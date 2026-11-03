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

package com.facebook.samples.litho.java.fastscroll;

import static com.facebook.samples.litho.java.fastscroll.FastScrollHandleComponentSpec.HANDLE_SIZE_DP;
import static com.facebook.samples.litho.java.fastscroll.FastScrollHandleComponentSpec.HANDLE_VERTICAL_MARGIN;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.facebook.litho.DynamicValue;
import com.facebook.litho.sections.widget.RecyclerCollectionEventsController;

class ScrollController extends RecyclerView.OnScrollListener implements View.OnTouchListener {
  private final DynamicValue<Float> handleOffsetDV;
  private final RecyclerCollectionEventsController scrollController;

  private int scrollOffsetRange = -1;
  private int handleOffsetRange = -1;

  private int scrollOffset = 0;
  private int handleOffset = 0;

  private boolean userControlling = false;

  private float prevHandleTouchEventY;

  ScrollController(
      RecyclerCollectionEventsController scrollController, DynamicValue<Float> handleOffsetDV) {
    this.scrollController = scrollController;
    this.handleOffsetDV = handleOffsetDV;
  }

  @Override
  public void onScrolled(RecyclerView rv, int dx, int dy) {
    final int scrollRange = rv.computeVerticalScrollRange();
    final int scrollExtent = rv.computeVerticalScrollExtent();

    scrollOffsetRange = scrollRange - scrollExtent;
    handleOffsetRange =
        scrollExtent - dpToPixel(rv.getContext(), 2 * HANDLE_VERTICAL_MARGIN + HANDLE_SIZE_DP);

    scrollOffset = rv.computeVerticalScrollOffset();

    if (userControlling) {
      return;
    }

    final float scale = ((float) scrollOffset) / scrollOffsetRange;
    handleOffset = (int) (handleOffsetRange * scale);
    handleOffsetDV.set((float) handleOffset);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event) {
    return onHandleTouchEvent(event);
  }

  boolean onHandleTouchEvent(MotionEvent event) {
    final float currentY = event.getRawY();

    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        userControlling = true;
        break;

      case MotionEvent.ACTION_MOVE:
        final float dy = prevHandleTouchEventY - currentY;

        if (handleOffsetRange > 0) {
          // Adjust offset
          handleOffset = (int) (handleOffset - dy);
          // Check if it is in the correct range
          handleOffset = Math.max(0, handleOffset);
          handleOffset = Math.min(handleOffsetRange, handleOffset);
          // Pass it on to the DynamicValue
          handleOffsetDV.set((float) handleOffset);

          final float scale = ((float) handleOffset) / handleOffsetRange;
          final int newScrollOffset = (int) (scale * scrollOffsetRange);

          scrollController.requestScrollBy(0, newScrollOffset - scrollOffset);

          scrollOffset = newScrollOffset;
        }
        break;

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        userControlling = false;
        break;
    }

    prevHandleTouchEventY = currentY;

    return true;
  }

  private static int dpToPixel(Context context, int dp) {
    return (int)
        (dp
            * ((float) context.getResources().getDisplayMetrics().densityDpi
                / DisplayMetrics.DENSITY_DEFAULT));
  }
}
