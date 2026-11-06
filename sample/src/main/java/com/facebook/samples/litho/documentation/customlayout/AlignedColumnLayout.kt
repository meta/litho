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

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Layout
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.margin
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.padding
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.LayoutProps
import com.facebook.litho.layout.layoutProps
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.rendercore.dp

// start_example
/** A child's place across an [AlignedColumnLayout]. */
@OptIn(ExperimentalLithoApi::class)
enum class ColumnAlignment : LayoutProps {
  /** Against the start edge: the left, or the right in a right-to-left layout. */
  START,
  /** Centred across the width. An odd leftover pixel goes to the end side. */
  CENTER,
  /** Against the end edge. */
  END,
}

/**
 * Sets this child's [ColumnAlignment] for the [AlignedColumnLayout] it's in. Other containers, such
 * as a flexbox `Column`, ignore it.
 */
@OptIn(ExperimentalLithoApi::class)
fun Style.columnAlignment(alignment: ColumnAlignment): Style = layoutProps(alignment)

/**
 * Stacks [children] from the top. Each child sets its own place across the width with
 * [columnAlignment], and is at the start edge if it sets none, where a Yoga `Column` would stretch
 * it. This layout is as wide as its widest child, up to the maximum width, or fills an exact width.
 * A child wider than that keeps its place and extends past the edge or edges opposite it, as with
 * `align-self` in a Yoga `Column`.
 */
@OptIn(ExperimentalLithoApi::class)
class AlignedColumnLayout(
    private val children: List<Component>,
    private val style: Style = Style,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Layout(children, style = style) { measurables, constraints ->
        // Each child keeps its own width: under an exact width, a minimum would stretch it and
        // leave nothing to align.
        val placeables = measurables.map { child ->
          child.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val alignments = measurables.map { child -> child.layoutProps(ColumnAlignment.START) }
        val widest = placeables.maxOfOrNull { placeable -> placeable.width } ?: 0
        // An exact width comes as a minimum, which this layout fills.
        val layoutWidth = maxOf(constraints.minWidth, widest)
        layout(layoutWidth, placeables.sumOf { placeable -> placeable.height }) {
          var top = 0
          placeables.forEachIndexed { index, placeable ->
            // Placed by measuredWidth, the child's own width. Placing by width, which is kept
            // within the constraints, would misplace a child wider than the layout.
            val start =
                when (alignments[index]) {
                  ColumnAlignment.START -> 0
                  ColumnAlignment.CENTER -> (layoutWidth - placeable.measuredWidth) / 2
                  ColumnAlignment.END -> layoutWidth - placeable.measuredWidth
                }
            placeable.placeRelative(start = start, top = top)
            top += placeable.height
          }
        }
      }
}

// end_example

/** A chat thread: incoming messages at the start, outgoing ones at the end, the day centred. */
class AlignedColumnLayoutExample : KComponent() {
  override fun ComponentScope.render(): Component = AlignedColumnLayout(
      listOf(
          Chip(
              "Today",
              background = CustomLayoutTheme.Page,
              textColor = CustomLayoutTheme.TextSecondary,
              style = Style.margin(bottom = 4.dp).columnAlignment(ColumnAlignment.CENTER),
          ),
          TextBubble("Lunch at noon?", outgoing = false),
          TextBubble(
              "Yes! See you there",
              outgoing = true,
              style = Style.columnAlignment(ColumnAlignment.END),
          ),
          TextBubble("Great, I'll book a table", outgoing = false),
      ),
  )
}

/** A message in a rounded bubble, on the incoming or the outgoing colour. */
private fun ResourcesScope.TextBubble(
    text: String,
    outgoing: Boolean,
    style: Style = Style,
): Component = Text(
    text = text,
    textSize = CustomLayoutTheme.BodySize,
    textColor = CustomLayoutTheme.TextPrimary,
    style =
        style
            .maxWidth(240.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .margin(vertical = 4.dp)
            .background(
                if (outgoing) CustomLayoutTheme.BubbleOutgoing
                else CustomLayoutTheme.BubbleIncoming,
                Shape.RoundedRectangle(CustomLayoutTheme.BubbleRadius),
            ),
)
