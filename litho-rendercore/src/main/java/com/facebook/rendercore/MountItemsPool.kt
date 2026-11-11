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

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.core.util.Pools
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.rendercore.thread.utils.ThreadUtils.runOnUiThread
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.concurrent.GuardedBy

/**
 * Pools of recycled resources.
 *
 * FUTURE: Consider customizing the pool implementation such that we can match buffer sizes. Without
 * this we will tend to expand all buffers to the largest size needed.
 */
object MountContentPools {

  private var mountItemPoolsReleaseValidator: MountItemPoolsReleaseValidator? = null

  const val ATTACHED_VIEW_RELEASED_CATEGORY: String = "MountContentPools:ReleasedAttachedView"
  const val ATTACHED_VIEW_ACQUIRED_CATEGORY: String = "MountContentPools:AcquiredAttachedView"
  private const val MAX_REPORTED_PARENTS: Int = 4

  /** A factory used to create [MountContentPools.ContentPool]s. */
  fun interface Factory {

    /** Creates an ContentPool for the mountable content. */
    fun createMountContentPool(): ContentPool
  }

  private val mountContentLock: Any = Any()

  @GuardedBy("mountContentLock")
  private val mountContentPoolsByContext: MutableMap<Context, ContextContentPools> = HashMap(4)

  // This Map is used as a set and the values are ignored.
  @GuardedBy("mountContentLock")
  private val destroyedRootContexts: WeakHashMap<Context, Boolean> = WeakHashMap<Context, Boolean>()

  @GuardedBy("mountContentLock")
  private val contextsWithLifecycleObservers: WeakHashMap<Context, Boolean> =
      WeakHashMap<Context, Boolean>()

  private var activityCallbacks: PoolsActivityCallback? = null

  /**
   * To support Gingerbread (where the registerActivityLifecycleCallbacks API doesn't exist), we
   * allow apps to explicitly invoke activity callbacks. If this is enabled we'll throw if we are
   * passed a context for which we have no record.
   */
  @JvmField var isManualCallbacks: Boolean = false

  /** Should be used to disable pooling entirely for debugging, testing, and other use cases. */
  @JvmField var isPoolingDisabled: Boolean = false

  /**
   * Enables per-pool usage statistics tracking (acquire hits/misses, releases, discards, peak
   * size). When false (default), [DefaultContentPool] skips all stats bookkeeping so there is zero
   * overhead on the acquire/release hot path. Set to true by the observability system at init time.
   */
  @JvmField var isStatsTrackingEnabled: Boolean = false

  /** Can be used to return a custom Pool implementation for testing. */
  private val mountContentPoolFactory: ThreadLocal<Factory> = ThreadLocal<Factory>()

  private var hasMountContentPoolFactory = false

  @JvmStatic
  fun acquireMountContent(
      context: Context,
      poolableMountContent: ContentAllocator<*>,
      poolScope: PoolScope = PoolScope.None,
  ): Any {
    val contentFromPool =
        if (poolableMountContent.poolingPolicy.canAcquireContent) {
          val pool = getOrCreateMountContentPool(
              context = context,
              allocator = poolableMountContent,
              poolScope = poolScope,
          )
          pool?.acquire(poolableMountContent)?.let { content ->
            discardIfAttached(content, poolableMountContent)
          }
        } else {
          null
        }

    return if (contentFromPool != null) {
          contentFromPool
        } else {
          val isTracing = RenderCoreSystrace.isTracing()
          if (isTracing) {
            RenderCoreSystrace.beginSection(
                "MountContentPools:createMountContent ${poolableMountContent.poolKeyTypeName}",
            )
          }
          val newContent = poolableMountContent.createContent(context)
          if (isTracing) {
            RenderCoreSystrace.endSection()
          }

          newContent
        }
        .also { content ->
          if (content is View) {
            mountItemPoolsReleaseValidator?.registerAcquiredViewState(content)
          }
        }
  }

