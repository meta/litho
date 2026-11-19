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

package com.facebook.litho.ir

import com.facebook.kotlin.compilercompat.compatExtensionReceiver
import com.facebook.kotlin.compilercompat.valueArgumentsCountCompat
import com.facebook.litho.common.AutoDepsConfig
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/** The dependencies inferred for one call site, and everything that went wrong finding them. */
class Captures(val deps: List<IrExpression>, val problems: List<CaptureProblem>) {
  val hasErrors: Boolean
    get() = problems.any { it.kind.isError }
}

/** A capture the analysis could not handle silently, anchored at [element] for source position. */
class CaptureProblem(val kind: Kind, val element: IrElement, val detail: String) {

  enum class Kind(val isError: Boolean) {
    MALFORMED_DECLARATION(true),
    NON_LITERAL_TARGET(true),
    LOCAL_FUNCTION_CALL(true),
    UNEXPANDABLE_CAPTURE(true),
    MUTABLE_CAPTURE(true),
    UNSTABLE_CAPTURE(false);

    val id: String
      get() = "AUTODEPS_$name"
  }
}

/**
 * Infers the dependency list of an `@AutoDeps` call site by reading the closure of its `@DepsFrom`
 * arguments.
 *
 * The base analysis cannot under-capture *what the lambda closes over*: it emits every value the
 * lambda reads from an enclosing scope, so the inferred set is a superset of the enclosing state
 * the body depends on. Only the two configured repairs -- `expand` and `ignore` -- can drop
 * something, which is why both are named in a reviewable config file rather than being implicit
 * compiler behaviour.
 *
 * State that is not closed over is out of scope by construction, and this is the same line React's
 * exhaustive-deps rule draws. A read of a top-level or `object` property has no closure to capture
 * it from, so it yields no dependency; if that state is mutable, the author must pass it
 * explicitly. Widening the analysis to reach it would mean reasoning about a declaration this one
 * does not recompile with -- the same reason interprocedural analysis is rejected.
 *
 * Comparing the dependencies is the framework's job, not the analysis's. A chain rooted at a
 * captured local depends on the local, so `user.name` depends on `user`, and a `user` mutated in
 * place rather than replaced compares equal to itself.
 *
 * A `var` the lambda reads or assigns is rejected. The dependency is read when the call is made and
 * the lambda reads the variable when it runs, so the two can disagree; and a lambda the framework
 * reuses writes to the variable of the call that created it, which no dependency value can tell
 * apart from the next call's.
 *
 * Four levels, per the design:
 * - **0**: every value read whose declaration lies outside the lambda.
 * - **1**: reads rooted at an enclosing receiver become property paths (`title`, not `this`).
 * - **2**: a captured local of an `expand` type becomes the free values of its initialiser.
 * - **3**: a captured local lambda becomes its own free values, so it stops invalidating every
 *   render.
 *
 * The analysis never reads a body in another file. That is a hard constraint, not a simplification:
 * interprocedural inference would make a call site's dependencies depend on a body it does not
 * recompile with.
 */
