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

import android.app.Activity
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.ComponentContext
import com.facebook.litho.LithoConfiguration
import com.facebook.litho.LithoView
import com.facebook.litho.Size
import com.facebook.litho.SizeSpec.EXACTLY
import com.facebook.litho.SizeSpec.makeSizeSpec
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * [ComponentsConfiguration.pauseMountingWhileHidden] set on the [ComponentContext] of a
 * [RecyclerBinder] reaches its rows, which the RecyclerView lays out even while hidden.
 */
@RunWith(LithoTestRunner::class)
class RecyclerBinderPauseMountingWhileHiddenTest {

  @Test
  fun `a row laid out while the list is hidden mounts once shown`() {
    val list = HiddenList(pause = true)

    assertThat(list.row.mountDelegateTarget.getMountItemCount()).isEqualTo(0)

    list.show()

    assertThat(list.row.mountDelegateTarget.getMountItemCount()).isGreaterThan(0)
  }

  @Test
  fun `a row laid out while the list is hidden mounts right away when the flag is off`() {
    val list = HiddenList(pause = false)

    assertThat(list.row.mountDelegateTarget.getMountItemCount()).isGreaterThan(0)
  }

  /** A shown list that gets a second row while hidden, as the inbox does behind a thread. */
  private class HiddenList(pause: Boolean) {
    private val container: FrameLayout
    val row: LithoView

    init {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val recyclerView = RecyclerView(activity).apply { itemAnimator = null }
      container = FrameLayout(activity).apply { addView(recyclerView) }
      val listSpec = makeSizeSpec(LIST_SIZE, EXACTLY)
      val config = ComponentsConfiguration.defaultInstance.copy(pauseMountingWhileHidden = pause)
      val binder =
          RecyclerBinder.Builder()
              .layoutInfo(LinearLayoutInfo(activity, RecyclerView.VERTICAL, false))
              .build(ComponentContext(activity, LithoConfiguration(activity, config)))
      binder.insertItemAt(0, rowRenderInfo())
      binder.notifyChangeSetComplete(true, null)
      binder.measure(Size(), listSpec, listSpec, null)
      binder.mount(recyclerView)
      activity.setContentView(
          container,
          ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LIST_SIZE),
      )
      shadowOf(Looper.getMainLooper()).idle()

      container.visibility = View.GONE
      binder.insertItemAt(1, rowRenderInfo())
      binder.notifyChangeSetComplete(true, null)
      // A RecyclerView lays out its children itself, even while an ancestor is hidden.
      recyclerView.measure(listSpec, listSpec)
      recyclerView.layout(0, 0, LIST_SIZE, LIST_SIZE)
      row = recyclerView.getChildAt(1) as LithoView
    }

    private fun rowRenderInfo(): RenderInfo =
        ComponentRenderInfo.create()
            .component(
                SolidColor(Color.BLACK, style = Style.width(ROW_SIZE.px).height(ROW_SIZE.px)),
            )
            .build()

    /**
     * Robolectric doesn't dispatch aggregated visibility changes, so this does what [View] does
     * when an ancestor in a visible window becomes visible.
     */
    fun show() {
      container.visibility = View.VISIBLE
      row.onVisibilityAggregated(true)
    }
  }

  private companion object {
    const val ROW_SIZE = 100
    const val LIST_SIZE = 500
  }
}
