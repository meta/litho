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

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useCached
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.text.ClickableSpanListener
import com.facebook.rendercore.text.RCTextView
import com.facebook.rendercore.text.TouchableSpanListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Tests [RichText] component. */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RichTextTest {
  @JvmField @Rule var lithoTestRule = LithoTestRule()

  @Test
  fun testTouchableSpanListenerWorksAfterStateUpdate() {
    var spanTouchCount = 0

    val clickableText = Spannable.Factory.getInstance().newSpannable("Test")
    val clickableSpan =
        object : ClickableSpan() {
          override fun onClick(widget: View): Unit = Unit
        }
    clickableText.setSpan(clickableSpan, 0, "Test".length, 0)

    val testLithoView: TestLithoView = lithoTestRule.render {
      TestKComponent(clickableText, onSpanTouch = { spanTouchCount++ })
    }
    val rcTextView: RCTextView = testLithoView.lithoView.getChildAt(0) as RCTextView

    assertThat(spanTouchCount).isEqualTo(0)

    // perform touch
    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    rcTextView.onTouchEvent(downEvent)

    assertThat(spanTouchCount).isEqualTo(1)

    // trigger state update
    lithoTestRule.act(testLithoView) { clickOnTag("richText") }
    lithoTestRule.idle()

    assertThat(spanTouchCount).isEqualTo(1)

    // touch after state update
    rcTextView.onTouchEvent(downEvent)

    assertThat(spanTouchCount).isEqualTo(2)
  }

  @Test
  fun testClickableSpanListenerWorksAfterStateUpdate() {
    var spanClickCount = 0

    val clickableText = Spannable.Factory.getInstance().newSpannable("Test")
    val clickableSpan =
        object : ClickableSpan() {
          override fun onClick(widget: View): Unit = Unit
        }
    clickableText.setSpan(clickableSpan, 0, "Test".length, 0)

    val testLithoView: TestLithoView = lithoTestRule.render {
      TestKComponent(clickableText, onSpanClick = { spanClickCount++ })
    }
    val rcTextView: RCTextView = testLithoView.lithoView.getChildAt(0) as RCTextView

    assertThat(spanClickCount).isEqualTo(0)

    // perform click using touch events
    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    rcTextView.onTouchEvent(downEvent)
    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
    rcTextView.onTouchEvent(upEvent)

    assertThat(spanClickCount).isEqualTo(1)

    // trigger state update
    lithoTestRule.act(testLithoView) { clickOnTag("richText") }
    lithoTestRule.idle()

    assertThat(spanClickCount).isEqualTo(1)

    // click after state update
    rcTextView.onTouchEvent(downEvent)
    rcTextView.onTouchEvent(upEvent)

    assertThat(spanClickCount).isEqualTo(2)
  }
}

private class TestKComponent(
    private val textWithSpans: CharSequence,
    private val onSpanTouch: () -> Unit = {},
    private val onSpanClick: () -> Unit = {},
) : KComponent() {
  override fun ComponentScope.render(): Component? {
    val counter = useState { 0 }

    val touchSpanListener = useCached {
      object : TouchableSpanListener {
        override fun onTouch(span: ClickableSpan?, motionEvent: MotionEvent, view: View): Boolean {
          onSpanTouch()
          return true
        }
      }
    }

    val clickableSpanListener = useCached {
      object : ClickableSpanListener {
        override fun onClick(span: ClickableSpan, view: View): Boolean {
          onSpanClick()
          return true
        }
      }
    }

    return RichText(
        text = SpannableStringBuilder(textWithSpans).apply { append("${counter.value}") },
        touchableSpanListener = touchSpanListener,
        clickableSpanListener = clickableSpanListener,
        style = Style.onClick { counter.update { it + 1 } }.viewTag("richText"),
    )
  }
}
