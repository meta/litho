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

import android.graphics.Color
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.aspectRatio
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.dp
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LazyListTest {

  @get:Rule val lithoTestRule = LithoTestRule()

  @Test
  fun `inner padding is taken into account when set on a LazyList`() {
    val tag = "lazy-list-tag"
    val height = 1000
    val width = 500
    val outerPadding = 2
    val topPadding = 16
    val bottomPadding = 4
    val lateralPadding = 10

    val lithoView = lithoTestRule.render {
      TestGridComponent(
          tag = tag,
          outerPadding = outerPadding.px,
          lazyListStartPadding = lateralPadding.px,
          lazyListEndPadding = lateralPadding.px,
          lazyListTopPadding = topPadding.px,
          lazyListBottomPadding = bottomPadding.px,
          width = width.px,
          height = height.px,
      )
    }

    // Verify measured sizes
    val collectionComponent = lithoView.findCollectionComponent()
    val measuredWidth = collectionComponent?.measuredWidth ?: 0
    val measuredHeight = collectionComponent?.measuredHeight ?: 0
    Assertions.assertThat(measuredWidth)
        .isEqualTo(width - (outerPadding * 2) - (lateralPadding * 2))
    Assertions.assertThat(measuredHeight)
        .isEqualTo(height - (outerPadding * 2) - topPadding - bottomPadding)

    // Verify that padding is not applied in the RV wrapper
    val view = lithoView.findViewWithTag(tag)
    Assertions.assertThat(view).isInstanceOf(SectionsRecyclerView::class.java)
    Assertions.assertThat(view.paddingLeft).isEqualTo(0)
    Assertions.assertThat(view.paddingEnd).isEqualTo(0)
    Assertions.assertThat(view.paddingTop).isEqualTo(0)
    Assertions.assertThat(view.paddingBottom).isEqualTo(0)

    // Verify that padding is applied inner RV
    val innerRv = (view as SectionsRecyclerView).recyclerView
    Assertions.assertThat(innerRv).isNotNull
    Assertions.assertThat(innerRv.paddingLeft).isEqualTo(lateralPadding)
    Assertions.assertThat(innerRv.paddingEnd).isEqualTo(lateralPadding)
    Assertions.assertThat(innerRv.paddingTop).isEqualTo(topPadding)
    Assertions.assertThat(innerRv.paddingBottom).isEqualTo(bottomPadding)
  }

  class TestGridComponent(
      private val tag: String,
      private val outerPadding: Dimen,
      private val lazyListStartPadding: Dimen,
      private val lazyListEndPadding: Dimen,
      private val lazyListTopPadding: Dimen,
      private val lazyListBottomPadding: Dimen,
      private val height: Dimen,
      private val width: Dimen,
  ) : KComponent() {

    override fun ComponentScope.render(): Component {
      return Column(style = Style.height(height).width(width).padding(all = outerPadding)) {
        child(
            LazyGrid(
                columns = 2,
                topPadding = lazyListTopPadding,
                bottomPadding = lazyListBottomPadding,
                startPadding = lazyListStartPadding,
                endPadding = lazyListEndPadding,
                clipToPadding = false,
                style = Style.viewTag(tag),
            ) {
              children(items = (0..31), id = { it }) { item(Style.padding(all = 8.dp)) }
            },
        )
      }
    }

    private fun ResourcesScope.item(style: Style): Component =
        Column(style = style) {
          child(Column(style = Style.backgroundColor(Color.RED).height(10.dp).aspectRatio(1f)))
        }
  }
}
