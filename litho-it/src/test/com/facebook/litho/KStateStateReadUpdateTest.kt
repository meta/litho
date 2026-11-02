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

import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

@RunWith(LithoTestRunner::class)
class KStateStateReadUpdateTest {
  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `skip subsequent resolve if updated state not read in resolve`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger(0)

    lithoTestRule.render { MyStateReadUpdateComponent(renderCount, readInResolve = false) }
    repeat(10) { ShadowLooper.runMainLooperOneTask() }

    Assertions.assertThat(renderCount.get()).isEqualTo(1)
  }

  @Test
  fun `do not skip subsequent resolve if updated state read in resolve`() {
    ShadowLooper.pauseMainLooper()

    val renderCount = AtomicInteger(0)

    lithoTestRule.render { MyStateReadUpdateComponent(renderCount, readInResolve = true) }
    repeat(10) { ShadowLooper.runMainLooperOneTask() }

    Assertions.assertThat(renderCount.get()).isEqualTo(11)
  }
}

private class MyStateReadUpdateComponent(
    private val renderCount: AtomicInteger,
    private val readInResolve: Boolean = false,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    renderCount.incrementAndGet()

    val state = useState { 0 }
    if (readInResolve) state.value
    state.updateSync { it + 1 }

    return Column()
  }
}
