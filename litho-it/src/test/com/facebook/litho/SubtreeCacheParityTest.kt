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

package com.facebook.litho

import android.view.View
import androidx.collection.MutableScatterSet
import androidx.collection.mutableScatterSetOf
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.border
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.state.StateId
import com.facebook.litho.stats.LithoStats
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.transition.transitionKey
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.foregroundColor
import com.facebook.litho.view.onClick
import com.facebook.litho.view.testKey
import com.facebook.litho.visibility.onVisible
import com.facebook.litho.widget.MountSpecExcludeFromIncrementalMount
import com.facebook.litho.widget.PrimitiveWithLayoutEffect
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.incrementalmount.IncrementalMountOutput
import com.facebook.rendercore.px
import com.facebook.rendercore.sp
import com.facebook.yoga.YogaJustify
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/**
 * Parity tests verifying that subtree caching produces IDENTICAL reduce outputs to baseline.
 *
 * Strategy: for each fixture, do two layout passes (initial + state-driven re-layout). Run the
 * re-layout twice — once with caching OFF, once with caching ON — and compare every output field.
 * Any divergence is a correctness bug in the splice path.
 *
 * Coverage:
 * - positionStable splice (dx=dy=0): flat list, nested list, dashboard, single-deep, mixed feed
 * - position-offset splice (dy≠0): sibling-sink height shift (NOT root prop change)
 * - hosted subtrees (needsHostView=true): rows with onClick handlers
 * - visibility outputs: rows with onVisible handlers
 * - transitions: rows with transitionKey
 * - threshold-excluded subtrees: wide-shallow Text leaves
 * - flag matrix: useFinalizeForIMOBounds
 * - deep DiffNode comparison (per-node, recursive)
 * - multi-pass parity (verify cache survives N updates without drift)
 * - splice-fires invariant (hits ≥ expected count, not just > 0)
 */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class SubtreeCacheParityTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  private val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
  private val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)

  private companion object {
    /** Default row count for parity tests. Large enough to populate multiple cached subtrees. */
    const val DEFAULT_ROW_COUNT = 20

    /** Larger row count for stress tests that want >50 cached subtrees in one tree. */
    const val LARGE_ROW_COUNT = 50

    /** Wide-shallow row count: must exceed any plausible threshold-coincidence to isolate cause. */
    const val WIDE_SHALLOW_ROW_COUNT = 100

    /** Multi-pass iterations: chosen to amplify SubtreeRange index drift across many splices. */
    const val MULTI_PASS_ITERATIONS = 50

    /**
     * Sentinel for splice-fires invariant: number of cached subtrees we expect to splice per pass.
     * Tests using DEFAULT_ROW_COUNT FeedRows expect ≥ this many hits after one update.
     */
    const val EXPECTED_HITS_PER_PASS = DEFAULT_ROW_COUNT.toLong()
  }

  private var prevEnableSubtreeCaching = false
  private var prevUseFinalizeForIMOBounds = false
  private var prevIsEndToEndTestRun = false

  @Before
  fun setup() {
    prevEnableSubtreeCaching = ComponentsConfiguration.enableSubtreeCaching
    prevUseFinalizeForIMOBounds = ComponentsConfiguration.useFinalizeForIMOBounds
    prevIsEndToEndTestRun = ComponentsConfiguration.isEndToEndTestRun
    LithoStats.resetAllCounters()
  }

  @After
  fun teardown() {
    ComponentsConfiguration.enableSubtreeCaching = prevEnableSubtreeCaching
    ComponentsConfiguration.useFinalizeForIMOBounds = prevUseFinalizeForIMOBounds
    ComponentsConfiguration.isEndToEndTestRun = prevIsEndToEndTestRun
    ParityStateHolder.state = null
    ParityStateHolder.heightState = null
    ParityStateHolder.innerState = null
  }

  // ── positionStable: flat list of rows ──

  @Test fun parity_flatList_50rows() = assertParity { ParityStatefulRoot(50) }

  @Test fun parity_flatList_10rows() = assertParity { ParityStatefulRoot(10) }

  // ── positionStable: dashboard (wider per-card subtrees) ──

  @Test fun parity_dashboard() = assertParity { ParityStatefulDashboard() }

  // ── positionStable: deep nesting ──

  @Test fun parity_deepNested_30x8() = assertParity { ParityStatefulDeep(width = 30, depth = 8) }

  @Test fun parity_deepNested_5x3() = assertParity { ParityStatefulDeep(width = 5, depth = 3) }

  // ── positionStable: single deep subtree ──

  @Test fun parity_singleHeavy_depth20() = assertParity { ParityStatefulSingleHeavy(depth = 20) }

  // ── positionStable: realistic mixed row types ──

  @Test fun parity_realisticFeed_20rows() = assertParity { ParityStatefulRealisticFeed(20) }

  @Test
  fun `normal reduce captures content type and immediate host id`() {
    ComponentsConfiguration.enableSubtreeCaching = false
    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulNestedHostMetadata(20),
        widthSpec,
        heightSpec,
    )

    assertIncrementalMountMetadata(captureLayoutState(componentTree), requireFastPath = false)
  }

  @Test
  fun `fast splice captures content type and immediate host id`() {
    ComponentsConfiguration.enableSubtreeCaching = true
    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulNestedHostMetadata(20),
        widthSpec,
        heightSpec,
    )

    LithoStats.resetAllCounters()
    ParityStateHolder.state?.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulNestedHostMetadata(20),
        widthSpec,
        heightSpec,
    )

    assertThat(LithoStats.subtreeCacheHitCount.get())
        .describedAs("splice did not engage; metadata assertion would be vacuous")
        .isGreaterThan(0)
    assertIncrementalMountMetadata(captureLayoutState(componentTree), requireFastPath = true)
  }

  // ── Flag matrix: positionStable × useFinalizeForIMOBounds ──
  // Flag is restored by `@After` (see `teardown`); per-test cleanup is not needed.

  @Test
  fun parity_flatList_withFinalizeIMO() =
      withFinalizeFlags(imo = true) { assertParity { ParityStatefulRoot(DEFAULT_ROW_COUNT) } }

  // ── position-offset: sibling sink drives height shift via state ── //
  // NOTE: spacerHeight is in useState in HeightShiftSink (a sibling), NOT a constructor
  // prop on the root. If the prop were on the root, isReconcilable=false would cause
  // full re-resolve and splice would never engage — making the parity test vacuous.

  @Test fun parity_heightShift_10rows() = assertParity { ParityStatefulHeightShift(10) }

  @Test
  fun parity_heightShift_withFinalize() =
      withFinalizeFlags(imo = true) { assertParity { ParityStatefulHeightShift(10) } }

  // ── Hosted subtree: rows with onClick force needsHostView=true ── //
  // Splice gate excludes hosted subtrees (`hostRenderUnit != null`) — but parity must
  // still hold when hosted subtrees are PRESENT in the tree (they just don't splice).

  // Hosted rows fail the `hostRenderUnit == null` splice gate. Inner Texts are below
  // MIN_SUBTREE_SCOPES, so the whole tree produces zero splice hits — opt out of the
  // splice-fired guard.
  @Test
  fun parity_hostedRows_10() = assertParity(requireSplice = false) { ParityStatefulHostedRows(10) }

  @Test fun parity_mixedHostedAndCacheable() = assertParity { ParityStatefulMixedHosted(10) }

  // ── External parent ID change (host gained/lost in chain) ── //
  // The IDENTITY-change branch of the gate (externalParentRenderUnitId mismatch) is covered
  // directly in [LithoReducerExternalParentMatchesTest]. It cannot be exercised end-to-end via
  // state-driven re-layout: any state read that toggles a host-affecting prop (onClick /
  // transitionKey / etc.) on a wrapper marks that wrapper's scope dirty; `getReconciliationMode`
  // returns RECREATE for the dirty wrapper, which fully re-resolves its subtree. The descendants
  // become FRESH LithoNodes — and because [reductionState.subtreeSnapshots] is keyed by LithoNode
  // identity, the snapshot lookup misses and the gate is never reached.

  // ── External parent position-only shift (same RU id, different absoluteY) ── //
  // The wrapper has onClick (so it gets a host RTN). A SIBLING above it has state-driven
  // height. When the sibling grows, Yoga reflows the outer Column: the wrapper's RU id is
  // unchanged (LithoNode preserved by reconcile, props identical) but its absoluteY shifts.
  // The cached subtree (rows under the wrapper) has unchanged input constraints → cached →
  // reconciled → snapshot exists. The gate's RU id check passes; the gate's absoluteY check
  // catches the parent shift.
  //
  // No parity-OFF-vs-ON test for this fixture: every splice attempt is rejected by the gate, so
  // hits = 0 → parity comparison is vacuous (cache OFF and cache ON both fall back to baseline).
  // The gate's correctness is verified by counting rejects directly below.

  @Test
  fun splice_rejects_when_external_parent_shifts_position() {
    ComponentsConfiguration.enableSubtreeCaching = true
    LithoStats.resetAllCounters()

    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulPositionShiftedExternalParent(rows = 10),
        widthSpec,
        heightSpec,
    )
    val capturedHeight =
        checkNotNull(ParityStateHolder.heightState) { "height sink did not capture state" }

    // The state update queues an uncommitted update; the second setRoot is what actually
    // synchronously processes it. Inside `setRootAndSizeSpecInternal`, `hasUncommittedUpdates`
    // forces a shallow-clone of the new root (line 1626 in ComponentTree.kt), which in turn
    // forces `doResolve` → `Resolver.resolveTree`. There `isReconcilable` returns true because
    // `treeState.hasUncommittedUpdates()` is set, so reconcile runs: the sink is RECREATE,
    // the wrapper is REUSE (clean scope), and Yoga reflows the outer Column shifting the
    // wrapper's absoluteY. The splice gate sees the position mismatch and rejects.
    capturedHeight.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulPositionShiftedExternalParent(rows = 10),
        widthSpec,
        heightSpec,
    )

    val gateRejects = LithoStats.subtreeCacheGateRejectCount.get()
    assertThat(gateRejects)
        .describedAs(
            "splice gate should reject cached subtrees whose external parent shifted absoluteY " +
                "(sibling above hosted wrapper grew)",
        )
        .isGreaterThan(0)
  }

  // ── Visibility outputs: onVisible handlers add VisibilityOutput entries ── //

  @Test fun parity_withVisibility_10rows() = assertParity { ParityStatefulVisibilityRows(10) }

  // ── Transitions: rows with transitionKey ── //
  // `Style.transitionKey(...)` forces `needsHostViewForTransition=true` (LithoNode.kt:1333),
  // which fails the splice gate (`hostRenderUnit == null`). Cached children inside transition
  // rows are too small to clear the threshold. Opt out of the splice-fired guard.

  @Test
  fun parity_withTransitions_10rows() =
      assertParity(requireSplice = false) { ParityStatefulTransitionRows(10) }

  // ── Offset-path visibility: covers VisibilityOutput rebuild branch with dy ≠ 0 ── //

  @Test
  fun parity_heightShift_visibility_10rows() = assertParity {
    ParityStatefulHeightShiftVisibilityRows(10)
  }

  // ── useEffect: parity must hold for attachables under cache ON vs OFF ── //

  @Test fun parity_withEffects_10rows() = assertParity { ParityStatefulEffectRows(10) }

  // ── Layout-phase useEffect: covers attachable range replay in spliceFromSnapshot ── //
  // Unlike resolve-phase `ComponentScope.useEffect` (which is pre-populated into ReductionState
  // before reduction starts), `LayoutScope.useEffect` inside a Primitive's calculateLayout
  // produces effects on `LithoLayoutResult.effects` that are accumulated per-node during
  // reduction. The splice path must replay these via `SubtreeSnapshot.attachableStart..End`.

  @Test
  fun parity_withLayoutPhaseEffects_10rows() = assertParity {
    ParityStatefulLayoutPhaseEffectRows(10)
  }

  // ── Nested useState inside FeedRow: covers stateReads replay path ── //

  @Test fun parity_withLocalState_10rows() = assertParity { ParityStatefulLocalStateRows(10) }

  // ── excludeFromIncrementalMount=true: covers IM-flag propagation in splice ── //
  // The splice path explicitly bubbles the excludeFromIM flag up the new ancestor RTN
  // chain in spliceFromRange's reused-IMO branch (LithoReducer.kt). Without that walk,
  // the new ancestor IMO (built by current pass's baseline) wouldn't learn about the
  // spliced descendant's flag because the reused IMO's hostOutput field still points
  // at PREV's ancestors.

  // The flat row (Text + MountSpec) only contributes 2 Spec scopes per row — below
  // MIN_SUBTREE_SCOPES — so no row-level subtree splices. The test still validates the
  // parity invariant (no caching = no harm) on the excludeFromIM-bearing tree shape.
  // Deep-nested coverage that DOES exercise the splice + bubble-up walk lives below
  // (`parity_excludeFromIM_deepNested`).
  @Test
  fun parity_excludeFromIM_10rows() =
      assertParity(requireSplice = false) { ParityStatefulExcludeFromIMRows(10) }

  // ── testKey: populates TestOutput list — only meaningful with isEndToEndTestRun=true ── //

  // TestKey rows are bare `Row { Text }` — too small to clear MIN_SUBTREE_SCOPES, so the
  // row-level subtrees don't splice. The test still asserts parity (no drift in the
  // test-output array layout) and exercises the testKey field on TestOutput.
  @Test
  fun parity_withTestKeys_10rows() {
    // `isEndToEndTestRun` is restored by @After (saved in setup()).
    ComponentsConfiguration.isEndToEndTestRun = true
    assertParity(requireSplice = false) { ParityStatefulTestKeyRows(10) }
  }

  // ── wide shallow: Text leaves below MIN_SUBTREE_SCOPES threshold ── //
  // No subtrees clear the threshold, so splice never fires — opt out of the splice-fired
  // guard. The parity comparison still validates that no caching does no harm.

  @Test
  fun parity_wideShallow_noRegression() =
      assertParity(requireSplice = false) { ParityStatefulWideShallow(WIDE_SHALLOW_ROW_COUNT) }

  // ── Decorated rows: verify background/foreground/border outputs survive splice ── //

  @Test fun parity_decoratedRows_10rows() = assertParity { ParityStatefulDecoratedRows(10) }

  // ── Deep-nested excludeFromIncrementalMount: verify the bubble-up walk reaches all ancestors ──
  // //
  // Each row wraps the excluded MountSpec under 4 nested Column LithoNodes. The splice path must
  // propagate the excludeFromIM flag up through every new ancestor, not just the immediate one.

  @Test
  fun parity_excludeFromIM_deepNested() = assertParity { ParityStatefulDeepExcludeFromIMRows(5) }

  // ── Kitchen-sink: every SubtreeSnapshot field exercised in one cached subtree ── //
  // The single-concern fixtures above each populate ONE field of SubtreeSnapshot non-trivially
  // (visibility OR test outputs OR attachables OR state reads OR offset shift). Most other
  // fixtures pass 0==0 vacuously for those fields, so a bug affecting the INTERACTION between
  // fields would slip through the scattered coverage. The kitchen-sink fixture combines them
  // all in one cached row to validate cross-field interactions.

  @Test
  fun parity_kitchenSink_10rows() {
    // testKey only emits TestOutput entries when isEndToEndTestRun is true (restored by @After).
    ComponentsConfiguration.isEndToEndTestRun = true
    assertParity { ParityStatefulKitchenSinkRows(10) }
  }

  @Test
  fun parity_kitchenSink_heightShift_10rows() {
    ComponentsConfiguration.isEndToEndTestRun = true
    assertParity { ParityStatefulHeightShiftKitchenSinkRows(10) }
  }

  @Test
  fun parity_multiPass_kitchenSink_50_iterations() {
    ComponentsConfiguration.isEndToEndTestRun = true
    assertParityMultiPass(passes = MULTI_PASS_ITERATIONS) {
      ParityStatefulKitchenSinkRows(DEFAULT_ROW_COUNT)
    }
  }

  // Direct snapshot-field assertion: validates that each cached SubtreeSnapshot has the
  // expected field POPULATION (non-empty ranges where they should be), not just that the
  // OUTPUTS round-trip correctly. A snapshot with subtly-wrong index ranges might still
  // produce passing parity outputs by luck, but downstream code (next-pass splice) would
  // misbehave. Asserting directly on the snapshot map catches snapshot-write bugs that
  // output parity would mask.
  @Test
  fun snapshot_fields_populated_for_kitchenSink() {
    ComponentsConfiguration.isEndToEndTestRun = true
    ComponentsConfiguration.enableSubtreeCaching = true
    LithoStats.resetAllCounters()

    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(ParityStatefulKitchenSinkRows(10), widthSpec, heightSpec)
    val capturedState = checkNotNull(ParityStateHolder.state) { "state not captured" }
    capturedState.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(ParityStatefulKitchenSinkRows(10), widthSpec, heightSpec)

    val layoutState = captureLayoutState(componentTree)
    val snapshots =
        checkNotNull(layoutState.subtreeSnapshots) {
          "subtreeSnapshots map is null — splice never wrote any snapshot"
        }

    assertThat(snapshots.size)
        .describedAs("expected ≥ 10 cached subtree snapshots (one per kitchen-sink row)")
        .isGreaterThanOrEqualTo(10)

    var snapshotsWithVisibility = 0
    var snapshotsWithTestOutputs = 0
    snapshots.forEachValue { snapshot ->
      if (snapshot.visibilityOutputEnd > snapshot.visibilityOutputStart) snapshotsWithVisibility++
      if (snapshot.testOutputEnd > snapshot.testOutputStart) snapshotsWithTestOutputs++
    }

    assertThat(snapshotsWithVisibility)
        .describedAs("snapshots populating visibility range (onVisible per row)")
        .isGreaterThanOrEqualTo(10)
    assertThat(snapshotsWithTestOutputs)
        .describedAs("snapshots populating test-output range (testKey per row)")
        .isGreaterThanOrEqualTo(10)
  }

  @Test
  fun snapshot_attachable_range_populated_for_layoutPhaseEffects() {
    ComponentsConfiguration.enableSubtreeCaching = true
    LithoStats.resetAllCounters()

    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulLayoutPhaseEffectRows(10),
        widthSpec,
        heightSpec,
    )
    val capturedState = checkNotNull(ParityStateHolder.state) { "state not captured" }
    capturedState.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulLayoutPhaseEffectRows(10),
        widthSpec,
        heightSpec,
    )

    val layoutState = captureLayoutState(componentTree)
    val snapshots =
        checkNotNull(layoutState.subtreeSnapshots) {
          "subtreeSnapshots map is null — splice never wrote any snapshot"
        }

    var snapshotsWithAttachables = 0
    snapshots.forEachValue { snapshot ->
      if (snapshot.attachableEnd > snapshot.attachableStart) snapshotsWithAttachables++
    }

    assertThat(snapshotsWithAttachables)
        .describedAs(
            "snapshots populating attachable range (layout-phase useEffect per row). " +
                "If 0, the PrimitiveWithLayoutEffect descendant's LayoutScope.useEffect " +
                "did not produce layout-phase attachables, or the snapshot recording " +
                "did not capture the attachable range.",
        )
        .isGreaterThanOrEqualTo(10)
  }

  // ── Multi-pass: verify cache survives many updates without drift ── //

  @Test
  fun parity_multiPass_50_iterations() {
    assertParityMultiPass(passes = MULTI_PASS_ITERATIONS) { ParityStatefulRoot(DEFAULT_ROW_COUNT) }
  }

  @Test
  fun parity_multiPass_heightShift_50_iterations() {
    assertParityMultiPass(passes = MULTI_PASS_ITERATIONS) {
      ParityStatefulHeightShift(DEFAULT_ROW_COUNT)
    }
  }

  // Validates that local state INSIDE cached rows is replayed correctly across many splice
  // cycles. A "stateReads dropped on second cache hit" regression would flip on pass ≥ 2.
  @Test
  fun parity_multiPass_localState_50_iterations() {
    assertParityMultiPass(passes = MULTI_PASS_ITERATIONS) {
      ParityStatefulLocalStateRows(DEFAULT_ROW_COUNT)
    }
  }

  // Validates that when the SINK changes its mountable-output count between passes (1 vs 3
  // outputs), cached siblings still splice correctly — their absolute indices in the new
  // pass's `mountableOutputs` shift, but the descendant-only SubtreeRange must accommodate.
  @Test
  fun parity_multiPass_structurallyVaryingSink_50_iterations() {
    assertParityMultiPass(passes = MULTI_PASS_ITERATIONS) {
      ParityStatefulStructurallyVaryingRoot(DEFAULT_ROW_COUNT)
    }
  }

  // ── Splice-fires invariants (tighter assertions) ── //

  @Test
  fun splice_fires_for_all_eligible_subtrees_flatList() = assertSpliceHits(
      stateHolder = { ParityStateHolder.state },
      minimumHits = EXPECTED_HITS_PER_PASS,
      buildRoot = { ParityStatefulRoot(DEFAULT_ROW_COUNT) },
  )

  @Test
  fun splice_fires_for_offset_path_heightShift() = assertSpliceHits(
      stateHolder = { ParityStateHolder.heightState },
      minimumHits = EXPECTED_HITS_PER_PASS,
      buildRoot = { ParityStatefulHeightShift(DEFAULT_ROW_COUNT) },
  )

  @Test
  fun splice_does_not_fire_below_threshold() = assertSpliceHits(
      stateHolder = { ParityStateHolder.state },
      expectedHits = 0L,
      buildRoot = { ParityStatefulWideShallow(LARGE_ROW_COUNT) },
  )

  @Test
  fun splice_does_not_fire_for_hosted_subtrees() {
    // Hosted subtree itself fails the `hostRenderUnit == null` gate. The inner Texts inside
    // ParityHostedFeedRow are too small to clear MIN_SUBTREE_SCOPES, so the WHOLE tree
    // should produce zero splice hits.
    assertSpliceHits(
        stateHolder = { ParityStateHolder.state },
        expectedHits = 0L,
        buildRoot = { ParityStatefulHostedRows(10) },
    )
  }

  // External-parent-change rejection scenarios are covered higher in the file (position-shift
  // via `splice_rejects_when_external_parent_shifts_position`) and as direct unit tests in
  // [LithoReducerExternalParentMatchesTest].

  // ── Width-change parity:

  // ── Width-change parity: the cached subtree's root position is unchanged but its allocated
  // size differs between passes (parent grows / shrinks). Yoga marks cached LithoNodes as
  // `isCachedLayout=true` because their intrinsic measurement is stable, but their children
  // can re-position (justify-end shifts right when container grows) or even re-size (flex-grow
  // children grow). The splice must:
  //   1. Apply per-direct-child position offsets when children shifted but didn't resize.
  //   2. Fall back to baseline when any direct child resized (interior may re-layout
  //      non-uniformly; no safe single-offset shortcut).
  // Bug history: pre-Tier-1 splice reused PREV pass's local bounds verbatim when the subtree
  // root's POSITION didn't change, regardless of whether its SIZE changed. This produced
  // ~1% pixel diffs at right-aligned children in the UFI screenshot tests
  // (MediaUfiLithoBinderScreenshotTest).

  @Test
  fun parity_widthChange_justifyEnd_positionsShift() = assertParityOnWidthChange {
    ParityStatefulJustifyEndRoot(10)
  }

  @Test
  fun parity_widthChange_justifyEnd_positionsShift_withFinalize() =
      withFinalizeFlags(imo = true) {
        assertParityOnWidthChange { ParityStatefulJustifyEndRoot(10) }
      }

  @Test
  fun parity_widthChange_spaceBetween_positionsShift() = assertParityOnWidthChange {
    ParityStatefulSpaceBetweenRoot(10)
  }

  /**
   * flex-grow inside a cached subtree: when the parent allocated width changes, the flex-grow
   * direct child resizes (its width changes proportionally). Splice MUST fall back to baseline for
   * that subtree because the resized child's interior may re-layout non-uniformly. We assert parity
   * (cache ON == cache OFF) but do NOT require splice to engage on these rows.
   */
  @Test
  fun parity_widthChange_flexGrow_fallsBackToBaseline() =
      assertParityOnWidthChange(requireSplice = false) { ParityStatefulFlexGrowRoot(10) }

  /**
   * Multi-pass width-change: alternate narrow/wide widths across N passes. Verifies that the cache
   * survives repeated size oscillations without drift — a classic regression pattern is "splice
   * produces correct output once, then subsequent passes accumulate stale state because the
   * snapshot captured per-child positions for the previous width and re-recording on hit gets
   * confused".
   */
  @Test
  fun parity_widthChange_justifyEnd_multiPass() =
      assertParityOnWidthChangeMultiPass(passes = MULTI_PASS_ITERATIONS) {
        ParityStatefulJustifyEndRoot(DEFAULT_ROW_COUNT)
      }

  /**
   * Shrink path: parent gets NARROWER between passes. Same per-child offset machinery as the grow
   * path but with negative offsets. Catches sign / overflow bugs in the offset arithmetic.
   */
  @Test
  fun parity_widthChange_justifyEnd_shrink() =
      assertParityOnWidthChange(initialWidthPx = 1500, finalWidthPx = 1080) {
        ParityStatefulJustifyEndRoot(10)
      }

  /**
   * No-op width change: identical width specs across both passes. Acts as a regression guard for
   * the per-child machinery — when widths match, every child has offset=(0,0) and splice should
   * behave identically to the pre-Tier-1 positionStable path.
   */
  @Test
  fun parity_widthChange_noChange_identicalToBaseline() =
      assertParityOnWidthChange(initialWidthPx = 1080, finalWidthPx = 1080) {
        ParityStatefulJustifyEndRoot(10)
      }

  // ── Splice-fires invariants for width-change paths ── //

  /**
   * Width grows; right-aligned children shift right within each row. Per-child offset path should
   * engage for every cached row (exact lower bound = 10).
   */
  @Test
  fun splice_fires_for_widthChange_justifyEnd() = assertSpliceHitsOnWidthChange(
      minimumHits = 10L,
      buildRoot = { ParityStatefulJustifyEndRoot(10) },
  )

  // NOTE: a stricter "splice does not fire when a direct child resizes" assertion is hard to
  // isolate because the OUTER subtree (Column containing the flex-grow rows) ALSO has resizing
  // direct children — so it correctly cache-misses, but other intermediate subtrees in the
  // tree may still cache and splice harmlessly. The `parity_widthChange_flexGrow_*` parity
  // assertions are the meaningful guard: cache ON output must equal cache OFF, regardless of
  // which subtrees engage the splice path.

  // ── Stale-reference regression guards ── //
  //
  // Output equivalence (parity) does not catch referential corruption: a spliced IMO can produce
  // the same logical output yet hold a stale reference into the previous LayoutState's IMO graph.
  // That stale reference manifests far downstream (mount/draw) as IndexOutOfBoundsException in
  // ComponentHost.getChildDrawingOrder when the host structure flips between passes. These tests
  // pin the splice path's referential invariants directly so a future "let's reuse the prev IMO
  // for zero alloc" optimization can't regress without a flag-day failure here.

  /**
   * Splice must build IMOs fresh in the current pass — no second-pass IMO may reference a
   * first-pass IMO via [IncrementalMountOutput.hostOutput]. Pre-fix, the splice path returned the
   * previous LayoutState's IMO instance verbatim when positionStable, leaving its `hostOutput`
   * field pointing at a discarded ancestor. When [LithoNode.needsHostView] flipped between passes
   * (driven by per-pass inputs like onClick presence), MountState used that stale chain and
   * produced viewMountItems that were inconsistent with the host's actual View child list.
   */
  @Test
  fun splice_does_not_carry_prev_pass_imo_references() {
    ComponentsConfiguration.enableSubtreeCaching = true
    val ct = createComponentTree()
    ct.setRootAndSizeSpecSync(ParityStatefulMixedHosted(20), widthSpec, heightSpec)
    val firstPassImoIdentities =
        java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<IncrementalMountOutput, Boolean>(),
        )
    captureLayoutState(ct).getIncrementalMountOutputs()?.let { firstPassImoIdentities.addAll(it) }

    LithoStats.resetAllCounters()
    ParityStateHolder.state?.update { it + 1 }
    ct.setRootAndSizeSpecSync(ParityStatefulMixedHosted(20), widthSpec, heightSpec)

    // Sanity #1: splice actually engaged — without hits, no spliced IMO exists to corrupt.
    assertThat(LithoStats.subtreeCacheHitCount.get())
        .describedAs("splice did not engage; assertion below would be vacuous")
        .isGreaterThan(0)

    val secondPassImos = captureLayoutState(ct).getIncrementalMountOutputs() ?: emptyList()
    var hostOutputChainsWalked = 0
    secondPassImos.forEach { imo ->
      var current: IncrementalMountOutput? = imo.hostOutput
      while (current != null) {
        hostOutputChainsWalked++
        assertThat(firstPassImoIdentities.contains(current))
            .describedAs(
                "IMO id=${imo.id} has hostOutput chain referencing a prev-pass IMO instance — " +
                    "splice must build IMOs fresh per pass to keep hostOutput in-graph",
            )
            .isFalse
        current = current.hostOutput
      }
    }

    // Sanity #2: at least one IMO had a non-null hostOutput, so the assertion above ran. Guards
    // against a regression that nulls all hostOutput fields and silently skips the inner loop.
    assertThat(hostOutputChainsWalked)
        .describedAs(
            "no second-pass IMO had a non-null hostOutput — chain assertion never executed; " +
                "fixture may have lost its hosted subtrees",
        )
        .isGreaterThan(0)
  }

  // ── Deferred descendant inside a cacheable subtree ── //
  // A `RenderWithConstraints` leaf's `actualResult` is computed at measure time and may evolve
  // across passes — `copyLayoutResult` nulls `actualDeferredNodeResult` whenever an outer cached
  // layout is reused, forcing `measureDeferredNode` to re-resolve. The splice path stores
  // descendant outputs by index range at recording time, so when the outer subtree's snapshot is
  // replayed on a later pass the spliced range still describes the previous actualResult — even
  // though the current actualResult has produced a different mountable-output shape.

  /**
   * Outer cache hits while a `RenderWithConstraints` descendant re-resolves with different content
   * shape. The deferred leaf reads `innerState` so its actualResult diverges across passes; the
   * cacheable rows above remain identical so splice fires for them. If splice also fires for the
   * row containing the deferred leaf and replays a stale snapshot, the mountable-output count
   * diverges from baseline.
   */
  @Test
  fun parity_deferredLeafInsideCacheableSubtree() = assertParity {
    ParityStatefulDeferredLeafRoot(rows = 10)
  }

  /**
   * Direct success-scenario assertion: the deferred-leaf row's enclosing LithoNode must NOT have a
   * recorded entry in [LayoutState.subtreeSnapshots] after a state-driven re-layout. Output parity
   * could pass by chance even if the gate is bypassed and the snapshot's stale outputs happened to
   * match, so this test asserts the contract directly. Companion to
   * [snapshot_fields_populated_for_kitchenSink].
   */
  @Test
  fun snapshot_absent_for_deferredLeafSubtree() {
    ComponentsConfiguration.enableSubtreeCaching = true
    LithoStats.resetAllCounters()

    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulDeferredLeafRoot(rows = 10),
        widthSpec,
        heightSpec,
    )
    val capturedState = checkNotNull(ParityStateHolder.state) { "outer state not captured" }
    val capturedInner = checkNotNull(ParityStateHolder.innerState) { "inner state not captured" }
    capturedState.update { it + 1 }
    capturedInner.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(
        ParityStatefulDeferredLeafRoot(rows = 10),
        widthSpec,
        heightSpec,
    )

    val layoutState = captureLayoutState(componentTree)
    val snapshots =
        checkNotNull(layoutState.subtreeSnapshots) {
          "subtreeSnapshots map is null — splice never wrote any snapshot"
        }

    // Cacheable feed rows still get snapshots — the deferred-row exclusion is selective, not
    // wholesale. Without this assertion a regression that disables ALL caching would also satisfy
    // the absence check below.
    assertThat(snapshots.size)
        .describedAs("non-deferred FeedRow subtrees should still cache (≥ 10 snapshots expected)")
        .isGreaterThanOrEqualTo(10)

    // Deferred gate signal — at least one rejection must have ticked. If zero, either the gate
    // is wired wrong or the fixture stopped containing a deferred descendant.
    assertThat(LithoStats.subtreeCacheDeferredSkippedCount.get())
        .describedAs("deferred-skip counter should fire for the deferred-row recording attempt")
        .isGreaterThan(0)

    // Direct contract: no snapshot entry should have its recording window flagged as
    // containing a deferred. Recording-time gate prevents the entry from being created at all.
    var leakedDeferredEntry = false
    snapshots.forEachValue { snapshot ->
      if (snapshot.containsDeferredDescendant) leakedDeferredEntry = true
    }
    assertThat(leakedDeferredEntry)
        .describedAs(
            "no snapshot entry should carry containsDeferredDescendant=true — recording-time " +
                "gate must skip them before they are stored",
        )
        .isFalse
  }

  /**
   * Multi-pass parity for the deferred-leaf shape. Verifies the gate holds across N updates — a
   * regression that fires the gate only on the first pass would silently drift on subsequent
   * passes.
   */
  @Test
  fun parity_deferredLeaf_multiPass() =
      assertParityMultiPass(passes = MULTI_PASS_ITERATIONS) {
        ParityStatefulDeferredLeafRoot(rows = 10)
      }

  /**
   * Deferred descendant whose `actualResult` shape is INVARIANT across passes (no state read inside
   * the deferred lambda). Pins the trade-off: the gate is intentionally coarse and skips caching
   * even when the deferred would have produced identical outputs. A future "smarter" gate that
   * compares actualResult identity must update this test to assert splice DOES fire.
   */
  @Test
  fun parity_deferredLeaf_invariantContent_stillSkipsCache() = assertParity {
    ParityStatefulInvariantDeferredLeafRoot(rows = 10)
  }

  /**
   * Multiple deferred descendants in the same recording window. Verifies the counter delta is "any
   * deferred entered" rather than "exactly one" — two increments still produce a single rejection
   * per enclosing subtree.
   */
  @Test
  fun parity_multipleDeferredLeavesInSubtree() = assertParity {
    ParityStatefulMultipleDeferredLeavesRoot(rows = 10)
  }

  /**
   * Nested deferred: a `RenderWithConstraints` whose body returns another `RenderWithConstraints`.
   * Each entry into the deferred branch increments the counter, and any enclosing subtree-root must
   * reject. Confirms the gate composes through multiple deferred boundaries without a hole.
   */
  @Test
  fun parity_nestedDeferredLeaves() = assertParity {
    ParityStatefulNestedDeferredLeafRoot(rows = 10)
  }

  // ── collectSubtreeStateReads filter contract ── //
  // Pins the (StateId → owner GlobalKey) filter that the splice path stores per SubtreeSnapshot
  // and replays into reductionState.stateReads. A regression here causes
  // [LayoutTreeFuture.willSkipLayout] to evaluate against an incomplete read graph — silent
  // downstream.

  @Test
  fun collectSubtreeStateReads_returnsNull_whenScopeRangeEmpty() {
    val state = newReductionStateWithReadsAndScopes(emptyMap(), emptyList())
    assertThat(LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 0)).isNull()
  }

  @Test
  fun collectSubtreeStateReads_returnsNull_whenStateReadsNeverAllocated() {
    val ownerKey = StringGlobalKey("subtree-root")
    val state = newReductionStateWithReadsAndScopes(emptyMap(), listOf(ownerKey))
    // stateReads stays null because the helper skips getOrCreateStateReads when empty.
    assertThat(LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 0)).isNull()
  }

  @Test
  fun collectSubtreeStateReads_returnsNull_whenStateReadsAllocatedButEmpty() {
    val ownerKey = StringGlobalKey("subtree-root")
    val state = newReductionStateWithReadsAndScopes(emptyMap(), listOf(ownerKey))
    state.getOrCreateStateReads() // force allocation; map remains empty
    assertThat(LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 0)).isNull()
  }

  @Test
  fun collectSubtreeStateReads_returnsNull_whenStartIndexEqualsScopeEnd() {
    val outsideKey = StringGlobalKey("outside")
    val outsideStateId = makeStateId(hash = 100)
    val state = newReductionStateWithReadsAndScopes(
        stateReads = mapOf(outsideStateId to setOf<GlobalKey>(outsideKey)),
        scopes = listOf(outsideKey),
    )
    // subtreeScopeStartIndex == componentScopes.size → empty range, short-circuit before allocating
    // the subtreeKeys HashSet even though stateReads is non-empty.
    assertThat(LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 1)).isNull()
  }

  @Test
  fun collectSubtreeStateReads_returnsNull_whenAllReadsOwnedOutsideSubtree() {
    val outsideKey = StringGlobalKey("outside")
    val insideKey = StringGlobalKey("inside")
    val outsideStateId = makeStateId(hash = 100)
    val state = newReductionStateWithReadsAndScopes(
        stateReads = mapOf(outsideStateId to setOf<GlobalKey>(outsideKey)),
        scopes = listOf(outsideKey, insideKey),
    )
    // subtreeStateReads stays null after the loop completes without matches — distinct from
    // "no reads at all" and from "filtered to empty map". A regression that wrongly initialized
    // the result map upfront would return empty-but-non-null and slip through.
    assertThat(LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 1)).isNull()
  }

  @Test
  fun collectSubtreeStateReads_skipsScopesWithNullGlobalKey() {
    val nullKeyScope = newScopedComponentInfoWithNullGlobalKey()
    val insideKey = StringGlobalKey("inside")
    val orphanStateId = makeStateId(hash = 100) // owned by null-key scope (which has no GlobalKey)
    val insideStateId = makeStateId(hash = 200)
    val state = newReductionStateWithReadsAndScopes(
        stateReads = mapOf(insideStateId to setOf<GlobalKey>(insideKey)),
        scopes = emptyList(),
    )
    // Append a scope whose nullableGlobalKey returns null — the filter must skip it without
    // poisoning the subtreeKeys set.
    state.componentScopes.add(0, nullKeyScope)
    state.componentScopes.add(makeScopedComponentInfoForKey(insideKey))

    val result = LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 0)

    assertThat(result).isNotNull
    assertThat(result!!.keys).containsExactly(insideStateId)
    assertThat(result[insideStateId]!!.asSet()).containsExactly(insideKey)
    // Confirm the null-key scope didn't accidentally enable any owner-match for orphanStateId:
    assertThat(result.keys).doesNotContain(orphanStateId)
  }

  @Test
  fun collectSubtreeStateReads_filtersOutReadsOwnedOutsideSubtree() {
    val outsideKey = StringGlobalKey("outside")
    val insideKey = StringGlobalKey("inside")
    val outsideStateId = makeStateId(hash = 100)
    val insideStateId = makeStateId(hash = 200)
    val state = newReductionStateWithReadsAndScopes(
        stateReads =
            mapOf(
                outsideStateId to setOf<GlobalKey>(outsideKey),
                insideStateId to setOf<GlobalKey>(insideKey),
            ),
        scopes = listOf(outsideKey, insideKey),
    )

    // subtreeScopeStartIndex = 1 → only the second scope (insideKey) is in the subtree
    val result = LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 1)

    assertThat(result).isNotNull
    assertThat(result!!.keys).containsExactly(insideStateId)
    assertThat(result[insideStateId]!!.asSet()).containsExactly(insideKey)
  }

  @Test
  fun collectSubtreeStateReads_aggregatesMultipleOwnersPerStateId() {
    val ownerA = StringGlobalKey("A")
    val ownerB = StringGlobalKey("B")
    val ownerC = StringGlobalKey("C") // outside the subtree
    val sharedStateId = makeStateId(hash = 300)
    val state = newReductionStateWithReadsAndScopes(
        stateReads = mapOf(sharedStateId to setOf<GlobalKey>(ownerA, ownerB, ownerC)),
        // ownerC at index 0 is outside the subtree; ownerA/ownerB at indices 1/2 are inside.
        scopes = listOf(ownerC, ownerA, ownerB),
    )

    val result = LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 1)

    // Both inside owners aggregated under the same StateId; outside owner excluded — proves
    // aggregation isn't a "return all" no-op.
    assertThat(result).isNotNull
    assertThat(result!![sharedStateId]!!.asSet()).containsExactlyInAnyOrder(ownerA, ownerB)
  }

  @Test
  fun collectSubtreeStateReads_partitionsOwnersOfSharedStateId() {
    val outsideKey = StringGlobalKey("outside")
    val insideKey = StringGlobalKey("inside")
    val mixedStateId = makeStateId(hash = 400)
    val state = newReductionStateWithReadsAndScopes(
        stateReads = mapOf(mixedStateId to setOf<GlobalKey>(outsideKey, insideKey)),
        scopes = listOf(outsideKey, insideKey),
    )

    // subtree starts at index 1 → only insideKey qualifies; per-owner filter on a single StateId.
    val result = LithoReducer.collectSubtreeStateReads(state, subtreeScopeStartIndex = 1)

    assertThat(result).isNotNull
    assertThat(result!![mixedStateId]!!.asSet()).containsExactly(insideKey)
  }

  /**
   * Build a [ReductionState] populated only with the fields [collectSubtreeStateReads] reads:
   * [componentScopes] (filter source) and [stateReads] (input read graph). All other fields use
   * defaults; the function never touches them.
   */
  private fun newReductionStateWithReadsAndScopes(
      stateReads: Map<StateId, Set<GlobalKey>>,
      scopes: List<GlobalKey>,
  ): ReductionState {
    val state = ReductionState(
        componentContext = lithoTestRule.context,
        sizeConstraints = SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec),
        currentLayoutState = null,
        rootX = 0,
        rootY = 0,
    )
    scopes.forEach { key -> state.componentScopes.add(makeScopedComponentInfoForKey(key)) }
    if (stateReads.isNotEmpty()) {
      val accumulated = state.getOrCreateStateReads()
      stateReads.forEach { (stateId, owners) ->
        val target = accumulated.getOrPut(stateId) { mutableScatterSetOf() }
        owners.forEach { target.add(it) }
      }
    }
    return state
  }

  /**
   * Build a [ScopedComponentInfo] whose `context.nullableGlobalKey` returns [globalKey]. Only that
   * lookup is exercised by [collectSubtreeStateReads]; the wrapped Component is irrelevant.
   */
  private fun makeScopedComponentInfoForKey(globalKey: GlobalKey): ScopedComponentInfo {
    val component = EmptyComponent()
    val ctx = ComponentContext.withComponentScope(lithoTestRule.context, component, globalKey)
    return ScopedComponentInfo(component, ctx, errorEventHandler = null)
  }

  /**
   * Build a [ScopedComponentInfo] whose `context.nullableGlobalKey` returns null. Used to exercise
   * the null-key skip in [collectSubtreeStateReads]'s scope walk.
   */
  private fun newScopedComponentInfoWithNullGlobalKey(): ScopedComponentInfo {
    val component = EmptyComponent()
    val ctx =
        ComponentContext.withComponentScope(lithoTestRule.context, component, null as GlobalKey?)
    return ScopedComponentInfo(component, ctx, errorEventHandler = null)
  }

  private fun makeStateId(hash: Int): StateId = StateId(
      treeId = 1,
      hookGlobalKey =
          HookGlobalKey(
              globalKey = StringGlobalKey("state-$hash"),
              hookKey = "hook-$hash",
              scope = RenderScope.Resolve,
          ),
  )

  private fun MutableScatterSet<GlobalKey>.asSet(): Set<GlobalKey> {
    val out = HashSet<GlobalKey>(size)
    forEach { out.add(it) }
    return out
  }

  // ── Helpers ──

  /**
   * Drive ONE state-update cycle on a fresh ComponentTree against [buildRoot] and assert the
   * resulting splice-hit count matches expectations. Exactly one of [expectedHits] (strict
   * equality, e.g. for "no splice expected") or [minimumHits] (lower bound, e.g. for "≥ N rows
   * spliced") must be specified.
   *
   * [stateHolder] returns the relevant captured State (e.g. `ParityStateHolder.state` for the
   * sink's tick or `heightState` for the offset-path sink). Failing to capture means the fixture's
   * sink was never rendered — fail fast with a clear message.
   */
  private fun assertSpliceHits(
      stateHolder: () -> State<Int>?,
      buildRoot: () -> Component,
      expectedHits: Long? = null,
      minimumHits: Long? = null,
  ) {
    require((expectedHits == null) xor (minimumHits == null)) {
      "Specify exactly one of expectedHits or minimumHits"
    }
    ComponentsConfiguration.enableSubtreeCaching = true
    LithoStats.resetAllCounters()

    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(buildRoot(), widthSpec, heightSpec)
    val capturedState =
        checkNotNull(stateHolder()) {
          "Fixture's sink did not capture state into ParityStateHolder — sink was never rendered"
        }
    capturedState.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(buildRoot(), widthSpec, heightSpec)

    val actualHits = LithoStats.subtreeCacheHitCount.get()
    if (expectedHits != null) {
      assertThat(actualHits)
          .describedAs("subtreeCacheHitCount (expected exactly)")
          .isEqualTo(expectedHits)
    } else {
      assertThat(actualHits)
          .describedAs("subtreeCacheHitCount (expected at least)")
          .isGreaterThanOrEqualTo(checkNotNull(minimumHits))
    }
  }

  private fun assertParity(requireSplice: Boolean = true, build: () -> Component) {
    assertParity(requireSplice = requireSplice, buildFirst = build, buildSecond = build)
  }

  /**
   * Compare cached vs uncached layout outputs after one state-driven re-layout.
   *
   * Strategy: build the tree twice — once with caching OFF (baseline) and once with caching ON. In
   * each run, drive the same state update through `ParityStateHolder` so both paths see identical
   * content changes. Then assert every output field matches via [compareLayoutStates].
   *
   * Set [requireSplice]=false ONLY for fixtures that intentionally produce zero splice hits (e.g.
   * wide-shallow-below-threshold, hosted-only-rows). All other tests must verify splice actually
   * engaged — otherwise the parity comparison is vacuous (e.g. a regression that disables splice
   * would silently make every test green).
   */
  private fun assertParity(
      requireSplice: Boolean,
      buildFirst: () -> Component,
      buildSecond: () -> Component,
  ) {
    val offState = runOneUpdateCycle(cachingEnabled = false, buildFirst, buildSecond)
    LithoStats.resetAllCounters()
    val onState = runOneUpdateCycle(cachingEnabled = true, buildFirst, buildSecond)

    if (requireSplice) {
      assertThat(LithoStats.subtreeCacheHitCount.get())
          .describedAs(
              "splice never engaged — parity comparison would be vacuous. " +
                  "If this fixture genuinely produces 0 hits, opt out via " +
                  "assertParity(requireSplice = false) { ... }",
          )
          .isGreaterThan(0)
    }
    compareLayoutStates(offState, onState)
  }

  /**
   * Runs a build → state-update → re-build cycle on a fresh ComponentTree, returning the committed
   * LayoutState. Resets the state holders before each call so consecutive cycles cannot contaminate
   * each other.
   */
  private fun runOneUpdateCycle(
      cachingEnabled: Boolean,
      buildFirst: () -> Component,
      buildSecond: () -> Component,
  ): LayoutState {
    ParityStateHolder.state = null
    ParityStateHolder.heightState = null
    ParityStateHolder.innerState = null
    ComponentsConfiguration.enableSubtreeCaching = cachingEnabled

    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(buildFirst(), widthSpec, heightSpec)
    ParityStateHolder.state?.update { it + 1 }
    ParityStateHolder.heightState?.update { it + 1 }
    ParityStateHolder.innerState?.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(buildSecond(), widthSpec, heightSpec)
    return captureLayoutState(componentTree)
  }

  /**
   * Multi-pass parity: drives N state updates and verifies every pass produces identical outputs
   * OFF vs ON. Catches drift bugs (e.g., SubtreeRange index drift accumulating stale positions)
   * that two-pass tests would miss.
   *
   * Asserts that splice fired AT LEAST [passes] times across the run — i.e. each pass should have
   * contributed at least one cache hit. A weaker `> 0` guard would let a "splice fires once then
   * stops" regression pass silently across 50 iterations.
   */
  private fun assertParityMultiPass(passes: Int, build: () -> Component) {
    val offStates = runMultiPassUpdateCycle(cachingEnabled = false, passes, build)
    LithoStats.resetAllCounters()
    val onStates = runMultiPassUpdateCycle(cachingEnabled = true, passes, build)

    val totalHits = LithoStats.subtreeCacheHitCount.get()
    assertThat(totalHits)
        .describedAs(
            "expected ≥ $passes splice hits (≥ 1 per pass); got $totalHits — splice may " +
                "have fired only on early passes and stopped, masking later-pass drift.",
        )
        .isGreaterThanOrEqualTo(passes.toLong())

    for (passIndex in 0 until passes) {
      try {
        compareLayoutStates(offStates[passIndex], onStates[passIndex])
      } catch (assertionError: AssertionError) {
        throw AssertionError(
            "Parity divergence at pass ${passIndex + 1} of $passes: ${assertionError.message}",
            assertionError,
        )
      }
    }
  }

  private fun runMultiPassUpdateCycle(
      cachingEnabled: Boolean,
      passes: Int,
      build: () -> Component,
  ): List<LayoutState> {
    ParityStateHolder.state = null
    ParityStateHolder.heightState = null
    ParityStateHolder.innerState = null
    ComponentsConfiguration.enableSubtreeCaching = cachingEnabled

    val componentTree = createComponentTree()
    componentTree.setRootAndSizeSpecSync(build(), widthSpec, heightSpec)
    val capturedStates = ArrayList<LayoutState>(passes)
    repeat(passes) {
      ParityStateHolder.state?.update { it + 1 }
      ParityStateHolder.heightState?.update { it + 1 }
      ParityStateHolder.innerState?.update { it + 1 }
      componentTree.setRootAndSizeSpecSync(build(), widthSpec, heightSpec)
      capturedStates.add(captureLayoutState(componentTree))
    }
    return capturedStates
  }

  /**
   * Width-change variant of [assertParity]: the second pass uses a DIFFERENT widthSpec than the
   * first. No state update is needed — the size constraint change alone forces re-layout. The
   * cached subtree's intrinsic measurement is preserved (Yoga marks `isCachedLayout=true`), but its
   * allocated width changes; per-direct-child positions / sizes may shift accordingly. The splice's
   * per-child offset path must produce the same outputs as cache-OFF baseline.
   */
  private fun assertParityOnWidthChange(
      initialWidthPx: Int = 1080,
      finalWidthPx: Int = 1500,
      requireSplice: Boolean = true,
      build: () -> Component,
  ) {
    val offState = runWidthChangeCycle(
        cachingEnabled = false,
        build = build,
        initialWidthPx = initialWidthPx,
        finalWidthPx = finalWidthPx,
    )
    LithoStats.resetAllCounters()
    val onState = runWidthChangeCycle(
        cachingEnabled = true,
        build = build,
        initialWidthPx = initialWidthPx,
        finalWidthPx = finalWidthPx,
    )

    if (requireSplice) {
      assertThat(LithoStats.subtreeCacheHitCount.get())
          .describedAs(
              "splice never engaged on width-change path — parity comparison would be vacuous",
          )
          .isGreaterThan(0)
    }
    compareLayoutStates(offState, onState)
  }

  private fun runWidthChangeCycle(
      cachingEnabled: Boolean,
      build: () -> Component,
      initialWidthPx: Int,
      finalWidthPx: Int,
  ): LayoutState {
    ParityStateHolder.state = null
    ParityStateHolder.heightState = null
    ComponentsConfiguration.enableSubtreeCaching = cachingEnabled

    val componentTree = createComponentTree()
    val initialWidthSpec =
        View.MeasureSpec.makeMeasureSpec(initialWidthPx, View.MeasureSpec.EXACTLY)
    val finalWidthSpec = View.MeasureSpec.makeMeasureSpec(finalWidthPx, View.MeasureSpec.EXACTLY)
    componentTree.setRootAndSizeSpecSync(build(), initialWidthSpec, heightSpec)
    // Drive a state update on the sibling sink so the resolver runs in RECONCILE mode (preserving
    // unchanged FeedRow LithoNode identities). Without a state update, calling setRoot with a
    // fresh tree forces full re-resolve, which discards the LithoNode identities the splice gate
    // keys on (`subtreeSnapshots[node]` is identity-keyed). Width-spec change alone doesn't
    // trigger reconcile; it only re-runs Yoga layout against the new constraints.
    ParityStateHolder.state?.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(build(), finalWidthSpec, heightSpec)
    return captureLayoutState(componentTree)
  }

  /**
   * Multi-pass width-change parity. Alternates between [initialWidthPx] and [finalWidthPx] across
   * [passes] iterations. The first pass establishes the baseline at [initialWidthPx]; each
   * subsequent pass toggles to the other width. Verifies cache survives repeated size oscillations
   * without drift.
   */
  private fun assertParityOnWidthChangeMultiPass(
      passes: Int,
      initialWidthPx: Int = 1080,
      finalWidthPx: Int = 1500,
      build: () -> Component,
  ) {
    val offStates = runMultiPassWidthChangeCycle(
        cachingEnabled = false,
        passes = passes,
        build = build,
        initialWidthPx = initialWidthPx,
        finalWidthPx = finalWidthPx,
    )
    LithoStats.resetAllCounters()
    val onStates = runMultiPassWidthChangeCycle(
        cachingEnabled = true,
        passes = passes,
        build = build,
        initialWidthPx = initialWidthPx,
        finalWidthPx = finalWidthPx,
    )

    val totalHits = LithoStats.subtreeCacheHitCount.get()
    assertThat(totalHits)
        .describedAs(
            "expected ≥ $passes splice hits across $passes width-toggle passes; got $totalHits",
        )
        .isGreaterThanOrEqualTo(passes.toLong())

    for (passIndex in 0 until passes) {
      try {
        compareLayoutStates(offStates[passIndex], onStates[passIndex])
      } catch (assertionError: AssertionError) {
        throw AssertionError(
            "Parity divergence at width-change pass ${passIndex + 1} of $passes: " +
                "${assertionError.message}",
            assertionError,
        )
      }
    }
  }

  private fun runMultiPassWidthChangeCycle(
      cachingEnabled: Boolean,
      passes: Int,
      build: () -> Component,
      initialWidthPx: Int,
      finalWidthPx: Int,
  ): List<LayoutState> {
    ParityStateHolder.state = null
    ParityStateHolder.heightState = null
    ComponentsConfiguration.enableSubtreeCaching = cachingEnabled

    val componentTree = createComponentTree()
    val widthSpecA = View.MeasureSpec.makeMeasureSpec(initialWidthPx, View.MeasureSpec.EXACTLY)
    val widthSpecB = View.MeasureSpec.makeMeasureSpec(finalWidthPx, View.MeasureSpec.EXACTLY)
    componentTree.setRootAndSizeSpecSync(build(), widthSpecA, heightSpec)
    val capturedStates = ArrayList<LayoutState>(passes)
    repeat(passes) { i ->
      // State update on the sibling sink — same rationale as `runWidthChangeCycle`: forces
      // RECONCILE so the cached LithoNodes survive into the next pass and splice can engage.
      ParityStateHolder.state?.update { it + 1 }
      val nextSpec = if (i % 2 == 0) widthSpecB else widthSpecA
      componentTree.setRootAndSizeSpecSync(build(), nextSpec, heightSpec)
      capturedStates.add(captureLayoutState(componentTree))
    }
    return capturedStates
  }

  /**
   * Splice-fires assertion variant for the width-change path. Drives one width-change cycle and
   * checks that the actual splice-hit count matches expectations.
   */
  private fun assertSpliceHitsOnWidthChange(
      buildRoot: () -> Component,
      initialWidthPx: Int = 1080,
      finalWidthPx: Int = 1500,
      expectedHits: Long? = null,
      minimumHits: Long? = null,
  ) {
    require((expectedHits == null) xor (minimumHits == null)) {
      "Specify exactly one of expectedHits or minimumHits"
    }
    ComponentsConfiguration.enableSubtreeCaching = true
    LithoStats.resetAllCounters()

    val componentTree = createComponentTree()
    val initialWidthSpec =
        View.MeasureSpec.makeMeasureSpec(initialWidthPx, View.MeasureSpec.EXACTLY)
    val finalWidthSpec = View.MeasureSpec.makeMeasureSpec(finalWidthPx, View.MeasureSpec.EXACTLY)
    componentTree.setRootAndSizeSpecSync(buildRoot(), initialWidthSpec, heightSpec)
    // See comment in `runWidthChangeCycle`: the state update is what triggers RECONCILE on the
    // second pass, preserving the FeedRow LithoNode identities that the splice gate keys on.
    ParityStateHolder.state?.update { it + 1 }
    componentTree.setRootAndSizeSpecSync(buildRoot(), finalWidthSpec, heightSpec)

    val actualHits = LithoStats.subtreeCacheHitCount.get()
    if (expectedHits != null) {
      assertThat(actualHits)
          .describedAs("subtreeCacheHitCount on width-change (expected exactly)")
          .isEqualTo(expectedHits)
    } else {
      assertThat(actualHits)
          .describedAs("subtreeCacheHitCount on width-change (expected at least)")
          .isGreaterThanOrEqualTo(checkNotNull(minimumHits))
    }
  }

  /**
   * Run [block] with the given Finalize* config flags applied. Flags are restored automatically by
   * [teardown]; this helper exists to keep the per-test boilerplate at the top of each flag-matrix
   * test compact.
   */
  private inline fun withFinalizeFlags(imo: Boolean = false, block: () -> Unit) {
    ComponentsConfiguration.useFinalizeForIMOBounds = imo
    block()
  }

  private fun createComponentTree(): ComponentTree {
    ParityStateHolder.state = null
    ParityStateHolder.heightState = null
    ParityStateHolder.innerState = null
    return ComponentTree.create(lithoTestRule.context).build()
  }

  private fun captureLayoutState(ct: ComponentTree): LayoutState {
    return checkNotNull(ct.getCommittedLayoutState()) { "no committed LayoutState" }
  }

  private fun assertIncrementalMountMetadata(
      layoutState: LayoutState,
      requireFastPath: Boolean,
  ) {
    val rootHostId = layoutState.mountableOutputs.first().renderUnit.id
    var outputsChecked = 0
    var fastPathOutputsChecked = 0
    var nonRootParentOutputsChecked = 0
    layoutState.mountableOutputs.forEach { renderTreeNode ->
      val output =
          layoutState.getIncrementalMountOutputForId(renderTreeNode.renderUnit.id) ?: return@forEach
      if (requireFastPath && !renderTreeNode.isFastPathEligible) {
        return@forEach
      }

      assertThat(output.contentType)
          .describedAs("IMO id=${output.id} contentType")
          .isSameAs(renderTreeNode.renderUnit.contentAllocator.getPoolKey())
      assertThat(output.hostId)
          .describedAs("IMO id=${output.id} hostId")
          .isEqualTo(
              renderTreeNode.parent?.renderUnit?.id ?: IncrementalMountOutput.NO_HOST_ID,
          )
      outputsChecked++
      if (renderTreeNode.isFastPathEligible) {
        fastPathOutputsChecked++
      }
      val parentId = renderTreeNode.parent?.renderUnit?.id
      if (parentId != null && parentId != rootHostId) {
        nonRootParentOutputsChecked++
      }
    }

    assertThat(outputsChecked)
        .describedAs("no incremental-mount outputs were checked")
        .isGreaterThan(0)
    assertThat(nonRootParentOutputsChecked)
        .describedAs("no output with a non-root immediate parent was checked")
        .isGreaterThan(0)
    if (requireFastPath) {
      assertThat(fastPathOutputsChecked)
          .describedAs("no fast-path outputs were checked")
          .isGreaterThan(0)
    }
  }

  /**
   * Compare two [LayoutState] instances field-by-field. Used as the parity contract: any divergence
   * between OFF (no caching) and ON (cached splice) indicates a correctness bug in the splice path.
   * Inline comments anchor each assertion block to the [LithoReducer] line range that produces the
   * corresponding output — useful for triaging failures.
   *
   * Compared categories (in declaration order):
   * 1. `mountableOutputs` — count, then per-index: bounds, absolute coords, RenderUnit class,
   *    output type, position-in-parent, parent index, excludeFromIM flag.
   * 2. `visibilityOutputs` — count + per-index full field-level compare (bounds, key,
   *    componentClassName, tag, ratios, hasMountableContent, all 6 callback presences).
   * 3. `incrementalMountOutputs` (per ID) — IMO presence + bounds + excludeFromIM flag.
   * 4. `animatableItems` (per ID) — presence + absolute/local bounds + outputType + transitionId
   *    structural compare (skipping AUTOGENERATED ids — see TransitionsExtension audit notes).
   * 5. `testOutputs` — count + per-index testKey + bounds.
   * 6. `transitionIdMapping` — size + key set, EXCLUDING AUTOGENERATED ids (framework-internal and
   *    intentionally dropped by splice — see audit notes for safety analysis).
   * 7. `attachables` / `componentScopes` / `componentHandles` — count parity.
   * 8. `mountableOutputTops` / `mountableOutputBottoms` — full-list ordering equality.
   * 9. `width` / `height` / `hasComponentsExcludedFromIncrementalMount` flags.
   * 10. `diffTree` — recursive structural compare via [compareDiffNodes].
   */
  private fun compareLayoutStates(off: LayoutState, on: LayoutState) {
    // mountableOutputs count
    assertThat(on.mountableOutputs.size)
        .describedAs("mountableOutputs count")
        .isEqualTo(off.mountableOutputs.size)

    // Per-output comparison (structural, not ID-based — IDs differ across ComponentTrees)
    for (i in 0 until off.mountableOutputs.size) {
      val offRtn = off.mountableOutputs[i]
      val onRtn = on.mountableOutputs[i]

      assertThat(onRtn.bounds).describedAs("output[$i] bounds").isEqualTo(offRtn.bounds)
      assertThat(onRtn.absoluteX).describedAs("output[$i] absoluteX").isEqualTo(offRtn.absoluteX)
      assertThat(onRtn.absoluteY).describedAs("output[$i] absoluteY").isEqualTo(offRtn.absoluteY)

      val offRu = offRtn.renderUnit as LithoRenderUnit
      val onRu = onRtn.renderUnit as LithoRenderUnit

      // Component class equality (stricter than simpleName — two different components
      // with the same simpleName would pass simpleName check but fail this).
      assertThat(onRu.component.javaClass)
          .describedAs("output[$i] component class")
          .isEqualTo(offRu.component.javaClass)

      // Output type bits in the ID
      assertThat(RenderUnitIdGenerator.decodeType(onRtn.renderUnit.id))
          .describedAs("output[$i] outputType")
          .isEqualTo(RenderUnitIdGenerator.decodeType(offRtn.renderUnit.id))

      // Position-in-parent (verifies tree structure preserved across splice)
      assertThat(onRtn.positionInParent)
          .describedAs("output[$i] positionInParent")
          .isEqualTo(offRtn.positionInParent)

      // Parent index — verifies parent chain by structural index
      val offParentIdx = off.mountableOutputs.indexOfFirst { it === offRtn.parent }
      val onParentIdx = on.mountableOutputs.indexOfFirst { it === onRtn.parent }
      assertThat(onParentIdx).describedAs("output[$i] parent index").isEqualTo(offParentIdx)

      // excludeFromIM flag must match
      assertThat(onRu.excludesFromIncrementalMount)
          .describedAs("output[$i] excludesFromIncrementalMount")
          .isEqualTo(offRu.excludesFromIncrementalMount)
    }

    // Visibility outputs count
    assertThat(on.visibilityOutputCount)
        .describedAs("visibilityOutputs count")
        .isEqualTo(off.visibilityOutputCount)

    // Visibility output FULL field comparison (per index). Splice's offset path rebuilds
    // VisibilityOutput by manually copying 14 fields (LithoReducer.kt:1138-1160) — any
    // field omission silently breaks visibility events for cached subtrees that move.
    val offVisCount = off.visibilityOutputCount
    for (i in 0 until offVisCount) {
      val offVo = off.getVisibilityOutputAt(i)
      val onVo = on.getVisibilityOutputAt(i)
      assertThat(onVo.bounds).describedAs("visOutput[$i] bounds").isEqualTo(offVo.bounds)
      assertThat(onVo.key).describedAs("visOutput[$i] key").isEqualTo(offVo.key)
      assertThat(onVo.componentClassName)
          .describedAs("visOutput[$i] componentClassName")
          .isEqualTo(offVo.componentClassName)
      assertThat(onVo.tag).describedAs("visOutput[$i] tag").isEqualTo(offVo.tag)
      assertThat(onVo.visibleHeightRatio)
          .describedAs("visOutput[$i] visibleHeightRatio")
          .isEqualTo(offVo.visibleHeightRatio)
      assertThat(onVo.visibleWidthRatio)
          .describedAs("visOutput[$i] visibleWidthRatio")
          .isEqualTo(offVo.visibleWidthRatio)
      assertThat(onVo.hasMountableContent)
          .describedAs("visOutput[$i] hasMountableContent")
          .isEqualTo(offVo.hasMountableContent)
      // Per-handler presence (callback identity differs across trees, but a dropped handler
      // is the realistic bug — assert NULL-vs-NON-NULL parity for each of the 6 callbacks).
      assertThat(onVo.onVisible == null)
          .describedAs("visOutput[$i] onVisible presence")
          .isEqualTo(offVo.onVisible == null)
      assertThat(onVo.onInvisible == null)
          .describedAs("visOutput[$i] onInvisible presence")
          .isEqualTo(offVo.onInvisible == null)
      assertThat(onVo.onFocusedVisible == null)
          .describedAs("visOutput[$i] onFocusedVisible presence")
          .isEqualTo(offVo.onFocusedVisible == null)
      assertThat(onVo.onUnfocusedVisible == null)
          .describedAs("visOutput[$i] onUnfocusedVisible presence")
          .isEqualTo(offVo.onUnfocusedVisible == null)
      assertThat(onVo.onFullImpression == null)
          .describedAs("visOutput[$i] onFullImpression presence")
          .isEqualTo(offVo.onFullImpression == null)
      assertThat(onVo.onVisibilityChange == null)
          .describedAs("visOutput[$i] onVisibilityChange presence")
          .isEqualTo(offVo.onVisibilityChange == null)
    }

    // Incremental mount outputs count + bounds
    assertThat(on.getIncrementalMountOutputCount())
        .describedAs("incrementalMountOutputs count")
        .isEqualTo(off.getIncrementalMountOutputCount())

    for (i in 0 until off.mountableOutputs.size) {
      val offImo = off.getIncrementalMountOutputForId(off.mountableOutputs[i].renderUnit.id)
      val onImo = on.getIncrementalMountOutputForId(on.mountableOutputs[i].renderUnit.id)
      if (offImo != null) {
        assertThat(onImo).describedAs("output[$i] IMO presence").isNotNull
        assertThat(onImo!!.bounds).describedAs("output[$i] IMO bounds").isEqualTo(offImo.bounds)
        assertThat(onImo.excludeFromIncrementalMount())
            .describedAs("output[$i] IMO excludeFromIncrementalMount")
            .isEqualTo(offImo.excludeFromIncrementalMount())
      } else {
        assertThat(onImo).describedAs("output[$i] IMO presence (both null)").isNull()
      }
    }

    // AnimatableItem comparison — bounds (absolute + local), id, transitionId, outputType
    for (i in 0 until off.mountableOutputs.size) {
      val offAi = off.getAnimatableItem(off.mountableOutputs[i].renderUnit.id)
      val onAi = on.getAnimatableItem(on.mountableOutputs[i].renderUnit.id)
      if (offAi != null) {
        assertThat(onAi).describedAs("output[$i] AI presence").isNotNull
        assertThat(onAi!!.getAbsoluteBounds())
            .describedAs("output[$i] AI absoluteBounds")
            .isEqualTo(offAi.getAbsoluteBounds())
        assertThat(onAi.getBounds())
            .describedAs("output[$i] AI localBounds")
            .isEqualTo(offAi.getBounds())
        assertThat(onAi.getOutputType() and 0x7)
            .describedAs("output[$i] AI outputType")
            .isEqualTo(offAi.getOutputType() and 0x7)
        // transitionId structural equality for USER-declared transitions only.
        // AUTOGENERATED ids are framework-internal — splice intentionally drops them
        // for cached subtrees (offAi has one, onAi may not). For GLOBAL/SCOPED, a
        // grafted-stale TransitionId carrying a wrong reference would cause animations
        // to match the wrong item; we assert (type, reference) equality.
        val offTid = offAi.getTransitionId()
        val onTid = onAi.getTransitionId()
        if (offTid != null && offTid.type != TransitionId.Type.AUTOGENERATED) {
          assertThat(onTid?.type)
              .describedAs("output[$i] AI transitionId.type")
              .isEqualTo(offTid.type)
          assertThat(onTid?.reference)
              .describedAs("output[$i] AI transitionId.reference")
              .isEqualTo(offTid.reference)
        }
        // Animatable property values + their `is*Set` flags. Splice reuses prev's AI
        // by reference when positionStable, so values should match exactly. When position
        // shifts (offset path), splice rebuilds the AI via createAnimatableItem(...) which
        // copies these from the underlying RenderUnit. A divergence here means the
        // rebuilt AI lost the prev pass's animated property snapshot — visual stutter
        // when an animation interrupts a state-driven re-layout.
        assertThat(onAi.getScale()).describedAs("output[$i] AI scale").isEqualTo(offAi.getScale())
        assertThat(onAi.getAlpha()).describedAs("output[$i] AI alpha").isEqualTo(offAi.getAlpha())
        assertThat(onAi.getRotation())
            .describedAs("output[$i] AI rotation")
            .isEqualTo(offAi.getRotation())
        assertThat(onAi.getRotationX())
            .describedAs("output[$i] AI rotationX")
            .isEqualTo(offAi.getRotationX())
        assertThat(onAi.getRotationY())
            .describedAs("output[$i] AI rotationY")
            .isEqualTo(offAi.getRotationY())
        assertThat(onAi.isScaleSet())
            .describedAs("output[$i] AI isScaleSet")
            .isEqualTo(offAi.isScaleSet())
        assertThat(onAi.isAlphaSet())
            .describedAs("output[$i] AI isAlphaSet")
            .isEqualTo(offAi.isAlphaSet())
        assertThat(onAi.isRotationSet())
            .describedAs("output[$i] AI isRotationSet")
            .isEqualTo(offAi.isRotationSet())
        assertThat(onAi.isRotationXSet())
            .describedAs("output[$i] AI isRotationXSet")
            .isEqualTo(offAi.isRotationXSet())
        assertThat(onAi.isRotationYSet())
            .describedAs("output[$i] AI isRotationYSet")
            .isEqualTo(offAi.isRotationYSet())
      } else {
        assertThat(onAi).describedAs("output[$i] AI presence (both null)").isNull()
      }
    }

    // Test outputs — count + per-entry parity (testKey + bounds). Splice rebuilds these
    // at LithoReducer.kt:1177-1185; without this loop a per-entry drift would slip through.
    assertThat(on.testOutputCount).describedAs("testOutputs count").isEqualTo(off.testOutputCount)
    for (i in 0 until off.testOutputCount) {
      val offTo = off.getTestOutputAt(i)
      val onTo = on.getTestOutputAt(i)
      if (offTo == null) {
        assertThat(onTo).describedAs("testOutput[$i] (both null)").isNull()
      } else {
        assertThat(onTo).describedAs("testOutput[$i] presence").isNotNull
        assertThat(onTo!!.testKey).describedAs("testOutput[$i] testKey").isEqualTo(offTo.testKey)
        assertThat(onTo.bounds).describedAs("testOutput[$i] bounds").isEqualTo(offTo.bounds)
        // layoutOutputId — must be -1 (unanchored) on BOTH sides or non-(-1) on both. Exact
        // ids differ across ComponentTrees so we compare presence only; a "drops anchor on
        // splice" bug would surface as one being -1 and the other not.
        assertThat(onTo.layoutOutputId == -1L)
            .describedAs("testOutput[$i] layoutOutputId anchored")
            .isEqualTo(offTo.layoutOutputId == -1L)
      }
    }

    // transitionIdMapping — keys must match by (type, reference) for user-declared
    // transitions. AUTOGENERATED entries are framework-internal (every output gets
    // one); splice intentionally does NOT propagate them for cached subtrees because
    // they're not used unless a transition is active. We assert only the user-visible
    // GLOBAL/SCOPED entries — a missing user-declared transition silently breaks
    // entry/exit animations.
    val offTidMapping = off.transitionIdMapping
    val onTidMapping = on.transitionIdMapping
    val offUserTids =
        offTidMapping?.keys?.filter { it.type != TransitionId.Type.AUTOGENERATED } ?: emptyList()
    val onUserTids =
        onTidMapping?.keys?.filter { it.type != TransitionId.Type.AUTOGENERATED } ?: emptyList()
    assertThat(onUserTids.size)
        .describedAs("transitionIdMapping size (user-declared, excl. AUTOGENERATED)")
        .isEqualTo(offUserTids.size)
    val offKeys = offUserTids.map { it.type to it.reference }.toSet()
    val onKeys = onUserTids.map { it.type to it.reference }.toSet()
    assertThat(onKeys)
        .describedAs("transitionIdMapping (type,reference) key set, excl. AUTOGENERATED")
        .isEqualTo(offKeys)

    // attachables count (Attachable instances differ by identity, but a dropped/duped
    // attachable from the splice path would change the count). Splice copies these at
    // LithoReducer.kt:1098-1106.
    assertThat(on.attachables?.size)
        .describedAs("attachables count")
        .isEqualTo(off.attachables?.size)

    // componentScopes count — splice copies cached SpecGeneratedComponent scopes from
    // the prev LayoutState by reference (LithoReducer.kt:1089-1095). Counts must match
    // OFF baseline; a divergence indicates the slice math (which cached positions get
    // copied vs which root contributions get re-added in the current pass) is wrong.
    assertThat(on.componentScopesInternal.size)
        .describedAs("componentScopes count")
        .isEqualTo(off.componentScopesInternal.size)

    // componentHandles count — used by Litho's Handle-based event dispatch.
    assertThat(on.componentHandles?.size)
        .describedAs("componentHandles count")
        .isEqualTo(off.componentHandles?.size)

    // IMO top/bottom order — incremental mount walks these sorted lists. A drift in
    // sort order (LithoReducer.kt:1021-1022) silently breaks scroll-driven mount/unmount.
    val offTops = off.getOutputsOrderedByTopBounds().map { it.bounds.top }
    val onTops = on.getOutputsOrderedByTopBounds().map { it.bounds.top }
    assertThat(onTops).describedAs("mountableOutputTops order (by top)").isEqualTo(offTops)
    val offBottoms = off.getOutputsOrderedByBottomBounds().map { it.bounds.bottom }
    val onBottoms = on.getOutputsOrderedByBottomBounds().map { it.bounds.bottom }
    assertThat(onBottoms)
        .describedAs("mountableOutputBottoms order (by bottom)")
        .isEqualTo(offBottoms)

    // Width/height
    assertThat(on.width).describedAs("width").isEqualTo(off.width)
    assertThat(on.height).describedAs("height").isEqualTo(off.height)

    // hasComponentsExcludedFromIncrementalMount flag
    assertThat(on.hasComponentsExcludedFromIncrementalMount)
        .describedAs("hasComponentsExcludedFromIncrementalMount")
        .isEqualTo(off.hasComponentsExcludedFromIncrementalMount)

    // renderUnitIdsWhichHostRenderTrees — populated for components that host nested
    // render trees (e.g. Lazy collections, HSCROLL). A missing id means MountState skips
    // mounting child render trees inside cached subtrees → blank rows in nested feeds.
    // Compare by SIZE (ids differ across ComponentTrees) — drift here would surface as a
    // count mismatch since splice copies prev's contributions verbatim.
    assertThat(on.renderUnitIdsWhichHostRenderTrees?.size ?: 0)
        .describedAs("renderUnitIdsWhichHostRenderTrees count")
        .isEqualTo(off.renderUnitIdsWhichHostRenderTrees?.size ?: 0)

    // dynamicValueOutputs — populated for any RenderUnit with `commonDynamicProps` (e.g.
    // animated alpha, scale, translation via DynamicValue). Missing entries silently
    // disable dynamic-value updates on cached subtrees. Compare by SIZE since the keys
    // are render-unit IDs that differ across ComponentTrees.
    assertThat(on.dynamicValueOutputs?.size ?: 0)
        .describedAs("dynamicValueOutputs count")
        .isEqualTo(off.dynamicValueOutputs?.size ?: 0)

    // `outputParentIndices` is intentionally NOT compared: it's splice-write infrastructure,
    // populated only when caching is ON (for next-pass splice's `parentIsExternal` lookup).
    // OFF baseline doesn't track it. Multi-pass parity tests indirectly validate it: if the
    // structure were corrupted, pass-N+1 splice would mis-attribute parents and bounds would
    // drift visibly.

    // stateReads — splice replays prev's pre-filtered subtree map. A dropped key means
    // the next state update doesn't invalidate this subtree → stale UI. Compare the
    // number of (state, owner-key) pairs as a coarse check; `assertParityMultiPass`
    // catches downstream invalidation bugs by varying state across N passes.
    val offStateReadsPairs = off.stateReads?.values?.sumOf { it.size } ?: 0
    val onStateReadsPairs = on.stateReads?.values?.sumOf { it.size } ?: 0
    assertThat(onStateReadsPairs)
        .describedAs("stateReads (state,owner-key) pair count")
        .isEqualTo(offStateReadsPairs)

    // DiffNode tree — recursive structural comparison
    compareDiffNodes(off.diffTree, on.diffTree, "root")
  }

  /** Recursive DiffNode comparison. Catches grafting bugs that depth-only checks miss. */
  private fun compareDiffNodes(off: DiffNode?, on: DiffNode?, path: String) {
    if (off == null) {
      assertThat(on).describedAs("DiffNode at $path (both null)").isNull()
      return
    }
    assertThat(on).describedAs("DiffNode at $path (off non-null but on null)").isNotNull
    on!!

    assertThat(on.childCount).describedAs("DiffNode at $path: childCount").isEqualTo(off.childCount)

    // Component identity — different ComponentTree instances, so compare class
    assertThat(on.component.javaClass)
        .describedAs("DiffNode at $path: component class")
        .isEqualTo(off.component.javaClass)

    // Measurement fields on grafted descendant DiffNodes may legitimately differ from
    // baseline when the component's content changes across passes (e.g. HeightShiftSink
    // text changes with state). These fields feed next-pass shouldUpdate, not current mount.
    // Structure (childCount, component class) is what matters for mount parity.

    // Output presence parity (content/host/background/foreground/border)
    assertThat(on.contentOutput == null)
        .describedAs("DiffNode at $path: contentOutput presence")
        .isEqualTo(off.contentOutput == null)
    assertThat(on.hostOutput == null)
        .describedAs("DiffNode at $path: hostOutput presence")
        .isEqualTo(off.hostOutput == null)
    if (off.hostOutput != null && on.hostOutput != null) {
      // hostOutput's component class must match — the splice grafts the host's
      // LithoRenderUnit by reference; a stale or wrong-class graft would break
      // host view recycling.
      assertThat(on.hostOutput!!.component.javaClass)
          .describedAs("DiffNode at $path: hostOutput component class")
          .isEqualTo(off.hostOutput!!.component.javaClass)
    }
    assertThat(on.borderOutput == null)
        .describedAs("DiffNode at $path: borderOutput presence")
        .isEqualTo(off.borderOutput == null)
    assertThat(on.visibilityOutput == null)
        .describedAs("DiffNode at $path: visibilityOutput presence")
        .isEqualTo(off.visibilityOutput == null)

    // Recurse into children
    for (i in 0 until off.childCount) {
      compareDiffNodes(off.getChildAt(i), on.getChildAt(i), "$path/$i")
    }
  }
}

