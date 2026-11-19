# Changelog

## Version 0.51.0-SNAPSHOT

_release-date_

* [NEW] Add new `useFirstDrawReporter` hook together with `Style.reportFirstContentDraw` for executing actions when related Component is fully rendered on the screen. Can be used to mark the end of TTRC or TTI markers.
* [NEW] Add a `LithoView(componentTree)` constructor, deprecated as well: it's a convenience for code that hasn't moved to `setLithoContent` or `lithoHost` yet. The view uses the tree's Android `Context`, so a tree no longer needs its Context passed again, as `LithoView.create(context, componentTree)` does.
* [Deprecated] Every `LithoView.create` overload, and `Activity.setContent`. Use `lithoHost` (`LithoHost.kt` in litho-surface) for a Fragment's or Activity's content, and `setLithoContent` (`LithoSurface.kt` in litho-surface) for other views.
* [BREAKING] Remove `LegacyLithoViewRule` from `com.facebook.litho.testing`, please use `LithoTestRule` and `TestLithoView` instead.
* [BREAKING] Replace `ComponentsConfiguration.useIncrementalMountGapWorker` and `enableGapWorkerIncrementalUnmount` with a single `gapWorkerMode: GapWorkerMode` (default `OFF`). Migrate `useIncrementalMountGapWorker(true)` to `gapWorkerMode(GapWorkerMode.PREMOUNT)`, or `COST_AWARE_PREMOUNT` / `COST_AWARE_PREMOUNT_WITH_INCREMENTAL_UNMOUNT`. `IncrementalMountExtensionConfigs.useGapWorker` and `useCostAwareGapWorkerPremount` are removed: the tree's mode alone decides the route.
* [BREAKING] Remove the Unit-returning `Style.onPerformAccessibilityAction`, `Style.onRequestSendAccessibilityEvent` and `Style.onPerformActionForVirtualView`, please use `onPerformAccessibilityActionWithResult`, `onRequestSendAccessibilityEventWithResult` and `onPerformActionForVirtualViewWithResult` instead.
* [BREAKING] `LithoLayoutResult.layoutOutput` is removed, and `YogaLayoutOutput` and `getYogaNode()` are internal. `LithoLayoutResult.measureOutput`, a new read-only sealed class `LithoMeasureOutput`, holds the node's geometry as its layout engine built it: `width`, `height`, padding, border and `layoutDirection`. The rest of what a pass records stays on the result itself: `widthSpec`, `heightSpec`, `contentWidth`, `contentHeight`, `lastMeasuredSize`, `cachedMeasuresValid`, `isDiffedLayout`, `isCachedLayout`, `diffNode`, `wasMeasured`, `measureHadExceptions`, `layoutData`, `delegate`, render units and `effects`: read `result.diffNode` instead of `result.layoutOutput.diffNode`. A node's position is its parent's: read `parent.getXForChildAtIndex(i)` instead of `layoutOutput.x`. The `LithoLayoutResult` and `DeferredLithoLayoutResult` constructors and `LithoNode.createLayoutResult` take a `LithoMeasureOutput`. `SpecGeneratedComponentLayout` takes the node's position and its measure output. `adjustedBounds` and `actualDeferredNodeResult` are internal: read `adjustedLeft()` to `adjustedBottom()`, and `DeferredLithoLayoutResult.result`. To inspect a result's margins or Yoga style, use `LithoLayoutResult.debugLayoutNode()` (`DebugLayoutNode`).
* [BREAKING] `LithoStats.customLayoutLeafCount` is removed: a `Layout`'s leaves are counted in `customLayoutChildLayoutCount`.

## Version 0.50.2
_2024-10-07_

* Accessibility API Improvements.
* Numerous bug fixes.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.50.1...v0.50.2).

## Version 0.50.1
_2024-07-10_

* Update the Barebones sample version

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.50.0...v0.50.1).

## Version 0.50.0
_2024-07-10_

* [BREAKING] All the nested BUCK modules under litho-core deleted
* [BREAKING] Rename `SpecTransitionWithDependency`,
* Exposed `androidContext` inside of `bind{}` blocks in Primitive API
* Add Style.rotationX/Y/, Styale.translationZ
* Add visibility style
* Converted more code to Kotlin
* Numerous bug fixes.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.49.1...v0.50.0).

## Version 0.49.1
_2024-03-13_

* [BREAKING] move litho-core-kotlin to litho-core module
* Converted more code to Kotlin

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.49.0...v0.49.1).

## Version 0.49.0

_2024-02-01_

* Converted more code to Kotlin.
* [NEW] Added debug event APIs for better debugging and integration with Flipper's new UI Debugger.
* [NEW] Added layout caching to improve performance.
* [BREAKING] Moved several configs from LithoConfiguration to ComponentsConfiguration.
* [BREAKING] Merged several configs from RecyclerBinderConfig and RecyclerBinderConfiguration.
* [BREAKING] Removed `Component.canResolve()` and `Component.resolve(ResolveContext, ComponentContext)` methods. All classes extending Component must now implement the new `Component.resolve(ResolveContext, ScopedComponentInfo, int, int, ComponentsLogger)` method which is always used in the resolution flow.
* [BREAKING] Rename `Style.performAccessibilityAction`, `Style.sendAccessibilityEvent` and `Style.sendAccessibilityEventUnchecked` methods.
* [BREAKING] Yoga updated to use C++ 20.
* [BREAKING] Deletes several public constructors of EventHandler.
* Overhauled the render pipeline to support custom layout systems.
* Numerous bug fixes.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.48.0...v0.49.0).

## Version 0.48.0

_2023-08-25_

