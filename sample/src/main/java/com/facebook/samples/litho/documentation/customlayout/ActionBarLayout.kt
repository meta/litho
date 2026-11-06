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

import com.facebook.litho.AccessibilityRole
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Layout
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.accessibility.accessibilityRole
import com.facebook.litho.accessibility.contentDescription
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Image
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.drawableRes
import com.facebook.rendercore.stringRes
import com.facebook.samples.litho.R
import com.facebook.yoga.YogaAlign

// start_example
/**
 * Shows [labelled] when its own width fits the maximum width, and [iconOnly] otherwise: for
 * example, buttons with labels, and the same buttons as icons for narrow screens. It fills the
 * maximum width; with an unbounded width, [labelled] always fits.
 *
 * Both variants are declared up front, so both are resolved in `render`. [iconOnly] is measured
 * only when [labelled] doesn't fit, and the variant the layout doesn't place is never mounted.
 */
@OptIn(ExperimentalLithoApi::class)
class ActionBarLayout(
    private val labelled: Component,
    private val iconOnly: Component,
    private val style: Style = Style,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Layout(labelled, iconOnly, style = style) { labelledChild, iconOnlyChild, constraints ->
        // With no width limit, the labelled buttons report their full width instead of shrinking.
        val labelledPlaceable =
            labelledChild.measure(
                constraints.copy(minWidth = 0, maxWidth = SizeConstraints.Infinity, minHeight = 0),
            )
        val chosen =
            if (labelledPlaceable.measuredWidth <= constraints.maxWidth) {
              labelledPlaceable
            } else {
              iconOnlyChild.measure(constraints.copy(minWidth = 0, minHeight = 0))
            }
        // An unbounded maxWidth is SizeConstraints.Infinity, a marker rather than a width to
        // declare.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else chosen.width
        layout(width, chosen.height) { chosen.placeRelative(start = 0, top = 0) }
      }
}

// end_example

/** Like, Comment and Share, with labels when they fit and as icons when they don't. */
class ActionBarLayoutExample : KComponent() {
  override fun ComponentScope.render(): Component = ActionBarLayout(
      labelled =
          Row {
            ACTIONS.forEach { action ->
              child(LabelledAction(action.icon, stringRes(action.label)))
            }
          },
      iconOnly =
          Row {
            ACTIONS.forEach { action -> child(IconAction(action.icon, stringRes(action.label))) }
          },
  )
}

private class Action(val icon: Int, val label: Int)

private val ACTIONS: List<Action> = listOf(
    Action(R.drawable.ic_custom_layout_like, R.string.custom_layout_action_like),
    Action(R.drawable.ic_custom_layout_comment, R.string.custom_layout_action_comment),
    Action(R.drawable.ic_custom_layout_share, R.string.custom_layout_action_share),
)

/** An icon and a label in a pill, 48 dp high. */
private fun ResourcesScope.LabelledAction(icon: Int, label: String): Component = Row(
    alignItems = YogaAlign.CENTER,
    style =
        Style.height(CustomLayoutTheme.TouchTarget)
            .padding(horizontal = 10.dp)
            .margin(end = 4.dp)
            .background(
                CustomLayoutTheme.Page,
                Shape.RoundedRectangle(CustomLayoutTheme.PillRadius),
            )
            .accessibilityRole(AccessibilityRole.BUTTON),
) {
  child(Image(drawable = drawableRes(icon), style = Style.width(18.dp).height(18.dp)))
  child(
      Text(
          text = label,
          textSize = CustomLayoutTheme.LabelSize,
          textColor = CustomLayoutTheme.TextPrimary,
          style = Style.margin(start = 4.dp),
      ),
  )
}

/** An icon alone in a 48 dp circle, with its label as the screen reader text. */
private fun ResourcesScope.IconAction(icon: Int, label: String): Component = Row(
    alignItems = YogaAlign.CENTER,
    style =
        Style.width(CustomLayoutTheme.TouchTarget)
            .height(CustomLayoutTheme.TouchTarget)
            .padding(14.dp)
            .margin(end = 4.dp)
            .background(CustomLayoutTheme.Page, Shape.Circle)
            .contentDescription(label)
            .accessibilityRole(AccessibilityRole.BUTTON),
) {
  child(Image(drawable = drawableRes(icon), style = Style.width(20.dp).height(20.dp)))
}
