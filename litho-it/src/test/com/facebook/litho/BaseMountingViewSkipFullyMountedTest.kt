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
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.config.UnmountOnDetach
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.visibility.onInvisible
import com.facebook.litho.visibility.onVisible
import com.facebook.litho.widget.SolidColor
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.MountExtension
import com.facebook.rendercore.extensions.VisibilityChangeReason
import com.facebook.rendercore.extensions.VisibleBoundsCallbacks
import com.facebook.rendercore.px
import com.facebook.rendercore.visibility.VisibilityBoundsTransformer
import com.facebook.rendercore.visibility.VisibilityOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** Tests when a fully shown [BaseMountingView] that has mounted all of itself skips its pass. */
@RunWith(LithoTestRunner::class)
class BaseMountingViewSkipFullyMountedTest {

  private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
  private var visible = 0
  private var invisible = 0

  @Test
  fun `skips a fully shown view that has mounted all of itself`() {
    val lithoView = showInRow(skip = true)
    val passes = countPasses(lithoView)

    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(passes.count).isEqualTo(0)
  }

  @Test
  fun `mounts a fully shown view when the flag is off`() {
    val lithoView = showInRow(skip = false)
    val passes = countPasses(lithoView)

    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(passes.count).isEqualTo(1)
  }

  @Test
  fun `mounts a view cut off by the edge of its parent`() {
    val lithoView = showInRow(skip = true)
    val passes = countPasses(lithoView)
    val row = lithoView.parent as View
    row.translationY = ((row.parent as View).height - VIEW_SIZE / 2).toFloat()

    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(passes.count).isEqualTo(1)
  }

  @Test
  fun `mounts a view scrolled fully back in once, then skips it`() {
    val lithoView = showInRow(skip = true)
    val row = lithoView.parent as View
    row.translationY = ((row.parent as View).height - VIEW_SIZE / 2).toFloat()
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)
    val passes = countPasses(lithoView)

