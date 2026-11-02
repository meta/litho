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

package com.facebook.litho.animation

import android.graphics.Color
import android.os.Looper.getMainLooper
import androidx.collection.mutableScatterMapOf
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.GlobalKey
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Tests [ReflowAnimationStore] — the tree-scoped store of per-node animated reflow sizes that
 * stands in for a hook slot on `Style.transition`-declared reflow nodes. In production the tree's
 * `ReflowProvider` (which it implements) exposes an immutable [ReflowGeneration] via `reflowView`;
 * here it is constructed directly for isolation, and a component mints through it (via
 * [ReflowAnimationStore.rebuildKeySet]) using its own `lithoTree`, so this exercises the real class
 * end to end: a container-less `AnimatedValue<IntSize>` read at layout drives a real per-frame
 * sibling reflow, converges, drains cleanly, and is mint-persisted / evicted / released per the
 * store's lifecycle contract.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class ReflowAnimationStoreTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var store: ReflowAnimationStore? = null
  private var capturedKey: GlobalKey? = null

  @Test
  fun retarget_readAtLayout_reflowsSiblingProgressively_convergesAndDrains() {
    val testView = lithoTestRule.render { GrowComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(testView.findViewWithTagOrNull(SIBLING_TAG)?.top).isEqualTo(100)

    val reflowStore = checkNotNull(store)
    val key = checkNotNull(capturedKey)
    reflowStore.retarget(
        key,
        IntSize(100, 250),
        IntSize(100, 100),
        tween(duration = 144.ms, easing = Easing.Linear),
    )

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = checkNotNull(testView.findViewWithTagOrNull(SIBLING_TAG)?.top)
    assertThat(mid).isGreaterThan(100)
    assertThat(mid).isLessThan(250)

    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(testView.findViewWithTagOrNull(SIBLING_TAG)?.top).isEqualTo(250)
    assertThat(animationRule.hasScheduledFrames()).isFalse()

    // Drain: a further idle must reach quiescence (idle() throws on an infinite relayout loop) and
    // the settled value must hold.
    lithoTestRule.idle()
    testView.measure().layout()
    assertThat(testView.findViewWithTagOrNull(SIBLING_TAG)?.top).isEqualTo(250)
  }

  @Test
  fun rebuildKeySet_keepsSameInstanceForSurvivingKey() {
    lithoTestRule.render { GrowComponent() }
    shadowOf(getMainLooper()).idle()
    val reflowStore = checkNotNull(store)
    val key = checkNotNull(capturedKey)

    val first = checkNotNull(reflowStore.peek(key))
    // A rebuild that keeps the key reuses its existing entry by identity (seed ignored for
    // survivors).
    reflowStore.rebuildKeySet(mutableScatterMapOf(key to IntSize(2, 2)))
    val second = checkNotNull(reflowStore.peek(key))
    assertThat(first).isSameAs(second)
  }

  @Test
  fun rebuildKeySet_dropsAndStopsDepartedValues() {
    lithoTestRule.render { GrowComponent() }
    shadowOf(getMainLooper()).idle()
    val reflowStore = checkNotNull(store)
    val key = checkNotNull(capturedKey)

    reflowStore.retarget(
        key,
        IntSize(100, 250),
        IntSize(100, 100),
        tween(duration = 1_000.ms, easing = Easing.Linear),
    )
    animationRule.advanceBy(100.ms)
    assertThat(checkNotNull(reflowStore.peek(key)).isRunning).isTrue()

    reflowStore.rebuildKeySet(
        mutableScatterMapOf(),
    ) // no live keys → the running value is stopped + dropped
    assertThat(reflowStore.peek(key)).isNull()
  }

  @Test
  fun rebuildKeySet_keepsLiveValues() {
    lithoTestRule.render { GrowComponent() }
    shadowOf(getMainLooper()).idle()
    val reflowStore = checkNotNull(store)
    val key = checkNotNull(capturedKey)

    reflowStore.rebuildKeySet(mutableScatterMapOf(key to IntSize(100, 100)))
    assertThat(reflowStore.peek(key)).isNotNull
  }

  @Test
  fun release_stopsAndClearsEverything() {
    lithoTestRule.render { GrowComponent() }
    shadowOf(getMainLooper()).idle()
    val reflowStore = checkNotNull(store)
    val key = checkNotNull(capturedKey)

    reflowStore.release()
    assertThat(reflowStore.peek(key)).isNull()
  }

  @Test
  fun publishedView_reflectsTargetAndRunningState() {
    lithoTestRule.render { GrowComponent() }
    shadowOf(getMainLooper()).idle()
    val reflowStore = checkNotNull(store)
    val key = checkNotNull(capturedKey)

    reflowStore.retarget(
        key,
        IntSize(100, 250),
        IntSize(100, 100),
        tween(duration = 144.ms, easing = Easing.Linear),
    )
    animationRule.advanceBy(72.ms)

    // Minting published the generation; a retarget mutates the shared entry's live leaves in place,
    // so the already-published view reflects it with no republish.
    val view = reflowStore.reflowView
    assertThat(view.targetSize(key)).isEqualTo(IntSize(100, 250))
    assertThat(view.currentSizeIfRunning(key)).isNotNull

    reflowStore.release()
    val released = reflowStore.reflowView
    assertThat(released.currentSizeIfRunning(key)).isNull()
    assertThat(released.targetSize(key)).isNull()
  }

  private inner class GrowComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val lithoTree = checkNotNull(context.lithoTree)
      val key = context.globalKey
      capturedKey = key
      val reflowStore = store ?: ReflowAnimationStore(lithoTree).also { store = it }
      if (key !in reflowStore)
          reflowStore.rebuildKeySet(mutableScatterMapOf(key to IntSize(100, 100)))
      val size = checkNotNull(reflowStore.peek(key))
      return Column(style = Style.width(100.px)) {
        child(Row(style = Style.size(size).backgroundColor(Color.RED).viewTag(GROW_TAG)))
        child(
            Row(
                style =
                    Style.width(100.px)
                        .height(100.px)
                        .backgroundColor(Color.BLUE)
                        .viewTag(SIBLING_TAG),
            ),
        )
      }
    }
  }

  companion object {
    private const val GROW_TAG = "reg_reflow_grow"
    private const val SIBLING_TAG = "reg_reflow_sibling"
  }
}
