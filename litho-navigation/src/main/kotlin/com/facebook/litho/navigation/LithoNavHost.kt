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
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.EmptyComponent
import com.facebook.litho.KComponent
import com.facebook.litho.LithoSaveableStateHolder
import com.facebook.litho.Ref
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.ThreadUtils
import com.facebook.litho.TreeProp
import com.facebook.litho.TreePropProvider
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.flexbox.positionType
import com.facebook.litho.key
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.lifecycle.LifecycleOwnerTreeProp
import com.facebook.litho.onCleanup
import com.facebook.litho.treePropOf
import com.facebook.litho.useEffect
import com.facebook.litho.useRef
import com.facebook.litho.useSaveableStateHolder
import com.facebook.litho.useState
import com.facebook.yoga.YogaPositionType
import com.meta.foa.navigation.core.BackStackController
import com.meta.foa.navigation.core.BackStackHolder
import com.meta.foa.navigation.core.BackStackTracer
import com.meta.foa.navigation.core.FoaBackStack
import com.meta.foa.navigation.core.FoaBackStackSessionProvider
import com.meta.foa.navigation.core.FoaDestinationSpecRegistry
import com.meta.foa.navigation.core.FoaDestinationSpecs
import com.meta.foa.navigation.core.NoOpBackStackTracer
import com.meta.foa.session.FoaUserSession

/**
 * Creates a host that renders a FoA Navigation back stack: the top entry's screen, built by the
 * [lithoRenderer] in its destination spec. Create it on the main thread, inside
 * `LithoHost.setContent { }`; layout around it is fine. It builds the top screen when it is
 * created, so don't create it in a render, and don't keep the instance to show again: the lambda
 * runs again when the tree is rebuilt.
 *
 * The host keeps its back stack in a FoA `BackStackHolder` under [key] in [owner]'s
 * `ViewModelStore`, and [backStack] only seeds it: the host uses it when the holder has no back
 * stack, or only an emptied one. Otherwise the held stack wins and the host clears [backStack], so
 * pass a new one each time, built inside the `setContent` lambda; for a deep link, pass the whole
 * stack. After creation, read the live stack from [handle] or [useBackStack], not from the stack
 * you passed.
 * - A configuration change keeps the live stack, with each entry's ViewModels and prefetched data
 *   source. After process death its destinations and entry ids come back, with fresh entry stores.
 *   If FoA can't restore it, the host starts from [backStack].
 * - When the host leaves the tree while its owner lives, as when it is removed or a caller's
 *   visibility controller releases the tree, it clears the stack once that commit is done, which
 *   clears each entry's store and closes its data source. When the owner is destroyed by then, its
 *   `ViewModelStore` decides: it clears the stack with the store, or keeps it when the store
 *   outlives the owner, as for a FoA screen covered by a push.
 *
 * [owner] is the Activity or Fragment that shows the host. [key] names the host within it: keep it
 * the same across recreation, give each host in an owner its own key, and show one host per owner
 * and key at a time; a second host for the same stack fails. Keys name a fixed set of hosts, not
 * data: each key keeps a holder in the owner's `ViewModelStore` while the owner lives. The host is
 * keyed by [key], so a host for another key is another host, even in the same place in the tree;
 * don't give it a key of your own. Show a host in one `LithoHost`: moving it to another tree isn't
 * supported. A host created again with the same key, as by another `setContent`, continues the one
 * shown, with the new host's session provider, tracer and close callback.
 *
 * An entry covered by a push keeps its `useSaveableState` values and gets them back when it is on
 * top again, also after recreation and process death; its plain `useState` values start over, and a
 * list that doesn't save its scroll position comes back at its top. The values are saved under the
 * entry's FoA entry id; values of entries that can't be restored are never shown, but they stay in
 * the saved state while the host is in the tree.
 *
 * Each entry has its own lifecycle, which its screen reads through `LifecycleOwnerTreeProp` and
 * lifecycle-aware hooks such as `useFlowWithLifecycle`. The top entry follows the host's lifecycle,
 * covered entries stay at most `CREATED`, and entries that leave the stack are `DESTROYED`. If the
 * back stack is emptied, as when its owner's `ViewModelStore` is cleared while the host shows it,
 * the host keeps showing the last screen, which stays `CREATED`, until another screen replaces it.
 *
 * [backPress] says who handles system back. With [LithoNavBackPress.HostManaged], the host pops
 * while the stack has more than one entry, and at the root calls `onCloseRequested` and leaves the
 * stack as it is: the parent decides what closing means, as finishing the Activity or popping its
 * Fragment, and the stack is cleared when the host leaves. While it is started, such a host takes
 * system back ahead of back callbacks added before it, as another host's or that of a Fragment back
 * stack shown over it. With [LithoNavBackPress.Unmanaged] the host adds no back callback: use it
 * when a parent dispatches back itself, as FoA's tabbed navigator does, when the host can be
 * started while it is hidden, as on a tab page, or when back has to reach another handler first,
 * and pop the stack from the parent through [handle]. In a dialog that handles back itself, such as
 * a `DialogFragment`, system back goes to the dialog rather than the host; there, use
 * [LithoNavBackPress.Unmanaged] and pop through [handle] from a callback on the dialog's own
 * dispatcher. Screens change without a transition, and there is no predictive-back preview.
 *
 * The host reads its session from [sessionProvider] when it is created, and [tracer] hears its
 * back-stack events, as with FoA's Fragment host. [style] applies to the container around the top
 * screen. Change the back stack on the main thread. The tree needs a lifecycle owner, as
 * `LithoHost` gives it; in a tree built without one, as with `LithoView.create`, entries never
 * start and system back isn't handled.
 */
