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

import android.os.Bundle
import androidx.annotation.GuardedBy
import androidx.annotation.RestrictTo
import androidx.core.os.bundleOf
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.state.StateReadRecorder
import com.facebook.rendercore.LogLevel
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Registry of component state that can be snapshotted into Android saved state.
 *
 * Android's [androidx.savedstate.SavedStateRegistry] is owned and accessed by a lifecycle host on
 * the main thread. Litho may resolve components on multiple background threads, so a host restores
 * one [Bundle] into this registry and registers this registry's [snapshot] as one Android saved
 * state provider.
 *
 * A hook looks up its restored value on its first render, and again when its explicit key changes
 * or [generation] moves on; other renders only check [generation]. A snapshot reads the committed
 * state of the trees that rendered this registry's hooks, and keeps the restored values no hook has
 * taken, so they are saved again. A component that leaves its tree's committed state is no longer
 * saved. Two hooks saving under one key, as when separate trees share this registry, are not saved,
 * from the first save that finds both.
 *
 * This type is thread-safe. Savers run outside the registry lock. Locks are taken in one order: a
 * child's [parentConnectionLock], its parent's, the parent's [lock], then a child's [lock]; nothing
 * holding a [lock] calls up into a parent. The owner defines the registry lifetime and closes it
 * when the corresponding tree or navigation entry is permanently removed. A closed registry is
 * inert: calculations that still hold it read no restored values and it snapshots nothing.
 *
 * A child registry created without a key cannot be told apart from its siblings, so it keeps its
 * values only for its own lifetime and never reads or writes its parent's saved state. The same
 * applies to two open children with the same key: from the moment the second one reads, the key is
 * neither restored nor saved for the rest of the parent's lifetime.
 *
 * @param restoredState a snapshot produced by a previous registry instance and restored through
 *   Android's saved-state mechanism, or `null` for a new state scope.
 * @param valueClassLoader the host [android.content.Context] class loader used to deserialize
 *   custom values from the restored Bundle. Hosts should pass `Context.getClassLoader()`; `null`
 *   uses the Bundle's default class loader.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class LithoSaveableStateRegistry
