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

import com.facebook.litho.config.ComponentsConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * [TreePropContainer] guards its entries with the monitor of the map holding them, so a member that
 * reaches into a second container while holding its own map's monitor deadlocks against a thread
 * doing the same thing in the opposite direction.
 *
 * `ComponentTree.requestRenderWithSplitFutures` compares the committed container against the
 * incoming one on the main thread, while `ResolveTreeFuture.resolve` compares that same pair the
 * other way round on a resolve thread. Operand order is lock order, so the two hung each other.
 *
 * Every case runs with each map [ComponentsConfiguration.useScatterMapInTreePropContainer] selects,
 * since both arms ship.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class TreePropContainerConcurrencyTest(private val useScatterMap: Boolean) {

  private var previousSetting = false

  @Before
  fun setUp() {
    previousSetting = ComponentsConfiguration.useScatterMapInTreePropContainer
    ComponentsConfiguration.useScatterMapInTreePropContainer = useScatterMap
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.useScatterMapInTreePropContainer = previousSetting
  }

  @Test
  fun `equals does not hold the container monitor while comparing values`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val shared: TreeProp<Any?> = treePropOf { null }
    val unrelated: TreeProp<Any?> = treePropOf { null }
    val a = TreePropContainer().apply { put(shared, BlockingValue(entered, release)) }
    val b = TreePropContainer().apply { put(shared, BlockingValue(entered, release)) }

    val comparison = daemon { a == b }
    assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .describedAs("equals never reached the value comparison")
        .isTrue

    // Parked inside the comparison, so this write lands only if `a`'s monitor is free.
    val probed = CountDownLatch(1)
    val prober = daemon {
      a.put(unrelated, "probe")
      probed.countDown()
    }
    val probeCompleted = probed.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    release.countDown()
    comparison.join(JOIN_TIMEOUT_MILLIS)
    prober.join(JOIN_TIMEOUT_MILLIS)

    assertThat(probeCompleted)
        .describedAs("equals held the container monitor while comparing values")
        .isTrue
  }

  @Test
  fun `hashCode does not hold the container monitor while hashing values`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val hashed: TreeProp<Any?> = treePropOf { null }
    val unrelated: TreeProp<Any?> = treePropOf { null }
    val container = TreePropContainer().apply { put(hashed, BlockingHash(entered, release)) }

    val hashing = daemon { container.hashCode() }
    assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .describedAs("hashCode never reached the value hash")
        .isTrue

    val probed = CountDownLatch(1)
    val prober = daemon {
      container.put(unrelated, "probe")
      probed.countDown()
    }
    val probeCompleted = probed.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    release.countDown()
    hashing.join(JOIN_TIMEOUT_MILLIS)
    prober.join(JOIN_TIMEOUT_MILLIS)

    assertThat(probeCompleted)
        .describedAs("hashCode held the container monitor while hashing values")
        .isTrue
  }

  @Test
  fun `opposing concurrent equals do not deadlock`() {
    val (a, b) = equalContainerPair()
    assertNoDeadlock(forward = { a == b }, reverse = { b == a })
  }

  @Test
  fun `opposing concurrent putAll do not deadlock`() {
    val (a, b) = equalContainerPair()
    assertNoDeadlock(forward = { a.putAll(b) }, reverse = { b.putAll(a) })
  }

  @Test
  fun `opposing concurrent equals across map kinds do not deadlock`() {
    val (a, b) = equalContainerPair(otherUsesScatterMap = !useScatterMap)
    assertNoDeadlock(forward = { a == b }, reverse = { b == a })
  }

  @Test
  fun `opposing concurrent putAll across map kinds do not deadlock`() {
    val (a, b) = equalContainerPair(otherUsesScatterMap = !useScatterMap)
    assertNoDeadlock(forward = { a.putAll(b) }, reverse = { b.putAll(a) })
  }

  /**
   * Two containers with equal contents, so a comparison walks every entry instead of exiting. The
   * second is backed by the map [otherUsesScatterMap] selects.
   */
  private fun equalContainerPair(
      otherUsesScatterMap: Boolean = useScatterMap,
  ): Pair<TreePropContainer, TreePropContainer> {
    val props = List(ENTRY_COUNT) { treePropOf<Any?> { null } }
    val fill: TreePropContainer.() -> Unit = {
      props.forEachIndexed { index, prop -> put(prop, "value$index") }
    }
    val first = TreePropContainer().apply(fill)
    ComponentsConfiguration.useScatterMapInTreePropContainer = otherUsesScatterMap
    val second = TreePropContainer().apply(fill)
    ComponentsConfiguration.useScatterMapInTreePropContainer = useScatterMap
    return first to second
  }

  private fun assertNoDeadlock(forward: () -> Unit, reverse: () -> Unit) {
    val start = CyclicBarrier(2)
    val finished = CountDownLatch(2)
    val failure = AtomicReference<Throwable?>()

    daemon { hammer(start, finished, failure, forward) }
    daemon { hammer(start, finished, failure, reverse) }

    val completed = finished.await(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    assertThat(failure.get()).isNull()
    assertThat(completed)
        .describedAs(
            "deadlocked: a thread held one container's monitor while waiting for the other",
        )
        .isTrue
  }

  private fun hammer(
      start: CyclicBarrier,
      finished: CountDownLatch,
      failure: AtomicReference<Throwable?>,
      operation: () -> Unit,
  ) {
    try {
      start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      repeat(ITERATIONS) { operation() }
    } catch (t: Throwable) {
      failure.set(t)
    } finally {
      finished.countDown()
    }
  }

  private fun daemon(body: () -> Unit): Thread {
    val thread = Thread { body() }
    thread.isDaemon = true
    thread.start()
    return thread
  }

  /** Parks inside `equals` so the test can probe whether a container monitor is still held. */
  private class BlockingValue(
      private val entered: CountDownLatch,
      private val release: CountDownLatch,
  ) {
    override fun equals(other: Any?): Boolean {
      if (other !is BlockingValue) {
        return false
      }
      entered.countDown()
      release.await(PARK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      return true
    }

    override fun hashCode(): Int = 0
  }

  /**
   * Parks inside `hashCode`. Copying the map hashes only keys, so this runs on the snapshot and not
   * under the monitor.
   */
  private class BlockingHash(
      private val entered: CountDownLatch,
      private val release: CountDownLatch,
  ) {
    override fun hashCode(): Int {
      entered.countDown()
      release.await(PARK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      return 0
    }
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "useScatterMap={0}")
    fun settings(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    private const val TIMEOUT_SECONDS = 5L
    private const val PROBE_TIMEOUT_SECONDS = 5L
    /**
     * Far above [PROBE_TIMEOUT_SECONDS], so a held monitor fails the probe rather than racing it.
     */
    private const val PARK_TIMEOUT_SECONDS = 60L
    private const val DEADLOCK_TIMEOUT_SECONDS = 30L
    private const val JOIN_TIMEOUT_MILLIS = 65_000L
    private const val ENTRY_COUNT = 64
    private const val ITERATIONS = 20_000
  }
}
