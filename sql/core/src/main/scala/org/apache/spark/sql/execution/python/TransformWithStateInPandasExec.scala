/*
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
package org.apache.spark.sql.execution.python

import scala.concurrent.duration.NANOSECONDS

import org.apache.spark.JobArtifactSet
import org.apache.spark.api.python.{ChainedPythonFunctions, PythonEvalType}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Ascending, Attribute, Expression, PythonUDF, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.Distribution
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}
import org.apache.spark.sql.execution.python.PandasGroupUtils.{executePython, groupAndProject, resolveArgOffsets}
import org.apache.spark.sql.execution.streaming.{StatefulOperatorPartitioning, StatefulOperatorStateInfo, StatefulProcessorHandleImpl, StateStoreWriter, WatermarkSupport}
import org.apache.spark.sql.execution.streaming.state.{StateStore, StateStoreOps}
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.{BinaryType, StructType}
import org.apache.spark.util.CompletionIterator

case class TransformWithStateInPandasExec(
    functionExpr: Expression,
    groupingAttributes: Seq[Attribute],
    output: Seq[Attribute],
    outputMode: OutputMode,
    stateInfo: Option[StatefulOperatorStateInfo],
    batchTimestampMs: Option[Long],
    eventTimeWatermarkForLateEvents: Option[Long],
    eventTimeWatermarkForEviction: Option[Long],
    child: SparkPlan) extends UnaryExecNode with StateStoreWriter with WatermarkSupport {

  private val pythonUDF = functionExpr.asInstanceOf[PythonUDF]
  private val pythonFunction = pythonUDF.func
  private val chainedFunc =
    Seq((ChainedPythonFunctions(Seq(pythonFunction)), pythonUDF.resultId.id))

  private val sessionLocalTimeZone = conf.sessionLocalTimeZone
  private val pythonRunnerConf = ArrowPythonRunner.getPythonRunnerConfMap(conf)
  private[this] val jobArtifactUUID = JobArtifactSet.getCurrentJobArtifactState.map(_.uuid)


  /** The keys that may have a watermark attribute. */
  override def keyExpressions: Seq[Attribute] = groupingAttributes

  protected val schemaForKeyRow: StructType = new StructType().add("key", BinaryType)

  protected val schemaForValueRow: StructType = new StructType().add("value", BinaryType)

  override def requiredChildDistribution: Seq[Distribution] = {
    StatefulOperatorPartitioning.getCompatibleDistribution(groupingAttributes,
      getStateInfo, conf) ::
      Nil
  }

  override def requiredChildOrdering: Seq[Seq[SortOrder]] = Seq(
    groupingAttributes.map(SortOrder(_, Ascending)))

  /**
   * Produces the result of the query as an `RDD[InternalRow]`
   *
   * TODO Override by concrete implementations of SparkPlan.
   */
  override protected def doExecute(): RDD[InternalRow] = {
    metrics

    val (dedupAttributes, argOffsets) = resolveArgOffsets(child.output, groupingAttributes)

    child.execute().mapPartitionsWithStateStore[InternalRow](
      getStateInfo,
      schemaForKeyRow,
      schemaForValueRow,
      numColsPrefixKey = 0,
      session.sqlContext.sessionState,
      Some(session.sqlContext.streams.stateStoreCoordinator),
      useColumnFamilies = true,
      useMultipleValuesPerKey = true
    ) {
      case (store: StateStore, dataIterator: Iterator[InternalRow]) =>
        val allUpdatesTimeMs = longMetric("allUpdatesTimeMs")
        val commitTimeMs = longMetric("commitTimeMs")
        val currentTimeNs = System.nanoTime
        val updatesStartTimeNs = currentTimeNs

        val data = groupAndProject(dataIterator, groupingAttributes, child.output, dedupAttributes)

        // TOOD (sahnib): fix this
        val processorHandle = new StatefulProcessorHandleImpl(store, getStateInfo.queryRunId, null)
        val runner = new TransformWithStateInPandasPythonRunner(
          chainedFunc,
          PythonEvalType.SQL_TRANSFORM_WITH_STATE,
          Array(argOffsets),
          DataTypeUtils.fromAttributes(dedupAttributes),
          processorHandle,
          sessionLocalTimeZone,
          pythonRunnerConf,
          pythonMetrics,
          jobArtifactUUID
        )

        val outputIterator = executePython(data, output, runner)

        CompletionIterator[InternalRow, Iterator[InternalRow]](outputIterator, {
          // Note: Due to the iterator lazy execution, this metric also captures the time taken
          // by the upstream (consumer) operators in addition to the processing in this operator.
          allUpdatesTimeMs += NANOSECONDS.toMillis(System.nanoTime - updatesStartTimeNs)
          commitTimeMs += timeTakenMs {
            store.commit()
          }
          setStoreMetrics(store)
          setOperatorMetrics()
        })
    }
  }

  override protected def withNewChildInternal(newChild: SparkPlan): SparkPlan =
    copy(child = newChild)
}
