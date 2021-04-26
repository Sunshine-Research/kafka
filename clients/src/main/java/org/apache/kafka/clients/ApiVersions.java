/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients;

import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.requests.ProduceRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 包含节点的api版本号，用于请求外部client
 */
public class ApiVersions {

    private final Map<String, NodeApiVersions> nodeApiVersions = new HashMap<>();
    private byte maxUsableProduceMagic = RecordBatch.CURRENT_MAGIC_VALUE;

    public synchronized void update(String nodeId, NodeApiVersions nodeApiVersions) {
        this.nodeApiVersions.put(nodeId, nodeApiVersions);
        this.maxUsableProduceMagic = computeMaxUsableProduceMagic();
    }

    public synchronized void remove(String nodeId) {
        this.nodeApiVersions.remove(nodeId);
        this.maxUsableProduceMagic = computeMaxUsableProduceMagic();
    }

    public synchronized NodeApiVersions get(String nodeId) {
        return this.nodeApiVersions.get(nodeId);
    }

    private byte computeMaxUsableProduceMagic() {
		// use a magic version which is supported by all brokers to reduce the chance that
		// we will need to convert the messages when they are ready to be sent.
		Optional<Byte> knownBrokerNodesMinRequiredMagicForProduce = this.nodeApiVersions.values().stream()
				.filter(versions -> versions.apiVersion(ApiKeys.PRODUCE) != null) // filter out Raft controller nodes
				.map(versions -> ProduceRequest.requiredMagicForVersion(versions.latestUsableVersion(ApiKeys.PRODUCE)))
				.min(Byte::compare);
		return (byte) Math.min(RecordBatch.CURRENT_MAGIC_VALUE,
				knownBrokerNodesMinRequiredMagicForProduce.orElse(RecordBatch.CURRENT_MAGIC_VALUE));
	}

    public synchronized byte maxUsableProduceMagic() {
        return maxUsableProduceMagic;
    }

}
