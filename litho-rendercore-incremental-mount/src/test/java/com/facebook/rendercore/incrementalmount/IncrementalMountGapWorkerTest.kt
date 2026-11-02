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

import android.content.Context
import android.graphics.Rect
import android.util.Pair
import android.view.Display
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.facebook.rendercore.GapWorkerPremountScope
import com.facebook.rendercore.MountDelegate
import com.facebook.rendercore.MountState
import com.facebook.rendercore.RenderCoreConfig
import com.facebook.rendercore.RenderCoreExtensionHost
import com.facebook.rendercore.RenderState
import com.facebook.rendercore.RenderTree
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.RenderTreeUpdateListener
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.RootHostView
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventBus
import com.facebook.rendercore.debug.DebugEventSubscriber
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.GapWorkerCallbacks
import com.facebook.rendercore.extensions.InformsMountCallback
import com.facebook.rendercore.extensions.MountExtension
import com.facebook.rendercore.extensions.OnItemCallbacks
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.incrementalmount.IncrementalMountExtension.IncrementalMountExtensionState
import com.facebook.rendercore.testing.TestHostRenderUnit
import com.facebook.rendercore.testing.TestRenderCoreExtension
import com.facebook.rendercore.testing.TestRenderUnit
import com.meta.systrace.NoOpSystracer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowDisplay

@RunWith(RobolectricTestRunner::class)
class IncrementalMountGapWorkerTest {

  private val tracer = NoOpSystracer()
  private var savedDeadlineBufferMs: Long = 4L
  private var savedRefreshPerFrame: Boolean = false
  private var savedMarginFraction: Double = 0.0
  private var savedDirectCallbacks: Boolean = false

  @Before
  fun setUp() {
    savedDeadlineBufferMs = IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs
    savedRefreshPerFrame = RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame
    savedMarginFraction = IncrementalMountExtensionConfigs.gapWorkerMarginFraction
    savedDirectCallbacks = RenderCoreConfig.useGapWorkerExtensionState
    IncrementalMountGapWorker.resetWorkerForTesting()
  }

  @After
  fun tearDown() {
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = savedDeadlineBufferMs
    RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame = savedRefreshPerFrame
    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = savedMarginFraction
    RenderCoreConfig.useGapWorkerExtensionState = savedDirectCallbacks
    DebugEventBus.unsubscribeAll()
    IncrementalMountGapWorker.resetWorkerForTesting()
  }

  @Test
  fun `reuses one worker across calls`() {
    val first = IncrementalMountGapWorker.get(null, tracer)
    val second = IncrementalMountGapWorker.get(null, tracer)

    assertThat(second).isSameAs(first)
  }

  @Test
  fun `returns a worker when the display is unavailable`() {
    assertThat(IncrementalMountGapWorker.get(null, tracer)).isNotNull
  }

  @Test
  fun `premount loop uses the injected clock`() {
    var nowNs = Long.MAX_VALUE
    val extension = RecordingGapWorkerExtension()
    val extensionState = registerExtension(extension)
    val worker = IncrementalMountGapWorker.get(null, tracer) { nowNs }
    worker.add(extensionState.mountDelegate, null)

    worker.run()

    assertThat(extension.premountCalls).isZero()

    nowNs = 0L
    worker.run()

    assertThat(extension.premountCalls).isEqualTo(1)
  }

  @Test
  fun `registering for premount shares the process-wide worker`() {
    val extension = IncrementalMountExtension.getInstance(GapWorkerMode.PREMOUNT)
    val firstState = registerExtension(extension)
    val secondState = registerExtension(extension)

    extension.onRegisterForPremount(firstState, null)
    val worker = IncrementalMountGapWorker.get(null, tracer)
    extension.onRegisterForPremount(secondState, null)

    assertThat(IncrementalMountGapWorker.get(null, tracer)).isSameAs(worker)
  }

  @Test
  fun `unregistering removes the delegate from the shared worker`() {
    val extension = IncrementalMountExtension.getInstance(GapWorkerMode.PREMOUNT)
    val extensionState = registerExtension(extension)

    extension.onRegisterForPremount(extensionState, null)
    val worker = IncrementalMountGapWorker.get(null, tracer)
    extension.onUnregisterForPremount(extensionState)

    // The same instance survives unregistration, so `remove` acted on the delegate that `add`
    // registered rather than on a replacement worker.
    assertThat(IncrementalMountGapWorker.get(null, tracer)).isSameAs(worker)
  }

  @Test
  fun `direct callback path premounts the same items as delegate fanout`() {
    val premountedByFlag = mutableMapOf<Boolean, List<Int>>()

    for (useDirectCallbacks in listOf(false, true)) {
      RenderCoreConfig.useGapWorkerExtensionState = useDirectCallbacks
      IncrementalMountGapWorker.resetWorkerForTesting()
      val extension = RecordingGapWorkerExtension(mutableListOf(1, 2, 3))
      val extensionState = registerExtension(extension)
      val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }

      worker.add(extensionState.mountDelegate, null)
      worker.run()

      premountedByFlag[useDirectCallbacks] = extension.premountedItems.toList()
    }

