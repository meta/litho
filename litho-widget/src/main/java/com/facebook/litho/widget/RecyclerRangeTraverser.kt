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

/** An interface for generating traversing order for a range. */
fun interface RecyclerRangeTraverser {

  /**
   * Traverse the given range.
   *
   * @param rangeStart Start of the range, inclusive
   * @param rangeEnd End of the range, exclusive
   * @param firstVisible Index of the first visible item
   * @param lastVisible Index of the last visible item
   * @param handler Handler that will perform job for each index in the range and determines if we
   *   need to stop
   */
  fun traverse(
      rangeStart: Int,
      rangeEnd: Int,
      firstVisible: Int,
      lastVisible: Int,
      processor: Processor,
  )

  fun interface Processor {

    /** @return Whether or not to continue */
    fun process(index: Int): Boolean
  }

  companion object {
    @JvmField
    val FORWARD_TRAVERSER: RecyclerRangeTraverser =
        object : RecyclerRangeTraverser {
          override fun traverse(
              rangeStart: Int,
              rangeEnd: Int,
              firstVisible: Int,
              lastVisible: Int,
              processor: Processor,
          ) {
            for (i in rangeStart until rangeEnd) {
              if (!processor.process(i)) {
                return
              }
            }
          }
        }

    @JvmField
    val BACKWARD_TRAVERSER: RecyclerRangeTraverser =
        object : RecyclerRangeTraverser {
          override fun traverse(
              rangeStart: Int,
              rangeEnd: Int,
              firstVisible: Int,
              lastVisible: Int,
              processor: Processor,
          ) {
            for (i in rangeEnd - 1 downTo rangeStart) {
              if (!processor.process(i)) {
                return
              }
            }
          }
        }

    /**
     * A more optimized range traverser that expands from the center for the visible range so that
     * items adjacent to the visible ones are traversed first.
     */
    @JvmField
    val BIDIRECTIONAL_TRAVERSER: RecyclerRangeTraverser =
        object : RecyclerRangeTraverser {
          override fun traverse(
              rangeStart: Int,
              rangeEnd: Int,
              firstVisible: Int,
              lastVisible: Int,
              processor: Processor,
          ) {
            if (rangeEnd <= rangeStart) {
              return
            }

            val firstVisibleValid = firstVisible in rangeStart..<rangeEnd
            val lastVisibleValid = lastVisible in rangeStart..<rangeEnd
            val center =
                if (!firstVisibleValid && !lastVisibleValid) {
                  (rangeEnd + rangeStart - 1) / 2
                } else if (!firstVisibleValid) {
                  lastVisible
                } else if (!lastVisibleValid) {
                  firstVisible
                } else {
                  (firstVisible + lastVisible) / 2
                }

            if (!processor.process(center)) {
              return
            }

            var distance = 1
            while (true) {
              val head = center - distance
              val tail = center + distance
              val headInRange = head >= rangeStart
              val tailInRange = tail < rangeEnd

              if (!headInRange && !tailInRange) {
                return
              }

              if (headInRange) {
                if (!processor.process(head)) {
                  return
                }
              }

              if (tailInRange) {
                if (!processor.process(tail)) {
                  return
                }
              }

              distance += 1
            }
          }
        }
  }
}
