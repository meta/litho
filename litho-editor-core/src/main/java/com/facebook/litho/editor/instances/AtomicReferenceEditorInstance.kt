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

package com.facebook.litho.editor.instances

import com.facebook.litho.editor.Editor
import com.facebook.litho.editor.EditorRegistry.readValueThatIsNotAField
import com.facebook.litho.editor.EditorRegistry.writeValueThatIsNotAField
import com.facebook.litho.editor.Reflection.getValueUNSAFE
import com.facebook.litho.editor.model.EditorValue
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicReference

class AtomicReferenceEditorInstance : Editor {
  @Suppress("UNCHECKED_CAST")
  override fun read(f: Field, node: Any?): EditorValue {
    val reference =
        getValueUNSAFE<AtomicReference<Any>>(f, node) ?: return EditorValue.string("null")
    val o = reference.get() ?: return EditorValue.string("null")
    val oClass: Class<*> = o.javaClass
    val editorValue = readValueThatIsNotAField(oClass as Class<Any>, o)
    return editorValue ?: EditorValue.string(oClass.toString())
  }

  override fun write(f: Field, node: Any?, values: EditorValue): Boolean {
    val reference = getValueUNSAFE<AtomicReference<Any>>(f, node) ?: return false
    val o = reference.get() ?: return true
    val newValue = writeValueThatIsNotAField(o.javaClass, o, values)
    val hasUpdated = newValue.hasUpdated()
    if (hasUpdated != null && hasUpdated) {
      reference.set(newValue.value())
    }
    return true
  }
}
