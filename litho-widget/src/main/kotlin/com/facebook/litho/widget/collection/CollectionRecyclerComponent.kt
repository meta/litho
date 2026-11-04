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

package com.facebook.litho.widget.collection

import android.content.Context
import android.graphics.Color
import android.os.HandlerThread
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SnapHelper
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentUtils
import com.facebook.litho.ComponentsSystrace
import com.facebook.litho.LithoExtraContextForLayoutScope
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.LithoSaveableStateRegistryTreeProp
import com.facebook.litho.LithoStartupLogger
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Ref
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.choreographercompat.ChoreographerCompat
import com.facebook.litho.choreographercompat.ChoreographerCompatImpl
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.RunnableHandlerProvider
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.debug.LithoDebugEventAttributes
import com.facebook.litho.effects.useEffect as useLayoutEffect
import com.facebook.litho.onCleanup
import com.facebook.litho.saveableStateComponentKey
import com.facebook.litho.sections.widget.GridRecyclerConfiguration
import com.facebook.litho.sections.widget.RecyclerConfiguration
import com.facebook.litho.sections.widget.StaggeredGridRecyclerConfiguration
import com.facebook.litho.useCached
import com.facebook.litho.useCallback
import com.facebook.litho.useEffect
import com.facebook.litho.useRef
import com.facebook.litho.useState
import com.facebook.litho.useStateWithDeps
import com.facebook.litho.widget.ComponentRenderInfo
import com.facebook.litho.widget.GridLayoutInfo
import com.facebook.litho.widget.ItemDecorationWithMeasureFunction
import com.facebook.litho.widget.LayoutInfo
import com.facebook.litho.widget.LithoRecyclerView.OnAfterLayoutListener
import com.facebook.litho.widget.LithoRecyclerView.OnBeforeLayoutListener
import com.facebook.litho.widget.LithoRecyclerView.TouchInterceptor
import com.facebook.litho.widget.Recycler.Companion.createSectionsRecyclerView
import com.facebook.litho.widget.RecyclerEventsController
import com.facebook.litho.widget.RenderInfo
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.litho.widget.SectionsRecyclerView.SectionsRecyclerViewLogger
import com.facebook.litho.widget.SnapUtil
import com.facebook.litho.widget.SnapUtil.SnapMode
import com.facebook.litho.widget.ViewportInfo.ViewportChanged
import com.facebook.litho.widget.bindLegacyAttachBinder
import com.facebook.litho.widget.bindLegacyMountBinder
import com.facebook.litho.widget.requireLithoRecyclerView
import com.facebook.litho.widget.unbindLegacyAttachBinder
import com.facebook.litho.widget.unbindLegacyMountBinder
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.PoolScope
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.RunnableHandler.DefaultHandler
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventDispatcher
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.thread.utils.ThreadUtils
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.toMutableList
import kotlin.math.max

