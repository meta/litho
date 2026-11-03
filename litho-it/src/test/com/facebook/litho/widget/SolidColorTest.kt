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

package com.facebook.litho.widget

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.facebook.litho.MatrixDrawable
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.width
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [ComponentsConfiguration.cacheSolidColorDrawable]. Re-renders that keep the color change
 * only the margin, so [SolidColor] renders again with the same color.
 */
@RunWith(LithoTestRunner::class)
class SolidColorTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private var previousCacheSolidColorDrawable: Boolean = false

  private val size = Style.width(10.px).height(10.px)
  private val sizeWithMargin = size + Style.margin(all = 1.px)

  @Before
  fun setUp() {
    previousCacheSolidColorDrawable = ComponentsConfiguration.cacheSolidColorDrawable
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.cacheSolidColorDrawable = previousCacheSolidColorDrawable
  }

  @Test
  fun `cached drawable is reused when re-rendering with the same color`() {
    ComponentsConfiguration.cacheSolidColorDrawable = true
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          SolidColor(color = Color.RED, style = size)
        }
    val first = mountedColorDrawable(testLithoView)

    testLithoView.setRoot(SolidColor(color = Color.RED, style = sizeWithMargin))

    assertThat(mountedColorDrawable(testLithoView)).isSameAs(first)
  }

  @Test
  fun `cached drawable is replaced when the color changes`() {
    ComponentsConfiguration.cacheSolidColorDrawable = true
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          SolidColor(color = Color.RED, style = size)
        }
    val first = mountedColorDrawable(testLithoView)

    testLithoView.setRoot(SolidColor(color = Color.BLUE, style = size))

    val second = mountedColorDrawable(testLithoView)
    assertThat(second).isNotSameAs(first)
    assertThat(second.color).isEqualTo(Color.BLUE)
  }

  @Test
  fun `cached drawable is replaced when the alpha changes`() {
    ComponentsConfiguration.cacheSolidColorDrawable = true
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          SolidColor(color = Color.RED, alpha = 0.5f, style = size)
        }
    val first = mountedColorDrawable(testLithoView)

    testLithoView.setRoot(SolidColor(color = Color.RED, alpha = 1f, style = size))

    val second = mountedColorDrawable(testLithoView)
    assertThat(second).isNotSameAs(first)
    assertThat(second.color).isEqualTo(Color.RED)
  }

  @Test
  fun `uncached drawable is recreated when re-rendering with the same color`() {
    ComponentsConfiguration.cacheSolidColorDrawable = false
    val testLithoView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          SolidColor(color = Color.RED, style = size)
        }
    val first = mountedColorDrawable(testLithoView)

    testLithoView.setRoot(SolidColor(color = Color.RED, style = sizeWithMargin))

    val second = mountedColorDrawable(testLithoView)
    assertThat(second).isNotSameAs(first)
    assertThat(second.color).isEqualTo(Color.RED)
  }

  @Suppress("UNCHECKED_CAST")
  private fun mountedColorDrawable(testLithoView: TestLithoView): ColorDrawable {
    val drawable = testLithoView.lithoView.drawables.single() as MatrixDrawable<ColorDrawable>
    return checkNotNull(drawable.mountedDrawable)
  }
}
