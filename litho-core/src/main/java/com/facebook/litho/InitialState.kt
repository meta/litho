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

import androidx.annotation.VisibleForTesting
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.state.ComponentState
import com.facebook.rendercore.utils.areObjectsEquivalent
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import javax.annotation.concurrent.GuardedBy
import javax.annotation.concurrent.ThreadSafe

/**
 * The InitialStateContainer is a lookaside table used by a ComponentTree to create initial states
 * for Components. The idea is that the onCreateInitialState result for each component will be
 * cached and stored here so that we can guarantee it's not called multiple times on multiple
 * threads. We keep the initial states cached as long as there is a layout happening for the
 * ComponentTree. As soon as we detect that all in-flights layout have terminated we can clean up
 * the initial states cache.
 *
 * Locking discipline, as numbered invariants:
 * - **INV-1 (single monitor)**: every read or write of a [Slot]'s contents happens inside
 *   `synchronized(slot.lock)` for the slot returned by *this invocation's* [slotFor].
 * - **INV-2 (fetch-once)**: each public method calls [slotFor] exactly once, holds the result in a
 *   local, and never re-reads [slots] after acquiring the lock. Without this, a drain between the
 *   vend and the lock acquisition would let two threads hold different monitors over the same
 *   logical key.
 * - **INV-3 (co-drain)**: monitor and data are allocated together ([Slot]) and discarded together
 *   (`slots = null`), so a drain cannot go partial. Any future per-key cache belongs *inside*
 *   [Slot], never beside it. A thread still holding a pre-drain [Slot] keeps writing to it and its
 *   seed is discarded rather than resurrected into the new epoch -- safe only because a registered
 *   `StateHandler` keeps `pendingStateHandlers` non-empty and so prevents the drain.
 */
@ThreadSafe
class InitialState {

  // All the initial states that have been created and can not yet be released. This is a concurrent
  // map as we can access it from multiple threads. The safety is given by the fact that we will
  // only get and set for a key while holding a lock for that specific key.
  //
  // Holds *atomically created* containers only: Spec components (whose @OnCreateInitialState
  // populates the whole container in one call) and legacy index-mode hook containers (whose lookup
  // is positional and therefore needs insertion order preserved).
  @JvmField
  @VisibleForTesting
  val states = Collections.synchronizedMap(HashMap<GlobalKey, ComponentState<out StateContainer>>())

  /**
   * Per-[GlobalKey] seeding slot. The monitor and the data it guards are allocated together and
   * discarded together, so a drain can never leave a live monitor pointing at dead data, nor two
   * monitors over one logical key. Vended only by [slotFor].
   */
  private class Slot {
    val lock = Any()

    /**
     * Hook seeds for this key, one entry per hook. LinkedHashMap preserves render order when
     * KStateContainer.fromEntries materialises the state container.
     */
    @GuardedBy("lock") var hooks: LinkedHashMap<HookKey, CachedValue>? = null
  }

  @GuardedBy("this") private var slots: MutableMap<GlobalKey, Slot>? = null

  /**
   * Returns the [Slot] for [key], creating it if absent.
   *
   * Call this **exactly once** per public-method invocation and hold the result in a local; see
   * INV-2 above. The `getOrPut` here is atomic only because it runs wholly under
   * `synchronized(this)` on a plain [HashMap].
   */
  private fun slotFor(key: GlobalKey): Slot =
      synchronized(this) {
        (slots ?: HashMap<GlobalKey, Slot>().also { slots = it }).getOrPut(key) { Slot() }
      }

  @JvmField
  @GuardedBy("this")
  @VisibleForTesting
  var pendingStateHandlers: MutableSet<StateHandler> = HashSet()

  /**
   * Called when the ComponentTree creates a new StateHandler for a new layout computation. We keep
   * track of this new StateHandler so that we know that we need to wait for this layout computation
   * to finish before we can clear the initial states map.
   */
  @Synchronized
  fun registerStateHandler(stateHandler: StateHandler) {
    pendingStateHandlers.add(stateHandler)
  }

  fun createOrGetState(
      component: Component,
      scopedContext: ComponentContext,
      key: GlobalKey,
  ): ComponentState<out StateContainer> {
    val stateLock: Any = slotFor(key).lock

    return synchronized(stateLock) {
      val state =
          states.getOrPut(key) {
            createInitialState(context = scopedContext, component = component)
          }
      state
    }
  }

  private fun createInitialState(
      context: ComponentContext,
      component: Component,
  ): ComponentState<out StateContainer> {
    val specComponent = component as SpecGeneratedComponent
    val state =
        trace({ "create-initial-state:${component.simpleName}" }) {
          specComponent.createInitialStateContainer(context)
        }
    return ComponentState(
        value = state,
        eventDispatchInfo =
            EventDispatchInfo(
                hasEventDispatcher = specComponent,
                componentContext = context,
            ),
    )
  }

  fun getInitialStateForComponent(key: GlobalKey): ComponentState<out StateContainer>? {
    val stateLock: Any = slotFor(key).lock

    synchronized(stateLock) {
      return states[key]
    }
  }

