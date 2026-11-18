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

package com.facebook.rendercore.text;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.text.style.MetricAffectingSpan;
import android.util.Pair;
import android.util.SparseIntArray;
import android.view.View;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.Size;
import androidx.annotation.VisibleForTesting;
import androidx.core.text.TextDirectionHeuristicCompat;
import androidx.core.text.TextDirectionHeuristicsCompat;
import com.facebook.fbui.textlayoutbuilder.TextLayoutBuilder;
import com.facebook.fbui.textlayoutbuilder.util.LayoutMeasureUtil;
import com.facebook.rendercore.LayoutContext;
import com.facebook.rendercore.MountableLayoutResult;
import com.facebook.rendercore.RenderCoreConfig;
import com.facebook.rendercore.utils.LayoutUtils;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

public class TextMeasurementUtils {

  private static final String CAP_MEASUREMENT_TEXT = "T";

  @GuardedBy("self")
  // Map from hash(textSize, typeface) -> cap heights array for text
  private static final SparseIntArray sCapHeightsCache = new SparseIntArray();

  private static final SparseIntArray sBaselineCache = new SparseIntArray();

  // The offsets of the returned values within the int array.
  private static final int CAP_HEIGHT_OFFSET_INDEX = 0;
  private static final int BASELINE_OFFSET_INDEX = 1;
  private static final int MAX_CUSTOM_ELLIPSIS_STEP_BACKS = 16;

  @VisibleForTesting
  public interface DebugMeasureListener {

    void onTextMeasured(
        TextRenderUnit renderUnit, CharSequence text, int widthSpec, int heightSpec);
  }

  @VisibleForTesting public static DebugMeasureListener sDebugMeasureListener;

  public static class TextLayout {

    public Layout layout;
    public CharSequence processedText;
    @Nullable CharSequence fullText;
    public float textLayoutTranslationX;
    public float textLayoutTranslationY;
    ClickableSpan[] clickableSpans;
    ImageSpan[] imageSpans;
    TextStyle textStyle;
    boolean isExplicitlyTruncated;

    /** A copy that reuses this measured layout for new full text and style. */
    TextLayout copyForReuse(CharSequence fullText, TextStyle textStyle) {
      final TextLayout copy = new TextLayout();
      copy.layout = layout;
      copy.processedText = processedText;
      copy.fullText = fullText;
      copy.textLayoutTranslationX = textLayoutTranslationX;
      copy.textLayoutTranslationY = textLayoutTranslationY;
      copy.clickableSpans = clickableSpans;
      copy.imageSpans = imageSpans;
      copy.textStyle = textStyle;
      copy.isExplicitlyTruncated = isExplicitlyTruncated;
      return copy;
    }
  }

  public static MountableLayoutResult layout(
      LayoutContext<?> context,
      int widthSpec,
      int heightSpec,
      CharSequence text,
      TextRenderUnit renderUnit,
      TextStyle textStyle) {
    return layout(context, widthSpec, heightSpec, text, renderUnit, textStyle, false);
  }

  public static MountableLayoutResult layout(
      LayoutContext<?> context,
      int widthSpec,
      int heightSpec,
      CharSequence text,
      TextRenderUnit renderUnit,
      TextStyle textStyle,
      boolean usePerformantTruncation) {
    final DebugMeasureListener debugListener = sDebugMeasureListener;
    final Context androidContext = context.getAndroidContext();
    if (debugListener != null) {
      debugListener.onTextMeasured(renderUnit, text, widthSpec, heightSpec);
    }

    Pair<Rect, TextLayout> result =
        layout(androidContext, widthSpec, heightSpec, text, textStyle, usePerformantTruncation);
    if (textStyle.roundedBackgroundProps != null && text instanceof Spannable) {
      result =
          calculateLayoutWithBackgroundSpan(
              androidContext, result, textStyle, (Spannable) text, widthSpec, heightSpec);
    }

    return new MountableLayoutResult(
        renderUnit, result.first.width(), result.first.height(), result.second);
  }

  public static Pair<Rect, TextLayout> calculateLayoutWithBackgroundSpan(
      final Context context,
      final Pair<Rect, TextLayout> result,
      final TextStyle textStyle,
      final Spannable text,
      int widthSpec,
      int heightSpec) {

    final TextStyle.RoundedBackgroundProps roundedBackgroundProps =
        textStyle.roundedBackgroundProps;

    TextMeasurementUtils.TextLayout textLayout = result.second;
    float topPadding = roundedBackgroundProps.padding.top;
    float bottomPadding = roundedBackgroundProps.padding.bottom;
    float startPadding = roundedBackgroundProps.padding.left;
    float endPadding = roundedBackgroundProps.padding.right;
    float cornerRadius = roundedBackgroundProps.cornerRadius;
    int backgroundColor = roundedBackgroundProps.backgroundColor;

    if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
      // check if the specified vertical paddings can be applied without the background being cut
      // off and adjust if needed
      final int availableHeight = View.MeasureSpec.getSize(heightSpec);
      final float measuredHeight =
          LayoutMeasureUtil.getHeight(textLayout.layout) + topPadding + bottomPadding;
      final float maximumYPadding = (availableHeight - measuredHeight) / 2;
      if (availableHeight < measuredHeight) {
        topPadding = Math.abs(Math.min(topPadding, Math.max(0, maximumYPadding)));
        bottomPadding = Math.abs(Math.min(bottomPadding, Math.max(0, maximumYPadding)));
      }
    }

