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

package com.facebook.litho.host

import com.facebook.litho.ComponentHost
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class HostViewAttributesCleanupBinderTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `unbind resets translation when enabled`() {
    val host = translatedHost()

    HostViewAttributesCleanupBinder(resetTranslation = true).unbind(host)

    assertThat(host.translationX).isEqualTo(0f)
    assertThat(host.translationY).isEqualTo(0f)
  }

  @Test
  fun `unbind keeps translation when disabled`() {
    val host = translatedHost()

    HostViewAttributesCleanupBinder().unbind(host)

    assertThat(host.translationX).isEqualTo(-480f)
    assertThat(host.translationY).isEqualTo(-861f)
  }

  private fun translatedHost(): ComponentHost =
      ComponentHost(lithoTestRule.context).apply {
        translationX = -480f
        translationY = -861f
      }

  private fun HostViewAttributesCleanupBinder.unbind(host: ComponentHost) {
    unbind(host.context, host, null, null, null)
  }
}
