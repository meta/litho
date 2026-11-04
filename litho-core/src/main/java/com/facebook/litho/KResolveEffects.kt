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

import androidx.annotation.AnyThread
import androidx.annotation.GuardedBy
import com.facebook.litho.annotations.Hook
import com.facebook.rendercore.utils.areObjectsEquivalent

/**
 * Registers an effect that runs when the result this render belongs to is committed, before any
 * layout of it is shown. [useEffect] instead runs only once a layout of the result is shown on the
 * main thread, and never for a result that is committed but not shown, such as the tree of a list
 * item that is prepared ahead of time and released before it scrolls into view.
 *
 * A render during resolve belongs to the [ResolveResult], committed when the tree accepts it as its
 * newest resolve. A render during layout, such as under a size-dependent parent, belongs to the
 * [LayoutState], committed when the tree accepts it as its newest layout. Effects run on a thread
 * that commits, one committed result at a time and in commit order.
 *
 * [onCommit] can return a [CleanupFunc]. It is invoked when a later committed result no longer has
 * this component or has it with different [deps], and when the tree is released.
 *
 * Use it together with [useOnDiscard] to own something a render creates: [useOnDiscard] cleans up
 * after renders whose output is never committed, and the cleanup returned here after those whose
 * output was. A render gets exactly one of the two unless it is a transient run ([isTransientRun]).
 */
@Hook
fun ComponentScope.useResolveEffect(vararg deps: Any?, onCommit: () -> CleanupFunc?) {
  val effects = resolveEffects ?: ArrayList<Attachable>(2).also { resolveEffects = it }
  effects.add(
      ResolveEffectAttachable(
          HookGlobalKey(context.globalKey, requireCurrentHookKey(), RenderScope.Resolve),
          deps,
          onCommit,
      ),
  )
}

/**
 * Registers a callback that runs as soon as Litho knows that this render's output will never be
 * committed: a newer result was committed first, the calculation was released with its tree, the
 * render threw or its output was dropped (for example under an error boundary), or it was inside a
 * measurement ([Component.measure] with `shouldCacheResult = false`), which ends when the
 * measurement returns. It runs at most once, on whichever thread learns of it, and never for a
 * render whose output is committed. It never runs in a transient run ([isTransientRun]).
 */
@Hook
fun ComponentScope.useOnDiscard(onDiscard: () -> Unit) {
  val callbacks = resolveContext.discardCallbacks ?: return
  val key = discardKey ?: Any().also { discardKey = it }
  callbacks.add(key, onDiscard)
}

/**
 * Whether this render is a transient run, whose outcome Litho doesn't report: it may get neither
 * its [useResolveEffect] commit nor its [useOnDiscard] callback, so it must not take anything that
 * only those callbacks give back.
 *
 * True for a render in a measurement whose result is kept for the tree to reuse
 * ([Component.measure] with `shouldCacheResult = true`): if the tree never uses that result,
 * nothing commits or discards the render. Also true in trees whose results no [ComponentTree] or
 * [LithoRenderer] commits, such as the nested tree of a primitive.
 */
val ComponentScope.isTransientRun: Boolean
  get() = resolveContext.discardCallbacks == null

/** One [useResolveEffect] of one render. Attached and detached by its [CommittedEffects]. */
internal class ResolveEffectAttachable(
    override val uniqueId: Any,
    private val deps: Array<out Any?>,
    private val onCommit: () -> CleanupFunc?,
) : Attachable {

  // Accessed by one thread at a time: whichever drains the CommittedEffects.
  private var cleanup: CleanupFunc? = null

  override fun attach() {
    cleanup = onCommit()
  }

  override fun detach() {
    val cleanup = cleanup
    this.cleanup = null
    cleanup?.onCleanup()
  }

  override fun shouldUpdate(nextAttachable: Attachable): Boolean =
      !areObjectsEquivalent(deps, (nextAttachable as ResolveEffectAttachable).deps)

  override fun useLegacyUpdateBehavior(): Boolean = false
}

