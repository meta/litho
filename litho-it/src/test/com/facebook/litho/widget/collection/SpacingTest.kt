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

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Tests for [Collection]'s children */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SpacingTest {

  lateinit var view: View
  lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
  lateinit var recyclerView: RecyclerView
  lateinit var linearLayoutManager: LinearLayoutManager
  lateinit var state: RecyclerView.State

  @Before
  fun setUp() {
    view = mock {}
    linearLayoutManager = mock {
      on { orientation } doReturn RecyclerView.VERTICAL
      on { layoutDirection } doReturn View.LAYOUT_DIRECTION_LTR
    }
    adapter = mock { on { itemCount } doReturn 4 }
    recyclerView = mock {
      on { layoutManager } doReturn linearLayoutManager
      on { getChildAdapterPosition(any()) } doReturn -1
      on { adapter } doReturn adapter
    }
    state = mock {}
  }

  @Test
  fun `test equality`() {
    /*
     * Defend against the `data` keyword being removed from the class, as this is necessary for
     * passing isEquivalentTo checks.
     */
    assertThat(LinearSpacingItemDecoration(1, 2, 3, 4))
        .isEqualTo(LinearSpacingItemDecoration(1, 2, 3, 4))
  }

  @Test
  fun `test all applied correctly to vertical`() {
    val linearSpacing = LinearSpacingItemDecoration(all = 10)

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(0, 10, 0, 10))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(0, 0, 0, 10))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(0, 0, 0, 10))
  }

  @Test
  fun `test all applied correctly to horizontal`() {
    whenever(linearLayoutManager.orientation).thenReturn(RecyclerView.HORIZONTAL)

    val linearSpacing = LinearSpacingItemDecoration(all = 10)

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(10, 0, 10, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(0, 0, 10, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(0, 0, 10, 0))
  }

  @Test
  fun `test overrides applied correctly to vertical`() {
    val linearSpacing = LinearSpacingItemDecoration(
        all = 10,
        between = 1,
        start = 2,
        end = 3,
    )

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(0, 2, 0, 1))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(0, 0, 0, 1))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(0, 0, 0, 3))
  }

  @Test
  fun `test overrides applied correctly to horizontal`() {
    whenever(linearLayoutManager.orientation).thenReturn(RecyclerView.HORIZONTAL)

    val linearSpacing = LinearSpacingItemDecoration(
        all = 10,
        between = 1,
        start = 2,
        end = 3,
    )

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(2, 0, 1, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(0, 0, 1, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(0, 0, 3, 0))
  }

  @Test
  fun `test overrides applied correctly for RTL`() {
    whenever(linearLayoutManager.orientation).thenReturn(RecyclerView.HORIZONTAL)
    whenever(linearLayoutManager.layoutDirection).thenReturn(View.LAYOUT_DIRECTION_RTL)

    val linearSpacing = LinearSpacingItemDecoration(
        all = 10,
        between = 1,
        start = 2,
        end = 3,
    )

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(1, 0, 2, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(1, 0, 0, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(3, 0, 0, 0))
  }

  @Test
  fun `test overrides applied correctly for vertical reverse layout`() {
    whenever(linearLayoutManager.reverseLayout).thenReturn(true)

    val linearSpacing = LinearSpacingItemDecoration(
        all = 10,
        between = 1,
        start = 2,
        end = 3,
    )

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(0, 1, 0, 2))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(0, 1, 0, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(0, 3, 0, 0))
  }

  @Test
  fun `test overrides applied correctly for horizontal reverse layout`() {
    whenever(linearLayoutManager.orientation).thenReturn(RecyclerView.HORIZONTAL)
    whenever(linearLayoutManager.reverseLayout).thenReturn(true)

    val linearSpacing = LinearSpacingItemDecoration(
        all = 10,
        between = 1,
        start = 2,
        end = 3,
    )

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(1, 0, 2, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(1, 0, 0, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(3, 0, 0, 0))
  }

  @Test
  fun `test overrides applied correctly for horizontal reversed RTL layout`() {
    whenever(linearLayoutManager.orientation).thenReturn(RecyclerView.HORIZONTAL)
    whenever(linearLayoutManager.reverseLayout).thenReturn(true)
    whenever(linearLayoutManager.layoutDirection).thenReturn(View.LAYOUT_DIRECTION_RTL)

    val linearSpacing = LinearSpacingItemDecoration(
        all = 10,
        between = 1,
        start = 2,
        end = 3,
    )

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(2, 0, 1, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(0, 0, 1, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(0, 0, 3, 0))
  }

  @Test
  fun `test overrides applied correctly for vertical reverse RTL layout`() {
    whenever(linearLayoutManager.reverseLayout).thenReturn(true)
    whenever(linearLayoutManager.layoutDirection).thenReturn(View.LAYOUT_DIRECTION_RTL)

    val linearSpacing = LinearSpacingItemDecoration(
        all = 10,
        between = 1,
        start = 2,
        end = 3,
    )

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(0)
    val first = Rect()
    linearSpacing.getItemOffsets(first, view, recyclerView, state)
    assertThat(first).isEqualTo(Rect(0, 1, 0, 2))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(2)
    val middle = Rect()
    linearSpacing.getItemOffsets(middle, view, recyclerView, state)
    assertThat(middle).isEqualTo(Rect(0, 1, 0, 0))

    whenever(recyclerView.getChildAdapterPosition(any())).thenReturn(3)
    val last = Rect()
    linearSpacing.getItemOffsets(last, view, recyclerView, state)
    assertThat(last).isEqualTo(Rect(0, 3, 0, 0))
  }
}
