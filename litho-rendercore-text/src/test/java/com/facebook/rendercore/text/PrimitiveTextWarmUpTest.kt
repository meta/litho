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

package com.facebook.rendercore.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [PrimitiveTextWarmUp] runs on the app-startup path in every experiment arm, so it must complete
 * without throwing. Its purpose, getting the primitive Text methods into the harvested ART profile,
 * only shows up in a profile harvest and cannot be asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class PrimitiveTextWarmUpTest {

  @Test
  fun `warm up completes and records a result`() {
    PrimitiveTextWarmUp.warmUp(RuntimeEnvironment.getApplication())

    assertThat(PrimitiveTextWarmUp.lastResult)
        .describedAs("the sink that stops R8/Redex eliminating the warm-up")
        .isNotNull
  }
}
