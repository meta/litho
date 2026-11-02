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
import com.facebook.litho.animation.width
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.FlexboxScope
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.stateupdates.ComponentWithMeasureCall
import com.facebook.litho.stats.LithoStats
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.view.wrapInView
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope as PrimitiveLayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.rendercore.sp
import com.facebook.yoga.YogaAlign
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * State read while laying a container out reaches the next layout.
 *
 * With read tracking on, a state update skips layout entirely unless something recorded reading
 * that state. A read made only during layout is therefore invisible unless layout records it, and a
 * container that reads its own state or drops a child is where layout can lose one.
 */
@RunWith(LithoTestRunner::class)
class CustomLayoutStateReadTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  /**
   * A layout function that reads state lays its container out again when that state changes.
   *
   * The read happens only inside the layout function — a header that collapses against a scroll
   * offset reads it there — so nothing but layout can record it.
   */
  @Test
  fun `a layout function's state read lays its container out again`() {
    lateinit var offset: State<Int>

    class Collapsing : KComponent() {
      override fun ComponentScope.render(): Component {
        val state = useState { 0 }
        offset = state
        return Layout(
            listOf(SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))),
            layout =
                LayoutFunctionN { measurables, _ ->
                  val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
                  val y = state.value
                  layout(CHILD, CHILD + y) { placeable.place(x = 0, y = y) }
                },
        )
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Collapsing() }
    assertThat(containerOf(testView).getYForChildAtIndex(0)).isEqualTo(0)

    lithoTestRule.act(testView) { offset.updateSync(OFFSET) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).getYForChildAtIndex(0))
        .describedAs("placed at the new offset")
        .isEqualTo(OFFSET)
  }

  /**
   * A container whose size depends on a child grows when the child grows through state only the
   * child reads. The container attaches no Yoga children, so it relies on being marked dirty when
   * it is partly reused.
   */
  @Test
  fun `a leaf that grows through its own state grows its container`() {
    lateinit var h: State<Int>

    class Growing : KComponent() {
      override fun ComponentScope.render(): Component {
        val s = useState { CHILD }
        h = s
        return SimpleMountSpecTester(style = Style.width(CHILD.px).height(s.value.px))
      }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column(alignItems = YogaAlign.FLEX_START) {
            child(Layout(listOf(Growing()), layout = tallAsChild))
          }
        }
    lithoTestRule.act(testView) { h.updateSync(CHILD * 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).height).isEqualTo(CHILD * 2)
  }

  /** As above, with the child a container whose own height is read only in its layout function. */
  @Test
  fun `a nested container that grows through its own state grows its parent`() {
    lateinit var h: State<Int>

    class GrowingLayout : KComponent() {
      override fun ComponentScope.render(): Component {
        val s = useState { CHILD }
        h = s
        return Layout(
            listOf(SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))),
            layout =
                LayoutFunctionN { measurables, _ ->
                  val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
                  layout(CHILD, s.value) { placeable.place(0, 0) }
                },
        )
      }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column(alignItems = YogaAlign.FLEX_START) {
            child(Layout(listOf(GrowingLayout()), layout = tallAsChild))
          }
        }
    lithoTestRule.act(testView) { h.updateSync(CHILD * 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).height).isEqualTo(CHILD * 2)
  }

  /** State read only where children are placed lays the container out again too. */
  @Test
  fun `a state read only while placing lays the container out again`() {
    lateinit var offset: State<Int>

    class Sliding : KComponent() {
      override fun ComponentScope.render(): Component {
        val state = useState { 0 }
        offset = state
        return Layout(
            listOf(SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))),
            layout =
                LayoutFunctionN { measurables, constraints ->
                  val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
                  layout(constraints.maxWidth, CHILD * 2) {
                    placeable.place(x = 0, y = state.value)
                  }
                },
        )
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Sliding() }
    lithoTestRule.act(testView) { offset.updateSync(OFFSET) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).getYForChildAtIndex(0))
        .describedAs("placed at the new offset")
        .isEqualTo(OFFSET)
  }

  /**
   * A child the container leaves out because it does not fit is placed once it shrinks through
   * state only the child reads: whether it fits was decided from a measurement nothing placed.
   */
  @Test
  fun `a child left out because it did not fit is placed once its own state shrinks it`() {
    lateinit var w: State<Int>

    // Its width is read only while it is laid out, so only a layout records the read.
    class Sized : KComponent() {
      override fun ComponentScope.render(): Component {
        val s = useState { CHILD * 2 }
        w = s
        return Layout(
            listOf(SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))),
            layout =
                LayoutFunctionN { measurables, _ ->
                  val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
                  layout(s.value, CHILD) { placeable.place(0, 0) }
                },
        )
      }
    }
    val placesIfFits = LayoutFunctionN { measurables, constraints ->
      val placeable =
          measurables[0].measure(
              SizeConstraints(maxWidth = SizeConstraints.Infinity, maxHeight = CHILD),
          )
      layout(constraints.maxWidth, CHILD) { if (placeable.width <= CHILD) placeable.place(0, 0) }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Layout(listOf(Sized()), layout = placesIfFits)
        }
    assertThat(rootOf(testView).childCount).describedAs("too wide: left out").isEqualTo(0)

    lithoTestRule.act(testView) { w.updateSync(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(rootOf(testView).childCount).describedAs("fits now: placed").isEqualTo(1)
  }

  /**
   * A placement that fails for a state it read lays out again once that state changes, and
   * succeeds: the read is kept although the placement threw.
   */
  @Test
  fun `a placement that fails because of a state it read recovers when that state changes`() {
    lateinit var ready: State<Boolean>

    class Guarded : KComponent() {
      override fun ComponentScope.render(): Component {
        val state = useState { false }
        ready = state
        useErrorBoundary { _: Exception -> }
        return Layout(
            listOf(SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))),
            layout =
                LayoutFunctionN { measurables, constraints ->
                  val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
                  layout(constraints.maxWidth, CHILD) {
                    check(state.value) { "not ready" }
                    placeable.place(0, 0)
                  }
                },
        )
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Guarded() }
    assertThat(containerOf(testView).childCount).describedAs("failed: nothing placed").isEqualTo(0)

    lithoTestRule.act(testView) { ready.updateSync(true) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).childCount).describedAs("placed once ready").isEqualTo(1)
  }

  /**
   * A layout function that fails because of what a child measured, from state the child reads only
   * while laying out, lays out again once that state changes, and succeeds.
   */
  @Test
  fun `a function that fails on a child's state-driven size recovers when the state changes`() {
    lateinit var width: State<Int>

    class Host : KComponent() {
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD * 2 }
        width = state
        useErrorBoundary { _: Exception -> }
        return Layout(
            listOf(WidthFromState(state)),
            layout =
                LayoutFunctionN { measurables, constraints ->
                  val placeable =
                      measurables[0].measure(SizeConstraints(maxWidth = SizeConstraints.Infinity))
                  check(placeable.width <= CHILD) { "too wide" }
                  layout(constraints.maxWidth, CHILD) { placeable.place(0, 0) }
                },
        )
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(containerOf(testView).childCount).describedAs("failed: nothing placed").isEqualTo(0)

    lithoTestRule.act(testView) { width.updateSync(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).childCount).describedAs("placed once it fits").isEqualTo(1)
  }

  /**
   * A child left out because it did not fit, a Row whose leaf reads state only while laying out, is
   * placed once that state shrinks it: its last layout, which the layout cache keeps, is not taken
   * again as it was.
   */
  @Test
  fun `a Row child left out because it did not fit is placed once its leaf's state shrinks it`() {
    lateinit var width: State<Int>

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD * 2 }
        width = state
        return Layout(listOf(Row { child(WidthFromState(state)) }), layout = placesIfNarrow)
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(containerOf(testView).childCount).describedAs("too wide: left out").isEqualTo(0)

    lithoTestRule.act(testView) { width.update(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).childCount).describedAs("fits now: placed").isEqualTo(1)
  }

  /** As above, with the child deferred content holding the leaf. */
  @Test
  fun `a deferred child left out because it did not fit is placed once its content's state shrinks it`() {
    lateinit var width: State<Int>

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD * 2 }
        width = state
        return Layout(
            listOf(RenderWithConstraints { WidthFromState(state) }),
            layout = placesIfNarrow,
        )
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(containerOf(testView).childCount).describedAs("too wide: left out").isEqualTo(0)

    lithoTestRule.act(testView) { width.update(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).childCount).describedAs("fits now: placed").isEqualTo(1)
  }

  /**
   * A child left out renders no more often when state it read laying out changes than a placed
   * child does, and its container runs again to place it.
   */
  @Test
  fun `a child left out renders no more often on its state's update than a placed one`() {
    for (deferred in listOf(false, true)) {
      val placed = rendersOnUpdate(deferred, initialWidth = CHILD / 2)
      val leftOut = rendersOnUpdate(deferred, initialWidth = CHILD * 2)
      assertThat(leftOut.second).describedAs("function runs, deferred=$deferred").isGreaterThan(0)
      assertThat(leftOut.first)
          .describedAs("renders left out vs placed, deferred=$deferred")
          .isLessThanOrEqualTo(placed.first)
    }
  }

  /** The child's renders and the container's function runs across one update of its state. */
  private fun rendersOnUpdate(deferred: Boolean, initialWidth: Int): Pair<Int, Int> {
    lateinit var width: State<Int>
    var renders = 0

    @Suppress("KComponentStateInConstructorDetector")
    class Counted(private val state: State<Int>) : KComponent() {
      override fun ComponentScope.render(): Component {
        renders++
        return Row { child(WidthFromState(state)) }
      }
    }

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { initialWidth }
        width = state
        val child = if (deferred) RenderWithConstraints { Counted(state) } else Counted(state)
        return Layout(listOf(child), layout = placesIfNarrow)
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    renders = 0
    LithoStats.resetAllCounters()
    lithoTestRule.act(testView) { width.update(CHILD / 4) }
    lithoTestRule.idle()
    assertThat(containerOf(testView).childCount).describedAs("placed after").isEqualTo(1)
    return renders to LithoStats.customLayoutFunctionRunCount.get().toInt()
  }

  /**
   * A container whose own function reads state keeps its placed child's last layout when that state
   * changes: the child is taken from the cache, not laid out again.
   */
  @Test
  fun `a container reading its own state keeps its placed child's cached layout`() {
    lateinit var offset: State<Int>

    class Collapsing : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { 0 }
        offset = state
        return Layout(
            listOf(
                Row {
                  child(SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px)))
                },
            ),
            layout =
                LayoutFunctionN { measurables, _ ->
                  val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
                  val y = state.value
                  layout(CHILD, CHILD + y) { placeable.place(x = 0, y = y) }
                },
        )
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Collapsing() }
    lithoTestRule.act(testView) { offset.update(OFFSET) }
    lithoTestRule.idle()

    val container = containerOf(testView)
    assertThat(container.getYForChildAtIndex(0)).describedAs("laid out again").isEqualTo(OFFSET)
    assertThat(container.getChildAt(0).isCachedLayout).describedAs("from the cache").isTrue()
  }

  /** Places its only child, measured unbounded, only if it is at most [CHILD] wide. */
  private val placesIfNarrow = LayoutFunctionN { measurables, constraints ->
    val placeable =
        measurables[0].measure(
            SizeConstraints(maxWidth = SizeConstraints.Infinity, maxHeight = CHILD),
        )
    layout(constraints.maxWidth, CHILD) { if (placeable.width <= CHILD) placeable.place(0, 0) }
  }

  private fun rootOf(testView: TestLithoView): LithoLayoutResult =
      checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
          as LithoLayoutResult

  private fun containerOf(testView: TestLithoView): LithoLayoutResult {
    val root =
        checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
            as LithoLayoutResult
    return checkNotNull(findContainer(root)) { "container not found" }
  }

  private fun findContainer(result: LithoLayoutResult): LithoLayoutResult? {
    if (result.node.tailComponent is Layout) return result
    for (i in 0 until result.childCount) {
      findContainer(result.getChildAt(i))?.let {
        return it
      }
    }
    return null
  }

  @Test
  fun `a child's read while it lays out is recorded under the child's own key`() {
    var width: State<Int>? = null
    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD }
        width = state
        return Layout(listOf(WidthFromState(state)), layout = tallAsChild)
      }
    }
    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }

    val leaf = containerOf(testView).getChildAt(0)
    val readers =
        checkNotNull(testView.componentTree.mainThreadLayoutState?.stateReads)
            .getValue(checkNotNull(width).stateId)
    assertThat(readers.contains(leaf.node.tailComponentKey))
        .describedAs("the leaf is recorded as the state's reader")
        .isTrue()
  }

  /**
   * A nested `Layout` measured but not placed keeps its own unplaced measurements' readers: here
   * the only record of the leaf's read, since the measurement placed asks the inner `Layout` under
   * room where it measures nothing.
   */
  @Test
  fun `a nested Layout measured but not placed keeps its unplaced child's readers`() {
    lateinit var width: State<Int>

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD * 2 }
        width = state
        val inner = Layout(listOf(Row { child(WidthFromState(state)) }), layout = measuresIfRoomy)
        return Layout(listOf(inner), layout = placesIfRoomyAnswerFits)
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(containerOf(testView).childCount).describedAs("too wide: left out").isEqualTo(0)

    lithoTestRule.act(testView) { width.update(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).childCount).describedAs("fits now: placed").isEqualTo(1)
  }

  /**
   * As above, with the inner `Layout` inside a `Column` its parent leaves out: discarding the
   * `Column`'s tree discards the inner `Layout` through its builder, which adds its unplaced
   * child's read.
   */
  @Test
  fun `a nested Layout in a discarded Yoga tree keeps its unplaced child's readers`() {
    lateinit var width: State<Int>

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD * 2 }
        width = state
        val inner = Layout(listOf(Row { child(WidthFromState(state)) }), layout = measuresIfRoomy)
        return Layout(listOf(Column { child(inner) }), layout = placesIfRoomyAnswerFits)
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(containerOf(testView).childCount).describedAs("too wide: left out").isEqualTo(0)

    lithoTestRule.act(testView) { width.update(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).childCount).describedAs("fits now: placed").isEqualTo(1)
  }

  /**
   * As above, with deferred content beside the inner `Layout`, so each tree made for the `Column`
   * keeps its builder, and with neither of the `Column`'s measurements placed: discarding that kept
   * builder adds its unplaced child's read.
   */
  @Test
  fun `a kept Layout in a discarded Yoga tree keeps its unplaced child's readers`() {
    lateinit var width: State<Int>
    val sizedByRoomyLeftOut = LayoutFunctionN { measurables, _ ->
      val roomy = measurables[0].measure(SizeConstraints(maxWidth = ROOMY, maxHeight = CHILD))
      measurables[0].measure(SizeConstraints(maxWidth = CHILD, maxHeight = CHILD))
      layout(roomy.width, CHILD) {}
    }

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD * 2 }
        width = state
        val inner = Layout(listOf(Row { child(WidthFromState(state)) }), layout = measuresIfRoomy)
        val deferred = RenderWithConstraints { Column(style = Style.width(10.px).height(10.px)) }
        return Row {
          child(
              Layout(
                  listOf(
                      Column {
                        child(deferred)
                        child(inner)
                      },
                  ),
                  layout = sizedByRoomyLeftOut,
              ),
          )
        }
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(containerOf(testView).width).describedAs("too wide: the content's").isEqualTo(10)

    lithoTestRule.act(testView) { width.update(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).width).describedAs("fits now").isEqualTo(CHILD / 2)
  }

  /**
   * As above, with the inner `Layout` measuring its child at every size: the measurement placed
   * also holds the leaf's read.
   */
  @Test
  fun `a nested Layout measured twice keeps its child's readers`() {
    lateinit var width: State<Int>

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD * 2 }
        width = state
        val inner = Layout(listOf(Row { child(WidthFromState(state)) }), layout = placesIfNarrow)
        return Layout(listOf(inner), layout = measuresTwicePlacesSecond)
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(leafPlaced(testView)).describedAs("too wide: left out").isFalse()

    lithoTestRule.act(testView) { width.update(CHILD / 2) }
    lithoTestRule.idle()

    assertThat(leafPlaced(testView)).describedAs("fits now: placed").isTrue()
  }

  /**
   * A leaf whose box props read state (its width, from a State read as the props are recorded)
   * holds that read itself: an update lays it out again at the new width without its owner
   * rendering again.
   */
  @Test
  fun `a leaf's box props that read state lay it out again when the state changes`() {
    lateinit var width: State<Dimen>
    var renders = 0

    class Host : KComponent() {
      @Suppress("KComponentUncachedAllocationDetector")
      override fun ComponentScope.render(): Component {
        renders++
        val state = useState<Dimen> { 30.px }
        width = state
        return Layout(
            listOf(SimpleMountSpecTester(style = Style.width(state).height(20.px))),
            layout = tallAsChild,
        )
      }
    }

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { Host() }
    assertThat(containerOf(testView).getChildAt(0).width).describedAs("first pass").isEqualTo(30)

    lithoTestRule.act(testView) { width.update(60.px) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).getChildAt(0).width)
        .describedAs("after the update")
        .isEqualTo(60)
    assertThat(renders).describedAs("a layout-only update").isEqualTo(1)
  }

  /**
   * With subtree caching on, a `Column` of `Text`s inside a `Layout`'s child is not spliced from
   * the last pass: a splice replays only Spec components' reads, and the leaf beside them reads its
   * width while it lays out. A `Column` of `Text`s outside any `Layout` is spliced.
   */
  @Test
  fun `a subtree splice does not drop a read inside a Layout's child`() {
    withSubtreeCaching {
      val leaf = StateLeaf()
      val testView =
          lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
            Column {
              child(Layout(listOf(Column { textsAnd(threeTexts(), leaf) }), layout = tallAsChild))
              child(Column { textsAnd(threeTexts(), null) })
              child(Bump())
            }
          }

      assertSplicedElsewhereThenLaidOutAgain(testView)
    }
  }

  /** As above, with the `Column` of `Text`s holding the `Layout`. */
  @Test
  fun `a subtree splice does not drop a read inside a Layout it holds`() {
    withSubtreeCaching {
      val leaf = StateLeaf()
      val testView =
          lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
            Column {
              child(
                  Column {
                    textsAnd(threeTexts(), null)
                    child(Layout(listOf(leaf), layout = tallAsChild))
                  },
              )
              child(Column { textsAnd(threeTexts(), null) })
              child(Bump())
            }
          }

      assertSplicedElsewhereThenLaidOutAgain(testView)
    }
  }

  private var bump: State<Int>? = null

  /** A sibling with its own state: updating it keeps every other node. */
  private inner class Bump : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useState { 0 }
      bump = state
      return Column(style = Style.width((10 + state.value).px).height(10.px))
    }
  }

  private var stateLeafWidth: State<Int>? = null

  /** A leaf owning the state it reads only while it lays out: its render reads nothing. */
  private inner class StateLeaf : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useState { CHILD }
      stateLeafWidth = state
      return WidthFromState(state)
    }
  }

  /** Three `Text`s: Spec components, enough scopes for a subtree snapshot. */
  private fun ResourcesScope.threeTexts(): List<Component> =
      List(3) { i -> Text(text = "text $i", textSize = 10.sp) }

  /** [texts], then [leaf] if given. */
  private fun FlexboxScope.textsAnd(texts: List<Component>, leaf: Component?) {
    texts.forEach { child(it) }
    leaf?.let { child(it) }
  }

  private fun assertSplicedElsewhereThenLaidOutAgain(testView: TestLithoView) {
    LithoStats.resetAllCounters()
    lithoTestRule.act(testView) { checkNotNull(bump).update(1) }
    lithoTestRule.idle()
    assertThat(LithoStats.subtreeCacheHitCount.get())
        .describedAs("the Column outside the Layout is spliced")
        .isGreaterThan(0)

    lithoTestRule.act(testView) { checkNotNull(stateLeafWidth).update(CHILD * 2) }
    lithoTestRule.idle()

    assertThat(findLeaf(rootOf(testView))?.width)
        .describedAs("the leaf laid out again with its new width")
        .isEqualTo(CHILD * 2)
  }

  /**
   * State that a deferred child's content read while it rendered, for a measurement its container
   * left out, is its container's own: updating it lays the container out again.
   */
  @Test
  fun `an unplaced deferred child's render reads are its Layout's own`() {
    var readerState: State<Int>? = null

    class Reader : KComponent() {
      override fun ComponentScope.render(): Component {
        val size = useState { 10 }
        readerState = size
        return Column(style = Style.width(size.value.px).height(size.value.px))
      }
    }

    // As wide as its unplaced child measures, so it's only right if it runs again.
    val sizedByProbe = LayoutFunctionN { measurables, constraints ->
      val probe =
          measurables[0].measure(
              SizeConstraints(maxWidth = constraints.maxWidth, maxHeight = CHILD),
          )
      layout(probe.width, CHILD) {}
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Row {
            child(Layout(listOf(RenderWithConstraints { Reader() }), layout = sizedByProbe))
          }
        }

    assertThat(
        checkNotNull(containerOf(testView).stateReads).contains(checkNotNull(readerState).stateId),
    )
        .describedAs("the Layout's reads hold the reader's state")
        .isTrue()
    assertThat(containerOf(testView).width).describedAs("as wide as the reader").isEqualTo(10)

    lithoTestRule.act(testView) { checkNotNull(readerState).update(20) }
    lithoTestRule.idle()

    assertThat(containerOf(testView).width).describedAs("laid out again").isEqualTo(20)
  }

  @Test
  fun `content measured at render reads its state after an unrelated sibling update`() {
    var width: State<Int>? = null

    class StateWidth : KComponent() {
      override fun ComponentScope.render(): Component {
        val state = useState { CHILD }
        width = state
        return Column(
            style = Style.width(state.value.px).height(CHILD.px).wrapInView().viewTag("measured"),
        )
      }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column(alignItems = YogaAlign.FLEX_START) {
            child(
                Layout(
                    listOf(
                        Row {
                          child(
                              ComponentWithMeasureCall.create(lithoTestRule.context)
                                  .component(StateWidth())
                                  .widthSpec(SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.AT_MOST))
                                  .shouldCacheResult(true)
                                  .build(),
                          )
                        },
                    ),
                    layout = tallAsChild,
                ),
            )
            child(Bump())
          }
        }
    assertThat(testView.lithoView.findViewWithTag<View>("measured").width).isEqualTo(CHILD)

    lithoTestRule.act(testView) { checkNotNull(bump).update(1) }
    assertThat(testView.lithoView.findViewWithTag<View>("measured").width).isEqualTo(CHILD)

    lithoTestRule.act(testView) { checkNotNull(width).update(CHILD * 2) }
    assertThat(testView.lithoView.findViewWithTag<View>("measured").width).isEqualTo(CHILD * 2)
  }

  private inline fun withSubtreeCaching(block: () -> Unit) {
    val cachingWas = ComponentsConfiguration.enableSubtreeCaching
    ComponentsConfiguration.enableSubtreeCaching = true
    try {
      block()
    } finally {
      ComponentsConfiguration.enableSubtreeCaching = cachingWas
    }
  }

  private fun findLeaf(result: LithoLayoutResult): LithoLayoutResult? {
    if (result.node.tailComponent is WidthFromState) return result
    for (i in 0 until result.childCount) {
      findLeaf(result.getChildAt(i))?.let {
        return it
      }
    }
    return null
  }

  private fun leafPlaced(testView: TestLithoView): Boolean = findLeaf(rootOf(testView)) != null

  /**
   * Measures its only child unbounded only with room for [ROOMY], and places it only if it is at
   * most [CHILD] wide. It declares the width placed: none when it places nothing.
   */
  private val measuresIfRoomy = LayoutFunctionN { measurables, constraints ->
    val placeable =
        if (constraints.maxWidth >= ROOMY) {
          measurables[0].measure(
              SizeConstraints(maxWidth = SizeConstraints.Infinity, maxHeight = CHILD),
          )
        } else null
    val fits = placeable != null && placeable.width <= CHILD
    layout(if (fits) checkNotNull(placeable).width else 0, CHILD) {
      if (fits) checkNotNull(placeable).place(0, 0)
    }
  }

  /**
   * Measures its only child with room for [ROOMY], left unplaced, then within [CHILD]; places the
   * second only if the first declared some width.
   */
  private val placesIfRoomyAnswerFits = LayoutFunctionN { measurables, constraints ->
    val roomy = measurables[0].measure(SizeConstraints(maxWidth = ROOMY, maxHeight = CHILD))
    val narrow = measurables[0].measure(SizeConstraints(maxWidth = CHILD, maxHeight = CHILD))
    layout(constraints.maxWidth, CHILD) { if (roomy.width > 0) narrow.place(0, 0) }
  }

  /** Measures its only child at two widths, and places the second. */
  private val measuresTwicePlacesSecond = LayoutFunctionN { measurables, constraints ->
    measurables[0].measure(SizeConstraints(maxWidth = ROOMY, maxHeight = CHILD))
    val second = measurables[0].measure(SizeConstraints(maxWidth = ROOMY + 1, maxHeight = CHILD))
    layout(constraints.maxWidth, CHILD) { second.place(0, 0) }
  }

  companion object {
    private const val VIEWPORT = 1000
    private const val CHILD = 100
    private const val OFFSET = 30
    private const val ROOMY = 400
  }

  /** As tall and wide as its only child, measured without a height bound. */
  private val tallAsChild = LayoutFunctionN { measurables, constraints ->
    val placeable =
        measurables[0].measure(
            SizeConstraints(0, constraints.maxWidth, 0, SizeConstraints.Infinity),
        )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
  }
}

/** A Primitive as wide as [width] says, read only while it lays out. */
private class WidthFromState(private val width: State<Int>) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
      layoutBehavior = WidthFromStateBehavior(width),
      mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
      style = null,
  )
}

private class WidthFromStateBehavior(private val width: State<Int>) : LayoutBehavior {
  override fun PrimitiveLayoutScope.layout(
      sizeConstraints: SizeConstraints,
  ): PrimitiveLayoutResult = PrimitiveLayoutResult(width = width.value, height = 100)
}
