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

package com.facebook.litho.rangemanager

import android.content.Context
import android.os.Looper
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.BackgroundLayoutLooperRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.SizeConstraints
import com.meta.rangemanager.CollectionItemProvider
import com.meta.rangemanager.KeyBasedRange
import com.meta.rangemanager.KeyBasedRangeWorker
import com.meta.rangemanager.PreparableCollectionItem
import com.meta.rangemanager.PrepareScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * With [StaleContentAtBind.ALLOWED], a view new to the tree shows the tree's committed layout, and
 * data that changed since is laid out in the background once the frame is done.
 */
@OptIn(AllowsStaleContentOnScreen::class)
@RunWith(LithoTestRunner::class)
class LithoCollectionItemUpdateAfterFrameTest {

  @JvmField @Rule val backgroundLayoutLooperRule = BackgroundLayoutLooperRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val scope = PrepareScope()
  private val built = mutableListOf<Component>()
  private var title = "first"
  private val item = LithoCollectionItem(
      context = context,
      dataProvider = { title },
      component = { Row().also { built += it } },
      sizeConstraints = SizeConstraints.exact(SIZE_PX, SIZE_PX),
      traceTag = "test",
      staleContentAtBind = StaleContentAtBind.ALLOWED,
  )
  private val worker = KeyBasedRangeWorker(
      object : CollectionItemProvider<String>(scope = scope) {
        override fun create(key: String): PreparableCollectionItem = item

        override fun contains(key: String): Boolean = true
      },
      scope,
  )

  /**
   * A list measures and lays out a row in the frame that binds it: that pass must find a layout to
   * show.
   */
  @Test
  fun `a view new to the tree keeps the prepared layout through its measure and layout, then shows the change`() {
    prepareAndLayOut()
    val prepared = built.single()
    title = "second"

    val view = LithoView(context)
    item.bind(view)
    measureAndLayOut(view)

    assertThat(shows(view, prepared)).isTrue
    assertThat(built).containsExactly(prepared)

    finishFrameAndBackgroundLayout()

    val changed = built.last()
    assertThat(changed).isNotSameAs(prepared)
    assertThat(shows(view, changed)).isTrue
  }

  /** Otherwise the view's measure would lay the older data out on the main thread first. */
  @Test
  fun `a change that needs a size nothing is laid out at is laid out during the bind`() {
    val item = itemSizedByData()
    workerFor(item).prepareKey(KEY)
    finishFrameAndBackgroundLayout()
    title = "second"

    val view = LithoView(context)
    item.bind(view)

    assertThat(built).hasSize(2)
    assertThat(committedLayoutIsFor(view, built.last())).isTrue
  }

  @Test
  fun `an item released before its update runs builds nothing`() {
    worker.schedule(Unit, Keys(KEY))
    finishFrameAndBackgroundLayout()
    title = "second"
    val view = LithoView(context)
    item.bind(view)

    worker.schedule(Unit, Keys())
    item.unbind()
    finishFrameAndBackgroundLayout()

    assertThat(built).hasSize(1)
  }

  /** An item that keeps its tree for the view it is bound to must still drop the update. */
  @Test
  fun `an item released while shown before its update runs builds nothing`() {
    val item = LithoCollectionItem(
        context = context,
        dataProvider = { title },
        component = { Row().also { built += it } },
        sizeConstraints = SizeConstraints.exact(SIZE_PX, SIZE_PX),
        traceTag = "test",
        keepTreeWhileShown = true,
        staleContentAtBind = StaleContentAtBind.ALLOWED,
    )
    val worker = workerFor(item)
    worker.schedule(Unit, Keys(KEY))
    finishFrameAndBackgroundLayout()
    title = "second"
    item.bind(LithoView(context))

    worker.schedule(Unit, Keys())
    finishFrameAndBackgroundLayout()

    assertThat(built).hasSize(1)
  }

  @Test
  fun `a bind with unchanged data lays out nothing`() {
    prepareAndLayOut()

    item.bind(LithoView(context))
    finishFrameAndBackgroundLayout()

    assertThat(built).hasSize(1)
  }

