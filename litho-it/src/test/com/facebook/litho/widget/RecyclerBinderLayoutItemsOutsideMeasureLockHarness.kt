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

import android.view.View
import androidx.recyclerview.widget.OrientationHelper
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.ErrorEventHandler
import com.facebook.litho.EventDispatchInfo
import com.facebook.litho.EventHandler
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.LithoStartupLogger
import com.facebook.litho.LithoView
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec
import com.facebook.litho.State
import com.facebook.litho.annotations.EventHandlerRebindMode
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.RunnableHandlerProvider
import com.facebook.litho.useState
import com.facebook.litho.viewcompat.SimpleViewBinder
import com.facebook.litho.viewcompat.ViewCreator
import com.facebook.rendercore.RunnableHandler
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope as PrimitiveLayoutScope
import com.facebook.rendercore.primitives.MountBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.shadows.ShadowLooper

/**
 * Test harness for [RecyclerBinderConfig.layoutItemsOutsideMeasureLock]: a real binder whose item
 * layouts can park on threads the test controls, so a test decides which layouts overlap which
 * operations. The same harness builds the binder with the flag off, so a schedule can be compared
 * with the locked binder.
 */
internal class LockTestQueue : RunnableHandler {
  private val tasks = ConcurrentLinkedDeque<Runnable>()

  override fun isTracing(): Boolean = false

  override fun post(runnable: Runnable, tag: String) {
    tasks.addLast(runnable)
  }

  override fun postAtFront(runnable: Runnable, tag: String) {
    tasks.addFirst(runnable)
  }

  override fun remove(runnable: Runnable) {
    tasks.removeFirstOccurrence(runnable)
  }

  val isEmpty: Boolean
    get() = tasks.isEmpty()

  fun poll(): Runnable? = tasks.pollFirst()
}

/**
 * A thread that runs the tasks it is given, one at a time, and parks inside each item layout (and
 * at holder hooks) until released.
 */
internal class LockTestThread(val name: String, private val parkOrder: () -> Long) {
  @Volatile var parkedAt: Long = 0L
  @Volatile var busy: Boolean = false
  /** What this thread parked in: an item serial, or a hook name. */
  @Volatile var parkedIn: Any? = null
  private val permits = Semaphore(0)
  private val tasks = LinkedBlockingQueue<() -> Unit>()
  val failures: MutableList<String> = Collections.synchronizedList(ArrayList())
  val thread: Thread = Thread(
      {
        while (true) {
          val task =
              try {
                tasks.take()
              } catch (stopped: InterruptedException) {
                break
              }
          try {
            task()
          } catch (failure: Throwable) {
            failures.add("$name: $failure ${failure.stackTrace.take(6)}")
          } finally {
            busy = false
          }
        }
      },
      name,
  )
      .apply {
        isDaemon = true
        start()
      }

  val isParked: Boolean
    get() = parkedAt != 0L

  /** Runs [task] on this thread and waits until it parks, finishes or waits on something. */
  fun run(task: () -> Unit) {
    busy = true
    tasks.put(task)
    settle()
  }

  /** Called on this thread. */
  fun park(where: Any) {
    parkedIn = where
    parkedAt = parkOrder()
    try {
      check(permits.tryAcquire(20, TimeUnit.SECONDS)) { "$name parked in $where never released" }
    } finally {
      parkedAt = 0L
      parkedIn = null
    }
  }

  /** The park the last release was for: several releasers (main, the watchdogs) may race. */
  private var releasedPark = 0L

  fun stop() {
    thread.interrupt()
  }

  fun release() {
    val parkedAtRelease: Long
    synchronized(this) {
      parkedAtRelease = parkedAt
      // Only one release per park, or a spare permit would let a later park through.
      if (parkedAtRelease == 0L || releasedPark == parkedAtRelease) return
      releasedPark = parkedAtRelease
      permits.release()
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (parkedAt == parkedAtRelease && System.nanoTime() < deadline) Thread.yield()
    settle()
  }

  /** True when this thread is blocked entering a monitor of [lockClass]. */
  fun isBlockedOn(lockClass: Class<*>): Boolean {
    val info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.id) ?: return false
    return info.threadState == Thread.State.BLOCKED &&
        info.lockName?.startsWith(lockClass.name + "@") == true
  }