private constructor(
    restoredState: Bundle? = null,
    private val valueClassLoader: ClassLoader? = null,
    private val parentRegistry: LithoSaveableStateRegistry? = null,
    private val parentStorageKey: String? = null,
    private val claimRootState: (() -> Bundle?)? = null,
    joinsParentWhenShown: Boolean = false,
) : AutoCloseable {
  // region State

  private val lock = Any()

  /**
   * The values not live in any tree, by key: restored values no hook has taken, copies kept while a
   * tree or row isn't live, and the parts of children that haven't claimed theirs. A value key maps
   * to its saved form, and a child key to that child's own map of the same shape. A restored Bundle
   * is read into it once. Values leave through [discardRetainedValue], and every snapshot writes
   * what's left, under the live values.
   */
  @GuardedBy("lock") private val retainedValues = HashMap<String, Any?>()

  /** Keys two live values had at once; values under them are no longer saved. */
  @GuardedBy("lock") private val ambiguousKeys = mutableSetOf<String>()

  @GuardedBy("lock") private var closed = false

  /**
   * Bumped when restored values arrive late and when this registry closes after hooks used it.
   * Hooks first rendered under an older generation read their registry again on their next render,
   * and trees that rendered under one render again before they are shown.
   */
  @Volatile
  @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  var generation: Int = 0
    private set

  /** The trees that rendered this registry's hooks. Weak, so a dropped tree frees its entry. */
  @Volatile @GuardedBy("lock") private var sources: MutableSet<LithoTree>? = null

  /** The providers of registered children's saved values, by child key. */
  @GuardedBy("lock") private val providers = mutableMapOf<String, Provider>()

  /**
   * The open child holding each child key. Weak, so an unregistered child dropped without closing
   * frees its key; a registered child stays reachable through its [Provider].
   */
  @GuardedBy("lock")
  private val childKeyHolders = mutableMapOf<String, WeakReference<LithoSaveableStateRegistry>>()

  /** Child keys two open children claimed at once; no child reads or saves values under them. */
  @GuardedBy("lock") private val ambiguousChildKeys = mutableSetOf<String>()

  /** Child keys [discardChildState] dropped before this registry restored its saved values. */
  @GuardedBy("lock") private val keysDiscardedBeforeRestore = mutableSetOf<String>()

  /** Guards restoring from the parent or owner, including the owner claim, and the parent link. */
  private val parentConnectionLock = Any()

  @GuardedBy("parentConnectionLock") private var parentStateRestored = false

  @GuardedBy("parentConnectionLock") private var rootStateClaimed = false

  @GuardedBy("parentConnectionLock") private var registeredProvider: Provider? = null

  @GuardedBy("parentConnectionLock") private var parentKeyAmbiguous = false

  /** Whether [detachFromParent] stopped saving this child, until [attachToParent]. */
  @GuardedBy("parentConnectionLock") private var detachedFromParent = false

  /** Whether [detachFromParent] gave up this child's key, which it claims again when saved. */
  @GuardedBy("parentConnectionLock") private var parentKeyReleased = false

  /** Whether this child was registered when detached, so [attachToParent] registers it again. */
  @GuardedBy("parentConnectionLock") private var registeredWhenDetached = false

  /** Whether this child is in its parent's snapshot; see [setSavedInParent]. */
  @GuardedBy("lock") private var savedInParent = true

  /**
   * False until [onTreeShown] for a registry created to join its parent only once its content is
   * shown. While false, a new tree or child doesn't make this registry join its parent yet.
   */
  @Volatile private var shown = !joinsParentWhenShown

  /** Whether a new tree or child used this registry before it was shown, so it joins when shown. */
  @Volatile private var joinDeferred = false

  init {
    if (restoredState != null) {
      val failures = ArrayList<RuntimeException>()
      readSnapshot(restoredState, failures)?.let(retainedValues::putAll)
      failures.forEach { reportSaveableStateRestoreFailure("restore", it) }
    }
  }

  constructor(
      restoredState: Bundle? = null,
      valueClassLoader: ClassLoader? = null,
  ) : this(restoredState, valueClassLoader, null, null, null)

  @GuardedBy("lock") private fun hasRetainedValuesLocked(): Boolean = retainedValues.isNotEmpty()

  @GuardedBy("lock")
  private fun hasRetainedValueLocked(key: String): Boolean = retainedValues.containsKey(key)

  @GuardedBy("lock")
  private fun discardRetainedValue(key: String) {
    retainedValues.remove(key)
  }

  // endregion

  // region Hook reads

  /** Returns restored state without consuming it during a potentially speculative resolve. */
  internal fun restoredValue(key: String): RestoredSaveableState? {
    restoreSavedStateIfNeeded()
    return synchronized(lock) { restoredValueLocked(key) }
  }

  @GuardedBy("lock")
  private fun restoredValueLocked(key: String): RestoredSaveableState? {
    if (closed || !retainedValues.containsKey(key)) return null
    return RestoredSaveableState(retainedValues[key])
  }

  /**
   * Adds [tree] to the trees whose hooks this registry saves. A hook calls it whenever it takes its
   * slot here, and [adoptTree] for the tree it adopts. A new tree asks to join the parent.
   */
  internal fun addSource(tree: LithoTree) {
    val added =
        synchronized(lock) {
          if (closed) return
          val trees =
              sources
                  ?: Collections.newSetFromMap(WeakHashMap<LithoTree, Boolean>()).also {
                    sources = it
                  }
          trees.add(tree)
        }
    if (added) joinParentWhenShown()
  }

  /** Drops the restored value a first render in [tree] took, now that the render committed. */
  internal fun onClaimCommitted(slot: SaveableStateSlot, tree: LithoTree) {
    // An unbound row item gives nothing up; the item bound later takes the value.
    if (!tree.treeStateProvider.isBoundToHost) return
    synchronized(lock) {
      if (closed || slot.generation != generation) return
      discardRetainedValue(checkNotNull(slot.storageKey))
    }
  }

  /**
   * Throws as a save would if [value] can't be saved under [key] as it is. A closed registry saves
   * nothing, so it checks nothing.
   */
  internal fun requireSavableValue(key: String, value: Any?) {
    if (synchronized(lock) { closed }) return
    valueBundle(key, value)
  }

  // endregion

  // region Saving and retaining

  /** Returns a Bundle containing retained values and the latest live values. */
  fun snapshot(): Bundle {
    restoreSavedStateIfNeeded()
    return toBundle(savedValues())
  }

  /**
   * Like [snapshot], but claims nothing from the parent or owner first, so it takes no
   * [parentConnectionLock]. The owner saves, parks and hands over its trees' values with it.
   */
  internal fun snapshotForOwner(): Bundle = toBundle(savedValues())

  /** Retained values and the latest live values, by key, in the form [retainedValues] keeps. */
  private fun savedValues(): Map<String, Any?> {
    // Retained values are copied before the walk: a first render committing in between is either
    // still in the copy or already in its tree's committed state.
    val (values, childProviders) =
        synchronized(lock) {
          if (closed) return emptyMap()
          val unsavedKeys = unsavedChildKeys()
          val retained =
              HashMap(retainedValues).apply {
                keys.removeAll(unsavedKeys)
                keys.removeAll(ambiguousKeys)
              }
          retained to providers.filterKeys { it !in unsavedKeys }.toList()
        }
    val (newlyAmbiguous, liveValues) = savedLiveValues()
    values.keys.removeAll(newlyAmbiguous)
    values.putAll(liveValues)
    childProviders.forEach { (key, provider) -> values[key] = provider.savedValues() }
    return values
  }

  /**
   * Retains active values before an owner replaces its ComponentTree, unbinds a collection row or
   * detaches a list, or before a destination's content leaves ([LithoSaveableStateHolder.retain]).
   *
   * A component that leaves the tree is no longer saved, so dynamic components don't accumulate
   * retained state for the entire registry lifetime. Tree, navigation and collection-row owners
   * call this operation only when the same logical state scope will continue, in a replacement tree
   * or when the row is bound again. A registry no hook has used and no child is registered in has
   * nothing to retain, and claims nothing from its parent or owner. A child left out of this
   * registry's snapshot ([setSavedInParent]) keeps its own values, which are not retained here.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun retainActiveValues() {
    if (synchronized(lock) { providers.isEmpty() && sources == null }) return
    restoreSavedStateIfNeeded()
    val activeProviders =
        synchronized(lock) {
          if (closed) return
          val unsavedKeys = unsavedChildKeys()
          providers.filterKeys { it !in unsavedKeys }.toList()
        }
    val values = activeProviders.map { (key, provider) ->
      Triple(key, provider, provider.savedValues())
    }
    val (_, liveValues) = savedLiveValues()

    synchronized(lock) {
      if (closed) return
      liveValues.forEach { (key, value) -> if (key !in ambiguousKeys) retainedValues[key] = value }
      val unsavedKeys = unsavedChildKeys()
      values.forEach { (key, provider, value) ->
        val currentProvider = providers[key]
        if (currentProvider === provider && key !in unsavedKeys) {
          retainedValues[key] = value
        }
      }
    }
  }

  private class LiveEntry(val key: String, val slot: SaveableStateSlot, val value: Any?)

  /**
   * The committed hook values of this registry's trees that are bound to a host. Calls into the
   * trees outside [lock].
   */
  private fun liveEntries(): List<LiveEntry> {
    // Not `toList()`: the GC can clear a weak key between its `size` and `next()` calls.
    val trees = synchronized(lock) { sources?.let { ArrayList(it) } } ?: return emptyList()
    return liveEntries(
        trees.mapNotNull { tree ->
          tree.treeStateProvider.takeIf { it.isBoundToHost }?.committedTreeState
        },
    )
  }

  /** This registry's hook values in [treeStates]. */
  private fun liveEntries(treeStates: List<TreeState>): List<LiveEntry> {
    val entries = ArrayList<LiveEntry>()
    treeStates.forEach { treeState ->
      treeState.forEachSaveableEntry { slot, value ->
        if (slot.scope === this) entries += LiveEntry(checkNotNull(slot.storageKey), slot, value)
      }
    }
    return entries
  }

  /**
   * The keys that just became ambiguous, as two live values have them, and the saved form of every
   * other live value, by key. A hook that has not read a late restored value yet keeps that value
   * saved instead of its own.
   */
  private fun savedLiveValues(): Pair<List<String>, Map<String, Any?>> {
    val entries = liveEntries()
    if (entries.isEmpty()) return emptyList<String>() to emptyMap()
    val newlyAmbiguous = ArrayList<String>()
    val saved =
        synchronized(lock) {
          if (closed) return emptyList<String>() to emptyMap()
          val seen = HashSet<String>()
          entries.forEach { entry ->
            if (!seen.add(entry.key)) {
              if (ambiguousKeys.add(entry.key)) newlyAmbiguous += entry.key
              discardRetainedValue(entry.key)
            }
          }
          entries.filter { entry ->
            entry.key !in ambiguousKeys &&
                (entry.slot.generation == generation || !hasRetainedValueLocked(entry.key))
          }
        }
    newlyAmbiguous.forEach(::reportDuplicateKey)
    return newlyAmbiguous to
        saved.associate { entry ->
          // Saves run outside the render of the tree that holds the value, sometimes inside another
          // tree's render, where a tracked read would count as a cross-tree read.
          val value =
              StateReadRecorder.untracked(preserveTreeContext = false) {
                entry.slot.save(entry.value)
              }
          valueBundle(entry.key, value) // Throws, naming the key, if the value can't be saved.
          entry.key to value
        }
  }

  private fun reportDuplicateKey(key: String) {
    DebugInfoReporter.report(
        category = DUPLICATE_KEY_CATEGORY,
        level = LogLevel.WARNING,
    ) {
      this["key"] = key
    }
  }

  // endregion

  // region Copies that live values replace

  internal val hasSources: Boolean
    get() = sources != null

  /**
   * Called once a bound row's item rendered, or showed its committed layout, for the bind. The
   * values it holds live replace the copies kept for them; a value it left out keeps its copy.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun onBoundRowRendered() {
    if (sources != null) dropRetainedValuesHeldLive()
  }

  /**
   * For [tree] built with [treeState], the TreeState of a tree that saved values here. When the
   * tree's first render commits, the values it kept replace the copies kept for them; a value it
   * left out keeps its copy.
   */
  internal fun adoptTree(tree: LithoTree, treeState: TreeState) {
    addSource(tree)
    treeState.adoptSaveableValuesAtNextCommit(this)
  }

  /** Called when the first render of a tree that [adoptTree] took commits into [treeState]. */
  internal fun onAdoptedTreeCommitted(treeState: TreeState) {
    dropRetainedValuesHeldLive(treeState)
  }

  /** Drops the copies kept for the values [treeState], or else any tree, holds live. */
  private fun dropRetainedValuesHeldLive(treeState: TreeState? = null) {
    if (synchronized(lock) { closed || !hasRetainedValuesLocked() }) return
    val entries = if (treeState != null) liveEntries(listOf(treeState)) else liveEntries()
    synchronized(lock) {
      if (closed) return
      entries.forEach { entry ->
        if (entry.slot.generation == generation) discardRetainedValue(entry.key)
      }
    }
  }

  // endregion

  // region Late restore

  /**
   * Adopts restored values that arrived after the tree's first render. Only called before the tree
   * mounts, so the restored values replace the initial ones: hooks that rendered before them read
   * them on their next render, which their trees run before they are shown. Keys not in [values]
   * keep their values.
   *
   * An open child that already read its key never reads it again, so it adopts the values saved
   * under that key the same way, and its own children after it. Ambiguous keys stay dropped.
   */
  internal fun adoptLateRestoredValues(values: Bundle) {
    val ambiguous =
        synchronized(lock) {
          if (closed) return
          ambiguousKeys + ambiguousChildKeys
        }
    val failures = ArrayList<RuntimeException>()
    val restored = readSnapshot(values, failures, skipped = ambiguous)
    failures.forEach { reportSaveableStateRestoreFailure("late restore", it) }
    restored?.let { adoptLateRestoredValues(it) }
  }

  /** Adopts late restored values that were already read, as an open child gets them. */
  private fun adoptLateRestoredValues(values: Map<String, Any?>) {
    val children = mutableListOf<Pair<LithoSaveableStateRegistry, Map<String, Any?>>>()
    synchronized(lock) {
      if (closed) return
      var adopted = false
      values.forEach { (key, value) ->
        if (key in ambiguousKeys || key in ambiguousChildKeys) return@forEach
        retainedValues[key] = value
        adopted = true
        childKeyHolders[key]
            ?.get()
            ?.takeIf { !it.isClosed() }
            ?.let { child -> childValues(key, value)?.let { children += child to it } }
      }
      if (adopted) generation++
    }
    children.forEach { (child, childValues) -> child.adoptLateRestoredValues(childValues) }
  }

  // endregion

  // region Restoring and joining the parent

  /**
   * Reads the values saved for this registry on first need: a keyed child claims its key from its
   * parent ([claimChildKey]), and a root built by [ownerBacked] claims from its owner
   * ([claimRootStateIfNeeded]). Does nothing for other registries or a closed one; a detached child
   * tries again after [attachToParent].
   */
  private fun restoreSavedStateIfNeeded() {
    val parent = parentRegistry
    if (parent == null) {
      claimRootStateIfNeeded()
      return
    }
    val storageKey = parentStorageKey ?: return
    synchronized(parentConnectionLock) {
      if (parentStateRestored || detachedFromParent || synchronized(lock) { closed }) return
      val restoredState = parent.claimChildKey(storageKey, this)
      synchronized(lock) {
        if (closed) return
        if (restoredState != null) retainedValues.putAll(restoredState)
        keysDiscardedBeforeRestore.forEach(::discardRetainedValue)
        keysDiscardedBeforeRestore.clear()
      }
      parentStateRestored = true
    }
  }

  /**
   * Claims this root's saved values from its owner on the first read. Values the tree already
   * established are kept, so a claim never overwrites live state. A closed registry never claims,
   * so a calculation finishing after release cannot take the values from the next tree.
   *
   * The claim runs under [parentConnectionLock] so a concurrent first read waits for the merge
   * instead of reading defaults. This can't deadlock with the owner: under its own lock, the owner
   * only takes registries' [lock]s, to hand over late restored values, never a
   * [parentConnectionLock]; and nothing holding a [lock] calls the owner or takes a
   * [parentConnectionLock].
   */
  private fun claimRootStateIfNeeded() {
    val claim = claimRootState ?: return
    val failures = ArrayList<RuntimeException>()
    synchronized(parentConnectionLock) {
      if (rootStateClaimed) return
      rootStateClaimed = true
      val discardedKeys =
          synchronized(lock) {
            if (closed) return
            keysDiscardedBeforeRestore.toSet().also { keysDiscardedBeforeRestore.clear() }
          }
      val claimedState = claim() ?: return
      val restored = readSnapshot(claimedState, failures, skipped = discardedKeys).orEmpty()
      synchronized(lock) {
        if (closed) return
        restored.forEach { (key, value) ->
          if (!providers.containsKey(key) && !retainedValues.containsKey(key)) {
            retainedValues[key] = value
          }
        }
      }
    }
    failures.forEach { reportSaveableStateRestoreFailure("root claim", it) }
  }

  private fun joinParentWhenShown() {
    if (!shown) {
      joinDeferred = true
      if (!shown) return
    }
    registerWithParentIfNeeded()
  }

  /**
   * Called when the content using this registry is shown, as when its tree attaches or its
   * collection row binds. A registry created to join its parent only then does so now if its hooks
   * or children asked to.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun onTreeShown() {
    if (shown) return
    shown = true
    if (joinDeferred) registerWithParentIfNeeded()
  }

  private fun registerWithParentIfNeeded() {
    val parent = parentRegistry ?: return
    val storageKey = parentStorageKey ?: return
    restoreSavedStateIfNeeded()
    synchronized(parentConnectionLock) {
      if (detachedFromParent || registeredProvider != null || parentKeyAmbiguous) return
      if (synchronized(lock) { closed }) return
      if (parentKeyReleased) {
        // This child keeps its own values over the older ones saved under the key, and registering
        // drops the parent's copy.
        parent.claimChildKey(storageKey, this)
        parentKeyReleased = false
      }
      registeredProvider = parent.registerChild(storageKey, this)
      parentKeyAmbiguous = registeredProvider == null
    }
  }

  /**
   * Stops saving this child in its parent and gives up its key, but keeps its values, while the
   * content using it is detached and may come back with its state. Until [attachToParent], this
   * child claims nothing from its parent and is not saved there, even when its hooks render, as the
   * rows of a removed list still may.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun detachFromParent() {
    val provider =
        synchronized(parentConnectionLock) {
          detachedFromParent = true
          parentKeyReleased = true
          registeredWhenDetached = registeredWhenDetached || registeredProvider != null
          registeredProvider.also { registeredProvider = null }
        }
    parentStorageKey?.let { key -> parentRegistry?.removeChild(key, this, provider) }
  }

  /**
   * Ends [detachFromParent]: this child takes its key back and is saved in its parent again, right
   * away if it was registered when detached or hooks or children use it, otherwise on first use.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun attachToParent() {
    val register =
        synchronized(parentConnectionLock) {
          if (!detachedFromParent) return
          detachedFromParent = false
          registeredWhenDetached.also { registeredWhenDetached = false } ||
              synchronized(lock) { providers.isNotEmpty() || sources != null }
        }
    if (register) registerWithParentIfNeeded()
  }

  /**
   * Sets whether this child is saved in its parent, which it is by default.
   *
   * While it isn't, the parent leaves it out of its snapshot, with no entry for its key, and keeps
   * no copy of the values this child read from it. This child still holds its key, keeps its values
   * and its own children, and can read the values restored for it. Saving it again saves its
   * current values. This claims nothing from the parent: values restored for this child stay in the
   * parent's snapshot until this child reads them.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun setSavedInParent(saved: Boolean) {
    synchronized(lock) {
      if (closed || savedInParent == saved) return
      savedInParent = saved
    }
    val parent = parentRegistry ?: return
    val storageKey = parentStorageKey ?: return
    if (!saved) {
      parent.dropValuesOfUnsavedChild(storageKey, this)
    } else if (synchronized(parentConnectionLock) { parentStateRestored }) {
      // The parent dropped its copy of the values this child read, so this child provides them.
      registerWithParentIfNeeded()
    }
  }

  private fun isSavedInParent(): Boolean = synchronized(lock) { savedInParent }

  // endregion

  // region Child keys and providers

  /**
   * Creates an independently keyed registry whose snapshot is owned by this registry.
   *
   * @param key the child's key in this registry's saved state, or `null` for a child this registry
   *   never restores or saves
   * @param joinsParentWhenShown joins this registry's saved state only once [onTreeShown] is
   *   called, for content that may render without being shown
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun createChildRegistry(
      key: String?,
      joinsParentWhenShown: Boolean = false,
  ): LithoSaveableStateRegistry = LithoSaveableStateRegistry(
      valueClassLoader = valueClassLoader,
      parentRegistry = this,
      parentStorageKey = key?.let { "$CHILD_REGISTRY_PREFIX$it" },
      joinsParentWhenShown = joinsParentWhenShown,
  )

  /**
   * Drops the values saved for the child registry created with [key], including values restored for
   * a child that was never created again. A child that already read them keeps its copy. This
   * claims nothing from the parent or owner: values this registry has not restored yet are dropped
   * when it restores them.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun discardChildState(key: String) {
    val storageKey = "$CHILD_REGISTRY_PREFIX$key"
    synchronized(parentConnectionLock) {
      val restorePending =
          (parentStorageKey != null && !parentStateRestored) ||
              (claimRootState != null && !rootStateClaimed)
      synchronized(lock) {
        if (closed) return
        discardRetainedValue(storageKey)
        if (restorePending) keysDiscardedBeforeRestore += storageKey
      }
    }
  }

  /**
   * Gives [child] the child key [key] and returns the values saved under it. If another open child
   * already holds the key, the key becomes ambiguous instead: the holder stops being saved, and
   * neither child gets any values.
   */
  private fun claimChildKey(key: String, child: LithoSaveableStateRegistry): Map<String, Any?>? {
    restoreSavedStateIfNeeded()
    synchronized(lock) {
      if (closed || key in ambiguousChildKeys) return null
      val holder = childKeyHolders[key]?.get()
      if (holder != null && holder !== child && !holder.isClosed()) {
        ambiguousChildKeys += key
        childKeyHolders.remove(key)
        providers.remove(key)
        discardRetainedValue(key)
        return null
      }
      childKeyHolders[key] = WeakReference(child)
      val restoredState = childValues(key, restoredValueLocked(key)?.value)
      if (!child.isSavedInParent()) discardRetainedValue(key)
      return restoredState
    }
  }

  /** Drops this registry's copy of [child]'s values while [child] holds [key] unsaved. */
  private fun dropValuesOfUnsavedChild(key: String, child: LithoSaveableStateRegistry) {
    synchronized(lock) {
      if (childKeyHolders[key]?.get() === child && !child.isSavedInParent()) {
        discardRetainedValue(key)
      }
    }
  }

  /** The keys held by children that [setSavedInParent] left out of this registry's snapshot. */
  @GuardedBy("lock")
  private fun unsavedChildKeys(): Set<String> {
    if (childKeyHolders.isEmpty()) return emptySet()
    return childKeyHolders.entries.mapNotNullTo(HashSet()) { (key, holder) ->
      key.takeIf { holder.get()?.isSavedInParent() == false }
    }
  }

  /**
   * Registers [child] under [key] and drops this registry's copy of its values. Returns `null`
   * instead if this registry is closed, [child] doesn't hold the key, or a provider is already
   * registered for it.
   */
  private fun registerChild(key: String, child: LithoSaveableStateRegistry): Provider? {
    restoreSavedStateIfNeeded()
    val provider = Provider(child)
    synchronized(lock) {
      if (closed || childKeyHolders[key]?.get() !== child || providers.containsKey(key)) {
        return null
      }
      providers[key] = provider
      discardRetainedValue(key)
    }
    joinParentWhenShown()
    return provider
  }

  /** Removes [child]'s [provider] and its hold on [key], if they are still current. */
  private fun removeChild(key: String, child: LithoSaveableStateRegistry, provider: Provider?) {
    synchronized(lock) {
      if (provider != null && providers[key] === provider) providers.remove(key)
      if (childKeyHolders[key]?.get() === child) childKeyHolders.remove(key)
    }
  }

  /** One registration of [child] in its parent; a later registration gets a new provider. */
  private class Provider(private val child: LithoSaveableStateRegistry) {
    /**
     * Reads the child's values. It runs outside the render of the tree that registered it,
     * sometimes inside another tree's render, as when a list releases a row while it lays out,
     * where a tracked read would count as a cross-tree read that returns a stale value or crashes.
     */
    fun savedValues(): Map<String, Any?> =
        synchronized(this) {
          StateReadRecorder.untracked(preserveTreeContext = false) { child.savedValues() }
        }
  }

  // endregion

  // region Lifecycle

  /** Clears this registry; later restored-state reads and snapshots are no-ops. */
  override fun close() {
    val provider =
        synchronized(parentConnectionLock) {
          synchronized(lock) {
            closed = true
            providers.clear()
            // Hooks that used this registry move to the one above them on their next render.
            if (sources != null) generation++
            sources = null
            retainedValues.clear()
            childKeyHolders.clear()
          }
          registeredProvider.also { registeredProvider = null }
        }
    parentStorageKey?.let { key -> parentRegistry?.removeChild(key, this, provider) }
  }

  /**
   * Whether this registry holds anything to save: a live value, a child snapshot, a retained value,
   * or an open child holding a key. One that holds nothing can be closed without losing a value:
   * values its parent restored for it that it has not read stay in the parent, for the next child
   * with its key.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun holdsState(): Boolean {
    synchronized(lock) {
      if (closed) return false
      if (providers.isNotEmpty() || hasRetainedValuesLocked()) return true
      if (childKeyHolders.values.any { it.get()?.isClosed() == false }) return true
      if (sources == null) return false
    }
    return liveEntries().isNotEmpty()
  }

  private fun isClosed(): Boolean = synchronized(lock) { closed }

  // endregion

  // region Saved-state format

  /**
   * Reads a copy of [snapshot] into plain values with [valueClassLoader], or returns `null` if its
   * keys can't be read. A value key gets its saved form, and a child key gets a map read the same
   * way. Only child keys are read further, so a value a saver saved as a Bundle stays as saved.
   * Keys in [skipped] are left out, and so is a key that can't be read, with its failure added to
   * [failures].
   */
  @Suppress("DEPRECATION")
  private fun readSnapshot(
      snapshot: Bundle,
      failures: MutableList<RuntimeException>,
      skipped: Set<String> = emptySet(),
  ): HashMap<String, Any?>? {
    val restored = Bundle(snapshot)
    val keys =
        try {
          restored.classLoader = valueClassLoader
          restored.keySet().toList()
        } catch (exception: RuntimeException) {
          failures += exception
          return null
        }
    val values = HashMap<String, Any?>()
    keys.forEach { key ->
      if (key in skipped) return@forEach
      try {
        val entry = Bundle(restored.get(key) as Bundle)
        entry.classLoader = valueClassLoader
        val saved = entry.get(VALUE_KEY)
        if (isChildKey(key)) {
          readSnapshot(saved as Bundle, failures)?.let { values[key] = it }
        } else {
          values[key] = saved
        }
      } catch (exception: RuntimeException) {
        failures += exception
      }
    }
    return values
  }

  /** Writes [values] as a snapshot, the form [readSnapshot] reads. */
  private fun toBundle(values: Map<String, Any?>): Bundle {
    val snapshot = Bundle()
    snapshot.classLoader = valueClassLoader
    values.forEach { (key, value) ->
      val saved = childValues(key, value)?.let(::toBundle) ?: value
      snapshot.putBundle(key, valueBundle(key, saved))
    }
    return snapshot
  }

  /** The values of the child saved under [key], if [key] is a child key. */
  @Suppress("UNCHECKED_CAST")
  private fun childValues(key: String, value: Any?): Map<String, Any?>? =
      if (isChildKey(key)) value as? Map<String, Any?> else null

  private fun valueBundle(key: String, value: Any?): Bundle {
    try {
      val bundle = bundleOf(VALUE_KEY to value)
      bundle.classLoader = valueClassLoader
      return bundle
    } catch (exception: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Saveable-state provider for key '$key' returned unsupported value type " +
              "'${value?.javaClass?.name ?: "null"}'. Provide a Saver that produces a " +
              "Bundle-compatible value.",
          exception,
      )
    }
  }

  // endregion

  internal companion object {
    private const val CHILD_REGISTRY_PREFIX: String = "litho-child-registry:"
    private const val VALUE_KEY: String = "value"
    private const val DUPLICATE_KEY_CATEGORY: String = "LithoSaveableState:DuplicateKey"

    /** Hook keys start with their versioned prefix, so only child keys start with this one. */
    private fun isChildKey(key: String): Boolean = key.startsWith(CHILD_REGISTRY_PREFIX)

    /**
     * Creates a root ComponentTree's registry whose saved values come from [claimState] when a
     * component first reads saveable state.
     */
    fun ownerBacked(
        valueClassLoader: ClassLoader?,
        claimState: () -> Bundle?,
    ): LithoSaveableStateRegistry =
        LithoSaveableStateRegistry(null, valueClassLoader, null, null, claimState)
  }
}

/** A value restored for a key, which may be `null`; no value at all is a `null` result. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class RestoredSaveableState internal constructor(val value: Any?)

/**
 * Reports saved values that could not be read; the affected values start from their initializers.
 */
internal fun reportSaveableStateRestoreFailure(source: String, exception: RuntimeException) {
  DebugInfoReporter.report(category = "LithoSaveableState:RestoreFailed") {
    this["source"] = source
    this["exception"] = exception.javaClass.name
    this["message"] = exception.message
  }
}
