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

package com.facebook.litho.sections.common;

import static com.facebook.litho.widget.RenderInfoDebugInfoRegistry.SONAR_SECTIONS_DEBUG_INFO_TAG;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.DiffUtil;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentsReporter;
import com.facebook.litho.ComponentsSystrace;
import com.facebook.litho.Diff;
import com.facebook.litho.EventHandler;
import com.facebook.litho.HasEventDispatcher;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.annotations.Prop;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.config.LithoDebugConfigurations;
import com.facebook.litho.sections.ChangeSet;
import com.facebook.litho.sections.Section;
import com.facebook.litho.sections.SectionContext;
import com.facebook.litho.sections.annotations.DiffSectionSpec;
import com.facebook.litho.sections.annotations.OnDiff;
import com.facebook.litho.sections.annotations.OnVerifyChangeSet;
import com.facebook.litho.sections.logger.SectionsDebugEventLogger;
import com.facebook.litho.widget.ComponentRenderInfo;
import com.facebook.litho.widget.RecyclerBinderUpdateCallback;
import com.facebook.litho.widget.RecyclerBinderUpdateCallback.ComponentContainer;
import com.facebook.litho.widget.RecyclerBinderUpdateCallback.Operation;
import com.facebook.litho.widget.RenderInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link DiffSectionSpec} that creates a changeSet diffing a generic {@link List<T>} of data.
 * This {@link Section} emits the following events:
 *
 * <p>{@link RenderEvent} whenever it needs a {@link Component} to render a model T from the {@code
 * List<? extends T> data}. Providing a handler for this {@link OnEvent} is mandatory.
 *
 * <p>{@link OnCheckIsSameItemEvent} whenever during a diffing it wants to check whether two items
 * represent the same piece of data.
 *
 * <p>{@link OnCheckIsSameContentEvent} whenever during a diffing it wants to check whether two
 * items that represent the same piece of data have exactly the same content.
 *
 * <p>Diffing happens when the new {@code List<? extends T> data} is provided. Changes in {@link
 * com.facebook.litho.annotations.State} alone will not trigger diffing.
 *
 * <ul>
 *   <li>If {@link OnCheckIsSameItemEvent} returns false {@link RenderEvent} is triggered. Otherwise
 *       {@link OnCheckIsSameContentEvent} is called.
 *   <li>If {@link OnCheckIsSameContentEvent} returns false {@link RenderEvent} is triggered.
 * </ul>
 *
 * If {@link OnCheckIsSameItemEvent} is not implemented, new {@code List<? extends T> data} is
 * considered to be completely different and relayout will happen on every data update.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * '@'GroupSectionSpec
 * public class MyGroupSectionSpec {
 *
 *   '@'OnCreateChildren
 *   static Children onCreateChildren(
 *     SectionContext c,
 *     '@'Prop List<? extends Model> modelList) {
 *
 *     return Children.create()
 *         .child(
 *             DataDiffSection.<Model>create(c)
 *                 .data(modelList)
 *                 .renderEventHandler(MyGroupSection.onRender(c))
 *                 .onCheckIsSameItemEventHandler(MyGroupSection.onCheckIsSameItem(c))
 *                 .onCheckIsSameContentEventHandler(...))
 *        .build();
 *   }
 *
 *   '@'OnEvent(OnCheckIsSameItemEvent.class)
 *   static boolean onCheckIsSameItem(SectionContext c, @FromEvent Model previousItem, @FromEvent Model nextItem) {
 *     return previousItem.getId() == nextItem.getId();
 *   }
 *
 *   '@'OnEvent(RenderEvent.class)
 *   static RenderInfo onRender(SectionContext c, @FromEvent Model model) {
 *     return ComponentRenderInfo.create()
 *         .component(MyComponent.create(c).model(model).build())
 *         .build();
 *   }
 * }
 * </pre>
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
@DiffSectionSpec(
    events = {OnCheckIsSameContentEvent.class, OnCheckIsSameItemEvent.class, RenderEvent.class})
public class DataDiffSectionSpec<T> {

  public static final String DUPLICATES_EXIST_MSG =
      "Detected duplicates in data passed to DataDiffSection. Read more here:"
          + " https://fblitho.com/docs/sections/best-practices/#avoiding-indexoutofboundsexception";

  public static final String RENDER_INFO_RETURNS_NULL_MSG =
      "RenderInfo has returned null. Returning ComponentRenderInfo.createEmpty() as default.";

