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

package com.facebook.litho.widget.collection

import com.facebook.litho.ThreadUtils
import com.facebook.litho.widget.GridLayoutInfo
import com.facebook.litho.widget.LayoutInfo
import com.facebook.rendercore.FastMath.round
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.areCompatible
import com.facebook.rendercore.toHeightSpec
import com.facebook.rendercore.toWidthSpec
import com.facebook.rendercore.utils.MeasureSpecUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Calculates the size constraints for child items within a RecyclerView-like collection.
 *
 * @param item The collection item for which to calculate size constraints
 * @param collectionSize The size of the collection container. Defaults to the collection size
 *   obtained from ItemSizeConstraintsProviderScope.
 * @return SizeConstraints object containing the width and height measurement specifications
 *   (EXACTLY, AT_MOST, or UNSPECIFIED) that should be used when measuring the child item
 */
fun ItemSizeConstraintsProviderScope.getChildSizeConstraints(
    item: CollectionItem<*>,
    collectionSize: IntSize = this.collectionSize,
): SizeConstraints {

  if (layoutInfo is GridLayoutInfo) {
    val sizeConstraints = layoutInfo.sizeConstraintsCache[item]
    if (sizeConstraints != null) {
      return sizeConstraints
    }
  }

  val (widthSpec, heightSpec) =
      if (collectionSize.isValid && !isDynamicSize) {
        // The size of the collection is determined so we can use it to measure the child for the
        // second pass.
        val measuredSize: IntSize = collectionSize

        val resolvedWidthSpec =
            if (isMatchingParentSize(item.renderInfo.parentWidthPercent)) {
              MeasureSpecUtils.exactly(
                  round(measuredSize.width * item.renderInfo.parentWidthPercent / 100),
              )
            } else {
              layoutInfo.getChildWidthSpec(
                  MeasureSpecUtils.exactly(measuredSize.width),
                  item.renderInfo,
              )
            }
        val resolvedHeightSpec =
            if (isMatchingParentSize(item.renderInfo.parentHeightPercent)) {
              MeasureSpecUtils.exactly(
                  round(measuredSize.height * item.renderInfo.parentHeightPercent / 100),
              )
            } else {
              layoutInfo.getChildHeightSpec(
                  MeasureSpecUtils.exactly(measuredSize.height),
                  item.renderInfo,
              )
            }
        resolvedWidthSpec to resolvedHeightSpec
      } else {
        // 1) We haven't measured the collection yet, delegating to the layout info to measure the
        // child for the first pass.
        // 2) We've measured the collection but the wrap mode is dynamic, so we need to ensure the
        // child is aligned with the size of the collection.
        val shouldMeasureWidthDynamically = (isDynamicSize && isVertical)
        val size = item.size
        val resolvedWidthSpec =
            if (shouldMeasureWidthDynamically) {
              if (!size.isValid) {
                // We haven't measured the child yet, so we need to measure its actual size.
                MeasureSpecUtils.unspecified()
              } else {
                // We've known the size of the child, aligning with the size of the collection.
                MeasureSpecUtils.exactly(max(collectionSize.width, size.width))
              }
            } else {
              layoutInfo.getChildWidthSpec(collectionConstraints.toWidthSpec(), item.renderInfo)
            }
        val shouldMeasureHeightDynamically = (isDynamicSize && !isVertical)
        val resolvedHeightSpec =
            if (shouldMeasureHeightDynamically) {
              if (!size.isValid) {
                // We haven't measured the child yet, so we need to measure its actual size.
                MeasureSpecUtils.unspecified()
              } else {
                // We've known the size of the child, aligning with the size of the collection.
                MeasureSpecUtils.exactly(max(collectionSize.height, size.height))
              }
            } else {
              layoutInfo.getChildHeightSpec(collectionConstraints.toHeightSpec(), item.renderInfo)
            }
        resolvedWidthSpec to resolvedHeightSpec
      }

  return SizeConstraints.fromMeasureSpecs(widthSpec, heightSpec)
}

