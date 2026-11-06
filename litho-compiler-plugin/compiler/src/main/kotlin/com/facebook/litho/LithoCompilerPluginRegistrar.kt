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

import com.facebook.kotlin.compilercompat.MessageCollectorAccessCompat as MessageCollectorAccess
import com.facebook.litho.ir.LithoIrGenerationExtension
import com.facebook.litho.k2.LithoFirExtensionRegistrar
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar.ExtensionStorage
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * The single entry point through which every [LithoCompilerPlugin] reaches the compiler.
 *
 * The plugin has no configuration: every compatible Kotlin target receives it, and each feature is
 * a no-op on a target that does not use it. Adding a feature means adding it to
 * [LithoCompilerPlugins.ALL]; this registrar and the build integration do not change.
 *
 * @see [LithoCompilerPluginCommandLineProcessor]
 */
@OptIn(ExperimentalCompilerApi::class, MessageCollectorAccess::class)
class LithoCompilerPluginRegistrar(
    private val plugins: List<LithoCompilerPlugin> = LithoCompilerPlugins.ALL,
) : CompilerPluginRegistrar() {

  override val pluginId: String = LITHO_COMPILER_PLUGIN_ID

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val messages = configuration.messageCollector
    IrGenerationExtension.registerExtension(LithoIrGenerationExtension(messages, plugins))

    val checkers = plugins.mapNotNull { it.firCheckers }
    if (checkers.isNotEmpty()) {
      FirExtensionRegistrarAdapter.registerExtension(LithoFirExtensionRegistrar(checkers))
    }
  }

  override val supportsK2: Boolean
    get() = true
}
