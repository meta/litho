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

package com.facebook.litho.binders

import android.view.View
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.CommonProps
import com.facebook.litho.ComponentContext
import com.facebook.litho.Style
import com.facebook.litho.StyleItem
import com.facebook.litho.StyleItemField
import com.facebook.rendercore.RenderUnit
import com.facebook.rendercore.RenderUnit.DelegateBinder
import com.facebook.rendercore.RenderUnit.DelegateBinder.Companion.createDelegateBinder
import com.facebook.rendercore.primitives.BindFunc
import com.facebook.rendercore.primitives.BindScope
import com.facebook.rendercore.primitives.UnbindFunc
import com.facebook.rendercore.primitives.binder

/**
 * This [Style] adds a mount callbacks. The [BindFunc] is invoked when the Component is mounted, and
 * it receives a [BindScope] and the content this [Component] rendered to which can be a [View] or
 * [Drawable]. The [BindFunc] must return an [UnbindFunc] and it must undo any mutations made to the
 * [View] in the [BindFunc]. This API can be used to create higher-order Styles.
 *
 * The callbacks are guaranteed to be called on the main thread.
 *
 * The previous and new values of the [deps] are compared to check if the callbacks should be
 * invoked again. If the [deps] are equivalent then the callbacks are not invoked again; if they are
 * not equivalent then [UnbindFunc] is invoked followed by [BindFunc]. To invoke the callbacks only
 * once use [Unit] as [deps].
 *
 * @param deps the dependencies that will be compared to check if the binder should be rerun.
 * @param func the [RenderUnit.Binder] to be used to bind the model to the View content.
 * @param name the name of the binder. This is used for debugging purposes.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onBindWithDescription(
    noinline description: () -> String,
    vararg deps: Any?,
    func: BindFunc<Any>,
): Style =
    this +
        ObjectStyleItem(
            BinderObjectField.MOUNT_BINDER,
            binder(description = description, dep = deps, func = func),
        )

/**
 * This [Style] adds a mount callbacks. The [BindFunc] is invoked when the Component is mounted, and
 * it receives a [BindScope] and the content this [Component] rendered to which can be a [View] or
 * [Drawable]. The [BindFunc] must return an [UnbindFunc] and it must undo any mutations made to the
 * [View] in the [BindFunc]. This API can be used to create higher-order Styles.
 *
 * The callbacks are guaranteed to be called on the main thread.
 *
 * The previous and new values of the [deps] are compared to check if the callbacks should be
 * invoked again. If the [deps] are equivalent then the callbacks are not invoked again; if they are
 * not equivalent then [UnbindFunc] is invoked followed by [BindFunc]. To invoke the callbacks only
 * once use [Unit] as [deps].
 *
 * @param deps the dependencies that will be compared to check if the binder should be rerun.
 * @param func the [RenderUnit.Binder] to be used to bind the model to the View content.
 */
fun Style.onBind(vararg deps: Any?, func: BindFunc<Any>): Style =
    this +
        ObjectStyleItem(
            BinderObjectField.MOUNT_BINDER,
            binder(dep = deps, func = func),
        )

@Deprecated(ON_BIND_NO_DEPS_ERROR, level = DeprecationLevel.ERROR)
@Suppress("unused", "UNUSED_PARAMETER")
fun Style.onBindWithDescription(name: String, func: BindFunc<Any>): Style =
    throw IllegalArgumentException(ON_BIND_NO_DEPS_ERROR)

@Deprecated(ON_BIND_NO_DEPS_ERROR, level = DeprecationLevel.ERROR)
@Suppress("unused", "UNUSED_PARAMETER")
fun Style.onBind(func: BindFunc<Any>): Style = throw IllegalArgumentException(ON_BIND_NO_DEPS_ERROR)

