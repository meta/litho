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

import android.graphics.Typeface
import android.text.TextUtils
import com.facebook.litho.AccessibilityRole
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Layout
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.accessibility.accessibilityRole
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.padding
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.Placeable
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useState
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

// start_example
/**
 * Gives each of [children] an equal share of the maximum width, side by side, and measures each at
 * exactly its share: a child with no width of its own fills its share, and one with its own width
 * keeps it, from the start of its share. The pixels a split leaves over go one each to the first
 * children, so the shares fill the width and differ by at most 1 px. With an unbounded width, each
 * share is as wide as the widest child, and each child keeps its own width.
 */
@OptIn(ExperimentalLithoApi::class)
class EqualSplitLayout(
    private val children: List<Component>,
    private val style: Style = Style,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Layout(children, style = style) { measurables, constraints ->
        if (measurables.isEmpty()) {
          layout(width = 0, height = 0) {}
          return@Layout
        }
        val placeables: List<Placeable>
        val shares: List<Int>
        if (constraints.hasBoundedWidth) {
          val share = constraints.maxWidth / measurables.size
          val leftOver = constraints.maxWidth % measurables.size
          shares = List(measurables.size) { index -> if (index < leftOver) share + 1 else share }
          placeables = measurables.mapIndexed { index, child ->
            child.measure(
                constraints.copy(minWidth = shares[index], maxWidth = shares[index], minHeight = 0),
            )
          }
        } else {
          placeables = measurables.map { child -> child.measure(constraints.copy(minHeight = 0)) }
          val widest = placeables.maxOf { placeable -> placeable.width }
          shares = List(placeables.size) { widest }
        }
        layout(width = shares.sum(), height = placeables.maxOf { placeable -> placeable.height }) {
          var start = 0
          placeables.forEachIndexed { index, placeable ->
            placeable.placeRelative(start = start, top = 0)
            start += shares[index]
          }
        }
      }
}

// end_example

/** A segmented tab bar: three tabs of equal width, whatever their labels; tap one to select it. */
class EqualSplitLayoutExample : KComponent() {
  override fun ComponentScope.render(): Component {
    val selected = useState { 0 }
    return Column(
        style =
            Style.padding(horizontal = 4.dp)
                .background(
                    CustomLayoutTheme.Outline,
                    Shape.RoundedRectangle(CustomLayoutTheme.PillRadius),
                ),
    ) {
      child(
          EqualSplitLayout(
              TABS.mapIndexed { index, label ->
                Tab(label, isSelected = index == selected.value) { selected.update(index) }
              },
          ),
      )
    }
  }
}

private val TABS: List<String> = listOf("All", "Chats", "Calls")

/** One tab, 48 dp high, with a filled pill inside it when selected. */
private fun ResourcesScope.Tab(
    label: String,
    isSelected: Boolean,
    onTap: () -> Unit,
): Component = Row(
    style =
        Style.height(CustomLayoutTheme.TouchTarget)
            .padding(vertical = 4.dp)
            .accessibilityRole(AccessibilityRole.BUTTON)
            .onClick { onTap() },
) {
  child(
      Row(
          alignItems = YogaAlign.CENTER,
          justifyContent = YogaJustify.CENTER,
          style =
              Style.flex(grow = 1f)
                  .padding(horizontal = 8.dp)
                  .background(
                      if (isSelected) CustomLayoutTheme.Surface else CustomLayoutTheme.Outline,
                      Shape.RoundedRectangle(CustomLayoutTheme.PillRadius),
                  ),
      ) {
        child(
            Text(
                text = label,
                textSize = CustomLayoutTheme.LabelSize,
                textColor = CustomLayoutTheme.TextPrimary,
                textStyle = if (isSelected) Typeface.BOLD else Typeface.NORMAL,
                maxLines = 1,
                ellipsize = TextUtils.TruncateAt.END,
            ),
        )
      },
  )
}
