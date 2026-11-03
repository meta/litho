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
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleRegistry
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoSaveableStateRegistry
import com.facebook.litho.LithoSaveableStateRegistryTreeProp
import com.facebook.litho.LithoView
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState
import com.facebook.litho.LithoVisibilityEventsControllerDelegate
import com.facebook.litho.State
import com.facebook.litho.TreeProp
import com.facebook.litho.TreePropContainer
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.BackgroundLayoutLooperRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.treePropOf
import com.facebook.litho.useSaveableState
import com.facebook.litho.useState
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalLithoApi::class)
@SuppressLint("KComponentSyncUpdateDetector")
@RunWith(LithoTestRunner::class)
class LithoHostTest {

  @get:Rule val backgroundLayoutLooper = BackgroundLayoutLooperRule()

  private val controllers = mutableListOf<ActivityController<out ComponentActivity>>()

  @After
  fun tearDown() {
    controllers.filterNot { it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
    RENDERED.clear()
    SAVED.clear()
    PROPS_SEEN.clear()
    ShadowLog.clear()
    EarlyContentActivity.beforeCreate = null
  }

  // Saveable state

  @Test
  fun `an Activity host restores saveable values, not plain state, after process death`() {
    val first = launch<SingleHostActivity>()
    render(first.get().view)
    savedState("main").updateSync(8)
    screenState("main").updateSync(8)
    render(first.get().view)

    val second = launch<SingleHostActivity>(saveAndDestroy(first))
    render(second.get().view)

    assertThat(savedState("main").value).isEqualTo(8)
    assertThat(screenState("main").value).isEqualTo(0)
  }

  @Test
  fun `a host saves under its own key even if its tree props carry another registry`() {
    val first = launch<TreePropsHostActivity>()
    render(first.get().view)
    savedState("main").updateSync(8)
    render(first.get().view)

    val second = launch<TreePropsHostActivity>(saveAndDestroy(first))
    render(second.get().view)

    assertThat(savedState("main").value).isEqualTo(8)
  }

  @Test
  fun `hosts with the same root class are told apart by declaration order`() {
    val first = launch<TwoHostActivity>()
    render(first.get().firstView)
    render(first.get().secondView)
    savedState("first").updateSync(1)
    savedState("second").updateSync(2)
    render(first.get().firstView)
    render(first.get().secondView)

    val second = launch<TwoHostActivity>(saveAndDestroy(first))
    render(second.get().firstView)
    render(second.get().secondView)

    assertThat(savedState("first").value).isEqualTo(1)
    assertThat(savedState("second").value).isEqualTo(2)
  }

  @Test
  fun `a Fragment host restores saveable values after process death`() {
    val first = launch<FragmentActivity>()
    first.add(HostFragment(), FRAGMENT_TAG)
    render(first.fragment(FRAGMENT_TAG).lithoView)
    savedState("fragment").updateSync(7)
    render(first.fragment(FRAGMENT_TAG).lithoView)

    val second = launch<FragmentActivity>(saveAndDestroy(first))
    render(second.fragment(FRAGMENT_TAG).lithoView)

    assertThat(savedState("fragment").value).isEqualTo(7)
  }

  // Fragment views

  @Test
  fun `a Fragment host builds its tree from content set in onCreate before its view exists`() {
    val controller = launch<FragmentActivity>()
    val fragment = HostFragment()

    controller.add(fragment)
    render(fragment.lithoView)

    assertThat(RENDERED).containsKey("fragment")
  }

  @Test
  fun `a Fragment host keeps its tree and all its state when its view is recreated`() {
    val controller = launch<FragmentActivity>()
    val fragment = HostFragment()
    controller.add(fragment)
    val firstView = render(fragment.lithoView)
    val tree = firstView.componentTree
    screenState("fragment").updateSync(5)

    controller.detach(fragment)
    controller.attach(fragment)
    val secondView = render(fragment.lithoView)

    assertThat(secondView).isNotSameAs(firstView)
    assertThat(secondView.componentTree).isSameAs(tree)
    assertThat(firstView.componentTree).isNull()
    assertThat(screenState("fragment").value).isEqualTo(5)
  }

  @Test
  fun `a Fragment host lets go of its view when the view is destroyed`() {
    val controller = launch<FragmentActivity>()
    val fragment = HostFragment()
    controller.add(fragment)
    val view = fragment.lithoView
    assertThat(view.componentTree).isNotNull

    controller.detach(fragment)

    assertThat(view.componentTree).isNull()
  }

  @Test
  fun `a Fragment instance removed and added again shows its tree again`() {
    val controller = launch<FragmentActivity>()
    val fragment = HostFragment()
    controller.add(fragment)
    val firstTree = checkNotNull(fragment.lithoView.componentTree)

    controller.remove(fragment)
    RENDERED.clear()
    controller.add(fragment)
    val view = render(fragment.lithoView)

    assertThat(firstTree.isReleased).isTrue()
    assertThat(view.componentTree).isNotNull.isNotSameAs(firstTree)
    assertThat(RENDERED).containsKey("fragment")
  }

  @Test
  fun `a Fragment added again shows its last content without setting it again`() {
    val controller = launch<FragmentActivity>()
    val fragment = OnceContentFragment()
    controller.add(fragment)
    controller.remove(fragment)
    RENDERED.clear()

    controller.add(fragment)
    render(fragment.lithoView)

    assertThat(RENDERED).containsKey("once")
  }

  @Test
  fun `a Fragment with a host can be constructed off the main thread`() {
    var failure: Throwable? = null

    thread { failure = runCatching { HostFragment() }.exceptionOrNull() }.join()

    assertThat(failure).isNull()
  }

  // Retained Fragments

  @Test
  fun `a retained Fragment rebuilds its tree for the new Activity and keeps saveable values`() {
    val controller = launch<FragmentActivity>()
    val fragment = retainedHostFragment()
    controller.add(fragment, FRAGMENT_TAG)
    render(fragment.lithoView)
    savedState("fragment").updateSync(5)
    screenState("fragment").updateSync(5)
    render(fragment.lithoView)

    val secondActivity = recreate(controller)
    val view = render(fragment.lithoView)

    assertThat(secondActivity.supportFragmentManager.findFragmentByTag(FRAGMENT_TAG))
        .isSameAs(fragment)
    assertThat(checkNotNull(view.componentTree).context.androidContext).isSameAs(secondActivity)
    assertThat(savedState("fragment").value).isEqualTo(5)
    assertThat(screenState("fragment").value).isEqualTo(0)
  }

  @Test
  fun `a retained Fragment shows content set while it had no Activity`() {
    val controller = launch<EarlyContentActivity>()
    val fragment = retainedHostFragment()
    controller.add(fragment, FRAGMENT_TAG)
    render(fragment.lithoView)
    EarlyContentActivity.beforeCreate = {
      assertThat(fragment.context).isNull()
      fragment.setScreen("detached")
    }

    recreate(controller)
    render(fragment.lithoView)

    assertThat(RENDERED).containsKey("detached")
  }

  @Test
  fun `a retained Fragment on the back stack releases the tree built with its old Activity`() {
    val controller = launch<FragmentActivity>()
    val fragment = retainedHostFragment()
    controller.add(fragment, FRAGMENT_TAG)
    val firstTree = checkNotNull(fragment.lithoView.componentTree)
    controller.fragments
        .beginTransaction()
        .replace(android.R.id.content, Fragment())
        .addToBackStack(null)
        .commit()
    controller.fragments.executePendingTransactions()
    assertThat(fragment.view).isNull()

    val secondActivity = recreate(controller)

    assertThat(firstTree.isReleased).isTrue()
    secondActivity.supportFragmentManager.popBackStackImmediate()
    ShadowLooper.idleMainLooper()
    val tree = checkNotNull(fragment.lithoView.componentTree)
    assertThat(tree.context.androidContext).isSameAs(secondActivity)
  }

  // Views and content

  @Test
  fun `a host returns the same view for the rest of its view lifecycle`() {
    val activity = launch<SingleHostActivity>().get()

    assertThat(activity.host.getLithoView()).isSameAs(activity.view)
  }

  @Test
  fun `a later setContent replaces the root of a shown tree in the background`() {
    val controller = launch<ComponentActivity>()
    val host by controller.get().lithoHost()
    host.setContent { Screen("first") }
    render(host.getLithoView())
    assertThat(RENDERED).containsKey("first")

    host.setContent { Screen("second") }
    assertThat(RENDERED).doesNotContainKey("second")
    backgroundLayoutLooper.runToEndOfTasksSync()

    assertThat(RENDERED).containsKey("second")
  }

  @Test
  fun `setContentAsync replaces the root of a shown tree in the background`() {
    val controller = launch<ComponentActivity>()
    val host by controller.get().lithoHost()
    host.setContent { Screen("first") }
    render(host.getLithoView())

    host.setContentAsync { Screen("second") }
    assertThat(RENDERED).doesNotContainKey("second")
    backgroundLayoutLooper.runToEndOfTasksSync()

    assertThat(RENDERED).containsKey("second")
  }

  @Test
  fun `setContentSync lays out a shown tree's new root right away`() {
    val controller = launch<ComponentActivity>()
    val host by controller.get().lithoHost()
    host.setContent { Screen("first") }
    render(host.getLithoView())

    host.setContentSync { Screen("second") }

    assertThat(RENDERED).containsKey("second")
  }

  @Test
  fun `setContentSync before the first measure lays out at that measure`() {
    val controller = launch<ComponentActivity>()
    val host by controller.get().lithoHost()
    host.setContent { Screen("first") }
    val view = host.getLithoView()

    host.setContentSync { Screen("second") }
    assertThat(RENDERED).doesNotContainKey("second")

    render(view)
    assertThat(RENDERED).containsKey("second")
  }

  @Test
  fun `a view taken before content lays out the first content at its next measure`() {
    val activity = launch<ComponentActivity>().get()
    val host by activity.lithoHost()
    val view = host.getLithoView()
    activity.setContentView(view)
    ShadowLooper.idleMainLooper()
    assertThat(view.componentTree).isNull()

    host.setContent { Screen("after-view") }

    assertThat(view.isLayoutRequested).isTrue()
    ShadowLooper.idleMainLooper()
    assertThat(view.componentTree).isNotNull
    assertThat(RENDERED).containsKey("after-view")
  }

  @Test
  fun `a host passes its components configuration and tree props to its tree`() {
    val controller = launch<ComponentActivity>()
    val configuration =
        ComponentsConfiguration.defaultInstance.copy(
            incrementalMountEnabled =
                !ComponentsConfiguration.defaultInstance.incrementalMountEnabled,
        )
    val host by
        controller
            .get()
            .lithoHost(
                componentsConfiguration = configuration,
                treeProps = { TreePropContainer().apply { put(NAME_PROP, "from host") } },
            )
    host.setContent { PropReader() }

    val view = render(host.getLithoView())

    val treeConfig = checkNotNull(view.componentTree).lithoConfiguration.componentsConfig
    assertThat(treeConfig.incrementalMountEnabled).isEqualTo(configuration.incrementalMountEnabled)
    assertThat(PROPS_SEEN).containsExactly("from host")
  }

  // Dialogs

  @Test
  fun `a DialogFragment host shows its tree from onCreateDialog`() {
    val controller = launch<FragmentActivity>()
    val fragment = HostDialogFragment()

    fragment.showNow(controller.fragments, "dialog")
    val view = render(checkNotNull(fragment.dialogView))

    assertThat(view.componentTree).isNotNull
    assertThat(RENDERED).containsKey("dialog")
  }

  @Test
  fun `a DialogFragment host shows its tree again after its dialog is recreated`() {
    val controller = launch<FragmentActivity>()
    val fragment = HostDialogFragment()
    fragment.showNow(controller.fragments, "dialog")
    val first = render(checkNotNull(fragment.dialogView))

    controller.detach(fragment)
    assertThat(first.componentTree).isNull()
    controller.attach(fragment)
    val second = render(checkNotNull(fragment.dialogView))

    assertThat(second).isNotSameAs(first)
    assertThat(second.componentTree).isNotNull
  }

  @Test
  fun `a dialog rebuilt before it was ever shown gets a fresh view`() {
    val controller = launch<FragmentActivity>()
    controller.pause().stop()
    val fragment = HostDialogFragment()
    controller.fragments.beginTransaction().add(fragment, "dialog").commitNowAllowingStateLoss()
    val first = checkNotNull(fragment.dialogView)

    controller.fragments.beginTransaction().detach(fragment).commitNowAllowingStateLoss()
    controller.fragments.beginTransaction().attach(fragment).commitNowAllowingStateLoss()
    val second = checkNotNull(fragment.dialogView)
    controller.restart().start().resume()
    ShadowLooper.idleMainLooper()

    assertThat(fragment.dialogsCreated).isEqualTo(2)
    assertThat(second).isNotSameAs(first)
    assertThat(first.componentTree).isNull()
    assertThat(second.componentTree).isNotNull
  }

  @Test
  fun `a shown dialog keeps its view when getLithoView runs again before its first frame`() {
    val controller = launch<FragmentActivity>()
    val fragment = HostDialogFragment()
    fragment.showNow(controller.fragments, "dialog")
    val first = checkNotNull(fragment.dialogView)

    val again = fragment.requestLithoView()

    assertThat(again).isSameAs(first)
    assertThat(first.componentTree).isNotNull
  }

  @Test
  fun `a dialog that asks for the view twice while it is built gets one view`() {
    val controller = launch<FragmentActivity>()
    val fragment = TwiceDialogFragment()

    fragment.showNow(controller.fragments, "twice")

    assertThat(fragment.second).isSameAs(fragment.first)
    assertThat(checkNotNull(fragment.first).componentTree).isNotNull
  }

  // Visibility

  @Test
  fun `a host's tree has no visibility controller unless a caller passes one`() {
    val activity = launch<ComponentActivity>().get()
    val host by activity.lithoHost()
    host.setContent { Screen("main") }

    val tree = checkNotNull(host.getLithoView().componentTree)

    assertThat(tree.lithoVisibilityEventsController).isNull()
  }

  @Test
  fun `a host's tree uses a caller's visibility controller`() {
    val activity = launch<ComponentActivity>().get()
    val visibility = LithoVisibilityEventsControllerDelegate()
    val host by activity.lithoHost(visibilityController = visibility)
    host.setContent { Screen("main") }

    val tree = checkNotNull(host.getLithoView().componentTree)

    assertThat(tree.lithoVisibilityEventsController).isSameAs(visibility)
  }

  @Test
  fun `a caller's visibility controller keeps deciding visibility across resume`() {
    val controller = launch<ComponentActivity>()
    val visibility = LithoVisibilityEventsControllerDelegate()
    val host by controller.get().lithoHost(visibilityController = visibility)
    host.setContent { Screen("custom") }
    render(host.getLithoView())

    visibility.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
    controller.pause().resume()

    assertThat(visibility.visibilityState).isEqualTo(LithoVisibilityState.HINT_INVISIBLE)
  }

  @Test
  fun `a host releases a tree with a caller's visibility controller when its owner is destroyed`() {
    val controller = launch<ComponentActivity>()
    val visibility = LithoVisibilityEventsControllerDelegate()
    val host by controller.get().lithoHost(visibilityController = visibility)
    host.setContent { Screen("main") }
    val tree = checkNotNull(render(host.getLithoView()).componentTree)

    controller.pause().stop().destroy()

    assertThat(visibility.visibilityState).isNotEqualTo(LithoVisibilityState.DESTROYED)
    assertThat(tree.isReleased).isTrue()
  }

  @Test
  fun `a Fragment host shows a new tree after a caller's controller released the old one`() {
    val controller = launch<FragmentActivity>()
    val fragment = ControlledHostFragment()
    controller.add(fragment)
    val firstTree = checkNotNull(render(fragment.lithoView).componentTree)

    controller.detach(fragment)
    fragment.visibility.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
    fragment.visibility.moveToVisibilityState(LithoVisibilityState.DESTROYED)
    RENDERED.clear()
    controller.attach(fragment)
    val view = render(fragment.lithoView)

    assertThat(firstTree.isReleased).isTrue()
    assertThat(view.componentTree).isNotNull.isNotSameAs(firstTree)
    assertThat(RENDERED).containsKey("controlled")
  }

  @Test
  fun `a host shows new content in its view after a caller's controller released its tree`() {
    val activity = launch<ComponentActivity>().get()
    val visibility = LithoVisibilityEventsControllerDelegate()
    val host by activity.lithoHost(visibilityController = visibility)
    host.setContent { Screen("first") }
    val view = render(host.getLithoView())
    val firstTree = checkNotNull(view.componentTree)

    visibility.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
    visibility.moveToVisibilityState(LithoVisibilityState.DESTROYED)
    host.setContent { Screen("second") }
    render(view)

    assertThat(firstTree.isReleased).isTrue()
    assertThat(view.componentTree).isNotNull.isNotSameAs(firstTree)
    assertThat(RENDERED).containsKey("second")
  }

  // After the owner is destroyed

  @Test
  fun `a host releases its tree when its owner is destroyed`() {
    val controller = launch<SingleHostActivity>()
    val view = render(controller.get().view)
    val tree = checkNotNull(view.componentTree)

    controller.pause().stop().destroy()

    assertThat(tree.isReleased).isTrue()
    assertThat(view.componentTree).isNull()
  }

  @Test
  fun `a Fragment host destroyed before its Activity leaves no observer on the Activity`() {
    val controller = launch<FragmentActivity>()
    val activity = controller.get()
    val observersBefore = activity.observerCount()
    val fragment = HostFragment()
    controller.add(fragment)
    render(fragment.lithoView)

    controller.remove(fragment)

    assertThat(activity.observerCount()).isEqualTo(observersBefore)
  }

  @Test
  fun `a Fragment host that rebuilds its tree keeps one observer on its Activity`() {
    val controller = launch<FragmentActivity>()
    val activity = controller.get()
    val fragment = ControlledHostFragment()
    controller.add(fragment)
    render(fragment.lithoView)
    val observersWithTree = activity.observerCount()

    controller.detach(fragment)
    fragment.visibility.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
    fragment.visibility.moveToVisibilityState(LithoVisibilityState.DESTROYED)
    controller.attach(fragment)
    render(fragment.lithoView)

    assertThat(activity.observerCount()).isEqualTo(observersWithTree)
  }

  @Test
  fun `a host drops content set after its owner is destroyed`() {
    val controller = launch<SingleHostActivity>()
    val activity = controller.get()
    render(activity.view)

    controller.pause().stop().destroy()
    activity.host.setContent { Screen("late") }

    assertThat(RENDERED).doesNotContainKey("late")
    assertThat(usedAfterDestroyWarnings()).isZero()
  }

  @Test
  fun `a host builds nothing and refuses views once its owner is destroyed`() {
    val controller = launch<ComponentActivity>()
    val host by controller.get().lithoHost()
    host.setContent { Screen("before") }
    render(host.getLithoView())

    controller.pause().stop().destroy()
    host.setContent { Screen("late") }

    assertThat(RENDERED).doesNotContainKey("late")
    assertThatThrownBy { host.getLithoView() }.isInstanceOf(IllegalStateException::class.java)
  }

  @Test
  fun `a host first used after its owner is destroyed builds no tree and is reported`() {
    val controller = launch<ComponentActivity>()
    val host by controller.get().lithoHost()

    controller.pause().stop().destroy()
    host.setContent { Screen("late") }
    host.setContent { Screen("late") }

    assertThat(RENDERED).doesNotContainKey("late")
    assertThat(usedAfterDestroyWarnings()).isEqualTo(1)
  }

  // Misuse

  @Test
  fun `declaring a host once its owner is created is reported`() {
    val controller = launch<SingleHostActivity>()
    assertThat(declaredLateWarnings()).isZero()

    val host by controller.get().lithoHost()
    host.setContent { Screen("late") }

    assertThat(declaredLateWarnings()).isEqualTo(1)
  }

  private inline fun <reified T : ComponentActivity> launch(
      savedState: Bundle? = null,
  ): ActivityController<T> =
      Robolectric.buildActivity(T::class.java)
          .create(savedState)
          .start()
          .postCreate(savedState)
          .resume()
          .visible()
          .also { controller ->
            ShadowLooper.idleMainLooper()
            controllers += controller
          }

  private fun <T : ComponentActivity> recreate(controller: ActivityController<T>): T {
    controller.recreate()
    ShadowLooper.idleMainLooper()
    return controller.get()
  }

  /** Saves state and destroys the Activity, as before process death; the next launch restores. */
  private fun saveAndDestroy(controller: ActivityController<*>): Bundle {
    val savedState = Bundle()
    controller.pause().saveInstanceState(savedState).stop().destroy()
    RENDERED.clear()
    SAVED.clear()
    return savedState
  }

  private val ActivityController<out FragmentActivity>.fragments: FragmentManager
    get() = get().supportFragmentManager

  private fun ActivityController<out FragmentActivity>.add(
      fragment: Fragment,
      tag: String? = null,
  ) {
    fragments.beginTransaction().add(android.R.id.content, fragment, tag).commitNow()
  }

  private fun ActivityController<out FragmentActivity>.remove(fragment: Fragment) {
    fragments.beginTransaction().remove(fragment).commitNow()
  }

  private fun ActivityController<out FragmentActivity>.detach(fragment: Fragment) {
    fragments.beginTransaction().detach(fragment).commitNow()
  }

  private fun ActivityController<out FragmentActivity>.attach(fragment: Fragment) {
    fragments.beginTransaction().attach(fragment).commitNow()
  }

  private fun ActivityController<out FragmentActivity>.fragment(tag: String): Fragment =
      checkNotNull(fragments.findFragmentByTag(tag))

  private val Fragment.lithoView: LithoView
    get() = requireView() as LithoView

  private fun ComponentActivity.observerCount(): Int =
      (lifecycle as LifecycleRegistry).observerCount

  @Suppress("DEPRECATION")
  private fun retainedHostFragment(): HostFragment = HostFragment().apply { retainInstance = true }

  private fun render(view: LithoView): LithoView {
    val sizeSpec = View.MeasureSpec.makeMeasureSpec(VIEW_SIZE_PX, View.MeasureSpec.EXACTLY)
    view.measure(sizeSpec, sizeSpec)
    view.layout(0, 0, VIEW_SIZE_PX, VIEW_SIZE_PX)
    ShadowLooper.idleMainLooper()
    return view
  }

  private fun screenState(name: String): State<Int> =
      checkNotNull(RENDERED[name]) { "$name not rendered" }

  private fun savedState(name: String): State<Int> =
      checkNotNull(SAVED[name]) { "$name not rendered" }

  private fun declaredLateWarnings(): Int =
      ShadowLog.getLogs().count { it.msg.orEmpty().startsWith(DECLARED_LATE_CATEGORY) }

  private fun usedAfterDestroyWarnings(): Int =
      ShadowLog.getLogs().count { it.msg.orEmpty().startsWith(USED_AFTER_DESTROY_CATEGORY) }

  /** Records its plain and saveable state under [name] each time it renders. */
  private class Screen(private val name: String) : KComponent() {
    override fun ComponentScope.render(): Component {
      RENDERED[name] = useState { 0 }
      SAVED[name] = useSaveableState { 0 }
      return EmptyComponent()
    }
  }

  private class PropReader : KComponent() {
    override fun ComponentScope.render(): Component {
      PROPS_SEEN += NAME_PROP.value
      return EmptyComponent()
    }
  }

  @SuppressLint("EndpointWithoutSwitchOff")
  class SingleHostActivity : ComponentActivity() {
    val host by lithoHost()
    lateinit var view: LithoView
      private set

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      host.setContent { Screen("main") }
      view = host.getLithoView()
      setContentView(view)
    }
  }

