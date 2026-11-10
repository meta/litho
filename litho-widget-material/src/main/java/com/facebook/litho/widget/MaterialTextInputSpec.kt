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

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.KeyListener
import android.text.method.MovementMethod
import android.view.ActionMode
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.util.ObjectsCompat
import androidx.core.view.ViewCompat
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentLayout
import com.facebook.litho.Diff
import com.facebook.litho.Output
import com.facebook.litho.Size
import com.facebook.litho.StateValue
import com.facebook.litho.annotations.ExcuseMySpec
import com.facebook.litho.annotations.FromTrigger
import com.facebook.litho.annotations.MountSpec
import com.facebook.litho.annotations.OnBind
import com.facebook.litho.annotations.OnCreateInitialState
import com.facebook.litho.annotations.OnCreateMountContent
import com.facebook.litho.annotations.OnLoadStyle
import com.facebook.litho.annotations.OnMeasure
import com.facebook.litho.annotations.OnMount
import com.facebook.litho.annotations.OnTrigger
import com.facebook.litho.annotations.OnUnbind
import com.facebook.litho.annotations.OnUnmount
import com.facebook.litho.annotations.OnUpdateState
import com.facebook.litho.annotations.Prop
import com.facebook.litho.annotations.PropDefault
import com.facebook.litho.annotations.Reason
import com.facebook.litho.annotations.ResType
import com.facebook.litho.annotations.ShouldUpdate
import com.facebook.litho.annotations.State
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.utils.MeasureUtils
import com.facebook.litho.widget.TextInputComponentSpec.EditTextWithEventHandlers
import com.google.android.material.textfield.MountableTextInputLayout
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.atomic.AtomicReference

/**
 * Component that renders an editable text input with a floating label when the hint is hidden while
 * the user inputs text, using an android [TextInput] wrapped in a [TextInputLayout].
 *
 * @see [TextInput] for usage instructions
 */
@ExcuseMySpec(reason = Reason.J2K_CONVERSION)
@MountSpec(
    isPureRender = true,
    events =
        [
            TextChangedEvent::class,
            SelectionChangedEvent::class,
            InputFocusChangedEvent::class,
            KeyUpEvent::class,
            KeyPreImeEvent::class,
            EditorActionEvent::class,
            SetTextEvent::class,
            InputConnectionEvent::class,
            TextPastedEvent::class,
        ],
)
internal object MaterialTextInputSpec {

  private const val UNSET = Int.MIN_VALUE

  @JvmField
  @PropDefault
  val textColorStateList: ColorStateList = TextInputComponentSpec.textColorStateList

  @JvmField
  @PropDefault
  val hintColorStateList: ColorStateList = TextInputComponentSpec.hintColorStateList

  @JvmField @PropDefault val hint: CharSequence = TextInputComponentSpec.hint
  @JvmField @PropDefault val initialText: CharSequence = TextInputComponentSpec.initialText
  @JvmField @PropDefault val shadowColor: Int = TextInputComponentSpec.shadowColor
  @JvmField @PropDefault val textSize: Int = TextInputComponentSpec.textSize
  @JvmField @PropDefault val inputBackground: Drawable = TextInputComponentSpec.inputBackground
  @JvmField @PropDefault val typeface: Typeface = TextInputComponentSpec.typeface
  @JvmField @PropDefault val textStyle: Int = TextInputComponentSpec.textStyle
  @JvmField @PropDefault val textAlignment: Int = TextInputComponentSpec.textAlignment
  @JvmField @PropDefault val gravity: Int = TextInputComponentSpec.gravity
  @JvmField @PropDefault val editable: Boolean = TextInputComponentSpec.editable
  @JvmField @PropDefault val cursorVisible: Boolean = TextInputComponentSpec.cursorVisible
  @JvmField @PropDefault val inputType: Int = TextInputComponentSpec.inputType
  @JvmField @PropDefault val rawInputType: Int = TextInputComponentSpec.rawInputType
  @JvmField @PropDefault val imeOptions: Int = TextInputComponentSpec.imeOptions
  @JvmField @PropDefault val cursorDrawableRes: Int = TextInputComponentSpec.cursorDrawableRes
  @JvmField @PropDefault val multiline: Boolean = TextInputComponentSpec.multiline
  @JvmField @PropDefault val minLines: Int = TextInputComponentSpec.minLines
  @JvmField @PropDefault val maxLines: Int = TextInputComponentSpec.maxLines

