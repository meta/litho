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

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.collection.SparseArrayCompat
import com.facebook.litho.LithoLayoutData.Companion.NO_Z_INDEX
import com.facebook.litho.MountSpecLithoRenderUnit.UpdateState
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.drawable.ComparableColorDrawable
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestComponent
import com.facebook.litho.testing.TestDrawableComponent
import com.facebook.litho.testing.TestSizeDependentComponent
import com.facebook.litho.testing.Whitebox
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.Text
import com.facebook.rendercore.MountItem
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.SizeConstraints
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaEdge
import java.lang.Exception
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.LooperMode

@RunWith(LithoTestRunner::class)
@LooperMode(LooperMode.Mode.LEGACY)
class TreeDiffingTest {

  @JvmField
  @Rule
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(shouldAddHostViewForRootComponent = true),
  )

  @Before
  @Throws(Exception::class)
  fun setup() {
    RedDrawable = ComparableColorDrawable.create(Color.RED)
    BlackDrawable = ComparableColorDrawable.create(Color.BLACK)
    TransparentDrawable = ComparableColorDrawable.create(Color.TRANSPARENT)
  }

  @Test
  fun testLayoutStateDiffing() {
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component {
            return Column.create(c)
                .child(TestDrawableComponent.create(c))
                .child(Column.create(c).child(TestDrawableComponent.create(c)))
                .build()
          }
        }
    val context = lithoTestRule.createTestLithoView().componentTree.context
    val layoutState =
        calculateLayoutStateWithDiffing(context, component, exactly(350), exactly(200), null)

    // Check diff tree is not null and consistent.
    val node = layoutState.diffTree
    assertThat(node).isNotNull
    assertThat(4).isEqualTo(countNodes(requireNotNull(node)))
  }

  @Test
  fun testCachedMeasureFunction() {
    val c = lithoTestRule.context
    val component0: Component = Text.create(c).text("hello-world").build()
    val testLithoView = lithoTestRule.render { component0 }
    val diffNode = testLithoView.committedLayoutState?.diffTree
    val component1: Component = Text.create(c).text("hello-world").build()
    val result = lithoTestRule.render { component1 }.currentRootNode
    assertThat(result?.width).isEqualTo(diffNode?.lastMeasuredWidth)
    assertThat(result?.height).isEqualTo(diffNode?.lastMeasuredHeight)
  }

  @Test
  fun tesLastConstraints() {
    val c = lithoTestRule.context
    val component0: Component = Text.create(c).text("hello-world").build()
    val diffNode = lithoTestRule.render { component0 }.committedLayoutState?.diffTree
    val component1: Component = Text.create(c).text("hello-world").build()
    val result = lithoTestRule.render { component1 }.currentRootNode
    assertThat(diffNode?.lastWidthSpec).isEqualTo(result?.widthSpec)
    assertThat(diffNode?.lastHeightSpec).isEqualTo(result?.heightSpec)
  }

  @Test
  fun testCachedMeasures() {
    val c = lithoTestRule.context
    val component0: Component =
        Column.create(c)
            .child(Text.create(c).text("hello-world").build())
            .child(Text.create(c).text("hello-world").build())
            .build()
    val testLithoView = lithoTestRule.render { component0 }
    val component1: Component =
        Column.create(c)
            .child(Text.create(c).text("hello-world").build())
            .child(Text.create(c).text("hello-world").build())
            .build()
    val result = testLithoView.setRoot(component1).currentRootNode
    assertThat(result?.getChildAt(0)?.cachedMeasuresValid).isTrue
    assertThat(result?.getChildAt(1)?.cachedMeasuresValid).isTrue
  }

  @Test
  fun testPartiallyCachedMeasures() {
    val c = lithoTestRule.createTestLithoView().componentTree.context
    val component0: Component =
        Column.create(c)
            .child(Text.create(c).text("hello-world-1").build())
            .child(Text.create(c).text("hello-world-2").build())
            .build()
    val testLithoView = lithoTestRule.render { component0 }
    val component1: Component =
        Column.create(c)
            .child(Text.create(c).text("hello-world-1").build())
            .child(Text.create(c).text("hello-world-3").build())
            .build()
    val result = testLithoView.setRoot(component1).currentRootNode
    assertThat(result?.getChildAt(0)?.cachedMeasuresValid).isTrue
    assertThat(result?.getChildAt(1)?.cachedMeasuresValid).isFalse
  }

  @Test
  fun testLayoutOutputReuse() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12345
    val component1: Component =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component {
            return Column.create(c)
                .child(TestDrawableComponent.create(c))
                .child(Column.create(c).child(TestDrawableComponent.create(c)))
                .build()
          }
        }
    val component2: Component =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component {
            return Column.create(c)
                .child(TestDrawableComponent.create(c))
                .child(Column.create(c).child(TestDrawableComponent.create(c)))
                .build()
          }
        }
    val context = lithoTestRule.createTestLithoView().componentTree.context
    val prevLayoutState =
        calculateLayoutStateWithDiffing(context, component1, exactly(350), exactly(200), null)
    val layoutState = calculateLayoutStateWithDiffing(
        context,
        component2,
        exactly(350),
        exactly(200),
        prevLayoutState,
    )
    assertThat(layoutState.getMountableOutputCount())
        .isEqualTo(prevLayoutState.getMountableOutputCount())
    var i = 0
    val count = prevLayoutState.getMountableOutputCount()
    while (i < count) {
      assertThat(layoutState.getMountableOutputAt(i).renderUnit.id)
          .isEqualTo(prevLayoutState.getMountableOutputAt(i).renderUnit.id)
      i++
    }
  }

  @Test
  fun testLayoutOutputPartialReuse() {
    // Needed because of (legacy) usage two different InlineLayoutSpec that the test wants to treat
    // as the same component type.
    val COMPONENT_IDENTITY = 12345
    val component1: Component =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component {
            return Column.create(c)
                .child(TestDrawableComponent.create(c))
                .child(Column.create(c).child(TestDrawableComponent.create(c)))
                .build()
          }
        }
    val component2: Component =
        object : InlineLayoutSpec(COMPONENT_IDENTITY) {
          override fun onCreateLayout(c: ComponentContext): Component {
            return Column.create(c)
                .child(TestDrawableComponent.create(c))
                .child(Column.create(c).child(TestDrawableComponent.create(c)))
                .child(TestDrawableComponent.create(c))
                .build()
          }
        }
    val context = lithoTestRule.createTestLithoView().componentTree.context
    val prevLayoutState =
        calculateLayoutStateWithDiffing(context, component1, exactly(350), exactly(200), null)
    val layoutState = calculateLayoutStateWithDiffing(
        context,
        component2,
        exactly(350),
        exactly(200),
        prevLayoutState,
    )
    Assert.assertNotEquals(
        prevLayoutState.getMountableOutputCount().toLong(),
        layoutState.getMountableOutputCount().toLong(),
    )
    var i = 0
    val count = prevLayoutState.getMountableOutputCount()
    while (i < count) {
      assertThat(layoutState.getMountableOutputAt(i).renderUnit.id)
          .describedAs("Output $i")
          .isEqualTo(prevLayoutState.getMountableOutputAt(i).renderUnit.id)
      i++
    }
  }

  private fun assertCachedMeasurementsNotDefined(node: LithoLayoutResult) {
    assertThat(node.cachedMeasuresValid).isFalse
  }

  private fun checkAllComponentsHaveMeasureCache(node: LithoLayoutResult) {
    if (node.node.tailComponent != null && node.node.tailComponent.canMeasure()) {
      assertCachedMeasurementsDefined(node)
    }
    val numChildren = node.childCount
    for (i in 0 until numChildren) {
      checkAllComponentsHaveMeasureCache(node.getChildAt(i))
    }
  }

  @Test
  fun testComponentHostMoveItem() {
    val c = lithoTestRule.context
    val hostHolder = ComponentHost(c)
    val mountItem: MountItem = mock()
    whenever(mountItem.renderTreeNode).thenReturn(createNode(Column.create(c).build()))
    val mountItem1: MountItem = mock()
    whenever(mountItem1.renderTreeNode).thenReturn(createNode(Column.create(c).build()))
    val mountItem2: MountItem = mock()
    whenever(mountItem2.renderTreeNode).thenReturn(createNode(Column.create(c).build()))
    hostHolder.mount(0, mountItem, Rect())
    hostHolder.mount(1, mountItem1, Rect())
    hostHolder.mount(2, mountItem2, Rect())
    assertThat(mountItem).isEqualTo(hostHolder.getMountItemAt(0))
    assertThat(mountItem1).isEqualTo(hostHolder.getMountItemAt(1))
    assertThat(mountItem2).isEqualTo(hostHolder.getMountItemAt(2))
    hostHolder.moveItem(mountItem, 0, 2)
    hostHolder.moveItem(mountItem2, 2, 0)
    assertThat(mountItem2).isEqualTo(hostHolder.getMountItemAt(0))
    assertThat(mountItem1).isEqualTo(hostHolder.getMountItemAt(1))
    assertThat(mountItem).isEqualTo(hostHolder.getMountItemAt(2))
  }

  @Test
  fun testComponentHostMoveItemPartial() {
    val c = lithoTestRule.context
    val hostHolder = ComponentHost(c)
    val mountItem: MountItem = mock()
    whenever(mountItem.renderTreeNode).thenReturn(createNode(Column.create(c).build()))
    val mountItem1: MountItem = mock()
    whenever(mountItem1.renderTreeNode).thenReturn(createNode(Column.create(c).build()))
    val mountItem2: MountItem = mock()
    whenever(mountItem2.renderTreeNode).thenReturn(createNode(Column.create(c).build()))
    hostHolder.mount(0, mountItem, Rect())
    hostHolder.mount(1, mountItem1, Rect())
    hostHolder.mount(2, mountItem2, Rect())
    assertThat(mountItem).isEqualTo(hostHolder.getMountItemAt(0))
    assertThat(mountItem1).isEqualTo(hostHolder.getMountItemAt(1))
    assertThat(mountItem2).isEqualTo(hostHolder.getMountItemAt(2))
    hostHolder.moveItem(mountItem2, 2, 0)
    assertThat(mountItem2).isEqualTo(hostHolder.getMountItemAt(0))
    assertThat(mountItem1).isEqualTo(hostHolder.getMountItemAt(1))
    assertThat(1)
        .isEqualTo(
            (Whitebox.getInternalState<Any>(hostHolder, "scrapMountItemsArray")
                    as? SparseArrayCompat<MountItem?>)
                ?.size(),
        )
    hostHolder.unmount(0, mountItem)
    assertThat(2)
        .isEqualTo(
            (Whitebox.getInternalState<Any>(hostHolder, "mountItems")
                    as? SparseArrayCompat<MountItem?>)
                ?.size(),
        )
    assertThat(Whitebox.getInternalState<Any?>(hostHolder, "scrapMountItemsArray")).isNull()
  }

  @Test
  fun testLayoutOutputUpdateState() {
    val c = lithoTestRule.context
    val firstComponent: Component = TestDrawableComponent.create(c).color(Color.BLACK).build()
    val secondComponent: Component = TestDrawableComponent.create(c).color(Color.BLACK).build()
    val thirdComponent: Component = TestDrawableComponent.create(c).color(Color.WHITE).build()
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { firstComponent }
    val state = testLithoView.componentTree.mainThreadLayoutState
    assertOutputsState(state, MountSpecLithoRenderUnit.STATE_UNKNOWN)
    testLithoView.setRoot(secondComponent)
    val secondState = testLithoView.componentTree.mainThreadLayoutState
    assertOutputsState(secondState, MountSpecLithoRenderUnit.STATE_UPDATED)
    testLithoView.setRoot(thirdComponent)
    val thirdState = testLithoView.componentTree.mainThreadLayoutState
    assertOutputsState(thirdState, MountSpecLithoRenderUnit.STATE_DIRTY)
  }

  @Test
  fun testLayoutOutputUpdateStateWithBackground() {
    val component1: Component = TestLayoutSpecBgState(false)
    val component2: Component = TestLayoutSpecBgState(false)
    val component3: Component = TestLayoutSpecBgState(true)
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { component1 }
    val state = testLithoView.componentTree.mainThreadLayoutState
    assertOutputsState(state, MountSpecLithoRenderUnit.STATE_UNKNOWN)
    testLithoView.setRoot(component2)
    val secondState = testLithoView.componentTree.mainThreadLayoutState
    assertThat(secondState?.getMountableOutputCount()).isEqualTo(4)
    testLithoView.setRoot(component3)
    val thirdState = testLithoView.componentTree.mainThreadLayoutState
    assertThat(thirdState?.getMountableOutputCount()).isEqualTo(4)
    assertThat(
        MountSpecLithoRenderUnit.getUpdateState(
            requireNotNull(thirdState?.getMountableOutputAt(2)),
        ),
    )
        .isEqualTo(MountSpecLithoRenderUnit.STATE_UPDATED)
    assertThat(
        MountSpecLithoRenderUnit.getUpdateState(
            requireNotNull(thirdState?.getMountableOutputAt(3)),
        ),
    )
        .isEqualTo(MountSpecLithoRenderUnit.STATE_UPDATED)
  }

  // This test covers the same case with the foreground since the code path is the same!
  @Test
  fun testLayoutOutputUpdateStateWithBackgroundInWithLayout() {
    val component1: Component = TestLayoutSpecInnerState(false)
    val component2: Component = TestLayoutSpecInnerState(false)
    val component3: Component = TestLayoutSpecInnerState(true)
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { component1 }
    val state = testLithoView.componentTree.mainThreadLayoutState
    assertThat(
        MountSpecLithoRenderUnit.getUpdateState(requireNotNull(state?.getMountableOutputAt(2))),
    )
        .isEqualTo(MountSpecLithoRenderUnit.STATE_UNKNOWN)
    testLithoView.setRoot(component2)
    val secondState = testLithoView.componentTree.mainThreadLayoutState
    assertThat(
        MountSpecLithoRenderUnit.getUpdateState(
            requireNotNull(secondState?.getMountableOutputAt(2)),
        ),
    )
        .isEqualTo(MountSpecLithoRenderUnit.STATE_UNKNOWN)
    assertThat(
        MountSpecLithoRenderUnit.getUpdateState(
            requireNotNull(secondState?.getMountableOutputAt(3)),
        ),
    )
        .isEqualTo(MountSpecLithoRenderUnit.STATE_UPDATED)
    testLithoView.setRoot(component3)
    val thirdState = testLithoView.componentTree.mainThreadLayoutState
    assertThat(
        MountSpecLithoRenderUnit.getUpdateState(
            requireNotNull(thirdState?.getMountableOutputAt(2)),
        ),
    )
        .isEqualTo(MountSpecLithoRenderUnit.STATE_UNKNOWN)
    assertThat(
        MountSpecLithoRenderUnit.getUpdateState(
            requireNotNull(thirdState?.getMountableOutputAt(3)),
        ),
    )
        .isEqualTo(MountSpecLithoRenderUnit.STATE_DIRTY)
  }

  @Test
  fun testLayoutOutputUpdateStateIdClash() {
    val component1: Component = TestLayoutWithStateIdClash(false)
    val component2: Component = TestLayoutWithStateIdClash(true)
    val testLithoView =
        lithoTestRule.render(widthSpec = exactly(10), heightSpec = exactly(10)) { component1 }
    val state = testLithoView.componentTree.mainThreadLayoutState
    assertOutputsState(state, MountSpecLithoRenderUnit.STATE_UNKNOWN)
    testLithoView.setRoot(component2)
    val secondState = testLithoView.componentTree.mainThreadLayoutState
    assertThat(6).isEqualTo(secondState?.getMountableOutputCount())
    assertThat(MountSpecLithoRenderUnit.STATE_DIRTY)
        .isEqualTo(
            MountSpecLithoRenderUnit.getUpdateState(
                requireNotNull(secondState?.getMountableOutputAt(0)),
            ),
        )
    assertThat(MountSpecLithoRenderUnit.STATE_UNKNOWN)
        .isEqualTo(
            MountSpecLithoRenderUnit.getUpdateState(
                requireNotNull(secondState?.getMountableOutputAt(1)),
            ),
        )
    assertThat(MountSpecLithoRenderUnit.STATE_UPDATED)
        .isEqualTo(
            MountSpecLithoRenderUnit.getUpdateState(
                requireNotNull(secondState?.getMountableOutputAt(2)),
            ),
        )
    assertThat(MountSpecLithoRenderUnit.STATE_UNKNOWN)
        .isEqualTo(
            MountSpecLithoRenderUnit.getUpdateState(
                requireNotNull(secondState?.getMountableOutputAt(3)),
            ),
        )
    assertThat(MountSpecLithoRenderUnit.STATE_UNKNOWN)
        .isEqualTo(
            MountSpecLithoRenderUnit.getUpdateState(
                requireNotNull(secondState?.getMountableOutputAt(4)),
            ),
        )
    assertThat(MountSpecLithoRenderUnit.STATE_UPDATED)
        .isEqualTo(
            MountSpecLithoRenderUnit.getUpdateState(
                requireNotNull(secondState?.getMountableOutputAt(5)),
            ),
        )
  }

  @Test
  fun testDiffTreeUsedIfRootMeasureSpecsAreDifferentButChildHasSame() {
    val c = lithoTestRule.createTestLithoView().componentTree.context
    val component: TestComponent = TestDrawableComponent.create(c).color(Color.BLACK).build()
    val layoutComponent: Component = TestSimpleContainerLayout2(component)
    val firstLayoutState =
        calculateLayoutStateWithDiffing(c, layoutComponent, exactly(100), exactly(100), null)
    assertThat(component.wasMeasureCalled()).isTrue
    val secondComponent: TestComponent = TestDrawableComponent.create(c).color(Color.BLACK).build()
    val secondLayoutComponent: Component = TestSimpleContainerLayout2(secondComponent)
    calculateLayoutStateWithDiffing(
        c,
        secondLayoutComponent,
        exactly(100),
        exactly(90),
        firstLayoutState,
    )
    assertThat(secondComponent.wasMeasureCalled()).isFalse
  }

  @Test
  fun testDiffTreeUsedIfMeasureSpecsAreSame() {
    val c = lithoTestRule.createTestLithoView().componentTree.context
    val component: TestComponent = TestDrawableComponent.create(c).color(Color.BLACK).build()
    val layoutComponent: Component = TestSimpleContainerLayout(component, 0)
    val firstLayoutState =
        calculateLayoutStateWithDiffing(c, layoutComponent, exactly(100), exactly(100), null)
    assertThat(component.wasMeasureCalled()).isTrue
    val secondComponent: TestComponent = TestDrawableComponent.create(c).color(Color.BLACK).build()
    val secondLayoutComponent: Component = TestSimpleContainerLayout(secondComponent, 0)
    calculateLayoutStateWithDiffing(
        c,
        secondLayoutComponent,
        exactly(100),
        exactly(100),
        firstLayoutState,
    )
    assertThat(secondComponent.wasMeasureCalled()).isFalse
  }

  @Test
  fun testCachedMeasuresForNestedTreeComponentDelegateWithUndefinedSize() {
    val component1: Component = TestNestedTreeDelegateWithUndefinedSizeLayout()
    val component2: Component = TestNestedTreeDelegateWithUndefinedSizeLayout()
    val context = lithoTestRule.createTestLithoView().componentTree.context
    val prevLayoutState =
        calculateLayoutStateWithDiffing(context, component1, exactly(350), exactly(200), null)
    val layoutState = calculateLayoutStateWithDiffing(
        context,
        component2,
        exactly(350),
        exactly(200),
        prevLayoutState,
    )

    // The nested root measure() was called in the first layout calculation.
    val prevNestedRoot =
        LithoRenderUnit.getRenderUnit(prevLayoutState.getMountableOutputAt(2)).component
            as TestComponent
    assertThat(prevNestedRoot.wasMeasureCalled()).isTrue
    val nestedRoot =
        LithoRenderUnit.getRenderUnit(layoutState.getMountableOutputAt(2)).component
            as TestComponent
    assertThat(nestedRoot.wasMeasureCalled()).isFalse
  }

  @Test
  fun testCachedMeasuresForNestedTreeComponentWithUndefinedSize() {
    val component1: Component = TestUndefinedSizeLayout()
    val component2: Component = TestUndefinedSizeLayout()
    val context = lithoTestRule.createTestLithoView().componentTree.context
    val prevLayoutState =
        calculateLayoutStateWithDiffing(context, component1, exactly(350), exactly(200), null)
    val layoutState = calculateLayoutStateWithDiffing(
        context,
        component2,
        exactly(350),
        exactly(200),
        prevLayoutState,
    )

    // The nested root measure() was called in the first layout calculation.
    val prevMainTreeLeaf =
        LithoRenderUnit.getRenderUnit(prevLayoutState.getMountableOutputAt(1)).component
            as TestComponent
    assertThat(prevMainTreeLeaf.wasMeasureCalled()).isTrue
    val prevNestedLeaf1 =
        LithoRenderUnit.getRenderUnit(prevLayoutState.getMountableOutputAt(3)).component
            as TestComponent
    assertThat(prevNestedLeaf1.wasMeasureCalled()).isTrue
    val prevNestedLeaf2 =
        LithoRenderUnit.getRenderUnit(prevLayoutState.getMountableOutputAt(4)).component
            as TestComponent
    assertThat(prevNestedLeaf2.wasMeasureCalled()).isTrue
    val mainTreeLeaf =
        LithoRenderUnit.getRenderUnit(layoutState.getMountableOutputAt(1)).component
            as TestComponent
    assertThat(mainTreeLeaf.wasMeasureCalled()).isFalse
    val nestedLeaf1 =
        LithoRenderUnit.getRenderUnit(layoutState.getMountableOutputAt(3)).component
            as TestComponent
    assertThat(nestedLeaf1.wasMeasureCalled()).isFalse
    val nestedLeaf2 =
        LithoRenderUnit.getRenderUnit(layoutState.getMountableOutputAt(4)).component
            as TestComponent
    assertThat(nestedLeaf2.wasMeasureCalled()).isFalse
  }

  private class TestLayoutSpec internal constructor(private val addThirdChild: Boolean) :
      InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .child(TestDrawableComponent.create(c))
          .child(Column.create(c).child(TestDrawableComponent.create(c)))
          .child(if (addThirdChild) TestDrawableComponent.create(c) else null)
          .build()
    }
  }

  private class TestSimpleContainerLayout
  internal constructor(private val component: Component, private val horizontalPadding: Int) :
      InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .paddingPx(YogaEdge.HORIZONTAL, horizontalPadding)
          .child(component)
          .build()
    }
  }

  private class TestSimpleContainerLayout2 internal constructor(private val component: Component) :
      InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .alignItems(YogaAlign.FLEX_START)
          .child(Wrapper.create(c).delegate(component).heightPx(50))
          .build()
    }
  }

  private class TestLayoutSpecInnerState
  internal constructor(private val changeChildDrawable: Boolean) : InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .background(RedDrawable)
          .foregroundRes(android.R.drawable.btn_default)
          .child(
              TestDrawableComponent.create(c)
                  .background(if (changeChildDrawable) RedDrawable else BlackDrawable),
          )
          .child(Column.create(c).child(TestDrawableComponent.create(c)))
          .build()
    }
  }

  private class TestLayoutSpecBgState internal constructor(private val changeBg: Boolean) :
      InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .background(if (changeBg) BlackDrawable else RedDrawable)
          .foreground(TransparentDrawable)
          .child(TestDrawableComponent.create(c))
          .child(Column.create(c).child(TestDrawableComponent.create(c)))
          .build()
    }
  }

  private class TestUndefinedSizeLayout : InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .paddingPx(YogaEdge.ALL, 2)
          .child(TestDrawableComponent.create(c, true, true, false))
          .child(
              TestSizeDependentComponent.create(c)
                  .setDelegate(false)
                  .flexShrink(0f)
                  .marginPx(YogaEdge.ALL, 11),
          )
          .build()
    }
  }

  private class TestNestedTreeDelegateWithUndefinedSizeLayout : InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .paddingPx(YogaEdge.ALL, 2)
          .child(TestSizeDependentComponent.create(c).setDelegate(true).marginPx(YogaEdge.ALL, 11))
          .build()
    }
  }

  private class TestLayoutWithStateIdClash internal constructor(private val addChild: Boolean) :
      InlineLayoutSpec() {
    override fun onCreateLayout(c: ComponentContext): Component {
      return Column.create(c)
          .child(
              Column.create(c)
                  .wrapInView()
                  .child(TestDrawableComponent.create(c))
                  .child(if (addChild) TestDrawableComponent.create(c) else null),
          )
          .child(Column.create(c).wrapInView().child(TestDrawableComponent.create(c)))
          .build()
    }
  }

  private fun createNode(component: Component): RenderTreeNode {
    val unit: LithoRenderUnit =
        MountSpecLithoRenderUnit.create(
            0,
            component,
            null,
            lithoTestRule.context,
            null,
            0,
            0,
            MountSpecLithoRenderUnit.STATE_UNKNOWN,
            0,
            null,
        )
    return create(
        unit,
        Rect(),
        null,
        LithoLayoutData(
            0,
            0,
            null,
            NO_Z_INDEX,
            null,
            if (component is SpecGeneratedComponent) {
              component.isMountSizeDependent
            } else {
              false
            },
            null,
        ),
        null,
    )
  }

  companion object {

    private lateinit var RedDrawable: Drawable
    private lateinit var BlackDrawable: Drawable
    private lateinit var TransparentDrawable: Drawable

    private fun countNodes(node: DiffNode): Int {
      var sum = 1
      for (i in 0 until node.childCount) {
        val child = node.getChildAt(i)
        if (child != null) {
          sum += countNodes(child)
        }
      }
      return sum
    }

    private fun calculateLayoutState(
        context: ComponentContext,
        component: Component,
        widthSpec: Int,
        heightSpec: Int,
    ): LayoutState {
      val result =
          ResolveTreeFuture.resolve(context, component, TreeState(), -1, -1, null, null, null)
      return LayoutTreeFuture.layout(
          result,
          SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec),
          -1,
          -1,
          TreeState(result.treeState),
          null,
          null,
          null,
      )
    }

    private fun calculateLayoutStateWithDiffing(
        context: ComponentContext,
        component: Component,
        widthSpec: Int,
        heightSpec: Int,
        previousLayoutState: LayoutState?,
    ): LayoutState {
      val result =
          ResolveTreeFuture.resolve(
              context,
              component,
              TreeState(),
              -1,
              -1,
              previousLayoutState?.resolveResult,
              null,
              null,
          )
      return LayoutTreeFuture.layout(
          result,
          SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec),
          -1,
          -1,
          TreeState(result.treeState),
          previousLayoutState,
          previousLayoutState?.diffTree,
          null,
      )
    }

    private fun assertOutputsState(layoutState: LayoutState?, @UpdateState state: Int) {
      assertThat(MountSpecLithoRenderUnit.STATE_DIRTY)
          .isEqualTo(
              MountSpecLithoRenderUnit.getUpdateState(
                  requireNotNull(layoutState?.getMountableOutputAt(0)),
              ),
          )
      for (i in 1 until (layoutState?.getMountableOutputCount() ?: 0)) {
        assertThat(state)
            .isEqualTo(
                MountSpecLithoRenderUnit.getUpdateState(
                    requireNotNull(layoutState?.getMountableOutputAt(i)),
                ),
            )
      }
    }

    private fun assertCachedMeasurementsDefined(node: LithoLayoutResult) {
      val diffHeight: Int = node.diffNode?.lastMeasuredHeight ?: -1
      val diffWidth: Int = node.diffNode?.lastMeasuredWidth ?: -1
      assertThat(diffHeight != -1).isTrue
      assertThat(diffWidth != -1).isTrue
      assertThat(node.cachedMeasuresValid).isTrue
    }
  }
}
