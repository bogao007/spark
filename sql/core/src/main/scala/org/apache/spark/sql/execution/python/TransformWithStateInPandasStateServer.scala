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

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.net.ServerSocket

import scala.collection.mutable

import com.google.protobuf.ByteString

import org.apache.spark.internal.Logging
import org.apache.spark.sql.execution.streaming.{ImplicitGroupingKeyTracker, StatefulProcessorHandleImpl, StatefulProcessorHandleState}
import org.apache.spark.sql.execution.streaming.state.StateMessage.{HandleState, ListStateCall, StatefulProcessorHandleCall, StateRequest}
import org.apache.spark.sql.streaming.{ListState, ValueState}

class TransformWithStateInPandasStateServer(
    private val stateServerSocket: ServerSocket,
    private val statefulProcessorHandle: StatefulProcessorHandleImpl)
  extends Runnable
  with Logging{

  private var inputStream: DataInputStream = _
  private var outputStream: DataOutputStream = _

  private val valueStates = mutable.HashMap[String, ValueState[String]]()
  private val listStates = mutable.HashMap[String, ListState[String]]()

  def run(): Unit = {
    logWarning(s"Waiting for connection from Python worker")
    val listeningSocket = stateServerSocket.accept()
    logWarning(s"listening on socket - ${listeningSocket.getLocalAddress}")

    inputStream = new DataInputStream(
      new BufferedInputStream(listeningSocket.getInputStream))
    outputStream = new DataOutputStream(
      new BufferedOutputStream(listeningSocket.getOutputStream)
    )

    while (listeningSocket.isConnected &&
      statefulProcessorHandle.getHandleState != StatefulProcessorHandleState.CLOSED) {

      logWarning(s"reading the version")
      val version = inputStream.readInt()

      if (version != -1) {
        logWarning(s"version = ${version}")
        assert(version == 0)
        val messageLen = inputStream.readInt()
        logWarning(s"parsing a message of ${messageLen} bytes")

        val messageBytes = new Array[Byte](messageLen)
        inputStream.read(messageBytes)
        logWarning(s"read bytes = ${messageBytes.mkString("Array(", ", ", ")")}")

        val message = StateRequest.parseFrom(ByteString.copyFrom(messageBytes))

        logWarning(s"read message = $message")
        handleRequest(message)
        logWarning(s"flush output stream")

        outputStream.writeInt(0)
        outputStream.flush()
      }
    }

    logWarning(s"done from the state server thread")
  }

  private def handleRequest(message: StateRequest): Unit = {
    if (message.getMethodCase == StateRequest.MethodCase.STATEFULPROCESSORHANDLECALL) {
      val statefulProcessorHandleRequest = message.getStatefulProcessorHandleCall
      if (statefulProcessorHandleRequest.getMethodCase ==
        StatefulProcessorHandleCall.MethodCase.SETHANDLESTATE) {
        val requestedState = statefulProcessorHandleRequest.getSetHandleState.getState
        requestedState match {
          case HandleState.INITIALIZED =>
            logWarning(s"set handle state to Initialized")
            statefulProcessorHandle.setHandleState(StatefulProcessorHandleState.INITIALIZED)
          case _ =>
        }
      } else if (statefulProcessorHandleRequest.getMethodCase ==
        StatefulProcessorHandleCall.MethodCase.GETLISTSTATE) {
        val listStateName = statefulProcessorHandleRequest.getGetListState.getStateName
        initializeState("ListState", listStateName)
      }
    } else if (message.getMethodCase == StateRequest.MethodCase.LISTSTATECALL) {
      val listStateCall = message.getListStateCall
      val methodCase = listStateCall.getMethodCase
      if (methodCase == ListStateCall.MethodCase.GET) {
        logInfo("Handling list state get")
        outputStream.writeInt(0)
        outputStream.flush()
      } else {
        outputStream.writeInt(1)
      }
    }
  }

  def setHandleState(handleState: String): Unit = {
    logWarning(s"setting handle state to $handleState")
    if (handleState == "CREATED") {
      statefulProcessorHandle.setHandleState(StatefulProcessorHandleState.CREATED)
      outputStream.writeInt(0)
    } else if (handleState == "INITIALIZED") {
      statefulProcessorHandle.setHandleState(StatefulProcessorHandleState.INITIALIZED)
      outputStream.writeInt(0)
    } else if (handleState == "CLOSED") {
      statefulProcessorHandle.setHandleState(StatefulProcessorHandleState.CLOSED)
      outputStream.writeInt(0)
    } else {
      // fail
      outputStream.writeInt(1)
    }
  }

  private def valueState(stateName: String, parts: Array[String]): Unit = {
    val stateOption = valueStates.get(stateName)
    val operation = parts(2)

    if (stateOption.isEmpty) {
      outputStream.writeInt(1)
    } else if (operation == "get") {
      val state = stateOption.get
      val key = parts(3)

      ImplicitGroupingKeyTracker.setImplicitKey(key)
      val result = state.getOption()
      outputStream.writeInt(0)
      if (result.isDefined) {
        outputStream.writeInt(1)
        logWarning(s"Writing ${result.get} to output")
        outputStream.writeUTF(s"${result.get}\n")
      } else {
        outputStream.writeInt(0)
      }
    } else if (operation == "set") {
      val state = stateOption.get
      val key = parts(3)
      val newState = parts(4)
      logWarning(s"updating state for $key to $newState.")

      ImplicitGroupingKeyTracker.setImplicitKey(key)
      state.update(newState)
      logWarning(s"writing success to output")
      outputStream.writeInt(0)
    } else {
      outputStream.writeInt(1)
    }
  }

  private def performRequest(message: String): Unit = {
    if (message.nonEmpty) {
      val parts: Array[String] = message.split(':')
      if (parts(0) == "setHandleState") {
        assert(parts.length == 2)
        setHandleState(parts(1))
      } else if (parts(0) == "getState") {
        val stateType = parts(1)
        val stateName = parts(2)

        initializeState(stateType, stateName)
      } else {
        val stateType = parts(0)
        val stateName = parts(1)

        stateType match {
          case "ValueState" =>
            valueState(stateName, parts)
          case _ =>
            outputStream.writeInt(1)
        }
      }
    }
  }

  private def initializeState(stateType: String, stateName: String): Unit = {
    if (stateType == "ValueState") {
      val state = statefulProcessorHandle.getValueState[String](stateName)
      valueStates.put(stateName, state)
      outputStream.writeInt(0)
    } else if (stateType == "ListState") {
      val state = statefulProcessorHandle.getListState[String](stateName)
      listStates.put(stateName, state)
      outputStream.writeInt(0)
    } else {
      outputStream.writeInt(1)
    }
  }
}