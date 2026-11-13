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

package com.facebook.rendercore.simplelist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate;
import androidx.recyclerview.widget.SnapHelper;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import com.facebook.infer.annotation.Nullsafe;
import java.util.ArrayList;

/** RenderCore RecyclerView. Adds customisations, like being able to disable scrolling. */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class RCRecyclerView extends RecyclerView implements NestedScrollingParent3 {

  // Parents supporting nested scrolling can set this tag to inform children about the direction of
  // the nested scroll
  public static class NestedScrollingOrientationHint {
    private final int orientation;

    public NestedScrollingOrientationHint(int orientation) {
      this.orientation = orientation;
    }
  }

  public interface PullToRefreshParent {
    boolean isPullToRefreshEnabled();
  }

  @Nullable private NestedScrollingOrientationHint mNestedScrollingDirectionHint = null;
  private boolean mIsScrollable = true;

  private boolean mIsPullToRefreshEnabled = false;

  // Tracks the last DPAD key code so requestChildFocus can be direction-aware.
  private int mLastDpadKeyCode = 0;

  public interface RCRecyclerViewNestedScrollingChild {}

  private static final int[] ZERO_OFFSET = new int[] {0, 0};

  /** Whether to allow scrolling. By default, it is allowed. */
  private boolean mIsScrollEnabled = true;

  private final NestedScrollingParentHelper mParentHelper = new NestedScrollingParentHelper(this);

  /** The accessibility delegate set on the RecyclerView in its default scroll state. */
  private @Nullable AccessibilityDelegateCompat mOriginalAccessibilityDelegateCompat;

  private boolean mOverrideStateToIsDragging;
  private @Nullable ArrayList<OnScrollListener> mScrollListeners;

  // Set from the list's mount binder; false everywhere else until other mounts pass it too.
  private boolean mReportMovedScrollDistance;

  /** Sets whether this list reports the distance it moved in nested scrolls. */
  public void setReportMovedScrollDistance(final boolean enabled) {
    mReportMovedScrollDistance = enabled;
  }

  public RCRecyclerView(final Context context) {
    super(context);
  }

  public RCRecyclerView(final Context context, final @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  public RCRecyclerView(
      final Context context, final @Nullable AttributeSet attrs, final int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  public void setIsScrollEnabled(final boolean isScrollEnabled) {
    if (mIsScrollEnabled == isScrollEnabled) {
      return;
    }

    final boolean wasScrollEnabled = mIsScrollEnabled;
    mIsScrollEnabled = isScrollEnabled;

    if (wasScrollEnabled) {
      // Going from scrollable to non-scrollable. Save the accessibility delegate so we can restore
      // later.
      //
      // Due to a weird quirk with the Android AX apis, getAccessibilityDelegate can return a new
      // instance with each call, so we need to do this everytime state changes to non-scrollable.
      mOriginalAccessibilityDelegateCompat = ViewCompat.getAccessibilityDelegate(this);
    }

    if (mIsScrollEnabled) {
      // Scrollability is being restored, so we restore the original delegate.
      ViewCompat.setAccessibilityDelegate(this, mOriginalAccessibilityDelegateCompat);
      mOriginalAccessibilityDelegateCompat = null;
    } else {
      ViewCompat.setAccessibilityDelegate(this, createScrollDisablingAccessibilityDelegate());
    }
  }

  @Override
  public boolean onInterceptTouchEvent(final MotionEvent e) {
    return canInterceptTouchEvent(e) && super.onInterceptTouchEvent(e);
  }

  private boolean canInterceptTouchEvent(final MotionEvent e) {
    if (mIsScrollable) {
      // We are scrollable. We can intercept if scrolling is enabled.
      return mIsScrollEnabled;
    }

    // We are not scrollable.  We can only intercept non-scroll (i.e. non-MOVE) events, unless we
    // are inside a parent that handles pull-to-refresh (e.g. Android's SwipeRefreshLayout). The
    // parent may rely on us intercepting the MOVE event in order to correctly execute the nested
    // scrolling contract so it can detect and perform a refresh action.
    return e.getAction() != MotionEvent.ACTION_MOVE || mIsPullToRefreshEnabled;
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(final MotionEvent e) {
    // If scrolling is disabled, do not consume touch.
    return mIsScrollEnabled && super.onTouchEvent(e);
  }

  /**
   * If scrolling is disabled, do not scroll to reveal a child rectangle (e.g. when a screen reader
   * moves accessibility focus onto a descendant). Ancestors still get the request.
   */
  @Override
  public boolean requestChildRectangleOnScreen(
      final View child, final Rect rect, final boolean immediate) {
    return mIsScrollEnabled && super.requestChildRectangleOnScreen(child, rect, immediate);
  }

  /**
   * Creates an accessibility delegate that disallows scrolling actions. This is set on the view
   * when scrolling is being disabled.
   *
   * @return RecyclerViewAccessibilityDelegate instance
   */
  private RecyclerViewAccessibilityDelegate createScrollDisablingAccessibilityDelegate() {
    return new RecyclerViewAccessibilityDelegate(this) {
      @Override
      public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
        if (mOriginalAccessibilityDelegateCompat != null) {
          mOriginalAccessibilityDelegateCompat.onInitializeAccessibilityNodeInfo(host, info);
        }
        info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_UP);
        info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_DOWN);
        info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT);
        info.removeAction(
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT);
        info.removeAction(
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD);
        info.removeAction(
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD);
        info.setScrollable(false);
      }
    };
  }

  @Override
  public boolean onStartNestedScroll(
      @NonNull View child, @NonNull View target, int axes, int type) {
    if (target instanceof RCRecyclerViewNestedScrollingChild) {
      setIsScrollEnabled(false);
      return true;
    }
    return false;
  }

  @Override
  public void onNestedScrollAccepted(
      @NonNull View child, @NonNull View target, int axes, int type) {
    mParentHelper.onNestedScrollAccepted(child, target, axes, type);
  }

  @Override
  public void onStopNestedScroll(@NonNull View target, int type) {
    setIsScrollEnabled(true);
    mParentHelper.onStopNestedScroll(target, type);
    if (getScrollState() == SCROLL_STATE_DRAGGING) {
      mOverrideStateToIsDragging = false;

      if (mScrollListeners != null) {
        for (OnScrollListener listener : mScrollListeners) {
          listener.onScrollStateChanged(this, SCROLL_STATE_IDLE);
        }
      }
    }
  }

  @Override
  public void onNestedScroll(
      @NonNull View target,
      int dxConsumed,
      int dyConsumed,
      int dxUnconsumed,
      int dyUnconsumed,
      int type) {
    onNestedScrollInternal(dxUnconsumed, dyUnconsumed, type, null);
  }

  @Override
  public void onNestedPreScroll(
      @NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
    dispatchNestedPreScroll(dx, dy, consumed, ZERO_OFFSET, type);
  }

  @Override
  public int getNestedScrollAxes() {
    return mParentHelper.getNestedScrollAxes();
  }

  @Override
  public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
    dispatchNestedFling(velocityX, velocityY, consumed);
    if (fling((int) -velocityX, (int) -velocityY)) {
      mOverrideStateToIsDragging = false;
      return true;
    }
    return false;
  }

  @Override
  public boolean onNestedPreFling(@NonNull View target, float velocityX, float velocityY) {
    return dispatchNestedPreFling(velocityX, velocityY);
  }

  @Override
  public void onNestedScroll(
      @NonNull View target,
      int dxConsumed,
      int dyConsumed,
      int dxUnconsumed,
      int dyUnconsumed,
      int type,
      @NonNull int[] consumed) {
    onNestedScrollInternal(dxUnconsumed, dyUnconsumed, type, consumed);
  }

  private void onNestedScrollInternal(
      int dxUnconsumed, int dyUnconsumed, int type, @Nullable int[] consumed) {
    final int oldScrollX = getScrollX();
    final int oldScrollY = getScrollY();
    nestedScrollBy(dxUnconsumed, dyUnconsumed);
    mOverrideStateToIsDragging = true;
    if (mScrollListeners != null) {
      for (OnScrollListener listener : mScrollListeners) {
        listener.onScrollStateChanged(this, SCROLL_STATE_DRAGGING);
      }
    }
    final int yConsumed = getScrollY() - oldScrollY;
    final int xConsumed = getScrollX() - oldScrollX;
    if (consumed != null) {
      consumed[0] += xConsumed;
      consumed[1] += yConsumed;
    }
    dispatchNestedScroll(0, xConsumed, 0, yConsumed, ZERO_OFFSET, type, consumed);
  }

  @Override
  public int getScrollState() {
    if (mOverrideStateToIsDragging) {
      return SCROLL_STATE_DRAGGING;
    }
    return super.getScrollState();
  }

  /** we need to keep track of scroll listeners so we can manually invoke the dragging state */
  @Override
  public void addOnScrollListener(@NonNull OnScrollListener listener) {
    if (mScrollListeners == null) {
      mScrollListeners = new ArrayList<>();
    }
    mScrollListeners.add(listener);
    super.addOnScrollListener(listener);
  }

  @Override
  public void removeOnScrollListener(@NonNull OnScrollListener listener) {
    if (mScrollListeners != null) {
      mScrollListeners.remove(listener);
    }
    super.removeOnScrollListener(listener);
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    super.onLayout(changed, l, t, r, b);
    mNestedScrollingDirectionHint = findNestedScrollingHint(getParent());
    mIsPullToRefreshEnabled = isPullToRefreshEnabled(getParent());
    mIsScrollable =
        canScrollVertically(1)
            || canScrollVertically(-1)
            || canScrollHorizontally(1)
            || canScrollHorizontally(-1);
  }

  @Nullable
  private static NestedScrollingOrientationHint findNestedScrollingHint(
      @Nullable ViewParent parent) {
    if (parent instanceof View) {
      Object scrollingDirectionHint = ((View) parent).getTag();
      if (scrollingDirectionHint instanceof NestedScrollingOrientationHint) {
        return (NestedScrollingOrientationHint) scrollingDirectionHint;
      }
    }

    if (parent != null) {
      return findNestedScrollingHint(parent.getParent());
    }

    return null;
  }

  private static boolean isPullToRefreshEnabled(@Nullable ViewParent parent) {
    if (parent instanceof PullToRefreshParent) {
      return ((PullToRefreshParent) parent).isPullToRefreshEnabled();
    }

    if (parent != null) {
      return isPullToRefreshEnabled(parent.getParent());
    }

    return false;
  }

  @SuppressLint("WrongConstant")
  @Override
  public boolean isNestedScrollingEnabled() {
    if (mNestedScrollingDirectionHint != null
        && getLayoutManager() instanceof LinearLayoutManager) {
      final LinearLayoutManager linearLayoutManager = (LinearLayoutManager) getLayoutManager();
      if (mNestedScrollingDirectionHint.orientation != linearLayoutManager.getOrientation()
          || !mIsScrollEnabled
          || !mIsScrollable) {
        return false;
      }
    }

    if (mNestedScrollingDirectionHint != null
        && getLayoutManager() instanceof StaggeredGridLayoutManager) {
      final StaggeredGridLayoutManager linearLayoutManager =
          (StaggeredGridLayoutManager) getLayoutManager();
      if (mNestedScrollingDirectionHint.orientation != linearLayoutManager.getOrientation()) {
        return false;
      }
    }

    return super.isNestedScrollingEnabled();
  }

  @Override
  public void requestChildFocus(View child, @Nullable View focused) {
    super.requestChildFocus(child, focused);
    final int dpadDirection = mLastDpadKeyCode;
    mLastDpadKeyCode = 0;
    // Only adjust for DPAD navigation on collections WITHOUT snap.
    // Snap-enabled collections handle fading edge positioning using left-offset-on-snap option.
    boolean hasSnap = getOnFlingListener() instanceof SnapHelper;
    if (child != null && dpadDirection != 0 && !hasSnap) {
      final int verticalFading = getVerticalFadingEdgeLength();
      final int horizontalFading = getHorizontalFadingEdgeLength();
      post(
          () -> {
            switch (dpadDirection) {
              case KeyEvent.KEYCODE_DPAD_UP:
                if (verticalFading > 0) {
                  int top = child.getTop();
                  if (top < verticalFading) {
                    smoothScrollBy(0, top - verticalFading);
                  }
                }
                break;
              case KeyEvent.KEYCODE_DPAD_DOWN:
                if (verticalFading > 0) {
                  int bottom = child.getBottom();
                  int height = getHeight();
                  if (bottom > height - verticalFading) {
                    smoothScrollBy(0, bottom - (height - verticalFading));
                  }
                }
                break;
              case KeyEvent.KEYCODE_DPAD_LEFT:
                if (horizontalFading > 0) {
                  int left = child.getLeft();
                  if (left < horizontalFading) {
                    smoothScrollBy(left - horizontalFading, 0);
                  }
                }
                break;
              case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (horizontalFading > 0) {
                  int right = child.getRight();
                  int width = getWidth();
                  if (right > width - horizontalFading) {
                    smoothScrollBy(right - (width - horizontalFading), 0);
                  }
                }
                break;
            }
          });
    }
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // Track DPAD direction before super so requestChildFocus can use it.
    // Value is consumed and reset in requestChildFocus after use.
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      int kc = event.getKeyCode();
      if (kc == KeyEvent.KEYCODE_DPAD_UP
          || kc == KeyEvent.KEYCODE_DPAD_DOWN
          || kc == KeyEvent.KEYCODE_DPAD_LEFT
          || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
        mLastDpadKeyCode = kc;
      } else {
        mLastDpadKeyCode = 0;
      }
    }
    // Let child to dispatch first, then handle ours if child didn't do it.
    if (super.dispatchKeyEvent(event)) {
      return true;
    }

    LayoutManager layoutManager = getLayoutManager();
    // If there is no layout manager, then there is nothing to handle key events for.
    if (layoutManager == null) {
      return false;
    }

    if (event.getAction() != KeyEvent.ACTION_DOWN) {
      return false;
    }
    final int keyCode = event.getKeyCode();

    if (layoutManager.canScrollVertically()) {
      switch (keyCode) {
        case KeyEvent.KEYCODE_PAGE_DOWN:
        case KeyEvent.KEYCODE_PAGE_UP:
          int height = getMeasuredHeight();
          if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            smoothScrollBy(0, height, null, UNDEFINED_DURATION);
          } else {
            smoothScrollBy(0, -height, null, UNDEFINED_DURATION);
          }
          return true;
      }
    } else if (layoutManager.canScrollHorizontally()) {
      switch (keyCode) {
        case KeyEvent.KEYCODE_PAGE_DOWN:
        case KeyEvent.KEYCODE_PAGE_UP:
          int width = getMeasuredWidth();
          if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            smoothScrollBy(width, 0, null, UNDEFINED_DURATION);
          } else {
            smoothScrollBy(-width, 0, null, UNDEFINED_DURATION);
          }
          return true;
      }
    }

    final RecyclerView.Adapter adapter = getAdapter();
    if (adapter != null
        && (layoutManager.canScrollVertically() || layoutManager.canScrollHorizontally())) {
      final boolean isReversed = isLayoutReversed();
      final int itemCount = adapter.getItemCount();
      switch (keyCode) {
        case KeyEvent.KEYCODE_MOVE_HOME:
        case KeyEvent.KEYCODE_MOVE_END:
          final int targetOffset;
          if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
            targetOffset = isReversed ? itemCount : 0;
          } else {
            targetOffset = isReversed ? 0 : itemCount;
          }
          smoothScrollToPosition(targetOffset);
          return true;
      }
    }

    if (!hasNextFocusForKey(keyCode)
        && adapter != null
        && layoutManager instanceof LinearLayoutManager) {
      final LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
      final boolean isReversed = isLayoutReversed();
      final boolean shouldScrollVertically =
          linearLayoutManager.canScrollVertically()
              && (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP);
      final boolean shouldScrollHorizontally =
          linearLayoutManager.canScrollHorizontally()
              && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
      if (shouldScrollVertically || shouldScrollHorizontally) {
        final boolean scrollDownOrRight =
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;

        final int currentVisiblePosition;
        if (scrollDownOrRight) {
          currentVisiblePosition = linearLayoutManager.findLastVisibleItemPosition();
        } else {
          currentVisiblePosition = linearLayoutManager.findFirstVisibleItemPosition();
        }

        // Check if the current item is larger than the view
        if (currentVisiblePosition != RecyclerView.NO_POSITION) {
          final View currentView = linearLayoutManager.findViewByPosition(currentVisiblePosition);
          if (currentView != null) {
            final int viewSize = shouldScrollVertically ? getMeasuredHeight() : getMeasuredWidth();
            final int itemSize =
                shouldScrollVertically ? currentView.getHeight() : currentView.getWidth();

            if (itemSize > viewSize) {
              boolean reachedEndOfView = false;
              switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                  reachedEndOfView = currentView.getBottom() <= viewSize - getPaddingBottom();
                  break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                  reachedEndOfView = currentView.getRight() <= viewSize - getPaddingRight();
                  break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                  reachedEndOfView = currentView.getLeft() >= getPaddingLeft();
                  break;
                case KeyEvent.KEYCODE_DPAD_UP:
                  reachedEndOfView = currentView.getTop() >= getPaddingTop();
                  break;
              }
              // If the item content is larger than its view and hasn't yet scrolled all the way
              // through, scroll the item by the view height instead of jumping to the next item
              if (!reachedEndOfView) {
                final int fadingEdgeLength =
                    shouldScrollVertically
                        ? getVerticalFadingEdgeLength()
                        : getHorizontalFadingEdgeLength();

                final int scrollDistance =
                    scrollDownOrRight
                        ? viewSize - fadingEdgeLength
                        : -(viewSize - fadingEdgeLength);
                if (shouldScrollVertically) {
                  smoothScrollBy(0, scrollDistance, null, UNDEFINED_DURATION);
                } else {
                  smoothScrollBy(scrollDistance, 0, null, UNDEFINED_DURATION);
                }
                return true;
              }
            }
          }
        }

        // Default behavior: move to next/previous item
        final int targetPosition;
        final int direction;
        if (scrollDownOrRight) {
          direction = isReversed ? -1 : 1;
        } else {
          direction = isReversed ? 1 : -1;
        }
        targetPosition = currentVisiblePosition + direction;
        if (targetPosition >= 0 && targetPosition < adapter.getItemCount()) {
          smoothScrollToPosition(targetPosition);
          // Request focus on the target item after scrolling completes
          addOnScrollListener(
              new OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                  if (newState == SCROLL_STATE_IDLE) {
                    View targetView = linearLayoutManager.findViewByPosition(targetPosition);
                    if (targetView != null) {
                      targetView.requestFocus();
                    }
                    removeOnScrollListener(this);
                  }
                }
              });
          return true;
        } else {
          return false;
        }
      }
    }

    return false;
  }

  private boolean hasNextFocusForKey(int keyCode) {
    final int focusDirection;
    switch (keyCode) {
      case KeyEvent.KEYCODE_DPAD_UP:
        focusDirection = View.FOCUS_UP;
        break;
      case KeyEvent.KEYCODE_DPAD_DOWN:
        focusDirection = View.FOCUS_DOWN;
        break;
      case KeyEvent.KEYCODE_DPAD_LEFT:
        focusDirection = View.FOCUS_LEFT;
        break;
      case KeyEvent.KEYCODE_DPAD_RIGHT:
        focusDirection = View.FOCUS_RIGHT;
        break;
      default:
        return false;
    }

    View currentFocused = findFocus();
    if (currentFocused == this) {
      currentFocused = null;
    }
    View nextFocused =
        FocusFinder.getInstance().findNextFocus(this, currentFocused, focusDirection);
    return (nextFocused != null && nextFocused != this);
  }

  private boolean isLayoutReversed() {
    LayoutManager layoutManager = getLayoutManager();
    // If there is no layout manager, then there is nothing to handle key events for.
    if (layoutManager == null) {
      return false;
    }

    if (layoutManager instanceof LinearLayoutManager) {
      // covers both LinearLayoutManager and GridLayoutManager
      return ((LinearLayoutManager) layoutManager).getReverseLayout();
    } else if (layoutManager instanceof StaggeredGridLayoutManager) {
      return ((StaggeredGridLayoutManager) layoutManager).getReverseLayout();
    }

    return false;
  }
}
