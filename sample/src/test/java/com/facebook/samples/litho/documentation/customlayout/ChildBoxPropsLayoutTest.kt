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

package com.facebook.samples.litho.documentation.customlayout

import android.graphics.Color
import android.graphics.Rect
import com.facebook.litho.Border
import com.facebook.litho.Component
import com.facebook.litho.LithoLayoutResult
import com.facebook.litho.SizeSpec
import com.facebook.litho.Style
import com.facebook.litho.TestComponentScope
import com.facebook.litho.core.height
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.margin
import com.facebook.litho.core.marginPercent
import com.facebook.litho.core.maxHeight
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.padding
import com.facebook.litho.core.paddingPercent
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.alignSelf
import com.facebook.litho.flexbox.aspectRatio
import com.facebook.litho.flexbox.border
import com.facebook.litho.flexbox.flex
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SolidColor
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaEdge
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A [ChildBoxPropsLayout] in a 300 px wide parent gives each child the box a `Column` gives it in
 * the same parent, `alignItems = FLEX_START` or, with `stretch`, `STRETCH`, except in the cases
 * [ChildBoxPropsLayout]'s KDoc lists as differing on purpose. Bounds are compared for the whole
 * subtree, relative to the container, with the container's own size first.
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChildBoxPropsLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private fun border(): Border =
      Border.create(lithoTestRule.context)
          .widthPx(YogaEdge.LEFT, 4)
          .widthPx(YogaEdge.RIGHT, 2)
          .widthPx(YogaEdge.TOP, 3)
          .widthPx(YogaEdge.BOTTOM, 1)
          .color(YogaEdge.ALL, Color.BLACK)
          .build()

  /** Child styles that get the same box in a `Layout` as in a `Column`. */
  private fun parityStyles(): List<Pair<String, Style>> = listOf(
      "no props" to Style,
      "width in pixels" to Style.width(80.px),
      "width as a percentage" to Style.widthPercent(50f),
      "height in pixels" to Style.height(40.px),
      "min width" to Style.minWidth(200.px),
      "min width past the parent" to Style.minWidth(400.px),
      "max width" to Style.maxWidth(60.px),
      "min and max height" to Style.minHeight(40.px).maxHeight(60.px),
      "max height under the content" to Style.maxHeight(10.px),
      "min width above max width" to Style.minWidth(80.px).maxWidth(50.px),
      "aspect ratio with a width" to Style.width(80.px).aspectRatio(2f),
      "margins by logical edge" to
          Style.margin(start = 7.px, top = 5.px, end = 11.px, bottom = 3.px),
      "margins as a percentage" to Style.marginPercent(all = 5f),
      "negative start margin" to Style.margin(start = (-9f).px),
      "padding by logical edge" to
          Style.padding(start = 7.px, top = 5.px, end = 11.px, bottom = 3.px),
      "padding as a percentage" to Style.paddingPercent(all = 5f),
      "border on every edge" to Style.border(border()),
      "width, margins and padding" to
          Style.width(120.px).margin(start = 10.px, end = 4.px).padding(start = 6.px),
  )

  /**
   * Under an at-most width, these differ on purpose (below): a percentage is of the maximum, and
   * the layout doesn't grow past it.
   */
  private val atMostDifferences = setOf("width as a percentage", "min width past the parent")

  /** Matches a `Column` only when it doesn't stretch; stretched, it differs on purpose (below). */
  private fun unstretchedParityStyles(): List<Pair<String, Style>> =
      listOf("aspect ratio with a height" to Style.height(40.px).aspectRatio(2f))

  /** A text leaf, which measures its content. */
  private fun leaf(style: Style, text: String = "box props"): Component = Named(
      "leaf",
      with(TestComponentScope(lithoTestRule.context)) {
        Text(text = text, textSize = 20.px, style = style)
      },
  )

  /** A `Row` holding one box, so its padding and border move the box inside it. */
  private fun container(style: Style): Component =
      Named("row", Row(style = style) { child(box("inner", 10, 10)) })

  @Test
  fun `each child style gives a child the box it gets in a Column`() {
    val mismatches = ArrayList<String>()
    for (parent in listOf(Parent.EXACT, Parent.AT_MOST)) {
      for (stretch in listOf(false, true)) {
        // Stretched under an at-most width, the Layout differs on purpose (below).
        if (stretch && parent == Parent.AT_MOST) continue
        val styles = if (stretch) parityStyles() else parityStyles() + unstretchedParityStyles()
        for ((name, style) in styles) {
          if (parent == Parent.AT_MOST && name in atMostDifferences) continue
          for ((kind, child) in childKinds()) {
            for (rtl in listOf(false, true)) {
              val layout = subtreeBounds(inLayout(stacked(child(style)), stretch, parent), rtl)
              val column = subtreeBounds(inColumn(stacked(child(style)), stretch, parent), rtl)
              if (layout != column) {
                mismatches.add(
                    "[$name] $kind $parent stretch=$stretch rtl=$rtl\n" +
                        "  Layout: $layout\n  Column: $column",
                )
              }
            }
          }
        }
      }
    }
    assertThat(mismatches).isEmpty()
  }

  @Test
  fun `flexbox props on a child have no effect`() {
    val flexed = Style.flex(grow = 1f, shrink = 1f, basis = 50.px).alignSelf(YogaAlign.CENTER)

    for (rtl in listOf(false, true)) {
      assertThat(subtreeBounds(inLayout(stacked(leaf(flexed)), stretch = false), rtl))
          .describedAs("rtl=%s", rtl)
          .isEqualTo(subtreeBounds(inLayout(stacked(leaf(Style)), stretch = false), rtl))
    }
  }

  // The cases where a Layout's child differs from a Column's child on purpose.

  @Test
  fun `a child Column whose min width is above its max width gets the min, whereas a Column in a Column gets the max`() {
    fun minAboveMax() = Named(
        "column",
        Column(style = Style.minWidth(80.px).maxWidth(50.px)) { child(box("item", 10, 10)) },
    )

    assertThat(childWidth(inLayout(listOf(minAboveMax()), stretch = false))).isEqualTo(80)
    assertThat(childWidth(inColumn(listOf(minAboveMax()), stretch = false))).isEqualTo(50)
  }

  @Test
  fun `a percentage height has no base and keeps the content's height, whereas a Column derives a height from it`() {
    val contentHeight = childHeight(inLayout(listOf(leaf(Style)), stretch = false))
    val percent = Style.heightPercent(50f)

    assertThat(childHeight(inLayout(listOf(leaf(percent)), stretch = false, parentHeight = 500)))
        .isEqualTo(contentHeight)
    assertThat(childHeight(inColumn(listOf(leaf(percent)), stretch = false, parentHeight = 500)))
        .isEqualTo(COLUMN_PERCENT_HEIGHT)
  }

  @Test
  fun `a child Row wider than the maximum width is laid out within it, whereas a Column keeps the Row as wide as its content`() {
    fun wideRow() = Named(
        "row",
        Row {
          child(rigidBox("a", 48, 20))
          child(rigidBox("b", 50, 10))
        },
    )

    assertThat(childWidth(inLayout(listOf(wideRow()), stretch = false, width = 80))).isEqualTo(80)
    assertThat(childWidth(inColumn(listOf(wideRow()), stretch = false, width = 80))).isEqualTo(98)
  }

  @Test
  fun `a negative max width has no effect, whereas a stretching Column gives the child no width`() {
    val negativeMax = Style.maxWidth((-10f).px)

    assertThat(childWidth(inLayout(listOf(leaf(negativeMax)), stretch = true))).isEqualTo(300)
    assertThat(childWidth(inColumn(listOf(leaf(negativeMax)), stretch = true))).isEqualTo(0)
  }

  @Test
  fun `an aspect ratio on a stretched child with a height has no effect, whereas a Column derives the width`() {
    val style = Style.height(40.px).aspectRatio(2f)
    for ((kind, child) in ratioChildKinds()) {
      val layout = childBounds(inLayout(listOf(child(style)), stretch = true))
      val column = childBounds(inColumn(listOf(child(style)), stretch = true))

      assertThat(layout.width() to layout.height())
          .describedAs("%s, stretched", kind)
          .isEqualTo(300 to 40)
      assertThat(column.width()).describedAs("%s, from the height", kind).isEqualTo(80)
    }
  }

  @Test
  fun `an aspect ratio with neither axis fixed has no effect, whereas a Column derives the width`() {
    val style = Style.aspectRatio(2f)
    for ((kind, child) in ratioChildKinds()) {
      val content = childBounds(inLayout(listOf(child(Style)), stretch = false))
      val layout = childBounds(inLayout(listOf(child(style)), stretch = false))
      val column = childBounds(inColumn(listOf(child(style)), stretch = false))

      assertThat(layout.width() to layout.height())
          .describedAs("%s, the content's box", kind)
          .isEqualTo(content.width() to content.height())
      assertThat(column.width())
          .describedAs("%s, from the content's height", kind)
          .isEqualTo(content.height() * 2)
    }
  }

  @Test
  fun `with stretch under an at-most width, each child fills the maximum, whereas a stretching Column is as wide as its widest child`() {
    fun leaves() = listOf(leaf(Style), leaf(Style, "a longer label"))
    val layout = subtreeBounds(inLayout(leaves(), stretch = true, Parent.AT_MOST), rtl = false)
    val column = subtreeBounds(inColumn(leaves(), stretch = true, Parent.AT_MOST), rtl = false)
    val widest =
        subtreeBounds(inColumn(leaves(), stretch = false, Parent.AT_MOST), rtl = false)
            .drop(1)
            .maxOf { bounds -> bounds.width() }

    assertThat(layout.drop(1).map { bounds -> bounds.width() }).containsExactly(300, 300)
    assertThat(column.drop(1).map { bounds -> bounds.width() }).containsExactly(widest, widest)
    assertThat(widest).isLessThan(300)
  }

  private fun childKinds(): List<Pair<String, (Style) -> Component>> =
      listOf("leaf" to { style -> leaf(style) }, "container" to { style -> container(style) })

  private fun ratioChildKinds(): List<Pair<String, (Style) -> Component>> =
      listOf("row" to { style -> rowWithContent(style) }, "leaf" to { style -> leaf(style) })

  @Test
  fun `under an at-most width, a percentage width is of the maximum, whereas a Column that wraps its content keeps the child's content width`() {
    val content = childWidth(inLayout(listOf(leaf(Style)), stretch = false, Parent.AT_MOST))
    val percent = Style.widthPercent(50f)

    assertThat(childWidth(inLayout(listOf(leaf(percent)), stretch = false, Parent.AT_MOST)))
        .isEqualTo(150)
    assertThat(childWidth(inColumn(listOf(leaf(percent)), stretch = false, Parent.AT_MOST)))
        .isEqualTo(content)
  }

  @Test
  fun `under an at-most width, a child wider than the maximum doesn't make the layout wider, whereas a Column grows to hold it`() {
    val wide = Style.minWidth(400.px)

    val layout = subtreeBounds(inLayout(listOf(leaf(wide)), stretch = false, Parent.AT_MOST), false)
    val column = subtreeBounds(inColumn(listOf(leaf(wide)), stretch = false, Parent.AT_MOST), false)

    assertThat(layout[1].width() to column[1].width())
        .describedAs("the child")
        .isEqualTo(400 to 400)
    assertThat(layout[0].width() to column[0].width())
        .describedAs("the container")
        .isEqualTo(300 to 400)
  }

  /**
   * A percentage margin is rounded to a whole pixel on its own, where Yoga rounds a `Column`'s
   * positions on the pixel grid, so a fractional one can land 1 px apart: a 2.5% margin of 300, 7.5
   * px, is 8 px here and 7 px in a `Column`.
   */
  @Test
  fun `a fractional percentage margin can round 1 px differently from a Column`() {
    val margin = Style.marginPercent(all = 2.5f)

    val layout = childBounds(inLayout(listOf(leaf(margin)), stretch = false))
    val column = childBounds(inColumn(listOf(leaf(margin)), stretch = false))

    assertThat(layout.left to layout.top).isEqualTo(8 to 8)
    assertThat(column.left to column.top).isEqualTo(7 to 7)
  }

  /** A `Row` with 30 x 25 px of content. */
  private fun rowWithContent(style: Style): Component = Named(
      "row",
      Row(style = style) {
        child(box("a", 10, 10))
        child(box("b", 20, 25))
      },
  )

  /** A box that a `Row` doesn't shrink. */
  private fun rigidBox(name: String, width: Int, height: Int): Component = Named(
      name,
      SolidColor(
          color = Color.BLACK,
          style = Style.width(width.px).height(height.px).flex(shrink = 0f),
      ),
  )

  /** [child] between a 7 px high box and a 5 px high box, so its place in the stack is compared. */
  private fun stacked(child: Component): List<Component> =
      listOf(box("above", 10, 7), child, box("below", 10, 5))

  /** How a test's 300 px wide parent holds the container: stretched to exactly 300, or at most. */
  private enum class Parent {
    EXACT,
    AT_MOST,
  }

  private fun inLayout(
      children: List<Component>,
      stretch: Boolean,
      parent: Parent = Parent.EXACT,
      width: Int = 300,
      parentHeight: Int? = null,
  ): Component = sized(parent, width, parentHeight) { ChildBoxPropsLayout(children, stretch) }

  private fun inColumn(
      children: List<Component>,
      stretch: Boolean,
      parent: Parent = Parent.EXACT,
      width: Int = 300,
      parentHeight: Int? = null,
  ): Component =
      sized(parent, width, parentHeight) {
        Column(alignItems = if (stretch) YogaAlign.STRETCH else YogaAlign.FLEX_START) {
          children.forEach { each -> child(each) }
        }
      }

  /** A parent [width] px wide, and [parentHeight] px high if set, holding [container]. */
  private fun sized(
      parent: Parent,
      width: Int,
      parentHeight: Int?,
      container: () -> Component,
  ): Component {
    var style = Style.width(width.px)
    if (parentHeight != null) style = style.height(parentHeight.px)
    return Column(
        alignItems = if (parent == Parent.EXACT) YogaAlign.STRETCH else YogaAlign.FLEX_START,
        style = style,
    ) {
      child(container())
    }
  }

  private fun childBounds(parent: Component): Rect = subtreeBounds(parent, rtl = false)[1]

  private fun childWidth(parent: Component): Int = childBounds(parent).width()

  private fun childHeight(parent: Component): Int = childBounds(parent).height()

  /**
   * The container [parent] holds, then every node under it, in tree order, relative to the
   * container.
   */
  private fun subtreeBounds(parent: Component, rtl: Boolean): List<Rect> {
    val testView =
        lithoTestRule.render(
            widthSpec = SizeSpec.makeSizeSpec(1000, SizeSpec.EXACTLY),
            heightSpec = SizeSpec.makeSizeSpec(1000, SizeSpec.EXACTLY),
        ) {
          Column(
              alignItems = YogaAlign.FLEX_START,
              style = Style.layoutDirection(if (rtl) YogaDirection.RTL else YogaDirection.LTR),
          ) {
            child(parent)
          }
        }
    val container = checkNotNull(testView.currentRootNode).getChildAt(0).getChildAt(0)
    val bounds = arrayListOf(Rect(0, 0, container.width, container.height))
    fun walk(result: LithoLayoutResult, parentLeft: Int, parentTop: Int) {
      for (i in 0 until result.childCount) {
        val child = result.getChildAt(i)
        val left = parentLeft + result.getXForChildAtIndex(i)
        val top = parentTop + result.getYForChildAtIndex(i)
        bounds.add(Rect(left, top, left + child.width, top + child.height))
        walk(child, left, top)
      }
    }
    walk(container, 0, 0)
    return bounds
  }

  private companion object {
    /** What a `Column` in a 500 px high parent gives a child with `heightPercent(50f)`. */
    const val COLUMN_PERCENT_HEIGHT: Int = 125
  }
}
