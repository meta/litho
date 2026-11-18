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

package com.facebook.litho.animation

import android.graphics.Color as AndroidColor
import android.view.View
import com.facebook.litho.Style
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.binders.onBindViewWithDescription
import com.facebook.rendercore.graphics.Color

/**
 * This [Style] adds a mount callback for state value.
 *
 * When the Component is mounted, [func] is invoked with the [View] as the receiver and the current
 * value from [value]. The state read is tracked to enable automatic re-rendering when the tracked
 * state changes.
 *
 * When the Component is unmounted, [func] is invoked with [defaultValue] to reset the view state.
 *
 * @param name a description for the binder, used for debugging purposes.
 * @param value the [Value] whose reads will be tracked during the bind operation.
 * @param defaultValue the value to apply when unbinding to reset the view state.
 * @param func the function applied to the view with the current or default value.
 */
@ExperimentalLithoApi
inline fun <T> Style.onBindViewWithValue(
    noinline name: () -> String,
    value: Value<T>,
    defaultValue: T,
    crossinline func: View.(T) -> Unit,
): Style {
  return this +
      onBindViewWithDescription(name, value) { content ->
        content.func(value.value)
        onUnbind { content.func(defaultValue) }
      }
}

/**
 * Sets the alpha of the [View] using a [Value].
 *
 * The alpha value is applied when the Component is mounted and tracked for automatic re-rendering
 * when the state changes. When unmounted, the alpha is reset to 1f (fully opaque).
 *
 * @param alpha the [Value] containing the alpha value (0f = transparent, 1f = opaque).
 */
@ExperimentalLithoApi
fun Style.alpha(alpha: Value<Float>): Style = onBindViewWithValue(
    name = { "alpha" },
    value = alpha,
    defaultValue = 1f,
) { value ->
  this.alpha = value
}

/**
 * Sets the rotation of the [View] using a [Value].
 *
 * The rotation is applied when the Component is mounted and tracked for automatic re-rendering when
 * the state changes. When unmounted, the rotation is reset to 0f.
 *
 * @param rotation the [Value] containing the rotation in degrees.
 */
@ExperimentalLithoApi
fun Style.rotation(rotation: Value<Float>): Style = onBindViewWithValue(
    name = { "rotation" },
    value = rotation,
    defaultValue = 0f,
) { value ->
  this.rotation = value
}

/**
 * Sets the X-axis rotation of the [View] using a [Value].
 *
 * @param rotationX the [Value] containing the X-axis rotation in degrees.
 */
@ExperimentalLithoApi
fun Style.rotationX(rotationX: Value<Float>): Style = onBindViewWithValue(
    name = { "rotationX" },
    value = rotationX,
    defaultValue = 0f,
) { value ->
  this.rotationX = value
}

/**
 * Sets the Y-axis rotation of the [View] using a [Value].
 *
 * @param rotationY the [Value] containing the Y-axis rotation in degrees.
 */
@ExperimentalLithoApi
fun Style.rotationY(rotationY: Value<Float>): Style = onBindViewWithValue(
    name = { "rotationY" },
    value = rotationY,
    defaultValue = 0f,
) { value ->
  this.rotationY = value
}

/**
 * Sets the scale (scaleX and scaleY) of the [View] using a [Value].
 *
 * When unmounted, the scale is reset to 1f. Shares its per-axis binders with [scaleX] and [scaleY],
 * so a later `scaleX(...)` or `scaleY(...)` on the same [Style] overrides that axis.
 *
 * @param scale the [Value] containing the uniform scale factor.
 */
@ExperimentalLithoApi fun Style.scale(scale: Value<Float>): Style = scaleX(scale).scaleY(scale)

/**
 * Sets the X-axis scale of the [View] using a [Value].
 *
 * When unmounted, the X-axis scale is reset to 1f.
 *
 * @param scaleX the [Value] containing the X-axis scale factor.
 */
@ExperimentalLithoApi
fun Style.scaleX(scaleX: Value<Float>): Style = onBindViewWithValue(
    name = { "scaleX" },
    value = scaleX,
    defaultValue = 1f,
) { value ->
  this.scaleX = value
}

/**
 * Sets the Y-axis scale of the [View] using a [Value].
 *
 * When unmounted, the Y-axis scale is reset to 1f.
 *
 * @param scaleY the [Value] containing the Y-axis scale factor.
 */
@ExperimentalLithoApi
fun Style.scaleY(scaleY: Value<Float>): Style = onBindViewWithValue(
    name = { "scaleY" },
    value = scaleY,
    defaultValue = 1f,
) { value ->
  this.scaleY = value
}

/**
 * Sets the elevation of the [View] using a [Value].
 *
 * The value is in pixels. When unmounted, the elevation is reset to 0f.
 *
 * @param elevation the [Value] containing the elevation in pixels.
 */
@ExperimentalLithoApi
fun Style.elevation(elevation: Value<Float>): Style = onBindViewWithValue(
    name = { "elevation" },
    value = elevation,
    defaultValue = 0f,
) { value ->
  this.elevation = value
}

/**
 * Sets the X-axis translation of the [View] using a [Value].
 *
 * @param translationX the [Value] containing the X translation in pixels.
 */
@ExperimentalLithoApi
fun Style.translationX(translationX: Value<Float>): Style = onBindViewWithValue(
    name = { "translationX" },
    value = translationX,
    defaultValue = 0f,
) { value ->
  this.translationX = value
}

/**
 * Sets the Y-axis translation of the [View] using a [Value].
 *
 * @param translationY the [Value] containing the Y translation in pixels.
 */
@ExperimentalLithoApi
fun Style.translationY(translationY: Value<Float>): Style = onBindViewWithValue(
    name = { "translationY" },
    value = translationY,
    defaultValue = 0f,
) { value ->
  this.translationY = value
}

/**
 * Sets the background color of the [View] using a [Value].
 *
 * @param backgroundColor the [Value] containing the background color.
 */
@ExperimentalLithoApi
fun Style.backgroundColor(backgroundColor: Value<Color>): Style = onBindViewWithValue(
    name = { "backgroundColor" },
    value = backgroundColor,
    defaultValue = Color.fromInt(AndroidColor.TRANSPARENT),
) { color ->
  setBackgroundColor(color.toArgb())
}