    if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
      // check if the specified horizontal paddings can be applied without the background being cut
      // off and adjust if needed
      final int availableWidth = View.MeasureSpec.getSize(widthSpec);
      int maxLineWidth = 0;
      final Layout layout = textLayout.layout;
      for (int i = 0; i < layout.getLineCount(); i++) {
        maxLineWidth = (int) Math.max(maxLineWidth, layout.getLineWidth(i));
      }
      final float measuredWidth = maxLineWidth + startPadding + endPadding;
      final int maximumXPadding = (availableWidth - maxLineWidth) / 2;
      if (availableWidth < measuredWidth) {
        startPadding = Math.min(startPadding, Math.max(0, maximumXPadding));
        endPadding = Math.min(endPadding, Math.max(0, maximumXPadding));
      }
    }

    final RoundedBackgroundColorSpan span =
        new RoundedBackgroundColorSpan(
            textLayout.layout,
            backgroundColor,
            startPadding,
            endPadding,
            topPadding,
            bottomPadding,
            cornerRadius);
    text.setSpan(span, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    textStyle.extraSpacingLeft = startPadding;
    textStyle.extraSpacingRight = endPadding;

    // run layout to ensure that spacing and spans are applied to processed text
    final Pair<Rect, TextLayout> intermediateResult =
        TextMeasurementUtils.layout(context, widthSpec, heightSpec, text, textStyle);
    textLayout = intermediateResult.second;
    // we just ran layout, so textLayoutContext should not be null
    textLayout.textLayoutTranslationY = topPadding;

    switch (textStyle.alignment) {
      case CENTER:
        if (textLayout.layout.getLineCount() == 1) {
          // This is required for padding to work correctly with one line
          textLayout.textLayoutTranslationX = (startPadding + endPadding) / 2f;
        }
        break;
      case TEXT_START:
        textLayout.textLayoutTranslationX = startPadding;
        break;
      case TEXT_END:
        textLayout.textLayoutTranslationX =
            intermediateResult.first.width()
                - (LayoutMeasureUtil.getWidth(textLayout.layout) + endPadding);
        break;
    }

    return new Pair<>(
        new Rect(
            0,
            0,
            intermediateResult.first.width(),
            LayoutMeasureUtil.getHeight(textLayout.layout) + (int) (topPadding + bottomPadding)),
        textLayout);
  }

  public static Pair<Rect, TextLayout> layout(
      Context context, int widthSpec, int heightSpec, CharSequence text, TextStyle textStyle) {
    return layout(
        context, widthSpec, heightSpec, text, textStyle, RenderCoreConfig.usePerformantTruncation);
  }

  public static Pair<Rect, TextLayout> layout(
      Context context,
      int widthSpec,
      int heightSpec,
      CharSequence text,
      TextStyle textStyle,
      boolean usePerformantTruncation) {
    return layout(context, widthSpec, heightSpec, text, textStyle, usePerformantTruncation, false);
  }

  public static Pair<Rect, TextLayout> layout(
      Context context,
      int widthSpec,
      int heightSpec,
      CharSequence text,
      TextStyle textStyle,
      boolean usePerformantTruncation,
      boolean useLegacyTextCustomEllipsisOffsetFix) {
    CharSequence processedText = text;
    final TextLayout textLayout = new TextLayout();
    textLayout.textStyle = textStyle;

    if (TextUtils.isEmpty(processedText) && !textStyle.shouldLayoutEmptyText) {
      textLayout.processedText = processedText;
      return new Pair<>(new Rect(0, 0, 0, 0), textLayout);
    }
    Layout layout =
        TextMeasurementUtils.createTextLayout(
            context, textStyle, widthSpec, heightSpec, processedText);

    // check if the layout should truncate based on the size constraints
    int linesWithinConstrainedBounds = -1;
    if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
      final int heightConstraint = View.MeasureSpec.getSize(heightSpec);
      final boolean fitTextToConstraints =
          textStyle.shouldTruncateTextUsingConstraints
              && textStyle.maxLines == Integer.MAX_VALUE
              && LayoutMeasureUtil.getHeight(layout) > heightConstraint;

      if (fitTextToConstraints) {
        linesWithinConstrainedBounds = 1;
        int lineIndex = layout.getLineCount() - 1;
        while (lineIndex >= 0) {
          if (layout.getLineBottom(lineIndex) <= heightConstraint) {
            linesWithinConstrainedBounds = lineIndex + 1;
            break;
          }

          lineIndex -= 1;
        }
      }
    }

    if (linesWithinConstrainedBounds != -1) {
      // we have constrained the number of lines that can fit, so truncate the layout
      textStyle.maxLines = linesWithinConstrainedBounds;
      layout =
          TextMeasurementUtils.createTextLayout(
              context, textStyle, widthSpec, heightSpec, processedText);
    }

    final Pair<Integer, Boolean> resolvedWidth = resolveWidth(widthSpec, layout, textStyle);
    final int layoutWidth = resolvedWidth.first;
    final boolean isMinimallyWide = resolvedWidth.second;

    // Handle custom text truncation:
    final CharSequence customEllipsisText = textStyle.customEllipsisText;
    if (customEllipsisText != null && !customEllipsisText.equals("")) {
      CharSequence maybeTruncated = processedText;
      boolean isTruncated = false;
      if (textStyle.truncationStyle == TruncationStyle.FORCE_INLINE_TRUNCATION) {
        int maxLines = getEllipsizedLineNumber(layout);
        if (maxLines != -1) {
          int lastLineWithWordsOrNumbers =
              lastLineWithWordsOrNumbers(layout, maxLines, processedText);
          layout =
              TextMeasurementUtils.createTextLayout(
                  context,
                  textStyle,
                  widthSpec,
                  heightSpec,
                  processedText,
                  lastLineWithWordsOrNumbers);
        }
      }
      final int ellipsizedLineNumber = getEllipsizedLineNumber(layout);
      if (ellipsizedLineNumber != -1) {
        maybeTruncated =
            truncateText(
                processedText,
                customEllipsisText,
                layout,
                ellipsizedLineNumber,
                layoutWidth,
                usePerformantTruncation,
                useLegacyTextCustomEllipsisOffsetFix,
                textStyle);
        isTruncated = true;
      }
      if (isTruncated) {
        layout =
            TextMeasurementUtils.createTextLayout(
                context,
                textStyle,
                View.MeasureSpec.makeMeasureSpec(layoutWidth, View.MeasureSpec.EXACTLY),
                heightSpec,
                maybeTruncated);
        if (maybeTruncated != processedText
            && (keepsAdvanceOffset(useLegacyTextCustomEllipsisOffsetFix, usePerformantTruncation)
                || textStyle.useLegacyTextCustomEllipsisCut)) {
          // The kept offset is the closest one, so the prefix can be one grapheme too long and
          // Android ellipsizes the custom ellipsis itself. Step back until it fits.
          final BreakIterator graphemes = BreakIterator.getCharacterInstance();
          graphemes.setText(processedText.toString());
          int prefixLength = maybeTruncated.length() - customEllipsisText.length();
          if (!graphemes.isBoundary(prefixLength)) {
            // Never keep part of a grapheme, like a letter without its combining accent.
            prefixLength = graphemes.preceding(prefixLength);
            maybeTruncated =
                TextUtils.concat(processedText.subSequence(0, prefixLength), customEllipsisText);
            layout =
                TextMeasurementUtils.createTextLayout(
                    context,
                    textStyle,
                    View.MeasureSpec.makeMeasureSpec(layoutWidth, View.MeasureSpec.EXACTLY),
                    heightSpec,
                    maybeTruncated);
          }
          for (int i = 0;
              i < MAX_CUSTOM_ELLIPSIS_STEP_BACKS && getEllipsizedLineNumber(layout) != -1;
              i++) {
            prefixLength =
                prefixLength > 0 ? graphemes.preceding(prefixLength) : BreakIterator.DONE;
            if (prefixLength == BreakIterator.DONE) {
              break;
            }
            maybeTruncated =
                TextUtils.concat(processedText.subSequence(0, prefixLength), customEllipsisText);
            layout =
                TextMeasurementUtils.createTextLayout(
                    context,
                    textStyle,
                    View.MeasureSpec.makeMeasureSpec(layoutWidth, View.MeasureSpec.EXACTLY),
                    heightSpec,
                    maybeTruncated);
          }
          if (getEllipsizedLineNumber(layout) != -1 && prefixLength > 0) {
            // Out of steps and still cut by Android: the base-paint advance misjudged text with
            // size-changing spans. The suffix can need earlier lines too, so search shorter
            // prefixes down to the suffix alone and keep the longest that fits.
            final Pair<CharSequence, Layout> fitting =
                longestFittingPrefix(
                    context,
                    textStyle,
                    layoutWidth,
                    heightSpec,
                    processedText,
                    customEllipsisText,
                    graphemes,
                    0,
                    prefixLength);
            if (fitting != null) {
              maybeTruncated = fitting.first;
              layout = fitting.second;
            }
          }
        }
        processedText = maybeTruncated;
        textLayout.fullText = text;
        textLayout.isExplicitlyTruncated = true;
      }
    }

    // Adjust height according to the minimum number of lines.
    int preferredHeight = LayoutMeasureUtil.getHeight(layout);
    int extraSpacingHeight = 0;
    if (textStyle.lineHeightMultiplier > 1f && textStyle.shouldAddSpacingExtraToFirstLine) {
      final TextPaint paint = layout.getPaint();

      final int layoutLineHeight = paint.getFontMetricsInt(null);
      extraSpacingHeight = (int) (layoutLineHeight * (textStyle.lineHeightMultiplier - 1.0f));
      preferredHeight += extraSpacingHeight;
    }

    final int lineCount = layout.getLineCount();
    if (lineCount < textStyle.minLines) {
      final TextPaint paint = layout.getPaint();

      final int layoutLineHeight =
          Math.round(
              paint.getFontMetricsInt(null) * textStyle.lineHeightMultiplier
                  + textStyle.lineSpacingExtra);
      preferredHeight += layoutLineHeight * (textStyle.minLines - lineCount);
    }

    final float textHeight = LayoutMeasureUtil.getHeight(layout);
    final int capHeightOffset;

    if (hasManualSpacing(textStyle)) {
      final int[] capHeights = getCapHeightBaselineSpacing(layout.getPaint(), processedText);

      final int baselineOffset = capHeights[BASELINE_OFFSET_INDEX];
      capHeightOffset = capHeights[CAP_HEIGHT_OFFSET_INDEX] - textStyle.manualCapSpacing;
      preferredHeight -= (capHeightOffset + baselineOffset);
      preferredHeight += textStyle.manualBaselineSpacing;
    } else {
      capHeightOffset = 0;
    }

    final int layoutHeight = View.resolveSize(preferredHeight, heightSpec);

    float textLayoutTranslationY;
    switch (textStyle.verticalGravity) {
      case CENTER:
        textLayoutTranslationY =
            (layoutHeight - textHeight) / 2 + extraSpacingHeight - capHeightOffset;
        break;

      case BOTTOM:
        textLayoutTranslationY = layoutHeight - textHeight + extraSpacingHeight - capHeightOffset;
        break;

      default:
        textLayoutTranslationY = extraSpacingHeight - capHeightOffset;
        break;
    }

    textLayout.processedText = processedText;
    textLayout.layout = layout;
    if (textStyle.alignment == TextAlignment.TEXT_START) {
      textLayout.textLayoutTranslationX = textStyle.extraSpacingLeft;
    } else if (textStyle.alignment == TextAlignment.TEXT_END) {
      textLayout.textLayoutTranslationX = -textStyle.extraSpacingRight;
    }

    // Apply minimallyWide translation adjustment to properly center/align text
    // within the minimally-wide container.
    if (isMinimallyWide && layout.getLineCount() > 1) {
      float leftMost = layoutWidth;
      for (int i = 0; i < layout.getLineCount(); i++) {
        leftMost = Math.min(leftMost, layout.getLineLeft(i));
      }
      textLayout.textLayoutTranslationX = -leftMost;
    }

    textLayout.textLayoutTranslationY = textLayoutTranslationY;
    if (processedText instanceof Spanned) {
      Spanned spanned = (Spanned) processedText;
      textLayout.clickableSpans = spanned.getSpans(0, processedText.length(), ClickableSpan.class);
      textLayout.imageSpans = spanned.getSpans(0, processedText.length(), ImageSpan.class);
    }

    return new Pair<>(new Rect(0, 0, layoutWidth, layoutHeight), textLayout);
  }

  @VisibleForTesting
  protected static Pair<Integer, Boolean> resolveWidth(
      int widthSpec, Layout layout, TextStyle textStyle) {
    int fullWidth =
        View.resolveSize(
            layout.getWidth()
                + Math.round(textStyle.extraSpacingLeft + textStyle.extraSpacingRight),
            widthSpec);
    if (textStyle.minimallyWide && layout.getLineCount() > 1) {
      float leftMost = fullWidth;
      float rightMost = 0;
      for (int i = 0, count = layout.getLineCount(); i < count; i++) {
        leftMost = Math.min(leftMost, layout.getLineLeft(i));
        rightMost = Math.max(rightMost, layout.getLineRight(i));
      }
      // To determine the width of the longest line, which is also the minimum width we desire,
      // without leading and trailing whitespaces.
      final int minimalWidth = View.resolveSize((int) (rightMost - leftMost), widthSpec);

      if (fullWidth - minimalWidth > textStyle.minimallyWideThreshold) {
        return new Pair<>(minimalWidth, true);
      }
    }

    return new Pair<>(fullWidth, false);
  }

  static Layout createTextLayout(
      Context context, TextStyle textStyle, int widthSpec, int heightSpec, CharSequence text) {
    return createTextLayout(context, textStyle, widthSpec, heightSpec, text, textStyle.maxLines);
  }

  static Layout createTextLayout(
      Context context,
      TextStyle textStyle,
      int widthSpec,
      int heightSpec,
      CharSequence text,
      int maxLines) {
    TextLayoutBuilder layoutBuilder = new TextLayoutBuilder();
    layoutBuilder.setShouldCacheLayout(false);

    @TextLayoutBuilder.MeasureMode final int textMeasureMode;
    switch (View.MeasureSpec.getMode(widthSpec)) {
      case View.MeasureSpec.UNSPECIFIED:
        textMeasureMode = TextLayoutBuilder.MEASURE_MODE_UNSPECIFIED;
        break;
      case View.MeasureSpec.EXACTLY:
        textMeasureMode = TextLayoutBuilder.MEASURE_MODE_EXACTLY;
        break;
      case View.MeasureSpec.AT_MOST:
        textMeasureMode = TextLayoutBuilder.MEASURE_MODE_AT_MOST;
        break;
      default:
        throw new IllegalStateException(
            "Unexpected size mode: " + View.MeasureSpec.getMode(widthSpec));
    }

    final TextUtils.TruncateAt actualEllipsize;
    if (textStyle.ellipsize == null && textStyle.maxLines != Integer.MAX_VALUE) {
      // On recent apis (> 24) max lines is no longer considered for calculating layout height if an
      // ellipsize method isn't specified. To keep consistent behavior across platforms we default
      // to end if you specify maxLines but not ellipsize.
      actualEllipsize = TextUtils.TruncateAt.END;
    } else {
      actualEllipsize = textStyle.ellipsize;
    }

    final boolean includeFontPadding = textStyle.includeFontPadding && !hasManualSpacing(textStyle);

    layoutBuilder
        .setDensity(context.getResources().getDisplayMetrics().density)
        .setEllipsize(actualEllipsize)
        .setMaxLines(maxLines)
        .setShadowLayer(
            textStyle.shadowRadius, textStyle.shadowDx, textStyle.shadowDy, textStyle.shadowColor)
        .setSingleLine(textStyle.isSingleLine)
        .setText(text)
        .setTextSize(textStyle.textSize)
        .setWidth(View.MeasureSpec.getSize(widthSpec), textMeasureMode)
        .setIncludeFontPadding(includeFontPadding)
        .setTextSpacingExtra(textStyle.lineSpacingExtra)
        .setTextSpacingMultiplier(textStyle.lineHeightMultiplier)
        .setLinkColor(textStyle.linkColor)
        .setJustificationMode(textStyle.justificationMode)
        .setBreakStrategy(textStyle.breakStrategy)
        .setHyphenationFrequency(textStyle.hyphenationFrequency)
        .setShouldLayoutZeroLengthText(textStyle.shouldLayoutEmptyText);

    if (textStyle.lineHeight != Float.MAX_VALUE) {
      layoutBuilder.setLineHeight(textStyle.lineHeight);
    }

    layoutBuilder.setLetterSpacing(textStyle.letterSpacing);

    if (textStyle.minEms != TextStyle.UNSET) {
      layoutBuilder.setMinEms(textStyle.minEms);
    } else {
      layoutBuilder.setMinWidth(textStyle.minTextWidth);
    }

    if (textStyle.maxEms != TextStyle.UNSET) {
      layoutBuilder.setMaxEms(textStyle.maxEms);
    } else {
      layoutBuilder.setMaxWidth(textStyle.maxTextWidth);
    }

    if (textStyle.getTextColor() != 0) {
      layoutBuilder.setTextColor(textStyle.getTextColor());
    } else {
      layoutBuilder.setTextColor(textStyle.getTextColorStateList());
    }

    if (textStyle.typeface != null) {
      layoutBuilder.setTypeface(textStyle.typeface);
    } else {
      layoutBuilder.setTextStyle(textStyle.textStyle);
    }

    final Boolean resolvedLayoutDirectionRtl = textStyle.resolvedLayoutDirectionRtl;
    final boolean isLayoutDirectionRTL =
        resolvedLayoutDirectionRtl != null
            ? resolvedLayoutDirectionRtl
            : LayoutUtils.isLayoutDirectionRTL(context);
    // TEXT_START/TEXT_END follow the text's own direction when it's set manually or the host
    // resolved the layout direction; otherwise they're relative to the locale on the first layout
    // of a style. That branch writes the derived direction back into the style, so later layouts
    // of the same style take the first branch.
    final boolean ignoreLayoutDirection;
    final TextDirectionHeuristicCompat textDirection;
    if (textStyle.textDirection != null) {
      ignoreLayoutDirection = true;
      textDirection = textStyle.textDirection;
    } else if (resolvedLayoutDirectionRtl != null) {
      ignoreLayoutDirection = true;
      textDirection =
          isLayoutDirectionRTL
              ? TextDirectionHeuristicsCompat.FIRSTSTRONG_RTL
              : TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR;
    } else {
      ignoreLayoutDirection = false;
      textStyle.textDirection =
          isLayoutDirectionRTL
              ? TextDirectionHeuristicsCompat.FIRSTSTRONG_RTL
              : TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR;
      textDirection = textStyle.textDirection;
    }
    layoutBuilder.setTextDirection(textDirection);

    final boolean isTextDirectionRTL = textDirection.isRtl(text, 0, text.length());

    final Layout.Alignment textAlignment;
    switch (textStyle.alignment) {
      default:
      case TEXT_START:
        textAlignment =
            ignoreLayoutDirection || (isLayoutDirectionRTL == isTextDirectionRTL)
                ? Layout.Alignment.ALIGN_NORMAL
                : Layout.Alignment.ALIGN_OPPOSITE;
        break;
      case TEXT_END:
        textAlignment =
            ignoreLayoutDirection || (isLayoutDirectionRTL == isTextDirectionRTL)
                ? Layout.Alignment.ALIGN_OPPOSITE
                : Layout.Alignment.ALIGN_NORMAL;
        break;
      case LAYOUT_START:
        textAlignment =
            (isLayoutDirectionRTL == isTextDirectionRTL)
                ? Layout.Alignment.ALIGN_NORMAL
                : Layout.Alignment.ALIGN_OPPOSITE;
        break;
      case LAYOUT_END:
        textAlignment =
            (isLayoutDirectionRTL == isTextDirectionRTL)
                ? Layout.Alignment.ALIGN_OPPOSITE
                : Layout.Alignment.ALIGN_NORMAL;
        break;
      case LEFT:
        textAlignment =
            isTextDirectionRTL ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        break;
      case RIGHT:
        textAlignment =
            isTextDirectionRTL ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
        break;
      case CENTER:
        textAlignment = Layout.Alignment.ALIGN_CENTER;
        break;
    }
    layoutBuilder.setAlignment(textAlignment);

    return layoutBuilder.build();
  }

  private static boolean hasManualSpacing(TextStyle textStyle) {
    return textStyle.manualCapSpacing != Integer.MIN_VALUE
        && textStyle.manualBaselineSpacing != Integer.MIN_VALUE;
  }

  private static boolean hasNoWordsOrNumbers(CharSequence line) {
    for (int i = 0; i < line.length(); ++i) {
      char character = line.charAt(i);
      if (Character.isAlphabetic(character) || Character.isDigit(character)) {
        return false;
      }
    }
    return true;
  }

  private static int lastLineWithWordsOrNumbers(
      Layout newLayout, int ellipsizedLineNumber, CharSequence text) {
    for (int i = ellipsizedLineNumber; i > 0; --i) {
      if (!hasNoWordsOrNumbers(
          text.subSequence(newLayout.getLineStart(i), newLayout.getLineVisibleEnd(i)))) {
        return i + 1;
      }
    }
    return 1;
  }

  /**
   * Truncates text which is too long and appends the given custom ellipsis CharSequence to the end
   * of the visible text.
   *
   * @param text Text to truncate
   * @param customEllipsisText Text to append to the end to indicate truncation happened
   * @param newLayout A Layout object populated with measurement information for this text
   * @param ellipsizedLineNumber The line number within the text at which truncation occurs (i.e.
   *     the last visible line).
   * @param fullLayoutWidth The width of the text from the WidthSpec before subtracting extra
   *     spacing
   * @param usePerformantTruncation Whether to use getOffsetForAdvance rather than
   *     getOffsetForHorizontal to prevent ANRs
   * @param textStyle A TextStyle object that is used to determine extra spacing and the truncation
   *     style
   * @return The provided text truncated in such a way that the 'customEllipsisText' can appear at
   *     the end.
   */
  @VisibleForTesting
  static CharSequence truncateText(
      CharSequence text,
      CharSequence customEllipsisText,
      Layout newLayout,
      int ellipsizedLineNumber,
      float fullLayoutWidth,
      boolean usePerformantTruncation,
      TextStyle textStyle) {
    return truncateText(
        text,
        customEllipsisText,
        newLayout,
        ellipsizedLineNumber,
        fullLayoutWidth,
        usePerformantTruncation,
        false,
        textStyle);
  }

  static CharSequence truncateText(
      CharSequence text,
      CharSequence customEllipsisText,
      Layout newLayout,
      int ellipsizedLineNumber,
      float fullLayoutWidth,
      boolean usePerformantTruncation,
      boolean useLegacyTextCustomEllipsisOffsetFix,
      TextStyle textStyle) {
    // Identify the X position at which to truncate the final line:
    // Note: The left position of the line is needed for the case of RTL text.
    final float layoutWidth =
        fullLayoutWidth - textStyle.extraSpacingLeft - textStyle.extraSpacingRight;
    final float ellipsisTextWidth =
        BoringLayout.getDesiredWidth(
            customEllipsisText, 0, customEllipsisText.length(), newLayout.getPaint());
    final boolean isRTL =
        newLayout.getParagraphDirection(ellipsizedLineNumber) == Layout.DIR_RIGHT_TO_LEFT;

    // From local tests, correct truncation with getOffsetForAdvance doesn't depend on
    // newLayout.getLineLeft(ellipsizedLineNumber) and including it can cause incorrect rendering.
    // I suspect it was also incorrect for the previous getOffsetForAdvance. Regardless, we want to
    // test it so are putting it behind the same flag.
    final float ellipsisTarget;
    if (usePerformantTruncation) {
      // getOffsetForAdvance counts from the run's start, the right edge for RTL, so the RTL target
      // is the width left for text, as in legacy Text.
      ellipsisTarget =
          isRTL
              ? (textStyle.useLegacyTextCustomEllipsisCut
                  ? layoutWidth - ellipsisTextWidth
                  : ellipsisTextWidth)
              : layoutWidth
                  - ellipsisTextWidth
                  - newLayout.getLineLeft(ellipsizedLineNumber)
                  - newLayout.getParagraphLeft(ellipsizedLineNumber);
    } else {
      ellipsisTarget =
          isRTL
              ? ellipsisTextWidth
              : layoutWidth - ellipsisTextWidth + newLayout.getLineLeft(ellipsizedLineNumber);
    }

    // Get character offset number corresponding to that X position:
    int ellipsisOffset;
    if (usePerformantTruncation && Build.VERSION.SDK_INT >= 23) {
      int lineTextStart = newLayout.getLineStart(ellipsizedLineNumber);
      int lineTextEnd = newLayout.getLineEnd(ellipsizedLineNumber);
      ellipsisOffset =
          newLayout
              .getPaint()
              .getOffsetForAdvance(
                  text,
                  lineTextStart,
                  lineTextEnd,
                  lineTextStart,
                  lineTextEnd,
                  isRTL,
                  ellipsisTarget);
    } else {
      ellipsisOffset = newLayout.getOffsetForHorizontal(ellipsizedLineNumber, ellipsisTarget);
    }

    if (ellipsisOffset > 0) {
      // Both lookups return the closest character, which may not fit, so the original
      // implementation subtracts one to make room. Legacy Text with its offset fix keeps that
      // character; layout() then steps back if it doesn't fit.
      if (!keepsAdvanceOffset(useLegacyTextCustomEllipsisOffsetFix, usePerformantTruncation)) {
        ellipsisOffset -= 1;
      }

      // Ensure that we haven't chosen an ellipsisOffset that's past the end of the ellipsis start.
      // This can occur in several cases, including when the width of the customEllipsisText is less
      // than the width of the default ellipsis character, and when in RTL mode and there is
      // whitespace to the left of the text. In these cases, getOffsetForHorizontal will return the
      // end of the string because our ellipsisTarget was in the middle of the ellipsis character.
      if (newLayout.getEllipsisCount(ellipsizedLineNumber) > 0) {
        final int ellipsisStart =
            newLayout.getLineStart(ellipsizedLineNumber)
                + newLayout.getEllipsisStart(ellipsizedLineNumber);
        if (ellipsisOffset > ellipsisStart) {
          ellipsisOffset = ellipsisStart;
        }
      }

      // Don't split in the middle of a surrogate pair. If ellipsisOffset points
      // to a low surrogate (the second half of a surrogate pair), move back one
      // more position to truncate before the surrogate pair.
      if (ellipsisOffset > 0
          && ellipsisOffset < text.length()
          && Character.isLowSurrogate(text.charAt(ellipsisOffset))) {
        ellipsisOffset -= 1;
      }

      if (ellipsisOffset >= 0 && ellipsisOffset < text.length()) {
        return TextUtils.concat(text.subSequence(0, ellipsisOffset), customEllipsisText);
      } else {
        return text;
      }
    } else {
      return text;
    }
  }

  /**
   * The longest prefix ending on a grapheme boundary in [{@code from}, {@code before}) that,
   * followed by {@code suffix}, lays out without Android ellipsizing it, or null if none does.
   * Longer prefixes take more room, so the boundaries are binary searched.
   */
  private static @Nullable Pair<CharSequence, Layout> longestFittingPrefix(
      Context context,
      TextStyle textStyle,
      int layoutWidth,
      int heightSpec,
      CharSequence text,
      CharSequence suffix,
      BreakIterator graphemes,
      int from,
      int before) {
    final List<Integer> boundaries = new ArrayList<>();
    final int first = graphemes.isBoundary(from) ? from : graphemes.following(from);
    for (int offset = first;
        offset != BreakIterator.DONE && offset < before;
        offset = graphemes.following(offset)) {
      boundaries.add(offset);
    }
    @Nullable Pair<CharSequence, Layout> best = null;
    int low = 0;
    int high = boundaries.size() - 1;
    while (low <= high) {
      final int middle = (low + high) >>> 1;
      final CharSequence candidate =
          TextUtils.concat(text.subSequence(0, boundaries.get(middle)), suffix);
      final Layout layout =
          TextMeasurementUtils.createTextLayout(
              context,
              textStyle,
              View.MeasureSpec.makeMeasureSpec(layoutWidth, View.MeasureSpec.EXACTLY),
              heightSpec,
              candidate);
      if (getEllipsizedLineNumber(layout) == -1) {
        best = new Pair<>(candidate, layout);
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return best;
  }

  /**
   * Whether truncation keeps the closest offset from {@code Paint.getOffsetForAdvance} instead of
   * one character less, as legacy Litho Text does with its custom ellipsis offset fix.
   */
  private static boolean keepsAdvanceOffset(
      boolean useLegacyTextCustomEllipsisOffsetFix, boolean usePerformantTruncation) {
    return useLegacyTextCustomEllipsisOffsetFix
        && usePerformantTruncation
        && Build.VERSION.SDK_INT >= 23;
  }

  /**
   * @param layout A prepared text layout object
   * @return The (zero-indexed) line number at which the text in this layout will be ellipsized, or
   *     -1 if no line will be ellipsized.
   */
  public static int getEllipsizedLineNumber(Layout layout) {
    for (int i = 0; i < layout.getLineCount(); ++i) {
      if (layout.getEllipsisCount(i) > 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the width of the layout.
   *
   * @param layout The layout.
   * @return The width of the layout.
   */
  public static int getWidth(Layout layout) {
    if (layout == null) {
      return 0;
    }

    // Supplying VERY_WIDE will make layout.getWidth() return a very large value.
    int count = layout.getLineCount();
    int maxWidth = 0;

    for (int i = 0; i < count; i++) {
      maxWidth = Math.max(maxWidth, (int) layout.getLineRight(i));
    }

    return maxWidth;
  }

  /**
   * @param layout The layout.
   * @return The height of the layout.
   */
  public static int getHeight(@Nullable Layout layout) {
    if (layout == null) {
      return 0;
    }

    return layout.getHeight();
  }

  /**
   * Return the cap height values array {cap_height, cap_height_offset, baseline_offset} for the
   * given text size and typeface.
   */
  private static @Size(2) int[] getCapHeightBaselineSpacing(Paint paint, CharSequence text) {
    final TextPaint capTextPaint = new TextPaint(paint);
    Paint.FontMetricsInt fontMetricsInt = null;

    if (text instanceof Spanned && text.length() > 0) {
      MetricAffectingSpan[] spans = ((Spanned) text).getSpans(0, 0, MetricAffectingSpan.class);
      for (int i = 0; i < spans.length; i++) {
        spans[i].updateMeasureState(capTextPaint);
      }
    }

    final int hashCode = getKey(capTextPaint.getTextSize(), capTextPaint.getTypeface());

    int capHeight;
    synchronized (sCapHeightsCache) {
      capHeight = sCapHeightsCache.get(hashCode, Integer.MIN_VALUE);
    }

    if (capHeight == Integer.MIN_VALUE) {
      final Rect rect = new Rect();
      fontMetricsInt = new Paint.FontMetricsInt();
      capTextPaint.getFontMetricsInt(fontMetricsInt);
      capTextPaint.getTextBounds(CAP_MEASUREMENT_TEXT, 0, CAP_MEASUREMENT_TEXT.length(), rect);
      capHeight = (-1 * fontMetricsInt.ascent) - rect.height();
      synchronized (sCapHeightsCache) {
        sCapHeightsCache.put(hashCode, capHeight);
      }
    }
    final TextPaint baselineTextPaint = new TextPaint(paint);
    if (text instanceof Spanned && text.length() > 0) {
      MetricAffectingSpan[] spans =
          ((Spanned) text)
              .getSpans(text.length() - 1, text.length() - 1, MetricAffectingSpan.class);
      for (int i = 0; i < spans.length; i++) {
        spans[i].updateMeasureState(baselineTextPaint);
      }
    }
    final int baselineHashCode =
        getKey(baselineTextPaint.getTextSize(), baselineTextPaint.getTypeface());
    int baseline;
    synchronized (sBaselineCache) {
      baseline = sBaselineCache.get(baselineHashCode, Integer.MIN_VALUE);
    }
    if (baseline == Integer.MIN_VALUE) {
      if (fontMetricsInt == null
          || capTextPaint.getTextSize() != baselineTextPaint.getTextSize()
          || capTextPaint.getTypeface() != baselineTextPaint.getTypeface()) {
        fontMetricsInt = new Paint.FontMetricsInt();
        baselineTextPaint.getFontMetricsInt(fontMetricsInt);
      }

      baseline = fontMetricsInt.descent;
      synchronized (sBaselineCache) {
        sBaselineCache.put(baselineHashCode, baseline);
      }
    }

    return new int[] {capHeight, baseline};
  }

  /**
   * Get the key for a given size + typeface.
   *
   * <p>We use the size + typeface as a key without checking for collisions. In theory this could
   * collide, in which case we will return incorrect values.
   */
  private static int getKey(float textSizePx, Typeface typeface) {
    // Hash code implementation, see https://stackoverflow.com/questions/113511/
    return 31 * (int) textSizePx + typeface.hashCode();
  }
}
