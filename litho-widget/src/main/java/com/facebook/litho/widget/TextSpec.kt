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
import android.text.Layout
import android.text.TextUtils
import androidx.core.text.TextDirectionHeuristicCompat
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.DynamicValue
import com.facebook.litho.EventHandler
import com.facebook.litho.Output
import com.facebook.litho.annotations.ExcuseMySpec
import com.facebook.litho.annotations.LayoutSpec
import com.facebook.litho.annotations.OnCreateLayout
import com.facebook.litho.annotations.OnLoadStyle
import com.facebook.litho.annotations.Prop
import com.facebook.litho.annotations.PropDefault
import com.facebook.litho.annotations.Reason
import com.facebook.litho.annotations.ResType
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.RenderCoreConfig
import com.facebook.rendercore.text.ClickableSpanListener
import com.facebook.rendercore.text.TextStyle
import com.facebook.rendercore.text.TouchableSpanListener
import java.lang.Deprecated

@ExcuseMySpec(reason = Reason.J2K_CONVERSION)
@kotlin.Deprecated("Use RichText instead")
@LayoutSpec
object TextSpec {

  @JvmField @PropDefault val minLines: Int = Int.MIN_VALUE
  @JvmField @PropDefault val maxLines: Int = Int.MAX_VALUE
  @JvmField @PropDefault val minEms: Int = TextStylesHelper.DEFAULT_EMS
  @JvmField @PropDefault val maxEms: Int = TextStylesHelper.DEFAULT_EMS
  @JvmField @PropDefault val minTextWidth: Int = TextStylesHelper.DEFAULT_MIN_WIDTH
  @JvmField @PropDefault val maxTextWidth: Int = TextStylesHelper.DEFAULT_MAX_WIDTH
  @JvmField @PropDefault val shadowColor: Int = Color.GRAY
  @JvmField @PropDefault val outlineWidth: Float = 0f
  @JvmField @PropDefault val outlineColor: Int = 0
  @JvmField @PropDefault val textColor: Int = TextComponentSpec.DEFAULT_COLOR
  @JvmField @PropDefault val linkColor: Int = Color.BLUE

  @JvmField
  @PropDefault
  val textColorStateList: ColorStateList = ColorStateList(
      TextComponentSpec.DEFAULT_TEXT_COLOR_STATE_LIST_STATES,
      TextComponentSpec.DEFAULT_TEXT_COLOR_STATE_LIST_COLORS,
  )

  @JvmField @PropDefault val textSize: Int = TextComponentSpec.UNSET
  @JvmField @PropDefault val textStyle: Int = TextComponentSpec.DEFAULT_TYPEFACE.style
  @JvmField @PropDefault val typeface: Typeface = TextComponentSpec.DEFAULT_TYPEFACE
  @JvmField @PropDefault val spacingMultiplier: Float = 1.0f
  @JvmField @PropDefault val verticalGravity: VerticalGravity = VerticalGravity.TOP
  @JvmField @PropDefault val glyphWarming: Boolean = false
  @JvmField @PropDefault val shouldIncludeFontPadding: Boolean = true

  @JvmField @PropDefault val breakStrategy: Int = TextStylesHelper.DEFAULT_BREAK_STRATEGY
  @JvmField
  @PropDefault
  val hyphenationFrequency: Int = TextStylesHelper.DEFAULT_HYPHENATION_FREQUENCY
  @JvmField @PropDefault val justificationMode: Int = TextStylesHelper.DEFAULT_JUSTIFICATION_MODE
  @JvmField @PropDefault val highlightStartOffset: Int = -1
  @JvmField @PropDefault val highlightEndOffset: Int = -1
  @JvmField @PropDefault val clipToBounds: Boolean = true
  @JvmField @PropDefault val lineHeight: Float = Float.MAX_VALUE

