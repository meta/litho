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
import com.facebook.proguard.annotations.DoNotStrip

/**
 * An UI Element that contains textual content. This element can have more than one textual content.
 * For example, one [Mountable] wrapping a complex custom view can have multiple text views, and
 * therefore, multiple [TextContent.Item]
 *
 * It is responsibility of who implements the [Mountable] to implement this interface and provide
 * the correct properties.
 */
@DoNotStrip
interface TextContent {

  /** @return the list of text items that are rendered by this UI element. */
  @get:DoNotStrip val items: List<Item>

  /**
   * This is a helper method to retrieve the [CharSequence] that make up the textual content in this
   * given [TextContent]
   */
  val textList: List<CharSequence>

  interface Item {

    val text: CharSequence

    val textSize: Float

    val fontLineHeight: Float

    val typeface: Typeface?

    val color: Int

    val linesCount: Int

    val spannables: List<SpannableItem>

    /**
     * Whether this item's content is truncated — i.e. the text actually drawn ([displayedText])
     * differs from the complete text ([fullText]). Covers a standard `maxLines` + `ellipsize`
     * truncation and a custom ellipsis (e.g. "… See more"), including the framework bug that
     * re-ellipsizes a custom ellipsis.
     */
    val isTruncated: Boolean

    /**
     * The complete text the component was given, before any truncation. For a standard ellipsize
     * the drawn text keeps the full string (the ellipsis is applied only at draw time); for a
     * custom ellipsis the framework pre-truncates and bakes the ellipsis into the drawn text, so
     * [fullText] is the only place the original text survives.
     */
    val fullText: CharSequence

    /**
     * The text actually drawn on screen. For a standard ellipsize the ellipsized run is replaced
     * with the ellipsis glyph ("…"), reconstructed from the layout's per-line ellipsis metadata
     * (`getEllipsisStart`/`getEllipsisCount`) — exactly the substitution the platform makes when
     * drawing the line. For a custom ellipsis it is the pre-truncated text with the custom ellipsis
     * already baked in. Equals [fullText] when nothing is truncated.
     */
    val displayedText: CharSequence
  }

  interface SpannableItem {
    val className: String

    val hash: String

    val text: String

    val bounds: Rect
  }

  companion object {

    /**
     * The text of mounted [content] (for example a visibility event's content): Litho's legacy text
     * drawable, the text view of Litho's primitive Text, or any other [TextContent]. Null for other
     * content, including the text views of direct RichText users, which are not Litho Text.
     */
    @JvmStatic fun of(content: Any?): TextContent? = ComponentHostUtils.asTextContent(content)

    /**
     * The [Layout] Litho Text draws as [content], under the same rule as [of], whichever text
     * renderer is on. Null when [content] is not Litho Text or is not laid out.
     */
    @JvmStatic
    fun textLayoutOf(content: Any?): Layout? =
        (of(content)?.items?.singleOrNull() as? LayoutTextContentItem)?.layout
  }
}
