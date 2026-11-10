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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.method.KeyListener
import android.text.method.MovementMethod
import android.text.style.SuggestionRangeSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.util.ObjectsCompat
import androidx.core.view.ViewCompat
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentLayout
import com.facebook.litho.Diff
import com.facebook.litho.EventHandler
import com.facebook.litho.Output
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec
import com.facebook.litho.SizeSpec.getMode
import com.facebook.litho.SizeSpec.getSize
import com.facebook.litho.StateValue
import com.facebook.litho.ThreadUtils.assertMainThread
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
import com.facebook.litho.annotations.ShouldExcludeFromIncrementalMount
import com.facebook.litho.annotations.ShouldUpdate
import com.facebook.litho.annotations.State
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.utils.MeasureUtils.getViewMeasureSpec
import com.facebook.rendercore.LogLevel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Component that renders an editable text input using an android [EditText]. It is measured based
 * on the input text [String] representation.
 *
 * Performance is critical for good user experience. Follow these tips for good performance:
 * * Avoid changing props at all costs as it forces expensive EditText reconfiguration.
 * * Avoid updating state, use Event trigger [OnTrigger] to update text, request view focus or set
 *   selection. `TextInputComponent.setText(c, key, text)`.
 * * Using custom inputFilters take special care to implement equals correctly or the text field
 *   must be reconfigured on every mount. (Better yet, store your InputFilter in a static or
 *   LruCache so that you're not constantly creating new instances.)
 *
 * Because this component is backed by android [EditText] many native capabilities are applicable:
 * * Use [InputFilter] to set a text length limit or modify text input.
 * * Remove android EditText underline by removing background.
 * * Change the input representation by passing one of the [android.text.InputType] constants.
 *
 * It is also treated by the system as an android [EditText]:
 * * When [EditText] receives focus, a system keyboard is shown.
 * * When the user opens the screen and android [EditText] is the first element in the View
 *   hierarchy, it gains focus.
 *
 * Example of multiline editable text with custom text color, text length limit, removed underline
 * drawable, and sentence capitalisation:
 * ```
 * private static final InputFilter lenFilter = new InputFilter.LengthFilter(maxLength);
 *
 * TextInputComponent.create(c)
 *   .initialText(text)
 *   .textColorStateList(ColorStateList.valueOf(color))
 *   .multiline(true)
 *   .inputFilter(lenFilter)
 *   .backgroundColor(Color.TRANSPARENT)
 *   .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
 *   .build();
 * ```
 *
 * @see [EditText]
 * @prop initialText Initial text to display. If set, the value is set on the EditText exactly once:
 *   on initial mount. From then on, the EditText's text property is not modified.
 * @prop hint Hint text to display.
 * @prop inputBackground The background of the EditText itself; this is subtly distinct from the
 *   Litho background prop. The padding of the inputBackground drawable will be applied to the
 *   EditText itself, insetting the cursor and text field.
 * @prop shadowRadius Blur radius of the shadow.
 * @prop shadowDx Horizontal offset of the shadow.
 * @prop shadowDy Vertical offset of the shadow.
 * @prop shadowColor Color for the shadow underneath the text.
 * @prop cursorDrawableRes Drawable to set as an edit text cursor.
 * @prop textColorStateList ColorStateList of the text.
 * @prop hintTextColorStateList ColorStateList of the hint text.
 * @prop highlightColor Color for selected text.
 * @prop textSize Size of the text.
 * @prop typeface Typeface for the text.
 * @prop textStyle Style (bold, italic, bolditalic) for the text.
 * @prop textAlignment Alignment of the text within its container. This only has effect on API level
 *   17 and above; it's up to you to handle earlier API levels by adjusting gravity.
 * @prop gravity Gravity for the text within its container.
 * @prop editable If set, allows the text to be editable.
 * @prop cursorVisible Set whether the cursor is visible. The default is true.
 * @prop inputType Type of data being placed in a text field, used to help an input method decide
 *   how to let the user enter text. To add multiline use multiline(true) method.
 * @prop rawInputType Type of data being placed in a text field. Directly changes the content type
 *   integer of the text view, without modifying any other state. This prop will override inputType
 *   if both are provided.
 * @prop imeOptions Type of data in the text field, reported to an IME when it has focus.
 * @prop disableAutofill If set to true, disables autofill for this text input by setting the
 *   underlying [EditText]'s autofillType to [android.view.View#AUTOFILL_TYPE_NONE].
 * @prop inputFilters Used to filter the input to e.g. a max character count.
 * @prop multiline If set to true, type of the input will be changed to multiline TEXT. Because
 *   passwords or numbers couldn't be multiline by definition.
 * @prop ellipsize If set, specifies the position of the text to be ellipsized. See
 *   [android documentation](https://developer.android.com/reference/android/widget/TextView.html#setEllipsize(android.text.TextUtils.TruncateAt))
 *   for behavior description.
 * @prop minLines Minimum number of lines to show.
 * @prop maxLines Maximum number of lines to show.
 * @prop shouldIncludeFontPadding If set to false, removes the extra font metric padding above the
 *   first line and below the last line. With a [maxLines] cap the asymmetry between that top and
 *   bottom padding leaves the scroll offset off a line boundary, which renders a partially clipped
 *   row of text; setting this to false keeps the visible viewport an exact multiple of the line
 *   height. Defaults to true, matching the platform [android.widget.TextView] default.
 * @prop lineSpacingMultiplier Line spacing multiplier, see
 *   [android.widget.TextView.setLineSpacing]. Defaults to 1, the platform default.
 * @prop textWatchers Used to register text watchers e.g. mentions detection.
 * @prop movementMethod Used to set cursor positioning, scrolling and text selection functionality
 *   in EditText
 * @prop error Sets the right-hand compound drawable of the TextView to the "error" icon and sets an
 *   error message that will be displayed in a popup when the TextView has focus. The icon and error
 *   message will be reset to null when any key events cause changes to the TextView's text. If the
 *   error is null, the error message and icon will be cleared. See
 *   https://developer.android.com/reference/android/widget/TextView.html#setError for more details.
 * @prop errorDrawable Will show along with the error message when a message is set.
 * @prop shouldUseMatchParentLayoutParams See [TextInputSpec].
 * @prop isHintOnOneLine See [TextInputSpec].
 */
@ExcuseMySpec(reason = Reason.J2K_CONVERSION)
@Deprecated("Use ExperimentalTextInput instead")
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
internal object TextInputComponentSpec {
  /**
   * Dummy drawable used for differentiating user-provided null background drawable from default
   * drawable of the spec
   */
  @JvmField val UNSET_DRAWABLE: Drawable = ColorDrawable(Color.TRANSPARENT)

  @JvmField
  @PropDefault
  val textColorStateList: ColorStateList = ColorStateList.valueOf(Color.BLACK)

  @JvmField
  @PropDefault
  val hintColorStateList: ColorStateList = ColorStateList.valueOf(Color.LTGRAY)

  @JvmField @PropDefault val hint: CharSequence = ""
  @JvmField @PropDefault val initialText: CharSequence = ""
  @JvmField @PropDefault val shadowColor: Int = Color.GRAY
  @JvmField @PropDefault val textSize: Int = TextComponentSpec.UNSET
  @JvmField @PropDefault val inputBackground: Drawable = UNSET_DRAWABLE
  @JvmField @PropDefault val typeface: Typeface = Typeface.DEFAULT
  @JvmField @PropDefault val textStyle: Int = Typeface.NORMAL
  @JvmField @PropDefault val textAlignment: Int = View.TEXT_ALIGNMENT_GRAVITY
  @JvmField @PropDefault val gravity: Int = Gravity.CENTER_VERTICAL or Gravity.START
  @JvmField @PropDefault val editable: Boolean = true
  @JvmField @PropDefault val cursorVisible: Boolean = true
  @JvmField @PropDefault val inputType: Int = EditorInfo.TYPE_CLASS_TEXT
  @JvmField @PropDefault val rawInputType: Int = EditorInfo.TYPE_NULL
  @JvmField @PropDefault val imeOptions: Int = EditorInfo.IME_NULL
  @JvmField @PropDefault val cursorDrawableRes: Int = -1
  @JvmField @PropDefault val multiline: Boolean = false
  @JvmField @PropDefault val minLines: Int = 1
  @JvmField @PropDefault val maxLines: Int = Int.MAX_VALUE
  @JvmField @PropDefault val shouldIncludeFontPadding: Boolean = true
  @JvmField @PropDefault val lineSpacingMultiplier: Float = 1f
  @JvmField @PropDefault val importantForAutofill: Int = 0
  @JvmField @PropDefault val disableAutofill: Boolean = false
  @JvmField @PropDefault val shouldExcludeFromIncrementalMount: Boolean = false
  @JvmField @PropDefault val shouldUseMatchParentLayoutParams: Boolean = false

  @JvmField @PropDefault val movementMethod: MovementMethod = ArrowKeyMovementMethod.getInstance()

  /** UI thread only; used in OnMount. */
  private val BackgroundPaddingRect = Rect()

  /** UI thread only; used in OnMount. */
  private val NO_FILTERS = arrayOfNulls<InputFilter>(0)

  @JvmStatic
  @OnCreateInitialState
  fun onCreateInitialState(
      mountedView: StateValue<AtomicReference<EditTextWithEventHandlers?>?>,
      savedText: StateValue<AtomicReference<CharSequence?>?>,
      measureSeqNumber: StateValue<Int?>,
      @Prop(optional = true, resType = ResType.STRING) initialText: CharSequence?,
  ) {
    mountedView.set(AtomicReference())
    measureSeqNumber.set(0)
    savedText.set(AtomicReference(initialText))
  }

  @JvmStatic
  @OnLoadStyle
  fun onLoadStyle(c: ComponentContext, highlightColor: Output<Int?>) {
    val a = c.obtainStyledAttributes(intArrayOf(android.R.attr.textColorHighlight), 0)
    try {
      highlightColor.set(a.getColor(0, 0))
    } finally {
      a.recycle()
    }
  }

  @OnMeasure
  fun onMeasure(
      c: ComponentContext,
      layout: ComponentLayout?,
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
      @Prop(optional = true) typeface: Typeface?,
      @Prop(optional = true) textAlignment: Int,
      @Prop(optional = true) gravity: Int,
      @Prop(optional = true) editable: Boolean,
      @Prop(optional = true) cursorVisible: Boolean,
      @Prop(optional = true) inputType: Int,
      @Prop(optional = true) rawInputType: Int,
      @Prop(optional = true) imeOptions: Int,
      @Prop(optional = true) privateImeOptions: String?,
      @Prop(optional = true, varArg = "inputFilter")
      inputFilters: List<@JvmSuppressWildcards InputFilter?>?,
      @Prop(optional = true) multiline: Boolean,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true) minLines: Int,
      @Prop(optional = true) maxLines: Int,
      @Prop(optional = true, resType = ResType.BOOL) shouldIncludeFontPadding: Boolean,
      @Prop(optional = true) lineSpacingMultiplier: Float,
      @Prop(optional = true) cursorDrawableRes: Int,
      @Prop(optional = true, resType = ResType.STRING) error: CharSequence?,
      @Prop(optional = true, resType = ResType.DRAWABLE) errorDrawable: Drawable?,
      @Prop(optional = true) keyListener: KeyListener?,
      @Prop(optional = true) importantForAutofill: Int,
      @Prop(optional = true) autofillHints: Array<String?>?,
      @Prop(optional = true) disableAutofill: Boolean,
      @Prop(optional = true) isHintOnOneLine: Boolean,
      @State savedText: AtomicReference<CharSequence?>,
  ) {
    val forMeasure = createAndMeasureEditText(
        c,
        layout,
        widthSpec,
        heightSpec,
        size,
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
        keyListener,
        imeOptions,
        privateImeOptions,
        inputFilters,
        multiline,
        ellipsize,
        minLines,
        maxLines,
        shouldIncludeFontPadding,
        lineSpacingMultiplier,
        cursorDrawableRes,
        error,
        errorDrawable,
        importantForAutofill,
        autofillHints,
        disableAutofill,
        // onMeasure happens:
        // 1. After initState before onMount: savedText = initText.
        // 2. After onMount before onUnmount: savedText preserved from underlying editText.
        savedText.get(),
        isHintOnOneLine = isHintOnOneLine,
    )

    setSizeForView(size, widthSpec, heightSpec, forMeasure)
  }

  @JvmStatic
  fun setSizeForView(size: Size, widthSpec: Int, heightSpec: Int, forMeasure: View) {
    size.height = forMeasure.measuredHeight

    // For width we always take all available space, or collapse to 0 if unspecified.
    if (getMode(widthSpec) == SizeSpec.UNSPECIFIED) {
      size.width = 0
    } else {
      size.width = min(getSize(widthSpec).toDouble(), forMeasure.measuredWidth.toDouble()).toInt()
    }
  }

  @JvmStatic
  fun createAndMeasureEditText(
      c: ComponentContext,
      layout: ComponentLayout?,
      widthSpec: Int,
      heightSpec: Int,
      size: Size?,
      hint: CharSequence?,
      inputBackground: Drawable?,
      shadowRadius: Float,
      shadowDx: Float,
      shadowDy: Float,
      shadowColor: Int,
      textColorStateList: ColorStateList?,
      hintColorStateList: ColorStateList?,
      highlightColor: Int?,
      textSize: Int,
      typeface: Typeface?,
      textStyle: Int,
      textAlignment: Int,
      gravity: Int,
      editable: Boolean,
      cursorVisible: Boolean,
      inputType: Int,
      rawInputType: Int,
      keyListener: KeyListener?,
      imeOptions: Int,
      privateImeOptions: String?,
      inputFilters: List<InputFilter?>?,
      multiline: Boolean,
      ellipsize: TextUtils.TruncateAt?,
      minLines: Int,
      maxLines: Int,
      shouldIncludeFontPadding: Boolean,
      lineSpacingMultiplier: Float,
      cursorDrawableRes: Int,
      error: CharSequence?,
      errorDrawable: Drawable?,
      importantForAutofill: Int,
      autofillHints: Array<String?>?,
      disableAutofill: Boolean,
      text: CharSequence?,
      isHintOnOneLine: Boolean = false,
  ): EditText {
    // The height should be the measured height of EditText with relevant params
    var textToMeasure = text
    val forMeasure = ForMeasureEditText(c.androidContext)
    // If text contains Spans, we don't want it to be mutable for the measurement case
    if (textToMeasure is Spannable) {
      textToMeasure = textToMeasure.toString()
    }
    setParams(
        forMeasure,
        hint,
        getBackgroundOrDefault(
            c,
            if (inputBackground === UNSET_DRAWABLE) {
              forMeasure.background
            } else {
              inputBackground
            },
        ),
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
        shouldIncludeFontPadding,
        lineSpacingMultiplier,
        cursorDrawableRes,
        forMeasure.movementMethod,
        textToMeasure,
        error,
        errorDrawable,
        true,
        importantForAutofill,
        autofillHints,
        c,
        inputConnectionEventHandler = null, // Not needed for measurement
    )
    forMeasure.setDisableAutofill(disableAutofill)
    if (isHintOnOneLine && hint != null && getMode(widthSpec) != SizeSpec.UNSPECIFIED) {
      forMeasure.hint = cutToOneLine(hint, forMeasure, getSize(widthSpec))
    }
    forMeasure.measure(getViewMeasureSpec(widthSpec), getViewMeasureSpec(heightSpec))
    return forMeasure
  }

  @JvmStatic
  fun setParams(
      editText: EditText,
      hint: CharSequence?,
      background: Drawable?,
      shadowRadius: Float,
      shadowDx: Float,
      shadowDy: Float,
      shadowColor: Int,
      textColorStateList: ColorStateList?,
      hintColorStateList: ColorStateList?,
      highlightColor: Int?,
      textSize: Int,
      typeface: Typeface?,
      textStyle: Int,
      textAlignment: Int,
      gravity: Int,
      editable: Boolean,
      cursorVisible: Boolean,
      inputType: Int,
      rawInputType: Int,
      keyListener: KeyListener?,
      imeOptions: Int,
      privateImeOptions: String?,
      inputFilters: List<InputFilter?>?,
      multiline: Boolean,
      ellipsize: TextUtils.TruncateAt?,
      minLines: Int,
      maxLines: Int,
      shouldIncludeFontPadding: Boolean,
      lineSpacingMultiplier: Float,
      cursorDrawableRes: Int,
      movementMethod: MovementMethod?,
      text: CharSequence?,
      error: CharSequence?,
      errorDrawable: Drawable?,
      isForMeasure: Boolean,
      importantForAutofill: Int,
      autofillHints: Array<String?>?,
      c: ComponentContext,
      inputConnectionEventHandler: EventHandler<InputConnectionEvent>?,
  ) {
    var inputTypeToSet = inputType
    if (textSize == TextComponentSpec.UNSET) {
      editText.setTextSize(
          TypedValue.COMPLEX_UNIT_SP,
          TextComponentSpec.DEFAULT_TEXT_SIZE_SP.toFloat(),
      )
    } else {
      editText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize.toFloat())
    }

    editText.includeFontPadding = shouldIncludeFontPadding
    editText.setLineSpacing(editText.lineSpacingExtra, lineSpacingMultiplier)

    if (multiline) {
      inputTypeToSet =
          inputTypeToSet or (EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)
      editText.minLines = minLines
      editText.maxLines = maxLines
    } else {
      inputTypeToSet = inputTypeToSet and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE.inv()
      editText.setLines(1)
    }

    if (!editable) {
      inputTypeToSet = EditorInfo.TYPE_NULL
    }

    // Save current selection before changing inputType
    val currentSelectionStart = editText.selectionStart
    val currentSelectionEnd = editText.selectionEnd
    val hadSelection = currentSelectionStart >= 0 && currentSelectionEnd >= 0

    if (rawInputType != EditorInfo.TYPE_NULL) {
      editText.setRawInputType(rawInputType)
    } else {
      // Only set inputType if rawInputType is not specified.
      setInputTypeAndKeyListenerIfChanged(
          editText,
          inputTypeToSet,
          keyListener,
          inputConnectionEventHandler,
          isForMeasure,
      )
    }

    // Needs to be set before the text so it would apply to the current text
    if (inputFilters != null) {
      editText.filters = inputFilters.toTypedArray<InputFilter?>()
    } else {
      editText.filters = NO_FILTERS
    }
    editText.hint = hint
    editText.background = background
    // From the docs for setBackground:
    // "If the background has padding, this View's padding is set to the background's padding.
    // However, when a background is removed, this View's padding isn't touched. If setting the
    // padding is desired, please use setPadding."
    if (background == null || !background.getPadding(BackgroundPaddingRect)) {
      editText.setPadding(0, 0, 0, 0)
    }
    editText.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
    editText.setTypeface(typeface, textStyle)
    editText.gravity = gravity
    editText.imeOptions = imeOptions
    if (privateImeOptions != null) {
      editText.privateImeOptions = privateImeOptions
    }
    editText.isFocusable = editable
    editText.isFocusableInTouchMode = editable
    editText.isLongClickable = editable
    editText.isCursorVisible = cursorVisible
    editText.setTextColor(textColorStateList)
    editText.setHintTextColor(hintColorStateList)
    if (highlightColor != null) {
      editText.highlightColor = highlightColor
    }
    editText.movementMethod = movementMethod

    /**
     * Sets error state on the TextInput, which shows an error icon provided by errorDrawable and an
     * error message
     *
     * @param error Message that will be shown when error is not null and text input is in focused
     *   state
     * @param errorDrawable icon that signals an existing error and anchors a popover showing the
     *   errorMessage when component is focused.
     */
    editText.setError(error, errorDrawable)

    if (cursorDrawableRes != -1) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        editText.setTextCursorDrawable(cursorDrawableRes)
      } else {
        try {
          // Uses reflection because there is no public API to change cursor color programmatically.
          // Based on
          // http://stackoverflow.com/questions/25996032/how-to-change-programatically-edittext-cursor-color-in-android.
          val f = TextView::class.java.getDeclaredField("mCursorDrawableRes")
          f.isAccessible = true
          f[editText] = cursorDrawableRes
        } catch (exception: Exception) {
          // no-op don't set cursor drawable
        }
      }
    }

    editText.ellipsize = ellipsize
    editText.textAlignment = textAlignment
    if (text != null && !ObjectsCompat.equals(editText.text.toString(), text.toString())) {
      editText.setText(text)
      // Set the selection only when mounting because #setSelection does not affect measurement,
      // but it can mutate the span during measurement, potentially causing crashes.
      if (!isForMeasure) {
        editText.setSelection(editText.text.toString().length)
      }
    } else if (!isForMeasure && hadSelection) {
      // Restore selection after inputType change if text didn't change
      val textLength = editText.text.length
      val restoredStart = kotlin.math.min(currentSelectionStart, textLength)
      val restoredEnd = kotlin.math.min(currentSelectionEnd, textLength)
      editText.setSelection(
          restoredStart,
          if (restoredEnd < restoredStart) restoredStart else restoredEnd,
      )
    }

    Api26Utils.setAutoFillProps(editText, importantForAutofill, autofillHints)
  }

  private fun setInputTypeAndKeyListenerIfChanged(
      editText: EditText,
      inputType: Int,
      keyListener: KeyListener?,
      inputConnectionEventHandler: EventHandler<InputConnectionEvent>?,
      isForMeasure: Boolean,
  ) {

    // Avoid redundant call to InputMethodManager#restartInput.
    if (inputType != editText.inputType) {

      // CRITICAL: Set inputConnectionEventHandler BEFORE changing inputType to avoid race condition
      // When we change inputType, Android may immediately call onCreateInputConnection
      // If inputConnectionEventHandler is null at that moment, the custom InputConnection won't be
      // created
      if (
          ComponentsConfiguration.enableInputConnectionFix &&
              inputConnectionEventHandler != null &&
              !isForMeasure
      ) {
        (editText as? EditTextWithEventHandlers)?.setInputConnectionEventHandler(
            inputConnectionEventHandler,
        )
      }
      editText.inputType = inputType
    }

    // Optionally Set KeyListener later to override the one set by the InputType
    if (keyListener != null && keyListener !== editText.keyListener) {
      editText.keyListener = keyListener
    }
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
      @Prop(optional = true, resType = ResType.BOOL) shouldIncludeFontPadding: Diff<Boolean?>,
      @Prop(optional = true) lineSpacingMultiplier: Diff<Float?>,
      @Prop(optional = true) cursorDrawableRes: Diff<Int?>,
      @Prop(optional = true) movementMethod: Diff<MovementMethod?>,
      @Prop(optional = true, resType = ResType.STRING) error: Diff<CharSequence?>,
      @Prop(optional = true) keyListener: Diff<KeyListener?>,
      @Prop(optional = true) shouldExcludeFromIncrementalMount: Diff<Boolean>,
      @Prop(optional = true) shouldUseMatchParentLayoutParams: Diff<Boolean>,
      @Prop(optional = true) isHintOnOneLine: Diff<Boolean>,
      @State measureSeqNumber: Diff<Int?>,
      @State mountedView: Diff<AtomicReference<EditTextWithEventHandlers?>>,
      @State savedText: Diff<AtomicReference<CharSequence?>>,
  ): Boolean {
    if (!ObjectsCompat.equals(measureSeqNumber.previous, measureSeqNumber.next)) {
      return true
    }
    if (!ObjectsCompat.equals(initialText.previous, initialText.next)) {
      return true
    }
    if (!ObjectsCompat.equals(hint.previous, hint.next)) {
      return true
    }
    if (!ObjectsCompat.equals(shadowRadius.previous, shadowRadius.next)) {
      return true
    }
    if (!ObjectsCompat.equals(shadowDx.previous, shadowDx.next)) {
      return true
    }
    if (!ObjectsCompat.equals(shadowDy.previous, shadowDy.next)) {
      return true
    }
    if (!ObjectsCompat.equals(shadowColor.previous, shadowColor.next)) {
      return true
    }
    if (!ObjectsCompat.equals(textColorStateList.previous, textColorStateList.next)) {
      return true
    }
    if (!ObjectsCompat.equals(hintColorStateList.previous, hintColorStateList.next)) {
      return true
    }
    if (!ObjectsCompat.equals(highlightColor.previous, highlightColor.next)) {
      return true
    }
    if (!ObjectsCompat.equals(textSize.previous, textSize.next)) {
      return true
    }
    if (!ObjectsCompat.equals(typeface.previous, typeface.next)) {
      return true
    }
    if (!ObjectsCompat.equals(textStyle.previous, textStyle.next)) {
      return true
    }
    if (!ObjectsCompat.equals(textAlignment.previous, textAlignment.next)) {
      return true
    }
    if (!ObjectsCompat.equals(gravity.previous, gravity.next)) {
      return true
    }
    if (!ObjectsCompat.equals(editable.previous, editable.next)) {
      return true
    }
    if (!ObjectsCompat.equals(cursorVisible.previous, cursorVisible.next)) {
      return true
    }
    if (!ObjectsCompat.equals(inputType.previous, inputType.next)) {
      return true
    }
    if (!ObjectsCompat.equals(rawInputType.previous, rawInputType.next)) {
      return true
    }
    if (!ObjectsCompat.equals(keyListener.previous, keyListener.next)) {
      return true
    }
    if (
        !ObjectsCompat.equals(
            shouldExcludeFromIncrementalMount.previous,
            shouldExcludeFromIncrementalMount.next,
        )
    ) {
      return true
    }
    if (
        !ObjectsCompat.equals(
            shouldUseMatchParentLayoutParams.previous,
            shouldUseMatchParentLayoutParams.next,
        )
    ) {
      return true
    }
    if (isHintOnOneLine.previous != isHintOnOneLine.next) {
      return true
    }
    if (!ObjectsCompat.equals(imeOptions.previous, imeOptions.next)) {
      return true
    }
    if (!ObjectsCompat.equals(privateImeOptions.previous, privateImeOptions.next)) {
      return true
    }
    if (!equalInputFilters(inputFilters.previous, inputFilters.next)) {
      return true
    }
    if (!ObjectsCompat.equals(ellipsize.previous, ellipsize.next)) {
      return true
    }
    if (!ObjectsCompat.equals(multiline.previous, multiline.next)) {
      return true
    }
    // Minimum and maximum line count should only get checked if multiline is set
    if (multiline.next == true) {
      if (!ObjectsCompat.equals(minLines.previous, minLines.next)) {
        return true
      }
      if (!ObjectsCompat.equals(maxLines.previous, maxLines.next)) {
        return true
      }
    }
    if (
        !ObjectsCompat.equals(
            shouldIncludeFontPadding.previous,
            shouldIncludeFontPadding.next,
        )
    ) {
      return true
    }
    if (!ObjectsCompat.equals(lineSpacingMultiplier.previous, lineSpacingMultiplier.next)) {
      return true
    }
    if (!ObjectsCompat.equals(cursorDrawableRes.previous, cursorDrawableRes.next)) {
      return true
    }
    if (!ObjectsCompat.equals(movementMethod.previous, movementMethod.next)) {
      return true
    }
    if (!ObjectsCompat.equals(error.previous, error.next)) {
      return true
    }

    // Note, these are purposefully just comparing the containers, not the contents!
    if (mountedView.previous !== mountedView.next) {
      return true
    }
    if (savedText.previous !== savedText.next) {
      return true
    }

    // Save the nastiest for last: trying to diff drawables.
    val previousBackground = inputBackground.previous
    val nextBackground = inputBackground.next
    if (previousBackground == null && nextBackground != null) {
      return true
    } else if (previousBackground != null && nextBackground == null) {
      return true
    } else if (previousBackground != null && nextBackground != null) {
      if (previousBackground is ColorDrawable && nextBackground is ColorDrawable) {
        // This doesn't account for tint list/mode (no way to get that information)
        // and doesn't account for color filter (fine since ColorDrawable ignores it anyway).
        if (previousBackground.color != nextBackground.color) {
          return true
        }
      } else {
        // The best we can do here is compare getConstantState. This can result in spurious updates;
        // they might be different objects representing the same drawable. But it's the best we can
        // do without actually comparing bitmaps (which is too expensive).
        if (!ObjectsCompat.equals(previousBackground.constantState, nextBackground.constantState)) {
          return true
        }
      }
    }
    return false
  }

  /** LengthFilter and AllCaps do not implement isEqual. Correct for the deficiency. */
  fun equalInputFilters(a: List<InputFilter?>?, b: List<InputFilter?>?): Boolean {
    if (a == null && b == null) {
      return true
    }
    if (a == null || b == null) {
      return false
    }
    if (a.size != b.size) {
      return false
    }
    for (i in a.indices) {
      val fa = a[i]
      val fb = b[i]
      if (fa is InputFilter.AllCaps && fb is InputFilter.AllCaps) {
        continue // equal, AllCaps has no configuration
      }
      if (fa is InputFilter.LengthFilter && fb is InputFilter.LengthFilter) {
        if (fa.max != fb.max) {
          return false
        }
        continue // equal, same max
      }

      // Best we can do in this case is call equals().
      if (!ObjectsCompat.equals(fa, fb)) {
        return false
      }
    }
    return true
  }

  @OnCreateMountContent
  fun onCreateMountContent(c: Context?): EditTextWithEventHandlers {
    val editText = EditTextWithEventHandlers(c)
    // Setting a custom editable factory so we can catch and rethrow crashes from
    // SpannableStringBuilder#setSpan with additional information. This should cause no
    // functional changes.
    editText.setEditableFactory(SafeSetSpanEditableFactory())
    if (ComponentsConfiguration.textInputUsesMatchParentLayoutParams) {
      editText.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }
    return editText
  }

  @OnMount
  fun onMount(
      c: ComponentContext,
      editText: EditTextWithEventHandlers,
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
      @Prop(optional = true) typeface: Typeface?,
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
      inputFilters: List<@JvmSuppressWildcards InputFilter?>?,
      @Prop(optional = true) multiline: Boolean,
      @Prop(optional = true) minLines: Int,
      @Prop(optional = true) maxLines: Int,
      @Prop(optional = true, resType = ResType.BOOL) shouldIncludeFontPadding: Boolean,
      @Prop(optional = true) lineSpacingMultiplier: Float,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true) cursorDrawableRes: Int,
      @Prop(optional = true) movementMethod: MovementMethod,
      @Prop(optional = true, resType = ResType.STRING) error: CharSequence?,
      @Prop(optional = true, resType = ResType.DRAWABLE) errorDrawable: Drawable?,
      @Prop(optional = true) keyListener: KeyListener?,
      @Prop(optional = true) importantForAutofill: Int,
      @Prop(optional = true) autofillHints: Array<String?>?,
      @Prop(optional = true) disableAutofill: Boolean,
      @Prop(optional = true) shouldUseMatchParentLayoutParams: Boolean,
      @Prop(optional = true) isHintOnOneLine: Boolean,
      @State savedText: AtomicReference<CharSequence?>,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    mountedView.set(editText)
    if (shouldUseMatchParentLayoutParams) {
      editText.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    setParams(
        editText,
        hint,
        getBackgroundOrDefault(c, inputBackground),
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
        shouldIncludeFontPadding,
        lineSpacingMultiplier,
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
        inputConnectionEventHandler =
            if (ComponentsConfiguration.enableInputConnectionFix) {
              TextInputComponent.getInputConnectionEventHandler(c)
            } else {
              null
            },
    )
    editText.setDisableAutofill(disableAutofill)
    editText.setTextState(savedText)
    editText.setHintOnOneLine(hint.takeIf { isHintOnOneLine })
  }

  @OnBind
  fun onBind(
      c: ComponentContext,
      editText: EditTextWithEventHandlers,
      @Prop(optional = true, varArg = "textWatcher") textWatchers: List<TextWatcher>?,
      @Prop(optional = true) selectionActionModeCallback: ActionMode.Callback?,
      @Prop(optional = true) insertionActionModeCallback: ActionMode.Callback?,
  ) {
    onBindEditText(
        c,
        editText,
        textWatchers,
        selectionActionModeCallback,
        insertionActionModeCallback,
        TextInputComponent.getTextChangedEventHandler(c),
        TextInputComponent.getSelectionChangedEventHandler(c),
        TextInputComponent.getInputFocusChangedEventHandler(c),
        TextInputComponent.getKeyUpEventHandler(c),
        TextInputComponent.getKeyPreImeEventHandler(c),
        TextInputComponent.getEditorActionEventHandler(c),
        TextInputComponent.getInputConnectionEventHandler(c),
        TextInputComponent.getTextPastedEventHandler(c),
    )
  }

  @JvmStatic
  fun onBindEditText(
      c: ComponentContext,
      editText: EditTextWithEventHandlers,
      textWatchers: List<TextWatcher>?,
      selectionActionModeCallback: ActionMode.Callback?,
      insertionActionModeCallback: ActionMode.Callback?,
      textChangedEventHandler: EventHandler<TextChangedEvent>?,
      selectionChangedEventHandler: EventHandler<SelectionChangedEvent>?,
      inputFocusChangedEventHandler: EventHandler<InputFocusChangedEvent>?,
      keyUpEventHandler: EventHandler<KeyUpEvent>?,
      keyPreImeEventHandler: EventHandler<KeyPreImeEvent>?,
      EditorActionEventHandler: EventHandler<EditorActionEvent>?,
      inputConnectionEventHandler: EventHandler<InputConnectionEvent>?,
      textPastedEventHandler: EventHandler<TextPastedEvent>?,
  ) {
    editText.attachWatchers(textWatchers)
    editText.customSelectionActionModeCallback = selectionActionModeCallback
    editText.customInsertionActionModeCallback = insertionActionModeCallback
    editText.setComponentContext(c)
    editText.setTextChangedEventHandler(textChangedEventHandler)
    editText.setSelectionChangedEventHandler(selectionChangedEventHandler)
    editText.setInputFocusChangedEventHandler(inputFocusChangedEventHandler)
    editText.setKeyUpEventHandler(keyUpEventHandler)
    editText.setKeyPreImeEventEventHandler(keyPreImeEventHandler)
    editText.setEditorActionEventHandler(EditorActionEventHandler)
    editText.setInputConnectionEventHandler(inputConnectionEventHandler)
    editText.setTextPastedEventHandler(textPastedEventHandler)
  }

  @JvmStatic
  @OnUnmount
  fun onUnmount(
      c: ComponentContext,
      editText: EditTextWithEventHandlers,
      @Prop(optional = true) keyListener: KeyListener?,
      @Prop(optional = true) shouldUseMatchParentLayoutParams: Boolean,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    if (keyListener != null) {
      editText.keyListener = null // Clear any KeyListener
      editText.inputType = inputType // Set the input type back to default.
    }
    editText.movementMethod = null
    editText.setTextState(null)
    editText.removeOnWindowFocusChangeListener()
    editText.resetSoftInputState()
    if (ComponentsConfiguration.enableInputConnectionFix) {
      editText.setInputConnectionEventHandler(null)
    }
    editText.privateImeOptions = null
    editText.filters = NO_FILTERS
    // With the flag, MATCH_PARENT is what the view was created with.
    if (
        shouldUseMatchParentLayoutParams &&
            !ComponentsConfiguration.textInputUsesMatchParentLayoutParams
    ) {
      // The host default, so the pooled view matches a fresh one.
      editText.layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    }
    mountedView.set(null)
  }

  @JvmStatic
  @OnUnbind
  fun onUnbind(c: ComponentContext, editText: EditTextWithEventHandlers) {
    editText.detachWatchers()

    editText.setComponentContext(null)
    editText.setTextChangedEventHandler(null)
    editText.setSelectionChangedEventHandler(null)
    editText.setInputFocusChangedEventHandler(null)
    editText.setKeyUpEventHandler(null)
    editText.setKeyPreImeEventEventHandler(null)
    editText.setEditorActionEventHandler(null)
    editText.setInputConnectionEventHandler(null)
    editText.customSelectionActionModeCallback = null
    editText.customInsertionActionModeCallback = null
    editText.setTextPastedEventHandler(null)
  }

  @JvmStatic
  fun getBackgroundOrDefault(c: ComponentContext, specifiedBackground: Drawable?): Drawable? {
    if (specifiedBackground === UNSET_DRAWABLE) {
      val attrs = intArrayOf(android.R.attr.background)
      val a = c.androidContext.obtainStyledAttributes(null, attrs, android.R.attr.editTextStyle, 0)
      val defaultBackground = a.getDrawable(0)
      a.recycle()
      return defaultBackground
    }

    return specifiedBackground
  }

  @JvmStatic
  @OnTrigger(RequestFocusEvent::class)
  fun requestFocus(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    val view = mountedView.get()
    if (view != null) {
      if (view.requestFocus()) {
        view.setSoftInputVisibility(true)
        // Force request of accessibility focus because in some cases if something else is
        // requesting accessibility focus, it can lead to race condition with taking the focus from
        // view.requestFocus and input will not be highlighted by TalkBack
        view.performAccessibilityFocus()
      }
    }
  }

  @JvmStatic
  @OnTrigger(RequestFocusWithoutKeyboardEvent::class)
  fun requestFocusWithoutKeyboard(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    val view = mountedView.get()
    if (view?.requestFocus() == true) {
      // Force request of accessibility focus because in some cases if something else is
      // requesting accessibility focus, it can lead to race condition with taking the focus from
      // view.requestFocus and input will not be highlighted by TalkBack
      view.performAccessibilityFocus()
      view.setSoftInputVisibility(false)
    }
  }

  @JvmStatic
  @OnTrigger(ClearFocusEvent::class)
  fun clearFocus(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    val view = mountedView.get()
    if (view != null) {
      view.clearFocus()
      view.setSoftInputVisibility(false)
    }
  }

  @OnTrigger(ShowCursorEvent::class)
  fun showCursor(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    val view = mountedView.get()
    if (view != null) {
      view.isCursorVisible = true
    }
  }

  @OnTrigger(HideCursorEvent::class)
  fun hideCursor(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ) {
    val view = mountedView.get()
    if (view != null) {
      view.isCursorVisible = false
    }
  }

  @JvmStatic
  @OnTrigger(GetTextEvent::class)
  fun getText(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @State savedText: AtomicReference<CharSequence?>,
  ): CharSequence? {
    val view = mountedView.get()
    return if (view == null) savedText.get() else view.text
  }

  @JvmStatic
  @OnTrigger(GetLineCountEvent::class)
  fun getLineCount(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
  ): Int? {
    val view = mountedView.get()
    return view?.lineCount
  }

  @OnTrigger(SetTextEvent::class)
  fun setText(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @State savedText: AtomicReference<CharSequence?>,
      @FromTrigger text: CharSequence?,
  ) {
    val shouldRemeasure = setTextEditText(mountedView, savedText, text)
    if (shouldRemeasure) {
      TextInputComponent.remeasureForUpdatedTextSync(c)
    }
  }

  @JvmStatic
  fun setTextEditText(
      mountedView: AtomicReference<EditTextWithEventHandlers?>,
      savedText: AtomicReference<CharSequence?>,
      text: CharSequence?,
  ): Boolean {
    assertMainThread()

    val editText = mountedView.get()
    if (editText == null) {
      savedText.set(text)
      return true
    }

    // If line count changes state update will be triggered by view
    editText.setText(text)
    val editable = editText.text
    val length = editable?.length ?: 0
    editText.setSelection(length)
    return false
  }

  @OnTrigger(ReplaceTextEvent::class)
  fun replaceText(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @State savedText: AtomicReference<CharSequence?>,
      @FromTrigger text: CharSequence?,
      @FromTrigger startIndex: Int,
      @FromTrigger endIndex: Int,
      @FromTrigger skipSelection: Boolean,
  ) {
    val view = mountedView.get()
    val editable = view?.text
    if (editable != null) {
      editable.replace(startIndex, endIndex, text)
      if (!skipSelection) {
        view.setSelection(if (text != null) (startIndex + text.length) else startIndex)
      }
      return
    }

    val currentSavedText = savedText.get()
    savedText.set(
        if (currentSavedText == null) text
        else
            SpannableStringBuilder()
                .append(currentSavedText.subSequence(0, startIndex))
                .append(text)
                .append(currentSavedText.subSequence(endIndex, currentSavedText.length)),
    )

    TextInputComponent.remeasureForUpdatedTextSync(c)
  }

  @JvmStatic
  @OnTrigger(DispatchKeyEvent::class)
  fun dispatchKey(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @FromTrigger keyEvent: KeyEvent?,
  ) {
    val view = mountedView.get()
    view?.dispatchKeyEvent(keyEvent)
  }

  @JvmStatic
  @OnTrigger(SetSelectionEvent::class)
  fun setSelection(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @FromTrigger start: Int,
      @FromTrigger end: Int,
  ) {
    val view = mountedView.get()
    view?.setSelection(start, if (end < start) start else end)
  }

  @OnTrigger(SetSpanEvent::class)
  fun setSpan(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @FromTrigger what: Any?,
      @FromTrigger start: Int,
      @FromTrigger end: Int,
      @FromTrigger flags: Int,
  ) {
    assertMainThread()

    val view = mountedView.get()
    val editable = view?.text
    editable?.setSpan(what, start, end, flags)
  }

  @OnTrigger(RemoveSpanEvent::class)
  fun removeSpan(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @FromTrigger what: Any?,
  ) {
    assertMainThread()

    val view = mountedView.get()
    val editable = view?.text
    editable?.removeSpan(what)
  }

  @OnTrigger(GetSpanStartEvent::class)
  fun getSpanStart(
      c: ComponentContext,
      @State mountedView: AtomicReference<EditTextWithEventHandlers?>,
      @FromTrigger what: Any?,
  ): Int {
    assertMainThread()

    val view = mountedView.get()
    val editable = view?.text
    if (editable != null) {
      return editable.getSpanStart(what)
    }

    return -1
  }

  @OnUpdateState
  fun remeasureForUpdatedText(measureSeqNumber: StateValue<Int>) {
    measureSeqNumber.set((measureSeqNumber.get() ?: 0) + 1)
  }

  @JvmStatic
  @ShouldExcludeFromIncrementalMount
  fun shouldExcludeFromIncrementalMount(
      @Prop(optional = true) shouldExcludeFromIncrementalMount: Boolean,
  ): Boolean = shouldExcludeFromIncrementalMount

  internal class EditTextWithEventHandlers(context: Context?) :
      EditText(context), TextView.OnEditorActionListener {
    private var textPastedEventHandler: EventHandler<TextPastedEvent>? = null
    private var textChangedEventHandler: EventHandler<TextChangedEvent>? = null
    private var selectionChangedEventHandler: EventHandler<SelectionChangedEvent>? = null
    private var inputFocusChangedEventHandler: EventHandler<InputFocusChangedEvent>? = null
    private var keyUpEventHandler: EventHandler<KeyUpEvent>? = null
    private var keyPreImeEventEventHandler: EventHandler<KeyPreImeEvent>? = null
    private var editorActionEventHandler: EventHandler<EditorActionEvent>? = null
    private var inputConnectionEventHandler: EventHandler<InputConnectionEvent>? = null
    private var componentContext: ComponentContext? = null
    private var textState: AtomicReference<CharSequence?>? = null
    private var textLineCount = UNMEASURED_LINE_COUNT
    private var textWatcher: TextWatcher? = null
    private var isSoftInputRequested = false
    /** The whole hint while it's laid out on one line; null otherwise. */
    private var fullHint: CharSequence? = null
    private var oneLineHint: OneLineHint? = null

    private var disableAutofill = false
    private var isTextPasted = false

    private var onWindowFocusChangeListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    init {
      // Unfortunately we can't just override `void onEditorAction(int actionCode)` as that only
      // covers a subset of all cases where onEditorActionListener is invoked.
      this.setOnEditorActionListener(this)
    }

    override fun onTextChanged(
        text: CharSequence,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int,
    ) {
      super.onTextChanged(text, start, lengthBefore, lengthAfter)
      if (ComponentsConfiguration.snapshotTextInputTextOnTextChanged) {
        textState?.set(SpannableStringBuilder(text))
      } else {
        textState?.set(text)
      }
      if (textChangedEventHandler != null) {
        TextInputComponent.dispatchTextChangedEvent(
            textChangedEventHandler,
            this@EditTextWithEventHandlers,
            text.toString(),
        )
      }
      if (isTextPasted && textPastedEventHandler != null) {
        TextInputComponent.dispatchTextPastedEvent(
            textPastedEventHandler,
            this@EditTextWithEventHandlers,
            text.toString(),
        )
        isTextPasted = false
      }
      // Line count of changed text.
      val lineCount = lineCount
      if (
          this.textLineCount != UNMEASURED_LINE_COUNT &&
              (this.textLineCount != lineCount) &&
              (componentContext != null)
      ) {
        TextInputComponent.remeasureForUpdatedTextSync(componentContext)
      }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
      if (id == android.R.id.paste && textPastedEventHandler != null) {
        isTextPasted = true
      }
      return super.onTextContextMenuItem(id)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
      fullHint?.let { oneLineHint?.applyTo(this, it, View.MeasureSpec.getSize(widthMeasureSpec)) }
      super.onMeasure(widthMeasureSpec, heightMeasureSpec)
      // Line count of the current text.
      textLineCount = lineCount
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
      super.onInitializeAccessibilityNodeInfo(info)
      fullHint?.let { describeWholeHint(info, it, isShowingHint = text.isEmpty()) }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
      super.onSelectionChanged(selStart, selEnd)
      if (selectionChangedEventHandler != null) {
        TextInputComponent.dispatchSelectionChangedEvent(
            selectionChangedEventHandler,
            selStart,
            selEnd,
        )
      }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
      super.onFocusChanged(focused, direction, previouslyFocusedRect)
      if (inputFocusChangedEventHandler != null) {
        TextInputComponent.dispatchInputFocusChangedEvent(inputFocusChangedEventHandler, focused)
      }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
      if (keyUpEventHandler != null) {
        return TextInputComponent.dispatchKeyUpEvent(keyUpEventHandler, keyCode, event)
      }
      return super.onKeyUp(keyCode, event)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
      if (keyPreImeEventEventHandler != null) {
        return TextInputComponent.dispatchKeyPreImeEvent(keyPreImeEventEventHandler, keyCode, event)
      }
      return super.onKeyPreIme(keyCode, event)
    }

    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean {
      if (editorActionEventHandler != null) {
        return TextInputComponent.dispatchEditorActionEvent(
            editorActionEventHandler,
            v,
            actionId,
            event,
        )
      }
      return false
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
      val inputConnection = super.onCreateInputConnection(editorInfo)
      if (inputConnectionEventHandler != null) {
        return TextInputComponent.dispatchInputConnectionEvent(
            inputConnectionEventHandler,
            inputConnection,
            editorInfo,
        )
      }
      return inputConnection
    }

    override fun getAutofillType(): Int {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this.disableAutofill) {
        AUTOFILL_TYPE_NONE
      } else {
        super.getAutofillType()
      }
    }

    fun setDisableAutofill(value: Boolean) {
      this.disableAutofill = value
    }

    fun setTextPastedEventHandler(textPastedEventEventHandler: EventHandler<TextPastedEvent>?) {
      textPastedEventHandler = textPastedEventEventHandler
    }

    fun setTextChangedEventHandler(textChangedEventHandler: EventHandler<TextChangedEvent>?) {
      this.textChangedEventHandler = textChangedEventHandler
    }

    fun setSelectionChangedEventHandler(
        selectionChangedEventHandler: EventHandler<SelectionChangedEvent>?,
    ) {
      this.selectionChangedEventHandler = selectionChangedEventHandler
    }

    fun setInputFocusChangedEventHandler(
        inputFocusChangedEventHandler: EventHandler<InputFocusChangedEvent>?,
    ) {
      this.inputFocusChangedEventHandler = inputFocusChangedEventHandler
    }

    fun setKeyUpEventHandler(keyUpEventHandler: EventHandler<KeyUpEvent>?) {
      this.keyUpEventHandler = keyUpEventHandler
    }

    fun setKeyPreImeEventEventHandler(keyPreImeEventEventHandler: EventHandler<KeyPreImeEvent>?) {
      this.keyPreImeEventEventHandler = keyPreImeEventEventHandler
    }

    fun setEditorActionEventHandler(editorActionEventHandler: EventHandler<EditorActionEvent>?) {
      this.editorActionEventHandler = editorActionEventHandler
    }

    fun setInputConnectionEventHandler(
        inputConnectionEventHandler: EventHandler<InputConnectionEvent>?,
    ) {
      this.inputConnectionEventHandler = inputConnectionEventHandler
    }

    /** Sets context for state update, when the text height has changed. */
    fun setComponentContext(componentContext: ComponentContext?) {
      this.componentContext = componentContext
    }

    /** Sets reference to keep current text up to date. */
    fun setTextState(savedText: AtomicReference<CharSequence?>?) {
      textState = savedText
    }

    /**
     * Lays [hint] out on one ellipsized line at this input's width. Call it after `setParams`,
     * which sets the full hint; null keeps that hint.
     */
    fun setHintOnOneLine(hint: CharSequence?) {
      fullHint = hint
      if (hint != null) {
        val oneLineHint = oneLineHint ?: OneLineHint().also { oneLineHint = it }
        if (width > 0) {
          oneLineHint.applyTo(this, hint, width)
        }
      }
    }

    fun attachWatchers(textWatchers: List<TextWatcher?>?) {
      if (textWatchers == null) {
        return
      }

      val nonNullTextWatchers = textWatchers.filterNotNull()

      if (nonNullTextWatchers.isNotEmpty()) {
        textWatcher =
            if (nonNullTextWatchers.size == 1) nonNullTextWatchers[0]
            else CompositeTextWatcher(nonNullTextWatchers)
        addTextChangedListener(textWatcher)
      }
    }

    fun detachWatchers() {
      if (textWatcher != null) {
        removeTextChangedListener(textWatcher)
        textWatcher = null
      }
    }

    fun removeOnWindowFocusChangeListener() {
      if (onWindowFocusChangeListener != null) {
        viewTreeObserver.removeOnWindowFocusChangeListener(onWindowFocusChangeListener)
        onWindowFocusChangeListener = null
      }
    }

    fun setSoftInputVisibility(visible: Boolean) {
      val imm =
          context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

      if (visible) {
        showKeyboardWhenWindowIsFocused(imm)
      } else {
        hideKeyboard(imm)
      }
    }

    private fun showKeyboardWhenWindowIsFocused(imm: InputMethodManager) {
      if (hasWindowFocus()) {
        showKeyboard(imm)
      } else {
        // We need to wait until the window gets focus.
        // https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility#ShowReliably
        onWindowFocusChangeListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
          if (hasFocus) {
            showKeyboard(imm)
            removeOnWindowFocusChangeListener()
          }
        }
        viewTreeObserver.addOnWindowFocusChangeListener(onWindowFocusChangeListener)
      }
    }

    private fun showKeyboard(imm: InputMethodManager) {
      if (!isFocused) {
        return
      }

      if (imm.isActive(this)) {
        imm.showSoftInput(this, 0)
        isSoftInputRequested = false
      } else {
        // Unfortunately, IMM and requesting focus has race conditions and there are cases where
        // even though the focus request went through, IMM hasn't been updated yet (thus the
        // isActive check). Posting a Runnable gives time for the Runnable the IMM Binder posts
        // to run first and update the IMM.
        post {
          if (isSoftInputRequested) {
            imm.showSoftInput(this@EditTextWithEventHandlers, 0)
          }
          isSoftInputRequested = false
        }
        isSoftInputRequested = true
      }
    }

    private fun hideKeyboard(imm: InputMethodManager) {
      imm.hideSoftInputFromWindow(windowToken, 0)
      isSoftInputRequested = false
    }

    fun resetSoftInputState() {
      isSoftInputRequested = false
    }

    fun performAccessibilityFocus() {
      try {
        ViewCompat.performAccessibilityAction(
            this,
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null,
        )
      } catch (npe: NullPointerException) {
        // do nothing
      }
    }

    internal class CompositeTextWatcher(textWatchers: List<TextWatcher>) : TextWatcher {
      private val textWatchers: List<TextWatcher> = ArrayList(textWatchers)

      override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        for (w in textWatchers) {
          w.beforeTextChanged(s, start, count, after)
        }
      }

      override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        for (w in textWatchers) {
          w.onTextChanged(s, start, before, count)
        }
      }

      override fun afterTextChanged(editable: Editable) {
        for (w in textWatchers) {
          w.afterTextChanged(editable)
        }
      }
    }

    companion object {
      private const val UNMEASURED_LINE_COUNT = -1
    }
  }

  /**
   * We use this instead of vanilla EditText for measurement as the ConstantState of the EditText
   * background drawable is not thread-safe and shared across all EditText instances. This is
   * especially important as we measure this component mostly in background thread and it could lead
   * to race conditions where different instances are accessing/modifying same ConstantState
   * concurrently. Mutating background drawable will make sure that ConstantState is not shared
   * therefore will become thread-safe.
   */
  internal class ForMeasureEditText(context: Context?) : EditText(context) {
    private var disableAutofill = false

    // This view is not intended to be drawn and invalidated
    override fun invalidate(): Unit = Unit

    override fun setBackground(background: Drawable?) {
      background?.mutate()
      try {
        super.setBackground(background)
      } catch (e: NullPointerException) {
        // DrawableContainer$DrawableContainerState.createAllFutures() can NPE when a
        // ConstantState entry is null due to a race condition between concurrent background-thread
        // ForMeasureEditText constructions sharing the same default EditText drawable state.
        // Falling back to null background is safe because this view is only used for measurement
        // and the background is re-applied immediately after construction via setParams().
        super.setBackground(null)
      }
    }

    override fun getAutofillType(): Int {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this.disableAutofill) {
        AUTOFILL_TYPE_NONE
      } else {
        super.getAutofillType()
      }
    }

    fun setDisableAutofill(value: Boolean) {
      this.disableAutofill = value
    }
  }

  private object Api26Utils {
    fun setAutoFillProps(
        editText: EditText,
        importantForAutofill: Int,
        autofillHints: Array<String?>?,
    ) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        editText.importantForAutofill = importantForAutofill
        if (autofillHints != null) {
          editText.setAutofillHints(*autofillHints)
        } else {
          editText.setAutofillHints(null)
        }
      }
    }
  }

  private class SafeSetSpanEditableFactory : Editable.Factory() {
    override fun newEditable(source: CharSequence): Editable {
      return object : SpannableStringBuilder(source) {
        /**
         * Re-entrancy guard to prevent cascading emoji processing (T268141878).
         *
         * When super.setSpan() is called, Android's TypefaceEmojiUtilBase may intercept the call to
         * process emoji characters. This processing calls setSpan() again for each EmojiSpan it
         * creates, which re-enters this override and can trigger another round of emoji processing.
         * On slow devices with emoji-heavy text, this cascading behavior blocks the main thread
         * for >5 seconds, causing an ANR.
         *
         * The guard ensures that re-entrant setSpan() calls (from emoji processing) go directly to
         * super.setSpan() without additional overhead or risk of further cascading.
         */
        private var isSettingSpan = false

        override fun setSpan(what: Any?, start: Int, end: Int, flags: Int) {
          if (isSettingSpan) {
            // Re-entrant call (e.g. from TypefaceEmojiUtilBase emoji processing).
            // Delegate directly to super to avoid cascading emoji reprocessing.
            super.setSpan(what, start, end, flags)
            return
          }
          /*
            Fix for a crash in the Android Framework when deleting a character while the spell checker popup shows.
            Prevent setting a SuggestionRangeSpan with end past the Editable length using Math.min
            (https://issuetracker.google.com/issues/314288203)
          */
          isSettingSpan = true
          try {
            val spanStart: Int
            val spanEnd: Int
            if (shouldClampSelectionMarkerBounds(what)) {
              spanStart = min(max(0, start), length)
              spanEnd = max(spanStart, min(end, length))
              if (spanStart != start || spanEnd != end) {
                DebugInfoReporter.report(
                    category = "TextInputSetSpan:SelectionMarkerBoundsClamped",
                    level = LogLevel.WARNING,
                ) {
                  this["originalStart"] = start
                  this["originalEnd"] = end
                  this["spanClass"] = what?.javaClass?.name ?: "null"
                  this["flags"] = flags
                  this["length"] = length
                }
              }
            } else {
              spanStart = start
              spanEnd = if (shouldUseSafeSpanEnd(what)) min(end, length) else end
            }
            super.setSpan(what, spanStart, spanEnd, flags)
          } catch (e: IndexOutOfBoundsException) {
            /*
             Catching and rethrowing IndexOutOfBoundsExceptions with additional info. One known source
             of this crash is when using spell checker exceeds EditText maxLength
             (https://issuetracker.google.com/issues/36944935)
            */
            throw IndexOutOfBoundsException(
                String.format(
                    "%s | span=%s | flags=%d",
                    e.message,
                    what?.javaClass ?: "Unknown",
                    flags,
                ),
            )
          } finally {
            isSettingSpan = false
          }
        }

        private fun shouldUseSafeSpanEnd(span: Any?): Boolean {
          return SuggestionRangeSpanApi33Util.isSuggestionRangeSpan(span)
        }

        // Orthogonal to shouldUseSafeSpanEnd to preserve D54969262's narrow scope.
        private fun shouldClampSelectionMarkerBounds(span: Any?): Boolean {
          return ComponentsConfiguration.clampSelectionMarkerBoundsInTextInput &&
              (span === Selection.SELECTION_START || span === Selection.SELECTION_END)
        }
      }
    }
  }

  private object SuggestionRangeSpanApi33Util {
    private const val SUGGESTION_RANGE_SPAN_CLASS_NAME = "android.text.style.SuggestionRangeSpan"

    // SuggestionRangeSpan was made public in API 33, so check class name for lower API levels
    fun isSuggestionRangeSpan(span: Any?): Boolean {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        span is SuggestionRangeSpan
      } else {
        span != null && span.javaClass.name == SUGGESTION_RANGE_SPAN_CLASS_NAME
      }
    }
  }
}
