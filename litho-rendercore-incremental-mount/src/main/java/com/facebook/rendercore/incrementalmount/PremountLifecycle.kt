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

package com.facebook.rendercore.incrementalmount

import androidx.collection.MutableLongIntMap
import androidx.collection.MutableLongList
import java.util.concurrent.atomic.AtomicLong

/**
 * Identifies one continuous premount registration lifetime. Ids are never reused within a
 * [PremountLifecycle], so value equality is enough to reject work from an older session.
 */
@JvmInline internal value class PremountSession(val id: Long)

/**
 * Tracks what happened to each item the gap worker premounts, so Incremental Mount knows whether to
 * keep it mounted or release it when it goes off screen.
 *
 * ```
 *   (untracked) ──premount──> PREMOUNTED ──on screen──> SEEN ──off screen──> RELEASED
 *                                                        ▲                     │
 *                                                        └─────on screen───────┘
 * ```
 *
 * Untracked items are not in the map and behave exactly as Incremental Mount does today.
 *
 * Every write takes the [PremountSession] that started the work. A new layout or a teardown starts
 * a new session (or none), so work that finishes late is answered with `STALE` / `false` and
 * changes nothing.
 *
 * Acquire and release are split into `begin` (decide) and `finish` (record), because the physical
 * mount or unmount in between can re-enter Incremental Mount.
 *
 * Main thread only.
 */
internal class PremountLifecycle {

  internal val id: Long = nextLifecycleId.getAndIncrement()

  enum class State {
    /** Mounted ahead of the screen by the gap worker, not seen yet. Kept mounted off screen. */
    PREMOUNTED,
    /** Was on screen during a scroll or animation. Released as soon as it leaves the screen. */
    SEEN,
    /** Seen, then released off screen. The gap worker won't premount it again. */
    RELEASED,
  }

  /** Answer to "the gap worker wants to premount this item". */
  enum class AcquireDecision {
    /** Mount it now, then call [finishPremountAcquire]. */
    ACQUIRE,
    /** Already mounted or already tracked; leave it alone. */
    SKIP,
    /** The session ended; drop the work. */
    STALE,
  }

  /** Answer to "this item left the screen". */
  enum class ReleaseDecision {
    /** Release it. Nothing to record. */
    RELEASE,
    /** Release it, then pass this back to [finishOffscreenRelease] so it can record RELEASED. */
    RELEASE_AND_RECORD,
    /** Keep it mounted: still PREMOUNTED. */
    RETAIN,
    /** The session ended; drop the work. */
    STALE,
  }

  private val stateByOutputId = MutableLongIntMap(8)
  private var session: PremountSession? = null
  private var nextSessionId = 1L

  /**
   * Off-screen items waiting for the gap worker to release them, oldest first. Kept across
   * [renewSession]; see [IncrementalMountExtensionConfigs.maxPendingOffscreenReleases].
   */
  val pendingReleases: MutableLongList = MutableLongList(0)

  /** The tree registered for premount. Clears all state. */
  fun startSession(): PremountSession {
    stateByOutputId.clear()
    pendingReleases.clear()
    return newSession().also { session = it }
  }

  /** A new layout arrived. Same items, same states; work started under the old session is stale. */
  fun renewSession(): PremountSession? {
    if (session == null) {
      return null
    }
    return newSession().also { session = it }
  }

  private fun newSession(): PremountSession = PremountSession(nextSessionId++)

  /** The tree is going away. Clears all state; all later work is stale. */
  fun stopSession() {
    session = null
    stateByOutputId.clear()
    pendingReleases.clear()
  }

  fun currentSession(): PremountSession? = session

  fun isCurrent(candidate: PremountSession): Boolean = session == candidate

  fun stateOf(id: Long): State? =
      when (stateByOutputId.getOrDefault(id, NO_STATE)) {
        STATE_PREMOUNTED -> State.PREMOUNTED
        STATE_SEEN -> State.SEEN
        STATE_RELEASED -> State.RELEASED
        else -> null
      }

