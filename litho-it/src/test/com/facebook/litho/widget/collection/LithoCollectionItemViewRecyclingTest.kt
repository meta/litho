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
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.LinearLayoutInfo
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests for LithoCollectionItem view recycling behavior, specifically testing the fix for
 * RecyclerView change animation bugs where the same item is bound to multiple views.
 *
 * These tests verify that:
 * 1. Normal recycling works correctly (view is cleaned when it's the current view)
 * 2. Change animation scenario works correctly (old view is cleaned proactively, not on recycle)
 * 3. View tracking is properly maintained across bindings
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LithoCollectionItemViewRecyclingTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private lateinit var item: LithoCollectionItem
  private lateinit var layoutInfo: LinearLayoutInfo
  private lateinit var scope: CollectionLayoutScope

  @Before
  fun setup() {
    val renderInfo = ComponentRenderInfo.createEmpty()
    item = spy(LithoCollectionItem(lithoTestRule.context, renderInfo = renderInfo))

    // Setup layout info for proper binding
    layoutInfo = LinearLayoutInfo(LinearLayoutManager(lithoTestRule.context.androidContext))

    // Measure the item first
    item.measure(SizeConstraints.exact(100, 100), null)

    // Create collection layout scope for binding
    scope =
        CollectionLayoutScope(
            layoutInfo = layoutInfo,
            collectionConstraints = SizeConstraints.exact(400, 800),
            collectionSize = IntSize(400, 800),
            collectionItems = listOf(item),
            isVertical = true,
            wrapInMainAxis = false,
            crossAxisWrapMode = CrossAxisWrapMode.NoWrap,
            firstVisiblePosition = 0,
            lastVisiblePosition = 0,
        )
  }

  /**
   * Test normal recycling flow: When a view is recycled and it's still the current view, it should
   * be properly cleaned up.
   *
   * Flow:
   * 1. Bind to View A
   * 2. Recycle View A Expected: View A is cleaned up (unbind + clean called)
   */
  @Test
  fun `onViewRecycled cleans up current view normally`() {
    val view = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // Simulate binding using proper scope
    with(item) { scope.onBindView(view) }

    // Simulate normal recycling (view is still the current view)
    item.onViewRecycled(view)

    // Verify view was cleaned up
    verify(view, times(1)).clean()
  }

  /**
   * Test change animation scenario: When an item is bound to a NEW view while the OLD view is still
   * animating, the old view should be cleaned up proactively during binding, not during
   * onViewRecycled.
   *
   * This test verifies:
   * 1. View A is cleaned when we bind to View B
   * 2. onViewRecycled(View A) skips cleanup (not current view)
   * 3. View B remains functional and is NOT accidentally cleaned
   *
   * Flow:
   * 1. Bind to View A
   * 2. Bind to View B (change animation started)
   * 3. Recycle View A (animation completed)
   * 4. Recycle View B (current view)
   *
   * Expected:
   * - View A is cleaned at step 2 (proactive cleanup)
   * - onViewRecycled(View A) at step 3 skips cleanup (already done)
   * - View B is only cleaned at step 4 (when it's recycled as current view)
   */
  @Test
  fun `change animation - old view cleaned proactively, current view remains functional`() {
    val viewA = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))
    val viewB = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // Step 1: Bind to View A
    with(item) { scope.onBindView(viewA) }

    // Step 2: Bind to View B (simulates change animation)
    // This should proactively clean up View A
    with(item) { scope.onBindView(viewB) }

    // Verify View A was cleaned during rebind
    verify(viewA, times(1)).clean()

    // CRITICAL: Verify View B was NOT accidentally cleaned
    verify(viewB, never()).clean()

    // Step 3: Recycle View A (animation completed)
    // This should be a no-op since View A was already cleaned
    item.onViewRecycled(viewA)

    // Verify View A.clean() was NOT called again (still only 1 time from step 2)
    verify(viewA, times(1)).clean()

    // Step 4: Now recycle View B (current view) - should work normally
    item.onViewRecycled(viewB)

    // Verify View B was cleaned when it's recycled as current view
    verify(viewB, times(1)).clean()
  }

  /**
   * Test that rebinding to the same view doesn't cause unnecessary cleanup.
   *
   * Flow:
   * 1. Bind to View A
   * 2. Bind to View A again (same view) Expected: No cleanup on rebind (same view check)
   */
  @Test
  fun `rebinding to same view does not trigger cleanup`() {
    val view = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // First bind
    with(item) { scope.onBindView(view) }

    // Clear invocations to verify rebind doesn't call clean
    org.mockito.Mockito.clearInvocations(view)

    // Rebind to same view
    with(item) { scope.onBindView(view) }

    // Verify clean was NOT called (same view, no cleanup needed)
    verify(view, never()).clean()
  }

  /**
   * Test memory cleanup verification: After recycling the current view, the internal state is
   * properly reset so that rebinding to the same view works like a fresh binding.
   *
   * Flow:
   * 1. Bind to View A
   * 2. Recycle View A (should set currentBoundView = null)
   * 3. Rebind to same View A
   *
   * Expected:
   * - View A is cleaned at step 2 (normal recycling)
   * - Step 3 behaves like first binding (no cleanup, previousView was null)
   */
  @Test
  fun `recycling current view resets state allowing clean rebind`() {
    val view = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // Step 1: Bind View A
    with(item) { scope.onBindView(view) }

    // Step 2: Recycle View A (should set currentBoundView = null internally)
    item.onViewRecycled(view)
    verify(view, times(1)).clean()

    // Clear invocations
    org.mockito.Mockito.clearInvocations(view)

    // Step 3: Rebind to same View A - should behave like first binding (no cleanup)
    with(item) { scope.onBindView(view) }

    // Verify no cleanup happened (previousView was null after recycling)
    verify(view, never()).clean()
  }

  /**
   * Edge case: Multiple recycles of the same non-current view should all be no-ops.
   *
   * Flow:
   * 1. Bind to View A
   * 2. Bind to View B (View A is cleaned proactively)
   * 3. Recycle View A multiple times
   *
   * Expected:
   * - View A is cleaned only once (during step 2)
   * - Multiple recycles of View A are all no-ops
   */
  @Test
  fun `multiple recycles of same non-current view are all no-ops`() {
    val viewA = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))
    val viewB = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // Bind A, then B (A is cleaned during rebind)
    with(item) { scope.onBindView(viewA) }
    with(item) { scope.onBindView(viewB) }

    // Verify A was cleaned once
    verify(viewA, times(1)).clean()

    // Recycle A multiple times
    item.onViewRecycled(viewA)
    item.onViewRecycled(viewA)
    item.onViewRecycled(viewA)

    // Should still only have been cleaned once (during rebind)
    verify(viewA, times(1)).clean()
  }

  /**
   * Test multiple rapid rebinds (simulates rapid change animations). This test verifies that the
   * current view remains functional throughout the rebinding process.
   *
   * Flow:
   * 1. Bind to View A
   * 2. Bind to View B (A is cleaned)
   * 3. Bind to View C (B is cleaned)
   * 4. Recycle View A and B (should be no-ops)
   * 5. Recycle View C (current view)
   *
   * Expected:
   * - View A cleaned at step 2
   * - View B cleaned at step 3
   * - View C is NOT cleaned until step 5
   * - Steps 4 are no-ops
   */
  @Test
  fun `multiple rapid rebinds clean up old views and preserve current view`() {
    val viewA = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))
    val viewB = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))
    val viewC = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // Step 1: Bind to View A
    with(item) { scope.onBindView(viewA) }

    // Step 2: Bind to View B (View A cleaned here)
    with(item) { scope.onBindView(viewB) }

    // Verify View A was cleaned
    verify(viewA, times(1)).clean()

    // Step 3: Bind to View C (View B cleaned here)
    with(item) { scope.onBindView(viewC) }

    // Verify View B was cleaned
    verify(viewB, times(1)).clean()

    // CRITICAL: Verify View C was NOT cleaned (still current)
    verify(viewC, never()).clean()

    // Clear invocations to verify recycle doesn't call clean again
    org.mockito.Mockito.clearInvocations(viewA, viewB)

    // Step 4: Recycle View A and B (should be no-ops)
    item.onViewRecycled(viewA)
    item.onViewRecycled(viewB)

    // Verify neither was cleaned again
    verify(viewA, never()).clean()
    verify(viewB, never()).clean()

    // Step 5: Now recycle View C (current view) - should work normally
    item.onViewRecycled(viewC)

    // Verify View C was finally cleaned
    verify(viewC, times(1)).clean()
  }

  /**
   * Test that the current view is properly cleaned when recycled after rebinds.
   *
   * Flow:
   * 1. Bind to View A
   * 2. Bind to View B
   * 3. Recycle View B (current view) Expected: View B is cleaned up (it's the current view)
   */
  @Test
  fun `current view is cleaned when recycled after rebinds`() {
    val viewA = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))
    val viewB = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // Bind to View A
    with(item) { scope.onBindView(viewA) }

    // Bind to View B
    with(item) { scope.onBindView(viewB) }

    // Clear invocations to verify onViewRecycled calls clean
    org.mockito.Mockito.clearInvocations(viewB)

    // Recycle View B (current view, should be cleaned)
    item.onViewRecycled(viewB)

    // Verify View B was cleaned
    verify(viewB, times(1)).clean()
  }

  /**
   * Test first binding (no previous view).
   *
   * Flow:
   * 1. Bind to View A (first bind, no previous view) Expected: No cleanup attempt (previousView is
   *    null)
   */
  @Test
  fun `first binding with no previous view works correctly`() {
    val view = spy(LithoRenderTreeView(lithoTestRule.context.androidContext))

    // First bind (no previous view)
    with(item) { scope.onBindView(view) }

    // Verify clean was never called during binding
    verify(view, never()).clean()
  }
}