  @JvmField @PropDefault val movementMethod: MovementMethod = TextInputComponentSpec.movementMethod

  @JvmField @PropDefault val boxBackgroundMode: Int = TextInputLayout.BOX_BACKGROUND_NONE
  @JvmField @PropDefault val editTextStartPadding: Int = UNSET
  @JvmField @PropDefault val editTextTopPadding: Int = UNSET
  @JvmField @PropDefault val editTextEndPadding: Int = UNSET
  @JvmField @PropDefault val editTextBottomPadding: Int = UNSET
  @JvmField @PropDefault val importantForAutofill: Int = 0
  @JvmField @PropDefault val disableAutofill: Boolean = false
  @JvmField @PropDefault val tooltipText: String = ""

  @JvmStatic
  @OnCreateInitialState
  fun onCreateInitialState(
      c: ComponentContext,
      mountedEditTextRef: StateValue<AtomicReference<EditTextWithEventHandlers?>?>,
      savedText: StateValue<AtomicReference<CharSequence?>?>,
      measureSeqNumber: StateValue<Int?>,
      @Prop(optional = true, resType = ResType.STRING) initialText: CharSequence?,
  ) {
    TextInputComponentSpec.onCreateInitialState(
        mountedEditTextRef,
        savedText,
        measureSeqNumber,
        initialText,
    )
  }

  @JvmStatic
  @OnLoadStyle
  fun onLoadStyle(c: ComponentContext, highlightColor: Output<Int?>) {
    TextInputComponentSpec.onLoadStyle(c, highlightColor)
  }

