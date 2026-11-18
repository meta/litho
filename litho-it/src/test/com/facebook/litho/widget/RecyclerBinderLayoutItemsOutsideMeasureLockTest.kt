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

import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.ErrorEventHandler
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.LithoStartupLogger
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.Size
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.LockTestHarness.Companion.atMost
import com.facebook.litho.widget.LockTestHarness.Companion.exactly
import com.facebook.litho.widget.LockTestHarness.Companion.unspecified
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/**
 * Schedules that [RecyclerBinderConfig.layoutItemsOutsideMeasureLock] must handle: interleavings of
 * measures, data changes, range layouts and state updates in which a late or reused layout could
 * leave an item at the wrong size. Item layouts park on threads the test controls; a few windows
 * inside [ComponentTreeHolder] are opened by holding the item tree's monitor on main, so another
 * thread stops between the holder's own section and its request to the tree.
 */
// PAUSED: LEGACY loopers can deadlock when a background thread removes a message while main
// enqueues.
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RecyclerBinderLayoutItemsOutsideMeasureLockTest {

  private lateinit var context: ComponentContext
  private val harnesses = ArrayList<LockTestHarness>()

  @Before
  fun setup() {
    context = ComponentContext(getApplicationContext())
  }

  @After
  fun tearDown() {
    harnesses.forEach { it.stop() }
  }

  private fun createHarness(
      flag: Boolean? = true,
      wrap: Boolean = false,
      horizontal: Boolean = false,
      hScrollAsync: Boolean = false,
      asyncInsertQueue: Boolean = false,
      retainMaximumRange: Boolean = false,
      componentWarmer: ComponentWarmer? = null,
      configurationFlag: Boolean = false,
  ): LockTestHarness = LockTestHarness(
      context,
      flag,
      wrap,
      horizontal,
      hScrollAsync,
      asyncInsertQueue,
      retainMaximumRange = retainMaximumRange,
      componentWarmer = componentWarmer,
      configurationFlag = configurationFlag,
  )
      .also { harness -> harnesses.add(harness) }

  private fun LockTestHarness.waitUntilBlockedOnTree(thread: LockTestThread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!thread.isBlockedOn(ComponentTree::class.java)) {
      check(System.nanoTime() < deadline) { "${thread.name} never blocked on the item tree" }
      Thread.sleep(1)
    }
  }

  private fun LockTestHarness.waitUntilBlockedOnBinder(thread: LockTestThread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!thread.isBlockedOn(RecyclerBinder::class.java)) {
      check(System.nanoTime() < deadline) { "${thread.name} never blocked on the binder" }
      Thread.sleep(1)
    }
  }

  private fun LockTestThread.waitUntilDone() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (busy) {
      check(System.nanoTime() < deadline) { "$name never finished" }
      Thread.sleep(1)
    }
  }

  private fun LockTestHarness.assertSettled() {
    val state = debugState()
    assertThat(failures).describedAs("thread failures\n$state").isEmpty()
    assertThat(staleItems()).describedAs("stale items\n$state").isEmpty()
    assertThat(windowNotLaidOut()).describedAs("window items not laid out\n$state").isEmpty()
    assertThat(leakedTrees()).describedAs("leaked trees\n$state").isEmpty()
  }

  /**
   * The render info version: a background measure's holder section reads the old component; main
   * updates the item and lays the new one out; the background request for the old component then
   * reaches the tree. It must not lay out or commit the old component.
   */
  @Test
  fun `a request for a component that an update replaced does not reach the tree`() {
    val harness = createHarness()
    harness.insertAll(1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    val tree = checkNotNull(harness.binder.componentTreeHolders[0].componentTree)
    val oldSerial = harness.serialAt(0)
    lateinit var background: LockTestThread
    var layoutsBeforeUpdate = 0
    synchronized(tree) {
      // The background measure (new width) plans item 0, runs the holder's section and stops at
      // the tree's monitor.
      background = harness.measureBackground(exactly(10), exactly(60))
      harness.waitUntilBlockedOnTree(background)
      layoutsBeforeUpdate = harness.hooks.layoutsOf(oldSerial).size
      harness.onMain {
        harness.binder.updateItemAt(0, harness.item(5))
        harness.binder.notifyChangeSetComplete(
            true,
            RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
        )
      }
      harness.measureMain(exactly(10), exactly(60))
    }
    assertThat(harness.quiesceWithEnvironment()).isTrue
    assertThat(harness.hooks.layoutsOf(oldSerial).size)
        .describedAs("layouts of the replaced component after the update")
        .isEqualTo(layoutsBeforeUpdate)
    harness.assertSettled()
  }

  /**
   * A wrap list, where the holder checks the request after a sync layout: a background fill layout
   * records EXACTLY 15 in the holder, main then lays the item out for its own measure, and the
   * background request lands last with the other specs. The holder must not claim a valid tree for
   * main's specs.
   */
  @Test
  fun `a sync layout that an other request overtook does not mark the tree valid`() {
    val harness = createHarness(wrap = true)
    harness.insertAll(1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(10), atMost(60))
    assertThat(harness.quiesce()).isTrue
    val tree = checkNotNull(harness.binder.componentTreeHolders[1].componentTree)
    lateinit var background: LockTestThread
    synchronized(tree) {
      // Range item 0 at AT_MOST 30 (15 wide), then the fill at EXACTLY 15: item 0, then item 1,
      // which stops at the tree's monitor.
      background = harness.measureBackground(atMost(30), atMost(60), withHandler = true)
      harness.waitUntilBlockedOnTree(background)
      harness.onMain {
        harness.binder.updateItemAt(0, harness.view())
        harness.binder.notifyChangeSetComplete(
            true,
            RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
        )
      }
      harness.measureMain(atMost(30), atMost(60), withHandler = true)
    }
    assertThat(harness.quiesce()).isTrue
    assertThat(harness.staleItems())
        .describedAs("stale items before the environment runs")
        .isEmpty()
    assertThat(harness.quiesceWithEnvironment { Triple(atMost(30), atMost(60), true) }).isTrue
    harness.assertSettled()
  }

  /**
   * A tree that reuses a compatible layout for an ordered request records that request's specs, so
   * a queued layout for older specs does not commit over it. A background measure at EXACTLY 10
   * lays item 0 out (10 wide) without the lock; before it applies, a range computation on main
   * still sees EXACTLY 15 and queues a layout for it. The background apply then asks for EXACTLY 10
   * again, which reuses the committed layout: without the recorded specs, the queued EXACTLY 15
   * layout would commit a 15 wide layout under a holder valid for EXACTLY 10.
   */
  @Test
  fun `a reused layout's specs win over a queued range layout for older specs`() {
    val harness = createHarness()
    harness.insertAll(1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(15), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.hooks.parkLayouts = true
    val background = harness.measureBackground(exactly(10), exactly(60))
    assertThat(background.isParked).isTrue
    harness.hooks.parkLayouts = false
    synchronized(harness.binder) {
      background.release()
      harness.waitUntilBlockedOnBinder(background)
      // The range layout for EXACTLY 15 is queued, not run.
      harness.binder.onNewVisibleRange(0, 1)
    }
    background.waitUntilDone()
    assertThat(harness.quiesce()).isTrue
    assertThat(harness.staleItems()).describedAs("stale items\n${harness.debugState()}").isEmpty()
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /**
   * An apply that takes the locked measure's early return runs no range check. Main and a
   * background thread measure at the same specs: main's unlocked layout commits item 0; the
   * background layout's holder section records its specs; main's apply requests item 0 at its new
   * child specs; the background request then reuses the committed layout and finds the holder asked
   * for other specs, so it leaves it invalid with nothing queued. The background apply must request
   * the items it laid out that are not valid, or every bind lays them out again on main.
   */
  @Test
  fun `an apply that takes the early return requests the items it laid out that are not valid`() {
    val harness = createHarness()
    harness.insertAll(1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    val holder = harness.binder.componentTreeHolders[0]
    val tree = checkNotNull(holder.componentTree)
    var stopBackground = true
    harness.hooks.parkBeforeRequest = { _, async ->
      !async && stopBackground.also { stopBackground = false }
    }
    val background = harness.measureBackground(atMost(30), exactly(60))
    assertThat(background.parkedIn).isEqualTo("sync request")
    var releasedBackground = false
    harness.hooks.onMainRequest = { _, async ->
      if (async && !releasedBackground) {
        releasedBackground = true
        // The background holder section runs, then its request waits for the tree's monitor.
        background.release()
      }
    }
    harness.onMain {
      synchronized(tree) { harness.binder.measure(Size(), atMost(30), exactly(60), null) }
    }
    harness.hooks.onMainRequest = null
    assertThat(releasedBackground).isTrue
    background.waitUntilDone()
    assertThat(harness.quiesce()).isTrue
    val widthSpec = harness.binder.getChildWidthSpec(0)
    val heightSpec = harness.binder.getChildHeightSpec(0)
    assertThat(holder.isTreeValidForSizeSpecs(widthSpec, heightSpec))
        .describedAs("item 0 valid at its specs\n${harness.debugState()}")
        .isTrue
    val mainLayoutsBefore =
        harness.hooks.layoutsOf(harness.serialAt(0)).count { layout -> layout.thread == "main" }
    harness.bind(0)
    assertThat(
        harness.hooks.layoutsOf(harness.serialAt(0)).count { layout -> layout.thread == "main" },
    )
        .describedAs("main layouts of item 0 at its bind")
        .isEqualTo(mainLayoutsBefore)
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /**
   * A range request is issued in the lock section that read its specs. The range computation stops
   * right before issuing; a measure at other specs then runs. No request for the old specs may
   * follow the measure that replaced them.
   */
  @Test
  fun `a range request for old specs never follows the measure that replaced them`() {
    val harness = createHarness()
    harness.insertAll(1, 2, 3, 0, 1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(30))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    // Invalidate every item at EXACTLY 10 without running the queued range requests.
    harness.measureMain(exactly(10), exactly(30))
    while (!harness.queue.isEmpty) harness.queue.poll()
    harness.binder.componentTreeHolders.forEach { it.invalidateTree() }
    var stop = true
    harness.hooks.parkBeforeRequest = { _, async -> async && stop.also { stop = false } }
    val ranger = harness.thread("ranger")
    harness.backgrounds.add(ranger)
    ranger.run { harness.binder.onNewVisibleRange(0, 1) }
    assertThat(ranger.isParked).isTrue
    harness.measureMain(exactly(30), exactly(30))
    val requestsAfterMeasure = harness.hooks.requests.size
    ranger.release()
    assertThat(harness.quiesce()).isTrue
    val late =
        harness.hooks.requests.drop(requestsAfterMeasure).filter { it.contains("EXACTLY 10") }
    assertThat(late).describedAs("requests for EXACTLY 10 after the EXACTLY 30 measure").isEmpty()
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /** The same for the component warmer's async preparation of a holder. */
  @Test
  fun `a warmer's async preparation for old specs never follows the measure that replaced them`() {
    val harness = createHarness()
    harness.insertAll(1, 2, 3, 0, 1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(30))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.measureMain(exactly(10), exactly(30))
    while (!harness.queue.isEmpty) harness.queue.poll()
    val holder = harness.binder.componentTreeHolders[0]
    holder.invalidateTree()
    var stop = true
    harness.hooks.parkBeforeRequest = { _, async -> async && stop.also { stop = false } }
    val preparer = harness.thread("preparer")
    harness.backgrounds.add(preparer)
    preparer.run { harness.binder.componentTreeHolderPreparer.prepareAsync(holder) }
    assertThat(preparer.isParked).isTrue
    harness.measureMain(exactly(30), exactly(30))
    val requestsAfterMeasure = harness.hooks.requests.size
    preparer.release()
    assertThat(harness.quiesce()).isTrue
    val late =
        harness.hooks.requests.drop(requestsAfterMeasure).filter { request ->
          request.contains("EXACTLY 10")
        }
    assertThat(late).describedAs("requests for EXACTLY 10 after the EXACTLY 30 measure").isEmpty()
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /** The same for a binder that retains its maximum range. */
  @Test
  fun `a retained range request for old specs never follows the measure that replaced them`() {
    val harness = createHarness(retainMaximumRange = true)
    harness.insertAll(1, 2, 3, 0, 1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(30))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.measureMain(exactly(10), exactly(30))
    while (!harness.queue.isEmpty) harness.queue.poll()
    harness.binder.componentTreeHolders.forEach { holder -> holder.invalidateTree() }
    var stop = true
    harness.hooks.parkBeforeRequest = { _, async -> async && stop.also { stop = false } }
    val ranger = harness.thread("ranger")
    harness.backgrounds.add(ranger)
    ranger.run { harness.binder.onNewVisibleRange(0, 1) }
    assertThat(ranger.isParked).isTrue
    harness.measureMain(exactly(30), exactly(30))
    val requestsAfterMeasure = harness.hooks.requests.size
    ranger.release()
    assertThat(harness.quiesce()).isTrue
    val late =
        harness.hooks.requests.drop(requestsAfterMeasure).filter { request ->
          request.contains("EXACTLY 10")
        }
    assertThat(late).describedAs("requests for EXACTLY 10 after the EXACTLY 30 measure").isEmpty()
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /**
   * A holder removed while a background measure is about to lay it out without the lock must not
   * get a tree again (it would never be released).
   */
  @Test
  fun `a holder removed during an unlocked layout keeps no tree`() {
    val harness = createHarness()
    harness.insertAll(1, 2)
    var stop = true
    harness.hooks.parkBeforeRequest = { _, async -> !async && stop.also { stop = false } }
    val removed = harness.binder.componentTreeHolders[0]
    val background = harness.measureBackground(exactly(20), exactly(60))
    assertThat(background.parkedIn).isEqualTo("sync request")
    harness.onMain {
      harness.binder.removeItemAt(0)
      harness.binder.notifyChangeSetComplete(
          true,
          RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
      )
    }
    ShadowLooper.idleMainLooper()
    assertThat(harness.quiesceWithEnvironment()).isTrue
    assertThat(removed.componentTree).describedAs("removed holder's tree").isNull()
    harness.assertSettled()
  }

  /** The same for holders removed with removeRangeAt and replaceAll. */
  @Test
  fun `holders removed in a range or replaced during an unlocked layout keep no tree`() {
    for (replace in listOf(false, true)) {
      val harness = createHarness()
      harness.insertAll(1, 2)
      var stop = true
      harness.hooks.parkBeforeRequest = { _, async -> !async && stop.also { stop = false } }
      val removed = harness.binder.componentTreeHolders[0]
      val background = harness.measureBackground(exactly(20), exactly(60))
      assertThat(background.parkedIn).isEqualTo("sync request")
      harness.onMain {
        if (replace) {
          harness.binder.replaceAll(listOf(harness.item(3)))
        } else {
          harness.binder.removeRangeAt(0, 1)
        }
        harness.binder.notifyChangeSetComplete(
            true,
            RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
        )
      }
      ShadowLooper.idleMainLooper()
      assertThat(harness.quiesceWithEnvironment()).isTrue
      assertThat(removed.componentTree)
          .describedAs("removed holder's tree, replace=$replace")
          .isNull()
      harness.assertSettled()
    }
  }

  /**
   * A holder the binder takes from a component warmer, which built it without the binder, makes
   * ordered requests like the binder's own holders.
   */
  @Test
  fun `a holder taken from a component warmer makes ordered requests`() {
    val warmer = ComponentWarmer(context, exactly(20), unspecified())
    val harness = createHarness(componentWarmer = warmer)
    harness.hooks.parkLayouts = false
    val renderInfo =
        ComponentRenderInfo.create()
            .component(LockTestItem(100, 1, harness.hooks))
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "warmed")
            .build()
    warmer.prepare("warmed", renderInfo, Size())
    harness.onMain {
      harness.binder.insertItemAt(0, renderInfo)
      harness.binder.notifyChangeSetComplete(
          true,
          RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
      )
    }
    harness.measureMain(exactly(10), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    val tree = checkNotNull(harness.binder.componentTreeHolders[0].componentTree)
    val newestOrderedRequestVersion =
        ComponentTree::class
            .java
            .getDeclaredField("newestOrderedRequestVersion")
            .apply { isAccessible = true }
            .getInt(tree)
    assertThat(newestOrderedRequestVersion).describedAs("ordered request version").isNotNegative
    harness.assertSettled()
  }

  /**
   * Whether a background measure lays its item out without the binder lock, and whether the item's
   * tree got ordered requests.
   */
  private fun laysOutOutsideLock(
      flag: Boolean?,
      configurationFlag: Boolean,
  ): Pair<Boolean, Boolean> {
    val harness = createHarness(flag = flag, configurationFlag = configurationFlag)
    harness.insertAll(1, 2, 3)
    val background = harness.measureBackground(exactly(20), exactly(60))
    assertThat(background.isParked).isTrue
    val lockFree = harness.binderLockFree()
    harness.hooks.parkLayouts = false
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
    val tree = checkNotNull(harness.binder.componentTreeHolders[0].componentTree)
    val newestOrderedRequestVersion =
        ComponentTree::class
            .java
            .getDeclaredField("newestOrderedRequestVersion")
            .apply { isAccessible = true }
            .getInt(tree)
    return lockFree to (newestOrderedRequestVersion >= 0)
  }

  /** A binder whose config leaves the flag unset follows its components configuration. */
  @Test
  fun `a binder whose config leaves the flag unset follows its ComponentsConfiguration`() {
    for (configurationFlag in listOf(true, false)) {
      assertThat(laysOutOutsideLock(flag = null, configurationFlag = configurationFlag))
          .describedAs("configurationFlag=$configurationFlag")
          .isEqualTo(configurationFlag to configurationFlag)
    }
  }

  /** A value set on the binder's config wins over its components configuration. */
  @Test
  fun `a binder's own config value wins over its ComponentsConfiguration`() {
    for (flag in listOf(true, false)) {
      assertThat(laysOutOutsideLock(flag = flag, configurationFlag = !flag))
          .describedAs("flag=$flag")
          .isEqualTo(flag to flag)
    }
  }

  /** Configs, and copies of them, leave the flag unset unless it is set. */
  @Test
  fun `configs leave the flag unset by default`() {
    assertThat(RecyclerBinderConfig().layoutItemsOutsideMeasureLock).isNull()
    assertThat(RecyclerBinderConfig.create().build().layoutItemsOutsideMeasureLock).isNull()
    assertThat(
        RecyclerBinderConfig.create(RecyclerBinderConfig()).build().layoutItemsOutsideMeasureLock,
    )
        .isNull()
    assertThat(
        RecyclerBinderConfig.create(RecyclerBinderConfig(layoutItemsOutsideMeasureLock = false))
            .build()
            .layoutItemsOutsideMeasureLock,
    )
        .isFalse
    assertThat(ComponentsConfiguration.defaultInstance.layoutItemsOutsideMeasureLock).isFalse
  }

  /**
   * The ANR shape: a background measure is inside an item layout. Main's measure at other specs and
   * main's data operations do not wait for it, and the binder lock is free meanwhile. The locked
   * binder makes main wait for the whole layout.
   */
  @Test
  fun `main does not wait for a background measure's item layout`() {
    for (flag in listOf(true, false)) {
      val harness = createHarness(flag = flag)
      harness.insertAll(1, 2, 3)
      val background = harness.measureBackground(exactly(20), exactly(60))
      assertThat(background.isParked).isTrue
      assertThat(harness.binderLockFree()).describedAs("lock free, flag=$flag").isEqualTo(flag)
      harness.onMain {
        harness.binder.insertItemAt(3, harness.item(2))
        harness.binder.notifyChangeSetComplete(
            true,
            RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
        )
      }
      harness.hooks.parkLayouts = false
      val size = harness.measureMain(exactly(10), exactly(60))
      if (flag) {
        assertThat(harness.mainReleases).describedAs("main waits").isZero
        assertThat(background.isParked).isTrue
      } else {
        assertThat(harness.mainReleases).describedAs("main waits, locked").isPositive
      }
      assertThat(size.width).isEqualTo(10)
      assertThat(harness.quiesceWithEnvironment()).isTrue
      harness.assertSettled()
    }
  }

  /** setSize does not hold the binder lock across the measure it runs. */
  @Test
  fun `setSize lays items out without the binder lock`() {
    val harness = createHarness()
    harness.insertAll(1, 2, 3)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.hooks.parkLayouts = true
    val background = harness.setSizeBackground(30, 60)
    assertThat(background.isParked).describedAs("setSize lays an item out").isTrue
    assertThat(harness.binderLockFree())
        .describedAs("binder lock free during setSize's layout")
        .isTrue
    harness.onMain {
      harness.binder.insertItemAt(3, harness.item(2))
      harness.binder.notifyChangeSetComplete(
          true,
          RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
      )
    }
    assertThat(harness.mainReleases).isZero
    harness.hooks.parkLayouts = false
    assertThat(harness.quiesceWithEnvironment()).isTrue
    assertThat(harness.binder.measuredWidth).isEqualTo(30)
    harness.assertSettled()
  }

  /**
   * A background measure whose plan keeps changing lays out under the lock after three rounds
   * without progress (the locked binder's behaviour), instead of looping.
   */
  @Test
  fun `a background measure that makes no progress falls back to the locked measure`() {
    val harness = createHarness()
    harness.insertAll(1, 2)
    val background = harness.measureBackground(exactly(20), exactly(60))
    var unlockedParks = 0
    while (background.isParked && harness.binderLockFree()) {
      unlockedParks++
      check(unlockedParks < 10) { "never fell back" }
      // The item it lays out is replaced, so its next round finds nothing it computed.
      harness.onMain {
        harness.binder.updateItemAt(0, harness.item(unlockedParks + 1))
        harness.binder.notifyChangeSetComplete(
            true,
            RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
        )
      }
      background.release()
    }
    assertThat(background.isParked).describedAs("parked under the lock").isTrue
    assertThat(unlockedParks).isEqualTo(4)
    harness.hooks.parkLayouts = false
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /**
   * A state update between a size's unlocked layout and the measure's apply makes that size stale:
   * the measure lays the item out again and returns the size with the new state.
   */
  @Test
  fun `a state update after an item's unlocked layout makes the measure lay it out again`() {
    val harness = createHarness(wrap = true)
    harness.insertAll(1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), atMost(200))
    assertThat(harness.quiesce()).isTrue
    harness.hooks.parkLayouts = true
    val out = Size()
    // Width 30 invalidates both items: the fill lays item 0 out, then parks in item 1's layout.
    val background = harness.measureBackground(exactly(30), atMost(200), out = out)
    val serial0 = harness.serialAt(0)
    val serial1 = harness.serialAt(1)
    while (background.parkedIn != serial1) {
      check(background.isParked) { "background measure did not reach item 1" }
      background.release()
    }
    harness.hooks.parkLayouts = false
    // Item 0's state changes after its unlocked layout.
    harness.onMain { checkNotNull(harness.hooks.states[serial0]).updateSync(1) }
    ShadowLooper.idleMainLooper()
    background.release()
    assertThat(harness.quiesce()).isTrue
    val expected = LockTestSizes.height(1, 30, 1) + LockTestSizes.height(2, 30, 0)
    assertThat(out.height).describedAs("background measure height").isEqualTo(expected)
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /** Two consecutive state updates of an item both show, with and without the flag. */
  @Test
  fun `two consecutive state updates of an item both commit`() {
    for (flag in listOf(true, false)) {
      for (async in listOf(false, true)) {
        val harness = createHarness(flag = flag)
        harness.insertAll(1, 2)
        harness.hooks.parkLayouts = false
        harness.measureMain(exactly(20), exactly(60))
        assertThat(harness.quiesceWithEnvironment()).isTrue
        val serial = harness.serialAt(0)
        val state = checkNotNull(harness.hooks.states[serial])
        harness.onMain {
          if (async) state.update { it + 1 } else state.updateSync { it + 1 }
        }
        // A measure at other specs between the updates (the binder's own request to the tree).
        harness.measureMain(exactly(30), exactly(60))
        harness.onMain {
          if (async) checkNotNull(harness.hooks.states[serial]).update { it + 1 }
          else checkNotNull(harness.hooks.states[serial]).updateSync { it + 1 }
        }
        assertThat(harness.quiesceWithEnvironment()).isTrue
        val tree = checkNotNull(harness.binder.componentTreeHolders[0].componentTree)
        val layout = checkNotNull(tree.getCommittedLayoutState())
        assertThat(layout.height)
            .describedAs("flag=$flag async=$async")
            .isEqualTo(LockTestSizes.height(1, 30, 2))
        harness.assertSettled()
      }
    }
  }

  /**
   * A host LithoView measuring a bound item with its own EXACTLY specs commits that layout, and the
   * binder does not replace it afterwards, as with the locked binder. With a background measure
   * laying the item out at the same time, the binder may lay it out once more, but it settles.
   */
  @Test
  fun `a host view's own exact measure of a bound item stays committed`() {
    val committed = HashMap<String, String>()
    val states = HashMap<String, String>()
    for (flag in listOf(true, false)) {
      for (concurrent in listOf(false, true)) {
        val harness = createHarness(flag = flag)
        harness.insertAll(1, 2)
        harness.hooks.parkLayouts = false
        harness.measureMain(exactly(20), exactly(60))
        assertThat(harness.quiesceWithEnvironment()).isTrue
        var background: LockTestThread? = null
        if (concurrent) {
          harness.hooks.parkLayouts = true
          background = harness.measureBackground(exactly(30), exactly(60))
          assertThat(background.isParked).isTrue
          harness.hooks.parkLayouts = false
        }
        val viewSize = checkNotNull(harness.hostViewMeasure(0, exactly(20), exactly(50)))
        assertThat(viewSize.height).isEqualTo(50)
        assertThat(harness.quiesceWithEnvironment()).isTrue
        val layoutsAfter = harness.hooks.layouts.size
        assertThat(harness.quiesceWithEnvironment()).isTrue
        assertThat(harness.hooks.layouts.size)
            .describedAs("no more layouts once settled")
            .isEqualTo(layoutsAfter)
        val tree = checkNotNull(harness.binder.componentTreeHolders[0].componentTree)
        committed["flag=$flag concurrent=$concurrent"] =
            "${checkNotNull(tree.getCommittedLayoutState()).height}"
        states["flag=$flag concurrent=$concurrent"] = harness.debugState()
        assertThat(harness.failures).isEmpty()
        assertThat(harness.staleItems().filterNot { it.startsWith("item 0 ") }).isEmpty()
      }
    }
    assertThat(committed["flag=false concurrent=false"])
        .describedAs("without the flag")
        .isEqualTo("50")
    assertThat(committed["flag=true concurrent=false"])
        .describedAs("host view's layout with the flag\n${states["flag=true concurrent=false"]}")
        .isEqualTo(committed["flag=false concurrent=false"])
    println("RBLOCK host view committed heights: $committed")
  }

  /**
   * An async insert applied with notifyChangeSetCompleteAsync is laid out on the layout thread, not
   * on main, and only then shown; its layout uses the binder's specs when it runs.
   */
  @Test
  fun `async inserts are laid out off main at the specs current when they run`() {
    val countsAfterNotify = HashMap<Boolean, Int>()
    val mainLayouts = HashMap<Boolean, String>()
    for (flag in listOf(true, false)) {
      val harness = createHarness(flag = flag, asyncInsertQueue = true)
      harness.hooks.parkLayouts = false
      harness.measureMain(exactly(20), exactly(60))
      harness.onMain {
        harness.binder.insertItemAtAsync(0, harness.item(1))
        harness.binder.insertItemAtAsync(1, harness.item(2))
        harness.binder.notifyChangeSetCompleteAsync(
            true,
            RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
        )
      }
      ShadowLooper.idleMainLooper()
      mainLayouts[flag] =
          "${harness.hooks.layouts.count { it.thread == "main" }} at ${harness.hooks.firstMainLayoutStack}"
      countsAfterNotify[flag] = harness.binder.getItemCount()
      // The binder is measured at a new width before the insert layouts run.
      harness.measureMain(exactly(30), exactly(60))
      assertThat(harness.quiesceWithEnvironment()).isTrue
      assertThat(harness.binder.getItemCount()).isEqualTo(2)
      val insertWidths =
          harness.hooks.layouts
              .filter { it.thread == "worker" }
              .map { it.constraints.maxWidth }
              .distinct()
      if (flag)
          assertThat(insertWidths)
              .describedAs("worker layout widths\n${harness.debugState()}")
              .containsOnly(30)
      harness.assertSettled()
    }
    // The locked binder lays the first applied batch's range item out on main here (its initRange
    // in the mutation path); the flag must not add main layouts.
    assertThat(mainLayouts[true]?.substringBefore(" at "))
        .describedAs("main layouts at notify; without the flag: ${mainLayouts[false]}")
        .isEqualTo(mainLayouts[false]?.substringBefore(" at "))
    assertThat(countsAfterNotify[true])
        .describedAs("items shown before their layouts ran")
        .isEqualTo(countsAfterNotify[false])
  }

  /** Items that leave the range before their requested layouts run are not laid out. */
  @Test
  fun `items that left the range are not laid out`() {
    val laidOutByFlag = HashMap<Boolean, Set<Int>>()
    for (flag in listOf(true, false)) {
      val harness = createHarness(flag = flag)
      harness.insertAll(*IntArray(40) { it % 4 })
      harness.hooks.parkLayouts = false
      harness.measureMain(exactly(20), exactly(60))
      ShadowLooper.idleMainLooper()
      harness.onMain { harness.binder.onNewVisibleRange(30, 32) }
      val serials =
          harness.binder.componentTreeHolders.map {
            (it.renderInfo.component as LockTestItem).serial
          }
      val layoutsBefore = harness.hooks.layouts.size
      assertThat(harness.quiesce()).isTrue
      val laidOut =
          harness.hooks.layouts.drop(layoutsBefore).map { serials.indexOf(it.serial) }.toSet()
      laidOutByFlag[flag] = laidOut
      assertThat(laidOut.filter { it < 10 })
          .describedAs("flag=$flag laid out far out of range")
          .isEmpty()
      assertThat(harness.quiesceWithEnvironment()).isTrue
      harness.assertSettled()
    }
    assertThat(laidOutByFlag[true]).isEqualTo(laidOutByFlag[false])
  }

  /**
   * Measures on main and background at different specs, then their sizes match the locked binder.
   */
  @Test
  fun `concurrent measures return the locked binder's sizes once settled`() {
    for (wrap in listOf(false, true)) {
      val sizes = HashMap<Boolean, String>()
      for (flag in listOf(false, true)) {
        val harness = createHarness(flag = flag, wrap = wrap)
        harness.insertAll(1, 2, 3)
        val height = if (wrap) atMost(60) else exactly(60)
        harness.measureBackground(atMost(30), height, withHandler = true)
        harness.hooks.parkLayouts = false
        harness.measureMain(exactly(10), height)
        assertThat(harness.quiesceWithEnvironment { Triple(exactly(10), height, false) }).isTrue
        sizes[flag] = harness.measureMain(exactly(10), height).let { "${it.width}x${it.height}" }
        assertThat(harness.quiesceWithEnvironment()).isTrue
        harness.assertSettled()
      }
      assertThat(sizes[true]).describedAs("wrap=$wrap").isEqualTo(sizes[false])
    }
  }

  private fun LockTestHarness.boundTree(position: Int): ComponentTree {
    var tree: ComponentTree? = null
    onMain { tree = binder.getComponentForStickyHeaderAt(position) }
    return checkNotNull(tree)
  }

  /**
   * Review finding 1: a host view's measure that reuses the committed layout must not record its
   * EXACTLY specs in the tree, or a later state update is laid out at the old exact height and the
   * item cannot grow.
   */
  @Test
  fun `a host view's compatible measure does not pin the item to its exact size`() {
    val heights = HashMap<Boolean, Int>()
    for (flag in listOf(true, false)) {
      val harness = createHarness(flag = flag)
      harness.insertAll(1, 2)
      harness.hooks.parkLayouts = false
      harness.measureMain(exactly(20), exactly(100))
      assertThat(harness.quiesceWithEnvironment()).isTrue
      val tree = harness.boundTree(0)
      val natural = checkNotNull(tree.getCommittedLayoutState()).height
      // The host view measures at the item's own size: compatible, no layout.
      val viewSize = checkNotNull(harness.hostViewMeasure(0, exactly(20), exactly(natural)))
      assertThat(viewSize.height).isEqualTo(natural)
      val serial = harness.serialAt(0)
      harness.onMain { checkNotNull(harness.hooks.states[serial]).updateSync(1) }
      assertThat(harness.quiesce()).isTrue
      heights[flag] = checkNotNull(tree.getCommittedLayoutState()).height
      assertThat(heights[flag]).describedAs("flag=$flag").isEqualTo(LockTestSizes.height(1, 20, 1))
    }
    assertThat(heights[true]).isEqualTo(heights[false])
  }

  /**
   * Review finding 2: a holder request that reuses the committed layout while the host view's own
   * measure layout is running must not make the tree reject the view's layout.
   */
  @Test
  fun `a holder request during a host view's measure layout does not reject the view's layout`() {
    val results = HashMap<Boolean, String>()
    for (flag in listOf(true, false)) {
      val harness = createHarness(flag = flag)
      harness.insertAll(1, 2)
      harness.hooks.parkLayouts = false
      harness.measureMain(exactly(20), exactly(100))
      assertThat(harness.quiesceWithEnvironment()).isTrue
      val tree = harness.boundTree(0)
      val holder = harness.binder.componentTreeHolders[0]
      val serial = harness.serialAt(0)
      val childWidthSpec = harness.binder.getChildWidthSpec(0)
      val childHeightSpec = harness.binder.getChildHeightSpec(0)
      val requester = harness.thread("requester")
      harness.backgrounds.add(requester)
      var requested = false
      // While main lays the item out for the view's measure, a background binder request for the
      // item at its own specs arrives and reuses the committed layout.
      harness.hooks.onMainLayout = { layoutSerial ->
        if (layoutSerial == serial && !requested) {
          requested = true
          requester.run {
            holder.computeLayoutSync(harness.context, childWidthSpec, childHeightSpec, Size())
          }
          check(!requester.busy) { "the holder request waited on the view's layout" }
        }
      }
      val viewSize = checkNotNull(harness.hostViewMeasure(0, exactly(20), exactly(70)))
      harness.hooks.onMainLayout = null
      assertThat(requested).isTrue
      assertThat(harness.quiesce()).isTrue
      val committed = checkNotNull(tree.getCommittedLayoutState())
      results[flag] =
          "view ${viewSize.width}x${viewSize.height} committed ${committed.width}x${committed.height}"
      assertThat(harness.failures).isEmpty()
    }
    println("RBLOCK host view during holder request: $results")
    assertThat(results[true]).isEqualTo(results[false])
  }

  /**
   * Review finding 3: a holder request that reuses the committed layout while a state update's
   * layout runs (its resolve already committed) must not make the tree reject that layout.
   */
  @Test
  fun `a holder request during a state update's layout does not drop the update`() {
    val heights = HashMap<Boolean, Int>()
    for (flag in listOf(true, false)) {
      val harness = createHarness(flag = flag)
      harness.insertAll(1, 2)
      harness.hooks.parkLayouts = false
      harness.measureMain(exactly(20), exactly(100))
      assertThat(harness.quiesceWithEnvironment()).isTrue
      val tree = harness.boundTree(0)
      val holder = harness.binder.componentTreeHolders[0]
      val serial = harness.serialAt(0)
      val oldHeight = checkNotNull(tree.getCommittedLayoutState()).height
      val newHeight = LockTestSizes.height(1, 20, 1)
      assertThat(newHeight).isGreaterThan(oldHeight)
      // The async state update runs on the worker: its resolve commits, then its layout parks.
      harness.hooks.parkLayouts = true
      harness.onMain { checkNotNull(harness.hooks.states[serial]).update(1) }
      // Batched async updates reach the tree on main's next frame.
      ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
      while (!harness.worker.isParked && harness.workerStep()) {}
      check(harness.worker.isParked) { "state update layout did not park\n${harness.debugState()}" }
      harness.hooks.parkLayouts = false
      // A binder request at specs the old layout fits but the new one does not.
      harness.onMain {
        holder.computeLayoutSync(
            harness.context,
            exactly(20),
            atMost(oldHeight + 2),
            Size(),
        )
      }
      harness.worker.release()
      assertThat(harness.quiesce()).isTrue
      val layout = checkNotNull(tree.getCommittedLayoutState())
      heights[flag] = layout.height
      assertThat(layout.height).describedAs("flag=$flag: the update shows").isNotEqualTo(oldHeight)
    }
  }

  /**
   * Review finding 9: an item whose state keeps changing while main measures does not make main lay
   * it out over and over; main recomputes it once and takes the newest size.
   */
  @Test
  fun `main recomputes an item whose state keeps changing only once`() {
    val harness = createHarness(wrap = true)
    harness.insertAll(1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), atMost(200))
    assertThat(harness.quiesce()).isTrue
    val serial = harness.serialAt(0)
    var bumping = true
    val holder0 = harness.binder.componentTreeHolders[0]
    harness.hooks.onMainRequest = { holder, async ->
      // Another thread's state update arrives right before every main request for item 0.
      if (bumping && !async && holder === holder0) {
        val state = checkNotNull(harness.hooks.states[serial])
        val updater = Thread { state.update { it + 1 } }
        updater.start()
        updater.join()
      }
    }
    val layoutsBefore = harness.hooks.layoutsOf(serial).count { it.thread == "main" }
    harness.measureMain(exactly(30), atMost(200))
    bumping = false
    harness.hooks.onMainRequest = null
    val mainLayouts = harness.hooks.layoutsOf(serial).count { it.thread == "main" } - layoutsBefore
    assertThat(mainLayouts)
        .describedAs("main layouts of item 0 in one measure")
        .isLessThanOrEqualTo(3)
    assertThat(harness.hooks.syncLayoutsUnderBinderLock.get()).isZero
    harness.stop()
  }

  /**
   * Review finding 4: a state update enqueued during an item's own unlocked layout is missing from
   * that size, so the measure lays the item out again.
   */
  @Test
  fun `a state update during an item's unlocked layout makes the measure lay it out again`() {
    val harness = createHarness(wrap = true)
    harness.insertAll(1, 2)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), atMost(200))
    assertThat(harness.quiesce()).isTrue
    harness.hooks.parkLayouts = true
    val out = Size()
    val serial0 = harness.serialAt(0)
    val background = harness.measureBackground(exactly(30), atMost(200), out = out)
    check(background.parkedIn == serial0) { "parked in ${background.parkedIn}" }
    // Item 0's state changes while its layout runs (after its resolve).
    harness.onMain { checkNotNull(harness.hooks.states[serial0]).update(1) }
    harness.hooks.parkLayouts = false
    background.release()
    assertThat(harness.quiesce()).isTrue
    val expected = LockTestSizes.height(1, 30, 1) + LockTestSizes.height(2, 30, 0)
    assertThat(out.height).describedAs("background measure height").isEqualTo(expected)
  }

  /**
   * Two measures at different widths: main's request for its own width overtakes the background
   * measure's unlocked layout of the range item, so the item tree drops that layout, and the
   * background measure applies last. Before applying, the background measure lays the item out
   * again at its width, so the item is committed at the specs the binder binds it with: binding it
   * and measuring its host view do not lay it out on main, even while the layout thread lags
   * behind.
   */
  @Test
  fun `a measure whose unlocked layout another request overtook lays it out again`() {
    val harness = createHarness()
    harness.insertAll(1, 2, 3)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    val serial0 = harness.serialAt(0)
    harness.hooks.parkLayouts = true
    val background = harness.measureBackground(exactly(30), exactly(60))
    assertThat(background.parkedIn).isEqualTo(serial0)
    harness.hooks.parkLayouts = false
    // Main's measure lays item 0 out at its own width while the background layout runs.
    harness.measureMain(exactly(10), exactly(60))
    background.release()
    assertThat(background.busy).describedAs("background measure done").isFalse
    assertThat(harness.binder.measuredWidth).isEqualTo(30)
    val widthSpec = harness.binder.getChildWidthSpec(0)
    val heightSpec = harness.binder.getChildHeightSpec(0)
    val tree = checkNotNull(harness.binder.componentTreeHolders[0].componentTree)
    assertThat(tree.hasCompatibleLayout(widthSpec, heightSpec))
        .describedAs("item 0 committed at the binder's specs\n${harness.debugState()}")
        .isTrue
    // RecyclerView binds item 0 and measures its host view, before the layout thread runs.
    val mainLayoutsBefore = harness.hooks.layoutsOf(serial0).count { it.thread == "main" }
    harness.hostViewMeasure(0, widthSpec, heightSpec)
    assertThat(harness.hooks.layoutsOf(serial0).count { it.thread == "main" } - mainLayoutsBefore)
        .describedAs(
            "main layouts of item 0 in bind and host view measure\n${harness.debugState()}",
        )
        .isZero
    assertThat(harness.hooks.syncLayoutsUnderBinderLock.get()).isZero
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /**
   * Two background measures at different widths whose layouts of the range item keep overtaking
   * each other: each lays the item out again once, then takes its size as before, so neither loops
   * and neither falls back to laying out under the lock.
   */
  @Test
  fun `measures at different widths do not chase each other's layouts`() {
    val harness = createHarness()
    harness.insertAll(1, 2, 3)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    val serial0 = harness.serialAt(0)
    harness.hooks.parkLayouts = true
    val first = harness.measureBackground(exactly(30), exactly(60))
    val second = harness.measureBackground(exactly(10), exactly(60))
    assertThat(first.parkedIn).isEqualTo(serial0)
    assertThat(second.parkedIn).isEqualTo(serial0)
    var releases = 0
    // Alternate: each layout finishes after the other measure's request for other specs.
    while (first.busy || second.busy) {
      check(++releases <= 12) { "measures keep laying item 0 out\n${harness.debugState()}" }
      val next = if (releases % 2 == 1) first else second
      if (next.isParked) next.release() else (if (next === first) second else first).release()
    }
    val backgroundLayouts =
        harness.hooks.layoutsOf(serial0).count { it.thread.startsWith("background") }
    assertThat(backgroundLayouts).describedAs("item 0 layouts, two measures").isLessThanOrEqualTo(4)
    assertThat(harness.hooks.syncLayoutsUnderBinderLock.get()).isZero
    harness.hooks.parkLayouts = false
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /**
   * Main's setSize and measure at one width keep overtaking a background measure's layouts of the
   * same item at another width: the background measure lays the item out again once, then takes its
   * size, so it neither loops nor falls back to the locked measure, and main never waits for it.
   */
  @Test
  fun `main measures at another width do not make a background measure loop`() {
    val harness = createHarness()
    harness.insertAll(1, 2, 3)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), exactly(60))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    val serial0 = harness.serialAt(0)
    harness.hooks.parkLayouts = true
    val background = harness.measureBackground(exactly(10), exactly(60))
    var mainCalls = 0
    while (background.busy) {
      check(++mainCalls <= 6) {
        "background measure keeps laying item 0 out\n${harness.debugState()}"
      }
      assertThat(background.parkedIn).isEqualTo(serial0)
      // Alternately main's setSize and measure at width 20, while the background layout runs.
      harness.onMain { if (mainCalls % 2 == 1) harness.binder.setSize(20, 60) }
      if (mainCalls % 2 == 0) harness.measureMain(exactly(20), exactly(60))
      background.release()
    }
    assertThat(harness.hooks.layoutsOf(serial0).count { it.thread == background.name })
        .describedAs("background layouts of item 0")
        .isLessThanOrEqualTo(2)
    assertThat(harness.mainReleases).describedAs("main waits").isZero
    assertThat(harness.hooks.syncLayoutsUnderBinderLock.get()).isZero
    harness.hooks.parkLayouts = false
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }

  /**
   * Measures on one thread at a time lay out exactly what the locked binder lays out: the check
   * that an item's layout is committed costs no layout when nothing overtakes it.
   */
  @Test
  fun `measures on one thread at a time lay out the same items as the locked binder`() {
    for (wrap in listOf(false, true)) {
      val layouts = HashMap<Boolean, List<String>>()
      for (flag in listOf(false, true)) {
        val harness = createHarness(flag = flag, wrap = wrap)
        harness.hooks.parkLayouts = false
        harness.insertAll(1, 2, 3, 4)
        val height = if (wrap) atMost(200) else exactly(60)
        harness.measureMain(exactly(20), height)
        assertThat(harness.quiesceWithEnvironment()).isTrue
        val background = harness.measureBackground(exactly(30), height)
        assertThat(background.busy).isFalse
        harness.measureMain(exactly(10), height)
        harness.onMain {
          harness.binder.updateItemAt(1, harness.item(5))
          harness.binder.notifyChangeSetComplete(
              true,
              RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
          )
        }
        harness.measureMain(exactly(25), height)
        assertThat(harness.quiesceWithEnvironment()).isTrue
        harness.assertSettled()
        layouts[flag] =
            synchronized(harness.hooks.layouts) {
              harness.hooks.layouts.map { "${it.serial}@${it.constraints.maxWidth}" }
            }
      }
      assertThat(layouts[true]).describedAs("wrap=$wrap").isEqualTo(layouts[false])
    }
  }

  private class CrashingItem : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive = error("crashing item")
  }

  /**
   * An item tree still takes unversioned root changes: an error boundary's fallback (the tree's
   * `onErrorComponent` calls `setRoot`) and an app's own `setRoot` / `setRootAsync`.
   */
  @Test
  fun `an item tree takes unversioned root changes`() {
    val results = HashMap<Boolean, String>()
    for (flag in listOf(true, false)) {
      val errors = ArrayList<String>()
      lateinit var fallbackHooks: LockTestHooks
      val harness = LockTestHarness(
          context,
          flag,
          errorEventHandler =
              object : ErrorEventHandler() {
                override fun onError(context: ComponentContext, exception: Exception): Component {
                  errors.add(exception.message.orEmpty())
                  return LockTestItem(999, 2, fallbackHooks)
                }
              },
      )
          .also { harnesses.add(it) }
      fallbackHooks = harness.hooks
      harness.hooks.parkLayouts = false
      harness.onMain {
        harness.binder.insertRangeAt(
            0,
            listOf(harness.item(1), ComponentRenderInfo.create().component(CrashingItem()).build()),
        )
        harness.binder.notifyChangeSetComplete(
            true,
            RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK,
        )
      }
      // The crashing item is laid out by the binder's own (holder) requests.
      harness.measureMain(exactly(20), exactly(100))
      assertThat(harness.quiesceWithEnvironment()).isTrue
      assertThat(harness.failures).isEmpty()
      val crashingTree = checkNotNull(harness.binder.componentTreeHolders[1].componentTree)
      val fallback =
          (crashingTree.getCommittedLayoutState()?.rootComponent as? LockTestItem)?.serial
      // An app's own root changes on an item tree the binder laid out.
      val tree = harness.boundTree(0)
      harness.onMain {
        tree.setRoot(LockTestItem(500, 3, harness.hooks))
        tree.setRootAsync(LockTestItem(501, 3, harness.hooks))
      }
      assertThat(harness.quiesce()).isTrue
      // The binder's next request for that item still works.
      harness.measureMain(exactly(30), exactly(100))
      assertThat(harness.quiesceWithEnvironment()).isTrue
      assertThat(harness.failures).isEmpty()
      results[flag] = "errors=${errors.isNotEmpty()} fallback=$fallback"
    }
    assertThat(results[false]).startsWith("errors=true")
    assertThat(results[true]).isEqualTo(results[false])
  }

  /**
   * The startup logger's first layout points bracket the binder's first item layout, which the
   * flag's binder does outside the lock (TTRC traces read these points).
   */
  @Test
  fun `the first layout points bracket the range item's layout`() {
    val spans = HashMap<Boolean, String>()
    for (flag in listOf(true, false)) {
      val marks = ArrayList<Pair<String, Int>>()
      lateinit var hooksRef: LockTestHooks
      val logger =
          object : LithoStartupLogger() {
            override fun onMarkPoint(name: String) {
              marks.add(name to hooksRef.layouts.size)
            }

            override val isEnabled: Boolean = true
          }
      val harness =
          LockTestHarness(context, flag, startupLogger = logger).also { harnesses.add(it) }
      hooksRef = harness.hooks
      harness.hooks.parkLayouts = false
      harness.insertAll(1, 2, 3)
      harness.measureBackground(exactly(20), exactly(60))
      assertThat(harness.quiesce()).isTrue
      val start = marks.firstOrNull { it.first.endsWith("_firstlayout_start") }
      val end = marks.firstOrNull { it.first.endsWith("_firstlayout_end") }
      val firstItemLayout = harness.hooks.layouts.indexOfFirst { it.serial == 1 }
      spans[flag] = "start=${start?.first} end=${end?.first}"
      assertThat(start).describedAs("flag=$flag marks $marks").isNotNull
      assertThat(end).describedAs("flag=$flag marks $marks").isNotNull
      // The item's layout is recorded while it runs: after START, before END.
      assertThat(firstItemLayout).isGreaterThanOrEqualTo(checkNotNull(start).second)
      assertThat(firstItemLayout).isLessThan(checkNotNull(end).second)
    }
    assertThat(spans[true]).isEqualTo(spans[false])
  }

  /**
   * A root another caller sets on an item tree (an app's setRoot, an error boundary's fallback)
   * after the measure laid the item out is not the item: the measure lays the item's component out
   * again instead of taking that root's size.
   */
  @Test
  fun `a measure does not take the size of a root another caller set on the item tree`() {
    val harness = createHarness(wrap = true)
    harness.insertAll(1)
    harness.hooks.parkLayouts = false
    harness.measureMain(exactly(20), atMost(200))
    assertThat(harness.quiesceWithEnvironment()).isTrue
    val serial = harness.serialAt(0)
    val tree = checkNotNull(harness.binder.componentTreeHolders[0].componentTree)
    harness.hooks.parkLayouts = true
    val out = Size()
    val background = harness.measureBackground(exactly(30), atMost(200), out = out)
    assertThat(background.parkedIn).isEqualTo(serial)
    harness.hooks.parkLayouts = false
    synchronized(harness.binder) {
      background.release()
      harness.waitUntilBlockedOnBinder(background)
      tree.setRoot(LockTestItem(999, 3, harness.hooks))
    }
    background.waitUntilDone()
    assertThat(out.height)
        .describedAs("background measure height\n${harness.debugState()}")
        .isEqualTo(LockTestSizes.height(1, 30, 0))
    assertThat((tree.getCommittedLayoutState()?.rootComponent as? LockTestItem)?.serial)
        .isEqualTo(serial)
  }

  /**
   * As initRange does, the next item's layout is requested before the range item's layout runs, so
   * the two overlap.
   */
  @Test
  fun `the init range requests the next item before laying out the range item`() {
    for (flag in listOf(true, false)) {
      val harness = createHarness(flag = flag)
      harness.insertAll(1, 2, 3)
      val background = harness.measureBackground(exactly(20), exactly(60))
      // Items 1, 2, 3 have serials 1, 2, 3 (no binder getter here: the locked binder holds its
      // lock).
      assertThat(background.parkedIn).describedAs("flag=$flag").isEqualTo(1)
      assertThat(harness.hooks.requests.filter { it.startsWith("async 2 ") })
          .describedAs("flag=$flag requests ${harness.hooks.requests}")
          .isNotEmpty
      harness.hooks.parkLayouts = false
      assertThat(harness.quiesceWithEnvironment()).isTrue
      harness.assertSettled()
    }
  }

  /**
   * The init range's async layouts that a dry run starts keep the dry run's specs, even when one
   * completes before the measure's apply has set the binder's: no item is laid out at an
   * unspecified width in a list of exact width.
   */
  @Test
  fun `init range layouts started by the dry run keep its specs`() {
    val harness = createHarness()
    harness.insertAll(1, 2, 3, 0, 1)
    val background = harness.measureBackground(exactly(20), exactly(200))
    // Parked in item 0's layout, before the apply; the layout thread runs the next items' layouts.
    assertThat(background.parkedIn).isEqualTo(1)
    repeat(4) {
      while (harness.worker.isParked) harness.worker.release()
      harness.workerStep()
    }
    while (harness.worker.isParked) harness.worker.release()
    val widths =
        harness.hooks.layouts.filter { it.thread == "worker" }.map { it.constraints.maxWidth }
    assertThat(widths).describedAs("worker layout widths").isNotEmpty.containsOnly(20)
    harness.hooks.parkLayouts = false
    assertThat(harness.quiesceWithEnvironment()).isTrue
    harness.assertSettled()
  }
}
