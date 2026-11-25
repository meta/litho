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

package com.facebook.litho.annotations

/**
 * Marker for the Litho hooks.
 *
 * Carrying this annotation is what makes a function a hook, and it drives both hook rules:
 * `KComponentHookCallsite` requires the call to come from `render()`, another `@Hook` function, or
 * a lambda whose receiver is marked [RunsInOwnHookScope], and `KComponentHookInDeferredLambda`
 * requires it to run synchronously during render. Neither rule restricts control flow — the
 * compiler plugin gives each call site a structural identity, so hooks may be called in conditions,
 * loops, and after early returns. Two consequences are worth knowing: a hook's state lives exactly
 * as long as the hook keeps being called, so a hook that stops running loses its state and starts
 * from its initial value when it runs again; and repeated invocations of one call site are
 * positional unless wrapped in `withNewScope` with a stable product key. See the Hooks Overview
 * doc.
 *
 * Calling a hook requires opting in, and the only supported way to do that is the `-opt-in`
 * argument that `litho_hook_identity_decorator` emits alongside the Litho compiler plugin. A module
 * that calls a hook from an un-annotated function without the plugin therefore fails to compile
 * instead of throwing on its first render — a compiler plugin cannot detect its own absence, so the
 * always-present annotation has to carry the requirement.
 *
 * Never hand-write the opt-in. `@OptIn(Hook::class)` or a manual `-opt-in` argument silences the
 * requirement without adding the plugin, which is precisely the state this marker exists to
 * prevent. Both are what kotlinc suggests when the requirement fires, which is why [message] spells
 * out the real fix — the diagnostic is the only place the reader is looking.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "This module calls a Litho hook but is not built with the Litho compiler plugin, so its " +
            "hooks would get no identity and throw on first render. Fix the build, not the call " +
            "site: the plugin and this opt-in are injected together by " +
            "`litho_hook_identity_decorator`. Do NOT silence this by adding `@OptIn`, and do not " +
            "chase it up the call chain by annotating callers `@Hook` -- both compile clean and " +
            "still crash at runtime. (Annotating one helper `@Hook` is the right fix for the " +
            "separate `HOOK_CALLSITE_MISUSE` error, which only fires where the plugin is already " +
            "loaded.) Ask in the Litho support group if the target should already have the plugin.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class Hook
