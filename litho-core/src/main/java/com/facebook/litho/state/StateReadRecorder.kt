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

package com.facebook.litho.state

import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.emptyScatterSet
import androidx.collection.mutableScatterSetOf
import com.facebook.infer.annotation.ThreadSafe
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.utils.LithoThreadLocal
import com.facebook.litho.utils.getOrSet
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@ThreadSafe
internal class StateReadRecorder private constructor() {

  internal var readSet: MutableScatterSet<StateId>? = null
  internal var currentId: Int = NO_ID
  internal var currentDescription: String? = null

  private var recordingEnabled: Boolean = true

  /**
   * Executes [scope] in the context of the current recorder to enable tracking of all state reads
   * during that execution which are then returned as a set of [StateId]s.
   *
   * @param treeId The id of the current tree
   * @param description Optional attribution label surfaced on cross-tree read violation reports
   * @param scope The scope to execute
   * @return A set of all state ids that were read during the execution of the scope.
   */
  @OptIn(ExperimentalContracts::class)
  private inline fun record(
      treeId: Int,
      description: String?,
      scope: () -> Unit,
  ): ScatterSet<StateId> {
    contract { callsInPlace(scope, InvocationKind.EXACTLY_ONCE) }
    return if (treeId == currentId && recordingEnabled) {
      // Re-entrant call. Simply run the scope and return an empty set.
      // The full read set will be delivered to the parent recorder at the end.
      scope()
      emptyScatterSet()
    } else {
      withContext(treeId, description, recordingEnabled = true) {
        scope()
        readSet ?: emptyScatterSet()
      }
    }
  }

  @OptIn(ExperimentalContracts::class)
  private inline fun <T> withContext(
      treeId: Int,
      description: String?,
      recordingEnabled: Boolean,
      scope: () -> T,
  ): T {
    contract { callsInPlace(scope, InvocationKind.EXACTLY_ONCE) }
    val previousReadSet = readSet
    val previousTreeId = currentId
    val previousDescription = currentDescription
    val previousRecordingEnabled = this.recordingEnabled
    readSet = null
    currentId = treeId
    currentDescription = description
    this.recordingEnabled = recordingEnabled
    try {
      return scope()
    } finally {
      readSet = previousReadSet
      currentId = previousTreeId
      currentDescription = previousDescription
      this.recordingEnabled = previousRecordingEnabled
    }
  }

  /**
   * Records a [state] that is being read
   *
   * State may be read within the context of an active recorder as long as the the recorder is for
   * the same tree where the state was created. This restriction may be lifted in future.
   *
   * While it's possible to read state without an active recorder (for example during a post-bind
   * operation), such reads will not be tracked.
   *
   * @throws IllegalStateException If state is read from a different tree than where it was created
   */
  private fun read(state: StateId): Boolean {
    val isStateReadFromDifferentTree = currentId != NO_ID && currentId != state.treeId
    if (isStateReadFromDifferentTree) {
      val violationPolicy = ComponentsConfiguration.defaultInstance.crossTreeStateReadPolicy
      if (violationPolicy == ComponentsConfiguration.LogicViolationPolicy.CRASH) {
        error(
            "State can only be read in the same tree where it was created. " +
                "State owner: ${state.ownerName}\n" +
                "State hookKey: ${state.hookGlobalKey.hookKey}\n" +
                "State tree: ${state.treeId}\n" +
                "Reader tree: $currentId",
        )
      }
      if (violationPolicy == ComponentsConfiguration.LogicViolationPolicy.LOG) {
        DebugInfoReporter.report(
            "StateReadTracking:ReadFromDifferentTree",
            renderStateId = state.treeId,
        ) {
          put("state.treeId", state.treeId)
          put("state.hookKey", state.hookGlobalKey.hookKey)
          put("state.owner", state.ownerName)
          put("reader.treeId", currentId)
          currentDescription?.let { put("reader.description", it) }
        }
      }
      return false
    }
    if (currentId != NO_ID && recordingEnabled) {
      (readSet ?: mutableScatterSetOf<StateId>().also { readSet = it }).add(state)
    }
    return true
  }

  companion object {
    private const val NO_ID = Int.MIN_VALUE
    private val recorder = LithoThreadLocal<StateReadRecorder>()

    private val current: StateReadRecorder
      get() = recorder.getOrSet { StateReadRecorder() }

    @OptIn(ExperimentalContracts::class)
    inline fun record(
        treeId: Int,
        description: String? = null,
        scope: () -> Unit,
    ): ScatterSet<StateId> {
      contract { callsInPlace(scope, InvocationKind.EXACTLY_ONCE) }
      return current.record(treeId, description, scope)
    }

    fun read(state: StateId): Boolean {
      return current.read(state)
    }

    /**
     * Omits reads in [scope] from the enclosing recorder. Recording scopes started inside [scope]
     * still collect their own reads.
     *
     * Set [preserveTreeContext] to true to retain the enclosing tree context and its cross-tree
     * read policy, or false for independent framework work such as child layout or saveable-state
     * snapshots.
     */
    @OptIn(ExperimentalContracts::class)
    inline fun <T> untracked(preserveTreeContext: Boolean, scope: () -> T): T {
      contract { callsInPlace(scope, InvocationKind.EXACTLY_ONCE) }
      val recorder = current
      return recorder.withContext(
          treeId = if (preserveTreeContext) recorder.currentId else NO_ID,
          description = if (preserveTreeContext) recorder.currentDescription else null,
          recordingEnabled = false,
          scope = scope,
      )
    }
  }
}
