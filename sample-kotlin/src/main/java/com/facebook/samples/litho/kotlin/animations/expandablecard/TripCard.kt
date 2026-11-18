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

package com.facebook.samples.litho.kotlin.animations.expandablecard

import android.graphics.Color
import android.graphics.Typeface
import com.facebook.litho.Component
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.size
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Image
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.FlexboxScope
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.clip
import com.facebook.litho.view.elevation
import com.facebook.rendercore.dp
import com.facebook.rendercore.drawableRes
import com.facebook.rendercore.sp
import com.facebook.rendercore.stringRes
import com.facebook.samples.litho.R
import com.facebook.samples.litho.kotlin.drawable.RoundedRect
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

// Trip card UI shared by [StateBasedExpandableCardComponent] and
// [DiffBasedExpandableCardComponent], so the two demos differ only in how they animate.

/** The page title, the card holding [content], and a hint below it. */
internal inline fun ResourcesScope.TripCardPage(
    crossinline content: FlexboxScope.() -> Unit,
): Component =
    Column(style = Style.padding(all = 24.dp).backgroundColor(SURFACE)) {
      child(
          Text(
              stringRes(R.string.litho_sample_expandable_title),
              textSize = 28.sp,
              textStyle = Typeface.BOLD,
              textColor = INK,
          ),
      )
      child(
          Text(
              stringRes(R.string.litho_sample_expandable_subtitle),
              textSize = 15.sp,
              textColor = MUTED,
              style = Style.margin(top = 6.dp, bottom = 24.dp),
          ),
      )
      child(
          Column(
              style =
                  Style.widthPercent(100f)
                      .background(RoundedRect(Color.WHITE, 24.dp))
                      .clip(Shape.RoundedRectangle(24.dp))
                      .elevation(6.dp),
          ) {
            content()
          },
      )
      child(
          Text(
              stringRes(R.string.litho_sample_expandable_hint),
              textSize = 13.sp,
              textColor = MUTED,
              style = Style.margin(top = 20.dp),
          ),
      )
    }

internal fun ResourcesScope.TripHeader(
    expanded: Boolean,
    chevronStyle: Style,
    style: Style,
): Component = Row(
    alignItems = YogaAlign.CENTER,
    style =
        Style.padding(all = 24.dp)
            .background(drawableRes(R.drawable.litho_sample_expandable_coast)) + style,
) {
  child(
      Column(style = Style.flex(grow = 1f, shrink = 1f).margin(end = 12.dp)) {
        child(
            Text(
                stringRes(R.string.litho_sample_expandable_collection),
                textSize = 11.sp,
                textStyle = Typeface.BOLD,
                textColor = ON_ARTWORK,
            ),
        )
        child(
            Text(
                stringRes(R.string.litho_sample_expandable_destination),
                textSize = 26.sp,
                textStyle = Typeface.BOLD,
                textColor = Color.WHITE,
                style = Style.margin(top = 12.dp),
            ),
        )
        child(
            Text(
                stringRes(R.string.litho_sample_expandable_location),
                textSize = 14.sp,
                textColor = ON_ARTWORK,
                style = Style.margin(top = 6.dp),
            ),
        )
        child(
            Text(
                stringRes(
                    if (expanded) R.string.litho_sample_expandable_hide
                    else R.string.litho_sample_expandable_show,
                ),
                textSize = 13.sp,
                textStyle = Typeface.BOLD,
                textColor = Color.WHITE,
                style = Style.margin(top = 24.dp),
            ),
        )
      },
  )
  child(
      Row(
          alignItems = YogaAlign.CENTER,
          justifyContent = YogaJustify.CENTER,
          style = Style.size(44.dp).background(RoundedRect(0x26FFFFFF, 22.dp)) + chevronStyle,
      ) {
        child(
            Image(
                drawable = drawableRes(R.drawable.litho_sample_expandable_chevron),
                style = Style.size(20.dp),
            ),
        )
      },
  )
}

internal fun ResourcesScope.TripDetails(style: Style): Component =
    Column(style = Style.widthPercent(100f).padding(all = 24.dp) + style) {
      child(
          Row(style = Style.background(RoundedRect(SURFACE, 16.dp)).padding(vertical = 16.dp)) {
            child(
                TripMetric(
                    R.string.litho_sample_expandable_duration_value,
                    R.string.litho_sample_expandable_duration,
                ),
            )
            child(
                TripMetric(
                    R.string.litho_sample_expandable_stops_value,
                    R.string.litho_sample_expandable_stops,
                ),
            )
            child(
                TripMetric(
                    R.string.litho_sample_expandable_pace_value,
                    R.string.litho_sample_expandable_pace,
                ),
            )
          },
      )
      child(
          Text(
              stringRes(R.string.litho_sample_expandable_details_title),
              textSize = 18.sp,
              textStyle = Typeface.BOLD,
              textColor = INK,
              style = Style.margin(top = 24.dp),
          ),
      )
      child(
          Text(
              stringRes(R.string.litho_sample_expandable_summary),
              textSize = 14.sp,
              textColor = MUTED,
              style = Style.margin(top = 6.dp, bottom = 20.dp),
          ),
      )
      child(
          TripStop(
              "01",
              R.string.litho_sample_expandable_day_one_title,
              R.string.litho_sample_expandable_day_one_summary,
          ),
      )
      child(
          TripStop(
              "02",
              R.string.litho_sample_expandable_day_two_title,
              R.string.litho_sample_expandable_day_two_summary,
              Style.margin(top = 16.dp),
          ),
      )
    }

private fun ResourcesScope.TripMetric(valueRes: Int, labelRes: Int): Component = Column(
    alignItems = YogaAlign.CENTER,
    style = Style.flex(basis = 0.dp, grow = 1f, shrink = 1f),
) {
  child(Text(stringRes(valueRes), textSize = 18.sp, textStyle = Typeface.BOLD, textColor = INK))
  child(
      Text(
          stringRes(labelRes),
          textSize = 12.sp,
          textColor = MUTED,
          style = Style.margin(top = 4.dp),
      ),
  )
}

private fun ResourcesScope.TripStop(
    number: String,
    titleRes: Int,
    summaryRes: Int,
    style: Style = Style,
): Component =
    Row(alignItems = YogaAlign.CENTER, style = style) {
      child(
          Row(
              alignItems = YogaAlign.CENTER,
              justifyContent = YogaJustify.CENTER,
              style =
                  Style.size(36.dp).margin(end = 12.dp).background(RoundedRect(STOP_BADGE, 12.dp)),
          ) {
            child(Text(number, textSize = 12.sp, textStyle = Typeface.BOLD, textColor = ACCENT))
          },
      )
      child(
          Column(style = Style.flex(grow = 1f, shrink = 1f)) {
            child(
                Text(
                    stringRes(titleRes),
                    textSize = 14.sp,
                    textStyle = Typeface.BOLD,
                    textColor = INK,
                ),
            )
            child(
                Text(
                    stringRes(summaryRes),
                    textSize = 13.sp,
                    textColor = MUTED,
                    style = Style.margin(top = 3.dp),
                ),
            )
          },
      )
    }

internal val DETAILS_OFFSET = 12.dp

private val SURFACE = 0xFFF1F5F4.toInt()
private val INK = 0xFF142D2A.toInt()
private val MUTED = 0xFF627570.toInt()
private val ON_ARTWORK = 0xFFCCFBF1.toInt()
private val ACCENT = 0xFF0F766E.toInt()
private val STOP_BADGE = 0xFFE0F2F1.toInt()
