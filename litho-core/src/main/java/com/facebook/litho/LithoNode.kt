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

package com.facebook.litho

import android.animation.StateListAnimator
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.Px
import androidx.annotation.StyleRes
import androidx.collection.ScatterSet
import androidx.collection.emptyScatterSet
import androidx.collection.mutableScatterSetOf
import androidx.core.view.ViewCompat
import com.facebook.infer.annotation.ThreadConfined
import com.facebook.litho.CommonProps.DefaultLayoutProps
import com.facebook.litho.ComponentHostUtils.maybeSetDrawableState
import com.facebook.litho.LithoLayoutData.Companion.NO_Z_INDEX
import com.facebook.litho.Transition.TransitionKeyType
import com.facebook.litho.animation.StyleTransitionData
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.ImportantForAccessibility
import com.facebook.litho.config.LithoDebugConfigurations
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.drawable.ComparableColorDrawable
import com.facebook.litho.layout.LayoutDirection
import com.facebook.litho.layout.LayoutDirection.Companion.INHERIT
import com.facebook.litho.layout.LayoutDirection.Companion.isNullOrInherit
import com.facebook.litho.layout.LayoutFunction
import com.facebook.litho.state.StateId
import com.facebook.litho.state.StateReadRecorder
import com.facebook.litho.transition.MutableTransitionData
import com.facebook.litho.transition.TransitionData
import com.facebook.rendercore.BinderKey
import com.facebook.rendercore.FastMath
import com.facebook.rendercore.LayoutContext
import com.facebook.rendercore.Node
import com.facebook.rendercore.RenderType
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.RenderUnit.Binder
import com.facebook.rendercore.RenderUnit.DelegateBinder
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.Primitive
import com.facebook.rendercore.visibility.FocusedVisibleEvent
import com.facebook.rendercore.visibility.FullImpressionVisibleEvent
import com.facebook.rendercore.visibility.InvisibleEvent
import com.facebook.rendercore.visibility.UnfocusedVisibleEvent
import com.facebook.rendercore.visibility.VisibilityChangedEvent
import com.facebook.rendercore.visibility.VisibleEvent
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaConstants
import com.facebook.yoga.YogaEdge
import com.facebook.yoga.YogaFlexDirection
import com.facebook.yoga.YogaGutter
import com.facebook.yoga.YogaJustify
import com.facebook.yoga.YogaMeasureFunction
import com.facebook.yoga.YogaWrap
import java.util.concurrent.atomic.AtomicInteger

/** [LithoNode] is the [Node] implementation of Litho. */
@ThreadConfined(ThreadConfined.ANY)
open class LithoNode : Node<LithoLayoutContext>, Cloneable {

  // region private properties
  private var _transitionId: TransitionId? = null
  private var _attachables: MutableList<Attachable>? = null
  private var _resolveEffects: MutableList<Attachable>? = null
  private var _discardKeys: MutableList<Any>? = null
  private var _unresolvedComponents: MutableList<Component>? = null
  private var frozen: Boolean = false
  private var nodeInfoWasWritten: Boolean = false
  // endregion

  // region Properties
  var paddingFromBackground: Rect? = null
  var primitive: Primitive? = null

  // Set by PrimitiveComponent.resolve when scope.shouldExcludeFromIncrementalMount is true.
  // Read by LithoNodeUtils when constructing PrimitiveLithoRenderUnit so the unit doesn't
  // have to walk the binder map to recover this boolean per construction.
  internal var primitiveExcludesFromIncrementalMount: Boolean = false

  internal var id: Int = idGenerator.getAndIncrement()
  internal var children: MutableList<LithoNode> = ArrayList(4)
  internal var _layoutDirection: LayoutDirection? = null
  internal var justifyContent: YogaJustify? = null
  internal var alignContent: YogaAlign? = null
  internal var alignItems: YogaAlign? = null
  internal var flexDirection: YogaFlexDirection? = null
  internal var yogaWrap: YogaWrap? = null
  internal var columnGapPx: Int = 0
  internal var rowGapPx: Int = 0
  internal var allGapPx: Int = 0
  internal var yogaMeasureFunction: YogaMeasureFunction? = null
  internal var yogaMinContentMeasureFunction: YogaMeasureFunction? = null
  internal var deferredNode: DeferredLithoNode? = null

  internal var _needsHostView: Boolean = false
  internal var paddingsFromDeferredNode: Edges? = null
  internal var paddingIsPercentFromDefferedNode: BooleanArray? = null
  internal var privateFlags: Long = 0
  internal var debugLayoutProps: DefaultLayoutProps? = null

  internal val borderEdgeWidths: IntArray = IntArray(Border.EDGE_COUNT)
  val borderColors: IntArray = IntArray(Border.EDGE_COUNT)
  val borderRadius: FloatArray = FloatArray(Border.RADIUS_COUNT)

  val childCount: Int
    get() = children.size

  val attachables: List<Attachable>?
    get() = _attachables

  /** The [useResolveEffect]s of the renders whose output is this node. */
  internal val resolveEffects: List<Attachable>?
    get() = _resolveEffects

  /** The keys of the [useOnDiscard] callbacks of the renders whose output is this node. */
  internal val discardKeys: List<Any>?
    get() = _discardKeys

  val debugLayoutEditor: LayoutProps?
    get() {
      if (LithoDebugConfigurations.isDebugModeEnabled && debugLayoutProps == null) {
        debugLayoutProps = DefaultLayoutProps()
      }
      return debugLayoutProps
    }

  val transitionGlobalKey: Any
    get() = tailComponentKey

  val transitionId: TransitionId?
    get() {
      if (_transitionId == null) {
        LithoNodeUtils.createTransitionId(this)
      }
      return _transitionId
    }

  val unresolvedComponents: MutableList<Component>?
    get() = _unresolvedComponents

  val isImportantForAccessibilityIsSet: Boolean
    get() =
        (privateFlags and PFLAG_IMPORTANT_FOR_ACCESSIBILITY_IS_SET == 0L ||
            importantForAccessibility == ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO)

  var nodeInfo: NodeInfo? = null
    internal set

  var onVisible: EventHandler<VisibleEvent>? = null
    internal set

  var onFocusedVisible: EventHandler<FocusedVisibleEvent>? = null
    internal set

  var onUnfocusedVisible: EventHandler<UnfocusedVisibleEvent>? = null
    internal set

  var onFullImpression: EventHandler<FullImpressionVisibleEvent>? = null
    internal set

  var onInvisible: EventHandler<InvisibleEvent>? = null
    internal set

  var onVisibilityChanged: EventHandler<VisibilityChangedEvent>? = null
    internal set

  var background: Drawable? = null
    internal set

  var foreground: Drawable? = null
    internal set

  var borderPathEffect: PathEffect? = null
    internal set

  var stateListAnimator: StateListAnimator? = null
    internal set

  var transitionKey: String? = null
    internal set

  var transitionOwnerKey: Any? = null
    internal set

  var transitionKeyType: TransitionKeyType? = null
    internal set

  var layerPaint: Paint? = null
    internal set

  var isPaddingSet: Boolean = false
    internal set

  var isDuplicateParentStateEnabled: Boolean = false
    internal set

  var isHostDuplicateParentStateEnabled: Boolean = false
    internal set

  var isDuplicateChildrenStatesEnabled: Boolean = false
    internal set

  var isForceViewWrapping: Boolean = false
    internal set

