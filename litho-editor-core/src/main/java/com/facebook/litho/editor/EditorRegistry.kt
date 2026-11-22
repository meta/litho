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

import android.graphics.drawable.ColorDrawable
import android.util.Pair
import android.util.Size
import com.facebook.litho.CachedValue
import com.facebook.litho.Style
import com.facebook.litho.drawable.ComparableColorDrawable
import com.facebook.litho.editor.EditorRegistry.TransientField
import com.facebook.litho.editor.Reflection.getValueUNSAFE
import com.facebook.litho.editor.instances.AtomicBooleanEditorInstance
import com.facebook.litho.editor.instances.AtomicIntegerEditorInstance
import com.facebook.litho.editor.instances.AtomicReferenceEditorInstance
import com.facebook.litho.editor.instances.BoolEditorInstance
import com.facebook.litho.editor.instances.CachedValueEditorInstance
import com.facebook.litho.editor.instances.ColorDrawableEditorInstance
import com.facebook.litho.editor.instances.ComparableColorDrawableEditorInstance
import com.facebook.litho.editor.instances.GenericEditorInstance
import com.facebook.litho.editor.instances.ListEditorInstance
import com.facebook.litho.editor.instances.MapEditorInstance
import com.facebook.litho.editor.instances.NumberEditorInstance
import com.facebook.litho.editor.instances.ObjectEditorInstance
import com.facebook.litho.editor.instances.StringEditorInstance
import com.facebook.litho.editor.instances.StyleEditorInstance
import com.facebook.litho.editor.instances.UtilSizeEditorInstance
import com.facebook.litho.editor.model.EditorString
import com.facebook.litho.editor.model.EditorValue
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A repository of known Editor instances. As Editor is not aware of the Class it is defined for,
 * this registry is meant to enforce that relationship.
 *
 * When it is first loaded it will register editors for basic Java types
 *
 * @see com.facebook.litho.editor.Editor
 */
object EditorRegistry {
  private val EDITORS: MutableMap<Class<*>, Editor> = HashMap()
  private var USE_OBJECT_EDITOR = false

  private fun getEditor(c: Class<*>): Editor? {
    for (key in EDITORS.keys) {
      if (key.isAssignableFrom(c)) {
        return EDITORS[key]
      }
    }

    if (USE_OBJECT_EDITOR) {
      return ObjectEditorInstance.instance
    }

    return null
  }

  fun enableObjectEditor() {
    USE_OBJECT_EDITOR = true
  }

  fun disableObjectEditor() {
    USE_OBJECT_EDITOR = false
  }

  @JvmStatic
  fun registerEditor(c: Class<*>?, e: Editor) {
    EDITORS[c!!] = e
  }

  @JvmStatic
  fun registerEditors(e: Map<Class<*>, Editor>) {
    EDITORS.putAll(e)
  }

  /**
   * Reads an EditorValue for a field if there is an Editor defined for the Class parameter. Returns
   * null otherwise.
   */
  @JvmStatic
  fun read(c: Class<*>, f: Field, node: Any?): EditorValue? {
    val editor = getEditor(c)
    if (editor == null) {
      if (node is TransientField<*>) {
        return EditorString(node.content.toString())
      }
      val value = getValueUNSAFE<Any?>(f, node)
      val fallback = value?.toString() ?: "null"

      return EditorString(fallback)
    }

    return editor.read(f, node)
  }

  /**
   * Writes an EditorValue into a field if there is an Editor defined for the Class parameter.
   * Returns null otherwise.
   */
  @JvmStatic
  fun write(c: Class<*>, f: Field?, node: Any?, values: EditorValue?): Boolean? {
    return getEditor(c)?.takeIf { f != null && values != null }?.write(f, node, values)
  }

  /**
   * This helper gives the EditorValue of a value that is not a field of a class. If the value is a
   * field, use [.read] instead.
   *
   * @param <T> type of the value
   * @param c runtime Class of the value
   * @param value data to update
   * @return the EditorValue representation </T>
   */
  @JvmStatic
  fun <T> readValueThatIsNotAField(c: Class<T>, value: T): EditorValue? {
    return read(c, TransientField.CONTENT_FIELD, TransientField(value))
  }

