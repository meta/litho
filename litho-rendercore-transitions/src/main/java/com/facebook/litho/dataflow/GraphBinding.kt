/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.litho.dataflow

import androidx.annotation.VisibleForTesting

/**
 * Defines the relationship of a set of input values to a set of output values where the values from
 * the input nodes 'flow into' the output nodes. For example, input values could be a touch X/Y or a
 * layout value, and output values could be the X/Y position of a View or its opacity. Input and
 * output values can be connected to each other via intermediate operators like springs or timing.
 *
 * NB: ValueNodes can be referenced by multiple GraphBindings (e.g. a view property).
 */
class GraphBinding private constructor(private val dataFlowGraph: DataFlowGraph) {

  private val bindings: Bindings = Bindings()
  private val allNodes: ArrayList<ValueNode> = ArrayList()
  private var listener: BindingListener? = null
  var isActive: Boolean = false
    private set

  private var hasBeenActivated: Boolean = false

  /**
   * Adds a connection between two nodes to this graph. The connection will not be actualized until
   * [activate] is called, and will be removed once [deactivate] is called.
   */
  fun addBinding(fromNode: ValueNode, toNode: ValueNode, name: String) {
    if (hasBeenActivated) {
      throw RuntimeException(
          "Trying to add binding after DataFlowGraph has already been activated.",
      )
    }
    bindings.addBinding(fromNode, toNode, name)
    allNodes.add(fromNode)
    allNodes.add(toNode)
  }

  fun addBinding(fromNode: ValueNode, toNode: ValueNode) {
    addBinding(fromNode, toNode, ValueNode.DEFAULT_INPUT)
  }

  /** @return all nodes that have a binding defined in this [GraphBinding]. */
  fun getAllNodes(): ArrayList<ValueNode> = allNodes

  /**
   * Activates a binding, adding the sub-graph defined by this binding to the main [DataFlowGraph]
   * associated with this binding. This is expected to be called from framework code and should not
   * be called by the end developer.
   */
  fun activate() {
    bindings.applyBindings()
    hasBeenActivated = true
    isActive = true

    dataFlowGraph.register(this)
  }

  /**
   * Deactivates this binding which, as you might guess, is the reverse of activating it: the
   * sub-graph associated with this binding is removed from the main [DataFlowGraph]. As with
   * [activate], this is expected to only be called by framework code and not the end developer.
   */
  fun deactivate() {
    if (!isActive) {
      return
    }

    isActive = false
    dataFlowGraph.unregister(this)
    bindings.removeBindings()
  }

  fun notifyNodesHaveFinished() {
    listener?.onAllNodesFinished(this)
    deactivate()
  }

  /** Sets the [BindingListener]. */
  fun setListener(listener: BindingListener?) {
    if (this.listener != null && listener != null) {
      throw RuntimeException("Overriding existing listener!")
    }
    this.listener = listener
  }

  private class Bindings {

    private val fromNodes: ArrayList<ValueNode> = ArrayList()
    private val toNodes: ArrayList<ValueNode> = ArrayList()
    private val inputNames: ArrayList<String> = ArrayList()

    fun addBinding(fromNode: ValueNode, toNode: ValueNode, name: String) {
      fromNodes.add(fromNode)
      toNodes.add(toNode)
      inputNames.add(name)
    }

    fun applyBindings() {
      for (i in fromNodes.indices) {
        val fromNode = fromNodes[i]
        val toNode = toNodes[i]
        val name = inputNames[i]
        val currentInput = toNode.getInputUnsafe(name)

        if (currentInput != null) {
          unbindNodes(currentInput, toNode, name)
        }

        fromNode.addOutput(toNode)
        toNode.setInput(name, fromNode)
      }
    }

    fun removeBindings() {
      for (i in fromNodes.indices) {
        val fromNode = fromNodes[i]
        val toNode = toNodes[i]
        val name = inputNames[i]

        // Nodes may have already been re-bound
        if (toNode.getInputUnsafe(name) === fromNode) {
          unbindNodes(fromNode, toNode, name)
        }
      }
    }

    companion object {
      private fun unbindNodes(fromNode: ValueNode, toNode: ValueNode, name: String) {
        fromNode.removeOutput(toNode)
        toNode.removeInput(name)
      }
    }
  }

  companion object {
    /** Creates a [GraphBinding] associated with the default [DataFlowGraph] instance. */
    fun create(): GraphBinding = GraphBinding(DataFlowGraph.getInstance())

    @VisibleForTesting
    fun create(dataFlowGraph: DataFlowGraph): GraphBinding = GraphBinding(dataFlowGraph)
  }
}
