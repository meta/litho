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
import static com.facebook.litho.SizeSpec.EXACTLY;
import static com.facebook.litho.SizeSpec.UNSPECIFIED;
import static com.facebook.litho.SizeSpec.makeSizeSpec;
import static com.facebook.litho.widget.ComponentRenderInfo.create;
import static com.facebook.litho.widget.RecyclerBinderTest.NO_OP_CHANGE_SET_COMPLETE_CALLBACK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentTree;
import com.facebook.litho.EventHandler;
import com.facebook.litho.Size;
import com.facebook.litho.SizeSpec;
import com.facebook.litho.StyleCompat;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.litho.widget.collection.CrossAxisWrapMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/** Tests for manually specifying estimatedViewportCount to {@link RecyclerBinder} */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RecyclerBinderManualRangeTest {

  @Rule public ExpectedException mExpectedException = ExpectedException.none();

  private ComponentContext mComponentContext;
  private ShadowLooper mLayoutThreadShadowLooper;

  @Before
  public void setup() {
    mComponentContext = new ComponentContext(getApplicationContext());
    mComponentContext.getAndroidContext().setTheme(0);
    mLayoutThreadShadowLooper = Shadow.extract(ComponentTree.getDefaultLayoutThreadLooper());
  }

  private void runToEndOfTasks() {
    mLayoutThreadShadowLooper.runToEndOfTasks();
  }

  @After
  public void tearDown() {
    runToEndOfTasks();
  }

  @Test
  public void testMeasureAfterAddItems() {
    final int widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY);
    final int heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY);

    final RecyclerBinder recyclerBinder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().estimatedViewportCount(1).rangeRatio(0.5f).build())
            .build(mComponentContext);

    final List<RenderInfo> initialComponents = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      initialComponents.add(
          create()
              .component(new SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build()))
              .build());
    }
    recyclerBinder.insertRangeAt(0, initialComponents);
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK);

    recyclerBinder.measure(new Size(), widthSpec, heightSpec, null);

    for (int i = 0; i < initialComponents.size(); i++) {
      final ComponentTreeHolder holder = recyclerBinder.getComponentTreeHolderAt(i);
      assertThat(holder.hasCompletedLatestLayout()).describedAs("Holder " + i).isFalse();
    }

    runToEndOfTasks();

    for (int i = 0; i < 2; i++) {
      final ComponentTreeHolder holder = recyclerBinder.getComponentTreeHolderAt(i);
      assertThat(holder.hasCompletedLatestLayout()).describedAs("Holder " + i).isTrue();
      assertThat(holder.isTreeValid()).describedAs("Holder " + i).isTrue();
      RecyclerBinderTest.assertHasCompatibleLayout(
          recyclerBinder, i, makeSizeSpec(200, EXACTLY), makeSizeSpec(0, UNSPECIFIED));
    }

    for (int i = 2; i < initialComponents.size(); i++) {
      final ComponentTreeHolder holder = recyclerBinder.getComponentTreeHolderAt(i);
      assertThat(holder.hasCompletedLatestLayout()).describedAs("Holder " + i).isFalse();
      assertThat(holder.isTreeValid()).describedAs("Holder " + i).isFalse();
    }
  }

  @Test
  public void testAddItemsAfterMeasure() {
    final int widthSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY);
    final int heightSpec = SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY);

    final RecyclerBinder recyclerBinder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(
                RecyclerBinderConfig.create().estimatedViewportCount(1).rangeRatio(0.5f).build())
            .build(mComponentContext);

    recyclerBinder.measure(new Size(), widthSpec, heightSpec, null);

    final List<RenderInfo> initialComponents = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      initialComponents.add(
          create()
              .component(new SimpleMountSpecTester(StyleCompat.widthPx(100).heightPx(100).build()))
              .build());
    }
    recyclerBinder.insertRangeAt(0, initialComponents);
    recyclerBinder.notifyChangeSetComplete(true, NO_OP_CHANGE_SET_COMPLETE_CALLBACK);

    for (int i = 0; i < initialComponents.size(); i++) {
      final ComponentTreeHolder holder = recyclerBinder.getComponentTreeHolderAt(i);
      assertThat(holder.hasCompletedLatestLayout()).describedAs("Holder " + i).isFalse();
    }

    runToEndOfTasks();

    for (int i = 0; i < 2; i++) {
      final ComponentTreeHolder holder = recyclerBinder.getComponentTreeHolderAt(i);
      assertThat(holder.hasCompletedLatestLayout()).describedAs("Holder " + i).isTrue();
      assertThat(holder.isTreeValid()).describedAs("Holder " + i).isTrue();
      RecyclerBinderTest.assertHasCompatibleLayout(
          recyclerBinder, i, makeSizeSpec(200, EXACTLY), makeSizeSpec(0, UNSPECIFIED));
    }

    for (int i = 2; i < initialComponents.size(); i++) {
      final ComponentTreeHolder holder = recyclerBinder.getComponentTreeHolderAt(i);
      assertThat(holder.hasCompletedLatestLayout()).describedAs("Holder " + i).isFalse();
      assertThat(holder.isTreeValid()).describedAs("Holder " + i).isFalse();
    }
  }

  @Test
  public void testCanMeasureIsUnsupported() {
    mExpectedException.expect(RuntimeException.class);
    mExpectedException.expectMessage(
        "Cannot use manual estimated viewport count when the RecyclerBinder needs an item to"
            + " determine its size!");

    final RecyclerBinder recyclerBinder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(
                RecyclerBinderConfig.create()
                    .crossAxisWrapMode(CrossAxisWrapMode.MatchFirstChild)
                    .estimatedViewportCount(1)
                    .rangeRatio(.05f)
                    .build())
            .build(mComponentContext);

    recyclerBinder.measure(
        new Size(),
        SizeSpec.makeSizeSpec(200, UNSPECIFIED),
        SizeSpec.makeSizeSpec(200, SizeSpec.EXACTLY),
        mock(EventHandler.class));
  }
}
