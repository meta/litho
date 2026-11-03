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

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.facebook.litho.Component
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentTree
import com.facebook.litho.LithoSaveableStateRegistryTreeProp
import com.facebook.litho.LithoView
import com.facebook.litho.LithoVisibilityEventsController
import com.facebook.litho.ThreadUtils
import com.facebook.litho.TreePropContainer
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.debug.DebugInfoReporter
import com.facebook.rendercore.LogLevel
import com.facebook.rendercore.extensions.VisibilityChangeReason
import java.util.WeakHashMap

/**
 * Hosts one root Litho [Component] for an Activity or Fragment: it owns the `ComponentTree` and the
 * [LithoView] that shows it.
 *
 * The tree lives as long as the Activity or Fragment, not its view, so [setContent] can build it in
 * `onCreate()`, before the view exists, and a Fragment keeps its tree, with all of its state, while
 * its view is destroyed and recreated on the back stack. The tree renders once its view is first
 * measured, and is released when the Activity or Fragment is destroyed. `useSaveableState` values
 * come back after recreation and process death; plain `useState` values do not.
 *
 * A Fragment instance can outlive what the host was built with, and the host follows it:
 * - removed and added again, it gets a new Lifecycle from AndroidX, and the host starts over on it;
 * - with `setRetainInstance(true)`, it keeps its instance when its Activity is recreated. The host
 *   releases the tree when the Activity it was built with is destroyed and builds a new one with
 *   the new Activity's Context from the last [setContent] content. Its `useSaveableState` values
 *   carry over; plain `useState` values do not, as on any recreation.
 *
 * Obtain a host with [Fragment.lithoHost] or [ComponentActivity.lithoHost]. Each host is identified
 * by its declaration order within its Activity or Fragment, so declare hosts as unconditional
 * properties and keep their order stable. Declaring one once the owner is created, as in
 * `onCreateView`, is reported: each run creates another host and tree.
 */
