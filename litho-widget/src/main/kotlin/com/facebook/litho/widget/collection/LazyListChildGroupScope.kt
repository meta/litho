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

package com.facebook.litho.widget.collection

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.ComponentScope
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.RunsInOwnHookScope

/**
 * The receiver of a [LazyListChildrenScope.childGroup] body. It is a [ComponentScope], so hooks
 * called in the body belong to the group: the list keeps their state, and an update re-renders the
 * list without re-rendering the component that owns it.
 */
@OptIn(ExperimentalLithoApi::class)
@RunsInOwnHookScope
class LazyListChildGroupScope internal constructor(host: ComponentScope) :
    ComponentScope(host), LazyListChildrenScope {

  override val children: LazyCollectionChildren = LazyCollectionChildren()
}

/** A group declared with [LazyListChildrenScope.childGroup], after [position] rows. */
internal class ChildGroup(
    val id: Any,
    val content: LazyListChildGroupScope.() -> Unit,
    val position: Int,
)

/**
 * The id of a row declared inside a child group: the row's own id together with the id of the group
 * that declared it. Group ids are unique across the list, so the pair is unique even though a row
 * id only needs to be unique within its group.
 */
@DataClassGenerate internal data class ChildGroupRowId(val groupId: Any, val id: Any)

/** The key of a child group whose id an earlier group in the same list already uses. */
@DataClassGenerate internal data class DuplicateChildGroupId(val id: Any, val occurrence: Int)
