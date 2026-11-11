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

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import java.text.BreakIterator
import java.text.StringCharacterIterator
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ceil

/**
 * Min-content intrinsic sizing helpers for text leaves, shared by the RichText Primitive and the
 * legacy Text MountSpec so both report the same min-content width.
 */
object TextLayoutIntrinsics {

  // Cap on the number of longest-by-character-count runs we actually measure. Mirrors androidx
  // Compose's LayoutIntrinsics (10 is an arbitrary small bound on the candidate set).
  private const val LONGEST_WORD_CANDIDATE_LIMIT = 10

  // Flat-topped capital whose glyph bounds sit at the cap line, used to measure cap height.
  private const val CAP_MEASUREMENT_TEXT = "T"

  // Distinct typefaces a thread keeps a paint for before the cache is dropped and rebuilt.
  private const val MAX_CACHED_PAINTS = 8

  private val threadState =
      object : ThreadLocal<ProbeState>() {
        override fun initialValue(): ProbeState = ProbeState()
      }

  /**
   * Returns a [TextPaint] for min-content measurement with [textSize], [typeface] and
   * [letterSpacing] applied: of the properties the real text layout sets, the only ones that change
   * glyph advances or font metrics (density and shadow don't).
   *
   * The paint is reused per thread and typeface, because building one (a native allocation plus
   * locale setup) and setting its typeface (a class-locked font load on some OEMs) costs more than
   * the measurement when the probe runs on the main thread. It is only valid until the next call on
   * the same thread, which overwrites its text size and letter spacing: pass it straight to one of
   * this object's measurements, and don't retain it, mutate it, or hold it across code that could
   * lay out text on this thread.
   */
  @JvmStatic
  fun intrinsicTextPaint(textSize: Float, typeface: Typeface?, letterSpacing: Float): TextPaint {
    val state = threadState.get()!!
    // A paint captures the default locale when built, as the real layout's paint does, so rebuild
    // the cached ones if it changes.
    val locale = Locale.getDefault()
    if (state.paintLocale != locale) {
      state.paints.clear()
      state.paintLocale = locale
    }
    // A fresh paint already measures with the framework default, so it needs no setTypeface.
    val key = typeface?.takeIf { it != Typeface.DEFAULT }
    var paint = state.paints[key]
    if (paint == null) {
      if (state.paints.size >= MAX_CACHED_PAINTS) {
        state.paints.clear()
      }
      paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
      if (key != null) {
        paint.typeface = key
      }
      state.paints[key] = paint
    }
    paint.textSize = textSize
    paint.letterSpacing = letterSpacing
    return paint
  }

  /**
   * Returns the min-content width of [text] in pixels: the width of its widest unbreakable run
   * ("longest word"), i.e. the smallest width at which the text lays out without breaking a word,
   * measured with [paint].
   *
   * We keep only the [LONGEST_WORD_CANDIDATE_LIMIT] longest runs by character count (a cheap proxy
   * for width) and measure just those, returning the max — the small candidate set absorbs the
   * cases where the run with the most characters isn't the widest in pixels.
   *
   * Runs are measured with [TextPaint.measureText] (pure glyph advances) rather than
   * [Layout.getDesiredWidth], which allocates a `MeasuredParagraph` and runs bidi analysis per call
   * — a cost heavy enough to ANR when this probe runs on the main thread during fast list
   * scrolling. [TextPaint.measureText] ignores spans, so it is only used when [text] has no
   * width-affecting ([MetricAffectingSpan]) span; text with size/style/typeface spans falls back to
   * the accurate [Layout.getDesiredWidth] path. Non-metric spans (color, click) don't change
   * advance width and stay on the fast path.
   */
  @JvmStatic
  fun longestWordWidth(text: CharSequence, paint: TextPaint): Int {
    if (text.isEmpty()) {
      return 0
    }

    val state = threadState.get()!!
    val iterator = state.lineBreakIterator(paint.textLocale)
    // Iterate the CharSequence directly rather than materializing it with toString().
    iterator.setText(CharSequenceCharacterIterator(text, 0, text.length))

    // Min-heap ordered by run length in characters; the head is the shortest current candidate.
    // A primitive Int comparison keeps the key unboxed (vs. compareBy).
    val candidates =
        PriorityQueue<Run>(LONGEST_WORD_CANDIDATE_LIMIT) { a, b -> a.length.compareTo(b.length) }
    try {
      var start = iterator.first()
      var end = iterator.next()
      while (end != BreakIterator.DONE) {
        val run = Run(start, end)
        val shortest = candidates.peek()
        if (candidates.size < LONGEST_WORD_CANDIDATE_LIMIT) {
          candidates.add(run)
        } else if (shortest != null && shortest.length < run.length) {
          candidates.poll()
          candidates.add(run)
        }
        start = end
        end = iterator.next()
      }
    } finally {
      // The iterator outlives this call, so drop the text: its spans can hold a Context.
      iterator.setText(state.emptyText)
    }

    // Fast path (no width-affecting span): paint.measureText avoids the per-run MeasuredParagraph +
    // bidi allocation that Layout.getDesiredWidth pays.
    val measureWithPaintOnly = !hasWidthAffectingSpans(text)
    var maxWidth = 0f
    for (run in candidates) {
      val runWidth =
          if (measureWithPaintOnly) {
            paint.measureText(text, run.start, run.endExclusive)
          } else {
            Layout.getDesiredWidth(text, run.start, run.endExclusive, paint)
          }
      maxWidth = maxOf(maxWidth, runWidth)
    }
    return ceil(maxWidth.toDouble()).toInt()
  }

