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

package com.facebook.litho.kotlin.widget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.InputFilter
import android.text.TextUtils.TruncateAt
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.ColorInt
import com.facebook.litho.Handle
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.eventHandler
import com.facebook.litho.eventHandlerWithReturn
import com.facebook.litho.kotlinStyle
import com.facebook.litho.widget.EditorActionEvent
import com.facebook.litho.widget.InputConnectionEvent
import com.facebook.litho.widget.InputFocusChangedEvent
import com.facebook.litho.widget.KeyPreImeEvent
import com.facebook.litho.widget.KeyUpEvent
import com.facebook.litho.widget.SelectionChangedEvent
import com.facebook.litho.widget.TextChangedEvent
import com.facebook.litho.widget.TextInput
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.sp

/** Builder function for creating [TextInputSpec] components. */
@Suppress("FunctionName")
fun ResourcesScope.TextInput(
    initialText: CharSequence,
    style: Style = Style,
    hint: CharSequence = "",
    @ColorInt textColor: Int = Color.BLACK,
    @ColorInt hintTextColor: Int = Color.LTGRAY,
    textSize: Dimen = 14.sp,
    typeface: Typeface = Typeface.DEFAULT,
    textAlignment: Int = View.TEXT_ALIGNMENT_GRAVITY,
    gravity: Int = Gravity.CENTER_VERTICAL or Gravity.START,
    editable: Boolean = true,
    multiline: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    includeFontPadding: Boolean = true,
    lineSpacingMultiplier: Float = 1f,
    inputType: Int = EditorInfo.TYPE_CLASS_TEXT,
    rawInputType: Int = EditorInfo.TYPE_NULL,
    imeOptions: Int = EditorInfo.IME_NULL,
    inputFilter: InputFilter? = null,
    inputFilters: List<InputFilter>? = null,
    ellipsize: TruncateAt? = null,
    /** Called when the text entered by the user changes. */
    onTextChanged: ((TextChangedEvent) -> Unit)? = null,
    /** Called when the selection (particular case: cursor position) gets changed by user. */
    onSelectionChanged: ((SelectionChangedEvent) -> Unit)? = null,
    /** Called when the input focus changed by user. */
    onInputFocusChanged: ((InputFocusChangedEvent) -> Unit)? = null,
    /** Called when underlying [android.widget.EditText#onKeyUp()] happens. */
    onKeyUp: ((KeyUpEvent) -> Boolean)? = null,
    /** Called when underlying [android.widget.EditText#onKeyPreIme()] happens. */
    onKeyPreIme: ((KeyPreImeEvent) -> Boolean)? = null,
    /** Called when the return key is pressed or the IME signals an 'action'. */
    onEditorAction: ((EditorActionEvent) -> Boolean)? = null,
    /**
     * Called when underlying
     * [android.widget.EditText#onCreateInputConnection(EditorInfo editorInfo)] happens.
     */
    onInputConnection: ((InputConnectionEvent) -> InputConnection)? = null,
    handle: Handle? = null,
    inputBackground: Drawable? = null,
    textWatcher: TextWatcher? = null,
    shouldExcludeFromIncrementalMount: Boolean = false,
): TextInput =
    TextInput.create(context)
        .inputFilters(inputFilters)
        .initialText(initialText)
        .hint(hint)
        .textColorStateList(ColorStateList.valueOf(textColor))
        .hintColorStateList(ColorStateList.valueOf(hintTextColor))
        .textSizePx(textSize.toPixels())
        .typeface(typeface)
        .textAlignment(textAlignment)
        .gravity(gravity)
        .editable(editable)
        .multiline(multiline)
        .minLines(minLines)
        .maxLines(maxLines)
        .shouldIncludeFontPadding(includeFontPadding)
        .lineSpacingMultiplier(lineSpacingMultiplier)
        .inputType(inputType)
        .rawInputType(rawInputType)
        .imeOptions(imeOptions)
        .inputFilter(inputFilter)
        .inputBackground(inputBackground)
        .textWatcher(textWatcher)
        .handle(handle)
        .kotlinStyle(style)
        .shouldExcludeFromIncrementalMount(shouldExcludeFromIncrementalMount)
        .apply {
          onTextChanged?.let { textChangedEventHandler(eventHandler(it)) }
          onSelectionChanged?.let { selectionChangedEventHandler(eventHandler(it)) }
          onInputFocusChanged?.let { inputFocusChangedEventHandler(eventHandler(it)) }
          onKeyUp?.let { keyUpEventHandler(eventHandlerWithReturn(it)) }
          onKeyPreIme?.let { keyPreImeEventHandler(eventHandlerWithReturn(it)) }
          onEditorAction?.let { editorActionEventHandler(eventHandlerWithReturn(it)) }
          onInputConnection?.let { inputConnectionEventHandler(eventHandlerWithReturn(it)) }
          ellipsize?.let { ellipsize(it) }
        }
        .build()
