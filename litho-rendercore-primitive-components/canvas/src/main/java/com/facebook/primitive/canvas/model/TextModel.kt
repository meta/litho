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

package com.facebook.primitive.canvas.model

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.withMatrix
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.primitive.canvas.CanvasState
import com.facebook.primitive.utils.types.BlendingMode

/**
 * A definition for the font text is drawn with.
 *
 * Text is laid out by the caller: these commands draw or outline a string at a given position, they
 * never measure, wrap or position glyphs. Glyphs are unhinted, so outlines and native text drawn at
 * any [size] scale exactly with the transforms they're drawn under.
 *
 * @property typeface The typeface, or null for the platform default
 * @property size The text size, in the units of the space the text is drawn in
 * @property featureSettings OpenType feature settings in CSS `font-feature-settings` syntax, or
 *   null
 * @property variationSettings Variable font axis settings in CSS `font-variation-settings` syntax,
 *   or null. Ignored before Android O; malformed settings draw the default instance.
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasFont
@JvmOverloads
constructor(
    val typeface: Typeface?,
    val size: Float,
    val featureSettings: String? = null,
    val variationSettings: String? = null,
)

/**
 * A definition for a child path which adds the outlines of [text], drawn with [font] with its
 * origin at (0, 0) on the baseline, transformed by [transformModel]. The outlines are filled and
 * stroked like any other contour of the path, so shading, dashes and clips apply to text as to
 * shapes. Glyphs that have no outline (for example color emoji, drawn from bitmaps) add nothing:
 * draw them with [CanvasText].
 *
 * @property text The text to outline, usually one grapheme cluster
 * @property font The font of the text
 * @property transformModel The transform that will be applied to the outlines before adding them to
 *   the current path
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasPathText(
    private val text: String,
    private val font: CanvasFont,
    private val transformModel: CanvasTransformModel,
) : CanvasPathChildModel {
  override fun applyTo(androidPath: Path, state: CanvasState) {
    val outline = state.getTextOutline(text, font)
    state.getMatrix(transformModel) { matrix -> androidPath.addPath(outline, matrix) }
  }
}

/**
 * A definition for a canvas model which draws [text] with the platform's text drawing, with its
 * origin at (0, 0) on the baseline, transformed by [transform]. Use it for glyphs that have no
 * outline, such as color emoji; text with outlines is better added to a path with [CanvasPathText],
 * so it can be stroked and clipped like a shape.
 *
 * @property text The text to draw, usually one grapheme cluster
 * @property font The font of the text
 * @property transform The transform that will be applied to the text
 * @property shading The shading the text is filled with. Color glyphs keep their own colors and
 *   only take its alpha.
 * @property blendingMode The blend mode that will be used when drawing the text
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasText(
    val text: String,
    val font: CanvasFont,
    val transform: CanvasTransformModel,
    val shading: CanvasShadingModel,
    val blendingMode: BlendingMode,
) : CanvasNodeModel {
  override fun draw(canvas: Canvas, state: CanvasState) {
    val paint = state.configureTextPaint(this)
    state.getMatrix(transform) { matrix ->
      canvas.withMatrix(matrix) { canvas.drawText(text, 0f, 0f, paint) }
    }
  }

  override fun needsSoftwareLayer(): Boolean = false
}
