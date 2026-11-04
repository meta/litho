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

package com.facebook.litho.widget

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.TreePropProvider
import com.facebook.litho.legacyTreePropOf
import com.facebook.litho.useState
import com.facebook.litho.widget.treeprops.SimpleTreeProp

class ComponentWithTreePropParent(private val propA: SimpleTreeProp) : KComponent() {

  override fun ComponentScope.render(): Component {

    @Suppress("UNUSED_VARIABLE") val isCheckedState = useState<Boolean> { false }

    return TreePropProvider(legacyTreePropOf<SimpleTreeProp>() to propA) {
      MountSpecWithTreeProp(viewWidth = 100, viewHeight = 100)
    }
  }
}
