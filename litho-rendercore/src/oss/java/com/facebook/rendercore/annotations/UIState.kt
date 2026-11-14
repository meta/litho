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

package com.facebook.rendercore.annotations

/**
 * To be used with a RenderUnit: annotates that this field is mutable state that can be accessed on
 * the UI thread only. This should only be annotated on non-primitive fields because we want to make
 * sure to maintain the same instance when cloning.
 */
@Target(AnnotationTarget.FIELD) @Retention(AnnotationRetention.SOURCE) annotation class UIState
