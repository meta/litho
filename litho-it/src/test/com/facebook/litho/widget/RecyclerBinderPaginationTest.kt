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

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.facebook.litho.ComponentContext
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.viewcompat.SimpleViewBinder
import com.facebook.litho.viewcompat.ViewCreator
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * [RecyclerBinder.PaginationStrategy.SCROLL_TO_INSERT_POSITION] on a full-page pager parked on a
 * trailing spinner, where a tail load and a head cap arrive in the same change set. The change sets
 * mirror DataDiffSection, which dispatches from the end of the list: the tail insert first, then
 * the head removal.
 */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RecyclerBinderPaginationTest {

  private lateinit var context: ComponentContext

  @Before
  fun setup() {
    context = ComponentContext(getApplicationContext())
  }

  @Test
  fun `tail load with a larger cap lands on the first new item instead of the spinner`() {
    val pager = Pager(resolveTargetOnCommit = true)

    pager.tailLoad(loadCount = 3, capCount = 5)

    assertThat(pager.visibleTag()).isEqualTo("new 0")
  }

  @Test
  fun `tail load with a smaller cap lands on the first new item instead of skipping ahead`() {
    val pager = Pager(resolveTargetOnCommit = true)

    pager.tailLoad(loadCount = 8, capCount = 5)

    assertThat(pager.visibleTag()).isEqualTo("new 0")
  }

  @Test
  fun `async tail load with a cap lands on the first new item`() {
    val pager = Pager(resolveTargetOnCommit = true, useAsyncChangeSets = true)

    pager.tailLoad(loadCount = 3, capCount = 5)

    assertThat(pager.visibleTag()).isEqualTo("new 0")
  }

  @Test
  fun `tail load without a cap lands on the first new item`() {
    val pager = Pager(resolveTargetOnCommit = true)

    pager.tailLoad(loadCount = 3, capCount = 0)

    assertThat(pager.visibleTag()).isEqualTo("new 0")
  }

  @Test
  fun `scroll requested in onDataBound still wins over the pagination target`() {
    val pager = Pager(resolveTargetOnCommit = true)

    pager.tailLoad(loadCount = 3, capCount = 5, onDataBound = { pager.binder.scrollToPosition(0) })

    assertThat(pager.visibleTag()).isEqualTo("old 5")
  }

  @Test
  fun `pagination target does not outlive its change set`() {
    val pager = Pager(resolveTargetOnCommit = true)
    pager.tailLoad(loadCount = 3, capCount = 5)
    pager.binder.scrollToPosition(1)
    pager.layout()

    pager.binder.updateItemAt(0, page("old 5"))
    pager.binder.notifyChangeSetComplete(true, NO_OP_CALLBACK)
    pager.layout()

    assertThat(pager.visibleTag()).isEqualTo("old 6")
  }

  @Test
  fun `stale insert index is kept when the flag is off`() {
    val pager = Pager(resolveTargetOnCommit = false)

    pager.tailLoad(loadCount = 3, capCount = 5)

    assertThat(pager.visibleTag()).isEqualTo("spinner")
  }

  @Test
  fun `empty insert at a stale position does not crash`() {
    val pager = Pager(resolveTargetOnCommit = true)
    pager.binder.removeRangeAt(0, 5)

    pager.binder.insertRangeAt(SPINNER_POSITION, emptyList())
    pager.binder.notifyChangeSetComplete(true, NO_OP_CALLBACK)
    pager.layout()

    assertThat(pager.visibleTag()).isEqualTo("spinner")
  }

  @Test
  fun `change set applied while unmounted restores to the first new item`() {
    val pager = Pager(resolveTargetOnCommit = true)
    pager.unmount()

    pager.tailLoad(loadCount = 3, capCount = 5)
    pager.mount()

    assertThat(pager.visibleTag()).isEqualTo("new 0")
  }

  @Test
  fun `builder carries the flag through create and build`() {
    assertThat(RecyclerBinderConfig().resolvePaginationTargetOnCommit).isFalse

    val enabled = RecyclerBinderConfig.create().resolvePaginationTargetOnCommit(true).build()

    assertThat(enabled.resolvePaginationTargetOnCommit).isTrue
    assertThat(RecyclerBinderConfig.create(enabled).build().resolvePaginationTargetOnCommit).isTrue
  }

  private inner class Pager(
      resolveTargetOnCommit: Boolean,
      private val useAsyncChangeSets: Boolean = false,
  ) {
    val binder: RecyclerBinder =
        RecyclerBinder.Builder()
            .layoutInfo(LinearLayoutInfo(context, RecyclerView.VERTICAL, false))
            .recyclerBinderConfig(
                RecyclerBinderConfig(
                    paginationStrategy =
                        RecyclerBinder.PaginationStrategy.SCROLL_TO_INSERT_POSITION,
                    resolvePaginationTargetOnCommit = resolveTargetOnCommit,
                    estimatedViewportCount = 1,
                ),
            )
            .build(context)
    private val recyclerView = RecyclerView(context.androidContext).apply { itemAnimator = null }

    init {
      insert(0, List(SPINNER_POSITION) { "old $it" } + "spinner")
      commit()
      binder.measure(Size(), PAGE_SPEC, PAGE_SPEC, null)
      if (useAsyncChangeSets) {
        binder.applyReadyBatches()
      }
      binder.mount(recyclerView)
      layout()
      binder.scrollToPosition(SPINNER_POSITION)
      layout()
      binder.onNewVisibleRange(SPINNER_POSITION, SPINNER_POSITION)
      check(visibleTag() == "spinner")
    }

    fun tailLoad(loadCount: Int, capCount: Int, onDataBound: () -> Unit = {}) {
      if (loadCount > 0) {
        insert(SPINNER_POSITION, List(loadCount) { "new $it" })
      }
      if (capCount > 0) {
        if (useAsyncChangeSets) {
          binder.removeRangeAtAsync(0, capCount)
        } else {
          binder.removeRangeAt(0, capCount)
        }
      }
      commit(onDataBound)
      layout()
    }

    fun layout() {
      recyclerView.measure(PAGE_SPEC, PAGE_SPEC)
      recyclerView.layout(0, 0, PAGE_SIZE, PAGE_SIZE)
    }

    fun unmount() {
      binder.unmount(recyclerView)
    }

    fun mount() {
      binder.mount(recyclerView)
      layout()
    }

    fun visibleTag(): Any? {
      val layoutManager = recyclerView.layoutManager as LinearLayoutManager
      return layoutManager.findViewByPosition(layoutManager.findFirstVisibleItemPosition())?.tag
    }

    private fun insert(position: Int, ids: List<String>) {
      val renderInfos = ids.map(::page)
      if (useAsyncChangeSets) {
        binder.insertRangeAtAsync(position, renderInfos)
      } else {
        binder.insertRangeAt(position, renderInfos)
      }
    }

    private fun commit(onDataBound: () -> Unit = {}) {
      val callback =
          object : ChangeSetCompleteCallback {
            override fun onDataBound() = onDataBound()

            override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) = Unit
          }
      if (useAsyncChangeSets) {
        binder.notifyChangeSetCompleteAsync(true, callback)
      } else {
        binder.notifyChangeSetComplete(true, callback)
      }
    }
  }

  private fun page(id: String): RenderInfo =
      ViewRenderInfo.create()
          .viewCreator(PAGE_CREATOR)
          .viewBinder(
              object : SimpleViewBinder<View>() {
                override fun bind(view: View) {
                  view.tag = id
                }
              },
          )
          .build()

  private companion object {
    const val PAGE_SIZE = 200
    const val SPINNER_POSITION = 10
    val PAGE_SPEC: Int
      get() = makeSizeSpec(PAGE_SIZE, EXACTLY)

    val PAGE_CREATOR =
        object : ViewCreator<View> {
          override fun createView(c: Context, parent: ViewGroup?): View =
              View(c).apply {
                layoutParams =
                    RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PAGE_SIZE)
              }
        }

    val NO_OP_CALLBACK =
        object : ChangeSetCompleteCallback {
          override fun onDataBound() = Unit

          override fun onDataRendered(isMounted: Boolean, uptimeMillis: Long) = Unit
        }
  }
}
