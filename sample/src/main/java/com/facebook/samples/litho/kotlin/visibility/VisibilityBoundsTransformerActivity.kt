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

package com.facebook.samples.litho.kotlin.visibility

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.view.background
import com.facebook.litho.visibility.onInvisible
import com.facebook.litho.visibility.onVisible
import com.facebook.litho.widget.collection.LazyList
import com.facebook.rendercore.dp
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.visibility.VisibilityBoundsTransformer
import com.facebook.rendercore.visibility.VisibilityOutput
import com.facebook.samples.litho.NavigatableDemoActivity

class VisibilityBoundsTransformerActivity : NavigatableDemoActivity() {
  var occludingHeaderView: View? = null

  private sealed class Occlusion {
    object FullyOccluded : Occlusion()

    object NotOccluded : Occlusion()

    data class PartiallyOccluded(val occludedPixels: Int) : Occlusion()
  }

  private fun calculateOcclusion(hostBounds: Rect, headerBounds: Rect): Occlusion {
    return when {
      // Host is entirely behind the header
      hostBounds.bottom <= headerBounds.bottom -> Occlusion.FullyOccluded
      // Host's top is behind the header (partial overlap)
      hostBounds.top < headerBounds.bottom -> {
        val occludedPixels = headerBounds.bottom - hostBounds.top
        Occlusion.PartiallyOccluded(occludedPixels)
      }
      // Host is fully below the header
      else -> Occlusion.NotOccluded
    }
  }

  val visibilityBoundsTransformer: VisibilityBoundsTransformer =
      object : VisibilityBoundsTransformer {
        // Reusable Rect instances to avoid allocations during scroll
        private val headerBounds = Rect()
        private val hostBounds = Rect()

        override fun getTransformedLocalVisibleRect(host: ViewGroup, outRect: Rect) {
          val header = occludingHeaderView
          requireNotNull(header) { "header view cannot be null" }

          if (!header.isLaidOut) {
            // Header not ready, treat as fully occluded
            outRect.setEmpty()
            return
          }

          header.getGlobalVisibleRect(headerBounds)
          host.getGlobalVisibleRect(hostBounds)

          when (val occlusion = calculateOcclusion(hostBounds, headerBounds)) {
            Occlusion.FullyOccluded -> outRect.setEmpty()
            Occlusion.NotOccluded -> host.getLocalVisibleRect(outRect)
            is Occlusion.PartiallyOccluded -> {
              host.getLocalVisibleRect(outRect)
              outRect.top = occlusion.occludedPixels
            }
          }
        }

        override fun getViewportRect(view: View, outRect: Rect) {
          view.getGlobalVisibleRect(outRect)
        }

        override fun shouldUseTransformedVisibleRect(visibilityOutput: VisibilityOutput): Boolean {
          return true
        }
      }

  // ComponentsConfiguration has a non-public ctor; copy() is the only way to derive variants.
  @Suppress("DATA_CLASS_INVISIBLE_COPY_USAGE_WARNING")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val parentContainer = FrameLayout(this)
    val lithoView =
        LithoView.create(
            ComponentContext(this),
            ComponentTree.create(this, ParentComponent())
                .componentsConfiguration(
                    ComponentsConfiguration.defaultInstance.copy(
                        visibilityBoundsTransformer = visibilityBoundsTransformer,
                    ),
                )
                .build(),
        )
    occludingHeaderView =
        TextView(this).apply {
          setBackgroundColor(Color.parseColor("#80FF0000"))
          gravity = Gravity.CENTER
          text = "Occluding Header"
        }
    parentContainer.addView(lithoView)
    parentContainer.addView(occludingHeaderView, FrameLayout.LayoutParams(MATCH_PARENT, 500))
    setContentView(parentContainer)

    occludingHeaderView
        ?.viewTreeObserver
        ?.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
              override fun onGlobalLayout() {
                // notify the LithoView that the visible bounds have changed when the header view is
                // laid out (layout change = VISIBILITY_HINT as it's a programmatic trigger)
                lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.VISIBILITY_HINT)
                occludingHeaderView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
              }
            },
        )
  }

  private class ParentComponent : KComponent() {
    override fun ComponentScope.render(): Component? {
      return LazyList { (0..50).forEach { child(id = it, component = VisibilityComponent(it)) } }
    }
  }

  private class VisibilityComponent(private val index: Int) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val boundaryStroke =
          Paint().apply {
            color = Color.parseColor("#88000000")
            style = Paint.Style.STROKE
            strokeWidth = 2f
          }
      return Column(
          style =
              Style.height(50.dp)
                  .width(200.dp)
                  .background(StrokeBackgroundDrawable(boundaryStroke))
                  .onVisible { Log.d("VisibilityComponentLog", "$index onVisible") }
                  .onInvisible { Log.d("VisibilityComponentLog", "$index onInvisible") },
      ) {
        child(Text("Hello World $index"))
      }
    }
  }

  private class StrokeBackgroundDrawable(private val paint: Paint) : Drawable() {
    override fun draw(canvas: Canvas) {
      canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
      paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
      paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
      return PixelFormat.TRANSLUCENT
    }
  }
}