* [NEW] Lots more code in Kotlin.
* [NEW] Adds new [Primitive APIs](https://fblitho.com/docs/primitivecomponents/overview/) (Kotlin APIs that replace MountSpecs) .
* [NEW] Bumps compile, min, target SDK, and builds tools versions.
* [NEW] Bumps Yoga to version 2.0.0.
* [NEW] Add litho-rendercore-glide package.
* [NEW] Fixes RenderCore sample app.
* [BREAKING] Removed `ComponentsConfiguration.canInterruptAndMoveLayoutsBetweenThreads`, `ComponentTree.canInterruptAndMoveLayoutsBetweenThreads`, `RecyclerBinder.canInterruptAndMoveLayoutsBetweenThreads`, `ComponentTreeHolder.canInterruptAndMoveLayoutsBetweenThreads`. ComponentTrees can no longer exempt themselves from interrupt and move layouts between threads operation.
* [BREAKING] Removed `usePersistentEffect` and made deps parameter required in `useEffect`, `useCoroutine`, `useFlow`. The equivalent of `usePersistentEffect {}` is `useEffect(Unit) {}`, and the equivalent of `useEffect {}` without arguments is `useEffect(Any()) {}`.
* [BREAKING] Removed deprecated APIs of GridRecyclerConfiguration.
* [FIXES] Reconciliation now works with components that render to null.
* [PERFORMANCE] Layout calculation split into 2 phases: resolution and measurements.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.47.0...v0.48.0).

## Version 0.47.0

_2023-02-13_

* [New] Adds debug overlay for to show components bounds, and interactive elements. Set the following configs to enable the feature: `debugHighlightInteractiveBounds`, `debugHighlightMountBounds`.
* [Deprecated] `ComponentTree.canInterruptAndMoveLayoutsBetweenThreads`, `RecyclerBinder.canInterruptAndMoveLayoutsBetweenThreads`, `ComponentTreeHolder.canInterruptAndMoveLayoutsBetweenThreads`. All of those will be removed in one of subsequent versions.
* [BREAKING] Introduces several new API to replace the ComponentTree in ComponentContext.
  * Use `ComponentContext#getLithoTree` to get access to API previous accessed from the `ComponentTree`.
  * `ComponentTree` removed from the `OnError` API; Use `ComponentContext#getErrorComponentReceiver` to set the root on error.
* [BREAKING] `equivalence` utility method have moved from `CommonUtils` to `EquivalenceUtils`.
* The logic to trigger mount has be simplified; there are now fewer hops between LithoView and ComponentTree.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.46.0...0.47.0).

## Version 0.46.0

_2023-01-05_

* [New] [Canvas component](https://fblitho.com/docs/widgets/canvas/) provides a means for drawing simple 2D graphics.
* [New] LayoutThreadFactory can now be customized via an optional Runnable; for example to set a StrictMode ThreadPolicy.
* [Testing] Several improvements to testings APIs: matching with text, content description.
* [Deprecated] SizeSpecMountWrapperComponentSpec. Use `@OnCreateLayoutWithSizeSpec` instead.
* [Internal] Render logic has been split into 2 classes: Resolver and Layout.
* [Internal] LayoutStateFuture has been generalised into a TreeFuture, and there are several related changes.
* [Internal] State generated during layout is now collected in a different StateHandler.
* [Internal] Improved reliability of rebinding EventHandlers.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.45.0...v0.46.0).

## Version 0.45.0

_2022-12-02_

 * Many internals changes which should not affect end-users: StateContainers are now immutable and added support for testing split of resolve and layout processes
 * Fix for bug where some EventHandlers would be stale after updates when using sections
 * SimpleMountable API is stabilized and ready to replace @MountSpec usages in Kotlin. See [here](https://fblitho.com/docs/mainconcepts/mountablecomponents/overview/) for the docs!

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.44.0...v0.45.0).

## Version 0.44.0

_2022-10-27_

 * New APIs for debugging.
 * **Breaking**: `ComponentContext.withComponentScope` is now package-private: it was @VisibleForTesting before, but we are now enforcing the privacy. If you were using it in tests, you can replace it with `ComponentTestHelper.createScopedComponentContextWithStateForTest` (however you should really write tests against LithoTestRule for kt tests and LegacyLithoTestRule for java tests)

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.43.0...v0.44.0).

## Version 0.43.0

_2022-10-21_

 * **Breaking:**: If you use `ComponentsSystrace.provide` to provide a custom Systrace implementation, there have been some changes to the types and methods involved:
   * Instead of implementing `ComponentsSystrace.Systrace`, implementations should implement `com.facebook.rendercore.Systracer`, located in litho-rendercore. The `ArgsBuilder` interface has also moved from ComponentsSystrace to the Systracer interface.
   * Some method names have been corrected. Specifically start/endSectionAsync have become start/endAsyncSection, aligning them to the Tracer API.
 * We have implemented a mechanism that allows us to batch state updates with a deterministic approach. This mechanism enqueues state updates until the next Choreographer Frame Callback (precisely the next "anim" step) and only then schedules the following layout calculation.
For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.42.0...v0.43.0).

## Version 0.42.0