// ── State holder ──

/**
 * Sink-captured state references, exposed so the test driver can call `state.update {...}` after
 * layout to trigger a re-render. NOT thread-safe — every test runs serially under Robolectric
 * `@LooperMode.LEGACY` (single-threaded). A future migration to JUnit 5 parallel test execution
 * would require switching to per-test holders or a `ThreadLocal`.
 */
internal object ParityStateHolder {
  @Volatile var state: State<Int>? = null
  @Volatile var heightState: State<Int>? = null
  // Drives a state read INSIDE a deferred lambda (e.g. RenderWithConstraints) — distinct from
  // [state] which is read by an outer sink. Lets tests dirty either the outer scope, the deferred
  // descendant scope, or both.
  @Volatile var innerState: State<Int>? = null
}

// ── Sinks ──

private class ParityStatefulSink : KComponent() {
  override fun ComponentScope.render(): Component {
    val s = useState { 0 }
    ParityStateHolder.state = s
    return Text(text = "v=${s.value}", textSize = 1.sp)
  }
}

/**
 * Sibling sink that drives a position-shifting layout change via state. Returns a Row with a
 * varying height (10px when tick is even, 30px when odd). Because the height value lives in this
 * sink's local state, only this sink's scope is dirty per update — sibling FeedRow components keep
 * their LithoNode identity → cache hits → splice fires in OFFSET mode (positions of siblings shift
 * by dy).
 */
