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

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.GuardedBy
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Releases the [ComponentTree]s built for an Activity once it is destroyed, so that an Activity
 * that leaks doesn't also keep their components, state, cached values and layouts alive.
 *
 * Does nothing until [install]. Trees are tracked weakly from creation and released on the main
 * thread, oldest first, a short while after the Activity's `onDestroy`, once its Fragments and
 * windows are torn down. Trees built after the Activity was destroyed are released the same way.
 *
 * Trees built with the application context, or with a context that goes through a
 * [MutableContextWrapper] (whose base can be swapped), belong to no Activity and are left alone, as
 * are those of an Activity destroyed for a configuration change, which its replacement can take
 * over. A tree of a destroyed Activity that is shown in a window when it is due means its views are
 * reused elsewhere, so from then on none of that Activity's trees are released, including later
 * ones.
 */
object ActivityScopedTreeReleaser {

  const val RELEASE_FAILED_CATEGORY: String = "ActivityScopedTreeReleaser:ReleaseFailed"
  const val TREES_KEPT_CATEGORY: String = "ActivityScopedTreeReleaser:TreesKept"

  @VisibleForTesting internal const val RELEASE_DELAY_MS = 5_000L
  private const val RELEASE_TIME_BUDGET_NS = 4_000_000L

  @Volatile
  internal var isInstalled: Boolean = false
    private set

  private val lock = Any()
  @GuardedBy("lock") private val trackedActivities = WeakHashMap<Activity, TrackedActivity>()
  private val handler by lazy { Handler(Looper.getMainLooper()) }
  private var releaseDelayMs = RELEASE_DELAY_MS
  private var inSlices = true
  private var sliceBudgetNs = RELEASE_TIME_BUDGET_NS
  private var sliceDelayMs = 0L

  @JvmStatic
  fun install(
      application: Application,
      releaseDelayMs: Long = RELEASE_DELAY_MS,
      releaseInSlices: Boolean = true,
      sliceBudgetNs: Long = RELEASE_TIME_BUDGET_NS,
      sliceDelayMs: Long = 0L,
  ) {
    synchronized(lock) {
      if (isInstalled) {
        return
      }
      this.releaseDelayMs = releaseDelayMs
      inSlices = releaseInSlices
      this.sliceBudgetNs = sliceBudgetNs
      this.sliceDelayMs = sliceDelayMs
      isInstalled = true
    }
    application.registerActivityLifecycleCallbacks(DestroyCallbacks)
  }

  @VisibleForTesting
  internal fun reset(application: Application) {
    application.unregisterActivityLifecycleCallbacks(DestroyCallbacks)
    handler.removeCallbacksAndMessages(null)
    synchronized(lock) {
      trackedActivities.clear()
      isInstalled = false
    }
  }

  internal fun onTreeCreated(tree: ComponentTree, context: Context) {
    val activity = findActivity(context) ?: return
    synchronized(lock) {
      val tracked =
          trackedActivities.getOrPut(activity) { TrackedActivity(activity.javaClass.name) }
      tracked.add(tree)
      if (activity.isDestroyed) {
        scheduleSweep(tracked)
      }
    }
  }

  private fun markDestroyed(activity: Activity) {
    synchronized(lock) {
      if (activity.isChangingConfigurations) {
        // Its replacement can take over its trees, e.g. through a ViewModel.
        trackedActivities
            .getOrPut(activity) { TrackedActivity(activity.javaClass.name) }
            .neverRelease()
      } else {
        trackedActivities[activity]?.let(::scheduleSweep)
      }
    }
  }

  private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
      when (current) {
        is Activity -> return current
        is MutableContextWrapper -> return null
        else -> current = current.baseContext
      }
    }
    return null
  }

  @GuardedBy("lock")
  private fun scheduleSweep(tracked: TrackedActivity) {
    if (tracked.dontRelease || tracked.sweepPending) {
      return
    }
    tracked.sweepPending = true
    handler.postDelayed({ sweep(tracked) }, releaseDelayMs)
  }

  @UiThread
  private fun sweep(tracked: TrackedActivity) {
    val trees = synchronized(lock) { tracked.takeTrees() }
    val shown = trees.firstOrNull { it.isShownInWindow() }
    if (shown == null) {
      if (inSlices) {
        releaseInSlices(ArrayDeque(trees))
      } else {
        trees.forEach(::release)
      }
      return
    }
    synchronized(lock) { tracked.neverRelease() }
    ErrorReporter.report(
        LogLevel.WARNING,
        TREES_KEPT_CATEGORY,
        "${shown.getSimpleName()} of destroyed ${tracked.activityName} is shown in a window, so " +
            "none of the Activity's trees are released",
    )
  }

  // Spread over several main-thread messages, as release() runs component cleanups.
  @UiThread
  private fun releaseInSlices(trees: ArrayDeque<ComponentTree>) {
    val start = System.nanoTime()
    do {
      release(trees.removeFirstOrNull() ?: return)
    } while (System.nanoTime() - start < sliceBudgetNs)
    handler.postDelayed({ releaseInSlices(trees) }, sliceDelayMs)
  }

  @UiThread
  private fun release(tree: ComponentTree) {
    if (tree.isReleased && tree.uiTreeState == null) {
      return
    }
    try {
      tree.release()
    } catch (failure: Throwable) {
      if (failure is VirtualMachineError) {
        throw failure
      }
      // A cleanup or an unmount threw part-way through release(). A second release() still drops
      // the tree's state, but skips the cleanups the first one didn't reach; the view is cleared
      // without unmounting in case unmounting threw.
      val releasedOnRetry =
          try {
            tree.lithoView?.setComponentTree(null, false)
            tree.release()
            true
          } catch (retryFailure: Throwable) {
            if (retryFailure is VirtualMachineError) {
              throw retryFailure
            }
            failure.addSuppressed(retryFailure)
            false
          }
      ErrorReporter.report(
          LogLevel.ERROR,
          RELEASE_FAILED_CATEGORY,
          "Releasing ${tree.getSimpleName() ?: tree.getReleasedComponent()} of a destroyed " +
              "Activity threw (${if (releasedOnRetry) "released on retry" else "not released"})",
          failure,
      )
    }
  }

  @UiThread
  private fun ComponentTree.isShownInWindow(): Boolean = lithoView?.isAttachedToWindow == true

  /** The ComponentTrees tied to one Activity, oldest first. Guarded by [lock]. */
  private class TrackedActivity(val activityName: String) {
    private var trees = ArrayList<WeakReference<ComponentTree>>()
    var sweepPending = false
    // Once set, none of the Activity's trees are tracked or released.
    var dontRelease = false
      private set

    fun add(tree: ComponentTree) {
      if (dontRelease) {
        return
      }
      if (trees.size % 1024 == 0) {
        trees.removeAll { it.get() == null }
      }
      trees.add(WeakReference(tree))
    }

    fun takeTrees(): List<ComponentTree> {
      sweepPending = false
      val taken = trees.mapNotNull { it.get() }
      trees = ArrayList()
      return taken
    }

    fun neverRelease() {
      dontRelease = true
      trees = ArrayList()
    }
  }

  private object DestroyCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityDestroyed(activity: Activity) = markDestroyed(activity)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  }
}
