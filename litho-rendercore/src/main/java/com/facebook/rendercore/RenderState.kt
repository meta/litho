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

package com.facebook.rendercore

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.AnyThread
import com.facebook.infer.annotation.ThreadConfined
import com.facebook.rendercore.StateUpdateReceiver.StateUpdate
import com.facebook.rendercore.extensions.RenderCoreExtension
import com.facebook.rendercore.thread.utils.PriorityInheritingFuture
import com.facebook.rendercore.thread.utils.ThreadUtils
import com.facebook.rendercore.utils.VSyncUtils
import java.util.Objects
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.concurrent.ThreadSafe
import kotlin.math.roundToInt

/** todo: javadocs * */
class RenderState<ResolveState, RenderContext, StateUpdateType : StateUpdate<*>>
@JvmOverloads
constructor(
    private val context: Context,
    delegate: Delegate<Any?>,
    private val renderContext: RenderContext?,
    val extensions: Array<RenderCoreExtension<*, *>>?,
    private val resolveExecutor: Executor = Executor { r -> ThreadUtils.runOnBackgroundThread(r) },
    private val rootLayoutFunction:
        RootLayoutFunction<ResolveState, RenderContext, Node<RenderContext>, Any?>? =
        null,
    private val preMeasureCallback: Runnable? = null,
    private val deferCommitToUIUntilMount: Boolean = false,
    instrumenter: TraceInstrumenter? = null,
    private val deferLayoutCommitToMainThread: Boolean = false,
    private val relayoutLatestTreeOnSpecsChange: Boolean = false,
) : StateUpdateReceiver<StateUpdateType> {

  private val traceManager: RenderTraceManager? = instrumenter?.let { RenderTraceManager(it) }

  /**
   * Represents a function capable of creating a tree. The tree is lazy so that the creation can be
   * done inline with the layout pass.
   *
   * @param <State> Represents the State that this tree would like to commit when the tree itself is
   *   committed
   */
  @ThreadSafe
  fun interface ResolveFunc<State, RenderContext, StateUpdateType : StateUpdate<*>> {
    /**
     * Resolves the tree represented by this ResolveFunc. Results for resolve might be cached. The
     * assumption is that multiple resolve calls on a ResolveFunc would return equivalent trees.
     *
     * @param stateUpdateReceiver
     * @param committedTree
     * @param committedState
     * @param stateUpdatesToApply
     */
    fun resolve(
        stateUpdateReceiver: StateUpdateReceiver<StateUpdateType>,
        committedTree: Node<RenderContext>?,
        committedState: State?,
        stateUpdatesToApply: List<@JvmSuppressWildcards StateUpdateType>,
    ): ResolveResult<Node<RenderContext>, State>
  }

  /**
   * Performs layout of the resolved tree. This function can be called on any thread.
   * Implementations should not hold internal state that can be potentially inconsistent across
   * invocations
   */
  @AnyThread
  fun interface RootLayoutFunction<
      ResolveState,
      RenderContext : Any?,
      Tree : Node<RenderContext>,
      LayoutState,
  > {
    fun invoke(
        context: Context,
        tree: Tree,
        state: ResolveState?,
        renderContext: RenderContext?,
        previousResult: RenderResult<LayoutState, RenderContext>?,
        extensions: Array<RenderCoreExtension<*, *>>?,
        version: Int,
        sizeConstraints: SizeConstraints,
    ): RenderResult<LayoutState, RenderContext>
  }

  interface Delegate<State> {
    fun commit(
        layoutVersion: Int,
        current: RenderTree?,
        next: RenderTree,
        currentState: State?,
        nextState: State?,
    )

    fun commitToUI(
        tree: RenderTree,
        state: State?,
        frameVersion: Int,
        renderTrace: RCRenderTrace?,
    )
  }

  fun interface HostListener {
    fun onMountInputUpdated(newMountInput: RootHostInput?)
  }

  private val uiHandler: RenderStateHandler = RenderStateHandler(Looper.getMainLooper())
  val id: Int = ID_GENERATOR.incrementAndGet()

  @ThreadConfined(ThreadConfined.UI) private var hostListener: HostListener? = null

  @get:ThreadConfined(ThreadConfined.UI)
  val uiRenderTree: RenderTree?
    get() = uiRootHostInput?.mountInput?.renderTree

  @get:ThreadConfined(ThreadConfined.UI)
  var uiRootHostInput: RootHostInput? = null
    private set

  private var latestResolveFunc: ResolveFunc<ResolveState, RenderContext, StateUpdateType>? = null
  private var resolveFuture: ResolveFuture<ResolveState, RenderContext, StateUpdateType>? = null
  private var layoutTask: RootLayoutTask<RenderContext>? = null
  private var resolveVersionCounter = 0
  private var committedResolveVersion = UNSET
  private var committedResolvedTree: Node<RenderContext>? = null
  private var committedResolveState: ResolveState? = null
  private var committedResolvedFrameId = UNSET
  private val pendingStateUpdates: MutableList<StateUpdateType> = ArrayList()
  private var committedRenderResult: RenderResult<Any?, RenderContext>? = null
  private var pendingLayoutTrace: RCRenderTrace? = null
  private var committedTrace: RCRenderTrace? = null
  private var lastResolveRenderId: Int = UNSET

  private val normalVSyncTime = VSyncUtils.getNormalVsyncTime(context)
  private val frameReferenceTimeNanos = System.nanoTime()

  private var layoutVersionCounter = 0
  private var committedLayoutVersion = UNSET
  private var committedLayoutFrameId = UNSET
  private var sizeConstraints: SizeConstraints = SizeConstraints()
  private var hasSizeConstraints = false
  private val resolveToken = Any()
  private val delegate: Delegate<Any?> = delegate
  private val stateUpdateReceiver:
      StateUpdateReceiverDelegate<ResolveState, RenderContext, StateUpdateType> =
      StateUpdateReceiverDelegate(this)

  @ThreadConfined(ThreadConfined.ANY)
  fun setTree(resolveFunc: ResolveFunc<ResolveState, RenderContext, StateUpdateType>?) {
    requestResolve(resolveFunc, doAsync = false)
  }

  @ThreadConfined(ThreadConfined.ANY)
  fun setTreeAsync(
      resolveFunc: ResolveFunc<ResolveState, RenderContext, StateUpdateType>?,
  ) {
    requestResolve(resolveFunc, doAsync = true)
  }

  @ThreadConfined(ThreadConfined.ANY)
  override fun enqueueStateUpdate(stateUpdate: StateUpdateType) {
    stateUpdateReceiver.enqueueStateUpdate(stateUpdate)
  }

  @ThreadConfined(ThreadConfined.ANY)
  override fun enqueueStateUpdateSync(stateUpdate: StateUpdateType) {
    stateUpdateReceiver.enqueueStateUpdateSync(stateUpdate)
  }

  private fun enqueueStateUpdateInternal(stateUpdate: StateUpdateType, async: Boolean) {
    synchronized(this) {
      pendingStateUpdates.add(stateUpdate)
      if (latestResolveFunc == null) {
        return
      }
    }

    uiHandler.removeCallbacksAndMessages(resolveToken)
    uiHandler.postAtTime({ requestResolve(null, async) }, resolveToken, 0)
  }

  private fun getElapsedFrameCount(): Int {
    val vsyncTime = normalVSyncTime
    if (vsyncTime <= 0) return 0
    val elapsedTimeNanos = System.nanoTime() - frameReferenceTimeNanos
    return (elapsedTimeNanos * 1.0 / vsyncTime).roundToInt()
  }

  private fun requestResolve(
      resolveFunc: ResolveFunc<ResolveState, RenderContext, StateUpdateType>?,
      doAsync: Boolean,
  ) {
    val future: ResolveFuture<ResolveState, RenderContext, StateUpdateType>
    val traceToBegin: RCRenderTrace?
    synchronized(this) {

      // Resolve was triggered by State Update, but all pendingStateUpdates are already applied.
      if (resolveFunc == null && pendingStateUpdates.isEmpty()) {
        return
      }

      if (resolveFunc != null) {
        latestResolveFunc = resolveFunc
      }
      // Construct the trace under the lock so it's tied 1:1 with the future / version increment,
      // but defer the manager.begin() call until after we release the lock so the instrumenter
      // callback doesn't run under RenderState's monitor.
      traceToBegin = traceManager?.createResolveTrace()
      future =
          ResolveFuture(
              requireNotNull(latestResolveFunc),
              stateUpdateReceiver,
              committedResolvedTree,
              committedResolveState,
              if (pendingStateUpdates.isEmpty()) emptyList() else ArrayList(pendingStateUpdates),
              resolveVersionCounter++,
              getElapsedFrameCount(),
              traceToBegin,
          )
      resolveFuture = future
    }
    traceToBegin?.let { traceManager?.begin(it) }
    if (doAsync) {
      resolveExecutor.execute { resolveTreeAndMaybeCommit(future) }
    } else {
      resolveTreeAndMaybeCommit(future)
    }
  }

  private fun resolveTreeAndMaybeCommit(
      future: ResolveFuture<ResolveState, RenderContext, StateUpdateType>,
  ) {
    val result = future.runAndGet()
    when (maybeCommitResolveResult(result, future)) {
      ResolveCommitResult.COMMITTED -> layoutAndMaybeCommitInternal(null)
      ResolveCommitResult.ALREADY_COMMITTED -> Unit
      ResolveCommitResult.SUPERSEDED ->
          future.renderTrace?.markCancelled(RCRenderTrace.CANCEL_REASON_NEWER_RESOLVE_COMMITTED)
    }
  }

  private fun maybeCommitResolveResult(
      result: ResolveResult<Node<RenderContext>, ResolveState>,
      future: ResolveFuture<ResolveState, RenderContext, StateUpdateType>,
  ): ResolveCommitResult {
    // We don't want to compute, layout, or reduce trees while holding a lock. However this means
    // that another thread could compute a layout and commit it before we get to this point. To
    // handle this, we make sure that the committed resolve version is only ever increased, meaning
    // we only go "forward in time" and will eventually get to the latest layout.
    var commitResult = ResolveCommitResult.SUPERSEDED
    var supersededPendingLayoutTrace: RCRenderTrace? = null
    synchronized(this) {
      if (future.version > committedResolveVersion) {
        // Capture any prior pending-layout trace before we overwrite it; otherwise an older
        // resolve's trace that committed first but hasn't yet been picked up by
        // layoutAndMaybeCommitInternal would be stranded in the trace manager until destroy().
        supersededPendingLayoutTrace = pendingLayoutTrace
        committedResolveVersion = future.version
        committedResolvedTree = result.resolvedNode
        committedResolvedFrameId = future.frameId
        committedResolveState = result.resolvedState
        pendingLayoutTrace = future.renderTrace
        future.renderTrace?.let { lastResolveRenderId = it.renderId }
        val appliedUpdates = result.appliedStateUpdates
        if (!appliedUpdates.isNullOrEmpty()) {
          pendingStateUpdates.removeAll(appliedUpdates)
        }
        commitResult = ResolveCommitResult.COMMITTED
      } else if (future.version == committedResolveVersion) {
        commitResult = ResolveCommitResult.ALREADY_COMMITTED
      }
      if (resolveFuture == future) {
        resolveFuture = null
      }
    }
    supersededPendingLayoutTrace?.markCancelled(RCRenderTrace.CANCEL_REASON_NEWER_RESOLVE_COMMITTED)
    return commitResult
  }

  private fun layoutAndMaybeCommitInternal(measureOutput: IntArray?) {
    val isMainThread = ThreadUtils.isMainThread
    val layoutTask: RootLayoutTask<RenderContext>
    val previousRenderResult: RenderResult<Any?, RenderContext>?
    val futureFrameId: Int
    // If we open a new MEASURE trace inside the lock, defer the manager.begin() call until
    // after we release the lock so the instrumenter callback doesn't run under our monitor.
    var measureTraceToBegin: RCRenderTrace? = null
    synchronized(this) {
      if (!hasSizeConstraints) {
        return
      }
      val committedTree =
          requireNotNull(committedResolvedTree) {
            "Tried executing the layout step before resolving a tree"
          }
      val layout = this.layoutTask
      if (
          layout == null || layout.tree != committedTree || !hasSameSpecs(layout, sizeConstraints)
      ) {
        val layoutVersion = layoutVersionCounter++

        // The trace for this layout: prefer one from a resolve that hasn't been bound to a task
        // yet; otherwise this is a pure re-layout (e.g. measure with new constraints) and we open
        // a fresh MEASURE trace against the current generation.
        val taskTrace =
            pendingLayoutTrace
                ?: traceManager
                    ?.takeIf { lastResolveRenderId != UNSET }
                    ?.createMeasureTrace(lastResolveRenderId)
                    ?.also { measureTraceToBegin = it }
        pendingLayoutTrace = null

        val future = PriorityInheritingFuture(
            "LayoutFuture",
            getLayoutFutureCallable(committedTree, layoutVersion, taskTrace),
        )

        this.layoutTask =
            RootLayoutTask(layoutVersion, committedTree, sizeConstraints, future, taskTrace)
      }
      layoutTask = requireNotNull(this.layoutTask)
      if (deferLayoutCommitToMainThread && isMainThread) {
        layoutTask.hasMainThreadWaiter = true
      }
      futureFrameId = committedResolvedFrameId
      previousRenderResult = committedRenderResult
    }
    measureTraceToBegin?.let { traceManager?.begin(it) }
    val renderResult =
        try {
          layoutTask.future.runAndGet()
        } catch (throwable: Throwable) {
          if (isMainThread) {
            synchronized(this) { layoutTask.hasMainThreadWaiter = false }
          }
          throw throwable
        }
    var committedNewLayout = false
    var traceForCommit: RCRenderTrace? = null
    var traceToCancel: RCRenderTrace? = null
    synchronized(this) {
      if (isMainThread) {
        layoutTask.hasMainThreadWaiter = false
      }
      // Let an active UI measure commit its shared layout so the mount input is available to the
      // current traversal instead of being posted to the next frame by a background consumer. The
      // main waiter also owns cancellation if the result became stale while the future was running.
      val deferCommitToMain = layoutTask.hasMainThreadWaiter && !isMainThread
      if (
          !deferCommitToMain &&
              hasSameSpecs(layoutTask, sizeConstraints) &&
              layoutTask.version > committedLayoutVersion &&
              committedRenderResult != renderResult
      ) {
        committedLayoutVersion = layoutTask.version
        committedNewLayout = true
        committedLayoutFrameId = futureFrameId
        committedRenderResult = renderResult
        committedTrace = layoutTask.renderTrace
        traceForCommit = committedTrace
      } else if (!deferCommitToMain && layoutTask.renderTrace !== committedTrace) {
        // Two threads can share a layoutTask (same future, same trace) and both reach this
        // gate. The first wins and sets committedTrace to this trace; the second loses the
        // gate. Skip the cancel in that case — otherwise we'd transition the just-committed
        // trace from COMMITTED to CANCELLED via transitionToCancelled.
        traceToCancel = layoutTask.renderTrace
      }
      if (!deferCommitToMain && this.layoutTask == layoutTask) {
        this.layoutTask = null
      }
    }
    if (measureOutput != null) {
      measureOutput[0] = renderResult.renderTree.width
      measureOutput[1] = renderResult.renderTree.height
    }
    if (committedNewLayout) {
      delegate.commit(
          layoutTask.version,
          previousRenderResult?.renderTree,
          renderResult.renderTree,
          previousRenderResult?.state,
          renderResult.state,
      )
      traceForCommit?.markCommitted()
      schedulePromoteCommittedTreeToUI()
    }
    traceToCancel?.markCancelled(RCRenderTrace.CANCEL_REASON_NEWER_LAYOUT_COMMITTED)
  }

  private fun getLayoutFutureCallable(
      committedTree: Node<RenderContext>,
      layoutVersion: Int,
      renderTrace: RCRenderTrace?,
  ): Callable<RenderResult<Any?, RenderContext>> {
    if (rootLayoutFunction != null) {
      return Callable {
        // Only advance to RUNNING for measure-only traces; resolve traces already advanced
        // inside the resolve future.
        if (renderTrace?.currentState == RenderTraceState.PENDING) {
          renderTrace.markRunning()
        }
        renderTrace?.addPoint(RenderTraceManager.POINT_LAYOUT_START)
        try {
          val result =
              rootLayoutFunction.invoke(
                  context,
                  committedTree,
                  committedResolveState,
                  renderContext,
                  committedRenderResult,
                  extensions,
                  layoutVersion,
                  sizeConstraints,
              )
          renderTrace?.addPoint(RenderTraceManager.POINT_LAYOUT_END)
          result
        } catch (e: Throwable) {
          // Emit _end before cancelling so the trace ends cleanly; addPoint
          // becomes a no-op once the trace is terminal.
          renderTrace?.addPoint(RenderTraceManager.POINT_LAYOUT_END)
          renderTrace?.markCancelled(RCRenderTrace.CANCEL_REASON_LAYOUT_FAILED)
          throw e
        }
      }
    }

    return Callable {
      if (renderTrace?.currentState == RenderTraceState.PENDING) {
        renderTrace.markRunning()
      }
      renderTrace?.addPoint(RenderTraceManager.POINT_LAYOUT_START)
      try {
        val result: RenderResult<Any?, RenderContext> =
            RenderResult.render(
                context,
                ResolveResult(committedTree, committedResolveState as? Any),
                renderContext,
                extensions,
                committedRenderResult,
                layoutVersion,
                sizeConstraints,
            )
        renderTrace?.addPoint(RenderTraceManager.POINT_LAYOUT_END)
        result
      } catch (e: Throwable) {
        renderTrace?.addPoint(RenderTraceManager.POINT_LAYOUT_END)
        renderTrace?.markCancelled(RCRenderTrace.CANCEL_REASON_LAYOUT_FAILED)
        throw e
      }
    }
  }

  @ThreadConfined(ThreadConfined.UI)
  fun measure(sizeConstraints: SizeConstraints, measureOutput: IntArray?) {
    preMeasureCallback?.run()
    val futureToResolveBeforeMeasuring: ResolveFuture<ResolveState, RenderContext, StateUpdateType>?
    synchronized(this) {
      val specsChanged = !hasSizeConstraints || this.sizeConstraints != sizeConstraints
      if (specsChanged) {
        hasSizeConstraints = true
        this.sizeConstraints = sizeConstraints
      }
      // A specs change stales any in-flight layout at the exact-spec commit gate. If that
      // task carries the latest tree, the fast paths below would strand it with no layout.
      val inFlightLayout = layoutTask
      val mustRelayout =
          relayoutLatestTreeOnSpecsChange &&
              specsChanged &&
              inFlightLayout != null &&
              !hasSameSpecs(inFlightLayout, sizeConstraints)

      val renderTree = uiRenderTree
      // The current UI tree is compatible. We might just return those values
      if (!mustRelayout && renderTree != null && hasCompatibleSize(renderTree, sizeConstraints)) {
        if (measureOutput != null) {
          measureOutput[0] = renderTree.width
          measureOutput[1] = renderTree.height
        }
        return
      }
      val renderResult = committedRenderResult
      if (
          !mustRelayout &&
              renderResult != null &&
              hasCompatibleSize(renderResult.renderTree, sizeConstraints)
      ) {
        maybePromoteCommittedTreeToUI(removePendingMessage = true)
        if (measureOutput != null) {
          // We have a tree that we previously resolved with these constraints. For measuring we can
          // just return it
          measureOutput[0] = renderResult.renderTree.width
          measureOutput[1] = renderResult.renderTree.height
        }
        return
      }

      // We don't have a valid resolve function yet. Let's just bail until then.
      if (latestResolveFunc == null) {
        if (measureOutput != null) {
          measureOutput[0] = 0
          measureOutput[1] = 0
        }
        return
      }

      // If we do have a resolve function we expect to have either a committed resolved tree or a
      // future. If we have neither something has gone wrong with the setTree call.
      futureToResolveBeforeMeasuring =
          if (committedResolvedTree != null) {
            null
          } else {
            Objects.requireNonNull(resolveFuture)
          }
    }
    if (futureToResolveBeforeMeasuring != null) {
      futureToResolveBeforeMeasuring.renderTrace?.addPoint(
          RenderTraceManager.POINT_WAIT_FOR_RESOLVE_END,
      )
      val result = futureToResolveBeforeMeasuring.runAndGet()
      if (
          maybeCommitResolveResult(result, futureToResolveBeforeMeasuring) ==
              ResolveCommitResult.SUPERSEDED
      ) {
        futureToResolveBeforeMeasuring.renderTrace?.markCancelled(
            RCRenderTrace.CANCEL_REASON_NEWER_RESOLVE_COMMITTED,
        )
      }
    }
    layoutAndMaybeCommitInternal(measureOutput)
  }

  @ThreadConfined(ThreadConfined.UI)
  fun attach(hostListener: HostListener) {
    if (this.hostListener != null && this.hostListener != hostListener) {
      throw RuntimeException("Must detach from previous host listener first")
    }
    this.hostListener = hostListener
  }

  @ThreadConfined(ThreadConfined.UI)
  fun detach() {
    hostListener = null
  }

  @ThreadConfined(ThreadConfined.UI)
  fun destroy() {
    stateUpdateReceiver.clear()
    detach()
    uiHandler.removeCallbacksAndMessages(null)
    traceManager?.cancelAll()
  }

  private fun schedulePromoteCommittedTreeToUI() {
    if (ThreadUtils.isMainThread) {
      maybePromoteCommittedTreeToUI(removePendingMessage = true)
    } else {
      if (!uiHandler.hasMessages(PROMOTION_MESSAGE)) {
        uiHandler.sendEmptyMessage(PROMOTION_MESSAGE)
      }
    }
  }

  @ThreadConfined(ThreadConfined.UI)
  private fun maybePromoteCommittedTreeToUI(removePendingMessage: Boolean) {
    if (removePendingMessage) {
      uiHandler.removeMessages(PROMOTION_MESSAGE)
    }
    if (deferCommitToUIUntilMount) commitDeferred() else commitImmediate()
  }

  private fun commitImmediate() {
    synchronized(this) {
      val committedRenderResult = this.committedRenderResult
      if (committedRenderResult == null) {
        return
      }
      val trace = this.committedTrace
      this.committedTrace = null

      trace?.markCommittedToUI()
      delegate.commitToUI(
          committedRenderResult.renderTree,
          committedRenderResult.state,
          committedLayoutFrameId,
          trace,
      )
      if (uiRootHostInput?.mountInput === committedRenderResult.mountInput) {
        return
      }
      uiRootHostInput = RootHostInput(committedRenderResult.mountInput, null, UNSET, null, null)
    }
    hostListener?.onMountInputUpdated(uiRootHostInput)
  }

  private fun commitDeferred() {
    var mountInputForHost: RootHostInput? = null
    synchronized(this) {
      val committedRenderResult = this.committedRenderResult ?: return
      val trace = this.committedTrace
      this.committedTrace = null
      val currentRootHostInput = uiRootHostInput
      val currentMountInput = currentRootHostInput?.mountInput

      if (currentMountInput === committedRenderResult.mountInput) {
        if (currentRootHostInput.hasPendingCommit) {
          currentRootHostInput.updateCommit(
              committedRenderResult.state,
              committedLayoutFrameId,
              delegate,
              trace,
          )
          trace?.markPendingCommitToUI()
        } else {
          trace?.markCommittedToUI()
          delegate.commitToUI(
              committedRenderResult.renderTree,
              committedRenderResult.state,
              committedLayoutFrameId,
              trace,
          )
        }
        return
      }

      currentRootHostInput?.clearCommit()
      uiRootHostInput =
          RootHostInput(
              committedRenderResult.mountInput,
              committedRenderResult.state,
              committedLayoutFrameId,
              delegate,
              trace,
          )
      trace?.markPendingCommitToUI()
      mountInputForHost = uiRootHostInput
    }

    hostListener?.onMountInputUpdated(mountInputForHost)
  }

  private inner class RenderStateHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(msg: Message) {
      when (msg.what) {
        PROMOTION_MESSAGE -> maybePromoteCommittedTreeToUI(removePendingMessage = false)
        else -> throw RuntimeException("Unknown message: " + msg.what)
      }
    }
  }

  private class RootLayoutTask<RenderContext>(
      val version: Int,
      val tree: Node<RenderContext>,
      val sizeConstraints: SizeConstraints,
      val future: PriorityInheritingFuture<RenderResult<Any?, RenderContext>>,
      val renderTrace: RCRenderTrace?,
  ) {
    // Read and written only while holding the owning RenderState's monitor.
    var hasMainThreadWaiter: Boolean = false
  }

  private enum class ResolveCommitResult {
    COMMITTED,
    ALREADY_COMMITTED,
    SUPERSEDED,
  }

  private class StateUpdateReceiverDelegate<
      ResolveState,
      RenderContext,
      StateUpdateType : StateUpdate<*>,
  >(
      renderState: RenderState<ResolveState, RenderContext, StateUpdateType>,
  ) : StateUpdateReceiver<StateUpdateType> {
    @Volatile
    private var renderState: RenderState<ResolveState, RenderContext, StateUpdateType>? =
        renderState

    @ThreadConfined(ThreadConfined.ANY)
    override fun enqueueStateUpdate(stateUpdate: StateUpdateType) {
      renderState?.enqueueStateUpdateInternal(stateUpdate, true)
    }

    @ThreadConfined(ThreadConfined.ANY)
    override fun enqueueStateUpdateSync(stateUpdate: StateUpdateType) {
      renderState?.enqueueStateUpdateInternal(stateUpdate, false)
    }

    fun clear() {
      renderState = null
    }
  }

  companion object {
    @JvmField var NO_ID: Int = -1
    const val UNSET: Int = -1
    private const val PROMOTION_MESSAGE: Int = 99
    private val ID_GENERATOR: AtomicInteger = AtomicInteger(0)

    @JvmStatic
    private fun hasCompatibleSize(tree: RenderTree, sizeConstraints: SizeConstraints): Boolean {
      return sizeConstraints.areCompatible(tree.sizeConstraints, IntSize(tree.width, tree.height))
    }

    @JvmStatic
    private fun <RenderContext> hasSameSpecs(
        task: RootLayoutTask<RenderContext>,
        sizeConstraints: SizeConstraints,
    ): Boolean {
      return task.sizeConstraints == sizeConstraints
    }
  }
}

