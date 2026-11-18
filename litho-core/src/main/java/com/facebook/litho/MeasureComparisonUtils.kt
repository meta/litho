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

/**
 * An utility class to verify that an old measured size is still compatible to be used with a new
 * measureSpec.
 */
object MeasureComparisonUtils {
  /**
   * Check if a cached nested tree has compatible SizeSpec to be reused as is or if it needs to be
   * recomputed.
   *
   * The conditions to be able to re-use previous measurements are: 1) The measureSpec is the
   * same 2) The new measureSpec is EXACTLY and the last measured size matches the measureSpec size.
   * 3) The old measureSpec is UNSPECIFIED, the new one is AT_MOST and the old measured size is
   *    smaller that the maximum size the new measureSpec will allow. 4) Both measure specs are
   *    AT_MOST. The old measure spec allows a bigger size than the new and the old measured size is
   *    smaller than the allowed max size for the new sizeSpec.
   */
  @JvmStatic
  fun hasCompatibleSizeSpec(
      oldWidthSpec: Int,
      oldHeightSpec: Int,
      newWidthSpec: Int,
      newHeightSpec: Int,
      oldMeasuredWidth: Int,
      oldMeasuredHeight: Int,
  ): Boolean {
    val widthIsCompatible = isMeasureSpecCompatible(oldWidthSpec, newWidthSpec, oldMeasuredWidth)
    val heightIsCompatible =
        isMeasureSpecCompatible(oldHeightSpec, newHeightSpec, oldMeasuredHeight)
    return widthIsCompatible && heightIsCompatible
  }

  private fun newSizeIsExactAndMatchesOldMeasuredSize(
      newSizeSpecMode: Int,
      newSizeSpecSize: Int,
      oldMeasuredSize: Int,
  ): Boolean = newSizeSpecMode == SizeSpec.EXACTLY && newSizeSpecSize == oldMeasuredSize

  private fun oldSizeIsUnspecifiedAndStillFits(
      oldSizeSpecMode: Int,
      newSizeSpecMode: Int,
      newSizeSpecSize: Int,
      oldMeasuredSize: Int,
  ): Boolean =
      newSizeSpecMode == SizeSpec.AT_MOST &&
          oldSizeSpecMode == SizeSpec.UNSPECIFIED &&
          newSizeSpecSize >= oldMeasuredSize

  /**
   * Whether a size measured under an "at most" spec is still what a stricter "at most" spec gives:
   * the new limit is lower than the old one and the measured size fits it.
   *
   * @param oldSizeSpecMode the mode of the spec the size was measured under.
   * @param newSizeSpecMode the mode of the new spec.
   * @param oldSizeSpecSize the limit of the spec the size was measured under.
   * @param newSizeSpecSize the limit of the new spec.
   * @param oldMeasuredSize the size measured under the old spec.
   * @return true when both specs are AT_MOST, the new limit is lower, and the size fits it.
   */
  @JvmStatic
  fun newMeasureSizeIsStricterAndStillValid(
      oldSizeSpecMode: Int,
      newSizeSpecMode: Int,
      oldSizeSpecSize: Int,
      newSizeSpecSize: Int,
      oldMeasuredSize: Int,
  ): Boolean =
      oldSizeSpecMode == SizeSpec.AT_MOST &&
          newSizeSpecMode == SizeSpec.AT_MOST &&
          oldSizeSpecSize > newSizeSpecSize &&
          oldMeasuredSize <= newSizeSpecSize

  @JvmStatic
  fun areMeasureSpecsEquivalent(specA: Int, specB: Int): Boolean =
      specA == specB ||
          (SizeSpec.getMode(specA) == SizeSpec.UNSPECIFIED &&
              SizeSpec.getMode(specB) == SizeSpec.UNSPECIFIED)

  @JvmStatic
  fun isMeasureSpecCompatible(oldSizeSpec: Int, sizeSpec: Int, oldMeasuredSize: Int): Boolean {
    val newSpecMode = SizeSpec.getMode(sizeSpec)
    val newSpecSize = SizeSpec.getSize(sizeSpec)
    val oldSpecMode = SizeSpec.getMode(oldSizeSpec)
    val oldSpecSize = SizeSpec.getSize(oldSizeSpec)
    return oldSizeSpec == sizeSpec ||
        (oldSpecMode == SizeSpec.UNSPECIFIED && newSpecMode == SizeSpec.UNSPECIFIED) ||
        newSizeIsExactAndMatchesOldMeasuredSize(newSpecMode, newSpecSize, oldMeasuredSize) ||
        oldSizeIsUnspecifiedAndStillFits(oldSpecMode, newSpecMode, newSpecSize, oldMeasuredSize) ||
        newMeasureSizeIsStricterAndStillValid(
            oldSpecMode,
            newSpecMode,
            oldSpecSize,
            newSpecSize,
            oldMeasuredSize,
        )
  }
}
