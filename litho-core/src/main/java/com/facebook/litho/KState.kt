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

import com.facebook.litho.annotations.Hook
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.state.ComponentState
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateProvider
import com.facebook.litho.state.StateReadRecorder
import com.facebook.rendercore.utils.NoDeps
import com.facebook.rendercore.utils.areObjectsEquivalent
import java.util.Objects

internal fun <T> ComponentScope.getUpdatedState(
    vararg deps: Any? = NoDeps,
    initializer: () -> T,
): T = getUpdatedStateByHookKey(requireCurrentHookKey(), deps, initializer)

/**
 * If [base] is the container this render already forked ([ComponentScope.ownedStateContainer]),
 * mutate it in place; otherwise fork a single mutable copy off the committed/shared [base] and
 * record it as this render's owned fork. Returns the same instance when mutated in place, so the
 * caller re-publishes via addState only when the container actually changed. The identity check is
 * what preserves copy-on-write safety: a committed/shared container is never `===` this render's
 * private fork, so it is always copied.
 */
private fun ComponentScope.transientMutate(
    base: KStateContainer,
    hookKey: HookKey,
    newValue: Any?,
    deps: Array<*>?,
): KStateContainer =
    if (base === ownedStateContainer) {
      base.mutateInPlace(hookKey, newValue, deps)
    } else {
      base.mutableForkWith(hookKey, newValue, deps).also { ownedStateContainer = it }
    }

/** State lookup using the [hookKey] captured by the caller. */
internal fun <T> ComponentScope.getUpdatedStateByHookKey(
    hookKey: HookKey,
    deps: Array<out Any?>,
    initializer: () -> T,
): T {
  val key = context.globalKey
  val treeState: TreeState = resolveContext.treeState
  val isLayoutState = context.isContextForLayout
  val existing = treeState.getState(key, isLayoutState) as ComponentState<KStateContainer>?
  val previousEntry = existing?.value?.getByHookKey(hookKey)

  recordVisitedHookKey(hookKey)

  @Suppress("UNCHECKED_CAST") val deps = deps as Array<Any?>

  val current: ComponentState<KStateContainer> =
      if (existing != null && previousEntry == null) {
        // Prior committed state is missing this hookKey (a new/conditional hook). Get its initial
        // value and merge into the EXISTING container (not InitialState's) to avoid the drain
        // state-loss hazard (initialstate-drain-state-loss.md).
        val entry: CachedValue =
            treeState.peekOrSeedInitialHookStateByHookKey(
                key,
                hookKey,
                deps,
                initializer,
                isLayoutState,
                context.scopedComponentInfo.component.simpleName,
            )
        val newContainer = transientMutate(existing.value, hookKey, entry.value, entry.deps)
        if (newContainer === existing.value) {
          existing
        } else {
          existing.copy(value = newContainer).also { treeState.addState(key, it, isLayoutState) }
        }
      } else if (existing == null) {
        val createdState: ComponentState<KStateContainer> =
            try {
              treeState.createOrGetInitialHookStateByHookKey(
                  key,
                  hookKey,
                  deps,
                  initializer,
                  isLayoutState,
                  context.scopedComponentInfo.component.simpleName,
              )
            } catch (e: Exception) {
              throw IllegalStateException(
                  "KStateUnexpectedHookKeyException: HookKey: $hookKey, Key: $key, IsLayoutState: $isLayoutState",
                  e,
              )
            }
        treeState.addState(key, createdState, isLayoutState)
        createdState
      } else {
        existing
      }

  val existingEntry = previousEntry ?: current.value.getByHookKey(hookKey)
  val value: T
  val updated: ComponentState<KStateContainer> =
      if (existingEntry != null && areObjectsEquivalent(existingEntry.deps, deps)) {
        @Suppress("UNCHECKED_CAST") val cachedValue = existingEntry.value as T
        value = cachedValue
        current
      } else {
        value = initializer()
        val newContainer = transientMutate(current.value, hookKey, value, deps)
        if (newContainer === current.value) {
          current
        } else {
          current.copy(value = newContainer).also { treeState.addState(key, it, isLayoutState) }
        }
      }

  context.scopedComponentInfo.state = updated

  // Mark state in use on first hook encountered for this key
  if (!stateAlreadyMarkedAsUsed) {
    treeState.markStateInUse(key, isLayoutState)
    stateAlreadyMarkedAsUsed = true
  }

  return value
}