@ExperimentalLithoApi
@MainThread
fun <O> LithoNavHost(
    owner: O,
    key: String,
    backStack: FoaBackStack<LithoDestination>,
    sessionProvider: FoaBackStackSessionProvider,
    backPress: LithoNavBackPress,
    handle: LithoNavHostHandle? = null,
    tracer: BackStackTracer = NoOpBackStackTracer,
    style: Style = Style,
): Component where O : ViewModelStoreOwner, O : SavedStateRegistryOwner {
  ThreadUtils.assertMainThread()
  return LithoNavHost(
      key,
      backStackHolder(owner, key),
      backStack,
      sessionProvider,
      handle,
      owner.lifecycle,
      tracer,
      backPress,
      style,
      FoaDestinationSpecRegistry.specs(),
  )
}

/** Who handles system back for a [LithoNavHost]. */
@ExperimentalLithoApi
sealed interface LithoNavBackPress {

  /**
   * The host handles system back: it pops while the back stack has more than one entry, and at the
   * root calls [onCloseRequested] without changing the back stack. The host's back callback is off
   * while [onCloseRequested] runs, so a back sent through the Activity's dispatcher from inside it
   * goes on to the Activity or Fragment. A parent that decides later, as after a confirmation,
   * finishes or pops itself rather than sending back again.
   */
  class HostManaged(val onCloseRequested: () -> Unit) : LithoNavBackPress

  /**
   * The parent handles system back and pops the back stack through a [LithoNavHostHandle]; pass one
   * as the host's `handle`.
   */
  data object Unmanaged : LithoNavBackPress
}

/**
 * Gives the parent of a [LithoNavHost] the host's live back stack, for example to pop it when the
 * parent handles back. Create one as a field of the Activity or Fragment, pass it to one host, and
 * read it on the main thread; the last host created with it wins. It doesn't report changes to the
 * stack.
 */
@ExperimentalLithoApi
class LithoNavHostHandle {

  internal var holder: BackStackHolder? = null

  /**
   * The back stack the host shows: null until a host is created with this handle, as for a retained
   * Fragment whose `setContent` runs before it has a Context, and after the host clears the stack.
   */
  @get:MainThread
  val backStack: FoaBackStack<LithoDestination>?
    get() = holder?.lithoBackStack()
}

/**
 * Returns the back stack of the nearest [LithoNavHost], for its screens to navigate with, for
 * example `push` and `pop`. Change it on the main thread, as from an event handler.
 *
 * To go back as system back does, send a system back through the Activity's
 * `OnBackPressedDispatcher`, as FDS's navigation bar does; in a dialog, back goes to the dialog's
 * own dispatcher. `pop()` never removes the root entry. Don't call `handleBack()` or `clear()` at
 * the root: FoA then empties the back stack and clears the root entry's ViewModels, and the host
 * keeps showing the screen without asking to close. To handle back in a screen, add an
 * `OnBackPressedCallback` with `LifecycleOwnerTreeProp`'s owner; it runs before the host's while
 * the screen is on top, and, like the host's, while a hidden host stays started.
 *
 * To test a screen without a host, read the back stack in a root component and pass navigation
 * lambdas to the component that draws the screen.
 */
