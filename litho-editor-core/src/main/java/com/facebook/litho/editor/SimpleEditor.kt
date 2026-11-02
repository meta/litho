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

package com.facebook.litho.editor

import com.facebook.litho.editor.Reflection.getValueUNSAFE
import com.facebook.litho.editor.Reflection.setValueUNSAFE
import com.facebook.litho.editor.model.EditorArray
import com.facebook.litho.editor.model.EditorBool
import com.facebook.litho.editor.model.EditorColor
import com.facebook.litho.editor.model.EditorNumber
import com.facebook.litho.editor.model.EditorPick
import com.facebook.litho.editor.model.EditorShape
import com.facebook.litho.editor.model.EditorString
import com.facebook.litho.editor.model.EditorValue
import com.facebook.litho.editor.model.EditorValue.DefaultEditorVisitor
import com.facebook.litho.editor.model.EditorValue.EditorPrimitiveVisitor
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicReference

object SimpleEditor {
  // Constructors
  @JvmStatic
  fun <T> makeMutable(propertyEditor: MutablePropertyEditor<T>): Editor {
    return object : Editor {
      override fun read(f: Field, node: Any?): EditorValue {
        return makeValue<T>(f, node, propertyEditor)
      }

      override fun write(f: Field, node: Any?, values: EditorValue): Boolean {
        val value = getValueUNSAFE<T>(f, node) ?: return false
        values.`when`(writeMutable(propertyEditor, value))
        return true
      }
    }
  }

  @JvmStatic
  fun <T> makeImmutable(propertyEditor: ImmutablePropertyEditor<T?>): Editor {
    return object : Editor {
      override fun read(f: Field, node: Any?): EditorValue {
        return makeValue<T?>(f, node, propertyEditor)
      }

      override fun write(f: Field, node: Any?, values: EditorValue): Boolean {
        val value = getValueUNSAFE<T>(f, node)
        values.`when`(writeImmutable(f, node, propertyEditor, value))
        return true
      }
    }
  }

  // Read data
  private fun <T> makeValue(f: Field, node: Any?, propertyReader: PropertyReader<T>): EditorValue {
    val value = getValueUNSAFE<T>(f, node) ?: return EditorValue.shape(emptyMap())
    val properties = propertyReader.readProperties(value)
    val shape: MutableMap<String, EditorValue> = HashMap()
    for ((key, value1) in properties) {
      shape[key] = value1.value
    }
    return EditorValue.shape(shape)
  }

  // Write data
  private fun <T> writeMutable(
      propertyEditor: MutablePropertyEditor<T>,
      value: T,
  ): DefaultEditorVisitor {
    return object : DefaultEditorVisitor() {
      override fun isShape(`object`: EditorShape): Void? {
        val properties = propertyEditor.readProperties(value)
        val updates = `object`.value
        for ((key, value1) in updates) {
          val newValue = SimpleEditorValue.fromEditorValueOrNull(value1)
          val oldValue = properties[key]
          if (newValue != null && oldValue != null) {
            val updatedValue = maybePickFromString(oldValue, newValue)
            if (updatedValue.type == oldValue.type) {
              updatedValue.value.whenPrimitive(
                  object : EditorPrimitiveVisitor {
                    override fun isNumber(path: Array<String>, number: EditorNumber): Boolean {
                      propertyEditor.writeNumberProperty(value, key, number.value)
                      return true
                    }

                    override fun isColor(path: Array<String>, color: EditorColor): Boolean {
                      propertyEditor.writeNumberProperty(value, key, color.color)
                      return true
                    }

                    override fun isString(path: Array<String>, string: EditorString): Boolean {
                      propertyEditor.writeStringProperty(value, key, string.value)
                      return false
                    }

                    override fun isBool(path: Array<String>, bool: EditorBool): Boolean {
                      propertyEditor.writeBoolProperty(value, key, bool.value)
                      return false
                    }

                    override fun isPick(path: Array<String>, pick: EditorPick): Boolean {
                      propertyEditor.writePickProperty(value, key, pick.selected)
                      return false
                    }
                  },
              )
            }
          }
        }
        return null
      }
    }
  }

  private fun <T> writeImmutable(
      f: Field,
      node: Any?,
      propertyEditor: ImmutablePropertyEditor<T>,
      value: T,
  ): EditorValue.EditorVisitor<Void?> {
    return object : DefaultEditorVisitor() {
      override fun isShape(`object`: EditorShape): Void? {
        val properties = propertyEditor.readProperties(value)
        val stringProperties: MutableMap<String, String> = HashMap()
        val numberProperties: MutableMap<String, Number> = HashMap()
        val boolProperties: MutableMap<String, Boolean> = HashMap()
        val pickProperties: MutableMap<String, String> = HashMap()
        for ((key, value1) in properties) {
          value1.value.whenPrimitive(
              classifyValue(
                  key,
                  stringProperties,
                  numberProperties,
                  boolProperties,
                  pickProperties,
              ),
          )
        }
        val updates = `object`.value
        for ((propertyKey, value1) in updates) {
          val newValue = SimpleEditorValue.fromEditorValueOrNull(value1)
          val oldValue = properties[propertyKey]
          if (newValue != null && oldValue != null) {
            val updatedValue = maybePickFromString(oldValue, newValue)
            if (updatedValue.type == oldValue.type) {
              updatedValue.value.whenPrimitive(
                  classifyValue(
                      propertyKey,
                      stringProperties,
                      numberProperties,
                      boolProperties,
                      pickProperties,
                  ),
              )
            }
          }
        }
        val newValue =
            propertyEditor.writeProperties(
                value,
                stringProperties,
                numberProperties,
                boolProperties,
                pickProperties,
            )
        setValueUNSAFE(f, node, newValue)
        return null
      }
    }
  }

