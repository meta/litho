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

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static com.facebook.rendercore.text.accessibility.AccessibilityUtils.ACCESSIBILITY_ROLE_LINK;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.MetaKeyKeyListener;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.DoNotInline;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import com.facebook.infer.annotation.FalseOnNull;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.proguard.annotations.DoNotStrip;
import com.facebook.rendercore.ErrorReporter;
import com.facebook.rendercore.LogLevel;
import com.facebook.rendercore.text.TextMeasurementUtils.TextLayout;
import com.facebook.rendercore.text.accessibility.AccessibilityUtils;
import com.facebook.rendercore.text.accessibility.RCAccessibleClickableSpan;
import com.facebook.rendercore.text.selection.RCTextEditor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A custom Android View that behaves like a TextView providing support for spans, custom drawing
 * and truncations.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
@DoNotStrip
public class RCTextView extends ViewGroup {
  private static final String ACCESSIBILITY_CLASS_BUTTON = "android.widget.Button";
  // See TextView#getTextForAccessibility()
  private static final int SAFE_PARCELABLE_SIZE = 1000000;

  // Ported from AOSP TextView.TEMP_POSITION (TextView.java:414). Static array reused across calls
  // to isPositionVisible() to avoid per-call allocation; guarded by synchronized(TEMP_POSITION).
  private static final float[] TEMP_POSITION = new float[2];
  @Nullable private final RCTextAccessibilityDelegate mRCTextAccessibilityDelegate;
  @Nullable private final AccessibilityManager mAccessibilityManager;
  private final Region mClickableSpanAreaRegion = new Region();
  private final Path mClickableSpanAreaPath = new Path();
  // NULLSAFE_FIXME[Field Not Initialized]
  private CharSequence mText;
  @Nullable private CharSequence mFullText;
  // NULLSAFE_FIXME[Field Not Initialized]
  private ClickableSpan[] mClickableSpans;
  // NULLSAFE_FIXME[Field Not Initialized]
  private Layout mLayout;
  private float mLayoutTranslationX;
  private float mLayoutTranslationY;
  private boolean mClipToBounds;
  private boolean mTextBelongsToHost;
  @Nullable private ColorStateList mColorStateList;
  private int mLinkColor;
  @Nullable private Integer mDynamicTextColor;
  // The layout's text color when mounted. The layout's paint can be shared with other views, so
  // drawing never reads the base color back from it.
  private int mMountedTextColor;
  private int mHighlightColor;
  private int mKeyboardHighlightColor;
  private int mHighlightCornerRadius;
  private int mSelectionHandleColor;
  private boolean mIsExplicitlyTruncated;
  // NULLSAFE_FIXME[Field Not Initialized]
  private ImageSpan[] mImageSpans;
  private int mSelectionStart;
  private int mSelectionEnd;
  private int mSelectionColor;
  // NULLSAFE_FIXME[Field Not Initialized]
  private Path mSelectionPath;
  private boolean mSelectionPathNeedsUpdate;
  // NULLSAFE_FIXME[Field Not Initialized]
  private Paint mHighlightPaint;
  private boolean mIsSettingDefaultAccessibilityDelegate = false;
  private final Rect mBounds = new Rect();
  private boolean mShouldHandleTouch;
  private boolean mShouldHandleKeyEvents;
  private boolean mLongClickActivated;
  private float mClickableSpanExpandedOffset;
  private boolean mTrackLongClickInLocalCoordinates;
  private boolean mClickSpanUnderRelease;
  private float mOutlineWidth;
  private int mOutlineColor;
  @Nullable private Path mTouchAreaPath;
  @Nullable private Integer mWasFocusable;
  private boolean mDidDisableDefaultFocusHighlight;
  @Nullable private TouchableSpanListener mTouchableSpanListener;
  @Nullable private ClickableSpan mCurrentlyTouchedSpan;
  @Nullable private ClickableSpanListener mClickableSpanListener;
  @Nullable private ClickableSpanAccessibilityProvider mClickableSpanAccessibilityProvider;
  private boolean mExposeClickableSpansToAccessibility = true;
  @Nullable private Handler mLongClickHandler;
  @Nullable private LongClickRunnable mLongClickRunnable;
  @Nullable private Map<Object, InlineViewHolder> mInlineViewHolders;

  // Text selection support
  private boolean mTextIsSelectable;
  @Nullable private RCTextEditor mEditor;
  // State saved/restored for text selection setup (separate from mWasFocusable which is for
  // keyboard highlight). Tracks whether we changed focusableInTouchMode and longClickable.
  private boolean mWasFocusableInTouchMode;
  private boolean mWasLongClickable;

  // Tracks MIUI vendor NPE in Layout.draw() to report if not recovered on next draw pass.
  private boolean mMiuiDrawFailed;
  private boolean mMiuiNpeReported;

