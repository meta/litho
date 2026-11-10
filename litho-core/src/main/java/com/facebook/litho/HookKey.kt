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

import androidx.collection.MutableLongObjectMap
import com.facebook.proguard.annotations.DoNotStrip

/**
 * A structured key that identifies a hook call site within a component's render method.
 *
 * Each hook call is identified by a compiler-generated call-site id, a local identity, and a parent
 * pointer. Ordinary hooks use their runtime occurrence index as the local identity. [withNewScope]
 * replaces that occurrence index with a developer-provided key so hooks in a collection remain
 * attached to the same item when the collection is reordered.
 *
 * The [id] field packs two 32-bit integers into a single Long (see [IntSize] for a similar
 * pattern):
 * - **High 32 bits**: the call-site id — a stable hash of the enclosing callable identity and its
 *   local @Hook-call ordinal.
 * - **Low 32 bits**: the runtime index — how many times this call-site has been entered within its
 *   parent hook invocation (distinguishes calls in loops).
 *
 * When interning is enabled (see [create]'s `intern` parameter), [create] returns the same object
 * for the same (id, parent) combination across render passes, avoiding repeated allocations for
 * deterministic hook call trees. This also enables fast referential equality checks in map lookups.
 *
 * @param id Packed call-site id (high 32) and runtime index (low 32) for an ordinary hook key.
 * @param parent The HookKey of the enclosing hook if this call is nested inside another @Hook
 *   function, or null if at the top level of a component's render method.
 */
