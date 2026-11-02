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

package com.facebook.litho.sections.widget;

import static com.facebook.yoga.YogaAlign.FLEX_START;
import static com.facebook.yoga.YogaEdge.ALL;
import static com.facebook.yoga.YogaPositionType.ABSOLUTE;

import android.view.View;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemAnimator;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import androidx.recyclerview.widget.RecyclerView.OnItemTouchListener;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
import androidx.recyclerview.widget.SnapHelper;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.Column;
import com.facebook.litho.Component;
import com.facebook.litho.Component.ContainerBuilder;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.EventHandler;
import com.facebook.litho.JavaStyle;
import com.facebook.litho.LithoStartupLogger;
import com.facebook.litho.StateValue;
import com.facebook.litho.StyleCompat;
import com.facebook.litho.TouchEvent;
import com.facebook.litho.Wrapper;
import com.facebook.litho.annotations.FromTrigger;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnCreateInitialState;
import com.facebook.litho.annotations.OnCreateLayout;
import com.facebook.litho.annotations.OnDetached;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.annotations.OnTrigger;
import com.facebook.litho.annotations.OnUpdateState;
import com.facebook.litho.annotations.Param;
import com.facebook.litho.annotations.Prop;
import com.facebook.litho.annotations.PropDefault;
import com.facebook.litho.annotations.ResType;
import com.facebook.litho.annotations.State;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.config.PrimitiveRecyclerBinderStrategy;
import com.facebook.litho.sections.BaseLoadEventsHandler;
import com.facebook.litho.sections.LoadEventsHandler;
import com.facebook.litho.sections.Section;
import com.facebook.litho.sections.SectionContext;
import com.facebook.litho.sections.SectionLifecycle;
import com.facebook.litho.sections.SectionTree;
import com.facebook.litho.sections.annotations.GroupSectionSpec;
import com.facebook.litho.widget.Binder;
import com.facebook.litho.widget.LayoutInfo;
import com.facebook.litho.widget.LithoRecyclerView;
import com.facebook.litho.widget.PTRRefreshEvent;
import com.facebook.litho.widget.PostDispatchDrawListener;
import com.facebook.litho.widget.Recycler;
import com.facebook.litho.widget.RecyclerBinder;
import com.facebook.litho.widget.RecyclerBinder.CommitPolicy;
import com.facebook.litho.widget.RecyclerBinderConfig;
import com.facebook.litho.widget.RecyclerEventsController;
import com.facebook.litho.widget.SectionsRecyclerView.SectionsRecyclerViewLogger;
import com.facebook.litho.widget.StickyHeaderControllerFactory;
import com.facebook.litho.widget.ViewportInfo;
import com.facebook.litho.widget.collection.CrossAxisWrapMode;
import com.facebook.rendercore.PoolScope;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;

/**
 * A {@link Component} that renders a {@link Recycler} backed by a {@link Section} tree. See <a
 * href="https://fblitho.com/docs/recycler-collection-component">recycler-collection-component</a>
 * for details.
 *
 * <p>This {@link Component} handles the loading events from the {@link Section} hierarchy and shows
 * the appropriate error,loading or empty {@link Component} passed in as props. If either the empty
 * or the error components are not passed in and the {@link RecyclerCollectionComponent} is in one
 * of these states it will simply not render anything.
 *
 * <p>The {@link RecyclerCollectionComponent} also exposes a {@link LoadEventsHandler} and a {@link
 * OnScrollListener} as {@link Prop}s so its users can receive events about the state of the loading
 * and about the state of the {@link Recycler} scrolling.
 *
 * <p>clipToPadding, clipChildren, itemDecoration, scrollBarStyle, horizontalPadding,
 * verticalPadding and recyclerViewId {@link Prop}s will be directly applied to the {@link Recycler}
 * component.
 *
 * <p>The {@link RecyclerCollectionEventsController} {@link Prop} is a way to send commands to the
 * {@link RecyclerCollectionComponentSpec}, such as scrollTo(position) and refresh().
 *
 * <p>To trigger scrolling from the Section use {@link SectionLifecycle#requestFocus(SectionContext,
 * int)}. See <a
 * href="https://fblitho.com/docs/communicating-with-the-ui#scrolling-requestfocus">communicating-with-the-ui</a>
 * for details.
 *
 * @prop itemAnimator This prop defines the animations that take place on items as changes are made.
 *     To remove change animation use {@link NoUpdateItemAnimator}. To completely disable all
 *     animations use {@link NotAnimatedItemAnimator}.
 * @prop recyclerConfiguration: This prop adds customization. For example {@link
 *     RecyclerBinderConfiguration} allows to make {@link Recycler} circular.
 * @see Section
 * @see GroupSectionSpec
 * @deprecated Use LazyList, LazyGrid, or Recycler instead.
 */
