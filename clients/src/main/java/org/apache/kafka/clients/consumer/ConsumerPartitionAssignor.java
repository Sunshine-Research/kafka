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
package org.apache.kafka.clients.consumer;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.TopicPartition;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * This interface is used to define custom partition assignment for use in
 * {@link org.apache.kafka.clients.consumer.KafkaConsumer}. Members of the consumer group subscribe
 * to the topics they are interested in and forward their subscriptions to a Kafka broker serving
 * as the group coordinator. The coordinator selects one member to perform the group assignment and
 * propagates the subscriptions of all members to it. Then {@link #assign(Cluster, GroupSubscription)} is called
 * to perform the assignment and the results are forwarded back to each respective members
 *
 * In some cases, it is useful to forward additional metadata to the assignor in order to make
 * assignment decisions. For this, you can override {@link #subscriptionUserData(Set)} and provide custom
 * userData in the returned Subscription. For example, to have a rack-aware assignor, an implementation
 * can use this user data to forward the rackId belonging to each member.
 */
public interface ConsumerPartitionAssignor {

    /**
     * Return serialized data that will be included in the {@link Subscription} sent to the leader
     * and can be leveraged in {@link #assign(Cluster, GroupSubscription)} ((e.g. local host/rack information)
     *
     * @param topics Topics subscribed to through {@link org.apache.kafka.clients.consumer.KafkaConsumer#subscribe(java.util.Collection)}
     *               and variants
     * @return nullable subscription user data
     */
    default ByteBuffer subscriptionUserData(Set<String> topics) {
        return null;
    }

    /**
     * Perform the group assignment given the member subscriptions and current cluster metadata.
     * @param metadata Current topic/broker metadata known by consumer
     * @param groupSubscription Subscriptions from all members including metadata provided through {@link #subscriptionUserData(Set)}
     * @return A map from the members to their respective assignments. This should have one entry
     *         for each member in the input subscription map.
     */
    GroupAssignment assign(Cluster metadata, GroupSubscription groupSubscription);

    /**
     * Callback which is invoked when a group member receives its assignment from the leader.
     * @param assignment The local member's assignment as provided by the leader in {@link #assign(Cluster, GroupSubscription)}
     * @param metadata Additional metadata on the consumer (optional)
     */
    default void onAssignment(Assignment assignment, ConsumerGroupMetadata metadata) {
    }

    /**
     * Indicate which rebalance protocol this assignor works with;
     * By default it should always work with {@link RebalanceProtocol#EAGER}.
     */
    default List<RebalanceProtocol> supportedProtocols() {
        return Collections.singletonList(RebalanceProtocol.EAGER);
    }

    /**
     * Return the version of the assignor which indicates how the user metadata encodings
     * and the assignment algorithm gets evolved.
     */
    default short version() {
        return (short) 0;
    }

    /**
     * Unique name for this assignor (e.g. "range" or "roundrobin" or "sticky"). Note, this is not required
     * to be the same as the class name specified in {@link ConsumerConfig#PARTITION_ASSIGNMENT_STRATEGY_CONFIG}
     * @return non-null unique name
     */
    String name();

    final class Subscription {
        private final List<String> topics;
        private final ByteBuffer userData;
        private final List<TopicPartition> ownedPartitions;
        private Optional<String> groupInstanceId;

        public Subscription(List<String> topics, ByteBuffer userData, List<TopicPartition> ownedPartitions) {
            this.topics = topics;
            this.userData = userData;
            this.ownedPartitions = ownedPartitions;
            this.groupInstanceId = Optional.empty();
        }

        public Subscription(List<String> topics, ByteBuffer userData) {
            this(topics, userData, Collections.emptyList());
        }

        public Subscription(List<String> topics) {
            this(topics, null, Collections.emptyList());
        }

        public List<String> topics() {
            return topics;
        }

        public ByteBuffer userData() {
            return userData;
        }

        public List<TopicPartition> ownedPartitions() {
            return ownedPartitions;
        }

        public void setGroupInstanceId(Optional<String> groupInstanceId) {
            this.groupInstanceId = groupInstanceId;
        }

        public Optional<String> groupInstanceId() {
            return groupInstanceId;
        }
    }

	/**
	 * 分配
	 */
	final class Assignment {
		/**
		 * 分配的partition
		 */
		private List<TopicPartition> partitions;

		private ByteBuffer userData;

		public Assignment(List<TopicPartition> partitions, ByteBuffer userData) {
			this.partitions = partitions;
			this.userData = userData;
		}

		public Assignment(List<TopicPartition> partitions) {
			this(partitions, null);
		}

		public List<TopicPartition> partitions() {
			return partitions;
		}

		public ByteBuffer userData() {
			return userData;
		}
	}

	/**
	 * 当前消费组的订阅信息
	 */
	final class GroupSubscription {
		private final Map<String, Subscription> subscriptions;

		/**
		 * memberId-订阅信息
		 */
		public GroupSubscription(Map<String, Subscription> subscriptions) {
			this.subscriptions = subscriptions;
		}

		public Map<String, Subscription> groupSubscription() {
			return subscriptions;
		}
	}

	/**
	 * 集群的管理信息
	 */
	final class GroupAssignment {
		/**
		 * memberId-分配partition信息
		 */
		private final Map<String, Assignment> assignments;

		public GroupAssignment(Map<String, Assignment> assignments) {
			this.assignments = assignments;
		}

		public Map<String, Assignment> groupAssignment() {
			return assignments;
		}
	}

    /**
	 * 再平衡协议声明了partition分配方式和撤销语义，宗旨是建立一个一致性的规则集合，所有的consumer都会在一个组中，按照顺序来在partition中传递关系
	 * {@link ConsumerPartitionAssignor}实现，可以声明支持一个或者多个再平衡协议和{@link ConsumerPartitionAssignor#supportedProtocols()}
	 * 并且在{@link ConsumerPartitionAssignor#assign(Cluster, GroupSubscription)}实现中尊重协议规则是它们的责任
	 * 没有遵循支持协议的规则将会产生运行时错误和未定义的表现
	 *
	 * {@link RebalanceProtocol#EAGER}再平衡协议需要一个consumer在参与平衡事件之前，来撤销所有已拥有的partition信息，因此，它允许进行重新分配
	 * {@link RebalanceProtocol#COOPERATIVE}再平衡协议允许consumer在参与平衡事件之前，保留当前拥有的partition，分配器不会立即重新分配已拥有的partition
	 * 但是相反，这可能表明了consumer需要撤销partition，以便于在下一次再平衡事件中能将已撤销的partition重新非配给其他使用者
     */
    enum RebalanceProtocol {
		/**
		 * 独占
		 */
		EAGER((byte) 0),
		/**
		 * 协作
		 */
		COOPERATIVE((byte) 1);

        private final byte id;

        RebalanceProtocol(byte id) {
            this.id = id;
        }

        public byte id() {
            return id;
        }

        public static RebalanceProtocol forId(byte id) {
            switch (id) {
                case 0:
                    return EAGER;
                case 1:
                    return COOPERATIVE;
                default:
                    throw new IllegalArgumentException("Unknown rebalance protocol id: " + id);
            }
        }
    }

}
