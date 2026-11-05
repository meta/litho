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

package com.facebook.rendercore

import androidx.annotation.Px
import com.facebook.rendercore.geometry.Point

/**
 * Represents the result of a Layout pass. A LayoutResult has a reference to its originating Node
 * and all the layout information needed to position the content of such Node.
 */
interface LayoutResult {

  /** @return the RenderUnit that should be rendered by this layout result. */
  val renderUnit: RenderUnit<*>?

  /**
   * @return layout specific data that was generated during the layout pass that created this
   *   LayoutResult.
   */
  val layoutData: Any?

  /** @return the number of children of this LayoutResult. */
  val childrenCount: Int

  /** @return the LayoutResult for the given child index */
  fun getChildAt(index: Int): LayoutResult

  /** @return the resolved X position for the Node */
  @Px fun getXForChildAtIndex(index: Int): Int

  /** @return the resolved Y position for the Node */
  @Px fun getYForChildAtIndex(index: Int): Int

  @get:Px val width: Int

  @get:Px val height: Int

  @get:Px val paddingTop: Int

  @get:Px val paddingRight: Int

  @get:Px val paddingBottom: Int

  @get:Px val paddingLeft: Int
}

fun LayoutResult.getRelativePositionOf(target: LayoutResult): Point {
  val calculator = LayoutResultPositionCalculator()
  calculator.calculate(root = this, target = target)

  return Point(calculator.x.toFloat(), calculator.y.toFloat())
}

internal class LayoutResultPositionCalculator constructor() {

  companion object {
    const val TARGET_NOT_FOUND_MESSAGE = "Target not found"
  }

  var x = 0
  var y = 0

  fun calculate(root: LayoutResult, target: LayoutResult) {
    val found = traverse(current = root, target = target)
    if (!found) {
      error(TARGET_NOT_FOUND_MESSAGE)
    }
  }

  fun traverse(current: LayoutResult, target: LayoutResult): Boolean {
    if (current == target) {
      return true
    }

    for (i in 0 until current.childrenCount) {
      val child = current.getChildAt(i)
      val index = current.getIndexOfChild(child)
      val xForChild = current.getXForChildAtIndex(index)
      val yForChild = current.getYForChildAtIndex(index)

      x = x + xForChild
      y = y + yForChild

      val found = traverse(current = child, target = target)
      if (found) {
        return true
      }

      x = x - xForChild
      y = y - yForChild
    }

    return false
  }
}

fun LayoutResult.getIndexOfChild(child: LayoutResult): Int {
  for (i in 0 until childrenCount) {
    if (getChildAt(i) === child) {
      return i
    }
  }

  error("Child not found")
}