  /**
   * Something outside the pool can still hold a pooled View and give it a parent: an Android
   * `Visibility` transition re-parents Views it saw disappear into the scene root's overlay, even
   * after they were cleanly unmounted and released. Mounting such a View throws in `addView`, so
   * the pool drops it and the caller creates new content. The holder that attached it keeps owning
   * it, so `onContentDiscarded` is not called: it would release resources that holder is still
   * drawing.
   */
  private fun discardIfAttached(content: Any, allocator: ContentAllocator<*>): Any? {
    if (content !is View || content.parent == null) {
      return content
    }
    reportAttachedView(
        ATTACHED_VIEW_ACQUIRED_CATEGORY,
        "Acquired",
        content,
        allocator.poolKeyTypeName,
    )
    return null
  }

  @JvmStatic
  fun recycle(
      context: Context,
      poolableMountContent: ContentAllocator<*>,
      mountContent: Any,
      poolScope: PoolScope = PoolScope.None,
  ) {
    val isTracing = RenderCoreSystrace.isTracing()
    if (isTracing) {
      RenderCoreSystrace.beginSection(
          "MountContentPools:recycleToPool ${poolableMountContent.poolKeyTypeName}",
      )
    }
    try {
      if (RenderCoreConfig.removeComponentHostListeners) {
        if (mountContent is Host) {
          mountContent.removeViewListeners()
        }
      }
      if (RenderCoreConfig.resetHostTranslationOnRecycle && mountContent is Host) {
        mountContent.translationX = 0f
        mountContent.translationY = 0f
      }
      if (reportIfAttached(mountContent, poolableMountContent)) {
        // Owned by whoever attached it, so it is neither pooled nor discarded; see
        // discardIfAttached.
        return
      }

      val pool =
          if (poolableMountContent.poolingPolicy.canReleaseContent) {
            getOrCreateMountContentPool(
                context = context,
                allocator = poolableMountContent,
                poolScope = poolScope,
            )
          } else {
            null
          }
      if (pool != null && shouldHoldUntilDrawn(mountContent)) {
        ReleasedViewGroups.hold(context, poolableMountContent, mountContent, poolScope)
      } else {
        releaseToPool(pool, poolableMountContent, mountContent)
      }
    } finally {
      if (isTracing) {
        RenderCoreSystrace.endSection()
      }
    }
  }

  private fun reportIfAttached(content: Any, allocator: ContentAllocator<*>): Boolean {
    if (content !is View || content.parent == null) {
      return false
    }
    reportAttachedView(
        ATTACHED_VIEW_RELEASED_CATEGORY,
        "Released",
        content,
        allocator.poolKeyTypeName,
    )
    return true
  }

  private fun releaseToPool(
      pool: ContentPool?,
      poolableMountContent: ContentAllocator<*>,
      mountContent: Any,
  ) {
    if (pool == null) {
      // There is no pool, call onContentDiscarded to allow for releasing resources if needed.
      poolableMountContent.onContentDiscarded?.invoke(mountContent)
      return
    }

    if (mountItemPoolsReleaseValidator != null && mountContent is View) {
      mountItemPoolsReleaseValidator?.assertValidRelease(
          mountContent,
          listOf(poolableMountContent.poolKeyTypeName),
      )
    }

    val releasedToThePool = pool.release(mountContent)

    if (!releasedToThePool) {
      // Content was not released to the pool. Call onContentDiscarded to allow for releasing
      // resources if needed.
      poolableMountContent.onContentDiscarded?.invoke(mountContent)
    }
  }

  private fun shouldHoldUntilDrawn(mountContent: Any): Boolean =
      RenderCoreConfig.holdReleasedViewGroupsUntilDrawn &&
          Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
          mountContent is ViewGroup &&
          Looper.myLooper() == Looper.getMainLooper()

  /** Looks up the pool [recycle] released into, without creating one if it was cleared since. */
  private fun findMountContentPool(
      context: Context,
      allocator: ContentAllocator<*>,
      poolScope: PoolScope,
  ): ContentPool? {
    if (isPoolingDisabled) {
      return null
    }
    synchronized(mountContentLock) {
      val contextContentPools = mountContentPoolsByContext[context] ?: return null
      val pools =
          if (poolScope == PoolScope.None) {
            contextContentPools.unscopedPools
          } else {
            contextContentPools.scopedPools[poolScope]
          }
      return pools?.get(allocator.getPoolKey())
    }
  }

  /**
   * ViewGroups kept out of their pools until a frame has been drawn since their release; see
   * [RenderCoreConfig.holdReleasedViewGroupsUntilDrawn]. Main thread only.
   */
  private object ReleasedViewGroups : Choreographer.FrameCallback {

