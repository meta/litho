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
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.rendercore.SizeConstraints

// start_example
/**
 * Stacks [children] from the top, each at the start edge, as a `Column` with `alignItems` set to
 * `FLEX_START` does. With [stretch], each child is measured at the maximum width: under an exact
 * width that's what a `Column` that stretches its children does.
 *
 * `measure` applies each child's own box props within the constraints, so each child gets the box a
 * `Column` gives it. That covers width, in pixels or as a percentage of the maximum width; height
 * in pixels; min and max width and height; an aspect ratio with one axis fixed; margin; padding;
 * and border. A `Layout` ignores flexbox props such as `flex` and `alignSelf`.
 *
 * A child differs from a `Column`'s child on purpose in these cases:
 * - A child `Column` whose min width is above its max width gets the min, as in CSS, whereas a
 *   `Column` that doesn't stretch it gives it the max.
 * - A percentage height has no base, because this layout gives its children no height limit, so the
 *   child keeps its content's height, as with an auto height in CSS.
 * - A negative max width has no effect, as in CSS, whereas a stretching `Column` gives the child no
 *   width.
 * - An aspect ratio on a stretched child with a height has no effect, as `align-self: stretch` wins
 *   in CSS, whereas a `Column` derives the width from the height.
 * - An aspect ratio with neither axis fixed has no effect, as in CSS block layout, whereas a
 *   `Column` derives the width from the content's height.
 * - A child `Row` whose content is wider than the maximum width is laid out within it, whereas a
 *   `Column` that doesn't stretch it keeps the `Row` as wide as its content.
 * - With [stretch] under an at-most width, each child fills the maximum, whereas a stretching
 *   `Column` is as wide as its widest child.
 * - Under an at-most width, a percentage width is of the maximum, whereas in a `Column` that wraps
 *   its content the child keeps its content's width.
 * - Under an at-most width, a child wider than the maximum doesn't make this layout wider, whereas
 *   a `Column` grows to hold it.
 * - A fractional percentage margin can round 1 px differently: 7.5 px is 8 px here and 7 px in a
 *   `Column`.
 */
@OptIn(ExperimentalLithoApi::class)
class ChildBoxPropsLayout(
    private val children: List<Component>,
    private val stretch: Boolean = false,
    private val style: Style = Style,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Layout(children, style = style) { measurables, constraints ->
        // No height limit, so each child keeps its own height and the stack may be taller than
        // the height it's given.
        val childConstraints =
            constraints.copy(
                minWidth = if (stretch && constraints.hasBoundedWidth) constraints.maxWidth else 0,
                minHeight = 0,
                maxHeight = SizeConstraints.Infinity,
            )
        val placeables = measurables.map { child -> child.measure(childConstraints) }
        layout(
            width = placeables.maxOfOrNull { placeable -> placeable.width } ?: 0,
            height = placeables.sumOf { placeable -> placeable.height },
        ) {
          var top = 0
          for (placeable in placeables) {
            placeable.placeRelative(start = 0, top = top)
            top += placeable.height
          }
        }
      }
}

// end_example
