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

import android.graphics.Color
import com.facebook.litho.config.UnmountOnDetach
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.visibility.onInvisible
import com.facebook.litho.visibility.onVisible
import com.facebook.litho.widget.SolidColor
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class VisibilityExtensionTest {

  @JvmField @Rule var lithoTestRule = LithoTestRule()

  @Test
  fun `when litho view is in transient state then visibility event should not be dispatched`() {
    class VisibilityEventCallbackComponent(val callback: () -> Unit) : KComponent() {
      override fun ComponentScope.render(): Component {
        return SolidColor(
            Color.BLACK,
            style = Style.width(100.px).height(100.px).onVisible { callback() },
        )
      }
    }

    var wasCalled = false
    val component = VisibilityEventCallbackComponent(callback = { wasCalled = true })

    val lithoView = LithoView(lithoTestRule.context)
    lithoView.setHasTransientState(true)

    lithoTestRule.render(lithoView = lithoView) { component }

    assertThat(wasCalled).isFalse

    lithoView.setHasTransientState(false)

    assertThat(wasCalled).isTrue
  }

  @Test
  fun `when litho view was detached and re-attached then visibility event should be dispatched`() {
    class VisibilityEventCallbackComponent(val callback: (Boolean) -> Unit) : KComponent() {
      override fun ComponentScope.render(): Component {
        return SolidColor(
            Color.BLACK,
            style =
                Style.width(100.px)
                    .height(100.px)
                    .onVisible { callback(true) }
                    .onInvisible { callback(false) },
        )
      }
    }

    var isVisible = false
    val component = VisibilityEventCallbackComponent(callback = { isVisible = it })
    val testLithoView = lithoTestRule.render { component }

    val config = testLithoView.lithoView.configuration
    if (config != null && config.unmountOnDetach != UnmountOnDetach.Never) {
      assertThat(isVisible).isTrue()

      testLithoView.detachFromWindow()
      assertThat(isVisible).isFalse()

      testLithoView.attachToWindow()
      testLithoView.lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.UNKNOWN)
      assertThat(isVisible).isTrue()
    }
  }

  @Test
  fun `when setVisibilityHint(true) is being called when detached then nothing should be dispatched`() {
    class VisibilityEventCallbackComponent(val callback: (Boolean) -> Unit) : KComponent() {
      override fun ComponentScope.render(): Component {
        return SolidColor(
            Color.BLACK,
            style =
                Style.width(100.px)
                    .height(100.px)
                    .onVisible { callback(true) }
                    .onInvisible { callback(false) },
        )
      }
    }

    var isVisible = false
    val component = VisibilityEventCallbackComponent(callback = { isVisible = it })
    val testLithoView = lithoTestRule.render { component }

    val config = testLithoView.lithoView.configuration
    if (config != null && config.unmountOnDetach != UnmountOnDetach.Never) {
      assertThat(isVisible).isTrue()

      testLithoView.detachFromWindow()
      assertThat(isVisible).isFalse()

      testLithoView.lithoView.setVisibilityHint(true)
      assertThat(isVisible).isFalse()
    }
  }
}
