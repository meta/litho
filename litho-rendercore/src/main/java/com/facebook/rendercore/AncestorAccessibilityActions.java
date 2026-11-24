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

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Cooperation between existing real/virtual node owners and an ancestor action provider. */
public final class AncestorAccessibilityActions {

  private static final int MAX_SYSTEM_ACTION_ID = 0x01FFFFFF;
  private static final int FOCUS_ACTIONS =
      AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
          | AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS;

  private AncestorAccessibilityActions() {}

  /** See {@link #onInitializeAccessibilityNodeInfo(View, AccessibilityNodeInfo, boolean)}. */
  public static void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
    onInitializeAccessibilityNodeInfo(host, info, false);
  }

  /**
   * Augments collections and nodes whose own actions would shadow ancestor actions. Ordinary items
   * inherit the collection's actions without gaining an additional action-availability hint.
   *
   * <p>Ordinary descendants rely on accessibility-service ancestor-action discovery rather than
   * receiving a copy when an exposed collection carries the actions.
   *
   * <p>Call after the owner's node population. A collection owner can pass {@code isCollection}
   * before its layout manager supplies collection metadata. Only node actions are changed, never
   * View state.
   */
  public static void onInitializeAccessibilityNodeInfo(
      View host, AccessibilityNodeInfo info, boolean isCollection) {
    onInitializeAccessibilityNodeInfo(host, info, isCollection, false);
  }

  private static void onInitializeAccessibilityNodeInfo(
      View host, AccessibilityNodeInfo info, boolean isCollection, boolean isVirtual) {
    final AccessibilityActionProvider provider = findProvider(host);
    if (provider == null) {
      return;
    }
    if (!isCollection
        && (isVirtual
            || (!provider.isAccessibilityCollectionForDescendants(host)
                && !(host instanceof ScrollView)))
        && !ScrollView.class.getName().equals(info.getClassName())
        && !info.isScrollable()
        && info.getCollectionInfo() == null
        && !hasBlockingAction(info)
        && hasExposedCollectionAncestor(
            isVirtual ? host : (host == provider ? null : parentView(host)), provider)) {
      return;
    }
    final List<AccessibilityAction> ownActions = new ArrayList<>(info.getActionList());
    provider.addAccessibilityActionsForDescendants(info);
    final List<AccessibilityAction> actions = new ArrayList<>(info.getActionList());
    if (startsWithSameActions(actions, ownActions)) {
      return;
    }
    final List<AccessibilityAction> inherited = new ArrayList<>();
    for (int i = 0; i < actions.size(); i++) {
      final AccessibilityAction action = actions.get(i);
      info.removeAction(action);
      if (!ownActions.contains(action)) {
        inherited.add(action);
      }
    }
    for (int i = 0; i < ownActions.size(); i++) {
      info.addAction(ownActions.get(i));
    }
    for (int i = 0; i < inherited.size(); i++) {
      info.addAction(inherited.get(i));
    }
  }

  /**
   * Variant for virtual nodes populated through {@code ExploreByTouchHelper}, which rejects a node
   * whose legacy action mask has an accessibility focus bit. That mask includes every action ID up
   * to {@link AccessibilityNodeInfo#ACTION_SET_TEXT}, negative custom IDs among them, so inherited
   * actions that would set a focus bit are left out.
   */
  public static void onInitializeVirtualAccessibilityNodeInfo(
      View host, AccessibilityNodeInfo info) {
    final List<AccessibilityAction> ownActions = new ArrayList<>(info.getActionList());
    onInitializeAccessibilityNodeInfo(host, info, false, true);
    final List<AccessibilityAction> actions = new ArrayList<>(info.getActionList());
    for (int i = 0; i < actions.size(); i++) {
      final AccessibilityAction action = actions.get(i);
      final int id = action.getId();
      if (id <= AccessibilityNodeInfo.ACTION_SET_TEXT
          && (id & FOCUS_ACTIONS) != 0
          && !ownActions.contains(action)) {
        info.removeAction(action);
      }
    }
  }

  /** Identifies an inherited action without evaluating exposure gates or performing it. */
  public static boolean supportsAction(View host, int action) {
    final AccessibilityActionProvider provider = findProvider(host);
    return provider != null && provider.supportsAccessibilityActionForDescendants(action);
  }

  /** Resolves the nearest boundary again; a declining inner provider never falls back outward. */
  public static boolean performAction(View host, int action, @Nullable Bundle arguments) {
    final AccessibilityActionProvider provider = findProvider(host);
    return provider != null
        && provider.supportsAccessibilityActionForDescendants(action)
        && provider.performAccessibilityActionForDescendants(action, arguments);
  }

  /**
   * Final permission gate for an owner that has just finished handling, or declining, {@code
   * action} itself.
   *
   * <p>This decides only whether a declining owner may hand this one call on to {@link
   * #performAction(View, int, Bundle)}. It is not a request-level dedupe and it does not change
   * that method's own invocation scope: an owner that calls {@code performAction} directly keeps
   * the behaviour it already had.
   *
   * @param handled what the owner's own handling returned. {@code true} short-circuits, so no
   *     provider is consulted for a request the owner already served.
   * @param allowInheritedFallback the owner's permission for this call, carrying the full condition
   *     its existing code captured, at the point it captured it. {@code false} is terminal: no
   *     fresh support lookup is made to second-guess a permission already decided.
   */
  public static boolean performActionAfterOwner(
      View host,
      int action,
      @Nullable Bundle arguments,
      boolean handled,
      boolean allowInheritedFallback) {
    return handled || (allowInheritedFallback && performAction(host, action, arguments));
  }

  /** Whether a provider only appended actions with IDs the owner does not already have. */
  private static boolean startsWithSameActions(
      List<AccessibilityAction> actions, List<AccessibilityAction> ownActions) {
    if (actions.size() < ownActions.size()) {
      return false;
    }
    for (int i = 0; i < ownActions.size(); i++) {
      if (actions.get(i) != ownActions.get(i)) {
        return false;
      }
    }
    return true;
  }

  private static @Nullable AccessibilityActionProvider findProvider(View host) {
    View ancestor = host;
    while (ancestor != null) {
      if (ancestor instanceof AccessibilityActionProvider) {
        return (AccessibilityActionProvider) ancestor;
      }
      ancestor = parentView(ancestor);
    }
    return null;
  }

  private static boolean hasExposedCollectionAncestor(
      @Nullable View ancestor, AccessibilityActionProvider provider) {
    while (ancestor != null) {
      if (ancestor.isImportantForAccessibility()
          && provider.isAccessibilityCollectionForDescendants(ancestor)) {
        return true;
      }
      if (ancestor instanceof AccessibilityActionProvider) {
        return false;
      }
      ancestor = parentView(ancestor);
    }
    return false;
  }

  private static @Nullable View parentView(View view) {
    final ViewParent parent = view.getParent();
    return parent instanceof View ? (View) parent : null;
  }

  /**
   * Custom actions use resource IDs above the system range; IDs from package IDs at or above 0x80
   * are negative.
   */
  private static boolean hasBlockingAction(AccessibilityNodeInfo info) {
    final List<AccessibilityAction> actions = info.getActionList();
    for (int i = 0; i < actions.size(); i++) {
      final AccessibilityAction action = actions.get(i);
      final int id = action.getId();
      if (((id < 0 || id > MAX_SYSTEM_ACTION_ID) && !TextUtils.isEmpty(action.getLabel()))
          || id == AccessibilityAction.ACTION_DISMISS.getId()
          || id == AccessibilityAction.ACTION_EXPAND.getId()
          || id == AccessibilityAction.ACTION_COLLAPSE.getId()
          || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2
              && (id == AccessibilityAction.ACTION_DRAG_START.getId()
                  || id == AccessibilityAction.ACTION_DRAG_DROP.getId()
                  || id == AccessibilityAction.ACTION_DRAG_CANCEL.getId()))) {
        return true;
      }
    }
    return false;
  }
}
