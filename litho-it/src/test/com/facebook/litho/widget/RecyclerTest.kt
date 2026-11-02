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

package com.facebook.litho.widget

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.PrimitiveRecyclerBinderStrategy
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/** Tests for [Recycler] */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RecyclerTest {

  @Rule @JvmField val lithoTestRule: LithoTestRule = LithoTestRule()

  // In a real window, the remeasure a wrap-content binder posts for the next frame runs.
  @Rule @JvmField val realWindowRule: LithoTestRule = LithoTestRule(attachToRealWindow = true)

  lateinit var recyclerBinder: RecyclerBinder

  @Before
  fun setup() {
    recyclerBinder = newRecyclerBinder()
  }

  @Test
  fun `ExperimentalRecycler should render with default values`() {
    val testLithoView = lithoTestRule.render {
      Recycler(binder = recyclerBinder, style = Style.width(100.px).height(100.px))
    }

    // should find an ExperimentalRecycler in the tree
    assertNotNull(testLithoView.findComponent(Recycler::class))

    val sectionsRecyclerView =
        testLithoView.lithoView.getMountItemAt(0).content as SectionsRecyclerView

    // values should be set to default
    assertThat(sectionsRecyclerView.recyclerView.hasFixedSize()).isEqualTo(true)
    assertThat(sectionsRecyclerView.recyclerView.clipToPadding).isEqualTo(true)
    assertThat(sectionsRecyclerView.recyclerView.isNestedScrollingEnabled).isEqualTo(true)
    assertThat(sectionsRecyclerView.recyclerView.scrollBarStyle)
        .isEqualTo(View.SCROLLBARS_INSIDE_OVERLAY)
    assertThat(sectionsRecyclerView.recyclerView.id).isEqualTo(View.NO_ID)
    assertThat(sectionsRecyclerView.recyclerView.horizontalFadingEdgeLength).isEqualTo(0)
    assertThat(sectionsRecyclerView.recyclerView.isHorizontalFadingEdgeEnabled).isEqualTo(false)
    assertThat(sectionsRecyclerView.recyclerView.isVerticalFadingEdgeEnabled).isEqualTo(false)
  }

  // The switch's lifetime under both binder strategies: only an unmount turns it off.

  @Test
  fun `a re-render with the same props keeps skipsRedundantBringToFront on`() {
    forEachStrategy { strategy, binder ->
      val testLithoView = lithoTestRule.render { recycler(binder, strategy) }
      val list = testLithoView.mountedList()
      list.skipsRedundantBringToFront = true

      testLithoView.setRoot { recycler(binder, strategy) }

      assertThat(testLithoView.mountedList()).isSameAs(list)
      assertThat(list.skipsRedundantBringToFront).describedAs("$strategy").isTrue
    }
  }

  @Test
  fun `a rebind for new props keeps skipsRedundantBringToFront on`() {
    forEachStrategy { strategy, binder ->
      val testLithoView = lithoTestRule.render { recycler(binder, strategy, Color.RED) }
      val list = testLithoView.mountedList()
      list.skipsRedundantBringToFront = true

      testLithoView.setRoot { recycler(binder, strategy, Color.BLUE) }

      assertThat(testLithoView.mountedList()).isSameAs(list)
      assertThat(list.skipsRedundantBringToFront).describedAs("$strategy").isTrue
    }
  }

  @Test
  fun `a growing wrap-content list keeps skipsRedundantBringToFront on`() {
    forEachStrategy { strategy, _ ->
      val wrapContentBinder =
          RecyclerBinder.Builder()
              .layoutInfo(
                  LinearLayoutInfo(realWindowRule.context, OrientationHelper.VERTICAL, false),
              )
              .recyclerBinderConfig(RecyclerBinderConfig.create().wrapContent(true).build())
              .build(realWindowRule.context)
      wrapContentBinder.insertItemAt(0, textRow(realWindowRule.context))
      val testLithoView =
          realWindowRule.render(
              heightSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
          ) {
            Recycler(
                binder = wrapContentBinder,
                binderStrategy = strategy,
                style = Style.width(100.px),
            )
          }
      val list = testLithoView.mountedList()
      val heightBefore = list.height
      list.skipsRedundantBringToFront = true

      // The list grows, so it measures again: the measure version changes with no new props.
      for (i in 1 until 4) {
        wrapContentBinder.insertItemAt(i, textRow(realWindowRule.context))
      }
      wrapContentBinder.notifyChangeSetComplete(true, NoOpChangeSetCompleteCallback)
      // The binder posts its remeasure for the next animation frame.
      ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
      realWindowRule.idle()
      testLithoView.measure().layout()

      assertThat(list.height).describedAs("$strategy").isGreaterThan(heightBefore)
      assertThat(testLithoView.mountedList()).isSameAs(list)
      assertThat(list.skipsRedundantBringToFront).describedAs("$strategy").isTrue
    }
  }

  @Test
  fun `a detach without enableFixForIM keeps skipsRedundantBringToFront on`() {
    forEachStrategy { strategy, _ ->
      // In a real window, the detach reaches the list itself, not only the LithoView.
      val binder = newRecyclerBinder(realWindowRule.context)
      val testLithoView =
          realWindowRule.render(componentTree = componentTree(enableFixForIM = false)) {
            Column { child(recycler(binder, strategy)) }
          }
      val list = testLithoView.mountedList()
      list.skipsRedundantBringToFront = true

      testLithoView.detachFromWindow()

      assertThat(testLithoView.mountedList()).isSameAs(list)
      assertThat(list.skipsRedundantBringToFront).describedAs("$strategy").isTrue
    }
  }

  @Test
  fun `unmounting turns skipsRedundantBringToFront off for the next list`() {
    forEachStrategy { strategy, binder ->
      val testLithoView = lithoTestRule.render { recycler(binder, strategy) }
      val list = testLithoView.mountedList()
      list.skipsRedundantBringToFront = true

      testLithoView.lithoView.unmountAllItems()

      assertThat(list.skipsRedundantBringToFront).describedAs("$strategy").isFalse
    }
  }

  @Test
  fun `a pooled list comes back with skipsRedundantBringToFront off`() {
    forEachStrategy { strategy, binder ->
      val first = lithoTestRule.render { recycler(binder, strategy) }
      val pooled = first.mountedList()
      pooled.skipsRedundantBringToFront = true
      first.lithoView.unmountAllItems()

      val second = lithoTestRule.render { recycler(binder, strategy) }

      assertThat(second.mountedList()).isSameAs(pooled)
      assertThat(pooled.skipsRedundantBringToFront).describedAs("$strategy").isFalse
    }
  }

  @Test
  fun `a detach under enableFixForIM turns skipsRedundantBringToFront off`() {
    forEachStrategy { strategy, _ ->
      val binder = newRecyclerBinder(realWindowRule.context)
      // Below the root, as in an app: incremental mount never unmounts the root item.
      val testLithoView =
          realWindowRule.render(componentTree = componentTree(enableFixForIM = true)) {
            Column { child(recycler(binder, strategy)) }
          }
      val list = testLithoView.mountedList()
      list.skipsRedundantBringToFront = true

      // The detach's empty-rect pass unmounts the list.
      testLithoView.detachFromWindow()

      assertThat(list.skipsRedundantBringToFront).describedAs("$strategy").isFalse
    }
  }

  @Test
  fun `ItemDecorator when set is respected`() {
    val itemDecoration: RecyclerView.ItemDecoration =
        object : RecyclerView.ItemDecoration() {
          override fun getItemOffsets(
              outRect: Rect,
              view: View,
              parent: RecyclerView,
              state: RecyclerView.State,
          ) {
            outRect.left = 10
          }
        }

    val testLithoView = lithoTestRule.render {
      Recycler(
          binder = recyclerBinder,
          itemDecorations = listOf(itemDecoration),
          style = Style.width(100.px).height(100.px),
      )
    }

    val sectionsRecyclerView =
        testLithoView.lithoView.getMountItemAt(0).content as SectionsRecyclerView

    assertThat(sectionsRecyclerView.recyclerView.itemDecorationCount).isEqualTo(1)
    assertThat(sectionsRecyclerView.recyclerView.getItemDecorationAt(0)).isEqualTo(itemDecoration)
  }

  @Test
  fun `RecyclerEventController when set is respected`() {
    val recyclerEventsController = RecyclerEventsController()
    val testLithoView = lithoTestRule.render {
      Recycler(
          binder = recyclerBinder,
          recyclerEventsController = recyclerEventsController,
          style = Style.width(100.px).height(100.px),
      )
    }

    val sectionsRecyclerView =
        testLithoView.lithoView.getMountItemAt(0).content as SectionsRecyclerView

    assertThat(recyclerEventsController.recyclerView).isEqualTo(sectionsRecyclerView.recyclerView)
  }

  @Test
  fun `ItemAnimator when set is respected`() {
    val itemAnimator = DefaultItemAnimator()
    val testLithoView = lithoTestRule.render {
      Recycler(
          binder = recyclerBinder,
          itemAnimator = itemAnimator,
          style = Style.width(100.px).height(100.px),
      )
    }

    val sectionsRecyclerView =
        testLithoView.lithoView.getMountItemAt(0).content as SectionsRecyclerView

    assertThat(sectionsRecyclerView.recyclerView.itemAnimator).isEqualTo(itemAnimator)
  }

  /** Runs [test] once per binder strategy, each on a fresh binder and empty content pools. */
  private fun forEachStrategy(test: (PrimitiveRecyclerBinderStrategy, RecyclerBinder) -> Unit) {
    for (strategy in PrimitiveRecyclerBinderStrategy.values()) {
      MountContentPools.clear()
      test(strategy, newRecyclerBinder())
    }
  }

  private fun newRecyclerBinder(context: ComponentContext = lithoTestRule.context): RecyclerBinder {
    val binder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(context, OrientationHelper.VERTICAL, false))
            .build(context)
    for (i in 0 until 10) {
      binder.insertItemAt(i, textRow(context))
    }
    return binder
  }

  private fun ResourcesScope.recycler(
      binder: RecyclerBinder,
      strategy: PrimitiveRecyclerBinderStrategy,
      color: Int = Color.RED,
  ) = Recycler(
      binder = binder,
      binderStrategy = strategy,
      refreshProgressBarColor = color,
      style = Style.width(100.px).height(100.px),
  )

  /** Sets [enableFixForIM] explicitly, since one of the runner's configurations turns it on. */
  private fun componentTree(enableFixForIM: Boolean): ComponentTree =
      ComponentTree.create(realWindowRule.context)
          .componentsConfiguration(
              ComponentsConfiguration.create(
                      realWindowRule.context.lithoConfiguration.componentsConfig,
                  )
                  .enableFixForIM(enableFixForIM)
                  .build(),
          )
          .build()

  private fun TestLithoView.mountedList(): SectionsRecyclerView =
      lithoView.getMountItemAt(0).content as SectionsRecyclerView

  private fun textRow(context: ComponentContext): RenderInfo =
      ComponentRenderInfo.create().component(Text.create(context).text("test")).build()

  private object NoOpChangeSetCompleteCallback : ChangeSetCompleteCallback {
    override fun onDataBound() = Unit

    override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) = Unit
  }
}