  /** Waits until this thread parks, has nothing to run, or waits on something else. */
  fun settle() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var waitingSince = 0L
    while (System.nanoTime() < deadline) {
      if (isParked || !busy) return
      val state = thread.state
      if (
          state == Thread.State.WAITING ||
              state == Thread.State.BLOCKED ||
              state == Thread.State.TIMED_WAITING
      ) {
        if (waitingSince == 0L) waitingSince = System.nanoTime()
        else if (System.nanoTime() - waitingSince > 1_000_000) return
      } else {
        waitingSince = 0L
      }
      Thread.yield()
    }
    error("$name never settled")
  }
}

/** One item layout: which item, on which thread, at which constraints and state value. */
internal data class LockTestLayout(
    val serial: Int,
    val thread: String,
    val constraints: SizeConstraints,
    val stateValue: Int,
    val index: Int,
)

/** Hooks shared by a harness's items and holders. */
internal class LockTestHooks {
  @Volatile var parkLayouts: Boolean = true
  val controlled: MutableMap<Thread, LockTestThread> = ConcurrentHashMap()
  val states: MutableMap<Int, State<Int>> = ConcurrentHashMap()
  val layouts: MutableList<LockTestLayout> = Collections.synchronizedList(ArrayList())
  private val layoutCounter = AtomicInteger()
  /**
   * Called on the calling thread before a holder's computeLayoutSync / computeLayoutAsync; returns
   * whether to park there.
   */
  @Volatile
  var parkBeforeRequest: ((holder: ComponentTreeHolder, async: Boolean) -> Boolean)? = null
  val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
  /** The binder whose lock [syncLayoutsUnderBinderLock] watches; set once it is built. */
  @Volatile var binder: Any? = null
  /** Sync item layouts requested while the requesting thread held the binder lock. */
  val syncLayoutsUnderBinderLock: AtomicInteger = AtomicInteger()

  /** Where main laid an item out first, for failure messages. */
  @Volatile var firstMainLayoutStack: String? = null

  /** Called on main inside each of its item layouts. */
  @Volatile var onMainLayout: ((serial: Int) -> Unit)? = null

  fun onLayout(serial: Int, constraints: SizeConstraints, stateValue: Int) {
    val thread = Thread.currentThread()
    val controlledThread = controlled[thread]
    if (controlledThread == null && thread.name.contains("main", ignoreCase = true)) {
      if (firstMainLayoutStack == null) {
        firstMainLayoutStack =
            Throwable()
                .stackTrace
                .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                .filter { it.startsWith("RecyclerBinder") || it.startsWith("ComponentTreeHolder") }
                .take(8)
                .joinToString(" < ")
      }
      onMainLayout?.invoke(serial)
    }
    layouts.add(
        LockTestLayout(
            serial,
            controlledThread?.name
                ?: if (thread.name.contains("main", ignoreCase = true)) "main" else thread.name,
            constraints,
            stateValue,
            layoutCounter.incrementAndGet(),
        ),
    )
    if (parkLayouts && controlledThread != null) controlledThread.park(serial)
  }

  /** Called on main before each of its holder requests. */
  @Volatile var onMainRequest: ((holder: ComponentTreeHolder, async: Boolean) -> Unit)? = null

  fun beforeRequest(holder: ComponentTreeHolder, async: Boolean, widthSpec: Int, heightSpec: Int) {
    val controlledThread = controlled[Thread.currentThread()]
    if (
        controlledThread == null && Thread.currentThread().name.contains("main", ignoreCase = true)
    ) {
      onMainRequest?.invoke(holder, async)
    }
    if (controlledThread != null && parkBeforeRequest?.invoke(holder, async) == true) {
      controlledThread.park(if (async) "async request" else "sync request")
    }
    // Logged when the request goes on, after any park.
    val item = holder.renderInfo.takeIf { it.rendersComponent() }?.component as? LockTestItem
    requests.add(
        "${if (async) "async" else "sync"} ${item?.serial} " +
            "${SizeSpec.toSimpleString(widthSpec)}/${SizeSpec.toSimpleString(heightSpec)} " +
            "on ${controlledThread?.name ?: Thread.currentThread().name}",
    )
  }

  fun layoutsOf(serial: Int): List<LockTestLayout> =
      synchronized(layouts) { layouts.filter { it.serial == serial } }
}

/** The size an item gives: width from its content, height from content, width and state. */
internal object LockTestSizes {
  fun preferredWidth(content: Int): Int = 10 * (content % 4) + 5