/** A component that renders a list of items using a [RecyclerView]. */
@OptIn(ExperimentalLithoApi::class)
class CollectionRecyclerComponent(
    private val children: List<CollectionChild>,
    private val componentRenderer: (index: Int, model: CollectionChild) -> RenderInfo,
    private val contentComparator:
        (previousItem: CollectionChild, nextItem: CollectionChild) -> Boolean,
    private val idComparator: (previousItem: CollectionChild, nextItem: CollectionChild) -> Boolean,
    private val recyclerConfiguration: RecyclerConfiguration,
    private val clipChildren: Boolean = true,
    private val clipToPadding: Boolean = true,
    private val bottomPadding: Int = 0,
    private val endPadding: Int = 0,
    private val fadingEdgeLength: Int = 0,
    private val horizontalFadingEdgeEnabled: Boolean = false,
    private val isBottomFadingEnabled: Boolean = true,
    private val isLeftFadingEnabled: Boolean = true,
    private val isRightFadingEnabled: Boolean = true,
    private val isTopFadingEnabled: Boolean = true,
    private val itemAnimator: RecyclerView.ItemAnimator? = null,
    private val itemDecoration: RecyclerView.ItemDecoration? = null,
    private val itemTouchListener: RecyclerView.OnItemTouchListener? = null,
    private val lazyCollectionController: LazyCollectionController? = null,
    private val nestedScrollingEnabled: Boolean = true,
    private val onAfterLayoutListener: OnAfterLayoutListener? = null,
    private val onBeforeLayoutListener: OnBeforeLayoutListener? = null,
    private val onDataBound: OnDataBound? = null,
    private val onDataRendered:
        ((
            isDataChanged: Boolean,
            isMounted: Boolean,
            monoTimestampMs: Long,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
        ) -> Unit)? =
        null,
    private val onNearEnd: OnNearCallback? = null,
    private val onPullToRefresh: (() -> Unit)? = null,
    private val onScrollListeners: List<RecyclerView.OnScrollListener?>? = null,
    private val onViewportChanged:
        ((
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
            totalCount: Int,
            firstFullyVisibleIndex: Int,
            lastFullyVisibleIndex: Int,
        ) -> Unit)? =
        null,
    private val overScrollMode: Int = View.OVER_SCROLL_ALWAYS,
    private val pullToRefreshEnabled: Boolean = false,
    private val recyclerViewId: Int = View.NO_ID,
    @param:ColorInt private val refreshProgressBarBackgroundColor: Int? = null,
    @param:ColorInt private val refreshProgressBarColor: Int? = null,
    private val scrollBarStyle: Int = View.SCROLLBARS_INSIDE_OVERLAY,
    private val sectionsViewLogger: SectionsRecyclerViewLogger? = null,
    private val shouldExcludeFromIncrementalMount: Boolean = false,
    private val startPadding: Int = 0,
    private val startupLogger: LithoStartupLogger? = null,
    private val style: Style = Style,
    private val topPadding: Int = 0,
    private val touchInterceptor: TouchInterceptor? = null,
    private val verticalFadingEdgeEnabled: Boolean = false,
) : PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    val layoutConfig: CollectionLayoutConfig = useConfig(recyclerConfiguration)
    val measureVersion = useState { 0 }
    val poolScope = useState { PoolScope.ManuallyManaged() }.value
    val layoutInfo = useStateWithDeps(
        layoutConfig.orientation,
        layoutConfig.reverseLayout,
        layoutConfig.stackFromEnd,
        layoutConfig.spanCount,
        layoutConfig.gapStrategy,
    ) {
      recyclerConfiguration.getLayoutInfo(context)
    }
        .value
    val latestCommittedData = useState {
      LatestCommittedData(
          layoutInfo,
          layoutConfig,
          saveableStateRegistries =
              CollectionSaveableStateRegistries(
                  context.getTreeProp(LithoSaveableStateRegistryTreeProp)?.let {
                    saveableStateComponentKey(context)
                  },
              ),
      )
    }
        .value
    val adapter = useState { CollectionPrimitiveViewAdapter() }.value
    val collectionPreparationManager =
        useStateWithDeps(layoutInfo, adapter) { CollectionPreparationManager(layoutInfo, adapter) }
            .value
    val stickyHeaderController =
        useStateWithDeps(layoutInfo) { NewStickyHeaderController(layoutInfo, adapter) }.value
    val onChildMeasured: (CollectionItem<*>, IntSize) -> Unit =
        useCallback { _: CollectionItem<*>, size: IntSize ->
          if (layoutConfig.crossAxisWrapMode == CrossAxisWrapMode.Dynamic) {
            val isVertical = layoutConfig.orientation == CollectionOrientation.VERTICAL
            val isChildBiggerThanCollection =
                if (isVertical) {
                  size.width > latestCommittedData.collectionSize.width
                } else {
                  size.height > latestCommittedData.collectionSize.height
                }

            if (isChildBiggerThanCollection) {
              if (isVertical) {
                latestCommittedData.collectionSize =
                    IntSize(size.width, latestCommittedData.collectionSize.height)
              } else {
                latestCommittedData.collectionSize =
                    IntSize(latestCommittedData.collectionSize.width, size.height)
              }
              measureVersion.update { it + 1 }
            }
          }
        }

    val onNearEndCallbackLock = useRef { false }
    val onNearEndCallback = useCallback { totalCount: Int, lastVisibleIndex: Int ->
      onNearEnd?.let { block ->
        val threshold = max(0, totalCount - 1 - block.offset)
        if (onNearEndCallbackLock.value && lastVisibleIndex < threshold) {
          // Reset the flag to allow onNearEnd callback when user scrolls back up after failed
          // data loading, otherwise there would be no opportunity to retry loading when
          // scrolling down again.
          onNearEndCallbackLock.value = false
        } else if (!onNearEndCallbackLock.value && lastVisibleIndex >= threshold) {
          // Prevent duplicate onNearEnd callback invocations by setting a flag that persists
          // until the next data update
          onNearEndCallbackLock.value = true
          block.callback()
        }
      }
    }
    val runnableHandlerProvider = useCached {
      ComponentsConfiguration.collectionLayoutHandlerProvider
    }

    // This calculates the diff between the previous and new data to determine what changes need to
    // be made to the RecyclerView. It's performed as a best-effort calculation on the background
    // thread without synchronization locks since this data might be discarded if new updates come
    // in before it's applied.
    val changeset = generateChangeset(
        context,
        runnableHandlerProvider,
        latestCommittedData,
        children,
        contentComparator,
        idComparator,
        componentRenderer,
        onChildMeasured,
    )

    val recyclerEventsController = useState { RecyclerEventsController() }.value
    val collectionPrimitiveViewScroller = useState {
      CollectionPrimitiveViewScroller(context.androidContext)
    }
        .value
    useEffect(lazyCollectionController) {
      lazyCollectionController?.recyclerEventsController = recyclerEventsController
      lazyCollectionController?.scrollerDelegate = RecyclerScroller(collectionPrimitiveViewScroller)
      onCleanup {
        lazyCollectionController?.recyclerEventsController = null
        lazyCollectionController?.scrollerDelegate = null
      }
    }

    val viewportChangedCallback =
        useCallback {
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
            itemCount: Int,
            firstFullyVisibleIndex: Int,
            lastFullyVisibleIndex: Int ->
          // Ensure that we always have the latest viewport information
          latestCommittedData.firstVisiblePosition = firstVisibleIndex
          latestCommittedData.lastVisiblePosition = lastVisibleIndex
          onViewportChanged?.invoke(
              firstVisibleIndex,
              lastVisibleIndex,
              itemCount,
              firstFullyVisibleIndex,
              lastFullyVisibleIndex,
          )
        }
    useEffect(adapter, collectionPreparationManager) {
      val viewportChangedListener =
          ViewportChanged {
              firstVisibleIndex,
              lastVisibleIndex,
              firstFullyVisibleIndex,
              lastFullyVisibleIndex,
              state ->
            val totalCount = latestCommittedData.items.size
            onNearEndCallback(totalCount, lastVisibleIndex)
            viewportChangedCallback(
                firstVisibleIndex,
                lastVisibleIndex,
                totalCount,
                firstFullyVisibleIndex,
                lastFullyVisibleIndex,
            )
            if (
                ComponentsConfiguration.enableCollectionLayoutHandlerProvider &&
                    runnableHandlerProvider is PoolingThreadHandlerProvider
            ) {
              if (ComponentsConfiguration.enableDynamicPoolSize) {
                val workload = 2 // max workload per worker thread
                val workers = max(
                    1,
                    ((lastVisibleIndex - firstVisibleIndex) / workload).coerceAtMost(
                        runnableHandlerProvider.poolSize,
                    ),
                )
                runnableHandlerProvider.activeSize = workers
              }
            }
          }
      collectionPreparationManager.addViewportChangedListener(viewportChangedListener)
      onCleanup {
        collectionPreparationManager.removeViewportChangedListener(viewportChangedListener)
      }
    }

    useEffect(Unit) {
      latestCommittedData.saveableStateRegistries.attach()
      onCleanup {
        poolScope.releaseScope()
        latestCommittedData.saveableStateRegistries.detach()
        // A row's state handle can outlive the list, e.g. held by a pooled view, so the rows are
        // released here or that handle would keep them, and all they reference, alive. This also
        // runs when the row holding a nested list is recycled: the inner rows then render again
        // from scratch when it is bound again, keeping only their saveable state.
        latestCommittedData.items.forEach { item ->
          (item as? LithoCollectionItem)?.releaseRenderer()
        }
      }
    }

    val componentRendererCallback = useCallback { index: Int, model: CollectionChild ->
      componentRenderer(index, model)
    }
    val contentComparatorCallback =
        useCallback { previousItem: CollectionChild, nextItem: CollectionChild ->
          contentComparator(previousItem, nextItem)
        }
    val idComparatorCallback =
        useCallback { previousItem: CollectionChild, nextItem: CollectionChild ->
          idComparator(previousItem, nextItem)
        }
    val onDataBoundCallback: () -> Unit = useCallback { -> onDataBound?.invoke() }
    val onDataRenderedCallback: (Boolean, Boolean, Long, Int, Int) -> Unit =
        useCallback {
            isDataChanged: Boolean,
            isMounted: Boolean,
            monoTimestampMs: Long,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int ->
          onDataRendered?.invoke(
              isDataChanged,
              isMounted,
              monoTimestampMs,
              firstVisibleIndex,
              lastVisibleIndex,
          )
        }
    val remeasureCallback: () -> Unit = useCallback { -> measureVersion.update { it + 1 } }
    val pullToRefreshCallback: () -> Unit = useCallback { -> onPullToRefresh?.invoke() }
    val internalPullToRefreshEnabled = (layoutConfig.orientation.isVertical && pullToRefreshEnabled)
    // LazyCollection resolves the default sentinel before it gets here; the sentinel is a shared
    // instance and must never be attached to a RecyclerView.
    val itemAnimatorToUse =
        if (itemAnimator === CollectionRecyclerSpec.itemAnimator) null else itemAnimator
    val padding =
        useStateWithDeps(startPadding, topPadding, endPadding, bottomPadding) {
              Padding(
                  startPadding = startPadding,
                  endPadding = endPadding,
                  topPadding = topPadding,
                  bottomPadding = bottomPadding,
              )
            }
            .value
    return LithoPrimitive(
        layoutBehavior =
            CollectionPrimitiveViewLayoutBehavior(
                adapter = adapter,
                layoutConfig = layoutConfig,
                layoutInfo = layoutInfo,
                runnableHandlerProvider = runnableHandlerProvider,
                changeset = changeset,
                children = children,
                componentRenderer = componentRendererCallback,
                contentComparator = contentComparatorCallback,
                idComparator = idComparatorCallback,
                latestCommittedData = latestCommittedData,
                preparationManager = collectionPreparationManager,
                stickyHeaderController = stickyHeaderController,
                recyclerEventsController = recyclerEventsController,
                onDataRendered = onDataRenderedCallback,
                onDataBound = onDataBoundCallback,
                onNearEndCallbackLock = onNearEndCallbackLock,
                measureVersion = measureVersion.value,
                onChildMeasured = onChildMeasured,
                padding = padding,
                onRemeasure = remeasureCallback,
            ),
        mountBehavior =
            CollectionPrimitiveViewMountBehavior(
                measureVersion = measureVersion.value,
                layoutConfig = layoutConfig,
                layoutInfo = layoutInfo,
                adapter = adapter,
                saveableStateRegistries = latestCommittedData.saveableStateRegistries,
                preparationManager = collectionPreparationManager,
                scroller = collectionPrimitiveViewScroller,
                clipChildren = clipChildren,
                clipToPadding = clipToPadding,
                padding = padding,
                onRefresh =
                    if (internalPullToRefreshEnabled) {
                      pullToRefreshCallback
                    } else {
                      null
                    },
                snapHelper = layoutConfig.snapHelper,
                excludeFromIncrementalMount =
                    this@CollectionRecyclerComponent.shouldExcludeFromIncrementalMount,
                fadingEdgeLength = fadingEdgeLength,
                horizontalFadingEdgeEnabled = horizontalFadingEdgeEnabled,
                isBottomFadingEnabled = isBottomFadingEnabled,
                isLeftFadingEnabled = isLeftFadingEnabled,
                isRightFadingEnabled = isRightFadingEnabled,
                isTopFadingEnabled = isTopFadingEnabled,
                itemAnimator = itemAnimatorToUse,
                itemDecorations = itemDecoration?.let { listOf(it) },
                itemTouchListener = itemTouchListener,
                nestedScrollingEnabled = nestedScrollingEnabled,
                onAfterLayoutListener = onAfterLayoutListener,
                onBeforeLayoutListener = onBeforeLayoutListener,
                onScrollListeners = onScrollListeners?.filterNotNull(),
                overScrollMode = overScrollMode,
                pullToRefreshEnabled = pullToRefreshEnabled,
                recyclerEventsController = recyclerEventsController,
                stickyHeaderController = stickyHeaderController,
                recyclerViewId = recyclerViewId,
                refreshProgressBarBackgroundColor = refreshProgressBarBackgroundColor,
                refreshProgressBarColor = refreshProgressBarColor ?: Color.BLACK,
                scrollBarStyle = scrollBarStyle,
                sectionsViewLogger = sectionsViewLogger,
                touchInterceptor = touchInterceptor,
                verticalFadingEdgeEnabled = verticalFadingEdgeEnabled,
            ),
        style = style,
    )
  }

  companion object {

    /**
     * Reads configuration values from a RecyclerConfiguration and creates a new
     * CollectionLayoutConfig if there's any change. This function extracts various layout and
     * behavior settings from the provided configuration and wraps them in a state-managed
     * CollectionLayoutConfig object for use in the component.
     */
    @OptIn(ExperimentalLithoApi::class)
    @Hook
    private fun ComponentScope.useConfig(config: RecyclerConfiguration): CollectionLayoutConfig {
      val mainAxisWrapContent = config.recyclerBinderConfiguration.recyclerBinderConfig.wrapContent
      val crossAxisWrapMode =
          config.recyclerBinderConfiguration.recyclerBinderConfig.crossAxisWrapMode
      val isCircular = config.recyclerBinderConfiguration.recyclerBinderConfig.isCircular
      val snapHelper = config.snapHelper
      val snapMode = config.snapMode
      val rangeRatio = config.recyclerBinderConfiguration.recyclerBinderConfig.rangeRatio
      val orientation = CollectionOrientation.fromInt(config.orientation)
      val reverseLayout = config.reverseLayout
      val stackFromEnd = config.stackFromEnd
      val spanCount =
          when (config) {
            is GridRecyclerConfiguration -> config.numColumns
            is StaggeredGridRecyclerConfiguration -> config.numSpans
            else -> GridLayoutManager.DEFAULT_SPAN_COUNT
          }
      val gapStrategy =
          if (config is StaggeredGridRecyclerConfiguration) {
            config.gapStrategy
          } else {
            StaggeredGridLayoutManager.GAP_HANDLING_NONE
          }
      val enableItemPrefetch =
          config.recyclerBinderConfiguration.recyclerBinderConfig.recyclerViewItemPrefetch
      val itemViewCacheSize =
          config.recyclerBinderConfiguration.recyclerBinderConfig.itemViewCacheSize

      return useStateWithDeps(
          mainAxisWrapContent,
          crossAxisWrapMode,
          snapHelper,
          snapMode,
          rangeRatio,
          orientation,
          reverseLayout,
          stackFromEnd,
          spanCount,
          gapStrategy,
          enableItemPrefetch,
          itemViewCacheSize,
          isCircular,
      ) {
        CollectionLayoutConfig(
            mainAxisWrapContent = mainAxisWrapContent,
            crossAxisWrapMode = crossAxisWrapMode,
            snapHelper = snapHelper,
            snapMode = snapMode,
            rangeRatio = rangeRatio,
            orientation = orientation,
            reverseLayout = reverseLayout,
            stackFromEnd = stackFromEnd,
            spanCount = spanCount,
            gapStrategy = gapStrategy,
            enableItemPrefetch = enableItemPrefetch,
            itemViewCacheSize = itemViewCacheSize,
            isCircular = isCircular,
        )
      }
          .value
    }
  }
}

