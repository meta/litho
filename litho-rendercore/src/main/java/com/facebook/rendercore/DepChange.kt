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

import com.facebook.rendercore.utils.areObjectsEquivalent
import kotlin.jvm.internal.FunctionBase

/** Marks a [RenderUnit.Binder] whose model is a positional array of dependencies. */
interface DepsModel

/** A dependency that differed between two renders, causing its binder to rebind. */
data class DepChange(
    val index: Int,
    val typeName: String,
    val before: String,
    val after: String,
    /**
     * The type neither overrides `equals` nor implements [Equivalence], so any new instance counts
     * as a change and this binder rebinds on every update. Usually a lambda.
     */
    val comparesByIdentity: Boolean,
    /** The type on the before side. Equal to [typeName] unless [typeChanged]. */
    val beforeTypeName: String = typeName,
    /**
     * Both sides were non-null and of different classes. `areObjectsEquivalent` rejects those
     * before comparing anything, so the type swap *is* the reason the binder rebound.
     */
    val typeChanged: Boolean = false,
    /**
     * The two values differed as raw text, before truncation. Distinct from `before != after`,
     * which only sees what fits in the report.
     */
    val valuesDiffer: Boolean = true,
    /**
     * When the values differ only past the display cap (so [before] and [after] print the same), a
     * window of the raw text around the first divergence for each side. Names the field that
     * flipped instead of the useless "differs past N chars".
     */
    val diffBefore: String? = null,
    val diffAfter: String? = null,
    /**
     * The first divergence falls inside an `@<hex>` identity hash: the dep overrides `equals` at
     * the top level but a nested field is identity-compared and rebuilt each render, so it rebinds
     * every update while looking like an ordinary value change.
     */
    val identityHashChurn: Boolean = false,
) {
  fun describe(): String {
    // A type swap can be the whole reason, and it is invisible in the values alone: `String "hi"`
    // and `SpannableString "hi"` render the same. Name both sides when that happens.
    //
    // Identity-compared types are excluded: every lambda literal is its own synthetic class, so two
    // lambdas almost always "changed type". That name carries nothing, and reporting it would hide
    // the hint below, which is the signal that actually matters.
    if (typeChanged && !comparesByIdentity) {
      return "dep[$index] $beforeTypeName $before → $typeName $after"
    }
    if (valuesDiffer) {
      // Two values can differ past the truncation point and still print the same, which reads as a
      // contradiction. Show a window around the first divergence so the field that flipped is
      // named,
      // and call out the case where that divergence is only an identity hash (per-render churn).
      if (before == after && diffBefore != null && diffAfter != null) {
        val churn =
            if (identityHashChurn)
                " (differs only in an @<identity-hash>: nested identity-compared dep, rebinds every update)"
            else ""
        return "dep[$index] $typeName …$diffBefore… → …$diffAfter…$churn"
      }
      return "dep[$index] $typeName $before → $after"
    }
    // The values are genuinely the same text, yet the deps compared unequal - so whatever differs
    // is invisible here. That is the shape of a dep that rebinds on every update: a bare lambda, a
    // holder whose `equals` compares one by reference, or a container holding one.
    val hint =
        if (comparesByIdentity) {
          // No `equals` at all, so a new instance can never match: certain, not probable.
          " (compares by identity, rebinds every update)"
        } else {
          " (no visible difference, likely rebinds every update)"
        }
    return "dep[$index] $typeName$hint"
  }
}

private const val MAX_VALUE_LENGTH = 64

/**
 * Diffs two dependency models, handling both the `vararg deps` array and the single dependency used
 * by `bindTo`. An empty result means the deps compared equal, so something else caused the rebind.
 */
