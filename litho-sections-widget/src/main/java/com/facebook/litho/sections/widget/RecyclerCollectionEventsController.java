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

package com.facebook.litho.sections.widget;

import static com.facebook.litho.widget.SnapUtil.SNAP_NONE;
import static com.facebook.litho.widget.SnapUtil.SNAP_TO_CENTER;
import static com.facebook.litho.widget.SnapUtil.SNAP_TO_CENTER_CHILD;
import static com.facebook.litho.widget.SnapUtil.SNAP_TO_END;
import static com.facebook.litho.widget.SnapUtil.SNAP_TO_START;

import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.sections.SectionTree;
import com.facebook.litho.widget.LayoutInfo;
import com.facebook.litho.widget.RecyclerEventsController;
import com.facebook.litho.widget.SmoothScrollAlignmentType;
import com.facebook.litho.widget.SnapUtil;
import javax.annotation.Nullable;

/**
 * An controller that can be passed as {@link com.facebook.litho.annotations.Prop} to a
 * RecyclerCollection component to trigger events from outside the component hierarchy.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class RecyclerCollectionEventsController extends RecyclerEventsController {

  @Nullable private SectionTree mSectionTree;
  private int mSnapMode = SNAP_NONE;
  private int mFirstCompletelyVisibleItemPosition = 0;
  private int mLastCompletelyVisibleItemPosition = 0;
  private int mItemCount = 0;

  /** Sent the RecyclerCollection a request to refresh it's backing data. */
  public void requestRefresh() {
    requestRefresh(false);
  }

  /**
   * Sent the RecyclerCollection a request to refresh it's backing data. If showSpinner is true,
   * then refresh spinner is shown.
   *
   * @param showSpinner
   */
  public void requestRefresh(boolean showSpinner) {
    if (mSectionTree != null) {
      if (showSpinner) {
        showRefreshing();
      }

      mSectionTree.refresh();
    }
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content to the next item
   * in the binder.
   *
   * @param animated whether the scroll will happen with animation.
   */
  public void requestScrollToNextPosition(boolean animated) {
    final int defaultTarget = Math.max(0, mLastCompletelyVisibleItemPosition + 1);
    final int snapTarget = getSmoothScrollTarget(true, defaultTarget);
    requestScrollToPositionInner(animated, defaultTarget, snapTarget);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content to the next item
   * in the binder.
   *
   * @param smoothScroller The provided smoothScroller is used to scroll to the target.
   */
  public void requestScrollToNextPositionWithSnap(RecyclerView.SmoothScroller smoothScroller) {
    final int defaultTarget = Math.max(0, mLastCompletelyVisibleItemPosition + 1);
    final int snapTarget = getSmoothScrollTarget(true, defaultTarget);
    requestScrollToPositionInner(
        true, // Animated has to be true, otherwise no need to apply customize Speed
        defaultTarget,
        snapTarget,
        smoothScroller);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content to the previous
   * item in the binder.
   *
   * @param animated whether the scroll will happen with animation.
   */
  public void requestScrollToPreviousPosition(boolean animated) {
    final int defaultTarget = Math.max(0, mFirstCompletelyVisibleItemPosition - 1);
    final int snapTarget = getSmoothScrollTarget(false, defaultTarget);
    requestScrollToPositionInner(animated, defaultTarget, snapTarget);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content to the given
   * target position taking into account snapping behavior.
   *
   * <p>For example, if the content is center snapped, then the {@link RecyclerView} will scroll
   * contents until the target position is visible at the center (as opposed to the edge of {@link
   * RecyclerView} which is the case when using {@link #requestScrollToPosition(int, boolean)}).
   */
  public void requestScrollToPositionWithSnap(final int target) {
    requestScrollToPositionInner(true, target, target);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content to the given
   * target position taking into account snapping behavior. Can toggle if the scroll is animated
   */
  public void requestScrollToPositionWithSnap(final int target, final boolean animated) {
    requestScrollToPositionInner(animated, target, target);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content to the given
   * target position taking into account provided snapping behavior. The provided smoothScroller is
   * used to scroll to the target.
   */
  public void requestScrollToPositionWithSnap(
      final int target, final RecyclerView.SmoothScroller smoothScroller) {
    requestScrollToPositionInner(true, target, target, smoothScroller);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content by the given
   * margins.
   */
  public void requestScrollBy(int dx, int dy) {
    final RecyclerView recyclerView = getRecyclerView();
    if (recyclerView == null) {
      return;
    }

    recyclerView.scrollBy(dx, dy);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content by the given
   * margins, animate a scroll by the given amount of pixels along either axis.
   */
  public void requestSmoothScrollBy(int dx, int dy) {
    final RecyclerView recyclerView = getRecyclerView();
    if (recyclerView == null) {
      return;
    }

    recyclerView.smoothScrollBy(dx, dy);
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to scroll the content to the target with
   * the given custom attribute {@link com.facebook.litho.widget.RecyclerBinder#ID_CUSTOM_ATTR_KEY
   * "id"}.
   */
  public void requestSmoothScroll(Object id, int offset, SmoothScrollAlignmentType type) {
    if (mSectionTree != null) {
      mSectionTree.requestSmoothFocusOnRoot(id, offset, type);
    }
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to instantly scroll the content to the
   * target with the given custom attribute {@link
   * com.facebook.litho.widget.RecyclerBinder#ID_CUSTOM_ATTR_KEY "id"}.
   */
  public void requestScroll(Object id, int offset) {
    if (mSectionTree != null) {
      mSectionTree.requestFocusOnRoot(id, offset);
    }
  }

  /**
   * Send the {@link RecyclerCollectionComponent} a request to directly focus on the target with
   * offset, used to trigger an update in {@link com.facebook.litho.widget.RecyclerBinder} without
   * viewport change.
   */
  public void requestFocusOnRoot(int index, int offset) {
    if (mSectionTree != null) {
      mSectionTree.requestFocusOnRoot(index, offset);
    }
  }

  /**
   * @param animated whether the scroll will happen with animation.
   * @param defaultTarget target to use as fallback.
   * @param snapTarget target that takes into account snapping behavior.
   */
  private void requestScrollToPositionInner(boolean animated, int defaultTarget, int snapTarget) {
    requestScrollToPositionInner(
        animated, defaultTarget, snapTarget, null // smoothScroller
        );
  }

  /**
   * @param animated whether the scroll will happen with animation.
   * @param defaultTarget target to use as fallback.
   * @param snapTarget target that takes into account snapping behavior.
   * @param smoothScroller custom smooth scroller
   */
  private void requestScrollToPositionInner(
      boolean animated,
      int defaultTarget,
      int snapTarget,
      @Nullable RecyclerView.SmoothScroller smoothScroller) {
    final RecyclerView recyclerView = getRecyclerView();
    if (recyclerView == null) {
      return;
    }

    final RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
    if (layoutManager == null || recyclerView.isLayoutFrozen()) {
      return;
    }

    if (!animated && mSnapMode == SNAP_TO_START) {
      requestScrollToPositionWithOffset(snapTarget, /* offset */ 0);
      return;
    }

    if (!animated) {
      requestScrollToPosition(defaultTarget, false);
      return;
    }

    if (smoothScroller == null && mSnapMode == SNAP_NONE) {
      requestScrollToPosition(defaultTarget, true);
      return;
    }

    if (smoothScroller == null) {
      smoothScroller =
          SnapUtil.getSmoothScrollerWithOffset(
              recyclerView.getContext(),
              0, // offset
              getSmoothScrollAlignmentTypeFrom(mSnapMode));
    }

    smoothScroller.setTargetPosition(snapTarget);
    layoutManager.startSmoothScroll(smoothScroller);
  }

  private int getSmoothScrollTarget(boolean forward, int defaultTarget) {
    switch (mSnapMode) {
      case SNAP_NONE:
        return defaultTarget;
      case SNAP_TO_START:
        return Math.max(
            0,
            forward
                ? mFirstCompletelyVisibleItemPosition + 1
                : mFirstCompletelyVisibleItemPosition - 1);
      case SNAP_TO_END: // SNAP_TO_END not yet implemented
        return Math.max(
            0,
            forward
                ? mLastCompletelyVisibleItemPosition + 1
                : mLastCompletelyVisibleItemPosition - 1);
      case SNAP_TO_CENTER:
      case SNAP_TO_CENTER_CHILD:
        final RecyclerView recyclerView = getRecyclerView();
        if (recyclerView == null) {
          return defaultTarget;
        }
        final int centerPositionX = recyclerView.getWidth() / 2;
        final int centerPositionY = recyclerView.getHeight() / 2;
        final View centerView = recyclerView.findChildViewUnder(centerPositionX, centerPositionY);
        if (centerView == null) {
          return defaultTarget;
        }
        final int centerViewPosition = recyclerView.getChildAdapterPosition(centerView);
        return Math.max(0, forward ? centerViewPosition + 1 : centerViewPosition - 1);
      default:
        return defaultTarget;
    }
  }

  private static SmoothScrollAlignmentType getSmoothScrollAlignmentTypeFrom(int snapMode) {
    switch (snapMode) {
      case SNAP_TO_START:
        return SmoothScrollAlignmentType.SNAP_TO_START;
      case SNAP_TO_END: // SNAP_TO_END not yet implemented
        return SmoothScrollAlignmentType.SNAP_TO_END;
      case SNAP_TO_CENTER:
      case SNAP_TO_CENTER_CHILD:
        return SmoothScrollAlignmentType.SNAP_TO_CENTER;
      default:
        return SmoothScrollAlignmentType.DEFAULT;
    }
  }

  public void setSectionTree(@Nullable SectionTree sectionTree) {
    mSectionTree = sectionTree;
  }

  public void setSnapMode(int snapMode) {
    mSnapMode = snapMode;
  }

  public void updateFirstLastFullyVisibleItemPositions(LayoutInfo layoutInfo) {
    final int firstCompletelyVisibleItemPosition = layoutInfo.findFirstFullyVisibleItemPosition();
    if (firstCompletelyVisibleItemPosition != -1) {
      // firstCompletelyVisibleItemPosition can be -1 in middle of the scroll, so
      // wait until it finishes to set the state.
      mFirstCompletelyVisibleItemPosition = firstCompletelyVisibleItemPosition;
    }

    final int lastCompletelyVisibleItemPosition = layoutInfo.findLastFullyVisibleItemPosition();
    if (lastCompletelyVisibleItemPosition != -1) {
      mLastCompletelyVisibleItemPosition = lastCompletelyVisibleItemPosition;
    }
    mItemCount = layoutInfo.getItemCount();
  }

  public int getFirstCompletelyVisibleItemPosition() {
    return mFirstCompletelyVisibleItemPosition;
  }

  public int getLastCompletelyVisibleItemPosition() {
    return mLastCompletelyVisibleItemPosition;
  }

  public int getItemCount() {
    return mItemCount;
  }
}
