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

package com.facebook.litho.view

import android.animation.StateListAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.SparseArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import androidx.annotation.IdRes
import androidx.annotation.VisibleForTesting
import androidx.core.view.ViewCompat
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.ClickEvent
import com.facebook.litho.CommonProps
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentHost
import com.facebook.litho.ComponentLongClickListener
import com.facebook.litho.ComponentTouchListener
import com.facebook.litho.FocusChangedEvent
import com.facebook.litho.InterceptTouchEvent
import com.facebook.litho.LayerType
import com.facebook.litho.LayerTypeGuard
import com.facebook.litho.LithoRenderEffect
import com.facebook.litho.LongClickEvent
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.litho.SupportsPivotTransform
import com.facebook.litho.SupportsPivotTransform.Companion.BadPivotClassErrorMessage
import com.facebook.litho.TouchEvent
import com.facebook.litho.binders.onBindViewWithDescription
import com.facebook.litho.binders.viewBinder
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.CoreDimenField
import com.facebook.litho.core.CoreDimenStyleItem
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.debug.getComponentStackTraceElement
import com.facebook.litho.drawable.ComparableCircleDrawable
import com.facebook.litho.drawable.ComparableColorDrawable
import com.facebook.litho.drawable.ComparableGradientDrawable
import com.facebook.litho.drawable.DrawableUtils
import com.facebook.litho.drawable.inscribedSquare
import com.facebook.litho.eventHandler
import com.facebook.litho.eventHandlerWithReturn
import com.facebook.litho.utils.VersionedAndroidApis
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.ResourceCache
import com.facebook.rendercore.ResourceResolver
import com.facebook.rendercore.px
import com.facebook.rendercore.utils.areObjectsEquivalent
import com.facebook.yoga.YogaEdge
import kotlin.math.ceil

/** Enums for [ObjectStyleItem]. */
@PublishedApi
internal enum class ObjectField : StyleItemField {
  BACKGROUND,
  CLICKABLE,
  CLIP_CHILDREN,
  DEFAULT_FOCUS_HIGHLIGHT_ENABLED,
  CLIP_TO_OUTLINE,
  DUPLICATE_CHILDREN_STATES,
  DUPLICATE_PARENT_STATE,
  FOCUSABLE,
  FOREGROUND,
  ON_CLICK,
  ADDITIONAL_ON_CLICK,
  ON_FOCUS_CHANGED,
  ON_INTERCEPT_TOUCH,
  ON_LONG_CLICK,
  ON_TOUCH,
  OUTLINE_PROVIDER,
  SELECTED,
  STATE_LIST_ANIMATOR,
  TEST_KEY,
  TRANSITION_NAME,
  WRAP_IN_VIEW,
  VIEW_ID,
  VIEW_TAG,
  VIEW_TAGS,
  ENABLED,
  KEYBOARD_NAVIGATION_CLUSTER,
  ADD_TOUCH_EXCLUSION_ZONE,
  TOOLTIP_TEXT,
  LAYER_TYPE,
  RENDER_EFFECT,
  COMPONENT_TAG,
}

/** Enums for [FloatStyleItem]. */
@PublishedApi
internal enum class FloatField : StyleItemField {
  ALPHA,
  ROTATION,
  ROTATION_X,
  ROTATION_Y,
  SCALE,
}

/** Enums for [DimenStyleItem]. */
@PublishedApi
internal enum class DimenField : StyleItemField {
  TOUCH_EXPANSION_START,
  TOUCH_EXPANSION_TOP,
  TOUCH_EXPANSION_END,
  TOUCH_EXPANSION_BOTTOM,
  TOUCH_EXPANSION_LEFT,
  TOUCH_EXPANSION_RIGHT,
  TOUCH_EXPANSION_HORIZONTAL,
  TOUCH_EXPANSION_VERTICAL,
  TOUCH_EXPANSION_ALL,
  ELEVATION,
}

/** Enums for [IntStyleItem]. */
@PublishedApi
internal enum class IntField : StyleItemField {
  Z_INDEX,
}

/**
 * Background style item that carries the background's padding, read once when the [Style] is
 * created. The same [Drawable] instance is shared by every resolve of the owning Component, which
 * can run concurrently on different threads, so the drawable must not be queried during resolve.
 */
@DataClassGenerate
internal data class BackgroundStyleItem(
    override val value: Drawable?,
    private val paddingFromBackground: Rect?,
) : StyleItem<Drawable?> {
  override val field: ObjectField = ObjectField.BACKGROUND

  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    commonProps.background(value, paddingFromBackground)
  }
}

/** Common style item for all object styles. See note on [DimenField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class ObjectStyleItem(override val field: ObjectField, override val value: Any?) :
    StyleItem<Any?> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      ObjectField.BACKGROUND -> commonProps.background(value as Drawable?)
      ObjectField.CLICKABLE -> commonProps.clickable(value as Boolean)
      ObjectField.CLIP_CHILDREN -> commonProps.clipChildren(value as Boolean)
      ObjectField.DEFAULT_FOCUS_HIGHLIGHT_ENABLED ->
          // value is always a non-null Boolean here (set by Style.defaultFocusHighlightEnabled).
          commonProps.defaultFocusHighlightEnabled(checkNotNull(value) as Boolean)
      ObjectField.CLIP_TO_OUTLINE -> commonProps.clipToOutline(value as Boolean)
      ObjectField.DUPLICATE_CHILDREN_STATES -> commonProps.duplicateChildrenStates(value as Boolean)
      ObjectField.DUPLICATE_PARENT_STATE -> commonProps.duplicateParentState(value as Boolean)
      ObjectField.FOCUSABLE -> commonProps.focusable(value as Boolean)
      ObjectField.FOREGROUND -> commonProps.foreground(value as Drawable?)
      ObjectField.ON_FOCUS_CHANGED -> {
        val focusChangedHandler =
            if (value != null) eventHandler(value as (FocusChangedEvent) -> Unit, null, context)
            else null
        commonProps.focusChangeHandler(focusChangedHandler)
      }
      ObjectField.ON_CLICK -> {
        val clickHandler =
            if (value != null) eventHandler(value as (ClickEvent) -> Unit, null, context) else null
        commonProps.clickHandler(clickHandler)
      }
      ObjectField.ADDITIONAL_ON_CLICK -> {
        (value as? ClickListenerHolder)?.let { commonProps.addClickListener(it) }
      }
      ObjectField.ON_LONG_CLICK -> {
        val longClickHandler =
            if (value != null) eventHandlerWithReturn(value as ((LongClickEvent) -> Boolean))
            else null
        commonProps.longClickHandler(longClickHandler)
      }
      ObjectField.ON_INTERCEPT_TOUCH -> {
        val interceptTouchHandler =
            if (value != null) eventHandlerWithReturn(value as ((InterceptTouchEvent) -> Boolean))
            else null
        commonProps.interceptTouchHandler(interceptTouchHandler)
      }
      ObjectField.ON_TOUCH -> {
        val touchHandler =
            if (value != null) eventHandler(value as ((TouchEvent) -> Unit), null, context)
            else null
        commonProps.touchHandler(touchHandler)
      }
      ObjectField.SELECTED -> commonProps.selected(value as Boolean)
      ObjectField.STATE_LIST_ANIMATOR -> commonProps.stateListAnimator(value as StateListAnimator?)
      ObjectField.TEST_KEY -> commonProps.testKey(value as String?)
      ObjectField.TRANSITION_NAME -> commonProps.transitionName(value as String?)
      ObjectField.WRAP_IN_VIEW -> commonProps.wrapInView()
      ObjectField.VIEW_ID -> commonProps.viewId(value as Int)
      ObjectField.VIEW_TAG -> commonProps.viewTag(value)
      ObjectField.VIEW_TAGS -> commonProps.viewTags(value as SparseArray<Any>)
      ObjectField.OUTLINE_PROVIDER -> commonProps.outlineProvider(value as ViewOutlineProvider?)
      ObjectField.ENABLED -> commonProps.enabled(value as Boolean)
      ObjectField.KEYBOARD_NAVIGATION_CLUSTER ->
          commonProps.keyboardNavigationCluster(value as Boolean)
      ObjectField.ADD_TOUCH_EXCLUSION_ZONE ->
          commonProps.addSystemGestureExclusionZone(value as (Rect) -> Rect)
      ObjectField.TOOLTIP_TEXT -> commonProps.tooltipText(value as String?)
      ObjectField.LAYER_TYPE -> {
        val (layerType, paint) = value as Pair<Int, Paint?>
        commonProps.layerType(layerType, paint)
      }
      ObjectField.RENDER_EFFECT -> commonProps.renderEffect(value as LithoRenderEffect?)
      ObjectField.COMPONENT_TAG -> commonProps.componentTag(value)
    }
  }
}

/** Common style item for all float styles. See note on [FloatField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class FloatStyleItem(override val field: FloatField, override val value: Float) :
    StyleItem<Float> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      FloatField.ALPHA -> commonProps.alpha(value)
      FloatField.ROTATION -> commonProps.rotation(value)
      FloatField.ROTATION_X -> commonProps.rotationX(value)
      FloatField.ROTATION_Y -> commonProps.rotationY(value)
      FloatField.SCALE -> commonProps.scale(value)
    }
  }
}

/** Common style item for all float styles. See note on [FloatField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class DimenStyleItem(override val field: DimenField, override val value: Dimen) :
    StyleItem<Dimen> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    val pixelValue = value.toPixels(context.resourceResolver)
    when (field) {
      DimenField.TOUCH_EXPANSION_START -> commonProps.touchExpansionPx(YogaEdge.START, pixelValue)
      DimenField.TOUCH_EXPANSION_TOP -> commonProps.touchExpansionPx(YogaEdge.TOP, pixelValue)
      DimenField.TOUCH_EXPANSION_END -> commonProps.touchExpansionPx(YogaEdge.END, pixelValue)
      DimenField.TOUCH_EXPANSION_BOTTOM -> commonProps.touchExpansionPx(YogaEdge.BOTTOM, pixelValue)
      DimenField.TOUCH_EXPANSION_LEFT -> commonProps.touchExpansionPx(YogaEdge.LEFT, pixelValue)
      DimenField.TOUCH_EXPANSION_RIGHT -> commonProps.touchExpansionPx(YogaEdge.RIGHT, pixelValue)
      DimenField.TOUCH_EXPANSION_HORIZONTAL ->
          commonProps.touchExpansionPx(YogaEdge.HORIZONTAL, pixelValue)
      DimenField.TOUCH_EXPANSION_VERTICAL ->
          commonProps.touchExpansionPx(YogaEdge.VERTICAL, pixelValue)
      DimenField.TOUCH_EXPANSION_ALL -> commonProps.touchExpansionPx(YogaEdge.ALL, pixelValue)
      DimenField.ELEVATION -> commonProps.shadowElevationPx(pixelValue.toFloat())
    }
  }
}

/** Common style item for all int styles. See note on [IntField] about this pattern. */
@PublishedApi
@DataClassGenerate
internal data class IntStyleItem(override val field: IntField, override val value: Int) :
    StyleItem<Int> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      IntField.Z_INDEX -> commonProps.zIndex(value)
    }
  }
}