  var layerType: Int = LayerType.LAYER_TYPE_NOT_SET
    internal set

  var visibilityOutputTag: String? = null
    internal set

  var importantForAccessibility: Int = ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    internal set

  @DrawableRes
  var stateListAnimatorRes: Int = 0
    internal set

  var zIndex: Int? = NO_Z_INDEX
    internal set

  var visibleHeightRatio: Float = 0f
    internal set

  var visibleWidthRatio: Float = 0f
    internal set

  /**
   * Returns a nullable map of [RenderUnit.DelegateBinder] that is aimed to be used to set the
   * optional mount binders right after creating a [MountSpecLithoRenderUnit].
   */
  var customViewBindersForMountSpec: MutableMap<BinderKey, DelegateBinder<Any, Any, Any>>? = null
    internal set

  /**
   * Returns a nullable map of [RenderUnit.DelegateBinder] that is aimed to be used to set the
   * optional mount binders on the Host View of this [LithoNode].
   *
   * @see LithoNodeUtils.createHostRenderUnit
   */
  var customHostViewBinders: MutableMap<BinderKey, DelegateBinder<Any, Any, Any>>? = null
    internal set

  var customBindersForMountSpec: MutableMap<BinderKey, DelegateBinder<Any, Any, Any>>? = null
    internal set

  /**
   * A unique identifier which may be set for retrieving a component and its bounds when testing.
   */
  var testKey: String? = null
    internal set

  var willMountView: Boolean = false
    private set

  var touchExpansion: Edges? = null
    private set

  private var _systemGestureExclusionZones: MutableList<(Rect) -> Rect>? = null
  val systemGestureExclusionZones: List<(Rect) -> Rect>?
    get() = _systemGestureExclusionZones

  var isClone: Boolean = false
    private set

  internal var transitionData: MutableTransitionData? = null
    private set

  internal var styleTransitionData: StyleTransitionData? = null
    private set

  private var _workingRangeRegistrations: MutableList<WorkingRangeContainer.Registration>? = null
  val workingRangeRegistrations: MutableList<WorkingRangeContainer.Registration>?
    get() = _workingRangeRegistrations

  @ThreadConfined(ThreadConfined.ANY)
  private val _scopedComponentInfos: MutableList<ScopedComponentInfo> = ArrayList(2)
  val simpleName: String
    get() = if (_scopedComponentInfos.isEmpty()) "<null>" else getComponentAt(0).simpleName

  val componentCount: Int
    get() = _scopedComponentInfos.size

  val scopedComponentInfos: List<ScopedComponentInfo>
    get() = _scopedComponentInfos

  val headComponent: Component
    get() = _scopedComponentInfos[_scopedComponentInfos.size - 1].component

  val headComponentKey: GlobalKey
    get() = _scopedComponentInfos[_scopedComponentInfos.size - 1].context.globalKey

  val headComponentContext: ComponentContext
    get() = _scopedComponentInfos[_scopedComponentInfos.size - 1].context

  val tailComponent: Component
    get() = _scopedComponentInfos[0].component

  val tailComponentKey: GlobalKey
    get() = _scopedComponentInfos[0].context.globalKey

  val tailComponentContext: ComponentContext
    get() = _scopedComponentInfos[0].context

  val tailScopedComponentInfo: ScopedComponentInfo
    get() = _scopedComponentInfos[0]

  // endregion

  /**
   * Replays this node's layout props into [props], in the order a writer for its node receives
   * them: styled attributes, background padding, each component's common props, border, deferred
   * padding and debug overrides. Yoga's writer and a `Layout`'s box-props recorder both read a
   * node's props through it. It writes only to [props], so a node can be replayed into any number
   * of sinks. It returns the state the components' props read.
   */
  internal fun copyLayoutPropsInto(
      context: LayoutContext<LithoLayoutContext>,
      props: LayoutProps,
  ): ScatterSet<StateId> {
    var stateReads: ScatterSet<StateId> = emptyScatterSet()
    var wasDeferredBackgroundPaddingWritten = false
    for (info in scopedComponentInfos) {
      val component: Component = info.component

      // The size-spec component's own props went to its holder, which handed its node props to
      // this node when it was resolved (LayoutUtils.measureDeferredNode). Only the background
      // padding it handed over is written here, at this point in the order.
      if (deferredNode != null && Component.isLayoutSpecWithSizeSpec(component)) {
        if (wasDeferredBackgroundPaddingWritten) {
          continue
        }
        wasDeferredBackgroundPaddingWritten = true
        paddingFromBackground?.let { props.setPaddingFromRect(it) }
      } else {
        info.commonProps?.let { commonProps ->
          commonProps.writeStyledAttributesToLayoutProps(tailComponentContext.androidContext, props)
          commonProps.paddingFromBackground?.let { padding ->
            props.setPaddingFromRect(padding)
          }
          val reads =
              StateReadRecorder.record(
                  requireNotNull(context.renderContext).treeId,
                  attribution("layout", component),
              ) {
                commonProps.copyLayoutProps(props, tailComponentContext.resourceResolver)
              }
          if (reads.isNotEmpty()) {
            stateReads =
                if (stateReads.isEmpty()) reads
                else
                    mutableScatterSetOf<StateId>().also { merged ->
                      merged += stateReads
                      merged += reads
                    }
          }
        }
      }
    }

    applyBorderWidth { yogaEdge, width -> props.setBorderWidth(yogaEdge, width) }

    // Maybe apply the padding if parent is a Deferred Node
    applyDeferredPadding(
        { yogaEdge, paddingPx -> props.paddingPx(yogaEdge, paddingPx) },
        { yogaEdge, paddingPercent -> props.paddingPercent(yogaEdge, paddingPercent) },
    )

    debugLayoutProps?.copyInto(props)
    return stateReads
  }

  /**
   * The layout function of a `Layout`, which measures and places this node's children; null for any
   * other node, whose children flexbox lays out.
   */
  @OptIn(ExperimentalLithoApi::class) internal var layoutFunction: LayoutFunction? = null

  /** The layout function this node lays itself and its subtree out with. */
  internal open val customLayoutFunction: CustomLayoutFunction
    get() =
        if (layoutFunction != null) CustomLayoutFunction.Container else CustomLayoutFunction.Yoga

  override fun calculateLayout(
      context: LayoutContext<LithoLayoutContext>,
      sizeConstraints: SizeConstraints,
  ): LithoLayoutResult {

    val renderContext: LithoLayoutContext? = context.renderContext
    checkNotNull(renderContext) { "Cannot calculate a layout without RenderContext." }
    check(!renderContext.isReleased) {
      "Cannot calculate a layout with a released LayoutStateContext."
    }

    return customLayoutFunction.calculateLayout(context, this, sizeConstraints)
  }

  public override fun clone(): LithoNode {
    val node: LithoNode
    try {
      node = super.clone() as LithoNode
      node.isClone = true
      node.id = id
    } catch (e: CloneNotSupportedException) {
      throw RuntimeException(e)
    }
    return node
  }

  open fun createLayoutResult(
      measureOutput: LithoMeasureOutput,
  ): LithoLayoutResult = LithoLayoutResult(
      context = tailComponentContext,
      node = this,
      measureOutput = measureOutput,
  )