fun describeDepChanges(currentModel: Any?, newModel: Any?): List<DepChange> {
  if (currentModel !is Array<*> || newModel !is Array<*>) {
    return if (areObjectsEquivalent(currentModel, newModel)) {
      emptyList()
    } else {
      listOf(describeDepChange(0, currentModel, newModel))
    }
  }
  var changes: MutableList<DepChange>? = null
  // Compare up to the longer model so a dep present on only one side is reported as appearing or
  // disappearing. Returning early on a size mismatch would produce an empty list, which callers
  // read as "the deps compared equal" and attribute to layout data instead.
  for (i in 0 until maxOf(currentModel.size, newModel.size)) {
    val before = currentModel.getOrNull(i)
    val after = newModel.getOrNull(i)
    if (areObjectsEquivalent(before, after)) {
      continue
    }
    val list = changes ?: mutableListOf<DepChange>().also { changes = it }
    list.add(describeDepChange(i, before, after))
  }
  return changes ?: emptyList()
}

private fun describeDepChange(index: Int, before: Any?, after: Any?): DepChange {
  val beforeType = before?.javaClass
  val afterType = after?.javaClass
  val type = afterType ?: beforeType
  val rawBefore = renderValue(before)
  val rawAfter = renderValue(after)
  val valuesDiffer = rawBefore != rawAfter
  val displayBefore = displayValue(before, rawBefore)
  val displayAfter = displayValue(after, rawAfter)
  // The window only feeds describe()'s collapsed-display branch, so build it only when the
  // truncated values actually print the same. Skips the diff scan on every ordinary value change.
  val window =
      if (valuesDiffer && displayBefore == displayAfter) diffWindow(rawBefore, rawAfter) else null
  return DepChange(
      index = index,
      typeName = type?.let { typeNameOf(it) } ?: "null",
      before = displayBefore,
      after = displayAfter,
      comparesByIdentity = type != null && comparesByIdentity(type),
      beforeTypeName = (beforeType ?: type)?.let { typeNameOf(it) } ?: "null",
      typeChanged = beforeType != null && afterType != null && beforeType != afterType,
      // Decided on the untruncated text. Comparing the displayed values instead would read two
      // long URLs sharing a 64-char prefix as unchanged and blame them for rebinding forever.
      valuesDiffer = valuesDiffer,
      diffBefore = window?.before,
      diffAfter = window?.after,
      identityHashChurn = window?.identityHashChurn ?: false,
  )
}

/** A slice of raw text around the first point two values diverge, for each side. */
private class DiffWindow(val before: String, val after: String, val identityHashChurn: Boolean)

private const val DIFF_CONTEXT = 30

/**
 * Returns a window of the raw text around the first point [before] and [after] diverge, for each
 * side, so the field that flipped is visible without dumping the whole value. The window is capped
 * at [MAX_VALUE_LENGTH] code points per side, so a long-running divergence cannot flood the report
 * past the same cap the rest of the reporting relies on. Flags the divergence when it lands inside
 * an `@<hex>` identity hash — the signature of a nested identity-compared dep churning every
 * render.
 *
 * Operates on code points, not chars, so a window boundary never splits a surrogate pair (emoji and
 * other supplementary characters) into a lone surrogate.
 */
private fun diffWindow(before: String, after: String): DiffWindow {
  val b = codePointArray(before)
  val a = codePointArray(after)
  val min = minOf(b.size, a.size)
  var i = 0
  while (i < min && b[i] == a[i]) {
    i++
  }
  var jb = b.size - 1
  var ja = a.size - 1
  while (jb >= i && ja >= i && b[jb] == a[ja]) {
    jb--
    ja--
  }
  val lo = maxOf(0, i - DIFF_CONTEXT)
  val churn = isInsideIdentityHash(b, i) || isInsideIdentityHash(a, i)
  return DiffWindow(
      codePointsToString(b, lo, minOf(b.size, jb + 1 + DIFF_CONTEXT, lo + MAX_VALUE_LENGTH)),
      codePointsToString(a, lo, minOf(a.size, ja + 1 + DIFF_CONTEXT, lo + MAX_VALUE_LENGTH)),
      churn,
  )
}

/** The code points of [s]. `String.codePoints()` is API 24+, so it would crash on Android 6. */
private fun codePointArray(s: String): IntArray {
  val out = IntArray(s.codePointCount(0, s.length))
  var offset = 0
  for (k in out.indices) {
    val codePoint = s.codePointAt(offset)
    out[k] = codePoint
    offset += Character.charCount(codePoint)
  }
  return out
}

