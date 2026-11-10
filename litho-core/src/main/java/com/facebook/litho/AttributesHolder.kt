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

package com.facebook.litho

import java.util.LinkedHashMap

/** Simple holder which allows to store and retrieve attributes. */
class AttributesHolder : AttributesAcceptor {

  private val _attributes: MutableMap<AttributeKey<*>, Any?> = LinkedHashMap()

  @Suppress("UNCHECKED_CAST")
  operator fun <T> get(key: AttributeKey<T>): T? = _attributes[key] as T?

  override fun <T> setDebugAttributeKey(attributeKey: AttributeKey<T>, value: T) {
    _attributes[attributeKey] = value
  }

  val attributes: Map<AttributeKey<*>, Any?>
    get() = _attributes
}