  open fun border(widths: IntArray, colors: IntArray, radii: FloatArray, effect: PathEffect?) {
    privateFlags = privateFlags or PFLAG_BORDER_IS_SET
    System.arraycopy(widths, 0, borderEdgeWidths, 0, borderEdgeWidths.size)
    System.arraycopy(colors, 0, borderColors, 0, borderColors.size)
    System.arraycopy(radii, 0, borderRadius, 0, borderRadius.size)
    borderPathEffect = effect
  }

  open fun background(background: Drawable? = null) {
    privateFlags = privateFlags or PFLAG_BACKGROUND_IS_SET
    this.background = background
  }

  open fun backgroundColor(@ColorInt backgroundColor: Int) {
    background(ComparableColorDrawable.create(backgroundColor))
  }

  open fun backgroundRes(context: Context, @DrawableRes resId: Int) {
    if (resId == 0) {
      background()
    } else {
      background(tailComponentContext.resourceResolver.resolveDrawableRes(resId))
    }
  }

  open fun border(border: Border) {
    border(border.edgeWidths, border.edgeColors, border.radius, border.pathEffect)
  }

  open fun foreground(foreground: Drawable? = null) {
    privateFlags = privateFlags or PFLAG_FOREGROUND_IS_SET
    this.foreground = foreground
  }

  open fun foregroundColor(@ColorInt foregroundColor: Int) {
    foreground(ComparableColorDrawable.create(foregroundColor))
  }

  open fun foregroundRes(context: Context, @DrawableRes resId: Int) {
    if (resId == 0) {
      foreground()
    } else {
      foreground(tailComponentContext.resourceResolver.resolveDrawableRes(resId))
    }
  }

  open fun importantForAccessibility(importantForAccessibility: Int): LithoNode {
    privateFlags = privateFlags or PFLAG_IMPORTANT_FOR_ACCESSIBILITY_IS_SET
    this.importantForAccessibility = importantForAccessibility
    return this
  }

  open fun layoutDirection(direction: LayoutDirection) {
    privateFlags = privateFlags or PFLAG_LAYOUT_DIRECTION_IS_SET
    _layoutDirection = direction
  }

  val layoutDirection: LayoutDirection
    get() = checkNotNull(_layoutDirection)

  open fun wrapInView() {
    isForceViewWrapping = true
  }

  fun applyParentDependentCommonProps(
      context: CalculationContext,
      parentLayoutDirection: LayoutDirection,
      parentImportantForAccessibility: Int =
          ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
      parentEnabledState: Int = NodeInfo.ENABLED_UNSET,
      parentDuplicatesParentState: Boolean = true,
  ) {
    if (frozen) {
      return
    }

    val isRoot: Boolean = (context.rootComponentId == headComponent.instanceId)

    if (!isRoot) { // if not root component

      // If parents important for A11Y is YES_HIDE_DESCENDANTS then
      // child's important for A11Y needs to be NO_HIDE_DESCENDANTS
      @Suppress("DEPRECATION")
      if (
          parentImportantForAccessibility ==
              ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_YES_HIDE_DESCENDANTS
      ) {
        importantForAccessibility(
            ImportantForAccessibility.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        )
      }

      // If the parent of this node is disabled, this node has to be disabled too.
      if (parentEnabledState == NodeInfo.ENABLED_SET_FALSE) {
        mutableNodeInfo().setEnabled(false)
      }
    }

    isHostDuplicateParentStateEnabled = isDuplicateParentStateEnabled
    _layoutDirection =
        if (_layoutDirection.isNullOrInherit()) {
          parentLayoutDirection
        } else {
          _layoutDirection ?: INHERIT
        }
    _needsHostView = needsHostView(this)

    // We need to take into account flattening when setting duplicate parent state. The parent after
    // flattening may no longer exist. Therefore the value of duplicate parent state should only be
    // true if the path between us (inclusive) and our inner/root host (exclusive) all are
    // duplicate parent state.

    val shouldDuplicateParentState: Boolean =
        _needsHostView || isRoot || (parentDuplicatesParentState && isDuplicateParentStateEnabled)

    duplicateParentState(shouldDuplicateParentState)

    _transitionId = LithoNodeUtils.createTransitionId(this)

    for (i in 0 until childCount) {
      getChildAt(i)
          .applyParentDependentCommonProps(
              context = context,
              parentLayoutDirection = layoutDirection,
              parentImportantForAccessibility = importantForAccessibility,
              parentEnabledState = (nodeInfo?.enabledState ?: NodeInfo.ENABLED_UNSET),
              parentDuplicatesParentState = isDuplicateParentStateEnabled,
          )
    }

    addViewAttributesBinderIfForPrimitive()
    addRefreshDrawableStateBinder()

    // Sets mFrozen as true to avoid anymore mutation.
    frozen = true
  }

  /**
   * This method will setup the [ViewAttributesViewBinder] if this node wraps a [Primitive]. This
   * should be called after all the node properties were set, so that we have all the available
   * information to create the view attributes.
   *
   * This is also only called for [PrimitiveLithoRenderUnit], as the handling for MountSpecs is done
   * during the creation of the [MountSpecLithoRenderUnit].
   *
   * This method is specific for [Primitive] because of how we structure Render Units in primitives.
   * At each layout phase, we create a [com.facebook.litho.PrimitiveLithoRenderUnit], which will
   * wrap the RenderUnit that is created in the Resolve phase. This means that if we added mount
   * binders to each of the [com.facebook.litho.PrimitiveLithoRenderUnit], then we could create
   * comodification exceptions. To avoid it, we add the [ViewAttributesBinder] to the shared Render
   * Unit.
   */
  private fun addViewAttributesBinderIfForPrimitive() {
    val nodePrimitive = primitive

    if (nodePrimitive != null && willMountView) {
      val viewAttributes: ViewAttributes? =
          LithoNodeUtils.createViewAttributesForBinder(
              lithoNode = this,
              component = tailComponent,
              willMountView = true,
              importantForAccessibility = importantForAccessibility,
          )

      if (viewAttributes != null) {
        val viewAttributesBinder =
            ViewAttributesViewBinder.create(
                ViewAttributesViewBinder.Model(
                    nodeInfo = nodeInfo,
                    viewAttributes = viewAttributes,
                    isRootHost = false,
                ),
            )
        nodePrimitive.renderUnit.addOptionalMountBinder(viewAttributesBinder)
      }
    }
  }

  /**
   * This method will add a binder that updates the content drawable's drawable state to primitive
   * render unit.
   */
  private fun addRefreshDrawableStateBinder() {
    var flags = 0

    if (isDuplicateParentStateEnabled) {
      flags = flags or LithoRenderUnit.LAYOUT_FLAG_DUPLICATE_PARENT_STATE
    }
    if (nodeInfo?.hasTouchEventHandlers() == true) {
      flags = flags or LithoRenderUnit.LAYOUT_FLAG_HAS_TOUCH_EVENT_HANDLERS
    }

    primitive
        ?.renderUnit
        ?.addAttachBinder(DelegateBinder.createDelegateBinder(flags, RefreshDrawableStateBinder))
  }

  fun addComponentNeedingPreviousRenderData(scopedComponentInfo: ScopedComponentInfo) {
    val transitionCreator = SpecTransitionCreator(scopedComponentInfo)
    transitionData
        .getOrCreate { MutableTransitionData().also { transitionData = it } }
        .addTransitionCreator(transitionCreator)
  }

  fun addTransition(transition: Transition) {
    transitionData
        .getOrCreate { MutableTransitionData().also { transitionData = it } }
        .addTransition(transition)
  }