    private class Held(
        val context: Context,
        val allocator: ContentAllocator<*>,
        val content: Any,
        val poolScope: PoolScope,
    )

    /** Released since the last frame callback, which can run before that frame's traversal. */
    private var waiting = ArrayList<Held>()

    /** Released before the last frame callback, so that frame's traversal has drawn since. */
    private var drawn = ArrayList<Held>()

    private var isScheduled = false

    fun hold(context: Context, allocator: ContentAllocator<*>, content: Any, poolScope: PoolScope) {
      waiting.add(Held(context, allocator, content, poolScope))
      schedule()
    }

    override fun doFrame(frameTimeNanos: Long) {
      isScheduled = false
      val ready = drawn
      drawn = waiting
      waiting = ArrayList()
      for (held in ready) {
        // Re-parented during the hold, so it belongs to its new parent; see discardIfAttached.
        if (reportIfAttached(held.content, held.allocator)) {
          continue
        }
        releaseToPool(
            findMountContentPool(held.context, held.allocator, held.poolScope),
            held.allocator,
            held.content,
        )
      }
      if (drawn.isNotEmpty()) {
        schedule()
      }
    }

    fun clear() {
      if (isScheduled) {
        Choreographer.getInstance().removeFrameCallback(this)
        isScheduled = false
      }
      for (held in waiting + drawn) {
        held.allocator.onContentDiscarded?.invoke(held.content)
      }
      waiting.clear()
      drawn.clear()
    }

    private fun schedule() {
      if (!isScheduled) {
        isScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
      }
    }
  }

  /**
   * A pooled View that still has a parent crashes the next mount that acquires it, in
   * `ViewGroup.addView` ("The specified child already has a parent"), far from the code that
   * attached it. Reporting here captures the stack of the release or acquire instead.
   */
  private fun reportAttachedView(category: String, action: String, view: View, poolKey: String) {
    val message =
        "$action a View from the mount content pool while it is still attached: " +
            "content=${view.javaClass.name}, pool=$poolKey, " +
            "attachedToWindow=${view.isAttachedToWindow}, parents=${describeParents(view)}"
    ErrorReporter.report(LogLevel.ERROR, category, message, IllegalStateException(message))
  }

  private fun describeParents(view: View): String {
    val parents = ArrayList<String>(MAX_REPORTED_PARENTS)
    var parent = view.parent
    while (parent != null && parents.size < MAX_REPORTED_PARENTS) {
      parents.add(parent.javaClass.name)
      parent = parent.parent
    }
    return parents.joinToString(" > ")
  }

  @JvmStatic
  fun getContentPoolItemCount(
      context: Context,
      allocator: ContentAllocator<*>,
      poolScope: PoolScope = PoolScope.None,
  ): Int {
    if (isPoolingDisabled) {
      return 0
    }

    return synchronized(mountContentLock) {
      if (poolScope.isReleased()) {
        return@synchronized 0
      }
      val contextContentPools = mountContentPoolsByContext[context]
      val scopedPools = contextContentPools?.getPools(poolScope)
      val pool = scopedPools?.get(allocator.getPoolKey())
      pool?.pooledItemsCount ?: 0
    }
  }

  @JvmStatic
  fun maybePreallocateContent(
      context: Context,
      poolableMountContent: ContentAllocator<*>,
      poolScope: PoolScope = PoolScope.None,
  ): Boolean {
    val isTracing = RenderCoreSystrace.isTracing()
    if (isTracing) {
      RenderCoreSystrace.beginSection(
          "MountContentPools:maybePreallocateContent ${poolableMountContent.poolKeyTypeName}",
      )
    }
    try {
      val pool = getOrCreateMountContentPool(
          context = context,
          allocator = poolableMountContent,
          poolScope = poolScope,
      )
      return pool?.maybePreallocateContent(context, poolableMountContent) ?: false
    } finally {
      if (isTracing) {
        RenderCoreSystrace.endSection()
      }
    }
  }

