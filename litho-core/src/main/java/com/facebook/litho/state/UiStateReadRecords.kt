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

import androidx.annotation.MainThread
import androidx.collection.LongSet
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.emptyLongSet
import androidx.collection.emptyScatterSet
import androidx.collection.mutableLongObjectMapOf
import androidx.collection.mutableLongSetOf
import androidx.collection.mutableScatterMapOf
import androidx.collection.mutableScatterSetOf
import com.facebook.rendercore.BinderId
import com.facebook.rendercore.BinderObserver
import com.facebook.rendercore.BooleanFunc
import com.facebook.rendercore.DepChange

/**
 * A [BinderObserver] responsible for tracking and recording state reads in binders.
 *
 * The [UiStateReadRecords] keeps a live record of which state is read and by who. These records are
 * exclusively those that are read during the UI phases (namely Mount and Draw). These records may
 * be used to determine which binders or draw hosts need to be re-bound or invalidated respectively,
 * when a state that they read is updated, which can help to significantly optimize execution in the
 * respective phases.
 *
 * @param config Supplies the read tracking status and current treeId to the observer.
 */
@MainThread
class UiStateReadRecords(
    private val config: Config,
    private val binderEventListener: BinderEventListener? = null,
) : BinderObserver() {

  /**
   * Optional listener for binder lifecycle events. Allows external systems (e.g., observability) to
   * observe shouldUpdate results and bind durations without subclassing UiStateReadRecords.
   */
  interface BinderEventListener {
    /** Called after shouldUpdate completes with the result. */
    fun onShouldUpdate(binderId: BinderId, result: Boolean)

    /** Called after onBind completes with the elapsed duration. */
    fun onBindComplete(binderId: BinderId, durationNs: Long)

    /**
     * Called when a binder is rebound because its deps changed. Empty means the deps compared equal
     * and something else caused the rebind.
     */
    fun onDepsChanged(binderId: BinderId, changes: List<DepChange>): Unit = Unit

    /**
     * Called instead of [onDepsChanged] when the binder declares deps but they were not diffed, so
     * a `null` change list means "nobody looked" rather than "no dep model".
     */
    fun onDepsNotCollected(binderId: BinderId): Unit = Unit

    /** Called when a binder is rebound because a state value it read was updated. */
    fun onStateReadRebind(binderId: BinderId): Unit = Unit
  }

  // Forward maps: StateId -> readers (optimized for getBindersToRebind / getContentIdsToInvalidate)
  private val onBindRecords = mutableScatterMapOf<StateId, MutableScatterSet<BinderId>>()
  private val onDrawRecords = mutableScatterMapOf<StateId, MutableLongSet>()

  // Inverse maps: reader -> StateIds (optimized for onUnbind / removeDrawScope).
  // Values are the ScatterSet returned by StateReadRecorder.record(), stored directly without copy.
  private val binderToStates = mutableScatterMapOf<BinderId, ScatterSet<StateId>>()
  private val drawHostToStates: MutableLongObjectMap<ScatterSet<StateId>> = mutableLongObjectMapOf()

  override fun shouldUpdate(binderId: BinderId, func: BooleanFunc): Boolean {
    val result = func.invoke()
    binderEventListener?.onShouldUpdate(binderId, result)
    return result
  }

  override fun onDepsChanged(binderId: BinderId, changes: List<DepChange>) {
    binderEventListener?.onDepsChanged(binderId, changes)
  }

  override fun onDepsNotCollected(binderId: BinderId) {
    binderEventListener?.onDepsNotCollected(binderId)
  }

  override fun onStateReadRebind(binderId: BinderId) {
    binderEventListener?.onStateReadRebind(binderId)
  }

  override fun onBind(binderId: BinderId, func: () -> Unit) {
    val treeId = config.currentTreeId()
    val start = if (binderEventListener != null) System.nanoTime() else 0L
    val reads = StateReadRecorder.record(treeId, binderId.binderDebugDescription, scope = func)
    if (binderEventListener != null) {
      binderEventListener.onBindComplete(binderId, System.nanoTime() - start)
    }
    if (reads.isNotEmpty()) {
      reads.forEach { stateId ->
        onBindRecords.getOrPut(stateId) { mutableScatterSetOf() }.add(binderId)
      }
      binderToStates[binderId] = reads
    }
  }

  override fun onUnbind(binderId: BinderId, func: () -> Unit) {
    func()

    val states = binderToStates.remove(binderId) ?: return
    states.forEach { stateId ->
      val binders = onBindRecords[stateId] ?: return@forEach
      binders.remove(binderId)
      if (binders.isEmpty()) onBindRecords.remove(stateId)
    }
  }

  fun recordOnDraw(hostId: Long, description: String?, func: () -> Unit) {
    val treeId = config.currentTreeId()
    val reads = StateReadRecorder.record(treeId, description, scope = func)

    if (reads.isNotEmpty()) {
      reads.forEach { stateId ->
        onDrawRecords.getOrPut(stateId) { mutableLongSetOf() }.add(hostId)
      }
      drawHostToStates[hostId] = reads
    }
  }

  fun removeDrawScope(hostId: Long) {
    val states = drawHostToStates.remove(hostId) ?: return
    states.forEach { stateId ->
      val hosts = onDrawRecords[stateId] ?: return@forEach
      hosts.remove(hostId)
      if (hosts.isEmpty()) onDrawRecords.remove(stateId)
    }
  }

  fun getBindersToRebind(dirtyStates: Set<StateId>): ScatterSet<BinderId> {
    if (dirtyStates.isEmpty()) {
      return emptyScatterSet()
    }
    var expectedBinderCount = 0
    dirtyStates.forEach { stateId -> expectedBinderCount += onBindRecords[stateId]?.size ?: 0 }
    if (expectedBinderCount == 0) {
      return emptyScatterSet()
    }
    val result = MutableScatterSet<BinderId>(expectedBinderCount)
    dirtyStates.forEach { stateId -> onBindRecords[stateId]?.let { result.addAll(it) } }
    return result
  }

  fun getContentIdsToInvalidate(dirtyStates: Set<StateId>): LongSet {
    if (dirtyStates.isEmpty()) {
      return emptyLongSet()
    }
    var expectedHostCount = 0
    dirtyStates.forEach { stateId -> expectedHostCount += onDrawRecords[stateId]?.size ?: 0 }
    if (expectedHostCount == 0) {
      return emptyLongSet()
    }
    val result = MutableLongSet(expectedHostCount)
    dirtyStates.forEach { stateId -> onDrawRecords[stateId]?.let { result.addAll(it) } }
    return result
  }

  interface Config {
    fun currentTreeId(): Int
  }
}
