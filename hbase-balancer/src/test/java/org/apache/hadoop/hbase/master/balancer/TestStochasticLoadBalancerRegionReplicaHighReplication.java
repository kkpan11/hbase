/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.balancer;

import java.time.Duration;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({ MasterTests.class, MediumTests.class })
public class TestStochasticLoadBalancerRegionReplicaHighReplication
  extends StochasticBalancerTestBase2 {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestStochasticLoadBalancerRegionReplicaHighReplication.class);

  @Test
  public void testRegionReplicasOnMidClusterHighReplication() {
    conf.setLong(StochasticLoadBalancer.MAX_STEPS_KEY, 4000000L);
    setMaxRunTime(Duration.ofSeconds(5));
    loadBalancer.onConfigurationChange(conf);
    int numNodes = 40;
    int numRegions = 6 * numNodes;
    int replication = 40; // 40 replicas per region, one for each server
    int numRegionsPerServer = 5;
    int numTables = 10;
    testWithClusterWithIteration(numNodes, numRegions, numRegionsPerServer, replication, numTables,
      false, true);
  }
}