open class HookKey
private constructor(
    val id: Long,
    val parent: HookKey?,
) {

  /**
   * Memoised [hashCode], computed on first use and cached -- the same pattern [GlobalKey] uses.
   *
   * The recursive form this replaced walked the whole parent chain on every call, and hash
   * containers call [hashCode] on every `get` *and* every `put`, so a deep key paid that walk on
   * every lookup. Caching is safe because both [id] and [parent] are immutable; [children] is a
   * cache and deliberately not part of the identity.
   *
   * Lazy rather than eager because with interning off every `create` allocates a fresh key, and
   * most of those are never used as a map key -- eager hashing would be pure waste for them. `0`
   * doubles as "not yet computed"; a key whose real hash is 0 simply recomputes, which is correct
   * and rare. Unsynchronised on purpose: concurrent callers race to write the same value, and an
   * `Int` write cannot tear.
   */
  private var cachedHashCode: Int = 0

  private var children: MutableLongObjectMap<HookKey>? = null

  private fun getOrCreateChild(childId: Long): HookKey {
    synchronized(this) {
      val c = children ?: MutableLongObjectMap<HookKey>().also { children = it }
      c[childId]?.let {
        return it
      }
      return HookKey(childId, this).also { c[childId] = it }
    }
  }

  // Ordinary and custom keys occupy disjoint identity domains; the override mirrors this type
  // check so equality remains symmetric.
  protected open fun hasSameLocalIdentity(other: HookKey): Boolean =
      other !is CustomHookKey && id == other.id

  protected open fun localIdentityHashCode(): Int = id.hashCode()

  final override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is HookKey) return false
    return hasSameLocalIdentity(other) && parent == other.parent
  }

  final override fun hashCode(): Int {
    if (cachedHashCode != 0) return cachedHashCode
    val h = 31 * localIdentityHashCode() + (parent?.hashCode() ?: 0)
    cachedHashCode = h
    return h
  }

  @Suppress("REDUNDANT_MODALITY_MODIFIER") open override fun toString(): String = "$id:$parent"

  internal val callSiteId: Int
    get() = (id shr 32).toInt()

  internal val isCustom: Boolean
    get() = this is CustomHookKey

  private class CustomHookKey(
      callSiteId: Int,
      private val customKey: Any,
      private val duplicateIndex: Int,
      parent: HookKey?,
  ) : HookKey(pack(callSiteId, duplicateIndex), parent) {

    override fun hasSameLocalIdentity(other: HookKey): Boolean =
        other is CustomHookKey && id == other.id && customKey == other.customKey

    override fun localIdentityHashCode(): Int = 31 * id.hashCode() + customKey.hashCode()

    override fun toString(): String = "$callSiteId[$customKey,$duplicateIndex]:$parent"
  }

  companion object {
    private const val BIT_MASK: Long = 0xFFFFFFFFL

    private val roots = MutableLongObjectMap<HookKey>()

    /**
     * Packs [callSiteId] and [index] into a single Long and returns a [HookKey].
     *
     * When [intern] is true, the returned instance is cached so that subsequent calls with the same
     * arguments return the same object. When false, a fresh instance is allocated every time.
     */
    fun create(callSiteId: Int, index: Int, parent: HookKey?, intern: Boolean = true): HookKey {
      val packed = pack(callSiteId, index)
      if (!intern) return HookKey(packed, parent)
      return if (parent == null) getOrCreateRoot(packed) else parent.getOrCreateChild(packed)
    }

    /** Creates a non-interned identity boundary for [withNewScope]. */
    internal fun createCustom(
        callSiteId: Int,
        key: Any,
        parent: HookKey?,
        duplicateIndex: Int = 0,
    ): HookKey = CustomHookKey(callSiteId, key, duplicateIndex, parent)

    private fun pack(callSiteId: Int, index: Int): Long =
        callSiteId.toLong().shl(32) or (index.toLong() and BIT_MASK)

    private fun getOrCreateRoot(id: Long): HookKey {
      synchronized(roots) {
        roots[id]?.let {
          return it
        }
        return HookKey(id, null).also { roots[id] = it }
      }
    }

    /**
     * Call-site id reserved for [warmUpForProfile]. The compiler plugin explicitly remaps this
     * value if a real call site hashes to it -- important because the [roots] intern map is
     * process-lifetime and never cleared.
     */
    internal const val WARM_UP_CALL_SITE_ID: Int = Int.MIN_VALUE

    /**
     * Executes the hook-identity-only methods of this class once, so that a PoGO Stick harvest
     * observes them and the generated ART profile AOT-compiles them. See [HookIdentityWarmUp].
     *
     * Must run unconditionally, in every experiment arm: the point is to make the profile identical
     * for control and treatment, not to make it match the treatment.
     */
    @DoNotStrip
    internal fun warmUpForProfile(): Any {
      // Interned root and child, so getOrCreateRoot/getOrCreateChild both execute. Exactly one
      // entry is added to the process-lifetime `roots` map, under the reserved call-site id.
      val root = create(WARM_UP_CALL_SITE_ID, 0, parent = null)
      val child = create(WARM_UP_CALL_SITE_ID, 1, parent = root)
      // Two equal-but-distinct parents, then two equal-but-distinct children of them, so that
      // `equals` recurses through a real parent chain rather than short-circuiting on `===` at the
      // first comparison the way it does under interning.
      val parentA = create(WARM_UP_CALL_SITE_ID, 2, parent = child, intern = false)
      val parentB = create(WARM_UP_CALL_SITE_ID, 2, parent = child, intern = false)
      val a = create(WARM_UP_CALL_SITE_ID, 3, parent = parentA, intern = false)
      val b = create(WARM_UP_CALL_SITE_ID, 3, parent = parentB, intern = false)
      var acc = 0
      acc += a.hashCode() // compute-and-memoise branch, recursing up the chain
      acc += a.hashCode() // cached branch
      acc += if (a == b) 1 else 0 // structural compare, two levels deep
      acc += if (a == root) 1 else 0 // early-out on id mismatch
      return acc + child.id.toInt()
    }
  }
}

/**
 * A globally unique key for a hook call within a component tree. Combines the component's global
 * key with the hook-local identity and the [RenderScope] in which the hook executes.
 *
 * @param globalKey The component's global key in the tree.
 * @param hookKey The hook-local identity -- either an [Int] index (legacy sequential mode) or a
 *   [HookKey] (hook identity mode).
 * @param scope The render phase ([RenderScope.Resolve] or [RenderScope.Layout]) in which this hook
 *   executes.
 */
data class HookGlobalKey(
    val globalKey: GlobalKey,
    val hookKey: Any,
    val scope: RenderScope,
) {
  override fun toString(): String = "$globalKey:$hookKey[$scope]"

  companion object {
    /** Java-friendly factory that creates a [HookGlobalKey] with [RenderScope.Resolve]. */
    @JvmStatic
    fun createForResolveScope(globalKey: GlobalKey, hookKey: Any): HookGlobalKey =
        HookGlobalKey(globalKey, hookKey, RenderScope.Resolve)

    /** Java-friendly factory that creates a [HookGlobalKey] with [RenderScope.Layout]. */
    @JvmStatic
    fun createForLayoutScope(globalKey: GlobalKey, hookKey: Any): HookGlobalKey =
        HookGlobalKey(globalKey, hookKey, RenderScope.Layout)
  }
}