private class ParityHeightShiftSink : KComponent() {
  override fun ComponentScope.render(): Component {
    val h = useState { 10 }
    ParityStateHolder.heightState = h
    val height = if (h.value % 2 == 0) 10 else 30
    return Row(style = Style.height(height.px))
  }
}

// ── Cacheable fixtures ──

private class ParityStatefulRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRow(i))
        }
      }
}

/**
 * SHARED FIXTURE — used by `ParityStatefulRoot`, `ParityStatefulRealisticFeed`,
 * `ParityStatefulHeightShift`, and `ParityStatefulMixedHosted`. Mutating this class will affect 4
 * test scenarios; if you need a variant, create a new class instead of editing this one.
 */
private class ParityFeedRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        child(
            Column {
              child(Text(text = "Title $i", textSize = 16.sp))
              child(Text(text = "Subtitle $i", textSize = 12.sp))
              child(
                  Row {
                    child(Text(text = "Like", textSize = 11.sp))
                    child(Text(text = "Comment", textSize = 11.sp))
                    child(Text(text = "Share", textSize = 11.sp))
                  },
              )
            },
        )
      }
}

private class ParityStatefulDashboard : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until 12) {
          child(ParityDashboardCard(i))
        }
      }
}

private class ParityDashboardCard(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(140.px)) {
        child(
            Row {
              child(Text(text = "Section $i", textSize = 16.sp))
              child(Text(text = "${(i + 1) * 127}K", textSize = 14.sp))
            },
        )
        child(Text(text = "Description for card $i", textSize = 12.sp))
        child(
            Row {
              child(Text(text = "+${i * 3 + 5}%", textSize = 13.sp))
              child(Text(text = "vs last week", textSize = 11.sp))
            },
        )
      }
}

