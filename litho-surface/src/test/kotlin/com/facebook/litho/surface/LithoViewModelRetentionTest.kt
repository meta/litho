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

package com.facebook.litho.surface

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.State
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useSaveableState
import com.facebook.rendercore.ErrorReporter
import com.facebook.rendercore.LogLevel
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalLithoApi::class)
@SuppressLint("KComponentSyncUpdateDetector")
@RunWith(LithoTestRunner::class)
class LithoViewModelRetentionTest {

  private val reports = mutableListOf<Report>()
  private val previousErrorReporter = ErrorReporter.instance

  @Before
  fun setUp() {
    ErrorReporter.provide { level, categoryKey, message, cause, _, _ ->
      reports += Report(level, categoryKey, message, cause)
    }
  }

  @After
  fun tearDown() {
    ErrorReporter.provide(previousErrorReporter)
    SaveableStateActivity.state.set(null)
  }

  @Test
  fun `close actions all run once in registration order and the first failure is reported`() {
    val ownerStore = ViewModelStore()
    val host = createHost(ownerStore)
    val calls = mutableListOf<String>()
    val firstFailure = Error("first close action failure")
    val secondFailure = IllegalStateException("second close action failure")
    host.onSurfaceClose { calls += "first" }
    host.onSurfaceClose {
      calls += "failing"
      throw firstFailure
    }
    host.onSurfaceClose {
      calls += "also failing"
      throw secondFailure
    }
    host.onSurfaceClose { calls += "last" }

    ownerStore.clear()

    assertThat(calls).containsExactly("first", "failing", "also failing", "last")
    assertThat(reports).hasSize(1)
    val report = reports.single()
    assertThat(report.level).isEqualTo(LogLevel.ERROR)
    assertThat(report.categoryKey).isEqualTo(LithoViewModel.CLOSE_ACTION_FAILED_CATEGORY)
    assertThat(report.message).contains("[test]")
    assertThat(report.cause).isSameAs(firstFailure)
    assertThat(firstFailure.suppressed).containsExactly(secondFailure)
    assertThatThrownBy { host.onSurfaceClose { calls += "unexpected" } }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("cleared")
    ownerStore.clear()
    assertThat(calls).containsExactly("first", "failing", "also failing", "last")
    assertThat(reports).hasSize(1)
  }

  @Test
  fun `close actions that throw the same Throwable all run and it is reported once`() {
    val ownerStore = ViewModelStore()
    val host = createHost(ownerStore)
    val calls = mutableListOf<String>()
    val sharedFailure = IllegalStateException("shared close action failure")
    host.onSurfaceClose {
      calls += "first"
      throw sharedFailure
    }
    host.onSurfaceClose {
      calls += "second"
      throw sharedFailure
    }
    host.onSurfaceClose { calls += "last" }

    ownerStore.clear()

    assertThat(calls).containsExactly("first", "second", "last")
    assertThat(reports.map { it.cause }).containsExactly(sharedFailure)
    assertThat(sharedFailure.suppressed).isEmpty()
  }

  @Test
  fun `close actions that succeed report nothing`() {
    val ownerStore = ViewModelStore()
    val host = createHost(ownerStore)
    host.onSurfaceClose {}

    ownerStore.clear()

    assertThat(reports).isEmpty()
  }

  @Test
  fun `saved state owner must be restored before setting Litho content`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val ownerStore = ViewModelStore()
    val host = createHost(ownerStore)
    val owner = TestSavedStateOwner(restoredState = null, restore = false)
    host.bind(
        contextProvider = { context },
        instanceOwnerProvider = { owner },
        viewOwnerProvider = { owner },
    )

    assertThatThrownBy { host.setLithoContent { EmptyComponent() } }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("after the owner's saved state is restored")

    ownerStore.clear()
  }

  @Test
  fun `owner without saved state support is rejected during host setup`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val ownerStore = ViewModelStore()
    val host = createHost(ownerStore)
    val owner = TestLifecycleOwner()
    host.bind(
        contextProvider = { context },
        instanceOwnerProvider = { owner },
        viewOwnerProvider = { owner },
    )
    assertThatThrownBy { host.setLithoContent { EmptyComponent() } }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("requires an owner that implements SavedStateRegistryOwner")

    ownerStore.clear()
  }

  /**
   * ```text
   * First Activity                     Recreated Activity
   * --------------                     ------------------
   * Render state = 0
   * Update state = 9
   * Android requests saved state
   * Registry snapshots 9       ----->  Android restores the Bundle
   * Destroy Activity                   Create a new LithoViewModel and tree
   * Clear the old LithoViewModel       First render restores state = 9
   * ```
   */
  @Test
  fun `new Android owner restores saveable state`() {
    SaveableStateActivity.state.set(null)
    val firstController =
        Robolectric.buildActivity(SaveableStateActivity::class.java).create().start().resume()
    val firstState = checkNotNull(SaveableStateActivity.state.get())
    firstState.updateSync(9)
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    val savedState = Bundle()

    firstController.pause().saveInstanceState(savedState).stop().destroy()
    ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    SaveableStateActivity.state.set(null)
    Robolectric.buildActivity(SaveableStateActivity::class.java).create(savedState).start().resume()

    assertThat(checkNotNull(SaveableStateActivity.state.get()).value).isEqualTo(9)
  }

  @SuppressLint("EndpointWithoutSwitchOff")
  private class Report(
      val level: LogLevel,
      val categoryKey: String,
      val message: String,
      val cause: Throwable?,
  )

  class SaveableStateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      setLithoContent { SaveableStateComponent(state::set) }
      val view = getLithoView()
      setContentView(view)
      render(view)
    }

    companion object {
      val state = AtomicReference<State<Int>>()
    }
  }

  private class SaveableStateComponent(
      private val onState: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useSaveableState { 0 }
      onState(state)
      return EmptyComponent()
    }
  }

  private class CapturingSaveableStateComponent(
      private val onState: (State<Int>) -> Unit,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val state = useSaveableState { 0 }
      onState(state)
      return EmptyComponent()
    }
  }

  private class TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    init {
      registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
      registry.currentState = Lifecycle.State.DESTROYED
    }
  }

  private class TestSavedStateOwner(
      restoredState: Bundle? = null,
      restore: Boolean = true,
  ) : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    init {
      controller.performAttach()
      if (restore) {
        controller.performRestore(restoredState)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
      }
    }

    fun save(): Bundle = Bundle().also(controller::performSave)
  }

  private companion object {
    const val VIEW_SIZE_PX = 100

    fun createHost(ownerStore: ViewModelStore): LithoViewModel =
        ViewModelProvider.create(
                ownerStore,
                LithoViewModelFactory(
                    key = "test",
                    componentsConfiguration = null,
                    treePropsFactory = null,
                    isExplicitlyConfigured = false,
                ),
            )["test", LithoViewModel::class]

    fun render(view: LithoView) {
      val sizeSpec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE_PX, View.MeasureSpec.EXACTLY)
      view.measure(sizeSpec, sizeSpec)
      view.layout(0, 0, VIEW_SIZE_PX, VIEW_SIZE_PX)
    }
  }
}