  @OnDiff
  public static <T> void onCreateChangeSet(
      SectionContext c,
      ChangeSet changeSet,
      @Prop Diff<List<? extends T>> data,
      @Prop(optional = true) @Nullable Diff<Boolean> detectMoves,
      @Prop(optional = true) @Nullable Diff<Boolean> alwaysDetectDuplicates) {

    final List<? extends T> previousData = data.getPrevious();
    final List<? extends T> nextData = data.getNext();
    final ComponentRenderer<T> componentRenderer =
        // NULLSAFE_FIXME[Parameter Not Nullable]
        new ComponentRenderer<T>(DataDiffSection.<T>getRenderEventHandler(c), c);
    final DiffSectionOperationExecutor operationExecutor =
        new DiffSectionOperationExecutor(changeSet);
    final RecyclerBinderUpdateCallback<T> updatesCallback;
    final boolean isTracing = ComponentsSystrace.isTracing();

    final Callback<T> callback = new Callback<>(c, data.getPrevious(), data.getNext());

    if (nextData != null && isDetectDuplicatesEnabled(alwaysDetectDuplicates)) {
      detectDuplicates(nextData, callback, changeSet.getSectionName());
    }
    if (isTracing) {
      ComponentsSystrace.beginSection("sections.calculateDiff");
    }
    final DiffUtil.DiffResult result =
        DiffUtil.calculateDiff(callback, isDetectMovesEnabled(detectMoves));
    if (isTracing) {
      ComponentsSystrace.endSection();
    }

    updatesCallback =
        new RecyclerBinderUpdateCallback<>(
            previousData, nextData, componentRenderer, operationExecutor);
    result.dispatchUpdatesTo(updatesCallback);

    updatesCallback.applyChangeset(c);
  }

  @OnVerifyChangeSet
  @Nullable
  public static <T> String verifyChangeSet(SectionContext context, @Prop List<? extends T> data) {
    final List<? extends T> nextData = data;
    if (nextData != null) {
      final Callback<T> callback = new Callback<>(context, null, nextData);
      final String section =
          context.getSectionScope() != null ? context.getSectionScope().getSimpleName() : "null";
      return detectDuplicates(nextData, callback, section);
    }
    return null;
  }

  @Nullable
  public static <T> String detectDuplicates(
      List<? extends T> data, Callback<T> callback, final @Nullable String section) {
    int idx = 0;
    for (ListIterator<? extends T> it = data.listIterator(); it.hasNext(); idx++) {
      final int currentIndex = idx;
      int nextIdx = it.nextIndex() + 1;
      T item = it.next();
      for (ListIterator<? extends T> jt = data.listIterator(nextIdx); jt.hasNext(); nextIdx++) {
        final int nextIndex = nextIdx;
        T other = jt.next();
        if (callback.areItemsTheSame(item, other)) {
          final String itemDesc = item != null ? item.toString() : "null";
          final String otherItemDesc = other != null ? other.toString() : "null";
          final String itemType =
              "<cls>" + (item != null ? item.getClass().getName() : "null") + "</cls>";
          final String otherItemType =
              "<cls>" + (other != null ? other.getClass().getName() : "null") + "</cls>";

          SectionsDebugEventLogger.log(
              "DuplicateItemsInSection",
              attributes -> {
                attributes.put("section", section);
                attributes.put("size", data.size());
                attributes.put("item", itemDesc);
                attributes.put("item-type", itemType);
                attributes.put("other-item", otherItemDesc);
                attributes.put("other-item-type", otherItemType);
                attributes.put("index", currentIndex);
                attributes.put("next-index", nextIndex);
                return null;
              });

          /* we don't need to know how many, just that there is at least one duplicate */
          return "Duplicates are [type:"
              + itemType
              + " hash:"
              + System.identityHashCode(item)
              + " position:"
              + idx
              + "] and [type:"
              + otherItemType
              + " hash:"
              + System.identityHashCode(other)
              + " position:"
              + nextIdx
              + "]";
        }
      }
    }
    return null;
  }

  /**
   * @return true if detect moves should be enabled when performing the Diff. Detect moves is
   *     enabled by default
   */
  private static boolean isDetectMovesEnabled(@Nullable Diff<Boolean> detectMoves) {
    return detectMoves == null || detectMoves.getNext() == null || detectMoves.getNext();
  }

  /**
   * @return true if duplicates detection should be enabled when performing the Diff. Always on on
   *     debug. Default to false on release.
   */
  private static boolean isDetectDuplicatesEnabled(@Nullable Diff<Boolean> alwaysDetectDuplicates) {
    if (alwaysDetectDuplicates == null || alwaysDetectDuplicates.getNext() == null) {
      return LithoDebugConfigurations.isDebugModeEnabled;
    }
    return alwaysDetectDuplicates.getNext();
  }

