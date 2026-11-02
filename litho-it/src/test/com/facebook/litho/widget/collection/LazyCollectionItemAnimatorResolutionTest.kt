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

package com.facebook.litho.widget.collection

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.widget.LazyCollectionItemAnimator
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.dp
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

// PAUSED, like a device: RecyclerView's postOnAnimation must not run in the middle of a
// changeset's notifications.
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LazyCollectionItemAnimatorResolutionTest {

  @get:Rule val legacyFlagOn = rule(enabled = true, newCollection = false)
  @get:Rule val newFlagOn = rule(enabled = true, newCollection = true)
  @get:Rule val legacyFlagOff = rule(enabled = false, newCollection = false)
  @get:Rule val newFlagOff = rule(enabled = false, newCollection = true)

  private val flagOnRules: List<LithoTestRule>
    get() = listOf(legacyFlagOn, newFlagOn)

  @Test
  fun `flag off keeps lists without an animator`() {
    for (rule in listOf(legacyFlagOff, newFlagOff)) {
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = true) }

      assertThat(lithoView.itemAnimator()).isNull()
    }
  }

  @Test
  fun `flag on animates lists whose children all have ids`() {
    for (rule in flagOnRules) {
      val animator = rule.render(widthPx = 100, heightPx = 100) { list(ids = true) }.itemAnimator()

      assertThat(animator).isInstanceOf(LazyCollectionItemAnimator::class.java)
      assertThat(animator).isNotSameAs(LazyCollectionItemAnimator.Default)
    }
  }

  @Test
  fun `flag on keeps lists with a generated id without an animator`() {
    for (rule in flagOnRules) {
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = false) }

      assertThat(lithoView.itemAnimator()).isNull()
    }
  }

  @Test
  fun `a list animates once all its children have ids`() {
    for (rule in flagOnRules) {
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = false) }

      lithoView.setRoot { list(ids = true) }
      rule.idle()

      assertThat(lithoView.itemAnimator()).isInstanceOf(LazyCollectionItemAnimator::class.java)
    }
  }

  @Test
  fun `changing the animator does not remount the list`() {
    // Legacy only: the new backend keeps its adapter and layout manager in their own binders, so
    // its
    // rows are never detached by a mount binder rebind (see the padding control below).
    val lithoView =
        legacyFlagOn.render(widthPx = 100, heightPx = 100) {
          list(ids = true, itemAnimator = DefaultItemAnimator())
        }
    lithoView.settle(legacyFlagOn)
    val detachedRows = lithoView.countDetachedRows()

    lithoView.setRoot { list(ids = true, itemAnimator = OtherItemAnimator()) }
    lithoView.settle(legacyFlagOn)

    assertThat(lithoView.itemAnimator()).isInstanceOf(OtherItemAnimator::class.java)
    assertThat(detachedRows.get()).isEqualTo(0)
  }

  @Test
  fun `a new animator instance of the same class is picked up when padding changes`() {
    for (rule in flagOnRules) {
      val lithoView =
          rule.render(widthPx = 100, heightPx = 100) {
            list(ids = true, itemAnimator = DurationAnimator(100))
          }
      lithoView.settle(rule)

      lithoView.setRoot { list(ids = true, itemAnimator = DurationAnimator(900), topPadding = 4) }
      lithoView.settle(rule)

      assertThat(lithoView.itemAnimator()?.addDuration).isEqualTo(900)
    }
  }

  @Test
  fun `changing the animator swaps it on the new backend`() {
    val lithoView =
        newFlagOn.render(widthPx = 100, heightPx = 100) {
          list(ids = true, itemAnimator = DefaultItemAnimator())
        }

    lithoView.setRoot { list(ids = true, itemAnimator = OtherItemAnimator()) }
    newFlagOn.idle()

    assertThat(lithoView.itemAnimator()).isInstanceOf(OtherItemAnimator::class.java)
  }

  @Test
  fun `changing the padding remounts the list`() {
    // Control for the test above: a change that goes through the mount binder detaches the rows.
    val lithoView = legacyFlagOn.render(widthPx = 100, heightPx = 100) { list(ids = true) }
    lithoView.settle(legacyFlagOn)
    val detachedRows = lithoView.countDetachedRows()

    lithoView.setRoot { list(ids = true, topPadding = 4) }
    lithoView.settle(legacyFlagOn)

    assertThat(detachedRows.get()).isGreaterThan(0)
  }

  @Test
  fun `remounting the list mid-animation ends it without fading the rows in again`() {
    val lithoView = legacyFlagOn.render(widthPx = 100, heightPx = 100) { list(ids = true) }
    lithoView.settle(legacyFlagOn)
    val animator = lithoView.itemAnimator()

    // Remove a row and stop as soon as its animation is in flight, before a frame advances it.
    lithoView.setRoot { list(ids = true, rows = 1 until 5) }
    var tasks = 0
    while (animator?.isRunning != true && tasks++ < 100) {
      legacyFlagOn.finishAsyncUpdates()
      ShadowLooper.shadowMainLooper().runOneTask()
    }
    assertThat(animator?.isRunning).isTrue()

    // A padding change goes through the mount binder, which detaches the adapter and every row.
    // Check in the same main-thread task, before a frame could finish the removal on its own.
    val detachedRows = lithoView.countDetachedRows()
    lithoView.setRoot { list(ids = true, rows = 1 until 5, topPadding = 4) }
    tasks = 0
    while (detachedRows.get() < 4 && tasks++ < 100) {
      legacyFlagOn.finishAsyncUpdates()
      ShadowLooper.shadowMainLooper().runOneTask()
    }
    assertThat(detachedRows.get()).isGreaterThanOrEqualTo(4)
    val recyclerView = lithoView.lithoView.findViewWithTag<SectionsRecyclerView>(TAG).recyclerView
    assertThat(recyclerView.children()).hasSize(4).allMatch { it.alpha == 1f }

    lithoView.settle(legacyFlagOn)
    assertThat(animator?.isRunning).isFalse()
    // The remount re-applies the animator, so the list gets a fresh one.
    assertThat(lithoView.itemAnimator()).isInstanceOf(LazyCollectionItemAnimator::class.java)
    assertThat(recyclerView.children()).hasSize(4).allMatch {
      it.alpha == 1f && it.translationX == 0f && it.translationY == 0f
    }
  }

  @Test
  fun `a wrap content list that grows does not fade its rows in again`() {
    // Growing remeasures the list, and the legacy mount binder reattaches the adapter.
    val lithoView =
        legacyFlagOn.render(widthPx = 300, heightPx = 300) {
          WrapList(heights = listOf(20, 20, 20))
        }
    lithoView.settle(legacyFlagOn)
    val detachedRows = lithoView.countDetachedRows()

    lithoView.setRoot { WrapList(heights = listOf(20, 60, 20, 20)) }
    var tasks = 0
    while (detachedRows.get() < 3 && tasks++ < 100) {
      legacyFlagOn.finishAsyncUpdates()
      ShadowLooper.shadowMainLooper().runOneTask()
    }
    legacyFlagOn.finishAsyncUpdates()
    lithoView.measure().layout()

    val recyclerView = lithoView.lithoView.findViewWithTag<SectionsRecyclerView>(TAG).recyclerView
    assertThat(detachedRows.get()).isGreaterThanOrEqualTo(3)
    assertThat(recyclerView.children()).isNotEmpty().allMatch { it.alpha == 1f }
  }

  private class WrapList(private val heights: List<Int>) : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(
          LazyList(
              orientation = RecyclerView.HORIZONTAL,
              crossAxisWrapMode = CrossAxisWrapMode.Dynamic,
              style = Style.viewTag(TAG),
          ) {
            heights.forEachIndexed { i, height ->
              child(
                  id = "$i:$height",
                  component = Column(style = Style.height(height.dp).width(30.dp)),
              )
            }
          },
      )
    }
  }

  private fun RecyclerView.children(): List<View> = (0 until childCount).map { getChildAt(it) }

  @Test
  fun `custom animators are passed through`() {
    for (rule in flagOnRules) {
      val custom = DefaultItemAnimator()

      val lithoView =
          rule.render(widthPx = 100, heightPx = 100) { list(ids = false, itemAnimator = custom) }

      assertThat(lithoView.itemAnimator()).isSameAs(custom)
    }
  }

  @Test
  fun `an explicit null animator stays null`() {
    for (rule in flagOnRules) {
      val lithoView =
          rule.render(widthPx = 100, heightPx = 100) { list(ids = true, itemAnimator = null) }

      assertThat(lithoView.itemAnimator()).isNull()
    }
  }

  @Test
  fun `nested lists never share an animator`() {
    for (rule in flagOnRules) {
      val lithoView = rule.render(widthPx = 100, heightPx = 300) { NestedLists(rows = 0 until 6) }
      lithoView.settle(rule)
      val before = lithoView.lithoView.attachedAnimators()

      lithoView.setRoot { NestedLists(rows = 2 until 6) }
      lithoView.settle(rule)
      val after = lithoView.lithoView.attachedAnimators()

      assertThat(before).hasSizeGreaterThan(1)
      for (animators in listOf(before, after)) {
        assertThat(animators).allMatch { it is LazyCollectionItemAnimator }
        assertThat(animators).doesNotContain(LazyCollectionItemAnimator.Default)
        assertThat(animators.distinctBy { System.identityHashCode(it) }).hasSameSizeAs(animators)
      }
    }
  }

  @Test
  fun `sharing one animator between nested lists crashes`() {
    // Control for the test above: the same scenario with one animator instance on every inner list
    // reproduces the crash the per-RecyclerView animator prevents.
    val shared = DefaultItemAnimator()

    assertThatThrownBy {
          val lithoView =
              legacyFlagOn.render(widthPx = 100, heightPx = 300) {
                NestedLists(rows = 0 until 6, innerAnimator = shared)
              }
          lithoView.settle(legacyFlagOn)
          lithoView.setRoot { NestedLists(rows = 2 until 6, innerAnimator = shared) }
          lithoView.settle(legacyFlagOn)
        }
        .hasStackTraceContaining("Scrapped or attached views may not be recycled")
  }

  // Lists without ids are the case under test.
  @SuppressLint("CollectionChildWithoutIdInLoopDetector")
  private fun ResourcesScope.list(
      ids: Boolean,
      itemAnimator: RecyclerView.ItemAnimator? = CollectionRecyclerSpec.itemAnimator,
      topPadding: Int = 0,
      rows: IntRange = 0 until 5,
  ): Component =
      Column(style = Style.height(100.dp)) {
        child(
            LazyList(
                itemAnimator = itemAnimator,
                topPadding = topPadding.dp,
                style = Style.viewTag(TAG).height(100.dp),
            ) {
              for (i in rows) {
                if (ids) {
                  child(id = i, component = row())
                } else {
                  child(row())
                }
              }
            },
        )
      }

  /**
   * Built inside a component so the lists capture the tree's configuration, as in production; rows
   * inherit it through the list's binder configuration.
   */
  private class NestedLists(
      private val rows: IntRange,
      private val innerAnimator: RecyclerView.ItemAnimator? = CollectionRecyclerSpec.itemAnimator,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.height(300.dp)) {
          child(
              LazyList(style = Style.height(300.dp)) {
                for (row in rows) {
                  child(
                      id = row,
                      component =
                          LazyList(
                              orientation = RecyclerView.HORIZONTAL,
                              itemAnimator = innerAnimator,
                              style = Style.height(40.dp),
                          ) {
                            for (i in 0 until 4) {
                              child(
                                  id = i,
                                  component = Column(style = Style.height(40.dp).width(30.dp)),
                              )
                            }
                          },
                  )
                }
              },
          )
        }
  }

  /** Lets the binders apply their async changesets and the RecyclerViews lay out their rows. */
  private fun TestLithoView.settle(rule: LithoTestRule) {
    repeat(3) {
      ShadowLooper.idleMainLooper()
      rule.idle()
      measure().layout()
    }
  }

  private fun View.attachedAnimators(): List<RecyclerView.ItemAnimator> {
    val result = mutableListOf<RecyclerView.ItemAnimator>()
    fun visit(view: View) {
      if (view is SectionsRecyclerView) {
        view.recyclerView.itemAnimator?.let { result.add(it) }
      }
      if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
          visit(view.getChildAt(i))
        }
      }
    }
    visit(this)
    return result
  }

  private fun ResourcesScope.row(): Component = Column(style = Style.height(10.dp))

  private fun TestLithoView.itemAnimator(): RecyclerView.ItemAnimator? =
      (lithoView.findViewWithTag<SectionsRecyclerView>(TAG)).recyclerView.itemAnimator

  private fun TestLithoView.countDetachedRows(): AtomicInteger {
    val detached = AtomicInteger()
    val recyclerView = lithoView.findViewWithTag<SectionsRecyclerView>(TAG).recyclerView
    assertThat(recyclerView.childCount).isGreaterThan(0)
    recyclerView.addOnChildAttachStateChangeListener(
        object : RecyclerView.OnChildAttachStateChangeListener {
          override fun onChildViewAttachedToWindow(view: View) = Unit

          override fun onChildViewDetachedFromWindow(view: View) {
            detached.incrementAndGet()
          }
        },
    )
    return detached
  }

  private class OtherItemAnimator : DefaultItemAnimator()

  private class DurationAnimator(duration: Long) : DefaultItemAnimator() {
    init {
      addDuration = duration
    }
  }

  private companion object {
    const val TAG = "lazy_list"

    private fun rule(enabled: Boolean, newCollection: Boolean): LithoTestRule = LithoTestRule(
        componentsConfiguration =
            ComponentsConfiguration.defaultInstance.copy(
                useDefaultItemAnimatorInLazyCollections = enabled,
                enableNewCollection = newCollection,
            ),
        // RecyclerView only lays out its rows, and so mounts the nested lists, in a real window.
        attachToRealWindow = true,
    )
  }
}