/** The code points of [cp] from [from] (inclusive) to [to] (exclusive) as a string. */
private fun codePointsToString(cp: IntArray, from: Int, to: Int): String {
  val builder = StringBuilder()
  for (k in from until to) {
    builder.appendCodePoint(cp[k])
  }
  return builder.toString()
}

private fun isHex(codePoint: Int): Boolean =
    codePoint in '0'.code..'9'.code ||
        codePoint in 'a'.code..'f'.code ||
        codePoint in 'A'.code..'F'.code

// `Integer.toHexString(hashCode())` drops leading zeros, so an identity hash is almost always this
// many hex digits. Requiring it rejects short `@<word>` tokens that happen to be all hex (`@cafe`,
// `@dead`), which would otherwise be misread as churn.
private const val MIN_IDENTITY_HASH_HEX_DIGITS = 6

/**
 * Whether the divergence at [i] in [cp] sits inside an `@<hex>` identity hash. [i] must itself be a
 * hex digit — so a divergence that lands just *after* a hash, or one that is not hex at all, is not
 * misread as churn — and the hex run it belongs to must be immediately preceded by '@' and at least
 * [MIN_IDENTITY_HASH_HEX_DIGITS] long.
 */
private fun isInsideIdentityHash(cp: IntArray, i: Int): Boolean {
  if (i >= cp.size || !isHex(cp[i])) {
    return false
  }
  var start = i
  while (start > 0 && isHex(cp[start - 1])) {
    start--
  }
  if (start == 0 || cp[start - 1] != '@'.code) {
    return false
  }
  var end = i
  while (end + 1 < cp.size && isHex(cp[end + 1])) {
    end++
  }
  return end - start + 1 >= MIN_IDENTITY_HASH_HEX_DIGITS
}

private fun typeNameOf(clazz: Class<*>): String {
  val simple = clazz.simpleName
  if (simple.isNotEmpty()) {
    return simple
  }
  // Lambdas and other synthetic classes have no simple name; the binary name is the only handle.
  return clazz.name.substringAfterLast('.')
}

// Not cached: this only runs per changed dep behind `collectBindReasons`, so the reflection is
// cheaper than a Class-keyed map that would outlive the classes it holds.
private fun comparesByIdentity(clazz: Class<*>): Boolean =
    // Mirrors the fallbacks in `areObjectsEquivalent`: anything it compares structurally must not
    // be reported as identity-compared, or we would blame a dep the framework handles correctly.
    when {
      Equivalence::class.java.isAssignableFrom(clazz) -> false
      clazz.isArray -> false
      Collection::class.java.isAssignableFrom(clazz) -> false
      else ->
          try {
            clazz.getMethod("equals", Any::class.java).declaringClass == Any::class.java
          } catch (e: NoSuchMethodException) {
            true
          }
    }

/** The full text of a value. Compared to decide whether anything actually changed. */
private fun renderValue(value: Any?): String {
  if (value == null) {
    return "null"
  }
  // A Kotlin function's `toString` asks kotlin-reflect to render its signature. Where that library
  // is present, its first use loads hundreds of classes on the calling (main) thread, stalling the
  // mount that triggered this report for close to a second. The arity is enough here: functions
  // are flagged by identity and the type name already identifies the lambda. Reading it stays
  // inside the guard: a reflective function object can throw from `arity`.
  return try {
    if (value is FunctionBase<*>) "Function${value.arity}" else value.toString()
  } catch (e: Throwable) {
    "<${typeNameOf(value.javaClass)}: toString threw ${typeNameOf(e.javaClass)}>"
  }
}

/** [raw] shortened for the report, quoted for char sequences so blank values stay visible. */
private fun displayValue(value: Any?, raw: String): String {
  // Truncate before quoting so the quotes survive on an over-long value.
  val truncated = if (raw.length <= MAX_VALUE_LENGTH) raw else raw.take(MAX_VALUE_LENGTH) + "…"
  return if (value is CharSequence) "\"$truncated\"" else truncated
}