/**
 * Internal data class that holds the latest committed data for the collection. Uses @Volatile to
 * ensure thread-safe access to all fields across different threads. This class serves as a
 * thread-safe container for collection state that can be accessed from both UI and background
 * threads during layout computation and data updates.
 *
 * @param layoutInfo The layout information for the collection's RecyclerView
 * @param layoutConfig The configuration settings for the collection layout
 * @param data The list of CollectionChild items that have been committed to the collection.
 *   Defaults to null when no data has been committed yet.
 * @param items The list of CollectionItem items that have been committed to the collection.
 * @param firstVisiblePosition The position of the first visible item in the viewport.
 * @param lastVisiblePosition The position of the last visible item in the viewport.
 * @param collectionConstraints The size constraints applied to the collection during layout.
 * @param collectionSize The measured size of the collection after layout computation.
 * @param lastAppliedMeasureVersion The measure version that was current when visible items were
 *   last notified to rebind. Compared against the current measure version each layout pass to
 *   detect whether a [CrossAxisWrapMode.Dynamic] child-measurement cycle has occurred and visible
 *   items need to be rebound.
 * @param saveableStateRegistries The registries for the rows' saveable state, one per row key.
 */
internal class LatestCommittedData(
    @Volatile var layoutInfo: LayoutInfo,
    @Volatile var layoutConfig: CollectionLayoutConfig,
    @Volatile var data: List<CollectionChild>? = null,
    @Volatile var items: List<CollectionItem<*>> = emptyList(),
    @Volatile var firstVisiblePosition: Int = RecyclerView.NO_POSITION,
    @Volatile var lastVisiblePosition: Int = RecyclerView.NO_POSITION,
    @Volatile var collectionConstraints: SizeConstraints = SizeConstraints(),
    @Volatile var collectionSize: IntSize = IntSize.Invalid,
    @Volatile var lastAppliedMeasureVersion: Int = 0,
    val saveableStateRegistries: CollectionSaveableStateRegistries =
        CollectionSaveableStateRegistries(),
) {

  private val lock = Any()

  @UiThread
  fun commit(changeset: Changeset) {
    ThreadUtils.assertMainThread()
    val removedItems =
        synchronized(lock) {
          data = changeset.operation.nextData
          val newItems = changeset.items ?: return@synchronized emptyList()
          val removed = itemsRemoved(from = items, to = newItems)
          // Update the items list with new data from the changeset if available. This prevents
          // unnecessary UI updates when the underlying data has changed but nothing should be
          // reflected.
          items = newItems
          removed
        }
    changeset.rowKeys?.let(saveableStateRegistries::onCommit)
    // A removed row's state handle can outlive it, e.g. held by a pooled view, so the row is
    // released or that handle would keep it alive.
    removedItems.forEach { item -> (item as? LithoCollectionItem)?.releaseRenderer() }
  }

  /** The items in [from] that are not in [to], compared by identity. */
  private fun itemsRemoved(
      from: List<CollectionItem<*>>,
      to: List<CollectionItem<*>>,
  ): List<CollectionItem<*>> {
    if (from.isEmpty()) {
      return emptyList()
    }
    val kept = Collections.newSetFromMap(IdentityHashMap<CollectionItem<*>, Boolean>())
    kept.addAll(to)
    return from.filter { item -> item !in kept }
  }

  /**
   * Retrieves a snapshot of the current committed data and items in a thread-safe manner.
   *
   * This method returns a pair containing the current data list and a mutable copy of the items
   * list. The data reference is returned as-is (can be null if no data has been committed), while
   * the items are copied to a new mutable list to prevent external modifications to the internal
   * state.
   */
  fun getData(): Pair<List<CollectionChild>?, MutableList<CollectionItem<*>>> {
    return synchronized(lock) { this.data to this.items.toMutableList() }
  }

  /**
   * Creates a CollectionLayoutScope instance with the current layout state and configuration.
   *
   * This method provides a snapshot of the collection's layout information that can be used for
   * layout calculations, item positioning, and viewport management. The returned scope contains all
   * necessary data for performing layout operations on collection items.
   *
   * @return CollectionLayoutScope configured with current layout state including constraints, size,
   *   items, orientation, wrap modes, and visible positions
   */
  fun createLayoutScope(): CollectionLayoutScope = CollectionLayoutScope(
      layoutInfo = layoutInfo,
      collectionConstraints = collectionConstraints,
      collectionSize = collectionSize,
      collectionItems = items,
      isVertical = layoutConfig.orientation == CollectionOrientation.VERTICAL,
      wrapInMainAxis = layoutConfig.mainAxisWrapContent,
      crossAxisWrapMode = layoutConfig.crossAxisWrapMode,
      firstVisiblePosition = firstVisiblePosition,
      lastVisiblePosition = lastVisiblePosition,
  )
}

/**
 * Payload passed to [RecyclerView.Adapter.notifyItemRangeChanged] when visible items need to rebind
 * due to a size change rather than a data change.
 *
 * When the payload list is non-empty, [DefaultItemAnimator.canReuseUpdatedViewHolder] returns
 * `true`, which causes RecyclerView to rebind the same ViewHolder in-place rather than allocating a
 * new one and cross-fading between them. This avoids an unnecessary ViewHolder allocation and the
 * cross-fade change animation for the default animator.
 *
 * Note: [com.facebook.litho.widget.LazyCollectionItemAnimator] (the Lazy Collection default when
 * [ComponentsConfiguration.useDefaultItemAnimatorInLazyCollections] is enabled) already sets
 * [SimpleItemAnimator.setSupportsChangeAnimations] to `false`, so [canReuseUpdatedViewHolder]
 * returns `true` regardless of the payload. The payload is still passed here as a defensive measure
 * for cases where a custom [RecyclerView.ItemAnimator] is set.
 */
private object SizeRebindPayload

/**
 * Calculates the difference between two lists and returns a CollectionUpdateOperation containing
 * the operations needed to transform the previous list into the next list.
 *
 * @param T The type of items in the lists
 * @param previousData The original list of items (can be null)
 * @param nextData The new list of items to compare against (can be null)
 * @param sameItemComparator Optional comparator to determine if two items represent the same entity
 * @param sameContentComparator Optional comparator to determine if two items have the same content
 * @return CollectionUpdateOperation containing the diff operations and data references
 */
private fun <T> calculateDiff(
    previousData: List<T>?,
    nextData: List<T>?,
    sameItemComparator: ((previousItem: T, nextItem: T) -> Boolean)? = null,
    sameContentComparator: ((previousItem: T, nextItem: T) -> Boolean)? = null,
): CollectionUpdateOperation<T> {
  ComponentsSystrace.trace({ "diffing" }) {
    val updateOperation = CollectionUpdateOperation(prevData = previousData, nextData = nextData)
    val diffCallback =
        CollectionDiffCallback(previousData, nextData, sameItemComparator, sameContentComparator)
    val result = DiffUtil.calculateDiff(diffCallback)
    result.dispatchUpdatesTo(updateOperation)
    return updateOperation
  }
}

