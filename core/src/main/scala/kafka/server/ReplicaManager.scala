/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.server

import java.io.File
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.locks.Lock

import com.yammer.metrics.core.{Gauge, Meter}
import kafka.api._
import kafka.cluster.{BrokerEndPoint, Partition}
import kafka.controller.{KafkaController, StateChangeLogger}
import kafka.log._
import kafka.metrics.KafkaMetricsGroup
import kafka.server.QuotaFactory.QuotaManagers
import kafka.server.checkpoints.{LazyOffsetCheckpoints, OffsetCheckpointFile, OffsetCheckpoints}
import kafka.utils._
import kafka.zk.KafkaZkClient
import org.apache.kafka.common.errors._
import org.apache.kafka.common.internals.Topic
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record.FileRecords.TimestampAndOffset
import org.apache.kafka.common.record._
import org.apache.kafka.common.replica.PartitionView.DefaultPartitionView
import org.apache.kafka.common.replica.ReplicaView.DefaultReplicaView
import org.apache.kafka.common.replica.{ClientMetadata, _}
import org.apache.kafka.common.requests.DescribeLogDirsResponse.{LogDirInfo, ReplicaInfo}
import org.apache.kafka.common.requests.EpochEndOffset._
import org.apache.kafka.common.requests.FetchRequest.PartitionData
import org.apache.kafka.common.requests.FetchResponse.AbortedTransaction
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.apache.kafka.common.requests._
import org.apache.kafka.common.utils.Time
import org.apache.kafka.common.{ElectionType, Node, TopicPartition}

import scala.collection.JavaConverters._
import scala.collection.{Map, Seq, Set, mutable}
import scala.compat.java8.OptionConverters._

/*
 * Result metadata of a log append operation on the log
 */
case class LogAppendResult(info: LogAppendInfo, exception: Option[Throwable] = None) {
  def error: Errors = exception match {
    case None => Errors.NONE
    case Some(e) => Errors.forException(e)
  }
}

case class LogDeleteRecordsResult(requestedOffset: Long, lowWatermark: Long, exception: Option[Throwable] = None) {
  def error: Errors = exception match {
    case None => Errors.NONE
    case Some(e) => Errors.forException(e)
  }
}

/*
 * Result metadata of a log read operation on the log
 * @param info @FetchDataInfo returned by the @Log read
 * @param hw high watermark of the local replica
 * @param readSize amount of data that was read from the log i.e. size of the fetch
 * @param isReadFromLogEnd true if the request read up to the log end offset snapshot
 *                         when the read was initiated, false otherwise
 * @param preferredReadReplica the preferred read replica to be used for future fetches
 * @param exception Exception if error encountered while reading from the log
 */
case class LogReadResult(info: FetchDataInfo,
                         highWatermark: Long,
                         leaderLogStartOffset: Long,
                         leaderLogEndOffset: Long,
                         followerLogStartOffset: Long,
                         fetchTimeMs: Long,
                         readSize: Int,
                         lastStableOffset: Option[Long],
                         preferredReadReplica: Option[Int] = None,
                         followerNeedsHwUpdate: Boolean = false,
                         exception: Option[Throwable] = None) {

  def error: Errors = exception match {
    case None => Errors.NONE
    case Some(e) => Errors.forException(e)
  }

  def withEmptyFetchInfo: LogReadResult =
    copy(info = FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MemoryRecords.EMPTY))

  override def toString =
    s"Fetch Data: [$info], HW: [$highWatermark], leaderLogStartOffset: [$leaderLogStartOffset], leaderLogEndOffset: [$leaderLogEndOffset], " +
    s"followerLogStartOffset: [$followerLogStartOffset], fetchTimeMs: [$fetchTimeMs], readSize: [$readSize], lastStableOffset: [$lastStableOffset], error: [$error]"

}

case class FetchPartitionData(error: Errors = Errors.NONE,
                              highWatermark: Long,
                              logStartOffset: Long,
                              records: Records,
                              lastStableOffset: Option[Long],
                              abortedTransactions: Option[List[AbortedTransaction]],
                              preferredReadReplica: Option[Int])


/**
 * 托管partition状态的接口
 * 在broker收到一个来自控制器的LeaderAndIsr请求时，创建一个具体（活跃的）partition实例
 * 这证明当前broker应该是partition的leader或者follower
 */
sealed trait HostedPartition
object HostedPartition {

  /**
   * broker没有本地partition的任何状态
   */
  final object None extends HostedPartition

  /**
   * broker上托管了partition并且partition处于在线状态
   */
  final case class Online(partition: Partition) extends HostedPartition

  /**
   * broker上托管了partition，但是是一个下线的日志目录
   */
  final object Offline extends HostedPartition
}

object ReplicaManager {
  val HighWatermarkFilename = "replication-offset-checkpoint"
  val IsrChangePropagationBlackOut = 5000L
  val IsrChangePropagationInterval = 60000L
}