private class ParityStatefulDeep(private val width: Int, private val depth: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until width) {
          child(ParityNestedColumn(remaining = depth, idx = i))
        }
      }
}

/**
 * Each nesting level emits a [Text] sibling so the subtree contains ≥ MIN_SUBTREE_SCOPES Spec
 * scopes (KComponents themselves don't increment `componentScopes` — only Spec components do, see
 * `LithoReducer.kt:518-519`). Without per-level Text leaves, a deep KComponent chain contributes
 * just 1 leaf scope and the splice gate rejects the subtree, making "deep" tests vacuous.
 */
private class ParityNestedColumn(private val remaining: Int, private val idx: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      if (remaining == 0) {
        Text(text = "Leaf $idx", textSize = 11.sp)
      } else {
        Column {
          child(Text(text = "Level $remaining/$idx", textSize = 11.sp))
          child(ParityNestedColumn(remaining = remaining - 1, idx = idx))
        }
      }
}

private class ParityStatefulSingleHeavy(private val depth: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        child(ParityNestedColumn(remaining = depth, idx = 0))
      }
}

private class ParityStatefulRealisticFeed(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(
              when (i % 3) {
                0 -> ParityPhotoRow(i)
                1 -> ParityTextPost(i)
                else -> ParityFeedRow(i)
              },
          )
        }
      }
}