/**
 * Builds a list of CollectionItem objects by applying update operations to an existing adapter's
 * items. This method processes insert, delete, move, and update operations to transform the current
 * collection into the target state defined by the update callback.
 *
 * @param context The ComponentContext used for creating new collection items
 * @param updatedItems The list of CollectionItem objects to be applied with the update operations
 * @param renderer Function that converts a model at a given index into RenderInfo
 * @param updateOperation Contains the operations and target data for the collection update
 * @return List of CollectionItem objects representing the updated collection state
 */
private fun buildCollectionItems(
    context: ComponentContext,
    runnableHandlerProvider: RunnableHandlerProvider,
    updatedItems: MutableList<CollectionItem<*>>,
    renderer: (index: Int, model: CollectionChild) -> RenderInfo,
    updateOperation: CollectionUpdateOperation<CollectionChild>,
    onChildMeasured: (LithoCollectionItem, IntSize) -> Unit,
    saveableStateRegistries: CollectionSaveableStateRegistries,
): List<CollectionItem<*>> {
  val itemsNeedToRefreshRenderInfo = mutableSetOf<Int>()
  for (operation in updateOperation.operations) {
    when (operation.type) {
      CollectionOperation.Type.INSERT -> {
        for (offset in 0 until operation.count) {
          val position = operation.index + offset
          if (position <= updatedItems.size) {
            val item = LithoCollectionItem(
                componentContext = context,
                renderInfo = ComponentRenderInfo.createEmpty(),
                runnableHandlerProvider = runnableHandlerProvider,
                onChildMeasured = onChildMeasured,
                prioritizeBoundRows = runnableHandlerProvider is PoolingThreadHandlerProvider,
                saveableStateRegistries = saveableStateRegistries,
            )
            updatedItems.add(position, item)
            itemsNeedToRefreshRenderInfo.add(item.id)
          }
        }
      }
      CollectionOperation.Type.DELETE -> {
        repeat(operation.count) {
          if (operation.index < updatedItems.size) {
            // To avoid index out of bounds exception, and we would check the consistency of data
            // below.
            updatedItems.removeAt(operation.index)
          }
        }
      }
      CollectionOperation.Type.MOVE -> {
        if (operation.index < updatedItems.size && operation.toIndex < updatedItems.size) {
          updatedItems.add(operation.toIndex, updatedItems.removeAt(operation.index))
        }
      }
      CollectionOperation.Type.UPDATE -> {
        for (offset in 0 until operation.count) {
          val position = operation.index + offset
          if (position < updatedItems.size) {
            val oldItem = updatedItems[position]
            itemsNeedToRefreshRenderInfo.add(oldItem.id)
          }
        }
      }
    }
  }

  if (updateOperation.nextData != null && updateOperation.nextData.size != updatedItems.size) {
    // We may encounter a scenario where the data size doesn't match the result after applying
    // the changeset operations. In such cases, we need to clear all existing items and
    // repopulate the list with the new data.
    updateOperation.operations.clear()
    updateOperation.operations.add(
        CollectionOperation(
            type = CollectionOperation.Type.DELETE,
            index = 0,
            count = updatedItems.size,
        ),
    )
    updatedItems.clear()

    // Refill the list with new data
    for (index in updateOperation.nextData.indices) {
      val model = updateOperation.nextData[index]
      val renderInfo = renderer(index, model)
      val item = LithoCollectionItem(
          componentContext = context,
          renderInfo = renderInfo,
          runnableHandlerProvider = runnableHandlerProvider,
          onChildMeasured = onChildMeasured,
          prioritizeBoundRows = runnableHandlerProvider is PoolingThreadHandlerProvider,
          saveableStateRegistries = saveableStateRegistries,
      )
      updatedItems.add(item)
    }
    updateOperation.operations.add(
        CollectionOperation(
            type = CollectionOperation.Type.INSERT,
            index = 0,
            count = updateOperation.nextData.size,
        ),
    )
  } else {
    for (index in 0 until updatedItems.size) {
      val item = updatedItems[index]
      // Generate render info for all changed items
      if (itemsNeedToRefreshRenderInfo.contains(item.id)) {
        item.renderInfo =
            (updateOperation.nextData?.get(index)?.let { model -> renderer(index, model) }
                ?: ComponentRenderInfo.Companion.createEmpty())
      }
    }
  }
  return updatedItems
}

/**
 * Applies update operations to the RecyclerView adapter and notifies it of data changes. This
 * method processes a collection of operations (insert, delete, move, update) and dispatches the
 * appropriate notifications to the adapter to trigger UI updates.
 *
 * @param changeset The changeset containing the operations and updated items to apply
 * @param callback Function to be invoked after all changeset operations have been applied
 */
@UiThread
private fun CollectionPrimitiveViewAdapter.applyChangeset(
    changeset: Changeset,
    callback: () -> Unit,
) {
  ThreadUtils.assertMainThread()
  ComponentsSystrace.trace({ "applyChangeset" }) {
    if (changeset.items != null) {
      setItems(changeset.items)
      for (operation in changeset.operation.operations) {
        when (operation.type) {
          CollectionOperation.Type.INSERT -> {
            if (operation.count > 1) {
              notifyItemRangeInserted(operation.index, operation.count)
            } else {
              notifyItemInserted(operation.index)
            }
          }
          CollectionOperation.Type.DELETE -> {
            if (operation.count > 1) {
              notifyItemRangeRemoved(operation.index, operation.count)
            } else {
              notifyItemRemoved(operation.index)
            }
          }
          CollectionOperation.Type.MOVE -> {
            notifyItemMoved(operation.index, operation.toIndex)
          }
          CollectionOperation.Type.UPDATE -> {
            if (operation.count > 1) {
              notifyItemRangeChanged(operation.index, operation.count)
            } else {
              notifyItemChanged(operation.index)
            }
          }
        }
      }
    }
    callback()
  }
}

/**
 * Dispatches update callbacks to notify listeners about data changes and rendering completion. This
 * function handles the invocation of data bound and data rendered callbacks based on whether the
 * underlying data has changed.
 *
 * @param firstVisiblePosition The index of the first visible item in the collection
 * @param lastVisiblePosition The index of the last visible item in the collection
 * @param isDataChanged Boolean flag indicating whether the data has been modified
 * @param onDataBound Optional callback invoked when data has been bound to the collection
 * @param onDataRendered Optional callback invoked when data rendering is complete
 */
private fun dispatchUpdateCallback(
    firstVisiblePosition: Int,
    lastVisiblePosition: Int,
    isDataChanged: Boolean,
    onDataBound: OnDataBound?,
    onDataRendered:
        ((
            isDataChanged: Boolean,
            isMounted: Boolean,
            monoTimestampMs: Long,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
        ) -> Unit)?,
) {
  if (isDataChanged) {
    onDataBound?.invoke()
  }
  onDataRendered?.invoke(
      isDataChanged,
      true,
      SystemClock.uptimeMillis(),
      firstVisiblePosition,
      lastVisiblePosition,
  )
}

/**
 * Layout behavior implementation for primitive collection views that handles the measurement and
 * layout of collection items within specified size constraints and padding.
 */
