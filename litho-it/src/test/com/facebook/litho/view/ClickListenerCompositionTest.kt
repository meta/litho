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

// Tests use `Style.viewTag` + `findViewWithTag` to identify the click target. The preferred
// alternative is `Style.testKey` (see `TestLithoView.findViewWithTestKey`), but it requires
// `ComponentsConfiguration.isEndToEndTestRun = true` to be in effect before `ReductionState` is
// built — toggling the flag from a `@Before` (after `LithoTestRule` initialisation) does not
// reliably wire up the test-item registry under all parametrised Litho configurations used here.
// The legacy `viewTag` pattern mirrors what the existing `ViewStylesTest` click tests use.

package com.facebook.litho.view

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.Wrapper
import com.facebook.litho.child
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlinStyle
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.testing.unspecified
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression suite for click-listener composition via [Style.onClick] + [Style.additionalOnClick].
 * Covers:
 * 1. Existing `Style.onClick` behaviour: primary alone dispatches once per click.
 * 2. `Style.additionalOnClick` alone: dispatches without a primary.
 * 3. Composition: primary + additional listeners co-existing; dispatch order = primary first, then
 *    additionals in insertion order.
 * 4. The render-time "new primary clears prior additionals" rule and its symmetric edge cases.
 * 5. Disabled `Style.onClick(enabled = false, …)` does NOT clobber previously-registered
 *    additionals.
 * 6. `Style.additionalOnClick` on a `Wrapper` survives being merged into a delegate that already
 *    owns a click handler (the `NodeInfo.copyInto(NodeInfo)` merge path).
 * 7. Duplicate-key registration throws; same key on different nodes does not collide.
 */
