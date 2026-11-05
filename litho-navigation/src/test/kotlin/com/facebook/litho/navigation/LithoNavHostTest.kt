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

@file:OptIn(ExperimentalLithoApi::class)

package com.facebook.litho.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.ComponentTree
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoView
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.lifecycle.LifecycleOwnerTreeProp
import com.facebook.litho.onCleanup
import com.facebook.litho.surface.lithoHost
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.TestLithoView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.useSaveableState
import com.facebook.litho.useState
import com.facebook.ultralight.testing.statics.ULStaticsTestRule
import com.meta.foa.datasource.DataSource
import com.meta.foa.navigation.core.BackStackHolder
import com.meta.foa.navigation.core.BackStackTracer
import com.meta.foa.navigation.core.FoaBackStack
import com.meta.foa.navigation.core.FoaBackStackSessionProvider
import com.meta.foa.navigation.core.FoaDestinationDataSourceFactory
import com.meta.foa.navigation.core.FoaDestinationSpecContribution
import com.meta.foa.navigation.core.FoaDestinationSpecs
import com.meta.foa.navigation.core.FoaRenderer
import com.meta.foa.navigation.core.NoOpBackStackTracer
import com.meta.foa.navigation.core.RegisteredFoaDestinationSpec
import com.meta.foa.navigation.core.destination
import com.meta.foa.session.FoaUserSession
import com.meta.foa.testing.fakes.FakeFoaUserSession
import com.meta.foa.testing.ultralight.FoaULStaticsTestRule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

@Serializable
// kotlinx-serialization's generated $$serializer, and the data class's generated members
@SuppressLint("DeprecatedClass", "MissingTestAnnotation")
@DataClassGenerate(toString = Mode.KEEP, equalsHashCode = Mode.KEEP)
private data class CounterDestination(val name: String) : LithoDestination

@Serializable
// kotlinx-serialization's generated $$serializer, and the data class's generated members
@SuppressLint("DeprecatedClass", "MissingTestAnnotation")
@DataClassGenerate(toString = Mode.KEEP, equalsHashCode = Mode.KEEP)
private data class ProfileDestination(val id: Int) : LithoDestination

@Serializable
// kotlinx-serialization's generated $$serializer, and the data class's generated members
@SuppressLint("DeprecatedClass", "MissingTestAnnotation")
@DataClassGenerate(toString = Mode.KEEP, equalsHashCode = Mode.KEEP)
private data class NotLithoDestination(val id: Int) : LithoDestination

@Serializable
// kotlinx-serialization's generated $$serializer, and the data class's generated members
@SuppressLint("DeprecatedClass", "MissingTestAnnotation")
@DataClassGenerate(toString = Mode.KEEP, equalsHashCode = Mode.KEEP)
private data class ModelDestination(val name: String) : LithoDestination

@Serializable
// kotlinx-serialization's generated $$serializer, and the data class's generated members
@SuppressLint("DeprecatedClass", "MissingTestAnnotation")
@DataClassGenerate(toString = Mode.KEEP, equalsHashCode = Mode.KEEP)
private data class NavigatingDestination(val id: Int) : LithoDestination

@Serializable
// kotlinx-serialization's generated $$serializer, and the data class's generated members
@SuppressLint("DeprecatedClass", "MissingTestAnnotation")
@DataClassGenerate(toString = Mode.KEEP, equalsHashCode = Mode.KEEP)
private data class ScopeDestination(val id: Int) : LithoDestination

private class RecordingTracer : BackStackTracer {
  val events: MutableList<String> = CopyOnWriteArrayList()

  override fun onStackChanged(topIndex: Int) {
    events += "changed:$topIndex"
  }

  override fun onScreenRendered(topIndex: Int) {
    events += "rendered:$topIndex"
  }
}

/** The states of each shown [CounterScreen], by name. */
private val SCREENS: MutableMap<String, ScreenStates> = ConcurrentHashMap()

private val COUNTER_RENDERER_CALLS = AtomicInteger()

/** What a [CounterScreen] does when it is shown, by name: given its entry's lifecycle owner. */
private val SCREEN_EFFECTS: MutableMap<String, (LifecycleOwner, Context) -> (() -> Unit)?> =
    ConcurrentHashMap()

private val EVENTS: MutableList<String> = CopyOnWriteArrayList()

/** Hosts handle back and record each close request in [EVENTS]. */
private val CLOSE_TO_EVENTS: LithoNavBackPress = LithoNavBackPress.HostManaged { EVENTS += "close" }

private val SESSION_STATE = AtomicReference<State<FoaBackStackSessionProvider>?>()

/** The sessions the profile data source was created with, in call order. */
private val PROFILE_SESSIONS: MutableList<FoaUserSession> = CopyOnWriteArrayList()

/** What the profile renderer read from its scope, in call order. */
private val PROFILE_RENDERS: MutableList<ProfileRender> = mutableListOf()

/** The seeds NavHostActivity's hosts were given, in call order. */
private val SEEDS: MutableList<FoaBackStack<LithoDestination>> = mutableListOf()

/** The back stack the navigating renderer pushes onto while it runs. */
private val NAVIGATING_STACK = AtomicReference<FoaBackStack<LithoDestination>?>()

private class ProfileRender(
    val viewModelStore: ViewModelStore,
    val source: ProfileSource,
    val session: FoaUserSession,
)

/** The ViewModel the model renderer got, in call order. */
private val MODEL_RENDERS: MutableList<NameViewModel> = mutableListOf()

/** The ViewModel the model renderer got under the key "other", in call order. */
private val KEYED_RENDERS: MutableList<NameViewModel> = mutableListOf()

/** The scope the scope renderer was given, kept past its call. */
private val SAVED_SCOPE = AtomicReference<LithoEntryScope<*>?>()

private class NameViewModel(val name: String) : ViewModel() {
  var cleared: Boolean = false
    private set

  override fun onCleared() {
    cleared = true
  }
}

private class ScreenStates(
    val saveable: State<Int>,
    val plain: State<Int>,
    val lifecycleOwner: LifecycleOwner?,
    val backStack: FoaBackStack<LithoDestination>,
)