  /**
   * This helper writes an EditorValue to a value that is not a field of a class. If the value is a
   * field, use [.write] instead.
   *
   * @param <T> type of the value
   * @param c runtime Class of the value
   * @param value data to update
   * @param values EditorValue used to update the value
   * @return if the field has been updated correctly and the value after passing through the editor
   *   </T>
   */
  @JvmStatic
  fun <T> writeValueThatIsNotAField(c: Class<T>, value: T, values: EditorValue?): WrittenValue<T> {
    val wrapper = TransientField(value)
    val result = write(c, TransientField.CONTENT_FIELD, wrapper, values)
    return object : WrittenValue<T> {
      override fun hasUpdated(): Boolean? {
        return result
      }

      override fun value(): T {
        return wrapper.content
      }
    }
  }

  init {
    registerEditor(
        Int::class.javaPrimitiveType,
        NumberEditorInstance(checkNotNull(Int::class.javaPrimitiveType)),
    )
    registerEditor(
        Float::class.javaPrimitiveType,
        NumberEditorInstance(checkNotNull(Float::class.javaPrimitiveType)),
    )
    registerEditor(
        Double::class.javaPrimitiveType,
        NumberEditorInstance(checkNotNull(Double::class.javaPrimitiveType)),
    )
    registerEditor(
        Long::class.javaPrimitiveType,
        NumberEditorInstance(checkNotNull(Long::class.javaPrimitiveType)),
    )
    registerEditor(
        Short::class.javaPrimitiveType,
        NumberEditorInstance(checkNotNull(Short::class.javaPrimitiveType)),
    )
    registerEditor(
        Byte::class.javaPrimitiveType,
        NumberEditorInstance(checkNotNull(Byte::class.javaPrimitiveType)),
    )
    registerEditor(Int::class.java, NumberEditorInstance(Int::class.java))
    registerEditor(Float::class.java, NumberEditorInstance(Float::class.java))
    registerEditor(Double::class.java, NumberEditorInstance(Double::class.java))
    registerEditor(Long::class.java, NumberEditorInstance(Long::class.java))
    registerEditor(Short::class.java, NumberEditorInstance(Short::class.java))
    registerEditor(Byte::class.java, NumberEditorInstance(Byte::class.java))

    registerEditor(CharSequence::class.java, StringEditorInstance())
    registerEditor(Pair::class.java, GenericEditorInstance())

    val boolEditor = BoolEditorInstance()
    registerEditor(Boolean::class.java, boolEditor)
    registerEditor(Boolean::class.javaPrimitiveType, boolEditor)

    registerEditor(AtomicReference::class.java, AtomicReferenceEditorInstance())

    registerEditor(AtomicBoolean::class.java, AtomicBooleanEditorInstance())
    registerEditor(AtomicInteger::class.java, AtomicIntegerEditorInstance())

    registerEditor(MutableList::class.java, ListEditorInstance())
    registerEditor(MutableMap::class.java, MapEditorInstance.instance)

    registerEditor(Style::class.java, StyleEditorInstance.instance)

    registerEditor(Size::class.java, UtilSizeEditorInstance())

    registerEditor(ColorDrawable::class.java, ColorDrawableEditorInstance())
    registerEditor(ComparableColorDrawable::class.java, ComparableColorDrawableEditorInstance())
    registerEditor(CachedValue::class.java, CachedValueEditorInstance())
  }

  interface WrittenValue<T> {
    fun hasUpdated(): Boolean?

    fun value(): T
  }

  /**
   * This class exists for the cases where you have to update a value that is **not** a field. One
   * example are elements inside a Collection
   *
   * Note that updating the whole reference immutably wouldn't affect the original value, just the
   * value of this transient class.
   *
   * @param <T> </T>
   */
  private class TransientField<T>(val content: T) {
    companion object {
      val CONTENT_FIELD: Field = TransientField::class.java.getDeclaredField("content")
    }
  }
}