/**
 * The [useOnDiscard] callbacks of the renders of one calculation, keyed by render. Its owner
 * reports the calculation's outcome once: [onDiscarded] runs every callback; [onCommitted] runs the
 * callbacks of renders whose output isn't in the committed result and forgets the rest. A callback
 * added after the outcome was reported runs at once if it would have run.
 *
 * Callbacks can have children, for part of a calculation that can be discarded on its own, such as
 * a nested tree that is resolved again under new size constraints. Reporting an outcome reports the
 * same one to the children that don't have one yet.
 */
class DiscardCallbacks {

  // All guarded by this.
  private var keys: ArrayList<Any>? = null
  private var callbacks: ArrayList<() -> Unit>? = null
  private var children: ArrayList<DiscardCallbacks>? = null
  private var outcome: Outcome? = null

  @AnyThread
  internal fun add(key: Any, callback: () -> Unit) {
    val outcome =
        synchronized(this) {
          val outcome = outcome
          if (outcome == null) {
            (keys ?: ArrayList<Any>(2).also { keys = it }).add(key)
            (callbacks ?: ArrayList<() -> Unit>(2).also { callbacks = it }).add(callback)
            return
          }
          outcome
        }
    if (outcome.shouldRun(key)) {
      callback()
    }
  }

  /**
   * Returns new callbacks that get the outcome of these, unless they get one of their own first.
   */
  @AnyThread
  internal fun child(): DiscardCallbacks {
    val child = DiscardCallbacks()
    val outcome =
        synchronized(this) {
          val outcome = outcome
          if (outcome == null) {
            (children ?: ArrayList<DiscardCallbacks>(2).also { children = it }).add(child)
            return child
          }
          outcome
        }
    child.report(outcome)
    return child
  }

  /**
   * The calculation was discarded: runs every callback, even if one throws, then rethrows the first
   * failure.
   */
  @AnyThread fun onDiscarded() = report(Outcome.Discarded)

  /**
   * The calculation was committed: runs the callbacks of renders whose key isn't in
   * [committedKeys], which are the renders whose output made it into the committed result, and
   * forgets the rest.
   */
  @AnyThread fun onCommitted(committedKeys: Set<Any>) = report(Outcome.Committed(committedKeys))

  private fun report(outcome: Outcome) {
    val keys: List<Any>?
    val callbacks: List<() -> Unit>?
    val children: List<DiscardCallbacks>?
    synchronized(this) {
      if (this.outcome != null) {
        return
      }
      this.outcome = outcome
      keys = this.keys
      callbacks = this.callbacks
      children = this.children
      this.keys = null
      this.callbacks = null
      this.children = null
    }
    val failures = Failures()
    if (keys != null && callbacks != null) {
      for (i in callbacks.indices) {
        if (outcome.shouldRun(keys[i])) {
          failures.attempt(callbacks[i])
        }
      }
    }
    children?.forEach { child -> failures.attempt { child.report(outcome) } }
    failures.rethrow()
  }

  private sealed class Outcome {
    abstract fun shouldRun(key: Any): Boolean

    object Discarded : Outcome() {
      override fun shouldRun(key: Any): Boolean = true
    }

    class Committed(private val committedKeys: Set<Any>) : Outcome() {
      override fun shouldRun(key: Any): Boolean = key !in committedKeys
    }
  }
}

/** How the tree took a result it was given. */
internal enum class CommitOutcome {
  /** It committed it. */
  COMMITTED,
  /** Another caller of the same calculation committed it, and reports it. */
  ALREADY_COMMITTED,
  /** It kept a newer result. */
  REJECTED,
}

/**
 * The [useResolveEffect]s of a tree's committed results of one kind, resolve or layout. Each
 * committed result's effects are diffed with the previous one's by hook and deps: effects that are
 * gone or whose deps changed are detached, then new ones are attached.
 *
 * The diff is computed under a lock, so a tree that reports while holding its commit lock gets its
 * results diffed in commit order. The callbacks (detach, attach and discard) run outside any lock,
 * one result at a time and in report order, by [drain]: a thread that drains runs the callbacks
 * other threads queued meanwhile, including those a callback queues by committing a result itself.
 */
internal class CommittedEffects {

  private val lock = Any()
  @GuardedBy("lock") private var attached: Map<Any, Attachable> = emptyMap()
  @GuardedBy("lock") private var isReleased = false
  @GuardedBy("lock") private val pending = ArrayDeque<List<() -> Unit>>()
  @GuardedBy("lock") private var isDraining = false

