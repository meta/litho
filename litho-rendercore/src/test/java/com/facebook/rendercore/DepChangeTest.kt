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

package com.facebook.rendercore

import kotlin.jvm.internal.FunctionBase
import kotlin.jvm.internal.Lambda
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DepChangeTest {

  @Test
  fun `reports the index and values of the dependency that changed`() {
    val changes = describeDepChanges(arrayOf<Any?>("a", 1, true), arrayOf<Any?>("a", 2, true))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].index).isEqualTo(1)
    assertThat(changes[0].typeName).isEqualTo("Integer")
    assertThat(changes[0].before).isEqualTo("1")
    assertThat(changes[0].after).isEqualTo("2")
    assertThat(changes[0].comparesByIdentity).isFalse()
  }

  @Test
  fun `reports every dependency that changed, in declaration order`() {
    val changes = describeDepChanges(arrayOf<Any?>("a", 1, true), arrayOf<Any?>("b", 1, false))

    assertThat(changes.map { it.index }).containsExactly(0, 2)
  }

  @Test
  fun `reports nothing when every dependency compares equal`() {
    assertThat(describeDepChanges(arrayOf<Any?>("a", 1), arrayOf<Any?>("a", 1))).isEmpty()
  }

  @Test
  fun `renders char sequences quoted so empty and blank values are distinguishable`() {
    val changes = describeDepChanges(arrayOf(""), arrayOf(" "))

    assertThat(changes[0].before).isEqualTo("\"\"")
    assertThat(changes[0].after).isEqualTo("\" \"")
  }

  @Test
  fun `flags a dependency whose type compares by identity`() {
    val before: () -> Unit = {}
    val after: () -> Unit = {}

    val changes = describeDepChanges(arrayOf<Any?>(before), arrayOf<Any?>(after))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].comparesByIdentity).isTrue()
  }

  @Test
  fun `omits values when both sides render identically`() {
    val before: () -> Unit = {}
    val after: () -> Unit = {}

    val changes = describeDepChanges(arrayOf<Any?>(before), arrayOf<Any?>(after))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].before).isEqualTo(changes[0].after)
    assertThat(changes[0].describe())
        .doesNotContain("→")
        .contains("compares by identity, rebinds every update")
  }

  @Test
  fun `does not call toString on a Kotlin lambda`() {
    class CountingLambda : Lambda<Unit>(0), () -> Unit {
      var toStringCalls = 0

      override fun invoke() = Unit

      override fun toString(): String {
        toStringCalls++
        return "rendered"
      }
    }
    val before = CountingLambda()
    val after = CountingLambda()

    val changes = describeDepChanges(arrayOf<Any?>(before), arrayOf<Any?>(after))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].comparesByIdentity).isTrue()
    assertThat(before.toStringCalls).isEqualTo(0)
    assertThat(after.toStringCalls).isEqualTo(0)
    assertThat(changes[0].before).isEqualTo("Function0")
    assertThat(changes[0].after).isEqualTo("Function0")
  }

  @Test
  fun `renders a lambda by its arity so differently shaped lambdas still differ`() {
    val before: () -> Unit = {}
    val after: (Int) -> Unit = {}

    val changes = describeDepChanges(arrayOf<Any?>(before), arrayOf<Any?>(after))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].before).isEqualTo("Function0")
    assertThat(changes[0].after).isEqualTo("Function1")
  }

  @Test
  fun `renders a function reference by its arity`() {
    val before: (Int, Int) -> Unit = ::twoArguments
    val after: (Int, Int) -> Unit = ::twoArguments

    val changes = describeDepChanges(arrayOf<Any?>(before), arrayOf<Any?>(after))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].before).isEqualTo("Function2")
    assertThat(changes[0].after).isEqualTo("Function2")
  }

  @Test
  fun `does not throw when a function dependency cannot report its arity`() {
    class ThrowingArity : FunctionBase<Unit> {
      override val arity: Int
        get() = throw IllegalStateException("arity unavailable")
    }

    val changes = describeDepChanges(arrayOf<Any?>(ThrowingArity()), arrayOf<Any?>(ThrowingArity()))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].before).contains("ThrowingArity").contains("threw IllegalStateException")
    assertThat(changes[0].comparesByIdentity).isTrue()
  }

  @Test
  fun `keeps values when the two sides render differently`() {
    val changes = describeDepChanges(arrayOf<Any?>(1), arrayOf<Any?>(2))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].describe()).contains("1 → 2")
  }

  @Test
  fun `reports a dependency appearing`() {
    val after: () -> Unit = {}

    val changes = describeDepChanges(arrayOf<Any?>(null), arrayOf<Any?>(after))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].before).isEqualTo("null")
    assertThat(changes[0].after).isNotEqualTo("null")
  }

  @Test
  fun `reports a dependency disappearing`() {
    val before: () -> Unit = {}

    val changes = describeDepChanges(arrayOf<Any?>(before), arrayOf<Any?>(null))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].before).isNotEqualTo("null")
    assertThat(changes[0].after).isEqualTo("null")
  }

  @Test
  fun `reports nothing when a dependency is null on both sides`() {
    assertThat(describeDepChanges(arrayOf<Any?>(null), arrayOf<Any?>(null))).isEmpty()
  }

  @Test
  fun `does not flag an array, which the framework compares by content`() {
    val changes = describeDepChanges(arrayOf(intArrayOf(1, 2)), arrayOf(intArrayOf(1, 3)))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].comparesByIdentity).isFalse()
  }

  @Test
  fun `does not flag a collection, which the framework compares by content`() {
    val changes = describeDepChanges(arrayOf(ArrayDeque(listOf(1))), arrayOf(ArrayDeque(listOf(2))))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].comparesByIdentity).isFalse()
  }

  @Test
  fun `does not flag a type that overrides equals`() {
    val changes = describeDepChanges(arrayOf(Point(1, 1)), arrayOf(Point(1, 2)))

    assertThat(changes[0].comparesByIdentity).isFalse()
    assertThat(changes[0].after).isEqualTo("Point(x=1, y=2)")
  }

  @Test
  fun `does not flag a type that implements Equivalence`() {
    val changes = describeDepChanges(arrayOf(Tolerant(1)), arrayOf(Tolerant(2)))

    assertThat(changes[0].comparesByIdentity).isFalse()
  }

  @Test
  fun `treats a non-array model as a single dependency at index 0`() {
    val changes = describeDepChanges("before", "after")

    assertThat(changes).hasSize(1)
    assertThat(changes[0].index).isEqualTo(0)
    assertThat(changes[0].after).isEqualTo("\"after\"")
  }

  @Test
  fun `reports nothing when an equal non-array model is compared`() {
    assertThat(describeDepChanges("same", "same")).isEmpty()
  }

  @Test
  fun `handles a dependency becoming null`() {
    val changes = describeDepChanges(arrayOf<Any?>("value"), arrayOf<Any?>(null))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].typeName).isEqualTo("String")
    assertThat(changes[0].after).isEqualTo("null")
  }

  @Test
  fun `reports the extra dependency when the dependency count grows`() {
    val changes = describeDepChanges(arrayOf("a"), arrayOf("a", "b"))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].index).isEqualTo(1)
    assertThat(changes[0].before).isEqualTo("null")
  }

  @Test
  fun `reports the missing dependency when the dependency count shrinks`() {
    val changes = describeDepChanges(arrayOf("a", "b"), arrayOf("a"))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].index).isEqualTo(1)
    assertThat(changes[0].after).isEqualTo("null")
  }

  @Test
  fun `truncates long values so a single dependency cannot flood the output`() {
    val changes = describeDepChanges(arrayOf("short"), arrayOf("x".repeat(500)))

    assertThat(changes[0].after).hasSizeLessThan(80)
    // Truncation happens before quoting, so an over-long string is still delimited on both sides.
    assertThat(changes[0].after).startsWith("\"").endsWith("…\"")
  }

  @Test
  fun `survives a dependency whose toString throws`() {
    val changes = describeDepChanges(arrayOf(Exploding(1)), arrayOf(Exploding(2)))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].after).contains("toString threw")
  }

  @Test
  fun `describes a value change as before and after`() {
    val changes = describeDepChanges(arrayOf(1), arrayOf(2))

    assertThat(changes[0].describe()).isEqualTo("dep[0] Integer 1 → 2")
  }

  @Test
  fun `describes an identity-compared dependency as always rebinding`() {
    val changes = describeDepChanges(arrayOf<Any?>({}), arrayOf<Any?>({}))

    assertThat(changes[0].describe()).contains("compares by identity, rebinds every update")
  }

  @Test
  fun `names both types when the dependency changed type`() {
    val changes = describeDepChanges(arrayOf<Any?>(1), arrayOf<Any?>(1L))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].typeChanged).isTrue()
    // Both sides render "1", so without the type names the line would say nothing at all.
    assertThat(changes[0].describe()).isEqualTo("dep[0] Integer 1 → Long 1")
  }

  @Test
  fun `flags a holder whose equals compares a lambda by identity`() {
    val changes = describeDepChanges(arrayOf(Handlers({})), arrayOf(Handlers({})))

    assertThat(changes).hasSize(1)
    // `equals` is overridden, so comparesByIdentity cannot see it - but the data class compares its
    // lambda by reference, so this rebinds forever while both sides render identically.
    assertThat(changes[0].comparesByIdentity).isFalse()
    assertThat(changes[0].describe())
        .isEqualTo("dep[0] Handlers (no visible difference, likely rebinds every update)")
  }

  @Test
  fun `flags a container holding a dependency that compares by identity`() {
    val changes = describeDepChanges(arrayOf(listOf({})), arrayOf(listOf({})))

    assertThat(changes).hasSize(1)
    // areObjectsEquivalent recurses into the list and compares the lambda by reference, so the
    // container rebinds every update even though the container type itself overrides `equals`.
    assertThat(changes[0].comparesByIdentity).isFalse()
    assertThat(changes[0].describe()).contains("rebinds every update")
  }

  @Test
  fun `surfaces the differing region when values differ only past the truncation point`() {
    // Longer than MAX_VALUE_LENGTH so both sides truncate to the same text, without the test
    // having to know the exact limit.
    val prefix = "x".repeat(100)

    val changes = describeDepChanges(arrayOf(prefix + "A"), arrayOf(prefix + "B"))

    assertThat(changes).hasSize(1)
    // A genuine change, so we must not blame it as an identity rebind...
    assertThat(changes[0].describe()).doesNotContain("rebinds every update")
    // ...and instead of the useless "differs past N chars", the window now surfaces the exact
    // characters that flipped, which truncation had hidden.
    assertThat(changes[0].describe()).doesNotContain("differs past")
    assertThat(changes[0].describe()).contains("A\u2026 \u2192 ")
    assertThat(changes[0].describe()).endsWith("B\u2026")
  }

  @Test
  fun `flags per-render churn when the divergence is only an identity hash`() {
    // The real shape from Reels: a state class (MediaInteractiveUiState) with a correct, generated
    // `equals`, holding a nested field (ClipsFeedInteractiveItem) that has no `equals` and is
    // rebuilt
    // every render. The state compares unequal every render even though nothing meaningful changed;
    // its toString differs only in the nested field's @<hash>, and the stable padding pushes that
    // past the truncation cap so both sides would otherwise print identically.
    val stable = "p".repeat(80)

    val changes = describeDepChanges(
        arrayOf(StateWithChurningField(stable, NoEqualsField("a558348"))),
        arrayOf(StateWithChurningField(stable, NoEqualsField("5783f06"))),
    )

    assertThat(changes).hasSize(1)
    // The state class itself has a real `equals`, so it is not identity-compared...
    assertThat(changes[0].comparesByIdentity).isFalse()
    // ...but it churns because its nested field is, which is exactly what the annotation calls out.
    assertThat(changes[0].identityHashChurn).isTrue()
    assertThat(changes[0].describe()).contains("@<identity-hash>").contains("rebinds every update")
  }

  @Test
  fun `does not flag churn when the divergence is not inside an identity hash`() {
    // Same @<hash> on both sides; only the character right after it differs. A real value changed
    // past the cap, not identity churn - flagging it would send the reader chasing a rebind that is
    // not happening.
    val prefix = "x".repeat(80) + "Item@a558348"

    val changes = describeDepChanges(arrayOf("$prefix)"), arrayOf("$prefix]"))

    assertThat(changes).hasSize(1)
    assertThat(changes[0].identityHashChurn).isFalse()
    assertThat(changes[0].describe()).doesNotContain("rebinds every update")
  }

  @Test
  fun `bounds the diff window so a long divergence cannot flood the report`() {
    // Shared prefix past the cap so the window path is taken, then a divergence that runs for
    // thousands of characters. The window must stay bounded rather than dumping the whole value,
    // which would bypass the truncation cap the rest of the report relies on.
    val prefix = "x".repeat(80)

    val changes = describeDepChanges(
        arrayOf(prefix + "A".repeat(5_000)),
        arrayOf(prefix + "B".repeat(5_000)),
    )

    assertThat(changes).hasSize(1)
    assertThat(changes[0].describe().length).isLessThan(200)
  }

  private data class Point(val x: Int, val y: Int)

  private data class Handlers(@Suppress("unused") val onTap: () -> Unit)

  /**
   * Stands in for a real no-`equals` type like Reels' `ClipsFeedInteractiveItem`: with no `equals`
   * override, every instance is unequal to every other, so anything holding it rebinds every
   * render. The fixed toString fakes the `ClassName@<hash>` shape deterministically - a real
   * identity hash is random per run and would make the assertions flaky.
   */
  private class NoEqualsField(private val fakeHash: String) {
    override fun toString(): String = "Item@$fakeHash"
  }

  /**
   * Stands in for a real state class like Reels' `MediaInteractiveUiState`: a data class with a
   * correct generated `equals` whose only churning field is an identity-compared [NoEqualsField],
   * so the whole dep compares unequal every render even when [stable] never changed.
   */
  private data class StateWithChurningField(
      val stable: String,
      @Suppress("unused") val churning: NoEqualsField,
  )

  private class Tolerant(@Suppress("unused") val value: Int) : Equivalence<Tolerant> {
    override fun isEquivalentTo(other: Tolerant): Boolean = false
  }

  private class Exploding(private val id: Int) {
    override fun equals(other: Any?): Boolean = other is Exploding && other.id == id

    override fun hashCode(): Int = id

    override fun toString(): String = throw IllegalStateException("nope")
  }
}

private fun twoArguments(a: Int, b: Int) = Unit
