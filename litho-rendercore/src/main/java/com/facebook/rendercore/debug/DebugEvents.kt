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

package com.facebook.rendercore.debug

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.debug.DebugEvent.Companion.All
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Base class of all debug events */
sealed class DebugEvent(
    val type: String,
    val renderStateId: String,
    val threadName: String = Thread.currentThread().name,
    val logLevel: LogLevel = LogLevel.DEBUG,
    val attributes: Map<String, Any?> = emptyMap(),
) {

  companion object {
    val NoId = { "" }
    const val All = "*"
    const val RenderTreeCommitted = "RenderCore.RenderTreeCommitted"
    const val RenderTreeCalculated = "RenderCore.RenderTreeCalculated"
    const val RenderTreeMounted = "RenderCore.RenderTreeMounted"
    const val MountItemMount = "RenderCore.MountItem.Mount"
    const val RenderUnitMounted = "RenderCore.RenderUnit.Mounted"
    const val RenderUnitUnmounted = "RenderCore.RenderUnit.Unmounted"
    const val RenderUnitUpdated = "RenderCore.RenderUnit.Updated"
    const val RenderUnitOnVisible = "RenderCore.RenderUnit.OnVisible"
    const val RenderUnitOnInvisible = "RenderCore.RenderUnit.OnInvisible"
    const val RenderUnitOnFocused = "RenderCore.RenderUnit.OnFocused"
    const val RenderUnitOnUnfocused = "RenderCore.RenderUnit.OnUnfocused"
    const val RenderUnitOnFullImpression = "RenderCore.RenderUnit.OnFullImpression"
    const val RenderUnitOnVisibilityChanged = "RenderCore.RenderUnit.OnVisibilityChanged"
    const val ViewOnLayout = "RenderCore.View.OnLayout"
    const val IncrementalMountStart = "RenderCore.IncrementalMount.Start"
    const val IncrementalMountEnd = "RenderCore.IncrementalMount.End"
    const val RenderTreeMountStart = "RenderCore.RenderTreeMount.Start"
    const val RenderTreeMountEnd = "RenderCore.RenderTreeMount.End"
  }

  /** Returns the value of attribute with [name]. */
  @Suppress("UNCHECKED_CAST") fun <T> attribute(name: String): T = attributes[name] as T

  /** Returns the value of attribute with [name] or `null` if not present. */
  @Suppress("UNCHECKED_CAST") fun <T> attributeOrNull(name: String): T? = attributes[name] as? T

  /**
   * Returns the value of the attribute with [name]. If there is no value present, then it will
   * return the [default].
   */
  fun <T> attribute(name: String, default: T): T = attributeOrNull(name) ?: default

  override fun toString(): String {
    return """
      |[DebugEvent]
      |  type = '$type',
      |  renderStateId = '$renderStateId',
      |  thread = '$threadName',
      |  attributes = ${
      attributes.entries.joinToString(
          prefix = "{\n",
          separator = ",\n",
          postfix = "\n|  }",
          transform = { e -> "|    ${e.key} = ${e.value}" },
      )
    }
    """
        .trimMargin()
  }
}

/** Collection of attributes */
object DebugEventAttribute {
  const val Id = "id"
  const val Timestamp = "timestamp"
  const val Duration = "duration"
  const val Version = "version"
  const val Width = "width"
  const val Height = "height"
  const val WidthSpec = "widthSpec"
  const val HeightSpec = "heightSpec"
  const val SizeConstraints = "sizeConstraints"
  const val Source = "source"
  const val Async = "async"
  const val RenderUnitId = "renderUnitId"
  const val Description = "description"
  const val HashCode = "hashCode"
  const val Bounds = "bounds"
  const val RootHostHashCode = "rootHostHashCode"
  const val Name = "name"
  const val Key = "key"
  const val VisibleRect = "visibleRect"
  const val BoundsVisible = "areBoundsVisible"
  const val NumMountableOutputs = "numMountableOutputs"
  const val NumItemsMounted = "numItemsMounted"
  const val NumItemsUnmounted = "numItemsUnmounted"
  const val WasInterrupted = "wasInterrupted"
  const val ThreadPriority = "threadPriority"
  const val CallbackType = "callbackType"
  const val ComponentClassName = "componentClassName"
  const val IsGapWorkerPremount = "isGapWorkerPremount"
}

