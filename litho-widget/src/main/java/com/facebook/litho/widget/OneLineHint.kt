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

import android.graphics.Typeface
import android.text.Layout
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.ceil

/**
 * A mounted text input's hint cut to one ellipsized line, for `isHintOnOneLine`. Main thread only:
 * it keeps its last cut and cuts again only when the width, the hint, the text size or the typeface
 * changes.
 */
internal class OneLineHint {
  private var hint: CharSequence? = null
  private var availableWidth = 0
  private var textSize = 0f
  private var typeface: Typeface? = null
  private var cut: CharSequence? = null

  /** Sets [view]'s hint to [hint] cut to one line at [width], the width [view] is laid out at. */
  fun applyTo(view: TextView, hint: CharSequence, width: Int) {
    val paint = view.paint
    val availableWidth = width - view.compoundPaddingLeft - view.compoundPaddingRight
    if (
        availableWidth != this.availableWidth ||
            paint.textSize != textSize ||
            paint.typeface != typeface ||
            hint != this.hint
    ) {
      this.hint = hint
      this.availableWidth = availableWidth
      textSize = paint.textSize
      typeface = paint.typeface
      cut = cutToOneLine(hint, view, width)
    }
    if (!view.hint.contentEquals(cut)) {
      view.hint = cut
    }
  }
}

/**
 * [hint] ellipsized at its end to fit one line of [view] at [width], the width [view] is laid out
 * at, less its compound padding. TextView keeps a hint on one line only when its width, rounded up,
 * fits, so a cut that measures a fraction of a pixel over is cut again a pixel narrower.
 */
internal fun cutToOneLine(hint: CharSequence, view: TextView, width: Int): CharSequence {
  val availableWidth = width - view.compoundPaddingLeft - view.compoundPaddingRight
  if (availableWidth <= 0) {
    return hint
  }
  val paint = view.paint
  var budget = availableWidth.toFloat()
  var cut = TextUtils.ellipsize(hint, paint, budget, TextUtils.TruncateAt.END)
  while (cut.isNotEmpty() && ceil(Layout.getDesiredWidth(cut, paint)) > availableWidth) {
    budget -= 1f
    cut = TextUtils.ellipsize(hint, paint, budget, TextUtils.TruncateAt.END)
  }
  return cut
}

/** Gives [info] the whole [hint], where the input shows it cut. */
internal fun describeWholeHint(
    info: AccessibilityNodeInfo,
    hint: CharSequence,
    isShowingHint: Boolean,
) {
  val node = AccessibilityNodeInfoCompat.wrap(info)
  if (isShowingHint) {
    node.text = hint
  }
  node.hintText = hint
}
