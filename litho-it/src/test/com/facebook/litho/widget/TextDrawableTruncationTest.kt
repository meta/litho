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

import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.facebook.litho.LayoutTextContentItem
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests for [TextDrawable]'s truncation extraction, which reconstructs the drawn text from the
 * layout's per-line ellipsis metadata (`getEllipsisStart`/`getEllipsisCount`) — the same
 * substitution the platform makes at draw time.
 *
 * These drive a mocked [Layout] directly because Robolectric's real [android.text.StaticLayout]
 * does not populate ellipsis state (the same limitation that keeps Litho's custom-ellipsis
 * instrumentation tests @Ignore'd, T146174263), and because a mock lets us model the
 * custom-ellipsis cases — both the normal baked-in ellipsis and the framework bug that
 * re-ellipsizes it — deterministically.
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextDrawableTruncationTest {

  @Test
  fun `isEllipsized is true when any line has a platform ellipsis`() {
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 3
          on { getEllipsisCount(0) } doReturn 0
          on { getEllipsisCount(1) } doReturn 0
          on { getEllipsisCount(2) } doReturn 4
        }

    assertThat(LayoutTextContentItem.isEllipsized(layout)).isTrue()
  }

  @Test
  fun `isEllipsized is false when no line has a platform ellipsis`() {
    // A baked-in custom ellipsis has no platform ellipsis metadata; truncation is signalled by the
    // component instead (isExplicitlyTruncated), not by this helper.
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 2
          on { getEllipsisCount(0) } doReturn 0
          on { getEllipsisCount(1) } doReturn 0
        }

    assertThat(LayoutTextContentItem.isEllipsized(layout)).isFalse()
  }

  @Test
  fun `buildDisplayedText substitutes the ellipsis glyph for a standard ellipsized run`() {
    // Standard ellipsize: `text` is the full string and the "…" exists only as layout metadata.
    val text = "0123456789ABCDEFGHIJ_HIDDEN"
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 1
          on { getLineStart(0) } doReturn 0
          on { getLineEnd(0) } doReturn text.length
          on { getEllipsisStart(0) } doReturn 20
          on { getEllipsisCount(0) } doReturn 7
        }

    assertThat(LayoutTextContentItem.buildDisplayedText(layout, text).toString())
        .isEqualTo("0123456789ABCDEFGHIJ…")
  }

  @Test
  fun `buildDisplayedText returns a baked-in custom ellipsis verbatim`() {
    // Custom-ellipsis (normal): Litho/Bloks/RenderCore pre-truncate and bake "… See more" into the
    // characters, leaving no ellipsis metadata. The drawn text is exactly the laid-out text.
    val text = "Weekend trip recap … See more"
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 1
          on { getLineStart(0) } doReturn 0
          on { getLineEnd(0) } doReturn text.length
          on { getEllipsisCount(0) } doReturn 0
        }

    assertThat(LayoutTextContentItem.buildDisplayedText(layout, text).toString()).isEqualTo(text)
  }

  @Test
  fun `buildDisplayedText reflects a custom ellipsis that the framework re-ellipsized`() {
    // Framework bug: the rebuilt layout over the baked "See more" text still has ellipsize set, so
    // the platform re-ellipsizes and cuts INTO the custom ellipsis. The metadata reappears and the
    // drawn text shows "See more" cut to "See m…" — differing from the baked-in `text`.
    val text = "Trip recap See more"
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 1
          on { getLineStart(0) } doReturn 0
          on { getLineEnd(0) } doReturn text.length
          on { getEllipsisStart(0) } doReturn 16
          on { getEllipsisCount(0) } doReturn 3
        }

    assertThat(LayoutTextContentItem.buildDisplayedText(layout, text).toString())
        .isEqualTo("Trip recap See m…")
  }

  @Test
  fun `buildDisplayedText joins multiple lines and ellipsizes only the truncated one`() {
    val text = "FirstSecondLINEDROP"
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 2
          on { getLineStart(0) } doReturn 0
          on { getLineEnd(0) } doReturn 5
          on { getEllipsisCount(0) } doReturn 0
          on { getLineStart(1) } doReturn 5
          on { getLineEnd(1) } doReturn text.length
          on { getEllipsisStart(1) } doReturn 6 // relative to the line start (offset 5)
          on { getEllipsisCount(1) } doReturn 8
        }

    assertThat(LayoutTextContentItem.buildDisplayedText(layout, text).toString())
        .isEqualTo("FirstSecond…")
  }

  @Test
  fun `buildDisplayedText clamps offsets when the layout spans more text than is supplied`() {
    // Regression for the RageShake crash (IndexOutOfBoundsException: start 0, end 14, s.length 10):
    // the layout was measured over a longer string than the mounted `text`, so its line offsets run
    // past `text`. Clamping must keep us in bounds rather than throwing.
    val text = "0123456789" // length 10
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 1
          on { getLineStart(0) } doReturn 0
          on { getLineEnd(0) } doReturn 14 // beyond text.length
          on { getEllipsisCount(0) } doReturn 0
        }

    assertThat(LayoutTextContentItem.buildDisplayedText(layout, text).toString()).isEqualTo(text)
  }

  @Test
  fun `buildDisplayedText clamps an ellipsis run that runs past the supplied text`() {
    val text = "0123456789" // length 10
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 1
          on { getLineStart(0) } doReturn 0
          on { getLineEnd(0) } doReturn 14 // beyond text.length
          on { getEllipsisStart(0) } doReturn 12 // beyond text.length
          on { getEllipsisCount(0) } doReturn 2
        }

    // Must not throw; the kept prefix is clamped to the available text and the glyph is appended.
    assertThat(LayoutTextContentItem.buildDisplayedText(layout, text).toString())
        .isEqualTo("0123456789…")
  }

  @Test
  fun `mounted item reports full text, drawn text, and truncated when explicitly truncated`() {
    // Custom-ellipsis shape: the mounted text is the pre-truncated string (with "… See more" baked
    // in), the original full text is supplied separately, and isExplicitlyTruncated is set. Uses a
    // real StaticLayout so the item's typography getters (which read layout.paint) work.
    val fullText = "Pacific Northwest Weekend Hiking Backpacking and Coastal Trail Adventurers"
    val mountedText = "Pacific Northwest Weekend Hiking… See more"
    val drawable = mountTextDrawable(mountedText, fullText = fullText, isExplicitlyTruncated = true)

    val item = drawable.items.single()
    assertThat(item.fullText.toString()).isEqualTo(fullText)
    assertThat(item.text.toString()).isEqualTo(mountedText)
    assertThat(item.displayedText.toString()).isEqualTo(mountedText)
    assertThat(item.isTruncated).isTrue()
  }

  @Test
  fun `mounted item reports not truncated when full text is shown and not ellipsized`() {
    val text = "Short group name"
    val drawable = mountTextDrawable(text, fullText = text, isExplicitlyTruncated = false)

    val item = drawable.items.single()
    assertThat(item.fullText.toString()).isEqualTo(text)
    assertThat(item.displayedText.toString()).isEqualTo(text)
    assertThat(item.isTruncated).isFalse()
  }

  @Test
  fun `displayedText reflects the laid-out text when the mounted text desyncs shorter`() {
    // The Android 15/16 StaticLayout desync: the layout was laid out over a LONGER string than the
    // mounted `text`. displayedText must report what was actually drawn (the layout's own text),
    // not the shorter stored text — and must not throw. This is the `start 0, end N, length M (<N)`
    // crash scenario; sourcing from `layout.text` makes the reported text correct, not just safe.
    // (A clamp-only fix would instead return the shorter mounted text here, which is wrong.)
    val laidOutText = "Pacific Northwest Weekend Hiking"
    val mountedText = "Pacific..."
    val drawable = mountTextDrawable(
        mountedText,
        fullText = mountedText,
        isExplicitlyTruncated = false,
        layoutText = laidOutText,
    )

    val item = drawable.items.single()
    assertThat(item.text.toString()).isEqualTo(mountedText)
    assertThat(item.displayedText.toString()).isEqualTo(laidOutText)
  }

  private fun mountTextDrawable(
      mountedText: CharSequence,
      fullText: CharSequence,
      isExplicitlyTruncated: Boolean,
      // The string the layout is laid out over. Defaults to the mounted text; pass a different
      // (e.g. longer) value to simulate a layout/text desync.
      layoutText: CharSequence = mountedText,
  ): TextDrawable {
    val paint = TextPaint().apply { textSize = 30f }
    // Use the deprecated StaticLayout constructor (API 1+) rather than StaticLayout.Builder
    // (API 23+): this module's minSdk is 21.
    @Suppress("DEPRECATION")
    val layout = StaticLayout(
        layoutText,
        paint,
        10_000,
        Layout.Alignment.ALIGN_NORMAL,
        /* spacingmult = */ 1f,
        /* spacingadd = */ 0f,
        /* includepad = */ false,
    )
    return TextDrawable().apply {
      mount(
          mountedText,
          layout,
          /* layoutTranslationX = */ 0f,
          /* layoutTranslationY = */ 0f,
          /* clipToBounds = */ false,
          /* colorStateList = */ null,
          /* userColor = */ Color.BLACK,
          /* highlightColor = */ 0,
          /* outlineWidth = */ 0f,
          /* outlineColor = */ 0,
          /* clickableSpans = */ null,
          /* imageSpans = */ null,
          /* spanListener = */ null,
          /* touchableSpanListener = */ null,
          /* textOffsetOnTouchListener = */ null,
          /* highlightStartOffset = */ -1,
          /* highlightEndOffset = */ -1,
          /* clickableSpanExpandedOffset = */ 0f,
          /* contextLogTag = */ null,
          fullText,
          isExplicitlyTruncated,
      )
    }
  }
}