  @SuppressWarnings("this-escape")
  public RCTextView(Context context) {
    super(context);
    setWillNotDraw(false);

    if (getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
      mRCTextAccessibilityDelegate = new RCTextAccessibilityDelegate();
      setDefaultAccessibilityDelegate();
      mAccessibilityManager =
          (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    } else {
      mRCTextAccessibilityDelegate = null;
      mAccessibilityManager = null;
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    if (mLayout == null || !(mText instanceof Spanned) || mInlineViewHolders == null) {
      return;
    }

    final Spanned text = (Spanned) mText;
    for (InlineViewHolder holder : mInlineViewHolders.values()) {
      if (!holder.active) {
        continue;
      }
      final InlineViewSpan span = holder.span;
      final View child = holder.view;
      final int spanStart = text.getSpanStart(span);
      final int spanEnd = text.getSpanEnd(span);
      if (spanStart < 0 || spanEnd <= spanStart) {
        child.setVisibility(GONE);
        continue;
      }

      final int line = mLayout.getLineForOffset(spanStart);
      final int visibleLineEnd = mLayout.getLineVisibleEnd(line);
      if (spanStart >= visibleLineEnd || spanEnd > visibleLineEnd) {
        child.setVisibility(GONE);
        continue;
      }
      if (isSpanEllipsized(mLayout, line, spanStart, spanEnd)) {
        child.setVisibility(GONE);
        continue;
      }

      final float startX = mLayout.getPrimaryHorizontal(spanStart);
      final float endX = mLayout.getPrimaryHorizontal(spanEnd);
      final Rect childBounds =
          span.getInlineViewBounds(mLayout.getLineAscent(line), mLayout.getLineDescent(line));
      final int spanLeft =
          Math.round(getPaddingLeft() + mLayoutTranslationX + Math.min(startX, endX));
      final int baseline =
          Math.round(getPaddingTop() + mLayoutTranslationY + mLayout.getLineBaseline(line));
      final int childWidth = childBounds.width();
      final int childHeight = childBounds.height();
      child.measure(
          MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
      child.setVisibility(VISIBLE);
      child.layout(
          spanLeft + childBounds.left,
          baseline + childBounds.top,
          spanLeft + childBounds.right,
          baseline + childBounds.bottom);
    }
  }

  private static boolean isSpanEllipsized(Layout layout, int line, int spanStart, int spanEnd) {
    final int ellipsisCount = layout.getEllipsisCount(line);
    if (ellipsisCount == 0) {
      return false;
    }
    final int ellipsisStart = layout.getLineStart(line) + layout.getEllipsisStart(line);
    final int ellipsisEnd = ellipsisStart + ellipsisCount;
    return spanStart < ellipsisEnd && spanEnd > ellipsisStart;
  }

  void mountInlineView(InlineViewSpan span) {
    final Object reuseKey = span.getInlineViewReuseKey();
    InlineViewHolder holder = mInlineViewHolders == null ? null : mInlineViewHolders.get(reuseKey);
    if (holder != null && holder.active) {
      ErrorReporter.report(
          LogLevel.ERROR,
          "RCTextView:DuplicateInlineViewKey",
          "Cannot mount the same inline component more than once in one text layout");
      return;
    }

    if (holder == null) {
      final View child = span.createInlineView(getContext());
      if (child == null) {
        return;
      }
      final ViewParent parent = child.getParent();
      if (parent != null) {
        ErrorReporter.report(
            LogLevel.ERROR,
            "RCTextView:InlineViewAlreadyParented",
            "Cannot mount inline view already attached to " + parent.getClass().getName());
        span.releaseInlineView(child);
        return;
      }
      addView(child);
      holder = new InlineViewHolder(span, child);
      if (mInlineViewHolders == null) {
        mInlineViewHolders = new HashMap<>();
      }
      mInlineViewHolders.put(reuseKey, holder);
    } else {
      holder.span = span;
    }

    final View child = holder.view;
    span.bindInlineView(child);
    child.setVisibility(VISIBLE);
    holder.active = true;
  }

  void unmountInlineView(InlineViewSpan span) {
    if (mInlineViewHolders == null) {
      return;
    }
    final InlineViewHolder holder = mInlineViewHolders.get(span.getInlineViewReuseKey());
    if (holder == null || holder.span != span) {
      return;
    }
    holder.active = false;
    holder.view.setVisibility(GONE);
  }

  private void releaseAllInlineViews() {
    if (mInlineViewHolders == null) {
      return;
    }
    final List<InlineViewHolder> holders = new ArrayList<>(mInlineViewHolders.values());
    mInlineViewHolders = null;
    removeAllViews();
    for (InlineViewHolder holder : holders) {
      holder.span.releaseInlineView(holder.view);
    }
  }

  private void deactivateInlineViews() {
    if (mInlineViewHolders == null) {
      return;
    }
    for (InlineViewHolder holder : mInlineViewHolders.values()) {
      holder.active = false;
    }
  }

  private void releaseInactiveInlineViews() {
    if (mInlineViewHolders == null) {
      return;
    }
    final Iterator<InlineViewHolder> iterator = mInlineViewHolders.values().iterator();
    while (iterator.hasNext()) {
      final InlineViewHolder holder = iterator.next();
      if (holder.active) {
        continue;
      }
      removeView(holder.view);
      holder.span.releaseInlineView(holder.view);
      iterator.remove();
    }
    if (mInlineViewHolders.isEmpty()) {
      mInlineViewHolders = null;
    } else {
      requestLayout();
    }
  }

  public void finalUnmount() {
    releaseAllInlineViews();
  }

  private static final class InlineViewHolder {
    private InlineViewSpan span;
    private final View view;
    private boolean active;

    private InlineViewHolder(InlineViewSpan span, View view) {
      this.span = span;
      this.view = view;
    }
  }

  private static boolean highlightOffsetsValid(
      CharSequence text, int highlightStart, int highlightEnd) {
    return highlightStart >= 0 && highlightEnd <= text.length() && highlightStart < highlightEnd;
  }

  // Text is drawn in onDraw, like TextView, so everything View.draw paints after the content
  // (foreground, overlay, focus highlight) lands over it.
  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    if (mLayout == null) {
      return;
    }

    // Always save: a span can leave the canvas changed (for example scaled), and the foreground and
    // overlay View.draw paints after this must not inherit that.
    final int saveCount = canvas.save();
    final android.text.TextPaint paint = mLayout.getPaint();
    // Set this view's own color, even without a dynamic one: a view sharing the layout may be
    // drawing around this one, for example from a span, and has left its own color on the paint.
    final int previousColor = paint.getColor();
    try {
      if (mClipToBounds) {
        // Like legacy Text, whose drawable bounds are the content box inside the padding.
        canvas.clipRect(
            getPaddingLeft(),
            getPaddingTop(),
            getWidth() - getPaddingRight(),
            getHeight() - getPaddingBottom());
      }
      canvas.translate(mLayoutTranslationX, mLayoutTranslationY);
      canvas.translate(getPaddingLeft(), getPaddingTop());

      final OnPrePostDrawSpan[] onPrePostDrawSpans = getOnPrePostDrawSpans();
      paint.setColor(getCurrentTextColor());
      if (onPrePostDrawSpans.length == 0) {
        drawLayout(canvas);
      } else {
        drawInternal(onPrePostDrawSpans, canvas);
      }
    } finally {
      paint.setColor(previousColor);
      canvas.restoreToCount(saveCount);
    }
  }

  private void drawInternal(final OnPrePostDrawSpan[] onOnPrePostDrawSpans, final Canvas canvas) {
    OnPrePostDrawSpan.Command currentDrawAction =
        new OnPrePostDrawSpan.Command() {
          @Override
          public void draw(Canvas canvas) {
            drawLayout(canvas);
          }
        };
    if (!(mText instanceof Spanned)) {
      return;
    }
    final Spanned text = (Spanned) mText;
    // OnPrePostDraw spans are retrieved in the same order in which they are applied (i.e. in order
    // of pre-order traversal of the composable span tree).  We want to have their onDraw calls
    // invoked in the same order.  In order to chain them in that order, we need to walk the spans
    // list backward.
    int length = onOnPrePostDrawSpans.length;
    for (int i = length - 1; i >= 0; i--) {
      OnPrePostDrawSpan onDraw = onOnPrePostDrawSpans[i];
      OnPrePostDrawSpan.Command previousAction = currentDrawAction;
      int spanStart = text.getSpanStart(onDraw);
      int spanEnd = text.getSpanEnd(onDraw);
      currentDrawAction =
          new OnPrePostDrawSpan.Command() {
            @Override
            public void draw(Canvas canvas) {
              onDraw.draw(canvas, mText, spanStart, spanEnd, mLayout, previousAction);
            }
          };
    }
    currentDrawAction.draw(canvas);
  }

  private void drawLayout(Canvas canvas) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      maybeDrawOutline(canvas);
    }

    // Determine which selection path and paint to use.
    // If the editor has an active interactive selection, use that;
    // otherwise fall back to the static highlight selection.
    Path selectionPath = null;
    Paint selectionPaint = null;
    if (mEditor != null && mEditor.hasSelection()) {
      selectionPath = mEditor.getSelectionHighlightPath();
      selectionPaint = mEditor.getSelectionHighlightPaint();
    }
    if (selectionPath == null) {
      selectionPath = getSelectionPath();
      selectionPaint = mHighlightPaint;
    }

    if (isMIUIDevice()) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          Api34Utils.draw(mLayout, canvas, selectionPath, selectionPaint);
        } else {
          // NULLSAFE_FIXME[Parameter Not Nullable]
          mLayout.draw(canvas, selectionPath, selectionPaint, 0);
        }
        // Draw succeeded — reset the failure flag if it was set (recovered).
        mMiuiDrawFailed = false;
      } catch (NullPointerException e) {
        // Workaround for a vendor bug on Xiaomi/MIUI devices where
        // miui.util.font.FontNameUtil.isNameOf() crashes with an NPE when
        // calling String.startsWith() on a null font name during Paint.set()
        // inside Layout.draw(). Since the bug is in vendor code we cannot fix,
        // we catch and suppress the NPE to prevent an app crash. The text may
        // not render for this frame but will recover on the next draw pass.
        if (mMiuiDrawFailed && !mMiuiNpeReported) {
          // NPE was caught in the previous draw pass and still not recovered.
          // Report once to avoid spamming error logs every frame.
          mMiuiNpeReported = true;
          ErrorReporter.report(
              LogLevel.ERROR,
              "RCTextView:MIUIDrawNPE",
              "MIUI vendor NPE in Layout.draw() not recovered after retry",
              e);
        }
        mMiuiDrawFailed = true;
      }
    } else {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Api34Utils.draw(mLayout, canvas, selectionPath, selectionPaint);
      } else {
        // NULLSAFE_FIXME[Parameter Not Nullable]
        mLayout.draw(canvas, selectionPath, selectionPaint, 0);
      }
    }

    // Draw selection handles if active
    if (mEditor != null) {
      mEditor.drawHandles(canvas);
    }
  }

  /**
   * All texts drawn on top of images and videos need contrast outlines and shadows to be more
   * visible against busy backgrounds. Standard Android shadows do not produce the separation of
   * intensity needed, so the RCTextView provides a special outline attribute that draws contrast
   * outlines usually combined with shadows. These outlines are drawn outside the contours to avoid
   * reducing the visible surface of character glyphs. However, since Android has no mode for
   * drawing outside strokes, they need to be drawn twice: the first pass draws strokes, and the
   * second pass draws inner filled shapes. This method performs the first outlining pass if needed.
   *
   * @param canvas - A canvas to draw on.
   */
  @RequiresApi(Build.VERSION_CODES.Q)
  private void maybeDrawOutline(Canvas canvas) {
    if (mOutlineWidth > 0f) {
      Paint p = mLayout.getPaint();
      int savedColor = p.getColor();
      Paint.Style savedStyle = p.getStyle();
      float savedStrokeWidth = p.getStrokeWidth();
      Paint.Join savedJoin = p.getStrokeJoin();
      p.setStrokeJoin(Paint.Join.ROUND);
      p.setColor(mOutlineColor != 0 ? mOutlineColor : p.getShadowLayerColor());
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(mOutlineWidth);
      mLayout.draw(canvas);
      p.setStrokeWidth(savedStrokeWidth);
      p.setStyle(savedStyle);
      p.setColor(savedColor);
      p.setStrokeJoin(savedJoin);
    }
  }

  private OnPrePostDrawSpan[] getOnPrePostDrawSpans() {
    if (!(mText instanceof Spanned)) {
      return new OnPrePostDrawSpan[0];
    }
    OnPrePostDrawSpan[] spans =
        ((Spanned) mText).getSpans(0, mText.length(), OnPrePostDrawSpan.class);
    return spans != null ? spans : new OnPrePostDrawSpan[0];
  }

  private MountableSpan[] getMountableSpans() {
    if (!(mText instanceof Spanned)) {
      return new MountableSpan[0];
    }
    MountableSpan[] spans = ((Spanned) mText).getSpans(0, mText.length(), MountableSpan.class);
    return spans != null ? spans : new MountableSpan[0];
  }

  public void mount(TextLayout textLayout) {
    final ColorStateList colorStateList = textLayout.textStyle.getTextColorStateList();
    mText = textLayout.processedText;
    mFullText = textLayout.fullText;
    mLayout = textLayout.layout;
    mLayoutTranslationX = textLayout.textLayoutTranslationX;
    mLayoutTranslationY = textLayout.textLayoutTranslationY;
    mClipToBounds = textLayout.textStyle.clipToBounds;
    mTextBelongsToHost = textLayout.textStyle.textBelongsToHost;
    mExposeClickableSpansToAccessibility = textLayout.textStyle.exposeClickableSpansToAccessibility;
    mHighlightColor = textLayout.textStyle.highlightColor;
    mKeyboardHighlightColor = textLayout.textStyle.keyboardHighlightColor;
    mHighlightCornerRadius = textLayout.textStyle.highlightCornerRadius;
    mSelectionHandleColor = textLayout.textStyle.selectionHandleColor;
    mIsExplicitlyTruncated = textLayout.isExplicitlyTruncated;
    mClickableSpanExpandedOffset = textLayout.textStyle.clickableSpanExpandedOffset;
    mTrackLongClickInLocalCoordinates = textLayout.textStyle.trackLongClickInLocalCoordinates;
    mClickSpanUnderRelease = textLayout.textStyle.clickSpanUnderRelease;
    // The color is this view's own state, resolved from the style the same way measurement does,
    // never read from or written to the paint: the paint can be shared, and another view may be
    // drawing with its own color on it right now, for example a view that mounts this one from a
    // span. draw() applies it.
    if (textLayout.textStyle.getTextColor() != 0) {
      mColorStateList = null;
      mLinkColor = textLayout.textStyle.getTextColor();
      mMountedTextColor = textLayout.textStyle.getTextColor();
    } else {
      mColorStateList = colorStateList;
      // Measurement uses BLACK when there is no color and no state list.
      mMountedTextColor = mColorStateList != null ? mColorStateList.getDefaultColor() : Color.BLACK;
      if (mColorStateList != null) {
        mLinkColor = mColorStateList.getDefaultColor();
      }
    }

    if (highlightOffsetsValid(
        mText,
        textLayout.textStyle.highlightStartOffset,
        textLayout.textStyle.highlightEndOffset)) {
      setSelection(
          textLayout.textStyle.highlightStartOffset,
          textLayout.textStyle.highlightEndOffset,
          mHighlightColor);
    } else {
      clearSelection();
    }
    if (textLayout.textStyle.outlineWidth > 0f) {
      mOutlineWidth = textLayout.textStyle.outlineWidth;
      mOutlineColor = textLayout.textStyle.outlineColor;
    }

    if (textLayout.imageSpans != null) {
      mImageSpans = textLayout.imageSpans;

      for (int i = 0, size = mImageSpans.length; i < size; i++) {
        Drawable drawable = mImageSpans[i].getDrawable();
        if (drawable != null) {
          drawable.setCallback(this);
          drawable.setVisible(true, false);
        }
      }
    }
    mClickableSpans = textLayout.clickableSpans;
    mShouldHandleTouch = mClickableSpans != null && mClickableSpans.length > 0;
    if (mKeyboardHighlightColor == 0) {
      mKeyboardHighlightColor = mHighlightColor;
    }
    mShouldHandleKeyEvents =
        mClickableSpans != null
            && mClickableSpans.length > 0
            && Color.alpha(mKeyboardHighlightColor) != 0;
    if (mShouldHandleKeyEvents) {
      // View needs to be focusable in order to receive key events
      // Capture focusable state to restore it on unmount
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        mWasFocusable = Api26Utils.getFocusable(this);
      } else {
        mWasFocusable = isFocusable() ? 1 : 0;
      }
      setFocusable(true);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setDefaultFocusHighlightEnabled(false);
        mDidDisableDefaultFocusHighlight = true;
      }
    }
    if (textLayout.textStyle.accessibilityLabel != null) {
      setContentDescription(textLayout.textStyle.accessibilityLabel);
    }

    if (mLongClickHandler == null && containsLongClickableSpan(mClickableSpans)) {
      mLongClickHandler = new Handler(Looper.getMainLooper());
    }

    // Initialize text selection editor if text is selectable
    mTextIsSelectable = textLayout.textStyle.isTextSelectable;
    if (mTextIsSelectable) {
      if (mEditor == null) {
        mEditor = new RCTextEditor(this);
      }
      // Make the text Spannable so Selection spans can be set
      final Spannable spannable;
      if (mText instanceof Spannable) {
        spannable = (Spannable) mText;
      } else {
        spannable = new SpannableString(mText);
        mText = spannable;
      }
      mEditor.setSpannable(spannable);
      // Save current state for proper restoration in unmount()
      mWasFocusableInTouchMode = isFocusableInTouchMode();
      mWasLongClickable = isLongClickable();
      // Focusable is needed for keyboard selection (Ctrl+A, Ctrl+C).
      // Only capture mWasFocusable if not already captured by the keyboard
      // highlight code path above, to avoid overwriting it.
      if (mWasFocusable == null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          mWasFocusable = Api26Utils.getFocusable(this);
        } else {
          mWasFocusable = isFocusable() ? 1 : 0;
        }
      }
      setFocusable(true);
      setFocusableInTouchMode(true);
      // Enable long-clickable for long-press selection
      setLongClickable(true);
      mShouldHandleTouch = true;
    } else {
      if (mEditor != null) {
        mEditor.onDetach();
        mEditor = null;
      }
    }

    final MountableSpan[] mountableSpans = getMountableSpans();
    deactivateInlineViews();
    for (MountableSpan mountableSpan : mountableSpans) {
      mountableSpan.onMount(this);
    }
    releaseInactiveInlineViews();
    if (mRCTextAccessibilityDelegate != null) {
      mRCTextAccessibilityDelegate.clearIneligibleAccessibilityFocus();
    }
    invalidate();
  }

  public void setTouchableSpanListener(@Nullable TouchableSpanListener touchableSpanListener) {
    mTouchableSpanListener = touchableSpanListener;
  }

  public void setClickableSpanAccessibilityProvider(
      @Nullable ClickableSpanAccessibilityProvider provider) {
    mClickableSpanAccessibilityProvider = provider;
  }

  public void setClickableSpanListener(@Nullable ClickableSpanListener clickableSpanListener) {
    mClickableSpanListener = clickableSpanListener;
  }

  public boolean isTextTruncated() {
    return mIsExplicitlyTruncated || TextMeasurementUtils.getEllipsizedLineNumber(mLayout) != -1;
  }

  /**
   * Draws the text in {@code color} instead of the style's color until cleared with {@code null}.
   * Applied only while drawing, so the layout's paint, which can be shared, is never left changed.
   */
  public void setDynamicTextColor(@Nullable Integer color) {
    mDynamicTextColor = color;
    invalidate();
  }

  @Nullable
  public Integer getDynamicTextColor() {
    return mDynamicTextColor;
  }

  /** The color the text is drawn in: the dynamic color if one is set, otherwise the style's. */
  public int getCurrentTextColor() {
    if (mDynamicTextColor != null) {
      return mDynamicTextColor;
    }
    if (mLayout == null) {
      return 0;
    }
    if (mColorStateList != null) {
      return mColorStateList.getColorForState(
          getDrawableState(), mColorStateList.getDefaultColor());
    }
    return mMountedTextColor;
  }

  public void unmount() {
    final MountableSpan[] mountableSpans = getMountableSpans();
    for (MountableSpan mountableSpan : mountableSpans) {
      mountableSpan.onUnmount(this);
    }
    // NULLSAFE_FIXME[Field Not Nullable]
    mText = null;
    mFullText = null;
    // NULLSAFE_FIXME[Field Not Nullable]
    mLayout = null;
    mLayoutTranslationX = 0;
    mLayoutTranslationY = 0;
    mClipToBounds = false;
    mTextBelongsToHost = false;
    mExposeClickableSpansToAccessibility = true;
    mHighlightColor = 0;
    mKeyboardHighlightColor = 0;
    mHighlightCornerRadius = 0;
    mSelectionHandleColor = 0;
    mColorStateList = null;
    mLinkColor = 0;
    if (mImageSpans != null) {
      for (int i = 0, size = mImageSpans.length; i < size; i++) {
        Drawable drawable = mImageSpans[i].getDrawable();
        if (drawable != null) {
          drawable.setCallback(null);
          drawable.setVisible(false, false);
        }
      }
      // NULLSAFE_FIXME[Field Not Nullable]
      mImageSpans = null;
    }
    // NULLSAFE_FIXME[Field Not Nullable]
    mClickableSpans = null;
    mShouldHandleTouch = false;
    mShouldHandleKeyEvents = false;
    mCurrentlyTouchedSpan = null;
    mBounds.setEmpty();
    mClickableSpanAreaRegion.setEmpty();
    mClickableSpanAreaPath.reset();
    resetLongClick();
    // Clean up text selection editor
    if (mEditor != null) {
      mEditor.onDetach();
      mEditor = null;
    }
    if (mTextIsSelectable) {
      // Restore focusableInTouchMode and longClickable that we set in mount()
      setFocusableInTouchMode(mWasFocusableInTouchMode);
      setLongClickable(mWasLongClickable);
    }
    mTextIsSelectable = false;
    // Restore original focusable state if it was overridden
    if (mWasFocusable != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Api26Utils.setFocusable(this, mWasFocusable);
        if (mDidDisableDefaultFocusHighlight) {
          setDefaultFocusHighlightEnabled(true);
          mDidDisableDefaultFocusHighlight = false;
        }
      } else {
        setFocusable(mWasFocusable == 1);
      }
      mWasFocusable = null;
    }
    setContentDescription("");

    if (mRCTextAccessibilityDelegate != null) {
      mRCTextAccessibilityDelegate.invalidateRoot();
    }
  }

  // Note: if renaming this method, we have use reflection to access this in
  // EndToEndDumpsysHelper.java
  @DoNotStrip
  public CharSequence getText() {
    return mText;
  }

  /** The text before any custom-ellipsis truncation; equals {@link #getText()} otherwise. */
  /** See {@link TextStyle#textBelongsToHost}. */
  public boolean textBelongsToHost() {
    return mTextBelongsToHost;
  }

  public CharSequence getFullText() {
    return mFullText != null ? mFullText : mText;
  }

  private @Nullable Path getSelectionPath() {
    if (mSelectionStart == mSelectionEnd) {
      return null;
    }

    if (Color.alpha(mSelectionColor) == 0) {
      return null;
    }

    if (mSelectionPathNeedsUpdate) {
      if (mSelectionPath == null) {
        mSelectionPath = new Path();
      }

      mLayout.getSelectionPath(mSelectionStart, mSelectionEnd, mSelectionPath);
      mSelectionPathNeedsUpdate = false;
    }

    return mSelectionPath;
  }

  private void setSelection(ClickableSpan span, boolean isTouch) {
    final Spanned text = (Spanned) mText;
    setSelection(
        text.getSpanStart(span),
        text.getSpanEnd(span),
        isTouch ? mHighlightColor : mKeyboardHighlightColor);
  }

  /** Updates selection to [selectionStart, selectionEnd] range. */
  private void setSelection(int selectionStart, int selectionEnd, int color) {
    if (Color.alpha(color) == 0
        || (mSelectionStart == selectionStart && mSelectionEnd == selectionEnd)) {
      return;
    }

    mSelectionStart = selectionStart;
    mSelectionEnd = selectionEnd;
    mSelectionColor = color;

    if (mHighlightPaint == null) {
      mHighlightPaint = new Paint();
    }
    mHighlightPaint.setColor(mSelectionColor);

    if (mHighlightCornerRadius != 0) {
      mHighlightPaint.setPathEffect(new CornerPathEffect(mHighlightCornerRadius));
    } else {
      mHighlightPaint.setPathEffect(null);
    }

    mSelectionPathNeedsUpdate = true;
    invalidate();
  }

  private void clearSelection() {
    setSelection(0, 0, mSelectionColor);
  }

  private CharSequence getTextForAccessibility() {
    if (mText == null || mText.length() < SAFE_PARCELABLE_SIZE) {
      return mText;
    }

    if (Character.isHighSurrogate(mText.charAt(SAFE_PARCELABLE_SIZE - 1))
        && Character.isLowSurrogate(mText.charAt(SAFE_PARCELABLE_SIZE))) {
      return mText.subSequence(0, SAFE_PARCELABLE_SIZE - 1);
    }
    return mText.subSequence(0, SAFE_PARCELABLE_SIZE);
  }

  /**
   * Handles touch events for clickable spans and text selection.
   *
   * <p>The text selection handling is ported from AOSP {@code TextView.onTouchEvent()}
   * (TextView.java:13178-13282). The editor receives touch events first; if a drag is active or we
   * need to discard the up event, we consume it. Otherwise we fall through to existing
   * ClickableSpan handling.
   */
  @Override
  public boolean onTouchEvent(MotionEvent event) {
    // If text is selectable, dispatch to the editor first.
    if (mTextIsSelectable && mEditor != null) {
      mEditor.onTouchEvent(event);
      final int action = event.getActionMasked();
      // If the editor is actively dragging selection, consume the event
      if (mEditor.isSelectionDragActive()) {
        super.onTouchEvent(event);
        return true;
      }
      // If the editor flagged this action-up to be discarded (e.g., after drag),
      // consume it and don't let clickable spans fire
      if (action == ACTION_UP && mEditor.isDiscardNextActionUp()) {
        mEditor.clearDiscardNextActionUp();
        super.onTouchEvent(event);
        return true;
      }
    }

    if (!mShouldHandleTouch) {
      // Even if no clickable spans, still call onTouchUpEvent for selectable text
      // so tap-to-deselect works (AOSP: "touchIsFinished && textIsSelectable" path)
      if (mTextIsSelectable && mEditor != null) {
        final int action = event.getActionMasked();
        boolean touchIsFinished = action == ACTION_UP && !mLongClickActivated && isFocused();
        if (touchIsFinished) {
          mEditor.onTouchUpEvent(event);
        }
        super.onTouchEvent(event);
        return true;
      }
      return super.onTouchEvent(event);
    }

    final int action = event.getActionMasked();

    if (action == ACTION_MOVE && !mLongClickActivated && mLongClickRunnable != null) {
      trackLongClickBoundaryOnMove(event);
    }

    final boolean clickActivationAllowed = !mLongClickActivated;

    ClickableSpan currentSpan = mCurrentlyTouchedSpan;
    if (action == ACTION_UP) {
      if (mClickSpanUnderRelease && currentSpan != null) {
        currentSpan = getClickableSpanUnderRelease(event);
      }
      resetLongClick();
      clearSelection();
      if (clickActivationAllowed && currentSpan != null) {
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        if (mClickableSpanListener == null || !mClickableSpanListener.onClick(currentSpan, this)) {
          currentSpan.onClick(this);
        }
      }
      mCurrentlyTouchedSpan = null;
    } else if (action == ACTION_DOWN) {
      final int x = (int) event.getX();
      final int y = (int) event.getY();
      mCurrentlyTouchedSpan = getClickableSpanInCoords(x, y);
      if (mCurrentlyTouchedSpan == null) {
        // No clickable span found. For selectable text, handle tap-to-deselect on UP.
        if (mTextIsSelectable && mEditor != null) {
          // Fall through to bottom of method
        } else {
          return super.onTouchEvent(event);
        }
      } else {
        // Skip LongClickableSpan registration when text is selectable
        // (long press should trigger word selection, not span long-click)
        if (!mTextIsSelectable && mCurrentlyTouchedSpan instanceof LongClickableSpan) {
          registerForLongClick((LongClickableSpan) mCurrentlyTouchedSpan);
        }
        setSelection(mCurrentlyTouchedSpan, true);
        currentSpan = mCurrentlyTouchedSpan;
      }
    } else if (action == ACTION_CANCEL) {
      clearSelection();
      resetLongClick();
      mCurrentlyTouchedSpan = null;
    }
    if (mTouchableSpanListener != null) {
      mTouchableSpanListener.onTouch(currentSpan, event, this);
    }

    // After existing clickable span handling, for selectable text:
    // on touch-finished, call onTouchUpEvent for tap-to-deselect / cursor placement
    // Ported from AOSP: "if (touchIsFinished && mTextIsSelectable)"
    if (mTextIsSelectable && mEditor != null) {
      boolean touchIsFinished = action == ACTION_UP && !mLongClickActivated && isFocused();
      if (touchIsFinished) {
        mEditor.onTouchUpEvent(event);
      }
    }

    if (currentSpan == null) {
      return super.onTouchEvent(event);
    }

    return true;
  }

  /**
   * Ported from AOSP {@code TextView.performLongClick()} (TextView.java:15275-15310). The AOSP
   * pattern calls super.performLongClick() first to run OnLongClickListener, then passes the result
   * to the editor which selects a word if not already handled.
   */
  @Override
  public boolean performLongClick() {
    if (mTextIsSelectable && mEditor != null) {
      boolean handled = super.performLongClick();
      handled = mEditor.performLongClick(handled);
      if (handled) {
        mEditor.setDiscardNextActionUp(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
      }
      return handled;
    }
    return super.performLongClick();
  }

  @Override
  public boolean onKeyShortcut(int keyCode, KeyEvent event) {
    if (mTextIsSelectable && mEditor != null) {
      if (event.hasModifiers(KeyEvent.META_CTRL_ON)) {
        switch (keyCode) {
          case KeyEvent.KEYCODE_A:
            mEditor.selectAll();
            return true;
          case KeyEvent.KEYCODE_C:
            mEditor.copyToClipboard();
            return true;
        }
      }
    }
    return super.onKeyShortcut(keyCode, event);
  }

  /**
   * Handles keyboard arrow/home/end keys for text selection movement.
   *
   * <p>Ported from AOSP {@code BaseMovementMethod.handleMovementKey()}
   * (BaseMovementMethod.java:161-249) combined with {@code ArrowKeyMovementMethod}
   * (ArrowKeyMovementMethod.java:52-220). The isSelecting check uses
   * MetaKeyKeyListener.META_SHIFT_ON matching ArrowKeyMovementMethod.isSelecting()
   * (ArrowKeyMovementMethod.java:34-37).
   */
  private boolean handleSelectionMovementKey(
      Spannable buffer, Layout layout, int keyCode, KeyEvent event) {
    // Check if Shift is held for extending selection
    // Ported from ArrowKeyMovementMethod.isSelecting()
    final boolean selecting =
        (MetaKeyKeyListener.getMetaState(buffer, MetaKeyKeyListener.META_SHIFT_ON) == 1)
            || event.isShiftPressed();

    // Compute movement meta state (without shift, for modifier checks)
    // Ported from BaseMovementMethod.getMovementMetaState()
    final int metaState =
        KeyEvent.normalizeMetaState(event.getMetaState()) & ~KeyEvent.META_SHIFT_MASK;

    switch (keyCode) {
      case KeyEvent.KEYCODE_DPAD_LEFT:
        if (KeyEvent.metaStateHasNoModifiers(metaState)) {
          return selecting
              ? Selection.extendLeft(buffer, layout)
              : Selection.moveLeft(buffer, layout);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_CTRL_ON)) {
          // Ctrl+Left: word-level left
          return moveByWord(buffer, layout, /* forward= */ false, selecting);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_ALT_ON)) {
          return selecting
              ? Selection.extendToLeftEdge(buffer, layout)
              : Selection.moveToLeftEdge(buffer, layout);
        }
        break;

      case KeyEvent.KEYCODE_DPAD_RIGHT:
        if (KeyEvent.metaStateHasNoModifiers(metaState)) {
          return selecting
              ? Selection.extendRight(buffer, layout)
              : Selection.moveRight(buffer, layout);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_CTRL_ON)) {
          // Ctrl+Right: word-level right
          return moveByWord(buffer, layout, /* forward= */ true, selecting);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_ALT_ON)) {
          return selecting
              ? Selection.extendToRightEdge(buffer, layout)
              : Selection.moveToRightEdge(buffer, layout);
        }
        break;

      case KeyEvent.KEYCODE_DPAD_UP:
        if (KeyEvent.metaStateHasNoModifiers(metaState)) {
          return selecting ? Selection.extendUp(buffer, layout) : Selection.moveUp(buffer, layout);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_ALT_ON)) {
          // Alt+Up: top of document
          return selecting ? extendToDocumentStart(buffer) : moveToDocumentStart(buffer);
        }
        break;

      case KeyEvent.KEYCODE_DPAD_DOWN:
        if (KeyEvent.metaStateHasNoModifiers(metaState)) {
          return selecting
              ? Selection.extendDown(buffer, layout)
              : Selection.moveDown(buffer, layout);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_ALT_ON)) {
          // Alt+Down: bottom of document
          return selecting ? extendToDocumentEnd(buffer) : moveToDocumentEnd(buffer);
        }
        break;

      case KeyEvent.KEYCODE_MOVE_HOME:
        if (KeyEvent.metaStateHasNoModifiers(metaState)) {
          // Home: line start
          return selecting
              ? Selection.extendToLeftEdge(buffer, layout)
              : Selection.moveToLeftEdge(buffer, layout);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_CTRL_ON)) {
          // Ctrl+Home: document start
          return selecting ? extendToDocumentStart(buffer) : moveToDocumentStart(buffer);
        }
        break;

      case KeyEvent.KEYCODE_MOVE_END:
        if (KeyEvent.metaStateHasNoModifiers(metaState)) {
          // End: line end
          return selecting
              ? Selection.extendToRightEdge(buffer, layout)
              : Selection.moveToRightEdge(buffer, layout);
        } else if (KeyEvent.metaStateHasModifiers(metaState, KeyEvent.META_CTRL_ON)) {
          // Ctrl+End: document end
          return selecting ? extendToDocumentEnd(buffer) : moveToDocumentEnd(buffer);
        }
        break;

      case KeyEvent.KEYCODE_BACK:
      case KeyEvent.KEYCODE_ESCAPE:
        // Dismiss selection on Back/Escape
        if (mEditor != null && mEditor.hasSelection()) {
          mEditor.stopTextActionMode();
          // Collapse selection to end
          final int selEnd = Selection.getSelectionEnd(buffer);
          Selection.setSelection(buffer, selEnd);
          return true;
        }
        break;
    }
    return false;
  }

  /**
   * Moves or extends selection by one word in the given direction.
   *
   * <p>Ported from AOSP {@code ArrowKeyMovementMethod.leftWord()}
   * (ArrowKeyMovementMethod.java:223-228) and {@code rightWord()}
   * (ArrowKeyMovementMethod.java:232-237).
   */
  private boolean moveByWord(Spannable buffer, Layout layout, boolean forward, boolean selecting) {
    final int selectionEnd = Selection.getSelectionEnd(buffer);
    if (mEditor == null) {
      return false;
    }
    // Use word iterator from the editor
    final int wordBoundary =
        forward ? mEditor.getWordEnd(selectionEnd) : mEditor.getWordStart(selectionEnd);
    if (wordBoundary == selectionEnd) {
      return false;
    }
    if (selecting) {
      Selection.extendSelection(buffer, wordBoundary);
    } else {
      Selection.setSelection(buffer, wordBoundary);
    }
    return true;
  }

  /**
   * Ported from AOSP {@code ArrowKeyMovementMethod.top()} (ArrowKeyMovementMethod.java:176-183).
   */
  private static boolean moveToDocumentStart(Spannable buffer) {
    Selection.setSelection(buffer, 0);
    return true;
  }

  /**
   * Ported from AOSP {@code ArrowKeyMovementMethod.top()} selecting variant
   * (ArrowKeyMovementMethod.java:177-179).
   */
  private static boolean extendToDocumentStart(Spannable buffer) {
    Selection.extendSelection(buffer, 0);
    return true;
  }

  /**
   * Ported from AOSP {@code ArrowKeyMovementMethod.bottom()} (ArrowKeyMovementMethod.java:186-193).
   */
  private static boolean moveToDocumentEnd(Spannable buffer) {
    Selection.setSelection(buffer, buffer.length());
    return true;
  }

  /**
   * Ported from AOSP {@code ArrowKeyMovementMethod.bottom()} selecting variant
   * (ArrowKeyMovementMethod.java:187-189).
   */
  private static boolean extendToDocumentEnd(Spannable buffer) {
    Selection.extendSelection(buffer, buffer.length());
    return true;
  }

  public float getLayoutTranslationX() {
    return mLayoutTranslationX;
  }

  public float getLayoutTranslationY() {
    return mLayoutTranslationY;
  }

  @Nullable
  private ClickableSpan getClickableSpanUnderRelease(MotionEvent event) {
    final int x = (int) event.getX();
    final int y = (int) event.getY();
    if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
      return null;
    }
    return getClickableSpanInCoords(x, y);
  }

  /**
   * Get the clickable span that is at the exact coordinates
   *
   * @param x x-position of the click
   * @param y y-position of the click
   * @return a clickable span that's located where the click occurred, or: {@code null} if no
   *     clickable span was located there
   */
  @Nullable
  private ClickableSpan getClickableSpanInCoords(int x, int y) {
    final int offset = getTextOffsetAt(x, y);
    if (!(mText instanceof Spanned) || offset < 0) {
      return null;
    }

    final ClickableSpan[] clickableSpans =
        ((Spanned) mText).getSpans(offset, offset, ClickableSpan.class);

    if (clickableSpans != null && clickableSpans.length > 0) {
      return clickableSpans[0];
    }

    if (mClickableSpanExpandedOffset > 0) {
      return getClickableSpanInProximityToClick(x, y);
    }
    return null;
  }

  private int getTextOffsetAt(int x, int y) {
    // Adjust for any canvas translations
    y = (int) (y - mLayoutTranslationY);
    x = (int) (x - mLayoutTranslationX);
    final int line = mLayout.getLineForVertical(y);

    final float left;
    final float right;

    if (mLayout.getAlignment() == Layout.Alignment.ALIGN_CENTER) {
      /**
       * {@link Layout#getLineLeft} and {@link Layout#getLineRight} properly account for paragraph
       * margins on centered text.
       */
      left = mLayout.getLineLeft(line);
      right = mLayout.getLineRight(line);
    } else {
      /**
       * {@link Layout#getLineLeft} and {@link Layout#getLineRight} do NOT properly account for
       * paragraph margins on non-centered text, so we need an alternative.
       *
       * <p>To determine the actual bounds of the line, we need the line's direction, leading
       * margin, and extent, but only the first is available directly. The margin is given by either
       * {@link Layout#getParagraphLeft} or {@link Layout#getParagraphRight} depending on line
       * direction, and {@link Layout#getLineMax} gives the extent *plus* the leading margin, so we
       * can figure out the rest from there.
       */
      final boolean rtl = mLayout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
      left = rtl ? mLayout.getWidth() - mLayout.getLineMax(line) : mLayout.getParagraphLeft(line);
      right = rtl ? mLayout.getParagraphRight(line) : mLayout.getLineMax(line);
    }

    if (x < left || x > right) {
      return -1;
    }

    try {
      return mLayout.getOffsetForHorizontal(line, x);
    } catch (ArrayIndexOutOfBoundsException e) {
      // This happens for bidi text on Android 7-8.
      // See
      // https://android.googlesource.com/platform/frameworks/base/+/821e9bd5cc2be4b3210cb0226e40ba0f42b51aed
      return -1;
    }
  }

  /**
   * Get the clickable span that's close to where the view was clicked.
   *
   * @param x x-position of the click
   * @param y y-position of the click
   * @return a clickable span that's close the click position, or: {@code null} if no clickable span
   *     was close to the click, or if a link was directly clicked or if more than one clickable
   *     span was in proximity to the click.
   */
  @Nullable
  private ClickableSpan getClickableSpanInProximityToClick(float x, float y) {
    final Region touchAreaRegion = new Region();
    final Region clipBoundsRegion = new Region();

    if (mTouchAreaPath == null) {
      mTouchAreaPath = new Path();
    }

    clipBoundsRegion.set(
        0, 0, TextMeasurementUtils.getWidth(mLayout), TextMeasurementUtils.getHeight(mLayout));
    mTouchAreaPath.reset();
    mTouchAreaPath.addCircle(x, y, mClickableSpanExpandedOffset, Path.Direction.CW);
    touchAreaRegion.setPath(mTouchAreaPath, clipBoundsRegion);

    ClickableSpan result = null;
    for (ClickableSpan span : mClickableSpans) {
      if (!isClickCloseToSpan(span, (Spanned) mText, mLayout, touchAreaRegion, clipBoundsRegion)) {
        continue;
      }

      if (result != null) {
        // This is the second span that's close to the tap, so we don't have a definitive answer
        return null;
      }

      result = span;
    }

    return result;
  }

  private boolean isClickCloseToSpan(
      ClickableSpan span,
      Spanned buffer,
      Layout layout,
      Region touchAreaRegion,
      Region clipBoundsRegion) {
    mClickableSpanAreaRegion.setEmpty();
    mClickableSpanAreaPath.reset();

    layout.getSelectionPath(
        buffer.getSpanStart(span), buffer.getSpanEnd(span), mClickableSpanAreaPath);
    mClickableSpanAreaRegion.setPath(mClickableSpanAreaPath, clipBoundsRegion);

    return mClickableSpanAreaRegion.op(touchAreaRegion, Region.Op.INTERSECT);
  }

  private void resetLongClick() {
    if (mLongClickHandler != null && mLongClickRunnable != null) {
      mLongClickHandler.removeCallbacks(mLongClickRunnable);
      mLongClickRunnable = null;
    }
    mLongClickActivated = false;
  }

  private void registerForLongClick(@Nullable LongClickableSpan longClickableSpan) {
    if (longClickableSpan == null || mLongClickHandler == null) {
      return;
    }
    mLongClickRunnable = new LongClickRunnable(longClickableSpan, this);
    mLongClickHandler.postDelayed(mLongClickRunnable, ViewConfiguration.getLongPressTimeout());
  }

  private void trackLongClickBoundaryOnMove(MotionEvent event) {
    if (mTrackLongClickInLocalCoordinates) {
      final int x = (int) event.getX();
      final int y = (int) event.getY();
      if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
        resetLongClick();
        return;
      }
      if (mLongClickRunnable != null
          && mLongClickRunnable.longClickableSpan != getClickableSpanInCoords(x, y)) {
        resetLongClick();
      }
      return;
    }
    mBounds.setEmpty();
    this.getHitRect(mBounds);
    if (!isWithinBounds(mBounds, event)) {
      resetLongClick();
      return;
    }

    final ClickableSpan clickableSpan =
        getClickableSpanInCoords(
            (int) event.getX() - mBounds.left, (int) event.getY() - mBounds.top);
    if (mLongClickRunnable != null && mLongClickRunnable.longClickableSpan != clickableSpan) {
      // we are out of span area, reset longClick
      resetLongClick();
    }
  }

  private static boolean isWithinBounds(Rect bounds, MotionEvent event) {
    return bounds.contains((int) event.getX(), (int) event.getY());
  }

  @FalseOnNull
  private static boolean containsLongClickableSpan(@Nullable ClickableSpan[] clickableSpans) {
    if (clickableSpans == null) {
      return false;
    }

    for (ClickableSpan span : clickableSpans) {
      if (span instanceof LongClickableSpan) {
        return true;
      }
    }

    return false;
  }

  private void setDefaultAccessibilityDelegate() {
    if (mRCTextAccessibilityDelegate != null) {
      mIsSettingDefaultAccessibilityDelegate = true;
      ViewCompat.setAccessibilityDelegate(this, mRCTextAccessibilityDelegate);
      mIsSettingDefaultAccessibilityDelegate = false;
    }
  }

  @Override
  public void setAccessibilityDelegate(@Nullable View.AccessibilityDelegate delegate) {
    super.setAccessibilityDelegate(delegate);
    if (mRCTextAccessibilityDelegate != null && !mIsSettingDefaultAccessibilityDelegate) {
      // We need to do this like this to get the AccessibilityDelegateCompat with the ViewCompat
      // helper since the compatibility class/methods are protected.
      final AccessibilityDelegateCompat accessibilityDelegateCompat =
          ViewCompat.getAccessibilityDelegate(this);
      if (accessibilityDelegateCompat != mRCTextAccessibilityDelegate) {
        mRCTextAccessibilityDelegate.setWrappedAccessibilityDelegate(accessibilityDelegateCompat);
        setDefaultAccessibilityDelegate();
      }
    }
  }

  /** Ported from AOSP {@code TextView.onDetachedFromWindow()} — cleans up editor state. */
  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (mEditor != null) {
      mEditor.onDetachedFromWindow();
    }
  }

  /** Ported from AOSP {@code TextView.onWindowFocusChanged()} (TextView.java:13097-13100). */
  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    super.onWindowFocusChanged(hasWindowFocus);
    if (mEditor != null && mTextIsSelectable) {
      mEditor.onWindowFocusChanged(hasWindowFocus);
    }
  }

  @Override
  protected void drawableStateChanged() {
    super.drawableStateChanged();
    // draw() resolves the state list's color for the current state, so a redraw is all it needs.
    if (mColorStateList != null && mColorStateList.isStateful() && mLayout != null) {
      invalidate();
    }
  }

  @Override
  public boolean dispatchHoverEvent(MotionEvent event) {
    return (mRCTextAccessibilityDelegate != null
            && mRCTextAccessibilityDelegate.dispatchHoverEvent(event))
        || super.dispatchHoverEvent(event);
  }

  @Override
  public final void onFocusChanged(
      boolean gainFocus, int direction, @Nullable Rect previouslyFocusedRect) {
    if (mShouldHandleKeyEvents && !gainFocus) {
      final int selectedSpanIndex = getSelectedSpanIndex();
      if (selectedSpanIndex != -1) {
        final ClickableSpan selectedSpan = mClickableSpans[selectedSpanIndex];
        if (selectedSpan instanceof RCAccessibleClickableSpan) {
          ((RCAccessibleClickableSpan) selectedSpan).setKeyboardSelected(false);
        }
        clearSelection();
      }
    }
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    if (mRCTextAccessibilityDelegate != null
        && mClickableSpans != null
        && mClickableSpans.length > 0) {
      mRCTextAccessibilityDelegate.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    return (mRCTextAccessibilityDelegate != null
            && mClickableSpans != null
            && mClickableSpans.length > 0
            // ExploreByTouchHelper does the same check internally for all other methods, expect
            // dispatchKeyEvent, this seems like a bug. Let's do the same check ourselves, otherwise
            // ExploreByTouchHelper would consume keyDown events even when the screen reader is
            // disabled.
            && isScreenReaderEnabled()
            && mRCTextAccessibilityDelegate.dispatchKeyEvent(event))
        || super.dispatchKeyEvent(event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (mShouldHandleKeyEvents
        && mSelectionStart == 0
        && mSelectionEnd == 0
        && (isDirectionKey(keyCode) || keyCode == KeyEvent.KEYCODE_TAB)) {
      // View just received focus due to keyboard navigation. Nothing is currently selected,
      // let's select first span according to the navigation direction.
      ClickableSpan targetSpan = null;
      if (isDirectionKey(keyCode) && event.hasNoModifiers()) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
          targetSpan = mClickableSpans[0];
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
          targetSpan = mClickableSpans[mClickableSpans.length - 1];
        }
      }

      if (keyCode == KeyEvent.KEYCODE_TAB) {
        if (event.hasNoModifiers()) {
          targetSpan = mClickableSpans[0];
        } else if (event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
          targetSpan = mClickableSpans[mClickableSpans.length - 1];
        }
      }

      if (targetSpan != null) {
        if (targetSpan instanceof RCAccessibleClickableSpan) {
          ((RCAccessibleClickableSpan) targetSpan).setKeyboardSelected(true);
        }
        setSelection(targetSpan, false);
        return true;
      }
    }

    return super.onKeyUp(keyCode, event);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    // Text selection keyboard movement (Ported from AOSP BaseMovementMethod/ArrowKeyMovementMethod)
    if (mTextIsSelectable && mEditor != null) {
      final Spannable spannable = mEditor.getSpannable();
      final Layout layout = getLayout();
      if (spannable != null && layout != null) {
        boolean handled = handleSelectionMovementKey(spannable, layout, keyCode, event);
        if (handled) {
          return true;
        }
      }
    }

    // Clickable span keyboard navigation (existing logic)
    final boolean isTabNavigation =
        keyCode == KeyEvent.KEYCODE_TAB
            && (event.hasNoModifiers() || event.hasModifiers(KeyEvent.META_SHIFT_ON));
    if (mShouldHandleKeyEvents
        && (isTabNavigation
            || ((isDirectionKey(keyCode) || isConfirmKey(keyCode)) && event.hasNoModifiers()))) {
      final int selectedSpanIndex = getSelectedSpanIndex();
      if (selectedSpanIndex == -1) {
        return super.onKeyDown(keyCode, event);
      }
      final ClickableSpan selectedSpan = mClickableSpans[selectedSpanIndex];

      if (isDirectionKey(keyCode) || isTabNavigation) {
        final int direction;
        if (isTabNavigation) {
          direction = event.hasModifiers(KeyEvent.META_SHIFT_ON) ? -1 : 1;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
          direction = 1;
        } else {
          // keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP
          direction = -1;
        }
        final int repeatCount = 1 + event.getRepeatCount();
        final int targetIndex = selectedSpanIndex + direction * repeatCount;
        if (targetIndex >= 0 && targetIndex < mClickableSpans.length) {
          final ClickableSpan targetSpan = mClickableSpans[targetIndex];
          if (selectedSpan instanceof RCAccessibleClickableSpan) {
            ((RCAccessibleClickableSpan) selectedSpan).setKeyboardSelected(false);
          }
          if (targetSpan instanceof RCAccessibleClickableSpan) {
            ((RCAccessibleClickableSpan) targetSpan).setKeyboardSelected(true);
          }
          setSelection(targetSpan, false);
          return true;
        }
      }

      if (isConfirmKey(keyCode) && event.getRepeatCount() == 0) {
        if (selectedSpan instanceof RCAccessibleClickableSpan) {
          ((RCAccessibleClickableSpan) selectedSpan).setKeyboardSelected(false);
        }
        clearSelection();
        selectedSpan.onClick(this);
        return true;
      }
    }

    return super.onKeyDown(keyCode, event);
  }

  /** Null until the view is mounted, and null again after unmount. */
  @Nullable
  public Layout getLayout() {
    return mLayout;
  }

  /**
   * Returns the TextPaint used by the Layout. This is needed by the editor for word boundary
   * detection and magnifier font metrics.
   */
  @Nullable
  public android.text.TextPaint getPaint() {
    return mLayout != null ? mLayout.getPaint() : null;
  }

  /**
   * Returns the total left padding including layout translation. Ported from AOSP {@code
   * TextView.getTotalPaddingLeft()} (TextView.java:3101-3103). Since RCTextView doesn't support
   * compound drawables, this uses layout translation instead.
   */
  public int getTotalPaddingLeft() {
    return getPaddingLeft() + (int) mLayoutTranslationX;
  }

  /**
   * Returns the total right padding. Ported from AOSP {@code TextView.getTotalPaddingRight()}.
   * Since RCTextView doesn't support compound drawables, this is equivalent to getPaddingRight().
   */
  public int getTotalPaddingRight() {
    return getPaddingRight();
  }

  /**
   * Returns the total top padding including layout translation. Ported from AOSP {@code
   * TextView.getTotalPaddingTop()} (TextView.java:3134-3136). Since RCTextView doesn't support
   * compound drawables, this uses layout translation instead.
   */
  public int getTotalPaddingTop() {
    return getPaddingTop() + (int) mLayoutTranslationY;
  }

  /**
   * Returns the total bottom padding. Ported from AOSP {@code TextView.getTotalPaddingBottom()}.
   * Since RCTextView doesn't support compound drawables, this is equivalent to getPaddingBottom().
   */
  public int getTotalPaddingBottom() {
    return getPaddingBottom();
  }

  /** Returns the line height of the default font in the layout's paint. */
  public float getLineHeight() {
    if (mLayout == null) {
      return 0;
    }
    final android.text.TextPaint paint = mLayout.getPaint();
    // NULLSAFE_FIXME[Parameter Not Nullable]
    return paint.getFontMetrics(null);
  }

  /** Returns the locale for text services (word iteration, text classification). */
  public Locale getTextServicesLocale() {
    return Locale.getDefault();
  }

  /**
   * Returns the highlight color used for interactive text selection. Falls back to the theme's
   * {@code textColorHighlight} attribute if no explicit color was set, matching AOSP {@code
   * TextView.getHighlightColor()}.
   */
  public int getHighlightColor() {
    if (mHighlightColor != 0) {
      return mHighlightColor;
    }
    // Resolve from theme the same way AOSP TextView does (via TypedArray.getColor
    // which properly handles color resources, theme references, and color state lists).
    // Falls back to AOSP's default highlight color (0x6633B5E5) if not defined.
    TypedArray ta =
        getContext().obtainStyledAttributes(new int[] {android.R.attr.textColorHighlight});
    int color = ta.getColor(0, 0x6633B5E5);
    ta.recycle();
    return color;
  }

  /**
   * Returns the color used to tint selection handle drawables, or TRANSPARENT for theme default.
   */
  public int getSelectionHandleColor() {
    return mSelectionHandleColor;
  }

  /**
   * Returns true if the given position (in this view's local coordinates) is visible within the
   * parent hierarchy. Walks up the view tree, checking bounds, scroll offsets, and matrix
   * transforms at each ancestor.
   *
   * <p>Ported from AOSP {@code TextView.isPositionVisible()} (TextView.java:14869-14907). This
   * method is {@code @hide} in AOSP and not available as a public API. {@code
   * View.getLocalVisibleRect()} is a public alternative but operates on integer {@code Rect}
   * coordinates with different boundary semantics, so we copy the AOSP implementation verbatim to
   * match {@code Editor.HandleView.shouldShow()} behavior exactly.
   */
  public boolean isPositionVisible(float positionX, float positionY) {
    // Ported from AOSP TextView.isPositionVisible() (TextView.java:14869-14907).
    // Uses a static array with synchronized access to avoid per-call allocation,
    // matching AOSP's TEMP_POSITION pattern.
    synchronized (TEMP_POSITION) {
      final float[] position = TEMP_POSITION;
      position[0] = positionX;
      position[1] = positionY;
      View view = this;

      while (view != null) {
        if (view != this) {
          // Local scroll is already taken into account in positionX/Y
          position[0] -= view.getScrollX();
          position[1] -= view.getScrollY();
        }

        if (position[0] < 0
            || position[1] < 0
            || position[0] > view.getWidth()
            || position[1] > view.getHeight()) {
          return false;
        }

        if (!view.getMatrix().isIdentity()) {
          view.getMatrix().mapPoints(position);
        }

        position[0] += view.getLeft();
        position[1] += view.getTop();

        final ViewParent parent = view.getParent();
        if (parent instanceof View) {
          view = (View) parent;
        } else {
          // We've reached the ViewRoot, stop iterating
          view = null;
        }
      }
    }

    // We've been able to walk up the view hierarchy and the position was never clipped
    return true;
  }

  private static boolean isDirectionKey(int keyCode) {
    return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        || keyCode == KeyEvent.KEYCODE_DPAD_UP
        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
  }

  private static boolean isConfirmKey(int keyCode) {
    return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        || keyCode == KeyEvent.KEYCODE_ENTER
        || keyCode == KeyEvent.KEYCODE_SPACE
        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
  }

  private int getSelectedSpanIndex() {
    if (mClickableSpans == null
        || mClickableSpans.length == 0
        || (mSelectionStart == 0 && mSelectionEnd == 0)
        || !(mText instanceof Spanned)) {
      return -1;
    }
    final Spanned spanned = (Spanned) mText;
    for (int i = 0; i < mClickableSpans.length; i++) {
      final ClickableSpan span = mClickableSpans[i];
      final int spanStart = spanned.getSpanStart(span);
      final int spanEnd = spanned.getSpanEnd(span);
      if (spanStart == mSelectionStart && spanEnd == mSelectionEnd) {
        return i;
      }
    }
    return -1;
  }

  private boolean isScreenReaderEnabled() {
    // this system boolean is used by litho and internal tooling to "force" the accessibility state
    // copypaste of AccessibilityEnabledUtil, can't use this dependency directly because of WA
    if (Boolean.getBoolean("is_accessibility_enabled")) {
      return true;
    }

    return mAccessibilityManager != null
        && mAccessibilityManager.isEnabled()
        && mAccessibilityManager.isTouchExplorationEnabled();
  }

  private class RCTextAccessibilityDelegate extends ExploreByTouchHelper {

    @Nullable private AccessibilityDelegateCompat mWrappedAccessibilityDelegate;

    public RCTextAccessibilityDelegate() {
      super(RCTextView.this);

      RCTextView.this.setFocusable(false);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setWrappedAccessibilityDelegate(
        @Nullable AccessibilityDelegateCompat wrappedAccessibilityDelegate) {
      mWrappedAccessibilityDelegate = wrappedAccessibilityDelegate;
    }

    @Nullable private AccessibilityNodeProviderCompat mSpanNodeProvider;

    // ExploreByTouchHelper gives accessibility focus to any virtual id before asking this class, so
    // span eligibility is checked in front of its provider too: a span that isn't exposed or isn't
    // on screen can't take focus, and isn't reported as focused.
    @Override
    public @Nullable AccessibilityNodeProviderCompat getAccessibilityNodeProvider(View host) {
      if (mSpanNodeProvider != null) {
        return mSpanNodeProvider;
      }
      final AccessibilityNodeProviderCompat helperProvider =
          super.getAccessibilityNodeProvider(host);
      if (helperProvider == null) {
        return null;
      }
      mSpanNodeProvider = new SpanNodeProvider(helperProvider);
      return mSpanNodeProvider;
    }

    private final class SpanNodeProvider extends AccessibilityNodeProviderCompat {

      private final AccessibilityNodeProviderCompat mHelperProvider;

      SpanNodeProvider(AccessibilityNodeProviderCompat helperProvider) {
        mHelperProvider = helperProvider;
      }

      @Override
      public @Nullable AccessibilityNodeInfoCompat createAccessibilityNodeInfo(int virtualViewId) {
        return mHelperProvider.createAccessibilityNodeInfo(virtualViewId);
      }

      @Override
      public @Nullable List<AccessibilityNodeInfoCompat> findAccessibilityNodeInfosByText(
          String text, int virtualViewId) {
        return mHelperProvider.findAccessibilityNodeInfosByText(text, virtualViewId);
      }

      @Override
      public boolean performAction(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (action == AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS
            && virtualViewId != HOST_ID
            && !isSpanOnScreen(virtualViewId)) {
          return false;
        }
        return mHelperProvider.performAction(virtualViewId, action, arguments);
      }

      @Override
      public @Nullable AccessibilityNodeInfoCompat findFocus(int focus) {
        final int focusedId = getAccessibilityFocusedVirtualViewId();
        if (focus == AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY
            && focusedId != INVALID_ID
            && !isSpanOnScreen(focusedId)) {
          return null;
        }
        return mHelperProvider.findFocus(focus);
      }

      @Override
      public void addExtraDataToAccessibilityNodeInfo(
          int virtualViewId,
          AccessibilityNodeInfoCompat info,
          String extraDataKey,
          @Nullable Bundle arguments) {
        mHelperProvider.addExtraDataToAccessibilityNodeInfo(
            virtualViewId, info, extraDataKey, arguments);
      }
    }

    /** Clears accessibility focus from a span that is no longer exposed or on screen. */
    void clearIneligibleAccessibilityFocus() {
      final int focusedId = getAccessibilityFocusedVirtualViewId();
      if (focusedId == INVALID_ID || isSpanOnScreen(focusedId)) {
        return;
      }
      final AccessibilityNodeProviderCompat provider =
          getAccessibilityNodeProvider(RCTextView.this);
      if (provider != null) {
        provider.performAction(
            focusedId, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
      }
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
      if (!mExposeClickableSpansToAccessibility
          || !(mText instanceof Spanned)
          || mClickableSpans == null) {
        return INVALID_ID;
      }
      final Spanned spanned = (Spanned) mText;

      for (int i = 0; i < mClickableSpans.length; i++) {
        final ClickableSpan span = mClickableSpans[i];
        final int start = spanned.getSpanStart(span);
        final int end = spanned.getSpanEnd(span);
        int textOffset = RCTextView.this.getTextOffsetAt((int) x, (int) y);
        if (textOffset >= start && textOffset <= end && isSpanOnScreen(i)) {
          return i;
        }
      }

      return INVALID_ID;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
      if (!mExposeClickableSpansToAccessibility) {
        return;
      }
      final int virtualViewsCount = mClickableSpans != null ? mClickableSpans.length : 0;
      for (int i = 0; i < virtualViewsCount; i++) {
        // A span with nothing on screen (empty, or cut off by maxLines) has no node: a node with
        // stand-in bounds would still be focusable and clickable.
        if (isSpanOnScreen(i)) {
          virtualViewIds.add(i);
        }
      }
    }

    /** Whether the span is still exposed and any of it is drawn. */
    private boolean isSpanOnScreen(int virtualViewId) {
      if (!mExposeClickableSpansToAccessibility
          || !(mText instanceof Spanned)
          || mClickableSpans == null
          || virtualViewId < 0
          || virtualViewId >= mClickableSpans.length
          || mLayout == null) {
        return false;
      }
      return !spanBounds(virtualViewId, new Rect()).isEmpty();
    }

    /**
     * The span's bounds, first line only: TalkBack clicks the center of these bounds, which must
     * land on the span. The wrapped part needs no outline, as the whole span is read anyway.
     */
    private Rect spanBounds(int virtualViewId, Rect outRect) {
      final Spanned spanned = (Spanned) mText;
      final ClickableSpan span = mClickableSpans[virtualViewId];
      final int start = spanned.getSpanStart(span);
      final int end = spanned.getSpanEnd(span);
      final int startLine = mLayout.getLineForOffset(start);
      final int endLine = mLayout.getLineForOffset(end);
      final int selectionPathEnd =
          startLine == endLine ? end : mLayout.getLineVisibleEnd(startLine);
      final Path path = new Path();
      final RectF rectF = new RectF();
      mLayout.getSelectionPath(start, selectionPathEnd, path);
      path.computeBounds(rectF, /* unused */ true);
      rectF.offset(mLayoutTranslationX, mLayoutTranslationY);
      rectF.round(outRect);
      return outRect;
    }

    // ExploreByTouchHelper requires virtual nodes' parent bounds, set with the deprecated accessor.
    @SuppressWarnings("deprecation")
    @SuppressLint("DeprecatedMethod")
    @Override
    protected void onPopulateNodeForVirtualView(
        int virtualViewId, AccessibilityNodeInfoCompat node) {
      // The framework can ask for a node that is gone: after an unmount/mount cycle, after span
      // exposure is turned off, or for a span that is no longer on screen. Such a node is blank and
      // can't be focused or clicked.
      if (!isSpanOnScreen(virtualViewId)) {
        node.setText("");
        node.setBoundsInParent(new Rect());
        node.setClickable(false);
        node.setFocusable(false);
        return;
      }
      final Spanned spanned = (Spanned) mText;
      final ClickableSpan span = mClickableSpans[virtualViewId];
      final int start = spanned.getSpanStart(span);
      final int end = spanned.getSpanEnd(span);

      node.setBoundsInParent(spanBounds(virtualViewId, new Rect()));

      node.setClickable(true);
      node.setFocusable(true);
      node.setEnabled(true);
      node.setVisibleToUser(true);
      node.setText(spanned.subSequence(start, end));
      // This is needed to get the swipe action
      node.setClassName(ACCESSIBILITY_CLASS_BUTTON);

      if (mClickableSpanAccessibilityProvider != null
          && mClickableSpanAccessibilityProvider.onPopulateSpanNode(span, node)) {
        return;
      }
      if (span instanceof RCAccessibleClickableSpan) {
        populateNodeAccessibilityInfo((RCAccessibleClickableSpan) span, node);
      }
    }

    private void populateNodeAccessibilityInfo(
        RCAccessibleClickableSpan span, AccessibilityNodeInfoCompat node) {
      final String label = span.getAccessibilityLabel();
      // Default to link if no role is specified to align with iOS.
      final String role =
          span.getAccessibilityRole() == null
              ? ACCESSIBILITY_ROLE_LINK
              : span.getAccessibilityRole();
      AccessibilityUtils.initializeAccessibilityLabel(label, node);
      AccessibilityUtils.initializeAccessibilityRole(getContext(), role, null, node);
    }

    @Override
    protected boolean onPerformActionForVirtualView(
        int virtualViewId, int action, @Nullable Bundle arguments) {
      if (action == AccessibilityNodeInfoCompat.ACTION_CLICK && isSpanOnScreen(virtualViewId)) {
        mClickableSpans[virtualViewId].onClick(RCTextView.this);
        return true;
      }
      return false;
    }

    /**
     * Handles accessibility actions for the host view (not virtual views). Ported from AOSP {@code
     * TextView.performAccessibilityActionInternal()} — handles ACTION_SET_SELECTION, ACTION_COPY,
     * and PROCESS_TEXT actions.
     */
    @Override
    public boolean performAccessibilityAction(View host, int action, @Nullable Bundle args) {
      if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION
          && mEditor != null
          && mTextIsSelectable) {
        // Ported from AOSP: ACTION_SET_SELECTION → set selection on spannable
        if (args != null) {
          int start = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1);
          int end = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1);
          Spannable spannable = mEditor.getSpannable();
          if (spannable != null) {
            // Ported from AOSP: clear selection when both start and end are -1
            if (start == end && start == -1) {
              Selection.removeSelection(spannable);
              return true;
            }
            if (start >= 0 && start <= end && end <= spannable.length()) {
              Selection.setSelection(spannable, start, end);
              // Start action mode if we have a selection range
              if (start != end) {
                mEditor.startSelectionActionMode(false);
              }
              return true;
            }
          }
        }
      } else if (action == AccessibilityNodeInfo.ACTION_COPY
          && mEditor != null
          && mTextIsSelectable) {
        // Ported from AOSP: ACTION_COPY → copy to clipboard (requires focus and selection)
        if (isFocused() && mEditor.hasSelection()) {
          mEditor.copyToClipboard();
          return true;
        }
        return false;
      } else if (mEditor != null && mTextIsSelectable) {
        // Check for PROCESS_TEXT accessibility actions (e.g., Translate, Search).
        // Ported from AOSP TextView.performAccessibilityActionInternal().
        if (mEditor.getProcessTextIntentActionsHandler().performAccessibilityAction(action)) {
          return true;
        }
      }
      return super.performAccessibilityAction(host, action, args);
    }

    @Override
    protected void onVirtualViewKeyboardFocusChanged(int virtualViewId, boolean hasFocus) {
      if (mClickableSpans != null
          && virtualViewId < mClickableSpans.length
          && mClickableSpans[virtualViewId] instanceof RCAccessibleClickableSpan) {
        ((RCAccessibleClickableSpan) mClickableSpans[virtualViewId]).setKeyboardFocused(hasFocus);

        // force redraw when focus changes, so that any visual changes get applied.
        RCTextView.this.invalidate();
      }
      super.onVirtualViewKeyboardFocusChanged(virtualViewId, hasFocus);
    }

    @Override
    public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
      super.onPopulateAccessibilityEvent(host, event);
      if (!TextUtils.isEmpty(mText)) {
        event.getText().add(getTextForAccessibility());
      }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
      super.onInitializeAccessibilityNodeInfo(host, info);
      final CharSequence textForAccessibility = ((RCTextView) host).getTextForAccessibility();
      if (!TextUtils.isEmpty(textForAccessibility)) {
        info.setText(textForAccessibility);

        info.addAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        info.setMovementGranularities(
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE);
        info.addAction(AccessibilityNodeInfo.ACTION_SET_SELECTION);
        // Add copy action when text is selectable
        if (mTextIsSelectable) {
          info.addAction(AccessibilityNodeInfo.ACTION_COPY);
          // API 33+: mark the node as text-selectable
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info.setTextSelectable(true);
          }
          // Add PROCESS_TEXT accessibility actions (e.g., Translate, Search).
          // Ported from AOSP TextView.onInitializeAccessibilityNodeInfoInternal().
          if (mEditor != null) {
            RCTextEditor.ProcessTextIntentActionsHandler handler =
                mEditor.getProcessTextIntentActionsHandler();
            handler.initializeAccessibilityActions();
            handler.onInitializeAccessibilityNodeInfo(info.unwrap());
          }
        }
      }
      // We call the wrapped delegate to override any configuration done here.
      if (mWrappedAccessibilityDelegate != null) {
        mWrappedAccessibilityDelegate.onInitializeAccessibilityNodeInfo(host, info);
      }
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  private static class Api26Utils {

    @DoNotInline
    public static int getFocusable(View view) {
      return view.getFocusable();
    }

    @DoNotInline
    public static void setFocusable(View view, int focusable) {
      view.setFocusable(focusable);
    }
  }

  private static boolean isMIUIDevice() {
    String brand = Build.BRAND.toLowerCase(Locale.US);
    return "xiaomi".equals(brand) || "poco".equals(brand) || "redmi".equals(brand);
  }

  @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private static class Api34Utils {

    private static List<Path> highlightPaths;
    private static List<Paint> highlightPaints;

    @DoNotInline
    public static void draw(
        Layout layout,
        Canvas canvas,
        @Nullable Path selectionPath,
        @Nullable Paint selectionPaint) {
      if (selectionPath != null) {
        // Layout#drawHighlights noops when highlightPaths and highlightPaints are nulls
        // Passing empty lists to fix that
        if (highlightPaths == null) {
          highlightPaths = new ArrayList<>();
        }
        if (highlightPaints == null) {
          highlightPaints = new ArrayList<>();
        }
      }
      layout.draw(canvas, highlightPaths, highlightPaints, selectionPath, selectionPaint, 0);
    }
  }

  private class LongClickRunnable implements Runnable {

    private LongClickableSpan longClickableSpan;
    private View longClickableSpanView;

    LongClickRunnable(LongClickableSpan span, View view) {
      longClickableSpan = span;
      longClickableSpanView = view;
    }

    @Override
    public void run() {
      mLongClickActivated =
          (mClickableSpanListener != null
                  && mClickableSpanListener.onLongClick(longClickableSpan, longClickableSpanView))
              || longClickableSpan.onLongClick(longClickableSpanView);
      sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
    }
  }
}
