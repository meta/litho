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

import androidx.annotation.VisibleForTesting
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import com.facebook.litho.Attachable
import com.facebook.litho.ThreadUtils
import com.facebook.litho.attribution
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.decodeComponentName
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.state.getEffectDirtyScopes

internal class OptimizedContextualEffectHandler : EffectHandler {

  private var effectsById: MutableScatterMap<Any, Attachable>? = null
  private var effectOrder: List<Attachable>? = null
  private var hasPendingEffects = false
  private var stateReadRecords: MutableScatterMap<StateId, MutableScatterSet<Any>>? = null
  private var effectToStateReadRecords: MutableScatterMap<Any, ScatterSet<StateId>>? = null

  override val effects: Collection<Attachable>?
    get() = effectOrder

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
      error("The previously deferred effects have not yet run")
    }

    val currentEffectsById = effectsById
    val currentEffectsInOrder = effectOrder
    if ((currentEffectsById == null || currentEffectsById.isEmpty()) && effects.isNullOrEmpty()) {
      return EffectHandler.EmptyRunnable
    }

    var incomingEffectsInOrder = effects
    val nextEffectsById = effects?.let { nextEffects ->
      val effectsByUniqueId = MutableScatterMap<Any, Attachable>(nextEffects.size)
      var duplicateEffectCount = 0
      var firstDuplicateEffect: Attachable? = null
      for (effect in nextEffects) {
        val id = effect.uniqueId
        val previous = effectsByUniqueId.put(id, effect)
        if (previous != null) {
          duplicateEffectCount++
          if (firstDuplicateEffect == null) firstDuplicateEffect = effect
        }
      }
      if (duplicateEffectCount > 0) {
        DebugInfoReporter.report(
            category = "EffectHandler:DuplicateEffectId",
            renderStateId = treeId,
        ) {
          put("incomingEffects", nextEffects.size)
          put("uniqueEffects", effectsByUniqueId.size)
          put("duplicateEffects", duplicateEffectCount)
          put("firstDuplicateId", firstDuplicateEffect?.uniqueId?.toString())
          put("firstDuplicateOwner", firstDuplicateEffect?.decodeComponentName())
        }
        incomingEffectsInOrder = nextEffects.deduplicateEffectOrder(effectsByUniqueId)
      }
      effectsByUniqueId
    }

    if (nextEffectsById == null || nextEffectsById.isEmpty()) {
      // 1. if there is nothing to run, then we must cleanup all current effects
      effectsById = nextEffectsById
      effectOrder = incomingEffectsInOrder
      if (!currentEffectsInOrder.isNullOrEmpty()) {
        cleanup(currentEffectsInOrder)
        stateReadRecords?.clear()
        effectToStateReadRecords?.clear()
      }
      return EffectHandler.EmptyRunnable
    }

    incomingEffectsInOrder = checkNotNull(incomingEffectsInOrder)
    if (currentEffectsById == null || currentEffectsById.isEmpty()) {
      // 2. if we have no current effects, we can simply run all the new ones.
      effectsById = nextEffectsById
      effectOrder = incomingEffectsInOrder
      return runLater(incomingEffectsInOrder)
    }

    val currentEffects = currentEffectsInOrder.orEmpty()
    var hasRemovedEffects = false
    for (effect in currentEffects) {
      val id = effect.uniqueId
      // 3.1 cleanup removed effects.
      if (id !in nextEffectsById) {
        hasRemovedEffects = true
        cleanup(id, effect)
      }
    }

    val scopesToUpdate = getEffectDirtyScopes(updatedState, stateReadRecords)
    if (
        scopesToUpdate.isNotEmpty() &&
            !hasRemovedEffects &&
            scopesToUpdate.size >= currentEffectsById.size &&
            nextEffectsById.size == currentEffectsById.size &&
            allCurrentEffectsAreDirty(currentEffects, nextEffectsById, scopesToUpdate)
    ) {
      cleanup(currentEffects)
      stateReadRecords?.clear()
      effectToStateReadRecords?.clear()
      effectsById = nextEffectsById
      effectOrder = incomingEffectsInOrder
      return runLater(incomingEffectsInOrder)
    }

    var toRun: Array<Attachable?>? = null
    var toRunCount = 0
    var resolvedEffectsInOrder: MutableList<Attachable>? = null
    for (index in incomingEffectsInOrder.indices) {
      val incomingEffect = incomingEffectsInOrder[index]
      val id = incomingEffect.uniqueId
      val currentEffect = currentEffectsById[id]
      var resolvedEffect = incomingEffect
      if (currentEffect == null) {
        // 3.2 queue new effects.
        var effectsToRun = toRun
        if (effectsToRun == null) {
          effectsToRun = arrayOfNulls(nextEffectsById.size)
          toRun = effectsToRun
        }
        effectsToRun[toRunCount++] = incomingEffect
      } else {
        val usesLegacyUpdateBehavior = currentEffect.useLegacyUpdateBehavior()
        val isDirty =
            scopesToUpdate.isNotEmpty() && !usesLegacyUpdateBehavior && id in scopesToUpdate
        val shouldRerun = isDirty || currentEffect.shouldUpdate(incomingEffect)
        if (shouldRerun) {
          // 3.3 cleanup and queue changed effects.
          cleanup(id, currentEffect)
          var effectsToRun = toRun
          if (effectsToRun == null) {
            effectsToRun = arrayOfNulls(nextEffectsById.size)
            toRun = effectsToRun
          }
          effectsToRun[toRunCount++] = incomingEffect
        } else if (!usesLegacyUpdateBehavior) {
          // If the effect already exists and it doesn't need to update, make sure to use the
          // existing one.
          nextEffectsById[id] = currentEffect
          resolvedEffect = currentEffect
        }
      }
      val resolvedOrder = resolvedEffectsInOrder
      if (resolvedOrder != null) {
        resolvedOrder.add(resolvedEffect)
      } else if (resolvedEffect !== incomingEffect) {
        resolvedEffectsInOrder =
            ArrayList<Attachable>(incomingEffectsInOrder.size).also { resolvedOrder ->
              for (copyIndex in 0 until index) {
                resolvedOrder.add(incomingEffectsInOrder[copyIndex])
              }
              resolvedOrder.add(resolvedEffect)
            }
      }
    }

    // 4 update the map reference
    effectsById = nextEffectsById
    effectOrder = resolvedEffectsInOrder ?: incomingEffectsInOrder

    val effectsToRun = toRun
    if (effectsToRun == null) {
      return EffectHandler.EmptyRunnable
    }

    return runLater(effectsToRun, toRunCount)
  }

  private fun EffectContext.runLater(effects: Array<Attachable?>, count: Int): Runnable {
    hasPendingEffects = true
    return Runnable {
      ThreadUtils.assertMainThread()
      if (hasPendingEffects) {
        try {
          run(effects, count)
        } finally {
          hasPendingEffects = false
        }
      }
    }
  }

  private fun EffectContext.runLater(effects: Collection<Attachable>): Runnable {
    hasPendingEffects = true
    return Runnable {
      ThreadUtils.assertMainThread()
      if (hasPendingEffects) {
        try {
          run(effects)
        } finally {
          hasPendingEffects = false
        }
      }
    }
  }

  private fun EffectContext.run(effects: Array<Attachable?>, count: Int) {
    for (index in 0 until count) {
      checkNotNull(effects[index]).runWithTracking(this, count)
    }
  }

  override fun hasReadsFor(updatedState: Set<StateId>): Boolean {
    val records = stateReadRecords ?: return false
    return updatedState.any { records[it]?.isEmpty() == false }
  }

  override fun onCleanup() {
    ThreadUtils.assertMainThread()
    effectOrder?.let { cleanup(it) }
    stateReadRecords?.clear()
    effectToStateReadRecords?.clear()
    effectsById = null
    effectOrder = null
  }

  private fun EffectContext.run(effects: Collection<Attachable>) {
    val expectedReadRecordCount = effects.size
    for (effect in effects) {
      effect.runWithTracking(this, expectedReadRecordCount)
    }
  }

  private fun List<Attachable>.deduplicateEffectOrder(
      effectsById: MutableScatterMap<Any, Attachable>,
  ): List<Attachable> {
    val seenEffectIds = MutableScatterSet<Any>(effectsById.size)
    val dedupedEffects = ArrayList<Attachable>(effectsById.size)
    for (effect in this) {
      val id = effect.uniqueId
      if (seenEffectIds.add(id)) {
        dedupedEffects.add(checkNotNull(effectsById[id]))
      }
    }
    return dedupedEffects
  }

  private fun cleanup(effects: Collection<Attachable>) {
    for (effect in effects) {
      effect.detach()
    }
  }

  private fun cleanup(effectId: Any, effect: Attachable) {
    effect.detach()
    removeStateReadRecords(effectId)
  }

  /**
   * Removes state read records for the given effects. Only records associated with the cleaned-up
   * effects are removed; records for retained effects are preserved so that future state updates
   * can still trigger them.
   */
  private fun removeStateReadRecords(effectId: Any) {
    val stateIdsReadByEffect = effectToStateReadRecords?.remove(effectId) ?: return
    val effectIdsByState = stateReadRecords ?: return
    stateIdsReadByEffect.forEach state@{ stateId ->
      val effectIdsThatReadState = effectIdsByState[stateId] ?: return@state
      effectIdsThatReadState.remove(effectId)
      if (effectIdsThatReadState.isEmpty()) {
        effectIdsByState.remove(stateId)
      }
    }
  }

  private fun allCurrentEffectsAreDirty(
      currentEffectsInOrder: List<Attachable>,
      nextEffectsById: MutableScatterMap<Any, Attachable>,
      scopesToUpdate: ScatterSet<Any>,
  ): Boolean {
    for (effect in currentEffectsInOrder) {
      val id = effect.uniqueId
      if (
          effect.useLegacyUpdateBehavior() || id !in scopesToUpdate || nextEffectsById[id] == null
      ) {
        return false
      }
    }
    return true
  }

  private fun Attachable.runWithTracking(context: EffectContext, expectedReadRecordCount: Int) {
    if (!context.isReadTrackingEnabled || useLegacyUpdateBehavior()) {
      attach()
      return
    }

    val stateReads = StateReadRecorder.record(context.treeId, attribution()) { attach() }

    if (stateReads.isNotEmpty()) {
      val readRecords =
          stateReadRecords
              ?: MutableScatterMap<StateId, MutableScatterSet<Any>>(expectedReadRecordCount).also {
                stateReadRecords = it
              }
      val effectReadRecords =
          effectToStateReadRecords
              ?: MutableScatterMap<Any, ScatterSet<StateId>>(expectedReadRecordCount).also {
                effectToStateReadRecords = it
              }
      stateReads.forEach { stateId ->
        readRecords
            .getOrPut(stateId) { MutableScatterSet(EXPECTED_READERS_PER_STATE) }
            .add(uniqueId)
      }
      effectReadRecords[uniqueId] = stateReads
    }
  }

  @VisibleForTesting
  internal fun getTrackedStateReadCountForTest(): Int = stateReadRecords?.size ?: 0

  @VisibleForTesting
  internal fun getTrackedEffectReadCountForTest(): Int = effectToStateReadRecords?.size ?: 0

  @VisibleForTesting internal fun hasPendingEffectsForTest(): Boolean = hasPendingEffects

  private companion object {
    private const val EXPECTED_READERS_PER_STATE = 4
  }
}
