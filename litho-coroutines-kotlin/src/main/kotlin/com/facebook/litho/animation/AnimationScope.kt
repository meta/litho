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
 * Animation runs tied to the lifetime of a [useAnimationScope] hook.
 *
 * Suspending [animateTo] and [snapTo] commands share ownership and optional playback. Values stay
 * owned between steps until the run ends. External takeover of an owned value cancels the whole
 * run. Launch and control operations must run on the main thread.
 */
@ExperimentalLithoApi
class AnimationScope internal constructor() {
  private var host: CoroutineScope? = null
  private var currentRun: AnimationRun? = null
  private var playback: AnimationPlayback? = null

  /**
   * Starts a new run, cancelling the previous one and preserving interruption velocity.
   *
   * Cancelling the returned [Job] stops owned motion immediately and cancels pending run waits,
   * including inside `NonCancellable`. Joining it waits for the body and its children, including
   * cleanup. Values unused by the replacement stop and retain velocity until that replacement ends.
   *
   * An unmounted hook returns a cancelled job. A call from cancellation cleanup is still a new
   * request.
   */
  @MainThread
  fun launchLatest(block: suspend AnimationRunScope.() -> Unit): Job {
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
            // Joining a child lets cancellation reach finally before NonCancellable cleanup ends.
            val body = launch { block(AnimationRunScope(coroutineContext)) }
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

  /** Cancels the current run and stops its owned motion. */
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

/**
 * Returns a stable host for [AnimationScope.launchLatest] runs. [playback] controls their
 * animations and waits. Removing the hook cancels its current run.
 */
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

/**
 * Coroutine receiver for a single [AnimationScope.launchLatest] run.
 *
 * Reuses the body's coroutine context and job. Children inherit ownership and playback. Run
 * commands check the calling coroutine's context and must execute on the main thread within this
 * run, including from nested coroutine scopes.
 */
@ExperimentalLithoApi
class AnimationRunScope internal constructor(override val coroutineContext: CoroutineContext) :
    CoroutineScope {
  private val run = checkNotNull(coroutineContext[AnimationRun])

  /**
   * Whether Android's animator duration scale is positive, read live without Litho state tracking.
   * Playback and cancellation do not affect this value. Use [awaitMotion] to wait for available
   * motion.
   */
  @get:MainThread
  val isMotionEnabled: Boolean
    get() = AnimationController.isMotionEnabled

  /**
   * Waits for [duration] in animation playback time, respecting pauses and duration scale.
   * [duration] must be finite and nonnegative. Zero returns immediately. With motion disabled, a
   * positive wait completes on the next frame that playback permits.
   */
  @MainThread
  suspend fun wait(duration: Duration) {
    activeContext()
    require(duration.isFinite() && duration >= Duration.ZERO) {
      "Wait duration must be finite and nonnegative."
    }
    if (duration == Duration.ZERO) return
    run.awaitHold { onEnd -> run.owner.hold(duration, onEnd) }
  }

  /**
   * Checks cancellation and waits until system motion is enabled and playback permits progress.
   * Returns immediately when available; otherwise suspends without polling.
   *
   * Does not pace loop iterations. Loops of immediate commands such as [snapTo] can block the main
   * thread. Use timed animations or a positive [wait] for pacing.
   */
  @MainThread
  suspend fun awaitMotion() {
    val context = activeContext()
    while (!run.owner.canAnimate) {
      run.awaitHold(run.owner::awaitMotion)
      context.ensureActive()
      run.job.ensureActive()
    }
  }

  private suspend fun activeContext(): CoroutineContext {
    ThreadUtils.assertMainThread()
    val context = currentCoroutineContext()
    context.ensureActive()
    run.job.ensureActive()
    check(context[AnimationRun] === run) {
      "Animation run commands must execute within their launchLatest run."
    }
    return context
  }
}

@OptIn(ExperimentalLithoApi::class)
private suspend inline fun AnimationRun.awaitHold(
    crossinline register: ((AnimationEnd) -> Unit) -> AnimationRunOwner.Hold,
) {
  suspendCancellableCoroutine<Unit> { continuation ->
    val hold = register { outcome ->
      if (outcome == AnimationEnd.Finished) continuation.resume(Unit)
      else if (continuation.isActive) continuation.cancel(animationCancellation(outcome))
    }
    continuation.invokeOnCancellation {
      onMain {
        owner.cancelHold(hold)
        if (!job.isActive) cancel()
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
 * Sets [target] immediately without suspending, while checking cancellation. Retains ownership
 * inside [AnimationScope.launchLatest] and also works outside a run.
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