  internal fun styleTransition(styleTransitionData: StyleTransitionData) {
    this.styleTransitionData =
        this.styleTransitionData?.plus(styleTransitionData) ?: styleTransitionData
  }

  internal fun addTransitionData(data: TransitionData) {
    transitionData.getOrCreate { MutableTransitionData().also { transitionData = it } }.add(data)
  }

  fun addWorkingRanges(registrations: List<WorkingRangeContainer.Registration>) {
    _workingRangeRegistrations
        .getOrCreate {
          ArrayList<WorkingRangeContainer.Registration>(registrations.size).also {
            _workingRangeRegistrations = it
          }
        }
        .addAll(registrations)
  }

  fun addAttachable(attachable: Attachable) {
    _attachables.getOrCreate { ArrayList<Attachable>(4).also { _attachables = it } }.add(attachable)
  }

  internal fun addResolveEffect(effect: Attachable) {
    _resolveEffects
        .getOrCreate { ArrayList<Attachable>(2).also { _resolveEffects = it } }
        .add(effect)
  }

  internal fun addDiscardKey(key: Any) {
    _discardKeys.getOrCreate { ArrayList<Any>(1).also { _discardKeys = it } }.add(key)
  }

  fun alignContent(alignContent: YogaAlign) {
    this.alignContent = alignContent
  }

  fun alignItems(alignItems: YogaAlign) {
    this.alignItems = alignItems
  }

  fun appendComponent(scopedComponentInfo: ScopedComponentInfo) {
    _scopedComponentInfos.add(scopedComponentInfo)
    if (_scopedComponentInfos.size == 1) {
      willMountView = willMountView(this)
    }
  }

  fun appendUnresolvedComponent(component: Component) {
    _unresolvedComponents
        .getOrCreate { ArrayList<Component>().also { _unresolvedComponents = it } }
        .add(component)
  }

  /**
   * The goal of this method is to add the optional mount binders to the associated to this
   * [LithoNode]. If we are dealing with a Primitive, we will get the corresponding [LithoNode] and
   * associate the binders map as optional mount binders. For this reason, this method should be
   * called as soon as their [RenderUnit] is created. In Litho, this happens in the Resolve phase,
   * specifically when the mount content preparation is invoked.
   *
   * For [MountSpecLithoRenderUnit] (e.g., the node is associated with a MountSpec, or the Primitive
   * mounts a Drawable and, therefore will need to be wrapped in a [ComponentHost] to work with the
   * view binders), the addition of the optional mount binders is delayed until the moment of its
   * creation. For that, we store these binders in the [LithoNode] and use them later.
   */
  fun addViewCustomBinders(viewBindersMap: Map<BinderKey, DelegateBinder<Any, Any, Any>>? = null) {
    if (viewBindersMap.isNullOrEmpty()) {
      return
    }

    privateFlags = privateFlags or PFLAG_BINDER_IS_SET

    if (!willMountDrawable(this)) {
      allNotNull(primitive, viewBindersMap) { primitive, map ->
        for (binder in map.values) {
          primitive.renderUnit.addOptionalMountBinder(binder)
        }
      }
    }

    customViewBindersForMountSpec
        .getOrCreate {
          LinkedHashMap<BinderKey, DelegateBinder<Any, Any, Any>>().also {
            customViewBindersForMountSpec = it
          }
        }
        .putAll(viewBindersMap)
  }

  fun addCustomBinders(binders: Map<BinderKey, DelegateBinder<Any, Any, Any>>? = null) {
    if (binders.isNullOrEmpty()) {
      return
    }

    if (primitive != null) {
      allNotNull(primitive, binders) { primitive, map ->
        for (binder in map.values) {
          primitive.renderUnit.addOptionalMountBinder(binder)
        }
      }
    } else {
      customBindersForMountSpec
          .getOrCreate {
            LinkedHashMap<BinderKey, DelegateBinder<Any, Any, Any>>().also {
              customBindersForMountSpec = it
            }
          }
          .putAll(binders)
    }
  }

  fun addHostViewCustomBinder(binders: Map<BinderKey, DelegateBinder<Any, Any, Any>>?) {
    if (binders.isNullOrEmpty()) {
      return
    }

    customHostViewBinders
        .getOrCreate {
          LinkedHashMap<BinderKey, DelegateBinder<Any, Any, Any>>().also {
            customHostViewBinders = it
          }
        }
        .putAll(binders)
  }

  open fun child(resolveContext: ResolveContext, c: ComponentContext, child: Component?) {
    child?.let { child(Resolver.resolve(resolveContext, c, it)) }
  }

  fun child(child: LithoNode?) {
    child?.let { addChildAt(it, children.size) }
  }

  fun addChildAt(child: LithoNode, index: Int) {
    children.add(index, child)
  }

  fun getChildIndex(child: LithoNode): Int {
    for (i in 0 until children.size) {
      if (children[i] === child) {
        return i
      }
    }
    return -1
  }

  fun duplicateParentState(duplicateParentState: Boolean) {
    isDuplicateParentStateEnabled = duplicateParentState
  }

  fun duplicateChildrenStates(duplicateChildrenStates: Boolean) {
    privateFlags = privateFlags or PFLAG_DUPLICATE_CHILDREN_STATES_IS_SET
    isDuplicateChildrenStatesEnabled = duplicateChildrenStates
  }

  fun flexDirection(direction: YogaFlexDirection) {
    flexDirection = direction
  }

  fun layerType(@LayerType type: Int, paint: Paint?) {
    if (type != LayerType.LAYER_TYPE_NOT_SET) {
      layerType = type
      layerPaint = paint
    }
  }

  fun getChildAt(index: Int): LithoNode = children[index]

  fun mutableNodeInfo(): NodeInfo {
    val nodeInfo: NodeInfo
    if (!nodeInfoWasWritten) {
      nodeInfoWasWritten = true
      nodeInfo = NodeInfo()
      this.nodeInfo?.copyInto(nodeInfo)
    } else {
      nodeInfo =
          this.nodeInfo
              // In theory this will not happen, just to avoid any lint warnings from static
              // analysis
              ?: NodeInfo()
    }
    this.nodeInfo = nodeInfo
    return nodeInfo
  }

  fun applyNodeInfo(nodeInfo: NodeInfo?) {
    if (nodeInfo != null) {
      if (nodeInfoWasWritten || this.nodeInfo != null) {
        nodeInfo.copyInto(mutableNodeInfo())
      } else {
        this.nodeInfo = nodeInfo
      }
    }
  }

  fun getComponentInfoAt(index: Int): ScopedComponentInfo = _scopedComponentInfos[index]

  fun getCommonPropsAt(index: Int): CommonProps? = getComponentInfoAt(index).commonProps

  fun getComponentContextAt(index: Int): ComponentContext = getComponentInfoAt(index).context

  fun getComponentAt(index: Int): Component = getComponentInfoAt(index).component

  fun getGlobalKeyAt(index: Int): GlobalKey = getComponentContextAt(index).globalKey

  fun setDeferredPadding(padding: Edges?, isPercentage: BooleanArray?) {
    paddingsFromDeferredNode = padding
    paddingIsPercentFromDefferedNode = isPercentage
  }

  fun hasBorderColor(): Boolean {
    return borderColors.any { color -> color != Color.TRANSPARENT }
  }

  fun hasStateListAnimatorResSet(): Boolean =
      privateFlags and PFLAG_STATE_LIST_ANIMATOR_RES_SET != 0L

