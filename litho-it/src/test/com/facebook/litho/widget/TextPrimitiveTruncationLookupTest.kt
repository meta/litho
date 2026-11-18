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

import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import android.view.View
import android.view.ViewGroup
import com.facebook.litho.LithoView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.rendercore.RenderCoreConfig
import com.facebook.rendercore.text.RCTextView
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * With FB4A's flags (legacy Text's custom-ellipsis offset fix off), primitive Text finds where a
 * custom ellipsis goes with `Layout.getOffsetForHorizontal`, which re-walks the caption's spans and
 * froze the main thread on long captions.
 * [ComponentsConfiguration.primitiveTextPerformantTruncation] switches to
 * `Paint.getOffsetForAdvance`, like legacy Text, and steps back until the suffix fits.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextPrimitiveTruncationLookupTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveText = false
  private var previousEnableFix = false
  private var previousPerformantTruncation = false
  private var previousRenderCorePerformantTruncation = false

  @Before
  fun setUp() {
    RuntimeEnvironment.setQualifiers(TextPrimitiveParityTest.DENSITY_QUALIFIER)
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    previousEnableFix = ComponentsConfiguration.enableFixForTextEllipsisOffset
    previousPerformantTruncation = ComponentsConfiguration.primitiveTextPerformantTruncation
    previousRenderCorePerformantTruncation = RenderCoreConfig.usePerformantTruncation
    ComponentsConfiguration.enableFixForTextEllipsisOffset = false
    RenderCoreConfig.usePerformantTruncation = false
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
    ComponentsConfiguration.enableFixForTextEllipsisOffset = previousEnableFix
    ComponentsConfiguration.primitiveTextPerformantTruncation = previousPerformantTruncation
    RenderCoreConfig.usePerformantTruncation = previousRenderCorePerformantTruncation
  }

  @Test
  fun `cutting a long styled caption for See more doesn't re-walk its styles`() {
    ComponentsConfiguration.primitiveTextPerformantTruncation = true
    val style = CountingSpan()

    renderPrimitive(styled(LONG_CAPTION, style))

    assertThat(style.drawStateUpdates).isZero()
  }

  @Test
  fun `with the flag off the caption's styles are re-walked to find the cut`() {
    ComponentsConfiguration.primitiveTextPerformantTruncation = false
    val style = CountingSpan()

    renderPrimitive(styled(LONG_CAPTION, style))

    assertThat(style.drawStateUpdates).isPositive()
  }

  @Test
  fun `left to right captions are cut exactly like legacy Text and today's primitive Text`() {
    val broken = WIDTHS_PX.mapNotNull { width ->
      val legacy = renderLegacy(LTR_CAPTION, width)
      ComponentsConfiguration.primitiveTextPerformantTruncation = false
      val horizontal = renderPrimitive(LTR_CAPTION, width).text.toString()
      ComponentsConfiguration.primitiveTextPerformantTruncation = true
      val fast = renderPrimitive(LTR_CAPTION, width).text.toString()
      if (fast != legacy || fast != horizontal) {
        "@${width}px: legacy '$legacy', horizontal '$horizontal', fast '$fast'"
      } else {
        null
      }
    }

    assertThat(broken).isEmpty()
  }

  @Test
  fun `right to left captions keep as much text as today's primitive Text`() {
    val broken = WIDTHS_PX.mapNotNull { width ->
      ComponentsConfiguration.primitiveTextPerformantTruncation = false
      val horizontal = renderPrimitive(RTL_CAPTION, width).text.length
      ComponentsConfiguration.primitiveTextPerformantTruncation = true
      val fast = renderPrimitive(RTL_CAPTION, width).text.length
      if (fast < horizontal) "@${width}px: horizontal keeps $horizontal, fast $fast" else null
    }

    assertThat(broken).isEmpty()
  }

  @Test
  fun `the See more suffix is shown whole at every width`() {
    ComponentsConfiguration.primitiveTextPerformantTruncation = true

    val broken = CAPTIONS.flatMap { (name, caption) ->
      WIDTHS_PX.mapNotNull { width ->
        val shown = renderPrimitive(caption, width)
        val androidEllipsized = (0 until shown.lineCount).any { shown.getEllipsisCount(it) > 0 }
        if (!shown.text.endsWith(SEE_MORE) || androidEllipsized) {
          "$name @${width}px: '${shown.text}'"
        } else {
          null
        }
      }
    }

    assertThat(broken).isEmpty()
  }

  private fun renderPrimitive(caption: CharSequence, widthPx: Int = 900): Layout {
    ComponentsConfiguration.usePrimitiveText = true
    return checkNotNull(findRCTextView(render(caption, widthPx))?.layout)
  }

  private fun renderLegacy(caption: CharSequence, widthPx: Int): String {
    ComponentsConfiguration.usePrimitiveText = false
    val textDrawable = render(caption, widthPx).drawables.filterIsInstance<TextDrawable>().first()
    return checkNotNull(textDrawable.layout).text.toString()
  }

  @Suppress("DEPRECATION")
  private fun render(caption: CharSequence, widthPx: Int): LithoView =
      lithoTestRule
          .render(
              widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
              heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
          ) {
            Text.create(context)
                .text(caption)
                .textSizePx(42)
                .maxLines(4)
                .ellipsize(TextUtils.TruncateAt.END)
                .customEllipsisText(SEE_MORE)
                .build()
          }
          .lithoView

  private fun findRCTextView(view: View): RCTextView? {
    if (view is RCTextView) {
      return view
    }
    if (view !is ViewGroup) {
      return null
    }
    return (0 until view.childCount).firstNotNullOfOrNull { findRCTextView(view.getChildAt(it)) }
  }

  private fun styled(text: String, span: Any): CharSequence =
      SpannableString(text).apply { setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

  /** A no-op style that counts how often text measurement applies it to a paint. */
  private class CountingSpan : MetricAffectingSpan() {
    var drawStateUpdates = 0

    override fun updateMeasureState(textPaint: TextPaint) = Unit

    override fun updateDrawState(textPaint: TextPaint) {
      drawStateUpdates++
    }
  }

  companion object {
    private const val SEE_MORE = "\u2026 See more"
    private val LONG_CAPTION = "Had the best weekend at the lake with everyone. ".repeat(200)
    private val LTR_CAPTION = "Had the best weekend at the lake with everyone. ".repeat(20)
    private val RTL_CAPTION =
        ("\u05D8\u05E7\u05E1\u05D8 \u05D1\u05E2\u05D1\u05E8\u05D9\u05EA " +
                "\u05E9\u05DE\u05DE\u05E9\u05D9\u05DA \u05DC\u05E9\u05D5\u05E8\u05D5\u05EA ")
            .repeat(20)
    private val WIDTHS_PX = listOf(320, 540, 720, 900, 1080)
    private val CAPTIONS: List<Pair<String, String>> =
        listOf("ltr" to LTR_CAPTION, "rtl" to RTL_CAPTION)
  }
}
