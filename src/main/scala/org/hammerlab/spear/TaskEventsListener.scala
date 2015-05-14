package org.hammerlab.spear

import org.apache.spark.scheduler.{SparkListenerTaskEnd, SparkListenerTaskGettingResult, SparkListenerTaskStart, SparkListener}
import com.foursquare.rogue.spindle.{SpindleQuery => Q}
import com.foursquare.rogue.spindle.SpindleRogue._
import org.hammerlab.spear.TaskEndReasonType.SUCCESS

trait TaskEventsListener extends HasDatabaseService with DBHelpers {
  this: SparkListener =>

  // Task events
  override def onTaskStart(taskStart: SparkListenerTaskStart): Unit = {
    val ti = taskStart.taskInfo
    db.insert(
      Task.newBuilder
      .id(ti.taskId)
      .index(ti.index)
      .attempt(ti.attempt)
      .stageId(taskStart.stageId)
      .stageAttemptId(taskStart.stageAttemptId)
      .startTime(ti.launchTime)
      .execId(ti.executorId)
      .taskLocality(TaskLocality.findById(ti.taskLocality.id))
      .speculative(ti.speculative)
      .result()
    )

    val q = Q(Stage)
            .where(_.id eqs taskStart.stageId)
            .and(_.attempt eqs taskStart.stageAttemptId)
            .findAndModify(_.tasksStarted inc 1)

    db.findAndUpdateOne(q)
  }

  override def onTaskGettingResult(taskGettingResult: SparkListenerTaskGettingResult): Unit = {
    db.findAndUpdateOne(
      Q(Task)
      .where(_.id eqs taskGettingResult.taskInfo.taskId)
      .findAndModify(_.gettingResult setTo true)
    )
  }

  override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {

    val reason = SparkIDL.taskEndReason(taskEnd.reason)
    val success = reason.tpeOption().exists(_ == SUCCESS)
    val tm = SparkIDL.taskMetrics(taskEnd.taskMetrics)
    val ti = taskEnd.taskInfo
    val tid = ti.taskId

    db.findAndUpdateOne(
      Q(Task)
      .where(_.id eqs tid)
      .findAndModify(_.taskType setTo taskEnd.taskType)
      .and(_.taskEndReason setTo reason)
      .and(_.metrics push tm)
    )

    db.findAndUpdateOne(
      Q(Stage)
      .where(_.id eqs taskEnd.stageId)
      .and(_.attempt eqs taskEnd.stageAttemptId)
      .findAndModify(s => (
        if (success) s.tasksSucceeded
        else s.tasksFailed
        ) inc 1
        )
    )

    val metricsUpdates = Seq((tid, taskEnd.stageId, taskEnd.stageAttemptId, taskEnd.taskMetrics))
    val metricsDeltas = getTaskMetricsDeltasMap(metricsUpdates)
    updateStageMetrics(metricsUpdates, metricsDeltas)
    updateExecutorMetrics(ti.executorId, metricsUpdates, metricsDeltas)
  }


}