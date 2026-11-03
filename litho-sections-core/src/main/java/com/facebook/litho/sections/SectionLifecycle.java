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

import static com.facebook.litho.sections.SectionContext.NO_SCOPE_EVENT_HANDLER;

import androidx.annotation.Nullable;
import com.facebook.litho.ComponentUtils;
import com.facebook.litho.ComponentsReporter;
import com.facebook.litho.EventDispatchInfo;
import com.facebook.litho.EventDispatcher;
import com.facebook.litho.EventHandler;
import com.facebook.litho.EventTrigger;
import com.facebook.litho.EventTriggerKey;
import com.facebook.litho.EventTriggerTarget;
import com.facebook.litho.EventTriggersContainer;
import com.facebook.litho.Handle;
import com.facebook.litho.NoOpEventHandler;
import com.facebook.litho.StateContainer;
import com.facebook.litho.TreePropContainer;
import com.facebook.litho.annotations.EventHandlerRebindMode;
import com.facebook.litho.annotations.OnCreateTreeProp;
import com.facebook.litho.sections.annotations.DiffSectionSpec;
import com.facebook.litho.sections.annotations.GroupSectionSpec;
import com.facebook.litho.sections.annotations.OnDataBound;
import com.facebook.litho.sections.annotations.OnDataRendered;
import com.facebook.litho.sections.annotations.OnDiff;
import com.facebook.litho.widget.SmoothScrollAlignmentType;

public abstract class SectionLifecycle implements EventDispatcher, EventTriggerTarget {
  static final String WRONG_CONTEXT_FOR_EVENT_HANDLER =
      "SectionLifecycle:WrongContextForEventHandler";

  /**
   * This methods will delegate to the {@link GroupSectionSpec} method annotated with {@link
   * com.facebook.litho.sections.annotations.OnCreateChildren}
   */
  @Nullable
  protected Children createChildren(SectionContext c) {
    return null;
  }

  /**
   * This method will delegate to the {@link DiffSectionSpec} method annotated with {@link OnDiff}
   */
  protected void generateChangeSet(
      SectionContext c,
      ChangeSet changeSet,
      SectionContext previousContext,
      Section previous,
      SectionContext nextContext,
      Section next) {}

  /**
   * This method will delegate to the {@link Section}Spec method annotated with {@link OnDataBound}
   */
  protected void dataBound(SectionContext c) {}

  /**
   * This method will delegate to the {@link Section}Spec method annotated with {@link
   * OnDataRendered}.
   */
  protected void dataRendered(
      SectionContext c,
      boolean isDataChanged,
      boolean isMounted,
      long uptimeMillis,
      int firstVisibleIndex,
      int lastVisibleIndex,
      ChangesInfo changesInfo,
      int globalOffset) {}

  protected void bindService(SectionContext c) {}

  protected void unbindService(SectionContext c) {}

  protected void createInitialState(SectionContext c) {}

  protected void createService(SectionContext c) {}

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  @Nullable
  @Override
  public final Object dispatchOnEvent(EventHandler eventHandler, Object eventState) {
    try {
      return dispatchOnEventImpl(eventHandler, eventState);
    } catch (Exception e) {
      if (eventHandler.dispatchInfo.componentContext != null) {
        throw ComponentUtils.wrapWithMetadata(eventHandler.dispatchInfo.componentContext, e);
      } else {
        throw e;
      }
    }
  }

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  protected @Nullable Object dispatchOnEventImpl(EventHandler eventHandler, Object eventState) {
    // Do nothing by default.
    return null;
  }

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  @Override
  @Nullable
  public final Object acceptTriggerEvent(
      EventTrigger eventTrigger, Object eventState, Object[] params) {
    try {
      return acceptTriggerEventImpl(eventTrigger, eventState, params);
    } catch (Exception e) {
      if (eventTrigger.getComponentContext() != null) {
        throw ComponentUtils.wrapWithMetadata(eventTrigger.getComponentContext(), e);
      } else {
        throw e;
      }
    }
  }

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  protected @Nullable Object acceptTriggerEventImpl(
      EventTrigger eventTrigger, Object eventState, Object[] params) {
    // Do nothing by default
    return null;
  }

