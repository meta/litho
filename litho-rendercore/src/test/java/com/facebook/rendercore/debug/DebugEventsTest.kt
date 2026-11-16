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

import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.debug.DebugEvent.Companion.All
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugEventsTest {

  companion object {
    const val TestEvent = "TestEvent"
    const val TestRenderStateId = "1"
    const val TestAttr = "attr"
    const val TestAttrValue = 1
  }

  @Before
  fun before() {
    DebugEventDispatcher.minLogLevel = LogLevel.DEBUG
  }

  @After
  fun after() {
    DebugEventBus.unsubscribeAll()
  }

  @Test
  fun `test event should be dispatched to subscriber`() {
    var event: DebugEvent? = null
    DebugEventBus.subscribe(TestEventSubscriber { e -> event = e })

    val timestamp = System.currentTimeMillis()
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = timestamp,
    ) { map ->
      map[TestAttr] = TestAttrValue
    }

    assertThat(event).isNotNull
    assertThat(event?.type).isEqualTo(TestEvent)
    assertThat(event?.renderStateId).isEqualTo(TestRenderStateId)
    assertThat(event?.attribute<Long>(DebugEventAttribute.Timestamp)).isEqualTo(timestamp)
    assertThat(event?.attribute<Int>(TestAttr)).isEqualTo(TestAttrValue)
  }

  @Test
  fun `test event should be dispatched to multiple subscribers`() {
    var eventToSub1: DebugEvent? = null
    var eventToSub2: DebugEvent? = null
    DebugEventBus.subscribe(TestEventSubscriber { e -> eventToSub1 = e })
    DebugEventBus.subscribe(TestEventSubscriber { e -> eventToSub2 = e })

    val timestamp = System.currentTimeMillis()
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = timestamp,
    ) { map ->
      map[TestAttr] = TestAttrValue
    }

    assertThat(eventToSub1).isNotNull
    assertThat(eventToSub1?.type).isEqualTo(TestEvent)
    assertThat(eventToSub1?.renderStateId).isEqualTo(TestRenderStateId)
    assertThat(eventToSub1?.attribute<Long>(DebugEventAttribute.Timestamp)).isEqualTo(timestamp)
    assertThat(eventToSub1?.attribute<Int>(TestAttr)).isEqualTo(TestAttrValue)

    assertThat(eventToSub2).isNotNull
    assertThat(eventToSub2?.type).isEqualTo(TestEvent)
    assertThat(eventToSub2?.renderStateId).isEqualTo(TestRenderStateId)
    assertThat(eventToSub2?.attribute<Long>(DebugEventAttribute.Timestamp)).isEqualTo(timestamp)
    assertThat(eventToSub2?.attribute<Int>(TestAttr)).isEqualTo(TestAttrValue)

    assertThat(eventToSub1).isSameAs(eventToSub2)
  }

  @Test
  fun `test event should not be created on dispatch if there are no subscribers`() {
    var createdDebugEvent = false

    fun dispatchDebugEvent() {
      DebugEventDispatcher.dispatch(
          type = TestEvent,
          renderStateId = {
            createdDebugEvent = true
            TestRenderStateId
          },
      )
    }

    dispatchDebugEvent()
    assertThat(createdDebugEvent).isFalse

    DebugEventBus.subscribe(TestEventSubscriber { /* do nothing */ })
    dispatchDebugEvent()
    assertThat(createdDebugEvent).isTrue
  }

  @Test
  fun `test event should not be created on trace if there are no subscribers`() {
    var createdDebugEvent = false
    var traceBlockRan = false

    fun runTrace() {
      DebugEventDispatcher.trace(
          type = TestEvent,
          renderStateId = {
            createdDebugEvent = true
            TestRenderStateId
          },
      ) {
        traceBlockRan = true
      }
    }

    runTrace()
    assertThat(createdDebugEvent).isFalse
    assertThat(traceBlockRan).isTrue

    DebugEventBus.subscribe(TestEventSubscriber { /* do nothing */ })
    runTrace()
    assertThat(createdDebugEvent).isTrue
    assertThat(traceBlockRan).isTrue
  }

  @Test
  fun `test event should not be dispatched to subscriber after unsubscribing`() {
    var event: DebugEvent? = null
    val subscriber = TestEventSubscriber { e -> event = e }
    DebugEventBus.subscribe(subscriber)

    DebugEventDispatcher.dispatch(type = TestEvent, renderStateId = { TestRenderStateId })

    assertThat(event).isNotNull
    event = null

    DebugEventBus.unsubscribe(subscriber)

    DebugEventDispatcher.dispatch(type = TestEvent, renderStateId = { TestRenderStateId })

    assertThat(event).isNull()
  }

  @Test
  fun `multiple test events should be dispatched to subscriber`() {
    val events = mutableListOf<DebugEvent>()

    DebugEventBus.subscribe(TestEventSubscriber { e -> events.add(e) })

    val timestamp = System.currentTimeMillis() - 1 // note: time in the past
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = timestamp,
    ) { map ->
      map[TestAttr] = TestAttrValue
    }
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId }, // note: no timestamp passed
    ) { map ->
      map[TestAttr] = "{$TestAttrValue}_1" // note: different attr value
    }

    assertThat(events.size).isEqualTo(2)
    assertThat(events[0].type).isEqualTo(TestEvent)
    assertThat(events[0].renderStateId).isEqualTo(TestRenderStateId)
    assertThat(events[0].attribute<Long>(DebugEventAttribute.Timestamp)).isEqualTo(timestamp)
    assertThat(events[0].attribute<Int>(TestAttr)).isEqualTo(TestAttrValue)

    assertThat(events).isNotEmpty
    assertThat(events[1].type).isEqualTo(TestEvent)
    assertThat(events[1].renderStateId).isEqualTo(TestRenderStateId)
    assertThat(events[1].attribute<Long>(DebugEventAttribute.Timestamp)).isGreaterThan(timestamp)
    assertThat(events[1].attribute<String>(TestAttr)).isEqualTo("{$TestAttrValue}_1")
  }

  @Test
  fun `test events should be dispatched to correct subscribers`() {
    var event: DebugEvent? = null
    DebugEventBus.subscribe(TestEventSubscriber { e -> event = e })

    val notTestEvent = "Not${TestEvent}"
    var otherEvent: DebugEvent? = null

    DebugEventBus.subscribe(TestEventsSubscriber(notTestEvent, listener = { e -> otherEvent = e }))

    DebugEventDispatcher.dispatch(type = TestEvent, renderStateId = { TestRenderStateId })

    DebugEventDispatcher.dispatch(type = notTestEvent, renderStateId = { TestRenderStateId })

    assertThat(event).isNotNull
    assertThat(otherEvent).isNotNull
    assertThat(event).isNotEqualTo(otherEvent)
  }

  @Test
  fun `test event should be dispatched to all event subscriber`() {
    val events = mutableListOf<DebugEvent>()

    DebugEventBus.subscribe(TestEventsSubscriber(All, listener = { e -> events.add(e) }))

    DebugEventDispatcher.dispatch(type = "a", renderStateId = { TestRenderStateId })
    DebugEventDispatcher.dispatch(type = "b", renderStateId = { TestRenderStateId })
    DebugEventDispatcher.dispatch(type = "c", renderStateId = { TestRenderStateId })
    DebugEventDispatcher.dispatch(type = "d", renderStateId = { TestRenderStateId })

    assertThat(events.size).isEqualTo(4)
  }

  @Test
  fun `trace event should be dispatched to subscriber`() {
    val traceTestAttr = "otherTestAttr"
    val traceTestAttrValue = "2"

    var event: DebugEvent? = null
    DebugEventBus.subscribe(TestEventSubscriber { e -> event = e })

    val timestamp = System.currentTimeMillis() - 1 // note: time in the past

    DebugEventDispatcher.trace(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        attributesAccumulator = { attributes -> attributes[TestAttr] = TestAttrValue },
    ) { scope ->
      scope?.attribute(traceTestAttr, "2")
    }

    assertThat(event).isNotNull
    assertThat(event?.type).isEqualTo(TestEvent)
    assertThat(event?.renderStateId).isEqualTo(TestRenderStateId)
    assertThat(event?.attribute<Long>(DebugEventAttribute.Timestamp)).isGreaterThan(timestamp)
    assertThat(event?.attribute<Duration>(DebugEventAttribute.Duration)?.value).isGreaterThan(0)
    assertThat(event?.attribute<Int>(TestAttr)).isEqualTo(TestAttrValue)
    assertThat(event?.attribute<String>(traceTestAttr)).isEqualTo(traceTestAttrValue)
  }

  @Test
  fun `test event should be dispatched only if min log level is smaller than event log level`() {
    var event: DebugEvent? = null
    DebugEventBus.subscribe(TestEventSubscriber { e -> event = e })

    DebugEventDispatcher.minLogLevel = LogLevel.VERBOSE
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.VERBOSE,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.minLogLevel = LogLevel.VERBOSE
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.DEBUG,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.minLogLevel = LogLevel.WARNING
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.VERBOSE,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNull()
    event = null

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.WARNING,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.DEBUG,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.minLogLevel = LogLevel.DEBUG
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.WARNING,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNull()
    event = null

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.DEBUG,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.ERROR,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.minLogLevel = LogLevel.ERROR
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.DEBUG,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNull()
    event = null

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.ERROR,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.FATAL,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null

    DebugEventDispatcher.minLogLevel = LogLevel.FATAL
    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.ERROR,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNull()
    event = null

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        timestamp = System.currentTimeMillis(),
        logLevel = LogLevel.FATAL,
    ) {
      mapOf<String, Any?>(TestAttr to TestAttrValue)
    }

    assertThat(event).isNotNull
    event = null
  }

  @Test
  fun `generateTraceIdentifier should generate a valid id if there is at least one subscriber listening to all events`() {
    assertThat(DebugEventDispatcher.generateTraceIdentifier(TestEvent)).isNull()
    DebugEventDispatcher.subscribe(TestEventsSubscriber(All, listener = { /* nothing */ }))
    assertThat(DebugEventDispatcher.generateTraceIdentifier(TestEvent)).isNotNull()
  }

  @Test
  fun `generateTraceIdentifier should generate a valid id if there is at least one subscriber listens to that event`() {
    assertThat(DebugEventDispatcher.generateTraceIdentifier(TestEvent)).isNull()
    DebugEventDispatcher.subscribe(TestEventsSubscriber(TestEvent, listener = { /* nothing */ }))
    assertThat(DebugEventDispatcher.generateTraceIdentifier(TestEvent)).isNotNull()
  }

  @Test
  fun `generateTraceIdentifier should generate different ids for each invocation as long as there are valid subscribers`() {
    assertThat(DebugEventDispatcher.generateTraceIdentifier(TestEvent)).isNull()
    DebugEventDispatcher.subscribe(TestEventsSubscriber(TestEvent, listener = { /* nothing */ }))

    // Relative, not absolute: the counter is process-wide, so other tests in this JVM shift it.
    val first = DebugEventDispatcher.generateTraceIdentifier(TestEvent)
    val second = DebugEventDispatcher.generateTraceIdentifier(TestEvent)

    assertThat(first).isNotNull()
    assertThat(second).isEqualTo(checkNotNull(first) + 1)
  }

  @Test
  fun `java trace APIs work correctly`() {
    val events = mutableListOf<DebugEvent>()
    DebugEventDispatcher.subscribe(TestEventsSubscriber(All, listener = { events.add(it) }))

    val traceId = DebugEventDispatcher.generateTraceIdentifier(TestEvent)

    DebugEventDispatcher.beginTrace(
        traceId!!,
        TestEvent,
        TestRenderStateId,
        mutableMapOf(TestAttr to 1),
    )

    assertThat(events).isEmpty()

    DebugEventDispatcher.endTrace(traceId, mutableMapOf("other-attr" to "other-value"))

    assertThat(events).hasSize(1)

    val event = events.last()

    assertThat(event.type).isEqualTo(TestEvent)
    assertThat(event.renderStateId).isEqualTo(TestRenderStateId)
    assertThat(event.attribute<Int>(TestAttr)).isEqualTo(1)
    assertThat(event.attribute<String>("other-attr")).isEqualTo("other-value")
  }

  @Test
  fun `filtered debug event subscriber should only handle filtered events`() {
    val events = mutableListOf<DebugEvent>()
    var subscriber: DebugEventSubscriber

    subscriber = FilteredDebugEventSubscriber(DebugEvent.ViewOnLayout) { events.add(it) }

    DebugEventBus.subscribe(subscriber)

    DebugEventDispatcher.dispatch(type = DebugEvent.MountItemMount, renderStateId = { "-1" })

    assertThat(events).hasSize(0)

    DebugEventDispatcher.dispatch(type = DebugEvent.ViewOnLayout, renderStateId = { "-1" })

    assertThat(events).hasSize(1)

    DebugEventBus.unsubscribe(subscriber)
    events.clear()

    subscriber =
        FilteredDebugEventSubscriber(
            DebugEvent.ViewOnLayout,
            DebugEvent.ViewOnLayout + ":start",
            matcher = StringAttributeMatcher(DebugEventAttribute.Name to "ViewGroup"),
        ) {
          events.add(it)
        }

    DebugEventBus.subscribe(subscriber)

    DebugEventDispatcher.dispatch(type = DebugEvent.ViewOnLayout, renderStateId = { "-1" }) {
      it[DebugEventAttribute.Name] = "Drawable"
    }

    assertThat(events).hasSize(0)

    DebugEventDispatcher.dispatch(
        type = DebugEvent.ViewOnLayout + ":start",
        renderStateId = { "-1" },
    ) {
      it[DebugEventAttribute.Name] = "ViewGroup"
    }

    assertThat(events).hasSize(1)
  }

  @Test
  fun `trace event should be dispatched to trace listeners`() {
    val traceListener0 = TestTraceListener("random")
    DebugEventBus.subscribe(traceListener0)

    val traceListener1 = TestTraceListener()
    DebugEventBus.subscribe(traceListener1)

    DebugEventDispatcher.trace(type = TestEvent, renderStateId = { TestRenderStateId }) {}

    assertThat(traceListener0.records.size).isEqualTo(0)
    assertThat(traceListener1.records.size).isEqualTo(1)

    val traceListener2 = TestTraceListener()
    DebugEventBus.subscribe(traceListener2)

    DebugEventDispatcher.trace(type = TestEvent, renderStateId = { TestRenderStateId }) {}

    assertThat(traceListener0.records.size).isEqualTo(0)
    assertThat(traceListener1.records.size).isEqualTo(2)
    assertThat(traceListener2.records.size).isEqualTo(1)

    DebugEventBus.unsubscribe(traceListener1)

    DebugEventDispatcher.trace(type = TestEvent, renderStateId = { TestRenderStateId }) {}

    assertThat(traceListener0.records.size).isEqualTo(0)
    assertThat(traceListener1.records.size).isEqualTo(2)
    assertThat(traceListener2.records.size).isEqualTo(2)

    DebugEventDispatcher.trace(type = "random", renderStateId = { TestRenderStateId }) {}

    assertThat(traceListener0.records.size).isEqualTo(1)
    assertThat(traceListener1.records.size).isEqualTo(2)
    assertThat(traceListener2.records.size).isEqualTo(2)
  }

  @Test
  fun `an event no subscriber accepts is not built`() {
    val declining = AcceptingSubscriber(accepts = false)
    DebugEventBus.subscribe(declining)
    var builtAttributes = false

    DebugEventDispatcher.dispatch(type = TestEvent, renderStateId = { TestRenderStateId }) {
      builtAttributes = true
    }

    assertThat(builtAttributes).isFalse
    assertThat(declining.received).isEmpty()
  }

  @Test
  fun `an event is built once and reaches only the subscribers that accept it`() {
    val declining = AcceptingSubscriber(accepts = false)
    val accepting = AcceptingSubscriber(accepts = true)
    DebugEventBus.subscribe(declining)
    DebugEventBus.subscribe(accepting)
    var attributeBuilds = 0

    DebugEventDispatcher.dispatch(type = TestEvent, renderStateId = { TestRenderStateId }) { map ->
      attributeBuilds++
      map[TestAttr] = TestAttrValue
    }

    assertThat(attributeBuilds).isEqualTo(1)
    assertThat(declining.received).isEmpty()
    assertThat(accepting.received).hasSize(1)
    assertThat(accepting.received.single().attribute<Int>(TestAttr)).isEqualTo(TestAttrValue)
  }

  @Test
  fun `a subscriber is asked with the event's type and log level`() {
    val accepting = AcceptingSubscriber(accepts = true)
    DebugEventBus.subscribe(accepting)

    DebugEventDispatcher.dispatch(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        logLevel = LogLevel.ERROR,
    )

    assertThat(accepting.questions).containsExactly(TestEvent to LogLevel.ERROR)
  }

  @Test
  fun `a trace no subscriber accepts runs its block without building the event`() {
    val declining = AcceptingSubscriber(accepts = false)
    DebugEventBus.subscribe(declining)
    var builtAttributes = false
    var ranBlock = false

    DebugEventDispatcher.trace(
        type = TestEvent,
        renderStateId = { TestRenderStateId },
        attributesAccumulator = { builtAttributes = true },
    ) { scope ->
      ranBlock = true
      assertThat(scope).isNull()
    }

    assertThat(ranBlock).isTrue
    assertThat(builtAttributes).isFalse
    assertThat(declining.received).isEmpty()
    assertThat(declining.questions).containsExactly(TestEvent to LogLevel.DEBUG)
  }

  class AcceptingSubscriber(private val accepts: Boolean) : DebugEventSubscriber(TestEvent) {
    val questions = mutableListOf<Pair<String, LogLevel>>()
    val received = mutableListOf<DebugEvent>()

    override fun acceptsEvent(type: String, logLevel: LogLevel): Boolean {
      questions.add(type to logLevel)
      return accepts
    }

    override fun onEvent(event: DebugEvent) {
      received.add(event)
    }
  }

  class TestEventSubscriber(val listener: (DebugEvent) -> Unit) : DebugEventSubscriber(TestEvent) {
    override fun onEvent(event: DebugEvent) {
      listener(event)
    }
  }

  class TestEventsSubscriber(
      vararg events: String,
      val listener: (DebugEvent) -> Unit,
  ) : DebugEventSubscriber(*events) {
    override fun onEvent(event: DebugEvent) {
      listener(event)
    }
  }

  class TestTraceListener(
      vararg events: String = arrayOf(TestEvent),
  ) : TraceListener<Int>, DebugEventSubscriber(*events) {

    val records = mutableListOf<Any>()

    private var counter = 0

    override fun onEvent(event: DebugEvent): Unit = Unit

    override fun onTraceStart(
        type: String,
        timestamp: Long,
        renderStateId: String,
        attributes: Map<String, Any?>,
    ): Int {
      records.add(type)
      val token = counter
      counter++
      return token
    }

    override fun onTraceEnd(token: Int, event: DebugProcessEvent) {
      assertThat(records[token]).isEqualTo(event.type)
    }
  }
}
