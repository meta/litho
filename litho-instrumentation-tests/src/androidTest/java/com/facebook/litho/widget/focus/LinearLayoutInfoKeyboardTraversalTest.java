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

package com.facebook.litho.widget.focus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentHost;
import com.facebook.litho.ComponentTree;
import com.facebook.litho.LithoView;
import com.facebook.litho.sections.tti.TTIMarkerActivity;
import com.facebook.litho.testing.espresso.LithoActivityTestRule;
import com.facebook.litho.widget.GridLayoutInfo;
import com.facebook.litho.widget.LayoutInfo;
import com.facebook.litho.widget.LinearLayoutInfo;
import com.facebook.litho.widget.StaggeredGridLayoutInfo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Keyboard traversal through a Litho list backed by {@link LinearLayoutInfo}.
 *
 * <p>RecyclerView answers a focus search that runs off the edge by laying the adjacent item out
 * past the viewport and re-running FocusFinder over it. Litho gates mount on {@code
 * View.getLocalVisibleRect()}, so an item laid out off-screen stays an empty host: FocusFinder sees
 * no focusable, focus wraps, and the key press leaves the list. These tests drive one key press at
 * a time and assert on the sequence of adapter positions focus actually lands on.
 */
@RunWith(AndroidJUnit4.class)
public class LinearLayoutInfoKeyboardTraversalTest {

  private static final int ITEM = 300;
  private static final int VIEWPORT = 600;
  private static final int COUNT = 6;
  private static final int LAST = COUNT - 1;
  private static final long SETTLE_TIMEOUT_MS = 2_000;

  // Borrowed purely as a window host; every test replaces its content view.
  @Rule
  public LithoActivityTestRule<TTIMarkerActivity> mActivity =
      new LithoActivityTestRule<>(TTIMarkerActivity.class);

  // ---------------------------------------------------------------- D113330985: vertical TAB