class CaptureAnalysis(
    pluginContext: IrPluginContext,
    config: AutoDepsConfig,
    messages: MessageCollector,
) {

  private val ignored: List<IrClassSymbol> =
      pluginContext.resolve(config.ignore, "ignore", messages)
  private val expandable: List<IrClassSymbol> =
      pluginContext.resolve(config.expand, "expand", messages)
  private val stable: List<IrClassSymbol> = pluginContext.resolve(config.stable, "stable", messages)

  /**
   * A type the compilation cannot see is skipped rather than fatal: the shipped default names types
   * -- `android.content.Context`, Litho's own -- that not every module compiling with the plugin
   * can see, and that is expected.
   *
   * An entry an author supplied is a different claim, so it is skipped out loud. A directive that
   * never applies is exactly what the parser refuses to let happen silently, and the consequence is
   * real: a dropped `ignore` adds a dependency nobody asked for, and a dropped `expand` turns an
   * error into a dead cache.
   */
  private fun IrPluginContext.resolve(
      entries: List<AutoDepsConfig.Entry>,
      directive: String,
      messages: MessageCollector,
  ): List<IrClassSymbol> = entries.mapNotNull { entry ->
    val symbol = referenceClass(entry.type)
    if (symbol == null && !entry.isDefault) {
      messages.report(
          CompilerMessageSeverity.WARNING,
          "litho-autodeps: ${entry.where}: '$directive ${entry.type.asFqNameString()}' does not " +
              "resolve in this compilation, so the directive has no effect here.",
      )
    }
    symbol
  }

  /**
   * Scans the [targets] -- the `@DepsFrom` arguments of a single call -- for their captures.
   *
   * [constructing] holds the receivers of objects still under construction where the call is made.
   */
  fun analyse(
      targets: List<IrExpression>,
      constructing: Set<IrValueSymbol> = emptySet(),
  ): Captures = Scan(constructing).run(targets)

  private inner class Scan(
      /**
       * Receivers of objects under construction. A field declared further down still holds null, so
       * a path through one, read at the call, could throw; no path is rooted at them.
       */
      private val constructing: Set<IrValueSymbol>,
      /** The local callees being probed on the way to this scan, so recursion through them ends. */
      private val probing: Set<IrFunctionSymbol> = emptySet(),
  ) {

    /** Every value declared inside the scanned closures; reads of these are not dependencies. */
    private val bound = mutableSetOf<IrValueSymbol>()

    /** Local callees already looked at, so a function called twice is reported once. */
    private val followedLocalCallees = mutableSetOf<IrFunctionSymbol>()

    /** Functions the walk entered, whose reads are therefore already dependencies. */
    private val walkedFunctions = mutableSetOf<IrFunctionSymbol>()

    /** Mutable captures already reported, so a `var` read and assigned is one error. */
    private val reportedMutables = mutableSetOf<Any>()
    private val problems = mutableListOf<CaptureProblem>()

    fun run(targets: List<IrExpression>): Captures {
      val deps = LinkedHashMap<Any, IrExpression>()
      for (target in targets) {
        val lambda = literalLambdaOrNull(target)
        if (lambda == null) {
          problems +=
              CaptureProblem(
                  CaptureProblem.Kind.NON_LITERAL_TARGET,
                  target,
                  "the argument is not a lambda literal, so it has no closure to read. Pass the " +
                      "dependencies explicitly, or inline the lambda at the call site.",
              )
          continue
        }
        resolve(collect(lambda), depth = 0, into = deps)
      }
      return Captures(deps.values.toList(), problems)
    }

    /** Applies the repairs and the ignore list to raw captures, accumulating into [into]. */
    private fun resolve(
        candidates: List<IrExpression>,
        depth: Int,
        into: MutableMap<Any, IrExpression>,
    ) {
      for (candidate in candidates) {
        val type = candidate.type
        when {
          ignored.any(type::isSubtypeOfClass) -> Unit
          expandable.any(type::isSubtypeOfClass) -> expand(candidate, depth, into, required = true)
          isLocalLambda(candidate) -> expand(candidate, depth, into, required = false)
          else -> keep(candidate, into)
        }
      }
    }

    /**
     * Replaces [candidate] with the free values of its initialiser. When that is impossible and the
     * expansion was [required] -- an `expand` type has no meaningful equivalence, so keeping it
     * silently kills caching -- the author is told rather than guessed at.
     */
    private fun expand(
        candidate: IrExpression,
        depth: Int,
        into: MutableMap<Any, IrExpression>,
        required: Boolean,
    ) {
      val initializer = localInitializer(candidate)
      val tooDeep = depth >= MAX_EXPANSION_DEPTH
      if (initializer == null || tooDeep) {
        if (required) {
          val why =
              if (tooDeep) "the chain of expansions is deeper than $MAX_EXPANSION_DEPTH"
              else "it is not a local val whose initialiser this declaration can be read for"
          problems +=
              CaptureProblem(
                  CaptureProblem.Kind.UNEXPANDABLE_CAPTURE,
                  candidate,
                  "'${candidate.type.simpleName()}' has no meaningful equivalence and cannot be " +
                      "expanded to its inputs, because $why. Hoist it into a local val in the same " +
                      "declaration, or pass the dependencies explicitly.",
              )
        } else {
          keep(candidate, into)
        }
        return
      }
      resolve(collect(initializer), depth + 1, into)
    }

    /** Keeps [candidate] as a dependency, reporting the shapes that cannot be kept soundly. */
    private fun keep(candidate: IrExpression, into: MutableMap<Any, IrExpression>) {
      // A value read several times in the lambda is one dependency, so it is also one report.
      val key = candidate.depKey()
      if (key in into) return
      into[key] = candidate

      val mutable = candidate.varName()
      if (mutable != null) reportMutable(key, candidate, mutableRead(mutable))
      val type = candidate.type
      if (type.isFunctionLike() && stable.none(type::isSubtypeOfClass)) {
        problems +=
            CaptureProblem(
                CaptureProblem.Kind.UNSTABLE_CAPTURE,
                candidate,
                "'${type.simpleName()}' compares by identity, so this dependency changes on every render. " +
                    "Wrap it in useCallback, or hoist it out of the enclosing declaration.",
            )
      }
    }

    /** Reports a mutable capture once per variable, however the lambda reaches it. */
    private fun reportMutable(key: Any, at: IrElement, detail: String) {
      if (reportedMutables.add(key)) {
        problems += CaptureProblem(CaptureProblem.Kind.MUTABLE_CAPTURE, at, detail)
      }
    }

    private fun mutableRead(name: String): String =
        "'$name' is a var. The dependency is read when the call is made, but the lambda reads " +
            "'$name' when it runs, so the two can differ and the lambda go stale. Copy it into a " +
            "local val, or pass the dependencies explicitly."

    /**
     * Reaching [callee] reads nothing here, but its body may close over the enclosing scope, and
     * this walk does not enter it: the body is a sibling declaration, not part of the lambda.
     * Saying nothing would drop those values from the dependency list -- an under-capture, which is
     * the one direction the inference must never fail in -- so the author is told instead of
     * guessed at.
     *
     * Reported rather than expanded. The values are right there and following them is a strictly
     * larger change to what a call site infers, so it is a decision for the API's owner, not one to
     * take silently on the way past.
     *
     * [at] is what the diagnostic is positioned on: the call, or the reference that never calls it.
     */
    private fun reportUnfollowedLocalCallee(callee: IrFunction, at: IrExpression) {
      if (!callee.isNestedInAFunction()) return
      // Declared inside the closure, so the walk already collected what it reads.
      if (callee.symbol in walkedFunctions) return
      // Self-recursion, or a callee that reaches back to one already being probed.
      if (callee.symbol in probing) return
      if (!followedLocalCallees.add(callee.symbol)) return
      if (!Scan(constructing, probing + callee.symbol).readsEnclosingScope(callee)) return
      problems += CaptureProblem(CaptureProblem.Kind.LOCAL_FUNCTION_CALL, at, unfollowed(callee))
    }

    /**
     * Whether [function] reads or assigns a value from outside itself, directly or through a local
     * function it calls in turn.
     *
     * Runs as a scan of its own, so nothing the probe binds, walks or reports leaks into the scan
     * that asked. A probe only walks, so the errors it can find are a callee of [function]'s that
     * reads the enclosing scope and an assignment to an enclosing `var` -- either way [function]
     * touches that scope, and the error belongs on the call the lambda makes, not on one buried in
     * a body outside it.
     */
    fun readsEnclosingScope(function: IrFunction): Boolean {
      // An ignored type would have been dropped from the dependency list anyway, so a body that
      // reads nothing else is not an under-capture and must not be rejected.
      val captured = collect(function).filterNot { ignored.any(it.type::isSubtypeOfClass) }
      return captured.isNotEmpty() || problems.any { it.kind.isError }
    }

    private fun unfollowed(callee: IrFunction): String {
      val property = callee.delegatedPropertyName()
      return if (property != null) {
        "'$property' is a delegated local property, and reading it calls a delegate the analysis " +
            "does not follow. Read it into a local val outside the lambda, or pass the " +
            "dependencies explicitly."
      } else {
        "'${callee.name}' is declared inside the enclosing declaration and reads values from it, " +
            "which the analysis does not follow. Inline it into the lambda, or pass the " +
            "dependencies explicitly."
      }
    }

    /**
     * Whether [this] is lexically nested inside a function, and so can read that function's locals.
     *
     * Broader than `LOCAL` visibility, which a local function has but a member of a *local class*
     * does not -- and at this phase closure conversion has not run, so nothing in the lambda
     * mentions what such a member reads.
     */
    private fun IrFunction.isNestedInAFunction(): Boolean {
      var ancestor: IrDeclarationParent? = parent
      while (ancestor != null) {
        if (ancestor is IrFunction) return true
        ancestor = (ancestor as? IrDeclaration)?.parent
      }
      return false
    }

    /** Walks [element] for every value it reads from outside itself, in source order. */
    private fun collect(element: IrElement): List<IrExpression> {
      val captures = mutableListOf<IrExpression>()
      element.acceptVoid(Collector(captures))
      return captures
    }

    private fun isLocalLambda(candidate: IrExpression): Boolean =
        localInitializer(candidate)?.let(::literalLambdaOrNull) != null

    private inner class Collector(private val out: MutableList<IrExpression>) : IrVisitorVoid() {

      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      // Parameters are visited before the body, so binding them here is enough to exclude
      // everything the lambda -- or any lambda nested in it -- introduces, receivers included.
      override fun visitValueParameter(declaration: IrValueParameter) {
        bound += declaration.symbol
        declaration.acceptChildrenVoid(this)
      }

      // A function declared inside the scanned closure is walked like any other statement, so what
      // it reads from further out is already a dependency. Recording it keeps
      // [reportUnfollowedLocalCallee] from rejecting a closure this walk handled.
      override fun visitFunction(declaration: IrFunction) {
        walkedFunctions += declaration.symbol
        declaration.acceptChildrenVoid(this)
      }

      override fun visitVariable(declaration: IrVariable) {
        declaration.initializer?.acceptVoid(this)
        bound += declaration.symbol
      }

      override fun visitGetValue(expression: IrGetValue) {
        if (expression.symbol !in bound) out += expression
      }

      // An assignment reads nothing, so without this a lambda that only writes an enclosing `var`
      // would infer no dependency at all.
      override fun visitSetValue(expression: IrSetValue) {
        if (expression.symbol !in bound) {
          val name = expression.symbol.owner.name
          reportMutable(
              expression.symbol,
              expression,
              "'$name' is a var the lambda assigns. A lambda the framework reuses writes to the " +
                  "'$name' of the call that created it, and no dependency can tell that apart from " +
                  "the next call's. Return the value instead, or pass the dependencies explicitly.",
          )
        }
        expression.acceptChildrenVoid(this)
      }

      override fun visitCall(expression: IrCall) {
        if (expression.isReceiverRootedPropertyPath()) {
          out += expression
          return
        }
        rejectUnliftedVar(expression)
        reportUnfollowedLocalCallee(expression.symbol.owner, expression)
        expression.acceptChildrenVoid(this)
      }

      override fun visitFunctionReference(expression: IrFunctionReference) {
        reportUnfollowedLocalCallee(expression.symbol.owner, expression)
        expression.acceptChildrenVoid(this)
      }

      override fun visitConstructorCall(expression: IrConstructorCall) {
        reportUnfollowedLocalCallee(expression.symbol.owner, expression)
        expression.acceptChildrenVoid(this)
      }

      override fun visitGetField(expression: IrGetField) {
        if (expression.isReceiverRootedPropertyPath()) {
          out += expression
          return
        }
        rejectUnliftedVar(expression)
        expression.acceptChildrenVoid(this)
      }

      /**
       * A property read that is not lifted is walked like a method call, which makes its receiver
       * the dependency. A `var` it reads still changes under the lambda, and the receiver would
       * hide that, so it is rejected by name -- including through an object under construction.
       */
      private fun rejectUnliftedVar(expression: IrExpression) {
        val property = expression.pathProperty() ?: return
        if (property.isVar && expression.isRootedPropertyRead(throughConstruction = true)) {
          reportMutable(expression.depKey(), expression, mutableRead(property.name.asString()))
        }
      }

      /**
       * Level 1: the longest chain of stored-property reads rooted at a receiver the closure does
       * not bind. `this.user.name` is the dependency; `this` is not. A chain rooted at anything
       * else -- a captured local, a top-level property -- is left to the ordinary walk, which emits
       * the root.
       *
       * Only a read that runs none of the author's code is a link ([isLiftable]), because the path
       * is lifted out of the lambda and evaluated at the call. A custom getter, an extension
       * property, or a getter an override can replace would run even where the lambda guards it or
       * never runs, and a getter such as `get() = _binding!!` would throw there. It is walked like
       * a method call instead, so its receiver is the dependency.
       *
       * A `var` link is still a path, so [keep] can reject it by name. Falling back to `this` would
       * say nothing, and `this` compares equal to itself however often the property changed.
       */
      private fun IrExpression.isReceiverRootedPropertyPath(
          throughConstruction: Boolean = false,
      ): Boolean = isLiftable() && isRootedPropertyRead(throughConstruction)

      /**
       * A property read, through any accessor, every one of whose receivers is rooted. Requiring
       * every receiver keeps a path from lifting a subtree that reads something the lambda binds to
       * a place it does not exist, which the backend rejects with "No mapping for symbol".
       */
      private fun IrExpression.isRootedPropertyRead(throughConstruction: Boolean = false): Boolean {
        if (!isPathLink()) return false
        val receivers = pathReceivers()
        // No receiver at all is a top-level or object read: nothing closes over it.
        return receivers.isNotEmpty() && receivers.all { it.isRooted(throughConstruction) }
      }

      /** A value a path may stand on: an unbound receiver read, or another fully rooted link. */
      private fun IrExpression.isRooted(throughConstruction: Boolean): Boolean =
          isUnboundReceiverRead(throughConstruction) ||
              isReceiverRootedPropertyPath(throughConstruction)

      /**
       * An unbound receiver read. The receiver of an object under construction counts only
       * [throughConstruction], which is for naming a `var`, never for lifting a path.
       */
      private fun IrExpression.isUnboundReceiverRead(throughConstruction: Boolean): Boolean {
        val declaration = (this as? IrGetValue)?.symbol?.owner ?: return false
        return declaration.isReceiver &&
            declaration.symbol !in bound &&
            (throughConstruction || declaration.symbol !in constructing)
      }
    }
  }

  private companion object {
    /**
     * Bounds the `expand` chain; a local whose initialiser is another expandable local recurses.
     */
    const val MAX_EXPANSION_DEPTH = 4

    /** Where the compiler puts the generated `FunctionN` and `SuspendFunctionN` classes. */
    val FUNCTION_CLASS_PACKAGES =
        setOf("kotlin", "kotlin.jvm.functions", "kotlin.coroutines", "kotlin.reflect")

    /**
     * Kotlin names receiver parameters `<this>` (dispatch) and `$this$<function>` (extension). The
     * unified-parameter-model `kind` that would say so directly is not on every Kotlin version the
     * plugin builds against, and it is not shimmed.
     */
    val IrValueDeclaration.isReceiver: Boolean
      get() {
        if (this !is IrValueParameter) return false
        val text = name.asString()
        return text == "<this>" || text.startsWith("\$this\$")
      }

    fun literalLambdaOrNull(target: IrExpression): IrFunction? {
      var current: IrExpression = target
      while (
          current is IrTypeOperatorCall &&
              (current.operator == IrTypeOperator.SAM_CONVERSION ||
                  current.operator == IrTypeOperator.IMPLICIT_CAST)
      ) {
        current = current.argument
      }
      return (current as? IrFunctionExpression)?.function
    }

    /**
     * The initialiser of a captured local `val`.
     *
     * A `var` is excluded deliberately. Expanding one substitutes the free values of an initialiser
     * the variable may no longer hold, which is a silent under-capture -- the one thing the repairs
     * are not allowed to be. Left unexpanded, an `expand` type reports
     * `AUTODEPS_UNEXPANDABLE_CAPTURE` and anything else routes through `keep`, which rejects it.
     */
    fun localInitializer(candidate: IrExpression): IrExpression? {
      val variable = (candidate as? IrGetValue)?.symbol?.owner as? IrVariable ?: return null
      return if (variable.isVar) null else variable.initializer
    }

    /** The property [this] reads, if it is a getter call or a backing field read. */
    fun IrExpression.pathProperty(): IrProperty? =
        when (this) {
          is IrCall -> symbol.owner.correspondingPropertySymbol?.owner
          is IrGetField -> symbol.owner.correspondingPropertySymbol?.owner
          else -> null
        }

    /**
     * One link of a property path: a getter call, or a read of a backing field. Which of the two
     * the compiler produced for `this.name` is not the analysis's concern. A setter belongs to the
     * same property, and its argument is what tells it apart.
     */
    @OptIn(DeprecatedForRemovalCompilerApi::class) // valueArgumentsCountCompat
    fun IrExpression.isPathLink(): Boolean =
        pathProperty() != null && (this !is IrCall || valueArgumentsCountCompat == 0)

    /**
     * Whether reading [this] at the call runs none of the author's code: a field read, or a default
     * getter no override can replace.
     *
     * The getter a call names is not always the one it runs. An `open`, abstract or interface
     * property is dispatched virtually, so a subclass's custom getter would run at the call even
     * though the property it overrides has a default one. Finality is therefore read off the getter
     * the call names; whether it is default is read off the declaration it stands for, since a read
     * through a subclass names an inherited getter's fake override.
     */
    fun IrExpression.isLiftable(): Boolean =
        when (this) {
          is IrGetField -> true
          is IrCall -> {
            val named = symbol.owner
            val isFinal =
                named.modality == Modality.FINAL ||
                    (named.parent as? IrClass)?.modality == Modality.FINAL
            isFinal && named.declaration()?.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
          }
          else -> false
        }

    /**
     * The real declaration a fake override stands for, or null when it inherits more than one and
     * which one runs is not this analysis's to decide.
     */
    fun IrSimpleFunction.declaration(): IrSimpleFunction? {
      var function = this
      while (function.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
        function = function.overriddenSymbols.singleOrNull()?.owner ?: return null
      }
      return function
    }

    /**
     * The `var` a dependency reads through, if any -- the captured local itself, or any link of a
     * property path. Either can change between the call and the lambda's own read.
     */
    fun IrExpression.varName(): String? =
        when (this) {
          is IrGetValue -> (symbol.owner as? IrVariable)?.takeIf { it.isVar }?.name?.asString()
          is IrCall,
          is IrGetField ->
              pathProperty()?.takeIf { it.isVar }?.name?.asString()
                  ?: pathReceivers().firstNotNullOfOrNull { it.varName() }
          else -> null
        }

    /**
     * The property a local delegated property's accessor belongs to. The accessor is all the IR
     * names -- `<get-text>` -- where the source said `text`.
     */
    fun IrFunction.delegatedPropertyName(): String? {
      if (origin != IrDeclarationOrigin.DELEGATED_PROPERTY_ACCESSOR) return null
      return name.asString().removePrefix("<get-").removePrefix("<set-").removeSuffix(">")
    }

    /** Every receiver a property read goes through. A member extension property has two. */
    @OptIn(DeprecatedForRemovalCompilerApi::class) // compatExtensionReceiver
    fun IrExpression.pathReceivers(): List<IrExpression> =
        when (this) {
          is IrCall -> listOfNotNull(dispatchReceiver, compatExtensionReceiver)
          is IrGetField -> listOfNotNull(receiver)
          else -> emptyList()
        }

    /**
     * Identifies a dependency for de-duplication: the symbol chain it reads, receiver first.
     *
     * Every receiver is keyed, so reads of one property through different receivers -- `a.x` and
     * `b.x`, even when they share a dispatch receiver -- are different keys.
     */
    fun IrExpression.depKey(): Any =
        when (this) {
          is IrGetValue -> symbol
          is IrCall -> listOf(symbol) + pathReceivers().map { it.depKey() }
          is IrGetField -> listOf(symbol) + pathReceivers().map { it.depKey() }
          else -> this
        }

    /**
     * The shapes with identity equality that Level 3 exists to repair: function types and `fun
     * interface`s. The package is part of the test because the compiler's function classes are only
     * recognisable by name, and a user type called `FunctionRegistry` is not one of them.
     */
    fun IrType.isFunctionLike(): Boolean {
      val owner = classOrNull?.owner ?: return false
      if (owner.isFun) return true
      val name = owner.name.asString()
      if (!name.startsWith("Function") && !name.startsWith("SuspendFunction")) return false
      return owner.getPackageFragment().packageFqName.asString() in FUNCTION_CLASS_PACKAGES
    }

    fun IrType.simpleName(): String = classOrNull?.owner?.name?.asString() ?: "?"
  }
}
