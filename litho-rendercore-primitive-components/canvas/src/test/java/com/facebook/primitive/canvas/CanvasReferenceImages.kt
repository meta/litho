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

package com.facebook.primitive.canvas

import android.graphics.Bitmap
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Compares renders with the reference images checked in under `src/test/references`.
 *
 * Run the tests with `CANVAS_UPDATE_REFERENCES=<dir>` to write every render to `<dir>` instead of
 * comparing it, and with `CANVAS_DIFF_OUT=<dir>` to keep the renders that do not match.
 */
internal object CanvasReferenceImages {

  /** Per-channel tolerance on premultiplied ARGB, for anti-aliasing differences. */
  private const val CHANNEL_TOLERANCE = 2

  private val referencesDir: File
    get() = File(System.getenv("CANVAS_REFERENCES_DIR") ?: "src/test/references")

  fun assertMatchesReference(name: String, actual: Bitmap) {
    System.getenv("CANVAS_UPDATE_REFERENCES")?.let { dir ->
      write(File(dir, "$name.png"), actual)
      return
    }
    val file = File(referencesDir, "$name.png")
    assertTrue(
        "$name: no reference image at ${file.path}; write it with CANVAS_UPDATE_REFERENCES",
        file.exists(),
    )
    val image = requireNotNull(ImageIO.read(file)) { "Cannot decode ${file.path}" }
    assertEquals("$name width", image.width, actual.width)
    assertEquals("$name height", image.height, actual.height)
    val expected = premultiply(image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    val pixels = IntArray(actual.width * actual.height)
    actual.getPixels(pixels, 0, actual.width, 0, 0, actual.width, actual.height)
    premultiply(pixels)

    var failing = 0
    var worst = 0
    for (i in pixels.indices) {
      val delta = delta(expected[i], pixels[i])
      worst = maxOf(worst, delta)
      if (delta > CHANNEL_TOLERANCE) {
        failing++
      }
    }
    if (failing > 0) {
      System.getenv("CANVAS_DIFF_OUT")?.let { dir -> write(File(dir, "$name.png"), actual) }
      fail("$name: $failing pixels differ from the reference image (worst channel delta $worst)")
    }
  }

  private fun delta(a: Int, b: Int): Int =
      intArrayOf(24, 16, 8, 0).maxOf { abs(((a ushr it) and 0xFF) - ((b ushr it) and 0xFF)) }

  /** `getPixel(s)` and PNG files hold unpremultiplied colors. */
  private fun premultiply(pixels: IntArray): IntArray {
    for (i in pixels.indices) {
      val p = pixels[i]
      val a = p ushr 24
      val r = ((p shr 16) and 0xFF) * a / 255
      val g = ((p shr 8) and 0xFF) * a / 255
      val b = (p and 0xFF) * a / 255
      pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return pixels
  }

  private fun write(file: File, bitmap: Bitmap) {
    file.parentFile?.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