  /**
   * Fills up a mount content pool for the specified [poolableMountContent] allocator. If a pool
   * doesn't exist for a [ContentAllocator], a default one will be created with the specified size.
   * The specified [poolSize] will only be respected if the [poolableMountContent] does not provide
   * a custom Pool implementation.
   */
  @JvmStatic
  fun prefillMountContentPool(
      context: Context,
      poolSize: Int,
      poolableMountContent: ContentAllocator<*>,
      poolScope: PoolScope = PoolScope.None,
  ) {
    if (poolSize == 0) {
      return
    }

    val isTracing = RenderCoreSystrace.isTracing()
    if (isTracing) {
      RenderCoreSystrace.beginSection(
          "MountContentPools:prefillMountContentPool ${poolableMountContent.poolKeyTypeName}",
      )
    }
    try {
      val pool = getOrCreateMountContentPool(
          context = context,
          allocator = poolableMountContent,
          poolScope = poolScope,
          poolSize = poolSize,
      )

      if (pool != null) {
        for (i in 0 until poolSize) {
          if (!pool.release(poolableMountContent.createContent(context))) {
            break
          }
        }
      }
    } finally {
      if (isTracing) {
        RenderCoreSystrace.endSection()
      }
    }
  }

  /**
   * Retrieves a recycling pool for the given [ContentAllocator] if it exists. Else it will default
   * to creating a new pool with the [allocator]'s pool size if no size is specified
   */
  private fun getOrCreateMountContentPool(
      context: Context,
      allocator: ContentAllocator<*>,
      poolScope: PoolScope,
      poolSize: Int = allocator.poolSize(),
  ): ContentPool? {
    if (isPoolingDisabled || poolSize <= 0) {
      return null
    }

    synchronized(mountContentLock) {
      if (poolScope.isReleased()) {
        return null
      }
      var contextContentPools = mountContentPoolsByContext[context]
      if (contextContentPools == null) {
        val rootContext = getRootContext(context)
        if (destroyedRootContexts.containsKey(rootContext)) {
          return null
        }
        ensureLifecycleCallbacks(rootContext)
        contextContentPools = ContextContentPools()
        mountContentPoolsByContext[context] = contextContentPools
      }
      val scopedPools = contextContentPools.getPools(poolScope)

      val poolKey = allocator.getPoolKey()
      var pool = scopedPools[poolKey]

      if (pool == null && hasMountContentPoolFactory) {
        pool = mountContentPoolFactory.get()?.createMountContentPool()
      }

      if (pool == null) {
        pool = allocator.onCreateMountContentPool(poolSize) ?: DefaultContentPool(poolKey, poolSize)
        pool.setOnClearedListener(allocator.onContentDiscarded)
      }

      scopedPools[poolKey] = pool
      return pool
    }
  }