private class ParityPhotoRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(300.px)) {
        child(
            Row(style = Style.height(200.px)) {
              child(Text(text = "[Photo $i]", textSize = 14.sp))
            },
        )
        child(Text(text = "Caption $i", textSize = 14.sp))
        child(
            Row {
              child(Text(text = "Like", textSize = 12.sp))
              child(Text(text = "Comment", textSize = 12.sp))
            },
        )
      }
}

private class ParityTextPost(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(120.px)) {
        child(Text(text = "User $i posted:", textSize = 13.sp))
        child(Text(text = "Text post content #$i with details.", textSize = 15.sp))
        child(
            Row {
              child(Text(text = "Like", textSize = 12.sp))
              child(Text(text = "React", textSize = 12.sp))
            },
        )
      }
}

/** Position-offset fixture using sibling sink (NOT root prop) to drive height shift. */
private class ParityStatefulHeightShift(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        child(ParityHeightShiftSink())
        for (i in 0 until rows) {
          child(ParityFeedRow(i))
        }
      }
}

private class ParityStatefulWideShallow(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(Text(text = "Row $i", textSize = 14.sp))
        }
      }
}

// ── Hosted-subtree fixtures (force needsHostView=true via onClick) ── //

private class ParityHostedFeedRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px).onClick { /* hosted */ }) {
        child(Text(text = "Hosted Row $i", textSize = 14.sp))
        child(Text(text = "Subtitle $i", textSize = 12.sp))
      }
}

