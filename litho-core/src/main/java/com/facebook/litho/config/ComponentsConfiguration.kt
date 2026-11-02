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

package com.facebook.litho.config

import android.os.Build
import com.facebook.litho.BuildConfig
import com.facebook.litho.ComponentHost
import com.facebook.litho.ComponentHost.UnsafeModificationPolicy
import com.facebook.litho.ComponentTree
import com.facebook.litho.DefaultErrorEventHandler
import com.facebook.litho.ErrorEventHandler
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.rendercore.PoolingPolicy
import com.facebook.rendercore.incrementalmount.GapWorkerMode
import com.facebook.rendercore.visibility.VisibilityBoundsTransformer

/**
 * These values are safe defaults and should not require manual changes.
 *
 * This class hosts all the config parameters that the ComponentTree configures it self .... enable
 * and disable features ... A Component tree uses the [.defaultComponentsConfiguration] by default
 * but a [Builder] can be used to create new instances of the config to override the default
 * parameters ... The default config values can also be overridden by manually setting their values
 * in [.defaultBuilder]
 */
data class ComponentsConfiguration
internal constructor(
    val shouldAddHostViewForRootComponent: Boolean = false,
    /**
     * How this tree uses the gap worker: off, premount, cost-aware premount, or cost-aware premount
     * that also releases seen items as they leave the screen. See [GapWorkerMode].
     */
    @JvmField val gapWorkerMode: GapWorkerMode = GapWorkerMode.OFF,
    /** The handler [ComponentTree] will be used to run the pre-allocation process */
    @JvmField val preAllocationHandler: PreAllocationHandler? = null,
    /** Whether the [com.facebook.rendercore.MountState] can be mounted using incremental mount. */
    @JvmField val incrementalMountEnabled: Boolean = true,
    /** Determines the pooling behavior for component hosts */
    @JvmField val componentHostPoolingPolicy: PoolingPolicy = PoolingPolicy.Disabled,
    /**
     * Whether the [com.facebook.LithoView] associated with the [com.facebook.litho.ComponentTree]
     * will process visibility events.
     */
    @JvmField val visibilityProcessingEnabled: Boolean = true,
    /**
     * This class is an error event handler that clients can optionally set on a [ComponentTree] to
     * gracefully handle uncaught/unhandled exceptions thrown from the framework while resolving a
     * layout.
     */
    @JvmField val errorEventHandler: ErrorEventHandler = DefaultErrorEventHandler,
    @JvmField val logTag: String? = null,
    /**
     * Determines whether we log, crash, or do nothing if an invalid
     * [com.facebook.litho.ComponentHost] view modification is detected.
     *
     * @see [ComponentHost.UnsafeModificationPolicy]
     */
    @JvmField
    val componentHostInvalidModificationPolicy: ComponentHost.UnsafeModificationPolicy? = null,

    /** Lifecycle Owner in BaseMountingView and TreeProp */
    @JvmField val enableDefaultLifecycleOwnerAsFragmentOrActivity: Boolean = false,

    /** Visibility Events related */
    @JvmField val enableCheckVisibilityAggregated: Boolean = false,
    /**
     * Gives Lazy Collections that keep the default item animator structural item animations (fade
     * on insert and remove, slide on move), as
     * [com.facebook.litho.sections.widget.RecyclerCollectionComponent] has. Only lists whose
     * children all have explicit ids are animated, since generated ids are positional. Each
     * RecyclerView gets its own animator instance.
     */
    @JvmField val useDefaultItemAnimatorInLazyCollections: Boolean = false,
    /**
     * Enables refresh shortcuts while a refreshable collection or its non-editor child has focus.
     */
    @JvmField val enableKeyboardPullToRefresh: Boolean = false,
    /**
     * This defines which strategy we will use to bind the
     * [com.facebook.litho.sections.widget.ExperimentalRecycler].
     *
     * @see [PrimitiveRecyclerBinderStrategy] for more details.
     */
    @JvmField
    val primitiveRecyclerBinderStrategy: PrimitiveRecyclerBinderStrategy =
        PrimitiveRecyclerBinderStrategy.DEFAULT,
    /**
     * This flag is used to enable a fix for the issue where components that match the host view
     * size do not get unmounted when they go out of the viewport.
     */
    @JvmField val enableFixForIM: Boolean = false,
    /**
     * When true, a released [com.facebook.litho.ComponentTree] detaches itself from its
     * [com.facebook.litho.StateUpdaterDelegator], so it can be garbage collected while the
     * delegator's owner (such as a ComponentTreeHolder) is alive.
     */
    @JvmField val detachStateUpdaterOnTreeRelease: Boolean = false,
    /**
     * When true, [com.facebook.litho.BaseMountingView.notifyVisibleBoundsChanged] returns early
     * when the view is fully visible and its last mount already covered all of it, so a parent can
     * notify every nested view on each scroll frame (e.g. through a
     * [com.facebook.rendercore.extensions.HostVisibilityNotifier]) without each one redoing its
     * mount pass. It only skips after a pass that processed visibility for the same bounds, so the
     * next notify after an unbind, a detach or a visibility hint change still runs a full pass.
     */
    @JvmField val skipMountWhenFullyShownAndMounted: Boolean = false,
    /**
     * When true, uses an empty visible rect for visibility processing when the root host view is
     * detached from the window. This causes all visibility outputs to be treated as invisible,
     * properly firing InvisibleEvent, UnfocusedVisibleEvent, etc. This prevents incorrect
     * visibility events caused by [android.view.View.getLocalVisibleRect] returning stale/full
     * rects for detached views. Uses [android.view.View.isAttachedToWindow] which remains true
     * during temporary detaches (e.g. RecyclerView item recycling), so those legitimate cases are
     * not affected.
     */
    @JvmField val useEmptyVisibleRectOnDetach: Boolean = false,
    /**
     * When true, a visibility hint set via the deprecated
     * [com.facebook.litho.BaseMountingView.setVisibilityHint] API is cleared when the ComponentTree
     * is detached from its view. Without this, a view hinted invisible before being recycled or
     * rebound keeps refusing to mount once it hosts new content, rendering as correctly sized blank
     * space.
     */
    @JvmField val resetVisibilityHintOnTreeDetach: Boolean = false,
    @JvmField val visibilityBoundsTransformer: VisibilityBoundsTransformer? = null,
    @JvmField val sectionsRecyclerViewOnCreateHandler: ((Any) -> Unit)? = null,
    /**
     * Determines whether we should enable stable ids by default in the
     * [com.facebook.litho.widget.RecyclerBinder]
     */
    @JvmField val useStableIdsInRecyclerBinder: Boolean = true,
    /**
     * This will perform an optimization that will verify if the same size specs were used. However,
     * this creates a bug in a specific scenario where double measure happens.
     */
    @JvmField val performExactSameSpecsCheck: Boolean = true,
    @JvmField val componentEqualityMode: ComponentEqualityMode = ComponentEqualityMode.DEFAULT,

    /**
     * When enabled the framework will add an additional binder to Host RenderUnits to clean up view
     * attributes that may have been added by non-litho code.
     */
    @JvmField val isHostViewAttributesCleanUpEnabled: Boolean = false,
    /** This flag is used to enable the new state read tracking logic. */
    @JvmField val enableStateReadTracking: Boolean = true,
    /** Determines the policy to use when measuring in resolve phase is detected. */
    @JvmField val measureInResolvePolicy: LogicViolationPolicy = LogicViolationPolicy.LOG,
    /** Determines the policy to use when state reads across tree boundaries is detected. */
    @JvmField val crossTreeStateReadPolicy: LogicViolationPolicy = LogicViolationPolicy.LOG,
    /**
     * Determines the policy to use when an event handler is created with the [ComponentContext] of
     * a component other than the one that owns the handler.
     */
    @JvmField val eventHandlerViolationPolicy: LogicViolationPolicy = LogicViolationPolicy.CRASH,
    /**
     * Determines the policy to use when a state update is performed with the [ComponentContext] of
     * a component other than the one that owns the state. Defaults to [LogicViolationPolicy.LOG] so
     * the newly-added assertion can be observed via [com.facebook.litho.debug.DebugInfoReporter]
     * before being promoted to [LogicViolationPolicy.CRASH].
     */
    @JvmField val stateUpdateViolationPolicy: LogicViolationPolicy = LogicViolationPolicy.LOG,
    /**
     * This flag is used to independently enable the new behavior for State that detaches its
     * identity from its value.
     */
    @JvmField val detachStateIdFromValue: Boolean = false,
    /** This flag is used to trigger a log whenever a recursive state provider scope is detected */
    @JvmField val logRecursiveStateProviderScope: Boolean = false,
    /** When enabled, contextual effects use the optimized handler implementation. */
    @JvmField val useOptimizedContextualEffectHandler: Boolean = true,

    /** This flag enables the use of Component State for cached values */
    @JvmField val useStateForCachedValues: Boolean = false,

    /** This flag enables the new collection infra */
    @JvmField val enableNewCollection: Boolean = false,

    /**
     * When true, animated values produced during the `AnimationController` frame callback are
     * published through a same-frame invalidation path (`StateUpdater.applyAnimationFrameUpdates`)
     * instead of the frame-deferred `updateHookStateSync`. When false, animation frames fall back
     * to the previous frame-deferred behavior.
     */
    @JvmField val enableAnimationFrameInvalidation: Boolean = true,

    /**
     * This object provides a custom Runnable Handler where each handler holding a reference to
     * single thread. The default value is a standard single Layout component thread.
     */
    @JvmField
    var customLayoutHandlerProvider: RunnableHandlerProvider =
        ComponentTree.DefaultLayoutHandlerProvider(),
    /**
     * When true, view style properties use onBindViewWithDescription binders instead of
     * ViewAttributes.
     */
    @JvmField val useViewStyleBinders: Boolean = false,
    /** Use postAtFront for layout promotion to prioritize background layout results */
    @property:ExperimentalLithoApi @JvmField val usePostAtFrontForLayoutPromotion: Boolean = false,
    /**
     * When true, only use postAtFront for the first background layout promotion per ComponentTree
     */
    @property:ExperimentalLithoApi
    @JvmField
    val usePostAtFrontForLayoutPromotionOnlyFirstLayout: Boolean = false,
    /**
     * When true, a measure that reuses the main-thread layout because its size specs are exactly
     * the same does not request another async layout when the tree's own size specs are unchanged.
     * The pending render already covers them, and the extra request only repeats it, for example
     * after a state update cloned the root.
     */
    @property:ExperimentalLithoApi @JvmField val skipRedundantAsyncMeasureLayout: Boolean = false,
    /**
     * When true, the legacy `Row`/`Column` builder's `build()` returns a KRow/KColumn
     * ([com.facebook.litho.layout.flexbox.FlexboxContainerComponent]) carrying a [Style], instead
     * of a legacy SpecGenerated `Row`/`Column`. Guards the migration of the legacy builders onto
     * the Kotlin flexbox components.
     */
    @JvmField val useKComponentForLegacyRowColumn: Boolean = false,
) {

  companion object {

    /**
     * This is just a proxy to [LithoDebugConfigurations.isDebugModeEnabled]. We have to keep it
     * until we release a new oss version and we can refer to [LithoDebugConfigurations] directly on
     * Flipper.
     */
    @Deprecated("Use the LithoDebugConfigurations instead")
    var isDebugModeEnabled: Boolean
      get() = LithoDebugConfigurations.isDebugModeEnabled
      set(value) {
        LithoDebugConfigurations.isDebugModeEnabled = value
      }

    /**
     * This is just a proxy to [LithoDebugConfigurations.isDebugHierarchyEnabled]. We have to keep
     * it until we release a new oss version and we can refer to [LithoDebugConfigurations] directly
     * on Flipper.
     */
    @Deprecated("Use the LithoDebugConfigurations instead")
    var isDebugHierarchyEnabled: Boolean
      get() = LithoDebugConfigurations.isDebugHierarchyEnabled
      set(value) {
        LithoDebugConfigurations.isDebugHierarchyEnabled = value
      }

    @JvmField var defaultInstance: ComponentsConfiguration = ComponentsConfiguration()

    /** Indicates that the incremental mount helper is required for this build. */
    @JvmField val USE_INCREMENTAL_MOUNT_HELPER: Boolean = BuildConfig.USE_INCREMENTAL_MOUNT_HELPER

    /** Whether we need to account for lack of synchronization while accessing Themes. */
    @JvmField
    val NEEDS_THEME_SYNCHRONIZATION: Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1

    /** The default priority for threads that perform background layout calculations. */
    const val DEFAULT_BACKGROUND_THREAD_PRIORITY: Int = 5

    /**
     * The default priority for threads that perform background sections change set calculations.
     */
    const val DEFAULT_CHANGE_SET_THREAD_PRIORITY: Int = 0

    /**
     * Populates additional metadata to find mounted components at runtime. Defaults to the presence
     * of an
     *
     * ```
     * IS_TESTING
     * ```
     *
     * system property at startup but can be overridden at runtime.
     */
    @JvmField var isEndToEndTestRun: Boolean = System.getProperty("IS_TESTING") != null
    @JvmField var isAnimationDisabled = "true" == System.getProperty("litho.animation.disabled")

    /**
     * By default end-to-end tests will disable transitions and this flag lets to explicitly enable
     * transitions to test animation related behavior.
     */
    @JvmField var forceEnableTransitionsForInstrumentationTests: Boolean = false

    @JvmField var enableThreadTracingStacktrace: Boolean = false

    @JvmField var overlappingRenderingViewSizeLimit: Int = Int.MAX_VALUE
    @JvmField var partialAlphaWarningSizeThresold: Int = Int.MAX_VALUE

    /** Initialize sticky header during layout when its component tree is null */
    @JvmField var initStickyHeaderInLayoutWhenComponentTreeIsNull: Boolean = false

    @JvmField var hostComponentPoolSize: Int = 30

    /**
     * Caches, per class, the `@Comparable`-annotated fields that
     * [com.facebook.litho.ComponentUtils.hasEquivalentFields] compares, together with their
     * resolved comparison type and with accessibility already forced.
     *
     * The three reflective steps this skips — `getDeclaredFields`, the `isAnnotationPresent`
     * filter, and the `getAnnotation` read that allocates an annotation proxy — depend only on the
     * class, but run per comparison when this is false. Only reading the two field values is
     * per-instance.
     *
     * Both arms return the same answer for every pair; this exists to measure the difference.
     */
    @JvmField var cacheComparableFields: Boolean = false

    @JvmField var shouldOverrideHasTransientState: Boolean = false
    @JvmField var reduceMemorySpikeUserSession: Boolean = false
    @JvmField var reduceMemorySpikeDataDiffSection: Boolean = false
    @JvmField var reduceMemorySpikeGetUri: Boolean = false
    @JvmField var clampSelectionMarkerBoundsInTextInput: Boolean = true

    @JvmField var enableInvalidAccessibilityVirtualNodeFix: Boolean = false

    /**
     * Dispatches `Style.onPopulateAccessibilityNode` on hosts that have neither a node-info handler
     * nor an accessible mount item. Without it the handler never runs on such hosts, e.g. a
     * clickable Row whose text is hosted by its own view.
     */
    @JvmField var enablePopulateAccessibilityNodeOnAllHosts: Boolean = false

    @JvmField var enableComponentHostViewMoveInvalidation: Boolean = false

    /**
     * This flag enables the use of the main thread priority when raising the priority of a blocking
     * thread on main
     */
    @JvmField var enableRaisePriorityToMain: Boolean = false

    /**
     * When true, IMOs are created with hostOutput=null during reduce (skipping per-child
     * ensureHostBounds), and host bounds are finalized in a single bottom-up pass after reduce.
     * Eliminates N recursive ensureHostBounds calls, replacing with N flat comparisons.
     *
     * This is the umbrella flag for moving IMO position-dependent state out of construction and
     * into [LithoReducer.finalizeIncrementalMountBounds]; subsequent diffs in the subtree cache
     * Phase 1A series add additional finalize-driven passes (bounds initialization, hostOutput
     * re-derivation) under this same gate.
     */
    @JvmField var useFinalizeForIMOBounds: Boolean = false

    /** This config will enable logging of interactable components with 0 alpha */
    @JvmField var isZeroAlphaLoggingEnabled: Boolean = false

    /** This flag is to enable usage custom pool scopes */
    @JvmField var customPoolScopesEnabled: Boolean = false

    /** When true, uses memory-efficient structural GlobalKey instead of concatenated strings. */
    @JvmField var useMemoryEfficientGlobalKeys: Boolean = true

    /** This flag is to enable usage of Primitive Text */
    @JvmField var usePrimitiveText: Boolean = false

    /** This flag is to enable usage of text with spans when measuring Primitive TextInput */
    @JvmField var useTextWithSpansForMeasure: Boolean = false

    /** Use SpannableString instead of SpannedString in TextInput to fix ClassCastException. */
    @JvmField var useSpannableStringInTextInput: Boolean = false

    /** This flag is to enable usage of Primitive TextInput */
    @JvmField var usePrimitiveTextInput: Boolean = false

    /**
     * Gives every mounted TextInput MATCH_PARENT layout params, as its
     * `shouldUseMatchParentLayoutParams` prop does for one input. Litho sizes the EditText itself,
     * so the default WRAP_CONTENT params only make every edit request a layout up to the window
     * root.
     */
    @JvmField var textInputUsesMatchParentLayoutParams: Boolean = false

    /**
     * When true, `TextInputComponentSpec.EditTextWithEventHandlers.onTextChanged` stores a detached
     * `SpannableStringBuilder` snapshot in `savedText` instead of the live buffer owned by the
     * underlying `EditText`. This eliminates a TOCTOU race that crashes with
     * `IndexOutOfBoundsException` in `TextInputComponentSpec.createAndMeasureEditText` when the
     * layout thread reads the live buffer while the user concurrently mutates it on the main
     * thread.
     */
    @JvmField var snapshotTextInputTextOnTextChanged: Boolean = false

    /**
     * This flag is used to enable a fix for the issue where the text is not truncated correctly
     * when customEllipsisText is set.
     */
    @JvmField var enableFixForTextEllipsisOffset: Boolean = true

    /**
     * Fixes RTL custom ellipsis truncation by converting ellipsisTarget to a run-relative advance
     * (layoutWidth - ellipsisTarget) before passing to Paint.getOffsetForAdvance for RTL text.
     */
    @JvmField var fixRtlCustomEllipsisTruncation: Boolean = false

    /**
     * Steps a custom ellipsis truncation back by grapheme until the custom ellipsis fits on the
     * last visible line, instead of letting it wrap onto a line that is clipped.
     */
    @JvmField var fitCustomEllipsisOnTruncatedLine: Boolean = false

    /** This flag is to enable usage of new onTouch behaviour within TextSpec touchableSpan */
    @JvmField var enableNewHandleTouchForSpansMethod: Boolean = false

    /**
     * Guards against ANR in TextDrawable touch handling caused by expensive
     * Layout.getOffsetForHorizontal() calls on complex text. When enabled, skips redundant span
     * lookup on MOVE events in the old handleTouchForSpans path.
     */
    @JvmField var enableTextDrawableTouchAnrFix: Boolean = false

    /** Enables the use of a collection-specific layout handler provider for improved performance */
    @JvmField var enableCollectionLayoutHandlerProvider: Boolean = false

    /** Enables dynamic sizing of handler pools based on usage patterns */
    @JvmField var enableDynamicPoolSize: Boolean = false

    /** Active pool size for collection layout handler provider */
    @JvmField var collectionLayoutHandlerPoolActiveSize: Int = 2

    /** Pool size for collection layout handler provider */
    @JvmField var collectionLayoutHandlerPoolSize: Int = 5

    /** Thread priority for collection layout handler threads */
    @JvmField var collectionLayoutHandlerPriority: Int = 5

    /** Enables double measurement for grid layouts. */
    @JvmField var enableDoubleMeasureForGridLayout: Boolean = false

    /**
     * When a collection item is bound and its renderer already has a layout committed for the same
     * size constraints, show that layout and render the current component in the background instead
     * of rendering it synchronously on the main thread.
     */
    @JvmField var useCommittedLayoutOnCollectionBind: Boolean = false

    /**
     * When enabled, the trace section name in EventHandler.dispatchEvent is lazily evaluated,
     * avoiding string allocation on every dispatch when systrace is not active.
     */
    @JvmField var lazyEventHandlerTraceName: Boolean = false

    @JvmField
    var collectionLayoutHandlerProvider: RunnableHandlerProvider =
        ComponentTree.DefaultLayoutHandlerProvider()

    /** Controls whether collection item preparation should be disabled on the UI thread. */
    @JvmField var disablePreparationOnUiThread: Boolean = true

    /**
     * When enabled, a layout-only pass (same resolve, new size specs) takes its `RenderState` from
     * the resolve snapshot instead of the last committed layout. The committed layout's render data
     * is written on the main thread at mount, so copying it on a layout thread can throw a
     * `ConcurrentModificationException`. Layout-scoped state still carries over from the committed
     * layout.
     */
    @JvmField var layoutOnlyPassUsesResolveRenderState: Boolean = true

    /**
     * Enables dispatching working ranges that were registered on a deferred node holder. Without
     * it, a spec whose layout is delegated to a component that defers resolution loses its working
     * ranges, because the registrations land on the holder and nothing reads them there.
     */
    @JvmField var enableWorkingRangeOnDeferredNode: Boolean = false

    /**
     * Marks a deferred node (`RenderWithConstraints`, a `Component.measure` holder) dirty when the
     * layout cache copies its subtree whole, so Yoga measures it under the constraints of this
     * pass. Without it, Yoga can reuse a size the node measured in the last pass under other
     * constraints, and its content is laid out only at that size.
     */
    @JvmField var remeasureDeferredNodesInCachedSubtree: Boolean = false

    /** Enables a default visibility events controller in LithoView to handle visibility events */
    @JvmField var enableDefaultVisibilityEventsController: Boolean = false

    /** Enables a fix for input connection. */
    @JvmField var enableInputConnectionFix: Boolean = false

    /**
     * When enabled, additional main thread assertions will be performed in ComponentHost methods
     * that were not previously checked.
     */
    @JvmField var enableExpandedComponentHostMainThreadChecks: Boolean = false

    /**
     * When enabled, a main thread assertion will be performed in ComponentHost.requestLayout. This
     * is separate from [enableExpandedComponentHostMainThreadChecks] because requestLayout was
     * found to be called off the main thread significantly more than other methods.
     */
    @JvmField var enableRequestLayoutMainThreadCheck: Boolean = false

    /**
     * When `true`, the shared Litho [com.facebook.yoga.YogaConfig] (built by
     * [com.facebook.litho.yoga.LithoYogaFactory.createYogaConfig]) clears the
     * `MinSizeUndefinedInsteadOfAuto` errata bit, enabling CSS Flexbox §4.5 automatic minimum
     * sizing for every flex item. Each item whose main-axis `min-{width,height}` is undefined gets
     * a content-derived floor — `min(min-content, specified-size, max-size)` plus any
     * aspect-ratio-transferred lower bound — so it cannot shrink below what CSS browsers would
     * honor.
     *
     * Read once at process start when [com.facebook.litho.NodeConfig.yogaConfig] is initialised;
     * set this from your app's startup before any `ComponentTree` is created.
     *
     * Per-item opt-outs follow the CSS spec: explicit `Style.minWidth(0.dp)` /
     * `Style.minHeight(0.dp)`, `display:none`, or `overflow != visible`.
     */
    @JvmField var useAutoMinSize: Boolean = false

    /**
     * When `true`, interactive components smaller than [minTouchTargetSizeDp] have their touch
     * target (not their visual bounds) expanded to that minimum, augmenting any manual
     * [com.facebook.litho.view.touchExpansion].
     */
    @JvmField var useAutoTouchExpansion: Boolean = false

    /** The minimum touch-target size, in dp, used by [useAutoTouchExpansion]. Defaults to 48dp. */
    @JvmField var minTouchTargetSizeDp: Int = 48

    /**
     * Policy for interactive components smaller than [minTouchTargetSizeDp].
     * [LogicViolationPolicy.LOG] reports each one via [com.facebook.litho.debug.DebugInfoReporter]
     * to measure the opportunity; [LogicViolationPolicy.NONE] (default) is a no-op. A sub-minimum
     * touch target is never fatal, so [LogicViolationPolicy.CRASH] is treated as
     * [LogicViolationPolicy.LOG]. Independent of [useAutoTouchExpansion].
     */
    @JvmField var minTouchTargetViolationPolicy: LogicViolationPolicy = LogicViolationPolicy.NONE

    /**
     * When enabled, the reduction pass caches subtree index ranges and splices unchanged subtrees
     * from the previous LayoutState instead of re-traversing them.
     */
    @JvmField var enableSubtreeCaching: Boolean = false

    /**
     * Checks each `Layout` child whose layout holds a subtree copied whole from the last pass's
     * layout cache against a fresh layout, on sizes, and the positions of every descendant that is
     * not deferred content; deferred content is compared by its holder's size only. Throws on a
     * difference (`CustomLayoutShadowValidation`). Off by default; for tests and debug builds.
     */
    @JvmField var customLayoutShadowValidation: Boolean = false

    /**
     * This method is only used so that Java clients can have a builder like approach to override a
     * configuration.
     */
    @JvmStatic fun create(): Builder = create(defaultInstance)

    @JvmStatic
    fun create(configuration: ComponentsConfiguration): Builder = Builder(configuration.copy())
  }

  /**
   * This is a builder that only exists so that Java clients can have an easier time creating and
   * overriding specific configurations. For Kotlin one can use directly the named parameters on the
   * [ComponentsConfiguration] constructor.
   */
  class Builder internal constructor(private var config: ComponentsConfiguration) {

    fun shouldAddHostViewForRootComponent(enabled: Boolean): Builder = apply {
      config = config.copy(shouldAddHostViewForRootComponent = enabled)
    }

    fun withPreAllocationHandler(handler: PreAllocationHandler?): Builder = apply {
      config = config.copy(preAllocationHandler = handler)
    }

    fun incrementalMountEnabled(enabled: Boolean): Builder = apply {
      config = config.copy(incrementalMountEnabled = enabled)
    }

    /** See [ComponentsConfiguration.gapWorkerMode]. */
    fun gapWorkerMode(mode: GapWorkerMode): Builder = apply {
      config = config.copy(gapWorkerMode = mode)
    }

    fun componentHostPoolingPolicy(poolingPolicy: PoolingPolicy): Builder = apply {
      config = config.copy(componentHostPoolingPolicy = poolingPolicy)
    }

    fun componentHostInvalidModificationPolicy(
        invalidModificationPolicy: UnsafeModificationPolicy?,
    ): Builder = apply {
      config = config.copy(componentHostInvalidModificationPolicy = invalidModificationPolicy)
    }

    fun enableVisibilityProcessing(enabled: Boolean): Builder = apply {
      config = config.copy(visibilityProcessingEnabled = enabled)
    }

    fun errorEventHandler(handler: ErrorEventHandler): Builder = apply {
      config = config.copy(errorEventHandler = handler)
    }

    fun logTag(tag: String?): Builder = apply { config = config.copy(logTag = tag) }

    fun primitiveRecyclerBinderStrategy(
        primitiveRecyclerBinderStrategy: PrimitiveRecyclerBinderStrategy,
    ): Builder = apply {
      config = config.copy(primitiveRecyclerBinderStrategy = primitiveRecyclerBinderStrategy)
    }

    fun enableDefaultLifecycleOwnerAsFragmentOrActivity(
        enableDefaultLifecycleOwnerAsFragmentOrActivity: Boolean,
    ): Builder = apply {
      config =
          config.copy(
              enableDefaultLifecycleOwnerAsFragmentOrActivity =
                  enableDefaultLifecycleOwnerAsFragmentOrActivity,
          )
    }

    fun useDefaultItemAnimatorInLazyCollections(enabled: Boolean): Builder = apply {
      config = config.copy(useDefaultItemAnimatorInLazyCollections = enabled)
    }

    fun enableKeyboardPullToRefresh(enabled: Boolean): Builder = apply {
      config = config.copy(enableKeyboardPullToRefresh = enabled)
    }

    fun enableFixForIM(enabled: Boolean): Builder = apply {
      config = config.copy(enableFixForIM = enabled)
    }

    fun detachStateUpdaterOnTreeRelease(enabled: Boolean): Builder = apply {
      config = config.copy(detachStateUpdaterOnTreeRelease = enabled)
    }

    fun skipMountWhenFullyShownAndMounted(enabled: Boolean): Builder = apply {
      config = config.copy(skipMountWhenFullyShownAndMounted = enabled)
    }

    fun useEmptyVisibleRectOnDetach(enabled: Boolean): Builder = apply {
      config = config.copy(useEmptyVisibleRectOnDetach = enabled)
    }

    fun resetVisibilityHintOnTreeDetach(enabled: Boolean): Builder = apply {
      config = config.copy(resetVisibilityHintOnTreeDetach = enabled)
    }

    fun visibilityBoundsTransformer(transformer: VisibilityBoundsTransformer?): Builder = apply {
      config = config.copy(visibilityBoundsTransformer = transformer)
    }

    fun sectionsRecyclerViewOnCreateHandler(handler: ((Any) -> Unit)?): Builder = apply {
      config = config.copy(sectionsRecyclerViewOnCreateHandler = handler)
    }

    fun useStableIdsInRecyclerBinder(enabled: Boolean): Builder = apply {
      config = config.copy(useStableIdsInRecyclerBinder = enabled)
    }

    fun enableHostViewAttributesCleanUp(enabled: Boolean): Builder = apply {
      config = config.copy(isHostViewAttributesCleanUpEnabled = enabled)
    }

    fun enableStateReadTracking(enabled: Boolean): Builder = apply {
      config = config.copy(enableStateReadTracking = enabled)
    }

    fun useOptimizedContextualEffectHandler(enabled: Boolean): Builder = apply {
      config = config.copy(useOptimizedContextualEffectHandler = enabled)
    }

    fun enableNewCollection(enabled: Boolean): Builder = apply {
      config = config.copy(enableNewCollection = enabled)
    }

    fun customLayoutHandlerProvider(provider: RunnableHandlerProvider): Builder = apply {
      config = config.copy(customLayoutHandlerProvider = provider)
    }

    @ExperimentalLithoApi
    fun usePostAtFrontForLayoutPromotion(enabled: Boolean): Builder = apply {
      config = config.copy(usePostAtFrontForLayoutPromotion = enabled)
    }

    @ExperimentalLithoApi
    fun usePostAtFrontForLayoutPromotionOnlyFirstLayout(enabled: Boolean): Builder = apply {
      config = config.copy(usePostAtFrontForLayoutPromotionOnlyFirstLayout = enabled)
    }

    @ExperimentalLithoApi
    fun skipRedundantAsyncMeasureLayout(enabled: Boolean): Builder = apply {
      config = config.copy(skipRedundantAsyncMeasureLayout = enabled)
    }

    fun useKComponentForLegacyRowColumn(enabled: Boolean): Builder = apply {
      config = config.copy(useKComponentForLegacyRowColumn = enabled)
    }

    fun build(): ComponentsConfiguration = config
  }

  enum class LogicViolationPolicy(val key: String) {
    LOG("log"),
    CRASH("crash"),
    NONE("none"),
  }
}

fun String?.toViolationPolicy(): ComponentsConfiguration.LogicViolationPolicy {
  return when (this) {
    "crash" -> ComponentsConfiguration.LogicViolationPolicy.CRASH
    "debug_crash",
    "crash_debug" ->
        if (BuildConfig.IS_INTERNAL_BUILD) {
          ComponentsConfiguration.LogicViolationPolicy.CRASH
        } else {
          ComponentsConfiguration.LogicViolationPolicy.LOG
        }
    "log" -> ComponentsConfiguration.LogicViolationPolicy.LOG
    "none" -> ComponentsConfiguration.LogicViolationPolicy.NONE
    else -> ComponentsConfiguration.LogicViolationPolicy.LOG
  }
}
