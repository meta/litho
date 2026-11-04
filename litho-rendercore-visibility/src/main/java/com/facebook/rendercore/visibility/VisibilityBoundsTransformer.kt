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

package com.facebook.rendercore.visibility

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

interface VisibilityBoundsTransformer {

  /**
   * Transforms the local visible rect of the host view to account for occlusions.
   *
   * @param host The host ViewGroup whose visibility bounds should be transformed.
   * @param outRect The output Rect to populate with transformed bounds. Implementations should
   *   modify this Rect in-place. If the host is fully occluded, the Rect should be set to empty via
   *   [Rect.setEmpty].
   */
  fun getTransformedLocalVisibleRect(host: ViewGroup, outRect: Rect)

  /**
   * Gets the viewport rect of the view.
   *
   * @param view The view to get viewport for.
   * @param outRect The output Rect to populate with viewport bounds. Implementations should modify
   *   this Rect in-place.
   */
  fun getViewportRect(view: View, outRect: Rect)

  fun shouldUseTransformedVisibleRect(visibilityOutput: VisibilityOutput): Boolean

  /**
   * Whether each visibility pass that runs records the transformed visible rect it used, so that
   * the next pass skips only if that rect has not changed. With the default, false, only passes
   * that find the local visible rect unchanged record it, and mount passes clear it. A later pass
   * can then compare against an older rect and wrongly skip: a row that one scroll frame carries
   * from visible to fully occluded, while its local visible rect stays the same, can keep counting
   * as visible. Return true to avoid this. The first unchanged pass after a mount then skips too,
   * as it does with no transformer.
   *
   * A tree records it if it has visibility outputs of its own or hosts nested trees. Only they read
   * it, so a tree with neither may keep an older rect, which changes only whether it skips.
   *
   * True also lets a fully visible, mounted host that moves by an offset or translation skip its
   * pass, as a host with no transformer can; only true keeps the recorded rect current.
   * BaseMountingView skips only when the last pass saw the whole host as its local rect, the
   * recorded transformed rect is the whole host or empty, and [getTransformedLocalVisibleRect]
   * still returns that rect. That call then runs for each such host on every scroll step, so it
   * must not allocate. A skipped step also skips incremental mount and the nested trees' passes. A
   * host whose layout places a nested tree outside it never skips: a parent that doesn't clip its
   * children shows that part.
   *
   * The skip makes the assumptions of the skip with no transformer. It checks the host only against
   * its parent's bounds, so it can miss the parent's padding or clip bounds, clipping by an
   * ancestor above the parent, and a scale or rotation, unless the transform clips the host as
   * [View.getGlobalVisibleRect] does and leaves it whole. A transform that leaves the host whole
   * must leave the nested trees inside it whole, and one that covers the host must cover them. The
   * skip reads only the host's own layout, so a nested tree must keep its own nested trees inside
   * it, and no translation may move a nested tree out of its host.
   */
  fun shouldRecordTransformedVisibleRectAtEveryPass(): Boolean = false
}
