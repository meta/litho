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

import com.facebook.kotlin.compilercompat.FirRegularClassCheckerCompat
import com.facebook.kotlin.compilercompat.FirSimpleFunctionCheckerCompat
import com.facebook.litho.common.LithoNames
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.arrayElementType
import org.jetbrains.kotlin.fir.types.isNullableAny
import org.jetbrains.kotlin.fir.types.isSomeFunctionType

/**
 * Checks that an `@AutoDeps` declaration is shaped so the IR transform can act on it: it needs an
 * omittable `@Deps` parameter to write the inferred array into, and at least one `@DepsFrom` lambda
 * to read the dependencies from.
 *
 * The transform stays silent on a malformed declaration -- these errors are the only report.
 */
class AutoDepsFunctionChecker : FirSimpleFunctionCheckerCompat(::checkAutoDepsFunction)

/** The same checks for `@AutoDeps` classes, whose parameters live on the primary constructor. */
class AutoDepsClassChecker : FirRegularClassCheckerCompat(::checkAutoDepsClass)

private fun checkAutoDepsFunction(
    declaration: FirNamedFunction,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
  val parameters = declaration.symbol.valueParameterSymbols
  if (!declaration.hasAnnotation(LithoNames.AutoDeps, context.session)) {
    reportOrphanedParticipants(parameters, context, reporter)
    return
  }
  checkParameters(parameters, declaration.source, context, reporter)
}

@OptIn(DirectDeclarationsAccess::class)
private fun checkAutoDepsClass(
    declaration: FirRegularClass,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
  // Every constructor of the class is a call site the transform will look at, so every constructor
  // has to be well formed -- not just the primary one, which a class may not even have.
  val constructors = declaration.symbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>()
  if (!declaration.hasAnnotation(LithoNames.AutoDeps, context.session)) {
    constructors.forEach { reportOrphanedParticipants(it.valueParameterSymbols, context, reporter) }
    return
  }
  // An interface has no constructor to check, and no call site for the transform to rewrite.
  if (constructors.isEmpty()) {
    checkParameters(emptyList(), declaration.source, context, reporter)
    return
  }
  constructors.forEach { constructor ->
    checkParameters(
        constructor.valueParameterSymbols,
        constructor.source ?: declaration.source,
        context,
        reporter,
    )
  }
}

/**
 * `@Deps` and `@DepsFrom` mean nothing without `@AutoDeps`: the transform gates on it, so the
 * parameters are ignored and the call site takes the `@Deps` default -- an empty array, silently.
 * The annotation's own contract says the three appear together, so say so rather than infer
 * nothing.
 */
private fun reportOrphanedParticipants(
    parameters: List<FirValueParameterSymbol>,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
  parameters
      .filter {
        it.hasAnnotation(LithoNames.Deps, context.session) ||
            it.hasAnnotation(LithoNames.DepsFrom, context.session)
      }
      .forEach { reporter.reportOn(it.source, AutoDepsErrors.AUTODEPS_MISSING_AUTO_DEPS, context) }
}

private fun checkParameters(
    parameters: List<FirValueParameterSymbol>,
    declarationSource: KtSourceElement?,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
  val deps = parameters.filter { it.hasAnnotation(LithoNames.Deps, context.session) }
  val depsFrom = parameters.filter { it.hasAnnotation(LithoNames.DepsFrom, context.session) }

  if (deps.isEmpty()) {
    reporter.reportOn(declarationSource, AutoDepsErrors.AUTODEPS_MISSING_DEPS_PARAM, context)
  }
  // The transform writes into the first @Deps parameter, so a second one would never be filled in.
  deps.drop(1).forEach {
    reporter.reportOn(it.source, AutoDepsErrors.AUTODEPS_DUPLICATE_DEPS_PARAM, context)
  }
  if (depsFrom.isEmpty()) {
    reporter.reportOn(declarationSource, AutoDepsErrors.AUTODEPS_MISSING_DEPS_FROM, context)
  }

  for (parameter in deps) {
    // Either shape works, because the transform writes an IrVararg into one and an arrayOf() call
    // into the other. What both need is for the argument to be *absent* at a call site, which is
    // what tells the transform to infer -- free for a vararg, a default value otherwise.
    if (parameter.resolvedReturnType.arrayElementType()?.isNullableAny != true) {
      reporter.reportOn(parameter.source, AutoDepsErrors.AUTODEPS_INVALID_DEPS_TYPE, context)
    } else if (!parameter.isVararg && !parameter.hasDefaultValue) {
      reporter.reportOn(parameter.source, AutoDepsErrors.AUTODEPS_DEPS_NOT_OMITTABLE, context)
    }
  }

  for (parameter in depsFrom) {
    val type = parameter.resolvedReturnType
    val hasClosure =
        type.isSomeFunctionType(context.session) ||
            type.toRegularClassSymbol(context.session)?.resolvedStatus?.isFun == true
    if (!hasClosure) {
      reporter.reportOn(parameter.source, AutoDepsErrors.AUTODEPS_INVALID_DEPS_FROM_TYPE, context)
    } else if (parameter.hasDefaultValue) {
      reporter.reportOn(parameter.source, AutoDepsErrors.AUTODEPS_DEPS_FROM_HAS_DEFAULT, context)
    }
  }
}
