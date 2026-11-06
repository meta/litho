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

package com.facebook.litho

import android.graphics.Color
import android.graphics.Rect
import com.facebook.litho.YogaLayoutOutput.Companion.getYogaNode
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.margin
import com.facebook.litho.core.marginPercent
import com.facebook.litho.core.maxHeight
import com.facebook.litho.core.maxHeightPercent
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.maxWidthPercent
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.minWidthPercent
import com.facebook.litho.core.padding
import com.facebook.litho.core.paddingPercent
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.aspectRatio
import com.facebook.litho.flexbox.border
import com.facebook.litho.flexbox.flex
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.layout.LayoutFunctionN
import com.facebook.litho.layout.Placeable
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.stats.LithoStats
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.litho.widget.Text
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaEdge
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val VIEWPORT = 1000
private const val CHILD = 100
/**
 * Lays its children out at 0, 0 and declares their measured size, past its constraints if need be.
 */
private val DECLARES_MEASURED = LayoutFunctionN { measurables, constraints ->
  val placeables = measurables.map {
    it.measure(SizeConstraints(0, constraints.maxWidth, 0, constraints.maxHeight))
  }
  layout(placeables.maxOf { it.measuredWidth }, placeables.maxOf { it.measuredHeight }) {
    for (placeable in placeables) placeable.place(0, 0)
  }
}

private const val PARITY_WIDTH = 300
private const val ROW_CONTENT_WIDTH = 30
private const val ROW_CONTENT_HEIGHT = 25

/** Child styles a container lays out exactly as a Column does, stretched or not. */
private val COLUMN_PARITY: List<Pair<String, Style>> = listOf(
    "no props" to Style,
    "padding by logical edge" to
        Style.padding(start = 7.px, top = 5.px, end = 11.px, bottom = 3.px),
    "percent padding" to Style.paddingPercent(all = 5f),
    "percent start padding" to Style.paddingPercent(start = 10f),
    "margins by logical edge" to Style.margin(start = 7.px, top = 5.px, end = 11.px, bottom = 3.px),
    "percent margins" to Style.marginPercent(all = 5f),
    "percent vertical margins" to Style.marginPercent(vertical = 10f),
    "negative start margin" to Style.margin(start = (-9f).px),
    "min width" to Style.minWidth(200.px),
    "percent min width" to Style.minWidthPercent(80f),
    "min and max height" to Style.minHeight(40.px).maxHeight(60.px),
    "width" to Style.width(80.px),
    "percent width" to Style.widthPercent(50f),
    "width, margins and padding" to
        Style.width(120.px).margin(start = 10.px, end = 4.px).padding(start = 6.px),
    "max width" to Style.maxWidth(20.px),
    "percent max width" to Style.maxWidthPercent(10f),
    "max width and margins" to Style.maxWidth(50.px).margin(start = 10.px, end = 10.px),
    "aspect ratio with a width" to Style.width(80.px).aspectRatio(2f),
    "aspect ratio with a height" to Style.height(40.px).aspectRatio(2f),
    "aspect ratio with a percent width" to Style.widthPercent(20f).aspectRatio(0.5f),
)

/**
 * Rows that differ from a stretching Column by a rule (see the next test): exact constraints
 * override a width derived from an aspect ratio.
 */
private val UNSTRETCHED_ONLY: Set<String> = setOf(
    "aspect ratio with a width",
    "aspect ratio with a height",
    "aspect ratio with a percent width",
)