_2022-08-15_

 * **Breaking:** There are some changes to how we store `ComponentContext` and `HasEventDispatcher` on `EventHandler` - these changes only affect manually-constructed EventHandlers and will present as a compile error. They don't affect ones generated by the annotation processor for @OnEvent:
   * Previously the `ComponentContext` was stored as param[0]. It's now stored on `eventDispatchInfo.componentContext` and all other params should be shifted by one (so index 1 is now index 0) - **you only need to do anything here if you were manually constructing EventHandlers - generated EventHandlers from @OnEvent are automatically updated**.
   * Previously the `HasEventDispatcher` was stored directly on the EventHandler. It's not stored at `eventDispatchInfo.hasEventDispatcher`. This is also automatically updated for generated EventHandlers.
 * **Breaking:** `com.facebook.litho.widget.EmptyComponentSpec` is removed. Construct a `com.facebook.litho.EmptyComponent` directly. In places that require a Builder, use `Wrapper.create(<context>).delegate(EmptyComponent())`
 * **Breaking:** `PoolableContentProvider` renamed to `ContentAllocator` in RenderCore. `RenderUnit` no longer implements `PoolableContentProvider`, instead it should return `ContentAllocator` implementation from `getContentAllocator` method.
 * **Breaking:** `StateHandler` has now been replaced by `TreeState` in `ComponentTree` for all state handling. Use `ComponentTree.acquireTreeState()` and `ComponentTree.Builder.treeState(...)` to save/restore state across different component trees.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.41.2...v0.42.0).

## Version 0.41.2

_2022-04-19_

 * Refactor: Rename `setRootAndSizeSpec` to `setRootAndSizeSpecSync` in `ComponentTree`.
 * New: Foreground color is supported as a common `DynamicValue`.
 * **Breaking:** Fix: More fully support `@PropDefault` annotations in Kotlin Specs.  (Results in `@field:PropDefault` failing at compilation time.  Use [fastmod](https://github.com/facebookincubator/fastmod) on existing codebase with the command: `fastmod '@field:PropDefault' '@PropDefault' --dir .`)
 * **Breaking:** `InternalNode` renamed to `LithoNode`, and elevated to a concrete class. Deletes `DefaultInternalNode`.
 * **Breaking:** `NodeInfo` is now a concrete class; deleted `DefaultNodeInfo`.
 * Experimental Kotlin Mountable Component API added.
 * Refactored RenderCore MountState extensions call order.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.41.1...v0.41.2).


## Version 0.41.1

_2021-11-23_

 * **Breaking:** Rename 'LithoAssertions' to 'LegacyLithoAssertions'. (Use [fastmod](https://github.com/facebookincubator/fastmod) on existing codebase with the command: `fastmod 'LithoAssertions' 'LegacyLithoAssertions' --dir .`)
 * Upgrade: Bump Yoga version to `1.19.0`.


For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.41.0...v0.41.1).


## Version 0.41.0

_2021-11-18_

 * **Breaking:** Delete `@FromBind` inter stage prop. **Replace existing usages with `@State AtomicReference<?>` instead.** Create a `@State AtomicReference<?>` for the `Component`; set that value for the `AtomicReference` in `@OnBind`, and get from it in `@OnUnbind` or other lifecycle methods.
 * **Breaking:** Add `ComponentTree` in `Handle` so that `Handle` can be used across component trees, i.e. throughout Sections. **Remove static references of `Handle` as that can lead to memory leaks** since it holds reference to `ComponentTree` now, **instead `Handle` should be used via `@State` in Spec API or `useState` in Kotlin API**.
 * **Breaking:** Remove `@OnShouldCreateLayoutWithNewSizeSpec` API. We hope to provide replacements for it in the future, please let us know if you were relying on it.
 * **Breaking:** Add new `ComponentTree` parameter to `ErrorEventHandler.onError()` method.
 * **Breaking:** Add UI thread call assertion to `ComponentTree.release()` method.
 * **Breaking:** Make `getErrorHandler`, `getHandle`, `getId`, and `getKey` package-private for `Component` and `Section`. This is for compatibility with the Kotlin API.
 * **Breaking:** Make most `Component`/`ComponentLifecycle` non-lifecycle methods (e.g. `onCreateLayout`, `onMount`, etc) final as they are not meant to be overridden.
 * **Breaking:** Remove `checkNeedsRemeasure`, `useVisibilityExtension`, `useInternalNodesForLayoutDiffing`, `hostHasOverlappingRendering`, `inheritPriorityFromUiThread`, `interruptUseCurrentLayoutSource`, `ignoreDuplicateTransitionKeysInLayout`, `onlyProcessAutogeneratedTransitionIdsWhenNecessary`, `ignoreStateUpdatesForScreenshotTest`, `computeRangeOnSyncLayout` and `threadPoolForBackgroundThreadsConfig` configuration parameters from `ComponentsConfiguration`. These configs were used for experimentation and respective experiments were successfully shipped and therefore they are no longer needed.
 * **Breaking:** Remove stale `ThreadPoolDynamicPriorityLayoutHandler` and `LayoutPriorityThreadPoolExecutor` classes.
 * **Breaking:** Rename `LithoHandler` to `RunnableHandler` and `DefaultLithoHandler` to `DefaultHandler`.
 * **Breaking:** Move `RunnableHandler`, `FutureInstrumenter`, and `HandlerInstrumenter` to core RenderCore artifact.
 * **Breaking:** `Component` and `ComponentLifecycle` are now merged as one class (`Component`). `ComponentLifecycle` is now removed. Anywhere `ComponentLifecycle` was directly referenced should be changed to `Component`. Generated components now extend `SpecGeneratedComponent` which extends `Component`.
   * `onCreateLayout`/`onCreateLayoutWithSizeSpec` methods have been moved to `SpecGeneratedComponent`
   * Direct subclasses of `Component` should implement `render` method instead
  * New: Almost all lifecycle methods are now covered by the `@OnError` lifecycle API. It's encouraged that high-level Specs implement `@OnError` callbacks in order to gracefully handle errors that may arise in their descendant Specs.
  * New: Allow passing `@TreeProp` to `@OnCalculateCachedValue` methods

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.40.0...v0.41.0).


## Version 0.40.0

_2021-02-26_

* **Breaking:** Change the return type of `ComponentLifecycle.resolve()` from `ComponentLayout` to `InternalNode`.
* New: Expose `visibleTop` and `visibleLeft` fields from the `VisibilityChangedEvent` to better understand which side of the component is hidden. Check out `VisibilityChangedEvent`'s javadoc for more info.
* New: Expose mounted content from the `VisibleEvent`.
* New: Lifecycle arguments are now optional in the spec. (e.g. `ComponentContext` is now optional in `@OnCreateInitialState`)

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.39.0...v0.40.0).


