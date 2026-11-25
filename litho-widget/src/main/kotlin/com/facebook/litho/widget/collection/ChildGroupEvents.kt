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

package com.facebook.litho.widget.collection

import androidx.annotation.UiThread
import com.facebook.litho.ThreadUtils

/** The rows `[start, end)` of the flattened list that a child group and its nested groups own. */
internal class ChildGroupRange(
    val start: Int,
    val end: Int,
    val state: ChildGroupEventState,
    val onNearEnd: OnNearCallback?,
)

/**
 * What a child group's events last reported. It is kept in the group's hook state, so it is dropped
 * with the group and starts fresh when the group is added back. It is not synchronized, so it may
 * only be read and written on the main thread.
 */
internal class ChildGroupEventState {
  private var _nearEndFiredAtCount: Int = -1

  // The group's row count when its onNearEnd last fired, or -1 to re-arm it.
  var nearEndFiredAtCount: Int
    get() {
      ThreadUtils.assertMainThread()
      return _nearEndFiredAtCount
    }
    set(value) {
      ThreadUtils.assertMainThread()
      _nearEndFiredAtCount = value
    }
}

/** Turns a viewport change of the list into the events of each child group. */
@UiThread
internal fun List<ChildGroupRange>.dispatchViewportChanged(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
) {
  for (range in this) {
    range.onNearEnd?.let { range.checkNearEnd(it, firstVisibleIndex, lastVisibleIndex) }
  }
}

private fun ChildGroupRange.checkNearEnd(
    nearEnd: OnNearCallback,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
) {
  val count = end - start
  val isNearEnd =
      count > 0 &&
          lastVisibleIndex >= start &&
          firstVisibleIndex < end &&
          lastVisibleIndex >= end - 1 - nearEnd.offset
  if (!isNearEnd) {
    state.nearEndFiredAtCount = -1
  } else if (state.nearEndFiredAtCount != count) {
    state.nearEndFiredAtCount = count
    nearEnd.callback()
  }
}
