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

import android.app.Activity
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.state.StateReadRecorder
import java.lang.ref.WeakReference

/**
 * A root ComponentTree's claim on saved state under the [SavedStateRegistryOwner] it was created
 * with.
 *
 * Values are saved in the narrower of the two owners: the lifecycle owner when it can save state
 * and isn't an Activity, otherwise the given store. An Activity outlives its Fragments, so saving a
 * Fragment's values there would bring a popped screen's values back. For a Fragment tree whose
 * lifecycle owner is `viewLifecycleOwner`, values go to the view owner's registry: AndroidX saves
 * it when the view goes onto the back stack and restores it into the next view, also after process
 * death, and drops it when the Fragment is popped.
 *
 * The tree's identity is its saveable-state key when it has one, otherwise its root component's
 * class and manual key, read when a component first reads saveable state. The values live in
 * [registry] for the tree's lifetime and are saved under that identity by the owner's
 * [ComponentTreeSaveableStateOwner].
 */
internal class ComponentTreeSaveableStateBinding(
    store: SavedStateRegistryOwner,
    lifecycleOwner: LifecycleOwner?,
    valueClassLoader: ClassLoader,
    internal val keyed: Boolean,
    private val rootIdentity: () -> String?,
    private val hasMounted: () -> Boolean,
    private val renderRestoredValues: () -> Unit,
) {
  private val owner: ComponentTreeSaveableStateOwner
  private val storeRef: WeakReference<SavedStateRegistryOwner>

  /** Both owners; the host is going down when either one is. */
  private val hostOwnerRefs = listOfNotNull(lifecycleOwner, store).distinct().map(::WeakReference)

  /**
   * Trees that replace each other share a lineage, so only they can hand values over: trees of the
   * same lifecycle owner. An explicit key already names the tree, so keyed trees share one lineage.
   */
  internal val lineage: Any = if (keyed) KEYED_LINEAGE else lifecycleOwner ?: store

  init {
    val savingStore =
        if (lifecycleOwner is SavedStateRegistryOwner && lifecycleOwner !is Activity) {
          lifecycleOwner
        } else {
          store
        }
    owner = ComponentTreeSaveableStateOwner.of(savingStore, valueClassLoader)
    storeRef = WeakReference(savingStore)
  }

  internal val registry: LithoSaveableStateRegistry =
      LithoSaveableStateRegistry.ownerBacked(valueClassLoader) { claim() }

  @Volatile
  internal var identity: String? = null
    private set

  @Volatile
  internal var released: Boolean = false
    private set

  @Volatile
  internal var attached: Boolean = false
    private set

  /**
   * Set when this tree claimed before the owner took its restored values from Android, as when the
   * tree is built and rendered off the main thread before any main-thread call.
   */
  @Volatile internal var claimedBeforeRestore: Boolean = false

  /** A tree attached to a window may have shown its values, so late restored values skip it. */
  @Volatile private var everAttached: Boolean = false

  private fun claim(): Bundle? {
    val claimedIdentity = rootIdentity() ?: return null
    identity = claimedIdentity
    return owner.claim(this, claimedIdentity)
  }

  /**
   * Snapshots this tree's values for the owner to save, park or hand to a newer tree. These run
   * outside this tree's render, sometimes inside another tree's render or mount, as for a takeover,
   * where tracked reads would count as cross-tree reads that return stale values or crash.
   */
  internal fun snapshotValues(): Bundle =
      StateReadRecorder.untracked(preserveTreeContext = false) {
        registry.snapshotForOwner()
      }

  /**
   * Takes restored values that arrived after this tree's first render, and returns whether it did.
   * Nothing has been shown before the tree is attached and mounted, so until then the values
   * replace the initial ones and [renderLateRestore] renders them; afterwards they are dropped and
   * the tree keeps its `useState` behaviour. Called under the owner's lock, so the tree still holds
   * its identity when it takes them.
   */
  internal fun adoptLateRestore(values: Bundle): Boolean {
    if (released || mayHaveShownValues()) return false
    registry.adoptLateRestoredValues(values)
    return true
  }

  /** Whether this tree was attached or mounted, so the user may have seen or changed its values. */
  internal fun mayHaveShownValues(): Boolean = everAttached || hasMounted()

  /** Renders again with the values taken by [adoptLateRestore], before the first mount. */
  @MainThread
  internal fun renderLateRestore() {
    renderRestoredValues()
  }

  /** Connects to Android saved state when the store allows it; safe to call repeatedly. */
  @MainThread
  fun initializeOnMain() {
    storeRef.get()?.let(owner::initializeOnMain)
  }

  /**
   * Takes back this tree's own values if they were parked when its view went away, and holds its
   * identity unless another tree of it is on screen.
   */
  @MainThread
  fun onAttach() {
    attached = true
    initializeOnMain()
    owner.attach(this)
    // Set after setup: restored values delivered during this first attach still precede the mount.
    everAttached = true
  }

  /**
   * Parks this tree's values when its host is going down, as on back navigation. A keyed tree
   * always parks: it may be dropped without [onRelease], as when a list reuses its LithoView for
   * another item, and parking keeps its values for the next tree with the key even if this tree is
   * garbage collected. [onAttach] takes them back if this tree comes back first.
   */
  @MainThread
  fun onDetach() {
    attached = false
    if (keyed || isTearingDown()) {
      owner.park(this)
    }
  }

  /**
   * Parks this tree's values when its host is going down, or always for a keyed tree, so the next
   * tree can take them; otherwise drops them. The registry is inert afterwards. For example, a tree
   * released in `onDestroyView` during back navigation is parked, while an unkeyed tree removed
   * from a visible screen is dropped.
   */
  @MainThread
  fun onRelease() {
    // Set before park or end so a claim racing on another thread is rejected under the owner lock.
    released = true
    attached = false
    // A keyed tree hands its values to the next tree with its key, even while its host is active.
    if (keyed || isTearingDown()) owner.park(this) else owner.end(this)
    registry.close()
  }

  /**
   * The tree's host is going down, as on back-stack navigation, recreation, or finish: either owner
   * is below STARTED. For example, a Fragment store with the Activity as lifecycle owner goes down
   * when the Fragment goes onto the back stack, while the Activity stays resumed.
   */
  private fun isTearingDown(): Boolean = hostOwnerRefs.any { ref ->
    val hostOwner = ref.get() ?: return@any true
    !hostOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
  }

  private companion object {
    val KEYED_LINEAGE: Any = Any()
  }
}
