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

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The ellipsis tests drive a mocked [Layout] directly, so the custom-ellipsis cases (a baked-in
 * ellipsis, and the framework bug that re-ellipsizes it) are modelled deterministically. The
 * truncation-rule tests lay text out for real with native graphics, so lines break and clip as on a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutTruncationTest {

  @Test
  fun `isEllipsized is true when any line has a platform ellipsis`() {
    val layout =
        mock<Layout> {
          on { lineCount } doReturn 3
          on { getEllipsisCount(0) } doReturn 0
          on { getEllipsisCount(1) } doReturn 0
          on { getEllipsisCount(2) } doReturn 4
        }

    assertThat(LayoutTruncation.isEllipsized(layout)).isTrue()
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

    assertThat(LayoutTruncation.isEllipsized(layout)).isFalse()
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

    assertThat(LayoutTruncation.buildDisplayedText(layout, text).toString())
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

    assertThat(LayoutTruncation.buildDisplayedText(layout, text).toString()).isEqualTo(text)
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

    assertThat(LayoutTruncation.buildDisplayedText(layout, text).toString())
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

    assertThat(LayoutTruncation.buildDisplayedText(layout, text).toString())
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

    assertThat(LayoutTruncation.buildDisplayedText(layout, text).toString()).isEqualTo(text)
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
    assertThat(LayoutTruncation.buildDisplayedText(layout, text).toString())
        .isEqualTo("0123456789…")
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `a layout that draws all its text is not truncated`() {
    val layout = layoutOf(LONG_TEXT)

    assertThat(layout.lineCount).isGreaterThan(1)
    assertThat(LayoutTruncation.isTruncated(layout, isExplicitlyTruncated = false)).isFalse()
    assertThat(LayoutTruncation.buildDisplayedText(layout).toString()).isEqualTo(LONG_TEXT)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `an explicitly truncated layout is truncated`() {
    val layout = layoutOf("Trip recap… See more")

    assertThat(LayoutTruncation.isTruncated(layout, isExplicitlyTruncated = true)).isTrue()
    assertThat(LayoutTruncation.buildDisplayedText(layout).toString())
        .isEqualTo("Trip recap… See more")
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `an ellipsized layout is truncated`() {
    val layout = layoutOf(LONG_TEXT, maxLines = 1, ellipsize = true)

    assertThat(LayoutTruncation.isTruncated(layout, isExplicitlyTruncated = false)).isTrue()
    assertThat(LayoutTruncation.buildDisplayedText(layout).toString())
        .isEqualTo(LONG_TEXT.substring(0, layout.getEllipsisStart(0)) + "…")
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `a layout that does not ellipsize keeps every line, so maxLines alone does not truncate it`() {
    val layout = layoutOf(LONG_TEXT, maxLines = 1)

    assertThat(layout.lineCount).isGreaterThan(1)
    assertThat(LayoutTruncation.isTruncated(layout, isExplicitlyTruncated = false)).isFalse()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `text whose last shown line ends before the text does is truncated, without that line's trailing space`() {
    val layout = layoutOf(LONG_TEXT)
    val lineEnd = layout.getLineEnd(0)

    assertThat(layout.lineCount).isGreaterThan(1)
    assertThat(LONG_TEXT.codePointBefore(lineEnd)).isEqualTo(' '.code)
    assertThat(
        LayoutTruncation.isTruncated(layout, isExplicitlyTruncated = false, shownLineCount = 1),
    )
        .isTrue()
    assertThat(LayoutTruncation.buildDisplayedText(layout, shownLineCount = 1).toString())
        .isEqualTo(LONG_TEXT.substring(0, lineEnd - 1))
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun `whitespace after the last shown line is not truncation`() {
    val text = "Fits\n\n\n"
    val layout = layoutOf(text)

    assertThat(layout.getLineEnd(0)).isLessThan(text.length)
    assertThat(
        LayoutTruncation.isTruncated(layout, isExplicitlyTruncated = false, shownLineCount = 1),
    )
        .isFalse()
    assertThat(LayoutTruncation.buildDisplayedText(layout, shownLineCount = 1).toString())
        .isEqualTo("Fits\n")
  }

  private fun layoutOf(
      text: CharSequence,
      maxLines: Int = Int.MAX_VALUE,
      ellipsize: Boolean = false,
  ): Layout =
      StaticLayout.Builder.obtain(text, 0, text.length, TextPaint().apply { textSize = 30f }, 200)
          .setMaxLines(maxLines)
          .apply { if (ellipsize) setEllipsize(android.text.TextUtils.TruncateAt.END) }
          .build()

  private companion object {
    const val LONG_TEXT = "A sentence far too long to fit on one narrow line of text"
  }
}
