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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.ACCESSIBILITY_SERVICE
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.PaintDrawable
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.annotation.Keep
import androidx.annotation.RestrictTo
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.infer.annotation.ThreadConfined
import com.facebook.infer.annotation.ThreadSafe
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.LayoutState.Companion.isFromSyncLayout
import com.facebook.litho.LayoutState.Companion.layoutSourceToString
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState
import com.facebook.litho.RenderSourceUtils.getExecutionMode
import com.facebook.litho.RenderSourceUtils.getSource
import com.facebook.litho.StateContainer.StateUpdate
import com.facebook.litho.ThreadUtils.assertMainThread
import com.facebook.litho.ThreadUtils.isMainThread
import com.facebook.litho.animation.AnimationController
import com.facebook.litho.animation.AnimationFrameInvalidation
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.ComponentsConfiguration.Companion.DEFAULT_BACKGROUND_THREAD_PRIORITY
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.config.PreAllocationHandler
import com.facebook.litho.config.RunnableHandlerProvider
import com.facebook.litho.debug.DebugOverlay
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.debug.LithoDebugEventAttributes.Breadcrumb
import com.facebook.litho.debug.LithoDebugEventAttributes.CurrentHeightSpec
import com.facebook.litho.debug.LithoDebugEventAttributes.CurrentRootId
import com.facebook.litho.debug.LithoDebugEventAttributes.CurrentSizeConstraint
import com.facebook.litho.debug.LithoDebugEventAttributes.CurrentWidthSpec
import com.facebook.litho.debug.LithoDebugEventAttributes.HasMainThreadLayoutState
import com.facebook.litho.debug.LithoDebugEventAttributes.IdMatch
import com.facebook.litho.debug.LithoDebugEventAttributes.Root
import com.facebook.litho.debug.LithoDebugEventAttributes.RootId
import com.facebook.litho.debug.LithoDebugEventAttributes.SizeConstraint
import com.facebook.litho.debug.LithoDebugEventAttributes.SizeSpecsMatch
import com.facebook.litho.debug.getAttribution
import com.facebook.litho.effects.EffectContext
import com.facebook.litho.effects.EffectHandler
import com.facebook.litho.lifecycle.LifecycleOwnerTreeProp
import com.facebook.litho.lifecycle.LifecycleOwnerWrapper
import com.facebook.litho.state.StateId
import com.facebook.litho.state.TreeStateProvider
import com.facebook.litho.state.UiStateReadRecords
import com.facebook.litho.state.UiStateReadRecordsProvider
import com.facebook.litho.state.hasDirtyScopes
import com.facebook.litho.stats.LithoStats
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.MountContentPools
import com.facebook.rendercore.PoolScope
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.RunnableHandler.DefaultHandler
import com.facebook.rendercore.SizeConstraints.Helper.getHeightSpec
import com.facebook.rendercore.SizeConstraints.Helper.getWidthSpec
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventAttribute.Async
import com.facebook.rendercore.debug.DebugEventAttribute.HeightSpec
import com.facebook.rendercore.debug.DebugEventAttribute.Source
import com.facebook.rendercore.debug.DebugEventAttribute.WidthSpec
import com.facebook.rendercore.debug.DebugEventDispatcher
import com.facebook.rendercore.instrumentation.HandlerInstrumenter.instrumentHandler
import com.facebook.rendercore.utils.MeasureSpecUtils.getMeasureSpecDescription
import com.facebook.rendercore.utils.equals
import com.facebook.rendercore.visibility.VisibilityChangeReason
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.annotation.concurrent.GuardedBy

/**
 * Represents a tree of components and controls their life cycle. ComponentTree takes in a single
 * root component and recursively invokes its OnCreateLayout to create a tree of components.
 * ComponentTree is responsible for refreshing the mounted state of a component with new props.
 *
 * The usual use case for [ComponentTree] is:
 * ```
 * ComponentTree component = ComponentTree.create(context, MyComponent.create());
 * myHostView.setRoot(component);
 * ```
 */
