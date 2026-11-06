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
import androidx.core.text.TextDirectionHeuristicsCompat
import com.facebook.litho.Component
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

/**
 * The look shared by the custom layout examples and their demo frame: colours, type sizes, corner
 * radii and spacing, and the small pieces of UI the examples are built from. It's kept out of the
 * layout code the docs include, which uses none of it.
 */
internal object CustomLayoutTheme {
  val Surface: Int = Color.WHITE
  val Page: Int = Color.rgb(0xF2, 0xF3, 0xF5)
  val Outline: Int = Color.rgb(0xDA, 0xDD, 0xE1)
  val RoomOutline: Int = Color.rgb(0x85, 0x8D, 0x98)
  val TextPrimary: Int = Color.rgb(0x1C, 0x1E, 0x21)
  val TextSecondary: Int = Color.rgb(0x60, 0x67, 0x70)
  val Accent: Int = Color.rgb(0x00, 0x5F, 0xB8)
  val AccentSoft: Int = Color.rgb(0xE6, 0xF0, 0xFB)
  val BubbleIncoming: Int = Color.rgb(0xF0, 0xF2, 0xF5)
  val BubbleOutgoing: Int = Color.rgb(0xDC, 0xEB, 0xFD)
  val Badge: Int = Color.rgb(0xD3, 0x2F, 0x2F)
  /** Avatar colours, each dark enough for white initials. */
  val AvatarColors: List<Int> = listOf(
      Color.rgb(0x15, 0x65, 0xC0),
      Color.rgb(0x8E, 0x24, 0xAA),
      Color.rgb(0x2E, 0x7D, 0x32),
      Color.rgb(0xBF, 0x36, 0x0C),
      Color.rgb(0x00, 0x69, 0x73),
      Color.rgb(0xC2, 0x18, 0x5B),
      Color.rgb(0x5D, 0x40, 0x37),
  )

  val BodySize: Dimen = 16.sp
  val LabelSize: Dimen = 14.sp
  val MetaSize: Dimen = 12.sp

  val CardRadius: Dimen = 16.dp
  val BubbleRadius: Dimen = 12.dp
  val PillRadius: Dimen = 999.dp

  val Gap: Dimen = 8.dp
  val Padding: Dimen = 16.dp
  val TouchTarget: Dimen = 48.dp
  val AvatarSize: Dimen = 40.dp
  val AvatarRing: Dimen = 2.dp
}

/** A circle of [size] with [initials] in white on [color], inside a white ring. */
internal fun ResourcesScope.Avatar(
    initials: String,
    color: Int,
    size: Dimen = CustomLayoutTheme.AvatarSize,
    textSize: Dimen = CustomLayoutTheme.LabelSize,
    style: Style = Style,
): Component = Column(
    style =
        style
            .width(size)
            .height(size)
            .padding(CustomLayoutTheme.AvatarRing)
            .background(Color.WHITE, Shape.Circle),
) {
  child(
      Column(
          alignItems = YogaAlign.CENTER,
          justifyContent = YogaJustify.CENTER,
          style = Style.flex(grow = 1f).background(color, Shape.Circle),
      ) {
        child(
            Text(
                text = initials,
                textSize = textSize,
                textColor = Color.WHITE,
                textStyle = Typeface.BOLD,
                // Keeps "+3" from reading "3+" in a right-to-left layout.
                textDirection = TextDirectionHeuristicsCompat.LTR,
            ),
        )
      },
  )
}

/** A rounded chip with [text]: a tag, or a label such as "Today". */
internal fun ResourcesScope.Chip(
    text: String,
    background: Int = CustomLayoutTheme.AccentSoft,
    textColor: Int = CustomLayoutTheme.Accent,
    style: Style = Style,
): Component = Text(
    text = text,
    textSize = CustomLayoutTheme.LabelSize,
    textColor = textColor,
    style =
        style
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(background, Shape.RoundedRectangle(CustomLayoutTheme.PillRadius)),
)