  private static class DiffSectionOperationExecutor
      implements RecyclerBinderUpdateCallback.OperationExecutor {

    private final ChangeSet mChangeSet;

    private DiffSectionOperationExecutor(ChangeSet changeSet) {
      mChangeSet = changeSet;
    }

    @Override
    public void executeOperations(ComponentContext c, List<Operation> operations) {
      for (int i = 0, size = operations.size(); i < size; i++) {
        final Operation operation = operations.get(i);
        final List<ComponentContainer> components = operation.getComponentContainers();
        final List<Diff<?>> dataHolders = operation.getDataContainers();
        final int opSize = components == null ? 1 : components.size();
        switch (operation.getType()) {
          case Operation.INSERT:
            if (opSize == 1) {
              final RenderInfo renderInfo =
                  (components != null && !components.isEmpty())
                      ? components.get(0).getRenderInfo()
                      : null;
              if (renderInfo != null) {
                mChangeSet.insert(
                    operation.getIndex(),
                    renderInfo,
                    c.getTreePropContainerCopy(),
                    dataHolders.get(0).getNext());
              }
            } else {
              // NULLSAFE_FIXME[Parameter Not Nullable]
              final List<RenderInfo> renderInfos = extractComponentInfos(opSize, components);
              mChangeSet.insertRange(
                  operation.getIndex(),
                  opSize,
                  renderInfos,
                  c.getTreePropContainerCopy(),
                  extractNextData(dataHolders));
            }
            break;

          case Operation.DELETE:
            // RecyclerBinderUpdateCallback uses the toIndex field of the operation to store count.
            final int count = operation.getToIndex();
            if (count == 1) {
              mChangeSet.delete(operation.getIndex(), dataHolders.get(0).getPrevious());
            } else {
              mChangeSet.deleteRange(operation.getIndex(), count, extractPrevData(dataHolders));
            }
            break;

          case Operation.MOVE:
            mChangeSet.move(
                operation.getIndex(), operation.getToIndex(), dataHolders.get(0).getNext());
            break;

          case Operation.UPDATE:
            if (opSize == 1) {
              final RenderInfo renderInfo =
                  (components != null && !components.isEmpty())
                      ? components.get(0).getRenderInfo()
                      : null;
              if (renderInfo != null) {
                mChangeSet.update(
                    operation.getIndex(),
                    renderInfo,
                    c.getTreePropContainerCopy(),
                    dataHolders.get(0).getPrevious(),
                    dataHolders.get(0).getNext());
              }
            } else {
              // NULLSAFE_FIXME[Parameter Not Nullable]
              final List<RenderInfo> renderInfos = extractComponentInfos(opSize, components);
              mChangeSet.updateRange(
                  operation.getIndex(),
                  opSize,
                  renderInfos,
                  c.getTreePropContainerCopy(),
                  extractPrevData(dataHolders),
                  extractNextData(dataHolders));
            }
            break;
        }
      }
    }

    private static List<RenderInfo> extractComponentInfos(
        int opSize, List<ComponentContainer> components) {
      final List<RenderInfo> renderInfos = new ArrayList<>(opSize);
      int i = 0;
      for (ComponentContainer container : components) {
        if (i++ == opSize) {
          break;
        }
        renderInfos.add(container.getRenderInfo());
      }
      return renderInfos;
    }

    private static List<Object> extractPrevData(List<Diff<?>> dataHolders) {
      final List<Object> data = new ArrayList<>(dataHolders.size());
      for (Diff<?> diff : dataHolders) {
        data.add(diff.getPrevious());
      }
      return data;
    }

    private static List<Object> extractNextData(List<Diff<?>> dataHolders) {
      final List<Object> data = new ArrayList<>(dataHolders.size());
      for (Diff<?> diff : dataHolders) {
        data.add(diff.getNext());
      }
      return data;
    }
  }

  private static class ComponentRenderer<T>
      implements RecyclerBinderUpdateCallback.ComponentRenderer<T> {

    private final EventHandler<RenderEvent<T>> mRenderEventEventHandler;
    private final SectionContext mSectionContext;

    private ComponentRenderer(
        EventHandler<RenderEvent<T>> renderEventEventHandler, SectionContext sectionContext) {
      mRenderEventEventHandler = renderEventEventHandler;
      mSectionContext = sectionContext;
    }

    @Override
    public RenderInfo render(Object o, int index) {
      RenderInfo renderInfo =
          DataDiffSection.dispatchRenderEvent(mRenderEventEventHandler, index, o, null);

      if (renderInfo == null) {
        ComponentsReporter.emitMessage(
            ComponentsReporter.LogLevel.ERROR,
            "DataDiffSection:RenderInfoNull",
            RENDER_INFO_RETURNS_NULL_MSG);

        renderInfo = ComponentRenderInfo.createEmpty();
      }

      if (LithoDebugConfigurations.isRenderInfoDebuggingEnabled) {
        renderInfo.addDebugInfo(SONAR_SECTIONS_DEBUG_INFO_TAG, mSectionContext.getSectionScope());
      }

      return renderInfo;
    }
  }

