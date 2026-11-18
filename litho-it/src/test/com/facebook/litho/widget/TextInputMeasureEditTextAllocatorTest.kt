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

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.View.MeasureSpec
import androidx.activity.ComponentActivity
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.ThreadUtils
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.kotlin.widget.TextInput
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.MountContentPools
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class TextInputMeasureEditTextAllocatorTest {

  private val activityControllers = mutableListOf<ActivityController<ComponentActivity>>()

  @Before
  fun setUp() {
    ComponentsConfiguration.prefetchTextInputMeasureEditText = true
    layoutLooper().pause()
  }

  @After
  fun tearDown() {
    activityControllers.filterNot { it.get().isDestroyed }.forEach { it.destroy() }
    layoutLooper().idle()
    layoutLooper().unPause()
    MountContentPools.clear()
    ComponentsConfiguration.prefetchTextInputMeasureEditText = false
    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED)
  }

  @Test
  fun `take uses the view built in the background for the same context, then refills the pool`() {
    val activity = newActivity()

    TextInputMeasureEditTextAllocator.prefill(activity)
    assertThat(pooledViews(activity)).isEqualTo(0)

    runLayoutThread()
    assertThat(pooledViews(activity)).isEqualTo(1)
    val first = TextInputMeasureEditTextAllocator.take(activity)
    assertThat(first?.context).isSameAs(activity)
    assertThat(pooledViews(activity)).isEqualTo(0)

    runLayoutThread()
    assertThat(pooledViews(activity)).isEqualTo(1)
    assertThat(TextInputMeasureEditTextAllocator.take(activity)).isNotNull.isNotSameAs(first)
  }

  @Test
  fun `the view is built only once the layout thread has no other work queued`() {
    val activity = newActivity()
    TextInputMeasureEditTextAllocator.prefill(activity)
    Handler(ComponentTree.getDefaultLayoutThreadLooper()).post {}

    layoutLooper().runOneTask()
    assertThat(pooledViews(activity)).isEqualTo(0)

    layoutLooper().runOneTask()
    assertThat(pooledViews(activity)).isEqualTo(1)
  }

  @Test
  fun `nothing is built or taken when the flag is off`() {
    val activity = newActivity()
    ComponentsConfiguration.prefetchTextInputMeasureEditText = false

    TextInputMeasureEditTextAllocator.prefill(activity)
    runLayoutThread()

    assertThat(pooledViews(activity)).isEqualTo(0)
    assertThat(TextInputMeasureEditTextAllocator.take(activity)).isNull()
  }

  @Test
  fun `a view built for one context is not given to another`() {
    val activity = newActivity()
    TextInputMeasureEditTextAllocator.prefill(activity)
    runLayoutThread()

    assertThat(TextInputMeasureEditTextAllocator.take(newActivity())).isNull()
    assertThat(pooledViews(activity)).isEqualTo(1)
  }

  @Test
  fun `a view built before a configuration change is not taken`() {
    val context = ConfigurationSwitchingContext(newActivity())
    TextInputMeasureEditTextAllocator.prefill(context)
    runLayoutThread()

    context.switchToLandscape()
    assertThat(TextInputMeasureEditTextAllocator.take(context)).isNull()
    assertThat(pooledViews(context)).isEqualTo(0)

    runLayoutThread()
    assertThat(pooledViews(context)).isEqualTo(1)
    assertThat(TextInputMeasureEditTextAllocator.take(context)).isNotNull
  }

  @Test
  fun `a build that throws does not stop the next one`() {
    val context = FirstThemeFailingContext(newActivity())
    // Unpaused, the layout thread runs idle handlers through MessageQueue, which logs an exception
    // thrown by one and keeps looping, as on a device. A paused Robolectric looper shuts down.
    layoutLooper().unPause()
    try {
      TextInputMeasureEditTextAllocator.prefill(context)
      assertThat(waitUntil { context.hasFailed }).isTrue()
      val drained = AtomicBoolean(false)
      Handler(ComponentTree.getDefaultLayoutThreadLooper()).post { drained.set(true) }
      assertThat(waitUntil { drained.get() }).isTrue()
      assertThat(pooledViews(context)).isEqualTo(0)

      TextInputMeasureEditTextAllocator.prefill(context)
      assertThat(waitUntil { pooledViews(context) == 1 }).isTrue()
    } finally {
      layoutLooper().pause()
    }
  }

  @Test
  fun `take returns nothing off the main thread`() {
    val activity = newActivity()
    TextInputMeasureEditTextAllocator.prefill(activity)
    runLayoutThread()

    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_MAIN_THREAD_FALSE)
    assertThat(TextInputMeasureEditTextAllocator.take(activity)).isNull()
    assertThat(pooledViews(activity)).isEqualTo(1)

    ThreadUtils.setMainThreadOverride(ThreadUtils.OVERRIDE_DISABLED)
    assertThat(TextInputMeasureEditTextAllocator.take(activity)).isNotNull
    assertThat(pooledViews(activity)).isEqualTo(0)
  }

  @Test
  fun `destroying the activity drops its view`() {
    val controller = newActivityController()
    val activity = controller.get()
    TextInputMeasureEditTextAllocator.prefill(activity)
    runLayoutThread()
    // Runs the posted registration of the pools' lifecycle observer.
    shadowOf(Looper.getMainLooper()).idle()
    assertThat(pooledViews(activity)).isEqualTo(1)

    controller.destroy()

    assertThat(pooledViews(activity)).isEqualTo(0)
  }

  @Test
  fun `nothing is built for an activity destroyed before the build runs`() {
    val controller = newActivityController()
    val activity = controller.get()
    TextInputMeasureEditTextAllocator.prefill(activity)

    controller.destroy()
    runLayoutThread()

    assertThat(pooledViews(activity)).isEqualTo(0)
  }

  @Test
  fun `a TextInput measured on the main thread uses the pooled view and measures the same`() {
    val activity = newActivity()
    ComponentsConfiguration.prefetchTextInputMeasureEditText = false
    val sizeWithoutPool = measureTextInput(activity)
    ComponentsConfiguration.prefetchTextInputMeasureEditText = true

    TextInputMeasureEditTextAllocator.prefill(activity)
    runLayoutThread()
    assertThat(pooledViews(activity)).isEqualTo(1)
    val sizeWithPool = measureTextInput(activity)

    assertThat(pooledViews(activity)).isEqualTo(0)
    assertThat(sizeWithPool).isEqualTo(sizeWithoutPool)
  }

  @Test
  fun `measuring a TextInput while the activity is being created doesn't break its launch`() {
    val controller = Robolectric.buildActivity(ComponentActivity::class.java)
    activityControllers.add(controller)
    var heightInOnCreate = 0
    // Runs inside onCreate, before the lifecycle reaches CREATED.
    controller.get().addOnContextAvailableListener {
      heightInOnCreate = measureTextInput(controller.get()).second
    }

    controller.setup()

    assertThat(heightInOnCreate).isGreaterThan(0)
    assertThat(pooledViews(controller.get())).isEqualTo(0)
  }

  private fun measureTextInput(activity: ComponentActivity): Pair<Int, Int> {
    val lithoView = LithoView(activity)
    lithoView.componentTree =
        ComponentTree.create(ComponentContext(activity), MultilineTextInput()).build()
    lithoView.measure(
        MeasureSpec.makeMeasureSpec(300, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
    )
    return lithoView.measuredWidth to lithoView.measuredHeight
  }

  private fun pooledViews(context: Context): Int =
      MountContentPools.getContentPoolItemCount(context, TextInputMeasureEditTextAllocator)

  private fun layoutLooper() = shadowOf(ComponentTree.getDefaultLayoutThreadLooper())

  private fun runLayoutThread() {
    layoutLooper().idle()
  }

  /** Checks [condition] on this thread every 10 ms, for up to 5 s. */
  private fun waitUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
      if (System.nanoTime() > deadline) {
        return false
      }
      Thread.sleep(10)
    }
    return true
  }

  private fun newActivity(): ComponentActivity = newActivityController().get()

  private fun newActivityController(): ActivityController<ComponentActivity> =
      Robolectric.buildActivity(ComponentActivity::class.java).setup().also {
        activityControllers.add(it)
      }

  /** Keeps its identity while its resources switch to another configuration. */
  private class ConfigurationSwitchingContext(base: Context) : ContextWrapper(base) {
    private var switchedResources: Resources? = null

    override fun getResources(): Resources = switchedResources ?: super.getResources()

    fun switchToLandscape() {
      val landscape =
          Configuration(resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
          }
      switchedResources = createConfigurationContext(landscape).resources
    }
  }

  /** Throws from its first [getTheme] call, so the first view built for it fails to construct. */
  private class FirstThemeFailingContext(base: Context) : ContextWrapper(base) {
    private val failNext = AtomicBoolean(true)

    val hasFailed: Boolean
      get() = !failNext.get()

    override fun getTheme(): Resources.Theme {
      check(!failNext.getAndSet(false)) { "The first build fails" }
      return super.getTheme()
    }
  }

  private class MultilineTextInput : KComponent() {
    override fun ComponentScope.render(): Component =
        TextInput(initialText = "Measured text that wraps onto a second line", multiline = true)
  }
}
