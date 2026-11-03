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

package com.facebook.litho.animation

import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.LithoRenderer
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.ms
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class SystemDurationScaleSourceTest {
  @Test
  fun preViewCommitInitializesBeforeMotionAndObservesLiveSettings() {
    val context = ComponentContext(RuntimeEnvironment.getApplication())
    val resolver = context.androidContext.contentResolver
    val setting = Settings.Global.ANIMATOR_DURATION_SCALE
    val uri = Settings.Global.getUriFor(setting)
    val previous = Settings.Global.getString(resolver, setting)
    val driver = FrameDriver()
    val linear = tween<Float>(1_000.ms, Easing.Linear)
    val sizeSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
    try {
      Settings.Global.putFloat(resolver, setting, 0f)
      AnimationController.withFrameDriverForTest(driver, observeSystemDurationScale = true) {
        lateinit var command: Animatable<Float>
        val tree = ComponentTree.create(context).build()
        try {
          tree.setRootAndSizeSpecSync(
              object : KComponent() {
                override fun ComponentScope.render(): Component {
                  command = useAnimatable(0f, FloatTypeConverter)
                  return EmptyComponent()
                }
              },
              sizeSpec,
              sizeSpec,
          )
          shadowOf(Looper.getMainLooper()).idle()
          assertThat(tree.lithoView).isNull()
          var completions = 0
          command.startAnimation(0f, tween(0.ms)) { completions++ }
          command.startAnimation(1f, linear) { completions++ }
          driver.frame(0L)
          assertThat(command.value.value).isEqualTo(1f)
          assertThat(completions).isEqualTo(2)
          assertThat(driver.callback).isNull()

          Settings.Global.putFloat(resolver, setting, 2f)
          resolver.notifyChange(uri, null)
          shadowOf(Looper.getMainLooper()).idle()
          command.startAnimation(0f, linear)
          driver.frame(100.ms.inWholeNanoseconds)
          driver.frame(300.ms.inWholeNanoseconds)
          assertThat(command.value.value).isCloseTo(0.9f, offset(0.0001f))

          Settings.Global.putFloat(resolver, setting, 0.5f)
          driver.frame(400.ms.inWholeNanoseconds)
          assertThat(command.value.value).isCloseTo(0.85f, offset(0.0001f))
          resolver.notifyChange(uri, null)
          shadowOf(Looper.getMainLooper()).idle()
          driver.frame(500.ms.inWholeNanoseconds)
          assertThat(command.value.value).isCloseTo(0.65f, offset(0.0001f))

          Settings.Global.putFloat(resolver, setting, 0f)
          resolver.notifyChange(uri, null)
          shadowOf(Looper.getMainLooper()).idle()
          driver.frame(600.ms.inWholeNanoseconds)
          assertThat(command.value.value).isZero()
          assertThat(driver.callback).isNull()
        } finally {
          tree.release()
        }
      }
    } finally {
      Settings.Global.putString(resolver, setting, previous)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }

  @Test
  fun rendererCommitSuppliesSettingsBeforeItsFirstAnimationFrame() {
    val context = RuntimeEnvironment.getApplication()
    val resolver = context.contentResolver
    val setting = Settings.Global.ANIMATOR_DURATION_SCALE
    val previous = Settings.Global.getString(resolver, setting)
    val driver = FrameDriver()
    try {
      Settings.Global.putFloat(resolver, setting, 2f)
      AnimationController.withFrameDriverForTest(driver, observeSystemDurationScale = true) {
        lateinit var value: Value<Float>
        fun component(target: Float) =
            object : KComponent() {
              override fun ComponentScope.render(): Component {
                value = useAnimatedFloat(target, tween(1_000.ms, Easing.Linear))
                return EmptyComponent()
              }
            }
        val renderer = LithoRenderer(context)
        renderer.bindTo(LithoRenderTreeView(context))
        try {
          val constraints = SizeConstraints(100, 100, 100, 100)
          renderer.renderSync(component(0f), constraints)
          shadowOf(Looper.getMainLooper()).idle()
          assertThat(value.value).isZero()
          renderer.renderSync(component(1f), constraints)
          shadowOf(Looper.getMainLooper()).idle()
          driver.frame(0L)
          driver.frame(100.ms.inWholeNanoseconds)
          assertThat(value.value).isCloseTo(0.05f, offset(0.0001f))
        } finally {
          renderer.unbind()
          renderer.cleanup()
        }
      }
    } finally {
      Settings.Global.putString(resolver, setting, previous)
      shadowOf(Looper.getMainLooper()).idle()
    }
  }

  private class FrameDriver : AnimationFrameDriver {
    var callback: Choreographer.FrameCallback? = null

    override fun postFrameCallback(callback: Choreographer.FrameCallback) {
      this.callback = callback
    }

    override fun removeFrameCallback(callback: Choreographer.FrameCallback) {
      if (this.callback === callback) this.callback = null
    }

    fun frame(timeNanos: Long) {
      val scheduled = callback
      callback = null
      scheduled?.doFrame(timeNanos)
    }
  }
}
