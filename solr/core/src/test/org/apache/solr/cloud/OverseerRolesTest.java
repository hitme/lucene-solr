package org.apache.solr.cloud;

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

import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrServer;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.apache.solr.cloud.OverseerCollectionProcessor.MAX_SHARDS_PER_NODE;
import static org.apache.solr.cloud.OverseerCollectionProcessor.NUM_SLICES;
import static org.apache.solr.cloud.OverseerCollectionProcessor.REPLICATION_FACTOR;
import static org.apache.solr.common.cloud.ZkNodeProps.makeMap;
import static org.apache.solr.common.params.CollectionParams.CollectionAction;

public class OverseerRolesTest  extends AbstractFullDistribZkTestBase{
  private CloudSolrServer client;

  @BeforeClass
  public static void beforeThisClass2() throws Exception {

  }

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty("numShards", Integer.toString(sliceCount));
    System.setProperty("solr.xml.persist", "true");
    client = createCloudClient(null);
  }

  @After
  public void tearDown() throws Exception {
    super.tearDown();
    client.shutdown();
  }

  protected String getSolrXml() {
    return "solr-no-core.xml";
  }

  public OverseerRolesTest() {
    fixShardCount = true;

    sliceCount = 2;
    shardCount = 6;

    checkCreatedVsState = false;
  }

  @Override
  public void doTest() throws Exception {
    addOverseerRole2ExistingNodes();

  }

  private void addOverseerRole2ExistingNodes() throws Exception {
    String collectionName = "testOverseerCol";

    createCollection(collectionName, client);

    waitForRecoveriesToFinish(collectionName, false);
    Set<String> nodes = client.getZkStateReader().getClusterState().getLiveNodes();

    ArrayList<String> l = new ArrayList<String>(nodes);
    log.info("All nodes {}", l);
    String currentLeader = getLeaderNode(client);
    log.info("Current leader {} ", currentLeader);
    l.remove(currentLeader);

    Collections.shuffle(l);
    String overseerDesignate = l.get(0);
    log.info("overseerDesignate {}",overseerDesignate);
    setOverseerRole(CollectionAction.ADDROLE,overseerDesignate);

    long timeout = System.currentTimeMillis()+10000;

    boolean leaderchanged = false;
    for(;System.currentTimeMillis() < timeout;){
      if(getLeaderNode(client).equals(overseerDesignate)){
        log.info("overseer designate is the new overseer");
        leaderchanged =true;
        break;
      }
      Thread.sleep(100);
    }
    assertTrue("could not set the new overseer",leaderchanged);



    //add another node as overseer


    l.remove(overseerDesignate);

    Collections.shuffle(l);

    String anotherOverseer = l.get(0);
    log.info("Adding another overseer designate {}", anotherOverseer);
    setOverseerRole(CollectionAction.ADDROLE, anotherOverseer);

    timeout = System.currentTimeMillis()+10000;
    leaderchanged = false;
    for(;System.currentTimeMillis() < timeout;){
      log.info(" count {}", System.currentTimeMillis());
      List<String> seqs = client.getZkStateReader().getZkClient().getChildren("/overseer_elect/election", null, true);
      LeaderElector.sortSeqs(seqs);

      log.info("seqs : {} ",seqs);
//
      if(LeaderElector.getNodeName(seqs.get(1)).equals(anotherOverseer)){
        leaderchanged =true;
        break;
      }
      Thread.sleep(100);
    }

    assertTrue("New overseer not the frontrunner", leaderchanged);


    client.shutdown();


  }

  private void setOverseerRole(CollectionAction action, String overseerDesignate) throws SolrServerException, IOException {
    log.info("Adding overseer designate {} ", overseerDesignate);
    Map m = makeMap(
        "action", action.toString().toLowerCase(Locale.ROOT),
        "role", "overseer",
        "node", overseerDesignate);
    SolrParams params = new MapSolrParams(m);
    SolrRequest request = new QueryRequest(params);
    request.setPath("/admin/collections");
    client.request(request);
  }

  private String getLeaderNode(CloudSolrServer client) throws KeeperException, InterruptedException {
    Map m = (Map) ZkStateReader.fromJSON(client.getZkStateReader().getZkClient().getData("/overseer_elect/leader", null, new Stat(), true));
    String s = (String) m.get("id");
//    log.info("leader-id {}",s);
    String nodeName = LeaderElector.getNodeName(s);
//    log.info("Leader {}", nodeName);
    return nodeName;
  }

  protected void createCollection(String COLL_NAME, CloudSolrServer client) throws Exception {
    int replicationFactor = 2;
    int numShards = 4;
    int maxShardsPerNode = ((((numShards+1) * replicationFactor) / getCommonCloudSolrServer()
        .getZkStateReader().getClusterState().getLiveNodes().size())) + 1;

    Map<String, Object> props = makeMap(
        REPLICATION_FACTOR, replicationFactor,
        MAX_SHARDS_PER_NODE, maxShardsPerNode,
        NUM_SLICES, numShards);
    Map<String,List<Integer>> collectionInfos = new HashMap<String,List<Integer>>();
    createCollection(collectionInfos, COLL_NAME, props, client);
  }


}
