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

package com.facebook.samples.litho.java.dynamicprops;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import androidx.annotation.Nullable;

public class ClockView extends View {
  private static final String TAG = ClockView.class.getSimpleName();

  public static final long ONE_MINUTE = 60L * 1000;
  public static final long ONE_HOUR = 60 * ONE_MINUTE;
  public static final long TWELVE_HOURS = 12 * ONE_HOUR;

  private static final int DEFAULT_SIZE_DP = 200;
  private static final int STROKE_THIN_WIDTH_DP = 6;
  private static final int STROKE_MEDIUM_WIDTH_DP = 9;
  private static final int STROKE_WIDE_WIDTH_DP = 12;

  private int mDefaultSizePx;
  private int mStrokeThinWidthPx;
  private int mStrokeMediumWidthPx;
  private int mStrokeWideWidthPx;

  private Paint mPaintThin;
  private Paint mPaintMedium;
  private Paint mPaintWide;

  private int mCenterX;
  private int mCenterY;
  private int mRadius;
  private int mCenterDotRadius;
  private int mShortHandLength;
  private int mLongHandLength;
  private int mShortHandAngle;
  private int mLongHandAngle;

  private long mTime;

  @SuppressWarnings("this-escape")
  public ClockView(Context context) {
    super(context);
    init();
  }

  @SuppressWarnings("this-escape")
  public ClockView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  @SuppressWarnings("this-escape")
  public ClockView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  @SuppressWarnings("this-escape")
  public ClockView(
      Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    init();
  }

  @SuppressWarnings("this-escape")
  private void init() {
    mDefaultSizePx = dpToPx(DEFAULT_SIZE_DP);
    mStrokeThinWidthPx = dpToPx(STROKE_THIN_WIDTH_DP);
    mStrokeMediumWidthPx = dpToPx(STROKE_MEDIUM_WIDTH_DP);
    mStrokeWideWidthPx = dpToPx(STROKE_WIDE_WIDTH_DP);

    mPaintThin = buildPaint(mStrokeThinWidthPx);
    mPaintMedium = buildPaint(mStrokeMediumWidthPx);
    mPaintWide = buildPaint(mStrokeWideWidthPx);
  }

  private Paint buildPaint(int strokeWidth) {
    Paint paint = new Paint();
    paint.setColor(Color.BLACK);
    paint.setAntiAlias(true);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(strokeWidth);
    return paint;
  }

  public void setTime(long time) {
    if (time < 0 || time >= TWELVE_HOURS) {
      throw new IllegalArgumentException(
          "Time should be in range between [0, "
              + TWELVE_HOURS
              + "), where 0 corresponds to 00:00, "
              + TWELVE_HOURS
              + " - to 12:00");
    }

    if (mTime == time) {
      return;
    }

    mTime = time;

    Log.d(TAG, "Time set. Time=" + time + " - " + getTimeString());

    final float inHourProgress = ((float) time % ONE_HOUR) / ONE_HOUR;
    mLongHandAngle = (int) (360 * inHourProgress);

    final float inTwelveHoursProgress = ((float) time) / TWELVE_HOURS;
    mShortHandAngle = (int) (360 * inTwelveHoursProgress);

    invalidate();
  }

  public long getTime() {
    return mTime;
  }

  public String getTimeString() {
    return getTimeString(mTime, true);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int w = getSize(widthMeasureSpec);
    final int h = getSize(heightMeasureSpec);
    final int dim = Math.min(w, h);
    setMeasuredDimension(dim, dim);
  }

  private int getSize(int measureSpec) {
    int specMode = MeasureSpec.getMode(measureSpec);
    int specSize = MeasureSpec.getSize(measureSpec);

    switch (specMode) {
      case MeasureSpec.UNSPECIFIED:
        return mDefaultSizePx;

      case MeasureSpec.AT_MOST:
        return Math.min(mDefaultSizePx, specSize);

      case MeasureSpec.EXACTLY:
        return specSize;
    }

    return 0;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    Log.d(TAG, "w=" + w + ", h=" + h + ", oldw=" + w + ", oldh=" + h);

    mCenterX = w / 2;
    mCenterY = h / 2;

    final int dim = Math.min(w, h);

    mRadius = (dim - mStrokeWideWidthPx) / 2;
    mCenterDotRadius = (int) (mRadius * 0.07f);
    mShortHandLength = (int) (mRadius * 0.4f);
    mLongHandLength = (int) (mRadius * 0.7f);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    Log.d(TAG, "onDraw");

    canvas.drawCircle(mCenterX, mCenterY, mRadius, mPaintWide);
    canvas.drawCircle(mCenterX, mCenterY, mCenterDotRadius, mPaintThin);

    drawHand(canvas, mLongHandAngle, mLongHandLength, mPaintThin);
    drawHand(canvas, mShortHandAngle, mShortHandLength, mPaintMedium);
  }

  private void drawHand(Canvas canvas, int degrees, int length, Paint paint) {
    // Save and then rotate canvas
    final int savedCount = canvas.save();
    canvas.rotate(degrees, mCenterX, mCenterY);

    // Calculate hand's start and end points
    final int startX = mCenterX;
    final int startY = mCenterY - mCenterDotRadius;
    final int endX = mCenterX;
    final int endY = startY - length;

    // Draw a hand
    canvas.drawLine(startX, startY, endX, endY, paint);

    // Round up a hand's end
    canvas.drawCircle(endX, endY, 0.001f, paint);

    // Restore canvas
    canvas.restoreToCount(savedCount);
  }

  @SuppressWarnings("this-escape")
  private int dpToPx(float dp) {
    float densityDpi = getResources().getDisplayMetrics().densityDpi;
    return (int) (dp * (densityDpi / DisplayMetrics.DENSITY_DEFAULT));
  }

  public static int getHours(long time) {
    return (int) ((time / ONE_HOUR) % 12);
  }

  public static int getMinutes(long time) {
    return (int) ((time / ONE_MINUTE) % 60);
  }

  public static String getTimeString(long time, boolean omitSuffix) {
    final boolean isAM = time < TWELVE_HOURS;
    int hours = getHours(time);
    if (hours == 0 && !isAM) {
      hours = 12;
    }
    final String s = String.format("%02d:%02d", hours, getMinutes(time));

    if (omitSuffix) {
      return s;
    }

    return s + (isAM ? " AM" : " PM");
  }
}