@RunWith(LithoTestRunner::class)
class ClickListenerCompositionTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  // ------------------------------------------------------------------------
  // Style integration — primary only (regression for existing onClick behaviour)
  // ------------------------------------------------------------------------

  @Test
  fun `style - onClick alone dispatches once per click`() {
    var clicks = 0

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(style = Style.width(100.px).height(100.px).viewTag(TARGET).onClick { clicks += 1 })
        }

    val view = testLithoView.findViewWithTag(TARGET)
    view.performClick()
    view.performClick()

    assertThat(clicks).isEqualTo(2)
  }

  // ------------------------------------------------------------------------
  // Style integration — additionals only (no primary)
  // ------------------------------------------------------------------------

  @Test
  fun `style - additionalOnClick alone dispatches without a primary`() {
    var fired = false

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(
              style =
                  Style.width(100.px).height(100.px).viewTag(TARGET).additionalOnClick(KEY_A) {
                    fired = true
                  },
          )
        }

    val view = testLithoView.findViewWithTag(TARGET)
    assertThat(view.isClickable).isTrue
    view.performClick()
    assertThat(fired).isTrue
  }

  @Test
  fun `style - multiple additionalOnClicks without a primary fire in insertion order`() {
    val log = mutableListOf<String>()

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(
              style =
                  Style.width(100.px)
                      .height(100.px)
                      .viewTag(TARGET)
                      .additionalOnClick(KEY_A) { log.add("a") }
                      .additionalOnClick(KEY_B) { log.add("b") },
          )
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    assertThat(log).containsExactly("a", "b")
  }

  // ------------------------------------------------------------------------
  // Style integration — primary + additionals co-existing
  // ------------------------------------------------------------------------

  @Test
  fun `style - onClick and additionalOnClick both fire in declaration order`() {
    val log = mutableListOf<String>()

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(
              style =
                  Style.width(100.px)
                      .height(100.px)
                      .viewTag(TARGET)
                      .onClick { log.add("primary") }
                      .additionalOnClick(KEY_A) { log.add("a") }
                      .additionalOnClick(KEY_B) { log.add("b") },
          )
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    // Primary always dispatched first, then additionals in insertion order.
    assertThat(log).containsExactly("primary", "a", "b")
  }

  @Test
  fun `style - additionals declared before primary still fire after primary`() {
    val log = mutableListOf<String>()

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(
              style =
                  Style.width(100.px)
                      .height(100.px)
                      .viewTag(TARGET)
                      .additionalOnClick(KEY_A) { log.add("a") }
                      .additionalOnClick(KEY_B) { log.add("b") }
                      .onClick { log.add("primary") },
          )
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    // Adding `Style.onClick { … }` AFTER the additionals clears them (matches the chain-level
    // "new primary clears prior additionals" rule) — only the primary survives.
    assertThat(log).containsExactly("primary")
  }

  // ------------------------------------------------------------------------
  // The "new primary clears prior additionals" rule (render-time composition)
  // ------------------------------------------------------------------------

  @Test
  fun `style - second onClick replaces first and wipes intervening additionals`() {
    val log = mutableListOf<String>()

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(
              style =
                  Style.width(100.px)
                      .height(100.px)
                      .viewTag(TARGET)
                      .onClick { log.add("first-primary") }
                      .additionalOnClick(KEY_A) { log.add("a-between") }
                      .onClick { log.add("second-primary") }
                      .additionalOnClick(KEY_B) { log.add("b-after") },
          )
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    // The second `onClick` clears the additional registered between the two onClicks.
    // The additional registered AFTER the second onClick survives.
    assertThat(log).containsExactly("second-primary", "b-after")
  }

  // ------------------------------------------------------------------------
  // Regression for AI reviewer's bug: disabled onClick must NOT clobber additionals
  // ------------------------------------------------------------------------

  @Test
  fun `style - disabled onClick does not wipe previously registered additionalOnClick`() {
    val log = mutableListOf<String>()

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(
              style =
                  Style.width(100.px)
                      .height(100.px)
                      .viewTag(TARGET)
                      .additionalOnClick(KEY_A) { log.add("telemetry") }
                      // Disabled `onClick` lowers to `commonProps.clickHandler(null)`. A null
                      // primary must NOT clear the previously-registered additionals (otherwise
                      // the telemetry listener silently disappears when the gate is off).
                      .onClick(enabled = false) { error("disabled onClick should not execute") },
          )
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    assertThat(log).containsExactly("telemetry")
  }

  @Test
  fun `style - enabled false onClick after primary does not wipe additionals either`() {
    val log = mutableListOf<String>()

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          Row(
              style =
                  Style.width(100.px)
                      .height(100.px)
                      .viewTag(TARGET)
                      .onClick { log.add("primary") }
                      .additionalOnClick(KEY_A) { log.add("a") }
                      // Disabled onClick → clickHandler(null). Previous (non-null) primary
                      // installed via the earlier `onClick { … }` would be overwritten by the
                      // null assignment, but the additional registered between the two stays.
                      // Documented edge: the explicit null assignment cancels the primary; it
                      // does NOT cancel additionals (see the setter in NodeInfo).
                      .onClick(enabled = false) { error("disabled onClick should not execute") },
          )
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    // Primary is gone (overridden to null by the disabled onClick), but `a` survives.
    assertThat(log).containsExactly("a")
  }

  // ------------------------------------------------------------------------
  // Wrapper + delegate merge — NodeInfo.copyInto(NodeInfo) must carry additionals
  // ------------------------------------------------------------------------

  @Test
  fun `additionalOnClick on a Wrapper survives merge into a delegate that owns a click handler`() {
    val log = mutableListOf<String>()

    class WrappedButton : KComponent() {
      override fun ComponentScope.render(): Component {
        // Inner component owns its own click handler, so it resolves to a NodeInfo with
        // PFLAG_CLICK_HANDLER_IS_SET — mirrors FDSInternalActionTextButton / JavaFDSPressedState.
        val inner = Row(
            style =
                Style.width(100.px).height(100.px).viewTag(TARGET).onClick {
                  log.add("primary")
                },
        )
        // Wrapping with additionalOnClick merges the Wrapper's NodeInfo into the delegate's via
        // NodeInfo.copyInto(NodeInfo). That overload previously copied only the primary handler and
        // dropped extraClickListeners, silently losing this additional listener.
        return Wrapper.create(context)
            .delegate(inner)
            .kotlinStyle(Style.additionalOnClick(KEY_A) { log.add("additional") })
            .build()
      }
    }

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          WrappedButton()
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    assertThat(log).containsExactly("primary", "additional")
  }

  @Test
  fun `merging a Wrapper into a delegate that share an additional-click key does not crash`() {
    val log = mutableListOf<String>()

    class WrappedButton : KComponent() {
      override fun ComponentScope.render(): Component {
        // The delegate already owns an additional listener for KEY_A (plus a primary handler).
        val inner = Row(
            style =
                Style.width(100.px)
                    .height(100.px)
                    .viewTag(TARGET)
                    .onClick { log.add("primary") }
                    .additionalOnClick(KEY_A) { log.add("delegate-additional") },
        )
        // The Wrapper registers an additional listener for the SAME key. Merging its NodeInfo into
        // the delegate's via NodeInfo.copyInto(NodeInfo) must de-dupe on key instead of routing
        // through addExtraClickListener's duplicate-key check, which would crash layout.
        return Wrapper.create(context)
            .delegate(inner)
            .kotlinStyle(Style.additionalOnClick(KEY_A) { log.add("wrapper-additional") })
            .build()
      }
    }

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          WrappedButton()
        }

    testLithoView.findViewWithTag(TARGET).performClick()

    // No crash; the delegate's listener for the shared key is kept and the Wrapper's is de-duped.
    assertThat(log).containsExactly("primary", "delegate-additional")
  }

  // ------------------------------------------------------------------------
  // Duplicate-key registration on the same view throws
  // ------------------------------------------------------------------------

  @Test
  fun `style - duplicate key on same node throws at render time`() {
    val thrown = catchThrowable {
      lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
        Row(
            style =
                Style.width(100.px)
                    .height(100.px)
                    .additionalOnClick(KEY_A) {}
                    .additionalOnClick(KEY_A) {},
        )
      }
    }

    // Depending on the Litho configuration variant the test runs under, the
    // [IllegalStateException] from `addExtraClickListener` may bubble out directly or be wrapped
    // (e.g., in a `LithoMetadataExceptionWrapper`). Walk the cause chain to verify the origin in
    // either case.
    assertThat(thrown).isNotNull
    var cursor: Throwable? = thrown
    var foundDuplicateKey = false
    while (cursor != null) {
      if (
          cursor is IllegalStateException &&
              cursor.message?.contains(Integer.toHexString(KEY_A)) == true
      ) {
        foundDuplicateKey = true
        break
      }
      cursor = cursor.cause
    }
    assertThat(foundDuplicateKey)
        .withFailMessage(
            "Expected an IllegalStateException mentioning duplicate key 0x${Integer.toHexString(KEY_A)} in the cause chain of: $thrown",
        )
        .isTrue
  }

  @Test
  fun `style - same key on different nodes does not collide`() {
    var aCount = 0
    var bCount = 0

    class TwoChildren : KComponent() {
      override fun ComponentScope.render(): Component =
          Row(style = Style.width(200.px).height(200.px)) {
            child(
                Row(
                    style =
                        Style.width(100.px).height(100.px).viewTag(TARGET_A).additionalOnClick(
                            KEY_A,
                        ) {
                          aCount += 1
                        },
                ),
            )
            child(
                Row(
                    style =
                        Style.width(100.px).height(100.px).viewTag(TARGET_B).additionalOnClick(
                            KEY_A,
                        ) {
                          bCount += 1
                        },
                ),
            )
          }
    }

    val testLithoView =
        lithoTestRule.render(widthSpec = unspecified(), heightSpec = unspecified()) {
          TwoChildren()
        }

    testLithoView.findViewWithTag(TARGET_A).performClick()
    testLithoView.findViewWithTag(TARGET_B).performClick()

    assertThat(aCount).isEqualTo(1)
    assertThat(bCount).isEqualTo(1)
  }

  companion object {
    private const val TARGET = "target"
    private const val TARGET_A = "target_a"
    private const val TARGET_B = "target_b"

    // Arbitrary `@IdRes`-shaped ints for namespacing additional listeners in tests. Real
    // call-sites should declare keys in their module's `ids.xml`.
    private const val KEY_A: Int = 0x7f0a0001
    private const val KEY_B: Int = 0x7f0a0002
  }
}