  fun hasTouchExpansion(): Boolean = privateFlags and PFLAG_TOUCH_EXPANSION_IS_SET != 0L

  fun hasTransitionKey(): Boolean = !transitionKey.isNullOrEmpty()

  fun justifyContent(justifyContent: YogaJustify) {
    this.justifyContent = justifyContent
  }

  fun setMeasureFunction(measureFunction: YogaMeasureFunction) {
    yogaMeasureFunction = measureFunction
  }

  /**
   * Optional companion to [setMeasureFunction] used by the CSS Flexbox §4.5 automatic minimum
   * sizing probe. When set, the underlying [com.facebook.yoga.YogaNode] will invoke this function
   * instead of the regular measure function when sizing this node for its min-content contribution
   * along the parent flex container's main axis.
   *
   * Use this from a Primitive when its min-content size is cheap to compute or differs from what
   * the regular measure would return under `AtMost 0` — e.g. an image returning `(0, 0)` instead of
   * doing a bitmap intrinsic size lookup, or a collection returning `0` along its scroll axis.
   * Primitives without a min-content measure function fall back to the regular measure with `AtMost
   * 0`, which text measurers naturally answer with longest-word width.
   */
  fun setMinContentMeasureFunction(measureFunction: YogaMeasureFunction) {
    yogaMinContentMeasureFunction = measureFunction
  }

  fun stateListAnimator(stateListAnimator: StateListAnimator?) {
    privateFlags = privateFlags or PFLAG_STATE_LIST_ANIMATOR_SET
    this.stateListAnimator = stateListAnimator
    wrapInView()
  }

  fun stateListAnimatorRes(@DrawableRes resId: Int) {
    privateFlags = privateFlags or PFLAG_STATE_LIST_ANIMATOR_RES_SET
    stateListAnimatorRes = resId
    wrapInView()
  }

  fun zIndex(order: Int) {
    zIndex = order
  }

  fun testKey(testKey: String?) {
    this.testKey = testKey
  }

  fun touchExpansionPx(edge: YogaEdge, @Px touchExpansion: Int) {
    if (this.touchExpansion == null) {
      this.touchExpansion = Edges()
    }
    privateFlags = privateFlags or PFLAG_TOUCH_EXPANSION_IS_SET
    this.touchExpansion?.set(edge, touchExpansion.toFloat())
  }

  fun transitionKey(key: String?, ownerKey: Any?) {
    if (!key.isNullOrEmpty()) {
      privateFlags = privateFlags or PFLAG_TRANSITION_KEY_IS_SET
      transitionKey = key
      transitionOwnerKey = ownerKey
    }
  }

  fun transitionKeyType(type: TransitionKeyType?) {
    privateFlags = privateFlags or PFLAG_TRANSITION_KEY_TYPE_IS_SET
    transitionKeyType = type
  }

  fun addUnfocusedVisibleEventListener(callback: EventHandler<UnfocusedVisibleEvent>?) {
    privateFlags = privateFlags or PFLAG_UNFOCUSED_HANDLER_IS_SET
    this.onUnfocusedVisible = addVisibilityHandler(this.onUnfocusedVisible, callback)
  }

  fun addVisibilityChangedEventListener(callback: EventHandler<VisibilityChangedEvent>?) {
    privateFlags = privateFlags or PFLAG_VISIBLE_RECT_CHANGED_HANDLER_IS_SET
    this.onVisibilityChanged = addVisibilityHandler(this.onVisibilityChanged, callback)
  }

  fun addVisibleEventListener(callback: EventHandler<VisibleEvent>?) {
    privateFlags = privateFlags or PFLAG_VISIBLE_HANDLER_IS_SET
    this.onVisible = addVisibilityHandler(this.onVisible, callback)
  }

  fun addInvisibleEventListener(callback: EventHandler<InvisibleEvent>?): LithoNode {
    privateFlags = privateFlags or PFLAG_INVISIBLE_HANDLER_IS_SET
    this.onInvisible = addVisibilityHandler(this.onInvisible, callback)
    return this
  }

  fun addFocusedVisibleEventListener(callback: EventHandler<FocusedVisibleEvent>?) {
    privateFlags = privateFlags or PFLAG_FOCUSED_HANDLER_IS_SET
    this.onFocusedVisible = addVisibilityHandler(this.onFocusedVisible, callback)
  }

  fun addFullImpressionEventListener(callback: EventHandler<FullImpressionVisibleEvent>?) {
    privateFlags = privateFlags or PFLAG_FULL_IMPRESSION_HANDLER_IS_SET
    this.onFullImpression = addVisibilityHandler(this.onFullImpression, callback)
  }

  fun visibleHeightRatio(visibleHeightRatio: Float) {
    this.visibleHeightRatio = visibleHeightRatio
  }

  fun visibleWidthRatio(visibleWidthRatio: Float) {
    this.visibleWidthRatio = visibleWidthRatio
  }

  fun hasVisibilityHandlers(): Boolean =
      onVisible != null ||
          onFocusedVisible != null ||
          onUnfocusedVisible != null ||
          onFullImpression != null ||
          onInvisible != null ||
          onVisibilityChanged != null

  private fun reportNullVisibilityCallbackData(methodName: String) {
    DebugInfoReporter.report("LithoNode:null_visibility_callback_data") {
      put("method", methodName)
      put("component", simpleName)
      put("hasVisibilityHandlers", hasVisibilityHandlers())
      put("thread", Thread.currentThread().name)
    }
  }

  fun visibilityOutputTag(visibilityOutputTag: String?) {
    this.visibilityOutputTag = visibilityOutputTag
  }

  fun wrap(wrap: YogaWrap) {
    yogaWrap = wrap
  }

  fun setGap(gutter: YogaGutter, gap: Int) {
    when (gutter) {
      YogaGutter.COLUMN -> columnGapPx = gap
      YogaGutter.ROW -> rowGapPx = gap
      YogaGutter.ALL -> allGapPx = gap
      else -> {}
    }
  }

  fun addSystemGestureExclusionZones(zones: MutableList<(Rect) -> Rect>) {
    (_systemGestureExclusionZones
            ?: ArrayList<(Rect) -> Rect>().also { _systemGestureExclusionZones = it })
        .addAll(zones)
  }

  fun applyAttributes(c: Context, @AttrRes defStyleAttr: Int, @StyleRes defStyleRes: Int) {
    val a = c.obtainStyledAttributes(null, R.styleable.ComponentLayout, defStyleAttr, defStyleRes)
    for (i in 0 until a.indexCount) {
      when (val attr = a.getIndex(i)) {
        R.styleable.ComponentLayout_android_importantForAccessibility ->
            importantForAccessibility(a.getInt(attr, 0))
        R.styleable.ComponentLayout_android_duplicateParentState ->
            duplicateParentState(a.getBoolean(attr, false))
        R.styleable.ComponentLayout_android_background -> {
          if (TypedArrayUtils.isColorAttribute(a, R.styleable.ComponentLayout_android_background)) {
            backgroundColor(a.getColor(attr, 0))
          } else {
            backgroundRes(c, a.getResourceId(attr, -1))
          }
        }
        R.styleable.ComponentLayout_android_foreground -> {
          if (TypedArrayUtils.isColorAttribute(a, R.styleable.ComponentLayout_android_foreground)) {
            foregroundColor(a.getColor(attr, 0))
          } else {
            foregroundRes(c, a.getResourceId(attr, -1))
          }
        }
        R.styleable.ComponentLayout_android_contentDescription ->
            mutableNodeInfo().contentDescription = a.getString(attr)
      }
    }
    a.recycle()
  }

