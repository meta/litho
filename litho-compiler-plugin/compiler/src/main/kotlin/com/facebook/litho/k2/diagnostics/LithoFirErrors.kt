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

package com.facebook.litho.k2.diagnostics

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory

/**
 * Diagnostics reported by [com.facebook.litho.k2.diagnostics.LithoFirHookUsageChecker].
 *
 * Only the call-site rule remains. The conditional, early-return and naming diagnostics were
 * removed in D120344640 because structural hook identity makes those patterns safe; the
 * `@Unconditional` variants moved to the `KComponentUnconditionalMisuseDetector` androidlint rule.
 */
object LithoFirErrors : KtDiagnosticsContainer() {
  val HOOK_CALLSITE_MISUSE: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = LithoFirErrorMessages
}

private object LithoFirErrorMessages : BaseDiagnosticRendererFactory() {
  override val MAP: KtDiagnosticFactoryToRendererMap by
      KtDiagnosticFactoryToRendererMap("Litho") { map ->
        map.put(
            LithoFirErrors.HOOK_CALLSITE_MISUSE,
            "Hooks can only be called from render() or from another @Hook function. Either " +
                "annotate this function @Hook, which makes its own call sites subject to the same " +
                "rule, or hoist the hook into the caller and pass the value in. " +
                "See: https://www.internalfb.com/intern/staticdocs/litho/docs/state-and-side-effects/hooks-intro#rules-for-hooks",
        )
      }
}