@ExperimentalLithoApi
fun ComponentScope.useBackStack(): FoaBackStack<LithoDestination> =
    checkNotNull(BackStackTreeProp.value) { "useBackStack() called outside a LithoNavHost" }

private val BackStackTreeProp: TreeProp<FoaBackStack<LithoDestination>?> = treePropOf { null }

/** The holder of the back stack that the host with [key] keeps in [owner]'s `ViewModelStore`. */
internal fun <O> backStackHolder(owner: O, key: String): BackStackHolder
    where O : ViewModelStoreOwner, O : SavedStateRegistryOwner {
  val extras = MutableCreationExtras()
  extras[SAVED_STATE_REGISTRY_OWNER_KEY] = owner
  extras[VIEW_MODEL_STORE_OWNER_KEY] = owner
  return ViewModelProvider.create(owner.viewModelStore, BackStackHolderFactory, extras)[
          "$HOLDER_KEY_PREFIX$key", BackStackHolder::class]
}

private val BackStackHolderFactory: ViewModelProvider.Factory = viewModelFactory {
  initializer { BackStackHolder(createSavedStateHandle()) }
}

// Only a LithoNavHost puts a back stack in its holder, so its destinations are Litho ones.
@Suppress("UNCHECKED_CAST")
private fun BackStackHolder.lithoBackStack(): FoaBackStack<LithoDestination>? =
    backStack as FoaBackStack<LithoDestination>?

/**
 * Creates the host for [key], showing the held back stack, or [seed] when the holder has none or
 * only an emptied one. [ownerLifecycle] is the lifecycle of the holder's owner.
 */
@MainThread
internal fun LithoNavHost(
    key: String,
    backStackHolder: BackStackHolder,
    seed: FoaBackStack<LithoDestination>,
    sessionProvider: FoaBackStackSessionProvider,
    handle: LithoNavHostHandle?,
    ownerLifecycle: Lifecycle?,
    tracer: BackStackTracer,
    backPress: LithoNavBackPress,
    style: Style,
    destinationSpecs: FoaDestinationSpecs,
): Component {
  ThreadUtils.assertMainThread()
  val held = backStackHolder.lithoBackStack()
  val backStack =
      if (held != null && held.backStack.isNotEmpty()) {
        // The held stack wins after a configuration change or process death; the seed never shows.
        if (seed !== held) seed.clear()
        held
      } else {
        require(seed.backStack.isNotEmpty()) {
          "LithoNavHost got an empty back stack. Build it inside the setContent lambda, so each " +
              "call gets a new one."
        }
        seed.also { backStackHolder.backStack = it }
      }
  handle?.holder = backStackHolder
  val controller =
      BackStackController(backStack, sessionProvider.foaSession, tracer, destinationSpecs)
  // Renderers may read the entry's prefetched data source, so prepare it before the first screen.
  // attach() prepares again, which reuses it.
  controller.prepareEntries()
  val topRoot = createTopRoot(backStack, controller.session, destinationSpecs)
  return key(key) {
    LithoNavHostComponent(
        backStack,
        backStackHolder,
        sessionProvider,
        ownerLifecycle,
        destinationSpecs,
        controller,
        backPress,
        NavState.create(backStack.entryIds, topRoot),
        style,
    )
  }
}

