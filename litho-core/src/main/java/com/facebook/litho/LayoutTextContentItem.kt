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

package com.facebook.litho

import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import androidx.annotation.VisibleForTesting

/**
 * A [TextContent.Item] read from the [Layout] a text component draws, shared by Litho's legacy text
 * drawable and RenderCore's text view so both report text the same way.
 *
 * @param text the text that is mounted; for a custom ellipsis, the already-truncated string.
 * @param fullText the text the component was given, before any truncation.
 * @param isExplicitlyTruncated whether the component pre-truncated the text for a custom ellipsis.
 * @param color the color the text is drawn in, when it differs from the layout's paint.
 * @param isMounted whether [layout] is still the one the component draws; span bounds are empty
 *   once it isn't, as they were before this class was shared.
 */
class LayoutTextContentItem(
    internal val layout: Layout,
    override val text: CharSequence,
    override val fullText: CharSequence,
    isExplicitlyTruncated: Boolean,
    override val color: Int = layout.paint.color,
    private val isMounted: () -> Boolean = { true },
) : TextContent.Item {

  override val textSize: Float = layout.paint.textSize

  override val typeface: Typeface? = layout.paint.typeface

  override val fontLineHeight: Float =
      (layout.paint.getFontMetricsInt(null) * layout.spacingMultiplier) + layout.spacingAdd

  override val linesCount: Int = layout.lineCount

  override val isTruncated: Boolean = isExplicitlyTruncated || isEllipsized(layout)

  // Built from `layout.text`, the exact CharSequence the layout's offsets index into, so it
  // reflects what is drawn even if `text` has diverged (the Android 15/16 StaticLayout desync).
  // Only read by rare debug tooling, so deferred until first accessed.
  override val displayedText: CharSequence by lazy { buildDisplayedText(layout) }

  // Span ranges are read from `layout.text`, the text the layout's offsets index, so their bounds
  // and text always describe the same characters.
  override val spannables: List<TextContent.SpannableItem>
    get() {
      val spanned = layout.text as? Spanned ?: return emptyList()
      val result = ArrayList<TextContent.SpannableItem>()
      for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        if (start != -1 && end != -1 && start != end) {
          result.add(SpanItem(span, start, end))
        }
      }
      return result
    }

  private inner class SpanItem(
      private val span: Any,
      private val start: Int,
      private val end: Int,
  ) : TextContent.SpannableItem {

    override val className: String
      get() = span.javaClass.name

    override val hash: String
      get() = Integer.toHexString(span.hashCode())

    override val text: String
      get() = layout.text.subSequence(start, end).toString()

    override val bounds: Rect
      get() {
        if (!isMounted()) {
          return Rect(0, 0, 0, 0)
        }
        return try {
          spanBounds()
        } catch (e: IndexOutOfBoundsException) {
          Rect(0, 0, 0, 0)
        }
      }

    private fun spanBounds(): Rect {
      val startLine = layout.getLineForOffset(start)
      val endLine = layout.getLineForOffset(end)
      val startX = layout.getPrimaryHorizontal(start).toInt()
      val startY = layout.getLineTop(startLine)
      if (startLine == endLine) {
        return Rect(
            startX,
            startY,
            layout.getPrimaryHorizontal(end).toInt(),
            layout.getLineBottom(endLine),
        )
      }
      // A span over several lines may not be rectangular, so report only its first line, where
      // every point inside the bounds belongs to the span.
      return Rect(
          startX,
          startY,
          layout.getPrimaryHorizontal(layout.getLineEnd(startLine) - 1).toInt(),
          layout.getLineBottom(startLine),
      )
    }
  }

  companion object {

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
     * Reconstructs the text actually drawn by [layout], line by line: on each line the platform
     * ellipsis run (`getEllipsisStart`/`getEllipsisCount`) is replaced with the ellipsis glyph,
     * exactly the substitution the platform makes when drawing the line. Lines with no ellipsis are
     * copied verbatim, so a custom ellipsis baked into the text is preserved — and a custom
     * ellipsis the framework re-ellipsized is shown cut, as actually rendered.
     */
    @JvmStatic
    fun buildDisplayedText(layout: Layout): CharSequence = buildDisplayedText(layout, layout.text)

    /**
     * Line-walking core, split out so tests can drive a mocked [Layout] with the [text] supplied
     * out-of-band: Robolectric's real `StaticLayout` never populates ellipsis metadata, and
     * [Layout.getText] is `final` so a mock cannot return it. Production always passes
     * `layout.text` via the single-argument overload above. The clamp is a backstop only for AOSP
     * `StaticLayout` defects (notably Android 15/16) that return line offsets outside their own
     * text — it degrades the reconstruction rather than throwing IndexOutOfBoundsException.
     */
    @VisibleForTesting
    @JvmStatic
    fun buildDisplayedText(layout: Layout, text: CharSequence): CharSequence {
      val length = text.length
      val builder = StringBuilder(length + layout.lineCount)
      for (line in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(line).coerceIn(0, length)
        val lineEnd = layout.getLineEnd(line).coerceIn(lineStart, length)
        val ellipsisCount = layout.getEllipsisCount(line)
        if (ellipsisCount == 0) {
          builder.append(text, lineStart, lineEnd)
        } else {
          val ellipsisStart =
              (lineStart + layout.getEllipsisStart(line)).coerceIn(lineStart, lineEnd)
          builder.append(text, lineStart, ellipsisStart)
          builder.append('…')
          builder.append(
              text,
              (ellipsisStart + ellipsisCount).coerceIn(ellipsisStart, lineEnd),
              lineEnd,
          )
        }
      }
      return builder.toString()
    }
  }
}