  /**
   * The case D113330985 was written for: a vertical Litho list whose off-screen rows were
   * unreachable by TAB. Every row must be reachable, and at one press per row -- the workaround it
   * replaced pinned focus for a press, costing two.
   */
  @Test
  public void verticalTabReachesEveryRowOnePressEach() throws Throwable {
    final Result r = traverse(OrientationHelper.VERTICAL, ITEM, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
    assertEquals("one press per row", LAST, r.presses);
  }

  /** Shift+TAB back up the same list. */
  @Test
  public void verticalShiftTabReachesEveryRow() throws Throwable {
    final Result r = traverse(OrientationHelper.VERTICAL, ITEM, 1, false, View.FOCUS_BACKWARD);
    assertReachedEveryItem(r);
  }

  /**
   * Rows hosting more than one focusable must be traversed internally before the list advances;
   * D113330985 added a getFocusables() gate for this, which the native path handles on its own.
   */
  @Test
  public void verticalTabVisitsEveryFocusableWithinARow() throws Throwable {
    final Result r = traverse(OrientationHelper.VERTICAL, ITEM, 2, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
    assertEquals("two focusables per row means two presses per row", 2 * LAST, r.presses);
  }

  /** D113330985 also handled reverseLayout; traversal must not stall. */
  @Test
  public void verticalReverseLayoutTabReachesEveryRow() throws Throwable {
    final Result r = traverse(OrientationHelper.VERTICAL, ITEM, 1, true, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
  }

  // --------------------------------------------------------------- D119588322: horizontal TAB

  /** The case D119588322 reported: TAB left a horizontal carousel after the visible cards. */
  @Test
  public void horizontalTabReachesEveryCardOnePressEach() throws Throwable {
    final Result r = traverse(OrientationHelper.HORIZONTAL, ITEM, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
    assertEquals("one press per card", LAST, r.presses);
  }

  @Test
  public void horizontalShiftTabReachesEveryCard() throws Throwable {
    final Result r = traverse(OrientationHelper.HORIZONTAL, ITEM, 1, false, View.FOCUS_BACKWARD);
    assertReachedEveryItem(r);
  }

  /**
   * Full-bleed cards are the case a scroll-based fix cannot serve: revealing the next card
   * necessarily evicts the focused one, which detaches the view holding focus.
   */
  @Test
  public void horizontalFullBleedTabKeepsFocusAndReachesEveryCard() throws Throwable {
    final Result r = traverse(OrientationHelper.HORIZONTAL, VIEWPORT, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
  }

  @Test
  public void verticalFullBleedTabKeepsFocusAndReachesEveryRow() throws Throwable {
    final Result r = traverse(OrientationHelper.VERTICAL, VIEWPORT, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
  }

  // --------------------------------------------------- the same defect in the other LayoutInfos

  /** GridLayoutInfo has the same off-screen-unmounted problem; it is not Linear-specific. */
  @Test
  public void gridVerticalTabReachesEveryRow() throws Throwable {
    final Result r =
        traverse(Kind.GRID, OrientationHelper.VERTICAL, ITEM, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
  }

  @Test
  public void gridHorizontalTabReachesEveryCard() throws Throwable {
    final Result r =
        traverse(Kind.GRID, OrientationHelper.HORIZONTAL, ITEM, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
  }

  /** StaggeredGridLayoutManager is not a LinearLayoutManager, so it needs the fix too. */
  @Test
  public void staggeredVerticalTabReachesEveryRow() throws Throwable {
    final Result r =
        traverse(Kind.STAGGERED, OrientationHelper.VERTICAL, ITEM, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
  }

  @Test
  public void staggeredHorizontalTabReachesEveryCard() throws Throwable {
    final Result r =
        traverse(Kind.STAGGERED, OrientationHelper.HORIZONTAL, ITEM, 1, false, View.FOCUS_FORWARD);
    assertReachedEveryItem(r);
  }

  // ------------------------------------------------------------------------- arrow keys

  /** DPAD traversal already worked via a different RecyclerView path; it must keep working. */
  @Test
  public void verticalArrowDownReachesEveryRow() throws Throwable {
    final Result r = traverse(OrientationHelper.VERTICAL, ITEM, 1, false, View.FOCUS_DOWN);
    assertReachedEveryItem(r);
  }

  @Test
  public void horizontalArrowRightReachesEveryCard() throws Throwable {
    final Result r = traverse(OrientationHelper.HORIZONTAL, ITEM, 1, false, View.FOCUS_RIGHT);
    assertReachedEveryItem(r);
  }

  // ------------------------------------------------ a11y virtual views competing for KEYCODE_TAB

  /**
   * `ComponentHost.dispatchKeyEvent` hands keys to `ExploreByTouchHelper` when the host exposes
   * accessibility virtual views, and that helper claims `KEYCODE_TAB` to walk between them. Litho
   * `Text` exposes one virtual view per ClickableSpan when `accessibleClickableSpans` is set, which
   * ads surfaces do (`AdCaptionComponent`, `AdHeaderTitleComponent`). That could in principle
   * swallow TAB before any focus search runs, bypassing the mount fix entirely.
   *
   * <p>It cannot trap focus: `ExploreByTouchHelper.moveFocus` searches with `wrap = false`, so once
   * the spans are exhausted it returns false and the key falls through to the focus search. The
   * links cost one extra press each, which is the correct behaviour for a link.
   *
   * <p>The helper claims TAB only if the host can take focus. The host is focusable only when view
   * flattening merges it with the focusable `Column`, which the coverage build does not do, so the
   * test makes it focusable.
   */
  @Test
  public void virtualViewsClaimTabButAlwaysReleaseIt() throws Throwable {
    final Activity activity = mActivity.getActivity();
    final LithoView[] holder = new LithoView[1];
    runOnMain(
        () -> {
          final LithoView lithoView = new LithoView(activity);
          final ComponentContext c = new ComponentContext(activity);
          lithoView.setComponentTree(ComponentTree.create(c, new SpanTextCard(ITEM)).build());
          activity.setContentView(lithoView);
          holder[0] = lithoView;
        });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    Thread.sleep(800);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();

    final List<Boolean> claims = new ArrayList<>();
    runOnMain(
        () -> {
          final ComponentHost host = findHostWithVirtualViews(holder[0]);
          assertNotNull("no host exposed virtual views; the risk path is not set up", host);
          assertTrue(
              "the delegation gate needs a single accessible item",
              host.getHasOnlyOneAccessibleItem());
          host.setFocusableInTouchMode(true);
          assertTrue("the host must hold focus for its helper to claim TAB", host.requestFocus());
          for (int i = 0; i < 6; i++) {
            claims.add(
                host.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)));
          }
        });

    // Non-vacuous: the helper really is claiming TAB here.
    assertTrue("expected at least one claimed TAB, got " + claims, claims.contains(true));
    // And it always lets go, so traversal can continue into the focus search.
    for (int i = 1; i < claims.size(); i++) {
      assertFalse(
          "TAB was claimed twice in a row, which would trap focus: " + claims,
          claims.get(i) && claims.get(i - 1));
    }
  }

  private static ComponentHost findHostWithVirtualViews(View view) {
    if (view instanceof ComponentHost && ((ComponentHost) view).implementsVirtualViews()) {
      return (ComponentHost) view;
    }
    if (view instanceof ViewGroup) {
      final ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        final ComponentHost found = findHostWithVirtualViews(group.getChildAt(i));
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  // ------------------------------------------------------- visibility events / ad impressions

  /**
   * Mounting an off-screen item for a focus search must not make Litho think it was seen. An
   * impression fired for a card the user never looked at is a correctness bug on an ads surface,
   * and a swallowed one is worse. The visible event must fire once, when the card really appears.
   */
  @Test
  public void forceMountForFocusSearchDoesNotDisturbVisibleEvents() throws Throwable {
    final Activity activity = mActivity.getActivity();
    final int[] visibleCounts = new int[COUNT];
    final RecyclerView[] holder = new RecyclerView[1];

    runOnMain(
        () -> {
          final RecyclerView recyclerView = new RecyclerView(activity);
          recyclerView.setLayoutManager(
              layoutInfo(activity, Kind.LINEAR, OrientationHelper.HORIZONTAL, false));
          recyclerView.setAdapter(new ImpressionAdapter(visibleCounts));
          recyclerView.setLayoutParams(new ViewGroup.LayoutParams(VIEWPORT, ITEM));
          activity.setContentView(recyclerView);
          holder[0] = recyclerView;
        });
    settle(holder[0]);

    final RecyclerView recyclerView = holder[0];
    final LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
    final int[] target = new int[1];

    runOnMain(
        () -> {
          final int lastVisible = lm.findLastVisibleItemPosition();
          target[0] = lastVisible + 1;
          assertTrue("need an item past the viewport", target[0] < COUNT);
          assertEquals(
              "off-screen card must not have an impression yet", 0, visibleCounts[target[0]]);

          // focusSearch() alone runs the focus-failure layout and our force-mount, but moves no
          // focus, so nothing scrolls -- the card stays off-screen while mounted.
          final View focused = focusableIn(lm.findViewByPosition(lastVisible), false);
          assertNotNull(focused);
          recyclerView.focusSearch(focused, View.FOCUS_FORWARD);
        });
    settle(recyclerView);

    runOnMain(
        () -> {
          final View card = lm.findViewByPosition(target[0]);
          assertNotNull("focus search should have laid the next card out", card);
          assertTrue("and our fix should have mounted it", card.hasFocusable());
          assertEquals(
              "mounting for a focus search must not fire an impression",
              0,
              visibleCounts[target[0]]);
        });
    settle(recyclerView);

    runOnMain(() -> lm.scrollToPosition(target[0]));
    settle(recyclerView);

    runOnMain(
        () ->
            assertEquals(
                "the impression must still fire, exactly once, when the card is really shown",
                1,
                visibleCounts[target[0]]));
  }

  private static class ImpressionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final int[] mCounts;

    ImpressionAdapter(int[] counts) {
      mCounts = counts;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
      final LithoView lithoView = new LithoView(parent.getContext());
      lithoView.setLayoutParams(new RecyclerView.LayoutParams(ITEM, ITEM));
      return new RecyclerView.ViewHolder(lithoView) {};
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
      final LithoView lithoView = (LithoView) holder.itemView;
      final ComponentContext c = new ComponentContext(lithoView.getContext());
      final int index = position;
      lithoView.setComponentTree(
          ComponentTree.create(c, new ImpressionCard(ITEM, () -> mCounts[index]++)).build());
    }

    @Override
    public int getItemCount() {
      return COUNT;
    }
  }

  private static void assertReachedEveryItem(Result r) {
    assertFalse(
        "focus was lost mid-traversal after "
            + r.presses
            + " presses; visited "
            + r.visited
            + " (last seen "
            + r.lastState
            + ")",
        r.focusLost);
    for (int position = 0; position < COUNT; position++) {
      assertTrue(
          "never reached adapter position " + position + "; visited " + r.visited,
          r.visited.contains(position));
    }
  }

  private static class Result {
    final Set<Integer> visited = new LinkedHashSet<>();
    int presses;
    boolean focusLost;
    String lastState = "";
  }

  private static class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final int mFocusables;
    private final int mSize;

    Adapter(int focusables, int size) {
      mFocusables = focusables;
      mSize = size;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
      final LithoView lithoView = new LithoView(parent.getContext());
      lithoView.setLayoutParams(new RecyclerView.LayoutParams(mSize, mSize));
      return new RecyclerView.ViewHolder(lithoView) {};
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
      final LithoView lithoView = (LithoView) holder.itemView;
      final ComponentContext c = new ComponentContext(lithoView.getContext());
      lithoView.setComponentTree(
          ComponentTree.create(c, new TraversalCard(mFocusables, mSize)).build());
    }

    @Override
    public int getItemCount() {
      return COUNT;
    }
  }

  /**
   * Presses one key at a time until every adapter position has been visited or the traversal
   * stalls, mirroring what ViewRootImpl does with an unhandled navigation key.
   */
  /** Which Litho LayoutInfo backs the list under test. */
  private enum Kind {
    LINEAR,
    GRID,
    STAGGERED
  }

  private static RecyclerView.LayoutManager layoutInfo(
      Activity activity, Kind kind, int orientation, boolean reverseLayout) {
    final LayoutInfo info;
    switch (kind) {
      case GRID:
        info = new GridLayoutInfo(activity, 1, orientation, reverseLayout);
        break;
      case STAGGERED:
        info = new StaggeredGridLayoutInfo(1, orientation, reverseLayout, 0);
        break;
      default:
        info = new LinearLayoutInfo(activity, orientation, reverseLayout);
    }
    return info.getLayoutManager();
  }

  private Result traverse(
      int orientation, int itemSize, int focusablesPerItem, boolean reverseLayout, int direction)
      throws Throwable {
    return traverse(
        Kind.LINEAR, orientation, itemSize, focusablesPerItem, reverseLayout, direction);
  }

  private Result traverse(
      Kind kind,
      int orientation,
      int itemSize,
      int focusablesPerItem,
      boolean reverseLayout,
      int direction)
      throws Throwable {
    final Activity activity = mActivity.getActivity();
    final RecyclerView[] holder = new RecyclerView[1];
    final boolean backward =
        direction == View.FOCUS_BACKWARD
            || direction == View.FOCUS_UP
            || direction == View.FOCUS_LEFT;
    // reverseLayout puts adapter position 0 at the visual end, so the visual start flips with it.
    final boolean startAtHighestPosition = backward != reverseLayout;

    runOnMain(
        () -> {
          final RecyclerView recyclerView = new RecyclerView(activity);
          recyclerView.setLayoutManager(layoutInfo(activity, kind, orientation, reverseLayout));
          recyclerView.setAdapter(new Adapter(focusablesPerItem, itemSize));
          recyclerView.setLayoutParams(
              orientation == OrientationHelper.VERTICAL
                  ? new ViewGroup.LayoutParams(itemSize, VIEWPORT)
                  : new ViewGroup.LayoutParams(VIEWPORT, itemSize));
          activity.setContentView(recyclerView);
          holder[0] = recyclerView;
        });
    settle(holder[0]);

    final RecyclerView recyclerView = holder[0];
    final RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
    final int startPosition = startAtHighestPosition ? LAST : 0;

    // Backward traversal starts from the far end, which has to be scrolled into view first.
    if (startAtHighestPosition) {
      runOnMain(() -> layoutManager.scrollToPosition(startPosition));
      settle(recyclerView);
    }

    final Result result = new Result();
    final View[] current = new View[1];
    runOnMain(
        () -> {
          final View start =
              focusableIn(layoutManager.findViewByPosition(startPosition), startAtHighestPosition);
          assertNotNull("start item mounted no focusable", start);
          assertTrue(start.requestFocus());
          final View owner = recyclerView.findFocus();
          current[0] = owner != null ? owner : start;
          record(result, recyclerView, layoutManager, current[0]);
        });
    settle(recyclerView);

    final int maxPresses = COUNT * focusablesPerItem * 3;
    for (int press = 0; press < maxPresses && result.visited.size() < COUNT; press++) {
      runOnMain(
          () -> {
            final View from = current[0];
            if (from == null) {
              return;
            }
            final View next = from.focusSearch(direction);
            if (next != null && next != from) {
              next.requestFocus();
            }
            // requestFocus() on an item view delegates down to a focusable descendant, so ask the
            // list who actually owns focus now rather than assuming it is the view we were handed.
            final View owner = recyclerView.findFocus();
            if (owner != null) {
              current[0] = owner;
            }
            result.presses++;
            record(result, recyclerView, layoutManager, owner);
          });
      settle(recyclerView);
      if (result.focusLost) {
        break;
      }
    }
    return result;
  }

  private static void record(
      Result result, RecyclerView recyclerView, RecyclerView.LayoutManager lm, View focused) {
    if (focused == null) {
      // Nothing inside the list holds focus any more: the traversal dead-ended or the view that
      // held focus was detached by a scroll.
      result.lastState = "no focus owner inside the list";
      result.focusLost = true;
      return;
    }
    final View item = recyclerView.findContainingItemView(focused);
    if (item != null) {
      result.visited.add(lm.getPosition(item));
    }
  }

  @Nullable
  private static View focusableIn(@Nullable View itemView, boolean last) {
    if (itemView == null) {
      return null;
    }
    final List<View> out = new ArrayList<>();
    itemView.addFocusables((ArrayList<View>) out, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
    if (out.isEmpty()) {
      return null;
    }
    return last ? out.get(out.size() - 1) : out.get(0);
  }

  private static void runOnMain(Runnable runnable) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
  }

  /**
   * Waits for layout and scrolling to quiesce rather than sleeping a fixed amount -- a fixed sleep
   * is both slower than it needs to be and still racy on a loaded emulator.
   */
  private static void settle(RecyclerView recyclerView) {
    final long deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS;
    do {
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      final boolean[] busy = new boolean[1];
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () ->
                  busy[0] =
                      recyclerView.isLayoutRequested()
                          || recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE
                          || recyclerView.hasPendingAdapterUpdates());
      if (!busy[0]) {
        return;
      }
    } while (SystemClock.uptimeMillis() < deadline);
  }
}
