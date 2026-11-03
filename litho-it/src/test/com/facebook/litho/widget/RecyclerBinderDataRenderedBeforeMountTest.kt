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

import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.ComponentContext
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventBus
import com.facebook.rendercore.debug.FilteredDebugEventSubscriber
import com.facebook.rendercore.debug.StringAttributeMatcher
import java.lang.ref.WeakReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class RecyclerBinderDataRenderedBeforeMountTest {

  private val droppedBeforeMountEvents = mutableListOf<DebugEvent>()

  private val subscriber = FilteredDebugEventSubscriber(
      LithoDebugEvent.DebugInfo,
      matcher = StringAttributeMatcher(DebugEventAttribute.Name to DROPPED_BEFORE_MOUNT_CATEGORY),
  ) {
    droppedBeforeMountEvents.add(it)
  }

  @Before
  fun setup() {
    DebugEventBus.subscribe(subscriber)
  }

  @After
  fun tearDown() {
    DebugEventBus.unsubscribe(subscriber)
  }

  private class RecordingCallback(val generation: Int, val rendered: MutableList<Int>) :
      ChangeSetCompleteCallback {
    override fun onDataBound() = Unit

    override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) {
      rendered.add(generation)
    }
  }

  private fun createBinder(bounded: Boolean): RecyclerBinder =
      RecyclerBinder.Builder()
          .recyclerBinderConfig(
              RecyclerBinderConfig.create()
                  .componentsConfiguration(
                      ComponentsConfiguration.defaultInstance.copy(
                          boundDataRenderedCallbacksBeforeMount = bounded,
                      ),
                  )
                  .build(),
          )
          .build(ComponentContext(getApplicationContext()))

  private fun notifyGenerations(
      binder: RecyclerBinder,
      generations: IntRange,
      rendered: MutableList<Int>,
  ): List<WeakReference<ChangeSetCompleteCallback>> = generations.map { generation ->
    val callback = RecordingCallback(generation, rendered)
    binder.notifyChangeSetComplete(true, callback)
    WeakReference<ChangeSetCompleteCallback>(callback)
  }

  private fun mountWithoutPendingUpdates(binder: RecyclerBinder) {
    val recyclerView = mock(LithoRecyclerView::class.java)
    whenever(recyclerView.hasPendingAdapterUpdates()).thenReturn(false)
    binder.mount(recyclerView)
  }

  @Test
  fun `bounded queue keeps the latest generations in order and releases the rest`() {
    val binder = createBinder(bounded = true)
    val rendered = mutableListOf<Int>()

    val dropped = notifyGenerations(binder, 0 until GENERATIONS - MAX_PENDING, rendered)
    val kept = notifyGenerations(binder, GENERATIONS - MAX_PENDING until GENERATIONS, rendered)

    assertThat(binder.dataRenderedCallbacks.map { (it as RecordingCallback).generation })
        .containsExactlyElementsOf(GENERATIONS - MAX_PENDING until GENERATIONS)
    assertThat(
        dropped.all { reference ->
          binder.dataRenderedCallbacks.none { it === reference.get() }
        },
    )
        .isTrue()
    awaitCollected(dropped)
    assertThat(dropped.map { it.get() }).containsOnlyNulls()
    assertThat(kept.map { it.get() }).doesNotContainNull()

    mountWithoutPendingUpdates(binder)
    binder.notifyChangeSetComplete(true, RecordingCallback(GENERATIONS, rendered))
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered).containsExactlyElementsOf(GENERATIONS - MAX_PENDING..GENERATIONS)
  }

  @Test
  fun `bounded queue keeps the latest data-changed generation when trimming`() {
    val binder = createBinder(bounded = true)
    val rendered = mutableListOf<Int>()

    binder.notifyChangeSetComplete(true, RecordingCallback(0, rendered))
    for (generation in 1 until GENERATIONS) {
      binder.notifyChangeSetComplete(false, RecordingCallback(generation, rendered))
    }

    assertThat(binder.dataRenderedCallbacks.map { (it as RecordingCallback).generation })
        .containsExactlyElementsOf(
            listOf(0) + (GENERATIONS - MAX_PENDING + 1 until GENERATIONS),
        )
  }

  @Test
  fun `bounded queue keeps the data change committed from inside onDataBound`() {
    val binder = createBinder(bounded = true)
    val rendered = mutableListOf<Int>()
    val nested = RecordingCallback(1, rendered)
    val outer =
        object : ChangeSetCompleteCallback {
          override fun onDataBound() = binder.notifyChangeSetComplete(true, nested)

          override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) {
            rendered.add(0)
          }
        }

    binder.notifyChangeSetComplete(true, outer)
    for (generation in 2 until GENERATIONS) {
      binder.notifyChangeSetComplete(false, RecordingCallback(generation, rendered))
    }
    mountWithoutPendingUpdates(binder)
    binder.notifyChangeSetComplete(false, RecordingCallback(GENERATIONS, rendered))
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered)
        .containsExactlyElementsOf(listOf(1) + (GENERATIONS - MAX_PENDING + 1..GENERATIONS))
  }

  @Test
  fun `bounded queue permits callback reuse`() {
    val binder = createBinder(bounded = true)
    val callback = RecordingCallback(0, mutableListOf())
    repeat(GENERATIONS) { binder.notifyChangeSetComplete(it == 0, callback) }

    assertThat(binder.dataRenderedCallbacks).hasSize(MAX_PENDING)
  }

  @Test
  fun `bounded queue reports dropped callbacks once per binder`() {
    val binder = createBinder(bounded = true)

    notifyGenerations(binder, 0 until GENERATIONS, mutableListOf())

    assertThat(droppedBeforeMountReports()).isEqualTo(1)
  }

  @Test
  fun `unbounded queue keeps every generation and does not report`() {
    val binder = createBinder(bounded = false)
    val rendered = mutableListOf<Int>()

    notifyGenerations(binder, 0 until GENERATIONS, rendered)

    assertThat(binder.dataRenderedCallbacks).hasSize(GENERATIONS)
    assertThat(droppedBeforeMountReports()).isEqualTo(0)
  }

  private fun droppedBeforeMountReports(): Int = droppedBeforeMountEvents.size

  private fun awaitCollected(references: List<WeakReference<*>>) {
    repeat(GC_ATTEMPTS) {
      if (references.all { it.get() == null }) return
      System.gc()
    }
  }

  private companion object {
    const val DROPPED_BEFORE_MOUNT_CATEGORY = "RecyclerBinder:DataRenderedDroppedBeforeMount"
    const val GENERATIONS = 50
    const val MAX_PENDING = 20
    const val GC_ATTEMPTS = 10
  }
}