/**
 * Use this [Style] instead of [onBind] to force the binder to be applied on a [View]; for example
 * if a binder needs to be set on a [Row] or [Column] component, or if the binder needs to set
 * common view properties even for components that render a Drawable.
 *
 * This [Style] adds a mount callbacks. The [BindFunc] is invoked when the Component is mounted, and
 * it receives a [BindScope] and the [View] this [Component] rendered to. The [BindFunc] must return
 * an [UnbindFunc] and it must undo any mutations made to the [View] in the [BindFunc]. This API can
 * be used to create higher-order Styles.
 *
 * If the [Component] does not render a view then this [Style] will wrap the content into a [View].
 * The callbacks are guaranteed to be called on the main thread.
 *
 * The previous and new values of the [deps] are compared to check if the callbacks should be
 * invoked again. If the [deps] are equivalent then the callbacks are not invoked again; if they are
 * not equivalent then [UnbindFunc] is invoked followed by [BindFunc]. To invoke the callbacks only
 * once use [Unit] as [deps].
 *
 * @param deps the dependencies that will be compared to check if the binder should be rerun.
 * @param func the [RenderUnit.Binder] to be used to bind the model to the View content.
 * @param name the name of the binder. This is used for debugging purposes.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onBindViewWithDescription(
    noinline description: () -> String,
    vararg deps: Any?,
    func: BindFunc<View>,
): Style =
    this +
        ObjectStyleItem(
            BinderObjectField.VIEW_MOUNT_BINDER,
            binder(description = description, dep = deps, func = func),
        )

/**
 * **Note: Please use [onBindViewWithDescription] instead.**
 *
 * This [Style] adds a mount callbacks. The [BindFunc] is invoked when the Component is mounted, and
 * it receives a [BindScope] and the [View] this [Component] rendered to. The [BindFunc] must return
 * an [UnbindFunc] and it must undo any mutations made to the [View] in the [BindFunc]. This API can
 * be used to create higher-order Styles.
 *
 * If the [Component] does not render a view then this [Style] will wrap the content into a [View].
 * The callbacks are guaranteed to be called on the main thread.
 *
 * The previous and new values of the [deps] are compared to check if the callbacks should be
 * invoked again. If the [deps] are equivalent then the callbacks are not invoked again; if they are
 * not equivalent then [UnbindFunc] is invoked followed by [BindFunc]. To invoke the callbacks only
 * once use [Unit] as [deps].
 *
 * @param deps the dependencies that will be compared to check if the binder should be rerun.
 * @param func the [RenderUnit.Binder] to be used to bind the model to the View content.
 */
fun Style.onBindView(vararg deps: Any?, func: BindFunc<View>): Style =
    this +
        ObjectStyleItem(
            BinderObjectField.VIEW_MOUNT_BINDER,
            binder(dep = deps, func = func),
        )

@Deprecated(ON_BIND_NO_DEPS_ERROR, level = DeprecationLevel.ERROR)
@Suppress("unused", "UNUSED_PARAMETER")
fun Style.onBindViewWithDescription(name: String, func: BindFunc<View>): Style =
    throw IllegalArgumentException(ON_BIND_NO_DEPS_ERROR)

@Deprecated(ON_BIND_NO_DEPS_ERROR, level = DeprecationLevel.ERROR)
@Suppress("unused", "UNUSED_PARAMETER")
fun Style.onBindView(func: BindFunc<View>): Style =
    throw IllegalArgumentException(ON_BIND_NO_DEPS_ERROR)