  /** Passes tree props that carry a saveable-state registry, as if copied from another tree. */
  @SuppressLint("EndpointWithoutSwitchOff")
  class TreePropsHostActivity : ComponentActivity() {
    private val host by lithoHost {
      TreePropContainer().apply {
        put(LithoSaveableStateRegistryTreeProp, LithoSaveableStateRegistry())
      }
    }
    lateinit var view: LithoView
      private set

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      host.setContent { Screen("main") }
      view = host.getLithoView()
      setContentView(view)
    }
  }

  @SuppressLint("EndpointWithoutSwitchOff")
  class TwoHostActivity : ComponentActivity() {
    private val firstHost by lithoHost()
    private val secondHost by lithoHost()
    lateinit var firstView: LithoView
      private set

    lateinit var secondView: LithoView
      private set

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      firstHost.setContent { Screen("first") }
      secondHost.setContent { Screen("second") }
      firstView = firstHost.getLithoView()
      secondView = secondHost.getLithoView()
      setContentView(
          LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(firstView)
            addView(secondView)
          },
      )
    }
  }

  @SuppressLint("BadSuperClassFragmentActivity.FbFragmentActivity", "EndpointWithoutSwitchOff")
  class EarlyContentActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
      beforeCreate?.invoke()
      beforeCreate = null
      super.onCreate(savedInstanceState)
    }

    companion object {
      /** Runs at the start of the next `onCreate`, before a retained Fragment is attached again. */
      var beforeCreate: (() -> Unit)? = null
    }
  }

  @SuppressLint("BadSuperClassFragment.FbFragment")
  class HostFragment : Fragment() {
    private val host by lithoHost()

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      host.setContent { Screen("fragment") }
    }

    fun setScreen(name: String) {
      host.setContent { Screen(name) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = host.getLithoView()
  }

  /** Decides its tree's visibility with its own controller. */
  @SuppressLint("BadSuperClassFragment.FbFragment")
  class ControlledHostFragment : Fragment() {
    val visibility = LithoVisibilityEventsControllerDelegate()
    private val host by lithoHost(visibilityController = visibility)

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      host.setContent { Screen("controlled") }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = host.getLithoView()
  }

  /** Sets its content only the first time it's created, so an instance added again reuses it. */
  @SuppressLint("BadSuperClassFragment.FbFragment")
  class OnceContentFragment : Fragment() {
    private val host by lithoHost()
    private var contentSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      if (!contentSet) {
        contentSet = true
        host.setContent { Screen("once") }
      }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = host.getLithoView()
  }

  @SuppressLint("BadSuperClassFragment.FbFragment")
  class HostDialogFragment : DialogFragment() {
    private val host by lithoHost()
    var dialogView: LithoView? = null
      private set

    var dialogsCreated = 0
      private set

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      host.setContent { Screen("dialog") }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
      dialogsCreated++
      return Dialog(requireContext()).apply {
        setContentView(host.getLithoView().also { dialogView = it })
      }
    }

    fun requestLithoView(): LithoView = host.getLithoView()

    override fun onDestroyView() {
      dialogView = null
      super.onDestroyView()
    }
  }

  /** Asks for the host's view twice while building its dialog. */
  @SuppressLint("BadSuperClassFragment.FbFragment")
  class TwiceDialogFragment : DialogFragment() {
    private val host by lithoHost()
    var first: LithoView? = null
      private set

    var second: LithoView? = null
      private set

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      host.setContent { Screen("twice-dialog") }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext()).apply {
          setContentView(host.getLithoView().also { first = it })
          second = host.getLithoView()
        }

    override fun onDestroyView() {
      first = null
      second = null
      super.onDestroyView()
    }
  }

  private companion object {
    const val VIEW_SIZE_PX: Int = 100
    const val FRAGMENT_TAG: String = "host"
    val RENDERED: MutableMap<String, State<Int>> = mutableMapOf()
    val SAVED: MutableMap<String, State<Int>> = mutableMapOf()
    val PROPS_SEEN: MutableList<String?> = mutableListOf()
    val NAME_PROP: TreeProp<String?> = treePropOf { null }
  }
}