  protected void viewportChanged(
      SectionContext sectionContext,
      int firstVisibleItem,
      int lastVisibleItem,
      int totalItemsCount,
      int firstFullyVisibleItem,
      int lastFullyVisibleItem) {}

  protected void refresh(SectionContext sectionContext) {}

  /**
   * Call this to transfer the {@link com.facebook.litho.annotations.State} annotated values between
   * two {@link Section} with the same global scope.
   */
  protected void transferState(
      @Nullable StateContainer previousStateContainer,
      @Nullable StateContainer nextStateContainer) {}

  /** Call this to transfer the Services between two {@link Section} with the same global scope. */
  protected void transferService(SectionContext c, Section oldSection, Section newSection) {}

  @Nullable
  protected Object getService(Section section) {
    return null;
  }

  /**
   * @return Whether it's necessary to generate a new changeSet for the sub-tree with root in next
   * @param previous the {@link Section} with the same globalScope in the previous tree.
   * @param next the {@link Section} for which we want to check whether a new changeSet is needed.
   */
  final boolean shouldComponentUpdate(Section previous, Section next) {
    boolean dirty = false;

    // If next is invalidated it means a state update was applied to it so we need to create
    // the ChangeSet regardless.
    if (!dirty && next != null) {
      dirty |= next.isInvalidated();
    }

    final SectionContext prevScopedContext = previous == null ? null : previous.getScopedContext();
    final SectionContext nextScopedContext = next == null ? null : next.getScopedContext();

    return dirty || shouldUpdate(prevScopedContext, previous, nextScopedContext, next);
  }

  protected boolean shouldUpdate(
      SectionContext previousScopedContext,
      Section previous,
      SectionContext nextScopedContext,
      Section next) {

    if (previous == next) {
      return false;
    }

    if (previous != null) {
      final StateContainer prevStateContainer =
          previous == null ? null : previous.getStateContainer();
      final StateContainer nextStateContainer = next == null ? null : next.getStateContainer();
      return !previous.isEquivalentTo(next)
          || !ComponentUtils.hasEquivalentState(prevStateContainer, nextStateContainer);
    }

    return true;
  }

  /**
   * @return true if this lifecycle will generate a changeSet. If false this lifecycle will instead
   *     implement createChildren.
   */
  public boolean isDiffSectionSpec() {
    return false;
  }