/**
 * Declares a state within a Component. The [initializer] will be invoked to provide the initial
 * value of state. The initializer will be invoked again if the [deps] change when the Component is
 * rendered again.
 *
 * Note: The component does not re-render if the [initializer] is invoked. The updated value of the
 * state is visible to currently rendering component.
 *
 * The [initializer] must be free from side effects because it can invoked multiple times from any
 * Thread during the lifecycle of the Component.
 *
 * @see [State]
 */
@Hook
fun <T> ComponentScope.useStateWithDeps(
    vararg deps: Any? = NoDeps,
    initializer: () -> T,
): State<T> = useStateImpl(deps as Array<Any?>, initializer)

/**
 * Declares a state variable within a Component. The initializer will provide the initial value if
 * it hasn't already been initialized in a previous render of the Component.
 *
 * Assignments to the state variables are allowed only in [updateState] block to batch updates and
 * trigger a UI layout only once per batch.
 */
@Hook
fun <T> ComponentScope.useState(initializer: () -> T): State<T> =
    useStateImpl(NoDeps as Array<Any?>, initializer)

// Shared impl for useState / useStateWithDeps. Not @Hook -- reads the caller's currentHookKey
// directly so the wrapper doesn't add another enterHook layer. This keeps the resulting HookKey
// shape (depth) the same as useCached / useEffect for the same call context, eliminating a class
// of state-loss bugs where useState's nested key shape didn't match useCached's top-level shape
// in the same KStateContainer (paste P2291310265).
private fun <T> ComponentScope.useStateImpl(
    deps: Array<Any?>,
    initializer: () -> T,
): State<T> {
  val lithoTree = context.lithoTree ?: error("LithoTree is null")
  val hookKey = requireCurrentHookKey()
  return hookState(lithoTree, hookKey, getUpdatedStateByHookKey(hookKey, deps, initializer))
}

/** The [State] of the hook at [hookKey], whose value this render read as [value]. */
internal fun <T> ComponentScope.hookState(
    lithoTree: LithoTree,
    hookKey: HookKey,
    value: T,
): State<T> = State(
    lithoTree.stateProvider,
    lithoTree.stateUpdater,
    HookGlobalKey(context.globalKey, hookKey, RenderScope.Resolve),
    context.isContextForLayout,
    context.componentScope,
    lithoTree.isReadTrackingEnabled,
    context.lithoConfiguration.componentsConfig.detachStateIdFromValue,
    value,
)

