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

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.binders.onBind
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.view.viewTag
import com.facebook.litho.view.wrapInView
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** A Text with no text keeps its place in the layout for both legacy and primitive Text. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextEmptyParityTest(private val usePrimitiveText: Boolean) {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveText = false

  @Before
  fun setUp() {
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    ComponentsConfiguration.usePrimitiveText = usePrimitiveText
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
  }

  @Test
  fun `null text keeps its margin and flex in the layout`() {
    assertThat(markerLeft(text = null)).isEqualTo(EXPECTED_MARKER_LEFT)
  }

  @Test
  fun `empty text keeps its margin and flex in the layout`() {
    assertThat(markerLeft(text = "")).isEqualTo(EXPECTED_MARKER_LEFT)
  }

  @Test
  fun `null text adds no extra width when it does not grow`() {
    assertThat(markerLeft(text = null, grow = false)).isEqualTo(MARGIN_PX)
  }

  @Test
  fun `empty text adds no extra width when it does not grow`() {
    assertThat(markerLeft(text = "", grow = false)).isEqualTo(MARGIN_PX)
  }

  @Test
  fun `empty text rounds a fractional width like legacy`() {
    for (text in listOf(null, "")) {
      for (percent in listOf(20.1f, 33.3f, 50.5f, 66.7f)) {
        for (start in listOf(0, 1, 3)) {
          assertThat(roundedWidth(text, percent, start))
              .describedAs("text=%s widthPercent=%s start=%s", text, percent, start)
              .isEqualTo(legacyRoundedWidth(text, percent, start))
        }
      }
    }
  }

  @Test
  fun `empty text runs content binders like legacy`() {
    for (text in listOf(null, "")) {
      var binds = 0
      var unbinds = 0
      val testLithoView =
          lithoTestRule.render(widthPx = ROW_WIDTH_PX, heightPx = 100) {
            object : KComponent() {
              override fun ComponentScope.render(): Component = Row {
                child(
                    Text(
                        text = text,
                        style =
                            Style.width(20.px).height(20.px).onBind(Unit) { _ ->
                              binds++
                              onUnbind { unbinds++ }
                            },
                    ),
                )
              }
            }
          }
      testLithoView.lithoView.unmountAllItems()

      assertThat(binds).describedAs("binds for text=%s", text).isEqualTo(1)
      assertThat(unbinds).describedAs("unbinds for text=%s", text).isEqualTo(1)
    }
  }

  private fun roundedWidth(text: CharSequence?, percent: Float, start: Int): Int {
    val testLithoView =
        lithoTestRule.render(widthPx = ROUNDING_ROW_WIDTH_PX, heightPx = 100) {
          object : KComponent() {
            override fun ComponentScope.render(): Component =
                Row(style = Style.width(ROUNDING_ROW_WIDTH_PX.px)) {
                  child(
                      Text(
                          text = text,
                          style =
                              Style.margin(start = start.px)
                                  .widthPercent(percent)
                                  .height(20.px)
                                  .viewTag(ROUNDING_TAG)
                                  .wrapInView(),
                      ),
                  )
                }
          }
        }
    return testLithoView.findViewWithTag(ROUNDING_TAG).width
  }

  private fun legacyRoundedWidth(text: CharSequence?, percent: Float, start: Int): Int {
    val previous = ComponentsConfiguration.usePrimitiveText
    ComponentsConfiguration.usePrimitiveText = false
    try {
      return roundedWidth(text, percent, start)
    } finally {
      ComponentsConfiguration.usePrimitiveText = previous
    }
  }

  // The marker sits after the Text; where it lands shows whether the Text's style survived.
  private fun markerLeft(text: CharSequence?, grow: Boolean = true): Int {
    val textStyle = Style.margin(start = MARGIN_PX.px).let { if (grow) it.flex(grow = 1f) else it }
    val testLithoView =
        lithoTestRule.render(widthPx = ROW_WIDTH_PX, heightPx = 100) {
          object : KComponent() {
            override fun ComponentScope.render(): Component =
                Row(style = Style.width(ROW_WIDTH_PX.px)) {
                  child(Text(text = text, style = textStyle))
                  child(
                      Column(
                          style = Style.width(MARKER_WIDTH_PX.px).height(10.px).viewTag(MARKER_TAG),
                      ),
                  )
                }
          }
        }
    return testLithoView.findViewWithTag(MARKER_TAG).left
  }

  companion object {
    private const val ROW_WIDTH_PX = 500
    private const val MARGIN_PX = 20
    private const val MARKER_WIDTH_PX = 100
    private const val MARKER_TAG = "marker"
    private const val ROUNDING_ROW_WIDTH_PX = 101
    private const val ROUNDING_TAG = "rounding"
    private const val EXPECTED_MARKER_LEFT = ROW_WIDTH_PX - MARKER_WIDTH_PX

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveText={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
