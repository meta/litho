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

package com.facebook.litho.fresco;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import com.facebook.drawee.drawable.ForwardingDrawable;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.interfaces.DraweeHierarchy;
import com.facebook.drawee.view.DraweeHolder;
import com.facebook.litho.ImageContent;
import com.facebook.litho.Touchable;
import java.util.Collections;
import java.util.List;

/** A Drawable that draws images using Drawee. */
public class DraweeDrawable<DH extends DraweeHierarchy> extends ForwardingDrawable
    implements ImageContent, Touchable {

  private final Drawable mNoOpDrawable = new NoOpDrawable();

  private final DraweeHolder<DH> mDraweeHolder;

  @SuppressWarnings("this-escape")
  public DraweeDrawable(Context context, DH draweeHierarchy) {
    super(null);

    setCurrent(mNoOpDrawable);
    mDraweeHolder = DraweeHolder.create(draweeHierarchy, context);
  }

  public void mount() {
    setDrawable(mDraweeHolder.getTopLevelDrawable());
    mDraweeHolder.onAttach();
  }

  public void unmount() {
    mDraweeHolder.onDetach();
    setDrawable(mNoOpDrawable);
  }

  @Override
  public void draw(Canvas canvas) {
    mDraweeHolder.onDraw();
    super.draw(canvas);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event, View host) {
    return mDraweeHolder.onTouchEvent(event);
  }

  @Override
  public boolean shouldHandleTouchEvent(MotionEvent event) {
    return true;
  }

  @Override
  public List<Drawable> getImageItems() {
    return Collections.<Drawable>singletonList(this);
  }

  public DraweeController getController() {
    return mDraweeHolder.getController();
  }

  public DH getDraweeHierarchy() {
    return mDraweeHolder.getHierarchy();
  }

  public void setController(DraweeController controller) {
    if (mDraweeHolder.getController() == controller) {
      return;
    }
    mDraweeHolder.setController(controller);
  }
}
