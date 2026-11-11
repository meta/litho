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

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Covers the per-thread state [TextLayoutIntrinsics] reuses across min-content probes: the
 * [TextLayoutIntrinsics.intrinsicTextPaint] cache and the line [java.text.BreakIterator] behind
 * [TextLayoutIntrinsics.longestWordWidth]. Runs in NATIVE graphics mode so measurements are real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextLayoutIntrinsicsCacheTest {

  // The JVM default, not the app locale, is what a TextPaint captures and what the tests change.
  @SuppressLint("BadMethodUse-java.util.Locale.getDefault")
  private val defaultLocale = Locale.getDefault()

  @After
  fun restoreLocale() {
    Locale.setDefault(defaultLocale)
  }

  private fun freshPaint(textSize: Float): TextPaint =
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize }

  /** Runs [block] on a new thread, which starts with empty probe state. */
  private fun <T> onNewThread(block: () -> T): T {
    var result: T? = null
    val thread = Thread { result = block() }
    thread.start()
    thread.join()
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  @Test
  fun intrinsicTextPaint_appliesRequestedProperties() {
    val paint =
        TextLayoutIntrinsics.intrinsicTextPaint(
            textSize = 30f,
            typeface = Typeface.DEFAULT_BOLD,
            letterSpacing = 0.1f,
        )

    assertThat(paint.textSize).isEqualTo(30f)
    assertThat(paint.letterSpacing).isEqualTo(0.1f)
    assertThat(paint.typeface).isEqualTo(Typeface.DEFAULT_BOLD)
    assertThat(paint.isAntiAlias).isTrue()
  }

  @Test
  fun intrinsicTextPaint_reusesOnePaintPerTypefaceOnAThread() {
    val first = TextLayoutIntrinsics.intrinsicTextPaint(24f, null, 0f)
    // Typeface.DEFAULT is what a fresh paint already uses, so it shares the null entry.
    val second = TextLayoutIntrinsics.intrinsicTextPaint(40f, Typeface.DEFAULT, 0.2f)
    val bold = TextLayoutIntrinsics.intrinsicTextPaint(24f, Typeface.DEFAULT_BOLD, 0f)

    assertThat(second).isSameAs(first)
    assertThat(second.textSize).isEqualTo(40f)
    assertThat(second.letterSpacing).isEqualTo(0.2f)
    assertThat(bold).isNotSameAs(first)
    assertThat(TextLayoutIntrinsics.intrinsicTextPaint(24f, Typeface.DEFAULT_BOLD, 0f))
        .isSameAs(bold)
  }

  @Test
  fun intrinsicTextPaint_isNotSharedAcrossThreads() {
    val mainThreadPaint = TextLayoutIntrinsics.intrinsicTextPaint(24f, null, 0f)
    val otherThreadPaint = onNewThread { TextLayoutIntrinsics.intrinsicTextPaint(24f, null, 0f) }

    assertThat(otherThreadPaint).isNotSameAs(mainThreadPaint)
  }

  @Test
  fun intrinsicTextPaint_rebuildsWhenDefaultLocaleChanges() {
    Locale.setDefault(Locale.US)
    val usPaint = TextLayoutIntrinsics.intrinsicTextPaint(24f, null, 0f)
    Locale.setDefault(Locale.JAPAN)
    val japanPaint = TextLayoutIntrinsics.intrinsicTextPaint(24f, null, 0f)

    assertThat(japanPaint).isNotSameAs(usPaint)
    assertThat(japanPaint.textLocale).isEqualTo(Locale.JAPAN)
  }

  @Test
  fun intrinsicTextPaint_measuresLikeAFreshPaintAfterOtherProbes() {
    val text = "a supercalifragilistic b"
    TextLayoutIntrinsics.longestWordWidth(
        text,
        TextLayoutIntrinsics.intrinsicTextPaint(48f, null, 0.3f),
    )

    assertThat(
        TextLayoutIntrinsics.longestWordWidth(
            text,
            TextLayoutIntrinsics.intrinsicTextPaint(24f, null, 0f),
        ),
    )
        .isEqualTo(TextLayoutIntrinsics.longestWordWidth(text, freshPaint(24f)))
  }

  @Test
  fun longestWordWidth_reusedIteratorMatchesAFreshOne() {
    val longWord = "a supercalifragilistic b"
    val shortWords = "tiny words only"
    val paint = freshPaint(24f)
    val shortWordsOnFreshThread = onNewThread {
      TextLayoutIntrinsics.longestWordWidth(shortWords, paint)
    }

    val first = TextLayoutIntrinsics.longestWordWidth(longWord, paint)
    val second = TextLayoutIntrinsics.longestWordWidth(shortWords, paint)
    val third = TextLayoutIntrinsics.longestWordWidth(longWord, paint)

    assertThat(second).isEqualTo(shortWordsOnFreshThread)
    assertThat(second).isLessThan(first)
    assertThat(third).isEqualTo(first)
  }

  @Test
  fun longestWordWidth_switchesIteratorWithPaintLocale() {
    val text = "a supercalifragilistic b"
    val usPaint = freshPaint(24f).apply { textLocale = Locale.US }
    val japanPaint = freshPaint(24f).apply { textLocale = Locale.JAPAN }

    val us = TextLayoutIntrinsics.longestWordWidth(text, usPaint)
    val japan = TextLayoutIntrinsics.longestWordWidth(text, japanPaint)

    assertThat(japan)
        .isEqualTo(onNewThread { TextLayoutIntrinsics.longestWordWidth(text, japanPaint) })
    assertThat(TextLayoutIntrinsics.longestWordWidth(text, usPaint)).isEqualTo(us)
  }
}
