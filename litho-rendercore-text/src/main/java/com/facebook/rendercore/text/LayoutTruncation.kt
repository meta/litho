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
import androidx.annotation.VisibleForTesting

/**
 * Whether a text [Layout] draws all of its text, and what it draws when it doesn't, so every reader
 * of laid-out text reports truncation the same way.
 */
object LayoutTruncation {

  /** Whether the platform ellipsized any line of [layout] (a standard `ellipsize` truncation). */
  @JvmStatic
  fun isEllipsized(layout: Layout): Boolean {
    for (line in 0 until layout.lineCount) {
      if (layout.getEllipsisCount(line) > 0) {
        return true
      }
    }
    return false
  }

  /**
   * Whether [layout] does not show all of its text: an ellipsis is drawn, the platform's or a
   * custom one baked into the text ([isExplicitlyTruncated]), or the last line it shows ends before
   * the text does (clipped). Whitespace after that line does not count.
   *
   * @param shownLineCount how many of [layout]'s lines are shown: fewer than its line count when
   *   its view clips the rest, as a `TextView` does for `maxLines` without `ellipsize`.
   */
  @JvmStatic
  @JvmOverloads
  fun isTruncated(
      layout: Layout,
      isExplicitlyTruncated: Boolean,
      shownLineCount: Int = layout.lineCount,
  ): Boolean =
      isExplicitlyTruncated ||
          isEllipsized(layout) ||
          isClipped(layout, layout.text, shownLineCount)

  /**
   * Reconstructs the text actually drawn by the first [shownLineCount] lines of [layout], line by
   * line: on each line the platform ellipsis run (`getEllipsisStart`/`getEllipsisCount`) is
   * replaced with the ellipsis glyph, exactly the substitution the platform makes when drawing the
   * line. Lines with no ellipsis are copied verbatim, so a custom ellipsis baked into the text is
   * preserved — and a custom ellipsis the framework re-ellipsized is shown cut, as actually
   * rendered. When the text is clipped, the whitespace the last shown line ends with is dropped.
   */
  @JvmStatic
  @JvmOverloads
  fun buildDisplayedText(layout: Layout, shownLineCount: Int = layout.lineCount): CharSequence =
      buildDisplayedText(layout, layout.text, shownLineCount)

  /**
   * Line-walking core, split out so tests can hand it a mocked [Layout] whose offsets run past its
   * text, or a custom ellipsis the framework re-ellipsized, with the [text] supplied out-of-band:
   * [Layout.getText] is `final`, so a mock cannot return it. Production always passes `layout.text`
   * via the overload above. The clamp is a backstop only for AOSP `StaticLayout` defects (notably
   * Android 15/16) that return line offsets outside their own text — it degrades the reconstruction
   * rather than throwing IndexOutOfBoundsException.
   */
  @VisibleForTesting
  @JvmStatic
  fun buildDisplayedText(
      layout: Layout,
      text: CharSequence,
      shownLineCount: Int = layout.lineCount,
  ): CharSequence {
    val length = text.length
    val lineCount = shownLineCount.coerceIn(0, layout.lineCount)
    val builder = StringBuilder(length + lineCount)
    for (line in 0 until lineCount) {
      val lineStart = layout.getLineStart(line).coerceIn(0, length)
      val lineEnd = layout.getLineEnd(line).coerceIn(lineStart, length)
      val ellipsisCount = layout.getEllipsisCount(line)
      if (ellipsisCount == 0) {
        builder.append(text, lineStart, lineEnd)
      } else {
        val ellipsisStart = (lineStart + layout.getEllipsisStart(line)).coerceIn(lineStart, lineEnd)
        builder.append(text, lineStart, ellipsisStart)
        builder.append('…')
        builder.append(
            text,
            (ellipsisStart + ellipsisCount).coerceIn(ellipsisStart, lineEnd),
            lineEnd,
        )
      }
    }
    return if (isClipped(layout, text, lineCount)) builder.trimEnd().toString()
    else builder.toString()
  }

  private fun isClipped(layout: Layout, text: CharSequence, shownLineCount: Int): Boolean {
    val lineCount = shownLineCount.coerceAtMost(layout.lineCount)
    return lineCount > 0 && layout.getLineEnd(lineCount - 1) < trimmedLength(text)
  }

  /** The length of [text] without its trailing whitespace, without copying it. */
  private fun trimmedLength(text: CharSequence): Int {
    var end = text.length
    while (end > 0 && text[end - 1].isWhitespace()) {
      end--
    }
    return end
  }
}
