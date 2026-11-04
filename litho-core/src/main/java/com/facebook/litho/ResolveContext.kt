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

import android.util.Pair
import androidx.annotation.VisibleForTesting
import com.facebook.litho.state.DirtyScopeIndex

class ResolveContext
@JvmOverloads
constructor(
    override val treeId: Int,
    override val cache: MeasuredResultCache,
    treeState: TreeState?,
    override val layoutVersion: Int,
    override val rootComponentId: Int,
    override val isAccessibilityEnabled: Boolean,
    treeFuture: TreeFuture<*>?,
    val currentResolveResult: ResolveResult?,
    val isInLayout: Boolean,
    override val scopesToUpdate: DirtyScopeIndex = DirtyScopeIndex.EMPTY,
    enclosingLayoutContext: LithoLayoutContext? = null,
    override val discardCallbacks: DiscardCallbacks? = null,
) : CalculationContext {

  private var _treeState: TreeState? = treeState
  private var _future: TreeFuture<*>? = treeFuture
  private var _enclosingLayoutContext: LithoLayoutContext? = enclosingLayoutContext

  private var _isInterruptible: Boolean = treeFuture?.isInterruptible ?: false

  private var _cachedNodes: MutableMap<Int, LithoNode?>? = null
  private var _eventHandlers: MutableList<Pair<GlobalKey, EventHandler<*>>>? = null

  override val treeFuture: TreeFuture<*>?
    get() {
      return _future
    }

  override val isFutureReleased: Boolean
    get() {
      val future = _future
      return future != null && future.isReleased
    }

  override val treeState: TreeState
    get() {
      return checkNotNull(_treeState)
    }

  override val eventHandlers: List<Pair<GlobalKey, EventHandler<*>>>?
    get() {
      return _eventHandlers
    }

  internal val enclosingLayoutContext: LithoLayoutContext?
    get() = _enclosingLayoutContext

  override fun recordEventHandler(globalKey: GlobalKey, eventHandler: EventHandler<*>) {
    (_eventHandlers ?: ArrayList()).apply {
      _eventHandlers = this
      add(Pair(globalKey, eventHandler))
    }
  }

  fun markLayoutUninterruptible() {
    _isInterruptible = false
  }

  val isResolveInterrupted: Boolean
    get() {
      val isInterruptible = _isInterruptible
      return if (!isInterruptible || ThreadUtils.isMainThread) {
        false
      } else {
        val future = _future
        return future != null && future.isInterruptRequested
      }
    }

  fun consumeLayoutCreatedInWillRender(id: Int): LithoNode? = _cachedNodes?.remove(id)

  fun getLayoutCreatedInWillRender(id: Int): LithoNode? = _cachedNodes?.get(id)

  fun setLayoutCreatedInWillRender(id: Int, node: LithoNode?) {
    (_cachedNodes ?: HashMap()).apply {
      _cachedNodes = this
      put(id, node)
    }
  }

  @VisibleForTesting
  fun setLayoutStateFutureForTest(future: TreeFuture<*>) {
    _future = future
  }

  fun release() {
    _cachedNodes = null
    _future = null
    _treeState = null
    _enclosingLayoutContext = null
  }
}