  /**
   * At startup a row often binds before its first background layout lands. With unchanged data an
   * [StaleContentAtBind.ALLOWED] bind does what a [StaleContentAtBind.NEVER] bind does: it neither
   * renders on the main thread nor waits for that layout. The view's measure and layout still wait
   * for it, in both modes.
   */
  @Test
  fun `a bind with unchanged data leaves a layout still in the background to finish there, as NEVER does`() {
    for (mode in listOf(StaleContentAtBind.NEVER, StaleContentAtBind.ALLOWED)) {
      val renders = mutableListOf<Boolean>()
      val gate = CountDownLatch(1)
      val item = LithoCollectionItem(
          context = context,
          dataProvider = { title },
          component = { GatedRow(renders, gate) },
          sizeConstraints = SizeConstraints.exact(SIZE_PX, SIZE_PX),
          traceTag = "test",
          staleContentAtBind = mode,
      )
      workerFor(item).prepareKey(KEY)

      val bindStartNanos = System.nanoTime()
      item.bind(LithoView(context))
      val bindMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bindStartNanos)
      gate.countDown()

      assertThat(synchronized(renders) { renders.toList() })
          .describedAs("$mode")
          .doesNotContain(true)
      assertThat(bindMillis).describedAs("$mode").isLessThan(GATE_TIMEOUT_MS / 2)
    }
  }

  /** Renders off the main thread wait for [gate], so the test controls when they finish. */
  private class GatedRow(
      private val renders: MutableList<Boolean>,
      private val gate: CountDownLatch,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val onMainThread = Looper.myLooper() == Looper.getMainLooper()
      synchronized(renders) { renders += onMainThread }
      if (!onMainThread) {
        gate.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      }
      return EmptyComponent()
    }
  }

  /**
   * A view already showing the tree has a layout and owns its size, whatever the new data needs.
   */
  @Test
  fun `a view already showing the tree keeps its layout when the change needs another size`() {
    val item = itemSizedByData()
    workerFor(item).prepareKey(KEY)
    finishFrameAndBackgroundLayout()
    val view = LithoView(context)
    item.bind(view)
    measureAndLayOut(view)
    finishFrameAndBackgroundLayout()
    val prepared = built.single()

    title = "second"
    item.bind(view)

    assertThat(built).containsExactly(prepared)

    finishFrameAndBackgroundLayout()

    assertThat(built.last()).isNotSameAs(prepared)
  }

  /** An item whose data "first" is laid out at 100 x 100, and any other data at 100 x 200. */
  private fun itemSizedByData(): LithoCollectionItem<String> = LithoCollectionItem(
      context = context,
      dataProvider = { title },
      component = { Row().also { built += it } },
      sizeConstraints = SizeConstraints.exact(SIZE_PX, SIZE_PX),
      sizeConstraintsProvider = { data ->
        if (data == "first") {
          SizeConstraints.exact(SIZE_PX, SIZE_PX)
        } else {
          SizeConstraints.exact(SIZE_PX, SIZE_PX * 2)
        }
      },
      traceTag = "test",
      staleContentAtBind = StaleContentAtBind.ALLOWED,
  )

  private fun workerFor(
      item: LithoCollectionItem<String>,
  ): KeyBasedRangeWorker<String> = KeyBasedRangeWorker(
      object : CollectionItemProvider<String>(scope = scope) {
        override fun create(key: String): PreparableCollectionItem = item

        override fun contains(key: String): Boolean = true
      },
      scope,
  )

  private fun prepareAndLayOut() {
    worker.prepareKey(KEY)
    finishFrameAndBackgroundLayout()
  }

  private fun finishFrameAndBackgroundLayout() {
    shadowOf(Looper.getMainLooper()).idle()
    backgroundLayoutLooperRule.runToEndOfTasksSync()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun committedLayoutIsFor(view: LithoView, component: Component): Boolean =
      checkNotNull(checkNotNull(view.componentTree).getCommittedLayoutState())
          .isForComponentId(component.instanceId)

  private fun shows(view: LithoView, component: Component): Boolean =
      checkNotNull(checkNotNull(view.componentTree).mainThreadLayoutState)
          .isForComponentId(component.instanceId)

  /** With EXACTLY x EXACTLY specs, [LithoView] runs the tree's measure from the layout pass. */
  private fun measureAndLayOut(view: LithoView) {
    view.measure(exactly(SIZE_PX), exactly(SIZE_PX))
    view.layout(0, 0, SIZE_PX, SIZE_PX)
  }

  private fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

  private class Keys(vararg keys: String) : KeyBasedRange<String> {
    override val keys: List<String> = keys.toList()
  }

  private companion object {
    const val KEY = "row"
    const val SIZE_PX = 100
    const val GATE_TIMEOUT_MS = 4_000L
  }
}