@Deprecated
@Nullsafe(Nullsafe.Mode.LOCAL)
@LayoutSpec(events = PTRRefreshEvent.class)
public class RecyclerCollectionComponentSpec {

  @PropDefault
  public static final RecyclerConfiguration recyclerConfiguration =
      ListRecyclerConfiguration.create().build();

  @PropDefault public static final boolean nestedScrollingEnabled = true;
  @PropDefault public static final int scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY;
  @PropDefault public static final int recyclerViewId = View.NO_ID;
  @PropDefault public static final int overScrollMode = View.OVER_SCROLL_ALWAYS;

  @PropDefault protected static final boolean asyncStateUpdates = false;

  @PropDefault public static final ItemAnimator itemAnimator = new NoUpdateItemAnimator();

  @PropDefault protected static final boolean asyncPropUpdates = false;

  @PropDefault protected static final boolean setRootAsync = false;

  @PropDefault public static final boolean clipToPadding = true;
  @PropDefault public static final boolean clipChildren = true;
  @PropDefault public static final boolean incrementalMount = true;
  @PropDefault public static final int refreshProgressBarColor = 0XFF4267B2; // blue

  @PropDefault public static final boolean isLeftFadingEnabled = true;
  @PropDefault public static final boolean isRightFadingEnabled = true;
  @PropDefault public static final boolean isTopFadingEnabled = true;
  @PropDefault public static final boolean isBottomFadingEnabled = true;
  @PropDefault public static final boolean removeAccessibilityListRole = false;

