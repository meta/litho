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

package com.facebook.litho

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.core.height
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.testing.LithoStatsRule
import com.facebook.litho.testing.assertj.LithoViewAssert.Companion.assertThat
import com.facebook.litho.testing.atMost
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assumptions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowView

@RunWith(LithoTestRunner::class)
class LithoViewTest {

  @JvmField @Rule val expectedException = ExpectedException.none()
  @JvmField @Rule val lithoStatsRule: LithoStatsRule = LithoStatsRule()

  private lateinit var lithoView: LithoView
  private lateinit var initialComponent: Component

  @Before
  fun setup() {
    initialComponent =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component {
            return SimpleMountSpecTester(style = Style.width(100.px).height(100.px))
          }
        }
    lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(initialComponent)
  }

  @Test
  fun measureBeforeBeingAttached() {
    lithoView.measure(unspecified(), unspecified())
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)

    // View got measured.
    assertThat(lithoView.measuredWidth).isGreaterThan(0)
    assertThat(lithoView.measuredHeight).isGreaterThan(0)

    // Attaching will automatically mount since we already have a layout fitting our size.
    val shadow: ShadowView = Shadow.extract(lithoView) as ShadowView
    shadow.callOnAttachedToWindow()
    assertThat(getInternalMountItems(lithoView)).isEqualTo(2)
  }

  @Test
  fun testNullLithoViewDimensions() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            return null
          }
        }
    val nullLithoView = LithoView(getApplicationContext<Context>())
    nullLithoView.setComponent(component)
    nullLithoView.measure(unspecified(), unspecified())
    nullLithoView.layout(0, 0, nullLithoView.measuredWidth, nullLithoView.measuredHeight)
    assertThat(nullLithoView).hasMeasuredWidthOf(0).hasMeasuredHeightOf(0)
  }

  @Test
  fun testSuppressMeasureComponentTree() {
    val mockComponentTree: ComponentTree = mock()
    val width = 240
    val height = 400
    lithoView.componentTree = mockComponentTree
    lithoView.suppressMeasureComponentTree(true)
    lithoView.measure(exactly(width), exactly(height))
    verify(mockComponentTree, never()).measure(anyInt(), anyInt(), anyOrNull(), anyBoolean())
    assertThat(lithoView).hasMeasuredWidthOf(width).hasMeasuredHeightOf(height)
  }

  @Test
  fun testDontThrowWhenLayoutStateIsNull() {
    val mockComponentTree: ComponentTree = mock()
    lithoView.componentTree = mockComponentTree
    lithoView.requestLayout()
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.UNKNOWN)
  }

  /** This verifies that the width is 0 with normal layout params. */
  @Test
  fun measureWithLayoutParams() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component {
            return SimpleMountSpecTester(style = Style.widthPercent(100f).height(100.px))
          }
        }
    lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(component)
    lithoView.layoutParams = ViewGroup.LayoutParams(0, 200)
    lithoView.measure(unspecified(), exactly(200))
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)

    // View got measured.
    assertThat(lithoView.measuredWidth).isEqualTo(0)
    assertThat(lithoView.measuredHeight).isEqualTo(200)

    // Attaching will not mount anything as we have no width.
    val shadow: ShadowView = Shadow.extract(lithoView) as ShadowView
    shadow.callOnAttachedToWindow()

    // With no volume, ensure the component is not mounted.
    // When IM is blocked when rect is empty - nothing is mounted, so we expect 0 items.
    val totalExpectedMountedItems = 0
    assertThat(getInternalMountItems(lithoView)).isEqualTo(totalExpectedMountedItems)
  }

  /** This verifies that the width is correct with at most layout params. */
  @Test
  fun measureWithAtMostLayoutParams() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component {
            return SimpleMountSpecTester(style = Style.widthPercent(50f).heightPercent(10f))
          }
        }
    lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(component)
    lithoView.layoutParams = RecyclerViewLayoutManagerOverrideParams(atMost(100), atMost(200))
    lithoView.measure(unspecified(), unspecified())
    lithoView.layout(0, 0, lithoView.measuredWidth, lithoView.measuredHeight)

    // View got measured.
    assertThat(lithoView.measuredWidth).isEqualTo(50)
    assertThat(lithoView.measuredHeight).isEqualTo(20)

    // Attaching will automatically mount since we already have a layout fitting our size.
    val shadow: ShadowView = Shadow.extract(lithoView) as ShadowView
    shadow.callOnAttachedToWindow()
    assertThat(getInternalMountItems(lithoView)).isEqualTo(2)
  }

  @Test
  fun testMeasureDoesNotComputeLayoutStateWhenSpecsAreExact() {
    lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(SimpleMountSpecTester())
    lithoView.measure(exactly(100), exactly(100))
    assertThat(lithoView.measuredWidth).isEqualTo(100)
    assertThat(lithoView.measuredHeight).isEqualTo(100)
    assertThat(lithoView.componentTree?.mainThreadLayoutState).isNull()
    lithoView.layout(0, 0, 50, 50)
    val layoutState = lithoView.componentTree?.mainThreadLayoutState
    assertThat(layoutState).isNotNull
    assertThat(layoutState?.isCompatibleSize(50, 50)).isTrue
  }

  @Test
  fun testMeasureComputesLayoutStateWhenSpecsAreNotExact() {
    lithoView = LithoView(getApplicationContext<Context>())
    lithoView.setComponent(SimpleMountSpecTester(style = Style.height(100.px)))
    lithoView.measure(exactly(100), atMost(100))
    assertThat(lithoView.measuredWidth).isEqualTo(100)
    assertThat(lithoView.measuredHeight).isEqualTo(100)
    assertThat(lithoView.componentTree?.mainThreadLayoutState).isNotNull
  }

  @Test
  fun forceLayout_whenForceLayoutIsSet_recomputesLayout() {
    lithoView.measure(exactly(100), atMost(100))
    lithoView.forceRelayout()
    lithoView.measure(exactly(100), atMost(100))
    assertThat(lithoStatsRule.getComponentCalculateLayoutCount())
        .describedAs("Should force layout.")
        .isEqualTo(2)
    lithoView.measure(exactly(100), atMost(100))
    assertThat(lithoStatsRule.getComponentCalculateLayoutCount())
        .describedAs("Should only force layout one time.")
        .isEqualTo(2)
  }

  @Test
  fun forceLayout_whenForceLayoutIsNotSet_doesNotRecomputeLayout() {
    lithoView.measure(exactly(100), atMost(100))
    lithoView.measure(exactly(100), atMost(100))
    lithoView.measure(exactly(100), atMost(100))
    assertThat(lithoStatsRule.getComponentCalculateLayoutCount())
        .describedAs("Should only compute the first layout as other layouts are not forced.")
        .isEqualTo(1)
  }

  @Test
  fun forceLayout_whenForceLayoutIsSetAndHasExactMeasurements_recomputesLayout() {
    lithoView.measure(exactly(100), exactly(100))
    lithoView.layout(0, 0, 100, 100)
    lithoView.forceRelayout()
    lithoView.measure(exactly(100), exactly(100))
    lithoView.layout(0, 0, 100, 100)
    assertThat(lithoStatsRule.getComponentCalculateLayoutCount())
        .describedAs("Should force layout.")
        .isEqualTo(2)
    lithoView.measure(exactly(100), exactly(100))
    lithoView.layout(0, 0, 100, 100)
    assertThat(lithoStatsRule.getComponentCalculateLayoutCount())
        .describedAs("Should only force layout one time.")
        .isEqualTo(2)
  }

  @Test
  fun forceLayout_whenForceLayoutIsNotSetAndHasExactMeasurements_doesNotRecomputeLayout() {
    lithoView.measure(exactly(100), exactly(100))
    lithoView.layout(0, 0, 100, 100)
    lithoView.measure(exactly(100), exactly(100))
    lithoView.layout(0, 0, 100, 100)
    lithoView.measure(exactly(100), exactly(100))
    lithoView.layout(0, 0, 100, 100)
    assertThat(lithoStatsRule.getComponentCalculateLayoutCount())
        .describedAs("Should only force layout one time.")
        .isEqualTo(1)
  }

  @Test
  fun rootComponent_returnsRootComponentWhenSet_viaSetComponent() {
    assertThat(lithoView.rootComponent).isEqualTo(initialComponent)
    val newRootComponent: Component = SimpleMountSpecTester(style = Style.height(12345.px))
    lithoView.setComponent(newRootComponent)
    assertThat(lithoView.rootComponent).isEqualTo(newRootComponent)
  }

  @Test
  fun rootComponent_returnsRootComponentWhenSet_viaSetComponentTree() {
    assertThat(lithoView.rootComponent).isEqualTo(initialComponent)
    val c = lithoView.componentContext
    val newRootComponent: Component = SimpleMountSpecTester(style = Style.height(12345.px))
    lithoView.componentTree = ComponentTree.create(c, newRootComponent).build()
    assertThat(lithoView.rootComponent).isEqualTo(newRootComponent)
  }

  @Test
  fun rootComponent_returnsNullComponentWhenNoComponentSet() {
    lithoView = LithoView(getApplicationContext<Context>())
    assertThat(lithoView.rootComponent).isNull()
  }

  // Measure + layout is necessary to ensure async operations have finished come assertion point
  @Test
  fun rootComponent_returnsNullWhenNoComponent_viaSetComponentTree() {
    Assumptions.assumeThat(lithoView.rootComponent).isEqualTo(initialComponent)
    lithoView.componentTree = null
    // Measure + layout is necessary to ensure async operations have finished come assertion point
    lithoView.measure(unspecified(), unspecified())
    lithoView.layout(0, 0, 50, 50)
    assertThat(lithoView.rootComponent).isNull()
  }

  // Measure + layout is necessary to ensure async operations have finished come assertion point
  @Test
  fun rootComponent_returnsEmptyComponentWhenNoComponent_viaSetComponent() {
    Assumptions.assumeThat(lithoView.rootComponent).isEqualTo(initialComponent)
    lithoView.setComponent(null)
    // Measure + layout is necessary to ensure async operations have finished come assertion point
    lithoView.measure(unspecified(), unspecified())
    lithoView.layout(0, 0, 50, 50)
    assertThat(lithoView.rootComponent).isInstanceOf(EmptyComponent::class.java)
  }

  @Test
  @Suppress("DEPRECATION")
  fun constructor_withComponentTree_showsTheTreeWithItsContext() {
    val tree =
        ComponentTree.create(ComponentContext(getApplicationContext<Context>()), initialComponent)
            .build()
    lithoView = LithoView(tree)
    assertThat(lithoView.componentTree).isSameAs(tree)
    assertThat(lithoView.context).isSameAs(tree.context.androidContext)
    assertThat(lithoView.componentContext).isNotSameAs(tree.context)
  }

  private class RecyclerViewLayoutManagerOverrideParams(
      _widthMeasureSpec: Int,
      _heightMeasureSpec: Int,
  ) : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT), LayoutManagerOverrideParams {
    override val widthMeasureSpec: Int = _widthMeasureSpec
    override val heightMeasureSpec: Int = _heightMeasureSpec
  }

  companion object {
    private fun getInternalMountItems(lithoView: LithoView): Int {
      return lithoView.mountDelegateTarget.getMountItemCount()
    }
  }
}
