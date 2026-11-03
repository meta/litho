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

import android.view.Choreographer

/**
 * Schedules and cancels frame callbacks for [AnimationController].
 *
 * Production uses Android's [Choreographer], while tests can install a deterministic driver that
 * advances time explicitly.
 */
internal interface AnimationFrameDriver {

  /** Schedules [callback] for the next frame. Re-posting the same callback is allowed. */
  fun postFrameCallback(callback: Choreographer.FrameCallback)

  /** Cancels a previously scheduled [callback]. No-op if it is not scheduled. */
  fun removeFrameCallback(callback: Choreographer.FrameCallback)
}

internal fun interface FrameDrivenAnimation {
  /**
   * Ticks in system-scaled animation time and returns whether to stay registered. These timestamps
   * must not be compared with wall time; disabled owners may stay registered without ticking.
   */
  fun onFrame(frameTimeNanos: Long): Boolean
}

internal fun interface PostFrameCallback {
  /** Runs after the controller has applied this frame's animation invalidations. */
  fun onPostFrame()
}

/**
 * Default [AnimationFrameDriver] backed by Android's [Choreographer].
 *
 * The callback type is [Choreographer.FrameCallback], so this driver can delegate directly without
 * maintaining adapter state.
 */
internal object ChoreographerAnimationFrameDriver : AnimationFrameDriver {
  override fun postFrameCallback(callback: Choreographer.FrameCallback) {
    Choreographer.getInstance().postFrameCallback(callback)
  }

  override fun removeFrameCallback(callback: Choreographer.FrameCallback) {
    Choreographer.getInstance().removeFrameCallback(callback)
  }
}
