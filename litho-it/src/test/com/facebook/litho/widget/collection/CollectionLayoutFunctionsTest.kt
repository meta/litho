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

package com.facebook.litho.widget.collection

import androidx.recyclerview.widget.LinearLayoutManager
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.LinearLayoutInfo
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.SizeConstraints.Companion.Infinity
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.robolectric.annotation.GraphicsMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CollectionLayoutFunctionsTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  // region: Cases for CollectionLayoutFunctions::getChildSizeConstraints

  // Tests child size constraints when collection has a fixed size (200x400)
  // Condition:
  //  - vertical collection
  //  - collection size is fixed (200x400)
  //  - item hasn't been measured yet
  //  - CrossAxisWrapMode.NoWrap
  // Expectation: child constraints follow layout info specs directly
  @Test
  fun `getChildSizeConstraints when collection size is fixed`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(200, 400),
        collectionSize = IntSize(200, 400),
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = ComponentRenderInfo.createEmpty()
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(100))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(100))
    val item = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.maxWidth == 100).isTrue
    assertThat(result.maxHeight == 100).isTrue
  }

  // Tests child size constraints when collection has fixed size and item matches parent
  // Condition:
  //  - vertical collection
  //  - collection size is fixed (300x600)
  //  - item has parent percentage constraints (50% width, 50% height)
  //  - CrossAxisWrapMode.NoWrap
  // Expectation: child constraints are adjusted based on parent percentage values
  @Test
  fun `getChildSizeConstraints when collection size is fixed and item matches parent`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(300, 600),
        collectionSize = IntSize(300, 600),
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = spy(ComponentRenderInfo.createEmpty())
    whenever(renderInfo.parentWidthPercent).thenReturn(50f)
    whenever(renderInfo.parentHeightPercent).thenReturn(50f)
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(300))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(600))
    val item = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.maxWidth).isEqualTo(150)
    assertThat(result.maxHeight).isEqualTo(300)
  }

  // Tests child size constraints with no collection size and CrossAxisWrapMode.NoWrap (vertical)
  // Condition:
  //  - vertical collection
  //  - no collection size (null)
  //  - item hasn't been measured yet
  //  - CrossAxisWrapMode.NoWrap
  // Expectation: child constraints follow layout info specs directly
  @Test
  fun `getChildSizeConstraints with no collection size and crossAxisWrapMode is NoWrap vertical`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(400, 400),
        collectionSize = IntSize.Invalid,
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = ComponentRenderInfo.createEmpty()
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(100))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    val item = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.hasExactWidth).isTrue
    assertThat(result.maxWidth).isEqualTo(100)
    assertThat(result.maxHeight).isEqualTo(200)
  }

  // Tests child size constraints with no collection size and CrossAxisWrapMode.NoWrap (horizontal)
  // Condition:
  //  - horizontal collection
  //  - no collection size (null)
  //  - item hasn't been measured yet
  //  - CrossAxisWrapMode.NoWrap
  // Expectation: child constraints follow layout info specs directly
  @Test
  fun `getChildSizeConstraints with no collection size and crossAxisWrapMode is NoWrap horizontal`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(400, 400),
        collectionSize = IntSize.Invalid,
        collectionItems = emptyList(),
        isVertical = false,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = ComponentRenderInfo.createEmpty()
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(100))
    val item = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.maxWidth).isEqualTo(200)
    assertThat(result.hasExactHeight).isTrue
    assertThat(result.maxHeight).isEqualTo(100)
  }

  // Tests child size constraints with collection size and CrossAxisWrapMode.Dynamic (vertical)
  // Condition:
  //  - vertical collection
  //  - collection size is fixed
  //  - item hasn't been measured yet
  //  - CrossAxisWrapMode.Dynamic
  // Expectation: no limitation on child's width
  @Test
  fun `getChildSizeConstraints with collection size and crossAxisWrapMode is Dynamic vertical`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(400, 400),
        collectionSize = IntSize(400, 400),
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = ComponentRenderInfo.createEmpty()
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    val item = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.hasBoundedWidth).isFalse
    assertThat(result.maxWidth).isEqualTo(Infinity)
    assertThat(result.maxHeight).isEqualTo(200)
  }

  // Tests child size constraints with collection size and CrossAxisWrapMode.Dynamic (horizontal)
  // Condition:
  //  - horizontal collection
  //  - collection size is fixed
  //  - item hasn't been measured yet
  //  - CrossAxisWrapMode.Dynamic
  // Expectation: no limitation on child's height
  @Test
  fun `getChildSizeConstraints with collection size is fixed and crossAxisWrapMode is Dynamic horizontal`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(400, 400),
        collectionSize = IntSize(400, 400),
        collectionItems = emptyList(),
        isVertical = false,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = ComponentRenderInfo.createEmpty()
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    val item = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.maxWidth).isEqualTo(200)
    assertThat(result.hasBoundedHeight).isFalse
    assertThat(result.maxHeight).isEqualTo(Infinity)
  }

  // Tests child size constraints with fixed item size in vertical collection with
  // CrossAxisWrapMode.Dynamic
  // Condition:
  //  - vertical collection
  //  - collection size is fixed
  //  - item has fixed size (150x100)
  //  - CrossAxisWrapMode.Dynamic
  // Expectation: child's width constraint should respect collection size
  @Test
  fun `getChildSizeConstraints with fixed item size in vertical Dynamic collection`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(400, 400),
        collectionSize = IntSize(400, 400),
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = spy(ComponentRenderInfo.createEmpty())
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(150))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(100))
    val item = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo))
    item.measure(SizeConstraints.exact(150, 100), null)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.hasExactWidth).isTrue
    assertThat(result.maxWidth).isEqualTo(400)
    assertThat(result.maxHeight).isEqualTo(100)
  }

  // Tests child size constraints with fixed item size in horizontal collection with
  // CrossAxisWrapMode.Dynamic
  // Condition:
  //  - horizontal collection
  //  - collection size is fixed
  //  - item has fixed size (150x100)
  //  - CrossAxisWrapMode.Dynamic
  // Expectation: child's height constraint should respect collection size
  @Test
  fun `getChildSizeConstraints with fixed item size in horizontal Dynamic collection`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(400, 400),
        collectionSize = IntSize(400, 400),
        collectionItems = emptyList(),
        isVertical = false,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = spy(ComponentRenderInfo.createEmpty())
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(150))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(100))
    val item = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo))
    item.measure(SizeConstraints.exact(150, 100), null)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.maxWidth).isEqualTo(150)
    assertThat(result.hasExactHeight).isTrue
    assertThat(result.maxHeight).isEqualTo(400)
  }

  // Tests child size constraints with larger item size in vertical collection with
  // CrossAxisWrapMode.Dynamic
  // Condition:
  //  - vertical collection
  //  - collection size is fixed (300x300)
  //  - item has fixed size larger than collection (500x200)
  //  - CrossAxisWrapMode.Dynamic
  // Expectation:
  //  - child's width constraint should be the largest number between collection size and
  // item size
  @Test
  fun `getChildSizeConstraints with larger item width in vertical Dynamic collection`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(300, 300),
        collectionSize = IntSize(300, 300),
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = spy(ComponentRenderInfo.createEmpty())
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(500))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    val item = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo))
    item.measure(SizeConstraints.exact(500, 200), null)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.hasExactWidth).isTrue
    assertThat(result.maxWidth).isEqualTo(500)
    assertThat(result.maxHeight).isEqualTo(200)
  }

  // Tests child size constraints with larger item size in horizontal collection with
  // CrossAxisWrapMode.Dynamic
  // Condition:
  //  - horizontal collection
  //  - collection size is fixed (300x300)
  //  - item has fixed size larger than collection (200x500)
  //  - CrossAxisWrapMode.Dynamic
  // Expectation: child's height constraint should be the largest number between collection size and
  // item size
  @Test
  fun `getChildSizeConstraints with larger item height in horizontal Dynamic collection`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(300, 300),
        collectionSize = IntSize(300, 300),
        collectionItems = emptyList(),
        isVertical = false,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )
    val renderInfo = spy(ComponentRenderInfo.createEmpty())
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo))).thenReturn(exactly(200))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo))).thenReturn(exactly(500))
    val item = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo))
    item.measure(SizeConstraints.exact(200, 500), null)
    val result = scope.getChildSizeConstraints(item)
    assertThat(result).isNotNull
    assertThat(result.maxWidth).isEqualTo(200)
    assertThat(result.hasExactHeight).isTrue
    assertThat(result.maxHeight).isEqualTo(500)
  }

  // endregion

  // region CollectionLayoutFunctions::calculateLayout

  // Tests calculateLayout when collection has exact size constraints
  // Condition:
  //  - Collection has exact size constraints (300x300) - both width and height are fixed
  //  - Collection size is not pre-calculated (null)
  //  - Empty list of items to layout
  // Expectation:
  //  - Should return exact size matching the constraints (300x300)
  //  - Width and height should both be 300 regardless of item content
  @Test
  fun `calculateLayout with exact collection constraints`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints.exact(300, 300),
        collectionSize = IntSize.Invalid,
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(300)
    assertThat(result.height).isEqualTo(300)
  }

  // Tests calculateLayout with maximum collection constraints and no wrapping
  // Condition:
  //  - Collection has maximum size constraints (maxWidth = 400, maxHeight = 500)
  //  - Collection size is not pre-calculated (null)
  //  - wrapInMainAxis = false: collection uses full available space in main axis
  //  - CrossAxisWrapMode.NoWrap: collection uses full available space in cross axis
  //  - Empty list of items to layout
  // Expectation:
  //  - Should return size matching the maximum constraints (400x500)
  //  - Width and height should use full available space regardless of item content
  @Test
  fun `calculateLayout with maximum collection constraints and no wrapping`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints(maxWidth = 400, maxHeight = 500),
        collectionSize = IntSize.Invalid,
        collectionItems = emptyList(),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(400)
    assertThat(result.height).isEqualTo(500)
  }

  // Tests calculateLayout when cross-axis wrapping matches the first child's size
  // Condition:
  //  - CrossAxisWrapMode is MatchFirstChild - collection width should match first item
  //  - Vertical orientation - items are stacked vertically, width varies per item
  //  - Collection has bounded height constraint (maxHeight = 400) but flexible width
  //  - Two items with different widths: first item 100px wide, second item 200px wide
  // Expectation:
  //  - Collection width should match the first item's width (100px), ignoring other items
  //  - Collection height should use the maximum available height (400px)
  //  - Final size should be 100x400, not influenced by the second item's 200px width
  @Test
  fun `calculateLayout with MatchFirstChild wrap mode vertical`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    whenever(linearLayoutInfo.createViewportFiller(any(), any())).thenReturn(null)

    // Create items with different sizes
    val renderInfo1 = ComponentRenderInfo.createEmpty()
    val firstItem = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo1)
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo1))).thenReturn(exactly(100))
    val renderInfo2 = ComponentRenderInfo.createEmpty()
    val secondItem = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo2)
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo2))).thenReturn(exactly(200))

    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints(maxHeight = 400),
        collectionSize = IntSize.Invalid,
        collectionItems = listOf(firstItem, secondItem),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.MatchFirstChild,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(100)
    assertThat(result.height).isEqualTo(400)
  }

  // Tests calculateLayout with MatchFirstChild wrap mode in horizontal orientation
  // Condition:
  //  - Collection is horizontally oriented (items arranged left-to-right)
  //  - CrossAxisWrapMode.MatchFirstChild: collection's cross-axis size matches first item
  //  - Collection has flexible width constraint (maxWidth = 400) but no fixed size
  //  - Two items with different cross-axis (height) sizes: first=200px, second=100px
  // Expectation:
  //  - Collection width: uses maximum available width (400px) since width is main axis
  //  - Collection height: matches first item's height (200px), ignoring second item's 100px
  //  - Final size: 400x200 (width from constraint, height from first item)
  @Test
  fun `calculateLayout with MatchFirstChild wrap mode horizontal`() {
    val linearLayoutInfo = mock<LinearLayoutInfo>()
    whenever(linearLayoutInfo.createViewportFiller(any(), any())).thenReturn(null)

    // Create items with different sizes
    val renderInfo1 = ComponentRenderInfo.createEmpty()
    val firstItem = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo1)
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo1))).thenReturn(exactly(200))
    val renderInfo2 = ComponentRenderInfo.createEmpty()
    val secondItem = LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo2)
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo2))).thenReturn(exactly(100))

    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints(maxWidth = 400),
        collectionSize = IntSize.Invalid,
        collectionItems = listOf(firstItem, secondItem),
        isVertical = false,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.MatchFirstChild,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(400)
    assertThat(result.height).isEqualTo(200)
  }

  // Tests calculateLayout with Dynamic wrap mode in vertical orientation
  // Condition:
  //  - Collection is vertically oriented (items stacked top-to-bottom)
  //  - CrossAxisWrapMode.Dynamic: collection's cross-axis size adapts to largest item
  //  - Collection has flexible height constraint (maxHeight = 400) but no fixed size
  //  - Five items with varying cross-axis (width) sizes: 100px, 200px, 150px, 230px, 120px
  // Expectation:
  //  - Collection width: matches largest item width (230px from fourth item)
  //  - Collection height: uses maximum available height (400px) since height is main axis
  //  - Final size: 230x400 (width from largest item, height from constraint)
  @Test
  fun `calculateLayout with Dynamic wrap mode vertical`() {
    val linearLayoutInfo =
        spy(LinearLayoutInfo(LinearLayoutManager(lithoTestRule.context.androidContext)))

    // Create items with different sizes
    val renderInfo1 = ComponentRenderInfo.createEmpty()
    val firstItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo1))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo1))).thenReturn(exactly(50))
    firstItem.measure(SizeConstraints.exact(100, 50), null)
    val renderInfo2 = ComponentRenderInfo.createEmpty()
    val secondItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo2))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo2))).thenReturn(exactly(75))
    secondItem.measure(SizeConstraints.exact(200, 75), null)
    val renderInfo3 = ComponentRenderInfo.createEmpty()
    val thirdItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo3))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo3))).thenReturn(exactly(60))
    thirdItem.measure(SizeConstraints.exact(150, 60), null)
    val renderInfo4 = ComponentRenderInfo.createEmpty()
    val fourthItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo4))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo4))).thenReturn(exactly(90))
    fourthItem.measure(SizeConstraints.exact(230, 90), null)
    val renderInfo5 = ComponentRenderInfo.createEmpty()
    val fifthItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo5))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo5))).thenReturn(exactly(40))
    fifthItem.measure(SizeConstraints.exact(120, 40), null)

    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints(maxHeight = 400),
        collectionSize = IntSize.Invalid,
        collectionItems = listOf(firstItem, secondItem, thirdItem, fourthItem, fifthItem),
        isVertical = true,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(230) // Largest width
    assertThat(result.height).isEqualTo(400)
  }

  // Tests calculateLayout with Dynamic wrap mode in horizontal orientation
  // Condition:
  //  - Collection is horizontally oriented (items arranged left-to-right)
  //  - CrossAxisWrapMode.Dynamic: collection's cross-axis size adapts to largest item
  //  - Collection has flexible width constraint (maxWidth = 400) but no fixed size
  //  - Five items with varying cross-axis (height) sizes: 100px, 200px, 150px, 230px, 120px
  // Expectation:
  //  - Collection width: uses maximum available width (400px) since width is main axis
  //  - Collection height: matches largest item height (230px from fourth item)
  //  - Final size: 400x230 (width from constraint, height from largest item)
  @Test
  fun `calculateLayout with Dynamic wrap mode horizontal`() {
    val linearLayoutInfo = spy(
        LinearLayoutInfo(
            LinearLayoutManager(
                lithoTestRule.context.androidContext,
                LinearLayoutManager.HORIZONTAL,
                false,
            ),
        ),
    )

    // Create items with different sizes
    val renderInfo1 = ComponentRenderInfo.createEmpty()
    val firstItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo1))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo1))).thenReturn(exactly(50))
    firstItem.measure(SizeConstraints.exact(50, 100), null)
    val renderInfo2 = ComponentRenderInfo.createEmpty()
    val secondItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo2))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo2))).thenReturn(exactly(75))
    secondItem.measure(SizeConstraints.exact(75, 200), null)
    val renderInfo3 = ComponentRenderInfo.createEmpty()
    val thirdItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo3))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo3))).thenReturn(exactly(60))
    thirdItem.measure(SizeConstraints.exact(60, 150), null)
    val renderInfo4 = ComponentRenderInfo.createEmpty()
    val fourthItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo4))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo4))).thenReturn(exactly(90))
    fourthItem.measure(SizeConstraints.exact(90, 230), null)
    val renderInfo5 = ComponentRenderInfo.createEmpty()
    val fifthItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo5))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo5))).thenReturn(exactly(40))
    fifthItem.measure(SizeConstraints.exact(40, 120), null)

    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints(maxWidth = 400),
        collectionSize = IntSize.Invalid,
        collectionItems = listOf(firstItem, secondItem, thirdItem, fourthItem, fifthItem),
        isVertical = false,
        wrapInMainAxis = false,
        crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(400)
    assertThat(result.height).isEqualTo(230) // Largest height
  }

  // Tests calculateLayout with main-axis wrapping enabled in vertical orientation
  // Condition:
  //  - Collection is vertically oriented (items stacked top-to-bottom)
  //  - CrossAxisWrapMode.NoWrap: collection width is constrained, not adaptive
  //  - wrapInMainAxis = true: collection height wraps to fit content instead of using max
  // constraint
  //  - Collection has size constraints (maxWidth = 300, maxHeight = 400)
  //  - Two items with heights 50px and 75px, both using full width (300px)
  // Expectation:
  //  - Collection width: uses maximum available width (300px) since cross-axis doesn't wrap
  //  - Collection height: wraps to sum of item heights (50 + 75 = 125px), ignoring maxHeight
  // constraint
  //  - Final size: 300x125 (width from constraint, height from content wrapping)
  @Test
  fun `calculateLayout with wrapInMainAxis and vertical`() {
    val linearLayoutInfo =
        spy(LinearLayoutInfo(LinearLayoutManager(lithoTestRule.context.androidContext)))

    // Create items with different sizes
    val renderInfo1 = ComponentRenderInfo.createEmpty()
    val firstItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo1))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo1))).thenReturn(exactly(300))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo1))).thenReturn(exactly(50))
    firstItem.measure(SizeConstraints.exact(300, 50), null)
    val renderInfo2 = ComponentRenderInfo.createEmpty()
    val secondItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo2))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo2))).thenReturn(exactly(300))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo2))).thenReturn(exactly(75))
    secondItem.measure(SizeConstraints.exact(300, 75), null)

    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints(maxWidth = 300, maxHeight = 400),
        collectionSize = IntSize.Invalid,
        collectionItems = listOf(firstItem, secondItem),
        isVertical = true,
        wrapInMainAxis = true,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(300)
    assertThat(result.height).isEqualTo(125) // Sum of item heights: 50 + 75
  }

  // Tests calculateLayout with main-axis wrapping enabled in horizontal orientation
  // Condition:
  //  - Collection is horizontally oriented (items arranged left-to-right)
  //  - CrossAxisWrapMode.NoWrap: collection height is constrained, not adaptive
  //  - wrapInMainAxis = true: collection width wraps to fit content instead of using max constraint
  //  - Collection has size constraints (maxWidth = 400, maxHeight = 300)
  //  - Two items with widths 50px and 75px, both using full height (300px)
  // Expectation:
  //  - Collection width: wraps to sum of item widths (50 + 75 = 125px), ignoring maxWidth
  // constraint
  //  - Collection height: uses maximum available height (300px) since cross-axis doesn't wrap
  //  - Final size: 125x300 (width from content wrapping, height from constraint)
  @Test
  fun `calculateLayout with wrapInMainAxis and horizontal`() {
    val linearLayoutInfo = spy(
        LinearLayoutInfo(
            LinearLayoutManager(
                lithoTestRule.context.androidContext,
                LinearLayoutManager.HORIZONTAL,
                false,
            ),
        ),
    )

    // Create items with different sizes
    val renderInfo1 = ComponentRenderInfo.createEmpty()
    val firstItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo1))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo1))).thenReturn(exactly(50))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo1))).thenReturn(exactly(300))
    firstItem.measure(SizeConstraints.exact(50, 300), null)
    val renderInfo2 = ComponentRenderInfo.createEmpty()
    val secondItem = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo2))
    whenever(linearLayoutInfo.getChildWidthSpec(any(), eq(renderInfo2))).thenReturn(exactly(75))
    whenever(linearLayoutInfo.getChildHeightSpec(any(), eq(renderInfo2))).thenReturn(exactly(300))
    secondItem.measure(SizeConstraints.exact(75, 300), null)

    val scope = CollectionLayoutScope(
        layoutInfo = linearLayoutInfo,
        collectionConstraints = SizeConstraints(maxWidth = 400, maxHeight = 300),
        collectionSize = IntSize.Invalid,
        collectionItems = listOf(firstItem, secondItem),
        isVertical = false,
        wrapInMainAxis = true,
        crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
        firstVisiblePosition = 0,
        lastVisiblePosition = 10,
    )

    val result = scope.calculateLayout()

    assertThat(result).isNotNull
    assertThat(result.width).isEqualTo(125) // Sum of item widths: 50 + 75
    assertThat(result.height).isEqualTo(300)
  }

  // endregion
}
