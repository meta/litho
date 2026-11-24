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

package com.facebook.rendercore;

import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import javax.annotation.Nullable;

/**
 * An ancestor View that offers actions through its descendants' existing accessibility owners.
 *
 * <p>The nearest provider is a boundary even while unavailable. Implementations populate only their
 * own actions, preserve the supplied node's other properties, and recheck state when invoked.
 */
public interface AccessibilityActionProvider {

  /** Identifies collections whose node owner forwards these actions, without reading gates. */
  default boolean isAccessibilityCollectionForDescendants(View view) {
    return view instanceof AccessibilityActionForwardingCollection;
  }

  /** Recognises an action without reading exposure gates or starting it. */
  boolean supportsAccessibilityActionForDescendants(int action);

  /** Adds currently available actions without changing the descendant View or its delegate. */
  void addAccessibilityActionsForDescendants(AccessibilityNodeInfo info);

  /** Returns true only when the requested action was performed. */
  boolean performAccessibilityActionForDescendants(int action, @Nullable Bundle arguments);
}