/**
 * Sets an alpha on the View this Component mounts to. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setAlpha]
 */
fun Style.alpha(alpha: Float): Style {
  maybeLogZeroAlpha(alpha)
  return if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
    this +
        Style.onBindViewWithDescription({ "alpha" }, alpha) { content ->
          content.alpha = alpha
          onUnbind { content.alpha = 1f }
        }
  } else {
    this + FloatStyleItem(FloatField.ALPHA, alpha)
  }
}

fun maybeLogZeroAlpha(alpha: Float) {
  if (ComponentsConfiguration.isZeroAlphaLoggingEnabled && alpha <= 0) {
    DebugInfoReporter.report(category = "ZeroAlphaComponent") {
      val attribution = getComponentStackTraceElement()
      val component = "<cls>${attribution?.className ?: "nothing"}</cls>"
      this["isSpec"] = false
      this["component"] = component
      this["location"] = "$component:${attribution?.lineNumber ?: "nothing"}"
    }
  }
}

/**
 * Sets a background on the View this Component mounts to. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 *
 * The background's padding is read once, when this Style is created, and applied as padding on
 * every layout. Padding that depends on the drawable's later state (e.g. the layout direction of an
 * auto-mirrored drawable, which is set at mount) is not reflected; create a new Style to pick it
 * up.
 *
 * See [android.view.View.setBackground]
 */
fun Style.background(background: Drawable?): Style {
  if (!ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
    return this + BackgroundStyleItem(background, background?.let(DrawableUtils::getPaddingOrNull))
  }
  var style = viewBinder(BackgroundBinder, background)
  if (background != null) {
    val paddingRect = DrawableUtils.getPaddingOrNull(background)
    if (paddingRect != null) {
      style =
          style +
              (if (paddingRect.left > 0)
                  CoreDimenStyleItem(CoreDimenField.PADDING_LEFT, paddingRect.left.px)
              else null) +
              (if (paddingRect.top > 0)
                  CoreDimenStyleItem(CoreDimenField.PADDING_TOP, paddingRect.top.px)
              else null) +
              (if (paddingRect.right > 0)
                  CoreDimenStyleItem(CoreDimenField.PADDING_RIGHT, paddingRect.right.px)
              else null) +
              (if (paddingRect.bottom > 0)
                  CoreDimenStyleItem(CoreDimenField.PADDING_BOTTOM, paddingRect.bottom.px)
              else null)
    }
  }
  return style
}

/**
 * Sets ColorDrawable of the given color as a background on the View this Component mounts to.
 * Setting this property will cause the Component to be represented as a View at mount time if it
 * wasn't going to already.
 *
 * See [android.view.View.setBackgroundColor]
 */
fun Style.backgroundColor(@ColorInt backgroundColor: Int): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      viewBinder(BackgroundBinder, ColorBackground(backgroundColor))
    } else {
      this +
          ObjectStyleItem(ObjectField.BACKGROUND, ComparableColorDrawable.create(backgroundColor))
    }

fun Style.backgroundColor(@ColorInt backgroundColor: Long): Style =
    backgroundColor(backgroundColor.toInt())

/**
 * Draws a filled background of the given [color] in the given [shape] on the View this Component
 * mounts to, without clipping its children (for [Shape.Circle] and [Shape.RoundedRectangle]; other
 * shapes fall back to [clip], see below).
 *
 * Unlike [clip] — which uses [android.view.View.setClipToOutline] and therefore also clips children
 * — this paints a drawable shaped like [shape]. Use it for filled rounded or circular surfaces; use
 * [clip] when you need to clip content (e.g. images) to a shape.
 *
 * [Shape.Circle] and [Shape.RoundedRectangle] are drawn exactly on all API levels, matching the
 * region [clip] would use. Any other [Shape] only exposes a [ViewOutlineProvider], so it falls back
 * to [backgroundColor] + [clip], which does clip children.
 */
fun Style.background(@ColorInt color: Int, shape: Shape): Style =
    if (shape !is PaintableShape) {
      backgroundColor(color).clip(shape)
    } else if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      viewBinder(BackgroundBinder, ShapeBackground(color, shape))
    } else {
      this + BackgroundShapeStyleItem(BackgroundShapeStyleItemParams(color, shape))
    }

fun Style.background(@ColorInt color: Long, shape: Shape): Style = background(color.toInt(), shape)

/**
 * Sets [View.setBackground] for the static [background] and [backgroundColor] Styles when
 * [ComponentsConfiguration.useViewStyleBinders] is on. Their shared binder key retains the last
 * background model at the first background's insertion position. Custom binders and dynamic
 * background Styles write independently and may run after this binder. Caller-provided drawables
 * retain their mutable state when rebound.
 */