  /**
   * Queues the callbacks for the result of version [version] of a calculation the tree was given.
   * If it [committed][outcome] it, its [effects] are attached, the discard callbacks of its renders
   * whose output isn't in it run ([committedKeys]), and the calculations among [futures] older than
   * it, which can never be committed now, are discarded. If it rejected it, its [discards] run, as
   * they do for every result after [detachAll] with `release`. Call [drain] afterwards, outside the
   * tree's locks.
   */
  @AnyThread
  fun report(
      effects: List<Attachable>?,
      discards: DiscardCallbacks?,
      committedKeys: Set<Any>?,
      outcome: CommitOutcome,
      version: Int,
      futures: List<TreeFuture<*>>,
      futuresLock: Any,
  ) {
    val olderCalculations =
        if (outcome == CommitOutcome.COMMITTED) {
          synchronized(futuresLock) {
            futures.mapNotNull { future ->
              if (future.getVersion() < version) future.discardCallbacks else null
            }
          }
        } else {
          emptyList()
        }
    synchronized(lock) {
      if (outcome == CommitOutcome.ALREADY_COMMITTED) {
        return
      }
      if (outcome == CommitOutcome.REJECTED || isReleased) {
        if (discards != null) {
          pending.add(listOf(discards::onDiscarded))
        }
        return
      }
      val next = LinkedHashMap<Any, Attachable>()
      val toAttach = ArrayList<Attachable>()
      effects?.forEach { effect ->
        if (next.containsKey(effect.uniqueId)) {
          return@forEach
        }
        val current = attached[effect.uniqueId]
        if (current != null && (current === effect || !current.shouldUpdate(effect))) {
          next[effect.uniqueId] = current
        } else {
          next[effect.uniqueId] = effect
          toAttach.add(effect)
        }
      }
      val toDetach = attached.values.filter { next[it.uniqueId] !== it }
      attached = next

      val callbacks =
          ArrayList<() -> Unit>(toDetach.size + toAttach.size + olderCalculations.size + 1)
      toDetach.forEach { effect -> callbacks.add(effect::detach) }
      toAttach.forEach { effect -> callbacks.add(effect::attach) }
      if (discards != null) {
        val keys = committedKeys ?: emptySet()
        callbacks.add { discards.onCommitted(keys) }
      }
      olderCalculations.forEach { older -> callbacks.add(older::onDiscarded) }
      pending.add(callbacks)
    }
  }

  /**
   * Queues detaching every effect, as when the tree is reset, or released ([release]), and
   * discarding [calculations] still running. Call [drain] afterwards.
   */
  @AnyThread
  fun detachAll(release: Boolean, calculations: List<DiscardCallbacks> = emptyList()) {
    synchronized(lock) {
      if (isReleased) {
        return
      }
      isReleased = release
      val toDetach = attached.values.toList()
      attached = emptyMap()
      val callbacks = ArrayList<() -> Unit>(calculations.size + toDetach.size)
      calculations.forEach { calculation -> callbacks.add(calculation::onDiscarded) }
      toDetach.forEach { effect -> callbacks.add(effect::detach) }
      pending.add(callbacks)
    }
  }

  /**
   * Runs the queued callbacks, unless another thread, or a callback on this thread, is already
   * running them. Runs every callback even if one throws, then rethrows the first failure.
   */
  @AnyThread
  fun drain() {
    synchronized(lock) {
      if (isDraining) {
        return
      }
      isDraining = true
    }
    val failures = Failures()
    while (true) {
      val callbacks =
          synchronized(lock) {
            pending.removeFirstOrNull()
                ?: run {
                  isDraining = false
                  null
                }
          } ?: break
      callbacks.forEach { failures.attempt(it) }
    }
    failures.rethrow()
  }
}

/** Collects the failures of callbacks that must all run, to rethrow the first one. */
private class Failures {
  private var first: Throwable? = null

  inline fun attempt(block: () -> Unit) {
    try {
      block()
    } catch (t: Throwable) {
      val first = first
      if (first == null) {
        this.first = t
      } else if (first !== t) {
        first.addSuppressed(t)
      }
    }
  }

  fun rethrow() {
    first?.let { throw it }
  }
}
