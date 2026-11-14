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

package com.facebook.rendercore.annotations

/**
 * Marks a function or class whose dependency list the Litho compiler plugin infers.
 *
 * A participating declaration also names two parameters: [Deps] receives the inferred array, and
 * [DepsFrom] supplies the lambda whose captured values become the dependencies.
 *
 * ```
 * @AutoDeps
 * fun <T> ComponentScope.useCached(@Deps vararg inputs: Any?, @DepsFrom calculator: () -> T): T
 * ```
 *
 * At the call site, omitting the deps is the default and the intended shape:
 * ```
 * useCached { "$name has $count messages" }  // rewritten to useCached(name, count) { … }
 * useCached(Unit) { expensive() }            // untouched -- computed once
 * useCached(name) { "$name: $count" }        // untouched -- explicit deps always win
 * ```
 *
 * The three annotations must appear together; the plugin reports an error otherwise.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class AutoDeps

/**
 * Marks the parameter of an [AutoDeps] declaration that receives the inferred dependency array.
 *
 * Declared either `vararg deps: Any?` or, when the vararg slot is needed for something else, `deps:
 * Array<Any?> = emptyArray()`. An absent argument is what tells the plugin to infer, so the
 * non-vararg form must have a default value. The values it infers are `Any?`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Deps

/**
 * Marks the parameter of an [AutoDeps] declaration whose captured values are the dependencies.
 *
 * More than one parameter may carry it, in which case the dependency set is the union of their
 * captures. The argument must be a lambda literal: a variable or a method reference has no visible
 * closure to read.
 *
 * @property keepsDefaultIfNotLiteral when the argument isn't a lambda literal and the call omits
 *   the [Deps] argument, the call keeps the [Deps] parameter's default instead of failing to
 *   compile. Set it only when the declaration can tell that default apart from any inferred array
 *   and compares the argument itself instead, as `Layout` compares its layout function with
 *   `equals`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class DepsFrom(val keepsDefaultIfNotLiteral: Boolean = false)
