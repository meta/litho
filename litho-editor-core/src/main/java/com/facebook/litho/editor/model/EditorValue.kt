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

package com.facebook.litho.editor.model

import java.util.ArrayDeque
import java.util.Deque
import java.util.concurrent.atomic.AtomicReference

/** A sealed class for values supported by the editor */
abstract class EditorValue {
  // Members
  /** Finds the real value of this EditorValue */
  abstract fun <R> `when`(visitor: EditorVisitor<R>): R

  interface EditorVisitor<R> {
    fun isShape(`object`: EditorShape): R

    fun isArray(array: EditorArray): R

    fun isPick(pick: EditorPick): R

    fun isNumber(number: EditorNumber): R

    fun isColor(number: EditorColor): R

    fun isString(string: EditorString): R

    fun isBool(bool: EditorBool): R
  }

  abstract class DefaultEditorVisitor : EditorVisitor<Void?> {
    override fun isShape(`object`: EditorShape): Void? {
      return null
    }

    override fun isArray(array: EditorArray): Void? {
      return null
    }

    override fun isPick(pick: EditorPick): Void? {
      return null
    }

    override fun isNumber(number: EditorNumber): Void? {
      return null
    }

    override fun isColor(number: EditorColor): Void? {
      return null
    }

    override fun isString(string: EditorString): Void? {
      return null
    }

    override fun isBool(bool: EditorBool): Void? {
      return null
    }
  }

  /** Depth-first traversal of the tree nodes. Shortcircuits on true. */
  fun whenPrimitive(visitor: EditorPrimitiveVisitor) {
    whenPrimitive(visitor, ArrayDeque())
  }

  interface EditorPrimitiveVisitor {
    fun isNumber(path: Array<String>, number: EditorNumber): Boolean

    fun isColor(path: Array<String>, color: EditorColor): Boolean

    fun isString(path: Array<String>, string: EditorString): Boolean

    fun isBool(path: Array<String>, bool: EditorBool): Boolean

    fun isPick(path: Array<String>, pick: EditorPick): Boolean
  }

  abstract class DefaultEditorPrimitiveVisitor : EditorPrimitiveVisitor {
    override fun isNumber(path: Array<String>, number: EditorNumber): Boolean {
      return false
    }

    override fun isColor(path: Array<String>, color: EditorColor): Boolean {
      return false
    }

    override fun isString(path: Array<String>, string: EditorString): Boolean {
      return false
    }

    override fun isBool(path: Array<String>, bool: EditorBool): Boolean {
      return false
    }

    override fun isPick(path: Array<String>, pick: EditorPick): Boolean {
      return false
    }
  }

  private fun whenPrimitive(visitor: EditorPrimitiveVisitor, path: Deque<String>): Boolean {
    return `when`(
        object : EditorVisitor<Boolean> {
          override fun isShape(`object`: EditorShape): Boolean {
            for ((key, value1) in `object`.value) {
              path.add(key)
              if (value1.whenPrimitive(visitor, path)) {
                return true
              }
              path.removeLast()
            }
            return false
          }

          override fun isArray(array: EditorArray): Boolean {
            val value = array.value
            for (i in value.indices) {
              val entry = value[i]
              path.add(i.toString())
              if (entry.whenPrimitive(visitor, path)) {
                return true
              }
              path.removeLast()
            }
            return false
          }

          override fun isPick(pick: EditorPick): Boolean {
            return visitor.isPick(path.toTypedArray(), pick)
          }

          override fun isNumber(number: EditorNumber): Boolean {
            return visitor.isNumber(path.toTypedArray(), number)
          }

          override fun isColor(number: EditorColor): Boolean {
            return visitor.isColor(path.toTypedArray(), number)
          }

          override fun isString(string: EditorString): Boolean {
            return visitor.isString(path.toTypedArray(), string)
          }

          override fun isBool(bool: EditorBool): Boolean {
            return visitor.isBool(path.toTypedArray(), bool)
          }
        },
    )
  }

  /** Traverses the tree enriching an initial value */
  fun <R> aggregate(init: R, aggregator: EditorAggregator<R>): R {
    val ref = AtomicReference(init)
    `when`<Boolean>(
        object : EditorVisitor<Boolean> {
          override fun isShape(`object`: EditorShape): Boolean {
            ref.set(aggregator.addShape(ref.get(), `object`))
            return false
          }

          override fun isArray(array: EditorArray): Boolean {
            ref.set(aggregator.addArray(ref.get(), array))
            return false
          }

          override fun isPick(pick: EditorPick): Boolean {
            ref.set(aggregator.addString(ref.get(), EditorString(pick.selected)))
            return false
          }

          override fun isNumber(number: EditorNumber): Boolean {
            ref.set(aggregator.addNumber(ref.get(), number))
            return false
          }

          override fun isColor(number: EditorColor): Boolean {
            ref.set(aggregator.addColor(ref.get(), number))
            return false
          }

          override fun isString(string: EditorString): Boolean {
            ref.set(aggregator.addString(ref.get(), string))
            return false
          }

          override fun isBool(bool: EditorBool): Boolean {
            ref.set(aggregator.addBool(ref.get(), bool))
            return false
          }
        },
    )

    return ref.get()
  }

  interface EditorAggregator<R> {
    fun addShape(aggregator: R, `object`: EditorShape?): R

    fun addArray(aggregator: R, array: EditorArray?): R

    fun addNumber(aggregator: R, number: EditorNumber?): R

    fun addColor(aggregator: R, color: EditorColor?): R

    fun addString(aggregator: R, string: EditorString?): R

    fun addBool(aggregator: R, bool: EditorBool?): R
  }

  fun getValue(): Any {
    return when (this) {
      is EditorNumber -> this.value
      is EditorString -> this.value
      is EditorBool -> this.value
      is EditorShape -> this.value
      is EditorArray -> this.value
      is EditorPick -> this.selected
      else -> throw IllegalStateException("No Value")
    }
  }

  companion object {
    // Constructors
    @JvmStatic
    fun number(n: Number): EditorValue {
      return EditorNumber(n)
    }

    @JvmStatic
    fun color(n: Number): EditorValue {
      return EditorColor(n)
    }

    @JvmStatic
    fun string(s: String): EditorValue {
      return EditorString(s)
    }

    @JvmStatic
    fun bool(b: Boolean): EditorValue {
      return EditorBool(b)
    }

    @JvmStatic
    fun shape(shape: Map<String, EditorValue>): EditorValue {
      return EditorShape(shape)
    }

    @JvmStatic
    fun array(array: List<EditorValue>): EditorValue {
      return EditorArray(array)
    }

    @JvmStatic
    fun array(vararg values: EditorValue): EditorValue {
      return EditorArray(*values)
    }

    @JvmStatic
    fun pick(selected: String, otherValues: Set<String>): EditorValue {
      return EditorPick(otherValues, selected)
    }
  }
}
