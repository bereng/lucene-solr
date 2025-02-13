/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.util.TestUtil;
import org.apache.solr.BaseDistributedSearchTestCase;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrRequestHandler;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SolrTestCaseJ4.SuppressSSL
public class TestRandomRequestDistribution extends AbstractFullDistribZkTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  List<String> nodeNames = new ArrayList<>(3);

  @Test
  @BaseDistributedSearchTestCase.ShardsFixed(num = 3)
  public void test() throws Exception {
    waitForThingsToLevelOut(30);

    for (CloudJettyRunner cloudJetty : cloudJettys) {
      nodeNames.add(cloudJetty.nodeName);
    }
    assertEquals(3, nodeNames.size());

    testRequestTracking();
    testQueryAgainstDownReplica();
  }

  /**
   * Asserts that requests aren't always sent to the same poor node. See SOLR-7493
   */
  private void testRequestTracking() throws Exception {

    new CollectionAdminRequest.Create()
        .setCollectionName("a1x2")
        .setNumShards(1)
        .setReplicationFactor(2)
        .setCreateNodeSet(nodeNames.get(0) + ',' + nodeNames.get(1))
        .process(cloudClient);

    new CollectionAdminRequest.Create()
        .setCollectionName("b1x1")
        .setNumShards(1)
        .setReplicationFactor(1)
        .setCreateNodeSet(nodeNames.get(2))
        .process(cloudClient);

    waitForRecoveriesToFinish("a1x2", true);
    waitForRecoveriesToFinish("b1x1", true);

    cloudClient.getZkStateReader().forceUpdateCollection("b1x1");

    ClusterState clusterState = cloudClient.getZkStateReader().getClusterState();
    DocCollection b1x1 = clusterState.getCollection("b1x1");
    Collection<Replica> replicas = b1x1.getSlice("shard1").getReplicas();
    assertEquals(1, replicas.size());
    String baseUrl = replicas.iterator().next().getStr(ZkStateReader.BASE_URL_PROP);
    if (!baseUrl.endsWith("/")) baseUrl += "/";
    try (HttpSolrClient client = new HttpSolrClient(baseUrl + "a1x2")) {
      client.setSoTimeout(5000);
      client.setConnectionTimeout(2000);

      log.info("Making requests to " + baseUrl + "a1x2");
      for (int i = 0; i < 10; i++) {
        client.query(new SolrQuery("*:*"));
      }
    }

    Map<String, Integer> shardVsCount = new HashMap<>();
    for (JettySolrRunner runner : jettys) {
      CoreContainer container = runner.getCoreContainer();
      for (SolrCore core : container.getCores()) {
        SolrRequestHandler select = core.getRequestHandler("");
        long c = (long) select.getStatistics().get("requests");
        shardVsCount.put(core.getName(), (int) c);
      }
    }

    log.info("Shard count map = " + shardVsCount);

    for (Map.Entry<String, Integer> entry : shardVsCount.entrySet()) {
      assertTrue("Shard " + entry.getKey() + " received all 10 requests", entry.getValue() != 10);
    }
  }

  /**
   * Asserts that requests against a collection are only served by a 'active' local replica
   */
  private void testQueryAgainstDownReplica() throws Exception {

    log.info("Creating collection 'football' with 1 shard and 2 replicas");
    new CollectionAdminRequest.Create()
        .setCollectionName("football")
        .setNumShards(1)
        .setReplicationFactor(2)
        .setCreateNodeSet(nodeNames.get(0) + ',' + nodeNames.get(1))
        .process(cloudClient);

    waitForRecoveriesToFinish("football", true);

    cloudClient.getZkStateReader().forceUpdateCollection("football");

    Replica leader = null;
    Replica notLeader = null;

    Collection<Replica> replicas = cloudClient.getZkStateReader().getClusterState().getSlice("football", "shard1").getReplicas();
    for (Replica replica : replicas) {
      if (replica.getStr(ZkStateReader.LEADER_PROP) != null) {
        leader = replica;
      } else {
        notLeader = replica;
      }
    }

    //Simulate a replica being in down state.
    ZkNodeProps m = new ZkNodeProps(Overseer.QUEUE_OPERATION, OverseerAction.STATE.toLower(),
        ZkStateReader.BASE_URL_PROP, notLeader.getStr(ZkStateReader.BASE_URL_PROP),
        ZkStateReader.NODE_NAME_PROP, notLeader.getStr(ZkStateReader.NODE_NAME_PROP),
        ZkStateReader.COLLECTION_PROP, "football",
        ZkStateReader.SHARD_ID_PROP, "shard1",
        ZkStateReader.CORE_NAME_PROP, notLeader.getStr(ZkStateReader.CORE_NAME_PROP),
        ZkStateReader.ROLES_PROP, "",
        ZkStateReader.STATE_PROP, Replica.State.DOWN.toString());

    log.info("Forcing {} to go into 'down' state", notLeader.getStr(ZkStateReader.CORE_NAME_PROP));
    DistributedQueue q = Overseer.getStateUpdateQueue(cloudClient.getZkStateReader().getZkClient());
    q.offer(Utils.toJSON(m));

    verifyReplicaStatus(cloudClient.getZkStateReader(), "football", "shard1", notLeader.getName(), Replica.State.DOWN);

    //Query against the node which hosts the down replica

    String baseUrl = notLeader.getStr(ZkStateReader.BASE_URL_PROP);
    if (!baseUrl.endsWith("/")) baseUrl += "/";
    String path = baseUrl + "football";
    log.info("Firing queries against path=" + path);
    try (HttpSolrClient client = new HttpSolrClient(path)) {
      client.setSoTimeout(5000);
      client.setConnectionTimeout(2000);

      SolrCore leaderCore = null;
      for (JettySolrRunner jetty : jettys) {
        CoreContainer container = jetty.getCoreContainer();
        for (SolrCore core : container.getCores()) {
          if (core.getName().equals(leader.getStr(ZkStateReader.CORE_NAME_PROP))) {
            leaderCore = core;
            break;
          }
        }
      }
      assertNotNull(leaderCore);

      // All queries should be served by the active replica
      // To make sure that's true we keep querying the down replica
      // If queries are getting processed by the down replica then the cluster state hasn't updated for that replica
      // locally
      // So we keep trying till it has updated and then verify if ALL queries go to the active reploca
      long count = 0;
      while (true) {
        count++;
        client.query(new SolrQuery("*:*"));

        SolrRequestHandler select = leaderCore.getRequestHandler("");
        long c = (long) select.getStatistics().get("requests");

        if (c == 1) {
          break; // cluster state has got update locally
        } else {
          Thread.sleep(100);
        }

        if (count > 10000) {
          fail("After 10k queries we still see all requests being processed by the down replica");
        }
      }

      // Now we fire a few additional queries and make sure ALL of them
      // are served by the active replica
      int moreQueries = TestUtil.nextInt(random(), 4, 10);
      count = 1; // Since 1 query has already hit the leader
      for (int i = 0; i < moreQueries; i++) {
        client.query(new SolrQuery("*:*"));
        count++;

        SolrRequestHandler select = leaderCore.getRequestHandler("");
        long c = (long) select.getStatistics().get("requests");

        assertEquals("Query wasn't served by leader", count, c);
      }
    }
  }
}
