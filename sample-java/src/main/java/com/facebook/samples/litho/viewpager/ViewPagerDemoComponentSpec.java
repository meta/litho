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

package com.facebook.samples.litho.java.viewpager;

import android.graphics.Color;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import com.facebook.litho.Column;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.InterceptTouchEvent;
import com.facebook.litho.StateValue;
import com.facebook.litho.Wrapper;
import com.facebook.litho.annotations.FromEvent;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnCreateInitialState;
import com.facebook.litho.annotations.OnCreateLayout;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.annotations.State;
import com.facebook.litho.sections.SectionContext;
import com.facebook.litho.sections.common.DataDiffSection;
import com.facebook.litho.sections.common.RenderEvent;
import com.facebook.litho.sections.widget.RecyclerCollectionEventsController;
import com.facebook.litho.sections.widget.ViewPagerComponent;
import com.facebook.litho.widget.ComponentRenderInfo;
import com.facebook.litho.widget.RenderInfo;
import com.facebook.yoga.YogaEdge;
import com.facebook.yoga.YogaPositionType;
import java.util.Arrays;
import java.util.List;

@LayoutSpec
public class ViewPagerDemoComponentSpec {

  public static final List<Model> MODELS =
      Arrays.asList(
          new Model("This is a ViewPager Demo", "You can swipe to navigate", hsvToColor(0, .5f, 1)),
          new Model(
              "You can also tap left/right",
              "This isn't built into ViewPagerComponent but you can use a GestureDetector like in"
                  + " this demo!",
              hsvToColor(60, .5f, 1)),
          new Model(
              "A ViewPagerComponent lets you specify only the *initial* page index as a prop",
              null,
              hsvToColor(120, .5f, 1)),
          new Model(
              "RecyclerCollectionEventsController will let you change the page once the ViewPager"
                  + " is on screen",
              "Check out the code for this demo to see how",
              hsvToColor(180, .5f, 1)),
          new Model(
              "This is the end of the ViewPager",
              "Double-tap in the middle to go back to the start",
              hsvToColor(240, .5f, 1)));

  @OnCreateInitialState
  static void OnCreateInitialState(
      final ComponentContext c,
      StateValue<RecyclerCollectionEventsController> eventsController,
      StateValue<GestureDetector> gestureDetector,
      StateValue<LeftRightTapGestureListener> gestureListener) {
    final RecyclerCollectionEventsController recyclerCollectionEventsController =
        new RecyclerCollectionEventsController();
    eventsController.set(recyclerCollectionEventsController);
    gestureListener.set(
        new LeftRightTapGestureListener() {
          @Override
          public void onClickLeft() {
            recyclerCollectionEventsController.requestScrollToPreviousPosition(false);
          }

          @Override
          public void onClickRight() {
            recyclerCollectionEventsController.requestScrollToNextPosition(false);
          }

          @Override
          public boolean onDoubleTap(MotionEvent e) {
            recyclerCollectionEventsController.requestScrollToPositionWithSnap(0);
            return true;
          }
        });
    gestureDetector.set(new GestureDetector(c.getAndroidContext(), gestureListener.get()));
  }

  @OnEvent(InterceptTouchEvent.class)
  static boolean onInterceptTouchEvent(
      ComponentContext c,
      @FromEvent View view,
      @FromEvent MotionEvent motionEvent,
      @State LeftRightTapGestureListener gestureListener,
      @State GestureDetector gestureDetector) {
    gestureListener.setViewWidth(view.getWidth());
    return gestureDetector.onTouchEvent(motionEvent);
  }

  @OnCreateLayout
  @SuppressWarnings("unchecked")
  static Component onCreateLayout(
      ComponentContext c, @State RecyclerCollectionEventsController eventsController) {

    return Column.create(c)
        .positionType(YogaPositionType.ABSOLUTE)
        .flexGrow(1)
        .child(
            Wrapper.create(c)
                .delegate(
                    new ViewPagerComponent<Model>(
                        DataDiffSection.<Model>create(new SectionContext(c))
                            .data(MODELS)
                            .renderEventHandler(ViewPagerDemoComponent.onRenderEvent(c))
                            .build(),
                        eventsController))
                .positionPx(YogaEdge.ALL, 0))
        .interceptTouchHandler(ViewPagerDemoComponent.onInterceptTouchEvent(c))
        .build();
  }

  @OnEvent(RenderEvent.class)
  static RenderInfo onRenderEvent(ComponentContext c, @FromEvent Model model) {
    return ComponentRenderInfo.create()
        .component(
            ViewPagerItemComponent.create(c)
                .bgColor(model.bgColor)
                .title(model.title)
                .subtitle(model.subtitle))
        .build();
  }

  static class Model {
    public final String title;
    public final @Nullable String subtitle;
    public final @ColorInt int bgColor;

    public Model(String title, @Nullable String subtitle, @ColorInt int bgColor) {
      this.title = title;
      this.subtitle = subtitle;
      this.bgColor = bgColor;
    }
  }

  abstract static class LeftRightTapGestureListener
      extends GestureDetector.SimpleOnGestureListener {

    private int mViewWidthPx = -1;

    public void setViewWidth(int widthPx) {
      mViewWidthPx = widthPx;
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
      if (mViewWidthPx == -1) {
        return false;
      }
      if (e.getX() < mViewWidthPx / 3) {
        onClickLeft();
        return true;
      } else if (e.getX() > 2 * mViewWidthPx / 3) {
        onClickRight();
        return true;
      }
      return false;
    }

    public abstract void onClickLeft();

    public abstract void onClickRight();
  }

  private static @ColorInt int hsvToColor(int hue, float sat, float value) {
    return Color.HSVToColor(new float[] {hue, sat, value});
  }
}