  /**
   * Note: Is only resolved after layout.
   *
   * @return `true` iff the node's out requires a host to wrap it
   */
  fun needsHostView(): Boolean {
    check(frozen) { "LithoNode:(${simpleName}) has not been resolved." }
    return _needsHostView
  }

  fun setDeferredNode(holder: DeferredLithoNode?) {
    deferredNode = holder
  }

  private fun shouldApplyTouchExpansion(): Boolean =
      touchExpansion != null && (nodeInfo?.hasTouchEventHandlers() == true)

  internal fun touchExpansionLeft(isRtl: Boolean): Int =
      if (shouldApplyTouchExpansion()) {
        touchExpansion?.let { edges ->
          FastMath.round(resolveHorizontalEdges(edges, YogaEdge.LEFT, isRtl))
        } ?: 0
      } else 0

  internal fun touchExpansionTop(): Int =
      if (shouldApplyTouchExpansion()) {
        touchExpansion?.let { edges -> FastMath.round(edges[YogaEdge.TOP]) } ?: 0
      } else 0

  internal fun touchExpansionRight(isRtl: Boolean): Int =
      if (shouldApplyTouchExpansion()) {
        touchExpansion?.let { edges ->
          FastMath.round(resolveHorizontalEdges(edges, YogaEdge.RIGHT, isRtl))
        } ?: 0
      } else 0

  internal fun touchExpansionBottom(): Int =
      if (shouldApplyTouchExpansion()) {
        touchExpansion?.let { edges -> FastMath.round(edges[YogaEdge.BOTTOM]) } ?: 0
      } else 0

  private fun hasCustomBindersForMountSpec(): Boolean =
      customViewBindersForMountSpec?.isNotEmpty() == true

