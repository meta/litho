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

package com.facebook.samples.litho

import android.content.Context
import android.content.Intent
import com.facebook.litho.Component
import com.facebook.litho.sections.SectionContext
import com.facebook.litho.sections.widget.RecyclerCollectionComponent
import com.facebook.litho.widget.ComponentCreator
import com.facebook.samples.litho.common.playground.PlaygroundComponent
import com.facebook.samples.litho.documentation.customlayout.ActionBarLayoutExample
import com.facebook.samples.litho.documentation.customlayout.AlignedColumnLayoutExample
import com.facebook.samples.litho.documentation.customlayout.BadgeOverlayLayoutExample
import com.facebook.samples.litho.documentation.customlayout.CustomLayoutDemo
import com.facebook.samples.litho.documentation.customlayout.EqualSplitLayoutExample
import com.facebook.samples.litho.documentation.customlayout.FacepileLayoutExample
import com.facebook.samples.litho.documentation.customlayout.FlowRowLayoutExample
import com.facebook.samples.litho.integration.actionhandler.ActionHandlerComponent
import com.facebook.samples.litho.integration.dataflow.DataFlowComponent
import com.facebook.samples.litho.integration.listpagination.PaginatedGridComponent
import com.facebook.samples.litho.integration.listpagination.PaginatedListComponent
import com.facebook.samples.litho.integration.reactivebridge.CallbackBridgeComponent
import com.facebook.samples.litho.integration.reactivebridge.SharedFlowEventComponent
import com.facebook.samples.litho.integration.reactivebridge.UseFlowBridgeComponent
import com.facebook.samples.litho.integration.uistate.UiStateComponent
import com.facebook.samples.litho.java.bordereffects.BorderEffectsComponent
import com.facebook.samples.litho.java.changesetdebug.ItemsRerenderingActivity
import com.facebook.samples.litho.java.changesetdebug.PropUpdatingActivity
import com.facebook.samples.litho.java.changesetdebug.ScrollingToBottomActivity
import com.facebook.samples.litho.java.changesetdebug.StateResettingActivity
import com.facebook.samples.litho.java.communicating.CommunicatingBetweenSiblings
import com.facebook.samples.litho.java.communicating.CommunicatingFromChildToParent
import com.facebook.samples.litho.java.communicating.CommunicatingFromParentToChild
import com.facebook.samples.litho.java.duplicatestate.DuplicateState
import com.facebook.samples.litho.java.dynamicprops.DynamicPropsActivity
import com.facebook.samples.litho.java.editor.SimpleEditorExampleActivity
import com.facebook.samples.litho.java.errors.ErrorHandlingActivity
import com.facebook.samples.litho.java.fastscroll.FastScrollHandleComponent
import com.facebook.samples.litho.java.horizontalscroll.HorizontalScrollRootComponent
import com.facebook.samples.litho.java.hscroll.HorizontalScrollWithDynamicItemHeight
import com.facebook.samples.litho.java.hscroll.HorizontalScrollWithSnapActivity
import com.facebook.samples.litho.java.identity.ComponentIdentityActivity
import com.facebook.samples.litho.java.incrementalmount.IncrementalMountWithCustomViewContainerActivity
import com.facebook.samples.litho.java.lifecycle.LifecycleDelegateActivity
import com.facebook.samples.litho.java.lifecycle.LifecycleFragmentActivity
import com.facebook.samples.litho.java.lifecycle.ViewPagerLifecycleActivity
import com.facebook.samples.litho.java.lithography.LithographyActivity
import com.facebook.samples.litho.java.onboarding.IntroducingLayoutComponent
import com.facebook.samples.litho.java.onboarding.LayoutWithImageComponent
import com.facebook.samples.litho.java.stateupdates.SectionStateUpdateFromComponentSection
import com.facebook.samples.litho.java.stateupdates.StateUpdateFromOutsideTreeActivity
import com.facebook.samples.litho.java.stats.Stats
import com.facebook.samples.litho.java.textinput.TextInputRequestAndClearFocus
import com.facebook.samples.litho.java.textinput.TextInputWithKeyboardAndFocusDemo
import com.facebook.samples.litho.java.triggers.ClearTextTriggerExampleComponent
import com.facebook.samples.litho.java.triggers.CustomEventTriggerExampleComponent
import com.facebook.samples.litho.java.triggers.TooltipTriggerExampleActivity
import com.facebook.samples.litho.java.viewpager.ViewPagerDemoComponent
import com.facebook.samples.litho.kotlin.accessibility.FocusOrderComponent
import com.facebook.samples.litho.kotlin.accessibility.InitialFocusComponent
import com.facebook.samples.litho.kotlin.accessibility.KeyboardTraversalComponent
import com.facebook.samples.litho.kotlin.accessibility.LiveRegionModeComponent
import com.facebook.samples.litho.kotlin.accessibility.PaneTitleComponent
import com.facebook.samples.litho.kotlin.accessibility.StateDescriptionComponent
import com.facebook.samples.litho.kotlin.animations.SimpleAnimatableKComponent
import com.facebook.samples.litho.kotlin.animations.animatedbadge.AnimatedBadgeKotlin
import com.facebook.samples.litho.kotlin.animations.blur.BlurKComponent
import com.facebook.samples.litho.kotlin.animations.bounds.BoundsAnimationComponent
import com.facebook.samples.litho.kotlin.animations.diffbased.ChoreographyComponent
import com.facebook.samples.litho.kotlin.animations.diffbased.DiffBoundsReorderComponent
import com.facebook.samples.litho.kotlin.animations.diffbased.DiffFadeToggleComponent
import com.facebook.samples.litho.kotlin.animations.diffbased.DiffInterruptionPreservationComponent
import com.facebook.samples.litho.kotlin.animations.diffbased.DiffReflowToggleComponent
import com.facebook.samples.litho.kotlin.animations.diffbased.DiffReusableMotionComponent
import com.facebook.samples.litho.kotlin.animations.diffbased.PageIndicatorsComponent
import com.facebook.samples.litho.kotlin.animations.dynamicprops.AllCommonDynamicPropsKComponent
import com.facebook.samples.litho.kotlin.animations.dynamicprops.AnimateDynamicPropsKComponent
import com.facebook.samples.litho.kotlin.animations.dynamicprops.CustomDynamicPropsKComponent
import com.facebook.samples.litho.kotlin.animations.expandableelement.ExpandableElementRootKotlinKComponent
import com.facebook.samples.litho.kotlin.animations.messages.Message
import com.facebook.samples.litho.kotlin.animations.statebased.AnimatedContentCounterComponent
import com.facebook.samples.litho.kotlin.animations.statebased.AnimatedContentDemoComponent
import com.facebook.samples.litho.kotlin.animations.statebased.AnimatedCounterComponent
import com.facebook.samples.litho.kotlin.animations.statebased.AnimatedPropertiesComponent
import com.facebook.samples.litho.kotlin.animations.statebased.AnimatedVisibilityDemoComponent
import com.facebook.samples.litho.kotlin.animations.statebased.AnimationCallbackComponent
import com.facebook.samples.litho.kotlin.animations.statebased.ColorAnimationComponent
import com.facebook.samples.litho.kotlin.animations.statebased.ExpandableCardComponent
import com.facebook.samples.litho.kotlin.animations.statebased.FadeToggleComponent
import com.facebook.samples.litho.kotlin.animations.statebased.InfiniteAnimationDemosComponent
import com.facebook.samples.litho.kotlin.animations.statebased.PathMotionComponent
import com.facebook.samples.litho.kotlin.animations.statebased.SpecComparisonComponent
import com.facebook.samples.litho.kotlin.animations.statebased.SpringComparisonComponent
import com.facebook.samples.litho.kotlin.animations.statebased.StaggeredEntranceComponent
import com.facebook.samples.litho.kotlin.animations.transitions.TransitionsComponent
import com.facebook.samples.litho.kotlin.bordereffects.BorderEffectsComponentKotlin
import com.facebook.samples.litho.kotlin.canvas.DrawAnimatedSquareCanvasKComponent
import com.facebook.samples.litho.kotlin.canvas.DrawPathCanvasKComponent
import com.facebook.samples.litho.kotlin.canvas.DrawRotatedGroupCanvasKComponent
import com.facebook.samples.litho.kotlin.canvas.DrawShapeCanvasKComponent
import com.facebook.samples.litho.kotlin.canvas.DrawStrokedShapeCanvasKComponent
import com.facebook.samples.litho.kotlin.canvas.DrawTransparentHoleCanvasKComponent
import com.facebook.samples.litho.kotlin.canvas.SimpleImageViewExamplePrimitiveComponent
import com.facebook.samples.litho.kotlin.collection.ChangeableItemsCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.CollectionKComponent
import com.facebook.samples.litho.kotlin.collection.DepsCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.FriendsCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.GridCollectionExample
import com.facebook.samples.litho.kotlin.collection.HorizontalCardCarouselComponent
import com.facebook.samples.litho.kotlin.collection.HorizontalScrollKComponent
import com.facebook.samples.litho.kotlin.collection.LazyListItemAnimationsComponent
import com.facebook.samples.litho.kotlin.collection.ModularCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.MultiListCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.PaginationCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.PullToRefreshCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.RangeManagerActivity
import com.facebook.samples.litho.kotlin.collection.RangeManagerFeedActivity
import com.facebook.samples.litho.kotlin.collection.ScrollSnappingComponent
import com.facebook.samples.litho.kotlin.collection.ScrollToCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.SelectionCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.SpanCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.StaggeredGridCollectionExample
import com.facebook.samples.litho.kotlin.collection.StickyHeaderCollectionKComponent
import com.facebook.samples.litho.kotlin.collection.TabsCollectionKComponent
import com.facebook.samples.litho.kotlin.commonprops.PerCornerRoundedRectangleExampleComponent
import com.facebook.samples.litho.kotlin.commonprops.RoundedCornerExampleComponent
import com.facebook.samples.litho.kotlin.commonprops.ShapeBackgroundExampleComponent
import com.facebook.samples.litho.kotlin.documentation.VisibilityChangeReasonExampleComponent
import com.facebook.samples.litho.kotlin.errors.ErrorHandlingKotlinActivity
import com.facebook.samples.litho.kotlin.flexbox.FlexboxLayoutExamples
import com.facebook.samples.litho.kotlin.flexbox.SpacingBetweenElementsComponent
import com.facebook.samples.litho.kotlin.gettingstarted.BasicList
import com.facebook.samples.litho.kotlin.gettingstarted.ClickableText
import com.facebook.samples.litho.kotlin.gettingstartedsolution.VerticalSpeller
import com.facebook.samples.litho.kotlin.layout.StackLayoutExamplesComponent
import com.facebook.samples.litho.kotlin.lithography.LithographyKotlinActivity
import com.facebook.samples.litho.kotlin.mountandvisibility.IncrementalMountExcludeComponent
import com.facebook.samples.litho.kotlin.mountandvisibility.IncrementalMountListDemoComponent
import com.facebook.samples.litho.kotlin.mountandvisibility.IncrementalMountManualTriggerActivity
import com.facebook.samples.litho.kotlin.mountandvisibility.ViewPager2IncrementalMountActivity
import com.facebook.samples.litho.kotlin.mountandvisibility.ViewPagerIncrementalMountActivity
import com.facebook.samples.litho.kotlin.navigation.BottomNavBarComponent
import com.facebook.samples.litho.kotlin.observability.UseFlowComponent
import com.facebook.samples.litho.kotlin.observability.UseLiveDataComponent
import com.facebook.samples.litho.kotlin.playground.PlaygroundKComponent
import com.facebook.samples.litho.kotlin.playground.SelectableTextDemoKComponent
import com.facebook.samples.litho.kotlin.primitives.SimpleCanvasExampleComponent
import com.facebook.samples.litho.kotlin.primitives.SimpleImageViewWithAccessibilityExamplePrimitiveComponent
import com.facebook.samples.litho.kotlin.primitives.bindto.PrimitiveBindToExampleComponent
import com.facebook.samples.litho.kotlin.primitives.controllers.ControllersExamplePrimitiveComponent
import com.facebook.samples.litho.kotlin.primitives.widgets.PrimitiveWidgetsExampleComponent
import com.facebook.samples.litho.kotlin.renderwithconstraints.RenderWithConstraintsKComponent
import com.facebook.samples.litho.kotlin.state.IdentityRootComponent
import com.facebook.samples.litho.kotlin.state.StateParentChildComponent
import com.facebook.samples.litho.kotlin.treeprops.TreePropsExampleComponent
import com.facebook.samples.litho.kotlin.triggers.TooltipTriggerExampleKComponent
import com.facebook.samples.litho.kotlin.visibility.AOSPLithoVisibilityEventsControllerActivity
import com.facebook.samples.litho.kotlin.visibility.LithoVisibilityEventsControllerActivity
import com.facebook.samples.litho.kotlin.visibility.SetVisibilityActivity
import com.facebook.samples.litho.kotlin.visibility.VisibilityBoundsTransformerActivity
import com.facebook.samples.litho.kotlin.widgets.TextWidgetSampleComponent
import com.facebook.samples.litho.kotlin.widgets.WidgetsExamplesComponent
import com.facebook.samples.litho.onboarding.FirstComponentActivity
import com.facebook.samples.litho.onboarding.HelloWorldActivity
import com.facebook.samples.litho.onboarding.PostStyledKComponent
import com.facebook.samples.litho.onboarding.PostWithActionsKComponent
import com.facebook.samples.litho.onboarding.UserFeedKComponent
import com.facebook.samples.litho.onboarding.UserFeedWithStoriesKComponent
import com.facebook.samples.litho.onboarding.model.Feed
import com.facebook.samples.litho.onboarding.model.NebulasPost
import com.facebook.samples.litho.onboarding.model.UserStories
import com.facebook.samples.litho.playground.HostPlaygroundActivity
import com.facebook.samples.litho.playground.SaveableStatePlaygroundActivity
import com.facebook.samples.litho.playground.XMLPlaygroundActivity

