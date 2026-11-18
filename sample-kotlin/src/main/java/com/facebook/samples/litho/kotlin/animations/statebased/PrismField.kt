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

package com.facebook.samples.litho.kotlin.animations.statebased

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Style
import com.facebook.litho.animation.AnimationVector
import com.facebook.litho.animation.TypeConverter
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.rendercore.primitives.DrawableAllocator
import com.facebook.rendercore.primitives.EqualDimensionsLayoutBehavior
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@DataClassGenerate(toString = Mode.OMIT, equalsHashCode = Mode.KEEP)
internal data class PrismForm(val halo: Float, val bloom: Float, val galaxy: Float)

internal object PrismFormConverter : TypeConverter<PrismForm> {
  override fun newVector(): AnimationVector = AnimationVector.ofSize(3)

  override fun toVector(value: PrismForm, vector: AnimationVector) {
    vector[0] = value.halo
    vector[1] = value.bloom
    vector[2] = value.galaxy
  }

  override fun fromVector(vector: AnimationVector): PrismForm =
      PrismForm(vector[0], vector[1], vector[2])
}

/** Reads the animated values at bind time so particle motion needs no per-frame Yoga layout. */
@OptIn(ExperimentalLithoApi::class)
internal class PrismField(private val motion: PrismChoreography, private val style: Style) :
    PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    val density = androidContext.resources.displayMetrics.density
    return LithoPrimitive(
        layoutBehavior = EqualDimensionsLayoutBehavior,
        mountBehavior =
            MountBehavior(DrawableAllocator { PrismDrawable() }) {
              bind(motion, density) { drawable ->
                drawable.update(
                    motion.form.value.value,
                    motion.rotation.value.value,
                    motion.radius.value.value,
                    motion.tilt.value.value,
                    motion.warmth.value.value,
                    motion.breath.value.value,
                    motion.flare.value.value,
                    motion.wave.value.value,
                    density,
                )
                onUnbind { drawable.clear() }
              }
            },
        style = style,
    )
  }
}

