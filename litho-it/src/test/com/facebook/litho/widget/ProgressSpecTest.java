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

package com.facebook.litho.widget;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.LithoView;
import com.facebook.litho.it.R;
import com.facebook.litho.testing.helper.ComponentTestHelper;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDrawable;

/** Tests {@link ProgressPrimitiveComponent} */
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ProgressSpecTest {
  private ComponentContext mContext;

  @Before
  public void setup() {
    mContext = new ComponentContext(getApplicationContext());
  }

  @Test
  public void testDefault() {
    LithoView view = getMountedView();
    assertThat(view.getMeasuredWidth()).isGreaterThan(0);
    assertThat(view.getMeasuredHeight()).isGreaterThan(0);
  }

  @Test
  public void testUnsetSize() {
    LithoView view = getMountedView();

    view.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

    assertThat(view.getMeasuredWidth()).isEqualTo(ProgressPrimitiveComponent.DEFAULT_SIZE);
    assertThat(view.getMeasuredHeight()).isEqualTo(ProgressPrimitiveComponent.DEFAULT_SIZE);
  }

  @Test
  public void testIndeterminateDrawableResolvedFromDefStyleRes() {
    Component progress =
        ProgressPrimitiveComponent.create(
            Color.TRANSPARENT, null, null, null, null, 0, R.style.ProgressStyleTest);
    LithoView view = ComponentTestHelper.mountComponent(mContext, progress);

    ProgressView progressView = findProgressView(view);
    Drawable indeterminateDrawable = progressView.getIndeterminateDrawable();
    assertThat(indeterminateDrawable).isNotNull();
    ShadowDrawable shadowDrawable = Shadow.extract(indeterminateDrawable);
    assertThat(shadowDrawable.getCreatedFromResId()).isEqualTo(R.drawable.test_bg);
  }

  private LithoView getMountedView() {
    Component progress =
        ProgressPrimitiveComponent.create(Color.TRANSPARENT, null, null, null, null);

    return ComponentTestHelper.mountComponent(mContext, progress);
  }

  private static ProgressView findProgressView(LithoView view) {
    for (int i = 0; i < view.getMountItemCount(); i++) {
      Object content = view.getMountItemAt(i).getContent();
      if (content instanceof ProgressView) {
        return (ProgressView) content;
      }
    }
    throw new AssertionError("No ProgressView was mounted");
  }
}
