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
package org.apache.solr.cloud.overseer;

import java.lang.invoke.MethodHandles;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.solr.client.solrj.cloud.autoscaling.DistribStateManager;
import org.apache.solr.client.solrj.cloud.autoscaling.SolrCloudManager;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.ImplicitDocRouter;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.Utils;
import org.apache.solr.handler.admin.CollectionsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.params.CommonParams.NAME;

public class CollectionMutator {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final SolrCloudManager dataProvider;
  protected final DistribStateManager stateManager;

  public CollectionMutator(SolrCloudManager dataProvider) {
    this.dataProvider = dataProvider;
    this.stateManager = dataProvider.getDistribStateManager();
  }

  public ZkWriteCommand createShard(final ClusterState clusterState, ZkNodeProps message) {
    String collectionName = message.getStr(ZkStateReader.COLLECTION_PROP);
    if (!checkCollectionKeyExistence(message)) return ZkStateWriter.NO_OP;
    String shardId = message.getStr(ZkStateReader.SHARD_ID_PROP);
    DocCollection collection = clusterState.getCollection(collectionName);
    Slice slice = collection.getSlice(shardId);
    if (slice == null) {
      Map<String, Replica> replicas = Collections.EMPTY_MAP;
      Map<String, Object> sliceProps = new HashMap<>();
      String shardRange = message.getStr(ZkStateReader.SHARD_RANGE_PROP);
      String shardState = message.getStr(ZkStateReader.SHARD_STATE_PROP);
      String shardParent = message.getStr(ZkStateReader.SHARD_PARENT_PROP);
      String shardParentZkSession = message.getStr("shard_parent_zk_session");
      String shardParentNode = message.getStr("shard_parent_node");
      sliceProps.put(Slice.RANGE, shardRange);
      sliceProps.put(ZkStateReader.STATE_PROP, shardState);
      if (shardParent != null) {
        sliceProps.put(Slice.PARENT, shardParent);
      }
      if (shardParentZkSession != null) {
        sliceProps.put("shard_parent_zk_session", shardParentZkSession);
      }
      if (shardParentNode != null)  {
        sliceProps.put("shard_parent_node", shardParentNode);
      }
      collection = updateSlice(collectionName, collection, new Slice(shardId, replicas, sliceProps));
      return new ZkWriteCommand(collectionName, collection);
    } else {
      log.error("Unable to create Shard: " + shardId + " because it already exists in collection: " + collectionName);
      return ZkStateWriter.NO_OP;
    }
  }

  public ZkWriteCommand deleteShard(final ClusterState clusterState, ZkNodeProps message) {
    final String sliceId = message.getStr(ZkStateReader.SHARD_ID_PROP);
    final String collection = message.getStr(ZkStateReader.COLLECTION_PROP);
    if (!checkCollectionKeyExistence(message)) return ZkStateWriter.NO_OP;

    log.info("Removing collection: " + collection + " shard: " + sliceId + " from clusterstate");

    DocCollection coll = clusterState.getCollection(collection);

    Map<String, Slice> newSlices = new LinkedHashMap<>(coll.getSlicesMap());
    newSlices.remove(sliceId);

    DocCollection newCollection = coll.copyWithSlices(newSlices);
    return new ZkWriteCommand(collection, newCollection);
  }

  public ZkWriteCommand modifyCollection(final ClusterState clusterState, ZkNodeProps message){
    if (!checkCollectionKeyExistence(message)) return ZkStateWriter.NO_OP;
    DocCollection coll = clusterState.getCollection(message.getStr(COLLECTION_PROP));
    Map<String, Object> m = coll.shallowCopy();
    boolean hasAnyOps = false;
    for (String prop : CollectionsHandler.MODIFIABLE_COLL_PROPS) {
      if(message.get(prop)!= null) {
        hasAnyOps = true;
        m.put(prop,message.get(prop));
      }
    }
    
    if(!hasAnyOps) {
      return ZkStateWriter.NO_OP;
    }
    
    return new ZkWriteCommand(coll.getName(),
        new DocCollection(coll.getName(),coll.getSlicesMap(),m,coll.getRouter(),coll.getZNodeVersion(),coll.getZNode()));
  }

  public static DocCollection updateSlice(String collectionName, DocCollection collection, Slice slice) {
    DocCollection newCollection = null;
    Map<String, Slice> slices;

    if (collection == null) {
      //  when updateSlice is called on a collection that doesn't exist, it's currently when a core is publishing itself
      // without explicitly creating a collection.  In this current case, we assume custom sharding with an "implicit" router.
      slices = new LinkedHashMap<>(1);
      slices.put(slice.getName(), slice);
      Map<String, Object> props = new HashMap<>(1);
      props.put(DocCollection.DOC_ROUTER, Utils.makeMap(NAME, ImplicitDocRouter.NAME));
      newCollection = new DocCollection(collectionName, slices, props, new ImplicitDocRouter());
    } else {
      slices = new LinkedHashMap<>(collection.getSlicesMap()); // make a shallow copy
      slices.put(slice.getName(), slice);
      newCollection = collection.copyWithSlices(slices);
    }

    return newCollection;
  }

  static boolean checkCollectionKeyExistence(ZkNodeProps message) {
    return checkKeyExistence(message, ZkStateReader.COLLECTION_PROP);
  }

  static boolean checkKeyExistence(ZkNodeProps message, String key) {
    String value = message.getStr(key);
    if (value == null || value.trim().length() == 0) {
      log.error("Skipping invalid Overseer message because it has no " + key + " specified: " + message);
      return false;
    }
    return true;
  }
}