@ExperimentalLithoApi
class LithoHost
internal constructor(
    private val key: String,
    private val owner: SavedStateRegistryOwner,
    private val context: () -> Context?,
    private val viewLifecycleOwner: () -> LifecycleOwner?,
    private val componentsConfiguration: ComponentsConfiguration?,
    private val customVisibilityController: LithoVisibilityEventsController?,
    private val treeProps: (() -> TreePropContainer?)?,
) {
  private var componentTree: ComponentTree? = null
  private var boundView: LithoView? = null
  private var boundViewOwner: LifecycleOwner? = null
  private var destroyed = false
  private var observedLifecycle: Lifecycle? = null
  private var contextOwnerLifecycle: Lifecycle? = null
  private var contextOwnerObserver: LifecycleObserver? = null
  private var content: (() -> Component)? = null
  private var reportedUseAfterDestroy = false

  init {
    observeOwner()
  }

  /**
   * Sets the root component, building the tree if the host has none and replacing the root
   * otherwise. A replaced root is laid out in the background, as `LithoView.setComponentAsync`
   * does; [setContentSync] lays it out before returning. The host keeps [content] to rebuild the
   * tree if the Fragment's Context changes. After the host's owner is destroyed this does nothing,
   * so content that arrives late, as from a network callback, is dropped. A Fragment that was only
   * removed keeps the content for when it is added again.
   */
  @MainThread
  fun setContent(content: () -> Component) {
    applyContent(content, sync = false)
  }

  /** Same as [setContent]: a replaced root is laid out in the background. */
  @MainThread
  fun setContentAsync(content: () -> Component) {
    setContent(content)
  }

  /**
   * Like [setContent], but a root that replaces the root of a measured tree is laid out on the
   * calling thread before this returns, as `LithoView.setComponent` does. Before the tree's first
   * measure, the root is laid out at that measure.
   */
  @MainThread
  fun setContentSync(content: () -> Component) {
    applyContent(content, sync = true)
  }

  private fun applyContent(content: () -> Component, sync: Boolean) {
    ThreadUtils.assertMainThread()
    observeOwner()
    if (destroyed) {
      if (this.content == null) reportUseAfterDestroy()
      return
    }
    this.content = content
    // A retained Fragment has no Context between its Activities; its next view builds with this.
    val context = context() ?: return
    releaseTreeIfContextChanged(context)
    forgetReleasedTree()
    val root = content()
    when (val tree = componentTree) {
      null -> showTree(buildTree(context, root))
      else -> if (sync) tree.setRoot(root) else tree.setRootAsync(root)
    }
  }

  /**
   * Returns the [LithoView] showing this host's tree for the current view lifecycle, the same one
   * for every call within it. Before the first [setContent] the view has no tree and shows nothing.
   * The first [setContent] gives it one, and the view lays out that content at its next measure, as
   * `LithoView.setComponentAsync` does for a view without a tree. Later content is laid out as
   * [setContent] and [setContentSync] describe.
   */
  @MainThread
  fun getLithoView(): LithoView {
    checkUsable("getLithoView")
    val context = requireContext("getLithoView")
    val tree = content?.let { ensureTree(context, it) }
    bindViewLifecycle()
    boundView?.let {
      return it
    }
    return LithoView(context).also { view ->
      view.componentTree = tree
      boundView = view
      if (viewLifecycleOwner() == null) releaseWhenFragmentViewDestroyed(view)
    }
  }

  private fun buildTree(context: Context, root: Component): ComponentTree {
    // Tree props copied from another tree may carry its saveable-state registry, which would save
    // this tree inside that one, under no key, instead of under this host's key.
    val treePropContainer =
        treeProps
            ?.invoke()
            ?.takeUnless { it.isEmpty() }
            ?.let { TreePropContainer.copy(it) }
            ?.apply { put(LithoSaveableStateRegistryTreeProp, null) }
    val builder =
        ComponentTree.create(
                ComponentContext(context, treePropContainer = treePropContainer),
                owner,
                root,
            )
            .withSaveableStateKey(key)
    customVisibilityController?.let(builder::withLithoVisibilityEventsController)
    componentsConfiguration?.let(builder::componentsConfiguration)
    return builder.build().also { tree -> releaseWhenContextOwnerDestroyed(context, tree) }
  }

  /**
   * Releases [tree] when the Activity it was built with is destroyed while the owner lives on, as a
   * retained Fragment's tree is during an Activity recreation, even if the Fragment has no view to
   * rebuild it from. The released tree parks its saveable values under this host's key, so the next
   * tree takes them.
   *
   * The host stops watching once [tree] is released or forgotten, so a Fragment destroyed before
   * its Activity isn't kept reachable from it.
   */
  private fun releaseWhenContextOwnerDestroyed(context: Context, tree: ComponentTree) {
    val contextOwner = context as? LifecycleOwner ?: return
    if (contextOwner === owner) return
    stopWatchingContextOwner()
    val observer =
        object : DefaultLifecycleObserver {
          override fun onDestroy(owner: LifecycleOwner) {
            stopWatchingContextOwner()
            if (componentTree === tree) releaseTree()
          }
        }
    contextOwner.lifecycle.addObserver(observer)
    contextOwnerLifecycle = contextOwner.lifecycle
    contextOwnerObserver = observer
  }

  private fun stopWatchingContextOwner() {
    contextOwnerObserver?.let { contextOwnerLifecycle?.removeObserver(it) }
    contextOwnerLifecycle = null
    contextOwnerObserver = null
  }

  /** Makes [tree] the host's tree, and shows it in the current view if there is one. */
  private fun showTree(tree: ComponentTree) {
    componentTree = tree
    boundView?.componentTree = tree
  }

  /**
   * Forgets a tree that the caller's visibility controller released by moving to DESTROYED while
   * the owner lives on, so the host builds a new one from its last content.
   */
  private fun forgetReleasedTree() {
    if (componentTree?.isReleased != true) return
    stopWatchingContextOwner()
    componentTree = null
  }

  /** Releases the view and the tree. */
  private fun releaseTree() {
    releaseView()
    stopWatchingContextOwner()
    componentTree?.release()
    componentTree = null
  }

  /**
   * Follows the owner's current Lifecycle. A Fragment removed and added again gets a new one, so
   * the host starts over on it instead of staying destroyed with the old one.
   */
  private fun observeOwner() {
    val lifecycle = owner.lifecycle
    if (lifecycle === observedLifecycle) return
    observedLifecycle = lifecycle
    // A destroyed Lifecycle never sends onDestroy again, so a host first used after it is unusable.
    destroyed = lifecycle.currentState == Lifecycle.State.DESTROYED
    if (destroyed) return
    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (observedLifecycle === lifecycle) destroy()
          }
        },
    )
  }

  /**
   * Releases the tree if it was built with an earlier Context, as a retained Fragment's tree is
   * once its Activity is recreated.
   */
  private fun releaseTreeIfContextChanged(context: Context) {
    val tree = componentTree ?: return
    if (tree.context.androidContext === context) return
    releaseTree()
  }

  /**
   * Gives the host a tree built with [context] from [content]: a new one for a new Context, or when
   * it has none, as for a Fragment added again or content set before it had a Context.
   */
  private fun ensureTree(context: Context, content: () -> Component): ComponentTree {
    releaseTreeIfContextChanged(context)
    forgetReleasedTree()
    return componentTree ?: buildTree(context, content()).also(::showTree)
  }

  /** Follows the view lifecycle the LithoView belongs to, and lets go of the view when it ends. */
  private fun bindViewLifecycle() {
    val viewOwner = viewLifecycleOwner() ?: owner
    if (boundViewOwner === viewOwner) return
    releaseView()
    boundViewOwner = viewOwner
    viewOwner.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onResume(owner: LifecycleOwner) {
            if (boundViewOwner === owner) {
              boundView?.notifyVisibleBoundsChanged(VisibilityChangeReason.LIFECYCLE)
            }
          }

          override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (boundViewOwner === owner) releaseView()
          }
        },
    )
  }

  /**
   * Lets go of [view] when the Fragment's view lifecycle ends while it has no view lifecycle owner,
   * as for a dialog built in `DialogFragment.onCreateDialog`: AndroidX reports that moment after
   * the dialog is dismissed, whether or not the Fragment has a view, so the next dialog gets a
   * fresh one.
   */
  private fun releaseWhenFragmentViewDestroyed(view: LithoView) {
    val fragment = owner as? Fragment ?: return
    fragment.parentFragmentManager.registerFragmentLifecycleCallbacks(
        object : FragmentManager.FragmentLifecycleCallbacks() {
          override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f !== fragment) return
            fm.unregisterFragmentLifecycleCallbacks(this)
            if (boundView === view) {
              view.componentTree = null
              boundView = null
            }
          }
        },
        false,
    )
  }

  private fun releaseView() {
    boundView?.componentTree = null
    boundView = null
    boundViewOwner = null
  }

  private fun destroy() {
    releaseTree()
    destroyed = true
  }

  private fun requireContext(operation: String): Context =
      checkNotNull(context()) { "[$key] $operation() called while the Fragment has no Context" }

  /** Reports a host whose first use comes after its owner is destroyed; it never shows anything. */
  private fun reportUseAfterDestroy() {
    if (reportedUseAfterDestroy) return
    reportedUseAfterDestroy = true
    DebugInfoReporter.report(category = USED_AFTER_DESTROY_CATEGORY, level = LogLevel.WARNING) {
      this["owner"] = owner.javaClass.name
      this["key"] = key
    }
  }

  private fun checkUsable(operation: String) {
    ThreadUtils.assertMainThread()
    observeOwner()
    check(!destroyed) { "[$key] $operation() called after the host's owner was destroyed" }
  }
}

