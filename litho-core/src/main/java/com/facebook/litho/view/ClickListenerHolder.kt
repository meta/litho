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

package com.facebook.litho.view

import androidx.annotation.IdRes
import com.facebook.litho.ClickEvent

/**
 * An additional click listener registered alongside a primary click handler via
 * `Style.additionalOnClick(key, …)`. [key] is an `@IdRes` that namespaces the registration so
 * different call-sites can layer independent listeners on the same view without colliding.
 *
 * Plain class (not `data class`) on purpose: [onClick] is a lambda and lambdas have no meaningful
 * value equality, so an auto-generated `equals`/`hashCode` would compare by reference identity
 * while pretending to be a value type — misleading. Reference equality from the default `Any`
 * `equals`/`hashCode` is honest.
 */
@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
internal class ClickListenerHolder(@IdRes val key: Int, val onClick: (ClickEvent) -> Unit)
