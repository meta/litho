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

package com.facebook.litho.layout

import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints

/**
 * A child that has been measured and knows its size, but not yet where it sits.
 *
 * Only the framework can make one — the constructor is internal — which is what lets
 * [PlacementScope.place] trust its child. `place` is an extension on this type rather than a
 * function taking an index, so the index travels with the placeable and cannot be fabricated to
 * place a child at the wrong slot.
 */
@ExperimentalLithoApi
class Placeable
internal constructor(
    /**
     * The child measured. Framework-only: its index is how `place` finds a slot, and its owner the
     * only container that may place it.
     */
    internal val measurable: Measurable,
    /** The constraints this child was measured under. */
    internal val constraints: SizeConstraints,
    /** What the child measured, margins included, kept the moment its measure returned. */
    measured: IntSize,
    /** The box the child was measured in, which it's built in if it's placed. */
    internal val box: BoxConstraints,
) {

  /**
   * The width this child asked for, margins included, before it was kept within its constraints.
   * Differs from [width] only when the child does not fit them: it then starts at the start edge of
   * [width], and falls short of or overflows its end.
   */
  val measuredWidth: Int = measured.width

  /** The height this child asked for. The other axis of [measuredWidth]. */
  val measuredHeight: Int = measured.height

  /**
   * The width this child occupies, margins included, within the constraints it was measured at.
   * [measuredWidth] is the child's own size, so place a child by [measuredWidth] to align it to the
   * end. A child's content stays within the constraints, as in Compose: its own size, min or max is
   * what can take [measuredWidth] past them. Under a Yoga parent, a `Layout` instead answers the
   * size its function declares past an at-most room, as a Yoga `Column` that wraps its content
   * does.
   */
  val width: Int = measuredWidth.coerceIn(constraints.minWidth, constraints.maxWidth)

  /**
   * The height this child occupies, margins included, within the constraints it was measured at.
   */
  val height: Int = measuredHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

  /** Where the child's border box sits inside [measuredWidth]: its left and top margins. */
  internal val marginLeft: Int
    get() = box.margins.left

  internal val marginTop: Int
    get() = box.margins.top
}
