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

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import androidx.recyclerview.widget.RecyclerView;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ThreadUtils;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RecyclerEventsControllerTest {

  private TestSectionsRecyclerView mSectionsRecyclerView;
  private RecyclerView mRecyclerView;
  private RecyclerEventsController mRecyclerEventsController;
  private RecyclerEventsController.OnRecyclerUpdateListener mOnRecyclerUpdateListener;
  private ComponentContext mComponentContext;

  @Before
  public void setup() {
    mComponentContext = new ComponentContext(getApplicationContext());
    mRecyclerView = new RecyclerView(mComponentContext.getAndroidContext());
    mSectionsRecyclerView =
        new TestSectionsRecyclerView(mComponentContext.getAndroidContext(), mRecyclerView);
    mOnRecyclerUpdateListener = mock(RecyclerEventsController.OnRecyclerUpdateListener.class);
    mRecyclerEventsController = new RecyclerEventsController();
    mRecyclerEventsController.setSectionsRecyclerView(mSectionsRecyclerView);
  }

  @After
  public void teardown() {
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED);
  }

  /**
   * Used for setting up initial value required for tests and then resetting the fields of {@code
   * mSectionsRecyclerView}.
   *
   * <p>This is useful when we want to mock certain behavior.
   *
   * <p>Example : when(mSectionsRecyclerView.isRefreshing()).thenReturn(true);
   */
  private void resetValuesAndSetInitialRefreshing(boolean isRefreshing) {
    mSectionsRecyclerView.setRefreshing(isRefreshing);
    mSectionsRecyclerView.reset();
  }

  @Test
  public void testOnRecyclerListener() {
    verify(mOnRecyclerUpdateListener, never()).onUpdate((RecyclerView) any());

    mRecyclerEventsController.setOnRecyclerUpdateListener(mOnRecyclerUpdateListener);
    mRecyclerEventsController.setSectionsRecyclerView(null);

    mRecyclerEventsController.setSectionsRecyclerView(mSectionsRecyclerView);

    verify(mOnRecyclerUpdateListener, times(1)).onUpdate(null);
    verify(mOnRecyclerUpdateListener, times(1)).onUpdate(mRecyclerView);
  }

  @Test
  public void testClearRefreshingOnNotRefreshingView() {
    resetValuesAndSetInitialRefreshing(false);
    mRecyclerEventsController.clearRefreshing();

    verifySetRefreshingWasCalledNTimesWith(mSectionsRecyclerView, 0);
    verifyRemoveCallbacksWasCalledNTimes(mSectionsRecyclerView, 0);
    verifyPostWasCalledNTimes(mSectionsRecyclerView, 0);
  }

  @Test
  public void testClearRefreshingFromUIThread() {
    resetValuesAndSetInitialRefreshing(true);
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE);
    mRecyclerEventsController.clearRefreshing();

    verifySetRefreshingWasCalledNTimesWith(mSectionsRecyclerView, 1);
    assertThat(mSectionsRecyclerView.isRefreshing()).isFalse();
    verifyRemoveCallbacksWasCalledNTimes(mSectionsRecyclerView, 0);
    verifyPostWasCalledNTimes(mSectionsRecyclerView, 0);
  }

  @Test
  public void testClearRefreshingFromNonUIThread() {

    resetValuesAndSetInitialRefreshing(true);
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_FALSE);

    mRecyclerEventsController.clearRefreshing();
    verifyRemoveCallbacksWasCalledNTimes(mSectionsRecyclerView, 1);
    verifyPostWasCalledNTimes(mSectionsRecyclerView, 1);
  }

  @Test
  public void testShowRefreshingFromUIThread() {
    resetValuesAndSetInitialRefreshing(false);
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_TRUE);

    mRecyclerEventsController.showRefreshing();
    verifySetRefreshingWasCalledNTimesWith(mSectionsRecyclerView, 1);
    assertThat(mSectionsRecyclerView.isRefreshing()).isTrue();
  }

  @Test
  public void testShowRefreshingAlreadyRefreshing() {
    resetValuesAndSetInitialRefreshing(true);
    mRecyclerEventsController.showRefreshing();

    verifySetRefreshingWasCalledNTimesWith(mSectionsRecyclerView, 0);
  }

  private static void verifyRemoveCallbacksWasCalledNTimes(
      TestSectionsRecyclerView recyclerView, int times) {
    assertThat(recyclerView.getRemoveCallbackCount()).isEqualTo(times);
  }

  private static void verifyPostWasCalledNTimes(TestSectionsRecyclerView recyclerView, int times) {
    assertThat(recyclerView.getPostCount()).isEqualTo(times);
  }

  private static void verifySetRefreshingWasCalledNTimesWith(
      TestSectionsRecyclerView recyclerView, int times) {
    assertThat(recyclerView.getSetRefreshingValuesCount()).isEqualTo(times);
  }
}
