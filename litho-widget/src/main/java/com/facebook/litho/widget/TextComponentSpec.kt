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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.ReplacementSpan
import android.view.View
import androidx.annotation.Dimension
import androidx.annotation.Dimension.Companion.SP
import androidx.annotation.VisibleForTesting
import androidx.core.text.TextDirectionHeuristicCompat
import androidx.core.text.TextDirectionHeuristicsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.facebook.fbui.textlayoutbuilder.TextLayoutBuilder
import com.facebook.fbui.textlayoutbuilder.util.LayoutMeasureUtil
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentLayout
import com.facebook.litho.ComponentsReporter
import com.facebook.litho.ComponentsReporter.emitMessage
import com.facebook.litho.EventHandler
import com.facebook.litho.Output
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec.AT_MOST
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.UNSPECIFIED
import com.facebook.litho.SizeSpec.getMode
import com.facebook.litho.SizeSpec.getSize
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.SizeSpec.resolveSize
import com.facebook.litho.annotations.ExcuseMySpec
import com.facebook.litho.annotations.FromBoundsDefined
import com.facebook.litho.annotations.FromMeasure
import com.facebook.litho.annotations.GetExtraAccessibilityNodeAt
import com.facebook.litho.annotations.GetExtraAccessibilityNodesCount
import com.facebook.litho.annotations.MountSpec
import com.facebook.litho.annotations.OnBindDynamicValue
import com.facebook.litho.annotations.OnBoundsDefined
import com.facebook.litho.annotations.OnCreateMountContent
import com.facebook.litho.annotations.OnLoadStyle
import com.facebook.litho.annotations.OnMeasure
import com.facebook.litho.annotations.OnMeasureIntrinsicHeight
import com.facebook.litho.annotations.OnMeasureIntrinsicWidth
import com.facebook.litho.annotations.OnMount
import com.facebook.litho.annotations.OnPerformActionForVirtualView
import com.facebook.litho.annotations.OnPopulateAccessibilityNode
import com.facebook.litho.annotations.OnPopulateExtraAccessibilityNode
import com.facebook.litho.annotations.OnUnmount
import com.facebook.litho.annotations.OnVirtualViewKeyboardFocusChanged
import com.facebook.litho.annotations.Prop
import com.facebook.litho.annotations.PropDefault
import com.facebook.litho.annotations.Reason
import com.facebook.litho.annotations.ResType
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.utils.VersionedAndroidApis.M.getEllipsisOffsetFromPaintAdvance
import com.facebook.litho.utils.VersionedAndroidApis.Q.breakIteratorGetPreceding
import com.facebook.litho.widget.TextDrawable.TextOffsetOnTouchListener
import com.facebook.litho.widget.TextStylesHelper.DEFAULT_BREAK_STRATEGY
import com.facebook.litho.widget.TextStylesHelper.DEFAULT_EMS
import com.facebook.litho.widget.TextStylesHelper.DEFAULT_HYPHENATION_FREQUENCY
import com.facebook.litho.widget.TextStylesHelper.DEFAULT_JUSTIFICATION_MODE
import com.facebook.litho.widget.TextStylesHelper.DEFAULT_MAX_WIDTH
import com.facebook.litho.widget.TextStylesHelper.DEFAULT_MIN_WIDTH
import com.facebook.litho.widget.TextureWarmer.Companion.instance
import com.facebook.rendercore.text.ClickableSpanListener
import com.facebook.rendercore.text.TextLayoutIntrinsics
import com.facebook.rendercore.text.TouchableSpanListener
import com.facebook.widget.accessibility.delegates.AccessibleClickableSpan
import com.facebook.widget.accessibility.delegates.ContentDescriptionSpan
import com.facebook.yoga.YogaDirection
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min

/**
 * Component to render text. See [text-widget](https://fblitho.com/docs/widgets#text) for more
 * details.
 *
 * Example Text usage:
 * ```
 * final SpannableStringBuilder spannable = new SpannableStringBuilder();
 * spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
 *
 * Text.create(c)
 *   .text(spannable) // String can be used
 *   .textSizeDip(20)
 *   .maxLines(3)
 *   .ellipsize(TextUtils.TruncateAt.END)
 *   .textColor(Color.BLACK)
 *   .build()
 * ```
 *
 * @uidocs
 * @prop text Text to display.
 * @prop ellipsize If set, specifies the position of the text to be ellipsized.
 * @prop minLines Minimum number of lines to show.
 * @prop maxLines Maximum number of lines to show.
 * @prop minEms Makes the text to be mim ems wide.
 * @prop maxEms Makes the text to be max ems wide.
 * @prop minTextWidth Makes the text to be min pixels wide.
 * @prop maxTextWidth Makes the text to be max pixels wide.
 * @prop shadowRadius Blur radius of the shadow.
 * @prop shadowDx Horizontal offset of the shadow.
 * @prop shadowDy Vertical offset of the shadow.
 * @prop shadowColor Color for the shadow underneath the text.
 * @prop outlineWidth If set, gives the text outline of the specified width.
 * @prop outlineColor Sets the outline color; if it's 0 or not set, outline uses the same color as
 *   shadow.
 * @prop isSingleLine If set, makes the text to be rendered in a single line.
 * @prop textColor Color of the text.
 * @prop textColorStateList ColorStateList of the text.
 * @prop linkColor Color for links in the text.
 * @prop highlightColor Color for an optional highlight of the text.
 * @prop highlightStartOffset Start offset for an optional highlight of the text.
 * @prop highlightEndOffset End offset for an optional highlight of the text.
 * @prop textSize Size of the text.
 * @prop extraSpacing Extra spacing between the lines of text.
 * @prop spacingMultiplier Extra spacing between the lines of text, as a multiplier.
 * @prop letterSpacing Text letter-spacing. Typical values for slight expansion will be around 0.05
 *   ems. Negative values tighten text.
 * @prop textStyle Style for the font (e.g. [Typeface.BOLD]). See the @Style interface in [Typeface]
 *   for supported attributes.
 * @prop typeface Typeface for the text.
 * @prop textAlignment Alignment of the text within its container.
 * @prop breakStrategy Break strategy to use for multi-line text.
 * @prop hyphenationFrequency How frequently to hyphenate text.
 * @prop justificationMode How to justify the text. See [android.text.Layout]
 * @prop glyphWarming If set, pre-renders the text to an off-screen Canvas to boost performance.
 * @prop textDirection Heuristic to use to determine the direction of the text.
 * @prop shouldIncludeFontPadding If set, uses extra padding for ascenders and descenders.
 * @prop verticalGravity Vertical gravity for the text within its container.
 * @prop clickableSpanExpandedOffset Click offset amount to determine how far off the ClickableSpan
 *   bounds user can click to be able to trigger ClickableSpan's click action. This could be useful
 *   in a densely lined text with links like 'Continue reading ...' in NewsFeed to be able to click
 *   that easily.
 * @prop spanListener Listener to override click and/or longclick actions of spannables extracted
 *   from text. This can be used to avoid memory leaks if the click/long click actions require a
 *   context, since spannables are stored statically in memory.
 * @prop touchableSpanListener Listener to listen for touch down actions of spannables extracted
 *   from text. This can be used to avoid memory leaks if the touch down actions require a context,
 *   since spannables are stored statically in memory.
 * @prop clipToBounds If the text should be clipped inside component bounds. Default: `true`
 * @prop customEllipsisText Text used to replace the standard Android ... ellipsis at the end of
 *   truncated lines. Warning: specifying this prop causes measurement to run twice. This can have a
 *   serious performance cost, especially on older devices!
 * @prop textOffsetOnTouchHandler A handler for touch events that need to know their character
 *   offset into the text. Will only fire on ACTION_DOWN events that occur at an index within the
 *   text.
 * @prop accessibleClickableSpans Whether the text can contain accessible clickable spans.
 * @prop minimallyWide If set, multi-line text width is determined by the widest line, rather than
 *   the overall layout width. This can eliminate empty space in word-wrapped text with line breaks
 *   preceding lengthy words or spans.
 * @prop minimallyWideThreshold If set, `minimallyWide` logic will not run for text whose minimal
 *   width is smaller than its normal width by less than the threshold.
 * @prop lineHeight Controls the line height of text (the amount of vertical space reserved for each
 *   line of text).
 */