class Demos {
  companion object {
    @kotlin.jvm.JvmField
    val Demos = listOf(
        DemoList(
            name = "Playground",
            listOf(
                DemoGrouping(
                    name = "Playground",
                    listOf(
                        SingleDemo(name = "Java API Playground") { context ->
                          PlaygroundComponent.create(context).build()
                        },
                        SingleDemo(
                            name = "Kotlin API Playground",
                            component = PlaygroundKComponent(),
                        ),
                        SingleDemo(
                            name = "Saveable State Playground",
                            klass = SaveableStatePlaygroundActivity::class.java,
                        ),
                        SingleDemo(
                            name = "XML Playground",
                            klass = XMLPlaygroundActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Host Playground",
                            klass = HostPlaygroundActivity::class.java,
                        ),
                    ),
                ),
            ),
        ),
        // Please keep this alphabetical with consistent naming and casing!
        DemoList(
            name = "Kotlin API Demos",
            listOf(
                DemoGrouping(
                    name = "Accessibility",
                    listOf(
                        SingleDemo(name = "Focus Order", component = FocusOrderComponent()),
                        SingleDemo(
                            name = "Keyboard Traversal",
                            component = KeyboardTraversalComponent(),
                        ),
                        SingleDemo(name = "Pane Title", component = PaneTitleComponent()),
                        SingleDemo(
                            name = "Live Region Mode",
                            component = LiveRegionModeComponent(),
                        ),
                        SingleDemo(name = "Initial Focus", component = InitialFocusComponent()),
                        SingleDemo(
                            name = "State Description",
                            component = StateDescriptionComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Animations",
                    listOf(
                        SingleDemo(name = "Blur Animation", component = BlurKComponent()),
                        SingleDemo(
                            name = "Simple Animatable",
                            component = SimpleAnimatableKComponent(),
                        ),
                        SingleDemo(
                            name = "All Common Dynamic Props",
                            component = AllCommonDynamicPropsKComponent(),
                        ),
                        SingleDemo(
                            name = "[KComponent] Custom Dynamic Props",
                            component = CustomDynamicPropsKComponent(),
                        ),
                        SingleDemo(
                            name = "[KComponent] Animated Dynamic Props",
                            component = AnimateDynamicPropsKComponent(),
                        ),
                        SingleDemo(
                            name = "[KComponent] RenderWithConstraints",
                            component = RenderWithConstraintsKComponent(),
                        ),
                        SingleDemo(
                            name = "Transitions",
                            component = TransitionsComponent(),
                        ),
                        SingleDemo(name = "Animated Badge", component = AnimatedBadgeKotlin()),
                        SingleDemo(
                            name = "Bounds Animation (Legacy)",
                            component = BoundsAnimationComponent(),
                        ),
                        SingleDemo(
                            name = "Expandable Element (Legacy)",
                            component =
                                ExpandableElementRootKotlinKComponent(
                                    initialMessages = Message.Messages,
                                ),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Animated Containers",
                    listOf(
                        SingleDemo(
                            name = "Animated Visibility",
                            component = AnimatedVisibilityDemoComponent(),
                        ),
                        SingleDemo(
                            name = "Animated Content",
                            component = AnimatedContentDemoComponent(),
                        ),
                        SingleDemo(
                            name = "Animated Content (Counter)",
                            component = AnimatedContentCounterComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "State-Based Animations",
                    listOf(
                        SingleDemo(
                            name = "Fade Toggle",
                            component = FadeToggleComponent(),
                        ),
                        SingleDemo(
                            name = "Animated Counter",
                            component = AnimatedCounterComponent(),
                        ),
                        SingleDemo(
                            name = "Spring Comparison",
                            component = SpringComparisonComponent(),
                        ),
                        SingleDemo(
                            name = "Spec Comparison",
                            component = SpecComparisonComponent(),
                        ),
                        SingleDemo(
                            name = "Expandable Card",
                            component = ExpandableCardComponent(),
                        ),
                        SingleDemo(
                            name = "Staggered Entrance",
                            component = StaggeredEntranceComponent(),
                        ),
                        SingleDemo(
                            name = "Animated Properties",
                            component = AnimatedPropertiesComponent(),
                        ),
                        SingleDemo(
                            name = "Color Animation",
                            component = ColorAnimationComponent(),
                        ),
                        SingleDemo(
                            name = "Infinite Animations",
                            component = InfiniteAnimationDemosComponent(),
                        ),
                        SingleDemo(
                            name = "Path Motion",
                            component = PathMotionComponent(),
                        ),
                        SingleDemo(
                            name = "Animation Callback",
                            component = AnimationCallbackComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Diff-Based Animations",
                    listOf(
                        SingleDemo(
                            name = "Fade Toggle",
                            component = DiffFadeToggleComponent(),
                        ),
                        SingleDemo(
                            name = "Bounds Reorder",
                            component = DiffBoundsReorderComponent(),
                        ),
                        SingleDemo(
                            name = "Reusable Motion",
                            component = DiffReusableMotionComponent(),
                        ),
                        SingleDemo(
                            name = "Interruption Preservation",
                            component = DiffInterruptionPreservationComponent(),
                        ),
                        SingleDemo(
                            name = "Reflow",
                            component = DiffReflowToggleComponent(),
                        ),
                        SingleDemo(
                            name = "Choreography",
                            component = ChoreographyComponent(),
                        ),
                        SingleDemo(
                            name = "Page Indicators",
                            component = PageIndicatorsComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Getting started",
                    listOf(
                        SingleDemo(name = "Clickable Text", component = ClickableText("Litho")),
                        SingleDemo(name = "Basic List", component = BasicList()),
                        SingleDemo(
                            name = "Vertical Speller (Solution)",
                            component = VerticalSpeller("I❤️Litho"),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Collections",
                    listOf(
                        SingleDemo(name = "Fixed Items", component = CollectionKComponent()),
                        SingleDemo(
                            name = "Item Animations",
                            component = LazyListItemAnimationsComponent(),
                        ),
                        SingleDemo(
                            name = "Changeable items",
                            component = ChangeableItemsCollectionKComponent(),
                        ),
                        SingleDemo(
                            name = "Scroll to items",
                            component = ScrollToCollectionKComponent(),
                        ),
                        SingleDemo(
                            name = "Pull to refresh",
                            component = PullToRefreshCollectionKComponent(),
                        ),
                        SingleDemo(
                            name = "Sticky Header",
                            component = StickyHeaderCollectionKComponent(),
                        ),
                        SingleDemo(name = "Span", component = SpanCollectionKComponent()),
                        SingleDemo(
                            name = "MultiList",
                            component = MultiListCollectionKComponent(),
                        ),
                        SingleDemo(
                            name = "Pagination",
                            component = PaginationCollectionKComponent(),
                        ),
                        SingleDemo(name = "Deps", component = DepsCollectionKComponent()),
                        SingleDemo(
                            name = "Selection",
                            component = SelectionCollectionKComponent(),
                        ),
                        SingleDemo(
                            name = "Modular Collection",
                            component = ModularCollectionKComponent(),
                        ),
                        SingleDemo(name = "Friends", component = FriendsCollectionKComponent()),
                        SingleDemo(
                            name = "Horizontal Scroll",
                            component = HorizontalScrollKComponent(),
                        ),
                        SingleDemo(
                            name = "Scroll Snapping (Snap to Start)",
                            component = ScrollSnappingComponent(),
                        ),
                        SingleDemo(name = "Tabs", component = TabsCollectionKComponent()),
                        SingleDemo(name = "2xN Grid", component = GridCollectionExample()),
                        SingleDemo(
                            name = "Staggered Grid",
                            component = StaggeredGridCollectionExample(),
                        ),
                        SingleDemo(
                            name = "Sections Demo: Lithography",
                            klass = LithographyKotlinActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Horizontal Card Carousel",
                            component = HorizontalCardCarouselComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "RangeManager",
                    listOf(
                        SingleDemo(
                            name = "Basic",
                            klass = RangeManagerActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Social Feed",
                            klass = RangeManagerFeedActivity::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Primitives",
                    listOf(
                        SingleDemo(
                            name = "Simple ImageView Primitive Component Example",
                            component = SimpleImageViewExamplePrimitiveComponent(),
                        ),
                        SingleDemo(
                            name = "Simple ImageView Primitive Component With A11Y Example",
                            component = SimpleImageViewWithAccessibilityExamplePrimitiveComponent(),
                        ),
                        SingleDemo(
                            name = "BindTo - Primitive Dynamic Values API",
                            component = PrimitiveBindToExampleComponent(),
                        ),
                        SingleDemo(
                            name = "Sample Primitive Components Example",
                            component = PrimitiveWidgetsExampleComponent(),
                        ),
                        SingleDemo(
                            name = "Controllers",
                            component = ControllersExamplePrimitiveComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Canvas",
                    listOf(
                        SingleDemo(
                            name = "Canvas Component Example",
                            component = SimpleCanvasExampleComponent(),
                        ),
                        SingleDemo(
                            name = "Draw Shape",
                            component = DrawShapeCanvasKComponent(),
                        ),
                        SingleDemo(name = "Draw Path", component = DrawPathCanvasKComponent()),
                        SingleDemo(
                            name = "Draw Stroked Shape",
                            component = DrawStrokedShapeCanvasKComponent(),
                        ),
                        SingleDemo(
                            name = "Draw Rotated Group",
                            component = DrawRotatedGroupCanvasKComponent(),
                        ),
                        SingleDemo(
                            name = "Draw Transparent Hole",
                            component = DrawTransparentHoleCanvasKComponent(),
                        ),
                        SingleDemo(
                            name = "Draw Animated Square",
                            component = DrawAnimatedSquareCanvasKComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Custom Layout",
                    listOf(
                        SingleDemo(
                            name = "Action Bar",
                            component =
                                CustomLayoutDemo(
                                    caption =
                                        "Narrow the width: the buttons lose their labels and show " +
                                            "icons only.",
                                    sample = ActionBarLayoutExample(),
                                ),
                        ),
                        SingleDemo(
                            name = "Facepile",
                            component =
                                CustomLayoutDemo(
                                    caption =
                                        "Narrow the width: fewer avatars fit and the count grows. " +
                                            "RTL: the pile starts at the right.",
                                    sample = FacepileLayoutExample(),
                                ),
                        ),
                        SingleDemo(
                            name = "Aligned Column",
                            component =
                                CustomLayoutDemo(
                                    caption =
                                        "RTL: incoming and outgoing messages swap sides. At most: " +
                                            "the thread is as wide as its widest bubble.",
                                    sample = AlignedColumnLayoutExample(),
                                    showsFit = true,
                                ),
                        ),
                        SingleDemo(
                            name = "Flow Row",
                            component =
                                CustomLayoutDemo(
                                    caption =
                                        "Narrow the width: the tags wrap onto more lines. RTL: each " +
                                            "line starts at the right.",
                                    sample = FlowRowLayoutExample(),
                                ),
                        ),
                        SingleDemo(
                            name = "Equal Split",
                            component =
                                CustomLayoutDemo(
                                    caption =
                                        "Tap a tab. Narrow or widen the width: the tabs keep equal " +
                                            "widths, whatever their labels.",
                                    sample = EqualSplitLayoutExample(),
                                ),
                        ),
                        SingleDemo(
                            name = "Badge Overlay",
                            component =
                                CustomLayoutDemo(
                                    caption =
                                        "RTL: the badge moves to the top-left corner, still drawn " +
                                            "over the avatar. Unbounded: the row is as wide as its " +
                                            "content.",
                                    sample = BadgeOverlayLayoutExample(),
                                ),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Errors",
                    listOf(
                        SingleDemo(
                            name = "[KComponent] Error Boundaries",
                            klass = ErrorHandlingKotlinActivity::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Widgets",
                    listOf(
                        SingleDemo(
                            name = "[KComponent] Built In Widgets",
                            component = WidgetsExamplesComponent(),
                        ),
                        SingleDemo(
                            name = "[KComponent] Text Widget Sample",
                            component = TextWidgetSampleComponent(),
                        ),
                        SingleDemo(
                            name = "Selectable Text Demo",
                            component = SelectableTextDemoKComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "State",
                    listOf(
                        SingleDemo(
                            name = "Updating State<T> from child component",
                            component = StateParentChildComponent(),
                        ),
                        SingleDemo(
                            name = "Component Identity",
                            component = IdentityRootComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Navigation",
                    listOf(
                        SingleDemo(
                            name = "Bottom Navigation Bar",
                            component = BottomNavBarComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Common Props",
                    listOf(
                        SingleDemo(
                            name = "Border Effects",
                            component = BorderEffectsComponentKotlin(),
                        ),
                        SingleDemo(name = "TreeProps", component = TreePropsExampleComponent()),
                        SingleDemo(
                            name = "RoundedCorners",
                            component = RoundedCornerExampleComponent(),
                        ),
                        SingleDemo(
                            name = "Per-corner RoundedRectangle",
                            component = PerCornerRoundedRectangleExampleComponent(),
                        ),
                        SingleDemo(
                            name = "Shape Backgrounds",
                            component = ShapeBackgroundExampleComponent(),
                        ),
                        SingleDemo(
                            name = "Flexbox Layout Examples",
                            component = FlexboxLayoutExamples(),
                        ),
                        SingleDemo(
                            name = "StackLayout Examples",
                            component = StackLayoutExamplesComponent(),
                        ),
                        SingleDemo(
                            name = "Spacing Between Elements",
                            component = SpacingBetweenElementsComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Triggers",
                    listOf(
                        SingleDemo(
                            name = "Tooltip Trigger",
                            component = TooltipTriggerExampleKComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Observability",
                    listOf(
                        SingleDemo(name = "useLiveData", component = UseLiveDataComponent()),
                        SingleDemo(name = "useFlow", component = UseFlowComponent()),
                    ),
                ),
                DemoGrouping(
                    name = "Handling Visibility",
                    listOf(
                        SingleDemo(
                            name = "VisibilityChangeReason Demo",
                            component = VisibilityChangeReasonExampleComponent(),
                        ),
                        SingleDemo(
                            name = "Android setVisibility API (in experiment)",
                            klass = SetVisibilityActivity::class.java,
                        ),
                        SingleDemo(
                            name = "LithoVisibilityEventsController",
                            klass = LithoVisibilityEventsControllerActivity::class.java,
                        ),
                        SingleDemo(
                            name = "AOSPLithoVisibilityEventsController",
                            klass = AOSPLithoVisibilityEventsControllerActivity::class.java,
                        ),
                        SingleDemo(
                            name = "VisibilityBoundsTransformer (occlusion)",
                            klass = VisibilityBoundsTransformerActivity::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Incremental Mount",
                    listOf(
                        SingleDemo(
                            name = "Mount vs Visibility Events",
                            component = IncrementalMountListDemoComponent(),
                        ),
                        SingleDemo(
                            name = "ViewPager + Incremental Mount",
                            klass = ViewPagerIncrementalMountActivity::class.java,
                        ),
                        SingleDemo(
                            name = "ViewPager2 + Incremental Mount",
                            klass = ViewPager2IncrementalMountActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Manual Mount Trigger",
                            klass = IncrementalMountManualTriggerActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Exclude from Incremental Mount",
                            component = IncrementalMountExcludeComponent(),
                        ),
                    ),
                ),
            ),
        ),
        DemoList(
            name = "Integration Samples",
            listOf(
                DemoGrouping(
                    name = "State Patterns",
                    listOf(
                        SingleDemo(
                            name = "UiState Pattern",
                            component = UiStateComponent(),
                        ),
                        SingleDemo(
                            name = "Data Flow (MVVM)",
                            component = DataFlowComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Reactive Bridges",
                    listOf(
                        SingleDemo(
                            name = "useFlow Bridge",
                            component = UseFlowBridgeComponent(),
                        ),
                        SingleDemo(
                            name = "Callback Bridge",
                            component = CallbackBridgeComponent(),
                        ),
                        SingleDemo(
                            name = "SharedFlow Events",
                            component = SharedFlowEventComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Lists & Pagination",
                    listOf(
                        SingleDemo(
                            name = "Paginated List",
                            component = PaginatedListComponent(),
                        ),
                        SingleDemo(
                            name = "Paginated Grid",
                            component = PaginatedGridComponent(),
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Navigation & Actions",
                    listOf(
                        SingleDemo(
                            name = "Action Handler",
                            component = ActionHandlerComponent(),
                        ),
                    ),
                ),
            ),
        ),
        DemoList(
            name = "Java API Demos",
            listOf(
                DemoGrouping(
                    name = "Collections",
                    listOf(
                        SingleDemo(name = "HorizontalScroll (non-recycling)") { context ->
                          HorizontalScrollRootComponent.create(context).build()
                        },
                        SingleDemo(
                            name = "Sections Demo: Lithography",
                            klass = LithographyActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Snapping",
                            klass = HorizontalScrollWithSnapActivity::class.java,
                        ),
                        SingleDemo(name = "Dynamic Item Height") { context ->
                          HorizontalScrollWithDynamicItemHeight.create(context).build()
                        },
                    ),
                ),
                DemoGrouping(
                    name = "Common Props",
                    listOf(
                        SingleDemo(name = "Border Effects") { context ->
                          BorderEffectsComponent.create(context).build()
                        },
                        SingleDemo(name = "Duplicate Parent/Child State") { context ->
                          DuplicateState.create(context).build()
                        },
                    ),
                ),
                DemoGrouping(
                    name = "Dynamic Props",
                    listOf(
                        SingleDemo(
                            name = "Dynamic Props Demo",
                            klass = DynamicPropsActivity::class.java,
                        ),
                        SingleDemo(name = "Fast Scroll Handle") { context ->
                          FastScrollHandleComponent.create(context).build()
                        },
                    ),
                ),
                DemoGrouping(
                    name = "Incremental Mount",
                    listOf(
                        SingleDemo(
                            name = "With Custom Animating Container",
                            klass = IncrementalMountWithCustomViewContainerActivity::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Lifecycle",
                    listOf(
                        SingleDemo(
                            name = "Error Boundaries",
                            klass = ErrorHandlingActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Lifecycle Callbacks",
                            klass = LifecycleDelegateActivity::class.java,
                        ),
                        SingleDemo(
                            name = "ViewPager Lifecycle Callbacks",
                            klass = ViewPagerLifecycleActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Fragment transactions lifecycle",
                            klass = LifecycleFragmentActivity::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Other Widgets",
                    listOf(
                        SingleDemo(name = "ViewPager") { context ->
                          ViewPagerDemoComponent.create(context).build()
                        },
                    ),
                ),
                DemoGrouping(
                    name = "State Updates",
                    listOf(
                        SingleDemo(
                            name = "State Update from Outside Litho",
                            klass = StateUpdateFromOutsideTreeActivity::class.java,
                        ),
                        SingleDemo(name = "State Update in Section from Child Component") { context
                          ->
                          RecyclerCollectionComponent.create(context)
                              .disablePTR(true)
                              .section(
                                  SectionStateUpdateFromComponentSection.create(
                                          SectionContext(context),
                                      )
                                      .build(),
                              )
                              .build()
                        },
                        SingleDemo(
                            name = "State and identity",
                            klass = ComponentIdentityActivity::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Communicating between Components",
                    listOf(
                        SingleDemo(
                            name = "From child to parent",
                            klass = CommunicatingFromChildToParent::class.java,
                        ),
                        SingleDemo(
                            name = "From parent to child",
                            klass = CommunicatingFromParentToChild::class.java,
                        ),
                        SingleDemo(
                            name = "Between sibling components",
                            klass = CommunicatingBetweenSiblings::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "TextInput",
                    listOf(
                        SingleDemo(name = "Focus and Show Soft Keyboard on Appear") { context ->
                          TextInputWithKeyboardAndFocusDemo.create(context).build()
                        },
                        SingleDemo(name = "Request and Clear Focus with Keyboard") { context ->
                          TextInputRequestAndClearFocus.create(context).build()
                        },
                    ),
                ),
                DemoGrouping(
                    name = "Triggers",
                    listOf(
                        SingleDemo(name = "Clear Text Trigger") { context ->
                          ClearTextTriggerExampleComponent.create(context).build()
                        },
                        SingleDemo(name = "Custom Event Trigger") { context ->
                          CustomEventTriggerExampleComponent.create(context).build()
                        },
                        SingleDemo(
                            name = "Tooltip Trigger",
                            klass = TooltipTriggerExampleActivity::class.java,
                        ),
                    ),
                ),
                DemoGrouping(
                    name = "Editor",
                    listOf(
                        SingleDemo(
                            name = "SimpleEditor for Props and State",
                            klass = SimpleEditorExampleActivity::class.java,
                        ),
                    ),
                ),
            ),
        ),
        DemoList(
            name = "Internal Debugging Samples",
            listOf(
                DemoGrouping(
                    name = "Litho Stats",
                    listOf(
                        SingleDemo(name = "LithoStats") { context ->
                          Stats.create(context).build()
                        },
                    ),
                ),
                DemoGrouping(
                    name = "Sections Changesets",
                    listOf(
                        SingleDemo(
                            name = "Resetting state",
                            klass = StateResettingActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Items re-rendering",
                            klass = ItemsRerenderingActivity::class.java,
                        ),
                        SingleDemo(
                            name = "Not updating with new props",
                            klass = PropUpdatingActivity::class.java,
                        ),
                        SingleDemo(
                            name = "List scrolls to bottom",
                            klass = ScrollingToBottomActivity::class.java,
                        ),
                    ),
                ),
            ),
        ),
        DemoList(
            name = "Tutorial",
            listOf(
                DemoGrouping(
                    name = "Onboarding",
                    listOf(
                        SingleDemo(
                            name = "1. Hello World",
                            klass = HelloWorldActivity::class.java,
                        ),
                        SingleDemo(
                            name = "2. First Litho Component",
                            klass = FirstComponentActivity::class.java,
                        ),
                        SingleDemo(name = "3. Introducing Layout") { context ->
                          IntroducingLayoutComponent.create(context).build()
                        },
                        SingleDemo(name = "3.1. More with Layout") { context ->
                          LayoutWithImageComponent.create(context).build()
                        },
                        SingleDemo(name = "3.2. Flexbox Styling") {
                          PostStyledKComponent(post = NebulasPost)
                        },
                        SingleDemo(name = "4. Add State") {
                          PostWithActionsKComponent(post = NebulasPost)
                        },
                        SingleDemo(name = "5. List") { UserFeedKComponent(posts = Feed) },
                        SingleDemo(name = "5.1. List within Lists") {
                          UserFeedWithStoriesKComponent(
                              posts = Feed,
                              usersWithStories = UserStories,
                          )
                        },
                    ),
                ),
            ),
        ),
    )
  }

  interface DemoItem {
    val name: String
  }

  /**
   * The reasons indices are used is so we have something parcelable to pass to the Activity (a
   * ComponentCreator is not parcelable).
   */
  interface NavigableDemoItem : DemoItem {
    fun getIntent(context: Context?, currentIndices: IntArray?): Intent
  }

  interface HasChildrenDemos {
    val demos: List<DemoItem>?
  }

  /** A DemoList has groupings of SingleDemos or DemoLists to navigate to. */
  class DemoList(override val name: String, val datamodels: List<DemoGrouping>) :
      NavigableDemoItem, HasChildrenDemos {
    override fun getIntent(context: Context?, currentIndices: IntArray?): Intent {
      val intent = Intent(context, DemoListActivity::class.java)
      intent.putExtra(DemoListActivity.INDICES, currentIndices)
      return intent
    }

    override val demos: List<DemoGrouping>?
      get() = datamodels
  }

  /** A DemoGrouping is a list of demo items that show under a single heading. */
  class DemoGrouping
  internal constructor(override val name: String, val datamodels: List<NavigableDemoItem>?) :
      DemoItem, HasChildrenDemos {
    override val demos: List<DemoItem>?
      get() = datamodels
  }

  class SingleDemo : NavigableDemoItem {
    override val name: String
    val klass: Class<*>?
    val componentCreator: ComponentCreator?
    val component: Component?

    internal constructor(
        name: String,
        klass: Class<*>? = null,
        component: Component? = null,
        componentCreator: ComponentCreator? = null,
    ) {
      this.name = name
      this.klass = klass
      this.componentCreator = componentCreator
      this.component = component
    }

    private val activityClass: Class<*>
      get() = klass ?: ComponentDemoActivity::class.java

    override fun getIntent(context: Context?, currentIndices: IntArray?): Intent {
      val intent = Intent(context, activityClass)
      intent.putExtra(DemoListActivity.INDICES, currentIndices)
      return intent
    }
  }
}