private class LithoNavHostComponent(
    // FoaBackStack, BackStackHolder, FoaBackStackSessionProvider, Lifecycle, FoaDestinationSpecs,
    // BackStackController, LithoNavBackPress, and NavState do not implement structural equals,
    // so
    // the lint cannot verify that re-renders with the same objects are no-ops. Changes to these
    // inputs are handled through effects and state, not prop equality.
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val backStack: FoaBackStack<LithoDestination>,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val backStackHolder: BackStackHolder,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val sessionProvider: FoaBackStackSessionProvider,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val ownerLifecycle: Lifecycle?,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val destinationSpecs: FoaDestinationSpecs,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val controller: BackStackController<LithoDestination>,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val backPress: LithoNavBackPress,
    @field:SuppressLint("KComponentNonComparableInputDetector") private val initialState: NavState,
    private val style: Style,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val holder = useSaveableStateHolder()
    val navState = useState { initialState }
    val tracker = useRef<EntryTracker?> { null }
    // The back stack the host attached to last; a host created again in place can bring another.
    val shownStack = useRef<FoaBackStack<LithoDestination>?> { null }
    // The latest close callback, so a host created again in place keeps its back callback.
    val onCloseRequested = useRef<(() -> Unit)?> { null }
    val backCallback = useRef<OnBackPressedCallback?> { null }
    val handlesBack = backPress is LithoNavBackPress.HostManaged
    val androidContext = context.androidContext
    val lifecycleOwner = LifecycleOwnerTreeProp.value
    val state = navState.value

    useEffect(backPress) {
      onCloseRequested.value = (backPress as? LithoNavBackPress.HostManaged)?.onCloseRequested
      null
    }
    useEffect(
        backStack,
        sessionProvider,
        controller,
        holder,
        lifecycleOwner,
    ) {
      check(!backStack.isDisplayed) {
        "Another LithoNavHost shows this back stack; show one host per owner and key at a time."
      }
      shownStack.value = backStack
      val entries = tracker.getOrCreate(state)
      entries.attachHost(lifecycleOwner)
      controller.attach {
        onStackChanged(backStack, controller.session, destinationSpecs, entries, holder, navState)
        backCallback.value?.isEnabled = backStack.backStack.isNotEmpty()
      }
      onCleanup {
        controller.detach()
        entries.detachHost()
      }
    }
    useEffect(Unit) {
      onCleanup {
        // On unmount: destroy all remaining entry lifecycles immediately so they are not kept alive
        // by the host observer until the Activity destroys.
        tracker.value?.destroyAll()
        // A destroyed owner's ViewModelStore decides whether the stack outlives it.
        if (
            ownerLifecycle?.currentState != Lifecycle.State.DESTROYED &&
                androidContext.findActivity()?.isChangingConfigurations != true
        ) {
          clearWhenNotDisplayed(shownStack.value ?: backStack, backStackHolder, ownerLifecycle)
        }
      }
    }
    useEffect(state) {
      tracker.getOrCreate(state).dropRemoved(committed = state).forEach(holder::removeState)
      null
    }
    val topId = state.entries.lastOrNull()?.id
    useEffect(topId) {
      if (topId != null) controller.onRendered(state.entries.lastIndex)
      null
    }

    val top = state.top ?: return EmptyComponent()
    return Column(style = style) {
      // Effects run children first, in child order, and the dispatcher orders callbacks by when
      // they were added: first, so the top screen's callbacks handle back before the host's.
      child(HostBackCallback(backStack, handlesBack, onCloseRequested, backCallback))
      child(
          holder.SaveableStateProvider(top.id) {
            TreePropProvider(
                LifecycleOwnerTreeProp to top.entry.lifecycleOwner,
                BackStackTreeProp to backStack,
            ) {
              top.root
            }
          },
      )
    }
  }
}

/** Runs on the main thread after each back-stack change, before the tree renders it. */
private fun onStackChanged(
    backStack: FoaBackStack<LithoDestination>,
    session: FoaUserSession,
    destinationSpecs: FoaDestinationSpecs,
    tracker: EntryTracker,
    holder: LithoSaveableStateHolder,
    navState: State<NavState>,
) {
  ThreadUtils.assertMainThread()
  val previous = tracker.latest
  val next =
      previous.next(backStack.entryIds) { createTopRoot(backStack, session, destinationSpecs) }
  if (next.ids == previous.ids) return
  val coveredId = previous.top?.id
  if (coveredId != null && coveredId != next.top?.id && coveredId in next.ids) {
    // Its values are still registered; they are gone once the update commits.
    holder.retain(coveredId)
  }
  // Before publishing: an entry's lifecycle callback can navigate while it is published, and that
  // later update has to win.
  navState.update(next)
  tracker.publish(next)
}

/**
 * Clears [backStack] from [holder] once the commit that removed its host is done, unless another
 * host displays it by then, as when the same host moves to a new place in the tree in that commit,
 * or the holder's owner is destroyed by then. A displayed back stack has `isDisplayed` true.
 */
