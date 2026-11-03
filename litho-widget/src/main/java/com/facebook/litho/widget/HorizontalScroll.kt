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

package com.facebook.litho.widget

import android.view.View
import android.view.ViewGroup
import com.facebook.litho.Component
import com.facebook.litho.LayoutState
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.NestedLithoTree
import com.facebook.litho.NestedLithoTreeState
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.ResolveResult
import com.facebook.litho.Style
import com.facebook.litho.bindToRenderTreeView
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.layoutVersion
import com.facebook.litho.useCached
import com.facebook.litho.useNestedTree
import com.facebook.litho.useState
import com.facebook.litho.utils.MeasureUtils.getChildMeasureSize
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.MaxPossibleWidthValue
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.utils.MeasureSpecUtils.getMode
import kotlin.math.max
import kotlin.math.min

class HorizontalScroll(
    val child: Component,
    val scrollbarEnabled: Boolean = true,
    val horizontalFadingEdgeEnabled: Boolean = false,
    val fillViewport: Boolean = false,
    val overScrollMode: Int = View.OVER_SCROLL_IF_CONTENT_SCROLLS,
    val fadingEdgeLength: Dimen = 0.dp,
    val initialScrollPosition: Dimen = LAST_SCROLL_POSITION_UNSET.dp,
    val eventsController: HorizontalScrollEventsController? = null,
    val onScrollChangeListener: HorizontalScrollLithoView.OnScrollChangeListener? = null,
    val scrollStateListener: ScrollStateListener? = null,
    val incrementalMountEnabled: Boolean = false,
    val wrapContent: Boolean = false, // TODO:T182959582
    val style: Style = Style,
) : PrimitiveComponent() {

  companion object {
    const val LAST_SCROLL_POSITION_UNSET = -1

    /** Factory method for Java callers (child only, all defaults). */
    @JvmStatic fun create(child: Component): HorizontalScroll = HorizontalScroll(child = child)

    /** Factory method for Java callers (child + scrollbarEnabled). */
    @JvmStatic
    fun create(child: Component, scrollbarEnabled: Boolean): HorizontalScroll =
        HorizontalScroll(child = child, scrollbarEnabled = scrollbarEnabled)
  }

  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    val fadingEdgeLengthPx = fadingEdgeLength.toPixels()
    val config =
        useCached(incrementalMountEnabled) {
          val defaultConfig = context.lithoConfiguration.componentsConfig
          if (incrementalMountEnabled != defaultConfig.incrementalMountEnabled) {
            defaultConfig.copy(incrementalMountEnabled = incrementalMountEnabled)
          } else {
            defaultConfig
          }
        }
    val (
        state: NestedLithoTreeState,
        resolveResult: ResolveResult,
    ) = useNestedTree(root = child, config = config, treeProps = context.treePropContainer)

    val scrollPosition = useState {
      HorizontalScrollLithoView.ScrollPosition(
          if (initialScrollPosition == LAST_SCROLL_POSITION_UNSET.dp) {
            LAST_SCROLL_POSITION_UNSET
          } else {
            initialScrollPosition.toPixels()
          },
      )
    }

    return LithoPrimitive(
        layoutBehavior =
            HorizontalScrollLayoutBehavior(
                resolveResult = resolveResult,
                fillViewport = fillViewport,
            ),
        mountBehavior =
            MountBehavior(
                { "HorizontalScroll" },
                ViewAllocator { context ->
                  HorizontalScrollLithoView(context, LithoRenderTreeView(context))
                },
            ) {
              // bind to LithoRenderTreeView
              bindToRenderTreeView(state = state) { renderTreeView as LithoRenderTreeView }

              withDescription("scrollbarEnabled") {
                bind(scrollbarEnabled) { content ->
                  content.isHorizontalScrollBarEnabled = scrollbarEnabled
                  onUnbind { content.isHorizontalScrollBarEnabled = false }
                }
              }

              withDescription("overScrollMode") {
                overScrollMode.bindTo(
                    HorizontalScrollLithoView::setOverScrollMode,
                    View.OVER_SCROLL_IF_CONTENT_SCROLLS,
                )
              }
              withDescription("horizontalFadingEdgeEnabled") {
                horizontalFadingEdgeEnabled.bindTo(
                    HorizontalScrollLithoView::setHorizontalFadingEdgeEnabled,
                    false,
                )
              }

              withDescription("scrollbarFadingEnabled") {
                fadingEdgeLengthPx.bindTo(HorizontalScrollLithoView::setFadingEdgeLength, 0)
              }

              withDescription("scrollPosition") {
                scrollPosition.value.bindTo(HorizontalScrollLithoView::setScrollPosition, null)
              }

              withDescription("onScrollStateChange") {
                onScrollChangeListener.bindTo(
                    HorizontalScrollLithoView::setOnScrollChangeListener,
                    null,
                )
              }

              withDescription("scrollStateListener") {
                scrollStateListener.bindTo(HorizontalScrollLithoView::setScrollStateListener, null)
              }

              withDescription("eventsController") {
                bind(eventsController) { content ->
                  eventsController?.setScrollableView(content)
                  onUnbind { eventsController?.setScrollableView(null) }
                }
              }
            },
        style = style,
    )
  }
}

