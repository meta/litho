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
import com.facebook.litho.BaseMountingView
import com.facebook.litho.Component
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.PrimitiveRecyclerBinderStrategy
import com.facebook.litho.core.height
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.sections.widget.NoUpdateItemAnimator
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.litho.visibility.onInvisible
import com.facebook.litho.visibility.onVisible
import com.facebook.litho.widget.LithoRecyclerView.OnAfterLayoutListener
import com.facebook.litho.widget.SectionsRecyclerView
import com.facebook.rendercore.dp
import com.facebook.rendercore.extensions.VisibilityChangeReason
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

// PAUSED, like a device: the checks run after the layout that starts the animations and before a
// frame advances them.
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LazyCollectionLeavingItemVisibilityTest {

  @get:Rule val legacy = rule(newCollection = false)
  @get:Rule val newInfra = rule(newCollection = true)
  @get:Rule val legacyFlagOff = rule(newCollection = false, animationsFlag = false)
  @get:Rule val newInfraFlagOff = rule(newCollection = true, animationsFlag = false)
  @get:Rule
  val legacySplitBinders = rule(
      newCollection = false,
      binderStrategy = PrimitiveRecyclerBinderStrategy.SPLIT_BINDERS,
  )

  private val visible = mutableListOf<Int>()
  private val invisible = mutableListOf<Int>()
  private var onNestedInvisible: () -> Unit = {}

  /**
   * Removing item 2 fires its onInvisible in the layout that removes it, before its fade-out plays,
   * as it would without an animator.
   */
  @Test
  fun `a removed item is invisible as soon as it starts animating out`() {
    for (rule in listOf(legacy, legacySplitBinders, newInfra)) {
      reset()
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 5) }
      lithoView.settle(rule)

      lithoView.setRoot { list(ids = listOf(0, 1, 3, 4)) }
      lithoView.runUntilAnimating(rule)

      assertThat(invisible).containsExactly(2)
    }
  }

  /**
   * Items inserted after 4 push 5..9 below the list. They slide out, and fire onInvisible in that
   * layout.
   *
   * ```
   * before: [0 1 2 3 4 5   6   7   8   9  ]
   * after:  [0 1 2 3 4 100 101 102 103 104]   5..9 slide out below
   * ```
   */
  @Test
  fun `an item pushed out of the list is invisible as soon as it starts animating out`() {
    for (rule in listOf(legacy, legacySplitBinders, newInfra)) {
      reset()
      // 100px list, 10px items: 0..9 fill it.
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 10) }
      lithoView.settle(rule)

      // Five items inserted after 4 push 5..9 out of the list; they slide out.
      lithoView.setRoot { list(ids = (0 until 5) + (100 until 105) + (5 until 10)) }
      lithoView.runUntilAnimating(rule)

      assertThat(invisible).containsExactlyInAnyOrder(5, 6, 7, 8, 9)
    }
  }

  /**
   * The list's after-layout listeners, which SERP uses to decide that its results are rendered,
   * already see the removed item as invisible in the layout that removes it. A list that wraps its
   * content lays out in onLayout, before any draw.
   */
  @Test
  fun `a removed item is invisible by the time the list's after-layout listeners run`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val invisibleAfterEachLayout = mutableListOf<List<Int>>()
      val onAfterLayout = OnAfterLayoutListener { invisibleAfterEachLayout.add(invisible.toList()) }
      val lithoView =
          rule.render(widthPx = 100, heightPx = 100) {
            list(ids = 0 until 5, onAfterLayout = onAfterLayout, wrapContent = true)
          }
      lithoView.settle(rule)

      lithoView.setRoot {
        list(ids = listOf(0, 1, 3, 4), onAfterLayout = onAfterLayout, wrapContent = true)
      }
      lithoView.runUntilAnimating(rule)

      // The list wraps its content, so the other rows remount and report too.
      assertThat(invisibleAfterEachLayout.last()).contains(2)
    }
  }

  /** Once a removed item's fade-out finishes, its view is recycled and reports the next item. */
  @Test
  fun `the view of a removed item gets visibility events again once recycled`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 5) }
      lithoView.settle(rule)
      lithoView.setRoot { list(ids = listOf(0, 1, 3, 4)) }
      lithoView.settle(rule)
      ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)

      lithoView.setRoot { list(ids = listOf(0, 1, 3, 4, 5)) }
      lithoView.settle(rule)

      assertThat(visible).contains(5)
    }
  }

  /**
   * A removed item's fade-out can be cut short, e.g. when the list is detached or remounted. Its
   * view is still recycled and reports the next item.
   */
  @Test
  fun `the view of a removed item gets visibility events again when its animation is ended early`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 5) }
      lithoView.settle(rule)
      lithoView.setRoot { list(ids = listOf(0, 1, 3, 4)) }
      lithoView.runUntilAnimating(rule)

      // scenario: the fade-out of 2 is cut short, as when the list is detached or remounted.
      lithoView.recyclerView().itemAnimator?.endAnimations()
      lithoView.setRoot { list(ids = listOf(0, 1, 3, 4, 5)) }
      lithoView.settle(rule)

      // expectation: the recycled view mounts and reports the new item as visible.
      assertThat(visible).contains(5)
    }
  }

  /**
   * Items pushed out of the list can come back before their exit ends: RecyclerView reuses the same
   * views for them. They must report visible again.
   */
  @Test
  fun `items brought back before their exit ends report visible again`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 10) }
      lithoView.settle(rule)

      // scenario: inserted items push 5..9 out; before they finish sliding out, the insert is
      // undone and 5..9 come back.
      lithoView.setRoot { list(ids = (0 until 5) + (100 until 105) + (5 until 10)) }
      lithoView.runUntilAnimating(rule)
      visible.clear()
      lithoView.setRoot { list(ids = 0 until 10) }
      lithoView.settle(rule)

      // expectation: 5..9 report visible again.
      assertThat(visible).containsAll(5 until 10)
    }
  }

  /**
   * An item pushed out of the list can scroll back into view while it slides out: RecyclerView
   * reuses its still-animating view for it. It must report visible as soon as it is back in the
   * list, and the items still sliding out off screen must not.
   *
   * ```
   * after the insert:  [0 1 2 3 4 100 101 102 103 104]  5..9 slide out below
   * scrolled by 10px:  [1 2 3 4 100 101 102 103 104 5]  5 is back
   * ```
   */
  @Test
  fun `an item scrolled back while it slides out reports visible again`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 10) }
      lithoView.settle(rule)

      // scenario: inserted items push 5..9 out; while they slide out, the list scrolls 5 back in.
      lithoView.setRoot { list(ids = (0 until 5) + (100 until 105) + (5 until 10)) }
      lithoView.runUntilAnimating(rule)
      visible.clear()
      lithoView.recyclerView().scrollBy(0, 10)
      // Two frames: long before the slide-out ends.
      ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)

      // expectation: 5 reports visible; 6..9 stay off screen and report nothing.
      assertThat(visible).contains(5).doesNotContain(6, 7, 8, 9)
    }
  }

  /**
   * An item's own onInvisible ends the list's animations, at the moment its exit starts and fires
   * that onInvisible. Nothing may stay paused: its view, recycled for the next item, reports it.
   */
  @Test
  fun `an item whose onInvisible ends the animations gets visibility events again`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 5) }
      lithoView.settle(rule)
      val list = lithoView.recyclerView()

      // scenario: 2 is removed, and its onInvisible ends the list's animations right away.
      lithoView.setRoot {
        list(
            ids = listOf(0, 1, 3, 4),
            onInvisible = { id ->
              if (id == 2) {
                list.itemAnimator?.endAnimations()
              }
            },
        )
      }
      lithoView.settle(rule)
      visible.clear()
      lithoView.setRoot { list(ids = listOf(0, 1, 3, 4, 5)) }
      lithoView.settle(rule)

      // expectation: the next item reports visible.
      assertThat(visible).contains(5)
    }
  }

  /**
   * A view can be leaving for two lists at once, e.g. an item of a nested list when the item around
   * that list is leaving the outer list too. Finishing one exit must not let it report visible
   * while the other one still runs.
   *
   * ```
   * outer list's exit   start ....................... finish
   * nested list's exit        start ..... finish
   *                                              ^ still invisible here
   * ```
   */
  @Test
  fun `a view leaving for two lists stays invisible until both finish`() {
    reset()
    val lithoView = legacy.render(widthPx = 100, heightPx = 10) { item(0) }.lithoView

    // scenario: both lists start the view's exit; the nested one finishes first.
    lithoView.startLeavingParent()
    lithoView.startLeavingParent()
    lithoView.finishLeavingParent()
    visible.clear()
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    // expectation: still leaving for the outer list, so it reports nothing.
    assertThat(visible).isEmpty()

    // scenario: the outer list finishes too.
    lithoView.finishLeavingParent()
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    // expectation: it reports visible again.
    assertThat(visible).containsExactly(0)
  }

  /**
   * A nested list inside a leaving item can still scroll and attach new items. They must not report
   * visible, because the item around them is gone.
   */
  @Test
  fun `a nested list scrolled inside a leaving item reports nothing visible`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      // One 100px item holding a nested list of 40 items of 10px: nested items 0..9 show.
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { listWithNestedList() }
      lithoView.settle(rule)
      val list = lithoView.recyclerView()
      val nestedList = nestedRecyclerView(requireNotNull(list.getChildAt(0)))

      // scenario: the item holding the nested list is removed and starts leaving, then the nested
      // list scrolls to items 30..39.
      lithoView.setRoot { listWithNestedList(hasNestedList = false) }
      lithoView.runUntilAnimating(rule)
      visible.clear()
      nestedList.scrollBy(0, 300)
      repeat(5) { ShadowLooper.shadowMainLooper().runOneTask() }

      // expectation: nothing inside the leaving item reports visible.
      assertThat(visible).isEmpty()
      list.itemAnimator?.endAnimations()
    }
  }

  /**
   * The first nested item to report invisible can end the exit right away. The nested items not
   * cleared yet still report invisible, through the detach of the removed item.
   */
  @Test
  fun `nested items report invisible when one of them ends the exit`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val lithoView =
          rule.render(widthPx = 100, heightPx = 100) {
            listWithNestedList(onNestedInvisible = { onNestedInvisible() })
          }
      lithoView.settle(rule)
      val list = lithoView.recyclerView()
      val unfinishedBefore = BaseMountingView.unfinishedLeavingParentCount

      // scenario: the item holding the nested list is removed, and the first nested onInvisible
      // ends the outer list's animations.
      onNestedInvisible = {
        onNestedInvisible = {}
        list.itemAnimator?.endAnimations()
      }
      invisible.clear()
      lithoView.setRoot { listWithNestedList(hasNestedList = false) }
      lithoView.settle(rule)

      // expectation: every nested item that was on screen reports invisible, and nothing is left
      // leaving.
      assertThat(invisible).containsAll(0 until 10)
      assertThat(BaseMountingView.unfinishedLeavingParentCount).isEqualTo(unfinishedBefore)
    }
  }

  /**
   * A list laid out while it isn't attached, e.g. measured off screen, doesn't pause the item it
   * animates out: it may never be attached, and nothing would finish the pause.
   */
  @Test
  fun `a list laid out while detached pauses nothing`() {
    for (rule in listOf(legacy, newInfra)) {
      reset()
      val lithoView = rule.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 5) }
      lithoView.settle(rule)

      // scenario: the list is detached, then lays out the removal of item 2.
      lithoView.detachFromWindow()
      val unfinishedBefore = BaseMountingView.unfinishedLeavingParentCount
      lithoView.setRoot { list(ids = listOf(0, 1, 3, 4)) }
      lithoView.settle(rule)

      // expectation: no view started leaving.
      assertThat(BaseMountingView.unfinishedLeavingParentCount).isEqualTo(unfinishedBefore)
      lithoView.recyclerView().itemAnimator?.endAnimations()
    }
  }

  /**
   * A leaving item keeps mounting what moves into view while it animates out, so it never draws
   * blank parts. Only its visibility events wait. New backend only: the legacy one doesn't mount on
   * a translation, leaving or not.
   *
   * ```
   * scrolled by 20px:              [c][1][2]...    item 0 = a b c, only c on screen
   * item 0 removed, moved 20px:    [a][b][c]...    a is on screen again and must be mounted
   * ```
   */
  @Test
  fun `a leaving item mounts the part of it that moves into view`() {
    reset()
    val lithoView =
        newInfra.render(widthPx = 100, heightPx = 100) { listWithTallFirstItem(0 until 20) }
    lithoView.settle(newInfra)
    val list = lithoView.recyclerView()
    list.scrollBy(0, 20)
    lithoView.settle(newInfra)
    val tallItem =
        (0 until list.childCount)
            .map { list.getChildAt(it) }
            .first { it.findViewWithTag<View>("c") != null }

    // scenario: the tall item is removed and, while it leaves, an animator moves it down 20px.
    lithoView.setRoot { listWithTallFirstItem(1 until 20) }
    lithoView.runUntilAnimating(newInfra)
    tallItem.translationY = 20f
    ShadowLooper.shadowMainLooper().runOneTask()

    // expectation: its top part, now on screen, is mounted.
    assertThat(tallItem.findViewWithTag<View>("a")).isNotNull()
    list.itemAnimator?.endAnimations()
  }

  /**
   * Only lists with the default Lazy Collection animator pause leaving items. A list with its own
   * animator reports the same visibility events with the animations flag on as with it off.
   */
  @Test
  fun `a list with its own item animator reports the same events with the animations flag on`() {
    for ((flagOn, flagOff) in listOf(legacy to legacyFlagOff, newInfra to newInfraFlagOff)) {
      // Change animations off: the new backend also reports the other items as changed, and their
      // old copies cross-fading out would report invisible.
      for (newItemAnimator in
          listOf(
              { NoUpdateItemAnimator() },
              { DefaultItemAnimator().apply { supportsChangeAnimations = false } },
          )) {
        val withFlag = invisibleWhenItemTwoStartsLeaving(flagOn, newItemAnimator())
        val withoutFlag = invisibleWhenItemTwoStartsLeaving(flagOff, newItemAnimator())

        assertThat(withFlag)
            .describedAs(newItemAnimator().javaClass.simpleName)
            .isEqualTo(withoutFlag)
      }
    }
  }

  private fun invisibleWhenItemTwoStartsLeaving(
      rule: LithoTestRule,
      itemAnimator: RecyclerView.ItemAnimator,
  ): List<Int> {
    reset()
    val lithoView =
        rule.render(widthPx = 100, heightPx = 100) {
          list(ids = 0 until 5, itemAnimator = itemAnimator)
        }
    lithoView.settle(rule)

    lithoView.setRoot { list(ids = listOf(0, 1, 3, 4), itemAnimator = itemAnimator) }
    lithoView.runUntilAnimating(rule)
    val invisibleAtExitStart = invisible.toList()
    // Ends this list's exits now, so their late events don't land in the next run.
    lithoView.recyclerView().itemAnimator?.endAnimations()
    lithoView.settle(rule)
    return invisibleAtExitStart
  }

  /**
   * The list finds its leaving items through RecyclerView's bookkeeping: an item kept on screen
   * only to animate it out is a child of the RecyclerView that its layout manager doesn't list.
   * Fails if an androidx update changes that.
   */
  @Test
  fun `the layout manager doesn't list an item that is only animating out`() {
    reset()
    val lithoView = legacy.render(widthPx = 100, heightPx = 100) { list(ids = 0 until 5) }
    lithoView.settle(rule = legacy)
    val list = lithoView.recyclerView()

    lithoView.setRoot { list(ids = listOf(0, 1, 3, 4)) }
    lithoView.runUntilAnimating(legacy)

    assertThat(list.childCount).isEqualTo(5)
    assertThat(requireNotNull(list.layoutManager).childCount).isEqualTo(4)
  }

  private fun reset() {
    visible.clear()
    invisible.clear()
  }

  // LazyList's default item animator comes from the deprecated CollectionRecyclerSpec.
  @SuppressLint("DeprecatedClass")
  private fun ResourcesScope.list(
      ids: Iterable<Int>,
      onInvisible: (Int) -> Unit = {},
      onAfterLayout: OnAfterLayoutListener? = null,
      wrapContent: Boolean = false,
  ): Component =
      Column(style = Style.height(100.dp)) {
        child(
            LazyList(
                style = if (wrapContent) Style.viewTag(TAG) else Style.viewTag(TAG).height(100.dp),
                mainAxisWrapContent = wrapContent,
                onAfterLayout = onAfterLayout,
            ) {
              for (id in ids) {
                child(id = id, component = item(id, onInvisible = { onInvisible(id) }))
              }
            },
        )
      }

  private fun ResourcesScope.list(
      ids: Iterable<Int>,
      itemAnimator: RecyclerView.ItemAnimator,
  ): Component =
      Column(style = Style.height(100.dp)) {
        child(
            LazyList(itemAnimator = itemAnimator, style = Style.viewTag(TAG).height(100.dp)) {
              for (id in ids) {
                child(id = id, component = item(id))
              }
            },
        )
      }

  // LazyList's default item animator comes from the deprecated CollectionRecyclerSpec.
  @SuppressLint("DeprecatedClass")
  private fun ResourcesScope.listWithNestedList(
      hasNestedList: Boolean = true,
      onNestedInvisible: () -> Unit = {},
  ): Component =
      Column(style = Style.height(100.dp)) {
        child(
            LazyList(style = Style.viewTag(TAG).height(100.dp)) {
              if (hasNestedList) {
                child(
                    id = -1,
                    component =
                        LazyList(style = Style.height(100.dp)) {
                          for (id in 0 until 40) {
                            child(id = id, component = item(id, onInvisible = onNestedInvisible))
                          }
                        },
                )
              }
            },
        )
      }

  // LazyList's default item animator comes from the deprecated CollectionRecyclerSpec.
  @SuppressLint("DeprecatedClass")
  private fun ResourcesScope.listWithTallFirstItem(ids: Iterable<Int>): Component =
      Column(style = Style.height(100.dp)) {
        child(
            LazyList(style = Style.viewTag(TAG).height(100.dp)) {
              for (id in ids) {
                child(id = id, component = if (id == 0) tallItem() else item(id))
              }
            },
        )
      }

  /** A 30px item made of three 10px parts tagged "a", "b" and "c", top to bottom. */
  private fun tallItem(): Component =
      Column(style = Style.height(30.dp)) {
        child(Column(style = Style.height(10.dp).viewTag("a")))
        child(Column(style = Style.height(10.dp).viewTag("b")))
        child(Column(style = Style.height(10.dp).viewTag("c")))
      }

  /** A 10px item that records its visibility events into [visible] and [invisible]. */
  private fun item(id: Int, onInvisible: () -> Unit = {}): Component = Column(
      style =
          Style.height(10.dp)
              .onVisible { visible.add(id) }
              .onInvisible {
                invisible.add(id)
                onInvisible()
              },
  )

  private fun nestedRecyclerView(root: View): RecyclerView {
    val toVisit = ArrayDeque<View>().apply { add(root) }
    while (toVisit.isNotEmpty()) {
      val next = toVisit.removeFirst()
      if (next is RecyclerView) {
        return next
      }
      if (next is ViewGroup) {
        for (i in 0 until next.childCount) {
          toVisit.add(next.getChildAt(i))
        }
      }
    }
    error("No RecyclerView under $root")
  }

  private fun TestLithoView.settle(rule: LithoTestRule) {
    repeat(3) {
      ShadowLooper.idleMainLooper()
      rule.idle()
      measure().layout()
    }
  }

  /** Runs main-thread tasks until the list starts its animations, before a frame advances them. */
  private fun TestLithoView.runUntilAnimating(rule: LithoTestRule) {
    val recyclerView = recyclerView()
    var tasks = 0
    while (recyclerView.itemAnimator?.isRunning != true && tasks++ < 100) {
      rule.finishAsyncUpdates()
      ShadowLooper.shadowMainLooper().runOneTask()
    }
    assertThat(recyclerView.itemAnimator?.isRunning).isTrue()
  }

  private fun TestLithoView.recyclerView(): RecyclerView =
      lithoView.findViewWithTag<SectionsRecyclerView>(TAG).recyclerView

  private companion object {
    const val TAG = "list"

    private fun rule(
        newCollection: Boolean,
        animationsFlag: Boolean = true,
        binderStrategy: PrimitiveRecyclerBinderStrategy =
            ComponentsConfiguration.defaultInstance.primitiveRecyclerBinderStrategy,
    ): LithoTestRule = LithoTestRule(
        componentsConfiguration =
            ComponentsConfiguration.defaultInstance.copy(
                useDefaultItemAnimatorInLazyCollections = animationsFlag,
                enableNewCollection = newCollection,
                primitiveRecyclerBinderStrategy = binderStrategy,
            ),
        // RecyclerView only lays out its rows in a real window.
        attachToRealWindow = true,
    )
  }
}
