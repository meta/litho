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

package com.facebook.litho.sections;

import static com.facebook.litho.ComponentTree.STATE_UPDATES_IN_LOOP_THRESHOLD;
import static com.facebook.litho.ThreadUtils.assertMainThread;
import static com.facebook.litho.ThreadUtils.isMainThread;
import static com.facebook.litho.sections.DebugEvents.APPLY_CHANGE_SET;
import static com.facebook.litho.sections.SectionsLogEventUtils.ApplyNewChangeSet.SET_ROOT_ASYNC;
import static com.facebook.litho.sections.SectionsLogEventUtils.ApplyNewChangeSet.UPDATE_STATE_ASYNC;
import static com.facebook.rendercore.debug.DebugEventAttribute.Async;
import static com.facebook.rendercore.debug.DebugEventAttribute.Id;
import static com.facebook.rendercore.debug.DebugEventAttribute.Name;
import static com.facebook.rendercore.debug.DebugEventAttribute.Source;
import static com.facebook.rendercore.instrumentation.HandlerInstrumenter.instrumentHandler;

import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import com.facebook.infer.annotation.ThreadConfined;
import com.facebook.litho.CollectionsUtils;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentsReporter;
import com.facebook.litho.ComponentsSystrace;
import com.facebook.litho.EventHandler;
import com.facebook.litho.EventHandlersController;
import com.facebook.litho.EventTrigger;
import com.facebook.litho.EventTriggersContainer;
import com.facebook.litho.GlobalKey;
import com.facebook.litho.Handle;
import com.facebook.litho.LithoStartupLogger;
import com.facebook.litho.StateContainer;
import com.facebook.litho.StringGlobalKey;
import com.facebook.litho.ThreadTracingRunnable;
import com.facebook.litho.ThreadUtils;
import com.facebook.litho.TreePropContainer;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.sections.ChangesetDebugConfiguration.ChangesetDebugInfo;
import com.facebook.litho.sections.ChangesetDebugConfiguration.ChangesetDebugListener;
import com.facebook.litho.sections.SectionsLogEventUtils.ApplyNewChangeSet;
import com.facebook.litho.sections.config.SectionsConfiguration;
import com.facebook.litho.sections.logger.SectionsDebugLogger;
import com.facebook.litho.stats.LithoStats;
import com.facebook.litho.widget.ChangeSetCompleteCallback;
import com.facebook.litho.widget.RecyclerBinder.CommitPolicy;
import com.facebook.litho.widget.RenderInfo;
import com.facebook.litho.widget.SectionsDebug;
import com.facebook.litho.widget.SmoothScrollAlignmentType;
import com.facebook.litho.widget.ViewportInfo;
import com.facebook.rendercore.RenderState;
import com.facebook.rendercore.RunnableHandler;
import com.facebook.rendercore.RunnableHandler.DefaultHandler;
import com.facebook.rendercore.debug.DebugEventDispatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import kotlin.Unit;

/**
 * Represents a tree of {@link Section} and manages their lifecycle. {@link SectionTree} takes a
 * root {@link Section} and generates the complete tree by recursively invoking OnCreateChildren.
 * {@link SectionTree} is also responsible for regenerating the tree in response to state update
 * events.
 */
public class SectionTree {

  private static class Range {

    private int firstVisibleIndex;
    private int lastVisibleIndex;
    private int firstFullyVisibleIndex;
    private int lastFullyVisibleIndex;
    private int totalItemsCount;
  }

  private static final String DEFAULT_CHANGESET_THREAD_NAME = "SectionChangeSetThread";
  private static final String OUT_OF_BOUNDS_REQUEST_FOCUS = "SectionTree:OutOfBoundsRequestFocus";
  private static final String NULL_TO_NON_NULL_SERVICE_TRANSFER =
      "SectionTree:NullToNonNullServiceTransfer";
  private static final String STATE_UPDATES_IN_LOOP_EXCEED_THRESHOLD =
      "SectionTree:StateUpdatesFromInsideChangeSetCalculateExceedsThreshold";

  private final SectionsDebugLogger mSectionsDebugLogger;
  private volatile boolean mReleased;

  /**
   * The class implementing this interface will be responsible to translate the ChangeSet into UI
   * updates.
   */
  public interface Target {

    /** Notify that a {@link Component} was added at index. */
    void insert(int index, RenderInfo renderInfo);

    void insertRange(int index, int count, List<RenderInfo> renderInfos);

    /** Notify that a {@link Component} was updated at index. */
    void update(int index, RenderInfo renderInfo);

    void updateRange(int index, int count, List<RenderInfo> renderInfos);

    /** Notify that the {@link Component} at index was deleted. */
    void delete(int index);

    void deleteRange(int index, int count);

    /** Notify that a {@link Component} was moved fromPosition toPosition. */
    void move(int fromPosition, int toPosition);

    /** Called when a changeset has finished being applied. */
    void notifyChangeSetComplete(
        boolean isDataChanged, ChangeSetCompleteCallback changeSetCompleteCallback);

    /** Request focus on the item with the given index. */
    void requestFocus(int index);

    /** Request smooth focus on the item with the given index. */
    void requestSmoothFocus(int index, int offset, SmoothScrollAlignmentType type);

    /** Request smooth focus on the item with the given Handle. */
    void requestSmoothFocus(Object id, int offset, SmoothScrollAlignmentType type);

    /** Request focus on the item with the given index, plus some additional offset. */
    void requestFocusWithOffset(int index, int offset);

    /** Request focus on the item with the given id, plus some additional offset. */
    void requestFocusWithOffset(Object id, int offset);

    /**
     * @return whether this target supports applying change sets from a background thread.
     */
    boolean supportsBackgroundChangeSets();

    /** Notify this target that a new set of configurations is applied. */
    void changeConfig(DynamicConfig dynamicConfig);

    class DynamicConfig {

      public final @CommitPolicy int mChangeSetsCommitPolicy;

      public DynamicConfig(@CommitPolicy int changeSetsCommitPolicy) {
        mChangeSetsCommitPolicy = changeSetsCommitPolicy;
      }
    }
  }

  private static final String EMPTY_STRING = "";
  private static final String INDEX_OUT_OF_BOUNDS_DEBUG_MESSAGE =
      "Index out of bounds while applying a new section. This indicates a bad diff was sent to the"
          + " RecyclerBinder. See"
          + " https://fblitho.com/docs/sections/best-practices/#avoiding-indexoutofboundsexception"
          + " for more information. Debug info: ";

  @GuardedBy("SectionTree.class")
  private static volatile Looper sDefaultChangeSetThreadLooper;

  private final RunnableHandler mMainThreadHandler;
  private final SectionContext mContext;
  private final BatchedTarget mTarget;
  private final FocusDispatcher mFocusDispatcher;
  private final boolean mAsyncStateUpdates;
  private final boolean mAsyncPropUpdates;
  private final String mTag;
  private final Map<String, Range> mLastRanges = new HashMap<>();
  private final boolean mForceSyncStateUpdates;
  private final boolean mUseBackgroundChangeSets;
  private final @Nullable ChangesetDebugListener mChangesetDebug;

  private LoadEventsHandler mLoadEventsHandler;

  private final CalculateChangeSetRunnable mCalculateChangeSetOnMainThreadRunnable;
  private final CalculateChangeSetRunnable mCalculateChangeSetRunnable;

  private class CalculateChangeSetRunnable extends ThreadTracingRunnable {

    private final RunnableHandler mHandler;

    @GuardedBy("this")
    private boolean mIsPosted;

    @GuardedBy("this")
    private @ApplyNewChangeSet int mSource = ApplyNewChangeSet.NONE;

    @GuardedBy("this")
    private @Nullable String mAttribution;

    private @Nullable ChangesetDebugInfo mChangesetDebugInfo;

    public CalculateChangeSetRunnable(RunnableHandler handler) {
      mHandler = handler;
    }

    public synchronized void ensurePosted(
        @ApplyNewChangeSet int source,
        @Nullable String attribution,
        @Nullable ChangesetDebugInfo changesetDebugInfo) {
      if (!mIsPosted) {
        mIsPosted = true;
        resetTrace();

        String tag = EMPTY_STRING;
        if (mHandler.isTracing()) {
          StringBuilder sb =
              new StringBuilder("SectionTree.CalculateChangeSetRunnable.ensurePosted - ")
                  .append(SectionTree.this.mTag)
                  .append(" - ")
                  .append(source);
          if (attribution != null) {
            sb.append(" - ").append(attribution);
          }
          tag = sb.toString();
        }

        mHandler.post(this, tag);
        mSource = source;
        mAttribution = attribution;
        mChangesetDebugInfo = changesetDebugInfo;
      }
    }

    public synchronized void cancel() {
      if (mIsPosted) {
        mIsPosted = false;
        mSource = ApplyNewChangeSet.NONE;
        mAttribution = null;
        mHandler.remove(this);
      }
    }