## Version 0.39.0

_2020-10-07_

* **Breaking:** `Component.getScopedContext()` access changed from public to package-private.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.38.0...v.39.0).


## Version 0.38.0

_2020-09-17_

* New: `ComponentLifecycle.dispatchErrorEvent(ComponentContext, Exception)` has become deprecated for public use. Instead, use `ComponentUtils.raise(ComponentContext, Exception)`.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.37.1...v0.38.0).


## Version 0.37.1

_2020-07-24_

* **Breaking:** Ignore mount calls after `setVisibilityHint(false)` was called on a LithoView until `setVisibilityHint(true)` is called. For more details see the docs about [changing LithoView visibility](https://fblitho.com/docs/visibility-handling#changing-lithoview-visibility).
* New: Add `LithoGestureDetector` wrapper class that ensures gestures are processed on UI thread.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.37.0...v0.37.1).


## Version 0.37.0

_2020-07-07_

* **Breaking: `TransparencyEnabledCard` is deprecated** Moved the behavior of `TransparencyEnabledCard` into `Card` when the prop `transparencyEnabled` is `true`. Please migrate your current uses because it will be removed in a few releases.
* New: Add `.duplicateChildrenStates(boolean)` method to `Component` which passes the flag to [`ViewGroup#setAddStatesFromChildren(boolean)`](https://developer.android.com/reference/android/view/ViewGroup#setAddStatesFromChildren(boolean)). When this flag is set to true, the component applies all of its children's drawable states (focused, pressed, etc.) to itself.
* New: Ability to specify the percentage of Component's width/height which should be visible to trigger Visible events. Read more in [the documentation](https://fblitho.com/docs/visibility-handling#custom-visibility-percentage).
* Fix: Fix showing vertical scrollbar with `VerticalScrollSpec`. Default behaviour is scrollbars disabled.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.36.0...v0.37.0).


## Version 0.36.0

_2020-06-01_

* **Breaking:** Remove unused obsolete `RenderThreadTransition`.
* **Breaking:** Ordering of params in `@ShouldUpdate` callback is fixed (`getNext()`  was previous and `getPrevious()` was next).
* **Breaking:** Rename `ComponentsTestRunner` to `LithoTestRunner`.
* New: Add `TransitionEndEvent` event callback to receive events when a transition ends. Read more about it in [the documentation](https://fblitho.com/docs/transition-choreography#transition-end-callback).
* New: Add `acquireStateHandlerOnRelease` flag for `RecyclerBinder` to opt out of caching `StateHandler`s.
* New: Improved testing APIs: `MountSpecLifecycleTester`, `LifecycleTracker` to track basic lifecycle methods which would replace custom component implementations like `TestDrawable`, `TestComponent`, etc.
* Fix: Ensure `@OnAttached` and `@OnDetached` methods are called in the same order.
* Fix: Remove overriding `isLayoutRequested()` in `SectionsRecyclerView`.
* Fix: Deprecate and ignore `ShouldUpdate#onMount` param. `MountSpec`s with `pureRender` will now always check `shouldUpdate` on the main thread if the information from layout isn't able to be used.
* Fix: Fixup `BackgroundLayoutLooperRule` and improve threading APIs in `ComponentTreeTest`.
* Fix: Fix incorrect key generation after shallow copy.
* Fix: Remove host invalidation suppression during mount.
* Fix: Move setting `PTRRefreshEvent` from `onPrepare` to `onBind` in `RecyclerSpec`.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.35.0...v0.36.0).


## Version 0.35.0

_2020-05-01_

