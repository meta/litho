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
import android.view.View
import android.view.ViewGroup
import com.facebook.litho.Component
import com.facebook.litho.ComponentHost
import com.facebook.litho.ComponentScope
import com.facebook.litho.DynamicValue
import com.facebook.litho.KComponent
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** `dynamicTextColor` recolors mounted text the same way for legacy and primitive Text. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextDynamicColorParityTest(private val usePrimitiveText: Boolean) {

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
  fun `dynamic text color is applied on mount`() {
    val testLithoView = render(DynamicValue(Color.RED))

    assertThat(mountedTextColor(testLithoView)).isEqualTo(Color.RED)
  }

  @Test
  fun `dynamic text color updates without a new layout`() {
    val color = DynamicValue(Color.RED)
    val testLithoView = render(color)

    color.set(Color.BLUE)

    assertThat(mountedTextColor(testLithoView)).isEqualTo(Color.BLUE)
  }

  @Test
  fun `style color is used without a dynamic color`() {
    val testLithoView = render(dynamicTextColor = null)

    assertThat(mountedTextColor(testLithoView)).isEqualTo(Color.BLACK)
  }

  @Test
  fun `adding and removing a dynamic color on the same text updates it in place`() {
    val testLithoView = render(dynamicTextColor = null)

    testLithoView.setRoot(textWith(DynamicValue(Color.RED)))
    assertThat(mountedTextColor(testLithoView)).isEqualTo(Color.RED)

    testLithoView.setRoot(textWith(dynamicTextColor = null))
    assertThat(mountedTextColor(testLithoView)).isEqualTo(Color.BLACK)
  }

  @Test
  fun `dynamic text color survives a text change that keeps the same value`() {
    val color = DynamicValue(Color.RED)
    val testLithoView = render(color)

    testLithoView.setRoot(textWith(color, text = "Recolored and longer"))

    assertThat(mountedTextColor(testLithoView)).isEqualTo(Color.RED)
  }

  @Test
  fun `setting the dynamic value to null`() {
    val color = DynamicValue<Int?>(Color.RED)
    val testLithoView =
        lithoTestRule.render(widthPx = 500, heightPx = 200) {
          Text.create(context)
              .text("Colored")
              .textColor(Color.BLACK)
              .dynamicTextColor(color)
              .build()
        }

    color.set(null)

    // Legacy keeps the last color; primitive goes back to the style color.
    assertThat(mountedTextColor(testLithoView))
        .isEqualTo(if (usePrimitiveText) Color.BLACK else Color.RED)
  }

  private fun render(dynamicTextColor: DynamicValue<Int>?): TestLithoView =
      lithoTestRule.render(widthPx = 500, heightPx = 200) { textWith(dynamicTextColor) }

  private fun textWith(dynamicTextColor: DynamicValue<Int>?, text: String = "Colored"): Component =
      object : KComponent() {
        override fun ComponentScope.render(): Component =
            Text(text = text, textColor = Color.BLACK, dynamicTextColor = dynamicTextColor)
      }

  private fun mountedTextColor(testLithoView: TestLithoView): Int? =
      reportedTextColors(testLithoView.lithoView).singleOrNull()

  // What tools and tests read through the host's text content, which must be the color drawn.
  private fun reportedTextColors(view: View): List<Int> {
    val colors = mutableListOf<Int>()
    if (view is ComponentHost) {
      view.textContent.flatMap { it.items }.forEach { colors += it.color }
    }
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        colors += reportedTextColors(view.getChildAt(i))
      }
    }
    return colors
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveText={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
