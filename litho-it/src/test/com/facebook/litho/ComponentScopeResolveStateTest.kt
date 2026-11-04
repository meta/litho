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

import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class ComponentScopeResolveStateTest {

  @Rule @JvmField val lithoViewRule = LithoTestRule()

  @Test
  fun `the resolve state of a render reads false once render returns`() {
    var scope: ComponentScope? = null
    val tree = ComponentTree.create(lithoViewRule.context).build()

    tree.setRootAndSizeSpecSync(
        CapturingScope { scope = it },
        exactly(100),
        exactly(100),
    )

    val captured = checkNotNull(scope)
    assertThat(captured.isResolveInterrupted).isFalse()
    assertThat(captured.isResolveCancelled).isFalse()
    tree.release()
  }

  private class CapturingScope(private val onRender: (ComponentScope) -> Unit) : KComponent() {
    override fun ComponentScope.render(): Component {
      onRender(this)
      return Row(style = Style.width(1.px))
    }
  }
}