@ThreadSafe
open class ComponentTree protected constructor(builder: Builder) :
    LithoVisibilityEventsListener,
    StateUpdater,
    TreeStateProvider,
    UiStateReadRecordsProvider,
    MountedViewReference,
    ErrorComponentReceiver,
    LithoTreeLifecycleProvider {

  @Volatile
  var lithoVisibilityEventsController: LithoVisibilityEventsController? = null
    private set

  @GuardedBy("this") private var released: Boolean = false

  @ThreadConfined(ThreadConfined.UI)
  private var onReleaseListeners: MutableList<LithoTreeLifecycleProvider.OnReleaseListener>? = null

  @get:Synchronized
  private var _releasedComponent: String = ""
    set

  private val accessibilityManager: AccessibilityManager?

  private var inAttach: Boolean = false

  @GuardedBy("this") private var measureListeners: MutableList<MeasureListener>? = null

  private val incrementalMountHelper: IncrementalMountHelper?

  private val stateUpdater: StateUpdater

  private val updateStateSyncRunnableLock = Any()

  @GuardedBy("updateStateSyncRunnableLock")
  private var updateStateSyncRunnable: UpdateStateSyncRunnable? = null

  val context: ComponentContext

  private var preAllocator: ContentPreAllocator? = null

  private val parentLithoSaveableStateRegistry =
      builder.treePropContainer?.get(LithoSaveableStateRegistryTreeProp)

  private val saveableStateKey: String? = builder.saveableStateKey

  private val ownerSaveableStateBinding: ComponentTreeSaveableStateBinding? =
      builder.savedStateRegistryOwner
          ?.takeIf {
            builder.retainedLithoSaveableStateRegistry == null &&
                parentLithoSaveableStateRegistry == null
          }
          ?.let { store ->
            ComponentTreeSaveableStateBinding(
                store,
                builder.lifecycleOwner,
                builder.androidContext.classLoader,
                keyed = builder.saveableStateKey != null,
                rootIdentity = ::saveableRootIdentity,
                hasMounted = ::hasMounted,
                renderRestoredValues = ::renderRestoredSaveableState,
            )
          }

  // Read by saveable-state claims on resolve threads; mirrors the latest root of a tree with an
  // owner binding, and is cleared on release.
  @Volatile
  private var saveableIdentityRoot: Component? =
      builder.root.takeIf { ownerSaveableStateBinding != null }
  private val lithoSaveableStateRegistry =
      builder.retainedLithoSaveableStateRegistry
          ?: parentLithoSaveableStateRegistry?.createChildRegistry(
              builder.nestedTreeSaveableStateKey,
              joinsParentWhenShown = true,
          )
          ?: ownerSaveableStateBinding?.registry
  private val hasRetainedLithoSaveableStateRegistry =
      builder.retainedLithoSaveableStateRegistry != null

  @GuardedBy("this") private var retainedLithoSaveableStateRegistryAcquired = false

  /** The generation of [lithoSaveableStateRegistry] that this tree's layouts may have read. */
  @Volatile private var renderedGeneration: Int = lithoSaveableStateRegistry?.generation ?: 0

  // These variables are only accessed from the main thread.
  @ThreadConfined(ThreadConfined.UI) private var isMeasuring: Boolean = false

  @ThreadConfined(ThreadConfined.UI) private var _isAttached: Boolean = false

  @ThreadConfined(ThreadConfined.UI) internal var _lithoView: LithoView? = null

  @ThreadConfined(ThreadConfined.UI) private var layoutThreadHandler: RunnableHandler? = null

  private var mainThreadHandler: RunnableHandler = DefaultHandler(Looper.getMainLooper())

  private val backgroundLayoutStateUpdateRunnable = Runnable { backgroundLayoutStateUpdated() }

  @Volatile private var hasPromotedFirstLayout: Boolean = false

  @Volatile var newLayoutStateReadyListener: NewLayoutStateReadyListener? = null

  private val currentDoLayoutRunnableLock = Any()

  @GuardedBy("currentDoLayoutRunnableLock")
  private var currentDoLayoutRunnable: DoLayoutRunnable? = null

  @GuardedBy("currentDoLayoutRunnableLock")
  private var currentDoResolveRunnable: DoResolveRunnable? = null

  private val layoutStateFutureLock = Any()

  private val resolveResultFutureLock = Any()

  @GuardedBy("resolveResultFutureLock")
  private val resolveResultFutures: MutableList<ResolveTreeFuture> = ArrayList()

  @GuardedBy("layoutStateFutureLock")
  private val layoutTreeFutures: MutableList<LayoutTreeFuture> = ArrayList()

  // The resolve effects of the committed resolve result, and of the trees resolved while laying out
  // the committed layout.
  private val committedResolveEffects = CommittedEffects()
  private val committedLayoutEffects = CommittedEffects()

  private var futureExecutionListener: TreeFuture.FutureExecutionListener? = null

  @GuardedBy("this") private var _root: Component? = null

  @GuardedBy("this") private var externalRootVersion: Int = INVALID_LAYOUT_VERSION

  // Versioning that gets incremented every time we start a new layout computation. This can
  // be useful for stateful objects shared across layouts that need to check whether for example
  // a measure/onCreateLayout call is being executed in the context of an old layout calculation.
  @GuardedBy("this") private var nextResolveVersion: Int = 0

  @GuardedBy("this") private var nextLayoutVersion: Int = 0

  @GuardedBy("this") private var committedLayoutVersion: Int = INVALID_LAYOUT_VERSION

  @GuardedBy("this") private val parentTreePropContainer: TreePropContainer?

  @GuardedBy("this") private var treePropContainer: TreePropContainer? = null

  @GuardedBy("this") private var implicitTreePropContainer: TreePropContainer? = null

  @GuardedBy("this") private var widthSpec: Int = SIZE_UNINITIALIZED

  @GuardedBy("this") private var heightSpec: Int = SIZE_UNINITIALIZED

  @SuppressLint("NotAccessedPrivateField")
  @GuardedBy("this")
  @RenderSource
  private var lastLayoutSource: Int = RenderSource.NONE

  // This is written to only by the main thread with the lock held, read from the main thread with
  // no lock held, or read from any other thread with the lock held.
  private var _mainThreadLayoutState: LayoutState? = null

  @GuardedBy("this") private var committedLayoutState: LayoutState? = null

  @GuardedBy("this") private var committedResolveResult: ResolveResult? = null

  @GuardedBy("this")
  override var committedTreeState: TreeState? = null
    internal set

  @GuardedBy("this")
  override var uiTreeState: TreeState? = null
    internal set

  val id: Int

  @GuardedBy("this") private val workingRangeStatusHandler = WorkingRangeStatusHandler()

  private var debugLogBreadcrumb: String? = null

  private val batchedStateUpdatesStrategy: BatchedStateUpdatesStrategy =
      PostStateUpdateToChoreographerCallback()

  init {
    _root = builder.root
    id =
        if (builder.overrideComponentTreeId != INVALID_ID) {
          builder.overrideComponentTreeId
        } else {
          LithoTree.generateComponentTreeId()
        }
    val renderUnitIdGenerator: RenderUnitIdGenerator =
        if (builder.renderUnitIdGenerator != null) {
          val generator = builder.renderUnitIdGenerator
          requireNotNull(generator) { "RenderUnitIdGenerator should not be null here" }
          if (id != generator.componentTreeId) {
            throw IllegalStateException(
                "Copying RenderUnitIdGenerator is only allowed if the ComponentTree IDs match",
            )
          }
          generator
        } else {
          RenderUnitIdGenerator(id)
        }

    addMeasureListener(builder.measureListener)

    val androidContext: Context = builder.androidContext

    val config = LithoConfiguration(
        builder.config,
        AnimationsDebug.areTransitionsEnabled(androidContext),
        renderUnitIdGenerator,
    )

    builder.treeState?.clearResolveUpdates()
    committedTreeState = builder.treeState ?: TreeState()
    uiTreeState =
        TreeState(
            builder.treeState,
            EffectHandler(
                useOptimizedContextualEffectHandler =
                    config.componentsConfig.useOptimizedContextualEffectHandler,
            ),
        )

    incrementalMountHelper =
        if (ComponentsConfiguration.USE_INCREMENTAL_MOUNT_HELPER) {
          IncrementalMountHelper(this)
        } else {
          null
        }

    layoutThreadHandler = builder.layoutThreadHandler
    // Instrument LithoHandlers.
    mainThreadHandler = instrumentHandler(mainThreadHandler)
    layoutThreadHandler =
        ensureAndInstrumentLayoutThreadHandler(
            getOrCreateRunnableHandlerProvider(config.componentsConfig),
            layoutThreadHandler,
        )

    stateUpdater = builder.stateUpdater ?: this

    parentTreePropContainer = builder.treePropContainer

    val implicitLifecycleOwner = getImplicitLifecycleOwner(builder.treePropContainer)
    implicitTreePropContainer = createImplicitTreePropContainer(implicitLifecycleOwner)
    if (ComponentsConfiguration.customPoolScopesEnabled || builder.config.usePoolScopes) {
      requireNotNull(implicitTreePropContainer) { "implicitTreePropContainer should not be null" }
          .put(PoolScopeTreeProp, builder.poolScope)
    }

    // This must be called after both parentTreePropContainer and implicitTreePropContainer are
    // initialized.
    treePropContainer = createTreePropContainer(null)
    builder.lifecycleOwner?.let { lifecycleOwner -> setLifecycleOwnerTreeProp(lifecycleOwner) }
    context =
        ComponentContext(
            androidContext,
            null,
            config,
            LithoTree.create(this, stateUpdater, builder.config.enableStateReadTracking),
            StringGlobalKey("root"),
            builder.lithoVisibilityEventsController,
            null,
            null,
        )

    val preAllocationHandler = builder.config.preAllocationHandler
    if (preAllocationHandler != null) {
      val mountContentHandler: RunnableHandler?
      when (preAllocationHandler) {
        is PreAllocationHandler.LayoutThread -> {
          mountContentHandler = DefaultHandler(getDefaultLayoutThreadLooper())
        }
        is PreAllocationHandler.Custom -> {
          mountContentHandler = instrumentHandler(preAllocationHandler.handler)
        }
      }
      if (mountContentHandler != null) {
        preAllocator =
            ContentPreAllocator(
                id,
                context,
                mountContentHandler,
                {
                  val layoutState =
                      synchronized(this) { _mainThreadLayoutState ?: committedLayoutState }
                  layoutState?.mountableOutputs ?: Collections.emptyList()
                },
                MountContentPools::maybePreallocateContent,
            )
      }
    }

    val controller = context.lithoVisibilityEventsController
    if (controller != null) {
      subscribeToVisibilityEventsController(controller)
    }

    accessibilityManager =
        context.androidContext.getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager

    if (isMainThread) {
      ownerSaveableStateBinding?.initializeOnMain()
    }

    val carriedTreeState = builder.treeState
    if (carriedTreeState != null && lithoSaveableStateRegistry?.hasSources == true) {
      lithoSaveableStateRegistry.adoptTree(checkNotNull(context.lithoTree), carriedTreeState)
    }

    ComponentTreeRegistry.register(this)
  }

  /**
   * This method associates this [ComponentTree] debug logs with the given `String` This allows you
   * to create an association of the [ComponentTree] with any identifier or sorts that enables you
   * to trace the logs of this [ComponentTree] with the identifier.
   *
   * This is particularly useful if you have multiple [ComponentTree] and you want to filter the
   * logs according to the given breadcrumb.
   */
  fun setDebugLogsBreadcrumb(breadcrumb: String?) {
    debugLogBreadcrumb = breadcrumb
  }

  /**
   * The provided measureListener will be called when a valid layout is committed.
   *
   * @param measureListener
   */
  fun addMeasureListener(measureListener: MeasureListener?) {
    if (measureListener == null) {
      return
    }

    synchronized(this) {
      if (measureListeners == null) {
        measureListeners = ArrayList()
      }
      measureListeners?.add(measureListener)
    }
  }

  fun clearMeasureListener(measureListener: MeasureListener?) {
    if (measureListener == null) {
      return
    }

    synchronized(this) { measureListeners?.remove(measureListener) }
  }

  /**
   * Kotlin property alias for [_mainThreadLayoutState].
   *
   * This property is hidden from Java callers via [@JvmSynthetic], so Java code will continue to
   * use [getMainThreadLayoutState]. The [@JvmName] annotation provides a unique JVM name to avoid
   * conflicts with the existing getter method. Kotlin callers can use this property directly (e.g.,
   * `componentTree.mainThreadLayoutState`).
   *
   * NOTE: This getter delegates to [getMainThreadLayoutState] to ensure that mocking the method in
   * tests also affects the Kotlin property accessor.
   */
  @Suppress("FieldsBelowInit")
  val mainThreadLayoutState: LayoutState?
    @JvmSynthetic
    @JvmName("mainThreadLayoutStateProperty")
    @ThreadConfined(ThreadConfined.UI)
    get() = getMainThreadLayoutState()

  /**
   * Kotlin property alias for [_lithoView].
   *
   * This property is hidden from Java callers via [@JvmSynthetic], so Java code will continue to
   * use [getLithoView]. The [@JvmName] annotation provides a unique JVM name to avoid conflicts
   * with the existing getter method. Kotlin callers can use this property directly (e.g.,
   * `componentTree.lithoView`).
   *
   * NOTE: This getter delegates to [getLithoView] to ensure that mocking the method in tests also
   * affects the Kotlin property accessor.
   */
  @Suppress("FieldsBelowInit")
  val lithoView: LithoView?
    @JvmSynthetic
    @JvmName("lithoViewProperty")
    @ThreadConfined(ThreadConfined.UI)
    get() = getLithoView()

  @ThreadConfined(ThreadConfined.UI)
  fun getMainThreadLayoutState(): LayoutState? {
    return _mainThreadLayoutState
  }

  @VisibleForTesting(otherwise = VisibleForTesting.NONE)
  @GuardedBy("this")
  fun getCommittedLayoutState(): LayoutState? {
    return committedLayoutState
  }

  /** Whether this ComponentTree has been mounted at least once. */
  fun hasMounted(): Boolean {
    val treeState = uiTreeState ?: return false
    return treeState.mountInfo.hasMounted
  }

  @Suppress("FieldsBelowInit")
  override var isFirstMount: Boolean
    get() {
      val treeState = uiTreeState ?: return false
      return treeState.mountInfo.isFirstMount
    }
    set(isFirstMount) {
      val treeState = uiTreeState ?: return
      treeState.mountInfo.isFirstMount = isFirstMount
    }

  override fun <T> canSkipStateUpdate(
      stateId: StateId,
      newValue: T?,
      isLayoutState: Boolean,
  ): Boolean {
    val treeState = getCommittedTreeStateSync() ?: return false
    return treeState.canSkipStateUpdate(stateId, newValue, isLayoutState)
  }

  override fun <T> canSkipStateUpdate(
      newValueFunction: (T) -> T,
      stateId: StateId,
      isLayoutState: Boolean,
  ): Boolean {
    val treeState = getCommittedTreeStateSync() ?: return false
    return treeState.canSkipStateUpdate(newValueFunction, stateId, isLayoutState)
  }

  /** Provide custom [RunnableHandler]. If null is provided default one will be used for layouts. */
  @ThreadConfined(ThreadConfined.UI)
  fun updateLayoutThreadHandler(layoutThreadHandler: RunnableHandler?) {
    synchronized(updateStateSyncRunnableLock) {
      val currentHandler = this.layoutThreadHandler
      val runnable = updateStateSyncRunnable
      if (runnable != null && currentHandler != null) {
        currentHandler.remove(runnable as Runnable)
      }
    }

    this.layoutThreadHandler =
        ensureAndInstrumentLayoutThreadHandler(
            getOrCreateRunnableHandlerProvider(lithoConfiguration.componentsConfig),
            layoutThreadHandler,
        )
  }

  @ThreadConfined(ThreadConfined.UI)
  private fun dispatchNewLayoutStateReady() {
    val listener = newLayoutStateReadyListener
    listener?.onNewLayoutStateReady(this)
  }

  private fun backgroundLayoutStateUpdated() {
    assertMainThread()

    trace(
        {
          "commit.backgroundLayoutStateUpdated[${_mainThreadLayoutState?.rootComponent?.simpleName ?: "Unknown"}]"
        },
    ) {
      val layoutStateUpdated: Boolean
      val promotedLayoutStateReadyListener: NewLayoutStateReadyListener?
      synchronized(this) {
        if (_root == null) {
          // We have been released. Abort.
          return
        }
        val committedLayoutState =
            committedLayoutState ?: throw RuntimeException("Unexpected null mCommittedLayoutState")

        val layoutStatePromoted = committedLayoutState !== _mainThreadLayoutState
        layoutStateUpdated = maybePromoteCommittedLayoutStateToUI()
        val listener = newLayoutStateReadyListener
        promotedLayoutStateReadyListener =
            if (
                !layoutStateUpdated &&
                    layoutStatePromoted &&
                    listener?.dispatchOnPromotedLayout == true
            ) {
              listener
            } else {
              null
            }
      }

      if (!layoutStateUpdated) {
        promotedLayoutStateReadyListener?.onNewLayoutStateReady(this)
        if (DEBUG_LOGS) {
          debugLog("backgroundLayoutStateUpdated", "Abort: LayoutState was not updated", _root)
        }

        return
      }

      dispatchNewLayoutStateReady()

      // If we are in measure, we will let mounting happen from the layout call
      if (!_isAttached || isMeasuring) {
        if (DEBUG_LOGS) {
          debugLog(
              "backgroundLayoutStateUpdated",
              "Abort: will wait for attach/measure (isAttached: $_isAttached, isMeasuring: $isMeasuring)",
              _mainThreadLayoutState?.rootComponent,
          )
        }

        return
      }

      val lithoView = _lithoView
      if (lithoView == null) {
        if (DEBUG_LOGS) {
          debugLog(
              "backgroundLayoutStateUpdated",
              "Abort: LithoView is null",
              _mainThreadLayoutState?.rootComponent,
          )
        }
        return
      }

      // We defer until measure if we don't yet have a width/height
      val viewWidth = lithoView.measuredWidth
      val viewHeight = lithoView.measuredHeight
      if (!lithoView.hasMeasuredAtLeastOnce()) {
        if (DEBUG_LOGS) {
          debugLog(
              "backgroundLayoutStateUpdated",
              "Abort: Host view was not measured yet",
              _mainThreadLayoutState?.rootComponent,
          )
        }

        // The host view has not been measured yet.
        return
      }

      val needsAndroidLayout =
          _mainThreadLayoutState == null ||
              _mainThreadLayoutState?.width != viewWidth ||
              _mainThreadLayoutState?.height != viewHeight

      if (needsAndroidLayout) {
        lithoView.requestLayout()
      } else {
        lithoView.mountComponentIfNeeded()
      }

      if (DEBUG_LOGS) {
        debugLog(
            "backgroundLayoutStateUpdated",
            "Updated - viewWidth: $viewWidth, viewHeight: $viewHeight, needsAndroidLayout: $needsAndroidLayout, layoutRequested: ${lithoView.isLayoutRequested}",
            _mainThreadLayoutState?.rootComponent,
        )
      }
    }
  }

  internal fun attach() {
    assertMainThread()

    if (_lithoView == null) {
      throw IllegalStateException("Trying to attach a ComponentTree without a set View")
    }
    ownerSaveableStateBinding?.onAttach()
    renderLateRestoredNestedState()
    lithoSaveableStateRegistry?.onTreeShown()
    inAttach = true
    try {
      val currentLithoView =
          requireNotNull(_lithoView) { "LithoView should not be null during attach" }
      incrementalMountHelper?.onAttach(currentLithoView)

      synchronized(this) {
        // We need to track that we are attached regardless...
        _isAttached = true

        maybePromoteCommittedLayoutStateToUI()

        if (_root == null) {
          throw IllegalStateException(
              "Trying to attach a ComponentTree with a null root. Is released: $released, Released Component name is: $_releasedComponent",
          )
        }
      }

      // We defer until measure if we don't yet have a width/height
      val viewWidth = currentLithoView.measuredWidth
      val viewHeight = currentLithoView.measuredHeight
      if (!currentLithoView.hasMeasuredAtLeastOnce()) {
        // The host view has not been measured yet.
        return
      }

      val needsAndroidLayout =
          _mainThreadLayoutState == null ||
              _mainThreadLayoutState?.width != viewWidth ||
              _mainThreadLayoutState?.height != viewHeight

      if (needsAndroidLayout || currentLithoView.isMountStateDirty) {
        currentLithoView.requestLayout()
      } else {
        currentLithoView.rebind()
      }
    } finally {
      inAttach = false
    }
  }

  /**
   * @return whether this ComponentTree has a computed layout that will work for the given measure
   *   specs.
   */
  @Synchronized
  fun hasCompatibleLayout(widthSpec: Int, heightSpec: Int): Boolean {
    return isCompatibleSpec(_mainThreadLayoutState, widthSpec, heightSpec) ||
        isCompatibleSpec(committedLayoutState, widthSpec, heightSpec)
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  @Synchronized
  fun hasComponentAndSizeCompatibleLayoutIgnoringAccessibility(
      widthSpec: Int,
      heightSpec: Int,
  ): Boolean {
    val rootId = _root?.instanceId ?: INVALID_ID
    return _mainThreadLayoutState?.isCompatibleComponentAndSpec(rootId, widthSpec, heightSpec) ==
        true ||
        committedLayoutState?.isCompatibleComponentAndSpec(rootId, widthSpec, heightSpec) == true
  }

  internal fun detach() {
    assertMainThread()

    if (incrementalMountHelper != null) {
      val lithoView = _lithoView
      if (lithoView != null) {
        incrementalMountHelper.onDetach(lithoView)
      }
    }

    synchronized(this) { _isAttached = false }
    ownerSaveableStateBinding?.onDetach()
  }

  internal fun isAttached(): Boolean {
    return _isAttached
  }

  /**
   * Set a new LithoView to this ComponentTree checking that they have the same context and clear
   * the ComponentTree reference from the previous LithoView if any. Be sure this ComponentTree is
   * detach first.
   */
  internal fun setLithoView(view: LithoView) {
    assertMainThread()
    ownerSaveableStateBinding?.initializeOnMain()

    if (_lithoView == view) {
      return
    }
    lithoVisibilityEventsController?.let { controller ->
      view.initVisibilityHint(controller.visibilityState)
    }

    if (_lithoView != null) {
      _lithoView?.componentTree = null
    } else if (_isAttached) {
      // It's possible that the view associated with this ComponentTree was recycled but was
      // never detached. In all cases we have to make sure that the old references between
      // lithoView and componentTree are reset.
      detach()
    }

    if (
        context.androidContext != context.applicationContext &&
            !hasSameRootContext(view.context, context.androidContext)
    ) {
      // This would indicate bad things happening, like leaking a context.
      throw IllegalArgumentException(
          "Base view context differs, view context is: ${view.context}, ComponentTree context is: ${context.androidContext}",
      )
    }

    _lithoView = view
  }

  internal fun clearLithoView() {
    assertMainThread()

    // Crash if the ComponentTree is mounted to a view.
    if (_isAttached) {
      throw IllegalStateException("Clearing the LithoView while the ComponentTree is attached")
    }
    if (
        lithoVisibilityEventsController != null ||
            lithoConfiguration.componentsConfig.resetVisibilityHintOnTreeDetach
    ) {
      _lithoView?.resetVisibilityHint()
    }

    if (inAttach) {
      throw RuntimeException("clearing LithoView while in attach")
    }

    BaseMountingView.clearDebugOverlay(_lithoView)

    _lithoView = null
  }

  @UiThread
  @GuardedBy("this")
  private fun maybePromoteCommittedLayoutStateToUI(): Boolean {
    val committedLayoutState = committedLayoutState ?: return false

    if (committedLayoutState === _mainThreadLayoutState) {
      return false
    }

    val previousMainThreadLayoutState: LayoutState? = _mainThreadLayoutState
    _mainThreadLayoutState = committedLayoutState

    val layoutState =
        requireNotNull(_mainThreadLayoutState) { "mainThreadLayoutState should not be null" }

    AnimationController.bindContext(layoutState.componentContext.androidContext)
    val isReadTrackingEnabled = context.isReadTrackingEnabled
    val treeState: TreeState? = uiTreeState
    val committedState = getCommittedTreeStateSync()
    val effectiveSnapshot =
        if (isReadTrackingEnabled) {
          committedState?.getUpdatesForPromotion(layoutState.treeState) ?: emptyMap()
        } else {
          emptyMap()
        }
    val updatedStates = effectiveSnapshot.keys
    val effectContext = EffectContext(id, isReadTrackingEnabled, updatedStates)
    var effectRunnable: Runnable? = null
    try {
      if (treeState != null) {
        val attachables: List<Attachable>? = layoutState.attachables
        // This must happen before committing new updates to the UI tree state
        effectRunnable = with(treeState.effectsHandler) { effectContext.updateEffects(attachables) }

        treeState.commitResolveState(layoutState.resolveResult.treeState)
        treeState.commitLayoutState(layoutState.treeState)

        // Accumulate mount updates on UI TreeState
        treeState.addMountUpdates(updatedStates)
      }
    } finally {
      effectRunnable?.run()
    }

    // Subtract promoted updates from committed TreeState
    if (effectiveSnapshot.isNotEmpty()) {
      committedState?.subtractPromotedUpdates(effectiveSnapshot)
    }

    if (
        LayoutState.isNullOrEmpty(previousMainThreadLayoutState) &&
            LayoutState.isNullOrEmpty(committedLayoutState)
    ) {
      return false
    }

    if (treeState != null) {
      val scopes = layoutState.consumeComponentScopes()
      if (scopes != null) {
        treeState.updateEventDispatchers(scopes)
      }
    }

    _lithoView?.setMountStateDirty()

    return true
  }

  internal fun measure(
      widthSpec: Int,
      heightSpec: Int,
      measureOutput: IntArray,
      forceLayout: Boolean,
  ) {
    assertMainThread()

    isMeasuring = true
    try {
      // A bind/draw frame may have queued a layout-affecting animation update and requested
      // this traversal; recompute in-frame with the incoming specs. Consume the marker, but
      // if a forced relayout also coincides, defer to the forced path (it still applies the
      // queued updates) instead of downgrading it to the incremental animation path.
      if (consumePendingAnimationLayoutUpdates() && !forceLayout) {
        flushAnimationLayout(widthSpec, heightSpec, measureOutput)
        return
      }

      val needsSyncLayout: Boolean
      var isAsyncMeasureLayoutRedundant = false
      synchronized(this) {
        if (isCompatibleSpec(committedLayoutState, widthSpec, heightSpec)) {
          maybePromoteCommittedLayoutStateToUI()
        }

        val mainThreadLayoutState = _mainThreadLayoutState
        val hasExactSameSpecs =
            mainThreadLayoutState != null &&
                getWidthSpec(mainThreadLayoutState.sizeConstraints) == widthSpec &&
                getHeightSpec(mainThreadLayoutState.sizeConstraints) == heightSpec
        val rootInstanceId = _root?.instanceId ?: INVALID_ID
        val hasSameRootAndEquivalentSpecs = isCompatibleComponentAndSpec(
            mainThreadLayoutState,
            rootInstanceId,
            widthSpec,
            heightSpec,
        )

        if (
            (context.lithoConfiguration.componentsConfig.performExactSameSpecsCheck &&
                hasExactSameSpecs) || hasSameRootAndEquivalentSpecs
        ) {
          requireNotNull(mainThreadLayoutState)
          measureOutput[0] = mainThreadLayoutState.width
          measureOutput[1] = mainThreadLayoutState.height
          needsSyncLayout = false
          isAsyncMeasureLayoutRedundant =
              hasExactSameSpecs &&
                  isAsyncMeasureLayoutRedundant(mainThreadLayoutState, widthSpec, heightSpec)
        } else {
          needsSyncLayout = true

          val state = _mainThreadLayoutState

          DebugEventDispatcher.dispatch(
              LithoDebugEvent.RenderOnMainThreadStarted,
              { id.toString() },
          ) { attributes ->
            attributes[Root] = _root?.simpleName ?: ""
            attributes[Breadcrumb] = debugLogBreadcrumb
            attributes[HasMainThreadLayoutState] = state != null
            attributes[SizeSpecsMatch] = true
            attributes[IdMatch] = true

            if (state != null) {
              val rootId = _root?.instanceId ?: INVALID_ID
              val mainThreadLayoutStateRootId = state.rootComponent.instanceId
              val currentWidthSpec = getWidthSpec(state.sizeConstraints)
              val currentHeightSpec = getHeightSpec(state.sizeConstraints)

              attributes[SizeSpecsMatch] = state.isCompatibleSpec(widthSpec, heightSpec)
              attributes[IdMatch] = mainThreadLayoutStateRootId == rootId
              attributes[CurrentWidthSpec] = currentWidthSpec
              attributes[CurrentHeightSpec] = currentHeightSpec
              attributes[CurrentSizeConstraint] = specsToString(currentWidthSpec, currentHeightSpec)
              attributes[WidthSpec] = widthSpec
              attributes[HeightSpec] = heightSpec
              attributes[SizeConstraint] = specsToString(widthSpec, heightSpec)
              attributes[RootId] = rootId
              attributes[CurrentRootId] = mainThreadLayoutStateRootId
            }
          }
        }
      }

      if (needsSyncLayout || forceLayout) {
        if (DebugOverlay.isEnabled && ThreadUtils.isMainThread && _lithoView != null) {
          _lithoView?.let { view -> flash(view) }
        }

        val output = Size()

        setSizeSpecForMeasure(widthSpec, heightSpec, output, forceLayout)

        // It's possible we don't commit a layout or block on a future on another thread (which will
        // not immediately promote the committed layout state since that needs to happen on the main
        // thread). Ensure we have the latest LayoutState before exiting.
        synchronized(this) {
          if (released) {
            throw RuntimeException("Tree is released during measure!")
          }
          maybePromoteCommittedLayoutStateToUI()

          if (_mainThreadLayoutState != null) {
            measureOutput[0] = _mainThreadLayoutState?.width ?: output.width
            measureOutput[1] = _mainThreadLayoutState?.height ?: output.height
          } else {
            measureOutput[0] = output.width
            measureOutput[1] = output.height
          }
        }
      } else if (!isAsyncMeasureLayoutRedundant) {
        setSizeSpecForMeasureAsync(widthSpec, heightSpec)
      }
    } finally {
      isMeasuring = false
    }

    if (DEBUG_LOGS) {
      debugLog(
          "FinishMeasure",
          "WidthSpec: ${SizeSpec.toSimpleString(widthSpec)}, HeightSpec: ${SizeSpec.toSimpleString(heightSpec)}, OutWidth: ${measureOutput[0]}, OutHeight: ${measureOutput[1]}",
          _root,
      )
    }
  }

  @Suppress("FieldsBelowInit")
  val lithoConfiguration: LithoConfiguration
    get() = context.lithoConfiguration

  /** Returns whether incremental mount is enabled or not in this component. */
  @Suppress("FieldsBelowInit")
  val isIncrementalMountEnabled: Boolean
    get() = ComponentContext.isIncrementalMountEnabled(context)

  internal fun isVisibilityProcessingEnabled(): Boolean {
    return ComponentContext.isVisibilityProcessingEnabled(context)
  }

  /**
   * Kotlin property for accessing and setting the root component.
   *
   * The getter returns [_root] directly. The setter is hidden from Java callers via
   * [@JvmSynthetic], so Java code will continue to use [setRoot]. The [@JvmName] annotation
   * provides a unique JVM name to avoid conflicts with the existing setter method. Kotlin callers
   * can use this property directly (e.g., `componentTree.root = myComponent`).
   *
   * NOTE: The setter delegates to [setRoot] to ensure that mocking the method in tests also affects
   * the Kotlin property accessor.
   */
  @get:Synchronized
  @Suppress("FieldsBelowInit")
  var root: Component?
    get() = _root
    @JvmSynthetic @JvmName("setRootProperty") set(value) = setRoot(value)

  @Synchronized
  internal fun getWidthSpec(): Int {
    return widthSpec
  }

  @Synchronized
  internal fun getHeightSpec(): Int {
    return heightSpec
  }

  /**
   * Update the root component. This can happen in both attached and detached states. In each case
   * we will run a layout and then proxy a message to the main thread to cause a
   * relayout/invalidate.
   */
  fun setRoot(root: Component?) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = SIZE_UNINITIALIZED,
        heightSpec = SIZE_UNINITIALIZED,
        isAsync = false,
        output = null,
        source = RenderSource.SET_ROOT_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
    )
  }

  fun setRootSync(root: Component?) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = SIZE_UNINITIALIZED,
        heightSpec = SIZE_UNINITIALIZED,
        isAsync = false,
        output = null,
        source = RenderSource.SET_ROOT_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
    )
  }

  fun setRootAsync(root: Component?) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = SIZE_UNINITIALIZED,
        heightSpec = SIZE_UNINITIALIZED,
        isAsync = true,
        output = null,
        source = RenderSource.SET_ROOT_ASYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
    )
  }

  @VisibleForTesting
  @Synchronized
  internal fun updateStateLazy(componentKey: GlobalKey, stateUpdate: StateUpdate) {
    updateStateLazy(componentKey, stateUpdate, false)
  }

  @Synchronized
  override fun updateStateLazy(
      componentKey: GlobalKey,
      stateUpdate: StateUpdate,
      isLayoutState: Boolean,
  ) {
    val treeState = getCommittedTreeStateSync()
    if (_root == null || treeState == null) {
      return
    }

    val stateId = StateId.forSpec(id, componentKey)
    treeState.queueStateUpdate(stateId, stateUpdate, true, isLayoutState)
  }

  /**
   * @return a StateContainer with lazy state updates applied. This may be the same container passed
   *   in if there were no updates to apply. This method won't mutate the passed container.
   */
  @Synchronized
  override fun applyLazyStateUpdatesForContainer(
      componentKey: GlobalKey,
      container: StateContainer,
      isLayoutState: Boolean,
  ): StateContainer {
    val treeState = getCommittedTreeStateSync()
    if (_root == null || treeState == null) {
      return container
    }

    val stateId = StateId.forSpec(id, componentKey)
    return treeState.applyLazyStateUpdatesForContainer(stateId, container, isLayoutState)
  }

  @VisibleForTesting
  internal fun updateStateSync(
      componentKey: GlobalKey,
      stateUpdate: StateUpdate,
      attribution: String?,
  ) {
    updateStateSync(componentKey, stateUpdate, attribution, false)
  }

  override fun updateStateSync(
      componentKey: GlobalKey,
      stateUpdate: StateUpdate,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    synchronized(this) {
      if (_root == null) {
        return
      }

      val committedTreeState = getCommittedTreeStateSync()
      if (committedTreeState != null) {
        try {
          val stateId = StateId.forSpec(id, componentKey)
          committedTreeState.queueStateUpdate(stateId, stateUpdate, false, isLayoutState)
        } catch (e: Exception) {
          context.lithoConfiguration.componentsConfig.errorEventHandler.onError(context, e)
        }
      }
    }

    dispatchStateUpdateEnqueuedEvent(attribution, isSynchronous = true)
    ensureSyncStateUpdateRunnable(attribution)
  }

  @VisibleForTesting
  fun updateStateAsync(componentKey: String, stateUpdate: StateUpdate, attribution: String?) {
    updateStateAsync(StringGlobalKey(componentKey), stateUpdate, attribution, false)
  }

  override fun updateStateAsync(
      componentKey: GlobalKey,
      stateUpdate: StateUpdate,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    synchronized(this) {
      if (_root == null) {
        return
      }

      val stateId = StateId.forSpec(id, componentKey)
      getCommittedTreeStateSync()?.queueStateUpdate(stateId, stateUpdate, false, isLayoutState)
    }

    dispatchStateUpdateEnqueuedEvent(attribution, isSynchronous = false)
    LithoStats.incrementComponentStateUpdateAsyncCount()
    onAsyncStateUpdateEnqueued(attribution)
  }

  override fun updateHookStateSync(
      stateId: StateId,
      updater: HookUpdater,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    synchronized(this) {
      if (_root == null) {
        return
      }

      getCommittedTreeStateSync()?.queueHookStateUpdate(stateId, updater, isLayoutState)
    }

    dispatchStateUpdateEnqueuedEvent(attribution, isSynchronous = true)
    ensureSyncStateUpdateRunnable(attribution)
  }

  @ThreadConfined(ThreadConfined.UI) private var pendingAnimationLayoutUpdates: Boolean = false

  override val isAnimationFrameInvalidationEnabled: Boolean =
      context.lithoConfiguration.componentsConfig.enableAnimationFrameInvalidation

  override fun applyAnimationFrameUpdates(invalidations: List<AnimationFrameInvalidation>) {
    if (invalidations.isEmpty()) {
      return
    }

    val lithoView = _lithoView
    val layoutState = mainThreadLayoutState
    if (
        lithoView == null ||
            lithoView.isMounting ||
            isMeasuring ||
            layoutState == null ||
            !context.isReadTrackingEnabled
    ) {
      // Fall back to the frame-deferred path: disabled, no mounted view, no committed layout, read
      // tracking off, or re-entrant (mid-mount/measure) where both applyChanges and requestLayout
      // are unsafe. Re-entrancy is only reachable via snapTo from inside a mount/measure callback
      // (frame ticks run before traversal); such a value isn't frame-driven, so there is no next
      // frame to re-queue to and the deferred path is the correct behavior.
      super.applyAnimationFrameUpdates(invalidations)
      return
    }

    val updatedStates: Set<StateId> =
        invalidations.mapTo(HashSet(invalidations.size)) { it.stateId }

    // In-frame bind/draw fast path. Taken only when no dirty value is read at resolve,
    // layout, or in an effect: resolve/layout reads need a real layout, and effects run
    // only at commit. Anything else falls through to the normal committing path. Both
    // checks are allocation-free and short-circuit, so the common bind-only frame is cheap.
    val resolveStateReads = layoutState.resolveResult.outputs?.stateReads
    val layoutStateReads = layoutState.stateReads
    if (
        !hasDirtyScopes(updatedStates, resolveStateReads) &&
            !hasDirtyScopes(updatedStates, layoutStateReads) &&
            effectHandler?.hasReadsFor(updatedStates) != true
    ) {
      if (lithoView.applyChanges(updatedStates)) {
        return
      }
    }

    if (!queueAnimationLayoutInvalidations(invalidations, lithoView)) {
      super.applyAnimationFrameUpdates(invalidations)
    }
  }

  /**
   * Queues [invalidations] as no-op hook updates and requests a single traversal that recomputes
   * the dirty scope in-frame (see [measure]). Returns false if there is nothing committed to queue
   * against, in which case the caller should use the frame-deferred fallback.
   */
  private fun queueAnimationLayoutInvalidations(
      invalidations: List<AnimationFrameInvalidation>,
      lithoView: LithoView,
  ): Boolean {
    synchronized(this) {
      if (_root == null) {
        return false
      }
      val treeState = getCommittedTreeStateSync() ?: return false
      for (invalidation in invalidations) {
        treeState.queueHookStateUpdate(
            invalidation.stateId,
            HookUpdater.NoOp,
            invalidation.isLayoutState,
        )
      }
      pendingAnimationLayoutUpdates = true
    }
    dispatchStateUpdateEnqueuedEvent(invalidations[0].attribution, isSynchronous = true)
    lithoView.requestLayout()
    return true
  }

  // UI-thread confined (measure asserts main thread), so no lock is needed.
  private fun consumePendingAnimationLayoutUpdates(): Boolean {
    val pending = pendingAnimationLayoutUpdates
    pendingAnimationLayoutUpdates = false
    return pending
  }

  /**
   * Recomputes a pending animation layout during [measure]: the queued no-op updates drive an
   * incremental re-resolve of only the dirty scope (via the uncommitted-updates path), using the
   * incoming measure specs so a same-frame parent spec change is handled.
   *
   * `forceLayout = false` is intentional: the queued updates already shallow-clone the root (the
   * same clone every `updateStateSync` incurs), and `forceLayout = true` would force a full
   * re-resolve, defeating the incremental recompute. There is no recompute-without-clone seam.
   */
  private fun flushAnimationLayout(widthSpec: Int, heightSpec: Int, measureOutput: IntArray) {
    val output = Size()
    setRootAndSizeSpecInternal(
        root = _root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = output,
        source = RenderSource.MEASURE_SET_SIZE_SPEC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = "animation",
        treePropContainer = null,
        forceLayout = false,
    )
    synchronized(this) {
      if (released) {
        throw RuntimeException("Tree is released during measure!")
      }
      maybePromoteCommittedLayoutStateToUI()
      measureOutput[0] = _mainThreadLayoutState?.width ?: output.width
      measureOutput[1] = _mainThreadLayoutState?.height ?: output.height
    }
  }

  override fun updateHookStateAsync(
      stateId: StateId,
      updater: HookUpdater,
      attribution: String?,
      isLayoutState: Boolean,
  ) {
    synchronized(this) {
      if (_root == null) {
        return
      }

      getCommittedTreeStateSync()?.queueHookStateUpdate(stateId, updater, isLayoutState)
    }

    dispatchStateUpdateEnqueuedEvent(attribution, isSynchronous = false)
    LithoStats.incrementComponentStateUpdateAsyncCount()
    onAsyncStateUpdateEnqueued(attribution)
  }

  private fun onAsyncStateUpdateEnqueued(attribution: String?) {
    if (!batchedStateUpdatesStrategy.onAsyncStateUpdateEnqueued(attribution, false)) {
      updateStateInternal(true, attribution)
    }
  }

  private fun ensureSyncStateUpdateRunnable(attribution: String?) {
    LithoStats.incrementComponentStateUpdateSyncCount()
    val looper = Looper.myLooper()

    if (looper == null) {
      Log.w(
          TAG,
          "You cannot update state synchronously from a thread without a looper, using the default background layout thread instead",
      )
      synchronized(updateStateSyncRunnableLock) {
        val layoutThreadHandler = this.layoutThreadHandler
        if (layoutThreadHandler != null) {
          updateStateSyncRunnable?.let { layoutThreadHandler.remove(it) }
          val runnable = UpdateStateSyncRunnable(attribution)
          updateStateSyncRunnable = runnable

          var tag = EMPTY_STRING
          if (layoutThreadHandler.isTracing()) {
            tag = "updateStateSyncNoLooper $attribution"
          }
          layoutThreadHandler.post(runnable, tag)
        }
      }
      return
    }

    val handlerWr = syncStateUpdatesHandler.get()
    var handler = handlerWr?.get()

    if (handler == null) {
      handler = DefaultHandler(looper)
      syncStateUpdatesHandler.set(WeakReference(handler))
    }

    synchronized(updateStateSyncRunnableLock) {
      updateStateSyncRunnable?.let { handler.remove(it) }
      val runnable = UpdateStateSyncRunnable(attribution)
      updateStateSyncRunnable = runnable

      var tag = EMPTY_STRING
      if (handler.isTracing()) {
        tag = "updateStateSync $attribution"
      }
      handler.post(runnable, tag)
    }
  }

  private fun dispatchStateUpdateEnqueuedEvent(attribution: String?, isSynchronous: Boolean) {
    DebugEventDispatcher.dispatch(LithoDebugEvent.StateUpdateEnqueued, { id.toString() }) {
        attributes ->
      attributes[LithoDebugEventAttributes.Root] = _root?.simpleName ?: ""
      attributes[LithoDebugEventAttributes.Attribution] = attribution
      attributes[Async] = !isSynchronous
      attributes[LithoDebugEventAttributes.Stack] = LithoDebugEvent.generateStateTrace()
    }
  }

  private fun dispatchRootClonedEvent(
      root: Component?,
      oldRootId: Int,
      newRootId: Int,
      reason: String,
      attribution: String?,
  ) {
    DebugEventDispatcher.dispatch(LithoDebugEvent.RootCloned, { id.toString() }) { attributes ->
      attributes[LithoDebugEventAttributes.Root] = root?.simpleName ?: ""
      attributes[LithoDebugEventAttributes.CurrentRootId] = oldRootId
      attributes[LithoDebugEventAttributes.RootId] = newRootId
      attributes[LithoDebugEventAttributes.Reason] = reason
      attributes[LithoDebugEventAttributes.Attribution] = attribution
    }
  }

  /**
   * Renders again after restored saveable values arrived for a tree that rendered before them. It
   * runs synchronously so the restored values, not the initial ones, are what the first mount
   * shows.
   */
  @UiThread
  private fun renderRestoredSaveableState() {
    setRootAndSizeSpecInternal(
        root = null,
        widthSpec = SIZE_UNINITIALIZED,
        heightSpec = SIZE_UNINITIALIZED,
        isAsync = false,
        output = null,
        source = RenderSource.UPDATE_STATE_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = "saveable state restored",
        treePropContainer = null,
        forceLayout = true,
    )
  }

  /**
   * Renders a tree without an owner again when restored values reached its registry through its
   * parent after it rendered, as for a list row laid out before its host tree's late restore. The
   * host tree had not mounted then, so this tree has shown nothing yet.
   */
  @UiThread
  private fun renderLateRestoredNestedState() {
    if (ownerSaveableStateBinding != null) return
    val generation = lithoSaveableStateRegistry?.generation ?: return
    if (generation == renderedGeneration) return
    renderedGeneration = generation
    renderRestoredSaveableState()
  }

  internal fun updateStateInternal(isAsync: Boolean, attribution: String?) {
    synchronized(this) {
      if (_root == null) {
        return
      }
      batchedStateUpdatesStrategy.onInternalStateUpdateStart()
    }

    setRootAndSizeSpecInternal(
        root = _root,
        widthSpec = SIZE_UNINITIALIZED,
        heightSpec = SIZE_UNINITIALIZED,
        isAsync = isAsync,
        output = null,
        source = if (isAsync) RenderSource.UPDATE_STATE_ASYNC else RenderSource.UPDATE_STATE_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = attribution,
        treePropContainer = null,
        forceLayout = false,
    )
  }

  /**
   * Retrieves the tree property associated with the given key, if it exists in this [ComponentTree]
   *
   * @param key the key of the tree property to retrieve
   * @param <T> the type of the tree property to retrieve
   * @return the value associated with the given key, or null if no such value exists
   */
  @VisibleForTesting
  @Synchronized
  fun <T> getTreeProp(key: TreeProp<T>): T? {
    val treePropContainer = treePropContainer
    return treePropContainer?.get(key)
  }

  @Synchronized
  fun getCommittedTreeStateSync(): TreeState? {
    return committedTreeState
  }

  @Synchronized
  fun getUiTreeStateSync(): TreeState? {
    return uiTreeState
  }

  @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
  override fun getEventTrigger(triggerKey: Any): EventTrigger<*>? {
    return uiTreeState?.getEventTrigger(triggerKey)
  }

  @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
  override fun getEventTrigger(handle: Handle, methodId: Int): EventTrigger<*>? {
    return uiTreeState?.getEventTrigger(handle, methodId)
  }

  /**
   * Check if the any child components stored in [LayoutState] have entered/exited the working
   * range, and dispatch the event to trigger the corresponding registered methods.
   */
  @Synchronized
  fun checkWorkingRangeAndDispatch(
      position: Int,
      firstVisibleIndex: Int,
      lastVisibleIndex: Int,
      firstFullyVisibleIndex: Int,
      lastFullyVisibleIndex: Int,
  ) {
    committedLayoutState?.checkWorkingRangeAndDispatch(
        position,
        firstVisibleIndex,
        lastVisibleIndex,
        firstFullyVisibleIndex,
        lastFullyVisibleIndex,
        workingRangeStatusHandler,
    )
  }

  /**
   * Dispatch OnExitedRange event to component which is still in the range, then clear the handler.
   */
  @Synchronized
  private fun clearWorkingRangeStatusHandler() {
    committedLayoutState?.dispatchOnExitRangeIfNeeded(workingRangeStatusHandler)
    workingRangeStatusHandler.clear()
  }

  /**
   * Update the width/height spec. This is useful if you are currently detached and are responding
   * to a configuration change. If you are currently attached then the HostView is the source of
   * truth for width/height, so this call will be ignored.
   */
  fun setSizeSpec(widthSpec: Int, heightSpec: Int) {
    setSizeSpec(widthSpec, heightSpec, null)
  }

  /** Same as [setSizeSpec] but fetches the resulting width/height in the given [Size]. */
  fun setSizeSpec(widthSpec: Int, heightSpec: Int, output: Size?) {
    setRootAndSizeSpecInternal(
        root = null,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = output,
        source = RenderSource.SET_SIZE_SPEC_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
        forceLayout = false,
    )
  }

  fun setSizeSpecAsync(widthSpec: Int, heightSpec: Int) {
    setRootAndSizeSpecInternal(
        root = null,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = true,
        output = null,
        source = RenderSource.SET_SIZE_SPEC_ASYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
        forceLayout = false,
    )
  }

  private fun setSizeSpecForMeasure(
      widthSpec: Int,
      heightSpec: Int,
      output: Size,
      forceLayout: Boolean,
  ) {
    setRootAndSizeSpecInternal(
        root = null,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = output,
        source = RenderSource.MEASURE_SET_SIZE_SPEC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
        forceLayout = forceLayout,
    )
  }

  private fun setSizeSpecForMeasureAsync(widthSpec: Int, heightSpec: Int) {
    setRootAndSizeSpecInternal(
        root = null,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = true,
        output = null,
        source = RenderSource.MEASURE_SET_SIZE_SPEC_ASYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
        forceLayout = false,
    )
  }

  /** Compute asynchronously a new layout with the given component root and sizes */
  fun setRootAndSizeSpecAsync(root: Component?, widthSpec: Int, heightSpec: Int) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = true,
        output = null,
        source = RenderSource.SET_ROOT_ASYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
    )
  }

  /**
   * Compute asynchronously a new layout with the given component root, sizes and stored
   * TreePropContainer.
   */
  fun setRootAndSizeSpecAsync(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      treePropContainer: TreePropContainer?,
  ) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = true,
        output = null,
        source = RenderSource.SET_ROOT_ASYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = treePropContainer,
    )
  }

  /** When forced, bypasses compatible-layout reuse by cloning the root identity. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun setRootAndSizeSpecAsync(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      treePropContainer: TreePropContainer?,
      forceLayout: Boolean,
  ) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = true,
        output = null,
        source = RenderSource.SET_ROOT_ASYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = treePropContainer,
        forceLayout = forceLayout,
    )
  }

  /** Compute a new layout with the given component root and sizes */
  fun setRootAndSizeSpecSync(root: Component?, widthSpec: Int, heightSpec: Int) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = null,
        source = RenderSource.SET_ROOT_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
    )
  }

  fun setRootAndSizeSpecSync(root: Component?, widthSpec: Int, heightSpec: Int, output: Size?) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = output,
        source = RenderSource.SET_ROOT_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = null,
    )
  }

  fun setRootAndSizeSpecSync(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      output: Size?,
      treePropContainer: TreePropContainer?,
  ) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = output,
        source = RenderSource.SET_ROOT_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = treePropContainer,
    )
  }

  /** When forced, bypasses compatible-layout reuse by cloning the root identity. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun setRootAndSizeSpecSync(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      output: Size?,
      treePropContainer: TreePropContainer?,
      forceLayout: Boolean,
  ) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = output,
        source = RenderSource.SET_ROOT_SYNC,
        externalRootVersion = INVALID_LAYOUT_VERSION,
        extraAttribution = null,
        treePropContainer = treePropContainer,
        forceLayout = forceLayout,
    )
  }

  fun setVersionedRootAndSizeSpec(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      output: Size?,
      treePropContainer: TreePropContainer?,
      externalRootVersion: Int,
  ) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = false,
        output = output,
        source = RenderSource.SET_ROOT_SYNC,
        externalRootVersion = externalRootVersion,
        extraAttribution = null,
        treePropContainer = treePropContainer,
    )
  }

  fun setVersionedRootAndSizeSpecAsync(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      output: Size?,
      treePropContainer: TreePropContainer?,
      externalRootVersion: Int,
  ) {
    setRootAndSizeSpecAndWrapper(
        root = root,
        widthSpec = widthSpec,
        heightSpec = heightSpec,
        isAsync = true,
        output = output,
        source = RenderSource.SET_ROOT_ASYNC,
        externalRootVersion = externalRootVersion,
        extraAttribution = null,
        treePropContainer = treePropContainer,
    )
  }

  /**
   * @return the [LithoView] associated with this ComponentTree if any. Since this is modified on
   *   the main thread, it is racy to get the current LithoView off the main thread.
   */
  @Keep
  @UiThread
  fun getLithoView(): LithoView? {
    return _lithoView
  }

  /**
   * Provides a new instance of the [TreeState] that is initialized with the committed TreeState
   * held by the ComponentTree.
   *
   * @return a copy of tree state instance held by ComponentTree
   */
  @Synchronized
  fun acquireTreeState(): TreeState {
    val committedTreeState = getCommittedTreeStateSync()
    val state = if (committedTreeState == null) TreeState() else TreeState(committedTreeState)
    val mainThreadLayoutState = _mainThreadLayoutState
    if (mainThreadLayoutState != null) {
      state.recordRenderData(mainThreadLayoutState)
    }
    return state
  }

  /**
   * Retains this nested tree's [LithoSaveableStateRegistry] for a replacement tree managed by the
   * same framework owner. The caller becomes responsible for closing the registry when that nested
   * tree is permanently removed.
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  @UiThread
  @Synchronized
  fun acquireRetainedSaveableStateRegistryForTreeReplacement(): LithoSaveableStateRegistry? {
    assertMainThread()
    check(!released) { "Cannot acquire a saveable-state registry from a released ComponentTree" }
    check(ownerSaveableStateBinding == null) {
      "A ComponentTree cannot transfer a registry owned by its SavedStateRegistryOwner"
    }
    val registry = lithoSaveableStateRegistry ?: return null
    check(!retainedLithoSaveableStateRegistryAcquired) {
      "The ComponentTree saveable-state registry has already been transferred"
    }
    retainedLithoSaveableStateRegistryAcquired = true
    return registry
  }

  /**
   * Common internal entry-point for calls which are updating the root. If the provided root is
   * null, an EmptyComponent is used instead.
   */
  private fun setRootAndSizeSpecAndWrapper(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      isAsync: Boolean,
      output: Size?,
      @RenderSource source: Int,
      externalRootVersion: Int,
      extraAttribution: String?,
      treePropContainer: TreePropContainer?,
      forceLayout: Boolean = false,
  ) {
    val resolvedRoot = root ?: EmptyComponent()

    setRootAndSizeSpecInternal(
        resolvedRoot,
        widthSpec,
        heightSpec,
        isAsync,
        output,
        source,
        externalRootVersion,
        extraAttribution,
        treePropContainer,
        forceLayout,
    )
  }

  private fun setRootAndSizeSpecInternal(
      root: Component?,
      widthSpec: Int,
      heightSpec: Int,
      isAsync: Boolean,
      output: Size?,
      @RenderSource source: Int,
      externalRootVersion: Int,
      extraAttribution: String?,
      treePropContainer: TreePropContainer?,
      forceLayout: Boolean,
  ) {
    if (isMainThread) {
      ownerSaveableStateBinding?.initializeOnMain()
    }

    var currentRoot = root
    val currentIsAsync = isAsync
    val currentSource = source

    val requestedWidthSpec: Int
    val requestedHeightSpec: Int
    val requestedRoot: Component?
    val requestedTreePropContainer: TreePropContainer?

    synchronized(this) {
      // logs the render that was requested
      logRenderRequest(root, source, widthSpec, heightSpec, extraAttribution, forceLayout)

      if (released) {
        // If this is coming from a background thread, we may have been released from the main
        // thread. In that case, do nothing.
        //
        // NB: This is only safe because we don't re-use released ComponentTrees.
        return
      }

      // If this is coming from a setRoot
      if (source == RenderSource.SET_ROOT_SYNC || source == RenderSource.SET_ROOT_ASYNC) {
        if (this.externalRootVersion >= 0 && externalRootVersion < 0) {
          throw IllegalStateException(
              "Setting an unversioned root after calling setVersionedRootAndSizeSpec is not " +
                  "supported. If this ComponentTree takes its version from a parent tree make " +
                  "sure to always call setVersionedRootAndSizeSpec",
          )
        }

        if (this.externalRootVersion > externalRootVersion) {
          // Since this layout is not really valid we don't need to set a Size.
          return
        }

        this.externalRootVersion = externalRootVersion
      }

      if (currentRoot != null) {
        val committedTreeState = getCommittedTreeStateSync()
        if (committedTreeState != null && committedTreeState.hasUncommittedUpdates()) {
          val oldRootId = currentRoot.instanceId
          currentRoot = currentRoot.makeShallowCopyWithNewId()
          dispatchRootClonedEvent(
              root = currentRoot,
              oldRootId = oldRootId,
              newRootId = currentRoot.instanceId,
              reason = "uncommitted",
              attribution = extraAttribution,
          )
        }
      }

      val rootInitialized = currentRoot != null
      val treePropContainerInitialized = treePropContainer != null
      val widthSpecInitialized = widthSpec != SIZE_UNINITIALIZED
      val heightSpecInitialized = heightSpec != SIZE_UNINITIALIZED
      val resolvedRoot = currentRoot ?: _root
      val resolvedWidthSpec = if (widthSpecInitialized) widthSpec else this.widthSpec
      val resolvedHeightSpec = if (heightSpecInitialized) heightSpec else this.heightSpec
      val mostRecentLayoutState = committedLayoutState

      if (
          !forceLayout &&
              resolvedRoot != null &&
              mostRecentLayoutState != null &&
              mostRecentLayoutState.isCompatibleComponentAndSpec(
                  resolvedRoot.instanceId,
                  resolvedWidthSpec,
                  resolvedHeightSpec,
              )
      ) {
        if (output != null) {
          output.height = mostRecentLayoutState.height
          output.width = mostRecentLayoutState.width
        }

        if (DEBUG_LOGS) {
          debugLog(
              "StartLayout",
              "Layout was compatible, not calculating a new one - Source: ${layoutSourceToString(source)}, Extra: $extraAttribution, WidthSpec: ${SizeSpec.toSimpleString(resolvedWidthSpec)}, HeightSpec: ${SizeSpec.toSimpleString(resolvedHeightSpec)}",
              resolvedRoot,
          )
        }

        return
      }

      if (widthSpecInitialized) {
        this.widthSpec = widthSpec
      }

      if (heightSpecInitialized) {
        this.heightSpec = heightSpec
      }

      if (rootInitialized) {
        _root = currentRoot
        if (ownerSaveableStateBinding != null) saveableIdentityRoot = currentRoot
      }

      if (forceLayout && _root != null) {
        val oldRootId = _root?.instanceId
        _root = _root?.makeShallowCopyWithNewId()
        dispatchRootClonedEvent(
            root = _root,
            oldRootId = oldRootId ?: -1,
            newRootId = _root?.instanceId ?: -1,
            reason = "forced",
            attribution = extraAttribution,
        )
      }

      if (treePropContainerInitialized) {
        val newTreeProps = createTreePropContainer(treePropContainer)
        if (!equals(newTreeProps, this.treePropContainer)) {
          this.treePropContainer = newTreeProps
        }
      }

      requestedWidthSpec = this.widthSpec
      requestedHeightSpec = this.heightSpec
      requestedRoot = _root
      requestedTreePropContainer = this.treePropContainer

      lastLayoutSource = source
    }

    if (currentIsAsync && output != null) {
      throw IllegalArgumentException(
          "The layout can't be calculated asynchronously if we need the Size back",
      )
    }

    if (DEBUG_LOGS) {
      debugLog(
          "StartLayout",
          "Calculating new layout - Source: ${layoutSourceToString(source)}, Extra: $extraAttribution, WidthSpec: ${SizeSpec.toSimpleString(requestedWidthSpec)}, HeightSpec: ${SizeSpec.toSimpleString(requestedHeightSpec)}",
          requestedRoot,
      )
    }

    requestRenderWithSplitFutures(
        currentIsAsync,
        output,
        currentSource,
        extraAttribution,
        requestedWidthSpec,
        requestedHeightSpec,
        requestedRoot,
        requestedTreePropContainer,
    )
  }

  private fun logRenderRequest(
      root: Component?,
      @RenderSource source: Int,
      widthSpec: Int,
      heightSpec: Int,
      extraAttribution: String?,
      wasForced: Boolean,
  ) {
    DebugEventDispatcher.dispatch(
        LithoDebugEvent.RenderRequest,
        { id.toString() },
        LogLevel.VERBOSE,
    ) { attrs ->
      val attribution = getAttribution(extraAttribution)
      attrs[Source] = getSource(source)
      attrs[LithoDebugEventAttributes.RenderExecutionMode] = getExecutionMode(source)
      attrs[LithoDebugEventAttributes.Attribution] = attribution
      attrs[LithoDebugEventAttributes.Root] = root?.fullyQualifiedName ?: "null"
      attrs[LithoDebugEventAttributes.Forced] = wasForced
      if (widthSpec != SIZE_UNINITIALIZED || heightSpec != SIZE_UNINITIALIZED) {
        attrs[WidthSpec] = getMeasureSpecDescription(widthSpec)
        attrs[HeightSpec] = getMeasureSpecDescription(heightSpec)
      }
      attrs[LithoDebugEventAttributes.Stack] = LithoDebugEvent.generateStateTrace()
    }
  }

  private fun requestRenderWithSplitFutures(
      isAsync: Boolean,
      output: Size?,
      @RenderSource source: Int,
      extraAttribution: String?,
      widthSpec: Int,
      heightSpec: Int,
      root: Component?,
      treePropContainer: TreePropContainer?,
  ) {
    var currentIsAsync = isAsync
    var currentSource = source

    val currentResolveResult: ResolveResult?
    synchronized(this) { currentResolveResult = committedResolveResult }

    // If we're only setting root, and there are no size specs, move the operation to async
    // to avoid hanging on the main thread
    if (
        source == RenderSource.SET_ROOT_SYNC &&
            widthSpec == SIZE_UNINITIALIZED &&
            heightSpec == SIZE_UNINITIALIZED
    ) {
      currentIsAsync = true
      currentSource = RenderSource.SET_ROOT_ASYNC
    }

    // The current root and tree-props are the same as the committed resolved result. Therefore,
    // there is no need to calculate the resolved result again and we can proceed straight to
    // layout.
    //
    // Comparing two tree-prop containers walks the map and runs arbitrary value equals, so it has
    // to stay behind the root check instead of being hoisted into its own val: whenever the root
    // changed the answer cannot affect the outcome.
    if (currentResolveResult != null) {
      val canLayoutWithoutResolve =
          currentResolveResult.component === root &&
              (treePropContainer == null ||
                  equals(currentResolveResult.context.treePropContainer, treePropContainer))

      if (canLayoutWithoutResolve) {
        requestLayoutWithSplitFutures(
            currentResolveResult,
            output,
            currentSource,
            extraAttribution,
            false,
            widthSpec,
            heightSpec,
        )
        return
      }
    }

    synchronized(currentDoLayoutRunnableLock) {
      currentDoResolveRunnable?.let { runnable ->
        layoutThreadHandler?.remove(runnable)
        currentDoResolveRunnable = null
      }
    }

    if (currentIsAsync) {
      val layoutThreadHandler = this.layoutThreadHandler
      if (layoutThreadHandler != null && root != null) {
        synchronized(currentDoLayoutRunnableLock) {
          currentDoResolveRunnable =
              DoResolveRunnable(
                  currentSource,
                  root,
                  treePropContainer,
                  widthSpec,
                  heightSpec,
                  extraAttribution,
              )

          var tag = EMPTY_STRING
          if (layoutThreadHandler.isTracing()) {
            tag = "doResolve "
            _root?.let { root -> tag = tag + root.simpleName }
          }
          currentDoResolveRunnable?.let { runnable -> layoutThreadHandler.post(runnable, tag) }
        }
      }
    } else {
      doResolve(
          output,
          currentSource,
          extraAttribution,
          root,
          treePropContainer,
          widthSpec,
          heightSpec,
      )
    }
  }

  private fun doResolve(
      output: Size?,
      @RenderSource source: Int,
      extraAttribution: String?,
      requestedRoot: Component?,
      requestedTreePropContainer: TreePropContainer?,
      widthSpec: Int,
      heightSpec: Int,
  ) {
    val root: Component
    val treePropContainer: TreePropContainer?
    val localResolveVersion: Int
    val treeState: TreeState
    val resolveContext: ComponentContext
    val currentResult: ResolveResult?

    synchronized(this) {
      if (context.lithoConfiguration.componentsConfig.renderWithCurrentInputs) {
        // The root and tree props are read with the version and the state snapshot, not when the
        // render was requested: a resolve that runs late renders the tree as it is now, so commits
        // in version order never go back to an older root.
        root = _root ?: return
        treePropContainer = this.treePropContainer
        if (!isFromSyncLayout(source) && isUpToDate(root, treePropContainer)) {
          return
        }
      } else {
        root = requestedRoot ?: return
        treePropContainer = requestedTreePropContainer
      }

      localResolveVersion = nextResolveVersion++
      treeState = acquireTreeState()
      currentResult = committedResolveResult
      resolveContext = ComponentContext(context, treePropContainer)
    }

    if (
        root.builderContextName != null &&
            Component.getBuilderContextName(context.androidContext) != root.builderContextName
    ) {
      val message =
          "ComponentTree context is different from root builder context" +
              ", ComponentTree context=${Component.getBuilderContextName(context.androidContext)}" +
              ", root builder context=${root.builderContextName}" +
              ", root=${root.simpleName}" +
              ", ContextTree=${ComponentTreeDumpingHelper.dumpContextTree(this)}"
      ErrorReporter.report(
          LogLevel.ERROR,
          CT_CONTEXT_IS_DIFFERENT_FROM_ROOT_BUILDER_CONTEXT,
          message,
      )
    }

    // If sync operations are interruptible and happen on background thread, which could end
    // up being moved to UI thread with null result and causing crash later. To prevent that
    // we mark it as Non-Interruptible.
    val isInterruptible = !isFromSyncLayout(source)

    val initialStateScope = InitialStateScope.forResolve(treeState)
    val treeFuture = ResolveTreeFuture(
        resolveContext,
        root,
        treeState,
        currentResult,
        localResolveVersion,
        isInterruptible,
        id,
        extraAttribution,
        source,
        debugLogBreadcrumb,
    )

    val resolveResultHolder =
        TreeFuture.trackRunAndCommit(
            treeFuture,
            resolveResultFutures,
            source,
            resolveResultFutureLock,
            futureExecutionListener,
            initialStateScope,
        ) { result ->
          commitResolveResult(result)
          committedResolveEffects.drain()
        }

    val resolveResult = resolveResultHolder.result

    if (resolveResult == null) {
      val isWaitingButInterrupted = TreeFuture.FutureState.WAITING == resolveResultHolder.state
      val isLatestRequest: Boolean
      // To check if this is the latest resolve request and we don't want to get lost of it.
      synchronized(this) { isLatestRequest = localResolveVersion == nextResolveVersion - 1 }
      if (isWaitingButInterrupted && isLatestRequest) {
        // If we found any interrupted async resolve task which is also the latest request,
        // which means the current resolve request might be getting lost and we need to re-try
        // it to make sure there's not render in flight.
        requestRenderWithSplitFutures(
            true,
            output,
            source,
            extraAttribution,
            widthSpec,
            heightSpec,
            root,
            treePropContainer,
        )
      }
    } else {
      requestLayoutWithSplitFutures(
          resolveResult,
          output,
          source,
          extraAttribution,
          // Don't post when using the same thread handler, as it could cause heavy delays
          true,
          widthSpec,
          heightSpec,
      )
    }
  }

  /**
   * Whether the tree already shows [root] with [treePropContainer]: the committed resolve rendered
   * them, no state update is pending, and its layout is committed at the current size.
   */
  @GuardedBy("this")
  private fun isUpToDate(root: Component, treePropContainer: TreePropContainer?): Boolean {
    val resolveResult = committedResolveResult ?: return false
    val layoutState = committedLayoutState ?: return false
    return resolveResult.component === root &&
        layoutState.resolveResult === resolveResult &&
        isCompatibleSpec(layoutState, widthSpec, heightSpec) &&
        getCommittedTreeStateSync()?.hasUncommittedUpdates() != true &&
        equals(resolveResult.context.treePropContainer, treePropContainer)
  }

  @GuardedBy("this")
  private fun hasSize(): Boolean =
      widthSpec != SIZE_UNINITIALIZED || heightSpec != SIZE_UNINITIALIZED

  /**
   * Commits [resolveResult] if it is newer than the committed one, and reports it to the committed
   * resolve effects under the same lock, so they see results in commit order.
   */
  @Synchronized
  private fun commitResolveResult(resolveResult: ResolveResult) {
    val outcome =
        when {
          committedResolveResult == null ||
              (committedResolveResult?.version ?: Int.MIN_VALUE) < resolveResult.version ->
              CommitOutcome.COMMITTED
          committedResolveResult === resolveResult -> CommitOutcome.ALREADY_COMMITTED
          else -> CommitOutcome.REJECTED
        }
    committedResolveEffects.report(
        resolveResult.outputs?.resolveEffects,
        resolveResult.discardCallbacks,
        resolveResult.outputs?.discardKeys,
        outcome,
        resolveResult.version,
        resolveResultFutures,
        resolveResultFutureLock,
    )
    if (outcome == CommitOutcome.COMMITTED) {
      committedResolveResult = resolveResult

      getCommittedTreeStateSync()
          ?.commitResolveState(
              resolveResult.treeState,
              trackForPromotion = context.isReadTrackingEnabled,
              resolveVersion = resolveResult.version,
          )
    }
  }

  private fun requestLayoutWithSplitFutures(
      resolveResult: ResolveResult,
      output: Size?,
      @RenderSource source: Int,
      extraAttribution: String?,
      forceSyncCalculation: Boolean,
      widthSpec: Int,
      heightSpec: Int,
  ) {
    val isAsync = !isFromSyncLayout(source)

    if (isAsync && output != null) {
      throw IllegalStateException(
          "Cannot generate output for async layout calculation (source = $source)",
      )
    }

    synchronized(currentDoLayoutRunnableLock) {
      currentDoLayoutRunnable?.let { runnable ->
        layoutThreadHandler?.remove(runnable)
        currentDoLayoutRunnable = null
      }
    }

    if (isAsync && !forceSyncCalculation) {
      val layoutThreadHandler = this.layoutThreadHandler
      if (layoutThreadHandler != null) {
        synchronized(currentDoLayoutRunnableLock) {
          currentDoLayoutRunnable =
              DoLayoutRunnable(resolveResult, source, widthSpec, heightSpec, extraAttribution)

          var tag = EMPTY_STRING
          if (layoutThreadHandler.isTracing()) {
            tag = "doLayout "
            _root?.let { root -> tag = tag + root.simpleName }
          }
          currentDoLayoutRunnable?.let { runnable -> layoutThreadHandler.post(runnable, tag) }
        }
      }
    } else {
      doLayout(resolveResult, output, source, extraAttribution, widthSpec, heightSpec)
    }
  }

  private fun doLayout(
      resolveResult: ResolveResult,
      output: Size?,
      @RenderSource source: Int,
      extraAttribution: String?,
      widthSpec: Int,
      heightSpec: Int,
  ) {
    val layoutVersion: Int
    val currentLayoutState: LayoutState?
    val currentDiffNode: DiffNode?
    val treePropContainer: TreePropContainer?
    val layoutTreeState: TreeState
    var layoutWidthSpec = widthSpec
    var layoutHeightSpec = heightSpec

    synchronized(this) {
      currentLayoutState = committedLayoutState
      val previousLayout = currentLayoutState
      // A layout-only pass (same resolve, new size specs) continues from the last committed
      // layout, which holds layout-scoped state created since the resolve snapshot was taken.
      layoutTreeState =
          if (previousLayout != null && previousLayout.resolveResult === resolveResult) {
            TreeState.forLayoutOnlyPass(previousLayout.treeState, resolveResult.treeState)
          } else {
            TreeState(resolveResult.treeState)
          }
      currentDiffNode = currentLayoutState?.diffTree
      layoutVersion = nextLayoutVersion++
      treePropContainer = resolveResult.context.treePropContainer
      // Likewise the size: the committed resolve is laid out at the size the tree has now, which is
      // the only size commitLayoutState accepts.
      if (
          context.lithoConfiguration.componentsConfig.renderWithCurrentInputs &&
              output == null &&
              resolveResult === committedResolveResult &&
              hasSize()
      ) {
        layoutWidthSpec = this.widthSpec
        layoutHeightSpec = this.heightSpec
      }
    }

    if (layoutWidthSpec == SIZE_UNINITIALIZED && layoutHeightSpec == SIZE_UNINITIALIZED) {
      return
    }

    val initialStateScope = InitialStateScope.forLayout(layoutTreeState)
    val layoutTreeFuture = LayoutTreeFuture(
        resolveResult,
        currentLayoutState,
        currentDiffNode,
        layoutWidthSpec,
        layoutHeightSpec,
        id,
        layoutTreeState,
        layoutVersion,
        source,
        debugLogBreadcrumb,
    )

    TreeFuture.trackRunAndCommit(
        layoutTreeFuture,
        layoutTreeFutures,
        source,
        layoutStateFutureLock,
        futureExecutionListener,
        initialStateScope,
    ) { layoutState ->
      if (output != null) {
        output.width = layoutState.width
        output.height = layoutState.height
      }

      val committedResolveResult: ResolveResult?
      synchronized(this) { committedResolveResult = this.committedResolveResult }
      if (resolveResult === committedResolveResult) {
        commitLayoutState(
            layoutState,
            layoutVersion,
            source,
            extraAttribution,
            treePropContainer,
            resolveResult.component,
        )
      } else {
        layoutState.discardCallbacks?.onDiscarded()
      }
    }
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  internal fun setFutureExecutionListener(
      futureExecutionListener: TreeFuture.FutureExecutionListener?,
  ) {
    this.futureExecutionListener = futureExecutionListener
  }

  private fun commitLayoutState(
      layoutState: LayoutState,
      layoutVersion: Int,
      @RenderSource source: Int,
      extraAttribution: String?,
      treePropContainer: TreePropContainer?,
      rootComponent: Component,
  ) {
    var measureListeners: List<MeasureListener>? = null

    var rootWidth = 0
    var rootHeight = 0
    var committedNewLayout = false
    synchronized(this) {
      // We don't want to compute, layout, or reduce trees while holding a lock. However this means
      // that another thread could compute a layout and commit it before we get to this point. To
      // handle this, we make sure that the committed setRootId is only ever increased, meaning
      // we only go "forward in time" and will eventually get to the latest layout.
      // TODO(t66287929): Remove isCommitted check by only allowing one LayoutStateFuture at a time
      if (
          layoutVersion > committedLayoutVersion &&
              !layoutState.isCommitted() &&
              isCompatibleSpec(layoutState, this.widthSpec, this.heightSpec)
      ) {
        committedLayoutVersion = layoutVersion
        layoutState.toRenderTree()
        committedLayoutState = layoutState
        layoutState.markCommitted()
        committedNewLayout = true
        DebugEventDispatcher.dispatch(LithoDebugEvent.LayoutCommitted, { id.toString() }) {
            attributes ->
          attributes[DebugEventAttribute.Version] = layoutVersion
          attributes[DebugEventAttribute.Source] = layoutSourceToString(source)
          attributes[DebugEventAttribute.Width] = layoutState.width
          attributes[DebugEventAttribute.Height] = layoutState.height
        }
      } else if (layoutVersion <= committedLayoutVersion) {
        // Layout was computed but is stale (older version already committed)
        DebugEventDispatcher.dispatch(LithoDebugEvent.AsyncLayoutDropped, { id.toString() }) {
            attributes ->
          attributes[DebugEventAttribute.Version] = layoutVersion
          attributes[DebugEventAttribute.Source] = layoutSourceToString(source)
          attributes[LithoDebugEventAttributes.CommittedVersion] = committedLayoutVersion
          attributes[Root] = rootComponent.fullyQualifiedName
          attributes[LithoDebugEventAttributes.LayoutVersion] = layoutVersion
          attributes[LithoDebugEventAttributes.ResolveVersion] = layoutState.resolveResult.version
          attributes[LithoDebugEventAttributes.ComponentCount] =
              layoutState.resolveResult.node?.scopedComponentInfos?.size ?: 0
        }
      }

      if (DEBUG_LOGS) {
        logFinishLayout(source, extraAttribution, layoutState, committedNewLayout)
      }

      committedLayoutEffects.report(
          layoutState.resolveEffects,
          layoutState.discardCallbacks,
          layoutState.discardKeys,
          when {
            committedNewLayout -> CommitOutcome.COMMITTED
            layoutState.isCommitted() -> CommitOutcome.ALREADY_COMMITTED
            else -> CommitOutcome.REJECTED
          },
          layoutVersion,
          layoutTreeFutures,
          layoutStateFutureLock,
      )

      val stateToCommit = layoutState.treeState
      if (committedNewLayout) {
        getCommittedTreeStateSync()
            ?.commitLayoutState(
                stateToCommit,
                trackForPromotion = context.isReadTrackingEnabled,
                resolveVersion = layoutState.resolveResult.version,
            )

        if (this.measureListeners != null) {
          rootWidth = layoutState.width
          rootHeight = layoutState.height
        }

        bindHandlesToComponentTree(this, layoutState)

        measureListeners = this.measureListeners?.toList()
      }
    }

    committedLayoutEffects.drain()

    if (committedNewLayout) {
      measureListeners?.forEach { measureListener ->
        measureListener.onSetRootAndSizeSpec(
            layoutVersion,
            rootWidth,
            rootHeight,
            source == RenderSource.UPDATE_STATE_ASYNC || source == RenderSource.UPDATE_STATE_SYNC,
        )
      }

      postBackgroundLayoutStateUpdated()

      if (preAllocator != null) {
        var tag = EMPTY_STRING
        if (preAllocator?.isHandlerTracing == true) {
          tag = "preallocateLayout ${rootComponent.simpleName}"
        }
        preAllocator?.execute(tag)
      }
    }
  }

  /**
   * Transfers mCommittedLayoutState to mMainThreadLayoutState. This will proxy to the main thread
   * if necessary.
   */
  private fun postBackgroundLayoutStateUpdated() {
    // Remove the job from the queue to avoid unnecessary cost
    mainThreadHandler.remove(backgroundLayoutStateUpdateRunnable)

    if (isMainThread) {
      // We need to possibly update mMainThreadLayoutState. This call will
      // cause the host view to be invalidated and re-laid out, if necessary.
      backgroundLayoutStateUpdated()
    } else {
      // If we aren't on the main thread, we send a message to the main thread
      // to invoke backgroundLayoutStateUpdated.
      var tag = EMPTY_STRING
      if (mainThreadHandler.isTracing()) {
        tag = "postBackgroundLayoutStateUpdated"
      }
      val config = context.lithoConfiguration.componentsConfig
      if (
          config.usePostAtFrontForLayoutPromotion &&
              (!config.usePostAtFrontForLayoutPromotionOnlyFirstLayout || !hasPromotedFirstLayout)
      ) {
        hasPromotedFirstLayout = true
        mainThreadHandler.postAtFront(backgroundLayoutStateUpdateRunnable, tag)
      } else {
        mainThreadHandler.post(backgroundLayoutStateUpdateRunnable, tag)
      }
    }
  }

  private fun logFinishLayout(
      source: Int,
      extraAttribution: String?,
      localLayoutState: LayoutState,
      committedNewLayout: Boolean,
  ) {
    val message = if (committedNewLayout) "Committed layout" else "Did NOT commit layout"
    debugLog(
        "FinishLayout",
        "$message - Source: ${layoutSourceToString(source)}, Extra: $extraAttribution, WidthSpec: ${SizeSpec.toSimpleString(getWidthSpec(localLayoutState.sizeConstraints))}, HeightSpec: ${SizeSpec.toSimpleString(getHeightSpec(localLayoutState.sizeConstraints))}, Width: ${localLayoutState.width}, Height: ${localLayoutState.height}",
        localLayoutState.rootComponent,
    )
  }

  /**
   * The contract is that in order to release a ComponentTree, you must do so from the main thread.
   * Usually HostView will handle releasing, but if you never attach to a host view, then you should
   * call release yourself.
   */
  fun release() {
    assertMainThread()
    if (_lithoView?.isMounting == true) {
      throw IllegalStateException("Releasing a ComponentTree that is currently being mounted")
    }

    val wasReleased = synchronized(this) { released }
    val shouldRetainLithoSaveableStateRegistry =
        synchronized(this) {
          !released &&
              lithoSaveableStateRegistry != null &&
              (hasRetainedLithoSaveableStateRegistry || retainedLithoSaveableStateRegistryAcquired)
        }
    if (shouldRetainLithoSaveableStateRegistry) {
      lithoSaveableStateRegistry?.retainActiveValues()
    }
    if (!wasReleased) {
      ownerSaveableStateBinding?.onRelease()
    }

    val effectsHandler: EffectHandler?
    val shouldCloseLithoSaveableStateRegistry: Boolean
    val releasedCalculations = ArrayList<DiscardCallbacks>()
    synchronized(this) {
      batchedStateUpdatesStrategy.release()

      mainThreadHandler.remove(backgroundLayoutStateUpdateRunnable)

      synchronized(currentDoLayoutRunnableLock) {
        currentDoResolveRunnable?.let { runnable ->
          layoutThreadHandler?.remove(runnable)
          currentDoResolveRunnable = null
        }
      }

      synchronized(currentDoLayoutRunnableLock) {
        currentDoLayoutRunnable?.let { runnable ->
          layoutThreadHandler?.remove(runnable)
          currentDoLayoutRunnable = null
        }
      }

      synchronized(updateStateSyncRunnableLock) {
        updateStateSyncRunnable?.let { runnable ->
          layoutThreadHandler?.remove(runnable)
          updateStateSyncRunnable = null
        }
      }

      synchronized(resolveResultFutureLock) {
        for (rtf in resolveResultFutures) {
          rtf.release()
          releasedCalculations.add(rtf.discardCallbacks)
        }
        resolveResultFutures.clear()
      }

      synchronized(layoutStateFutureLock) {
        for (ltf in layoutTreeFutures) {
          ltf.release()
          releasedCalculations.add(ltf.discardCallbacks)
        }
        layoutTreeFutures.clear()
      }

      preAllocator?.cancel()

      if (_root != null) {
        _releasedComponent = _root?.simpleName ?: ""
      }
      _lithoView?.componentTree = null
      released = true
      _root = null
      saveableIdentityRoot = null

      // Clear mWorkingRangeStatusHandler before releasing LayoutState because we need them to help
      // dispatch OnExitRange events.
      clearWorkingRangeStatusHandler()

      // Execute detached callbacks if necessary.
      effectsHandler =
          if (uiTreeState != null) {
            val handler = uiTreeState?.effectsHandler
            uiTreeState?.clearEventHandlersAndTriggers()
            handler
          } else {
            null
          }

      // This should happen before setting mUiTreeState to null
      effectsHandler?.onCleanup()

      _mainThreadLayoutState = null
      committedLayoutState = null
      committedTreeState = null
      uiTreeState = null
      measureListeners = null
      committedResolveResult = null

      lithoVisibilityEventsController?.let { controller ->
        controller.removeListener(this)
        lithoVisibilityEventsController = null
      }

      shouldCloseLithoSaveableStateRegistry =
          lithoSaveableStateRegistry != null &&
              !hasRetainedLithoSaveableStateRegistry &&
              !retainedLithoSaveableStateRegistryAcquired
    }

    ComponentTreeRegistry.markReleased(this)

    if (context.lithoConfiguration.componentsConfig.detachStateUpdaterOnTreeRelease) {
      (stateUpdater as? StateUpdaterDelegator)?.detachStateUpdater(this)
    }

    if (shouldCloseLithoSaveableStateRegistry) {
      lithoSaveableStateRegistry?.close()
    }

    onReleaseListeners?.let { listeners ->
      for (listener in listeners) {
        listener.onReleased()
      }
    }
    onReleaseListeners = null

    // Calculations still running can never be committed now, and results reported from now on are
    // discarded.
    committedResolveEffects.detachAll(release = true, calculations = releasedCalculations)
    committedLayoutEffects.detachAll(release = true)
    try {
      committedResolveEffects.drain()
    } finally {
      committedLayoutEffects.drain()
    }
  }

  @Synchronized
  fun getSimpleName(): String? {
    return _root?.simpleName
  }

  /**
   * The persistent identity of this root tree: its saveable-state key, otherwise its root
   * component's class and manual key.
   */
  private fun saveableRootIdentity(): String? {
    saveableStateKey?.let {
      return ComponentTreeSaveableStateOwner.KEYED_IDENTITY_PREFIX + it
    }
    val root = saveableIdentityRoot ?: return null
    val typeName = Component.typeNameForId(root.typeId) ?: return null
    return if (root.hasManualKey()) "$typeName#${root.key}" else typeName
  }

  @Synchronized
  override fun getCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      isLayoutState: Boolean,
  ): Any? {
    if (released || uiTreeState == null) {
      return null
    }
    return uiTreeState?.getCachedValue(cacheKey, cachedValueInputs, isLayoutState)
  }

  @VisibleForTesting
  @JvmName("getEffectHandlerNotNull")
  fun getEffectHandler(): EffectHandler {
    return checkNotNull(uiTreeState).effectsHandler
  }

  /** Property accessor for effectHandler, for backward compatibility with test code. */
  @get:VisibleForTesting
  @Suppress("FieldsBelowInit")
  val effectHandler: EffectHandler?
    get() = uiTreeState?.effectsHandler

  @Synchronized
  override fun putCachedValue(
      cacheKey: HookGlobalKey,
      cachedValueInputs: Any,
      cachedValue: Any?,
      isLayoutState: Boolean,
  ) {
    if (released || uiTreeState == null) {
      return
    }
    uiTreeState?.putCachedValue(cacheKey, cachedValueInputs, cachedValue, isLayoutState)
  }

  override fun removePendingStateUpdate(key: GlobalKey, isLayoutState: Boolean) {
    val committedTreeState = getCommittedTreeStateSync()
    if (released || committedTreeState == null) {
      return
    }
    val stateId = StateId.forSpec(id, key)
    committedTreeState.removePendingStateUpdate(stateId, isLayoutState)
  }

  private fun createImplicitTreePropContainer(lifecycleOwner: LifecycleOwner): TreePropContainer {
    val treePropContainer = TreePropContainer()
    treePropContainer.put(LifecycleOwnerTreeProp, lifecycleOwner)
    lithoSaveableStateRegistry?.let { registry ->
      treePropContainer.put(LithoSaveableStateRegistryTreeProp, registry)
    }
    return treePropContainer
  }

  private fun getImplicitLifecycleOwner(inputTreeProps: TreePropContainer?): LifecycleOwner {
    val lifecycleOwner = inputTreeProps?.get(LifecycleOwnerTreeProp)

    return when {
      lifecycleOwner == null -> LifecycleOwnerWrapper(null)
      lifecycleOwner is LifecycleOwnerWrapper -> LifecycleOwnerWrapper(lifecycleOwner.getDelegate())
      else -> lifecycleOwner
    }
  }

  private fun createTreePropContainer(inputTreeProps: TreePropContainer?): TreePropContainer? {
    var treePropContainer = TreePropContainer.copy(parentTreePropContainer)
    if (inputTreeProps != null) {
      if (treePropContainer == null) {
        treePropContainer = TreePropContainer()
      }
      treePropContainer.putAll(inputTreeProps)
    }
    if (treePropContainer == null) {
      treePropContainer = TreePropContainer()
    }
    // The default tree props implicit to the component tree so we don't want them to be
    // overridden by the parent.
    val implicitContainer =
        requireNotNull(implicitTreePropContainer) { "implicitTreePropContainer should not be null" }
    treePropContainer.putAll(implicitContainer)

    return treePropContainer
  }

  /**
   * Whether the async layout a compatible measure would request duplicates work already covered:
   * the committed resolve is for the current root and the tree already targets these specs, so any
   * pending render produces this layout.
   */
  @GuardedBy("this")
  private fun isAsyncMeasureLayoutRedundant(
      mainThreadLayoutState: LayoutState,
      widthSpec: Int,
      heightSpec: Int,
  ): Boolean =
      context.lithoConfiguration.componentsConfig.skipRedundantAsyncMeasureLayout &&
          committedResolveResult?.component === _root &&
          this.widthSpec == widthSpec &&
          this.heightSpec == heightSpec &&
          AccessibilityUtils.isAccessibilityEnabled(accessibilityManager) ==
              mainThreadLayoutState.isAccessibilityEnabled

  private fun isCompatibleSpec(
      layoutState: LayoutState?,
      widthSpec: Int,
      heightSpec: Int,
  ): Boolean {
    return layoutState != null &&
        layoutState.isCompatibleSpec(widthSpec, heightSpec) &&
        AccessibilityUtils.isAccessibilityEnabled(accessibilityManager) ==
            layoutState.isAccessibilityEnabled
  }

  private fun isCompatibleComponentAndSpec(
      layoutState: LayoutState?,
      componentId: Int,
      widthSpec: Int,
      heightSpec: Int,
  ): Boolean {
    return layoutState != null &&
        layoutState.isCompatibleComponentAndSpec(componentId, widthSpec, heightSpec) &&
        AccessibilityUtils.isAccessibilityEnabled(accessibilityManager) ==
            layoutState.isAccessibilityEnabled
  }

  @get:Synchronized
  @Suppress("FieldsBelowInit")
  override val isReleased: Boolean
    get() = released

  @Synchronized
  internal fun getReleasedComponent(): String {
    return _releasedComponent
  }

  @Suppress("FieldsBelowInit")
  override val mountedView: View?
    get() = _lithoView

  override fun onErrorComponent(component: Component?) {
    setRoot(component)
  }

  @Suppress("FieldsBelowInit")
  val logTag: String?
    get() = context.lithoConfiguration.componentsConfig.logTag

  /**
   * The layouts which this ComponentTree was currently calculating will be terminated before a
   * valid result is computed. It's not safe to try to compute any layouts for this ComponentTree
   * after that because it's in an incomplete state, so it needs to be released.
   */
  fun cancelLayoutAndReleaseTree() {
    if (ThreadUtils.isMainThread) {
      release()
    } else {
      mainThreadHandler.post(this::release, "Release")
    }
  }

  override fun addOnReleaseListener(listener: LithoTreeLifecycleProvider.OnReleaseListener) {
    assertMainThread()
    if (onReleaseListeners == null) {
      onReleaseListeners = ArrayList<LithoTreeLifecycleProvider.OnReleaseListener>()
    }
    onReleaseListeners?.add(listener)
  }

  override fun removeOnReleaseListener(listener: LithoTreeLifecycleProvider.OnReleaseListener) {
    assertMainThread()
    onReleaseListeners?.remove(listener)
  }

  private fun debugLog(eventName: String, info: String, root: Component?) {
    if (DEBUG_LOGS) {
      Log.d(
          "ComponentTreeDebug",
          "(${hashCode()}${if (debugLogBreadcrumb != null) "/$debugLogBreadcrumb" else ""}) [$eventName - Root: ${root?.simpleName}] $info",
      )
    }
  }

  override fun getUiStateReadRecords(): UiStateReadRecords {
    if (_lithoView == null) {
      throw IllegalStateException("Trying to get UI state read records without a set host view")
    }
    return requireNotNull(_lithoView) { "LithoView should not be null" }.uiStateReadRecords
  }

  @VisibleForTesting
  fun getEventHandlersController(): EventHandlersController {
    return checkNotNull(uiTreeState).eventHandlersController
  }

  @Synchronized
  fun subscribeToVisibilityEventsController(controller: LithoVisibilityEventsController) {
    if (lithoVisibilityEventsController != null) {
      throw IllegalStateException("Already subscribed")
    }

    lithoVisibilityEventsController = controller
    lithoVisibilityEventsController?.addListener(this)
  }

  fun setLifecycleOwnerTreeProp(owner: LifecycleOwner) {
    setLifecycleOwnerTreePropInternal(owner)
  }

  @Synchronized
  private fun setLifecycleOwnerTreePropInternal(owner: LifecycleOwner) {
    val treePropContainer =
        treePropContainer ?: throw NullPointerException("The treePropContainer cannot be null")
    val lifecycleOwner = treePropContainer.get(LifecycleOwnerTreeProp)
    if (lifecycleOwner is LifecycleOwnerWrapper) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        lifecycleOwner.setDelegate(owner)
      } else {
        mainThreadHandler.post(
            { lifecycleOwner.setDelegate(owner) },
            "LifecycleOwnerWrapper.setDelegate",
        )
      }
    } else {
      // Developers may still set the lifecycle owner on the Tree Prop directly.
      treePropContainer.put(LifecycleOwnerTreeProp, owner)
    }
  }

  @Synchronized
  fun isSubscribedToVisibilityEventsController(): Boolean {
    return lithoVisibilityEventsController != null
  }

  override fun onMovedToState(state: LithoVisibilityState) {
    when (state) {
      LithoVisibilityState.HINT_VISIBLE -> onMoveToStateHintVisible()
      LithoVisibilityState.HINT_INVISIBLE -> onMoveToStateHintInvisible()
      LithoVisibilityState.DESTROYED -> onMoveToStateDestroy()
    }
  }

  private fun onMoveToStateHintVisible() {
    _lithoView?.let { view ->
      view.setVisibilityHintNonRecursive(true, VisibilityChangeReason.LIFECYCLE)
    }
  }

  private fun onMoveToStateHintInvisible() {
    _lithoView?.let { view ->
      view.setVisibilityHintNonRecursive(false, VisibilityChangeReason.LIFECYCLE)
    }
  }

  private fun onMoveToStateDestroy() {
    release()
  }

  /**
   * In this approach we are attempting to start the layout calculation using the Choreographer
   * frame callbacks system.
   *
   * Whenever the Choreographer receives a VSYNC signal, it starts a cycle to prepare the next
   * Frame. In this cycle is goes through 3 main phases: input handling, animation and traversals
   * (which layouts, measures and draws).
   *
   * Fortunately, the Choreographer API provides a way to execute code during the next animation
   * phase of the processing.
   *
   * With this knowledge, this new variant to batch state updates will schedule the layout
   * calculation start on the Choregrapher's animation phase. This way we can guarantee that all
   * states generated by input handling are properly enqueued before we start the layout
   * calculation.
   */
  @SuppressLint("ReflectionMethodUse")
  internal inner class PostStateUpdateToChoreographerCallback : BatchedStateUpdatesStrategy {

    private val mainChoreographer = AtomicReference<Choreographer>()

    private val enqueuedUpdatesCount = AtomicInteger(0)
    private val attribution = AtomicReference("")

    private val frameCallback = Choreographer.FrameCallback {
      val attribution = this.attribution.getAndSet("")

      if (enqueuedUpdatesCount.getAndSet(0) > 0) {
        val componentScope = context.componentScope
        updateStateInternal(
            true,
            attribution ?: "<cls>${componentScope?.javaClass?.name ?: "null"}</cls>",
        )
      }
    }

    private val createMainChoreographerRunnable = Runnable {
      try {
        mainChoreographer.set(Choreographer.getInstance())
      } catch (_: NullPointerException) {
        // MIUI's TurboSchedMonitorImpl.addTid() crashes with NPE inside the Choreographer
        // constructor on some Xiaomi/Redmi devices. Gracefully degrade: state updates will
        // still work correctly but won't be batched via the choreographer frame callback.
        return@Runnable
      }

      if (enqueuedUpdatesCount.get() > 0) {
        mainChoreographer.get()?.postFrameCallback(frameCallback)
      }
    }

    init {
      initializeMainChoreographer()
    }

    private fun initializeMainChoreographer() {
      if (mainChoreographer.get() != null) return

      if (Looper.myLooper() == Looper.getMainLooper()) {
        try {
          mainChoreographer.set(Choreographer.getInstance())
        } catch (_: NullPointerException) {
          // MIUI's TurboSchedMonitorImpl.addTid() crashes with NPE inside the Choreographer
          // constructor on some Xiaomi/Redmi devices. Gracefully degrade: state updates will
          // still work correctly but won't be batched via the choreographer frame callback.
        }
      } else {
        scheduleChoreographerCreation()
      }
    }

    override fun onAsyncStateUpdateEnqueued(
        attribution: String?,
        isCreateLayoutInProgress: Boolean,
    ): Boolean {
      if (enqueuedUpdatesCount.getAndIncrement() == 0 && mainChoreographer.get() != null) {
        this.attribution.set(attribution)
        mainChoreographer.get()?.postFrameCallback(frameCallback)
      }

      return true
    }

    override fun onInternalStateUpdateStart() {
      resetEnqueuedUpdates()
      removeFrameCallback()
    }

    override fun release() {
      resetEnqueuedUpdates()
      removeChoreographerCreation()
      removeFrameCallback()
    }

    private fun resetEnqueuedUpdates() {
      enqueuedUpdatesCount.set(0)
    }

    private fun removeChoreographerCreation() {
      mainThreadHandler.remove(createMainChoreographerRunnable)
    }

    private fun removeFrameCallback() {
      mainChoreographer.get()?.removeFrameCallback(frameCallback)
    }

    private fun scheduleChoreographerCreation() {
      mainThreadHandler.postAtFront(createMainChoreographerRunnable, "Create Main Choreographer")
    }
  }

  fun interface MeasureListener {
    /**
     * This callback gets called every time a ComponentTree commits a new layout computation. The
     * call is executed on the same thread that computed the newly committed layout but outside the
     * commit lock. This means that in practice the calls are not guaranteed to be ordered. A layout
     * X committed before a layout Y could end up executing its MeasureListener's callback after the
     * callback of layout Y. Clients that need guarantee over the ordering can rely on the
     * layoutVersion parameter that is guaranteed to be increasing for successive commits (in the
     * example layout X callback will receive a layoutVersion that is lower than the layoutVersion
     * for layout Y)
     *
     * @param layoutVersion the layout version associated with the layout that triggered this
     *   callback
     * @param width the resulting width from the committed layout computation
     * @param height the resulting height from the committed layout computation
     * @param stateUpdate whether this layout computation was triggered by a state update.
     */
    fun onSetRootAndSizeSpec(layoutVersion: Int, width: Int, height: Int, stateUpdate: Boolean)
  }

  /**
   * Listener that will be notified when a new LayoutState is computed and ready to be committed to
   * this ComponentTree.
   */
  fun interface NewLayoutStateReadyListener {
    fun onNewLayoutStateReady(componentTree: ComponentTree)

    /** Whether promotion without mount work should still notify this listener. */
    val dispatchOnPromotedLayout: Boolean
      get() = false
  }

  /** A builder class that can be used to create a [ComponentTree]. */
  class Builder internal constructor(context: ComponentContext) {

    internal var root: Component? = null

    internal var androidContext: Context

    internal var config: ComponentsConfiguration

    private var incrementalMountEnabled: Boolean? = null
    internal var layoutThreadHandler: RunnableHandler? = null
    internal var treeState: TreeState? = null
    internal var overrideComponentTreeId: Int = INVALID_ID
    internal var measureListener: MeasureListener? = null
    internal var lithoVisibilityEventsController: LithoVisibilityEventsController? = null
    internal var lifecycleOwner: LifecycleOwner? = null
    internal var savedStateRegistryOwner: SavedStateRegistryOwner? = null
    internal var retainedLithoSaveableStateRegistry: LithoSaveableStateRegistry? = null
    internal var nestedTreeSaveableStateKey: String? = null
    internal var renderUnitIdGenerator: RenderUnitIdGenerator? = null

    internal val treePropContainer: TreePropContainer?
    @Deprecated("") internal val parentTreePropContainer: TreePropContainer?

    internal var stateUpdater: StateUpdater? = null

    internal var poolScope: PoolScope = context.getTreeProp(PoolScopeTreeProp)

    internal var saveableStateKey: String? = null

    init {
      config = context.lithoConfiguration.componentsConfig
      treePropContainer = context.treePropContainer
      parentTreePropContainer = context.parentTreePropContainer
      androidContext = context.androidContext
    }

    /**
     * Configures the tree with the specified configuration.
     *
     * Use this method to set all configurations for components, including incremental mount, using
     * the ComponentsConfiguration object.
     *
     * @param config the [ComponentsConfiguration] object containing the configs to be used.
     */
    fun componentsConfiguration(config: ComponentsConfiguration): Builder {
      this.config = config
      return this
    }

    /**
     * Specify root for the component tree
     *
     * IMPORTANT: If you do not set this, a default root will be set and you can reset root after
     * build and attach of the component tree
     */
    fun withRoot(root: Component): Builder {
      this.root = root
      return this
    }

    fun withLithoVisibilityEventsController(controller: LithoVisibilityEventsController?): Builder {
      lithoVisibilityEventsController = controller
      return this
    }

    fun withLifecycleOwnerTreeProp(lifecycleOwner: LifecycleOwner?): Builder {
      this.lifecycleOwner = lifecycleOwner
      return this
    }

    /** Retains a nested tree's registry while its framework owner replaces the ComponentTree. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun withRetainedSaveableStateRegistryForTreeReplacement(
        registry: LithoSaveableStateRegistry,
    ): Builder {
      retainedLithoSaveableStateRegistry = registry
      return this
    }

    /** Supplies the stable identity used to save this nested tree inside its parent registry. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun withNestedTreeSaveableStateKey(key: String): Builder {
      require(key.isNotEmpty()) { "A nested ComponentTree saveable-state key cannot be empty" }
      nestedTreeSaveableStateKey = key
      return this
    }

    /**
     * @param isEnabled a boolean value to enable or disable incremental mount.
     * @deprecated This method usage is to be replaced by the [componentsConfiguration] method.
     *
     * This method is deprecated because the configuration of the tree should now be handled through
     * the [ComponentsConfiguration] object, allowing for more centralized and flexible
     * configuration.
     */
    @Deprecated(
        "Set ComponentsConfiguration.incrementalMountEnabled and pass it via componentsConfiguration() instead.",
    )
    fun incrementalMount(isEnabled: Boolean): Builder {
      incrementalMountEnabled = isEnabled
      return this
    }

    /**
     * Specify the looper to use for running layouts on. Note that in rare cases layout must run on
     * the UI thread. For example, if you rotate the screen, we must measure on the UI thread. If
     * you don't specify a Looper here, the Components default Looper will be used.
     */
    fun layoutThreadLooper(looper: Looper?): Builder {
      if (looper != null) {
        layoutThreadHandler = DefaultHandler(looper)
      }
      return this
    }

    /**
     * Specify the looper to use for running layouts on. Note that in rare cases layout must run on
     * the UI thread. For example, if you rotate the screen, we must measure on the UI thread. If
     * you don't specify a Looper here, the Components default Looper will be used.
     */
    fun layoutThreadHandler(handler: RunnableHandler?): Builder {
      layoutThreadHandler = handler
      return this
    }

    /**
     * Specify an initial tree state object that the ComponentTree can use to set the current values
     * for states.
     */
    fun treeState(treeState: TreeState?): Builder {
      this.treeState = treeState
      return this
    }

    /**
     * Gives the ability to override the auto-generated ComponentTree id: this is generally not
     * useful in the majority of circumstances, so don't use it unless you really know what you're
     * doing.
     */
    fun overrideComponentTreeId(overrideComponentTreeId: Int): Builder {
      this.overrideComponentTreeId = overrideComponentTreeId
      return this
    }

    /**
     * This should not be used in majority of cases.
     *
     * @param renderUnitIdGenerator to override the [ComponentTree] renderUnitIgGenerator.
     */
    fun overrideRenderUnitIdMap(
        renderUnitIdGenerator: RenderUnitIdGenerator?,
        overrideComponentTreeId: Int,
    ): Builder {
      this.renderUnitIdGenerator = renderUnitIdGenerator
      this.overrideComponentTreeId = overrideComponentTreeId
      return this
    }

    fun measureListener(measureListener: MeasureListener?): Builder {
      this.measureListener = measureListener
      return this
    }

    fun stateUpdater(stateUpdater: StateUpdater?): Builder {
      this.stateUpdater = stateUpdater
      return this
    }

    @ExperimentalLithoApi
    fun poolScope(poolScope: PoolScope): Builder {
      this.poolScope = poolScope
      return this
    }

    /**
     * Names this root tree for `useSaveableState` under the [SavedStateRegistryOwner] passed to
     * [create]. The key replaces the root component's class as the tree's identity, so a screen can
     * save two trees with the same root, and the next tree with this key takes over its values even
     * when it replaces this tree while the screen is still visible. Nested trees ignore it.
     */
    @ExperimentalLithoApi
    fun withSaveableStateKey(key: String): Builder {
      require(key.isNotEmpty()) { "A ComponentTree saveable-state key cannot be empty" }
      saveableStateKey = key
      return this
    }

    /** Builds a [ComponentTree] using the parameters specified in this builder. */
    fun build(): ComponentTree {
      // Setting root to default to allow users to initialise without a root.
      if (root == null) {
        root = EmptyComponent()
      }

      val incrementalMountToUse = incrementalMountEnabled ?: config.incrementalMountEnabled

      val logTag = config.logTag

      config =
          ComponentsConfiguration.create(config)
              .logTag(logTag ?: root?.simpleName.orEmpty())
              .incrementalMountEnabled(
                  incrementalMountToUse &&
                      !LithoDebugConfigurations.isIncrementalMountGloballyDisabled,
              )
              .build()

      return ComponentTree(this)
    }
  }

  private inner class DoResolveRunnable(
      @RenderSource private val source: Int,
      private val root: Component,
      private val treePropContainer: TreePropContainer?,
      private val widthSpec: Int,
      private val heightSpec: Int,
      private val attribution: String?,
  ) : ThreadTracingRunnable() {

    override fun tracedRun() {
      doResolve(null, source, attribution, root, treePropContainer, widthSpec, heightSpec)
    }
  }

  private inner class DoLayoutRunnable(
      private val resolveResult: ResolveResult,
      @RenderSource private val source: Int,
      private val widthSpec: Int,
      private val heightSpec: Int,
      private val attribution: String?,
  ) : ThreadTracingRunnable() {

    override fun tracedRun() {
      doLayout(resolveResult, null, source, attribution, widthSpec, heightSpec)
    }
  }

  private inner class UpdateStateSyncRunnable(private val attribution: String?) :
      ThreadTracingRunnable() {

    override fun tracedRun() {
      updateStateInternal(false, attribution)
    }
  }

  class DefaultLayoutHandlerProvider : RunnableHandlerProvider {
    override fun getRunnableHandler(): RunnableHandler {
      return DefaultHandler(getDefaultLayoutThreadLooper())
    }
  }

  companion object {
    /**
     * When `true`, prints verbose tree lifecycle events to logcat under the `ComponentTreeDebug`
     * tag.
     */
    @JvmField var DEBUG_LOGS: Boolean = false

    const val INVALID_LAYOUT_VERSION: Int = -1
    const val INVALID_ID: Int = -1
    private const val TAG = "ComponentTree"
    const val SIZE_UNINITIALIZED: Int = -1
    internal const val DEFAULT_LAYOUT_THREAD_NAME = "ComponentLayoutThread"
    private const val EMPTY_STRING = ""
    private const val CT_CONTEXT_IS_DIFFERENT_FROM_ROOT_BUILDER_CONTEXT =
        "ComponentTree:CTContextIsDifferentFromRootBuilderContext"
    const val STATE_UPDATES_IN_LOOP_THRESHOLD: Int = 50

    // Do not access defaultLayoutThreadLooper directly, use getDefaultLayoutThreadLooper().
    @GuardedBy("ComponentTree::class")
    @Volatile
    private var defaultLayoutThreadLooper: Looper? = null

    private val syncStateUpdatesHandler: ThreadLocal<WeakReference<RunnableHandler>> = ThreadLocal()

    @JvmStatic
    fun create(context: ComponentContext): Builder {
      return Builder(context)
    }

    /** Creates a root ComponentTree hosted by [activity], which is also its context. */
    @JvmStatic fun create(activity: ComponentActivity): Builder = create(activity, activity)

    @JvmStatic
    fun create(activity: ComponentActivity, root: Component.Builder<*>): Builder =
        create(activity, root.build())

    @JvmStatic
    fun create(activity: ComponentActivity, root: Component?): Builder =
        create(activity).apply { root?.let(::withRoot) }

    /**
     * Creates a root ComponentTree hosted by [host], the Fragment or Activity that creates it.
     *
     * [host] is both the tree's lifecycle owner, exposed to components through
     * [LifecycleOwnerTreeProp], and its saved-state owner. A Fragment passes itself, not
     * `viewLifecycleOwner`, so it can create the tree as early as `onCreate`. [context] is the
     * context the tree renders with, which can differ from the host's, such as a themed wrapper.
     * Nested ComponentTrees should continue to use [createNestedComponentTree] so they inherit
     * these dependencies from their parent.
     */
    @JvmStatic
    fun create(context: Context, host: SavedStateRegistryOwner): Builder =
        create(ComponentContext(context), host)

    /**
     * Like `create(Context, SavedStateRegistryOwner)`, keeping the Litho configuration and
     * TreeProps already installed on [context].
     */
    @JvmStatic
    fun create(context: ComponentContext, host: SavedStateRegistryOwner): Builder =
        Builder(context).apply {
          withLifecycleOwnerTreeProp(host)
          savedStateRegistryOwner = host
        }

    @JvmStatic
    fun create(
        context: Context,
        host: SavedStateRegistryOwner,
        root: Component.Builder<*>,
    ): Builder = create(context, host, root.build())

    @JvmStatic
    fun create(context: Context, host: SavedStateRegistryOwner, root: Component?): Builder =
        create(context, host).apply { root?.let(::withRoot) }

    @JvmStatic
    fun create(
        context: ComponentContext,
        host: SavedStateRegistryOwner,
        root: Component.Builder<*>,
    ): Builder = create(context, host, root.build())

    @JvmStatic
    fun create(
        context: ComponentContext,
        host: SavedStateRegistryOwner,
        root: Component?,
    ): Builder = create(context, host).apply { root?.let(::withRoot) }

    @JvmStatic
    fun create(context: ComponentContext, root: Component.Builder<*>): Builder {
      return create(context, root.build())
    }

    @JvmStatic
    fun create(context: ComponentContext, root: Component?): Builder {
      val builder = Builder(context)

      if (root != null) {
        builder.withRoot(root)
      }
      return builder
    }

    private fun getOrCreateRunnableHandlerProvider(
        componentsConfiguration: ComponentsConfiguration,
    ): RunnableHandlerProvider {
      return componentsConfiguration.customLayoutHandlerProvider
    }

    private fun ensureAndInstrumentLayoutThreadHandler(
        runnableHandlerProvider: RunnableHandlerProvider,
        handler: RunnableHandler?,
    ): RunnableHandler {
      var currentHandler = handler
      if (currentHandler == null) {
        currentHandler = runnableHandlerProvider.getRunnableHandler()
      }
      return instrumentHandler(
          requireNotNull(currentHandler) { "currentHandler should not be null" },
      )
    }

    private fun hasSameRootContext(context1: Context, context2: Context): Boolean {
      return ContextUtils.getRootContext(context1) === ContextUtils.getRootContext(context2)
    }

    private fun specsToString(widthSpec: Int, heightSpec: Int): String {
      return "w: ${SizeSpec.toSimpleString(widthSpec)}, h: ${SizeSpec.toSimpleString(heightSpec)}"
    }

    @JvmStatic
    fun getLithoVisibilityEventsController(
        context: ComponentContext,
    ): LithoVisibilityEventsController? {
      return context.lithoVisibilityEventsController
    }

    /**
     * Creates a ComponentTree nested inside the ComponentTree of the provided parentContext. If the
     * parent ComponentTree is subscribed to a LithoVisibilityEventsController, the nested
     * ComponentTree will also subscribe to a [SimpleNestedTreeVisibilityEventsController] hooked
     * with the parent's visibility events controller.
     *
     * @param parentContext context associated with the parent ComponentTree.
     * @param component root of the new nested ComponentTree.
     * @return builder for a nested ComponentTree.
     */
    @JvmStatic
    fun createNestedComponentTree(parentContext: ComponentContext, component: Component?): Builder {
      val controller =
          if (parentContext.lithoVisibilityEventsController == null) {
            null
          } else {
            val controller =
                requireNotNull(parentContext.lithoVisibilityEventsController) {
                  "lithoVisibilityEventsController should not be null"
                }
            SimpleNestedTreeVisibilityEventsController(controller)
          }

      return create(ComponentContext.makeCopyForNestedTree(parentContext), component)
          .withLithoVisibilityEventsController(controller)
          .apply {
            stableNestedTreeSaveableStateKey(component)?.let(::withNestedTreeSaveableStateKey)
          }
    }

    @JvmStatic
    fun createNestedComponentTree(parentContext: ComponentContext): Builder {
      return createNestedComponentTree(parentContext, null)
    }

    @JvmStatic
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun stableNestedTreeSaveableStateKey(component: Component?): String? =
        component?.takeIf(Component::hasManualKey)?.let { keyedComponent ->
          buildString {
            append("component:")
            append(keyedComponent.fullyQualifiedName.length)
            append(':')
            append(keyedComponent.fullyQualifiedName)
            append(":key:")
            append(keyedComponent.key.length)
            append(':')
            append(keyedComponent.key)
          }
        }

    @SuppressLint("BadMethodUse-android.os.HandlerThread._Constructor")
    @JvmStatic
    fun getDefaultLayoutThreadLooper(): Looper {
      synchronized(ComponentTree::class.java) {
        if (defaultLayoutThreadLooper == null) {
          val defaultThread =
              HandlerThread(DEFAULT_LAYOUT_THREAD_NAME, DEFAULT_BACKGROUND_THREAD_PRIORITY)
          defaultThread.start()
          defaultLayoutThreadLooper = checkNotNull(defaultThread.looper)
        }

        return requireNotNull(defaultLayoutThreadLooper) {
          "defaultLayoutThreadLooper should not be null"
        }
      }
    }

    private fun bindHandlesToComponentTree(componentTree: ComponentTree, layoutState: LayoutState) {
      layoutState.componentHandles?.forEach { handle ->
        handle.setStateUpdaterAndRootViewReference(componentTree, componentTree)
      }
    }

    private fun flash(view: View) {
      val d: Drawable = PaintDrawable(Color.RED)
      d.alpha = 128
      view.post {
        d.setBounds(0, 0, view.width, view.height)
        view.overlay.add(d)
        view.postDelayed({ view.overlay.remove(d) }, 500)
      }
    }
  }
}
