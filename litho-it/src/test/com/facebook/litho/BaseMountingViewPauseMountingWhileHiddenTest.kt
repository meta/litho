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
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.visibility.onInvisible
import com.facebook.litho.visibility.onVisible
import com.facebook.litho.widget.MountSpecLifecycleTester
import com.facebook.litho.widget.SolidColor
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.incrementalmount.GapWorkerMode
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** Tests [ComponentsConfiguration.pauseMountingWhileHidden]. */
@RunWith(LithoTestRunner::class)
class BaseMountingViewPauseMountingWhileHiddenTest {

  private val container = FrameLayout(Robolectric.buildActivity(Activity::class.java).setup().get())
  private val activity: Activity
    get() = container.context as Activity

  private val shownCounts = IntArray(2)
  private val hiddenCounts = IntArray(2)

  @Test
  fun `mounts the latest layout once shown again, not while hidden`() {
    val lithoView = show(pause = true)

    container.visibility = View.GONE
    requireNotNull(lithoView.componentTree).root = NewContent()

    assertThat(shownCounts[NEW]).isEqualTo(0)
    assertThat(hiddenCounts[OLD]).isEqualTo(0)

    showContainer(lithoView)

    assertThat(shownCounts[NEW]).isEqualTo(1)
    assertThat(hiddenCounts[OLD]).isEqualTo(1)
  }

  @Test
  fun `mounts while hidden when the flag is off`() {
    val lithoView = show(pause = false)

    container.visibility = View.GONE
    requireNotNull(lithoView.componentTree).root = NewContent()

    assertThat(shownCounts[NEW]).isEqualTo(1)
  }

  @Test
  fun `mounts while the root view of the window is hidden, as in a stopped activity`() {
    val lithoView = show(pause = true)

    activity.window.decorView.visibility = View.INVISIBLE
    requireNotNull(lithoView.componentTree).root = NewContent()

    assertThat(shownCounts[NEW]).isEqualTo(1)
  }

  @Test
  fun `mounts a view laid out under a hidden ancestor once shown`() {
    activity.setContentView(
        container,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CONTAINER_HEIGHT),
    )
    idle()
    container.visibility = View.GONE
    val lithoView = createLithoView(pause = true, NewContent())
    container.addView(lithoView, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
    // A RecyclerView lays out its children itself, even while an ancestor is hidden.
    lithoView.measure(
        View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY),
    )
    lithoView.layout(0, 0, VIEW_SIZE, VIEW_SIZE)

    assertThat(lithoView.mountDelegateTarget.getMountItemCount()).isEqualTo(0)

    showContainer(lithoView)

