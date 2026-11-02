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

package com.facebook.litho.widget

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.facebook.litho.ComponentHost
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.maxHeight
import com.facebook.litho.core.maxWidth
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.minWidth
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.alignSelf
import com.facebook.litho.kotlin.widget.HorizontalDivider
import com.facebook.litho.kotlin.widget.VerticalDivider
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import com.facebook.yoga.YogaAlign
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DividerTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `horizontal divider preserves thickness and stretches across column`() {
    val lithoView =
        lithoTestRule
            .render(widthPx = 100, heightPx = 40) {
              Column {
                child(
                    HorizontalDivider(
                        color = Color.RED,
                        thickness = 2.px,
                        style =
                            Style.height(17.px)
                                .minHeight(17.px)
                                .maxHeight(17.px)
                                .alignSelf(YogaAlign.FLEX_START)
                                .margin(start = 7.px),
                    ),
                )
              }
            }
            .lithoView

    val divider = lithoView.dividerHost()
    assertThat(divider.bounds()).isEqualTo(Rect(7, 0, 100, 2))
    assertThat((divider.background as ColorDrawable).color).isEqualTo(Color.RED)
  }

  @Test
  fun `horizontal divider fills available width inside row`() {
    val lithoView =
        lithoTestRule
            .render(widthPx = 100, heightPx = 40) {
              Row { child(HorizontalDivider(color = Color.RED, thickness = 2.px)) }
            }
            .lithoView

    assertThat(lithoView.dividerHost().bounds()).isEqualTo(Rect(0, 0, 100, 2))
  }

  @Test
  fun `vertical divider preserves thickness and stretches across row`() {
    val lithoView =
        lithoTestRule
            .render(widthPx = 40, heightPx = 100) {
              Row {
                child(
                    VerticalDivider(
                        color = Color.BLUE,
                        thickness = 2.px,
                        style =
                            Style.width(17.px)
                                .minWidth(17.px)
                                .maxWidth(17.px)
                                .alignSelf(YogaAlign.FLEX_START)
                                .margin(top = 7.px),
                    ),
                )
              }
            }
            .lithoView

    val divider = lithoView.dividerHost()
    assertThat(divider.bounds()).isEqualTo(Rect(0, 7, 2, 100))
    assertThat((divider.background as ColorDrawable).color).isEqualTo(Color.BLUE)
  }

  @Test
  fun `vertical divider fills available height inside column`() {
    val lithoView =
        lithoTestRule
            .render(widthPx = 40, heightPx = 100) {
              Column { child(VerticalDivider(color = Color.BLUE, thickness = 2.px)) }
            }
            .lithoView

    assertThat(lithoView.dividerHost().bounds()).isEqualTo(Rect(0, 0, 2, 100))
  }

  private fun ComponentHost.dividerHost(): ComponentHost =
      (0 until childCount).map(::getChildAt).filterIsInstance<ComponentHost>().single()

  private fun View.bounds(): Rect = Rect(left, top, right, bottom)
}
