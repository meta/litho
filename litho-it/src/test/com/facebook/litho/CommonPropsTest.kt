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

import android.animation.StateListAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Shader
import android.util.SparseArray
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.annotations.ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_AUTO
import com.facebook.litho.drawable.ComparableColorDrawable
import com.facebook.litho.it.R.drawable.background_with_padding
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestViewComponent
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.Text
import com.facebook.rendercore.visibility.FocusedVisibleEvent
import com.facebook.rendercore.visibility.FullImpressionVisibleEvent
import com.facebook.rendercore.visibility.InvisibleEvent
import com.facebook.rendercore.visibility.UnfocusedVisibleEvent
import com.facebook.rendercore.visibility.VisibilityChangedEvent
import com.facebook.rendercore.visibility.VisibleEvent
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaDirection
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaPositionType
import com.facebook.yoga.YogaUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class CommonPropsTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()
  private lateinit var node: LithoNode
  private lateinit var commonProps: CommonProps
  private lateinit var componentContext: ComponentContext

  private lateinit var content: Component
  private lateinit var dispatchInfo: EventDispatchInfo

  @Before
  fun setup() {
    node = LithoNode()
    componentContext = ComponentContext(ApplicationProvider.getApplicationContext<Context>())
    content = TestViewComponent.create(componentContext).build()
    dispatchInfo = EventDispatchInfo(content as HasEventDispatcher, componentContext)
    commonProps = CommonProps()
  }

  @Test
  fun testSetPropsAndBuild() {
    commonProps.layoutDirection(YogaDirection.INHERIT.toLayoutDirection())
    commonProps.alignSelf(YogaAlign.AUTO)
    commonProps.positionType(YogaPositionType.ABSOLUTE)
    commonProps.flex(2f)
    commonProps.flexGrow(3f)
    commonProps.flexShrink(4f)
    commonProps.flexBasisPx(5)
    commonProps.flexBasisPercent(6f)
    commonProps.importantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO)
    commonProps.duplicateParentState(false)
    commonProps.marginPx(YogaEdge.ALL, 5)
    commonProps.marginPercent(YogaEdge.VERTICAL, 12f)
    commonProps.marginPercent(YogaEdge.RIGHT, 5f)
    commonProps.marginAuto(YogaEdge.LEFT)
    commonProps.paddingPx(YogaEdge.ALL, 1)
    commonProps.paddingPercent(YogaEdge.VERTICAL, 7f)
    commonProps.border(
        Border.create(componentContext)
            .widthPx(YogaEdge.ALL, 1)
            .color(YogaEdge.ALL, Color.RED)
            .build(),
    )
    commonProps.positionPx(YogaEdge.ALL, 11)
    commonProps.positionPx(YogaEdge.RIGHT, 12)
    commonProps.positionPercent(YogaEdge.VERTICAL, 17f)
    commonProps.widthPx(5)
    commonProps.minWidthPercent(100f)
    commonProps.maxWidthPx(25)
    commonProps.heightPx(30)
    commonProps.minHeightPercent(33f)
    commonProps.maxHeightPx(34)
    commonProps.aspectRatio(20f)
    commonProps.touchExpansionPx(YogaEdge.RIGHT, 22)
    commonProps.touchExpansionPx(YogaEdge.LEFT, 23)
    commonProps.touchExpansionPx(YogaEdge.ALL, 21)
    val background = ComparableColorDrawable.create(Color.RED)
    commonProps.background(background)
    val foreground = ComparableColorDrawable.create(Color.BLACK)
    commonProps.foreground(foreground)
    commonProps.wrapInView()
    val clickHandler: EventHandler<ClickEvent> = eventHandler {}
    val longClickHandler: EventHandler<LongClickEvent> = eventHandler {}
    val touchHandler: EventHandler<TouchEvent> = eventHandler {}
    val interceptTouchHandler: EventHandler<InterceptTouchEvent> = eventHandler {}
    val focusChangedHandler: EventHandler<FocusChangedEvent> = eventHandler {}
    commonProps.clickHandler(clickHandler)
    commonProps.focusChangeHandler(focusChangedHandler)
    commonProps.longClickHandler(longClickHandler)
    commonProps.touchHandler(touchHandler)
    commonProps.interceptTouchHandler(interceptTouchHandler)
    commonProps.focusable(true)
    commonProps.clickable(true)
    commonProps.selected(false)
    commonProps.enabled(false)
    commonProps.keyboardNavigationCluster(true)
    commonProps.tooltipText("test")
    commonProps.renderEffect(LithoRenderEffect.Blur(1f, 1f, Shader.TileMode.CLAMP))
    commonProps.visibleHeightRatio(55f)
    commonProps.visibleWidthRatio(56f)
    commonProps.accessibilityHeading(false)
    commonProps.screenReaderFocusable(true)
    val visibleHandler: EventHandler<VisibleEvent> = eventHandler {}
    val focusedHandler: EventHandler<FocusedVisibleEvent> = eventHandler {}
    val unfocusedHandler: EventHandler<UnfocusedVisibleEvent> = eventHandler {}
    val fullImpressionHandler: EventHandler<FullImpressionVisibleEvent> = eventHandler {}
    val invisibleHandler: EventHandler<InvisibleEvent> = eventHandler {}
    val visibleRectChangedHandler: EventHandler<VisibilityChangedEvent> = eventHandler {}
    commonProps.visibleHandler(visibleHandler)
    commonProps.focusedHandler(focusedHandler)
    commonProps.unfocusedHandler(unfocusedHandler)
    commonProps.fullImpressionHandler(fullImpressionHandler)
    commonProps.invisibleHandler(invisibleHandler)
    commonProps.visibilityChangedHandler(visibleRectChangedHandler)
    commonProps.contentDescription("test")
    val viewTag = Any()
    val viewTags = SparseArray<Any>()
    commonProps.viewTag(viewTag)
    commonProps.viewTags(viewTags)
    commonProps.shadowElevationPx(60f)
    commonProps.clipToOutline(false)
    commonProps.transitionKey("transitionKey", "")
    commonProps.testKey("testKey")
    val dispatchPopulateAccessibilityEventHandler:
        EventHandler<DispatchPopulateAccessibilityEventEvent> =
        eventHandler {}
    val onInitializeAccessibilityEventHandler: EventHandler<OnInitializeAccessibilityEventEvent> =
        eventHandler {}
    val onInitializeAccessibilityNodeInfoHandler:
        EventHandler<OnInitializeAccessibilityNodeInfoEvent> =
        eventHandler {}
    val onPopulateAccessibilityEventHandler: EventHandler<OnPopulateAccessibilityEventEvent> =
        eventHandler {}
    val onPopulateAccessibiliNodeHandler: EventHandler<OnPopulateAccessibilityNodeEvent> =
        eventHandler {}
    val onRequestSendAccessibilityEventHandler: EventHandler<OnRequestSendAccessibilityEventEvent> =
        eventHandler {}
    val performAccessibilityActionHandler: EventHandler<PerformAccessibilityActionEvent> =
        eventHandler {}
    val sendAccessibilityEventHandler: EventHandler<SendAccessibilityEventEvent> = eventHandler {}
    val sendAccessibilityEventUncheckedHandler: EventHandler<SendAccessibilityEventUncheckedEvent> =
        eventHandler {}
    val onPerformActionForVirtualViewHandler: EventHandler<PerformActionForVirtualViewEvent> =
        eventHandler {}
    val onVirtualViewKeyboardFocusChangedHandler:
        EventHandler<VirtualViewKeyboardFocusChangedEvent> =
        eventHandler {}
    commonProps.accessibilityRole(AccessibilityRole.BUTTON)
    commonProps.accessibilityRoleDescription("Test Role Description")
    commonProps.dispatchPopulateAccessibilityEventHandler(dispatchPopulateAccessibilityEventHandler)
    commonProps.onInitializeAccessibilityEventHandler(onInitializeAccessibilityEventHandler)
    commonProps.onInitializeAccessibilityNodeInfoHandler(onInitializeAccessibilityNodeInfoHandler)
    commonProps.onPopulateAccessibilityEventHandler(onPopulateAccessibilityEventHandler)
    commonProps.onPopulateAccessibilityNodeHandler(onPopulateAccessibiliNodeHandler)
    commonProps.onRequestSendAccessibilityEventHandler(onRequestSendAccessibilityEventHandler)
    commonProps.performAccessibilityActionHandler(performAccessibilityActionHandler)
    commonProps.sendAccessibilityEventHandler(sendAccessibilityEventHandler)
    commonProps.sendAccessibilityEventUncheckedHandler(sendAccessibilityEventUncheckedHandler)
    commonProps.onPerformActionForVirtualViewHandler(onPerformActionForVirtualViewHandler)
    commonProps.onVirtualViewKeyboardFocusChangedHandler(onVirtualViewKeyboardFocusChangedHandler)

    val stateListAnimator = StateListAnimator()
    commonProps.stateListAnimator(stateListAnimator)
    commonProps.gap(YogaGutter.ALL, 10)

    val yogaNode = NodeConfig.createYogaNode()
    val output: LayoutProps = YogaLayoutProps(yogaNode)

    commonProps.copyInto(componentContext, node)
    commonProps.copyLayoutProps(output, componentContext.resourceResolver)

    assertThat(yogaNode.layoutDirection).isEqualTo(YogaDirection.INHERIT)
    assertThat(yogaNode.alignSelf).isEqualTo(YogaAlign.AUTO)
    assertThat(yogaNode.positionType).isEqualTo(YogaPositionType.ABSOLUTE)
    assertThat(yogaNode.flex).isEqualTo(2f)
    assertThat(yogaNode.flexGrow).isEqualTo(3f)
    assertThat(yogaNode.flexShrink).isEqualTo(4f)
    assertThat(yogaNode.flexBasis.value).isEqualTo(6f)
    assertThat(yogaNode.getMargin(YogaEdge.ALL).value).isEqualTo(5f)
    assertThat(yogaNode.getMargin(YogaEdge.VERTICAL).unit).isEqualTo(YogaUnit.PERCENT)
    assertThat(yogaNode.getMargin(YogaEdge.VERTICAL).value).isEqualTo(12f)
    assertThat(yogaNode.getMargin(YogaEdge.RIGHT).unit).isEqualTo(YogaUnit.PERCENT)
    assertThat(yogaNode.getMargin(YogaEdge.RIGHT).value).isEqualTo(5f)
    assertThat(yogaNode.getMargin(YogaEdge.LEFT).unit).isEqualTo(YogaUnit.AUTO)
    assertThat(yogaNode.getPadding(YogaEdge.ALL).value).isEqualTo(1f)
    assertThat(yogaNode.getPadding(YogaEdge.VERTICAL).unit).isEqualTo(YogaUnit.PERCENT)
    assertThat(yogaNode.getPadding(YogaEdge.VERTICAL).value).isEqualTo(7f)
    assertThat(yogaNode.getPosition(YogaEdge.ALL).value).isEqualTo(11f)
    assertThat(yogaNode.getPosition(YogaEdge.RIGHT).value).isEqualTo(12f)
    assertThat(yogaNode.getPosition(YogaEdge.VERTICAL).unit).isEqualTo(YogaUnit.PERCENT)
    assertThat(yogaNode.getPosition(YogaEdge.VERTICAL).value).isEqualTo(17f)
    assertThat(yogaNode.width.value).isEqualTo(5f)
    assertThat(yogaNode.minWidth.unit).isEqualTo(YogaUnit.PERCENT)
    assertThat(yogaNode.minWidth.value).isEqualTo(100f)
    assertThat(yogaNode.maxWidth.value).isEqualTo(25f)
    assertThat(yogaNode.height.value).isEqualTo(30f)
    assertThat(yogaNode.minHeight.unit).isEqualTo(YogaUnit.PERCENT)
    assertThat(yogaNode.minHeight.value).isEqualTo(33f)
    assertThat(yogaNode.maxHeight.value).isEqualTo(34f)
    assertThat(yogaNode.aspectRatio).isEqualTo(20f)
    assertThat(yogaNode.getGap(YogaGutter.ALL).value).isEqualTo(10f)
    assertThat(node.importantForAccessibility).isEqualTo(IMPORTANT_FOR_ACCESSIBILITY_AUTO)
    assertThat(node.isDuplicateChildrenStatesEnabled).isEqualTo(false)
    assertThat(node.hasBorderColor()).isTrue
    assertThat(node.touchExpansion?.get(YogaEdge.RIGHT)).isEqualTo(22f)
    assertThat(node.touchExpansion?.get(YogaEdge.LEFT)).isEqualTo(23f)
    assertThat(node.touchExpansion?.get(YogaEdge.ALL)).isEqualTo(21f)
    assertThat(node.background).isEqualTo(background)
    assertThat(node.foreground).isEqualTo(foreground)
    assertThat(node.isForceViewWrapping).isTrue
    assertThat(node.nodeInfo).isNotNull
    assertThat(node.visibleHeightRatio).isEqualTo(55f)
    assertThat(node.visibleWidthRatio).isEqualTo(56f)
    assertThat(node.onVisible).isEqualTo(visibleHandler)
    assertThat(node.onFocusedVisible).isEqualTo(focusedHandler)
    assertThat(node.onUnfocusedVisible).isEqualTo(unfocusedHandler)
    assertThat(node.onFullImpression).isEqualTo(fullImpressionHandler)
    assertThat(node.onInvisible).isEqualTo(invisibleHandler)
    assertThat(node.onVisibilityChanged).isEqualTo(visibleRectChangedHandler)
    assertThat(node.transitionKey).isEqualTo("transitionKey")
    assertThat(node.testKey).isEqualTo("testKey")
    assertThat(node.stateListAnimator).isEqualTo(stateListAnimator)
  }

  @Test
  fun testSetScalePropsWrapsInView() {
    commonProps.scale(5f)
    commonProps.copyInto(componentContext, node)
    assertThat(node.nodeInfo).isNotNull
    assertThat(node.isForceViewWrapping).isTrue
  }

  @Test
  fun testSetFullScalePropsDoesNotWrapInView() {
    commonProps.scale(0.5f)
    commonProps.scale(1f)
    commonProps.copyInto(componentContext, node)
    assertThat(node.nodeInfo).isNotNull
    assertThat(node.isForceViewWrapping).isFalse
  }

  @Test
  fun testSetAlphaPropsWrapsInView() {
    commonProps.alpha(5f)
    commonProps.copyInto(componentContext, node)
    assertThat(node.nodeInfo).isNotNull
    assertThat(node.isForceViewWrapping).isTrue
  }

  @Test
  fun testSetFullAlphaPropsDoesNotWrapInView() {
    commonProps.alpha(5f)
    commonProps.alpha(1f)
    commonProps.copyInto(componentContext, node)
    assertThat(node.nodeInfo).isNotNull
    assertThat(node.isForceViewWrapping).isFalse
  }

  @Test
  fun testSetRotationPropsWrapsInView() {
    commonProps.rotation(5f)
    commonProps.copyInto(componentContext, node)
    assertThat(node.nodeInfo).isNotNull
    assertThat(node.isForceViewWrapping).isTrue
  }

  @Test
  fun testSetZeroRotationPropsDoesNotWrapInView() {
    commonProps.rotation(1f)
    commonProps.rotation(0f)
    commonProps.copyInto(componentContext, node)
    assertThat(node.nodeInfo).isNotNull
    assertThat(node.isForceViewWrapping).isFalse
  }

  @Test
  fun testPaddingFromDrawable() {
    val c = lithoTestRule.context
    val component =
        Column.create(c)
            .child(Text.create(c).text("Hello World").backgroundRes(background_with_padding))
            .build()
    val testLithoView = lithoTestRule.render { component }
    val result = testLithoView.currentRootNode!!.getChildAt(0)
    assertThat(result.paddingLeft).isEqualTo(48)
    assertThat(result.paddingTop).isEqualTo(0)
    assertThat(result.paddingRight).isEqualTo(0)
    assertThat(result.paddingBottom).isEqualTo(0)
  }

  @Test
  fun testPaddingFromDrawableIsOverwritten() {
    val c = lithoTestRule.context
    val component =
        Column.create(c)
            .child(
                Text.create(c)
                    .text("Hello World")
                    .backgroundRes(background_with_padding)
                    .paddingPx(YogaEdge.LEFT, 8)
                    .paddingPx(YogaEdge.RIGHT, 8)
                    .paddingPx(YogaEdge.TOP, 8)
                    .paddingPx(YogaEdge.BOTTOM, 8),
            )
            .build()
    val testLithoView = lithoTestRule.render { component }
    val result = testLithoView.currentRootNode!!.getChildAt(0)
    assertThat(result.paddingLeft).isEqualTo(8)
    assertThat(result.paddingTop).isEqualTo(8)
    assertThat(result.paddingRight).isEqualTo(8)
    assertThat(result.paddingBottom).isEqualTo(8)
  }

  @Test
  fun testSameObjectEquivalentTo() {
    assertThat(commonProps.isEquivalentTo(commonProps)).isEqualTo(true)
  }

  @Test
  fun testNullObjectEquivalentTo() {
    assertThat(commonProps.isEquivalentTo(null)).isEqualTo(false)
  }

  @Test
  fun testDifferentObjectWithSameContentEquivalentTo() {
    commonProps = CommonProps()
    setCommonProps(commonProps, dispatchInfo)
    val commonProps2 = CommonProps()
    setCommonProps(commonProps2, dispatchInfo)
    assertThat(commonProps.isEquivalentTo(commonProps2)).isEqualTo(true)
  }

  @Test
  fun testMultipleGapGuttersPreservedThroughCopyLayoutProps() {
    val props = CommonProps()
    props.gap(YogaGutter.COLUMN, 10)
    props.gap(YogaGutter.ROW, 20)

    val yogaNode = NodeConfig.createYogaNode()
    val output: LayoutProps = YogaLayoutProps(yogaNode)
    props.copyLayoutProps(output, componentContext.resourceResolver)

    assertThat(yogaNode.getGap(YogaGutter.COLUMN).value).isEqualTo(10f)
    assertThat(yogaNode.getGap(YogaGutter.ROW).value).isEqualTo(20f)
  }

  @Test
  fun testGapAllAndPerAxisPreservedThroughCopyLayoutProps() {
    val props = CommonProps()
    props.gap(YogaGutter.ALL, 5)
    props.gap(YogaGutter.ROW, 15)

    val yogaNode = NodeConfig.createYogaNode()
    val output: LayoutProps = YogaLayoutProps(yogaNode)
    props.copyLayoutProps(output, componentContext.resourceResolver)

    assertThat(yogaNode.getGap(YogaGutter.ALL).value).isEqualTo(5f)
    assertThat(yogaNode.getGap(YogaGutter.ROW).value).isEqualTo(15f)
  }

  @Test
  fun testMultipleGapGuttersEquivalence() {
    val props1 = CommonProps()
    props1.gap(YogaGutter.COLUMN, 10)
    props1.gap(YogaGutter.ROW, 20)

    val props2 = CommonProps()
    props2.gap(YogaGutter.COLUMN, 10)
    props2.gap(YogaGutter.ROW, 20)

    assertThat(props1.isEquivalentTo(props2)).isTrue

    val props3 = CommonProps()
    props3.gap(YogaGutter.COLUMN, 10)
    // Only COLUMN, no ROW — should differ
    assertThat(props1.isEquivalentTo(props3)).isFalse
  }

  @Test
  fun testAutoSizeEquivalence() {
    val sized = CommonProps()
    sized.widthPx(10)
    sized.heightPx(10)

    val widthAuto = CommonProps()
    widthAuto.widthPx(10)
    widthAuto.heightPx(10)
    widthAuto.widthAuto()

    val heightAuto = CommonProps()
    heightAuto.widthPx(10)
    heightAuto.heightPx(10)
    heightAuto.heightAuto()

    assertThat(sized.isEquivalentTo(widthAuto)).isFalse
    assertThat(sized.isEquivalentTo(heightAuto)).isFalse
    assertThat(widthAuto.isEquivalentTo(heightAuto)).isFalse

    val widthAuto2 = CommonProps()
    widthAuto2.widthPx(10)
    widthAuto2.heightPx(10)
    widthAuto2.widthAuto()
    assertThat(widthAuto.isEquivalentTo(widthAuto2)).isTrue
  }

  @Test
  fun testDifferentObjectWithDifferentContentEquivalentTo() {
    commonProps = CommonProps()
    setCommonProps(commonProps, dispatchInfo)
    commonProps.duplicateParentState(false)
    val commonProps2 = CommonProps()
    setCommonProps(commonProps2, dispatchInfo)
    commonProps2.duplicateParentState(true)
    assertThat(commonProps.isEquivalentTo(commonProps2)).isEqualTo(false)
  }

  private fun setCommonProps(
      commonProps: CommonProps,
      dispatchInfo: EventDispatchInfo,
  ) {
    commonProps.layoutDirection(YogaDirection.INHERIT.toLayoutDirection())
    commonProps.alignSelf(YogaAlign.AUTO)
    commonProps.positionType(YogaPositionType.ABSOLUTE)
    commonProps.flex(2f)
    commonProps.flexGrow(3f)
    commonProps.flexShrink(4f)
    commonProps.flexBasisPx(5)
    commonProps.flexBasisPercent(6f)
    commonProps.importantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO)
    commonProps.duplicateParentState(false)
    commonProps.marginPx(YogaEdge.ALL, 5)
    commonProps.marginPx(YogaEdge.RIGHT, 6)
    commonProps.marginPx(YogaEdge.LEFT, 4)
    commonProps.marginPercent(YogaEdge.ALL, 10f)
    commonProps.marginPercent(YogaEdge.VERTICAL, 12f)
    commonProps.marginPercent(YogaEdge.RIGHT, 5f)
    commonProps.marginAuto(YogaEdge.LEFT)
    commonProps.marginAuto(YogaEdge.TOP)
    commonProps.marginAuto(YogaEdge.RIGHT)
    commonProps.marginAuto(YogaEdge.BOTTOM)
    commonProps.paddingPx(YogaEdge.ALL, 1)
    commonProps.paddingPx(YogaEdge.RIGHT, 2)
    commonProps.paddingPx(YogaEdge.LEFT, 3)
    commonProps.paddingPercent(YogaEdge.VERTICAL, 7f)
    commonProps.paddingPercent(YogaEdge.RIGHT, 6f)
    commonProps.paddingPercent(YogaEdge.ALL, 5f)
    commonProps.border(
        Border.create(componentContext)
            .widthPx(YogaEdge.ALL, 1)
            .color(YogaEdge.ALL, Color.RED)
            .build(),
    )
    commonProps.positionPx(YogaEdge.ALL, 11)
    commonProps.positionPx(YogaEdge.RIGHT, 12)
    commonProps.positionPx(YogaEdge.LEFT, 13)
    commonProps.positionPercent(YogaEdge.VERTICAL, 17f)
    commonProps.positionPercent(YogaEdge.RIGHT, 16f)
    commonProps.positionPercent(YogaEdge.ALL, 15f)
    commonProps.widthPx(5)
    commonProps.widthPercent(50f)
    commonProps.minWidthPx(15)
    commonProps.minWidthPercent(100f)
    commonProps.maxWidthPx(25)
    commonProps.maxWidthPercent(26f)
    commonProps.heightPx(30)
    commonProps.heightPercent(31f)
    commonProps.minHeightPx(32)
    commonProps.minHeightPercent(33f)
    commonProps.maxHeightPx(34)
    commonProps.maxHeightPercent(35f)
    commonProps.aspectRatio(20f)
    commonProps.touchExpansionPx(YogaEdge.RIGHT, 22)
    commonProps.touchExpansionPx(YogaEdge.LEFT, 23)
    commonProps.touchExpansionPx(YogaEdge.ALL, 21)
    val background = ComparableColorDrawable.create(Color.RED)
    commonProps.background(background)
    val foreground = ComparableColorDrawable.create(Color.BLACK)
    commonProps.foreground(foreground)
    commonProps.wrapInView()

    val clickHandler = EventHandlerTestUtil.create<ClickEvent>(3, dispatchInfo)
    val longClickHandler = EventHandlerTestUtil.create<LongClickEvent>(3, dispatchInfo)
    val touchHandler = EventHandlerTestUtil.create<TouchEvent>(3, dispatchInfo)
    val interceptTouchHandler = EventHandlerTestUtil.create<InterceptTouchEvent>(3, dispatchInfo)
    val focusChangedHandler = EventHandlerTestUtil.create<FocusChangedEvent>(3, dispatchInfo)
    commonProps.clickHandler(clickHandler)
    commonProps.focusChangeHandler(focusChangedHandler)
    commonProps.longClickHandler(longClickHandler)
    commonProps.touchHandler(touchHandler)
    commonProps.interceptTouchHandler(interceptTouchHandler)
    commonProps.focusable(true)
    commonProps.clickable(true)
    commonProps.selected(false)
    commonProps.keyboardNavigationCluster(true)
    commonProps.enabled(false)
    commonProps.visibleHeightRatio(55f)
    commonProps.visibleWidthRatio(56f)
    commonProps.accessibilityHeading(false)
    commonProps.tooltipText("test")
    commonProps.renderEffect(LithoRenderEffect.Blur(1f, 1f, Shader.TileMode.CLAMP))
    commonProps.screenReaderFocusable(true)
    val visibleHandler = EventHandlerTestUtil.create<VisibleEvent>(3, dispatchInfo)
    val focusedHandler = EventHandlerTestUtil.create<FocusedVisibleEvent>(3, dispatchInfo)
    val unfocusedHandler = EventHandlerTestUtil.create<UnfocusedVisibleEvent>(3, dispatchInfo)
    val fullImpressionHandler =
        EventHandlerTestUtil.create<FullImpressionVisibleEvent>(3, dispatchInfo)
    val invisibleHandler = EventHandlerTestUtil.create<InvisibleEvent>(3, dispatchInfo)
    val visibleRectChangedHandler =
        EventHandlerTestUtil.create<VisibilityChangedEvent>(3, dispatchInfo)
    commonProps.visibleHandler(visibleHandler)
    commonProps.focusedHandler(focusedHandler)
    commonProps.unfocusedHandler(unfocusedHandler)
    commonProps.fullImpressionHandler(fullImpressionHandler)
    commonProps.invisibleHandler(invisibleHandler)
    commonProps.visibilityChangedHandler(visibleRectChangedHandler)
    commonProps.contentDescription("test")
    commonProps.viewTag("Hello World")
    commonProps.shadowElevationPx(60f)
    commonProps.clipToOutline(false)
    commonProps.transitionKey("transitionKey", "")
    commonProps.testKey("testKey")
    val dispatchPopulateAccessibilityEventHandler =
        EventHandlerTestUtil.create<DispatchPopulateAccessibilityEventEvent>(3, dispatchInfo)
    val onInitializeAccessibilityEventHandler =
        EventHandlerTestUtil.create<OnInitializeAccessibilityEventEvent>(3, dispatchInfo)
    val onInitializeAccessibilityNodeInfoHandler =
        EventHandlerTestUtil.create<OnInitializeAccessibilityNodeInfoEvent>(3, dispatchInfo)
    val onPopulateAccessibilityEventHandler =
        EventHandlerTestUtil.create<OnPopulateAccessibilityEventEvent>(3, dispatchInfo)
    val onPopulateAccessibilityNodeHandler =
        EventHandlerTestUtil.create<OnPopulateAccessibilityNodeEvent>(3, dispatchInfo)
    val onRequestSendAccessibilityEventHandler =
        EventHandlerTestUtil.create<OnRequestSendAccessibilityEventEvent>(3, dispatchInfo)
    val performAccessibilityActionHandler =
        EventHandlerTestUtil.create<PerformAccessibilityActionEvent>(3, dispatchInfo)
    val sendAccessibilityEventHandler =
        EventHandlerTestUtil.create<SendAccessibilityEventEvent>(3, dispatchInfo)
    val sendAccessibilityEventUncheckedHandler =
        EventHandlerTestUtil.create<SendAccessibilityEventUncheckedEvent>(3, dispatchInfo)
    val onPerformActionForVirtualViewHandler =
        EventHandlerTestUtil.create<PerformActionForVirtualViewEvent>(3, dispatchInfo)
    val onVirtualViewKeyboardFocusChangedHandler =
        EventHandlerTestUtil.create<VirtualViewKeyboardFocusChangedEvent>(3, dispatchInfo)

    commonProps.accessibilityRole(AccessibilityRole.BUTTON)
    commonProps.accessibilityRoleDescription("Test Role Description")
    commonProps.dispatchPopulateAccessibilityEventHandler(dispatchPopulateAccessibilityEventHandler)
    commonProps.onInitializeAccessibilityEventHandler(onInitializeAccessibilityEventHandler)
    commonProps.onInitializeAccessibilityNodeInfoHandler(onInitializeAccessibilityNodeInfoHandler)
    commonProps.onPopulateAccessibilityEventHandler(onPopulateAccessibilityEventHandler)
    commonProps.onPopulateAccessibilityNodeHandler(onPopulateAccessibilityNodeHandler)
    commonProps.onRequestSendAccessibilityEventHandler(onRequestSendAccessibilityEventHandler)
    commonProps.performAccessibilityActionHandler(performAccessibilityActionHandler)
    commonProps.sendAccessibilityEventHandler(sendAccessibilityEventHandler)
    commonProps.sendAccessibilityEventUncheckedHandler(sendAccessibilityEventUncheckedHandler)
    commonProps.onPerformActionForVirtualViewHandler(onPerformActionForVirtualViewHandler)
    commonProps.onVirtualViewKeyboardFocusChangedHandler(onVirtualViewKeyboardFocusChangedHandler)

    //    final StateListAnimator stateListAnimator = mock(StateListAnimator.class);
    //    commonProps.stateListAnimator(stateListAnimator);
    commonProps.copyInto(componentContext, node)
  }
}
