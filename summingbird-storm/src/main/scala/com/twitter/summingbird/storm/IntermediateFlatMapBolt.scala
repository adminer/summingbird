/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird.storm

import backtype.storm.tuple.{Fields, Tuple, Values}
import com.twitter.chill.Externalizer
import com.twitter.storehaus.algebra.MergeableStore.enrich
import com.twitter.summingbird.batch.Timestamp
import com.twitter.summingbird.storm.option.{ AnchorTuples, FlatMapStormMetrics }
import com.twitter.summingbird.online.FlatMapOperation

import Constants._

/**
  * This bolt is used for intermediate flatMapping before a grouping.
  * Its output storm-tuple contains a single scala.Tuple2[Long, U] at
  * the 0 position. The Long is the timestamp of the source object
  * from which U was derived. Each U is one of the output items of the
  * flatMapOp.
  */
class IntermediateFlatMapBolt[T](
  @transient flatMapOp: FlatMapOperation[T, _],
  metrics: FlatMapStormMetrics,
  anchor: AnchorTuples,
  shouldEmit: Boolean) extends BaseBolt(metrics.metrics) {

  val lockedOp = Externalizer(flatMapOp)

  def toValues(time: Timestamp, item: Any): Values = new Values((time.milliSinceEpoch, item))

  override val fields = Some(new Fields("pair"))

  override def execute(tuple: Tuple) {
    val (timeMs, t) = tuple.getValue(0).asInstanceOf[(Long, T)]
    val time = Timestamp(timeMs)

    lockedOp.get(t).foreach { items =>
      if(shouldEmit) {
        items.foreach { u =>
          onCollector { col =>
            val values = toValues(time, u)
            if (anchor.anchor)
              col.emit(tuple, values)
            else col.emit(values)
          }
        }
      }
      ack(tuple)
    }
  }

  override def cleanup { lockedOp.get.close }
}