  companion object {
    private val idGenerator = AtomicInteger(1)

    // Flags used to indicate that a certain attribute was explicitly set on the node.
    internal const val PFLAG_LAYOUT_DIRECTION_IS_SET: Long = 1L
    internal const val PFLAG_IMPORTANT_FOR_ACCESSIBILITY_IS_SET: Long = 1L shl 7
    internal const val PFLAG_BACKGROUND_IS_SET: Long = 1L shl 18
    internal const val PFLAG_FOREGROUND_IS_SET: Long = 1L shl 19
    internal const val PFLAG_VISIBLE_HANDLER_IS_SET: Long = 1L shl 20
    internal const val PFLAG_FOCUSED_HANDLER_IS_SET: Long = 1L shl 21
    internal const val PFLAG_FULL_IMPRESSION_HANDLER_IS_SET: Long = 1L shl 22
    internal const val PFLAG_INVISIBLE_HANDLER_IS_SET: Long = 1L shl 23
    internal const val PFLAG_UNFOCUSED_HANDLER_IS_SET: Long = 1L shl 24
    internal const val PFLAG_TOUCH_EXPANSION_IS_SET: Long = 1L shl 25
    internal const val PFLAG_TRANSITION_KEY_IS_SET: Long = 1L shl 27
    internal const val PFLAG_BORDER_IS_SET: Long = 1L shl 28
    internal const val PFLAG_STATE_LIST_ANIMATOR_SET: Long = 1L shl 29
    internal const val PFLAG_STATE_LIST_ANIMATOR_RES_SET: Long = 1L shl 30
    internal const val PFLAG_VISIBLE_RECT_CHANGED_HANDLER_IS_SET: Long = 1L shl 31
    internal const val PFLAG_TRANSITION_KEY_TYPE_IS_SET: Long = 1L shl 32
    internal const val PFLAG_DUPLICATE_CHILDREN_STATES_IS_SET: Long = 1L shl 33
    internal const val PFLAG_BINDER_IS_SET: Long = 1L shl 34

    private const val COMPONENT_HOST_LOG: String = "LithoComponentHostLog"

    private inline fun readStyledAttributes(
        context: Context,
        styleAttr: Int,
        styleRes: Int,
        block: (TypedArray) -> Unit,
    ) {
      var attributes: TypedArray? = null
      try {
        attributes =
            context.obtainStyledAttributes(null, R.styleable.ComponentLayout, styleAttr, styleRes)
        block(attributes)
      } finally {
        attributes?.recycle()
      }
    }

    private fun applyLayoutStyleAttributes(props: LayoutProps, a: TypedArray) {
      for (i in 0 until a.indexCount) {
        when (val attr = a.getIndex(i)) {
          R.styleable.ComponentLayout_android_layout_width -> {
            val width = a.getLayoutDimension(attr, -1)
            // We don't support WRAP_CONTENT or MATCH_PARENT so no-op for them
            if (width >= 0) {
              props.widthPx(width)
            }
          }
          R.styleable.ComponentLayout_android_layout_height -> {
            val height = a.getLayoutDimension(attr, -1)
            // We don't support WRAP_CONTENT or MATCH_PARENT so no-op for them
            if (height >= 0) {
              props.heightPx(height)
            }
          }
          R.styleable.ComponentLayout_android_minHeight ->
              props.minHeightPx(a.getDimensionPixelSize(attr, 0))
          R.styleable.ComponentLayout_android_minWidth ->
              props.minWidthPx(a.getDimensionPixelSize(attr, 0))
          R.styleable.ComponentLayout_android_paddingLeft ->
              props.paddingPx(YogaEdge.LEFT, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_paddingTop ->
              props.paddingPx(YogaEdge.TOP, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_paddingRight ->
              props.paddingPx(YogaEdge.RIGHT, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_paddingBottom ->
              props.paddingPx(YogaEdge.BOTTOM, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_paddingStart ->
              props.paddingPx(YogaEdge.START, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_paddingEnd ->
              props.paddingPx(YogaEdge.END, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_padding ->
              props.paddingPx(YogaEdge.ALL, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_layout_marginLeft ->
              props.marginPx(YogaEdge.LEFT, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_layout_marginTop ->
              props.marginPx(YogaEdge.TOP, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_layout_marginRight ->
              props.marginPx(YogaEdge.RIGHT, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_layout_marginBottom ->
              props.marginPx(YogaEdge.BOTTOM, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_layout_marginStart ->
              props.marginPx(YogaEdge.START, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_layout_marginEnd ->
              props.marginPx(YogaEdge.END, a.getDimensionPixelOffset(attr, 0))
          R.styleable.ComponentLayout_android_layout_margin ->
              props.marginPx(YogaEdge.ALL, a.getDimensionPixelOffset(attr, 0))
        }
      }
    }

    internal fun CommonProps.writeStyledAttributesToLayoutProps(
        context: Context,
        layoutProps: LayoutProps,
    ) {
      val styleAttr: Int = defStyleAttr
      val styleRes: Int = defStyleRes
      if (styleAttr != 0 || styleRes != 0) {
        readStyledAttributes(context, styleAttr, styleRes) { typedArray ->
          applyLayoutStyleAttributes(layoutProps, typedArray)
        }
      }
    }

    internal inline fun LithoNode.applyBorderWidth(block: (YogaEdge, Float) -> Unit) {
      if (privateFlags and PFLAG_BORDER_IS_SET != 0L) {
        for (i in borderEdgeWidths.indices) {
          block(Border.edgeFromIndex(i), borderEdgeWidths[i].toFloat())
        }
      }
    }

    internal inline fun LithoNode.applyDeferredPadding(
        paddingPx: (YogaEdge, Int) -> Unit,
        paddingPercent: (YogaEdge, Float) -> Unit,
    ) {
      paddingsFromDeferredNode?.let { edges ->
        for (i in 0 until Edges.EDGES_LENGTH) {
          val value: Float = edges.getRaw(i)
          if (!YogaConstants.isUndefined(value)) {
            val edge: YogaEdge = YogaEdge.fromInt(i)
            if (paddingIsPercentFromDefferedNode?.get(edge.intValue()) != null) {
              paddingPercent(edge, value)
            } else {
              paddingPx(edge, value.toInt())
            }
          }
        }
      }
    }

    /**
     * This utility method checks if the {@param result} will mount a [android.view.View]. It
     * returns true if and only if the {@param result} will mount a [android.view.View]. If it
     * returns `false` then the result will either mount a [Drawable] or it is
     * [DeferredLithoLayoutResult], which will not mount anything.
     *
     * @return `true` iff the result will mount a view.
     */
    private fun willMountView(node: LithoNode): Boolean =
        if (node.primitive?.renderUnit?.renderType == RenderType.VIEW) {
          true
        } else {
          node.tailComponent.mountType == Component.MountType.VIEW
        }

    private fun willMountDrawable(node: LithoNode): Boolean =
        if (node.primitive?.renderUnit?.renderType == RenderType.DRAWABLE) {
          true
        } else {
          node.tailComponent.mountType == Component.MountType.DRAWABLE
        }

    private fun debugLog(tag: String, message: String) {
      Log.d(tag, message)
    }

    /**
     * Returns true if this is the root node (which always generates a matching layout output), if
     * the node has view attributes e.g. tags, content description, etc, or if the node has
     * explicitly been forced to be wrapped in a view.
     *
     * @param node The LithoNode to check
     */
    internal fun needsHostView(node: LithoNode): Boolean {
      if (LithoDebugConfigurations.isComponentHostLoggingEnabled) {
        debugLog(COMPONENT_HOST_LOG, "----------------------------------------------")
        val infos: List<ScopedComponentInfo> = node.scopedComponentInfos
        for (info in infos) {
          debugLog(
              COMPONENT_HOST_LOG,
              "Component: " + info.component.toString() + "@" + info.component.hashCode(),
          )
        }
        if (node.willMountView) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: alreadyRepresentsView")
        }
        if (node.isForceViewWrapping) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: isForceViewWrapping")
        }
        if (needsHostViewForCommonDynamicProps(node)) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: needsHostViewForCommonDynamicProps")
        }
        if (needsHostViewForTransition(node)) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: needsHostViewForTransition")
        }
        if (hasSelectedStateWhenDisablingDrawableOutputs(node)) {
          debugLog(
              COMPONENT_HOST_LOG,
              "NeedsHostView: hasSelectedStateWhenDisablingDrawableOutputs",
          )
        }
        if (Component.isLayoutSpec(node.tailComponent)) {
          if (!node.systemGestureExclusionZones.isNullOrEmpty()) {
            debugLog(COMPONENT_HOST_LOG, "NeedsHostView: gestureExclusionZones")
          }
          if (node.hasCustomBindersForMountSpec()) {
            debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasCustomBindersForMountSpec")
          }
        }
        if (
            willMountDrawable(node) &&
                (!node.systemGestureExclusionZones.isNullOrEmpty() ||
                    node.hasCustomBindersForMountSpec())
        ) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: willMountDrawable")
        }
      }

      if (node.willMountView) {
        // Component already represents a View.
        return false
      }
      if (node.isForceViewWrapping) {
        // Wrapping into a View requested.
        return true
      }
      if (hasViewContent(node)) {
        // Has View content (e.g. Accessibility content, Focus change listener, shadow, view tag
        // etc) thus needs a host View.
        return true
      }
      if (needsHostViewForCommonDynamicProps(node)) {
        return true
      }
      if (needsHostViewForTransition(node)) {
        return true
      }
      if (hasSelectedStateWhenDisablingDrawableOutputs(node)) {
        return true
      }

      if (
          Component.isLayoutSpec(node.tailComponent) &&
              (!node.systemGestureExclusionZones.isNullOrEmpty() ||
                  node.hasCustomBindersForMountSpec())
      ) {
        return true
      }
      return willMountDrawable(node) &&
          (!node.systemGestureExclusionZones.isNullOrEmpty() || node.hasCustomBindersForMountSpec())
    }

    private fun hasViewAttributes(nodeInfo: NodeInfo?): Boolean {
      if (nodeInfo == null) {
        return false
      }
      val hasFocusChangeHandler = nodeInfo.hasFocusChangeHandler()
      val hasEnabledTouchEventHandlers =
          nodeInfo.hasTouchEventHandlers() && nodeInfo.enabledState != NodeInfo.ENABLED_SET_FALSE
      val hasViewId = nodeInfo.hasViewId()
      val hasViewTag = nodeInfo.viewTag != null
      val hasViewTags = nodeInfo.viewTags != null
      val hasShadowElevation = nodeInfo.shadowElevation != 0f
      val hasAmbientShadowColor = nodeInfo.ambientShadowColor != Color.BLACK
      val hasSpotShadowColor = nodeInfo.spotShadowColor != Color.BLACK
      val hasOutlineProvider = nodeInfo.outlineProvider != null
      val hasClipToOutline = nodeInfo.clipToOutline
      val isFocusableSetTrue = nodeInfo.focusState == NodeInfo.FOCUS_SET_TRUE
      val isClickableSetTrue = nodeInfo.clickableState == NodeInfo.CLICKABLE_SET_TRUE
      val isKeyboardNavigationClusterSetTrue =
          nodeInfo.keyboardNavigationClusterState == NodeInfo.KEYBOARD_NAVIGATION_CLUSTER_SET_TRUE
      val hasTooltipText = nodeInfo.tooltipText != null
      val hasClipChildrenSet = nodeInfo.isClipChildrenSet
      val hasTransitionName = nodeInfo.transitionName != null

      if (LithoDebugConfigurations.isComponentHostLoggingEnabled) {
        if (hasFocusChangeHandler) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasFocusChangeHandler")
        }
        if (hasEnabledTouchEventHandlers) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasEnabledTouchEventHandlers")
        }
        if (hasViewId) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasViewId")
        }
        if (hasViewTag) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasViewTag")
        }
        if (hasViewTags) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasViewTags")
        }
        if (hasShadowElevation) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasShadowElevation")
        }
        if (hasAmbientShadowColor) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasAmbientShadowColor")
        }
        if (hasSpotShadowColor) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasSpotShadowColor")
        }
        if (hasOutlineProvider) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasOutlineProvider")
        }
        if (hasClipToOutline) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasClipToOutline")
        }
        if (hasClipChildrenSet) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasClipChildrenSet")
        }
        if (isFocusableSetTrue) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: isFocusableSetTrue")
        }
        if (isClickableSetTrue) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: isClickableSetTrue")
        }
        if (isKeyboardNavigationClusterSetTrue) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: isKeyboardNavigationClusterSetTrue")
        }
        if (hasTooltipText) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasTooltipText")
        }
        if (hasTransitionName) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasTransitionName")
        }
      }

      return hasFocusChangeHandler ||
          hasEnabledTouchEventHandlers ||
          hasViewId ||
          hasViewTag ||
          hasViewTags ||
          hasShadowElevation ||
          hasAmbientShadowColor ||
          hasSpotShadowColor ||
          hasOutlineProvider ||
          hasClipToOutline ||
          hasClipChildrenSet ||
          isFocusableSetTrue ||
          isClickableSetTrue ||
          isKeyboardNavigationClusterSetTrue ||
          hasTooltipText ||
          hasTransitionName
    }

    private fun hasSelectedStateWhenDisablingDrawableOutputs(node: LithoNode): Boolean =
        ComponentContext.getComponentsConfig(node.headComponentContext)
            .shouldAddHostViewForRootComponent &&
            !node.willMountView &&
            node.nodeInfo != null &&
            node.nodeInfo?.selectedState != NodeInfo.SELECTED_UNSET

    /**
     * Similar to [LithoNode.needsHostView] but without dependency to [LayoutState] instance. This
     * will be used for debugging tools to indicate whether the mountable output is a wrapped View
     * or View MountSpec. Unlike [LithoNode.needsHostView] this does not consider accessibility also
     * does not consider root component, but this approximation is good enough for debugging
     * purposes.
     */
    fun hasViewOutput(node: LithoNode): Boolean =
        node.isForceViewWrapping ||
            node.willMountView ||
            hasViewAttributes(node.nodeInfo) ||
            needsHostViewForCommonDynamicProps(node) ||
            needsHostViewForTransition(node)

    private fun needsHostViewForCommonDynamicProps(node: LithoNode): Boolean {
      val infos: List<ScopedComponentInfo> = node.scopedComponentInfos
      for (info in infos) {
        if (info.commonProps?.hasCommonDynamicProps() == true) {
          // Need a host View to apply the dynamic props to
          return true
        }
      }
      return false
    }

    private fun needsHostViewForTransition(node: LithoNode): Boolean =
        !node.transitionKey.isNullOrEmpty() && !node.willMountView

    /**
     * Whether [node] would get its own host view if it mounted an accessible drawable instead of a
     * view, as legacy Text does. [needsHostView] is always false for a node that mounts a view.
     */
    internal fun needsHostViewAsAccessibleDrawable(node: LithoNode): Boolean =
        node.isForceViewWrapping ||
            hasViewContent(node, assumeImplementsAccessibility = true) ||
            needsHostViewForCommonDynamicProps(node) ||
            !node.transitionKey.isNullOrEmpty() ||
            (ComponentContext.getComponentsConfig(node.headComponentContext)
                .shouldAddHostViewForRootComponent &&
                node.nodeInfo != null &&
                node.nodeInfo?.selectedState != NodeInfo.SELECTED_UNSET) ||
            !node.systemGestureExclusionZones.isNullOrEmpty() ||
            node.hasCustomBindersForMountSpec()

    /**
     * Determine if a given [LithoNode] within the context of a given [LayoutState] requires to be
     * wrapped inside a view.
     *
     * @see LithoNode.needsHostView
     */
    private fun hasViewContent(
        node: LithoNode,
        assumeImplementsAccessibility: Boolean = false,
    ): Boolean {
      val component: Component = node.tailComponent
      val nodeInfo: NodeInfo? = node.nodeInfo
      val implementsAccessibility =
          assumeImplementsAccessibility ||
              nodeInfo?.needsAccessibilityDelegate() == true ||
              (component is SpecGeneratedComponent && component.implementsAccessibility())
      val importantForAccessibility: Int = node.importantForAccessibility
      val c: ComponentContext = node.headComponentContext
      val context: CalculationContext? = c.calculationStateContext

      // A component has accessibility content if:
      //   1. Accessibility is currently enabled.
      //   2. Accessibility hasn't been explicitly disabled on it
      //      i.e. IMPORTANT_FOR_ACCESSIBILITY_NO.
      //   3. Any of these conditions are true:
      //      - It implements accessibility support.
      //      - It has a content description.
      //      - It has importantForAccessibility set as either IMPORTANT_FOR_ACCESSIBILITY_YES
      //        or IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS.
      // IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS should trigger an inner host
      // so that such flag is applied in the resulting view hierarchy after the component
      // tree is mounted. Click handling is also considered accessibility content but
      // this is already covered separately i.e. click handler is not null.
      val hasBackgroundOrForeground = node.background != null || node.foreground != null
      val hasAccessibilityContent =
          (context?.isAccessibilityEnabled == true) &&
              (importantForAccessibility != ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO) &&
              (implementsAccessibility ||
                  !(nodeInfo?.contentDescription.isNullOrEmpty()) ||
                  (importantForAccessibility != ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO))

      if (LithoDebugConfigurations.isComponentHostLoggingEnabled) {
        if (hasBackgroundOrForeground) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasBackgroundOrForeground")
        }
        if (hasAccessibilityContent) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: hasAccessibilityContent")
        }
        if (node.isDuplicateChildrenStatesEnabled) {
          debugLog(COMPONENT_HOST_LOG, "NeedsHostView: node.isDuplicateChildrenStatesEnabled")
        }
        if (node.layerType != LayerType.LAYER_TYPE_NOT_SET) {
          debugLog(
              COMPONENT_HOST_LOG,
              "NeedsHostView: node.layerType != LayerType.LAYER_TYPE_NOT_SET",
          )
        }
      }

      return hasBackgroundOrForeground ||
          hasAccessibilityContent ||
          node.isDuplicateChildrenStatesEnabled ||
          hasViewAttributes(nodeInfo) ||
          node.layerType != LayerType.LAYER_TYPE_NOT_SET
    }

    private fun <T : Any> addVisibilityHandler(
        currentHandler: EventHandler<T>? = null,
        newHandler: EventHandler<T>? = null,
    ): EventHandler<T>? {
      if (currentHandler == null) {
        return newHandler
      }
      if (newHandler == null) {
        return currentHandler
      }
      return if (currentHandler is DelegatingEventHandler<*>) {
        val delegatingEventHandler = currentHandler as DelegatingEventHandler<T>
        delegatingEventHandler.addEventHandler(newHandler)
      } else {
        DelegatingEventHandler(currentHandler, newHandler)
      }
    }

    private fun resolveHorizontalEdges(spacing: Edges, edge: YogaEdge, isRtl: Boolean): Float {
      val resolvedEdge =
          when (edge) {
            YogaEdge.LEFT -> (if (isRtl) YogaEdge.END else YogaEdge.START)
            YogaEdge.RIGHT -> (if (isRtl) YogaEdge.START else YogaEdge.END)
            else -> throw IllegalArgumentException("Not an horizontal padding edge: $edge")
          }
      var result = spacing.getRaw(resolvedEdge)
      if (YogaConstants.isUndefined(result)) {
        result = spacing[edge]
      }
      return result
    }
  }
}

private val RefreshDrawableStateBinder: Binder<Int, Any, Any> =
    object : Binder<Int, Any, Any> {
      override fun shouldUpdate(
          currentModel: Int,
          newModel: Int,
          currentLayoutData: Any?,
          nextLayoutData: Any?,
      ): Boolean = true

      override fun bind(
          context: Context,
          content: Any,
          model: Int, // flags
          layoutData: Any?,
      ): Any? {
        if (content is Drawable) {
          if (content.callback is View) {
            val view = content.callback as View
            maybeSetDrawableState(view, content, model)
          }
        }
        return null
      }

      override fun unbind(
          context: Context,
          content: Any,
          model: Int,
          layoutData: Any?,
          bindData: Any?,
      ) = Unit
    }