internal class HorizontalScrollLayoutBehavior(
    private val resolveResult: ResolveResult,
    private val fillViewport: Boolean,
) : LayoutBehavior {
  override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {

    val constraints: SizeConstraints =
        if (fillViewport) {
          if (!sizeConstraints.hasBoundedWidth) {
            sizeConstraints.copy(
                minWidth = 0,
                maxWidth = sizeConstraints.maxWidth,
            )
          } else {
            val width = min(sizeConstraints.MaxPossibleWidthValue, sizeConstraints.maxWidth)
            if (width != sizeConstraints.maxWidth) {
              DebugInfoReporter.report(category = "SizeConstraintViolation") {
                this["component"] = resolveResult.component.simpleName
                this["sizeConstraints"] = sizeConstraints.toString()
                this["MaxPossibleWidthValue"] = sizeConstraints.MaxPossibleWidthValue
              }
            }
            sizeConstraints.copy(
                minWidth = width,
                maxWidth = SizeConstraints.Infinity,
            )
          }
        } else {
          SizeConstraints(
              maxWidth = SizeConstraints.Infinity,
              maxHeight = sizeConstraints.maxHeight,
          )
        }

    val (minMeasureHeight, maxMeasureHeight) =
        getChildMeasureSize(
            constraints.minHeight,
            constraints.maxHeight,
            ViewGroup.LayoutParams.MATCH_PARENT,
            getMode(sizeConstraints.toHeightSpec()),
        )
    val childConstraints =
        constraints.copy(minHeight = minMeasureHeight, maxHeight = maxMeasureHeight)

    // Use the layout version to ensure version consistency during double measures
    val layoutState =
        NestedLithoTree.layout(
            result = resolveResult,
            sizeConstraints = childConstraints,
            current = previousLayoutData as LayoutState?,
            version = layoutVersion,
        )

    // Ensure that height is not less than 0
    val height: Int = max(sizeConstraints.minHeight, layoutState.height)

    // Compute the appropriate size depending on the widthSpec
    val width: Int =
        if (sizeConstraints.hasExactWidth) {
          // If this Horizontal scroll is being measured with an exact width we
          // don't care about the size of the content and just use that instead
          sizeConstraints.maxWidth
        } else if (sizeConstraints.hasBoundedWidth) {
          // For bounded width we want the HorizontalScroll to be as big as its
          // content up to the maximum width specified in the widthSpec
          max(sizeConstraints.minWidth, min(sizeConstraints.maxWidth, layoutState.width))
        } else {
          max(sizeConstraints.minWidth, layoutState.width)
        }

    layoutState.toRenderTree()

    return PrimitiveLayoutResult(width = width, height = height, layoutData = layoutState)
  }
}
