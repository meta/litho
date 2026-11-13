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
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.annotation.FloatRange
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.primitive.canvas.CanvasState
import com.facebook.primitive.canvas.withLayer
import com.facebook.primitive.utils.types.BlendingMode
import com.facebook.primitive.utils.types.DEFAULT_BLENDING_MODE
import com.facebook.primitive.utils.types.LineCap
import com.facebook.primitive.utils.types.LineJoin
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.geometry.Size

sealed interface CanvasNodeModel {
  fun draw(canvas: Canvas, state: CanvasState)

  fun needsSoftwareLayer(): Boolean
}

/**
 * A definition for a canvas model which describes a tree of drawing commands.
 *
 * @property canvasState The state which is responsible for managing [Paint], [Matrix] and
 *   [android.graphics.Path] objects.
 * @property children The child nodes of the canvas
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasModel(
    private val canvasState: CanvasState,
    private val children: List<CanvasNodeModel>,
) {

  private val pathCount: Int = countCachedPaths(children)

  /**
   * Draw the model onto the provided canvas.
   *
   * @param canvas a canvas which should be used for executing the drawing commands
   */
  fun draw(canvas: Canvas) {
    canvasState.ensurePathCacheCapacity(pathCount)
    for (i in children.indices) {
      children[i].draw(canvas, canvasState)
    }
    canvasState.trimPathCache(pathCount)
  }

  /**
   * Not all drawing commands supported by this API are hardware accelerated on all supported
   * versions of Android. For operations which are not hardware accelerated, a software layer needs
   * to be enabled.This method analyzes the tree and checks if a software layer needs to be enabled
   * or not.
   *
   * @return true if software layer needs to be enabled, false otherwise
   * @see <a
   *   href="https://developer.android.com/topic/performance/hardware-accel#drawing-support">Drawing
   *   Support</a>
   */
  fun needsSoftwareLayer(): Boolean {
    return checkIfSoftwareLayerNeeded(children = children)
  }
}

/**
 * A definition for a canvas model which describes a group of drawing commands. Groups can be used
 * to treat multiple drawing commands as a single one and applying transforms or clips to all of
 * them at the same time. For a group, first the transform is applied, then the content is drawn
 * inside the transformed coordinates and then the clip is applied to the drawn content.
 *
 * @property transform The transform that will be applied to the children of the group
 * @property size The size of the group
 * @property clip The path describing the shape of the clip that will be applied to the children of
 *   the group
 * @property clipToBounds The group will be clipped to its bounds if this is set to true and [clip]
 *   is null
 * @property children The child nodes of the group
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasGroup(
    private val transform: CanvasTransformModel,
    private val size: Size,
    private val clip: CanvasPathModel?,
    private val clipToBounds: Boolean,
    private val children: List<CanvasNodeModel>,
) : CanvasNodeModel {
  override fun draw(canvas: Canvas, state: CanvasState) {
    state.getMatrix(transform) { matrix ->
      canvas.withMatrix(matrix) {
        if (clip != null) {
          val clipPath = state.getOrCreatePath(clip)
          canvas.withClip(clipPath) {
            for (i in children.indices) {
              children[i].draw(canvas, state)
            }
          }
        } else if (clipToBounds) {
          canvas.withClip(0f, 0f, size.width, size.height) {
            for (i in children.indices) {
              children[i].draw(canvas, state)
            }
          }
        } else {
          for (i in children.indices) {
            children[i].draw(canvas, state)
          }
        }
      }
    }
  }

  override fun needsSoftwareLayer(): Boolean {
    return checkIfSoftwareLayerNeeded(children = children)
  }

  internal fun countCachedPaths(): Int = (clip?.cachedPathCount() ?: 0) + children.cachedPathCount()
}

/**
 * A definition for a canvas model which describes a layer containing drawing commands. A Layer
 * allocates and redirects drawing to an offscreen buffer. It may be useful in cases like applying
 * transparency or blending modes to multiple drawing commands at once. Layers should be as small as
 * possible and should be used only when necessary because they may cause performance issues if used
 * incorrectly.
 *
 * @property transform The transform that will be applied to the children of the layer
 * @property size The size of the layer
 * @property clip The path describing the shape of the clip that will be applied to the children of
 *   the layer
 * @property alpha The alpha that will be applied to the layer when compositing it with the parent
 *   layer
 * @property blendingMode The blend mode that will be applied to the layer when compositing it with
 *   the parent layer
 * @property children The child nodes of the layer
 */
