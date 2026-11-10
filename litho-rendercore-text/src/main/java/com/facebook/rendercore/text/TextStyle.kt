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

package com.facebook.rendercore.text

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import androidx.annotation.ColorInt
import androidx.core.text.TextDirectionHeuristicCompat
import androidx.core.text.TextDirectionHeuristicsCompat
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import kotlin.jvm.JvmField

class TextStyle : Cloneable {

  @JvmField var ellipsize: TextUtils.TruncateAt? = null
  @JvmField var customEllipsisText: CharSequence? = null
  @JvmField var includeFontPadding: Boolean = true
  @JvmField var minLines: Int = Int.MIN_VALUE
  @JvmField var maxLines: Int = Int.MAX_VALUE
  @JvmField var minEms: Int = UNSET
  @JvmField var maxEms: Int = UNSET
  @JvmField var minTextWidth: Int = 0
  @JvmField var maxTextWidth: Int = Int.MAX_VALUE
  @JvmField var shadowRadius: Float = 0f
  @JvmField var shadowDx: Float = 0f
  @JvmField var shadowDy: Float = 0f
  @ColorInt @JvmField var shadowColor: Int = Color.GRAY
  @JvmField var isSingleLine: Boolean = false
  @ColorInt @JvmField var linkColor: Int = Color.BLUE
  @JvmField var textSize: Int = UNSET
  @JvmField var shouldAddSpacingExtraToFirstLine: Boolean = false
  @JvmField var lineSpacingExtra: Float = 0f
  @JvmField var lineHeight: Float = Float.MAX_VALUE
  @JvmField var lineHeightMultiplier: Float = 1f
  @JvmField var letterSpacing: Float = 0f
  @JvmField var textStyle: Int = Typeface.DEFAULT.style
  @JvmField var typeface: Typeface? = null
  @JvmField var alignment: TextAlignment = TextAlignment.TEXT_START
  @JvmField var breakStrategy: Int = UNSET
  @JvmField var hyphenationFrequency: Int = 0
  @JvmField var justificationMode: Int = 0
  @JvmField var textDirection: TextDirectionHeuristicCompat? = null
  /**
   * Clips drawn text to the view's bounds, like Litho's legacy `Text`. Off by default: Bloks, FOA,
   * Showreel and others draw past their bounds today (e.g. cap-height trimmed text), so turning it
   * on for them would cut glyphs.
   */
  @JvmField var clipToBounds: Boolean = false
  /**
   * Marks the text as belonging to its parent host view, the way Litho's legacy Text draws into its
   * host. Test lookups then find the host (the clickable, selectable container) rather than the
   * text view. Set by Litho's `Text`; off for direct primitive users, whose tests expect the text
   * view.
   */
  @JvmField var textBelongsToHost: Boolean = false
  /**
   * Exposes each clickable span to screen readers as its own node. On by default; Litho's `Text`
   * turns it off unless `accessibleClickableSpans` is set, matching its legacy implementation.
   */
  @JvmField var exposeClickableSpansToAccessibility: Boolean = true
  @JvmField var verticalGravity: VerticalGravity = VerticalGravity.TOP
  @ColorInt @JvmField var highlightColor: Int = Color.TRANSPARENT
  @ColorInt @JvmField var keyboardHighlightColor: Int = Color.TRANSPARENT
  @JvmField var highlightStartOffset: Int = UNSET
  @JvmField var highlightEndOffset: Int = UNSET
  @JvmField var highlightCornerRadius: Int = 0
  @JvmField var shouldLayoutEmptyText: Boolean = false
  @JvmField var minimallyWide: Boolean = false
  @JvmField var minimallyWideThreshold: Int = 0

