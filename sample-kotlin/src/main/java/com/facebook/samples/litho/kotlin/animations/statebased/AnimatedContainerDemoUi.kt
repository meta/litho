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

package com.facebook.samples.litho.kotlin.animations.statebased

import android.graphics.Color
import com.facebook.litho.Component
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.widthPercent
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.FlexboxScope
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.view.background
import com.facebook.litho.view.backgroundColor
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp
import com.facebook.samples.litho.kotlin.drawable.RoundedRect
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

internal fun ResourcesScope.DemoSurface(content: FlexboxScope.() -> Unit): Component = Column(
    style = Style.padding(all = 18.dp).backgroundColor(Color.WHITE),
) {
  content()
}

internal fun ResourcesScope.SectionTitle(text: String): Component = Text(
    text = text,
    textSize = 22.sp,
    textColor = INK,
    style = Style.margin(bottom = 12.dp),
)

internal fun ResourcesScope.DemoButton(
    label: String,
    style: Style = Style,
    selected: Boolean = false,
): Component = Column(
    justifyContent = YogaJustify.CENTER,
    alignItems = YogaAlign.CENTER,
    style =
        style
            .height(40.dp)
            .padding(horizontal = 14.dp)
            .background(RoundedRect(if (selected) 0xFF0F766E else 0xFFE2E8F0, 20.dp)),
) {
  child(Text(label, textSize = 13.sp, textColor = if (selected) Color.WHITE else INK))
}

internal fun ResourcesScope.StatusRow(
    label: String,
    value: String,
    style: Style = Style,
): Component = Row(
    justifyContent = YogaJustify.SPACE_BETWEEN,
    alignItems = YogaAlign.CENTER,
    style =
        style
            .widthPercent(100f)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .margin(bottom = 10.dp)
            .background(RoundedRect(0xFFF1F5F9, 12.dp)),
) {
  child(Text(label, textSize = 13.sp, textColor = MUTED))
  child(Text(value, textSize = 13.sp, textColor = INK))
}

private val INK = 0xFF0F172A.toInt()
private val MUTED = 0xFF64748B.toInt()
