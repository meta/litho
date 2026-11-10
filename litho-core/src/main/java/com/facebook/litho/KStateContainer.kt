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

import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.rendercore.utils.NoDeps
import java.lang.UnsupportedOperationException
import java.util.ArrayList
import java.util.Collections

/**
 * The StateContainer implementation for Kotlin components. Tracks all state defined by useState
 * calls for the same component (see KState for usage). Immutable by default: every copy-on-write
 * mutation returns a new instance with the prior entries copied and one entry added or replaced.
 * The one exception is the transient render-owned fork ([mutableForkWith] / [mutateInPlace]): a
 * render forks a single mutable copy of its component's container and then mutates that copy in
 * place for the rest of the pass, tracked by [ComponentScope.ownedStateContainer]. Committed/shared
 * containers are never mutated in place - a subsequent render forks them.
 *
 * Each entry carries its [HookKey]; lookup is an O(N) linear scan over the list using [HookKey]
 * equality via [getByHookKey]/[hasHookKey]/[copyAndMutateByHookKey]. Interning lets equality
 * short-circuit on reference identity, so the scan is essentially a pointer compare.
 */
class KStateContainer : StateContainer {

  val states: List<CachedValue>

  /** Append [entry] to [prior]. */
  private constructor(prior: List<CachedValue>, entry: CachedValue) {
    val newStates = ArrayList<CachedValue>(prior.size + 1)
    newStates.addAll(prior)
    newStates.add(entry)
    this.states = Collections.unmodifiableList(newStates)
  }

  /** Replace the entry at [index] in [prior] with [entry]. [index] must be in `prior.indices`. */
  private constructor(prior: List<CachedValue>, index: Int, entry: CachedValue) {
    require(index in prior.indices) { "index $index out of bounds for prior size ${prior.size}" }
    val newStates = ArrayList(prior)
    newStates[index] = entry
    this.states = Collections.unmodifiableList(newStates)
  }

  /**
   * Adopts [entries] as the backing list (no copy), so the caller must pass a freshly-owned list.
   * [wrap] is false only for a render-owned fork, whose backing has to stay live for
   * [mutateInPlace].
   */
  private constructor(entries: List<CachedValue>, wrap: Boolean) {
    this.states = if (wrap) Collections.unmodifiableList(entries) else entries
  }

  /**
   * Fork a single mutable copy of this container with [hookKey] added or replaced. The returned
   * container's backing is a live [ArrayList] (not wrapped), so the owning render can keep mutating
   * it in place via [mutateInPlace] for the rest of the pass. The caller ([KState]) records the
   * result as [ComponentScope.ownedStateContainer] so subsequent mutations this pass route to
   * [mutateInPlace].
   */
  fun mutableForkWith(hookKey: HookKey, newValue: Any?, deps: Array<*>?): KStateContainer {
    // The +1 leaves room for the append case, so a fork that introduces a new hook does not grow
    // and re-copy the array on its very first mutation.
    val backing = ArrayList<CachedValue>(states.size + 1)
    backing.addAll(states)
    applyMutation(backing, hookKey, newValue, deps)
    return KStateContainer(backing, wrap = false)
  }

  /**
   * Mutate this container in place. MUST ONLY be done by a scope that owns this container for its
   * current render pass (tracked by [ComponentScope.ownedStateContainer]); the caller guarantees
   * that with an identity check.
   */
  fun mutateInPlace(hookKey: HookKey, newValue: Any?, deps: Array<*>?): KStateContainer {
    @Suppress("UNCHECKED_CAST") val backing = states as ArrayList<CachedValue>
    applyMutation(backing, hookKey, newValue, deps)
    return this
  }

