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

import android.os.Bundle;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.Component;
import com.facebook.litho.EventHandler;
import com.facebook.litho.StateValue;
import com.facebook.litho.annotations.FromEvent;
import com.facebook.litho.annotations.OnCreateInitialState;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.annotations.OnUpdateState;
import com.facebook.litho.annotations.Param;
import com.facebook.litho.annotations.Prop;
import com.facebook.litho.annotations.State;
import com.facebook.litho.sections.Children;
import com.facebook.litho.sections.Section;
import com.facebook.litho.sections.SectionContext;
import com.facebook.litho.sections.annotations.GroupSectionSpec;
import com.facebook.litho.sections.annotations.OnCreateChildren;
import com.facebook.litho.widget.RenderInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A {@link HideableDataDiffSectionSpec} that wraps a {@link DataDiffSectionSpec}. It provides the
 * ability to remove an item from the DataDiffSection via the {@link HideItemEvent}. This {@link
 * Section} emits the following events:
 *
 * <p>{@link RenderWithHideItemHandlerEvent} whenever it needs a {@link Component} to render a model
 * T from the list of data. A {@link HideItemEvent} handler is a param of this event. Providing an
 * handler for this {@link OnEvent} is mandatory.
 *
 * <p>{@link GetUniqueIdentifierEvent} is fired when a single unique identifier is needed for a
 * model object.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
@GroupSectionSpec(events = {RenderWithHideItemHandlerEvent.class, GetUniqueIdentifierEvent.class})
public class HideableDataDiffSectionSpec<T> {

  @OnCreateInitialState
  @SuppressWarnings("rawtypes")
  public static <T> void onCreateInitialState(
      SectionContext c, StateValue<HashSet<Object>> blacklistState) {
    blacklistState.set(new HashSet<>());
  }

  @OnUpdateState
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void onBlacklistUpdate(
      StateValue<HashSet<Object>> blacklistState,
      @Param Object modelObject,
      @Param EventHandler<GetUniqueIdentifierEvent> getUniqueIdentifierHandlerParam) {
    HashSet<Object> newSet = new HashSet<>();
    final HashSet<Object> currentBlacklist = blacklistState.get();
    if (currentBlacklist != null) {
      newSet.addAll(currentBlacklist);
    }
    newSet.add(
        HideableDataDiffSection.dispatchGetUniqueIdentifierEvent(
            getUniqueIdentifierHandlerParam, modelObject));
    blacklistState.set(newSet);
  }

  @OnCreateChildren
  @SuppressWarnings("rawtypes")
  protected static <T> Children onCreateChildren(
      SectionContext c,
      @State HashSet<Object> blacklistState,
      @Prop List<T> data,
      @Prop EventHandler<GetUniqueIdentifierEvent> getUniqueIdentifierHandler,
      @Prop(optional = true) EventHandler<OnCheckIsSameItemEvent<T>> onSameItemEventHandler,
      @Prop(optional = true) EventHandler<OnCheckIsSameContentEvent<T>> onSameContentEventHandler) {
    return Children.create()
        .child(
            DataDiffSection.<T>create(c)
                .data(removeBlacklistedItems(c, data, blacklistState, getUniqueIdentifierHandler))
                .renderEventHandler(HideableDataDiffSection.<T>onRenderEvent(c))
                .onCheckIsSameContentEventHandler(onSameContentEventHandler)
                .onCheckIsSameItemEventHandler(onSameItemEventHandler))
        .build();
  }

  @SuppressWarnings("rawtypes")
  private static <T> List<T> removeBlacklistedItems(
      SectionContext c,
      List<T> data,
      HashSet<Object> blacklist,
      EventHandler<GetUniqueIdentifierEvent> getItemUniqueIdentifierHandler) {
    ArrayList<T> builder = new ArrayList<>();
    final int size = data.size();
    for (int i = 0; i < size; i++) {
      final T model = data.get(i);
      if (!blacklist.contains(
          HideableDataDiffSection.dispatchGetUniqueIdentifierEvent(
              getItemUniqueIdentifierHandler, model))) {
        builder.add(model);
      }
    }

    return builder;
  }

  @OnEvent(RenderEvent.class)
  protected static <T> RenderInfo onRenderEvent(
      SectionContext c,
      @Prop EventHandler<RenderWithHideItemHandlerEvent> renderWithHideItemHandler,
      @FromEvent int index,
      @FromEvent T model,
      @FromEvent Bundle loggingExtras) {
    return HideableDataDiffSection.dispatchRenderWithHideItemHandlerEvent(
        renderWithHideItemHandler,
        index,
        model,
        HideableDataDiffSection.onHideItem(c),
        loggingExtras);
  }

  @OnEvent(HideItemEvent.class)
  public static void onHideItem(
      SectionContext c,
      @FromEvent Object model,
      @Prop EventHandler<GetUniqueIdentifierEvent> getUniqueIdentifierHandler) {
    HideableDataDiffSection.onBlacklistUpdateSync(c, model, getUniqueIdentifierHandler);
  }
}
