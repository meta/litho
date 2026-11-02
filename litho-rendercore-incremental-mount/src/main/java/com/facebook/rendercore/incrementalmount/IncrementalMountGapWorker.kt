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

package com.facebook.rendercore.incrementalmount

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Choreographer
import android.view.Display
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.facebook.rendercore.GapWorkerPremountScope
import com.facebook.rendercore.MountDelegate
import com.facebook.rendercore.RenderCoreConfig
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.GapWorkerCallbacks
import com.facebook.rendercore.incrementalmount.IncrementalMountExtension.IncrementalMountExtensionState
import com.facebook.rendercore.thread.utils.ThreadUtils.assertMainThread
import com.meta.systrace.Systracer
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** What one premount ask accomplished, and therefore what the gap worker should do next. */
internal enum class PremountResult {
  /** An item was mounted. Ask this delegate again. */
  MOUNTED,
  /** The next item was already mounted; the cursor moved past it. Ask this delegate again. */
  PROGRESSED,
  /**
   * Nothing was mounted, but this delegate still has work. Stop asking it this frame and keep it
   * registered so it is asked again on the next one.
   */
  YIELD,
  /** Lifecycle state changed during the ask. Keep the delegate registered for the next frame. */
  STALE,
  /** No premountable work is left here. Unregister this delegate. */
  EXHAUSTED,
}

internal interface PremountCostEstimator {
  fun estimateNs(contentType: Any): Long?

  fun record(contentType: Any, durationNs: Long)
}

/**
 * Predicts premount cost per content type from an exponentially weighted moving average, the shape
 * RecyclerView uses for its own prefetch estimates
 * (`RecyclerView.RecycledViewPool.runningAverage`).
 *
 * Weighted three to one toward history, so one slow mount moves the estimate by a quarter of its
 * excess rather than replacing it. The point is that it **decays**: an over-estimate here is
 * self-reinforcing, because an item rejected as too expensive is never mounted, so it is never
 * measured, so nothing would ever correct it. A decaying average recovers over the next few mounts
 * on its own.
 *
 * Typical cost, deliberately, not worst case. The frame margin already holds room back for a mount
 * that runs long, and skipping an item does not make its cost go away, it just moves back into the
 * next mount pass.
 */
@UiThread
internal class EwmaPremountCostEstimator : PremountCostEstimator {

  private val averagesByContentType = LruCache<Any, RunningAverage>(MAX_CONTENT_TYPES)

  override fun estimateNs(contentType: Any): Long? {
    assertMainThread()
    // Absence is the "never measured" signal, so callers get null rather than a zero sentinel.
    return averagesByContentType[contentType]?.valueNs
  }

  override fun record(contentType: Any, durationNs: Long) {
    assertMainThread()
    val average = averagesByContentType[contentType]
    if (average == null) {
      averagesByContentType.put(contentType, RunningAverage(durationNs))
    } else {
      average.add(durationNs)
    }
  }

  /** Mutable so a new sample updates in place, without boxing and re-inserting on every mount. */
  private class RunningAverage(var valueNs: Long) {
    fun add(sampleNs: Long) {
      valueNs = (valueNs / 4 * 3) + (sampleNs / 4)
    }
  }

  companion object {
    /**
     * The gap worker is a process-wide singleton, so this budget is shared by every mounting
     * surface at once, not spent per screen. Overflowing it is silent rather than harmful — evicted
     * content types report no estimate and are attempted unconditionally, which is the pre-existing
     * behaviour — but the estimator stops earning its keep, so the cap is set well above the
     * content types a single screen is expected to use.
     */
    @VisibleForTesting internal const val MAX_CONTENT_TYPES = 128
  }
}