  @OnCreateLayout
  static @Nullable Component onCreateLayout(
      final ComponentContext c,
      @Prop Section section,
      @Prop(optional = true) @Nullable Component loadingComponent,
      @Prop(optional = true) @Nullable Component emptyComponent,
      @Prop(optional = true) @Nullable Component errorComponent,
      @Prop(optional = true, varArg = "onScrollListener") @Nullable
          List<OnScrollListener> onScrollListeners,
      @Nullable @Prop(optional = true) final LoadEventsHandler loadEventsHandler,
      @Prop(optional = true) boolean clipToPadding,
      @Prop(optional = true) boolean clipChildren,
      @Prop(optional = true) boolean nestedScrollingEnabled,
      @Prop(optional = true) int scrollBarStyle,
      @Prop(optional = true, varArg = "itemDecoration") @Nullable
          List<ItemDecoration> itemDecorations,
      @Prop(optional = true) @Nullable ItemAnimator itemAnimator,
      @Prop(optional = true) @IdRes int recyclerViewId,
      @Prop(optional = true) int overScrollMode,
      @Prop(optional = true) @Nullable RecyclerView.EdgeEffectFactory edgeEffectFactory,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) int leftPadding,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) int rightPadding,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) int topPadding,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) int bottomPadding,
      @Prop(optional = true) boolean disableAddingPadding,
      @Prop(optional = true) @Nullable EventHandler<TouchEvent> recyclerTouchEventHandler,
      @Prop(optional = true) boolean horizontalFadingEdgeEnabled,
      @Prop(optional = true) boolean verticalFadingEdgeEnabled,
      @Prop(optional = true) boolean isLeftFadingEnabled,
      @Prop(optional = true) boolean isRightFadingEnabled,
      @Prop(optional = true) boolean isTopFadingEnabled,
      @Prop(optional = true) boolean isBottomFadingEnabled,
      @Prop(optional = true, resType = ResType.DIMEN_SIZE) int fadingEdgeLength,
      @Prop(optional = true, resType = ResType.COLOR) @Nullable
          Integer refreshProgressBarBackgroundColor,
      @Prop(optional = true, resType = ResType.COLOR) int refreshProgressBarColor,
      @Prop(optional = true) @Nullable LithoRecyclerView.TouchInterceptor touchInterceptor,
      @Prop(optional = true) @Nullable OnItemTouchListener itemTouchListener,
      @Prop(optional = true) boolean setRootAsync,
      @Prop(optional = true) boolean disablePTR,
      @Prop(optional = true) RecyclerConfiguration recyclerConfiguration,
      @Prop(optional = true) @Nullable SectionsRecyclerViewLogger sectionsViewLogger,
      @Prop(optional = true) @Nullable CharSequence recyclerContentDescription,
      @Prop(optional = true) boolean shouldExcludeFromIncrementalMount,
      // Caution: ignoreLoadingUpdates breaks loadingComponent/errorComponent/emptyComponent.
      // It's intended to be a temporary workaround, not something you should use often.
      @Prop(optional = true) boolean ignoreLoadingUpdates,
      @Prop(optional = true) @Nullable
          LithoRecyclerView.OnBeforeLayoutListener onBeforeLayoutListener,
      @Prop(optional = true) @Nullable
          LithoRecyclerView.OnAfterLayoutListener onAfterLayoutListener,
      @Prop(optional = true) boolean removeAccessibilityListRole,
      @State(canUpdateLazily = true) boolean hasSetSectionTreeRoot,
      @State RecyclerCollectionEventsController internalEventsController,
      @State LayoutInfo layoutInfo,
      @State LoadingState loadingState,
      @State Binder<RecyclerView> binder,
      @State SectionTree sectionTree,
      @State RecyclerCollectionLoadEventsHandler recyclerCollectionLoadEventsHandler,
      @State @Nullable SnapHelper snapHelper) {
    // This is a side effect from OnCreateLayout, so it's inherently prone to race conditions:
    recyclerCollectionLoadEventsHandler.setLoadEventsHandler(loadEventsHandler);

    // More side effects in OnCreateLayout. Watch out:
    if (!hasSetSectionTreeRoot) {
      RecyclerCollectionComponent.lazyUpdateHasSetSectionTreeRoot(c, true);
    }
    if (setRootAsync && hasSetSectionTreeRoot) {
      sectionTree.setRootAsync(section);
    } else {
      sectionTree.setRoot(section);
    }

    final boolean isErrorButNoErrorComponent =
        loadingState == LoadingState.ERROR && (errorComponent == null);
    final boolean isEmptyButNoEmptyComponent =
        loadingState == LoadingState.EMPTY && (emptyComponent == null);
    final boolean shouldHideComponent = isEmptyButNoEmptyComponent || isErrorButNoErrorComponent;

    if (shouldHideComponent) {
      return null;
    }

    final boolean canPTR =
        recyclerConfiguration.getOrientation() != OrientationHelper.HORIZONTAL && !disablePTR;

    RecyclerBinderConfig recyclerBinderConfig =
        recyclerConfiguration.getRecyclerBinderConfiguration().getRecyclerBinderConfig();
    ComponentsConfiguration componentsConfiguration = recyclerBinderConfig.componentsConfiguration;

    if (componentsConfiguration == null) {
      componentsConfiguration = c.getLithoConfiguration().componentsConfig;
    }

    PrimitiveRecyclerBinderStrategy primitiveRecyclerBinderStrategy =
        recyclerConfiguration.getRecyclerBinderConfiguration().getPrimitiveRecyclerBinderStrategy()
                != null
            ? recyclerConfiguration
                .getRecyclerBinderConfiguration()
                .getPrimitiveRecyclerBinderStrategy()
            : componentsConfiguration.primitiveRecyclerBinderStrategy;

    boolean shouldNotWrapContent =
        !recyclerBinderConfig.wrapContent
            && (recyclerBinderConfig.crossAxisWrapMode == CrossAxisWrapMode.NoWrap);

    /*
     * This is a hacky way to detect that the user opted by using our default implementation. We
     * detect that by comparing with the item animator property default value, and if there is a
     * match then we pass on a new instance of the same type of item animator. This is because we
     * can't reuse the same item animator across different instances of RecyclerView, or it will
     * crash.
     */
    ItemAnimator recyclerItemAnimator =
        RecyclerCollectionComponentSpec.itemAnimator == itemAnimator
            ? new NoUpdateItemAnimator()
            : itemAnimator;

    Component recyclerComponent;

    List<OnScrollListener> listenersToUse =
        onScrollListeners != null ? new ArrayList<>(onScrollListeners) : new ArrayList<>();
    listenersToUse.add(
        new RecyclerCollectionOnScrollListener(internalEventsController, layoutInfo));

    JavaStyle javaStyle = StyleCompat.touchHandler(recyclerTouchEventHandler).flexShrink(0f);

    if (shouldNotWrapContent) {
      javaStyle.positionType(ABSOLUTE).positionPx(ALL, 0);
    }

    recyclerComponent =
        new Recycler(
            binder,
            primitiveRecyclerBinderStrategy,
            true,
            clipToPadding,
            leftPadding,
            topPadding,
            rightPadding,
            bottomPadding,
            refreshProgressBarBackgroundColor,
            refreshProgressBarColor,
            clipChildren,
            nestedScrollingEnabled,
            scrollBarStyle,
            itemDecorations,
            horizontalFadingEdgeEnabled,
            verticalFadingEdgeEnabled,
            isLeftFadingEnabled,
            isRightFadingEnabled,
            isTopFadingEnabled,
            isBottomFadingEnabled,
            fadingEdgeLength,
            edgeEffectFactory,
            recyclerViewId,
            overScrollMode,
            recyclerContentDescription,
            recyclerItemAnimator,
            internalEventsController,
            listenersToUse,
            snapHelper,
            canPTR,
            touchInterceptor,
            itemTouchListener,
            canPTR
                ? () -> {
                  refreshContent(c, sectionTree, ignoreLoadingUpdates);
                  return Unit.INSTANCE;
                }
                : null,
            sectionsViewLogger,
            shouldExcludeFromIncrementalMount,
            disableAddingPadding,
            onBeforeLayoutListener,
            onAfterLayoutListener,
            removeAccessibilityListRole,
            javaStyle.build());

    final ContainerBuilder<?> containerBuilder =
        Column.create(c).flexShrink(0).alignContent(FLEX_START).child(recyclerComponent);

    if (loadingState == LoadingState.LOADING && loadingComponent != null) {
      containerBuilder.child(
          Wrapper.create(c)
              .delegate(loadingComponent)
              .flexShrink(0)
              .positionType(ABSOLUTE)
              .positionPx(ALL, 0));
    } else if (loadingState == LoadingState.EMPTY) {
      containerBuilder.child(
          Wrapper.create(c)
              .delegate(emptyComponent)
              .flexShrink(0)
              .positionType(ABSOLUTE)
              .positionPx(ALL, 0));
    } else if (loadingState == LoadingState.ERROR) {
      containerBuilder.child(
          Wrapper.create(c)
              .delegate(errorComponent)
              .flexShrink(0)
              .positionType(ABSOLUTE)
              .positionPx(ALL, 0));
    }

    return containerBuilder.build();
  }

  @OnCreateInitialState
  static void createInitialState(
      final ComponentContext c,
      StateValue<SnapHelper> snapHelper,
      StateValue<SectionTree> sectionTree,
      StateValue<RecyclerCollectionLoadEventsHandler> recyclerCollectionLoadEventsHandler,
      StateValue<Binder<RecyclerView>> binder,
      StateValue<LoadingState> loadingState,
      StateValue<RecyclerCollectionEventsController> internalEventsController,
      StateValue<LayoutInfo> layoutInfo,
      StateValue<PoolScope.ManuallyManaged> collectionRecyclerPoolScope,
      @Prop Section section,
      @Prop(optional = true) RecyclerConfiguration recyclerConfiguration,
      @Prop(optional = true) @Nullable RecyclerCollectionEventsController eventsController,
      @Prop(optional = true) boolean asyncPropUpdates,
      @Prop(optional = true) boolean asyncStateUpdates,
      // NB: This is a *workaround* for sections that use non-threadsafe models, e.g. models that
      // may be updated from the main thread while background changesets would be calculated. It has
      // negative performance implications since it forces all changesets to be calculated on the
      // main thread!
      @Prop(optional = true) boolean forceSyncStateUpdates,
      // Caution: ignoreLoadingUpdates breaks loadingComponent/errorComponent/emptyComponent.
      // It's intended to be a temporary workaround, not something you should use often.
      @Prop(optional = true) boolean ignoreLoadingUpdates,
      @Prop(optional = true) @Nullable String sectionTreeTag,
      // Don't use this. If false, off incremental mount for all subviews of this Recycler.
      @Prop(optional = true) boolean incrementalMount,
      @Prop(optional = true) @Nullable LithoStartupLogger startupLogger,
      @Prop(optional = true) @Nullable StickyHeaderControllerFactory stickyHeaderControllerFactory,
      @Prop(optional = true) @Nullable
          List<PostDispatchDrawListener> additionalPostDispatchDrawListeners) {

    RecyclerBinderConfiguration binderConfiguration =
        recyclerConfiguration.getRecyclerBinderConfiguration();

    final LayoutInfo newLayoutInfo = recyclerConfiguration.getLayoutInfo(c);
    layoutInfo.set(newLayoutInfo);

    RecyclerBinderConfig recyclerBinderConfig = binderConfiguration.getRecyclerBinderConfig();
    ComponentsConfiguration componentsConfiguration =
        (recyclerBinderConfig.componentsConfiguration != null)
            ? recyclerBinderConfig.componentsConfiguration
            : c.getLithoConfiguration().componentsConfig;

    PoolScope.ManuallyManaged poolScope = new PoolScope.ManuallyManaged();
    collectionRecyclerPoolScope.set(poolScope);

    RecyclerBinder.Builder recyclerBinderBuilder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(
                RecyclerBinderConfig.create(recyclerBinderConfig)
                    .componentsConfiguration(
                        ComponentsConfiguration.create(componentsConfiguration)
                            .incrementalMountEnabled(
                                incrementalMount && componentsConfiguration.incrementalMountEnabled)
                            .build())
                    .build())
            .layoutInfo(newLayoutInfo)
            .stickyHeaderControllerFactory(stickyHeaderControllerFactory)
            .startupLogger(startupLogger)
            .poolScope(poolScope)
            .keySaveableStateByComponent();

    if (additionalPostDispatchDrawListeners != null) {
      recyclerBinderBuilder.addAdditionalPostDispatchDrawListeners(
          additionalPostDispatchDrawListeners);
    }

    RecyclerBinder recyclerBinder = recyclerBinderBuilder.build(c);

    SectionBinderTarget targetBinder =
        new SectionBinderTarget(recyclerBinder, binderConfiguration.getUseBackgroundChangeSets());

    final SectionContext sectionContext = new SectionContext(c);
    binder.set(targetBinder);
    snapHelper.set(recyclerConfiguration.getSnapHelper());

    final SectionTree sectionTreeInstance =
        SectionTree.create(sectionContext, targetBinder)
            .tag(
                sectionTreeTag == null || sectionTreeTag.equals("")
                    ? section.getSimpleName()
                    : sectionTreeTag)
            .asyncPropUpdates(asyncPropUpdates)
            .asyncStateUpdates(asyncStateUpdates)
            .forceSyncStateUpdates(forceSyncStateUpdates)
            .changeSetThreadHandler(binderConfiguration.getChangeSetThreadHandler())
            .postToFrontOfQueueForFirstChangeset(
                binderConfiguration.isPostToFrontOfQueueForFirstChangeset())
            .build();
    sectionTree.set(sectionTreeInstance);

    final RecyclerCollectionEventsController internalEventsControllerInstance =
        eventsController != null ? eventsController : new RecyclerCollectionEventsController();
    internalEventsControllerInstance.setSectionTree(sectionTreeInstance);
    internalEventsControllerInstance.setSnapMode(recyclerConfiguration.getSnapMode());
    internalEventsController.set(internalEventsControllerInstance);

    final RecyclerCollectionLoadEventsHandler recyclerCollectionLoadEventsHandlerInstance =
        new RecyclerCollectionLoadEventsHandler(
            c, internalEventsControllerInstance, ignoreLoadingUpdates);
    recyclerCollectionLoadEventsHandler.set(recyclerCollectionLoadEventsHandlerInstance);
    sectionTreeInstance.setLoadEventsHandler(recyclerCollectionLoadEventsHandlerInstance);

    final ViewportInfo.ViewportChanged viewPortChanged =
        new ViewportInfo.ViewportChanged() {
          @Override
          public void viewportChanged(
              int firstVisibleIndex,
              int lastVisibleIndex,
              int firstFullyVisibleIndex,
              int lastFullyVisibleIndex,
              int state) {
            sectionTreeInstance.viewPortChanged(
                firstVisibleIndex,
                lastVisibleIndex,
                firstFullyVisibleIndex,
                lastFullyVisibleIndex,
                state);
          }
        };

    targetBinder.setViewportChangedListener(viewPortChanged);

    if (ignoreLoadingUpdates) {
      loadingState.set(LoadingState.LOADED);
    } else {
      loadingState.set(LoadingState.LOADING);
    }
  }

  @OnUpdateState
  static void updateLoadingState(
      StateValue<LoadingState> loadingState, @Param LoadingState currentLoadingState) {
    loadingState.set(currentLoadingState);
  }

  @OnEvent(PTRRefreshEvent.class)
  protected static boolean onRefresh(
      ComponentContext c,
      @Param SectionTree sectionTree,
      @Prop(optional = true) boolean ignoreLoadingUpdates) {
    refreshContent(c, sectionTree, ignoreLoadingUpdates);
    return true;
  }

  private static void refreshContent(
      ComponentContext c, SectionTree sectionTree, boolean ignoreLoadingUpdates) {
    EventHandler<PTRRefreshEvent> ptrEventHandler =
        RecyclerCollectionComponent.getPTRRefreshEventHandler(c);

    if (!ignoreLoadingUpdates || ptrEventHandler == null) {
      sectionTree.refresh();
      return;
    }

    final boolean isHandled = RecyclerCollectionComponent.dispatchPTRRefreshEvent(ptrEventHandler);
    if (!isHandled) {
      sectionTree.refresh();
    }
  }

  @OnTrigger(ScrollEvent.class)
  static void onScroll(
      ComponentContext c,
      @FromTrigger int position,
      @FromTrigger boolean animate,
      @State SectionTree sectionTree,
      @State RecyclerCollectionEventsController internalEventsController) {
    sectionTree.requestFocusOnRoot(position);
  }

  @OnTrigger(RecyclerDynamicConfigEvent.class)
  static void onRecyclerConfigChanged(
      ComponentContext c,
      @FromTrigger @CommitPolicy int commitPolicy,
      @State SectionTree sectionTree) {
    sectionTree.setTargetConfig(new SectionTree.Target.DynamicConfig(commitPolicy));
  }

  @OnTrigger(ClearRefreshingEvent.class)
  static void onClearRefreshing(
      ComponentContext c, @State RecyclerCollectionEventsController internalEventsController) {
    internalEventsController.clearRefreshing();
  }

  @OnDetached
  static void onDetached(
      ComponentContext c,
      @State Binder<RecyclerView> binder,
      @State PoolScope.ManuallyManaged collectionRecyclerPoolScope) {
    binder.detach();
    collectionRecyclerPoolScope.releaseScope();
  }

  private static class RecyclerCollectionOnScrollListener extends OnScrollListener {

    private final RecyclerCollectionEventsController mEventsController;
    private final LayoutInfo mLayoutInfo;

    private RecyclerCollectionOnScrollListener(
        RecyclerCollectionEventsController eventsController, LayoutInfo layoutInfo) {
      mEventsController = eventsController;
      mLayoutInfo = layoutInfo;
    }

    @Override
    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
      super.onScrolled(recyclerView, dx, dy);
      mEventsController.updateFirstLastFullyVisibleItemPositions(mLayoutInfo);
    }
  }

  @VisibleForTesting
  public enum LoadingState {
    /** We're loading but don't have any content yet. */
    LOADING,
    /** A load completed and we have content. */
    LOADED,
    /** A load completed, but the content is empty. */
    EMPTY,
    /** A load failed with an error. */
    ERROR,
  }

  static class RecyclerCollectionLoadEventsHandler extends BaseLoadEventsHandler {

    private @Nullable LoadEventsHandler mDelegate;
    private LoadingState mLastState = LoadingState.LOADING;
    private final ComponentContext mComponentContext;
    private final RecyclerEventsController mRecyclerEventsController;
    private final boolean mIgnoreLoadingUpdates;

    private RecyclerCollectionLoadEventsHandler(
        ComponentContext c,
        RecyclerEventsController recyclerEventsController,
        boolean ignoreLoadingUpdates) {
      mComponentContext = c;
      mRecyclerEventsController = recyclerEventsController;
      mIgnoreLoadingUpdates = ignoreLoadingUpdates;
    }

    /** May be called from any thread (in OnCreateLayout). (Does this need synchronization?) */
    public void setLoadEventsHandler(@Nullable LoadEventsHandler delegate) {
      mDelegate = delegate;
    }

    /**
     * One would hope this is only called from one thread, since onLoadSucceeded could arrive before
     * onLoadStarted if you post them on different threads. But use synchronized to defend against
     * bad clients.
     *
     * <p>This method exists to avoid thrashing Litho with state updates as we do a bunch of load
     * operations. In theory you could call updateLoadingStateAsync every single time and get the
     * same result, but it's more efficient to avoid all the unnecessary updates.
     */
    private synchronized void updateState(LoadingState newState) {
      if (mIgnoreLoadingUpdates) {
        return;
      }
      if (mLastState != newState) {
        mLastState = newState;
        RecyclerCollectionComponent.updateLoadingStateAsync(mComponentContext, newState);
      }
    }

    @Override
    public void onLoadStarted(boolean empty) {
      updateState(empty ? LoadingState.LOADING : LoadingState.LOADED);

      final LoadEventsHandler delegate = mDelegate;
      if (delegate != null) {
        delegate.onLoadStarted(empty);
      }
    }

    @Override
    public void onLoadSucceeded(boolean empty) {
      updateState(empty ? LoadingState.EMPTY : LoadingState.LOADED);

      mRecyclerEventsController.clearRefreshing();

      final LoadEventsHandler delegate = mDelegate;
      if (delegate != null) {
        delegate.onLoadSucceeded(empty);
      }
    }

    @Override
    public void onLoadFailed(boolean empty) {
      updateState(empty ? LoadingState.ERROR : LoadingState.LOADED);

      mRecyclerEventsController.clearRefreshing();

      final LoadEventsHandler delegate = mDelegate;
      if (delegate != null) {
        delegate.onLoadFailed(empty);
      }
    }

    @Override
    public void onInitialLoad() {
      final LoadEventsHandler delegate = mDelegate;
      if (delegate != null) {
        delegate.onInitialLoad();
      }
    }
  }
}
