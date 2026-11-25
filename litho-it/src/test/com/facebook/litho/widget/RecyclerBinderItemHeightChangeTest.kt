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

package com.facebook.litho.widget

import android.view.View
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.EventHandler
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec.AT_MOST
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.UNSPECIFIED
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.State
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/** Tests for [RecyclerBinderConfig.remeasureOnItemHeightChange]. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class RecyclerBinderItemHeightChangeTest {

  private val context = ComponentContext(getApplicationContext())
  private val recyclerView = TestRecyclerView(context.androidContext)
  private val widthSpec = makeSizeSpec(1000, EXACTLY)
  private val heightSpec = makeSizeSpec(1000, AT_MOST)
  private val delta = AtomicReference<State<Int>>()

  @Suppress("UNCHECKED_CAST")
  private val reMeasureEventHandler = mock(EventHandler::class.java) as EventHandler<ReMeasureEvent>

  @Test
  fun `item growing from a state update requests a remeasure when enabled`() {
    val binder = mountWithOneItem(remeasureOnItemHeightChange = true)

    updateDelta(150)

    assertThat(remeasureRequests(binder)).isEqualTo(1)
    assertThat(measure(binder)).isEqualTo(250)
  }

  @Test
  fun `item shrinking from a state update requests a remeasure when enabled`() {
    val binder = mountWithOneItem(remeasureOnItemHeightChange = true)

    updateDelta(-60)

    assertThat(remeasureRequests(binder)).isEqualTo(1)
    assertThat(measure(binder)).isEqualTo(40)
  }

  @Test
  fun `state update keeping the item height requests no remeasure`() {
    val binder = mountWithOneItem(remeasureOnItemHeightChange = true)

    updateDelta(0)

    assertThat(remeasureRequests(binder)).isEqualTo(0)
  }

  @Test
  fun `item growing from a state update requests no remeasure when disabled`() {
    val binder = mountWithOneItem(remeasureOnItemHeightChange = false)

    updateDelta(150)

    assertThat(remeasureRequests(binder)).isEqualTo(0)
  }

  @Test
  fun `item growing then shrinking from state updates requests a remeasure each time`() {
    val binder = mountWithOneItem(remeasureOnItemHeightChange = true)

    updateDelta(150)
    assertThat(remeasureRequests(binder)).isEqualTo(1)
    assertThat(measure(binder)).isEqualTo(250)

    updateDelta(0)
    assertThat(remeasureRequests(binder)).isEqualTo(2)
    assertThat(measure(binder)).isEqualTo(100)
  }

  @Test
  fun `state update back to an older height after a change set relayout requests a remeasure`() {
    val binder = mountWithOneItem(remeasureOnItemHeightChange = true, baseHeight = 160)

    updateDelta(-60)
    assertThat(remeasureRequests(binder)).isEqualTo(1)
    assertThat(measure(binder)).isEqualTo(100)

    binder.updateItemAt(0, ComponentRenderInfo.create().component(item(baseHeight = 220)).build())
    binder.notifyChangeSetComplete(true, RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    runLayoutThread()
    assertThat(measure(binder)).isEqualTo(160)
    val requestsAfterChangeSet = remeasureRequests(binder)

    updateDelta(-120)

    assertThat(remeasureRequests(binder)).isEqualTo(requestsAfterChangeSet + 1)
    assertThat(measure(binder)).isEqualTo(100)
  }

  @Test
  fun `state update back to an older height after an async binder layout requests a remeasure`() {
    val binder = mountWithOneItem(remeasureOnItemHeightChange = true, baseHeight = 160)

    updateDelta(-60)
    assertThat(remeasureRequests(binder)).isEqualTo(1)
    assertThat(measure(binder)).isEqualTo(100)

    val holder = binder.getComponentTreeHolderAt(0)
    holder.renderInfo = ComponentRenderInfo.create().component(item(baseHeight = 220)).build()
    holder.computeLayoutAsync(context, widthSpec, makeSizeSpec(0, UNSPECIFIED))
    runLayoutThread()
    assertThat(holder.measuredHeight).isEqualTo(160)
    assertThat(remeasureRequests(binder)).isEqualTo(1)

    updateDelta(-120)

    assertThat(remeasureRequests(binder)).isEqualTo(2)
    assertThat(measure(binder)).isEqualTo(100)
  }

  private fun mountWithOneItem(
      remeasureOnItemHeightChange: Boolean,
      baseHeight: Int = 100,
  ): RecyclerBinder {
    val binder =
        RecyclerBinder.Builder()
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .wrapContent(true)
                    .remeasureOnItemHeightChange(remeasureOnItemHeightChange)
                    .build(),
            )
            .build(context)
    binder.mount(recyclerView)
    binder.insertItemAt(0, ComponentRenderInfo.create().component(item(baseHeight)).build())
    binder.notifyChangeSetComplete(true, RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    assertThat(measure(binder)).isEqualTo(baseHeight)
    return binder
  }

  private fun item(baseHeight: Int) = ResizableItem(baseHeight) { delta.set(it) }

  private fun updateDelta(value: Int) {
    delta.get().update(value)
    runLayoutThread()
  }

  private fun runLayoutThread() {
    shadowOf(ComponentTree.getDefaultLayoutThreadLooper()).runToEndOfTasks()
  }

  private fun remeasureRequests(binder: RecyclerBinder): Int =
      recyclerView.getPostAnimationRunnableList().count { it === binder.remeasureRunnable }

  private fun measure(binder: RecyclerBinder): Int {
    val size = Size()
    binder.measure(size, widthSpec, heightSpec, reMeasureEventHandler)
    return size.height
  }

  private class ResizableItem(
      private val baseHeight: Int,
      private val onDeltaState: (State<Int>) -> Unit,
  ) : PrimitiveComponent() {

    override fun PrimitiveComponentScope.render(): LithoPrimitive {
      val delta = useState { 0 }
      onDeltaState(delta)
      return LithoPrimitive(
          layoutBehavior = FixedHeight(baseHeight + delta.value),
          mountBehavior =
              MountBehavior(ViewAllocator { androidContext -> View(androidContext) }) {},
          style = null,
      )
    }
  }

  private class FixedHeight(private val height: Int) : LayoutBehavior {
    override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult =
        PrimitiveLayoutResult(width = sizeConstraints.maxWidth, height = height)
  }
}