@OptIn(ExperimentalLithoApi::class)
private class CollectionPrimitiveViewLayoutBehavior(
    private val adapter: CollectionPrimitiveViewAdapter,
    private val layoutConfig: CollectionLayoutConfig,
    private val layoutInfo: LayoutInfo,
    private val runnableHandlerProvider: RunnableHandlerProvider,
    private val changeset: Changeset,
    private val children: List<CollectionChild>,
    private val componentRenderer: (index: Int, model: CollectionChild) -> RenderInfo,
    private val contentComparator:
        (previousItem: CollectionChild, nextItem: CollectionChild) -> Boolean,
    private val idComparator: (previousItem: CollectionChild, nextItem: CollectionChild) -> Boolean,
    private val latestCommittedData: LatestCommittedData,
    private val onDataBound: OnDataBound?,
    private val onDataRendered:
        ((
            isDataChanged: Boolean,
            isMounted: Boolean,
            monoTimestampMs: Long,
            firstVisibleIndex: Int,
            lastVisibleIndex: Int,
        ) -> Unit)?,
    private val onNearEndCallbackLock: Ref<Boolean>,
    private val preparationManager: CollectionPreparationManager,
    private val stickyHeaderController: NewStickyHeaderController,
    private val recyclerEventsController: RecyclerEventsController,
    private val onChildMeasured: (CollectionItem<*>, IntSize) -> Unit,
    private val measureVersion: Int,
    private val padding: Padding,
    private val onRemeasure: () -> Unit,
) : LayoutBehavior {

  override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {
    val previousLayoutData = previousLayoutData as CollectionLayoutData?
    val componentContext = (extraContext as LithoExtraContextForLayoutScope).componentContext

    val constraintsWithoutPadding = sizeConstraintsWithoutPadding(
        sizeConstraints,
        padding.horizontalPadding,
        padding.verticalPadding,
    )
    val latestItems =
        if (previousLayoutData != null && latestCommittedData.data === children) {
          // Layout is being re-triggered with the same dataset, so we can safely reuse
          // the cached items from the latest committed data to avoid unnecessary re-computation.
          previousLayoutData.items
        } else {
          // Data has changed or this is a fresh render with a different dataset.
          // Use items from the changeset if available (indicating processed changes),
          // otherwise fall back to the latest committed items as a safe default.
          changeset.items ?: latestCommittedData.items
        }

    // Skip layout calculation if size constraints and data haven't changed
    val canSkipLayoutCalculation =
        previousLayoutData != null &&
            previousLayoutData.items === latestItems &&
            previousLayoutData.sizeConstraints == constraintsWithoutPadding &&
            previousLayoutData.measuredSize.isValid &&
            previousLayoutData.measuredVersion == measureVersion

    val latestSize =
        if (canSkipLayoutCalculation) {
          // Reuse cached size when constraints and data are unchanged
          checkNotNull(previousLayoutData).measuredSize
        } else {
          val layoutScope = CollectionLayoutScope(
              layoutInfo = layoutInfo,
              collectionConstraints = constraintsWithoutPadding,
              collectionSize = latestCommittedData.collectionSize,
              collectionItems = latestItems,
              isVertical = layoutConfig.orientation.isVertical,
              wrapInMainAxis = layoutConfig.mainAxisWrapContent,
              crossAxisWrapMode = layoutConfig.crossAxisWrapMode,
              firstVisiblePosition = latestCommittedData.firstVisiblePosition,
              lastVisiblePosition = latestCommittedData.lastVisiblePosition,
          )
          val collectionSize = layoutScope.calculateLayout()

          if (!preparationManager.hasApproximateRangeSize) {
            // Measure the first child item with the calculated size constraints
            // todo: use different strategies to have a more precise estimation
            latestItems.firstOrNull()?.let { firstChild ->
              val constraints = layoutScope.getChildSizeConstraints(firstChild, collectionSize)
              val size = layoutScope.getMeasuredSize(firstChild, constraints)
              preparationManager.estimateItemsInViewPort(collectionSize, size)
            }
          }

          val shouldPrepare =
              !ThreadUtils.isMainThread || !ComponentsConfiguration.disablePreparationOnUiThread
          if (shouldPrepare) {
            // Layout computation may occur on the UI thread during startup, which could negatively
            // impact startup performance metrics due to the cost of preparing items. To mitigate
            // this, we skip item preparation on the UI thread. Any items in the viewport that
            // weren't prepared will be handled during onBindViewHolder when they're actually needed
            // for display.
            with(preparationManager) {
              // Use the latest measured collection size to prepare items that will be visible in
              // the viewport ahead of time for better performance
              layoutScope.prepareItemsInViewport(latestItems, collectionSize)
            }
          }

          // Return the latest measured size
          collectionSize
        }

    useLayoutEffect(adapter) {
      adapter.viewHolderCreator = ViewHolderCreator
      // RecyclerView.Adapter doesn't allow changing the stable ID flag after observers are
      // registered. Since this flag determines whether we use stable IDs for item identification,
      // we set it once during initialization.
      adapter.isCircular = layoutConfig.isCircular
      adapter.latestCommittedData = latestCommittedData
      onCleanup {
        // The viewHolderCreator should only be reset after the adapter is completely unbound from
        // the RecyclerView, as resetting it while UI updates are still in progress can cause
        // crashes.
        adapter.latestCommittedData = null
      }
    }

    useLayoutEffect(layoutInfo, adapter) {
      if (
          ComponentsConfiguration.enableDoubleMeasureForGridLayout && (layoutInfo is GridLayoutInfo)
      ) {
        adapter.onItemMeasured = { view, item, widthSpec, heightSpec ->
          val constraints = SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec)
          if (constraints != item.sizeConstraints) {
            layoutInfo.sizeConstraintsCache[item] = constraints
            latestCommittedData.createLayoutScope().let { scope ->
              with(item) { scope.onBindView(view) }
            }
          }
        }
      }
      onCleanup { adapter.onItemMeasured = null }
    }

    useLayoutEffect(layoutInfo, adapter) {
      layoutInfo.setRenderInfoCollection { position ->
        val item =
            adapter.findItemByPosition(position)
                ?: throw IllegalStateException("Trying to find a child item out of range!")
        item.renderInfo
      }
      onCleanup { layoutInfo.setRenderInfoCollection(null) }
    }

    useLayoutEffect(adapter, stickyHeaderController, preparationManager) {
      val dataChangeObserver = CollectionAdapterDataObserver {
        stickyHeaderController.onItemsChanged()
        preparationManager.onItemsChanged()
      }
      adapter.addDataObserver(dataChangeObserver)
      onCleanup { adapter.removeDataObserver(dataChangeObserver) }
    }

    useLayoutEffect(Any()) {
      val previousSize = latestCommittedData.collectionSize
      val previousMeasureVersion = latestCommittedData.lastAppliedMeasureVersion
      latestCommittedData.layoutInfo = layoutInfo
      latestCommittedData.layoutConfig = layoutConfig
      latestCommittedData.collectionConstraints = constraintsWithoutPadding
      latestCommittedData.collectionSize = latestSize
      latestCommittedData.lastAppliedMeasureVersion = measureVersion

      // Notify visible items to rebind in two cases:
      // 1. measureVersion changed: a child was measured wider than the collection
      //    (CrossAxisWrapMode.Dynamic), so visible items need to re-render at the updated size.
      // 2. collection size changed without a measureVersion bump: the collection was laid out twice
      //    with different constraints (e.g., Litho resolution pass then Android View measurement
      //    pass), and already-bound items retain stale dimensions.
      // TODO: Inline the layout calculation to ensure it happens in a single frame.
      val shouldRebindVisibleItems =
          previousMeasureVersion != measureVersion ||
              (previousSize.isValid && previousSize != latestSize)
      if (shouldRebindVisibleItems) {
        val start = latestCommittedData.firstVisiblePosition
        if (start >= 0) {
          val range = max(
              latestCommittedData.lastVisiblePosition - start + 1,
              1,
          )
          adapter.notifyItemRangeChanged(start, range, SizeRebindPayload)
        }
      }

      onCleanup {
        // nothing to do
      }
    }

    val layoutData = CollectionLayoutData(
        measuredVersion = measureVersion,
        sizeConstraints = constraintsWithoutPadding,
        measuredSize = latestSize,
        items = latestItems,
    )
    useLayoutEffect(changeset) {
      val resolvedChangeset =
          if (changeset.operation.prevData !== latestCommittedData.data) {
            // If the data has changed since the last diff calculation, we need to re-calculate
            // the result to make sure we're always applying the latest changeset.
            val newChangeset = generateChangeset(
                componentContext,
                runnableHandlerProvider,
                latestCommittedData,
                children,
                contentComparator,
                idComparator,
                componentRenderer,
                onChildMeasured,
            )
            if (newChangeset.items != null) {
              // Remeasure the list and trigger re-layout if the size doesn't match
              val remeasuredSize = CollectionLayoutScope(
                  layoutInfo = layoutInfo,
                  collectionConstraints = constraintsWithoutPadding,
                  collectionSize = latestCommittedData.collectionSize,
                  collectionItems = newChangeset.items,
                  isVertical = layoutConfig.orientation.isVertical,
                  wrapInMainAxis = layoutConfig.mainAxisWrapContent,
                  crossAxisWrapMode = layoutConfig.crossAxisWrapMode,
                  firstVisiblePosition = latestCommittedData.firstVisiblePosition,
                  lastVisiblePosition = latestCommittedData.lastVisiblePosition,
              )
                  .calculateLayout()
              if (latestSize != remeasuredSize) {
                onRemeasure()
              }
            }
            newChangeset
          } else {
            changeset
          }

      commitChangeset(
          componentContext,
          recyclerEventsController,
          resolvedChangeset,
          layoutData,
          latestCommittedData,
          adapter,
          layoutInfo,
          onDataBound,
          onDataRendered,
          0,
          if (layoutConfig.orientation == CollectionOrientation.VERTICAL) RecyclerView.VERTICAL
          else RecyclerView.HORIZONTAL,
      )

      onNearEndCallbackLock.value = false
      onCleanup {
        // nothing to do
      }
    }

    return PrimitiveLayoutResult(
        width = latestSize.width + padding.horizontalPadding,
        height = latestSize.height + padding.verticalPadding,
        layoutData = layoutData,
    )
  }

  companion object {

    private const val APPLY_CHANGESET_RETRY_LIMIT: Int = 100

    /**
     * Factory function that creates CollectionItemRootHostHolder instances for RecyclerView items.
     */
    private val ViewHolderCreator:
        (View, Int) -> CollectionItemRootHostHolder<out View, out CollectionItem<out View>> =
        { parent, viewType ->
          when (viewType) {
            LithoCollectionItem.DEFAULT_COMPONENT_VIEW_TYPE -> {
              LithoCollectionItemViewHolder(parent.context)
            }
            else -> {
              throw IllegalArgumentException("Unknown view type: $viewType")
            }
          }
        }

    private fun RecyclerView.getState(): String {
      try {
        val field = RecyclerView::class.java.getDeclaredField("mState")
        field.isAccessible = true
        val state = field[this]
        return state!!.toString()
      } catch (e: Exception) {
        DebugInfoReporter.report(category = "Collection") {
          this["event"] = "InvalidRecyclerViewState"
          this["message"] = e.message.toString()
        }
        return "Exception getting state: ${e.message}"
      }
    }

    /**
     * Commits a changeset to the RecyclerView adapter, handling layout computation conflicts.
     *
     * This function applies data changes to the adapter while ensuring thread safety and avoiding
     * crashes that can occur when the RecyclerView is in the middle of layout computation. If the
     * RecyclerView is currently computing layout, the commit is deferred to the next frame to
     * prevent IllegalStateException crashes.
     */
    private fun commitChangeset(
        componentContext: ComponentContext,
        recyclerEventsController: RecyclerEventsController,
        changeset: Changeset,
        layoutData: CollectionLayoutData,
        latestCommittedData: LatestCommittedData,
        adapter: CollectionPrimitiveViewAdapter,
        layoutInfo: LayoutInfo,
        onDataBound: OnDataBound?,
        onDataRendered:
            ((
                isDataChanged: Boolean,
                isMounted: Boolean,
                monoTimestampMs: Long,
                firstVisibleIndex: Int,
                lastVisibleIndex: Int,
            ) -> Unit)?,
        retryCount: Int,
        orientation: Int,
    ) {

      if (recyclerEventsController.recyclerView?.isComputingLayout == true) {
        // Sanity check that we don't get stuck in an infinite loop
        if (retryCount > APPLY_CHANGESET_RETRY_LIMIT) {
          val exceptionMessage =
              """Too many retries -- RecyclerView is stuck in layout,
                   recyclerView: ${recyclerEventsController.recyclerView},
                   isAttachedToWindow: ${recyclerEventsController.recyclerView?.isAttachedToWindow},
                   isAnimating: ${recyclerEventsController.recyclerView?.isAnimating}
                   state: ${recyclerEventsController.recyclerView?.getState()}
                """
                  .trimIndent()
          throw ComponentUtils.wrapWithMetadata(
              componentContext,
              RuntimeException(exceptionMessage),
          )
        }

        // Making changes to the adapter here will crash us. Just post to the next frame boundary.
        ChoreographerCompatImpl.getInstance()
            .postFrameCallback(
                object : ChoreographerCompat.FrameCallback() {
                  override fun doFrame(frameTimeNanos: Long) {
                    commitChangeset(
                        componentContext,
                        recyclerEventsController,
                        changeset,
                        layoutData,
                        latestCommittedData,
                        adapter,
                        layoutInfo,
                        onDataBound,
                        onDataRendered,
                        retryCount + 1,
                        orientation,
                    )
                  }
                },
            )
        return
      }

      latestCommittedData.commit(changeset)
      // Range preparation relies on layout data to determine which items need to be prepared for
      // rendering. We must commit the layout data before notifying the adapter of changes.
      layoutData.items = latestCommittedData.items
      val changesetStartNs = System.nanoTime()
      adapter.applyChangeset(changeset) {
        dispatchUpdateCallback(
            firstVisiblePosition = layoutInfo.findFirstVisibleItemPosition(),
            lastVisiblePosition = layoutInfo.findLastVisibleItemPosition(),
            isDataChanged = changeset.operation.hasChanges,
            onDataBound = onDataBound,
            onDataRendered = onDataRendered,
        )
      }
      val changesetDurationNs = System.nanoTime() - changesetStartNs

      if (changeset.operation.hasChanges) {
        dispatchCollectionChangesetApplied(
            changeset,
            adapter,
            orientation = orientation,
            durationNs = changesetDurationNs,
        )
      }
    }
  }
}