private object BackgroundBinder : RenderUnit.Binder<Any?, View, Any> {
  override val description: String
    get() = "background"

  override fun shouldUpdate(
      currentModel: Any?,
      newModel: Any?,
      currentLayoutData: Any?,
      nextLayoutData: Any?,
  ): Boolean = !areObjectsEquivalent(currentModel, newModel)

  override fun bind(context: Context, content: View, model: Any?, layoutData: Any?): Any? {
    content.background =
        when (model) {
          is ColorBackground -> ComparableColorDrawable.create(model.color)
          is ShapeBackground ->
              model.shape.createBackground(
                  model.color,
                  ResourceResolver(
                      context,
                      ResourceCache.getLatest(context.resources.configuration),
                  ),
              )
          else -> model as Drawable?
        }
    return null
  }

  override fun unbind(
      context: Context,
      content: View,
      model: Any?,
      layoutData: Any?,
      bindData: Any?,
  ) {
    content.background = null
  }
}

@DataClassGenerate private data class ColorBackground(@ColorInt val color: Int)

@DataClassGenerate
private data class ShapeBackground(@ColorInt val color: Int, val shape: PaintableShape)

/**
 * Sets if the View this Component mounts to should be clickable. Setting this property will cause
 * the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setClickable]
 */
fun Style.clickable(isClickable: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "clickable" }, isClickable) { content ->
            val originalClickable = content.isClickable
            content.isClickable = isClickable
            onUnbind { content.isClickable = originalClickable }
          }
    } else {
      this + ObjectStyleItem(ObjectField.CLICKABLE, isClickable)
    }

/**
 * Wraps the given [block] with [ComponentHost.setSafeViewModificationsEnabled] if the receiver is a
 * [ComponentHost]. This mirrors the wrapping done in [ViewAttributes] for the legacy path and
 * ensures that binder-driven style mutations do not trigger
 * [ComponentHost.checkUnsafeViewModification].
 */
@PublishedApi
internal inline fun View.withSafeComponentHostModification(block: () -> Unit) {
  val host = this as? ComponentHost
  if (host != null) host.setSafeViewModificationsEnabled(true)
  try {
    block()
  } finally {
    if (host != null) host.setSafeViewModificationsEnabled(false)
  }
}

/**
 * Sets if the View this Component mounts to should be enabled. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setClickable]
 */
fun Style.enabled(isEnabled: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "enabled" }, isEnabled) { content ->
            val originalEnabled = content.isEnabled
            content.withSafeComponentHostModification { content.isEnabled = isEnabled }
            onUnbind {
              content.withSafeComponentHostModification { content.isEnabled = originalEnabled }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.ENABLED, isEnabled)
    }

/**
 * Setting this to false allows child views of this view to draw outside its bounds, overriding the
 * default behavior. It only applies to direct children. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 */
fun Style.clipChildren(clipChildren: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "clipChildren" }, clipChildren) { content ->
            val originalClipChildren = (content as? ViewGroup)?.clipChildren
            if (content is ViewGroup) {
              content.clipChildren = clipChildren
            }
            onUnbind {
              if (content is ViewGroup && originalClipChildren != null) {
                content.clipChildren = originalClipChildren
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.CLIP_CHILDREN, clipChildren)
    }

/**
 * Sets whether this View should have a default focus highlight when it gets focus but doesn't have
 * R.attr.state_focused defined in its background. Setting this property will cause the Component to
 * be represented as a View at mount time if it wasn't already. Only effective on API 26+ (Android
 * O); a no-op on older API levels.
 *
 * See [android.view.View.setDefaultFocusHighlightEnabled]
 */
fun Style.defaultFocusHighlightEnabled(defaultFocusHighlightEnabled: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription(
              { "defaultFocusHighlightEnabled" },
              defaultFocusHighlightEnabled,
          ) { content ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              VersionedAndroidApis.O.setDefaultFocusHighlightEnabled(
                  content,
                  defaultFocusHighlightEnabled,
              )
            }
            onUnbind {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VersionedAndroidApis.O.setDefaultFocusHighlightEnabled(content, true)
              }
            }
          }
    } else {
      this +
          ObjectStyleItem(
              ObjectField.DEFAULT_FOCUS_HIGHLIGHT_ENABLED,
              defaultFocusHighlightEnabled,
          )
    }

/**
 * Sets whether the View's Outline should be used to clip the contents of the View. Setting this
 * property will cause the Component to be represented as a View at mount time if it wasn't going to
 * already.
 *
 * See [android.view.View.setClipToOutline]
 */
fun Style.clipToOutline(clipToOutline: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "clipToOutline" }, clipToOutline) { content ->
            content.clipToOutline = clipToOutline
            onUnbind { content.clipToOutline = false }
          }
    } else {
      this + ObjectStyleItem(ObjectField.CLIP_TO_OUTLINE, clipToOutline)
    }

/**
 * Sets whether this ViewGroup's drawable states also include its children's drawable states. This
 * is used, for example, to make a group appear to be focused when its child EditText or button is
 * focused. Setting this property will cause the Component to be represented as a View at mount time
 * if it wasn't going to already.
 *
 * See [android.view.ViewGroup.setAddStatesFromChildren]
 */
fun Style.duplicateChildrenStates(duplicateChildrenStates: Boolean): Style =
    this + ObjectStyleItem(ObjectField.DUPLICATE_CHILDREN_STATES, duplicateChildrenStates)

/**
 * Sets whether the View gets its drawable state (focused, pressed, etc.) from its direct parent
 * rather than from itself. Setting this property will cause the Component to be represented as a
 * View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setDuplicateParentStateEnabled]
 */
fun Style.duplicateParentState(duplicateParentState: Boolean): Style =
    this + ObjectStyleItem(ObjectField.DUPLICATE_PARENT_STATE, duplicateParentState)

/**
 * Sets an elevation on the View this Component mounts to. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setElevation]
 */
fun Style.elevation(elevation: Dimen): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "elevation" }, elevation) { content ->
            val elevationPx =
                elevation.toFloatPixels(
                    ResourceResolver(
                        content.context,
                        ResourceCache.getLatest(content.resources.configuration),
                    ),
                )
            if (elevationPx != 0f) {
              ViewCompat.setElevation(content, elevationPx)
            }
            onUnbind {
              if (elevationPx != 0f) {
                ViewCompat.setElevation(content, 0f)
              }
            }
          }
    } else {
      this + DimenStyleItem(DimenField.ELEVATION, elevation)
    }

/**
 * Defines the drawing order for sibling components within the same container. It only orders the
 * drawing of the children within their immediate container. Components with higher order values are
 * drawn on top of components with lower values. Components with the same drawing order are drawn in
 * their natural mount order. Setting this style will wrap any non-view content in a View.
 *
 * @param order The zIndex index.
 */
internal fun Style.zIndex(order: Int): Style =
    this + wrapInView() + IntStyleItem(IntField.Z_INDEX, order)

/**
 * Setting this property will cause the Component to be focusable. Setting this property will cause
 * the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setFocusable]
 */
fun Style.focusable(isFocusable: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "focusable" }, isFocusable) { content ->
            val originalFocusable =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  content.focusable
                } else {
                  if (content.isFocusable) View.FOCUSABLE else View.NOT_FOCUSABLE
                }
            content.isFocusable = isFocusable
            onUnbind {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                content.focusable = originalFocusable
              } else {
                content.isFocusable = originalFocusable == View.FOCUSABLE
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.FOCUSABLE, isFocusable)
    }

/**
 * Sets a foreground on the View this Component mounts to. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setForeground]
 */
fun Style.foreground(foreground: Drawable?): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "foreground" }, foreground) { content ->
            if (foreground != null) {
              check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ("MountState has a ViewNodeInfo with foreground however " +
                    "the current Android version doesn't support foreground on Views")
              }
              //noinspection AndroidLintNewApi
              content.foreground = foreground
            }
            onUnbind {
              if (foreground != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //noinspection AndroidLintNewApi
                content.foreground = null
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.FOREGROUND, foreground)
    }