  @JvmStatic
  @OnCreateLayout
  fun onCreateLayout(
      c: ComponentContext,
      @Prop(resType = ResType.STRING) text: CharSequence?,
      @Prop(optional = true, resType = ResType.COLOR) highlightColor: Int,
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
      @Prop(optional = true, resType = ResType.COLOR) outlineColor: Int,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) outlineWidth: Float,
      @Prop(optional = true, resType = ResType.STRING) customEllipsisText: CharSequence?,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) clickableSpanExpandedOffset: Float,
      @Prop(optional = true, resType = ResType.BOOL) accessibleClickableSpans: Boolean,
      @Prop(optional = true) textOffsetOnTouchHandler: EventHandler<*>?,
      @Prop(optional = true) spanListener: ClickableSpanListener?,
      @Prop(optional = true) touchableSpanListener: TouchableSpanListener?,
      @Prop(optional = true) clipToBounds: Boolean,
      @Prop(optional = true) highlightStartOffset: Int,
      @Prop(optional = true) highlightEndOffset: Int,
      @Prop(optional = true) dynamicTextColor: DynamicValue<Int?>?,
      @Prop(optional = true) verticalGravity: VerticalGravity,
      @Prop(optional = true) textStyle: Int,
      @Prop(optional = true) typeface: Typeface?,
      @Prop(optional = true) @Deprecated textAlignment: Layout.Alignment?,
      @Prop(optional = true) alignment: TextAlignment?,
      @Prop(optional = true) breakStrategy: Int,
      @Prop(optional = true) hyphenationFrequency: Int,
      @Prop(optional = true) justificationMode: Int,
      @Prop(optional = true) glyphWarming: Boolean,
      @Prop(optional = true) textDirection: TextDirectionHeuristicCompat?,
      @Prop(optional = true) minimallyWide: Boolean,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) minimallyWideThreshold: Int,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) lineHeight: Float,
  ): Component {
    if (ComponentsConfiguration.usePrimitiveText) {
      val richTextStyle = TextStyle()
      richTextStyle.breakStrategy = TextStylesHelper.DEFAULT_BREAK_STRATEGY
      ellipsize?.let { richTextStyle.ellipsize = it }
      if (highlightColor != 0) {
        richTextStyle.highlightColor = highlightColor
      }
      if (maxLines != Int.MAX_VALUE) {
        richTextStyle.maxLines = maxLines
      }
      if (minLines != Int.MIN_VALUE) {
        richTextStyle.minLines = minLines
      }
      if (minEms != TextStylesHelper.DEFAULT_EMS) {
        richTextStyle.minEms = minEms
      } else {
        richTextStyle.minTextWidth = minTextWidth
      }
      if (maxEms != TextStylesHelper.DEFAULT_EMS) {
        richTextStyle.maxEms = maxEms
      } else {
        richTextStyle.maxTextWidth = maxTextWidth
      }
      if (typeface != TextComponentSpec.DEFAULT_TYPEFACE && typeface != null) {
        richTextStyle.typeface = typeface
      } else {
        richTextStyle.textStyle = textStyle
      }
      if (shadowRadius != 0f) {
        richTextStyle.shadowRadius = shadowRadius
      }
      if (shadowDx != 0f) {
        richTextStyle.shadowDx = shadowDx
      }
      if (shadowDy != 0f) {
        richTextStyle.shadowDy = shadowDy
      }
      if (shadowColor != Color.GRAY) {
        richTextStyle.shadowColor = shadowColor
      }
      if (isSingleLine) {
        richTextStyle.isSingleLine = isSingleLine
      }
      if (!shouldIncludeFontPadding) {
        richTextStyle.includeFontPadding = shouldIncludeFontPadding
      }
      if (textColor != TextComponentSpec.DEFAULT_COLOR) {
        richTextStyle.textColor = textColor
      } else if (textColorStateList != null) {
        richTextStyle.textColorStateList = textColorStateList
      }
      if (linkColor != Color.BLUE) {
        richTextStyle.linkColor = linkColor
      }
      // text size must be set before the line hight
      if (textSize != TextComponentSpec.UNSET) {
        richTextStyle.textSize = textSize
      } else {
        val defaultTextSize: Int =
            c.resourceResolver.sipsToPixels(TextComponentSpec.DEFAULT_TEXT_SIZE_SP.toFloat())
        richTextStyle.textSize = defaultTextSize
      }
      if (extraSpacing != 0f) {
        richTextStyle.lineSpacingExtra = extraSpacing
      }
      if (spacingMultiplier != 1.0f) {
        richTextStyle.lineHeightMultiplier = spacingMultiplier
      }
      if (letterSpacing != 0f) {
        richTextStyle.letterSpacing = letterSpacing
      }
      if (outlineColor != 0) {
        richTextStyle.outlineColor = outlineColor
      }
      if (outlineWidth != 0f) {
        richTextStyle.outlineWidth = outlineWidth
      }
      customEllipsisText?.let { richTextStyle.customEllipsisText = it }
      richTextStyle.clickableSpanExpandedOffset = clickableSpanExpandedOffset
      richTextStyle.clipToBounds = clipToBounds
      richTextStyle.textBelongsToHost = true
      richTextStyle.exposeClickableSpansToAccessibility = accessibleClickableSpans
      if (highlightStartOffset != -1) {
        richTextStyle.highlightStartOffset = highlightStartOffset
      }
      if (highlightEndOffset != -1) {
        richTextStyle.highlightEndOffset = highlightEndOffset
      }
      if (verticalGravity != VerticalGravity.TOP) {
        richTextStyle.verticalGravity = getRCVerticalGravity(verticalGravity)
      }
      textAlignment?.let {
        val resolvedTextAlignment = TextComponentSpec.getTextAlignment(textAlignment, alignment)
        richTextStyle.alignment = getAlignment(resolvedTextAlignment)
      }

      alignment?.let { richTextStyle.alignment = getAlignment(alignment) }
      if (breakStrategy != TextStylesHelper.DEFAULT_BREAK_STRATEGY) {
        richTextStyle.breakStrategy = breakStrategy
      }
      if (hyphenationFrequency != TextStylesHelper.DEFAULT_HYPHENATION_FREQUENCY) {
        richTextStyle.hyphenationFrequency = hyphenationFrequency
      }
      if (justificationMode != TextStylesHelper.DEFAULT_JUSTIFICATION_MODE) {
        richTextStyle.justificationMode = justificationMode
      }
      textDirection?.let { richTextStyle.textDirection = it }
      richTextStyle.minimallyWide = minimallyWide
      richTextStyle.minimallyWideThreshold = minimallyWideThreshold
      if (lineHeight != Float.MAX_VALUE) {
        richTextStyle.lineHeight = lineHeight
      }
      richTextStyle.useLegacyTextCustomEllipsisCut =
          ComponentsConfiguration.primitiveTextPerformantTruncation
      return RichText(
          // Null text still mounts an empty text view, like legacy: the caller's style, size
          // rounding and content binders then behave the same as for any other Text.
          text = text ?: "",
          textStyle = richTextStyle,
          touchableSpanListener = touchableSpanListener,
          clickableSpanListener = spanListener,
          clickableSpanAccessibilityProvider = AccessibleClickableSpanNodes,
          dynamicTextColor = dynamicTextColor,
          // With its custom-ellipsis offset fix, legacy Text cuts at Paint.getOffsetForAdvance's
          // offset. Without it, keep RichText's default truncation, so turning the fix off is a
          // full rollback. primitiveTextPerformantTruncation alone uses legacy's fast cut, without
          // the offset fix.
          usePerformantTruncation =
              ComponentsConfiguration.primitiveTextPerformantTruncation ||
                  ComponentsConfiguration.enableFixForTextEllipsisOffset ||
                  RenderCoreConfig.usePerformantTruncation,
          useLegacyTextCustomEllipsisOffsetFix =
              ComponentsConfiguration.enableFixForTextEllipsisOffset,
          // Like legacy, resolve direction from the component's Yoga direction rather than the
          // locale, so subtrees that override layoutDirection behave the same.
          useResolvedLayoutDirection = true,
      )
    } else {
      return TextComponent.create(c)
          .text(text)
          .ellipsize(ellipsize)
          .shouldIncludeFontPadding(shouldIncludeFontPadding)
          .maxLines(maxLines)
          .minLines(minLines)
          .minEms(minEms)
          .maxEms(maxEms)
          .minTextWidthPx(minTextWidth)
          .maxTextWidthPx(maxTextWidth)
          .shadowRadiusPx(shadowRadius)
          .shadowDxPx(shadowDx)
          .shadowDyPx(shadowDy)
          .shadowColor(shadowColor)
          .isSingleLine(isSingleLine)
          .textColor(textColor)
          .textColorStateList(textColorStateList)
          .linkColor(linkColor)
          .textSizePx(textSize)
          .highlightColor(highlightColor)
          .extraSpacingPx(extraSpacing)
          .spacingMultiplier(spacingMultiplier)
          .letterSpacing(letterSpacing)
          .verticalGravity(verticalGravity)
          .textStyle(textStyle)
          .typeface(typeface)
          .textAlignment(textAlignment)
          .outlineColor(outlineColor)
          .outlineWidthPx(outlineWidth)
          .alignment(alignment)
          .dynamicTextColor(dynamicTextColor)
          .breakStrategy(breakStrategy)
          .hyphenationFrequency(hyphenationFrequency)
          .glyphWarming(glyphWarming)
          .textDirection(textDirection)
          .customEllipsisText(customEllipsisText)
          .lineHeightPx(lineHeight)
          .minimallyWide(minimallyWide)
          .minimallyWideThresholdPx(minimallyWideThreshold)
          .highlightStartOffset(highlightStartOffset)
          .highlightEndOffset(highlightEndOffset)
          .justificationMode(justificationMode)
          .clickableSpanExpandedOffsetPx(clickableSpanExpandedOffset)
          .spanListener(spanListener)
          .touchableSpanListener(touchableSpanListener)
          .clipToBounds(clipToBounds)
          .textOffsetOnTouchHandler(textOffsetOnTouchHandler)
          .accessibleClickableSpans(accessibleClickableSpans)
          .build()
    }
  }

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

  private fun getRCVerticalGravity(
      verticalGravity: VerticalGravity,
  ): com.facebook.rendercore.text.VerticalGravity {
    return when (verticalGravity) {
      VerticalGravity.TOP -> com.facebook.rendercore.text.VerticalGravity.TOP
      VerticalGravity.CENTER -> com.facebook.rendercore.text.VerticalGravity.CENTER
      VerticalGravity.BOTTOM -> com.facebook.rendercore.text.VerticalGravity.BOTTOM
    }
  }

  private fun getAlignment(alignment: TextAlignment): com.facebook.rendercore.text.TextAlignment {
    return when (alignment) {
      TextAlignment.TEXT_START -> com.facebook.rendercore.text.TextAlignment.TEXT_START
      TextAlignment.TEXT_END -> com.facebook.rendercore.text.TextAlignment.TEXT_END
      TextAlignment.CENTER -> com.facebook.rendercore.text.TextAlignment.CENTER
      TextAlignment.LAYOUT_START -> com.facebook.rendercore.text.TextAlignment.LAYOUT_START
      TextAlignment.LAYOUT_END -> com.facebook.rendercore.text.TextAlignment.LAYOUT_END
      TextAlignment.LEFT -> com.facebook.rendercore.text.TextAlignment.LEFT
      TextAlignment.RIGHT -> com.facebook.rendercore.text.TextAlignment.RIGHT
    }
  }
}