  @VisibleForTesting
  static class Callback<T> extends DiffUtil.Callback {

    private final @Nullable List<? extends T> mPreviousData;
    private final @Nullable List<? extends T> mNextData;
    private final SectionContext mSectionContext;
    @Nullable private final EventHandler<OnCheckIsSameItemEvent<T>> mIsSameItemEventHandler;
    @Nullable private final EventHandler<OnCheckIsSameContentEvent<T>> mIsSameContentEventHandler;

    private final ThreadLocal<OnCheckIsSameItemEvent<T>> mIsSameItemEventStates;
    private final OnCheckIsSameItemEvent<T> mIsSameItemEventSingleton;
    private final AtomicBoolean mIsSameItemEventSingletonUsed;
    private static final OnCheckIsSameItemEvent<?> sDummy = new OnCheckIsSameItemEvent<>();

    Callback(
        SectionContext sectionContext,
        @Nullable List<? extends T> previousData,
        @Nullable List<? extends T> nextData) {
      mSectionContext = sectionContext;
      mIsSameItemEventHandler = DataDiffSection.getOnCheckIsSameItemEventHandler(mSectionContext);
      mIsSameContentEventHandler =
          DataDiffSection.getOnCheckIsSameContentEventHandler(mSectionContext);

      mPreviousData = previousData;
      mNextData = nextData;

      mIsSameItemEventStates =
          new ThreadLocal<OnCheckIsSameItemEvent<T>>() {
            @SuppressWarnings("unchecked") // sentinel capture-to-T (reference-compared)
            @Override
            protected OnCheckIsSameItemEvent<T> initialValue() {
              OnCheckIsSameItemEvent<T> event = new OnCheckIsSameItemEvent<>();
              event.previousItem = (T) sDummy.previousItem;
              event.nextItem = (T) sDummy.nextItem;
              return event;
            }
          };
      mIsSameItemEventSingleton = new OnCheckIsSameItemEvent<>();
      mIsSameItemEventSingletonUsed = new AtomicBoolean(false);
    }

    @Override
    public int getOldListSize() {
      return mPreviousData == null ? 0 : mPreviousData.size();
    }

    @Override
    public int getNewListSize() {
      return mNextData == null ? 0 : mNextData.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
      if (mPreviousData == null || mNextData == null) {
        return false;
      }
      final T previous = mPreviousData.get(oldItemPosition);
      final T next = mNextData.get(newItemPosition);

      return areItemsTheSame(previous, next);
    }

    @SuppressWarnings("unchecked") // sentinel capture-to-T (reference-compared)
    private boolean areItemsTheSame(T previous, T next) {
      if (previous == next) {
        return true;
      }

      if (mIsSameItemEventHandler != null) {
        HasEventDispatcher hasEventDispatcher =
            mIsSameItemEventHandler.dispatchInfo.hasEventDispatcher;
        boolean owned = mIsSameItemEventSingletonUsed.compareAndSet(false, true);
        OnCheckIsSameItemEvent<T> isSameItemEventState;
        if (owned) {
          isSameItemEventState = mIsSameItemEventSingleton;
        } else {
          isSameItemEventState = mIsSameItemEventStates.get();
        }
        if (ComponentsConfiguration.reduceMemorySpikeDataDiffSection
            && hasEventDispatcher != null
            && isSameItemEventState != null
            && isSameItemEventState.previousItem == sDummy.previousItem) {
          isSameItemEventState.previousItem = previous;
          isSameItemEventState.nextItem = next;
          try {
            Object result = mIsSameItemEventHandler.dispatchEvent(isSameItemEventState);
            if (result == null) {
              return false;
            } else {
              return (Boolean) result;
            }
          } finally {
            isSameItemEventState.previousItem = (T) sDummy.previousItem;
            isSameItemEventState.nextItem = (T) sDummy.nextItem;
            if (owned) {
              mIsSameItemEventSingletonUsed.set(false);
            }
          }
        } else {
          return DataDiffSection.dispatchOnCheckIsSameItemEvent(
              mIsSameItemEventHandler, previous, next);
        }
      }

      return previous.equals(next);
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
      if (mPreviousData == null || mNextData == null) {
        return false;
      }
      final T previous = mPreviousData.get(oldItemPosition);
      final T next = mNextData.get(newItemPosition);

      return areContentsTheSame(previous, next);
    }

    private boolean areContentsTheSame(T previous, T next) {
      if (previous == next) {
        return true;
      }

      if (mIsSameContentEventHandler != null) {
        return DataDiffSection.dispatchOnCheckIsSameContentEvent(
            mIsSameContentEventHandler, previous, next);
      }

      return previous.equals(next);
    }
  }
}