  @JvmStatic
  @VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE)
  fun clear() {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      ReleasedViewGroups.clear()
    }
    synchronized(mountContentLock) {
      // Clear pools and dispatch OnClearedListeners before clearing the maps
      mountContentPoolsByContext.values.forEach { contextContentPool -> contextContentPool.clear() }

      mountContentPoolsByContext.clear()
      destroyedRootContexts.clear()
      contextsWithLifecycleObservers.clear()
    }
  }

  @JvmStatic
  fun releaseScope(poolScope: PoolScope) {
    synchronized(mountContentLock) {
      mountContentPoolsByContext.values.forEach { it.releaseScope(poolScope) }
    }
  }

  @JvmStatic
  @VisibleForTesting
  fun setMountContentPoolFactory(factory: Factory?) {
    mountContentPoolFactory.set(factory)
    hasMountContentPoolFactory = factory != null
  }

  /**
   * @return the "most base" Context of this Context, i.e. the Activity, Application, or Service
   *   backing this Context and all its ContextWrappers. In some cases, e.g. instrumentation tests
   *   or other places we don't wrap a standard Context, this root Context may instead be a raw
   *   ContextImpl.
   */
  private fun getRootContext(context: Context): Context {
    var currentContext = context
    while (
        currentContext is ContextWrapper &&
            currentContext !is Activity &&
            currentContext !is Application &&
            currentContext !is Service
    ) {
      currentContext = currentContext.baseContext
    }
    return currentContext
  }

  private fun ensureLifecycleCallbacks(context: Context) {
    if (!isManualCallbacks) {
      if (context is LifecycleOwner) {
        synchronized(mountContentLock) {
          if (!contextsWithLifecycleObservers.containsKey(context)) {
            contextsWithLifecycleObservers[context] = true
            runOnUiThread {
              context.lifecycle.addObserver(PoolsLifecycleObserver(context.lifecycle.currentState))
            }
          }
        }
      } else if (activityCallbacks == null) {
        activityCallbacks = PoolsActivityCallback()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            activityCallbacks,
        )
      }
    }
  }

  @JvmStatic
  fun onContextCreated(context: Context) {
    synchronized(mountContentLock) {
      check(!mountContentPoolsByContext.containsKey(context)) {
        "The MountContentPools has a reference to an activity that has just been created"
      }
    }
  }

  @JvmStatic
  fun onContextDestroyed(context: Context) {
    synchronized(mountContentLock) {
      clearMatchingContexts(context)
      destroyedRootContexts.put(getRootContext(context), true)
    }
  }

  /**
   * Evicts ONLY the pool entry keyed by exactly [context] (identity match).
   *
   * For display contexts RenderCore cannot observe (dialogs, presentations): unlike
   * [onContextDestroyed] it neither sweeps sibling wrapper keys nor marks the host root destroyed,
   * so the host Activity keeps pooling.
   */
  @JvmStatic
  fun onNonActivityContextDestroyed(context: Context) {
    synchronized(mountContentLock) {
      mountContentPoolsByContext.remove(context)?.clear()
    }
  }

  @GuardedBy("mountContentLock")
  private fun clearMatchingContexts(
      context: Context,
  ) {
    val removedContextContentPool = mountContentPoolsByContext.remove(context)
    removedContextContentPool?.clear()

    // Clear any context wrappers holding a reference to this activity.
    val iterator: MutableIterator<Map.Entry<Context, ContextContentPools>> =
        mountContentPoolsByContext.entries.iterator()
    while (iterator.hasNext()) {
      val (contextKey, contextContentPool) = iterator.next()
      if (isContextWrapper(contextKey, context)) {
        iterator.remove()
        contextContentPool.clear()
      }
    }
  }

  /**
   * Returns a snapshot of pool statistics for all active pools. Useful for observability and
   * detecting pool sizing issues.
   */
  @JvmStatic
  fun getAllPoolStats(): List<PoolStats> {
    synchronized(mountContentLock) {
      val result = mutableListOf<PoolStats>()
      for (contextPools in mountContentPoolsByContext.values) {
        for (pool in contextPools.unscopedPools.values) {
          pool.getPoolStats()?.let { result.add(it) }
        }
        for (scopedMap in contextPools.scopedPools.values) {
          for (pool in scopedMap.values) {
            pool.getPoolStats()?.let { result.add(it) }
          }
        }
      }
      return result
    }
  }

  @get:VisibleForTesting
  val mountContentPools: List<ContentPool>
    get() {
      val result: MutableList<ContentPool> = ArrayList()
      for (itemPools in mountContentPoolsByContext.values) {
        itemPools.scopedPools.values.flatMap { it.values }.forEach { pool -> result.add(pool) }
        itemPools.unscopedPools.values.forEach { pool -> result.add(pool) }
      }
      return result
    }

  /** Check whether contextWrapper is a wrapper of baseContext */
  private fun isContextWrapper(contextWrapper: Context, baseContext: Context): Boolean {
    var baseCtx = baseContext
    while (baseCtx is ContextWrapper) {
      baseCtx = baseCtx.baseContext
    }
    var currentContext = contextWrapper
    while (currentContext is ContextWrapper) {
      currentContext = currentContext.baseContext
    }
    return currentContext === baseCtx
  }

  private val ContentAllocator<*>.poolKeyTypeName: String
    get() {
      val poolKey: Any = getPoolKey()
      return (poolKey as? Class<*>)?.simpleName ?: poolKey.toString()
    }

  /** Empty implementation of the [Application.ActivityLifecycleCallbacks] interface */
  private class PoolsActivityCallback : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
      onContextCreated(activity)
    }

    override fun onActivityStarted(activity: Activity) {
      // Do nothing.
    }

    override fun onActivityResumed(activity: Activity) {
      // Do nothing.
    }

    override fun onActivityPaused(activity: Activity) {
      // Do nothing.
    }

    override fun onActivityStopped(activity: Activity) {
      // Do nothing.
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
      // Do nothing.
    }

    override fun onActivityDestroyed(activity: Activity) {
      onContextDestroyed(activity)
    }
  }

  private class PoolsLifecycleObserver(private val initialState: Lifecycle.State) :
      DefaultLifecycleObserver {
    override fun onCreate(owner: LifecycleOwner) {
      // When the lifecycle observer is attached to an owner that has already been created, the
      // onCreate method will be invoked but we want to call onContextCreated only when the owner
      // switches to created for the first time.
      if (!initialState.isAtLeast(Lifecycle.State.CREATED)) {
        // we know owner is a Context because we checked this when adding the lifecycle observer
        onContextCreated(owner as Context)
      }
    }

    override fun onDestroy(owner: LifecycleOwner) {
      // we know owner is a Context because we checked this when adding the lifecycle observer
      val context = owner as Context
      onContextDestroyed(context)
      owner.lifecycle.removeObserver(this)
      synchronized(mountContentLock) { contextsWithLifecycleObservers.remove(context) }
    }
  }

  /**
   * Enables the validation step which verifies if the content is released with listeners to the
   * pool. This should only be used for debugging and logging purposes.
   */
  fun enableItemsReleaseValidation(
      failOnDetection: Boolean,
      excludedPatterns: Set<Regex> = emptySet(),
      extraFields: List<MountItemPoolsReleaseValidator.FieldExtractionDefinition> = emptyList(),
      onInvalidRelease: (exception: InvalidReleaseToMountPoolException) -> Unit,
  ) {
    mountItemPoolsReleaseValidator =
        MountItemPoolsReleaseValidator(
            failOnDetection = failOnDetection,
            excludedPatterns = excludedPatterns,
            onInvalidRelease = onInvalidRelease,
            extraFields = extraFields,
        )
  }

  /** Content pool that RenderCore uses to recycle content (such as Views) */
  interface ContentPool {

    /** The number of items currently in the pool. */
    val pooledItemsCount: Int

    /** Interface definition for a callback to be invoked when the pool is cleared. */
    fun interface OnClearedListener {
      /**
       * Called when the pool is cleared.
       *
       * @param item The content that has been removed from the pool when pool has been cleared.
       */
      fun onCleared(item: Any)
    }

    /**
     * Acquire a pooled content item from the pool
     *
     * @param contentAllocator the content allocator used.
     * @return a pooled content item
     */
    fun acquire(contentAllocator: ContentAllocator<*>): Any?

    /**
     * Called when an item is released and can return to the pool
     *
     * @param item the item to release to the pool
     * @return `true` if the {@param item} is released to the pool.
     */
    fun release(item: Any): Boolean

    /**
     * This method can be called to allocate mount content to a Pool ahead of time.
     *
     * @param c the android context
     * @param contentAllocator the content allocator used.
     * @return `` true if the mount content created by {@param contentAllocator} is released into
     *   the pool.
     */
    fun maybePreallocateContent(c: Context, contentAllocator: ContentAllocator<*>): Boolean

    /**
     * Register a callback to be invoked when this pool is cleared.
     *
     * @param listener The callback that will run
     */
    fun setOnClearedListener(listener: OnClearedListener?): Unit = Unit

    /** Clears the pool and dispatches [OnClearedListener] if present. */
    fun clear(): Unit = Unit

    /** Returns a snapshot of pool statistics for observability, or null if not tracked. */
    fun getPoolStats(): PoolStats? = null
  }

  /**
   * Snapshot of pool usage statistics for observability.
   *
   * These stats enable detection of pool sizing issues:
   * - Cold-start: [totalHits] == 0 on first mount pass
   * - Steady-state misses: high [totalMisses] / ([totalHits] + [totalMisses]) ratio
   * - Oversized: [peakSize] << [maxSize] (pool larger than needed)
   * - Undersized: [totalDiscards] > 0 (content discarded because pool was full)
   */
  @DataClassGenerate
  data class PoolStats(
      /** Debug name for this pool (typically the component class name). */
      val name: String,
      /**
       * Unique identifier for this pool instance. Distinguishes pools that share the same [name]
       * but exist in different contexts or scopes (e.g., multiple Activities each have their own
       * ViewAllocator pool).
       */
      val id: Int,
      /** Maximum capacity of the pool. */
      val maxSize: Int,
      /** Current number of items in the pool. */
      val currentSize: Int,
      /** Highest number of items the pool has held simultaneously. */
      val peakSize: Int,
      /** Total number of acquire() calls. */
      val totalAcquires: Int,
      /** Number of acquire() calls that returned content (pool hit). */
      val totalHits: Int,
      /** Number of acquire() calls that returned null (pool miss). */
      val totalMisses: Int,
      /** Total number of release() calls. */
      val totalReleases: Int,
      /** Number of release() calls where the pool was full (content discarded). */
      val totalDiscards: Int,
  )

  open class DefaultContentPool(private val poolKey: Any, private val maxPoolSize: Int) :
      ContentPool {

    private val pool: Pools.SynchronizedPool<Any> = Pools.SynchronizedPool(maxPoolSize)

    private val debugIdentifier: String = (poolKey as? Class<*>)?.name ?: poolKey.toString()

    private val currentPoolSize: AtomicInteger = AtomicInteger(0)

    private var onClearedListener: ContentPool.OnClearedListener? = null

    // Pool usage stats for observability
    private val _totalAcquires = AtomicInteger(0)
    private val _totalHits = AtomicInteger(0)
    private val _totalMisses = AtomicInteger(0)
    private val _totalReleases = AtomicInteger(0)
    private val _totalDiscards = AtomicInteger(0)
    private val _peakSize = AtomicInteger(0)

    override val pooledItemsCount: Int
      get() = currentPoolSize.get()

    override fun acquire(contentAllocator: ContentAllocator<*>): Any? {
      val isTracing = RenderCoreSystrace.isTracing()
      if (isTracing) {
        val poolKey = contentAllocator.getPoolKey()
        RenderCoreSystrace.beginSection(
            "MountContentPools:acquireFromPool ${(poolKey as? Class<*>)?.simpleName ?: poolKey}",
        )
      }
      try {
        val tracking = isStatsTrackingEnabled
        if (tracking) {
          _totalAcquires.incrementAndGet()
        }
        val content = pool.acquire()
        if (content != null) {
          currentPoolSize.decrementAndGet()
          if (tracking) {
            _totalHits.incrementAndGet()
          }
        } else {
          if (tracking) {
            _totalMisses.incrementAndGet()
          }
        }
        return content
      } finally {
        if (isTracing) {
          RenderCoreSystrace.endSection()
        }
      }
    }

    override fun release(item: Any): Boolean {
      val isTracing = RenderCoreSystrace.isTracing()
      if (isTracing) {
        RenderCoreSystrace.beginSection(
            "MountContentPools:releaseToPool ${(poolKey as? Class<*>)?.simpleName ?: poolKey}",
        )
      }
      try {
        val tracking = isStatsTrackingEnabled
        if (tracking) {
          _totalReleases.incrementAndGet()
        }
        return try {
          val releasedIntoPool = pool.release(item)
          if (releasedIntoPool) {
            val newSize = currentPoolSize.incrementAndGet()
            if (tracking) {
              // Update peak size atomically
              var peak = _peakSize.get()
              while (newSize > peak) {
                if (_peakSize.compareAndSet(peak, newSize)) {
                  break
                }
                peak = _peakSize.get()
              }
            }
          } else {
            if (tracking) {
              _totalDiscards.incrementAndGet()
            }
          }
          releasedIntoPool
        } catch (e: IllegalStateException) {
          val metadata = "Failed to release item to DefaultContentPool: $debugIdentifier"
          throw IllegalStateException(metadata, e)
        }
      } finally {
        if (isTracing) {
          RenderCoreSystrace.endSection()
        }
      }
    }

    override fun getPoolStats(): PoolStats? {
      if (!isStatsTrackingEnabled) {
        return null
      }
      return PoolStats(
          name = debugIdentifier,
          id = System.identityHashCode(this),
          maxSize = maxPoolSize,
          currentSize = currentPoolSize.get(),
          peakSize = _peakSize.get(),
          totalAcquires = _totalAcquires.get(),
          totalHits = _totalHits.get(),
          totalMisses = _totalMisses.get(),
          totalReleases = _totalReleases.get(),
          totalDiscards = _totalDiscards.get(),
      )
    }

    override fun maybePreallocateContent(
        c: Context,
        contentAllocator: ContentAllocator<*>,
    ): Boolean {
      val isTracing = RenderCoreSystrace.isTracing()
      if (isTracing) {
        val poolKey = contentAllocator.getPoolKey()
        RenderCoreSystrace.beginSection(
            "MountContentPools:preallocateToPool ${(poolKey as? Class<*>)?.simpleName ?: poolKey}",
        )
      }
      try {
        if (RenderCoreConfig.disableDefaultPoolPreallocation) {
          return false
        }
        return if (currentPoolSize.get() < maxPoolSize) {
          release(contentAllocator.createContent(c))
        } else false
      } finally {
        if (isTracing) {
          RenderCoreSystrace.endSection()
        }
      }
    }

    override fun setOnClearedListener(listener: ContentPool.OnClearedListener?) {
      onClearedListener = listener
    }

    override fun clear() {
      // clear is currently used only in order to invoke the onClearedListener
      // so if the listener isn't present, there is no need to do the additional work
      if (onClearedListener == null || currentPoolSize.get() == 0) {
        return
      }

      do {
        val content = pool.acquire()
        if (content != null) {
          currentPoolSize.decrementAndGet()
          onClearedListener?.onCleared(content)
        }
      } while (content != null)
    }
  }
}

