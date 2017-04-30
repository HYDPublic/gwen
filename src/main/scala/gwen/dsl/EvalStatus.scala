/*
 * Copyright 2014-2017 Branko Juric, Brady Wood
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gwen.dsl

import scala.concurrent.duration._
import java.util.Date
import gwen.Predefs.Formatting._

/** Captures the evaluation status of a [[SpecNode]]. */
sealed trait EvalStatus {
  
  val status: StatusKeyword.Value
  val nanos: Long
  
  /** Returns the duration in nanoseconds. */
  def duration: Duration = Duration.fromNanos(nanos)
  
  /** Must be overriden to return exit code. */
  def exitCode: Int
  
  /** Must be overriden to return an emoticon. */
  def emoticon: String
  
  override def toString: String =
    if (nanos > 0) {
      s"[${formatDuration(duration)}] $status"
    } else status.toString
}

/**
  * Defines a passed evaluation status.
  * 
  * @param nanos the duration in nanoseconds
  */
case class Passed(nanos: Long) extends EvalStatus {
  val status = StatusKeyword.Passed
  override def exitCode = 0
  override def emoticon = "[:)]"
}

/**
  * Defines a failed evaluation status.
  * 
  * @param nanos the duration in nanoseconds
  * @param error the error message
  */
case class Failed(nanos: Long, error: Throwable) extends EvalStatus {
  val status = StatusKeyword.Failed
  val timestamp = new Date()
  override def exitCode = 1
  override def emoticon = "[:(]"
}

/** Defines the skipped status. */
case object Skipped extends EvalStatus {
  val nanos = 0L
  val status = StatusKeyword.Skipped
  override def exitCode = 0
  override def emoticon = "[:|]"
}

/**
  * Defines the pending status.
  */
case object Pending extends EvalStatus {
  val nanos = 0L 
  val status = StatusKeyword.Pending
  override def exitCode = 1
  override def emoticon = "[:|]"
}

/** Defines the loaded status. */
case object Loaded extends EvalStatus {
  val nanos = 0L
  val status = StatusKeyword.Loaded
  override def exitCode = 0
  override def emoticon = "[:)]"
}

object EvalStatus {

  import gwen.Predefs.DurationOps
  
  /**
    * Function for getting the effective evaluation status of a given list of statuses.
    * 
    * @param statuses the list of evaluation statuses
    */
  def apply(statuses: List[EvalStatus]): EvalStatus = {
    if (statuses.nonEmpty) {
      val duration = DurationOps.sum(statuses.map(_.duration))
      statuses.collectFirst { case failed @ Failed(_, _) => failed } match {
        case Some(failed) => Failed(duration.toNanos, failed.error)  
        case None =>
          if (statuses.forall(_ == Loaded)) {
            Loaded
          } else {
            statuses.filter(_ != Loaded).lastOption match {
              case Some(lastStatus) => lastStatus match {
                case Passed(_) => Passed(duration.toNanos)
                case Skipped => lastStatus
                case _ => Pending
              }
              case None => Pending
            }
          }
      }
    } else Skipped
  }

  /**
    * Returns true if the given status is an evaluated status. A status is considered evalauted if it is
    * Passed or Failed.
    *
    * @param status the status to check
    * @return true if the status is Passed or Failed, false otherwise
    */
  def isEvaluated(status: StatusKeyword.Value): Boolean = status == StatusKeyword.Passed || status == StatusKeyword.Failed
  
}

/**
  * Enumeration of supported status keywords.
  * 
  * @author Branko Juric
  */
object StatusKeyword extends Enumeration {

  val Passed, Failed, Skipped, Pending, Loaded = Value
  
  val reportables = List(Passed, Failed, Skipped, Pending)

  /**
    * Groups counts by status.
    * 
    * @param statuses the statuses to group
    */
  def countsByStatus(statuses: List[EvalStatus]): Map[StatusKeyword.Value, Int] = 
    statuses.groupBy(_.status) map { case (k, v) => (k, v.size) }
}