/** Base class for marker events */
class DebugMarkerEvent(
    val timestamp: Long = System.currentTimeMillis(), // for calendar time
    type: String,
    renderStateId: String,
    threadName: String = Thread.currentThread().name,
    logLevel: LogLevel = LogLevel.DEBUG,
    attributes: Map<String, Any?> = emptyMap(),
) :
    DebugEvent(
        type = type,
        renderStateId = renderStateId,
        threadName = threadName,
        logLevel = logLevel,
        attributes =
            buildMap {
              put(DebugEventAttribute.Timestamp, timestamp)
              putAll(attributes)
            },
    )

/** Base class for process events */
class DebugProcessEvent(
    val timestamp: Long = System.currentTimeMillis(), // for calendar time
    val durationNs: Duration,
    type: String,
    renderStateId: String,
    threadName: String = Thread.currentThread().name,
    logLevel: LogLevel = LogLevel.DEBUG,
    attributes: Map<String, Any?> = emptyMap(),
) :
    DebugEvent(
        type = type,
        renderStateId = renderStateId,
        threadName = threadName,
        logLevel = logLevel,
        attributes =
            buildMap {
              put(DebugEventAttribute.Timestamp, timestamp)
              put(DebugEventAttribute.Duration, durationNs)
              putAll(attributes)
            },
    )

/** Base class for event subscribers */
abstract class DebugEventSubscriber(vararg val events: String) {
  abstract fun onEvent(event: DebugEvent)

  /**
   * Whether this subscriber takes the next event of [type], one of its [events], at [logLevel].
   *
   * The dispatcher asks before it builds the event, and builds it only if a subscriber takes it, so
   * a subscriber that samples events should sample here rather than in [onEvent]: the events it
   * skips then cost nothing to make. Trace events are asked about at [LogLevel.DEBUG], once per
   * trace. A trace recorded with `beginTrace` and `endTrace` is asked about in `endTrace`, after
   * the attributes passed to `beginTrace` were built.
   */
  open fun acceptsEvent(type: String, logLevel: LogLevel): Boolean = true
}

/**
 * Any [DebugEventSubscriber] that implements this interface will receive callbacks just before and
 * right after a trace event. The subscriber can return a token from [onTraceStart] and the
 * dispatcher will return the token in [onTraceEnd].
 */
interface TraceListener<T : Any?> {

  // Called right before trace event starts
  fun onTraceStart(
      type: String,
      timestamp: Long,
      renderStateId: String,
      attributes: Map<String, Any?>,
  ): T

  // Called after the trace event ends
  fun onTraceEnd(payload: T, event: DebugProcessEvent)
}

/** Object to dispatch debug events */
object DebugEventDispatcher {

  private val minLogLevelRef = AtomicReference(LogLevel.DEBUG)

  @JvmStatic
  var minLogLevel: LogLevel
    @JvmStatic get() = minLogLevelRef.get()
    @JvmStatic
    set(value) {
      minLogLevelRef.set(value)
    }

  private val mutableSubscribers: MutableSet<DebugEventSubscriber> = CopyOnWriteArraySet()

  val subscribers: Set<DebugEventSubscriber>
    get() = mutableSubscribers

  @JvmStatic
  inline fun dispatch(
      type: String,
      renderStateId: () -> String,
      timestamp: Long = System.currentTimeMillis(), // for calendar time
      logLevel: LogLevel = LogLevel.DEBUG,
      attributesAccumulator: (MutableMap<String, Any?>) -> Unit = {},
  ) {
    if (logLevel < minLogLevel || subscribers.isEmpty()) {
      return
    }

    val subscribersToNotify = subscribers.filterLikelyEmpty { subscriber ->
      (subscriber.events.contains(type) || subscriber.events.contains(DebugEvent.All)) &&
          subscriber.acceptsEvent(type, logLevel)
    }
    if (subscribersToNotify.isNotEmpty()) {
      val attributes = LinkedHashMap<String, Any?>()
      attributesAccumulator(attributes)

      val event = DebugMarkerEvent(
          type = type,
          renderStateId = renderStateId(),
          attributes = attributes,
          timestamp = timestamp,
          logLevel = logLevel,
      )
      subscribersToNotify.forEach { subscriber -> subscriber.onEvent(event) }
    }
  }

