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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;





/**
 * @author Matthias J. Sax
 */
public class SpoutOutputBatcherTest {
	private IRichSpout spoutMock;
	private SpoutOutputBatcher spout;
	
	private long seed;
	private Random r;
	
	
	
	@Before
	public void prepare() {
		this.seed = System.currentTimeMillis();
		this.r = new Random(this.seed);
		System.out.println("Test seed: " + this.seed);
		
		this.spoutMock = mock(IRichSpout.class);
		this.spout = new SpoutOutputBatcher(this.spoutMock, 2 + this.r.nextInt(8));
	}
	
	
	
	@Test
	public void testOpen() {
		@SuppressWarnings("rawtypes")
		Map conf = new HashMap();
		TopologyContext context = mock(TopologyContext.class);
		this.spout.open(conf, context, null);
		
		verify(this.spoutMock).open(same(conf), same(context), any(SpoutBatchCollector.class));
	}
	
	@Test
	public void testClose() {
		this.spout.close();
		verify(this.spoutMock).close();
	}
	
	@Test
	public void testActivate() {
		this.spout.activate();
		verify(this.spoutMock).activate();
	}
	
	@Test
	public void testDeactivate() {
		this.spout.deactivate();
		verify(this.spoutMock).deactivate();
	}
	
	@Test
	public void testAck() {
		Object messageId = mock(Object.class);
		this.spout.ack(messageId);
		verify(this.spoutMock).ack(messageId);
	}
	
	@Test
	public void testFail() {
		Object messageId = mock(Object.class);
		this.spout.fail(messageId);
		verify(this.spoutMock).fail(messageId);
	}
	
	@Test
	public void testDeclareOutputFields() {
		OutputFieldsDeclarer declarer = mock(OutputFieldsDeclarer.class);
		this.spout.declareOutputFields(declarer);
		verify(this.spoutMock).declareOutputFields(declarer);
	}
	
	@Test
	public void testGetComponentConfiguration() {
		final Map<String, Object> conf = new HashMap<String, Object>();
		when(this.spout.getComponentConfiguration()).thenReturn(conf);
		
		Map<String, Object> result = this.spout.getComponentConfiguration();
		
		verify(this.spoutMock).getComponentConfiguration();
		Assert.assertSame(result, conf);
	}
	
}
