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

package com.facebook.rendercore.incrementalmount

/**
 * How a tree uses the gap worker, including how the worker picks its work each frame. Each mode
 * includes the one above it; the mode alone decides whether the tree takes the cost-aware route.
 */
enum class GapWorkerMode {
  /** No gap worker: normal incremental mount. */
  OFF,
  /** Gap worker premounts ahead; nothing off screen is unmounted until teardown. */
  PREMOUNT,
  /** [PREMOUNT], always through the cost-aware route, which fits work into each frame's budget. */
  COST_AWARE_PREMOUNT,
  /**
   * [COST_AWARE_PREMOUNT], plus items the user has seen are unmounted as they leave the screen.
   * Always cost-aware: only that route lets premounted items be marked seen when they scroll in.
   */
  COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
  /**
   * [COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT], plus the gap worker empties a tree that
   * scrolled up past the top of the viewport and was then removed from its parent, such as a
   * RecyclerBinder row moved into the RecyclerView view cache: leaves first, then hosts once they
   * are empty, including premounted items that were never seen. Recycling the row later then has
   * almost nothing left to unmount inside a scroll frame. In lists that do not scroll content
   * upward this behaves like [COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT].
   */
  COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN,
  ;

  internal val requestsCostAwareRoute: Boolean
    get() = this == COST_AWARE_PREMOUNT || unmountsIncrementally

  internal val unmountsIncrementally: Boolean
    get() =
        this == COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT ||
            this == COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN

  internal val drainsRemovedTrees: Boolean
    get() = this == COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN
}
