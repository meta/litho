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

package com.facebook.litho.config

/**
 * What a LithoView unmounts when it is detached from the window.
 *
 * Every detach unbinds the mounted content and dispatches invisible events. Unmounting on top of
 * that frees content that fills its host, which incremental mount never sees leave the viewport. It
 * costs a full remount if the view comes back.
 *
 * Unmounting runs incremental mount against an empty viewport, so it only happens when incremental
 * mount is enabled, and it skips temporary detaches and views with transient state. Components
 * excluded from incremental mount stay mounted.
 */
enum class UnmountOnDetach {
  /** Nothing. Content stays mounted until the tree is released or a list recycles the row. */
  Never,

  /** Everything incremental mount can unmount (see above). */
  Always,
}