/**
 * Sets ColorDrawable of the given color as a background on the View this Component mounts to.
 * Setting this property will cause the Component to be represented as a View at mount time if it
 * wasn't going to already.
 *
 * See [android.view.View.setForeground]
 */
fun Style.foregroundColor(@ColorInt foregroundColor: Int): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "foreground" }, foregroundColor) { content ->
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              ("MountState has a ViewNodeInfo with foreground however " +
                  "the current Android version doesn't support foreground on Views")
            }
            //noinspection AndroidLintNewApi
            content.foreground = ComparableColorDrawable.create(foregroundColor)
            onUnbind {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //noinspection AndroidLintNewApi
                content.foreground = null
              }
            }
          }
    } else {
      this +
          ObjectStyleItem(ObjectField.FOREGROUND, ComparableColorDrawable.create(foregroundColor))
    }

/**
 * Sets a listener that will invoke the given lambda when this Component's focus changes. Setting
 * this property will cause the Component to be represented as a View at mount time if it wasn't
 * going to already.
 *
 * See [android.view.View.OnFocusChangeListener]
 */
fun Style.onFocusedChanged(action: (FocusChangedEvent) -> Unit): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "focus-change-handler" }, action) { content ->
            content.withSafeComponentHostModification {
              content.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                val event = FocusChangedEvent()
                event.view = view
                event.hasFocus = hasFocus
                action(event)
              }
            }
            onUnbind {
              content.withSafeComponentHostModification { content.onFocusChangeListener = null }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.ON_FOCUS_CHANGED, action)
    }

/**
 * Sets a listener that will invoke the given lambda when this Component's focus changes but only if
 * [enabled] is true. If enabled, setting this property will cause the Component to be represented
 * as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.OnFocusChangeListener]
 */
fun Style.onFocusedChanged(
    enabled: Boolean,
    action: (FocusChangedEvent) -> Unit,
): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      if (enabled) onFocusedChanged(action) else this
    } else {
      this + ObjectStyleItem(ObjectField.ON_FOCUS_CHANGED, if (enabled) action else null)
    }

/**
 * Sets a listener that will invoke the given lambda when this Component is clicked. Setting this
 * property will cause the Component to be represented as a View at mount time if it wasn't going to
 * already.
 */
// Implementation note: click handlers (both [onClick] and [additionalOnClick]) always route
// through `CommonProps`, not the `onBindViewWithDescription` binder path. Independent binders run
// in non-deterministic order at bind time, so the "new primary clears additionals" rule would not
// hold reliably if binders were used.
fun Style.onClick(action: (ClickEvent) -> Unit): Style =
    this + ObjectStyleItem(ObjectField.ON_CLICK, action)

/**
 * Adds an additional click listener identified by [key] that fires alongside any primary handler
 * installed via [onClick] or by the underlying component's `CommonProps.clickHandler`. Multiple
 * additional listeners with distinct keys can coexist on the same view and all fire on click in
 * insertion order.
 *
 * Adding another `Style.onClick { … }` to the same style chain after this `additionalOnClick`
 * clears all additional listeners registered before it — matching the "last `onClick` wins"
 * semantics of plain `Style.onClick`. Note: this is about chain composition at render time, not
 * about runtime click dispatch.
 *
 * [key] must be a stable `R.id` value — using the same key for two registrations on the same view
 * throws. Namespace keys per call-site (declare them in your module's `ids.xml`).
 *
 * Setting this property will cause the Component to be represented as a View at mount time if it
 * wasn't going to already.
 */
fun Style.additionalOnClick(@IdRes key: Int, onClick: (ClickEvent) -> Unit): Style =
    this + ObjectStyleItem(ObjectField.ADDITIONAL_ON_CLICK, ClickListenerHolder(key, onClick))

/**
 * Sets a listener that will invoke the given lambda when this Component is clicked but only if
 * [enabled] is true. If enabled, setting this property will cause the Component to be represented
 * as a View at mount time if it wasn't going to already.
 */
fun Style.onClick(enabled: Boolean, action: (ClickEvent) -> Unit): Style =
    this + ObjectStyleItem(ObjectField.ON_CLICK, if (enabled) action else null)

/**
 * Sets a listener that will invoke the given lambda when key event is received by this Component.
 * Setting this property will cause the Component to be represented as a View at mount time if it
 * wasn't going to already.
 *
 * @param listener lambda that will be invoked when key event is received by this Component. The
 *   lambda parameters are: the view the key has been dispatched to, the keyCode for the physical
 *   key that was pressed event, and the KeyEvent object containing full information about the
 *   event.
 *
 * See [android.view.View.OnKeyListener]
 */
inline fun Style.onKeyEvent(noinline listener: (View, Int, KeyEvent) -> Boolean): Style =
    this +
        Style.onBindViewWithDescription({ "on-key-event-listener" }, listener) { content ->
          if (content is ComponentHost) {
            content.componentKeyListener = listener
          } else {
            content.setOnKeyListener(listener)
          }
          onUnbind {
            if (content is ComponentHost) {
              content.componentKeyListener = null
            } else {
              content.setOnKeyListener(null)
            }
          }
        }

/**
 * Sets a listener that will invoke the given lambda when this Component is long clicked. Setting
 * this property will cause the Component to be represented as a View at mount time if it wasn't
 * going to already.
 */
fun Style.onLongClick(action: (LongClickEvent) -> Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "long-click-handler" }, action) { content ->
            val wasLongClickable = content.isLongClickable
            if (content is ComponentHost) {
              var listener = content.componentLongClickListener
              if (listener == null) {
                listener = ComponentLongClickListener()
                content.componentLongClickListener = listener
              }
              listener.eventHandler = eventHandlerWithReturn(action)
            } else {
              content.setOnLongClickListener { view ->
                val event = LongClickEvent()
                event.view = view
                action(event)
              }
            }
            content.isLongClickable = true
            onUnbind {
              if (content is ComponentHost) {
                content.componentLongClickListener?.eventHandler = null
              } else {
                content.setOnLongClickListener(null)
              }
              content.isLongClickable = wasLongClickable
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.ON_LONG_CLICK, action)
    }

/**
 * Sets a listener that will invoke the given lambda when this Component is long clicked but only if
 * [enabled] is true. If enabled, setting this property will cause the Component to be represented
 * as a View at mount time if it wasn't going to already.
 */
fun Style.onLongClick(
    enabled: Boolean,
    action: (LongClickEvent) -> Boolean,
): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      if (enabled) onLongClick(action) else this
    } else {
      this + ObjectStyleItem(ObjectField.ON_LONG_CLICK, if (enabled) action else null)
    }

/**
 * Sets a listener that will invoke the given lambda when this Component is touched. Setting this
 * property will cause the Component to be represented as a View at mount time if it wasn't going to
 * already.
 */