  /**
   * Click offset amount to determine how far off the ClickableSpan bounds user can click to be able
   * to trigger ClickableSpan's click action. This could be useful in a densely lined text with
   * links like 'Continue reading ...' in NewsFeed to be able to click that easily.
   */
  @JvmField var clickableSpanExpandedOffset: Float = 0f
  @JvmField var shouldTruncateTextUsingConstraints: Boolean = false
  @JvmField var manualBaselineSpacing: Int = Int.MIN_VALUE
  @JvmField var manualCapSpacing: Int = Int.MIN_VALUE
  @JvmField var extraSpacingLeft: Float = 0f
  @JvmField var extraSpacingRight: Float = 0f
  @JvmField var outlineWidth: Float = 0f
  @ColorInt @JvmField var outlineColor: Int = Color.TRANSPARENT
  @JvmField var roundedBackgroundProps: RoundedBackgroundProps? = null
  @JvmField var accessibilityLabel: String? = null
  @JvmField var isTextSelectable: Boolean = false
  @ColorInt @JvmField var selectionHandleColor: Int = Color.TRANSPARENT

  @JvmField var truncationStyle: TruncationStyle = TruncationStyle.USE_MAX_LINES

  /**
   * The layout direction the host resolved for this text (e.g. Litho's Yoga direction), or `null`
   * to use the locale's. When set, it replaces the locale for the default FIRSTSTRONG text
   * direction and for [TextAlignment.LAYOUT_START]/[TextAlignment.LAYOUT_END], and
   * [TextAlignment.TEXT_START] aligns to the text's own start, as Litho's legacy Text does.
   */
  @JvmField var resolvedLayoutDirectionRtl: Boolean? = null

  @ColorInt private var _textColor: Int = Color.BLACK
  private var _textColorStateList: ColorStateList? = null

  var textColor: Int
    @ColorInt get() = _textColor
    set(@ColorInt value) {
      _textColor = value
      _textColorStateList = null
    }

  var textColorStateList: ColorStateList?
    get() = _textColorStateList
    set(value) {
      _textColorStateList = value
      _textColor = 0
    }

  fun makeCopy(): TextStyle {
    try {
      return super.clone() as TextStyle
    } catch (e: CloneNotSupportedException) {
      throw RuntimeException(e)
    }
  }

  /**
   * Whether every value matches [other]'s, for deciding if a layout measured with one can be reused
   * for the other. Typeface, text direction and the colour state list compare by identity: they're
   * shared framework objects, and their toString() doesn't tell different behaviour apart. The
   * custom ellipsis compares by its characters; callers compare its spans separately.
   */
  fun hasSameValuesAs(other: TextStyle): Boolean =
      this === other ||
          (customEllipsisText.contentEquals(other.customEllipsisText) &&
              typeface === other.typeface &&
              textDirection === other.textDirection &&
              _textColorStateList === other._textColorStateList &&
              ellipsize == other.ellipsize &&
              includeFontPadding == other.includeFontPadding &&
              minLines == other.minLines &&
              maxLines == other.maxLines &&
              minEms == other.minEms &&
              maxEms == other.maxEms &&
              minTextWidth == other.minTextWidth &&
              maxTextWidth == other.maxTextWidth &&
              shadowRadius == other.shadowRadius &&
              shadowDx == other.shadowDx &&
              shadowDy == other.shadowDy &&
              shadowColor == other.shadowColor &&
              isSingleLine == other.isSingleLine &&
              linkColor == other.linkColor &&
              textSize == other.textSize &&
              shouldAddSpacingExtraToFirstLine == other.shouldAddSpacingExtraToFirstLine &&
              lineSpacingExtra == other.lineSpacingExtra &&
              lineHeight == other.lineHeight &&
              lineHeightMultiplier == other.lineHeightMultiplier &&
              letterSpacing == other.letterSpacing &&
              textStyle == other.textStyle &&
              alignment == other.alignment &&
              breakStrategy == other.breakStrategy &&
              hyphenationFrequency == other.hyphenationFrequency &&
              justificationMode == other.justificationMode &&
              clipToBounds == other.clipToBounds &&
              textBelongsToHost == other.textBelongsToHost &&
              exposeClickableSpansToAccessibility == other.exposeClickableSpansToAccessibility &&
              verticalGravity == other.verticalGravity &&
              highlightColor == other.highlightColor &&
              keyboardHighlightColor == other.keyboardHighlightColor &&
              highlightStartOffset == other.highlightStartOffset &&
              highlightEndOffset == other.highlightEndOffset &&
              highlightCornerRadius == other.highlightCornerRadius &&
              shouldLayoutEmptyText == other.shouldLayoutEmptyText &&
              minimallyWide == other.minimallyWide &&
              minimallyWideThreshold == other.minimallyWideThreshold &&
              clickableSpanExpandedOffset == other.clickableSpanExpandedOffset &&
              shouldTruncateTextUsingConstraints == other.shouldTruncateTextUsingConstraints &&
              manualBaselineSpacing == other.manualBaselineSpacing &&
              manualCapSpacing == other.manualCapSpacing &&
              extraSpacingLeft == other.extraSpacingLeft &&
              extraSpacingRight == other.extraSpacingRight &&
              outlineWidth == other.outlineWidth &&
              outlineColor == other.outlineColor &&
              roundedBackgroundProps == other.roundedBackgroundProps &&
              accessibilityLabel == other.accessibilityLabel &&
              isTextSelectable == other.isTextSelectable &&
              selectionHandleColor == other.selectionHandleColor &&
              truncationStyle == other.truncationStyle &&
              resolvedLayoutDirectionRtl == other.resolvedLayoutDirectionRtl &&
              _textColor == other._textColor)