    assertThat(premountedByFlag[false]).containsExactly(1, 2, 3)
    assertThat(premountedByFlag[true]).containsExactlyElementsOf(premountedByFlag[false])
  }

  @Test
  fun `cost-aware path continues after mounted and progressed results`() {
    val fixture = createCostAwareFixture(
        PremountResult.MOUNTED,
        PremountResult.PROGRESSED,
        PremountResult.EXHAUSTED,
    )
    val worker = createWorker({ 0L }, fixture.dispatcher)

    worker.add(fixture.extensionState.mountDelegate, null, costAware = true)
    worker.run()

    assertThat(fixture.resultsReturned)
        .containsExactly(
            PremountResult.MOUNTED,
            PremountResult.PROGRESSED,
            PremountResult.EXHAUSTED,
        )
  }

  @Test
  fun `cost-aware path stops a yielding delegate for the frame and runs later delegates`() {
    val decliningFixture = createCostAwareFixture(PremountResult.YIELD, PremountResult.EXHAUSTED)
    val laterFixture = createCostAwareFixture(PremountResult.MOUNTED, PremountResult.EXHAUSTED)
    val worker = createWorker({ 0L }, dispatcherFor(decliningFixture, laterFixture))

    worker.add(decliningFixture.extensionState.mountDelegate, null, costAware = true)
    worker.add(laterFixture.extensionState.mountDelegate, null, costAware = true)
    worker.run()

    assertThat(decliningFixture.resultsReturned).containsExactly(PremountResult.YIELD)
    assertThat(laterFixture.resultsReturned)
        .containsExactly(PremountResult.MOUNTED, PremountResult.EXHAUSTED)

    worker.run()

    assertThat(decliningFixture.resultsReturned)
        .containsExactly(PremountResult.YIELD, PremountResult.EXHAUSTED)
  }

  @Test
  fun `cost-aware path retains a delegate when the deadline follows progress`() {
    var nowNs = 0L
    val fixture = createCostAwareFixture(
        PremountResult.MOUNTED,
        PremountResult.EXHAUSTED,
        afterDispatch = { nowNs = Long.MAX_VALUE },
    )
    val worker = createWorker({ nowNs }, fixture.dispatcher)
    worker.add(fixture.extensionState.mountDelegate, null, costAware = true)

    worker.run()

    assertThat(fixture.resultsReturned).containsExactly(PremountResult.MOUNTED)

    nowNs = 0L
    worker.run()

    assertThat(fixture.resultsReturned)
        .containsExactly(PremountResult.MOUNTED, PremountResult.EXHAUSTED)
  }

  @Test
  fun `cost-aware path keeps a stale delegate for the next frame`() {
    val fixture = createCostAwareFixture(PremountResult.STALE, PremountResult.EXHAUSTED)
    val worker = createWorker({ 0L }, fixture.dispatcher)
    worker.add(fixture.extensionState.mountDelegate, null, costAware = true)

    worker.run()

    assertThat(fixture.resultsReturned).containsExactly(PremountResult.STALE)

    worker.run()

    assertThat(fixture.resultsReturned)
        .containsExactly(PremountResult.STALE, PremountResult.EXHAUSTED)
  }

  @Test
  fun `tearing down an incremental unmount tree removes it from the worker`() {
    val fixture = createCostAwareFixture(
        PremountResult.YIELD,
        PremountResult.MOUNTED,
        mode = GapWorkerMode.COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
    )
    val worker = createWorker({ 0L }, fixture.dispatcher)
    fixture.extension.onRegisterForPremount(fixture.extensionState, null)
    worker.run()
    assertThat(fixture.resultsReturned).containsExactly(PremountResult.YIELD)

    fixture.extension.onBeforeUnmount(fixture.extensionState)
    worker.run()

    assertThat(fixture.resultsReturned).containsExactly(PremountResult.YIELD)
  }

  @Test
  fun `cost-aware path keeps an exhausted delegate while releases are still queued`() {
    var clockReads = 0
    var readsBeforeDeadline = 2
    val fixture = createCostAwareFixture(
        PremountResult.EXHAUSTED,
        mode = GapWorkerMode.COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT,
    )
    val queued = checkNotNull(fixture.extensionState.state.premountLifecycle).pendingReleases
    queued.add(1L)
    queued.add(2L)
    // Two reads in time: the frame's own check, then one release.
    val worker = createWorker(
        { if (clockReads++ < readsBeforeDeadline) 0L else Long.MAX_VALUE },
        fixture.dispatcher,
    )
    worker.add(fixture.extensionState.mountDelegate, null, costAware = true)

    worker.run()

    assertThat(queued.size).isEqualTo(1)
    assertThat(queued[0]).isEqualTo(2L)
    assertThat(fixture.resultsReturned).isEmpty()

    readsBeforeDeadline = Int.MAX_VALUE
    worker.run()

    assertThat(queued.isEmpty()).isTrue()
    assertThat(fixture.resultsReturned).containsExactly(PremountResult.EXHAUSTED)
  }

  @Test
  fun `cost-aware path removes an exhausted delegate`() {
    val fixture = createCostAwareFixture(PremountResult.EXHAUSTED)
    val worker = createWorker({ 0L }, fixture.dispatcher)
    worker.add(fixture.extensionState.mountDelegate, null, costAware = true)

    worker.run()
    worker.run()

    assertThat(fixture.resultsReturned).containsExactly(PremountResult.EXHAUSTED)
  }

  @Test
  fun `a mount that unregisters another delegate does not throw`() {
    lateinit var worker: IncrementalMountGapWorker
    val recycledFixture = createCostAwareFixture(PremountResult.EXHAUSTED)
    val mountingFixture = createCostAwareFixture(
        PremountResult.EXHAUSTED,
        afterDispatch = { worker.remove(recycledFixture.extensionState.mountDelegate) },
    )
    worker = createWorker({ 0L }, dispatcherFor(mountingFixture, recycledFixture))
    worker.add(mountingFixture.extensionState.mountDelegate, null, costAware = true)
    worker.add(recycledFixture.extensionState.mountDelegate, null, costAware = true)

    worker.run()

    assertThat(mountingFixture.resultsReturned).containsExactly(PremountResult.EXHAUSTED)
    // Unregistered mid-pass, so it is skipped rather than premounted into a surface that is gone.
    assertThat(recycledFixture.resultsReturned).isEmpty()
  }

  @Test
  fun `a mount that unregisters the delegate being served does not throw`() {
    lateinit var worker: IncrementalMountGapWorker
    lateinit var fixture: CostAwareFixture
    fixture =
        createCostAwareFixture(
            PremountResult.EXHAUSTED,
            afterDispatch = { worker.remove(fixture.extensionState.mountDelegate) },
        )
    worker = createWorker({ 0L }, fixture.dispatcher)
    worker.add(fixture.extensionState.mountDelegate, null, costAware = true)

    worker.run()

    assertThat(fixture.resultsReturned).containsExactly(PremountResult.EXHAUSTED)
  }

  @Test
  fun `a mount that registers a delegate defers it to the next frame`() {
    lateinit var worker: IncrementalMountGapWorker
    val addedFixture = createCostAwareFixture(PremountResult.EXHAUSTED)
    val mountingFixture = createCostAwareFixture(
        PremountResult.EXHAUSTED,
        afterDispatch = {
          worker.add(addedFixture.extensionState.mountDelegate, null, costAware = true)
        },
    )
    worker = createWorker({ 0L }, dispatcherFor(mountingFixture, addedFixture))
    worker.add(mountingFixture.extensionState.mountDelegate, null, costAware = true)

    worker.run()

    assertThat(addedFixture.resultsReturned).isEmpty()

    worker.run()

    assertThat(addedFixture.resultsReturned).containsExactly(PremountResult.EXHAUSTED)
  }

  @Test
  fun `a tree takes the cost-aware route only when its mode asks for it`() {
    RenderCoreConfig.useGapWorkerExtensionState = false
    val costAware = createCostAwareFixture(
        PremountResult.MOUNTED,
        PremountResult.EXHAUSTED,
        mode = GapWorkerMode.COST_AWARE_PREMOUNT,
    )
    val legacy = createCostAwareFixture(PremountResult.MOUNTED, mode = GapWorkerMode.PREMOUNT)
    val worker = createWorker({ 0L }, dispatcherFor(costAware, legacy))

    costAware.extension.onRegisterForPremount(costAware.extensionState, null)
    legacy.extension.onRegisterForPremount(legacy.extensionState, null)
    worker.run()

    assertThat(costAware.resultsReturned)
        .containsExactly(PremountResult.MOUNTED, PremountResult.EXHAUSTED)
    assertThat(legacy.resultsReturned).isEmpty()
  }

  @Test
  fun `re-adding a tree without the cost-aware route clears its earlier request`() {
    RenderCoreConfig.useGapWorkerExtensionState = false
    val fixture = createCostAwareFixture(PremountResult.MOUNTED)
    val worker = createWorker({ 0L }, fixture.dispatcher)

    worker.add(fixture.extensionState.mountDelegate, null, costAware = true)
    worker.add(fixture.extensionState.mountDelegate, null, costAware = false)
    worker.run()

    assertThat(fixture.resultsReturned).isEmpty()
  }

  @Test
  fun `a premount tree never takes the cost-aware route whatever the callback flag says`() {
    for (directCallbacks in listOf(false, true)) {
      RenderCoreConfig.useGapWorkerExtensionState = directCallbacks
      IncrementalMountGapWorker.resetWorkerForTesting()
      val fixture = createCostAwareFixture(PremountResult.MOUNTED)
      val worker = createWorker(
          nanoTime = { 0L },
          dispatcher = throwingPremountDispatcher(),
          estimator =
              estimator(
                  onEstimate = { error("estimate accessed") },
                  onRecord = { _, _ -> error("sample recorded") },
              ),
      )

      worker.add(fixture.extensionState.mountDelegate, null)
      worker.run()

      assertThat(fixture.resultsReturned).isEmpty()
    }
  }

  @Test
  fun `successful real mount records one sample after the visible bounds bracket closes`() {
    val fixture = createCostAwareMountEventFixture(throwAfterMount = false)
    val records = mutableListOf<PremountSample>()
    var dispatchStarted = false
    var dispatchFinished = false
    var bracketFlushFinished = false
    val clock = SamplingClock(
        bracketDepth = { notifyVisibleBoundsChangedNestCount(fixture.delegate) },
        mountStartNs = 10L,
        endNs = 37L,
        dispatchStarted = { dispatchStarted },
        dispatchFinished = { dispatchFinished },
        bracketFlushFinished = { bracketFlushFinished },
    )
    fixture.delegate.setCollectVisibleBoundsChangedCalls(true)
    fixture.delegate.notifyVisibleBoundsChangedForItem(
        RecordingExtensionHost { bracketFlushFinished = true },
        VisibilityChangeReason.SCROLL,
    )
    val dispatcher =
        wrapDispatcher(fixture.requireDispatcher()) { dispatch ->
          dispatchStarted = true
          try {
            dispatch()
          } finally {
            dispatchFinished = true
          }
        }
    val worker = createWorker(
        nanoTime = clock,
        dispatcher = dispatcher,
        estimator =
            estimator(
                onRecord = { contentType, durationNs ->
                  assertThat(bracketFlushFinished).isTrue()
                  records.add(PremountSample(contentType, durationNs))
                },
            ),
    )

    worker.add(fixture.delegate, null, costAware = true)
    worker.run()

    assertThat(fixture.ownsGapWorkerMountReference()).isTrue()
    assertThat(records).containsExactly(PremountSample(PREMOUNT_CONTENT_TYPE, 27L))
  }

  @Test
  fun `already mounted or owned output does not record a sample`() {
    val scenarios = listOf(
        MountStateSamplingScenario("already-mounted MountState no-op") {
          it.mountGapWorkerContentWithoutExtensionReference()
        },
        MountStateSamplingScenario("already-owned output progress") { fixture ->
          fixture.acquireGapWorkerMountReference()
          fixture.rewindPremountCursor()
        },
    )

    for (scenario in scenarios) {
      IncrementalMountGapWorker.resetWorkerForTesting()
      val fixture = createCostAwareMountEventFixture(throwAfterMount = false)
      scenario.prepare(fixture)
      val records = mutableListOf<PremountSample>()
      val worker = createWorker(
          nanoTime = { 0L },
          dispatcher = fixture.requireDispatcher(),
          estimator =
              estimator(
                  onEstimate = { error("measurement-only phase must not estimate") },
                  onRecord = { contentType, durationNs ->
                    records.add(PremountSample(contentType, durationNs))
                  },
              ),
      )

      worker.add(fixture.delegate, null, costAware = true)
      worker.run()

      assertThat(fixture.ownsGapWorkerMountReference()).describedAs(scenario.description).isTrue()
      assertThat(records).describedAs(scenario.description).isEmpty()
    }
  }

  @Test
  fun `protocol results without a real mount do not record samples`() {
    val scenarios = listOf(
        ProtocolSamplingScenario(
            "mounted without pending content type",
            PremountResult.MOUNTED,
            pendingContentType = null,
            expectedResults = listOf(PremountResult.MOUNTED, PremountResult.EXHAUSTED),
        ),
        ProtocolSamplingScenario(
            "progressed with pending content type",
            PremountResult.PROGRESSED,
            pendingContentType = PREMOUNT_CONTENT_TYPE,
            expectedResults = listOf(PremountResult.PROGRESSED, PremountResult.EXHAUSTED),
        ),
        ProtocolSamplingScenario(
            "declined with pending content type",
            PremountResult.YIELD,
            pendingContentType = PREMOUNT_CONTENT_TYPE,
            expectedResults = listOf(PremountResult.YIELD),
        ),
        ProtocolSamplingScenario(
            "exhausted with pending content type",
            PremountResult.EXHAUSTED,
            pendingContentType = PREMOUNT_CONTENT_TYPE,
            expectedResults = listOf(PremountResult.EXHAUSTED),
        ),
    )

    for (scenario in scenarios) {
      IncrementalMountGapWorker.resetWorkerForTesting()
      val results =
          if (
              scenario.result == PremountResult.MOUNTED ||
                  scenario.result == PremountResult.PROGRESSED
          ) {
            arrayOf(scenario.result, PremountResult.EXHAUSTED)
          } else {
            arrayOf(scenario.result)
          }
      val fixture = createCostAwareFixture(*results)
      var dispatchCount = 0
      val dispatcher =
          wrapDispatcher(fixture.dispatcher) { dispatch ->
            if (dispatchCount++ == 0) {
              fixture.extensionState.state.costAwareScan.pendingContentType =
                  scenario.pendingContentType
            }
            dispatch()
          }
      val records = mutableListOf<PremountSample>()
      val worker = createWorker(
          nanoTime = { 0L },
          dispatcher = dispatcher,
          estimator =
              estimator(
                  onEstimate = { error("measurement-only phase must not estimate") },
                  onRecord = { contentType, durationNs ->
                    records.add(PremountSample(contentType, durationNs))
                  },
              ),
      )

      worker.add(fixture.extensionState.mountDelegate, null, costAware = true)
      worker.run()

      assertThat(fixture.resultsReturned)
          .describedAs(scenario.description)
          .containsExactlyElementsOf(scenario.expectedResults)
      assertThat(fixture.extensionState.state.costAwareScan.pendingContentType)
          .describedAs(scenario.description)
          .isNull()
      assertThat(records).describedAs(scenario.description).isEmpty()
    }
  }

  @Test
  fun `cost-aware exceptions close the bracket clear pending samples and record nothing`() {
    for (failurePoint in listOf("start clock", "dispatch", "after real mount")) {
      IncrementalMountGapWorker.resetWorkerForTesting()
      val records = mutableListOf<PremountSample>()
      val fixture =
          if (failurePoint == "after real mount") {
            createCostAwareMountEventFixture(throwAfterMount = true)
          } else {
            createCostAwareFixture(PremountResult.EXHAUSTED).asMountEventFixture()
          }
      fixture.delegate.setCollectVisibleBoundsChangedCalls(true)
      var clockReads = 0
      val nanoTime: () -> Long = {
        clockReads++
        if (failurePoint == "start clock" && clockReads == 3) {
          fixture.setPendingPremountContentType(PREMOUNT_CONTENT_TYPE)
          error("start clock failed")
        }
        0L
      }
      val dispatcher =
          wrapDispatcher(fixture.requireDispatcher()) { dispatch ->
            assertThat(notifyVisibleBoundsChangedNestCount(fixture.delegate))
                .describedAs(failurePoint)
                .isEqualTo(1)
            if (failurePoint == "dispatch") {
              fixture.setPendingPremountContentType(PREMOUNT_CONTENT_TYPE)
              error("dispatch failed")
            }
            dispatch()
          }
      val worker = createWorker(
          nanoTime = nanoTime,
          dispatcher = dispatcher,
          estimator =
              estimator(
                  onRecord = { contentType, durationNs ->
                    records.add(PremountSample(contentType, durationNs))
                  },
              ),
      )
      worker.add(fixture.delegate, null, costAware = true)

      assertThatThrownBy { worker.run() }
          .describedAs(failurePoint)
          .isInstanceOf(IllegalStateException::class.java)
          .hasMessageContaining("failed")
      assertThat(notifyVisibleBoundsChangedNestCount(fixture.delegate))
          .describedAs(failurePoint)
          .isZero()
      assertThat(fixture.pendingPremountContentType()).describedAs(failurePoint).isNull()
      assertThat(records).describedAs(failurePoint).isEmpty()
      if (failurePoint == "after real mount") {
        fixture.clearCostAwareInput()
        worker.run()
        assertThat(records).describedAs("$failurePoint retry").isEmpty()
      }
    }
  }

  @Test
  fun `unsupported extension uses direct legacy callbacks without estimator access`() {
    val extension = RecordingGapWorkerExtension(mutableListOf(1))
    val extensionState = registerExtension(extension)
    val worker = createWorker(
        nanoTime = { 0L },
        dispatcher = throwingPremountDispatcher(),
        estimator =
            estimator(
                onEstimate = { error("estimate accessed") },
                onRecord = { _, _ -> error("sample recorded") },
            ),
    )

    worker.add(extensionState.mountDelegate, null, costAware = true)
    worker.run()

    assertThat(extension.premountedItems).containsExactly(1)
  }

  @Test
  fun `cost-aware path passes absolute deadline with a non-zero dispatch clock`() {
    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = 0.0
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 0L
    val nanoTime: () -> Long = { NON_ZERO_DISPATCH_TIME_NS }
    val estimator = estimator()
    val fixture = createCostAwareFixture(PremountResult.EXHAUSTED)
    val worker = createWorker(
        nanoTime = nanoTime,
        dispatcher = fixture.dispatcher,
        estimator = estimator,
    )

    worker.add(fixture.extensionState.mountDelegate, frameTimeMs = 7L, costAware = true)
    worker.run()

    val call = fixture.calls.single()
    assertThat(call.extension).isSameAs(fixture.extension)
    assertThat(call.extensionState).isSameAs(fixture.extensionState)
    assertThat(call.deadlineNs).isEqualTo(7_000_000L + FRAME_END_60HZ_NS)
    assertThat(call.frameSequence).isZero()
    // Widened to Any: assertThat has no ObjectAssert overload for a function type, so isSameAs
    // does not resolve on the bare `() -> Long`.
    assertThat(call.nanoTime as Any).isSameAs(nanoTime)
    assertThat(call.estimator).isSameAs(estimator)
  }

  @Test
  fun `cost-aware path increments frame sequence only in doFrame and snapshots each run`() {
    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = 0.0
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 0L
    val fixture = createCostAwareFixture(
        PremountResult.MOUNTED,
        PremountResult.EXHAUSTED,
        PremountResult.EXHAUSTED,
    )
    lateinit var worker: IncrementalMountGapWorker
    val dispatcher:
        (
            IncrementalMountExtension,
            ExtensionState<IncrementalMountExtensionState>,
            Long,
            Long,
            () -> Long,
            PremountCostEstimator,
        ) -> PremountResult =
        { extension, extensionState, deadlineNs, frameSequence, nanoTime, estimator ->
          val result =
              fixture.dispatch(
                  extension,
                  extensionState,
                  deadlineNs,
                  frameSequence,
                  nanoTime,
                  estimator,
              )
          if (fixture.calls.size == 1) {
            worker.doFrame(SECOND_FRAME_TIME_NS)
          }
          result
        }
    worker = createWorker({ 0L }, dispatcher)
    worker.doFrame(FIRST_FRAME_TIME_NS)
    worker.add(fixture.extensionState.mountDelegate, frameTimeMs = 99L, costAware = true)

    worker.run()

    assertThat(fixture.calls.map { it.frameSequence }).containsExactly(1L, 1L)
    assertThat(fixture.calls.map { it.deadlineNs })
        .containsExactly(
            99_000_000L + FRAME_END_60HZ_NS,
            99_000_000L + FRAME_END_60HZ_NS,
        )

    worker.add(fixture.extensionState.mountDelegate, frameTimeMs = 100L, costAware = true)
    worker.run()

    assertThat(fixture.calls.last().frameSequence).isEqualTo(2L)
    assertThat(fixture.calls.last().deadlineNs).isEqualTo(100_000_000L + FRAME_END_60HZ_NS)
  }

  @Test
  fun `delegate path retains fanout to every gap worker extension`() {
    RenderCoreConfig.useGapWorkerExtensionState = false
    val first = RecordingGapWorkerExtension(mutableListOf(1))
    val extensionState = registerExtension(first)
    val second = RecordingGapWorkerExtension(mutableListOf(2))
    extensionState.mountDelegate.registerExtensions(
        listOf(
            Pair(TestRenderCoreExtension(first), Unit),
            Pair(TestRenderCoreExtension(second), Unit),
        ),
    )
    val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }

    worker.add(extensionState.mountDelegate, null)
    worker.run()

    assertThat(first.premountedItems).containsExactly(1)
    assertThat(second.premountedItems).containsExactly(2)
  }

  @Test
  fun `both callback paths bracket each premount call`() {
    for (useDirectCallbacks in listOf(false, true)) {
      RenderCoreConfig.useGapWorkerExtensionState = useDirectCallbacks
      IncrementalMountGapWorker.resetWorkerForTesting()
      val extension = RecordingGapWorkerExtension(mutableListOf(1, 2))
      val extensionState = registerExtension(extension)
      val delegate = extensionState.mountDelegate
      delegate.setCollectVisibleBoundsChangedCalls(true)
      val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }

      worker.add(delegate, null)
      worker.run()

      assertThat(extension.nestCountsDuringPremount).containsExactly(1, 1)
      assertThat(notifyVisibleBoundsChangedNestCount(delegate)).isZero()
    }
  }

  @Test
  fun `direct callback path closes the bracket when premount throws`() {
    RenderCoreConfig.useGapWorkerExtensionState = true
    val extension = RecordingGapWorkerExtension(mutableListOf(1), throwOnPremount = true)
    val extensionState = registerExtension(extension)
    val delegate = extensionState.mountDelegate
    delegate.setCollectVisibleBoundsChangedCalls(true)
    val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }
    worker.add(delegate, null)

    assertThatThrownBy { worker.run() }.isInstanceOf(IllegalStateException::class.java)
    assertThat(notifyVisibleBoundsChangedNestCount(delegate)).isZero()
  }

  @Test
  fun `direct callback path resolves the replacement extension state`() {
    RenderCoreConfig.useGapWorkerExtensionState = true
    val originalExtension = RecordingGapWorkerExtension(mutableListOf(1))
    val originalState = registerExtension(originalExtension)
    val delegate = originalState.mountDelegate
    val replacementExtension = RecordingGapWorkerExtension(mutableListOf(2))
    val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }
    worker.add(delegate, null)

    delegate.registerExtensions(
        listOf(Pair(TestRenderCoreExtension(replacementExtension), Unit)),
    )
    worker.run()

    assertThat(originalExtension.premountedItems).isEmpty()
    assertThat(replacementExtension.premountedItems).containsExactly(2)
  }

  @Test
  fun `cost-aware path resolves the replacement extension state`() {
    val extension = IncrementalMountExtension.getInstance(GapWorkerMode.PREMOUNT)
    val originalState = registerExtension(extension)
    val delegate = originalState.mountDelegate
    var dispatchedState: ExtensionState<IncrementalMountExtensionState>? = null
    val worker = createWorker(
        nanoTime = { 0L },
        dispatcher = { _, extensionState, _, _, _, _ ->
          dispatchedState = extensionState
          PremountResult.EXHAUSTED
        },
    )
    worker.add(delegate, null, costAware = true)

    delegate.registerExtensions(
        listOf(Pair(TestRenderCoreExtension(extension), null)),
    )
    worker.run()

    assertThat(dispatchedState).isNotNull().isNotSameAs(originalState)
  }

  @Test
  fun `direct callback path evicts a delegate with no remaining items`() {
    RenderCoreConfig.useGapWorkerExtensionState = true
    val extension = RecordingGapWorkerExtension(mutableListOf())
    val extensionState = registerExtension(extension)
    val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }
    worker.add(extensionState.mountDelegate, null)

    worker.run()
    val callsAfterEviction = extension.hasItemCalls
    extension.items.add(1)
    worker.run()

    assertThat(extension.hasItemCalls).isEqualTo(callsAfterEviction)
    assertThat(extension.premountedItems).isEmpty()
  }

  @Test
  fun `premount mount event is marked on every callback path and marker is restored`() {

    for ((useDirectCallbacks, useCostAwarePremount) in
        listOf(false to false, true to false, true to true)) {
      RenderCoreConfig.useGapWorkerExtensionState = useDirectCallbacks
      IncrementalMountGapWorker.resetWorkerForTesting()
      val fixture =
          if (useCostAwarePremount) {
            createCostAwareMountEventFixture()
          } else {
            createMountEventFixture(throwOnPremount = true)
          }
      val events = mutableListOf<DebugEvent>()
      val subscriber =
          object : DebugEventSubscriber(DebugEvent.RenderUnitMounted) {
            override fun onEvent(event: DebugEvent) {
              events.add(event)
            }
          }
      DebugEventBus.subscribe(subscriber)

      try {
        val worker =
            fixture.dispatcher?.let { dispatcher ->
              createWorker({ 0L }, dispatcher)
            } ?: IncrementalMountGapWorker.get(null, tracer) { 0L }
        worker.add(fixture.delegate, null, costAware = useCostAwarePremount)

        assertThatThrownBy { worker.run() }.isInstanceOf(IllegalStateException::class.java)
        fixture.acquireOrdinaryMountReference()
      } finally {
        DebugEventBus.unsubscribe(subscriber)
      }

      val premountEvent = events.single {
        it.attribute<Long>(DebugEventAttribute.RenderUnitId) == GAP_WORKER_MOUNT_ID
      }
      val premountParentEvent = events.single {
        it.attribute<Long>(DebugEventAttribute.RenderUnitId) == GAP_WORKER_PARENT_ID
      }
      val ordinaryEvent = events.single {
        it.attribute<Long>(DebugEventAttribute.RenderUnitId) == ORDINARY_MOUNT_ID
      }
      assertThat(
          premountEvent.attribute(
              DebugEventAttribute.IsGapWorkerPremount,
              false,
          ),
      )
          .isTrue()
      assertThat(
          premountParentEvent.attribute(
              DebugEventAttribute.IsGapWorkerPremount,
              false,
          ),
      )
          .isTrue()
      assertThat(
          ordinaryEvent.attribute(
              DebugEventAttribute.IsGapWorkerPremount,
              false,
          ),
      )
          .isFalse()
    }
  }

  @Test
  fun `premount marks mounts inside a nested render tree host`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mountState = MountState(RootHostView(context))
    val extension = NestedHostPremountExtension(mutableListOf(GAP_WORKER_MOUNT_ID))
    val extensionState = mountState.registerExtension(extension)
    mountNestedHostTree(mountState)
    val events = mutableListOf<DebugEvent>()
    val subscriber =
        object : DebugEventSubscriber(DebugEvent.RenderUnitMounted) {
          override fun onEvent(event: DebugEvent) {
            events.add(event)
          }
        }
    DebugEventBus.subscribe(subscriber)

    try {
      val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }
      worker.add(extensionState.mountDelegate, null)
      worker.run()
    } finally {
      DebugEventBus.unsubscribe(subscriber)
    }

    // The nested tree is inflated synchronously inside the premount scope, so its mounts are part
    // of the premount's cost and must carry the same marker as the host that triggered them.
    val nestedEvent = events.single {
      it.attribute<Long>(DebugEventAttribute.RenderUnitId) == NESTED_MOUNT_ID
    }
    assertThat(nestedEvent.attribute(DebugEventAttribute.IsGapWorkerPremount, false)).isTrue()
  }

  @Test
  fun `direct callback path rejects multiple gap worker extensions`() {
    RenderCoreConfig.useGapWorkerExtensionState = true
    val first = RecordingGapWorkerExtension(mutableListOf(1))
    val extensionState = registerExtension(first)
    extensionState.mountDelegate.registerExtensions(
        listOf(
            Pair(TestRenderCoreExtension(first), Unit),
            Pair(TestRenderCoreExtension(RecordingGapWorkerExtension(mutableListOf(2))), Unit),
        ),
    )
    val worker = IncrementalMountGapWorker.get(null, tracer) { 0L }
    worker.add(extensionState.mountDelegate, null)

    assertThatThrownBy { worker.run() }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("Multiple GapWorkerCallbacks")
  }

  @Test
  fun `gap worker premount scope restores an outer scope`() {
    val fixture = createMountEventFixture()
    val events = mutableListOf<DebugEvent>()
    val subscriber =
        object : DebugEventSubscriber(DebugEvent.RenderUnitMounted) {
          override fun onEvent(event: DebugEvent) {
            events.add(event)
          }
        }
    DebugEventBus.subscribe(subscriber)

    try {
      GapWorkerPremountScope.with {
        GapWorkerPremountScope.with { Unit }
        fixture.acquireOrdinaryMountReference()
      }
    } finally {
      DebugEventBus.unsubscribe(subscriber)
    }

    val event = events.single {
      it.attribute<Long>(DebugEventAttribute.RenderUnitId) == ORDINARY_MOUNT_ID
    }
    assertThat(event.attribute(DebugEventAttribute.IsGapWorkerPremount, false)).isTrue()
  }

  private fun createMountEventFixture(throwOnPremount: Boolean = false): MountEventFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mountState = MountState(RootHostView(context))
    val extension = MountingGapWorkerExtension(
        mutableListOf(GAP_WORKER_MOUNT_ID),
        throwOnPremount,
    )
    val extensionState = mountState.registerExtension(extension)
    mountEventTree(mountState, firstPremountChildBottom = 50)
    return MountEventFixture(
        delegate = extensionState.mountDelegate,
        acquireOrdinaryMountReference = {
          extensionState.acquireMountReference(ORDINARY_MOUNT_ID, true)
        },
    )
  }

  private fun createCostAwareMountEventFixture(
      throwAfterMount: Boolean = true,
      contentType: Any = PREMOUNT_CONTENT_TYPE,
      includeSecondPremountChild: Boolean = false,
  ): MountEventFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mountState = MountState(RootHostView(context))
    val extension = IncrementalMountExtension.getInstance(GapWorkerMode.PREMOUNT)
    val extensionState = mountState.registerExtension(extension)
    mountEventTree(
        mountState,
        firstPremountChildBottom = 25,
        includeSecondPremountChild = includeSecondPremountChild,
    )
    val firstOutput = IncrementalMountOutput(
        GAP_WORKER_MOUNT_ID,
        2,
        Rect(0, 0, 50, 25),
        false,
        "premount-child",
        null,
        contentType,
        IncrementalMountOutput.NO_HOST_ID,
    )
    val secondOutput =
        if (!includeSecondPremountChild) {
          null
        } else {
          IncrementalMountOutput(
              SECOND_GAP_WORKER_MOUNT_ID,
              3,
              Rect(0, 25, 50, 50),
              false,
              "second-premount-child",
              null,
              contentType,
              IncrementalMountOutput.NO_HOST_ID,
          )
        }
    extensionState.state.input =
        if (secondOutput == null) {
          TestIncrementalMountExtensionInput(firstOutput)
        } else {
          TestIncrementalMountExtensionInput(firstOutput, secondOutput)
        }
    val dispatcher:
        (
            IncrementalMountExtension,
            ExtensionState<IncrementalMountExtensionState>,
            Long,
            Long,
            () -> Long,
            PremountCostEstimator,
        ) -> PremountResult =
        { actualExtension, actualState, deadlineNs, frameSequence, nanoTime, estimator ->
          // Return the adapter's own result: hardcoding MOUNTED would never let the worker's
          // loop terminate, since it exits only on YIELD or EXHAUSTED.
          val result =
              actualExtension.premountNextForGapWorker(
                  actualState,
                  deadlineNs,
                  frameSequence,
                  nanoTime,
                  estimator,
              )
          if (throwAfterMount && result == PremountResult.MOUNTED) {
            error("premount failed")
          }
          result
        }
    return MountEventFixture(
        delegate = extensionState.mountDelegate,
        acquireOrdinaryMountReference = {
          extensionState.acquireMountReference(ORDINARY_MOUNT_ID, true)
        },
        ownsGapWorkerMountReference = {
          extensionState.ownsReference(GAP_WORKER_MOUNT_ID)
        },
        dispatcher = dispatcher,
        mountGapWorkerContentWithoutExtensionReference = {
          mountState.notifyMount(GAP_WORKER_MOUNT_ID)
        },
        acquireGapWorkerMountReference = {
          extensionState.acquireMountReference(GAP_WORKER_MOUNT_ID, true)
        },
        ownsSecondGapWorkerMountReference = {
          extensionState.ownsReference(SECOND_GAP_WORKER_MOUNT_ID)
        },
        rewindPremountCursor = {
          extensionState.state.previousTopsIndex = 0
        },
        setPendingPremountContentType = { contentType ->
          extensionState.state.costAwareScan.pendingContentType = contentType
        },
        pendingPremountContentType = { extensionState.state.costAwareScan.pendingContentType },
        clearCostAwareInput = { extensionState.state.input = null },
    )
  }

  private fun mountEventTree(
      mountState: MountState,
      firstPremountChildBottom: Int,
      includeSecondPremountChild: Boolean = false,
  ) {
    val root = RenderTreeNode(null, TestHostRenderUnit(0), null, Rect(0, 0, 100, 100), null, 0)
    val premountParent = RenderTreeNode(
        root,
        TestHostRenderUnit(GAP_WORKER_PARENT_ID),
        null,
        Rect(0, 0, 50, 50),
        null,
        0,
    )
    val nodes = mutableListOf(
        root,
        premountParent,
        RenderTreeNode(
            premountParent,
            TestRenderUnit(GAP_WORKER_MOUNT_ID),
            null,
            Rect(0, 0, 50, firstPremountChildBottom),
            null,
            0,
        ),
    )
    if (includeSecondPremountChild) {
      nodes.add(
          RenderTreeNode(
              premountParent,
              TestRenderUnit(SECOND_GAP_WORKER_MOUNT_ID),
              null,
              Rect(0, firstPremountChildBottom, 50, 50),
              null,
              1,
          ),
      )
    }
    nodes.add(
        RenderTreeNode(
            root,
            TestRenderUnit(ORDINARY_MOUNT_ID),
            null,
            Rect(50, 0, 100, 50),
            null,
            1,
        ),
    )
    mountState.mount(
        RenderTree(
            root,
            nodes.toTypedArray(),
            null,
            SizeConstraints.exact(100, 100),
            RenderState.NO_ID,
            null,
            null,
            null,
        ),
    )
  }

  private fun mountNestedHostTree(mountState: MountState) {
    val root = RenderTreeNode(null, TestHostRenderUnit(0), null, Rect(0, 0, 100, 100), null, 0)
    val premountParent = RenderTreeNode(
        root,
        TestHostRenderUnit(GAP_WORKER_PARENT_ID),
        null,
        Rect(0, 0, 50, 50),
        null,
        0,
    )
    val nestedHostChild = RenderTreeNode(
        premountParent,
        NestedHostRenderUnit(GAP_WORKER_MOUNT_ID),
        null,
        Rect(0, 0, 50, 50),
        null,
        0,
    )
    mountState.mount(
        RenderTree(
            root,
            arrayOf(root, premountParent, nestedHostChild),
            null,
            SizeConstraints.exact(100, 100),
            RenderState.NO_ID,
            null,
            null,
            null,
        ),
    )
  }

  private fun mountEventTree(
      mountState: MountState,
      firstPremountChildBottom: Int,
  ) {
    val root = RenderTreeNode(null, TestHostRenderUnit(0), null, Rect(0, 0, 100, 100), null, 0)
    val premountParent = RenderTreeNode(
        root,
        TestHostRenderUnit(GAP_WORKER_PARENT_ID),
        null,
        Rect(0, 0, 50, 50),
        null,
        0,
    )
    val premountChild = RenderTreeNode(
        premountParent,
        TestRenderUnit(GAP_WORKER_MOUNT_ID),
        null,
        Rect(0, 0, 50, firstPremountChildBottom),
        null,
        0,
    )
    val ordinaryChild = RenderTreeNode(
        root,
        TestRenderUnit(ORDINARY_MOUNT_ID),
        null,
        Rect(50, 0, 100, 50),
        null,
        1,
    )
    mountState.mount(
        RenderTree(
            root,
            arrayOf(root, premountParent, premountChild, ordinaryChild),
            null,
            SizeConstraints.exact(100, 100),
            RenderState.NO_ID,
            null,
            null,
            null,
        ),
    )
  }

  private fun registerExtension(
      extension: IncrementalMountExtension,
  ): ExtensionState<IncrementalMountExtensionState> = registerExtensionInternal(extension)

  private fun <State> registerExtension(
      extension: MountExtension<*, State>,
  ): ExtensionState<State> = registerExtensionInternal(extension)

  private fun <State> registerExtensionInternal(
      extension: MountExtension<*, State>,
  ): ExtensionState<State> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return MountState(RootHostView(context)).registerExtension(extension)
  }

  private class MountingGapWorkerExtension(
      private val ids: MutableList<Long>,
      private val throwOnPremount: Boolean,
  ) : MountExtension<Unit, Unit>(), GapWorkerCallbacks<Unit>, InformsMountCallback {

    override fun createState(): Unit = Unit

    override fun canPreventMount(): Boolean = true

    override fun onRegisterForPremount(extensionState: ExtensionState<Unit>, frameTimeMs: Long?) =
        Unit

    override fun onUnregisterForPremount(extensionState: ExtensionState<Unit>) = Unit

    override fun hasItemToMount(extensionState: ExtensionState<Unit>): Boolean = ids.isNotEmpty()

    override fun premountNext(extensionState: ExtensionState<Unit>) {
      extensionState.acquireMountReference(ids.removeAt(0), true)
      if (throwOnPremount) {
        error("premount failed")
      }
    }
  }

  private class MountEventFixture(
      val delegate: com.facebook.rendercore.MountDelegate,
      val acquireOrdinaryMountReference: () -> Unit,
      val ownsGapWorkerMountReference: () -> Boolean = { false },
      val dispatcher:
          ((
              IncrementalMountExtension,
              ExtensionState<IncrementalMountExtensionState>,
              Long,
              Long,
              () -> Long,
              PremountCostEstimator,
          ) -> PremountResult)? =
          null,
      val mountGapWorkerContentWithoutExtensionReference: () -> Unit = {},
      val acquireGapWorkerMountReference: () -> Unit = {},
      val ownsSecondGapWorkerMountReference: () -> Boolean = { false },
      val rewindPremountCursor: () -> Unit = {},
      val setPendingPremountContentType: (Any?) -> Unit = {},
      val pendingPremountContentType: () -> Any? = { null },
      val clearCostAwareInput: () -> Unit = {},
  ) {
    fun requireDispatcher():
        (
            IncrementalMountExtension,
            ExtensionState<IncrementalMountExtensionState>,
            Long,
            Long,
            () -> Long,
            PremountCostEstimator,
        ) -> PremountResult = checkNotNull(dispatcher)
  }

  private class MountStateSamplingScenario(
      val description: String,
      val prepare: (MountEventFixture) -> Unit,
  )

  private class ProtocolSamplingScenario(
      val description: String,
      val result: PremountResult,
      val pendingContentType: Any?,
      val expectedResults: List<PremountResult>,
  )

  private data class PremountSample(val contentType: Any, val durationNs: Long)

  /**
   * Models the two ends of a premount sample.
   *
   * The sample opens inside the dispatch, where the extension stamps the mount, rather than around
   * the whole ask: the search that selects an output can inspect several candidates, and charging
   * that to the content type would inflate whichever type happens to sit late in the scan. It
   * closes after the visible-bounds bracket drains, so nested work the mount triggered is counted.
   */
  private class SamplingClock(
      private val bracketDepth: () -> Int,
      private val mountStartNs: Long,
      private val endNs: Long,
      private val dispatchStarted: () -> Boolean,
      private val dispatchFinished: () -> Boolean,
      private val bracketFlushFinished: () -> Boolean,
  ) : () -> Long {

    override fun invoke(): Long =
        when {
          dispatchFinished() && bracketDepth() == 0 && bracketFlushFinished() -> endNs
          dispatchStarted() && bracketDepth() == 1 -> mountStartNs
          else -> 0L
        }
  }

  private class RecordingExtensionHost(private val onNotify: () -> Unit) : RenderCoreExtensionHost {
    override fun notifyVisibleBoundsChanged(reason: VisibilityChangeReason) = onNotify()

    override fun onRegisterForPremount(frameTimeMs: Long?) = Unit

    override fun onUnregisterForPremount() = Unit

    override fun setRenderTreeUpdateListener(listener: RenderTreeUpdateListener?) = Unit
  }

  /** A render unit whose content owns a nested render tree, like a Litho `Recycler`. */
  private class NestedHostRenderUnit(id: Long) : TestRenderUnit(id) {
    override fun createContent(c: Context): View = NestedHostView(c)

    override fun doesMountRenderTreeHosts(): Boolean = true
  }

  /**
   * Stands in for a nested `BaseMountingView`: it mounts its own tree, on its own [MountState] with
   * its own `MountDelegate`, when its parent notifies it of a bounds change.
   */
  private class NestedHostView(context: Context) : View(context), RenderCoreExtensionHost {
    private val nestedMountState = MountState(RootHostView(context))
    private var hasMounted = false

    init {
      // Give the nested MountState a MountDelegate of its own, as a real nested view has.
      nestedMountState.registerExtension(NoOpMountExtension())
    }

    override fun notifyVisibleBoundsChanged(reason: VisibilityChangeReason) {
      if (hasMounted) {
        return
      }
      hasMounted = true
      val root = RenderTreeNode(null, TestHostRenderUnit(0), null, Rect(0, 0, 50, 50), null, 0)
      val child = RenderTreeNode(
          root,
          TestRenderUnit(NESTED_MOUNT_ID),
          null,
          Rect(0, 0, 50, 50),
          null,
          0,
      )
      nestedMountState.mount(
          RenderTree(
              root,
              arrayOf(root, child),
              null,
              SizeConstraints.exact(50, 50),
              RenderState.NO_ID,
              null,
              null,
              null,
          ),
      )
    }

    override fun onRegisterForPremount(frameTimeMs: Long?) = Unit

    override fun onUnregisterForPremount() = Unit

    override fun setRenderTreeUpdateListener(listener: RenderTreeUpdateListener?) = Unit
  }

  private class NoOpMountExtension : MountExtension<Unit, Unit>() {
    override fun createState(): Unit = Unit
  }

  private class NestedHostPremountExtension(
      private val ids: MutableList<Long>,
  ) :
      MountExtension<Unit, Unit>(),
      GapWorkerCallbacks<Unit>,
      InformsMountCallback,
      OnItemCallbacks<Unit> {

    override fun createState(): Unit = Unit

    override fun canPreventMount(): Boolean = true

    override fun onRegisterForPremount(extensionState: ExtensionState<Unit>, frameTimeMs: Long?) =
        Unit

    override fun onUnregisterForPremount(extensionState: ExtensionState<Unit>) = Unit

    override fun hasItemToMount(extensionState: ExtensionState<Unit>): Boolean = ids.isNotEmpty()

    override fun premountNext(extensionState: ExtensionState<Unit>) {
      extensionState.acquireMountReference(ids.removeAt(0), true)
    }

    // Mirrors IncrementalMountExtension.onBindItem: a unit that hosts render trees has them
    // notified while still inside the gap-worker premount scope.
    override fun onBindItem(
        extensionState: ExtensionState<Unit>,
        renderUnit: RenderUnit<*>,
        content: Any,
        layoutData: Any?,
    ) {
      if (!renderUnit.doesMountRenderTreeHosts()) {
        return
      }
      extensionState.mountDelegate.notifyVisibleBoundsChangedForItem(
          content,
          VisibilityChangeReason.MOUNT,
      )
    }

    override fun beforeMountItem(
        extensionState: ExtensionState<Unit>,
        renderTreeNode: RenderTreeNode,
        index: Int,
    ) = Unit

    override fun onMountItem(
        extensionState: ExtensionState<Unit>,
        renderUnit: RenderUnit<*>,
        content: Any,
        layoutData: Any?,
    ) = Unit

    override fun shouldUpdateItem(
        extensionState: ExtensionState<Unit>,
        previousRenderUnit: RenderUnit<*>,
        previousLayoutData: Any?,
        nextRenderUnit: RenderUnit<*>,
        nextLayoutData: Any?,
    ): Boolean = false

    override fun onUnbindItem(
        extensionState: ExtensionState<Unit>,
        renderUnit: RenderUnit<*>,
        content: Any,
        layoutData: Any?,
    ) = Unit

    override fun onUnmountItem(
        extensionState: ExtensionState<Unit>,
        renderUnit: RenderUnit<*>,
        content: Any,
        layoutData: Any?,
    ) = Unit

    override fun onBoundsAppliedToItem(
        extensionState: ExtensionState<Unit>,
        renderUnit: RenderUnit<*>,
        content: Any,
        layoutData: Any?,
        changed: Boolean,
    ) = Unit
  }

  private fun createWorker(
      nanoTime: () -> Long,
      dispatcher:
          (
              IncrementalMountExtension,
              ExtensionState<IncrementalMountExtensionState>,
              Long,
              Long,
              () -> Long,
              PremountCostEstimator,
          ) -> PremountResult,
      estimator: PremountCostEstimator = estimator(),
  ): IncrementalMountGapWorker =
      IncrementalMountGapWorker.get(
          null,
          tracer,
          nanoTime,
          estimator,
          dispatcher,
      )

  private fun createCostAwareFixture(
      vararg results: PremountResult,
      afterDispatch: () -> Unit = {},
      mode: GapWorkerMode = GapWorkerMode.PREMOUNT,
  ): CostAwareFixture {
    val extension = IncrementalMountExtension.getInstance(mode)
    val extensionState = registerExtension(extension)
    return CostAwareFixture(
        extension,
        extensionState,
        results.toMutableList(),
        afterDispatch,
    )
  }

  private fun CostAwareFixture.asMountEventFixture(): MountEventFixture = MountEventFixture(
      delegate = extensionState.mountDelegate,
      acquireOrdinaryMountReference = {},
      dispatcher = dispatcher,
      setPendingPremountContentType = { contentType ->
        extensionState.state.costAwareScan.pendingContentType = contentType
      },
      pendingPremountContentType = { extensionState.state.costAwareScan.pendingContentType },
  )

  private fun wrapDispatcher(
      delegate:
          (
              IncrementalMountExtension,
              ExtensionState<IncrementalMountExtensionState>,
              Long,
              Long,
              () -> Long,
              PremountCostEstimator,
          ) -> PremountResult,
      block: (() -> PremountResult) -> PremountResult,
  ): (
      IncrementalMountExtension,
      ExtensionState<IncrementalMountExtensionState>,
      Long,
      Long,
      () -> Long,
      PremountCostEstimator,
  ) -> PremountResult =
      { extension, extensionState, deadlineNs, frameSequence, nanoTime, estimator ->
        block {
          delegate(
              extension,
              extensionState,
              deadlineNs,
              frameSequence,
              nanoTime,
              estimator,
          )
        }
      }

  private fun dispatcherFor(
      vararg fixtures: CostAwareFixture,
  ): (
      IncrementalMountExtension,
      ExtensionState<IncrementalMountExtensionState>,
      Long,
      Long,
      () -> Long,
      PremountCostEstimator,
  ) -> PremountResult =
      { extension, extensionState, deadlineNs, frameSequence, nanoTime, estimator ->
        fixtures
            .single { it.extensionState === extensionState }
            .dispatch(
                extension,
                extensionState,
                deadlineNs,
                frameSequence,
                nanoTime,
                estimator,
            )
      }

  private fun throwingPremountDispatcher():
      (
          IncrementalMountExtension,
          ExtensionState<IncrementalMountExtensionState>,
          Long,
          Long,
          () -> Long,
          PremountCostEstimator,
      ) -> PremountResult = { _, _, _, _, _, _ ->
    error("dispatcher accessed")
  }

  private fun estimator(
      onEstimate: (Any) -> Long? = { null },
      onRecord: (Any, Long) -> Unit = { _, _ -> },
  ): PremountCostEstimator =
      object : PremountCostEstimator {
        // Named apart from the overrides: an `estimateNs(...)` call inside this object would
        // resolve to the member below, not to the lambda, and recurse forever.
        override fun estimateNs(contentType: Any): Long? = onEstimate(contentType)

        override fun record(contentType: Any, actualNs: Long) = onRecord(contentType, actualNs)
      }

  private class CostAwareFixture(
      val extension: IncrementalMountExtension,
      val extensionState: ExtensionState<IncrementalMountExtensionState>,
      private val results: MutableList<PremountResult>,
      private val afterDispatch: () -> Unit,
  ) {
    val calls = mutableListOf<PremountDispatchCall>()
    val resultsReturned = mutableListOf<PremountResult>()
    val dispatcher:
        (
            IncrementalMountExtension,
            ExtensionState<IncrementalMountExtensionState>,
            Long,
            Long,
            () -> Long,
            PremountCostEstimator,
        ) -> PremountResult =
        ::dispatch

    fun dispatch(
        extension: IncrementalMountExtension,
        extensionState: ExtensionState<IncrementalMountExtensionState>,
        deadlineNs: Long,
        frameSequence: Long,
        nanoTime: () -> Long,
        estimator: PremountCostEstimator,
    ): PremountResult {
      calls.add(
          PremountDispatchCall(
              extension,
              extensionState,
              deadlineNs,
              frameSequence,
              nanoTime,
              estimator,
          ),
      )
      val result = results.removeAt(0)
      resultsReturned.add(result)
      afterDispatch()
      return result
    }
  }

  private data class PremountDispatchCall(
      val extension: IncrementalMountExtension,
      val extensionState: ExtensionState<IncrementalMountExtensionState>,
      val deadlineNs: Long,
      val frameSequence: Long,
      val nanoTime: () -> Long,
      val estimator: PremountCostEstimator,
  )

  private class RecordingGapWorkerExtension(
      val items: MutableList<Int> = mutableListOf(1),
      private val throwOnPremount: Boolean = false,
  ) : MountExtension<Unit, Unit>(), GapWorkerCallbacks<Unit> {
    val premountedItems = mutableListOf<Int>()
    val nestCountsDuringPremount = mutableListOf<Int>()
    val premountCalls: Int
      get() = premountedItems.size

    var hasItemCalls = 0

    override fun createState(): Unit = Unit

    override fun onRegisterForPremount(extensionState: ExtensionState<Unit>, frameTimeMs: Long?) =
        Unit

    override fun onUnregisterForPremount(extensionState: ExtensionState<Unit>) = Unit

    override fun hasItemToMount(extensionState: ExtensionState<Unit>): Boolean {
      hasItemCalls++
      return items.isNotEmpty()
    }

    override fun premountNext(extensionState: ExtensionState<Unit>) {
      nestCountsDuringPremount.add(
          notifyVisibleBoundsChangedNestCount(extensionState.mountDelegate),
      )
      if (throwOnPremount) {
        error("premount failed")
      }
      premountedItems.add(items.removeAt(0))
    }
  }

  @Test
  fun whenDeadlineBufferIsDefault_computeDeadlineMatchesFrameEndMinusFourMs() {
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 4L
    val worker = IncrementalMountGapWorker.get(null, tracer)

    assertThat(worker.computeDeadlineNs()).isEqualTo(FRAME_END_60HZ_NS - 4_000_000L)
  }

  @Test
  fun whenDeadlineBufferIsZero_computeDeadlineEqualsFrameEnd() {
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 0L
    val worker = IncrementalMountGapWorker.get(null, tracer)

    assertThat(worker.computeDeadlineNs()).isEqualTo(FRAME_END_60HZ_NS)
  }

  @Test
  fun whenDeadlineBufferChangesAfterClassInit_computeDeadlineUsesTheNewValue() {
    val worker = IncrementalMountGapWorker.get(null, tracer)

    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 4L
    val withDefaultBuffer = worker.computeDeadlineNs()

    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 8L
    val withLargerBuffer = worker.computeDeadlineNs()

    assertThat(withLargerBuffer).isEqualTo(withDefaultBuffer - 4_000_000L)
  }

  @Test
  fun whenRefreshFlagOff_frameIntervalStaysAtConstructionValue() {
    RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame = false
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 0L

    val display = displayWithRefreshRate(90f)
    val worker = IncrementalMountGapWorker.get(display, tracer)

    setRefreshRate(display, 120f)
    worker.doFrame(0L)

    assertThat(worker.computeDeadlineNs()).isEqualTo(frameIntervalNsFor(90f))
  }

  @Test
  fun whenRefreshFlagOn_frameIntervalTracksDisplayRefreshRate() {
    RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame = true
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 0L

    val display = displayWithRefreshRate(90f)
    val worker = IncrementalMountGapWorker.get(display, tracer)

    setRefreshRate(display, 120f)
    worker.doFrame(0L)

    assertThat(worker.computeDeadlineNs()).isEqualTo(frameIntervalNsFor(120f))
  }

  @Test
  fun whenRefreshFlagOn_andRefreshRateBelowFloor_frameIntervalFallsBackTo60Hz() {
    RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame = true
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 0L

    val display = displayWithRefreshRate(90f)
    val worker = IncrementalMountGapWorker.get(display, tracer)

    setRefreshRate(display, 24f)
    worker.doFrame(0L)

    assertThat(worker.computeDeadlineNs()).isEqualTo(FRAME_END_60HZ_NS)
  }

  @Test
  fun whenMarginFractionIsZero_theAbsoluteBufferIsUsed() {
    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = 0.0
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 4L
    val worker = IncrementalMountGapWorker.get(null, tracer)

    assertThat(worker.computeDeadlineNs()).isEqualTo(FRAME_END_60HZ_NS - 4_000_000L)
  }

  @Test
  fun whenMarginFractionIsSet_itReplacesTheAbsoluteBuffer() {
    // The buffer is deliberately not 4ms, so a deadline of frame end minus 4ms can only come from
    // the fraction.
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 9L
    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = SIXTY_HZ_EQUIVALENT_FRACTION
    val worker = IncrementalMountGapWorker.get(null, tracer)

    assertThat(worker.computeDeadlineNs()).isEqualTo(FRAME_END_60HZ_NS - 4_000_000L)
  }

  @Test
  fun atOneHundredTwentyHertz_theFractionScalesTheMarginWhereTheAbsoluteBufferDoesNot() {
    RenderCoreConfig.refreshGapWorkerFrameIntervalPerFrame = true
    val frameIntervalNs = frameIntervalNsFor(120f)
    val worker = IncrementalMountGapWorker.get(displayWithRefreshRate(120f), tracer)
    worker.doFrame(0L)

    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = 0.0
    IncrementalMountExtensionConfigs.gapWorkerDeadlineBufferMs = 4L
    val absoluteMarginNs = frameIntervalNs - worker.computeDeadlineNs()

    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = SIXTY_HZ_EQUIVALENT_FRACTION
    val fractionalMarginNs = frameIntervalNs - worker.computeDeadlineNs()

    // The absolute buffer keeps eating 4ms of a frame that is now only 8.3ms long.
    assertThat(absoluteMarginNs).isEqualTo(4_000_000L)
    assertThat(fractionalMarginNs).isEqualTo(2_000_000L)
  }

  @Test
  fun whenMarginFractionIsBelowOne_theMarginIsNotClamped() {
    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = 0.5
    val worker = IncrementalMountGapWorker.get(null, tracer)

    assertThat(worker.computeDeadlineNs()).isEqualTo(FRAME_END_60HZ_NS - 8_333_334L)
  }

  @Test
  fun whenMarginFractionExceedsOne_theDeadlineIsNotBeforeTheFrameStart() {
    IncrementalMountExtensionConfigs.gapWorkerMarginFraction = 5.0
    val worker = IncrementalMountGapWorker.get(null, tracer)

    assertThat(worker.computeDeadlineNs()).isEqualTo(0L)
  }

  private fun displayWithRefreshRate(rate: Float): Display {
    val display = ShadowDisplay.getDefaultDisplay()
    setRefreshRate(display, rate)
    return display
  }

  private fun setRefreshRate(display: Display, rate: Float) {
    Shadows.shadowOf(display).setRefreshRate(rate)
  }

  private fun frameIntervalNsFor(rate: Float): Long = (1000000000 / rate).toLong()

  companion object {

    // A worker that has not seen a frame has latestFrameTimeNs == 0, and a null display falls back
    // to 60Hz. The odd last digit is float rounding in (1_000_000_000 / 60.0f).
    private const val FRAME_END_60HZ_NS = 16_666_667L
    private const val FIRST_FRAME_TIME_NS = 7_000_000L
    private const val SECOND_FRAME_TIME_NS = 9_000_000L
    private const val NON_ZERO_DISPATCH_TIME_NS = 5_000_000L

    // 4ms of a 16.667ms frame, so the fraction is a no-op at 60Hz.
    private const val SIXTY_HZ_EQUIVALENT_FRACTION = 0.24
    private const val GAP_WORKER_PARENT_ID = 1L
    private const val GAP_WORKER_MOUNT_ID = 2L
    private const val ORDINARY_MOUNT_ID = 3L
    private const val NESTED_MOUNT_ID = 4L
    private const val SECOND_GAP_WORKER_MOUNT_ID = 4L
    private const val PREMOUNT_CONTENT_TYPE = "premount-content-type"
  }
}

private fun notifyVisibleBoundsChangedNestCount(delegate: MountDelegate): Int {
  val field = delegate.javaClass.getDeclaredField("notifyVisibleBoundsChangedNestCount")
  field.isAccessible = true
  return field.getInt(delegate)
}
