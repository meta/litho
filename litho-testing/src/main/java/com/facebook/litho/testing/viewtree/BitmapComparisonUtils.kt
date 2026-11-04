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

package com.facebook.litho.testing.viewtree

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.drawToBitmap

/** Utility functions for comparing drawables and views by rendering them to bitmaps. */
object BitmapComparisonUtils {

  private const val DEFAULT_SIZE = 100

  /**
   * Draws both drawables to Bitmaps and compares them for equality.
   *
   * @param drawable1 the first drawable to compare
   * @param drawable2 the second drawable to compare
   * @return true if the drawables render identically
   */
  @JvmStatic
  fun drawablesMatch(drawable1: Drawable, drawable2: Drawable): Boolean {
    val width = maxOf(drawable1.intrinsicWidth, drawable2.intrinsicWidth, DEFAULT_SIZE)
    val height = maxOf(drawable1.intrinsicHeight, drawable2.intrinsicHeight, DEFAULT_SIZE)

    var bitmap1: Bitmap? = null
    var bitmap2: Bitmap? = null
    try {
      bitmap1 = drawable1.toBitmap(width, height)
      bitmap2 = drawable2.toBitmap(width, height)
      return bitmap1.sameAs(bitmap2)
    } finally {
      bitmap1?.recycle()
      bitmap2?.recycle()
    }
  }

  /**
   * Compares a view's rendered output to a drawable.
   *
   * @param view the view to compare (must be laid out with nonzero dimensions)
   * @param drawable the drawable to compare against
   * @return true if the view renders identically to the drawable
   * @throws IllegalStateException if the view has zero width or height
   */
  @JvmStatic
  fun viewMatchesDrawable(view: View, drawable: Drawable): Boolean {
    check(view.width > 0) { "View must have a nonzero width, but was ${view.width}" }
    check(view.height > 0) { "View must have a nonzero height, but was ${view.height}" }

    var viewBitmap: Bitmap? = null
    var drawableBitmap: Bitmap? = null
    try {
      viewBitmap = view.drawToBitmap()
      drawableBitmap = drawable.toBitmap(view.width, view.height)
      return viewBitmap.sameAs(drawableBitmap)
    } finally {
      viewBitmap?.recycle()
      drawableBitmap?.recycle()
    }
  }
}