  @DataClassGenerate
  data class RoundedBackgroundProps(
      @JvmField val padding: RectF,
      @JvmField val cornerRadius: Float,
      @field:ColorInt @JvmField val backgroundColor: Int,
  )

  companion object {
    const val UNSET: Int = -1

    @JvmStatic
    fun createDefaultConfiguredTextStyle(context: Context): TextStyle =
        TextStylesAttributeHelper.createThemedTextStyle(context).apply {
          shouldLayoutEmptyText = true
          highlightColor = Color.TRANSPARENT
        }

    @JvmStatic
    fun maybeSetTextAlignment(textStyle: TextStyle, textAlign: Int?) {
      if (textAlign != null) {
        val textAlignment =
            when (textAlign) {
              Gravity.CENTER_HORIZONTAL -> TextAlignment.CENTER
              Gravity.START -> TextAlignment.TEXT_START
              Gravity.END -> TextAlignment.TEXT_END
              else -> TextAlignment.TEXT_START
            }
        textStyle.alignment = textAlignment
      }
    }

    @JvmStatic
    fun maybeSetTextDirection(textStyle: TextStyle, textDirectionStr: String?, isRTL: Boolean) {
      if (textDirectionStr != null) {
        val textDirection =
            when (textDirectionStr) {
              "device_locale" -> TextDirectionHeuristicsCompat.LOCALE
              "text_first_strong" ->
                  if (isRTL) TextDirectionHeuristicsCompat.FIRSTSTRONG_RTL
                  else TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR

              else -> TextDirectionHeuristicsCompat.LOCALE
            }
        textStyle.textDirection = textDirection
      }
    }

    @JvmStatic
    fun maybeSetHyphens(textStyle: TextStyle, hyphensStr: String?) {
      if (hyphensStr != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        textStyle.hyphenationFrequency =
            when (hyphensStr) {
              "auto" -> Layout.HYPHENATION_FREQUENCY_NORMAL
              else -> Layout.HYPHENATION_FREQUENCY_NONE
            }
      }
    }

    @JvmStatic
    fun maybeSetLineHeight(textStyle: TextStyle, lineHeight: Float) {
      if (lineHeight >= 0) {
        textStyle.lineHeight = lineHeight
      }
    }

    @JvmStatic
    fun maybeSetMaxNumberOfLines(textStyle: TextStyle, maxNumberOfLines: Int) {
      if (maxNumberOfLines > -1) {
        textStyle.maxLines = maxNumberOfLines
        textStyle.ellipsize = TextUtils.TruncateAt.END
      }
    }

    @JvmStatic
    @JvmOverloads
    fun maybeSetLineHeightMultiplier(
        textStyle: TextStyle,
        lineHeightMultiplier: Float,
        applyToFirstLine: Boolean = true,
    ) {
      if (lineHeightMultiplier > 0) {
        // This is a special behavior to match Bloks classic and the iOS behavior.
        textStyle.shouldAddSpacingExtraToFirstLine = applyToFirstLine
        textStyle.lineHeightMultiplier = lineHeightMultiplier
      }
    }

    @JvmStatic
    fun addThemedTextStyleForContextToCache(context: Context) {
      TextStylesAttributeHelper.addThemedTextStyleForContext(context)
    }
  }

