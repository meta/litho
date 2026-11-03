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

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.VerticalScroll
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalLithoApi::class)
@SuppressLint("KComponentSyncUpdateDetector")
@RunWith(LithoTestRunner::class)
class ComponentTreeSaveableStateOwnerTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `root tree restores after process recreation`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    render(createTree(firstOwner, FirstStateComponent(first::set)))
    first.get().updateSync(7)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restored = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, FirstStateComponent(restored::set)))

    assertThat(restored.get().value).isEqualTo(7)
    restoredOwner.destroy()
  }

  @Test
  fun `roots of different classes restore regardless of creation order`() {
    val firstOwner = TestSavedStateOwner()
    val header = AtomicReference<State<Int>>()
    val body = AtomicReference<State<Int>>()
    render(createTree(firstOwner, FirstStateComponent(header::set)))
    render(createTree(firstOwner, SecondStateComponent(body::set)))
    header.get().updateSync(3)
    body.get().updateSync(8)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restoredBody = AtomicReference<State<Int>>()
    val restoredHeader = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, SecondStateComponent(restoredBody::set)))
    render(createTree(restoredOwner, FirstStateComponent(restoredHeader::set)))

    assertThat(restoredHeader.get().value).isEqualTo(3)
    assertThat(restoredBody.get().value).isEqualTo(8)
    restoredOwner.destroy()
  }

  @Test
  fun `a conditional root does not shift the others`() {
    val firstOwner = TestSavedStateOwner()
    val body = AtomicReference<State<Int>>()
    render(createTree(firstOwner, SecondStateComponent(body::set)))
    body.get().updateSync(8)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val insertedHeader = AtomicReference<State<Int>>()
    val restoredBody = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, FirstStateComponent(insertedHeader::set)))
    render(createTree(restoredOwner, SecondStateComponent(restoredBody::set)))

    assertThat(insertedHeader.get().value).isEqualTo(0)
    assertThat(restoredBody.get().value).isEqualTo(8)
    restoredOwner.destroy()
  }

  @Test
  fun `coexisting roots of the same class are not saved`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    render(createTree(firstOwner, FirstStateComponent(first::set)))
    render(createTree(firstOwner, FirstStateComponent(second::set)))
    first.get().updateSync(3)
    second.get().updateSync(8)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    assertThat(lithoSavedState(savedState)).isNotNull()
    assertThat(savedRootCount(savedState)).isZero()
  }

  @Test
  fun `a tree replacing a released tree on a visible screen starts fresh and is saved`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val firstView = render(createTree(owner, FirstStateComponent(first::set)))
    first.get().updateSync(5)

    firstView.release()
    val replacement = AtomicReference<State<Int>>()
    render(createTree(owner, FirstStateComponent(replacement::set)))
    assertThat(replacement.get().value).isEqualTo(0)
    replacement.get().updateSync(6)
    val savedState = owner.save()
    owner.destroy()

    val restored = AtomicReference<State<Int>>()
    render(createTree(TestSavedStateOwner(savedState), FirstStateComponent(restored::set)))
    assertThat(restored.get().value).isEqualTo(6)
  }

  @Test
  fun `trees of one class shown together are not saved whichever rendered first`() {
    val owner = TestSavedStateOwner()
    val offscreen = createTree(owner, FirstStateComponent {})
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    offscreen.setRootAndSizeSpecSync(FirstStateComponent {}, sizeSpec, sizeSpec)
    render(createTree(owner, FirstStateComponent {}))

    render(offscreen)

    assertThat(savedRootCount(owner.save())).isZero()
    owner.destroy()
  }

  @Test
  fun `a measure-only tree does not switch saving off for the screen tree`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    render(createTree(firstOwner, FirstStateComponent(first::set)))
    first.get().updateSync(7)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val owner = TestSavedStateOwner(savedState)
    val measured = AtomicReference<State<Int>>()
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    createTree(owner, FirstStateComponent(measured::set))
        .setRootAndSizeSpecSync(FirstStateComponent(measured::set), sizeSpec, sizeSpec)
    val screen = AtomicReference<State<Int>>()
    render(createTree(owner, FirstStateComponent(screen::set)))
    screen.get().updateSync(9)
    val nextState = owner.save()
    owner.destroy()

    val restored = AtomicReference<State<Int>>()
    render(createTree(TestSavedStateOwner(nextState), FirstStateComponent(restored::set)))
    assertThat(measured.get().value).isEqualTo(7)
    assertThat(screen.get().value).isEqualTo(9)
    assertThat(restored.get().value).isEqualTo(9)
  }

  /**
   * ```text
   * Host                       Litho
   * ----                       -----
   * Show tree, user sets 9
   * Navigate away (stopped)
   * Remove view, no release    Park 9 for the next tree
   * Navigate back
   * Build a new tree           Restore 9
   * ```
   */
  @Test
  fun `a tree rebuilt after teardown restores without release`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    var view = render(createTree(owner, FirstStateComponent(first::set)))
    first.get().updateSync(9)

    repeat(3) {
      owner.moveTo(Lifecycle.State.CREATED)
      view.detachFromWindow()
      val next = AtomicReference<State<Int>>()
      view = render(createTree(owner, FirstStateComponent(next::set)))
      owner.moveTo(Lifecycle.State.RESUMED)

      assertThat(next.get().value).isEqualTo(9)
    }
    assertThat(savedRootCount(owner.save())).isEqualTo(1)
    owner.destroy()
  }

  /**
   * ```
   * Step                                          Expected
   * ----                                          --------
   * Fragment 1 shows a tree, user sets 7
   * Navigate forward: host stopped, view detached Values parked
   * Process death while on the next screen        The parked 7 is saved
   * Relaunch, Back: Fragment 1 rebuilds its tree  Restores 7
   * ```
   */
  @Test
  fun `values parked on the back stack survive process death`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val view = render(createTree(owner, FirstStateComponent(first::set)))
    first.get().updateSync(7)

    owner.moveTo(Lifecycle.State.CREATED)
    view.detachFromWindow()
    val savedState = owner.save()
    owner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restored = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, FirstStateComponent(restored::set)))

    assertThat(restored.get().value).isEqualTo(7)
    restoredOwner.destroy()
  }

  @Test
  fun `a tree created after its data arrives restores after process death`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    render(createTree(firstOwner, FirstStateComponent(first::set)))
    first.get().updateSync(6)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    // The owner is already resumed, as when a tree is built once its data arrives.
    val restoredOwner = TestSavedStateOwner(savedState)
    val restored = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, FirstStateComponent(restored::set)))

    assertThat(restored.get().value).isEqualTo(6)
    restoredOwner.destroy()
  }

  @Test
  fun `a manual root key separates roots of the same class`() {
    val firstOwner = TestSavedStateOwner()
    val left = AtomicReference<State<Int>>()
    val right = AtomicReference<State<Int>>()
    render(createTree(firstOwner, key("left") { FirstStateComponent(left::set) }))
    render(createTree(firstOwner, key("right") { FirstStateComponent(right::set) }))
    left.get().updateSync(1)
    right.get().updateSync(2)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restoredRight = AtomicReference<State<Int>>()
    val restoredLeft = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, key("right") { FirstStateComponent(restoredRight::set) }))
    render(createTree(restoredOwner, key("left") { FirstStateComponent(restoredLeft::set) }))

    assertThat(restoredLeft.get().value).isEqualTo(1)
    assertThat(restoredRight.get().value).isEqualTo(2)
    restoredOwner.destroy()
  }

  @Test
  fun `a nested tree restores through its owner-backed root`() {
    val firstOwner = TestSavedStateOwner()
    val firstRootRegistry = AtomicReference<LithoSaveableStateRegistry>()
    render(createTree(firstOwner, RegistryCapturingComponent(firstRootRegistry::set)))
    val nested = AtomicReference<State<Int>>()
    render(createNestedTree(firstRootRegistry.get(), nested::set))
    nested.get().updateSync(4)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restoredRootRegistry = AtomicReference<LithoSaveableStateRegistry>()
    render(createTree(restoredOwner, RegistryCapturingComponent(restoredRootRegistry::set)))
    val restoredNested = AtomicReference<State<Int>>()
    render(createNestedTree(restoredRootRegistry.get(), restoredNested::set))

    assertThat(restoredNested.get().value).isEqualTo(4)
    restoredOwner.destroy()
  }

  @Test
  fun `unclaimed restored values are carried through one save and then dropped`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    render(createTree(firstOwner, FirstStateComponent(first::set)))
    first.get().updateSync(2)
    val firstSave = firstOwner.save()
    firstOwner.destroy()

    // Restoring consumes keys from the Bundle passed in, so count each save before reusing it.
    val secondOwner = TestSavedStateOwner(firstSave)
    render(createTree(secondOwner, SecondStateComponent {}))
    val secondSave = secondOwner.save()
    val secondSaveRoots = savedRootCount(secondSave)
    secondOwner.destroy()
    val thirdOwner = TestSavedStateOwner(secondSave)
    render(createTree(thirdOwner, SecondStateComponent {}))
    val thirdSaveRoots = savedRootCount(thirdOwner.save())
    thirdOwner.destroy()

    assertThat(secondSaveRoots).isEqualTo(2)
    assertThat(thirdSaveRoots).isEqualTo(1)
  }

  @Test
  fun `a read after release does not take values from the next tree`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    render(createTree(firstOwner, LateStateComponent(readsState = true, onState = first::set)))
    first.get().updateSync(5)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val releasedRegistry = AtomicReference<LithoSaveableStateRegistry>()
    val releasedTree = createTree(
        restoredOwner,
        LateStateComponent(readsState = false, onRegistry = releasedRegistry::set),
    )
    render(releasedTree)
    releasedTree.release()
    // A calculation that was still running when the tree was released reads afterwards.
    assertThat(releasedRegistry.get().restoredValue("late")).isNull()

    val restored = AtomicReference<State<Int>>()
    render(
        createTree(restoredOwner, LateStateComponent(readsState = true, onState = restored::set)),
    )

    assertThat(restored.get().value).isEqualTo(5)
    restoredOwner.destroy()
  }

  @Test
  fun `owner destruction unregisters the Litho provider`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val view = render(createTree(owner, FirstStateComponent(first::set)))
    first.get().updateSync(1)

    owner.destroy()

    assertThat(lithoSavedState(owner.save())).isNull()
    view.release()
  }

  @Test
  fun `a keyed tree released while its screen is active hands its values to the next one`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val firstTree = createKeyedTree(owner, "feed", FirstStateComponent(first::set))
    render(firstTree)
    first.get().updateSync(4)

    firstTree.release()
    val refreshed = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, "feed", FirstStateComponent(refreshed::set)))

    assertThat(refreshed.get().value).isEqualTo(4)
    owner.destroy()
  }

  /**
   * ```text
   * Show keyed tree A and set it to 1
   * Detach A without releasing it      A parks 1
   * Set A to 2 while detached
   * Process death                      The key restores 2, not the parked 1
   * ```
   */
  @Test
  fun `a parked tree that was not released saves its current values`() {
    val firstOwner = TestSavedStateOwner()
    val state = AtomicReference<State<Int>>()
    val view = render(createKeyedTree(firstOwner, "feed", FirstStateComponent(state::set)))
    state.get().updateSync(1)

    view.detachFromWindow()
    state.get().updateSync(2)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restored = AtomicReference<State<Int>>()
    render(createKeyedTree(restoredOwner, "feed", FirstStateComponent(restored::set)))
    assertThat(restored.get().value).isEqualTo(2)
    restoredOwner.destroy()
  }

  /**
   * ```
   * Step                                   Expected
   * ----                                   --------
   * Show keyed tree A, user sets 3
   * Fade in keyed tree B, A still attached B takes the key with 3, warning logged
   * Set B to 5, A to 9                     A keeps working
   * Process death                          The key restores 5, not A's 9
   * ```
   */
  @Test
  fun `a keyed tree takes its key over from a live tree, which is no longer saved`() {
    ShadowLog.clear()
    val firstOwner = TestSavedStateOwner()
    val old = AtomicReference<State<Int>>()
    render(createKeyedTree(firstOwner, "feed", FirstStateComponent(old::set)))
    old.get().updateSync(3)

    val replacement = AtomicReference<State<Int>>()
    render(createKeyedTree(firstOwner, "feed", FirstStateComponent(replacement::set)))
    assertThat(replacement.get().value).isEqualTo(3)
    assertThat(keyTakeoverWarnings()).isEqualTo(1)
    replacement.get().updateSync(5)
    old.get().updateSync(9)
    assertThat(old.get().value).isEqualTo(9)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restored = AtomicReference<State<Int>>()
    render(createKeyedTree(restoredOwner, "feed", FirstStateComponent(restored::set)))

    assertThat(restored.get().value).isEqualTo(5)
    restoredOwner.destroy()
  }

  /**
   * The takeover snapshot also runs nested trees' providers, whose state belongs to the nested
   * trees. Strict cross-tree read checking must not fail the new tree's render.
   */
  @Test
  fun `a tree parked during another tree's render does not record a cross-tree read`() {
    val config = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        config.copy(crossTreeStateReadPolicy = ComponentsConfiguration.LogicViolationPolicy.CRASH)
    try {
      val owner = TestSavedStateOwner()
      val first = AtomicReference<State<Int>>()
      val view = render(createKeyedTree(owner, "row", FirstStateComponent(first::set)))
      first.get().updateSync(4)
      val otherTree = createTree(owner, SecondStateComponent {})

      StateReadRecorder.record(otherTree.id) { view.detachFromWindow() }
      val next = AtomicReference<State<Int>>()
      render(createKeyedTree(owner, "row", FirstStateComponent(next::set)))

      assertThat(next.get().value).isEqualTo(4)
      owner.destroy()
    } finally {
      ComponentsConfiguration.defaultInstance = config
    }
  }

  @Test
  fun `a keyed takeover hands over nested tree values without a cross-tree read`() {
    val config = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        config.copy(crossTreeStateReadPolicy = ComponentsConfiguration.LogicViolationPolicy.CRASH)
    try {
      val owner = TestSavedStateOwner()
      val oldRegistry = AtomicReference<LithoSaveableStateRegistry>()
      render(
          createKeyedTree(
              owner,
              "feed",
              LateStateComponent(readsState = true, onRegistry = oldRegistry::set),
          ),
      )
      val nested = AtomicReference<State<Int>>()
      render(createNestedTree(oldRegistry.get(), nested::set))
      nested.get().updateSync(4)

      val newRegistry = AtomicReference<LithoSaveableStateRegistry>()
      render(
          createKeyedTree(
              owner,
              "feed",
              LateStateComponent(readsState = true, onRegistry = newRegistry::set),
          ),
      )
      val handedOver = AtomicReference<State<Int>>()
      render(createNestedTree(newRegistry.get(), handedOver::set))

      assertThat(handedOver.get().value).isEqualTo(4)
      owner.destroy()
    } finally {
      ComponentsConfiguration.defaultInstance = config
    }
  }

  @Test
  fun `a keyed tree set on the same LithoView takes the key without a warning`() {
    ShadowLog.clear()
    val owner = TestSavedStateOwner()
    val old = AtomicReference<State<Int>>()
    val view = render(createKeyedTree(owner, "feed", FirstStateComponent(old::set)))
    old.get().updateSync(4)

    // The old tree is never released, as when a host swaps trees on one view.
    val replacement = AtomicReference<State<Int>>()
    view
        .useComponentTree(createKeyedTree(owner, "feed", FirstStateComponent(replacement::set)))
        .measure()
        .layout()

    assertThat(replacement.get().value).isEqualTo(4)
    assertThat(keyTakeoverWarnings()).isZero()
    owner.destroy()
  }

  @Test
  fun `an unkeyed tree set on the same LithoView starts fresh and is saved`() {
    val owner = TestSavedStateOwner()
    val old = AtomicReference<State<Int>>()
    val view = render(createTree(owner, FirstStateComponent(old::set)))
    old.get().updateSync(4)

    val replacement = AtomicReference<State<Int>>()
    view
        .useComponentTree(createTree(owner, FirstStateComponent(replacement::set)))
        .measure()
        .layout()
    assertThat(replacement.get().value).isEqualTo(0)
    replacement.get().updateSync(6)
    val savedState = owner.save()
    owner.destroy()

    val restored = AtomicReference<State<Int>>()
    render(createTree(TestSavedStateOwner(savedState), FirstStateComponent(restored::set)))
    assertThat(restored.get().value).isEqualTo(6)
  }

  @Test
  fun `keyed rows rebuilt for the same item keep their own values`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val second = AtomicReference<State<Int>>()
    var firstRow = createKeyedTree(owner, "row-1", FirstStateComponent(first::set))
    var secondRow = createKeyedTree(owner, "row-2", FirstStateComponent(second::set))
    render(firstRow)
    render(secondRow)
    first.get().updateSync(1)
    second.get().updateSync(2)

    secondRow.release()
    firstRow.release()
    secondRow = createKeyedTree(owner, "row-2", FirstStateComponent(second::set))
    firstRow = createKeyedTree(owner, "row-1", FirstStateComponent(first::set))
    render(secondRow)
    render(firstRow)

    assertThat(first.get().value).isEqualTo(1)
    assertThat(second.get().value).isEqualTo(2)
    owner.destroy()
  }

  @Test
  fun `a keyed tree swapped off its view without release keeps its values after collection`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val view = render(createKeyedTree(owner, "row-1", FirstStateComponent(first::set)))
    first.get().updateSync(5)
    // The State points back to its tree, so let go of it for the tree to be collected.
    first.set(null)
    val oldTree = WeakReference(checkNotNull(view.lithoView.componentTree))

    view.useComponentTree(createKeyedTree(owner, "row-2", FirstStateComponent {}))
    view.measure().layout()
    awaitCollected(oldTree)
    val next = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, "row-1", FirstStateComponent(next::set)))

    assertThat(next.get().value).isEqualTo(5)
    owner.destroy()
  }

  @Test
  fun `a keyed tree attached again after a newer tree took its key leaves the key to it`() {
    val owner = TestSavedStateOwner()
    val old = AtomicReference<State<Int>>()
    val oldView = render(createKeyedTree(owner, "row", FirstStateComponent(old::set)))
    old.get().updateSync(5)
    oldView.detachFromWindow()
    val newer = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, "row", FirstStateComponent(newer::set)))

    oldView.attachToWindow()
    newer.get().updateSync(8)
    val savedState = owner.save()
    owner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restored = AtomicReference<State<Int>>()
    render(createKeyedTree(restoredOwner, "row", FirstStateComponent(restored::set)))
    assertThat(newer.get().value).isEqualTo(8)
    assertThat(restored.get().value).isEqualTo(8)
    restoredOwner.destroy()
  }

  @Test
  fun `only the most recently parked keyed trees are kept and saved`() {
    val owner = TestSavedStateOwner()
    val rowCount = ComponentTreeSaveableStateOwner.MAX_PARKED_KEYS + 8
    repeat(rowCount) { index -> releaseKeyedRow(owner, "row-$index", index) }

    assertThat(savedRootCount(owner.save()))
        .isEqualTo(ComponentTreeSaveableStateOwner.MAX_PARKED_KEYS)
    val oldest = AtomicReference<State<Int>>()
    val newest = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, "row-0", FirstStateComponent(oldest::set)))
    render(createKeyedTree(owner, "row-${rowCount - 1}", FirstStateComponent(newest::set)))

    assertThat(oldest.get().value).isEqualTo(0)
    assertThat(newest.get().value).isEqualTo(rowCount - 1)
    assertThat(parkedKeyDropWarnings()).isEqualTo(1)
    owner.destroy()
  }

  @Test
  fun `a claimed key no longer counts toward the parked limit`() {
    val owner = TestSavedStateOwner()
    repeat(ComponentTreeSaveableStateOwner.MAX_PARKED_KEYS) { index ->
      releaseKeyedRow(owner, "row-$index", index + 1)
    }
    val claimed = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, "row-0", FirstStateComponent(claimed::set)))

    releaseKeyedRow(owner, "row-extra", 99)
    val second = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, "row-1", FirstStateComponent(second::set)))

    assertThat(claimed.get().value).isEqualTo(1)
    assertThat(second.get().value).isEqualTo(2)
    assertThat(parkedKeyDropWarnings()).isZero()
    owner.destroy()
  }

  @Test
  fun `a tree released with its stopped host restores in the next tree`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val firstTree = createTree(owner, FirstStateComponent(first::set))
    val view = render(firstTree)
    first.get().updateSync(6)

    owner.moveTo(Lifecycle.State.CREATED)
    view.detachFromWindow()
    firstTree.release()
    val next = AtomicReference<State<Int>>()
    render(createTree(owner, FirstStateComponent(next::set)))
    owner.moveTo(Lifecycle.State.RESUMED)

    assertThat(next.get().value).isEqualTo(6)
    owner.destroy()
  }

  @Test
  fun `saveable-state keys separate roots of the same class`() {
    val firstOwner = TestSavedStateOwner()
    val left = AtomicReference<State<Int>>()
    val right = AtomicReference<State<Int>>()
    render(createKeyedTree(firstOwner, "left", FirstStateComponent(left::set)))
    render(createKeyedTree(firstOwner, "right", FirstStateComponent(right::set)))
    left.get().updateSync(1)
    right.get().updateSync(2)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restoredRight = AtomicReference<State<Int>>()
    val restoredLeft = AtomicReference<State<Int>>()
    render(createKeyedTree(restoredOwner, "right", FirstStateComponent(restoredRight::set)))
    render(createKeyedTree(restoredOwner, "left", FirstStateComponent(restoredLeft::set)))

    assertThat(restoredLeft.get().value).isEqualTo(1)
    assertThat(restoredRight.get().value).isEqualTo(2)
    restoredOwner.destroy()
  }

  @Test
  fun `a saveable-state key replaces the root class as the identity`() {
    val firstOwner = TestSavedStateOwner()
    val keyed = AtomicReference<State<Int>>()
    render(createKeyedTree(firstOwner, "feed", FirstStateComponent(keyed::set)))
    keyed.get().updateSync(6)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val unkeyed = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, FirstStateComponent(unkeyed::set)))
    val restoredKeyed = AtomicReference<State<Int>>()
    render(createKeyedTree(restoredOwner, "feed", FirstStateComponent(restoredKeyed::set)))

    assertThat(unkeyed.get().value).isEqualTo(0)
    assertThat(restoredKeyed.get().value).isEqualTo(6)
    restoredOwner.destroy()
  }

  @Test
  fun `a keyed tree rebuilt three times while active keeps one saved entry`() {
    val owner = TestSavedStateOwner()
    val state = AtomicReference<State<Int>>()
    var tree = createKeyedTree(owner, "feed", FirstStateComponent(state::set))
    render(tree)
    state.get().updateSync(7)

    repeat(3) {
      tree.release()
      tree = createKeyedTree(owner, "feed", FirstStateComponent(state::set))
      render(tree)
      assertThat(state.get().value).isEqualTo(7)
    }
    assertThat(savedRootCount(owner.save())).isEqualTo(1)
    owner.destroy()
  }

  @Test
  fun `an owner whose provider key is taken lets its registry be collected`() {
    var host: TestSavedStateOwner? = TestSavedStateOwner()
    val foreignProvider = SavedStateRegistry.SavedStateProvider { Bundle() }
    checkNotNull(host).savedStateRegistry.registerSavedStateProvider(PROVIDER_KEY, foreignProvider)
    createTree(checkNotNull(host), FirstStateComponent {}).release()
    val registry = WeakReference(checkNotNull(host).savedStateRegistry)
    assertThat(checkNotNull(host).savedStateRegistry.getSavedStateProvider(PROVIDER_KEY))
        .isSameAs(foreignProvider)

    host = null

    awaitCollected(registry)
  }

  @Test
  fun `a tree laid out off the main thread and released while stopped lets its host be collected`() {
    awaitCollected(releaseTreeLaidOutOffMain())
  }

  @Test
  fun `a host dropped without being destroyed can be collected`() {
    awaitCollected(dropHostWithParkedTree())
  }

  @Test
  fun `a ComponentDialog that is never shown can be collected`() {
    awaitCollected(dropNeverShownDialog())
  }

  @Test
  fun `a released tree that is still referenced lets its last root be collected`() {
    val host = TestSavedStateOwner()
    val hosted = releaseKeepingTree(createTree(host, FirstStateComponent {}))
    val unhosted = releaseKeepingTree(
        ComponentTree.create(lithoTestRule.context, FirstStateComponent {}).build(),
    )

    awaitCollected(hosted.second)
    awaitCollected(unhosted.second)
    assertThat(hosted.first.isReleased).isTrue()
    assertThat(unhosted.first.isReleased).isTrue()
    host.destroy()
  }

  /** Lays out [tree] with a new root, releases it, and returns it with a weak ref to that root. */
  private fun releaseKeepingTree(
      tree: ComponentTree,
  ): Pair<ComponentTree, WeakReference<Component>> {
    val root = FirstStateComponent {}
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    tree.setRootAndSizeSpecSync(root, sizeSpec, sizeSpec)
    tree.release()
    shadowOf(Looper.getMainLooper()).idle()
    return tree to WeakReference(root)
  }

  /** Builds and lays out a tree on a background thread, then releases it unshown at CREATED. */
  private fun releaseTreeLaidOutOffMain(): WeakReference<SavedStateRegistryOwner> {
    val host = TestSavedStateOwner()
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    var tree: ComponentTree? = null
    Thread {
      tree =
          createTree(host, FirstStateComponent {}).apply {
            setRootAndSizeSpecSync(FirstStateComponent {}, sizeSpec, sizeSpec)
          }
    }
        .apply { start() }
        .join()
    host.moveTo(Lifecycle.State.CREATED)
    checkNotNull(tree).release()
    host.destroy()
    shadowOf(Looper.getMainLooper()).idle()
    return WeakReference(host)
  }

  /** Parks a tree's values in a connected host that never reaches DESTROYED. */
  private fun dropHostWithParkedTree(): WeakReference<SavedStateRegistryOwner> {
    val host = TestSavedStateOwner()
    val tree = createTree(host, FirstStateComponent {})
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    tree.setRootAndSizeSpecSync(FirstStateComponent {}, sizeSpec, sizeSpec)
    host.moveTo(Lifecycle.State.CREATED)
    tree.release()
    shadowOf(Looper.getMainLooper()).idle()
    return WeakReference(host)
  }

  /** A ComponentDialog sends ON_DESTROY only from onStop, so one that is never shown never does. */
  private fun dropNeverShownDialog(): WeakReference<ComponentDialog> {
    val dialog = ComponentDialog(lithoTestRule.context.androidContext)
    val tree = createTree(dialog, FirstStateComponent {})
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    tree.setRootAndSizeSpecSync(FirstStateComponent {}, sizeSpec, sizeSpec)
    tree.release()
    shadowOf(Looper.getMainLooper()).idle()
    return WeakReference(dialog)
  }

  /**
   * ```
   * Step                                        Expected
   * ----                                        --------
   * Process death with 7 saved
   * Build and render the tree off main thread   Renders 0: restored values not taken yet
   * Set it on a LithoView on main, then mount   Renders 7 before mounting, mounts 7
   * ```
   */
  @Test
  fun `a tree built and rendered off the main thread restores before it mounts`() {
    val savedState = savedStateWithFirstValue(7)
    val restoredOwner = TestSavedStateOwner(savedState)
    val values = CopyOnWriteArrayList<Int>()
    val restored = AtomicReference<State<Int>>()
    val root = FirstStateComponent { state ->
      restored.set(state)
      values += state.value
    }

    val tree = renderOffMainThread(root) { createTree(restoredOwner, it) }
    assertThat(values).containsExactly(0)
    render(tree)
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(restored.get().value).isEqualTo(7)
    assertThat(values.drop(1)).isNotEmpty.allMatch { it == 7 }
    restoredOwner.destroy()
  }

  @Test
  fun `a keyed tree built and rendered off the main thread restores before it mounts`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    render(createKeyedTree(firstOwner, "feed", FirstStateComponent(first::set)))
    first.get().updateSync(8)
    val savedState = firstOwner.save()
    firstOwner.destroy()

    val restoredOwner = TestSavedStateOwner(savedState)
    val restored = AtomicReference<State<Int>>()
    val tree =
        renderOffMainThread(FirstStateComponent(restored::set)) {
          createKeyedTree(restoredOwner, "feed", it)
        }
    render(tree)

    assertThat(restored.get().value).isEqualTo(8)
    restoredOwner.destroy()
  }

  /**
   * ```text
   * Before process death: feed = 8
   * Build and render tree A off the main thread   A claims "feed" before restore, renders 0
   * Main thread takes Android's restored values   8 waits for whoever holds "feed"
   * Tree B takes "feed" over from A               B copies A's 0 and holds "feed"
   * The 8 is delivered                            B gets 8; A, replaced, keeps 0
   * ```
   */
  @Test
  fun `late restored values go to the tree that took the key over before delivery`() {
    val restoredOwner = TestSavedStateOwner(savedKeyedState("feed", 8))
    val superseded = AtomicReference<State<Int>>()
    val replacement = AtomicReference<State<Int>>()
    val replacementTree = AtomicReference<ComponentTree>()
    val supersededTree =
        renderOffMainThread(FirstStateComponent(superseded::set)) {
          createKeyedTree(restoredOwner, "feed", it)
        }
    val owner = saveableStateOwner(restoredOwner)
    owner.beforeLateRestoreDelivery = {
      owner.beforeLateRestoreDelivery = null
      replacementTree.set(
          renderOffMainThread(FirstStateComponent(replacement::set)) {
            createKeyedTree(restoredOwner, "feed", it)
          },
      )
    }
    render(supersededTree)

    assertThat(replacement.get().value).isEqualTo(8)
    assertThat(superseded.get().value).isEqualTo(0)
    assertThat(restoredKeyedValue(restoredOwner, "feed")).isEqualTo(8)
  }

  /**
   * ```text
   * Before process death: feed = 8
   * Build and render tree A off the main thread   A claims "feed" before restore, renders 0
   * Tree B takes "feed" over off the main thread  B copies A's 0, also before restore
   * Show B: the main thread takes the restore     B renders 8 before it mounts
   * ```
   */
  @Test
  fun `a keyed tree that took the key over before restore gets the restored values`() {
    val restoredOwner = TestSavedStateOwner(savedKeyedState("feed", 8))
    val superseded = AtomicReference<State<Int>>()
    val replacement = AtomicReference<State<Int>>()
    val supersededTree =
        renderOffMainThread(FirstStateComponent(superseded::set)) {
          createKeyedTree(restoredOwner, "feed", it)
        }
    val replacementTree =
        renderOffMainThread(FirstStateComponent(replacement::set)) {
          createKeyedTree(restoredOwner, "feed", it)
        }

    render(replacementTree)

    assertThat(replacement.get().value).isEqualTo(8)
    assertThat(superseded.get().value).isEqualTo(0)
    assertThat(restoredKeyedValue(restoredOwner, "feed")).isEqualTo(8)
    supersededTree.release()
  }

  /**
   * ```text
   * Before process death: feed = 8
   * Build and render tree A off the main thread   A claims "feed" before restore, renders 0
   * Release A                                     A parks its starting 0
   * Build B on the main thread                    The restore replaces A's 0 with 8
   * Show B                                        B claims "feed" and renders 8
   * ```
   */
  @Test
  fun `restored values replace starting values parked before restore`() {
    val restoredOwner = TestSavedStateOwner(savedKeyedState("feed", 8))
    val released = AtomicReference<State<Int>>()
    val replacement = AtomicReference<State<Int>>()
    val releasedTree =
        renderOffMainThread(FirstStateComponent(released::set)) {
          createKeyedTree(restoredOwner, "feed", it)
        }

    releasedTree.release()
    render(createKeyedTree(restoredOwner, "feed", FirstStateComponent(replacement::set)))

    assertThat(replacement.get().value).isEqualTo(8)
    assertThat(restoredKeyedValue(restoredOwner, "feed")).isEqualTo(8)
  }

  /**
   * ```text
   * Before process death: feed = 8; the owner is not restored yet
   * Show tree A and set it to 2                   A claims "feed" before restore
   * Release A                                     A parks 2, which the user saw
   * Android restores, then tree B claims "feed"   B gets A's 2, not 8
   * ```
   */
  @Test
  fun `values parked by a shown tree win over values restored later`() {
    val owner = TestSavedStateOwner(savedKeyedState("feed", 8), restoreNow = false)
    val shown = AtomicReference<State<Int>>()
    val shownTree = createKeyedTree(owner, "feed", FirstStateComponent(shown::set))
    render(shownTree)
    shown.get().updateSync(2)

    shownTree.release()
    owner.restore()
    owner.moveTo(Lifecycle.State.RESUMED)
    val next = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, "feed", FirstStateComponent(next::set)))

    assertThat(next.get().value).isEqualTo(2)
    owner.destroy()
  }

  /**
   * ```text
   * Before process death: scroll = 7; the filter isn't shown, so nothing is saved for it
   * Render the tree, not shown, before Android restores   (scroll 0, filter 0)
   * Set the filter to 2                                   (scroll 0, filter 2)
   * Android restores                                      (scroll 7, filter 2)
   * ```
   */
  @Test
  fun `a late restore keeps values that are not in the restored state`() {
    val firstOwner = TestSavedStateOwner()
    val firstScroll = AtomicReference<State<Int>>()
    render(createKeyedTree(firstOwner, "feed", FeedComponent(false, firstScroll::set)))
    firstScroll.get().updateSync(7)
    val restoredOwner = TestSavedStateOwner(firstOwner.save(), restoreNow = false)
    firstOwner.destroy()
    val scroll = AtomicReference<State<Int>>()
    val filter = AtomicReference<State<Int>>()
    val root = FeedComponent(true, scroll::set, filter::set)
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    val tree = createKeyedTree(restoredOwner, "feed", root)
    tree.setRootAndSizeSpecSync(root, sizeSpec, sizeSpec)
    filter.get().updateSync(2)
    assertThat(scroll.get().value to filter.get().value).isEqualTo(0 to 2)

    restoredOwner.restore()
    restoredOwner.moveTo(Lifecycle.State.RESUMED)

    assertThat(scroll.get().value to filter.get().value).isEqualTo(7 to 2)
    tree.release()
    restoredOwner.destroy()
  }

  @Test
  fun `a tree that mounted before restored values arrived keeps its own values`() {
    val savedState = savedStateWithFirstValue(7)
    val owner = TestSavedStateOwner(savedState, restoreNow = false)
    val state = AtomicReference<State<Int>>()
    render(createTree(owner, FirstStateComponent(state::set)))
    state.get().updateSync(2)

    owner.restore()
    owner.moveTo(Lifecycle.State.RESUMED)

    assertThat(state.get().value).isEqualTo(2)
    owner.destroy()
  }

  @Test
  fun `a late restore updates a hook that already rendered without initializing it again`() {
    val firstOwner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    render(createTree(firstOwner, CountingStateComponent(AtomicInteger(), first::set)))
    first.get().updateSync(7)
    val restoredOwner = TestSavedStateOwner(firstOwner.save(), restoreNow = false)
    firstOwner.destroy()
    val initializerCalls = AtomicInteger()
    val state = AtomicReference<State<Int>>()
    val root = CountingStateComponent(initializerCalls, state::set)
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    val tree = createTree(restoredOwner, root)
    tree.setRootAndSizeSpecSync(root, sizeSpec, sizeSpec)
    assertThat(state.get().value).isEqualTo(0)

    restoredOwner.restore()
    restoredOwner.moveTo(Lifecycle.State.RESUMED)

    assertThat(state.get().value).isEqualTo(7)
    assertThat(initializerCalls).hasValue(1)
    tree.release()
    restoredOwner.destroy()
  }

  /**
   * ```text
   * Before process death: 5, and 7 inside a vertical scroll
   * Render the tree, not shown, before Android restores   (0, 0)
   * Android restores                                      (5, 7)
   * ```
   */
  @Test
  fun `a late restore reaches values inside a vertical scroll`() {
    val firstOwner = TestSavedStateOwner()
    val firstOuter = AtomicReference<State<Int>>()
    val firstInner = AtomicReference<State<Int>>()
    val firstView =
        lithoTestRule
            .createTestLithoView(
                componentTree =
                    createTree(
                        firstOwner,
                        ScrollingStateComponent(firstOuter::set, firstInner::set),
                    ),
                widthPx = VIEW_SIZE_PX,
                heightPx = VIEW_SIZE_PX,
            )
            .attachToWindow()
            .measure()
            .layout()
    attachedViews.add(firstView)
    firstOuter.get().updateSync(5)
    firstInner.get().updateSync(7)
    lithoTestRule.idle()
    val restoredOwner = TestSavedStateOwner(firstOwner.save(), restoreNow = false)
    firstOwner.destroy()
    val outer = AtomicReference<State<Int>>()
    val inner = AtomicReference<State<Int>>()
    val root = ScrollingStateComponent(outer::set, inner::set)
    val sizeSpec = SizeSpec.makeSizeSpec(VIEW_SIZE_PX, SizeSpec.EXACTLY)
    val tree = createTree(restoredOwner, root)
    tree.setRootAndSizeSpecSync(root, sizeSpec, sizeSpec)
    assertThat(outer.get().value to inner.get().value).isEqualTo(0 to 0)

    restoredOwner.restore()
    restoredOwner.moveTo(Lifecycle.State.RESUMED)

    assertThat(outer.get().value to inner.get().value).isEqualTo(5 to 7)
    tree.release()
    restoredOwner.destroy()
  }

  /**
   * ```text
   * Tree A = 4; its TreeState is kept and A is released, closing its registry
   * Tree B is built with that TreeState         B shows 4 and saves it in its own registry
   * ```
   */
  @Test
  fun `a TreeState carried into a tree with a new registry is saved there`() {
    val owner = TestSavedStateOwner()
    val first = AtomicReference<State<Int>>()
    val firstTree = createTree(owner, FirstStateComponent(first::set))
    val firstView = render(firstTree)
    first.get().updateSync(4)
    val carried = firstTree.acquireTreeState()
    firstView.release()

    val second = AtomicReference<State<Int>>()
    render(
        ComponentTree.create(
                lithoTestRule.context.androidContext,
                owner,
                FirstStateComponent(second::set),
            )
            .treeState(carried)
            .build(),
    )
    assertThat(second.get().value).isEqualTo(4)

    val restoredOwner = TestSavedStateOwner(owner.save())
    owner.destroy()
    val restored = AtomicReference<State<Int>>()
    render(createTree(restoredOwner, FirstStateComponent(restored::set)))
    assertThat(restored.get().value).isEqualTo(4)
    restoredOwner.destroy()
  }

  private fun savedStateWithFirstValue(value: Int): Bundle {
    val owner = TestSavedStateOwner()
    val state = AtomicReference<State<Int>>()
    render(createTree(owner, FirstStateComponent(state::set)))
    state.get().updateSync(value)
    return owner.save().also { owner.destroy() }
  }

  /** Builds and lays out a tree on a background thread, with no main-thread call into it. */
  private fun renderOffMainThread(
      root: Component,
      create: (Component) -> ComponentTree,
  ): ComponentTree {
    val sizeSpec = SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY)
    val tree = AtomicReference<ComponentTree>()
    val failure = AtomicReference<Throwable>()
    thread {
      try {
        tree.set(create(root).apply { setRootAndSizeSpecSync(root, sizeSpec, sizeSpec) })
      } catch (throwable: Throwable) {
        failure.set(throwable)
      }
    }
        .join()
    failure.get()?.let { throw it }
    return tree.get()
  }

  private fun savedKeyedState(key: String, value: Int): Bundle {
    val owner = TestSavedStateOwner()
    val state = AtomicReference<State<Int>>()
    render(createKeyedTree(owner, key, FirstStateComponent(state::set)))
    state.get().updateSync(value)
    return owner.save().also { owner.destroy() }
  }

  /** Saves [owner], destroys it, and returns the value a keyed tree restores from that save. */
  private fun restoredKeyedValue(owner: TestSavedStateOwner, key: String): Int {
    val nextOwner = TestSavedStateOwner(owner.save())
    owner.destroy()
    val next = AtomicReference<State<Int>>()
    render(createKeyedTree(nextOwner, key, FirstStateComponent(next::set)))
    return next.get().value.also { nextOwner.destroy() }
  }

  private fun saveableStateOwner(store: SavedStateRegistryOwner): ComponentTreeSaveableStateOwner =
      ComponentTreeSaveableStateOwner.of(store, lithoTestRule.context.androidContext.classLoader)

  private fun createTree(owner: SavedStateRegistryOwner, root: Component): ComponentTree =
      ComponentTree.create(lithoTestRule.context.androidContext, owner, root).build()

  private fun createKeyedTree(
      owner: SavedStateRegistryOwner,
      key: String,
      root: Component,
  ): ComponentTree =
      ComponentTree.create(lithoTestRule.context.androidContext, owner, root)
          .withSaveableStateKey(key)
          .build()

  private fun createNestedTree(
      parentRegistry: LithoSaveableStateRegistry,
      onState: (State<Int>) -> Unit,
  ): ComponentTree {
    val treeProps =
        TreePropContainer().apply { put(LithoSaveableStateRegistryTreeProp, parentRegistry) }
    return ComponentTree.create(
            ComponentContext(lithoTestRule.context.androidContext, treePropContainer = treeProps),
            FirstStateComponent(onState),
        )
        .withNestedTreeSaveableStateKey(NESTED_TREE_KEY)
        .build()
  }

  // Attached views keep their trees reachable, as a window does; the owner holds trees weakly.
  private val attachedViews = mutableListOf<TestLithoView>()

  private fun render(tree: ComponentTree): TestLithoView =
      lithoTestRule
          .createTestLithoView(componentTree = tree)
          .attachToWindow()
          .measure()
          .layout()
          .also(attachedViews::add)

  /** Runs garbage collection until [reference] is cleared, failing if it never is. */
  private fun awaitCollected(reference: WeakReference<*>) {
    repeat(GC_ATTEMPTS) {
      if (reference.get() == null) return
      System.gc()
    }
    assertThat(reference.get()).describedAs("not garbage collected").isNull()
  }

  /** Renders a keyed row, sets its value, and releases it while the owner is active. */
  private fun releaseKeyedRow(owner: SavedStateRegistryOwner, key: String, value: Int) {
    val state = AtomicReference<State<Int>>()
    val row = createKeyedTree(owner, key, FirstStateComponent(state::set))
    render(row)
    state.get().updateSync(value)
    row.release()
  }

  private fun parkedKeyDropWarnings(): Int =
      ShadowLog.getLogs().count {
        it.msg.orEmpty().startsWith("LithoSaveableState:ParkedKeyDropped")
      }

  private fun keyTakeoverWarnings(): Int =
      ShadowLog.getLogs().count {
        it.msg.orEmpty().startsWith("LithoSaveableState:KeyTakenFromAttachedTree")
      }

  private fun savedRootCount(savedState: Bundle): Int =
      lithoSavedState(savedState)?.getBundle("roots")?.size() ?: 0

  /** SavedStateRegistry nests every provider's Bundle inside its own container entry. */
  private fun lithoSavedState(savedState: Bundle): Bundle? =
      savedState.keySet().firstNotNullOfOrNull { key ->
        savedState.getBundle(key)?.getBundle(PROVIDER_KEY)
      }

  private class FirstStateComponent(private val onState: (State<Int>) -> Unit) : KComponent() {
    override fun ComponentScope.render(): Component {
      onState(useSaveableState { 0 })
      return EmptyComponent()
    }
  }

  private class SecondStateComponent(private val onState: (State<Int>) -> Unit) : KComponent() {
    override fun ComponentScope.render(): Component {
      onState(useSaveableState { 0 })
      return EmptyComponent()
    }
  }

  private class CountingStateComponent(
      private val initializerCalls: AtomicInteger,
      private val onState: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      onState(
          useSaveableState {
            initializerCalls.incrementAndGet()
            0
          },
      )
      return EmptyComponent()
    }
  }

  /** A value of its own, and one inside a vertical scroll. */
  private class ScrollingStateComponent(
      private val onOuter: (State<Int>) -> Unit,
      private val onInner: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      onOuter(useSaveableState { 0 })
      return VerticalScroll(child = FirstStateComponent(onInner))
    }
  }

  /** A scroll value, and a filter value in a child shown only when [showFilter]. */
  private class FeedComponent(
      private val showFilter: Boolean,
      private val onScroll: (State<Int>) -> Unit,
      private val onFilter: (State<Int>) -> Unit = {},
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      onScroll(useSaveableState { 0 })
      return if (showFilter) FirstStateComponent(onFilter) else EmptyComponent()
    }
  }

  private class LateStateComponent(
      private val readsState: Boolean,
      private val onRegistry: (LithoSaveableStateRegistry) -> Unit = {},
      private val onState: (State<Int>) -> Unit = {},
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      onRegistry(checkNotNull(LithoSaveableStateRegistryTreeProp.value))
      if (readsState) {
        onState(useSaveableState { 0 })
      }
      return EmptyComponent()
    }
  }

  private class RegistryCapturingComponent(
      private val onRegistry: (LithoSaveableStateRegistry) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      onRegistry(checkNotNull(LithoSaveableStateRegistryTreeProp.value))
      return EmptyComponent()
    }
  }

  /**
   * With [restoreNow] false, the owner stays INITIALIZED and unrestored until [restore], as an
   * Activity is before `super.onCreate()` restores it.
   */
  private class TestSavedStateOwner(
      private val restoredState: Bundle? = null,
      restoreNow: Boolean = true,
  ) : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    init {
      controller.performAttach()
      if (restoreNow) {
        controller.performRestore(restoredState)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
      }
    }

    fun restore() {
      controller.performRestore(restoredState)
    }

    fun moveTo(state: Lifecycle.State) {
      lifecycleRegistry.currentState = state
    }

    fun save(): Bundle = Bundle().also(controller::performSave)

    fun destroy() {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
  }

  private companion object {
    const val PROVIDER_KEY: String = "com.facebook.litho.ComponentTreeSaveableStateOwner"
    const val NESTED_TREE_KEY: String = "nested"
    const val GC_ATTEMPTS: Int = 20
    const val VIEW_SIZE_PX: Int = 100
  }
}
