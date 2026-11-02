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

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/**
 * An item's effects can run while the collection is binding it. They must still be able to resolve
 * the item's adapter position, e.g. to smooth scroll the item into view.
 */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChildAdapterPositionFromEffectDuringRebindTest {

  @get:Rule val legacy = rule(newCollection = false)
  @get:Rule val new = rule(newCollection = true)

  @Test
  fun `legacy - effect run by a rebind resolves the item's adapter position`() {
    assertEffectsResolveCardPosition(legacy)
  }

  @Test
  fun `new - effect run by a rebind resolves the item's adapter position`() {
    assertEffectsResolveCardPosition(new)
  }

  @Test
  fun `legacy - effect on a new item's first bind gets no position instead of crashing`() {
    assertFirstBindLookupDoesNotCrash(legacy)
  }

  @Test
  fun `new - effect on a new item's first bind gets no position instead of crashing`() {
    assertFirstBindLookupDoesNotCrash(new)
  }

  private fun assertEffectsResolveCardPosition(rule: LithoTestRule) {
    val positions = mutableListOf<Int>()
    val view =
        rule.render(widthPx = 1080, heightPx = 1920) {
          Sheet(listHeight = 800, effectKey = 0, positions = positions)
        }
    idle(rule)
    positions.clear()

    // The list is resized while the card's effect key changes, so the card is rebound and runs its
    // effect in the same pass.
    for (step in 1..4) {
      view.setRoot(Sheet(listHeight = 800 + step * 150, effectKey = step, positions = positions))
      idle(rule)
    }

    assertThat(positions)
        .containsExactly(CARD_POSITION, CARD_POSITION, CARD_POSITION, CARD_POSITION)
  }

  // A new item view has no layout params until the collection gives it some, and RecyclerView
  // dereferences them to answer getChildAdapterPosition().
  private fun assertFirstBindLookupDoesNotCrash(rule: LithoTestRule) {
    val positions = mutableListOf<Int>()
    rule.render(widthPx = 1080, heightPx = 1920) { FirstBindList(positions) }
    idle(rule)

    assertThat(positions.filter { it != CARD_NOT_FOUND })
        .describedAs("at least one effect looked itself up")
        .isNotEmpty()
    assertThat(positions).doesNotContain(LOOKUP_THREW)
  }

  private fun idle(rule: LithoTestRule) {
    repeat(3) {
      ShadowLooper.idleMainLooper()
      rule.idle()
    }
  }

  private class Sheet(
      private val listHeight: Int,
      private val effectKey: Int,
      private val positions: MutableList<Int>,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val controller = useLazyCollectionController()
      return LazyList(
          lazyCollectionController = controller,
          style = Style.width(1080.px).height(listHeight.px),
      ) {
        child(id = "header", component = Column(style = Style.height(120.px)))
        child(id = "comment_0", component = Column(style = Style.height(180.px)))
        child(id = "comment_1", component = Column(style = Style.height(90.px)))
        child(id = "card", component = Card(controller, effectKey, positions))
      }
    }
  }

  private class FirstBindList(private val positions: MutableList<Int>) : KComponent() {
    override fun ComponentScope.render(): Component {
      val controller = useLazyCollectionController()
      return LazyList(
          lazyCollectionController = controller,
          style = Style.width(1080.px).height(1000.px),
      ) {
        child(id = "a", component = SelfLocatingItem(controller, positions))
        child(id = "b", component = SelfLocatingItem(controller, positions))
      }
    }
  }

  private class SelfLocatingItem(
      private val controller: LazyCollectionController,
      private val positions: MutableList<Int>,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val componentContext = context
      useEffect(Unit) {
        positions.add(adapterPositionOfHost(controller.recyclerView, componentContext.mountedView))
        onCleanup {}
      }
      return Column(style = Style.height(300.px))
    }
  }

  private class Card(
      private val controller: LazyCollectionController,
      private val effectKey: Int,
      private val positions: MutableList<Int>,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(effectKey) {
        positions.add(adapterPositionOfCard(controller.recyclerView))
        onCleanup {}
      }
      return Column(style = Style.viewTag(CARD_TAG).height(1400.px))
    }
  }

  companion object {
    private const val CARD_TAG = "rebind_position_card"
    private const val CARD_POSITION = 3
    private const val CARD_NOT_FOUND = Int.MIN_VALUE
    private const val LOOKUP_THREW = Int.MIN_VALUE + 1

    private fun adapterPositionOfHost(recyclerView: RecyclerView?, host: View?): Int {
      if (recyclerView == null || host == null) {
        return CARD_NOT_FOUND
      }
      return try {
        recyclerView.getChildAdapterPosition(host)
      } catch (e: NullPointerException) {
        LOOKUP_THREW
      }
    }

    private fun adapterPositionOfCard(recyclerView: RecyclerView?): Int {
      recyclerView ?: return CARD_NOT_FOUND
      var view: View = recyclerView.findViewWithTag(CARD_TAG) ?: return CARD_NOT_FOUND
      while (view.parent !== recyclerView) {
        view = view.parent as? View ?: return CARD_NOT_FOUND
      }
      return recyclerView.getChildAdapterPosition(view)
    }

    private fun rule(newCollection: Boolean): LithoTestRule = LithoTestRule(
        componentsConfiguration =
            ComponentsConfiguration.defaultInstance.copy(enableNewCollection = newCollection),
        attachToRealWindow = true,
    )
  }
}