// findComponent needs the screen to leave a layout node.
@SuppressLint("KComponentEmptyContainerReturnDetector")
private class CounterScreen(private val name: String) : KComponent() {
  override fun ComponentScope.render(): Component {
    val lifecycleOwner = LifecycleOwnerTreeProp.value
    SCREENS[name] =
        ScreenStates(
            saveable = useSaveableState { 0 },
            plain = useState { 0 },
            lifecycleOwner = lifecycleOwner,
            backStack = useBackStack(),
        )
    val androidContext = context.androidContext
    useEffect(lifecycleOwner) {
      val cleanup = lifecycleOwner?.let { SCREEN_EFFECTS[name]?.invoke(it, androidContext) }
      onCleanup { cleanup?.invoke() }
    }
    return Column()
  }
}

/** Reads the back stack of the nearest nav host. */
private class BackStackReader : KComponent() {
  override fun ComponentScope.render(): Component {
    useBackStack()
    return EmptyComponent()
  }
}

/**
 * Shows a nav host with a session provider it can change, so the host's effect runs again in place.
 */
private class SessionParent(
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val backStackHolder: BackStackHolder,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val handle: LithoNavHostHandle,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    val sessionProvider = useState<FoaBackStackSessionProvider> { TestSessionProvider() }
    SESSION_STATE.set(sessionProvider)
    return LithoNavHost(
        "session",
        backStackHolder,
        FoaBackStack.create(CounterDestination("a")),
        sessionProvider.value,
        handle,
        ownerLifecycle = null,
        NoOpBackStackTracer,
        backPress = CLOSE_TO_EVENTS,
        Style,
        SPECS,
    )
  }
}

private class TestSessionProvider(
    override val foaSession: FoaUserSession = FakeFoaUserSession(),
) : FoaBackStackSessionProvider

private class ProfileSource(val id: Int) : DataSource {
  override fun prepare() = Unit

  override fun prefetch() = Unit

  override fun close() = Unit
}

private object ProfileSourceFactory :
    FoaDestinationDataSourceFactory<ProfileDestination, ProfileSource> {
  override fun create(session: FoaUserSession, args: ProfileDestination): ProfileSource {
    PROFILE_SESSIONS += session
    return ProfileSource(args.id)
  }
}

private class TestContribution<D : LithoDestination>(
    override val destinationType: KClass<D>,
    private val spec: RegisteredFoaDestinationSpec<D>,
) : FoaDestinationSpecContribution<D> {
  override fun create(): RegisteredFoaDestinationSpec<D> = spec
}

private val SPECS: FoaDestinationSpecs = FoaDestinationSpecs(
    listOf(
        TestContribution(
            CounterDestination::class,
            destination(
                renderer =
                    lithoRenderer {
                      COUNTER_RENDERER_CALLS.incrementAndGet()
                      CounterScreen(destination.name)
                    },
            ),
        ),
        TestContribution(
            ProfileDestination::class,
            destination(
                renderer =
                    lithoRenderer {
                      PROFILE_RENDERS +=
                          ProfileRender(viewModelStore, dataSource(ProfileSourceFactory), session)
                      EmptyComponent()
                    },
                dataSource = ProfileSourceFactory,
            ),
        ),
        TestContribution(
            ModelDestination::class,
            destination(
                renderer =
                    lithoRenderer {
                      MODEL_RENDERS +=
                          viewModel(NameViewModel::class) { NameViewModel(destination.name) }
                      KEYED_RENDERS +=
                          viewModel(NameViewModel::class, key = "other") { NameViewModel("other") }
                      EmptyComponent()
                    },
            ),
        ),
        TestContribution(
            NavigatingDestination::class,
            destination(
                renderer =
                    lithoRenderer {
                      NAVIGATING_STACK.get()?.push(CounterDestination("z"))
                      EmptyComponent()
                    },
            ),
        ),
        TestContribution(
            ScopeDestination::class,
            destination(
                renderer =
                    lithoRenderer {
                      SAVED_SCOPE.set(this)
                      EmptyComponent()
                    },
            ),
        ),
        TestContribution(
            NotLithoDestination::class,
            destination(renderer = object : FoaRenderer<NotLithoDestination> {}),
        ),
    ),
)

@SuppressLint("KComponentSyncUpdateDetector")
@RunWith(LithoTestRunner::class)
class LithoNavHostTest {

  private val ulStaticsRule = ULStaticsTestRule()
  private val lithoTestRule = LithoTestRule()

  @get:Rule
  val rules: RuleChain =
      RuleChain.outerRule(ulStaticsRule)
          .around(FoaULStaticsTestRule(ulStaticsRule))
          .around(lithoTestRule)

  private val sessionProvider = TestSessionProvider()

  @After
  fun tearDown() {
    SCREENS.clear()
    COUNTER_RENDERER_CALLS.set(0)
    PROFILE_RENDERS.clear()
    MODEL_RENDERS.clear()
    KEYED_RENDERS.clear()
    SAVED_SCOPE.set(null)
    SCREEN_EFFECTS.clear()
    EVENTS.clear()
    SESSION_STATE.set(null)
    PROFILE_SESSIONS.clear()
    SEEDS.clear()
    NAVIGATING_STACK.set(null)
    NavHostActivity.content = null
  }

  @Test
  fun `a covered screen gets its saveable values back and starts its plain state over`() {
    val backStack = backStackOf(CounterDestination("a"))
    val view = render(backStack)
    screen("a").saveable.commit(5)
    screen("a").plain.commit(5)

    navigate { backStack.push(CounterDestination("b")) }
    assertThat(screen("b").saveable.value).isEqualTo(0)
    navigate { backStack.pop() }

    assertThat(screen("a").saveable.value).isEqualTo(5)
    assertThat(screen("a").plain.value).isEqualTo(0)
    view.release()
  }

  @Test
  fun `a screen pushed right after a pop starts fresh`() {
    val backStack = backStackOf(CounterDestination("a"))
    val view = render(backStack)
    navigate { backStack.push(CounterDestination("b")) }
    screen("b").saveable.commit(9)
    screen("b").plain.commit(9)
    SCREENS.remove("a")

    navigate {
      backStack.pop()
      backStack.push(CounterDestination("c"))
    }

    assertThat(SCREENS).describedAs("screens rendered between pop and push").doesNotContainKey("a")
    assertThat(screen("c").saveable.value).isEqualTo(0)
    assertThat(screen("c").plain.value).isEqualTo(0)
    view.release()
  }

  @Test
  fun `entries that leave the stack are not saved`() {
    val owner = TestSavedStateOwner()
    val backStack = backStackOf(CounterDestination("a"))
    val view = render(owner, backStack)
    navigate { backStack.push(CounterDestination("b")) }
    screen("b").saveable.commit(REMOVED_VALUE)
    navigate { backStack.push(CounterDestination("c")) }

    navigate { backStack.popToRoot() }
    navigate { backStack.push(CounterDestination("b")) }

    assertThat(screen("b").saveable.value).isEqualTo(0)
    assertThat(owner.save().containsValue(REMOVED_VALUE)).isFalse()
    view.release()
  }

