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

import com.facebook.litho.config.ComponentsConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Runs with each map [ComponentsConfiguration.useScatterMapInTreePropContainer] selects. A
 * container can meet one backed by the other map, for example one created before the flag was set,
 * so the cases that combine two containers also pair it with the other kind.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class TreePropContainerTest(private val useScatterMap: Boolean) {

  private val intProp: TreeProp<Int> = treePropOf { 7 }
  private val nullableProp: TreeProp<String?> = treePropOf { "default" }

  private var previousSetting = false

  @Before
  fun setUp() {
    previousSetting = ComponentsConfiguration.useScatterMapInTreePropContainer
    ComponentsConfiguration.useScatterMapInTreePropContainer = useScatterMap
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.useScatterMapInTreePropContainer = previousSetting
  }

  @Test
  fun `a tree prop that was never set reads its default`() {
    val container = TreePropContainer()

    assertThat(container[intProp]).isEqualTo(7)
    assertThat(container[String::class.java]).isNull()
  }

  @Test
  fun `a tree prop reads the last value set`() {
    val container = TreePropContainer()
    container.put(intProp, 1)
    container.put(intProp, 2)
    container.put(String::class.java, "a")
    container[String::class.java] = "b"

    assertThat(container[intProp]).isEqualTo(2)
    assertThat(container[String::class.java]).isEqualTo("b")
  }

  @Test
  fun `a tree prop set to null reads null rather than its default`() {
    val container = TreePropContainer()
    container.put(nullableProp, null)

    assertThat(container[nullableProp]).isNull()
    assertThat(container.isEmpty()).isFalse
  }

  @Test
  fun `reset removes every tree prop`() {
    val container = TreePropContainer()
    container.put(intProp, 1)
    container.put(String::class.java, "a")

    container.reset()

    assertThat(container.isEmpty()).isTrue
    assertThat(container[intProp]).isEqualTo(7)
    assertThat(container[String::class.java]).isNull()
  }

  @Test
  fun `acquire copies the source, and later writes to either side stay on that side`() {
    val source = filled(useScatterMap)

    val copy = TreePropContainer.acquire(source)
    copy.put(intProp, 100)
    source.put(nullableProp, "changed")

    assertThat(copy[intProp]).isEqualTo(100)
    assertThat(copy[nullableProp]).isNull()
    assertThat(copy[String::class.java]).isEqualTo("a")
    assertThat(source[intProp]).isEqualTo(1)
    assertThat(source[nullableProp]).isEqualTo("changed")
  }

  @Test
  fun `acquire of null is empty and copy of null is null`() {
    assertThat(TreePropContainer.acquire(null).isEmpty()).isTrue
    assertThat(TreePropContainer.copy(null)).isNull()
  }

  @Test
  fun `acquire copies a source backed by either map`() {
    for (sourceUsesScatterMap in BOTH) {
      val copy = TreePropContainer.acquire(filled(sourceUsesScatterMap))

      assertThat(copy).isEqualTo(filled(useScatterMap))
    }
  }

  @Test
  fun `putAll adds and overwrites from a container backed by either map`() {
    for (sourceUsesScatterMap in BOTH) {
      val destination = TreePropContainer()
      destination.put(intProp, 50)
      destination.put(Int::class.java, 3)

      destination.putAll(filled(sourceUsesScatterMap))

      assertThat(destination[intProp]).isEqualTo(1)
      assertThat(destination[nullableProp]).isNull()
      assertThat(destination[String::class.java]).isEqualTo("a")
      assertThat(destination[Int::class.java]).isEqualTo(3)
    }
  }

  @Test
  fun `containers with the same tree props are equal and hash alike whichever map backs them`() {
    val container = filled(useScatterMap)

    for (otherUsesScatterMap in BOTH) {
      val other = filled(otherUsesScatterMap)

      assertThat(container).isEqualTo(other)
      assertThat(other).isEqualTo(container)
      assertThat(container.hashCode()).isEqualTo(other.hashCode())
    }
  }

  @Test
  fun `containers that differ in a value, a key or a null are not equal`() {
    val container = filled(useScatterMap)

    for (otherUsesScatterMap in BOTH) {
      val differentValue = filled(otherUsesScatterMap).apply { put(intProp, 2) }
      val extraKey = filled(otherUsesScatterMap).apply { put(Int::class.java, 3) }
      val missingNull =
          containerOf(otherUsesScatterMap) {
            put(intProp, 1)
            put(String::class.java, "a")
            put(Int::class.java, null)
          }

      assertThat(container).isNotEqualTo(differentValue)
      assertThat(container).isNotEqualTo(extraKey)
      assertThat(extraKey).isNotEqualTo(container)
      // Same size, but a null value under a different key is not the null set for nullableProp.
      assertThat(container).isNotEqualTo(missingNull)
      assertThat(missingNull).isNotEqualTo(container)
    }
  }

  private fun filled(usesScatterMap: Boolean): TreePropContainer =
      containerOf(usesScatterMap) {
        put(intProp, 1)
        put(nullableProp, null)
        put(String::class.java, "a")
      }

  private fun containerOf(
      usesScatterMap: Boolean,
      fill: TreePropContainer.() -> Unit,
  ): TreePropContainer {
    ComponentsConfiguration.useScatterMapInTreePropContainer = usesScatterMap
    try {
      return TreePropContainer().apply(fill)
    } finally {
      ComponentsConfiguration.useScatterMapInTreePropContainer = useScatterMap
    }
  }

  companion object {
    private val BOTH = listOf(false, true)

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "useScatterMap={0}")
    fun settings(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
