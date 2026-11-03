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
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class NeverMountedCollectionDataRenderedTest {

  @get:Rule
  val boundedRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableNewCollection = false,
          boundDataRenderedCallbacksBeforeMount = true,
      ),
  )

  @get:Rule
  val unboundedRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableNewCollection = false,
          boundDataRenderedCallbacksBeforeMount = false,
      ),
  )

  private class Controls {
    var bump: (() -> Unit)? = null
    var rerender: (() -> Unit)? = null
    var reveal: (() -> Unit)? = null
    var bound = 0
    val renderedVersions = mutableListOf<Int>()
    var renderedDataChanges = 0
  }

  private class Tabs(private val initialHeightPx: Int, private val controls: Controls) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      val version = useState { 0 }
      val renderCount = useState { 0 }
      val height = useState { initialHeightPx }
      controls.bump = { version.update { it + 1 } }
      controls.rerender = { renderCount.update { it + 1 } }
      controls.reveal = { height.update(VISIBLE_HEIGHT_PX) }
      // Read so that bumping it re-renders this component under Litho read tracking.
      renderCount.value
      val renderedVersion = version.value
      return Column(style = Style.height(height.value.px)) {
        child(
            LazyList(
                orientation = RecyclerView.HORIZONTAL,
                onDataBound = { controls.bound++ },
                onDataRendered = { isDataChanged, _, _, _, _ ->
                  controls.renderedVersions.add(renderedVersion)
                  if (isDataChanged) controls.renderedDataChanges++
                },
            ) {
              children(items = listOf(0, 1, 2), id = { it }) { index ->
                Text("tab $index v${version.value}")
              }
            },
        )
      }
    }
  }

  private fun render(rule: LithoTestRule, initialHeightPx: Int): Controls {
    val controls = Controls()
    rule.render(widthPx = exactly(1080), heightPx = exactly(1920)) {
      Tabs(initialHeightPx, controls)
    }
    rule.idle()
    repeat(UPDATES) {
      checkNotNull(controls.bump).invoke()
      rule.idle()
    }
    return controls
  }

  private fun revealAndUpdate(rule: LithoTestRule, controls: Controls) {
    checkNotNull(controls.reveal).invoke()
    rule.idle()
    checkNotNull(controls.bump).invoke()
    rule.idle()
  }

  // Robolectric does not draw, so a no-op re-render after the reveal is what dispatches the queue.
  private fun revealAndRerender(rule: LithoTestRule, controls: Controls) {
    checkNotNull(controls.reveal).invoke()
    rule.idle()
    checkNotNull(controls.rerender).invoke()
    rule.idle()
  }

  private fun renderThenRerenderWithSameItems(rule: LithoTestRule): Controls {
    val controls = Controls()
    rule.render(widthPx = exactly(1080), heightPx = exactly(1920)) { Tabs(0, controls) }
    rule.idle()
    repeat(NO_OP_RERENDERS) {
      checkNotNull(controls.rerender).invoke()
      rule.idle()
    }
    return controls
  }

  @Test
  fun `never mounted collection without bound dispatches every queued callback on first mount`() {
    val controls = render(unboundedRule, initialHeightPx = 0)
    assertThat(controls.bound).isEqualTo(UPDATES + 1)
    assertThat(controls.renderedVersions).isEmpty()

    revealAndUpdate(unboundedRule, controls)

    assertThat(controls.renderedVersions.size).isGreaterThanOrEqualTo(UPDATES + 1)
    assertThat(controls.renderedVersions.last()).isEqualTo(UPDATES + 1)
  }

  @Test
  fun `never mounted collection with bound only keeps the most recent callbacks until mount`() {
    val controls = render(boundedRule, initialHeightPx = 0)
    assertThat(controls.bound).isEqualTo(UPDATES + 1)
    assertThat(controls.renderedVersions).isEmpty()

    revealAndUpdate(boundedRule, controls)

    assertThat(controls.renderedVersions.size).isLessThanOrEqualTo(MAX_PENDING + 1)
    assertThat(controls.renderedVersions.last()).isEqualTo(UPDATES + 1)
    assertThat(controls.renderedVersions.min()).isGreaterThanOrEqualTo(UPDATES - MAX_PENDING)
  }

  @Test
  fun `never mounted collection without bound delivers its data change after no-op re-renders`() {
    val controls = renderThenRerenderWithSameItems(unboundedRule)

    revealAndRerender(unboundedRule, controls)

    assertThat(controls.renderedDataChanges).isEqualTo(1)
  }

  @Test
  fun `never mounted collection with bound keeps its data change through no-op re-renders`() {
    val controls = renderThenRerenderWithSameItems(boundedRule)

    revealAndRerender(boundedRule, controls)

    assertThat(controls.renderedVersions.size).isLessThanOrEqualTo(MAX_PENDING + 1)
    assertThat(controls.renderedDataChanges).isEqualTo(1)
  }

  @Test
  fun `mounted collection dispatches every callback with the bound on`() {
    val controls = render(boundedRule, initialHeightPx = VISIBLE_HEIGHT_PX)
    assertThat(controls.bound).isEqualTo(UPDATES + 1)
    assertThat(controls.renderedVersions).hasSize(UPDATES + 1)
  }

  private companion object {
    const val UPDATES = 50
    const val NO_OP_RERENDERS = 25
    const val MAX_PENDING = 20
    const val VISIBLE_HEIGHT_PX = 100
  }
}