@ExcuseMySpec(reason = Reason.J2K_CONVERSION)
@Deprecated("Use RichText instead")
@MountSpec(
    isPureRender = true,
    hasIntrinsicSize = true,
    poolSize = 30,
    canPreallocate = true,
    events = [TextOffsetOnTouchEvent::class],
)
object TextComponentSpec {
  @Dimension(unit = SP) val DEFAULT_TEXT_SIZE_SP: Int = 14
  const val UNSET: Int = -1

  val DEFAULT_TYPEFACE: Typeface = Typeface.DEFAULT
  const val DEFAULT_COLOR: Int = 0
  private const val TAG = "TextComponentSpec"
  private const val WRONG_TEXT_SIZE = "TextComponentSpec:WrongTextSize"
  private const val MAX_CUSTOM_ELLIPSIS_STEP_BACKS = 16

  val DEFAULT_TEXT_COLOR_STATE_LIST_STATES: Array<IntArray> = arrayOf(intArrayOf(0))
  val DEFAULT_TEXT_COLOR_STATE_LIST_COLORS: IntArray = intArrayOf(Color.BLACK)

  @PropDefault val minLines: Int = Int.MIN_VALUE
  @PropDefault val maxLines: Int = Int.MAX_VALUE
  @PropDefault val minEms: Int = DEFAULT_EMS
  @PropDefault val maxEms: Int = DEFAULT_EMS
  @PropDefault val minTextWidth: Int = DEFAULT_MIN_WIDTH
  @PropDefault val maxTextWidth: Int = DEFAULT_MAX_WIDTH
  @PropDefault val shadowColor: Int = Color.GRAY
  @PropDefault val outlineWidth: Float = 0f
  @PropDefault val outlineColor: Int = 0
  @PropDefault val textColor: Int = DEFAULT_COLOR
  @PropDefault val linkColor: Int = Color.BLUE

  @PropDefault
  val textColorStateList: ColorStateList =
      ColorStateList(DEFAULT_TEXT_COLOR_STATE_LIST_STATES, DEFAULT_TEXT_COLOR_STATE_LIST_COLORS)

  @PropDefault val textSize: Int = UNSET
  @PropDefault val textStyle: Int = DEFAULT_TYPEFACE.style
  @PropDefault val typeface: Typeface = DEFAULT_TYPEFACE
  @PropDefault val spacingMultiplier: Float = 1.0f
  @PropDefault val verticalGravity: VerticalGravity = VerticalGravity.TOP
  @PropDefault val glyphWarming: Boolean = false
  @PropDefault val shouldIncludeFontPadding: Boolean = true

  @PropDefault val breakStrategy: Int = DEFAULT_BREAK_STRATEGY
  @PropDefault val hyphenationFrequency: Int = DEFAULT_HYPHENATION_FREQUENCY
  @PropDefault val justificationMode: Int = DEFAULT_JUSTIFICATION_MODE
  @PropDefault val highlightStartOffset: Int = -1
  @PropDefault val highlightEndOffset: Int = -1
  @PropDefault val clipToBounds: Boolean = true
  @PropDefault val lineHeight: Float = Float.MAX_VALUE

  private val TempPath = Path()
  private val TempRect = Rect()
  private val TempRectF = RectF()

  @OnLoadStyle
  fun onLoadStyle(
      c: ComponentContext,
      ellipsize: Output<TextUtils.TruncateAt>?,
      extraSpacing: Output<Float>?,
      shouldIncludeFontPadding: Output<Boolean>?,
      spacingMultiplier: Output<Float>?,
      minLines: Output<Int>?,
      maxLines: Output<Int>?,
      minEms: Output<Int>?,
      maxEms: Output<Int>?,
      minTextWidth: Output<Int>?,
      maxTextWidth: Output<Int>?,
      isSingleLine: Output<Boolean>?,
      text: Output<CharSequence>?,
      textColorStateList: Output<ColorStateList>?,
      linkColor: Output<Int>?,
      highlightColor: Output<Int>?,
      textSize: Output<Int>?,
      alignment: Output<TextAlignment>?,
      breakStrategy: Output<Int>?,
      hyphenationFrequency: Output<Int>?,
      justificationMode: Output<Int>?,
      textStyle: Output<Int>?,
      shadowRadius: Output<Float>?,
      shadowDx: Output<Float>?,
      shadowDy: Output<Float>?,
      shadowColor: Output<Int>?,
      verticalGravity: Output<VerticalGravity>?,
      typeface: Output<Typeface>?,
  ) {
    TextStylesHelper.onLoadStyle(
        c,
        ellipsize,
        extraSpacing,
        shouldIncludeFontPadding,
        spacingMultiplier,
        minLines,
        maxLines,
        minEms,
        maxEms,
        minTextWidth,
        maxTextWidth,
        isSingleLine,
        text,
        textColorStateList,
        linkColor,
        highlightColor,
        textSize,
        alignment,
        breakStrategy,
        hyphenationFrequency,
        justificationMode,
        textStyle,
        shadowRadius,
        shadowDx,
        shadowDy,
        shadowColor,
        verticalGravity,
        typeface,
    )
  }

