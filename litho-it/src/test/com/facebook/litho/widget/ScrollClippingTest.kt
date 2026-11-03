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
import android.view.View
import android.view.ViewGroup
import com.facebook.litho.ComponentHost
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scroll container itself clips its children to the viewport, but the component hosts inside
 * the nested scroll content do not clip: `enableChildClipping` defaults to false and no scroll call
 * site opts back in.
 */
@RunWith(LithoTestRunner::class)
class ScrollClippingTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun `vertical scroll clips viewport but inner hosts do not clip`() {
    val lithoView =
        lithoTestRule
            .render(widthPx = 200, heightPx = 400) {
              VerticalScroll(
                  child =
                      Column {
                        child(
                            Column(
                                style = Style.height(100.px).backgroundColor(Color.GRAY),
                            ) {
                              child(
                                  SolidColor(
                                      color = Color.RED,
                                      style = Style.width(200.px).height(140.px),
                                  ),
                              )
                            },
                        )
                        child(
                            SolidColor(
                                color = Color.BLUE,
                                style = Style.width(200.px).height(100.px),
                            ),
                        )
                      },
                  style = Style.height(150.px),
              )
            }
            .lithoView

    val scrollView = findDescendant(lithoView, LithoScrollView::class.java)
    assertThat(scrollView).isNotNull()
    assertThat(scrollView!!.clipChildren).isTrue()

    val hosts = findAll(lithoView, ComponentHost::class.java)
    assertThat(hosts).isNotEmpty()
    hosts.forEach { assertThat(it.clipChildren).isFalse() }
  }

  @Test
  fun `horizontal scroll clips viewport but inner hosts do not clip`() {
    val lithoView =
        lithoTestRule
            .render(widthPx = 400, heightPx = 200) {
              HorizontalScroll(
                  child =
                      Row {
                        child(
                            Row(
                                style = Style.width(100.px).backgroundColor(Color.GRAY),
                            ) {
                              child(
                                  SolidColor(
                                      color = Color.RED,
                                      style = Style.width(140.px).height(200.px),
                                  ),
                              )
                            },
                        )
                        child(
                            SolidColor(
                                color = Color.BLUE,
                                style = Style.width(100.px).height(200.px),
                            ),
                        )
                      },
                  style = Style.width(150.px),
              )
            }
            .lithoView

    val scrollView = findDescendant(lithoView, HorizontalScrollLithoView::class.java)
    assertThat(scrollView).isNotNull()
    assertThat(scrollView!!.clipChildren).isTrue()

    val hosts = findAll(lithoView, ComponentHost::class.java)
    assertThat(hosts).isNotEmpty()
    hosts.forEach { assertThat(it.clipChildren).isFalse() }
  }

  private fun <T : View> findDescendant(root: View, clazz: Class<T>): T? {
    if (clazz.isInstance(root)) return clazz.cast(root)
    if (root is ViewGroup) {
      for (i in 0 until root.childCount) {
        findDescendant(root.getChildAt(i), clazz)?.let {
          return it
        }
      }
    }
    return null
  }

  private fun <T : View> findAll(root: View, clazz: Class<T>): List<T> {
    val found = mutableListOf<T>()
    if (clazz.isInstance(root)) found.add(clazz.cast(root))
    if (root is ViewGroup) {
      for (i in 0 until root.childCount) {
        found.addAll(findAll(root.getChildAt(i), clazz))
      }
    }
    return found
  }
}
