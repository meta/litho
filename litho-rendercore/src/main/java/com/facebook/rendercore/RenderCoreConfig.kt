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

/** Config class to enable or disable specific features. */
object RenderCoreConfig {

  /**
   * Defaults to the presence of an
   * <pre>IS_TESTING</pre>
   *
   * system property at startup but can be overridden at runtime.
   *
   * Note: we read via `System.getProperties().getProperty(...)` rather than
   * `System.getProperty(...)` to avoid invoking `java.lang.System.checkKey`, which on certain
   * OEM-modified libcore implementations (e.g. Samsung Android 8.1, SM-J710FQ) throws
   * `IllegalArgumentException("key can't be empty")` even for non-empty caller literals.
   * `Properties.getProperty` delegates to `Hashtable.get` and never invokes the vendor-patched
   * validator. Reads the same backing `Properties` store, so semantics on AOSP and
   * `System.setProperty` writes are unchanged.
   */
  @JvmField
  var isEndToEndTestRun: Boolean = System.getProperties().getProperty("IS_TESTING") != null

  /**
   * When enabled, `IncrementalMountGapWorker.get` only resolves the display frame interval when a
   * worker actually has to be constructed. Resolving it reads `Display.refreshRate`, which costs
   * more than 1ms on some devices, so doing it on every call — including the ones that just return
   * the already initialised global worker — can drop frames.
   */
  @JvmField var computeGapWorkerFrameIntervalLazily: Boolean = false

  /**
   * When enabled, `IncrementalMountGapWorker` re-reads the display frame interval each frame
   * instead of keeping the one captured when the process-wide worker was built. Requires API 30.
   */
  @JvmField var refreshGapWorkerFrameIntervalPerFrame: Boolean = false

  /**
   * When enabled, `IncrementalMountGapWorker` calls its single gap-worker extension directly
   * instead of routing each item through `MountDelegate`'s extension fan-out.
   */
  @JvmField var useGapWorkerExtensionState: Boolean = false

  /**
   * Enabling this config will fix the issue that component matches host view size will never be
   * unmounted when getting out of the viewport.
   */
  @JvmField var shouldEnableIMFix: Boolean = false

  /** Enabling unmounting components reversely from bottom to top. */
  @JvmField var processFromLeafNode: Boolean = false

  /** This flag is used to disable incremental unmounting in LithoView. */
  @JvmField var disableIncrementalUnmounting: Boolean = false

  /** This flag is used to enable automatic removal of View listeners from ComponentHost. */
  @JvmField var removeComponentHostListeners: Boolean = false

  /**
   * This flag is used to clear translationX/Y left by X/Y transitions on a Host before it is
   * released to the pool, so it isn't drawn offset after being mounted elsewhere.
   */
  @JvmField var resetHostTranslationOnRecycle: Boolean = false

  /** Flag to use getOffsetForAdvance in TextMeasurementUtils to prevent ANRs. */
  @JvmField var usePerformantTruncation: Boolean = false

  /** Flag to re-use compatible TextLayouts in truncation scenarios. */
  @JvmField var useTruncationCaching: Boolean = false

  /**
   * Before Android 7, `InputMethodManager` can call `finishComposingText` on the previously focused
   * text input from whichever thread calls into it. When this returns true, text inputs post those
   * calls to their own thread (the main thread, in an Activity's window), as Android 7 does. It's
   * read each time a text input creates its input connection, so an app can switch it within a
   * session.
   */
  @JvmField var postFinishComposingTextToMainThread: () -> Boolean = { false }

  /**
   * Master switch for debug-only tooling that is too costly to run in production. When enabled,
   * RenderCore:
   * - logs mount statistics ([MountState.mountStats]);
   * - populates the debug descriptions on [BinderId]s; and
   * - reads the debug-only [RenderUnit.debugKey] when building `IncrementalMountOutput`s. Some
   *   RenderUnits compute this key by recursively rebuilding a string (e.g. Litho under structural
   *   global keys), so reading it for every output is wasted work in prod, where the output's debug
   *   key is unused.
   *
   * Defaults to debug builds; a host can enable it in internal release builds that surface debug
   * tooling.
   */
  @JvmField var isDebugModeEnabled: Boolean = BuildConfig.DEBUG

  /**
   * When enabled, a binder that re-runs reports which dependency changed. Costs a `toString` per
   * changed dependency.
   *
   * Defaults to debug builds. Assigned from [BuildConfig.DEBUG] rather than from
   * [isDebugModeEnabled]: a property initialiser would snapshot that field before a host sets it,
   * leaving this silently off wherever the two are turned on together.
   */
  @JvmField var collectBindReasons: Boolean = BuildConfig.DEBUG

  /** Skip calling [View.setPadding] if the padding values have not changed. */
  @JvmField var skipRedundantSetPadding: Boolean = true

  /**
   * When true, [MountContentPools.DefaultContentPool.maybePreallocateContent] will skip creating
   * and pooling mount content ahead of time. When false (default), preallocation works normally.
   */
  @JvmField var disableDefaultPoolPreallocation: Boolean = false

  /**
   * When true, on API 25 and lower a ViewGroup released by [MountContentPools.recycle] goes back to
   * its pool only once a frame has been drawn since its release, so the mount that released it
   * can't reuse it.
   *
   * Before Android 8, when a frame drops the last parent of a View, hwui frees that View's display
   * list right away and walks its children, mixing lists recorded this frame with lists from the
   * frame before. A mount that reuses a released host above one of its former ancestors, and drops
   * the View between them, turns that walk into a loop that overflows RenderThread's stack.
   */
  @JvmField var holdReleasedViewGroupsUntilDrawn: Boolean = false

  /**
   * Caches the reflected instance field list per class in
   * [com.facebook.rendercore.utils.hasEquivalentFields].
   *
   * `Class.getDeclaredFields` clones the array and every `Field` in it on every call, so an
   * uncached comparison allocates proportionally to the field count before it reads a value, and
   * toggles accessibility on each field as it goes. When false, the field list is resolved per
   * comparison, which is what shipped before the cache existed.
   *
   * Both arms return the same answer for every pair; this exists to measure the difference.
   */
  @JvmField var cacheEquivalenceFields: Boolean = false
}
