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

import android.util.Pair;
import androidx.annotation.Nullable;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.EventHandler;
import com.facebook.litho.GlobalKey;
import com.facebook.litho.StringGlobalKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Container for data, besides the ChangeSet itself, created as part of a changeset calculation:
 * this is local to a particular changeset calculation and is only active while the changeset is
 * being calculated.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class ChangeSetCalculationState {

  private @Nullable State mState = new State();

  /**
   * @return whether the associated changeset calculation is still in progress.
   */
  boolean isActive() {
    return mState != null;
  }

  @SuppressWarnings({"rawtypes"}) // heterogeneous event plumbing (raw by design)
  void recordEventHandler(String globalKey, EventHandler eventHandler) {
    if (mState == null) {
      throw new RuntimeException("Trying to use inactive ChangeSetCalculationState!");
    }
    mState.eventHandlers.add(new Pair<>(new StringGlobalKey(globalKey), eventHandler));
  }

  List<Pair<GlobalKey, EventHandler<?>>> getEventHandlers() {
    if (mState == null) {
      throw new RuntimeException("Trying to use inactive ChangeSetCalculationState!");
    }
    return mState.eventHandlers;
  }

  /**
   * Marks the changeset calculation as done and drops this state - you should now now longer use
   * this object.
   */
  void clear() {
    if (mState == null) {
      throw new IllegalStateException("Trying to clear inactive ChangeSetCalculationState!");
    }
    mState = null;
  }

  /**
   * The actual implementation of the calculation state - ChangeSetCalculationState is just a box
   * for this data which can be cleared.
   */
  private static class State {
    private final ArrayList<Pair<GlobalKey, EventHandler<?>>> eventHandlers = new ArrayList<>();
  }
}
