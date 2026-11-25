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

package com.facebook.litho.diagnostics

import com.facebook.litho.AbstractCompilerTest
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test

@OptIn(ExperimentalCompilerApi::class)
class LithoHookUsageCheckerTest : AbstractCompilerTest() {

  /**
   * `Hooks.misuse.kt` is also the regression test for what hook identity made legal. Its expected
   * output lists exactly one diagnostic — the hook called from a plain helper — while the file
   * still contains hooks in `if`, `when`, `for`, `while`, `forEach` and `try`/`catch`. If the
   * conditional checks deleted in D120344640 ever come back, those produce diagnostics that are not
   * in the expected output and this fails.
   */
  @Test
  fun hooks_misuse() {
    runTest("test-data/Hooks.misuse.kt")
  }

  @Test
  fun hooks_good_use() {
    runTest("test-data/Hooks.good.kt")
  }

  @Test
  fun hooks_in_top_level_receiver_lambda_require_named_owner() {
    assertOwnerlessHooksRejected(
        """
        val body: SCOPE.() -> Pair<Int, Int> = {
          val first = useState { 1 }
          val second = useState { 2 }
          first.value to second.value
        }
        """
            .trimIndent(),
    )
  }

  @Test
  fun hooks_in_class_field_receiver_lambda_require_named_owner() {
    assertOwnerlessHooksRejected(
        """
        class Holder {
          val body: SCOPE.() -> Pair<Int, Int> = {
            val first = useState { 1 }
            val second = useState { 2 }
            first.value to second.value
          }
        }
        """
            .trimIndent(),
    )
  }

  @Test
  fun hooks_in_plain_local_function_do_not_borrow_receiver_lambda_scope() {
    assertOwnerlessHooksRejected(
        """
        fun createBody(): SCOPE.() -> Unit = {
          fun plain() {
            useState { 1 }
            useState { 2 }
          }
        }
        """
            .trimIndent(),
    )
  }

  @Test
  fun hooks_in_generic_own_hook_scope_receiver_lambdas() {
    val result = compile(
        SourceFile.kotlin(
            "GenericReceiver.kt",
            """
            import com.facebook.litho.ComponentScope
            import com.facebook.litho.annotations.ExperimentalLithoApi
            import com.facebook.litho.annotations.RunsInOwnHookScope
            import com.facebook.litho.useState

            @OptIn(ExperimentalLithoApi::class)
            @RunsInOwnHookScope
            abstract class OwnScope(host: ComponentScope) : ComponentScope(host)

            // Stands in for an API that keeps the annotation's promise; the checker sees only call sites.
            fun <S : OwnScope> hosted(scope: S, content: S.() -> Unit) = scope.content()

            fun <S : OwnScope> helper(scope: S) {
              val body: S.() -> Unit = { useState { 1 }; useState { 2 } }
              hosted<S>(scope, body)
              hosted<S>(scope) { useState { 3 } }
              hosted(scope) { useState { 4 } }
            }

            fun <S, T> chainedHelper(scope: S) where S : T, T : OwnScope {
              val body: S.() -> Unit = { useState { 5 }; useState { 6 } }
              hosted(scope, body)
            }
            """
                .trimIndent(),
        ),
    )
    assertThat(result.exitCode)
        .describedAs(result.messages)
        .isEqualTo(KotlinCompilation.ExitCode.OK)
  }

  @Test
  fun hooks_in_plain_scope_receiver_lambdas_in_a_plain_helper_are_rejected() {
    val result = compile(
        SourceFile.kotlin(
            "PlainReceiver.kt",
            """
            import com.facebook.litho.ComponentScope
            import com.facebook.litho.useState

            fun <S : ComponentScope> generic(scope: S) = with(scope) { useState { 1 } }

            fun plainCounter(scope: ComponentScope, initial: Int) = with(scope) { useState { initial } }
            """
                .trimIndent(),
        ),
    )
    assertThat(result.exitCode)
        .describedAs(result.messages)
        .isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages.lines().filter { it.contains("Hooks can only be called") })
        .describedAs(result.messages)
        .hasSize(2)
  }

  @Test
  fun hooks_in_scope_functions_on_an_own_hook_scope_value_in_a_plain_helper_are_rejected() {
    assertHooksRejected(
        count = 3,
        """
        fun counter(scope: OwnScope, initial: Int) = with(scope) { useState { initial } }

        fun ran(scope: OwnScope) = scope.run { useState { 1 } }

        fun applied(scope: OwnScope) = scope.apply { useState { 2 } }
        """
            .trimIndent(),
    )
  }

  @Test
  fun hooks_on_another_receiver_inside_an_own_hook_scope_body_are_rejected() {
    assertHooksRejected(
        count = 2,
        """
        fun hosted(scope: OwnScope, content: OwnScope.() -> Unit) = scope.content()

        fun helper(outer: ComponentScope, own: OwnScope) {
          hosted(own) { with(outer) { useState { 0 } } }
          hosted(own) { outer.useState { 1 } }
        }
        """
            .trimIndent(),
    )
  }

  private fun assertHooksRejected(count: Int, body: String) {
    val result = compile(
        SourceFile.kotlin(
            "OwnScopeMisuse.kt",
            """
            import com.facebook.litho.ComponentScope
            import com.facebook.litho.annotations.ExperimentalLithoApi
            import com.facebook.litho.annotations.RunsInOwnHookScope
            import com.facebook.litho.useState

            @OptIn(ExperimentalLithoApi::class)
            @RunsInOwnHookScope
            abstract class OwnScope(host: ComponentScope) : ComponentScope(host)

            """
                .trimIndent() + "\n" + body,
        ),
    )
    assertThat(result.exitCode)
        .describedAs(result.messages)
        .isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
    assertThat(result.messages.lines().filter { it.contains("Hooks can only be called") })
        .describedAs(result.messages)
        .hasSize(count)
  }

  private fun assertOwnerlessHooksRejected(body: String) {
    for (scope in listOf("ComponentScope", "PrimitiveComponentScope", "OwnScope")) {
      val declarations =
          if (scope == "OwnScope") {
            """
            import com.facebook.litho.ComponentScope
            import com.facebook.litho.annotations.ExperimentalLithoApi
            import com.facebook.litho.annotations.RunsInOwnHookScope

            @OptIn(ExperimentalLithoApi::class)
            @RunsInOwnHookScope
            abstract class OwnScope(host: ComponentScope) : ComponentScope(host)
            """
                .trimIndent()
          } else {
            "import com.facebook.litho.$scope"
          }
      val result = compile(
          SourceFile.kotlin(
              "Ownerless.kt",
              declarations +
                  "\nimport com.facebook.litho.useState\n\n" +
                  body.replace("SCOPE", scope),
          ),
      )
      assertThat(result.exitCode)
          .describedAs(result.messages)
          .isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR)
      assertThat(result.messages.lines().filter { it.contains("Hooks can only be called") })
          .describedAs(result.messages)
          .hasSize(2)
    }
  }
}