  override fun toString(): String = buildString {
    append("TextStyle(")
    append("ellipsize=").append(ellipsize).append(", ")
    append("customEllipsisText=").append(customEllipsisText).append(", ")
    append("includeFontPadding=").append(includeFontPadding).append(", ")
    append("minLines=").append(minLines).append(", ")
    append("maxLines=").append(maxLines).append(", ")
    append("minEms=").append(minEms).append(", ")
    append("maxEms=").append(maxEms).append(", ")
    append("minTextWidth=").append(minTextWidth).append(", ")
    append("maxTextWidth=").append(maxTextWidth).append(", ")
    append("shadowRadius=").append(shadowRadius).append(", ")
    append("shadowDx=").append(shadowDx).append(", ")
    append("shadowDy=").append(shadowDy).append(", ")
    append("shadowColor=").append(shadowColor).append(", ")
    append("isSingleLine=").append(isSingleLine).append(", ")
    append("linkColor=").append(linkColor).append(", ")
    append("textSize=").append(textSize).append(", ")
    append("shouldAddSpacingExtraToFirstLine=")
        .append(shouldAddSpacingExtraToFirstLine)
        .append(", ")
    append("lineSpacingExtra=").append(lineSpacingExtra).append(", ")
    append("lineHeight=").append(lineHeight).append(", ")
    append("lineHeightMultiplier=").append(lineHeightMultiplier).append(", ")
    append("letterSpacing=").append(letterSpacing).append(", ")
    append("textStyle=").append(textStyle).append(", ")
    append("typeface=").append(typeface).append(", ")
    append("alignment=").append(alignment).append(", ")
    append("breakStrategy=").append(breakStrategy).append(", ")
    append("hyphenationFrequency=").append(hyphenationFrequency).append(", ")
    append("justificationMode=").append(justificationMode).append(", ")
    append("textDirection=").append(textDirection).append(", ")
    append("clipToBounds=").append(clipToBounds).append(", ")
    append("textBelongsToHost=").append(textBelongsToHost).append(", ")
    append("exposeClickableSpansToAccessibility=")
        .append(exposeClickableSpansToAccessibility)
        .append(", ")
    append("verticalGravity=").append(verticalGravity).append(", ")
    append("highlightColor=").append(highlightColor).append(", ")
    append("keyboardHighlightColor=").append(keyboardHighlightColor).append(", ")
    append("highlightStartOffset=").append(highlightStartOffset).append(", ")
    append("highlightEndOffset=").append(highlightEndOffset).append(", ")
    append("highlightCornerRadius=").append(highlightCornerRadius).append(", ")
    append("shouldLayoutEmptyText=").append(shouldLayoutEmptyText).append(", ")
    append("minimallyWide=").append(minimallyWide).append(", ")
    append("minimallyWideThreshold=").append(minimallyWideThreshold).append(", ")
    append("clickableSpanExpandedOffset=").append(clickableSpanExpandedOffset).append(", ")
    append("shouldTruncateTextUsingConstraints=")
        .append(shouldTruncateTextUsingConstraints)
        .append(", ")
    append("manualBaselineSpacing=").append(manualBaselineSpacing).append(", ")
    append("manualCapSpacing=").append(manualCapSpacing).append(", ")
    append("extraSpacingLeft=").append(extraSpacingLeft).append(", ")
    append("extraSpacingRight=").append(extraSpacingRight).append(", ")
    append("outlineWidth=").append(outlineWidth).append(", ")
    append("outlineColor=").append(outlineColor).append(", ")
    append("roundedBackgroundProps=").append(roundedBackgroundProps).append(", ")
    append("accessibilityLabel=").append(accessibilityLabel).append(", ")
    append("isTextSelectable=").append(isTextSelectable).append(", ")
    append("selectionHandleColor=").append(selectionHandleColor).append(", ")
    append("truncationStyle=").append(truncationStyle).append(", ")
    append("resolvedLayoutDirectionRtl=").append(resolvedLayoutDirectionRtl).append(", ")
    append("textColor=").append(textColor).append(", ")
    append("textColorStateList=").append(textColorStateList)
    append(")")
  }
}