class RootHostInput
internal constructor(
    @JvmField val mountInput: MountInput,
    private var state: Any?,
    private var frameVersion: Int,
    private var delegate: RenderState.Delegate<Any?>?,
    private var renderTrace: RCRenderTrace?,
) {
  val renderTree: RenderTree
    get() = mountInput.renderTree

  internal val hasPendingCommit: Boolean
    get() = delegate != null

  internal fun clearCommit() {
    renderTrace?.markCancelled(RCRenderTrace.CANCEL_REASON_DEFERRED_COMMIT_TO_UI_SUPERSEDED)
    delegate = null
    state = null
    renderTrace = null
  }

  internal fun updateCommit(
      state: Any?,
      frameVersion: Int,
      delegate: RenderState.Delegate<Any?>,
      renderTrace: RCRenderTrace?,
  ) {
    this.state = state
    this.frameVersion = frameVersion
    this.delegate = delegate
    this.renderTrace = renderTrace
  }

  fun commitToUI() {
    val delegate = delegate ?: return
    val state = state
    val renderTrace = renderTrace
    this.delegate = null
    this.state = null
    this.renderTrace = null
    renderTrace?.markCommittedToUI()
    delegate.commitToUI(mountInput.renderTree, state, frameVersion, renderTrace)
  }
}