  /**
   * Return a container with only the entries whose [CachedValue.hookKey] is in [live] (null-hookKey
   * index entries are always kept). Returns `this` when nothing is dropped.
   */
  fun retainByHookKeys(live: List<HookKey>): KStateContainer {
    if (states.size == live.size) {
      var sameOrder = true
      for (i in states.indices) {
        if (states[i].hookKey != live[i]) {
          sameOrder = false
          break
        }
      }
      if (sameOrder) {
        return this
      }
    }

    var anyDropped = false
    for (i in states.indices) {
      val hookKey = states[i].hookKey
      if (hookKey != null && hookKey !in live) {
        anyDropped = true
        break
      }
    }
    if (!anyDropped) {
      return this
    }
    val kept = ArrayList<CachedValue>(states.size)
    for (i in states.indices) {
      val entry = states[i]
      val hookKey = entry.hookKey
      if (hookKey == null || hookKey in live) {
        kept.add(entry)
      }
    }
    // wrap = true: the retained container is committed/shared, not a render-owned mutable fork.
    return KStateContainer(kept, wrap = true)
  }

  override fun applyStateUpdate(stateUpdate: StateUpdate) {
    throw UnsupportedOperationException(
        "Kotlin states should not be updated through applyStateUpdate calls",
    )
  }

  // Hook-identity mode

  fun getByHookKey(hookKey: HookKey): CachedValue? {
    val i = indexOfHookKey(states, hookKey)
    return if (i >= 0) states[i] else null
  }

  fun hasHookKey(hookKey: HookKey): Boolean = indexOfHookKey(states, hookKey) >= 0

  /**
   * Add or replace the entry for [hookKey], preserving its existing [CachedValue.deps] when one is
   * already present (and using [NoDeps] otherwise).
   */
  fun copyAndMutateByHookKey(hookKey: HookKey, newValue: Any?): KStateContainer {
    val replaceAt = indexOfHookKey(states, hookKey)
    val deps = if (replaceAt >= 0) states[replaceAt].deps else NoDeps
    return replaceOrAppend(states, replaceAt, CachedValue(hookKey, deps, newValue))
  }

  /** Add or replace the entry for [hookKey]; [deps] overwrites any previously stored deps. */
  fun copyAndMutateByHookKey(hookKey: HookKey, newValue: Any?, deps: Array<*>): KStateContainer =
      replaceOrAppend(states, indexOfHookKey(states, hookKey), CachedValue(hookKey, deps, newValue))

  override fun toString(): String = "KStateContainer(size=${states.size})"

