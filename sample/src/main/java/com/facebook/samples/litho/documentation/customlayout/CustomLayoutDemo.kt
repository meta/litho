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
import android.graphics.drawable.GradientDrawable
import com.facebook.litho.AccessibilityRole
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.accessibility.accessibilityRole
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.alignSelf
import com.facebook.litho.flexbox.flex
import com.facebook.litho.flexbox.layoutDirection
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.kotlin.widget.VerticalScroll
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useCached
import com.facebook.litho.useState
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.clipChildren
import com.facebook.litho.view.elevation
import com.facebook.litho.view.onClick
import com.facebook.litho.widget.HorizontalScroll
import com.facebook.rendercore.dp
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaJustify

/**
 * Shows a custom layout sample in a card, inside a dashed outline that marks the width it's given,
 * with a caption saying what to try and controls for the layout direction and that width. With
 * [showsFit], a third control switches between an exact width and an at-most one.
 */
class CustomLayoutDemo(
    private val caption: String,
    private val sample: Component,
    private val showsFit: Boolean = false,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    val isRtl = useState { false }
    val width = useState { DemoWidth.WIDE }
    val isExact = useState { true }
    val outlinePx = 1.dp.toPixels()
    val dashPx = 4.dp.toPixels()
    val roomOutline =
        useCached(outlinePx, dashPx) {
          GradientDrawable().apply {
            setStroke(outlinePx, CustomLayoutTheme.RoomOutline, dashPx.toFloat(), dashPx.toFloat())
            cornerRadius = dashPx.toFloat()
          }
        }
    val direction = if (isRtl.value) YogaDirection.RTL else YogaDirection.LTR
    val alignItems = if (isExact.value || !showsFit) YogaAlign.STRETCH else YogaAlign.FLEX_START
    // The outline, and a 4 dp gap inside it, so the sample doesn't touch the dashes. The direction
    // is set here too, because a HorizontalScroll's content doesn't inherit it.
    val room =
        Style.background(roomOutline)
            .padding((outlinePx + 4.dp.toPixels()).px)
            .layoutDirection(direction)
    val sampleInRoom =
        when (width.value) {
          DemoWidth.NARROW ->
              Column(alignItems = alignItems, style = room.width(180.dp)) { child(sample) }
          DemoWidth.WIDE ->
              Column(alignItems = alignItems, style = room.width(296.dp)) { child(sample) }
          DemoWidth.UNBOUNDED ->
              HorizontalScroll(
                  child = Column(alignItems = alignItems, style = room) { child(sample) },
                  scrollbarEnabled = false,
                  horizontalFadingEdgeEnabled = true,
                  fadingEdgeLength = 24.dp,
                  style = Style.alignSelf(YogaAlign.STRETCH),
              )
        }
    return VerticalScroll(fillViewport = true) {
      Column(
          style = Style.padding(CustomLayoutTheme.Padding).backgroundColor(CustomLayoutTheme.Page),
      ) {
        child(
            Column(
                alignItems = YogaAlign.FLEX_START,
                style =
                    Style.padding(CustomLayoutTheme.Padding)
                        .background(
                            CustomLayoutTheme.Surface,
                            Shape.RoundedRectangle(CustomLayoutTheme.CardRadius),
                        )
                        .elevation(2.dp)
                        .clipChildren(true)
                        .layoutDirection(direction),
            ) {
              child(sampleInRoom)
            },
        )
        child(
            Text(
                text = caption,
                textSize = CustomLayoutTheme.LabelSize,
                textColor = CustomLayoutTheme.TextSecondary,
                style = Style.margin(top = 12.dp, bottom = 4.dp),
            ),
        )
        child(
            SegmentedControl("Direction", listOf("LTR", "RTL"), if (isRtl.value) 1 else 0) { index
              ->
              isRtl.update(index == 1)
            },
        )
        child(
            SegmentedControl("Width", DemoWidth.entries.map { it.label }, width.value.ordinal) {
                index ->
              width.update(DemoWidth.entries[index])
            },
        )
        if (showsFit) {
          child(
              SegmentedControl(
                  "Width is",
                  listOf("Exact", "At most"),
                  if (isExact.value) 0 else 1,
              ) { index ->
                isExact.update(index == 0)
              },
          )
        }
      }
    }
  }
}

private enum class DemoWidth(val label: String) {
  NARROW("Narrow"),
  WIDE("Wide"),
  UNBOUNDED("Unbounded"),
}

/** A titled row of options in a rounded track; the selected one is filled. 48 dp high. */
private fun ResourcesScope.SegmentedControl(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
): Component =
    Column(style = Style.margin(top = 12.dp)) {
      child(
          Text(
              text = title,
              textSize = CustomLayoutTheme.LabelSize,
              textColor = CustomLayoutTheme.TextPrimary,
              style = Style.margin(bottom = 4.dp),
          ),
      )
      child(
          Row(
              style =
                  Style.padding(horizontal = 4.dp)
                      .background(
                          CustomLayoutTheme.Outline,
                          Shape.RoundedRectangle(CustomLayoutTheme.PillRadius),
                      ),
          ) {
            options.forEachIndexed { index, option ->
              val isSelected = index == selected
              child(
                  Row(
                      style =
                          Style.flex(grow = 1f, basis = 0.dp)
                              .height(CustomLayoutTheme.TouchTarget)
                              .padding(vertical = 4.dp)
                              .accessibilityRole(AccessibilityRole.BUTTON)
                              .onClick { onSelect(index) },
                  ) {
                    child(
                        Row(
                            alignItems = YogaAlign.CENTER,
                            justifyContent = YogaJustify.CENTER,
                            style =
                                Style.flex(grow = 1f)
                                    .background(
                                        if (isSelected) CustomLayoutTheme.Surface
                                        else CustomLayoutTheme.Outline,
                                        Shape.RoundedRectangle(CustomLayoutTheme.PillRadius),
                                    ),
                        ) {
                          child(
                              Text(
                                  text = option,
                                  textSize = CustomLayoutTheme.LabelSize,
                                  textColor = CustomLayoutTheme.TextPrimary,
                                  textStyle = if (isSelected) Typeface.BOLD else Typeface.NORMAL,
                              ),
                          )
                        },
                    )
                  },
              )
            }
          },
      )
    }
