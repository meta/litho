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
import com.facebook.litho.key
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp

// start_example
/**
 * Lays out [items] in order along a line, and starts a new line below when the next item doesn't
 * fit: for example, tags. [itemSpacing] separates items on a line and [lineSpacing] separates
 * lines. An item wider than the maximum width gets a line of its own and extends past the end edge.
 * With an unbounded width, every item is on one line.
 */
@OptIn(ExperimentalLithoApi::class)
class FlowRowLayout(
    private val items: List<Component>,
    private val itemSpacing: Dimen = 0.dp,
    private val lineSpacing: Dimen = 0.dp,
    private val style: Style = Style,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Layout(items, style = style) { measurables, constraints ->
        val itemSpacingPx = itemSpacing.toPixels()
        val lineSpacingPx = lineSpacing.toPixels()
        // Lines stack downwards, so an item's height isn't limited.
        val itemConstraints =
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = SizeConstraints.Infinity)
        val placeables = measurables.map { item -> item.measure(itemConstraints) }
        val starts = IntArray(placeables.size)
        val tops = IntArray(placeables.size)
        var itemsOnLine = 0
        var lineEnd = 0
        var lineTop = 0
        var lineHeight = 0
        var widest = 0
        placeables.forEachIndexed { index, placeable ->
          var start = if (itemsOnLine == 0) 0 else lineEnd + itemSpacingPx
          if (itemsOnLine > 0 && start + placeable.width > constraints.maxWidth) {
            lineTop += lineHeight + lineSpacingPx
            itemsOnLine = 0
            lineHeight = 0
            start = 0
          }
          starts[index] = start
          tops[index] = lineTop
          lineEnd = start + placeable.width
          lineHeight = maxOf(lineHeight, placeable.height)
          widest = maxOf(widest, lineEnd)
          itemsOnLine++
        }
        layout(width = widest, height = lineTop + lineHeight) {
          placeables.forEachIndexed { index, placeable ->
            placeable.placeRelative(start = starts[index], top = tops[index])
          }
        }
      }
}

// end_example

/** Topic tags as rounded chips, wrapping onto as many lines as the width needs. */
class FlowRowLayoutExample : KComponent() {
  override fun ComponentScope.render(): Component = FlowRowLayout(
      items = TAGS.map { tag -> key(tag) { Chip(tag) } },
      itemSpacing = CustomLayoutTheme.Gap,
      lineSpacing = CustomLayoutTheme.Gap,
  )
}

private val TAGS: List<String> =
    listOf("Kotlin", "Layout", "Yoga", "Litho", "Android", "Views", "Flexbox", "Animation")
