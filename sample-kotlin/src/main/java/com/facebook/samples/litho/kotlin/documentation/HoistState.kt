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

package com.facebook.samples.litho.kotlin.documentation

import android.view.inputmethod.EditorInfo
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.Handle
import com.facebook.litho.KComponent
import com.facebook.litho.eventHandlerWithReturn
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useEffect
import com.facebook.litho.useRef
import com.facebook.litho.useState
import com.facebook.litho.widget.TextInput

// start_example
class TemperatureConvertor : KComponent() {
  override fun ComponentScope.render(): Component {
    // Singe hoisted state
    val temperatureCelsius = useState { 0.0 }

    return Column {
      child(
          TemperatureInput(
              value = temperatureCelsius.value,
              scale = TemperatureScale.Celsius,
              onReturn = { newTemp -> temperatureCelsius.update(newTemp) },
          ),
      )
      child(
          TemperatureInput(
              value = toFahrenheit(temperatureCelsius.value),
              scale = TemperatureScale.Fahrenheit,
              onReturn = { newTemp -> temperatureCelsius.update(toCelsius(newTemp)) },
          ),
      )
    }
  }
}

// end_example

fun toFahrenheit(degreesCelsius: Double): Double = degreesCelsius * 9 / 5 + 32

fun toCelsius(degreesFahrenheit: Double): Double = (degreesFahrenheit - 32) * 5 / 9

enum class TemperatureScale(val unit: Char) {
  Celsius('C'),
  Fahrenheit('F');

  val degreeUnit: String = "°$unit"
}

/** User input for a temperature, including the unit */
class TemperatureInput(
    val value: Double,
    val scale: TemperatureScale,
    private val onReturn: ((Double) -> Unit)? = null,
) : KComponent() {
  override fun ComponentScope.render(): Component = Row {
    child(DoubleInput(value, onReturn))
    child(Text(scale.degreeUnit))
  }
}

/** User input for a Double */
class DoubleInput(val double: Double, private val onReturn: ((Double) -> Unit)? = null) :
    KComponent() {
  override fun ComponentScope.render(): Component? {
    val handle = useRef { Handle() }.value

    useEffect(double) {
      TextInput.setText(context, handle, "$double")
      null
    }

    return TextInput.create(context)
        .handle(handle)
        .inputType(
            EditorInfo.TYPE_CLASS_NUMBER or
                EditorInfo.TYPE_NUMBER_FLAG_SIGNED or
                EditorInfo.TYPE_NUMBER_FLAG_DECIMAL,
        )
        .editorActionEventHandler(
            eventHandlerWithReturn { event ->
              event.view.text
                  .toString()
                  .toDoubleOrNull()
                  ?.takeIf { it != double }
                  ?.let { onReturn?.invoke(it) }
              true
            },
        )
        .flexGrow(1f)
        .build()
  }
}