private fun clearWhenNotDisplayed(
    backStack: FoaBackStack<LithoDestination>,
    holder: BackStackHolder,
    ownerLifecycle: Lifecycle?,
) {
  Handler(Looper.getMainLooper()).post {
    if (
        ownerLifecycle?.currentState != Lifecycle.State.DESTROYED &&
            holder.backStack === backStack &&
            !backStack.isDisplayed
    ) {
      holder.backStack = null
    }
  }
}

private fun createTopRoot(
    backStack: FoaBackStack<LithoDestination>,
    session: FoaUserSession,
    destinationSpecs: FoaDestinationSpecs,
): Component {
  val destination = backStack.backStack.last()
  val entryIds = backStack.entryIds
  val scope = LithoEntryScope(destination, backStack.viewModelStores.last(), session)
  val root =
      try {
        rendererFor(destinationSpecs, destination).create(scope)
      } finally {
        scope.active = false
      }
  check(backStack.entryIds == entryIds) {
    "The renderer for ${destination.javaClass.name} changed the back stack; navigate from an " +
        "effect or an event handler instead."
  }
  return root
}

private fun rendererFor(
    destinationSpecs: FoaDestinationSpecs,
    destination: LithoDestination,
): LithoDestinationRenderer<LithoDestination> {
  val renderer = destinationSpecs.specFor(destination).renderer
  check(renderer is LithoDestinationRenderer<*>) {
    "LithoNavHost requires a Litho renderer for ${destination.javaClass.name}, but its spec " +
        "uses ${renderer.javaClass.name}."
  }
  @Suppress("UNCHECKED_CAST")
  return renderer as LithoDestinationRenderer<LithoDestination>
}

/**
 * Adds the host's back callback while [handlesBack], and keeps it in [callback]. The container
 * gives the effect a node of its own.
 */
private class HostBackCallback(
    // FoaBackStack and Ref do not implement structural equals; changes are handled by the effect.
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val backStack: FoaBackStack<LithoDestination>,
    private val handlesBack: Boolean,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val onCloseRequested: Ref<(() -> Unit)?>,
    @field:SuppressLint("KComponentNonComparableInputDetector")
    private val callback: Ref<OnBackPressedCallback?>,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val androidContext = context.androidContext
    val lifecycleOwner = LifecycleOwnerTreeProp.value
    useEffect(backStack, androidContext, lifecycleOwner, handlesBack) {
      val added =
          if (handlesBack) {
            addBackCallback(backStack, androidContext, lifecycleOwner, onCloseRequested)
          } else {
            null
          }
      callback.value = added
      onCleanup {
        added?.remove()
        callback.value = null
      }
    }
    return Column(style = Style.positionType(YogaPositionType.ABSOLUTE))
  }
}

private fun addBackCallback(
    backStack: FoaBackStack<*>,
    context: Context,
    lifecycleOwner: LifecycleOwner?,
    onCloseRequested: Ref<(() -> Unit)?>,
): OnBackPressedCallback? {
  val dispatcherOwner = context.findBackDispatcherOwner() ?: return null
  val callback =
      object : OnBackPressedCallback(enabled = backStack.backStack.isNotEmpty()) {
        override fun handleOnBackPressed() {
          if (backStack.pop()) return
          // Off while the parent closes, so a parent that sends back on to the system doesn't
          // reach this callback again.
          isEnabled = false
          try {
            onCloseRequested.value?.invoke()
          } finally {
            isEnabled = backStack.backStack.isNotEmpty()
          }
        }
      }
  dispatcherOwner.onBackPressedDispatcher.addCallback(lifecycleOwner ?: dispatcherOwner, callback)
  return callback
}

private fun Ref<EntryTracker?>.getOrCreate(state: NavState): EntryTracker =
    value ?: EntryTracker(state).also { value = it }

private fun Context.findBackDispatcherOwner(): OnBackPressedDispatcherOwner? {
  var context: Context? = this
  while (context != null) {
    if (context is OnBackPressedDispatcherOwner) return context
    context = (context as? ContextWrapper)?.baseContext
  }
  return null
}

private fun Context.findActivity(): Activity? {
  var context: Context? = this
  while (context != null) {
    if (context is Activity) return context
    context = (context as? ContextWrapper)?.baseContext
  }
  return null
}

private const val HOLDER_KEY_PREFIX: String = "com.facebook.litho.navigation.LithoNavHost:"
