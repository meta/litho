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

import android.view.View
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/**
 * Tests for [ComponentTree.setOrderedRootAndSizeSpec]. Background layouts go to a queue the test
 * runs, and a layout can call back into the test while it runs, so every interleaving is fixed.
 */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class ComponentTreeOrderedRootRequestTest {

  private lateinit var context: ComponentContext
  private val layoutQueue = TaskQueue()
  private val recorder = LayoutRecorder()
  private val trees = ArrayList<ComponentTree>()

  @Before
  fun setup() {
    context = ComponentContext(getApplicationContext())
  }

  @After
  fun tearDown() {
    trees.forEach { tree -> tree.release() }
  }

  @Test
  fun `an older ordered request that arrives after a newer one is ignored`() {
    val tree = tree()
    tree.request(Item("newer", recorder), exactly(100), version = 2)
    assertThat(tree.committedLabel()).isEqualTo("newer")

    // A sync and an async request for the component that the newer request replaced.
    val staleOutput = Size()
    tree.request(Item("older", recorder), exactly(50), version = 1, output = staleOutput)
    tree.request(Item("older", recorder), exactly(50), version = 1, isAsync = true)
    runLayoutQueue()

    assertThat(recorder.labels()).doesNotContain("older")
    assertThat(tree.committedLabel()).isEqualTo("newer")
    assertThat((tree.root as Item).label).isEqualTo("newer")
    assertThat(staleOutput.width).isEqualTo(0)

    // A request with the newest version still applies.
    tree.request(Item("same version", recorder), exactly(60), version = 2)
    assertThat(tree.committedLabel()).isEqualTo("same version")
    assertThat(tree.committedLayout().width).isEqualTo(60)
  }

  @Test
  fun `unversioned requests still apply after ordered requests`() {
    val tree = tree()
    tree.request(Item("ordered", recorder), exactly(100), version = 5)

    tree.setRoot(Item("setRoot", recorder))
    assertThat(tree.committedLabel()).isEqualTo("setRoot")

    tree.setRootAsync(Item("setRootAsync", recorder))
    runLayoutQueue()
    assertThat(tree.committedLabel()).isEqualTo("setRootAsync")

    tree.setSizeSpec(exactly(80), unspecified(), Size())
    assertThat(tree.committedLayout().width).isEqualTo(80)

    tree.request(Item("ordered again", recorder), exactly(100), version = 6)
    assertThat(tree.committedLabel()).isEqualTo("ordered again")
  }

  @Test
  fun `an error boundary's fallback applies to a tree that takes ordered requests`() {
    val errors = ArrayList<String>()
    val configuration =
        ComponentsConfiguration.defaultInstance.copy(
            errorEventHandler =
                object : ErrorEventHandler() {
                  override fun onError(context: ComponentContext, exception: Exception): Component {
                    errors.add(exception.message.orEmpty())
                    return Item("fallback", recorder)
                  }
                },
        )
    val tree = tree(configuration)
    tree.request(Item("ordered", recorder), exactly(100), version = 1)

    // An event handler of the item throws: the tree's error handler sets the fallback through
    // setRoot.
    checkNotNull(recorder.failingEventHandlers["ordered"]).dispatchEvent(Unit)
    runLayoutQueue()
    ShadowLooper.idleMainLooper()

    assertThat(errors).hasSize(1)
    assertThat(errors[0]).contains("event failed")
    assertThat(tree.committedLabel()).isEqualTo("fallback")

    tree.request(Item("ordered again", recorder), exactly(100), version = 2)
    assertThat(tree.committedLabel()).isEqualTo("ordered again")
  }

  @Test
  fun `a compatible ordered reuse keeps a layout for older specs from committing`() {
    val tree = tree()

    assertThat(olderSpecsLayoutAfterReuse(tree, OrderedRequests(tree))).isEqualTo(15)
    assertThat(tree.hasCompatibleLayout(atMost(30), unspecified())).isTrue
  }

  @Test
  fun `an ordered reuse does not record over specs set by a host view's measure`() {
    val tree = tree()

    assertThat(reuseDuringHostViewMeasure(tree, OrderedRequests(tree)))
        .isEqualTo(HostViewMeasure(measuredHeight = 70, committedHeight = 70))
  }

  @Test
  fun `a host view's compatible measure does not pin the tree to its specs`() {
    val tree = tree()
    attachToHostView(tree)
    val item = Item("item", recorder)
    tree.request(item, exactly(20), version = 0)
    assertThat(tree.committedLayout().height).isEqualTo(20)

    // The host view measures at the tree's own size: compatible, nothing is laid out.
    val measured = IntArray(2)
    tree.measure(exactly(20), exactly(20), measured, false)
    runLayoutQueue()
    assertThat(measured[1]).isEqualTo(20)

    // A state update that grows the item is laid out at the tree's specs, not the view's.
    recorder.updateState("item", 1)
    assertThat(tree.committedLayout().height).isEqualTo(30)
  }

  @Test
  fun `an ordered reuse while a state update is laid out does not drop the update`() {
    val tree = tree()

    // The request lays the newest resolve out at its specs: the update shows, clamped to 25.
    assertThat(reuseDuringStateUpdateLayout(tree, OrderedRequests(tree))).isEqualTo(25)
  }

  @Test
  fun `unordered requests do not record specs, even on a tree that took ordered requests`() {
    val outcomes = HashMap<String, List<Any>>()
    for (afterOrdered in listOf(true, false)) {
      fun newTree(): ComponentTree =
          tree().also { tree ->
            if (afterOrdered) {
              OrderedRequests(tree).sync(Item("first", recorder), exactly(40), unspecified())
            }
          }
      val olderSpecs = newTree()
      val hostView = newTree()
      val stateUpdate = newTree()
      outcomes[if (afterOrdered) "after ordered" else "fresh"] =
          listOf(
              olderSpecsLayoutAfterReuse(olderSpecs, UnorderedRequests(olderSpecs)),
              reuseDuringHostViewMeasure(hostView, UnorderedRequests(hostView)),
              reuseDuringStateUpdateLayout(stateUpdate, UnorderedRequests(stateUpdate)),
          )
    }

    // Without recording, the layout for older specs commits and the update commits unclamped.
    assertThat(outcomes["fresh"])
        .containsExactly(10, HostViewMeasure(measuredHeight = 70, committedHeight = 70), 30)
    assertThat(outcomes["after ordered"]).isEqualTo(outcomes["fresh"])
  }

  /**
   * A request at `AT_MOST 30` (width 15), an async request at `EXACTLY 10`, then a request at
   * `AT_MOST 30` that reuses the committed layout before the async layout runs. Returns the
   * committed width.
   */
  private fun olderSpecsLayoutAfterReuse(tree: ComponentTree, requests: Requests): Int {
    val item = Item("item", recorder)
    assertThat(requests.sync(item, atMost(30), unspecified()).width).isEqualTo(15)
    requests.async(item, exactly(10), unspecified())
    requests.sync(item, atMost(30), unspecified())
    runLayoutQueue()
    return tree.committedLayout().width
  }

  data class HostViewMeasure(val measuredHeight: Int, val committedHeight: Int)

  /**
   * The host view measures the tree at `EXACTLY 70` high, which the committed layout (height 20)
   * does not fit; while that layout runs, a request at the tree's own specs reuses the committed
   * layout.
   */
  private fun reuseDuringHostViewMeasure(
      tree: ComponentTree,
      requests: Requests,
  ): HostViewMeasure {
    attachToHostView(tree)
    val item = Item("item", recorder)
    assertThat(requests.sync(item, exactly(20), unspecified()).height).isEqualTo(20)
    var requested = false
    recorder.onLayout = { layout ->
      if (!requested && layout.constraints.minHeight == 70) {
        requested = true
        onOtherThread { requests.sync(item, exactly(20), unspecified()) }
      }
    }
    val measured = IntArray(2)
    tree.measure(exactly(20), exactly(70), measured, false)
    recorder.onLayout = null
    runLayoutQueue()
    assertThat(requested).isTrue
    return HostViewMeasure(measured[1], tree.committedLayout().height)
  }

  /**
   * A state update grows the item from 20 to 30; while its layout runs (its resolve is committed),
   * an async request at `AT_MOST 25` high, which the old layout fits, arrives. Returns the
   * committed height.
   */
  private fun reuseDuringStateUpdateLayout(tree: ComponentTree, requests: Requests): Int {
    val item = Item("item", recorder)
    assertThat(requests.sync(item, exactly(20), unspecified()).height).isEqualTo(20)
    var requested = false
    recorder.onLayout = { layout ->
      if (!requested && layout.stateValue == 1) {
        requested = true
        onOtherThread { requests.async(item, exactly(20), atMost(25)) }
      }
    }
    recorder.updateState("item", 1)
    recorder.onLayout = null
    assertThat(requested).isTrue
    runLayoutQueue()
    return tree.committedLayout().height
  }

  /** How a scenario sends its root requests to a tree. */
  private interface Requests {
    fun sync(root: Component, widthSpec: Int, heightSpec: Int): Size

    fun async(root: Component, widthSpec: Int, heightSpec: Int)
  }

  private class OrderedRequests(private val tree: ComponentTree) : Requests {
    override fun sync(root: Component, widthSpec: Int, heightSpec: Int): Size =
        Size().also { output ->
          tree.setOrderedRootAndSizeSpec(
              root,
              widthSpec,
              heightSpec,
              output,
              null,
              requestVersion = 0,
              isAsync = false,
              forceLayout = false,
          )
        }

    override fun async(root: Component, widthSpec: Int, heightSpec: Int) {
      tree.setOrderedRootAndSizeSpec(
          root,
          widthSpec,
          heightSpec,
          null,
          null,
          requestVersion = 0,
          isAsync = true,
          forceLayout = false,
      )
    }
  }

  private class UnorderedRequests(private val tree: ComponentTree) : Requests {
    override fun sync(root: Component, widthSpec: Int, heightSpec: Int): Size =
        Size().also { output -> tree.setRootAndSizeSpecSync(root, widthSpec, heightSpec, output) }

    override fun async(root: Component, widthSpec: Int, heightSpec: Int) {
      tree.setRootAndSizeSpecAsync(root, widthSpec, heightSpec)
    }
  }

  private fun tree(
      configuration: ComponentsConfiguration = ComponentsConfiguration.defaultInstance,
  ): ComponentTree =
      ComponentTree.create(context)
          .componentsConfiguration(configuration)
          .layoutThreadHandler(layoutQueue)
          .build()
          .also { tree -> trees.add(tree) }

  private fun attachToHostView(tree: ComponentTree) {
    tree.setLithoView(LithoView(context))
    tree.attach()
  }

  private fun ComponentTree.request(
      root: Component,
      widthSpec: Int,
      version: Int,
      output: Size = Size(),
      isAsync: Boolean = false,
  ) {
    setOrderedRootAndSizeSpec(
        root,
        widthSpec,
        unspecified(),
        if (isAsync) null else output,
        null,
        version,
        isAsync,
        forceLayout = false,
    )
  }

  private fun ComponentTree.committedLayout(): LayoutState =
      checkNotNull(synchronized(this) { getCommittedLayoutState() })

  private fun ComponentTree.committedLabel(): String? =
      (committedLayout().rootComponent as? Item)?.label

  private fun runLayoutQueue() {
    while (true) {
      val task = layoutQueue.poll() ?: break
      task.run()
    }
  }

  private fun onOtherThread(block: () -> Unit) {
    val failure = AtomicReference<Throwable>()
    val thread = Thread {
      try {
        block()
      } catch (thrown: Throwable) {
        failure.set(thrown)
      }
    }
    thread.start()
    thread.join(TimeUnit.SECONDS.toMillis(10))
    check(!thread.isAlive) { "the request on the other thread did not return" }
    failure.get()?.let { thrown -> throw AssertionError("the other thread failed", thrown) }
  }

  private fun exactly(size: Int): Int = SizeSpec.makeSizeSpec(size, SizeSpec.EXACTLY)

  private fun atMost(size: Int): Int = SizeSpec.makeSizeSpec(size, SizeSpec.AT_MOST)

  private fun unspecified(): Int = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)

  /** A layout thread whose tasks run only when the test runs them. */
  private class TaskQueue : RunnableHandler {
    private val tasks = ConcurrentLinkedDeque<Runnable>()

    override fun isTracing(): Boolean = false

    override fun post(runnable: Runnable, tag: String) {
      tasks.addLast(runnable)
    }

    override fun postAtFront(runnable: Runnable, tag: String) {
      tasks.addFirst(runnable)
    }

    override fun remove(runnable: Runnable) {
      tasks.removeFirstOccurrence(runnable)
    }

    fun poll(): Runnable? = tasks.pollFirst()
  }

  class RecordedLayout(val label: String, val constraints: SizeConstraints, val stateValue: Int)

  /** Records the items' layouts and their states, and calls [onLayout] inside each layout. */
  class LayoutRecorder {
    private val layouts: MutableList<RecordedLayout> = Collections.synchronizedList(ArrayList())
    val states: MutableMap<String, State<Int>> = ConcurrentHashMap()
    val failingEventHandlers: MutableMap<String, EventHandler<Unit>> = ConcurrentHashMap()
    @Volatile var onLayout: ((RecordedLayout) -> Unit)? = null

    fun record(layout: RecordedLayout) {
      layouts.add(layout)
      onLayout?.invoke(layout)
    }

    fun labels(): List<String> = synchronized(layouts) { layouts.map { layout -> layout.label } }

    /** A sync state update of the item [label], run as main runs it. */
    fun updateState(label: String, value: Int) {
      checkNotNull(states[label]).updateSync(value)
      ShadowLooper.idleMainLooper()
    }
  }

  /**
   * Width 15 unless the constraints say otherwise; height 20, plus 10 for each step of its state.
   * Its event handler [LayoutRecorder.failingEventHandlers] throws.
   */
  class Item(val label: String, private val recorder: LayoutRecorder) : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive {
      val extra = useState { 0 }
      recorder.states[label] = extra
      recorder.failingEventHandlers[label] =
          eventHandler<Unit>({ error("event failed") }, null, context)
      return LithoPrimitive(
          layoutBehavior = ItemLayout(label, extra.value, recorder),
          mountBehavior =
              MountBehavior(ViewAllocator { androidContext -> View(androidContext) }) {},
          style = null,
      )
    }
  }

  private class ItemLayout(
      private val label: String,
      private val stateValue: Int,
      private val recorder: LayoutRecorder,
  ) : LayoutBehavior {
    override fun LayoutScope.layout(constraints: SizeConstraints): PrimitiveLayoutResult {
      recorder.record(RecordedLayout(label, constraints, stateValue))
      val width =
          if (constraints.hasBoundedWidth) 15.coerceIn(constraints.minWidth, constraints.maxWidth)
          else 15.coerceAtLeast(constraints.minWidth)
      val naturalHeight = 20 + 10 * stateValue
      val height =
          if (constraints.hasBoundedHeight)
              naturalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
          else naturalHeight.coerceAtLeast(constraints.minHeight)
      return PrimitiveLayoutResult(width = width, height = height)
    }
  }
}