fun Style.onTouch(action: (TouchEvent) -> Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "touch-handler" }, action) { content ->
            if (content is ComponentHost) {
              var listener = content.componentTouchListener
              if (listener == null) {
                listener = ComponentTouchListener()
                content.componentTouchListener = listener
              }
              listener.eventHandler = eventHandlerWithReturn(action)
            } else {
              content.setOnTouchListener { view, motionEvent ->
                val event = TouchEvent()
                event.view = view
                event.motionEvent = motionEvent
                action(event)
              }
            }
            onUnbind {
              if (content is ComponentHost) {
                content.componentTouchListener?.eventHandler = null
              } else {
                content.setOnTouchListener(null)
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.ON_TOUCH, action)
    }

/**
 * Sets a listener that will invoke the given lambda when this Component is touched but only if
 * [enabled] is true. If enabled, setting this property will cause the Component to be represented
 * as a View at mount time if it wasn't going to already.
 */
fun Style.onTouch(enabled: Boolean, action: (TouchEvent) -> Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      if (enabled) onTouch(action) else this
    } else {
      this + ObjectStyleItem(ObjectField.ON_TOUCH, if (enabled) action else null)
    }

/**
 * Sets a listener that will intercept all touch screen motion events. This allows you to watch
 * events as they are dispatched to your children, and take ownership of the current gesture at any
 * point. Implementations should return true if they intercepted the event and wish to receive
 * subsequent events, and false otherwise. Setting this property will cause the Component to be
 * represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.ViewGroup.onInterceptTouchEvent]
 */
fun Style.onInterceptTouch(action: (InterceptTouchEvent) -> Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "intercept-touch-handler" }, action) { content ->
            if (content is ComponentHost) {
              content.onInterceptTouchEventHandler = eventHandlerWithReturn(action)
            }
            onUnbind {
              if (content is ComponentHost) {
                content.onInterceptTouchEventHandler = null
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.ON_INTERCEPT_TOUCH, action)
    }

/**
 * Sets a listener that will intercept all touch screen motion events but only if [enabled] is true.
 * This allows you to watch events as they are dispatched to your children, and take ownership of
 * the current gesture at any point. Implementations should return true if they intercepted the
 * event and wish to receive subsequent events, and false otherwise. If [enabled] is true, setting
 * this property will cause the Component to be represented as a View at mount time if it wasn't
 * going to already.
 *
 * See [android.view.ViewGroup.onInterceptTouchEvent]
 */
fun Style.onInterceptTouch(
    enabled: Boolean,
    action: (InterceptTouchEvent) -> Boolean,
): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      if (enabled) onInterceptTouch(action) else this
    } else {
      this + ObjectStyleItem(ObjectField.ON_INTERCEPT_TOUCH, if (enabled) action else null)
    }

/**
 * Sets a listener that will invoke the given lambda when this Component is hovered on. If the
 * component does not render a view then the framework will render it inside a View.
 */
inline fun Style.onHover(noinline handler: (View, MotionEvent) -> Boolean): Style =
    this +
        Style.onBindViewWithDescription({ "hover-handler" }, handler) { content ->
          content.setOnHoverListener(handler)
          onUnbind { content.setOnHoverListener(null) }
        }

/**
 * Sets the requested frame rate for the View this Component mounts to. Only effective on API 35+
 * (Android 15). On older API levels, this is a no-op.
 *
 * The value is reset to the default frame rate category on unbind.
 *
 * See [android.view.View.setRequestedFrameRate]
 */
fun Style.requestedFrameRate(frameRate: Float): Style {
  // Use hardcoded 35 instead of Build.VERSION_CODES.VANILLA_ICE_CREAM because some consumers
  // (e.g., ARVR) compile against API 34 where that constant does not exist.
  return if (Build.VERSION.SDK_INT >= 35) {
    this +
        Style.onBindViewWithDescription({ "requestedFrameRate" }, frameRate) { content ->
          invokeSetRequestedFrameRate(content, frameRate)
          onUnbind { invokeSetRequestedFrameRate(content, REQUESTED_FRAME_RATE_CATEGORY_DEFAULT) }
        }
  } else {
    this
  }
}

/**
 * Inlined value of `View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT`, which cannot be referenced
 * directly for the same reason [invokeSetRequestedFrameRate] uses reflection. Anything else,
 * including 0f, leaves a recycled View holding a real frame rate vote.
 */
private const val REQUESTED_FRAME_RATE_CATEGORY_DEFAULT: Float = Float.NaN

/**
 * Reflection-based wrapper for View.setRequestedFrameRate (API 35+). Reflection is required because
 * some consumers (e.g., ARVR) compile against API 34 where this method does not exist.
 */
@PublishedApi
internal fun invokeSetRequestedFrameRate(view: View, frameRate: Float) {
  try {
    view.javaClass
        .getMethod("setRequestedFrameRate", Float::class.javaPrimitiveType)
        .invoke(view, frameRate)
  } catch (_: ReflectiveOperationException) {
    // Method unavailable on this API level; ignore.
  }
}

/**
 * Sets the transform pivot of a View (used for scale and rotation transforms) to be centered at the
 * given percentages of the View's width and height. The default pivot point is (50f, 50f).
 *
 * The Component this Style is applied to must render to a View which implements
 * [SupportsPivotTransform]. Rows and Columns both render to ComponentHost which implements this
 * interface. If you need to apply a pivot to a Component that doesn't render to a View that
 * implements this interface, you can either implement this interface in the View it does render to,
 * or wrap this Component in a Row or Column and apply the transform and pivot there instead.
 *
 * Note: Unlike [View.setPivotX] and [View.setPivotY], the value of this pivot is a percentage, not
 * an absolute pixel value.
 *
 * @param pivotXPercent the percentage of the width to use as the pivotX
 * @param pivotYPercent the percentage of the height to use as the pivotY
 * @see SupportsPivotTransform
 * @see android.view.View.setPivotX
 * @see android.view.View.setPivotY
 */
fun Style.pivotPercent(
    @FloatRange(0.0, 100.0) pivotXPercent: Float = 50f,
    @FloatRange(0.0, 100.0) pivotYPercent: Float = 50f,
): Style {
  check(pivotXPercent in 0f..100f && pivotYPercent in 0f..100f) {
    "Pivot values must be between 0 and 100f. Got ($pivotXPercent, $pivotYPercent)."
  }
  val model = Pair(pivotXPercent, pivotYPercent)
  return this +
      Style.onBindViewWithDescription({ "PivotPercent" }, model) { content ->
        check(content is SupportsPivotTransform) { BadPivotClassErrorMessage }
        content.setTransformPivot(model.first, model.second)
        onUnbind { SupportsPivotTransform.resetPivot(content) }
      }
}

/**
 * Sets the degree that this component is rotated around the pivot point. Increasing the value
 * results in clockwise rotation. By default, the pivot point is centered on the component. Setting
 * this property will cause the Component to be represented as a View at mount time if it wasn't
 * going to already.
 *
 * @see android.view.View.setRotation
 * @see pivotPercent to set the pivot point to a percentage of the component's size
 */
fun Style.rotation(rotation: Float): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "rotation" }, rotation) { content ->
            content.rotation = rotation
            onUnbind { content.rotation = 0f }
          }
    } else {
      this + FloatStyleItem(FloatField.ROTATION, rotation)
    }

/**
 * Sets the degree that this component is rotated around the horizontal axis through the pivot
 * point. Setting this property will cause the Component to be represented as a View at mount time
 * if it wasn't going to already.
 *
 * @see android.view.View.setRotationX
 * @see pivotPercent to set the pivot point to a percentage of the component's size
 */
fun Style.rotationX(rotationX: Float): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "rotationX" }, rotationX) { content ->
            content.rotationX = rotationX
            onUnbind { content.rotationX = 0f }
          }
    } else {
      this + FloatStyleItem(FloatField.ROTATION_X, rotationX)
    }

/**
 * Sets the degree that this component is rotated around the vertical axis through the pivot point.
 * Setting this property will cause the Component to be represented as a View at mount time if it
 * wasn't going to already.
 *
 * @see android.view.View.setRotationY
 * @see pivotPercent to set the pivot point to a percentage of the component's size
 */
fun Style.rotationY(rotationY: Float): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "rotationY" }, rotationY) { content ->
            content.rotationY = rotationY
            onUnbind { content.rotationY = 0f }
          }
    } else {
      this + FloatStyleItem(FloatField.ROTATION_Y, rotationY)
    }

/**
 * Sets the scale (scaleX and scaleY) on this component. This is mostly relevant for animations and
 * being able to animate size changes. Otherwise for non-animation usecases, you should use the
 * standard layout properties to control the size of your component. Setting this property will
 * cause the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * @see android.view.View.setScaleX
 * @see android.view.View.setScaleY
 * @see pivotPercent to set the pivot point to a percentage of the component's size
 */
