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

package com.facebook.samples.litho.kotlin.mountandvisibility

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Style
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.kotlin.widget.VerticalScroll
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.view.backgroundColor
import com.facebook.rendercore.dp
import com.facebook.rendercore.primitives.FixedSizeLayoutBehavior
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.rendercore.sp
import com.facebook.yoga.YogaAlign

/**
 * Demo component showing how to exclude items from incremental mount.
 *
 * Setting `shouldExcludeFromIncrementalMount = true` in a Primitive's MountBehavior causes that
 * component to remain mounted even when scrolled completely out of view. This is useful for:
 * - Components that hold expensive state that shouldn't be recreated when scrolling back
 * - TextInput components that should retain focus and text when scrolled away
 * - Components with expensive initialization that shouldn't repeat
 * - Video players that should continue buffering when scrolled away
 *
 * IMPORTANT: This demo uses VerticalScroll (not LazyList/RecyclerView) because
 * shouldExcludeFromIncrementalMount only affects Litho's incremental mount system. RecyclerView has
 * its own view recycling that operates independently.
 *
 * The demo shows a list with two types of items:
 * 1. Normal items (gray indicator) - mount/unmount based on visibility
 * 2. Excluded items (blue indicator) - stay mounted even when not visible
 *
 * Scroll the list and observe:
 * - Normal items: MOUNTED when visible, UNMOUNTED when scrolled away
 * - Excluded items: MOUNTED once, never UNMOUNTED
 */
class IncrementalMountExcludeComponent(
    private val style: Style = Style,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    return Column(style = style + Style.flex(grow = 1f).backgroundColor(Color.WHITE)) {
      child(
          Column(style = Style.flex(shrink = 0f).padding(all = 16.dp)) {
            child(
                Text(
                    text = "Exclude from Incremental Mount Demo",
                    textSize = 20.sp,
                    style = Style.padding(bottom = 8.dp),
                ),
            )
            child(
                Text(
                    text =
                        "Gray indicators = Normal items (mount/unmount on scroll)\n" +
                            "Blue indicators = Excluded items (stay mounted)",
                    textSize = 14.sp,
                    textColor = Color.DKGRAY,
                    style = Style.padding(bottom = 8.dp),
                ),
            )
          },
      )

      child(
          VerticalScroll(style = Style.flex(grow = 1f)) {
            Column {
              for (i in 1..30) {
                val isExcluded = i % 5 == 0 // Every 5th item is excluded from IM
                child(
                    ExcludeDemoItemComponent(
                        itemIndex = i,
                        isExcludedFromIM = isExcluded,
                    ),
                )
              }
            }
          },
      )
    }
  }
}

/** Item component that can be either normal or excluded from incremental mount. */
class ExcludeDemoItemComponent(
    private val itemIndex: Int,
    private val isExcludedFromIM: Boolean,
    private val style: Style = Style,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val bgColor =
        if (isExcludedFromIM) {
          "#E3F2FD".toColorInt()
        } else if (itemIndex % 2 == 0) {
          "#FFFFFF".toColorInt()
        } else {
          "#F5F5F5".toColorInt()
        }

    val label =
        if (isExcludedFromIM) {
          "Item $itemIndex (EXCLUDED from IM)"
        } else {
          "Item $itemIndex"
        }

    return Row(
        alignItems = YogaAlign.CENTER,
        style =
            style +
                Style.flex(shrink = 0f)
                    .height(60.dp)
                    .margin(bottom = 4.dp)
                    .padding(horizontal = 16.dp)
                    .backgroundColor(bgColor),
    ) {
      if (isExcludedFromIM) {
        child(
            ExcludedMountIndicator(
                tag = "ExcludeIM",
                itemId = "Item$itemIndex",
                style = Style.width(24.dp).height(24.dp).margin(end = 12.dp),
            ),
        )
      } else {
        child(
            NormalMountIndicator(
                tag = "ExcludeIM",
                itemId = "Item$itemIndex",
                style = Style.width(24.dp).height(24.dp).margin(end = 12.dp),
            ),
        )
      }
      child(
          Text(
              text = label,
              textSize = 16.sp,
              style = Style.flex(shrink = 0f),
          ),
      )
    }
  }
}

// start_exclude_example
/**
 * Primitive that is EXCLUDED from incremental mount.
 *
 * This component will NOT be unmounted when scrolled out of view. It stays mounted as long as the
 * LithoView/screen is alive. Note: It will still be unmounted when the screen is destroyed (e.g.,
 * Activity finishes or Fragment is removed). The `shouldExcludeFromIncrementalMount = true` flag
 * only affects scroll-based incremental mount behavior, not lifecycle-based unmounting.
 */
class ExcludedMountIndicator(
    private val tag: String,
    private val itemId: String,
    private val style: Style? = null,
) : PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    return LithoPrimitive(
        layoutBehavior = FixedSizeLayoutBehavior(24.px, 24.px),
        mountBehavior =
            MountBehavior(ViewAllocator { context -> View(context) }) {
              // THIS IS THE KEY: Exclude this component from incremental mount
              @Suppress("PrimitiveExcludeFromIncrementalMountDetector")
              shouldExcludeFromIncrementalMount = true

              bind(itemId) { content ->
                ViewCompat.setBackground(content, ColorDrawable(Color.BLUE))
                Log.d(tag, "MOUNTED (EXCLUDED): $itemId - Will NOT unmount on scroll")

                onUnbind {
                  // This should NEVER be called for excluded items during scrolling!
                  ViewCompat.setBackground(content, ColorDrawable(Color.RED))
                  Log.d(tag, "UNMOUNTED (EXCLUDED): $itemId - This shouldn't happen!")
                }
              }
            },
        style = style,
    )
  }
}

// end_exclude_example

/**
 * Normal primitive that participates in incremental mount.
 *
 * This component will be mounted/unmounted based on visibility.
 */
class NormalMountIndicator(
    private val tag: String,
    private val itemId: String,
    private val style: Style? = null,
) : PrimitiveComponent() {

  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    return LithoPrimitive(
        layoutBehavior = FixedSizeLayoutBehavior(24.px, 24.px),
        mountBehavior =
            MountBehavior(ViewAllocator { context -> View(context) }) {
              // Default behavior: participates in incremental mount
              // shouldExcludeFromIncrementalMount = false (default)

              bind(itemId) { content ->
                ViewCompat.setBackground(content, ColorDrawable(Color.GRAY))
                Log.d(tag, "MOUNTED: $itemId")

                onUnbind {
                  ViewCompat.setBackground(content, ColorDrawable(Color.LTGRAY))
                  Log.d(tag, "UNMOUNTED: $itemId")
                }
              }
            },
        style = style,
    )
  }
}