    assertThat(lithoView.mountDelegateTarget.getMountItemCount()).isGreaterThan(0)
    assertThat(shownCounts[NEW]).isEqualTo(1)
  }

  @Test
  fun `runs an incremental mount pass skipped while hidden once shown`() {
    val lithoView =
        show(pause = true, root = TallContent(), height = CONTAINER_HEIGHT + 2 * VIEW_SIZE)

    container.visibility = View.GONE
    // Moves the block below the container into view, as scrolling a parent would.
    lithoView.translationY = -CONTAINER_HEIGHT.toFloat()
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(shownCounts[NEW]).isEqualTo(0)

    showContainer(lithoView)

    assertThat(shownCounts[NEW]).isEqualTo(1)
  }

  @Test
  fun `still unmounts while hidden`() {
    val lithoView = show(pause = true)
    val mountedItems = lithoView.mountDelegateTarget.getMountItemCount()

    container.visibility = View.GONE
    lithoView.notifyVisibleBoundsChanged(Rect(), true, VisibilityChangeReason.SCROLL)

    assertThat(lithoView.mountDelegateTarget.getMountItemCount()).isLessThan(mountedItems)
  }

  @Test
  fun `doesn't mount content excluded from incremental mount while hidden`() {
    val lithoView = show(pause = true)
    val tracker = LifecycleTracker()

    container.visibility = View.GONE
    requireNotNull(lithoView.componentTree).root =
        MountSpecLifecycleTester.create(ComponentContext(activity))
            .lifecycleTracker(tracker)
            .intrinsicSize(Size(VIEW_SIZE, VIEW_SIZE))
            .shouldExcludeFromIncrementalMount(true)
            .build()
    lithoView.notifyVisibleBoundsChanged(Rect(), true, VisibilityChangeReason.SCROLL)

    assertThat(tracker.isMounted).isFalse()

    showContainer(lithoView)

    assertThat(tracker.isMounted).isTrue()
  }

  @Test
  fun `doesn't register for premount while hidden, and registers once shown`() {
    val lithoView = show(pause = true, gapWorkerMode = GapWorkerMode.PREMOUNT)
    var dirtyMounts = 0
    lithoView.setOnDirtyMountListener { dirtyMounts++ }

    container.visibility = View.GONE
    requireNotNull(lithoView.componentTree).root = NewContent()
    lithoView.onRegisterForPremount(0L)

    assertThat(dirtyMounts).isEqualTo(0)

    showContainer(lithoView)

    assertThat(dirtyMounts).isEqualTo(1)
    assertThat(lithoView.premountRegistrations).hasSize(2)
  }

  @Test
  fun `content that stays visible gets no new onVisible when shown again`() {
    val lithoView = show(pause = true)

    container.visibility = View.GONE
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)
    showContainer(lithoView)

    assertThat(shownCounts[OLD]).isEqualTo(1)
    assertThat(hiddenCounts[OLD]).isEqualTo(0)
  }

  @Test
  fun `with enableCheckVisibilityAggregated also on, onInvisible runs at the hide and a show catches up once`() {
    val lithoView = show(pause = true)
    val defaultConfiguration = ComponentsConfiguration.defaultInstance
    ComponentsConfiguration.defaultInstance =
        defaultConfiguration.copy(enableCheckVisibilityAggregated = true)
    try {
      container.visibility = View.GONE
      lithoView.onVisibilityAggregated(false)
      requireNotNull(lithoView.componentTree).root = NewContent()

      assertThat(hiddenCounts[OLD]).isEqualTo(1)
      assertThat(shownCounts[NEW]).isEqualTo(0)

      lithoView.visibleBoundsNotifications = 0
      showContainer(lithoView)

      assertThat(lithoView.visibleBoundsNotifications).isEqualTo(1)
      assertThat(shownCounts[NEW]).isEqualTo(1)
    } finally {
      ComponentsConfiguration.defaultInstance = defaultConfiguration
    }
  }

  @Test
  fun `an app visibility hint false survives the view being shown`() {
    val app = LithoVisibilityEventsControllerDelegate()
    val lithoView = show(pause = true, visibilityController = app)

    app.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
    lithoView.onVisibilityAggregated(true)
    requireNotNull(lithoView.componentTree).root = NewContent()

    assertThat(shownCounts[NEW]).isEqualTo(0)
  }

  @Test
  fun `registers for premount under an app visibility hint false`() {
    val app = LithoVisibilityEventsControllerDelegate()
    val lithoView =
        show(pause = true, gapWorkerMode = GapWorkerMode.PREMOUNT, visibilityController = app)

    app.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
    lithoView.onRegisterForPremount(0L)
    app.moveToVisibilityState(LithoVisibilityState.HINT_VISIBLE)

    assertThat(lithoView.isRegisteredForPremount).isTrue()
    lithoView.onUnregisterForPremount()
  }

  @Test
  fun `an empty-rect pass under an app visibility hint false unmounts nothing`() {
    val app = LithoVisibilityEventsControllerDelegate()
    val lithoView = show(pause = true, visibilityController = app)
    val mountedItems = lithoView.mountDelegateTarget.getMountItemCount()

    app.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
    lithoView.notifyVisibleBoundsChanged(Rect(), true, VisibilityChangeReason.SCROLL)

    assertThat(lithoView.mountDelegateTarget.getMountItemCount()).isEqualTo(mountedItems)
  }

  @Test
  fun `mounts at the next pass once the root view of the window is hidden, after a pause`() {
    val lithoView = show(pause = true)
    container.visibility = View.GONE
    requireNotNull(lithoView.componentTree).root = NewContent()

    assertThat(shownCounts[NEW]).isEqualTo(0)

    activity.window.decorView.visibility = View.INVISIBLE
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(shownCounts[NEW]).isEqualTo(1)
  }

  @Test
  fun `mounts at the next pass once shown, without the aggregated visibility callback`() {
    val lithoView = show(pause = true)
    container.visibility = View.GONE
    requireNotNull(lithoView.componentTree).root = NewContent()

    assertThat(shownCounts[NEW]).isEqualTo(0)

    container.visibility = View.VISIBLE
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(shownCounts[NEW]).isEqualTo(1)
  }

  /** Shows a [LithoView] inside [container] and lays it out. */
  private fun show(
      pause: Boolean,
      root: Component = OldContent(),
      height: Int = VIEW_SIZE,
      gapWorkerMode: GapWorkerMode = GapWorkerMode.OFF,
      visibilityController: LithoVisibilityEventsController? = null,
  ): RecordingLithoView {
    val lithoView = createLithoView(pause, root, gapWorkerMode, visibilityController)
    container.addView(lithoView, FrameLayout.LayoutParams(VIEW_SIZE, height))
    activity.setContentView(
        container,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CONTAINER_HEIGHT),
    )
    idle()
    assertThat(shownCounts[OLD]).isEqualTo(1)
    return lithoView
  }

  /**
   * Robolectric reports the window as not visible, so [View.setVisibility] doesn't dispatch the
   * aggregated visibility change that a visible window would.
   */
  private fun showContainer(lithoView: LithoView) {
    container.visibility = View.VISIBLE
    lithoView.onVisibilityAggregated(true)
  }

  private fun createLithoView(
      pause: Boolean,
      root: Component,
      gapWorkerMode: GapWorkerMode = GapWorkerMode.OFF,
      visibilityController: LithoVisibilityEventsController? = null,
  ): RecordingLithoView {
    val config =
        ComponentsConfiguration.defaultInstance.copy(
            pauseMountingWhileHidden = pause,
            gapWorkerMode = gapWorkerMode,
        )
    return RecordingLithoView(activity).apply {
      componentTree =
          ComponentTree.create(ComponentContext(activity), root)
              .componentsConfiguration(config)
              .withLithoVisibilityEventsController(visibilityController)
              .build()
    }
  }

  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  /** Records premount registrations and catch-up passes. */
  private class RecordingLithoView(context: Context) : LithoView(context) {
    val premountRegistrations: MutableList<Long?> = mutableListOf()
    var visibleBoundsNotifications: Int = 0

    /** Whether incremental mount registered this tree with the gap worker. */
    val isRegisteredForPremount: Boolean
      get() {
        val state =
            lithoHostListenerCoordinator?.getIncrementalMountExtensionState()?.state ?: return false
        return ReflectionHelpers.getField(state, "isRegisteredWithGapWorker")
      }

    override fun onRegisterForPremount(frameTimeMs: Long?) {
      premountRegistrations.add(frameTimeMs)
      super.onRegisterForPremount(frameTimeMs)
    }

    override fun notifyVisibleBoundsChanged(reason: VisibilityChangeReason) {
      visibleBoundsNotifications++
      super.notifyVisibleBoundsChanged(reason)
    }
  }

  private fun counted(index: Int, color: Int): Component = SolidColor(
      color,
      style =
          Style.width(VIEW_SIZE.px)
              .height(VIEW_SIZE.px)
              .onVisible { shownCounts[index]++ }
              .onInvisible { hiddenCounts[index]++ },
  )

  private inner class OldContent : KComponent() {
    override fun ComponentScope.render(): Component = counted(OLD, Color.BLACK)
  }

  private inner class NewContent : KComponent() {
    override fun ComponentScope.render(): Component = counted(NEW, Color.WHITE)
  }

  /** [OldContent] on top and [NewContent] starting below the bottom edge of [container]. */
  private inner class TallContent : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(counted(OLD, Color.BLACK))
      child(SolidColor(Color.TRANSPARENT, style = Style.height(CONTAINER_HEIGHT.px)))
      child(counted(NEW, Color.WHITE))
    }
  }

  private companion object {
    const val OLD = 0
    const val NEW = 1
    const val VIEW_SIZE = 100
    const val CONTAINER_HEIGHT = 300
  }
}