fun Style.scale(scale: Float): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "scale" }, scale) { content ->
            content.scaleX = scale
            content.scaleY = scale
            onUnbind {
              content.scaleX = 1f
              content.scaleY = 1f
            }
          }
    } else {
      this + FloatStyleItem(FloatField.SCALE, scale)
    }

/**
 * Changes the selection state of this Component. Setting this property will cause the Component to
 * be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setSelected]
 */
fun Style.selected(isSelected: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "selected" }, isSelected) { content ->
            val originalSelected = content.isSelected
            content.isSelected = isSelected
            onUnbind { content.isSelected = originalSelected }
          }
    } else {
      this + ObjectStyleItem(ObjectField.SELECTED, isSelected)
    }

/**
 * Attaches the provided StateListAnimator to this Component. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setStateListAnimator]
 */
fun Style.stateListAnimator(stateListAnimator: StateListAnimator?): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "stateListAnimator" }, stateListAnimator) { content ->
            content.stateListAnimator = stateListAnimator
            onUnbind {
              if (content.stateListAnimator != null) {
                content.stateListAnimator.jumpToCurrentState()
                content.stateListAnimator = null
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.STATE_LIST_ANIMATOR, stateListAnimator)
    }

/**
 * Sets testKey on the View this Component mounts to. Setting this property will cause the Component
 * to be represented as a View at mount time if it wasn't going to already.
 */
fun Style.testKey(testKey: String?): Style = this + ObjectStyleItem(ObjectField.TEST_KEY, testKey)

/**
 * Sets Activity transition name on the View this Component mounts to. Setting this property will
 * cause the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setTransitionName]
 */
fun Style.transitionName(transitionName: String?): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "transitionName" }, transitionName) { content ->
            ViewCompat.setTransitionName(content, transitionName)
            onUnbind { ViewCompat.setTransitionName(content, null) }
          }
    } else {
      this + ObjectStyleItem(ObjectField.TRANSITION_NAME, transitionName)
    }

/**
 * Specifies that this Component should be represented as a View when this hierarchy is mounted.
 *
 * By default a Component does not mount any content. The exceptions to this are if:
 * - The Component renders to a [com.facebook.litho.annotations.MountSpec] with View mount content
 * - The Component has styles that require a View (like [background])
 * - The Component has the wrapInView style
 */
fun Style.wrapInView(): Style = this + ObjectStyleItem(ObjectField.WRAP_IN_VIEW, null)

/**
 * Adds a View tag to the [View] this [Component] mounts to. Setting this property will cause the
 * [Component] to be represented as a [View] at mount time if it wasn't going to already.
 *
 * See [android.view.View.setId]
 */
fun Style.viewId(@IdRes viewId: Int): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "viewId" }, viewId) { content ->
            if (viewId != View.NO_ID) {
              content.id = viewId
            }
            onUnbind { content.id = View.NO_ID }
          }
    } else {
      this + ObjectStyleItem(ObjectField.VIEW_ID, viewId)
    }

/**
 * Adds a View tag to the View this Component mounts to. Setting this property will cause the
 * Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setTag]
 */
fun Style.viewTag(viewTag: Any?): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "viewTag" }, viewTag) { content ->
            content.withSafeComponentHostModification { content.tag = viewTag }
            onUnbind { content.withSafeComponentHostModification { content.tag = null } }
          }
    } else {
      this + ObjectStyleItem(ObjectField.VIEW_TAG, viewTag)
    }

/**
 * Sets a component-level tag on this Component, readable via [com.facebook.litho.DebugComponent]'s
 * `componentTag` / `InspectableComponent.getComponentTag` (test / inspection tooling). Unlike
 * [viewTag], this is not an Android `View` tag: it has no mount- or layout-time effect and does not
 * force the Component to be represented as a View.
 */
fun Style.componentTag(componentTag: Any?): Style =
    this + ObjectStyleItem(ObjectField.COMPONENT_TAG, componentTag)

/**
 * Adds a set of View tags to the View this Component mounts to. Setting this property will cause
 * the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * For each Int, Any pair in the given SparseArray, `View.setTag(Int, Any)` will be called. These
 * tags can be retrieved with `View.getTag(Int)`
 *
 * See [android.view.View.setTag]
 */
fun Style.viewTags(viewTags: SparseArray<out Any>): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "viewTags" }, viewTags) { content ->
            if (content is ComponentHost) {
              @Suppress("UNCHECKED_CAST") content.addViewTags(viewTags as SparseArray<Any>)
            } else {
              for (i in 0 until viewTags.size()) {
                content.setTag(viewTags.keyAt(i), viewTags.valueAt(i))
              }
            }
            onUnbind {
              if (content is ComponentHost) {
                content.unsetViewTags()
              } else {
                for (i in 0 until viewTags.size()) {
                  content.setTag(viewTags.keyAt(i), null)
                }
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.VIEW_TAGS, viewTags)
    }

/**
 * Sets a [ViewOutlineProvider] on the View this Component mounts to. Setting this property will
 * cause the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * Note: This only sets the outline provider. To also clip to the outline, use [clipToOutline] or
 * prefer the [clip] function with a [Shape] parameter which handles both.
 *
 * See [android.view.View.setOutlineProvider]
 */
fun Style.clip(outlineProvider: ViewOutlineProvider?): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "outlineProvider" }, outlineProvider) { content ->
            if (outlineProvider != null) {
              content.outlineProvider = outlineProvider
            }
            onUnbind {
              if (outlineProvider != null) {
                content.outlineProvider = ViewOutlineProvider.BACKGROUND
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.OUTLINE_PROVIDER, outlineProvider)
    }

/**
 * Sets a [ViewOutlineProvider] on the View this Component mounts to. Setting this property will
 * cause the Component to be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setOutlineProvider]
 *
 * @deprecated Use [clip] instead for consistency with the Shape-based clipping API.
 */
@Deprecated("Use clip() instead", ReplaceWith("clip(outlineProvider)"))
fun Style.outlineProvider(outlineProvider: ViewOutlineProvider?): Style = clip(outlineProvider)

/** Defines touch Expansion area around the component on a per-edge basis. */
fun Style.touchExpansion(
    all: Dimen? = null,
    horizontal: Dimen? = null,
    vertical: Dimen? = null,
    start: Dimen? = null,
    top: Dimen? = null,
    end: Dimen? = null,
    bottom: Dimen? = null,
    left: Dimen? = null,
    right: Dimen? = null,
): Style =
    this +
        all?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_ALL, it) } +
        horizontal?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_HORIZONTAL, it) } +
        vertical?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_VERTICAL, it) } +
        start?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_START, it) } +
        top?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_TOP, it) } +
        end?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_END, it) } +
        bottom?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_BOTTOM, it) } +
        left?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_LEFT, it) } +
        right?.let { DimenStyleItem(DimenField.TOUCH_EXPANSION_RIGHT, it) }

enum class ShadowStyleField : StyleItemField {
  SHADOW_ITEM,
}

@DataClassGenerate
data class ShadowStyleItemParams(
    val elevation: Dimen,
    val outlineProvider: ViewOutlineProvider,
    @ColorInt val ambientShadowColor: Int,
    @ColorInt val spotShadowColor: Int,
)

@PublishedApi
@DataClassGenerate
internal data class ShadowStyleItem(
    val elevation: Dimen,
    val outlineProvider: ViewOutlineProvider,
    @ColorInt val ambientShadowColor: Int,
    @ColorInt val spotShadowColor: Int,
) : StyleItem<ShadowStyleItemParams> {

  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    commonProps.shadowElevationPx(elevation.toPixels(context.resourceResolver).toFloat())
    commonProps.outlineProvider(outlineProvider)
    commonProps.ambientShadowColor(ambientShadowColor)
    commonProps.spotShadowColor(spotShadowColor)
  }

  override val field: ShadowStyleField = ShadowStyleField.SHADOW_ITEM
  override val value: ShadowStyleItemParams =
      ShadowStyleItemParams(elevation, outlineProvider, ambientShadowColor, spotShadowColor)
}