/**
 * Represents a scope of a custom scoped pool.
 *
 * Use [LifecycleAware] if you want the scope to be automatically released when the [Lifecyle]
 * transitions to destroyed state or [ManuallyManaged] if you want to manually control when the
 * scope is released.
 */
sealed interface PoolScope {

  /** An object representing none scope. Used as a default [PoolScope] value. */
  object None : PoolScope

  /**
   * A pool scope that automatically releases the pool when the given [lifecycle] transitions to
   * destroyed state
   */
  class LifecycleAware(internal val lifecycle: Lifecycle) : PoolScope {
    init {
      lifecycle.addObserver(
          object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
              lifecycle.removeObserver(this)
              MountContentPools.releaseScope(this@LifecycleAware)
            }
          },
      )
    }
  }

  /** A pool scope that needs to be manually released by calling [releaseScope] method on it. */
  class ManuallyManaged : PoolScope {

    /** Clears the pool associated with this [PoolScope]. */
    fun releaseScope() {
      MountContentPools.releaseScope(this)
    }
  }
}

/**
 * A class that holds content pools.
 *
 * [scopedPools] contains pools scoped to [PoolScope] [unscopedPools] contains pools scoped to the
 * [Context]
 */
private class ContextContentPools {

  val scopedPools: MutableMap<PoolScope, MutableMap<Any, MountContentPools.ContentPool>> = HashMap()

