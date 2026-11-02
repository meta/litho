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

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import com.facebook.infer.annotation.Nullsafe;

/**
 * This implementation of {@link RecyclerView.ItemAnimator} disables all animations in a {@link
 * RecyclerView}.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class NotAnimatedItemAnimator extends SimpleItemAnimator {

  @SuppressWarnings("this-escape") // ctor calls overridable setter (litho pattern)
  public NotAnimatedItemAnimator() {
    super();
    setSupportsChangeAnimations(false);
  }

  @Override
  public void runPendingAnimations() {
    // nothing
  }

  @Override
  public boolean animateRemove(RecyclerView.ViewHolder holder) {
    dispatchRemoveStarting(holder);
    dispatchRemoveFinished(holder);
    return true;
  }

  @Override
  public boolean animateAdd(RecyclerView.ViewHolder holder) {
    dispatchAddStarting(holder);
    dispatchAddFinished(holder);
    return true;
  }

  @Override
  public boolean animateMove(
      RecyclerView.ViewHolder holder, int fromX, int fromY, int toX, int toY) {
    dispatchMoveStarting(holder);
    dispatchMoveFinished(holder);
    return true;
  }

  @Override
  public boolean animateChange(
      RecyclerView.ViewHolder oldHolder,
      RecyclerView.ViewHolder newHolder,
      int fromLeft,
      int fromTop,
      int toLeft,
      int toTop) {
    // Avoid calling animation finish for the item twice,
    // preventing "isRecyclable decremented below 0" error
    if (oldHolder != newHolder) {
      dispatchChangeStarting(oldHolder, true);
      dispatchChangeFinished(oldHolder, true);
    }
    dispatchChangeStarting(newHolder, false);
    dispatchChangeFinished(newHolder, false);
    return true;
  }

  @Override
  public void endAnimation(RecyclerView.ViewHolder item) {}

  @Override
  public void endAnimations() {}

  @Override
  public boolean isRunning() {
    return false;
  }
}
