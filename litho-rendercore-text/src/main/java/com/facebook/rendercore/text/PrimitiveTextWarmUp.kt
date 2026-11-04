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

package com.facebook.rendercore.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import androidx.core.graphics.createBitmap
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes the primitive Text path once at startup so that Meta's production ART profile learns it.
 *
 * Litho's `Text` only renders a [RichTextPrimitive] when `ComponentsConfiguration.usePrimitiveText`
 * is on, so the methods it runs are missing from the harvested profile (IG4A cold start: at most 2%
 * of sessions against a 30% threshold, while legacy `TextComponent` is at ~99%). The experiment arm
 * therefore runs them interpreted or JIT-compiled and pays a startup cost the control arm does not.
 *
 * Like `com.facebook.litho.HookIdentityWarmUp`, this runs in every arm, so the next harvest builds
 * a profile that is the same for all of them, and it only takes effect after a release containing
 * it has been harvested and the new profile has shipped. [RichTextLayoutBehavior]'s `layout` and
 * the mount binders need a live render and stay uncovered.
 */
@DoNotStrip
object PrimitiveTextWarmUp {

  private val hasRun = AtomicBoolean(false)

  /**
   * Sink for the warm-up's results, so that R8 and Redex cannot prove the warm-up dead and delete
   * it. A deleted warm-up fails silently: its only effect is on a profile harvested weeks later.
   */
  @Volatile @JvmStatic var lastResult: Any? = null

  /**
   * Idempotent. Call it off the main thread: it lays out and draws one short text. It runs on every
   * app start, so a failure in it, e.g. an OEM text or draw bug or a framework method missing on a
   * device, is reported and dropped. VM errors such as out-of-memory still propagate.
   */
  @JvmStatic
  @DoNotStrip
  fun warmUp(context: Context) {
    if (!hasRun.compareAndSet(false, true)) {
      return
    }
    try {
      lastResult = runWarmUp(context)
    } catch (e: Exception) {
      reportFailure(e)
    } catch (e: LinkageError) {
      reportFailure(e)
    }
  }

  private fun reportFailure(cause: Throwable) {
    ErrorReporter.report(
        LogLevel.WARNING,
        "PrimitiveTextWarmUp:Failed",
        "Primitive Text warm-up failed, so its methods may stay out of the ART profile",
        cause,
    )
  }

  private fun runWarmUp(context: Context): Any {
    val style = TextStyle().apply { textSize = WARM_UP_TEXT_SIZE_PX }
    val behavior = primitive(style).layoutBehavior
    val isEquivalent = behavior.isEquivalentTo(primitive(style.makeCopy()).layoutBehavior)

    val result =
        TextMeasurementUtils.layout(
            context,
            MeasureSpec.makeMeasureSpec(WARM_UP_MAX_WIDTH_PX, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            WARM_UP_TEXT,
            style,
        )
    val size = result.first
    val view = RCTextView(context)
    view.mount(result.second)
    view.measure(exactly(size.width()), exactly(size.height()))
    view.layout(0, 0, size.width(), size.height())
    view.draw(Canvas(createBitmap(1, 1, Bitmap.Config.ALPHA_8)))
    view.unmount()

    return isEquivalent to size
  }

  // Same flag Litho's Text passes, so the warmed constructor and equivalence paths match its own.
  private fun primitive(style: TextStyle) = RichTextPrimitive(
      id = WARM_UP_ID,
      text = WARM_UP_TEXT,
      style = style,
      useResolvedLayoutDirection = true,
  )

  private fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

  private const val WARM_UP_ID = Long.MIN_VALUE
  private const val WARM_UP_TEXT = "Warm up"
  private const val WARM_UP_TEXT_SIZE_PX = 42
  private const val WARM_UP_MAX_WIDTH_PX = 1080
}
