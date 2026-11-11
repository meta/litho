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

import android.graphics.Rect
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.util.component1
import androidx.core.util.component2
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.RenderCoreConfig
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.MountConfigurationScope
import com.facebook.rendercore.primitives.Primitive
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ResolvedLayoutDirectionProvider
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.text.TextMeasurementUtils.TextLayout
import com.facebook.rendercore.text.TextMeasurementUtils.layout
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import com.facebook.rendercore.utils.areObjectsEquivalent
import java.lang.Integer.max

/**
 * @param useLegacyTextCustomEllipsisOffsetFix With performant truncation, cut a custom ellipsis at
 *   the offset `Paint.getOffsetForAdvance` returns instead of one character before it, like Litho's
 *   legacy Text offset fix, stepping back while it doesn't fit. Off by default so existing
 *   primitive text users keep their current truncation.
 * @param useResolvedLayoutDirection Use the layout direction the host resolved for this node (see
 *   [ResolvedLayoutDirectionProvider]) instead of the locale's, as Litho's legacy Text does: the
 *   default text direction and LAYOUT_START/LAYOUT_END follow that direction, and TEXT_START aligns
 *   to the text's own start.
 *
 *   Off by default because existing primitive text users (Bloks, FOA DSP, Showreel) ship with the
 *   locale-based behaviour, and turning it on visibly changes them: for example RTL text in an LTR
 *   app moves from the left edge to the right. Litho Text opts in to keep its legacy behaviour when
 *   migrating to the primitive. Making it the default for everyone needs those owners on board and
 *   an experiment, after which this flag can go away.
 */
fun RichTextPrimitive(
    id: Long,
    text: CharSequence,
    style: TextStyle,
    touchableSpanListener: TouchableSpanListener? = null,
    clickableSpanListener: ClickableSpanListener? = null,
    usePerformantTruncation: Boolean = RenderCoreConfig.usePerformantTruncation,
    useTruncationCaching: Boolean = RenderCoreConfig.useTruncationCaching,
    hasIntrinsicSize: Boolean = false,
    extraMountConfiguration: (MountConfigurationScope<RCTextView>.() -> Unit)? = null,
    clickableSpanAccessibilityProvider: ClickableSpanAccessibilityProvider? = null,
    useResolvedLayoutDirection: Boolean = false,
    useLegacyTextCustomEllipsisOffsetFix: Boolean = false,
): Primitive {
  return Primitive(
      layoutBehavior =
          RichTextLayoutBehavior(
              text = text,
              style = style,
              usePerformantTruncation = usePerformantTruncation,
              useTruncationCaching = useTruncationCaching,
              hasIntrinsicSize = hasIntrinsicSize,
              useResolvedLayoutDirection = useResolvedLayoutDirection,
              useLegacyTextCustomEllipsisOffsetFix = useLegacyTextCustomEllipsisOffsetFix,
          ),
      mountBehavior =
          MountBehavior(
              id = id,
              contentAllocator =
                  ViewAllocator(
                      canPreallocate = RichTextPrimitiveConfig.canPreallocation,
                      poolSize = RichTextPrimitiveConfig.poolSize,
                  ) { c ->
                    RCTextView(c)
                  },
          ) {
            doesMountRenderTreeHosts =
                text is Spanned &&
                    text.getSpans(0, text.length, InlineViewSpan::class.java).isNotEmpty()
            bind(Unit) { content -> onUnbind { content.finalUnmount() } }
            bindWithLayoutData<RichTextLayoutData>(Unit) { content, layoutData ->
              content.mount(layoutData.textLayout)
              onUnbind { content.unmount() }
            }
            touchableSpanListener.bindTo(RCTextView::setTouchableSpanListener)
            clickableSpanListener.bindTo(RCTextView::setClickableSpanListener)
            clickableSpanAccessibilityProvider.bindTo(
                RCTextView::setClickableSpanAccessibilityProvider,
            )
            extraMountConfiguration?.invoke(this)
          },
  )
}

/**
 * The per-thread [TextPaint] for the min-content probe, with the textSize/typeface/letterSpacing
 * [TextMeasurementUtils.createTextLayout] applies. Must stay in sync with those setters; textSize
 * is set unconditionally (UNSET(-1) -> -1f).
 */
internal fun configuredTextPaint(style: TextStyle): TextPaint =
    TextLayoutIntrinsics.intrinsicTextPaint(
        textSize = style.textSize.toFloat(),
        typeface = style.typeface ?: Typeface.defaultFromStyle(style.textStyle),
        letterSpacing = style.letterSpacing,
    )

