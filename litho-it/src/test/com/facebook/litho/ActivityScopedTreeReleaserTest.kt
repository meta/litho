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

import android.app.Activity
import android.app.Application
import android.content.MutableContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.SimpleMountSpecTester
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

@RunWith(LithoTestRunner::class)
class ActivityScopedTreeReleaserTest {

  private val application: Application = ApplicationProvider.getApplicationContext()
  private val previousErrorReporter = ErrorReporter.instance
  private val reports = mutableListOf<Report>()

  @Before
  fun setUp() {
    ErrorReporter.provide { _, categoryKey, message, cause, _, _ ->
      reports += Report(categoryKey, message, cause)
    }
    ActivityScopedTreeReleaser.install(application)
  }

  @After
  fun tearDown() {
    ActivityScopedTreeReleaser.reset(application)
    ErrorReporter.provide(previousErrorReporter)
  }

  @Test
  fun `releases the trees of an activity a while after it is destroyed`() {
    val activity = createActivity()
    val tree = createTree(activity.get())
    val lithoView = LithoView(activity.get()).apply { componentTree = tree }

    activity.destroy()
    assertThat(tree.isReleased).isFalse()

    idlePastDelay()
    assertThat(tree.isReleased).isTrue()
    assertThat(lithoView.componentTree).isNull()
  }

  @Test
  fun `releases a tree built after its activity was destroyed`() {
    val activity = createActivity()
    activity.destroy()
    idlePastDelay()

    val tree = createTree(activity.get())
    idlePastDelay()

    assertThat(tree.isReleased).isTrue()
  }

  @Test
  fun `keeps the trees of other activities`() {
    val destroyed = createActivity()
    val alive = createActivity()
    val destroyedTree = createTree(destroyed.get())
    val aliveTree = createTree(alive.get())

    destroyed.destroy()
    idlePastDelay()

    assertThat(destroyedTree.isReleased).isTrue()
    assertThat(aliveTree.isReleased).isFalse()
  }

  @Test
  fun `keeps trees built with the application context`() {
    val activity = createActivity()
    val tree = ComponentTree.create(ComponentContext(application), SimpleMountSpecTester()).build()

    activity.destroy()
    idlePastDelay()

    assertThat(tree.isReleased).isFalse()
  }

  @Test
  fun `keeps trees built through a MutableContextWrapper`() {
    val activity = createActivity()
    val wrappedTree =
        ComponentTree.create(
                ComponentContext(MutableContextWrapper(activity.get())),
                SimpleMountSpecTester(),
            )
            .build()
    val directTree = createTree(activity.get())

    activity.destroy()
    idlePastDelay()

    assertThat(wrappedTree.isReleased).isFalse()
    assertThat(directTree.isReleased).isTrue()
  }

  @Test
  fun `keeps all trees of a destroyed activity, including later ones, once one is shown`() {
    val destroyed = createActivity()
    val shownTree = createTree(destroyed.get())
    val otherTree = createTree(destroyed.get())
    val host = Robolectric.buildActivity(Activity::class.java).setup()
    host.get().setContentView(LithoView(destroyed.get()).apply { componentTree = shownTree })

    destroyed.destroy()
    idlePastDelay()
    val laterTree = createTree(destroyed.get())
    idlePastDelay()

    assertThat(shownTree.isReleased).isFalse()
    assertThat(otherTree.isReleased).isFalse()
    assertThat(laterTree.isReleased).isFalse()
    assertThat(reportsIn(ActivityScopedTreeReleaser.TREES_KEPT_CATEGORY)).hasSize(1)
  }

  @Test
  fun `releases a tree whose effect cleanup throws and reports it once`() {
    val activity = createActivity()
    val attaches = AtomicInteger()
    val cleanups = AtomicInteger()
    val tree = ComponentTree.create(ComponentContext(activity.get())).build()
    tree.setRootAndSizeSpecSync(CleanupThrows(attaches, cleanups), exactly(100), exactly(100))
    assertThat(attaches.get()).isEqualTo(1)

    activity.destroy()
    idlePastDelay()

    assertThat(tree.isReleased).isTrue()
    assertThat(tree.uiTreeState).isNull()
    assertThat(cleanups.get()).isEqualTo(1)
    val failures = reportsIn(ActivityScopedTreeReleaser.RELEASE_FAILED_CATEGORY)
    assertThat(failures).hasSize(1)
    assertThat(failures[0].message).contains("CleanupThrows").contains("released on retry")
    assertThat(failures[0].cause)
        .isInstanceOf(AssertionError::class.java)
        .hasMessage("cleanup failed")
  }

  @Test
  fun `releases one tree per slice with no budget, a slice delay apart`() {
    reinstall(releaseInSlices = true, sliceBudgetNs = 0, sliceDelayMs = 100)
    val activity = createActivity()
    val first = createTree(activity.get())
    val second = createTree(activity.get())

    activity.destroy()
    idlePastDelay()
    assertThat(first.isReleased).isTrue()
    assertThat(second.isReleased).isFalse()

    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    assertThat(second.isReleased).isTrue()
  }

  @Test
  fun `releases all trees at once when not in slices`() {
    reinstall(releaseInSlices = false, sliceBudgetNs = 0, sliceDelayMs = 100)
    val activity = createActivity()
    val first = createTree(activity.get())
    val second = createTree(activity.get())

    activity.destroy()
    idlePastDelay()

    assertThat(first.isReleased).isTrue()
    assertThat(second.isReleased).isTrue()
  }

  @Test
  fun `waits the configured delay before releasing`() {
    ActivityScopedTreeReleaser.reset(application)
    ActivityScopedTreeReleaser.install(application, releaseDelayMs = 100)
    val activity = createActivity()
    val tree = createTree(activity.get())

    activity.destroy()
    ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
    assertThat(tree.isReleased).isFalse()

    ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
    assertThat(tree.isReleased).isTrue()
  }

  @Test
  fun `releases nothing when not installed`() {
    ActivityScopedTreeReleaser.reset(application)
    val activity = createActivity()
    val tree = createTree(activity.get())

    activity.destroy()
    idlePastDelay()

    assertThat(tree.isReleased).isFalse()
  }

  private fun idlePastDelay() {
    ShadowLooper.idleMainLooper(
        ActivityScopedTreeReleaser.RELEASE_DELAY_MS + 1,
        TimeUnit.MILLISECONDS,
    )
  }

  private fun reinstall(releaseInSlices: Boolean, sliceBudgetNs: Long, sliceDelayMs: Long) {
    ActivityScopedTreeReleaser.reset(application)
    ActivityScopedTreeReleaser.install(
        application,
        releaseInSlices = releaseInSlices,
        sliceBudgetNs = sliceBudgetNs,
        sliceDelayMs = sliceDelayMs,
    )
  }

  private fun reportsIn(category: String): List<Report> = reports.filter { it.category == category }

  private fun createActivity(): ActivityController<Activity> =
      Robolectric.buildActivity(Activity::class.java).create()

  private fun createTree(activity: Activity): ComponentTree =
      ComponentTree.create(ComponentContext(activity), SimpleMountSpecTester()).build()

  private data class Report(val category: String, val message: String, val cause: Throwable?)

  private class CleanupThrows(
      private val attaches: AtomicInteger,
      private val cleanups: AtomicInteger,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(Unit) {
        attaches.incrementAndGet()
        onCleanup {
          cleanups.incrementAndGet()
          throw AssertionError("cleanup failed")
        }
      }
      return SimpleMountSpecTester()
    }
  }
}