/**
 * Style for attaching a standard Material Design shadow to a component. Refer to
 * https://material.io/design/environment/light-shadows.html for more information.
 * - **elevation**: Sets the elevation of this component above the surface using
 *   https://developer.android.com/reference/android/view/View#setElevation(float). Larger elevation
 *   values result in larger shadows.
 * - **outlineProvider**: Used to determine the shape of the shadow. If not specified,
 *   https://developer.android.com/reference/android/view/ViewOutlineProvider#BOUNDS will be used to
 *   target the component's bounds.
 * - **ambientShadowColor**: Sets the color of the ambient shadow. Ignored on < API 28 devices. See
 *   https://developer.android.com/reference/android/view/View#setOutlineAmbientShadowColor(int)
 * - **outlineShadowColor**: Sets the color of the spotlight shadow. Ignored on < API 28 devices.
 *   See https://developer.android.com/reference/android/view/View#setOutlineSpotShadowColor(int)
 */
fun Style.shadow(
    elevation: Dimen,
    outlineProvider: ViewOutlineProvider = ViewOutlineProvider.BOUNDS,
    @ColorInt ambientShadowColor: Int = Color.BLACK,
    @ColorInt spotShadowColor: Int = Color.BLACK,
): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription(
              { "shadow" },
              ShadowStyleItemParams(
                  elevation,
                  outlineProvider,
                  ambientShadowColor,
                  spotShadowColor,
              ),
          ) { content ->
            val elevationPx =
                elevation.toFloatPixels(
                    ResourceResolver(
                        content.context,
                        ResourceCache.getLatest(content.resources.configuration),
                    ),
                )
            if (elevationPx != 0f) {
              ViewCompat.setElevation(content, elevationPx)
            }
            content.outlineProvider = outlineProvider
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              VersionedAndroidApis.P.setAmbientShadowColor(content, ambientShadowColor)
              VersionedAndroidApis.P.setSpotShadowColor(content, spotShadowColor)
            }
            onUnbind {
              if (elevationPx != 0f) {
                ViewCompat.setElevation(content, 0f)
              }
              content.outlineProvider = ViewOutlineProvider.BACKGROUND
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (ambientShadowColor != Color.BLACK) {
                  VersionedAndroidApis.P.setAmbientShadowColor(content, Color.BLACK)
                }
                if (spotShadowColor != Color.BLACK) {
                  VersionedAndroidApis.P.setSpotShadowColor(content, Color.BLACK)
                }
              }
            }
          }
    } else {
      this + ShadowStyleItem(elevation, outlineProvider, ambientShadowColor, spotShadowColor)
    }

/**
 * Setting this property will cause the Component to be a keyboard navigation cluster. Setting this
 * property will cause the Component to be represented as a View at mount time if it wasn't going to
 * already.
 *
 * See [android.view.View.setKeyboardNavigationCluster]
 */
fun Style.keyboardNavigationCluster(isKeyboardNavigationCluster: Boolean): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription(
              { "keyboardNavigationCluster" },
              isKeyboardNavigationCluster,
          ) { content ->
            val originalCluster = ViewCompat.isKeyboardNavigationCluster(content)
            ViewCompat.setKeyboardNavigationCluster(content, isKeyboardNavigationCluster)
            onUnbind { ViewCompat.setKeyboardNavigationCluster(content, originalCluster) }
          }
    } else {
      this + ObjectStyleItem(ObjectField.KEYBOARD_NAVIGATION_CLUSTER, isKeyboardNavigationCluster)
    }

/**
 * Excludes a rectangle within the local bounds from the system gesture. After layout, []exclusion]
 * is called to determine the Rect to exclude from the system gesture area.
 *
 * The bounds of the content's location in the layout is passed as passed as exclusion's parameter.
 */
inline fun Style.addSystemGestureExclusionZone(noinline exclusion: (bounds: Rect) -> Rect): Style {
  return this + ObjectStyleItem(ObjectField.ADD_TOUCH_EXCLUSION_ZONE, exclusion)
}

/**
 * Sets the tooltip text which will be displayed in a small popup next to the view.
 *
 * See [android.view.View.setTooltipText]
 */
fun Style.tooltipText(tooltipText: String?): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "tooltipText" }, tooltipText) { content ->
            ViewCompat.setTooltipText(content, tooltipText)
            onUnbind { ViewCompat.setTooltipText(content, null) }
          }
    } else {
      this + ObjectStyleItem(ObjectField.TOOLTIP_TEXT, tooltipText)
    }

/**
 * Specifies the type of layer backing this view. The layer can be [LayerType.LAYER_TYPE_NOT_SET],
 * [LayerType.LAYER_TYPE_NONE], [LayerType.LAYER_TYPE_SOFTWARE] or [LayerType.LAYER_TYPE_HARDWARE].
 *
 * @param layerType The type of layer to use with this view, must be one of
 *   [LayerType.LAYER_TYPE_NOT_SET], [LayerType.LAYER_TYPE_NONE], [LayerType.LAYER_TYPE_SOFTWARE] or
 *   [LayerType.LAYER_TYPE_HARDWARE].
 * @param paint The paint used to compose the layer. This argument is optional and can be null. It
 *   is ignored when the layer type is [LayerType.LAYER_TYPE_NONE].
 * @see [android.view.View.setLayerType]
 */
fun Style.layerType(@LayerType layerType: Int, paint: Paint? = null): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "layerType" }, layerType, paint) { content ->
            val originalLayerType = content.layerType
            if (layerType != LayerType.LAYER_TYPE_NOT_SET) {
              // Guard oversized HARDWARE layers against the hwui "Unable to create layer" abort
              // (see LayerTypeGuard). No-op unless LayerTypeGuard.maxHardwareLayerSizePx is set.
              content.setLayerType(
                  LayerTypeGuard.guardHardwareLayerForSize(content, layerType),
                  paint,
              )
            }
            onUnbind {
              if (layerType != LayerType.LAYER_TYPE_NOT_SET) {
                content.setLayerType(originalLayerType, null)
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.LAYER_TYPE, Pair(layerType, paint))
    }

/**
 * Creates a Blur [LithoRenderEffect] with the specified radius along the x and y axis and applies
 * it to the component.
 *
 * **This style is only supported on Android 12 and above. Usage of this Style on older Android
 * versions will be ignored.**
 *
 * @param radiusX Radius of blur along the X axis in pixels.
 * @param radiusY Radius of blur along the Y axis in pixels.
 * @param edgeTreatment Policy for how to blur content near edges.
 *
 * See [android.view.View.setRenderEffect] and [RenderEffect.createBlurEffect]
 */
fun Style.blur(
    radiusX: Float,
    radiusY: Float,
    edgeTreatment: Shader.TileMode = Shader.TileMode.CLAMP,
): Style {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val blurEffect =
        if (radiusX == 0f && radiusY == 0f) {
          // Creating a blur effect with radiusX and radiusY set to 0f throws an exception.
          null
        } else {
          LithoRenderEffect.Blur(radiusX, radiusY, edgeTreatment)
        }

    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "renderEffect" }, blurEffect) { content ->
            if (blurEffect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              content.setRenderEffect(blurEffect.toRenderEffect())
            }
            onUnbind {
              if (blurEffect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                content.setRenderEffect(null)
              }
            }
          }
    } else {
      this + ObjectStyleItem(ObjectField.RENDER_EFFECT, blurEffect)
    }
  } else {
    this
  }
}

/**
 * Defines a shape for clipping a Component's content via [ViewOutlineProvider].
 *
 * Custom shapes can be created by implementing this interface.
 */