/** Validates the size constraints for the Recycler. */
private fun validateSizeConstraint(
    sizeConstraints: SizeConstraints,
    isVertical: Boolean,
    wrapInMainAxis: Boolean,
    crossAxisWrapMode: CrossAxisWrapMode,
) {
  val noBoundedSizeInMainAxis =
      if (isVertical) {
        !sizeConstraints.hasBoundedHeight
      } else {
        !sizeConstraints.hasBoundedWidth
      }
  val noBoundedSizeInCrossAxis =
      if (isVertical) {
        !sizeConstraints.hasBoundedWidth
      } else {
        !sizeConstraints.hasBoundedHeight
      }
  if (!wrapInMainAxis && noBoundedSizeInMainAxis) {
    throw IllegalArgumentException(
        "${if (isVertical) "Height" else "Width"}[main axis] has to be EXACTLY OR AT MOST " +
            "for a scrolling RecyclerView.",
    )
  }
  if (crossAxisWrapMode == CrossAxisWrapMode.NoWrap && noBoundedSizeInCrossAxis) {
    throw IllegalArgumentException(
        "Can't use Unspecified ${if (isVertical) "width" else "height"} on a scrolling " +
            "RecyclerView if dynamic measurement is not allowed, please use " +
            "[CrossAxisWrapMode.MatchFirstChild] or [CrossAxisWrapMode.Dynamic] instead.",
    )
  }
}

/** Measures the size of the Collection. */
fun CollectionLayoutScope.calculateLayout(): IntSize {

  validateSizeConstraint(collectionConstraints, isVertical, wrapInMainAxis, crossAxisWrapMode)

  if (collectionConstraints.hasExactWidth && collectionConstraints.hasExactHeight) {
    // The collection has a fixed size, so we don't need to measure it again.
    // However, if we're on the UI thread, perform synchronous layout for visible items
    // to avoid the "Christmas light" effect where items appear inconsistently.
    if (ThreadUtils.isMainThread) {
      prepareVisibleItemsSync(
          IntSize(collectionConstraints.maxWidth, collectionConstraints.maxHeight),
      )
    }
    return IntSize(collectionConstraints.maxWidth, collectionConstraints.maxHeight)
  }

  var sizeInMainAxis: Int =
      if (isVertical) {
        collectionConstraints.maxHeight
      } else {
        collectionConstraints.maxWidth
      }
  var sizeInCrossAxis: Int =
      if (isVertical) {
        collectionConstraints.maxWidth
      } else {
        collectionConstraints.maxHeight
      }

  if (wrapInMainAxis || crossAxisWrapMode == CrossAxisWrapMode.Dynamic) {
    // We need to measure items in the viewport to get the size of the collection.
    val filler: LayoutInfo.ViewportFiller? =
        layoutInfo.createViewportFiller(
            collectionConstraints.maxWidth,
            collectionConstraints.maxHeight,
        )

    if (filler != null) {
      // The viewport info might not be reliable because user could be scrolling the list at the
      // same time, we should double check when commiting the changeset.
      var maxChildWidthInTheViewport = max(0, collectionSize.width)
      var maxChildHeightInTheViewport = max(0, collectionSize.height)
      var index: Int = max(0, firstVisiblePosition)

      while (filler.wantsMore() && index < collectionItems.size) {
        val item = collectionItems[index]
        val size = getMeasuredSize(item)
        filler.add(item.renderInfo, size.width, size.height)
        maxChildWidthInTheViewport = max(maxChildWidthInTheViewport, size.width)
        maxChildHeightInTheViewport = max(maxChildHeightInTheViewport, size.height)
        index++
      }
      if (wrapInMainAxis) {
        sizeInMainAxis = min(sizeInMainAxis, filler.getFill())
      }

      if (crossAxisWrapMode == CrossAxisWrapMode.Dynamic) {
        sizeInCrossAxis =
            if (isVertical) maxChildWidthInTheViewport else maxChildHeightInTheViewport
      }
    }
  }

  if (crossAxisWrapMode == CrossAxisWrapMode.MatchFirstChild && collectionItems.isEmpty()) {
    // Nothing to match yet: take the least cross-axis space allowed, like RecyclerBinder.
    sizeInCrossAxis =
        if (isVertical) collectionConstraints.minWidth else collectionConstraints.minHeight
  } else if (crossAxisWrapMode == CrossAxisWrapMode.MatchFirstChild) {
    // Measure against the incoming constraints, not the last committed size, which comes from an
    // earlier pass with other items or constraints.
    val firstChild = collectionItems.first()
    val firstChildSize =
        getMeasuredSize(firstChild, getChildSizeConstraints(firstChild, IntSize.Invalid))
    sizeInCrossAxis = if (isVertical) firstChildSize.width else firstChildSize.height

    // Perform synchronous layout for other visible items when on the UI thread
    // to avoid the "Christmas light" effect where items appear inconsistently.
    if (ThreadUtils.isMainThread) {
      // Use the first child's cross-axis dimension for correct constraints.
      val prepCollectionSize =
          if (isVertical) {
            IntSize(firstChildSize.width, collectionConstraints.maxHeight)
          } else {
            IntSize(collectionConstraints.maxWidth, firstChildSize.height)
          }
      prepareVisibleItemsSync(prepCollectionSize, firstChildSize, startIndex = 1)
    }
  }

  return if (isVertical) {
    IntSize(sizeInCrossAxis, sizeInMainAxis)
  } else {
    IntSize(sizeInMainAxis, sizeInCrossAxis)
  }
}

