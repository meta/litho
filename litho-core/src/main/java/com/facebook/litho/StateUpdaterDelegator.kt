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

package com.facebook.litho

import com.facebook.litho.animation.AnimationFrameInvalidation
import com.facebook.litho.state.StateId

/**
 * StateUpdaterDelegator is responsible for holding reference to the right [StateUpdater] that will
 * be used during creation of [ComponentTree]
 */
class StateUpdaterDelegator : StateUpdater {
  @Volatile
  var delegate: StateUpdater? = null
    private set

  @Synchronized
  fun attachStateUpdater(stateUpdater: StateUpdater) {
    this.delegate = stateUpdater
  }

  fun detachStateUpdater() {
    this.delegate = null
  }

  /**
   * Stops routing to [expected] if it is still the delegate, so a released [ComponentTree] can be
   * garbage collected without detaching another tree attached since. Until another updater is
   * attached, calls behave as they would on a released tree.
   */
  @Synchronized
  fun detachStateUpdater(expected: StateUpdater) {
    if (delegate === expected) {
      delegate = DetachedStateUpdater
    }
  }

  override var isFirstMount: Boolean
    get() = applyOnStateUpdater { isFirstMount }
    set(value) {
      applyOnStateUpdater { isFirstMount = value }
    }

  override fun updateStateSync(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      attribution: String?,
      isLayoutState: Boolean,
  ) = applyOnStateUpdater { updateStateSync(globalKey, stateUpdate, attribution, isLayoutState) }

  override fun updateStateAsync(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      attribution: String?,
      isLayoutState: Boolean,
  ) = applyOnStateUpdater { updateStateAsync(globalKey, stateUpdate, attribution, isLayoutState) }

  override fun updateStateLazy(
      globalKey: GlobalKey,
      stateUpdate: StateContainer.StateUpdate,
      isLayoutState: Boolean,
  ) = applyOnStateUpdater { updateStateLazy(globalKey, stateUpdate, isLayoutState) }

  override fun updateHookStateAsync(
      stateId: StateId,
      updateBlock: HookUpdater,
      attribution: String?,
      isLayoutState: Boolean,
  ) = applyOnStateUpdater { updateHookStateAsync(stateId, updateBlock, attribution, isLayoutState) }

  override fun updateHookStateSync(
      stateId: StateId,
      updateBlock: HookUpdater,
      attribution: String?,
      isLayoutState: Boolean,
  ) = applyOnStateUpdater { updateHookStateSync(stateId, updateBlock, attribution, isLayoutState) }

  override fun applyAnimationFrameUpdates(invalidations: List<AnimationFrameInvalidation>) =
      applyOnStateUpdater {
        applyAnimationFrameUpdates(invalidations)
      }

  override val isAnimationFrameInvalidationEnabled: Boolean
    get() = applyOnStateUpdater { isAnimationFrameInvalidationEnabled }

  override fun applyLazyStateUpdatesForContainer(
      globalKey: GlobalKey,
      container: StateContainer,
      isLayoutState: Boolean,
  ): StateContainer = applyOnStateUpdater {
    applyLazyStateUpdatesForContainer(globalKey, container, isLayoutState)
  }

  override fun getCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      isLayoutState: Boolean,
  ): Any? = applyOnStateUpdater { getCachedValue(cacheKey, cachedValueInputs, isLayoutState) }

  override fun putCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      cachedValue: Any?,
      isLayoutState: Boolean,
  ) = applyOnStateUpdater {
    putCachedValue(cacheKey, cachedValueInputs, cachedValue, isLayoutState)
  }

  override fun removePendingStateUpdate(key: GlobalKey, isLayoutState: Boolean) =
      applyOnStateUpdater {
        removePendingStateUpdate(key, isLayoutState)
      }

  override fun <T> canSkipStateUpdate(
      stateId: StateId,
      newValue: T?,
      isLayoutState: Boolean,
  ): Boolean = applyOnStateUpdater { canSkipStateUpdate(stateId, newValue, isLayoutState) }

  override fun <T> canSkipStateUpdate(
      newValueFunction: (T) -> T,
      stateId: StateId,
      isLayoutState: Boolean,
  ): Boolean = applyOnStateUpdater { canSkipStateUpdate(newValueFunction, stateId, isLayoutState) }

  override fun getEventTrigger(key: Any): EventTrigger<*>? = applyOnStateUpdater {
    getEventTrigger(key)
  }

  override fun getEventTrigger(handle: Handle, id: Int): EventTrigger<*>? = applyOnStateUpdater {
    getEventTrigger(handle, id)
  }

  private fun <T> applyOnStateUpdater(body: StateUpdater.() -> T): T {
    return body(delegate ?: error("Delegate StateUpdater not set"))
  }

  // Mirrors a released ComponentTree: updates are dropped and lookups return nothing.
  private object DetachedStateUpdater : StateUpdater {
    override var isFirstMount: Boolean
      get() = false
      set(_) {}

    override fun updateStateSync(
        globalKey: GlobalKey,
        stateUpdate: StateContainer.StateUpdate,
        attribution: String?,
        isLayoutState: Boolean,
    ) = Unit

    override fun updateStateAsync(
        globalKey: GlobalKey,
        stateUpdate: StateContainer.StateUpdate,
        attribution: String?,
        isLayoutState: Boolean,
    ) = Unit

    override fun updateStateLazy(
        globalKey: GlobalKey,
        stateUpdate: StateContainer.StateUpdate,
        isLayoutState: Boolean,
    ) = Unit

    override fun updateHookStateAsync(
        stateId: StateId,
        updateBlock: HookUpdater,
        attribution: String?,
        isLayoutState: Boolean,
    ) = Unit

    override fun updateHookStateSync(
        stateId: StateId,
        updateBlock: HookUpdater,
        attribution: String?,
        isLayoutState: Boolean,
    ) = Unit

    override fun applyLazyStateUpdatesForContainer(
        globalKey: GlobalKey,
        container: StateContainer,
        isLayoutState: Boolean,
    ): StateContainer = container

    override fun getCachedValue(
        cacheKey: HookGlobalKey,
        cachedValueInputs: Any,
        isLayoutState: Boolean,
    ): Any? = null

    override fun putCachedValue(
        cacheKey: HookGlobalKey,
        cachedValueInputs: Any,
        cachedValue: Any?,
        isLayoutState: Boolean,
    ) = Unit

    override fun removePendingStateUpdate(key: GlobalKey, isLayoutState: Boolean) = Unit

    override fun <T> canSkipStateUpdate(
        stateId: StateId,
        newValue: T?,
        isLayoutState: Boolean,
    ): Boolean = false

    override fun <T> canSkipStateUpdate(
        newValueFunction: (T) -> T,
        stateId: StateId,
        isLayoutState: Boolean,
    ): Boolean = false

    override fun getEventTrigger(key: Any): EventTrigger<*>? = null

    override fun getEventTrigger(handle: Handle, id: Int): EventTrigger<*>? = null
  }
}
