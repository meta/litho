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

package com.facebook.litho

/** Specify a position to anchor a tooltip on a component. */
enum class TooltipPosition {
  CENTER,
  BOTTOM_LEFT,
  CENTER_LEFT,
  TOP_LEFT,
  CENTER_TOP,
  TOP_RIGHT,
  CENTER_RIGHT,
  BOTTOM_RIGHT,
  CENTER_BOTTOM,
}
