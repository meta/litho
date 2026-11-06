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
import android.graphics.Typeface
import android.text.TextUtils
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Layout
import com.facebook.litho.Style
import com.facebook.litho.accessibility.contentDescription
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.widget.TextAlignment
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.quantityStringRes
import com.facebook.samples.litho.R
import com.facebook.yoga.YogaAlign

// start_example
/**
 * Shows [badge] centred on the top-end corner of [content]. It's larger than the room [content]
 * takes by the part of the badge that sticks out, and the badge stays on the content's corner even
 * when the content is wider than the maximum width.
 */
@OptIn(ExperimentalLithoApi::class)
class BadgeOverlayLayout(
    private val content: Component,
    private val badge: Component,
    private val style: Style = Style,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Layout(content, badge, style = style) { contentChild, badgeChild, constraints ->
        val badgePlaceable = badgeChild.measure(constraints.copy(minWidth = 0, minHeight = 0))
        // What sticks out past the content's end and top: half the badge, rounded up.
        val overhangX = badgePlaceable.width - badgePlaceable.width / 2
        val overhangY = badgePlaceable.height - badgePlaceable.height / 2
        // The content's room leaves space for the overhang. An unbounded axis stays unbounded:
        // SizeConstraints.Infinity is a marker, and subtracting from it would make a real limit.
        val contentPlaceable =
            contentChild.measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth =
                        if (constraints.hasBoundedWidth) {
                          (constraints.maxWidth - overhangX).coerceAtLeast(0)
                        } else {
                          SizeConstraints.Infinity
                        },
                    maxHeight =
                        if (constraints.hasBoundedHeight) {
                          (constraints.maxHeight - overhangY).coerceAtLeast(0)
                        } else {
                          SizeConstraints.Infinity
                        },
                ),
            )
        layout(contentPlaceable.width + overhangX, contentPlaceable.height + overhangY) {
          contentPlaceable.placeRelative(start = 0, top = overhangY)
          // Placed last, so it's drawn over the content. Placed by measuredWidth, the content's own
          // width, because width is kept within the constraints.
          badgePlaceable.placeRelative(
              start = contentPlaceable.measuredWidth + overhangX - badgePlaceable.width,
              top = 0,
          )
        }
      }
}

// end_example

/**
 * A conversation in a list: the contact's avatar with a count of new items, their name and the last
 * message.
 */
class BadgeOverlayLayoutExample : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(alignItems = YogaAlign.CENTER) {
        child(
            BadgeOverlayLayout(
                content =
                    Avatar(
                        "AL",
                        CustomLayoutTheme.AvatarColors[0],
                        size = 56.dp,
                        textSize = CustomLayoutTheme.BodySize,
                    ),
                badge =
                    Column(
                        style =
                            Style.padding(CustomLayoutTheme.AvatarRing)
                                .background(
                                    Color.WHITE,
                                    Shape.RoundedRectangle(CustomLayoutTheme.PillRadius),
                                ),
                    ) {
                      child(
                          Text(
                              text = "3",
                              textSize = CustomLayoutTheme.MetaSize,
                              textColor = Color.WHITE,
                              textStyle = Typeface.BOLD,
                              style =
                                  Style.padding(horizontal = 6.dp, vertical = 1.dp)
                                      .background(
                                          CustomLayoutTheme.Badge,
                                          Shape.RoundedRectangle(CustomLayoutTheme.PillRadius),
                                      )
                                      .contentDescription(
                                          quantityStringRes(
                                              R.plurals.custom_layout_new_items,
                                              3,
                                              3,
                                          ),
                                      ),
                          ),
                      )
                    },
            ),
        )
        child(
            Column(style = Style.flex(grow = 1f, shrink = 1f).margin(start = 12.dp)) {
              child(
                  Text(
                      text = "Alex Lee",
                      textSize = CustomLayoutTheme.BodySize,
                      textColor = CustomLayoutTheme.TextPrimary,
                      textStyle = Typeface.BOLD,
                      alignment = TextAlignment.LAYOUT_START,
                      maxLines = 1,
                      ellipsize = TextUtils.TruncateAt.END,
                  ),
              )
              child(
                  Text(
                      text = "See you at eight!",
                      textSize = CustomLayoutTheme.LabelSize,
                      textColor = CustomLayoutTheme.TextSecondary,
                      alignment = TextAlignment.LAYOUT_START,
                      maxLines = 1,
                      ellipsize = TextUtils.TruncateAt.END,
                  ),
              )
            },
        )
      }
}