  companion object {
    fun withNewStateByHookKey(
        kStateContainer: KStateContainer?,
        hookKey: HookKey,
        value: Any?,
        deps: Array<*> = NoDeps,
    ): KStateContainer =
        kStateContainer?.copyAndMutateByHookKey(hookKey, value, deps)
            ?: KStateContainer(emptyList(), CachedValue(hookKey, deps, value))

    /**
     * Materialise a container from [entries], preserving iteration order.
     *
     * Used by [InitialState] to hand back a container built from its flat per-hook seed map in one
     * pass, instead of growing one by copy-on-write append per hook. Takes a defensive copy, so the
     * caller may keep mutating its own collection.
     */
    internal fun fromEntries(entries: Collection<CachedValue>): KStateContainer =
        KStateContainer(ArrayList(entries), wrap = true)

    /**
     * Replace [prior]'s entry at [replaceAt] when non-negative; otherwise append. Encapsulates the
     * "look-up returned -1 (not found) vs valid index" branch so each hook-identity entry point
     * reads as a single line.
     */
    private fun replaceOrAppend(
        prior: List<CachedValue>,
        replaceAt: Int,
        entry: CachedValue,
    ): KStateContainer =
        if (replaceAt >= 0) KStateContainer(prior, replaceAt, entry)
        else KStateContainer(prior, entry)

    // Use `==` (HookKey.equals) so this works whether or not interning is enabled. When interning
    // is on, equals short-circuits on `this === other` so the cost is the same as `===`. When off,
    // it falls back to structural comparison up the parent chain (still cheap for typical depths).
    private fun indexOfHookKey(states: List<CachedValue>, hookKey: HookKey): Int {
      for (i in states.indices) {
        if (states[i].hookKey == hookKey) return i
      }
      return -1
    }

    /**
     * Number of synthetic hooks [warmUpForProfile] builds. Profile inclusion is presence-based --
     * `fbandroid/native/baseline_profiles/deep_data.py` only requires `avg_call >= 1` -- so this is
     * sized to make the scan loop iterate and both the replace and append branches run, not to
     * mirror the production hook count (~62 on Reels).
     */
    private const val WARM_UP_HOOK_COUNT: Int = 8

    /**
     * Executes the hook-identity-only methods of this class once, so that a PoGO Stick harvest
     * observes them and the generated ART profile AOT-compiles them. See [HookIdentityWarmUp].
     *
     * Must run unconditionally, in every experiment arm: the point is to make the profile identical
     * for control and treatment, not to make it match the treatment.
     */
    @DoNotStrip
    internal fun warmUpForProfile(): Any {
      // Non-interned keys: the scan has to run HookKey.equals's full body rather than short-circuit
      // on `===`, and nothing here should be retained by the process-lifetime intern map.
      val parent = HookKey.create(HookKey.WARM_UP_CALL_SITE_ID, 0, parent = null, intern = false)
      val keys = ArrayList<HookKey>(WARM_UP_HOOK_COUNT)
      val entries = ArrayList<CachedValue>(WARM_UP_HOOK_COUNT)
      for (i in 0 until WARM_UP_HOOK_COUNT) {
        val key = HookKey.create(HookKey.WARM_UP_CALL_SITE_ID, i, parent, intern = false)
        keys.add(key)
        entries.add(CachedValue(key, NoDeps, null))
      }

      // Flat seeding followed by a lookup per key, the last of which is a
      // full-length indexOfHookKey scan.
      var container = fromEntries(entries)
      var sink: Any? = null
      for (i in keys.indices) {
        sink = container.getByHookKey(keys[i])
      }
      sink = container.hasHookKey(keys[WARM_UP_HOOK_COUNT - 1])

      // Both copyAndMutateByHookKey overloads (replace branch), then the append branch.
      container = container.copyAndMutateByHookKey(keys[0], sink)
      container = container.copyAndMutateByHookKey(keys[0], sink, NoDeps)
      val appended =
          HookKey.create(HookKey.WARM_UP_CALL_SITE_ID, WARM_UP_HOOK_COUNT, parent, intern = false)
      container = withNewStateByHookKey(container, appended, sink)

      // Transient container: fork once, then mutate the fork in place.
      val forked = container.mutableForkWith(keys[1], sink, NoDeps)
      forked.mutateInPlace(keys[2], sink, NoDeps)

      // Pruning: drop at least one entry so the copying branch runs, not the `return this` no-op.
      val retained = container.retainByHookKeys(keys.subList(0, WARM_UP_HOOK_COUNT - 1))

      return retained.states.size + forked.states.size
    }

    /**
     * Add or replace [hookKey]'s entry in place. Shared by [mutableForkWith] and [mutateInPlace].
     */
    private fun applyMutation(
        backing: ArrayList<CachedValue>,
        hookKey: HookKey,
        newValue: Any?,
        deps: Array<*>?,
    ) {
      val i = indexOfHookKey(backing, hookKey)
      if (i >= 0) {
        backing[i] = CachedValue(hookKey, deps ?: backing[i].deps, newValue)
      } else {
        backing.add(CachedValue(hookKey, deps ?: NoDeps, newValue))
      }
    }
  }
}

/**
 * A single hook's cached value.
 *
 * [hookKey] is non-null when this entry was added via the hook-identity API
 * ([KStateContainer.copyAndMutateByHookKey] / [KStateContainer.withNewStateByHookKey]); null for
 * index-based (legacy Spec) entries.
 */
class CachedValue(val hookKey: HookKey?, val deps: Array<*>, val value: Any?) {

  /** Index-based / legacy constructor -- no hook key. */
  constructor(deps: Array<*>, value: Any?) : this(hookKey = null, deps = deps, value = value)
}
