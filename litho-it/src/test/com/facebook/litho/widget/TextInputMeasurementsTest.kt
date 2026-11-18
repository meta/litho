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

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextUtils
import android.text.method.ArrowKeyMovementMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Tests when [TextInputMeasurements] reuses a size measured under a taller "at most" height. They
 * measure real EditTexts, so they need real font metrics (text wraps as on a device).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextInputMeasurementsTest {

  private val configuration = Configuration()
  private val measurements = TextInputMeasurements()
  private val layoutBehavior = layoutBehaviorFor(measurements)

  @Test
  fun `a reused size matches measuring the EditText again`() {
    val texts = listOf("", "hello", "a\nb\nc", "lorem ipsum dolor ".repeat(40), "x".repeat(400))
    val widths =
        listOf(SizeConstraints(maxWidth = 888), SizeConstraints(minWidth = 414, maxWidth = 414))
    var reused = 0
    for (text in texts) {
      for (width in widths) {
        val measurements = TextInputMeasurements()
        val layoutBehavior = layoutBehaviorFor(measurements)
        val tall = width.withMaxHeight(TALL)
        val measured = measuredSize(text, tall)
        measurements.record(
            behavior = layoutBehavior,
            configuration = configuration,
            sizeConstraints = tall,
            text = text,
            result = measured,
        )

        for (lower in listOf(measured.height - 1, measured.height, measured.height + 1, LOWER)) {
          val cached =
              measurements.sizeFor(
                  behavior = layoutBehavior,
                  configuration = configuration,
                  sizeConstraints = width.withMaxHeight(lower),
                  text = text,
              )
          if (lower < measured.height) {
            assertThat(cached).describedAs("'$text', at most $lower").isNull()
            continue
          }
          val fresh = measuredSize(text, width.withMaxHeight(lower))
          assertThat(cached?.let { it.width to it.height })
              .describedAs("'$text', $tall then at most $lower")
              .isEqualTo(fresh.width to fresh.height)
          reused++
        }
      }
    }
    assertThat(reused).isGreaterThan(0)
  }

  @Test
  fun `a size measured under a taller at most height is reused when it fits`() {
    record(
        sizeConstraints = SizeConstraints(maxWidth = 888, maxHeight = TALL),
        measuredHeight = 101,
    )

    assertThat(sizeFor(SizeConstraints(maxWidth = 888, maxHeight = LOWER))?.height).isEqualTo(101)
  }

  @Test
  fun `a size for other text doesn't hide a reusable size for the current text`() {
    measurements.record(
        behavior = layoutBehavior,
        configuration = configuration,
        sizeConstraints = SizeConstraints(maxWidth = 888, maxHeight = LOWER),
        text = "older text",
        result = PrimitiveLayoutResult(width = 888, height = 170),
    )
    record(
        sizeConstraints = SizeConstraints(maxWidth = 888, maxHeight = TALL),
        measuredHeight = 101,
    )

    assertThat(sizeFor(SizeConstraints(maxWidth = 888, maxHeight = LOWER))?.height).isEqualTo(101)
  }

  @Test
  fun `a size is not reused when it doesn't fit the lower height`() {
    record(
        sizeConstraints = SizeConstraints(maxWidth = 888, maxHeight = TALL),
        measuredHeight = LOWER + 1,
    )

    assertThat(sizeFor(SizeConstraints(maxWidth = 888, maxHeight = LOWER))).isNull()
  }

  @Test
  fun `a size is not reused for another width rule`() {
    record(
        sizeConstraints = SizeConstraints(maxWidth = 888, maxHeight = TALL),
        measuredHeight = 101,
    )

    assertThat(sizeFor(SizeConstraints(maxWidth = 887, maxHeight = LOWER))).isNull()
    assertThat(sizeFor(SizeConstraints(minWidth = 888, maxWidth = 888, maxHeight = LOWER))).isNull()
  }

  @Test
  fun `a size is not reused for a taller limit or an exact height`() {
    record(
        sizeConstraints = SizeConstraints(maxWidth = 888, maxHeight = LOWER),
        measuredHeight = 101,
    )

    assertThat(sizeFor(SizeConstraints(maxWidth = 888, maxHeight = TALL))).isNull()
    assertThat(sizeFor(SizeConstraints(maxWidth = 888, minHeight = 101, maxHeight = 101))).isNull()
  }

  @Test
  fun `a size measured with an unbounded height is not reused for an at most height`() {
    record(sizeConstraints = SizeConstraints(maxWidth = 888), measuredHeight = 101)

    assertThat(sizeFor(SizeConstraints(maxWidth = 888, maxHeight = LOWER))).isNull()
  }

  private fun record(sizeConstraints: SizeConstraints, measuredHeight: Int) {
    measurements.record(
        behavior = layoutBehavior,
        configuration = configuration,
        sizeConstraints = sizeConstraints,
        text = TEXT,
        result = PrimitiveLayoutResult(width = 888, height = measuredHeight),
    )
  }

  private fun sizeFor(sizeConstraints: SizeConstraints): PrimitiveLayoutResult? =
      measurements.sizeFor(
          behavior = layoutBehavior,
          configuration = configuration,
          sizeConstraints = sizeConstraints,
          text = TEXT,
      )

  private fun SizeConstraints.withMaxHeight(maxHeight: Int): SizeConstraints =
      SizeConstraints(minWidth, maxWidth, 0, maxHeight)

  /** Measures [text] the way the primitive does, with every other prop at its default. */
  private fun measuredSize(
      text: CharSequence,
      sizeConstraints: SizeConstraints,
  ): PrimitiveLayoutResult {
    val editText = createAndMeasureEditText(
        context = MeasureContext(ApplicationProvider.getApplicationContext()),
        sizeConstraints = sizeConstraints,
        hint = "",
        inputBackground = UNSET_DRAWABLE,
        shadowRadius = 0f,
        shadowDx = 0f,
        shadowDy = 0f,
        shadowColor = Color.GRAY,
        textColorStateList = ColorStateList.valueOf(Color.BLACK),
        hintColorStateList = ColorStateList.valueOf(Color.LTGRAY),
        highlightColor = null,
        textSize = TextComponentSpec.UNSET,
        typeface = Typeface.DEFAULT,
        textStyle = TextStyle.Normal,
        textAlignment = View.TEXT_ALIGNMENT_GRAVITY,
        gravity = Gravity.CENTER_VERTICAL or Gravity.START,
        editable = true,
        cursorVisible = true,
        inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE,
        rawInputType = EditorInfo.TYPE_NULL,
        keyListener = null,
        imeOptions = EditorInfo.IME_NULL,
        privateImeOptions = null,
        inputFilters = null,
        multiline = true,
        ellipsize = TextUtils.TruncateAt.END,
        minLines = 1,
        maxLines = Int.MAX_VALUE,
        shouldIncludeFontPadding = true,
        lineSpacingMultiplier = 1f,
        cursorDrawableRes = -1,
        error = null,
        errorDrawable = null,
        importantForAutofill = 0,
        autofillHints = null,
        disableAutofill = false,
        textWithoutSpans = text,
        textWithSpans = null,
        useTextWithSpansForMeasure = false,
        isHintOnOneLine = false,
    )
    return PrimitiveLayoutResult(
        width = minOf(sizeConstraints.maxWidth, editText.measuredWidth),
        height = editText.measuredHeight,
    )
  }

  private fun layoutBehaviorFor(
      measurements: TextInputMeasurements,
  ): TextInputLayoutBehavior = TextInputLayoutBehavior(
      hint = "",
      inputBackground = null,
      shadowRadius = 0f,
      shadowDx = 0f,
      shadowDy = 0f,
      shadowColor = Color.GRAY,
      textColorStateList = ColorStateList.valueOf(Color.BLACK),
      hintColorStateList = ColorStateList.valueOf(Color.LTGRAY),
      highlightColor = null,
      textSize = TextComponentSpec.UNSET,
      typeface = Typeface.DEFAULT,
      textStyle = TextStyle.Normal,
      textAlignment = 0,
      gravity = 0,
      editable = true,
      cursorVisible = true,
      inputType = EditorInfo.TYPE_CLASS_TEXT,
      rawInputType = EditorInfo.TYPE_NULL,
      imeOptions = EditorInfo.IME_NULL,
      privateImeOptions = null,
      inputFilters = null,
      multiline = true,
      ellipsize = null,
      minLines = 1,
      maxLines = Int.MAX_VALUE,
      shouldIncludeFontPadding = true,
      lineSpacingMultiplier = 1f,
      cursorDrawableRes = -1,
      error = null,
      errorDrawable = null,
      keyListener = null,
      importantForAutofill = 0,
      autofillHints = null,
      disableAutofill = false,
      movementMethod = ArrowKeyMovementMethod.getInstance(),
      measureSeqNumber = 0,
      savedText = AtomicReference<CharSequence?>(TEXT),
      savedTextWithoutSpans = AtomicReference<CharSequence?>(TEXT),
      savedTextWithSpans = AtomicReference<Spanned?>(null),
      useTextWithSpansForMeasure = false,
      isHintOnOneLine = false,
      measurements = measurements,
  )

  companion object {
    private const val TEXT = "hello"
    private const val TALL = 1887
    private const val LOWER = 1500
  }
}