  @JvmStatic
  inline fun dispatch(
      type: String,
      renderStateId: () -> String,
      attributesAccumulator: (MutableMap<String, Any?>) -> Unit = {},
  ) {
    dispatch(
        type = type,
        renderStateId = renderStateId,
        timestamp = System.currentTimeMillis(), // for calendar time
        attributesAccumulator = attributesAccumulator,
    )
  }

  @JvmStatic
  inline fun dispatch(
      type: String,
      renderStateId: () -> String,
      logLevel: LogLevel = LogLevel.DEBUG,
      attributesAccumulator: (MutableMap<String, Any?>) -> Unit = {},
  ) {
    dispatch(
        type = type,
        renderStateId = renderStateId,
        timestamp = System.currentTimeMillis(), // for calendar time
        logLevel = logLevel,
        attributesAccumulator = attributesAccumulator,
    )
  }

  private val lastTraceIdentifier = AtomicInteger(0)
  private val traceIdsToEvents: MutableMap<Int, EventData> = HashMap()

  /**
   * Returns an identifier for a trace for the given [type]. This identifier will always be unique
   * across a session, and if `null` it means this event is not to be traced.
   *
   * This is to be used whenever the [DebugEvent] API is called from Java.
   */
  @JvmStatic
  fun generateTraceIdentifier(type: String): Int? =
      if (subscribers.isNotEmpty() && subscribers.any { type in it.events || All in it.events }) {
        lastTraceIdentifier.getAndIncrement()
      } else {
        null
      }

  @JvmStatic
  @JvmOverloads
  fun beginTrace(
      traceIdentifier: Int,
      type: String,
      renderStateId: String,
      attributes: (MutableMap<String, Any?>) -> Unit = {},
  ) {
    val attrs = LinkedHashMap<String, Any?>()
    attributes.invoke(attrs)
    beginTrace(
        traceIdentifier = traceIdentifier,
        type = type,
        renderStateId = renderStateId,
        attributes = attrs,
    )
  }

  /**
   * Starts recording a trace block for the given [type].
   *
   * This is intended to be used with Java clients due to performance reasons. The recommended usage
   * is the following:
   * ```
   * Integer traceIdentifier = DebugEvents.getTraceIdentifier(DebugEvents.MyEvent);
   * if(traceIdentifier != null) {
   *    DebugEvents.beginTrace(traceIdentifier, ...);
   * }
   *
   * // code for the process
   *
   * if(traceIdentifier != null) {
   *   DebugEvents.endTrace(traceIdentifier);
   * }
   * ```
   */
  @JvmStatic
  fun beginTrace(
      traceIdentifier: Int,
      type: String,
      renderStateId: String,
      attributes: Map<String, Any?>,
  ) {
    traceIdsToEvents[traceIdentifier] =
        EventData(
            type = type,
            renderStateId = renderStateId,
            attributes = attributes,
        )
  }

  @JvmStatic
  @JvmOverloads
  fun endTrace(
      traceIdentifier: Int,
      attributes: Map<String, Any?> = emptyMap(),
  ) {
    val last = traceIdsToEvents.remove(traceIdentifier) ?: return
    val type = last.type

    if (subscribers.isEmpty()) {
      return
    }

    // find the subscribers listening for this event
    val subscribersToNotify = subscribers.filterLikelyEmpty { subscriber ->
      (subscriber.events.contains(type) || subscriber.events.contains(DebugEvent.All)) &&
          subscriber.acceptsEvent(type, LogLevel.DEBUG)
    }

    if (subscribersToNotify.isEmpty()) {
      return
    }

    val endTime = System.nanoTime()

    val event = DebugProcessEvent(
        type = type,
        timestamp = last.timestamp, // for calendar time
        renderStateId = last.renderStateId,
        durationNs = Duration(value = endTime - last.startTime),
        attributes = last.attributes + attributes,
    )

    subscribersToNotify.forEach { it.onEvent(event) }
  }