@Suppress("DEPRECATION")
@SuppressLint("NotInvokedPrivateMethod", "DeprecatedMethod")
@DataClassGenerate
data class CanvasLayer(
    val transform: CanvasTransformModel,
    val size: Size,
    val clip: CanvasPathModel?,
    @param:FloatRange(from = 0.0, to = 1.0) val alpha: Float,
    val blendingMode: BlendingMode,
    val children: List<CanvasNodeModel>,
) : CanvasNodeModel {
  override fun draw(canvas: Canvas, state: CanvasState) {
    state.getMatrix(transform) { matrix ->
      val paint =
          if (alpha != DEFAULT_ALPHA || blendingMode != DEFAULT_BLENDING_MODE) {
            state.configureLayerPaint(this)
          } else {
            null
          }

      canvas.withMatrix(matrix) {
        canvas.withLayer(0f, 0f, size.width, size.height, paint) {
          if (clip != null) {
            val clipPath = state.getOrCreatePath(clip)
            canvas.withClip(clipPath) {
              for (i in children.indices) {
                children[i].draw(canvas, state)
              }
            }
          } else {
            for (i in children.indices) {
              children[i].draw(canvas, state)
            }
          }
        }
      }
    }
  }

  override fun needsSoftwareLayer(): Boolean {
    return checkIfSoftwareLayerNeeded(blendingMode = blendingMode, children = children)
  }

  companion object {
    const val DEFAULT_ALPHA = 1.0f
  }
}

/**
 * A definition for a canvas model which describes a fill drawing command. Paths drawn with this
 * command will be filled.
 *
 * @property shape The shape that will be drawn with fill style. A shape can be either a
 *   [CanvasPathModel] or [CanvasShapeModel]. Prefer using [CanvasPathModel] if possible because it
 *   offers better drawing performance.
 * @property shading The shading definition that describes how to fill the path. For example solid
 *   color or a gradient.
 * @property blendingMode The blend mode that will be used when filling the shape
 * @property shadow The shadow that will be drawn below the path
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasFill(
    val shape: CanvasShape,
    val shading: CanvasShadingModel,
    val blendingMode: BlendingMode,
    val shadow: CanvasShadowModel?,
) : CanvasNodeModel {
  override fun draw(canvas: Canvas, state: CanvasState) {
    val fillPaint = state.configureFillPaint(this)
    when (shape) {
      is CanvasPathModel -> {
        val androidPath = state.getOrCreatePath(shape)
        canvas.drawPath(androidPath, fillPaint)
      }
      is CanvasShapeModel -> {
        if (shape is CanvasShapeLine) {
          // A line shape should not be rendered "filled" for consistency with iOS and HTML5, which
          // only allow lines to be stroked. This also makes conceptual sense since a line isn't a
          // "fill-able" shape.This also makes Android internally consistent with the following
          // equivalent case:
          //
          // A CanvasFill element that contains a CanvasPath with a single LineTo element.
          //
          // A CanvasFill with a CanvasLineShape should behave similarly.
          ErrorReporter.report(
              LogLevel.ERROR,
              "CanvasFill",
              "A Line shape cannot be 'filled'. Ignoring.",
          )
        } else {
          shape.draw(canvas, fillPaint)
        }
      }
    }
  }

  override fun needsSoftwareLayer(): Boolean {
    return checkIfSoftwareLayerNeeded(shadow = shadow, blendingMode = blendingMode)
  }
}

/**
 * A definition for a canvas model which describes a stroke drawing command. Paths drawn with this
 * command will be stroked.
 *
 * Note: Strokes are rendered in such a way where half of the stroke width is drawn inside of the
 * shape and half is drawn outside of the shape. Keep this in mind when drawing stroked shapes close
 * to the clip or screen bounds and make sure that the whole stroke width is rendered properly along
 * the entire shape.
 *
 * @property shape The shape that will be drawn with stroke style. A shape can be either a
 *   [CanvasPathModel] or [CanvasShapeModel]. Prefer using [CanvasPathModel] if possible because it
 *   offers better drawing performance.
 * @property shading The shading definition that describes how to stroke the path. For example solid
 *   color or a gradient.
 * @property blendingMode The blend mode that will be used when stroking the shape
 * @property shadow The shadow that will be drawn below the path
 * @property lineWidth The width for stroking. Pass 0 to stroke in hairline mode. Hairlines always
 *   draws a single pixel.
 * @property lineCap The line cap style
 * @property lineJoin The line join style
 * @property miterLimit The stroke miter value. This is used to control the behavior of miter joins
 *   when the joins angle is sharp. This value must be >= 0.
 * @property dashLengths The array of ON and OFF distances. It must contain an even number of
 *   entries (>=2), with the even indices specifying the "on" intervals, and the odd indices
 *   specifying the "off" intervals. This array controls the length of the dashes. The [lineWidth]
 *   controls the thickness of the dashes.
 * @property dashPhase The value which specifies how far into the dash pattern the line starts. For
 *   example, passing a value of 3 means the line is drawn with the dash pattern starting at three
 *   units from its beginning. Passing a value of 0 draws a line starting with the beginning of a
 *   dash pattern. Ignored if dash-lengths is null.
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasStroke(
    val shape: CanvasShape,
    val shading: CanvasShadingModel,
    val blendingMode: BlendingMode,
    val shadow: CanvasShadowModel?,
    val lineWidth: Float,
    val lineCap: LineCap,
    val lineJoin: LineJoin,
    val miterLimit: Float,
    val dashLengths: FloatArray?,
    val dashPhase: Float,
) : CanvasNodeModel {
  override fun draw(canvas: Canvas, state: CanvasState) {
    val strokePaint = state.configureStrokePaint(this)
    when (shape) {
      is CanvasPathModel -> {
        val androidPath = state.getOrCreatePath(shape)
        canvas.drawPath(androidPath, strokePaint)
      }
      is CanvasShapeModel -> {
        shape.draw(canvas, strokePaint)
      }
    }
  }

  override fun needsSoftwareLayer(): Boolean {
    return checkIfSoftwareLayerNeeded(shadow = shadow, blendingMode = blendingMode)
  }

  // equals and hashCode has to be explicitly declared because of dashLength property which is of
  // FloatArray type and we want to compare it by its contents
  override fun equals(other: Any?): Boolean {
    if (this === other) {
      return true
    }
    if (javaClass != other?.javaClass) {
      return false
    }

    other as CanvasStroke

    if (shape != other.shape) {
      return false
    }
    if (shading != other.shading) {
      return false
    }
    if (blendingMode != other.blendingMode) {
      return false
    }
    if (shadow != other.shadow) {
      return false
    }
    if (lineWidth != other.lineWidth) {
      return false
    }
    if (lineCap != other.lineCap) {
      return false
    }
    if (lineJoin != other.lineJoin) {
      return false
    }
    if (miterLimit != other.miterLimit) {
      return false
    }
    if (dashLengths != null) {
      if (other.dashLengths == null) {
        return false
      }
      if (!dashLengths.contentEquals(other.dashLengths)) {
        return false
      }
    } else if (other.dashLengths != null) {
      return false
    }
    if (dashPhase != other.dashPhase) {
      return false
    }

    return true
  }

  override fun hashCode(): Int {
    var result = shape.hashCode()
    result = 31 * result + shading.hashCode()
    result = 31 * result + blendingMode.hashCode()
    result = 31 * result + (shadow?.hashCode() ?: 0)
    result = 31 * result + lineWidth.hashCode()
    result = 31 * result + lineCap.hashCode()
    result = 31 * result + lineJoin.hashCode()
    result = 31 * result + miterLimit.hashCode()
    result = 31 * result + (dashLengths?.contentHashCode() ?: 0)
    result = 31 * result + dashPhase.hashCode()
    return result
  }

  companion object {
    const val DEFAULT_LINE_WIDTH: Float = 0f
    const val DEFAULT_MITER_LIMIT: Float = 4f
  }
}

/**
 * A definition for a canvas model which describes a drawing command that provides access to draw
 * directly with the underlying [Canvas]. This is helpful for situations to re-use the existing
 * [Canvas] drawing logic.
 *
 * @property block The lambda callback to issue drawing commands on the provided [Canvas]
 */