  fun height(content: Int, width: Int, stateValue: Int): Int =
      ((3 * content + width / 5) % 4) * 10 + 10 + 5 * stateValue
}

private class LockTestBehavior(
    private val serial: Int,
    private val content: Int,
    private val stateValue: Int,
    private val hooks: LockTestHooks,
) : LayoutBehavior {
  override fun PrimitiveLayoutScope.layout(constraints: SizeConstraints): PrimitiveLayoutResult {
    hooks.onLayout(serial, constraints, stateValue)
    val preferred = LockTestSizes.preferredWidth(content)
    val width =
        if (constraints.hasBoundedWidth)
            preferred.coerceIn(constraints.minWidth, constraints.maxWidth)
        else preferred.coerceAtLeast(constraints.minWidth)
    val height = LockTestSizes.height(content, width, stateValue)
    return PrimitiveLayoutResult(
        width = width,
        height =
            if (constraints.hasBoundedHeight)
                height.coerceIn(constraints.minHeight, constraints.maxHeight)
            else height.coerceAtLeast(constraints.minHeight),
    )
  }
}

/** An item: [serial] identifies the render info (clones keep it), [content] picks its size. */
internal class LockTestItem(val serial: Int, val content: Int, private val hooks: LockTestHooks) :
    PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    val extra = useState { 0 }
    hooks.states[serial] = extra
    return LithoPrimitive(
        layoutBehavior = LockTestBehavior(serial, content, extra.value, hooks),
        mountBehavior = MountBehavior(ViewAllocator { androidContext -> View(androidContext) }) {},
        style = null,
    )
  }
}

/** A holder that tells the hooks before each layout request, so a test can park there. */
internal class LockTestHolder(
    builder: ComponentTreeHolder.Builder,
    private val hooks: LockTestHooks,
) : ComponentTreeHolder(builder) {
  override fun computeLayoutSync(
      context: ComponentContext,
      widthSpec: Int,
      heightSpec: Int,
      size: Size?,
      forceLayout: Boolean,
  ) {
    val binder = hooks.binder
    if (binder != null && Thread.holdsLock(binder)) {
      hooks.syncLayoutsUnderBinderLock.incrementAndGet()
    }
    hooks.beforeRequest(this, false, widthSpec, heightSpec)
    super.computeLayoutSync(context, widthSpec, heightSpec, size, forceLayout)
  }

  override fun computeLayoutAsync(
      context: ComponentContext,
      widthSpec: Int,
      heightSpec: Int,
      measureListener: ComponentTree.MeasureListener?,
      forceLayout: Boolean,
  ) {
    hooks.beforeRequest(this, true, widthSpec, heightSpec)
    super.computeLayoutAsync(context, widthSpec, heightSpec, measureListener, forceLayout)
  }
}

/**
 * One binder, with or without [RecyclerBinderConfig.layoutItemsOutsideMeasureLock]. A null [flag]
 * leaves it unset, so the binder follows [configurationFlag], its
 * [ComponentsConfiguration.layoutItemsOutsideMeasureLock].
 */