class IncrementalMountGapWorker
private constructor(
    private var frameIntervalNs: Long,
    private val tracer: Systracer,
    private val nanoTime: () -> Long,
    private val estimator: PremountCostEstimator,
    private val premountDispatcher:
        (
            IncrementalMountExtension,
            ExtensionState<IncrementalMountExtensionState>,
            Long,
            Long,
            () -> Long,
            PremountCostEstimator,
        ) -> PremountResult,
) : Choreographer.FrameCallback, Runnable {

  private val handler: Handler = Handler(Looper.getMainLooper())
  private val delegates: MutableSet<MountDelegate> = mutableSetOf()
  // Trees whose gap worker mode asks for the cost-aware route.
  private val costAwareDelegates: MutableSet<MountDelegate> = mutableSetOf()

  private var latestFrameTimeNs: Long = 0

  /**
   * A label for the current frame, not a record of what happened in it. Starvation accounting above
   * stamps the items it passed over with this, so a counter rather than a drawing timestamp, which
   * can repeat or move backwards.
   */
  private var frameSequence: Long = 0

  private var isRunning: Boolean = false

  // Main-thread confined. A DisplayManager.DisplayListener would make this a cross-thread write.
  private var display: Display? = null

  /**
   * On every frame update the frame time. If there are items to mount then it also posts a
   * [Runnable] on the UI Thread to premount content during any gaps.
   */
  override fun doFrame(frameTimeNanos: Long) {
    latestFrameTimeNs = frameTimeNanos // update the frame time
    frameSequence++

    // Recomputing from a null Display would pin the interval to the 60Hz fallback.
    val currentDisplay = display
    if (
        currentDisplay != null &&
            RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    ) {
      frameIntervalNs = getFrameIntervalInNs(currentDisplay)
    }

    if (delegates.isNotEmpty()) {
      val isTracing = tracer.isTracing()
      if (isTracing) {
        tracer.beginSection("IncrementalMountGapWorker::doFrame")
      }
      handler.post(this)
      Choreographer.getInstance().postFrameCallback(this)
      if (isTracing) {
        tracer.endSection()
      }
    } else {
      isRunning = false
    }
  }

  override fun run() {
    val frameTimeNs = latestFrameTimeNs
    val currentFrameSequence = frameSequence
    premount(computeDeadlineNs(frameTimeNs), currentFrameSequence)
  }

  @VisibleForTesting fun computeDeadlineNs(): Long = computeDeadlineNs(latestFrameTimeNs)

  private fun computeDeadlineNs(frameTimeNs: Long): Long =
      frameTimeNs + frameIntervalNs - computeMarginNs(frameIntervalNs)

  /** The slice of the frame held back so the last premounted item has room to finish. */
  private fun computeMarginNs(frameIntervalNs: Long): Long {
    // Read at use time: a companion val would freeze at class-init and never see an MC ramp.
    val fraction = IncrementalMountExtensionConfigs.gapWorkerMarginFraction
    return if (fraction > 0.0) {
      // Capped at the frame length: a larger margin would put the deadline before the frame began.
      (frameIntervalNs * fraction).roundToLong().coerceAtMost(frameIntervalNs)
    } else {
      TimeUnit.MILLISECONDS.toNanos(IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs)
    }
  }

  private fun premount(deadlineNs: Long, frameSequence: Long) {
    val isTracing = tracer.isTracing()
    if (isTracing) {
      tracer.beginSection("premount")
    }
    try {
      // Iterated over a snapshot, not the live set: premountNext mounts content, and mounting
      // re-enters this worker's add/remove through onRegisterForPremount — a RecyclerBinder bound
      // or recycled inside a premount is enough. A live iterator sees that as concurrent
      // modification and throws on the UI thread.
      for (delegate in delegates.toList()) {
        if (hasReachedDeadline(deadlineNs)) {
          break
        }
        // A delegate unregistered earlier in this pass is no longer premountable, so skip it
        // rather than mount into a surface that has already gone away.
        if (delegate !in delegates) {
          continue
        }
        if (!premountNext(delegate, deadlineNs, frameSequence)) {
          delegates.remove(delegate)
          costAwareDelegates.remove(delegate)
        }
      }
    } finally {
      if (isTracing) {
        tracer.endSection()
      }
    }
  }

  /** returns `true` iff there are still items left to mount */
  private fun premountNext(
      delegate: MountDelegate,
      deadlineNs: Long,
      frameSequence: Long,
  ): Boolean {
    val isTracing = tracer.isTracing()
    if (isTracing) {
      tracer.beginSection("premount-item")
    }
    return try {
      GapWorkerPremountScope.with {
        premountViaActiveRoute(delegate, deadlineNs, frameSequence)
      }
    } finally {
      if (isTracing) {
        tracer.endSection()
      }
    }
  }

  /** Premounts one delegate through whichever routing path the config currently selects. */
  private fun premountViaActiveRoute(
      delegate: MountDelegate,
      deadlineNs: Long,
      frameSequence: Long,
  ): Boolean {
    val requestsCostAwareRoute = delegate in costAwareDelegates
    if (!requestsCostAwareRoute && !RenderCoreConfig.useGapWorkerExtensionState) {
      return premountDelegate(delegate, deadlineNs)
    }
    val extensionState = delegate.getGapWorkerExtensionState() ?: return false
    val extension = extensionState.extension
    if (requestsCostAwareRoute && extension is IncrementalMountExtension) {
      return premountCostAwareExtension(
          delegate,
          extensionState,
          extension,
          deadlineNs,
          frameSequence,
      )
    }
    return premountExtension(delegate, extensionState, deadlineNs)
  }

  private fun premountDelegate(delegate: MountDelegate, deadlineNs: Long): Boolean {
    while (delegate.hasItemToMount() && !hasReachedDeadline(deadlineNs)) {
      delegate.premountNext()
    }
    return delegate.hasItemToMount()
  }

  @Suppress("UNCHECKED_CAST")
  private fun premountCostAwareExtension(
      delegate: MountDelegate,
      extensionState: ExtensionState<Any>,
      extension: IncrementalMountExtension,
      deadlineNs: Long,
      frameSequence: Long,
  ): Boolean {
    val incrementalMountState =
        extensionState as ExtensionState<IncrementalMountExtension.IncrementalMountExtensionState>
    // Releases first: they are owed, while premounting is speculative.
    delegate.startNotifyVisibleBoundsChangedSection()
    try {
      extension.releaseQueuedOffscreenForGapWorker(incrementalMountState, deadlineNs, nanoTime)
    } finally {
      delegate.endNotifyVisibleBoundsChangedSection()
    }
    while (!hasReachedDeadline(deadlineNs)) {
      // One catch for the whole body: any throw leaves the pending sample behind, and a stale one
      // would be attributed to whatever the next ask mounts.
      val result: PremountResult
      val endNs: Long
      try {
        delegate.startNotifyVisibleBoundsChangedSection()
        result =
            try {
              premountDispatcher(
                  extension,
                  incrementalMountState,
                  deadlineNs,
                  frameSequence,
                  nanoTime,
                  estimator,
              )
            } finally {
              delegate.endNotifyVisibleBoundsChangedSection()
            }
        // Closed after the bracket so nested visible-bounds work the mount triggered is counted.
        endNs = nanoTime()
      } catch (throwable: Throwable) {
        extension.clearPendingPremountSample(incrementalMountState)
        throw throwable
      }
      val mountStartNs = extension.pendingPremountStartNs(incrementalMountState)
      val mountedContentType = extension.consumePendingPremountContentType(incrementalMountState)
      // The zero check is not redundant with the null check: both fields are set together today,
      // but a start of zero would record a sample of roughly the whole epoch. The average decays
      // a quarter per sample, so it would take dozens of mounts to recover — and the content type
      // would read as unaffordable throughout, so it would never get them.
      if (result == PremountResult.MOUNTED && mountedContentType != null && mountStartNs != 0L) {
        estimator.record(mountedContentType, (endNs - mountStartNs).coerceAtLeast(0L))
      }
      when (result) {
        PremountResult.MOUNTED,
        PremountResult.PROGRESSED -> continue
        PremountResult.STALE -> {
          extension.clearPendingPremountSample(incrementalMountState)
          return true
        }
        PremountResult.YIELD -> return true
        // Read now, not before premounting: a mount callback can have queued a release since.
        PremountResult.EXHAUSTED ->
            return extension.hasQueuedOffscreenReleases(incrementalMountState)
      }
    }
    return true
  }

  @Suppress("UNCHECKED_CAST")
  private fun premountExtension(
      delegate: MountDelegate,
      extensionState: ExtensionState<Any>,
      deadlineNs: Long,
  ): Boolean {
    val extension = extensionState.extension as GapWorkerCallbacks<Any>
    while (extension.hasItemToMount(extensionState) && !hasReachedDeadline(deadlineNs)) {
      delegate.startNotifyVisibleBoundsChangedSection()
      try {
        extension.premountNext(extensionState)
      } finally {
        delegate.endNotifyVisibleBoundsChangedSection()
      }
    }
    return extension.hasItemToMount(extensionState)
  }

  private fun hasReachedDeadline(deadlineNs: Long): Boolean = nanoTime() >= deadlineNs

  private fun startIfNotRunning(updatedFrameTimeMs: Long?) {
    if (updatedFrameTimeMs != null) {
      val updatedFrameTimeNs = TimeUnit.MILLISECONDS.toNanos(updatedFrameTimeMs)
      if (updatedFrameTimeNs > latestFrameTimeNs) {
        latestFrameTimeNs = updatedFrameTimeNs
      }
    }
    if (isRunning) {
      return
    }
    if (delegates.isNotEmpty()) {
      isRunning = true
      handler.post(this) // post to premount in this frame
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  /**
   * Registers a [MountDelegate] with the [IncrementalMountGapWorker]. This will also start the
   * [IncrementalMountGapWorker] if it isn't already running. [costAware] routes this tree through
   * the cost-aware path; see [GapWorkerMode.requestsCostAwareRoute].
   */
  fun add(root: MountDelegate, frameTimeMs: Long?, costAware: Boolean = false) {
    delegates.add(root)
    if (costAware) {
      costAwareDelegates.add(root)
    } else {
      costAwareDelegates.remove(root)
    }
    startIfNotRunning(frameTimeMs)
  }

  /**
   * Removes a [MountDelegate] registered with the [IncrementalMountGapWorker]. This will also
   * disable the [IncrementalMountGapWorker] if there are no more [MountDelegate]s.
   */
  fun remove(root: MountDelegate) {
    delegates.remove(root)
    costAwareDelegates.remove(root)
  }

  companion object {

    private var worker: IncrementalMountGapWorker? = null

    @JvmStatic
    @UiThread
    fun get(display: Display?, tracer: Systracer): IncrementalMountGapWorker =
        get(display, tracer, SYSTEM_NANO_TIME)

    @JvmStatic
    @UiThread
    @VisibleForTesting
    fun get(
        display: Display?,
        tracer: Systracer,
        nanoTime: () -> Long,
    ): IncrementalMountGapWorker {
      worker?.let { initializedWorker ->
        updateDisplay(initializedWorker, display)
        return initializedWorker
      }
      return get(
          display,
          tracer,
          nanoTime,
          EwmaPremountCostEstimator(),
          DEFAULT_PREMOUNT_DISPATCHER,
      )
    }

    @JvmStatic
    @UiThread
    @VisibleForTesting
    internal fun get(
        display: Display?,
        tracer: Systracer,
        nanoTime: () -> Long,
        estimator: PremountCostEstimator,
    ): IncrementalMountGapWorker =
        get(display, tracer, nanoTime, estimator, DEFAULT_PREMOUNT_DISPATCHER)

    @JvmStatic
    @UiThread
    @VisibleForTesting
    internal fun get(
        display: Display?,
        tracer: Systracer,
        nanoTime: () -> Long,
        estimator: PremountCostEstimator,
        premountDispatcher:
            (
                IncrementalMountExtension,
                ExtensionState<IncrementalMountExtensionState>,
                Long,
                Long,
                () -> Long,
                PremountCostEstimator,
            ) -> PremountResult,
    ): IncrementalMountGapWorker {
      val eagerFrameInterval =
          if (RenderCoreConfig.computeGapWorkerFrameIntervalLazily) {
            null
          } else {
            getFrameIntervalInNs(display)
          }

      worker?.let { initializedWorker ->
        updateDisplay(initializedWorker, display)
        return initializedWorker
      }

      val frameInterval = eagerFrameInterval ?: getFrameIntervalInNs(display)
      val newWorker = IncrementalMountGapWorker(
          frameInterval,
          tracer,
          nanoTime,
          estimator,
          premountDispatcher,
      )
      if (RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame && display != null) {
        newWorker.display = display
      }
      worker = newWorker
      return newWorker
    }

    @JvmStatic
    private fun getFrameIntervalInNs(display: Display?): Long {

      // This logic was been copied from the RecyclerView
      // break 60 fps assumption if data from display appears valid
      // NOTE: Do only once because it's very expensive (> 1ms)
      var refreshRate = 60.0f
      if (display != null) {
        val displayRefreshRate = display.refreshRate
        if (displayRefreshRate >= 30.0f) {
          refreshRate = displayRefreshRate
        }
      }

      return (1000000000 / refreshRate).toLong()
    }

    private val SYSTEM_NANO_TIME: () -> Long = System::nanoTime

    private val DEFAULT_PREMOUNT_DISPATCHER:
        (
            IncrementalMountExtension,
            ExtensionState<IncrementalMountExtensionState>,
            Long,
            Long,
            () -> Long,
            PremountCostEstimator,
        ) -> PremountResult =
        { extension, extensionState, deadlineNs, frameSequence, nanoTime, estimator ->
          extension.premountNextForGapWorker(
              extensionState,
              deadlineNs,
              frameSequence,
              nanoTime,
              estimator,
          )
        }

    private fun updateDisplay(worker: IncrementalMountGapWorker, display: Display?) {
      // Never overwrite a known-good Display with null: unregister always passes a detached host.
      if (RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame && display != null) {
        worker.display = display
      }
    }

    @JvmStatic
    @VisibleForTesting
    fun resetWorkerForTesting() {
      worker = null
    }
  }
}
