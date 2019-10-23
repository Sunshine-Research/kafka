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

package kafka.log

import org.apache.kafka.common.requests.ListOffsetResponse

sealed trait IndexEntry {
  // We always use Long for both key and value to avoid boxing.
  def indexKey: Long
  def indexValue: Long
}

/**
 * 逻辑log offset和具有给定offset的消息集条目开头的某些日志文件中的物理位之间的映射
 */
case class OffsetPosition(offset: Long, position: Int) extends IndexEntry {
  /**
   * @return 逻辑log offset
   */
  override def indexKey = offset

  /**
   * @return 物理文件地址
   */
  override def indexValue = position.toLong
}


/**
 * The mapping between a timestamp to a message offset. The entry means that any message whose timestamp is greater
 * than that timestamp must be at or after that offset.
 * @param timestamp The max timestamp before the given offset.
 * @param offset The message offset.
 */
case class TimestampOffset(timestamp: Long, offset: Long) extends IndexEntry {
  override def indexKey = timestamp
  override def indexValue = offset
}

object TimestampOffset {
  val Unknown = TimestampOffset(ListOffsetResponse.UNKNOWN_TIMESTAMP, ListOffsetResponse.UNKNOWN_OFFSET)
}