    @Override
    public void tracedRun() {
      @ApplyNewChangeSet int source;
      final String attribution;
      final ChangesetDebugInfo changesetDebugInfo = mChangesetDebugInfo;
      synchronized (this) {
        if (!mIsPosted) {
          return;
        }
        source = mSource;
        attribution = mAttribution;
        mSource = ApplyNewChangeSet.NONE;
        mAttribution = null;
        mIsPosted = false;
      }

      try {
        applyNewChangeSet(source, attribution, changesetDebugInfo);
      } catch (IndexOutOfBoundsException e) {
        throw maybeWrapWithDuplicatesMetadata(SectionTree.this, mCurrentSection, e);
      }
    }
  }

  @GuardedBy("this")
  private @Nullable Section mCurrentSection;

  @GuardedBy("this")
  private @Nullable Section mPreviousSection;

  @GuardedBy("this")
  private @Nullable Section mNextSection;

  @GuardedBy("this")
  private boolean mIsChangeSetCalculationInProgress;

  @GuardedBy("this")
  private int mStateUpdatesFromChangeSetCount;

  @ThreadConfined(ThreadConfined.UI)
  private @Nullable Section mBoundSection;

  @GuardedBy("this")
  private StateUpdatesHolder mPendingStateUpdates;

  @GuardedBy("this")
  private List<ChangeSet> mPendingChangeSets;

  /** Map of all cached values that are stored for the current ComponentTree. */
  @GuardedBy("this")
  @Nullable
  private Map<Object, Object> mCachedValues;

  private final AtomicBoolean mPostToFrontOfQueueForFirstChangeset;

  private final EventHandlersController mEventHandlersController = new EventHandlersController();

  private final EventTriggersContainer mEventTriggersContainer = new EventTriggersContainer();

  private synchronized void bindTriggerHandler(Section section) {
    section.recordEventTrigger(section.getScopedContext(), mEventTriggersContainer);

    final List<Section> children = section.getChildren();
    if (children != null) {
      for (int i = 0, size = children.size(); i < size; i++) {
        bindTriggerHandler(children.get(i));
      }
    }
  }

  private void clearUnusedTriggerHandlers() {
    mEventTriggersContainer.clear();
  }

  @VisibleForTesting
  EventHandlersController getEventHandlersController() {
    return mEventHandlersController;
  }

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  @Nullable
  synchronized EventTrigger getEventTrigger(Object triggerKey) {
    return mEventTriggersContainer.getEventTrigger(triggerKey);
  }

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  @Nullable
  synchronized EventTrigger getEventTrigger(Handle handle, int methodId) {
    return mEventTriggersContainer.getEventTrigger(handle, methodId);
  }

  private SectionTree(Builder builder) {
    mMainThreadHandler = instrumentHandler(new DefaultHandler(Looper.getMainLooper()));
    mSectionsDebugLogger = new Logger(SectionsConfiguration.LOGGERS);
    mReleased = false;
    mAsyncStateUpdates = builder.mAsyncStateUpdates;
    mForceSyncStateUpdates = builder.mForceSyncStateUpdates;
    if (mAsyncStateUpdates && mForceSyncStateUpdates) {
      throw new RuntimeException("Cannot force both sync and async state updates at the same time");
    }

    mAsyncPropUpdates = builder.mAsyncPropUpdates;
    mTag = builder.mTag;
    mTarget = new BatchedTarget(builder.mTarget, mSectionsDebugLogger, mTag);
    mUseBackgroundChangeSets = mTarget.supportsBackgroundChangeSets();
    mFocusDispatcher = new FocusDispatcher(mTarget);

    mContext = SectionContext.withSectionTree(builder.mContext, this);
    mPendingChangeSets = new ArrayList<>();
    mPendingStateUpdates = new StateUpdatesHolder();
    RunnableHandler changeSetThreadHandler =
        builder.mChangeSetThreadHandler != null
            ? builder.mChangeSetThreadHandler
            : new DefaultHandler(getDefaultChangeSetThreadLooper());
    changeSetThreadHandler = instrumentHandler(changeSetThreadHandler);
    mCalculateChangeSetRunnable = new CalculateChangeSetRunnable(changeSetThreadHandler);
    mCalculateChangeSetOnMainThreadRunnable = new CalculateChangeSetRunnable(mMainThreadHandler);
    mChangesetDebug = ChangesetDebugConfiguration.getListener();
    mPostToFrontOfQueueForFirstChangeset =
        new AtomicBoolean(builder.mPostToFrontOfQueueForFirstChangeset);
  }

  /**
   * Create a {@link Builder} that can be used to configure a {@link SectionTree}.
   *
   * @param context The {@link SectionContext} taht will be used to create the child {@link
   *     com.facebook.litho.Component}s
   * @param target The {@link Target} that will be responsible to apply the {@link ChangeSet} to the
   *     UI.
   */
  public static Builder create(SectionContext context, Target target) {
    return new Builder(context, target);
  }

  public SectionContext getContext() {
    return mContext;
  }

  /**
   * Update the root Section. This will create the new Section tree and generate a {@link ChangeSet}
   * to be applied to the UI. In response to this {@link Target#applyNewChangeSet(int)} ()} will be
   * invoked once the {@link ChangeSet} has been calculated. The generation of the ChangeSet will
   * happen synchronously in the thread calling this method.
   *
   * @param section The new root.
   */
  public void setRoot(Section section) {
    boolean isFirstSetRoot;
    synchronized (this) {
      if (mReleased) {
        throw new IllegalStateException("Setting root on a released tree");
      }

      if (mCurrentSection != null && mCurrentSection.getId() == section.getId()) {
        return;
      }

      if (mNextSection != null && mNextSection.getId() == section.getId()) {
        return;
      }

      mNextSection = copy(section, false);
      isFirstSetRoot = mCurrentSection == null;
    }

    final @Nullable Integer traceId =
        DebugEventDispatcher.generateTraceIdentifier(DebugEvents.SET_ROOT);
    if (traceId != null) {
      DebugEventDispatcher.beginTrace(
          traceId,
          DebugEvents.SET_ROOT,
          "-1",
          attributes -> {
            attributes.put(Id, hashCode());
            attributes.put(Name, (section != null ? section.getSimpleName() : "null"));
            attributes.put(Async, mAsyncPropUpdates && !isFirstSetRoot);
            return Unit.INSTANCE;
          });
    }

    try {
      if (mAsyncPropUpdates && !isFirstSetRoot) {
        final ChangesetDebugInfo changesetDebugInfo =
            mChangesetDebug == null
                ? null
                : new ChangesetDebugInfo(
                    SET_ROOT_ASYNC,
                    section.getSimpleName(),
                    Thread.currentThread().getStackTrace());
        mCalculateChangeSetRunnable.ensurePosted(SET_ROOT_ASYNC, null, changesetDebugInfo);
      } else {
        final ChangesetDebugInfo changesetDebugInfo =
            mChangesetDebug == null
                ? null
                : new ChangesetDebugInfo(
                    ApplyNewChangeSet.SET_ROOT,
                    section.getSimpleName(),
                    Thread.currentThread().getStackTrace());
        applyNewChangeSet(ApplyNewChangeSet.SET_ROOT, null, changesetDebugInfo);
      }
    } finally {
      if (traceId != null) {
        DebugEventDispatcher.endTrace(traceId);
      }
    }
  }

  /**
   * Update the root Section. This will create the new Section tree and generate a {@link ChangeSet}
   * to be applied to the UI. In response to this {@link Target#applyNewChangeSet(int, String,
   * Throwable)} will be invoked once the {@link ChangeSet} has been calculated. The generation of
   * the ChangeSet will happen asynchronously in this SectionTree ChangeSetThread.
   *
   * @param section The new root.
   */
  public void setRootAsync(Section section) {
    if (mReleased) {
      throw new IllegalStateException("Setting root on a released tree");
    }

    synchronized (this) {
      if (mCurrentSection != null && mCurrentSection.getId() == section.getId()) {
        return;
      }

      if (mNextSection != null && mNextSection.getId() == section.getId()) {
        return;
      }

      mNextSection = copy(section, false);
    }

    final ChangesetDebugInfo changesetDebugInfo =
        mChangesetDebug == null
            ? null
            : new ChangesetDebugInfo(
                SET_ROOT_ASYNC, section.getSimpleName(), Thread.currentThread().getStackTrace());
    mCalculateChangeSetRunnable.ensurePosted(SET_ROOT_ASYNC, null, changesetDebugInfo);
  }

  /**
   * Asks all the {@link Section} in the tree to refresh themselves. The refresh is by default a
   * no-op. {@link Section}s that need a refresh behaviour should implement a method annotated with
   * {@link com.facebook.litho.sections.annotations.OnRefresh}.
   */
  public void refresh() {
    final Section section;

    synchronized (this) {
      if (mReleased) {
        throw new IllegalStateException("Calling refresh on a released tree");
      }

      section = mCurrentSection;
    }

    if (section == null) {
      return;
    }

    refreshRecursive(section);
  }

