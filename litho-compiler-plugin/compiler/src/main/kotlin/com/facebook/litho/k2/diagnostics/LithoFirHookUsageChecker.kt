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

import com.facebook.kotlin.compilercompat.FirFunctionCallCheckerCompat
import com.facebook.litho.common.LithoNames
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.utils.addToStdlib.lastIsInstanceOrNull

/**
 * Checks that a hook is called from `render()` or from another `@Hook` function.
 *
 * This is the one hook rule structural hook identity does not lift, and cannot. A hook's identity
 * is its call site nested under the identity of the enclosing hook scope, so a hook reached from a
 * plain helper has no scope to nest under: it mints a key with no parent at a static index, which
 * aliases sibling calls onto the same state, and throws outright when no scope is open. Pass the
 * hook's result into the helper, or annotate the helper `@Hook`.
 *
 * The conditional, early-return and naming checks that used to live here were removed in
 * D120344640: identity makes conditions, loops and early returns safe, which is the point of the
 * transform. The `@Unconditional` variants moved to the `KComponentUnconditionalMisuseDetector`
 * androidlint rule, which still enforces call order for `ComponentScope.diffOf`.
 *
 * See
 * [Litho Hook Usage](https://fblitho.com/docs/state-and-side-effects/hooks-intro/#rules-for-hooks)
 * for more details.
 */
class LithoFirHookUsageChecker : FirFunctionCallCheckerCompat() {
  override fun check(
      expression: FirFunctionCall,
      context: CheckerContext,
      reporter: DiagnosticReporter,
  ) {
    val symbol = expression.calleeReference.symbol ?: return
    if (!symbol.hasAnnotation(LithoNames.Hook, context.session)) return
    if (context.hasInvalidParent()) {
      reporter.reportOn(expression.source, LithoFirErrors.HOOK_CALLSITE_MISUSE, context)
    }
  }
}

/**
 * Whether the innermost enclosing named function cannot host a hook call.
 *
 * A call with no enclosing named function at all — a property initializer, say — is invalid: there
 * is no hook scope for it to nest under. Lambdas are deliberately transparent here, so the check
 * looks through them to the named function that contains them: a hook inside a lambda that runs
 * during render is legitimate, and one inside a deferred lambda is the separate
 * `KComponentHookInDeferredLambda` androidlint rule, which needs to know how the lambda is invoked
 * rather than only where it is written.
 */
private fun CheckerContext.hasInvalidParent(): Boolean {
  val container = containingElements.lastIsInstanceOrNull<FirNamedFunction>() ?: return true
  return !container.isRenderMethod(session) && !container.hasAnnotation(LithoNames.Hook, session)
}

private fun FirNamedFunction.isRenderMethod(session: FirSession): Boolean {
  val receiver = receiverParameter?.typeRef?.coneType
  return name == LithoNames.render && isDescendantOfComponentScope(receiver, session)
}

private fun isDescendantOfComponentScope(receiver: ConeKotlinType?, session: FirSession): Boolean {
  val queue = ArrayDeque(listOfNotNull(receiver))
  val visited = mutableSetOf<ClassId>()
  while (queue.isNotEmpty()) {
    val type = queue.removeFirst()
    val classId = type.classId ?: continue
    if (classId == LithoNames.ComponentScope || classId == LithoNames.PrimitiveComponentScope) {
      return true
    }
    if (!visited.add(classId)) continue
    queue.addAll(type.toRegularClassSymbol(session)?.resolvedSuperTypes.orEmpty())
  }
  return false
}
