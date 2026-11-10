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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.drawable.toDrawable
import androidx.test.core.app.ApplicationProvider
import com.facebook.rendercore.SizeConstraints
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An EditText measured to an exact width and height takes that size whatever it shows, which is why
 * [TextInputLayoutBehavior] answers an exact size without measuring one.
 */
@RunWith(RobolectricTestRunner::class)
class ExperimentalTextInputExactSizeTest {

  @Test
  fun `an EditText measured to an exact size takes that size whatever its props`() {
    val cases = mapOf(
        "empty" to Input(),
        "more lines than fit" to Input(text = "a\nb\nc\nd\ne\nf", multiline = true),
        "fewer lines than its minimum" to Input(text = "a", multiline = true, minLines = 6),
        "a line wider than it" to Input(text = "x".repeat(400)),
        "a padded background" to Input(background = PaddedDrawable(padding = 60)),
        "the themed background" to Input(background = UNSET_DRAWABLE),
        "an error icon" to Input(error = "Invalid", errorDrawable = Color.RED.toDrawable()),
        "a long hint on one line" to Input(hint = "hint ".repeat(100), isHintOnOneLine = true),
        "a length filter" to
            Input(text = "z".repeat(50), filters = listOf(InputFilter.LengthFilter(5))),
    )

    for ((name, input) in cases) {
      for ((width, height) in EXACT_SIZES) {
        assertThat(measuredSize(input, width, height))
            .describedAs("$name, measured exactly $width x $height")
            .isEqualTo(width to height)
      }
    }
  }

  /** Measures [input] the way the primitive does, with every prop it doesn't set at its default. */
  private fun measuredSize(input: Input, width: Int, height: Int): Pair<Int, Int> {
    val editText = createAndMeasureEditText(
        context = MeasureContext(ApplicationProvider.getApplicationContext()),
        sizeConstraints = SizeConstraints.exact(width, height),
        hint = input.hint,
        inputBackground = input.background,
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
        inputType = EditorInfo.TYPE_CLASS_TEXT,
        rawInputType = EditorInfo.TYPE_NULL,
        keyListener = null,
        imeOptions = EditorInfo.IME_NULL,
        privateImeOptions = null,
        inputFilters = input.filters,
        multiline = input.multiline,
        ellipsize = TextUtils.TruncateAt.END,
        minLines = input.minLines,
        maxLines = Int.MAX_VALUE,
        shouldIncludeFontPadding = true,
        lineSpacingMultiplier = 1f,
        cursorDrawableRes = -1,
        error = input.error,
        errorDrawable = input.errorDrawable,
        importantForAutofill = 0,
        autofillHints = null,
        disableAutofill = false,
        textWithoutSpans = input.text,
        textWithSpans = null,
        useTextWithSpansForMeasure = false,
        isHintOnOneLine = input.isHintOnOneLine,
    )
    return editText.measuredWidth to editText.measuredHeight
  }

  /** The props a case sets; everything else is the TextInput default. */
  private class Input(
      val text: CharSequence = "",
      val hint: CharSequence = "",
      val background: Drawable? = null,
      val multiline: Boolean = false,
      val minLines: Int = 1,
      val error: CharSequence? = null,
      val errorDrawable: Drawable? = null,
      val filters: List<InputFilter>? = null,
      val isHintOnOneLine: Boolean = false,
  )

  private class PaddedDrawable(private val padding: Int) : ColorDrawable(Color.TRANSPARENT) {
    override fun getPadding(padding: Rect): Boolean {
      padding.set(this.padding, this.padding, this.padding, this.padding)
      return true
    }
  }

  companion object {
    private val EXACT_SIZES = listOf(240 to 84, 720 to 600, 1 to 1, 0 to 0)
  }
}