/**
 * Use this [Style] instead of [onBind] to force the binder to be applied on a wrapped [HostView].
 *
 * This [Style] adds a mount callbacks. The [BindFunc] is invoked when the [HostView] is mounted,
 * and it receives a [BindScope] and the [HostView] this [Component] rendered to. The [BindFunc]
 * must return an [UnbindFunc] and it must undo any mutations made to the [View] in the [BindFunc].
 * This API can be used to create higher-order Styles.
 *
 * Even if the [Component] renders a view this [Style] will force wrap the content into a
 * [HostView]. The callbacks are guaranteed to be called on the main thread.
 *
 * The previous and new values of the [deps] are compared to check if the callbacks should be
 * invoked again. If the [deps] are equivalent then the callbacks are not invoked again; if they are
 * not equivalent then [UnbindFunc] is invoked followed by [BindFunc]. To invoke the callbacks only
 * once use [Unit] as [deps].
 *
 * The model of the [BindScope] receiver is a [ModelWithContext] type and hence can be safely casted
 * during the execution of the [BindFunc].
 *
 * @param deps the dependencies that will be compared to check if the binder should be rerun.
 * @param func the [RenderUnit.Binder] to be used to bind the model to the View content.
 * @param description the name of the binder. This is used for debugging purposes.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Style.onBindHostViewWithDescription(
    noinline description: () -> String,
    vararg deps: Any?,
    func: BindFunc<View>,
): Style =
    this +
        ObjectStyleItem(
            BinderObjectField.HOST_VIEW_MOUNT_BINDER,
            DeferredBinderData(description = description, deps = deps, func = func),
        )

/**
 * Use this [Style] instead of [onBind] to force the binder to be applied on a wrapped [HostView].
 *
 * This [Style] adds a mount callbacks. The [BindFunc] is invoked when the [HostView] is mounted,
 * and it receives a [BindScope] and the [HostView] this [Component] rendered to. The [BindFunc]
 * must return an [UnbindFunc] and it must undo any mutations made to the [View] in the [BindFunc].
 * This API can be used to create higher-order Styles.
 *
 * Even if the [Component] renders a view this [Style] will force wrap the content into a
 * [HostView]. The callbacks are guaranteed to be called on the main thread.
 *
 * The previous and new values of the [deps] are compared to check if the callbacks should be
 * invoked again. If the [deps] are equivalent then the callbacks are not invoked again; if they are
 * not equivalent then [UnbindFunc] is invoked followed by [BindFunc]. To invoke the callbacks only
 * once use [Unit] as [deps].
 *
 * The model of the [BindScope] receiver is a [ModelWithContext] type and hence can be safely casted
 * during the execution of the [BindFunc].
 *
 * @param deps the dependencies that will be compared to check if the binder should be rerun.
 * @param func the [RenderUnit.Binder] to be used to bind the model to the View content.
 */
fun Style.onBindHostView(vararg deps: Any?, func: BindFunc<View>): Style =
    this +
        ObjectStyleItem(
            BinderObjectField.HOST_VIEW_MOUNT_BINDER,
            DeferredBinderData(description = null, deps = deps, func = func),
        )

@Deprecated(ON_BIND_NO_DEPS_ERROR, level = DeprecationLevel.ERROR)
@Suppress("unused", "UNUSED_PARAMETER")
fun Style.onBindHostViewWithDescription(name: String, func: BindFunc<View>): Style =
    throw IllegalArgumentException(ON_BIND_NO_DEPS_ERROR)

@Deprecated(ON_BIND_NO_DEPS_ERROR, level = DeprecationLevel.ERROR)
@Suppress("unused", "UNUSED_PARAMETER")
fun Style.onBindHostView(func: BindFunc<View>): Style =
    throw IllegalArgumentException(ON_BIND_NO_DEPS_ERROR)

/**
 * Creates a [Style] which will apply the given [RenderUnit.Binder] to the [View] rendered by the
 * Component the Style is added to. This abstraction can be used to create higher-level Styles which
 * can apply some property generically to any type of Component.
 *
 * The usage of this binder will guarantee that there will be an associated View to it by calling
 * [Style.wrapInView].
 *
 * Notes for implementing a [RenderUnit.Binder]:
 *
 * The binder's [RenderUnit.Binder.bind] will be called by the framework with the current model to
 * bind the model to the View content, and [RenderUnit.Binder.unbind] will be called with the
 * current model to unbind the model from the View content. When the model changes in a new render
 * pass, [RenderUnit.Binder.shouldUpdate] will be called with the old and new models before
 * unbind/bind: if it returns true, unbind(oldModel)+bind(newModel) will be called. Otherwise, the
 * update will be skipped.
 *
 * In some cases, you may want your binder's bind to be called only the first time the content is
 * mounted, and unbind called the last time it is unmounted before going offscreen. In that case,
 * you can unconditionally return false from [RenderUnit.Binder.shouldUpdate].
 *
 * Generally speaking, a [RenderUnit.Binder] should be static and unchanging, while the model may
 * change between renders.
 *
 * @param binder the [RenderUnit.Binder] to be used to bind the model to the View content.
 * @param model the current model to bind.
 */
