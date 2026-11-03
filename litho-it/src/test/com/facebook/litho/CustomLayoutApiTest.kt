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

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.aspectRatio
import com.facebook.litho.flexbox.border
import com.facebook.litho.flexbox.flex
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.flexbox.useHeightAsBaseline
import com.facebook.litho.layout.LayoutDirection
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.Measurable
import com.facebook.litho.layout.MeasureScope
import com.facebook.litho.layout.Placeable
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.stateupdates.ComponentWithMeasureCall
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.assertj.LithoAssertions
import com.facebook.litho.testing.error.TestErrorBoundary
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.MountSpecLifecycleTester
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.litho.widget.Text
import com.facebook.rendercore.LayoutCache
import com.facebook.rendercore.LayoutContext
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope as PrimitiveLayoutScope
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaConfigFactory
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaErrata
import com.facebook.yoga.YogaExperimentalFeature
import com.facebook.yoga.YogaNodeFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the guarantees a [LayoutFunction] may rely on.
 *
 * One test per guarantee, so each can be checked on its own rather than through a general
 * impression that layout works.
 */
@RunWith(LithoTestRunner::class)
class CustomLayoutApiTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  // ─────────────────────────────────────────────── measuring more than once

  /**
   * Measuring a child twice is what makes flexbox expressible: CSS Flexbox §9.7 resolves flexible
   * lengths by measuring at the flex basis, distributing free space, then measuring again at the
   * resolved size.
   */
  @Test
  fun `a child can be measured more than once in one pass`() {
    val measures = AtomicInteger(0)

    val layout = LayoutFunctionN { measurables, _ ->
      measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      measures.incrementAndGet()
      val second = measurables[0].measure(SizeConstraints.exact(CHILD * 2, CHILD))
      measures.incrementAndGet()
      layout(second.width, second.height) { second.place(0, 0) }
    }

    val result =
        renderAndFindContainer(container(childCount = 1, layout = layout, contentSized = true))

    // More than two: the enclosing Column stretches the container, so the layout function runs
    // again at the size it actually got. That fallback is pinned on its own in `a size Yoga
    // overrides is measured again`; here all that matters is the second measure was not refused.
    assertThat(measures.get()).describedAs("both measures ran").isGreaterThanOrEqualTo(2)
    // The child's width, not the container's: the wrapping Column stretches the container to
    // VIEWPORT whatever the layout function returned, so only the child shows what changed.
    assertThat(result.getChildAt(0).width)
        .describedAs("the second measure is the one that stands")
        .isEqualTo(CHILD * 2)
  }

  // ─────────────────────────────────────────────── a child's own box

  /**
   * A child's aspect ratio decides its other axis, as it does in a Column.
   *
   * Yoga applies an aspect ratio only when a parent lays a child out, and a container's child is
   * laid out as a root of its own, so without resolving it here the ratio was ignored and the child
   * came back at its content height. Both ways of fixing the first axis are covered: an exact width
   * asked for, and a width the child declares.
   */
  @Test
  fun `a child's aspect ratio decides the axis the constraints leave open`() {
    val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
    val layout = LayoutFunctionN { measurables, _ ->
      val exact = measurables[0].measure(SizeConstraints(minWidth = 200, maxWidth = 200))
      val declared = measurables[1].measure(SizeConstraints())
      sizes.add(exact.width to exact.height)
      sizes.add(declared.width to declared.height)
      layout(200, exact.height + declared.height) {
        exact.place(0, 0)
        declared.place(0, exact.height)
      }
    }

    renderAndFindContainer(
        Layout(
            listOf(
                Column(style = Style.aspectRatio(2f)),
                Column(style = Style.width(120.px).aspectRatio(3f)),
            ),
            layout = layout,
        ),
    )

    assertThat(sizes.last { true })
        .describedAs("declared width 120 at ratio 3")
        .isEqualTo(120 to 40)
    assertThat(sizes[sizes.size - 2])
        .describedAs("exact width 200 at ratio 2")
        .isEqualTo(200 to 100)
  }

  // ─────────────────────────────────────────────── place() on Placeable

  /**
   * `place` is an extension on the placeable, so the index travels with it rather than being
   * supplied by the caller. That rules out placing placeable *i* at index *j*.
   *
   * Placed in reverse, so the slot each position lands in cannot come from the order of the calls.
   */
  @Test
  fun `a placeable is placed at its own index`() {
    val layout = LayoutFunctionN { measurables, constraints ->
      val placeables = measurables.map { it.measure(SizeConstraints.exact(CHILD, CHILD)) }
      layout(constraints.maxWidth, CHILD) {
        placeables.withIndex().reversed().forEach { (i, placeable) ->
          placeable.place(x = i * STRIDE, y = 0)
        }
      }
    }

    val result = renderAndFindContainer(container(childCount = 3, layout = layout))
    val xByDeclaredIndex =
        (0 until result.childCount).associate { slot ->
          val declaredIndex =
              (0 until result.node.childCount).single { index ->
                result.node.getChildAt(index) === result.getChildAt(slot).node
              }
          declaredIndex to result.getXForChildAtIndex(slot)
        }

    assertThat(xByDeclaredIndex)
        .describedAs("each placeable placed itself, at its own declared index")
        .containsExactlyInAnyOrderEntriesOf(mapOf(0 to 0, 1 to STRIDE, 2 to STRIDE * 2))
  }

  // ─────────────────────────────────────────────── placeRelative

  /** `place` is absolute; `placeRelative` mirrors under a right-to-left layout direction. */
  @Test
  fun `placeRelative mirrors under RTL`() {
    val layout = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.placeRelative(start = 0, top = 0) }
    }

    assertThat(renderAndFindContainer(container(1, layout)).getXForChildAtIndex(0))
        .describedAs("LTR: the leading edge is the left one")
        .isEqualTo(0)

    val rtl = container(1, layout, style = Style.layoutDirection(YogaDirection.RTL))
    assertThat(renderAndFindContainer(rtl).getXForChildAtIndex(0))
        .describedAs("RTL: the leading edge is the right one")
        .isEqualTo(VIEWPORT - CHILD)
  }

  /**
   * The mirroring axis is the container's own width, not the width its layout function asked for.
   *
   * The two differ whenever Yoga overrides the size — here the enclosing Column's `alignItems:
   * stretch` widens the container to the viewport after the function asked for `CHILD`. Mirroring
   * against the requested width puts a child placed at the leading edge on the *left* under RTL,
   * which is the one thing `placeRelative` exists to prevent.
   */
  @Test
  fun `placeRelative mirrors against the width the container ends up with`() {
    val layout = LayoutFunctionN { measurables, _ ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(CHILD, CHILD) { placeable.placeRelative(start = 0, top = 0) }
    }

    val result =
        renderAndFindContainer(container(1, layout, Style.layoutDirection(YogaDirection.RTL)))

    assertThat(result.width)
        .describedAs("Yoga stretched the container past what it asked for")
        .isEqualTo(VIEWPORT)
    assertThat(result.getXForChildAtIndex(0))
        .describedAs("so the leading edge is the viewport's right edge, not CHILD's")
        .isEqualTo(VIEWPORT - CHILD)
  }

  /**
   * RTL reaches a container that never declares it.
   *
   * The tests above put the direction on the container itself; a locale puts it on an ancestor and
   * lets it inherit. That path runs through `writeNodePropsToYogaNode` rather than Yoga's own
   * inheritance, because a container's children are laid out as detached roots, which inherit from
   * nothing.
   */
  @Test
  fun `placeRelative mirrors under an inherited RTL`() {
    val layout = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.placeRelative(start = 0, top = 0) }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column(style = Style.layoutDirection(YogaDirection.RTL)) {
            child(container(1, layout))
          }
        }
    val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
    val result = checkNotNull(findContainer(root as LithoLayoutResult))

    assertThat(result.getXForChildAtIndex(0))
        .describedAs("the container inherited RTL without declaring it")
        .isEqualTo(VIEWPORT - CHILD)
  }

  // ─────────────────────────────────────────────── padding and border

  /**
   * A container lays out inside its padding and border, as a `Column` does.
   *
   * Yoga hands the measure function the space inside them and adds them back to the size it
   * reports, so the layout function works in that space. The placement pass has to agree: measure
   * against the same space when it runs the function again, and offset what it places by the
   * leading insets, since a child's position is read from the container's outer edge.
   */
  @Test
  fun `padding and border are outside the space a container lays out in`() {
    val widthsSeen = CopyOnWriteArrayList<Int>()

    // Asks for CHILD, so the Column's stretch overrides it and the placement pass runs it again.
    val layout = LayoutFunctionN { measurables, constraints ->
      widthsSeen.add(constraints.maxWidth)
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(CHILD, CHILD) { placeable.place(0, 0) }
    }

    val result = renderAndFindContainer(container(1, layout, PADDED_AND_BORDERED))

    assertThat(widthsSeen)
        .describedAs("measured by Yoga, then again at the settled size, both inside the insets")
        .containsExactly(VIEWPORT - INSET_H, VIEWPORT - INSET_H)
    assertThat(result.height)
        .describedAs("the insets are added to what the function asked for")
        .isEqualTo(CHILD + INSET_V)
    assertThat(result.getXForChildAtIndex(0))
        .describedAs("placed at the content box's left edge")
        .isEqualTo(PAD_LEFT + BORDER)
    assertThat(result.getYForChildAtIndex(0))
        .describedAs("and its top edge")
        .isEqualTo(PAD_TOP + BORDER)
  }

  /**
   * Padding does not cost a second run. The size the function asked for is compared with the space
   * inside the insets, which is what it was asked about, so a size Yoga left alone still matches.
   */
  @Test
  fun `padding does not make a container lay out twice`() {
    val runs = AtomicInteger(0)

    val layout = LayoutFunctionN { measurables, constraints ->
      runs.incrementAndGet()
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.place(0, 0) }
    }

    renderAndFindContainer(container(1, layout, PADDED_AND_BORDERED))

    assertThat(runs.get()).describedAs("the measure function's run was reused").isEqualTo(1)
  }

  /** Under RTL the leading edge is the content box's right edge, inside the right inset. */
  @Test
  fun `placeRelative mirrors inside the padding and border`() {
    val layout = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.placeRelative(start = 0, top = 0) }
    }

    val rtl = container(1, layout, PADDED_AND_BORDERED.layoutDirection(YogaDirection.RTL))

    assertThat(renderAndFindContainer(rtl).getXForChildAtIndex(0))
        .isEqualTo(VIEWPORT - PAD_RIGHT - BORDER - CHILD)
  }

  /**
   * A container that places its child where a `Column` would ends up with the Column's geometry,
   * whatever the insets and the direction.
   *
   * Bounds are compared at every depth in absolute coordinates, so an inset dropped, applied twice
   * or applied to the wrong edge shows up as a mismatch without an assertion of its own. The insets
   * are given as `start` and `end`, so under RTL they also have to resolve to the other side.
   *
   * Each test reaches the layout function by a different route, and each route works out the
   * content box separately.
   */
  @Test
  fun `a stretched container honours its insets as a Column does`() {
    // Stretched by the enclosing Column: measured at an exact width, then run again at the settled
    // size by the placement pass.
    assertMatchesColumn { it }
  }

  @Test
  fun `a content-sized container honours its insets as a Column does`() {
    // Inside a Row the width is open, so Yoga asks the intrinsic question instead.
    assertMatchesColumn { Row { child(it) } }
  }

  @Test
  fun `a container of definite size honours its insets as a Column does`() {
    // Yoga never calls the measure function on a definite size, so only the placement pass runs
    // the layout function.
    assertMatchesColumn(extra = Style.width(DEFINITE.px).height(DEFINITE.px)) { it }
  }

  @Test
  fun `nested containers each honour their insets as nested Columns do`() {
    // The inner container is laid out as a detached root by the outer one's measurable.
    assertMatchesColumn(depth = 2) { Row { child(it) } }
  }

  /**
   * A child's own content mirrors under RTL too, not just where the container puts the child.
   *
   * Each child is laid out as a detached Yoga root, which inherits no direction from a parent it
   * does not have, so the direction has to be on the child's own node. The leaf here is a Row wider
   * than its content, so under RTL its content sits at the Row's right edge only if the Row itself
   * was laid out right-to-left.
   */
  @Test
  fun `a child's own content mirrors under RTL as it does under a Column`() {
    assertMatchesColumn(leaf = { Row(style = Style.width(DEFINITE.px)) { child(fixedLeaf()) } }) {
      it
    }
  }

  /**
   * A child's margins are part of the box it is measured and placed as, as they are in a Column.
   *
   * The framework applies them, so a layout function knows nothing about them: the constraints a
   * child is measured under exclude them, its [Placeable] reports the size including them, and its
   * content lands inside them. Different on every edge and given as start and end, so a margin on
   * the wrong edge, or not mirrored under RTL, shows up as a mismatch.
   */
  @Test
  fun `a child's margins are honoured as a Column honours them`() {
    assertMatchesColumn(leaf = ::marginedLeaf) { it }
    assertMatchesColumn(leaf = ::marginedLeaf) { Row { child(it) } }
  }

  /**
   * A child that fills the width it is offered keeps its margins inside that width, once.
   *
   * The Column-parity margin test uses a fixed-size child, which is laid out under an exact width
   * and never reaches the bounded path. Here the child takes whatever width it is given, and the
   * container asks it with only an upper bound, as a Column wrapping its child does. The margins
   * must come off that bound once: taken off before Yoga is asked and again by Yoga itself, the
   * child comes back narrower than the one inside a Column by the margins a second time.
   */
  @Test
  fun `a child that fills a bounded width keeps its margins once`() {
    assertMatchesColumn(extra = Style.width(CONTAINER.px), leaf = ::fillingMarginedLeaf) { it }
  }

  /**
   * A percent margin resolves against the width the child is offered, as it does in a Column, even
   * when that width is only an upper bound.
   *
   * Sized so the margin comes to whole pixels. A fraction would be rounded from the child's own
   * origin, since a container lays each child out as a Yoga root of its own, where a Column rounds
   * it along with the rest of the tree; the two can then differ by a pixel.
   */
  @Test
  fun `a percent margin resolves against a bounded width as a Column resolves it`() {
    assertMatchesColumn(
        extra = Style.width(WHOLE_PERCENT_CONTAINER.px),
        leaf = ::percentMarginedLeaf,
    ) {
      it
    }
  }

  /**
   * A percent margin is resolved again for each size a child is offered, not kept from the first.
   *
   * Measured unbounded first, as an intrinsic query does, where a percentage of an undefined width
   * resolves to nothing. Kept, that nothing would stand for the bounded measurement too: the child
   * would be placed over its own margin.
   */
  @Test
  fun `a percent margin is resolved again under each bound`() {
    val layout = LayoutFunctionN { measurables, _ ->
      measurables[0].measure(SizeConstraints(maxHeight = CHILD))
      val placeable =
          measurables[0].measure(SizeConstraints(maxWidth = PERCENT_BASE, maxHeight = CHILD))
      layout(PERCENT_BASE, CHILD) { placeable.place(0, 0) }
    }

    val result = renderAndFindContainer(Layout(listOf(percentMarginedLeaf()), layout = layout))

    assertThat(result.getXForChildAtIndex(0))
        .describedAs("placed inside a leading margin of %s%% of the bound", MARGIN_PERCENT)
        .isEqualTo((PERCENT_BASE * MARGIN_PERCENT / 100).toInt())
  }

  private fun fillingMarginedLeaf(): Component =
      MountSpecLifecycleTester.create(lithoTestRule.context)
          .lifecycleTracker(LifecycleTracker())
          .heightPx(CHILD)
          .marginPx(YogaEdge.START, MARGIN_START)
          .marginPx(YogaEdge.END, MARGIN_END)
          .marginPx(YogaEdge.TOP, MARGIN_TOP)
          .build()

  private fun percentMarginedLeaf(): Component =
      MountSpecLifecycleTester.create(lithoTestRule.context)
          .lifecycleTracker(LifecycleTracker())
          .heightPx(CHILD)
          .marginPercent(YogaEdge.START, MARGIN_PERCENT)
          .marginPercent(YogaEdge.END, MARGIN_PERCENT)
          .build()

  private fun marginedLeaf(): Component = SimpleMountSpecTester(
      style =
          Style.width(CHILD.px)
              .height(CHILD.px)
              .margin(
                  start = MARGIN_START.px,
                  top = MARGIN_TOP.px,
                  end = MARGIN_END.px,
                  bottom = MARGIN_BOTTOM.px,
              ),
  )

  private fun assertMatchesColumn(
      extra: Style = Style,
      depth: Int = 1,
      leaf: () -> Component = ::fixedLeaf,
      around: (Component) -> Component,
  ) {
    val style = RELATIVE_INSETS + extra
    for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
      var column: Component = leaf()
      var container: Component = leaf()
      repeat(depth) {
        val columnChild = column
        val containerChild = container
        column = Column(style = style) { child(columnChild) }
        container = Layout(listOf(containerChild), style = style, layout = PLACE_LIKE_A_COLUMN)
      }

      assertThat(boundsAlongFirstChild(around(container), direction))
          .describedAs("$direction")
          .isEqualTo(boundsAlongFirstChild(around(column), direction))
    }
  }

  private fun fixedLeaf(): Component =
      SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))

  private fun boundsAlongFirstChild(component: Component, direction: YogaDirection): List<Rect> {
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column(style = Style.layoutDirection(direction)) { child(component) }
        }
    var result =
        checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
            as LithoLayoutResult
    var x = 0
    var y = 0
    val bounds = ArrayList<Rect>()
    while (true) {
      bounds.add(Rect(x, y, x + result.width, y + result.height))
      if (result.childCount == 0) {
        return bounds
      }
      x += result.getXForChildAtIndex(0)
      y += result.getYForChildAtIndex(0)
      result = result.getChildAt(0)
    }
  }

  // ─────────────────────────────────────────────── measurement is a commitment

  /**
   * Returning to constraints asked before an intervening question answers from that earlier
   * measurement, but the child is still laid out at the constraints it is placed with. Its Yoga
   * node holds the intervening layout by then, so placing the recalled answer lays it out again
   * under the recalled constraints.
   */
  @Test
  fun `returning to earlier constraints lays the child out at the ones it is placed with`() {
    val layout = LayoutFunctionN { measurables, _ ->
      measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      measurables[0].measure(SizeConstraints.exact(CHILD * 2, CHILD))
      val back = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(CHILD, CHILD) { back.place(0, 0) }
    }

    val result = renderAndFindContainer(container(childCount = 1, layout = layout))

    assertThat(result.getChildAt(0).width)
        .describedAs("laid out at the constraints it was placed with, not the intervening ones")
        .isEqualTo(CHILD)
  }

  /** A leaf of fixed area, as tall as that area divided by the width it is given. */
  private class FixedAreaLeaf : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
        layoutBehavior = FixedAreaLayoutBehavior,
        mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
        style = null,
    )
  }

  private object FixedAreaLayoutBehavior : LayoutBehavior {
    override val hasIntrinsicSize: Boolean
      get() = true

    override fun PrimitiveLayoutScope.layout(
        sizeConstraints: SizeConstraints,
    ): PrimitiveLayoutResult {
      val width = if (sizeConstraints.hasBoundedWidth) sizeConstraints.maxWidth else AREA_WIDTH
      return PrimitiveLayoutResult(width = width, height = AREA / maxOf(width, 1))
    }
  }

  private fun fixedNarrowLeaf(): Component =
      SimpleMountSpecTester(style = Style.width(NARROW.px).height(CHILD.px))

  // ─────────────────────────────────────────────── container intrinsics

  /**
   * Past three children the named constructors stop, and the list form takes over.
   *
   * The list form is typed to [LayoutFunctionN], so it claims nothing about how many children
   * arrive, which is what lets it accept any count. Names come back by destructuring the
   * measurables it is handed, for which the standard library supplies `component1()` through
   * `component5()`.
   */
  @Test
  fun `four children go through the list form, named by destructuring`() {
    val stack = LayoutFunctionN { measurables, constraints ->
      val (first, second, third, fourth) = measurables
      val placeables =
          listOf(first, second, third, fourth).map {
            it.measure(SizeConstraints.exact(CHILD, CHILD))
          }
      layout(CHILD, CHILD * placeables.size) {
        placeables.forEachIndexed { i, placeable -> placeable.place(x = 0, y = i * CHILD) }
      }
    }

    val result = renderAndFindContainer(
        Layout(
            List(4) { SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px)) },
            layout = stack,
        ),
    )

    assertThat(result.childCount).describedAs("all four arrived").isEqualTo(4)
    assertThat((0 until result.childCount).map { result.getYForChildAtIndex(it) })
        .describedAs("and the function placed them by the names it destructured")
        .containsExactly(0, CHILD, CHILD * 2, CHILD * 3)
  }

  /**
   * A layout function reports through its scope, so nothing makes it finish — the framework does.
   *
   * Pinned for a container with no children as well as one with them: an empty container never
   * reaches placement, so if only the placement path checked, it would measure 0x0 in silence.
   */
  @Test
  fun `a layout function that declares no size is refused`() {
    val silent = LayoutFunctionN { measurables, _ -> }

    assertThatThrownBy { renderAndFindContainer(container(childCount = 1, layout = silent)) }
        .hasMessageContaining("must finish by calling layout(width, height)")

    assertThatThrownBy { renderAndFindContainer(container(childCount = 0, layout = silent)) }
        .describedAs("a container with no children reaches only the measure function")
        .hasMessageContaining("must finish by calling layout(width, height)")
  }

  @Test
  fun `a child measured from the placement block is refused`() {
    val measuresWhilePlacing = LayoutFunctionN { measurables, _ ->
      val scope = this
      layout(CHILD, CHILD) {
        with(scope) { measurables[0].measure(SizeConstraints.exact(CHILD, CHILD)) }.place(0, 0)
      }
    }

    assertThatThrownBy {
          renderAndFindContainer(container(childCount = 1, layout = measuresWhilePlacing))
        }
        .hasMessageContaining("can only be measured by its layout function")
  }

  @Test
  fun `a child measured through a measurable kept from an earlier pass is refused`() {
    var kept: Measurable? = null
    val keepsItsFirstMeasurable = LayoutFunctionN { measurables, constraints ->
      val measurable = kept ?: measurables[0].also { kept = it }
      val placeable = measurable.measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.place(0, 0) }
    }
    val view =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          container(childCount = 1, layout = keepsItsFirstMeasurable)
        }

    assertThatThrownBy { view.setSizePx(VIEWPORT / 2, VIEWPORT).measure().layout() }
        .hasMessageContaining("can only be measured by its layout function")
  }

  /**
   * A container's child can be a whole component tree, not only a leaf or another container.
   *
   * The child is laid out as its own Yoga root, and the reducer folds the container's placement
   * into the absolute coordinates every descendant mounts at. A subtree that fails to lay out, or a
   * placement that stops folding partway down, draws content at the wrong offset rather than
   * failing.
   *
   * Asserted on a second pass too, since a cached ancestor reaches this subtree through the clone
   * path, which treats a detached child differently.
   */
  @Test
  fun `a deep child subtree lays out and is placed under the container`() {
    val layout = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.place(x = STRIDE, y = 0) }
    }
    val container = Layout(listOf(DeepChild(DEPTH)), layout = layout)

    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { wrap(container) }
    assertLeafMountedAt(testView, STRIDE)

    // A second pass over an unchanged tree, so the subtree arrives by way of the clone path.
    testView.setSizeSpecs(
        SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
        SizeSpec.makeSizeSpec(VIEWPORT / 2, SizeSpec.EXACTLY),
    )
    testView.measure().layout()
    assertLeafMountedAt(testView, STRIDE)
  }

  /**
   * Asserts where the leaf of the deep subtree actually mounts.
   *
   * Read from the render tree, not the `LithoLayoutResult`: the result is the structure the
   * placement was written into, so reading it back only restates it. What decides where content is
   * drawn is `LithoReducer.collectResults` folding child positions into absolute coordinates, which
   * only [RenderTreeNode.getAbsoluteBounds] shows. The leaf is found by its size, which nothing
   * else in the tree shares.
   */
  private fun assertLeafMountedAt(testView: com.facebook.litho.testing.TestLithoView, x: Int) {
    val tree = checkNotNull(testView.committedLayoutState).toRenderTree()
    val lefts =
        (0 until tree.mountableOutputCount)
            .map { tree.getRenderTreeNodeAtIndex(it) }
            .filter { it.bounds.width() == LEAF && it.bounds.height() == LEAF }
            .map { it.getAbsoluteBounds(Rect()).left }

    assertThat(lefts)
        .describedAs("the leaf mounted once, at the container's placement folded all the way down")
        .containsExactly(x)
  }

  /**
   * A stack of plain Columns with a Text at the bottom, so the container's child is a real tree.
   */
  private class DeepChild(private val depth: Int) : KComponent() {
    override fun ComponentScope.render(): Component =
        if (depth == 0) Text.create(context).text("leaf").widthPx(LEAF).heightPx(LEAF).build()
        else Column { child(DeepChild(depth - 1)) }
  }

  /**
   * A container whose child is another container: both place, and the inner one places against the
   * size the outer assigned it rather than anything it was born with.
   *
   * Worth pinning because the inner one is built in the box the outer's placeable measured it in.
   */
  @Test
  fun `a container inside a container places against the size it was given`() {
    // Right-aligns, so where it puts its child depends on the width it was given — which is the
    // width the outer container assigns when it measures. Run before the outer, it sees a stale
    // one.
    val inner = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) {
        placeable.place(x = constraints.maxWidth - CHILD, y = 0)
      }
    }
    val outer = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD * 4, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.place(x = CHILD, y = 0) }
    }

    val result = renderAndFindContainer(Nested(outer, inner))

    assertThat(result.getXForChildAtIndex(0))
        .describedAs("the outer container placed the inner one")
        .isEqualTo(CHILD)
    val innerResult = result.getChildAt(0)
    assertThat(innerResult.getXForChildAtIndex(0))
        .describedAs("and the inner one right-aligned against the width the outer gave it")
        .isEqualTo(CHILD * 4 - CHILD)
  }

  // ─────────────────────────────────────────────── how often a layout function runs

  /**
   * The debug check before reduce reaches a container inside a deferred node's content, which is
   * not among the deferred node's children and is laid out and committed apart from the rest of the
   * tree. That container is resized after its commit, which is what the check exists to catch.
   */
  @Test
  fun `a container inside deferred content passes the pre-reduce check`() {
    val fit = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(constraints)
      layout(placeable.width, placeable.height) { placeable.place(x = 0, y = 0) }
    }

    // Debug mode is on under `LithoTestRule`, so the check runs before reduce.
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column { child(RenderWithConstraints { Layout(listOf(fixedLeaf()), layout = fit) }) }
        }

    assertThat(testView.componentTree.mainThreadLayoutState).isNotNull
  }

  /**
   * A container laid out for a future that has been released answers 0x0 without running its layout
   * function.
   *
   * A released future's output is thrown away, so work done for it is wasted: the measure function
   * checks the future before anything else, as a leaf's measure does, and the commit skips a
   * released future too. The same container is laid out under a live future first, so the test also
   * shows the setup reaches the measure function at all.
   */
  @Test
  fun `a container laid out for a released future runs no layout function`() {
    val runs = AtomicInteger(0)
    val layout = LayoutFunctionN { measurables, constraints ->
      runs.incrementAndGet()
      val placeable = measurables[0].measure(constraints)
      layout(placeable.width, placeable.height) { placeable.place(x = 0, y = 0) }
    }
    // A fresh context, as `LithoYogaLayoutCacheTest` uses: the rule's context belongs to its own
    // tree.
    val componentContext = ComponentContext(ApplicationProvider.getApplicationContext<Context>())
    fun layOutFor(future: TreeFuture<*>): LithoLayoutResult {
      val resolveContext = componentContext.setRenderStateContextForTests()
      val node = requireNotNull(
          Resolver.resolveTree(
              resolveContext,
              componentContext,
              // A plain child: a Primitive needs the render state only a full tree sets up.
              Layout(
                  listOf(Column(style = Style.width(CHILD.px).height(CHILD.px))),
                  layout = layout,
              ),
          ),
      )
      val layoutCache = LayoutCache()
      val lithoLayoutContext = LithoLayoutContext(
          resolveContext.treeId,
          resolveContext.cache,
          componentContext,
          resolveContext.treeState,
          resolveContext.layoutVersion,
          resolveContext.rootComponentId,
          resolveContext.isAccessibilityEnabled,
          layoutCache,
          null,
          future,
      )
      return requireNotNull(
          node.calculateLayout(
              LayoutContext(
                  componentContext.androidContext,
                  lithoLayoutContext,
                  0,
                  layoutCache,
                  null,
              ),
              SizeConstraints(maxWidth = VIEWPORT, maxHeight = VIEWPORT),
          ),
      )
    }

    val live = layOutFor(TestTreeFuture())
    assertThat(runs.get()).describedAs("a live future runs the layout function").isPositive()
    assertThat(live.width).describedAs("and the container takes its child's size").isEqualTo(CHILD)

    runs.set(0)
    val released = layOutFor(TestTreeFuture().apply { release() })

    assertThat(runs.get()).describedAs("a released future runs no layout function").isZero()
    assertThat(released.width).describedAs("and the container answers 0 wide").isZero()
    assertThat(released.height).describedAs("and 0 tall").isZero()
  }

  /**
   * No builder outlives its pass: a `Layout` in a Yoga tree holds its builder on its result until
   * the tree is built, and lets go of it then.
   */
  @Test
  fun `a Layout in a Yoga tree holds no builder once its pass is done`() {
    val view =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          container(
              childCount = 0,
              layout = LayoutFunctionN { _, _ -> layout(CHILD, CHILD) {} },
          )
              .let { inner ->
                Layout(
                    listOf(Column { child(inner) }),
                    layout =
                        LayoutFunctionN { measurables, constraints ->
                          val placeable = measurables[0].measure(constraints)
                          layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        },
                )
              }
        }
    val root =
        checkNotNull(view.componentTree.mainThreadLayoutState?.rootLayoutResult)
            as LithoLayoutResult
    val layouts = ArrayList<LithoLayoutResult>()
    fun collect(result: LithoLayoutResult) {
      if (result.node.layoutFunction != null) layouts.add(result)
      for (i in 0 until result.childCount) collect(result.getChildAt(i))
    }
    for (i in 0 until root.childCount) collect(root.getChildAt(i))

    assertThat(layouts).describedAs("the Layout in the root's Yoga child").hasSize(1)
    assertThat(layouts.single().defaultLayoutBuilder).isNull()
  }

  /**
   * A layout function runs once per layout pass when the size it asks for is the size it gets.
   *
   * This is what makes the API affordable, and it holds because a [MeasureScope] carries its
   * placement block unevaluated: the measure function can answer Yoga without committing to a
   * placement, so its result survives to be replayed instead of recomputed.
   *
   * The constraints witness which run was kept. Yoga measures a stretched container with an exact
   * width and an open height; its build, in the size Yoga settled, has both axes exact. One run
   * with an open height means the measure callback ran it and the build reused it.
   */
  @Test
  fun `a layout function runs once when its size is not overridden`() {
    val runs = AtomicInteger(0)
    val placements = AtomicInteger(0)
    val constraintsSeen = CopyOnWriteArrayList<String>()

    val layout = LayoutFunctionN { measurables, constraints ->
      runs.incrementAndGet()
      constraintsSeen.add(
          "exactW=${constraints.hasExactWidth},exactH=${constraints.hasExactHeight}",
      )
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) {
        placements.incrementAndGet()
        placeable.place(0, 0)
      }
    }

    renderAndFindContainer(container(childCount = 1, layout = layout))

    assertThat(runs.get()).describedAs("measured once").isEqualTo(1)
    assertThat(placements.get()).describedAs("placed once").isEqualTo(1)
    assertThat(constraintsSeen)
        .describedAs("the one run came from the measure function, not from a second exact pass")
        .containsExactly("exactW=true,exactH=false")
  }

  /**
   * A container whose size the parent overrides is measured again, at the size it actually got.
   *
   * The reuse above is only sound while the two agree, and a stretching parent disagrees routinely:
   * placements computed against the size the container asked for would put every child in the wrong
   * place. The second pass is paid only when it is owed.
   */
  @Test
  fun `a size Yoga overrides is measured again`() {
    val constraintsSeen = CopyOnWriteArrayList<String>()

    // Asks for CHILD, but the enclosing Column's alignItems:stretch makes it VIEWPORT wide.
    val layout = LayoutFunctionN { measurables, constraints ->
      constraintsSeen.add(
          "exactW=${constraints.hasExactWidth},exactH=${constraints.hasExactHeight}",
      )
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      // Where the child goes depends on the run, so the placement that stands names its run.
      layout(CHILD, CHILD) {
        placeable.place(x = if (constraints.hasExactHeight) STRIDE else 0, y = 0)
      }
    }

    val result = renderAndFindContainer(container(childCount = 1, layout = layout))

    assertThat(constraintsSeen)
        .describedAs("measured by Yoga, then again at the size Yoga settled on")
        .containsExactly("exactW=true,exactH=false", "exactW=true,exactH=true")
    assertThat(result.getXForChildAtIndex(0))
        .describedAs("and the placement that stands came from the second run")
        .isEqualTo(STRIDE)
  }

  /**
   * The other half of an allowance: content taller than it is capped to it.
   *
   * On the main axis, so the enclosing Column cannot re-derive the answer the way `alignItems`
   * re-derives a cross-axis one, and with `flexShrink` off so nothing downstream pulls an
   * over-reported height back. Without the cap the container keeps its content height and
   * overflows.
   */
  @Test
  fun `an at-most axis caps content that exceeds the allowance`() {
    val overflowing = VIEWPORT * 2
    // Declares more than it is allowed; the answer Yoga gets is coerced into the allowance.
    val layout = LayoutFunctionN { measurables, _ ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(CHILD, overflowing) { placeable.place(x = 0, y = 0) }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(
              container(childCount = 1, layout = layout, style = Style.flex(shrink = 0f)),
          )
        }
    testView.setSizeSpecs(
        SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
        SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.AT_MOST),
    )
    testView.measure().layout()
    val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
    val result = checkNotNull(findContainer(root as LithoLayoutResult))

    assertThat(result.height)
        .describedAs("capped to the allowance, not the taller content")
        .isEqualTo(VIEWPORT)
  }

  /**
   * An exact width with an open height is a real measurement, taken at the width it is exact at.
   *
   * It is what a container gets inside a vertical Column or list: stretched across, sized down.
   * Yoga keeps the answer as the container's height, so the layout function has to be asked at the
   * width it will actually have — exact, not merely allowed. One whose height follows its width,
   * like an aspect ratio, would otherwise be asked at a minimum of zero and come back flat.
   *
   * And since that run is the real one, placement reuses it rather than running the function again.
   */
  @Test
  fun `an exact width with an open height is measured at that width, once`() {
    val runs = AtomicInteger(0)
    val seen = CopyOnWriteArrayList<String>()
    val halfAsTallAsWide = LayoutFunctionN { measurables, constraints ->
      runs.incrementAndGet()
      seen.add(constraints.toString())
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.minWidth, constraints.minWidth / 2) { placeable.place(x = 0, y = 0) }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(container(childCount = 1, layout = halfAsTallAsWide))
        }
    runs.set(0)
    seen.clear()
    testView.setSizeSpecs(
        SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
        SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
    )
    testView.measure().layout()
    val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
    val result = checkNotNull(findContainer(root as LithoLayoutResult))

    assertThat(result.height)
        .describedAs("asked at the exact width it has, so half of it")
        .isEqualTo(VIEWPORT / 2)
    assertThat(runs.get()).describedAs("and placement reused that run: %s", seen).isEqualTo(1)
  }

  /**
   * Asking a container its intrinsic size does not place anything.
   *
   * An intrinsic query asks what a container *would* do, so running its placement block to answer
   * would fire any side effect the block holds on a hypothetical. Only the framework can invoke the
   * block, and it does so once, in the real pass.
   */
  @Test
  fun `an intrinsic query does not run the placement block`() {
    val runs = AtomicInteger(0)
    val placements = AtomicInteger(0)

    val layout = LayoutFunctionN { measurables, _ ->
      runs.incrementAndGet()
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(CHILD, CHILD) {
        placements.incrementAndGet()
        placeable.place(0, 0)
      }
    }

    // An unspecified width is what drives Yoga to ask the intrinsic question. The minimum then
    // overrides the answer, so the real pass runs the function again at the size Yoga settled on,
    // and the run that answered the question is one whose placement block must never run.
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(
              container(
                  childCount = 1,
                  layout = layout,
                  style = Style.minWidth((CHILD * 2).px),
              ),
          )
        }
    // Counted over the unspecified-width pass alone; rendering above was a pass of its own.
    runs.set(0)
    placements.set(0)
    testView.setSizeSpecs(
        SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
        SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
    )
    testView.measure().layout()

    assertThat(runs.get())
        .describedAs("measured unbounded, then again at the width its min gives it")
        .isGreaterThan(1)
    assertThat(placements.get())
        .describedAs("but only the run that places ran its placement block")
        .isEqualTo(1)
  }

  // ─────────────────────────────────────────────── the layout cache

  @Test
  fun `a minimum reaches the child, not just the reported size`() {
    val reported = AtomicInteger(0)

    val layout = LayoutFunctionN { measurables, _ ->
      // The child declares CHILD; ask for it no narrower than three times that.
      val placeable =
          measurables[0].measure(
              SizeConstraints(
                  minWidth = CHILD * 3,
                  maxWidth = VIEWPORT,
                  minHeight = 0,
                  maxHeight = VIEWPORT,
              ),
          )
      reported.set(placeable.width)
      layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    val result =
        renderAndFindContainer(container(childCount = 1, layout = layout, contentSized = true))

    assertThat(reported.get()).describedAs("the minimum was honoured").isEqualTo(CHILD * 3)
    assertThat(result.getChildAt(0).width)
        .describedAs("and the child really is that wide, not still at its declared size")
        .isEqualTo(reported.get())
  }

  /**
   * A looser constraint is not capped by a tighter earlier one.
   *
   * Constraints are written onto the child's Yoga node, and an unbounded axis mentions no upper
   * bound — so unless it actively clears the previous call's cap, a container that probes narrow
   * and then asks again unbounded gets the probe's answer twice.
   */
  @Test
  fun `a looser constraint is not capped by a tighter earlier one`() {
    val capped = AtomicInteger(0)
    val loosened = AtomicInteger(0)

    val layout = LayoutFunctionN { measurables, _ ->
      val narrow =
          measurables[0].measure(
              SizeConstraints(minWidth = 0, maxWidth = CHILD / 2, minHeight = 0, maxHeight = CHILD),
          )
      capped.set(narrow.width)
      val open =
          measurables[0].measure(
              SizeConstraints(
                  minWidth = 0,
                  maxWidth = SizeConstraints.Infinity,
                  minHeight = 0,
                  maxHeight = CHILD,
              ),
          )
      loosened.set(open.width)
      layout(open.width, open.height) { open.place(0, 0) }
    }

    renderAndFindContainer(container(childCount = 1, layout = layout, contentSized = true))

    assertThat(capped.get())
        .describedAs("the cap applied while it was asked for")
        .isEqualTo(CHILD / 2)
    assertThat(loosened.get()).describedAs("and released when it was not").isEqualTo(CHILD)
  }

  // ─────────────────────────────────────────────── dp inside a layout function

  // ─────────────────────────────────────────────── arity

  /** The list form takes whatever it is given, which is the point of it. */
  @Test
  fun `the list form accepts any number of children`() {
    val anyNumber = LayoutFunctionN { measurables, constraints ->
      val placeables = measurables.map { it.measure(SizeConstraints.exact(CHILD, CHILD)) }
      layout(constraints.maxWidth, CHILD) {
        placeables.forEachIndexed { i, placeable -> placeable.place(x = i * STRIDE, y = 0) }
      }
    }

    val result = renderAndFindContainer(container(childCount = 5, layout = anyNumber))
    assertThat((0 until result.childCount).map { result.getXForChildAtIndex(it) })
        .describedAs("all five reach the function, each placed where it asked")
        .containsExactly(0, STRIDE, STRIDE * 2, STRIDE * 3, STRIDE * 4)
  }

  // ─────────────────────────────────────────────── a child that is measured but not placed

  /**
   * Nesting costs a constant number of layout passes per level, not a multiplying one.
   *
   * Yoga asks a container the same intrinsic question several times in one pass, and nothing below
   * the layout function caches — the intrinsic path does not go through Yoga's measure cache. Each
   * level therefore used to multiply what the level above it repeated: depth 5 cost 72 runs,
   * against 24 once a pass keeps the answers it has already worked out.
   *
   * Checked with the §4.5 probe both off and on, since an intrinsic query reaches a container by
   * either route.
   *
   * Compared from the second level on. The innermost container's child is a leaf with a size of its
   * own, which wins over a min-content probe, and every deeper container's child is a container; so
   * the first level can cost less than the rest, while a multiplying cost still grows level on
   * level.
   */
  @Test
  fun `nesting costs a constant number of passes per level`() {
    for (autoMin in listOf(false, true)) {
      val runs = (1..5).map { depth -> nestedLayoutRuns(depth, autoMin) }
      val perLevel = runs.zipWithNext { shallower, deeper -> deeper - shallower }.drop(1)

      assertThat(perLevel.toSet())
          .describedAs(
              "autoMin=%s: every level adds the same cost, so nesting is linear (%s)",
              autoMin,
              runs,
          )
          .hasSize(1)
    }
  }

  /**
   * Renders [depth] nested containers under an unbounded width, and counts layout function runs.
   */
  private fun nestedLayoutRuns(depth: Int, autoMin: Boolean): Int {
    val runs = AtomicInteger(0)
    val fit = LayoutFunctionN { measurables, constraints ->
      runs.incrementAndGet()
      val placeable = measurables[0].measure(constraints)
      layout(placeable.width, placeable.height) { placeable.place(x = 0, y = 0) }
    }
    var component: Component = SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))
    repeat(depth) { component = Layout(listOf(component), layout = fit) }

    val render = {
      val testView =
          lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { wrap(component) }
      testView.setSizeSpecs(
          SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
          SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
      )
      testView.measure().layout()
    }
    if (autoMin) withAutoMinSize { render() } else render()
    return runs.get()
  }

  /** Enables CSS Flexbox §4.5 auto-min, which [NodeConfig.yogaConfig] latches at class load. */
  private fun <T> withAutoMinSize(body: () -> T): T {
    val config =
        YogaConfigFactory.create().apply {
          setUseWebDefaults(true)
          setExperimentalFeatureEnabled(YogaExperimentalFeature.WEB_FLEX_BASIS, true)
          setErrata(
              YogaErrata.CLASSIC.intValue() and
                  YogaErrata.MIN_SIZE_UNDEFINED_INSTEAD_OF_AUTO.intValue().inv(),
          )
        }
    val previous = NodeConfig.yogaNodeFactory
    NodeConfig.yogaNodeFactory = NodeConfig.InternalYogaNodeFactory {
      YogaNodeFactory.create(config)
    }
    try {
      return body()
    } finally {
      NodeConfig.yogaNodeFactory = previous
    }
  }

  /**
   * A negative margin lets a child outgrow the space it is given; it does not make the child demand
   * more of it. Taken off the minimum as a positive margin is, it raised the minimum instead, past
   * the maximum when the two were close, and building those constraints threw.
   */
  @Test
  fun `a negative margin never raises the minimum`() {
    val errors = CopyOnWriteArrayList<Exception>()
    val sizes = CopyOnWriteArrayList<Int>()
    val squeeze = LayoutFunctionN { measurables, _ ->
      val placeable =
          measurables[0].measure(SizeConstraints(minWidth = 50, maxWidth = 60, maxHeight = CHILD))
      sizes.add(placeable.width)
      layout(60, CHILD) { placeable.place(0, 0) }
    }

    lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
      TestErrorBoundary.create(lithoTestRule.context)
          .errorOutput(errors)
          .child(
              wrap(
                  Layout(
                      listOf(
                          Column(style = Style.height(CHILD.px).margin(horizontal = (-10).px)),
                      ),
                      layout = squeeze,
                  ),
              ),
          )
          .build()
    }

    assertThat(errors).describedAs("errors").isEmpty()
    assertThat(sizes).describedAs("widths the child came back at").isNotEmpty.allSatisfy {
      assertThat(it).isBetween(50, 60)
    }
  }

  /**
   * A container Yoga asks at a bounded width and no fixed height is asked at that width, once.
   *
   * That is how a Column that does not stretch its children asks, and how a Row takes each child's
   * flex basis when its own height is open. The question used to be answered from a run at an
   * unbounded width, capped afterwards: a layout function that decides by room — side by side when
   * there is space, stacked when not — was told it had all it wanted, answered wide, and was then
   * cut to the bound, while a second run followed at the width it really got.
   */
  @Test
  fun `a container asked at a bounded width with an open height is asked at that width`() {
    val asked = CopyOnWriteArrayList<Int>()
    val byRoom = LayoutFunctionN { measurables, constraints ->
      asked.add(constraints.maxWidth)
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      val wide = constraints.maxWidth >= STRIDE
      layout(if (wide) STRIDE else NARROW_ANSWER, CHILD) { placeable.place(0, 0) }
    }

    // The height is left open all the way up, so Yoga measures the container's height as
    // max-content while its width is at most the Column's.
    val testView =
        lithoTestRule.render(
            widthSpec = SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
            heightSpec = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
        ) {
          Column(
              alignItems = com.facebook.yoga.YogaAlign.FLEX_START,
              style = Style.width(BOUND.px),
          ) {
            child(Layout(listOf(fixedLeaf()), layout = byRoom))
          }
        }
    val result = checkNotNull(
        findContainer(
            checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
                as LithoLayoutResult,
        ),
    )

    assertThat(asked).describedAs("widths the function was asked at").containsOnly(BOUND)
    assertThat(result.width).describedAs("the answer for the room it has").isEqualTo(NARROW_ANSWER)
  }

  /**
   * The runs of a layout function and the layouts of its children are counted, so a tree that
   * repeats either shows it without a profiler.
   */
  @Test
  fun `layout function runs and child layouts are counted`() {
    val runs = AtomicInteger(0)
    val twoChildren = LayoutFunctionN { measurables, constraints ->
      runs.incrementAndGet()
      val placeables = measurables.map { it.measure(SizeConstraints.exact(CHILD, CHILD)) }
      layout(constraints.maxWidth, CHILD) { placeables.forEach { it.place(0, 0) } }
    }
    com.facebook.litho.stats.LithoStats.resetAllCounters()

    renderAndFindContainer(Layout(listOf(fixedLeaf(), fixedLeaf()), layout = twoChildren))

    assertThat(com.facebook.litho.stats.LithoStats.customLayoutFunctionRunCount.get())
        .describedAs("counted runs match the runs")
        .isEqualTo(runs.get().toLong())
    assertThat(com.facebook.litho.stats.LithoStats.customLayoutChildLayoutCount.get())
        .describedAs("each child laid out at least once")
        .isGreaterThanOrEqualTo(2)
  }

  /**
   * A run under an unbounded width is counted too. Measured with an unspecified width, as a
   * container inside a horizontal scroll is, the container answers "how wide would you like to be"
   * by running its layout function with that width unbounded.
   */
  @Test
  fun `a run under an unbounded width is counted`() {
    val runs = AtomicInteger(0)
    val intrinsicRuns = AtomicInteger(0)
    val sizedByItsChild = LayoutFunctionN { measurables, constraints ->
      runs.incrementAndGet()
      if (constraints.maxWidth == SizeConstraints.Infinity) {
        intrinsicRuns.incrementAndGet()
      }
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(CHILD, CHILD) { placeable.place(0, 0) }
    }
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(container(childCount = 1, layout = sizedByItsChild))
        }
    runs.set(0)
    intrinsicRuns.set(0)
    com.facebook.litho.stats.LithoStats.resetAllCounters()

    testView.setSizeSpecs(
        SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
        SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
    )
    testView.measure().layout()

    assertThat(intrinsicRuns.get()).describedAs("a run under an unbounded width").isPositive()
    assertThat(com.facebook.litho.stats.LithoStats.customLayoutFunctionRunCount.get())
        .describedAs("counted runs match the runs, unbounded ones included")
        .isEqualTo(runs.get().toLong())
  }

  /**
   * A child reports where its container placed it, as a Column's child does.
   *
   * Each child is laid out as its own Yoga root, so its node's own position is that root's, 0. The
   * position its container reports for it must still be where it sits: compared here with the same
   * children in a Column with the same padding, the second with margins.
   */
  @Test
  fun `a child reports where it was placed, as a Column's child does`() {
    val padded = Style.padding(start = 7.px, top = 9.px)
    fun children(): List<Component> = listOf(
        Column(style = Style.width(CHILD.px).height(CHILD.px)),
        Column(style = Style.width(CHILD.px).height(CHILD.px).margin(start = 5.px, top = 3.px)),
    )
    val stacksThem = LayoutFunctionN { measurables, constraints ->
      val placeables = measurables.map {
        it.measure(SizeConstraints(maxWidth = constraints.maxWidth))
      }
      layout(constraints.maxWidth, placeables.sumOf { it.height }) {
        var y = 0
        placeables.forEach { placeable ->
          placeable.place(x = 0, y = y)
          y += placeable.height
        }
      }
    }

    fun positions(result: LithoLayoutResult): List<Pair<Int, Int>> =
        (0 until result.childCount).map { i ->
          result.getXForChildAtIndex(i) to result.getYForChildAtIndex(i)
        }

    val container = renderAndFindContainer(Layout(children(), style = padded, layout = stacksThem))
    val columnView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(Column(style = padded) { children().forEach { child(it) } })
        }
    val root = checkNotNull(columnView.componentTree.mainThreadLayoutState?.rootLayoutResult)
    val column = (root as LithoLayoutResult).getChildAt(0)

    assertThat(positions(container))
        .describedAs("against a Column's children")
        .isEqualTo(positions(column))
  }

  /**
   * The position a MountSpec's `@OnBoundsDefined` reads is where its parent placed it: a Column's
   * child inside the Column's padding, and a `Layout`'s child where its function placed it.
   */
  @Test
  fun `a MountSpec's bounds are where its parent placed it`() {
    val inColumn = LifecycleTracker()
    val inLayout = LifecycleTracker()
    fun leaf(tracker: LifecycleTracker): Component =
        MountSpecLifecycleTester.create(lithoTestRule.context)
            .lifecycleTracker(tracker)
            .widthPx(CHILD)
            .heightPx(CHILD)
            .build()
    val placesAt13And17 = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD * 2) { placeable.place(13, 17) }
    }

    lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
      Column(style = Style.padding(start = 7.px, top = 9.px)) {
        child(leaf(inColumn))
        child(Layout(listOf(leaf(inLayout)), layout = placesAt13And17))
      }
    }

    assertThat(inColumn.definedBounds.left to inColumn.definedBounds.top)
        .describedAs("a Column's child")
        .isEqualTo(7 to 9)
    assertThat(inLayout.definedBounds.left to inLayout.definedBounds.top)
        .describedAs("a Layout's child")
        .isEqualTo(13 to 17)
  }

  /** A container whose own style asks for its height as its baseline keeps that. */
  @Test
  fun `a container's own useHeightAsBaseline is kept`() {
    val children = listOf(
        Column(style = Style.width(CHILD.px).height(CHILD.px)),
        Column(style = Style.width(CHILD.px).height(CHILD.px)),
    )

    assertThat(
        siblingYInBaselineRow(
            Layout(
                children,
                style = Style.useHeightAsBaseline(true),
                layout = STACKS_VERTICALLY,
            ),
        ),
    )
        .isEqualTo(2 * CHILD - BASELINE_SIBLING)
  }

  /** The y a Row aligning by baseline gives a [BASELINE_SIBLING]-tall sibling of [subject]. */
  private fun siblingYInBaselineRow(subject: Component): Int {
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Row(alignItems = YogaAlign.BASELINE) {
            child(subject)
            child(Column(style = Style.width(BASELINE_SIBLING.px).height(BASELINE_SIBLING.px)))
          }
        }
    val root =
        checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
            as LithoLayoutResult
    val row = if (root.childCount == 2) root else root.getChildAt(0)
    return row.getYForChildAtIndex(1)
  }

  // ─────────────────────────────────────────────── paint order

  /**
   * Overlapping children are drawn in the order the container's placement block places them, the
   * last placed on top, whatever order the container declares them in.
   *
   * Declared 200, 100, 300 wide and placed second, third, first, so the order they're placed in is
   * neither the order they're declared in, its reverse, nor their sizes.
   */
  @Test
  fun `overlapping children are painted in placement order, not declaration order`() {
    val widths = listOf(200, 100, 300)
    val layout = LayoutFunctionN { measurables, constraints ->
      val placeables = measurables.mapIndexed { i, measurable ->
        measurable.measure(SizeConstraints.exact(widths[i], CHILD))
      }
      layout(constraints.maxWidth, CHILD) {
        listOf(1, 2, 0).forEach { index -> placeables[index].place(x = 0, y = 0) }
      }
    }

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Layout(
              widths.map { width ->
                SimpleMountSpecTester(style = Style.width(width.px).height(CHILD.px))
              },
              layout = layout,
          )
        }

    assertThat(paintedWidths(testView))
        .describedAs("drawn in placement order, the last placed on top")
        .containsExactly(100, 300, 200)
  }

  /** The host the two clickable children are mounted in: the deepest host holding two views. */
  private fun hostOf(view: View): ComponentHost {
    var found: ComponentHost? = null
    fun walk(v: View) {
      if (v is ComponentHost && v.childCount >= 2) found = v
      if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(view)
    return checkNotNull(found)
  }

  /**
   * The container's children, by width, in the order they are painted. With [collapseHosts], a
   * child that needs a host — contributing the host and its content, both at its size — is counted
   * once; without it every output is listed, so a child visited twice shows up twice.
   */
  private fun paintedWidths(
      testView: com.facebook.litho.testing.TestLithoView,
      collapseHosts: Boolean = true,
  ): List<Int> {
    val tree = checkNotNull(testView.committedLayoutState).toRenderTree()
    val widths =
        (0 until tree.mountableOutputCount)
            .map { tree.getRenderTreeNodeAtIndex(it) }
            .filter { it.bounds.width() in setOf(100, 200, 300) }
            .sortedBy { it.positionInParent }
            .map { it.bounds.width() }
    return if (collapseHosts) widths.distinct() else widths
  }

  // ─────────────────────────────────────────────── helpers

  /** A container whose only child is another container. */
  private class Nested(
      private val outer: LayoutFunctionN,
      private val inner: LayoutFunctionN,
  ) : KComponent() {
    // The children are built here, so caching the list would hand back the previous render's.
    @Suppress("KComponentUncachedAllocationDetector")
    override fun ComponentScope.render(): Component = Layout(
        listOf(
            Layout(
                listOf(SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))),
                layout = inner,
            ),
        ),
        layout = outer,
    )
  }

  private class Container(
      private val childCount: Int,
      private val layout: LayoutFunctionN,
      private val style: Style,
      private val contentSized: Boolean = false,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Layout(
        components =
            List(childCount) {
              if (contentSized) {
                // No own size: its content asks for CHILD, and the constraints decide, as a child
                // with a declared width no longer lets them.
                SimpleMountSpecTester(measuredWidth = CHILD, measuredHeight = CHILD)
              } else {
                SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))
              }
            },
        style = style,
        layout = layout,
    )
  }

  /**
   * One name reaches one type per lambda shape, and the shape is mostly the parameter count.
   *
   * Overload resolution counts the lambda's parameters before it considers anything else, and never
   * the type the result is assigned to — which is why three and four parameters resolve on their
   * own. Two parameters do not: the general form and the one-child form share that count, so the
   * first parameter's type is all that separates them, and a call site at that arity has to write
   * it.
   *
   * The counts therefore have to stay distinct, and nothing in the language says so. Adding a
   * parameter to any shape collides it with its neighbour, and the error surfaces at some call site
   * rather than at the declaration that caused it.
   *
   * So each shape is passed to `Layout` as an untyped trailing lambda, the way a call site writes
   * it, and typed only where the count alone cannot decide. Each places its children at distinct
   * strides, so a lambda that resolved to the wrong form, or received its children in the wrong
   * order, shows up in where they land.
   */
  @Test
  fun `one name reaches one type per lambda shape`() {
    val exact = SizeConstraints.exact(CHILD, CHILD)
    val one =
        Layout(fixedLeaf()) { only: Measurable, _ ->
          val placeable = only.measure(exact)
          layout(CHILD, CHILD) { placeable.place(STRIDE, 0) }
        }
    val two =
        Layout(fixedLeaf(), fixedLeaf()) { first, second, _ ->
          val firstPlaceable = first.measure(exact)
          val secondPlaceable = second.measure(exact)
          layout(CHILD, CHILD) {
            firstPlaceable.place(0, 0)
            secondPlaceable.place(STRIDE, 0)
          }
        }
    val three =
        Layout(fixedLeaf(), fixedLeaf(), fixedLeaf()) { first, second, third, _ ->
          val firstPlaceable = first.measure(exact)
          val secondPlaceable = second.measure(exact)
          val thirdPlaceable = third.measure(exact)
          layout(CHILD, CHILD) {
            firstPlaceable.place(0, 0)
            secondPlaceable.place(STRIDE, 0)
            thirdPlaceable.place(STRIDE * 2, 0)
          }
        }
    val general =
        Layout(listOf(fixedLeaf(), fixedLeaf())) { measurables, _ ->
          val placeables = measurables.map { it.measure(exact) }
          layout(CHILD, CHILD) {
            placeables.forEachIndexed { index, placeable -> placeable.place(index * STRIDE, 0) }
          }
        }

    fun placedXs(component: Component): List<Int> {
      val result = renderAndFindContainer(component)
      return (0 until result.childCount).map { result.getXForChildAtIndex(it) }
    }
    assertThat(placedXs(one))
        .describedAs("two parameters led by a Measurable is the one-child form")
        .containsExactly(STRIDE)
    assertThat(placedXs(two))
        .describedAs("three parameters names two children, in order")
        .containsExactly(0, STRIDE)
    assertThat(placedXs(three))
        .describedAs("four parameters names three children, in order")
        .containsExactly(0, STRIDE, STRIDE * 2)
    assertThat(placedXs(general))
        .describedAs("two parameters over a list is the general form")
        .containsExactly(0, STRIDE)
  }

  // ─────────────────────────────────────────────── what every scope reads

  /**
   * Measuring and placing both read the container's direction and convert dp through its resources,
   * so a layout function needs neither captured from outside.
   */
  @Config(qualifiers = "xhdpi")
  @Test
  fun `a layout function reads its direction and converts dp in every scope`() {
    val measuring = AtomicReference<Pair<LayoutDirection, Int>>()
    val placing = AtomicReference<Pair<LayoutDirection, Int>>()
    renderAndFindContainer(
        Layout(fixedLeaf(), style = Style.layoutDirection(YogaDirection.RTL)) { child, _ ->
          measuring.set(layoutDirection to DP_INSET.dp.toPixels())
          val placeable = child.measure(SizeConstraints.exact(CHILD, CHILD))
          layout(CHILD, CHILD) {
            placing.set(layoutDirection to DP_INSET.dp.toPixels())
            placeable.place(0, 0)
          }
        },
    )

    assertThat(measuring.get())
        .describedAs("measuring sees the node's direction and the density")
        .isEqualTo(LayoutDirection.RTL to DP_INSET * DENSITY)
    assertThat(placing.get())
        .describedAs("and so does placing")
        .isEqualTo(LayoutDirection.RTL to DP_INSET * DENSITY)
  }

  // ─────────────────────────────────────────────── intrinsic defaults

  /**
   * A default intrinsic runs the layout function, and the children it measures there are measured
   * as in any other run: a placeable keeps the size the child took, not only the room it occupies.
   * So a function that declares `p.measuredWidth` answers the width it measures its child at.
   */
  @Test
  fun `a default intrinsic answers the raw size the function measures`() {
    val seen = CopyOnWriteArrayList<Placeable>()
    val declaresRaw = LayoutFunctionN { measurables, constraints ->
      val placeable =
          measurables[0].measure(
              SizeConstraints(maxWidth = CHILD, maxHeight = constraints.maxHeight),
          )
      seen.add(placeable)
      layout(placeable.measuredWidth, placeable.height) { placeable.place(0, 0) }
    }
    val answers = AtomicReference<Pair<Int, Int>>()
    renderAndFindContainer(
        Layout(SimpleMountSpecTester(style = Style.width(WIDE.px).height(CHILD.px))) {
            child,
            constraints ->
          val max = with(declaresRaw) { maxIntrinsicWidth(listOf(child), constraints.maxHeight) }
          val min = with(declaresRaw) { minIntrinsicWidth(listOf(child), constraints.maxHeight) }
          answers.set(max to min)
          val placeable = child.measure(SizeConstraints.exact(CHILD, CHILD))
          layout(CHILD, CHILD) { placeable.place(0, 0) }
        },
    )

    assertThat(seen.map { it.width }).describedAs("the room the child occupies").containsOnly(CHILD)
    assertThat(answers.get().first)
        .describedAs("the default max answers the size the child took")
        .isEqualTo(WIDE)
    assertThat(answers.get().second).describedAs("and so does the default min").isEqualTo(WIDE)
  }

  private fun container(
      childCount: Int,
      layout: LayoutFunctionN,
      style: Style = Style,
      contentSized: Boolean = false,
  ): Component = Container(childCount, layout, style, contentSized)

  /**
   * A placeable is never smaller than 0, however negative the child's margins.
   *
   * A negative margin pulls a child's box in, and one larger than the child would leave a negative
   * size, which a container sizing itself from its child passes on to `layout`, where it fails.
   */
  @Test
  fun `a placeable is never smaller than 0`() {
    val answered = AtomicReference<Pair<Int, Int>>()
    val fit = LayoutFunctionN { measurables, _ ->
      val placeable = measurables[0].measure(SizeConstraints())
      answered.set(placeable.width to placeable.height)
      layout(placeable.width, placeable.height) { placeable.place(x = 0, y = 0) }
    }
    val pulledIn = SimpleMountSpecTester(
        style =
            Style.width(CHILD.px)
                .height(CHILD.px)
                .margin(start = (-CHILD * 2).px, top = (-CHILD * 2).px),
    )

    renderAndFindContainer(Layout(listOf(pulledIn), layout = fit))

    assertThat(answered.get())
        .describedAs("100 wide and tall, less 200 of margins")
        .isEqualTo(0 to 0)
  }

  // ─────────────────────────────────────────────── placement re-enters layout

  /**
   * A container is built while a deferred descendant builds a container of its own.
   *
   * Re-measuring a deferred child that was measured through the `Component.measure` API rebuilds
   * its subtree against *this* layout context — `LayoutUtils.measureDeferredNode` hands the
   * enclosing `LithoLayoutContext` straight to `measureTree` when the cached size spec no longer
   * fits. So a container inside that subtree is laid out by a pass nested in the outer container's
   * build, which must still build its child, and the container after it.
   *
   * Four things are deliberate. The outer container has a definite size, which is what makes Yoga
   * skip its measure callback so the measuring happens during its build rather than before it. It
   * measures its child against a bound, so the child has to answer rather than be handed a size.
   * That bound differs from the size the child was measured at, which is what makes the cached
   * result unusable and forces the rebuild. And a second container follows it, so a build after the
   * nested pass is checked too.
   */
  @Test
  fun `a deferred descendant may build a container while one is being placed`() {
    val stack = LayoutFunctionN { measurables, _ ->
      val placeables = measurables.map {
        it.measure(SizeConstraints(maxWidth = CHILD, maxHeight = CHILD))
      }
      layout(CHILD, CHILD) { placeables.forEach { it.place(x = 0, y = 0) } }
    }
    val inner =
        Layout(listOf(Column(style = Style.width(CHILD.px).height(CHILD.px))), layout = stack)
    val deferred =
        ComponentWithMeasureCall.create(lithoTestRule.context)
            .component(inner)
            .widthSpec(SizeSpec.makeSizeSpec(MEASURED, SizeSpec.EXACTLY))
            .heightSpec(SizeSpec.makeSizeSpec(MEASURED, SizeSpec.EXACTLY))
            .shouldCacheResult(true)
            .build()

    val reentering =
        Layout(listOf(deferred), style = Style.width(CHILD.px).height(CHILD.px), layout = stack)
    val following = container(childCount = 1, layout = stack)

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(
              Column {
                child(reentering)
                child(following)
              },
          )
        }
    val column =
        (checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
                as LithoLayoutResult)
            .getChildAt(0)
    val result = column.getChildAt(0)

    assertThat(result.childCount)
        .describedAs("the outer container still holds its child after the re-entrant build")
        .isEqualTo(1)
    assertThat(result.getChildAt(0).width)
        .describedAs("and the deferred child was laid out rather than left at zero")
        .isEqualTo(CHILD)
    assertThat(column.getChildAt(1).getChildAt(0).width)
        .describedAs("and the container after it in the walk was still placed")
        .isEqualTo(CHILD)
  }

  /**
   * A nested pass leaves its caller's containers alone.
   *
   * Nesting a pass inside a container's build is covered separately, and is harmless: every size
   * has settled by then. This nests one inside the enclosing Yoga layout instead, which is where
   * the two passes can disagree — the container under test exists once the tree is made but has no
   * size until that layout ends. A nested pass that built it would build it in a box of zero, and
   * it's built once per pass, so the enclosing pass would not get to correct it.
   *
   * The deferred sibling is declared first so the nested pass runs before Yoga reaches the
   * container, and is measured at a size the viewport will not satisfy so that the cached result is
   * unusable — that is what sends `LayoutUtils.measureDeferredNode` down the branch re-measuring
   * against the enclosing `LithoLayoutContext` rather than a fresh one.
   *
   * Every part of that shape is load-bearing, and the whole test is vacuous without it. Giving the
   * deferred sibling a plain `Column` instead of a container of its own is enough: the re-measure
   * is then answered from cache, no pass ever nests, and the test passes whatever the build code
   * does.
   */
  @Test
  fun `a nested pass leaves a container its caller has not sized yet`() {
    // Right-aligns, so where the child lands reports which width the container was placed against.
    val rightAlign = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(NARROW, CHILD))
      layout(constraints.maxWidth, CHILD) {
        placeable.place(x = constraints.maxWidth - NARROW, y = 0)
      }
    }
    val stack = LayoutFunctionN { measurables, _ ->
      val placeables = measurables.map {
        it.measure(SizeConstraints(maxWidth = CHILD, maxHeight = CHILD))
      }
      layout(CHILD, CHILD) { placeables.forEach { it.place(x = 0, y = 0) } }
    }
    val deferred =
        ComponentWithMeasureCall.create(lithoTestRule.context)
            .component(
                Layout(
                    listOf(Column(style = Style.width(CHILD.px).height(CHILD.px))),
                    layout = stack,
                ),
            )
            .widthSpec(SizeSpec.makeSizeSpec(MEASURED, SizeSpec.EXACTLY))
            .heightSpec(SizeSpec.makeSizeSpec(MEASURED, SizeSpec.EXACTLY))
            .shouldCacheResult(true)
            .build()

    // No width of its own, so Yoga settles it by stretching, and only once the sibling above has
    // been measured. Its child is the only one [NARROW] wide, which is how it is found again below:
    // the container the deferred subtree builds is stretched to the viewport too.
    val container =
        Layout(listOf(Column(style = Style.width(NARROW.px).height(CHILD.px))), layout = rightAlign)

    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column {
            child(deferred)
            child(container)
          }
        }
    val root =
        checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
            as LithoLayoutResult
    val result = checkNotNull(findNarrowContainer(root)) { "container under test not found" }

    assertThat(result.width)
        .describedAs("the container stretched to the viewport")
        .isEqualTo(VIEWPORT)
    assertThat(result.getXForChildAtIndex(0))
        .describedAs("and was placed against that width, not one the enclosing pass had not set")
        .isEqualTo(VIEWPORT - NARROW)
  }

  private fun findNarrowContainer(result: LithoLayoutResult): LithoLayoutResult? {
    if (
        result.node.tailComponent is Layout &&
            result.childCount == 1 &&
            result.getChildAt(0).width == NARROW
    ) {
      return result
    }
    for (i in 0 until result.childCount) {
      findNarrowContainer(result.getChildAt(i))?.let {
        return it
      }
    }
    return null
  }

  // ─────────────────────────────────────────────── view flattening

  /**
   * A container flattens its children exactly as a `Row` does.
   *
   * Placement reaches a child through its bounds — the reducer folds a container's child positions
   * into the absolute coordinates each descendant is mounted at — so a child Litho would flatten
   * into drawables needs no view of its own.
   *
   * Pinned against a `Row` holding the same children rather than an absolute count, so it measures
   * what a container costs over flexbox, not how many hosts Litho happens to mount.
   */
  @Test
  fun `a container flattens its children as a Row does`() {
    val layout = LayoutFunctionN { measurables, constraints ->
      val placeables = measurables.map { it.measure(SizeConstraints.exact(CHILD, CHILD)) }
      layout(constraints.maxWidth, CHILD) {
        placeables.forEachIndexed { i, placeable -> placeable.place(x = i * STRIDE, y = 0) }
      }
    }
    val inContainer =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(Layout(List(3) { flattenableChild(it) }, layout = layout))
        }
    val inRow =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          wrap(Row { repeat(3) { child(flattenableChild(it)) } })
        }

    for (testView in listOf(inContainer, inRow)) {
      LithoAssertions.assertThat(testView)
          .hasVisibleText("child 0")
          .hasVisibleText("child 1")
          .hasVisibleText("child 2")
    }
    assertThat(countHosts(inContainer.lithoView))
        .describedAs("all three mounted, and a container costs no host a Row would not also mount")
        .isEqualTo(countHosts(inRow.lithoView))
  }

  /** Text mounts a drawable, so Litho flattens it unless something asks for a view. */
  private fun flattenableChild(index: Int): Component =
      Text.create(lithoTestRule.context).text("child $index").build()

  private fun countHosts(view: View): Int {
    var count = if (view is ComponentHost) 1 else 0
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        count += countHosts(view.getChildAt(i))
      }
    }
    return count
  }

  // ─────────────────────────────────────────────── padding

  /**
   * A layout function works inside the container's padding, as a flexbox child does: it is handed
   * the content box, and the positions it chooses are measured from that box's top-left.
   */
  @Test
  fun `a layout function places inside the container's padding`() {
    val constraintsSeen = CopyOnWriteArrayList<SizeConstraints>()
    val layout = LayoutFunctionN { measurables, constraints ->
      constraintsSeen.add(constraints)
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.place(x = STRIDE, y = 0) }
    }

    val result = renderAndFindContainer(
        container(childCount = 1, layout = layout, style = Style.padding(all = PADDING.px)),
    )

    assertThat(result.height).isEqualTo(CHILD + PADDING * 2)
    assertThat(constraintsSeen.map { it.maxWidth })
        .describedAs("every run, placement included, is handed the width inside the padding")
        .containsOnly(VIEWPORT - PADDING * 2)
    assertThat(result.getXForChildAtIndex(0)).isEqualTo(PADDING + STRIDE)
    assertThat(result.getYForChildAtIndex(0)).isEqualTo(PADDING)
  }

  // ─────────────────────────────────────────────── threads

  /**
   * A container lays out on whichever thread lays the tree out, and places against the size that
   * thread settled on. Every other test here lays out on the main thread, which is the one thread a
   * list item or an async root never uses.
   */
  @Test
  fun `a container lays out on a background thread`() {
    val threads = CopyOnWriteArrayList<Thread>()
    val layout = LayoutFunctionN { measurables, _ ->
      threads.add(Thread.currentThread())
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(CHILD * 2, CHILD) { placeable.place(CHILD, 0) }
    }
    val root = wrap(Layout(listOf(fixedLeaf()), layout = layout))
    val tree = ComponentTree.create(lithoTestRule.context).build()

    val background = Thread {
      tree.setRootAndSizeSpecSync(
          root,
          SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
          SizeSpec.makeSizeSpec(VIEWPORT, SizeSpec.EXACTLY),
      )
    }
    background.start()
    background.join(TimeUnit.SECONDS.toMillis(5))
    lithoTestRule.idle()

    assertThat(threads).describedAs("threads the layout function ran on").isNotEmpty
    assertThat(threads).allSatisfy { assertThat(it).isSameAs(background) }
    val result = checkNotNull(
        findContainer(
            checkNotNull(tree.mainThreadLayoutState?.rootLayoutResult) as LithoLayoutResult,
        ),
    )
    assertThat(result.getXForChildAtIndex(0))
        .describedAs("placed as the function said")
        .isEqualTo(CHILD)
  }

  // ─────────────────────────────────────────────── errors

  private fun renderAndFindContainer(component: Component): LithoLayoutResult {
    val testView = lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) { wrap(component) }
    val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
    return checkNotNull(findContainer(root as LithoLayoutResult)) { "container not found in tree" }
  }

  /** A container is never the root host, so place it one level down, as real usage does. */
  private fun wrap(component: Component): Component = Column { child(component) }

  private fun findContainer(result: LithoLayoutResult): LithoLayoutResult? {
    if (result.node.tailComponent is Layout) return result
    for (i in 0 until result.childCount) {
      findContainer(result.getChildAt(i))?.let {
        return it
      }
    }
    return null
  }

  companion object {
    /** Measures each child with the width it has, and stacks them top to bottom. */
    val STACKS_VERTICALLY = LayoutFunctionN { measurables, constraints ->
      val placeables = measurables.map {
        it.measure(SizeConstraints(maxWidth = constraints.maxWidth))
      }
      layout(placeables.maxOf { it.width }, placeables.sumOf { it.height }) {
        var y = 0
        placeables.forEach { placeable ->
          placeable.place(x = 0, y = y)
          y += placeable.height
        }
      }
    }

    private const val BASELINE_SIBLING = 10

    /** Measures its only child with no bound, and places it. */
    val MEASURES_UNBOUNDED = LayoutFunctionN { measurables, _ ->
      val placeable = measurables[0].measure(SizeConstraints())
      layout(CHILD, CHILD) { placeable.place(x = 0, y = 0) }
    }

    private const val VIEWPORT = 1000
    private const val BOUND = 80
    private const val NARROW_ANSWER = 60
    private const val CHILD = 100
    private const val STRIDE = 150
    private const val WIDE = 150
    private const val SQUEEZE = 150
    private const val TEXT_SQUEEZE = 16
    private const val WORDS = "aaaaaaaaaa bbbb"
    private const val DEPTH = 6
    private const val LEAF = 37
    private const val DECLARED_WIDTH = 777
    private const val MEASURED = 200
    private const val PADDING = 10
    /** A width no other child in these tests has, so a container carrying it is identifiable. */
    private const val NARROW = 64
    private const val DP_INSET = 8
    private const val DP_WIDTH = 120
    /**
     * The density `xhdpi` resolves to, which the dp test pins itself to.
     *
     * Anything but 1: at the default density a dp resolves to the same number of pixels, so every
     * assertion below would compare a dp constant against itself and hold even if `toPixels` never
     * converted anything.
     */
    private const val DENSITY = 2

    // Every edge different, so an inset applied on the wrong edge cannot pass by coincidence.
    private const val PAD_LEFT = 11
    private const val PAD_TOP = 13
    private const val PAD_RIGHT = 17
    private const val PAD_BOTTOM = 19
    private const val BORDER = 3
    private const val INSET_H = PAD_LEFT + PAD_RIGHT + 2 * BORDER
    private const val INSET_V = PAD_TOP + PAD_BOTTOM + 2 * BORDER
    private val PADDED_AND_BORDERED: Style =
        Style.padding(
                left = PAD_LEFT.px,
                top = PAD_TOP.px,
                right = PAD_RIGHT.px,
                bottom = PAD_BOTTOM.px,
            )
            .border(width = BORDER.px, color = Color.BLACK)
    private val RELATIVE_INSETS: Style =
        Style.padding(
                start = PAD_LEFT.px,
                top = PAD_TOP.px,
                end = PAD_RIGHT.px,
                bottom = PAD_BOTTOM.px,
            )
            .border(width = BORDER.px, color = Color.BLACK)
    private const val DEFINITE = 400
    private const val MARGIN_START = 5
    private const val MARGIN_TOP = 7
    private const val MARGIN_END = 9
    private const val MARGIN_BOTTOM = 23
    private const val CONTAINER = 300
    /** A position that mirrored under RTL would land somewhere else entirely. */
    private const val PLACED_AT = 10
    /** Leaves a content box of 300 inside [RELATIVE_INSETS], so [MARGIN_PERCENT] is a whole 30. */
    private const val WHOLE_PERCENT_CONTAINER = 300 + PAD_LEFT + PAD_RIGHT + BORDER * 2
    private const val MARGIN_PERCENT = 10f
    /** A bound [MARGIN_PERCENT] of which is a whole number of pixels. */
    private const val PERCENT_BASE = 300
    private const val AREA = 4000
    private const val AREA_WIDTH = 200
    private const val PLACEMENT_FAILURE = "thrown while placing"
    private const val CRASH = "layout function crash"

    /** Fills the width it is given and puts its one child at the origin. */
    private val FILL_WIDTH = LayoutFunctionN { measurables, constraints ->
      val placeable = measurables[0].measure(SizeConstraints.exact(CHILD, CHILD))
      layout(constraints.maxWidth, CHILD) { placeable.place(x = 0, y = 0) }
    }

    /** Measures its child loosely and puts it at the leading top corner, as a `Column` would. */
    private val PLACE_LIKE_A_COLUMN = LayoutFunctionN { measurables, constraints ->
      val loose =
          SizeConstraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
      val placeable = measurables[0].measure(loose)
      layout(placeable.width, placeable.height) { placeable.placeRelative(start = 0, top = 0) }
    }
  }

  /** A future that computes nothing; only its released state matters. */
  private class TestTreeFuture : TreeFuture<TestTreeFuture.Empty>(0, false) {
    object Empty : PotentiallyPartialResult {
      override val isPartialResult: Boolean = false
    }

    override fun getDescription(): String = "test"

    override fun getVersion(): Int = 0

    override fun calculate(): Empty = Empty

    override fun resumeCalculation(partialResult: Empty?): Empty = Empty

    override fun isEquivalentTo(that: TreeFuture<*>?): Boolean = that === this
  }
}