  inline fun <T> trace(
      type: String,
      renderStateId: () -> String,
      attributesAccumulator: (MutableMap<String, Any?>) -> Unit = {},
      block: (TraceScope?) -> T,
  ): T {

    if (subscribers.isEmpty()) {
      return block(null)
    }

    // find the subscribers listening for this event
    val subscribersToNotify = subscribers.filterLikelyEmpty { subscriber ->
      (subscriber.events.contains(type) || subscriber.events.contains(All)) &&
          subscriber.acceptsEvent(type, LogLevel.DEBUG)
    }

    // run the block if there are no subscribers
    if (subscribersToNotify.isEmpty()) {
      return block(null)
    }

    @Suppress("UNCHECKED_CAST")
    val traceListeners =
        subscribersToNotify.filterLikelyEmpty { it is TraceListener<*> }
            as List<TraceListener<Any?>>

    val attributes = LinkedHashMap<String, Any?>()
    attributesAccumulator(attributes)

    val renderStateId = renderStateId()
    val timestamp = System.currentTimeMillis()
    val startTime = System.nanoTime()
    val payloads: List<Any?> = traceListeners.map {
      it.onTraceStart(type, timestamp, renderStateId, attributes)
    }
    val res = block(TraceScope(attributes = attributes))
    val endTime = System.nanoTime()

    val event = DebugProcessEvent(
        type = type,
        timestamp = timestamp,
        renderStateId = renderStateId,
        durationNs = Duration(value = endTime - startTime),
        attributes = attributes,
    )

    traceListeners.forEachIndexed { i, it -> it.onTraceEnd(payload = payloads[i], event = event) }
    subscribersToNotify.forEach { it.onEvent(event) }

    return res
  }

  @JvmStatic
  fun subscribe(subscriber: DebugEventSubscriber) {
    mutableSubscribers.add(subscriber)
  }

  internal fun unsubscribe(subscriber: DebugEventSubscriber) {
    mutableSubscribers.remove(subscriber)
  }

  fun unsubscribeAll() {
    mutableSubscribers.clear()
  }

  class TraceScope(private val attributes: LinkedHashMap<String, Any?>) {
    fun attribute(name: String, value: Any?) {
      attributes[name] = value
    }
  }

  @DataClassGenerate
  private data class EventData(
      val type: String,
      val renderStateId: String,
      val attributes: Map<String, Any?>,
      val timestamp: Long = System.currentTimeMillis(),
      val startTime: Long = System.nanoTime(),
  )
}

/**
 * Filter implementation that doesn't allocate a new list unless the predicate returns true for at
 * least one item.
 */
@PublishedApi
internal inline fun <T> Iterable<T>.filterLikelyEmpty(predicate: (T) -> Boolean): List<T> {
  var result: ArrayList<T>? = null
  forEach {
    if (predicate(it)) {
      if (result == null) {
        result = ArrayList()
      }
      result.add(it)
    }
  }
  return result ?: listOf()
}

/** Object to subscribe to debug events */
object DebugEventBus {

  @JvmStatic
  fun subscribe(subscriber: DebugEventSubscriber) {
    DebugEventDispatcher.subscribe(subscriber)
  }

  @JvmStatic
  fun unsubscribe(subscriber: DebugEventSubscriber) {
    DebugEventDispatcher.unsubscribe(subscriber)
  }

  @JvmStatic
  fun unsubscribeAll() {
    DebugEventDispatcher.unsubscribeAll()
  }
}

@JvmInline
value class Duration(val value: Long) {
  override fun toString(): String {
    return if (value < 1_000) {
      "$value ns"
    } else if (value < 1_000_0000) {
      "${TimeUnit.NANOSECONDS.toMicros(value)} µs"
    } else {
      "${TimeUnit.NANOSECONDS.toMillis(value)} ms"
    }
  }
}

/**
 * Formats the per-component mount stats into a string for debug logging. Format:
 * {ComponentName:m=X/u=Y/r=Z/re=W,...}
 */
fun formatMountStatsSummary(
    summary: Map<String, com.facebook.rendercore.MountStats.ComponentMountStats>,
): String {
  if (summary.isEmpty()) return "{}"
  return summary.entries.joinToString(",", "{", "}") { (name, stats) ->
    "$name:m=${stats.mounted}/u=${stats.updated}/r=${stats.reused}/re=${stats.remounted}"
  }
}