  /**
   * ```text
   * a = 5, push x, pop, push b, b = 9 ── process death ──> b restores 9, pop, a restores 5
   * ```
   *
   * Pushing and popping x makes the saved entry ids differ from the ones a new host hands out.
   */
  @Test
  fun `screens get their saveable values back after process death`() {
    val first = launch(savedState = null)
    screen("a").saveable.updateSync(5)
    layOut(first.get().view)
    first.navigate { push(CounterDestination("x")) }
    first.navigate { pop() }
    first.navigate { push(CounterDestination("b")) }
    screen("b").saveable.updateSync(9)
    layOut(first.get().view)
    val savedState = recreate(first)

    val second = launch(savedState)
    assertThat(second.get().backStack.backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    assertThat(screen("b").saveable.value).isEqualTo(9)
    second.navigate { pop() }

    assertThat(screen("a").saveable.value).isEqualTo(5)
    second.pause().stop().destroy()
  }

  @Test
  fun `a different back stack restored into the tree starts its screens fresh`() {
    val firstOwner = TestSavedStateOwner()
    val first = backStackOf(CounterDestination("a"))
    val firstView = render(firstOwner, first)
    screen("a").saveable.commit(COVERED_VALUE)
    navigate { first.push(CounterDestination("b")) }
    screen("b").saveable.commit(TOP_VALUE)
    val savedState = firstOwner.save()
    firstView.release()
    SCREENS.clear()

    val owner = TestSavedStateOwner(savedState)
    val view = render(owner, backStackOf(CounterDestination("a")))

    assertThat(screen("a").saveable.value).isEqualTo(0)
    view.release()
  }

  @Test
  fun `the renderer gets the entry's ViewModelStore, its prepared data source and the session`() {
    val backStack = backStackOf(ProfileDestination(1))
    val view = render(backStack)

    navigate { backStack.push(ProfileDestination(2)) }

    assertThat(PROFILE_RENDERS).hasSize(2)
    PROFILE_RENDERS.forEachIndexed { index, render ->
      assertThat(render.viewModelStore).isSameAs(backStack.viewModelStores[index])
      assertThat(render.source.id).isEqualTo(index + 1)
      assertThat(render.session).isSameAs(sessionProvider.foaSession)
    }
    view.release()
  }

  @Test
  fun `a renderer's ViewModel stays with its entry until the entry is popped`() {
    val backStack = backStackOf(ModelDestination("a"))
    val view = render(backStack)

    navigate { backStack.push(ModelDestination("b")) }
    navigate { backStack.pop() }

    assertThat(MODEL_RENDERS).hasSize(3)
    val (a, b, aAgain) = MODEL_RENDERS
    assertThat(aAgain).isSameAs(a)
    assertThat(a.name).isEqualTo("a")
    assertThat(b.name).isEqualTo("b")
    assertThat(b.cleared).isTrue()
    assertThat(a.cleared).isFalse()
    val (keyedA, keyedB, keyedAAgain) = KEYED_RENDERS
    assertThat(keyedA).isNotSameAs(a)
    assertThat(keyedA.name).isEqualTo("other")
    assertThat(keyedAAgain).isSameAs(keyedA)
    assertThat(keyedB.cleared).isTrue()
    view.release()
  }

  @Test
  fun `system back pops while the stack has more than one entry`() {
    val controller = launch(savedState = null)
    val activity = controller.get()

    controller.navigate { push(CounterDestination("b")) }
    activity.onBackPressedDispatcher.onBackPressed()
    layOut(activity.view)

    assertThat(activity.backStack.backStack).containsExactly(CounterDestination("a"))
    assertThat(EVENTS).isEmpty()
    controller.pause().stop().destroy()
  }

  @Test
  fun `system back at the root asks the parent to close and keeps the back stack`() {
    NavHostActivity.content = { activity ->
      NavHostActivity.navHost(activity, stack = listOf(ModelDestination("a")))
    }
    val controller = launch(savedState = null)
    val activity = controller.get()

    activity.onBackPressedDispatcher.onBackPressed()
    layOut(activity.view)

    assertThat(EVENTS).containsExactly("close")
    assertThat(activity.backStack.backStack).containsExactly(ModelDestination("a"))
    assertThat(MODEL_RENDERS.single().cleared).isFalse()
    assertThat(activity.isFinishing).isFalse()
    controller.pause().stop().destroy()
  }

  @Test
  fun `each system back at the root asks the parent to close`() {
    val controller = launch(savedState = null)
    val activity = controller.get()

    activity.onBackPressedDispatcher.onBackPressed()
    activity.onBackPressedDispatcher.onBackPressed()

    assertThat(EVENTS).containsExactly("close", "close")
    assertThat(activity.onBackPressedDispatcher.hasEnabledCallbacks()).isTrue()
    controller.pause().stop().destroy()
  }

  @Test
  fun `a close request can send back on to the Activity`() {
    NavHostActivity.content = { activity ->
      NavHostActivity.navHost(
          activity,
          backPress =
              LithoNavBackPress.HostManaged { activity.onBackPressedDispatcher.onBackPressed() },
      )
    }
    val controller = launch(savedState = null)
    val activity = controller.get()

    activity.onBackPressedDispatcher.onBackPressed()

    assertThat(activity.isFinishing).isTrue()
    assertThat(activity.backStack.backStack).containsExactly(CounterDestination("a"))
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host created again in place asks its new close callback`() {
    val controller = launch(savedState = null)
    val activity = controller.get()

    activity.replaceContent {
      NavHostActivity.navHost(
          activity,
          backPress = LithoNavBackPress.HostManaged { EVENTS += "close:new" },
      )
    }
    layOut(activity.view)
    activity.onBackPressedDispatcher.onBackPressed()

    assertThat(EVENTS).containsExactly("close:new")
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host created again in place for its parent to handle back drops its back callback`() {
    val controller = launch(savedState = null)
    val activity = controller.get()
    assertThat(activity.onBackPressedDispatcher.hasEnabledCallbacks()).isTrue()

    activity.replaceContent {
      NavHostActivity.navHost(activity, backPress = LithoNavBackPress.Unmanaged)
    }
    layOut(activity.view)

    assertThat(activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host created again in place to handle back itself adds a back callback`() {
    NavHostActivity.content = { activity ->
      NavHostActivity.navHost(activity, backPress = LithoNavBackPress.Unmanaged)
    }
    val controller = launch(savedState = null)
    val activity = controller.get()
    assertThat(activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()

    activity.replaceContent { NavHostActivity.navHost(activity) }
    layOut(activity.view)
    activity.onBackPressedDispatcher.onBackPressed()

    assertThat(EVENTS).containsExactly("close")
    controller.pause().stop().destroy()
  }

  @Test
  fun `each entry has its own lifecycle that follows the host while it is on top`() {
    val controller = launch(savedState = null)
    val a = checkNotNull(screen("a").lifecycleOwner)
    assertThat(a).isInstanceOf(EntryLifecycleOwner::class.java)
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)

    controller.navigate { push(CounterDestination("b")) }
    val b = checkNotNull(screen("b").lifecycleOwner)
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)

    controller.pause()
    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
    controller.stop()
    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    controller.restart().resume()
    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)

    controller.navigate { pop() }
    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    assertThat(screen("a").lifecycleOwner).isSameAs(a)
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)

    controller.pause().stop().destroy()
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
  }

  @Test
  fun `a screen's own back callback keeps handling back after the host stops and restarts`() {
    SCREEN_EFFECTS["b"] = ::addScreenBackCallback
    val controller = launch(savedState = null)
    val activity = controller.get()
    controller.navigate { push(CounterDestination("b")) }

    controller.pause().stop()
    controller.restart().resume()
    activity.onBackPressedDispatcher.onBackPressed()
    layOut(activity.view)

    assertThat(EVENTS).containsExactly("back:b")
    assertThat(activity.backStack.backStack).hasSize(2)
    controller.pause().stop().destroy()
  }

  @Test
  fun `a screen's own back callback keeps handling back after the Activity is recreated`() {
    SCREEN_EFFECTS["b"] = ::addScreenBackCallback
    val controller = launch(savedState = null)
    controller.navigate { push(CounterDestination("b")) }

    controller.recreate()
    layOut(controller.get().view)
    controller.get().onBackPressedDispatcher.onBackPressed()
    layOut(controller.get().view)

    assertThat(EVENTS).containsExactly("back:b")
    assertThat(controller.get().backStack.backStack).hasSize(2)
    controller.pause().stop().destroy()
  }

  @Test
  fun `a stopped host has no active back callback`() {
    val controller = launch(savedState = null)
    val dispatcher = controller.get().onBackPressedDispatcher
    controller.navigate { push(CounterDestination("b")) }
    assertThat(dispatcher.hasEnabledCallbacks()).isTrue()

    controller.pause().stop()

    assertThat(dispatcher.hasEnabledCallbacks()).isFalse()
    controller.destroy()
  }

  @Test
  fun `entries are destroyed when the host leaves the tree`() {
    val controller = launch(savedState = null)
    val a = checkNotNull(screen("a").lifecycleOwner)

    controller.get().replaceContent { EmptyComponent() }
    layOut(controller.get().view)

    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    controller.pause().stop().destroy()
  }

  @Test
  fun `the host keeps its back stack and entry ViewModels across an Activity recreation`() {
    NavHostActivity.content = { activity ->
      NavHostActivity.navHost(activity, stack = listOf(ModelDestination("a")))
    }
    val controller = launch(savedState = null)
    val backStack = controller.get().backStack

    controller.recreate()
    layOut(controller.get().view)
    ShadowLooper.idleMainLooper()

    assertThat(controller.get().backStack).isSameAs(backStack)
    assertThat(MODEL_RENDERS.first().cleared).isFalse()
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host removed while its owner lives clears its back stack`() {
    NavHostActivity.content = { activity ->
      NavHostActivity.navHost(activity, stack = listOf(ModelDestination("a")))
    }
    val controller = launch(savedState = null)
    controller.navigate { push(ModelDestination("b")) }

    controller.get().replaceContent { EmptyComponent() }
    layOut(controller.get().view)
    ShadowLooper.idleMainLooper()

    assertThat(controller.get().handle(NAV_KEY).backStack).isNull()
    assertThat(MODEL_RENDERS.map { it.cleared }).containsOnly(true)
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host that moves to a new place in the tree keeps its back stack`() {
    val controller = launch(savedState = null)
    val activity = controller.get()
    controller.navigate { push(CounterDestination("b")) }
    val backStack = activity.backStack

    activity.replaceContent { Column { child(NavHostActivity.navHost(activity)) } }
    layOut(activity.view)
    ShadowLooper.idleMainLooper()

    assertThat(activity.backStack).isSameAs(backStack)
    assertThat(backStack.backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    controller.pause().stop().destroy()
  }

  @Test
  fun `hosts with different keys in one owner keep separate back stacks`() {
    NavHostActivity.content = { activity ->
      Column {
        child(
            NavHostActivity.navHost(
                activity,
                key = "first",
                stack = listOf(CounterDestination("a")),
            ),
        )
        child(
            NavHostActivity.navHost(
                activity,
                key = "second",
                stack = listOf(CounterDestination("x")),
            ),
        )
      }
    }
    val controller = launch(savedState = null)
    val activity = controller.get()

    activity.backStack("first").push(CounterDestination("b"))
    layOut(activity.view)

    assertThat(activity.backStack("first").backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    assertThat(activity.backStack("second").backStack).containsExactly(CounterDestination("x"))
    assertThat(SCREENS).containsKeys("b", "x")
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host whose parent handles back adds no back callback`() {
    NavHostActivity.content = { activity ->
      NavHostActivity.navHost(activity, backPress = LithoNavBackPress.Unmanaged)
    }
    val controller = launch(savedState = null)

    controller.navigate { push(CounterDestination("b")) }

    assertThat(controller.get().onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    controller.pause().stop().destroy()
  }

  @Test
  fun `the tracer hears each top screen once it is rendered`() {
    val rendered = CopyOnWriteArrayList<Int>()
    val tracer =
        object : BackStackTracer {
          override fun onScreenRendered(topIndex: Int) {
            rendered += topIndex
          }
        }
    NavHostActivity.content = { activity -> NavHostActivity.navHost(activity, tracer = tracer) }
    val controller = launch(savedState = null)

    controller.navigate { push(CounterDestination("b")) }
    controller.navigate { pop() }

    assertThat(rendered).containsExactly(0, 1, 0)
    controller.pause().stop().destroy()
  }

  @Test
  fun `the host creates data sources with its session provider's session`() {
    val provider = TestSessionProvider()
    val view = lithoTestRule.render {
      navHost(backStackOf(ProfileDestination(1)), sessionProvider = provider)
    }

    assertThat(PROFILE_SESSIONS).containsOnly(provider.foaSession)
    view.release()
  }

  @Test
  fun `the top entry stays resumed when the host's effect runs again in place`() {
    NavHostActivity.content = { activity ->
      SessionParent(backStackHolder(activity, NAV_KEY), activity.handle(NAV_KEY))
    }
    val controller = launch(savedState = null)
    val a = checkNotNull(screen("a").lifecycleOwner)

    checkNotNull(SESSION_STATE.get()).updateSync(TestSessionProvider())
    layOut(controller.get().view)

    assertThat(screen("a").lifecycleOwner).isSameAs(a)
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    controller.pause().stop().destroy()
  }

  @Test
  fun `a screen that navigates from its lifecycle during a pop shows the new screen`() {
    val armed = AtomicBoolean(false)
    val backStackRef = AtomicReference<FoaBackStack<LithoDestination>?>()
    SCREEN_EFFECTS["a"] = { owner, _ ->
      owner.lifecycle.addObserver(
          LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && armed.getAndSet(false)) {
              backStackRef.get()?.push(CounterDestination("c"))
            }
          },
      )
      null
    }
    val controller = launch(savedState = null)
    val backStack = controller.get().backStack
    backStackRef.set(backStack)
    controller.navigate { push(CounterDestination("b")) }

    armed.set(true)
    controller.navigate { pop() }

    assertThat(backStack.backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("c"))
    assertThat(checkNotNull(screen("c").lifecycleOwner).lifecycle.currentState)
        .isEqualTo(Lifecycle.State.RESUMED)
    controller.pause().stop().destroy()
  }

  @Test
  fun `a popped entry is destroyed only after the pop renders`() {
    val controller = launch(savedState = null)
    controller.navigate { push(CounterDestination("b")) }
    val b = checkNotNull(screen("b").lifecycleOwner)

    controller.get().backStack.pop()
    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    layOut(controller.get().view)

    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    controller.pause().stop().destroy()
  }

  @Test
  fun `an entry published after the rendered state keeps its lifecycle`() {
    val ids = List(3) { "entry-$it" }
    val first = NavState.create(ids.take(1), EmptyComponent())
    val tracker = EntryTracker(first)
    tracker.attachHost(null)
    val rendered = first.next(ids.take(2)) { EmptyComponent() }
    tracker.publish(rendered)
    val latest = rendered.next(ids) { EmptyComponent() }
    tracker.publish(latest)

    tracker.dropRemoved(committed = rendered)

    assertThat(latest.entries.last().lifecycleOwner.lifecycle.currentState)
        .isEqualTo(Lifecycle.State.RESUMED)
  }

  @Test
  fun `entries attached without a lifecycle owner outlive the owner they followed before`() {
    val tracker = EntryTracker(NavState.create(listOf("entry"), EmptyComponent()))
    val entry = tracker.latest.entries.single().lifecycleOwner
    val owner = TestLifecycleOwner()
    tracker.attachHost(owner)
    owner.registry.currentState = Lifecycle.State.RESUMED

    tracker.detachHost()
    tracker.attachHost(null)
    owner.registry.currentState = Lifecycle.State.DESTROYED

    assertThat(entry.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
  }

  @Test
  fun `a screen navigates with the back stack from useBackStack`() {
    val backStack = backStackOf(CounterDestination("a"))
    val view = render(backStack)
    assertThat(screen("a").backStack).isSameAs(backStack)

    navigate { screen("a").backStack.push(CounterDestination("b")) }
    assertThat(view.findComponent(CounterScreen::class)).isNotNull
    assertThat(backStack.backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    SCREENS.remove("a")
    navigate { screen("b").backStack.handleBack() }

    assertThat(backStack.backStack).containsExactly(CounterDestination("a"))
    assertThat(SCREENS).containsKey("a")
    view.release()
  }

  @Test
  fun `useBackStack outside a LithoNavHost is reported`() {
    assertThatThrownBy { lithoTestRule.render { BackStackReader() } }
        .hasStackTraceContaining("useBackStack() called outside a LithoNavHost")
  }

  @Test
  fun `an entry that navigates from its lifecycle during a publish leaves only the new top resumed`() {
    val ids = listOf("a", "b", "c")
    val first = NavState.create(ids.take(1), EmptyComponent())
    val tracker = EntryTracker(first)
    tracker.attachHost(null)
    val withB = first.next(ids.take(2)) { EmptyComponent() }
    val withC = withB.next(ids) { EmptyComponent() }
    first.entries
        .single()
        .lifecycleOwner
        .lifecycle
        .addObserver(
            LifecycleEventObserver { _, event ->
              if (event == Lifecycle.Event.ON_STOP) tracker.publish(withC)
            },
        )

    tracker.publish(withB)

    assertThat(withC.entries.map { it.lifecycleOwner.lifecycle.currentState })
        .containsExactly(Lifecycle.State.CREATED, Lifecycle.State.CREATED, Lifecycle.State.RESUMED)
  }

  @Test
  fun `a host keeps showing its last screen when its back stack is emptied`() {
    val backStack = backStackOf(CounterDestination("a"))
    val view = render(TestSavedStateOwner(), backStack)
    val a = checkNotNull(screen("a").lifecycleOwner)

    navigate { backStack.handleBack() }

    assertThat(backStack.backStack).isEmpty()
    assertThat(view.findComponent(CounterScreen::class)).isNotNull
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    view.release()
  }

  @Test
  fun `system back after the back stack is emptied goes to the Activity`() {
    val controller = launch(savedState = null)
    val activity = controller.get()

    controller.navigate { handleBack() }
    activity.onBackPressedDispatcher.onBackPressed()

    assertThat(activity.backStack.backStack).isEmpty()
    assertThat(activity.isFinishing).isTrue()
    assertThat(EVENTS).isEmpty()
    controller.pause().stop().destroy()
  }

  /** As when FoA pops the screen that owns the host, which clears that screen's store first. */
  @Test
  fun `a host keeps showing its last screen when its owner's store is cleared`() {
    val store = ViewModelStore()
    val holder =
        ViewModelProvider.create(
                store,
                viewModelFactory { initializer { BackStackHolder(SavedStateHandle()) } },
            )[BackStackHolder::class]
    val view = lithoTestRule.render { navHost(backStackOf(CounterDestination("a")), holder) }

    store.clear()
    lithoTestRule.idle()

    assertThat(holder.backStack).isNull()
    assertThat(view.findComponent(CounterScreen::class)).isNotNull
    view.release()
  }

  @Test
  fun `a screen pushed after the back stack was emptied replaces the last screen`() {
    val owner = TestSavedStateOwner()
    val backStack = backStackOf(CounterDestination("a"))
    val view = render(owner, backStack)
    screen("a").saveable.commit(REMOVED_VALUE)
    val a = checkNotNull(screen("a").lifecycleOwner)
    navigate { backStack.handleBack() }
    assertThat(owner.save().containsValue(REMOVED_VALUE)).isTrue()

    navigate { backStack.push(CounterDestination("b")) }

    assertThat(screen("b").saveable.value).isEqualTo(0)
    assertThat(a.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    assertThat(owner.save().containsValue(REMOVED_VALUE)).isFalse()
    view.release()
  }

  @Test
  fun `a host created with an emptied back stack starts from the back stack it is given`() {
    val controller = launch(savedState = null)
    val emptied = controller.get().backStack
    controller.navigate { handleBack() }
    SCREENS.clear()

    controller.recreate()
    layOut(controller.get().view)

    val backStack = controller.get().backStack
    assertThat(backStack).isNotSameAs(emptied)
    assertThat(backStack.backStack).containsExactly(CounterDestination("a"))
    assertThat(SCREENS).containsKey("a")
    controller.pause().stop().destroy()
  }

  @Test
  fun `a deep-link back stack shows its top, and back goes to the entry below`() {
    NavHostActivity.content = { activity ->
      NavHostActivity.navHost(
          activity,
          stack = listOf(CounterDestination("a"), CounterDestination("b"), CounterDestination("c")),
      )
    }
    val controller = launch(savedState = null)
    val activity = controller.get()
    assertThat(SCREENS.keys).containsExactly("c")

    activity.onBackPressedDispatcher.onBackPressed()
    layOut(activity.view)

    assertThat(activity.backStack.backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    assertThat(SCREENS).containsKey("b")
    controller.pause().stop().destroy()
  }

  @Test
  fun `a seed that loses to the held back stack is cleared`() {
    val controller = launch(savedState = null)
    controller.navigate { push(CounterDestination("b")) }

    controller.recreate()
    layOut(controller.get().view)

    assertThat(SEEDS).hasSize(2)
    assertThat(SEEDS.last().backStack).isEmpty()
    assertThat(controller.get().backStack.backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    controller.pause().stop().destroy()
  }

  @Test
  fun `the held back stack passed as the seed is not cleared`() {
    val backStack = backStackOf(CounterDestination("a"))
    val holder = BackStackHolder(SavedStateHandle()).also { it.backStack = backStack }

    val view = lithoTestRule.render { navHost(backStack, holder) }

    assertThat(backStack.backStack).containsExactly(CounterDestination("a"))
    assertThat(view.findComponent(CounterScreen::class)).isNotNull
    view.release()
  }

  @Test
  fun `an empty seed with nothing held is reported`() {
    val seed = backStackOf(CounterDestination("a")).also { it.clear() }

    assertThatThrownBy { navHost(seed) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("empty back stack")
  }

  @Test
  fun `an empty seed that loses to a live back stack is ignored`() {
    val held = backStackOf(CounterDestination("a"))
    val holder = BackStackHolder(SavedStateHandle()).also { it.backStack = held }
    val seed = backStackOf(CounterDestination("x")).also { it.clear() }

    val view = lithoTestRule.render { navHost(seed, holder) }

    assertThat(SCREENS.keys).containsExactly("a")
    view.release()
  }

  @Test
  fun `the handle has the back stack as soon as the host is created`() {
    val handle = LithoNavHostHandle()
    val backStack = backStackOf(CounterDestination("a"))
    assertThat(handle.backStack).isNull()

    navHost(backStack, handle = handle)

    assertThat(handle.backStack).isSameAs(backStack)
  }

  /**
   * As when FoA pushes a screen over the one that owns the host: FoA destroys that screen's
   * Fragment, but keeps its store.
   */
  @Test
  fun `a host whose owner is destroyed while its store lives keeps its back stack`() {
    val store = ViewModelStore()
    val first = StoreOwner(store)
    val firstHandle = LithoNavHostHandle()
    val firstView = render(first, ownedNavHost(first, firstHandle))
    navigate { checkNotNull(firstHandle.backStack).push(CounterDestination("b")) }

    first.destroy()
    firstView.release()
    lithoTestRule.idle()
    ShadowLooper.idleMainLooper()
    SCREENS.clear()
    val second = StoreOwner(store)
    val secondHandle = LithoNavHostHandle()
    val secondView = render(second, ownedNavHost(second, secondHandle))

    assertThat(checkNotNull(secondHandle.backStack).backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    assertThat(SCREENS.keys).containsExactly("b")
    secondView.release()
  }

  @Test
  fun `a host for another key in the same place is a new host`() {
    val controller = launch(savedState = null)
    val activity = controller.get()
    controller.navigate { push(CounterDestination("b")) }
    val b = checkNotNull(screen("b").lifecycleOwner)

    activity.replaceContent {
      NavHostActivity.navHost(activity, key = "other", stack = listOf(CounterDestination("x")))
    }
    layOut(activity.view)
    ShadowLooper.idleMainLooper()

    assertThat(activity.backStack("other").backStack).containsExactly(CounterDestination("x"))
    assertThat(SCREENS).containsKey("x")
    assertThat(activity.handle(NAV_KEY).backStack).isNull()
    assertThat(b.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    controller.pause().stop().destroy()
  }

  @Test
  fun `a renderer that changes the back stack is reported`() {
    val backStack = backStackOf(NavigatingDestination(1))
    NAVIGATING_STACK.set(backStack)

    assertThatThrownBy { navHost(backStack) }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("changed the back stack")
  }

  /** As when a caller's visibility controller releases the tree before its owner is destroyed. */
  @Test
  fun `a host released just before its owner is destroyed keeps its back stack`() {
    val store = ViewModelStore()
    val first = StoreOwner(store)
    val firstHandle = LithoNavHostHandle()
    val firstView = render(first, ownedNavHost(first, firstHandle))
    navigate { checkNotNull(firstHandle.backStack).push(CounterDestination("b")) }

    firstView.release()
    first.destroy()
    lithoTestRule.idle()
    ShadowLooper.idleMainLooper()
    val second = StoreOwner(store)
    val secondHandle = LithoNavHostHandle()
    val secondView = render(second, ownedNavHost(second, secondHandle))

    assertThat(checkNotNull(secondHandle.backStack).backStack)
        .containsExactly(CounterDestination("a"), CounterDestination("b"))
    secondView.release()
  }

  @Test
  fun `a host whose owner and store go away for good clears its entries' ViewModels`() {
    val store = ViewModelStore()
    val owner = StoreOwner(store)
    val view = render(owner, ownedNavHost(owner, handle = null, start = ModelDestination("a")))

    owner.destroy()
    view.release()
    store.clear()

    assertThat(MODEL_RENDERS.single().cleared).isTrue()
  }

  @Test
  fun `a host whose owner outlives an Activity recreation keeps its back stack`() {
    val owner = StoreOwner(ViewModelStore())
    NavHostActivity.content = { activity ->
      LithoNavHost(
          NAV_KEY,
          backStackHolder(owner, NAV_KEY),
          backStackOf(CounterDestination("a")),
          activity,
          activity.handle(NAV_KEY),
          owner.lifecycle,
          NoOpBackStackTracer,
          backPress = CLOSE_TO_EVENTS,
          Style,
          SPECS,
      )
    }
    val controller = launch(savedState = null)
    controller.navigate { push(CounterDestination("b")) }
    val backStack = controller.get().backStack

    controller.recreate()
    layOut(controller.get().view)
    ShadowLooper.idleMainLooper()

    assertThat(controller.get().backStack).isSameAs(backStack)
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host keeps its screens when a host next to it is removed`() {
    NavHostActivity.content = { activity ->
      Column {
        child(NavHostActivity.navHost(activity, key = "first"))
        child(
            NavHostActivity.navHost(
                activity,
                key = "second",
                stack = listOf(CounterDestination("x")),
            ),
        )
      }
    }
    val controller = launch(savedState = null)
    val activity = controller.get()
    val x = checkNotNull(screen("x").lifecycleOwner)

    activity.replaceContent {
      Column {
        child(
            NavHostActivity.navHost(
                activity,
                key = "second",
                stack = listOf(CounterDestination("x")),
            ),
        )
      }
    }
    layOut(activity.view)
    ShadowLooper.idleMainLooper()

    assertThat(x.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    assertThat(activity.handle("first").backStack).isNull()
    controller.pause().stop().destroy()
  }

  @Test
  fun `a second host for the same back stack is reported`() {
    val holder = BackStackHolder(SavedStateHandle())
    val first = lithoTestRule.render { navHost(backStackOf(CounterDestination("a")), holder) }

    assertThatThrownBy {
          lithoTestRule.render { navHost(backStackOf(CounterDestination("x")), holder) }
        }
        .hasMessageContaining("Another LithoNavHost shows this back stack")
    first.release()
  }

  @Test
  fun `a host created again in place with a new back stack clears that one when it leaves`() {
    val controller = launch(savedState = null)
    val activity = controller.get()
    controller.navigate { handleBack() }
    activity.replaceContent { NavHostActivity.navHost(activity) }
    layOut(activity.view)
    val reseeded = activity.backStack
    assertThat(reseeded.backStack).containsExactly(CounterDestination("a"))

    activity.replaceContent { EmptyComponent() }
    layOut(activity.view)
    ShadowLooper.idleMainLooper()

    assertThat(activity.handle(NAV_KEY).backStack).isNull()
    assertThat(reseeded.backStack).isEmpty()
    controller.pause().stop().destroy()
  }

  @Test
  fun `a host created again in place reports to its new tracer`() {
    val first = RecordingTracer()
    val second = RecordingTracer()
    NavHostActivity.content = { activity -> NavHostActivity.navHost(activity, tracer = first) }
    val controller = launch(savedState = null)
    val activity = controller.get()
    activity.replaceContent { NavHostActivity.navHost(activity, tracer = second) }
    layOut(activity.view)
    first.events.clear()
    second.events.clear()

    controller.navigate { push(CounterDestination("b")) }

    assertThat(first.events).isEmpty()
    assertThat(second.events).containsExactly("changed:1", "rendered:1")
    controller.pause().stop().destroy()
  }

  @Test
  fun `a renderer's scope used after the renderer returns is reported`() {
    val view = render(backStackOf(ScopeDestination(1)))
    val scope = checkNotNull(SAVED_SCOPE.get())

    assertThatThrownBy { scope.viewModelStore }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("used after its lithoRenderer factory returned")
    assertThatThrownBy { scope.viewModel(NameViewModel::class) { NameViewModel("late") } }
        .isInstanceOf(IllegalStateException::class.java)
    view.release()
  }

  @Test
  fun `a renderer that changes the back stack inside a push is reported`() {
    val backStack = backStackOf(CounterDestination("a"))
    val view = render(backStack)
    NAVIGATING_STACK.set(backStack)

    assertThatThrownBy { backStack.push(NavigatingDestination(1)) }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("changed the back stack")
    view.release()
  }

  @Test
  fun `a handle reads the back stack of the last host created with it`() {
    val handle = LithoNavHostHandle()
    navHost(backStackOf(CounterDestination("a")), handle = handle)
    val second = backStackOf(CounterDestination("x"))

    navHost(second, BackStackHolder(SavedStateHandle()), handle = handle)

    assertThat(handle.backStack).isSameAs(second)
  }

  @Test
  fun `creating a host off the main thread throws before building a screen`() {
    val backStack = backStackOf(CounterDestination("a"))
    var failure: Throwable? = null

    val creator = thread {
      failure = runCatching { navHost(backStack) }.exceptionOrNull()
    }
    creator.join()

    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    assertThat(failure).hasMessageContaining("main thread")
    assertThat(COUNTER_RENDERER_CALLS.get()).isZero()
  }

  @Test
  fun `a destination without a Litho renderer is reported`() {
    val backStack = backStackOf(NotLithoDestination(1))

    assertThatThrownBy { navHost(backStack) }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("requires a Litho renderer")
  }

  private fun backStackOf(destination: LithoDestination): FoaBackStack<LithoDestination> =
      FoaBackStack.create(destination)

  private fun render(backStack: FoaBackStack<LithoDestination>): TestLithoView =
      lithoTestRule.render {
        navHost(backStack)
      }

  /** A nav host for [seed], whose holder belongs to no owner unless [holder] is given. */
  private fun navHost(
      seed: FoaBackStack<LithoDestination>,
      holder: BackStackHolder = BackStackHolder(SavedStateHandle()),
      sessionProvider: FoaBackStackSessionProvider = this.sessionProvider,
      handle: LithoNavHostHandle? = null,
  ): Component = LithoNavHost(
      NAV_KEY,
      holder,
      seed,
      sessionProvider,
      handle,
      ownerLifecycle = null,
      NoOpBackStackTracer,
      backPress = CLOSE_TO_EVENTS,
      Style,
      SPECS,
  )

  /** A nav host that keeps its back stack in [owner], starting at [start]. */
  private fun ownedNavHost(
      owner: StoreOwner,
      handle: LithoNavHostHandle?,
      start: LithoDestination = CounterDestination("a"),
  ): Component = LithoNavHost(
      NAV_KEY,
      backStackHolder(owner, NAV_KEY),
      backStackOf(start),
      sessionProvider,
      handle,
      owner.lifecycle,
      NoOpBackStackTracer,
      backPress = CLOSE_TO_EVENTS,
      Style,
      SPECS,
  )

  private fun render(
      owner: SavedStateRegistryOwner,
      backStack: FoaBackStack<LithoDestination>,
  ): TestLithoView = render(owner, navHost(backStack))

  private fun render(owner: SavedStateRegistryOwner, root: Component): TestLithoView {
    val tree = ComponentTree.create(lithoTestRule.context.androidContext, owner, root).build()
    return lithoTestRule
        .createTestLithoView(componentTree = tree)
        .attachToWindow()
        .measure()
        .layout()
  }

  private fun navigate(change: () -> Unit) {
    change()
    lithoTestRule.idle()
  }

  private fun screen(name: String): ScreenStates = checkNotNull(SCREENS[name]) { "$name not shown" }

  /** Updates the state and lets the update commit, as it would before the user navigates. */
  private fun State<Int>.commit(value: Int) {
    updateSync(value)
    lithoTestRule.idle()
  }

  private fun launch(savedState: Bundle?): ActivityController<NavHostActivity> =
      Robolectric.buildActivity(NavHostActivity::class.java)
          .create(savedState)
          .start()
          .postCreate(savedState)
          .resume()
          .visible()
          .also { layOut(it.get().view) }

  /** Saves state and destroys the Activity, as before process death; the next launch restores. */
  private fun recreate(controller: ActivityController<NavHostActivity>): Bundle {
    val savedState = Bundle()
    controller.pause().saveInstanceState(savedState).stop().destroy()
    SCREENS.clear()
    return savedState
  }

  private fun ActivityController<NavHostActivity>.navigate(
      change: FoaBackStack<LithoDestination>.() -> Unit,
  ) {
    get().backStack.change()
    layOut(get().view)
  }

  private fun layOut(view: LithoView) {
    lithoTestRule.idle()
    val sizeSpec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE_PX, View.MeasureSpec.EXACTLY)
    view.measure(sizeSpec, sizeSpec)
    view.layout(0, 0, VIEW_SIZE_PX, VIEW_SIZE_PX)
    lithoTestRule.idle()
  }

  private fun Bundle.containsValue(value: Any): Boolean =
      keySet().any { key ->
        @Suppress("DEPRECATION") val item = get(key)
        item == value || (item is Bundle && item.containsValue(value))
      }

  /** Adds a back callback for the screen, registered on its entry's lifecycle. */
  private fun addScreenBackCallback(owner: LifecycleOwner, context: Context): () -> Unit {
    val callback =
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            EVENTS += "back:b"
          }
        }
    context.findActivity().onBackPressedDispatcher.addCallback(owner, callback)
    return callback::remove
  }

  private fun Context.findActivity(): ComponentActivity {
    var context: Context? = this
    while (context != null) {
      if (context is ComponentActivity) return context
      context = (context as? ContextWrapper)?.baseContext
    }
    error("No ComponentActivity in $this")
  }

  @SuppressLint("EndpointWithoutSwitchOff")
  class NavHostActivity : ComponentActivity(), FoaBackStackSessionProvider {
    private val host by lithoHost()
    override val foaSession: FoaUserSession = FakeFoaUserSession()

    lateinit var view: LithoView
      private set

    private val handles = mutableMapOf<String, LithoNavHostHandle>()

    /** The back stack of the host with [NAV_KEY]. */
    val backStack: FoaBackStack<LithoDestination>
      get() = backStack(NAV_KEY)

    /** The back stack of the host with [key]. */
    fun backStack(key: String): FoaBackStack<LithoDestination> = checkNotNull(handle(key).backStack)

    /** This Activity's handle for the host with [key]. */
    fun handle(key: String): LithoNavHostHandle = handles.getOrPut(key) { LithoNavHostHandle() }

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      val content = content
      host.setContent { content?.invoke(this) ?: navHost(this) }
      view = host.getLithoView()
      setContentView(view)
    }

    fun replaceContent(content: () -> Component) {
      host.setContent(content)
    }

    companion object {
      /** Replaces the nav host the next Activity shows, as a parent of the host would. */
      var content: ((NavHostActivity) -> Component)? = null

      /** A nav host that keeps its back stack in [activity] under [key], seeded with [stack]. */
      fun navHost(
          activity: NavHostActivity,
          key: String = NAV_KEY,
          stack: List<LithoDestination> = listOf(CounterDestination("a")),
          tracer: BackStackTracer = NoOpBackStackTracer,
          backPress: LithoNavBackPress = CLOSE_TO_EVENTS,
      ): Component {
        val seed = FoaBackStack.create(stack).also { SEEDS += it }
        return LithoNavHost(
            key,
            backStackHolder(activity, key),
            seed,
            activity,
            activity.handle(key),
            activity.lifecycle,
            tracer,
            backPress,
            Style,
            SPECS,
        )
      }
    }
  }

  private class TestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
      get() = registry
  }

  private class TestSavedStateOwner(restoredState: Bundle? = null) : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    init {
      controller.performAttach()
      controller.performRestore(restoredState)
      lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun save(): Bundle = Bundle().also(controller::performSave)
  }

  /**
   * An owner whose store can outlive it, as a FoA screen Fragment's back-stack entry store does.
   */
  private class StoreOwner(override val viewModelStore: ViewModelStore) :
      ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    init {
      controller.performAttach()
      enableSavedStateHandles()
      controller.performRestore(null)
      lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
      lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
  }

  private companion object {
    const val NAV_KEY: String = "test"
    const val VIEW_SIZE_PX: Int = 100
    const val REMOVED_VALUE: Int = 4242
    const val COVERED_VALUE: Int = 4343
    const val TOP_VALUE: Int = 4444
  }
}
