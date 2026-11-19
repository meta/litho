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

package com.facebook.litho

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo.TouchDelegateInfo
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.collection.MutableScatterMap
import androidx.collection.SparseArrayCompat
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.MountItem

/** Compound touch delegate that forward touch events to recyclable inner touch delegates. */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
class TouchExpansionDelegate(host: ComponentHost?) : TouchDelegate(IGNORED_RECT, host) {

  private val delegates = SparseArrayCompat<InnerTouchDelegate?>()
  private var scrapDelegates: SparseArrayCompat<InnerTouchDelegate?>? = null
  private var touchDelegateInfoCache: TouchDelegateInfoCache? = null

  /**
   * Registers an inner touch delegate for the given view with the specified expansion. It assumes
   * the given view has its final bounds set.
   *
   * @param index The drawing order index of the given view.
   * @param view The view to which touch expansion should be applied.
   * @param item The mount item which requires touch expansion.
   */
  fun registerTouchExpansion(index: Int, view: View, item: MountItem) {
    delegates.put(index, InnerTouchDelegate(view, item))
    touchDelegateInfoCache = null
  }

  /**
   * Unregisters an inner touch delegate with the given index.
   *
   * @param index The drawing order index of the given view.
   */
  fun unregisterTouchExpansion(index: Int) {
    touchDelegateInfoCache = null
    if (maybeUnregisterFromScrap(index)) {
      return
    }
    delegates.remove(index)
  }

  private fun maybeUnregisterFromScrap(index: Int): Boolean {
    val touchDelegate = scrapDelegates?.get(index)
    if (touchDelegate != null) {
      scrapDelegates?.remove(index)
      return true
    }
    return false
  }