private fun dispatchCollectionChangesetApplied(
    changeset: Changeset,
    adapter: CollectionPrimitiveViewAdapter,
    orientation: Int,
    durationNs: Long,
) {
  DebugEventDispatcher.dispatch(
      type = LithoDebugEvent.CollectionChangesetApplied,
      renderStateId = DebugEvent.NoId,
  ) { attrs ->
    val operations = changeset.operation.operations
    val changeType = resolveCollectionChangeType(operations)
    val changeCount = operations.sumOf { it.count }

    attrs[LithoDebugEventAttributes.IsLegacyInfra] = false
    attrs[LithoDebugEventAttributes.ChangeType] = changeType
    attrs[LithoDebugEventAttributes.ChangeCount] = changeCount
    attrs[LithoDebugEventAttributes.TotalItemCount] = adapter.itemCount
    attrs[LithoDebugEventAttributes.IsSync] = true
    attrs[LithoDebugEventAttributes.IsAsyncInsertMode] = false
    attrs[LithoDebugEventAttributes.HasPreparationWarming] = false
    attrs[LithoDebugEventAttributes.Orientation] =
        if (orientation == RecyclerView.HORIZONTAL) "HORIZONTAL" else "VERTICAL"
    attrs[LithoDebugEventAttributes.ChangesetDurationNs] = durationNs
  }
}

private fun resolveCollectionChangeType(operations: List<CollectionOperation>): String {
  val types = operations.map { it.type }.toSet()
  if (types.size == 1) {
    return when (types.first()) {
      CollectionOperation.Type.INSERT -> "INSERT"
      CollectionOperation.Type.UPDATE -> "UPDATE"
      CollectionOperation.Type.DELETE -> "DELETE"
      CollectionOperation.Type.MOVE -> "MOVE"
    }
  }
  return "MIXED"
}

/**
 * Represents a changeset containing the operations needed to update a collection and the resulting
 * items. This data class encapsulates both the diff operations and the final list of collection
 * items after applying those operations.
 *
 * @param operation The collection update operation containing insert, delete, move, and update
 *   operations that need to be applied to transform the collection from its previous state
 * @param items The resulting list of collection items after applying the update operations, or null
 *   if no changes were detected and no new items need to be generated
 * @param rowKeys The saveable-state keys of [items], read where the items were built, or null if
 *   there are no new items or the collection has no saveable-state registry
 */
@DataClassGenerate(toString = Mode.OMIT, equalsHashCode = Mode.KEEP)
internal data class Changeset(
    val operation: CollectionUpdateOperation<CollectionChild>,
    val items: List<CollectionItem<*>>?,
    val rowKeys: Set<String>? = null,
)

/**
 * Represents padding values for all four sides of a UI component. Provides convenient computed
 * properties for total horizontal and vertical padding.
 *
 * @param startPadding Padding at the start edge
 * @param endPadding Padding at the end edge
 * @param topPadding Padding at the top edge
 * @param bottomPadding Padding at the bottom edge
 */
@DataClassGenerate(toString = Mode.OMIT, equalsHashCode = Mode.KEEP)
internal data class Padding(
    val startPadding: Int,
    val endPadding: Int,
    val topPadding: Int,
    val bottomPadding: Int,
) {
  /** Total horizontal padding combining start and end padding values */
  val horizontalPadding: Int = startPadding + endPadding

  /** Total vertical padding combining top and bottom padding values */
  val verticalPadding: Int = topPadding + bottomPadding
}

/**
 * Data class that holds layout-related information for a collection component.
 *
 * This class stores the computed layout state including size constraints, measured dimensions, and
 * the list of items that need to be rendered in the collection.
 *
 * @param measuredVersion Version number for tracking layout changes
 * @param sizeConstraints The size constraints applied during layout computation
 * @param measuredSize The computed size of the collection after layout
 * @param items The list of collection items to be displayed in the RecyclerView. The items list
 *   needs to be mutable because diff calculations may occur inside useLayoutEffect conditionally,
 *   requiring the ability to update the list with new changeset results.
 */
internal class CollectionLayoutData(
    val measuredVersion: Int,
    val sizeConstraints: SizeConstraints,
    val measuredSize: IntSize = IntSize.Invalid,
    var items: List<CollectionItem<*>>,
)

/**
 * Generates a changeset by calculating the difference between the latest committed data and new
 * children, then builds the corresponding collection items based on whether changes were detected.
 */
