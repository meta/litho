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

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.LithoRenderer
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.onCleanup
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.Whitebox
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useCallback
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.rendercore.px
import java.lang.ref.WeakReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/**
 * Once a row has left a list, nothing it rendered may stay reachable from a state handle that
 * outlives it.
 *
 * This reproduces the comments sheet leak: a pooled ComposeView in the row keeps a click lambda
 * after it is disposed, and that lambda keeps the row's [State]. The state reaches the row's
 * renderer, and through it the row component, which holds the fragment. Here, [CommentsSheet]
 * renders one such row: it hands its state to a [PooledViewStandIn] and holds an object that stands
 * in for the fragment. Each test makes the row leave the list in one way and checks that the object
 * is collected. Each test runs on both collections.
 */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class CollectionReleasedRowRetentionTest {

  @get:Rule val legacy = rule(newCollection = false)
  @get:Rule val new = rule(newCollection = true)

  @Test
  fun `legacy - closing the sheet releases its rows`() {
    closingTheSheetReleasesItsRows(legacy)
  }

  @Test
  fun `new - closing the sheet releases its rows`() {
    closingTheSheetReleasesItsRows(new)
  }

  @Test
  fun `legacy - closing the sheet releases what its rows keep in hook state`() {
    closingTheSheetReleasesWhatItsRowsKeepInHookState(legacy)
  }

  @Test
  fun `new - closing the sheet releases what its rows keep in hook state`() {
    closingTheSheetReleasesWhatItsRowsKeepInHookState(new)
  }

  @Test
  fun `legacy - closing the sheet while a row is sticky releases it`() {
    closingTheSheetWhileARowIsStickyReleasesIt(legacy)
  }

  @Test
  fun `new - closing the sheet while a row is sticky releases it`() {
    closingTheSheetWhileARowIsStickyReleasesIt(new)
  }

  @Test
  fun `legacy - removing a shown row releases it`() {
    removingAShownRowReleasesIt(legacy)
  }

  @Test
  fun `new - removing a shown row releases it`() {
    removingAShownRowReleasesIt(new)
  }

  @Test
  fun `legacy - removing a row that scrolled away releases it`() {
    removingARowThatScrolledAwayReleasesIt(legacy)
  }

  @Test
  fun `new - removing a row that scrolled away releases it`() {
    removingARowThatScrolledAwayReleasesIt(new)
  }

  /**
   * The floating header is a copy of the sticky row, a separate item, so releasing the removed row
   * doesn't release the copy. Checked while the sheet is still open, because closing it would also
   * release the copy.
   */
  @Test
  fun `new - removing the only sticky row releases its floating copy`() {
    val sheet = CommentsSheet(new, rowIsSticky = true)
    sheet.scrollUntilTheRowSticks()
    val floatingCopy = sheet.floatingCopyRenderer()
    assertThat(floatingCopy.treeState).describedAs("the floating copy rendered").isNotNull

    sheet.removeRow()

    assertThat(floatingCopy.treeState).describedAs("the floating copy is released").isNull()
    sheet.close()
  }

  /**
   * The cleanup that releases a list's rows also runs when the row holding a nested list is
   * recycled. When that row comes back, its inner rows render again from scratch: saveable state is
   * kept, plain state is not.
   */
  @Test
  fun `legacy - inner rows render again after the row holding them is recycled`() {
    assertInnerRowsRenderAgainAfterOuterRecycle(legacy)
  }

  @Test
  fun `new - inner rows render again after the row holding them is recycled`() {
    assertInnerRowsRenderAgainAfterOuterRecycle(new)
  }

  private fun closingTheSheetReleasesItsRows(rule: LithoTestRule) {
    val sheet = CommentsSheet(rule)

    sheet.close()

    sheet.assertFragmentCollected()
  }

  /** Releasing the row component alone isn't enough: the row keeps the fragment in a hook too. */
  private fun closingTheSheetReleasesWhatItsRowsKeepInHookState(rule: LithoTestRule) {
    val sheet = CommentsSheet(rule, rowKeepsFragmentInHook = true)

    sheet.close()

    sheet.assertFragmentCollected()
  }

  private fun closingTheSheetWhileARowIsStickyReleasesIt(rule: LithoTestRule) {
    val sheet = CommentsSheet(rule, rowIsSticky = true)
    sheet.scrollUntilTheRowSticks()

    sheet.close()

    sheet.assertFragmentCollected()
  }

  private fun removingAShownRowReleasesIt(rule: LithoTestRule) {
    val sheet = CommentsSheet(rule, rowKeepsFragmentInHook = true)

    sheet.removeRow()
    sheet.close()

    sheet.assertFragmentCollected()
  }

  /** A row that scrolled away has dropped its component already, but not its hook state. */
  private fun removingARowThatScrolledAwayReleasesIt(rule: LithoTestRule) {
    val sheet = CommentsSheet(rule, rowKeepsFragmentInHook = true)
    sheet.scrollTheRowAway()

    sheet.removeRow()
    sheet.close()

    sheet.assertFragmentCollected()
  }

  private fun assertInnerRowsRenderAgainAfterOuterRecycle(rule: LithoTestRule) {
    val inner = InnerRecorder()
    val view = rule.render(widthPx = 1080, heightPx = 1920) { OuterList(inner) }
    idle(rule)
    rule.act(view) { inner.states.last().update(5) }
    idle(rule)
    assertThat(inner.states.last().value).isEqualTo(5)

    scrollBy(rule, view, FILLER_HEIGHT_PX * FILLER_COUNT)
    assertThat(inner.outerRowCleanups).describedAs("the outer row was recycled").isPositive()
    scrollBy(rule, view, -FILLER_HEIGHT_PX * FILLER_COUNT)

    assertThat(inner.states.last().value).isEqualTo(0)
  }

  /**
   * A rendered list with one comment row followed by fillers. The row holds [fragment] and hands
   * its state to [pooledView]. Only a weak reference to the fragment is kept here, so the test can
   * check it is collected.
   */
  private inner class CommentsSheet(
      private val rule: LithoTestRule,
      rowKeepsFragmentInHook: Boolean = false,
      rowIsSticky: Boolean = false,
  ) {
    private val pooledView = PooledViewStandIn()
    private val fragment: WeakReference<Any>
    private var view: TestLithoView?

    init {
      val fragment = Any()
      this.fragment = WeakReference(fragment)
      val sheet = Sheet(
          fragment,
          pooledView,
          rowKeepsFragmentInHook,
          withRow = true,
          rowIsSticky = rowIsSticky,
      )
      view = rule.render(widthPx = 1080, heightPx = 1920) { sheet }
      idle(rule)
      assertThat(pooledView.rowStates).describedAs("the row gave its state away").isNotEmpty()
    }

    private val openView: TestLithoView
      get() = checkNotNull(view) { "the sheet is closed" }

    fun scrollUntilTheRowSticks() = scrollBy(rule, openView, FILLER_HEIGHT_PX * 2)

    fun scrollTheRowAway() = scrollBy(rule, openView, FILLER_HEIGHT_PX * FILLER_COUNT)

    fun removeRow() {
      openView.setRoot(
          Sheet(Any(), PooledViewStandIn(), rowKeepsFragmentInHook = false, withRow = false),
      )
      idle(rule)
    }

    /** Releases the list, and drops the view so only the pooled view's state handles remain. */
    fun close() {
      openView.release()
      view = null
      idle(rule)
    }

    fun floatingCopyRenderer(): LithoRenderer {
      val recyclerView = recyclerViews(openView.lithoView).first()
      val listeners =
          Whitebox.getInternalState<List<RecyclerView.OnScrollListener>>(
              recyclerView,
              "mScrollListeners",
          )
      val controller = listeners.filterIsInstance<NewStickyHeaderController>().single()
      val copy = checkNotNull(
          Whitebox.getInternalState<LithoCollectionItem?>(controller, "stickyHeaderItem"),
      ) {
        "no floating copy"
      }
      return checkNotNull(Whitebox.getInternalState<LithoRenderer?>(copy, "_renderer")) {
        "the floating copy never rendered"
      }
    }

    fun assertFragmentCollected() {
      assertThat(pooledView.rowStates)
          .describedAs("the pooled view still holds the row's state")
          .isNotEmpty()
      repeat(20) {
        if (fragment.get() == null) {
          return
        }
        Runtime.getRuntime().gc()
        Thread.sleep(10)
      }
      assertThat(fragment.get()).describedAs("fragment kept after the row left the list").isNull()
    }
  }

  private fun scrollBy(rule: LithoTestRule, view: TestLithoView, dy: Int) {
    val recyclerView = recyclerViews(view.lithoView).first()
    rule.act(view) { recyclerView.scrollBy(0, dy) }
    idle(rule)
  }

  private fun recyclerViews(root: View): List<RecyclerView> {
    val found = mutableListOf<RecyclerView>()
    val queue = ArrayDeque<View>().apply { add(root) }
    while (queue.isNotEmpty()) {
      val view = queue.removeFirst()
      if (view is RecyclerView) {
        found += view
      }
      if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
          queue.add(view.getChildAt(i))
        }
      }
    }
    return found
  }

  private fun idle(rule: LithoTestRule) {
    repeat(3) {
      ShadowLooper.idleMainLooper()
      rule.idle()
    }
  }

  /**
   * Stands in for the pooled ComposeView: it keeps the row's [State] after the row has left the
   * list, as the view's click lambda does.
   */
  private class PooledViewStandIn {
    val rowStates = mutableListOf<State<*>>()
  }

  private class Sheet(
      private val fragment: Any,
      private val pooledView: PooledViewStandIn,
      private val rowKeepsFragmentInHook: Boolean,
      private val withRow: Boolean,
      private val rowIsSticky: Boolean = false,
  ) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.width(1080.px).height(1920.px)) {
          if (withRow) {
            child(
                id = "row",
                isSticky = rowIsSticky,
                component = CommentRow(fragment, pooledView, rowKeepsFragmentInHook),
            )
          }
          for (i in 0 until FILLER_COUNT) {
            child(id = "filler$i", component = Column(style = Style.height(FILLER_HEIGHT_PX.px)))
          }
        }
  }

  /** Like the comments caption row: the row holds the fragment, a child owns the state. */
  private class CommentRow(
      private val fragment: Any,
      private val pooledView: PooledViewStandIn,
      private val keepsFragmentInHook: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      if (keepsFragmentInHook) {
        useCallback { -> fragment }
      }
      return StateOwner(pooledView)
    }
  }

  private class StateOwner(private val pooledView: PooledViewStandIn) : KComponent() {
    override fun ComponentScope.render(): Component {
      pooledView.rowStates.add(useState { 0 })
      return Column(style = Style.height(300.px))
    }
  }

  private class InnerRecorder {
    val states = mutableListOf<State<Int>>()
    var outerRowCleanups = 0
  }

  private class OuterList(private val inner: InnerRecorder) : KComponent() {
    override fun ComponentScope.render(): Component =
        LazyList(style = Style.width(1080.px).height(1920.px)) {
          child(id = "holder", component = InnerListHolder(inner))
          for (i in 0 until FILLER_COUNT) {
            child(id = "filler$i", component = Column(style = Style.height(FILLER_HEIGHT_PX.px)))
          }
        }
  }

  private class InnerListHolder(private val inner: InnerRecorder) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(Unit) { onCleanup { inner.outerRowCleanups++ } }
      return LazyList(style = Style.width(1080.px).height(400.px)) {
        child(id = "card", component = InnerRow(inner))
      }
    }
  }

  private class InnerRow(private val inner: InnerRecorder) : KComponent() {
    override fun ComponentScope.render(): Component {
      inner.states.add(useState { 0 })
      return Column(style = Style.height(300.px))
    }
  }

  companion object {
    private const val FILLER_COUNT = 40
    private const val FILLER_HEIGHT_PX = 1000

    private fun rule(newCollection: Boolean): LithoTestRule = LithoTestRule(
        componentsConfiguration =
            ComponentsConfiguration.defaultInstance.copy(enableNewCollection = newCollection),
    )
  }
}
