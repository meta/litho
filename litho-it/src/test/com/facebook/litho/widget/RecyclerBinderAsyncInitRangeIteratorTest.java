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
import static com.facebook.litho.widget.RecyclerBinder.findInitialComponentPosition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.LithoVisibilityEventsController;
import com.facebook.litho.Size;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.litho.viewcompat.SimpleViewBinder;
import com.facebook.litho.viewcompat.ViewCreator;
import com.facebook.rendercore.RunnableHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

/** Tests for {@link RecyclerBinder.ComponentAsyncInitRangeIterator} */
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RecyclerBinderAsyncInitRangeIteratorTest {

  private static final float RANGE_RATIO = 2.0f;

  private static final ViewCreator VIEW_CREATOR =
      new ViewCreator() {
        @Override
        public View createView(Context c, ViewGroup parent) {
          return mock(View.class);
        }
      };

  private RecyclerBinder mRecyclerBinder;
  private RecyclerBinder.Builder mRecyclerBinderBuilder;
  private ComponentContext mComponentContext;
  private LayoutInfo mLayoutInfo;

  private final List<ComponentTreeHolder> mAllHoldersList = new ArrayList<>();

  @Before
  public void setup() throws Exception {
    mComponentContext = new ComponentContext(getApplicationContext());

    final RecyclerBinder.ComponentTreeHolderFactory componentTreeHolderFactory =
        new RecyclerBinder.ComponentTreeHolderFactory() {
          @Override
          public ComponentTreeHolder create(
              RenderInfo renderInfo,
              RunnableHandler layoutHandler,
              ComponentTreeHolder.ComponentTreeMeasureListenerFactory
                  componentTreeMeasureListenerFactory,
              ComponentsConfiguration componentsConfiguration,
              @Nullable LithoVisibilityEventsController lifecycleProvider) {
            final TestComponentTreeHolder holder = new TestComponentTreeHolder(renderInfo);
            mAllHoldersList.add(holder);

            return holder;
          }
        };
    mLayoutInfo = mock(LayoutInfo.class);
    mRecyclerBinderBuilder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().rangeRatio(RANGE_RATIO).build())
            .layoutInfo(mLayoutInfo)
            .componentTreeHolderFactory(componentTreeHolderFactory);
    mRecyclerBinder = mRecyclerBinderBuilder.build(mComponentContext);
  }

  @Test
  public void testComponentAsyncInitRangeIteratorForward() {
    int totalCount = 10;
    final RenderInfo[] components = new RenderInfo[totalCount];
    for (int i = 0; i < totalCount; i++) {
      components[i] = ComponentRenderInfo.create().component(mock(Component.class)).build();
    }

    mRecyclerBinder.insertRangeAt(0, new ArrayList<>(Arrays.asList(components)));

    final int initialComponentPosition =
        findInitialComponentPosition(mAllHoldersList, mRecyclerBinder.traverseLayoutBackwards);
    assertThat(initialComponentPosition).isEqualTo(0);

    final Iterator<ComponentTreeHolder> asyncRangeIterator =
        new RecyclerBinder.ComponentAsyncInitRangeIterator(
            mAllHoldersList, initialComponentPosition, 4, mRecyclerBinder.traverseLayoutBackwards);
    assertIterator(
        asyncRangeIterator,
        new LinkedList<>(
            Arrays.asList(components[1], components[2], components[3], components[4])));
  }

  @Test
  public void testComponentAsyncInitRangeIteratorForwardSkipViews() {
    int totalCount = 10;
    final RenderInfo[] components = new RenderInfo[totalCount];
    for (int i = 0; i < totalCount; i++) {
      if (i == 0 || i == 2) {
        components[i] =
            ViewRenderInfo.create()
                .viewCreator(VIEW_CREATOR)
                .viewBinder(new SimpleViewBinder())
                .build();
      } else {
        components[i] = ComponentRenderInfo.create().component(mock(Component.class)).build();
      }
    }

    mRecyclerBinder.insertRangeAt(0, new ArrayList<>(Arrays.asList(components)));

    final int initialComponentPosition =
        findInitialComponentPosition(mAllHoldersList, mRecyclerBinder.traverseLayoutBackwards);
    assertThat(initialComponentPosition).isEqualTo(1);

    final Iterator<ComponentTreeHolder> asyncRangeIterator =
        new RecyclerBinder.ComponentAsyncInitRangeIterator(
            mAllHoldersList, initialComponentPosition, 4, mRecyclerBinder.traverseLayoutBackwards);
    assertIterator(
        asyncRangeIterator,
        new LinkedList<>(
            Arrays.asList(components[3], components[4], components[5], components[6])));
  }

  @Test
  public void testComponentAsyncInitRangeIteratorBackward() {
    final RecyclerBinder binder = getStackedEndRecyclerBinder();

    int totalCount = 10;
    final RenderInfo[] components = new RenderInfo[totalCount];
    for (int i = 0; i < totalCount; i++) {
      components[i] = ComponentRenderInfo.create().component(mock(Component.class)).build();
    }
    binder.insertRangeAt(0, new ArrayList<>(Arrays.asList(components)));

    final int initialComponentPosition =
        findInitialComponentPosition(mAllHoldersList, binder.traverseLayoutBackwards);
    assertThat(initialComponentPosition).isEqualTo(9);

    final Iterator<ComponentTreeHolder> asyncRangeIterator =
        new RecyclerBinder.ComponentAsyncInitRangeIterator(
            mAllHoldersList, initialComponentPosition, 4, binder.traverseLayoutBackwards);
    assertIterator(
        asyncRangeIterator,
        new LinkedList<>(
            Arrays.asList(components[8], components[7], components[6], components[5])));
  }

  @Test
  public void testComponentAsyncInitRangeIteratorBackwardSkipValidTrees() {
    final RecyclerBinder binder = getStackedEndRecyclerBinder();

    int totalCount = 10;
    final RenderInfo[] components = new RenderInfo[totalCount];
    for (int i = 0; i < totalCount; i++) {
      components[i] = ComponentRenderInfo.create().component(mock(Component.class)).build();
    }
    binder.insertRangeAt(0, new ArrayList<>(Arrays.asList(components)));
    mAllHoldersList.get(9).computeLayoutSync(mComponentContext, 0, 0, new Size());
    mAllHoldersList.get(8).computeLayoutSync(mComponentContext, 0, 0, new Size());

    final int initialComponentPosition =
        findInitialComponentPosition(mAllHoldersList, binder.traverseLayoutBackwards);
    assertThat(initialComponentPosition).isEqualTo(9);

    final Iterator<ComponentTreeHolder> asyncRangeIterator =
        new RecyclerBinder.ComponentAsyncInitRangeIterator(
            mAllHoldersList, initialComponentPosition, 4, binder.traverseLayoutBackwards);
    assertIterator(
        asyncRangeIterator,
        new LinkedList<>(
            Arrays.asList(components[7], components[6], components[5], components[4])));
  }

  @Test
  public void testComponentAsyncInitRangeIteratorForwardFewerItems() {
    int totalCount = 10;
    final RenderInfo[] components = new RenderInfo[totalCount];
    for (int i = 0; i < totalCount; i++) {
      if (i == 4 || i == 6 || i == 9) {
        components[i] = ComponentRenderInfo.create().component(mock(Component.class)).build();
      } else {
        components[i] =
            ViewRenderInfo.create()
                .viewCreator(VIEW_CREATOR)
                .viewBinder(new SimpleViewBinder())
                .build();
      }
    }

    mRecyclerBinder.insertRangeAt(0, new ArrayList<>(Arrays.asList(components)));

    mAllHoldersList.get(6).computeLayoutSync(mComponentContext, 0, 0, new Size());

    final int initialComponentPosition =
        findInitialComponentPosition(mAllHoldersList, mRecyclerBinder.traverseLayoutBackwards);
    assertThat(initialComponentPosition).isEqualTo(4);

    final Iterator<ComponentTreeHolder> asyncRangeIterator =
        new RecyclerBinder.ComponentAsyncInitRangeIterator(
            mAllHoldersList, initialComponentPosition, 4, mRecyclerBinder.traverseLayoutBackwards);
    assertIterator(asyncRangeIterator, new LinkedList<>(Arrays.asList(components[9])));
  }

  private RecyclerBinder getStackedEndRecyclerBinder() {
    final LinearLayoutManager layoutManager =
        new LinearLayoutManager(
            mComponentContext.getAndroidContext(), OrientationHelper.VERTICAL, false);
    layoutManager.setStackFromEnd(true);
    when(mLayoutInfo.getLayoutManager()).thenReturn(layoutManager);
    return mRecyclerBinderBuilder.build(mComponentContext);
  }

  private void assertIterator(
      Iterator<ComponentTreeHolder> asyncRangeIterator, LinkedList<RenderInfo> targetItems) {
    while (asyncRangeIterator.hasNext()) {
      Assert.assertEquals(asyncRangeIterator.next().getRenderInfo(), targetItems.removeFirst());
    }
    assertThat(targetItems.isEmpty()).isTrue();
  }
}
