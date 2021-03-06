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

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.Config;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;





/**
 * {@link SpoutOutputBatcher} wraps a spout, buffers the spout's output tuples, and emits those tuples in batches. All
 * receiving bolts must be wrapped by an {@link InputDebatcher}.<br/>
 * <br/>
 * <strong>CAUTION:</strong>Calls to {@code .emit(...)} will return {@code null}, because the tuples might still be in
 * the output buffer and not transfered yet.<br />
 * <br />
 * <strong>CAUTION:</strong>Tuple acking, failing, and anchoring is currently not supported.
 * 
 * @author Matthias J. Sax
 */
public class SpoutOutputBatcher implements IRichSpout {
	private final static long serialVersionUID = -8627934412821417370L;
	
	private final static Logger logger = LoggerFactory.getLogger(SpoutOutputBatcher.class);
	
	/**
	 * The used {@link SpoutBatchCollector} that wraps the actual {@link SpoutOutputCollector}.
	 */
	private SpoutBatchCollector batchCollector;
	/**
	 * The spout that is wrapped.
	 */
	private final IRichSpout wrappedSpout;
	/**
	 * The size of the output batches to be sent.
	 */
	private final int batchSize;
	
	
	
	/**
	 * Instantiates a new {@link SpoutOutputBatcher} the emits the output of the given {@link IRichSpout} in batches of
	 * size {@code batchSize}.
	 * 
	 * @param spout
	 *            The original spout to be wrapped.
	 * @param batchSize
	 *            The size of the output batches to be built.
	 */
	public SpoutOutputBatcher(IRichSpout spout, int batchSize) {
		logger.debug("batchSize: {}", new Integer(batchSize));
		this.wrappedSpout = spout;
		this.batchSize = batchSize;
	}
	
	
	
	@Override
	public void open(@SuppressWarnings("rawtypes") Map conf, TopologyContext context, SpoutOutputCollector collector) {
		this.batchCollector = new SpoutBatchCollector(context, collector, this.batchSize);
		this.wrappedSpout.open(conf, context, this.batchCollector);
	}
	
	@Override
	public void close() {
		this.wrappedSpout.close();
		this.batchCollector.flush();
	}
	
	@Override
	public void activate() {
		this.wrappedSpout.activate();
	}
	
	@Override
	public void deactivate() {
		this.wrappedSpout.deactivate();
		this.batchCollector.flush();
	}
	
	@Override
	public void nextTuple() {
		/*
		 * In order to avoid a waiting penalty (because of a missing emit), we try to fill up a complete batch before
		 * returning. If the wrapped spout does not add a new tuple to an output batch we return as well in order to
		 * avoid busy waiting within the while-true-loop.
		 */
		while(true) {
			this.batchCollector.tupleEmitted = false;
			this.batchCollector.batchEmitted = false;
			
			this.wrappedSpout.nextTuple();
			
			if(!this.batchCollector.tupleEmitted || this.batchCollector.batchEmitted) {
				break;
			}
		}
	}
	
	@Override
	public void ack(Object msgId) {
		// TODO: receiving ack for whole batch -> reply acks for single tuples to user spout
		// TODO: store original Ids of all tuples for each batch
		this.wrappedSpout.ack(msgId);
	}
	
	@Override
	public void fail(Object msgId) {
		// TODO: receiving fail for whole batch -> reply fails for single tuples to user spout
		// TODO: store original Ids of all tuples for each batch
		this.wrappedSpout.fail(msgId);
	}
	
	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		this.wrappedSpout.declareOutputFields(new BatchingOutputFieldsDeclarer(declarer));
		
	}
	
	@Override
	public Map<String, Object> getComponentConfiguration() {
		return this.wrappedSpout.getComponentConfiguration();
	}
	
	/**
	 * Registers internally used classes for serialization and deserialization. Same as
	 * {@link BoltOutputBatcher#registerKryoClasses(Config)} and {@link InputDebatcher#registerKryoClasses(Config)}.
	 * 
	 * @param stormConfig
	 *            The storm config the which the classes should be registered to.
	 */
	public static void registerKryoClasses(Config stormConfig) {
		AbstractBatchCollector.registerKryoClasses(stormConfig);
	}
	
}
