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

package com.facebook.litho.widget.collection

import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.LEGACY)
class CollectionViewportCallbackContractTest {
  @Rule
  @JvmField
  val lithoTestRule =
      LithoTestRule(ComponentsConfiguration.defaultInstance.copy(enableNewCollection = true))

  private val events = mutableListOf<String>()
  private var viewport = emptyList<Int>()

  @Test
  fun `near end runs before viewport and callbacks use the latest committed props`() {
    val view = lithoTestRule.render(widthPx = 100, heightPx = 100) { list("old") }
    lithoTestRule.idle()
    val recycler = recyclerView(view)
    events.clear()

    lithoTestRule.act(view) { recycler.scrollBy(0, 250) }

    assertThat(events.count { it == "near:old" }).isEqualTo(1)
    assertThat(events.zipWithNext().any { it == ("near:old" to "viewport:old") }).isTrue
    assertThat(viewport[1]).isEqualTo(4)
    assertThat(viewport[2]).isEqualTo(5)

    view.setRoot { list("new") }
    lithoTestRule.idle()
    events.clear()
    lithoTestRule.act(view) { recycler.scrollBy(0, -250) }
    lithoTestRule.act(view) { recycler.scrollBy(0, 250) }

    assertThat(events.count { it == "near:new" }).isEqualTo(1)
    assertThat(events.zipWithNext().any { it == ("near:new" to "viewport:new") }).isTrue
    assertThat(events.none { it.endsWith(":old") }).isTrue
    assertThat(viewport[1]).isEqualTo(4)
    assertThat(viewport[2]).isEqualTo(5)
  }

  @Test
  fun `absent near end and viewport callbacks remain optional`() {
    val view =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          list("unused", callbacksEnabled = false)
        }
    lithoTestRule.idle()
    val recycler = recyclerView(view)

    lithoTestRule.act(view) { recycler.scrollBy(0, 250) }
    lithoTestRule.act(view) { recycler.scrollBy(0, -250) }

    assertThat(events).isEmpty()
    assertThat(recycler.adapter?.itemCount).isEqualTo(5)
  }

  private fun list(marker: String, callbacksEnabled: Boolean = true): Component =
      object : KComponent() {
        override fun ComponentScope.render(): Component {
          val onViewportChanged: ((Int, Int, Int, Int, Int) -> Unit)? =
              if (callbacksEnabled) {
                { first, last, total, _, _ ->
                  events += "viewport:$marker"
                  viewport = listOf(first, last, total)
                }
              } else {
                null
              }
          return LazyList(
              style = Style.viewTag("viewport-contract"),
              onNearEnd =
                  if (callbacksEnabled) OnNearCallback { events += "near:$marker" } else null,
              onViewportChanged = onViewportChanged,
          ) {
            children(0..4, id = { it }) { index ->
              Text("item_$index", style = Style.height(50.px))
            }
          }
        }
      }

  private fun recyclerView(view: TestLithoView): RecyclerView = checkNotNull(
      ((view.findViewWithTagOrNull("viewport-contract") as LithoView?)?.getChildAt(0)
              as SectionsRecyclerView?)
          ?.recyclerView,
  )
}