fun <ModelT, BindDataT : Any> Style.viewBinder(
    binder: RenderUnit.Binder<ModelT, View, BindDataT>,
    model: ModelT,
): Style =
    this + ObjectStyleItem(BinderObjectField.VIEW_MOUNT_BINDER, createDelegateBinder(model, binder))

/**
 * An overload of [Style.viewBinder] which takes a [RenderUnit.Binder] that does not require a
 * model.
 *
 * @param binder the [RenderUnit.Binder] to be used to bind the model to the View content.
 */
fun <BindDataT : Any> Style.viewBinder(binder: RenderUnit.Binder<Unit, View, BindDataT>): Style =
    this.viewBinder(binder, model = Unit)

@Deprecated("use parameterized viewBinder methods above")
fun Style.viewBinder(binder: DelegateBinder<*, View, Any>): Style =
    this + ObjectStyleItem(BinderObjectField.VIEW_MOUNT_BINDER, binder)

@PublishedApi
internal enum class BinderObjectField : StyleItemField {
  MOUNT_BINDER,
  VIEW_MOUNT_BINDER,
  HOST_VIEW_MOUNT_BINDER,
}

@PublishedApi
@DataClassGenerate
internal data class ObjectStyleItem(
    override val field: BinderObjectField,
    override val value: Any?,
) : StyleItem<Any?> {
  @Suppress("UNCHECKED_CAST")
  override fun applyCommonProps(context: ComponentContext, commonProps: CommonProps) {
    when (field) {
      BinderObjectField.MOUNT_BINDER ->
          commonProps.mountBinder(value as DelegateBinder<Any, Any, Any>)
      BinderObjectField.VIEW_MOUNT_BINDER ->
          commonProps.delegateMountViewBinder(value as DelegateBinder<Any, Any, Any>)
      BinderObjectField.HOST_VIEW_MOUNT_BINDER -> {
        value as DeferredBinderData
        val model = ModelWithContext(context, value.deps)
        val binder = binder(value.description, dep = model, value.func)
        commonProps.delegateHostViewMountBinder(binder as DelegateBinder<Any, Any, Any>)
      }
    }
  }
}

@PublishedApi
internal class DeferredBinderData(
    val description: (() -> String)?,
    val deps: Array<*>,
    val func: BindFunc<View>,
)

/**
 * A [com.facebook.rendercore.primitives.KBinder] model that contains the typical KBinder [deps]
 * together with a scoped [ComponentContext].
 *
 * The [scopedContext] is transient and as a result does not participate in [equals]/[hashCode]
 * evaluations. This ensures that the binder continues to behave exactly as if only [deps] were
 * provided.
 */
class ModelWithContext
internal constructor(val scopedContext: ComponentContext, internal val deps: Array<*>) {
  override fun equals(other: Any?): Boolean =
      other is ModelWithContext && deps.contentEquals(other.deps)

  override fun hashCode(): Int = deps.contentHashCode()
}

const val ON_BIND_NO_DEPS_ERROR =
    "onBind must provide the 'deps' parameter to determine whether an existing binder should " +
        "be re-bound. Use 'Unit' as 'deps' to invoke the binder only once when the component " +
        "is mounted or 'Any' to invoke the binder every time the component is mounted."