/** Measures the only child under [constraints] and places it at the origin. */
private fun measuringUnder(constraints: (SizeConstraints) -> SizeConstraints): LayoutFunctionN =
    LayoutFunctionN { measurables, layoutConstraints ->
      val placeable = measurables[0].measure(constraints(layoutConstraints))
      layout(layoutConstraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }

/**
 * A custom layout applies a child's own box props itself — margins, size, min and max, aspect
 * ratio, padding and border — within the constraints it measures the child under, and Yoga lays out
 * only the child's content.
 */
@RunWith(LithoTestRunner::class)
class CustomLayoutBoxConstraintsTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `a vertical percent margin is taken of the constraints' width, as in CSS`() {
    val child = SimpleMountSpecTester(style = Style.height(CHILD.px).marginPercent(top = 10f))

    val result = renderAndFindContainer(
        Layout(
            listOf(child),
            layout = measuringUnder { SizeConstraints(0, 200, 0, VIEWPORT) },
        ),
    )

    assertThat(result.getYForChildAtIndex(0))
        .describedAs("10% of the 200 width, not of the height")
        .isEqualTo(20)
  }

  @Test
  fun `a percent padding is resolved against the constraints the child is placed with`() {
    val child = SimpleMountSpecTester(style = Style.height(CHILD.px).paddingPercent(all = 5f))
    val layout = LayoutFunctionN { ms, c ->
      ms[0].measure(SizeConstraints(0, 400, 0, CHILD * 2))
      val p = ms[0].measure(SizeConstraints(0, 200, 0, CHILD * 2))
      layout(c.maxWidth, p.height) { p.place(0, 0) }
    }

    val laidOut = renderAndFindContainer(Layout(listOf(child), layout = layout)).getChildAt(0)

    assertThat(laidOut.paddingLeft)
        .describedAs("5% of 200, not of the 400 asked first")
        .isEqualTo(10)
  }

  @Test
  fun `a child's content keeps the direction it inherits`() {
    val row =
        Row(style = Style.width(CHILD.px)) {
          child(SimpleMountSpecTester(style = Style.width(10.px).height(10.px)))
          child(SimpleMountSpecTester(style = Style.width(20.px).height(10.px)))
        }
    val rtl =
        Column(style = Style.layoutDirection(YogaDirection.RTL)) {
          child(Layout(listOf(row), layout = measuringUnder { it.copy(minWidth = 0) }))
        }

    val laidOutRow = renderAndFindContainer(rtl).getChildAt(0)

    assertThat(laidOutRow.getXForChildAtIndex(0))
        .describedAs("the first item of an RTL row sits at its right edge")
        .isEqualTo(CHILD - 10)
  }

  @Test
  fun `padding that cannot fit answers the smallest box the child can have`() {
    val child = SimpleMountSpecTester(style = Style.padding(all = 8.px))

    val result = renderAndFindContainer(
        Layout(listOf(child), layout = measuringUnder { SizeConstraints.exact(10, 10) }),
    )

    assertThat(result.getChildAt(0).width).isEqualTo(16)
  }

  @Test
  fun `a flexbox-item prop on a container child is not written to its node`() {
    // A container child, not a leaf: a leaf has no Yoga node to check, and a container child's is
    // the root of its own tree, which no flexbox parent reads.
    val child =
        Row(style = Style.width(CHILD.px).height(CHILD.px).flex(grow = 1f)) {
          child(SimpleMountSpecTester())
        }

    val result = renderAndFindContainer(
        Layout(listOf(child), layout = measuringUnder { it.copy(minWidth = 0) }),
    )

    assertThat(result.getChildAt(0).getYogaNode().flexGrow).isEqualTo(0f)
    assertThat(result.getChildAt(0).width).isEqualTo(CHILD)
  }

  /**
   * The inspector shows a container child's border on every edge: the declared width where there is
   * one, and what the node was laid out with — 0 — where there is none.
   */
  @Test
  fun `the inspector shows a container child's border`() {
    val border =
        Border.create(lithoTestRule.context)
            .widthPx(YogaEdge.LEFT, 4)
            .color(YogaEdge.LEFT, Color.RED)
            .build()
    val bordered =
        SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px).border(border))
    val plain = SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px))
    val stack = LayoutFunctionN { ms, c ->
      val ps = ms.map {
        it.measure(SizeConstraints(maxWidth = c.maxWidth, maxHeight = c.maxHeight))
      }
      layout(c.maxWidth, CHILD) { ps.forEach { it.place(0, 0) } }
    }

    val result = renderAndFindContainer(Layout(listOf(bordered, plain), layout = stack))

    assertThat(DebugLayoutNode(result.getChildAt(0)).getBorderWidth(YogaEdge.LEFT)).isEqualTo(4f)
    assertThat(DebugLayoutNode(result.getChildAt(0)).getBorderWidth(YogaEdge.TOP)).isEqualTo(0f)
    assertThat(DebugLayoutNode(result.getChildAt(1)).getBorderWidth(YogaEdge.LEFT)).isEqualTo(0f)
  }

  @Test
  fun `a child with margins is laid out once for its first measurement`() {
    val child =
        SimpleMountSpecTester(style = Style.width(CHILD.px).height(CHILD.px).margin(all = 7.px))
    val layout = LayoutFunctionN { ms, c ->
      val before = LithoStats.customLayoutChildLayoutCount.get()
      val p = ms[0].measure(SizeConstraints(0, 300, 0, 300))
      // A later run of the function is answered from this pass's memo and lays nothing out.
      val layouts = LithoStats.customLayoutChildLayoutCount.get() - before
      check(layouts <= 1L) { "laid out $layouts times" }
      layout(c.maxWidth, p.height) { p.place(0, 0) }
    }

    val result = renderAndFindContainer(Layout(listOf(child), layout = layout))

    assertThat(result.getXForChildAtIndex(0)).isEqualTo(7)
    assertThat(result.getChildAt(0).width).isEqualTo(CHILD)
  }

  // ─────────────────────────────────────────────── against Yoga

  /**
   * A child laid out by a container lands exactly where Yoga puts the same child in a Column that
   * offers it the same room, content included, in both directions.
   *
   * The container asks with the Column's width as an upper bound, as a Column that does not stretch
   * its children offers it, or as an exact width, as one that stretches them does. The Column rows
   * only use props an exact width does not override. Each child is a small row, so its content's
   * own layout is compared too, not just the child's box.
   */
  @Test
  fun `a child lays out as Yoga lays it out in a Column`() {
    val mismatches = ArrayList<String>()
    for ((name, style) in COLUMN_PARITY) {
      for (stretch in listOf(false, true)) {
        if (stretch && name in UNSTRETCHED_ONLY) continue
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val box = subtreeBounds(inContainer(boxChild(style), stretch), direction)
          val yoga = subtreeBounds(inColumn(boxChild(style), stretch), direction)
          if (box != yoga) {
            mismatches.add("[$name] stretch=$stretch $direction\n  box : $box\n  yoga: $yoga")
          }
        }
      }
    }
    for ((name, child) in parityLeaves()) {
      for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
        val box = subtreeBounds(inContainer(child(), stretch = false), direction)
        val yoga = subtreeBounds(inColumn(child(), stretch = false), direction)
        if (box != yoga) mismatches.add("[$name] $direction\n  box : $box\n  yoga: $yoga")
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * A leaf with start padding and a border, in both directions, has the size and the physical
   * padding and border edges under a container that it has in a Column: start padding lands on the
   * right in RTL, and the border, declared on physical edges, stays where it was declared.
   */
  @Test
  fun `a leaf's start padding and border resolve as in a Column, in both directions`() {
    val leaf = {
      Text.create(lithoTestRule.context)
          .text("custom layout parity")
          .kotlinStyle(
              Style.padding(start = 7.px, top = 2.px)
                  .border(
                      Border.create(lithoTestRule.context)
                          .widthPx(YogaEdge.LEFT, 3)
                          .widthPx(YogaEdge.RIGHT, 1)
                          .color(YogaEdge.ALL, Color.BLACK)
                          .build(),
                  ),
          )
          .build()
    }
    fun edgesOf(subject: Component, direction: YogaDirection): List<Int> {
      val testView =
          lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
            Column(alignItems = YogaAlign.FLEX_START, style = Style.layoutDirection(direction)) {
              child(subject)
            }
          }
      val root =
          checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
              as LithoLayoutResult
      var result = root.getChildAt(0)
      while (result.childCount > 0) result = result.getChildAt(0)
      return listOf(
          result.width,
          result.height,
          result.paddingLeft,
          result.paddingTop,
          result.paddingRight,
          result.paddingBottom,
          result.borderLeft,
          result.borderRight,
      )
    }

    for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
      val underContainer = edgesOf(inContainer(leaf(), stretch = false), direction)
      val inColumn = edgesOf(inColumn(leaf(), stretch = false), direction)

      assertThat(underContainer).describedAs("%s", direction).isEqualTo(inColumn)
      val startPadding =
          if (direction == YogaDirection.RTL) underContainer[4] else underContainer[2]
      assertThat(startPadding).describedAs("start padding, %s", direction).isEqualTo(7)
      assertThat(underContainer[6] to underContainer[7])
          .describedAs("border, %s", direction)
          .isEqualTo(3 to 1)
    }
  }

  /**
   * Where a container child deliberately differs from the same child in a Column. Each difference
   * is one of the child box's rules; a change here is a change to the contract, not a fix.
   */
  @Test
  fun `a child differs from a Column only where its box rules say so`() {
    fun boxWidth(style: Style, stretch: Boolean) =
        subtreeBounds(inContainer(boxChild(style), stretch), YogaDirection.LTR)[0].width()
    fun columnWidth(style: Style, stretch: Boolean) =
        subtreeBounds(inColumn(boxChild(style), stretch), YogaDirection.LTR)[0].width()

    fun boxLeft(style: Style, stretch: Boolean) =
        subtreeBounds(inContainer(boxChild(style), stretch), YogaDirection.LTR)[0].left
    fun columnLeft(style: Style, stretch: Boolean) =
        subtreeBounds(inColumn(boxChild(style), stretch), YogaDirection.LTR)[0].left

    // A declared width, an own minimum and an own maximum win over the constraints, as in a
    // Column. Outside the constraints the child then starts at the start edge, as in a Column.
    assertThat(boxWidth(Style.width(80.px), stretch = true)).isEqualTo(80)
    assertThat(columnWidth(Style.width(80.px), stretch = true)).isEqualTo(80)
    assertThat(boxLeft(Style.width(80.px), stretch = true))
        .describedAs("80 at the start of the exact 300")
        .isEqualTo(columnLeft(Style.width(80.px), stretch = true))
    assertThat(boxWidth(Style.minWidth(400.px), stretch = false)).isEqualTo(400)
    assertThat(columnWidth(Style.minWidth(400.px), stretch = false)).isEqualTo(400)
    assertThat(boxLeft(Style.minWidth(400.px), stretch = false))
        .describedAs("400 from the start of the 300 it was allowed: 100 past the end")
        .isEqualTo(columnLeft(Style.minWidth(400.px), stretch = false))
    assertThat(boxWidth(Style.maxWidth(80.px), stretch = true)).isEqualTo(80)
    assertThat(columnWidth(Style.maxWidth(80.px), stretch = true)).isEqualTo(80)

    // An own min beats an own max, as in CSS and here in a Column.
    assertThat(boxWidth(Style.minWidth(90.px).maxWidth(50.px), stretch = false)).isEqualTo(90)
    assertThat(columnWidth(Style.minWidth(90.px).maxWidth(50.px), stretch = false)).isEqualTo(90)

    // An own min under an exact room is stretched past, as in a Column.
    assertThat(boxWidth(Style.minWidth(200.px), stretch = true)).isEqualTo(PARITY_WIDTH)
    assertThat(columnWidth(Style.minWidth(200.px), stretch = true)).isEqualTo(PARITY_WIDTH)

    // A percentage width in an exact room keeps the percentage, as in a Column.
    assertThat(boxWidth(Style.widthPercent(50f), stretch = true)).isEqualTo(PARITY_WIDTH / 2)
    assertThat(columnWidth(Style.widthPercent(50f), stretch = true)).isEqualTo(PARITY_WIDTH / 2)

    // An aspect ratio with the other axis stretched: exact constraints win over the ratio, as
    // `align-self: stretch` wins in CSS. A Yoga child derives 80 from its height instead.
    assertThat(boxWidth(Style.height(40.px).aspectRatio(2f), stretch = true))
        .isEqualTo(PARITY_WIDTH)
    assertThat(columnWidth(Style.height(40.px).aspectRatio(2f), stretch = true)).isEqualTo(80)

    // An aspect ratio with neither axis fixed has no effect, so the child keeps its content's box,
    // as in CSS block layout and a CSS row. A Yoga `Column` child derives the width from the
    // content's height.
    assertThat(boxWidth(Style.aspectRatio(2f), stretch = false)).isEqualTo(ROW_CONTENT_WIDTH)
    assertThat(columnWidth(Style.aspectRatio(2f), stretch = false))
        .isEqualTo(ROW_CONTENT_HEIGHT * 2)

    // A percentage of an axis the constraints leave unbounded counts as auto, as in CSS. A Column
    // takes it of the height it has available.
    val percentHeight =
        subtreeBounds(inContainer(boxChild(Style.heightPercent(50f)), false), YogaDirection.LTR)[0]
    assertThat(percentHeight.height()).isEqualTo(ROW_CONTENT_HEIGHT)
  }

  /**
   * A child's own min raises the room's minimum and never lowers it, and a percentage size is of
   * the room, within its own min and max: under an exact room a child gets the box a stretched
   * `Column` or `Row` child gets, and under an at-most room the one a start-aligned child gets, in
   * both directions. Each container measures its only child under the room and wraps it.
   */
  @Test
  fun `an own min and a percentage size give a child a Column or Row child's box`() {
    val styles: List<Pair<String, Style>> = listOf(
        "min width 120" to Style.minWidth(120.px),
        "min width 400" to Style.minWidth(400.px),
        "min height 60" to Style.minHeight(60.px),
        "min height 150" to Style.minHeight(150.px),
        "width 50%" to Style.widthPercent(50f),
        "width 50%, max width 100" to Style.widthPercent(50f).maxWidth(100.px),
        "width 150%" to Style.widthPercent(150f),
        "height 50%" to Style.heightPercent(50f),
        "height 50%, min height 70" to Style.heightPercent(50f).minHeight(70.px),
        "width -10" to Style.width((-10f).px),
        "height -10" to Style.height((-10f).px),
        "width -50%" to Style.widthPercent(-50f),
        "width -0.5%" to Style.widthPercent(-0.5f),
        "height -50%" to Style.heightPercent(-50f),
        "height -0.5%" to Style.heightPercent(-0.5f),
    )
    // A percentage on the axis a room leaves unbounded has no base under a `Layout`, where a Yoga
    // parent takes it of the space its own parent gives: the next test covers that axis.
    // The constraints a Column-like or Row-like function passes, and the Yoga parent they stand
    // for.
    val rooms: List<Triple<String, SizeConstraints, (Component) -> Component>> = listOf(
        Triple(
            "Column, exact",
            SizeConstraints(PARITY_WIDTH, PARITY_WIDTH, 0, SizeConstraints.Infinity),
            { child -> inColumn(child, stretch = true) },
        ),
        Triple(
            "Column, at most",
            SizeConstraints(maxWidth = PARITY_WIDTH),
            { child -> inColumn(child, stretch = false) },
        ),
        Triple(
            "Row, exact",
            SizeConstraints(0, SizeConstraints.Infinity, CHILD, CHILD),
            { child ->
              Row(alignItems = YogaAlign.STRETCH, style = Style.height(CHILD.px)) { child(child) }
            },
        ),
        Triple(
            "Row, at most",
            SizeConstraints(0, SizeConstraints.Infinity, 0, CHILD),
            { child ->
              Row(alignItems = YogaAlign.FLEX_START, style = Style.height(CHILD.px)) {
                child(child)
              }
            },
        ),
    )
    val mismatches = ArrayList<String>()
    for ((roomName, room, yogaParent) in rooms) {
      for ((name, style) in styles) {
        val unboundedAxis = if (room.hasBoundedHeight) "width" else "height"
        if (name.contains(unboundedAxis) && "%" in name) continue
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          val container = Layout(
              listOf(boxChild(style)),
              layout =
                  LayoutFunctionN { measurables, _ ->
                    val placeable = measurables[0].measure(room)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                  },
          )
          val box = subtreeBounds(container, direction)
          val yoga = subtreeBounds(yogaParent(boxChild(style)), direction)
          if (box != yoga)
              mismatches.add("[$name] $roomName $direction\n  box : $box\n  yoga: $yoga")
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /** With no bound, an own min still sets the least size and a percentage has no base. */
  @Test
  fun `with no bound, an own min is the least size and a percentage has no effect`() {
    val cases = listOf(
        "min width 120" to Style.minWidth(120.px) to (120 to ROW_CONTENT_HEIGHT),
        "min height 60" to Style.minHeight(60.px) to (ROW_CONTENT_WIDTH to 60),
        "width 50%" to Style.widthPercent(50f) to (ROW_CONTENT_WIDTH to ROW_CONTENT_HEIGHT),
        "height 50%" to Style.heightPercent(50f) to (ROW_CONTENT_WIDTH to ROW_CONTENT_HEIGHT),
    )
    for ((named, size) in cases) {
      val (name, style) = named
      for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
        val room = SizeConstraints()
        val container = Layout(
            listOf(boxChild(style)),
            layout =
                LayoutFunctionN { measurables, _ ->
                  val placeable = measurables[0].measure(room)
                  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        )
        val bounds = subtreeBounds(container, direction)[0]
        assertThat(bounds.width() to bounds.height())
            .describedAs("%s, %s", name, direction)
            .isEqualTo(size)
      }
    }
  }

  /**
   * A negative size or own max has no effect, as in CSS: the child gets the box it gets without it,
   * exactly, at most and with no bound, in both directions. A Yoga `Column` child agrees on the
   * size, and a `Row` child on the max; a stretched `Column` child's negative max width makes it 0.
   */
  @Test
  fun `a negative size or own max has no effect, as in CSS`() {
    val styles: List<Pair<String, Style>> = listOf(
        "width -10" to Style.width((-10f).px),
        "width -50%" to Style.widthPercent(-50f),
        "width -0.5%" to Style.widthPercent(-0.5f),
        "height -10" to Style.height((-10f).px),
        "height -50%" to Style.heightPercent(-50f),
        "height -0.5%" to Style.heightPercent(-0.5f),
        "max width -10" to Style.maxWidth((-10f).px),
        "max width -50%" to Style.maxWidthPercent(-50f),
        "max height -10" to Style.maxHeight((-10f).px),
        "max height -0.5%" to Style.maxHeightPercent(-0.5f),
    )
    val rooms = listOf(
        "exact" to SizeConstraints(PARITY_WIDTH, PARITY_WIDTH, CHILD, CHILD),
        "at most" to SizeConstraints(maxWidth = PARITY_WIDTH, maxHeight = CHILD),
        "unbounded" to SizeConstraints(),
    )
    val mismatches = ArrayList<String>()
    for ((roomName, room) in rooms) {
      for ((name, style) in styles) {
        for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
          fun boxWith(childStyle: Style): List<Rect> = subtreeBounds(
              Layout(
                  listOf(boxChild(childStyle)),
                  layout =
                      LayoutFunctionN { measurables, _ ->
                        val placeable = measurables[0].measure(room)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                      },
              ),
              direction,
          )
          val with = boxWith(style)
          val without = boxWith(Style)
          if (with != without) mismatches += "$roomName, $name, $direction: $with, without $without"
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * Under a Yoga parent, a `Layout` answers the size its function declares past an at-most room, as
   * a Yoga `Column` that wraps its content grows past it, within its own max; an exact room stays
   * exact. Across and down, in both directions.
   */
  @Test
  fun `under a Yoga parent it answers past an at-most room, as a wrapping Column does`() {
    val wide = Style.minWidth(400.px).height(10.px)
    val tall = Style.minHeight(150.px).width(10.px)
    val acrossAtMost: (Component) -> Component = { content ->
      Column(alignItems = YogaAlign.FLEX_START, style = Style.width(PARITY_WIDTH.px)) {
        child(content)
      }
    }
    val acrossExact: (Component) -> Component = { content ->
      Column(alignItems = YogaAlign.STRETCH, style = Style.width(PARITY_WIDTH.px)) {
        child(content)
      }
    }
    val downAtMost: (Component) -> Component = { content ->
      Row(alignItems = YogaAlign.FLEX_START, style = Style.height(CHILD.px)) { child(content) }
    }
    val cases = listOf(
        Triple("at most 300 across", Style to wide, acrossAtMost),
        Triple("at most 300 across, own max 350", Style.maxWidth(350.px) to wide, acrossAtMost),
        Triple("exactly 300 across", Style to wide, acrossExact),
        Triple("at most 100 down", Style to tall, downAtMost),
    )
    val mismatches = ArrayList<String>()
    for ((name, styles, parent) in cases) {
      val (ownStyle, leafStyle) = styles
      for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
        val layout =
            subtreeBounds(
                parent(
                    Layout(
                        listOf(SimpleMountSpecTester(style = leafStyle)),
                        style = ownStyle,
                        layout = DECLARES_MEASURED,
                    ),
                ),
                direction,
            )[0]
        val column =
            subtreeBounds(
                parent(
                    Column(alignItems = YogaAlign.FLEX_START, style = ownStyle) {
                      child(SimpleMountSpecTester(style = leafStyle))
                    },
                ),
                direction,
            )[0]
        if (layout != column) mismatches += "$name, $direction: Layout $layout, Column $column"
      }
    }
    assertThat(mismatches).isEmpty()
  }

  /**
   * In a `Layout`, a `Layout` child's content stays within the constraints it's measured under, as
   * in Compose: a function declaring 400 under at most 300 gives a placeable 300 wide,
   * measured 300.
   */
  @Test
  fun `in a Layout, a Layout child's content stays within its constraints`() {
    val seen = java.util.concurrent.atomic.AtomicReference(IntSize(0, 0))
    subtreeBounds(
        Layout(
            listOf(
                Layout(
                    listOf(SimpleMountSpecTester(style = Style.minWidth(400.px).height(10.px))),
                    layout = DECLARES_MEASURED,
                ),
            ),
            layout =
                LayoutFunctionN { measurables, _ ->
                  val placeable =
                      measurables[0].measure(
                          SizeConstraints(0, PARITY_WIDTH, 0, SizeConstraints.Infinity),
                      )
                  seen.set(IntSize(placeable.width, placeable.measuredWidth))
                  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        ),
        YogaDirection.LTR,
    )
    assertThat(seen.get()).describedAs("its width and measured width").isEqualTo(IntSize(300, 300))
  }

  /**
   * A Yoga subtree whose content is wider than its room is laid out within the room: a `Row` of 48
   * and 50 measured at most 80 wide is 80, where a `Column`'s child would keep its content's 98.
   */
  @Test
  fun `a Yoga subtree wider than its room is laid out within the room`() {
    for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
      val bounds = subtreeBounds(
          Layout(
              listOf(
                  Row {
                    child(
                        SimpleMountSpecTester(
                            style = Style.width(48.px).height(20.px).flex(shrink = 0f),
                        ),
                    )
                    child(
                        SimpleMountSpecTester(
                            style = Style.width(50.px).height(10.px).flex(shrink = 0f),
                        ),
                    )
                  },
              ),
              layout =
                  LayoutFunctionN { measurables, _ ->
                    val placeable = measurables[0].measure(SizeConstraints(maxWidth = 80))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                  },
          ),
          direction,
      )
      assertThat(bounds[0].width()).describedAs("%s: the Row's width", direction).isEqualTo(80)
    }
  }

  /**
   * A percentage height on an axis its room leaves unbounded has no base, so it keeps its content's
   * height, as CSS gives a percentage of an auto height, even where an ancestor is 500 high and
   * Yoga would take the percentage of it.
   */
  @Test
  fun `a percentage height on an axis its room leaves unbounded keeps its content's height`() {
    for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
      val testView =
          lithoTestRule.render(
              widthSpec = SizeSpec.makeSizeSpec(PARITY_WIDTH, SizeSpec.EXACTLY),
              heightSpec = SizeSpec.makeSizeSpec(500, SizeSpec.EXACTLY),
          ) {
            Column(alignItems = YogaAlign.FLEX_START, style = Style.layoutDirection(direction)) {
              child(
                  Layout(
                      listOf(boxChild(Style.heightPercent(50f))),
                      layout =
                          LayoutFunctionN { measurables, constraints ->
                            val placeable =
                                measurables[0].measure(
                                    SizeConstraints(
                                        0,
                                        constraints.maxWidth,
                                        0,
                                        SizeConstraints.Infinity,
                                    ),
                                )
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                          },
                  ),
              )
            }
          }
      val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
      val container = checkNotNull(findContainer(root as LithoLayoutResult))
      assertThat(container.getChildAt(0).height)
          .describedAs("%s: its content's height, not 50%% of an ancestor's", direction)
          .isEqualTo(ROW_CONTENT_HEIGHT)
    }
  }

  /** A small row, so a child's own content layout is compared, not only its box. */
  private fun boxChild(style: Style): Component =
      Row(style = style) {
        child(SimpleMountSpecTester(style = Style.width(10.px).height(10.px)))
        child(SimpleMountSpecTester(style = Style.width(20.px).height(ROW_CONTENT_HEIGHT.px)))
      }

  private fun parityLeaves(): List<Pair<String, () -> Component>> = listOf(
      "text with padding and margins" to
          {
            Text.create(lithoTestRule.context)
                .text("custom layout parity")
                .kotlinStyle(
                    Style.padding(start = 7.px, end = 11.px).margin(start = 5.px, top = 3.px),
                )
                .build()
          },
      "text with percent padding and a max width" to
          {
            Text.create(lithoTestRule.context)
                .text("custom layout parity")
                .kotlinStyle(Style.paddingPercent(start = 5f).maxWidth(60.px))
                .build()
          },
      "nested container with padding and margins" to
          {
            Layout(
                listOf(SimpleMountSpecTester(style = Style.width(40.px).height(12.px))),
                style = Style.padding(start = 6.px, top = 2.px).margin(end = 9.px),
                layout =
                    LayoutFunctionN { ms, c ->
                      val p = ms[0].measure(SizeConstraints(maxWidth = c.maxWidth))
                      layout(p.width, p.height) { p.place(0, 0) }
                    },
            )
          },
  )

  /**
   * A container sized to its only child and placing it at its origin, so the child lands where the
   * Column places it in either direction. It asks with the Column's width, as an upper bound or,
   * for a stretching Column, exactly.
   */
  private fun inContainer(child: Component, stretch: Boolean): Component = Layout(
      listOf(child),
      layout =
          LayoutFunctionN { measurables, _ ->
            val constraints =
                if (stretch)
                    SizeConstraints(PARITY_WIDTH, PARITY_WIDTH, 0, SizeConstraints.Infinity)
                else SizeConstraints(maxWidth = PARITY_WIDTH)
            val placeable = measurables[0].measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
          },
  )

  private fun inColumn(child: Component, stretch: Boolean): Component = Column(
      alignItems = if (stretch) YogaAlign.STRETCH else YogaAlign.FLEX_START,
      style = Style.width(PARITY_WIDTH.px),
  ) {
    child(child)
  }

  /** Absolute bounds of the child and every node under it, in tree order. */
  private fun subtreeBounds(subject: Component, direction: YogaDirection): List<Rect> {
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          Column(alignItems = YogaAlign.FLEX_START, style = Style.layoutDirection(direction)) {
            child(subject)
          }
        }
    val root =
        checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
            as LithoLayoutResult
    // The container is sized to its child and the Column is not, so the walk starts at the child,
    // in absolute coordinates.
    val holder = root.getChildAt(0)
    val originX = root.getXForChildAtIndex(0)
    val originY = root.getYForChildAtIndex(0)
    val bounds = ArrayList<Rect>()
    fun walk(result: LithoLayoutResult, x: Int, y: Int) {
      bounds.add(Rect(x, y, x + result.width, y + result.height))
      for (i in 0 until result.childCount) {
        walk(
            result.getChildAt(i),
            x + result.getXForChildAtIndex(i),
            y + result.getYForChildAtIndex(i),
        )
      }
    }
    walk(
        holder.getChildAt(0),
        originX + holder.getXForChildAtIndex(0),
        originY + holder.getYForChildAtIndex(0),
    )
    return bounds
  }

  @Test
  fun `a child whose own edges cannot fit is kept within its room and starts at its start edge`() {
    val child = SimpleMountSpecTester(style = Style.padding(all = 8.px))

    val result = renderAndFindContainer(
        Layout(listOf(child), layout = measuringUnder { SizeConstraints.exact(10, 10) }),
    )

    assertThat(result.getChildAt(0).width).describedAs("its padding floors it at 16").isEqualTo(16)
    assertThat(result.getXForChildAtIndex(0))
        .describedAs("16 from the start of the 10 it occupies: 6 px past the end")
        .isEqualTo(0)
  }

  /**
   * A child that takes more than it was allowed occupies what it was allowed, and is drawn from its
   * start edge, overflowing the end, as in a Column. Its real size is kept as its measured size.
   */
  @Test
  fun `a child larger than its room occupies the room and is drawn from its start edge`() {
    val seen = AtomicReference<Placeable>()
    val result = renderAndFindContainer(
        Layout(
            listOf(SimpleMountSpecTester(style = Style.width(150.px).height(CHILD.px))),
            layout =
                LayoutFunctionN { ms, c ->
                  val p = ms[0].measure(SizeConstraints(maxWidth = 100, maxHeight = c.maxHeight))
                  seen.set(p)
                  layout(p.width, p.height) { p.place(0, 0) }
                },
        ),
    )

    assertThat(seen.get().width).describedAs("the space it occupies").isEqualTo(100)
    assertThat(seen.get().measuredWidth).describedAs("the size it took").isEqualTo(150)
    assertThat(result.getChildAt(0).width).isEqualTo(150)
    assertThat(result.getXForChildAtIndex(0)).describedAs("50 past the end").isEqualTo(0)
  }

  /** A child smaller than an exact room starts at its start edge too, as in a Column. */
  @Test
  fun `a child smaller than an exact room starts at its start edge`() {
    val result = renderAndFindContainer(
        Layout(
            listOf(SimpleMountSpecTester(style = Style.width(81.px).height(CHILD.px))),
            layout = measuringUnder { c -> SizeConstraints(100, 100, 0, c.maxHeight) },
        ),
    )

    assertThat(result.getChildAt(0).width).isEqualTo(81)
    assertThat(result.getXForChildAtIndex(0)).describedAs("19 short of the end").isEqualTo(0)
  }

  /**
   * A child that doesn't fit the constraints it was measured with starts at the start edge of the
   * space it occupies, as Yoga starts it, and falls short of or overflows the end: in a `Layout`'s
   * child and in a root `Layout`, in both directions.
   */
  @Test
  fun `a child that doesn't fit its constraints starts at its start edge`() {
    val exactRoom = LayoutFunctionN { measurables, constraints ->
      val placeable =
          measurables[0].measure(
              SizeConstraints(PARITY_WIDTH, PARITY_WIDTH, 0, SizeConstraints.Infinity),
          )
      layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
    }
    for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
      for (asRoot in listOf(false, true)) {
        for (width in listOf(120, 400)) {
          val container = Layout(
              listOf(SimpleMountSpecTester(style = Style.width(width.px).height(CHILD.px))),
              style = Style.layoutDirection(direction),
              layout = exactRoom,
          )
          val result = renderStartEdgeSubject(container, asRoot)
          val expected = if (direction == YogaDirection.RTL) PARITY_WIDTH - width else 0
          assertThat(result.getXForChildAtIndex(0))
              .describedAs("%s in an exact %s, %s, root=%s", width, PARITY_WIDTH, direction, asRoot)
              .isEqualTo(expected)
        }
      }
    }
  }

  /**
   * The same for children a `Layout` stacks as a `Column` does inside its own max width, which is
   * narrower than both: they start at its start edge and overflow its end, as in a `Column`.
   */
  @Test
  fun `children wider than their container's max width start at its start edge`() {
    val stack = LayoutFunctionN { measurables, constraints ->
      val placeables = measurables.map {
        it.measure(SizeConstraints(0, constraints.maxWidth, 0, SizeConstraints.Infinity))
      }
      layout(
          placeables.maxOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth),
          placeables.sumOf { it.height },
      ) {
        var y = 0
        for (placeable in placeables) {
          placeable.place(0, y)
          y += placeable.height
        }
      }
    }
    for (direction in listOf(YogaDirection.LTR, YogaDirection.RTL)) {
      for (asRoot in listOf(false, true)) {
        val container = Layout(
            listOf(
                SimpleMountSpecTester(style = Style.width(30.px).height(20.px)),
                SimpleMountSpecTester(style = Style.width(50.px).height(10.px)),
            ),
            style = Style.maxWidth(20.px).layoutDirection(direction),
            layout = stack,
        )
        val result = renderStartEdgeSubject(container, asRoot)
        val starts = listOf(result.getXForChildAtIndex(0), result.getXForChildAtIndex(1))
        assertThat(result.width).isEqualTo(20)
        assertThat(starts)
            .describedAs("%s, root=%s", direction, asRoot)
            .isEqualTo(
                if (direction == YogaDirection.RTL) listOf(20 - 30, 20 - 50) else listOf(0, 0),
            )
      }
    }
  }

  /** [container] as the root of a 300-wide tree, or as the child of a 300-wide `Column`. */
  private fun renderStartEdgeSubject(container: Layout, asRoot: Boolean): LithoLayoutResult {
    val testView =
        lithoTestRule.render(
            widthSpec = SizeSpec.makeSizeSpec(PARITY_WIDTH, SizeSpec.EXACTLY),
            heightSpec = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED),
        ) {
          if (asRoot) container
          else
              Column(alignItems = YogaAlign.FLEX_START, style = Style.width(PARITY_WIDTH.px)) {
                child(container)
              }
        }
    val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
    return checkNotNull(findContainer(root as LithoLayoutResult)) { "container not found" }
  }

  private fun renderAndFindContainer(component: Component): LithoLayoutResult {
    val testView =
        lithoTestRule.render(widthPx = VIEWPORT, heightPx = VIEWPORT) {
          if (component is Layout) Column { child(component) } else component
        }
    val root = checkNotNull(testView.componentTree.mainThreadLayoutState?.rootLayoutResult)
    return checkNotNull(findContainer(root as LithoLayoutResult)) { "container not found" }
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
}
