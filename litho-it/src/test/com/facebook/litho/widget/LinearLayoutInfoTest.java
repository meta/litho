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

import static androidx.recyclerview.widget.OrientationHelper.HORIZONTAL;
import static androidx.recyclerview.widget.OrientationHelper.VERTICAL;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.facebook.litho.SizeSpec.EXACTLY;
import static com.facebook.litho.SizeSpec.UNSPECIFIED;
import static com.facebook.litho.SizeSpec.makeSizeSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.recyclerview.widget.LinearLayoutManager;
import com.facebook.litho.SizeSpec;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

/** Tests for {@link LinearLayoutInfo} */
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class LinearLayoutInfoTest {

  @Test
  public void testOrientations() {
    final LinearLayoutInfo verticalLinearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), VERTICAL, false);

    assertThat(VERTICAL).isEqualTo(verticalLinearLayoutInfo.getScrollDirection());

    final LinearLayoutInfo horizontalLinearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), HORIZONTAL, false);

    assertThat(HORIZONTAL).isEqualTo(horizontalLinearLayoutInfo.getScrollDirection());
  }

  @Test
  public void testGetLayoutManager() {
    final LinearLayoutInfo linearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), VERTICAL, false);

    assertThat(linearLayoutInfo.getLayoutManager()).isInstanceOf(LinearLayoutManager.class);
  }

  @Test
  public void testApproximateRangeVertical() {
    final LinearLayoutInfo linearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), VERTICAL, false);

    int rangeSize = linearLayoutInfo.approximateRangeSize(10, 10, 10, 100);

    assertThat(rangeSize).isEqualTo(10);
  }

  @Test
  public void testApproximateRangeHorizontal() {
    final LinearLayoutInfo linearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), HORIZONTAL, false);

    int rangeSize = linearLayoutInfo.approximateRangeSize(10, 10, 100, 10);

    assertThat(rangeSize).isEqualTo(10);
  }

  @Test
  public void testGetChildMeasureSpecVertical() {
    final LinearLayoutInfo linearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), VERTICAL, false);
    final int sizeSpec = makeSizeSpec(200, EXACTLY);

    final int heightSpec =
        linearLayoutInfo.getChildHeightSpec(sizeSpec, ComponentRenderInfo.createEmpty());
    assertThat(makeSizeSpec(0, UNSPECIFIED)).isEqualTo(heightSpec);

    final int widthSpec =
        linearLayoutInfo.getChildWidthSpec(sizeSpec, ComponentRenderInfo.createEmpty());
    assertThat(sizeSpec).isEqualTo(widthSpec);
  }

  @Test
  public void testGetChildMeasureSpecHorizontal() {
    final LinearLayoutInfo linearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), HORIZONTAL, false);
    final int sizeSpec = makeSizeSpec(200, EXACTLY);

    final int heightSpec =
        linearLayoutInfo.getChildHeightSpec(sizeSpec, ComponentRenderInfo.createEmpty());
    assertThat(sizeSpec).isEqualTo(heightSpec);

    final int widthSpec =
        linearLayoutInfo.getChildWidthSpec(sizeSpec, ComponentRenderInfo.createEmpty());
    assertThat(makeSizeSpec(0, UNSPECIFIED)).isEqualTo(widthSpec);
  }

  @Test
  public void testComputeWrappedHeightOnVertical() {
    /*
     * -------
     * | 100 |
     * ~~~~~~~
     * ... x8
     * ~~~~~~~
     * | 100 |
     * -------
     */
    final LinearLayoutInfo linearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), VERTICAL, false);
    final int sizeSpec = SizeSpec.makeSizeSpec(1200, SizeSpec.AT_MOST);

    final List<ComponentTreeHolder> componentTreeHolders = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final ComponentTreeHolder holder = mock(ComponentTreeHolder.class);
      when(holder.getMeasuredHeight()).thenReturn(100);
      when(holder.isTreeValid()).thenReturn(true);

      componentTreeHolders.add(holder);
    }

    int measuredHeight =
        linearLayoutInfo.computeWrappedHeight(SizeSpec.getSize(sizeSpec), componentTreeHolders);
    assertThat(measuredHeight).isEqualTo(1000);
  }

  @Test
  public void testComputeWrappedHeightOnVerticalWrapped() {
    /*
     * -------
     * | 100 |
     * ~~~~~~~
     * ... x7
     * ~~~~~~~
     */
    final LinearLayoutInfo linearLayoutInfo =
        new LinearLayoutInfo(getApplicationContext(), VERTICAL, false);
    final int sizeSpec = SizeSpec.makeSizeSpec(800, SizeSpec.AT_MOST);

    final List<ComponentTreeHolder> componentTreeHolders = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final ComponentTreeHolder holder = mock(ComponentTreeHolder.class);
      when(holder.getMeasuredHeight()).thenReturn(100);
      when(holder.isTreeValid()).thenReturn(true);

      componentTreeHolders.add(holder);
    }

    int measuredHeight =
        linearLayoutInfo.computeWrappedHeight(SizeSpec.getSize(sizeSpec), componentTreeHolders);
    assertThat(measuredHeight).isEqualTo(800);
  }

  @Test
  public void testViewportFiller() {
    LinearLayoutInfo.ViewportFiller viewportFiller =
        new LinearLayoutInfo.ViewportFiller(100, 100, VERTICAL);

    for (int i = 0; i < 8; i++) {
      viewportFiller.add(mock(RenderInfo.class), 100, 10);
    }

    assertThat(viewportFiller.getFill()).isEqualTo(80);
  }
}
