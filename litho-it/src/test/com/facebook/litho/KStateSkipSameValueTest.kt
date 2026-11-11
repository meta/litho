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

import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.assertj.LithoViewAssert
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/** Unit tests for skipping state updates if value won't change. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class KStateSkipSameValueTest {
  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `skip state update if new value is the same as old value during layout`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        // unconditional state update
        state.updateSync(true)

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip state update if nullable new value is the same as old value during layout`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger()
    val stateSampleString: String? = "sample string"

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { stateSampleString }

        // unconditional state update
        state.updateSync(null)

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip state update if new value object is the same as old value during layout`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { Person("Joe") }

        // unconditional state update
        state.updateSync(Person("Joe"))

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip async state update if new value is the same as old value`() {
    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        // unconditional state update
        state.update(true)

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    lithoTestRule.idle()

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip async state update if nullable new value is the same as old value`() {
    val renderCount = AtomicInteger()

    val stateSampleString: String? = "sample string"

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { stateSampleString }

        // unconditional state update
        state.update(null)

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    lithoTestRule.idle()

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip async state update if new value object reference is the same as old value`() {
    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { Person("Joe") }

        // unconditional state update
        state.update(Person("Joe"))

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    lithoTestRule.idle()

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip sync state update triggered from outside layout if value is same`() {
    ShadowLooper.pauseMainLooper()
    val renderCount = AtomicInteger()
    val listeners = mutableListOf<Listener>()

    class RootComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        val listener1 =
            useCached(state) {
              object : Listener {
                override fun onTriggerListener() {
                  state.updateSync(true)
                }
              }
            }

        useEffect(listener1) {
          listeners.add(listener1)
          onCleanup { listeners.remove(listener1) }
        }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent() }

    for (i in 0..10) {
      for (listener in listeners) {
        listener.onTriggerListener()
      }
    }

    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `do not skip state update if new updates were enqueued during layout`() {
    ShadowLooper.pauseMainLooper()
    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        // unconditional state update
        if (renderCount.get() == 1) {
          state.updateSync(true)
          state.updateSync(false)
        }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    val lithoView = lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    LithoViewAssert.assertThat(lithoView.lithoView).hasVisibleText("State is false")
  }

  @Test
  fun `do not skip state update if new updates were enqueued outside layout`() {
    ShadowLooper.pauseMainLooper()
    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        return Row(
            style =
                Style.viewTag("clickTag").onClick {
                  state.updateSync(true)
                  state.updateSync(false)
                },
        ) {
          child(Text(text = "State is ${state.value}"))
        }
      }
    }

    val lithoView = lithoTestRule.render { RootComponent(renderCount = renderCount) }
    lithoTestRule.act(lithoView) { clickOnTag("clickTag") }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    LithoViewAssert.assertThat(lithoView.lithoView).hasVisibleText("State is false")
  }

  // Same tests as above but with lambda state updates.

  @Test
  fun `skip lambda state update if new value is the same as old value during layout`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        // unconditional state update
        state.updateSync { true }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip lambda state update if new nullable value is the same as old value during layout`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger()
    val stateSampleString: String? = "sample string"

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { stateSampleString }

        // unconditional state update
        state.updateSync { null }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip lambda state update if new object is the same as old value during layout`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { Person("Joe") }

        // unconditional state update
        state.updateSync { Person("Joe") }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip lambda async state update if new value is the same as old value`() {
    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        // unconditional state update
        state.update { true }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    lithoTestRule.idle()

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip lambda async state update if new nullable value is the same as old value`() {
    val renderCount = AtomicInteger()
    val stateSampleString: String? = "sample string"

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { stateSampleString }

        // unconditional state update
        state.update { null }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    lithoTestRule.idle()

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip lambda async state update if new object value is the same as old value`() {
    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { Person("Joe") }

        // unconditional state update
        state.update { Person("Joe") }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent(renderCount = renderCount) }
    lithoTestRule.idle()

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `skip lambda sync state update triggered from outside layout if value is same`() {
    ShadowLooper.pauseMainLooper()
    val renderCount = AtomicInteger()
    val listeners = mutableListOf<Listener>()

    class RootComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        val listener1 =
            useCached(state) {
              object : Listener {
                override fun onTriggerListener() {
                  state.updateSync { true }
                }
              }
            }

        useEffect(listener1) {
          listeners.add(listener1)
          onCleanup { listeners.remove(listener1) }
        }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    lithoTestRule.render { RootComponent() }

    for (i in 0..10) {
      for (listener in listeners) {
        listener.onTriggerListener()
      }
    }

    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    Assertions.assertThat(renderCount.get()).isEqualTo(2)
  }

  @Test
  fun `do not skip lambda state update if new updates were enqueued during layout`() {
    ShadowLooper.pauseMainLooper()
    val renderCount = AtomicInteger()

    class RootComponent(private val renderCount: AtomicInteger) : KComponent() {
      override fun ComponentScope.render(): Component {
        renderCount.incrementAndGet()
        val state = useState { false }

        // unconditional state update
        if (renderCount.get() == 1) {
          state.updateSync { currentVal -> !currentVal }
          state.updateSync { currentVal -> !currentVal }
        }

        return Row { child(Text(text = "State is ${state.value}")) }
      }
    }

    val lithoView = lithoTestRule.render { RootComponent(renderCount = renderCount) }
    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    LithoViewAssert.assertThat(lithoView.lithoView).hasVisibleText("State is false")
  }

  @Test
  fun `do not skip lambda state update if new updates were enqueued outside layout`() {
    ShadowLooper.pauseMainLooper()

    class RootComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val state = useState { false }

        return Row(
            style =
                Style.viewTag("clickTag").onClick {
                  state.updateSync { currentState -> !currentState }
                  state.updateSync { currentState -> !currentState }
                },
        ) {
          child(Text(text = "State is ${state.value}"))
        }
      }
    }

    val lithoView = lithoTestRule.render { RootComponent() }
    lithoTestRule.act(lithoView) {
      clickOnTag("clickTag")
      /**
       * We need to perform this assertion here because as soon as we exit this lambda, all pending
       * runnables enqueued on the UI or BG thread will be executed.
       */
      Assertions.assertThat(
              lithoView.componentTree.committedTreeState?.resolveState?.pendingHookUpdatesCount,
          )
          .isEqualTo(2)
    }

    for (i in 0..10) {
      ShadowLooper.runMainLooperOneTask()
    }

    LithoViewAssert.assertThat(lithoView.lithoView).hasVisibleText("State is false")
  }

  private interface Listener {
    fun onTriggerListener()
  }

  class Person(var name: String) {
    override fun equals(that: Any?): Boolean {
      if (that == null) return false
      if (that !is Person) return false
      return this.name == that.name
    }

    override fun hashCode(): Int {
      return name.hashCode()
    }
  }
}
