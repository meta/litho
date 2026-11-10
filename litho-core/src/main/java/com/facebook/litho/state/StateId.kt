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

package com.facebook.litho.state

import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.Mode
import com.facebook.litho.GlobalKey
import com.facebook.litho.HookGlobalKey
import com.facebook.litho.RenderScope

@DataClassGenerate(toString = Mode.KEEP)
data class StateId(
    val treeId: Int,
    val hookGlobalKey: HookGlobalKey,
) {
  internal var ownerName: String? = null

  val globalKey: GlobalKey
    get() = hookGlobalKey.globalKey

  @Suppress("USELESS_CAST")
  val isSpecState: Boolean
    get() = hookGlobalKey.hookKey is Int && (hookGlobalKey.hookKey as Int) == SPEC_STATE_INDEX

  companion object {
    const val SPEC_STATE_INDEX: Int = -1

    fun forSpec(treeId: Int, globalKey: GlobalKey): StateId =
        StateId(treeId, HookGlobalKey(globalKey, SPEC_STATE_INDEX, RenderScope.Resolve))
  }
}