  /**
   * If an initial state for this component has already been created just return it, otherwise
   * execute the initializer and cache the result. Uses [HookKey]-based identity instead of
   * sequential index. The state is stored in a map-based [KStateContainer].
   */
  fun <T> getOrCreateInitialKStateByHookKey(
      key: GlobalKey,
      hookKey: HookKey,
      deps: Array<*>,
      initializer: HookInitializer<T>,
      componentName: String,
  ): ComponentState<KStateContainer> {
    val slot = slotFor(key)

    return synchronized(slot.lock) {
      val hooks = slot.hooksLocked()
      if (!hooks.containsKey(hookKey)) {
        hooks[hookKey] = CachedValue(hookKey, deps, initializer.init())
      }
      val container = KStateContainer.fromEntries(hooks.values)
      @Suppress("UNCHECKED_CAST") val existing = states[key] as ComponentState<KStateContainer>?
      existing?.copy(value = container) ?: ComponentState(value = container)
    }
  }

  /**
   * Looks up the initial value for [hookKey] in the lookaside cache, with a deps-equivalence guard.
   *
   * Three-way decision based on what the cache contains:
   * - Cached entry exists with deps that match [deps]: return the cached entry (concurrent-render
   *   dedup).
   * - Cached entry exists but deps differ: run the [initializer] and return a fresh [CachedValue]
   *   with the caller's deps; do NOT update the cache (another render owns the cached entry;
   *   overwriting would corrupt its `useStateWithDeps` semantics).
   * - No cached entry: run the [initializer], seed the cache, return the entry.
   *
   * Unlike [getOrCreateInitialKStateByHookKey], this does not return a full
   * [ComponentState]<[KStateContainer]> -- callers wanting to merge into committed state should do
   * so themselves with [KStateContainer.withNewStateByHookKey] using the returned `(value, deps)`.
   * This avoids the empty-source-map overwrite hazard when the cache has been drained by
   * [unregisterStateHandler].
   */
  fun <T> peekOrSeedInitialKStateByHookKey(
      key: GlobalKey,
      hookKey: HookKey,
      deps: Array<*>,
      initializer: HookInitializer<T>,
      componentName: String,
  ): CachedValue {
    val slot = slotFor(key)

    return synchronized(slot.lock) {
      val hooks = slot.hooksLocked()
      val cached = hooks[hookKey]
      when {
        cached != null && areObjectsEquivalent(cached.deps, deps) -> cached
        cached != null ->
            // Another in-flight render owns the cached entry; do not overwrite its deps.
            CachedValue(hookKey = hookKey, deps = deps, value = initializer.init())
        else -> CachedValue(hookKey, deps, initializer.init()).also { hooks[hookKey] = it }
      }
    }
  }

  /**
   * The flat per-hook seed map for this slot, created on first use.
   *
   * Callers must already hold `slot.lock` (INV-1).
   */
  private fun Slot.hooksLocked(): LinkedHashMap<HookKey, CachedValue> =
      hooks ?: LinkedHashMap<HookKey, CachedValue>(INITIAL_HOOKS_CAPACITY).also { hooks = it }

  /**
   * True when nothing is cached for any key, in any storage.
   *
   * Test-only. Prefer this over asserting on [states] directly so that assertions describe the
   * seeding *contract* ("the cache was drained") rather than the storage shape, and therefore keep
   * their meaning as the hook side moves out of [states].
   *
   * Reads the two storages under separate lock acquisitions, so the verdict is only meaningful with
   * no resolve in flight -- which is how every caller uses it.
   */
  @VisibleForTesting
  fun isCacheEmpty(): Boolean {
    if (states.isNotEmpty()) {
      return false
    }
    val currentSlots = synchronized(this) { slots?.values?.toList() } ?: return true
    return currentSlots.none { slot -> synchronized(slot.lock) { !slot.hooks.isNullOrEmpty() } }
  }

  /**
   * A snapshot of the hook-identity seeds cached for [key], keyed by [HookKey]; empty when nothing
   * has been seeded for [key].
   *
   * Test-only, and shape-independent by design: it reads whichever storage currently holds hook
   * seeds, so tests written against it survive the flattening of the hook side.
   */
  @VisibleForTesting
  fun hookSnapshot(key: GlobalKey): Map<HookKey, CachedValue> {
    // Deliberately does NOT go through slotFor: an observational helper must not create a Slot (nor
    // resurrect a drained `slots` map) for the state it is inspecting.
    val slot = synchronized(this) { slots?.get(key) }
    synchronized(slot?.lock ?: this) {
      slot?.hooks?.let {
        return LinkedHashMap(it)
      }
      val container = states[key]?.value as? KStateContainer ?: return emptyMap()
      val snapshot = LinkedHashMap<HookKey, CachedValue>(container.states.size)
      for (entry in container.states) {
        entry.hookKey?.let { snapshot[it] = entry }
      }
      return snapshot
    }
  }

  /**
   * Called when a resolve or layout computation finishes and its result has been committed or
   * discarded. Removes the StateHandler from the pending set and clears the cached initial states
   * if no other computations are in-flight.
   *
   * Callers must ensure this runs AFTER committing or discarding the computation result, not inside
   * the resolve/layout computation itself. Use [InitialStateScope] to guarantee correct lifecycle
   * ordering.
   */
  @Synchronized
  fun unregisterStateHandler(stateHandler: StateHandler) {
    pendingStateHandlers.remove(stateHandler)
    if (pendingStateHandlers.isEmpty()) {
      // INV-3: monitors and the data they guard are dropped in one statement. Both storage shapes
      // are drained unconditionally so flipping the flag mid-process cannot leak the other one.
      slots = null
      states.clear()
    }
  }

  private companion object {
    /**
     * Most components declare only a handful of hooks, so start small and let the few hook-heavy
     * ones grow. Growth is amortised O(N) in total, which is the point of this storage.
     */
    const val INITIAL_HOOKS_CAPACITY = 4
  }
}
