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

import com.facebook.rendercore.utils.NoDeps
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class KStateContainerCachePerfTest {

  @Test
  fun `retaining unchanged hook order reads each live key once`() {
    for (hookCount in listOf(1, 16, 64)) {
      val keys = keys(hookCount)
      val container = container(keys)
      val live = CountingKeys(keys)

      assertThat(container.retainByHookKeys(live)).isSameAs(container)
      assertThat(live.reads).isEqualTo(hookCount)
    }
  }

  @Test
  fun `retaining reordered hooks preserves the container and entry order`() {
    val keys = keys(16)
    val container = container(keys)

    assertThat(container.retainByHookKeys(keys.reversed())).isSameAs(container)
    assertThat(container.states.map { it.hookKey }).containsExactlyElementsOf(keys)
  }

  @Test
  fun `retaining replacement hook removes only the absent entry`() {
    val keys = keys(4)
    val container = container(keys)
    val replacement = HookKey.create(2, 0, parent = null, intern = false)
    val live = listOf(keys[0], keys[1], keys[2], replacement)
    val retained = container.retainByHookKeys(live)

    assertThat(retained.states.map { it.hookKey }).containsExactlyElementsOf(keys.take(3))
    assertThat(container.states.map { it.hookKey }).containsExactlyElementsOf(keys)
  }

  @Test
  fun `retaining hook keys always keeps legacy entries`() {
    val keys = keys(2)
    val legacy = CachedValue(NoDeps, "legacy")
    val container = KStateContainer.fromEntries(listOf(legacy, CachedValue(keys[0], NoDeps, 0)))

    val retained = container.retainByHookKeys(listOf(keys[1], keys[0]))
    assertThat(retained).isSameAs(container)
    assertThat(container.retainByHookKeys(emptyList()).states).containsExactly(legacy)
  }

  private fun keys(count: Int): List<HookKey> =
      List(count) { HookKey.create(1, it, parent = null, intern = false) }

  private fun container(keys: List<HookKey>): KStateContainer =
      KStateContainer.fromEntries(keys.mapIndexed { index, key -> CachedValue(key, NoDeps, index) })

  private class CountingKeys(private val keys: List<HookKey>) : AbstractList<HookKey>() {
    var reads: Int = 0
      private set

    override val size: Int
      get() = keys.size

    override fun get(index: Int): HookKey {
      reads++
      return keys[index]
    }
  }
}
