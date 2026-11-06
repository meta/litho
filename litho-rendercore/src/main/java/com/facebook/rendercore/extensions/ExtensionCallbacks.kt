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

package com.facebook.rendercore.extensions

import android.graphics.Rect
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RenderUnit

interface GapWorkerCallbacks<State> {

  fun onRegisterForPremount(extensionState: ExtensionState<State>, frameTimeMs: Long?)

  fun onUnregisterForPremount(extensionState: ExtensionState<State>)

  fun hasItemToMount(extensionState: ExtensionState<State>): Boolean

  fun premountNext(extensionState: ExtensionState<State>)
}

fun interface InformsMountCallback {

  fun canPreventMount(): Boolean
}

interface OnItemCallbacks<State> {

  fun beforeMountItem(
      extensionState: ExtensionState<State>,
      renderTreeNode: RenderTreeNode,
      index: Int,
  )

  /** Called after an item is mounted. */
  fun onMountItem(
      extensionState: ExtensionState<State>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  )

  /**
   * Called when an item is already mounted. If true, the old item will be unbound and the new item
   * will be rebound
   */
  fun shouldUpdateItem(
      extensionState: ExtensionState<State>,
      previousRenderUnit: RenderUnit<*>,
      previousLayoutData: Any?,
      nextRenderUnit: RenderUnit<*>,
      nextLayoutData: Any?,
  ): Boolean

  /** Called after an item is bound, after it gets mounted or updated. */
  fun onBindItem(
      extensionState: ExtensionState<State>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  )

  /** Called after an item is unbound. */
  fun onUnbindItem(
      extensionState: ExtensionState<State>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  )

  /** Called after an item is unmounted. */
  fun onUnmountItem(
      extensionState: ExtensionState<State>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  )

  fun onBoundsAppliedToItem(
      extensionState: ExtensionState<State>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
      changed: Boolean,
  )
}

fun interface VisibleBoundsCallbacks<State> {

  /** Called when the visible bounds of the Host change. */
  fun onVisibleBoundsChanged(
      extensionState: ExtensionState<State>,
      localVisibleRect: Rect?,
      reason: VisibilityChangeReason,
  )
}
