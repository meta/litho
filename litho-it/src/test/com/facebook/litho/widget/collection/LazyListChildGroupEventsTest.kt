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

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class LazyListChildGroupEventsTest : LazyListChildGroupEventsTestCases(enableNewCollection = false)

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class LazyListChildGroupEventsNewCollectionTest :
    LazyListChildGroupEventsTestCases(enableNewCollection = true)

abstract class LazyListChildGroupEventsTestCases(enableNewCollection: Boolean) {

  @get:Rule
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(enableNewCollection = enableNewCollection),
  )

  private val controller = LazyCollectionController()
  private val nearEndCalls = AtomicInteger()

  private inner class Feed(private val feedRows: Int) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(lazyCollectionController = controller) {
          childGroup(id = "empty") { onNearEnd { error("an empty group never fires onNearEnd") } }
          childGroup(id = "feed") {
            onNearEnd { nearEndCalls.incrementAndGet() }
            children(items = 0 until feedRows, id = { it }) { Text("$it") }
          }
          children(items = 0 until 10, id = { "after:$it" }) { Text("after $it") }
        }
  }

  @Test
  fun onNearEnd_firesOnce_whenGroupsLastRowComesIntoView() {
    val view = lithoTestRule.render(widthPx = 100, heightPx = 100) { Feed(feedRows = 10) }
    lithoTestRule.idle()
    assertThat(nearEndCalls.get()).isEqualTo(0)

    view.scroll(Feed(feedRows = 10)) { controller.scrollToIdInGroup("feed", 9) }
    view.scroll(Feed(feedRows = 10)) { controller.scrollToId("after:5") }

    assertThat(nearEndCalls.get()).isEqualTo(1)
  }

  @Test
  fun onNearEnd_doesNotFire_whenTheListJumpsPastTheGroup() {
    val view = lithoTestRule.render(widthPx = 100, heightPx = 100) { Feed(feedRows = 10) }
    lithoTestRule.idle()

    view.scroll(Feed(feedRows = 10)) { controller.scrollToId("after:5") }

    assertThat(nearEndCalls.get()).isEqualTo(0)
  }

  @Test
  fun onNearEnd_firesAgain_afterScrollingBackAway() {
    val view = lithoTestRule.render(widthPx = 100, heightPx = 100) { Feed(feedRows = 10) }
    lithoTestRule.idle()

    view.scroll(Feed(feedRows = 10)) { controller.scrollToIdInGroup("feed", 9) }
    view.scroll(Feed(feedRows = 10)) { controller.scrollToIndex(0) }
    view.scroll(Feed(feedRows = 10)) { controller.scrollToIdInGroup("feed", 9) }

    assertThat(nearEndCalls.get()).isEqualTo(2)
  }

  @Test
  fun onNearEnd_firesAgain_whenGroupsNewLastRowComesIntoView() {
    val view = lithoTestRule.render(widthPx = 100, heightPx = 100) { Feed(feedRows = 10) }
    lithoTestRule.idle()

    view.scroll(Feed(feedRows = 10)) { controller.scrollToIdInGroup("feed", 9) }
    view.setRoot(Feed(feedRows = 20))
    lithoTestRule.idle()
    view.scroll(Feed(feedRows = 20)) { controller.scrollToIdInGroup("feed", 19) }

    assertThat(nearEndCalls.get()).isEqualTo(2)
  }

  private fun TestLithoView.scroll(root: Component, scroll: () -> Unit) {
    lithoTestRule.act(this) { scroll() }
    // Applies the RecyclerView's pending scroll.
    setRoot(root)
    lithoTestRule.idle()
  }
}
