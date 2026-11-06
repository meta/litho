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

@file:OptIn(ExperimentalLithoApi::class)

package com.facebook.litho.customlayout

import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.Layout
import com.facebook.litho.LithoLayoutResult
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.SizeSpec
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.margin
import com.facebook.litho.core.marginPercent
import com.facebook.litho.core.maxHeight
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.minWidthPercent
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.aspectRatio
import com.facebook.litho.flexbox.flex
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.flexbox.position
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.Placeable
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope as PrimitiveLayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val VIEWPORT = 1000
private const val INFINITY = SizeConstraints.Infinity

/**
 * Every number the pages' figures draw. `website/scripts/custom-layout-figures.js` reads this block
 * to draw them, and each F test builds its figure's case from these inputs and asserts its outputs,
 * so a figure can't show a number Litho doesn't produce.
 */
// start_figure_numbers
private val FIGURES: Map<String, Int> = mapOf(
    // F1: a Layout 300 wide with padding 10; its function gets exactly 280 and places a 30 x 25
    // child.
    "F1.layoutWidth" to 300,
    "F1.padding" to 10,
    "F1.contentWidth" to 280,
    "F1.childWidth" to 30,
    "F1.childHeight" to 25,
    "F1.childX" to 10,
    "F1.childY" to 10,
    // F2: width(100).margin(all = 7) at most 300; width(150) at most 100.
    "F2.marginRoom" to 300,
    "F2.marginOwnWidth" to 100,
    "F2.margin" to 7,
    "F2.marginMeasuredWidth" to 114,
    "F2.marginBoxX" to 7,
    "F2.overflowRoom" to 100,
    "F2.overflowOwnWidth" to 150,
    "F2.overflowWidth" to 100,
    "F2.overflowMeasuredWidth" to 150,
    // F3: the start edge.
    "F3.shortRoom" to 100,
    "F3.shortWidth" to 81,
    "F3.shortX" to 0,
    "F3.overRoom" to 100,
    "F3.overWidth" to 150,
    "F3.overX" to 0,
    "F3.rtlRoom" to 300,
    "F3.rtlWidth" to 120,
    "F3.rtlX" to 180,
    // F4: place and placeRelative in a container 1000 wide, a child 100 wide.
    "F4.container" to 1000,
    "F4.child" to 100,
    "F4.offset" to 30,
    "F4.ltrX" to 30,
    "F4.rtlRelativeX" to 870,
    "F4.rtlPlaceX" to 30,
    "F4.stretchedDeclared" to 100,
    "F4.stretchedX" to 900,
    "F4.narrowedDeclared" to 1200,
    "F4.narrowedPlaceX" to -200,
    "F4.narrowedRelativeX" to 900,
    // F5: the declared size is a request.
    "F5.stretchDeclared" to 100,
    "F5.stretchParent" to 1000,
    "F5.stretchFinal" to 1000,
    "F5.stretchRuns" to 2,
    "F5.pastRoom" to 300,
    "F5.pastDeclared" to 400,
    "F5.pastFinal" to 400,
    "F5.cappedMax" to 350,
    "F5.cappedFinal" to 350,
    "F5.exactRoom" to 300,
    "F5.exactFinal" to 300,
    // F6: a child's own style against its room.
    "F6.room" to 300,
    "F6.c3StretchMin" to 200,
    "F6.c3StretchResult" to 300,
    "F6.c3PastMin" to 400,
    "F6.c3PastResult" to 400,
    "F6.c4Max" to 80,
    "F6.c4Result" to 80,
    "F6.c6Width" to 80,
    "F6.c6Result" to 80,
    "F6.c6MinWidth" to 120,
    "F6.c6MinResult" to 120,
    "F6.c7HalfPercent" to 50,
    "F6.c7HalfResult" to 150,
    "F6.c7OverPercent" to 150,
    "F6.c7OverMeasured" to 450,
    "F6.c7OverWidth" to 300,
    "F6.c9Room" to 10,
    "F6.c9Padding" to 8,
    "F6.c9Result" to 16,
    // F7: ActionBarLayout's choice: labelled buttons 120 x 20, icons 50 x 10, in 120 and 119 wide.
    "F7.labelledWidth" to 120,
    "F7.labelledHeight" to 20,
    "F7.iconWidth" to 50,
    "F7.iconHeight" to 10,
    "F7.fitsRoom" to 120,
    "F7.tightRoom" to 119,
)

// end_figure_numbers

private fun fig(key: String): Int = FIGURES.getValue(key)

/**
 * Pins every rule and worked example of the custom `Layout` guide, `docs/layout/custom-layout.mdx`,
 * and its sizing rules, `docs/layout/custom-layout-rules.mdx`, with the numbers the pages state.
 * Each test is named by the rule it pins: P for measuring and placing, C for a `Layout`'s child, R
 * for a root `Layout`, D for a difference from a Yoga `Column` kept on purpose, L for the known
 * limitation, F for the numbers the figures draw, and G for the guide's other claims. A change that
 * makes one fail changes the documented contract: update the page with it.
 *
 * A child's content, where a rule reads it, is a `Row` of 10 x 10 and 20 x 25: 30 x 25. A root's is
 * a 30 x 20 view.
 */
