/*
 * #!
 * %
 * Copyright (C) 2014 - 2015 Humboldt-Universität zu Berlin
 * %
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #_
 */
package de.hub.cs.dbis.aeolus.batching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.Config;
import backtype.storm.generated.Grouping;
import backtype.storm.task.TopologyContext;
import backtype.storm.tuple.Tuple;





/**
 * {@link AbstractBatchCollector} buffers emitted tuples in batches and emits full batches. It is used by
 * {@link SpoutBatchCollector} and {@link BoltBatchCollector}.
 * 
 * {@link AbstractBatchCollector} uses {@code de.hub.cs.dbis.aeolus.batching.StormConnector} which is provided as
 * jar-file. This jar file need to be build manually (see folder aeolus/aeolus-storm-connector).
 * 
 * @author Matthias J. Sax
 */
// TODO: what about batches of different sizes (for different output streams? or for different consumers?)
abstract class AbstractBatchCollector {
	protected final static Logger logger = LoggerFactory.getLogger(AbstractBatchCollector.class);
	
	/**
	 * The size of the output batches.
	 */
	private final int batchSize;
	/**
	 * The number of the attributes of the output schema.
	 */
	private final Map<String, Integer> numberOfAttributes = new HashMap<String, Integer>();
	/**
	 * The current runtime environment.
	 */
	private final TopologyContext topologyContext;
	/**
	 * The ID of the producer operator which output is buffered by this {@link AbstractBatchCollector}.
	 */
	private final String componentId;
	/**
	 * Maps output streams to their receivers.
	 */
	private final Map<String, List<String>> receivers = new HashMap<String, List<String>>();
	/**
	 * Contains all receivers, that use fields-grouping.
	 */
	private final Set<String> fieldsGroupingReceivers = new HashSet<String>();
	/**
	 * Maps each output stream to a task-id-to-batch-index map.
	 * 
	 * Task-IDs can have arbitrary values but we need values from 0 to number-of-batches. Thus, we assign an appropriate
	 * index value to each task-ID.
	 */
	private final Map<String, Map<Integer, Integer>> streamBatchIndexMapping = new HashMap<String, Map<Integer, Integer>>();
	/**
	 * Maps output streams to corresponding output buffers.
	 * 
	 * The number of outputBuffers depends on the number of logical receivers as well as each logical receiver's
	 * distribution pattern and parallelism.
	 */
	private final Map<String, Batch[]> outputBuffers = new HashMap<String, Batch[]>();
	/**
	 * Maps direct output streams to corresponding output buffers. Each consumer task has its own output buffer.
	 */
	private final Map<String, Map<Integer, Batch>> directOutputBuffers = new HashMap<String, Map<Integer, Batch>>();
	/**
	 * Assigns a "weight" to each receiver the used fields-grouping. This weight is necessary to compute the correct
	 * index within the list of output buffers.
	 */
	private final Map<String, Integer> weights = new HashMap<String, Integer>();
	
	
	