private class PrismDrawable : Drawable() {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
  private val glowPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
      }
  private val shaderMatrix = Matrix()
  private val aura = RadialGradient(
      0f,
      0f,
      1f,
      intArrayOf(0x604147C8, 0x30213761, Color.TRANSPARENT),
      floatArrayOf(0f, 0.45f, 1f),
      Shader.TileMode.CLAMP,
  )
  private val warmAura = RadialGradient(
      0f,
      0f,
      1f,
      intArrayOf(0x60B44397, 0x302D1545, Color.TRANSPARENT),
      floatArrayOf(0f, 0.45f, 1f),
      Shader.TileMode.CLAMP,
  )
  private val coreGlow = RadialGradient(
      0f,
      0f,
      1f,
      intArrayOf(0xFFF6FCFF.toInt(), 0xA0A4CCFF.toInt(), 0x305654D3, Color.TRANSPARENT),
      floatArrayOf(0f, 0.15f, 0.45f, 1f),
      Shader.TileMode.CLAMP,
  )
  private val halo = FloatArray(PARTICLE_COUNT * 3)
  private val bloom = FloatArray(PARTICLE_COUNT * 3)
  private val galaxy = FloatArray(PARTICLE_COUNT * 3)
  private val projected = FloatArray(PARTICLE_COUNT * 2)
  private val buckets = Array(12) { FloatArray(PARTICLE_COUNT * 2) }
  private val counts = IntArray(12)
  private val ribbons = Array(BAND_COUNT) { Path() }
  private val coldColors = intArrayOf(
      0xFF8BEAFF.toInt(),
      0xFF7C9CFF.toInt(),
      0xFFB59AFF.toInt(),
      0xFFF0BEFF.toInt(),
      0xFF9BDEFF.toInt(),
      0xFFC2ACFF.toInt(),
  )
  private val warmColors = intArrayOf(
      0xFFFFDEA8.toInt(),
      0xFFFFB9D9.toInt(),
      0xFFD1A0FF.toInt(),
      0xFFF0C9FF.toInt(),
      0xFFFFBEAA.toInt(),
      0xFFB3BDFF.toInt(),
  )
  private var form = PrismForm(1f, 0f, 0f)
  private var rotation = 0f
  private var radius = 1f
  private var tilt = 0.95f
  private var warmth = 0f
  private var breath = 0f
  private var flare = 0f
  private var wave = 1f
  private var density = 1f
  private var active = false
  private var drawableAlpha = 255

  init {
    for (band in 0 until BAND_COUNT) {
      for (point in 0 until POINTS_PER_BAND) {
        val i = (band * POINTS_PER_BAND + point) * 3
        val u = point.toFloat() / (POINTS_PER_BAND - 1)
        val angle = u * PRISM_TAU
        val ring = 0.68f + band * 0.035f
        halo[i] = ring * cos(angle)
        halo[i + 1] = ring * sin(angle)
        halo[i + 2] = 0.17f * sin(angle * 3f + band * 0.45f)
        val petal = 0.58f + 0.24f * cos(angle * 6f + band * 0.18f)
        bloom[i] = petal * cos(angle)
        bloom[i + 1] = petal * sin(angle)
        bloom[i + 2] = 0.22f * sin(angle * 6f + band * 0.18f)
        val spiral = 0.07f + u * 0.86f
        val winding = angle * 1.65f + band * PRISM_TAU / BAND_COUNT
        galaxy[i] = spiral * cos(winding)
        galaxy[i + 1] = spiral * sin(winding)
        galaxy[i + 2] = 0.12f * sin(angle * 2f + band)
      }
    }
  }

  fun update(
      form: PrismForm,
      rotation: Float,
      radius: Float,
      tilt: Float,
      warmth: Float,
      breath: Float,
      flare: Float,
      wave: Float,
      density: Float,
  ) {
    this.form = form
    this.rotation = rotation
    this.radius = radius
    this.tilt = tilt
    this.warmth = warmth.coerceIn(0f, 1f)
    this.breath = breath
    this.flare = flare.coerceIn(0f, 1f)
    this.wave = wave.coerceIn(0f, 1f)
    this.density = density
    active = true
    invalidateSelf()
  }

  fun clear() {
    active = false
    invalidateSelf()
  }

  override fun draw(canvas: Canvas) {
    if (!active) return
    val saved = canvas.save()
    canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
    val width = bounds.width().toFloat()
    val height = bounds.height().toFloat()
    val cx = width / 2f
    val cy = height / 2f
    val extent = min(width, height)
    val fieldRadius = extent * 0.43f * radius.coerceAtLeast(0.02f) * (1f + breath * 0.045f)
    drawAtmosphere(canvas, width, height, cx, cy, extent)
    project(cx, cy, fieldRadius)
    drawRibbons(canvas)
    drawParticles(canvas)
    drawCore(canvas, cx, cy, fieldRadius)
    if (wave < 1f) {
      paint.shader = null
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = density * (0.6f + (1f - wave) * 2f)
      paint.color = blend(coldColors[0], warmColors[0], warmth)
      paint.alpha = alpha((1f - wave) * (1f - wave) * 150f)
      canvas.drawCircle(cx, cy, extent * (0.06f + wave * 0.57f), paint)
    }
    canvas.restoreToCount(saved)
  }

  private fun drawAtmosphere(
      canvas: Canvas,
      width: Float,
      height: Float,
      cx: Float,
      cy: Float,
      extent: Float,
  ) {
    paint.style = Paint.Style.FILL
    shaderMatrix.setScale(extent * 0.6f, extent * 0.6f)
    shaderMatrix.postTranslate(cx, cy)
    aura.setLocalMatrix(shaderMatrix)
    warmAura.setLocalMatrix(shaderMatrix)
    paint.shader = aura
    paint.alpha = alpha(255f * (1f - warmth))
    canvas.drawRect(0f, 0f, width, height, paint)
    paint.shader = warmAura
    paint.alpha = alpha(255f * warmth)
    canvas.drawRect(0f, 0f, width, height, paint)
    paint.shader = null
    paint.color = Color.WHITE
    for (i in 0 until 64) {
      val x = ((i * 73 + 19) % 997) / 997f * width
      val y = ((i * 137 + 47) % 991) / 991f * height
      paint.alpha = alpha(18f + (i % 5) * 10f)
      canvas.drawCircle(x, y, density * if (i % 7 == 0) 0.9f else 0.45f, paint)
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = density * 0.5f
    paint.color = coldColors[0]
    paint.alpha = alpha(24f)
    canvas.drawCircle(cx, cy, extent * 0.465f, paint)
    for (i in 0 until 48) {
      val a = i * PRISM_TAU / 48f
      val inner = extent * if (i % 4 == 0) 0.445f else 0.457f
      val outer = extent * 0.465f
      canvas.drawLine(
          cx + cos(a) * inner,
          cy + sin(a) * inner,
          cx + cos(a) * outer,
          cy + sin(a) * outer,
          paint,
      )
    }
  }

  private fun project(cx: Float, cy: Float, fieldRadius: Float) {
    counts.fill(0)
    val turn = rotation * 0.32f
    val ca = cos(turn)
    val sa = sin(turn)
    val pitch = tilt + sin(rotation) * 0.16f
    val ct = cos(pitch)
    val st = sin(pitch)
    for (i in 0 until PARTICLE_COUNT) {
      val p = i * 3
      val x = halo[p] * form.halo + bloom[p] * form.bloom + galaxy[p] * form.galaxy
      val y = halo[p + 1] * form.halo + bloom[p + 1] * form.bloom + galaxy[p + 1] * form.galaxy
      val z = halo[p + 2] * form.halo + bloom[p + 2] * form.bloom + galaxy[p + 2] * form.galaxy
      val rx = x * ca - y * sa
      val ry = x * sa + y * ca
      val depth = ry * st + z * ct
      val perspective = 1f / (1f - depth * 0.22f)
      val screenX = cx + rx * fieldRadius * perspective
      val screenY = cy + (ry * ct - z * st) * fieldRadius * perspective
      projected[i * 2] = screenX
      projected[i * 2 + 1] = screenY
      val bucket = i / POINTS_PER_BAND + if (depth > 0f) BAND_COUNT else 0
      val offset = counts[bucket]
      buckets[bucket][offset] = screenX
      buckets[bucket][offset + 1] = screenY
      counts[bucket] = offset + 2
    }
  }

  private fun drawRibbons(canvas: Canvas) {
    paint.shader = null
    paint.style = Paint.Style.STROKE
    for (band in 0 until BAND_COUNT) {
      val path = ribbons[band]
      path.rewind()
      for (point in 0 until POINTS_PER_BAND) {
        val i = (band * POINTS_PER_BAND + point) * 2
        if (point == 0) path.moveTo(projected[i], projected[i + 1])
        else path.lineTo(projected[i], projected[i + 1])
      }
      paint.color = blend(coldColors[band], warmColors[band], warmth)
      paint.alpha = alpha(42f + flare * 50f)
      paint.strokeWidth = density * 0.45f
      canvas.drawPath(path, paint)
    }
  }

  private fun drawParticles(canvas: Canvas) {
    paint.shader = null
    paint.style = Paint.Style.STROKE
    glowPaint.style = Paint.Style.STROKE
    for (bucket in buckets.indices) {
      val count = counts[bucket]
      if (count == 0) continue
      val band = bucket % BAND_COUNT
      val front = bucket >= BAND_COUNT
      val color = blend(coldColors[band], warmColors[band], warmth)
      glowPaint.color = color
      glowPaint.alpha = alpha(if (front) 20f else 9f)
      glowPaint.strokeWidth = density * (if (front) 8f else 5f)
      canvas.drawPoints(buckets[bucket], 0, count, glowPaint)
      glowPaint.alpha = alpha(if (front) 64f else 25f)
      glowPaint.strokeWidth = density * (if (front) 3.5f else 2.5f)
      canvas.drawPoints(buckets[bucket], 0, count, glowPaint)
      paint.color = color
      paint.alpha = alpha(if (front) 240f else 120f)
      paint.strokeWidth = density * (if (front) 1.35f else 0.9f)
      canvas.drawPoints(buckets[bucket], 0, count, paint)
    }
  }

  private fun drawCore(canvas: Canvas, cx: Float, cy: Float, fieldRadius: Float) {
    paint.style = Paint.Style.FILL
    val coreRadius = fieldRadius * (0.24f + flare * 0.45f)
    shaderMatrix.setScale(coreRadius, coreRadius)
    shaderMatrix.postTranslate(cx, cy)
    coreGlow.setLocalMatrix(shaderMatrix)
    paint.shader = coreGlow
    paint.alpha = alpha(170f + flare * 85f)
    canvas.drawCircle(cx, cy, coreRadius, paint)
    paint.shader = null
    if (form.halo > 0f) {
      paint.color = PRISM_SURFACE
      paint.alpha = alpha(form.halo.coerceIn(0f, 1f) * 255f * (1f - flare))
      canvas.drawCircle(cx, cy, fieldRadius * 0.18f, paint)
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = density * 0.7f
      paint.color = coldColors[0]
      paint.alpha = alpha(form.halo.coerceIn(0f, 1f) * 90f)
      canvas.drawCircle(cx, cy, fieldRadius * 0.185f, paint)
    }
    if (flare > 0f) {
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = density * 0.8f
      paint.color = Color.WHITE
      paint.alpha = alpha(flare * flare * 150f)
      val beam = fieldRadius * (0.7f + flare)
      canvas.drawLine(cx - beam, cy, cx + beam, cy, paint)
      paint.alpha = alpha(flare * 60f)
      canvas.drawLine(cx, cy - beam * 0.4f, cx, cy + beam * 0.4f, paint)
    }
  }

  private fun alpha(value: Float): Int = (value * drawableAlpha / 255f).toInt().coerceIn(0, 255)

  override fun setAlpha(alpha: Int) {
    drawableAlpha = alpha
    invalidateSelf()
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    paint.colorFilter = colorFilter
    glowPaint.colorFilter = colorFilter
    invalidateSelf()
  }

  @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private fun blend(cold: Int, warm: Int, progress: Float): Int =
    Color.rgb(
        (Color.red(cold) + (Color.red(warm) - Color.red(cold)) * progress).toInt(),
        (Color.green(cold) + (Color.green(warm) - Color.green(cold)) * progress).toInt(),
        (Color.blue(cold) + (Color.blue(warm) - Color.blue(cold)) * progress).toInt(),
    )

private const val BAND_COUNT = 6
private const val POINTS_PER_BAND = 120
private const val PARTICLE_COUNT = BAND_COUNT * POINTS_PER_BAND
