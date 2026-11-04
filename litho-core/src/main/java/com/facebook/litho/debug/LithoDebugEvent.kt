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

package com.facebook.litho.debug

import android.os.Process
import com.facebook.rendercore.MountStats
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventDispatcher

object LithoDebugEvent {

  val NoRenderStateId: () -> String = { "-1" }

  @JvmField var enableStackTraces: Boolean = true

  @JvmStatic
  fun generateStateTrace(): String {
    return if (enableStackTraces) {
      buildString {
        Throwable().stackTrace.forEachIndexed { index, item ->
          if (index > 4) { // skip first 4 elements since they are internal
            if (index > 5) { // add the joiner after the first element
              append('\n')
            }
            append(if (index > 5) "|${" ".repeat(12)}$item" else item.toString())
          }
        }
      }
    } else {
      "<disabled>"
    }
  }

  val RenderCore: DebugEvent.Companion = DebugEvent
  const val RenderRequest = "Litho.RenderRequest"
  const val LayoutCommitted = "Litho.LayoutCommitted"
  const val StateUpdateEnqueued = "Litho.StateUpdateEnqueued"
  const val RenderOnMainThreadStarted = "RenderOnMainThreadStarted"
  const val ComponentResolved = "Litho.Resolve.ComponentResolved"
  const val ComponentRendered = "Litho.Resolve.ComponentRendered"
  const val ComponentTreeResolve = "Litho.ComponentTree.Resolve"
  const val ComponentTreeLayout = "Litho.ComponentTree.Layout"
  const val ComponentTreeResume = "Litho.ComponentTree.Resume"
  const val TreeFutureRun = "Litho.TreeFuture.Run"
  const val TreeFutureGet = "Litho.TreeFuture.Get"
  const val TreeFutureGetPartial = "Litho.TreeFuture.GetPartial"
  const val TreeFutureWait = "Litho.TreeFuture.Wait"
  const val TreeFutureInterrupt = "Litho.TreeFuture.Interrupt"
  const val TreeFutureResume = "Litho.TreeFuture.Resume"
  const val ComponentTreeMountContentPreallocated = "Litho.ComponentTree.MountContent.Preallocated"
  const val TouchEvent = "Litho.Touch"
  const val DebugInfo = "Litho.DebugInfo" // used to report debug events upstream
  const val AsyncLayoutDropped = "Litho.AsyncLayout.Dropped"
  const val CollectionChangesetApplied = "Litho.Collection.ChangesetApplied"
  const val CollectionRangeComputed = "Litho.Collection.RangeComputed"
  const val RootCloned = "Litho.ComponentTree.RootCloned"
  const val MountEnd = "Litho.MountEnd"
}

/** Trigger that caused a [LithoDebugEvent.CollectionRangeComputed] event. */
enum class CollectionRangeTrigger {
  /** Initial range computation during first measure. */
  INITIAL,
  /** Range recomputation triggered by scrolling. */
  SCROLL,
  /** Range recomputation triggered by data changes (changeset applied). */
  DATA_CHANGE,
}

object LithoDebugEventAttributes {
  const val Root = "root"
  const val Attribution = "attribution"
  const val RootId = "root_id"
  const val CurrentRootId = "current_root_id"
  const val CurrentWidthSpec = "current_width_spec"
  const val CurrentHeightSpec = "current_height_spec"
  const val CurrentSizeConstraint = "current_size_constraint"
  const val SizeConstraint = "size_constraint"
  const val SizeSpecsMatch = "size_specs_match"
  const val IdMatch = "id_match"
  const val HasMainThreadLayoutState = "has_main_thread_layout_state"
  const val Breadcrumb = "breadcrumb"
  const val RenderExecutionMode = "execution-mode"
  const val Forced = "forced"

  const val Stack = "stack"
  const val Cause = "cause"
  const val WaitingOn = "waiting-on"

  /**
   * Monotonically increasing version identifying a specific resolve pass within a component tree.
   * Incremented by [ComponentTree] on each `setRoot` or state update that triggers a resolve.
   * Present on resolve-phase events ([LithoDebugEvent.ComponentTreeResolve],
   * [LithoDebugEvent.ComponentRendered]) and also on layout-phase events to enable cross-phase
   * correlation.
   */
  const val ResolveVersion = "resolve_version"

  /**
   * Monotonically increasing version identifying a specific layout pass within a component tree.
   * Incremented by [ComponentTree] each time a layout is computed from a resolve result. Present on
   * layout-phase events ([LithoDebugEvent.ComponentTreeLayout],
   * [LithoDebugEvent.AsyncLayoutDropped]) and mount events.
   */
  const val LayoutVersion = "layout_version"
  const val Reason = "reason"
  const val InitialResolve = "initial_resolve"
  const val IsFirstLayout = "is_first_layout"
  const val MountedCount = "mounted_count"
  const val UnmountedCount = "unmounted_count"
  const val UpdatedCount = "updated_count"
  const val ReusedCount = "reused_count"
  const val RemountedCount = "remounted_count"

  // Resolve phase attributes
  const val GlobalKeysChanged = "global_keys_changed"
  const val ComponentCount = "component_count"
  const val Components = "components"

  // Layout phase attributes
  const val Reused = "reused"
  const val BoundsChanged = "bounds_changed"
  const val CommittedVersion = "committed_version"

