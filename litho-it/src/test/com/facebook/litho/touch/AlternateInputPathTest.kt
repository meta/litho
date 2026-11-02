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

package com.facebook.litho.touch

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.litho.Style
import com.facebook.litho.accessibility.contentDescription
import com.facebook.litho.child
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.accessibilityProbe
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.focusable
import com.facebook.litho.view.onHover
import com.facebook.litho.view.onKeyEvent
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Input paths other than touch that reach a component through the same machinery.
 *
 * Hover, key events and focus are all delivered by the same per-component host a touch-dispatch
 * change would stop creating, so they can move with it and no gesture test would notice. Events are
 * dispatched at the root and targets are resolved by what a service announces, never by reaching
 * for a `ComponentHost`, so the tests survive that change.
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AlternateInputPathTest {

  @get:Rule val lithoTestRule = LithoTestRule(attachToRealWindow = true)

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
  }

  private fun labelled(label: String): (AccessibilityNodeInfo) -> Boolean = {
    it.contentDescription == label
  }

  @Test
  fun `onHover receives hover events dispatched at the root`() {
    val actions = mutableListOf<Int>()
    val testLithoView = lithoTestRule.render {
      Column {
        child(
            Column(
                style =
                    Style.width(SIZE.px).height(SIZE.px).onHover { _, event ->
                      actions.add(event.actionMasked)
                      true
                    },
            ),
        )
      }
    }

    val now = SystemClock.uptimeMillis()
    val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_ENTER, CENTER, CENTER, 0)
    try {
      testLithoView.lithoView.dispatchGenericMotionEvent(event)
    } finally {
      event.recycle()
    }

    assertThat(actions).contains(MotionEvent.ACTION_HOVER_ENTER)
  }

  @Test
  fun `onKeyEvent receives key events dispatched at the root once the target has focus`() {
    // Focus only moves by key outside touch mode, which is the state a device is in under D-pad or
    // keyboard navigation — the only way a key event reaches a component in the first place.
    InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    val codes = mutableListOf<Int>()
    val testLithoView = lithoTestRule.render {
      Column {
        child(
            Column(
                style =
                    Style.width(SIZE.px)
                        .height(SIZE.px)
                        .contentDescription(LABEL)
                        .focusable(true)
                        .onKeyEvent { _, keyCode, _ ->
                          codes.add(keyCode)
                          true
                        },
            ),
        )
      }
    }
    val focused = testLithoView.accessibilityProbe().require(labelled(LABEL)).requestKeyboardFocus()
    assertThat(focused).describedAs("focusable(true) lets focus move to the component").isTrue()

    testLithoView.lithoView.dispatchKeyEvent(
        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
    )

    assertThat(codes).contains(KeyEvent.KEYCODE_ENTER)
  }

  @Test
  fun `focusable is announced on the node`() {
    val probe =
        lithoTestRule
            .render {
              Column {
                child(
                    Column(
                        style =
                            Style.width(SIZE.px)
                                .height(SIZE.px)
                                .contentDescription(LABEL)
                                .focusable(true),
                    ),
                )
              }
            }
            .accessibilityProbe()

    assertThat(probe.require(labelled(LABEL)).info().isFocusable).isTrue()
  }

  companion object {
    private const val SIZE = 100
    private const val CENTER = SIZE / 2f
    private const val LABEL = "target"
  }
}
