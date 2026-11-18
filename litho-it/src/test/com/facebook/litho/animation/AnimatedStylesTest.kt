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

import android.os.Looper.getMainLooper
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimatedStylesTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private var scaleX: Animatable<Float>? = null
  private var scaleY: Animatable<Float>? = null

  @Test
  fun scaleXAndScaleY_applyEachAxisIndependently() {
    val testView = lithoTestRule.render { PerAxisScaleComponent() }
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.scaleX).isEqualTo(0.5f)
    assertThat(testView.lithoView.scaleY).isEqualTo(2f)
  }

  @Test
  fun scaleX_valueChange_updatesOnlyScaleX() {
    val testView = lithoTestRule.render { PerAxisScaleComponent() }
    shadowOf(getMainLooper()).idle()

    checkNotNull(scaleX).jumpTo(1.5f)
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.scaleX).isEqualTo(1.5f)
    assertThat(testView.lithoView.scaleY).isEqualTo(2f)
  }

  @Test
  fun scaleY_valueChange_updatesOnlyScaleY() {
    val testView = lithoTestRule.render { PerAxisScaleComponent() }
    shadowOf(getMainLooper()).idle()

    checkNotNull(scaleY).jumpTo(0.25f)
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.scaleX).isEqualTo(0.5f)
    assertThat(testView.lithoView.scaleY).isEqualTo(0.25f)
  }

  @Test
  fun scaleThenScaleX_scaleXOverridesUntilRemoved() {
    val testView = lithoTestRule.render { UniformThenPerAxisScaleComponent(overrideX = true) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.scaleX).isEqualTo(0.5f)
    assertThat(testView.lithoView.scaleY).isEqualTo(1f)

    // A change to the uniform scale rebinds only its own axes, so the X override survives.
    checkNotNull(scale).jumpTo(2f)
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.scaleX).isEqualTo(0.5f)
    assertThat(testView.lithoView.scaleY).isEqualTo(2f)

    testView.setRoot(UniformThenPerAxisScaleComponent(overrideX = false))
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.scaleX).isEqualTo(2f)
    assertThat(testView.lithoView.scaleY).isEqualTo(2f)
  }

  private var scale: Animatable<Float>? = null

  private inner class UniformThenPerAxisScaleComponent(private val overrideX: Boolean) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      val s = useAnimatable(1f, FloatTypeConverter, label = "scale").also { scale = it }
      val x = useAnimatable(0.5f, FloatTypeConverter, label = "scaleX").also { scaleX = it }
      val style = Style.width(100.px).height(100.px).scale(s.value)
      return Row(style = if (overrideX) style.scaleX(x.value) else style)
    }
  }

  private inner class PerAxisScaleComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val x = useAnimatable(0.5f, FloatTypeConverter, label = "scaleX").also { scaleX = it }
      val y = useAnimatable(2f, FloatTypeConverter, label = "scaleY").also { scaleY = it }
      return Row(style = Style.width(100.px).height(100.px).scaleX(x.value).scaleY(y.value))
    }
  }
}
