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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentTree;
import com.facebook.litho.EventHandler;
import com.facebook.litho.LithoVisibilityEventsController;
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState;
import com.facebook.litho.LithoVisibilityEventsControllerDelegate;
import com.facebook.litho.RenderCompleteEvent;
import com.facebook.litho.Size;
import com.facebook.litho.SizeSpec;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import com.facebook.litho.viewcompat.ViewBinder;
import com.facebook.litho.viewcompat.ViewCreator;
import com.facebook.rendercore.DefaultErrorReporter;
import com.facebook.rendercore.ErrorReporter;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/** Tests for {@link ComponentTreeHolder} */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ComponentTreeHolderTest {

  private ComponentContext mContext;
  private Component mComponent;
  private EventHandler<RenderCompleteEvent> mRenderCompleteEventHandler;
  private ComponentRenderInfo mComponentRenderInfo;
  private ViewRenderInfo mViewRenderInfo;
  private ShadowLooper mLayoutThreadShadowLooper;
  private final int mWidthSpec = SizeSpec.makeSizeSpec(100, EXACTLY);
  private final int mHeightSpec = SizeSpec.makeSizeSpec(100, EXACTLY);
  private final int mWidthSpec2 = SizeSpec.makeSizeSpec(101, EXACTLY);
  private final int mHeightSpec2 = SizeSpec.makeSizeSpec(101, EXACTLY);

  @Before
  public void setUp() throws Exception {
    mContext = new ComponentContext(getApplicationContext());
    mComponent = new SimpleMountSpecTester();
    mRenderCompleteEventHandler = (EventHandler<RenderCompleteEvent>) mock(EventHandler.class);
    mComponentRenderInfo =
        ComponentRenderInfo.create()
            .component(mComponent)
            .renderCompleteHandler(mRenderCompleteEventHandler)
            .build();
    mViewRenderInfo =
        ViewRenderInfo.create()
            .customViewType(0)
            .viewBinder(mock(ViewBinder.class))
            .viewCreator(mock(ViewCreator.class))
            .build();
    mLayoutThreadShadowLooper = Shadow.extract(ComponentTree.getDefaultLayoutThreadLooper());
  }

  @After
  public void tearDown() {
    ErrorReporter.provide(new DefaultErrorReporter());
  }

  private void runToEndOfTasks() {
    mLayoutThreadShadowLooper.runToEndOfTasks();
  }

  @Test
  public void testHasCompletedLatestLayoutWhenNoLayoutComputed() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    assertThat(holder.hasCompletedLatestLayout()).isFalse();
  }

  @Test
  public void testRetainComponentTreeIdAfterExitAndReenterRange() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    assertThat(holder.getComponentTree()).isNotNull();
    assertThat(holder.getComponentTree().getId()).isEqualTo(holder.getId());

    // Component goes out of range
    holder.releaseTree();
    assertThat(holder.getComponentTree()).isNull();

    // Component re-enters range
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());
    assertThat(holder.getComponentTree()).isNotNull();
    assertThat(holder.getComponentTree().getId()).isEqualTo(holder.getId());
  }

  @Test
  public void testRetainStateHandlerAfterExitingRange() {
    final ComponentRenderInfo renderInfo =
        ComponentRenderInfo.create()
            .customAttribute(ComponentTreeHolder.ACQUIRE_STATE_HANDLER_ON_RELEASE, true)
            .component(mComponent)
            .build();
    ComponentTreeHolder holder = createComponentTreeHolder(renderInfo);
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    // component goes out of range
    holder.acquireStateAndReleaseTree();
    assertThat(holder.getComponentTree()).isNull();
    assertThat(holder.getTreeState()).isNotNull();
  }

  @Test
  public void testDropStateHandlerAfterExitingRange() {
    final ComponentRenderInfo renderInfo =
        ComponentRenderInfo.create()
            .customAttribute(ComponentTreeHolder.ACQUIRE_STATE_HANDLER_ON_RELEASE, false)
            .component(mComponent)
            .build();
    ComponentTreeHolder holder = createComponentTreeHolder(renderInfo);
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    // component goes out of range
    holder.acquireStateAndReleaseTree();
    assertThat(holder.getComponentTree()).isNull();
    assertThat(holder.getTreeState()).isNull();
  }

  @Test
  public void testForceKeepStateHandlerAfterExitingRange() {
    final ComponentRenderInfo renderInfo =
        ComponentRenderInfo.create()
            .customAttribute(ComponentTreeHolder.ACQUIRE_STATE_HANDLER_ON_RELEASE, true)
            .component(mComponent)
            .build();
    ComponentTreeHolder holder = createComponentTreeHolder(renderInfo);
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    // component goes out of range
    holder.acquireStateAndReleaseTree();
    assertThat(holder.getComponentTree()).isNull();
    assertThat(holder.getTreeState()).isNotNull();
  }

  /**
   * A ComponentTree can be released without going through {@link ComponentTreeHolder#releaseTree()}
   * (for instance {@code ComponentWarmer.cancelPrepare} releases {@code holder.componentTree}
   * directly), which leaves the holder pointing at a released tree that it keeps reporting as
   * valid.
   */
  @Test
  public void testAcquireStateAndReleaseTreeWhenTreeAlreadyReleased() {
    final ComponentRenderInfo renderInfo =
        ComponentRenderInfo.create()
            .customAttribute(ComponentTreeHolder.ACQUIRE_STATE_HANDLER_ON_RELEASE, true)
            .component(mComponent)
            .build();
    ComponentTreeHolder holder = createComponentTreeHolder(renderInfo);
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());
    final ComponentTree tree = holder.getComponentTree();

    tree.release();
    // Re-entering the prepared range marks the released tree valid again, so the binder still asks
    // the holder to acquire its state once the item leaves the range.
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    assertThat(holder.getComponentTree()).isSameAs(tree);
    assertThat(holder.isTreeValid()).isTrue();

    // Component goes out of range
    holder.acquireStateAndReleaseTree();

    assertThat(holder.getComponentTree()).isNull();
    assertThat(holder.isTreeValid()).isFalse();
  }

  @Test
  public void testHasCompletedLatestLayoutForSyncRender() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    assertThat(holder.hasCompletedLatestLayout()).isTrue();
  }

  @Test
  public void testHasCompletedLatestLayoutForAsyncRender() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    holder.computeLayoutAsync(mContext, mWidthSpec, mHeightSpec);

    assertThat(holder.hasCompletedLatestLayout()).isFalse();

    runToEndOfTasks();

    assertThat(holder.hasCompletedLatestLayout()).isTrue();

    // Re-computing with the same async layout specs shouldn't invalidate the layout
    holder.computeLayoutAsync(mContext, mWidthSpec, mHeightSpec);

    assertThat(holder.hasCompletedLatestLayout()).isTrue();
  }

  @Test
  public void testHasCompletedLatestLayoutForAsyncRenderAfterSyncRender() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    assertThat(holder.hasCompletedLatestLayout()).isTrue();

    holder.computeLayoutAsync(mContext, mWidthSpec2, mHeightSpec2);

    assertThat(holder.hasCompletedLatestLayout()).isFalse();
  }

  @Test
  public void testHasCompletedLatestLayoutForViewBasedTreeHolder() {
    ComponentTreeHolder holder = createComponentTreeHolder(mViewRenderInfo);
    assertThat(holder.hasCompletedLatestLayout()).isTrue();
  }

  @Test
  public void testSetListenerBeforeTreeCreation() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    ComponentTree.NewLayoutStateReadyListener listener =
        mock(ComponentTree.NewLayoutStateReadyListener.class);
    holder.setNewLayoutReadyListener(listener);

    assertThat(holder.getComponentTree()).isNull();

    holder.computeLayoutSync(
        mContext,
        SizeSpec.makeSizeSpec(100, EXACTLY),
        SizeSpec.makeSizeSpec(100, EXACTLY),
        new Size());

    assertThat(holder.getComponentTree().getNewLayoutStateReadyListener()).isEqualTo(listener);

    holder.setNewLayoutReadyListener(null);

    assertThat(holder.getComponentTree().getNewLayoutStateReadyListener()).isNull();
  }

  @Test
  public void testSetListenerAfterTreeCreation() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    ComponentTree.NewLayoutStateReadyListener listener =
        mock(ComponentTree.NewLayoutStateReadyListener.class);

    holder.computeLayoutSync(
        mContext,
        SizeSpec.makeSizeSpec(100, EXACTLY),
        SizeSpec.makeSizeSpec(100, EXACTLY),
        new Size());

    holder.setNewLayoutReadyListener(listener);
    assertThat(holder.getComponentTree().getNewLayoutStateReadyListener()).isEqualTo(listener);
  }

  @Test
  public void testSetListenerOnViewRenderInfoDoesNotCrash() {
    ComponentTreeHolder holder = createComponentTreeHolder(mViewRenderInfo);
    ComponentTree.NewLayoutStateReadyListener listener =
        mock(ComponentTree.NewLayoutStateReadyListener.class);

    holder.setNewLayoutReadyListener(listener);
  }

  @Test
  public void testGetRenderCompleteHandlerDoesNotCrash() {
    ComponentTreeHolder holder = createComponentTreeHolder(mComponentRenderInfo);
    holder.getRenderInfo().getRenderCompleteEventHandler();
  }

  @Test
  public void testReportsTreeCreatedOnDestroyedController() {
    List<String> reportedCategories = captureErrorReports();

    ComponentTreeHolder holder =
        createComponentTreeHolder(mComponentRenderInfo, destroyedController());
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    assertThat(reportedCategories)
        .containsExactly(ComponentTreeHolder.TREE_CREATED_ON_DESTROYED_CONTROLLER);
  }

  @Test
  public void testReportsTreeCreatedOnDestroyedControllerOncePerRoot() {
    List<String> reportedCategories = captureErrorReports();

    createComponentTreeHolder(mComponentRenderInfo, destroyedController())
        .computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());
    createComponentTreeHolder(mComponentRenderInfo, destroyedController())
        .computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    assertThat(reportedCategories).hasSize(1);
  }

  @Test
  public void testDoesNotReportTreeCreatedOnLiveController() {
    List<String> reportedCategories = captureErrorReports();

    ComponentTreeHolder holder =
        createComponentTreeHolder(
            mComponentRenderInfo, new LithoVisibilityEventsControllerDelegate());
    holder.computeLayoutSync(mContext, mWidthSpec, mHeightSpec, new Size());

    assertThat(reportedCategories).isEmpty();
  }

  private List<String> captureErrorReports() {
    ComponentTreeHolder.resetReportedTreesOnDestroyedController();
    List<String> categories = new ArrayList<>();
    ErrorReporter.provide(
        (level, categoryKey, message, cause, samplingFrequency, metadata) ->
            categories.add(categoryKey));
    return categories;
  }

  private static LithoVisibilityEventsController destroyedController() {
    LithoVisibilityEventsControllerDelegate controller =
        new LithoVisibilityEventsControllerDelegate();
    controller.moveToVisibilityState(LithoVisibilityState.HINT_INVISIBLE);
    controller.moveToVisibilityState(LithoVisibilityState.DESTROYED);
    return controller;
  }

  private ComponentTreeHolder createComponentTreeHolder(
      RenderInfo info, LithoVisibilityEventsController controller) {
    return ComponentTreeHolder.create(ComponentsConfiguration.defaultInstance)
        .renderInfo(info)
        .lithoVisibilityEventsController(controller)
        .build();
  }

  private ComponentTreeHolder createComponentTreeHolder(RenderInfo info) {
    return ComponentTreeHolder.create(ComponentsConfiguration.defaultInstance)
        .renderInfo(info)
        .build();
  }
}
