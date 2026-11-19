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

import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.facebook.litho.Column
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.assertj.LithoAssertions
import com.facebook.rendercore.text.ClickableSpanListener
import com.facebook.rendercore.text.LongClickableSpan
import com.facebook.rendercore.text.TouchableSpanListener
import com.facebook.yoga.YogaEdge
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Every case runs under both settings of [ComponentsConfiguration.usePrimitiveText]; the same
 * assertions passing against the legacy `TextComponent` and the Primitive `RichText` is what makes
 * them behaviorally interchangeable.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextPrimitiveBehaviorParityTest(private val usePrimitiveText: Boolean) {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private var previousUsePrimitiveText = false
  private val events = mutableListOf<String>()

  @Before
  fun setUp() {
    RuntimeEnvironment.setQualifiers(TextPrimitiveParityTest.DENSITY_QUALIFIER)
    previousUsePrimitiveText = ComponentsConfiguration.usePrimitiveText
    ComponentsConfiguration.usePrimitiveText = usePrimitiveText
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.usePrimitiveText = previousUsePrimitiveText
  }

  @Test
  fun `tapping a clickable span invokes its onClick`() {
    val testLithoView = renderClickableText()

    tap(testLithoView)

    assertThat(events).containsExactly(SPAN_CLICK)
  }

  @Test
  fun `clickable span listener is notified before the span is clicked`() {
    val testLithoView = renderClickableText(spanListener = recordingSpanListener(consume = false))

    tap(testLithoView)

    assertThat(events).containsExactly(LISTENER_CLICK, SPAN_CLICK)
  }

  @Test
  fun `clickable span listener consuming the click prevents the span onClick`() {
    val testLithoView = renderClickableText(spanListener = recordingSpanListener(consume = true))

    tap(testLithoView)

    assertThat(events).containsExactly(LISTENER_CLICK)
  }

  @Test
  fun `releasing off the pressed span clicks nothing`() {
    // |0      40                 200
    // [ #tag  and some plain text  ]   text view, 200 x 40
    //   ^ press at (5, 10), then release on " and" at (150, 10) or below the text at (5, 120)
    for ((upX, upY) in listOf(150f to 10f, 5f to 120f)) {
      events.clear()
      val testLithoView = renderHashtagText()

      tap(testLithoView, downX = 5f, downY = 10f, upX = upX, upY = upY)

      assertThat(events).describedAs("release at ($upX, $upY)").isEmpty()
    }
  }

  @Test
  fun `releasing on the pressed span clicks it`() {
    val testLithoView = renderHashtagText()

    tap(testLithoView, downX = 5f, downY = 10f, upX = 15f, upY = 12f)

    assertThat(events).containsExactly(SPAN_CLICK)
  }

  @Test
  fun `a still tap next to a span clicks it when the span has an expanded touch area`() {
    // |0      40                 200
    // [ #tag  and some plain text  ]   text view, 200 x 40; "#tag" ends near x = 40
    //            ^ press and release at (50, 10): off "#tag", within 20 px of it
    val testLithoView = renderHashtagText(clickableSpanExpandedOffsetPx = 20f)

    tap(testLithoView, downX = 50f, downY = 10f, upX = 50f, upY = 10f)

    assertThat(events).containsExactly(SPAN_CLICK)
  }

  @Test
  fun `touchable span listener receives touch events on spans`() {
    val touchedActions = mutableListOf<Int>()
    val testLithoView = renderClickableText(
        touchableSpanListener =
            TouchableSpanListener { _, motionEvent, _ ->
              touchedActions.add(motionEvent.actionMasked)
              false
            },
    )

    tap(testLithoView)

    assertThat(touchedActions).contains(MotionEvent.ACTION_DOWN)
  }

  @Test
  fun `exposes rendered text to test helpers`() {
    val testLithoView = lithoTestRule.render { Text.create(context).text(TEXT).build() }

    LithoAssertions.assertThat(testLithoView).hasVisibleText(TEXT)
  }

  @Test
  fun `updates rendered text when the text prop changes`() {
    val testLithoView = lithoTestRule.render { Text.create(context).text(TEXT).build() }

    testLithoView.setRoot(Text.create(lithoTestRule.context).text(UPDATED_TEXT).build())
    lithoTestRule.idle()

    LithoAssertions.assertThat(testLithoView).hasVisibleText(UPDATED_TEXT)
    LithoAssertions.assertThat(testLithoView).doesNotHaveVisibleText(TEXT)
  }

  @Test
  fun `a long press on a span still opens after a stationary move when the text is offset`() {
    val testLithoView = renderLongClickableText(paddingStartPx = 40, paddingTopPx = 60)

    // |0   40                      240
    // 60   [ Like                    ]   text view, offset 40 px right and 60 px down
    // 100  [__________________________]
    //         ^ press at (45, 70) in the root, (5, 10) in the text view
    longPress(testLithoView, x = 45f, y = 70f, moveX = 45f, moveY = 70f)

    assertThat(events).containsExactly(SPAN_LONG_CLICK)
  }

  @Test
  fun `a long press on a span is cancelled when the press moves off the span or off the text`() {

    // |0   40                      240
    // 60   [ Like                    ]   text view, offset 40 px right and 60 px down
    // 100  [__________________________]
    //         ^ press at (45, 70), then moves past "Like" to (200, 70), or below the text to (45,
    // 120)
    for ((moveX, moveY) in listOf(200f to 70f, 45f to 120f)) {
      events.clear()
      val testLithoView = renderLongClickableText(paddingStartPx = 40, paddingTopPx = 60)

      longPress(testLithoView, x = 45f, y = 70f, moveX = moveX, moveY = moveY)

      assertThat(events).describedAs("move to ($moveX, $moveY)").doesNotContain(SPAN_LONG_CLICK)
    }
  }

  @Suppress("DEPRECATION")
  private fun renderLongClickableText(paddingStartPx: Int, paddingTopPx: Int): TestLithoView {
    val text = SpannableString(LIKE)
    text.setSpan(
        object : LongClickableSpan() {
          override fun onClick(widget: View) {
            events.add(SPAN_CLICK)
          }

          override fun onLongClick(view: View): Boolean {
            events.add(SPAN_LONG_CLICK)
            return true
          }
        },
        0,
        LIKE.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    return lithoTestRule.render {
      Column.create(context)
          .paddingPx(YogaEdge.START, paddingStartPx)
          .paddingPx(YogaEdge.TOP, paddingTopPx)
          .child(Text.create(context).text(text).textSizePx(20).widthPx(200).heightPx(40))
          .build()
    }
  }

  private fun longPress(
      testLithoView: TestLithoView,
      x: Float,
      y: Float,
      moveX: Float,
      moveY: Float,
  ) {
    val lithoView = testLithoView.lithoView
    val downTime = SystemClock.uptimeMillis()
    lithoView.dispatchTouchEvent(
        MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0),
    )
    lithoView.dispatchTouchEvent(
        MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_MOVE, moveX, moveY, 0),
    )
    shadowOf(Looper.getMainLooper())
        .idleFor(ViewConfiguration.getLongPressTimeout() + 100L, TimeUnit.MILLISECONDS)
    lithoView.dispatchTouchEvent(
        MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            moveX,
            moveY,
            0,
        ),
    )
  }

  private fun renderClickableText(
      spanListener: ClickableSpanListener? = null,
      touchableSpanListener: TouchableSpanListener? = null,
  ): TestLithoView {
    val text = SpannableString(TEXT)
    text.setSpan(
        object : ClickableSpan() {
          override fun onClick(widget: View) {
            events.add(SPAN_CLICK)
          }
        },
        0,
        TEXT.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    return lithoTestRule.render {
      Text.create(context)
          .text(text)
          .spanListener(spanListener)
          .touchableSpanListener(touchableSpanListener)
          .build()
    }
  }

  @Suppress("DEPRECATION")
  private fun renderHashtagText(clickableSpanExpandedOffsetPx: Float = 0f): TestLithoView {
    val text = SpannableString(HASHTAG_TEXT)
    text.setSpan(
        object : ClickableSpan() {
          override fun onClick(widget: View) {
            events.add(SPAN_CLICK)
          }
        },
        0,
        HASHTAG.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    return lithoTestRule.render {
      Column.create(context)
          .child(
              Text.create(context)
                  .text(text)
                  .textSizePx(20)
                  .clickableSpanExpandedOffsetPx(clickableSpanExpandedOffsetPx)
                  .widthPx(200)
                  .heightPx(40),
          )
          .build()
    }
  }

  private fun recordingSpanListener(consume: Boolean): ClickableSpanListener =
      object : ClickableSpanListener {
        override fun onClick(span: ClickableSpan, view: View): Boolean {
          events.add(LISTENER_CLICK)
          return consume
        }
      }

  private fun tap(testLithoView: TestLithoView) {
    val lithoView = testLithoView.lithoView
    lithoView.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
    lithoView.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_UP))
  }

  private fun tap(
      testLithoView: TestLithoView,
      downX: Float,
      downY: Float,
      upX: Float,
      upY: Float,
  ) {
    val lithoView = testLithoView.lithoView
    lithoView.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, downX, downY, 0))
    lithoView.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, upX, upY, 0))
  }

  private fun motionEvent(action: Int): MotionEvent =
      MotionEvent.obtain(0, 0, action, TAP_X, TAP_Y, 0)

  companion object {
    private const val TEXT = "hello world"
    private const val UPDATED_TEXT = "goodbye world"
    private const val TAP_X = 1f
    private const val TAP_Y = 1f
    private const val SPAN_CLICK = "span.onClick"
    private const val SPAN_LONG_CLICK = "span.onLongClick"
    private const val LIKE = "Like"
    private const val HASHTAG = "#tag"
    private const val HASHTAG_TEXT = "#tag and some plain text"
    private const val LISTENER_CLICK = "listener.onClick"

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "usePrimitiveText={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }
}
