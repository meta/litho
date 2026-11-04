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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.animation.height
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.ThreadTestingUtils
import com.facebook.litho.testing.TimeOutSemaphore
import com.facebook.litho.testing.Whitebox
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.RenderAndLayoutCountingTester
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.px
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper

/** Tests for LithoRenderer. */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class LithoRendererTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private lateinit var context: Context
  private lateinit var renderer: LithoRenderer
  private val testComponent: Component = TestComponent()
  private val testConstraints =
      SizeConstraints(minWidth = 100, maxWidth = 100, minHeight = 100, maxHeight = 100)

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    renderer =
        LithoRenderer(
            context = context,
            componentsConfig = ComponentsConfiguration.defaultInstance,
        )
  }

  // ========== Rebind Tests ==========
  // These tests verify the fix for always calling rebind() after layout commit,
  // regardless of render mode. Previously, rebind was only called for StateUpdate modes.
  // This change ensures UI consistency, especially for scenarios like RecyclerView adapters
  // where binding must happen after state commitment.

  @Test
  fun `rebind should update view layout state after SetRootSync render mode`() {
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    // Before the fix, SetRootSync would NOT call rebind (only StateUpdate modes did)
    // After the fix, rebind should be called for SetRootSync
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // Verify layout state is bound to the view (proves rebind was called)
    assertThat(view.layoutState).isNotNull
    assertThat(view.layoutState?.sizeConstraints).isEqualTo(testConstraints)
  }

  @Test
  fun `rebind should update view layout state after SetConstraintsSync render mode`() {
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    // Establish initial state
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val firstLayoutState = view.layoutState
    assertThat(firstLayoutState).isNotNull

    // Before the fix, SetConstraintsSync would NOT call rebind
    // After the fix, it should call rebind and update the view
    val newConstraints =
        SizeConstraints(minWidth = 150, maxWidth = 150, minHeight = 150, maxHeight = 150)
    renderer.layoutSync(newConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // Verify view was updated with new layout state
    assertThat(view.layoutState).isNotNull
    assertThat(view.layoutState).isNotSameAs(firstLayoutState)
    assertThat(view.layoutState?.sizeConstraints).isEqualTo(newConstraints)
  }

  @Test
  fun `rebind should be posted to UI thread for deferred execution`() {
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    // renderSync synchronously creates layout state but posts rebind to UI thread
    renderer.renderSync(testComponent, testConstraints)

    // Without idling the looper, rebind may not have executed yet
    // This tests that rebind is properly posted to UI thread handler
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // After idling, rebind should have executed and bound the layout state
    assertThat(view.layoutState).isNotNull
    assertThat(view.layoutState?.sizeConstraints).isEqualTo(testConstraints)
  }

  @Test
  fun `rebind should maintain UI consistency across multiple render operations`() {
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    // Simulate multiple render operations (e.g., in RecyclerView adapter scenarios)
    // Each should trigger rebind to keep UI in sync with latest layout state

    // First render
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val state1 = view.layoutState
    assertThat(state1?.sizeConstraints).isEqualTo(testConstraints)

    // Second render with different constraints
    val constraints2 =
        SizeConstraints(minWidth = 150, maxWidth = 150, minHeight = 150, maxHeight = 150)
    renderer.renderSync(testComponent, constraints2)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val state2 = view.layoutState
    assertThat(state2?.sizeConstraints).isEqualTo(constraints2)

    // Third render
    val constraints3 =
        SizeConstraints(minWidth = 200, maxWidth = 200, minHeight = 200, maxHeight = 200)
    renderer.renderSync(testComponent, constraints3)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val state3 = view.layoutState

    // Each render should have updated the view's layout state via rebind
    assertThat(state3?.sizeConstraints).isEqualTo(constraints3)
    assertThat(state1).isNotSameAs(state2)
    assertThat(state2).isNotSameAs(state3)
  }

  @Test
  fun `rebind should work correctly when view is not bound`() {
    // Test that rebind handles the case where no view is bound
    // (rebind checks if rootHost is LithoRenderTreeView before calling setLayoutState)

    // No view bound - should not crash
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // Now bind a view and render again with different constraints to force new layout state
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    val newConstraints =
        SizeConstraints(minWidth = 150, maxWidth = 150, minHeight = 150, maxHeight = 150)
    renderer.renderSync(testComponent, newConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // View should now have the layout state
    assertThat(view.layoutState).isNotNull
    assertThat(view.layoutState?.sizeConstraints).isEqualTo(newConstraints)
  }

  // ========== Rebind Guard Condition Tests ==========
  // These tests verify the guard conditions inside rebind() method:
  // 1. Same instance check (layoutState === uiThreadLayoutState)
  // 2. Version check (layoutState.version <= previousUiThreadLayoutState.version)
  // 3. Both states null/empty check

  @Test
  fun `rebind should skip when same layout state instance is already bound`() {
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    // First render
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val layoutState = view.layoutState
    assertThat(layoutState).isNotNull

    // Render again with same component and constraints
    // This should result in the same layout state instance being reused
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // Layout state should be the same instance (rebind should have detected and skipped)
    assertThat(view.layoutState).isSameAs(layoutState)
  }

  @Test
  fun `rebind should ensure layout state versions progress monotonically`() {
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    // First render
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val state1 = view.layoutState
    assertThat(state1).isNotNull

    // Second render with different constraints
    val constraints2 =
        SizeConstraints(minWidth = 150, maxWidth = 150, minHeight = 150, maxHeight = 150)
    renderer.renderSync(testComponent, constraints2)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val state2 = view.layoutState
    assertThat(state2).isNotNull

    // Third render with different constraints again
    val constraints3 =
        SizeConstraints(minWidth = 200, maxWidth = 200, minHeight = 200, maxHeight = 200)
    renderer.renderSync(testComponent, constraints3)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val state3 = view.layoutState
    assertThat(state3).isNotNull

    // Verify versions progress monotonically (rebind checks version <= previous and skips)
    // Each layout state should have a higher version than the previous
    assertThat(state2?.version).isGreaterThan(state1?.version ?: -1)
    assertThat(state3?.version).isGreaterThan(state2?.version ?: -1)
  }

  @Test
  fun `rebind should reject older version and keep current layout state`() {
    // This test directly verifies the version rejection guard in rebind() using reflection
    // Scenario:
    // 1. layoutSync without view -> creates V1, but doesn't commit to UI (no view)
    // 2. Bind view
    // 3. layoutSync with view -> creates V2, commits to UI
    // 4. Try to rebind with old V1 using reflection -> should be rejected

    // Step 1: Create layout state V1 without a view
    val oldLayoutState = renderer.layoutSync(testConstraints)
    val oldVersion = oldLayoutState.version

    // Step 2: Bind a view
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    // Step 3: Create newer layout state V2 with view bound
    val newConstraints =
        SizeConstraints(minWidth = 200, maxWidth = 200, minHeight = 200, maxHeight = 200)
    renderer.renderSync(testComponent, newConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()
    val newLayoutState = view.layoutState
    val newVersion = newLayoutState?.version
    assertThat(newLayoutState).isNotNull
    assertThat(newVersion).isGreaterThan(oldVersion)

    // Step 4: Try to rebind with old layout state using reflection
    // The version check in rebind should reject it
    val rebindMethod =
        LithoRenderer::class.java.getDeclaredMethod("rebind", LayoutState::class.java)
    rebindMethod.isAccessible = true
    rebindMethod.invoke(renderer, oldLayoutState)

    // Verify: View should still have the newer layout state (V2), not the old one (V1)
    // The version check prevented the older state from being applied
    assertThat(view.layoutState).isSameAs(newLayoutState)
    assertThat(view.layoutState?.version).isEqualTo(newVersion)
    assertThat(view.layoutState?.version).isNotEqualTo(oldVersion)
  }

  @Test
  fun `rebind should handle rendering without view then binding view later`() {
    // This tests the guard condition path where uiThreadLayoutState might be null initially
    // Then we bind a view and render, which should work correctly

    // First render without view - should not crash
    // Internally, uiThreadLayoutState may not be updated since rootHost is not LithoRenderTreeView
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // Bind view and render with different constraints
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)

    val newConstraints =
        SizeConstraints(minWidth = 200, maxWidth = 200, minHeight = 200, maxHeight = 200)
    renderer.renderSync(testComponent, newConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    // View should be updated with the layout state
    assertThat(view.layoutState).isNotNull
    assertThat(view.layoutState?.sizeConstraints).isEqualTo(newConstraints)

    // Render once more to verify subsequent renders work
    val constraints3 =
        SizeConstraints(minWidth = 250, maxWidth = 250, minHeight = 250, maxHeight = 250)
    renderer.renderSync(testComponent, constraints3)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    assertThat(view.layoutState?.sizeConstraints).isEqualTo(constraints3)
  }

  @Test
  fun `clean resets the visibility hint before dropping the layout state`() {
    val renderer = LithoRenderer(
        context = context,
        componentsConfig =
            ComponentsConfiguration.defaultInstance.copy(
                resetVisibilityHintOnTreeDetach = true,
            ),
    )
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)
    renderer.renderSync(testComponent, testConstraints)
    (Shadow.extract(android.os.Looper.getMainLooper()) as ShadowLooper).idle()

    view.setVisibilityHint(true)
    assertThat(Whitebox.getInternalState<Boolean>(view, "visibilityHintIsVisible")).isTrue

    view.clean()

    assertThat(Whitebox.getInternalState<Boolean>(view, "hasVisibilityHint")).isFalse
    assertThat(Whitebox.getInternalState<Boolean>(view, "pauseMountingWhileVisibilityHintFalse"))
        .isFalse
    assertThat(Whitebox.getInternalState<Boolean>(view, "visibilityHintIsVisible")).isFalse
  }

  /**
   * `LithoRenderer`'s path through the dropped-layout schedule in `SplitFuturesTest`. Rn is the
   * n-th resolve and Ln the layout of Rn's result; `s` is the reader's height, read only while
   * laying out.
   * 1. R1 and L1 commit: the reader is 100px tall.
   * 2. R2 applies `s = 200px` and commits on the layout thread. L2 is held while it measures the
   *    reader's child.
   * 3. On the main thread, R3 applies an update to a state nothing reads, and commits, and L3 lays
   *    out against L1.
   * 4. L2 is released and dropped, since R3 is now the committed resolve.
   *
   * L3 has to lay the reader out again, or it is served from L1 and stays 100px tall.
   */
  @OptIn(ExperimentalLithoApi::class)
  @Test
  fun `a layout lays out again what a resolve whose layout was dropped updated`() {
    val heightState = AtomicReference<State<Dimen>?>()
    val unreadState = AtomicReference<State<Int>?>()
    val layout2Held = TimeOutSemaphore(0)
    val releaseLayout2 = TimeOutSemaphore(0)
    val holdLayout2 = AtomicBoolean(false)
    val holdOnLayoutThread =
        object : RenderAndLayoutCountingTester.Listener {
          override fun onPrepare() = Unit

          override fun onMeasure() {
            if (
                Looper.myLooper() != Looper.getMainLooper() &&
                    holdLayout2.compareAndSet(true, false)
            ) {
              layout2Held.release()
              releaseLayout2.acquire()
            }
          }
        }
    val constraints =
        SizeConstraints(minWidth = 100, maxWidth = 100, minHeight = 0, maxHeight = 500)
    renderer.bindTo(LithoRenderTreeView(context))

    // Step 1.
    renderer.renderSync(
        RendererHeightReader(heightState, unreadState, holdOnLayoutThread),
        constraints,
    )
    assertThat(renderer.currentLayoutState?.height).isEqualTo(100)

    // Step 2.
    holdLayout2.set(true)
    checkNotNull(heightState.get()).update(200.px)
    val bgLatch = ThreadTestingUtils.runOnBackgroundThread { lithoTestRule.finishAsyncUpdates() }
    layout2Held.acquire()

    // Step 3. Sync, so that R3 resolves on this thread while L2 is held.
    @SuppressLint("KComponentSyncUpdateDetector")
    checkNotNull(unreadState.get()).updateSync { it + 1 }

    // Step 4.
    releaseLayout2.release()
    bgLatch.acquire()
    lithoTestRule.idle()

    assertThat(renderer.currentLayoutState?.height)
        .describedAs("L3 lays out again the component that read `s` while laying out")
        .isEqualTo(200)
    assertThat(checkNotNull(renderer.treeState).statesUpdatedAfter(TreeState.UNSET_VERSION))
        .describedAs("L3's commit drops every update its resolve version covers")
        .isEmpty()
  }

  // ========== Release Tests ==========

  @Test
  fun `release drops the root, the layout and the tree state`() {
    val payload = renderSyncAndReturnPayload(renderer, RenderCounter())

    renderer.release()

    assertThat(renderer.treeState).isNull()
    assertThat(renderer.currentLayoutState).isNull()
    awaitCollected(payload)
  }

  @Test
  fun `release releases the tree lifecycle and notifies its listeners`() {
    var notified = 0
    val tree = checkNotNull(Whitebox.getInternalState<LithoTree>(renderer, "lithoTree"))
    tree.treeLifecycle.addOnReleaseListener { notified++ }

    renderer.release()
    renderer.release()

    assertThat(tree.treeLifecycle.isReleased).isTrue
    assertThat(notified).isEqualTo(1)
  }

  @Test
  fun `a released renderer neither binds nor keeps what it renders`() {
    val renders = RenderCounter()
    renderer.release()
    val view = LithoRenderTreeView(context)

    renderer.bindTo(view)
    val payload = renderSyncAndReturnPayload(renderer, renders)
    ShadowLooper.idleMainLooper()

    assertThat(renders.count).describedAs("a synchronous render still runs").isEqualTo(1)
    assertThat(renderer.mountedView).describedAs("the view isn't kept as its host").isNull()
    assertThat(view.layoutState).describedAs("nothing is mounted").isNull()
    assertThat(renderer.currentLayoutState).isNull()
    awaitCollected(payload)
  }

  @Test
  fun `an asynchronous render after release does nothing`() {
    val handler = QueueRunnableHandler()
    val renderer = LithoRenderer(context = context, runnableHandlerProvider = handler.provider)
    renderer.release()

    renderer.render(TestComponent(), testConstraints)

    assertThat(handler.queue).isEmpty()
  }

  /**
   * A layout that committed just before [LithoRenderer.release] posts its rebind afterwards. The
   * released renderer must not keep that layout, or a state handle that outlives it would keep the
   * layout's components alive.
   */
  @Test
  fun `a layout committed just before release isn't kept by the released renderer`() {
    val handler = QueueRunnableHandler()
    val renderer = LithoRenderer(context = context, runnableHandlerProvider = handler.provider)
    val payload = renderWithPayload(renderer)
    runOnWorker(handler.queue.take()) // Resolves, then posts the layout.
    val layout = handler.queue.take()

    // The worker commits the layout, then waits for this lock to post the rebind.
    val runnableLock = checkNotNull(Whitebox.getInternalState<Any>(renderer, "runnableLock"))
    val worker = Thread(layout)
    synchronized(runnableLock) {
      worker.start()
      awaitBlocked(worker)
      assertThat(renderer.currentLayoutState).describedAs("the layout committed").isNotNull
      renderer.release()
    }
    worker.join(WORKER_TIMEOUT_MS)
    ShadowLooper.idleMainLooper()

    assertThat(Whitebox.getInternalState<Runnable?>(renderer, "currentRebindRunnable")).isNull()
    awaitCollected(payload)
  }

  @Test
  fun `a render racing release doesn't restore its root`() {
    assertRootNotRestoredByRacingRelease { renderer, root ->
      renderer.render(root, testConstraints)
    }
  }

  @Test
  fun `a resolve racing release doesn't restore its root`() {
    assertRootNotRestoredByRacingRelease { renderer, root -> renderer.resolve(root) }
  }

  @Test
  fun `a throwing release listener doesn't stop the renderer from dropping what it rendered`() {
    val payload = renderSyncAndReturnPayload(renderer, RenderCounter())
    treeLifecycle(renderer).addOnReleaseListener { error("listener failed") }

    assertThatThrownBy { renderer.release() }.hasMessage("listener failed")

    assertThat(renderer.treeState).isNull()
    assertThat(renderer.currentLayoutState).isNull()
    awaitCollected(payload)
  }

  @Test
  fun `a release listener that removes itself doesn't skip the next one`() {
    val lifecycle = treeLifecycle(renderer)
    var secondNotified = 0
    lifecycle.addOnReleaseListener(
        object : LithoTreeLifecycleProvider.OnReleaseListener {
          override fun onReleased() {
            lifecycle.removeOnReleaseListener(this)
          }
        },
    )
    lifecycle.addOnReleaseListener { secondNotified++ }

    renderer.release()

    assertThat(secondNotified).isEqualTo(1)
  }

  private fun treeLifecycle(renderer: LithoRenderer): LithoTreeLifecycleProvider =
      checkNotNull(Whitebox.getInternalState<LithoTree>(renderer, "lithoTree")).treeLifecycle

  /**
   * The worker enters [start] before [LithoRenderer.release] and waits for the renderer's monitor
   * to store its root, which it gets only after the release has dropped the inputs.
   */
  private fun assertRootNotRestoredByRacingRelease(start: (LithoRenderer, Component) -> Unit) {
    val handler = QueueRunnableHandler()
    val renderer = LithoRenderer(context = context, runnableHandlerProvider = handler.provider)

    val payload = startWhileReleasing(renderer, start)

    assertThat(handler.queue).describedAs("nothing is scheduled").isEmpty()
    awaitCollected(payload)
  }

  private fun startWhileReleasing(
      renderer: LithoRenderer,
      start: (LithoRenderer, Component) -> Unit,
  ): java.lang.ref.WeakReference<Any> {
    val payload = Any()
    val root = PayloadComponent(payload, RenderCounter())
    val worker = Thread { start(renderer, root) }
    synchronized(renderer) {
      worker.start()
      awaitBlocked(worker)
      renderer.release()
    }
    worker.join(WORKER_TIMEOUT_MS)
    return java.lang.ref.WeakReference(payload)
  }

  private fun renderSyncAndReturnPayload(
      renderer: LithoRenderer,
      renders: RenderCounter,
  ): java.lang.ref.WeakReference<Any> {
    val payload = Any()
    renderer.renderSync(PayloadComponent(payload, renders), testConstraints)
    return java.lang.ref.WeakReference(payload)
  }

  private fun renderWithPayload(renderer: LithoRenderer): java.lang.ref.WeakReference<Any> {
    val payload = Any()
    renderer.render(PayloadComponent(payload, RenderCounter()), testConstraints)
    return java.lang.ref.WeakReference(payload)
  }

  private fun runOnWorker(runnable: Runnable) {
    val worker = Thread(runnable)
    worker.start()
    worker.join(WORKER_TIMEOUT_MS)
  }

  private fun awaitBlocked(thread: Thread) {
    repeat(WORKER_TIMEOUT_MS.toInt()) {
      if (thread.state == Thread.State.BLOCKED) {
        return
      }
      Thread.sleep(1)
    }
    error("worker never blocked on the lock")
  }

  private fun awaitCollected(ref: java.lang.ref.WeakReference<*>) {
    repeat(20) {
      if (ref.get() == null) {
        return
      }
      Runtime.getRuntime().gc()
      Thread.sleep(10)
    }
    assertThat(ref.get()).describedAs("rendered props kept by the released renderer").isNull()
  }

  /** Runs nothing by itself: tests take the posted runnables and run them where they need to. */
  private class QueueRunnableHandler : com.facebook.rendercore.RunnableHandler {
    val queue = java.util.concurrent.LinkedBlockingDeque<Runnable>()

    val provider =
        object : com.facebook.litho.config.RunnableHandlerProvider {
          override fun getRunnableHandler() = this@QueueRunnableHandler
        }

    override fun isTracing(): Boolean = false

    override fun post(runnable: Runnable, tag: String) {
      queue.add(runnable)
    }

    override fun postAtFront(runnable: Runnable, tag: String) {
      queue.addFirst(runnable)
    }

    override fun remove(runnable: Runnable) {
      queue.remove(runnable)
    }
  }

  private class RenderCounter {
    @Volatile var count = 0
  }

  private class PayloadComponent(
      @Suppress("unused") private val payload: Any,
      private val renders: RenderCounter,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      renders.count++
      return Column(style = Style.height(10.px))
    }
  }

  // ========== Test Components ==========

  private class RendererHeightReader(
      private val heightState: AtomicReference<State<Dimen>?>,
      private val unreadState: AtomicReference<State<Int>?>,
      private val listener: RenderAndLayoutCountingTester.Listener,
  ) : KComponent() {
    @OptIn(ExperimentalLithoApi::class)
    override fun ComponentScope.render(): Component {
      // Not read here: `Style.height(Value)` reads the state while laying out.
      val height = useState { 100.px }
      heightState.set(height)
      unreadState.set(useState { 0 })
      return Column {
        child(
            Row(style = Style.width(100.px).height(height)) {
              // Stretched to the row's height, so it is measured again whenever that changes.
              child(
                  RenderAndLayoutCountingTester(
                      renderAndMeasureCounter = RenderAndMeasureCounter(),
                      listener = listener,
                      style = Style.width(100.px),
                  ),
              )
            },
        )
      }
    }
  }

  /** Test component for rendering tests */
  private class TestComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      return Row(style = Style.height(100.dp).width(100.dp)) {
        child(Column { child(Text(text = "Test Component")) })
      }
    }
  }

  /** Different test component to verify component change detection */
  private class DifferentTestComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      return Row(style = Style.height(100.dp).width(100.dp)) {
        child(Column { child(Text(text = "Different Test Component")) })
      }
    }
  }

  private companion object {
    const val WORKER_TIMEOUT_MS = 5_000L
  }
}