  /**
   * True if [text] has a [MetricAffectingSpan] (size, scale, style, typeface, letter-spacing) that
   * changes advance width, so [TextPaint.measureText] — which measures with the base paint only and
   * ignores spans — would be wrong and [Layout.getDesiredWidth] must be used. Non-metric spans
   * (color, click/URL) leave advance width unchanged and return false.
   */
  private fun hasWidthAffectingSpans(text: CharSequence): Boolean =
      text is Spanned && text.getSpans(0, text.length, MetricAffectingSpan::class.java).isNotEmpty()

  /**
   * Height of a single line of text from [paint]'s font metrics, without building a [Layout]:
   * `bottom - top` when [includeFontPadding], else `descent - ascent`.
   *
   * Line spacing is excluded because it applies between lines, not to a lone line: `spacingExtra`,
   * `spacingMultiplier`, and a fixed line height all leave a single line's height unchanged. The
   * first-line multiplier exception is [firstLineExtraSpacing]; manual cap/baseline spacing is
   * [singleLineHeightWithManualSpacing].
   */
  @JvmStatic
  fun singleLineHeight(paint: TextPaint, includeFontPadding: Boolean): Int {
    val fm = paint.fontMetricsInt
    return if (includeFontPadding) fm.bottom - fm.top else fm.descent - fm.ascent
  }

  /**
   * Extra height a [lineHeightMultiplier] > 1 adds to a line: `(descent - ascent) * (multiplier -
   * 1)`, truncated; 0 when the multiplier is <= 1. Only the first line is grown this way, so
   * callers add it to [singleLineHeight] separately.
   */
  @JvmStatic
  fun firstLineExtraSpacing(paint: TextPaint, lineHeightMultiplier: Float): Int {
    if (lineHeightMultiplier <= 1f) {
      return 0
    }
    val fm = paint.fontMetricsInt
    return ((fm.descent - fm.ascent) * (lineHeightMultiplier - 1f)).toInt()
  }

  /**
   * Single-line height when cap/baseline spacing is set manually: the visible cap height of "T"
   * plus [manualCapSpacing] and [manualBaselineSpacing], which replace the font's natural above-cap
   * and below-baseline gaps. Uses [paint]'s metrics only, without building a [Layout].
   */
  @JvmStatic
  fun singleLineHeightWithManualSpacing(
      paint: TextPaint,
      manualCapSpacing: Int,
      manualBaselineSpacing: Int,
  ): Int {
    val capBounds = Rect()
    paint.getTextBounds(CAP_MEASUREMENT_TEXT, 0, CAP_MEASUREMENT_TEXT.length, capBounds)
    return capBounds.height() + manualCapSpacing + manualBaselineSpacing
  }

  /**
   * A break-iterator run spanning [start] (inclusive) to [endExclusive] (exclusive) — the same
   * half-open convention as [BreakIterator] boundaries and [Layout.getDesiredWidth]'s `end`.
   */
  private class Run(val start: Int, val endExclusive: Int) {
    val length: Int = endExclusive - start
  }

  /** Per-thread scratch objects reused across probes; none of them are thread-safe. */
  private class ProbeState {
    var paintLocale: Locale? = null
    val paints: HashMap<Typeface?, TextPaint> = HashMap()
    val emptyText: StringCharacterIterator = StringCharacterIterator("")

    private var lineBreakIterator: BreakIterator? = null
    private var lineBreakLocale: Locale? = null

    /** [BreakIterator.getLineInstance] clones an ICU iterator on every call, so keep one. */
    fun lineBreakIterator(locale: Locale): BreakIterator {
      val cached = lineBreakIterator
      if (cached != null && lineBreakLocale == locale) {
        return cached
      }
      return BreakIterator.getLineInstance(locale).also { iterator ->
        lineBreakIterator = iterator
        lineBreakLocale = locale
      }
    }
  }
}
