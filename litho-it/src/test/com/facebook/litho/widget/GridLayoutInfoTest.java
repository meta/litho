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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.recyclerview.widget.GridLayoutManager;
import com.facebook.litho.SizeSpec;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class GridLayoutInfoTest {

  @Test
  public void testOrientation() {
    final GridLayoutInfo verticalGridLayoutInfo = createGridLayoutInfo(VERTICAL, 2);

    assertThat(verticalGridLayoutInfo.getScrollDirection()).isEqualTo(VERTICAL);

    final GridLayoutInfo horizontalGridLayoutInfo = createGridLayoutInfo(HORIZONTAL, 2);

    assertThat(horizontalGridLayoutInfo.getScrollDirection()).isEqualTo(HORIZONTAL);
  }

  @Test
  public void testLayoutManagerIsGrid() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(VERTICAL, 2);

    assertThat(gridLayoutInfo.getLayoutManager()).isInstanceOf(GridLayoutManager.class);
  }

  @Test
  public void testApproximateRangeVertical() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(VERTICAL, 3);
    int rangeSize = gridLayoutInfo.approximateRangeSize(10, 10, 30, 100);

    assertThat(rangeSize).isEqualTo(30);
  }

  @Test
  public void testApproximateRangeHorizontal() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(HORIZONTAL, 2);
    int rangeSize = gridLayoutInfo.approximateRangeSize(15, 10, 100, 20);

    assertThat(rangeSize).isEqualTo(14);
  }

  @Test
  public void testGetChildMeasureSpecVertical() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.getSpanSize()).thenReturn(2);

    final int heightSpec = gridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(UNSPECIFIED);

    final int widthSpec = gridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(widthSpec)).isEqualTo((200 / 3) * 2);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testGetChildMeasureSpecOverride() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.getSpanSize()).thenReturn(2);
    when(renderInfo.getCustomAttribute(GridLayoutInfo.OVERRIDE_SIZE)).thenReturn(20);

    final int heightSpec = gridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(UNSPECIFIED);

    final int widthSpec = gridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(widthSpec)).isEqualTo(20);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testGetChildMeasureSpecHorizontal() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(HORIZONTAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.getSpanSize()).thenReturn(2);

    final int heightSpec = gridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getSize(heightSpec)).isEqualTo((200 / 3) * 2);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(EXACTLY);

    final int widthSpec = gridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(UNSPECIFIED);
  }

  @Test
  public void testComputeWrappedHeightOnVertical() {
    /*
     * -------------------
     * | 200 | 200 | 200 |
     * -------------------
     * | 200 | 200 | 200 |
     * -------------------
     * | 200 | 200 | 200 |
     * -------------------
     * | 200 |
     * -------
     */
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(1000, SizeSpec.AT_MOST);

    final List<ComponentTreeHolder> componentTreeHolders = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final ComponentTreeHolder holder = mock(ComponentTreeHolder.class);
      when(holder.getMeasuredHeight()).thenReturn(200);

      componentTreeHolders.add(holder);
    }

    int measuredHeight =
        gridLayoutInfo.computeWrappedHeight(SizeSpec.getSize(sizeSpec), componentTreeHolders);
    assertThat(measuredHeight).isEqualTo(800);
  }

  @Test
  public void testComputeWrappedHeightOnVerticalWrapped() {
    /*
     * -------------------
     * | 200 | 200 | 200 |
     * -------------------
     * | 200 | 200 | 200 |
     * -------------------
     * | 200 | 200 | 200 |
     * ~~~~~~~~~~~~~~~~~~~
     */
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(600, SizeSpec.AT_MOST);

    final List<ComponentTreeHolder> componentTreeHolders = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      final ComponentTreeHolder holder = mock(ComponentTreeHolder.class);
      when(holder.getMeasuredHeight()).thenReturn(200);

      componentTreeHolders.add(holder);
    }

    int measuredHeight =
        gridLayoutInfo.computeWrappedHeight(SizeSpec.getSize(sizeSpec), componentTreeHolders);
    assertThat(measuredHeight).isEqualTo(600);
  }

  @Test
  public void testVerticalViewportFiller() {
    final int spanCount = 3;
    final int itemHeight = 10;
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(100, 100, VERTICAL, spanCount);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(false);
    when(renderInfo.getSpanSize()).thenReturn(1);
    for (int i = 0; i < 8; i++) {
      viewportFiller.add(renderInfo, 100, itemHeight);
    }

    assertThat(viewportFiller.getFill()).isEqualTo(itemHeight * spanCount);
  }

  @Test
  public void testVerticalViewportFillerWithFullSpan() {
    final int spanCount = 3;
    final int itemHeight = 10;
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(100, 100, VERTICAL, spanCount);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(true);
    when(renderInfo.getSpanSize()).thenReturn(spanCount);
    for (int i = 0; i < 8; i++) {
      viewportFiller.add(renderInfo, 100, itemHeight);
    }

    assertThat(viewportFiller.getFill()).isEqualTo(itemHeight * 8);
  }

  @Test
  public void testVerticalViewportFillerWithDifferentSpan() {
    /*
     * Test different spans (full span, partial span, no span)
     * -------------
     * |     A     |
     * -------------
     * |  B    | C |
     * -------------
     * | D | E | F |
     * -------------
     */
    final int spanCount = 3;
    final int itemHeight = 10;
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(100, 100, VERTICAL, spanCount);

    final RenderInfo renderInfoA = mock(RenderInfo.class);
    when(renderInfoA.isFullSpan()).thenReturn(true);
    when(renderInfoA.getSpanSize()).thenReturn(spanCount);
    viewportFiller.add(renderInfoA, 10 * spanCount, itemHeight);

    final RenderInfo renderInfoB = mock(RenderInfo.class);
    when(renderInfoB.isFullSpan()).thenReturn(false);
    when(renderInfoB.getSpanSize()).thenReturn(2);
    viewportFiller.add(renderInfoB, 10 * 2, itemHeight);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(false);
    when(renderInfo.getSpanSize()).thenReturn(1);

    for (int i = 0; i < 4; i++) {
      viewportFiller.add(renderInfo, 10, itemHeight);
    }
    assertThat(viewportFiller.getFill()).isEqualTo(itemHeight * 3);
  }

  @Test
  public void testHorizontalViewportFiller() {
    final int spanCount = 3;
    final int itemWidth = 10;
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(100, 100, HORIZONTAL, spanCount);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(false);
    when(renderInfo.getSpanSize()).thenReturn(1);
    for (int i = 0; i < 8; i++) {
      viewportFiller.add(renderInfo, itemWidth, 100);
    }

    assertThat(viewportFiller.getFill()).isEqualTo(itemWidth * spanCount);
  }

  @Test
  public void testHorizontalViewportFillerWithFullSpan() {
    final int spanCount = 3;
    final int itemWidth = 10;
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(100, 100, HORIZONTAL, spanCount);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(true);
    when(renderInfo.getSpanSize()).thenReturn(spanCount);
    for (int i = 0; i < 8; i++) {
      viewportFiller.add(renderInfo, itemWidth, 100);
    }

    assertThat(viewportFiller.getFill()).isEqualTo(itemWidth * 8);
  }

  @Test
  public void testHorizontalViewportFillerWithDifferentSpan() {
    /*
     * Test different spans (full span, partial span, no span)
     * -------------
     * |   |   | D |
     * |   | B |---|
     * | A |   | E |
     * |   |---|---|
     * |   | C | F |
     * -------------
     */
    final int spanCount = 3;
    final int itemWidth = 10;
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(100, 100, HORIZONTAL, spanCount);

    final RenderInfo renderInfoA = mock(RenderInfo.class);
    when(renderInfoA.isFullSpan()).thenReturn(true);
    when(renderInfoA.getSpanSize()).thenReturn(spanCount);
    viewportFiller.add(renderInfoA, itemWidth, 10 * spanCount);

    final RenderInfo renderInfoB = mock(RenderInfo.class);
    when(renderInfoB.isFullSpan()).thenReturn(false);
    when(renderInfoB.getSpanSize()).thenReturn(2);
    viewportFiller.add(renderInfoB, itemWidth, 10 * 2);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(false);
    when(renderInfo.getSpanSize()).thenReturn(1);

    for (int i = 0; i < 4; i++) {
      viewportFiller.add(renderInfo, itemWidth, 10);
    }
    assertThat(viewportFiller.getFill()).isEqualTo(itemWidth * 3);
  }

  @Test
  public void testVerticalViewportFillerWithDynamicHeight() {
    final Random random = new Random();
    int itemCount = 0;
    while (itemCount == 0) {
      itemCount = random.nextInt(50);
    }
    int spanCount = 0;
    while (spanCount == 0) {
      spanCount = random.nextInt(10);
    }
    final int[] itemHeight = new int[itemCount];
    for (int i = 0; i < itemCount; i++) {
      itemHeight[i] = random.nextInt(50);
    }
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(100, 500, VERTICAL, spanCount);

    int lastHeight = 0;
    int totalHeight = 0;
    for (int i = 0; i < itemCount; i++) {
      if (i % spanCount == 0) {
        lastHeight = totalHeight;
      }
      int height = itemHeight[i];
      totalHeight = Math.max(totalHeight, lastHeight + height);
      final RenderInfo renderInfo = mock(RenderInfo.class);
      when(renderInfo.getSpanSize()).thenReturn(1);
      viewportFiller.add(renderInfo, 100 / spanCount, height);
    }
    assertThat(viewportFiller.getFill()).isEqualTo(totalHeight);
  }

  @Test
  public void testHorizontalViewportFillerWithDynamicWidth() {
    final Random random = new Random();
    int itemCount = 0;
    while (itemCount == 0) {
      itemCount = random.nextInt(50);
    }
    int spanCount = 0;
    while (spanCount == 0) {
      spanCount = random.nextInt(10);
    }
    final int[] itemWidth = new int[itemCount];
    for (int i = 0; i < itemCount; i++) {
      itemWidth[i] = random.nextInt(50);
    }
    GridLayoutInfo.ViewportFiller viewportFiller =
        new GridLayoutInfo.ViewportFiller(500, 100, HORIZONTAL, spanCount);

    int lastWidth = 0;
    int totalWidth = 0;
    for (int i = 0; i < itemCount; i++) {
      if (i % spanCount == 0) {
        lastWidth = totalWidth;
      }
      int width = itemWidth[i];
      totalWidth = Math.max(totalWidth, lastWidth + width);
      final RenderInfo renderInfo = mock(RenderInfo.class);
      when(renderInfo.getSpanSize()).thenReturn(1);
      viewportFiller.add(renderInfo, width, 100 / spanCount);
    }
    assertThat(viewportFiller.getFill()).isEqualTo(totalWidth);
  }

  @Test
  public void testFullSpanChildWidthSpec() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(true);

    final int heightSpec = gridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(UNSPECIFIED);

    final int widthSpec = gridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(widthSpec)).isEqualTo(200);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testFullSpanChildHeightSpec() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(HORIZONTAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(true);

    final int widthSpec = gridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(UNSPECIFIED);

    final int heightSpec = gridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(heightSpec)).isEqualTo(200);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testGridSpanSizeLookup() {
    final GridLayoutInfo gridLayoutInfo = createGridLayoutInfo(HORIZONTAL, 3);

    final RenderInfo renderInfo1 = mock(RenderInfo.class);
    when(renderInfo1.isFullSpan()).thenReturn(true);
    when(renderInfo1.getSpanSize()).thenReturn(1);

    final RenderInfo renderInfo2 = mock(RenderInfo.class);
    when(renderInfo2.isFullSpan()).thenReturn(false);
    when(renderInfo2.getSpanSize()).thenReturn(2);

    final RenderInfo renderInfo3 = mock(RenderInfo.class);
    when(renderInfo3.isFullSpan()).thenReturn(false);
    when(renderInfo3.getSpanSize()).thenReturn(1);

    final LayoutInfo.RenderInfoCollection renderInfoCollection =
        mock(LayoutInfo.RenderInfoCollection.class);
    when(renderInfoCollection.getRenderInfoAt(0)).thenReturn(renderInfo1);
    when(renderInfoCollection.getRenderInfoAt(1)).thenReturn(renderInfo2);
    when(renderInfoCollection.getRenderInfoAt(2)).thenReturn(renderInfo3);
    gridLayoutInfo.setRenderInfoCollection(renderInfoCollection);

    final GridLayoutManager.SpanSizeLookup spanSizeLookup =
        ((GridLayoutManager) gridLayoutInfo.getLayoutManager()).getSpanSizeLookup();
    assertThat(spanSizeLookup.getSpanSize(0)).isEqualTo(3);
    assertThat(spanSizeLookup.getSpanSize(1)).isEqualTo(2);
    assertThat(spanSizeLookup.getSpanSize(2)).isEqualTo(1);
  }

  private static GridLayoutInfo createGridLayoutInfo(int direction, int spanCount) {
    return new GridLayoutInfo(getApplicationContext(), spanCount, direction, false);
  }
}