  /**
   * Sets the {@link LoadEventsHandler} that will be used to dispatch {@link LoadingEvent} outside
   * this {@link SectionTree}.
   */
  public void setLoadEventsHandler(LoadEventsHandler loadEventsHandler) {
    mLoadEventsHandler = loadEventsHandler;
  }

  /**
   * Set a new set of configurations to the {@link Target}. Only those allowed to be modified will
   * be included in {@link Target.DynamicConfig}.
   */
  public void setTargetConfig(Target.DynamicConfig dynamicConfig) {
    if (mUseBackgroundChangeSets) {
      synchronized (this) {
        // applyChangeSetsToTargetUnchecked() in background will be computed in a synchronized
        // block, and the config is only updated after the ongoing computation is completed.
        mTarget.changeConfig(dynamicConfig);
      }
    }
  }

  private void refreshRecursive(Section section) {
    section.refresh(section.getScopedContext());

    if (!section.isDiffSectionSpec()) {
      final List<Section> children = section.getChildren();
      for (int i = 0, size = children.size(); i < size; i++) {
        refreshRecursive(children.get(i));
      }
    }
  }

  @UiThread
  private void dataBound(Section currentSection) {
    ThreadUtils.assertMainThread();

    if (currentSection != null) {
      mBoundSection = currentSection;
      dataBoundRecursive(currentSection);
    }
  }

  @UiThread
  private void dataBoundRecursive(Section section) {

    section.dataBound(section.getScopedContext());

    if (section.isDiffSectionSpec()) {
      return;
    }

    final List<Section> children = section.getChildren();
    for (int i = 0, size = children.size(); i < size; i++) {
      dataBoundRecursive(children.get(i));
    }
  }

  @UiThread
  private void dataRendered(
      Section currentSection,
      boolean isDataChanged,
      boolean isMounted,
      long uptimeMillis,
      ChangesInfo changesInfo) {
    ThreadUtils.assertMainThread();

    if (currentSection != null) {
      dataRenderedRecursive(currentSection, isDataChanged, isMounted, uptimeMillis, changesInfo, 0);
    }
  }

  @UiThread
  private void dataRenderedRecursive(
      Section section,
      boolean isDataChanged,
      boolean isMounted,
      long uptimeMillis,
      ChangesInfo changesInfo,
      int globalOffset) {
    if (section.isDiffSectionSpec()) {
      return;
    }

    int firstVisibleIndex = -1;
    int lastVisibleIndex = -1;

    final Range currentRange = mLastRanges.get(section.getGlobalKey());
    if (currentRange != null) {
      firstVisibleIndex = currentRange.firstVisibleIndex;
      lastVisibleIndex = currentRange.lastVisibleIndex;
    }

    section.dataRendered(
        section.getScopedContext(),
        isDataChanged,
        isMounted,
        uptimeMillis,
        firstVisibleIndex,
        lastVisibleIndex,
        changesInfo,
        globalOffset);

    final List<Section> children = section.getChildren();
    for (int i = 0, size = children.size(); i < size; i++) {
      final Section child = children.get(i);
      dataRenderedRecursive(
          child, isDataChanged, isMounted, uptimeMillis, changesInfo, globalOffset);
      globalOffset += child.getCount();
    }
  }

  /** Calculates the global starting index for each section in the hierarchy. */
  @UiThread
  private SectionLocationInfo findSectionForKey(String key) {
    if (mBoundSection == null) {
      throw new IllegalStateException(
          "You cannot call requestFocus methods before dataBound() is called!");
    }

    final SectionLocationInfo sectionLocationInfo =
        findSectionForKeyRecursive(mBoundSection, key, 0);
    if (sectionLocationInfo == null) {
      throw new SectionKeyNotFoundException(
          "Did not find section with key '"
              + key
              + "'! Currently bound section's global key is '"
              + mBoundSection.getGlobalKey()
              + "'");
    }

    return sectionLocationInfo;
  }

  /** Calculates the global starting index for each section in the hierarchy. */
  @Nullable
  private SectionLocationInfo findSectionForKeyRecursive(
      @Nullable Section root, String key, int prevChildrenCount) {
    if (root == null) {
      return null;
    }

    if (key.equals(root.getGlobalKey())) {
      return new SectionLocationInfo(root, prevChildrenCount);
    }

    final List<Section> children = root.getChildren();
    if (CollectionsUtils.isNullOrEmpty(children)) {
      return null;
    }

    int currentChildrenCount = 0;
    for (int i = 0, size = children.size(); i < size; i++) {
      final Section child = children.get(i);
      final SectionLocationInfo result =
          findSectionForKeyRecursive(child, key, prevChildrenCount + currentChildrenCount);
      if (result != null) {
        return result;
      }
      currentChildrenCount += child.getCount();
    }

    return null;
  }

  public void viewPortChangedFromScrolling(
      int firstVisibleIndex,
      int lastVisibleIndex,
      int firstFullyVisibleIndex,
      int lastFullyVisibleIndex) {
    viewPortChanged(
        firstVisibleIndex,
        lastVisibleIndex,
        firstFullyVisibleIndex,
        lastFullyVisibleIndex,
        ViewportInfo.State.SCROLLING);
  }

  public void viewPortChanged(
      int firstVisibleIndex,
      int lastVisibleIndex,
      int firstFullyVisibleIndex,
      int lastFullyVisibleIndex,
      @ViewportInfo.State int state) {
    final Section currentSection;
    synchronized (this) {
      currentSection = mCurrentSection;
    }
    if (currentSection != null) {
      viewPortChangedRecursive(
          currentSection,
          firstVisibleIndex,
          lastVisibleIndex,
          firstFullyVisibleIndex,
          lastFullyVisibleIndex,
          state);
    }
  }

  private void viewPortChangedRecursive(
      Section section,
      int firstVisibleIndex,
      int lastVisibleIndex,
      int firstFullyVisibleIndex,
      int lastFullyVisibleIndex,
      @ViewportInfo.State int state) {
    Range currentRange = mLastRanges.get(section.getGlobalKey());
    final int totalItemsCount = section.getCount();

    if (currentRange == null) {
      currentRange = acquireRange();
      mLastRanges.put(section.getGlobalKey(), currentRange);
    } else if (currentRange.firstVisibleIndex == firstVisibleIndex
        && currentRange.lastVisibleIndex == lastVisibleIndex
        && currentRange.firstFullyVisibleIndex == firstFullyVisibleIndex
        && currentRange.lastFullyVisibleIndex == lastFullyVisibleIndex
        && currentRange.totalItemsCount == totalItemsCount) {

      if (state != ViewportInfo.State.DATA_CHANGES) {
        return;
      }
    }

    currentRange.lastVisibleIndex = lastVisibleIndex;
    currentRange.firstVisibleIndex = firstVisibleIndex;
    currentRange.firstFullyVisibleIndex = firstFullyVisibleIndex;
    currentRange.lastFullyVisibleIndex = lastFullyVisibleIndex;
    currentRange.totalItemsCount = totalItemsCount;

    section.viewportChanged(
        section.getScopedContext(),
        firstVisibleIndex,
        lastVisibleIndex,
        totalItemsCount,
        firstFullyVisibleIndex,
        lastFullyVisibleIndex);

    if (section.isDiffSectionSpec()) {
      return;
    }

    int offset = 0;
    final List<Section> children = section.getChildren();
    for (int i = 0, size = children.size(); i < size; i++) {
      final Section child = children.get(i);

      int childFirstVisibleIndex = firstVisibleIndex - offset;
      int childLastVisibleIndex = lastVisibleIndex - offset;

      int childFullyFirstVisibleIndex = firstFullyVisibleIndex - offset;
      int childFullyLastVisibleIndex = lastFullyVisibleIndex - offset;

      if (childFirstVisibleIndex >= child.getCount() || childLastVisibleIndex < 0) {
        childFirstVisibleIndex = -1;
        childLastVisibleIndex = -1;
      } else {
        childFirstVisibleIndex = Math.max(childFirstVisibleIndex, 0);
        childLastVisibleIndex = Math.min(childLastVisibleIndex, child.getCount() - 1);
      }

      if (childFullyFirstVisibleIndex >= child.getCount() || childFullyLastVisibleIndex < 0) {
        childFullyFirstVisibleIndex = -1;
        childFullyLastVisibleIndex = -1;
      } else {
        childFullyFirstVisibleIndex = Math.max(childFullyFirstVisibleIndex, 0);
        childFullyLastVisibleIndex = Math.min(childFullyLastVisibleIndex, child.getCount() - 1);
      }

      offset += child.getCount();
      viewPortChangedRecursive(
          child,
          childFirstVisibleIndex,
          childLastVisibleIndex,
          childFullyFirstVisibleIndex,
          childFullyLastVisibleIndex,
          state);
    }
  }