* **Experimental: Process Visibility events without Incremental Mount turned on.** If you used Incremental Mount only for Visibility events before, now you can turn it off!
* **Breaking: `LayoutInfo` implementations are required to implement `scrollToPositionWithOffset()`.** To avoid special casing for `LinearLayoutManager` and `StaggeredGridLayoutManager` in `RecyclerBinder` and other internal logic, a LayoutInfo implementation must now delegate to the underlying LayoutManager's scrolling by implementing `scrollToPositionWithOffset()`. Typical cases should call the LayoutManager's own implementation of `scrollToPositionWithOffset()`, or an equivalent. This creates a common interface for programmatic scrolling [a61e409](https://github.com/facebook/litho/commit/a61e409558f82a115d9c21d91c18d5cc8396d385).
* **Breaking: `MeasureListener` now takes two extra parameters**, `layoutVersion` and `stateUpdate`. These are safe to ignore for clients that don't need them. Check [javadoc](https://fblitho.com/javadoc/com/facebook/litho/ComponentTree.MeasureListener.html#onSetRootAndSizeSpec-int-int-int-boolean-) for all the relevant information.
* **Breaking:** Rename `LithoView.performIncrementalMount()` method to `LithoView.notifyVisibleBoundsChanged()`.
* **Breaking:** Rename `getShadowHorizontal()` to `getShadowLeft()` in `CardShadowDrawable`.
* **New:** `@OnCreateInitialState` method in Specs is now guaranteed to be called only once.
* New: Add ability to customize `shadowDx`/`shadowDy` offsets for `CardShadowDrawable` and `CardShadowSpec`.
* New: Add more [Animations](https://github.com/facebook/litho/tree/master/sample/src/main/java/com/facebook/samples/litho/animations/commondynamicprops) [examples](https://github.com/facebook/litho/tree/master/sample/src/main/java/com/facebook/samples/litho/animations/transitions) and Animations [Cookbook](https://github.com/facebook/litho/tree/master/sample/src/main/java/com/facebook/samples/litho/animations/animationcookbook) in the sample app. Check out [docs for more info](https://fblitho.com/docs/dynamic-props#animating-common-dynamic-props).
* New: Update accessibility utils to support newer version of Talkback.
* New: Replace Litho's `MountItem` with RenderCore's and wrap `LayoutOutput` with `RenderTreeNode`.
* New: Litho tests are now migrated to Robolectric 4 and Mockito 2!
* New: New testing utilities: `LithoTestRule`, `LithoStatsRule`, `BackgroundLayoutLooperRule`.
* Fix: Remove 1px white margin between content and shadow for `CardSpec` [00f2bdb](https://github.com/facebook/litho/commit/00f2bdb852a7e0ded29494b909f0c65d1c7c7dc8).
* Fix: Fix Sections not updating layout for sticky items with indices outside range ratio.
* Fix: Fix `RecyclerSpec` not respecting RTL for padding.
* Fix: Add missing `@Nullable` to every method accepting `EventHandler`.
* Fix: Propagate class-level annotations from `SectionSpec`s class to generated Section class.
* Fix: Don't crash on missing `@OnCreateMountContent` method for `MountSpec`s during code generation.
* Fix: Don't crash when comparing `ComparableGradientDrawable`s on API<=15.
* Fix: Fixes a bug in `ComponentUtils.isEquivalentTo()`.
* Fix: Correctly release `ComponentTree` on the main thread after `@OnDetached`.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.34.0...v0.35.0).


## Version 0.34.0

_2020-02-09_

* **Breaking: Reconciliation for state updates is enabled by default**. Reconciliation makes state updates faster at the expense of increase in memory usage. New APIs added to explicitly disable reconciliation when an explicit `ComponentTree` is not set on the `LithoView`. Read more about it in [the documentation](http://fblitho.com/docs/reconciliation).
  - `LithoView.create(Context, Component, boolean)`
  - `LithoView.create(ComponentContext, Component, boolean)`
  - `LithoView.setComponentWithoutReconciliation(Component)`
  - `LithoView.setComponentAsyncWithoutReconciliation(Component)`
* **Breaking:** Merge `BaseLithoStartupLogging` abstract class, `LithoStartupLoggerUtil` helper class and `LithoStartupLogger` interface into single `LithoStartupLogger` abstract class.
* **Breaking:** Consolidate two layout calculation `PerfEvent`s into one: remove `FrameworkLogEvents.EVENT_LAYOUT_CALCULATE` and move some of its annotations to `FrameworkLogEvents.CALCULATE_LAYOUT_STATE` which will be used instead.
* **Breaking:** Make `varArg` props effectively optional with a `Collections.EMPTY_LIST` as a default value.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.33.0...v0.34.0).


## Version 0.33.0

_2019-12-11_

* **Breaking: Changes in working with `ComparableDrawable`**. Litho's `DrawableWrapper` doesn't mimic Android Jetpack's implementations of `WrappedDrawable`, e.g. `WrappedDrawableApi21`, etc. Litho's wrapper will have to implement this correctly to have a legitimate chance of working across OS versions and all drawable types. This is not a good position to be in. It is better to remove the wrapper all together; this change doesn't remove it, but blocks usages except for specific internal ones. In essence, if a background or foreground `Drawable` is also a `ComparableDrawable`, Litho will invoke the `isEquivalentTo()` for comparison (instead of `equals()`). Also, `ComparableDrawable` is now an optional interface; so non comparable drawables will not be wrapped. The usage remains largely unchanged (except for the removal of Litho's `DrawableWrapper` implementation).
  - `ComparableDrawable` is a plain interface now (instead of a `Drawable`).
  - Remove `Component.Builder.background(ComparableDrawable)` and `Component.Builder.foreground(ComparableDrawable)`.
  - Remove `ComparableResDrawable`, `ComparableIntIdDrawable` and `DefaultComparableDrawable` implementations.
* **Breaking:** Provide global offset of the Section into `@OnDataRendered` method.
* **Breaking:** Fix default text size of `TextSpec`, `EditTextSpec` and `TextInputSpec` to be 14sp (from 13px).
* **Breaking:** FBJNI got removed from the build process. If you relied on `libfbjni.so` to be present, you can get the artifact from [the fbjni repository](https://github.com/facebookincubator/fbjni).
* **Breaking:** `LithoViewAssert.hasVisibleDrawable()` no longer relies on the broken `ShadowDrawable.equals()` implementation in Robolectric 3.X. Now Drawable equality relies on the descriptions being equal, or the resource ID they were created with being equal.
* **Breaking:** Remove unused `getKeyCollisionStackTraceBlacklist()` and `getKeyCollisionStackTraceKeywords()` from the `ComponentsReporter.Reporter` interface.
* New: Allow triggering `@OnTrigger` Events on Components using a `Handle` API.
* Fix: Propagate annotations specified on `@Param` args from `@OnEvent` methods to generated methods.
* Fix: Produce correct generated code for `@OnEvent` method when it has several args of the same generic type.
* Fix: Fix generating `@OnCalculateCachedValue` related methods when it has `ComponentContext` as a parameter.
* Fix: Fix `IndexOutOfBoundsException` in `RecyclerBinder.removeItemAt()` in Sections when `SingleComponentSection` is given a `null` Component.
* Fix: Support Dynamic Props for `LayoutSpec`s.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.32.0...v0.33.0).


## Version 0.32.0

_2019-11-15_

 * **Breaking:** Make ctors of `Component`/`ComponentLifecycle` that take an explicit type id package private.
 * **Breaking:** Add `categoryKey` param for `ComponentsReporter.emitMessage()` API to distribute errors into different buckets.
 * **Breaking:** Remove `ComponentsLogger.emitMessage()` API as it was fully replaced by `ComponentsReporter.emitMessage()`.
 * **Breaking:** Remove `YogaNode` parameter from `YogaLogger.log()`.
 * **Breaking:** Remove error reporting from `ComponentsLogger`.
 * **Breaking:** Limit scope of `Component`/`ComponentLifecycle` constructors that take explicit type param.
 * New: Add `requestSmoothScrollBy()` and `requestScrollToPositionWithSnap()` APIs for `RecyclerCollectionEventsController`.
 * New: Add ability to provide custom `ComponentsLogger` per `ComponentRenderInfo`.
 * New: Add new counters (calculateLayout, section state update, section changeset calculation) to `LithoStats` global counter.
 * New: Allow custom `StaggeredGridLayoutInfo` when using `StaggeredGridRecyclerConfiguration`.
 * New: Add support for more `textAlignment` values for `TextSpec`.
 * New: Add `ComponentWarmer` API to allow calculating layout ahead of time.
 * New: Add `ThreadPoolDynamicPriorityLayoutHandler` to enable changing priority of threads calculating layouts.
 * New: Add `varArgs` to the generated `Component`.
 * New: Add snap support for `GridRecyclerConfiguration`.
 * New: Add support for custom fling offset for `StartSnapHelper`.
 * New: Allow disabling top or bottom shadow in `TransparencyEnabledCardSpec`.
 * New: Add `Handle` API for Litho `Tooltip`s via `LithoTooltipController.showTooltipOnHandle()` that replaces previous way of anchoring tooltip with concatenated keys.
 * New: Track component hierarchy using `DebugHierarchy` after mount time.
 * Fix: Add generics support to `@OnCalculateCachedValue` methods.
 * Fix: Move setting `ItemAnimator` from `onBind`/`onUnbind` to `onMount`/`onUnmount` in `RecyclerSpec`.
 * Fix: Stop and clean running transitions that do not exist and not declared in the new layout.
 * Fix: Fix concurrent modification on finishing undeclared transitions.
 * Fix: Allow `TreeProp`s to be used in `@OnCreateInitialState` of Sections.
 * Fix: Define default color for spannable link in `TextSpec`.
 * Fix: Postpone `ComponentTree.mountComponent()` for reentrant mounts, then mount new `LayoutState` afterwards.
 * Fix: Enable automatic RTL support in sample apps.

For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.31.0...v0.32.0).


