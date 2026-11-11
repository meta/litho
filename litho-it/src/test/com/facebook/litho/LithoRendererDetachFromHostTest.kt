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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/**
 * A renderer detached from its host keeps the effects of its bound layout, updates them with the
 * layouts it commits, and shows that layout in the next host.
 */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class LithoRendererDetachFromHostTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val constraints =
      SizeConstraints(minWidth = 100, maxWidth = 100, minHeight = 100, maxHeight = 100)

  @Test
  fun `a detached layout is shown in the next host without running its effects again`() {
    val renderer = LithoRenderer(context = context)
    val events = mutableListOf<String>()
    val root = EffectRow(events)
    val firstView = LithoRenderTreeView(context)
    renderer.bindTo(firstView)
    renderer.renderSyncWithCompatibilityChecks(root, constraints)
    idle()
    val shown = checkNotNull(firstView.layoutState)

    renderer.detachFromHost()
    firstView.clean()
    val secondView = LithoRenderTreeView(context)
    renderer.bindTo(secondView)
    renderer.renderSyncWithCompatibilityChecks(root, constraints)
    idle()

    assertThat(secondView.layoutState).isSameAs(shown)
    assertThat(events).containsExactly("attach")
    renderer.release()
    assertThat(events).containsExactly("attach", "cleanup")
  }

  @Test
  fun `a detached layout kept by the committed layout path is shown in the next host`() {
    val renderer = LithoRenderer(context = context)
    val events = mutableListOf<String>()
    val root = EffectRow(events)
    val firstView = LithoRenderTreeView(context)
    renderer.bindTo(firstView)
    renderer.renderSyncWithCompatibilityChecks(root, constraints)
    idle()

    renderer.detachFromHost()
    firstView.clean()
    val secondView = LithoRenderTreeView(context)
    renderer.bindTo(secondView)
    val kept = renderer.renderAsyncKeepingCommittedLayout(root, constraints)
    idle()

    assertThat(kept).isTrue()
    assertThat(secondView.layoutState).isNotNull
    assertThat(events).containsExactly("attach")
    renderer.release()
  }

  @Test
  fun `cleaning detached effects from an effect's attach callback doesn't throw`() {
    val renderer = LithoRenderer(context = context)
    val events = mutableListOf<String>()
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)
    renderer.renderSyncWithCompatibilityChecks(EffectRow(events), constraints)
    idle()

    renderer.detachFromHost()
    view.clean()
    val reentrantRoot = ReentrantRow(events) { renderer.cleanupDetachedEffects() }
    renderer.renderSyncWithCompatibilityChecks(reentrantRoot, constraints)
    idle()

    assertThat(events).contains("attach:reentrant")
    renderer.release()
  }

  @Test
  fun `binding a newer layout keeps the mount updates of the layout committed while detached`() {
    val renderer = LithoRenderer(context = context)
    val counter = Counter()
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)
    renderer.renderSyncWithCompatibilityChecks(CountRow(counter, label = "A"), constraints)
    idle()

    // The view is not cleaned, so the same host is bound again.
    renderer.detachFromHost()
    checkNotNull(counter.increment).invoke()
    idle()
    renderer.bindTo(view)
    renderer.renderSyncWithCompatibilityChecks(CountRow(counter, label = "C"), constraints)
    idle()

    val bound = checkNotNull(view.layoutState)
    assertThat(bound.treeState.consumeMountUpdates())
        .describedAs("state updated while detached is still dirty for mount")
        .isNotEmpty()
    renderer.release()
  }

  @Test
  fun `rebinding the layout committed while detached keeps its mount updates`() {
    val renderer = LithoRenderer(context = context)
    val counter = Counter()
    val root = CountRow(counter, label = "A")
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)
    renderer.renderSyncWithCompatibilityChecks(root, constraints)
    idle()

    renderer.detachFromHost()
    checkNotNull(counter.increment).invoke()
    idle()
    renderer.bindTo(view)
    renderer.renderSyncWithCompatibilityChecks(root, constraints)
    idle()

    val bound = checkNotNull(view.layoutState)
    assertThat(bound.treeState.consumeMountUpdates()).isNotEmpty()
    renderer.release()
  }

  /**
   * Version 1 adds an effect whose attach callback renders version 2 on the same renderer while no
   * host is bound. Version 2 is applied once version 1's effects are attached.
   */
  @Test
  fun `an effect of a detached row that renders a newer layout while it attaches doesn't throw`() {
    val renderer = LithoRenderer(context = context)
    val events = mutableListOf<String>()
    val view = LithoRenderTreeView(context)
    renderer.bindTo(view)
    renderer.renderSyncWithCompatibilityChecks(VersionedRow(events, version = 0), constraints)
    idle()
    renderer.detachFromHost()
    view.clean()
    val renderVersion2 = {
      renderer.renderSyncWithCompatibilityChecks(VersionedRow(events, version = 2), constraints)
    }

    renderer.renderSyncWithCompatibilityChecks(
        VersionedRow(events, version = 1, onAttach = renderVersion2),
        constraints,
    )
    idle()

    assertThat(events).containsExactly("attach:0", "cleanup:0", "attach:1", "cleanup:1", "attach:2")
    renderer.release()
    assertThat(events.last()).isEqualTo("cleanup:2")
  }

  @Test
  fun `a layout rendered before any host doesn't attach its effects`() {
    val renderer = LithoRenderer(context = context)
    val events = mutableListOf<String>()
    renderer.renderSyncWithCompatibilityChecks(EffectRow(events), constraints)
    idle()

    assertThat(events).isEmpty()
    renderer.release()
  }

  private class EffectRow(private val events: MutableList<String>) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(Unit) {
        events.add("attach")
        onCleanup { events.add("cleanup") }
      }
      return Column(style = Style.width(100.px).height(100.px))
    }
  }

  private class ReentrantRow(
      private val events: MutableList<String>,
      private val onAttach: () -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(Unit) {
        events.add("attach")
        onCleanup { events.add("cleanup") }
      }
      useEffect("reentrant") {
        events.add("attach:reentrant")
        onAttach()
        onCleanup { events.add("cleanup:reentrant") }
      }
      return Column(style = Style.width(100.px).height(100.px))
    }
  }

  private class VersionedRow(
      private val events: MutableList<String>,
      private val version: Int,
      private val onAttach: () -> Unit = {},
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      useEffect(version) {
        events.add("attach:$version")
        onAttach()
        onCleanup { events.add("cleanup:$version") }
      }
      return Column(style = Style.width(100.px).height(100.px))
    }
  }

  private class Counter {
    var increment: (() -> Unit)? = null
  }

  private class CountRow(private val counter: Counter, private val label: String) : KComponent() {
    override fun ComponentScope.render(): Component {
      val count = useState { 0 }
      counter.increment = { count.update { it + 1 } }
      val height = 100 + count.value
      return Column(style = Style.width(100.px).height(height.px))
    }
  }

  private fun idle() {
    repeat(3) {
      ShadowLooper.idleMainLooper()
      lithoTestRule.idle()
    }
  }
}
