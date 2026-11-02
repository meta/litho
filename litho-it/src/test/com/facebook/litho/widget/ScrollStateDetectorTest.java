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

package com.facebook.litho.widget;

import static org.assertj.core.api.Assertions.assertThat;

import android.view.MotionEvent;
import android.view.View;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

/** Tests for {@link ScrollStateDetector} */
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ScrollStateDetectorTest {

  private ScrollStateDetector detector;
  private TestScrollStateListener scrollStateListener;

  @Before
  public void setup() {
    View hostView = new View(RuntimeEnvironment.application.getApplicationContext());
    scrollStateListener = new TestScrollStateListener();
    detector = new ScrollStateDetector(hostView);
    detector.setListener(scrollStateListener);
  }

  // For scroll driven by non-fling gesture, the first onScrollChange callback triggers start event
  // and the action_up event indicates scroll's stopping.
  @Test
  public void testSlowScroll() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_UP));
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testSlowScrollWithCancel() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_CANCEL));
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testSlowScrollWithDownMoveAndCancel() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_MOVE));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_CANCEL));
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testSlowScrollWithRandomOnDraw() {
    detector.onDraw();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onDraw();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onDraw();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_UP));
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testSlowScrollWithRandomOnDrawAndCancel() {
    detector.onDraw();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onDraw();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onDraw();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_CANCEL));
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testSlowScrollWithMoveRandomOnDrawAndCancel() {
    detector.onDraw();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_MOVE));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onDraw();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onDraw();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_CANCEL));
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  // For scroll driven by fling gesture, the first onScrollChange callback triggers start event
  // and then detector checks if there's an onScrollChanged() callback between onDraw() to determine
  // if scroll stops.
  @Test
  public void testFling() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.fling();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_UP));
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    detector.onDraw();
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testFlingWithCancel() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.fling();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_CANCEL));
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    detector.onDraw();
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testFlingWithMoveAndCancel() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_MOVE));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.fling();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_CANCEL));
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    detector.onDraw();
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  @Test
  public void testFlingWithDownMoveAndCancel() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_MOVE));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.fling();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_CANCEL));
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    detector.onDraw();
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  // After the 1st fling, user fling the list again before its stopping scroll.
  @Test
  public void testDoubleFling() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.fling();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_UP));
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();

    // second fling
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onDraw();
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    detector.onScrollChanged();
    detector.fling();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_UP));
    detector.onDraw();
    detector.onScrollChanged();
    detector.onDraw();
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    detector.onDraw();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  // After the 1st fling, user pauses the the scrolling by touch the list and then release.
  @Test
  public void testFlingHoldAndRelease() {
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.onScrollChanged();
    detector.fling();
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_UP));
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onScrollChanged();

    // touch the list hold on the scrolling.
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_DOWN));
    detector.onDraw();
    detector.onScrollChanged();
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(0);
    detector.onDraw();
    detector.onScrollChanged();

    // release the finger.
    detector.onTouchEvent(createFakeEvent(MotionEvent.ACTION_UP));
    assertThat(scrollStateListener.startCount).isEqualTo(1);
    assertThat(scrollStateListener.stopCount).isEqualTo(1);
  }

  private static MotionEvent createFakeEvent(int action) {
    return MotionEvent.obtain(0L, 0L, action, 0.0f, 0.0f, 0);
  }

  private static class TestScrollStateListener implements ScrollStateListener {
    private int startCount;
    private int stopCount;

    @Override
    public void onScrollStateChanged(View v, int scrollStatus) {
      if (scrollStatus == ScrollStateListener.SCROLL_STARTED) {
        startCount++;
      } else if (scrollStatus == ScrollStateListener.SCROLL_STOPPED) {
        stopCount++;
      }
    }
  }
}
