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

import com.facebook.kotlin.compilercompat.typeParameterSymbolCompat
import com.facebook.litho.common.LithoNames
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.InlineStatus
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Checks that a hook is called from `render()`, from another `@Hook` function, or from a lambda
 * whose receiver is marked `@RunsInOwnHookScope` inside a named function.
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
class LithoFirHookUsageChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(expression: FirFunctionCall) {
    val symbol = expression.calleeReference.symbol ?: return
    if (!symbol.hasAnnotation(LithoNames.Hook, context.session)) return
    if (context.hasInvalidParent(expression)) {
      reporter.reportOn(expression.source, LithoFirErrors.HOOK_CALLSITE_MISUSE, context)
    }
  }
}

/**
 * Whether no enclosing function can host a hook call.
 *
 * A lambda whose receiver is marked `@RunsInOwnHookScope` hosts a hook called on that receiver
 * inside a named function, as in a LazyList `childGroup` body written inside a helper: the
 * framework runs that body under its own hook scope, so its hooks are keyed independently of how
 * the helper is called. It must not be inlined: `with`, `run` and `apply` run their block as part
 * of the code around it, whatever the receiver's type. A hook called on another receiver, such as
 * an outer scope captured by the body, gets no such scope. Any other lambda is transparent,
 * including one whose receiver is a plain `ComponentScope` (`with(scope)`, `run`, `apply`): a plain
 * helper is not wrapped in a hook identity, so its calls share the caller's call site and a
 * conditional call would shift later calls onto its state. The check looks through such lambdas to
 * the named function that contains them: a hook inside a lambda that runs during render is
 * legitimate, and one inside a deferred lambda is the separate `KComponentHookInDeferredLambda`
 * androidlint rule, which needs to know how the lambda is invoked rather than only where it is
 * written.
 *
 * A call with no enclosing named function at all — a property initializer, say — is invalid: there
 * is no hook scope for it to nest under.
 */
private fun CheckerContext.hasInvalidParent(call: FirFunctionCall): Boolean {
  val receiver = (call.extensionReceiver ?: call.dispatchReceiver) as? FirThisReceiverExpression
  val receiverSymbol = receiver?.calleeReference?.boundSymbol
  var hasScopeReceiverLambda = false
  for (element in containingElements.asReversed()) {
    if (element is FirAnonymousFunction) {
      if (
          receiverSymbol != null &&
              receiverSymbol == element.receiverParameter?.symbol &&
              element.inlineStatus != InlineStatus.Inline &&
              runsInOwnHookScope(element.receiverParameter?.typeRef?.coneType, session)
      ) {
        hasScopeReceiverLambda = true
      }
    } else if (element is FirNamedFunction) {
      return !hasScopeReceiverLambda &&
          !element.isRenderMethod(session) &&
          !element.hasAnnotation(LithoNames.Hook, session)
    }
  }
  return true
}

private fun FirNamedFunction.isRenderMethod(session: FirSession): Boolean {
  val receiver = receiverParameter?.typeRef?.coneType
  return name == LithoNames.render && isDescendantOfComponentScope(receiver, session)
}

private fun isDescendantOfComponentScope(receiver: ConeKotlinType?, session: FirSession): Boolean {
  val queue = ArrayDeque(listOfNotNull(receiver))
  val visited = mutableSetOf<ConeKotlinType>()
  while (queue.isNotEmpty()) {
    val type = queue.removeFirst()
    if (!visited.add(type)) continue
    if (type is ConeTypeParameterType) {
      queue.addAll(type.typeParameterSymbolCompat.resolvedBounds.map { it.coneType })
      continue
    }
    val classId = type.classId ?: continue
    if (classId == LithoNames.ComponentScope || classId == LithoNames.PrimitiveComponentScope) {
      return true
    }
    queue.addAll(type.toRegularClassSymbol(session)?.resolvedSuperTypes.orEmpty())
  }
  return false
}

private fun runsInOwnHookScope(receiver: ConeKotlinType?, session: FirSession): Boolean {
  val queue = ArrayDeque(listOfNotNull(receiver))
  val visited = mutableSetOf<ConeKotlinType>()
  while (queue.isNotEmpty()) {
    val type = queue.removeFirst()
    if (!visited.add(type)) continue
    if (type is ConeTypeParameterType) {
      queue.addAll(type.typeParameterSymbolCompat.resolvedBounds.map { it.coneType })
      continue
    }
    val symbol = type.toRegularClassSymbol(session) ?: continue
    if (symbol.hasAnnotation(LithoNames.RunsInOwnHookScope, session)) {
      return true
    }
    queue.addAll(symbol.resolvedSuperTypes)
  }
  return false
}
