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

package com.facebook.samples.litho.documentation.props

import androidx.annotation.ColorInt
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.kotlin.widget.Text
import com.facebook.rendercore.Dimen

// start_example
class PropWithoutResourceTypeKComponent(
    private val name: CharSequence,
    private val size: Dimen,
    @param:ColorInt private val color: Int,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    return Text(text = "Hello $name!", textSize = size, textColor = color)
  }
}
// end_example
