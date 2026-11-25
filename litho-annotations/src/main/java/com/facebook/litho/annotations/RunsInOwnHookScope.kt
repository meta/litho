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

package com.facebook.litho.annotations

/**
 * Marks a `ComponentScope` subtype whose bodies the framework always runs under their own hook
 * scope (`withNewScope` with a stable key), such as a LazyList `childGroup` body.
 *
 * A lambda with such a receiver may call hooks on that receiver even inside a plain named function:
 * its hooks are keyed under that scope, so their identity does not depend on how often or in what
 * order the function is called. A lambda passed to an inline function, such as `with(scope)`, `run`
 * or `apply`, runs as part of the code around it and gets no such scope, whatever its receiver's
 * type; nor does a hook called on any other receiver. The hook rules treat those like the function
 * around them.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class RunsInOwnHookScope
