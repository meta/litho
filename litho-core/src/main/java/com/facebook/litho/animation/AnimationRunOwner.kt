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
import kotlin.time.Duration

/**
 * Owns an animation run's value claims and playback-aware waits, bridging the optional coroutine
 * module through callbacks. Run interruption releases the claims before requesting cancellation.
 */
@ExperimentalLithoApi
internal class AnimationRunOwner(
    playback: AnimationPlayback?,
    private val onInterruption: (AnimationEnd) -> Unit,
) {
  private var claimedValues: ArrayList<Animatable<*>>? = null
  private var holds: ArrayList<Hold>? = null
  private var holdDriver: AnimationDriver? = null
  var isReleased = false
    private set

  internal var playback = playback
    private set

  fun hold(duration: Duration, onEnd: () -> Unit): Hold {
    ThreadUtils.assertMainThread()
    check(!isReleased) { "The animation run has ended." }
    val durationNanos = duration.toFiniteNanos()
    val pendingHolds = holds ?: ArrayList<Hold>().also { holds = it }
    val driver =
        holdDriver
            ?: AnimationDriver(::advanceHolds).also { createdDriver ->
              createdDriver.bind(playback)
              holdDriver = createdDriver
            }
    driver.anchorInCompletionFrame()
    val hold = Hold(driver.playTimeNanos, durationNanos, onEnd)
    pendingHolds.add(hold)
    driver.updatePendingWork(true)
    return hold
  }

  fun cancelHold(hold: Hold) {
    ThreadUtils.assertMainThread()
    val pendingHolds = holds ?: return
    for (index in pendingHolds.lastIndex downTo 0) {
      if (pendingHolds[index] === hold) pendingHolds.removeAt(index)
    }
    holdDriver?.updatePendingWork(pendingHolds.isNotEmpty())
  }

  fun bind(playback: AnimationPlayback?) {
    ThreadUtils.assertMainThread()
    if (isReleased || this.playback === playback) return
    this.playback = playback
    val values = claimedValues.orEmpty()
    for (index in values.indices) values[index].bindRunPlayback(this, playback)
    holdDriver?.bind(playback)
  }

  fun release() {
    ThreadUtils.assertMainThread()
    release(null, AnimationEnd.Cancelled)
  }

  internal fun claim(value: Animatable<*>): Boolean {
    if (isReleased) return false
    val values = claimedValues ?: ArrayList<Animatable<*>>().also { claimedValues = it }
    values.add(value)
    return true
  }

  internal fun interrupt(value: Animatable<*>, outcome: AnimationEnd) {
    if (isReleased) return
    if (value.isHandoff) {
      value.releaseClaim(this, preserveAnimation = true, outcome)
      claimedValues?.remove(value)
      return
    }
    release(value, outcome)
    onInterruption(outcome)
  }

  /** Stops motion while retaining evaluator state for the replacement's first commands. */
  fun transferTo(replacement: AnimationRunOwner) {
    ThreadUtils.assertMainThread()
    if (isReleased) return
    if (replacement.isReleased) {
      release()
      return
    }
    check(replacement.claimedValues == null)
    isReleased = true
    clearHolds()
    val values = claimedValues
    claimedValues = null
    replacement.claimedValues = values
    if (values != null) {
      for (index in values.indices) values[index].transferClaim(this, replacement)
    }
    playback = null
  }

  fun freezeHandoffs() {
    if (isReleased) return
    val values = claimedValues.orEmpty()
    for (index in values.indices) values[index].freezeHandoff(this)
  }

  private fun clearHolds() {
    holdDriver?.unmount()
    holdDriver = null
    holds?.clear()
    holds = null
  }

  private fun release(replacement: Animatable<*>?, outcome: AnimationEnd) {
    if (isReleased) return
    isReleased = true
    clearHolds()
    val values = claimedValues.orEmpty()
    claimedValues = null
    for (index in values.indices) {
      val value = values[index]
      val takenOver = value === replacement
      value.releaseClaim(this, takenOver, if (takenOver) outcome else AnimationEnd.Cancelled)
    }
    playback = null
  }

  private fun advanceHolds(playTimeNanos: Long): Boolean {
    val pendingHolds = checkNotNull(holds)
    for (index in pendingHolds.lastIndex downTo 0) {
      val hold = pendingHolds[index]
      if (
          AnimationController.durationScale == 0f ||
              playTimeNanos - hold.originNanos >= hold.durationNanos
      ) {
        pendingHolds.removeAt(index)
        AnimationController.enqueueCompletion(hold)
      }
    }
    return pendingHolds.isNotEmpty()
  }

  internal inner class Hold(
      val originNanos: Long,
      val durationNanos: Long,
      val callback: () -> Unit,
  ) : PostFrameCallback {
    override fun onPostFrame() {
      if (!isReleased) callback()
    }
  }
}