  val unscopedPools: MutableMap<Any, MountContentPools.ContentPool> = HashMap()

  /** Returns pools associated with [poolScope] or [unscopedPools] if [poolScope] is null. */
  fun getPools(
      poolScope: PoolScope = PoolScope.None,
  ): MutableMap<Any, MountContentPools.ContentPool> {
    if (poolScope == PoolScope.None) {
      return unscopedPools
    }
    return scopedPools.getOrPut(poolScope) { HashMap() }
  }

  /**
   * Clears the pool associated with [poolScope] and dispatches
   * [MountContentPools.ContentPool.OnClearedListener]s if present.
   */
  fun releaseScope(poolScope: PoolScope) {
    val removedPool = scopedPools.remove(poolScope)
    removedPool?.values?.forEach { contentPool -> clearContentPool(contentPool) }
  }

  /**
   * Clears all pools and dispatches [MountContentPools.ContentPool.OnClearedListener]s if present.
   */
  fun clear() {
    scopedPools.values
        .flatMap { poolKeyToContentPoolMap -> poolKeyToContentPoolMap.values }
        .forEach { contentPool -> clearContentPool(contentPool) }
    scopedPools.clear()

    unscopedPools.values.forEach { contentPool -> clearContentPool(contentPool) }
    unscopedPools.clear()
  }

  private fun clearContentPool(contentPool: MountContentPools.ContentPool) {
    contentPool.clear()
    contentPool.setOnClearedListener(null)
  }
}

/**
 * A [PoolScope.LifecycleAware] scope whose lifecycle is destroyed gets no pool, so content its
 * surface recycles late is discarded instead of kept where nothing will release it.
 */
private fun PoolScope.isReleased(): Boolean =
    this is PoolScope.LifecycleAware && lifecycle.currentState == Lifecycle.State.DESTROYED
