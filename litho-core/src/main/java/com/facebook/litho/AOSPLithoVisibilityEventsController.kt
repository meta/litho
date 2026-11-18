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

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState

/**
 * This LithoVisibilityEventsController implementation dispatches to the registered observers the
 * lifecycle state changes triggered by the provided LifecycleOwner. For example, if a Fragment is
 * passed as param, the observers will be registered to listen to all of the fragment's lifecycle
 * state changes.
 */
open class AOSPLithoVisibilityEventsController
@JvmOverloads
constructor(
    final override val lifecycleOwner: LifecycleOwner,
    /**
     * Allows this controller to be built off the main thread, by deferring observer registration.
     *
     * Off by default, which keeps [LifecycleRegistry]'s own main-thread check as the contract.
     */
    private val allowOffMainThreadRegistration: Boolean = false,
) : LithoVisibilityEventsController, LifecycleEventObserver, AOSPLifecycleOwnerProvider {

  private val delegate:
      LithoVisibilityEventsControllerDelegate = LithoVisibilityEventsControllerDelegate(
      if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
          LithoVisibilityState.HINT_VISIBLE
      else LithoVisibilityState.HINT_INVISIBLE,
  )

  private var released = false

  init {
    // LifecycleRegistry only accepts observers from the main thread. Registration replays the
    // owner's current state, so hopping loses nothing.
    if (allowOffMainThreadRegistration && Looper.myLooper() != Looper.getMainLooper()) {
      mainHandler.post { registerOrMarkDestroyed() }
    } else {
      lifecycleOwner.lifecycle.addObserver(this)
    }
  }

  /**
   * Registers this observer, unless the owner was destroyed while registration was queued.
   *
   * Adding to a destroyed lifecycle does not replay ON_DESTROY, so the observer would sit in the
   * registry forever and this controller would never learn it should move to
   * [LithoVisibilityState.DESTROYED]. Take that transition directly instead of registering.
   */
  private fun registerOrMarkDestroyed() {
    if (released) {
      return
    }
    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
      moveToVisibilityState(LithoVisibilityState.DESTROYED)
    } else {
      lifecycleOwner.lifecycle.addObserver(this)
    }
  }

  /**
   * Stops observing [lifecycleOwner], including a registration still queued for the main thread.
   */
  internal fun release() {
    released = true
    lifecycleOwner.lifecycle.removeObserver(this)
  }

  override val visibilityState: LithoVisibilityState
    get() = delegate.visibilityState

  override fun moveToVisibilityState(newVisibilityState: LithoVisibilityState) {
    delegate.moveToVisibilityState(newVisibilityState)
  }

  override fun addListener(listener: LithoVisibilityEventsListener) {
    delegate.addListener(listener)
  }

  override fun removeListener(listener: LithoVisibilityEventsListener) {
    delegate.removeListener(listener)
  }

  override fun onStateChanged(source: LifecycleOwner, event: Event) {
    when (event) {
      Lifecycle.Event.ON_RESUME -> moveToVisibilityState(LithoVisibilityState.HINT_VISIBLE)
      Lifecycle.Event.ON_PAUSE -> moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE)
      Lifecycle.Event.ON_DESTROY -> {
        moveToVisibilityState(LithoVisibilityState.DESTROYED)
        lifecycleOwner.lifecycle.removeObserver(this)
      }
      else -> {}
    }
  }

  private companion object {
    private val mainHandler = Handler(Looper.getMainLooper())
  }
}
