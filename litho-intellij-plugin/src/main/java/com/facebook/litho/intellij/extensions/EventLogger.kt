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

package com.facebook.litho.intellij.extensions

/**
 * Extension point for other plugins to provide own mechanism of logging given events.
 *
 * @see "plugin.xml"
 */
fun interface EventLogger {

  /**
   * Logs given event.
   *
   * @param event given event name
   */
  fun log(event: String?) {
    log(event, HashMap())
  }

  fun log(event: String?, metadata: Map<String, String>)

  companion object {
    const val PLUGIN_ID: String = "com.facebook.litho.intellij"

    // Metadata keys
    const val KEY_PLUGIN_VERSION: String = "version"
    const val KEY_CLASS: String = "class"
    const val KEY_FILE: String = "file"
    const val KEY_RED_SYMBOLS: String = "red_symbols"
    const val KEY_RED_SYMBOLS_ALL: String = "all_red_symbols"
    const val KEY_RED_SYMBOLS_RESOLVED: String = "resolved_red_symbols"
    const val KEY_RESULT: String = "result"
    const val KEY_TARGET: String = "target"
    const val KEY_TIME_BIND_RED_SYMBOLS: String = "time_bind"
    const val KEY_TIME_COLLECT_RED_SYMBOLS: String = "time_collect"
    const val KEY_TIME_RESOLVE_RED_SYMBOLS: String = "time_resolve"
    const val KEY_TYPE: String = "type"

    // Metadata values. Naming VALUE_EVENTTYPE_KEYNAME_X
    const val VALUE_COMPLETION_TARGET_CALL: String = "call"
    const val VALUE_COMPLETION_TARGET_METHOD: String = "method"
    const val VALUE_COMPLETION_TARGET_PARAMETER: String = "parameter"
    const val VALUE_COMPLETION_TARGET_ARGUMENT: String = "argument"
    const val VALUE_NAVIGATION_CLASS_COMPONENT: String = "component"
    const val VALUE_NAVIGATION_CLASS_SECTION: String = "section"
    const val VALUE_NAVIGATION_TARGET_CLASS: String = "class"
    const val VALUE_NAVIGATION_TARGET_METHOD: String = "method"
    const val VALUE_NAVIGATION_TARGET_PARAMETER: String = "parameter"
    const val VALUE_NAVIGATION_TYPE_FIND_USAGES: String = "find_usages"
    const val VALUE_NAVIGATION_TYPE_GOTO: String = "goto"

    // Event types
    const val EVENT_ANNOTATOR: String = "warning"
    const val EVENT_COMPLETION: String = "completion"
    const val EVENT_FIX_EVENT_HANDLER: String = "fix.event_handler"
    const val EVENT_GOTO_GENERATED: String = "goto_generated"
    const val EVENT_NAVIGATION: String = "navigation"
    const val EVENT_NEW_TEMPLATE: String = "file_template"
    const val EVENT_ON_EVENT_GENERATION: String = "event.generation"
    const val EVENT_RED_SYMBOLS: String = "resolve_redsymbols"
    const val EVENT_SETTINGS: String = "settings.update"
    const val EVENT_TOOLWINDOW: String = "toolwindow"
    const val EVENT_GENERATE_COMPONENT: String = "generate_component"
    const val EVENT_ERROR: String = "error"
  }
}
