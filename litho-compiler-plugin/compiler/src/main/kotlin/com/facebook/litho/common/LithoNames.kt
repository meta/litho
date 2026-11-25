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

package com.facebook.litho.common

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Namespace to encapsulate the identity of different Litho references (classes, methods,
 * properties, etc) that are used by the compiler plugin.
 *
 * **Note**: The convention used in this class is such that the props are named exactly as the
 * references that they denote. In the case of class IDs and other FQCNs, the simple name should be
 * used.
 */
object LithoNames {
  val Component: ClassId = ClassId.fromString("com/facebook/litho/Component")
  val KComponent: ClassId = ClassId.fromString("com/facebook/litho/KComponent")
  val ComponentScope: ClassId = ClassId.fromString("com/facebook/litho/ComponentScope")
  val PrimitiveComponentScope: ClassId =
      ClassId.fromString("com/facebook/litho/PrimitiveComponentScope")

  val Unconditional: ClassId = ClassId.fromString("com/facebook/litho/annotations/Unconditional")
  val Hook: ClassId = ClassId.fromString("com/facebook/litho/annotations/Hook")
  val RunsInOwnHookScope: ClassId =
      ClassId.fromString("com/facebook/litho/annotations/RunsInOwnHookScope")

  // Auto Deps annotations live in RenderCore, not litho-core: rendercore-primitives carries two of
  // the target APIs and does not depend on litho-core.
  val AutoDeps: ClassId = ClassId.fromString("com/facebook/rendercore/annotations/AutoDeps")
  val Deps: ClassId = ClassId.fromString("com/facebook/rendercore/annotations/Deps")
  val DepsFrom: ClassId = ClassId.fromString("com/facebook/rendercore/annotations/DepsFrom")

  val getProps: Name = Name.identifier("getProps")
  val EMPTY_ARRAY: Name = Name.identifier("EMPTY_ARRAY")
  val render: Name = Name.identifier("render")
  val enterHook: Name = Name.identifier("enterHook")
  val exitHook: Name = Name.identifier("exitHook")
}