private class ParityStatefulHostedRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityHostedFeedRow(i))
        }
      }
}

/** Mix of hosted (onClick) and non-hosted FeedRow children — exercises both paths. */
private class ParityStatefulMixedHosted(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          if (i % 2 == 0) child(ParityHostedFeedRow(i)) else child(ParityFeedRow(i))
        }
      }
}

// ── Visibility fixture ── //

private class ParityVisibilityRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px).onVisible { /* visibility callback */ }) {
        child(Text(text = "Visible row $i", textSize = 14.sp))
        child(Text(text = "Subtitle $i", textSize = 12.sp))
      }
}

private class ParityStatefulVisibilityRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityVisibilityRow(i))
        }
      }
}

// ── Transition fixture ── //

private class ParityTransitionRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component = Row(
      style =
          Style.height(80.px).transitionKey(context, "row_$i", Transition.TransitionKeyType.GLOBAL),
  ) {
    child(Text(text = "Animated row $i", textSize = 14.sp))
  }
}

private class ParityStatefulTransitionRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityTransitionRow(i))
        }
      }
}

// ── Visibility under offset path: combine HeightShiftSink + onVisible rows ── //
// Covers the offset VisibilityOutput rebuild branch (LithoReducer.kt:1134-1163) which
// reconstructs the VO with a new bounds Rect when dy != 0.

private class ParityStatefulHeightShiftVisibilityRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        child(ParityHeightShiftSink())
        for (i in 0 until rows) {
          child(ParityVisibilityRow(i))
        }
      }
}

// ── Resolve-phase useEffect fixture: parity for attachables under cache ON vs OFF ── //
// Each FeedRow installs a resolve-phase useEffect (ComponentScope.useEffect). These attachables
// are pre-populated into ReductionState before reduction starts (via LayoutTreeFuture) and are
// unaffected by the splice path — but parity still must hold.

private class ParityFeedRowWithEffect(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component {
    useEffect(i) { onCleanup { /* no-op */ } }
    return Row(style = Style.height(80.px)) {
      child(
          Column {
            child(Text(text = "Effect Title $i", textSize = 16.sp))
            child(Text(text = "Effect Subtitle $i", textSize = 12.sp))
          },
      )
    }
  }
}

private class ParityStatefulEffectRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRowWithEffect(i))
        }
      }
}

// ── Layout-phase useEffect fixture: covers attachable range replay in spliceFromSnapshot ── //
// Each row wraps a PrimitiveWithLayoutEffect whose LayoutBehavior.layout() calls
// LayoutScope.useEffect(). This produces a layout-phase Attachable on
// LithoLayoutResult.effects — distinct from resolve-phase useEffect (ComponentScope.useEffect)
// which is pre-populated before reduction. The splice path must replay these via
// SubtreeSnapshot.attachableStart..attachableEnd.

private class ParityFeedRowWithLayoutPhaseEffect(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        child(
            Column {
              child(Text(text = "LayoutEffect Title $i", textSize = 16.sp))
              child(Text(text = "LayoutEffect Subtitle $i", textSize = 12.sp))
              child(
                  PrimitiveWithLayoutEffect(
                      id = i,
                      style = Style.width(60.px).height(40.px),
                  ),
              )
            },
        )
      }
}

private class ParityStatefulLayoutPhaseEffectRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRowWithLayoutPhaseEffect(i))
        }
      }
}

// ── Nested useState: covers stateReads replay path (LithoReducer.kt:1119-1123) ── //
// useState lives INSIDE the cacheable subtree (FeedRow), not at the root sink. With state-read
// tracking enabled, splice replays `subtreeStateReads` for the cached subtree on each pass.
// Without this fixture, the replay loop body never runs with a non-empty input.

private class ParityFeedRowWithLocalState(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component {
    val tick = useState { 0 }
    return Row(style = Style.height(80.px)) {
      child(
          Column {
            child(Text(text = "Row $i", textSize = 16.sp))
            child(Text(text = "Tick=${tick.value}", textSize = 12.sp))
          },
      )
    }
  }
}

private class ParityStatefulLocalStateRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRowWithLocalState(i))
        }
      }
}

// ── excludeFromIncrementalMount=true fixture (covers LithoReducer.kt:1017-1019) ── //
// Uses MountSpecExcludeFromIncrementalMount (a PrimitiveComponent that sets
// `shouldExcludeFromIncrementalMount = true`), wrapped inside a cacheable feed row.

private class ParityExcludeFromIMRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(120.px)) {
        child(Text(text = "Excluded row $i", textSize = 14.sp))
        child(
            MountSpecExcludeFromIncrementalMount(
                lifecycleTracker = LifecycleTracker(),
                style = Style.width(60.px).height(60.px),
            ),
        )
      }
}

private class ParityStatefulExcludeFromIMRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityExcludeFromIMRow(i))
        }
      }
}

// ── testKey fixture: populates TestOutput list (only when isEndToEndTestRun=true) ── //

private class ParityTestKeyRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px).testKey("row_test_key_$i")) {
        child(Text(text = "TestKeyed row $i", textSize = 14.sp))
      }
}

private class ParityStatefulTestKeyRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityTestKeyRow(i))
        }
      }
}

// ── Decorated rows: covers background + foreground + border outputs in cached subtrees ── //
// Decorations live on a Column INSIDE the cacheable Row (not on the Row itself, which would
// force needsHostView=true via background/border). The Row stays cacheable; the Column emits
// background, border, foreground outputs that the splice path must propagate.

private class ParityDecoratedFeedRow(private val rowIndex: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        child(
            Column(
                style =
                    Style.backgroundColor(0xFFFF0000.toInt())
                        .foregroundColor(0x4000FF00)
                        .border(width = 1.px, color = 0xFF0000FF.toInt()),
            ) {
              child(Text(text = "Decorated row $rowIndex", textSize = 16.sp))
              child(Text(text = "Subtitle $rowIndex", textSize = 12.sp))
              child(Text(text = "Footer $rowIndex", textSize = 11.sp))
            },
        )
      }
}

private class ParityStatefulDecoratedRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (rowIndex in 0 until rows) {
          child(ParityDecoratedFeedRow(rowIndex))
        }
      }
}

// ── Structurally-varying sink: shifts cached siblings' absolute mountable indices per pass ── //
// The sink emits 1 child output on even ticks, 3 on odd. Cached FeedRows sit AFTER the sink,
// so their absolute positions in `mountableOutputs` shift by ±2 between passes. This stresses
// the descendant-only SubtreeRange semantics — splice must offset to the new positions, and
// the next-pass cache write must record positions relative to the current pass's layout.

private class ParityStructurallyVaryingSink : KComponent() {
  override fun ComponentScope.render(): Component {
    val tick = useState { 0 }
    ParityStateHolder.state = tick
    return if (tick.value % 2 == 0) {
      Row { child(Text(text = "compact tick=${tick.value}", textSize = 1.sp)) }
    } else {
      Row {
        child(Text(text = "expanded tick=${tick.value}", textSize = 1.sp))
        child(Text(text = "extra1", textSize = 1.sp))
        child(Text(text = "extra2", textSize = 1.sp))
      }
    }
  }
}

private class ParityStatefulStructurallyVaryingRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStructurallyVaryingSink())
        for (rowIndex in 0 until rows) {
          child(ParityFeedRow(rowIndex))
        }
      }
}

// ── Deep-nested excludeFromIncrementalMount: walks the bubble-up chain past depth 3 ── //
// Wraps `MountSpecExcludeFromIncrementalMount` inside several nested KComponent Columns. The
// splice path must walk up the new ancestor RTN chain and propagate the excludeFromIM flag
// through ALL ancestors, not just the immediate parent. A walk-termination bug at depth ≥ 3
// would surface here.

