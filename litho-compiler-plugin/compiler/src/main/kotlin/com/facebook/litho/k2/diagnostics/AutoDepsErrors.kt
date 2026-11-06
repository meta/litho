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
 * The declaration diagnostics Auto Deps reports. Each plugin owns its own container.
 *
 * A [KtDiagnosticsContainer] rather than a bare renderer factory: in Kotlin 2.3 the factory
 * builders (`error0` and friends) are contextual declarations that need a container in scope, and
 * the container is what registers the renderer factory with the compiler.
 */
object AutoDepsErrors : KtDiagnosticsContainer() {
  val AUTODEPS_MISSING_DEPS_PARAM: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)
  val AUTODEPS_DUPLICATE_DEPS_PARAM: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)
  val AUTODEPS_MISSING_DEPS_FROM: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)
  val AUTODEPS_INVALID_DEPS_TYPE: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)
  val AUTODEPS_DEPS_NOT_OMITTABLE: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)
  val AUTODEPS_INVALID_DEPS_FROM_TYPE: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)
  val AUTODEPS_DEPS_FROM_HAS_DEFAULT: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)
  val AUTODEPS_MISSING_AUTO_DEPS: KtDiagnosticFactory0 by error0<PsiElement>(NAME_IDENTIFIER)

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = AutoDepsErrorMessages
}

private object AutoDepsErrorMessages : BaseDiagnosticRendererFactory() {
  override val MAP: KtDiagnosticFactoryToRendererMap by
      KtDiagnosticFactoryToRendererMap("Litho") { map ->
        map.put(
            AutoDepsErrors.AUTODEPS_MISSING_DEPS_PARAM,
            "@AutoDeps declarations must have a parameter annotated @Deps to receive the inferred " +
                "dependency array.",
        )
        map.put(
            AutoDepsErrors.AUTODEPS_DUPLICATE_DEPS_PARAM,
            "@AutoDeps declarations must have exactly one @Deps parameter. The inferred array is " +
                "written into the first one, so this one would never be filled in.",
        )
        map.put(
            AutoDepsErrors.AUTODEPS_MISSING_DEPS_FROM,
            "@AutoDeps declarations must have at least one parameter annotated @DepsFrom, whose " +
                "captured values become the dependencies.",
        )
        map.put(
            AutoDepsErrors.AUTODEPS_INVALID_DEPS_TYPE,
            "A @Deps parameter must be declared 'vararg deps: Any?' or 'deps: Array<Any?>'. The " +
                "inferred values are Any?.",
        )
        map.put(
            AutoDepsErrors.AUTODEPS_DEPS_NOT_OMITTABLE,
            "A non-vararg @Deps parameter must have a default value. Omitting the argument at the " +
                "call site is what tells the plugin to infer, and a parameter that cannot be " +
                "omitted can never be inferred.",
        )
        map.put(
            AutoDepsErrors.AUTODEPS_INVALID_DEPS_FROM_TYPE,
            "A @DepsFrom parameter must be a function type or a fun interface -- anything else has " +
                "no closure to read the dependencies from.",
        )
        map.put(
            AutoDepsErrors.AUTODEPS_DEPS_FROM_HAS_DEFAULT,
            "A @DepsFrom parameter must not have a default value. A default lambda is not written " +
                "at the call site, so the plugin cannot read what it captures, and those values " +
                "would silently be missing from the dependency list.",
        )
        map.put(
            AutoDepsErrors.AUTODEPS_MISSING_AUTO_DEPS,
            "@Deps and @DepsFrom only take effect on a declaration annotated @AutoDeps. Without " +
                "it nothing is inferred and the call site silently takes this parameter's default.",
        )
      }
}