    row.translationY = 0f
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(passes.count).isEqualTo(1)
  }

  @Test
  fun `mounts a view whose visible bounds a transformer redefines`() {
    val lithoView = showInRow(skip = true, transformer = mock<VisibilityBoundsTransformer>())
    val passes = countPasses(lithoView)

    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(passes.count).isEqualTo(1)
  }

  @Test
  fun `skips a moved view that its transformer leaves unchanged`() {
    val transformer = UnchangedBoundsTransformer(recordsAtEveryPass = true)
    val lithoView = showInRow(skip = false, transformer = transformer)
    val passes = countPasses(lithoView)

    lithoView.offsetTopAndBottom(VIEW_SIZE / 2)

    assertThat(passes.count).isEqualTo(0)
  }

  @Test
  fun `mounts a moved view whose transformer doesn't record at every pass`() {
    val transformer = UnchangedBoundsTransformer(recordsAtEveryPass = false)
    val lithoView = showInRow(skip = false, transformer = transformer)
    // This unchanged pass records the whole rect, so only recordsAtEveryPass stops the skip.
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)
    val passes = countPasses(lithoView)

    lithoView.offsetTopAndBottom(VIEW_SIZE / 2)

    assertThat(passes.count).isEqualTo(1)
  }

  @Test
  fun `dispatches visible again after the visibility hint returns to true`() {
    val lithoView = showInRow(skip = true)
    assertThat(visible).isEqualTo(1)

    lithoView.setVisibilityHint(false)
    lithoView.setVisibilityHint(true)

    assertThat(invisible).isEqualTo(1)
    assertThat(visible).isEqualTo(2)
  }

  @Test
  fun `dispatches visible again after unbind and rebind`() {
    val lithoView = showInRow(skip = true)
    assertThat(visible).isEqualTo(1)

    lithoView.unbind()
    lithoView.rebind()
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(invisible).isEqualTo(1)
    assertThat(visible).isEqualTo(2)
  }

  @Test
  fun `dispatches visible after a full mount that skipped visibility`() {
    val lithoView = showInRow(skip = true)
    lithoView.mountComponent(Rect(), true)
    lithoView.mountComponent(Rect(0, 0, VIEW_SIZE, VIEW_SIZE), false)
    assertThat(invisible).isEqualTo(1)

    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(visible).isEqualTo(2)
  }

  @Test
  fun `an explicit empty notification after a temporary detach still unmounts`() {
    val lithoView = showInRow(skip = true, unmountOnDetach = UnmountOnDetach.Always)
    val mountedItems = lithoView.mountDelegateTarget.getMountItemCount()

    lithoView.onStartTemporaryDetach()
    lithoView.notifyVisibleBoundsChanged(Rect(), VisibilityChangeReason.SCROLL)

    assertThat(lithoView.mountDelegateTarget.getMountItemCount()).isLessThan(mountedItems)
  }

  @Test
  fun `dispatches visible again for a row the list reuses on a data set change`() {
    val lithoView = createLithoView(skip = true, transformer = null)
    val rowView =
        FrameLayout(activity).apply {
          layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT)
          addView(lithoView, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
        }
    val adapter =
        object : BaseAdapter() {
          override fun getCount(): Int = 1

          override fun getItem(position: Int): Any = position

          override fun getItemId(position: Int): Long = position.toLong()

          override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
              convertView ?: rowView
        }
    activity.setContentView(ListView(activity).apply { this.adapter = adapter })
    idle()
    assertThat(visible).isEqualTo(1)

    adapter.notifyDataSetChanged()
    idle()
    lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.SCROLL)

    assertThat(invisible).isEqualTo(1)
    assertThat(visible).isEqualTo(2)
  }

  /** Shows a [LithoView] at the top of a row, as in a chat bubble, and lays it out. */
  private fun showInRow(
      skip: Boolean,
      transformer: VisibilityBoundsTransformer? = null,
      unmountOnDetach: UnmountOnDetach = UnmountOnDetach.Never,
  ): LithoView {
    val lithoView = createLithoView(skip, transformer, unmountOnDetach)
    val row =
        FrameLayout(activity).apply {
          addView(lithoView, FrameLayout.LayoutParams(VIEW_SIZE, VIEW_SIZE))
        }
    val list =
        FrameLayout(activity).apply {
          addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT))
        }
    activity.setContentView(list)
    idle()
    return lithoView
  }

  private fun createLithoView(
      skip: Boolean,
      transformer: VisibilityBoundsTransformer?,
      unmountOnDetach: UnmountOnDetach = UnmountOnDetach.Never,
  ): LithoView {
    val config =
        ComponentsConfiguration.defaultInstance.copy(
            skipMountWhenFullyShownAndMounted = skip,
            visibilityBoundsTransformer = transformer,
            unmountOnDetach = unmountOnDetach,
        )
    return LithoView(activity).apply {
      componentTree =
          ComponentTree.create(ComponentContext(activity), VisibilityCounting())
              .componentsConfiguration(config)
              .build()
    }
  }

  @Suppress("DEPRECATION")
  private fun countPasses(lithoView: LithoView): PassCounter =
      PassCounter().also {
        requireNotNull(lithoView.mountDelegateTarget.getMountDelegate()).registerMountExtension(it)
      }

  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  /** Counts the visible-bounds passes that reach the mount extensions. */
  private class PassCounter : MountExtension<Any?, Unit>(), VisibleBoundsCallbacks<Unit> {
    var count = 0

    override fun createState() = Unit

    override fun onVisibleBoundsChanged(
        extensionState: ExtensionState<Unit>,
        localVisibleRect: Rect?,
        reason: VisibilityChangeReason,
    ) {
      count++
    }
  }

  /** Leaves every visible rect as it is. */
  private class UnchangedBoundsTransformer(private val recordsAtEveryPass: Boolean) :
      VisibilityBoundsTransformer {
    override fun getTransformedLocalVisibleRect(host: ViewGroup, outRect: Rect) {
      if (!host.getLocalVisibleRect(outRect)) {
        outRect.setEmpty()
      }
    }

    override fun getViewportRect(view: View, outRect: Rect) {
      view.getLocalVisibleRect(outRect)
    }

    override fun shouldUseTransformedVisibleRect(visibilityOutput: VisibilityOutput): Boolean = true

    override fun shouldRecordTransformedVisibleRectAtEveryPass(): Boolean = recordsAtEveryPass
  }

  private inner class VisibilityCounting : KComponent() {
    override fun ComponentScope.render(): Component = SolidColor(
        Color.BLACK,
        style =
            Style.width(VIEW_SIZE.px)
                .height(VIEW_SIZE.px)
                .onVisible { visible++ }
                .onInvisible { invisible++ },
    )
  }

  private companion object {
    const val VIEW_SIZE = 100
    const val ROW_HEIGHT = 300
  }
}