  @JvmStatic
  @OnMeasure
  fun onMeasure(
      c: ComponentContext,
      layout: ComponentLayout,
      widthSpec: Int,
      heightSpec: Int,
      size: Size,
      @Prop(optional = true, resType = ResType.STRING) hint: CharSequence?,
      @Prop(optional = true, resType = ResType.DRAWABLE) inputBackground: Drawable?,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowRadius: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDx: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDy: Float,
      @Prop(optional = true, resType = ResType.COLOR) shadowColor: Int,
      @Prop(optional = true) textColorStateList: ColorStateList,
      @Prop(optional = true) hintColorStateList: ColorStateList,
      @Prop(optional = true, resType = ResType.COLOR) highlightColor: Int?,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) textSize: Int,
      @Prop(optional = true) typeface: Typeface,
      @Prop(optional = true) textStyle: Int,
      @Prop(optional = true) textAlignment: Int,
      @Prop(optional = true) gravity: Int,
      @Prop(optional = true) editable: Boolean,
      @Prop(optional = true) cursorVisible: Boolean,
      @Prop(optional = true) inputType: Int,
      @Prop(optional = true) rawInputType: Int,
      @Prop(optional = true) imeOptions: Int,
      @Prop(optional = true) privateImeOptions: String?,
      @Prop(optional = true, varArg = "inputFilter")
      inputFilters: List<@JvmSuppressWildcards InputFilter>?,
      @Prop(optional = true) multiline: Boolean,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true) minLines: Int,
      @Prop(optional = true) maxLines: Int,
      @Prop(optional = true) cursorDrawableRes: Int,
      @Prop(optional = true, resType = ResType.STRING) error: CharSequence?,
      @Prop(optional = true, resType = ResType.DRAWABLE) errorDrawable: Drawable?,
      @Prop(optional = true) counterEnabled: Boolean,
      @Prop(optional = true) counterMaxLength: Int,
      @Prop(optional = true) @TextInputLayout.BoxBackgroundMode boxBackgroundMode: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) boxStrokeWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextStartPadding: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextTopPadding: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextEndPadding: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextBottomPadding: Int,
      @Prop(optional = true) importantForAutofill: Int,
      @Prop(optional = true) autofillHints: Array<String?>?,
      @Prop(optional = true) disableAutofill: Boolean,
      @Prop(optional = true) keyListener: KeyListener?,
      @Prop(optional = true) tooltipText: String?,
      @State savedText: AtomicReference<CharSequence?>,
  ) {
    val editText =
        TextInputComponentSpec.createAndMeasureEditText(
            c,
            layout,
            widthSpec,
            heightSpec,
            size,
            null,
            inputBackground,
            shadowRadius,
            shadowDx,
            shadowDy,
            shadowColor,
            textColorStateList,
            hintColorStateList,
            highlightColor,
            textSize,
            typeface,
            textStyle,
            textAlignment,
            gravity,
            editable,
            cursorVisible,
            inputType,
            rawInputType,
            keyListener,
            imeOptions,
            privateImeOptions,
            inputFilters,
            multiline,
            ellipsize,
            minLines,
            maxLines,
            /* shouldIncludeFontPadding */ true,
            /* lineSpacingMultiplier */ 1f,
            cursorDrawableRes,
            error,
            errorDrawable,
            importantForAutofill,
            autofillHints,
            disableAutofill,
            savedText.get(),
        )
    val textInputLayout = MountableTextInputLayout(c.androidContext)
    setParams(
        editText,
        textInputLayout,
        hint,
        hintColorStateList,
        counterEnabled,
        counterMaxLength,
        boxBackgroundMode,
        boxStrokeWidth,
        editTextStartPadding,
        editTextTopPadding,
        editTextEndPadding,
        editTextBottomPadding,
        tooltipText,
    )
    textInputLayout.addView(editText)

    textInputLayout.measure(
        MeasureUtils.getViewMeasureSpec(widthSpec),
        MeasureUtils.getViewMeasureSpec(heightSpec),
    )

    TextInputComponentSpec.setSizeForView(size, widthSpec, heightSpec, textInputLayout)
  }

  @JvmStatic
  @ShouldUpdate
  fun shouldUpdate(
      @Prop(optional = true, resType = ResType.STRING) initialText: Diff<CharSequence?>,
      @Prop(optional = true, resType = ResType.STRING) hint: Diff<CharSequence?>,
      @Prop(optional = true, resType = ResType.DRAWABLE) inputBackground: Diff<Drawable?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowRadius: Diff<Float?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDx: Diff<Float?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDy: Diff<Float?>,
      @Prop(optional = true, resType = ResType.COLOR) shadowColor: Diff<Int?>,
      @Prop(optional = true) textColorStateList: Diff<ColorStateList?>,
      @Prop(optional = true) hintColorStateList: Diff<ColorStateList?>,
      @Prop(optional = true, resType = ResType.COLOR) highlightColor: Diff<Int?>,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) textSize: Diff<Int?>,
      @Prop(optional = true) typeface: Diff<Typeface?>,
      @Prop(optional = true) textStyle: Diff<Int>,
      @Prop(optional = true) textAlignment: Diff<Int?>,
      @Prop(optional = true) gravity: Diff<Int?>,
      @Prop(optional = true) editable: Diff<Boolean?>,
      @Prop(optional = true) cursorVisible: Diff<Boolean?>,
      @Prop(optional = true) inputType: Diff<Int?>,
      @Prop(optional = true) rawInputType: Diff<Int?>,
      @Prop(optional = true) imeOptions: Diff<Int?>,
      @Prop(optional = true) privateImeOptions: Diff<String?>,
      @Prop(optional = true, varArg = "inputFilter") inputFilters: Diff<List<InputFilter?>?>,
      @Prop(optional = true) ellipsize: Diff<TextUtils.TruncateAt?>,
      @Prop(optional = true) multiline: Diff<Boolean>,
      @Prop(optional = true) minLines: Diff<Int?>,
      @Prop(optional = true) maxLines: Diff<Int?>,
      @Prop(optional = true) cursorDrawableRes: Diff<Int?>,
      @Prop(optional = true) movementMethod: Diff<MovementMethod?>,
      @Prop(optional = true, resType = ResType.STRING) error: Diff<CharSequence?>,
      @Prop(optional = true) counterEnabled: Diff<Boolean?>,
      @Prop(optional = true) counterMaxLength: Diff<Int?>,
      @Prop(optional = true) @TextInputLayout.BoxBackgroundMode boxBackgroundMode: Diff<Int?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) boxStrokeWidth: Diff<Int?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextStartPadding: Diff<Int?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextTopPadding: Diff<Int?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextEndPadding: Diff<Int?>,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextBottomPadding: Diff<Int?>,
      @Prop(optional = true) keyListener: Diff<KeyListener?>,
      @Prop(optional = true) shouldExcludeFromIncrementalMount: Diff<Boolean>,
      @Prop(optional = true) tooltipText: Diff<String?>,
      @State measureSeqNumber: Diff<Int?>,
      @State mountedEditTextRef: Diff<AtomicReference<EditTextWithEventHandlers?>>,
      @State savedText: Diff<AtomicReference<CharSequence?>>,
  ): Boolean {
    val shouldUpdateEditText =
        TextInputComponentSpec.shouldUpdate(
            initialText,
            hint,
            inputBackground,
            shadowRadius,
            shadowDx,
            shadowDy,
            shadowColor,
            textColorStateList,
            hintColorStateList,
            highlightColor,
            textSize,
            typeface,
            textStyle,
            textAlignment,
            gravity,
            editable,
            cursorVisible,
            inputType,
            rawInputType,
            imeOptions,
            privateImeOptions,
            inputFilters,
            ellipsize,
            multiline,
            minLines,
            maxLines,
            /* shouldIncludeFontPadding */ Diff(true, true),
            /* lineSpacingMultiplier */ Diff(1f, 1f),
            cursorDrawableRes,
            movementMethod,
            error,
            keyListener,
            shouldExcludeFromIncrementalMount,
            shouldUseMatchParentLayoutParams = Diff(false, false),
            isHintOnOneLine = Diff(false, false),
            measureSeqNumber,
            mountedEditTextRef,
            savedText,
        )
    if (
        shouldUpdateEditText ||
            !ObjectsCompat.equals(counterEnabled.previous, counterEnabled.next) ||
            !ObjectsCompat.equals(counterMaxLength.previous, counterMaxLength.next) ||
            !ObjectsCompat.equals(boxBackgroundMode.previous, boxBackgroundMode.next) ||
            !ObjectsCompat.equals(boxStrokeWidth.previous, boxStrokeWidth.next) ||
            !ObjectsCompat.equals(editTextStartPadding.previous, editTextStartPadding.next) ||
            !ObjectsCompat.equals(editTextTopPadding.previous, editTextTopPadding.next) ||
            !ObjectsCompat.equals(editTextEndPadding.previous, editTextEndPadding.next) ||
            !ObjectsCompat.equals(editTextBottomPadding.previous, editTextBottomPadding.next) ||
            !ObjectsCompat.equals(tooltipText.previous, tooltipText.next)
    ) {
      return true
    }

    return false
  }

  @JvmStatic
  @OnCreateMountContent
  fun onCreateMountContent(c: Context): MountableTextInputLayout {
    val mountableTextInputLayout = MountableTextInputLayout(c)
    val editText = EditTextWithEventHandlers(c)
    mountableTextInputLayout.addView(editText, -1, ViewGroup.LayoutParams.MATCH_PARENT)
    return mountableTextInputLayout
  }

  @JvmStatic
  @OnMount
  fun onMount(
      c: ComponentContext,
      textInputLayout: MountableTextInputLayout,
      @Prop(optional = true, resType = ResType.STRING) hint: CharSequence?,
      @Prop(optional = true, resType = ResType.DRAWABLE) inputBackground: Drawable?,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowRadius: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDx: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDy: Float,
      @Prop(optional = true, resType = ResType.COLOR) shadowColor: Int,
      @Prop(optional = true) textColorStateList: ColorStateList,
      @Prop(optional = true) hintColorStateList: ColorStateList,
      @Prop(optional = true, resType = ResType.COLOR) highlightColor: Int?,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) textSize: Int,
      @Prop(optional = true) typeface: Typeface,
      @Prop(optional = true) textStyle: Int,
      @Prop(optional = true) textAlignment: Int,
      @Prop(optional = true) gravity: Int,
      @Prop(optional = true) editable: Boolean,
      @Prop(optional = true) cursorVisible: Boolean,
      @Prop(optional = true) inputType: Int,
      @Prop(optional = true) rawInputType: Int,
      @Prop(optional = true) imeOptions: Int,
      @Prop(optional = true) privateImeOptions: String?,
      @Prop(optional = true, varArg = "inputFilter")
      inputFilters: List<@JvmSuppressWildcards InputFilter>?,
      @Prop(optional = true) multiline: Boolean,
      @Prop(optional = true) minLines: Int,
      @Prop(optional = true) maxLines: Int,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true) cursorDrawableRes: Int,
      @Prop(optional = true) movementMethod: MovementMethod,
      @Prop(optional = true, resType = ResType.STRING) error: CharSequence?,
      @Prop(optional = true, resType = ResType.DRAWABLE) errorDrawable: Drawable?,
      @Prop(optional = true) counterEnabled: Boolean,
      @Prop(optional = true) counterMaxLength: Int,
      @Prop(optional = true) boxBackgroundMode: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) boxStrokeWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextStartPadding: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextTopPadding: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextEndPadding: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) editTextBottomPadding: Int,
      @Prop(optional = true) keyListener: KeyListener?,
      @Prop(optional = true) importantForAutofill: Int,
      @Prop(optional = true) autofillHints: Array<String?>?,
      @Prop(optional = true) disableAutofill: Boolean,
      @Prop(optional = true) tooltipText: String?,
      @State savedText: AtomicReference<CharSequence?>,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    val editText =
        textInputLayout.editText as? EditTextWithEventHandlers
            ?: error("editText must be EditTextWithEventHandlers")
    mountedEditTextRef.set(editText)

    TextInputComponentSpec.setParams(
        editText,
        null,
        TextInputComponentSpec.getBackgroundOrDefault(c, inputBackground),
        shadowRadius,
        shadowDx,
        shadowDy,
        shadowColor,
        textColorStateList,
        hintColorStateList,
        highlightColor,
        textSize,
        typeface,
        textStyle,
        textAlignment,
        gravity,
        editable,
        cursorVisible,
        inputType,
        rawInputType,
        keyListener,
        imeOptions,
        privateImeOptions,
        inputFilters,
        multiline,
        ellipsize,
        minLines,
        maxLines,
        /* shouldIncludeFontPadding */ true,
        /* lineSpacingMultiplier */ 1f,
        cursorDrawableRes,
        movementMethod,
        // onMount happens:
        // 1. After initState: savedText = initText.
        // 2. After onUnmount: savedText preserved from underlying editText.
        savedText.get(),
        error,
        errorDrawable,
        false,
        importantForAutofill,
        autofillHints,
        c,
        // Pass MaterialTextInput's own handler if fix is enabled
        inputConnectionEventHandler =
            if (ComponentsConfiguration.enableInputConnectionFix) {
              MaterialTextInput.getInputConnectionEventHandler(c)
            } else {
              null
            },
    )
    setParams(
        editText,
        textInputLayout,
        hint,
        hintColorStateList,
        counterEnabled,
        counterMaxLength,
        boxBackgroundMode,
        boxStrokeWidth,
        editTextStartPadding,
        editTextTopPadding,
        editTextEndPadding,
        editTextBottomPadding,
        tooltipText,
    )
    editText.setTextState(savedText)
    editText.setDisableAutofill(disableAutofill)
  }

  private fun setParams(
      editText: EditText,
      textInputLayout: MountableTextInputLayout,
      hint: CharSequence?,
      hintColorStateList: ColorStateList,
      counterEnabled: Boolean,
      counterMaxLength: Int,
      boxBackgroundMode: Int,
      boxStrokeWidth: Int,
      editTextStartPadding: Int,
      editTextTopPadding: Int,
      editTextEndPadding: Int,
      editTextBottomPadding: Int,
      tooltipText: String?,
  ) {
    textInputLayout.hint = hint
    textInputLayout.isCounterEnabled = counterEnabled
    textInputLayout.counterMaxLength = counterMaxLength
    textInputLayout.setDefaultHintTextColor(hintColorStateList)
    // Set box background mode and stroke width in order and edit text padding to fix pre-float
    // vertical center issue
    textInputLayout.boxBackgroundMode = boxBackgroundMode
    textInputLayout.boxStrokeWidth = boxStrokeWidth
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      editText.tooltipText = tooltipText
    }
    if (
        editTextStartPadding != UNSET ||
            editTextTopPadding != UNSET ||
            editTextEndPadding != UNSET ||
            editTextBottomPadding != UNSET
    ) {
      val start =
          if (editTextStartPadding != UNSET) {
            editTextStartPadding
          } else {
            ViewCompat.getPaddingStart(editText)
          }
      val top =
          if (editTextTopPadding != UNSET) {
            editTextTopPadding
          } else {
            editText.paddingTop
          }
      val end =
          if (editTextEndPadding != UNSET) {
            editTextEndPadding
          } else {
            ViewCompat.getPaddingEnd(editText)
          }
      val bottom =
          if (editTextBottomPadding != UNSET) {
            editTextBottomPadding
          } else {
            editText.bottom
          }
      editText.setPadding(start, top, end, bottom)
    }
  }

  @JvmStatic
  @OnBind
  fun onBind(
      c: ComponentContext,
      textInputLayout: MountableTextInputLayout,
      @Prop(optional = true, varArg = "textWatcher") textWatchers: List<TextWatcher>?,
      @Prop(optional = true) selectionActionModeCallback: ActionMode.Callback?,
      @Prop(optional = true) insertionActionModeCallback: ActionMode.Callback?,
  ) {
    val editText =
        textInputLayout.editText as? EditTextWithEventHandlers
            ?: error("editText must be EditTextWithEventHandlers")
    TextInputComponentSpec.onBindEditText(
        c,
        editText,
        textWatchers,
        selectionActionModeCallback,
        insertionActionModeCallback,
        MaterialTextInput.getTextChangedEventHandler(c),
        MaterialTextInput.getSelectionChangedEventHandler(c),
        MaterialTextInput.getInputFocusChangedEventHandler(c),
        MaterialTextInput.getKeyUpEventHandler(c),
        MaterialTextInput.getKeyPreImeEventHandler(c),
        MaterialTextInput.getEditorActionEventHandler(c),
        MaterialTextInput.getInputConnectionEventHandler(c),
        MaterialTextInput.getTextPastedEventHandler(c),
    )
  }

  @JvmStatic
  @OnUnmount
  fun onUnmount(
      c: ComponentContext,
      textInputLayout: MountableTextInputLayout,
      @Prop(optional = true) keyListener: KeyListener?,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    val editText =
        textInputLayout.editText as? EditTextWithEventHandlers
            ?: error("editText must be EditTextWithEventHandlers")
    TextInputComponentSpec.onUnmount(
        c,
        editText,
        keyListener,
        shouldUseMatchParentLayoutParams = false,
        mountedEditTextRef,
    )
  }

  @JvmStatic
  @OnUnbind
  fun onUnbind(c: ComponentContext, textInputLayout: MountableTextInputLayout) {
    val editText =
        textInputLayout.editText as? EditTextWithEventHandlers
            ?: error("editText must be EditTextWithEventHandlers")
    TextInputComponentSpec.onUnbind(c, editText)
  }

  @JvmStatic
  @OnTrigger(RequestFocusEvent::class)
  fun requestFocus(
      c: ComponentContext,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    TextInputComponentSpec.requestFocus(c, mountedEditTextRef)
  }

  @JvmStatic
  @OnTrigger(ClearFocusEvent::class)
  fun clearFocus(
      c: ComponentContext,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    TextInputComponentSpec.clearFocus(c, mountedEditTextRef)
  }

  @JvmStatic
  @OnTrigger(GetTextEvent::class)
  fun getText(
      c: ComponentContext,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
      @State savedText: AtomicReference<CharSequence?>,
  ): CharSequence? {
    return TextInputComponentSpec.getText(c, mountedEditTextRef, savedText)
  }

  @JvmStatic
  @OnTrigger(SetTextEvent::class)
  fun setText(
      c: ComponentContext,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
      @State savedText: AtomicReference<CharSequence?>,
      @FromTrigger text: CharSequence?,
  ) {
    val shouldRemeasure =
        TextInputComponentSpec.setTextEditText(mountedEditTextRef, savedText, text)
    if (shouldRemeasure) {
      MaterialTextInput.remeasureForUpdatedTextSync(c)
    }
  }

  @JvmStatic
  @OnTrigger(DispatchKeyEvent::class)
  fun dispatchKey(
      c: ComponentContext,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
      @FromTrigger keyEvent: KeyEvent?,
  ) {
    TextInputComponentSpec.dispatchKey(c, mountedEditTextRef, keyEvent)
  }

  @JvmStatic
  @OnTrigger(SetSelectionEvent::class)
  fun setSelection(
      c: ComponentContext,
      @State mountedEditTextRef: AtomicReference<EditTextWithEventHandlers?>,
      @FromTrigger start: Int,
      @FromTrigger end: Int,
  ) {
    TextInputComponentSpec.setSelection(c, mountedEditTextRef, start, end)
  }

  @JvmStatic
  @OnUpdateState
  fun remeasureForUpdatedText(measureSeqNumber: StateValue<Int>) {
    val current = requireNotNull(measureSeqNumber.get()) { "measureSeqNumber must be initialized" }
    measureSeqNumber.set(current + 1)
  }
}
