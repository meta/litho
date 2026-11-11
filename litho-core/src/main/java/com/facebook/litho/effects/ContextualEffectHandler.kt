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

package com.facebook.litho.effects

import androidx.collection.MutableScatterSet
import androidx.collection.mutableScatterSetOf
import com.facebook.litho.Attachable
import com.facebook.litho.ThreadUtils
import com.facebook.litho.attribution
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.state.getEffectDirtyScopes
import kotlin.collections.iterator

internal class ContextualEffectHandler : EffectHandler {

  private var _effects: MutableMap<Any, Attachable>? = null
  private var hasPendingEffects = false
  private val stateReadRecords = mutableMapOf<StateId, MutableScatterSet<Any>>()

  override val effects: Collection<Attachable>?
    get() = _effects?.values

  /**
   * Updates the set of associated [Attachables][Attachable].
   *
   * Running of effects is deferred until [Runnable.run] is called. This is useful for cases where
   * some actions need to happen first. For example, a caller might need to apply state updates and
   * other requisite actions before running the effects.
   *
   * Note that all outgoing effects are always detached as necessary before exiting this function.
   *
   * @return the [Runnable] through which the incoming effects can be executed. This is a
   *   [EffectHandler.EmptyRunnable] if there are no new effects to run.
   */
  override fun EffectContext.updateEffects(effects: List<Attachable>?): Runnable {
    ThreadUtils.assertMainThread()
    if (hasPendingEffects) {
      error("The previously deferred effects have not yet been ran")
    }
    if (_effects.isNullOrEmpty() && effects.isNullOrEmpty()) {
      return EffectHandler.EmptyRunnable
    }

    val currentEffects = _effects
    val newEffects = effects?.associateByTo(linkedMapOf<Any, Attachable>()) { it.uniqueId }

    val toCleanup: Map<Any, Attachable>?
    val toRun: Map<Any, Attachable>?
    if (newEffects.isNullOrEmpty()) {
      // 1. if there is nothing to run, then we must cleanup all current effects
      toCleanup = currentEffects
      toRun = null
    } else if (currentEffects.isNullOrEmpty()) {
      // 2. if we have no current effects, we can simply run all the new ones.
      toCleanup = null
      toRun = newEffects
    } else {
      toCleanup = linkedMapOf()
      toRun = linkedMapOf()
      for ((id, effect) in currentEffects) {
        // 3.1 add removed effects to toDetach
        if (id !in newEffects) toCleanup[id] = effect
      }
      val scopesToUpdate = getEffectDirtyScopes(updatedState, stateReadRecords)
      for ((id, newEffect) in newEffects) {
        val current = currentEffects[id]
        if (current == null) {
          // 3.2 add new effects to toRun
          toRun[id] = newEffect
        } else if (
            (!current.useLegacyUpdateBehavior() && id in scopesToUpdate) ||
                current.shouldUpdate(newEffect)
        ) {
          // 3.3 add changed effects to toCleanup/toRun
          toCleanup[id] = current
          toRun[id] = newEffect
        } else if (!current.useLegacyUpdateBehavior()) {
          // If the effect already exists and it doesn't need to update, make sure to use the
          // existing one.
          newEffects[id] = current
        }
      }
    }

    // 4 update the map reference
    _effects = newEffects

    // 5. Cleanup
    if (toCleanup != null && toCleanup.isNotEmpty()) {
      // 5.1 Cleanup the effects in toCleanup
      cleanup(toCleanup)
      // 5.2 Remove state read records for cleaned-up effects
      removeStateReadRecords(toCleanup)
    }

    if (toRun.isNullOrEmpty()) {
      return EffectHandler.EmptyRunnable
    }

    // 6. Return a handle to lazily run the effects in toRun
    hasPendingEffects = true
    return Runnable {
      ThreadUtils.assertMainThread()
      if (hasPendingEffects) {
        run(toRun)
        hasPendingEffects = false
      }
    }
  }

  override fun hasReadsFor(updatedState: Set<StateId>): Boolean = updatedState.any {
    stateReadRecords[it]?.isEmpty() == false
  }

  override fun onCleanup() {
    ThreadUtils.assertMainThread()
    // Emptied before the cleanup callbacks run, so effects they attach, e.g. by committing a new
    // layout, are kept and cleaned up later instead of being dropped here.
    val effects = _effects
    _effects = null
    stateReadRecords.clear()
    effects?.let { cleanup(it) }
  }

  private fun EffectContext.run(effects: Map<Any, Attachable>) {
    effects.forEach { (_, effect) -> effect.runWithTracking(this) }
  }

  private fun cleanup(effects: Map<Any, Attachable>) {
    effects.forEach { (_, effect) -> effect.detach() }
  }

  /**
   * Removes state read records for the given effects. Only records associated with the cleaned-up
   * effects are removed; records for retained effects are preserved so that future state updates
   * can still trigger them.
   */
  private fun removeStateReadRecords(effects: Map<Any, Attachable>) {
    val effectIds = effects.keys
    val iterator = stateReadRecords.iterator()
    while (iterator.hasNext()) {
      val (_, readers) = iterator.next()
      readers.removeAll(effectIds)
      if (readers.isEmpty()) {
        iterator.remove()
      }
    }
  }

  private fun Attachable.runWithTracking(context: EffectContext) {
    val stateReads = StateReadRecorder.record(context.treeId, attribution()) { attach() }

    stateReads.forEach { stateId ->
      stateReadRecords.getOrPut(stateId) { mutableScatterSetOf() }.add(uniqueId)
    }
  }
}