  fun draw(canvas: Canvas, paint: Paint) {
    for (i in delegates.size() - 1 downTo 0) {
      val delegate = delegates.valueAt(i)
      if (delegate != null) {
        val bounds = delegate.getDelegateBounds()
        if (bounds != null) {
          canvas.drawRect(bounds, paint)
        }
      }
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    for (i in delegates.size() - 1 downTo 0) {
      val touchDelegate = delegates.valueAt(i)
      if (touchDelegate?.onTouchEvent(event) == true) {
        return true
      }
    }
    return false
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  override fun onTouchExplorationHoverEvent(event: MotionEvent): Boolean = false

  /**
   * Reports the expanded touch bounds of every registered view, in host coordinates, so that
   * accessibility services see each enlarged touch target and the view it belongs to.
   *
   * The platform calls this on every touch exploration hover event, so the result is cached until a
   * delegate is registered, unregistered or moved, or a delegate's expanded bounds change.
   */
  @RequiresApi(Build.VERSION_CODES.Q)
  override fun getTouchDelegateInfo(): TouchDelegateInfo {
    if (!ComponentsConfiguration.enableTouchDelegateInfoForAccessibility) {
      return super.getTouchDelegateInfo()
    }
    touchDelegateInfoCache
        ?.takeIf { it.isValidFor(delegates) }
        ?.let {
          return it.info
        }
    val boundsSnapshot = IntArray(delegates.size() * BOUNDS_SIZE)
    val targetMap = MutableScatterMap<Region, View>(delegates.size())
    for (i in 0 until delegates.size()) {
      val delegate = delegates.valueAt(i)
      val bounds = delegate?.getDelegateBounds()
      writeBounds(bounds, boundsSnapshot, i * BOUNDS_SIZE)
      if (delegate == null || bounds == null) {
        continue
      }
      targetMap[Region(bounds)] = delegate.delegateView
    }
    // TouchDelegateInfo rejects an empty map and View reads this result without a null check, so
    // fall back to the platform's info (the host with empty bounds), which matches no point.
    // TouchDelegateInfo copies the map into its own, so a read-only view of this one is enough.
    val info =
        if (targetMap.isEmpty()) super.getTouchDelegateInfo()
        else TouchDelegateInfo(targetMap.asMap())
    touchDelegateInfoCache = TouchDelegateInfoCache(info, boundsSnapshot)
    return info
  }

  internal fun findDelegateViewAt(x: Int, y: Int): View? {
    for (i in delegates.size() - 1 downTo 0) {
      delegates.valueAt(i)?.findDelegateViewAt(x, y)?.let {
        return it
      }
    }
    return null
  }

  /**
   * Called when the MountItem this Delegate is referred to is moved to another position to also
   * update the indexes of the TouchExpansionDelegate.
   */
  fun moveTouchExpansionIndexes(oldIndex: Int, newIndex: Int) {
    touchDelegateInfoCache = null
    if (delegates[newIndex] != null) {
      ensureScrapDelegates()
      ComponentHostUtils.scrapItemAt(newIndex, delegates, scrapDelegates)
    }
    ComponentHostUtils.moveItem(oldIndex, newIndex, delegates, scrapDelegates)
    releaseScrapDelegatesIfNeeded()
  }

  private fun ensureScrapDelegates() {
    if (scrapDelegates == null) {
      scrapDelegates = SparseArrayCompat(4)
    }
  }

  @VisibleForTesting fun size(): Int = delegates.size()

  private fun releaseScrapDelegatesIfNeeded() {
    if (CollectionsUtils.isEmpty(scrapDelegates)) {
      scrapDelegates = null
    }
  }

  /** A built [TouchDelegateInfo] and the expanded bounds of each delegate it was built from. */
  @RequiresApi(Build.VERSION_CODES.Q)
  private class TouchDelegateInfoCache(
      val info: TouchDelegateInfo,
      private val boundsSnapshot: IntArray,
  ) {
    fun isValidFor(delegates: SparseArrayCompat<InnerTouchDelegate?>): Boolean {
      if (delegates.size() * BOUNDS_SIZE != boundsSnapshot.size) {
        return false
      }
      for (i in 0 until delegates.size()) {
        val offset = i * BOUNDS_SIZE
        val delegate = delegates.valueAt(i)
        val matches =
            delegate?.hasDelegateBounds(boundsSnapshot, offset)
                ?: (boundsSnapshot[offset] == NO_BOUNDS)
        if (!matches) {
          return false
        }
      }
      return true
    }
  }

  private class InnerTouchDelegate(val delegateView: View, private val item: MountItem) {
    private var isHandlingTouch = false

    fun getDelegateBounds(): Rect? {
      val expansion =
          LithoLayoutData.getExpandedTouchBounds(item.renderTreeNode.layoutData) ?: return null
      val bounds = item.renderTreeNode.bounds
      return Rect(
          bounds.left - expansion.left,
          bounds.top - expansion.top,
          bounds.right + expansion.right,
          bounds.bottom + expansion.bottom,
      )
    }

    /** Whether [getDelegateBounds] equals the bounds [writeBounds] stored at [offset]. */
    fun hasDelegateBounds(snapshot: IntArray, offset: Int): Boolean {
      val expansion =
          LithoLayoutData.getExpandedTouchBounds(item.renderTreeNode.layoutData)
              ?: return snapshot[offset] == NO_BOUNDS
      val bounds = item.renderTreeNode.bounds
      return snapshot[offset] == bounds.left - expansion.left &&
          snapshot[offset + 1] == bounds.top - expansion.top &&
          snapshot[offset + 2] == bounds.right + expansion.right &&
          snapshot[offset + 3] == bounds.bottom + expansion.bottom
    }

    fun findDelegateViewAt(x: Int, y: Int): View? =
        if (getDelegateBounds()?.contains(x, y) == true) delegateView else null

    fun onTouchEvent(event: MotionEvent): Boolean {
      val x = event.x.toInt()
      val y = event.y.toInt()
      val delegateBounds = getDelegateBounds() ?: return false
      val slop = ViewConfiguration.get(delegateView.context).scaledTouchSlop
      val delegateSlopBounds = Rect()
      delegateSlopBounds.set(delegateBounds)
      delegateSlopBounds.inset(-slop, -slop)
      var shouldDelegateTouchEvent = false
      var touchWithinViewBounds = true
      var handled = false
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          isHandlingTouch = delegateBounds.contains(x, y)
          shouldDelegateTouchEvent = isHandlingTouch
        }
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_MOVE -> {
          shouldDelegateTouchEvent = isHandlingTouch
          if (isHandlingTouch) {
            if (!delegateSlopBounds.contains(x, y)) {
              touchWithinViewBounds = false
            }
          }
          if (event.action == MotionEvent.ACTION_UP) {
            isHandlingTouch = false
          }
        }
        MotionEvent.ACTION_CANCEL -> {
          shouldDelegateTouchEvent = isHandlingTouch
          isHandlingTouch = false
        }
        else -> {}
      }
      if (shouldDelegateTouchEvent) {
        if (touchWithinViewBounds) {
          // Offset event coordinates to be inside the target view.
          event.setLocation((delegateView.width / 2).toFloat(), (delegateView.height / 2).toFloat())
        } else {
          // Offset event coordinates to be outside the target view (in case it does
          // something like tracking pressed state).
          event.setLocation(-(slop * 2).toFloat(), -(slop * 2).toFloat())
        }
        handled = delegateView.dispatchTouchEvent(event)
      }
      return handled
    }
  }

  companion object {
    private val IGNORED_RECT = Rect()
    private const val BOUNDS_SIZE = 4
    private const val NO_BOUNDS = Int.MIN_VALUE

    private fun writeBounds(bounds: Rect?, out: IntArray, offset: Int) {
      if (bounds == null) {
        out.fill(NO_BOUNDS, offset, offset + BOUNDS_SIZE)
        return
      }
      out[offset] = bounds.left
      out[offset + 1] = bounds.top
      out[offset + 2] = bounds.right
      out[offset + 3] = bounds.bottom
    }
  }
}
