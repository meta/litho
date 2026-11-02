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

package com.facebook.litho.state

import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Style
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.state.BinderUpdateTracer.BinderUpdateInfo
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventBus
import com.facebook.rendercore.debug.DebugEventSubscriber
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.utils.withEqualDimensions
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val ROOT_TAG = "root"

/**
 * End-to-end cover for the state-read rebind path, which no single module can test on its own.
 *
 * `RenderUnit.rebindBinders` writes the tracer entry and `MountState.rebindBinders` dispatches the
 * `RenderUnitUpdated` that drains it. rendercore owns both halves but cannot see the tracer, which
 * lives in litho-core; the litho-side tests drive the tracer directly and never mount anything. So
 * the two halves are each pinned in isolation and their composition - the thing the profiler
 * actually depends on - is pinned nowhere.
 *
 * This runs a real state update through a mounted Primitive and asserts what comes out the other
 * end. It goes red if the `onStateReadRebind` call moves below the bind, which is the mutation that
 * silently drops every bind duration and leaves `BinderProfileDetector` unable to rate a state-read
 * rebind WARNING or ERROR.
 */
@RunWith(LithoTestRunner::class)
class StateReadRebindProfilingTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private val drained: MutableList<BinderUpdateInfo> = mutableListOf()

  private val subscriber =
      object : DebugEventSubscriber(DebugEvent.RenderUnitUpdated) {
        override fun onEvent(event: DebugEvent) {
          val renderUnitId = event.attributeOrNull<Long>(DebugEventAttribute.RenderUnitId) ?: return
          BinderUpdateTracer.drainData(renderUnitId)?.let { drained.addAll(it) }
        }
      }

  @Before
  fun setUp() {
    BinderUpdateTracer.isEnabled = true
    DebugEventBus.subscribe(subscriber)
  }

  @After
  fun tearDown() {
    DebugEventBus.unsubscribe(subscriber)
    BinderUpdateTracer.isEnabled = false
  }

  @Test
  fun `a state read rebind reaches the tracer with its reason and bind duration`() {
    val bindCount = AtomicInteger(0)
    val testView = lithoTestRule.render { StateReadingRoot(bindCount) }

    // Control: the initial mount bound once, and initial mount dispatches no RenderUnitUpdated, so
    // nothing should have been drained yet.
    assertThat(bindCount.get()).describedAs("initial bind").isEqualTo(1)
    drained.clear()

    lithoTestRule.act(testView) { clickOnTag(ROOT_TAG) }

    // Control: the state-read fast path actually re-ran the binder. Without this the assertions
    // below would pass just as well on a build where nothing rebound at all.
    assertThat(bindCount.get()).describedAs("bind after state update").isEqualTo(2)

    val stateReads = drained.filter { it.isStateReadRebind }
    assertThat(stateReads).describedAs("state read rebinds").hasSize(1)
    assertThat(stateReads.single().describeReason()).isEqualTo("state read")
    // The entry has to exist before the bind or onBindComplete drops the duration on the floor.
    assertThat(stateReads.single().bindDurationNs).describedAs("bind duration").isNotNull()
  }
}

private class StateReadingRoot(private val bindCount: AtomicInteger) : KComponent() {
  override fun ComponentScope.render(): Component {
    val counter = useState { 0 }
    return Column(style = Style.viewTag(ROOT_TAG).onClick { counter.update { it + 1 } }) {
      child(StateReadingPrimitive(bindCount) { counter.value })
    }
  }
}

/**
 * Reads the state inside `bind`, not in resolve or layout. That is what puts the binder on the
 * state-read fast path: the value it read changed, so `MountState.rebindBinders` re-runs it without
 * going through `updateMountItemIfNeeded`. `bind(Unit)` so nothing else can explain a rebind.
 */
private class StateReadingPrimitive(
    private val bindCount: AtomicInteger,
    private val readState: () -> Int,
) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive = LithoPrimitive(
      layoutBehavior =
          LayoutBehavior { sizeConstraints ->
            PrimitiveLayoutResult(size = IntSize.withEqualDimensions(sizeConstraints))
          },
      mountBehavior =
          MountBehavior(ViewAllocator { context -> View(context) }) {
            bind(Unit) {
              readState()
              bindCount.incrementAndGet()
              onUnbind {}
            }
          },
      style = null,
  )
}
