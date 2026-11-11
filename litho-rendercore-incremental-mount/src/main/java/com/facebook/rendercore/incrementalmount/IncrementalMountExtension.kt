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

import android.graphics.Rect
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.core.view.ViewCompat
import com.facebook.rendercore.Host
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderCoreConfig
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventDispatcher
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.GapWorkerCallbacks
import com.facebook.rendercore.extensions.InformsMountCallback
import com.facebook.rendercore.extensions.MountExtension
import com.facebook.rendercore.extensions.OnItemCallbacks
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.extensions.VisibleBoundsCallbacks
import com.facebook.rendercore.incrementalmount.IncrementalMountExtension.IncrementalMountExtensionState
import com.facebook.rendercore.incrementalmount.IncrementalMountExtensionConfigs.DEBUG_TAG
import com.facebook.rendercore.incrementalmount.IncrementalMountGapWorker.Companion.get
import com.facebook.rendercore.thread.utils.ThreadUtils.assertMainThread
import java.util.BitSet

/** Extension for performing incremental mount. */
class IncrementalMountExtension(
    private val gapWorkerMode: GapWorkerMode,
) :
    MountExtension<IncrementalMountExtensionInput, IncrementalMountExtensionState>(),
    VisibleBoundsCallbacks<IncrementalMountExtensionState>,
    GapWorkerCallbacks<IncrementalMountExtensionState>,
    OnItemCallbacks<IncrementalMountExtensionState>,
    InformsMountCallback {
  private val useGapWorker: Boolean = gapWorkerMode != GapWorkerMode.OFF
  private val drainsRemovedTrees: Boolean = gapWorkerMode.drainsRemovedTrees

  public override fun createState(): IncrementalMountExtensionState {
    return IncrementalMountExtensionState(gapWorkerMode)
  }

  override fun onRegisterForPremount(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      frameTimeMs: Long?,
  ) {
    if (useGapWorker) {
      val state = extensionState.state
      val lifecycle = state.premountLifecycle
      // A rebind of an attached row registers again without an unregister; keeping the active
      // session keeps what is already SEEN or RELEASED, and the row stays shown from its top.
      if (lifecycle != null && lifecycle.currentSession() == null) {
        lifecycle.startSession()
      }
      state.isRegisteredWithGapWorker = true
      stopOffscreenDrain(extensionState)
      state.scrolledPastTop = false
      val worker: IncrementalMountGapWorker = getGapWorker(extensionState)
      worker.add(extensionState.mountDelegate, frameTimeMs, gapWorkerMode.requestsCostAwareRoute)
    }
  }

  override fun onUnregisterForPremount(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ) {
    if (useGapWorker) {
      val state = extensionState.state
      state.premountLifecycle?.stopSession()
      state.isRegisteredWithGapWorker = false
      clearOffscreenDrain(state)
      val worker: IncrementalMountGapWorker = getGapWorker(extensionState)
      worker.remove(extensionState.mountDelegate)
    }
  }

  override fun beforeMount(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      input: IncrementalMountExtensionInput?,
      localVisibleRect: Rect?,
  ) {
    val isTracing = extensionState.tracer.isTracing()
    if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
      Log.d(DEBUG_TAG, "beforeMount")
    }
    if (isTracing) {
      extensionState.tracer.beginSection("IncrementalMountExtension.beforeMount")
    }

    val state: IncrementalMountExtensionState = extensionState.state
    val previousInput = state.input
    val premountSession = state.premountLifecycle?.renewSession()

    releaseAcquiredReferencesForRemovedItems(
        extensionState,
        previousInput,
        input,
        premountSession,
    )
    state.input = input
    resetCostAwareSchedulerState(state)
    state.previousLocalVisibleRect.setEmpty()
    state.needsSeenSweep = true

    setVisibleRect(state, localVisibleRect)
    if (localVisibleRect != null) {
      noteShownFromTop(extensionState, localVisibleRect, isMeasured = false)
    }

    state.currentReason = VisibilityChangeReason.UNKNOWN

    if (isTracing) {
      extensionState.tracer.endSection()
    }
  }

  override fun afterMount(extensionState: ExtensionState<IncrementalMountExtensionState>) {
    val isTracing = extensionState.tracer.isTracing()
    if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
      Log.d(DEBUG_TAG, "afterMount")
    }
    if (isTracing) {
      extensionState.tracer.beginSection("IncrementalMountExtension.afterMount")
    }

    val state: IncrementalMountExtensionState = extensionState.state

    setupPreviousMountableOutputData(state, state.previousLocalVisibleRect)
    resetCostAwareSchedulerState(state)

    if (isTracing) {
      extensionState.tracer.endSection()
    }
  }

  override fun beforeMountItem(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      renderTreeNode: RenderTreeNode,
      index: Int,
  ) {
    val isTracing = extensionState.tracer.isTracing()
    if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
      Log.d(DEBUG_TAG, "beforeMountItem [id=" + renderTreeNode.renderUnit.id + "]")
    }
    if (isTracing) {
      extensionState.tracer.beginSection("IncrementalMountExtension.beforeMountItem")
    }

    val id = renderTreeNode.renderUnit.id
    val state: IncrementalMountExtensionState = extensionState.state

    if (state.input == null) {
      return
    }

    val output = state.input?.getIncrementalMountOutputForId(id)
    requireNotNull(output) { "Output with id=" + id + " not found." }

    maybeAcquireReference(extensionState, state.previousLocalVisibleRect, output, false)

    if (isTracing) {
      extensionState.tracer.endSection()
    }
  }

  override fun hasItemToMount(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ): Boolean {
    val state: IncrementalMountExtensionState = extensionState.state
    if (state.input == null) {
      return false
    }
    // Unregistered or torn down mid-ask: every further ask would come back stale.
    val lifecycle = state.premountLifecycle
    if (lifecycle != null && lifecycle.currentSession() == null) {
      return false
    }

    val count = state.input?.getIncrementalMountOutputCount() ?: 0
    return state.previousBottomsIndex > 0 || state.previousTopsIndex < count
  }

  override fun premountNext(extensionState: ExtensionState<IncrementalMountExtensionState>) {
    premountNextOutput(extensionState)
  }

  /**
   * Mounts at most one item that fits the time left before [deadlineNs], and reports what happened
   * so the gap worker knows whether to ask again.
   *
   * Candidates are considered in three passes, stopping at the first one that mounts something: the
   * deferred deferred output, then outward from the tops cursor, then outward from the bottoms
   * cursor. An item that does not fit is stepped over rather than ending the call, so one expensive
   * item cannot block cheaper ones behind it.
   *
   * Returns [PremountResult.YIELD] when a pass found nothing mountable but work remains — either
   * nothing inspected was affordable, or the per-frame inspection budget ran out.
   */
  internal fun premountNextForGapWorker(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      deadlineNs: Long,
      frameSequence: Long,
      nanoTime: () -> Long,
      estimator: PremountCostEstimator,
  ): PremountResult {
    val state = extensionState.state
    clearPendingPremountSample(extensionState)
    if (state.premountLifecycle != null && state.premountLifecycle.currentSession() == null) {
      return PremountResult.STALE
    }
    val input = state.input
    if (input == null) {
      // Emit the terminal even on the null-input path (which also unregisters the delegate) so a
      // surface going quiet stays visible in the trace.
      if (extensionState.tracer.isTracing()) {
        extensionState.tracer.beginSection("IncrementalMountExtension.premountResult.EXHAUSTED")
        extensionState.tracer.endSection()
      }
      return PremountResult.EXHAUSTED
    }
    prepareCostAwareFrame(state, frameSequence)

    // Snapshot the frame-cumulative counters so the outcome slice reports this ask's own
    // contribution, not the running frame total.
    val scan = state.costAwareScan
    val walkedTopsBefore = scan.walkedTops
    val inspectedTopsBefore = scan.inspectedTops
    val walkedBottomsBefore = scan.walkedBottoms
    val inspectedBottomsBefore = scan.inspectedBottoms

    val isTracing = extensionState.tracer.isTracing()
    if (isTracing) {
      extensionState.tracer.beginSection("IncrementalMountExtension.premountNextForGapWorker")
    }
    try {
      val result = premountNextCostAwareCandidate(
          extensionState,
          input,
          state,
          deadlineNs,
          frameSequence,
          nanoTime,
          estimator,
      )
      if (isTracing) {
        traceAskOutcome(
            extensionState,
            state,
            result,
            walkedTopsBefore,
            inspectedTopsBefore,
            walkedBottomsBefore,
            inspectedBottomsBefore,
        )
      }
      return result
    } finally {
      if (isTracing) {
        extensionState.tracer.endSection()
      }
    }
  }

  /**
   * Records what an ask concluded and how much it spent getting there.
   *
   * The outcome cannot ride on the ask's own slice: args are written when a section opens, and the
   * result is only known when it closes. So it goes on a separate, immediately-closed slice named
   * after the result, from a fixed set of literals so nothing builds a string in the hot path.
   *
   * The counts are this ask's own contribution (frame-cumulative totals minus the values
   * snapshotted before the ask), since several asks run per frame and the counters only reset on
   * `beginFrame`. `walked` minus `inspected` is the number this exists for: an ask that walks many
   * outputs and inspects none spent its budget rediscovering content it already had.
   */
  private fun traceAskOutcome(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      state: IncrementalMountExtensionState,
      result: PremountResult,
      walkedTopsBefore: Int,
      inspectedTopsBefore: Int,
      walkedBottomsBefore: Int,
      inspectedBottomsBefore: Int,
  ) {
    val scan = state.costAwareScan
    extensionState.tracer
        .beginSectionWithArgs(
            when (result) {
              PremountResult.MOUNTED -> "IncrementalMountExtension.premountResult.MOUNTED"
              PremountResult.PROGRESSED -> "IncrementalMountExtension.premountResult.PROGRESSED"
              PremountResult.YIELD -> "IncrementalMountExtension.premountResult.YIELD"
              PremountResult.STALE -> "IncrementalMountExtension.premountResult.STALE"
              PremountResult.EXHAUSTED -> "IncrementalMountExtension.premountResult.EXHAUSTED"
            },
        )
        .arg("walkedTops", scan.walkedTops - walkedTopsBefore)
        .arg("inspectedTops", scan.inspectedTops - inspectedTopsBefore)
        .arg("walkedBottoms", scan.walkedBottoms - walkedBottomsBefore)
        .arg("inspectedBottoms", scan.inspectedBottoms - inspectedBottomsBefore)
        .arg("deferredId", state.starvation.deferredId)
        .flush()
    extensionState.tracer.endSection()
  }

  private fun premountNextCostAwareCandidate(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      input: IncrementalMountExtensionInput,
      state: IncrementalMountExtensionState,
      deadlineNs: Long,
      frameSequence: Long,
      nanoTime: () -> Long,
      estimator: PremountCostEstimator,
  ): PremountResult {

    val headResult = reconsiderStarvationHead(
        extensionState,
        input,
        state,
        deadlineNs,
        frameSequence,
        nanoTime,
        estimator,
    )
    if (headResult != null) {
      return headResult
    }

    val topResult = scanCostAwareCandidates(
        extensionState,
        input,
        state,
        deadlineNs,
        frameSequence,
        nanoTime,
        estimator,
        scanTop = true,
    )
    if (topResult != null) {
      return topResult
    }

    val bottomResult = scanCostAwareCandidates(
        extensionState,
        input,
        state,
        deadlineNs,
        frameSequence,
        nanoTime,
        estimator,
        scanTop = false,
    )
    if (bottomResult != null) {
      return bottomResult
    }

    val scanCycleExhausted =
        state.costAwareScan.hasCompletedFullPass(input.getIncrementalMountOutputCount())
    val wasAwaitingFullPass = !state.starvation.canDefer && !state.starvation.isDeferring
    if (scanCycleExhausted) {
      resetCostAwareScanPositions(state)
    }
    return if (!scanCycleExhausted || wasAwaitingFullPass || state.starvation.isDeferring) {
      PremountResult.YIELD
    } else {
      PremountResult.EXHAUSTED
    }
  }

  /** When the mount just performed began, or `0` if this ask mounted nothing. */
  internal fun pendingPremountStartNs(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ): Long = extensionState.state.costAwareScan.pendingMountStartNs

  internal fun consumePendingPremountContentType(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ): Any? {
    val state = extensionState.state
    val contentType = state.costAwareScan.pendingContentType
    clearPendingPremountSample(extensionState)
    return contentType
  }

  internal fun clearPendingPremountSample(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ) {
    val scan = extensionState.state.costAwareScan
    scan.pendingContentType = null
    scan.pendingMountStartNs = 0L
  }

  /**
   * Releases queued off-screen items, oldest first, until [deadlineNs]. While the tree is drained,
   * empty hosts and never-seen premounted items are released too.
   */
  internal fun releaseQueuedOffscreenForGapWorker(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      deadlineNs: Long,
      nanoTime: () -> Long,
  ) {
    val state = extensionState.state
    val pending = state.premountLifecycle?.pendingReleases
    if (pending == null || pending.isEmpty()) {
      return
    }
    val isTracing = extensionState.tracer.isTracing()
    if (isTracing) {
      extensionState.tracer
          .beginSectionWithArgs("IncrementalMountExtension.releaseQueuedOffscreen")
          .arg("queued", pending.size)
          .flush()
    }
    try {
      val draining = state.offscreenDrain == OffscreenDrain.DRAINING
      while (pending.isNotEmpty() && nanoTime() < deadlineNs) {
        val id = pending.removeAt(0)
        val output = state.input?.getIncrementalMountOutputForId(id)
        if (output != null && !isVisibleInLatestLayout(state, id)) {
          maybeReleaseOffscreenReference(extensionState, output, true, draining)
        }
      }
      if (draining && pending.isEmpty()) {
        state.offscreenDrain = OffscreenDrain.NONE
      }
    } finally {
      if (isTracing) {
        extensionState.tracer.endSection()
      }
    }
  }

  internal fun hasQueuedOffscreenReleases(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ): Boolean = extensionState.state.premountLifecycle?.pendingReleases?.isNotEmpty() == true

  /**
   * Called by the gap worker before it works on this tree. A requested drain starts once the tree's
   * root is out of the window and has no parent, and stops if the root is attached again: a tree
   * detached together with its parent, because the whole screen went away, keeps what it holds.
   * Returns whether the tree is being drained, in which case it gets no premount work.
   */
  internal fun prepareOffscreenDrain(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ): Boolean {
    val state = extensionState.state
    if (state.offscreenDrain == OffscreenDrain.NONE) {
      return false
    }
    if (isRootInUse(extensionState)) {
      stopOffscreenDrain(extensionState)
      return false
    }
    if (state.offscreenDrain == OffscreenDrain.REQUESTED) {
      // Without the empty pass at detach (UnmountOnDetach.Never) this still holds the last slice
      // on screen, and the drain would skip its items. Empty, it also makes the next pass a full
      // one.
      state.previousLocalVisibleRect.setEmpty()
      queueEverythingLeftForDrain(extensionState)
      state.offscreenDrain = OffscreenDrain.DRAINING
    }
    return true
  }

  internal fun isDrainingOffscreen(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ): Boolean = extensionState.state.offscreenDrain == OffscreenDrain.DRAINING

  private fun isRootInUse(extensionState: ExtensionState<IncrementalMountExtensionState>): Boolean {
    val root = extensionState.rootHost
    return root.isAttachedToWindow || root.parent != null
  }

  /**
   * A measured pass reflects the viewport. Other passes, including the one a mount starts with,
   * count only while the root is in use, since a detached root can report its full bounds.
   */
  private fun noteShownFromTop(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      visibleRect: Rect,
      isMeasured: Boolean,
  ) {
    if (
        drainsRemovedTrees &&
            !visibleRect.isEmpty &&
            visibleRect.top == 0 &&
            (isMeasured || isRootInUse(extensionState))
    ) {
      extensionState.state.shownFromTop = true
    }
  }

  /**
   * The tree is in use again, so it is no longer emptied. Queued releases the scroll path would
   * also have queued stay queued, up to its cap; hosts and premounted items leave the queue and
   * stay mounted.
   */
  private fun stopOffscreenDrain(extensionState: ExtensionState<IncrementalMountExtensionState>) {
    val state = extensionState.state
    val lifecycle = state.premountLifecycle
    if (state.offscreenDrain == OffscreenDrain.DRAINING && lifecycle != null) {
      val pending = lifecycle.pendingReleases
      val queuedBefore = pending.size
      val maxPending = IncrementalMountExtensionConfigs.maxPendingOffscreenReleases
      var kept = 0
      for (i in 0 until pending.size) {
        val id = pending[i]
        if (kept < maxPending && canQueueOffscreenRelease(extensionState, lifecycle, id)) {
          pending[kept++] = id
        }
      }
      pending.removeRange(kept, pending.size)
      if (extensionState.tracer.isTracing()) {
        extensionState.tracer
            .beginSectionWithArgs(TRACE_OFFSCREEN_DRAIN_STOP)
            .arg("queuedBefore", queuedBefore)
            .arg("kept", kept)
            .flush()
        extensionState.tracer.endSection()
      }
    }
    state.offscreenDrain = OffscreenDrain.NONE
  }

  /** Queues every item still held, deepest first, so children are released before their hosts. */
  private fun queueEverythingLeftForDrain(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ) {
    val state = extensionState.state
    val pending = state.premountLifecycle?.pendingReleases ?: return
    val outputs = state.input?.getIncrementalMountOutputs() ?: return
    val queuedBefore = pending.size
    // Children follow their hosts in the outputs, so going backwards releases hosts last.
    for (output in outputs.reversed()) {
      val id = output.id
      if (
          !isRootItem(id) &&
              !output.excludeFromIncrementalMount() &&
              extensionState.ownsReference(id) &&
              !pending.contains(id)
      ) {
        pending.add(id)
      }
    }
    if (extensionState.tracer.isTracing()) {
      extensionState.tracer
          .beginSectionWithArgs(TRACE_OFFSCREEN_DRAIN_START)
          .arg("queuedBefore", queuedBefore)
          .arg("queued", pending.size)
          .flush()
      extensionState.tracer.endSection()
    }
  }

  private fun premountNextOutput(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
  ) {
    val isTracing = extensionState.tracer.isTracing()
    val candidate = nextPremountCandidate(extensionState.state)
    if (candidate == null) {
      if (isTracing) {
        traceLegacyPremountOutcome(
            extensionState,
            "IncrementalMountExtension.legacyPremountResult.EXHAUSTED",
        )
      }
      return
    }
    val alreadyOwned = extensionState.ownsReference(candidate.output.id)
    if (!alreadyOwned && !acquirePremountReference(extensionState, candidate.output.id, true)) {
      return
    }
    val acquired = !alreadyOwned && extensionState.ownsReference(candidate.output.id)
    advancePremountCursor(extensionState.state, candidate.cursor)
    if (isTracing) {
      traceLegacyPremountOutcome(
          extensionState,
          if (acquired) "IncrementalMountExtension.legacyPremountResult.MOUNTED"
          else "IncrementalMountExtension.legacyPremountResult.PROGRESSED",
      )
    }
  }

  /**
   * Mirrors the cost-aware `premountResult` slices on the legacy path so `MOUNTED` can be counted
   * against the cost-aware arm.
   *
   * Without this the arms are not comparable on the measure that matters: only the cost-aware
   * branch reported what it achieved, so an A/B could show that the new path engages but not
   * whether it premounts more for the same frame time. Both legacy routes reach here, since
   * `premountDelegate` and `premountExtension` both land in `premountNext`.
   *
   * Only `MOUNTED` is comparable across arms. Legacy `EXHAUSTED` fires only via `premountDelegate`
   * (never `premountExtension`), on a different condition than cost-aware `EXHAUSTED`.
   *
   * `MOUNTED` here means the reference was not already owned (so it was acquired); the cost-aware
   * path also requires content afterwards, so legacy `MOUNTED` over-counts, more so the more the
   * cursor is rewound behind content already present.
   */
  private fun traceLegacyPremountOutcome(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      sectionName: String,
  ) {
    extensionState.tracer.beginSection(sectionName)
    extensionState.tracer.endSection()
  }

  private enum class PremountCursor {
    TOPS,
    BOTTOMS,
  }

  private class PremountCandidate(
      val output: IncrementalMountOutput,
      val cursor: PremountCursor,
  )

  /**
   * The next output to premount, paired with the cursor it came from.
   *
   * The cursor travels with the output because mounting happens in between selecting and advancing,
   * and acquiring a mount reference can re-enter this extension and rewrite both indices.
   * Re-deriving the branch afterwards could then advance the wrong cursor, leaving the same output
   * selected and the gap worker spinning on it for the rest of the frame.
   */
  private fun nextPremountCandidate(
      state: IncrementalMountExtensionState,
  ): PremountCandidate? {
    val input = state.input ?: return null
    val count = input.getIncrementalMountOutputCount()
    if (state.previousTopsIndex in 0 until count) {
      return PremountCandidate(
          input.getOutputsOrderedByTopBounds()[state.previousTopsIndex],
          PremountCursor.TOPS,
      )
    }
    // Tighter than hasItemToMount's `previousBottomsIndex > 0`, and deliberately so: the two agree
    // for every reachable value, since afterMount re-derives both cursors by binary search and
    // neither can pass count.
    if (state.previousBottomsIndex in 1..count) {
      return PremountCandidate(
          input.getOutputsOrderedByBottomBounds()[state.previousBottomsIndex - 1],
          PremountCursor.BOTTOMS,
      )
    }
    return null
  }

  private fun advancePremountCursor(
      state: IncrementalMountExtensionState,
      cursor: PremountCursor,
  ) {
    when (cursor) {
      PremountCursor.TOPS -> state.previousTopsIndex++
      PremountCursor.BOTTOMS -> state.previousBottomsIndex--
    }
  }

  private fun prepareCostAwareFrame(
      state: IncrementalMountExtensionState,
      frameSequence: Long,
  ) {
    if (state.costAwareScan.needsPositionReset) {
      resetCostAwareScanPositions(state)
    }
    state.costAwareScan.beginFrame(frameSequence)
  }

  /**
   * Re-evaluates the output the search has been deferring, at most once per frame.
   *
   * The head is dropped rather than reconsidered if it has since been mounted, has disappeared from
   * the input, or now hangs off a different host — the last of which means the tree was rebuilt and
   * the remembered id refers to something else. Returns `null` when there is no head to reconsider
   * or it was dropped, leaving the caller to fall through to the directional sweeps.
   */
  private fun reconsiderStarvationHead(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      input: IncrementalMountExtensionInput,
      state: IncrementalMountExtensionState,
      deadlineNs: Long,
      frameSequence: Long,
      nanoTime: () -> Long,
      estimator: PremountCostEstimator,
  ): PremountResult? {
    val deferredId = state.starvation.deferredId
    if (!state.starvation.isDeferring || state.starvation.wasRetriedIn(frameSequence)) {
      return null
    }

    val deferred = input.getIncrementalMountOutputForId(deferredId)
    if (
        deferred == null ||
            extensionState.ownsReference(deferredId) ||
            state.premountLifecycle?.isReleased(deferredId) == true ||
            getContentById(extensionState, deferredId) != null
    ) {
      state.starvation.stopDeferringUntilFullPass()
      return null
    }
    if (deferred.hostId != state.starvation.deferredHostId) {
      state.starvation.stopDeferringUntilFullPass()
      return null
    }

    state.costAwareScan.markInspected(deferredId)
    state.starvation.markRetriedIn(frameSequence)
    val result = evaluateCostAwareCandidate(
        extensionState,
        input,
        state,
        deferred,
        deadlineNs,
        frameSequence,
        nanoTime,
        estimator,
    )
    if (result == PremountResult.MOUNTED) {
      state.starvation.requireFullPass()
    }
    return result
  }

  /**
   * Sweeps one direction looking for an output worth mounting now, and mounts the first one found.
   *
   * When [scanTop] the sweep walks forward through the outputs ordered by top bound, otherwise
   * backward through those ordered by bottom bound. It steps over outputs it has already inspected
   * this frame, ones already mounted or already holding content, and ones whose estimate exceeds
   * the time left — so a run of unaffordable items does not stop the search.
   *
   * Two separate bounds, because two different things are being limited. Outputs we already own
   * cost only a set lookup to step over, so they are free; what the inspection budget bounds is
   * *search* — host checks, estimates, mount attempts. Billing the free ones was the bug: premount
   * runs ahead of the cursor and the gap worker suppresses unmounting, so the owned run in front of
   * the cursor only grows, and once it reached the budget every frame spent its whole allowance
   * rediscovering what it already had. A much larger cap still bounds the walk itself.
   *
   * The sweep resumes from where it stopped on the next frame. Returns `null` when it mounted
   * nothing, which lets the caller try the other direction before concluding anything.
   */
  private fun scanCostAwareCandidates(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      input: IncrementalMountExtensionInput,
      state: IncrementalMountExtensionState,
      deadlineNs: Long,
      frameSequence: Long,
      nanoTime: () -> Long,
      estimator: PremountCostEstimator,
      scanTop: Boolean,
  ): PremountResult? {
    val isTracing = extensionState.tracer.isTracing()
    if (isTracing) {
      // Two literals rather than a built string: this runs inside the premount hot path.
      extensionState.tracer
          .beginSectionWithArgs(
              if (scanTop) "IncrementalMountExtension.scanCostAwareCandidates.tops"
              else "IncrementalMountExtension.scanCostAwareCandidates.bottoms",
          )
          // Where the sweep starts and how much is left in front of it, so a trace shows whether
          // it resumed or was rewound onto ground it had already covered. Bottoms reads
          // currentIndex = cursorBottoms - 1 (see resetPositions), so a resumed bottoms sweep shows
          // fromIndex one below cursorBottoms, not equal to it.
          .arg("fromIndex", state.costAwareScan.currentIndex(scanTop))
          .arg("outputCount", input.getIncrementalMountOutputCount())
          .arg("cursorTops", state.previousTopsIndex)
          .arg("cursorBottoms", state.previousBottomsIndex)
          .flush()
    }
    try {
      return scanCostAwareCandidatesInDirection(
          extensionState,
          input,
          state,
          deadlineNs,
          frameSequence,
          nanoTime,
          estimator,
          scanTop,
      )
    } finally {
      if (isTracing) {
        extensionState.tracer.endSection()
      }
    }
  }

  private fun scanCostAwareCandidatesInDirection(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      input: IncrementalMountExtensionInput,
      state: IncrementalMountExtensionState,
      deadlineNs: Long,
      frameSequence: Long,
      nanoTime: () -> Long,
      estimator: PremountCostEstimator,
      scanTop: Boolean,
  ): PremountResult? {
    val outputs =
        if (scanTop) input.getOutputsOrderedByTopBounds()
        else input.getOutputsOrderedByBottomBounds()
    val count = input.getIncrementalMountOutputCount()

    while (state.costAwareScan.canAdvance(count, scanTop)) {
      val output = outputs[state.costAwareScan.currentIndex(scanTop)]

      // Already ours or released: no search to do, so step over it without spending budget.
      if (
          extensionState.ownsReference(output.id) ||
              state.premountLifecycle?.isReleased(output.id) == true
      ) {
        state.costAwareScan.advance(scanTop)
        // It can no longer starve, so stop deferring it and let a new head be chosen.
        if (state.starvation.isDeferred(output.id)) {
          state.starvation.stopDeferringUntilFullPass()
        }
        continue
      }

      // The other direction already looked at it this frame: also free.
      if (state.costAwareScan.isInspected(output.id)) {
        state.costAwareScan.advance(scanTop)
        continue
      }

      // Out of evaluation budget. Stop without marking or advancing, so this output is where the
      // next frame resumes and the other direction can still reach it in this one.
      if (!state.costAwareScan.canInspect(count, scanTop)) {
        break
      }

      // A real candidate from here: host check, cost estimate, possibly a mount. Bill it.
      state.costAwareScan.markInspected(output.id)
      state.costAwareScan.advanceAndBillInspection(scanTop)
      if (getContentById(extensionState, output.id) != null) {
        if (!acquirePremountReference(extensionState, output.id, true)) {
          return PremountResult.STALE
        }
        if (state.starvation.isDeferred(output.id)) {
          state.starvation.stopDeferringUntilFullPass()
        }
        continue
      }

      if (state.starvation.canDefer) {
        state.starvation.defer(output.id, output.hostId)
      }
      if (state.starvation.isDeferred(output.id)) {
        state.starvation.markRetriedIn(frameSequence)
      }
      val result = evaluateCostAwareCandidate(
          extensionState,
          input,
          state,
          output,
          deadlineNs,
          frameSequence,
          nanoTime,
          estimator,
      )
      if (result != null) {
        return result
      }
    }

    return null
  }

  private fun evaluateCostAwareCandidate(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      input: IncrementalMountExtensionInput,
      state: IncrementalMountExtensionState,
      output: IncrementalMountOutput,
      deadlineNs: Long,
      frameSequence: Long,
      nanoTime: () -> Long,
      estimator: PremountCostEstimator,
  ): PremountResult? {
    if (!isImmediateHostReady(extensionState, input, output)) {
      return null
    }

    val isDeferred = state.starvation.isDeferred(output.id)
    val estimate = estimator.estimateNs(output.contentType)
    if (estimate != null && estimate > (deadlineNs - nanoTime()).coerceAtLeast(0L)) {
      val mountAnyway = isDeferred && state.starvation.recordPassedOver(frameSequence)
      if (!mountAnyway) {
        return null
      }
    }

    return mountCostAwareCandidate(extensionState, state, output, isDeferred, nanoTime)
  }

  private fun mountCostAwareCandidate(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      state: IncrementalMountExtensionState,
      output: IncrementalMountOutput,
      isDeferred: Boolean,
      nanoTime: () -> Long,
  ): PremountResult {
    if (getContentById(extensionState, output.id) != null) {
      if (!acquirePremountReference(extensionState, output.id, true)) {
        return PremountResult.STALE
      }
      if (isDeferred) {
        state.starvation.stopDeferring()
      }
      return PremountResult.PROGRESSED
    }

    // Started here rather than around the whole ask: the search that chose this output can inspect
    // several candidates, and charging that to the content type would inflate whichever type
    // happens to sit late in the scan. The worker closes the sample after the visible-bounds
    // bracket, so nested work the mount triggers is still counted.
    state.costAwareScan.pendingMountStartNs = nanoTime()
    state.costAwareScan.mountInProgressId = output.id
    try {
      if (!acquirePremountReference(extensionState, output.id, true)) {
        return PremountResult.STALE
      }
    } finally {
      state.costAwareScan.mountInProgressId = IncrementalMountOutput.NO_HOST_ID
    }

    if (getContentById(extensionState, output.id) == null) {
      // The acquire produced no content, so there is nothing to measure and nothing to show for
      // the attempt. Drop the head anyway: leaving it would re-force the same output every frame,
      // and since no replacement is chosen while a head is tracked, starvation protection would be
      // dead for this surface from here on.
      if (isDeferred) {
        state.starvation.stopDeferringUntilFullPass()
      }
      return PremountResult.PROGRESSED
    }
    if (isDeferred) {
      state.starvation.stopDeferring()
    }
    state.costAwareScan.pendingContentType = output.contentType
    return PremountResult.MOUNTED
  }

  private fun resetCostAwareScanPositions(state: IncrementalMountExtensionState) {
    state.costAwareScan.resetPositions(state.previousTopsIndex, state.previousBottomsIndex)
    state.starvation.onFullPassComplete()
  }

  private fun isImmediateHostReady(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      input: IncrementalMountExtensionInput,
      output: IncrementalMountOutput,
  ): Boolean {
    if (isRootItem(output.id) || output.hostId == IncrementalMountOutput.NO_HOST_ID) {
      return true
    }
    if (
        output.hostId != MountState.ROOT_HOST_ID &&
            input.getIncrementalMountOutputForId(output.hostId) == null
    ) {
      return false
    }
    return getContentById(extensionState, output.hostId) is Host
  }

  private fun resetCostAwareSchedulerState(state: IncrementalMountExtensionState) {
    state.costAwareScan.reset()
    state.starvation.reset()
  }

  private fun resetCurrentVisibleBoundsOperation(state: IncrementalMountExtensionState) {
    state.currentReason = VisibilityChangeReason.UNKNOWN
  }

  /**
   * Called when the visible bounds change to perform incremental mount. This is always called on a
   * non-dirty mount with a non-null localVisibleRect.
   *
   * @param localVisibleRect the current local visible rect of the root host.
   */
  override fun onVisibleBoundsChanged(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      localVisibleRect: Rect?,
      reason: VisibilityChangeReason,
  ) {
    assertMainThread()

    val isTracing = extensionState.tracer.isTracing()
    if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
      Log.d(DEBUG_TAG, "onVisibleBoundsChanged [visibleBounds=" + localVisibleRect + "]")
    }
    if (isTracing) {
      extensionState.tracer.beginSection("IncrementalMountExtension.onVisibleBoundsChanged")
    }

    val state: IncrementalMountExtensionState = extensionState.state
    resetCostAwareSchedulerState(state)
    state.currentReason = reason
    try {
      if (state.input == null) {
        // Something notified the host that the visible bounds changed, but nothing was mounted yet.
        // Nothing to do.
        if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
          Log.d(DEBUG_TAG, "Skipping: Input is empty.")
        }
        return
      }

      val visibleRect = requireNotNull(localVisibleRect)
      if (visibleRect.isEmpty && state.previousLocalVisibleRect.isEmpty) {
        if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
          Log.d(DEBUG_TAG, "Skipping: Visible area is 0")
        }
        return
      }
      runSeenSweepIfNeeded(extensionState, visibleRect)
      if (drainsRemovedTrees && !visibleRect.isEmpty) {
        // Before this pass queues anything: a drain's queue can be far over the cap, and queueing
        // onto it would run overflow releases inside this frame.
        if (state.offscreenDrain != OffscreenDrain.NONE && isRootInUse(extensionState)) {
          stopOffscreenDrain(extensionState)
        }
        // An UNKNOWN pass can carry the whole host, e.g. when transient state is set for an
        // animation.
        if (reason != VisibilityChangeReason.UNKNOWN) {
          noteShownFromTop(extensionState, visibleRect, isMeasuredViewportReason(reason))
        }
        if (isMeasuredViewportReason(reason)) {
          state.scrolledPastTop = state.shownFromTop && visibleRect.top > 0
        }
      }

      // Horizontally scrolling or no visible rect. Can't incrementally mount.
      if (
          state.previousLocalVisibleRect.isEmpty ||
              visibleRect.isEmpty ||
              visibleRect.left != state.previousLocalVisibleRect.left ||
              visibleRect.right != state.previousLocalVisibleRect.right
      ) {
        initIncrementalMount(extensionState, visibleRect)
      } else {
        performIncrementalMount(extensionState, visibleRect)
      }

      setVisibleRect(state, visibleRect)
    } finally {
      resetCurrentVisibleBoundsOperation(state)
      if (isTracing) {
        extensionState.tracer.endSection()
      }
    }
  }

  override fun onUnbind(extensionState: ExtensionState<IncrementalMountExtensionState>) {
    val state = extensionState.state
    if (!drainsRemovedTrees || !state.isRegisteredWithGapWorker || !state.scrolledPastTop) {
      return
    }
    state.offscreenDrain = OffscreenDrain.REQUESTED
    // Whether the tree was removed is only known once the detach that unbound it has finished.
    getGapWorker(extensionState).addDrainCandidate(extensionState.mountDelegate)
  }

  private fun clearOffscreenDrain(state: IncrementalMountExtensionState) {
    state.offscreenDrain = OffscreenDrain.NONE
    state.shownFromTop = false
    state.scrolledPastTop = false
  }

  override fun onBindItem(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ) {
    val id = renderUnit.id

    if (!renderUnit.doesMountRenderTreeHosts()) {
      return
    }
    recursivelyNotifyVisibleBoundsChanged(extensionState, id, content)
  }

  override fun onMountItem(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ) {
    val id = renderUnit.id

    if (id == MountState.ROOT_HOST_ID && !extensionState.ownsReference(id)) {
      extensionState.acquireMountReference(id, false)
    }

    val state: IncrementalMountExtensionState = extensionState.state
    if (state.starvation.isDeferred(id) && id != state.costAwareScan.mountInProgressId) {
      state.starvation.stopDeferringUntilFullPass()
    }
    if (state.input != null && state.input?.renderUnitWithIdHostsRenderTrees(id) == true) {
      state.itemsShouldNotNotifyVisibleBoundsChangedOnChildren.add(id)
      state.mountedOutputIdsWithNestedContent.put(id, content)
    }
  }

  override fun shouldUpdateItem(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      previousRenderUnit: RenderUnit<*>,
      previousLayoutData: Any?,
      nextRenderUnit: RenderUnit<*>,
      nextLayoutData: Any?,
  ): Boolean {
    return false
  }

  override fun onUnmountItem(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ) {
    val state: IncrementalMountExtensionState = extensionState.state
    val id = renderUnit.id

    if (id == MountState.ROOT_HOST_ID && extensionState.ownsReference(id)) {
      extensionState.releaseMountReference(id, false)
    }

    state.mountedOutputIdsWithNestedContent.remove(id)
  }

  override fun onUnbindItem(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
  ) {
    val state: IncrementalMountExtensionState = extensionState.state
    val id = renderUnit.id
    state.itemsShouldNotNotifyVisibleBoundsChangedOnChildren.remove(id)
  }

  override fun onBoundsAppliedToItem(
      extensionState: ExtensionState<IncrementalMountExtensionState>,
      renderUnit: RenderUnit<*>,
      content: Any,
      layoutData: Any?,
      changed: Boolean,
  ) = Unit

  override fun onBeforeUnmount(extensionState: ExtensionState<IncrementalMountExtensionState>) {
    val state = extensionState.state
    val lifecycle = state.premountLifecycle ?: return
    lifecycle.stopSession()
    clearOffscreenDrain(state)
    state.input = null
    resetCostAwareSchedulerState(state)
    if (state.isRegisteredWithGapWorker) {
      state.isRegisteredWithGapWorker = false
      getGapWorker(extensionState).remove(extensionState.mountDelegate)
    }
  }

  override fun onUnmount(extensionState: ExtensionState<IncrementalMountExtensionState>) {
    extensionState.state.premountLifecycle?.stopSession()
    extensionState.releaseAllAcquiredReferences()
    val state: IncrementalMountExtensionState = extensionState.state
    clearOffscreenDrain(state)
    state.input = null
    state.previousLocalVisibleRect.setEmpty()
    state.componentIdsMountedInThisFrameBitmap.clear()
    resetCostAwareSchedulerState(state)
  }

  override fun canPreventMount(): Boolean {
    return true
  }

  /** Where a tree is in being emptied by the gap worker after it scrolled away. */
  internal enum class OffscreenDrain {
    NONE,
    /** Unbound after it scrolled up past the top; the gap worker checks whether it was removed. */
    REQUESTED,
    /** Removed: the gap worker releases everything it holds and premounts nothing until done. */
    DRAINING,
  }

  @VisibleForTesting
  class IncrementalMountExtensionState(gapWorkerMode: GapWorkerMode = GapWorkerMode.OFF) {
    internal val previousLocalVisibleRect: Rect = Rect()

    internal val componentIdsMountedInThisFrameBitmap: BitSet = BitSet()

    internal val itemsShouldNotNotifyVisibleBoundsChangedOnChildren: MutableLongSet =
        MutableLongSet()

    internal val mountedOutputIdsWithNestedContent: MutableLongObjectMap<Any?> =
        MutableLongObjectMap(8)

    internal var input: IncrementalMountExtensionInput? = null
    internal var previousTopsIndex: Int = 0
    internal var previousBottomsIndex: Int = 0

    /**
     * Whether this tree is in the gap worker's list right now. Set on register and cleared on
     * unregister or teardown; while set, off-screen releases follow the gap worker rules.
     */
    internal var isRegisteredWithGapWorker: Boolean = false

    /**
     * Set when a pass that can't mark SEEN (a layout, MOUNT or UNKNOWN) may have put items on
     * screen. The next SCROLL or ANIMATION frame then runs [runSeenSweepIfNeeded] and clears it.
     */
    internal var needsSeenSweep: Boolean = false

    internal val premountLifecycle: PremountLifecycle? =
        if (gapWorkerMode.unmountsIncrementally) {
          PremountLifecycle()
        } else {
          null
        }

    /** Only moves off NONE in drain mode. */
    internal var offscreenDrain: OffscreenDrain = OffscreenDrain.NONE

    /** Whether this tree was shown from its top edge, so the user reached it. */
    internal var shownFromTop: Boolean = false

    /**
     * Whether the last measured pass that showed part of this tree had its top cut off after the
     * tree was shown from its top edge, so the user reached it and it is leaving through the top of
     * the viewport. A tree never shown, shown last from its top edge, such as the next row the user
     * only peeked at, or only ever shown below its top edge, such as the previous row peeking in
     * during a back swipe that snapped back, is never drained.
     */
    internal var scrolledPastTop: Boolean = false

    internal val costAwareScan: CostAwarePremountScan = CostAwarePremountScan()
    internal val starvation: StarvationTracker = StarvationTracker()

    /** Tracks the most recent visibility change reason from onVisibleBoundsChanged. */
    internal var currentReason: VisibilityChangeReason = VisibilityChangeReason.UNKNOWN
  }

  companion object {
    private const val TRACE_PREMOUNT_PREMOUNTED = "IM.premountLifecycle.PREMOUNTED"
    private const val TRACE_PREMOUNT_SEEN = "IM.premountLifecycle.SEEN"
    private const val TRACE_PREMOUNT_RELEASE = "IM.premountLifecycle.RELEASE"
    private const val TRACE_PREMOUNT_RELEASED = "IM.premountLifecycle.RELEASED"
    private const val TRACE_PENDING_RELEASE_QUEUED = "IM.pendingRelease.QUEUED"
    private const val TRACE_PENDING_RELEASE_CANCELLED = "IM.pendingRelease.CANCELLED"
    private const val TRACE_PENDING_RELEASE_OVERFLOW = "IM.pendingRelease.OVERFLOW"
    private const val TRACE_OFFSCREEN_DRAIN_START = "IM.offscreenDrain.START"
    private const val TRACE_OFFSCREEN_DRAIN_STOP = "IM.offscreenDrain.STOP"

    val instance: IncrementalMountExtension = IncrementalMountExtension(GapWorkerMode.OFF)
    private val PremountInstance = IncrementalMountExtension(GapWorkerMode.PREMOUNT)
    private val CostAwarePremountInstance =
        IncrementalMountExtension(GapWorkerMode.COST_AWARE_PREMOUNT)
    private val IncrementalUnmountInstance =
        IncrementalMountExtension(GapWorkerMode.COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT)
    private val OffscreenDrainInstance =
        IncrementalMountExtension(GapWorkerMode.COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN)

    @JvmStatic
    fun getInstance(mode: GapWorkerMode): IncrementalMountExtension =
        when (mode) {
          GapWorkerMode.OFF -> instance
          GapWorkerMode.PREMOUNT -> PremountInstance
          GapWorkerMode.COST_AWARE_PREMOUNT -> CostAwarePremountInstance
          GapWorkerMode.COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT -> IncrementalUnmountInstance
          GapWorkerMode.COST_AWARE_PREMOUNT_WITH_OFFSCREEN_DRAIN -> OffscreenDrainInstance
        }

    fun recursivelyNotifyVisibleBoundsChanged(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        id: Long,
        content: Any?,
    ) {
      assertMainThread()
      val isTracing = extensionState.tracer.isTracing()
      if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
        Log.d(DEBUG_TAG, "RecursivelyNotify [RenderUnit=" + id + "]")
      }
      if (isTracing) {
        extensionState.tracer.beginSection("IncrementalMountExtension.recursivelyNotify")
      }
      val reason = extensionState.state.currentReason
      extensionState.mountDelegate.notifyVisibleBoundsChangedForItem(content, reason)
      if (isTracing) {
        extensionState.tracer.endSection()
      }
    }

    private fun releaseAcquiredReferencesForRemovedItems(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        previousInput: IncrementalMountExtensionInput?,
        nextInput: IncrementalMountExtensionInput?,
        premountSession: PremountSession?,
    ) {
      val outputs = previousInput?.getIncrementalMountOutputs() ?: return
      val lifecycle = extensionState.state.premountLifecycle
      for (output in outputs) {
        val id = output.id
        if (nextInput?.getIncrementalMountOutputForId(id) != null) {
          continue
        }
        if (lifecycle != null && premountSession != null) {
          lifecycle.removeOutput(premountSession, id)
        }
        if (extensionState.ownsReference(id)) {
          extensionState.releaseMountReference(id, false)
        }
      }
    }

    private fun initIncrementalMount(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        localVisibleRect: Rect,
    ) {
      val state: IncrementalMountExtensionState = extensionState.state

      if (state.input == null) {
        return
      }

      if (RenderCoreConfig.processFromLeafNode) {
        val outputs: List<IncrementalMountOutput> =
            state.input?.getIncrementalMountOutputs()?.toList() ?: emptyList()

        // Unmount components from leaf node if necessary, then we might be able to release Host
        // because all children has been unmounted.
        for (i in outputs.indices.reversed()) {
          maybeAcquireReference(extensionState, localVisibleRect, outputs[i], true)
        }
      } else {
        val outputs: Collection<IncrementalMountOutput> =
            state.input?.getIncrementalMountOutputs() ?: emptyList()
        for (output in outputs) {
          maybeAcquireReference(extensionState, localVisibleRect, output, true)
        }
      }

      setupPreviousMountableOutputData(state, localVisibleRect)
    }

    private inline fun <T> tracePremountLifecycleEvent(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        name: String?,
        lifecycle: PremountLifecycle,
        session: PremountSession,
        outputId: Long,
        isTracing: Boolean = extensionState.tracer.isTracing(),
        fromState: String? = null,
        reason: VisibilityChangeReason? = null,
        block: () -> T,
    ): T {
      val tracer = extensionState.tracer
      if (name == null || !isTracing) {
        return block()
      }
      val args =
          tracer
              .beginSectionWithArgs(name)
              .arg("lifecycleId", lifecycle.id)
              .arg("sessionId", session.id)
              .arg("outputId", outputId)
      if (fromState != null) {
        args.arg("from", fromState)
      }
      if (reason != null) {
        args.arg("reason", reason)
      }
      args.flush()
      return try {
        block()
      } finally {
        tracer.endSection()
      }
    }

    private fun markSeen(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        localVisibleRect: Rect?,
        output: IncrementalMountOutput,
    ) {
      val state = extensionState.state
      val lifecycle = state.premountLifecycle ?: return
      val session = lifecycle.currentSession() ?: return
      if (
          !isMeasuredViewportReason(state.currentReason) ||
              localVisibleRect == null ||
              !Rect.intersects(localVisibleRect, output.bounds) ||
              isRootItem(output.id) ||
              output.excludeFromIncrementalMount()
      ) {
        return
      }
      val isTracing = extensionState.tracer.isTracing()
      val previousState = if (isTracing) lifecycle.stateOf(output.id) else null
      tracePremountLifecycleEvent(
          extensionState,
          if (previousState != PremountLifecycle.State.SEEN) TRACE_PREMOUNT_SEEN else null,
          lifecycle,
          session,
          output.id,
          isTracing = isTracing,
          fromState = previousState?.name ?: "UNTRACKED",
          reason = state.currentReason,
      ) {
        lifecycle.markSeen(session, output.id)
      }
    }

    /**
     * Marks SEEN every held item that is on screen in this frame's measured rect.
     *
     * A layout mounts what is on screen without marking it, because its rect can be the whole host.
     * Scrolling then only visits items crossing the screen edges, so an item that was already in
     * the middle would first be visited on its way out and never count as seen. Running once, on
     * the first measured frame after such a pass, catches those items before anything is released.
     */
    private fun runSeenSweepIfNeeded(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        visibleRect: Rect,
    ) {
      val state = extensionState.state
      if (state.premountLifecycle == null) {
        return
      }
      if (!isMeasuredViewportReason(state.currentReason)) {
        state.needsSeenSweep = true
        return
      }
      if (!state.needsSeenSweep || visibleRect.isEmpty) {
        return
      }
      state.needsSeenSweep = false
      val outputs = state.input?.getOutputsOrderedByTopBounds() ?: return
      for (i in outputs.indices) {
        val output = outputs[i]
        if (extensionState.ownsReference(output.id)) {
          markSeen(extensionState, visibleRect, output)
        }
      }
    }

    // SCROLL and ANIMATION rects are measured from the real view; MOUNT and UNKNOWN can carry
    // framework rects (whole host, empty) that are not the screen.
    private fun isMeasuredViewportReason(reason: VisibilityChangeReason): Boolean =
        reason == VisibilityChangeReason.SCROLL || reason == VisibilityChangeReason.ANIMATION

    private fun acquirePremountReference(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        id: Long,
        isMounting: Boolean,
    ): Boolean {
      val lifecycle = extensionState.state.premountLifecycle
      if (lifecycle == null) {
        if (!extensionState.ownsReference(id)) {
          extensionState.acquireMountReference(id, isMounting)
        }
        return true
      }
      val session = lifecycle.currentSession() ?: return false
      when (
          lifecycle.beginPremountAcquire(
              session,
              id,
              alreadyOwned = extensionState.ownsReference(id),
          )
      ) {
        PremountLifecycle.AcquireDecision.STALE -> return false
        PremountLifecycle.AcquireDecision.SKIP -> return true
        PremountLifecycle.AcquireDecision.ACQUIRE -> Unit
      }

      val becamePremounted: Boolean
      try {
        extensionState.acquireMountReference(id, isMounting)
      } finally {
        val isCurrent =
            lifecycle.finishPremountAcquire(
                session,
                id,
                acquired = extensionState.ownsReference(id),
            )
        becamePremounted =
            extensionState.tracer.isTracing() &&
                isCurrent &&
                lifecycle.stateOf(id) == PremountLifecycle.State.PREMOUNTED
        if (
            !isCurrent &&
                extensionState.ownsReference(id) &&
                !lifecycle.wantsMounted(id) &&
                !isVisibleInLatestLayout(extensionState.state, id)
        ) {
          extensionState.releaseMountReference(id, isMounting)
        }
      }
      if (becamePremounted) {
        tracePremountLifecycleEvent(
            extensionState,
            TRACE_PREMOUNT_PREMOUNTED,
            lifecycle,
            session,
            id,
        ) {}
      }
      return lifecycle.isCurrent(session)
    }

    private fun maybeReleaseOffscreenReference(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        output: IncrementalMountOutput,
        isMounting: Boolean,
        draining: Boolean = false,
    ): Boolean {
      val id = output.id
      if (!extensionState.ownsReference(id)) {
        return false
      }
      val state = extensionState.state
      // Not registered: plain incremental mount, which releases off-screen hosts too.
      if (!state.isRegisteredWithGapWorker) {
        extensionState.releaseMountReference(id, isMounting)
        return true
      }
      val lifecycle = state.premountLifecycle ?: return false
      val session = lifecycle.currentSession() ?: return false
      val content = getContentById(extensionState, id)
      // A drain queues children before their hosts, so a host that still holds items here holds
      // ones the drain could not release, such as items another extension keeps mounted, and
      // stays.
      if (content is Host && (!draining || content.mountItemCount > 0)) {
        return false
      }
      val decision = lifecycle.beginOffscreenRelease(session, id, releasePremounted = draining)
      if (
          decision == PremountLifecycle.ReleaseDecision.RETAIN ||
              decision == PremountLifecycle.ReleaseDecision.STALE
      ) {
        return false
      }

      val releaseReason = state.currentReason
      tracePremountLifecycleEvent(
          extensionState,
          if (decision == PremountLifecycle.ReleaseDecision.RELEASE_AND_RECORD) {
            TRACE_PREMOUNT_RELEASE
          } else {
            null
          },
          lifecycle,
          session,
          id,
          reason = releaseReason,
      ) {
        try {
          extensionState.releaseMountReference(id, isMounting)
        } finally {
          lifecycle.finishOffscreenRelease(
              session,
              id,
              decision,
              released = !extensionState.ownsReference(id),
          )
          if (lifecycle.wantsMounted(id) && !extensionState.ownsReference(id)) {
            extensionState.acquireMountReference(id, isMounting)
          }
        }
      }
      if (
          extensionState.tracer.isTracing() &&
              decision == PremountLifecycle.ReleaseDecision.RELEASE_AND_RECORD &&
              lifecycle.isCurrent(session) &&
              lifecycle.stateOf(id) == PremountLifecycle.State.RELEASED
      ) {
        tracePremountLifecycleEvent(
            extensionState,
            TRACE_PREMOUNT_RELEASED,
            lifecycle,
            session,
            id,
            reason = releaseReason,
        ) {}
      }
      return !extensionState.ownsReference(id)
    }

    /**
     * The scroll path's release: queues the item for the gap worker when the tree allows it,
     * otherwise releases it now. Returns whether the reference was released now.
     */
    private fun releaseOrQueueOffscreenReference(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        output: IncrementalMountOutput,
        localVisibleRect: Rect,
    ): Boolean {
      val maxPending = IncrementalMountExtensionConfigs.maxPendingOffscreenReleases
      val lifecycle = extensionState.state.premountLifecycle
      if (maxPending <= 0 || lifecycle == null) {
        return maybeReleaseOffscreenReference(extensionState, output, true)
      }
      val pending = lifecycle.pendingReleases
      // Re-checked after every overflow release: its unmount callbacks can unregister the tree or
      // run a nested visibility pass that refills the queue.
      while (true) {
        if (!canQueueOffscreenRelease(extensionState, lifecycle, output.id)) {
          return maybeReleaseOffscreenReference(extensionState, output, true)
        }
        if (pending.contains(output.id)) {
          return false
        }
        if (pending.size < maxPending) {
          break
        }
        val oldest = pending.removeAt(0)
        traceInstant(extensionState, TRACE_PENDING_RELEASE_OVERFLOW, oldest)
        val oldestOutput = extensionState.state.input?.getIncrementalMountOutputForId(oldest)
        // Checked against this pass's rect: previousLocalVisibleRect still holds the last one.
        if (oldestOutput != null && !isVisibleIn(oldestOutput, localVisibleRect)) {
          maybeReleaseOffscreenReference(extensionState, oldestOutput, true)
        }
      }
      pending.add(output.id)
      // The worker drops a tree once it has nothing to premount, so it has to be woken again.
      getGapWorker(extensionState).add(extensionState.mountDelegate, null, costAware = true)
      traceInstant(extensionState, TRACE_PENDING_RELEASE_QUEUED, output.id)
      return false
    }

    /** Whether [maybeReleaseOffscreenReference] would release this item rather than keep it. */
    private fun canQueueOffscreenRelease(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        lifecycle: PremountLifecycle,
        id: Long,
    ): Boolean {
      return extensionState.state.isRegisteredWithGapWorker &&
          lifecycle.currentSession() != null &&
          extensionState.ownsReference(id) &&
          lifecycle.stateOf(id) != PremountLifecycle.State.PREMOUNTED &&
          getContentById(extensionState, id) !is Host
    }

    private fun cancelQueuedOffscreenRelease(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        id: Long,
    ) {
      val pending = extensionState.state.premountLifecycle?.pendingReleases
      if (pending != null && pending.isNotEmpty() && pending.remove(id)) {
        traceInstant(extensionState, TRACE_PENDING_RELEASE_CANCELLED, id)
      }
    }

    private fun traceInstant(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        name: String,
        outputId: Long,
    ) {
      val tracer = extensionState.tracer
      if (tracer.isTracing()) {
        tracer.beginSectionWithArgs(name).arg("outputId", outputId).flush()
        tracer.endSection()
      }
    }

    private fun maybeAcquireReference(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        localVisibleRect: Rect?,
        incrementalMountOutput: IncrementalMountOutput,
        isMounting: Boolean,
    ) {
      val id = incrementalMountOutput.id
      val content = getContentById(extensionState, id)

      // By default, a LayoutOutput passed in to mount will be mountable. Incremental mount can
      // override that if the item is outside the visible bounds.
      // TODO (T64830748): extract animations logic out of this.
      val isMountable =
          isMountedHostWithChildContent(content) ||
              (localVisibleRect != null &&
                  Rect.intersects(localVisibleRect, incrementalMountOutput.bounds)) ||
              isRootItem(id) ||
              incrementalMountOutput.excludeFromIncrementalMount()
      markSeen(extensionState, localVisibleRect, incrementalMountOutput)
      if (isMountable) {
        cancelQueuedOffscreenRelease(extensionState, id)
      }
      val hasAcquiredMountRef = extensionState.ownsReference(id)
      if (isMountable && !hasAcquiredMountRef) {
        extensionState.acquireMountReference(incrementalMountOutput.id, isMounting)
      } else if (
          !isMountable &&
              hasAcquiredMountRef &&
              !isUnmeasuredEmptyPass(extensionState, localVisibleRect)
      ) {
        if (isMounting && localVisibleRect != null) {
          releaseOrQueueOffscreenReference(extensionState, incrementalMountOutput, localVisibleRect)
        } else {
          maybeReleaseOffscreenReference(extensionState, incrementalMountOutput, isMounting)
        }
      }
    }

    // Registering for premount mounts with an empty rect, which means "mount nothing new", not
    // "nothing is visible": a rebind of an attached row must not unmount what is on screen.
    private fun isUnmeasuredEmptyPass(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        localVisibleRect: Rect?,
    ): Boolean {
      val state = extensionState.state
      return state.premountLifecycle != null &&
          state.isRegisteredWithGapWorker &&
          (localVisibleRect == null || localVisibleRect.isEmpty) &&
          !isMeasuredViewportReason(state.currentReason)
    }

    private fun setVisibleRect(state: IncrementalMountExtensionState, localVisibleRect: Rect?) {
      if (localVisibleRect != null) {
        state.previousLocalVisibleRect.set(localVisibleRect)
      }
    }

    private fun performIncrementalMount(
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        localVisibleRect: Rect,
    ) {
      val state: IncrementalMountExtensionState = extensionState.state
      if (state.input == null) {
        return
      }

      val isTracing = extensionState.tracer.isTracing()
      if (isTracing) {
        extensionState.tracer.beginSection("performIncrementalMount")
      }

      DebugEventDispatcher.dispatch(
          DebugEvent.IncrementalMountStart,
          { extensionState.renderStateId.toString() },
          { attrs: MutableMap<String, Any?> ->
            attrs[DebugEventAttribute.VisibleRect] = localVisibleRect
          },
      )

      val byTopBounds: List<IncrementalMountOutput> =
          state.input?.getOutputsOrderedByTopBounds() ?: emptyList()
      val byBottomBounds: List<IncrementalMountOutput> =
          state.input?.getOutputsOrderedByBottomBounds() ?: emptyList()
      val count = state.input?.getIncrementalMountOutputCount() ?: 0

      var itemsMounted = 0
      var itemsUnmounted = 0

      if (localVisibleRect.top >= 0 || state.previousLocalVisibleRect.top >= 0) {
        // View is going on/off the top of the screen. Check the bottoms to see if there is anything
        // that has moved on/off the top of the screen.
        while (
            state.previousBottomsIndex < count &&
                (localVisibleRect.top >=
                    byBottomBounds.get(state.previousBottomsIndex).bounds.bottom)
        ) {
          val node = byBottomBounds.get(state.previousBottomsIndex)

          if (
              !node.excludeFromIncrementalMount() &&
                  !RenderCoreConfig.disableIncrementalUnmounting &&
                  releaseOrQueueOffscreenReference(extensionState, node, localVisibleRect)
          ) {
            if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
              itemsUnmounted++
            }
          }

          state.previousBottomsIndex++
        }

        while (
            state.previousBottomsIndex > 0 &&
                (localVisibleRect.top <
                    byBottomBounds.get(state.previousBottomsIndex - 1).bounds.bottom)
        ) {
          val node = byBottomBounds.get(state.previousBottomsIndex - 1)
          val id = node.id

          // Item should still be in the view port.
          if (
              localVisibleRect.bottom >=
                  byBottomBounds.get(state.previousBottomsIndex - 1).bounds.top
          ) {
            markSeen(extensionState, localVisibleRect, node)
            cancelQueuedOffscreenRelease(extensionState, id)
            if (!extensionState.ownsReference(id)) {
              extensionState.acquireMountReference(node.id, true)
              state.componentIdsMountedInThisFrameBitmap.set(node.index)
              if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
                itemsMounted++
              }
            }
          }

          state.previousBottomsIndex--
        }
      }

      val root = extensionState.rootHost
      val height = root.getHeight()
      if (localVisibleRect.bottom < height || state.previousLocalVisibleRect.bottom < height) {
        // View is going on/off the bottom of the screen. Check the tops to see if there is anything
        // that has changed.
        while (
            state.previousTopsIndex < count &&
                localVisibleRect.bottom >= byTopBounds.get(state.previousTopsIndex).bounds.top
        ) {
          val node = byTopBounds.get(state.previousTopsIndex)
          val id = node.id

          // Item should still be in the view port.
          if (localVisibleRect.top <= byTopBounds.get(state.previousTopsIndex).bounds.bottom) {
            markSeen(extensionState, localVisibleRect, node)
            cancelQueuedOffscreenRelease(extensionState, id)
            if (!extensionState.ownsReference(id)) {
              extensionState.acquireMountReference(node.id, true)
              state.componentIdsMountedInThisFrameBitmap.set(node.index)
              if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
                itemsMounted++
              }
            }
          }

          state.previousTopsIndex++
        }

        while (
            state.previousTopsIndex > 0 &&
                localVisibleRect.bottom < byTopBounds.get(state.previousTopsIndex - 1).bounds.top
        ) {
          val node = byTopBounds.get(state.previousTopsIndex - 1)

          if (
              !node.excludeFromIncrementalMount() &&
                  !RenderCoreConfig.disableIncrementalUnmounting &&
                  releaseOrQueueOffscreenReference(extensionState, node, localVisibleRect)
          ) {
            if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
              itemsUnmounted++
            }
          }

          state.previousTopsIndex--
        }
      }

      if (IncrementalMountExtensionConfigs.isDebugLoggingEnabled) {
        Log.d(
            DEBUG_TAG,
            "Updates: [Items Mounted=" + itemsMounted + ", Items Unmounted=" + itemsUnmounted + "]",
        )
      }

      val mounted = state.componentIdsMountedInThisFrameBitmap
      state.mountedOutputIdsWithNestedContent.forEach { id, content ->
        val index = state.input?.getIncrementalMountOutputForId(id)?.index ?: -1
        if ((index < 0 || !mounted.get(index)) && content != null) {
          recursivelyNotifyVisibleBoundsChanged(extensionState, id, content)
        }
      }
      mounted.clear()

      if (isTracing) {
        extensionState.tracer.endSection()
      }

      val finalItemsMounted = itemsMounted
      val finalItemsUnmounted = itemsUnmounted

      DebugEventDispatcher.dispatch(
          DebugEvent.IncrementalMountEnd,
          { extensionState.renderStateId.toString() },
          { attrs: MutableMap<String, Any?> ->
            attrs[DebugEventAttribute.NumItemsMounted] = finalItemsMounted
            attrs[DebugEventAttribute.NumItemsUnmounted] = finalItemsUnmounted
          },
      )
    }

    private fun setupPreviousMountableOutputData(
        state: IncrementalMountExtensionState,
        localVisibleRect: Rect,
    ) {
      if (localVisibleRect.isEmpty || state.input == null) {
        return
      }

      val byTopBounds: List<IncrementalMountOutput> =
          state.input?.getOutputsOrderedByTopBounds() ?: emptyList()
      val byBottomBounds: List<IncrementalMountOutput> =
          state.input?.getOutputsOrderedByBottomBounds() ?: emptyList()
      val mountableOutputCount = state.input?.getIncrementalMountOutputCount() ?: 0

      state.previousTopsIndex =
          binarySearchTopBoundary(localVisibleRect.bottom, byTopBounds, mountableOutputCount)
      state.previousBottomsIndex =
          binarySearchBottomBoundary(localVisibleRect.top, byBottomBounds, mountableOutputCount)
    }

    private fun getGapWorker(
        state: ExtensionState<IncrementalMountExtensionState>,
    ): IncrementalMountGapWorker {
      return get(ViewCompat.getDisplay(state.rootHost), state.tracer)
    }

    private fun binarySearchTopBoundary(
        rectBottom: Int,
        byTopBounds: List<IncrementalMountOutput>,
        totalCount: Int,
    ): Int {
      var low = 0
      var high = totalCount - 1

      while (low <= high) {
        val mid = low + ((high - low) / 2)

        if (rectBottom > byTopBounds[mid].bounds.top) {
          low = mid + 1
        } else if (mid > 0 && rectBottom <= byTopBounds[mid - 1].bounds.top) {
          high = mid - 1
        } else if (mid == 0 || rectBottom > byTopBounds[mid - 1].bounds.top) {
          return mid
        }
      }

      return totalCount
    }

    private fun binarySearchBottomBoundary(
        rectTop: Int,
        byBottomBounds: List<IncrementalMountOutput>,
        totalCount: Int,
    ): Int {
      var low = 0
      var high = totalCount - 1

      while (low <= high) {
        val mid = low + ((high - low) / 2)

        if (rectTop >= byBottomBounds[mid].bounds.bottom) {
          low = mid + 1
        } else if (mid > 0 && rectTop < byBottomBounds[mid - 1].bounds.bottom) {
          high = mid - 1
        } else if (mid == 0 || rectTop >= byBottomBounds[mid - 1].bounds.bottom) {
          return mid
        }
      }

      return totalCount
    }

    @VisibleForTesting
    fun getPreviousTopsIndex(state: IncrementalMountExtensionState): Int {
      return state.previousTopsIndex
    }

    @VisibleForTesting
    fun getPreviousBottomsIndex(state: IncrementalMountExtensionState): Int {
      return state.previousBottomsIndex
    }

    // A layout that arrives during the mount owns the item from then on; keep what it shows, as
    // plain incremental mount would.
    private fun isVisibleInLatestLayout(state: IncrementalMountExtensionState, id: Long): Boolean {
      val output = state.input?.getIncrementalMountOutputForId(id) ?: return false
      return isVisibleIn(output, state.previousLocalVisibleRect)
    }

    private fun isVisibleIn(output: IncrementalMountOutput, visibleRect: Rect): Boolean =
        output.excludeFromIncrementalMount() ||
            (!visibleRect.isEmpty && Rect.intersects(visibleRect, output.bounds))

    private fun isMountedHostWithChildContent(content: Any?): Boolean {
      return content is Host && content.mountItemCount > 0
    }
  }
}
