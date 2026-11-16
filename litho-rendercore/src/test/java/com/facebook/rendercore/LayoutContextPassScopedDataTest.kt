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

package com.facebook.rendercore

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LayoutContextPassScopedDataTest {
  @Test
  fun passDataIsSharedWithinOneContextOnly() {
    val context = newContext()
    val key = Any()
    val otherKey = Any()
    val stored = Any()
    assertThat(context.getPassScopedData<Any>(key)).isNull()
    context.putPassScopedData(key, stored)
    assertThat(context.getPassScopedData<Any>(key)).isSameAs(stored)
    assertThat(context.getPassScopedData<Any>(otherKey)).isNull()
    assertThat(newContext().getPassScopedData<Any>(key)).isNull()

    val replacement = Any()
    context.putPassScopedData(key, replacement)
    assertThat(context.getPassScopedData<Any>(key)).isSameAs(replacement)
  }

  @Test
  fun passDataIsNotCarriedInTheLayoutCacheAndIsDroppedWhenCleared() {
    val context = newContext()
    val key = Any()
    context.putPassScopedData(key, Any())
    val nextCache = LayoutCache(context.layoutCache.toCacheData())
    assertThat(nextCache.get<Any>(key)).isNull()
    context.clearCache()
    assertThat(context.getPassScopedData<Any>(key)).isNull()
  }

  private fun newContext(): LayoutContext<Any> =
      LayoutContext(RuntimeEnvironment.getApplication(), null, 0, LayoutCache(), null)
}
