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

import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.rendercore.thread.utils.ThreadUtils

/** One owner's frame registration and playback binding, sharing its time with owned values. */
@OptIn(ExperimentalLithoApi::class)
internal abstract class AnimationDriver : AnimationClock(), FrameDrivenAnimation {
  private var playback: AnimationPlayback? = null
  private var hasPendingWork = false

  final override fun onFrame(frameTimeNanos: Long): Boolean {
    if (!hasPendingWork || playback?.state == PlaybackState.Paused) {
      freeze()
      return false
    }
    hasPendingWork = advance(tick(frameTimeNanos))
    val ticking = hasPendingWork && playback?.state != PlaybackState.Paused
    if (!ticking || AnimationController.durationScale == 0f) freeze()
    return ticking
  }

  protected abstract fun advance(playTimeNanos: Long): Boolean

  fun bind(playback: AnimationPlayback?) {
    ThreadUtils.assertMainThread()
    if (this.playback === playback) return
    this.playback?.detach(this)
    this.playback = playback
    playback?.attach(this)
    reconcilePlayback()
  }

  fun updatePendingWork(pending: Boolean, forceFrame: Boolean = false) {
    ThreadUtils.assertMainThread()
    hasPendingWork = pending
    reconcilePlayback(forceFrame)
  }

  fun stop() {
    updatePendingWork(false)
  }

  fun unmount() {
    stop()
    playback?.detach(this)
    playback = null
  }

  internal fun reconcilePlayback(forceFrame: Boolean = false) {
    if (hasPendingWork && playback?.state != PlaybackState.Paused) {
      AnimationController.register(this, forceFrame)
    } else {
      AnimationController.unregister(this)
      freeze()
    }
  }
}

internal inline fun AnimationDriver(crossinline onAdvance: (Long) -> Boolean): AnimationDriver =
    object : AnimationDriver() {
      override fun advance(playTimeNanos: Long) = onAdvance(playTimeNanos)
    }
