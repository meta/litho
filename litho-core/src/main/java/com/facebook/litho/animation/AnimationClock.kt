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

package com.facebook.litho.animation

/** Owner-local time. Values can read time without access to scheduling or playback control. */
internal open class AnimationClock {
  @Volatile private var lastFrameTimeNanos = UNSET_FRAME_TIME

  var playTimeNanos: Long = 0L
    private set

  val isTicking: Boolean
    get() = lastFrameTimeNanos != UNSET_FRAME_TIME

  protected fun tick(frameTimeNanos: Long): Long {
    val previous = lastFrameTimeNanos
    if (previous != UNSET_FRAME_TIME) playTimeNanos += frameTimeNanos - previous
    lastFrameTimeNanos = frameTimeNanos
    return playTimeNanos
  }

  /** Preserves play time and re-anchors on the next tick. */
  protected fun freeze() {
    lastFrameTimeNanos = UNSET_FRAME_TIME
  }

  /** Sets the next tick's frame origin without advancing play time. */
  protected fun anchor(frameTimeNanos: Long) {
    lastFrameTimeNanos = frameTimeNanos
  }

  private companion object {
    const val UNSET_FRAME_TIME = Long.MIN_VALUE
  }
}
