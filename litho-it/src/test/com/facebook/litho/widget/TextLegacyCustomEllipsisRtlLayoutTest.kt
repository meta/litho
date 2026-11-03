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
import android.text.TextUtils
import android.view.View
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.yoga.YogaDirection
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
 * Legacy Text ([TextComponentSpec]) in an RTL layout keeps a custom ellipsis whole at the end of a
 * filled last visible line with [ComponentsConfiguration.fitCustomEllipsisOnTruncatedLine] off and
 * either state of [ComponentsConfiguration.fixRtlCustomEllipsisTruncation].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextLegacyCustomEllipsisRtlLayoutTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveText = false
  private var previousFixRtl = false
  private var previousOffsetFix = false
  private var previousFit = false

  @Before
  fun setUp() {
    RuntimeEnvironment.setQualifiers(TextPrimitiveParityTest.DENSITY_QUALIFIER)
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    previousFixRtl = ComponentsConfiguration.fixRtlCustomEllipsisTruncation
    previousOffsetFix = ComponentsConfiguration.enableFixForTextEllipsisOffset
    previousFit = ComponentsConfiguration.fitCustomEllipsisOnTruncatedLine
    ComponentsConfiguration.usePrimitiveText = false
    ComponentsConfiguration.fixRtlCustomEllipsisTruncation = false
    ComponentsConfiguration.enableFixForTextEllipsisOffset = true
    ComponentsConfiguration.fitCustomEllipsisOnTruncatedLine = false
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
    ComponentsConfiguration.fixRtlCustomEllipsisTruncation = previousFixRtl
    ComponentsConfiguration.enableFixForTextEllipsisOffset = previousOffsetFix
    ComponentsConfiguration.fitCustomEllipsisOnTruncatedLine = previousFit
  }

  @Test
  fun `the custom ellipsis ends a filled last visible line at every width`() {
    val broken =
        listOf(false, true).flatMap { fixRtl ->
          ComponentsConfiguration.fixRtlCustomEllipsisTruncation = fixRtl
          TEXTS.flatMap { (name, text) ->
            ALIGNMENTS.flatMap { alignment ->
              MAX_LINES.flatMap { maxLines ->
                WIDTHS_PX.mapNotNull { width ->
                  val shown = render(text, width, alignment, maxLines)
                  val shownText = shown.text.toString()
                  val lastLineWidth = shown.getLineWidth(shown.lineCount - 1)
                  val minLastLineWidth = width - shown.paint.measureText(SUFFIX)
                  if (
                      shownText != text &&
                          (shown.lineCount > maxLines ||
                              !shownText.endsWith(SUFFIX) ||
                              lastLineWidth < minLastLineWidth)
                  ) {
                    "fixRtl=$fixRtl $name $alignment maxLines=$maxLines @${width}px: " +
                        "lines=${shown.lineCount} lastLineWidth=$lastLineWidth " +
                        "min=$minLastLineWidth \"${shownText.takeLast(30)}\""
                  } else {
                    null
                  }
                }
              }
            }
          }
        }
    assertThat(broken).isEmpty()
  }

  private fun render(
      text: CharSequence,
      widthPx: Int,
      alignment: TextAlignment,
      maxLines: Int,
  ): Layout {
    val lithoView =
        lithoTestRule
            .render(
                widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            ) {
              Text.create(context)
                  .text(text)
                  .maxLines(maxLines)
                  .ellipsize(TextUtils.TruncateAt.END)
                  .customEllipsisText(SUFFIX)
                  .alignment(alignment)
                  .layoutDirection(YogaDirection.RTL)
                  .build()
            }
            .lithoView
    return checkNotNull(lithoView.drawables.filterIsInstance<TextDrawable>().first().layout)
  }

  companion object {
    private const val SUFFIX = "See more"
    private val MAX_LINES = listOf(1, 3)
    private val WIDTHS_PX = (300..1080 step 37).toList()
    private val ALIGNMENTS = listOf(TextAlignment.LAYOUT_START, TextAlignment.TEXT_START)

    private const val LTR_TEXT =
        "Remove item from list. You will no longer see this item or receive updates about it " +
            "in your feed or notifications. Lorem ipsum dolor sit amet, consectetur adipiscing " +
            "elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
    private const val HEBREW_TEXT =
        "טקסט בעברית שממשיך לשורות רבות מאוד בכל הרוחב טקסט בעברית שממשיך לשורות רבות מאוד " +
            "בכל הרוחב טקסט בעברית שממשיך לשורות רבות מאוד בכל הרוחב"

    private val TEXTS: List<Pair<String, String>> =
        listOf("ltr" to LTR_TEXT, "hebrew" to HEBREW_TEXT)
  }
}