fun interface Shape {
  /** Returns a [ViewOutlineProvider] that defines the clipping region for this shape. */
  fun getOutlineProvider(context: ComponentContext): ViewOutlineProvider

  companion object {
    /** A circle inscribed within the view bounds, using `min(width, height)` as diameter. */
    val Circle: Shape = CircleShape

    /** A rounded rectangle with the specified corner radius applied to all corners. */
    fun RoundedRectangle(cornerRadius: Dimen): Shape = RoundedRectangleShape(cornerRadius)

    /**
     * A rounded rectangle with a separate radius per corner. Start and end follow the layout
     * direction.
     *
     * The clip is exact on API 33+. Before API 33 Android can only clip to a single-radius rounded
     * rectangle, so on those versions the clip is exact only when no corner is rounded, or the
     * rounded corners share one radius and are either all four corners or the two corners of one
     * side (with an approximate elevation shadow). On those versions any other combination applies
     * the smallest non-zero radius to all corners.
     */
    fun RoundedRectangle(
        topStart: Dimen = 0.px,
        topEnd: Dimen = 0.px,
        bottomEnd: Dimen = 0.px,
        bottomStart: Dimen = 0.px,
    ): Shape = PerCornerRoundedRectangleShape(topStart, topEnd, bottomEnd, bottomStart)
  }
}

/**
 * A [Shape] that [background] can paint as well as clip to. Its background and its outline must
 * cover the same region.
 */
internal interface PaintableShape : Shape {
  fun createBackground(@ColorInt color: Int, resourceResolver: ResourceResolver): Drawable
}

internal object CircleShape : ViewOutlineProvider(), PaintableShape {
  override fun getOutlineProvider(context: ComponentContext): ViewOutlineProvider = this

  override fun getOutline(view: View, outline: Outline) {
    val box = Rect(0, 0, view.width, view.height)
    outline.setOval(inscribedSquare(box, box))
  }

  override fun createBackground(color: Int, resourceResolver: ResourceResolver): Drawable =
      ComparableCircleDrawable.create(color)
}

@DataClassGenerate
internal data class RoundedRectangleShape(val cornerRadius: Dimen) : PaintableShape {
  override fun getOutlineProvider(context: ComponentContext): ViewOutlineProvider {
    val cornerRadiusPx = cornerRadius.toPixels(context.resourceResolver).toFloat()
    return RoundedRectangleOutlineProvider(cornerRadiusPx)
  }

  override fun createBackground(color: Int, resourceResolver: ResourceResolver): Drawable {
    val cornerRadiusPx = cornerRadius.toPixels(resourceResolver).toFloat()
    return ComparableGradientDrawable.create().apply {
      setCornerRadius(cornerRadiusPx)
      setColor(color)
    }
  }
}

internal class RoundedRectangleOutlineProvider(private val cornerRadiusPx: Float) :
    ViewOutlineProvider() {
  override fun getOutline(view: View, outline: Outline) {
    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
  }
}

@DataClassGenerate
internal data class PerCornerRoundedRectangleShape(
    val topStart: Dimen,
    val topEnd: Dimen,
    val bottomEnd: Dimen,
    val bottomStart: Dimen,
) : Shape {
  override fun getOutlineProvider(context: ComponentContext): ViewOutlineProvider {
    val resolver = context.resourceResolver
    return PerCornerRoundedRectangleOutlineProvider(
        topStartPx = topStart.toPixels(resolver).toFloat(),
        topEndPx = topEnd.toPixels(resolver).toFloat(),
        bottomEndPx = bottomEnd.toPixels(resolver).toFloat(),
        bottomStartPx = bottomStart.toPixels(resolver).toFloat(),
    )
  }
}

internal class PerCornerRoundedRectangleOutlineProvider(
    private val topStartPx: Float,
    private val topEndPx: Float,
    private val bottomEndPx: Float,
    private val bottomStartPx: Float,
) : ViewOutlineProvider() {
  override fun getOutline(view: View, outline: Outline) {
    setOutline(
        outline,
        view.width,
        view.height,
        view.layoutDirection == View.LAYOUT_DIRECTION_RTL,
    )
  }

  @VisibleForTesting
  internal fun setOutline(outline: Outline, width: Int, height: Int, isRtl: Boolean) {
    val topLeft = (if (isRtl) topEndPx else topStartPx).coerceAtLeast(0f)
    val topRight = (if (isRtl) topStartPx else topEndPx).coerceAtLeast(0f)
    val bottomRight = (if (isRtl) bottomStartPx else bottomEndPx).coerceAtLeast(0f)
    val bottomLeft = (if (isRtl) bottomEndPx else bottomStartPx).coerceAtLeast(0f)
    val rounded = listOf(topLeft, topRight, bottomRight, bottomLeft).filter { it > 0f }
    if (rounded.isEmpty()) {
      outline.setRect(0, 0, width, height)
      return
    }
    val radius = rounded.min()
    val sameRadius = rounded.all { it == radius }
    if (sameRadius && rounded.size == 4) {
      outline.setRoundRect(0, 0, width, height, radius)
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val path = Path()
      path.addRoundRect(
          RectF(0f, 0f, width.toFloat(), height.toFloat()),
          floatArrayOf(
              topLeft,
              topLeft,
              topRight,
              topRight,
              bottomRight,
              bottomRight,
              bottomLeft,
              bottomLeft,
          ),
          Path.Direction.CW,
      )
      outline.setPath(path)
      return
    }
    // Path outlines can't clip before API 33. When only one side is rounded, a single-radius round
    // rect extended past the opposite edge clips exactly; the elevation shadow follows the extended
    // outline, so it is approximate.
    val extension = ceil(radius).toInt()
    when {
      sameRadius && rounded.size == 2 && topLeft > 0f && topRight > 0f ->
          outline.setRoundRect(0, 0, width, height + extension, radius)
      sameRadius && rounded.size == 2 && bottomLeft > 0f && bottomRight > 0f ->
          outline.setRoundRect(0, -extension, width, height, radius)
      sameRadius && rounded.size == 2 && topLeft > 0f && bottomLeft > 0f ->
          outline.setRoundRect(0, 0, width + extension, height, radius)
      sameRadius && rounded.size == 2 && topRight > 0f && bottomRight > 0f ->
          outline.setRoundRect(-extension, 0, width, height, radius)
      else -> outline.setRoundRect(0, 0, width, height, radius)
    }
  }
}

enum class ClipStyleField : StyleItemField {
  CLIP,
}

enum class BackgroundShapeStyleField : StyleItemField {
  BACKGROUND_SHAPE,
}

@DataClassGenerate
internal data class BackgroundShapeStyleItem(
    override val value: BackgroundShapeStyleItemParams,
) : StyleItem<BackgroundShapeStyleItemParams> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    commonProps.background(
        value.shape.createBackground(value.color, context.resourceResolver),
    )
  }

  override val field: BackgroundShapeStyleField = BackgroundShapeStyleField.BACKGROUND_SHAPE
}

@DataClassGenerate
internal data class BackgroundShapeStyleItemParams(
    @ColorInt val color: Int,
    val shape: PaintableShape,
)

@PublishedApi
@DataClassGenerate
internal data class ClipStyleItem(val shape: Shape) : StyleItem<Shape> {
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    commonProps.outlineProvider(shape.getOutlineProvider(context))
    commonProps.clipToOutline(true)
  }

  override val field: ClipStyleField = ClipStyleField.CLIP
  override val value: Shape = shape
}

/**
 * Clips the Component to the specified [Shape]. Setting this property will cause the Component to
 * be represented as a View at mount time if it wasn't going to already.
 *
 * See [android.view.View.setClipToOutline]
 */
fun Style.clip(shape: Shape): Style =
    if (ComponentsConfiguration.defaultInstance.useViewStyleBinders) {
      this +
          Style.onBindViewWithDescription({ "clip-shape" }, shape) { content ->
            val outlineProvider =
                if (shape is ViewOutlineProvider) {
                  shape
                } else {
                  shape.getOutlineProvider(ComponentContext(content.context))
                }
            content.outlineProvider = outlineProvider
            content.clipToOutline = true
            onUnbind {
              content.outlineProvider = ViewOutlineProvider.BACKGROUND
              content.clipToOutline = false
            }
          }
    } else {
      this + ClipStyleItem(shape)
    }
