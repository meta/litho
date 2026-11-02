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

package com.facebook.litho.animation

import android.graphics.Color
import android.os.Looper.getMainLooper
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.key
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * A structural sibling (banner) inserted ABOVE a reflow card shifts the card's Y position. That
 * shift must ANIMATE via an x/y transform (non-zero `translationY` mid-flight), regardless of
 * whether the card's own reflow SIZE animation happens to be running or changing this commit: a
 * node's own size change never moves its own position, so it must not suppress its own position
 * transform.
 *
 * Bug (from the "Reflow" demo video): the card's structural position shift snapped (`translationY
 * == 0`) whenever a reflow size change was in flight, because x/y suppression keyed off tree-wide
 * reflow activity instead of whether a DIFFERENT reflow node was driving this node's position via
 * relayout.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class StyleReflowPositionSnapTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var expanded: State<Boolean>? = null
  private var bannerOn: State<Boolean>? = null

  private fun cardTranslationY(v: TestLithoView): Float =
      checkNotNull(v.findViewWithTagOrNull(CARD_TAG)).translationY

  private fun cardTop(v: TestLithoView): Int = checkNotNull(v.findViewWithTagOrNull(CARD_TAG)).top

  /**
   * Control: banner toggled AFTER the reflow size settles — no reflow size change is in flight, so
   * the card's Y shift animates. Proves the oracle detects animation.
   */
  @Test
  fun bannerInserted_afterReflowSettled_cardPositionAnimates() {
    val testView = lithoTestRule.render { DemoComponent() }
    lithoTestRule.idle()
    testView.measure().layout()

    // Expand the card and let its reflow SIZE animation fully settle.
    checkNotNull(expanded).updateSync { true }
    lithoTestRule.idle()
    testView.measure().layout()
    animationRule.advanceBy(400.ms)
    lithoTestRule.idle()
    testView.measure().layout()
    val topBeforeBanner = cardTop(testView)

    // Insert the banner above (structural). Card layout Y jumps down; the transform should hold it
    // at the old position and animate — non-zero translationY.
    checkNotNull(bannerOn).updateSync { true }
    lithoTestRule.idle()
    testView.measure().layout()

    assertThat(cardTop(testView))
        .describedAs("card layout top should move down by the banner height")
        .isGreaterThan(topBeforeBanner)
    assertThat(cardTranslationY(testView))
        .describedAs("card Y shift should be animating (non-zero translation), not snapped")
        .isNotEqualTo(0f)
  }

  /**
   * Banner toggled WHILE the card's reflow size animation is running. The card is the only reflow
   * node, so nothing drives its position via relayout — the shift must animate, not snap.
   */
  @Test
  fun bannerInserted_whileReflowSizeRunning_cardPositionAnimates() {
    val testView = lithoTestRule.render { DemoComponent() }
    lithoTestRule.idle()
    testView.measure().layout()

    // Expand the card; advance only partway so the reflow SIZE animation is still running.
    checkNotNull(expanded).updateSync { true }
    lithoTestRule.idle()
    testView.measure().layout()
    animationRule.advanceBy(48.ms)
    testView.measure().layout()
    val topBeforeBanner = cardTop(testView)

    // Insert the banner above WHILE the size animation runs.
    checkNotNull(bannerOn).updateSync { true }
    lithoTestRule.idle()
    testView.measure().layout()

    assertThat(cardTop(testView))
        .describedAs("card layout top should move down by the banner height")
        .isGreaterThan(topBeforeBanner)
    assertThat(cardTranslationY(testView))
        .describedAs(
            "card Y shift must animate even while the reflow SIZE animation runs — size and " +
                "position are independent; suppressing the transform here makes it snap",
        )
        .isNotEqualTo(0f)
  }

  /**
   * The demo's real path: the banner shows only when `expanded && bannerOn`, so expanding with the
   * banner already enabled changes the card's SIZE and inserts the banner (shifting the card's
   * position) in ONE commit. The position shift must still animate.
   */
  @Test
  fun expandWithBannerEnabled_coalescedSizeAndPositionChange_cardPositionAnimates() {
    val testView = lithoTestRule.render { DemoComponent() }
    lithoTestRule.idle()
    testView.measure().layout()

    // Enable the banner while collapsed — it stays hidden (needs expanded && bannerOn), card at
    // 100.
    checkNotNull(bannerOn).updateSync { true }
    lithoTestRule.idle()
    testView.measure().layout()
    val topBeforeExpand = cardTop(testView)

    // Expand: one commit resizes the card AND inserts the banner above it.
    checkNotNull(expanded).updateSync { true }
    lithoTestRule.idle()
    testView.measure().layout()

    assertThat(cardTop(testView))
        .describedAs("card layout top should move down by the banner height")
        .isGreaterThan(topBeforeExpand)
    assertThat(cardTranslationY(testView))
        .describedAs(
            "the banner-driven position shift must animate even though the card is resizing in the " +
                "same commit — a node's own size change must not suppress its own position transform",
        )
        .isNotEqualTo(0f)
  }

  private inner class DemoComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val exp = useState { false }.also { expanded = it }
      val banner = useState { false }.also { bannerOn = it }
      return Column(style = Style.width(200.px)) {
        if (exp.value && banner.value) {
          child(
              key("banner") {
                Row(style = Style.width(200.px).height(80.px).backgroundColor(Color.YELLOW))
              },
          )
        }
        child(
            key("card") {
              Row(
                  style =
                      Style.width(200.px)
                          .height(if (exp.value) 250.px else 100.px)
                          .backgroundColor(Color.RED)
                          .transition {
                            bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                          }
                          .viewTag(CARD_TAG),
              )
            },
        )
        child(key("sibling") { Row(style = Style.width(200.px).height(60.px)) })
      }
    }
  }

  companion object {
    private const val CARD_TAG = "reflow_pos_card"
  }
}