  @OnMeasure
  fun onMeasure(
      context: ComponentContext,
      layout: ComponentLayout,
      widthSpec: Int,
      heightSpec: Int,
      size: Size,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true, resType = ResType.BOOL) shouldIncludeFontPadding: Boolean,
      @Prop(optional = true, resType = ResType.INT) minLines: Int,
      @Prop(optional = true, resType = ResType.INT) maxLines: Int,
      @Prop(optional = true, resType = ResType.INT) minEms: Int,
      @Prop(optional = true, resType = ResType.INT) maxEms: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) minTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) maxTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowRadius: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDx: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDy: Float,
      @Prop(optional = true, resType = ResType.COLOR) shadowColor: Int,
      @Prop(optional = true, resType = ResType.BOOL) isSingleLine: Boolean,
      @Prop(optional = true, resType = ResType.COLOR) textColor: Int,
      @Prop(optional = true) textColorStateList: ColorStateList?,
      @Prop(optional = true, resType = ResType.COLOR) linkColor: Int,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) textSize: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) extraSpacing: Float,
      @Prop(optional = true, resType = ResType.FLOAT) spacingMultiplier: Float,
      @Prop(optional = true, resType = ResType.FLOAT) letterSpacing: Float,
      @Prop(optional = true) textStyle: Int,
      @Prop(optional = true) typeface: Typeface?,
      @Prop(optional = true) @java.lang.Deprecated textAlignment: Layout.Alignment?,
      @Prop(optional = true) alignment: TextAlignment?,
      @Prop(optional = true) breakStrategy: Int,
      @Prop(optional = true) hyphenationFrequency: Int,
      @Prop(optional = true) justificationMode: Int,
      @Prop(optional = true) glyphWarming: Boolean,
      @Prop(optional = true) textDirection: TextDirectionHeuristicCompat?,
      @Prop(optional = true) minimallyWide: Boolean,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) minimallyWideThreshold: Int,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) lineHeight: Float,
      measureLayout: Output<Layout?>,
      measuredWidth: Output<Int?>,
      measuredHeight: Output<Int?>,
      fullWidth: Output<Int?>,
  ) {
    if (text.isNullOrEmpty()) {
      measureLayout.set(null)
      size.width = 0
      size.height = 0
      return
    }

    val resolvedTextAlignment = getTextAlignment(textAlignment, alignment)

    val newLayout = createTextLayout(
        context,
        widthSpec,
        ellipsize,
        shouldIncludeFontPadding,
        maxLines,
        shadowRadius,
        shadowDx,
        shadowDy,
        shadowColor,
        isSingleLine,
        text,
        textColor,
        textColorStateList,
        linkColor,
        textSize,
        extraSpacing,
        spacingMultiplier,
        letterSpacing,
        textStyle,
        typeface,
        resolvedTextAlignment,
        glyphWarming,
        layout.resolvedLayoutDirection,
        minEms,
        maxEms,
        minTextWidth,
        maxTextWidth,
        context.androidContext.resources.displayMetrics.density,
        breakStrategy,
        hyphenationFrequency,
        justificationMode,
        textDirection,
        lineHeight,
    )
    measureLayout.set(newLayout)

    fullWidth.set(max(0, resolveSize(widthSpec, newLayout.width)))
    size.width = resolveWidth(widthSpec, newLayout, minimallyWide, minimallyWideThreshold)

    // Adjust height according to the minimum number of lines.
    var preferredHeight = LayoutMeasureUtil.getHeight(newLayout)
    val lineCount = newLayout.lineCount
    if (lineCount < minLines) {
      val paint = newLayout.paint

      val layoutLineHeight =
          Math.round(paint.getFontMetricsInt(null) * spacingMultiplier + extraSpacing)
      preferredHeight += layoutLineHeight * (minLines - lineCount)
    }

    size.height = resolveSize(heightSpec, preferredHeight)

    // Some devices seem to be returning negative sizes in some cases.
    if (size.width < 0 || size.height < 0) {
      size.width = max(size.width, 0)
      size.height = max(size.height, 0)

      emitMessage(
          ComponentsReporter.LogLevel.ERROR,
          WRONG_TEXT_SIZE,
          "Text layout measured to less than 0 pixels",
      )
    }

    measuredWidth.set(size.width)
    measuredHeight.set(size.height)
  }

  @VisibleForTesting
  @JvmStatic
  fun resolveWidth(
      widthSpec: Int,
      layout: Layout,
      minimallyWide: Boolean,
      minimallyWideThreshold: Int,
  ): Int {
    val fullWidth = resolveSize(widthSpec, layout.width)

    if (minimallyWide && layout.lineCount > 1) {
      var leftMost = fullWidth.toFloat()
      var rightMost = 0f
      for (i in 0 until layout.lineCount) {
        leftMost = min(leftMost, layout.getLineLeft(i))
        rightMost = max(rightMost, layout.getLineRight(i))
      }
      // To determine the width of the longest line, which is also the minimum width we desire,
      // without leading and trailing whitespaces.
      val minimalWidth = resolveSize(widthSpec, (rightMost - leftMost).toInt())

      if (fullWidth - minimalWidth > minimallyWideThreshold) {
        return minimalWidth
      }
    }

    return fullWidth
  }

  /** Min-content width: the longest-word width (measured unbounded). */
  @OnMeasureIntrinsicWidth
  fun onMeasureIntrinsicWidth(
      c: ComponentContext,
      layout: ComponentLayout,
      widthSpec: Int,
      heightSpec: Int,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true, resType = ResType.BOOL) shouldIncludeFontPadding: Boolean,
      @Prop(optional = true, resType = ResType.INT) maxLines: Int,
      @Prop(optional = true, resType = ResType.INT) minEms: Int,
      @Prop(optional = true, resType = ResType.INT) maxEms: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) minTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) maxTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowRadius: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDx: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDy: Float,
      @Prop(optional = true, resType = ResType.COLOR) shadowColor: Int,
      @Prop(optional = true, resType = ResType.BOOL) isSingleLine: Boolean,
      @Prop(optional = true, resType = ResType.COLOR) textColor: Int,
      @Prop(optional = true) textColorStateList: ColorStateList?,
      @Prop(optional = true, resType = ResType.COLOR) linkColor: Int,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) textSize: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) extraSpacing: Float,
      @Prop(optional = true, resType = ResType.FLOAT) spacingMultiplier: Float,
      @Prop(optional = true, resType = ResType.FLOAT) letterSpacing: Float,
      @Prop(optional = true) textStyle: Int,
      @Prop(optional = true) typeface: Typeface?,
      @Prop(optional = true) @java.lang.Deprecated textAlignment: Layout.Alignment?,
      @Prop(optional = true) alignment: TextAlignment?,
      @Prop(optional = true) breakStrategy: Int,
      @Prop(optional = true) hyphenationFrequency: Int,
      @Prop(optional = true) justificationMode: Int,
      @Prop(optional = true) textDirection: TextDirectionHeuristicCompat?,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) lineHeight: Float,
  ): Int {
    if (text.isNullOrEmpty()) {
      return 0
    }
    val paint = createIntrinsicTextPaint(
        c,
        textSize,
        textStyle,
        typeface,
        letterSpacing,
        shadowRadius,
        shadowDx,
        shadowDy,
        shadowColor,
    )
    return TextLayoutIntrinsics.longestWordWidth(text, paint)
  }

  /** Min-content height: the natural single-line height (measured unbounded). */
  @OnMeasureIntrinsicHeight
  fun onMeasureIntrinsicHeight(
      c: ComponentContext,
      layout: ComponentLayout,
      widthSpec: Int,
      heightSpec: Int,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true, resType = ResType.BOOL) shouldIncludeFontPadding: Boolean,
      @Prop(optional = true, resType = ResType.INT) maxLines: Int,
      @Prop(optional = true, resType = ResType.INT) minEms: Int,
      @Prop(optional = true, resType = ResType.INT) maxEms: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) minTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) maxTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowRadius: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDx: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDy: Float,
      @Prop(optional = true, resType = ResType.COLOR) shadowColor: Int,
      @Prop(optional = true, resType = ResType.BOOL) isSingleLine: Boolean,
      @Prop(optional = true, resType = ResType.COLOR) textColor: Int,
      @Prop(optional = true) textColorStateList: ColorStateList?,
      @Prop(optional = true, resType = ResType.COLOR) linkColor: Int,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) textSize: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) extraSpacing: Float,
      @Prop(optional = true, resType = ResType.FLOAT) spacingMultiplier: Float,
      @Prop(optional = true, resType = ResType.FLOAT) letterSpacing: Float,
      @Prop(optional = true) textStyle: Int,
      @Prop(optional = true) typeface: Typeface?,
      @Prop(optional = true) @java.lang.Deprecated textAlignment: Layout.Alignment?,
      @Prop(optional = true) alignment: TextAlignment?,
      @Prop(optional = true) breakStrategy: Int,
      @Prop(optional = true) hyphenationFrequency: Int,
      @Prop(optional = true) justificationMode: Int,
      @Prop(optional = true) textDirection: TextDirectionHeuristicCompat?,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) lineHeight: Float,
  ): Int {
    if (text.isNullOrEmpty()) {
      return 0
    }
    val paint = createIntrinsicTextPaint(
        c,
        textSize,
        textStyle,
        typeface,
        letterSpacing,
        shadowRadius,
        shadowDx,
        shadowDy,
        shadowColor,
    )
    return TextLayoutIntrinsics.singleLineHeight(paint, shouldIncludeFontPadding)
  }

  /**
   * Builds a [TextPaint] with the advance-width-affecting properties [createTextLayout] applies,
   * without building a [Layout]. Must stay in sync with [createTextLayout]'s textSize/typeface
   * setters; textLocale is left at the default so [TextLayoutIntrinsics.longestWordWidth]'s
   * BreakIterator matches.
   */
  private fun createIntrinsicTextPaint(
      context: ComponentContext,
      textSize: Int,
      textStyle: Int,
      typeface: Typeface?,
      letterSpacing: Float,
      shadowRadius: Float,
      shadowDx: Float,
      shadowDy: Float,
      shadowColor: Int,
  ): TextPaint {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    paint.density = context.androidContext.resources.displayMetrics.density
    paint.textSize =
        if (textSize != UNSET) {
          textSize.toFloat()
        } else {
          context.resourceResolver.sipsToPixels(DEFAULT_TEXT_SIZE_SP.toFloat()).toFloat()
        }
    paint.letterSpacing = letterSpacing
    // Skip Paint.setTypeface for the framework default: a fresh TextPaint already measures with it,
    // and on some OEMs (e.g. MIUI) setTypeface triggers an expensive, class-locked variable-font
    // load — costly when this min-content probe runs on the main thread during list scrolling. Only
    // a non-default typeface (custom, or bold/italic default) needs to be set.
    val resolvedTypeface =
        if (DEFAULT_TYPEFACE != typeface) typeface else Typeface.defaultFromStyle(textStyle)
    if (resolvedTypeface != null && resolvedTypeface != Typeface.DEFAULT) {
      paint.typeface = resolvedTypeface
    }
    paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
    return paint
  }

  private fun createTextLayout(
      context: ComponentContext,
      widthSpec: Int,
      ellipsize: TextUtils.TruncateAt?,
      shouldIncludeFontPadding: Boolean,
      maxLines: Int,
      shadowRadius: Float,
      shadowDx: Float,
      shadowDy: Float,
      shadowColor: Int,
      isSingleLine: Boolean,
      text: CharSequence?,
      textColor: Int,
      textColorStateList: ColorStateList?,
      linkColor: Int,
      textSize: Int,
      extraSpacing: Float,
      spacingMultiplier: Float,
      letterSpacing: Float,
      textStyle: Int,
      typeface: Typeface?,
      textAlignment: TextAlignment,
      glyphWarming: Boolean,
      layoutDirection: YogaDirection,
      minEms: Int,
      maxEms: Int,
      minTextWidth: Int,
      maxTextWidth: Int,
      density: Float,
      breakStrategy: Int,
      hyphenationFrequency: Int,
      justificationMode: Int,
      textDirection: TextDirectionHeuristicCompat?,
      lineHeight: Float,
  ): Layout {
    var textDirectionToUse = textDirection
    val newLayout: Layout

    val layoutBuilder = TextLayoutBuilder()
    layoutBuilder.setShouldCacheLayout(false)
    @TextLayoutBuilder.MeasureMode
    val textMeasureMode =
        when (getMode(widthSpec)) {
          UNSPECIFIED -> TextLayoutBuilder.MEASURE_MODE_UNSPECIFIED
          EXACTLY -> TextLayoutBuilder.MEASURE_MODE_EXACTLY
          AT_MOST -> TextLayoutBuilder.MEASURE_MODE_AT_MOST
          else -> throw IllegalStateException("Unexpected size mode: " + getMode(widthSpec))
        }
    val actualEllipsize =
        if (ellipsize == null && maxLines != Int.MAX_VALUE) {
          // On recent apis (> 24) max lines is no longer considered for calculating layout height
          // if an
          // ellipsize method isn't specified. To keep consistent behavior across platforms we
          // default
          // to end if you specify maxLines but not ellipsize.
          TextUtils.TruncateAt.END
        } else {
          ellipsize
        }

    layoutBuilder
        .setDensity(density)
        .setEllipsize(actualEllipsize)
        .setMaxLines(maxLines)
        .setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        .setSingleLine(isSingleLine)
        .setText(text)
        .setWidth(getSize(widthSpec), textMeasureMode)
        .setIncludeFontPadding(shouldIncludeFontPadding)
        .setTextSpacingExtra(extraSpacing)
        .setTextSpacingMultiplier(spacingMultiplier)
        .setLinkColor(linkColor)
        .setJustificationMode(justificationMode)
        .setBreakStrategy(breakStrategy)
        .setHyphenationFrequency(hyphenationFrequency)

    // text size must be set before the line hight
    if (textSize != UNSET) {
      layoutBuilder.setTextSize(textSize)
    } else {
      val defaultTextSize = context.resourceResolver.sipsToPixels(DEFAULT_TEXT_SIZE_SP.toFloat())
      layoutBuilder.setTextSize(defaultTextSize)
    }

    if (lineHeight != Float.MAX_VALUE) {
      layoutBuilder.setLineHeight(lineHeight)
    }

    layoutBuilder.setLetterSpacing(letterSpacing)

    if (minEms != DEFAULT_EMS) {
      layoutBuilder.setMinEms(minEms)
    } else {
      layoutBuilder.setMinWidth(minTextWidth)
    }

    if (maxEms != DEFAULT_EMS) {
      layoutBuilder.setMaxEms(maxEms)
    } else {
      layoutBuilder.setMaxWidth(maxTextWidth)
    }

    if (textColor != DEFAULT_COLOR) {
      layoutBuilder.setTextColor(textColor)
    } else {
      layoutBuilder.setTextColor(textColorStateList)
    }

    if (DEFAULT_TYPEFACE != typeface) {
      layoutBuilder.setTypeface(typeface)
    } else {
      layoutBuilder.setTextStyle(textStyle)
    }

    textDirectionToUse = getTextDirection(textDirectionToUse, layoutDirection)
    layoutBuilder.setTextDirection(textDirectionToUse)
    layoutBuilder.setAlignment(
        getLayoutAlignment(textAlignment, textDirectionToUse, text, layoutDirection),
    )

    try {
      newLayout = checkNotNull(layoutBuilder.build())
    } catch (e: ArrayIndexOutOfBoundsException) { // To capture more info for T102756635
      throw RuntimeException("text: " + text.toString(), e)
    }

    if (glyphWarming) {
      instance.warmLayout(newLayout)
    }

    return newLayout
  }

  @OnBoundsDefined
  fun onBoundsDefined(
      c: ComponentContext,
      layout: ComponentLayout,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @Prop(optional = true) ellipsize: TextUtils.TruncateAt?,
      @Prop(optional = true, resType = ResType.BOOL) shouldIncludeFontPadding: Boolean,
      @Prop(optional = true, resType = ResType.INT) maxLines: Int,
      @Prop(optional = true, resType = ResType.INT) minEms: Int,
      @Prop(optional = true, resType = ResType.INT) maxEms: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) minTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) maxTextWidth: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowRadius: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDx: Float,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) shadowDy: Float,
      @Prop(optional = true, resType = ResType.COLOR) shadowColor: Int,
      @Prop(optional = true, resType = ResType.BOOL) isSingleLine: Boolean,
      @Prop(optional = true, resType = ResType.COLOR) textColor: Int,
      @Prop(optional = true) textColorStateList: ColorStateList?,
      @Prop(optional = true, resType = ResType.COLOR) linkColor: Int,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) textSize: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) extraSpacing: Float,
      @Prop(optional = true, resType = ResType.FLOAT) spacingMultiplier: Float,
      @Prop(optional = true, resType = ResType.FLOAT) letterSpacing: Float,
      @Prop(optional = true) verticalGravity: VerticalGravity?,
      @Prop(optional = true) textStyle: Int,
      @Prop(optional = true) typeface: Typeface?,
      @Prop(optional = true) @java.lang.Deprecated textAlignment: Layout.Alignment?,
      @Prop(optional = true) alignment: TextAlignment?,
      @Prop(optional = true) breakStrategy: Int,
      @Prop(optional = true) hyphenationFrequency: Int,
      @Prop(optional = true) glyphWarming: Boolean,
      @Prop(optional = true) textDirection: TextDirectionHeuristicCompat?,
      @Prop(optional = true, resType = ResType.STRING) customEllipsisText: CharSequence?,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) lineHeight: Float,
      @Prop(optional = true) minimallyWide: Boolean,
      @FromMeasure measureLayout: Layout?,
      @FromMeasure measuredWidth: Int?,
      @FromMeasure measuredHeight: Int?,
      @FromMeasure fullWidth: Int?,
      processedText: Output<CharSequence?>,
      textLayout: Output<Layout?>,
      textLayoutTranslationX: Output<Float?>,
      textLayoutTranslationY: Output<Float?>,
      clickableSpans: Output<Array<ClickableSpan?>?>,
      imageSpans: Output<Array<ImageSpan?>?>,
      isExplicitlyTruncated: Output<Boolean>,
  ) {
    processedText.set(text)
    // Set true only when we pre-truncate the text for a custom ellipsis below. A standard ellipsize
    // leaves the full text in place and is detected from the layout's ellipsis metadata instead.
    isExplicitlyTruncated.set(false)
    if (text.isNullOrEmpty()) {
      return
    }

    val layoutWidth = (layout.width - layout.paddingLeft - layout.paddingRight).toFloat()
    val layoutHeight = (layout.height - layout.paddingTop - layout.paddingBottom).toFloat()

    if (
        measureLayout != null &&
            (measuredWidth?.toFloat() ?: 0f) == layoutWidth &&
            (measuredHeight?.toFloat() ?: 0f) == layoutHeight
    ) {
      textLayout.set(measureLayout)
      // We don't need to perform translation if we didn't pass minimally wide threshold above
      if (minimallyWide && fullWidth != measuredWidth) {
        // Regardless of the text alignment, we can always use the leftmost point (the longest line)
        // as our starting point to keep the text drawable center-aligned.
        var leftMost = measuredWidth?.toFloat() ?: 0f
        var i = 0
        val count = measureLayout.lineCount
        while (i < count) {
          leftMost = min(leftMost, measureLayout.getLineLeft(i))
          i++
        }
        textLayoutTranslationX.set(-leftMost)
      }
    } else {
      textLayout.set(
          createTextLayout(
              c,
              makeSizeSpec(layoutWidth.toInt(), EXACTLY),
              ellipsize,
              shouldIncludeFontPadding,
              maxLines,
              shadowRadius,
              shadowDx,
              shadowDy,
              shadowColor,
              isSingleLine,
              text,
              textColor,
              textColorStateList,
              linkColor,
              textSize,
              extraSpacing,
              spacingMultiplier,
              letterSpacing,
              textStyle,
              typeface,
              getTextAlignment(textAlignment, alignment),
              glyphWarming,
              layout.resolvedLayoutDirection,
              minEms,
              maxEms,
              minTextWidth,
              maxTextWidth,
              c.androidContext.resources.displayMetrics.density,
              breakStrategy,
              hyphenationFrequency,
              justificationMode,
              textDirection,
              lineHeight,
          ),
      )
    }

    val textHeight = LayoutMeasureUtil.getHeight(textLayout.get()).toFloat()

    when (verticalGravity) {
      VerticalGravity.CENTER -> textLayoutTranslationY.set((layoutHeight - textHeight) / 2)
      VerticalGravity.BOTTOM -> textLayoutTranslationY.set(layoutHeight - textHeight)
      else -> textLayoutTranslationY.set(0f)
    }
    // Handle custom text truncation:
    if (customEllipsisText != null && customEllipsisText != "") {
      val ellipsizedLineNumber = getEllipsizedLineNumber(textLayout.get())
      if (ellipsizedLineNumber != -1) {
        val customEllipsisLayout = createTextLayout(
            c,
            makeSizeSpec(layoutWidth.toInt(), EXACTLY),
            ellipsize,
            shouldIncludeFontPadding,
            maxLines,
            shadowRadius,
            shadowDx,
            shadowDy,
            shadowColor,
            isSingleLine,
            customEllipsisText,
            textColor,
            textColorStateList,
            linkColor,
            textSize,
            extraSpacing,
            spacingMultiplier,
            letterSpacing,
            textStyle,
            typeface,
            getTextAlignment(textAlignment, alignment),
            glyphWarming,
            layout.resolvedLayoutDirection,
            minEms,
            maxEms,
            minTextWidth,
            maxTextWidth,
            c.androidContext.resources.displayMetrics.density,
            breakStrategy,
            hyphenationFrequency,
            justificationMode,
            textDirection,
            lineHeight,
        )

        val layoutDirection = layout.resolvedLayoutDirection
        val finalTextDirection = getTextDirection(textDirection, layoutDirection)
        val finalLayoutAlignment = customEllipsisLayout.alignment
        val isRtl: Boolean = finalTextDirection.isRtl(text, 0, text.length) == true
        val isAlignedLeft = isRtl xor (finalLayoutAlignment == Layout.Alignment.ALIGN_NORMAL)
        var truncated = truncateText(
            text,
            customEllipsisText,
            checkNotNull(textLayout.get()),
            customEllipsisLayout,
            ellipsizedLineNumber,
            layoutWidth,
            isAlignedLeft,
            isRtl,
            layoutDirection,
        )

        val layoutTruncatedText = { candidate: CharSequence ->
          createTextLayout(
              c,
              makeSizeSpec(layoutWidth.toInt(), EXACTLY),
              null,
              shouldIncludeFontPadding,
              Int.MAX_VALUE,
              shadowRadius,
              shadowDx,
              shadowDy,
              shadowColor,
              isSingleLine,
              candidate,
              textColor,
              textColorStateList,
              linkColor,
              textSize,
              extraSpacing,
              spacingMultiplier,
              letterSpacing,
              textStyle,
              typeface,
              getTextAlignment(textAlignment, alignment),
              glyphWarming,
              layout.resolvedLayoutDirection,
              minEms,
              maxEms,
              minTextWidth,
              maxTextWidth,
              c.androidContext.resources.displayMetrics.density,
              breakStrategy,
              hyphenationFrequency,
              justificationMode,
              textDirection,
              lineHeight,
          )
        }
        var newLayout = layoutTruncatedText(truncated)
        if (
            truncated !== text &&
                newLayout.lineCount > ellipsizedLineNumber + 1 &&
                (ComponentsConfiguration.fitCustomEllipsisOnTruncatedLine ||
                    layoutDirection == YogaDirection.RTL)
        ) {
          val fitting = fitCustomEllipsisOnLine(
              text,
              customEllipsisText,
              truncated.length - customEllipsisText.length,
              ellipsizedLineNumber,
              layoutTruncatedText,
          )
          if (fitting != null) {
            truncated = fitting.first
            newLayout = fitting.second
          }
        }

        processedText.set(truncated)
        textLayout.set(newLayout)
        isExplicitlyTruncated.set(true)
      }
    }

    val resultText = processedText.get()
    if (resultText is Spanned) {
      val spanned = resultText
      clickableSpans.set(spanned.getSpans(0, resultText.length, ClickableSpan::class.java))
      imageSpans.set(spanned.getSpans(0, resultText.length, ImageSpan::class.java))
    }
  }

  /**
   * Truncates text which is too long and appends the given custom ellipsis CharSequence to the end
   * of the visible text.
   *
   * @param text Text to truncate
   * @param customEllipsisText Text to append to the end to indicate truncation happened
   * @param newLayout A Layout object populated with measurement information for this text
   * @param ellipsisTextLayout A Layout object populated with measurement information for the
   *   ellipsis text.
   * @param ellipsizedLineNumber The line number within the text at which truncation occurs (i.e.
   *   the last visible line).
   * @return The provided text truncated in such a way that the 'customEllipsisText' can appear at
   *   the end.
   */
  @VisibleForTesting
  @JvmStatic
  @JvmOverloads
  fun truncateText(
      text: CharSequence,
      customEllipsisText: CharSequence,
      newLayout: Layout,
      ellipsisTextLayout: Layout,
      ellipsizedLineNumber: Int,
      layoutWidth: Float,
      isAlignedLeft: Boolean,
      isRtl: Boolean,
      layoutDirection: YogaDirection = YogaDirection.LTR,
  ): CharSequence {
    val customEllipsisTextWidth = ellipsisTextLayout.getLineWidth(0)
    // Identify the X position at which to truncate the final line:
    val ellipsisTarget: Float
    if (!isRtl && isAlignedLeft) {
      ellipsisTarget = layoutWidth - customEllipsisTextWidth
    } else if (!isRtl /* && !isAlignedLeft */) {
      val gap = layoutWidth - newLayout.getLineWidth(ellipsizedLineNumber)
      ellipsisTarget = layoutWidth - customEllipsisTextWidth + gap
    } else if (/* isRtl && */ isAlignedLeft) {
      val gap = layoutWidth - newLayout.getLineWidth(ellipsizedLineNumber)
      ellipsisTarget = customEllipsisTextWidth - gap
    } else /* isRtl && !isAlignedLeft */ {
      ellipsisTarget = customEllipsisTextWidth
    }
    // Get character offset at ellipsisTarget. For RTL, getOffsetForAdvance expects
    // run-relative advances (from right edge), not left-edge X coordinates like ellipsisTarget.
    // Convert ellipsisTarget to run-relative advance for RTL.
    val isRtlLayout = layoutDirection == YogaDirection.RTL
    val fixRtlTruncation =
        !isRtlLayout && isRtl && ComponentsConfiguration.fixRtlCustomEllipsisTruncation
    var ellipsisOffset: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          val advance =
              when {
                isRtlLayout -> layoutWidth - customEllipsisTextWidth
                fixRtlTruncation -> layoutWidth - ellipsisTarget
                else -> ellipsisTarget
              }
          val offset = getEllipsisOffsetFromPaintAdvance(
              newLayout,
              text,
              isRtl,
              ellipsizedLineNumber,
              advance,
          )
          // The advance conversion for RTL rounds inward by one character; adjust to avoid
          // over-truncation. This is safe because ellipsisStart bounds check below prevents
          // exceeding the line.
          if (fixRtlTruncation && offset > 0) offset + 1 else offset
        } else {
          newLayout.getOffsetForHorizontal(ellipsizedLineNumber, ellipsisTarget)
        }

    if (ellipsisOffset > 0) {
      // Since the offset adjustment was for the original implementation with
      // [Layout.getOffsetForHorizontal], now we've moved to using [Paint.getOffsetForAdvance] which
      // returns different value from the previous one, so we don't need it anymore.

      if (!ComponentsConfiguration.enableFixForTextEllipsisOffset) {
        // getOffsetForHorizontal returns the closest character, but we need to guarantee no
        // truncation, so subtract 1 from the result:
        ellipsisOffset -= 1
      }

      // Ensure that we haven't chosen an ellipsisOffset that's past the end of the ellipsis start.
      // This can occur in several cases, including when the width of the customEllipsisText is less
      // than the width of the default ellipsis character, and when in RTL mode and there is
      // whitespace to the left of the text. In these cases, getOffsetForHorizontal will return the
      // end of the string because our ellipsisTarget was in the middle of the ellipsis character.
      if (newLayout.getEllipsisCount(ellipsizedLineNumber) > 0) {
        val ellipsisStart =
            (newLayout.getLineStart(ellipsizedLineNumber) +
                newLayout.getEllipsisStart(ellipsizedLineNumber))
        if (ellipsisOffset > ellipsisStart) {
          ellipsisOffset = ellipsisStart
        }
      }

      if (ellipsisOffset < 0) {
        ellipsisOffset = 0
      } else if (ellipsisOffset > text.length) {
        ellipsisOffset = text.length
      }

      ellipsisOffset =
          clampEllipsisOffsetToReplacementSpanBoundary(
              text,
              newLayout,
              ellipsizedLineNumber,
              ellipsisTarget,
              ellipsisOffset,
          )

      if (
          ellipsisOffset != 0 &&
              ellipsisOffset != text.length &&
              Character.isLowSurrogate(text[ellipsisOffset])
      ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ellipsisOffset = breakIteratorGetPreceding(text, ellipsisOffset)
        } else {
          val iterator = BreakIterator.getCharacterInstance()
          iterator.setText(text.toString())
          ellipsisOffset = iterator.preceding(ellipsisOffset)
        }
      }

      return TextUtils.concat(text.subSequence(0, ellipsisOffset), customEllipsisText)
    } else {
      return text
    }
  }

  private fun clampEllipsisOffsetToReplacementSpanBoundary(
      text: CharSequence,
      layout: Layout,
      ellipsizedLineNumber: Int,
      ellipsisTarget: Float,
      ellipsisOffset: Int,
  ): Int {
    val spannedText = text as? Spanned ?: return ellipsisOffset
    val lineStart = layout.getLineStart(ellipsizedLineNumber)
    val lineEnd = layout.getLineEnd(ellipsizedLineNumber)
    // Avoid Layout.getOffsetForHorizontal's ANR-prone binary search and use Layout's span-aware
    // boundary positions instead of measuring each span again.
    val spanStart =
        spannedText
            .getSpans(lineStart, lineEnd, ReplacementSpan::class.java)
            .mapNotNull { span ->
              val start = spannedText.getSpanStart(span)
              val end = spannedText.getSpanEnd(span)
              if (start < lineStart || end > lineEnd || start >= end) {
                return@mapNotNull null
              }
              val startX = layout.getPrimaryHorizontal(start)
              val endX = layout.getPrimaryHorizontal(end)
              val targetInsideSpan =
                  if (startX <= endX) {
                    ellipsisTarget >= startX && ellipsisTarget < endX
                  } else {
                    ellipsisTarget <= startX && ellipsisTarget > endX
                  }
              if (targetInsideSpan || ellipsisOffset > start && ellipsisOffset < end) {
                start
              } else {
                null
              }
            }
            .minOrNull()
    return if (spanStart == null) ellipsisOffset else minOf(ellipsisOffset, spanStart)
  }

  /**
   * @param layout A prepared text layout object
   * @return The (zero-indexed) line number at which the text in this layout will be ellipsized, or
   *   -1 if no line will be ellipsized.
   */
  private fun getEllipsizedLineNumber(layout: Layout?): Int {
    if (layout != null) {
      for (i in 0 until layout.lineCount) {
        if (layout.getEllipsisCount(i) > 0) {
          return i
        }
      }
    }
    return -1
  }

  /**
   * Shortens the prefix kept before [customEllipsisText] one grapheme at a time until the custom
   * ellipsis fits on [lastVisibleLine]. If the step-back limit is reached, the longest fitting
   * shorter prefix is binary searched. Returns null if no prefix fits.
   */
  private fun fitCustomEllipsisOnLine(
      text: CharSequence,
      customEllipsisText: CharSequence,
      prefixLength: Int,
      lastVisibleLine: Int,
      layoutFor: (CharSequence) -> Layout,
  ): Pair<CharSequence, Layout>? {
    val graphemes = BreakIterator.getCharacterInstance()
    graphemes.setText(text.toString())
    var prefix = prefixLength
    repeat(MAX_CUSTOM_ELLIPSIS_STEP_BACKS) {
      prefix = precedingUnsplitBoundary(text, graphemes, prefix)
      if (prefix == BreakIterator.DONE) {
        return null
      }
      val candidate = TextUtils.concat(text.subSequence(0, prefix), customEllipsisText)
      val candidateLayout = layoutFor(candidate)
      if (candidateLayout.lineCount <= lastVisibleLine + 1) {
        return Pair(candidate, candidateLayout)
      }
    }
    val boundaries = ArrayList<Int>()
    var offset = 0
    while (offset != BreakIterator.DONE && offset < prefix) {
      if (!isInsideReplacementSpan(text, offset)) {
        boundaries.add(offset)
      }
      offset = graphemes.following(offset)
    }
    var best: Pair<CharSequence, Layout>? = null
    var low = 0
    var high = boundaries.size - 1
    while (low <= high) {
      val middle = (low + high) ushr 1
      val candidate = TextUtils.concat(text.subSequence(0, boundaries[middle]), customEllipsisText)
      val candidateLayout = layoutFor(candidate)
      if (candidateLayout.lineCount <= lastVisibleLine + 1) {
        best = Pair(candidate, candidateLayout)
        low = middle + 1
      } else {
        high = middle - 1
      }
    }
    return best
  }

  /**
   * The grapheme boundary before [offset] that is not inside a [ReplacementSpan] (such as an
   * [ImageSpan]), or [BreakIterator.DONE] if there is none.
   */
  private fun precedingUnsplitBoundary(
      text: CharSequence,
      graphemes: BreakIterator,
      offset: Int,
  ): Int {
    var boundary = if (offset > 0) graphemes.preceding(offset) else BreakIterator.DONE
    while (boundary > 0 && isInsideReplacementSpan(text, boundary)) {
      boundary = graphemes.preceding(boundary)
    }
    return boundary
  }

  private fun isInsideReplacementSpan(text: CharSequence, offset: Int): Boolean {
    val spanned = text as? Spanned ?: return false
    return spanned.getSpans(offset, offset, ReplacementSpan::class.java).any { span ->
      spanned.getSpanStart(span) < offset && offset < spanned.getSpanEnd(span)
    }
  }

  @OnCreateMountContent
  fun onCreateMountContent(c: Context?): TextDrawable {
    return TextDrawable()
  }

  @OnMount
  fun onMount(
      c: ComponentContext,
      textDrawable: TextDrawable,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @Prop(optional = true, resType = ResType.COLOR) textColor: Int,
      @Prop(optional = true, resType = ResType.COLOR) highlightColor: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) outlineWidth: Float,
      @Prop(optional = true, resType = ResType.COLOR) outlineColor: Int,
      @Prop(optional = true) textColorStateList: ColorStateList?,
      @Prop(optional = true) textOffsetOnTouchHandler: EventHandler<*>?,
      @Prop(optional = true) highlightStartOffset: Int,
      @Prop(optional = true) highlightEndOffset: Int,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) clickableSpanExpandedOffset: Float,
      @Prop(optional = true) clipToBounds: Boolean,
      @Prop(optional = true) spanListener: ClickableSpanListener?,
      @Prop(optional = true) touchableSpanListener: TouchableSpanListener?,
      @FromBoundsDefined processedText: CharSequence?,
      @FromBoundsDefined textLayout: Layout?,
      @FromBoundsDefined textLayoutTranslationX: Float?,
      @FromBoundsDefined textLayoutTranslationY: Float?,
      @FromBoundsDefined clickableSpans: Array<ClickableSpan?>?,
      @FromBoundsDefined imageSpans: Array<ImageSpan?>?,
      @FromBoundsDefined isExplicitlyTruncated: Boolean,
  ) {
    val componentScope = c.componentScope
    componentScope?.setDebugAttributeKey(WidgetAttributes.Text, listOf(processedText ?: ""))

    var textOffsetOnTouchListener: TextOffsetOnTouchListener? = null

    if (textOffsetOnTouchHandler != null) {
      textOffsetOnTouchListener = TextOffsetOnTouchListener { textOffset ->
        TextComponent.dispatchTextOffsetOnTouchEvent(
            textOffsetOnTouchHandler,
            processedText,
            textOffset,
        )
      }
    }
    textDrawable.mount(
        processedText,
        textLayout,
        textLayoutTranslationX ?: 0f,
        textLayoutTranslationY ?: 0f,
        clipToBounds,
        textColorStateList,
        textColor,
        highlightColor,
        outlineWidth,
        outlineColor,
        clickableSpans?.filterNotNull()?.toTypedArray(),
        imageSpans?.filterNotNull()?.toTypedArray(),
        spanListener,
        touchableSpanListener,
        textOffsetOnTouchListener,
        highlightStartOffset,
        highlightEndOffset,
        clickableSpanExpandedOffset,
        c.logTag,
        // The original, pre-truncation text. For a custom ellipsis `processedText` is the truncated
        // string actually drawn, so this is the only place the full text survives.
        text,
        // Whether we pre-truncated for a custom ellipsis (a standard ellipsize is detected from the
        // layout). Lets the dumper report truncation without comparing text contents.
        isExplicitlyTruncated,
    )

    // Custom ellipsis creates a new CharSequence containing the original spans. Mount the original
    // sequence when that copy no longer carries its lifecycle contract; the copied image spans
    // still share the same backing drawables.
    ((processedText as? MountableCharSequence) ?: (text as? MountableCharSequence))?.onMount(
        textDrawable,
    )
  }

  @OnUnmount
  fun onUnmount(
      c: ComponentContext?,
      textDrawable: TextDrawable,
      @Prop(resType = ResType.STRING) text: CharSequence?,
  ) {
    textDrawable.unmount()

    if (text is MountableCharSequence) {
      text.onUnmount(textDrawable)
    }
  }

  @OnPopulateAccessibilityNode
  fun onPopulateAccessibilityNode(
      c: ComponentContext?,
      host: View?,
      node: AccessibilityNodeInfoCompat,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @Prop(optional = true, resType = ResType.BOOL) isSingleLine: Boolean,
  ) {
    if (
        host != null &&
            ViewCompat.getImportantForAccessibility(host) ==
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    ) {
      ViewCompat.setImportantForAccessibility(host, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES)
    }

    val contentDescription = node.contentDescription
    val textWithContentDescriptions = text?.let { replaceContentDescriptionSpans(text) }
    node.text = contentDescription ?: textWithContentDescriptions
    node.contentDescription = contentDescription ?: textWithContentDescriptions

    node.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
    node.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
    node.movementGranularities =
        (AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER or
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD or
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH)

    if (!isSingleLine) {
      node.isMultiLine = true
    }
  }

  /**
   * Takes a text input, searches through it for any ContentDescriptionSpans, and if any are found
   * replaces the text at the spanned location with the text defined in the content description.
   * This is meant to generate text to set directly on the AccessibilityNodeInfo, not text for
   * display.
   *
   * @param text Text to modify.
   * @return The input text with the content of all ContentDescriptionSpans included.
   */
  private fun replaceContentDescriptionSpans(text: CharSequence): CharSequence {
    if (text !is Spanned) {
      return text
    }

    val contentDescriptionSpans = text.getSpans(0, text.length, ContentDescriptionSpan::class.java)

    if (contentDescriptionSpans.isEmpty()) {
      return text
    }

    val spannable = SpannableStringBuilder(text)
    for (span in contentDescriptionSpans) {
      val replacementText: CharSequence? = span.contentDescription
      if (replacementText.isNullOrEmpty()) {
        continue
      }
      val spanReplaceStart = spannable.getSpanStart(span)
      val spanReplaceEnd = spannable.getSpanEnd(span)
      spannable.replace(spanReplaceStart, spanReplaceEnd, replacementText)
    }

    return spannable.toString()
  }

  @GetExtraAccessibilityNodesCount
  fun getExtraAccessibilityNodesCount(
      c: ComponentContext?,
      @Prop(optional = true, resType = ResType.BOOL) accessibleClickableSpans: Boolean,
      @FromBoundsDefined clickableSpans: Array<ClickableSpan?>?,
  ): Int {
    return if ((accessibleClickableSpans && clickableSpans != null)) clickableSpans.size else 0
  }

  @OnPerformActionForVirtualView
  fun onPerformActionForVirtualView(
      c: ComponentContext?,
      host: View?,
      accessibilityNode: AccessibilityNodeInfoCompat?,
      virtualViewId: Int,
      action: Int,
      arguments: Bundle?,
      @FromBoundsDefined clickableSpans: Array<ClickableSpan>,
  ): Boolean {
    if (host != null && action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
      clickableSpans[virtualViewId].onClick(host)
      return true
    }
    return false
  }

  @OnVirtualViewKeyboardFocusChanged
  fun onVirtualViewKeyboardFocusChanged(
      c: ComponentContext?,
      host: View,
      accessibilityNode: AccessibilityNodeInfoCompat?,
      virtualViewId: Int,
      hasFocus: Boolean,
      @FromBoundsDefined clickableSpans: Array<ClickableSpan?>,
  ) {
    val span = clickableSpans[virtualViewId]
    if (span is AccessibleClickableSpan) {
      span.isKeyboardFocused = hasFocus
      // force redraw when focus changes, so that any visual changes get applied.
      host.invalidate()
    }
  }

  @OnPopulateExtraAccessibilityNode
  fun onPopulateExtraAccessibilityNode(
      c: ComponentContext?,
      node: AccessibilityNodeInfoCompat,
      extraNodeIndex: Int,
      componentBoundsLeft: Int,
      componentBoundsTop: Int,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @FromBoundsDefined textLayout: Layout,
      @FromBoundsDefined clickableSpans: Array<ClickableSpan?>,
  ) {
    if (text !is Spanned) {
      if (ComponentsConfiguration.enableInvalidAccessibilityVirtualNodeFix) {
        TempRect[0, 0, 1] = 1
        node.setBoundsInParent(TempRect)
        node.contentDescription = ""
      }
      return
    }

    val spanned = text

    val span = clickableSpans[extraNodeIndex]
    val start = spanned.getSpanStart(span)
    val end = spanned.getSpanEnd(span)
    val startLine = textLayout.getLineForOffset(start)
    val endLine = textLayout.getLineForOffset(end)

    // The bounds for multi-line strings should *only* include the first line.  This is because
    // Talkback triggers its click at the center point of these bounds, and if that center point
    // is outside the spannable, it will click on something else.  There is no harm in not outlining
    // the wrapped part of the string, as the text for the whole string will be read regardless of
    // the bounding box.
    val selectionPathEnd =
        if (startLine == endLine) end else textLayout.getLineVisibleEnd(startLine)

    textLayout.getSelectionPath(start, selectionPathEnd, TempPath)
    TempPath.computeBounds(TempRectF, /* unused */ true)

    TempRect[
        componentBoundsLeft + TempRectF.left.toInt(),
        componentBoundsTop + TempRectF.top.toInt(),
        componentBoundsLeft + TempRectF.right.toInt()] =
        componentBoundsTop + TempRectF.bottom.toInt()

    if (TempRect.isEmpty) {
      // Text is not actually visible.
      // Override bounds so it doesn't crash ExploreByTouchHelper.java
      TempRect[0, 0, 1] = 1
      node.setBoundsInParent(TempRect)
      node.contentDescription = "" // make node non-focusable
      return
    }

    node.setBoundsInParent(TempRect)

    node.isClickable = true
    node.isFocusable = true
    node.isEnabled = true
    node.isVisibleToUser = true
    node.text = spanned.subSequence(start, end)
    AccessibleClickableSpanNodes.applyRoleAndLabel(span, node)
  }

  @GetExtraAccessibilityNodeAt
  fun getExtraAccessibilityNodeAt(
      c: ComponentContext?,
      x: Int,
      y: Int,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @FromBoundsDefined textLayout: Layout,
      @FromBoundsDefined clickableSpans: Array<ClickableSpan?>,
  ): Int {
    if (text !is Spanned) {
      return ExploreByTouchHelper.INVALID_ID
    }

    val spanned = text

    for (i in clickableSpans.indices) {
      val span = clickableSpans[i]
      val start = spanned.getSpanStart(span)
      val end = spanned.getSpanEnd(span)

      textLayout.getSelectionPath(start, end, TempPath)
      TempPath.computeBounds(TempRectF, /* unused */ true)

      if (TempRectF.contains(x.toFloat(), y.toFloat())) {
        return i
      }
    }

    return ExploreByTouchHelper.INVALID_ID
  }

  @OnBindDynamicValue
  fun onBindTextColor(
      textDrawable: TextDrawable,
      @Prop(optional = true, dynamic = true) dynamicTextColor: Int?,
  ) {
    if (dynamicTextColor != null) {
      textDrawable.setTextColor(dynamicTextColor)
    }
  }

  fun getTextAlignment(alignment: Layout.Alignment?, textAlignment: TextAlignment?): TextAlignment {
    if (textAlignment != null) {
      return textAlignment
    }
    if (alignment != null) {
      return when (alignment) {
        Layout.Alignment.ALIGN_NORMAL -> TextAlignment.TEXT_START
        Layout.Alignment.ALIGN_OPPOSITE -> TextAlignment.TEXT_END
        Layout.Alignment.ALIGN_CENTER -> TextAlignment.CENTER
        else -> TextAlignment.TEXT_START
      }
    }
    return TextAlignment.TEXT_START
  }

  private fun getTextDirection(
      textDirection: TextDirectionHeuristicCompat?,
      layoutDirection: YogaDirection,
  ): TextDirectionHeuristicCompat {
    var textDirectionToUse = textDirection
    if (textDirectionToUse == null) {
      textDirectionToUse =
          if (layoutDirection == YogaDirection.RTL) TextDirectionHeuristicsCompat.FIRSTSTRONG_RTL
          else TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR
    }
    return textDirectionToUse
  }

  private fun getLayoutAlignment(
      textAlignment: TextAlignment,
      textDirection: TextDirectionHeuristicCompat?,
      text: CharSequence?,
      layoutDirection: YogaDirection,
  ): Layout.Alignment {
    val alignment: Layout.Alignment
    val layoutRtl: Boolean
    val textRtl: Boolean
    when (textAlignment) {
      TextAlignment.TEXT_START -> alignment = Layout.Alignment.ALIGN_NORMAL
      TextAlignment.TEXT_END -> alignment = Layout.Alignment.ALIGN_OPPOSITE
      TextAlignment.LAYOUT_START -> {
        layoutRtl = (layoutDirection == YogaDirection.RTL)
        textRtl = (textDirection?.isRtl(text, 0, text?.length ?: 0) == true)
        alignment =
            if ((layoutRtl == textRtl)) Layout.Alignment.ALIGN_NORMAL
            else Layout.Alignment.ALIGN_OPPOSITE
      }
      TextAlignment.LAYOUT_END -> {
        layoutRtl = (layoutDirection == YogaDirection.RTL)
        textRtl = (textDirection?.isRtl(text, 0, text?.length ?: 0) == true)
        alignment =
            if ((layoutRtl == textRtl)) Layout.Alignment.ALIGN_OPPOSITE
            else Layout.Alignment.ALIGN_NORMAL
      }
      TextAlignment.LEFT ->
          alignment =
              if (textDirection?.isRtl(text, 0, text?.length ?: 0) == true)
                  Layout.Alignment.ALIGN_OPPOSITE
              else Layout.Alignment.ALIGN_NORMAL
      TextAlignment.RIGHT ->
          alignment =
              if (textDirection?.isRtl(text, 0, text?.length ?: 0) == true)
                  Layout.Alignment.ALIGN_NORMAL
              else Layout.Alignment.ALIGN_OPPOSITE
      TextAlignment.CENTER -> alignment = Layout.Alignment.ALIGN_CENTER
      else -> alignment = Layout.Alignment.ALIGN_NORMAL
    }
    return alignment
  }
}
