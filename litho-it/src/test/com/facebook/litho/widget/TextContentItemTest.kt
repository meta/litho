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

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import com.facebook.litho.ComponentHost
import com.facebook.litho.LayoutTextContentItem
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.assertj.LithoAssertions
import com.facebook.rendercore.text.RCTextView
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** What a host's text content reports for Litho Text, with legacy and primitive Text. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextContentItemTest(private val usePrimitiveText: Boolean) {

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
  fun `text hidden after mount is not visible text`() {
    val testLithoView = lithoTestRule.render { Column { child(Text(text = LABEL)) } }
    LithoAssertions.assertThat(testLithoView).hasVisibleText(LABEL)
    val textView = findTextView(testLithoView.lithoView) ?: return // Legacy Text has no view.

    for (hidden in listOf(View.INVISIBLE, View.GONE)) {
      textView.visibility = hidden

      LithoAssertions.assertThat(testLithoView).doesNotHaveVisibleText(LABEL)
      LithoAssertions.assertThat(testLithoView).doesNotHaveVisibleTextMatching(LABEL)
    }
  }

  @Test
  fun `span bounds are empty once the text is unmounted`() {
    val testLithoView = renderSpannedText()
    val span = spanItems(testLithoView).single()
    assertThat(span.bounds.isEmpty).isFalse()

    testLithoView.lithoView.unmountAllItems()

    assertThat(span.bounds).isEqualTo(Rect(0, 0, 0, 0))
  }

  @Test
  fun `span ranges index the text the layout draws`() {
    val drawn = SpannableString("word").apply { setSpan(StyleSpan(Typeface.BOLD), 0, 4, 0) }
    val other = SpannableString("PREFIXword").apply { setSpan(StyleSpan(Typeface.BOLD), 6, 10, 0) }
    val layout = layoutOf(drawn)

    val span =
        LayoutTextContentItem(layout, text = other, fullText = other, isExplicitlyTruncated = false)
            .spannables
            .single()

    assertThat(span.text).isEqualTo("word")
    assertThat(span.bounds.isEmpty).isFalse()
  }

  private fun renderSpannedText(): TestLithoView {
    val text =
        SpannableString("$LABEL more").apply {
          setSpan(StyleSpan(Typeface.BOLD), 0, LABEL.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    return lithoTestRule.render { Column { child(Text(text = text)) } }
  }

  private fun spanItems(testLithoView: TestLithoView) =
      hosts(testLithoView.lithoView)
          .flatMap { it.textContent.flatMap { content -> content.items } }
          .flatMap { it.spannables }
          .filter { it.className == StyleSpan::class.java.name }
          .toList()

  private fun hosts(view: View): Sequence<ComponentHost> = sequence {
    if (view is ComponentHost) {
      yield(view)
    }
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        yieldAll(hosts(view.getChildAt(i)))
      }
    }
  }

  private fun findTextView(view: View): RCTextView? {
    if (view is RCTextView) {
      return view
    }
    if (view !is ViewGroup) {
      return null
    }
    return (0 until view.childCount).firstNotNullOfOrNull { findTextView(view.getChildAt(it)) }
  }

  @SuppressLint("NewApi") // Robolectric runs these tests on API 33.
  private fun layoutOf(text: CharSequence): Layout =
      StaticLayout.Builder.obtain(text, 0, text.length, TextPaint().apply { textSize = 40f }, 500)
          .build()

  companion object {
    private const val LABEL = "Hello"

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveText={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
