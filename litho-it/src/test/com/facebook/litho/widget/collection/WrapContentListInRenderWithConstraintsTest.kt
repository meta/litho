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
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/**
 * A wrap-content list inside [RenderWithConstraints] whose items arrive after its first measure,
 * like the m4a inbox subtab pills. It renders into a real window so a remeasure loop after mount,
 * which is posted to the view, is seen as well.
 */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class WrapContentListInRenderWithConstraintsTest {

  @get:Rule
  val rule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(enableNewCollection = false),
      attachToRealWindow = true,
  )

  private class Controls {
    var setItemCount: ((Int) -> Unit)? = null
    var layoutRuns = 0
    var lastMaxWidth = 0
    var bound = 0
  }

  private class Pills(private val controls: Controls) : KComponent() {
    override fun ComponentScope.render(): Component = Row {
      child(
          RenderWithConstraints { constraints ->
            controls.layoutRuns++
            controls.lastMaxWidth = constraints.maxWidth
            PillsList(controls, onTabClick = {})
          },
      )
    }
  }

  // Like the pills, every layout pass builds the list with a fresh lambda prop, so it re-renders.
  private class PillsList(private val controls: Controls, private val onTabClick: () -> Unit) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      val itemCount = useState { 0 }
      controls.setItemCount = { itemCount.update(it) }
      return LazyList(
          orientation = RecyclerView.HORIZONTAL,
          crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
          mainAxisWrapContent = true,
          style = Style.height(PILL_HEIGHT_PX.px),
          onDataBound = { controls.bound++ },
      ) {
        children(items = (0 until itemCount.value).toList(), id = { it }) { index ->
          Text("tab $index", style = Style.onClick { onTabClick() })
        }
      }
    }
  }

  @Test
  fun `wrap-content list converges after items arrive from zero`() {
    val controls = Controls()
    rule.render(widthPx = exactly(1080), heightPx = exactly(1920)) { Pills(controls) }
    rule.idle()
    assertThat(controls.lastMaxWidth).isEqualTo(0)
    val layoutRunsBefore = controls.layoutRuns
    val boundBefore = controls.bound

    checkNotNull(controls.setItemCount).invoke(3)
    rule.idle()

    assertThat(controls.lastMaxWidth).isPositive()
    assertThat(controls.layoutRuns - layoutRunsBefore).isLessThanOrEqualTo(MAX_LAYOUT_RUNS)
    assertThat(controls.bound - boundBefore).isLessThanOrEqualTo(MAX_CHANGE_SETS)
  }

  private companion object {
    const val PILL_HEIGHT_PX = 42
    const val MAX_LAYOUT_RUNS = 6
    const val MAX_CHANGE_SETS = 3
  }
}
