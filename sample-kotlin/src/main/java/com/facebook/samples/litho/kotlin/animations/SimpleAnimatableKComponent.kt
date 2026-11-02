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

package com.facebook.samples.litho.kotlin.animations

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.animation.FloatTypeConverter
import com.facebook.litho.animation.alpha
import com.facebook.litho.animation.scale
import com.facebook.litho.animation.useAnimatable
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.alignSelf
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.view.backgroundColor
import com.facebook.rendercore.colorRes
import com.facebook.rendercore.dp
import com.facebook.samples.litho.R
import com.facebook.samples.litho.common.animations.dynamicprops.SeekBar
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

// start_example
@OptIn(ExperimentalLithoApi::class)
class SimpleAnimatableKComponent : KComponent() {

  override fun ComponentScope.render(): Component? {
    val scale = useAnimatable(1f, FloatTypeConverter)
    val alpha = useAnimatable(1f, FloatTypeConverter)

    val square = Column(
        style =
            Style.width(100.dp)
                .height(100.dp)
                .backgroundColor(colorRes(R.color.primaryColor))
                .alignSelf(YogaAlign.CENTER)
                .scale(scale.value)
                .alpha(alpha.value),
    )

    return Column(justifyContent = YogaJustify.SPACE_BETWEEN, style = Style.padding(all = 20.dp)) {
      child(SeekBar(onProgressChanged = { alpha.jumpTo(it) }))
      child(square)
      child(SeekBar(onProgressChanged = { scale.jumpTo(it) }))
    }
  }
}
// end_example