private fun generateChangeset(
    componentContext: ComponentContext,
    runnableHandlerProvider: RunnableHandlerProvider,
    latestCommittedData: LatestCommittedData,
    children: List<CollectionChild>,
    contentComparator: (previousItem: CollectionChild, nextItem: CollectionChild) -> Boolean,
    idComparator: (previousItem: CollectionChild, nextItem: CollectionChild) -> Boolean,
    componentRenderer: (index: Int, model: CollectionChild) -> RenderInfo,
    onChildMeasured: (LithoCollectionItem, IntSize) -> Unit,
): Changeset {

  val (currentData, currentItems) = latestCommittedData.getData()

  val operation = calculateDiff(
      previousData = currentData,
      nextData = children,
      sameItemComparator = idComparator,
      sameContentComparator = contentComparator,
  )
  if (!operation.hasChanges) return Changeset(operation, null)

  val saveableStateRegistries = latestCommittedData.saveableStateRegistries
  // We're creating a speculative changeset for measurement without side effects,
  // which may be discarded if the dataset changes before committing the result,
  // so we need to duplicate the items and apply modifications to the copy.
  val items = buildCollectionItems(
      context = componentContext,
      runnableHandlerProvider = runnableHandlerProvider,
      updatedItems = currentItems,
      renderer = componentRenderer,
      updateOperation = operation,
      onChildMeasured = onChildMeasured,
      saveableStateRegistries = saveableStateRegistries,
  )
  val rowKeys =
      componentContext.getTreeProp(LithoSaveableStateRegistryTreeProp)?.let { registry ->
        saveableStateRegistries.onItemsBuilt(registry, items)
      }
  return Changeset(operation, items, rowKeys)
}

/**
 * ViewHolder implementation for Litho collection items that wraps a LithoRenderTreeView. This class
 * serves as a bridge between RecyclerView's ViewHolder pattern and Litho's component rendering
 * system, providing the view container for Litho components within a RecyclerView.
 *
 * @param context The Android context used to create the LithoRenderTreeView
 */
internal class LithoCollectionItemViewHolder(context: Context) :
    CollectionItemRootHostHolder<LithoRenderTreeView, LithoCollectionItem>() {

  /**
   * The root view for this ViewHolder, which is a LithoRenderTreeView that can render Litho
   * components within the RecyclerView item.
   */
  override val view: LithoRenderTreeView = LithoRenderTreeView(context)
}

/**
 * Creates a MountBehavior for CollectionPrimitiveView that handles the mounting and configuration
 * of a SectionsRecyclerView with all necessary properties, listeners, and decorations.
 */
@OptIn(ExperimentalLithoApi::class)
private fun PrimitiveComponentScope.CollectionPrimitiveViewMountBehavior(
    measureVersion: Int,
    layoutConfig: CollectionLayoutConfig,
    layoutInfo: LayoutInfo,
    adapter: CollectionPrimitiveViewAdapter,
    saveableStateRegistries: CollectionSaveableStateRegistries,
    preparationManager: CollectionPreparationManager,
    scroller: CollectionPrimitiveViewScroller,
    clipChildren: Boolean,
    clipToPadding: Boolean,
    padding: Padding,
    excludeFromIncrementalMount: Boolean,
    fadingEdgeLength: Int,
    horizontalFadingEdgeEnabled: Boolean,
    isBottomFadingEnabled: Boolean,
    isLeftFadingEnabled: Boolean,
    isRightFadingEnabled: Boolean,
    isTopFadingEnabled: Boolean,
    itemAnimator: RecyclerView.ItemAnimator?,
    itemDecorations: List<RecyclerView.ItemDecoration>?,
    itemTouchListener: RecyclerView.OnItemTouchListener?,
    nestedScrollingEnabled: Boolean,
    onAfterLayoutListener: OnAfterLayoutListener?,
    onBeforeLayoutListener: OnBeforeLayoutListener?,
    onRefresh: (() -> Unit)?,
    onScrollListeners: List<RecyclerView.OnScrollListener>?,
    overScrollMode: Int,
    pullToRefreshEnabled: Boolean,
    recyclerEventsController: RecyclerEventsController?,
    stickyHeaderController: NewStickyHeaderController,
    recyclerViewId: Int,
    refreshProgressBarBackgroundColor: Int?,
    refreshProgressBarColor: Int,
    scrollBarStyle: Int,
    sectionsViewLogger: SectionsRecyclerViewLogger?,
    snapHelper: SnapHelper?,
    touchInterceptor: TouchInterceptor?,
    verticalFadingEdgeEnabled: Boolean,
): MountBehavior<SectionsRecyclerView> {

  return MountBehavior(ViewAllocator { context -> createSectionsRecyclerView(context) }) {
    doesMountRenderTreeHosts = true
    shouldExcludeFromIncrementalMount = excludeFromIncrementalMount

    withDescription("recycler-decorations") {
      bind(itemDecorations, adapter) { sectionsRecyclerView ->
        val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()

        val measureFunction: (View.() -> Unit) = {
          val position = (layoutParams as RecyclerView.LayoutParams).viewLayoutPosition
          adapter.latestCommittedData?.createLayoutScope()?.let { scope ->
            scope.collectionItems.getOrNull(position)?.let { item ->
              val constraints = scope.getChildSizeConstraints(item)
              measure(constraints.toWidthSpec(), constraints.toHeightSpec())
            }
          }
        }

        itemDecorations?.forEach { decoration ->
          if (decoration is ItemDecorationWithMeasureFunction) {
            decoration.measure = measureFunction
          }
          recyclerView.addItemDecoration(decoration)
        }

        onUnbind {
          itemDecorations?.forEach { decoration ->
            recyclerView.removeItemDecoration(decoration)
            if (decoration is ItemDecorationWithMeasureFunction) {
              decoration.measure = null
            }
          }
        }
      }
    }

    val mountDeps = arrayOf<Any?>(
        measureVersion,
        clipToPadding,
        padding,
        clipChildren,
        scrollBarStyle,
        horizontalFadingEdgeEnabled,
        verticalFadingEdgeEnabled,
        fadingEdgeLength,
        refreshProgressBarBackgroundColor,
        refreshProgressBarColor,
    )

    withDescription("recycler-equivalent-mount") {
      bind(*mountDeps) { sectionsRecyclerView ->
        bindLegacyMountBinder(
            sectionsRecyclerView = sectionsRecyclerView,
            contentDescription = "", // not supported yet, using default value instead
            hasFixedSize = true, // not supported yet, using default value instead
            isClipToPaddingEnabled = clipToPadding,
            paddingAdditionDisabled = false, // not supported yet, using default value instead
            leftPadding = padding.startPadding,
            topPadding = padding.topPadding,
            rightPadding = padding.endPadding,
            bottomPadding = padding.bottomPadding,
            isClipChildrenEnabled = clipChildren,
            isNestedScrollingEnabled = nestedScrollingEnabled,
            scrollBarStyle = scrollBarStyle,
            isHorizontalFadingEdgeEnabled = horizontalFadingEdgeEnabled,
            isVerticalFadingEdgeEnabled = verticalFadingEdgeEnabled,
            isLeftFadingEnabled = isLeftFadingEnabled,
            isRightFadingEnabled = isRightFadingEnabled,
            isTopFadingEnabled = isTopFadingEnabled,
            isBottomFadingEnabled = isBottomFadingEnabled,
            fadingEdgeLength = fadingEdgeLength,
            recyclerViewId = recyclerViewId,
            overScrollMode = overScrollMode,
            edgeFactory = null, // // not supported yet, using default value instead
            refreshProgressBarBackgroundColor = refreshProgressBarBackgroundColor,
            refreshProgressBarColor = refreshProgressBarColor,
        )

        onUnbind {
          unbindLegacyMountBinder(
              sectionsRecyclerView = sectionsRecyclerView,
              refreshProgressBarBackgroundColor = refreshProgressBarBackgroundColor,
              edgeFactory = null,
              snapHelper = snapHelper,
          )
        }
      }
    }

    // Its own binder, so that changing the animator's class doesn't rebind the mount binder.
    // It also rebinds with the mount binder, which picks up a new animator instance as before.
    // Unlike Recycler, the mount binder here never reattaches the adapter, so it needs no fade-in
    // skip; the adapter binder below runs after this one.
    withDescription("recycler-item-animator") {
      bind(itemAnimator?.javaClass, *mountDeps) { sectionsRecyclerView ->
        sectionsRecyclerView.setItemAnimator(itemAnimator)
        onUnbind { sectionsRecyclerView.resetItemAnimator() }
      }
    }

    // Unmount only: the mount binder above also unbinds on new props or a new measure.
    withDescription("bring-to-front-reset") {
      bind(Unit) { sectionsRecyclerView ->
        onUnbind { sectionsRecyclerView.skipsRedundantBringToFront = false }
      }
    }

    withDescription("layout-manager") {
      bind(layoutInfo, layoutConfig.enableItemPrefetch, layoutConfig.itemViewCacheSize) {
          sectionsRecyclerView ->
        val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
        val layoutManager = layoutInfo.getLayoutManager()
        layoutManager.isItemPrefetchEnabled = layoutConfig.enableItemPrefetch
        recyclerView.setItemViewCacheSize(layoutConfig.itemViewCacheSize)
        recyclerView.layoutManager = layoutManager
        onUnbind { recyclerView.layoutManager = null }
      }
    }

    withDescription("recycler-adapter") {
      bind(adapter) { sectionsRecyclerView ->
        val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
        recyclerView.adapter = adapter
        onUnbind { saveableStateRegistries.keepRowsSavedWhile { recyclerView.adapter = null } }
      }
    }

    withDescription("preparation-manager") {
      bind(preparationManager, layoutConfig.rangeRatio, adapter) { sectionsRecyclerView ->
        val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
        val onEnterRangeCallback: CollectionLayoutScope.(CollectionItem<*>) -> Unit = { item ->
          item.prepareAsync(getChildSizeConstraints(item))
        }
        val onExitRangeCallback: CollectionLayoutScope.(CollectionItem<*>) -> Unit = { item ->
          with(item) { unprepare() }
        }
        preparationManager.bind(
            recyclerView,
            layoutConfig.rangeRatio,
            onEnterRangeCallback,
            onExitRangeCallback,
        )
        onUnbind { preparationManager.unbind(recyclerView) }
      }
    }

    withDescription("recycler-scroller") {
      bind(scroller, layoutInfo) { sectionsRecyclerView ->
        val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
        scroller.bind(layoutInfo, recyclerView, adapter)
        scroller.scrollToInitPosition(recyclerView)
        onUnbind {
          scroller.rememberScrollPosition(recyclerView)
          scroller.unbind()
        }
      }
    }

    withDescription("sticky-header-controller") {
      bind(stickyHeaderController) { sectionsRecyclerView ->
        stickyHeaderController.init(sectionsRecyclerView)
        onUnbind { stickyHeaderController.reset() }
      }
    }

    withDescription("recycler-before-layout") {
      bind(onBeforeLayoutListener) { sectionsRecyclerView ->
        val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
        recyclerView.setOnBeforeLayoutListener(onBeforeLayoutListener)
        onUnbind { recyclerView.setOnBeforeLayoutListener(null) }
      }
    }

    withDescription("recycler-after-layout") {
      bind(onAfterLayoutListener) { sectionsRecyclerView ->
        val recyclerView = sectionsRecyclerView.requireLithoRecyclerView()
        recyclerView.setOnAfterLayoutListener(onAfterLayoutListener)
        onUnbind { recyclerView.setOnAfterLayoutListener(null) }
      }
    }

    withDescription("recycler-equivalent-bind") {
      bind(Any()) { sectionsRecyclerView ->
        bindLegacyAttachBinder(
            sectionsRecyclerView = sectionsRecyclerView,
            sectionsViewLogger = sectionsViewLogger,
            isPullToRefreshEnabled = pullToRefreshEnabled,
            enableKeyboardPullToRefresh =
                context.lithoConfiguration.componentsConfig.enableKeyboardPullToRefresh,
            onRefresh = onRefresh,
            onScrollListeners = onScrollListeners,
            touchInterceptor = touchInterceptor,
            onItemTouchListener = itemTouchListener,
            snapHelper = snapHelper,
            recyclerEventsController = recyclerEventsController,
        )

        onUnbind {
          unbindLegacyAttachBinder(
              sectionsRecyclerView = sectionsRecyclerView,
              recyclerEventsController = recyclerEventsController,
              onScrollListeners = onScrollListeners,
              onItemTouchListener = itemTouchListener,
          )
        }
      }
    }
  }
}