  // Mount phase attributes
  const val SummaryByComponent = "summary_by_component"

  // Collection attributes
  const val ChangeType = "change_type"
  const val ChangeCount = "change_count"
  const val TotalItemCount = "total_item_count"
  const val IsSync = "is_sync"
  const val IsAsyncInsertMode = "is_async_insert_mode"
  const val HasPreparationWarming = "has_preparation_warming"
  const val Orientation = "orientation"
  const val IsLegacyInfra = "is_legacy_infra"
  const val ChangesetDurationNs = "changeset_duration_ns"
  const val Trigger = "trigger"
  const val FirstVisiblePosition = "first_visible_position"
  const val LastVisiblePosition = "last_visible_position"
  const val VisibleCount = "visible_count"
  const val RangeStart = "range_start"
  const val RangeEnd = "range_end"
  const val PreparedCount = "prepared_count"
  const val RangeRatio = "range_ratio"
  const val ItemViewCacheSize = "item_view_cache_size"
  const val RecyclerViewItemPrefetch = "recycler_view_item_prefetch"
  const val RecyclingStrategy = "recycling_strategy"
}

object LithoDebugEvents {

  object TreeFuture {

    @JvmStatic
    fun run(treeId: Int, name: String) {
      dispatch(type = LithoDebugEvent.TreeFutureRun, treeId = treeId) { attrs ->
        attrs[DebugEventAttribute.Name] = name
        attrs[DebugEventAttribute.ThreadPriority] = Process.getThreadPriority(Process.myTid())
      }
    }

    /**
     * Begins a TreeFutureWait trace. Returns a trace identifier that must be passed to [endWait]
     * after the blocking `.get()` completes. The resulting event will carry
     * [DebugEventAttribute.Duration] measuring the actual wait time.
     *
     * Returns `null` if no subscribers are listening (no trace overhead).
     */
    @JvmStatic
    fun beginWait(treeId: Int, name: String, waitingOn: Int): Int? {
      val traceId =
          DebugEventDispatcher.generateTraceIdentifier(LithoDebugEvent.TreeFutureWait)
              ?: return null
      DebugEventDispatcher.beginTrace(
          traceIdentifier = traceId,
          type = LithoDebugEvent.TreeFutureWait,
          renderStateId = treeId.toString(),
      ) { attrs ->
        attrs[DebugEventAttribute.Name] = name
        attrs[DebugEventAttribute.ThreadPriority] = Process.getThreadPriority(Process.myTid())
        attrs[LithoDebugEventAttributes.WaitingOn] = waitingOn
      }
      return traceId
    }

    /** Ends a TreeFutureWait trace started by [beginWait]. */
    @JvmStatic
    fun endWait(traceIdentifier: Int?) {
      if (traceIdentifier != null) {
        DebugEventDispatcher.endTrace(traceIdentifier)
      }
    }

    @JvmStatic
    fun get(treeId: Int, name: String) {
      dispatch(type = LithoDebugEvent.TreeFutureGet, treeId = treeId) { attrs ->
        attrs[DebugEventAttribute.Name] = name
        attrs[DebugEventAttribute.WasInterrupted] = false
        attrs[DebugEventAttribute.ThreadPriority] = Process.getThreadPriority(Process.myTid())
      }
    }

    @JvmStatic
    fun getPartial(treeId: Int, name: String) {
      dispatch(type = LithoDebugEvent.TreeFutureGetPartial, treeId = treeId) { attrs ->
        attrs[DebugEventAttribute.Name] = name
        attrs[DebugEventAttribute.WasInterrupted] = true
        attrs[DebugEventAttribute.ThreadPriority] = Process.getThreadPriority(Process.myTid())
      }
    }

    @JvmStatic
    fun resume(treeId: Int, name: String) {
      dispatch(type = LithoDebugEvent.TreeFutureResume, treeId = treeId) { attrs ->
        attrs[DebugEventAttribute.Name] = name
        attrs[DebugEventAttribute.ThreadPriority] = Process.getThreadPriority(Process.myTid())
      }
    }

    @JvmStatic
    fun interrupt(treeId: Int, name: String) {
      dispatch(type = LithoDebugEvent.TreeFutureInterrupt, treeId = treeId) { attrs ->
        attrs[DebugEventAttribute.Name] = name
        attrs[DebugEventAttribute.ThreadPriority] = Process.getThreadPriority(Process.myTid())
      }
    }
  }

  private fun dispatch(
      type: String,
      treeId: Int,
      putAttrs: (MutableMap<String, Any?>) -> Unit = {},
  ) {
    DebugEventDispatcher.dispatch(
        type = type,
        renderStateId = { treeId.toString() },
    ) { attrs ->
      putAttrs(attrs)
    }
  }
}

/**
 * Formats the per-component mount stats into a string for debug logging. Format:
 * {ComponentName:m=X/u=Y/r=Z/re=W,...}
 */
fun formatMountSummaryByComponent(summary: Map<String, MountStats.ComponentMountStats>): String {
  if (summary.isEmpty()) return "{}"
  return summary.entries.joinToString(",", "{", "}") { (name, stats) ->
    "$name:m=${stats.mounted}/u=${stats.updated}/r=${stats.reused}/re=${stats.remounted}"
  }
}
