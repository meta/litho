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

package com.facebook.rendercore;

/**
 * An accessibility delegate whose {@code performAccessibilityAction} already offers inherited
 * actions to the nearest {@link AccessibilityActionProvider}. A node owner that wraps one returns
 * the wrapped result for an inherited action instead of offering the action again.
 */
public interface InheritedAccessibilityActionOwner {}
