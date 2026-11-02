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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.lifecycle.LifecycleOwnerTreeProp
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/** Unit tests for [useFlow]. */
@OptIn(ExperimentalCoroutinesApi::class)
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class UseFlowTest {

  @Rule @JvmField val lithoViewRule = LithoTestRule()

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @Test
  fun `collect with a StateFlow`() {
    val stateRef = AtomicReference<Boolean>()
    val testFlow = MutableStateFlow(false)

    class UseCollectAsStateComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val value = useFlow(testFlow)
        stateRef.set(value)
        return EmptyComponent()
      }
    }

    val lithoView = lithoViewRule.render { UseCollectAsStateComponent() }
    assertThat(stateRef.get()).isFalse

    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(stateRef.get()).isFalse

    lithoViewRule.act(lithoView) {
      testFlow.value = true
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(stateRef.get()).isTrue
  }

  @Test
  fun `collect flow block`() {
    val stateRef = AtomicInteger()

    class UseCollectAsStateComponent(val dep: Int) : KComponent() {
      override fun ComponentScope.render(): Component {
        val value = useFlow(initialValue = { 0 }, dep) { MutableStateFlow(dep) }
        stateRef.set(value)
        return EmptyComponent()
      }
    }

    val lithoView = lithoViewRule.render { UseCollectAsStateComponent(1) }

    assertThat(stateRef.get()).isEqualTo(0)

    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(stateRef.get()).isEqualTo(1)

    lithoView.setRoot(UseCollectAsStateComponent(2))
    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(stateRef.get()).isEqualTo(2)
  }

  @Test
  fun `collect flow block only rerun on dependency change`() {
    val flowEvents = mutableListOf<String>()

    class UseCollectAsStateComponent(private val dep: Int) : KComponent() {
      override fun ComponentScope.render(): Component {
        useFlow(initialValue = { 0 }, dep) {
          flowEvents.add("launch $dep")
          try {
            awaitCancellation()
          } finally {
            flowEvents.add("cancel $dep")
          }
        }
        return EmptyComponent()
      }
    }

    val lithoView = lithoViewRule.render { UseCollectAsStateComponent(1) }

    assertThat(flowEvents).isEmpty()

    testDispatcher.scheduler.runCurrent()
    lithoViewRule.idle()

    assertThat(flowEvents).containsExactly("launch 1")

    lithoView.setRoot(UseCollectAsStateComponent(1))
    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(flowEvents).containsExactly("launch 1")

    lithoView.setRoot(UseCollectAsStateComponent(2))
    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(flowEvents).containsExactly("launch 1", "cancel 1", "launch 2")
  }

  @Test
  fun `collect flow block only rerun on one of many dependencies change`() {
    val flowEvents = mutableListOf<String>()

    class UseCollectAsStateComponent(private val dep1: Int, private val dep2: Int) : KComponent() {
      override fun ComponentScope.render(): Component {
        useFlow(initialValue = { 0 }, dep1, dep2) {
          flowEvents.add("launch $dep1:$dep2")
          try {
            awaitCancellation()
          } finally {
            flowEvents.add("cancel $dep1:$dep2")
          }
        }
        return EmptyComponent()
      }
    }

    val lithoView = lithoViewRule.render { UseCollectAsStateComponent(1, 1) }

    assertThat(flowEvents).isEmpty()

    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(flowEvents).containsExactly("launch 1:1")
    flowEvents.clear()

    lithoView.setRoot(UseCollectAsStateComponent(1, 1))
    lithoViewRule.act(lithoView) { lithoViewRule.idle() }

    assertThat(flowEvents).isEmpty()

    lithoView.setRoot(UseCollectAsStateComponent(2, 1))
    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(flowEvents).containsExactly("cancel 1:1", "launch 2:1")
    flowEvents.clear()

    lithoView.setRoot(UseCollectAsStateComponent(2, 2))
    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(flowEvents).containsExactly("cancel 2:1", "launch 2:2")
    flowEvents.clear()
  }

  @Test
  fun `collect with a StateFlow observes last value even if it has been emitted before subscription`() {
    val stateRef = AtomicReference<Boolean>()
    val testFlow = MutableStateFlow(false)

    val flowUpdatedLatch = CountDownLatch(1)

    class UseCollectAsStateComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val value = useFlow(testFlow)
        if (!flowUpdatedLatch.await(300, TimeUnit.MILLISECONDS)) {
          throw TimeoutException()
        }
        stateRef.set(value)
        return EmptyComponent()
      }
    }

    val componentTree = ComponentTree.create(lithoViewRule.context).build()

    val lithoView =
        lithoViewRule
            .createTestLithoView(componentTree = componentTree)
            .attachToWindow()
            .measure()
            .layout()

    componentTree.setRootAndSizeSpecAsync(
        UseCollectAsStateComponent(),
        unspecified(),
        unspecified(),
    )

    assertThat(testFlow.subscriptionCount.value).isEqualTo(0)

    testFlow.value = true
    flowUpdatedLatch.countDown()

    lithoViewRule.idle()

    assertThat(stateRef.get()).isTrue
  }

  @Test
  fun `useFlowValue collects from a StateFlow`() {
    val stateRef = AtomicReference<Boolean>()
    val testFlow = MutableStateFlow(false)

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val value = useFlowValue(testFlow)
        stateRef.set(value.value)
        return EmptyComponent()
      }
    }

    val lithoView = lithoViewRule.render { TestComponent() }
    assertThat(stateRef.get()).isFalse

    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    assertThat(stateRef.get()).isFalse

    lithoViewRule.act(lithoView) {
      testFlow.value = true
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(stateRef.get()).isTrue
  }

  @Test
  fun `useFlowValue updates on subsequent emissions`() {
    val stateRef = AtomicInteger()
    val testFlow = MutableStateFlow(0)

    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val value = useFlowValue(testFlow)
        stateRef.set(value.value)
        return EmptyComponent()
      }
    }

    val lithoView = lithoViewRule.render { TestComponent() }
    assertThat(stateRef.get()).isEqualTo(0)

    lithoViewRule.act(lithoView) {
      testFlow.value = 1
      testDispatcher.scheduler.runCurrent()
    }
    assertThat(stateRef.get()).isEqualTo(1)

    lithoViewRule.act(lithoView) {
      testFlow.value = 2
      testDispatcher.scheduler.runCurrent()
    }
    assertThat(stateRef.get()).isEqualTo(2)
  }

  @Test
  fun `useFlowValue read can be deferred`() {
    val renders = AtomicInteger(0)
    val layoutValueRef = AtomicInteger(0)
    val testFlow = MutableStateFlow(10)

    @Suppress("PrimitiveLayoutBehaviorCaptureDetector")
    class TestComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        renders.incrementAndGet()
        // counter.value is NOT read here (resolve phase)
        val counter = useFlowValue(testFlow)
        return LithoPrimitive(
            layoutBehavior =
                LayoutBehavior { sizeConstraints ->
                  layoutValueRef.set(counter.value) // Read deferred to LAYOUT phase
                  PrimitiveLayoutResult(width = counter.value, height = 0)
                },
            mountBehavior = MountBehavior(ViewAllocator { context -> View(context) }) {},
            style = null,
        )
      }
    }

    val tree = ComponentTree.create(lithoViewRule.context).build()

    val lithoView = lithoViewRule.render(componentTree = tree) { TestComponent() }
    lithoViewRule.act(lithoView) { testDispatcher.scheduler.runCurrent() }

    // Initial render: both resolve and layout ran
    assertThat(renders.get()).isEqualTo(1)
    assertThat(layoutValueRef.get()).isEqualTo(10)

    lithoViewRule.act(lithoView) {
      testFlow.value = 42
      testDispatcher.scheduler.runCurrent()
    }

    // Resolve was skipped (render not called again), but layout picked up the updated value
    assertThat(renders.get()).isEqualTo(1)
    assertThat(layoutValueRef.get()).isEqualTo(42)
  }

  @Test
  fun `useFlowValueWithLifecycle stops collecting when lifecycle is below min state`() {
    val renders = AtomicInteger(0)
    class TestComponent(private val count: StateFlow<Int>) : KComponent() {
      override fun ComponentScope.render(): Component {
        val counter = useFlowValueWithLifecycle(count, Lifecycle.State.RESUMED)
        renders.addAndGet(1)
        return Text(text = "Counter: ${counter.value}")
      }
    }

    val count = MutableStateFlow(0)
    val component = TestComponent(count)
    val lifecycleOwner = TestLifecycleOwner().apply { updateState(Lifecycle.State.STARTED) }

    val tree =
        ComponentTree.create(
                ComponentContext(
                    lithoViewRule.context,
                    TreePropContainer().apply { put(LifecycleOwnerTreeProp, lifecycleOwner) },
                ),
            )
            .build()

    val handle = lithoViewRule.render(componentTree = tree) { component }
    testDispatcher.scheduler.runCurrent()

    assertThat(renders.get()).isEqualTo(1)

    lifecycleOwner.updateState(Lifecycle.State.RESUMED)

    lithoViewRule.act(handle) {
      count.value = 1
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(renders.get()).isEqualTo(2) // should re-render

    lifecycleOwner.updateState(Lifecycle.State.STARTED)
    testDispatcher.scheduler.runCurrent()

    lithoViewRule.act(handle) {
      count.value = 2
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(renders.get()).isEqualTo(2) // should not re-render

    lifecycleOwner.updateState(Lifecycle.State.RESUMED)
    testDispatcher.scheduler.runCurrent()

    lithoViewRule.act(handle) {
      count.value = 3
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(renders.get()).isEqualTo(3) // should re-render
  }

  @Test
  fun `when lifecycle state is below min state then flow should stop updating state`() {
    var renders = AtomicInteger(0)
    class TestComponent(private val count: StateFlow<Int>) : KComponent() {
      override fun ComponentScope.render(): Component {
        val counter = useFlowWithLifecycle(count, Lifecycle.State.RESUMED)
        renders.addAndGet(1)
        return Text(text = "Counter: $counter")
      }
    }

    val count = MutableStateFlow(0)
    val component = TestComponent(count)
    val lifecycleOwner = TestLifecycleOwner().apply { updateState(Lifecycle.State.STARTED) }

    val tree =
        ComponentTree.create(
                ComponentContext(
                    lithoViewRule.context,
                    TreePropContainer().apply { put(LifecycleOwnerTreeProp, lifecycleOwner) },
                ),
            )
            .build()

    val handle = lithoViewRule.render(componentTree = tree) { component }
    testDispatcher.scheduler.runCurrent()

    assertThat(renders.get()).isEqualTo(1)

    lifecycleOwner.updateState(Lifecycle.State.RESUMED)

    lithoViewRule.act(handle) {
      count.value = 1
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(renders.get()).isEqualTo(2) // should re-renders

    lifecycleOwner.updateState(Lifecycle.State.STARTED)
    testDispatcher.scheduler.runCurrent()

    lithoViewRule.act(handle) {
      count.value = 2
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(renders.get()).isEqualTo(2) // should not re-render

    lifecycleOwner.updateState(Lifecycle.State.RESUMED)
    testDispatcher.scheduler.runCurrent()

    lithoViewRule.act(handle) {
      count.value = 3
      testDispatcher.scheduler.runCurrent()
    }

    assertThat(renders.get()).isEqualTo(3) // should re-render
  }
}

private class TestLifecycleOwner : LifecycleOwner {

  private val testLifecycle = TestLifecycle(this)

  override val lifecycle: Lifecycle = testLifecycle

  fun updateState(state: Lifecycle.State) {
    testLifecycle.updateState(state)
  }
}

private class TestLifecycle(private val lifecycleOwner: LifecycleOwner) : Lifecycle() {

  private var _state = State.INITIALIZED

  override val currentState: State
    get() = _state

  private val observers = mutableListOf<LifecycleObserver>()

  override fun addObserver(observer: LifecycleObserver) {
    observers.add(observer)
  }

  override fun removeObserver(observer: LifecycleObserver) {
    observers.remove(observer)
  }

  fun updateState(next: State) {
    val previous = _state
    _state = next
    val observersCopy = buildList { addAll(observers) }
    observersCopy.forEach { observer ->
      if (observer is DefaultLifecycleObserver) {
        when (next) {
          State.INITIALIZED -> observer.onCreate(lifecycleOwner)
          State.STARTED -> observer.onStart(lifecycleOwner)
          State.RESUMED -> observer.onResume(lifecycleOwner)
          State.CREATED -> observer.onCreate(lifecycleOwner)
          State.DESTROYED -> observer.onDestroy(lifecycleOwner)
        }
      } else if (observer is LifecycleEventObserver) {
        val event = toEvent(previous, next)
        event?.let { observer.onStateChanged(lifecycleOwner, it) }
      }
    }
  }

  private fun toEvent(previous: Lifecycle.State, next: Lifecycle.State): Event? {
    return if (previous > next) {
      Event.downTo(next)
    } else {
      Event.upTo(next)
    }
  }
}
