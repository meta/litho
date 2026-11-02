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

package com.facebook.litho.sections.widget;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;
import com.facebook.infer.annotation.Nullsafe;

/**
 * This implementation of {@link RecyclerView.ItemAnimator} disables animations of item change
 * events.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class NoUpdateItemAnimator extends DefaultItemAnimator {

  @SuppressWarnings("this-escape") // ctor calls setSupportsChangeAnimations (litho pattern)
  public NoUpdateItemAnimator() {
    super();
    setSupportsChangeAnimations(false);
  }
}
