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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextUtils
import android.text.method.ArrowKeyMovementMethod
import android.text.method.DigitsKeyListener
import android.text.method.KeyListener
import android.text.method.LinkMovementMethod
import android.text.method.MovementMethod
import android.text.method.TextKeyListener
import androidx.core.graphics.drawable.toDrawable
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** [TextInputLayoutBehavior.isEquivalentTo] compares every field. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextInputLayoutBehaviorTest {

  private val savedText = AtomicReference<CharSequence?>("hello")
  private val savedTextWithoutSpans = AtomicReference<CharSequence?>("hello")
  private val savedTextWithSpans = AtomicReference<Spanned?>(null)
  // Compared by identity, so shared across behaviors.
  private val errorDrawable: Drawable = Color.RED.toDrawable()
  private val keyListener: KeyListener = TextKeyListener.getInstance()
  private val measurements = TextInputMeasurements()

  @Test
  fun `behaviors built from the same props are equivalent`() {
    assertThat(behavior().isEquivalentTo(behavior())).isTrue()
  }

  @Test
  fun `changing any field makes the behavior not equivalent`() {
    val fields =
        TextInputLayoutBehavior::class.java.declaredFields.filterNot {
          Modifier.isStatic(it.modifiers)
        }
    assertThat(fields).isNotEmpty()

    val ignored = fields.mapNotNull { field ->
      val changed = behavior()
      field.isAccessible = true
      field.set(changed, differentValue(field.type, field.get(changed)))
      if (behavior().isEquivalentTo(changed)) field.name else null
    }

    assertThat(ignored).isEmpty()
  }

  private fun differentValue(type: Class<*>, current: Any?): Any? =
      when {
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType ->
            checkNotNull(current as? Int) + 1
        type == Float::class.javaPrimitiveType -> checkNotNull(current as? Float) + 1f
        type == Boolean::class.javaPrimitiveType -> !checkNotNull(current as? Boolean)
        CharSequence::class.java.isAssignableFrom(type) -> "$current changed"
        Drawable::class.java.isAssignableFrom(type) -> Color.BLUE.toDrawable()
        type == ColorStateList::class.java -> ColorStateList.valueOf(Color.GREEN)
        type == Typeface::class.java -> Typeface.MONOSPACE
        List::class.java.isAssignableFrom(type) -> listOf(InputFilter.LengthFilter(7))
        type == TextUtils.TruncateAt::class.java -> TextUtils.TruncateAt.START
        KeyListener::class.java.isAssignableFrom(type) -> DigitsKeyListener.getInstance("0")
        type.isArray -> arrayOf<String?>("changed")
        MovementMethod::class.java.isAssignableFrom(type) -> LinkMovementMethod.getInstance()
        type == AtomicReference::class.java -> AtomicReference<Any?>(null)
        type == TextInputMeasurements::class.java -> TextInputMeasurements()
        else -> error("Add a different value for $type")
      }

  private fun behavior(): TextInputLayoutBehavior = TextInputLayoutBehavior(
      hint = "hint",
      inputBackground = Color.RED.toDrawable(),
      shadowRadius = 1f,
      shadowDx = 1f,
      shadowDy = 1f,
      shadowColor = Color.GRAY,
      textColorStateList = ColorStateList.valueOf(Color.BLACK),
      hintColorStateList = ColorStateList.valueOf(Color.LTGRAY),
      highlightColor = Color.YELLOW,
      textSize = 40,
      typeface = Typeface.DEFAULT,
      textStyle = TextStyle.Normal,
      textAlignment = 0,
      gravity = 0,
      editable = true,
      cursorVisible = true,
      inputType = 1,
      rawInputType = 0,
      imeOptions = 0,
      privateImeOptions = "options",
      inputFilters = listOf(InputFilter.LengthFilter(5)),
      multiline = false,
      ellipsize = TextUtils.TruncateAt.END,
      minLines = 1,
      maxLines = 2,
      shouldIncludeFontPadding = true,
      lineSpacingMultiplier = 1f,
      cursorDrawableRes = 0,
      error = "error",
      errorDrawable = errorDrawable,
      keyListener = keyListener,
      importantForAutofill = 0,
      autofillHints = arrayOf("hint"),
      disableAutofill = false,
      movementMethod = ArrowKeyMovementMethod.getInstance(),
      measureSeqNumber = 0,
      savedText = savedText,
      savedTextWithoutSpans = savedTextWithoutSpans,
      savedTextWithSpans = savedTextWithSpans,
      useTextWithSpansForMeasure = false,
      isHintOnOneLine = false,
      measurements = measurements,
  )
}