	/**
	 * Creates a new {@link AbstractBatchCollector} that emits batches of size {@code batchSize}.
	 * 
	 * @param context
	 *            The current runtime environment.
	 * @param batchSize
	 *            The size of the output batches to be built.
	 */
	AbstractBatchCollector(TopologyContext context, int batchSize) {
		logger.trace("batchSize: {}", new Integer(batchSize));
		
		this.batchSize = batchSize;
		this.topologyContext = context;
		this.componentId = context.getThisComponentId();
		logger.trace("this-id: {}", this.componentId);
		
		// StreamId -> ReceiverId -> Grouping
		for(Entry<String, Map<String, Grouping>> outputStream : context.getThisTargets().entrySet()) {
			final String streamId = outputStream.getKey();
			logger.trace("output-stream: {}", streamId);
			final Map<String, Grouping> streamReceivers = outputStream.getValue();
			
			final int numAttributes = context.getComponentOutputFields(this.componentId, streamId).size();
			this.numberOfAttributes.put(streamId, new Integer(numAttributes));
			
			int numberOfBatches = 1;
			final ArrayList<String> receiverIds = new ArrayList<String>(streamReceivers.size());
			this.receivers.put(streamId, receiverIds);
			
			for(Entry<String, Grouping> receiver : streamReceivers.entrySet()) {
				final String receiverId = receiver.getKey();
				receiverIds.add(receiverId);
				final List<Integer> taskIds = context.getComponentTasks(receiverId);
				logger.trace("receiver and tasks: {} - {}", receiverId, taskIds);
				if(receiver.getValue().is_set_direct()) {
					logger.trace("directGrouping");
					
					Map<Integer, Batch> outputBatches = this.directOutputBuffers.get(streamId);
					if(outputBatches == null) {
						outputBatches = new HashMap<Integer, Batch>();
						this.directOutputBuffers.put(streamId, outputBatches);
					}
					for(Integer taskId : taskIds) {
						outputBatches.put(taskId, new Batch(batchSize, numAttributes));
					}
					
					numberOfBatches = 0; // mark as direct output stream
				} else if(receiver.getValue().is_set_fields()) {
					// do not consider as regular fieldsGrouping if emulated by directGrouping
					for(Entry<String, Map<String, Grouping>> outputStream2 : context.getThisTargets().entrySet()) {
						if(outputStream2.getKey().equals(BatchingOutputFieldsDeclarer.STREAM_PREFIX + streamId)) {
							final Map<String, Grouping> streamReceivers2 = outputStream2.getValue();
							for(Entry<String, Grouping> receiver2 : streamReceivers2.entrySet()) {
								if(receiver2.getKey().equals(receiverId)) {
									assert (receiver2.getValue().is_set_direct());
									numberOfBatches = 0; // mark as emulated via direct output stream
								}
							}
						}
					}
					
					if(numberOfBatches != 0) {
						// TODO we could reduce number of output buffers, if two logical consumers use the same output
						// field for partitioning AND have the same dop
						this.fieldsGroupingReceivers.add(receiverId);
						logger.trace("fieldsGrouping");
						
						this.weights.put(receiverId, new Integer(numberOfBatches));
						numberOfBatches *= taskIds.size();
						
						Map<Integer, Integer> taskToIndex = this.streamBatchIndexMapping.get(streamId);
						if(taskToIndex == null) {
							taskToIndex = new HashMap<Integer, Integer>();
							this.streamBatchIndexMapping.put(streamId, taskToIndex);
						}
						int i = 0;
						for(Integer tId : taskIds) {
							taskToIndex.put(tId, new Integer(i));
							++i;
						}
					}
				}
			}
			
			if(numberOfBatches > 0) { // otherwise, we got a direct output stream and this.directOutputBuffers is
										// already set up
				Batch[] batches = new Batch[numberOfBatches];
				for(int i = 0; i < numberOfBatches; ++i) {
					batches[i] = new Batch(batchSize, numAttributes);
				}
				this.outputBuffers.put(streamId, batches);
			}
		}
	}
	
	/**
	 * Captures an regular emit call of an operator, adds the output tuple to the corresponding output buffer, and emits
	 * the buffer if it gets filled completely during this call.
	 * 
	 * @param streamId
	 *            The name of the output stream the tuple is appended.
	 * @param anchors
	 *            The anchor tuples of the emitted tuple (bolts only).
	 * @param tuple
	 *            The output tuple to be emitted.
	 * @param messageId
	 *            The ID of the output tuple (spouts only).
	 * 
	 * @return currently {@code null} is returned, because the receiver task IDs cannot be determined if it is only
	 *         inserted into an output batch but not actual emit happens
	 */
	public List<Integer> tupleEmit(String streamId, Collection<Tuple> anchors, List<Object> tuple, Object messageId) {
		int bufferIndex = 0;
		
		final Map<Integer, Integer> taskIndex = this.streamBatchIndexMapping.get(streamId);
		if(taskIndex != null) {
			for(String receiverComponentId : this.receivers.get(streamId)) {
				if(this.fieldsGroupingReceivers.contains(receiverComponentId)) {
					Integer taskId = StormConnector.getFieldsGroupingReceiverTaskId(this.topologyContext,
						this.componentId, streamId, receiverComponentId, tuple);
					bufferIndex += (this.weights.get(receiverComponentId).intValue() * taskIndex.get(taskId).intValue());
				}
			}
		}
		String directStream = BatchingOutputFieldsDeclarer.STREAM_PREFIX + streamId;
		if(this.directOutputBuffers.containsKey(directStream)) {
			// emulate fieldsGrouping by direct emit
			for(String receiverComponentId : this.receivers.get(directStream)) {
				int taskId = StormConnector.getFieldsGroupingReceiverTaskId(this.topologyContext, this.componentId,
					streamId, receiverComponentId, tuple).intValue();
				this.tupleEmitDirect(taskId, directStream, anchors, tuple, messageId);
			}
		} else {
			// regular fieldsGrouping
			Batch[] streamBuffers = this.outputBuffers.get(streamId);
			if(streamBuffers != null) {
				final Batch buffer = streamBuffers[bufferIndex];
				buffer.addTuple(tuple);
				
				if(buffer.isFull()) {
					this.batchEmit(streamId, null, buffer, null);
					this.outputBuffers.get(streamId)[bufferIndex] = new Batch(this.batchSize, this.numberOfAttributes
						.get(streamId).intValue());
				}
			}
		}
		
		return null;
	}
	