private class ParityNestedExcludeFromIM(
    private val remainingDepth: Int,
    private val rowIndex: Int,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      if (remainingDepth == 0) {
        // Leaf: a Spec component with shouldExcludeFromIncrementalMount=true.
        MountSpecExcludeFromIncrementalMount(
            lifecycleTracker = LifecycleTracker(),
            style = Style.width(60.px).height(60.px),
        )
      } else {
        // Wrap one more level + emit a Spec sibling so the subtree clears MIN_SUBTREE_SCOPES.
        Column {
          child(Text(text = "Lvl $remainingDepth row $rowIndex", textSize = 11.sp))
          child(ParityNestedExcludeFromIM(remainingDepth - 1, rowIndex))
        }
      }
}

private class ParityDeepExcludeFromIMRow(private val rowIndex: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(160.px)) {
        child(Text(text = "Deep excluded row $rowIndex", textSize = 14.sp))
        child(ParityNestedExcludeFromIM(remainingDepth = 4, rowIndex = rowIndex))
      }
}

private class ParityStatefulDeepExcludeFromIMRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (rowIndex in 0 until rows) {
          child(ParityDeepExcludeFromIMRow(rowIndex))
        }
      }
}

// ── Kitchen-sink row: exercises every SubtreeSnapshot field in one cached subtree ── //
// Combines per-field exercises that scattered fixtures cover individually:
//   - `Style.onVisible` → `visibilityOutputStart..visibilityOutputEnd`
//   - `Style.testKey(...)` → `testOutputStart..testOutputEnd`
//   - 4+ Spec children → clears MIN_SUBTREE_SCOPES + populates `componentScopeStart..End`
//     and `mountableOutputStart..mountableOutputEnd`
// When wrapped in `ParityStatefulHeightShiftKitchenSinkRows`, the height-shift sink also
// drives non-zero (rootX, rootY) shift → exercises the `dy` offset path.
//
// A bug affecting the INTERACTION between fields (e.g. "splice copies visibility outputs
// but corrupts test outputs when both are present in the same cached subtree") would surface
// here and would NOT be caught by any of the single-concern fixtures above.

/** Inner KComponent that contributes Spec scopes / outputs as DESCENDANTS of the row. */
private class ParityKitchenSinkInnerChild(private val rowIndex: Int) : KComponent() {
  override fun ComponentScope.render(): Component {
    val innerTick = useState { 0 }
    return Text(text = "Body $rowIndex tick=${innerTick.value}", textSize = 12.sp)
  }
}

private class ParityKitchenSinkRow(private val rowIndex: Int) : KComponent() {
  override fun ComponentScope.render(): Component {
    // Field-population strategy: each contribution must come from a DESCENDANT to land in
    // the row's snapshot.
    //   - `Style.onVisible` on a child Text → `visibilityOutputStart..End`.
    //   - `Style.testKey` on a child Text → `testOutputStart..End`. testKey wraps the
    //     child in a view; the row stays cacheable since its own hostRenderUnit is null.
    //   - The 4 Text children + the inner KComponent provide enough Spec scopes to clear
    //     `MIN_SUBTREE_SCOPES` and populate `componentScopeStart..End` /
    //     `mountableOutputStart..End` substantively.
    return Row(style = Style.height(80.px)) {
      child(
          Column {
            child(Text(text = "Title $rowIndex", textSize = 16.sp))
            child(Text(text = "Subtitle $rowIndex", textSize = 12.sp))
            child(ParityKitchenSinkInnerChild(rowIndex))
            child(
                Text(
                    text = "Visible $rowIndex",
                    textSize = 12.sp,
                    style = Style.onVisible { /* visibility callback */ },
                ),
            )
            child(
                Text(
                    text = "Footer $rowIndex",
                    textSize = 11.sp,
                    style = Style.testKey("kitchen_$rowIndex"),
                ),
            )
          },
      )
    }
  }
}

private class ParityStatefulKitchenSinkRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (rowIndex in 0 until rows) {
          child(ParityKitchenSinkRow(rowIndex))
        }
      }
}

private class ParityStatefulHeightShiftKitchenSinkRows(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        child(ParityHeightShiftSink())
        for (rowIndex in 0 until rows) {
          child(ParityKitchenSinkRow(rowIndex))
        }
      }
}

// ── Width-change fixtures: parent's allocated width drives container resize ── //
//
// These fixtures intentionally OMIT a fixed `Style.width(...)` on the root Column. The Column
// inherits its width from the test's widthSpec via Yoga's default `align-items=stretch` (the
// Column's parent — the implicit LithoView root — stretches its sole child to its own width).
// When the test calls setRootAndSizeSpecSync with a different widthSpec, the Column re-allocates
// to the new width, which propagates down to each FeedRow (also stretched). The FeedRow's
// children layout based on the new Row width — `justify-end` shifts the right child, `flex-grow`
// children resize.

/**
 * Each row is a justify-end Row with a fixed-width left text + fixed-width right icon. When the
 * Row's allocated width grows, the right icon shifts right (its left edge = rowWidth - 50px). Sizes
 * of both children are intrinsic and unchanged across passes — only positions shift. This is the
 * Tier 1 happy path: per-direct-child offset splice should engage and produce outputs identical to
 * baseline.
 */
private class ParityStatefulJustifyEndRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(1920.px)) {
        // Sibling sink so the resolver runs in RECONCILE mode (preserves FeedRow identities)
        // when the second pass triggers a state update. Without this, a second `setRoot` call
        // does a full re-resolve and splice cannot engage (LithoNode identity changes break
        // the snapshot's identity-keyed lookup).
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityJustifyEndRow(i))
        }
      }
}

private class ParityJustifyEndRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(justifyContent = YogaJustify.SPACE_BETWEEN, style = Style.height(80.px)) {
        // Left side: fixed-size text (intrinsic width unchanged across passes)
        child(Text(text = "Item $i", textSize = 14.sp))
        // Right side: end-aligned container (its position shifts when row width changes)
        child(
            Row(style = Style.width(50.px).height(20.px)) {
              child(Text(text = "★ $i", textSize = 11.sp))
            },
        )
      }
}

/**
 * Variant that uses justify=SPACE_BETWEEN with three direct children — exercises offset computation
 * for MULTIPLE shifted children (not just the rightmost), since space-between redistributes the gap
 * evenly when the parent grows.
 */
private class ParityStatefulSpaceBetweenRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParitySpaceBetweenRow(i))
        }
      }
}

private class ParitySpaceBetweenRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(justifyContent = YogaJustify.SPACE_BETWEEN, style = Style.height(80.px)) {
        child(Text(text = "Left $i", textSize = 14.sp))
        child(Text(text = "Mid $i", textSize = 14.sp))
        child(Text(text = "Right $i", textSize = 14.sp))
      }
}

/**
 * Each row has a `flex-grow=1` direct child whose allocated WIDTH changes when the row width
 * changes (it grows to fill remaining space). The splice gate's `directChildSizesMatch` check
 * should reject this row (size mismatch on the flex-grow child), forcing baseline fallback. Parity
 * must still hold: cache ON output == cache OFF output.
 */
private class ParityStatefulFlexGrowRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFlexGrowRow(i))
        }
      }
}

private class ParityFlexGrowRow(private val i: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        // Fixed left
        child(Text(text = "Left $i", textSize = 14.sp))
        // Flex-grow middle: width depends on row width (resizes when row resizes)
        child(
            Row(style = Style.flex(grow = 1f).height(40.px)) {
              child(Text(text = "Stretch $i", textSize = 12.sp))
            },
        )
        // Fixed right
        child(Text(text = "Right $i", textSize = 14.sp))
      }
}

// ── Position-shifted-external-parent fixture ── //

/**
 * Hosted wrapper extracted into its own KComponent so the fresh `onClick` lambda allocated each
 * outer render() doesn't break reconcile. KComponent isEquivalentTo compares the OUTER component's
 * fields (`rows: Int`) — same value across passes, so reconcile takes the REUSE path and skips
 * render() entirely. The previously-resolved wrapper Column (with its previously-allocated onClick
 * lambda and its host RTN id) is preserved verbatim.
 */
private class ParityHostedWrapper(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.onClick { /* makes wrapper a host RTN */ }) {
        for (i in 0 until rows) {
          child(ParityFeedRow(i))
        }
      }
}

/**
 * Fixture that triggers the gate's POSITION-only check (same RU id, different absoluteY).
 *
 * Structure:
 * ```
 * Outer Column
 *   ├── ParityHeightShiftSink (state-driven height — grows on update)
 *   └── ParityHostedWrapper (Column with onClick → gets a host RTN)
 *         └── rows of ParityFeedRow
 * ```
 *
 * On update: only the sink re-renders (state read is local to sink). [ParityHostedWrapper]
 * isEquivalentTo on `rows: Int` succeeds across passes → REUSE → its LithoNode (and the
 * onClick-bearing Column inside) is preserved, so the wrapper's host RU id is stable. But Yoga
 * reflows the outer Column: the wrapper shifts down by the sink's growth.
 *
 * For the cached rows under the wrapper:
 * - Snapshot from pass 1 has externalParent = wrapper's host RTN at, e.g., absY=10
 * - Pass 2: external parent = same wrapper host RTN (same RU id), but absY = 30
 * - `externalParentRenderUnitId` matches → identity check passes
 * - `externalParentAbsoluteY` differs → position check fails → splice rejected
 *
 * Without the position check, the splice would over-apply rootDx by exactly the wrapper's absoluteY
 * delta and produce wrong absoluteY for first-level descendants.
 */
private class ParityStatefulNestedHostMetadata(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        child(ParityHostedWrapper(rows))
      }
}

private class ParityStatefulPositionShiftedExternalParent(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityHeightShiftSink())
        child(ParityHostedWrapper(rows))
      }
}

// ── Deferred descendant fixture ── //
// Backs `parity_deferredLeafInsideCacheableSubtree`. See the per-test KDoc for the invariant
// being verified.

private class ParityStatefulDeferredLeafRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRow(i))
        }
        child(ParityDeferredLeafRow())
      }
}

/**
 * Wraps a `RenderWithConstraints` leaf in enough non-deferred ancestors that the wrapping ROW
 * becomes a viable subtree-cache subject. The leaf's lambda reads `innerState` so its actualResult
 * diverges across passes (first pass renders a single Text, second pass renders a Row of two Texts
 * → an extra mountable output).
 */
private class ParityDeferredLeafRow : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        child(
            Column {
              child(Text(text = "deferred-row-header", textSize = 14.sp))
              child(
                  RenderWithConstraints(style = Style.width(800.px).height(60.px)) { _ ->
                    val s = useState { 0 }
                    ParityStateHolder.innerState = s
                    if (s.value % 2 == 0) {
                      Text(text = "EVEN ${s.value}", textSize = 12.sp)
                    } else {
                      Row {
                        child(Text(text = "OD", textSize = 12.sp))
                        child(Text(text = "D ${s.value}", textSize = 12.sp))
                      }
                    }
                  },
              )
            },
        )
      }
}

private class ParityStatefulInvariantDeferredLeafRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRow(i))
        }
        child(ParityInvariantDeferredLeafRow())
      }
}

/**
 * Deferred leaf whose body returns a constant — `actualResult` shape never differs across passes.
 * Pins the trade-off that the gate is structural (bake-in based on recording window crossing a
 * deferred), not content-aware.
 */
private class ParityInvariantDeferredLeafRow : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        child(
            Column {
              child(Text(text = "invariant-deferred-row-header", textSize = 14.sp))
              child(
                  RenderWithConstraints(style = Style.width(800.px).height(60.px)) { _ ->
                    Text(text = "constant", textSize = 12.sp)
                  },
              )
            },
        )
      }
}

private class ParityStatefulMultipleDeferredLeavesRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRow(i))
        }
        child(ParityMultipleDeferredLeavesRow())
      }
}

/**
 * Two deferred leaves as siblings in the same row. The counter increments twice but
 * `containsDeferredDescendant` reads as boolean — the enclosing subtree-root rejects exactly once.
 */
private class ParityMultipleDeferredLeavesRow : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        child(
            Column {
              child(Text(text = "multi-deferred-row-header", textSize = 14.sp))
              child(
                  RenderWithConstraints(style = Style.width(400.px).height(60.px)) { _ ->
                    val s = useState { 0 }
                    ParityStateHolder.innerState = s
                    Text(text = "left=${s.value}", textSize = 12.sp)
                  },
              )
              child(
                  RenderWithConstraints(style = Style.width(400.px).height(60.px)) { _ ->
                    Text(text = "right-constant", textSize = 12.sp)
                  },
              )
            },
        )
      }
}

private class ParityStatefulNestedDeferredLeafRoot(private val rows: Int) : KComponent() {
  override fun ComponentScope.render(): Component =
      Column(style = Style.width(1080.px).height(1920.px)) {
        child(ParityStatefulSink())
        for (i in 0 until rows) {
          child(ParityFeedRow(i))
        }
        child(ParityNestedDeferredLeafRow())
      }
}

/**
 * Nested deferred: the outer `RenderWithConstraints`'s body returns another
 * `RenderWithConstraints`. Counter increments at every entry into the deferred branch — the
 * enclosing subtree's recording window crosses both — confirming the gate composes through multiple
 * deferred boundaries.
 */
private class ParityNestedDeferredLeafRow : KComponent() {
  override fun ComponentScope.render(): Component =
      Row(style = Style.height(80.px)) {
        child(
            Column {
              child(Text(text = "nested-deferred-row-header", textSize = 14.sp))
              child(
                  RenderWithConstraints(style = Style.width(800.px).height(60.px)) { _ ->
                    RenderWithConstraints(style = Style.width(800.px).height(60.px)) { _ ->
                      val s = useState { 0 }
                      ParityStateHolder.innerState = s
                      Text(text = "inner=${s.value}", textSize = 12.sp)
                    }
                  },
              )
            },
        )
      }
}