@SuppressLint("NotInvokedPrivateMethod")
@DataClassGenerate
data class CanvasDrawIntoCanvas(private val block: (Canvas) -> Unit) : CanvasNodeModel {
  override fun draw(canvas: Canvas, state: CanvasState) {
    block(canvas)
  }

  override fun needsSoftwareLayer(): Boolean {
    return false
  }
}

/**
 * The number of [CanvasState] path cache entries used to draw [nodes]. Paths used by more than one
 * node are counted once per use, so this is an upper bound of the distinct paths. Pass it to
 * [CanvasState.ensurePathCacheCapacity] and [CanvasState.trimPathCache] when drawing nodes without
 * a [CanvasModel].
 */
fun countCachedPaths(nodes: List<CanvasNodeModel>): Int = nodes.cachedPathCount()

private fun List<CanvasNodeModel>.cachedPathCount(): Int {
  var count = 0
  for (i in indices) {
    count +=
        when (val node = this[i]) {
          is CanvasGroup -> node.countCachedPaths()
          is CanvasLayer -> (node.clip?.cachedPathCount() ?: 0) + node.children.cachedPathCount()
          is CanvasFill -> node.shape.cachedPathCount()
          is CanvasStroke -> node.shape.cachedPathCount()
          is CanvasDrawIntoCanvas -> 0
          is CanvasText -> 0
        }
  }
  return count
}

private fun CanvasShape.cachedPathCount(): Int =
    when (this) {
      is CanvasPathModel -> cachedPathCount()
      is CanvasShapeModel -> 0
    }

/**
 * According to: https://developer.android.com/topic/performance/hardware-accel#drawing-support
 * shadowLayer, DARKEN, LIGHTEN and OVERLAY blend modes are not hardware accelerated prior to
 * Android P.
 *
 * This table mentions also dash effect, but it seems to work fine without software layer. It
 * probably silently fallbacks to software rendering under the hoods but since it works fine without
 * software layer, we're not checking it here.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun checkIfSoftwareLayerNeeded(
    shadow: CanvasShadowModel? = null,
    blendingMode: BlendingMode? = null,
    children: List<CanvasNodeModel> = listOf(),
): Boolean {
  return (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) &&
      (blendingMode == BlendingMode.Darken ||
          blendingMode == BlendingMode.Lighten ||
          blendingMode == BlendingMode.Overlay ||
          shadow != null ||
          children.any { child -> child.needsSoftwareLayer() })
}