  private synchronized @Nullable String getRootSectionKey() {
    if (mCurrentSection == null) {
      return null;
    }
    return mCurrentSection.getGlobalKey();
  }

  public void requestFocusOnRoot(int index) {
    final String sectionKey = getRootSectionKey();
    if (sectionKey == null) {
      return;
    }

    requestFocus(sectionKey, index);
  }

  public void requestFocusOnRoot(int index, int offset) {
    final String sectionKey = getRootSectionKey();
    if (sectionKey == null) {
      return;
    }

    requestFocusWithOffset(sectionKey, index, offset);
  }

  public void requestFocusOnRoot(Object id, int offset) {
    final String sectionKey = getRootSectionKey();
    if (sectionKey == null) {
      return;
    }

    requestFocusWithOffset(id, offset);
  }

  public interface SectionMatcher {
    boolean isMatch(Section section);
  }

  public int getIndexForMatcher(SectionMatcher sectionMatcher) {
    @Nullable final List<Section> sections;
    synchronized (this) {
      sections = mCurrentSection != null ? mCurrentSection.getChildren() : null;
    }
    if (sections == null) {
      return -1;
    }

    for (int i = 0; i < sections.size(); i++) {
      @Nullable final Section section = sections.get(i);
      if (section == null) {
        continue;
      }

      if (sectionMatcher.isMatch(section)) {
        return i;
      }
    }

    return -1;
  }

  void requestFocus(Section section, int index) {
    requestFocus(section.getGlobalKey(), index);
  }

  void requestFocusStart(String sectionKey) {
    requestFocus(sectionKey, 0);
  }

  void requestFocusEnd(final String sectionKey) {
    maybeThrowIfNotMainThread();

    final SectionLocationInfo locationInfo = findSectionForKey(sectionKey);
    mFocusDispatcher.requestFocus(locationInfo.mStartIndex + locationInfo.mSection.getCount() - 1);
  }

  private void requestFocus(final String sectionKey, final int index) {
    maybeThrowIfNotMainThread();

    final SectionLocationInfo sectionLocationInfo = findSectionForKey(sectionKey);
    if (isFocusValid(sectionLocationInfo, index)) {
      mFocusDispatcher.requestFocus(sectionLocationInfo.mStartIndex + index);
    }
  }

  void requestFocusWithOffset(Section section, int index, int offset) {
    requestFocusWithOffset(section.getGlobalKey(), index, offset);
  }

  void requestFocusWithOffset(String sectionKey, int offset) {
    requestFocusWithOffset(sectionKey, 0, offset);
  }

  void requestFocusWithOffset(final String sectionKey, final int index, final int offset) {
    maybeThrowIfNotMainThread();

    final SectionLocationInfo sectionLocationInfo = findSectionForKey(sectionKey);
    if (isFocusValid(sectionLocationInfo, index)) {
      mFocusDispatcher.requestFocusWithOffset(sectionLocationInfo.mStartIndex + index, offset);
    }
  }

  void requestFocusWithOffset(final Object id, final int offset) {
    maybeThrowIfNotMainThread();
    mFocusDispatcher.requestFocusWithOffset(id, offset);
  }

  public void requestSmoothFocusOnRoot(int index, int offset, SmoothScrollAlignmentType type) {
    @Nullable final String rootSectionKey = getRootSectionKey();
    if (rootSectionKey == null) {
      return;
    }

    requestSmoothFocus(rootSectionKey, index, offset, type);
  }

  public void requestSmoothFocusOnRoot(Object id, int offset, SmoothScrollAlignmentType type) {
    requestSmoothFocus(id, offset, type);
  }

  void requestSmoothFocus(
      final String globalKey,
      final int index,
      final int offset,
      final SmoothScrollAlignmentType type) {
    maybeThrowIfNotMainThread();

    final SectionLocationInfo sectionLocationInfo = findSectionForKey(globalKey);
    if (isFocusValid(sectionLocationInfo, index)) {
      mFocusDispatcher.requestSmoothFocus(sectionLocationInfo.mStartIndex + index, offset, type);
    }
  }

  void requestSmoothFocus(final Object id, final int offset, final SmoothScrollAlignmentType type) {
    maybeThrowIfNotMainThread();

    mFocusDispatcher.requestSmoothFocus(id, offset, type);
  }

  public boolean isSectionIndexValid(String globalKey, int index) {
    return index < findSectionForKey(globalKey).mSection.getCount() && index >= 0;
  }

  /**
   * Returns true if the check is valid, false if not
   *
   * @param sectionLocationInfo
   * @param index
   * @return
   */
  private boolean isFocusValid(SectionLocationInfo sectionLocationInfo, int index) {
    if (index >= sectionLocationInfo.mSection.getCount() || index < 0) {
      String errorMessage =
          "You are trying to request focus with offset on an index that is out of bounds: "
              + "requested "
              + index
              + " , total "
              + sectionLocationInfo.mSection.getCount();
      ComponentsReporter.emitMessage(
          ComponentsReporter.LogLevel.ERROR, OUT_OF_BOUNDS_REQUEST_FOCUS, errorMessage);
      return false;
    }
    return true;
  }

  private static Range acquireRange() {
    return new Range();
  }

  private static void releaseRange(Range range) {}

  /**
   * Call this when to release this SectionTree and make sure that all the Services in the tree get
   * destroyed.
   */
  public void release() {
    synchronized (this) {
      mReleased = true;
      mCurrentSection = null;
      mNextSection = null;
    }

    for (Range range : mLastRanges.values()) {
      releaseRange(range);
    }
    mLastRanges.clear();
    mBoundSection = null;
    clearUnusedTriggerHandlers();
  }

  public boolean isReleased() {
    return mReleased;
  }

  /**
   * This will be called by the framework when one of the {@link Section} in the tree requests to
   * update its own state. The generation of the ChangeSet will happen synchronously in the thread
   * calling this method.
   *
   * @param key The unique key of the {@link Section} in the tree.
   * @param stateUpdate An implementation of {@link StateContainer.StateUpdate} that knows how to
   *     transition to the new state.
   */
  synchronized void updateState(
      String key, StateContainer.StateUpdate stateUpdate, String attribution) {
    if (mAsyncStateUpdates) {
      updateStateAsync(key, stateUpdate, attribution);
    } else {
      mCalculateChangeSetOnMainThreadRunnable.cancel();
      addStateUpdateInternal(key, stateUpdate, false);
      final ChangesetDebugInfo changesetDebugInfo =
          mChangesetDebug == null
              ? null
              : new ChangesetDebugInfo(
                  ApplyNewChangeSet.UPDATE_STATE,
                  attribution,
                  key,
                  Thread.currentThread().getStackTrace());
      mCalculateChangeSetOnMainThreadRunnable.ensurePosted(
          ApplyNewChangeSet.UPDATE_STATE, attribution, changesetDebugInfo);

      LithoStats.incrementSectionStateUpdateSyncCount();
    }
  }

  /**
   * This will be called by the framework when one of the {@link Section} in the tree requests to
   * update its own state. The generation of the ChangeSet will happen asynchronously in this
   * SectionTree ChangesetThread.
   *
   * @param key The unique key of the {@link Section} in the tree.
   * @param stateUpdate An implementation of {@link StateContainer.StateUpdate} that knows how to
   *     transition to the new state.
   */
  synchronized void updateStateAsync(
      String key, StateContainer.StateUpdate stateUpdate, String attribution) {
    if (mForceSyncStateUpdates) {
      updateState(key, stateUpdate, attribution);
    } else {
      mCalculateChangeSetRunnable.cancel();
      addStateUpdateInternal(key, stateUpdate, false);
      final ChangesetDebugInfo changesetDebugInfo =
          mChangesetDebug == null
              ? null
              : new ChangesetDebugInfo(
                  UPDATE_STATE_ASYNC, attribution, key, Thread.currentThread().getStackTrace());
      mCalculateChangeSetRunnable.ensurePosted(UPDATE_STATE_ASYNC, attribution, changesetDebugInfo);

      LithoStats.incrementSectionStateUpdateAsyncCount();
    }
  }

  synchronized void updateStateLazy(String key, StateContainer.StateUpdate stateUpdate) {
    addStateUpdateInternal(key, stateUpdate, true);
  }

  @Nullable
  synchronized Object getCachedValue(Object cachedValueInputs) {
    if (mCachedValues == null) {
      mCachedValues = new HashMap<>();
    }

    return mCachedValues.get(cachedValueInputs);
  }

  synchronized void putCachedValue(Object cachedValueInputs, Object cachedValue) {
    if (mCachedValues == null) {
      mCachedValues = new HashMap<>();
    }

    mCachedValues.put(cachedValueInputs, cachedValue);
  }

  private static @Nullable Section copy(@Nullable Section section, boolean deep) {
    return section != null ? section.makeShallowCopy(deep) : null;
  }