  // As Flipper doesn't have typed messages, selected Pick values are returned as String
  // and we have to apply the value to the old Pick
  private fun maybePickFromString(
      oldValue: SimpleEditorValue,
      newValue: SimpleEditorValue,
  ): SimpleEditorValue {
    if (oldValue.type != PRIMITIVE_TYPE_PICK || newValue.type != PRIMITIVE_TYPE_STRING) {
      return newValue
    }
    val ref = AtomicReference(newValue)
    oldValue.value.`when`(
        object : DefaultEditorVisitor() {
          override fun isPick(pick: EditorPick): Void? {
            return newValue.value.`when`(
                object : DefaultEditorVisitor() {
                  override fun isString(string: EditorString): Void? {
                    ref.set(SimpleEditorValue.pick(pick.values, string.value))
                    return null
                  }
                },
            )
          }
        },
    )
    return ref.get()
  }

  private fun classifyValue(
      key: String,
      stringProperties: MutableMap<String, String>,
      numberProperties: MutableMap<String, Number>,
      boolProperties: MutableMap<String, Boolean>,
      pickProperties: MutableMap<String, String>,
  ): EditorPrimitiveVisitor {
    return object : EditorPrimitiveVisitor {
      override fun isNumber(path: Array<String>, number: EditorNumber): Boolean {
        numberProperties[key] = number.value
        return false
      }

      override fun isColor(path: Array<String>, color: EditorColor): Boolean {
        numberProperties[key] = color.color
        return false
      }

      override fun isString(path: Array<String>, string: EditorString): Boolean {
        stringProperties[key] = string.value
        return false
      }

      override fun isBool(path: Array<String>, bool: EditorBool): Boolean {
        boolProperties[key] = bool.value
        return false
      }

      override fun isPick(path: Array<String>, pick: EditorPick): Boolean {
        pickProperties[key] = pick.selected
        return false
      }
    }
  }

  private const val PRIMITIVE_TYPE_NUMBER = 0
  private const val PRIMITIVE_TYPE_STRING = 1
  private const val PRIMITIVE_TYPE_BOOL = 2
  private const val PRIMITIVE_TYPE_PICK = 3

  // Types
  interface PropertyReader<T> {
    fun readProperties(value: T): Map<String, SimpleEditorValue>
  }

  interface MutablePropertyEditor<T> : PropertyReader<T> {
    fun writeStringProperty(value: T, property: String, newValue: String)

    fun writeNumberProperty(value: T, property: String, newValue: Number)

    fun writeBoolProperty(value: T, property: String, newValue: Boolean)

    fun writePickProperty(value: T, property: String, newValue: String)
  }

  abstract class DefaultMutablePropertyEditor<T> : MutablePropertyEditor<T> {
    override fun writeStringProperty(value: T, property: String, newValue: String) {}

    override fun writeNumberProperty(value: T, property: String, newValue: Number) {}

    override fun writeBoolProperty(value: T, property: String, newValue: Boolean) {}

    override fun writePickProperty(value: T, property: String, newValue: String) {}
  }

  interface ImmutablePropertyEditor<T> : PropertyReader<T> {
    fun writeProperties(
        value: T,
        newStringValues: Map<String, String>,
        newNumberValues: Map<String, Number>,
        newBoolValues: Map<String, Boolean>,
        newPickValues: Map<String, String>,
    ): T
  }

  /** A class that wraps either a number, string, bool or pick */
  class SimpleEditorValue private constructor(val value: EditorValue, val type: Int) {
    companion object {
      // Constructors
      @JvmStatic
      fun number(n: Number): SimpleEditorValue {
        return SimpleEditorValue(EditorNumber(n), PRIMITIVE_TYPE_NUMBER)
      }

      @JvmStatic
      fun color(n: Number): SimpleEditorValue {
        return SimpleEditorValue(EditorNumber(n), PRIMITIVE_TYPE_NUMBER)
      }

      @JvmStatic
      fun string(s: String): SimpleEditorValue {
        return SimpleEditorValue(EditorString(s), PRIMITIVE_TYPE_STRING)
      }

      @JvmStatic
      fun bool(b: Boolean): SimpleEditorValue {
        return SimpleEditorValue(EditorBool(b), PRIMITIVE_TYPE_BOOL)
      }

      @JvmStatic
      fun pick(otherValues: Set<String>, selected: String): SimpleEditorValue {
        return SimpleEditorValue(EditorPick(otherValues, selected), PRIMITIVE_TYPE_PICK)
      }

      @JvmStatic
      fun fromEditorValueOrNull(v: EditorValue): SimpleEditorValue? {
        return v.`when`(asPrimitive)
      }

      private val asPrimitive: EditorValue.EditorVisitor<SimpleEditorValue?> =
          object : EditorValue.EditorVisitor<SimpleEditorValue?> {
            override fun isShape(`object`: EditorShape): SimpleEditorValue? {
              return null
            }

            override fun isArray(array: EditorArray): SimpleEditorValue? {
              return null
            }

            override fun isPick(pick: EditorPick): SimpleEditorValue? {
              return pick(pick.values, pick.selected)
            }

            override fun isNumber(number: EditorNumber): SimpleEditorValue? {
              return number(number.value)
            }

            override fun isColor(number: EditorColor): SimpleEditorValue? {
              return color(number.color)
            }

            override fun isString(string: EditorString): SimpleEditorValue? {
              return string(string.value)
            }

            override fun isBool(bool: EditorBool): SimpleEditorValue? {
              return bool(bool.value)
            }
          }
    }
  }
}
