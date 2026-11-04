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

package com.facebook.litho.testing.subcomponents;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.BaseMountingView;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentHost;
import com.facebook.litho.DebugComponent;
import com.facebook.litho.DebugLayoutNode;
import com.facebook.litho.EventHandler;
import com.facebook.litho.LithoView;
import com.facebook.litho.LithoViewTestHelper;
import com.facebook.litho.StateContainer;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Wraps a {@link DebugComponent} exposing only information that are safe to use for test
 * assertions.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
@Immutable
public class InspectableComponent {
  private final DebugComponent mComponent;

  private InspectableComponent(DebugComponent component) {
    mComponent = component;
  }

  /**
   * @return The root {@link InspectableComponent} of a LithoView.
   */
  @Nullable
  public static InspectableComponent getRootInstance(BaseMountingView view) {
    final DebugComponent rootInstance = DebugComponent.getRootInstance(view);

    if (rootInstance == null) {
      return null;
    }

    return new InspectableComponent(rootInstance);
  }

  /** Obtain an instance of a Component nested inside the given inspectable Component. */
  @Nullable
  public InspectableComponent getNestedInstance(Component component) {
    final Queue<DebugComponent> queue = new LinkedList<>(mComponent.getChildComponents());

    while (!queue.isEmpty()) {
      final DebugComponent childComponent = queue.remove();

      if (Preconditions.checkNotNull(childComponent).getComponent() == component) {
        return new InspectableComponent(childComponent);
      }

      queue.addAll(childComponent.getChildComponents());
    }

    return null;
  }

  /**
   * @return A canonical name for this component. Suitable to present to the user.
   */
  public String getName() {
    return mComponent.getComponent().getClass().getName();
  }

  /**
   * @return A simpler canonical name for this component. Suitable to present to the user.
   */
  public String getSimpleName() {
    return mComponent.getComponent().getSimpleName();
  }

  /**
   * @return The class of the underlying Component.
   */
  @SuppressWarnings("rawtypes")
  public Class getComponentClass() {
    return mComponent.getComponent().getClass();
  }

  /**
   * Get the list of components composed by this component. This will not include any {@link View}s
   * that are mounted by this component as those are not components. Use {@link this#getMountedView}
   * for that.
   *
   * @return A list of child components.
   */
  public List<InspectableComponent> getChildComponents() {
    final List<DebugComponent> debugComponents = mComponent.getChildComponents();
    final List<InspectableComponent> res = new ArrayList<>(debugComponents.size());

    for (DebugComponent debugComponent : debugComponents) {
      res.add(new InspectableComponent(debugComponent));
    }

    return res;
  }

  /**
   * @return A mounted view or null if this component does not mount a view.
   */
  @Nullable
  public View getMountedView() {
    return mComponent.getMountedView();
  }

  /**
   * @return A mounted drawable or null if this component does not mount a drawable.
   */
  @Nullable
  public Drawable getMountedDrawable() {
    return mComponent.getMountedDrawable();
  }

  /**
   * @return The litho view hosting this component.
   */
  @Nullable
  public BaseMountingView getLithoView() {
    return mComponent.getLithoView();
  }

  /**
   * @return The bounds of this component relative to its hosting {@link LithoView}.
   */
  public Rect getBoundsInLithoView() {
    return mComponent.getBoundsInLithoView();
  }

  /**
   * @return The bounds of this component relative to its parent.
   */
  public Rect getBounds() {
    return mComponent.getBounds();
  }

  /**
   * @return This component's testKey or null if none is set.
   */
  @Nullable
  public String getTestKey() {
    return mComponent.getTestKey();
  }

  /**
   * Returns this component's testKey or null if none is set.
   *
   * <p>Unlike {@link #getTestKey()}, this function can return a test key set on any Component,
   * including container Components which resolve into LayoutNodes.
   */
  @Nullable
  public String getComponentTestKey() {
    return mComponent.getComponentTestKey();
  }

  /**
   * @return This component's componentTag or null if none is set.
   */
  @Nullable
  public Object getComponentTag() {
    return mComponent.getComponentTag();
  }

  /**
   * @return The text content of the component wrapped by the debug component, or null if no
   *     TextContent/TextView are found.
   */
  @Nullable
  public String getTextContent() {
    return mComponent.getTextContent();
  }

  /**
   * @return The {@link ComponentHost} that wraps this component or null if one cannot be found.
   */
  @Nullable
  public ComponentHost getComponentHost() {
    return mComponent.getComponentHost();
  }

  /**
   * @return This component's key or null if none is set.
   */
  @Nullable
  public String getKey() {
    return mComponent.getKey();
  }

  /**
   * @return The Component instance this debug component wraps.
   */
  public Component getComponent() {
    return mComponent.getComponent();
  }

  /**
   * @return The foreground drawable asscociated with this debug component. May be null.
   */
  @Nullable
  public Drawable getForeground() {
    final DebugLayoutNode layout = mComponent.getLayoutNode();
    return layout == null ? null : layout.getForeground();
  }

  /**
   * @return The background drawable asscociated with this debug component. May be null.
   */
  @Nullable
  public Drawable getBackground() {
    final DebugLayoutNode layout = mComponent.getLayoutNode();
    return layout == null ? null : layout.getBackground();
  }

  /**
   * @return The int value of the importantForAccessibility property on this debug component.
   */
  @Nullable
  public Integer getImportantForAccessibility() {
    final DebugLayoutNode layout = mComponent.getLayoutNode();
    return layout == null ? null : layout.getImportantForAccessibility();
  }

  /**
   * @return The boolean value of the focusable property on this debug component.
   */
  public boolean getFocusable() {
    final DebugLayoutNode layout = mComponent.getLayoutNode();
    return layout != null && layout.getFocusable();
  }

  /**
   * @return The boolean value of the screenReaderFocusable property on this debug component.
   */
  public boolean getScreenReaderFocusable() {
    final DebugLayoutNode layout = mComponent.getLayoutNode();
    return layout != null && layout.getScreenReaderFocusable();
  }

  /**
   * @return The content description CharSequence on this debug component. May be null.
   */
  @Nullable
  public CharSequence getContentDescription() {
    final DebugLayoutNode layout = mComponent.getLayoutNode();
    return layout == null ? null : layout.getContentDescription();
  }

  @Nullable
  public StateContainer getStateContainer() {
    return mComponent.getStateContainer();
  }

  public String getId() {
    return mComponent.getGlobalKey();
  }

  @Nullable
  @SuppressWarnings("rawtypes")
  public EventHandler getClickHandler() {
    final DebugLayoutNode layout = mComponent.getLayoutNode();
    return layout == null ? null : layout.getClickHandler();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("InspectableComponent of ");
    sb.append(getSimpleName());
    sb.append(" with hierarchy\n");
    // NULLSAFE_FIXME[Parameter Not Nullable]
    sb.append(LithoViewTestHelper.viewToString(mComponent.getLithoView()));

    return sb.toString();
  }
}