  private synchronized void addStateUpdateInternal(
      String key, StateContainer.StateUpdate stateUpdate, boolean isLazyStateUpdate) {
    if (mReleased) {
      return;
    }

    if (mCurrentSection == null && mNextSection == null) {
      throw new IllegalStateException("State set with no attached Section");
    }

    mPendingStateUpdates.addStateUpdate(key, stateUpdate, isLazyStateUpdate);

    // If the state update is lazy, do not create a new tree root because the calculation of
    // a new tree will not happen yet.
    if (isLazyStateUpdate) {
      return;
    }

    if (mIsChangeSetCalculationInProgress) {
      logStateUpdateWhenChangeSetInProgress();
    }
    // Need to calculate a new tree since the state changed. The next tree root will be the same
    // of the current tree or a copy of the next pending root.
    if (mNextSection == null) {
      mNextSection = copy(mCurrentSection, false);
    } else {
      mNextSection = copy(mNextSection, false);
    }
  }

  /**
   * State updates can be triggered from inside the change set calculation which causes an infinite
   * loop because state updates calculates the change set again. To prevent this we keep a track of
   * how many times consequently state updates was invoked from within change set. If this crosses
   * the threshold a runtime exception is thrown.
   */
  @GuardedBy(value = "this")
  private void logStateUpdateWhenChangeSetInProgress() {
    if (++mStateUpdatesFromChangeSetCount == STATE_UPDATES_IN_LOOP_THRESHOLD) {
      ComponentsReporter.emitMessage(
          ComponentsReporter.LogLevel.FATAL,
          STATE_UPDATES_IN_LOOP_EXCEED_THRESHOLD,
          "Large number of state updates detected which indicates an infinite loop leading to"
              + " unresponsive apps");
    }
  }

  private void applyNewChangeSet(
      @ApplyNewChangeSet int source,
      @Nullable String attribution,
      @Nullable ChangesetDebugInfo changesetDebugInfo) {
    if (attribution == null) {
      attribution = mTag;
    }

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      if (attribution != null) {
        ComponentsSystrace.beginSection("sections:" + attribution);
      }
      final String name;
      synchronized (this) {
        name = mNextSection != null ? mNextSection.getSimpleName() : "<null>";
      }
      ComponentsSystrace.beginSection("sections.applyNewChangeSet:" + name);
    }

    final @Nullable Integer traceId =
        DebugEventDispatcher.generateTraceIdentifier(DebugEvents.CALCULATE_CHANGE_SET);
    if (traceId != null) {

      DebugEventDispatcher.beginTrace(
          traceId,
          DebugEvents.CALCULATE_CHANGE_SET,
          "-1",
          attributes -> {
            attributes.put(Id, hashCode());
            attributes.put(Source, SectionsLogEventUtils.applyNewChangeSetSourceToString(source));
            attributes.put(Async, source == SET_ROOT_ASYNC || source == UPDATE_STATE_ASYNC);
            return Unit.INSTANCE;
          });
    }

    if (SectionsDebug.ENABLED) {
      final String name;
      synchronized (this) {
        name = mNextSection != null ? mNextSection.getSimpleName() : "<null>";
      }
      Log.d(
          SectionsDebug.TAG,
          "=== NEW CHANGE SET ("
              + SectionsLogEventUtils.applyNewChangeSetSourceToString(source)
              + ", S: "
              + name
              + ", Tree: "
              + hashCode()
              + ") ====");
    }

