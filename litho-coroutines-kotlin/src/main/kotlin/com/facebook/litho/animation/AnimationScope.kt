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
import com.facebook.litho.ComponentScope
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.lithoTreeScope
import com.facebook.litho.onCleanup
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.rendercore.thread.utils.ThreadUtils
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Hook-owned animation runs. Suspending commands share ownership and playback; synchronous
 * Animatable controls remain external. Claims survive completed steps until the run ends. Taking
 * over a claimed value cancels the run. Launch and control are main-thread operations.
 */
@ExperimentalLithoApi
class AnimationScope internal constructor() {
  private var host: CoroutineScope? = null
  private var currentRun: AnimationRun? = null
  private var playback: AnimationPlayback? = null

  /**
   * Starts a sequence, cancelling its predecessor. Cancelling the returned [Job] stops owned
   * motion; joining it waits for the body and its children, including cleanup. Unused predecessor
   * values stop and retain velocity until the replacement ends. An unmounted host returns a
   * cancelled job. An explicit call from cancelled cleanup is still a new request.
   */
  @MainThread
  fun launchLatest(block: suspend CoroutineScope.() -> Unit): Job {
    ThreadUtils.assertMainThread()
    val parent = host
    if (parent == null || !parent.isActive) {
      return Job().also { it.cancel(CancellationException("Animation scope is not mounted")) }
    }
    val run = AnimationRun(playback)
    val release = {
      onMain {
        run.owner.release()
        if (currentRun === run) currentRun = null
      }
    }
    val job =
        parent.launch(run, start = CoroutineStart.LAZY) {
          try {
            val body = launch(block = block)
            body.join()
          } finally {
            release()
          }
        }
    run.job = job
    job.invokeOnCompletion { release() }
    if (job.isCancelled) return job
    val previous = currentRun
    currentRun = run
    previous?.owner?.transferTo(run.owner)
    previous?.cancel(AnimationEnd.Interrupted)
    job.start()
    run.owner.freezeHandoffs()
    return job
  }

  /** Cancels the current run, including its claims and pending holds. */
  @MainThread
  fun cancel() {
    ThreadUtils.assertMainThread()
    currentRun?.cancel()
  }

  internal fun mount(parent: CoroutineScope) {
    check(host == null)
    host = parent
  }

  internal fun bind(playback: AnimationPlayback?) {
    this.playback = playback
    currentRun?.owner?.bind(playback)
  }

  internal fun unmount() {
    host = null
    val previousRun = currentRun
    currentRun = null
    previousRun?.cancel()
  }
}

/** Returns a stable animation host, cancelled when this hook leaves the committed tree. */
@ExperimentalLithoApi
@Hook
fun ComponentScope.useAnimationScope(playback: AnimationPlayback? = null): AnimationScope {
  val animations = useState { AnimationScope() }.value
  val parent = checkNotNull(context.lithoTree).lithoTreeScope
  useEffect(parent) {
    animations.mount(parent)
    onCleanup { animations.unmount() }
  }
  useEffect(playback) {
    animations.bind(playback)
    null
  }
  return animations
}

/** Waits on animation playback time inside [AnimationScope.launchLatest]. */
@ExperimentalLithoApi
@MainThread
suspend fun wait(duration: Duration) {
  ThreadUtils.assertMainThread()
  require(duration.isFinite() && duration >= Duration.ZERO) {
    "Wait duration must be finite and nonnegative."
  }
  val context = currentCoroutineContext()
  context.ensureActive()
  val run = checkNotNull(context[AnimationRun]) { "wait must run inside launchLatest." }
  run.job.ensureActive()
  if (duration == Duration.ZERO) return
  suspendCancellableCoroutine<Unit> { continuation ->
    val hold = run.owner.hold(duration) { continuation.resume(Unit) }
    continuation.invokeOnCancellation {
      onMain {
        if (!run.job.isActive) run.cancel() else run.owner.cancelHold(hold)
      }
    }
  }
}

/**
 * Returns after natural completion or `finish()`. Replacement, stop, unmount, and coroutine
 * cancellation cancel this await. Inside [AnimationScope.launchLatest], a newer command in the same
 * run interrupts the older step; external takeover cancels the run. Consecutive awaits may have
 * dispatch/frame gaps; use `chain` for exact spec timing.
 */
@ExperimentalLithoApi
@MainThread
suspend fun <T> Animatable<T>.animateTo(target: T, spec: FiniteAnimationSpec<T>) {
  ThreadUtils.assertMainThread()
  val context = currentCoroutineContext()
  context.ensureActive()
  val run = context[AnimationRun]
  run?.job?.ensureActive()
  suspendCancellableCoroutine<Unit> { continuation ->
    val owner = run?.owner
    val callback: (AnimationEnd) -> Unit = { outcome ->
      if (outcome == AnimationEnd.Finished) continuation.resume(Unit)
      else if (continuation.isActive) continuation.cancel(animationCancellation(outcome))
    }
    val version = startCommand(owner, target, spec, callback)
    continuation.invokeOnCancellation {
      onMain {
        if (run != null && !run.job.isActive) run.cancel() else cancelCommand(owner, version)
      }
    }
  }
}

/**
 * Sets [target] without waiting for a frame, retaining ownership inside
 * [AnimationScope.launchLatest].
 */
@ExperimentalLithoApi
@MainThread
suspend fun <T> Animatable<T>.snapTo(target: T) {
  ThreadUtils.assertMainThread()
  val context = currentCoroutineContext()
  context.ensureActive()
  val run = context[AnimationRun]
  run?.job?.ensureActive()
  snapCommand(run?.owner, target)
}

@OptIn(ExperimentalLithoApi::class)
private class AnimationRun(playback: AnimationPlayback?) : AbstractCoroutineContextElement(Key) {
  lateinit var job: Job
  val owner = AnimationRunOwner(playback, ::cancel)

  fun cancel(outcome: AnimationEnd = AnimationEnd.Cancelled) {
    owner.release()
    if (!job.isCompleted && !job.isCancelled) job.cancel(animationCancellation(outcome))
  }

  companion object Key : CoroutineContext.Key<AnimationRun>
}

private fun animationCancellation(outcome: AnimationEnd) =
    CancellationException("Animation command $outcome")

private inline fun onMain(crossinline block: () -> Unit) {
  if (ThreadUtils.isMainThread) block()
  else Dispatchers.Main.immediate.dispatch(EmptyCoroutineContext, Runnable { block() })
}
