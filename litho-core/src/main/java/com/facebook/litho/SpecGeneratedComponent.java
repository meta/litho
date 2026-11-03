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

package com.facebook.litho;

import static com.facebook.rendercore.debug.DebugEventDispatcher.beginTrace;
import static com.facebook.rendercore.debug.DebugEventDispatcher.endTrace;
import static com.facebook.rendercore.debug.DebugEventDispatcher.generateTraceIdentifier;
import static com.facebook.rendercore.utils.CommonUtils.getSectionNameForTracing;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;
import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Preconditions;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.infer.annotation.ThreadSafe;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnAttached;
import com.facebook.litho.annotations.OnCreateTreeProp;
import com.facebook.litho.annotations.OnDetached;
import com.facebook.litho.debug.LithoDebugEvent;
import com.facebook.litho.debug.LithoDebugEventAttributes;
import com.facebook.rendercore.ContentAllocator;
import com.facebook.rendercore.MountContentPools;
import com.facebook.rendercore.RenderType;
import com.facebook.rendercore.debug.DebugEventAttribute;
import com.facebook.rendercore.transitions.TransitionUtils;
import com.facebook.yoga.YogaFlexDirection;
import java.util.HashMap;

/** Base class for all component generated via the Spec API (@LayoutSpec and @MountSpec). */
@Nullsafe(Nullsafe.Mode.LOCAL)
public abstract class SpecGeneratedComponent extends Component
    implements ContentAllocator<Object>,
        HasEventDispatcher,
        EventDispatcher,
        EventTriggerTarget,
        HasEventTrigger {

  private static final DynamicValue<?>[] sEmptyArray = new DynamicValue<?>[0];

  private final String mSimpleName;
  private @Nullable Object mOwnerGlobalKey;

  // If we have a cachedLayout, onPrepare and onMeasure would have been called on it already.
  private @Nullable CommonProps mCommonProps;

  protected SpecGeneratedComponent(String simpleName) {
    mSimpleName = simpleName;
  }

  @VisibleForTesting
  protected SpecGeneratedComponent(int identityHashCode, String simpleName) {
    super(identityHashCode);
    mSimpleName = simpleName;
  }

  @VisibleForTesting
  @Nullable
  final Object getOwnerGlobalKey() {
    return mOwnerGlobalKey;
  }

  final void setOwnerGlobalKey(@Nullable Object ownerGlobalKey) {
    mOwnerGlobalKey = ownerGlobalKey;
  }

  /** Should only be used by logging to provide more readable messages. */
  @Override
  public final String getSimpleName() {
    final Component delegate = getSimpleNameDelegate();
    if (delegate == null) {
      return mSimpleName;
    }

    return mSimpleName + "(" + getFirstNonSimpleNameDelegate(delegate).getSimpleName() + ")";
  }

  final void bind(
      final @Nullable ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    if (c != null) {
      c.enterNoStateUpdatesMethod("bind");
    }
    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("bind:" + getSimpleName());
    }
    try {
      onBind(c, mountedContent, interStagePropsContainer);
    } catch (Exception e) {
      if (c != null) {
        ComponentUtils.handle(c, e);
      } else {
        throw e;
      }
    } finally {
      if (c != null) {
        c.exitNoStateUpdatesMethod();
      }
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  final void mount(
      final @Nullable ComponentContext c,
      final Object convertContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    if (c != null) {
      c.enterNoStateUpdatesMethod("mount");
    }
    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("mount:" + getSimpleName());
    }
    try {
      onMount(c, convertContent, interStagePropsContainer);
    } catch (Exception e) {
      if (c != null) {
        ComponentUtils.handle(c, e);
      } else {
        throw e;
      }
    } finally {
      if (c != null) {
        c.exitNoStateUpdatesMethod();
      }
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  final void unbind(
      final @Nullable ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("unbind:" + getSimpleName());
    }
    try {
      onUnbind(c, mountedContent, interStagePropsContainer);
    } catch (Exception e) {
      if (c != null) {
        ComponentUtils.handle(c, e);
      }
    } finally {
      ComponentsSystrace.endSection();
    }
  }

  final void unmount(
      final @Nullable ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    final boolean isTracing = ComponentsSystrace.isTracing();
    if (isTracing) {
      ComponentsSystrace.beginSection("unmount:" + getSimpleName());
    }
    try {
      onUnmount(c, mountedContent, interStagePropsContainer);
    } catch (Exception e) {
      if (c != null) {
        ComponentUtils.handle(c, e);
      }
    } finally {
      ComponentsSystrace.endSection();
    }
  }

  protected StateContainer createStateContainer() {
    throw new RuntimeException("createStateContainer has not been implemented!");
  }

  /**
   * @return the Component this Component should delegate its getSimpleName calls to. See {@link
   *     LayoutSpec#simpleNameDelegate()}
   */
  protected @Nullable Component getSimpleNameDelegate() {
    return null;
  }

  private static Component getFirstNonSimpleNameDelegate(Component component) {
    Component current = component;
    while (current instanceof SpecGeneratedComponent
        && ((SpecGeneratedComponent) current).getSimpleNameDelegate() != null) {
      current = ((SpecGeneratedComponent) current).getSimpleNameDelegate();
    }
    return current;
  }

  /**
   * @return Boolean indicating whether the component skips Incremental Mount. If this is true then
   *     the Component will not be involved in Incremental Mount.
   */
  protected boolean excludeFromIncrementalMount() {
    return false;
  }

  protected RenderResult<Component> render(
      ResolveContext resolveContext, ComponentContext c, int widthSpec, int heightSpec) {
    if (Component.isLayoutSpecWithSizeSpec(this)) {
      return new RenderResult<>(onCreateLayoutWithSizeSpec(c, widthSpec, heightSpec));
    } else {
      // NULLSAFE_FIXME[Parameter Not Nullable]
      return new RenderResult<>(onCreateLayout(c));
    }
  }

  @Override
  public ComponentResolveResult resolveDeferred(
      CalculationContext calculationContext,
      ComponentContext componentContext,
      ComponentContext parentContext) {
    final LithoNode node =
        new DeferredLithoNode(
            componentContext.getTreePropContainer(),
            calculationContext.getCache().getCachedNode(this),
            parentContext);
    return new ComponentResolveResult(node, getCommonProps());
  }

  @Override
  protected ComponentResolveResult resolve(
      final ResolveContext resolveContext,
      final ScopedComponentInfo scopedComponentInfo,
      final int parentWidthSpec,
      final int parentHeightSpec) {

    final ComponentContext c = scopedComponentInfo.getContext();
    final boolean isTracing = ComponentsSystrace.isTracing();
    LithoNode node = null;

    if (Component.isMountSpec(this)) {
      // Create a blank InternalNode for MountSpecs and set the default flex direction.
      node = new LithoNode();
      node.flexDirection(YogaFlexDirection.COLUMN);

      final Integer traceId = generateTraceIdentifier(LithoDebugEvent.ComponentRendered);
      if (traceId != null) {
        HashMap<String, Object> attributes = new HashMap<>(4);
        attributes.put(DebugEventAttribute.Name, getFullyQualifiedName());
        attributes.put(LithoDebugEventAttributes.ResolveVersion, resolveContext.getLayoutVersion());
        beginTrace(
            traceId,
            LithoDebugEvent.ComponentRendered,
            String.valueOf(resolveContext.getTreeId()),
            attributes);
      }

      if (isTracing) {
        ComponentsSystrace.beginSection("render:" + getSimpleName());
      }

      try {
        onPrepare(scopedComponentInfo.getContext());
      } finally {
        if (isTracing) {
          ComponentsSystrace.endSection();
        }
        if (traceId != null) {
          endTrace(traceId);
        }
      }
    }

    // If the component is a LayoutSpec.
    else if (Component.isLayoutSpec(this)) {

      final Integer traceId = generateTraceIdentifier(LithoDebugEvent.ComponentRendered);
      if (traceId != null) {
        HashMap<String, Object> attributes = new HashMap<>(4);
        attributes.put(DebugEventAttribute.Name, getFullyQualifiedName());
        attributes.put(LithoDebugEventAttributes.ResolveVersion, resolveContext.getLayoutVersion());
        beginTrace(
            traceId,
            LithoDebugEvent.ComponentRendered,
            String.valueOf(resolveContext.getTreeId()),
            attributes);
      }

      final RenderResult<Component> renderResult;

      if (isTracing) {
        ComponentsSystrace.beginSection("render:" + getSimpleName());
      }

      try {
        renderResult =
            ComponentRenderer.renderWithRecorder(
                this, resolveContext, scopedComponentInfo, parentWidthSpec, parentHeightSpec);
      } finally {
        if (isTracing) {
          ComponentsSystrace.endSection();
        }
        if (traceId != null) {
          endTrace(traceId);
        }
      }

      final Component root = renderResult.value;

      if (root != null) {
        node = Resolver.resolve(resolveContext, c, root);
      } else {
        node = new NullNode();
      }

      if (renderResult != null && node != null) {
        Resolver.applyTransitionsAndUseEffectEntriesToNode(
            renderResult.transitionData, renderResult.useEffectEntries, node);
      }
    }

    return new ComponentResolveResult(node, getCommonProps());
  }

  @Override
  public void recordEventTrigger(ComponentContext c, EventTriggersContainer container) {
    // Do nothing by default
  }

  /**
   * Generate a tree of {@link ComponentLayout} representing the layout structure of the {@link
   * Component} and its sub-components.
   *
   * @param c The {@link ComponentContext} to build a {@link ComponentLayout} tree.
   */
  @SuppressWarnings("deprecation") // default empty layout uses the legacy Column builder
  protected @Nullable Component onCreateLayout(ComponentContext c) {
    return Column.create(c).build();
  }

  @SuppressWarnings("deprecation") // default empty layout uses the legacy Column builder
  protected Component onCreateLayoutWithSizeSpec(
      ComponentContext c, int widthSpec, int heightSpec) {
    return Column.create(c).build();
  }

  protected void onPrepare(ComponentContext c) {
    // do nothing, by default
  }

  @Override
  protected boolean usesLocalStateContainer() {
    return true;
  }

  @Override
  @Nullable
  public final Object acceptTriggerEvent(
      EventTrigger<?> eventTrigger, Object eventState, Object[] params) {
    try {
      return acceptTriggerEventImpl(eventTrigger, eventState, params);
    } catch (Exception e) {
      if (eventTrigger.getComponentContext() != null) {
        ComponentUtils.handle(eventTrigger.getComponentContext(), e);
        return null;
      } else {
        throw e;
      }
    }
  }

  protected @Nullable Object acceptTriggerEventImpl(
      EventTrigger<?> eventTrigger, Object eventState, Object[] params) {
    // Do nothing by default
    return null;
  }

  protected void applyPreviousRenderData(@Nullable RenderData previousRenderData) {}

  protected void bindDynamicProp(int dynamicPropIndex, @Nullable Object value, Object content) {
    throw new RuntimeException("Components that have dynamic Props must override this method");
  }

  // This will not be needed anymore for stateless components.
  protected void copyInterStageImpl(
      final @Nullable InterStagePropsContainer copyIntoInterStagePropsContainer,
      final @Nullable InterStagePropsContainer copyFromInterStagePropsContainer) {}

  // This will not be needed anymore for stateless components.
  protected void copyPrepareInterStageImpl(
      final @Nullable PrepareInterStagePropsContainer copyIntoInterStagePropsContainer,
      final @Nullable PrepareInterStagePropsContainer copyFromInterStagePropsContainer) {}

  protected void createInitialState(ComponentContext c, StateContainer stateContainer) {}

  StateContainer createInitialStateContainer(ComponentContext c) {
    StateContainer stateContainer = createStateContainer();
    createInitialState(c, stateContainer);
    return stateContainer;
  }

  protected @Nullable InterStagePropsContainer createInterStagePropsContainer() {
    return null;
  }

  protected @Nullable PrepareInterStagePropsContainer createPrepareInterStagePropsContainer() {
    return null;
  }

  final void loadStyle(ComponentContext c, @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
    c.setDefStyle(defStyleAttr, defStyleRes);
    onLoadStyle(c);
    c.setDefStyle(0, 0);
  }

  final void loadStyle(ComponentContext c) {
    onLoadStyle(c);
  }

  protected void onLoadStyle(ComponentContext c) {}

  protected void dispatchOnEnteredRange(
      final ComponentContext c,
      final String name,
      final @Nullable InterStagePropsContainer interStageProps) {
    // Do nothing by default
  }

  protected void dispatchOnExitedRange(
      final ComponentContext c,
      final String name,
      final @Nullable InterStagePropsContainer interStageProps) {
    // Do nothing by default
  }

  /**
   * @return a {@link TransitionSet} specifying how to animate this component to its new layout and
   *     props.
   */
  protected @Nullable Transition onCreateTransition(ComponentContext c) {
    return null;
  }

  final @Nullable Transition createTransition(ComponentContext c) {
    final Transition transition = onCreateTransition(c);
    if (transition != null) {
      TransitionUtils.setOwnerKey(transition, c.getNullableGlobalKey());
    }
    return transition;
  }

  /**
   * Populate an accessibility node with information about the component.
   *
   * @param accessibilityNode node to populate
   */
  protected void onPopulateAccessibilityNode(
      final @Nullable ComponentContext c,
      final View host,
      final AccessibilityNodeInfoCompat accessibilityNode,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {}

  /**
   * Respond to an attempt to perform an AccessibilityAction on a virtual view.
   *
   * @param host host View containing the virtual view
   * @param accessibilityNode node to populate
   * @param virtualViewId ID of virtual view
   * @param action ID of AccessibilityAction performed
   * @param arguments a Bundle of arguments passed along with the AccessibilityAction
   * @param interStagePropsContainer
   */
  protected boolean onPerformActionForVirtualView(
      final ComponentContext c,
      final View host,
      final AccessibilityNodeInfoCompat accessibilityNode,
      final int virtualViewId,
      final int action,
      final @Nullable Bundle arguments,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    return false;
  }

  /**
   * Respond to an attempt to perform an AccessibilityAction on a virtual view.
   *
   * @param host host View containing the virtual view
   * @param accessibilityNode node to populate
   * @param virtualViewId ID of virtual view
   * @param hasFocus whether ther virtual view has focus
   * @param interStagePropsContainer
   */
  protected void onVirtualViewKeyboardFocusChanged(
      final ComponentContext c,
      final View host,
      final @Nullable AccessibilityNodeInfoCompat accessibilityNode,
      final int virtualViewId,
      final boolean hasFocus,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {}

  /**
   * Populate an extra accessibility node.
   *
   * @param accessibilityNode node to populate
   * @param extraNodeIndex index of extra node
   * @param componentBoundsX left bound of the mounted component
   * @param componentBoundsY top bound of the mounted component
   * @param interStagePropsContainer
   */
  protected void onPopulateExtraAccessibilityNode(
      final @Nullable ComponentContext c,
      final AccessibilityNodeInfoCompat accessibilityNode,
      final int extraNodeIndex,
      final int componentBoundsX,
      final int componentBoundsY,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {}

  /**
   * Get extra accessibility node id at a given point within the component.
   *
   * @param x x co-ordinate within the mounted component
   * @param y y co-ordinate within the mounted component
   * @return the extra virtual view id if one is found, otherwise {@code
   *     ExploreByTouchHelper#INVALID_ID}
   */
  protected int getExtraAccessibilityNodeAt(
      final @Nullable ComponentContext c,
      final int x,
      final int y,
      final @Nullable InterStagePropsContainer InterStagePropsContainer) {
    return ExploreByTouchHelper.INVALID_ID;
  }

  /**
   * The number of extra accessibility nodes that this component wishes to provides to the
   * accessibility system.
   *
   * @return the number of extra nodes
   */
  protected int getExtraAccessibilityNodesCount(
      final @Nullable ComponentContext c,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    return 0;
  }

  protected final @Nullable InterStagePropsContainer getInterStagePropsContainer(
      final ComponentContext scopedContext,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    return interStagePropsContainer;
  }

  protected final @Nullable PrepareInterStagePropsContainer getPrepareInterStagePropsContainer(
      final ComponentContext scopedContext) {
    return scopedContext.getScopedComponentInfo().getPrepareInterStagePropsContainer();
  }

  /** Updates the TreeProps map with outputs from all {@link OnCreateTreeProp} methods. */
  protected @Nullable TreePropContainer getTreePropContainerForChildren(
      ComponentContext c, @Nullable TreePropContainer treePropContainer) {
    return treePropContainer;
  }

  /**
   * @return true if the component implements {@link OnAttached} or {@link OnDetached} delegate
   *     methods.
   */
  protected boolean hasAttachDetachCallback() {
    return false;
  }

  /**
   * This method is overridden in the generated component to return true if and only if the
   * Component Spec has an OnError lifecycle callback.
   */
  protected boolean hasOwnErrorHandler() {
    return false;
  }

  /**
   * @return true if the Component is using state, false otherwise.
   */
  protected boolean hasState() {
    return false;
  }

  /**
   * Whether this {@link com.facebook.litho.Component} mounts views that contain component-based
   * content that can be incrementally mounted e.g. if the mounted view has a LithoView with
   * incremental mount enabled.
   */
  protected boolean hasChildLithoViews() {
    return false;
  }

  /**
   * Whether this component will populate any accessibility nodes or events that are passed to it.
   *
   * @return true if the component implements accessibility info
   */
  protected boolean implementsAccessibility() {
    return false;
  }

  /**
   * Whether this component will expose any virtual views to the accessibility framework
   *
   * @return true if the component exposes extra accessibility nodes
   */
  protected boolean implementsExtraAccessibilityNodes() {
    return false;
  }

  /**
   * Whether this component will attempt to perform any AccessibilityActions on any virtual views
   *
   * @return true if the component implements {@link OnPerformActionForVirtualView} annotated method
   */
  protected boolean implementsOnPerformActionForVirtualView() {
    return false;
  }

  /**
   * Whether this component will attempt to change keyboard focus behavior for virtual views
   *
   * @return true if the component implements {@link OnVirtualViewKeyboardFocusChanged} annotated
   *     method
   */
  protected boolean implementsKeyboardFocusChangeForVirtualViews() {
    return false;
  }

  /**
   * @return true if Mount uses @FromMeasure or @FromOnBoundsDefined parameters.
   */
  protected boolean isMountSizeDependent() {
    return false;
  }

  protected boolean needsPreviousRenderData() {
    return false;
  }

  /**
   * Called when the component is attached to the {@link ComponentTree}.
   *
   * @param c The {@link ComponentContext} the Component was constructed with.
   */
  protected void onAttached(ComponentContext c) {}

  protected void onBind(
      final @Nullable ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  /**
   * Called after the layout calculation is finished and the given {@link ComponentLayout} has its
   * bounds defined. You can use {@link ComponentLayout#getX()}, {@link ComponentLayout#getY()},
   * {@link ComponentLayout#getWidth()}, and {@link ComponentLayout#getHeight()} to get the size and
   * position of the component in the layout tree.
   *
   * @param c The {@link Context} used by this component.
   * @param layout The {@link ComponentLayout} with defined position and size.
   */
  protected void onBoundsDefined(
      final ComponentContext c,
      final ComponentLayout layout,
      @Nullable InterStagePropsContainer interStagePropsContainer) {}

  /**
   * Called when the component is detached from the {@link ComponentTree}.
   *
   * @param c The {@link ComponentContext} the Component was constructed with.
   */
  protected void onDetached(ComponentContext c) {}

  /**
   * Called to provide a fallback if a supported lifecycle method throws an exception. It is
   * possible to either recover from the error here or reraise the exception to catch it at a higher
   * level or crash the application.
   *
   * @param c The {@link ComponentContext} the Component was constructed with.
   * @param e The exception caught.
   * @see com.facebook.litho.annotations.OnError
   */
  protected void onError(ComponentContext c, Exception e) {
    EventHandler<ErrorEvent> eventHandler = c.getErrorEventHandler();
    if (eventHandler == null) {
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else {
        throw new RuntimeException(e);
      }
    }
    ErrorEvent errorEvent = new ErrorEvent();
    errorEvent.exception = e;
    errorEvent.componentContext = c;
    eventHandler.dispatchEvent(errorEvent);
  }

  protected void onMeasure(
      final ComponentContext c,
      final ComponentLayout layout,
      final int widthSpec,
      final int heightSpec,
      final Size size,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    throw new IllegalStateException(
        "You must override onMeasure() if you return true in canMeasure(), "
            + "Component is: "
            + this);
  }

  /**
   * Called during layout calculation to determine the baseline of a component.
   *
   * @param c The {@link Context} used by this component.
   * @param width The width of this component.
   * @param height The height of this component.
   * @param interStagePropsContainer
   */
  protected int onMeasureBaseline(
      final ComponentContext c,
      final int width,
      final int height,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    return height;
  }

  /** Min-content intrinsic width; generated from {@code @OnMeasureIntrinsicWidth}. */
  protected int onMeasureIntrinsicWidth(
      final ComponentContext c,
      final ComponentLayout layout,
      final int widthSpec,
      final int heightSpec) {
    throw new IllegalStateException(
        getSimpleName()
            + " declares hasIntrinsicSize = true but does not implement @OnMeasureIntrinsicWidth. "
            + "A MountSpec opting into intrinsic sizing must implement both "
            + "@OnMeasureIntrinsicWidth and @OnMeasureIntrinsicHeight.");
  }

  /** Min-content intrinsic height; generated from {@code @OnMeasureIntrinsicHeight}. */
  protected int onMeasureIntrinsicHeight(
      final ComponentContext c,
      final ComponentLayout layout,
      final int widthSpec,
      final int heightSpec) {
    throw new IllegalStateException(
        getSimpleName()
            + " declares hasIntrinsicSize = true but does not implement @OnMeasureIntrinsicHeight. "
            + "A MountSpec opting into intrinsic sizing must implement both "
            + "@OnMeasureIntrinsicWidth and @OnMeasureIntrinsicHeight.");
  }

  /**
   * Deploy all UI elements representing the final bounds defined in the given {@link
   * ComponentLayout}. Return either a {@link Drawable} or a {@link View} or {@code null} to be
   * mounted.
   *
   * @param c The {@link ComponentContext} to mount the component into.
   */
  protected void onMount(
      final @Nullable ComponentContext c,
      final Object convertContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  protected void onUnbind(
      final @Nullable ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  /**
   * Unload UI elements associated with this component.
   *
   * @param c The {@link Context} for this mount operation.
   * @param mountedContent The {@link Drawable} or {@link View} mounted by this component.
   * @param interStagePropsContainer
   */
  protected void onUnmount(
      final @Nullable ComponentContext c,
      final Object mountedContent,
      final @Nullable InterStagePropsContainer interStagePropsContainer) {
    // Do nothing by default.
  }

  /**
   * Retrieves all of the tree props used by this Component from the TreeProps map and sets the tree
   * props as fields on the ComponentImpl.
   */
  protected void populateTreePropContainer(@Nullable TreePropContainer parentTreePropContainer) {}

  protected @Nullable RenderData recordRenderData(
      ComponentContext c, @Nullable RenderData toRecycle) {
    return null;
  }

  protected DynamicValue<?>[] getDynamicProps() {
    return sEmptyArray;
  }

  /**
   * @return true if the Component should always be measured when receiving a remeasure event, false
   *     otherwise.
   */
  protected boolean shouldAlwaysRemeasure() {
    return false;
  }

  @Override
  public Object createContent(Context context) {
    final Object content = createMountContent(context);
    if (content == null) {
      throw new RuntimeException(
          "Component created null mount content, but mount content must never be null! Component: "
              + getSimpleName());
    }
    return content;
  }

  @Override
  public RenderType getRenderType() {
    return getMountType() == Component.MountType.DRAWABLE ? RenderType.DRAWABLE : RenderType.VIEW;
  }

  @Override
  public Object getPoolKey() {
    return getClass();
  }

  /**
   * @return true if this component can be preallocated.
   */
  @Override
  public boolean canPreallocate() {
    return false;
  }

  /**
   * @return the MountContentPool that should be used to recycle mount content for this mount spec.
   */
  @Override
  public MountContentPools.ContentPool onCreateMountContentPool(int poolSizeOverride) {
    return new MountContentPools.DefaultContentPool(getPoolKey(), poolSizeOverride);
  }

  @ThreadSafe
  @Override
  public int poolSize() {
    return DEFAULT_MAX_PREALLOCATION;
  }

  @Nullable
  public final CommonProps getCommonProps() {
    return mCommonProps;
  }

  final CommonProps getOrCreateCommonProps() {
    if (mCommonProps == null) {
      mCommonProps = new CommonProps();
    }

    return mCommonProps;
  }

  /**
   * @return {@link SparseArray} that holds common dynamic Props
   * @see DynamicPropsManager
   */
  @Nullable
  SparseArray<DynamicValue<?>> getCommonDynamicProps() {
    if (mCommonProps == null) {
      return null;
    }
    return mCommonProps.getOrCreateDynamicProps();
  }

  /**
   * @return {@link SparseArray} that holds common dynamic Props, initializing it beforehand if
   *     needed
   * @see DynamicPropsManager
   */
  final SparseArray<DynamicValue<?>> getOrCreateCommonDynamicProps() {
    return getOrCreateCommonProps().getOrCreateDynamicProps();
  }

  /**
   * @return true if component has common dynamic props, false - otherwise. If so {@link
   *     #getCommonDynamicProps()} will return not null value
   * @see DynamicPropsManager
   */
  boolean hasCommonDynamicProps() {
    if (mCommonProps == null) {
      return false;
    }
    return mCommonProps.hasCommonDynamicProps();
  }

  @Override
  public final boolean isEquivalentTo(@Nullable Component other) {
    if (other instanceof SpecGeneratedComponent
        && !isEquivalentCommonProps((SpecGeneratedComponent) other)) {
      return false;
    }
    if (Component.isEfficientEqualityConfigEnabled(this)) {
      @Nullable Boolean isEquivalent = isEquivalentPropsOptimized(this, other);
      if (isEquivalent != null) {
        return isEquivalent;
      }
    }
    return isEquivalentProps(other);
  }

  /**
   * Uses the generated {@link Component#getProps()} method to compare the props of two components.
   *
   * @param a the first component to compare
   * @param b the other component to compare
   * @return true if the props are equivalent, false otherwise. This method may also return null if
   *     {@link Component#getProps()} has not been implemented.
   */
  @Nullable
  protected static Boolean isEquivalentPropsOptimized(
      @Nullable Component a, @Nullable Component b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    if (a.getClass() != b.getClass()) {
      return false;
    }
    if (a.getInstanceId() == b.getInstanceId()) {
      return true;
    }
    @Nullable Object[] aProps = a.getProps();
    @Nullable Object[] bProps = b.getProps();
    if (aProps == null || bProps == null) {
      return null;
    }
    return ComponentUtils.arePropsEquivalent(aProps, bProps);
  }

  protected final boolean isEquivalentCommonProps(@Nullable SpecGeneratedComponent other) {
    if (other == null) {
      return false;
    }

    return (mCommonProps == null && other.mCommonProps == null)
        || (mCommonProps != null && mCommonProps.isEquivalentTo(other.mCommonProps));
  }

  public final boolean hasClickHandlerSet() {
    return mCommonProps != null
        && mCommonProps.getNodeInfo() != null
        && mCommonProps.getNodeInfo().getClickHandler() != null;
  }

  @Override
  public final @Nullable Object dispatchOnEvent(EventHandler<?> eventHandler, Object eventState) {
    boolean isTracing = ComponentsSystrace.isTracing();

    // We don't want to wrap and throw error events
    if (eventHandler.id == ERROR_EVENT_HANDLER_ID) {
      if (isTracing) {
        ComponentsSystrace.beginSection(
            "onError:" + getSimpleName() + ":" + getSectionNameForTracing(eventState.getClass()));
      }
      try {
        return dispatchOnEventImpl(eventHandler, eventState);
      } finally {
        if (isTracing) {
          ComponentsSystrace.endSection();
        }
      }
    }

    final Object token = EventDispatcherInstrumenter.onBeginWork(eventHandler, eventState);
    if (isTracing) {
      ComponentsSystrace.beginSection(
          "onEvent:" + getSimpleName() + ":" + getSectionNameForTracing(eventState.getClass()));
    }
    try {
      return dispatchOnEventImpl(eventHandler, eventState);
    } catch (Exception e) {
      if (eventHandler.dispatchInfo.componentContext != null) {
        ComponentUtils.handle(eventHandler.dispatchInfo.componentContext, e);
        return null;
      } else {
        throw e;
      }
    } finally {
      EventDispatcherInstrumenter.onEndWork(token);
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }
  }

  protected @Nullable Object dispatchOnEventImpl(EventHandler<?> eventHandler, Object eventState) {
    if (eventHandler.id == ERROR_EVENT_HANDLER_ID) {
      Preconditions.checkNotNull(
              getErrorHandler(
                  Preconditions.checkNotNull(eventHandler.dispatchInfo.componentContext)))
          .dispatchEvent((ErrorEvent) eventState);
    }

    // Don't do anything by default, unless we're handling an error.
    return null;
  }

  @Deprecated
  @Override
  public final EventDispatcher getEventDispatcher() {
    return this;
  }

  /**
   * Generated component's state container could implement this interface along with {@link
   * StateContainer} when componentspec specifies state update method with {@link
   * com.facebook.litho.annotations.OnUpdateStateWithTransition} annotation.
   */
  public interface TransitionContainer {

    /**
     * Applies a state update and optionally returns a transition. This is meant as a replacement to
     * the standard applyStateUpdate method on StateContainer.
     */
    @Nullable
    Transition applyStateUpdateWithTransition(StateContainer.StateUpdate stateUpdate);
  }
}
