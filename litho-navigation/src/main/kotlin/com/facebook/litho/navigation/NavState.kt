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

package com.facebook.litho.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.facebook.litho.Component

/** A back-stack entry: its FoA entry id, which its saveable values use, and its lifecycle. */
internal class NavEntry(
    val id: String,
    val lifecycleOwner: EntryLifecycleOwner = EntryLifecycleOwner(),
)

/** The root component of [entry]'s screen. */
internal class Screen(val entry: NavEntry, val root: Component) {
  val id: String
    get() = entry.id
}

/** The lifecycle of one back-stack entry, moved by [EntryTracker] on the main thread. */
internal class EntryLifecycleOwner : LifecycleOwner {
  private val registry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle
    get() = registry

  fun moveTo(state: Lifecycle.State) {
    val current = registry.currentState
    // DESTROYED is final, and a lifecycle that was never created cannot be destroyed.
    if (current == Lifecycle.State.DESTROYED) return
    if (current == Lifecycle.State.INITIALIZED && state == Lifecycle.State.DESTROYED) return
    registry.currentState = state
  }
}

/**
 * What [LithoNavHost] renders: each back-stack entry, bottom first, and the top screen. Once the
 * back stack is emptied, [top] stays the last screen the host showed, so the host never shows an
 * empty back stack.
 *
 * Entries are keyed by their FoA entry id. FoA never reuses an id, so after a pop and a push in the
 * same frame the new entry cannot take over the popped entry's state, and it restores the ids with
 * the back stack after process death, so each entry's saved values line up again.
 */
internal class NavState private constructor(val entries: List<NavEntry>, val top: Screen?) {

  val ids: List<String>
    get() = entries.map(NavEntry::id)

  /**
   * The state for the back stack's current [entryIds]: entries still on the stack keep their
   * lifecycle, new ones get a new one, and [createTopRoot] builds the top screen if the top entry
   * changed. An empty back stack keeps the current top screen.
   */
  fun next(entryIds: List<String>, createTopRoot: () -> Component): NavState {
    val known = entries.associateBy(NavEntry::id)
    val nextEntries = entryIds.map { id -> known[id] ?: NavEntry(id) }
    val nextTop =
        when (nextEntries.lastOrNull()?.id) {
          null,
          top?.id -> top
          else -> Screen(nextEntries.last(), createTopRoot())
        }
    return NavState(nextEntries, nextTop)
  }

  companion object {
    fun create(entryIds: List<String>, topRoot: Component?): NavState {
      val entries = entryIds.map(::NavEntry)
      val top = entries.lastOrNull()?.let { entry -> topRoot?.let { Screen(entry, it) } }
      return NavState(entries, top)
    }
  }
}

/**
 * Main-thread record of the states [LithoNavHost] published: the latest one, which the next
 * back-stack change starts from, and the lifecycles of every entry whose values may still be in the
 * holder.
 *
 * The top entry's lifecycle follows the host's, covered entries stay at most CREATED, and entries
 * that leave the stack are DESTROYED.
 */
internal class EntryTracker(initial: NavState) {

  var latest: NavState = initial
    private set

  private val lifecycles: MutableMap<String, EntryLifecycleOwner> =
      initial.entries.associateTo(mutableMapOf()) { entry -> entry.id to entry.lifecycleOwner }

  private var hostState: Lifecycle.State = Lifecycle.State.INITIALIZED

  private var host: Lifecycle? = null

  private var followingHost = false

  // Stays on the host after detachHost, until the next attachHost, so entries are still destroyed
  // with it.
  private val hostObserver = LifecycleEventObserver { _, event ->
    if (!followingHost && event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
    hostState = event.targetState
    moveLifecycles()
  }

  fun publish(state: NavState) {
    latest = state
    state.entries.forEach { entry ->
      if (entry.id !in lifecycles) lifecycles[entry.id] = entry.lifecycleOwner
    }
    moveLifecycles()
  }

  /**
   * Follows [owner]'s lifecycle. With no owner at all, as under an ancestor that provides none,
   * entries act as if the host were resumed.
   */
  fun attachHost(owner: LifecycleOwner?) {
    host?.removeObserver(hostObserver)
    host = null
    if (owner == null) {
      hostState = Lifecycle.State.RESUMED
      moveLifecycles()
    } else {
      followingHost = true
      host = owner.lifecycle.also { it.addObserver(hostObserver) }
    }
  }

  /**
   * Stops following the host. Entries stay at most CREATED until a host is attached again, and are
   * destroyed when the host is.
   */
  fun detachHost() {
    followingHost = false
    if (hostState.isAtLeast(Lifecycle.State.STARTED)) {
      hostState = Lifecycle.State.CREATED
      moveLifecycles()
    }
  }

  /**
   * Destroys all tracked entry lifecycles and removes the host observer. Call this when the host
   * component unmounts permanently so entries are not held alive until the Activity destroys.
   */
  fun destroyAll() {
    host?.removeObserver(hostObserver)
    host = null
    lifecycles.values.forEach { it.moveTo(Lifecycle.State.DESTROYED) }
    lifecycles.clear()
  }

  /**
   * Destroys the lifecycles of entries that left the stack once [committed] shows the update that
   * removed them, and returns their ids. Their values can be dropped then too: a render still in
   * flight cannot register them again. A screen [committed] still shows after the stack was emptied
   * is dropped once another screen replaces it.
   */
  fun dropRemoved(committed: NavState): List<String> {
    val live = committed.ids.toSet() + latest.ids + listOfNotNull(committed.top?.id)
    val removed = lifecycles.keys.filterNot(live::contains)
    removed.forEach { id -> lifecycles.remove(id)?.moveTo(Lifecycle.State.DESTROYED) }
    return removed
  }

  private fun moveLifecycles() {
    val state = latest
    val topId = state.ids.lastOrNull()
    // A copy: a lifecycle callback can navigate, which publishes and adds entries.
    for ((id, owner) in lifecycles.toList()) {
      val max = if (id == topId) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
      owner.moveTo(minOf(hostState, max))
      // A callback navigated, and the nested publish already moved every entry for the new top.
      if (latest !== state) return
    }
  }
}