internal class RichTextLayoutBehavior(
    val text: CharSequence,
    val style: TextStyle,
    val usePerformantTruncation: Boolean,
    val useTruncationCaching: Boolean,
    override val hasIntrinsicSize: Boolean = false,
    /** See [RichTextPrimitive]. */
    val useResolvedLayoutDirection: Boolean = false,
    val useLegacyTextCustomEllipsisOffsetFix: Boolean = false,
) : LayoutBehavior {

  /**
   * Compares the style by its values: the default field-wise equivalence compares a [TextStyle] by
   * identity, and callers such as Litho's Text build a new one on each render. The custom ellipsis
   * is also compared with its spans, which [TextStyle.hasSameValuesAs] leaves to callers.
   */
  override fun isEquivalentTo(other: LayoutBehavior): Boolean {
    if (this === other) {
      return true
    }
    if (other !is RichTextLayoutBehavior) {
      return false
    }
    return usePerformantTruncation == other.usePerformantTruncation &&
        useTruncationCaching == other.useTruncationCaching &&
        hasIntrinsicSize == other.hasIntrinsicSize &&
        useResolvedLayoutDirection == other.useResolvedLayoutDirection &&
        useLegacyTextCustomEllipsisOffsetFix == other.useLegacyTextCustomEllipsisOffsetFix &&
        areObjectsEquivalent(text, other.text) &&
        style.hasSameValuesAs(other.style) &&
        areObjectsEquivalent(style.customEllipsisText, other.style.customEllipsisText)
  }

  override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {
    val layoutStyle = styleForLayout()
    val previousRichTextLayoutData = previousLayoutData as? RichTextLayoutData
    if (
        useTruncationCaching &&
            previousRichTextLayoutData != null &&
            canReuseTextLayout(previousRichTextLayoutData, text, layoutStyle, sizeConstraints)
    ) {
      return PrimitiveLayoutResult(
          width = max(previousRichTextLayoutData.size.width(), sizeConstraints.minWidth),
          height = max(previousRichTextLayoutData.size.height(), sizeConstraints.minHeight),
          // TODO: This should copy the textLayout with new clickable spans
          layoutData =
              RichTextLayoutData(
                  previousRichTextLayoutData.textLayout.copyForReuse(text, layoutStyle),
                  previousRichTextLayoutData.size,
                  layoutStyle,
                  sizeConstraints,
                  previousRichTextLayoutData.inputStyle,
                  usePerformantTruncation,
                  useLegacyTextCustomEllipsisOffsetFix,
              ),
      )
    }

    val (size, textLayout) =
        layout(
            androidContext,
            sizeConstraints.toWidthSpec(),
            sizeConstraints.toHeightSpec(),
            text,
            layoutStyle,
            usePerformantTruncation,
            useLegacyTextCustomEllipsisOffsetFix,
        )
    return PrimitiveLayoutResult(
        width = max(size.width(), sizeConstraints.minWidth),
        height = max(size.height(), sizeConstraints.minHeight),
        layoutData =
            RichTextLayoutData(
                textLayout,
                size,
                layoutStyle,
                sizeConstraints,
                // A snapshot, because callers may change this style object between layouts.
                if (useResolvedLayoutDirection) style.makeCopy() else style,
                usePerformantTruncation,
                useLegacyTextCustomEllipsisOffsetFix,
            ),
    )
  }

  private fun LayoutScope.styleForLayout(): TextStyle {
    if (!useResolvedLayoutDirection) {
      return style
    }
    // Always copy, even with no resolved direction: measuring writes a default direction into the
    // style, which on the caller's style would later override a resolved direction. Without one
    // from the host, a direction the caller set on the style still applies.
    return style.makeCopy().apply {
      resolvedLayoutDirectionRtl =
          (extraContext as? ResolvedLayoutDirectionProvider)?.isResolvedLayoutDirectionRtl
              ?: style.resolvedLayoutDirectionRtl
    }
  }

  /** Min-content width: the longest-word width (measured unbounded). */
  override fun LayoutScope.minIntrinsicWidth(sizeConstraints: SizeConstraints): Int {
    if (text.isEmpty()) {
      return 0
    }

    return TextLayoutIntrinsics.longestWordWidth(text, configuredTextPaint(style))
  }

  /** Min-content height: the natural single-line height (measured unbounded). */
  override fun LayoutScope.minIntrinsicHeight(sizeConstraints: SizeConstraints): Int {
    if (text.isEmpty()) {
      return 0
    }

    val paint = configuredTextPaint(style)
    // Manual cap/baseline spacing drives height off the cap height rather than font metrics.
    if (style.manualCapSpacing != Int.MIN_VALUE && style.manualBaselineSpacing != Int.MIN_VALUE) {
      var manualHeight =
          TextLayoutIntrinsics.singleLineHeightWithManualSpacing(
              paint,
              style.manualCapSpacing,
              style.manualBaselineSpacing,
          )
      if (style.shouldAddSpacingExtraToFirstLine) {
        manualHeight +=
            TextLayoutIntrinsics.firstLineExtraSpacing(paint, style.lineHeightMultiplier)
      }
      return manualHeight
    }

    var height = TextLayoutIntrinsics.singleLineHeight(paint, style.includeFontPadding)
    if (style.shouldAddSpacingExtraToFirstLine) {
      height += TextLayoutIntrinsics.firstLineExtraSpacing(paint, style.lineHeightMultiplier)
    }
    return height
  }

  private fun canReuseTextLayout(
      previousLayoutData: RichTextLayoutData,
      text: CharSequence,
      style: TextStyle,
      sizeConstraints: SizeConstraints,
  ): Boolean {
    return previousLayoutData.textLayout.isExplicitlyTruncated &&
        previousLayoutData.sizeConstraints == sizeConstraints &&
        previousLayoutData.usePerformantTruncation == usePerformantTruncation &&
        previousLayoutData.useLegacyTextCustomEllipsisOffsetFix ==
            useLegacyTextCustomEllipsisOffsetFix &&
        hasEquivalentStyleInput(previousLayoutData, style) &&
        hasEquivalentTruncatedText(previousLayoutData.textLayout, text, style) &&
        hasSameShownSpans(previousLayoutData.textLayout, text, style)
  }

  // hasEquivalentStyle compares only some of the style. Measuring writes a default direction into
  // the style it's given, so on the locale path a new style object rarely matches and the same
  // object is what reuses a layout. With useResolvedLayoutDirection each layout measures a copy, so
  // the caller's style as it was at measurement is kept and every one of its values must still
  // match, whether it's the same object (possibly changed since) or a new one, and it must resolve
  // to the same direction.
  private fun hasEquivalentStyleInput(
      previousLayoutData: RichTextLayoutData,
      layoutStyle: TextStyle,
  ): Boolean =
      if (useResolvedLayoutDirection) {
        previousLayoutData.inputStyle.hasSameValuesAs(style) &&
            previousLayoutData.style.resolvedLayoutDirectionRtl ==
                layoutStyle.resolvedLayoutDirectionRtl
      } else {
        hasEquivalentStyle(layoutStyle, previousLayoutData.style)
      }

  // A reused layout keeps the previous displayed text with its spans, which style the glyphs,
  // handle touches and back the screen-reader nodes. So the text this layout would display, the
  // shown prefix plus the custom ellipsis, must have the same spans, ranges and flags.
  private fun hasSameShownSpans(
      textLayout: TextLayout,
      text: CharSequence,
      style: TextStyle,
  ): Boolean {
    val displayed = textLayout.layout.text
    val suffix = style.customEllipsisText ?: ""
    val wouldDisplay =
        TextUtils.concat(text.subSequence(0, displayed.length - suffix.length), suffix)
    return spansOf(displayed) == spansOf(wouldDisplay)
  }

  private fun spansOf(text: CharSequence): Set<List<Any>> {
    val spanned = text as? Spanned ?: return emptySet()
    return spanned
        .getSpans(0, spanned.length, Any::class.java)
        .map { span ->
          listOf(
              span,
              spanned.getSpanStart(span),
              spanned.getSpanEnd(span),
              spanned.getSpanFlags(span),
          )
        }
        .toSet()
  }

  // TODO: this should be moved to TextStyle and emcompass all properties
  fun hasEquivalentStyle(style1: TextStyle, style2: TextStyle): Boolean {
    return style1.textSize == style2.textSize &&
        style1.textColor == style2.textColor &&
        style1.textDirection == style2.textDirection &&
        style1.resolvedLayoutDirectionRtl == style2.resolvedLayoutDirectionRtl &&
        style1.alignment == style2.alignment &&
        style1.maxLines == style2.maxLines &&
        style1.textStyle == style2.textStyle &&
        style1.truncationStyle == style2.truncationStyle
  }

  private fun hasEquivalentTruncatedText(
      textLayout: TextLayout,
      text: CharSequence,
      style: TextStyle,
  ): Boolean {
    if (!textLayout.isExplicitlyTruncated) {
      return false
    }
    val textLayoutText = textLayout.layout.text
    val suffix = style.customEllipsisText ?: ""
    return textLayoutText.endsWith(suffix) &&
        text.startsWith(textLayoutText.subSequence(0, textLayoutText.length - suffix.length))
  }
}

internal class RichTextLayoutData(
    val textLayout: TextLayout,
    val size: Rect,
    val style: TextStyle,
    val sizeConstraints: SizeConstraints,
    /** The caller's style as it was when this layout was measured. */
    val inputStyle: TextStyle = style,
    // The truncation options decide where a custom ellipsis cuts, so a layout made with others
    // can't be reused.
    val usePerformantTruncation: Boolean = false,
    val useLegacyTextCustomEllipsisOffsetFix: Boolean = false,
)

object RichTextPrimitiveConfig {
  val canPreallocation: Boolean = true
  val poolSize: Int = 30
}

object RichTextPrimitiveUtils {
  fun getTextLayout(layoutResult: LayoutResult): TextLayout {
    check(layoutResult.layoutData is RichTextLayoutData) {
      "LayoutResult is not a Rich Text Primitive"
    }
    return (layoutResult.layoutData as RichTextLayoutData).textLayout
  }
}