	/**
	 * Captures an regular direct-emit call of an operator, adds the output tuple to the corresponding output buffer,
	 * and emits the buffer if it gets filled completely during this call.
	 * 
	 * @param taskId
	 *            The ID of the receiver task.
	 * 
	 * @param streamId
	 *            The name of the output stream the tuple is appended.
	 * 
	 * @param anchors
	 *            The anchor tuples of the emitted tuple (bolts only).
	 * 
	 * @param tuple
	 *            The output tuple to be emitted.
	 * 
	 * @param messageId
	 *            The ID of the output tuple (spouts only).
	 */
	public void tupleEmitDirect(int taskId, String streamId, Collection<Tuple> anchors, List<Object> tuple, Object messageId) {
		Integer tid = new Integer(taskId);
		final Map<Integer, Batch> streamBuffers = this.directOutputBuffers.get(streamId);
		if(streamBuffers != null) {
			final Batch buffer = streamBuffers.get(tid);
			if(buffer != null) {
				buffer.addTuple(tuple);
				
				if(buffer.isFull()) {
					this.batchEmitDirect(taskId, streamId, null, buffer, null);
					this.directOutputBuffers.get(streamId).put(tid,
						new Batch(this.batchSize, this.numberOfAttributes.get(streamId).intValue()));
				}
			}
		}
	}
	
	/**
	 * Emits all incomplete batches from the output buffer.
	 */
	public void flush() {
		for(String streamId : this.outputBuffers.keySet()) {
			for(int i = 0; i < this.outputBuffers.get(streamId).length; ++i) {
				Batch batch = this.outputBuffers.get(streamId)[i];
				if(!batch.isEmpty()) {
					this.batchEmit(streamId, null, batch, null);
					this.outputBuffers.get(streamId)[i] = new Batch(this.batchSize, this.numberOfAttributes.get(
						streamId).intValue());
				}
			}
		}
		
		for(String streamId : this.directOutputBuffers.keySet()) {
			for(Integer taskId : this.directOutputBuffers.get(streamId).keySet()) {
				Batch batch = this.directOutputBuffers.get(streamId).get(taskId);
				if(!batch.isEmpty()) {
					this.batchEmitDirect(taskId.intValue(), streamId, null, batch, null);
					this.directOutputBuffers.get(streamId).put(taskId,
						new Batch(this.batchSize, this.numberOfAttributes.get(streamId).intValue()));
				}
				
			}
		}
	}
	
	/**
	 * Registers the classes {@link Batch Batch.class} and {@link BatchColumn BatchColumn.class} for serialization and
	 * deserialization.
	 * 
	 * @param stormConfig
	 *            The storm config the which the classes should be registered to.
	 */
	static void registerKryoClasses(Config stormConfig) {
		stormConfig.registerSerialization(Batch.class);
		stormConfig.registerSerialization(BatchColumn.class);
	}
	
	/**
	 * Is called each time a batch is full and should be emitted.
	 * 
	 * @param streamId
	 *            The name of the output stream the batch is appended.
	 * @param anchors
	 *            The anchor tuples of the emitted batch (bolts only).
	 * @param batch
	 *            The output batch to be emitted.
	 * @param messageId
	 *            The ID of the output batch (spouts only).
	 * 
	 * @return the task IDs that received the batch
	 */
	protected abstract List<Integer> batchEmit(String streamId, Collection<Tuple> anchors, Batch batch, Object messageId);
	
	/**
	 * Is called each time a batch is full and should be emitted.
	 * 
	 * @param taskId
	 *            The ID of the receiver task.
	 * @param streamId
	 *            The name of the output stream the batch is appended.
	 * @param anchors
	 *            The anchor tuples of the emitted batch (bolts only).
	 * @param batch
	 *            The output batch to be emitted.
	 * @param messageId
	 *            The ID of the output batch (spouts only).
	 */
	protected abstract void batchEmitDirect(int taskId, String streamId, Collection<Tuple> anchors, Batch batch, Object messageId);
	
}
