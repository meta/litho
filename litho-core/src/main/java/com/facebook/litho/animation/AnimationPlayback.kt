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

import androidx.annotation.MainThread
import androidx.collection.MutableScatterSet
import com.facebook.litho.ComponentScope
import com.facebook.litho.State
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.getUpdatedState
import com.facebook.litho.useState
import com.facebook.rendercore.thread.utils.ThreadUtils

/** The requested playback state, independent of whether any attached animation has work. */
enum class PlaybackState {
  Playing,
  Paused,
}

/**
 * Durable playback shared explicitly by mounted animation owners.
 *
 * [pause] freezes their published play time, values, and velocity. Retargets remain prepared while
 * paused; [play] starts prepared work, resumes existing work, and permits future runs.
 *
 * Bind through the `playback` parameter of the animated value, group, or container APIs. Each owner
 * stays attached until unmount or rebinding, including between runs. There is no implicit
 * inheritance. Control and diagnostics are main-thread operations.
 *
 * [state] from [useAnimationPlayback] is observable: reading it in render, layout, an effect or a
 * binder subscribes that scope, and [play] and [pause] update it. A controller nobody reads causes
 * no renders.
 */
@ExperimentalLithoApi
class AnimationPlayback
internal constructor(
    autoPlay: Boolean,
    private val observed: State<PlaybackState>? = null,
) {
  private val drivers = MutableScatterSet<AnimationDriver>()

  /** What drivers act on. Untracked, so binding an animation never subscribes to playback. */
  @Volatile
  internal var currentState: PlaybackState =
      if (autoPlay) PlaybackState.Playing else PlaybackState.Paused
    private set

  /**
   * The requested playback state. Reflects [play] and [pause] immediately, including before the
   * renders they schedule.
   */
  val state: PlaybackState
    get() {
      // Subscribes tracked readers; the value comes from currentState, which is never behind.
      observed?.value
      // TODO: Remove backing `currentState` completely if we ever rollout State read-your-writes.
      return currentState
    }

  /** Whether an attached owner is currently advancing, excluding prepared and paused work. */
  @get:MainThread
  val isRunning: Boolean
    get() = drivers.any { it.isTicking }

  /** Starts or resumes prepared work. Remains playing when all attached work settles. */
  @MainThread fun play(): Unit = updateState(PlaybackState.Playing)

  /** Freezes attached work and keeps future runs paused until [play]. */
  @MainThread fun pause(): Unit = updateState(PlaybackState.Paused)

  internal fun attach(driver: AnimationDriver) {
    drivers.add(driver)
  }

  internal fun detach(driver: AnimationDriver) {
    drivers.remove(driver)
  }

  private fun updateState(nextState: PlaybackState) {
    ThreadUtils.assertMainThread()
    if (currentState == nextState) return
    currentState = nextState
    observed?.update(nextState)
    drivers.forEach { it.reconcilePlayback() }
  }
}

/**
 * Returns a stable playback controller. [autoPlay] selects its initial state only; later changes
 * use [AnimationPlayback.play] and [AnimationPlayback.pause], usually from event callbacks. Read
 * [AnimationPlayback.state] where the UI depends on it, such as a Pause/Resume label.
 */
@ExperimentalLithoApi
@Hook
fun ComponentScope.useAnimationPlayback(autoPlay: Boolean = true): AnimationPlayback {
  val observed = useState { if (autoPlay) PlaybackState.Playing else PlaybackState.Paused }
  return getUpdatedState { AnimationPlayback(autoPlay, observed) }
}
