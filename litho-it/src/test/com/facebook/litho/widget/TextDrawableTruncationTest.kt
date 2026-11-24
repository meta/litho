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
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Tests that a mounted [TextDrawable]'s text item reports its full text, the text it draws, and
 * whether it is truncated. The rule itself is tested in `LayoutTruncationTest` (rendercore-text).
 */
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TextDrawableTruncationTest {

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
