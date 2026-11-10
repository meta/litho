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
import android.os.HandlerThread
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.ComponentTree.Companion.SIZE_UNINITIALIZED
import com.facebook.litho.SizeSpec.AT_MOST
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.UNSPECIFIED
import com.facebook.litho.SizeSpec.getMode
import com.facebook.litho.SizeSpec.getSize
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.BackgroundLayoutLooperRule
import com.facebook.litho.testing.LithoStatsRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestDrawableComponent
import com.facebook.litho.testing.TestDrawableComponent.BlockInPrepareComponentListener
import com.facebook.litho.testing.TestLayoutComponent
import com.facebook.litho.testing.ThreadTestingUtils
import com.facebook.litho.testing.TimeOutSemaphore
import com.facebook.litho.testing.Whitebox
import com.facebook.litho.testing.atMost
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.ComponentTreeTester
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.litho.widget.SimpleStateUpdateEmulator
import com.facebook.litho.widget.SimpleStateUpdateEmulatorSpec
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventBus
import com.facebook.rendercore.debug.DebugEventSubscriber
import com.facebook.rendercore.px
import java.lang.Exception
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import junit.framework.AssertionFailedError
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class ComponentTreeTest {

  @JvmField @Rule var backgroundLayoutLooperRule = BackgroundLayoutLooperRule()
  @JvmField @Rule var lithoStatsRule = LithoStatsRule()

  private lateinit var context: ComponentContext
  private lateinit var component: Component
  private lateinit var layoutThreadShadowLooper: ShadowLooper
  private var widthSpec: Int = 0
  private var widthSpec2: Int = 0
  private var heightSpec: Int = 0
  private var heightSpec2: Int = 0

  @get:Rule val lithoTestRule = LithoTestRule()

  @Before
  fun setup() {
    context = ComponentContext(ApplicationProvider.getApplicationContext<Context>())
    component = SimpleMountSpecTester()
    layoutThreadShadowLooper =
        Shadow.extract(
            Whitebox.invokeMethod<Any>(ComponentTree::class.java, "getDefaultLayoutThreadLooper")
                as Looper,
        )
    widthSpec = makeSizeSpec(39, EXACTLY)
    widthSpec2 = makeSizeSpec(40, EXACTLY)
    heightSpec = makeSizeSpec(41, EXACTLY)
    heightSpec2 = makeSizeSpec(42, EXACTLY)
  }

  private fun captureRenderOnMainThreadStarted(
      componentTree: ComponentTree,
      block: () -> Unit,
  ): DebugEvent {
    val events = mutableListOf<DebugEvent>()
    val subscriber =
        object : DebugEventSubscriber(LithoDebugEvent.RenderOnMainThreadStarted) {
          override fun onEvent(event: DebugEvent) {
            if (event.renderStateId == componentTree.id.toString()) {
              events.add(event)
            }
          }
        }
    DebugEventBus.subscribe(subscriber)
    try {
      block()
    } finally {
      DebugEventBus.unsubscribe(subscriber)
    }
    return events.single()
  }

  private fun sizeSpecsToString(widthSpec: Int, heightSpec: Int): String =
      "w: ${SizeSpec.toSimpleString(widthSpec)}, h: ${SizeSpec.toSimpleString(heightSpec)}"

  private fun runToEndOfTasks() {
    layoutThreadShadowLooper.runToEndOfTasks()
  }

  private fun runOneTask() {
    layoutThreadShadowLooper.runOneTask()
  }

  @After
  fun tearDown() {
    // Clear pending tasks in case test failed
    runToEndOfTasks()
  }

  private fun creationCommonChecks(componentTree: ComponentTree) {
    // Not view or attached yet
    assertNull(componentTree.lithoView)

    // The component input should be the one we passed in
    assertSame(component, componentTree.root)
  }

  private fun postSizeSpecChecks(
      componentTree: ComponentTree,
      widthSpec: Int = this.widthSpec,
      heightSpec: Int = this.heightSpec,
  ) {
    // Spec specified in create
    assertThat(componentTreeHasSizeSpec(componentTree)).isTrue
    val mainThreadLayoutState = componentTree.mainThreadLayoutState
    val committedLayoutState = componentTree.getCommittedLayoutState()
    assertThat(mainThreadLayoutState).isEqualTo(committedLayoutState)
    assertThat(
        mainThreadLayoutState?.isCompatibleComponentAndSpec(
            component.instanceId,
            widthSpec,
            heightSpec,
        ),
    )
        .isTrue
  }

  @Test
  fun testCreate() {
    val componentTree = ComponentTree.create(context, component).build()
    creationCommonChecks(componentTree)

    // Both the main thread and the background layout state shouldn't be calculated yet.
    assertNull(componentTree.mainThreadLayoutState)
    assertNull(componentTree.getCommittedLayoutState())
    assertFalse(componentTreeHasSizeSpec(componentTree))
  }

  @Test
  fun testCreate_ContextIsNotScoped() {
    val scopedContext =
        ComponentContext.withComponentScope(context, Row.create(context).build(), "global_key")
    val componentTree = ComponentTree.create(scopedContext, component).build()
    val c = componentTree.context
    assertNull(c.componentScope)
  }

  @Test
  fun testSetSizeSpec() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setSizeSpec(widthSpec, heightSpec)
    postSizeSpecChecks(componentTree)
  }

  @Test
  fun testSetSizeSpecAsync() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setSizeSpecAsync(widthSpec, heightSpec)

    // Only fields changed but no layout is done yet.
    assertThat(componentTreeHasSizeSpec(componentTree)).isTrue
    assertNull(componentTree.mainThreadLayoutState)
    assertNull(componentTree.getCommittedLayoutState())

    // Now the background thread run the queued task.
    runOneTask()
    runOneTask()
    postSizeSpecChecks(componentTree)
  }

  @Test
  fun testLayoutState_ContextIsNotScoped() {
    val scopedContext =
        ComponentContext.withComponentScope(context, Row.create(context).build(), "global_key")
    val root = Column.create(scopedContext).key("key").build()
    val componentTree = ComponentTree.create(scopedContext, root).build()
    componentTree.setSizeSpecAsync(widthSpec, heightSpec)
    runOneTask()
    runOneTask()
    val layoutState = componentTree.mainThreadLayoutState
    val c = componentTree.context
    assertThat(c).isNotEqualTo(scopedContext)
    assertNull(c.componentScope)
    layoutState?.rootLayoutResult?.let { layoutResult ->
      assertThat(layoutResult is LithoLayoutResult).isTrue
      assertThat((layoutResult as LithoLayoutResult).context).isNotEqualTo(scopedContext)
    }
  }

  private class MeasureListener : ComponentTree.MeasureListener {
    var width: Int = 0
    var height: Int = 0

    override fun onSetRootAndSizeSpec(
        layoutVersion: Int,
        width: Int,
        height: Int,
        stateUpdate: Boolean,
    ) {
      this.width = width
      this.height = height
    }
  }

  @Test
  fun testRacyLayouts() {
    val asyncLatch = CountDownLatch(1)
    val syncLatch = CountDownLatch(1)
    val endOfTest = CountDownLatch(1)
    val widthSpec = makeSizeSpec(100, EXACTLY)
    val heightSpec = makeSizeSpec(100, EXACTLY)
    val asyncWidthSpec = makeSizeSpec(200, EXACTLY)
    val asyncHeightSpec = makeSizeSpec(200, EXACTLY)
    val componentTree = ComponentTree.create(context).build()
    val innerComponentTree = ComponentTree.create(context).build()
    val measureListener = MeasureListener()
    innerComponentTree.addMeasureListener(measureListener)
    val component: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            innerComponentTree.setVersionedRootAndSizeSpec(
                object : InlineLayoutSpec() {},
                widthSpec,
                heightSpec,
                null,
                null,
                c.layoutVersion,
            )
            return super.onCreateLayout(c)
          }
        }
    val asyncComponent: Component =
        object : InlineLayoutSpec() {
          override fun onCreateLayout(c: ComponentContext): Component? {
            ThreadTestingUtils.failSilentlyIfInterrupted {
              syncLatch.countDown()
              assertThat(asyncLatch.await(5, TimeUnit.SECONDS)).describedAs("Timeout!").isTrue()
            }
            innerComponentTree.setVersionedRootAndSizeSpec(
                object : InlineLayoutSpec() {},
                asyncWidthSpec,
                asyncHeightSpec,
                null,
                null,
                c.layoutVersion,
            )
            return super.onCreateLayout(c)
          }
        }
    object : Thread() {
          override fun run() {
            componentTree.setRootAndSizeSpecSync(asyncComponent, 0, 0)
            endOfTest.countDown()
          }
        }
        .start()
    assertThat(syncLatch.await(5, TimeUnit.SECONDS)).describedAs("Timeout!").isTrue
    componentTree.setRootAndSizeSpecSync(component, 0, 0)
    junit.framework.Assert.assertEquals(measureListener.width, 100)
    junit.framework.Assert.assertEquals(measureListener.height, 100)
    asyncLatch.countDown()
    componentTree.setRootAsync(component)
    assertThat(endOfTest.await(5, TimeUnit.SECONDS)).describedAs("Timeout!").isTrue

    // Verify your stuff at this point
    junit.framework.Assert.assertEquals(measureListener.width, 100)
    junit.framework.Assert.assertEquals(measureListener.height, 100)
  }

  @Test
  fun testSetSizeSpecAsyncThenSyncBeforeRunningTask() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setSizeSpecAsync(widthSpec, heightSpec)
    componentTree.setSizeSpec(widthSpec2, heightSpec2)
    runToEndOfTasks()
    postSizeSpecChecks(componentTree, widthSpec2, heightSpec2)
  }

  @Test
  fun testSetRootSynchThenAsyncThenSync() {
    val componentTree = ComponentTree.create(context).build()
    componentTree.setRootAndSizeSpecSync(
        SimpleMountSpecTester(style = Style.width(200.px).height(200.px)),
        makeSizeSpec(0, UNSPECIFIED),
        makeSizeSpec(0, UNSPECIFIED),
    )
    val newComponent = SimpleMountSpecTester(style = Style.width(100.px).height(100.px))
    componentTree.setRootAndSizeSpecAsync(
        newComponent,
        makeSizeSpec(0, UNSPECIFIED),
        makeSizeSpec(0, UNSPECIFIED),
    )
    val size = Size()
    componentTree.setRootAndSizeSpecSync(
        newComponent,
        makeSizeSpec(0, UNSPECIFIED),
        makeSizeSpec(0, UNSPECIFIED),
        size,
    )
    assertThat(size.width).isEqualTo(100)
    assertThat(size.height).isEqualTo(100)
  }

  @Test
  fun testSetSizeSpecAsyncThenSyncAfterRunningTask() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setSizeSpecAsync(widthSpec, heightSpec)
    runToEndOfTasks()
    componentTree.setSizeSpec(widthSpec2, heightSpec2)
    postSizeSpecChecks(componentTree, widthSpec2, heightSpec2)
  }

  @Test
  fun testSetSizeSpecWithOutput() {
    val componentTree = ComponentTree.create(context, component).build()
    val size = Size()
    componentTree.setSizeSpec(widthSpec, heightSpec, size)
    junit.framework.Assert.assertEquals(getSize(widthSpec).toDouble(), size.width.toDouble(), 0.0)
    junit.framework.Assert.assertEquals(getSize(heightSpec).toDouble(), size.height.toDouble(), 0.0)
    postSizeSpecChecks(componentTree)
  }

  @Test
  fun testSetSizeSpecWithOutputWhenAttachedToViewWithSameSpec() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    val size = Size()
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    componentTree.attach()
    componentTree.setSizeSpec(widthSpec, heightSpec, size)
    junit.framework.Assert.assertEquals(getSize(widthSpec).toDouble(), size.width.toDouble(), 0.0)
    junit.framework.Assert.assertEquals(getSize(heightSpec).toDouble(), size.height.toDouble(), 0.0)
    assertThat(componentTree.hasCompatibleLayout(widthSpec, heightSpec)).isTrue
    assertThat(componentTree.root).isEqualTo(component)
  }

  @Test
  fun testSetSizeSpecWithOutputWhenAttachedToViewWithNewSpec() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    val size = Size()
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    componentTree.attach()
    componentTree.setSizeSpec(widthSpec, heightSpec, size)
    junit.framework.Assert.assertEquals(getSize(widthSpec).toDouble(), size.width.toDouble(), 0.0)
    junit.framework.Assert.assertEquals(getSize(heightSpec).toDouble(), size.height.toDouble(), 0.0)
    assertThat(componentTree.hasCompatibleLayout(widthSpec, heightSpec)).isTrue
    assertThat(componentTree.root).isEqualTo(component)
  }

  @Test
  fun testSetCompatibleSizeSpec() {
    val componentTree = ComponentTree.create(context, component).build()
    val size = Size()
    componentTree.setSizeSpec(makeSizeSpec(100, AT_MOST), makeSizeSpec(100, AT_MOST), size)
    junit.framework.Assert.assertEquals(100.0, size.width.toDouble(), 0.0)
    junit.framework.Assert.assertEquals(100.0, size.height.toDouble(), 0.0)
    val firstLayoutState = componentTree.mainThreadLayoutState
    assertThat(firstLayoutState).isNotNull
    componentTree.setSizeSpec(makeSizeSpec(100, EXACTLY), makeSizeSpec(100, EXACTLY), size)
    junit.framework.Assert.assertEquals(100.0, size.width.toDouble(), 0.0)
    junit.framework.Assert.assertEquals(100.0, size.height.toDouble(), 0.0)
    assertThat(componentTree.mainThreadLayoutState).isEqualTo(firstLayoutState)
  }

  @Test
  fun testSetCompatibleSizeSpecWithDifferentRoot() {
    val componentTree = ComponentTree.create(context, component).build()
    val size = Size()
    componentTree.setSizeSpec(makeSizeSpec(100, AT_MOST), makeSizeSpec(100, AT_MOST), size)
    junit.framework.Assert.assertEquals(100.0, size.width.toDouble(), 0.0)
    junit.framework.Assert.assertEquals(100.0, size.height.toDouble(), 0.0)
    val firstLayoutState = componentTree.mainThreadLayoutState
    assertThat(firstLayoutState).isNotNull
    componentTree.setRootAndSizeSpecSync(
        SimpleMountSpecTester(),
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        size,
    )
    Assert.assertNotEquals(firstLayoutState, componentTree.mainThreadLayoutState)
  }

  @Test
  fun testSetRootAndSizeSpecWithTreeProps() {
    val componentTree = ComponentTree.create(context, component).build()
    val size = Size()
    val treePropContainer = TreePropContainer()
    treePropContainer.put(Any::class.java, "hello world")
    componentTree.setRootAndSizeSpecSync(
        SimpleMountSpecTester(),
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        size,
        treePropContainer,
    )
    val c = componentTree.mainThreadLayoutState!!.componentContext
    assertThat(c.treePropContainer?.get(Any::class.java)).isEqualTo("hello world")
  }

  @Test
  fun testDefaultInitialisationAndSetRoot() {
    val componentTree = ComponentTree.create(context).build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    assertThat(componentTree.root).isNotNull
    componentTree.setRootAndSizeSpecSync(component, widthSpec, heightSpec)
    assertThat(componentTree.root).isEqualTo(component)
  }

  @Test
  fun testSetRootWithTreePropsThenMeasure() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    val treePropContainer = TreePropContainer()
    treePropContainer.put(Any::class.java, "hello world")
    componentTree.setRootAndSizeSpecAsync(
        SimpleMountSpecTester(),
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        treePropContainer,
    )
    assertThat(componentTree.getCommittedLayoutState()).isNull()
    componentTree.measure(
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        IntArray(2),
        false,
    )
    val c = componentTree.mainThreadLayoutState!!.componentContext

    assertThat(c.treePropContainer).isNotNull
    assertThat(c.treePropContainer?.get(Any::class.java))
        .isEqualTo(treePropContainer.get(Any::class.java))
  }

  @Test
  fun testSetRootWithTreePropsThenSetSizeSpec() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    val treePropContainer = TreePropContainer()
    treePropContainer.put(Any::class.java, "hello world")
    componentTree.setRootAndSizeSpecAsync(
        SimpleMountSpecTester(),
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        treePropContainer,
    )
    assertThat(componentTree.getCommittedLayoutState()).isNull()
    componentTree.setSizeSpec(makeSizeSpec(200, EXACTLY), makeSizeSpec(200, EXACTLY))
    val c = componentTree.mainThreadLayoutState!!.componentContext
    assertThat(c.treePropContainer).isNotNull
    assertThat(c.treePropContainer?.get(Any::class.java))
        .isEqualTo(treePropContainer.get(Any::class.java))
  }

  @Test
  fun testSetRootWithTreePropsThenSetNewRoot() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    val treePropContainer = TreePropContainer()
    treePropContainer.put(Any::class.java, "hello world")
    componentTree.setRootAndSizeSpecAsync(
        SimpleMountSpecTester(),
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        treePropContainer,
    )
    assertThat(componentTree.getCommittedLayoutState()).isNull()
    componentTree.setRootAndSizeSpecSync(
        SimpleMountSpecTester(),
        makeSizeSpec(200, EXACTLY),
        makeSizeSpec(200, EXACTLY),
    )
    val c = componentTree.mainThreadLayoutState!!.componentContext
    assertThat(c.treePropContainer).isNotNull
    assertThat(c.treePropContainer?.get(Any::class.java))
        .isEqualTo(treePropContainer.get(Any::class.java))
  }

  @Test
  fun testSetRootWithTreePropsThenUpdateState() {
    val caller = SimpleStateUpdateEmulatorSpec.Caller()
    val componentTree =
        ComponentTree.create(
                context,
                SimpleStateUpdateEmulator.create(context).caller(caller).build(),
            )
            .build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    componentTree.setRootAndSizeSpecSync(
        SimpleStateUpdateEmulator.create(context).caller(caller).build(),
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        Size(),
    )
    val treePropContainer = TreePropContainer()
    treePropContainer.put(Any::class.java, "hello world")
    componentTree.setRootAndSizeSpecAsync(
        SimpleStateUpdateEmulator.create(context).caller(caller).build(),
        makeSizeSpec(100, EXACTLY),
        makeSizeSpec(100, EXACTLY),
        treePropContainer,
    )
    caller.increment()
    ShadowLooper.runUiThreadTasks()
    val c = componentTree.mainThreadLayoutState!!.componentContext
    assertThat(c.treePropContainer).isNotNull
    assertThat(c.treePropContainer?.get(Any::class.java))
        .isEqualTo(treePropContainer.get(Any::class.java))
  }

  @Test
  fun testSetInput() {
    val component = TestLayoutComponent.create(context).build()
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.root = this.component
    creationCommonChecks(componentTree)
    assertNull(componentTree.mainThreadLayoutState)
    assertNull(componentTree.getCommittedLayoutState())
    componentTree.setSizeSpec(widthSpec, heightSpec)
    postSizeSpecChecks(componentTree)
  }

  @Test
  fun testSetComponentFromView() {
    val component1 = SimpleMountSpecTester()
    val componentTree1 = ComponentTree.create(context, component1).build()
    val component2 = SimpleMountSpecTester()
    val componentTree2 = ComponentTree.create(context, component2).build()
    assertNull(componentTree1.lithoView)
    assertNull(componentTree2.lithoView)
    val lithoView = LithoView(context)
    lithoView.componentTree = componentTree1
    assertNotNull(componentTree1.lithoView)
    assertNull(componentTree2.lithoView)
    lithoView.componentTree = componentTree2
    assertNull(componentTree1.lithoView)
    assertNotNull(componentTree2.lithoView)
  }

  @Test
  fun testComponentTreeReleaseClearsView() {
    val component = SimpleMountSpecTester()
    val componentTree = ComponentTree.create(context, component).build()
    val lithoView = LithoView(context)
    lithoView.componentTree = componentTree
    assertThat(componentTree).isEqualTo(lithoView.componentTree)
    componentTree.release()
    assertThat(lithoView.componentTree).isNull()
  }

  @Test
  fun testSetTreeToTwoViewsBothAttached() {
    val component = SimpleMountSpecTester()
    val componentTree = ComponentTree.create(context, component).build()

    // Attach first view.
    val lithoView1 = LithoView(context)
    lithoView1.componentTree = componentTree
    lithoView1.onAttachedToWindow()

    // Attach second view.
    val lithoView2 = LithoView(context)
    lithoView2.onAttachedToWindow()

    // Set the component that is already mounted on the first view, on the second attached view.
    // This should be ok.
    lithoView2.componentTree = componentTree
  }

  @Test
  fun testSettingNewViewToTree() {
    val component = SimpleMountSpecTester()
    val componentTree = ComponentTree.create(context, component).build()

    // Attach first view.
    val lithoView1 = LithoView(context)
    lithoView1.componentTree = componentTree
    assertThat(componentTree.lithoView).isEqualTo(lithoView1)
    assertThat(lithoView1.componentTree).isEqualTo(componentTree)

    // Attach second view.
    val lithoView2 = LithoView(context)
    assertNull(lithoView2.componentTree)
    lithoView2.componentTree = componentTree
    assertThat(componentTree.lithoView).isEqualTo(lithoView2)
    assertThat(lithoView2.componentTree).isEqualTo(componentTree)
    assertNull(lithoView1.componentTree)
  }

  @Test
  fun testSetRootAsyncFollowedByMeasureDoesntComputeSyncLayout() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    componentTree.attach()
    val newComponent: Component = SimpleMountSpecTester(color = 1_234)
    componentTree.setRootAsync(newComponent)
    assertThat(componentTree.root).isEqualTo(newComponent)
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(component.instanceId)).isTrue
    runToEndOfTasks()
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
  }

  @Test
  fun `measure with the same specs during a state update's layout does not lay out again`() {
    val caller = SimpleStateUpdateEmulatorSpec.Caller()
    val componentTree = stateUpdateTree(caller, skipRedundantAsyncMeasureLayout = true)

    measureWhileStateUpdateLayoutRuns(componentTree, caller)

    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs("The initial layout and the state update's layout.")
        .isEqualTo(2)
    assertThat(componentTree.mainThreadLayoutState?.rootComponent?.instanceId)
        .isEqualTo(componentTree.root?.instanceId)
  }

  @Test
  fun `measure with the same specs during a state update's layout lays out again without the config`() {
    val caller = SimpleStateUpdateEmulatorSpec.Caller()
    val componentTree = stateUpdateTree(caller, skipRedundantAsyncMeasureLayout = false)

    measureWhileStateUpdateLayoutRuns(componentTree, caller)

    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "The initial layout, the state update's layout, and the measure's async layout.",
        )
        .isEqualTo(3)
    assertThat(componentTree.mainThreadLayoutState?.rootComponent?.instanceId)
        .isEqualTo(componentTree.root?.instanceId)
  }

  @Test
  fun `same-spec measure while the tree targets other specs still lays out at the measured specs`() {
    val caller = SimpleStateUpdateEmulatorSpec.Caller()
    val componentTree = stateUpdateTree(caller, skipRedundantAsyncMeasureLayout = true)

    measureWhileStateUpdateLayoutRuns(componentTree, caller) {
      componentTree.setSizeSpecAsync(widthSpec2, heightSpec2)
    }

    assertThat(componentTree.mainThreadLayoutState?.isCompatibleSpec(widthSpec, heightSpec))
        .describedAs("The tree ends at the specs it was last measured with.")
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.rootComponent?.instanceId)
        .isEqualTo(componentTree.root?.instanceId)
  }

  @Test
  fun `same-spec measure re-renders the current root when an older root's layout was promoted`() {
    val componentTree =
        ComponentTree.create(context, SimpleMountSpecTester(color = 1))
            .componentsConfiguration(
                ComponentsConfiguration.defaultInstance.copy(
                    skipRedundantAsyncMeasureLayout = true,
                ),
            )
            .build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)

    // The older root stays queued on the previous handler, resolves later with a newer version,
    // and its layout is promoted while the newer root is the tree's root.
    val olderRoot = SimpleMountSpecTester(color = 2)
    val newerRoot = SimpleMountSpecTester(color = 3)
    componentTree.setRootAsync(olderRoot)
    val newLayoutThread = createAndStartNewHandlerThread()
    componentTree.updateLayoutThreadHandler(RunnableHandler.DefaultHandler(newLayoutThread.looper))
    componentTree.setRootAsync(newerRoot)
    val newLayoutLooper = Shadow.extract<ShadowLooper>(newLayoutThread.looper)
    newLayoutLooper.runToEndOfTasks()
    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(olderRoot.instanceId))
        .describedAs("Asserting test setup: the older root's layout was promoted.")
        .isTrue

    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    newLayoutLooper.runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newerRoot.instanceId)).isTrue
    newLayoutThread.quit()
  }

  @Test
  fun `an older root that resolves late on the previous handler is never laid out`() {
    val componentTree =
        ComponentTree.create(context, SimpleMountSpecTester(color = 1))
            .componentsConfiguration(
                ComponentsConfiguration.defaultInstance.copy(
                    skipRedundantAsyncMeasureLayout = true,
                    renderWithCurrentInputs = true,
                ),
            )
            .build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)

    // The older root stays queued on the previous handler and runs after the newer root resolved.
    val olderRoot = SimpleMountSpecTester(color = 2)
    val newerRoot = SimpleMountSpecTester(color = 3)
    componentTree.setRootAsync(olderRoot)
    val newLayoutThread = createAndStartNewHandlerThread()
    componentTree.updateLayoutThreadHandler(RunnableHandler.DefaultHandler(newLayoutThread.looper))
    componentTree.setRootAsync(newerRoot)
    val newLayoutLooper = Shadow.extract<ShadowLooper>(newLayoutThread.looper)
    newLayoutLooper.runToEndOfTasks()
    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newerRoot.instanceId)).isTrue

    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    newLayoutLooper.runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()

    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newerRoot.instanceId)).isTrue
    newLayoutThread.quit()
  }

  /** An attached tree with one committed layout at [widthSpec] x [heightSpec]. */
  private fun stateUpdateTree(
      caller: SimpleStateUpdateEmulatorSpec.Caller,
      skipRedundantAsyncMeasureLayout: Boolean,
  ): ComponentTree {
    val componentTree =
        ComponentTree.create(
                context,
                SimpleStateUpdateEmulator.create(context).caller(caller).build(),
            )
            .componentsConfiguration(
                ComponentsConfiguration.defaultInstance.copy(
                    skipRedundantAsyncMeasureLayout = skipRedundantAsyncMeasureLayout,
                ),
            )
            .build()
    componentTree.setLithoView(LithoView(context))
    componentTree.attach()
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs("Asserting test setup: one initial layout.")
        .isEqualTo(1)
    return componentTree
  }

  /**
   * Measures with the tree's specs while the state update's layout is running on the layout thread,
   * after the update cloned the root: the order seen on device when a parent measures the LithoView
   * mid-update.
   */
  private fun measureWhileStateUpdateLayoutRuns(
      componentTree: ComponentTree,
      caller: SimpleStateUpdateEmulatorSpec.Caller,
      beforeMeasure: () -> Unit = {},
  ) {
    val layoutStarted = CountDownLatch(1)
    val measureDone = CountDownLatch(1)
    componentTree.setFutureExecutionListener(
        object : TreeFuture.FutureExecutionListener {
          override fun onPreExecution(
              version: Int,
              futureExecutionType: TreeFuture.FutureExecutionType,
              attribution: String,
          ) {
            if (attribution != LayoutTreeFuture.DESCRIPTION) {
              return
            }
            componentTree.setFutureExecutionListener(null)
            layoutStarted.countDown()
            measureDone.await(5, TimeUnit.SECONDS)
          }

          override fun onPostExecution(version: Int, released: Boolean, attribution: String) = Unit
        },
    )

    caller.incrementAsync()
    val stateUpdateLayout = runOnBackgroundThread { runToEndOfTasks() }
    assertThat(layoutStarted.await(5, TimeUnit.SECONDS)).isTrue

    // Measure posts to the layout thread, which the blocked looper above cannot take.
    val measureLayoutThread = createAndStartNewHandlerThread()
    componentTree.updateLayoutThreadHandler(
        RunnableHandler.DefaultHandler(measureLayoutThread.looper),
    )
    beforeMeasure()
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    measureDone.countDown()

    stateUpdateLayout.acquire()
    Shadow.extract<ShadowLooper>(measureLayoutThread.looper).runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()
  }

  @Test
  fun renderOnMainThreadStartedIncludesAllDiagnosticsForSizeSpecMismatch() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)

    val event =
        captureRenderOnMainThreadStarted(componentTree) {
          componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
        }

    assertThat(event.attribute<Boolean>(LithoDebugEventAttributes.HasMainThreadLayoutState)).isTrue
    assertThat(event.attribute<Boolean>(LithoDebugEventAttributes.SizeSpecsMatch)).isFalse
    assertThat(event.attribute<Boolean>(LithoDebugEventAttributes.IdMatch)).isTrue
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.CurrentWidthSpec))
        .isEqualTo(widthSpec)
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.CurrentHeightSpec))
        .isEqualTo(heightSpec)
    assertThat(event.attribute<String>(LithoDebugEventAttributes.CurrentSizeConstraint))
        .isEqualTo(sizeSpecsToString(widthSpec, heightSpec))
    assertThat(event.attribute<Int>(DebugEventAttribute.WidthSpec)).isEqualTo(widthSpec2)
    assertThat(event.attribute<Int>(DebugEventAttribute.HeightSpec)).isEqualTo(heightSpec2)
    assertThat(event.attribute<String>(LithoDebugEventAttributes.SizeConstraint))
        .isEqualTo(sizeSpecsToString(widthSpec2, heightSpec2))
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.CurrentRootId))
        .isEqualTo(component.instanceId)
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.RootId))
        .isEqualTo(component.instanceId)
  }

  @Test
  fun renderOnMainThreadStartedIncludesAllDiagnosticsForRootIdMismatch() {
    val oldComponent = SimpleMountSpecTester(style = Style.width(100.px).height(100.px))
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val initialWidthSpec = makeSizeSpec(1_000, EXACTLY)
    val initialHeightSpec = makeSizeSpec(1_000, AT_MOST)
    val compatibleWidthSpec = makeSizeSpec(1_000, EXACTLY)
    val compatibleHeightSpec = makeSizeSpec(500, AT_MOST)
    componentTree.measure(initialWidthSpec, initialHeightSpec, IntArray(2), false)
    val newComponent = SimpleMountSpecTester(style = Style.width(100.px).height(100.px))
    componentTree.setRootAsync(newComponent)

    val event =
        captureRenderOnMainThreadStarted(componentTree) {
          componentTree.measure(compatibleWidthSpec, compatibleHeightSpec, IntArray(2), false)
        }

    assertThat(event.attribute<Boolean>(LithoDebugEventAttributes.HasMainThreadLayoutState)).isTrue
    assertThat(event.attribute<Boolean>(LithoDebugEventAttributes.SizeSpecsMatch)).isTrue
    assertThat(event.attribute<Boolean>(LithoDebugEventAttributes.IdMatch)).isFalse
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.CurrentWidthSpec))
        .isEqualTo(initialWidthSpec)
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.CurrentHeightSpec))
        .isEqualTo(initialHeightSpec)
    assertThat(event.attribute<String>(LithoDebugEventAttributes.CurrentSizeConstraint))
        .isEqualTo(sizeSpecsToString(initialWidthSpec, initialHeightSpec))
    assertThat(event.attribute<Int>(DebugEventAttribute.WidthSpec)).isEqualTo(compatibleWidthSpec)
    assertThat(event.attribute<Int>(DebugEventAttribute.HeightSpec)).isEqualTo(compatibleHeightSpec)
    assertThat(event.attribute<String>(LithoDebugEventAttributes.SizeConstraint))
        .isEqualTo(sizeSpecsToString(compatibleWidthSpec, compatibleHeightSpec))
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.CurrentRootId))
        .isEqualTo(oldComponent.instanceId)
    assertThat(event.attribute<Int>(LithoDebugEventAttributes.RootId))
        .isEqualTo(newComponent.instanceId)
  }

  @Test
  fun testSetRootAsyncFollowedByNonCompatibleMeasureComputesSyncLayout() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    componentTree.measure(widthSpec, heightSpec, IntArray(2), false)
    componentTree.attach()
    val newComponent: Component = SimpleMountSpecTester(color = 1_234)
    componentTree.setRootAsync(newComponent)
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue

    // Clear tasks
    runToEndOfTasks()
  }

  /*
   * This test is meant to simulate a LithoView in a LinearLayout or RelativeLayout where it gets
   * measured twice in a single layout pass with the second measurement depending on the result
   * of the first (e.g. if the LithoView measures to be 500px in the first measure pass, the parent
   * remeasures with AT_MOST 500). We need to make sure we end up showing the correct root and
   * respecting the size it would have within its parent.
   *
   * In this test, the first component has fixed size 100x100 and will produce a layout compatible
   * with any specs that have AT_MOST >=100. In the test, we expect the initial measure with
   * AT_MOST 2000, an async setRoot call, and then a double measure from the parent, first with
   * AT_MOST 1000 (compatible with the old root) and then with AT_MOST <result of first measure>.
   * We need to make sure the second component (which unlike the first component will take up the
   * full size of the parent) ends up being allocated AT_MOST 1000 and not constrained to the 100px
   * the first component measured to.
   */
  @Test
  fun testSetRootAsyncFollowedByMeasurementInParentWithDoubleMeasure() {
    if (true) {
      return
    }
    val componentTree =
        ComponentTree.create(context, Row.create(context).minWidthPx(100).minHeightPx(100)).build()
    val lithoView = LithoView(context)
    lithoView.componentTree = componentTree
    val parent = DoubleMeasureViewGroup(context.androidContext)
    parent.addView(lithoView)
    lithoView.measure(atMost(2_000), atMost(2_000))
    val blockInPrepare = BlockInPrepareComponentListener()
    val newComponent = TestDrawableComponent.create(context).build()
    blockInPrepare.setDoNotBlockOnThisThread()
    newComponent.setTestComponentListener(blockInPrepare)
    componentTree.setRootAsync(newComponent)
    val asyncLayout = backgroundLayoutLooperRule.runToEndOfTasksAsync()
    blockInPrepare.awaitPrepareStart()

    // This is a bit of a hack: Robolectric's ShadowLegacyLooper implementation is synchronized
    // on runToEndOfTasks and post(). Since we are simulating being in the middle of calculating a
    // layout, this means that we can't post() to the same Handler (as we will try to do in measure)
    // The "fix" here is to update the layout thread to a new handler/looper that can be controlled
    // separately.
    val newHandlerThread = createAndStartNewHandlerThread()
    componentTree.updateLayoutThreadHandler(RunnableHandler.DefaultHandler(newHandlerThread.looper))
    parent.requestLayout()
    parent.measure(
        View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY),
    )
    parent.layout(0, 0, 1_000, 1_000)
    blockInPrepare.allowPrepareToComplete()
    asyncLayout.acquire()
    assertThat(lithoView.width).isEqualTo(1_000)
    assertThat(lithoView.height).isEqualTo(1_000)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(1_000)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a layout from measure.",
        )
        .isEqualTo(3)
  }

  /*
   * This test is meant to simulate a LithoView in a LinearLayout or RelativeLayout where it gets
   * measured twice in a single layout pass with the second measurement depending on the result
   * of the first (e.g. if the LithoView measures to be 500px in the first measure pass, the parent
   * remeasures with AT_MOST 500). We need to make sure we end up showing the correct root and
   * respecting the size it would have within its parent.
   *
   * In this test, we simulate a situation where:
   * - We have already set a set a root successfully with height 150px.
   * - We set a second root (async) with height 200px.
   * - While the second root is processing, a new measure happens, and it should detect that the
   * root has changed, and therefore request a sync resolve. If this didn't happen, the second measure (which constrains)
   * would be ran synchronously, using the wrong size specs from the first iteration of children measurement.
   */
  @Test
  fun testSetRootAsyncFollowedByMeasurementInParentWithDoubleMeasureWithSameSizeSpecs() {
    val firstIterationHeightPx = 150
    val secondIterationHeightPx = 200

    // Setting up Component Tree and LithoView
    val componentTree =
        ComponentTree.create(context)
            .componentsConfiguration(
                ComponentsConfiguration.defaultInstance.copy(performExactSameSpecsCheck = false),
            )
            .build()

    val testLithoView =
        lithoTestRule.render(componentTree = componentTree) {
          TestDrawableComponent.create(context)
              .widthPx(100)
              .heightPx(firstIterationHeightPx)
              .build()
        }

    // Add Litho View to Double Measure Parent.
    val parent =
        DoubleMeasureViewGroup(context = context.androidContext).apply {
          addView(testLithoView.lithoView)
        }

    // Update Size Specs
    testLithoView.setSizeSpecs(atMost(2_000), atMost(2_000))
    testLithoView.measure()

    // Set Async Root and block on Prepare.
    val blockInPrepare = BlockInPrepareComponentListener()
    val secondComponent =
        TestDrawableComponent.create(context).widthPx(100).heightPx(secondIterationHeightPx).build()
    blockInPrepare.setDoNotBlockOnThisThread()
    secondComponent.setTestComponentListener(blockInPrepare)
    componentTree.setRootAsync(secondComponent)
    val asyncLayout = backgroundLayoutLooperRule.runToEndOfTasksAsync()
    blockInPrepare.awaitPrepareStart()

    // This is a bit of a hack: Robolectric's ShadowLegacyLooper implementation is synchronized
    // on runToEndOfTasks and post(). Since we are simulating being in the middle of calculating a
    // layout, this means that we can't post() to the same Handler (as we will try to do in measure)
    // The "fix" here is to update the layout thread to a new handler/looper that can be controlled
    // separately.
    val newHandlerThread = createAndStartNewHandlerThread()
    componentTree.updateLayoutThreadHandler(RunnableHandler.DefaultHandler(newHandlerThread.looper))

    // This is used to detect the resolve that is happening from measure, and let the
    // blocked one through.
    componentTree.setFutureExecutionListener(
        object : TreeFuture.FutureExecutionListener {
          override fun onPreExecution(
              version: Int,
              futureExecutionType: TreeFuture.FutureExecutionType,
              attribution: String,
          ) {
            if (attribution == ResolveTreeFuture.DESCRIPTION) {
              blockInPrepare.allowPrepareToComplete()
              asyncLayout.acquire()
            }
            componentTree.setFutureExecutionListener(null)
          }

          override fun onPostExecution(version: Int, released: Boolean, attribution: String) {
            //
          }
        },
    )

    // Measure the parent.
    parent.requestLayout()
    parent.measure(exactly(2000), exactly(2000))
    parent.layout(0, 0, 2000, 2000)

    lithoTestRule.idle()

    assertThat(testLithoView.lithoView.width).isEqualTo(100)
    assertThat(testLithoView.lithoView.height).isEqualTo(200)
    assertThat(componentTree.root).isEqualTo(secondComponent)
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (secondComponent as Component).instanceId,
        ),
    )
        .isTrue

    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a layout from measure.",
        )
        .isEqualTo(3)
  }

  /*
   * Context for the test:
   * - The original component mComponent will measure to height 1000 with heightSpec1 and to 0 with
   *   heightSpec2
   * - newComponent will measure to height 1000 with heightSpec1 and to 0 with heightSpec2
   *
   * In this test, we setRootAsync but before any of its code runs, we measure with new width/height
   * specs that both the original component and the new component are incompatible with.
   */
  @Test
  fun testSetRootAsyncWithIncompatibleMeasureBeforeStart() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, EXACTLY)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(0, UNSPECIFIED)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val newComponent: Component = SimpleMountSpecTester(color = 1_234)
    componentTree.setRootAsync(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should not be compatible.")
        .isFalse
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)

    // Since the layout thread hasn't started the async layout, we know it will capture the updated
    // size specs when it does run
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .describedAs(
            "The old component spec is not compatible so we should do a sync layout with the new root.",
        )
        .isTrue
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    ShadowLooper.runUiThreadTasks()

    // Once the async layout finishes, the main thread should have the updated layout.
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(0)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout and one layout after measure. The async layout shouldn't happen.",
        )
        .isEqualTo(2)
  }

  /*
   * Context for the test:
   * - oldComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   * - newComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   *
   * In this test, we setRootAsync but before any of its code runs, we measure with new width/height
   * specs that both the original component and the new component are compatible with.
   */
  @Test
  fun testSetRootAsyncWithCompatibleMeasureBeforeStart() {
    val oldComponent =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val newComponent: Component =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    componentTree.setRootAsync(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should be compatible already.")
        .isTrue
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(100)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs("We expect one initial layout and the async layout.")
        .isEqualTo(2)
  }

  /*
   * Context for the test:
   * - oldComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   * - newComponent will measure to height 1000 with heightSpec1 and to 500 with heightSpec2
   *
   * In this test, we setRootAsync but before any of its code runs, we measure with new width/height
   * specs the original component is compatible with but the new component is incompatible with.
   */
  @Test
  fun testSetRootAsyncWithIncompatibleMeasureButCompatibleMeasureForExistingLayoutBeforeStart() {
    val oldComponent =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val newComponent: Component =
        SimpleMountSpecTester(style = Style.flex(grow = 1f), color = 1_234)
    componentTree.setRootAsync(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should be compatible already.")
        .isTrue
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout and one layout after measure. The async layout shouldn't happen.",
        )
        .isEqualTo(2)
  }

  /*
   * Context for the test:
   * - The original component mComponent will measure to height 1000 with heightSpec1 and to 0 with
   *   heightSpec2
   * - newComponent will measure to height 1000 with heightSpec1 and to 0 with heightSpec2
   *
   * In this test, we setRootAsync and let its code run, but before it is promoted on the main
   * thread, we measure with new width/height specs both the original component and the new
   * component are incompatible with.
   */
  @Test
  fun testSetRootAsyncWithIncompatibleMeasureAfterFinish() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, EXACTLY)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(0, UNSPECIFIED)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val newComponent: Component = SimpleMountSpecTester(color = 1_234)
    componentTree.setRootAsync(newComponent)
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should not be compatible.")
        .isFalse
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
        .withFailMessage(
            "The main thread should calculate a new layout synchronously because the async layout will not be used once it completes",
        )
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(0)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a final layout after measure.",
        )
        .isEqualTo(3)
  }

  /*
   * Context for the test:
   * - oldComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   * - newComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   *
   * In this test, we setRootAsync and let its code run, but before it is promoted on the main
   * thread, we measure with new width/height specs both the original component and the new
   * component are compatible with.
   */
  @Test
  fun testSetRootAsyncWithCompatibleMeasureAfterFinish() {
    val oldComponent =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val newComponent: Component =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    componentTree.setRootAsync(newComponent)
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should be compatible already.")
        .isTrue
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
        .withFailMessage(
            "The main thread should promote the committed layout to the UI thread in measure.",
        )
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(100)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs("We expect one initial layout and the async layout.")
        .isEqualTo(2)
  }

  /*
   * Context for the test:
   * - oldComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   * - newComponent will measure to height 1000 with heightSpec1 and to 500 with heightSpec2
   *
   * In this test, we setRootAsync and let its code run, but before it is promoted on the main
   * thread, we measure with new width/height specs both the original component and the new
   * component are compatible with.
   */
  @Test
  fun testSetRootAsyncWithIncompatibleMeasureButCompatibleMeasureForExistingLayoutAfterFinish() {
    val oldComponent =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val newComponent: Component =
        SimpleMountSpecTester(style = Style.flex(grow = 1f), color = 1_234)
    componentTree.setRootAsync(newComponent)
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should be compatible already.")
        .isTrue
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newComponent.instanceId))
        .isTrue
        .withFailMessage(
            "The main thread will calculate a new layout synchronously because the background layout isn't compatible.",
        )
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a final layout from measure.",
        )
        .isEqualTo(3)
  }

  /*
   * Context for the test:
   * - The original component mComponent will measure to height 1000 with heightSpec1 and to 0 with
   *   heightSpec2
   * - newComponent will measure to height 1000 with heightSpec1 and to 0 with heightSpec2
   *
   * In this test, we setRootAsync and let its code run but before it can commit on the background
   * thread, we measure with new width/height specs both the original component and the new
   * component are incompatible with.
   */
  @Test
  fun testSetRootAsyncWithIncompatibleMeasureDuringLayout() {
    if (true) {
      return
    }
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, EXACTLY)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(0, UNSPECIFIED)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val blockInPrepare = BlockInPrepareComponentListener()
    blockInPrepare.setDoNotBlockOnThisThread()
    val newComponent = TestDrawableComponent.create(context).color(1_234).build()
    newComponent.setTestComponentListener(blockInPrepare)
    componentTree.setRootAsync(newComponent)
    val asyncLayoutFinish = runOnBackgroundThread { runToEndOfTasks() }
    blockInPrepare.awaitPrepareStart()

    // At this point, the Layout thread is blocked in prepare (waiting for unblockAsyncPrepare) and
    // will have already captured the "bad" specs, but not completed its layout. We expect the main
    // thread to determine that this async layout will not be correct and that it needs to compute
    // one in measure
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should not be compatible.")
        .isFalse
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
        .withFailMessage(
            "The main thread should calculate a new layout synchronously because the async layout will not have compatible size specs",
        )
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(0)

    // Finally, let the async layout finish and make sure it doesn't replace the layout from measure
    blockInPrepare.allowPrepareToComplete()
    asyncLayoutFinish.acquire()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(0)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a final layout after measure.",
        )
        .isEqualTo(3)
  }

  /*
   * Context for the test:
   * - oldComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   * - newComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   *
   * In this test, we setRootAsync and let its code run but before it can commit on the background
   * thread, we measure with new width/height specs both the original component and the new
   * component are compatible with.
   */
  @Test
  fun testSetRootAsyncWithCompatibleMeasureDuringLayout() {
    if (true) {
      return
    }
    val oldComponent =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val blockInPrepare = BlockInPrepareComponentListener()
    blockInPrepare.setDoNotBlockOnThisThread()
    val newComponent =
        TestDrawableComponent.create(context).widthPx(100).heightPx(100).color(1_234).build()
    newComponent.setTestComponentListener(blockInPrepare)
    componentTree.setRootAsync(newComponent)
    val asyncLayoutFinish = runOnBackgroundThread { runToEndOfTasks() }
    blockInPrepare.awaitPrepareStart()

    // At this point, the Layout thread is blocked in prepare (waiting for unblockAsyncPrepare) and
    // will have already captured the "bad" specs, but not completed its layout.

    // This is a bit of a hack: Robolectric's ShadowLegacyLooper implementation is synchronized
    // on runToEndOfTasks and post(). Since we are simulating being in the middle of calculating a
    // layout, this means that we can't post() to the same Handler (as we will try to do in measure)
    // The "fix" here is to update the layout thread to a new handler/looper that can be controlled
    // separately.
    val newHandlerThread = createAndStartNewHandlerThread()
    componentTree.updateLayoutThreadHandler(RunnableHandler.DefaultHandler(newHandlerThread.looper))
    val newThreadLooper: ShadowLooper = Shadow.extract(newHandlerThread.looper)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should be compatible already.")
        .isTrue
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
        .withFailMessage(
            "The main thread should calculate a new layout synchronously because the async layout will not have compatible size specs",
        )
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(100)

    // Finally, let the async layout finish and make sure it doesn't replace the layout from measure
    blockInPrepare.allowPrepareToComplete()
    asyncLayoutFinish.acquire()
    newComponent.setTestComponentListener(null)
    newThreadLooper.runToEndOfTasks()
    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(100)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a final layout after measure.",
        )
        .isEqualTo(3)
    newHandlerThread.quit()
  }

  /*
   * Context for the test:
   * - oldComponent will measure to height 100 with heightSpec1 and to 100 with heightSpec2
   * - newComponent will measure to height 1000 with heightSpec1 and to 500 with heightSpec2
   *
   * In this test, we setRootAsync and let its code run but before it can commit on the background
   * thread, we measure with new width/height specs that the original component is compatible with
   * but and the new component isn't.
   */
  @Test
  fun testSetRootAsyncWithIncompatibleMeasureButCompatibleMeasureForExistingLayoutDuringLayout() {
    if (true) {
      return
    }
    val oldComponent =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    val blockInPrepare = BlockInPrepareComponentListener()
    blockInPrepare.setDoNotBlockOnThisThread()
    val newComponent = TestDrawableComponent.create(context).flexGrow(1f).color(1_234).build()
    newComponent.setTestComponentListener(blockInPrepare)
    componentTree.setRootAsync(newComponent)
    val asyncLayoutFinish = runOnBackgroundThread { runToEndOfTasks() }
    blockInPrepare.awaitPrepareStart()

    // At this point, the Layout thread is blocked in prepare (waiting for unblockAsyncPrepare) and
    // will have already captured the "bad" specs, but not completed its layout.

    // This is a bit of a hack: Robolectric's ShadowLegacyLooper implementation is synchronized
    // on runToEndOfTasks and post(). Since we are simulating being in the middle of calculating a
    // layout, this means that we can't post() to the same Handler (as we will try to do in measure)
    // The "fix" here is to update the layout thread to a new handler/looper that can be controlled
    // separately.
    val newHandlerThread = createAndStartNewHandlerThread()
    componentTree.updateLayoutThreadHandler(RunnableHandler.DefaultHandler(newHandlerThread.looper))
    val newThreadLooper: ShadowLooper = Shadow.extract(newHandlerThread.looper)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should be compatible already.")
        .isTrue
    componentTree.measure(widthSpec2, heightSpec2, IntArray(2), false)
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
        .withFailMessage(
            "The main thread should calculate a new layout synchronously because the async layout will not have compatible size specs",
        )
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)

    // Finally, let the async layout finish and make sure it doesn't replace the layout from measure
    blockInPrepare.allowPrepareToComplete()
    asyncLayoutFinish.acquire()
    newComponent.setTestComponentListener(null)
    newThreadLooper.runToEndOfTasks()
    runToEndOfTasks()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a final layout from measure.",
        )
        .isEqualTo(3)
    newHandlerThread.quit()
  }

  @Test
  fun testSetRootAndSetSizeSpecInParallelProduceCorrectResult() {
    if (true) {
      return
    }
    val oldComponent =
        SimpleMountSpecTester(style = Style.width(100.px).height(100.px), color = 1_234)
    val componentTree = ComponentTree.create(context, oldComponent).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)

    // This new component will produce a layout compatible with the first specs but not the second
    // specs.
    val newComponent = TestDrawableComponent.create(context).flexGrow(1f).color(1_234).build()
    val blockInPrepare = BlockInPrepareComponentListener()
    blockInPrepare.setDoNotBlockOnThisThread()
    newComponent.setTestComponentListener(blockInPrepare)
    componentTree.setRootAsync(newComponent)
    val asyncLayout = backgroundLayoutLooperRule.runToEndOfTasksAsync()
    blockInPrepare.awaitPrepareStart()
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2))
        .describedAs("Asserting test setup, second set of specs should be compatible.")
        .isTrue
    componentTree.setSizeSpec(widthSpec2, heightSpec2)
    blockInPrepare.allowPrepareToComplete()
    asyncLayout.acquire()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.root).isEqualTo(newComponent)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(
        componentTree.mainThreadLayoutState?.isForComponentId(
            (newComponent as Component).instanceId,
        ),
    )
        .isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "We expect one initial layout, the async layout (thrown away), and a layout from setSizeSpec.",
        )
        .isEqualTo(3)
  }

  @Test
  fun testComponentTreeHasLatestMainThreadLayoutStateAfterMeasure() {
    val blockInPrepare = BlockInPrepareComponentListener()
    val blockingComponent = TestDrawableComponent.create(context).flexGrow(1f).color(1_234).build()
    blockingComponent.setTestComponentListener(blockInPrepare)
    val lithoView = LithoView(context)
    val componentTree = ComponentTree.create(context, blockingComponent).build()
    lithoView.componentTree = componentTree
    val widthSpec = makeSizeSpec(1_000, AT_MOST)
    val heightSpec = makeSizeSpec(500, EXACTLY)
    val newRoot = Row.create(context).flexGrow(1f).build()
    val backgroundLayout = runOnBackgroundThread {
      blockInPrepare.setDoNotBlockOnThisThread()
      blockInPrepare.awaitPrepareStart()
      componentTree.root = newRoot
      blockInPrepare.allowPrepareToComplete()
    }
    componentTree.attach()
    lithoView.measure(widthSpec, heightSpec)
    backgroundLayout.acquire()

    // In this case, because the background layout completed before the measure layout, the
    // measure layout won't be committed. We want to ensure that we still have a non-null and
    // compatible main thread LayoutState at the end of measure.
    assertThat(componentTree.hasCompatibleLayout(widthSpec, heightSpec)).isTrue
    assertThat(componentTree.mainThreadLayoutState).isNotNull
    assertThat(componentTree.mainThreadLayoutState?.isForComponentId(newRoot.instanceId)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs(
            "One from measure that will be thrown away and one from the background setRoot",
        )
        .isEqualTo(2)
  }

  @Test
  fun testSetSizeSpecAsyncFollowedBySetSizeSpecSyncBeforeStartReturnsCorrectSize() {
    val component = SimpleMountSpecTester(style = Style.flex(grow = 1f), color = 1_234)
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.setLithoView(LithoView(context))
    val widthSpec1 = makeSizeSpec(1_000, EXACTLY)
    val heightSpec1 = makeSizeSpec(1_000, AT_MOST)
    val widthSpec2 = makeSizeSpec(500, EXACTLY)
    val heightSpec2 = makeSizeSpec(500, AT_MOST)
    componentTree.attach()
    componentTree.measure(widthSpec1, heightSpec1, IntArray(2), false)
    componentTree.setSizeSpecAsync(widthSpec2, heightSpec2)
    val size = Size()
    componentTree.setSizeSpec(widthSpec2, heightSpec2, size)
    assertThat(size).isEqualToComparingFieldByField(Size(500, 500))
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    ShadowLooper.runUiThreadTasks()
    assertThat(componentTree.root).isEqualTo(component)
    assertThat(componentTree.hasCompatibleLayout(widthSpec2, heightSpec2)).isTrue
    assertThat(componentTree.mainThreadLayoutState?.height).isEqualTo(500)
    assertThat(componentTree.mainThreadLayoutState?.width).isEqualTo(500)
    assertThat(lithoStatsRule.componentCalculateLayoutCount)
        .describedAs("We expect one initial layout and the async layout.")
        .isEqualTo(2)
  }

  @Test
  fun testSetRootAfterRelease() {
    val componentTree = ComponentTree.create(context, component).build()
    componentTree.release()

    // Verify we don't crash
    componentTree.root = SimpleMountSpecTester()
  }

  @Test
  fun testCachedValues() {
    val componentTree = ComponentTree.create(context, component).build()
    val cacheKey = HookGlobalKey(StringGlobalKey("globalKey"), 0, RenderScope.Resolve)
    assertThat(componentTree.getCachedValue(cacheKey, "key1", false)).isNull()
    componentTree.putCachedValue(cacheKey, "key1", "value1", false)
    assertThat(componentTree.getCachedValue(cacheKey, "key1", false)).isEqualTo("value1")
    assertThat(componentTree.getCachedValue(cacheKey, "key2", false)).isNull()
  }

  @Test
  fun testVersioningCalculate() {
    val root1: Component = ComponentTreeTester()
    val componentTree = ComponentTree.create(context).build()
    componentTree.setVersionedRootAndSizeSpec(root1, widthSpec, heightSpec, Size(), null, 0)
    var layoutState = componentTree.mainThreadLayoutState
    junit.framework.Assert.assertEquals(root1.instanceId, layoutState?.rootComponent?.instanceId)
    val root2: Component = ComponentTreeTester()
    val root3: Component = ComponentTreeTester()
    componentTree.setVersionedRootAndSizeSpec(root3, widthSpec, heightSpec, Size(), null, 2)
    layoutState = componentTree.mainThreadLayoutState
    junit.framework.Assert.assertEquals(root3.instanceId, layoutState?.rootComponent?.instanceId)
    componentTree.setVersionedRootAndSizeSpec(root2, widthSpec, heightSpec, Size(), null, 1)
    layoutState = componentTree.mainThreadLayoutState
    junit.framework.Assert.assertEquals(root3.instanceId, layoutState?.rootComponent?.instanceId)
  }

  @Test
  fun testAttachFromListenerDoesntCrash() {
    val component = TestLayoutComponent.create(context).build()
    val lithoView = LithoView(context)
    val componentTree = ComponentTree.create(context, component).build()
    lithoView.componentTree = componentTree
    componentTree.newLayoutStateReadyListener = ComponentTree.NewLayoutStateReadyListener {
      lithoView.onAttachedToWindow()
    }
    componentTree.setRootAndSizeSpecSync(this.component, widthSpec, heightSpec)
  }

  @Test
  fun testDetachFromListenerDoesntCrash() {
    val component = TestLayoutComponent.create(context).build()
    val lithoView = LithoView(context)
    val componentTree = ComponentTree.create(context, component).build()
    lithoView.componentTree = componentTree
    lithoView.onAttachedToWindow()
    componentTree.newLayoutStateReadyListener =
        ComponentTree.NewLayoutStateReadyListener { componentTree ->
          lithoView.onDetachedFromWindow()
          componentTree.clearLithoView()
        }
    componentTree.setRootAndSizeSpecSync(this.component, widthSpec, heightSpec)
  }

  @Test
  fun testSwapComponentTreeOnLithoViewWhileComponentTreeAttached() {
    class TestComponent(val text: String) : KComponent() {
      override fun ComponentScope.render(): Component? {
        return Row { child(Text(style = Style.viewTag(text), text = text)) }
      }
    }

    fun measureLayoutLithoView(lithoView: LithoView) {
      lithoView.onAttachedToWindow()
      lithoView.measure(exactly(1000), exactly(1000))
      lithoView.layout(0, 0, 1000, 1000)
    }

    val lithoView = LithoView(context)
    val componentTree1 = ComponentTree.create(context, TestComponent(text = "First text")).build()
    val componentTree2 = ComponentTree.create(context, TestComponent(text = "Second text")).build()
    lithoView.componentTree = componentTree1
    measureLayoutLithoView(lithoView)

    assertThat(lithoView.findViewWithTag("First text") as? View).isNotNull

    lithoView.onDetachedFromWindow()
    // Simulate ComponentTree staying attached due to being in a bad state (e.g. due to error
    // boundary)
    componentTree1.attach()
    lithoView.componentTree = componentTree2
    measureLayoutLithoView(lithoView)

    assertThat(lithoView.findViewWithTag("Second text") as? View).isNotNull
  }

  @Test
  fun testComponentIdIsUniqueAfterShallowCopy() {
    val firstComponent = Column.create(context).build()
    val copyFirstComponent = firstComponent.makeShallowCopyWithNewId()
    val secondComponent = Column.create(context).build()
    assertThat(secondComponent.instanceId).isNotEqualTo(copyFirstComponent.instanceId)
    assertThat(secondComponent.instanceId).isNotEqualTo(firstComponent.instanceId)
    assertThat(firstComponent.instanceId).isNotEqualTo(secondComponent.instanceId)
  }

  private class DoubleMeasureViewGroup(context: Context?) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
      if (childCount == 0) {
        throw RuntimeException("Missing child!")
      }
      if (getMode(widthMeasureSpec) == UNSPECIFIED || getMode(heightMeasureSpec) == UNSPECIFIED) {
        throw RuntimeException("Must give AT_MOST or EXACT measurements")
      }
      val child = getChildAt(0)

      // let children measure themselves unconstrained
      child.measure(atMost(getSize(widthMeasureSpec)), atMost(heightMeasureSpec))

      // constrain the height
      child.measure(atMost(child.measuredWidth), atMost(child.measuredHeight))

      setMeasuredDimension(child.measuredWidth, child.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
      if (childCount > 0) {
        val child = getChildAt(0)
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
      }
    }
  }

  companion object {
    private fun componentTreeHasSizeSpec(componentTree: ComponentTree): Boolean {
      synchronized(componentTree) {
        return componentTree.getWidthSpec() != SIZE_UNINITIALIZED &&
            componentTree.getHeightSpec() != SIZE_UNINITIALIZED
      }
    }

    private fun runOnBackgroundThread(runnable: Runnable): TimeOutSemaphore {
      val latch = TimeOutSemaphore(0)
      Thread {
        try {
          runnable.run()
        } catch (e: Exception) {
          latch.setException(e)
        } catch (e: AssertionFailedError) {
          latch.setException(e)
        }
        latch.release()
      }
          .start()
      return latch
    }

    private fun createAndStartNewHandlerThread(): HandlerThread {
      val newHandlerThread = HandlerThread(
          "test_handler_thread",
          ComponentsConfiguration.DEFAULT_BACKGROUND_THREAD_PRIORITY,
      )
      newHandlerThread.start()
      return newHandlerThread
    }
  }
}
