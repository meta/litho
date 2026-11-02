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

package com.meta.systrace

interface Systracer {

  fun beginSection(name: String)

  fun beginAsyncSection(name: String)

  fun beginAsyncSection(name: String, cookie: Int)

  fun beginSectionWithArgs(name: String): ArgsBuilder

  fun endSection()

  fun endAsyncSection(name: String)

  fun endAsyncSection(name: String, cookie: Int)

  fun isTracing(): Boolean

  /** Object that accumulates arguments for [beginSectionWithArgs]. */
  interface ArgsBuilder {

    /**
     * Write the full message to the Systrace buffer.
     *
     * You must call this to log the trace message.
     */
    fun flush()

    /**
     * Logs an argument whose value is any object. It will be stringified with
     * [java.lang.String.valueOf].
     */
    fun arg(key: String, value: Any): ArgsBuilder

    /**
     * Logs an argument whose value is an int. It will be stringified with
     * [java.lang.String.valueOf].
     */
    fun arg(key: String, value: Int): ArgsBuilder

    /**
     * Logs an argument whose value is a long. It will be stringified with
     * [java.lang.String.valueOf].
     */
    fun arg(key: String, value: Long): ArgsBuilder

    /**
     * Logs an argument whose value is a double. It will be stringified with
     * [java.lang.String.valueOf].
     */
    fun arg(key: String, value: Double): ArgsBuilder
  }
}

inline fun <T> Systracer.trace(name: () -> String, block: () -> T): T {
  return if (isTracing()) {
    beginSection(name())
    try {
      block()
    } finally {
      endSection()
    }
  } else {
    block()
  }
}