/**
 * Creates a [LithoHost] identified by its declaration order within this Fragment.
 *
 * Without a [visibilityController] the tree has none of its own, so the [LithoView]'s default
 * visibility controller follows the lifecycle owner of the view tree it is attached to, where
 * `ComponentsConfiguration.enableDefaultVisibilityEventsController` is on. To decide visibility
 * yourself, as for pages a pager keeps resumed while they are off screen, pass a
 * [visibilityController] and move it with `moveToVisibilityState`; the host then never changes it.
 * Moving it to DESTROYED is also up to you, and nested lists release their rows only then. The host
 * still releases its tree when the Fragment is destroyed. Moving it to DESTROYED while the Fragment
 * lives on releases the tree, and the host builds a new one from its last content when it next
 * needs one.
 *
 * A Fragment hidden with `FragmentTransaction.hide()` keeps its lifecycle, so its tree still counts
 * as visible. To change that, pass a [visibilityController] and move it in `onHiddenChanged()`.
 *
 * The [LithoView] uses the Fragment's Context, so a DialogFragment's tree uses the Activity's theme
 * rather than the dialog's.
 */
@ExperimentalLithoApi
fun Fragment.lithoHost(
    componentsConfiguration: ComponentsConfiguration? = null,
    visibilityController: LithoVisibilityEventsController? = null,
    treeProps: (() -> TreePropContainer?)? = null,
): Lazy<LithoHost> {
  val key = hostKey(LithoHostIndexes.next(this))
  return lazy(LazyThreadSafetyMode.NONE) {
    LithoHost(
        key = key,
        owner = this,
        context = ::getContext,
        viewLifecycleOwner = ::viewLifecycleOwnerOrNull,
        componentsConfiguration = componentsConfiguration,
        customVisibilityController = visibilityController,
        treeProps = treeProps,
    )
  }
}