/** An internal model that helps us access these configs as dependency. */
@DataClassGenerate(toString = Mode.OMIT, equalsHashCode = Mode.KEEP)
internal data class CollectionLayoutConfig(
    val mainAxisWrapContent: Boolean = false,
    val crossAxisWrapMode: CrossAxisWrapMode = CrossAxisWrapMode.NoWrap,
    val snapHelper: SnapHelper? = null,
    @param:SnapMode val snapMode: Int = SnapUtil.SNAP_NONE,
    val rangeRatio: Float = DEFAULT_RANGE_RATIO,
    /**
     * Set whether item prefetch should be enabled on the underlying RecyclerView.LayoutManager.
     * Defaults to false.
     *
     * <p>ItemPrefetching feature of RecyclerView clashes with RecyclerBinder's compute range
     * optimization and in certain scenarios (like sticky header) it might reset ComponentTree of
     * LithoView while it is still on screen making it render blank or zero height.
     *
     * <p>As ItemPrefetching is built on top of item view cache, please do remember to set a proper
     * cache size if you want to enable this feature. Otherwise, prefetched item will be thrown into
     * the recycler pool immediately.
     *
     * See [RecyclerView.LayoutManager.setItemPrefetchEnabled].
     */
    @JvmField val enableItemPrefetch: Boolean = true,
    /**
     * Set the number of offscreen views to retain before adding them to the potentially shared
     * recycled view pool.
     *
     * <p>The offscreen view cache stays aware of changes in the attached adapter, allowing a
     * LayoutManager to reuse those views unmodified without needing to return to the adapter to
     * rebind them.
     *
     * See [RecyclerView.setItemViewCacheSize].
     */
    @JvmField val itemViewCacheSize: Int = DEFAULT_CACHE_SIZE,

    // Configs that requires regenerating a new layout info
    val orientation: CollectionOrientation = CollectionOrientation.VERTICAL,
    val reverseLayout: Boolean = false,
    val stackFromEnd: Boolean = false,
    val spanCount: Int = GridLayoutManager.DEFAULT_SPAN_COUNT,
    val gapStrategy: Int = StaggeredGridLayoutManager.GAP_HANDLING_NONE,
    val isCircular: Boolean = false,
) {

  companion object {
    // Default range ratio for the collection
    private const val DEFAULT_RANGE_RATIO: Float = 2f
    // Default item view cache size for the collection
    private const val DEFAULT_CACHE_SIZE: Int = 2
  }
}

internal fun sizeConstraintsWithoutPadding(
    constraints: SizeConstraints,
    horizontalPadding: Int,
    verticalPadding: Int,
): SizeConstraints {

  var minWidth: Int = constraints.minWidth
  var maxWidth: Int = constraints.maxWidth
  var minHeight: Int = constraints.minHeight
  var maxHeight: Int = constraints.maxHeight
  if (constraints.hasExactWidth) {
    minWidth = max(constraints.minWidth - horizontalPadding, 0)
    maxWidth = max(constraints.maxWidth - horizontalPadding, 0)
  } else if (constraints.hasBoundedWidth) {
    maxWidth = max(constraints.maxWidth - horizontalPadding, 0)
  } else {
    // don't do anything for Infinity
  }
  if (constraints.hasExactHeight) {
    minHeight = max(constraints.minHeight - verticalPadding, 0)
    maxHeight = max(constraints.maxHeight - verticalPadding, 0)
  } else if (constraints.hasBoundedHeight) {
    maxHeight = max(constraints.maxHeight - verticalPadding, 0)
  } else {
    // don't do anything for Infinity
  }
  return SizeConstraints(
      minWidth = minWidth,
      maxWidth = maxWidth,
      minHeight = minHeight,
      maxHeight = maxHeight,
  )
}

/**
 * A thread pool provider that manages a pool of background handler threads for executing tasks.
 *
 * This class creates a fixed pool of HandlerThread instances and provides round-robin access to
 * their handlers. The active pool size can be dynamically adjusted to control how many threads are
 * actually used from the pool.
 *
 * @param poolSize The total number of handler threads to create in the pool
 * @param priority The thread priority for all handler threads in the pool
 */
class PoolingThreadHandlerProvider(
    val poolSize: Int = DEFAULT_POOL_SIZE,
    private val priority: Int = DEFAULT_BACKGROUND_THREAD_PRIORITY,
) : RunnableHandlerProvider {

  private val runnableHandlers: Array<RunnableHandler> = createRunnableHandlers()
  private val index: AtomicInteger = AtomicInteger(0)

  /**
   * The number of threads from the pool that are currently active. Must not exceed [poolSize]. When
   * set, only this many threads will be used in round-robin fashion from the available pool.
   */
  var activeSize: Int = DEFAULT_ACTIVE_SIZE
    set(value) {
      require(value in 1..poolSize) {
        "Active size cannot be 0 or greater than pool size. " +
            "Active size: $value, pool size: $poolSize"
      }
      field = value
    }

  private fun createRunnableHandlers(): Array<RunnableHandler> {
    return Array(poolSize) { threadIndex ->
      val handlerThread = HandlerThread("CollectionLayoutHandler_$threadIndex", priority)
      handlerThread.start()
      DefaultHandler(handlerThread.looper)
    }
  }

  override fun getRunnableHandler(): RunnableHandler {
    val activePoolSize = minOf(activeSize, poolSize)
    val currentIndex = index.getAndIncrement()
    val positiveIndex = (currentIndex and Int.MAX_VALUE) % activePoolSize
    return runnableHandlers[positiveIndex]
  }

  companion object {
    private const val DEFAULT_ACTIVE_SIZE = 2
    private const val DEFAULT_POOL_SIZE = 5
    private const val DEFAULT_BACKGROUND_THREAD_PRIORITY = 5
  }
}