internal class LockTestHarness(
    val context: ComponentContext,
    val flag: Boolean?,
    val wrap: Boolean = false,
    val horizontal: Boolean = false,
    val hScrollAsync: Boolean = false,
    asyncInsertQueue: Boolean = false,
    errorEventHandler: ErrorEventHandler? = null,
    startupLogger: LithoStartupLogger? = null,
    retainMaximumRange: Boolean = false,
    componentWarmer: ComponentWarmer? = null,
    configurationFlag: Boolean = false,
) {
  val hooks = LockTestHooks()
  private var parkCounter = 0L
  val queue = LockTestQueue()
  /** The async insert layout handler's queue, when the binder has one. */
  val insertQueue: LockTestQueue? = if (asyncInsertQueue) LockTestQueue() else null
  val worker = thread("worker")
  val backgrounds = ArrayList<LockTestThread>()
  var remeasureRequested = false
  var mainReleases = 0
  private var serials = 0
  /** Every holder the binder ever had, to find removed holders that kept a tree. */
  val allHolders: MutableSet<ComponentTreeHolder> = Collections.newSetFromMap(ConcurrentHashMap())

  val remeasureHandler: EventHandler<ReMeasureEvent> =
      object :
          EventHandler<ReMeasureEvent>(
              1,
              EventHandlerRebindMode.NONE,
              EventDispatchInfo(null, null),
              null,
          ) {
        override fun dispatchEvent(event: ReMeasureEvent): Any? {
          remeasureRequested = true
          return null
        }
      }

  val binder: RecyclerBinder =
      RecyclerBinder.Builder()
          .recyclerBinderConfig(
              RecyclerBinderConfig.create()
                  .wrapContent(wrap)
                  .hScrollAsyncMode(hScrollAsync)
                  .apply {
                    if (flag != null) {
                      layoutItemsOutsideMeasureLock(flag)
                    }
                    if (retainMaximumRange) {
                      recyclingStrategy(RecyclerBinder.RecyclingStrategy.RETAIN_MAXIMUM_RANGE)
                    }
                  }
                  .componentWarmer(componentWarmer)
                  .componentsConfiguration(
                      ComponentsConfiguration.create(context.lithoConfiguration.componentsConfig)
                          .customLayoutHandlerProvider(
                              object : RunnableHandlerProvider {
                                override fun getRunnableHandler(): RunnableHandler = queue
                              },
                          )
                          .layoutItemsOutsideMeasureLock(configurationFlag)
                          .build()
                          .let { config ->
                            if (errorEventHandler == null) config
                            else config.copy(errorEventHandler = errorEventHandler)
                          },
                  )
                  .build(),
          )
          .layoutInfo(
              LinearLayoutInfo(
                  context,
                  if (horizontal) OrientationHelper.HORIZONTAL else OrientationHelper.VERTICAL,
                  false,
              ),
          )
          .componentTreeHolderFactory {
              renderInfo,
              layoutHandler,
              measureListenerFactory,
              componentsConfiguration,
              lifecycleProvider ->
            LockTestHolder(
                ComponentTreeHolder.create(componentsConfiguration)
                    .renderInfo(renderInfo)
                    .layoutHandler(layoutHandler)
                    .componentTreeMeasureListenerFactory(measureListenerFactory)
                    .lithoVisibilityEventsController(lifecycleProvider),
                hooks,
            )
                .also { allHolders.add(it) }
          }
          .apply { if (insertQueue != null) asyncInsertLayoutHandler(insertQueue) }
          .apply { if (startupLogger != null) startupLogger(startupLogger) }
          .build(context)
          .also { builtBinder -> hooks.binder = builtBinder }

  fun thread(name: String): LockTestThread =
      LockTestThread(name) { ++parkCounter }.also { hooks.controlled[it.thread] = it }

  fun item(content: Int): RenderInfo =
      ComponentRenderInfo.create().component(LockTestItem(++serials, content, hooks)).build()

  fun view(): RenderInfo =
      ViewRenderInfo.create()
          .viewBinder(SimpleViewBinder<View>())
          .viewCreator(
              object : ViewCreator<View> {
                override fun createView(
                    context: android.content.Context,
                    parent: android.view.ViewGroup?,
                ): View = View(context)
              },
          )
          .build()

  fun serialAt(position: Int): Int =
      (binder.componentTreeHolders[position].renderInfo.component as LockTestItem).serial

  val failures: List<String>
    get() = worker.failures + backgrounds.flatMap { it.failures }

  fun stop() {
    (listOf(worker) + backgrounds).forEach { it.stop() }
    watchdog.interrupt()
  }

  /**
   * Main can also wait on a parked layout outside [onMain] (a sync state update that main's looper
   * runs, a join on another thread's tree future): whenever main waits (not a timed wait) while
   * threads are parked, let the oldest go.
   */
  private val mainThread = Thread.currentThread()
  private val watchdog: Thread = Thread(
      {
        var waitingSince = 0L
        while (!Thread.currentThread().isInterrupted) {
          val state = mainThread.state
          if (state == Thread.State.WAITING || state == Thread.State.BLOCKED) {
            if (waitingSince == 0L) waitingSince = System.nanoTime()
            else if (System.nanoTime() - waitingSince > 20_000_000) {
              parked().firstOrNull()?.release()
              waitingSince = 0L
            }
          } else {
            waitingSince = 0L
          }
          try {
            Thread.sleep(1)
          } catch (stopped: InterruptedException) {
            break
          }
        }
      },
      "watchdog",
  )
      .apply {
        isDaemon = true
        start()
      }

  fun parked(): List<LockTestThread> =
      (listOf(worker) + backgrounds).filter { it.isParked }.sortedBy { it.parkedAt }

  /** Runs [action] on main; while main waits on another thread, lets the oldest parked one go. */
  fun onMain(action: () -> Unit) {
    val mainThread = Thread.currentThread()
    val done = AtomicBoolean(false)
    val releaser = Thread(
        {
          var waitingSince = 0L
          while (!done.get()) {
            val state = mainThread.state
            if (
                state == Thread.State.WAITING ||
                    state == Thread.State.BLOCKED ||
                    state == Thread.State.TIMED_WAITING
            ) {
              if (waitingSince == 0L) waitingSince = System.nanoTime()
              else if (System.nanoTime() - waitingSince > 1_000_000) {
                parked().firstOrNull()?.let { oldest ->
                  mainReleases++
                  oldest.release()
                }
                waitingSince = 0L
              }
            } else {
              waitingSince = 0L
            }
            Thread.yield()
          }
        },
        "releaser",
    )
        .apply { start() }
    try {
      action()
    } finally {
      done.set(true)
      releaser.join(10_000)
    }
  }

  fun insertAll(vararg contents: Int) {
    onMain {
      binder.insertRangeAt(0, contents.map { item(it) })
      binder.notifyChangeSetComplete(true, RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK)
    }
  }

  fun measureMain(widthSpec: Int, heightSpec: Int, withHandler: Boolean = false): Size {
    val size = Size()
    onMain {
      binder.measure(size, widthSpec, heightSpec, if (withHandler) remeasureHandler else null)
    }
    return size
  }

  /** Starts a measure on a new background thread; returns once it parks, finishes or waits. */
  fun measureBackground(
      widthSpec: Int,
      heightSpec: Int,
      withHandler: Boolean = false,
      out: Size = Size(),
      onDone: (() -> Unit)? = null,
  ): LockTestThread {
    val background = thread("background-${backgrounds.size}")
    backgrounds.add(background)
    background.run {
      binder.measure(out, widthSpec, heightSpec, if (withHandler) remeasureHandler else null)
      onDone?.invoke()
    }
    return background
  }

  fun setSizeBackground(width: Int, height: Int, onDone: (() -> Unit)? = null): LockTestThread {
    val background = thread("background-${backgrounds.size}")
    backgrounds.add(background)
    background.run {
      binder.setSize(width, height)
      onDone?.invoke()
    }
    return background
  }

  /** Runs the next queued layout-thread task on the worker. */
  fun workerStep(fromInsertQueue: Boolean = false): Boolean {
    val source = if (fromInsertQueue) insertQueue ?: return false else queue
    if (worker.busy || source.isEmpty) return false
    worker.run { source.poll()?.run() }
    return true
  }

  private val viewHolders = HashMap<Int, androidx.recyclerview.widget.RecyclerView.ViewHolder>()

  /** RecyclerView's bind of [position] (the adapter's onBindViewHolder), on main; its LithoView. */
  fun bind(position: Int): LithoView? {
    var view: LithoView? = null
    // All on main through onMain: the binder's getters take its lock, which a background measure
    // of the locked binder holds while parked.
    onMain {
      if (binder.componentTreeHolders[position].renderInfo.rendersComponent()) {
        val viewHolder =
            viewHolders.getOrPut(position) {
              binder.internalAdapter.onCreateViewHolder(
                  android.widget.FrameLayout(context.androidContext),
                  RenderInfoViewCreatorController.DEFAULT_COMPONENT_VIEW_TYPE,
              )
            }
        binder.internalAdapter.onBindViewHolder(viewHolder, position)
        view = viewHolder.itemView as? LithoView
      }
    }
    return view
  }

  /** The bound item's host LithoView measuring with its own specs, on main. */
  fun hostViewMeasure(position: Int, widthSpec: Int, heightSpec: Int): Size? {
    val view = bind(position) ?: return null
    // A LithoView measured at EXACTLY x EXACTLY lays its tree out in its layout pass.
    onMain {
      view.measure(widthSpec, heightSpec)
      view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    return Size(view.measuredWidth, view.measuredHeight)
  }

  /** Lets everything run to the end. */
  fun quiesce(
      deliverRemeasures: Boolean = true,
      lastSpecs: (() -> Triple<Int, Int, Boolean>)? = null,
  ): Boolean {
    var remeasures = 0
    var idledDelayedTasks = false
    repeat(400) {
      parked().firstOrNull()?.let { oldest ->
        oldest.release()
        return@repeat
      }
      if (!worker.busy && !queue.isEmpty) {
        worker.run { queue.poll()?.run() }
        return@repeat
      }
      val inserts = insertQueue
      if (!worker.busy && inserts != null && !inserts.isEmpty) {
        worker.run { inserts.poll()?.run() }
        return@repeat
      }
      if ((listOf(worker) + backgrounds).any { it.busy }) {
        Thread.sleep(1)
        return@repeat
      }
      // Through onMain: main's tasks (a sync state update, a posted remeasure) may wait on a parked
      // layout.
      onMain { ShadowLooper.idleMainLooper() }
      if (!queue.isEmpty || inserts?.isEmpty == false) return@repeat
      if (!idledDelayedTasks) {
        // Delayed tasks (a batched state update's frame): let 20 ms pass once.
        idledDelayedTasks = true
        onMain { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS) }
        return@repeat
      }
      if (deliverRemeasures && remeasureRequested && lastSpecs != null && remeasures < 3) {
        remeasureRequested = false
        remeasures++
        val (widthSpec, heightSpec, withHandler) = lastSpecs()
        measureMain(widthSpec, heightSpec, withHandler)
        return@repeat
      }
      return true
    }
    return false
  }

  /**
   * Quiesces with the environment doing its part: a range computation over the visible window and
   * binds of its items, repeated until nothing new is laid out.
   */
  fun quiesceWithEnvironment(lastSpecs: (() -> Triple<Int, Int, Boolean>)? = null): Boolean {
    repeat(4) {
      val layoutsBefore = hooks.layouts.size
      if (!quiesce(lastSpecs = lastSpecs)) return false
      if (binder.componentTreeHolders.isEmpty() || lastSpecs == null && !isMeasured()) return true
      onMain {
        binder.onNewVisibleRange(
            maxOf(binder.currentFirstVisiblePosition, 0),
            maxOf(binder.currentLastVisiblePosition, 0),
        )
      }
      if (!quiesce(lastSpecs = lastSpecs)) return false
      for (position in window()) {
        bind(position)
      }
      if (!quiesce(lastSpecs = lastSpecs)) return false
      if (hooks.layouts.size == layoutsBefore) return true
    }
    return true
  }

  private fun isMeasured(): Boolean =
      binder.sizeForMeasure != null || binder.estimatedViewportCount > 0

  /** The visible window: the first visible item and the estimated viewport after it. */
  fun window(): IntRange {
    val count = binder.componentTreeHolders.size
    if (count == 0) return IntRange.EMPTY
    val first = maxOf(binder.currentFirstVisiblePosition, 0).coerceAtMost(count - 1)
    val last = minOf(
        maxOf(
            binder.currentLastVisiblePosition,
            first + maxOf(binder.estimatedViewportCount, 1) - 1,
        ),
        count - 1,
    )
    return first..last
  }

  private fun committedSerial(tree: ComponentTree): Int? =
      (tree.getCommittedLayoutState()?.rootComponent as? LockTestItem)?.serial

  /**
   * Items whose holder claims a valid tree that shows another component, or whose committed layout
   * does not fit the specs the holder was last asked for.
   */
  fun staleItems(): List<String> {
    val problems = ArrayList<String>()
    binder.componentTreeHolders.forEachIndexed { index, holder ->
      val renderInfo = holder.renderInfo
      if (!renderInfo.rendersComponent()) return@forEachIndexed
      val tree = holder.componentTree ?: return@forEachIndexed
      if (!holder.isTreeValid) return@forEachIndexed
      val serial = (renderInfo.component as LockTestItem).serial
      val committed = committedSerial(tree)
      if (committed != serial) {
        problems.add("item $index (serial $serial): valid, but its tree shows serial $committed")
      }
      val widthSpec = binder.getChildWidthSpec(index)
      val heightSpec = binder.getChildHeightSpec(index)
      if (
          holder.isTreeValidForSizeSpecs(widthSpec, heightSpec) &&
              !tree.hasCompatibleLayout(widthSpec, heightSpec)
      ) {
        val layout = tree.getCommittedLayoutState()
        problems.add(
            "item $index (serial $serial): valid at ${SizeSpec.toSimpleString(widthSpec)}/" +
                "${SizeSpec.toSimpleString(heightSpec)}, committed ${layout?.width}x${layout?.height}",
        )
      }
    }
    return problems
  }

  /** Items of the visible window that are not laid out at their specs with their component. */
  fun windowNotLaidOut(): List<String> {
    val problems = ArrayList<String>()
    for (index in window()) {
      val holder = binder.componentTreeHolders[index]
      val renderInfo = holder.renderInfo
      if (!renderInfo.rendersComponent()) continue
      val serial = (renderInfo.component as LockTestItem).serial
      val widthSpec = binder.getChildWidthSpec(index)
      val heightSpec = binder.getChildHeightSpec(index)
      val tree = holder.componentTree
      val isLaidOut =
          tree != null &&
              holder.isTreeValidForSizeSpecs(widthSpec, heightSpec) &&
              tree.hasCompatibleLayout(widthSpec, heightSpec) &&
              committedSerial(tree) == serial
      if (!isLaidOut) {
        problems.add(
            "item $index (serial $serial) not laid out at ${SizeSpec.toSimpleString(widthSpec)}/" +
                "${SizeSpec.toSimpleString(heightSpec)}: valid=${holder.isTreeValid} " +
                "committed=${tree?.getCommittedLayoutState()?.let { "${it.width}x${it.height}" }} " +
                "serial=${tree?.let { committedSerial(it) }}",
        )
      }
    }
    return problems
  }

  /** Removed holders that still have a tree once main ran its posted releases. */
  fun leakedTrees(): List<String> {
    val current = binder.componentTreeHolders.toSet()
    return allHolders
        .filter { it !in current && it.componentTree != null }
        .map { holder ->
          "removed holder ${(holder.renderInfo.takeIf { it.rendersComponent() }?.component as? LockTestItem)?.serial} kept a tree"
        }
  }

  private fun treeFields(tree: ComponentTree): String = listOf(
      "widthSpec",
      "heightSpec",
      "externalRootVersion",
      "currentDoLayoutRunnable",
      "currentDoResolveRunnable",
  )
      .joinToString(" ") { name ->
        val value =
            try {
              ComponentTree::class
                  .java
                  .getDeclaredField(name)
                  .apply { isAccessible = true }
                  .get(tree)
            } catch (failure: Exception) {
              "?"
            }
        "$name=${if (value is Int && name.endsWith("Spec")) SizeSpec.toSimpleString(value) else value}"
      }

  /** The binder's and items' state, for failure messages. */
  fun debugState(): String = buildString {
    append(
        "flag=$flag wrap=$wrap range=${binder.estimatedViewportCount} first=${binder.currentFirstVisiblePosition} ",
    )
    append("sizeForMeasure=${binder.sizeForMeasure?.let { "${it.width}x${it.height}" }}\n")
    binder.componentTreeHolders.forEachIndexed { index, holder ->
      val renderInfo = holder.renderInfo
      if (!renderInfo.rendersComponent()) {
        append("  $index: view\n")
        return@forEachIndexed
      }
      val widthSpec = binder.getChildWidthSpec(index)
      val heightSpec = binder.getChildHeightSpec(index)
      val layout = holder.componentTree?.getCommittedLayoutState()
      append(
          "  $index: serial=${(renderInfo.component as LockTestItem).serial} valid=${holder.isTreeValid} " +
              "validForSpecs=${holder.isTreeValidForSizeSpecs(widthSpec, heightSpec)} " +
              "specs=${SizeSpec.toSimpleString(widthSpec)}/${SizeSpec.toSimpleString(heightSpec)} " +
              "committed=${layout?.let { "${it.width}x${it.height}@${it.sizeConstraints}" }} " +
              "tree=${holder.componentTree?.let { treeFields(it) }}\n",
      )
    }
    append("queue empty=${queue.isEmpty}\n")
    append("requests: ${hooks.requests}\n")
    append(
        "layouts: ${synchronized(hooks.layouts) { hooks.layouts.map { "${it.serial}/${it.thread}/${it.constraints}" } }}\n",
    )
  }

  /** Whether the binder's lock is free right now (no thread lays out under it). */
  fun binderLockFree(): Boolean {
    val free = AtomicBoolean(false)
    val probe = Thread { synchronized(binder) { free.set(true) } }
    probe.start()
    probe.join(200)
    return free.get()
  }

  companion object {
    fun exactly(size: Int): Int = SizeSpec.makeSizeSpec(size, SizeSpec.EXACTLY)

    fun atMost(size: Int): Int = SizeSpec.makeSizeSpec(size, SizeSpec.AT_MOST)

    fun unspecified(): Int = SizeSpec.makeSizeSpec(0, SizeSpec.UNSPECIFIED)
  }
}
