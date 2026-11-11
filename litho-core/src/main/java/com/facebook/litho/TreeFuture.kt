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

import android.annotation.SuppressLint
import android.os.Process
import androidx.annotation.VisibleForTesting
import com.facebook.litho.ComponentsSystrace.beginSectionWithArgs
import com.facebook.litho.ComponentsSystrace.endSection
import com.facebook.litho.LayoutState.Companion.isFromSyncLayout
import com.facebook.litho.ThreadUtils.isMainThread
import com.facebook.litho.ThreadUtils.tryRaiseThreadPriority
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.litho.debug.LithoDebugEvents.TreeFuture.beginWait
import com.facebook.litho.debug.LithoDebugEvents.TreeFuture.endWait
import com.facebook.litho.debug.LithoDebugEvents.TreeFuture.get
import com.facebook.litho.debug.LithoDebugEvents.TreeFuture.getPartial
import com.facebook.litho.debug.LithoDebugEvents.TreeFuture.interrupt
import com.facebook.litho.debug.LithoDebugEvents.TreeFuture.resume
import com.facebook.litho.debug.LithoDebugEvents.TreeFuture.run
import com.facebook.rendercore.thread.utils.instrumentation.FutureInstrumenter.instrument
import com.meta.systrace.Systracer
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RunnableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Base class that wraps a [FutureTask] to allow calculating the same result across threads. */
abstract class TreeFuture<T : PotentiallyPartialResult>(
    protected open val treeId: Int,
    protected val isInterruptionEnabled: Boolean,
) {
  protected val runningThreadId: AtomicInteger = AtomicInteger(-1)

  /** The [useOnDiscard] callbacks of the renders of this calculation. */
  val discardCallbacks: DiscardCallbacks = DiscardCallbacks()

  private val interruptState: AtomicInteger =
      if (isInterruptionEnabled) {
        AtomicInteger(INTERRUPTIBLE)
      } else {
        AtomicInteger(NON_INTERRUPTIBLE)
      }

  private val refCount = AtomicInteger(0)

  @Volatile private var interruptToken: Any? = null

  @Volatile private var continuationToken: Any? = null

  /** Returns true if this future has been released. */
  @Volatile
  var isReleased: Boolean = false
    private set

  protected val futureTask: RunnableFuture<TreeFutureResult<T>>

  init {
    futureTask =
        instrument<TreeFutureResult<T>>(
            FutureTask(
                object : Callable<TreeFutureResult<T>> {
                  override fun call(): TreeFutureResult<T> {
                    synchronized(this@TreeFuture) {
                      if (isReleased) {
                        return TreeFutureResult.interruptWithMessage(state = FutureState.RELEASED)
                      }
                    }
                    run(treeId, getDescription())
                    val result = calculate()
                    synchronized(this@TreeFuture) {
                      if (isReleased) {
                        return TreeFutureResult.interruptWithMessage(state = FutureState.RELEASED)
                      }
                      return TreeFutureResult.finishWithResult(result)
                    }
                  }
                },
            ),
            "TreeFuture_calculateResult",
        )
  }

  /** Returns a String that gives a textual representation of the type of future it is. */
  abstract fun getDescription(): String

  /** Returns an integer that identifies uniquely the version of this [TreeFuture]. */
  abstract fun getVersion(): Int

  /** Calculates a new result for this TreeFuture. */
  protected abstract fun calculate(): T

  /** Resumes an interrupted calculation based on a partial result */
  protected abstract fun resumeCalculation(partialResult: T?): T

  /** Returns true if the provided TreeFuture is equivalent to this one. */
  abstract fun isEquivalentTo(that: TreeFuture<*>?): Boolean

  /** Releases this TreeFuture */
  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  @Synchronized
  fun release() {
    if (isReleased) {
      return
    }
    interruptToken = null
    continuationToken = null
    isReleased = true
  }

  /**
   * `true` if an interrupt has been requested on this future, indicating the calculation must be
   * resumed on the main-thread.
   */
  val isInterruptRequested: Boolean
    get() = interruptState.get() == INTERRUPTED

  /** `true` if this future is interruptible. */
  val isInterruptible: Boolean
    get() = interruptState.get() == INTERRUPTIBLE

  fun unregisterForResponse() {
    val newRefCount = refCount.decrementAndGet()
    check(newRefCount >= 0) { "TreeFuture ref count is below 0" }
  }

  /**
   * We want to prevent a sync layout in the background from waiting on an interrupted layout (which
   * will return a null result). To handle this, we make sure that a sync bg layout can only wait on
   * a NON_INTERRUPTIBLE future, and that a NON_INTERRUPTIBLE future can't be interrupted.
   *
   * The usage of AtomicInteger for interrupt state is just to make it lockless.
   */
  fun tryRegisterForResponse(waitingFromSyncLayout: Boolean): Boolean {
    if (waitingFromSyncLayout && isInterruptionEnabled && !isMainThread) {
      val state = interruptState.get()
      if (state == INTERRUPTED) {
        return false
      }
      if (state == INTERRUPTIBLE) {
        if (
            !interruptState.compareAndSet(INTERRUPTIBLE, NON_INTERRUPTIBLE) &&
                interruptState.get() != NON_INTERRUPTIBLE
        ) {
          return false
        }
      }
    }
    maybeInterruptEarly()

    // If we haven't returned false by now, we are now marked NON_INTERRUPTIBLE so we're good to
    // wait on this future
    refCount.incrementAndGet()
    return true
  }

  /** We only want to interrupt an INTERRUPTIBLE layout. */
  private fun tryMoveToInterruptedState(): Boolean {
    val state = interruptState.get()
    return when (state) {
      NON_INTERRUPTIBLE -> false
      INTERRUPTIBLE -> {
        !(!interruptState.compareAndSet(INTERRUPTIBLE, INTERRUPTED) &&
            interruptState.get() != INTERRUPTED)
        // If we haven't returned false by now, we are now marked INTERRUPTED so we're good to
        // interrupt.
      }

      else -> true
    }
  }

  val waitingCount: Int
    get() = refCount.get()

  /**
   * Called before the future's Get is invoked. Will start a trace. Override to implement additional
   * logging before Get, but always call super.
   */
  protected fun onGetStart(isTracing: Boolean) {
    if (isTracing) {
      startTrace("get")
    }
  }

  /**
   * Called after Get is completed. Will end a trace. Override to implement additional logging after
   * Get, but always call super.
   */
  protected fun onGetEnd(isTracing: Boolean) {
    if (isTracing) {
      endSection()
    }
  }

  /**
   * Called before Wait starts. Will start a trace. Override to implement additional logging before
   * Wait, but always call super.
   */
  protected fun onWaitStart(isTracing: Boolean) {
    if (isTracing) {
      startTrace("wait")
    }
  }

  /** Called after Wait ends. Ends both the debug event trace and the systrace section. */
  protected fun onWaitEnd(isTracing: Boolean, errorOccurred: Boolean, waitTraceId: Int?) {
    endWait(waitTraceId)
    if (isTracing) {
      endSection()
    }
  }

  /** Starts a sys-trace. The systrace's section will be named after the class name + a suffix. */
  private fun startTrace(suffix: String) {
    addSystraceArgs(beginSectionWithArgs("<cls>${javaClass.name}</cls>.$suffix"))
        .arg("runningThreadId", runningThreadId.get())
        .flush()
  }

  /**
   * Override to add additional args to a systrace. The args will already include the running thread
   * ID.
   */
  protected fun addSystraceArgs(argsBuilder: Systracer.ArgsBuilder): Systracer.ArgsBuilder {
    return argsBuilder
  }

  /**
   * When called, this method will apply the interrupt logic when calling tryRegisterForResponse,
   * rather than during runAndGet. This ensures that a tight race between reusing a future and
   * interrupting it can't happen, making the interruption and reuse logic a single operation.
   *
   * In doing so, we can ensure that async tasks that are interrupted by equivalent sync tasks do
   * not proceed and return null as intended.
   */
  private fun maybeInterruptEarly() {
    val runningThreadId = runningThreadId.get()
    val isRunningOnDifferentThread =
        !futureTask.isDone && runningThreadId != -1 && runningThreadId != Process.myTid()
    if (isInterruptionEnabled && isRunningOnDifferentThread && isMainThread) {
      tryMoveToInterruptedState()
    }
  }

  /**
   * Synchronously executes the future task, ensuring the result is calculated, and resumed on the
   * main thread if it is interrupted while running on a background thread.
   *
   * @param source The calculate-layout source, indicating whether or not it's running from a sync
   *   layout.
   * @return The expected result of calculation, or null if this future was released.
   */
  @SuppressLint("BadMethodUse-java.util.concurrent.Future.get")
  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  fun runAndGet(@RenderSource source: Int, type: FutureExecutionType? = null): TreeFutureResult<T> {
    val myTid = Process.myTid()
    if (runningThreadId.compareAndSet(-1, myTid)) {
      futureTask.run()
    }
    val runningThreadId = runningThreadId.get()
    val notRunningOnMyThread = runningThreadId != myTid
    val originalThreadPriority: Int
    val didRaiseThreadPriority: Boolean
    val raisedThreadPriority: Int
    val shouldWaitForResult = !futureTask.isDone && notRunningOnMyThread
    if (shouldWaitForResult && !isMainThread && !isFromSyncLayout(source)) {
      return TreeFutureResult.interruptWithMessage(
          type = type,
          state = FutureState.WAITING,
          description = FUTURE_RESULT_NULL_REASON_SYNC_RESULT_NON_MAIN_THREAD,
      )
    }
    if (isMainThread && shouldWaitForResult) {
      // This means the UI thread is about to be blocked by the bg thread. Instead of waiting,
      // the bg task is interrupted.
      if (isInterruptionEnabled) {
        if (tryMoveToInterruptedState()) {
          interrupt(treeId, getDescription())
          interruptToken = WorkContinuationInstrumenter.onAskForWorkToContinue("interruptCalculate")
        }
      }
      val desiredPriority = min(Process.getThreadPriority(myTid), Process.THREAD_PRIORITY_DISPLAY)
      val threadPriorityPair = tryRaiseThreadPriority(runningThreadId, desiredPriority)
      originalThreadPriority = threadPriorityPair.first
      raisedThreadPriority = threadPriorityPair.second
      didRaiseThreadPriority = originalThreadPriority != raisedThreadPriority
    } else {
      originalThreadPriority = Process.THREAD_PRIORITY_DEFAULT
      raisedThreadPriority = Process.THREAD_PRIORITY_DEFAULT
      didRaiseThreadPriority = false
    }
    var treeFutureResult: TreeFutureResult<T>
    val shouldTrace = notRunningOnMyThread && ComponentsSystrace.isTracing
    val waitTraceId =
        if (notRunningOnMyThread) {
          beginWait(treeId, getDescription(), runningThreadId)
        } else {
          null
        }
    try {

      // TODO (T133699532): Do we really need 2 lifecycle events here? If not, we can get rid
      // of one of these (and equivalent onEnd).
      onGetStart(shouldTrace)
      onWaitStart(shouldTrace)

      // Calling mFutureTask.get() - One of two things could happen here:
      // 1. This is the 1st time this future is triggered, in which case mFutureTask.get() will
      //    calculate the result, which may be interrupted and return a partial result.
      // 2. The future task has already been triggered, and its result has been cached by the
      //    future itself. If the future is being triggered again, then the cached result is
      //    returned immediately, and it is assumed that this result is partial. The partial result
      //    will then be resumed later in this method.
      treeFutureResult = futureTask.get()
      val isPartialResult =
          treeFutureResult.result != null && treeFutureResult.result.isPartialResult == true
      if (runningThreadId == myTid) {
        if (isPartialResult) {
          getPartial(treeId, getDescription())
        } else {
          get(treeId, getDescription())
        }
      }
      onWaitEnd(shouldTrace, false, waitTraceId)
      if (didRaiseThreadPriority) {
        // Reset the running thread's priority after we're unblocked.
        try {
          val currentThreadPriority = Process.getThreadPriority(runningThreadId)
          Process.setThreadPriority(runningThreadId, originalThreadPriority)

          // Log the scenario where the running thread's priority was raised, but between running
          // the thread and resetting the priority, the running thread's priority was changed
          // again.
          if (currentThreadPriority != raisedThreadPriority) {
            DebugInfoReporter.report(category = "TreeFuturePriorityInversion") {
              this["event"] = "ResettingAfterExternalModification"
              this["originalThreadPriority"] = "$originalThreadPriority"
              this["raisedThreadPriority"] = "$raisedThreadPriority"
              this["currentThreadPriority"] = "$currentThreadPriority"
            }
          }
        } catch (ignored: IllegalArgumentException) {
          DebugInfoReporter.report(category = "TreeFuturePriorityInversion") {
            this["event"] = "IllegalArgumentExceptionWhenResetting"
          }
        } catch (ignored: SecurityException) {
          DebugInfoReporter.report(category = "TreeFuturePriorityInversion") {
            this["event"] = "SecurityExceptionWhenResetting"
          }
        }
      }
      if (interruptState.get() == INTERRUPTED && isPartialResult) {
        if (isMainThread) {
          // This means that the bg task was interrupted and it returned a partially resolved
          // InternalNode. We need to finish computing this LayoutState.
          val token =
              WorkContinuationInstrumenter.onBeginWorkContinuation(
                  "continuePartial",
                  continuationToken,
              )
          continuationToken = null
          try {
            // Resuming here. We are on the main-thread.
            resume(treeId, getDescription())
            treeFutureResult =
                TreeFutureResult.finishWithResult(resumeCalculation(treeFutureResult.result))
            get(treeId, getDescription())
          } catch (th: Throwable) {
            WorkContinuationInstrumenter.markFailure(token, th)
            throw th
          } finally {
            WorkContinuationInstrumenter.onEndWorkContinuation(token)
          }
        } else {
          // This means that the bg task was interrupted and the UI thread will pick up the rest
          // of the work. No need to return a LayoutState.
          treeFutureResult =
              TreeFutureResult.interruptWithMessage(
                  type = type,
                  state = FutureState.INTERRUPTED,
                  description = FUTURE_RESULT_NULL_REASON_RESUME_NON_MAIN_THREAD,
              )
          continuationToken =
              WorkContinuationInstrumenter.onOfferWorkForContinuation(
                  "offerPartial",
                  interruptToken,
              )
          interruptToken = null
        }
      }
    } catch (e: ExecutionException) {
      onWaitEnd(shouldTrace, true, waitTraceId)
      val cause = e.cause
      if (cause is RuntimeException) {
        throw cause
      } else {
        throw RuntimeException(e.message, e)
      }
    } catch (e: InterruptedException) {
      onWaitEnd(shouldTrace, true, waitTraceId)
      val cause = e.cause
      if (cause is RuntimeException) {
        throw cause
      } else {
        throw RuntimeException(e.message, e)
      }
    } catch (e: CancellationException) {
      onWaitEnd(shouldTrace, true, waitTraceId)
      val cause = e.cause
      if (cause is RuntimeException) {
        throw cause
      } else {
        throw RuntimeException(e.message, e)
      }
    } finally {
      onGetEnd(shouldTrace)
    }
    synchronized(this@TreeFuture) {
      if (isReleased) {
        return TreeFutureResult.interruptWithMessage(state = FutureState.RELEASED)
      }
      return treeFutureResult
    }
  }

  /**
   * Holder class for tree-future results. When the contained result is null, the string message
   * will be populated with the result for it being null.
   */
  class TreeFutureResult<T : PotentiallyPartialResult>
  private constructor(
      @JvmField val type: FutureExecutionType? = null,
      @JvmField val state: FutureState,
      @JvmField val result: T? = null,
      @JvmField val description: String? = null,
  ) {
    companion object {
      fun <T : PotentiallyPartialResult> finishWithResult(
          result: T,
          type: FutureExecutionType? = null,
      ): TreeFutureResult<T> {
        return TreeFutureResult(result = result, type = type, state = FutureState.SUCCESS)
      }

      fun <T : PotentiallyPartialResult> interruptWithMessage(
          type: FutureExecutionType? = null,
          state: FutureState,
          description: String? = null,
      ): TreeFutureResult<T> {
        return TreeFutureResult(type = type, state = state, description = description)
      }
    }
  }

  interface FutureExecutionListener {

    /**
     * Called just before or after a future is triggered.
     *
     * @param futureExecutionType How the future is going to be executed - run a new future, reuse a
     *   running one, or cancelled entirely.
     */
    fun onPreExecution(version: Int, futureExecutionType: FutureExecutionType, attribution: String)

    fun onPostExecution(version: Int, released: Boolean, attribution: String)
  }

  enum class FutureExecutionType {

    /** A new future is about to be triggered */
    NEW_FUTURE,

    /** An already running future is about to be reused */
    REUSE_FUTURE,
  }

  enum class FutureState {
    SUCCESS,
    WAITING,
    INTERRUPTED,
    RELEASED,
  }

  companion object {
    const val FUTURE_RESULT_NULL_REASON_RELEASED: String = "TreeFuture released"
    const val FUTURE_RESULT_NULL_REASON_SYNC_RESULT_NON_MAIN_THREAD: String =
        "Waiting for sync result from non-main-thread"
    const val FUTURE_RESULT_NULL_REASON_RESUME_NON_MAIN_THREAD: String =
        "Resuming partial result skipped due to not being on main-thread"
    private const val INTERRUPTIBLE: Int = 0
    private const val INTERRUPTED: Int = 1
    private const val NON_INTERRUPTIBLE: Int = 2

    /**
     * Wraps compute (via [trackAndRunTreeFuture]) and commit into a single operation.
     *
     * @param treeFuture The future to execute
     * @param futureList The list of futures for dedup/reuse
     * @param source The calculation source
     * @param mutex Synchronization lock for the future list
     * @param futureExecutionListener Optional listener
     * @param scope The [InitialStateScope] to close after compute and commit
     * @param onResult Called with the non-null result to perform commit logic
     * @return The result holder from running the future
     */
    @JvmStatic
    fun <T : PotentiallyPartialResult, F : TreeFuture<T>> trackRunAndCommit(
        treeFuture: F,
        futureList: MutableList<F>,
        @RenderSource source: Int,
        mutex: Any,
        futureExecutionListener: FutureExecutionListener?,
        scope: InitialStateScope,
        onResult: (T) -> Unit,
    ): TreeFutureResult<T> {
      return try {
        val holder =
            trackAndRunTreeFuture(treeFuture, futureList, source, mutex, futureExecutionListener)
        if (holder.result != null) {
          onResult(holder.result)
        }
        holder
      } finally {
        scope.close()
      }
    }

    /**
     * Given a provided tree-future, this method will track it via a given list, and run it.
     *
     * If an equivalent tree-future is found in the given list of futures, the sequence in this
     * method will attempt to reuse it and increase the wait-count on it if successful.
     *
     * If an async operation is requested and an equivalent future is already running, it will be
     * discarded and return null.
     *
     * If no equivalent running future was found in the provided list, it will be added to the list
     * for the duration of the run, and then, provided it has a 0 wait-count, it will be removed
     * from the provided list.
     *
     * @param treeFuture The future to executed
     * @param futureList The list of futures this future will be added to
     * @param source The source of the calculation
     * @param mutex A mutex to use to synchronize access to the provided future list
     * @param futureExecutionListener optional listener that will be invoked just before the future
     *   is run
     * @param <T> The generic type of the provided future & expected result
     * @return The result holder of running the future. It will contain the result if there were no
     *   issues running the future, or a message explaining why the result is null. </T>
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @JvmStatic
    fun <T : PotentiallyPartialResult, F : TreeFuture<T>> trackAndRunTreeFuture(
        treeFuture: F,
        futureList: MutableList<F>,
        @RenderSource source: Int,
        mutex: Any,
        futureExecutionListener: FutureExecutionListener?,
    ): TreeFutureResult<T> {
      var future = treeFuture
      val isSync = isFromSyncLayout(source)
      var isReusingFuture = false
      synchronized(mutex) {

        // Iterate over the running futures to see if an equivalent one is running
        for (runningFuture: F in futureList) {
          if (
              (!runningFuture.isReleased &&
                  runningFuture.isEquivalentTo(future) &&
                  runningFuture.tryRegisterForResponse(isSync))
          ) {
            // An equivalent running future was found, and can be reused (tryRegisterForResponse
            // returned true). Discard the provided future, and use the running future instead.
            // tryRegisterForResponse returned true, and also increased the wait-count, so no need
            // explicitly call that.
            future = runningFuture
            isReusingFuture = true
            break
          }
        }

        // Not reusing so mark as NON_INTERRUPTIBLE, increase the wait count, add the new future to
        // the list.
        if (!isReusingFuture) {
          if (!future.tryRegisterForResponse(isSync)) {
            throw RuntimeException("Failed to register to tree future")
          }
          futureList.add(future)
        }
      }

      val executionType: FutureExecutionType =
          if (isReusingFuture) {
            FutureExecutionType.REUSE_FUTURE
          } else {
            FutureExecutionType.NEW_FUTURE
          }
      futureExecutionListener?.onPreExecution(
          future.getVersion(),
          executionType,
          future.getDescription(),
      )

      // Run and get the result
      val result: TreeFutureResult<T> = future.runAndGet(source, executionType)
      synchronized(mutex) {
        futureExecutionListener?.onPostExecution(
            future.getVersion(),
            future.isReleased,
            future.getDescription(),
        )

        // Unregister for response, decreasing the wait count
        future.unregisterForResponse()

        // If the wait count is 0, release the future and remove it from the list
        if (future.waitingCount == 0) {
          future.release()
          futureList.remove(future)
        }
      }
      return result
    }
  }
}
