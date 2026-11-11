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

package com.facebook.rendercore.incrementalmount

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.collection.MutableLongList
import androidx.test.core.app.ApplicationProvider
import com.facebook.rendercore.BindData
import com.facebook.rendercore.HostView
import com.facebook.rendercore.LayoutResult
import com.facebook.rendercore.MountContext
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderState
import com.facebook.rendercore.RenderTree
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RootHostView
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.MountExtension
import com.facebook.rendercore.extensions.RenderCoreExtension
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.incrementalmount.GapWorkerMode.COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT
import com.facebook.rendercore.incrementalmount.GapWorkerMode.COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN
import com.facebook.rendercore.incrementalmount.GapWorkerMode.PREMOUNT
import com.facebook.rendercore.incrementalmount.IncrementalMountExtension.IncrementalMountExtensionState
import com.facebook.rendercore.testing.LayoutResultWrappingNode
import com.facebook.rendercore.testing.RenderCoreTestRule
import com.facebook.rendercore.testing.SimpleLayoutResult
import com.facebook.rendercore.testing.TestHostRenderUnit
import com.facebook.rendercore.testing.TestRenderUnit
import com.facebook.rendercore.testing.ViewWrapperUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncrementalMountExtensionTest {
  @Rule @JvmField val renderCoreTestRule = RenderCoreTestRule()

  @Test
  fun whenVisibleBoundsIsEqualToHierarchy_shouldMountEverything() {
    val c = renderCoreTestRule.context
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 1))
            .width(100)
            .height(100)
            .build()
    renderCoreTestRule
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(100, 100)
        .render()
    val host = renderCoreTestRule.rootHost as HostView
    assertThat(host.childCount).isEqualTo(1)
    assertThat(host.getChildAt(0)).isInstanceOf(TextView::class.java)
  }

  @Test
  fun whenVisibleBoundsIntersectsHierarchy_shouldMountEverything() {
    val c = renderCoreTestRule.context
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 1))
            .width(100)
            .height(100)
            .build()
    renderCoreTestRule
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(50, 50)
        .render()
    val host = renderCoreTestRule.rootHost as HostView
    assertThat(host.childCount).isEqualTo(1)
    assertThat(host.getChildAt(0)).isInstanceOf(TextView::class.java)
  }

  @Test
  fun whenVisibleBoundsIsZero_shouldNotMountAnything() {
    val c = renderCoreTestRule.context
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 1))
            .width(100)
            .height(100)
            .build()
    renderCoreTestRule
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(0, 0)
        .render()
    val host = renderCoreTestRule.rootHost as HostView
    assertThat(host.childCount).isEqualTo(0)
  }

  @Test
  fun whenVisibleBoundsIsZeroAndShouldMountZeroBoundsContent_shouldMountEverything() {
    val c = renderCoreTestRule.context
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension(true))
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 1))
            .width(0)
            .height(0)
            .build()
    renderCoreTestRule
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(100, 100)
        .render()
    val host = renderCoreTestRule.rootHost as HostView
    assertThat(host.getChildAt(0)).isInstanceOf(TextView::class.java)
  }

  @Test
  fun whenVisibleBoundsIsZeroAndShouldNotMountZeroBoundsContent_shouldNotMountAnything() {
    val c = renderCoreTestRule.context
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(TextView(c), 1))
            .width(0)
            .height(0)
            .build()
    renderCoreTestRule
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(100, 100)
        .render()
    val host = renderCoreTestRule.rootHost as HostView
    assertThat(host.childCount).isEqualTo(0)
  }

  @Test
  fun whenVisibleBoundsChangeWithBoundaryConditions_shouldMountAndUnMountCorrectly() {
    val c = renderCoreTestRule.context
    val parent = FrameLayout(c)
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(10, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
    )
    parent.layout(0, 0, 10, 300)
    val host = RootHostView(c)
    parent.addView(host)
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(100)
            .height(300)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 1))
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 2))
                    .y(100)
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                    .y(200)
                    .width(100)
                    .height(100),
            )
            .build()
    renderCoreTestRule
        .useRootHost(host)
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(100, 300)
        .render()
    assertThat(host.childCount).isEqualTo(3)

    // Translate host up to boundary condition.
    host.offsetTopAndBottom(100)
    assertThat(host.childCount).isEqualTo(3)

    // Translate host beyond the boundary condition.
    host.offsetTopAndBottom(1) // 100 + 1 = 101
    assertThat(host.childCount).isEqualTo(2)

    // Translate host up to boundary condition is reverse direction.
    host.offsetTopAndBottom(-1 - 100 - 100) // 101 - 1 - 100 - 100 = -100
    assertThat(host.childCount).isEqualTo(2)
  }

  @Test
  fun whenVisibleBoundsChangeWithItemWindowSkipped_shouldMountAndUnMountCorrectly() {
    val c = renderCoreTestRule.context
    val parent = FrameLayout(c)
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(10, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
    )
    parent.layout(0, 0, 10, 300)
    val host = RootHostView(c)
    parent.addView(host)
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(100)
            .height(300)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 1))
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 2))
                    .y(100)
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                    .y(200)
                    .width(100)
                    .height(100),
            )
            .build()
    renderCoreTestRule
        .useRootHost(host)
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(100, 300)
        .render()
    assertThat(host.childCount).isEqualTo(3)
    host.offsetTopAndBottom(250) // visible window skips over item id 1 and 2
    assertThat(host.childCount).describedAs("Should unmount item id 1 and 2").isEqualTo(1)
  }

  @Test
  fun whenVisibleBoundsChangeHorizontally_shouldMountAndUnMountCorrectly() {
    val c = renderCoreTestRule.context
    val parent = FrameLayout(c)
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
    )
    parent.layout(0, 0, 300, 100)
    val host = RootHostView(c)
    parent.addView(host)
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(300)
            .height(100)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 1))
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 2))
                    .x(100)
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                    .x(200)
                    .width(100)
                    .height(100),
            )
            .build()
    renderCoreTestRule
        .useRootHost(host)
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(300, 100)
        .render()
    assertThat(host.childCount).isEqualTo(3)

    // Translate host up to boundary condition.
    host.offsetLeftAndRight(99)
    assertThat(host.childCount).isEqualTo(3)

    // Translate host beyond the boundary condition.
    host.offsetLeftAndRight(1) // 100 + 1 = 101
    assertThat(host.childCount).isEqualTo(2)

    // Translate host up to boundary condition is reverse direction.
    host.offsetLeftAndRight(-1 - 99 - 99) // 101 - 1 - 100 - 100 = -100
    assertThat(host.childCount).isEqualTo(3)

    // Translate host beyond the boundary condition is reverse direction.
    host.offsetLeftAndRight(-1) // -100 - 1 = -101
    assertThat(host.childCount).isEqualTo(2)
  }

  @Test
  fun whenPreviousHostIsMovedOutOfBounds_shouldMountItemsCorrectly() {
    val c = renderCoreTestRule.context
    val parent = FrameLayout(c)
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(99, View.MeasureSpec.EXACTLY),
    )
    parent.layout(0, 0, 100, 200)
    val host = RootHostView(c)
    parent.addView(host)
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .width(100)
            .height(201)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(HostView(c), 1))
                    .width(100)
                    .height(100)
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(ViewWrapperUnit(HostView(c), 2))
                            .width(100)
                            .height(100)
                            .child(
                                SimpleLayoutResult.create()
                                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                                    .width(100)
                                    .height(100),
                            ),
                    ),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(HostView(c), 4))
                    .y(100)
                    .width(100)
                    .height(101),
            )
            .build()
    host.translationY = 100f
    renderCoreTestRule
        .useRootHost(host)
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(100, 201)
        .render()
    assertThat(host.childCount).isEqualTo(1)
    assertThat((host.getChildAt(0) as HostView).childCount).isEqualTo(1)
    val newRoot: LayoutResult =
        SimpleLayoutResult.create()
            .width(100)
            .height(201)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(HostView(c), 1))
                    .width(100)
                    .height(100),
            )
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(HostView(c), 4))
                    .y(100)
                    .width(100)
                    .height(101)
                    .child(
                        SimpleLayoutResult.create()
                            .renderUnit(ViewWrapperUnit(HostView(c), 2)) // Host changed.
                            .y(1)
                            .width(100)
                            .height(100)
                            .child(
                                SimpleLayoutResult.create()
                                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                                    .width(100)
                                    .height(100),
                            ),
                    ),
            )
            .build()
    renderCoreTestRule.useRootNode(LayoutResultWrappingNode(newRoot)).setSizePx(100, 201).render()

    // Un-mounts the Host with id 2.
    assertThat((host.getChildAt(0) as HostView).childCount).isEqualTo(0)

    // On API 23+, only the visible host is mounted initially due to more precise bounds checking
    assertThat(host.childCount).isEqualTo(1)
    host.offsetTopAndBottom(-2)

    // Now both hosts should be mounted
    assertThat(host.childCount).isEqualTo(2)
    assertThat((host.getChildAt(1) as HostView).childCount).isEqualTo(1)
  }

  @Test
  fun whenItemBoundsAreOutsideHostBounds_shouldMountHostBeforeItem() {
    val c = renderCoreTestRule.context
    val parent = FrameLayout(c)
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
    )
    parent.layout(0, 0, 100, 100)
    val host = RootHostView(c)
    parent.addView(host)
    val extensions = arrayOf<RenderCoreExtension<*, *>>(IncrementalMountRenderCoreExtension())
    val root: LayoutResult =
        SimpleLayoutResult.create()
            .renderUnit(ViewWrapperUnit(HostView(c), 1))
            .width(100)
            .height(100)
            .child(
                SimpleLayoutResult.create()
                    .renderUnit(ViewWrapperUnit(TextView(c), 3))
                    .y(110)
                    .width(100)
                    .height(100),
            )
            .build()
    host.translationY = -105f
    renderCoreTestRule
        .useRootHost(host)
        .useExtensions(extensions)
        .useRootNode(LayoutResultWrappingNode(root))
        .setSizePx(100, 210)
        .render()
    assertThat(host.childCount).isEqualTo(1)
    assertThat((host.getChildAt(0) as HostView).childCount).isEqualTo(1)
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun testNegativeMarginChild_forcesHostMount() {
    val c = ApplicationProvider.getApplicationContext<Context>()
    val extension = IncrementalMountExtension.instance
    val mountState = createMountState(c)
    val extensionState =
        mountState.registerExtension(extension) as ExtensionState<IncrementalMountExtensionState>
    val rootHost = IncrementalMountOutput(
        0,
        0,
        Rect(0, 100, 100, 200),
        false,
        "root",
        null,
        DEFAULT_CONTENT_TYPE,
        IncrementalMountOutput.NO_HOST_ID,
    )
    val hostRenderUnit = TestHostRenderUnit(0)
    val hostRenderTreeNode = RenderTreeNode(null, hostRenderUnit, null, rootHost.bounds, null, 0)
    val host1 = IncrementalMountOutput(
        1,
        1,
        Rect(0, 100, 50, 200),
        false,
        "host1",
        rootHost,
        DEFAULT_CONTENT_TYPE,
        rootHost.id,
    )
    val host1RenderUnit = TestHostRenderUnit(1)
    val host1RTN = RenderTreeNode(hostRenderTreeNode, host1RenderUnit, null, host1.bounds, null, 0)
    val child1 = IncrementalMountOutput(
        2,
        2,
        Rect(0, 85, 50, 190),
        false,
        "child1",
        host1,
        DEFAULT_CONTENT_TYPE,
        host1.id,
    )
    val child1RenderUnit = TestRenderUnit(2)
    val child11RTN = RenderTreeNode(host1RTN, child1RenderUnit, null, child1.bounds, null, 0)
    val host2 = IncrementalMountOutput(
        3,
        3,
        Rect(50, 100, 50, 200),
        false,
        "host2",
        rootHost,
        DEFAULT_CONTENT_TYPE,
        rootHost.id,
    )
    val host2RenderUnit = TestHostRenderUnit(3)
    val host2RTN = RenderTreeNode(hostRenderTreeNode, host2RenderUnit, null, host2.bounds, null, 1)
    val child2 = IncrementalMountOutput(
        4,
        4,
        Rect(50, 100, 50, 200),
        false,
        "child2",
        host2,
        DEFAULT_CONTENT_TYPE,
        host2.id,
    )
    val child2RenderUnit = TestRenderUnit(4)
    val child2RTN = RenderTreeNode(host2RTN, child2RenderUnit, null, child2.bounds, null, 0)
    val flatList = arrayOf(hostRenderTreeNode, host1RTN, child11RTN, host2RTN, child2RTN)
    val renderTree = RenderTree(
        hostRenderTreeNode,
        flatList,
        null,
        SizeConstraints.exact(100, 100),
        RenderState.NO_ID,
        null,
        null,
        null,
    )
    val input1 = TestIncrementalMountExtensionInput(rootHost, host1, child1, host2, child2)
    extension.beforeMount(extensionState, input1, Rect(0, 80, 100, 90))
    mountState.mount(renderTree)
    assertThat(extensionState.ownsReference(1)).isTrue
    assertThat(extensionState.ownsReference(2)).isTrue
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun testNegativeMarginChild_hostMovedAndUnmounted_forcesHostMount() {
    val c = ApplicationProvider.getApplicationContext<Context>()
    val extension = IncrementalMountExtension.instance
    val mountState = createMountState(c)
    val extensionState =
        mountState.registerExtension(extension) as ExtensionState<IncrementalMountExtensionState>
    val rootHost = IncrementalMountOutput(
        0,
        0,
        Rect(0, 100, 100, 200),
        false,
        "root",
        null,
        DEFAULT_CONTENT_TYPE,
        IncrementalMountOutput.NO_HOST_ID,
    )
    val hostRenderUnit = TestHostRenderUnit(0)
    val hostRenderTreeNode = RenderTreeNode(null, hostRenderUnit, null, rootHost.bounds, null, 0)
    val host1 = IncrementalMountOutput(
        1,
        1,
        Rect(0, 100, 50, 200),
        false,
        "host1",
        rootHost,
        DEFAULT_CONTENT_TYPE,
        rootHost.id,
    )
    val host1RenderUnit = TestHostRenderUnit(1)
    val host1RTN = RenderTreeNode(hostRenderTreeNode, host1RenderUnit, null, host1.bounds, null, 0)
    val child1 = IncrementalMountOutput(
        2,
        2,
        Rect(0, 85, 50, 190),
        false,
        "child1",
        host1,
        DEFAULT_CONTENT_TYPE,
        host1.id,
    )
    val child1RenderUnit = TestRenderUnit(2)
    val child11RTN = RenderTreeNode(host1RTN, child1RenderUnit, null, child1.bounds, null, 0)
    val host2 = IncrementalMountOutput(
        3,
        3,
        Rect(50, 100, 50, 200),
        false,
        "host2",
        rootHost,
        DEFAULT_CONTENT_TYPE,
        rootHost.id,
    )
    val host2RenderUnit = TestHostRenderUnit(3)
    val host2RTN = RenderTreeNode(hostRenderTreeNode, host2RenderUnit, null, host2.bounds, null, 1)
    val child2 = IncrementalMountOutput(
        4,
        4,
        Rect(50, 100, 50, 200),
        false,
        "child2",
        host2,
        DEFAULT_CONTENT_TYPE,
        host2.id,
    )
    val child2RenderUnit = TestRenderUnit(4)
    val child2RTN = RenderTreeNode(host2RTN, child2RenderUnit, null, child2.bounds, null, 0)
    val flatList = arrayOf(hostRenderTreeNode, host2RTN, child2RTN, host1RTN, child11RTN)
    val renderTree = RenderTree(
        hostRenderTreeNode,
        flatList,
        null,
        SizeConstraints.exact(100, 100),
        RenderState.NO_ID,
        null,
        null,
        null,
    )
    val input = TestIncrementalMountExtensionInput(rootHost, host2, child2, host1, child1)
    extension.beforeMount(extensionState, input, Rect(0, 80, 100, 90))
    mountState.mount(renderTree)
    assertThat(extensionState.ownsReference(1)).isTrue
    assertThat(extensionState.ownsReference(2)).isTrue
    val host1Reparented = IncrementalMountOutput(
        1,
        1,
        Rect(0, 100, 50, 200),
        false,
        "host1",
        host2,
        DEFAULT_CONTENT_TYPE,
        host2.id,
    )
    val host1RenderUnitReparented = TestHostRenderUnit(1)
    val host1RTNReparented =
        RenderTreeNode(host2RTN, host1RenderUnitReparented, null, host1Reparented.bounds, null, 1)
    val child1Reparented = IncrementalMountOutput(
        2,
        2,
        Rect(0, 85, 50, 190),
        false,
        "child1",
        host1Reparented,
        DEFAULT_CONTENT_TYPE,
        host1Reparented.id,
    )
    val child1RenderUnitReparented = TestRenderUnit(2)
    val child11RTNReparented = RenderTreeNode(
        host1RTNReparented,
        child1RenderUnitReparented,
        null,
        child1Reparented.bounds,
        null,
        0,
    )
    val flatListReparented =
        arrayOf(hostRenderTreeNode, host2RTN, child2RTN, host1RTNReparented, child11RTNReparented)
    val renderTreeReparented = RenderTree(
        hostRenderTreeNode,
        flatListReparented,
        null,
        SizeConstraints.exact(100, 100),
        RenderState.NO_ID,
        null,
        null,
        null,
    )
    val inputReparented = TestIncrementalMountExtensionInput(
        rootHost,
        host2,
        child2,
        host1Reparented,
        child1Reparented,
    )
    extension.beforeMount(extensionState, inputReparented, Rect(0, 80, 100, 90))
    mountState.mount(renderTreeReparented)
    assertThat(extensionState.ownsReference(1)).isTrue
    assertThat(extensionState.ownsReference(2)).isTrue
  }

  @Test
  fun `unknown non-null content type mounts as a probe and exposes a pending sample`() {
    val contentType = Any()
    val output = output(id = 1, index = 1, top = 20, contentType = contentType)
    val fixture = createCostAwareFixture(listOf(output))
    val estimatedTypes = mutableListOf<Any>()

    val result =
        fixture.premount(
            frameSequence = 1,
            estimateNs = { type -> estimatedTypes.add(type).let { null } },
        )

    assertThat(result).isEqualTo(PremountResult.MOUNTED)
    assertThat(estimatedTypes).containsExactly(contentType)
    assertThat(fixture.extension.consumePendingPremountContentType(fixture.extensionState))
        .isSameAs(contentType)
    assertThat(fixture.mountState.getContentById(output.id)).isNotNull()
  }

  @Test
  fun `known estimate rejects above remaining deadline and accepts at or below it`() {
    val contentType = Any()
    val rejected = output(id = 1, index = 1, top = 20, contentType = contentType)
    val rejectedFixture = createCostAwareFixture(listOf(rejected))

    assertThat(
        rejectedFixture.premount(
            frameSequence = 1,
            runwayNs = ONE_SECOND_NS,
            nowNs = ONE_SECOND_NS,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    assertThat(rejectedFixture.mountState.getContentById(rejected.id)).isNull()

    val accepted = output(id = 2, index = 1, top = 20, contentType = contentType)
    val acceptedFixture = createCostAwareFixture(listOf(accepted))
    assertThat(
        acceptedFixture.premount(
            frameSequence = 1,
            runwayNs = ONE_SECOND_NS,
            nowNs = ONE_SECOND_NS,
            estimateNs = { ONE_SECOND_NS },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(acceptedFixture.mountState.getContentById(accepted.id)).isNotNull()
  }

  @Test
  fun `top scan mounts a cheap output past an expensive head`() {
    val expensiveType = Any()
    val cheapType = Any()
    val expensive = output(id = 1, index = 1, top = 20, contentType = expensiveType)
    val cheap = output(id = 2, index = 2, top = 40, contentType = cheapType)
    val fixture = createCostAwareFixture(listOf(expensive, cheap))

    val result =
        fixture.premount(
            frameSequence = 1,
            estimateNs = { type -> if (type === expensiveType) TWO_SECONDS_NS else 1L },
        )

    assertThat(result).isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(expensive.id)).isNull()
    assertThat(fixture.mountState.getContentById(cheap.id)).isNotNull()
  }

  @Test
  fun `bottom scan is the fallback after the top scan finds no affordable output`() {
    val expensiveType = Any()
    val cheapType = Any()
    val outputs =
        (0 until 9).map { offset ->
          output(
              id = offset.toLong() + 1,
              index = offset + 1,
              top = 20 + offset * 20,
              contentType = if (offset == 8) cheapType else expensiveType,
          )
        }
    val fixture = createCostAwareFixture(outputs)
    fixture.extensionState.state.previousBottomsIndex = outputs.size

    val result =
        fixture.premount(
            frameSequence = 1,
            estimateNs = { type -> if (type === cheapType) 1L else TWO_SECONDS_NS },
        )

    assertThat(result).isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(outputs.last().id)).isNotNull()
    assertThat(outputs.dropLast(1).map { fixture.mountState.getContentById(it.id) })
        .containsOnlyNulls()
  }

  @Test
  fun `a resolved prefix does not consume the scan limit`() {
    val outputs =
        (0 until 10).map { offset ->
          output(
              id = offset.toLong() + 1,
              index = offset + 1,
              top = 20 + offset * 20,
              contentType = Any(),
          )
        }
    val fixture = createCostAwareFixture(outputs)
    fixture.extensionState.state.previousBottomsIndex = 0
    for (output in outputs.take(9)) {
      fixture.extensionState.acquireMountReference(output.id, true)
    }
    val estimated = mutableListOf<Any>()

    // Nine owned outputs sit in front of the only real candidate, three more than the per-frame
    // inspection budget. Stepping over them costs nothing, so the tenth is still reached.
    val result =
        fixture.premount(
            frameSequence = 1,
            estimateNs = { contentType ->
              estimated.add(contentType)
              null
            },
        )

    assertThat(result).isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(outputs.last().id)).isNotNull()
    // Exactly one: counting estimator calls is what separates "did not re-walk" from
    // "re-walked, but cheaply".
    assertThat(estimated).hasSize(1)
  }

  @Test
  fun `premount depth grows past the eight-slot budget across scroll frames`() {
    val outputs =
        (0 until 30).map { offset ->
          output(
              id = offset.toLong() + 1,
              index = offset + 1,
              top = 20 + offset * 20,
              contentType = Any(),
          )
        }
    val fixture = createCostAwareFixture(outputs)
    fixture.extensionState.state.previousBottomsIndex = 0

    // Production order, once per frame: the visible bounds change, then the gap worker asks.
    // The bounds change is what rewinds the sweep back onto the outputs already premounted. An
    // empty rect makes it return before it touches the official cursors, so this pins the reset
    // and nothing else.
    for (frame in 1L..20L) {
      fixture.extension.onVisibleBoundsChanged(
          fixture.extensionState,
          Rect(),
          com.facebook.rendercore.extensions.VisibilityChangeReason.SCROLL,
      )
      repeat(8) { fixture.premount(frameSequence = frame) }
    }

    // Fails at eight under the old accounting: from frame nine on, every frame spent its whole
    // budget re-walking outputs it already owned and mounted nothing new, so depth was pinned at
    // the budget size no matter how long the user scrolled.
    assertThat(fixture.mountState.getContentById(outputs[15].id)).isNotNull()
  }

  @Test
  fun `scan continuation reaches an affordable candidate beyond the prior frame limit`() {
    val expensiveType = Any()
    val cheapType = Any()
    val outputs =
        (0 until 9).map { offset ->
          output(
              id = offset.toLong() + 1,
              index = offset + 1,
              top = 20 + offset * 20,
              contentType = if (offset == 8) cheapType else expensiveType,
          )
        }
    val fixture = createCostAwareFixture(outputs)
    fixture.extensionState.state.previousBottomsIndex = 0

    assertThat(
        fixture.premount(
            frameSequence = 1,
            estimateNs = { type -> if (type === cheapType) 1L else TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    assertThat(fixture.mountState.getContentById(outputs.last().id)).isNull()

    assertThat(
        fixture.premount(
            frameSequence = 2,
            estimateNs = { type -> if (type === cheapType) 1L else TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(outputs.last().id)).isNotNull()
  }

  @Test
  fun `full scan cycle resets so a declined non-head candidate is reconsidered`() {
    val host = output(id = 1, index = 1, top = 0, contentType = Any())
    val blockedHead = output(
        id = 2,
        index = 2,
        top = 20,
        contentType = Any(),
        hostOutput = host,
        hostId = host.id,
    )
    val laterType = Any()
    val later = output(id = 3, index = 3, top = 40, contentType = laterType)
    val fixture =
        createCostAwareFixture(listOf(host, blockedHead, later), hostOutputIds = setOf(host.id))
    fixture.extensionState.state.previousTopsIndex = 1
    fixture.extensionState.state.previousBottomsIndex = 0
    var laterEstimateCalls = 0

    for (frameSequence in 1L..2L) {
      assertThat(
          fixture.premount(
              frameSequence = frameSequence,
              estimateNs = { type ->
                if (type === laterType) {
                  laterEstimateCalls++
                }
                TWO_SECONDS_NS
              },
          ),
      )
          .isEqualTo(PremountResult.YIELD)
    }

    assertThat(laterEstimateCalls).isEqualTo(2)
    assertThat(fixture.mountState.getContentById(later.id)).isNull()
  }

  @Test
  fun `scan time is deducted before comparing the estimate with the absolute deadline`() {
    val output = output(id = 1, index = 1, top = 20, contentType = Any())
    val fixture = createCostAwareFixture(listOf(output))
    var nowNs = 100L

    val result =
        fixture.extension.premountNextForGapWorker(
            fixture.extensionState,
            deadlineNs = 110L,
            frameSequence = 1,
            nanoTime = { nowNs },
            estimator =
                estimator {
                  nowNs = 109L
                  2L
                },
        )

    assertThat(result).isEqualTo(PremountResult.YIELD)
    assertThat(fixture.mountState.getContentById(output.id)).isNull()
  }

  @Test
  fun `each direction scans at most eight list entries`() {
    for (direction in listOf("top", "bottom")) {
      val outputs =
          (0 until 9).map { offset ->
            output(
                id = offset.toLong() + 1,
                index = offset + 1,
                top = 20 + offset * 20,
                contentType = Any(),
            )
          }
      val fixture = createCostAwareFixture(outputs)
      if (direction == "top") {
        fixture.extensionState.state.previousBottomsIndex = 0
      } else {
        fixture.extensionState.state.previousTopsIndex = outputs.size
        fixture.extensionState.state.previousBottomsIndex = outputs.size
      }
      var estimateCalls = 0

      assertThat(
          fixture.premount(
              frameSequence = 1,
              estimateNs = {
                estimateCalls++
                TWO_SECONDS_NS
              },
          ),
      )
          .isEqualTo(PremountResult.YIELD)
      assertThat(estimateCalls).describedAs("$direction scan count").isEqualTo(8)
    }
  }

  @Test
  fun `bottom scan continues after rejecting a duplicate already inspected by top`() {
    val expensiveType = Any()
    val cheapType = Any()
    val expensiveOutputs =
        (0 until 8).map { offset ->
          output(
              id = offset.toLong() + 1,
              index = offset + 1,
              top = 20 + offset * 20,
              bottom = if (offset == 0) 1_000 else 100 + offset * 20,
              contentType = expensiveType,
          )
        }
    val cheap = output(
        id = 9,
        index = 9,
        top = 180,
        bottom = 900,
        contentType = cheapType,
    )
    val fixture = createCostAwareFixture(expensiveOutputs + cheap)
    fixture.extensionState.state.previousBottomsIndex = 9
    val estimatedTypes = mutableListOf<Any>()

    assertThat(
        fixture.premount(
            frameSequence = 1,
            estimateNs = { type ->
              estimatedTypes.add(type)
              if (type === cheapType) 1L else TWO_SECONDS_NS
            },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)

    assertThat(estimatedTypes).containsExactlyElementsOf(List(8) { expensiveType } + cheapType)
    assertThat(fixture.mountState.getContentById(cheap.id)).isNotNull()
  }

  @Test
  fun `cost-aware scan never changes the official incremental mount cursors`() {
    val expensiveType = Any()
    val cheapType = Any()
    val outputs = listOf(
        output(id = 1, index = 1, top = 20, contentType = expensiveType),
        output(id = 2, index = 2, top = 40, contentType = cheapType),
        output(id = 3, index = 3, top = 60, contentType = expensiveType),
    )
    val fixture = createCostAwareFixture(outputs)
    fixture.extensionState.state.previousTopsIndex = 0
    fixture.extensionState.state.previousBottomsIndex = outputs.size

    fixture.premount(
        frameSequence = 1,
        estimateNs = { type -> if (type === cheapType) 1L else TWO_SECONDS_NS },
    )

    assertThat(IncrementalMountExtension.getPreviousTopsIndex(fixture.extensionState.state))
        .isZero()
    assertThat(IncrementalMountExtension.getPreviousBottomsIndex(fixture.extensionState.state))
        .isEqualTo(outputs.size)
  }

  @Test
  fun `an output skipped by the cost-aware scan mounts normally when it becomes visible`() {
    val output = output(id = 1, index = 1, top = 100, contentType = Any())
    val fixture = createCostAwareFixture(listOf(output))

    assertThat(
        fixture.premount(
            frameSequence = 1,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    assertThat(fixture.mountState.getContentById(output.id)).isNull()

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 100, 10, 110),
        com.facebook.rendercore.extensions.VisibilityChangeReason.UNKNOWN,
    )

    assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
    assertThat(fixture.mountState.getContentById(output.id)).isNotNull()
  }

  @Test
  fun `candidate readiness handles host relationships`() {
    val noHost = output(id = 1, index = 1, top = 20)
    val noHostFixture = createCostAwareFixture(listOf(noHost))
    assertThat(noHostFixture.premount(frameSequence = 1))
        .describedAs("no host")
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(noHostFixture.mountState.getContentById(noHost.id)).isNotNull()

    val rootHosted = output(
        id = 2,
        index = 1,
        top = 20,
        hostId = MountState.ROOT_HOST_ID,
    )
    val rootHostFixture = createCostAwareFixture(listOf(rootHosted))
    assertThat(rootHostFixture.premount(frameSequence = 1))
        .describedAs("root host")
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(rootHostFixture.mountState.getContentById(rootHosted.id)).isNotNull()

    val expectedHost = output(id = 3, index = 1, top = 0, contentType = Any())
    val misleadingHost = output(id = 4, index = 2, top = 0, contentType = Any())
    val hosted = output(
        id = 5,
        index = 3,
        top = 20,
        contentType = Any(),
        hostOutput = misleadingHost,
        hostId = expectedHost.id,
    )
    val mountedHostFixture = createCostAwareFixture(
        listOf(expectedHost, misleadingHost, hosted),
        hostOutputIds = setOf(expectedHost.id, misleadingHost.id),
    )
    mountedHostFixture.extensionState.acquireMountReference(expectedHost.id, true)
    mountedHostFixture.extensionState.state.previousTopsIndex = 2
    assertThat(mountedHostFixture.premount(frameSequence = 1))
        .describedAs("mounted Host selected by hostId")
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(mountedHostFixture.mountState.getContentById(expectedHost.id))
        .isInstanceOf(com.facebook.rendercore.Host::class.java)
    assertThat(mountedHostFixture.mountState.getContentById(hosted.id)).isNotNull()
    assertThat(mountedHostFixture.mountState.getContentById(misleadingHost.id)).isNull()

    val missingHost = output(id = 6, index = 1, top = 20, contentType = Any(), hostId = 99)
    val missingHostFixture = createCostAwareFixture(listOf(missingHost))
    assertThat(missingHostFixture.premount(frameSequence = 1))
        .describedAs("missing host")
        .isEqualTo(PremountResult.YIELD)
    assertThat(missingHostFixture.mountState.getContentById(missingHost.id)).isNull()

    val nonHost = output(id = 7, index = 1, top = 0, contentType = Any())
    val nonHostChild = output(
        id = 8,
        index = 2,
        top = 20,
        contentType = Any(),
        hostId = nonHost.id,
    )
    val nonHostFixture = createCostAwareFixture(listOf(nonHost, nonHostChild))
    nonHostFixture.extensionState.acquireMountReference(nonHost.id, true)
    nonHostFixture.extensionState.state.previousTopsIndex = 1
    assertThat(nonHostFixture.premount(frameSequence = 1))
        .describedAs("mounted non-Host")
        .isEqualTo(PremountResult.YIELD)
    assertThat(nonHostFixture.mountState.getContentById(nonHost.id)).isNotNull()
    assertThat(nonHostFixture.mountState.getContentById(nonHostChild.id)).isNull()
  }

  @Test
  fun `scheduler state resets on input replacement official cursor setup and visible bounds changes`() {
    val resetActions = listOf<(CostAwarePremountFixture, IncrementalMountOutput) -> Unit>(
        { fixture, output ->
          fixture.extension.beforeMount(
              fixture.extensionState,
              TestIncrementalMountExtensionInput(output),
              Rect(),
          )
        },
        { fixture, _ ->
          fixture.extensionState.state.previousLocalVisibleRect.set(Rect(0, 0, 10, 10))
          fixture.extension.afterMount(fixture.extensionState)
        },
        { fixture, _ ->
          fixture.extensionState.state.previousLocalVisibleRect.set(Rect(0, 0, 10, 10))
          fixture.extension.onVisibleBoundsChanged(
              fixture.extensionState,
              Rect(0, 1, 10, 11),
              com.facebook.rendercore.extensions.VisibilityChangeReason.UNKNOWN,
          )
        },
    )

    for (resetAction in resetActions) {
      val output = output(id = 1, index = 1, top = 100, contentType = Any())
      val fixture = createCostAwareFixture(listOf(output))
      rejectOnDistinctFrames(fixture, 1L..7L)

      resetAction(fixture, output)

      assertThat(
          fixture.premount(
              frameSequence = 8,
              estimateNs = { TWO_SECONDS_NS },
          ),
      )
          .isEqualTo(PremountResult.YIELD)
      assertThat(fixture.mountState.getContentById(output.id)).isNull()
    }
  }

  @Test
  fun `ambiguous host relation clears starvation rather than forcing the candidate`() {
    val valid = output(id = 1, index = 1, top = 100, contentType = Any())
    val ambiguous = output(
        id = valid.id,
        index = valid.index,
        top = valid.bounds.top,
        contentType = valid.contentType,
        hostId = 99,
    )
    val fixture = createCostAwareFixture(listOf(valid))
    rejectOnDistinctFrames(fixture, 1L..7L)

    fixture.extensionState.state.input = TestIncrementalMountExtensionInput(ambiguous)
    assertThat(
        fixture.premount(
            frameSequence = 8,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    fixture.extensionState.state.input = TestIncrementalMountExtensionInput(valid)

    assertThat(
        fixture.premount(
            frameSequence = 9,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    assertThat(fixture.mountState.getContentById(valid.id)).isNull()
  }

  @Test
  fun `starvation rejection increments once per frame and forces on the eighth distinct frame`() {
    val output = output(id = 1, index = 1, top = 20, contentType = Any())
    val fixture = createCostAwareFixture(listOf(output))

    repeat(4) {
      assertThat(
          fixture.premount(
              frameSequence = 1,
              estimateNs = { TWO_SECONDS_NS },
          ),
      )
          .isEqualTo(PremountResult.YIELD)
    }
    rejectOnDistinctFrames(fixture, 2L..7L)
    assertThat(fixture.mountState.getContentById(output.id)).isNull()

    assertThat(
        fixture.premount(
            frameSequence = 8,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(output.id)).isNotNull()
  }

  @Test
  fun `mounting cheap scan-ahead outputs does not reset starvation of the expensive head`() {
    val expensiveType = Any()
    val cheapType = Any()
    val head = output(id = 1, index = 1, top = 20, contentType = expensiveType)
    val cheapOutputs =
        (0 until 7).map { offset ->
          output(
              id = offset.toLong() + 2,
              index = offset + 2,
              top = 40 + offset * 20,
              contentType = cheapType,
          )
        }
    val fixture = createCostAwareFixture(listOf(head) + cheapOutputs)

    for (frameSequence in 1L..7L) {
      assertThat(
          fixture.premount(
              frameSequence = frameSequence,
              estimateNs = { type -> if (type === expensiveType) TWO_SECONDS_NS else 1L },
          ),
      )
          .isEqualTo(PremountResult.MOUNTED)
      assertThat(fixture.mountState.getContentById(head.id)).isNull()
    }

    assertThat(
        fixture.premount(
            frameSequence = 8,
            estimateNs = { type -> if (type === expensiveType) TWO_SECONDS_NS else 1L },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(head.id)).isNotNull()
  }

  @Test
  fun `head replacement waits for cycle reset after stored head mounts`() {
    val expensiveType = Any()
    val cheapType = Any()
    val firstHead = output(id = 1, index = 1, top = 20, contentType = expensiveType)
    val nextHead = output(id = 2, index = 2, top = 40, contentType = expensiveType)
    val cheap = output(id = 3, index = 3, top = 60, contentType = cheapType)
    val fixture = createCostAwareFixture(listOf(firstHead, nextHead, cheap))
    fixture.extensionState.state.previousBottomsIndex = 0

    assertThat(
        fixture.premount(
            frameSequence = 1,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)

    assertThat(
        fixture.premount(
            frameSequence = 2,
            estimateNs = { type -> if (type === cheapType) 1L else null },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(firstHead.id)).isNotNull()

    assertThat(
        fixture.premount(
            frameSequence = 2,
            estimateNs = { type -> if (type === cheapType) 1L else TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(nextHead.id)).isNull()
    assertThat(fixture.mountState.getContentById(cheap.id)).isNotNull()

    assertThat(
        fixture.premount(
            frameSequence = 3,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    assertThat(fixture.extensionState.state.starvation.deferredId)
        .isEqualTo(IncrementalMountOutput.NO_HOST_ID)

    assertThat(
        fixture.premount(
            frameSequence = 4,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    assertThat(fixture.extensionState.state.starvation.deferredId).isEqualTo(nextHead.id)
  }

  @Test
  fun `host not ready frames do not increment starvation rejection`() {
    val host = output(id = 1, index = 1, top = 0, contentType = Any())
    val child = output(
        id = 2,
        index = 2,
        top = 20,
        contentType = Any(),
        hostOutput = host,
        hostId = host.id,
    )
    val fixture = createCostAwareFixture(listOf(host, child), hostOutputIds = setOf(host.id))
    fixture.extensionState.state.previousTopsIndex = 1

    rejectOnDistinctFrames(fixture, 1L..8L)
    assertThat(fixture.mountState.getContentById(child.id)).isNull()

    fixture.extensionState.acquireMountReference(host.id, true)
    assertThat(
        fixture.premount(
            frameSequence = 9,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.YIELD)
    assertThat(fixture.mountState.getContentById(child.id)).isNull()

    rejectOnDistinctFrames(fixture, 10L..15L)
    assertThat(
        fixture.premount(
            frameSequence = 16,
            estimateNs = { TWO_SECONDS_NS },
        ),
    )
        .isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.mountState.getContentById(child.id)).isNotNull()
  }

  @Test
  fun `visible bounds reason reaches nested callback during non-dirty pass and then resets`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture = createCostAwareFixture(listOf(output))
    val nestedHost = RecordingExtensionHost(renderCoreTestRule.context)
    fixture.extensionState.state.previousLocalVisibleRect.set(Rect(0, 0, 10, 10))
    fixture.extensionState.state.mountedOutputIdsWithNestedContent.put(output.id, nestedHost)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 1, 10, 11),
        VisibilityChangeReason.SCROLL,
    )
    IncrementalMountExtension.recursivelyNotifyVisibleBoundsChanged(
        fixture.extensionState,
        output.id,
        nestedHost,
    )

    assertThat(nestedHost.visibleBoundsReasons)
        .containsExactly(VisibilityChangeReason.SCROLL, VisibilityChangeReason.UNKNOWN)
  }

  @Test
  fun `cost-aware premount reports nothing when there is no candidate`() {
    val fixture = createCostAwareFixture(emptyList())

    assertThat(fixture.premount(frameSequence = 0)).isEqualTo(PremountResult.EXHAUSTED)
  }

  @Test
  fun `each gap worker mode has one stable instance`() {
    val instances =
        GapWorkerMode.entries.associateWith { IncrementalMountExtension.getInstance(it) }

    GapWorkerMode.entries.forEach { mode ->
      assertThat(IncrementalMountExtension.getInstance(mode)).isSameAs(instances[mode])
    }
    assertThat(instances.values.toSet()).hasSize(GapWorkerMode.entries.size)
    GapWorkerMode.entries.forEach { mode ->
      val lifecycle = instances.getValue(mode).createState().premountLifecycle
      if (mode.unmountsIncrementally) {
        assertThat(lifecycle).isNotNull()
      } else {
        assertThat(lifecycle).isNull()
      }
    }
  }

  @Test
  fun `scroll marks a premounted leaf as seen`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.MOUNTED)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        output.bounds,
        VisibilityChangeReason.SCROLL,
    )

    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
        .isEqualTo(PremountLifecycle.State.SEEN)
  }

  @Test
  fun `animation marks a premounted leaf as seen`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.MOUNTED)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        output.bounds,
        VisibilityChangeReason.ANIMATION,
    )

    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
        .isEqualTo(PremountLifecycle.State.SEEN)
  }

  @Test
  fun `unknown reason does not mark a premounted leaf as seen`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.MOUNTED)

    // Transient state mounts the whole host with UNKNOWN; that rect is not the screen.
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 1000, 1000),
        VisibilityChangeReason.UNKNOWN,
    )

    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
        .isEqualTo(PremountLifecycle.State.PREMOUNTED)
  }

  @Test
  fun `scroll exit releases a premounted leaf`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)

    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.MOUNTED)
    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
        .isEqualTo(PremountLifecycle.State.PREMOUNTED)

    showThenHide(fixture, output)

    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
        .isEqualTo(PremountLifecycle.State.RELEASED)
    assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
    assertThat(fixture.mountState.getContentById(output.id)).isNull()
  }

  @Test
  fun `reentrant visibility records untracked to seen instead of premounted`() {
    val output = output(id = 1, index = 1, top = 20)
    lateinit var fixture: CostAwarePremountFixture
    fixture =
        createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
            onCreateById =
                mapOf(
                    output.id to
                        {
                          fixture.extension.onVisibleBoundsChanged(
                              fixture.extensionState,
                              output.bounds,
                              VisibilityChangeReason.SCROLL,
                          )
                        },
                ),
        )

    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.MOUNTED)

    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
        .isEqualTo(PremountLifecycle.State.SEEN)
  }

  @Test
  fun `released leaf is skipped before cost estimation`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    fixture.premount(frameSequence = 1)
    showThenHide(fixture, output)
    var estimates = 0

    assertThat(
        fixture.premount(
            frameSequence = 2,
            estimateNs = {
              estimates++
              null
            },
        ),
    )
        .isEqualTo(PremountResult.EXHAUSTED)
    assertThat(estimates).isZero()
  }

  @Test
  fun `mount reason releases an untracked leaf without tracking it`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 20, 10, 30),
        VisibilityChangeReason.MOUNT,
    )
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 100, 10, 110),
        VisibilityChangeReason.MOUNT,
    )

    assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id)).isNull()
  }

  @Test
  fun `zero area scroll does not mark anything seen`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 20, 0, 30),
        VisibilityChangeReason.SCROLL,
    )

    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id)).isNull()
  }

  @Test
  fun `before-unmount fences late premount work`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    assertThat(fixture.extensionState.state.premountLifecycle?.currentSession()).isNotNull()

    fixture.extension.onBeforeUnmount(fixture.extensionState)

    assertThat(fixture.extensionState.state.premountLifecycle?.currentSession()).isNull()
    assertThat(fixture.extensionState.state.input).isNull()
    assertThat(fixture.extensionState.state.isRegisteredWithGapWorker).isFalse()
    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.STALE)
  }

  @Test
  fun `before-unmount leaves state untouched without incremental unmount`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture = createCostAwareFixture(listOf(output), mode = PREMOUNT)
    val input = fixture.extensionState.state.input
    assertThat(input).isNotNull()

    fixture.extension.onBeforeUnmount(fixture.extensionState)

    assertThat(fixture.extensionState.state.input).isSameAs(input)
  }

  @Test
  fun `same id reappearing after removal starts untracked`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    fixture.premount(frameSequence = 1)
    showThenHide(fixture, output)

    fixture.extension.beforeMount(
        fixture.extensionState,
        TestIncrementalMountExtensionInput(),
        Rect(),
    )
    fixture.extension.beforeMount(
        fixture.extensionState,
        TestIncrementalMountExtensionInput(output),
        Rect(),
    )

    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id)).isNull()
  }

  @Test
  fun `offscreen host stays owned while its seen leaf is released`() {
    val host = output(id = 10, index = 1, top = 0, bottom = 30)
    val child = output(id = 11, index = 2, top = 20, hostOutput = host, hostId = host.id)
    val fixture = createCostAwareFixture(
        listOf(host, child),
        hostOutputIds = setOf(host.id),
        mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
    )
    fixture.premount(frameSequence = 1)
    fixture.premount(frameSequence = 2)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 10, 30),
        VisibilityChangeReason.SCROLL,
    )
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 100, 10, 110),
        VisibilityChangeReason.SCROLL,
    )

    assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
    assertThat(fixture.extensionState.ownsReference(child.id)).isFalse()
    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(child.id))
        .isEqualTo(PremountLifecycle.State.RELEASED)
  }

  @Test
  fun `input replacement during acquire returns stale and reconciles ownership`() {
    val output = output(id = 1, index = 1, top = 20)
    lateinit var fixture: CostAwarePremountFixture
    fixture =
        createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
            onCreateById =
                mapOf(
                    output.id to
                        {
                          fixture.extension.beforeMount(
                              fixture.extensionState,
                              TestIncrementalMountExtensionInput(output),
                              Rect(),
                          )
                        },
                ),
        )

    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.STALE)
    assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
    assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id)).isNull()
  }

  @Test
  fun `re-registering an already registered tree keeps released items released`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    fixture.premount(frameSequence = 1)
    showThenHide(fixture, output)
    val lifecycle = checkNotNull(fixture.extensionState.state.premountLifecycle)
    val session = lifecycle.currentSession()

    try {
      // A RecyclerView rebind of an attached row registers again without unregistering first.
      fixture.extension.onRegisterForPremount(fixture.extensionState, null)

      assertThat(lifecycle.currentSession()).isEqualTo(session)
      assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.RELEASED)
    } finally {
      IncrementalMountGapWorker.resetWorkerForTesting()
    }
  }

  @Test
  fun `first scroll after a layout marks premounted items already on screen as seen`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    fixture.premount(frameSequence = 1)
    val lifecycle = checkNotNull(fixture.extensionState.state.premountLifecycle)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 10, 50),
        VisibilityChangeReason.MOUNT,
    )
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.PREMOUNTED)

    // A small scroll: the item stays on screen, so the edge sweep never visits it.
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 5, 10, 55),
        VisibilityChangeReason.SCROLL,
    )
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.SEEN)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 100, 10, 150),
        VisibilityChangeReason.SCROLL,
    )
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.RELEASED)
    assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
  }

  @Test
  fun `first scroll after a layout records an item mounted by the layout as released`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    val lifecycle = checkNotNull(fixture.extensionState.state.premountLifecycle)

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 10, 50),
        VisibilityChangeReason.MOUNT,
    )
    assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
    assertThat(lifecycle.stateOf(output.id)).isNull()

    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 5, 10, 55),
        VisibilityChangeReason.SCROLL,
    )
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 100, 10, 150),
        VisibilityChangeReason.SCROLL,
    )

    assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
    assertThat(lifecycle.isReleased(output.id)).isTrue()
  }

  @Test
  fun `unregistering during an acquire stops further premount asks`() {
    val output = output(id = 1, index = 1, top = 20)
    lateinit var fixture: CostAwarePremountFixture
    fixture =
        createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
            onCreateById =
                mapOf(
                    output.id to
                        {
                          // A mount that recycles this very row unregisters it mid-acquire.
                          fixture.extension.onUnregisterForPremount(fixture.extensionState)
                        },
                ),
        )

    try {
      // The legacy and direct-callback loops ask while hasItemToMount() is true.
      var asks = 0
      while (fixture.extension.hasItemToMount(fixture.extensionState) && asks < 10) {
        fixture.extension.premountNext(fixture.extensionState)
        asks++
      }

      assertThat(asks).isEqualTo(1)
      assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
    } finally {
      IncrementalMountGapWorker.resetWorkerForTesting()
    }
  }

  @Test
  fun `registering for premount starts the lifecycle session`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    val state = fixture.extensionState.state
    val lifecycle = checkNotNull(state.premountLifecycle)
    lifecycle.stopSession()
    state.isRegisteredWithGapWorker = false

    try {
      fixture.extension.onRegisterForPremount(fixture.extensionState, null)

      assertThat(lifecycle.currentSession()).isNotNull()
      assertThat(state.isRegisteredWithGapWorker).isTrue()
      assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.MOUNTED)
    } finally {
      IncrementalMountGapWorker.resetWorkerForTesting()
    }
  }

  @Test
  fun `unregistering stops the session so late premount work is stale`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    val state = fixture.extensionState.state

    try {
      fixture.extension.onUnregisterForPremount(fixture.extensionState)

      assertThat(state.premountLifecycle?.currentSession()).isNull()
      assertThat(state.isRegisteredWithGapWorker).isFalse()
      assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.STALE)
    } finally {
      IncrementalMountGapWorker.resetWorkerForTesting()
    }
  }

  @Test
  fun `empty-rect remount of a registered tree keeps visible items mounted`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    val lifecycle = checkNotNull(fixture.extensionState.state.premountLifecycle)
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 10, 50),
        VisibilityChangeReason.SCROLL,
    )
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.SEEN)

    // A rebind of an attached row with a new layout: onRegisterForPremount mounts with Rect().
    fixture.extension.beforeMount(
        fixture.extensionState,
        TestIncrementalMountExtensionInput(output),
        Rect(),
    )
    fixture.extension.beforeMountItem(fixture.extensionState, nodeFor(output), output.index)

    assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.SEEN)
  }

  @Test
  fun `stale acquire keeps an item the newer layout shows`() {
    val output = output(id = 1, index = 1, top = 20)
    lateinit var fixture: CostAwarePremountFixture
    fixture =
        createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
            onCreateById =
                mapOf(
                    output.id to
                        {
                          fixture.extension.beforeMount(
                              fixture.extensionState,
                              TestIncrementalMountExtensionInput(output),
                              Rect(0, 0, 10, 50),
                          )
                          fixture.extension.beforeMountItem(
                              fixture.extensionState,
                              nodeFor(output),
                              output.index,
                          )
                        },
                ),
        )

    assertThat(fixture.premount(frameSequence = 1)).isEqualTo(PremountResult.STALE)
    assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
    assertThat(fixture.mountState.getContentById(output.id)).isNotNull()
  }

  @Test
  fun `premounted item that scrolls in from below and back out is released`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    (fixture.extensionState.rootHost as View).layout(0, 0, 10, 1_000)
    val lifecycle = checkNotNull(fixture.extensionState.state.premountLifecycle)
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 10, 15),
        VisibilityChangeReason.SCROLL,
    )
    fixture.premount(frameSequence = 1)
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.PREMOUNTED)

    // Enters through the bottom edge.
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 18, 10, 33),
        VisibilityChangeReason.SCROLL,
    )
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.SEEN)

    // Leaves through the bottom edge.
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 10, 15),
        VisibilityChangeReason.SCROLL,
    )
    assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.RELEASED)
    assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
  }

  @Test
  fun `unmeasured pass after the first scroll still gets its items marked seen`() {
    val output = output(id = 1, index = 1, top = 20)
    val fixture =
        createCostAwareFixture(listOf(output), mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    val lifecycle = checkNotNull(fixture.extensionState.state.premountLifecycle)

    // The first measured frame after the layout, with the item off screen.
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 100, 10, 150),
        VisibilityChangeReason.SCROLL,
    )
    // A transient whole-view mount brings it in without marking it.
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 0, 10, 50),
        VisibilityChangeReason.MOUNT,
    )
    assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 5, 10, 55),
        VisibilityChangeReason.SCROLL,
    )
    fixture.extension.onVisibleBoundsChanged(
        fixture.extensionState,
        Rect(0, 100, 10, 150),
        VisibilityChangeReason.SCROLL,
    )

    assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
    assertThat(lifecycle.isReleased(output.id)).isTrue()
  }

  @Test
  fun `scroll exit queues a seen leaf until the gap worker releases it`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        fixture.premount(frameSequence = 1)

        showThenHide(fixture, output)

        assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
        assertThat(fixture.queuedReleases.contains(output.id)).isTrue()

        assertThat(fixture.releaseQueued()).isFalse()

        assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
        assertThat(fixture.mountState.getContentById(output.id)).isNull()
        assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
            .isEqualTo(PremountLifecycle.State.RELEASED)
      }

  @Test
  fun `scroll that empties the visible rect queues seen leaves`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        scroll(fixture, output.bounds)

        scroll(fixture, Rect())

        assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
        assertThat(fixture.queuedReleases.contains(output.id)).isTrue()

        fixture.releaseQueued()

        assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
        assertThat(fixture.extensionState.state.premountLifecycle?.isReleased(output.id)).isTrue()
      }

  @Test
  fun `gap worker releases queued leaves oldest first and stops at the deadline`() =
      withQueuedReleases(4) {
        val outputs = listOf(
            output(id = 1, index = 1, top = 20),
            output(id = 2, index = 2, top = 40),
            output(id = 3, index = 3, top = 60),
        )
        val fixture =
            createCostAwareFixture(outputs, mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
        scroll(fixture, Rect(0, 0, 10, 100))
        scroll(fixture, Rect(0, 200, 10, 300))
        var nowNs = 0L

        // Each clock read moves time on by 10ns, so a 15ns deadline leaves room for two.
        val hasMore =
            fixture.releaseQueued(deadlineNs = 15L) {
              nowNs.also { nowNs += 10L }
            }

        assertThat(hasMore).isTrue()
        assertThat(fixture.extensionState.ownsReference(1L)).isFalse()
        assertThat(fixture.extensionState.ownsReference(2L)).isFalse()
        assertThat(fixture.extensionState.ownsReference(3L)).isTrue()
        assertThat(fixture.queuedReleases.size).isEqualTo(1)

        assertThat(fixture.releaseQueued()).isFalse()
        assertThat(fixture.extensionState.ownsReference(3L)).isFalse()
      }

  @Test
  fun `scrolling a queued leaf back on screen cancels its release`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        fixture.premount(frameSequence = 1)
        showThenHide(fixture, output)

        scroll(fixture, output.bounds)
        fixture.releaseQueued()

        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
        assertThat(fixture.mountState.getContentById(output.id)).isNotNull()
        assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
            .isEqualTo(PremountLifecycle.State.SEEN)
      }

  @Test
  fun `offscreen host is never queued while its leaf is`() =
      withQueuedReleases(4) {
        val host = output(id = 10, index = 1, top = 0, bottom = 30)
        val child = output(id = 11, index = 2, top = 20, hostOutput = host, hostId = host.id)
        val fixture = createCostAwareFixture(
            listOf(host, child),
            hostOutputIds = setOf(host.id),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        fixture.premount(frameSequence = 1)
        fixture.premount(frameSequence = 2)
        scroll(fixture, Rect(0, 0, 10, 30))
        scroll(fixture, Rect(0, 100, 10, 110))

        assertThat(fixture.queuedReleases.contains(child.id)).isTrue()
        assertThat(fixture.queuedReleases.contains(host.id)).isFalse()

        fixture.releaseQueued()

        assertThat(fixture.extensionState.ownsReference(child.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
      }

  @Test
  fun `premounted leaf that was never seen is not queued`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        (fixture.extensionState.rootHost as View).layout(0, 0, 10, 1_000)
        scroll(fixture, Rect(0, 0, 10, 15))
        fixture.premount(frameSequence = 1)

        scroll(fixture, Rect(0, 100, 10, 115))

        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
      }

  @Test
  fun `queue at its limit releases the oldest leaf inside the scroll`() =
      withQueuedReleases(2) {
        val outputs = listOf(
            output(id = 1, index = 1, top = 20),
            output(id = 2, index = 2, top = 40),
            output(id = 3, index = 3, top = 60),
        )
        val fixture =
            createCostAwareFixture(outputs, mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
        scroll(fixture, Rect(0, 0, 10, 100))

        scroll(fixture, Rect(0, 200, 10, 300))

        assertThat(fixture.extensionState.ownsReference(1L)).isFalse()
        assertThat(fixture.queuedReleases.size).isEqualTo(2)
        assertThat(fixture.queuedReleases[0]).isEqualTo(2L)
        assertThat(fixture.queuedReleases[1]).isEqualTo(3L)
      }

  @Test
  fun `queue overflow keeps an item that is back inside the current rect`() =
      withQueuedReleases(1) {
        val first = output(id = 1, index = 1, top = 20)
        val second = output(id = 2, index = 2, top = 40)
        val fixture = createCostAwareFixture(
            listOf(first, second),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        (fixture.extensionState.rootHost as View).layout(0, 0, 10, 1_000)
        scroll(fixture, Rect(0, 0, 10, 100))
        // On screen but still queued, as if a re-entry path had missed the cancel.
        fixture.queuedReleases.add(first.id)

        scroll(fixture, Rect(0, 0, 10, 35))

        assertThat(fixture.extensionState.ownsReference(first.id)).isTrue()
        assertThat(fixture.mountState.getContentById(first.id)).isNotNull()
        assertThat(fixture.queuedReleases.size).isEqualTo(1)
        assertThat(fixture.queuedReleases[0]).isEqualTo(second.id)
      }

  @Test
  fun `overflow skips a queued leaf the new layout already released`() =
      withQueuedReleases(1) {
        val first = output(id = 1, index = 1, top = 20)
        val second = output(id = 2, index = 2, top = 40)
        val fixture = createCostAwareFixture(
            listOf(first, second),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        (fixture.extensionState.rootHost as View).layout(0, 0, 10, 1_000)
        scroll(fixture, Rect(0, 0, 10, 100))
        scroll(fixture, Rect(0, 35, 10, 100))
        assertThat(fixture.queuedReleases.contains(first.id)).isTrue()

        fixture.extension.beforeMount(
            fixture.extensionState,
            TestIncrementalMountExtensionInput(second),
            Rect(0, 35, 10, 100),
        )
        fixture.extension.afterMount(fixture.extensionState)

        assertThat(fixture.extensionState.ownsReference(first.id)).isFalse()
        assertThat(fixture.queuedReleases.contains(first.id)).isTrue()

        scroll(fixture, Rect(0, 200, 10, 300))

        assertThat(fixture.extensionState.ownsReference(first.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(second.id)).isTrue()
        assertThat(fixture.queuedReleases.size).isEqualTo(1)
        assertThat(fixture.queuedReleases[0]).isEqualTo(second.id)
      }

  @Test
  fun `starved worker leaves at most the cap queued`() =
      withQueuedReleases(2) {
        val outputs = listOf(
            output(id = 1, index = 1, top = 20),
            output(id = 2, index = 2, top = 40),
            output(id = 3, index = 3, top = 60),
        )
        val fixture =
            createCostAwareFixture(outputs, mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)

        repeat(2) {
          scroll(fixture, Rect(0, 0, 10, 100))
          scroll(fixture, Rect(0, 200, 10, 300))

          // Past its deadline the worker releases nothing and keeps the tree scheduled.
          assertThat(fixture.releaseQueued(deadlineNs = 0L) { 0L }).isTrue()
          assertThat(fixture.queuedReleases.size).isEqualTo(2)
          assertThat(fixture.extensionState.ownsReference(1L)).isFalse()
          assertThat(fixture.extensionState.ownsReference(2L)).isTrue()
          assertThat(fixture.extensionState.ownsReference(3L)).isTrue()
        }
      }

  @Test
  fun `queued leaf the new layout shows is kept mounted`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        fixture.premount(frameSequence = 1)
        showThenHide(fixture, output)

        fixture.extension.beforeMount(
            fixture.extensionState,
            TestIncrementalMountExtensionInput(output),
            output.bounds,
        )
        fixture.releaseQueued()

        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
      }

  @Test
  fun `queued leaf the new layout removes is skipped by the worker`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        fixture.premount(frameSequence = 1)
        showThenHide(fixture, output)

        fixture.extension.beforeMount(
            fixture.extensionState,
            TestIncrementalMountExtensionInput(),
            Rect(),
        )

        assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
        assertThat(fixture.releaseQueued()).isFalse()
        assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id)).isNull()
      }

  @Test
  fun `unregistering drops queued releases and stops the session`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        fixture.premount(frameSequence = 1)
        showThenHide(fixture, output)

        fixture.extension.onUnregisterForPremount(fixture.extensionState)

        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.releaseQueued()).isFalse()
        assertThat(fixture.extensionState.state.premountLifecycle?.currentSession()).isNull()
      }

  @Test
  fun `teardown drops queued releases so stale work does nothing`() =
      withQueuedReleases(4) {
        val output = output(id = 1, index = 1, top = 20)
        val fixture = createCostAwareFixture(
            listOf(output),
            mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
        )
        fixture.premount(frameSequence = 1)
        showThenHide(fixture, output)

        fixture.extension.onBeforeUnmount(fixture.extensionState)

        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.releaseQueued()).isFalse()
        assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id)).isNull()
      }

  @Test
  fun `release queued by a premount callback keeps the tree in the gap worker`() =
      withQueuedReleases(4) {
        val seen = output(id = 1, index = 1, top = 20)
        val premounted = output(id = 2, index = 2, top = 40)
        lateinit var fixture: CostAwarePremountFixture
        fixture =
            createCostAwareFixture(
                listOf(seen, premounted),
                mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
                onCreateById = mapOf(premounted.id to { scroll(fixture, Rect(0, 35, 10, 55)) }),
            )
        (fixture.extensionState.rootHost as View).layout(0, 0, 10, 1_000)
        scroll(fixture, seen.bounds)
        val worker = fixture.startGapWorker()

        worker.run()

        assertThat(fixture.premountResults)
            .containsExactly(PremountResult.MOUNTED, PremountResult.EXHAUSTED)
        assertThat(fixture.queuedReleases.contains(seen.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(seen.id)).isTrue()

        worker.run()

        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.extensionState.ownsReference(seen.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(premounted.id)).isTrue()
      }

  @Test
  fun `overflow release that unregisters the tree releases the new item instead of queueing it`() =
      withQueuedReleases(1) {
        val first = output(id = 1, index = 1, top = 20)
        val second = output(id = 2, index = 2, top = 40)
        var unregisterOnUnmount = false
        lateinit var fixture: CostAwarePremountFixture
        fixture =
            createCostAwareFixture(
                listOf(first, second),
                mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
                onUnmountById =
                    mapOf(
                        first.id to
                            {
                              if (unregisterOnUnmount) {
                                fixture.extension.onUnregisterForPremount(fixture.extensionState)
                              }
                            },
                    ),
            )
        (fixture.extensionState.rootHost as View).layout(0, 0, 10, 1_000)
        scroll(fixture, Rect(0, 0, 10, 100))
        scroll(fixture, Rect(0, 35, 10, 100))
        assertThat(fixture.queuedReleases.contains(first.id)).isTrue()
        val worker = fixture.startGapWorker()
        unregisterOnUnmount = true

        scroll(fixture, Rect(0, 55, 10, 100))

        assertThat(fixture.extensionState.state.isRegisteredWithGapWorker).isFalse()
        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.extensionState.ownsReference(first.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(second.id)).isFalse()

        worker.run()
        worker.run()

        assertThat(fixture.premountResults).isEmpty()
      }

  @Test
  fun `nested visibility pass during an overflow release cannot push the queue past its cap`() =
      withQueuedReleases(1) {
        val first = output(id = 1, index = 1, top = 20)
        val second = output(id = 2, index = 2, top = 40)
        val third = output(id = 3, index = 3, top = 80)
        var scrollOnUnmount = false
        lateinit var fixture: CostAwarePremountFixture
        fixture =
            createCostAwareFixture(
                listOf(first, second, third),
                mode = COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
                onUnmountById =
                    mapOf(
                        first.id to
                            {
                              if (scrollOnUnmount) {
                                scrollOnUnmount = false
                                // Only the bottom edge moves, so this pass queues `third`.
                                scroll(fixture, Rect(0, 35, 10, 75))
                              }
                            },
                    ),
            )
        (fixture.extensionState.rootHost as View).layout(0, 0, 10, 1_000)
        scroll(fixture, Rect(0, 0, 10, 100))
        scroll(fixture, Rect(0, 35, 10, 100))
        assertThat(fixture.queuedReleases.contains(first.id)).isTrue()
        scrollOnUnmount = true

        scroll(fixture, Rect(0, 55, 10, 75))

        assertThat(scrollOnUnmount).isFalse()
        assertThat(fixture.queuedReleases.size).isEqualTo(1)
        assertThat(fixture.queuedReleases[0]).isEqualTo(second.id)
        assertThat(fixture.extensionState.ownsReference(first.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(third.id)).isFalse()
      }

  @Test
  fun `tree scrolled up out of view and removed is drained, hosts and premounted items included`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        scrollPastTop(fixture)

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()
        assertThat(fixture.queuedReleases.toLongArrayList())
            .containsExactly(child.id, unseen.id, host.id)
        assertThat(fixture.releaseQueued()).isFalse()
        assertThat(fixture.extension.isDrainingOffscreen(fixture.extensionState)).isFalse()
        val lifecycle = checkNotNull(fixture.extensionState.state.premountLifecycle)
        for (output in listOf(host, child, unseen)) {
          assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
          assertThat(lifecycle.stateOf(output.id)).isEqualTo(PremountLifecycle.State.RELEASED)
        }
      }

  @Test
  fun `tree that left through the bottom keeps hosts and premounted items`() =
      withQueuedReleases(4) {
        val (fixture, host, _, unseen) = premountedDrainTree()
        scroll(fixture, Rect(0, 0, 10, 25))

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        fixture.releaseQueued()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `tree that only peeked in from the top keeps hosts and premounted items`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        scroll(fixture, Rect(0, 25, 10, 30))

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        fixture.releaseQueued()
        assertThat(fixture.extensionState.ownsReference(child.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `full pass on a detached tree does not count as shown from its top`() =
      withQueuedReleases(4) {
        val (fixture, host, _, unseen) = premountedDrainTree()
        fixture.extension.onVisibleBoundsChanged(
            fixture.extensionState,
            Rect(0, 0, 10, 30),
            VisibilityChangeReason.MOUNT,
        )
        scroll(fixture, Rect(0, 25, 10, 30))

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        fixture.releaseQueued()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `whole-host pass while attached does not count as shown from its top`() =
      withQueuedReleases(4) {
        val (fixture, host, _, unseen) = premountedDrainTree()
        val root = fixture.extensionState.rootHost as View
        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        parent.addView(root)
        // scenario: only the bottom strip peeks in, then transient state sends the whole host.
        scroll(fixture, Rect(0, 25, 10, 30))
        fixture.extension.onVisibleBoundsChanged(
            fixture.extensionState,
            Rect(0, 0, 10, 30),
            VisibilityChangeReason.UNKNOWN,
        )
        fixture.extension.onVisibleBoundsChanged(
            fixture.extensionState,
            Rect(0, 25, 10, 30),
            VisibilityChangeReason.ANIMATION,
        )
        parent.removeView(root)

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        fixture.releaseQueued()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `tree mounted in place on screen is drained after it scrolls past the top`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        val root = fixture.extensionState.rootHost as View
        val parent = FrameLayout(ApplicationProvider.getApplicationContext())
        parent.addView(root)
        fixture.extension.beforeMount(
            fixture.extensionState,
            fixture.extensionState.state.input,
            Rect(0, 0, 10, 30),
        )
        scroll(fixture, Rect(0, 5, 10, 30))
        parent.removeView(root)

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()
        assertThat(fixture.releaseQueued()).isFalse()
        for (output in listOf(host, child, unseen)) {
          assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
        }
      }

  @Test
  fun `tree registered again on screen is still drained after it scrolls past the top`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        scroll(fixture, Rect(0, 0, 10, 30))
        fixture.extension.onRegisterForPremount(fixture.extensionState, null)
        scroll(fixture, Rect(0, 5, 10, 30))

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()
        assertThat(fixture.releaseQueued()).isFalse()
        for (output in listOf(host, child, unseen)) {
          assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
        }
      }

  @Test
  fun `tree unbound without an empty pass is drained, its last visible slice included`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        scrollPastTop(fixture)

        fixture.extension.onUnbind(fixture.extensionState)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()
        assertThat(fixture.releaseQueued()).isFalse()
        for (output in listOf(host, child, unseen)) {
          assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
        }

        FrameLayout(ApplicationProvider.getApplicationContext())
            .addView(fixture.extensionState.rootHost as View)
        scroll(fixture, Rect(0, 5, 10, 30))

        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(child.id)).isTrue()
      }

  @Test
  fun `tree detached together with its parent keeps hosts and premounted items`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        scrollPastTop(fixture)
        FrameLayout(ApplicationProvider.getApplicationContext())
            .addView(fixture.extensionState.rootHost as View)

        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        fixture.releaseQueued()
        assertThat(fixture.extensionState.ownsReference(child.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `tree attached again mid-drain stops draining and keeps what it still holds`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        scrollPastTop(fixture)
        detach(fixture)
        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()
        var now = 0L
        fixture.releaseQueued(deadlineNs = 1L) { now++ }
        assertThat(fixture.extensionState.ownsReference(child.id)).isFalse()

        FrameLayout(ApplicationProvider.getApplicationContext())
            .addView(fixture.extensionState.rootHost as View)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `tree shown again mid-drain keeps the releases the scroll path would queue`() =
      withQueuedReleases(4) {
        val host = output(id = 10, index = 1, top = 0, bottom = 30)
        val above = output(id = 11, index = 2, top = 0, hostOutput = host, hostId = host.id)
        val below = output(id = 12, index = 3, top = 20, hostOutput = host, hostId = host.id)
        val unseen = output(id = 13, index = 4, top = 300)
        val fixture = createCostAwareFixture(
            listOf(host, above, below, unseen),
            hostOutputIds = setOf(host.id),
            mode = COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN,
        )
        premountAll(fixture, host, above, below, unseen)
        scrollPastTop(fixture)
        detach(fixture)
        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()

        FrameLayout(ApplicationProvider.getApplicationContext())
            .addView(fixture.extensionState.rootHost as View)
        scroll(fixture, Rect(0, 25, 10, 30))

        assertThat(fixture.extension.isDrainingOffscreen(fixture.extensionState)).isFalse()
        assertThat(fixture.queuedReleases.toLongArrayList()).containsExactly(above.id)
        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        assertThat(fixture.releaseQueued()).isFalse()
        assertThat(fixture.extensionState.ownsReference(above.id)).isFalse()
        for (output in listOf(host, below, unseen)) {
          assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
        }
      }

  @Test
  fun `tree registered again mid-drain keeps only the releases the scroll path would queue`() =
      withQueuedReleases(4) {
        val host = output(id = 10, index = 1, top = 0, bottom = 30)
        val above = output(id = 11, index = 2, top = 0, hostOutput = host, hostId = host.id)
        val below = output(id = 12, index = 3, top = 20, hostOutput = host, hostId = host.id)
        val unseen = output(id = 13, index = 4, top = 300)
        val fixture = createCostAwareFixture(
            listOf(host, above, below, unseen),
            hostOutputIds = setOf(host.id),
            mode = COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN,
        )
        premountAll(fixture, host, above, below, unseen)
        scrollPastTop(fixture)
        detach(fixture)
        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()

        fixture.extension.onRegisterForPremount(fixture.extensionState, null)

        assertThat(fixture.extension.isDrainingOffscreen(fixture.extensionState)).isFalse()
        assertThat(fixture.queuedReleases.toLongArrayList())
            .containsExactlyInAnyOrder(above.id, below.id)
        assertThat(fixture.releaseQueued()).isFalse()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `stopping a drain keeps at most the queue cap`() =
      withQueuedReleases(8) {
        val first = output(id = 11, index = 1, top = 0)
        val second = output(id = 12, index = 2, top = 10)
        val third = output(id = 13, index = 3, top = 20)
        val fixture = createCostAwareFixture(
            listOf(first, second, third),
            mode = COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN,
        )
        scrollPastTop(fixture)
        fixture.extension.onUnbind(fixture.extensionState)
        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()
        assertThat(fixture.queuedReleases.toLongArrayList())
            .containsExactlyInAnyOrder(first.id, second.id, third.id)

        IncrementalMountExtensionConfigs.maxPendingOffscreenReleases = 1
        FrameLayout(ApplicationProvider.getApplicationContext())
            .addView(fixture.extensionState.rootHost as View)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        assertThat(fixture.queuedReleases.size).isEqualTo(1)
      }

  @Test
  fun `tree that was never shown is not drained when unbound`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()

        fixture.extension.onUnbind(fixture.extensionState)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        for (output in listOf(host, child, unseen)) {
          assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
        }
      }

  @Test
  fun `host whose child another extension keeps mounted is kept by the drain`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        fixture.mountState
            .registerExtension(HoldingExtension())
            .acquireMountReference(child.id, isMounting = true)
        scrollPastTop(fixture)
        detach(fixture)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isTrue()
        fixture.releaseQueued()

        assertThat(fixture.extensionState.ownsReference(child.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(unseen.id)).isFalse()
        assertThat(fixture.extensionState.ownsReference(host.id)).isTrue()
        assertThat(fixture.mountState.getContentById(host.id)).isNotNull()
        assertThat(fixture.mountState.getContentById(child.id)).isNotNull()
      }

  @Test
  fun `gap worker drains a removed tree without premounting into it and then drops it`() =
      withQueuedReleases(4) {
        val (fixture, host, child, unseen) = premountedDrainTree()
        scrollPastTop(fixture)
        val worker = fixture.startGapWorker()
        detach(fixture)

        worker.run()

        assertThat(fixture.premountResults).isEmpty()
        for (output in listOf(host, child, unseen)) {
          assertThat(fixture.extensionState.ownsReference(output.id)).isFalse()
        }

        worker.run()

        assertThat(fixture.premountResults).isEmpty()
      }

  @Test
  fun `gap worker serves a tree on screen before a tree it is draining`() =
      withQueuedReleases(4) {
        val (drained, _, _, unseen) = premountedDrainTree()
        val ahead = output(id = 20, index = 1, top = 300)
        val onScreen =
            createCostAwareFixture(listOf(ahead), mode = COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN)
        scroll(onScreen, Rect(0, 0, 10, 100))
        IncrementalMountGapWorker.resetWorkerForTesting()
        var now = 0L
        // Each tree served ends the gap, so only the first tree in the worker's order is served.
        val worker =
            IncrementalMountGapWorker.get(
                null,
                drained.extensionState.tracer,
                { now },
                EwmaPremountCostEstimator(),
            ) { extension, extensionState, deadlineNs, frameSequence, nanoTime, estimator ->
              extension
                  .premountNextForGapWorker(
                      extensionState,
                      deadlineNs,
                      frameSequence,
                      nanoTime,
                      estimator,
                  )
                  .also { now = deadlineNs }
            }
        scrollPastTop(drained)
        detach(drained)
        worker.add(onScreen.extensionState.mountDelegate, null, costAware = true)

        worker.run()

        assertThat(onScreen.extensionState.ownsReference(ahead.id)).isTrue()
        assertThat(drained.extension.isDrainingOffscreen(drained.extensionState)).isTrue()
        assertThat(drained.queuedReleases.contains(unseen.id)).isTrue()
        assertThat(drained.extensionState.ownsReference(unseen.id)).isTrue()
      }

  @Test
  fun `unregistering clears a requested drain`() =
      withQueuedReleases(4) {
        val fixture = premountedDrainTree().fixture
        scrollPastTop(fixture)
        detach(fixture)

        fixture.extension.onUnregisterForPremount(fixture.extensionState)

        assertThat(fixture.extension.prepareOffscreenDrain(fixture.extensionState)).isFalse()
        assertThat(fixture.queuedReleases.isEmpty()).isTrue()
      }

  private fun nodeFor(output: IncrementalMountOutput): RenderTreeNode =
      RenderTreeNode(null, TestRenderUnit(output.id), null, output.bounds, null, 0)

  companion object {
    /** These tests do not care what the content type is, only that there is one. */
    private val DEFAULT_CONTENT_TYPE = Any()

    private const val ONE_SECOND_NS = 1_000_000_000L
    private const val TWO_SECONDS_NS = 2_000_000_000L

    private fun createMountState(c: Context): MountState {
      return MountState(RootHostView(c))
    }

    private fun output(
        id: Long,
        index: Int,
        top: Int,
        contentType: Any = DEFAULT_CONTENT_TYPE,
        bottom: Int = top + 10,
        hostOutput: IncrementalMountOutput? = null,
        hostId: Long = hostOutput?.id ?: IncrementalMountOutput.NO_HOST_ID,
        excludeFromIncrementalMount: Boolean = false,
    ): IncrementalMountOutput = IncrementalMountOutput(
        id,
        index,
        Rect(0, top, 10, bottom),
        excludeFromIncrementalMount,
        "output-$id",
        hostOutput,
        contentType,
        hostId,
    )

    private fun createCostAwareFixture(
        outputs: List<IncrementalMountOutput>,
        hostOutputIds: Set<Long> = emptySet(),
        inputOutputs: List<IncrementalMountOutput> = outputs,
        mode: GapWorkerMode = PREMOUNT,
        onCreateById: Map<Long, () -> Unit> = emptyMap(),
        onUnmountById: Map<Long, () -> Unit> = emptyMap(),
    ): CostAwarePremountFixture {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val extension = IncrementalMountExtension.getInstance(mode)
      val mountState = createMountState(context)
      val extensionState = mountState.registerExtension(extension)
      val root = RenderTreeNode(
          null,
          TestHostRenderUnit(MountState.ROOT_HOST_ID),
          null,
          Rect(0, 0, 10, 1_000),
          null,
          0,
      )
      val nodesById = mutableMapOf(MountState.ROOT_HOST_ID to root)
      val childCountsByHostId = mutableMapOf<Long, Int>()
      val nodes = mutableListOf(root)
      for (output in outputs.sortedBy { it.index }) {
        val parentId =
            if (output.hostId == IncrementalMountOutput.NO_HOST_ID) {
              MountState.ROOT_HOST_ID
            } else {
              output.hostId
            }
        val parent = nodesById[parentId] ?: root
        val renderUnit =
            if (hostOutputIds.contains(output.id)) {
              TestHostRenderUnit(output.id)
            } else if (
                onCreateById.containsKey(output.id) || onUnmountById.containsKey(output.id)
            ) {
              CallbackTestRenderUnit(
                  output.id,
                  onCreateById[output.id] ?: {},
                  onUnmountById[output.id] ?: {},
              )
            } else {
              TestRenderUnit(output.id)
            }
        val positionInParent = childCountsByHostId[parentId] ?: 0
        childCountsByHostId[parentId] = positionInParent + 1
        val node = RenderTreeNode(
            parent,
            renderUnit,
            null,
            output.bounds,
            null,
            positionInParent,
        )
        nodes.add(node)
        nodesById[output.id] = node
      }
      mountState.mount(
          RenderTree(
              root,
              nodes.toTypedArray(),
              null,
              SizeConstraints.exact(10, 1_000),
              RenderState.NO_ID,
              null,
              null,
              null,
          ),
      )
      val input = TestIncrementalMountExtensionInput(*inputOutputs.toTypedArray())
      if (mode.unmountsIncrementally) {
        extensionState.state.isRegisteredWithGapWorker = true
        extensionState.state.premountLifecycle?.startSession()
        extension.beforeMount(extensionState, input, Rect())
      } else {
        extensionState.state.input = input
      }
      return CostAwarePremountFixture(extension, extensionState, mountState)
    }

    private fun showThenHide(
        fixture: CostAwarePremountFixture,
        output: IncrementalMountOutput,
    ) {
      fixture.extension.onVisibleBoundsChanged(
          fixture.extensionState,
          output.bounds,
          VisibilityChangeReason.SCROLL,
      )
      assertThat(fixture.extensionState.state.premountLifecycle?.stateOf(output.id))
          .isEqualTo(PremountLifecycle.State.SEEN)
      fixture.extension.onVisibleBoundsChanged(
          fixture.extensionState,
          Rect(0, 100, 10, 110),
          VisibilityChangeReason.SCROLL,
      )
    }

    private fun scroll(fixture: CostAwarePremountFixture, rect: Rect) {
      fixture.extension.onVisibleBoundsChanged(
          fixture.extensionState,
          rect,
          VisibilityChangeReason.SCROLL,
      )
    }

    /** What a row gets as RecyclerView removes it: an empty measured pass, then unbind. */
    private fun detach(fixture: CostAwarePremountFixture) {
      scroll(fixture, Rect())
      fixture.extension.onUnbind(fixture.extensionState)
    }

    /** Shows the tree from its top edge, then scrolls it up until its top is cut off. */
    private fun scrollPastTop(fixture: CostAwarePremountFixture) {
      scroll(fixture, Rect(0, 0, 10, 30))
      scroll(fixture, Rect(0, 5, 10, 30))
    }

    private fun premountAll(
        fixture: CostAwarePremountFixture,
        vararg outputs: IncrementalMountOutput,
    ) {
      var frameSequence = 1L
      while (outputs.any { !fixture.extensionState.ownsReference(it.id) } && frameSequence < 20) {
        fixture.premount(frameSequence = frameSequence++)
      }
      for (output in outputs) {
        assertThat(fixture.extensionState.ownsReference(output.id)).isTrue()
      }
    }

    /** A host with a leaf, and a leaf further down that is premounted but never seen. */
    private fun premountedDrainTree(): DrainTree {
      val host = output(id = 10, index = 1, top = 0, bottom = 30)
      val child = output(id = 11, index = 2, top = 20, hostOutput = host, hostId = host.id)
      val unseen = output(id = 12, index = 3, top = 300)
      val fixture = createCostAwareFixture(
          listOf(host, child, unseen),
          hostOutputIds = setOf(host.id),
          mode = COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN,
      )
      premountAll(fixture, host, child, unseen)
      return DrainTree(fixture, host, child, unseen)
    }

    private fun MutableLongList.toLongArrayList(): List<Long> =
        ArrayList<Long>(size).also { list -> forEach { list.add(it) } }

    private fun withQueuedReleases(maxPending: Int, block: () -> Unit) {
      val saved = IncrementalMountExtensionConfigs.maxPendingOffscreenReleases
      IncrementalMountExtensionConfigs.maxPendingOffscreenReleases = maxPending
      try {
        block()
      } finally {
        IncrementalMountExtensionConfigs.maxPendingOffscreenReleases = saved
        IncrementalMountGapWorker.resetWorkerForTesting()
      }
    }

    private fun rejectOnDistinctFrames(
        fixture: CostAwarePremountFixture,
        frameSequences: LongRange,
    ) {
      for (frameSequence in frameSequences) {
        assertThat(
            fixture.premount(
                frameSequence = frameSequence,
                estimateNs = { TWO_SECONDS_NS },
            ),
        )
            .isEqualTo(PremountResult.YIELD)
      }
    }
  }

  private class RecordingExtensionHost(context: Context) : RootHostView(context) {

    val visibleBoundsReasons = ArrayList<VisibilityChangeReason>()

    override fun notifyVisibleBoundsChanged(reason: VisibilityChangeReason) {
      visibleBoundsReasons.add(reason)
    }
  }

  /** Holds mount references for another extension on the same mount state. */
  private class HoldingExtension : MountExtension<Unit, Unit>() {
    override fun createState(): Unit = Unit
  }

  private class CallbackTestRenderUnit(
      id: Long,
      private val onCreate: () -> Unit,
      private val onUnmount: () -> Unit,
  ) : TestRenderUnit(id) {
    override fun createContent(c: Context): View {
      onCreate()
      return super.createContent(c)
    }

    override fun unmountBinders(
        context: MountContext,
        content: Any,
        layoutData: Any?,
        bindData: BindData,
    ) {
      onUnmount()
      super.unmountBinders(context, content, layoutData, bindData)
    }
  }

  private data class DrainTree(
      val fixture: CostAwarePremountFixture,
      val host: IncrementalMountOutput,
      val child: IncrementalMountOutput,
      val unseen: IncrementalMountOutput,
  )

  private class CostAwarePremountFixture(
      val extension: IncrementalMountExtension,
      val extensionState: ExtensionState<IncrementalMountExtensionState>,
      val mountState: MountState,
  ) {
    val queuedReleases: MutableLongList
      get() = checkNotNull(extensionState.state.premountLifecycle).pendingReleases

    val premountResults: MutableList<PremountResult> = mutableListOf()

    /** Releases queued items and returns whether any are still queued. */
    fun releaseQueued(deadlineNs: Long = Long.MAX_VALUE, nanoTime: () -> Long = { 0L }): Boolean {
      extension.releaseQueuedOffscreenForGapWorker(extensionState, deadlineNs, nanoTime)
      return queuedReleases.isNotEmpty()
    }

    /** A gap worker on the real premount dispatcher that records each result. */
    fun startGapWorker(): IncrementalMountGapWorker {
      IncrementalMountGapWorker.resetWorkerForTesting()
      val worker =
          IncrementalMountGapWorker.get(
              null,
              extensionState.tracer,
              { 0L },
              EwmaPremountCostEstimator(),
          ) { extension, extensionState, deadlineNs, frameSequence, nanoTime, estimator ->
            extension
                .premountNextForGapWorker(
                    extensionState,
                    deadlineNs,
                    frameSequence,
                    nanoTime,
                    estimator,
                )
                .also { premountResults.add(it) }
          }
      worker.add(extensionState.mountDelegate, null, costAware = true)
      return worker
    }

    fun premount(
        frameSequence: Long,
        runwayNs: Long = ONE_SECOND_NS,
        nowNs: Long = 0L,
        estimateNs: (Any) -> Long? = { null },
    ): PremountResult =
        extension.premountNextForGapWorker(
            extensionState,
            nowNs + runwayNs,
            frameSequence,
            { nowNs },
            estimator(estimateNs),
        )
  }
}

private fun estimator(estimateNs: (Any) -> Long?): PremountCostEstimator =
    object : PremountCostEstimator {
      override fun estimateNs(contentType: Any): Long? = estimateNs(contentType)

      override fun record(contentType: Any, actualNs: Long): Unit = Unit
    }
