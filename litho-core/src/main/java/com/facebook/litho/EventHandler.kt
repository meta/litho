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

import android.annotation.SuppressLint
import com.facebook.litho.ComponentsSystrace.trace
import com.facebook.litho.annotations.EventHandlerRebindMode
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.rendercore.Equivalence
import com.facebook.rendercore.Function
import com.facebook.rendercore.utils.CommonUtils

open class EventHandler<E>(
    @JvmField val id: Int,
    @JvmField val mode: EventHandlerRebindMode,
    @JvmField var dispatchInfo: EventDispatchInfo,
    @JvmField val params: Array<out Any?>?,
) : Function<Void?>, Equivalence<EventHandler<*>?> {

  @SuppressLint("KotlinGenericsCast")
  @Suppress("UNCHECKED_CAST")
  override fun call(vararg arguments: Any?): Void? {
    dispatchEvent(arguments[0] as E)
    return null
  }

  open fun dispatchEvent(event: E): Any? {
    return if (ComponentsConfiguration.lazyEventHandlerTraceName) {
      trace({ "onEvent:$this" }) {
        requireNotNull(dispatchInfo.hasEventDispatcher?.getEventDispatcher())
            .dispatchOnEvent(this, event as Any)
      }
    } else {
      trace("onEvent:$this") {
        requireNotNull(dispatchInfo.hasEventDispatcher?.getEventDispatcher())
            .dispatchOnEvent(this, event as Any)
      }
    }
  }

  override fun isEquivalentTo(other: EventHandler<*>?): Boolean {
    if (this === other) {
      return true
    }

    if (other == null) {
      return false
    }

    if (other.javaClass != javaClass) {
      return false
    }

    if (id != other.id) {
      return false
    }

    if (mode != other.mode) {
      return false
    }

    if (dispatchInfo != other.dispatchInfo) {
      return false
    }

    if (params === other.params) {
      return true
    }

    if (params == null || other.params == null) {
      return false
    }

    if (params.size != other.params.size) {
      return false
    }

    for (i in params.indices) {
      val object1 = params[i]
      val object2 = other.params[i]

      if (object1 != object2) {
        return false
      }
    }

    // Deliberately don't check ComponentContext which will change between EventHandlers.
    return true
  }

  override fun toString(): String {
    val hasEventDispatcher = dispatchInfo.hasEventDispatcher
    return CommonUtils.getSectionNameForTracing(
        if (hasEventDispatcher != null && hasEventDispatcher !== this) {
          hasEventDispatcher.javaClass
        } else {
          javaClass
        },
    )
  }

  open val globalKey: GlobalKey?
    get() {
      val context = dispatchInfo.componentContext
      return context?.globalKey
    }

  open val tag: String?
    get() = dispatchInfo.tag
}