    try {
      Section currentRoot;
      Section nextRoot;
      StateUpdatesHolder pendingStateUpdates;

      synchronized (this) {
        if (mReleased) {
          return;
        }

        currentRoot = copy(mCurrentSection, true);
        nextRoot = copy(mNextSection, false);
        pendingStateUpdates = mPendingStateUpdates.copy();
        mIsChangeSetCalculationInProgress = true;
      }

      if (nextRoot != null) {
        clearUnusedTriggerHandlers();
      }

      // Checking nextRoot is enough here since whenever we enqueue a new state update we also
      // re-assign nextRoot.
      while (nextRoot != null) {
        // The tree can be released (e.g. CollectionRecycler teardown on the main thread) after the
        // root snapshot is captured above but before this off-lock calculation runs. release()
        // nulls both root fields under the monitor, so bail out instead of computing a degenerate
        // change set against a torn-down tree. mReleased is volatile, so this lockless read is
        // safe.
        if (mReleased) {
          return;
        }
        if (isTracing) {
          ComponentsSystrace.beginSection("sections.calculateNewChangeSet");
        }
        final SectionContext contextForCalculation =
            SectionContext.forNewChangeSetCalculation(mContext);
        final ChangeSetState changeSetState =
            calculateNewChangeSet(
                contextForCalculation,
                currentRoot,
                nextRoot,
                pendingStateUpdates.mAllStateUpdates,
                mSectionsDebugLogger,
                mTag);
        if (isTracing) {
          ComponentsSystrace.endSection();
        }

        final boolean changeSetIsValid;
        Section oldRoot = null;
        Section newRoot = null;
        final List<Pair<GlobalKey, EventHandler<?>>> createdEventHandlers;
        synchronized (this) {
          boolean currentNotNull = currentRoot != null;
          boolean instanceCurrentNotNull = mCurrentSection != null;
          boolean currentIsSame =
              (currentNotNull
                      && instanceCurrentNotNull
                      && currentRoot.getId() == mCurrentSection.getId())
                  || (!currentNotNull && !instanceCurrentNotNull);
          boolean nextIsSame = (mNextSection != null && nextRoot.getId() == mNextSection.getId());

          changeSetIsValid =
              currentIsSame && nextIsSame && isStateUpdateCompleted(pendingStateUpdates);

          if (changeSetIsValid) {
            oldRoot = mCurrentSection;
            newRoot = nextRoot;

            mCurrentSection = newRoot;
            mNextSection = null;
            resetStateUpdatesCount();
            mPendingStateUpdates.removeCompletedStateUpdates(pendingStateUpdates);
            mPendingChangeSets.add(changeSetState.getChangeSet());

            if (oldRoot != null) {
              unbindOldComponent(oldRoot);
            }

            bindTriggerHandler(newRoot);
          }

          final ChangeSetCalculationState calculationState =
              contextForCalculation.getChangeSetCalculationState();
          createdEventHandlers = calculationState.getEventHandlers();
          calculationState.clear();
        }

        if (changeSetIsValid) {
          mEventHandlersController.canonicalizeEventDispatchInfos(createdEventHandlers);
          if (newRoot != null) {
            bindNewComponent(newRoot);
          }
          mEventHandlersController.clearUnusedEventDispatchInfos();

          final List<Section> removedComponents = changeSetState.getRemovedComponents();
          for (int i = 0, size = removedComponents.size(); i < size; i++) {
            final Section removedComponent = removedComponents.get(i);
            releaseRange(mLastRanges.remove(removedComponent.getGlobalKey()));
          }

          postNewChangeSets(changesetDebugInfo);
        }

        synchronized (this) {
          pendingStateUpdates.release();

          if (mReleased) {
            return;
          }

          currentRoot = copy(mCurrentSection, true);
          nextRoot = copy(mNextSection, false);
          if (nextRoot != null) {
            pendingStateUpdates = mPendingStateUpdates.copy();
            mIsChangeSetCalculationInProgress = true;
          } else {
            resetStateUpdatesCount();
          }
        }
      }

      final LithoStartupLogger startupLogger =
          mContext.getTreePropContainer() == null
              ? null
              : mContext.getTreePropContainer().get(LithoStartupLogger.class);
      if (LithoStartupLogger.isEnabled(startupLogger)) {
        startupLogger.markPoint(LithoStartupLogger.CHANGESET_CALCULATION, LithoStartupLogger.END);
      }
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
        if (attribution != null) {
          ComponentsSystrace.endSection();
        }
      }
      if (traceId != null) {
        DebugEventDispatcher.endTrace(traceId);
      }
      LithoStats.incrementSectionCalculateNewChangesetCount();
      if (ThreadUtils.isMainThread()) {
        LithoStats.incrementSectionCalculateNewChangesetOnUICount();
      }
    }
  }

  /** Resetting the count of state updates which happened from within calculation of change set. */
  @GuardedBy("this")
  private void resetStateUpdatesCount() {
    mIsChangeSetCalculationInProgress = false;
    mStateUpdatesFromChangeSetCount = 0;
  }

  /**
   * The state update is completed if there are no new non-lazy state updates enqueued.
   *
   * @return true if there are no more state updates to be processed immediately.
   */
  private synchronized boolean isStateUpdateCompleted(StateUpdatesHolder localPendingStateUpdates) {
    return localPendingStateUpdates.mNonLazyStateUpdates.equals(
        mPendingStateUpdates.mNonLazyStateUpdates);
  }

  void dispatchLoadingEvent(LoadingEvent event) {
    final LoadingEvent.LoadingState loadingState = event.loadingState;
    if (mLoadEventsHandler != null) {
      final boolean isEmpty = event.isEmpty;

      switch (loadingState) {
        case FAILED:
          mLoadEventsHandler.onLoadFailed(isEmpty);
          break;
        case INITIAL_LOAD:
          mLoadEventsHandler.onInitialLoad();
          break;
        case LOADING:
          mLoadEventsHandler.onLoadStarted(isEmpty);
          break;
        case SUCCEEDED:
          mLoadEventsHandler.onLoadSucceeded(isEmpty);
          break;
      }
    }

    postLoadingStateToFocusDispatch(loadingState);
  }

  private void postLoadingStateToFocusDispatch(final LoadingEvent.LoadingState loadingState) {
    if (isMainThread()) {
      setLoadingStateToFocusDispatch(loadingState);
    } else {
      String tag = EMPTY_STRING;
      if (mMainThreadHandler.isTracing()) {
        tag = "SectionTree.postLoadingStateToFocusDispatch - " + loadingState.name() + " - " + mTag;
      }
      mMainThreadHandler.post(
          new ThreadTracingRunnable() {
            @Override
            public void tracedRun() {
              setLoadingStateToFocusDispatch(loadingState);
            }
          },
          tag);
    }
  }

  private void setLoadingStateToFocusDispatch(final LoadingEvent.LoadingState loadingState) {
    if (loadingState == LoadingEvent.LoadingState.INITIAL_LOAD
        || loadingState == LoadingEvent.LoadingState.LOADING) {
      mFocusDispatcher.waitForDataBound(true);
    }

    if (loadingState == LoadingEvent.LoadingState.FAILED) {
      mFocusDispatcher.waitForDataBound(false);
    }

    mFocusDispatcher.setLoadingState(loadingState);
    mFocusDispatcher.maybeDispatchFocusRequests();
  }

  private void bindNewComponent(Section section) {
    section.bindService(section.getScopedContext());
    mEventHandlersController.updateEventDispatchInfoForGlobalKey(
        section.getScopedContext(), section, new StringGlobalKey(section.getGlobalKey()));

    if (!section.isDiffSectionSpec()) {
      final List<Section> children = section.getChildren();
      for (int i = 0, size = children.size(); i < size; i++) {
        bindNewComponent(children.get(i));
      }
    }
  }

  private void unbindOldComponent(Section section) {
    section.unbindService(section.getScopedContext());

    if (!section.isDiffSectionSpec()) {
      final List<Section> children = section.getChildren();
      for (int i = 0, size = children.size(); i < size; i++) {
        unbindOldComponent(children.get(i));
      }
    }
  }

  private void postNewChangeSets(@Nullable final ChangesetDebugInfo changesetDebugInfo) {
    if (mUseBackgroundChangeSets) {
      applyChangeSetsToTargetBackgroundAllowed(changesetDebugInfo);
      return;
    }

    if (isMainThread()) {
      try {
        applyChangeSetsToTargetUIThreadOnly(changesetDebugInfo);
      } catch (IndexOutOfBoundsException e) {
        throw maybeWrapWithDuplicatesMetadata(this, mCurrentSection, e);
      }
    } else {
      String tag = EMPTY_STRING;
      if (mMainThreadHandler.isTracing()) {
        tag = "SectionTree.postNewChangeSets - " + mTag;
      }
      final Runnable applyChangeSetsRunnable =
          new ThreadTracingRunnable() {
            @Override
            public void tracedRun() {
              final SectionTree tree = SectionTree.this;
              try {
                tree.applyChangeSetsToTargetUIThreadOnly(changesetDebugInfo);
              } catch (IndexOutOfBoundsException e) {
                throw maybeWrapWithDuplicatesMetadata(SectionTree.this, mCurrentSection, e);
              }
            }
          };

      if (mPostToFrontOfQueueForFirstChangeset.compareAndSet(true, false)) {
        mMainThreadHandler.postAtFront(applyChangeSetsRunnable, tag);
      } else {
        mMainThreadHandler.post(applyChangeSetsRunnable, tag);
      }
    }
  }

  private static RuntimeException maybeWrapWithDuplicatesMetadata(
      SectionTree tree, @Nullable Section root, IndexOutOfBoundsException e) {
    RuntimeException result = tryWrapWithDuplicatesMetadata(null, root, e);
    if (result != e) {
      return result;
    }
    // return the fallback exception if no duplicates were found
    return new RuntimeException(
        INDEX_OUT_OF_BOUNDS_DEBUG_MESSAGE + getDebugInfo(tree) + e.getMessage(), e);
  }

  /**
   * We are going to traverse the whole SectionTree to find which Section contains the duplicate
   * items and wrap useful meta info to the exception.
   */
  private static RuntimeException tryWrapWithDuplicatesMetadata(
      @Nullable Section parentSection, @Nullable Section section, IndexOutOfBoundsException e) {
    if (section != null) {
      String info = section.verifyChangeSet(section.getScopedContext());
      if (info != null) {
        String sectionName =
            parentSection != null ? parentSection.getSimpleName() : section.getSimpleName();
        String sectionDebugInfo =
            INDEX_OUT_OF_BOUNDS_DEBUG_MESSAGE + info + " in the [" + sectionName + "].";
        RuntimeException exception = new RuntimeException(sectionDebugInfo, e);
        // erase the trace of wrapper exception
        exception.setStackTrace(new StackTraceElement[0]);
        return exception;
      }
      List<Section> children = section.getChildren();
      if (children != null) {
        for (Section child : children) {
          RuntimeException result = tryWrapWithDuplicatesMetadata(section, child, e);
          if (result != e) {
            return result;
          }
        }
      }
    }
    return e;
  }

  @ThreadConfined(ThreadConfined.ANY)
  private void applyChangeSetsToTargetBackgroundAllowed(
      @Nullable ChangesetDebugInfo changesetDebugInfo) {
    if (!mUseBackgroundChangeSets) {
      throw new IllegalStateException(
          "Must use UIThread-only variant when background change sets are not enabled.");
    }

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("sections.applyChangeSets");
    }

    try {
      // This whole operation (both determining the change sets to apply and applying them) must be
      // synchronized so that we apply change sets from a single thread at a time and in the correct
      // order.
      synchronized (this) {
        if (mReleased) {
          return;
        }

        applyChangeSetsToTargetUnchecked(mCurrentSection, mPendingChangeSets, changesetDebugInfo);
        mPendingChangeSets.clear();
      }

      if (isMainThread()) {
        maybeDispatchFocusRequests();
      } else {
        String tag = EMPTY_STRING;
        if (mMainThreadHandler.isTracing()) {
          tag = "SectionTree.applyChangeSetsToTargetBackgroundAllowed - " + mTag;
        }
        mMainThreadHandler.post(
            new ThreadTracingRunnable() {
              @Override
              public void tracedRun() {
                maybeDispatchFocusRequests();
              }
            },
            tag);
      }
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  @UiThread
  private void applyChangeSetsToTargetUIThreadOnly(
      @Nullable ChangesetDebugInfo changesetDebugInfo) {
    assertMainThread();
    if (mUseBackgroundChangeSets) {
      throw new IllegalStateException(
          "Cannot use UIThread-only variant when background change sets are enabled.");
    }

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("sections.applyChangeSets");
    }

    try {
      final List<ChangeSet> changeSets;
      final Section currentSection;
      synchronized (this) {
        if (mReleased) {
          return;
        }

        changeSets = new ArrayList<>(mPendingChangeSets);
        mPendingChangeSets.clear();
        currentSection = mCurrentSection;
      }

      applyChangeSetsToTargetUnchecked(currentSection, changeSets, changesetDebugInfo);
      maybeDispatchFocusRequests();
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  private void maybeDispatchFocusRequests() {
    if (mFocusDispatcher.isLoadingCompleted()) {
      mFocusDispatcher.waitForDataBound(false);
      mFocusDispatcher.maybeDispatchFocusRequests();
    }
  }

  private void maybeThrowIfNotMainThread() {
    if (!ThreadUtils.isMainThread()) {
      throw new RuntimeException(
          "Focus request not called from Main Thread (Current Thread:"
              + Thread.currentThread().getName()
              + ")");
    }
  }

  private void applyChangeSetsToTargetUnchecked(
      final Section currentSection,
      List<ChangeSet> changeSets,
      @Nullable final ChangesetDebugInfo changesetDebugInfo) {
    final boolean isTracing = ComponentsSystrace.isTracing();

    if (isTracing) {
      ComponentsSystrace.beginSection("sections.applyChangeSetToTarget");
    }
    boolean appliedChanges = false;
    final List<Change> changes = new ArrayList<>();

    try {

      final @Nullable Integer traceId =
          DebugEventDispatcher.generateTraceIdentifier(APPLY_CHANGE_SET);
      if (traceId != null) {
        DebugEventDispatcher.beginTrace(
            traceId, APPLY_CHANGE_SET, String.valueOf(RenderState.NO_ID));
      }

      try {

        for (int i = 0, size = changeSets.size(); i < size; i++) {
          final ChangeSet changeSet = changeSets.get(i);

          if (changeSet.getChangeCount() > 0) {
            for (int j = 0, changeSize = changeSet.getChangeCount(); j < changeSize; j++) {
              final Change change = changeSet.getChangeAt(j);
              switch (change.getType()) {
                case Change.INSERT:
                  appliedChanges = true;
                  mTarget.insert(change.getIndex(), change.getRenderInfo());
                  break;
                case Change.INSERT_RANGE:
                  appliedChanges = true;
                  mTarget.insertRange(
                      change.getIndex(), change.getCount(), change.getRenderInfos());
                  break;
                case Change.UPDATE:
                  appliedChanges = true;
                  mTarget.update(change.getIndex(), change.getRenderInfo());
                  break;
                case Change.UPDATE_RANGE:
                  appliedChanges = true;
                  mTarget.updateRange(
                      change.getIndex(), change.getCount(), change.getRenderInfos());
                  break;
                case Change.DELETE:
                  appliedChanges = true;
                  mTarget.delete(change.getIndex());
                  break;
                case Change.DELETE_RANGE:
                  appliedChanges = true;
                  mTarget.deleteRange(change.getIndex(), change.getCount());
                  break;
                case Change.MOVE:
                  appliedChanges = true;
                  mTarget.move(change.getIndex(), change.getToIndex());
              }
            }
            mTarget.dispatchLastEvent();
          }
          changes.addAll(changeSet.getChanges());
        }

      } finally {
        if (traceId != null) {
          final HashMap<String, Object> attributes = new HashMap<>();
          attributes.put("section", currentSection.getSimpleName());
          attributes.put("size", changes.size());
          // TODO(T198956736): Add break down of change set to the trace
          DebugEventDispatcher.endTrace(traceId, attributes);
        }
      }

      final boolean isDataChanged = appliedChanges;
      final ChangesInfo changesInfo = new ChangesInfo(changes);

      if (changesetDebugInfo != null) {
        final Section previousSection;
        synchronized (this) {
          previousSection = mPreviousSection;
          mPreviousSection = mCurrentSection;
        }

        changesetDebugInfo.setOldSection(previousSection);
      }

      mTarget.notifyChangeSetComplete(
          isDataChanged,
          new ChangeSetCompleteCallback() {
            @Override
            public void onDataBound() {
              if (mChangesetDebug != null && changesetDebugInfo != null) {
                mChangesetDebug.onChangesetApplied(
                    mCurrentSection,
                    changesInfo,
                    mContext.getLogTag() == null
                        ? "SectionTree" + SectionTree.this.mTag
                        : mContext.getLogTag(),
                    changesetDebugInfo);
              }

              if (!isDataChanged) {
                return;
              }

              if (isTracing) {
                ComponentsSystrace.beginSection("sections.dataBound");
              }
              try {
                dataBound(currentSection);
              } finally {
                if (isTracing) {
                  ComponentsSystrace.endSection();
                }
              }
            }

            @Override
            public void onDataRendered(boolean isMounted, long uptimeMillis) {
              if (mReleased) {
                return;
              }
              dataRendered(currentSection, isDataChanged, isMounted, uptimeMillis, changesInfo);
            }
          });
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  private static ChangeSetState calculateNewChangeSet(
      SectionContext context,
      @Nullable Section currentRoot,
      Section nextRoot,
      Map<String, List<StateContainer.StateUpdate>> pendingStateUpdates,
      SectionsDebugLogger sectionsDebugLogger,
      String sectionTreeTag) {
    nextRoot.setGlobalKey(nextRoot.getKey());

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("sections.createTree");
    }
    try {
      createNewTreeAndApplyStateUpdates(
          context, currentRoot, nextRoot, pendingStateUpdates, sectionsDebugLogger, sectionTreeTag);
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }

    if (isTracing) {
      ComponentsSystrace.beginSection("sections.generateChangeSet");
    }
    try {
      return ChangeSetState.generateChangeSet(
          context, currentRoot, nextRoot, sectionsDebugLogger, sectionTreeTag, "", "", false);
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  /**
   * Creates the new tree, transfers state/services from the current tree and applies all the state
   * updates that have been enqueued since the last tree calculation.
   */
  private static void createNewTreeAndApplyStateUpdates(
      SectionContext context,
      @Nullable Section currentRoot,
      Section nextRoot,
      Map<String, List<StateContainer.StateUpdate>> pendingStateUpdates,
      SectionsDebugLogger sectionsDebugLogger,
      String sectionTreeTag) {
    if (nextRoot == null) {
      throw new IllegalStateException("Can't generate a subtree with a null root");
    }

    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("sections.createChildren:" + nextRoot.getSimpleName());
    }

    final @Nullable Integer traceId =
        DebugEventDispatcher.generateTraceIdentifier(DebugEvents.CREATE_CHILDREN);

    if (traceId != null) {
      DebugEventDispatcher.beginTrace(
          traceId,
          DebugEvents.CREATE_CHILDREN,
          "-1",
          attributes -> {
            int id = context.getSectionTree() != null ? context.getSectionTree().hashCode() : -1;
            attributes.put(Id, id);
            attributes.put(Name, nextRoot.getSimpleName());
            return Unit.INSTANCE;
          });
    }

    try {
      nextRoot.setScopedContext(SectionContext.withScope(context, nextRoot));
      if (currentRoot != null) {
        nextRoot.setCount(currentRoot.getCount());
      }

      final boolean isNextRootDiffSection = nextRoot.isDiffSectionSpec();
      if (!isNextRootDiffSection) {
        nextRoot.populateTreePropContainer(context.getTreePropContainer());
      }

      final boolean shouldTransferState =
          currentRoot != null && currentRoot.getClass().equals(nextRoot.getClass());

      if (currentRoot == null || !shouldTransferState) {
        nextRoot.createInitialState(nextRoot.getScopedContext());
        nextRoot.createService(nextRoot.getScopedContext());
      } else {
        if (currentRoot.getService(currentRoot) == null) {
          nextRoot.createService(nextRoot.getScopedContext());

          if (nextRoot.getService(nextRoot) != null) {
            final String errorMessage =
                "We were about to transfer a null service from "
                    + currentRoot
                    + " to "
                    + nextRoot
                    + " while the later created a non-null service";
            ComponentsReporter.emitMessage(
                ComponentsReporter.LogLevel.ERROR, NULL_TO_NON_NULL_SERVICE_TRANSFER, errorMessage);
          }
        } else {
          nextRoot.transferService(nextRoot.getScopedContext(), currentRoot, nextRoot);
        }
        nextRoot.transferState(currentRoot.getStateContainer(), nextRoot.getStateContainer());
      }

      final List<StateContainer.StateUpdate> stateUpdates =
          pendingStateUpdates.get(nextRoot.getGlobalKey());
      if (stateUpdates != null) {
        final StateContainer stateContainer = nextRoot.getStateContainer();
        for (int i = 0, size = stateUpdates.size(); i < size; i++) {
          stateContainer.applyStateUpdate(stateUpdates.get(i));
        }

        LithoStats.incrementSectionAppliedStateUpdateCountBy(stateUpdates.size());

        if (nextRoot.shouldComponentUpdate(currentRoot, nextRoot)) {
          nextRoot.invalidate();
        }
      }

      if (!isNextRootDiffSection) {
        final Map<String, Pair<Section, Integer>> currentComponentChildren =
            currentRoot == null || currentRoot.isDiffSectionSpec()
                ? null
                : Section.acquireChildrenMap(currentRoot);

        final TreePropContainer parentTreePropContainer = context.getTreePropContainer();
        context.setTreePropContainer(
            nextRoot.getTreePropContainerForChildren(context, parentTreePropContainer));

        nextRoot.setChildren(nextRoot.createChildren(nextRoot.getScopedContext()));

        final List<Section> nextRootChildren = nextRoot.getChildren();

        for (int i = 0, size = nextRootChildren.size(); i < size; i++) {
          final Section child = nextRootChildren.get(i);
          child.setParent(nextRoot);
          final String childKey = child.getKey();
          if (TextUtils.isEmpty(childKey)) {
            final String errorMessage =
                "Your Section "
                    + child.getSimpleName()
                    + " has an empty key. Please specify a key.";
            throw new IllegalStateException(errorMessage);
          }

          final String globalKey = nextRoot.getGlobalKey() + childKey;
          child.generateKeyAndSet(nextRoot.getScopedContext(), globalKey);

          final Pair<Section, Integer> valueAndIndex =
              currentComponentChildren == null
                  ? null
                  : currentComponentChildren.get(child.getGlobalKey());
          final Section currentChild = valueAndIndex != null ? valueAndIndex.first : null;

          createNewTreeAndApplyStateUpdates(
              context,
              currentChild,
              child,
              pendingStateUpdates,
              sectionsDebugLogger,
              sectionTreeTag);
        }

        final TreePropContainer contextTreeProps = context.getTreePropContainer();
        if (contextTreeProps != parentTreePropContainer) {
          context.setTreePropContainer(parentTreePropContainer);
        }
      }
    } finally {
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
      if (traceId != null) {
        DebugEventDispatcher.endTrace(traceId);
      }
    }
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  public static synchronized Looper getDefaultChangeSetThreadLooper() {
    if (sDefaultChangeSetThreadLooper == null) {
      HandlerThread defaultThread =
          new HandlerThread(
              DEFAULT_CHANGESET_THREAD_NAME,
              ComponentsConfiguration.DEFAULT_CHANGE_SET_THREAD_PRIORITY);
      defaultThread.start();
      sDefaultChangeSetThreadLooper = defaultThread.getLooper();
    }

    return sDefaultChangeSetThreadLooper;
  }

  private static List<StateContainer.StateUpdate> acquireUpdatesList() {
    return new ArrayList<>();
  }

  private static void releaseUpdatesList(List<StateContainer.StateUpdate> stateUpdates) {}

  private static String getDebugInfo(SectionTree tree) {
    synchronized (tree) {
      if (tree.isReleased()) {
        return "[Released Tree]";
      }

      final StringBuilder sb = new StringBuilder();
      sb.append("tag: ");
      sb.append(tree.mTag);

      sb.append(", currentSection.size: ");
      sb.append(tree.mCurrentSection != null ? tree.mCurrentSection.getCount() : null);

      sb.append(", currentSection.name: ");
      sb.append(tree.mCurrentSection != null ? tree.mCurrentSection.getSimpleName() : null);

      sb.append(", nextSection.size: ");
      sb.append(tree.mNextSection != null ? tree.mNextSection.getCount() : null);

      sb.append(", nextSection.name: ");
      sb.append(tree.mNextSection != null ? tree.mNextSection.getSimpleName() : null);

      sb.append(", pendingChangeSets.size: ");
      sb.append(tree.mPendingChangeSets.size());

      sb.append(", pendingStateUpdates.size: ");
      sb.append(tree.mPendingStateUpdates.mAllStateUpdates.size());

      sb.append(", pendingNonLazyStateUpdates.size: ");
      sb.append(tree.mPendingStateUpdates.mNonLazyStateUpdates.size());
      sb.append("\n");
      return sb.toString();
    }
  }

  /** A builder class that can be used to create a {@link SectionTree}. */
  public static final class Builder {

    private final SectionContext mContext;
    private final Target mTarget;
    private boolean mAsyncStateUpdates;
    private boolean mAsyncPropUpdates;
    private String mTag;
    private @Nullable RunnableHandler mChangeSetThreadHandler;
    private boolean mForceSyncStateUpdates;
    private boolean mPostToFrontOfQueueForFirstChangeset;

    private Builder(SectionContext componentContext, Target target) {
      mContext = componentContext;
      mTarget = target;
    }

    /**
     * An optional Handler where {@link ChangeSet} calculation should happen. If not provided the
     * framework will use its default background thread.
     */
    public Builder changeSetThreadHandler(@Nullable RunnableHandler changeSetThreadHandler) {
      mChangeSetThreadHandler = changeSetThreadHandler;
      return this;
    }

    /** If enabled, all state updates will be performed on a background thread. */
    public Builder asyncStateUpdates(boolean asyncStateUpdates) {
      mAsyncStateUpdates = asyncStateUpdates;
      return this;
    }

    /**
     * If enabled, all state updates will be performed on the main thread. This may be necessary to
     * use if your data model is not thread safe (e.g. model objects can be mutated from another
     * thread), meaning changesets on the datamodel list cannot be computed in the background.
     *
     * <p>NB: This may come at significant performance cost.
     */
    public Builder forceSyncStateUpdates(boolean forceSyncStateUpdates) {
      mForceSyncStateUpdates = forceSyncStateUpdates;
      return this;
    }

    /**
     * If enabled, any prop updates done with setRoot will be on a background thread. This excludes
     * the very first setRoot where there isn't an existing section. That one will still be on the
     * main thread.
     */
    public Builder asyncPropUpdates(boolean asyncPropUpdates) {
      mAsyncPropUpdates = asyncPropUpdates;
      return this;
    }

    /**
     * If enabled, a tag will define the section tree being built
     *
     * @param tag tag that labels the section tree
     * @return
     */
    public Builder tag(String tag) {
      mTag = (tag == null) ? "" : tag;
      return this;
    }

    public Builder postToFrontOfQueueForFirstChangeset(
        boolean postToFrontOfQueueForFirstChangeset) {
      mPostToFrontOfQueueForFirstChangeset = postToFrontOfQueueForFirstChangeset;
      return this;
    }

    /**
     * @return the {@link SectionTree}.
     */
    public SectionTree build() {
      return new SectionTree(this);
    }
  }

  /**
   * A class to track state updates. It keeps track of both all state updates, and all non-lazy
   * state updates. We're interested in non-lazy state updates in particular since they are the ones
   * we need in order to determine whether we need to execute another state update or not.
   */
  static class StateUpdatesHolder {
    private Map<String, List<StateContainer.StateUpdate>> mAllStateUpdates;
    private Map<String, List<StateContainer.StateUpdate>> mNonLazyStateUpdates;

    StateUpdatesHolder() {
      mAllStateUpdates = new HashMap<>();
      mNonLazyStateUpdates = new HashMap<>();
    }

    private void addStateUpdate(
        String key, StateContainer.StateUpdate stateUpdate, boolean isLazyStateUpdate) {
      addStateUpdateForKey(key, stateUpdate, mAllStateUpdates);

      if (!isLazyStateUpdate) {
        addStateUpdateForKey(key, stateUpdate, mNonLazyStateUpdates);
      }
    }

    private static void addStateUpdateForKey(
        String key,
        StateContainer.StateUpdate stateUpdate,
        Map<String, List<StateContainer.StateUpdate>> map) {
      List<StateContainer.StateUpdate> currentStateUpdatesForKey = map.get(key);

      if (currentStateUpdatesForKey == null) {
        currentStateUpdatesForKey = acquireUpdatesList();
        map.put(key, currentStateUpdatesForKey);
      }

      currentStateUpdatesForKey.add(stateUpdate);
    }

    private StateUpdatesHolder copy() {
      StateUpdatesHolder clonedPendingStateUpdates = new StateUpdatesHolder();

      if (mAllStateUpdates.isEmpty()) {
        return clonedPendingStateUpdates;
      }

      final Set<String> keys = mAllStateUpdates.keySet();
      for (String key : keys) {
        clonedPendingStateUpdates.mAllStateUpdates.put(
            key, new ArrayList<>(mAllStateUpdates.get(key)));
      }

      final Set<String> keysNonLazy = mNonLazyStateUpdates.keySet();
      for (String key : keysNonLazy) {
        clonedPendingStateUpdates.mNonLazyStateUpdates.put(
            key, new ArrayList<>(mNonLazyStateUpdates.get(key)));
      }

      return clonedPendingStateUpdates;
    }

    private void removeCompletedStateUpdates(StateUpdatesHolder completedStateUpdates) {
      if (completedStateUpdates.mAllStateUpdates.isEmpty()) {
        return;
      }

      final Set<String> keys = completedStateUpdates.mAllStateUpdates.keySet();
      for (String key : keys) {
        if (!mAllStateUpdates.containsKey(key)) {
          // instanceMap has been modified and since the localMap is created, so it's no longer
          // valid. We will exit the function early.
          return;
        }

        removeCompletedStateUpdatesFromMap(
            mAllStateUpdates, completedStateUpdates.mAllStateUpdates, key);
        removeCompletedStateUpdatesFromMap(
            mNonLazyStateUpdates, completedStateUpdates.mNonLazyStateUpdates, key);
      }
    }

    private static void removeCompletedStateUpdatesFromMap(
        Map<String, List<StateContainer.StateUpdate>> currentStateUpdates,
        Map<String, List<StateContainer.StateUpdate>> completedStateUpdates,
        String key) {
      List<StateContainer.StateUpdate> completed = completedStateUpdates.get(key);
      List<StateContainer.StateUpdate> current = currentStateUpdates.remove(key);
      if (completed != null && current != null) {
        current.removeAll(completed);
      }
      if (CollectionsUtils.isNotNullOrEmpty(current)) {
        currentStateUpdates.put(key, current);
      }
    }

    void release() {
      mAllStateUpdates.clear();
      mNonLazyStateUpdates.clear();
    }
  }

  private static class SectionLocationInfo {

    private final Section mSection;
    private final int mStartIndex;

    public SectionLocationInfo(Section section, int startIndex) {
      mSection = section;
      mStartIndex = startIndex;
    }
  }
}