## Version 0.31.0

_2019-09-09_

 * **Breaking:** `Component.measure()` is only allowed during a `LayoutState` calculation.
 * New: Add support to `FrescoImageSpec` for photo focus points.
 * New: Allow Child Classes to set `ComponentContext` on `DefaultInternalNode`.
 * Fix: Immediately remove `MountItem` mapping on unmount to protect against re-entrancy.

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.30.0...v0.31.0).


## Version 0.30.0

_2019-08-23_

 * **Breaking:** Rename `@FromCreateLayout` to `@FromPreviousCreateLayout`.
 * **Breaking:** Add compile-time error when `Component.Builder` is passed as a `@Prop`. ([5da7121](https://github.com/facebook/litho/commit/5da712120243ec3419ecc70b9806bde9536d2251))
 * **Breaking:** Remove `MountSpec.shouldUseDisplayList()` - remnant of removed DisplayLists' usage. Was not doing anything.
 * New: Make `DynamicValue.get()` public.
 * New: Expose `RecyclerBinder`'s Commit Policy through `DynamicConfig`. ([ac513f3](https://github.com/facebook/litho/commit/ac513f32c0cb9da4b0ce26426669f78c02727446))
 * New: Allow to provide custom `GridLayoutInfo` (i.e. custom `GridLayoutManager`) through `GridLayoutInfoFactory`. ([4568d58](https://github.com/facebook/litho/commit/4568d581249a05c32397565300cc000a1a2f1011))
 * New: Allow creating `ComponentTree` without specifying root.
 * New: Deprecate `ComponentsLogger.emitMessage()` in favor of `ComponentsReporter.Reporter.emitMessage()`. ([9cb4caf](https://github.com/facebook/litho/commit/9cb4cafb1ffb03dedfe6698bc24ccdef48f0a9a1))
 * New: Auto set `Text.ellipsize()` if `maxLines()` is specified without an accompanying ellipsize, to make behavior consistent across different Android versions. ([3bef059](https://github.com/facebook/litho/commit/3bef059cef217e1820c9bd8a3a9f7cb8364abfa1))
 * New: Share the same `ResourceResolver` across all `Component`s in the same tree. ([c93517c](https://github.com/facebook/litho/commit/c93517c6fed1d05e186e4d9b71de19c9fb26830c), [7656822](https://github.com/facebook/litho/commit/7656822c8dc1d565520bb26111ebbd20ba74ca19), [dde30dc](https://github.com/facebook/litho/commit/dde30dc0fa1a7d1617f90129651d0801c0079299))
 * New: Add support for A11y headers. ([#573](https://github.com/facebook/litho/pull/573))
 * New: Update documentation and javadocs.
 * Fix: Better error messages for releasing mount content.
 * Fix: Don't use internal `javac` API in codegen. ([#577](https://github.com/facebook/litho/pull/577))
 * Fix: Propagate injected `treeProps` for Layout PerfEvent. ([#574](https://github.com/facebook/litho/pull/574))
 * Fix: Don't crash when using primitive `@CachedValue`s together with HotSwap mode. ([501f1a1](https://github.com/facebook/litho/commit/501f1a171e3df30bf40330b9eaffbb13f61cc007))
 * Fix: Improve `@OnUpdateStateWithTransition`'s behavior.

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.29.0...v0.30.0).


## Version 0.29.0

_2019-07-11_

 * New: Additional Sections debugging APIs:
   - Make `Change.getRenderInfos()` public.
   - Add `ChangesInfo.getAllChanges()`.
 * Fix: Don't crash on dangling mount content.

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.28.0...v0.29.0).


## Version 0.28.0

_2019-07-05_

 * New: Plain code Codelabs with README instructions. Try them out in [codelabs](codelabs).
 * New: Add interface `ChangesetDebugConfiguration.ChangesetDebugListener` for listening for `ChangeSet` generation in Sections.
 * Fix: Cleanup some unused code.

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.27.0...v0.28.0).


## Version 0.27.0

_2019-06-24_

 * **Breaking: Change in PerfEvents**:
   - Remove `FrameworkLogEvents.EVENT_CREATE_LAYOUT`, `FrameworkLogEvents.EVENT_CSS_LAYOUT `, and `FrameworkLogEvents.EVENT_COLLECT_RESULTS`: these are replaced by the sub-spans `"start_create_layout"`/`"end_create_layout"`, `"start_measure"`/`"end_measure"`, and `"start_collect_results"`/`"end_collect_results"` under the existing top-level `EVENT_CALCULATE_LAYOUT_STATE` event. The `PerfEvent.markerPoint()` API can be used to log these sub-spans. ([b859605](https://github.com/facebook/litho/commit/b859605258da6431b706d17c07df7bc8864396df))
   - Remove `FrameworkLogEvents.PREPARE_MOUNT` without replacement: this didn't provide much value. ([4917370](https://github.com/facebook/litho/commit/4917370d6a7405cddce01c13740f22e9ee736529))
   - Remove `FrameworkLogEvents.DRAW` without replacement: this was not free to maintain and didn't provide much value. ([9e548cb](https://github.com/facebook/litho/commit/9e548cbda5ad78d6f8d3a82ec42639439c118751))
 * **Breaking: The Default Range Ratio** for Sections/`RecyclerBinder` is changed from 4 screens worth of content in either direction to 2. This should improve resource usage with minimal effects on scroll performance. ([9b4fe95](https://github.com/facebook/litho/commit/9b4fe95b8cd48a15046b0d39c4f4756e50f35772))
 * **Breaking: `ComponentsSystrace.provide()`**: `ComponentsSystrace` now assumes an implementation will be provided before any other Litho operations occur. ([457a20f](https://github.com/facebook/litho/commit/457a20f660f14e7132e16668c21b4cf1ce766b70))
 * New: `ComponentsLogger` implementations can now return null for event types they don't care about. ([4075eb7](https://github.com/facebook/litho/commit/4075eb75c9a6f967111b451df6699d1b9b97671b))
 * New: Add `RecyclerCollectionEventsController.requestScrollBy()`. ([0146857](https://github.com/facebook/litho/commit/0146857653e29cb24491cbb8bee9307e55187365))
 * New: Add preliminary Robolectric v4 support. ([4c2f657](https://github.com/facebook/litho/commit/4c2f657f9e6edc45f78b9a8d82085025fa0fc86d), etc.)
 * New: More efficient code generation for state updates in Components and Sections. ([8c5c7e3](https://github.com/facebook/litho/commit/8c5c7e312fb7d1855caabc2fccbf5adc57e6e945), etc.)
 * Fix: Remove usage of API 19+ `Objects` class in cached value API. ([aabb24a](https://github.com/facebook/litho/commit/aabb24a5e67d30e5b93eb43f6c37aae91f455369))
 * Fix: Unset Components scope when creating a new `ComponentContext` in `ComponentTree`. ([05f11a7](https://github.com/facebook/litho/commit/05f11a74a4a06b4abcb1e302f56201609acccad2))
 * Fix: Fix perf logging for dirty mounts. ([3ad8bfb](https://github.com/facebook/litho/commit/3ad8bfba12a43e108602bcd8c63fd2404e6203cb))
 * Fix: Don't crash when `@OnCalculateCachedValue` takes no args. ([2a0f524](https://github.com/facebook/litho/commit/2a0f5240bc8c711d8e45db0e5426517f8bfbf49a))
 * Fix: Reduce number of systrace markers in `collectResults`: these were skewing the perceived size of `LayoutState.collectResults` in production and weren't actionable. ([3107467](https://github.com/facebook/litho/commit/3107467a4e3fd649821757699a5adba683f47edd))

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.26.0...v0.27.0).


## Version 0.26.1

_2019-05-28_

* Fix: Picks [513cf91](https://github.com/facebook/litho/commit/513cf91b747bdf06c6bda78840a9195e900d4deb) to fix an issue with the Flipper integration.


## Version 0.26.0

_2019-05-13_

 * **Breaking: Fix the [lazy State update](https://fblitho.com/docs/state#lazy-state-updates) semantics.** ([de3d938](https://github.com/facebook/litho/commit/de3d938c7db32739468617d3b22e5a4568cfc6a5))
 * **Breaking:** Rename `LayoutHandler` to `LithoHandler` and add `DefaultLithoHandler`. ([h69cba5](https://github.com/facebook/litho/commit/h69cba5029c186a4fa81f91c15b44846bc9d79e5c), [0d0bb0b](https://github.com/facebook/litho/commit/0d0bb0b196e4035976cb31c307aee95fd9433a27))
 * **Breaking:** Update Yoga version to `1.14.0`. Fixes [#536](https://github.com/facebook/litho/pull/536). ([c16baf6](https://github.com/facebook/litho/commit/c16baf676f59df99de53cea50e5efa0cb9ddeb0e))
 * **Breaking:** Release sections' `ComponentTree`s when `RecyclerCollectionComponent` is detached. ([8893049](https://github.com/facebook/litho/commit/88930499b19c01953dead8d37e4fed7c1d161ca1))
 * **Breaking:** Only enable incremental mount if parent incremental mount is enabled. ([c88a660](https://github.com/facebook/litho/commit/c88a6605d8e77378e0ac89b267dcb260daddc6f4))
 * **Experimental: Make state updates faster by only recreating the subtrees which are being updated and reuse (read "clone") the untouched subtrees while calculating a new layout.** You can try it out by setting `ComponentsConfiguration.isReconciliationEnabled()` flag globally or using `ComponentTree.Builder.isReconciliationEnabled()` for specific trees.
 * New: Add a `RecyclerBinder.replaceAll(List<RenderInfo>)` method. ([#451](https://github.com/facebook/litho/pull/451))
 * New: Add documentation.
 * Fix: Eliminate Gradle deprecated methods. ([#526](https://github.com/facebook/litho/pull/526))
 * Fix: Cleanup tests and unused code.
 * Fix: Remove object pooling everywhere.
 * Fix: Make Robolectric tests work. ([a92018a](https://github.com/facebook/litho/commit/a92018a32d5241ace4d27f4120908595fb26b51a))

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.25.0...v0.26.0).


## Version 0.25.0

_2019-03-14_

 * **Breaking:** Migrate support lib dependencies to AndroidX. ([de3097b](https://github.com/facebook/litho/commit/de3097b5c7e2bc9ede24ea8c5e8e863e67387039))
 * **Breaking:** Remove `DisplayListDrawable` and other `DisplayList`-related features. ([29f42fa](https://github.com/facebook/litho/commit/29f42fa04dfdff31fc82dd8f199ffc8c62ef5dbf))
 * **Breaking:** Remove `References` API. ([b1aa39a](https://github.com/facebook/litho/commit/b1aa39a460da25fbf29a80f727007a7e1f267440))
 * New: Remove object pooling for most internal objects.
 * New: Replace `powermock-reflect` with internal Whitebox implementation. ([ad899e4](https://github.com/facebook/litho/commit/ad899e4ae2c4217d751a75e82c0646d70b5837a3))
 * New: Enable Gradle incremental AnnotationProcessing. ([a864b5a](https://github.com/facebook/litho/commit/a864b5a642214d1d81c7422d3d4b2d0ada510625))
 * New: Allow `TextInput` to accept `null` as `inputBackground`. ([d1fd03b](https://github.com/facebook/litho/commit/d1fd03b4071794f55fc1617cf365f8a5f2a32558))
 * New: Add documentation.
 * Fix: Suppress focus temporarily while mounting. ([d93e2e0](https://github.com/facebook/litho/commit/d93e2e073a88b86b2674119bd1813a6c982622bf))
 * Fix: When clearing press state, also cancel pending input events. ([451e8b4](https://github.com/facebook/litho/commit/451e8b4a9c5b7744a6cf5b7702754fc0afe025a0))
 * Fix: Correctly calculate `VisibilityChangedEvent` in `MountState`. ([66d65fe](https://github.com/facebook/litho/commit/66d65feb39ed86450ea74891f9211fd6fda6ffc8))
 * Fix: Thread safety issue of `ViewportChanged` listeners of `ViewportManager`. ([9da9d90](https://github.com/facebook/litho/commit/9da9d903c22d0b10d823d509da18c08eb2b2f875))

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.24.0...v0.25.0).


## Version 0.24.0

_2019-02-18_

 **Breaking: The default for state updates has changed from sync to async.** At Facebook this meant we ran a codemod script on the codebase and changed all state update methods to the explicit Sync variant (`"Sync"` is just appended to the end of the state update call), then changed the default. The reason for this is to not change existing behavior in case it breaks something, since there are many callsites. The script is committed and available at [scripts/codemod-state-update-methods.sh](scripts/codemod-state-update-methods.sh). We recommend using it if you have any concerns about state update calls automatically becoming async. As a reminder, when you add a `@OnUpdateState` method, it generates three methods: `updateState()`, `updateStateSync()`, and `updateStateAsync()`. Previously `updateState() == updateStateSync()`. Now, `updateState() == updateStateAsync()`.

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.23.0...v0.24.0).


## Version 0.23.0

_2019-01-16_

 * **Breaking:** `KeyHandler`s now get registered in EndToEnd tests ([9836497](https://github.com/facebook/litho/commit/9836497c15986ebafbfc2b87b1b4a9730fc73080)). This is a an edge-case, but potentially behavior-changing.
 * New: **Add support for [Cached Values](https://fblitho.com/docs/cached-values)**.
 * New: `isEquivalentTo` now uses reflection for size reasons unless the target is a `MountSpec` or `SectionSpec`. ([2e27d99](https://github.com/facebook/litho/commit/2e27d99b0ae75e8356b5654fa412cb66ec965411))
 * Fix: Potential NPE in `RecyclerEventsController`. ([8e29036](https://github.com/facebook/litho/commit/8e29036de52e5108d5e9853abfcb5571b5643e86))

 For more details, see the [full diff](https://github.com/facebook/litho/compare/v0.22.0...v0.23.0).
