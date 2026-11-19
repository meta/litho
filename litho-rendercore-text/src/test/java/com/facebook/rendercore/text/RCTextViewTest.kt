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

package com.facebook.rendercore.text

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import com.facebook.rendercore.HostView
import com.facebook.rendercore.LayoutCache
import com.facebook.rendercore.LayoutContext
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.testing.RenderCoreTestRule
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class RCTextViewTest {

  @Rule @JvmField val renderCoreTestRule = RenderCoreTestRule()
  private val UNSPECIFIED_WIDTH_SPEC =
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

  private class InlineViewTracker {
    var createCount = 0
    var bindCount = 0
    var layoutCount = 0
    var releaseCount = 0
  }

  private class TestInlineViewSpan(
      private val childWidth: Int,
      private val childHeight: Int,
      private val leftInset: Int = 0,
      private val reuseKey: Any = Any(),
      private val tracker: InlineViewTracker = InlineViewTracker(),
  ) : ReplacementSpan(), InlineViewSpan {
    var createdView: View? = null
    var boundView: View? = null
    var releasedView: View? = null
    var releasedViewParent: ViewParent? = null
    val touchActions = mutableListOf<Int>()

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int {
      fontMetrics?.let { metrics ->
        metrics.ascent = minOf(metrics.ascent, -childHeight)
        metrics.top = minOf(metrics.top, -childHeight)
      }
      return leftInset + childWidth
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) = Unit

    override fun getInlineViewReuseKey(): Any = reuseKey

    override fun createInlineView(context: android.content.Context): View =
        object : View(context) {
              override fun onLayout(
                  changed: Boolean,
                  left: Int,
                  top: Int,
                  right: Int,
                  bottom: Int,
              ) {
                tracker.layoutCount++
              }

              override fun onTouchEvent(event: MotionEvent): Boolean {
                touchActions.add(event.action)
                return true
              }
            }
            .also { view ->
              tracker.createCount++
              createdView = view
            }

    override fun bindInlineView(view: View) {
      tracker.bindCount++
      boundView = view
      // Like RenderTreeHostView.setMountInput, which defers mounting to onLayout.
      view.requestLayout()
    }

    override fun releaseInlineView(view: View) {
      tracker.releaseCount++
      releasedView = view
      releasedViewParent = view.parent
    }

    override fun getInlineViewBounds(lineAscent: Int, lineDescent: Int): Rect =
        Rect(leftInset, -childHeight, leftInset + childWidth, 0)
  }

  private fun renderTextWithInlineSpan(
      text: String,
      spanStart: Int,
      width: Int = 200,
      inlineSpan: TestInlineViewSpan =
          TestInlineViewSpan(childWidth = 20, childHeight = 10, leftInset = 3),
  ): Pair<RCTextView, TestInlineViewSpan> {
    val spannable = SpannableString(text)
    spannable.setSpan(inlineSpan, spanStart, spanStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val textStyle = TextStyle().also { it.textSize = 20 }
    val root = RichTextPrimitive(id = 1, text = spannable, style = textStyle)
    renderCoreTestRule.useRootNode(root).setSizePx(width, 100).render()
    val host = renderCoreTestRule.rootHost as HostView
    return Pair(host.getChildAt(0) as RCTextView, inlineSpan)
  }

  @Test
  fun hostsInlineViewAtPlaceholderAndDispatchesTouchToChild() {
    val (textView, span) = renderTextWithInlineSpan("A\uFFFCB", spanStart = 1)
    val child = span.createdView!!

    assertThat(textView.childCount).isEqualTo(1)
    assertThat(child.parent).isSameAs(textView)
    assertThat(child.width).isEqualTo(20)
    assertThat(child.height).isEqualTo(10)
    assertThat(child.left).isGreaterThan(0)
    assertThat(child.translationX).isZero()
    assertThat(child.translationY).isZero()

    val touchX = (child.left + child.right) / 2f
    val touchY = (child.top + child.bottom) / 2f
    textView.dispatchTouchEvent(
        MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, touchX, touchY, 0),
    )

    assertThat(span.touchActions).containsExactly(MotionEvent.ACTION_DOWN)
  }

  @Test
  fun inlineViewSpansOptIntoNestedRenderTreeHostMounting() {
    val inlineSpan = TestInlineViewSpan(childWidth = 20, childHeight = 10)
    val text = SpannableString("\uFFFC")
    text.setSpan(inlineSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    val primitive = RichTextPrimitive(id = 1, text = text, style = TextStyle())

    assertThat(primitive.renderUnit.doesMountRenderTreeHosts()).isTrue()
    assertThat(TextRenderUnit(2, true).doesMountRenderTreeHosts()).isTrue()
    assertThat(TextRenderUnit(3).doesMountRenderTreeHosts()).isFalse()
  }

  @Test
  fun mountsOneInlineViewPerReuseKey() {
    val tracker = InlineViewTracker()
    val text = SpannableString("\uFFFC\uFFFC")
    repeat(2) { index ->
      text.setSpan(
          TestInlineViewSpan(
              childWidth = 20,
              childHeight = 10,
              reuseKey = "component",
              tracker = tracker,
          ),
          index,
          index + 1,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
    }
    renderCoreTestRule
        .useRootNode(RichTextPrimitive(id = 1, text = text, style = TextStyle()))
        .setSizePx(200, 100)
        .render()
    val textView = (renderCoreTestRule.rootHost as HostView).getChildAt(0) as RCTextView

    assertThat(textView.childCount).isEqualTo(1)
    assertThat(tracker.createCount).isEqualTo(1)
    assertThat(tracker.bindCount).isEqualTo(1)
    assertThat(textView.getChildAt(0).visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun remountWithoutInlineSpanReleasesInlineView() {
    val (textView, span) = renderTextWithInlineSpan("A\uFFFC", spanStart = 1)
    val child = span.createdView!!

    renderCoreTestRule
        .useRootNode(RichTextPrimitive(id = 1, text = "plain", style = TextStyle()))
        .setSizePx(200, 100)
        .render()

    assertThat((renderCoreTestRule.rootHost as HostView).getChildAt(0)).isSameAs(textView)
    assertThat(span.releasedView).isSameAs(child)
    assertThat(span.releasedViewParent).isNull()
    assertThat(textView.childCount).isZero()
  }

  @Test
  fun hidesInlineViewBeyondLastVisibleLineWithoutEllipsis() {
    val inlineSpan = TestInlineViewSpan(childWidth = 20, childHeight = 10)
    val text = SpannableString("visible\nhidden\uFFFC")
    text.setSpan(
        inlineSpan,
        text.length - 1,
        text.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    val layout = mock<Layout>()
    whenever(layout.getLineForOffset(text.length - 1)).thenReturn(0)
    whenever(layout.getLineVisibleEnd(0)).thenReturn("visible".length)
    whenever(layout.getPrimaryHorizontal(any()))
        .thenThrow(
            AssertionError("Hidden span offsets must not be measured"),
        )
    val textLayout =
        TextMeasurementUtils.TextLayout().also { textLayout ->
          textLayout.layout = layout
          textLayout.processedText = text
          textLayout.textStyle = TextStyle()
        }
    val textView = RCTextView(renderCoreTestRule.context)

    textView.mount(textLayout)
    textView.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
    )
    textView.layout(0, 0, 100, 100)

    assertThat(inlineSpan.boundView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun hidesInlineViewWhoseEndOverlapsEllipsizedText() {
    val inlineSpan = TestInlineViewSpan(childWidth = 20, childHeight = 10)
    val text = SpannableString("abc")
    text.setSpan(inlineSpan, 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val layout = mock<Layout>()
    whenever(layout.getLineForOffset(0)).thenReturn(0)
    whenever(layout.getLineVisibleEnd(0)).thenReturn(text.length)
    whenever(layout.getLineStart(0)).thenReturn(0)
    whenever(layout.getEllipsisStart(0)).thenReturn(1)
    whenever(layout.getEllipsisCount(0)).thenReturn(1)
    whenever(layout.getPrimaryHorizontal(any()))
        .thenThrow(AssertionError("Ellipsized span offsets must not be measured"))
    val textLayout =
        TextMeasurementUtils.TextLayout().also { textLayout ->
          textLayout.layout = layout
          textLayout.processedText = text
          textLayout.textStyle = TextStyle()
        }
    val textView = RCTextView(renderCoreTestRule.context)

    textView.mount(textLayout)
    textView.measure(
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
    )
    textView.layout(0, 0, 100, 100)

    assertThat(inlineSpan.boundView?.visibility).isEqualTo(View.GONE)
  }

  @Test
  fun placesInlineViewOnWrappedTextLine() {
    val (textView, span) =
        renderTextWithInlineSpan("prefix prefix \uFFFC", spanStart = 14, width = 70)
    val child = span.createdView!!

    assertThat(child.top).isGreaterThan(0)
    assertThat(child.left).isGreaterThanOrEqualTo(textView.paddingLeft)
    assertThat(child.right).isLessThanOrEqualTo(textView.width - textView.paddingRight)
  }

  @Test
  fun updateUnmountRetainsInlineViewUntilFinalUnmount() {
    val tracker = InlineViewTracker()
    val inlineSpan = TestInlineViewSpan(
        childWidth = 20,
        childHeight = 10,
        reuseKey = "component",
        tracker = tracker,
    )
    val (textView, span) =
        renderTextWithInlineSpan("\uFFFC", spanStart = 0, inlineSpan = inlineSpan)
    val child = span.createdView!!

    textView.unmount()

    assertThat(tracker.releaseCount).isZero()
    assertThat(textView.childCount).isEqualTo(1)
    assertThat(child.parent).isSameAs(textView)
    assertThat(child.visibility).isEqualTo(View.GONE)

    textView.finalUnmount()

    assertThat(span.releasedView).isSameAs(child)
    assertThat(span.releasedViewParent).isNull()
    assertThat(tracker.releaseCount).isEqualTo(1)
    assertThat(textView.childCount).isZero()
    assertThat(child.parent).isNull()
  }

  @Test
  fun remountReusesInlineViewForSameComponent() {
    val tracker = InlineViewTracker()
    val firstSpan = TestInlineViewSpan(
        childWidth = 20,
        childHeight = 10,
        reuseKey = "component",
        tracker = tracker,
    )
    val (firstTextView, _) =
        renderTextWithInlineSpan("A\uFFFC", spanStart = 1, inlineSpan = firstSpan)
    val firstChild = firstSpan.boundView!!
    val secondSpan = TestInlineViewSpan(
        childWidth = 30,
        childHeight = 15,
        reuseKey = "component",
        tracker = tracker,
    )

    val (secondTextView, _) =
        renderTextWithInlineSpan("B\uFFFC", spanStart = 1, inlineSpan = secondSpan)

    assertThat(secondTextView).isSameAs(firstTextView)
    assertThat(secondSpan.boundView).isSameAs(firstChild)
    assertThat(tracker.createCount).isEqualTo(1)
    assertThat(tracker.bindCount).isEqualTo(2)
    assertThat(tracker.releaseCount).isZero()
    assertThat(firstChild.width).isEqualTo(30)
    assertThat(firstChild.height).isEqualTo(15)
    assertThat(firstChild.visibility).isEqualTo(View.VISIBLE)
  }

  @Test
  fun remountWithUnchangedBoundsLaysOutReboundInlineView() {
    val tracker = InlineViewTracker()
    val textView = RCTextView(renderCoreTestRule.context)
    val exactly100 = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
    fun mountAndLayOut() {
      val text = SpannableString("A\uFFFC")
      text.setSpan(
          TestInlineViewSpan(
              childWidth = 20,
              childHeight = 10,
              reuseKey = "component",
              tracker = tracker,
          ),
          1,
          2,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
      val textLayout =
          TextMeasurementUtils.layout(
                  renderCoreTestRule.context,
                  exactly100,
                  exactly100,
                  text,
                  TextStyle(),
                  false,
              )
              .second
      textView.mount(textLayout)
      textView.measure(exactly100, exactly100)
      textView.layout(0, 0, 100, 100)
    }
    mountAndLayOut()
    val layoutCountAfterFirstMount = tracker.layoutCount

    textView.unmount()
    mountAndLayOut()

    assertThat(tracker.createCount).isEqualTo(1)
    assertThat(tracker.layoutCount).isGreaterThan(layoutCountAfterFirstMount)
  }

  @Test
  fun plainTextKeepsRCTextViewChildless() {
    val root = RichTextPrimitive(id = 1, text = "plain text", style = TextStyle())
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView
    val textView = host.getChildAt(0) as RCTextView
    val holdersField = RCTextView::class.java.getDeclaredField("mInlineViewHolders")
    holdersField.isAccessible = true

    assertThat(textView.childCount).isZero()
    assertThat(holdersField.get(textView)).isNull()
  }

  private fun setupClickableSpanTest(
      shouldSpanBeNull: Boolean = false,
  ): Pair<ArrayList<Int>, RCTextView> {
    val eventsFired = ArrayList<Int>()

    val textStyle = TextStyle()
    textStyle.textSize = 20
    val clickableText = Spannable.Factory.getInstance().newSpannable("Some text.")
    val clickableSpan: ClickableSpan =
        object : ClickableSpan() {
          override fun onClick(widget: View) {
            // No action
          }
        }
    clickableText.setSpan(clickableSpan, 0, 1, 0)

    val root = RichTextPrimitive(
        id = 1,
        text = clickableText,
        style = textStyle,
        touchableSpanListener = { span, motionEvent, _ ->
          eventsFired.add(motionEvent.action)
          if (shouldSpanBeNull) {
            assertThat(span).isNull()
          } else {
            assertThat(span).isNotNull()
          }
          true
        },
    )
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView

    val textView = host.getChildAt(0) as RCTextView

    return Pair(eventsFired, textView)
  }

  private fun setupClickableSpanListenerTest(
      shouldSpanBeNull: Boolean = false,
      clickSpanUnderRelease: Boolean = false,
  ): Pair<ArrayList<String>, RCTextView> {
    val eventsFired = ArrayList<String>()

    val clickableSpanListener =
        object : ClickableSpanListener {
          override fun onClick(span: ClickableSpan, view: View): Boolean {
            eventsFired.add("onClick")
            if (shouldSpanBeNull) {
              assertThat(span).isNull()
            } else {
              assertThat(span).isNotNull()
            }
            return true
          }

          override fun onLongClick(span: LongClickableSpan, view: View): Boolean {
            eventsFired.add("onLongClick")
            if (shouldSpanBeNull) {
              assertThat(span).isNull()
            } else {
              assertThat(span).isNotNull()
            }
            return true
          }
        }

    val textStyle = TextStyle()
    textStyle.textSize = 20
    textStyle.clickSpanUnderRelease = clickSpanUnderRelease

    val clickableText = Spannable.Factory.getInstance().newSpannable("Some text.")
    val clickableSpan =
        object : LongClickableSpan() {
          override fun onLongClick(view: View): Boolean = false

          override fun onClick(widget: View) = Unit
        }
    clickableText.setSpan(clickableSpan, 0, 1, 0)

    val root = RichTextPrimitive(
        id = 1,
        text = clickableText,
        style = textStyle,
        clickableSpanListener = clickableSpanListener,
    )
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView

    val textView = host.getChildAt(0) as RCTextView

    return Pair(eventsFired, textView)
  }

  private fun performActionDownOnSpan(eventsFired: ArrayList<Int>, textView: RCTextView) {
    // click on the span
    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(downEvent)).isEqualTo(true)
    assertThat(eventsFired.size).isEqualTo(1)
    assertThat(eventsFired).contains(MotionEvent.ACTION_DOWN)
    eventsFired.clear()
  }

  private fun performActionDownOutsideOfSpan(eventsFired: ArrayList<Int>, textView: RCTextView) {
    // click outside of the span
    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 9000f, 9000f, 0)
    assertThat(textView.onTouchEvent(downEvent)).isEqualTo(false)
    assertThat(eventsFired.size).isEqualTo(0)
  }

  private fun setupKeyboardSpanNavigationTest(): Pair<ArrayList<String>, RCTextView> {
    val clickedSpans = ArrayList<String>()

    val textStyle = TextStyle()
    textStyle.textSize = 20
    // A non-transparent keyboard highlight color is what opts the view into key event handling.
    textStyle.keyboardHighlightColor = Color.BLUE

    val text = Spannable.Factory.getInstance().newSpannable("one two six")
    listOf(Triple("one", 0, 3), Triple("two", 4, 7), Triple("six", 8, 11)).forEach {
        (label, start, end) ->
      text.setSpan(
          object : ClickableSpan() {
            override fun onClick(widget: View) {
              clickedSpans.add(label)
            }
          },
          start,
          end,
          Spannable.SPAN_INCLUSIVE_EXCLUSIVE,
      )
    }

    val root = RichTextPrimitive(id = 1, text = text, style = textStyle)
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView

    return Pair(clickedSpans, host.getChildAt(0) as RCTextView)
  }

  private fun tabDown(metaState: Int = 0): KeyEvent =
      KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, metaState)

  private fun tabUp(metaState: Int = 0): KeyEvent =
      KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, metaState)

  private fun enterDown(): KeyEvent =
      KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0)

  @Test
  fun testTabStepsForwardThroughClickableSpans() {
    val (clickedSpans, textView) = setupKeyboardSpanNavigationTest()

    // Focus enters the view via Tab, which selects the first span.
    assertThat(textView.onKeyUp(KeyEvent.KEYCODE_TAB, tabUp())).isEqualTo(true)
    // Further Tab presses step to the next span instead of leaving the view.
    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_TAB, tabDown())).isEqualTo(true)
    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_TAB, tabDown())).isEqualTo(true)
    // The last span is selected, so Tab is not consumed and focus can move on.
    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_TAB, tabDown())).isEqualTo(false)

    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_ENTER, enterDown())).isEqualTo(true)
    assertThat(clickedSpans).containsExactly("six")
  }

  @Test
  fun testShiftTabStepsBackwardThroughClickableSpans() {
    val (clickedSpans, textView) = setupKeyboardSpanNavigationTest()

    // Focus enters the view via Shift+Tab, which selects the last span.
    assertThat(textView.onKeyUp(KeyEvent.KEYCODE_TAB, tabUp(KeyEvent.META_SHIFT_ON)))
        .isEqualTo(true)
    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_TAB, tabDown(KeyEvent.META_SHIFT_ON)))
        .isEqualTo(true)
    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_TAB, tabDown(KeyEvent.META_SHIFT_ON)))
        .isEqualTo(true)
    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_TAB, tabDown(KeyEvent.META_SHIFT_ON)))
        .isEqualTo(false)

    assertThat(textView.onKeyDown(KeyEvent.KEYCODE_ENTER, enterDown())).isEqualTo(true)
    assertThat(clickedSpans).containsExactly("one")
  }

  @Test
  fun testClickableSpanTouchEventHandlingForActionDownAndUpWithinSpan() {
    val (eventsFired, textView) = setupClickableSpanTest()

    performActionDownOnSpan(eventsFired, textView)

    // action up within the bounds
    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(upEvent)).isEqualTo(true)
    assertThat(eventsFired.size).isEqualTo(1)
    assertThat(eventsFired).contains(MotionEvent.ACTION_UP)
  }

  @Test
  fun testClickableSpanTouchEventHandlingForActionDownAndCancelWithinSpan() {
    val (eventsFired, textView) = setupClickableSpanTest()

    performActionDownOnSpan(eventsFired, textView)

    // action cancel within the bounds
    val cancelEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(cancelEvent)).isEqualTo(true)
    assertThat(eventsFired.size).isEqualTo(1)
    assertThat(eventsFired).contains(MotionEvent.ACTION_CANCEL)
  }

  @Test
  fun testClickableSpanTouchEventHandlingForActionDownAndCancelOutsideSpan() {
    val (eventsFired, textView) = setupClickableSpanTest(shouldSpanBeNull = true)

    performActionDownOutsideOfSpan(eventsFired, textView)

    // action cancel within the bounds
    val cancelEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(cancelEvent)).isEqualTo(false)
    assertThat(eventsFired.size).isEqualTo(1)
    assertThat(eventsFired).contains(MotionEvent.ACTION_CANCEL)
  }

  @Test
  fun testClickableSpanTouchEventHandlingForActionDownAndUpOutsideSpan() {
    val (eventsFired, textView) = setupClickableSpanTest(shouldSpanBeNull = true)

    performActionDownOutsideOfSpan(eventsFired, textView)

    // action up within the bounds
    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(upEvent)).isEqualTo(false)
    assertThat(eventsFired.size).isEqualTo(1)
    assertThat(eventsFired).contains(MotionEvent.ACTION_UP)
  }

  @Test
  fun testClickableSpanClickEventHandlingForActionDownAndUpWithinSpan() {
    val (eventsFired, textView) = setupClickableSpanListenerTest(shouldSpanBeNull = false)

    // click on the span
    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(downEvent)).isEqualTo(true)
    assertThat(eventsFired.size).isEqualTo(0)

    // action up within the bounds
    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(upEvent)).isEqualTo(true)
    assertThat(eventsFired.size).isEqualTo(1)
    assertThat(eventsFired).contains("onClick")
  }

  @Test
  fun testClickableSpanClickEventHandlingForActionDownAndUpOutsideOfSpan() {
    val (eventsFired, textView) = setupClickableSpanListenerTest(shouldSpanBeNull = false)

    // click on the span
    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    assertThat(textView.onTouchEvent(downEvent)).isEqualTo(true)
    assertThat(eventsFired.size).isEqualTo(0)

    // action up outside of the bounds
    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 9000f, 9000f, 0)
    assertThat(textView.onTouchEvent(upEvent)).isEqualTo(true)
    assertThat(eventsFired.size).isEqualTo(1)
    assertThat(eventsFired).contains("onClick")
  }

  @Test
  fun clickSpanUnderRelease_releaseOnPressedSpan_clicksIt() {
    val (eventsFired, textView) = setupClickableSpanListenerTest(clickSpanUnderRelease = true)

    textView.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
    textView.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0))

    assertThat(eventsFired).containsExactly("onClick")
  }

  @Test
  fun clickSpanUnderRelease_releaseOutsideView_clicksNothing() {
    val (eventsFired, textView) = setupClickableSpanListenerTest(clickSpanUnderRelease = true)

    textView.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
    textView.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 9000f, 9000f, 0))

    assertThat(eventsFired).isEmpty()
  }

  @Test
  fun clickSpanUnderRelease_releaseOnTextOffTheSpan_clicksNothing() {
    val (eventsFired, textView) = setupClickableSpanListenerTest(clickSpanUnderRelease = true)

    // The span covers only "S"; x = 60 is further along "Some text." on the same line.
    textView.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
    textView.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 60f, 5f, 0))

    assertThat(eventsFired).isEmpty()
  }

  @Test
  fun testClickableSpanClickEventHandlingForActionDownAndUpBothOutsideOfSpan() {
    val (eventsFired, textView) = setupClickableSpanListenerTest(shouldSpanBeNull = true)

    // click on the span
    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 9000f, 9000f, 0)
    assertThat(textView.onTouchEvent(downEvent)).isEqualTo(false)
    assertThat(eventsFired.size).isEqualTo(0)

    // action up outside of the bounds
    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 9000f, 9000f, 0)
    assertThat(textView.onTouchEvent(upEvent)).isEqualTo(false)
    assertThat(eventsFired.size).isEqualTo(0)
  }

  @Test
  fun testSpannableWithClickableAndTouchableSpansWithSpanClickActionWillTriggerClickActionIfSpanListenerReturnsFalse() {
    val eventsFired = ArrayList<String>()
    val text = "hello world"

    val clickableText = Spannable.Factory.getInstance().newSpannable(text)
    val touchableSpanListener = TouchableSpanListener { span, motionEvent, view ->
      eventsFired.add(SPAN_LISTENER_TOUCH_ACTION)
      false
    }

    val clickableSpanListener =
        object : ClickableSpanListener {
          override fun onClick(span: ClickableSpan, view: View): Boolean {
            eventsFired.add(SPAN_LISTENER_CLICK_ACTION)
            return false
          }
        }

    val clickableSpan =
        object : ClickableSpan() {
          override fun onClick(widget: View) {
            eventsFired.add(SPAN_CLICK_ACTION)
          }
        }
    clickableText.setSpan(clickableSpan, 0, text.length, 0)

    val textStyle = TextStyle()
    textStyle.textSize = 20

    val root = RichTextPrimitive(
        id = 1,
        text = clickableText,
        style = textStyle,
        touchableSpanListener = touchableSpanListener,
        clickableSpanListener = clickableSpanListener,
    )
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView

    val textView = host.getChildAt(0) as RCTextView

    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    var onTouchEventReturnValue = textView.onTouchEvent(downEvent)
    // we touched the span and will return true to continue getting subsequent events related to the
    // initial touch.
    assertThat(onTouchEventReturnValue).isTrue()
    assertThat(eventsFired).size().isEqualTo(1)
    assertThat(eventsFired).contains(SPAN_LISTENER_TOUCH_ACTION)

    eventsFired.clear()

    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
    onTouchEventReturnValue = textView.onTouchEvent(upEvent)
    // because we are getting  subsequent events related to the initial touch, we will receive up
    // event that will trigger ClickableSpanListener -> with return value = false and will trigger
    // the ClickableSpan.onClick()
    assertThat(onTouchEventReturnValue).isTrue()
    assertThat(eventsFired).size().isEqualTo(3)
    assertThat(eventsFired)
        .contains(SPAN_LISTENER_CLICK_ACTION, SPAN_LISTENER_TOUCH_ACTION, SPAN_CLICK_ACTION)
  }

  @Test
  fun testSpannableWithClickableAndTouchableSpansWithSpanClickActionWontTriggerClickActionIfSpanListenerReturnsTrue() {
    val eventsFired = ArrayList<String>()
    val text = "hello world"

    val clickableText = Spannable.Factory.getInstance().newSpannable(text)
    val touchableSpanListener = TouchableSpanListener { span, motionEvent, view ->
      eventsFired.add(SPAN_LISTENER_TOUCH_ACTION)
      false
    }

    val clickableSpanListener =
        object : ClickableSpanListener {
          override fun onClick(span: ClickableSpan, view: View): Boolean {
            eventsFired.add(SPAN_LISTENER_CLICK_ACTION)
            return true
          }
        }

    val clickableSpan =
        object : ClickableSpan() {
          override fun onClick(widget: View) {
            eventsFired.add(SPAN_CLICK_ACTION)
          }
        }
    clickableText.setSpan(clickableSpan, 0, text.length, 0)

    val textStyle = TextStyle()
    textStyle.textSize = 20

    val root = RichTextPrimitive(
        id = 1,
        text = clickableText,
        style = textStyle,
        touchableSpanListener = touchableSpanListener,
        clickableSpanListener = clickableSpanListener,
    )
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView

    val textView = host.getChildAt(0) as RCTextView

    val downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
    var onTouchEventReturnValue = textView.onTouchEvent(downEvent)
    // we touched the span and will return true to continue getting subsequent events related to the
    // initial touch.
    assertThat(onTouchEventReturnValue).isTrue()
    assertThat(eventsFired).size().isEqualTo(1)
    assertThat(eventsFired).contains(SPAN_LISTENER_TOUCH_ACTION)

    eventsFired.clear()

    val upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
    onTouchEventReturnValue = textView.onTouchEvent(upEvent)
    // because we are getting  subsequent events related to the initial touch, we will receive up
    // event that will trigger ClickableSpanListener -> with return value = true and we wont trigger
    // the ClickableSpan.onClick()
    assertThat(onTouchEventReturnValue).isTrue()
    assertThat(eventsFired).size().isEqualTo(2)
    Assertions.assertThat(eventsFired)
        .contains(SPAN_LISTENER_CLICK_ACTION, SPAN_LISTENER_TOUCH_ACTION)
  }

  @Test
  fun testTextTruncationForTextExceedingMaxLines() {
    val textStyle = TextStyle()
    textStyle.maxLines = 3
    textStyle.customEllipsisText = "...See more"
    textStyle.truncationStyle = TruncationStyle.USE_MAX_LINES

    val clickableText =
        Spannable.Factory.getInstance()
            .newSpannable(
                "This is a very long text with a bullet list of empty items: \n * \n * \n * \n * \n * \n * \n This is the last line",
            )

    val root = RichTextPrimitive(
        id = 1,
        text = clickableText,
        style = textStyle,
        clickableSpanListener = null,
    )
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView
    val textView = host.getChildAt(0) as RCTextView
    assertThat(textView.isTextTruncated()).isEqualTo(true)
    assertThat(textView.text.toString())
        .isEqualTo(
            "This is a very long text with a bullet list of empty items: \n * \n * ...See more",
        )
  }

  @Test
  fun testForceInlineTruncatedTextFirstLine() {
    val textStyle = TextStyle()
    textStyle.maxLines = 3
    textStyle.customEllipsisText = "...See more"
    textStyle.truncationStyle = TruncationStyle.FORCE_INLINE_TRUNCATION

    val clickableText =
        Spannable.Factory.getInstance()
            .newSpannable(
                "This is a very long text with a bullet list of empty items: \n * \n * \n * \n * \n * \n * \n This is the last line",
            )

    val root = RichTextPrimitive(
        id = 1,
        text = clickableText,
        style = textStyle,
        clickableSpanListener = null,
    )
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView
    val textView = host.getChildAt(0) as RCTextView
    assertThat(textView.isTextTruncated()).isEqualTo(true)
    assertThat(textView.text.toString())
        .isEqualTo("This is a very long text with a bullet list of empty items: ...See more")
  }

  @Test
  fun testForceInlineTruncatedTextSecondLine() {
    val textStyle = TextStyle()
    textStyle.maxLines = 3
    textStyle.customEllipsisText = "...See more"
    textStyle.truncationStyle = TruncationStyle.FORCE_INLINE_TRUNCATION

    val clickableText =
        Spannable.Factory.getInstance()
            .newSpannable(
                "This is a very long text with a bullet list of empty items: \n * i have text \n * \n * \n * \n * \n * \n This is the last line",
            )

    val root = RichTextPrimitive(
        id = 1,
        text = clickableText,
        style = textStyle,
        clickableSpanListener = null,
    )
    renderCoreTestRule.useRootNode(root).setSizePx(100, 100).render()
    val host = renderCoreTestRule.rootHost as HostView
    val textView = host.getChildAt(0) as RCTextView
    assertThat(textView.isTextTruncated()).isEqualTo(true)
    assertThat(textView.isTextTruncated()).isEqualTo(true)
    assertThat(textView.text.toString())
        .isEqualTo(
            "This is a very long text with a bullet list of empty items: \n * i have text ...See more",
        )
  }

  @Test
  fun testMinimallyWideText() {
    val textStyle = TextStyle()
    textStyle.minimallyWide = true
    textStyle.minimallyWideThreshold = FULL_TEXT_WIDTH - MINIMAL_TEXT_WIDTH - 1
    val layout = setupWidthTestTextLayout()
    val resolvedWidth = TextMeasurementUtils.resolveWidth(UNSPECIFIED_WIDTH_SPEC, layout, textStyle)
    val layoutWidth = resolvedWidth.first
    val recalculateLayoutForMinimalWidth = resolvedWidth.second

    assertThat(layoutWidth).isEqualTo(MINIMAL_TEXT_WIDTH)
    assertThat(recalculateLayoutForMinimalWidth).isEqualTo(true)
  }

  @Test
  fun testMinimallyWideThresholdText() {
    val textStyle = TextStyle()
    textStyle.minimallyWide = true
    textStyle.minimallyWideThreshold = FULL_TEXT_WIDTH - MINIMAL_TEXT_WIDTH
    val layout = setupWidthTestTextLayout()
    val resolvedWidth = TextMeasurementUtils.resolveWidth(UNSPECIFIED_WIDTH_SPEC, layout, textStyle)
    val layoutWidth = resolvedWidth.first
    val recalculateLayoutForMinimalWidth = resolvedWidth.second

    assertThat(layoutWidth).isEqualTo(FULL_TEXT_WIDTH)
    assertThat(recalculateLayoutForMinimalWidth).isEqualTo(false)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun testTruncateTextDoesNotBreakSurrogatePairs() {
    // The grinning face emoji (😀) is U+1F600, encoded as surrogate pair U+D83D U+DE00 in UTF-16.
    // "A😀B" is represented as ['A', '\uD83D', '\uDE00', 'B']
    val text = "A😀B"
    val customEllipsisText = "..."
    val textStyle = TextStyle()

    // Create a layout with a width that forces ellipsization
    val widthSpec = View.MeasureSpec.makeMeasureSpec(30, View.MeasureSpec.AT_MOST)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    val layout =
        TextMeasurementUtils.createTextLayout(
            renderCoreTestRule.context,
            textStyle,
            widthSpec,
            heightSpec,
            text,
        )

    val truncatedText =
        TextMeasurementUtils.truncateText(
            text,
            customEllipsisText,
            layout,
            0,
            30f,
            true,
            textStyle,
        )

    // Check that truncating removed the Emoji, but that it didn't truncate in
    // the middle of a surrogate pair in doing so. If is was a high surrogate
    // left in the resulting string, the string will not exactly match "A...".
    assertThat(truncatedText.toString()).isEqualTo("A...")
  }

  // Regression test: empty text yields a null Android Layout from
  // TextMeasurementUtils; the §4.5 min-content probe must return 0, not NPE.
  @Test
  fun minIntrinsicWidth_emptyText_returnsZero() {
    val primitive =
        RichTextPrimitive(id = 1, text = "", style = TextStyle(), hasIntrinsicSize = true)
    val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.AT_MOST)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

    val minWidth =
        primitive.calculateMinIntrinsicWidth(
            layoutContext(),
            SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec),
        )

    assertThat(minWidth).isEqualTo(0)
  }

  @Test
  fun minIntrinsicHeight_emptyText_returnsZero() {
    val primitive =
        RichTextPrimitive(id = 1, text = "", style = TextStyle(), hasIntrinsicSize = true)
    val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.AT_MOST)

    val minHeight =
        primitive.calculateMinIntrinsicHeight(
            layoutContext(),
            SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec),
        )

    assertThat(minHeight).isEqualTo(0)
  }

  private fun layoutContext(): LayoutContext<Any?> =
      LayoutContext(renderCoreTestRule.context, null, -1, LayoutCache(), null)

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `dynamic text color is drawn until cleared without changing the layout paint`() {
    val style =
        TextStyle().apply {
          textColor = Color.BLACK
          textSize = 40
        }
    renderCoreTestRule
        .useRootNode(RichTextPrimitive(id = 1, text = "WWWW", style = style))
        .setSizePx(300, 100)
        .render()
    val textView = (renderCoreTestRule.rootHost as HostView).getChildAt(0) as RCTextView
    val paint = checkNotNull(textView.layout).paint

    textView.setDynamicTextColor(Color.RED)
    assertThat(drawnTextColors(textView)).containsOnly(Color.RED)
    assertThat(paint.color).isEqualTo(Color.BLACK)

    textView.setDynamicTextColor(null)
    assertThat(drawnTextColors(textView)).containsOnly(Color.BLACK)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `the text is drawn between the background and the foreground`() {
    val style =
        TextStyle().apply {
          textColor = Color.BLACK
          textSize = 40
        }
    renderCoreTestRule
        .useRootNode(RichTextPrimitive(id = 1, text = "WWWW", style = style))
        .setSizePx(300, 100)
        .render()
    val textView = (renderCoreTestRule.rootHost as HostView).getChildAt(0) as RCTextView

    textView.background = ColorDrawable(Color.WHITE)
    textView.foreground = ColorDrawable(Color.argb(128, 255, 0, 0))

    val colors = drawnTextColors(textView)
    // Black text under the 50% red foreground blends to about rgb(128, 0, 0). Text drawn over the
    // foreground stays black; text under the background, or missing, leaves only white plus red.
    assertThat(colors).anyMatch {
      Color.red(it) in 96..160 && Color.green(it) == 0 && Color.blue(it) == 0
    }
    assertThat(colors).doesNotContain(Color.BLACK)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `a span that leaves the canvas scaled does not shrink the foreground`() {
    val leavesCanvasScaled =
        object : OnPrePostDrawSpan {
          override fun draw(
              canvas: Canvas,
              text: CharSequence,
              start: Int,
              end: Int,
              layout: Layout,
              drawCommand: OnPrePostDrawSpan.Command,
          ) {
            canvas.scale(0.5f, 1f)
            drawCommand.draw(canvas)
          }
        }
    val text =
        SpannableString("WWWW").apply {
          setSpan(leavesCanvasScaled, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    renderCoreTestRule
        .useRootNode(RichTextPrimitive(id = 1, text = text, style = TextStyle()))
        .setSizePx(300, 100)
        .render()
    val textView = (renderCoreTestRule.rootHost as HostView).getChildAt(0) as RCTextView
    textView.foreground = ColorDrawable(Color.RED)

    val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
    textView.draw(Canvas(bitmap))

    // The far corner is only red if the foreground still covers the full width.
    assertThat(bitmap.getPixel(299, 99)).isEqualTo(Color.RED)
  }

  // Colors of the fully opaque pixels drawn, ignoring transparent pixels and anti-aliased edges.
  private fun drawnTextColors(view: View): Set<Int> {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return opaqueColors(bitmap)
  }

  private fun opaqueColors(bitmap: Bitmap): Set<Int> {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels.filter { Color.alpha(it) == 255 }.toSet()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `a view drawn while another view with the same layout draws keeps its own color`() {
    val style =
        TextStyle().apply {
          textColor = Color.BLACK
          textSize = 40
        }
    var nested: RCTextView? = null
    // Made up front: a bitmap can't be created while another canvas is drawing.
    val nestedBitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
    val drawsNestedFirst =
        object : OnPrePostDrawSpan {
          override fun draw(
              canvas: Canvas,
              text: CharSequence,
              start: Int,
              end: Int,
              layout: Layout,
              drawCommand: OnPrePostDrawSpan.Command,
          ) {
            // The nested view runs this span too; draw it only from the outer one.
            val toDraw = nested
            nested = null
            toDraw?.draw(Canvas(nestedBitmap))
            drawCommand.draw(canvas)
          }
        }
    val text =
        SpannableString("WWWW").apply {
          setSpan(drawsNestedFirst, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    val textLayout =
        TextMeasurementUtils.layout(
                renderCoreTestRule.context,
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                text,
                style,
            )
            .second
    fun mounted(): RCTextView =
        RCTextView(renderCoreTestRule.context).apply {
          mount(textLayout)
          layout(0, 0, 300, 100)
        }
    val outer = mounted().apply { setDynamicTextColor(Color.RED) }
    nested = mounted()

    assertThat(drawnTextColors(outer)).containsOnly(Color.RED)
    assertThat(opaqueColors(nestedBitmap)).containsOnly(Color.BLACK)
    assertThat(textLayout.layout.paint.color).isEqualTo(Color.BLACK)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `a view mounted while another view with the same layout draws keeps its own color`() {
    assertNestedMountKeepsColors(TextStyle().apply { textColor = Color.BLACK })
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `a nested mount keeps colors with a color state list`() {
    assertNestedMountKeepsColors(
        TextStyle().apply { textColorStateList = ColorStateList.valueOf(Color.BLACK) },
    )
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `a nested mount keeps colors with no text color set`() {
    // No color and no state list: the layout is measured BLACK.
    assertNestedMountKeepsColors(
        TextStyle().apply {
          textColor = 0
          textColorStateList = null
        },
    )
  }

  // Both views share one layout. The outer one draws RED (dynamic) and mounts the nested one from a
  // span mid-draw; each must draw only its own color.
  private fun assertNestedMountKeepsColors(style: TextStyle) {
    style.textSize = 40
    var textLayout: TextMeasurementUtils.TextLayout? = null
    var nested: RCTextView? = null
    var mountNested = true
    val mountsNested =
        object : OnPrePostDrawSpan {
          override fun draw(
              canvas: Canvas,
              text: CharSequence,
              start: Int,
              end: Int,
              layout: Layout,
              drawCommand: OnPrePostDrawSpan.Command,
          ) {
            // Only from the outer view: the nested one runs this span too.
            if (mountNested) {
              mountNested = false
              nested =
                  RCTextView(renderCoreTestRule.context).apply {
                    mount(checkNotNull(textLayout))
                    layout(0, 0, 300, 100)
                  }
            }
            drawCommand.draw(canvas)
          }
        }
    val text =
        SpannableString("WWWW").apply {
          setSpan(mountsNested, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    textLayout =
        TextMeasurementUtils.layout(
                renderCoreTestRule.context,
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                text,
                style,
            )
            .second
    val outer =
        RCTextView(renderCoreTestRule.context).apply {
          mount(checkNotNull(textLayout))
          layout(0, 0, 300, 100)
          setDynamicTextColor(Color.RED)
        }

    assertThat(drawnTextColors(outer)).containsOnly(Color.RED)
    val mounted = checkNotNull(nested)
    assertThat(mounted.currentTextColor).isEqualTo(Color.BLACK)
    assertThat(drawnTextColors(mounted)).containsOnly(Color.BLACK)
  }

  @Test
  fun `a new dynamic text color redraws the view`() {
    renderCoreTestRule
        .useRootNode(RichTextPrimitive(id = 1, text = "Colored", style = TextStyle()))
        .setSizePx(100, 40)
        .render()
    val textView = (renderCoreTestRule.rootHost as HostView).getChildAt(0) as RCTextView
    val shadow = Shadows.shadowOf(textView)
    shadow.clearWasInvalidated()

    textView.setDynamicTextColor(Color.RED)

    assertThat(shadow.wasInvalidated()).isTrue()
  }

  @Test
  fun `text is clipped to the view bounds only when clipToBounds is set`() {
    assertThat(clipsWhenDrawn(clipToBounds = true)).isTrue()
    assertThat(clipsWhenDrawn(clipToBounds = false)).isFalse()
    assertThat(clipsWhenDrawn(clipToBounds = null)).isFalse()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `text is clipped inside the padding`() {
    assertThat(inkInBottomPadding(clipToBounds = true)).isZero()
    assertThat(inkInBottomPadding(clipToBounds = false)).isPositive()
  }

  /** Counts drawn pixels in the bottom padding of a short view whose text overflows. */
  private fun inkInBottomPadding(clipToBounds: Boolean): Int {
    val style =
        TextStyle().apply {
          textColor = Color.BLACK
          textSize = 20
          this.clipToBounds = clipToBounds
        }
    renderCoreTestRule
        .useRootNode(
            RichTextPrimitive(id = 1, text = "Line one\nLine two\nLine three", style = style),
        )
        .setSizePx(200, 40)
        .render()
    val textView = (renderCoreTestRule.rootHost as HostView).getChildAt(0) as RCTextView
    textView.setPadding(0, 0, 0, BOTTOM_PADDING_PX)
    val bitmap = Bitmap.createBitmap(textView.width, textView.height, Bitmap.Config.ARGB_8888)
    textView.draw(Canvas(bitmap))
    var ink = 0
    for (y in textView.height - BOTTOM_PADDING_PX until textView.height) {
      for (x in 0 until textView.width) {
        if (Color.alpha(bitmap.getPixel(x, y)) != 0) {
          ink++
        }
      }
    }
    return ink
  }

  private fun clipsWhenDrawn(clipToBounds: Boolean?): Boolean {
    val style = TextStyle().apply { clipToBounds?.let { this.clipToBounds = it } }
    renderCoreTestRule
        .useRootNode(RichTextPrimitive(id = 1, text = "Taller than its box", style = style))
        .setSizePx(100, 4)
        .render()
    val textView = (renderCoreTestRule.rootHost as HostView).getChildAt(0) as RCTextView
    val canvas: Canvas = mock()
    textView.draw(canvas)
    return mockingDetails(canvas).invocations.any { invocation ->
      invocation.method.name == "clipRect" &&
          invocation.arguments.toList() == listOf(0, 0, textView.width, textView.height)
    }
  }

  private fun setupWidthTestTextLayout(): Layout {
    val layout: Layout = mock()

    whenever(layout.lineCount).thenReturn(2)
    whenever(layout.width).thenReturn(FULL_TEXT_WIDTH)
    whenever(layout.getLineRight(any())).thenReturn(MINIMAL_TEXT_WIDTH.toFloat())
    whenever(layout.getLineLeft(any())).thenReturn(0.0f)

    return layout
  }

  companion object {
    private const val FULL_TEXT_WIDTH: Int = 100
    private const val BOTTOM_PADDING_PX: Int = 16
    private const val MINIMAL_TEXT_WIDTH: Int = 95
    private const val SPAN_LISTENER_CLICK_ACTION: String = "SPAN_LISTENER_CLICK_ACTION"
    private const val SPAN_LISTENER_TOUCH_ACTION: String = "SPAN_LISTENER_TOUCH_ACTION"
    private const val SPAN_CLICK_ACTION: String = "SPAN_CLICK_ACTION"
  }
}
