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

import androidx.annotation.UiThread
import com.facebook.litho.annotations.Hook

/**
 * [useCallback] allows a parent to pass a child component a callback which: 1) maintains
 * referential equality across multiple layout passes 2) is updated to have the latest parent props
 * and state, even if the child doesn't re-render.
 *
 * ## Example
 *
 * This sounds complicated, so it helps to step back a bit to provide context via example:
 *
 * Say we have a parent component which renders a list of children via a Collection. Let's say we
 * also want this parent to implement multi-select behavior, meaning on click, a row can be marked
 * as selected/de-selected. We will store the state of which components are currently selected in
 * the parent, and the parent will pass a lambda to the child to allow that state to be updated when
 * a row is clicked.
 *
 * When the first row is clicked, it will update the state in the parent via an onClick lambda. At
 * this point, we ideally want to re-render only that row since that's the only row whose UI will be
 * updated, i.e. to a selected state. However, that means the rest of the children will still have
 * that original onClick lambda set on them: if that lambda captured props/state, then invoking it
 * will operate on stale props/state! In this case, that means selecting a second row will de-select
 * the first one which is incorrect.
 *
 * An alternative is to always re-render all children whenever the list of selected items changes.
 * This is effective in that it will make sure all children have a lambda capturing the latest
 * props/state, however it's inefficient since it re-renders all children even though their UI
 * doesn't appear different.
 *
 * [useCallback] tries to give the best of both worlds: it doesn't cause children to re-render, and
 * also gives a mechanism for them to invoke a lambda that has captured the latest props and state.
 *
 * ## Notes: Thread Safety
 *
 * The callback that will be invoked by the function returned by [useCallback] is updated when a new
 * layout has been committed **on the main thread**. This means that [useCallback] should only be
 * used with events that will also be invoked from the main thread, e.g. onClick.
 */
@Hook
fun <R> ComponentScope.useCallback(
    callback: () -> R,
): () -> R {
  val callbackRef = useState { MemoizedCallback0(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

/** 1-parameter overload of [useCallback]: refer to docs there. */
@Hook
fun <A, R> ComponentScope.useCallback(
    callback: (A) -> R,
): (A) -> R {
  val callbackRef = useState { MemoizedCallback1(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

/** 2-parameter overload of [useCallback]: refer to docs there. */
@Hook
fun <A, B, R> ComponentScope.useCallback(
    callback: (A, B) -> R,
): (A, B) -> R {
  val callbackRef = useState { MemoizedCallback2(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

/** 3-parameter overload of [useCallback]: refer to docs there. */
@Hook
fun <A, B, C, R> ComponentScope.useCallback(
    callback: (A, B, C) -> R,
): (A, B, C) -> R {
  val callbackRef = useState { MemoizedCallback3(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

/** 4-parameter overload of [useCallback]: refer to docs there. */
@Hook
fun <A, B, C, D, R> ComponentScope.useCallback(
    callback: (A, B, C, D) -> R,
): (A, B, C, D) -> R {
  val callbackRef = useState { MemoizedCallback4(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

/** 5-parameter overload of [useCallback]: refer to docs there. */
@Hook
fun <A, B, C, D, E, R> ComponentScope.useCallback(
    callback: (A, B, C, D, E) -> R,
): (A, B, C, D, E) -> R {
  val callbackRef = useState { MemoizedCallback5(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

/** 6-parameter overload of [useCallback]: refer to docs there. */
@Hook
fun <A, B, C, D, E, F, R> ComponentScope.useCallback(
    callback: (A, B, C, D, E, F) -> R,
): (A, B, C, D, E, F) -> R {
  val callbackRef = useState { MemoizedCallback6(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

/** 7-parameter overload of [useCallback]: refer to docs there. */
@Hook
fun <A, B, C, D, E, F, G, R> ComponentScope.useCallback(
    callback: (A, B, C, D, E, F, G) -> R,
): (A, B, C, D, E, F, G) -> R {
  val callbackRef = useState { MemoizedCallback7(CallbackHolder(callback)) }
  useEffect(callback) {
    callbackRef.value.callbackHolder.callback = callback
    null
  }
  return callbackRef.value
}

// Implementation Notes:
//
// So that we don't have to introduce a bunch of classes to support these callback holders, these
// are all value classes that hold a single CallbackHolder. Their purpose is to behave as a function
// of some type (e.g. (A, B) -> R) such that when they are invoked, they just invoke the latest
// callback that has been set on them.

@JvmInline
private value class MemoizedCallback0<R>(val callbackHolder: CallbackHolder<() -> R>) : () -> R {
  override fun invoke(): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback()
  }
}

@JvmInline
private value class MemoizedCallback1<A, R>(val callbackHolder: CallbackHolder<(A) -> R>) :
    (A) -> R {
  override fun invoke(p1: A): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback(p1)
  }
}

@JvmInline
private value class MemoizedCallback2<A, B, R>(val callbackHolder: CallbackHolder<(A, B) -> R>) :
    (A, B) -> R {
  override fun invoke(p1: A, p2: B): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback(p1, p2)
  }
}

@JvmInline
private value class MemoizedCallback3<A, B, C, R>(
    val callbackHolder: CallbackHolder<(A, B, C) -> R>,
) : (A, B, C) -> R {
  override fun invoke(p1: A, p2: B, p3: C): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback(p1, p2, p3)
  }
}

@JvmInline
private value class MemoizedCallback4<A, B, C, D, R>(
    val callbackHolder: CallbackHolder<(A, B, C, D) -> R>,
) : (A, B, C, D) -> R {
  override fun invoke(p1: A, p2: B, p3: C, p4: D): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback(p1, p2, p3, p4)
  }
}

@JvmInline
private value class MemoizedCallback5<A, B, C, D, E, R>(
    val callbackHolder: CallbackHolder<(A, B, C, D, E) -> R>,
) : (A, B, C, D, E) -> R {
  override fun invoke(p1: A, p2: B, p3: C, p4: D, p5: E): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback(p1, p2, p3, p4, p5)
  }
}

@JvmInline
private value class MemoizedCallback6<A, B, C, D, E, F, R>(
    val callbackHolder: CallbackHolder<(A, B, C, D, E, F) -> R>,
) : (A, B, C, D, E, F) -> R {
  override fun invoke(p1: A, p2: B, p3: C, p4: D, p5: E, p6: F): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback(p1, p2, p3, p4, p5, p6)
  }
}

@JvmInline
private value class MemoizedCallback7<A, B, C, D, E, F, G, R>(
    val callbackHolder: CallbackHolder<(A, B, C, D, E, F, G) -> R>,
) : (A, B, C, D, E, F, G) -> R {
  override fun invoke(p1: A, p2: B, p3: C, p4: D, p5: E, p6: F, p7: G): R {
    ThreadUtils.assertMainThread()
    return callbackHolder.callback(p1, p2, p3, p4, p5, p6, p7)
  }
}

/** A simple holder for a lambda that can be updated to a newer instance on the UI thread. */
@Suppress("REDUNDANT_VISIBILITY_MODIFIER")
private class CallbackHolder<T : Function<*>>(@UiThread internal var callback: T)
