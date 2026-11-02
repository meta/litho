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
import static com.facebook.litho.widget.ComponentRenderInfo.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import android.view.ViewGroup;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

/** Sub adapter mode tests for {@link RecyclerBinder} */
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RecyclerBinderSubAdapterTest {

  private ComponentContext mComponentContext;

  @Before
  public void setup() {
    mComponentContext = new ComponentContext(getApplicationContext());
    mComponentContext.getAndroidContext().setTheme(0);
  }

  @Test(expected = RuntimeException.class)
  public void testSubAdapterDoesNotAllowMount() {
    final RecyclerBinder recyclerBinder =
        new RecyclerBinder.Builder().isSubAdapter(true).build(mComponentContext);

    recyclerBinder.mount(new RecyclerView(mComponentContext.getAndroidContext()));
  }

  @Test(expected = RuntimeException.class)
  public void testSubAdapterDoesNotAllowUnmount() {
    final RecyclerBinder recyclerBinder =
        new RecyclerBinder.Builder().isSubAdapter(true).build(mComponentContext);

    recyclerBinder.unmount(new RecyclerView(mComponentContext.getAndroidContext()));
  }

  @Test(expected = RuntimeException.class)
  public void testNonSubAdapterDoesNotAllowUpdateSubAdapterVisibleRange() {
    final RecyclerBinder recyclerBinder = new RecyclerBinder.Builder().build(mComponentContext);

    recyclerBinder.updateSubAdapterVisibleRange(0, 1);
  }

  @Test
  public void testUpdateSubAdapterVisibleRange() {
    final RecyclerBinder recyclerBinder =
        new RecyclerBinder.Builder().isSubAdapter(true).build(mComponentContext);

    final List<ComponentRenderInfo> components1 = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      components1.add(create().component(mock(Component.class)).build());
      recyclerBinder.insertItemAt(i, components1.get(i));
    }
    recyclerBinder.notifyChangeSetComplete(
        true, RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK);

    recyclerBinder.updateSubAdapterVisibleRange(0, 10);
    assertThat(recyclerBinder.currentFirstVisiblePosition).isEqualTo(0);
    assertThat(recyclerBinder.currentLastVisiblePosition).isEqualTo(10);
  }

  @Test
  public void testSubAdapterInsertAndRemove() {
    final RecyclerBinder recyclerBinder1 =
        new RecyclerBinder.Builder().isSubAdapter(true).build(mComponentContext);
    final RecyclerBinder recyclerBinder2 =
        new RecyclerBinder.Builder().isSubAdapter(true).build(mComponentContext);
    final TwoAdapterMultiplexingAdapter multiplexingAdapter =
        new TwoAdapterMultiplexingAdapter(
            recyclerBinder1.getInternalAdapter(), recyclerBinder2.getInternalAdapter());

    final List<ComponentRenderInfo> components1 = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      components1.add(create().component(mock(Component.class)).build());
      recyclerBinder1.insertItemAt(i, components1.get(i));
    }
    recyclerBinder1.notifyChangeSetComplete(
        true, RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK);

    final List<ComponentRenderInfo> components2 = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      components2.add(create().component(mock(Component.class)).build());
      recyclerBinder2.insertItemAt(i, components2.get(i));
    }
    recyclerBinder2.notifyChangeSetComplete(
        true, RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK);

    assertThat(multiplexingAdapter.getItemCount()).isEqualTo(150);
  }

  private static class TwoAdapterMultiplexingAdapter extends RecyclerView.Adapter {

    private final RecyclerView.Adapter mFirst;
    private final RecyclerView.Adapter mSecond;

    public TwoAdapterMultiplexingAdapter(RecyclerView.Adapter first, RecyclerView.Adapter second) {
      mFirst = first;
      mSecond = second;

      mFirst.registerAdapterDataObserver(new AdapterObserver(mFirst));
      mSecond.registerAdapterDataObserver(new AdapterObserver(mSecond));
    }

    private RecyclerView.Adapter getAdapter(int position) {
      return position < mFirst.getItemCount() ? mFirst : mSecond;
    }

    private int correctPosition(int position) {
      return position < mFirst.getItemCount() ? position : position - mFirst.getItemCount();
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position, List payloads) {
      getAdapter(position).onBindViewHolder(holder, correctPosition(position), payloads);
    }

    @Override
    public int getItemViewType(int position) {
      return position < mFirst.getItemCount() ? 1 : 2;
    }

    @Override
    public long getItemId(int position) {
      return getAdapter(position).getItemId(correctPosition(position));
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
      return viewType == 1
          ? mFirst.createViewHolder(parent, 0)
          : mSecond.createViewHolder(parent, 0);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
      getAdapter(position).onBindViewHolder(holder, correctPosition(position));
    }

    @Override
    public int getItemCount() {
      return mFirst.getItemCount() + mSecond.getItemCount();
    }

    private class AdapterObserver extends RecyclerView.AdapterDataObserver {

      private final RecyclerView.Adapter mAdapter;

      private AdapterObserver(RecyclerView.Adapter adapter) {
        mAdapter = adapter;
      }

      private int correctPosition(int position) {
        return mAdapter == mFirst ? position : position + mFirst.getItemCount();
      }

      @Override
      public void onChanged() {
        TwoAdapterMultiplexingAdapter.this.notifyDataSetChanged();
      }

      @Override
      public void onItemRangeChanged(int positionStart, int itemCount) {
        TwoAdapterMultiplexingAdapter.this.notifyItemRangeChanged(
            correctPosition(positionStart), itemCount);
      }

      @Override
      public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
        TwoAdapterMultiplexingAdapter.this.notifyItemRangeChanged(
            correctPosition(positionStart), itemCount, payload);
      }

      @Override
      public void onItemRangeInserted(int positionStart, int itemCount) {
        TwoAdapterMultiplexingAdapter.this.notifyItemRangeInserted(
            correctPosition(positionStart), itemCount);
      }

      @Override
      public void onItemRangeRemoved(int positionStart, int itemCount) {
        TwoAdapterMultiplexingAdapter.this.notifyItemRangeRemoved(
            correctPosition(positionStart), itemCount);
      }

      @Override
      public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
        if (itemCount != 1) {
          throw new RuntimeException("Moving multiple indices at a time isn't supported.");
        }
        TwoAdapterMultiplexingAdapter.this.notifyItemMoved(
            correctPosition(fromPosition), correctPosition(toPosition));
      }
    }
  }
}