  @Nullable
  protected String verifyChangeSet(SectionContext context) {
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  protected static <E> EventHandler<E> newEventHandler(
      final Class<? extends Section> reference,
      final String className,
      final SectionContext c,
      final int id,
      final @Nullable Object[] params,
      final EventHandlerRebindMode mode) {
    Section section;
    if (c == null || (section = c.getSectionScope()) == null) {
      ComponentsReporter.emitMessage(
          ComponentsReporter.LogLevel.FATAL,
          NO_SCOPE_EVENT_HANDLER,
          "Creating event handler without scope.");
      return NoOpEventHandler.getNoOpEventHandler();
    } else if (reference != section.getClass()) {
      ComponentsReporter.emitMessage(
          ComponentsReporter.LogLevel.ERROR,
          WRONG_CONTEXT_FOR_EVENT_HANDLER + ":" + section.getSimpleName(),
          String.format(
              "A Event handler from %s was created using a context from %s. "
                  + "Event Handlers must be created using a SectionContext from its Section.",
              className, section.getSimpleName()));
    }
    final EventHandler<E> eventHandler =
        new EventHandler<>(id, mode, new EventDispatchInfo(section, c), params);
    final ChangeSetCalculationState calculationState = c.getChangeSetCalculationState();
    if (calculationState != null && calculationState.isActive()) {
      if (mode == EventHandlerRebindMode.REBIND) {
        calculationState.recordEventHandler(c.getGlobalKey().toString(), eventHandler);
      }
    }
    return eventHandler;
  }

  /**
   * This variant is used to create an EventTrigger used to register this component as a target in
   * {@link EventTriggersContainer}
   */
  protected static <E> EventTrigger<E> newEventTrigger(
      SectionContext c, Section section, int methodId) {
    return c.newEventTrigger(methodId, section.getKey(), section.getHandle());
  }

  /**
   * This is used to create a Trigger to be invoked later, e.g. in the context of the deprecated
   * trigger API TextInput.requestFocusTrigger(c, "my_key").
   */
  @Deprecated
  protected static <E> EventTrigger<E> newEventTrigger(
      SectionContext c, String childKey, int methodId) {
    return c.newEventTrigger(methodId, childKey, null);
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // heterogeneous event plumbing (raw by design)
  @Nullable
  protected static EventTrigger getEventTrigger(SectionContext c, int id, String key) {
    if (c.getSectionScope() == null) {
      return null;
    }

    EventTrigger trigger =
        c.getSectionTree()
            .getEventTrigger(new EventTriggerKey(c.getSectionScope().getGlobalKey(), id, key));

    if (trigger == null) {
      return null;
    }

    return trigger;
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // heterogeneous event plumbing (raw by design)
  @Nullable
  protected static EventTrigger getEventTrigger(SectionContext c, int id, Handle handle) {
    if (c.getSectionScope() == null) {
      return null;
    }

    EventTrigger trigger = c.getSectionTree().getEventTrigger(handle, id);

    if (trigger == null) {
      return null;
    }

    return trigger;
  }

  /**
   * Retrieves all of the tree props used by this Section from the TreeProps map and sets the tree
   * props as fields on the ComponentImpl.
   */
  protected void populateTreePropContainer(@Nullable TreePropContainer parentTreePropContainer) {}

  /** Updates the TreeProps map with outputs from all {@link OnCreateTreeProp} methods. */
  protected TreePropContainer getTreePropContainerForChildren(
      SectionContext c, @Nullable TreePropContainer previousTreeProps) {
    return previousTreeProps;
  }

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  @Nullable
  public static EventHandler getLoadingEventHandler(SectionContext context) {
    Section scopedSection = context.getSectionScope();
    if (scopedSection == null) {
      return null;
    }

    while (scopedSection.getParent() != null) {
      if (scopedSection.loadingEventHandler != null) {
        return scopedSection.loadingEventHandler;
      }
      scopedSection = scopedSection.getParent();
    }

    return context.getTreeLoadingEventHandler();
  }

  @SuppressWarnings({"unchecked"}) // heterogeneous event plumbing (raw by design)
  public static void dispatchLoadingEvent(
      SectionContext context,
      boolean isEmpty,
      LoadingEvent.LoadingState loadingState,
      @Nullable Throwable t) {
    final EventHandler<LoadingEvent> loadingEventHandler = getLoadingEventHandler(context);

    if (loadingEventHandler != null) {
      LoadingEvent loadingEvent = new LoadingEvent();
      loadingEvent.isEmpty = isEmpty;
      loadingEvent.loadingState = loadingState;
      loadingEvent.t = t;
      loadingEventHandler.dispatchEvent(loadingEvent);
    }
  }

  /** Scroll to the index of the section. */
  public static void requestFocus(SectionContext c, int index) {
    final Section scopedSection = c.getSectionScope();
    final SectionTree sectionTree = c.getSectionTree();

    if (scopedSection == null || sectionTree == null) {
      return;
    }

    sectionTree.requestFocus(scopedSection, index);
  }

  /** Scroll to the beginning of the section with the given key. */
  public static void requestFocus(SectionContext c, String sectionKey) {
    requestFocus(c, sectionKey, FocusType.START);
  }

  /** Scroll to the index in the section with the given key. */
  public static void requestFocus(SectionContext c, String sectionKey, FocusType focusType) {
    final Section scopedSection = c.getSectionScope();
    final SectionTree sectionTree = c.getSectionTree();

    if (scopedSection == null || sectionTree == null) {
      return;
    }

    final String globalKey = scopedSection.getGlobalKey() + sectionKey;

    switch (focusType) {
      case START:
        sectionTree.requestFocusStart(globalKey);
        break;
      case END:
        sectionTree.requestFocusEnd(globalKey);
        break;
    }
  }

  /**
   * Scroll to the index in the section with an additional offset. One notable difference between
   * this and the {@link #requestFocus(SectionContext, int)} API is that this will *always* take an
   * action, while {@link #requestFocus(SectionContext, int)} will ignore the command if the item is
   * visible on the screen.
   *
   * @param offset distance, in pixels, from the top of the screen to scroll the requested item
   */
  public static void requestFocusWithOffset(SectionContext c, int index, int offset) {
    final Section scopedSection = c.getSectionScope();
    final SectionTree sectionTree = c.getSectionTree();

    if (scopedSection == null || sectionTree == null) {
      return;
    }

    sectionTree.requestFocusWithOffset(scopedSection, index, offset);
  }

  /**
   * Scroll to the beginning of the section with the given key and an additional offset. One notable
   * difference between this and the {@link #requestFocus(SectionContext, String)} API is that this
   * will *always* take an action, while {@link #requestFocus(SectionContext, String)} will ignore
   * the command if the item is visible on the screen.
   *
   * @param sectionKey the key of the section component.
   * @param offset distance, in pixels, from the top of the screen to scroll the requested item.
   */
  public static void requestFocusWithOffset(SectionContext c, String sectionKey, int offset) {
    requestFocusWithOffset(c, sectionKey, 0, offset);
  }

  /**
   * Scroll to the index in the section with the given key and an additional offset. One notable
   * difference between this and the {@link #requestFocus(SectionContext, String)} API is that this
   * will *always* take an action, while {@link #requestFocus(SectionContext, String)} will ignore
   * the command if the item is visible on the screen.
   *
   * @param sectionKey the key of the section component.
   * @param index the index the section component
   * @param offset distance, in pixels, from the top of the screen to scroll the requested item.
   */
  public static void requestFocusWithOffset(
      SectionContext c, String sectionKey, int index, int offset) {
    final Section scopedSection = c.getSectionScope();
    final SectionTree sectionTree = c.getSectionTree();

    if (scopedSection == null || sectionTree == null) {
      return;
    }

    final String globalKey = scopedSection.getGlobalKey() + sectionKey;

    sectionTree.requestFocusWithOffset(globalKey, index, offset);
  }

  public static void requestSmoothFocus(SectionContext c, int index) {
    requestSmoothFocus(c, "", index, 0, SmoothScrollAlignmentType.DEFAULT);
  }

  public static void requestSmoothFocus(
      SectionContext c, int index, SmoothScrollAlignmentType type) {
    requestSmoothFocus(c, "", index, 0, type);
  }

  public static void requestSmoothFocus(
      SectionContext c, String keyString, int index, int offset, SmoothScrollAlignmentType type) {
    final Section scopedSection = c.getSectionScope();
    final SectionTree sectionTree = c.getSectionTree();

    if (scopedSection == null || sectionTree == null) {
      return;
    }

    final String globalKey = scopedSection.getGlobalKey() + keyString;
    sectionTree.requestSmoothFocus(globalKey, index, offset, type);
  }

  /**
   * Check to see if an index provided for within a section is valid
   *
   * @param keyString the key of the section component
   * @param index the index in the section component
   * @return if the index is valid for that section component
   */
  public static boolean isSectionIndexValid(SectionContext c, String keyString, int index) {
    final Section scopedSection = c.getSectionScope();
    final SectionTree sectionTree = c.getSectionTree();

    if (scopedSection == null || sectionTree == null) {
      return false;
    }

    final String globalKey = scopedSection.getGlobalKey() + keyString;
    return sectionTree.isSectionIndexValid(globalKey, index);
  }
}
