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

import android.view.View
import androidx.collection.MutableIntIntMap
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.state.ComponentState
import com.facebook.litho.transition.MutableTransitionData
import com.facebook.litho.utils.LithoThreadLocal
import com.facebook.litho.utils.getOrSet
import com.facebook.rendercore.LogLevel

/**
 * The implicit receiver for [KComponent.render] call. This class exposes the ability to use hooks,
 * like [useState], and convenience functions, like [dp].
 */
open class ComponentScope
private constructor(
    override val context: ComponentContext,
    hookState: HookState?,
) : ResourcesScope {

  @PublishedApi
  internal constructor(
      context: ComponentContext,
  ) : this(
      context,
      if (context.lithoConfiguration.componentsConfig.enableLazyListChildGroups) HookState()
      else null,
  )

  @Deprecated("Use the primary constructor instead, and call withResolveContext explicitly")
  constructor(context: ComponentContext, resolveContext: ResolveContext?) : this(context) {
    _resolveContext = resolveContext
  }

  /**
   * Creates a scope that renders as part of [host]. Hooks called on it belong to the component
   * [host] is rendering: their state, effects and transitions are that component's, keyed under
   * whichever hook key [host] is in when they run. This lets an API give a block a receiver of its
   * own type while the block's hooks still run in the enclosing render.
   *
   * Hooks called on it follow the rules for conditional hooks: their state and effects live only
   * while [host] runs the block in every render. A render that does not run the block drops its
   * state and detaches its effects.
   *
   * Don't keep a hosted scope past the render that created it: it still refers to that render's
   * hook data, so a later `useState` fails and a later `useEffect` is silently lost.
   */
  @ExperimentalLithoApi
  protected constructor(host: ComponentScope) : this(host.context, host.requireHookState())

  // Shared with every scope created for this render with the hosted constructor. Null while
  // enableLazyListChildGroups is off: this scope then keeps its hook data in the fields below.
  private var hookState: HookState? = hookState

  private var resolveContextField: ResolveContext? = null
  private var stateAlreadyMarkedAsUsedField: Boolean = false
  private var ownedStateContainerField: KStateContainer? = null
  private var transitionDataField: MutableTransitionData? = null
  private var useEffectEntriesField: MutableList<Attachable>? = null
  private var resolveEffectsField: MutableList<Attachable>? = null
  private var discardKeyField: Any? = null
  private var visitedHookKeysField: MutableList<HookKey>? = null
  private var currentHookKeyField: HookKey? = null
  private var hookDepthField: Int = 0
  private var hookIdCountersByDepthField: MutableList<MutableIntIntMap>? = null
  private var hookEnterCalledField: Boolean = false
  private var customHookKeyCountsField: MutableMap<HookKey, Int>? = null
  private var reportedDuplicateCustomHookKeyField: Boolean = false

  private fun requireHookState(): HookState =
      hookState
          ?: HookState().also { state ->
            state.resolveContext = resolveContextField
            state.stateAlreadyMarkedAsUsed = stateAlreadyMarkedAsUsedField
            state.ownedStateContainer = ownedStateContainerField
            state.transitionData = transitionDataField
            state.useEffectEntries = useEffectEntriesField
            state.resolveEffects = resolveEffectsField
            state.discardKey = discardKeyField
            state.visitedHookKeys = visitedHookKeysField
            state.currentHookKey = currentHookKeyField
            state.hookDepth = hookDepthField
            state.hookIdCountersByDepth = hookIdCountersByDepthField
            state.hookEnterCalled = hookEnterCalledField
            state.customHookKeyCounts = customHookKeyCountsField
            state.reportedDuplicateCustomHookKey = reportedDuplicateCustomHookKeyField
            hookState = state
          }

  private var isPristine: Boolean = true

  private var _resolveContext: ResolveContext?
    get() = hookState.let { if (it != null) it.resolveContext else resolveContextField }
    set(value) {
      val state = hookState
      if (state != null) state.resolveContext = value else resolveContextField = value
    }

  internal val resolveContext: ResolveContext
    get() =
        checkNotNull(_resolveContext) {
          "ResolveContext not found in scope. Did you forget to call ComponentScope.withResolveContext?"
        }

  /**
   * The resolve version inherited from the parent tree's resolve pass.
   *
   * This version should be used when performing nested tree resolves to ensure version consistency
   * across the tree hierarchy. It enables proper ordering of concurrent resolve operations.
   */
  internal val resolveVersion: Int
    get() = resolveContext.layoutVersion

  /**
   * Whether Litho asked this background resolve to stop so that the main thread can finish it.
   * Render can stop work it is able to resume later and leave the rest to the main thread. Always
   * false on the main thread, and false once render returns.
   */
  val isResolveInterrupted: Boolean
    get() = _resolveContext?.isResolveInterrupted ?: false

  /**
   * Whether this resolve was cancelled: its tree was released while it ran, so Litho discards its
   * result, and render can stop and drop its own work. False once render returns: Litho also
   * releases a future once it finishes and no caller waits for it any more, so a released future
   * only means a cancelled resolve while render runs.
   */
  val isResolveCancelled: Boolean
    get() = _resolveContext?.isFutureReleased ?: false

  internal var stateAlreadyMarkedAsUsed: Boolean
    get() = hookState.let {
      if (it != null) it.stateAlreadyMarkedAsUsed else stateAlreadyMarkedAsUsedField
    }
    set(value) {
      val state = hookState
      if (state != null) state.stateAlreadyMarkedAsUsed = value
      else stateAlreadyMarkedAsUsedField = value
    }

  // state container of the component for the current render pass
  internal var ownedStateContainer: KStateContainer?
    get() = hookState.let { if (it != null) it.ownedStateContainer else ownedStateContainerField }
    set(value) {
      val state = hookState
      if (state != null) state.ownedStateContainer = value else ownedStateContainerField = value
    }

  internal var transitionData: MutableTransitionData?
    get() = hookState.let { if (it != null) it.transitionData else transitionDataField }
    set(value) {
      val state = hookState
      if (state != null) state.transitionData = value else transitionDataField = value
    }

  internal var useEffectEntries: MutableList<Attachable>?
    get() = hookState.let { if (it != null) it.useEffectEntries else useEffectEntriesField }
    set(value) {
      val state = hookState
      if (state != null) state.useEffectEntries = value else useEffectEntriesField = value
    }

  internal var resolveEffects: MutableList<Attachable>?
    get() = hookState.let { if (it != null) it.resolveEffects else resolveEffectsField }
    set(value) {
      val state = hookState
      if (state != null) state.resolveEffects = value else resolveEffectsField = value
    }

  // Identifies this render's [useOnDiscard] callbacks; recorded on its node.
  internal var discardKey: Any?
    get() = hookState.let { if (it != null) it.discardKey else discardKeyField }
    set(value) {
      val state = hookState
      if (state != null) state.discardKey = value else discardKeyField = value
    }

  internal var visitedHookKeys: MutableList<HookKey>?
    get() = hookState.let { if (it != null) it.visitedHookKeys else visitedHookKeysField }
    set(value) {
      val state = hookState
      if (state != null) state.visitedHookKeys = value else visitedHookKeysField = value
    }

  /**
   * Records [hookKey] as visited this render for liveness-based pruning. Lazily borrows a buffer
   * from the per-thread [VisitedHookKeysPool] (cleared on borrow) so repeated renders of the same
   * component reuse buffers instead of allocating a fresh list each time. The buffer is returned to
   * the pool at the end of [withResolveContext].
   */
  internal fun recordVisitedHookKey(hookKey: HookKey) {
    (visitedHookKeys ?: VisitedHookKeysPool.borrow().also { visitedHookKeys = it }).add(hookKey)
  }

  private var _currentHookKey: HookKey?
    get() = hookState.let { if (it != null) it.currentHookKey else currentHookKeyField }
    set(value) {
      val state = hookState
      if (state != null) state.currentHookKey = value else currentHookKeyField = value
    }

  private var hookDepth: Int
    get() = hookState.let { if (it != null) it.hookDepth else hookDepthField }
    set(value) {
      val state = hookState
      if (state != null) state.hookDepth = value else hookDepthField = value
    }

  private var hookIdCountersByDepth: MutableList<MutableIntIntMap>?
    get() = hookState.let {
      if (it != null) it.hookIdCountersByDepth else hookIdCountersByDepthField
    }
    set(value) {
      val state = hookState
      if (state != null) state.hookIdCountersByDepth = value else hookIdCountersByDepthField = value
    }

  private var hookEnterCalled: Boolean
    get() = hookState.let { if (it != null) it.hookEnterCalled else hookEnterCalledField }
    set(value) {
      val state = hookState
      if (state != null) state.hookEnterCalled = value else hookEnterCalledField = value
    }

  private var customHookKeyCounts: MutableMap<HookKey, Int>?
    get() = hookState.let { if (it != null) it.customHookKeyCounts else customHookKeyCountsField }
    set(value) {
      val state = hookState
      if (state != null) state.customHookKeyCounts = value else customHookKeyCountsField = value
    }

  private var reportedDuplicateCustomHookKey: Boolean
    get() = hookState.let {
      if (it != null) it.reportedDuplicateCustomHookKey else reportedDuplicateCustomHookKeyField
    }
    set(value) {
      val state = hookState
      if (state != null) state.reportedDuplicateCustomHookKey = value
      else reportedDuplicateCustomHookKeyField = value
    }

  /**
   * Called by compiler-injected code before a dynamically repeated @Hook function call. Creates a
   * [HookKey] from the compile-time assigned [callSiteId] and a runtime index that counts how many
   * times this call site has been entered within its parent hook invocation.
   */
  fun enterHook(callSiteId: Int) {
    val countersByDepth =
        hookIdCountersByDepth ?: ArrayList<MutableIntIntMap>().also { hookIdCountersByDepth = it }
    while (hookDepth >= countersByDepth.size) {
      countersByDepth.add(MutableIntIntMap())
    }
    val counters = countersByDepth[hookDepth]
    val index =
        counters.getOrDefault(callSiteId, 0).also {
          counters[callSiteId] = it + 1
        }
    enterHook(callSiteId, index)
  }

  /** Called for a hook call site whose [index] the compiler determined statically. */
  fun enterHook(callSiteId: Int, index: Int) {
    hookEnterCalled = true
    _currentHookKey = HookKey.create(callSiteId, index, parent = _currentHookKey)
    hookDepth++

    hookIdCountersByDepth?.let { countersByDepth ->
      // The next depth belongs to the hook just entered. Clear counters left by its previous
      // sibling invocation so call-site indexes are scoped to this parent, not shared across
      // functions.
      if (hookDepth < countersByDepth.size) {
        countersByDepth[hookDepth].clear()
      }
    }
  }

  /**
   * Called by compiler-injected code after a @Hook function call returns. Restores the hook key to
   * the parent scope.
   */
  fun exitHook() {
    check(hookDepth > 0 && _currentHookKey != null) {
      "Hook identity error: exitHook() called without a matching enterHook()."
    }
    _currentHookKey = _currentHookKey?.parent
    hookDepth--
  }

  /** The current hook identity key, or null if not inside a hook call. */
  internal val currentHookKey: HookKey?
    get() = _currentHookKey

  /**
   * Returns the current [HookKey], or throws with a diagnostic message if it is null.
   *
   * Two failure modes are detected:
   * 1. `enterHook()` was never called — the Litho compiler plugin was not applied to the BUCK
   *    target that contains the calling component.
   * 2. `enterHook()` was called but `currentHookKey` is still null — a bug in the compiler plugin
   *    or runtime (enterHook should always set a non-null key).
   */
  internal fun requireCurrentHookKey(): HookKey {
    _currentHookKey?.let {
      return it
    }

    val componentName = context.scopedComponentInfo.component.simpleName
    if (!hookEnterCalled) {
      error(
          "Hook identity error: enterHook() was never called for $componentName. " +
              "The Litho compiler plugin was not applied to the BUCK target that contains this component. " +
              "Fix: add a direct dep on a Litho target (e.g. " +
              "\"//fbandroid/java/com/facebook/litho:litho\") to the target's BUCK file. " +
              "See https://fburl.com/litho-hook-identity for details.",
      )
    }
    error(
        "Hook identity error: currentHookKey is null for $componentName even though " +
            "enterHook() was called. This is likely a bug in the Litho compiler plugin or runtime.",
    )
  }

  @PublishedApi
  internal fun enterNewHookScope(key: Any): HookKey {
    val compilerKey = requireCurrentHookKey()
    check(!compilerKey.isCustom) {
      "Hook identity error: withNewScope() was not entered through compiler-generated hook identity."
    }
    val baseKey = HookKey.createCustom(compilerKey.callSiteId, key, compilerKey.parent)
    val keyCounts = customHookKeyCounts ?: HashMap<HookKey, Int>().also { customHookKeyCounts = it }
    val duplicateIndex = keyCounts[baseKey] ?: 0
    keyCounts[baseKey] = duplicateIndex + 1
    if (duplicateIndex > 0 && !reportedDuplicateCustomHookKey) {
      reportedDuplicateCustomHookKey = true
      DebugInfoReporter.report(
          category = "HookIdentity:DuplicateWithNewScopeKey",
          level = LogLevel.WARNING,
      ) {
        put("component", context.componentScope?.simpleName ?: "unknown")
        put("callSiteId", compilerKey.callSiteId)
      }
    }
    _currentHookKey =
        if (duplicateIndex == 0) {
          baseKey
        } else {
          HookKey.createCustom(
              compilerKey.callSiteId,
              key,
              compilerKey.parent,
              duplicateIndex,
          )
        }
    return compilerKey
  }

  @PublishedApi
  internal fun exitNewHookScope(compilerKey: HookKey, verifyBalance: Boolean = true) {
    val customKey = _currentHookKey
    val isBalanced =
        customKey?.isCustom == true &&
            customKey.callSiteId == compilerKey.callSiteId &&
            customKey.parent == compilerKey.parent
    _currentHookKey = compilerKey
    if (verifyBalance) {
      check(isBalanced) {
        "Hook identity error: withNewScope() exited with an unbalanced hook scope."
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  internal inline fun <T> withResolveContext(
      resolveContext: ResolveContext,
      crossinline block: ComponentScope.() -> T,
  ): T {
    check(isPristine) {
      "This ComponentScope already executed withResolveContext and cannot be reused"
    }
    isPristine = false
    try {
      _resolveContext = resolveContext
      val result = block()
      pruneToVisitedIfNeeded()
      return result
    } finally {
      _resolveContext = null
      ownedStateContainer = null
      recycleVisitedHookKeys()
    }
  }

  /**
   * Returns the visited-hook buffer to [VisitedHookKeysPool] for reuse by the next render on this
   * thread. [retainByHookKeys] consumes it synchronously and does not retain it, so recycling is
   * safe. Runs in [withResolveContext]'s `finally`, including the exception path.
   */
  private fun recycleVisitedHookKeys() {
    visitedHookKeys?.let { buffer ->
      visitedHookKeys = null
      VisitedHookKeysPool.release(buffer)
    }
  }

  /**
   * Hook-state pruning: at render end, drop committed [KStateContainer] entries whose hook was not
   * invoked this render, retaining the [visitedHookKeys]. No-op and no allocation when nothing is
   * dropped.
   */
  private fun pruneToVisitedIfNeeded() {
    // No hooks ran this render: nothing to retain, and no prune needed -- a re-rendered hookless
    // component is never markStateInUse'd, so clearUnusedState reclaims its whole container.
    val visited = visitedHookKeys ?: return
    val treeState = resolveContext.treeState
    val key = context.globalKey
    val isLayoutState = context.isContextForLayout
    @Suppress("UNCHECKED_CAST")
    val existing =
        treeState.getState(key, isLayoutState) as? ComponentState<KStateContainer> ?: return
    val retained = existing.value.retainByHookKeys(visited)
    if (retained !== existing.value) {
      val retainedState = existing.copy(value = retained)
      treeState.addState(key, retainedState, isLayoutState)
      // Keep scopedComponentInfo.state in sync with the published container.
      context.scopedComponentInfo.state = retainedState
    }
  }

  /**
   * A utility function to find the View with a given tag under the current Component's LithoView.
   * To set a view tag, use Style.viewTag. An appropriate time to call this is in your Component's
   * onVisible callback.
   *
   * <p>As with View.findViewWithTag in general, this must be called on the main thread.
   *
   * <p>Note that null may be returned if the associated View doesn't exist or isn't mounted: with
   * incremental mount turned on (which is the default), if the component is off-screen, it won't be
   * mounted.
   *
   * <p>Finally, note that you should never hold a reference to the view returned by this function
   * as Litho may unmount your Component and mount it to a different View.
   */
  fun <T : View> findViewWithTag(tag: Any): T? {
    return context.findViewWithTag(tag)
  }
}

/**
 * What one component render records through its hooks. A scope created for this render with the
 * hosted constructor shares the instance, so hooks called on either scope belong to the same
 * component. Add per-render hook data here and as a field on [ComponentScope], which keeps it there
 * while enableLazyListChildGroups is off.
 */
internal class HookState {
  var resolveContext: ResolveContext? = null
  var stateAlreadyMarkedAsUsed: Boolean = false
  var ownedStateContainer: KStateContainer? = null
  var transitionData: MutableTransitionData? = null
  var useEffectEntries: MutableList<Attachable>? = null
  var resolveEffects: MutableList<Attachable>? = null
  var discardKey: Any? = null
  // Hook keys visited this render, consumed by pruneToVisitedIfNeeded. Used only for membership, so
  // a list works and is cheaper than a set for the few hooks per component. The buffer is borrowed
  // from [VisitedHookKeysPool] and returned in [ComponentScope.withResolveContext].
  var visitedHookKeys: MutableList<HookKey>? = null
  var currentHookKey: HookKey? = null
  var hookDepth: Int = 0
  var hookIdCountersByDepth: MutableList<MutableIntIntMap>? = null
  var hookEnterCalled: Boolean = false
  var customHookKeyCounts: MutableMap<HookKey, Int>? = null
  var reportedDuplicateCustomHookKey: Boolean = false
}

/**
 * Gives every hook called by [block] an identity nested under [key]. Use this when the same source
 * hook call renders several logical items whose order can change. The key must be immutable, stable
 * across renders, and unique among invocations of this [withNewScope] call site under the same
 * parent scope. Duplicate sibling keys are reported and disambiguated by their order. Two different
 * call sites may use the same key without colliding.
 *
 * The key is retained with hook state. Prefer a small value object or identifier over a mutable or
 * heavyweight object.
 */
@Hook
inline fun <R> ComponentScope.withNewScope(key: Any, block: ComponentScope.() -> R): R {
  val compilerKey = enterNewHookScope(key)
  var failed = false
  try {
    return block()
  } catch (throwable: Throwable) {
    failed = true
    throw throwable
  } finally {
    exitNewHookScope(compilerKey, verifyBalance = !failed)
  }
}

/**
 * Per-thread free-list of reusable [HookKey] buffers for hook-state pruning ([ComponentScope.
 * visitedHookKeys]). Resolve runs each component's render (and its
 * [ComponentScope.withResolveContext] span) start-to-finish on a single thread; nested resolves on
 * the same thread simply borrow additional buffers and return them as they unwind, so a
 * thread-confined stack is safe regardless of cross-tree resolve parallelism. Recycling removes the
 * per-render list allocation that pruning would otherwise add on every render of every hook-using
 * component.
 */
private object VisitedHookKeysPool {
  private const val MAX_POOLED = 8

  // LithoThreadLocal fast-paths the main and default layout (resolve) threads with plain field
  // access, avoiding java.lang.ThreadLocal's map lookup on the per-render borrow/release hot path
  // (and sidesteps ThreadLocal.withInitial, which is API 26 while litho-core minSdk is 21). The
  // free-list is a plain ArrayList used as a stack (add / removeAt) to avoid ArrayDeque's
  // addLast/removeLast, which trip the API 35 SequencedCollection NewApi lint.
  private val pool = LithoThreadLocal<ArrayList<MutableList<HookKey>>>()

  fun borrow(): MutableList<HookKey> {
    val freeList = pool.getOrSet { ArrayList() }
    return if (freeList.isEmpty()) ArrayList()
    else freeList.removeAt(freeList.size - 1).also { it.clear() }
  }

  fun release(list: MutableList<HookKey>) {
    list.clear()
    val freeList = pool.getOrSet { ArrayList() }
    if (freeList.size < MAX_POOLED) {
      freeList.add(list)
    }
  }
}
