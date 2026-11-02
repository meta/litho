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
import static com.facebook.litho.SizeSpec.EXACTLY;
import static com.facebook.litho.SizeSpec.UNSPECIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import com.facebook.litho.SizeSpec;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class StaggeredGridLayoutInfoTest {

  @Test
  public void testOrientation() {
    final StaggeredGridLayoutInfo verticalStaggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(VERTICAL, 2);

    assertThat(verticalStaggeredGridLayoutInfo.getScrollDirection()).isEqualTo(VERTICAL);

    final StaggeredGridLayoutInfo horizontalStaggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(HORIZONTAL, 2);

    assertThat(horizontalStaggeredGridLayoutInfo.getScrollDirection()).isEqualTo(HORIZONTAL);
  }

  @Test
  public void testLayoutManagerIsStaggeredGrid() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(VERTICAL, 2);

    assertThat(staggeredGridLayoutInfo.getLayoutManager())
        .isInstanceOf(StaggeredGridLayoutManager.class);
  }

  @Test
  public void testApproximateRangeVertical() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(VERTICAL, 3);
    int rangeSize = staggeredGridLayoutInfo.approximateRangeSize(10, 10, 30, 100);

    assertThat(rangeSize).isEqualTo(30);
  }

  @Test
  public void testApproximateRangeHorizontal() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(HORIZONTAL, 2);
    int rangeSize = staggeredGridLayoutInfo.approximateRangeSize(15, 10, 100, 20);

    assertThat(rangeSize).isEqualTo(14);
  }

  @Test
  public void testGetChildMeasureSpecVertical() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.getSpanSize()).thenReturn(1);

    final int heightSpec = staggeredGridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(UNSPECIFIED);

    final int widthSpec = staggeredGridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(widthSpec)).isEqualTo((200 / 3) * 1);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testGetChildMeasureSpecOverrideVertical() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.getSpanSize()).thenReturn(2);
    when(renderInfo.getCustomAttribute(StaggeredGridLayoutInfo.OVERRIDE_SIZE)).thenReturn(20);

    final int heightSpec = staggeredGridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(UNSPECIFIED);

    final int widthSpec = staggeredGridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(widthSpec)).isEqualTo(20);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testGetChildMeasureSpecVerticalWithFullSpan() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(VERTICAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(true);

    final int heightSpec = staggeredGridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(UNSPECIFIED);

    final int widthSpec = staggeredGridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(widthSpec)).isEqualTo((200 / 3) * 3);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testGetChildMeasureSpecHorizontal() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(HORIZONTAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.getSpanSize()).thenReturn(1);

    final int heightSpec = staggeredGridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getSize(heightSpec)).isEqualTo((200 / 3) * 1);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(EXACTLY);

    final int widthSpec = staggeredGridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(UNSPECIFIED);
  }

  @Test
  public void testGetChildMeasureSpecOverrideHorizontal() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(HORIZONTAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.getSpanSize()).thenReturn(2);
    when(renderInfo.getCustomAttribute(StaggeredGridLayoutInfo.OVERRIDE_SIZE)).thenReturn(20);

    final int widthSpec = staggeredGridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(UNSPECIFIED);

    final int heightSpec = staggeredGridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);

    assertThat(SizeSpec.getSize(heightSpec)).isEqualTo(20);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(EXACTLY);
  }

  @Test
  public void testGetChildMeasureSpecHorizontalWithFullSpan() {
    final StaggeredGridLayoutInfo staggeredGridLayoutInfo =
        createStaggeredGridLayoutInfo(HORIZONTAL, 3);
    final int sizeSpec = SizeSpec.makeSizeSpec(200, EXACTLY);

    final RenderInfo renderInfo = mock(RenderInfo.class);
    when(renderInfo.isFullSpan()).thenReturn(true);

    final int heightSpec = staggeredGridLayoutInfo.getChildHeightSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getSize(heightSpec)).isEqualTo((200 / 3) * 3);
    assertThat(SizeSpec.getMode(heightSpec)).isEqualTo(EXACTLY);

    final int widthSpec = staggeredGridLayoutInfo.getChildWidthSpec(sizeSpec, renderInfo);
    assertThat(SizeSpec.getMode(widthSpec)).isEqualTo(UNSPECIFIED);
  }

  @Test
  public void testVerticalViewportFiller() {
    final int spanCount = 3;
    final int[] heights = {
      10, 13, 16,
      19, 22, 25,
      28, 31
    };
    final int maxHeight = 66; // 13 + 22 + 31

    StaggeredGridLayoutInfo.ViewportFiller viewportFiller =
        new StaggeredGridLayoutInfo.ViewportFiller(100, 100, VERTICAL, spanCount);
    for (int i = 0; i < heights.length; i++) {
      viewportFiller.add(mock(RenderInfo.class), 100, heights[i]);
    }

    assertThat(viewportFiller.getFill()).isEqualTo(maxHeight);
  }

  @Test
  public void testHorizontalViewportFiller() {
    final int spanCount = 3;
    final int[] widths = {
      10, 13, 16,
      19, 22, 25,
      28, 31
    };
    final int maxWidth = 66; // 19 + 22 + 25

    StaggeredGridLayoutInfo.ViewportFiller viewportFiller =
        new StaggeredGridLayoutInfo.ViewportFiller(100, 100, HORIZONTAL, spanCount);
    for (int i = 0; i < widths.length; i++) {
      viewportFiller.add(mock(RenderInfo.class), widths[i], 100);
    }

    assertThat(viewportFiller.getFill()).isEqualTo(maxWidth);
  }

  /**
   * This test created for SLA task T63231517 Creating two different staggered grid layouts with the
   * first one with a smaller span count would crash the second one.
   */
  @Test
  public void testTwoStaggeredGridWithDifferentSpanCounts() {
    final int spanCountOne = 2;
    final int spanCountTow = 3;

    StaggeredGridLayoutInfo staggeredGridLayoutInfoOne =
        createStaggeredGridLayoutInfo(VERTICAL, spanCountOne);
    StaggeredGridLayoutInfo staggeredGridLayoutInfoTwo =
        createStaggeredGridLayoutInfo(VERTICAL, spanCountTow);

    staggeredGridLayoutInfoOne.findFirstFullyVisibleItemPosition();
    staggeredGridLayoutInfoTwo.findFirstFullyVisibleItemPosition();
  }

  private static StaggeredGridLayoutInfo createStaggeredGridLayoutInfo(
      int direction, int spanCount) {
    return new StaggeredGridLayoutInfo(
        spanCount, direction, false, StaggeredGridLayoutManager.GAP_HANDLING_NONE);
  }
}
