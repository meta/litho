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

package com.facebook.litho.widget.collection

import androidx.annotation.GuardedBy
import androidx.annotation.RestrictTo
import com.facebook.litho.LithoSaveableStateRegistry
import java.util.IdentityHashMap

/**
 * The saveable-state registries of a collection's rows.
 *
 * The collection has a registry of its own in the tree's registry, keyed by the collection's
 * component identity, so two lists with the same row ids never compete for a key. Under it, each
 * row key has one registry, shared by every item built for that key: the items a speculative
 * changeset builds for measurement, which may never be committed, read the same registry as the
 * committed row.
 *
 * A row's registry lives as long as the collection, so a row that leaves, scrolled away or removed
 * from the data, keeps its values in memory and gets them back when it is shown again. The
 * collection saves a row while one of its items is bound to a view, and also while none has been
 * since the row's registry was created, as for rows rendered ahead of the viewport or not rendered
 * since a restore. Once its last item is unbound in a mounted collection, the row is left out of
 * the collection's saved state until it is bound again. A registry that holds nothing is closed
 * once its row is neither in the data nor bound, so rows without saveable state leave nothing
 * behind.
 *
 * Rows of one item list with the same key can't be told apart, so from then on every row with that
 * key gets a registry of its own that is not saved, and the values saved for the key are dropped.
 *
 * Items are built and rendered on background threads, and bound and unbound, and their data
 * committed, on the main thread.
 *
 * @param collectionKey the collection's component identity, or `null` if it has none to persist, in
 *   which case its rows are not saved
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class CollectionSaveableStateRegistries(private val collectionKey: String? = null) {

  private val lock = Any()

  @GuardedBy("lock") private var parent: LithoSaveableStateRegistry? = null

  @GuardedBy("lock") private var detached = false

  @GuardedBy("lock") private var collectionRegistry: LithoSaveableStateRegistry? = null

  @GuardedBy("lock") private val rowRegistries = HashMap<String, LithoSaveableStateRegistry>()

  @GuardedBy("lock") private val rowKeys = IdentityHashMap<LithoSaveableStateRegistry, String>()

  @GuardedBy("lock") private val sharedKeys = HashSet<String>()

  /** How many items bound to a view each row registry has, for the registries that have any. */
  @GuardedBy("lock")
  private val boundItemCounts = IdentityHashMap<LithoSaveableStateRegistry, Int>()

  /** The row keys of the committed items, or `null` before the first commit that has items. */
  @GuardedBy("lock") private var committedKeys: Set<String>? = null

  /** The row keys of the item list built last, which may commit next. */
  @GuardedBy("lock") private var builtKeys: Set<String> = emptySet()

  /** Whether the views being recycled are recycled because the collection's view is unmounted. */
  @GuardedBy("lock") private var unmounting = false

  /**
   * Returns the registry for the row with [key], creating it the first time. Every item built for
   * the row, including copies built only for measurement, gets the same registry, so they share one
   * set of values.
   *
   * Every row gets a registry, even one that never uses `useSaveableState`: the hook only runs
   * while the row renders, and finds its registry in the row's tree props then. An empty registry
   * claims nothing from its parent and saves nothing, and is closed once its row is gone. A row
   * joins the collection's saved state when one of its items is first bound; until then the
   * collection keeps the row's restored values.
   *
   * A row without a key, or with a key two rows share, gets a registry with no key. Without one,
   * the row would write into the tree's registry it inherits and clash with every row built from
   * the same component. A registry with no key never reads from or writes to its parent, so that
   * row behaves like `useState`.
   */
  internal fun registryFor(
      parent: LithoSaveableStateRegistry,
      key: String?,
  ): LithoSaveableStateRegistry =
      synchronized(lock) {
        val collection = collectionRegistryLocked(parent)
        if (key == null || key in sharedKeys) {
          collection.createChildRegistry(null, joinsParentWhenShown = true)
        } else {
          rowRegistries.getOrPut(key) {
            collection.createChildRegistry(key, joinsParentWhenShown = true).also { registry ->
              rowKeys[registry] = key
            }
          }
        }
      }

  /**
   * Reads the row keys of an item list the collection built, before any of its items renders.
   * Returns them, for [onCommit] if the list commits.
   *
   * Row ids should be unique within a list, but nothing enforces it: the collection doesn't check
   * for duplicates, and the legacy one only logs them in debug builds. Rows sharing a key would
   * share one registry and show each other's values. So a key two rows have stops being saved:
   * every row with it gets a registry of its own, and the value saved for the key is dropped.
   */
  internal fun onItemsBuilt(
      parent: LithoSaveableStateRegistry,
      items: List<CollectionItem<*>>,
  ): Set<String> {
    val keys = HashSet<String>()
    val keysOfSeveralRows = HashSet<String>()
    items.forEach { item ->
      val key = (item as? LithoCollectionItem)?.saveableStateKey ?: return@forEach
      if (!keys.add(key)) keysOfSeveralRows += key
    }
    synchronized(lock) {
      builtKeys = keys
      if (keysOfSeveralRows.isEmpty()) return keys
      val collection = collectionRegistryLocked(parent)
      keysOfSeveralRows.forEach { key ->
        if (sharedKeys.add(key)) {
          // As when two child registries read one key: the row holding it stops being saved.
          rowRegistries[key]?.let(::removeRowLocked)
          collection.discardChildState(key)
        }
      }
    }
    return keys
  }

  /**
   * Takes the row keys of the item list the collection committed, and closes the registries that
   * hold nothing for rows not in it, so rows without saveable state don't keep a registry after
   * they leave the data. A registry with values stays: a row removed and added back gets its values
   * again.
   */
  internal fun onCommit(keys: Set<String>) {
    synchronized(lock) {
      committedKeys = keys
      rowRegistries.values.toList().forEach(::closeIfUnusedLocked)
    }
  }

  /**
   * Saves the row of [registry] again, now that one of its items is bound to a view. One row can
   * have several items bound at once, as when an item is rebuilt while the old one is still on
   * screen, so bound items are counted.
   */
  internal fun onItemBound(registry: LithoSaveableStateRegistry) {
    synchronized(lock) {
      val count = boundItemCounts[registry] ?: 0
      boundItemCounts[registry] = count + 1
      if (count == 0) registry.setSavedInParent(true)
    }
  }

  /**
   * Leaves the row of [registry] out of the saved state once none of its items is bound, unless the
   * collection's view is being unmounted. The row keeps its values in memory and is saved again
   * when it is bound again, so a row that scrolled away comes back with its value but isn't saved
   * while it's away.
   */
  internal fun onItemUnbound(registry: LithoSaveableStateRegistry) {
    synchronized(lock) {
      val count = boundItemCounts[registry] ?: return
      if (count > 1) {
        boundItemCounts[registry] = count - 1
        return
      }
      boundItemCounts.remove(registry)
      if (!unmounting) registry.setSavedInParent(false)
      closeIfUnusedLocked(registry)
    }
  }

  /**
   * Runs [unmount], which recycles the view of every bound item because the collection's view is
   * unmounted, as when incremental mount takes it off screen. The collection is still in the tree,
   * so its rows stay saved, until they are bound again and recycled in a mounted view. Without
   * this, every row on screen would be left out of a save taken while the list is off screen.
   */
  internal fun keepRowsSavedWhile(unmount: () -> Unit) {
    synchronized(lock) { unmounting = true }
    try {
      unmount()
    } finally {
      synchronized(lock) { unmounting = false }
    }
  }

  /**
   * Saves the rows in the tree's registry again once the collection is shown, as after a tree
   * replacement that kept its state, or when the outer row holding a nested list is bound again.
   */
  internal fun attach() {
    val registry =
        synchronized(lock) {
          detached = false
          collectionRegistry
        }
    registry?.attachToParent()
  }

  /**
   * Stops saving the rows while the collection is not shown, keeping their values. It can come back
   * with these same rows, in a replacement tree or when the row holding it is bound again.
   *
   * Its key in the tree's registry is freed, so a new collection in its place starts fresh instead
   * of clashing with it, and a removed list's values don't come back after a restore. If the
   * collection leaves before its registry exists, the registry is created detached.
   */
  internal fun detach() {
    val registry =
        synchronized(lock) {
          detached = true
          collectionRegistry
        }
    registry?.detachFromParent()
  }

  /**
   * Closes [registry] if it holds nothing and its row is neither bound, committed, nor in the item
   * list built last. An uncommitted item that already took it then behaves like `useState`.
   */
  @GuardedBy("lock")
  private fun closeIfUnusedLocked(registry: LithoSaveableStateRegistry) {
    val committed = committedKeys ?: return
    val key = rowKeys[registry] ?: return
    if (key in committed || key in builtKeys || registry in boundItemCounts) return
    if (!registry.holdsState()) removeRowLocked(registry)
  }

  @GuardedBy("lock")
  private fun removeRowLocked(registry: LithoSaveableStateRegistry) {
    rowKeys.remove(registry)?.let(rowRegistries::remove)
    boundItemCounts.remove(registry)
    registry.close()
  }

  /**
   * Returns the collection's registry in [parent], creating it on first use. It is keyed by the
   * collection's identity, so two lists with the same row ids, or a list replaced by another with
   * the same ids, never share row keys.
   */
  @GuardedBy("lock")
  private fun collectionRegistryLocked(
      parent: LithoSaveableStateRegistry,
  ): LithoSaveableStateRegistry {
    val current = collectionRegistry
    if (current != null && this.parent === parent) return current
    // The collection's state moved to a tree with another registry, so its rows start over there.
    current?.close()
    rowRegistries.values.forEach(LithoSaveableStateRegistry::close)
    rowRegistries.clear()
    rowKeys.clear()
    boundItemCounts.clear()
    this.parent = parent
    return parent.createChildRegistry(collectionKey).also { registry ->
      collectionRegistry = registry
      // A layout still in flight when the collection left the tree builds rows after detach().
      if (detached) registry.detachFromParent()
    }
  }
}