@RunWith(LithoTestRunner::class)
class CustomLayoutGuideConformanceTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  // ─────────────────────────────────────────────── P: measuring and placing

  @Test
  fun `P1 the function lays out in the content box, and a minimum reaches it only exactly`() {
    val seen = AtomicReference<SizeConstraints>()
    val container = Layout(
        listOf(sized(width = 50, height = 20)),
        style = Style.width(300.px).padding(all = 10.px),
        layout =
            LayoutFunctionN { measurables, constraints ->
              seen.set(constraints)
              val placeable = measurables[0].measure(constraints.copy(minWidth = 0))
              layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
            },
    )

    val result = renderInColumn(container)

    assertThat(seen.get().minWidth to seen.get().maxWidth)
        .describedAs("exactly 300 less 10 and 10")
        .isEqualTo(280 to 280)
    assertThat(seen.get().minHeight).describedAs("the height isn't exact: no minimum").isEqualTo(0)
    assertThat(result.getXForChildAtIndex(0))
        .describedAs("(0, 0) is inside the padding")
        .isEqualTo(10)
    assertThat(result.getYForChildAtIndex(0)).isEqualTo(10)
  }

  @Test
  fun `P2 a placeable's width is kept within its constraints, its measured width is not`() {
    val seen = AtomicReference<Placeable>()

    renderInColumn(
        measuring(sized(width = 150, height = 100), SizeConstraints(maxWidth = 100), seen),
    )

    assertThat(seen.get().width).isEqualTo(100)
    assertThat(seen.get().measuredWidth).isEqualTo(150)
    assertThat(seen.get().height to seen.get().measuredHeight).isEqualTo(100 to 100)
  }

  @Test
  fun `P3 a child that doesn't fit starts at its start edge, in both directions`() {
    val short = renderInColumn(
        measuring(sized(width = 81, height = 100), exactWidth(100), declareRoomWidth = true),
    )
    assertThat(short.getChildAt(0).width).isEqualTo(81)
    assertThat(short.getXForChildAtIndex(0)).describedAs("19 short of the end").isEqualTo(0)

    val overflowing = renderInColumn(
        measuring(sized(width = 150, height = 100), SizeConstraints(maxWidth = 100)),
    )
    assertThat(overflowing.getChildAt(0).width).isEqualTo(150)
    assertThat(overflowing.getXForChildAtIndex(0)).describedAs("50 past the end").isEqualTo(0)

    val rtl = renderInColumn(
        measuring(
            sized(width = 120, height = 100),
            exactWidth(300),
            declareRoomWidth = true,
            style = Style.layoutDirection(YogaDirection.RTL),
        ),
    )
    assertThat(rtl.getXForChildAtIndex(0)).describedAs("at the right edge of 300").isEqualTo(180)
  }

  @Test
  fun `P4 placeRelative mirrors under RTL against the width the container ends up with`() {
    fun placedAt(direction: YogaDirection, relative: Boolean): Int {
      val container = Layout(
          listOf(sized(width = 100, height = 100)),
          style = Style.layoutDirection(direction),
          layout =
              LayoutFunctionN { measurables, _ ->
                val placeable = measurables[0].measure(SizeConstraints.exact(100, 100))
                layout(100, 100) {
                  if (relative) placeable.placeRelative(start = 0, top = 0)
                  else placeable.place(x = 0, y = 0)
                }
              },
      )
      return renderStretched(container).getXForChildAtIndex(0)
    }

    assertThat(placedAt(YogaDirection.LTR, relative = true)).isEqualTo(0)
    assertThat(placedAt(YogaDirection.RTL, relative = true))
        .describedAs("asked for 100, stretched to 1000: at its right edge")
        .isEqualTo(900)
    assertThat(placedAt(YogaDirection.LTR, relative = false)).isEqualTo(0)
    assertThat(placedAt(YogaDirection.RTL, relative = false))
        .describedAs("the 100 it declared ends at the right edge of the 1000 it got")
        .isEqualTo(900)
  }

  @Test
  fun `P4 under RTL, a declared width the parent narrows starts at the start edge too`() {
    fun placedAt(relative: Boolean): Pair<Int, Int> {
      val container = Layout(
          listOf(sized(width = 100, height = 100)),
          style = Style.layoutDirection(YogaDirection.RTL),
          layout =
              LayoutFunctionN { measurables, _ ->
                val placeable = measurables[0].measure(SizeConstraints.exact(100, 100))
                layout(1200, 100) {
                  if (relative) placeable.placeRelative(start = 0, top = 0)
                  else placeable.place(x = 0, y = 0)
                }
              },
      )
      val result = renderStretched(container)
      return result.width to result.getXForChildAtIndex(0)
    }

    assertThat(placedAt(relative = true))
        .describedAs("declared 1200, kept to 1000")
        .isEqualTo(1000 to 900)
    assertThat(placedAt(relative = false))
        .describedAs("the 1200 it declared ends at the right edge of the 1000 it got")
        .isEqualTo(1000 to -200)
  }

  @Test
  fun `P4 placeRelative mirrors a start offset under RTL, and place does not`() {
    fun placedAt(direction: YogaDirection, relative: Boolean): Int {
      val container = Layout(
          listOf(sized(width = 100, height = 100)),
          style = Style.layoutDirection(direction),
          layout =
              LayoutFunctionN { measurables, constraints ->
                val placeable = measurables[0].measure(SizeConstraints.exact(100, 100))
                layout(constraints.maxWidth, 100) {
                  if (relative) placeable.placeRelative(start = 30, top = 0)
                  else placeable.place(x = 30, y = 0)
                }
              },
      )
      return renderStretched(container).getXForChildAtIndex(0)
    }

    assertThat(placedAt(YogaDirection.LTR, relative = true)).isEqualTo(30)
    assertThat(placedAt(YogaDirection.RTL, relative = true))
        .describedAs("1000 - 30 - 100")
        .isEqualTo(870)
    assertThat(placedAt(YogaDirection.LTR, relative = false)).isEqualTo(30)
    assertThat(placedAt(YogaDirection.RTL, relative = false)).isEqualTo(30)
  }

  @Test
  fun `P5 the size a function declares is a request its parent may override`() {
    val container = Layout(
        listOf(sized(width = 100, height = 100)),
        layout =
            LayoutFunctionN { measurables, _ ->
              val placeable = measurables[0].measure(SizeConstraints.exact(100, 100))
              layout(100, 100) { placeable.place(0, 0) }
            },
    )

    assertThat(renderStretched(container).width).isEqualTo(VIEWPORT)
  }

  @Test
  fun `P6 the same constraints give the same placeable, and only placed children mount`() {
    val first = AtomicReference<Placeable>()
    val second = AtomicReference<Placeable>()
    val container = Layout(
        listOf(sized(width = 40, height = 10), sized(width = 60, height = 10)),
        layout =
            LayoutFunctionN { measurables, _ ->
              val room = SizeConstraints(maxWidth = 300)
              first.set(measurables[0].measure(room))
              second.set(measurables[0].measure(room))
              measurables[1].measure(room)
              layout(300, 10) { second.get().place(0, 0) }
            },
    )

    val result = renderInColumn(container)

    assertThat(second.get()).isSameAs(first.get())
    assertThat(result.childCount).describedAs("the unplaced child isn't built").isEqualTo(1)
    assertThat(result.getChildAt(0).width).isEqualTo(40)
  }

  @Test
  fun `P6 children are painted in the order they're placed`() {
    val container = Layout(
        listOf(sized(width = 40, height = 10), sized(width = 60, height = 10)),
        layout =
            LayoutFunctionN { measurables, _ ->
              val placeables = measurables.map { it.measure(SizeConstraints(maxWidth = 300)) }
              layout(300, 10) {
                placeables[1].place(0, 0)
                placeables[0].place(0, 0)
              }
            },
    )

    val result = renderInColumn(container)

    assertThat(listOf(result.getChildAt(0).width, result.getChildAt(1).width))
        .describedAs("the second child first, the first on top")
        .isEqualTo(listOf(60, 40))
  }

  @Test
  fun `P7 under a Yoga parent, a Layout's declared size can pass an at-most room, within its own max`() {
    fun widthIn(align: YogaAlign, style: Style): Int {
      val container = Layout(
          listOf(sized(width = 400, height = 10)),
          style = style,
          layout =
              LayoutFunctionN { measurables, _ ->
                val placeable = measurables[0].measure(SizeConstraints())
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
              },
      )
      return findLayout(
          renderTree(Column(alignItems = align, style = Style.width(300.px)) { child(container) }),
      )
          .width
    }

    assertThat(widthIn(YogaAlign.FLEX_START, Style)).describedAs("past at most 300").isEqualTo(400)
    assertThat(widthIn(YogaAlign.STRETCH, Style)).describedAs("exactly 300").isEqualTo(300)
    assertThat(widthIn(YogaAlign.FLEX_START, Style.maxWidth(350.px))).isEqualTo(350)
  }

  // ─────────────────────────────────────────────── C: a Layout's child

  @Test
  fun `C1 a percentage margin is of the room's width, on both axes`() {
    val result = renderInColumn(
        measuring(
            content(Style.marginPercent(top = 10f)),
            SizeConstraints(maxWidth = 200, maxHeight = VIEWPORT),
        ),
    )

    assertThat(result.getYForChildAtIndex(0)).describedAs("10% of 200").isEqualTo(20)
  }

  @Test
  fun `C1 a percentage has no effect on an unbounded axis, and a percentage margin is 0`() {
    val result = renderInColumn(
        measuring(content(Style.widthPercent(50f).marginPercent(left = 10f)), SizeConstraints()),
    )

    assertThat(result.getChildAt(0).width).describedAs("its content's 30").isEqualTo(30)
    assertThat(result.getXForChildAtIndex(0)).isEqualTo(0)
    val min = renderInColumn(measuring(content(Style.minWidthPercent(50f)), SizeConstraints()))
    assertThat(min.getChildAt(0).width).isEqualTo(30)
  }

  @Test
  fun `C2 margins come out of the room, and the measured width includes them`() {
    val seen = AtomicReference<Placeable>()
    val result = renderInColumn(
        measuring(
            sized(width = 100, height = 100, style = Style.margin(all = 7.px)),
            SizeConstraints(maxWidth = 300),
            seen,
        ),
    )

    assertThat(seen.get().measuredWidth).describedAs("100 + 7 + 7").isEqualTo(114)
    assertThat(result.getXForChildAtIndex(0)).isEqualTo(7)
    assertThat(result.getChildAt(0).width).isEqualTo(100)
  }

  @Test
  fun `C2 the margins come out of an exact room`() {
    val result = renderInColumn(
        measuring(content(Style.margin(horizontal = 10.px)), exactWidth(300)),
    )

    assertThat(result.getChildAt(0).width).describedAs("300 - 10 - 10").isEqualTo(280)
    val negative =
        renderInColumn(measuring(content(Style.margin(horizontal = (-10f).px)), exactWidth(300)))
    assertThat(negative.getChildAt(0).width).describedAs("300 + 10 + 10").isEqualTo(320)
  }

  @Test
  fun `C3 an own min raises the room's minimum and never lowers it`() {
    val stretched = renderInColumn(measuring(content(Style.minWidth(200.px)), exactWidth(300)))
    val raised =
        renderInColumn(measuring(content(Style.minWidth(400.px)), SizeConstraints(maxWidth = 300)))

    assertThat(stretched.getChildAt(0).width).isEqualTo(300)
    assertThat(raised.getChildAt(0).width).isEqualTo(400)
  }

  @Test
  fun `C4 an own max only narrows the room`() {
    val result = renderInColumn(
        measuring(content(Style.maxWidth(80.px)), exactWidth(300), declareRoomWidth = true),
    )

    assertThat(result.getChildAt(0).width).isEqualTo(80)
    assertThat(result.getXForChildAtIndex(0)).isEqualTo(0)
  }

  @Test
  fun `C5 an own min above an own max wins`() {
    val result = renderInColumn(
        measuring(
            content(Style.minWidth(90.px).maxWidth(50.px)),
            SizeConstraints(maxWidth = 300),
        ),
    )

    assertThat(result.getChildAt(0).width).isEqualTo(90)
  }

  @Test
  fun `C6 an own size fixes the axis, within its own min and max, whatever the room`() {
    val fixed = renderInColumn(measuring(content(Style.width(80.px)), exactWidth(300)))
    val raised =
        renderInColumn(measuring(content(Style.width(80.px).minWidth(120.px)), exactWidth(300)))

    assertThat(fixed.getChildAt(0).width).isEqualTo(80)
    assertThat(raised.getChildAt(0).width).isEqualTo(120)
  }

  @Test
  fun `C7 a percentage size is of the room's maximum and may exceed it`() {
    val seen = AtomicReference<Placeable>()
    val half = renderInColumn(measuring(content(Style.widthPercent(50f)), exactWidth(300)))
    renderInColumn(
        measuring(content(Style.widthPercent(150f)), SizeConstraints(maxWidth = 300), seen),
    )

    assertThat(half.getChildAt(0).width).isEqualTo(150)
    assertThat(seen.get().measuredWidth).isEqualTo(450)
    assertThat(seen.get().width).isEqualTo(300)
  }

  @Test
  fun `C8 an aspect ratio derives the other axis when exactly one is fixed`() {
    val byRoom = AtomicReference<Placeable>()
    val bySize = AtomicReference<Placeable>()
    renderInColumn(measuring(content(Style.aspectRatio(2f)), exactWidth(200), byRoom))
    renderInColumn(
        measuring(content(Style.width(120.px).aspectRatio(3f)), SizeConstraints(), bySize),
    )

    assertThat(byRoom.get().width to byRoom.get().height).isEqualTo(200 to 100)
    assertThat(bySize.get().width to bySize.get().height).isEqualTo(120 to 40)
  }

  @Test
  fun `C8 an aspect ratio has no effect when both axes or neither are fixed`() {
    val both = AtomicReference<Placeable>()
    val neither = AtomicReference<Placeable>()
    renderInColumn(
        measuring(content(Style.height(40.px).aspectRatio(2f)), exactWidth(300), both),
    )
    renderInColumn(
        measuring(content(Style.aspectRatio(2f)), SizeConstraints(maxWidth = 300), neither),
    )

    assertThat(both.get().width to both.get().height).isEqualTo(300 to 40)
    assertThat(neither.get().width to neither.get().height).isEqualTo(30 to 25)
  }

  @Test
  fun `C8 a ratio that isn't a finite number above 0 counts as none`() {
    for (ratio in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -2f)) {
      val seen = AtomicReference<Placeable>()
      renderInColumn(measuring(content(Style.aspectRatio(ratio)), exactWidth(200), seen))
      assertThat(seen.get().width to seen.get().height)
          .describedAs("%s", ratio)
          .isEqualTo(200 to 25)
    }
  }

  @Test
  fun `C8 a derived axis is kept within its own min and max`() {
    val seen = AtomicReference<Placeable>()
    renderInColumn(
        measuring(content(Style.aspectRatio(2f).maxHeight(60.px)), exactWidth(200), seen),
    )

    assertThat(seen.get().width to seen.get().height).isEqualTo(200 to 60)
  }

  @Test
  fun `C9 padding and border set the smallest the box can be`() {
    val result = renderInColumn(
        measuring(
            SimpleMountSpecTester(style = Style.padding(all = 8.px)),
            SizeConstraints.exact(10, 10),
            declareRoomWidth = true,
        ),
    )

    assertThat(result.getChildAt(0).width).isEqualTo(16)
    assertThat(result.getXForChildAtIndex(0)).isEqualTo(0)
  }

  @Test
  fun `C10 a flexbox item prop on a child has no effect`() {
    val child =
        Row(style = Style.width(100.px).height(100.px).flex(grow = 1f)) {
          child(SimpleMountSpecTester())
        }

    val result = renderInColumn(measuring(child, SizeConstraints(maxWidth = VIEWPORT)))
    val based = renderInColumn(
        measuring(content(Style.flex(basis = 200.px)), SizeConstraints(maxWidth = VIEWPORT)),
    )

    assertThat(result.getChildAt(0).width).isEqualTo(100)
    assertThat(based.getChildAt(0).width).describedAs("its content's 30").isEqualTo(30)
  }

  @Test
  fun `C11 a negative size or own max has no effect, and a negative own min acts as 0`() {
    fun width(style: Style, room: SizeConstraints): Int =
        renderInColumn(measuring(content(style), room)).getChildAt(0).width

    assertThat(width(Style.width((-10f).px), SizeConstraints(maxWidth = 300))).isEqualTo(30)
    assertThat(width(Style.widthPercent(-50f), SizeConstraints(maxWidth = 300))).isEqualTo(30)
    assertThat(width(Style.minWidth((-10f).px), SizeConstraints(maxWidth = 300))).isEqualTo(30)
    assertThat(width(Style.maxWidth((-10f).px), exactWidth(300))).isEqualTo(300)
  }

  @Test
  fun `C12 a child's content stays within its room`() {
    val seen = AtomicReference<Placeable>()
    val inner = Layout(
        listOf(sized(width = 400, height = 10)),
        layout =
            LayoutFunctionN { measurables, _ ->
              val placeable = measurables[0].measure(SizeConstraints())
              layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    )
    renderInColumn(measuring(inner, SizeConstraints(maxWidth = 300), seen))

    assertThat(seen.get().width to seen.get().measuredWidth)
        .describedAs("its function declares 400")
        .isEqualTo(300 to 300)
  }

  // ─────────────────────────────────────────────── R: a root Layout

  @Test
  fun `R1 a root fills an exact axis and wraps its content on an at-most one`() {
    val wraps = rootBox(atMost(200), atMost(100), Style.padding(all = 4.px))
    val fills = rootBox(exactly(200), atMost(300), Style)

    assertThat(wraps.size).describedAs("30 x 20 and 4 on each side").isEqualTo(38 to 28)
    assertThat(fills.size).isEqualTo(200 to 20)
  }

  @Test
  fun `R2 a root's margins come out of its room, across an exact axis too`() {
    val across = rootBox(exactly(300), exactly(300), Style.margin(start = 6.px))
    val down = rootBox(exactly(300), exactly(300), Style.margin(vertical = 5.px))

    assertThat(across.size.first).isEqualTo(294)
    assertThat(across.x).describedAs("placed at its margin").isEqualTo(6)
    assertThat(down.size.second).isEqualTo(290)
  }

  @Test
  fun `R3 a root's declared size stays past its room, and a percentage of no bound does nothing`() {
    assertThat(rootBox(exactly(200), atMost(300), Style.width(300.px)).size.first).isEqualTo(300)
    assertThat(rootBox(exactly(200), atMost(300), Style.widthPercent(50f)).size.first)
        .isEqualTo(100)
    assertThat(rootBox(exactly(200), UNBOUNDED, Style.heightPercent(50f)).size.second).isEqualTo(20)
  }

  @Test
  fun `R4 a root's own max bounds its border box, its own min is kept past the room and wins over its max`() {
    val capped = rootBox(exactly(200), atMost(300), Style.maxWidth(190.px).margin(start = 20.px))
    val conflicting = rootBox(exactly(200), atMost(300), Style.minWidth(150.px).maxWidth(100.px))

    assertThat(capped.size.first).describedAs("200 less the 20 margin, within 190").isEqualTo(180)
    assertThat(rootBox(exactly(200), atMost(300), Style.minWidth(300.px)).size.first)
        .describedAs("an own min past an exact room")
        .isEqualTo(300)
    assertThat(rootBox(atMost(200), atMost(300), Style.minWidth(300.px)).size.first)
        .describedAs("an own min past an at-most room")
        .isEqualTo(300)
    assertThat(conflicting.size.first).isEqualTo(150)
  }

  @Test
  fun `R5 a root's aspect ratio derives the other axis only when one is decided`() {
    assertThat(rootBox(exactly(200), atMost(300), Style.aspectRatio(0.5f)).size)
        .isEqualTo(200 to 400)
    assertThat(rootBox(exactly(200), atMost(300), Style.aspectRatio(0.5f).maxHeight(250.px)).size)
        .isEqualTo(200 to 250)
    assertThat(rootBox(atMost(300), atMost(300), Style.width(120.px).aspectRatio(3f)).size)
        .isEqualTo(120 to 40)
    assertThat(
        rootBox(atMost(300), atMost(300), Style.width(120.px).height(30.px).aspectRatio(3f)).size,
    )
        .describedAs("both axes fixed")
        .isEqualTo(120 to 30)
    assertThat(rootBox(atMost(200), atMost(300), Style.aspectRatio(2f)).size).isEqualTo(30 to 20)
  }

  @Test
  fun `R6 a root's padding and border floor it, even past its room`() {
    assertThat(rootBox(exactly(200), atMost(300), Style.padding(horizontal = 120.px)).size.first)
        .isEqualTo(240)
  }

  @Test
  fun `R7 a root's position insets aren't read`() {
    val box = rootBox(exactly(200), atMost(300), Style.position(left = 10.px))

    assertThat(box.x).isEqualTo(0)
    assertThat(box.size).isEqualTo(200 to 20)
  }

  @Test
  fun `R8 a root's negative size or own max has no effect, and a negative own min acts as 0`() {
    assertThat(rootBox(atMost(300), atMost(300), Style.height((-10f).px)).size).isEqualTo(30 to 20)
    assertThat(rootBox(atMost(300), atMost(300), Style.minHeight((-10f).px)).size)
        .isEqualTo(30 to 20)
    assertThat(rootBox(exactly(200), atMost(300), Style.maxWidth((-10f).px)).size)
        .isEqualTo(200 to 20)
  }

  // ─────────────────────────────────────────────── D: differences from a Yoga Column

  @Test
  fun `D1 a child's own min above its own max wins, where Yoga keeps an unstretched Column child's max`() {
    val style = Style.minWidth(80.px).maxWidth(50.px)
    fun layoutChild(child: Component): Int =
        renderInColumn(measuring(child, SizeConstraints(maxWidth = 300))).getChildAt(0).width
    fun columnChild(child: Component, align: YogaAlign): Int =
        renderTree(Column(alignItems = align, style = Style.width(300.px)) { child(child) })
            .getChildAt(0)
            .width

    assertThat(layoutChild(Column(style = style) { child(fixed(30, 20)) })).isEqualTo(80)
    assertThat(layoutChild(Row(style = style) { child(fixed(30, 20)) })).isEqualTo(80)
    assertThat(layoutChild(SimpleMountSpecTester(style = style))).isEqualTo(80)
    assertThat(columnChild(Column(style = style) { child(fixed(30, 20)) }, YogaAlign.FLEX_START))
        .describedAs("a Column child a Column doesn't stretch keeps its max")
        .isEqualTo(50)
    assertThat(columnChild(Column(style = style) { child(fixed(30, 20)) }, YogaAlign.STRETCH))
        .isEqualTo(80)
    assertThat(columnChild(Row(style = style) { child(fixed(30, 20)) }, YogaAlign.FLEX_START))
        .isEqualTo(80)
    assertThat(columnChild(SimpleMountSpecTester(style = style), YogaAlign.FLEX_START))
        .isEqualTo(80)
  }

  @Test
  fun `D2 a percentage height on an unbounded axis keeps its content's, where Yoga takes it of an ancestor`() {
    val layout = Layout(
        listOf(content(Style.heightPercent(50f))),
        layout =
            LayoutFunctionN { measurables, constraints ->
              val placeable =
                  measurables[0].measure(SizeConstraints(maxWidth = constraints.maxWidth))
              layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
    )
    fun inTallColumn(child: Component): LithoLayoutResult = renderTree(
        Column(alignItems = YogaAlign.FLEX_START) { child(child) },
        widthSpec = exactly(300),
        heightSpec = exactly(500),
    )

    assertThat(inTallColumn(layout).getChildAt(0).getChildAt(0).height).isEqualTo(25)
    assertThat(inTallColumn(content(Style.heightPercent(50f))).getChildAt(0).height)
        .describedAs("50% of the 500-high Column")
        .isEqualTo(250)
  }

  @Test
  fun `D3 a child's negative own max has no effect, where Yoga gives a stretched child no width`() {
    val style = Style.maxWidth((-10f).px)

    assertThat(renderInColumn(measuring(content(style), exactWidth(300))).getChildAt(0).width)
        .isEqualTo(300)
    assertThat(
        renderTree(Column(style = Style.width(300.px)) { child(content(style)) })
            .getChildAt(0)
            .width,
    )
        .isEqualTo(0)
  }

  @Test
  fun `D4 exact constraints win over a child's aspect ratio, where a stretched Yoga child derives its width`() {
    val style = Style.height(40.px).aspectRatio(2f)
    val seen = AtomicReference<Placeable>()
    renderInColumn(measuring(content(style), exactWidth(300), seen))
    val stretched =
        renderTree(Column(style = Style.width(300.px)) { child(content(style)) }).getChildAt(0)

    assertThat(seen.get().width to seen.get().height).isEqualTo(300 to 40)
    assertThat(stretched.width to stretched.height).isEqualTo(80 to 40)
  }

  @Test
  fun `D5 a child's aspect ratio with neither axis fixed has no effect, where Yoga derives one from its content`() {
    val style = Style.aspectRatio(2f)
    val seen = AtomicReference<Placeable>()
    renderInColumn(measuring(content(style), SizeConstraints(maxWidth = 300), seen))
    val inColumn = renderTree(
        Column(alignItems = YogaAlign.FLEX_START, style = Style.width(300.px)) {
          child(content(style))
        },
    )
        .getChildAt(0)
    val inRow = renderTree(
        Row(alignItems = YogaAlign.FLEX_START, style = Style.width(300.px)) {
          child(content(style))
        },
    )
        .getChildAt(0)

    assertThat(seen.get().width to seen.get().height).isEqualTo(30 to 25)
    assertThat(inColumn.width to inColumn.height)
        .describedAs("the width from the content's height")
        .isEqualTo(50 to 25)
    assertThat(inRow.width to inRow.height)
        .describedAs("the height from the content's width")
        .isEqualTo(30 to 15)
  }

  @Test
  fun `D6 a child's percentage under an at-most room is of its maximum, where a wrapping Yoga Column treats it as auto`() {
    val style = Style.widthPercent(50f)
    val layoutChild =
        renderInColumn(measuring(content(style), SizeConstraints(maxWidth = 300))).getChildAt(0)
    val wrapped = renderTree(
        Column(alignItems = YogaAlign.FLEX_START) {
          child(Column(alignItems = YogaAlign.FLEX_START) { child(content(style)) })
        },
    )
        .getChildAt(0)
        .getChildAt(0)

    assertThat(layoutChild.width).isEqualTo(150)
    assertThat(wrapped.width).describedAs("its content's width").isEqualTo(30)
  }

  @Test
  fun `D7 a root's own min above its own max wins on both axes, where Yoga depends on direction`() {
    val across = Style.minWidth(80.px).maxWidth(50.px)
    val down = Style.minHeight(200.px).maxHeight(50.px)

    assertThat(rootBox(atMost(300), atMost(300), across).size.first).isEqualTo(80)
    assertThat(yogaRootBox(atMost(300), atMost(300), across, row = false).size.first)
        .describedAs("a Column root keeps the max across")
        .isEqualTo(50)
    assertThat(yogaRootBox(atMost(300), atMost(300), across, row = true).size.first)
        .describedAs("a Row root keeps the min across")
        .isEqualTo(80)
    assertThat(rootBox(atMost(300), atMost(300), down).size.second).isEqualTo(200)
    assertThat(yogaRootBox(atMost(300), atMost(300), down, row = false).size.second)
        .describedAs("a Column root keeps the min down")
        .isEqualTo(200)
    assertThat(yogaRootBox(atMost(300), atMost(300), down, row = true).size.second)
        .describedAs("a Row root keeps the max down")
        .isEqualTo(50)
  }

  @Test
  fun `D8 a root's margins come out of an exact axis, where a Yoga root stays the room's size`() {
    val style = Style.margin(start = 6.px)

    assertThat(rootBox(exactly(300), exactly(300), style).size.first).isEqualTo(294)
    assertThat(yogaRootBox(exactly(300), exactly(300), style).size.first).isEqualTo(300)
    assertThat(
        renderTree(
            Column { child(Column(style = style) { child(fixed(30, 20)) }) },
            widthSpec = exactly(300),
            heightSpec = exactly(300),
        )
            .getChildAt(0)
            .width,
    )
        .describedAs("as a child a Column stretches")
        .isEqualTo(294)
  }

  @Test
  fun `D9 a root's aspect ratio derives the other axis, where a Yoga root wraps its content`() {
    val style = Style.width(120.px).aspectRatio(3f)

    assertThat(rootBox(atMost(300), atMost(300), style).size).isEqualTo(120 to 40)
    assertThat(yogaRootBox(atMost(300), atMost(300), style).size).isEqualTo(120 to 20)
    val child = renderTree(
        Column { child(Column(style = style) { child(fixed(30, 20)) }) },
        widthSpec = atMost(300),
        heightSpec = atMost(300),
    )
        .getChildAt(0)
    assertThat(child.width to child.height).describedAs("as a Column's child").isEqualTo(120 to 40)
  }

  @Test
  fun `D10 a root's own max bounds its border box, where a Yoga root's depends on the axis`() {
    fun layoutWidth(widthSpec: Int, style: Style): Int =
        rootBoxOf(widthSpec, atMost(300)) {
              Layout(
                  listOf(fixed(300, 300)),
                  style = style,
                  layout =
                      LayoutFunctionN { measurables, constraints ->
                        val placeable =
                            measurables[0].measure(
                                SizeConstraints(
                                    maxWidth = constraints.maxWidth,
                                    maxHeight = constraints.maxHeight,
                                ),
                            )
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                      },
              )
            }
            .size
            .first
    fun yogaWidth(widthSpec: Int, style: Style): Int =
        rootBoxOf(widthSpec, atMost(300)) { Column(style = style) { child(fixed(300, 300)) } }
            .size
            .first
    val wide = Style.maxWidth(190.px).margin(start = 20.px)
    val narrow = Style.maxWidth(150.px).margin(start = 20.px)

    assertThat(layoutWidth(exactly(200), wide))
        .describedAs("200 less 20, within 190")
        .isEqualTo(180)
    assertThat(yogaWidth(exactly(200), wide))
        .describedAs("exactly: the max ignores the margins")
        .isEqualTo(190)
    assertThat(layoutWidth(atMost(200), narrow)).isEqualTo(150)
    assertThat(yogaWidth(atMost(200), narrow)).describedAs("at most: 150 less 20").isEqualTo(130)
  }

  @Test
  fun `D11 a root's position insets aren't read, where a Yoga root's move it`() {
    val style = Style.position(left = 10.px)

    assertThat(rootBox(exactly(200), atMost(300), style).x).isEqualTo(0)
    assertThat(yogaRootBox(exactly(200), atMost(300), style).x).isEqualTo(10)
  }

  @Test
  fun `D12 a root's negative size has no effect, where a Yoga root fills its room`() {
    val style = Style.height((-10f).px)

    assertThat(rootBox(atMost(300), atMost(300), style).size.second).isEqualTo(20)
    assertThat(yogaRootBox(atMost(300), atMost(300), style).size.second).isEqualTo(300)
  }

  @Test
  fun `D13 a root's own min is kept past its room, where a Yoga Column root keeps it to an at-most room`() {
    val style = Style.minWidth(300.px)

    assertThat(rootBox(atMost(200), atMost(300), style).size.first).isEqualTo(300)
    assertThat(yogaRootBox(atMost(200), atMost(300), style).size.first).isEqualTo(200)
    assertThat(yogaRootBox(atMost(200), atMost(300), style, row = true).size.first).isEqualTo(300)
  }

  // ─────────────────────────────────────────────── L: known limitation

  @Test
  fun `L1 a Yoga subtree wider than its room is laid out within it, where a Column child keeps its content's width`() {
    fun row(): Component = Row {
      child(SimpleMountSpecTester(style = Style.width(48.px).height(20.px).flex(shrink = 0f)))
      child(SimpleMountSpecTester(style = Style.width(50.px).height(10.px).flex(shrink = 0f)))
    }

    val underLayout = renderInColumn(measuring(row(), SizeConstraints(maxWidth = 80)))
    val inColumn = renderTree(
        Column(alignItems = YogaAlign.FLEX_START, style = Style.width(80.px)) {
          child(row())
        },
    )

    assertThat(underLayout.getChildAt(0).width).isEqualTo(80)
    assertThat(inColumn.getChildAt(0).width).isEqualTo(98)
  }

  // ─────────────────────────────────────────────── F: the figures' numbers

  @Test
  fun `F1 the anatomy figure's numbers`() {
    val seen = AtomicReference<SizeConstraints>()
    val child = AtomicReference<Placeable>()
    val container = Layout(
        listOf(content(Style)),
        style = Style.width(fig("F1.layoutWidth").px).padding(all = fig("F1.padding").px),
        layout =
            LayoutFunctionN { measurables, constraints ->
              seen.set(constraints)
              val placeable = measurables[0].measure(constraints.copy(minWidth = 0))
              child.set(placeable)
              layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
            },
    )

    val result = renderInColumn(container)

    assertThat(seen.get().minWidth to seen.get().maxWidth)
        .isEqualTo(fig("F1.contentWidth") to fig("F1.contentWidth"))
    assertThat(child.get().width to child.get().height)
        .isEqualTo(fig("F1.childWidth") to fig("F1.childHeight"))
    assertThat(result.getXForChildAtIndex(0) to result.getYForChildAtIndex(0))
        .isEqualTo(fig("F1.childX") to fig("F1.childY"))
  }

  @Test
  fun `F2 the Placeable anatomy figure's numbers`() {
    val margined = AtomicReference<Placeable>()
    val marginResult = renderInColumn(
        measuring(
            sized(
                width = fig("F2.marginOwnWidth"),
                height = 25,
                style = Style.margin(all = fig("F2.margin").px),
            ),
            SizeConstraints(maxWidth = fig("F2.marginRoom")),
            margined,
        ),
    )
    val overflowing = AtomicReference<Placeable>()
    renderInColumn(
        measuring(
            sized(width = fig("F2.overflowOwnWidth"), height = 25),
            SizeConstraints(maxWidth = fig("F2.overflowRoom")),
            overflowing,
        ),
    )

    assertThat(margined.get().measuredWidth).isEqualTo(fig("F2.marginMeasuredWidth"))
    assertThat(marginResult.getXForChildAtIndex(0)).isEqualTo(fig("F2.marginBoxX"))
    assertThat(overflowing.get().width to overflowing.get().measuredWidth)
        .isEqualTo(fig("F2.overflowWidth") to fig("F2.overflowMeasuredWidth"))
  }

  @Test
  fun `F3 the start edge figure's numbers`() {
    fun placedAt(width: Int, room: SizeConstraints, rtl: Boolean): Int = renderInColumn(
        measuring(
            sized(width = width, height = 25),
            room,
            declareRoomWidth = room.hasExactWidth,
            style =
                if (rtl) Style.layoutDirection(YogaDirection.RTL)
                else Style.layoutDirection(YogaDirection.LTR),
        ),
    )
        .getXForChildAtIndex(0)

    assertThat(placedAt(fig("F3.shortWidth"), exactWidth(fig("F3.shortRoom")), rtl = false))
        .isEqualTo(fig("F3.shortX"))
    assertThat(
        placedAt(fig("F3.overWidth"), SizeConstraints(maxWidth = fig("F3.overRoom")), false),
    )
        .isEqualTo(fig("F3.overX"))
    assertThat(placedAt(fig("F3.rtlWidth"), exactWidth(fig("F3.rtlRoom")), rtl = true))
        .isEqualTo(fig("F3.rtlX"))
  }

  @Test
  fun `F4 the place and placeRelative figure's numbers`() {
    fun placedAt(
        direction: YogaDirection,
        relative: Boolean,
        offset: Int,
        declared: Int?,
    ): Int {
      val container = Layout(
          listOf(sized(width = fig("F4.child"), height = 25)),
          style = Style.layoutDirection(direction),
          layout =
              LayoutFunctionN { measurables, constraints ->
                val placeable = measurables[0].measure(SizeConstraints.exact(fig("F4.child"), 25))
                layout(declared ?: constraints.maxWidth, 25) {
                  if (relative) placeable.placeRelative(start = offset, top = 0)
                  else placeable.place(x = offset, y = 0)
                }
              },
      )
      return renderStretched(container).getXForChildAtIndex(0)
    }
    val offset = fig("F4.offset")

    assertThat(placedAt(YogaDirection.LTR, true, offset, null)).isEqualTo(fig("F4.ltrX"))
    assertThat(placedAt(YogaDirection.LTR, false, offset, null)).isEqualTo(fig("F4.ltrX"))
    assertThat(placedAt(YogaDirection.RTL, true, offset, null)).isEqualTo(fig("F4.rtlRelativeX"))
    assertThat(placedAt(YogaDirection.RTL, false, offset, null)).isEqualTo(fig("F4.rtlPlaceX"))
    val stretched = fig("F4.stretchedDeclared")
    assertThat(placedAt(YogaDirection.RTL, false, 0, stretched)).isEqualTo(fig("F4.stretchedX"))
    assertThat(placedAt(YogaDirection.RTL, true, 0, stretched)).isEqualTo(fig("F4.stretchedX"))
    val narrowed = fig("F4.narrowedDeclared")
    assertThat(placedAt(YogaDirection.RTL, false, 0, narrowed)).isEqualTo(fig("F4.narrowedPlaceX"))
    assertThat(placedAt(YogaDirection.RTL, true, 0, narrowed))
        .isEqualTo(fig("F4.narrowedRelativeX"))
    assertThat(fig("F4.container")).isEqualTo(VIEWPORT)
  }

  @Test
  fun `F5 the declared size figure's numbers`() {
    fun result(declared: Int, align: YogaAlign, parentWidth: Int, style: Style): Pair<Int, Int> {
      val runs = AtomicInteger(0)
      val container = Layout(
          listOf(sized(width = 10, height = 10)),
          style = style,
          layout =
              LayoutFunctionN { measurables, _ ->
                runs.incrementAndGet()
                val placeable = measurables[0].measure(SizeConstraints.exact(10, 10))
                layout(declared, 10) { placeable.place(0, 0) }
              },
      )
      val width = findLayout(
          renderTree(
              Column(alignItems = align, style = Style.width(parentWidth.px)) {
                child(container)
              },
          ),
      )
          .width
      return width to runs.get()
    }

    assertThat(
        result(fig("F5.stretchDeclared"), YogaAlign.STRETCH, fig("F5.stretchParent"), Style),
    )
        .isEqualTo(fig("F5.stretchFinal") to fig("F5.stretchRuns"))
    assertThat(
        result(fig("F5.pastDeclared"), YogaAlign.FLEX_START, fig("F5.pastRoom"), Style).first,
    )
        .isEqualTo(fig("F5.pastFinal"))
    assertThat(
        result(
            fig("F5.pastDeclared"),
            YogaAlign.FLEX_START,
            fig("F5.pastRoom"),
            Style.maxWidth(fig("F5.cappedMax").px),
        )
            .first,
    )
        .isEqualTo(fig("F5.cappedFinal"))
    assertThat(
        result(fig("F5.pastDeclared"), YogaAlign.STRETCH, fig("F5.exactRoom"), Style).first,
    )
        .isEqualTo(fig("F5.exactFinal"))
  }

  @Test
  fun `F6 the own style figure's numbers`() {
    val room = fig("F6.room")
    fun width(style: Style, constraints: SizeConstraints): Int =
        renderInColumn(measuring(content(style), constraints)).getChildAt(0).width

    assertThat(width(Style.minWidth(fig("F6.c3StretchMin").px), exactWidth(room)))
        .isEqualTo(fig("F6.c3StretchResult"))
    assertThat(width(Style.minWidth(fig("F6.c3PastMin").px), SizeConstraints(maxWidth = room)))
        .isEqualTo(fig("F6.c3PastResult"))
    assertThat(width(Style.maxWidth(fig("F6.c4Max").px), exactWidth(room)))
        .isEqualTo(fig("F6.c4Result"))
    assertThat(width(Style.width(fig("F6.c6Width").px), exactWidth(room)))
        .isEqualTo(fig("F6.c6Result"))
    assertThat(
        width(
            Style.width(fig("F6.c6Width").px).minWidth(fig("F6.c6MinWidth").px),
            exactWidth(room),
        ),
    )
        .isEqualTo(fig("F6.c6MinResult"))
    assertThat(width(Style.widthPercent(fig("F6.c7HalfPercent").toFloat()), exactWidth(room)))
        .isEqualTo(fig("F6.c7HalfResult"))
    val over = AtomicReference<Placeable>()
    renderInColumn(
        measuring(
            content(Style.widthPercent(fig("F6.c7OverPercent").toFloat())),
            SizeConstraints(maxWidth = room),
            over,
        ),
    )
    assertThat(over.get().measuredWidth to over.get().width)
        .isEqualTo(fig("F6.c7OverMeasured") to fig("F6.c7OverWidth"))
    val padded = renderInColumn(
        measuring(
            SimpleMountSpecTester(style = Style.padding(all = fig("F6.c9Padding").px)),
            SizeConstraints.exact(fig("F6.c9Room"), fig("F6.c9Room")),
            declareRoomWidth = true,
        ),
    )
    assertThat(padded.getChildAt(0).width).isEqualTo(fig("F6.c9Result"))
  }

  @Test
  fun `F7 the action bar figure's numbers`() {
    fun shownWidth(room: Int): Int {
      val container = Layout(
          sized(width = fig("F7.labelledWidth"), height = fig("F7.labelledHeight")),
          sized(width = fig("F7.iconWidth"), height = fig("F7.iconHeight")),
      ) { labelled, icons, _ ->
        val labelledPlaceable =
            labelled.measure(SizeConstraints(maxWidth = SizeConstraints.Infinity))
        val chosen =
            if (labelledPlaceable.measuredWidth <= room) labelledPlaceable
            else icons.measure(SizeConstraints(maxWidth = room))
        layout(room, chosen.height) { chosen.placeRelative(0, 0) }
      }
      return renderInColumn(container).getChildAt(0).width
    }

    assertThat(shownWidth(fig("F7.fitsRoom"))).isEqualTo(fig("F7.labelledWidth"))
    assertThat(shownWidth(fig("F7.tightRoom"))).isEqualTo(fig("F7.iconWidth"))
  }

  // ─────────────────────────────────────────────── G: the guide's other claims

  @Test
  fun `G1 aligning to the end by measuredWidth puts an overflowing child's end at the end`() {
    fun placedAt(byMeasuredWidth: Boolean): Int {
      val container = Layout(
          listOf(sized(width = 150, height = 20)),
          layout =
              LayoutFunctionN { measurables, _ ->
                val placeable = measurables[0].measure(SizeConstraints(maxWidth = 100))
                val childWidth = if (byMeasuredWidth) placeable.measuredWidth else placeable.width
                layout(100, placeable.height) { placeable.place(x = 100 - childWidth, y = 0) }
              },
      )
      return renderInColumn(container).getXForChildAtIndex(0)
    }

    assertThat(placedAt(byMeasuredWidth = false))
        .describedAs("by width: 50 past the end")
        .isEqualTo(0)
    assertThat(placedAt(byMeasuredWidth = true)).describedAs("its end at 100").isEqualTo(-50)
  }

  @Test
  fun `G2 a function that measures while placing, places twice, declares twice or never declares is refused`() {
    val child = sized(width = 10, height = 10)
    val measuresWhilePlacing = LayoutFunctionN { measurables, _ ->
      val scope = this
      layout(10, 10) {
        with(scope) { measurables[0].measure(SizeConstraints.exact(10, 10)) }.place(0, 0)
      }
    }
    val placesTwice = LayoutFunctionN { measurables, _ ->
      val placeable = measurables[0].measure(SizeConstraints.exact(10, 10))
      layout(10, 10) {
        placeable.place(0, 0)
        placeable.place(0, 0)
      }
    }
    val declaresTwice = LayoutFunctionN { _, _ ->
      layout(10, 10) {}
      layout(10, 10) {}
    }
    val neverDeclares = LayoutFunctionN { _, _ -> }

    assertThatThrownBy { renderInColumn(Layout(listOf(child), layout = measuresWhilePlacing)) }
        .hasMessageContaining("can only be measured by its layout function")
    assertThatThrownBy { renderInColumn(Layout(listOf(child), layout = placesTwice)) }
        .hasMessageContaining("has already been placed")
    assertThatThrownBy { renderInColumn(Layout(listOf(child), layout = declaresTwice)) }
        .hasMessageContaining("has already been called")
    assertThatThrownBy { renderInColumn(Layout(listOf(child), layout = neverDeclares)) }
        .hasMessageContaining("must finish by calling layout(width, height)")
  }

  @Test
  fun `G3 constraints with a minimum force a child up to it`() {
    val result = renderInColumn(
        measuring(content(Style), SizeConstraints(minWidth = 300, maxWidth = VIEWPORT)),
    )

    assertThat(result.getChildAt(0).width).describedAs("its content is 30").isEqualTo(300)
  }

  @Test
  fun `G4 the function runs once when its parent keeps its size, and again when it overrides it`() {
    fun runs(declaresWhatItGets: Boolean): Int {
      val count = AtomicInteger(0)
      val container = Layout(
          listOf(sized(width = 100, height = 100)),
          layout =
              LayoutFunctionN { measurables, constraints ->
                count.incrementAndGet()
                val placeable = measurables[0].measure(SizeConstraints.exact(100, 100))
                val width = if (declaresWhatItGets) constraints.maxWidth else 100
                layout(width, 100) { placeable.place(0, 0) }
              },
      )
      renderStretched(container)
      return count.get()
    }

    assertThat(runs(declaresWhatItGets = true)).isEqualTo(1)
    assertThat(runs(declaresWhatItGets = false))
        .describedAs("asked for 100, given 1000")
        .isEqualTo(2)
  }

  // ─────────────────────────────────────────────── helpers

  /** A `Row` of 10 x 10 and 20 x 25, styled with [style]: content 30 x 25. */
  private fun content(style: Style): Component =
      Row(style = style) {
        child(SimpleMountSpecTester(style = Style.width(10.px).height(10.px)))
        child(SimpleMountSpecTester(style = Style.width(20.px).height(25.px)))
      }

  private fun sized(width: Int, height: Int, style: Style = Style): Component =
      SimpleMountSpecTester(style = Style.width(width.px).height(height.px) + style)

  private fun exactWidth(width: Int): SizeConstraints =
      SizeConstraints(minWidth = width, maxWidth = width, maxHeight = INFINITY)

  /**
   * A `Layout` that measures its only child under [room] into [seen] and places it at its origin.
   * It declares the child's size, or the room's width when [declareRoomWidth].
   */
  private fun measuring(
      child: Component,
      room: SizeConstraints,
      seen: AtomicReference<Placeable>? = null,
      declareRoomWidth: Boolean = false,
      style: Style = Style,
  ): Layout = Layout(
      listOf(child),
      style = style,
      layout =
          LayoutFunctionN { measurables, _ ->
            val placeable = measurables[0].measure(room)
            seen?.set(placeable)
            val width = if (declareRoomWidth) room.maxWidth else placeable.width
            layout(width, placeable.height) { placeable.place(0, 0) }
          },
  )

  /** [container] in a `Column` that doesn't stretch it, in a 1000 x 1000 tree: its result. */
  private fun renderInColumn(container: Layout): LithoLayoutResult =
      findLayout(renderTree(Column(alignItems = YogaAlign.FLEX_START) { child(container) }))

  /** [container] in a `Column` that stretches it to 1000: its result. */
  private fun renderStretched(container: Layout): LithoLayoutResult =
      findLayout(renderTree(Column { child(container) }))

  private fun renderTree(
      component: Component,
      widthSpec: Int = exactly(VIEWPORT),
      heightSpec: Int = exactly(VIEWPORT),
  ): LithoLayoutResult =
      rootResult(lithoTestRule.render(widthSpec = widthSpec, heightSpec = heightSpec) { component })

  private fun findLayout(result: LithoLayoutResult): LithoLayoutResult =
      checkNotNull(findLayoutOrNull(result)) { "no Layout in the tree" }

  private fun findLayoutOrNull(result: LithoLayoutResult): LithoLayoutResult? {
    if (result.node.tailComponent is Layout) return result
    for (i in 0 until result.childCount) {
      findLayoutOrNull(result.getChildAt(i))?.let { found ->
        return found
      }
    }
    return null
  }

  /** A root `Layout` styled with [style], wrapping a 30 x 20 view, under the root specs. */
  private fun rootBox(widthSpec: Int, heightSpec: Int, style: Style): RootBox =
      rootBoxOf(widthSpec, heightSpec) {
        Layout(
            listOf(fixed(30, 20)),
            style = style,
            layout =
                LayoutFunctionN { measurables, constraints ->
                  val placeable =
                      measurables[0].measure(
                          SizeConstraints(
                              maxWidth = constraints.maxWidth,
                              maxHeight = constraints.maxHeight,
                          ),
                      )
                  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        )
      }

  /** The same as a Yoga `Column` root, or a `Row` root when [row]. */
  private fun yogaRootBox(
      widthSpec: Int,
      heightSpec: Int,
      style: Style,
      row: Boolean = false,
  ): RootBox =
      rootBoxOf(widthSpec, heightSpec) {
        if (row) Row(style = style) { child(fixed(30, 20)) }
        else Column(style = style) { child(fixed(30, 20)) }
      }

  private fun rootBoxOf(widthSpec: Int, heightSpec: Int, root: () -> Component): RootBox {
    val view = lithoTestRule.render(widthPx = 200, heightPx = 300) { root() }
    view.setSizeSpecs(widthSpec, heightSpec).measure().layout()
    lithoTestRule.idle()
    val result = rootResult(view)
    val origin =
        checkNotNull(view.componentTree.mainThreadLayoutState?.animatableRootItem)
            .getAbsoluteBounds()
    return RootBox(result.width to result.height, origin.left, origin.top)
  }

  private fun rootResult(view: TestLithoView): LithoLayoutResult =
      checkNotNull(view.componentTree.mainThreadLayoutState?.rootLayoutResult) as LithoLayoutResult

  /** A root's border box, and where its holder places it. */
  private data class RootBox(val size: Pair<Int, Int>, val x: Int, val y: Int)

  private fun fixed(width: Int, height: Int): Component = FixedView(width, height)

  /** A Primitive of [width] by [height] within its constraints, mounting a plain View. */
  private class FixedView(private val width: Int, private val height: Int) : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
        layoutBehavior = FixedBehavior(width, height),
        mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
        style = null,
    )
  }

  private class FixedBehavior(private val width: Int, private val height: Int) : LayoutBehavior {
    override fun PrimitiveLayoutScope.layout(
        sizeConstraints: SizeConstraints,
    ): PrimitiveLayoutResult = PrimitiveLayoutResult(
        width = minOf(width, sizeConstraints.maxWidth),
        height = minOf(height, sizeConstraints.maxHeight),
    )
  }
}

private fun exactly(size: Int): Int = SizeSpec.makeSizeSpec(size, SizeSpec.EXACTLY)

private fun atMost(size: Int): Int = SizeSpec.makeSizeSpec(size, SizeSpec.AT_MOST)

private val UNBOUNDED: Int = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
