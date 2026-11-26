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
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.ComponentContext
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class RecyclerBinderDataRenderedAfterLayoutTest {

  // Posted callbacks only run when a test advances the looper, as on a device.
  @Before
  fun pauseMainLooper() {
    ShadowLooper.pauseMainLooper()
  }

  @After
  fun unpauseMainLooper() {
    ShadowLooper.unPauseMainLooper()
  }

  private class RecordingCallback(val rendered: MutableList<Boolean>) : ChangeSetCompleteCallback {
    override fun onDataBound() = Unit

    override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) {
      rendered.add(isMounted)
    }
  }

  private fun createBinder(global: Boolean, override: Boolean? = null): RecyclerBinder =
      RecyclerBinder.Builder()
          .recyclerBinderConfig(
              RecyclerBinderConfig.create()
                  .componentsConfiguration(
                      ComponentsConfiguration.defaultInstance.copy(
                          dispatchDataRenderedAfterLayout = global,
                      ),
                  )
                  .dispatchDataRenderedAfterLayout(override)
                  .build(),
          )
          .build(ComponentContext(getApplicationContext()))

  private val treeObserver: ViewTreeObserver =
      mock(ViewTreeObserver::class.java).also { whenever(it.isAlive).thenReturn(true) }

  /** A visible, attached view whose adapter updates are not applied yet. */
  private fun visibleViewWithPendingUpdates(): LithoRecyclerView {
    val view = mock(LithoRecyclerView::class.java)
    whenever(view.hasPendingAdapterUpdates()).thenReturn(true)
    whenever(view.isAttachedToWindow).thenReturn(true)
    whenever(view.alpha).thenReturn(1f)
    whenever(view.getGlobalVisibleRect(any())).thenReturn(true)
    whenever(view.viewTreeObserver).thenReturn(treeObserver)
    return view
  }

  private fun capturePreDrawListener(): ViewTreeObserver.OnPreDrawListener {
    val listener = argumentCaptor<ViewTreeObserver.OnPreDrawListener>()
    verify(treeObserver).addOnPreDrawListener(listener.capture())
    return listener.firstValue
  }

  @Test
  fun `a change set laid out without a redraw dispatches on the next pre-draw`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    val rendered = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(rendered))
    val preDraw = capturePreDrawListener()
    ShadowLooper.runUiThreadTasks()
    assertThat(rendered).isEmpty()

    // The frame lays out the change set but does not redraw the view: only the pre-draw fires.
    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    preDraw.onPreDraw()
    assertThat(rendered).isEmpty()
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered).containsExactly(true)
    verify(treeObserver).removeOnPreDrawListener(preDraw)
  }

  @Test
  fun `a pre-draw before the change set is laid out keeps waiting`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    val rendered = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(rendered))
    val preDraw = capturePreDrawListener()
    preDraw.onPreDraw()
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered).isEmpty()
    verify(treeObserver, never()).removeOnPreDrawListener(any())
    verify(treeObserver, times(1)).addOnPreDrawListener(any())
  }

  @Test
  fun `a redraw dispatches once and removes the pre-draw listener`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    val drawListener = argumentCaptor<PostDispatchDrawListener>()
    verify(view).registerPostDispatchDrawListener(drawListener.capture())
    val rendered = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(rendered))
    val preDraw = capturePreDrawListener()
    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    drawListener.firstValue.postDispatchDraw(0)
    preDraw.onPreDraw()
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered).containsExactly(true)
    verify(treeObserver).removeOnPreDrawListener(preDraw)
  }

  @Test
  fun `pre-draw then draw in the same frame dispatches each callback once`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    val drawListener = argumentCaptor<PostDispatchDrawListener>()
    verify(view).registerPostDispatchDrawListener(drawListener.capture())
    val first = mutableListOf<Boolean>()
    val second = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(first))
    binder.notifyChangeSetComplete(false, RecordingCallback(second))
    val preDraw = capturePreDrawListener()
    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    preDraw.onPreDraw()
    drawListener.firstValue.postDispatchDraw(0)
    preDraw.onPreDraw()
    ShadowLooper.runUiThreadTasks()
    drawListener.firstValue.postDispatchDraw(0)
    preDraw.onPreDraw()
    ShadowLooper.runUiThreadTasks()

    assertThat(first).containsExactly(true)
    assertThat(second).containsExactly(true)
  }

  @Test
  fun `repeated pre-draws while updates are pending do not dispatch or re-register`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    val rendered = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(rendered))
    val preDraw = capturePreDrawListener()
    repeat(5) { preDraw.onPreDraw() }
    binder.notifyChangeSetComplete(false, RecordingCallback(rendered))
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered).isEmpty()
    verify(treeObserver, times(1)).addOnPreDrawListener(any())

    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    preDraw.onPreDraw()
    preDraw.onPreDraw()
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered).containsExactly(true, true)
  }

  @Test
  fun `a change set queued after a dispatch is delivered once, separately`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    val first = mutableListOf<Boolean>()
    val second = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(first))
    val preDraw = capturePreDrawListener()
    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    preDraw.onPreDraw()
    ShadowLooper.runUiThreadTasks()
    assertThat(first).containsExactly(true)

    whenever(view.hasPendingAdapterUpdates()).thenReturn(true)
    binder.notifyChangeSetComplete(true, RecordingCallback(second))
    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    preDraw.onPreDraw()
    preDraw.onPreDraw()
    ShadowLooper.runUiThreadTasks()

    assertThat(first).containsExactly(true)
    assertThat(second).containsExactly(true)
    verify(treeObserver, times(2)).addOnPreDrawListener(any())
    verify(treeObserver, times(2)).removeOnPreDrawListener(any())
  }

  @Test
  fun `unmount while callbacks are pending does not register the pre-draw listener again`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    binder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    val preDraw = capturePreDrawListener()

    binder.unmount(view)

    verify(treeObserver).removeOnPreDrawListener(preDraw)
    verify(treeObserver, times(1)).addOnPreDrawListener(any())
  }

  @Test
  fun `detach removes the pre-draw listener from the observer it was added to`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    binder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    val preDraw = capturePreDrawListener()
    val detachListener = argumentCaptor<View.OnAttachStateChangeListener>()
    verify(view).addOnAttachStateChangeListener(detachListener.capture())

    // Detached views return a different (floating) observer.
    val floatingObserver = mock(ViewTreeObserver::class.java)
    whenever(floatingObserver.isAlive).thenReturn(true)
    whenever(view.viewTreeObserver).thenReturn(floatingObserver)
    whenever(view.isAttachedToWindow).thenReturn(false)
    detachListener.firstValue.onViewDetachedFromWindow(view)

    verify(treeObserver).removeOnPreDrawListener(preDraw)
    verify(floatingObserver, never()).removeOnPreDrawListener(any())
    verify(view).removeOnAttachStateChangeListener(detachListener.firstValue)
  }

  @Test
  fun `clearing an overflowing queue removes the pre-draw listener`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    binder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    val preDraw = capturePreDrawListener()

    // More than DATA_RENDERED_CALLBACKS_QUEUE_MAX_SIZE (20) pending while updates stay pending.
    repeat(25) { binder.notifyChangeSetComplete(false, RecordingCallback(mutableListOf())) }

    verify(treeObserver).removeOnPreDrawListener(preDraw)
  }

  @Test
  fun `a plain RecyclerView keeps its own pre-draw listener and gets no extra pre-draw listener`() {
    val binder = createBinder(global = true)
    val view = mock(RecyclerView::class.java)
    whenever(view.hasPendingAdapterUpdates()).thenReturn(true)
    whenever(view.isAttachedToWindow).thenReturn(true)
    whenever(view.alpha).thenReturn(1f)
    whenever(view.getGlobalVisibleRect(any())).thenReturn(true)
    whenever(view.viewTreeObserver).thenReturn(treeObserver)
    binder.mount(view)
    verify(treeObserver, times(1)).addOnPreDrawListener(any())

    binder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))

    verify(treeObserver, times(1)).addOnPreDrawListener(any())
  }

  @Test
  fun `sub-adapter removal removes the pre-draw listener and does not re-add it`() {
    val binder =
        RecyclerBinder.Builder()
            .isSubAdapter(true)
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .componentsConfiguration(
                        ComponentsConfiguration.defaultInstance.copy(
                            dispatchDataRenderedAfterLayout = true,
                        ),
                    )
                    .build(),
            )
            .build(ComponentContext(getApplicationContext()))
    val view = visibleViewWithPendingUpdates()
    binder.setSubAdapterModeRecyclerView(view)
    binder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    val preDraw = capturePreDrawListener()

    binder.removeSubAdapterModeRecyclerView(view)

    verify(treeObserver).removeOnPreDrawListener(preDraw)
    verify(treeObserver, times(1)).addOnPreDrawListener(any())
  }

  @Test
  fun `registering on another view removes the listener from the previous one`() {
    val binder =
        RecyclerBinder.Builder()
            .isSubAdapter(true)
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .componentsConfiguration(
                        ComponentsConfiguration.defaultInstance.copy(
                            dispatchDataRenderedAfterLayout = true,
                        ),
                    )
                    .build(),
            )
            .build(ComponentContext(getApplicationContext()))
    val first = visibleViewWithPendingUpdates()
    binder.setSubAdapterModeRecyclerView(first)
    binder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    val preDraw = capturePreDrawListener()

    val secondObserver = mock(ViewTreeObserver::class.java)
    whenever(secondObserver.isAlive).thenReturn(true)
    val second = visibleViewWithPendingUpdates()
    whenever(second.viewTreeObserver).thenReturn(secondObserver)
    binder.setSubAdapterModeRecyclerView(second)
    binder.notifyChangeSetComplete(false, RecordingCallback(mutableListOf()))

    verify(treeObserver).removeOnPreDrawListener(preDraw)
    verify(secondObserver).addOnPreDrawListener(preDraw)
  }

  @Test
  fun `nothing is registered when callbacks are dispatched right away`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    binder.mount(view)
    val rendered = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(rendered))
    ShadowLooper.runUiThreadTasks()

    assertThat(rendered).containsExactly(true)
    verify(treeObserver, never()).addOnPreDrawListener(any())
  }

  @Test
  fun `without the flag a laid out change set still waits for a draw`() {
    val binder = createBinder(global = false)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    val rendered = mutableListOf<Boolean>()

    binder.notifyChangeSetComplete(true, RecordingCallback(rendered))
    whenever(view.hasPendingAdapterUpdates()).thenReturn(false)
    ShadowLooper.runUiThreadTasks()

    verify(treeObserver, never()).addOnPreDrawListener(any())
    assertThat(rendered).isEmpty()
  }

  @Test
  fun `the binder config overrides the components configuration`() {
    val enabledByBinder = createBinder(global = false, override = true)
    enabledByBinder.mount(visibleViewWithPendingUpdates())
    enabledByBinder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    verify(treeObserver, times(1)).addOnPreDrawListener(any())

    val disabledByBinder = createBinder(global = true, override = false)
    disabledByBinder.mount(visibleViewWithPendingUpdates())
    disabledByBinder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    verify(treeObserver, times(1)).addOnPreDrawListener(any())
  }

  @Test
  fun `unmount removes the pre-draw listener`() {
    val binder = createBinder(global = true)
    val view = visibleViewWithPendingUpdates()
    binder.mount(view)
    binder.notifyChangeSetComplete(true, RecordingCallback(mutableListOf()))
    val preDraw = capturePreDrawListener()

    binder.unmount(view)

    verify(treeObserver).removeOnPreDrawListener(preDraw)
  }
}
