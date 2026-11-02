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
import static org.robolectric.annotation.LooperMode.Mode.LEGACY;

import android.os.HandlerThread;
import android.os.Looper;
import com.facebook.litho.Column;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentTree;
import com.facebook.litho.RenderResult;
import com.facebook.litho.ResolveContext;
import com.facebook.litho.Row;
import com.facebook.litho.Size;
import com.facebook.litho.SizeSpec;
import com.facebook.litho.SpecGeneratedComponent;
import com.facebook.litho.testing.ThreadTestingUtils;
import com.facebook.litho.testing.inlinelayoutspec.InlineLayoutSpec;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.rendercore.RunnableHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

@LooperMode(LEGACY)
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ComponentWarmerTest {

  private ComponentContext mContext;
  private ComponentRenderInfo mComponentRenderInfo;
  private ComponentRenderInfo mPrepareComponentRenderInfo;
  private ShadowLooper mLayoutThreadShadowLooper;
  private int mWidthSpec;
  private int mHeightSpec;

  @Before
  public void setup() {
    mContext = new ComponentContext(getApplicationContext());
    mComponentRenderInfo =
        ComponentRenderInfo.create()
            .component(new SimpleMountSpecTester())
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();
    mPrepareComponentRenderInfo =
        ComponentRenderInfo.create().component(new SimpleMountSpecTester()).build();
    mLayoutThreadShadowLooper = Shadow.extract(ComponentTree.getDefaultLayoutThreadLooper());
    mWidthSpec = SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY);
    mHeightSpec = SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY);
  }

  private void runToEndOfTasks() {
    mLayoutThreadShadowLooper.runToEndOfTasks();
  }

  @Test
  public void testCreateFromRecyclerBinder() {
    final RecyclerBinder binder = new RecyclerBinder.Builder().build(mContext);

    final ComponentWarmer warmer = new ComponentWarmer(binder);

    assertThat(binder.getComponentWarmer()).isEqualTo(warmer);
    assertThat(warmer.getFactory()).isNotNull();
  }

  @Test
  public void testConsumeFromRecyclerBinder() {
    final RecyclerBinder binder = new RecyclerBinder.Builder().build(mContext);

    final ComponentWarmer warmer = new ComponentWarmer(binder);
    warmer.prepare("tag1", mPrepareComponentRenderInfo, null);

    assertThat(warmer.consume("tag1")).isNotNull();
    assertThat(warmer.consume("tag1")).isNull();
  }

  @Test
  public void testIsCachedReflectsCacheStateNonDestructively() {
    final RecyclerBinder binder = new RecyclerBinder.Builder().build(mContext);

    final ComponentWarmer warmer = new ComponentWarmer(binder);

    assertThat(warmer.isCached("tag1")).isFalse();

    warmer.prepare("tag1", mPrepareComponentRenderInfo, null);

    assertThat(warmer.isCached("tag1")).isTrue();
    // Non-destructive: repeated checks keep the entry, and it is still consumable.
    assertThat(warmer.isCached("tag1")).isTrue();
    assertThat(warmer.consume("tag1")).isNotNull();
    assertThat(warmer.isCached("tag1")).isFalse();
  }

  @Test
  public void testPrepareForRecyclerBinder() {
    final RecyclerBinder binder = new RecyclerBinder.Builder().build(mContext);

    final ComponentWarmer warmer = new ComponentWarmer(binder);
    ComponentWarmer.Cache cache = warmer.getCache();

    warmer.prepare("tag1", mPrepareComponentRenderInfo, null);

    final ComponentTreeHolder cachedCTH = cache.get("tag1");

    binder.insertItemAt(0, mComponentRenderInfo);

    assertThat(binder.getComponentTreeHolderAt(0)).isEqualTo(cachedCTH);
  }

  @Test
  public void testPrepareAsyncForRecyclerBinder() {
    final RecyclerBinder binder = new RecyclerBinder.Builder().build(mContext);

    final ComponentWarmer warmer = new ComponentWarmer(binder);
    ComponentWarmer.Cache cache = warmer.getCache();

    warmer.prepareAsync("tag1", mPrepareComponentRenderInfo);

    final ComponentTreeHolder cachedCTH = cache.get("tag1");

    binder.insertItemAt(0, mComponentRenderInfo);

    assertThat(binder.getComponentTreeHolderAt(0)).isEqualTo(cachedCTH);
  }

  @Test
  public void testCancelDuringPrepare() {
    final RecyclerBinder binder = new RecyclerBinder.Builder().build(mContext);

    binder.measure(
        new Size(),
        SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY),
        SizeSpec.makeSizeSpec(100, SizeSpec.EXACTLY),
        null);

    final ComponentWarmer warmer = new ComponentWarmer(binder);

    final CountDownLatch waitToResolveChild = new CountDownLatch(1);
    final CountDownLatch waitToCancel = new CountDownLatch(1);
    final CountDownLatch waitToAssert = new CountDownLatch(1);

    final boolean[] childrenResolved = {false, false};

    final Component childComponent1 =
        new InlineLayoutSpec() {
          @Override
          protected Component onCreateLayout(ComponentContext c) {
            childrenResolved[0] = true;
            waitToResolveChild.countDown();

            ThreadTestingUtils.failSilentlyIfInterrupted(
                () -> waitToCancel.await(7, TimeUnit.SECONDS));

            return Row.create(c).build();
          }
        };

    final Component childComponent2 =
        new InlineLayoutSpec() {
          @Override
          protected Component onCreateLayout(ComponentContext c) {
            childrenResolved[1] = true;
            return Row.create(c).build();
          }
        };

    final Component component =
        new InlineLayoutSpec() {
          @Override
          protected Component onCreateLayout(ComponentContext c) {
            return Column.create(c).child(childComponent1).child(childComponent2).build();
          }
        };

    runOnBackgroundThreadSync(
        new Runnable() {
          @Override
          public void run() {
            warmer.prepare("tag1", ComponentRenderInfo.create().component(component).build(), null);
            waitToAssert.countDown();
          }
        });

    ThreadTestingUtils.failSilentlyIfInterrupted(
        () -> {
          waitToResolveChild.await(5, TimeUnit.SECONDS);
          warmer.cancelPrepare("tag1");
          waitToCancel.countDown();
        });

    ThreadTestingUtils.failSilentlyIfInterrupted(() -> waitToAssert.await(5, TimeUnit.SECONDS));

    assertThat(childrenResolved[0]).isTrue();
    assertThat(childrenResolved[1]).isFalse();
    assertThat(warmer.consume("tag1")).isNull();
  }

  @Test
  public void testLazyComponentWarmerCreation() {
    final ComponentWarmer warmer = new ComponentWarmer();
    assertThat(warmer.isReady()).isFalse();

    RecyclerBinder binder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().componentWarmer(warmer).build())
            .build(mContext);

    assertThat(warmer.isReady()).isFalse();

    binder.measure(new Size(), mWidthSpec, mHeightSpec, null);

    assertThat(warmer.isReady()).isTrue();
  }

  @Test
  public void testLazySyncPrepareAddToPending() {
    final ComponentWarmer warmer = new ComponentWarmer();
    final ComponentRenderInfo renderInfo =
        ComponentRenderInfo.create()
            .component(new SimpleMountSpecTester())
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    warmer.prepare("tag1", renderInfo, null);
    assertThat(warmer.getPending().size()).isEqualTo(1);
    assertThat(warmer.getPending().poll()).isEqualTo(renderInfo);
    assertThat(warmer.getCache().get("tag1")).isNull();

    assertThat(renderInfo.getCustomAttribute(ComponentWarmer.COMPONENT_WARMER_TAG))
        .isEqualTo("tag1");
  }

  @Test
  public void testLazySyncPrepareWithHandlerAddToPending() {
    final ComponentWarmer warmer = new ComponentWarmer();
    final ComponentRenderInfo renderInfo =
        ComponentRenderInfo.create()
            .component(new SimpleMountSpecTester())
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();
    final RunnableHandler handler = new RunnableHandler.DefaultHandler(Looper.myLooper());

    warmer.prepare("tag1", renderInfo, null, handler);
    assertThat(warmer.getPending().size()).isEqualTo(1);
    assertThat(warmer.getPending().poll()).isEqualTo(renderInfo);
    assertThat(warmer.getCache().get("tag1")).isNull();
    assertThat(renderInfo.getCustomAttribute(ComponentWarmer.COMPONENT_WARMER_TAG))
        .isEqualTo("tag1");
    assertThat(renderInfo.getCustomAttribute(ComponentWarmer.COMPONENT_WARMER_PREPARE_HANDLER))
        .isEqualTo(handler);
  }

  @Test
  public void testLazyAsyncPrepareAddToPending() {
    final ComponentWarmer warmer = new ComponentWarmer();

    final ComponentRenderInfo renderInfo =
        ComponentRenderInfo.create()
            .component(new SimpleMountSpecTester())
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    warmer.prepareAsync("tag1", renderInfo);
    assertThat(warmer.getCache().get("tag1")).isNull();
    assertThat(warmer.getPending().size()).isEqualTo(1);
    assertThat(warmer.getPending().poll()).isEqualTo(renderInfo);

    assertThat(renderInfo.getCustomAttribute(ComponentWarmer.COMPONENT_WARMER_TAG))
        .isEqualTo("tag1");
  }

  @Test
  public void testLazySyncPrepare() {
    final ComponentWarmer warmer = new ComponentWarmer();

    final TestComponent component1 = new TestComponent("tag1");
    final ComponentRenderInfo renderInfo1 =
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    final TestComponent component2 = new TestComponent("tag2");
    final ComponentRenderInfo renderInfo2 =
        ComponentRenderInfo.create()
            .component(component2)
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    warmer.prepare("tag1", renderInfo1, null);
    warmer.prepare("tag2", renderInfo2, null);

    RecyclerBinder binder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().componentWarmer(warmer).build())
            .build(mContext);

    assertThat(warmer.getPending().size()).isEqualTo(2);
    assertThat(warmer.getCache().get("tag1")).isNull();
    assertThat(warmer.getCache().get("tag2")).isNull();

    binder.measure(new Size(), mWidthSpec, mHeightSpec, null);

    assertThat(warmer.getPending()).isEmpty();

    final ComponentTreeHolder holder1 = warmer.getCache().get("tag1");
    assertThat(holder1).isNotNull();
    assertThat(holder1.isTreeValid()).isTrue();

    final ComponentTreeHolder holder2 = warmer.getCache().get("tag2");
    assertThat(holder2).isNotNull();
    assertThat(holder2.isTreeValid()).isTrue();

    runToEndOfTasks();
    assertThat(component1.ranLayout.get()).isTrue();
    assertThat(component2.ranLayout.get()).isTrue();
  }

  @Test
  public void testLazySyncPrepareWithHandler() {
    final ComponentWarmer warmer = new ComponentWarmer();

    final TestComponent component1 = new TestComponent("tag1");
    final ComponentRenderInfo renderInfo1 =
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    final TestComponent component2 = new TestComponent("tag2");
    final ComponentRenderInfo renderInfo2 =
        ComponentRenderInfo.create()
            .component(component2)
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    final HandlerThread handlerThread = new HandlerThread("testt1");
    handlerThread.start();

    final Looper looper = handlerThread.getLooper();
    final RunnableHandler.DefaultHandler lithoHandler = new RunnableHandler.DefaultHandler(looper);

    warmer.prepare("tag1", renderInfo1, null, lithoHandler);
    warmer.prepare("tag2", renderInfo2, null, lithoHandler);

    RecyclerBinder binder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().componentWarmer(warmer).build())
            .build(mContext);

    assertThat(warmer.getPending().size()).isEqualTo(2);
    assertThat(warmer.getCache().get("tag1")).isNull();
    assertThat(warmer.getCache().get("tag2")).isNull();

    binder.measure(new Size(), mWidthSpec, mHeightSpec, null);

    assertThat(warmer.getPending()).isEmpty();

    ShadowLooper shadowLooper = Shadow.extract(looper);
    shadowLooper.runToEndOfTasks();

    final ComponentTreeHolder holder1 = warmer.getCache().get("tag1");
    assertThat(holder1).isNotNull();
    assertThat(holder1.isTreeValid()).isTrue();

    final ComponentTreeHolder holder2 = warmer.getCache().get("tag2");
    assertThat(holder2).isNotNull();
    assertThat(holder2.isTreeValid()).isTrue();

    assertThat(component1.ranLayout.get()).isTrue();
    assertThat(component2.ranLayout.get()).isTrue();
  }

  @Test
  public void testLazyAsyncPrepare() {
    final ComponentWarmer warmer = new ComponentWarmer();

    final TestComponent component1 = new TestComponent("tag1");
    final ComponentRenderInfo renderInfo1 =
        ComponentRenderInfo.create()
            .component(component1)
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    final TestComponent component2 = new TestComponent("tag2");
    final ComponentRenderInfo renderInfo2 =
        ComponentRenderInfo.create()
            .component(component2)
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .build();

    warmer.prepareAsync("tag1", renderInfo1);
    warmer.prepareAsync("tag2", renderInfo2);

    RecyclerBinder binder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().componentWarmer(warmer).build())
            .build(mContext);

    assertThat(warmer.getPending().size()).isEqualTo(2);
    assertThat(warmer.getCache().get("tag1")).isNull();
    assertThat(warmer.getCache().get("tag2")).isNull();

    binder.measure(new Size(), mWidthSpec, mHeightSpec, null);

    assertThat(warmer.getPending().isEmpty()).isTrue();

    final ComponentTreeHolder holder1 = warmer.getCache().get("tag1");
    assertThat(holder1).isNotNull();
    assertThat(holder1.isTreeValid()).isTrue();

    final ComponentTreeHolder holder2 = warmer.getCache().get("tag2");
    assertThat(holder2).isNotNull();
    assertThat(holder2.isTreeValid()).isTrue();

    runToEndOfTasks();

    assertThat(component1.ranLayout.get()).isTrue();
    assertThat(component2.ranLayout.get()).isTrue();
  }

  @Test
  public void prepareWithSizeSpecs() {
    final ComponentWarmer warmer = new ComponentWarmer(mContext, mWidthSpec, mHeightSpec);
    assertThat(warmer.isReady()).isTrue();

    final ComponentWarmer.ComponentTreeHolderPreparer preparer = warmer.getFactory();

    final TestComponent testComponentPrepare = new TestComponent("t1");
    final ComponentRenderInfo renderInfoPrepare =
        ComponentRenderInfo.create().component(testComponentPrepare).build();

    warmer.prepare("tag1", renderInfoPrepare, null);

    final ComponentTreeHolder holder = warmer.getCache().get("tag1");
    assertThat(holder).isNotNull();
    assertThat(testComponentPrepare.ranLayout.get()).isTrue();

    final RecyclerBinder recyclerBinder =
        new RecyclerBinder.Builder()
            .recyclerBinderConfig(RecyclerBinderConfig.create().componentWarmer(warmer).build())
            .build(mContext);

    final TestComponent testComponentInsert = new TestComponent("t2");
    final ComponentRenderInfo renderInfoInsert =
        ComponentRenderInfo.create()
            .customAttribute(ComponentWarmer.COMPONENT_WARMER_TAG, "tag1")
            .component(testComponentInsert)
            .build();
    recyclerBinder.insertItemAt(0, renderInfoInsert);

    assertThat(recyclerBinder.getComponentTreeHolderAt(0)).isEqualTo(holder);

    assertThat(warmer.getFactory()).isEqualTo(preparer);

    recyclerBinder.measure(new Size(), mWidthSpec, mHeightSpec, null);
    assertThat(warmer.getFactory()).isNotEqualTo(preparer);
  }

  private static void runOnBackgroundThreadSync(final Runnable runnable) {
    new Thread(
            new Runnable() {
              @Override
              public void run() {
                runnable.run();
              }
            })
        .start();
  }

  final class TestComponent extends SpecGeneratedComponent {

    AtomicBoolean ranLayout = new AtomicBoolean(false);

    protected TestComponent(String simpleName) {
      super(simpleName);
    }

    @Override
    protected RenderResult<Component> render(
        ResolveContext resolveContext, ComponentContext c, int widthSpec, int heightSpec) {
      ranLayout.set(true);
      return new RenderResult<>(Column.create(c).build());
    }

    @Override
    public Component makeShallowCopy() {
      TestComponent copy = (TestComponent) super.makeShallowCopy();
      copy.ranLayout = ranLayout;

      return copy;
    }
  }
}