class ReplicaManager(val config: KafkaConfig,
                     metrics: Metrics,
                     time: Time,
                     val zkClient: KafkaZkClient,
                     scheduler: Scheduler,
                     val logManager: LogManager,
                     val isShuttingDown: AtomicBoolean,
                     quotaManagers: QuotaManagers,
                     val brokerTopicStats: BrokerTopicStats,
                     val metadataCache: MetadataCache,
                     logDirFailureChannel: LogDirFailureChannel,
                     val delayedProducePurgatory: DelayedOperationPurgatory[DelayedProduce],
                     val delayedFetchPurgatory: DelayedOperationPurgatory[DelayedFetch],
                     val delayedDeleteRecordsPurgatory: DelayedOperationPurgatory[DelayedDeleteRecords],
                     val delayedElectLeaderPurgatory: DelayedOperationPurgatory[DelayedElectLeader],
                     threadNamePrefix: Option[String]) extends Logging with KafkaMetricsGroup {

  def this(config: KafkaConfig,
           metrics: Metrics,
           time: Time,
           zkClient: KafkaZkClient,
           scheduler: Scheduler,
           logManager: LogManager,
           isShuttingDown: AtomicBoolean,
           quotaManagers: QuotaManagers,
           brokerTopicStats: BrokerTopicStats,
           metadataCache: MetadataCache,
           logDirFailureChannel: LogDirFailureChannel,
           threadNamePrefix: Option[String] = None) {
    this(config, metrics, time, zkClient, scheduler, logManager, isShuttingDown,
      quotaManagers, brokerTopicStats, metadataCache, logDirFailureChannel,
      DelayedOperationPurgatory[DelayedProduce](
        purgatoryName = "Produce", brokerId = config.brokerId,
        purgeInterval = config.producerPurgatoryPurgeIntervalRequests),
      DelayedOperationPurgatory[DelayedFetch](
        purgatoryName = "Fetch", brokerId = config.brokerId,
        purgeInterval = config.fetchPurgatoryPurgeIntervalRequests),
      DelayedOperationPurgatory[DelayedDeleteRecords](
        purgatoryName = "DeleteRecords", brokerId = config.brokerId,
        purgeInterval = config.deleteRecordsPurgatoryPurgeIntervalRequests),
      DelayedOperationPurgatory[DelayedElectLeader](
        purgatoryName = "ElectLeader", brokerId = config.brokerId),
      threadNamePrefix)
  }

  /* epoch of the controller that last changed the leader */
  @volatile var controllerEpoch: Int = KafkaController.InitialControllerEpoch
  private val localBrokerId = config.brokerId
  /**
   * 所有partition的
   */
  private val allPartitions = new Pool[TopicPartition, HostedPartition](
    valueFactory = Some(tp => HostedPartition.Online(Partition(tp, time, this)))
  )
  private val replicaStateChangeLock = new Object
  val replicaFetcherManager = createReplicaFetcherManager(metrics, time, threadNamePrefix, quotaManagers.follower)
  val replicaAlterLogDirsManager = createReplicaAlterLogDirsManager(quotaManagers.alterLogDirs, brokerTopicStats)
  private val highWatermarkCheckPointThreadStarted = new AtomicBoolean(false)
  @volatile var highWatermarkCheckpoints: Map[String, OffsetCheckpointFile] = logManager.liveLogDirs.map(dir =>
    (dir.getAbsolutePath, new OffsetCheckpointFile(new File(dir, ReplicaManager.HighWatermarkFilename), logDirFailureChannel))).toMap

  this.logIdent = s"[ReplicaManager broker=$localBrokerId] "
  private val stateChangeLogger = new StateChangeLogger(localBrokerId, inControllerContext = false, None)

  private val isrChangeSet: mutable.Set[TopicPartition] = new mutable.HashSet[TopicPartition]()
  private val lastIsrChangeMs = new AtomicLong(System.currentTimeMillis())
  private val lastIsrPropagationMs = new AtomicLong(System.currentTimeMillis())

  private var logDirFailureHandler: LogDirFailureHandler = null

  private class LogDirFailureHandler(name: String, haltBrokerOnDirFailure: Boolean) extends ShutdownableThread(name) {
    override def doWork(): Unit = {
      val newOfflineLogDir = logDirFailureChannel.takeNextOfflineLogDir()
      if (haltBrokerOnDirFailure) {
        fatal(s"Halting broker because dir $newOfflineLogDir is offline")
        Exit.halt(1)
      }
      handleLogDirFailure(newOfflineLogDir)
    }
  }

  /**
   * 副本节点选择器
   */
  val replicaSelectorOpt: Option[ReplicaSelector] = createReplicaSelector()

  val leaderCount = newGauge(
    "LeaderCount",
    new Gauge[Int] {
      def value = leaderPartitionsIterator.size
    }
  )
  val partitionCount = newGauge(
    "PartitionCount",
    new Gauge[Int] {
      def value = allPartitions.size
    }
  )
  val offlineReplicaCount = newGauge(
    "OfflineReplicaCount",
    new Gauge[Int] {
      def value = offlinePartitionCount
    }
  )
  val underReplicatedPartitions = newGauge(
    "UnderReplicatedPartitions",
    new Gauge[Int] {
      def value = underReplicatedPartitionCount
    }
  )
  val underMinIsrPartitionCount = newGauge(
    "UnderMinIsrPartitionCount",
    new Gauge[Int] {
      def value = leaderPartitionsIterator.count(_.isUnderMinIsr)
    }
  )
  val atMinIsrPartitionCount = newGauge(
    "AtMinIsrPartitionCount",
    new Gauge[Int] {
      def value = leaderPartitionsIterator.count(_.isAtMinIsr)
    }
  )

  val isrExpandRate: Meter = newMeter("IsrExpandsPerSec", "expands", TimeUnit.SECONDS)
  val isrShrinkRate: Meter = newMeter("IsrShrinksPerSec", "shrinks", TimeUnit.SECONDS)
  val failedIsrUpdatesRate: Meter = newMeter("FailedIsrUpdatesPerSec", "failedUpdates", TimeUnit.SECONDS)

  def underReplicatedPartitionCount: Int = leaderPartitionsIterator.count(_.isUnderReplicated)

  /**
   * 开启HighWaterMark检查工作线程
   * @return
   */
  def startHighWatermarkCheckPointThread() = {
    if (highWatermarkCheckPointThreadStarted.compareAndSet(false, true))
    // 调度器进行调度
      scheduler.schedule("highwatermark-checkpoint", checkpointHighWatermarks _, period = config.replicaHighWatermarkCheckpointIntervalMs, unit = TimeUnit.MILLISECONDS)
  }

  /**
   * 记录ISR变化
   * @param topicPartition topic-partition信息
   */
  def recordIsrChange(topicPartition: TopicPartition): Unit = {
    isrChangeSet synchronized {
      isrChangeSet += topicPartition
      lastIsrChangeMs.set(System.currentTimeMillis())
    }
  }

  /**
   * This function periodically runs to see if ISR needs to be propagated. It propagates ISR when:
   * 1. There is ISR change not propagated yet.
   * 2. There is no ISR Change in the last five seconds, or it has been more than 60 seconds since the last ISR propagation.
   * This allows an occasional ISR change to be propagated within a few seconds, and avoids overwhelming controller and
   * other brokers when large amount of ISR change occurs.
   */
  def maybePropagateIsrChanges(): Unit = {
    val now = System.currentTimeMillis()
    isrChangeSet synchronized {
      if (isrChangeSet.nonEmpty &&
        (lastIsrChangeMs.get() + ReplicaManager.IsrChangePropagationBlackOut < now ||
          lastIsrPropagationMs.get() + ReplicaManager.IsrChangePropagationInterval < now)) {
        zkClient.propagateIsrChanges(isrChangeSet)
        isrChangeSet.clear()
        lastIsrPropagationMs.set(now)
      }
    }
  }

  // When ReplicaAlterDirThread finishes replacing a current replica with a future replica, it will
  // remove the partition from the partition state map. But it will not close itself even if the
  // partition state map is empty. Thus we need to call shutdownIdleReplicaAlterDirThread() periodically
  // to shutdown idle ReplicaAlterDirThread
  def shutdownIdleReplicaAlterLogDirsThread(): Unit = {
    replicaAlterLogDirsManager.shutdownIdleFetcherThreads()
  }

  def getLog(topicPartition: TopicPartition): Option[Log] = logManager.getLog(topicPartition)

  def hasDelayedElectionOperations: Boolean = delayedElectLeaderPurgatory.numDelayed != 0

  def tryCompleteElection(key: DelayedOperationKey): Unit = {
    val completed = delayedElectLeaderPurgatory.checkAndComplete(key)
    debug("Request key %s unblocked %d ElectLeader.".format(key.keyLabel, completed))
  }

  def startup(): Unit = {
    // start ISR expiration thread
    // A follower can lag behind leader for up to config.replicaLagTimeMaxMs x 1.5 before it is removed from ISR
    scheduler.schedule("isr-expiration", maybeShrinkIsr _, period = config.replicaLagTimeMaxMs / 2, unit = TimeUnit.MILLISECONDS)
    scheduler.schedule("isr-change-propagation", maybePropagateIsrChanges _, period = 2500L, unit = TimeUnit.MILLISECONDS)
    scheduler.schedule("shutdown-idle-replica-alter-log-dirs-thread", shutdownIdleReplicaAlterLogDirsThread _, period = 10000L, unit = TimeUnit.MILLISECONDS)

    // If inter-broker protocol (IBP) < 1.0, the controller will send LeaderAndIsrRequest V0 which does not include isNew field.
    // In this case, the broker receiving the request cannot determine whether it is safe to create a partition if a log directory has failed.
    // Thus, we choose to halt the broker on any log diretory failure if IBP < 1.0
    val haltBrokerOnFailure = config.interBrokerProtocolVersion < KAFKA_1_0_IV0
    logDirFailureHandler = new LogDirFailureHandler("LogDirFailureHandler", haltBrokerOnFailure)
    logDirFailureHandler.start()
  }

  private def maybeRemoveTopicMetrics(topic: String): Unit = {
    val topicHasOnlinePartition = allPartitions.values.exists {
      case HostedPartition.Online(partition) => topic == partition.topic
      case HostedPartition.None | HostedPartition.Offline => false
    }
    if (!topicHasOnlinePartition)
      brokerTopicStats.removeMetrics(topic)
  }

  def stopReplica(topicPartition: TopicPartition, deletePartition: Boolean)  = {
    stateChangeLogger.trace(s"Handling stop replica (delete=$deletePartition) for partition $topicPartition")

    if (deletePartition) {
      getPartition(topicPartition) match {
        case HostedPartition.Offline =>
          throw new KafkaStorageException(s"Partition $topicPartition is on an offline disk")

        case hostedPartition @ HostedPartition.Online(removedPartition) =>
          if (allPartitions.remove(topicPartition, hostedPartition)) {
            maybeRemoveTopicMetrics(topicPartition.topic)
            // this will delete the local log. This call may throw exception if the log is on offline directory
            removedPartition.delete()
          }

        case HostedPartition.None =>
          stateChangeLogger.trace(s"Ignoring stop replica (delete=$deletePartition) for partition " +
            s"$topicPartition as replica doesn't exist on broker")
      }

      // Delete log and corresponding folders in case replica manager doesn't hold them anymore.
      // This could happen when topic is being deleted while broker is down and recovers.
      if (logManager.getLog(topicPartition).isDefined)
        logManager.asyncDelete(topicPartition)
      if (logManager.getLog(topicPartition, isFuture = true).isDefined)
        logManager.asyncDelete(topicPartition, isFuture = true)
    }
    stateChangeLogger.trace(s"Finished handling stop replica (delete=$deletePartition) for partition $topicPartition")
  }

  def stopReplicas(stopReplicaRequest: StopReplicaRequest): (mutable.Map[TopicPartition, Errors], Errors) = {
    replicaStateChangeLock synchronized {
      val responseMap = new collection.mutable.HashMap[TopicPartition, Errors]
      if (stopReplicaRequest.controllerEpoch() < controllerEpoch) {
        stateChangeLogger.warn("Received stop replica request from an old controller epoch " +
          s"${stopReplicaRequest.controllerEpoch}. Latest known controller epoch is $controllerEpoch")
        (responseMap, Errors.STALE_CONTROLLER_EPOCH)
      } else {
        val partitions = stopReplicaRequest.partitions.asScala.toSet
        controllerEpoch = stopReplicaRequest.controllerEpoch
        // First stop fetchers for all partitions, then stop the corresponding replicas
        replicaFetcherManager.removeFetcherForPartitions(partitions)
        replicaAlterLogDirsManager.removeFetcherForPartitions(partitions)
        for (topicPartition <- partitions){
          try {
            stopReplica(topicPartition, stopReplicaRequest.deletePartitions)
            responseMap.put(topicPartition, Errors.NONE)
          } catch {
            case e: KafkaStorageException =>
              stateChangeLogger.error(s"Ignoring stop replica (delete=${stopReplicaRequest.deletePartitions}) for " +
                s"partition $topicPartition due to storage exception", e)
              responseMap.put(topicPartition, Errors.KAFKA_STORAGE_ERROR)
          }
        }
        (responseMap, Errors.NONE)
      }
    }
  }

  def getPartition(topicPartition: TopicPartition): HostedPartition = {
    Option(allPartitions.get(topicPartition)).getOrElse(HostedPartition.None)
  }

  // Visible for testing
  def createPartition(topicPartition: TopicPartition): Partition = {
    val partition = Partition(topicPartition, time, this)
    allPartitions.put(topicPartition, HostedPartition.Online(partition))
    partition
  }

  def nonOfflinePartition(topicPartition: TopicPartition): Option[Partition] = {
    getPartition(topicPartition) match {
      case HostedPartition.Online(partition) => Some(partition)
      case HostedPartition.None | HostedPartition.Offline => None
    }
  }

  /**
   * 一个非下线状态的partition的迭代器
   * 这是一个弱一致性的迭代器，如果一个partition状态变为下线状态，迭代器也可能返回这个partition
   * @return 非下线状态的partition的迭代器
   */
  private def nonOfflinePartitionsIterator: Iterator[Partition] = {
    allPartitions.values.iterator.flatMap {
      case HostedPartition.Online(partition) => Some(partition)
      case HostedPartition.None | HostedPartition.Offline => None
    }
  }

  /**
   * 下线状态的partition的数量
   * @return
   */
  private def offlinePartitionCount: Int = {
    allPartitions.values.iterator.count(_ == HostedPartition.Offline)
  }

  /**
   * 获取partition信息
   * @param topicPartition topic-partition信息
   * @param expectLeader
   * @return
   */
  def getPartitionOrException(topicPartition: TopicPartition, expectLeader: Boolean): Partition = {
    getPartition(topicPartition) match {
      case HostedPartition.Online(partition) =>
        partition

      case HostedPartition.Offline =>
        throw new KafkaStorageException(s"Partition $topicPartition is in an offline log directory")

      case HostedPartition.None if metadataCache.contains(topicPartition) =>
        if (expectLeader) {
          // The topic exists, but this broker is no longer a replica of it, so we return NOT_LEADER which
          // forces clients to refresh metadata to find the new location. This can happen, for example,
          // during a partition reassignment if a produce request from the client is sent to a broker after
          // the local replica has been deleted.
          throw new NotLeaderForPartitionException(s"Broker $localBrokerId is not a replica of $topicPartition")
        } else {
          throw new ReplicaNotAvailableException(s"Partition $topicPartition is not available")
        }

      case HostedPartition.None =>
        throw new UnknownTopicOrPartitionException(s"Partition $topicPartition doesn't exist")
    }
  }

  def localLogOrException(topicPartition: TopicPartition): Log = {
    getPartitionOrException(topicPartition, expectLeader = false).localLogOrException
  }

  def futureLocalLogOrException(topicPartition: TopicPartition): Log = {
    getPartitionOrException(topicPartition, expectLeader = false).futureLocalLogOrException
  }

  /**
   * 获取本地日志
   * @param topicPartition topic-partition信息
   * @return 本地日志
   */
  def localLog(topicPartition: TopicPartition): Option[Log] = {
    // 获取拥有本地日志的非下线partition
    nonOfflinePartition(topicPartition).flatMap(_.log)
  }

  def getLogDir(topicPartition: TopicPartition): Option[String] = {
    localLog(topicPartition).map(_.dir.getParent)
  }

  /**
   * 将消息内容追加到partition的leader副本节点上，并等待复制到其他副本节点上
   * 回调任务函数会在超时，或者收到ack之后触发
   * 如果回调函数已经在其他对象上同步，则可以忽略当前对象避免死锁
   */
  def appendRecords(timeout: Long,
                    requiredAcks: Short,
                    internalTopicsAllowed: Boolean,
                    isFromClient: Boolean,
                    entriesPerPartition: Map[TopicPartition, MemoryRecords],
                    responseCallback: Map[TopicPartition, PartitionResponse] => Unit,
                    delayedProduceLock: Option[Lock] = None,
                    recordConversionStatsCallback: Map[TopicPartition, RecordConversionStats] => Unit = _ => ()): Unit = {
    // producer需要同步的节点数量校验
    if (isValidRequiredAcks(requiredAcks)) {
      val sTime = time.milliseconds
      // 追加到本地日志中
      val localProduceResults = appendToLocalLog(internalTopicsAllowed = internalTopicsAllowed,
        isFromClient = isFromClient, entriesPerPartition, requiredAcks)
      debug("Produce to local log in %d ms".format(time.milliseconds - sTime))
      // 构建生产状态
      val produceStatus = localProduceResults.map { case (topicPartition, result) =>
        topicPartition ->
                ProducePartitionStatus(
                  result.info.lastOffset + 1, // required offset
                  new PartitionResponse(result.error, result.info.firstOffset.getOrElse(-1), result.info.logAppendTime, result.info.logStartOffset)) // response status
      }
      // 触发回调任务
      recordConversionStatsCallback(localProduceResults.map { case (k, v) => k -> v.info.recordConversionStats })

      // 如果需要延迟生产请出去
      if (delayedProduceRequestRequired(requiredAcks, entriesPerPartition, localProduceResults)) {
        // 创建延迟生产操作
        val produceMetadata = ProduceMetadata(requiredAcks, produceStatus)
        // 创建延迟生产
        val delayedProduce = new DelayedProduce(timeout, produceMetadata, this, responseCallback, delayedProduceLock)

        // 为这个延迟操作创建一个topic-partition为key，delayOperation为value的缓存键值对
        val producerRequestKeys = entriesPerPartition.keys.map(TopicPartitionOperationKey(_)).toSeq

        // 尝试完成请求，否则就放入到缓存中
        // 因为当delayOperation创建时，新的请求可能已经到达，会导致这个延迟操作完成
        delayedProducePurgatory.tryCompleteElseWatch(delayedProduce, producerRequestKeys)

      } else {
        // 如果我们不需要延迟生产，我们可以立即响应
        val produceResponseStatus = produceStatus.map { case (k, status) => k -> status.responseStatus }
        // 直接调用生产回调任务
        responseCallback(produceResponseStatus)
      }
    } else {
      // 如果收到的acks超过了可接受的范围，client可能发生了错误
      // 直接返回error，并且根本不处理这个请求
      val responseStatus = entriesPerPartition.map { case (topicPartition, _) =>
        topicPartition -> new PartitionResponse(Errors.INVALID_REQUIRED_ACKS,
          LogAppendInfo.UnknownLogAppendInfo.firstOffset.getOrElse(-1), RecordBatch.NO_TIMESTAMP, LogAppendInfo.UnknownLogAppendInfo.logStartOffset)
      }
      responseCallback(responseStatus)
    }
  }

  /**
   * 删除leader partition副本节点上的records，等待删除操作扩散到其他副本节点上
   * 响应回调任务会在超时，或者所有存活的副本节点的logStartOffset全部到达指定的offset后调用
   */
  private def deleteRecordsOnLocalLog(offsetPerPartition: Map[TopicPartition, Long]): Map[TopicPartition, LogDeleteRecordsResult] = {
    trace("Delete records on local logs to offsets [%s]".format(offsetPerPartition))
    offsetPerPartition.map { case (topicPartition, requestedOffset) =>
      // 拒绝在内部topic上的删除record操作
      if (Topic.isInternal(topicPartition.topic)) {
        (topicPartition, LogDeleteRecordsResult(-1L, -1L, Some(new InvalidTopicException(s"Cannot delete records of internal topic ${topicPartition.topic}"))))
      } else {
        try {
          // 获取partition的leader副本节点信息
          val partition = getPartitionOrException(topicPartition, expectLeader = true)
          // 对partition leader副本节点执行删除操作
          val logDeleteResult = partition.deleteRecordsOnLeader(requestedOffset)
          (topicPartition, logDeleteResult)
        } catch {
          case e@ (_: UnknownTopicOrPartitionException |
                   _: NotLeaderForPartitionException |
                   _: OffsetOutOfRangeException |
                   _: PolicyViolationException |
                   _: KafkaStorageException) =>
            (topicPartition, LogDeleteRecordsResult(-1L, -1L, Some(e)))
          case t: Throwable =>
            error("Error processing delete records operation on partition %s".format(topicPartition), t)
            (topicPartition, LogDeleteRecordsResult(-1L, -1L, Some(t)))
        }
      }
    }
  }

  // If there exists a topic partition that meets the following requirement,
  // we need to put a delayed DeleteRecordsRequest and wait for the delete records operation to complete
  //
  // 1. the delete records operation on this partition is successful
  // 2. low watermark of this partition is smaller than the specified offset
  private def delayedDeleteRecordsRequired(localDeleteRecordsResults: Map[TopicPartition, LogDeleteRecordsResult]): Boolean = {
    localDeleteRecordsResults.exists{ case (_, deleteRecordsResult) =>
      deleteRecordsResult.exception.isEmpty && deleteRecordsResult.lowWatermark < deleteRecordsResult.requestedOffset
    }
  }

  /**
   * For each pair of partition and log directory specified in the map, if the partition has already been created on
   * this broker, move its log files to the specified log directory. Otherwise, record the pair in the memory so that
   * the partition will be created in the specified log directory when broker receives LeaderAndIsrRequest for the partition later.
   */
  def alterReplicaLogDirs(partitionDirs: Map[TopicPartition, String]): Map[TopicPartition, Errors] = {
    replicaStateChangeLock synchronized {
      partitionDirs.map { case (topicPartition, destinationDir) =>
        try {
          /* If the topic name is exceptionally long, we can't support altering the log directory.
           * See KAFKA-4893 for details.
           * TODO: fix this by implementing topic IDs. */
          if (Log.logFutureDirName(topicPartition).size > 255)
            throw new InvalidTopicException("The topic name is too long.")
          if (!logManager.isLogDirOnline(destinationDir))
            throw new KafkaStorageException(s"Log directory $destinationDir is offline")

          getPartition(topicPartition) match {
            case HostedPartition.Online(partition) =>
              // Stop current replica movement if the destinationDir is different from the existing destination log directory
              if (partition.futureReplicaDirChanged(destinationDir)) {
                replicaAlterLogDirsManager.removeFetcherForPartitions(Set(topicPartition))
                partition.removeFutureLocalReplica()
              }
            case HostedPartition.Offline =>
              throw new KafkaStorageException(s"Partition $topicPartition is offline")

            case HostedPartition.None => // Do nothing
          }

          // If the log for this partition has not been created yet:
          // 1) Record the destination log directory in the memory so that the partition will be created in this log directory
          //    when broker receives LeaderAndIsrRequest for this partition later.
          // 2) Respond with ReplicaNotAvailableException for this partition in the AlterReplicaLogDirsResponse
          logManager.maybeUpdatePreferredLogDir(topicPartition, destinationDir)

          // throw ReplicaNotAvailableException if replica does not exist for the given partition
          val partition = getPartitionOrException(topicPartition, expectLeader = false)
          partition.localLogOrException

          // If the destinationLDir is different from the current log directory of the replica:
          // - If there is no offline log directory, create the future log in the destinationDir (if it does not exist) and
          //   start ReplicaAlterDirThread to move data of this partition from the current log to the future log
          // - Otherwise, return KafkaStorageException. We do not create the future log while there is offline log directory
          //   so that we can avoid creating future log for the same partition in multiple log directories.
          val highWatermarkCheckpoints = new LazyOffsetCheckpoints(this.highWatermarkCheckpoints)
          if (partition.maybeCreateFutureReplica(destinationDir, highWatermarkCheckpoints)) {
            val futureLog = futureLocalLogOrException(topicPartition)
            logManager.abortAndPauseCleaning(topicPartition)

            val initialFetchState = InitialFetchState(BrokerEndPoint(config.brokerId, "localhost", -1),
              partition.getLeaderEpoch, futureLog.highWatermark)
            replicaAlterLogDirsManager.addFetcherForPartitions(Map(topicPartition -> initialFetchState))
          }

          (topicPartition, Errors.NONE)
        } catch {
          case e@(_: InvalidTopicException |
                  _: LogDirNotFoundException |
                  _: ReplicaNotAvailableException |
                  _: KafkaStorageException) =>
            (topicPartition, Errors.forException(e))
          case t: Throwable =>
            error("Error while changing replica dir for partition %s".format(topicPartition), t)
            (topicPartition, Errors.forException(t))
        }
      }
    }
  }

  /*
   * Get the LogDirInfo for the specified list of partitions.
   *
   * Each LogDirInfo specifies the following information for a given log directory:
   * 1) Error of the log directory, e.g. whether the log is online or offline
   * 2) size and lag of current and future logs for each partition in the given log directory. Only logs of the queried partitions
   *    are included. There may be future logs (which will replace the current logs of the partition in the future) on the broker after KIP-113 is implemented.
   */
  def describeLogDirs(partitions: Set[TopicPartition]): Map[String, LogDirInfo] = {
    val logsByDir = logManager.allLogs.groupBy(log => log.dir.getParent)

    config.logDirs.toSet.map { logDir: String =>
      val absolutePath = new File(logDir).getAbsolutePath
      try {
        if (!logManager.isLogDirOnline(absolutePath))
          throw new KafkaStorageException(s"Log directory $absolutePath is offline")

        logsByDir.get(absolutePath) match {
          case Some(logs) =>
            val replicaInfos = logs.filter { log =>
              partitions.contains(log.topicPartition)
            }.map { log =>
              log.topicPartition -> new ReplicaInfo(log.size, getLogEndOffsetLag(log.topicPartition, log.logEndOffset, log.isFuture), log.isFuture)
            }.toMap

            (absolutePath, new LogDirInfo(Errors.NONE, replicaInfos.asJava))
          case None =>
            (absolutePath, new LogDirInfo(Errors.NONE, Map.empty[TopicPartition, ReplicaInfo].asJava))
        }

      } catch {
        case _: KafkaStorageException =>
          (absolutePath, new LogDirInfo(Errors.KAFKA_STORAGE_ERROR, Map.empty[TopicPartition, ReplicaInfo].asJava))
        case t: Throwable =>
          error(s"Error while describing replica in dir $absolutePath", t)
          (absolutePath, new LogDirInfo(Errors.forException(t), Map.empty[TopicPartition, ReplicaInfo].asJava))
      }
    }.toMap
  }

  def getLogEndOffsetLag(topicPartition: TopicPartition, logEndOffset: Long, isFuture: Boolean): Long = {
    localLog(topicPartition) match {
      case Some(log) =>
        if (isFuture)
          log.logEndOffset - logEndOffset
        else
          math.max(log.highWatermark - logEndOffset, 0)
      case None =>
        // return -1L to indicate that the LEO lag is not available if the replica is not created or is offline
        DescribeLogDirsResponse.INVALID_OFFSET_LAG
    }
  }

  def deleteRecords(timeout: Long,
                    offsetPerPartition: Map[TopicPartition, Long],
                    responseCallback: Map[TopicPartition, DeleteRecordsResponse.PartitionResponse] => Unit): Unit = {
    val timeBeforeLocalDeleteRecords = time.milliseconds
    val localDeleteRecordsResults = deleteRecordsOnLocalLog(offsetPerPartition)
    debug("Delete records on local log in %d ms".format(time.milliseconds - timeBeforeLocalDeleteRecords))

    val deleteRecordsStatus = localDeleteRecordsResults.map { case (topicPartition, result) =>
      topicPartition ->
        DeleteRecordsPartitionStatus(
          result.requestedOffset, // requested offset
          new DeleteRecordsResponse.PartitionResponse(result.lowWatermark, result.error)) // response status
    }

    if (delayedDeleteRecordsRequired(localDeleteRecordsResults)) {
      // create delayed delete records operation
      val delayedDeleteRecords = new DelayedDeleteRecords(timeout, deleteRecordsStatus, this, responseCallback)

      // create a list of (topic, partition) pairs to use as keys for this delayed delete records operation
      val deleteRecordsRequestKeys = offsetPerPartition.keys.map(TopicPartitionOperationKey(_)).toSeq

      // try to complete the request immediately, otherwise put it into the purgatory
      // this is because while the delayed delete records operation is being created, new
      // requests may arrive and hence make this operation completable.
      delayedDeleteRecordsPurgatory.tryCompleteElseWatch(delayedDeleteRecords, deleteRecordsRequestKeys)
    } else {
      // we can respond immediately
      val deleteRecordsResponseStatus = deleteRecordsStatus.map { case (k, status) => k -> status.responseStatus }
      responseCallback(deleteRecordsResponseStatus)
    }
  }

  // 如果下列条件为true，我们需要创建一个延迟生产请求，并等待复制完成
  // 1. required acks = -1
  // 2. 需要追加数据
  // 3. 至少一个partition追加成功
  private def delayedProduceRequestRequired(requiredAcks: Short,
                                            entriesPerPartition: Map[TopicPartition, MemoryRecords],
                                            localProduceResults: Map[TopicPartition, LogAppendResult]): Boolean = {
    requiredAcks == -1 &&
    entriesPerPartition.nonEmpty &&
    localProduceResults.values.count(_.exception.isDefined) < entriesPerPartition.size
  }

  private def isValidRequiredAcks(requiredAcks: Short): Boolean = {
    requiredAcks == -1 || requiredAcks == 1 || requiredAcks == 0
  }

  /**
   * Append the messages to the local replica logs
   */
  private def appendToLocalLog(internalTopicsAllowed: Boolean,
                               isFromClient: Boolean,
                               entriesPerPartition: Map[TopicPartition, MemoryRecords],
                               requiredAcks: Short): Map[TopicPartition, LogAppendResult] = {
    trace(s"Append [$entriesPerPartition] to local log")
    entriesPerPartition.map { case (topicPartition, records) =>
      brokerTopicStats.topicStats(topicPartition.topic).totalProduceRequestRate.mark()
      brokerTopicStats.allTopicsStats.totalProduceRequestRate.mark()

      // reject appending to internal topics if it is not allowed
      if (Topic.isInternal(topicPartition.topic) && !internalTopicsAllowed) {
        (topicPartition, LogAppendResult(
          LogAppendInfo.UnknownLogAppendInfo,
          Some(new InvalidTopicException(s"Cannot append to internal topic ${topicPartition.topic}"))))
      } else {
        try {
          val partition = getPartitionOrException(topicPartition, expectLeader = true)
          val info = partition.appendRecordsToLeader(records, isFromClient, requiredAcks)
          val numAppendedMessages = info.numMessages

          // update stats for successfully appended bytes and messages as bytesInRate and messageInRate
          brokerTopicStats.topicStats(topicPartition.topic).bytesInRate.mark(records.sizeInBytes)
          brokerTopicStats.allTopicsStats.bytesInRate.mark(records.sizeInBytes)
          brokerTopicStats.topicStats(topicPartition.topic).messagesInRate.mark(numAppendedMessages)
          brokerTopicStats.allTopicsStats.messagesInRate.mark(numAppendedMessages)

          trace(s"${records.sizeInBytes} written to log $topicPartition beginning at offset " +
            s"${info.firstOffset.getOrElse(-1)} and ending at offset ${info.lastOffset}")
          (topicPartition, LogAppendResult(info))
        } catch {
          // NOTE: Failed produce requests metric is not incremented for known exceptions
          // it is supposed to indicate un-expected failures of a broker in handling a produce request
          case e@ (_: UnknownTopicOrPartitionException |
                   _: NotLeaderForPartitionException |
                   _: RecordTooLargeException |
                   _: RecordBatchTooLargeException |
                   _: CorruptRecordException |
                   _: KafkaStorageException |
                   _: InvalidTimestampException) =>
            (topicPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(e)))
          case t: Throwable =>
            val logStartOffset = getPartition(topicPartition) match {
              case HostedPartition.Online(partition) => partition.logStartOffset
              case HostedPartition.None | HostedPartition.Offline => -1L
            }
            brokerTopicStats.topicStats(topicPartition.topic).failedProduceRequestRate.mark()
            brokerTopicStats.allTopicsStats.failedProduceRequestRate.mark()
            error(s"Error processing append operation on partition $topicPartition", t)
            (topicPartition, LogAppendResult(LogAppendInfo.unknownLogAppendInfoWithLogStartOffset(logStartOffset), Some(t)))
        }
      }
    }
  }

  def fetchOffsetForTimestamp(topicPartition: TopicPartition,
                              timestamp: Long,
                              isolationLevel: Option[IsolationLevel],
                              currentLeaderEpoch: Optional[Integer],
                              fetchOnlyFromLeader: Boolean): Option[TimestampAndOffset] = {
    val partition = getPartitionOrException(topicPartition, expectLeader = fetchOnlyFromLeader)
    partition.fetchOffsetForTimestamp(timestamp, isolationLevel, currentLeaderEpoch, fetchOnlyFromLeader)
  }

  def legacyFetchOffsetsForTimestamp(topicPartition: TopicPartition,
                                     timestamp: Long,
                                     maxNumOffsets: Int,
                                     isFromConsumer: Boolean,
                                     fetchOnlyFromLeader: Boolean): Seq[Long] = {
    val partition = getPartitionOrException(topicPartition, expectLeader = fetchOnlyFromLeader)
    partition.legacyFetchOffsetsForTimestamp(timestamp, maxNumOffsets, isFromConsumer, fetchOnlyFromLeader)
  }

  /**
   * 从副本节点拉取消息
   * 直到等待到足够多的数据之后才返回
   * 回调任务可以在超时或者满足需要的拉取信息之后返回
   * 消费者可能从任何一个副本节点拉取消息，但是follower节点仅能从leader节点拉取消息
   */
  def fetchMessages(timeout: Long,
                    replicaId: Int,
                    fetchMinBytes: Int,
                    fetchMaxBytes: Int,
                    hardMaxBytesLimit: Boolean,
                    fetchInfos: Seq[(TopicPartition, PartitionData)],
                    quota: ReplicaQuota,
                    responseCallback: Seq[(TopicPartition, FetchPartitionData)] => Unit,
                    isolationLevel: IsolationLevel,
                    clientMetadata: Option[ClientMetadata]): Unit = {
    // 校验备份副本的编号
    val isFromFollower = Request.isValidBrokerId(replicaId)
    // 确认拉取隔离级别
    // 如果拉取请求来自于follower节点，或者备份副本编号是未来的本地副本编号，则使用FetchLogEnd策略
    val fetchIsolation = if (isFromFollower || replicaId == Request.FutureLocalReplicaId)
      FetchLogEnd
    else if (isolationLevel == IsolationLevel.READ_COMMITTED)
    // 如果拉取隔离级别是可重复度，则使用FetchTxnCommitted策略
      FetchTxnCommitted
    else
    // 否则，默认情况下使用FetchHighWatermark策略
      FetchHighWatermark

    /**
     * 读取日志文件信息
     * @return key:   TopicPartition
     *         value: LogReadResult
     */
    def readFromLog(): Seq[(TopicPartition, LogReadResult)] = {
      // 从本地日志中读取
      val result = readFromLocalLog(
        replicaId = replicaId,
        fetchOnlyFromLeader = isFromFollower,
        fetchIsolation = fetchIsolation,
        fetchMaxBytes = fetchMaxBytes,
        hardMaxBytesLimit = hardMaxBytesLimit,
        readPartitionInfo = fetchInfos,
        quota = quota,
        clientMetadata = clientMetadata)
      // 如果是follower节点的信息，则更新当前follower节点的拉取状态
      if (isFromFollower) updateFollowerFetchState(replicaId, result)
      // 如果不是follower节点的FetchRequest，则直接返回读取结果
      else result
    }

    val logReadResults = readFromLog()

    // check if this fetch request can be satisfied right away
    var bytesReadable: Long = 0
    var errorReadingData = false
    val logReadResultMap = new mutable.HashMap[TopicPartition, LogReadResult]
    var anyPartitionsNeedHwUpdate = false
    logReadResults.foreach { case (topicPartition, logReadResult) =>
      if (logReadResult.error != Errors.NONE)
        errorReadingData = true
      bytesReadable = bytesReadable + logReadResult.info.records.sizeInBytes
      logReadResultMap.put(topicPartition, logReadResult)
      if (isFromFollower && logReadResult.followerNeedsHwUpdate) {
        anyPartitionsNeedHwUpdate = true
      }
    }

    // Wrap the given callback function with another function that will update the HW for the remote follower
    def maybeUpdateHwAndSendResponse(fetchPartitionData: Seq[(TopicPartition, FetchPartitionData)]): Unit = {
      if (isFromFollower) {
        fetchPartitionData.foreach {
          case (tp, partitionData) => updateFollowerHighWatermark(tp, replicaId, partitionData.highWatermark)
        }
      }
      responseCallback(fetchPartitionData)
    }

    // respond immediately if 1) fetch request does not want to wait
    //                        2) fetch request does not require any data
    //                        3) has enough data to respond
    //                        4) some error happens while reading data
    //                        5) any of the requested partitions need HW update
    if (timeout <= 0 || fetchInfos.isEmpty || bytesReadable >= fetchMinBytes || errorReadingData || anyPartitionsNeedHwUpdate) {
      val fetchPartitionData = logReadResults.map { case (tp, result) =>
        tp -> FetchPartitionData(result.error, result.highWatermark, result.leaderLogStartOffset, result.info.records,
          result.lastStableOffset, result.info.abortedTransactions, result.preferredReadReplica)
      }
      maybeUpdateHwAndSendResponse(fetchPartitionData)
    } else {
      // construct the fetch results from the read results
      val fetchPartitionStatus = new mutable.ArrayBuffer[(TopicPartition, FetchPartitionStatus)]
      fetchInfos.foreach { case (topicPartition, partitionData) =>
        logReadResultMap.get(topicPartition).foreach(logReadResult => {
          val logOffsetMetadata = logReadResult.info.fetchOffsetMetadata
          fetchPartitionStatus += (topicPartition -> FetchPartitionStatus(logOffsetMetadata, partitionData))
        })
      }
      val fetchMetadata = FetchMetadata(fetchMinBytes, fetchMaxBytes, hardMaxBytesLimit, isFromFollower,
        fetchIsolation, isFromFollower, replicaId, fetchPartitionStatus)
      val delayedFetch = new DelayedFetch(timeout, fetchMetadata, this, quota, clientMetadata,
        maybeUpdateHwAndSendResponse)

      // create a list of (topic, partition) pairs to use as keys for this delayed fetch operation
      val delayedFetchKeys = fetchPartitionStatus.map { case (tp, _) => TopicPartitionOperationKey(tp) }

      // try to complete the request immediately, otherwise put it into the purgatory;
      // this is because while the delayed fetch operation is being created, new requests
      // may arrive and hence make this operation completable.
      delayedFetchPurgatory.tryCompleteElseWatch(delayedFetch, delayedFetchKeys)
    }
  }

  /**
   * 从多个topic-partition中获取从给定的offset开始到maxSize字节大小的数据
   * @param replicaId           备份副本编号
   * @param fetchOnlyFromLeader 是否只从leader节点拉取数据，副本节点仅会从leader节点拉取数据
   * @param fetchIsolation      拉取隔离级别
   * @param fetchMaxBytes       拉取的最大字节数
   * @param hardMaxBytesLimit   是否要求必须获取
   * @param readPartitionInfo   需要进行读取的topic-partition信息
   * @param quota
   * @param clientMetadata
   */
  def readFromLocalLog(replicaId: Int,
                       fetchOnlyFromLeader: Boolean,
                       fetchIsolation: FetchIsolation,
                       fetchMaxBytes: Int,
                       hardMaxBytesLimit: Boolean,
                       readPartitionInfo: Seq[(TopicPartition, PartitionData)],
                       quota: ReplicaQuota,
                       clientMetadata: Option[ClientMetadata]): Seq[(TopicPartition, LogReadResult)] = {

    /**
     * 读取指定topic-partition的日志
     * @param tp         指定的topic-partition
     * @param fetchInfo  拉取信息
     * @param limitBytes 最大限制拉取字节数
     * @param minOneMessage
     * @return
     */
    def read(tp: TopicPartition, fetchInfo: PartitionData, limitBytes: Int, minOneMessage: Boolean): LogReadResult = {
      // 给定分区拉取的起始offset
      val offset = fetchInfo.fetchOffset
      // 需要拉取的字节数
      val partitionFetchSize = fetchInfo.maxBytes
      // 给定partition的起始偏移量
      val followerLogStartOffset = fetchInfo.logStartOffset
      // 标记给定topic的拉取频率
      brokerTopicStats.topicStats(tp.topic).totalFetchRequestRate.mark()
      // 标记所有topic的拉取频率
      brokerTopicStats.allTopicsStats.totalFetchRequestRate.mark()
      // 获取最大拉取字节数，由请求的最大拉取字节数和设置的最大拉取字节数组成
      val adjustedMaxBytes = math.min(fetchInfo.maxBytes, limitBytes)
      try {
        trace(s"Fetching log segment for partition $tp, offset $offset, partition fetch size $partitionFetchSize, " +
          s"remaining response limit $limitBytes" +
          (if (minOneMessage) s", ignoring response/partition size limits" else ""))
        // 获取partition信息
        val partition = getPartitionOrException(tp, expectLeader = fetchOnlyFromLeader)
        // 设置本次的拉取时间
        val fetchTimeMs = time.milliseconds

        // 如果当前节点是leader节点，那么确认首选的只读副本节点
        val preferredReadReplica = clientMetadata.flatMap(
          metadata => findPreferredReadReplica(tp, metadata, replicaId, fetchInfo.fetchOffset, fetchTimeMs))

        if (preferredReadReplica.isDefined) {
          replicaSelectorOpt.foreach{ selector =>
            debug(s"Replica selector ${selector.getClass.getSimpleName} returned preferred replica " +
              s"${preferredReadReplica.get} for $clientMetadata")
          }
          // If a preferred read-replica is set, skip the read
          val offsetSnapshot = partition.fetchOffsetSnapshot(fetchInfo.currentLeaderEpoch, false)
          LogReadResult(info = FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MemoryRecords.EMPTY),
            highWatermark = offsetSnapshot.highWatermark.messageOffset,
            leaderLogStartOffset = offsetSnapshot.logStartOffset,
            leaderLogEndOffset = offsetSnapshot.logEndOffset.messageOffset,
            followerLogStartOffset = followerLogStartOffset,
            fetchTimeMs = -1L,
            readSize = 0,
            lastStableOffset = Some(offsetSnapshot.lastStableOffset.messageOffset),
            preferredReadReplica = preferredReadReplica,
            exception = None)
        } else {
          // Try the read first, this tells us whether we need all of adjustedFetchSize for this partition
          val readInfo: LogReadInfo = partition.readRecords(
            fetchOffset = fetchInfo.fetchOffset,
            currentLeaderEpoch = fetchInfo.currentLeaderEpoch,
            maxBytes = adjustedMaxBytes,
            fetchIsolation = fetchIsolation,
            fetchOnlyFromLeader = fetchOnlyFromLeader,
            minOneMessage = minOneMessage)

          // Check if the HW known to the follower is behind the actual HW
          val followerNeedsHwUpdate: Boolean = partition.getReplica(replicaId)
            .exists(replica => replica.lastSentHighWatermark < readInfo.highWatermark)

          val fetchDataInfo = if (shouldLeaderThrottle(quota, tp, replicaId)) {
            // If the partition is being throttled, simply return an empty set.
            FetchDataInfo(readInfo.fetchedData.fetchOffsetMetadata, MemoryRecords.EMPTY)
          } else if (!hardMaxBytesLimit && readInfo.fetchedData.firstEntryIncomplete) {
            // For FetchRequest version 3, we replace incomplete message sets with an empty one as consumers can make
            // progress in such cases and don't need to report a `RecordTooLargeException`
            FetchDataInfo(readInfo.fetchedData.fetchOffsetMetadata, MemoryRecords.EMPTY)
          } else {
            readInfo.fetchedData
          }

          LogReadResult(info = fetchDataInfo,
            highWatermark = readInfo.highWatermark,
            leaderLogStartOffset = readInfo.logStartOffset,
            leaderLogEndOffset = readInfo.logEndOffset,
            followerLogStartOffset = followerLogStartOffset,
            fetchTimeMs = fetchTimeMs,
            readSize = adjustedMaxBytes,
            lastStableOffset = Some(readInfo.lastStableOffset),
            preferredReadReplica = preferredReadReplica,
            followerNeedsHwUpdate = followerNeedsHwUpdate,
            exception = None)
        }
      } catch {
        // NOTE: Failed fetch requests metric is not incremented for known exceptions since it
        // is supposed to indicate un-expected failure of a broker in handling a fetch request
        case e@ (_: UnknownTopicOrPartitionException |
                 _: NotLeaderForPartitionException |
                 _: UnknownLeaderEpochException |
                 _: FencedLeaderEpochException |
                 _: ReplicaNotAvailableException |
                 _: KafkaStorageException |
                 _: OffsetOutOfRangeException) =>
          LogReadResult(info = FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MemoryRecords.EMPTY),
            highWatermark = Log.UnknownOffset,
            leaderLogStartOffset = Log.UnknownOffset,
            leaderLogEndOffset = Log.UnknownOffset,
            followerLogStartOffset = Log.UnknownOffset,
            fetchTimeMs = -1L,
            readSize = 0,
            lastStableOffset = None,
            exception = Some(e))
        case e: Throwable =>
          brokerTopicStats.topicStats(tp.topic).failedFetchRequestRate.mark()
          brokerTopicStats.allTopicsStats.failedFetchRequestRate.mark()

          val fetchSource = Request.describeReplicaId(replicaId)
          error(s"Error processing fetch with max size $adjustedMaxBytes from $fetchSource " +
            s"on partition $tp: $fetchInfo", e)

          LogReadResult(info = FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MemoryRecords.EMPTY),
            highWatermark = Log.UnknownOffset,
            leaderLogStartOffset = Log.UnknownOffset,
            leaderLogEndOffset = Log.UnknownOffset,
            followerLogStartOffset = Log.UnknownOffset,
            fetchTimeMs = -1L,
            readSize = 0,
            lastStableOffset = None,
            exception = Some(e))
      }
    }

    var limitBytes = fetchMaxBytes
    val result = new mutable.ArrayBuffer[(TopicPartition, LogReadResult)]
    var minOneMessage = !hardMaxBytesLimit
    readPartitionInfo.foreach { case (tp, fetchInfo) =>
      val readResult = read(tp, fetchInfo, limitBytes, minOneMessage)
      val recordBatchSize = readResult.info.records.sizeInBytes
      // Once we read from a non-empty partition, we stop ignoring request and partition level size limits
      if (recordBatchSize > 0)
        minOneMessage = false
      limitBytes = math.max(0, limitBytes - recordBatchSize)
      result += (tp -> readResult)
    }
    result
  }

  /**
   * 使用配置的[[ReplicaSelector]]，通过给定的客户端元数据，请求的offset，当前的ISR来partition确认首选读副本节点
   * 如果首选的读副本节点就是leader节点，返回None
   */
  def findPreferredReadReplica(tp: TopicPartition,
                               clientMetadata: ClientMetadata,
                               replicaId: Int,
                               fetchOffset: Long,
                               currentTimeMs: Long): Option[Int] = {
    // 获取给定的分区信息
    val partition = getPartitionOrException(tp, expectLeader = false)
    // 如果当前分区时leader节点
    if (partition.isLeader) {
      // 如果通过了备份副本编号校验，则直接返回None
      if (Request.isValidBrokerId(replicaId)) {
        // 不要通过普通的复制同步来查找首选的follower节点
        Option.empty
      } else {
        // 通过replicaSelector选择读副本节点
        replicaSelectorOpt.flatMap { replicaSelector =>
          // 获取当前topic-partition节点的EndPoints信息
          val replicaEndpoints = metadataCache.getPartitionReplicaEndpoints(tp, new ListenerName(clientMetadata.listenerName))
          // 获取partition的远程副本节点
          var replicaInfoSet: Set[ReplicaView] = partition.remoteReplicas
            // 过滤正处于ISR状态的副本节点，也就是fetchOffset处于正常的偏移量范围内
            .filter(replica => replica.logEndOffset >= fetchOffset)
            .filter(replica => replica.logStartOffset <= fetchOffset)
            // 构建副本选择器
            .map(replica => new DefaultReplicaView(
              replicaEndpoints.getOrElse(replica.brokerId, Node.noNode()),
              replica.logEndOffset,
              currentTimeMs - replica.lastCaughtUpTimeMs
            ))
          // 如果partition有已声明的leader节点
          if (partition.leaderReplicaIdOpt.isDefined) {
            // 获取leader节点
            val leaderReplica: ReplicaView = partition.leaderReplicaIdOpt
              // 构建leader节点字典表
              .map(replicaId => replicaEndpoints.getOrElse(replicaId, Node.noNode()))
              .map(leaderNode => new DefaultReplicaView(leaderNode, partition.localLogOrException.logEndOffset, 0L))
              .get
            // 此时replicaInfoSet已有所有的ISR远程副本节点
            // 此时再加上所有的leader节点
            replicaInfoSet ++= Set(leaderReplica)
            // 构建副本节点信息和leader节点信息
            val partitionInfo = new DefaultPartitionView(replicaInfoSet.asJava, leaderReplica)

            replicaSelector.select(tp, clientMetadata, partitionInfo).asScala
              .filter(!_.endpoint.isEmpty)
              // 不希望leader节点发送FetchResponse，所以此处剔除了leader节点
              .filter(!_.equals(leaderReplica))
              .map(_.endpoint.id)
          } else {
            None
          }
        }
      }
    } else {
      None
    }
  }

  /**
   *  To avoid ISR thrashing, we only throttle a replica on the leader if it's in the throttled replica list,
   *  the quota is exceeded and the replica is not in sync.
   */
  def shouldLeaderThrottle(quota: ReplicaQuota, topicPartition: TopicPartition, replicaId: Int): Boolean = {
    val isReplicaInSync = nonOfflinePartition(topicPartition).exists(_.inSyncReplicaIds.contains(replicaId))
    !isReplicaInSync && quota.isThrottled(topicPartition) && quota.isQuotaExceeded
  }

  def getLogConfig(topicPartition: TopicPartition): Option[LogConfig] = localLog(topicPartition).map(_.config)

  def getMagic(topicPartition: TopicPartition): Option[Byte] = getLogConfig(topicPartition).map(_.messageFormatVersion.recordVersion.value)

  def maybeUpdateMetadataCache(correlationId: Int, updateMetadataRequest: UpdateMetadataRequest) : Seq[TopicPartition] =  {
    replicaStateChangeLock synchronized {
      if(updateMetadataRequest.controllerEpoch < controllerEpoch) {
        val stateControllerEpochErrorMessage = s"Received update metadata request with correlation id $correlationId " +
          s"from an old controller ${updateMetadataRequest.controllerId} with epoch ${updateMetadataRequest.controllerEpoch}. " +
          s"Latest known controller epoch is $controllerEpoch"
        stateChangeLogger.warn(stateControllerEpochErrorMessage)
        throw new ControllerMovedException(stateChangeLogger.messageWithPrefix(stateControllerEpochErrorMessage))
      } else {
        val deletedPartitions = metadataCache.updateMetadata(correlationId, updateMetadataRequest)
        controllerEpoch = updateMetadataRequest.controllerEpoch
        deletedPartitions
      }
    }
  }

  /**
   * 成为主节点或者副本节点
   * @param correlationId       请求是的标识ID
   * @param leaderAndIsrRequest { @link LeaderAndIsrRequest}
   * @param onLeadershipChange leader节点发生的变化事件
   * @return { @link LeaderAndIsrResponse}
   */
  def becomeLeaderOrFollower(correlationId: Int,
                             leaderAndIsrRequest: LeaderAndIsrRequest,
                             onLeadershipChange: (Iterable[Partition], Iterable[Partition]) => Unit): LeaderAndIsrResponse = {
    // 获取leaderAndIsr请求中每个分区的状态，并日志记录状态的变化
    leaderAndIsrRequest.partitionStates.asScala.foreach { case (topicPartition, stateInfo) =>
      stateChangeLogger.trace(s"Received LeaderAndIsr request $stateInfo " +
        s"correlation id $correlationId from controller ${leaderAndIsrRequest.controllerId} " +
        s"epoch ${leaderAndIsrRequest.controllerEpoch} for partition $topicPartition")
    }
    // 使用副本节点状态变化锁进行同步操作
    replicaStateChangeLock synchronized {
      // 如果请求的控制器的代小于当前的控制器版本
      if (leaderAndIsrRequest.controllerEpoch < controllerEpoch) {
        stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from controller ${leaderAndIsrRequest.controllerId} with " +
          s"correlation id $correlationId since its controller epoch ${leaderAndIsrRequest.controllerEpoch} is old. " +
          s"Latest known controller epoch is $controllerEpoch")
        // 直接返回错误的响应，错误信息：过期的控制器代
        leaderAndIsrRequest.getErrorResponse(0, Errors.STALE_CONTROLLER_EPOCH.exception)
      } else {
        // 否则，使用
        val responseMap = new mutable.HashMap[TopicPartition, Errors]
        // 获取控制器的ID和代版本
        val controllerId = leaderAndIsrRequest.controllerId
        controllerEpoch = leaderAndIsrRequest.controllerEpoch

        // 首先检查partition的leader代版本
        val partitionState = new mutable.HashMap[Partition, LeaderAndIsrRequest.PartitionState]()
        val newPartitions = new mutable.HashSet[Partition]
        // 遍历所有的leaderAndIsrRequest中的分区状态
        leaderAndIsrRequest.partitionStates.asScala.foreach { case (topicPartition, stateInfo) =>
          // 获取当前partition信息
          val partitionOpt = getPartition(topicPartition) match {
            // 如果partition处于下线状态
            case HostedPartition.Offline =>
              stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from " +
                s"controller $controllerId with correlation id $correlationId " +
                s"epoch $controllerEpoch for partition $topicPartition as the local replica for the " +
                "partition is in an offline log directory")
              // 返回磁盘错误响应信息
              responseMap.put(topicPartition, Errors.KAFKA_STORAGE_ERROR)
              None
            case HostedPartition.Online(partition) => Some(partition)
            // 处于在线状态

            case HostedPartition.None =>
              // 没有托管此partition，创建新的partition
              val partition = Partition(topicPartition, time, this)
              allPartitions.putIfNotExists(topicPartition, HostedPartition.Online(partition))
              newPartitions.add(partition)
              Some(partition)
          }
          // 遍历托管的partition
          partitionOpt.foreach { partition =>
            // 获取当前partition的leader epoch
            val currentLeaderEpoch = partition.getLeaderEpoch
            // 获取请求的leader epoch
            val requestLeaderEpoch = stateInfo.basePartitionState.leaderEpoch
            // 如果请求的leader epoch＞当前partition的leader epoch
            if (requestLeaderEpoch > currentLeaderEpoch) {
              // 如果leader epoch是有效的，则记录做出leader权限决定的epoch版本
              // 这在更新ISR以在Zookeeper路径中维持决策者控制器的epoch时很有用
              if (stateInfo.basePartitionState.replicas.contains(localBrokerId))
              // 如果当前的partition代理节点集合包含当前的broker
              // 放入更新集合中
                partitionState.put(partition, stateInfo)
              else {
                stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from controller $controllerId with " +
                  s"correlation id $correlationId epoch $controllerEpoch for partition $topicPartition as itself is not " +
                  s"in assigned replica list ${stateInfo.basePartitionState.replicas.asScala.mkString(",")}")
                // 没有当前的broker，则返回没有托管给定partition响应信息
                responseMap.put(topicPartition, Errors.UNKNOWN_TOPIC_OR_PARTITION)
              }
            } else if (requestLeaderEpoch < currentLeaderEpoch) {
              // 如果请求的leader epoch＜当前partition的epoch，返回过期的控制器epoch
              stateChangeLogger.warn(s"Ignoring LeaderAndIsr request from " +
                s"controller $controllerId with correlation id $correlationId " +
                s"epoch $controllerEpoch for partition $topicPartition since its associated " +
                s"leader epoch $requestLeaderEpoch is smaller than the current " +
                s"leader epoch $currentLeaderEpoch")
              responseMap.put(topicPartition, Errors.STALE_CONTROLLER_EPOCH)
            } else {
              // 此时leader epoch ＝ partition epoch，也返回过期的控制器epoch
              stateChangeLogger.debug(s"Ignoring LeaderAndIsr request from " +
                s"controller $controllerId with correlation id $correlationId " +
                s"epoch $controllerEpoch for partition $topicPartition since its associated " +
                s"leader epoch $requestLeaderEpoch matches the current leader epoch")
              responseMap.put(topicPartition, Errors.STALE_CONTROLLER_EPOCH)
            }
          }
        }
        // 过滤出成为leader身份的partition
        val partitionsTobeLeader = partitionState.filter { case (_, stateInfo) =>
          stateInfo.basePartitionState.leader == localBrokerId
        }
        // 两个集合取差异值即为成为follower身份的partition
        val partitionsToBeFollower = partitionState -- partitionsTobeLeader.keys
        // HighWaterMark检查点
        val highWatermarkCheckpoints = new LazyOffsetCheckpoints(this.highWatermarkCheckpoints)
        // 成为leader身份操作
        val partitionsBecomeLeader = if (partitionsTobeLeader.nonEmpty)
          makeLeaders(controllerId, controllerEpoch, partitionsTobeLeader, correlationId, responseMap,
            highWatermarkCheckpoints)
        else
          Set.empty[Partition]
        // 成为follower身份操作
        val partitionsBecomeFollower = if (partitionsToBeFollower.nonEmpty)
          makeFollowers(controllerId, controllerEpoch, partitionsToBeFollower, correlationId, responseMap,
            highWatermarkCheckpoints)
        else
          Set.empty[Partition]

        /*
         * KAFKA-8392
         * 对于那些broker不再是leader的partition，删除与这些topic关联的度量标准
         * 需要注意的是，这意味着broker停止成为partition的副本节点或者主节点
         */
        // leader topic集合
        val leaderTopicSet = leaderPartitionsIterator.map(_.topic).toSet
        // follower topic集合
        val followerTopicSet = partitionsBecomeFollower.map(_.topic).toSet
        // 获取之前是leader现在是follower的topic集合，将它们的leader身份度量标准删除
        followerTopicSet.diff(leaderTopicSet).foreach(brokerTopicStats.removeOldLeaderMetrics)

        // remove metrics for brokers which are not followers of a topic
        // 获取之前是follower，现在是leader的topic集合，将它们的follower身份的度量标准移除
        leaderTopicSet.diff(followerTopicSet).foreach(brokerTopicStats.removeOldFollowerMetrics)

        leaderAndIsrRequest.partitionStates.asScala.keys.foreach { topicPartition =>
          /*
           * 如果存在离线日志目录，partition对象可能已经在getOrCreateReplica()创建本地副本节点失败之前，通过getOrCreatePartition()创建
           * 在这种情况下，ReplicaManager.allPartitions将会映射topic-partition到一个空的partition对象上
           * 此时需要代替映射topic-partition到OfflinePartition对象上
           */
          // 获取所有非下线状态的topic-partition信息，如果这些topic-partition的本地日志为空，在让这些partition下线
          if (localLog(topicPartition).isEmpty)
            markPartitionOffline(topicPartition)
        }

        // 在第一次LeaderAndIsrRequest之后，初始化了HighWaterMark线程
        // 用于确保所有的partition已经完成填充，在开始检查点之前，通过避免奇怪的条件竞争
        startHighWatermarkCheckPointThread()

        val futureReplicasAndInitialOffset = new mutable.HashMap[TopicPartition, InitialFetchState]
        // 遍历此次LeaderAndIsrRequest请求新添加的partition
        for (partition <- newPartitions) {
          val topicPartition = partition.topicPartition
          // 判断此topic-partition日志是否创建
          if (logManager.getLog(topicPartition, isFuture = true).isDefined) {
            // 如果已经创建，则
            partition.log.foreach { log =>
              // 建立和此partiton leader身份的broker的P2P连接
              val leader = BrokerEndPoint(config.brokerId, "localhost", -1)

              // 添加未来的副本节点到partition集合中
              partition.createLogIfNotExists(Request.FutureLocalReplicaId, isNew = false, isFutureReplica = true,
                highWatermarkCheckpoints)

              // 暂停清除准备迁移的partition，并开启ReplicaAlterDirThread线程来将副本节点从源DIR迁移至目标DIR
              logManager.abortAndPauseCleaning(topicPartition)
              // 未来的部分节点和初始化offset中添加当前的topic-partition
              futureReplicasAndInitialOffset.put(topicPartition, InitialFetchState(leader,
                partition.getLeaderEpoch, log.highWatermark))
            }
          }
        }
        //
        replicaAlterLogDirsManager.addFetcherForPartitions(futureReplicasAndInitialOffset)
        // 停止空闲的拉取线程
        replicaFetcherManager.shutdownIdleFetcherThreads()
        replicaAlterLogDirsManager.shutdownIdleFetcherThreads()
        // 处理身份变化事件
        onLeadershipChange(partitionsBecomeLeader, partitionsBecomeFollower)
        // 返回处理LeaderAndIsrRequest成功响应
        new LeaderAndIsrResponse(Errors.NONE, responseMap.asJava)
      }
    }
  }

  /*
   * Make the current broker to become leader for a given set of partitions by:
   *
   * 1. Stop fetchers for these partitions
   * 2. Update the partition metadata in cache
   * 3. Add these partitions to the leader partitions set
   *
   * If an unexpected error is thrown in this function, it will be propagated to KafkaApis where
   * the error message will be set on each partition since we do not know which partition caused it. Otherwise,
   * return the set of partitions that are made leader due to this method
   *
   *  TODO: the above may need to be fixed later
   */
  private def makeLeaders(controllerId: Int,
                          controllerEpoch: Int,
                          partitionState: Map[Partition, LeaderAndIsrRequest.PartitionState],
                          correlationId: Int,
                          responseMap: mutable.Map[TopicPartition, Errors],
                          highWatermarkCheckpoints: OffsetCheckpoints): Set[Partition] = {
    partitionState.keys.foreach { partition =>
      stateChangeLogger.trace(s"Handling LeaderAndIsr request correlationId $correlationId from " +
        s"controller $controllerId epoch $controllerEpoch starting the become-leader transition for " +
        s"partition ${partition.topicPartition}")
    }

    for (partition <- partitionState.keys)
      responseMap.put(partition.topicPartition, Errors.NONE)

    val partitionsToMakeLeaders = mutable.Set[Partition]()

    try {
      // First stop fetchers for all the partitions
      replicaFetcherManager.removeFetcherForPartitions(partitionState.keySet.map(_.topicPartition))
      // Update the partition information to be the leader
      partitionState.foreach{ case (partition, partitionStateInfo) =>
        try {
          if (partition.makeLeader(controllerId, partitionStateInfo, correlationId, highWatermarkCheckpoints)) {
            partitionsToMakeLeaders += partition
            stateChangeLogger.trace(s"Stopped fetchers as part of become-leader request from " +
              s"controller $controllerId epoch $controllerEpoch with correlation id $correlationId for partition ${partition.topicPartition} " +
              s"(last update controller epoch ${partitionStateInfo.basePartitionState.controllerEpoch})")
          } else
            stateChangeLogger.info(s"Skipped the become-leader state change after marking its " +
              s"partition as leader with correlation id $correlationId from controller $controllerId epoch $controllerEpoch for " +
              s"partition ${partition.topicPartition} (last update controller epoch ${partitionStateInfo.basePartitionState.controllerEpoch}) " +
              s"since it is already the leader for the partition.")
        } catch {
          case e: KafkaStorageException =>
            stateChangeLogger.error(s"Skipped the become-leader state change with " +
              s"correlation id $correlationId from controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition} " +
              s"(last update controller epoch ${partitionStateInfo.basePartitionState.controllerEpoch}) since " +
              s"the replica for the partition is offline due to disk error $e")
            val dirOpt = getLogDir(partition.topicPartition)
            error(s"Error while making broker the leader for partition $partition in dir $dirOpt", e)
            responseMap.put(partition.topicPartition, Errors.KAFKA_STORAGE_ERROR)
        }
      }

    } catch {
      case e: Throwable =>
        partitionState.keys.foreach { partition =>
          stateChangeLogger.error(s"Error while processing LeaderAndIsr request correlationId $correlationId received " +
            s"from controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition}", e)
        }
        // Re-throw the exception for it to be caught in KafkaApis
        throw e
    }

    partitionState.keys.foreach { partition =>
      stateChangeLogger.trace(s"Completed LeaderAndIsr request correlationId $correlationId from controller $controllerId " +
        s"epoch $controllerEpoch for the become-leader transition for partition ${partition.topicPartition}")
    }

    partitionsToMakeLeaders
  }

  /*
   * Make the current broker to become follower for a given set of partitions by:
   *
   * 1. Remove these partitions from the leader partitions set.
   * 2. Mark the replicas as followers so that no more data can be added from the producer clients.
   * 3. Stop fetchers for these partitions so that no more data can be added by the replica fetcher threads.
   * 4. Truncate the log and checkpoint offsets for these partitions.
   * 5. Clear the produce and fetch requests in the purgatory
   * 6. If the broker is not shutting down, add the fetcher to the new leaders.
   *
   * The ordering of doing these steps make sure that the replicas in transition will not
   * take any more messages before checkpointing offsets so that all messages before the checkpoint
   * are guaranteed to be flushed to disks
   *
   * If an unexpected error is thrown in this function, it will be propagated to KafkaApis where
   * the error message will be set on each partition since we do not know which partition caused it. Otherwise,
   * return the set of partitions that are made follower due to this method
   */
  private def makeFollowers(controllerId: Int,
                            controllerEpoch: Int,
                            partitionStates: Map[Partition, LeaderAndIsrRequest.PartitionState],
                            correlationId: Int,
                            responseMap: mutable.Map[TopicPartition, Errors],
                            highWatermarkCheckpoints: OffsetCheckpoints) : Set[Partition] = {
    partitionStates.foreach { case (partition, partitionState) =>
      stateChangeLogger.trace(s"Handling LeaderAndIsr request correlationId $correlationId from controller $controllerId " +
        s"epoch $controllerEpoch starting the become-follower transition for partition ${partition.topicPartition} with leader " +
        s"${partitionState.basePartitionState.leader}")
    }

    for (partition <- partitionStates.keys)
      responseMap.put(partition.topicPartition, Errors.NONE)

    val partitionsToMakeFollower: mutable.Set[Partition] = mutable.Set()
    try {
      // TODO: Delete leaders from LeaderAndIsrRequest
      partitionStates.foreach { case (partition, partitionStateInfo) =>
        val newLeaderBrokerId = partitionStateInfo.basePartitionState.leader
        try {
          metadataCache.getAliveBrokers.find(_.id == newLeaderBrokerId) match {
            // Only change partition state when the leader is available
            case Some(_) =>
              if (partition.makeFollower(controllerId, partitionStateInfo, correlationId, highWatermarkCheckpoints))
                partitionsToMakeFollower += partition
              else
                stateChangeLogger.info(s"Skipped the become-follower state change after marking its partition as " +
                  s"follower with correlation id $correlationId from controller $controllerId epoch $controllerEpoch " +
                  s"for partition ${partition.topicPartition} (last update " +
                  s"controller epoch ${partitionStateInfo.basePartitionState.controllerEpoch}) " +
                  s"since the new leader $newLeaderBrokerId is the same as the old leader")
            case None =>
              // The leader broker should always be present in the metadata cache.
              // If not, we should record the error message and abort the transition process for this partition
              stateChangeLogger.error(s"Received LeaderAndIsrRequest with correlation id $correlationId from " +
                s"controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition} " +
                s"(last update controller epoch ${partitionStateInfo.basePartitionState.controllerEpoch}) " +
                s"but cannot become follower since the new leader $newLeaderBrokerId is unavailable.")
              // Create the local replica even if the leader is unavailable. This is required to ensure that we include
              // the partition's high watermark in the checkpoint file (see KAFKA-1647)
              partition.createLogIfNotExists(localBrokerId, isNew = partitionStateInfo.isNew, isFutureReplica = false,
                highWatermarkCheckpoints)
          }
        } catch {
          case e: KafkaStorageException =>
            stateChangeLogger.error(s"Skipped the become-follower state change with correlation id $correlationId from " +
              s"controller $controllerId epoch $controllerEpoch for partition ${partition.topicPartition} " +
              s"(last update controller epoch ${partitionStateInfo.basePartitionState.controllerEpoch}) with leader " +
              s"$newLeaderBrokerId since the replica for the partition is offline due to disk error $e")
            val dirOpt = getLogDir(partition.topicPartition)
            error(s"Error while making broker the follower for partition $partition with leader " +
              s"$newLeaderBrokerId in dir $dirOpt", e)
            responseMap.put(partition.topicPartition, Errors.KAFKA_STORAGE_ERROR)
        }
      }

      replicaFetcherManager.removeFetcherForPartitions(partitionsToMakeFollower.map(_.topicPartition))
      partitionsToMakeFollower.foreach { partition =>
        stateChangeLogger.trace(s"Stopped fetchers as part of become-follower request from controller $controllerId " +
          s"epoch $controllerEpoch with correlation id $correlationId for partition ${partition.topicPartition} with leader " +
          s"${partitionStates(partition).basePartitionState.leader}")
      }

      partitionsToMakeFollower.foreach { partition =>
        val topicPartitionOperationKey = TopicPartitionOperationKey(partition.topicPartition)
        delayedProducePurgatory.checkAndComplete(topicPartitionOperationKey)
        delayedFetchPurgatory.checkAndComplete(topicPartitionOperationKey)
      }

      partitionsToMakeFollower.foreach { partition =>
        stateChangeLogger.trace(s"Truncated logs and checkpointed recovery boundaries for partition " +
          s"${partition.topicPartition} as part of become-follower request with correlation id $correlationId from " +
          s"controller $controllerId epoch $controllerEpoch with leader ${partitionStates(partition).basePartitionState.leader}")
      }

      if (isShuttingDown.get()) {
        partitionsToMakeFollower.foreach { partition =>
          stateChangeLogger.trace(s"Skipped the adding-fetcher step of the become-follower state " +
            s"change with correlation id $correlationId from controller $controllerId epoch $controllerEpoch for " +
            s"partition ${partition.topicPartition} with leader ${partitionStates(partition).basePartitionState.leader} " +
            "since it is shutting down")
        }
      } else {
        // we do not need to check if the leader exists again since this has been done at the beginning of this process
        val partitionsToMakeFollowerWithLeaderAndOffset = partitionsToMakeFollower.map { partition =>
          val leader = metadataCache.getAliveBrokers.find(_.id == partition.leaderReplicaIdOpt.get).get
            .brokerEndPoint(config.interBrokerListenerName)
          val fetchOffset = partition.localLogOrException.highWatermark
          partition.topicPartition -> InitialFetchState(leader, partition.getLeaderEpoch, fetchOffset)
       }.toMap

        replicaFetcherManager.addFetcherForPartitions(partitionsToMakeFollowerWithLeaderAndOffset)
        partitionsToMakeFollowerWithLeaderAndOffset.foreach { case (partition, initialFetchState) =>
          stateChangeLogger.trace(s"Started fetcher to new leader as part of become-follower " +
            s"request from controller $controllerId epoch $controllerEpoch with correlation id $correlationId for " +
            s"partition $partition with leader ${initialFetchState.leader}")
        }
      }
    } catch {
      case e: Throwable =>
        stateChangeLogger.error(s"Error while processing LeaderAndIsr request with correlationId $correlationId " +
          s"received from controller $controllerId epoch $controllerEpoch", e)
        // Re-throw the exception for it to be caught in KafkaApis
        throw e
    }

    partitionStates.keys.foreach { partition =>
      stateChangeLogger.trace(s"Completed LeaderAndIsr request correlationId $correlationId from controller $controllerId " +
        s"epoch $controllerEpoch for the become-follower transition for partition ${partition.topicPartition} with leader " +
        s"${partitionStates(partition).basePartitionState.leader}")
    }

    partitionsToMakeFollower
  }

  private def maybeShrinkIsr(): Unit = {
    trace("Evaluating ISR list of partitions to see which replicas can be removed from the ISR")

    // Shrink ISRs for non offline partitions
    allPartitions.keys.foreach { topicPartition =>
      nonOfflinePartition(topicPartition).foreach(_.maybeShrinkIsr(config.replicaLagTimeMaxMs))
    }
  }

  /**
   * Update the follower's fetch state on the leader based on the last fetch request and update `readResult`.
   * If the follower replica is not recognized to be one of the assigned replicas, do not update
   * `readResult` so that log start/end offset and high watermark is consistent with
   * records in fetch response. Log start/end offset and high watermark may change not only due to
   * this fetch request, e.g., rolling new log segment and removing old log segment may move log
   * start offset further than the last offset in the fetched records. The followers will get the
   * updated leader's state in the next fetch response.
   */
  private def updateFollowerFetchState(followerId: Int,
                                       readResults: Seq[(TopicPartition, LogReadResult)]): Seq[(TopicPartition, LogReadResult)] = {
    readResults.map { case (topicPartition, readResult) =>
      val updatedReadResult = if (readResult.error != Errors.NONE) {
        debug(s"Skipping update of fetch state for follower $followerId since the " +
          s"log read returned error ${readResult.error}")
        readResult
      } else {
        nonOfflinePartition(topicPartition) match {
          case Some(partition) =>
            if (partition.updateFollowerFetchState(followerId,
              followerFetchOffsetMetadata = readResult.info.fetchOffsetMetadata,
              followerStartOffset = readResult.followerLogStartOffset,
              followerFetchTimeMs = readResult.fetchTimeMs,
              leaderEndOffset = readResult.leaderLogEndOffset)) {
              readResult
            } else {
              warn(s"Leader $localBrokerId failed to record follower $followerId's position " +
                s"${readResult.info.fetchOffsetMetadata.messageOffset} since the replica is not recognized to be " +
                s"one of the assigned replicas ${partition.allReplicaIds.mkString(",")} " +
                s"for partition $topicPartition. Empty records will be returned for this partition.")
              readResult.withEmptyFetchInfo
            }
          case None =>
            warn(s"While recording the replica LEO, the partition $topicPartition hasn't been created.")
            readResult
        }
      }
      topicPartition -> updatedReadResult
    }
  }

  /**
   * 更新副本节点本地HighWaterMark
   * @param topicPartition topic-partition信息
   * @param followerId     副本节点ID
   * @param highWatermark  副本的ID的最新HighWaterMark
   */
  private def updateFollowerHighWatermark(topicPartition: TopicPartition, followerId: Int, highWatermark: Long): Unit = {
    nonOfflinePartition(topicPartition).flatMap(_.getReplica(followerId)) match {
      case Some(replica) => replica.updateLastSentHighWatermark(highWatermark)
      case None =>
        warn(s"While updating the HW for follower $followerId for partition $topicPartition, " +
          s"the replica could not be found.")
    }
  }

  /**
   * 获取leader身份的partition
   * @return
   */
  private def leaderPartitionsIterator: Iterator[Partition] =
  // leader身份的判断标准：是否有本地日志
    nonOfflinePartitionsIterator.filter(_.leaderLogIfLocal.isDefined)

  def getLogEndOffset(topicPartition: TopicPartition): Option[Long] =
    nonOfflinePartition(topicPartition).flatMap(_.leaderLogIfLocal.map(_.logEndOffset))

  /**
   * 副本管理器刷写HW到最高水位
   */
  def checkpointHighWatermarks(): Unit = {
    // 迭代所有没有下线的partition
    // 映射到一个列表中
    val localLogs = nonOfflinePartitionsIterator.flatMap { partition =>
      val logsList: mutable.Set[Log] = mutable.Set()
      partition.log.foreach(logsList.add)
      partition.futureLog.foreach(logsList.add)
      logsList
    }.toBuffer
    // 根据数据目录进行分组
    val logsByDir = localLogs.groupBy(_.dir.getParent)
    // 遍历每个数据目录和需要刷写的日志
    for ((dir, logs) <- logsByDir) {
      // 从partition信息中获取每个日志文件最新的HW
      // 每个partition的日志目录对应的都是本地副本
      val hwms = logs.map(log => log.topicPartition -> log.highWatermark).toMap
      try {
        // 写入最新的HW
        highWatermarkCheckpoints.get(dir).foreach(_.write(hwms))
      } catch {
        case e: KafkaStorageException =>
          error(s"Error while writing to highwatermark file in directory $dir", e)
      }
    }
  }

  // Used only by test
  def markPartitionOffline(tp: TopicPartition): Unit = replicaStateChangeLock synchronized {
    allPartitions.put(tp, HostedPartition.Offline)
    Partition.removeMetrics(tp)
  }

  // logDir should be an absolute path
  // sendZkNotification is needed for unit test
  def handleLogDirFailure(dir: String, sendZkNotification: Boolean = true): Unit = {
    if (!logManager.isLogDirOnline(dir))
      return
    info(s"Stopping serving replicas in dir $dir")
    replicaStateChangeLock synchronized {
      val newOfflinePartitions = nonOfflinePartitionsIterator.filter { partition =>
        partition.log.exists { _.dir.getParent == dir }
      }.map(_.topicPartition).toSet

      val partitionsWithOfflineFutureReplica = nonOfflinePartitionsIterator.filter { partition =>
        partition.futureLog.exists { _.dir.getParent == dir }
      }.toSet

      replicaFetcherManager.removeFetcherForPartitions(newOfflinePartitions)
      replicaAlterLogDirsManager.removeFetcherForPartitions(newOfflinePartitions ++ partitionsWithOfflineFutureReplica.map(_.topicPartition))

      partitionsWithOfflineFutureReplica.foreach(partition => partition.removeFutureLocalReplica(deleteFromLogDir = false))
      newOfflinePartitions.foreach { topicPartition =>
        markPartitionOffline(topicPartition)
      }
      newOfflinePartitions.map(_.topic).foreach { topic: String =>
        maybeRemoveTopicMetrics(topic)
      }
      highWatermarkCheckpoints = highWatermarkCheckpoints.filter { case (checkpointDir, _) => checkpointDir != dir }

      info(s"Broker $localBrokerId stopped fetcher for partitions ${newOfflinePartitions.mkString(",")} and stopped moving logs " +
           s"for partitions ${partitionsWithOfflineFutureReplica.mkString(",")} because they are in the failed log directory $dir.")
    }
    logManager.handleLogDirFailure(dir)

    if (sendZkNotification)
      zkClient.propagateLogDirEvent(localBrokerId)
    info(s"Stopped serving replicas in dir $dir")
  }

  def removeMetrics(): Unit = {
    removeMetric("LeaderCount")
    removeMetric("PartitionCount")
    removeMetric("OfflineReplicaCount")
    removeMetric("UnderReplicatedPartitions")
    removeMetric("UnderMinIsrPartitionCount")
    removeMetric("AtMinIsrPartitionCount")
  }

  // High watermark do not need to be checkpointed only when under unit tests
  def shutdown(checkpointHW: Boolean = true): Unit = {
    info("Shutting down")
    removeMetrics()
    if (logDirFailureHandler != null)
      logDirFailureHandler.shutdown()
    replicaFetcherManager.shutdown()
    replicaAlterLogDirsManager.shutdown()
    delayedFetchPurgatory.shutdown()
    delayedProducePurgatory.shutdown()
    delayedDeleteRecordsPurgatory.shutdown()
    delayedElectLeaderPurgatory.shutdown()
    if (checkpointHW)
      checkpointHighWatermarks()
    replicaSelectorOpt.foreach(_.close)
    info("Shut down completely")
  }

  protected def createReplicaFetcherManager(metrics: Metrics, time: Time, threadNamePrefix: Option[String], quotaManager: ReplicationQuotaManager) = {
    new ReplicaFetcherManager(config, this, metrics, time, threadNamePrefix, quotaManager)
  }

  protected def createReplicaAlterLogDirsManager(quotaManager: ReplicationQuotaManager, brokerTopicStats: BrokerTopicStats) = {
    new ReplicaAlterLogDirsManager(config, this, quotaManager, brokerTopicStats)
  }

  /**
   * 创建副本节点选择器
   * @return 副本节点选择器
   */
  protected def createReplicaSelector(): Option[ReplicaSelector] = {
    config.replicaSelectorClassName.map { className =>
      // 通过副本选择器类名创建的是临时副本选择器
      val tmpReplicaSelector: ReplicaSelector = CoreUtils.createObject[ReplicaSelector](className)
      tmpReplicaSelector.configure(config.originals())
      tmpReplicaSelector
    }
  }

  def lastOffsetForLeaderEpoch(requestedEpochInfo: Map[TopicPartition, OffsetsForLeaderEpochRequest.PartitionData]): Map[TopicPartition, EpochEndOffset] = {
    requestedEpochInfo.map { case (tp, partitionData) =>
      val epochEndOffset = getPartition(tp) match {
        case HostedPartition.Online(partition) =>
          partition.lastOffsetForLeaderEpoch(partitionData.currentLeaderEpoch, partitionData.leaderEpoch,
            fetchOnlyFromLeader = true)

        case HostedPartition.Offline =>
          new EpochEndOffset(Errors.KAFKA_STORAGE_ERROR, UNDEFINED_EPOCH, UNDEFINED_EPOCH_OFFSET)

        case HostedPartition.None if metadataCache.contains(tp) =>
          new EpochEndOffset(Errors.NOT_LEADER_FOR_PARTITION, UNDEFINED_EPOCH, UNDEFINED_EPOCH_OFFSET)

        case HostedPartition.None =>
          new EpochEndOffset(Errors.UNKNOWN_TOPIC_OR_PARTITION, UNDEFINED_EPOCH, UNDEFINED_EPOCH_OFFSET)
      }
      tp -> epochEndOffset
    }
  }

  def electLeaders(
    controller: KafkaController,
    partitions: Set[TopicPartition],
    electionType: ElectionType,
    responseCallback: Map[TopicPartition, ApiError] => Unit,
    requestTimeout: Int
  ): Unit = {

    val deadline = time.milliseconds() + requestTimeout

    def electionCallback(results: Map[TopicPartition, Either[ApiError, Int]]): Unit = {
      val expectedLeaders = mutable.Map.empty[TopicPartition, Int]
      val failures = mutable.Map.empty[TopicPartition, ApiError]
      results.foreach {
        case (partition, Right(leader)) => expectedLeaders += partition -> leader
        case (partition, Left(error)) => failures += partition -> error
      }

      if (expectedLeaders.nonEmpty) {
        val watchKeys = expectedLeaders.iterator.map {
          case (tp, _) => TopicPartitionOperationKey(tp)
        }.toIndexedSeq

        delayedElectLeaderPurgatory.tryCompleteElseWatch(
          new DelayedElectLeader(
            math.max(0, deadline - time.milliseconds()),
            expectedLeaders,
            failures,
            this,
            responseCallback
          ),
          watchKeys
        )
      } else {
          // There are no partitions actually being elected, so return immediately
          responseCallback(failures)
      }
    }

    controller.electLeaders(partitions, electionType, electionCallback)
  }
}