/**
 * Creates a [LithoHost] identified by its declaration order within this Activity.
 *
 * Visibility works as for [Fragment.lithoHost].
 */
@ExperimentalLithoApi
fun ComponentActivity.lithoHost(
    componentsConfiguration: ComponentsConfiguration? = null,
    visibilityController: LithoVisibilityEventsController? = null,
    treeProps: (() -> TreePropContainer?)? = null,
): Lazy<LithoHost> {
  val key = hostKey(LithoHostIndexes.next(this))
  return lazy(LazyThreadSafetyMode.NONE) {
    LithoHost(
        key = key,
        owner = this,
        context = { this },
        viewLifecycleOwner = { this },
        componentsConfiguration = componentsConfiguration,
        customVisibilityController = visibilityController,
        treeProps = treeProps,
    )
  }
}

/**
 * The Fragment's view lifecycle owner, or null while it has none: before its view exists, as in
 * `DialogFragment.onCreateDialog`, after the view is destroyed, or for a DialogFragment that builds
 * its dialog without a view. AndroidX only exposes it by throwing.
 */
private fun Fragment.viewLifecycleOwnerOrNull(): LifecycleOwner? =
    try {
      viewLifecycleOwner
    } catch (_: IllegalStateException) {
      null
    }

/**
 * Hands out each host's index within its owner, in declaration order. A recreated owner declares
 * the same hosts in the same order, so each gets back the same index. Owners may be constructed off
 * the main thread, so this may run on any thread.
 */
private object LithoHostIndexes {
  private val nextIndexes = WeakHashMap<LifecycleOwner, Int>()

  fun next(owner: LifecycleOwner): Int {
    val index =
        synchronized(nextIndexes) {
          (nextIndexes[owner] ?: 0).also { nextIndexes[owner] = it + 1 }
        }
    // A host declared once the owner is created, as in onCreateView, gets a new index and a new
    // tree each time that code runs, and the old trees stay alive until the owner is destroyed.
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
      DebugInfoReporter.report(category = DECLARED_LATE_CATEGORY, level = LogLevel.WARNING) {
        this["owner"] = owner.javaClass.name
        this["index"] = index
      }
    }
    return index
  }
}

private fun hostKey(index: Int): String = "com.facebook.litho.surface.LithoHost:$index"

@VisibleForTesting
internal const val DECLARED_LATE_CATEGORY: String = "LithoHost:DeclaredAfterCreate"

@VisibleForTesting
internal const val USED_AFTER_DESTROY_CATEGORY: String = "LithoHost:UsedAfterDestroy"