/**
 * Performs synchronous layout for visible items to ensure consistent rendering.
 *
 * This is called when calculateLayout() runs on the UI thread to avoid the "Christmas light" effect
 * where items appear/disappear inconsistently due to async layout completing at different times. By
 * doing the layout synchronously, all visible items are ready at the same time.
 *
 * Note: This may increase TTRC (Time to Render Content) but provides a more accurate measurement of
 * the true rendering cost. If product teams want to optimize TTRC, they can explicitly defer
 * rendering this UI in product code.
 *
 * @param collectionSize The size to use for calculating child constraints
 * @param firstChildSize Optional pre-measured first child size. If null, first child will be
 *   measured.
 * @param startIndex The index to start preparing from (0 to include first child, 1 to skip it)
 */
private fun CollectionLayoutScope.prepareVisibleItemsSync(
    collectionSize: IntSize,
    firstChildSize: IntSize? = null,
    startIndex: Int = 0,
) {
  if (collectionItems.isEmpty()) return

  val measuredFirstChildSize =
      firstChildSize
          ?: run {
            val constraints = getChildSizeConstraints(collectionItems.first(), collectionSize)
            getMeasuredSize(collectionItems.first(), constraints)
          }

  val itemCount = collectionItems.size
  if (startIndex >= itemCount) return

  // Estimate the number of visible items in the viewport.
  // We use an approximation based on the first child's size to determine how many items
  // need to be laid out synchronously.
  val estimatedVisibleCount = max(
      layoutInfo.approximateRangeSize(
          measuredFirstChildSize.width,
          measuredFirstChildSize.height,
          collectionConstraints.maxWidth,
          collectionConstraints.maxHeight,
      ),
      1,
  )
  val endIndex = minOf(startIndex + estimatedVisibleCount, itemCount)

  // Synchronously measure each visible item to ensure they're all ready at the same time
  for (index in startIndex until endIndex) {
    val item = collectionItems[index]
    val constraints = getChildSizeConstraints(item, collectionSize)
    getMeasuredSize(item, constraints)
  }
}

/**
 * Gets the size of a collection item, either from its cached size or by measuring it.
 *
 * @param item The collection item to get the size for
 * @param constraints The size constraints to use for measuring. Defaults to child size constraints
 *   calculated from the ItemSizeConstraintsProviderScope. These constraints define the minimum and
 *   maximum width/height bounds for the item.
 * @return The size of the item as a Size object containing width and height values, either
 *   retrieved from cache or freshly measured
 */
internal fun ItemSizeConstraintsProviderScope.getMeasuredSize(
    item: CollectionItem<*>,
    constraints: SizeConstraints = getChildSizeConstraints(item),
): IntSize {
  val itemSize =
      if (item.areSizeConstraintsCompatible(constraints)) {
        item.size
      } else {
        IntSize.Invalid
      }
  return if (itemSize.isValid) {
    itemSize
  } else {
    val output = IntArray(2)
    item.measure(constraints, output)
    IntSize(output[0], output[1])
  }
}

/**
 * Checks if the provided size constraints are compatible with this CollectionItem's current size
 * constraints and measured size.
 *
 * @param constraints The size constraints to compare against this item's constraints
 * @return true if the constraints are compatible with this item's size and constraints, false
 *   otherwise
 */
fun CollectionItem<*>.areSizeConstraintsCompatible(constraints: SizeConstraints): Boolean {
  if (!size.isValid) {
    return false
  }
  val localConstraints = sizeConstraints ?: return false
  return constraints.areCompatible(localConstraints, size)
}

private fun isMatchingParentSize(percent: Float): Boolean {
  return percent in 0.0..100.0
}