/** Interface with which a component gets the value from a state or updates it. */
class State<T>
internal constructor(
    private val stateProvider: StateProvider,
    private val stateUpdater: StateUpdater,
    private val hookGlobalKey: HookGlobalKey,
    internal val isLayoutContext: Boolean,
    private val componentScope: Component?,
    private val isReadTrackingEnabled: Boolean,
    private val detachStateIdFromValue: Boolean,
    internal val fallback: T,
) : Value<T> {

  internal val stateId: StateId =
      StateId(stateProvider.treeId, hookGlobalKey).also {
        if (LithoDebugConfigurations.isDebugModeEnabled) it.ownerName = componentScope?.simpleName
      }

  override val value: T
    get() {
      if (!isReadTrackingEnabled) return fallback
      return when {
        StateReadRecorder.read(stateId) -> stateProvider.getValue(this)
        else -> fallback
      }
    }

  /**
   * Updates this state value and enqueues a new layout calculation reflecting it to execute in the
   * background.
   */
  fun update(newValue: T) {
    if (canSkip(newValue)) {
      return
    }

    stateUpdater.updateHookStateAsync(
        stateId,
        HookUpdaterValue(hookGlobalKey, newValue),
        componentScope?.simpleName ?: "hook",
        isLayoutContext,
    )
  }

  /**
   * Uses [newValueFunction] to update this state value using the previous state value, and enqueues
   * a new layout calculation reflecting it to execute in the background.
   *
   * [newValueFunction] receives the current state value and can use it to compute the update: this
   * is useful when there could be other enqueued updates that may not have been applied yet.
   *
   * For example, if your state update should increment a counter, using the function version of
   * [update] with `count -> count + 1` will allow you to account for updates that are in flight but
   * not yet applied (e.g. if the user has tapped a button triggering the update multiple times in
   * succession).
   */
  fun update(newValueFunction: (T) -> T) {
    if (canSkip(newValueFunction)) {
      return
    }

    val ownerName = componentScope?.simpleName
    stateUpdater.updateHookStateAsync(
        stateId,
        HookUpdaterLambda(hookGlobalKey, newValueFunction),
        ownerName ?: "hook",
        isLayoutContext,
    )
  }

  /**
   * Updates this state value and enqueues a new layout calculation reflecting it to execute on the
   * current thread. If called on the main thread, this means that the UI will be updated for the
   * current frame.
   *
   * Note: If [updateSync] is used on the main thread, it can easily cause dropped frames and
   * degrade user experience. Therefore it should only be used in exceptional circumstances or when
   * it's known to be executed off the main thread.
   */
  fun updateSync(newValue: T) {
    if (canSkip(newValue)) {
      return
    }

    stateUpdater.updateHookStateSync(
        stateId,
        HookUpdaterValue(hookGlobalKey, newValue),
        componentScope?.simpleName ?: "hook",
        isLayoutContext,
    )
  }

  /**
   * Uses [newValueFunction] to update this state value using the previous state value, and enqueues
   * a new layout calculation reflecting it to execute on the current thread.
   *
   * [newValueFunction] receives the current state value and can use it to compute the update: this
   * is useful when there could be other enqueued updates that may not have been applied yet.
   *
   * For example, if your state update should increment a counter, using the function version of
   * [update] with `count -> count + 1` will allow you to account for updates that are in flight but
   * not yet applied (e.g. if the user has tapped a button triggering the update multiple times in
   * succession).
   *
   * Note: If [updateSync] is used on the main thread, it can easily cause dropped frames and
   * degrade user experience. Therefore it should only be used in exceptional circumstances or when
   * it's known to be executed off the main thread.
   */
  fun updateSync(newValueFunction: (T) -> T) {
    if (canSkip(newValueFunction)) {
      return
    }

    val ownerName = componentScope?.simpleName
    stateUpdater.updateHookStateSync(
        stateId,
        HookUpdaterLambda(hookGlobalKey, newValueFunction),
        ownerName ?: "hook",
        isLayoutContext,
    )
  }

  private fun canSkip(newValue: T): Boolean {
    return stateUpdater.canSkipStateUpdate(stateId, newValue, isLayoutContext)
  }

  private fun canSkip(newValueFunction: (T) -> T): Boolean {
    return stateUpdater.canSkipStateUpdate(newValueFunction, stateId, isLayoutContext)
  }

  /**
   * We consider two state objects equal if they 1) belong to the same Tree, 2) have the same
   * [HookGlobalKey], and 3) have the same value (according to its own .equals check)
   */
  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }

    if (other !is State<*>) {
      return false
    }

    return hookGlobalKey == other.hookGlobalKey &&
        if (detachStateIdFromValue) stateId.treeId == other.stateId.treeId
        else fallback == other.fallback
  }

  override fun hashCode(): Int {
    return Objects.hash(
        hookGlobalKey,
        if (detachStateIdFromValue) stateId.treeId else fallback,
    )
  }
}

private class HookUpdaterValue<T>(
    private val hookGlobalKey: HookGlobalKey,
    private val newValue: T,
) : HookUpdater {
  override fun getUpdatedStateContainer(currentState: KStateContainer): KStateContainer {
    val hookKey = hookGlobalKey.hookKey as HookKey
    return if (currentState.getByHookKey(hookKey) == null) {
      // Hook was pruned; updating it is a no-op because re-appending would create zombie state.
      currentState
    } else {
      currentState.copyAndMutateByHookKey(hookKey, newValue)
    }
  }
}

private class HookUpdaterLambda<T>(
    private val hookGlobalKey: HookGlobalKey,
    private val newValueFunction: (T) -> T,
) : HookUpdater {
  @Suppress("UNCHECKED_CAST")
  override fun getUpdatedStateContainer(currentState: KStateContainer): KStateContainer {
    val hookKey = hookGlobalKey.hookKey as HookKey
    val cached = currentState.getByHookKey(hookKey) ?: return currentState
    return currentState.copyAndMutateByHookKey(
        hookKey,
        newValueFunction(cached.value as T),
    )
  }
}
