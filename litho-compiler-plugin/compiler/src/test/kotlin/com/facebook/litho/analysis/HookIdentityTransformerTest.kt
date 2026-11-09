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

package com.facebook.litho.analysis

import com.facebook.litho.AbstractCompilerTest
import com.facebook.litho.processor.HookIdentityCallProcessor
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test

@OptIn(ExperimentalCompilerApi::class)
class HookIdentityTransformerTest : AbstractCompilerTest() {

  @Test
  fun call_site_indexes() {
    runTest("test-data/HookIdentity.call-site-indexes.kt")
  }

  @Test
  fun callSiteIds_areStableAndDistinctAcrossCompilationUnits() {
    val first = compileCallSiteIds("first")
    val firstAgain = compileCallSiteIds("first")
    val second = compileCallSiteIds("second")

    assertThat(first).containsExactlyElementsOf(firstAgain)
    assertThat(first).hasSize(1)
    assertThat(second).hasSize(1)
    assertThat(first.single()).isNotEqualTo(second.single())
  }

  @Test
  fun callSiteIds_areStableForTypeParameters() {
    val hooks =
        """
        @Hook fun <T> ComponentScope.useLatest(value: T): State<T> = run { useState { value } }
        @Hook fun <S : ComponentScope> S.useCounter(): State<Int> = run { useState { 0 } }
        """
    val first = compileCallSiteIds("first", hooks)
    val firstAgain = compileCallSiteIds("first", hooks)

    assertThat(first).hasSize(2)
    assertThat(first).containsExactlyElementsOf(firstAgain)
  }

  private fun compileCallSiteIds(
      packageName: String,
      hooks: String = "@Hook fun ComponentScope.useCallSite(): State<Int> = run { useState { 0 } }",
  ): List<Int> {
    val processor = HookIdentityCallProcessor()
    val result = compile(
        SourceFile.kotlin(
            "$packageName.kt",
            """
                package $packageName

                import com.facebook.litho.ComponentScope
                import com.facebook.litho.State
                import com.facebook.litho.annotations.Hook
                import com.facebook.litho.useState

                $hooks
                """
                .trimIndent(),
        ),
        processor = processor,
    )
    assertThat(result.exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
    return processor.callSiteIds
  }
}