  /** The item was removed from the layout; it goes back to untracked. */
  fun removeOutput(session: PremountSession, id: Long): Boolean {
    if (!isCurrent(session)) {
      return false
    }
    stateByOutputId.remove(id)
    return true
  }

  /** On screen during a scroll or animation. Accepts any state, including untracked. */
  fun markSeen(session: PremountSession, id: Long): Boolean {
    if (!isCurrent(session)) {
      return false
    }
    stateByOutputId[id] = STATE_SEEN
    return true
  }

  fun beginPremountAcquire(
      session: PremountSession,
      id: Long,
      alreadyOwned: Boolean,
  ): AcquireDecision {
    if (!isCurrent(session)) {
      return AcquireDecision.STALE
    }
    // A tracked item was already seen (in practice RELEASED); premounting it again would churn.
    if (alreadyOwned || stateByOutputId.containsKey(id)) {
      return AcquireDecision.SKIP
    }
    return AcquireDecision.ACQUIRE
  }

  /**
   * Records the outcome of the mount: [acquired] is whether IM holds the reference afterwards.
   * Returns false when a newer session must reconcile it.
   */
  fun finishPremountAcquire(
      session: PremountSession,
      id: Long,
      acquired: Boolean,
  ): Boolean {
    if (!isCurrent(session)) {
      return false
    }
    if (acquired && !stateByOutputId.containsKey(id)) {
      stateByOutputId[id] = STATE_PREMOUNTED
    }
    return true
  }

  /**
   * An item whose reference is held left the screen. [releasePremounted] releases a PREMOUNTED item
   * too, recorded like a SEEN one, for a tree that is being emptied.
   */
  fun beginOffscreenRelease(
      session: PremountSession,
      id: Long,
      releasePremounted: Boolean = false,
  ): ReleaseDecision {
    if (!isCurrent(session)) {
      return ReleaseDecision.STALE
    }
    return when (stateOf(id)) {
      // Off screen until the user reaches it; teardown and layout changes still release it.
      State.PREMOUNTED ->
          if (releasePremounted) {
            stateByOutputId.remove(id)
            ReleaseDecision.RELEASE_AND_RECORD
          } else {
            ReleaseDecision.RETAIN
          }
      State.SEEN -> {
        // Absence lets a reentrant visible event publish a newer fact before completion.
        stateByOutputId.remove(id)
        ReleaseDecision.RELEASE_AND_RECORD
      }
      State.RELEASED,
      null -> ReleaseDecision.RELEASE
    }
  }

  /**
   * Records the outcome of the unmount: [released] is whether IM let go of the reference. Returns
   * false when a newer session must reconcile it.
   */
  fun finishOffscreenRelease(
      session: PremountSession,
      id: Long,
      decision: ReleaseDecision,
      released: Boolean,
  ): Boolean {
    if (!isCurrent(session)) {
      return false
    }
    if (decision == ReleaseDecision.RELEASE_AND_RECORD && !stateByOutputId.containsKey(id)) {
      stateByOutputId[id] = if (released) STATE_RELEASED else STATE_SEEN
    }
    return true
  }

  /**
   * Whether the current state wants this item mounted: PREMOUNTED and SEEN do, RELEASED and
   * untracked don't. Used to bring IM's reference back in line after a begin/finish went stale.
   */
  fun wantsMounted(id: Long): Boolean =
      session != null &&
          when (stateOf(id)) {
            State.PREMOUNTED,
            State.SEEN -> true
            State.RELEASED,
            null -> false
          }

  /** The gap worker skips RELEASED items so it doesn't remount what the user just left. */
  fun isReleased(id: Long): Boolean = session != null && stateOf(id) == State.RELEASED

  companion object {
    private val nextLifecycleId = AtomicLong(1)
    private const val NO_STATE = -1
    private const val STATE_PREMOUNTED = 0
    private const val STATE_SEEN = 1
    private const val STATE_RELEASED = 2
  }
}
