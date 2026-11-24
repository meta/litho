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

package com.facebook.rendercore

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AncestorAccessibilityActionsTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun ordinaryItemDoesNotReadProviderOrGainAnActionHint() {
    val provider = Provider()
    val collection = Collection()
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(provider.populations).isZero()
    assertThat(actionIds(info)).doesNotContain(REFRESH)
  }

  @Test
  fun collectionOffersTheActionWithoutItemActions() {
    val provider = Provider()
    val collection = FrameLayout(context)
    provider.addView(collection)
    val info = collection.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(collection, info, true)

    assertThat(actionIds(info)).contains(REFRESH)
    assertThat(provider.populations).isEqualTo(1)
  }

  @Test
  fun emptyScrollViewStillOffersTheAction() {
    val provider = Provider()
    val collection = ScrollView(context)
    provider.addView(collection)
    val info = collection.createAccessibilityNodeInfo()
    info.isScrollable = false

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(collection, info)

    assertThat(actionIds(info)).contains(REFRESH)
  }

  @Test
  fun customItemActionIsPreservedWithNodeMetadataAndDelegate() {
    val provider = Provider()
    val collection = Collection()
    val item = View(context)
    val originalDelegate = View.AccessibilityDelegate()
    item.accessibilityDelegate = originalDelegate
    provider.addView(collection)
    collection.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.text = "Row"
    info.contentDescription = "Description"
    info.className = "OriginalClass"
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))
    val focusable = item.isFocusable
    val important = item.importantForAccessibility

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(SAVE, REFRESH)
    assertThat(info.text).isEqualTo("Row")
    assertThat(info.contentDescription).isEqualTo("Description")
    assertThat(info.className).isEqualTo("OriginalClass")
    assertThat(item.accessibilityDelegate).isSameAs(originalDelegate)
    assertThat(item.isFocusable).isEqualTo(focusable)
    assertThat(item.importantForAccessibility).isEqualTo(important)
  }

  @Test
  fun customItemActionFromAHighPackageIdIsPreserved() {
    val provider = Provider()
    val collection = Collection()
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(HIGH_PACKAGE_SAVE, "Save"))

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(HIGH_PACKAGE_SAVE, REFRESH)
  }

  @Test
  fun expandActionThatShadowsAncestorsGetsTheAction() {
    val provider = Provider()
    val collection = Collection()
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(REFRESH)
  }

  @Test
  fun labelledStandardClickDoesNotGainAnActionHint() {
    val provider = Provider()
    val collection = Collection()
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.addAction(
        AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
            "Open",
        ),
    )

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(provider.populations).isZero()
    assertThat(actionIds(info)).doesNotContain(REFRESH)
  }

  @Test
  fun existingActionIsNotOverwrittenOrDuplicatedByAnotherOwner() {
    val provider = Provider()
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(REFRESH, "Owner label"))

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(provider.populations).isEqualTo(1)
    assertThat(info.actionList.filter { it.id == REFRESH }).hasSize(1)
    assertThat(info.actionList.single { it.id == REFRESH }.label).isEqualTo("Owner label")
  }

  @Test
  fun nearestBoundaryPerformsOnceAndPreservesArguments() {
    val outer = Provider()
    val inner = Provider()
    val item = View(context)
    outer.addView(inner)
    inner.addView(item)
    val arguments = Bundle()

    assertThat(AncestorAccessibilityActions.performAction(item, REFRESH, arguments)).isTrue()

    assertThat(inner.performances).isEqualTo(1)
    assertThat(inner.lastArguments).isSameAs(arguments)
    assertThat(outer.performances).isZero()
  }

  @Test
  fun unavailableInnerBoundaryDoesNotOfferOrInvokeOuterAction() {
    val outer = Provider()
    val inner = Provider().apply { available = false }
    val item = View(context)
    outer.addView(inner)
    inner.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).doesNotContain(REFRESH)
    assertThat(AncestorAccessibilityActions.supportsAction(item, REFRESH)).isTrue()
    assertThat(AncestorAccessibilityActions.performAction(item, REFRESH, null)).isFalse()
    assertThat(outer.populations).isZero()
    assertThat(outer.performances).isZero()
  }

  @Test
  fun ownerThatHandledTheRequestKeepsTheProviderOutOfIt() {
    val provider = Provider()
    val item = View(context)
    provider.addView(item)

    assertThat(
        AncestorAccessibilityActions.performActionAfterOwner(item, REFRESH, null, true, true),
    )
        .isTrue()

    assertThat(provider.performances).isZero()
  }

  @Test
  fun ownerThatWithheldPermissionKeepsTheProviderOutOfADeclinedRequest() {
    val provider = Provider()
    val item = View(context)
    provider.addView(item)

    assertThat(
        AncestorAccessibilityActions.performActionAfterOwner(item, REFRESH, null, false, false),
    )
        .isFalse()
    assertThat(provider.performances).isZero()

    val arguments = Bundle()
    assertThat(
        AncestorAccessibilityActions.performActionAfterOwner(item, REFRESH, arguments, false, true),
    )
        .isTrue()
    assertThat(provider.performances).isEqualTo(1)
    assertThat(provider.lastArguments).isSameAs(arguments)
  }

  @Test
  fun permittedRequestThatTheNearestProviderDeclinesDoesNotReachAnOuterProvider() {
    val outer = Provider()
    val inner = Provider().apply { available = false }
    val item = View(context)
    outer.addView(inner)
    inner.addView(item)

    assertThat(
        AncestorAccessibilityActions.performActionAfterOwner(item, REFRESH, null, false, true),
    )
        .isFalse()

    assertThat(outer.performances).isZero()
    assertThat(outer.populations).isZero()
  }

  @Test
  fun unsupportedActionDoesNotInvokeAProvider() {
    val provider = Provider()
    val item = View(context)
    provider.addView(item)

    assertThat(AncestorAccessibilityActions.supportsAction(item, SAVE)).isFalse()
    assertThat(AncestorAccessibilityActions.performAction(item, SAVE, null)).isFalse()
    assertThat(provider.performances).isZero()
    assertThat(provider.populations).isZero()
  }

  @Test
  fun reparentingResolvesTheCurrentBoundaryWithoutRetainingThePreviousOne() {
    val first = Provider()
    val second = Provider()
    val item = View(context)
    first.addView(item)
    assertThat(AncestorAccessibilityActions.performAction(item, REFRESH, null)).isTrue()
    first.removeView(item)
    second.addView(item)

    assertThat(AncestorAccessibilityActions.performAction(item, REFRESH, null)).isTrue()

    assertThat(first.performances).isEqualTo(1)
    assertThat(second.performances).isEqualTo(1)
    second.removeView(item)
    assertThat(AncestorAccessibilityActions.performAction(item, REFRESH, null)).isFalse()
  }

  @Test
  fun providerHostIsItsOwnBoundary() {
    val outer = Provider()
    val inner = Provider().apply { available = false }
    outer.addView(inner)

    assertThat(AncestorAccessibilityActions.performAction(inner, REFRESH, null)).isFalse()
    assertThat(outer.performances).isZero()
  }

  @Test
  fun virtualItemOfAForwardingCollectionInheritsWithoutAnActionHint() {
    val provider = Provider()
    val host = Collection()
    provider.addView(host)
    val info = virtualItemInfo(host)

    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(host, info)

    assertThat(actionIds(info)).doesNotContain(REFRESH)
    assertThat(provider.populations).isZero()
  }

  @Test
  fun ordinaryRowOfAProviderThatIsItsOwnCollectionInheritsWithoutAnActionHint() {
    val provider = CollectionProvider()
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).doesNotContain(REFRESH)
    assertThat(provider.populations).isZero()
  }

  @Test
  fun virtualItemOfAProviderThatIsItsOwnCollectionInheritsWithoutAnActionHint() {
    val host = CollectionProvider()
    val info = virtualItemInfo(host)

    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(host, info)

    assertThat(actionIds(info)).doesNotContain(REFRESH)
    assertThat(host.populations).isZero()
  }

  @Test
  fun providerHostInsideAnOuterCollectionPopulatesItsOwnActions() {
    val outer = Provider(actionId = LOAD_MORE)
    val collection = Collection()
    val host = Provider()
    outer.addView(collection)
    collection.addView(host)
    val collectionInfo = collection.createAccessibilityNodeInfo()
    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(collection, collectionInfo)
    assertThat(actionIds(collectionInfo)).contains(LOAD_MORE)
    val info = host.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(host, info)

    assertThat(actionIds(info)).contains(REFRESH).doesNotContain(LOAD_MORE)
    assertThat(host.populations).isEqualTo(1)
    assertThat(host.collectionChecks).doesNotContain(collection)
    assertThat(outer.populations).isEqualTo(1)
  }

  @Test
  fun nonCollectionContentGetsAReachableItemAction() {
    val provider = Provider()
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(REFRESH)
  }

  @Test
  fun unrecognisedScrollAncestorIsNotAssumedToExposeTheAction() {
    val provider = Provider()
    val collection = ScrollView(context)
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(REFRESH)
  }

  @Test
  fun excludedCollectionDoesNotHideTheItemAlternative() {
    val provider = Provider()
    val collection = Collection()
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    collection.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(REFRESH)
    assertThat(collection.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
  }

  @Test
  fun virtualNodeLeavesOutAnInheritedActionThatWouldSetEitherFocusBit() {
    for (actionId in listOf(FOCUS_BIT_ACTION, CLEAR_FOCUS_BIT_ACTION, BOTH_FOCUS_BITS_ACTION)) {
      val provider = Provider(actionId)
      val item = View(context)
      provider.addView(item)
      val info = item.createAccessibilityNodeInfo()
      info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))

      AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(item, info)

      assertThat(actionIds(info)).containsExactly(SAVE)
    }
  }

  @Test
  fun virtualNodeKeepsAnInheritedActionOutsideTheLegacyMask() {
    val provider = Provider(POSITIVE_FOCUS_BIT_ACTION)
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(POSITIVE_FOCUS_BIT_ACTION)
  }

  @Test
  fun multiActionProviderAddsOnlyMissingActionsAfterOwnerActionsInOrder() {
    val provider = Provider(extraActionIds = listOf(LOAD_MORE))
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.actionList.toList().forEach { info.removeAction(it) }
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(REFRESH, "Owner refresh"))
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info))
        .containsExactly(
            SAVE,
            REFRESH,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id,
            LOAD_MORE,
        )
    assertThat(info.actionList.single { it.id == REFRESH }.label).isEqualTo("Owner refresh")
    assertThat(info.actionList.single { it.id == SAVE }.label).isEqualTo("Save")
    assertThat(provider.populations).isEqualTo(1)
  }

  @Test
  fun multiActionProviderWithoutCollisionsAppendsAfterOwnerActions() {
    val provider = Provider(extraActionIds = listOf(LOAD_MORE))
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.actionList.toList().forEach { info.removeAction(it) }
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).containsExactly(SAVE, REFRESH, LOAD_MORE)
  }

  @Test
  fun unavailableMultiActionProviderLeavesOwnerActionsUnchanged() {
    val provider = Provider(extraActionIds = listOf(LOAD_MORE))
    provider.available = false
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.actionList.toList().forEach { info.removeAction(it) }
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(REFRESH, "Owner refresh"))

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).containsExactly(SAVE, REFRESH)
    assertThat(info.actionList.single { it.id == REFRESH }.label).isEqualTo("Owner refresh")
  }

  @Test
  fun providerThatRemovesAnOwnerActionCannotDropIt() {
    val provider =
        Provider(onAdd = { info -> info.actionList.toList().forEach(info::removeAction) })
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.actionList.toList().forEach { info.removeAction(it) }
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).containsExactly(SAVE, REFRESH)
    assertThat(info.actionList.single { it.id == SAVE }.label).isEqualTo("Save")
  }

  @Test
  fun virtualNodeKeepsOwnerActionsAndSafeInheritedActionsWhileLeavingOutUnsafeOnes() {
    val provider = Provider(extraActionIds = listOf(FOCUS_BIT_ACTION, LOAD_MORE))
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()
    info.actionList.toList().forEach { info.removeAction(it) }
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(SAVE, "Save"))
    info.addAction(AccessibilityNodeInfo.AccessibilityAction(REFRESH, "Owner refresh"))

    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).containsExactly(SAVE, REFRESH, LOAD_MORE)
    assertThat(info.actionList.single { it.id == REFRESH }.label).isEqualTo("Owner refresh")
  }

  @Test
  fun virtualItemOfAScrollHostInheritsWithoutAnActionHint() {
    val provider = Provider()
    val collection = Collection()
    val host = ScrollView(context)
    provider.addView(collection)
    collection.addView(host)
    val info = virtualItemInfo(host)

    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(host, info)

    assertThat(actionIds(info)).doesNotContain(REFRESH)
    assertThat(provider.populations).isZero()
  }

  @Test
  fun collectionProviderOffersItsOwnActionBeforeLayoutMetadataExists() {
    val provider = CollectionProvider()
    val info = provider.createAccessibilityNodeInfo()
    assertThat(info.collectionInfo).isNull()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(provider, info)

    assertThat(actionIds(info)).contains(REFRESH)
    assertThat(provider.populations).isEqualTo(1)
  }

  @Test
  fun shadowingVirtualItemPreservesItsActionsAndMetadataUnderEitherCollectionKind() {
    val provider = Provider()
    val collection = Collection()
    provider.addView(collection)
    val collectionProvider = CollectionProvider()
    for ((owner, host) in
        listOf(provider to collection, collectionProvider to collectionProvider)) {
      val info = virtualItemInfo(host)
      val save = AccessibilityNodeInfo.AccessibilityAction(SAVE, "Owner save")
      info.addAction(save)
      info.contentDescription = "Original description"
      info.className = "Original class"
      val importance = host.importantForAccessibility

      AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(host, info)

      assertThat(actionIds(info)).containsExactly(SAVE, REFRESH)
      assertThat(info.actionList.first()).isSameAs(save)
      assertThat(info.actionList.first().label).isEqualTo("Owner save")
      assertThat(info.text).isEqualTo("Virtual item")
      assertThat(info.contentDescription).isEqualTo("Original description")
      assertThat(info.className).isEqualTo("Original class")
      assertThat(info.isFocusable).isTrue()
      assertThat(info.isEnabled).isTrue()
      assertThat(info.isVisibleToUser).isTrue()
      assertThat(host.importantForAccessibility).isEqualTo(importance)
      assertThat(owner.populations).isEqualTo(1)
    }
  }

  @Test
  fun virtualNodeOwnScrollOrCollectionMetadataStillExposesTheAction() {
    val provider = Provider()
    val host = Collection()
    provider.addView(host)
    for (kind in 0..2) {
      val info = virtualItemInfo(host)
      when (kind) {
        0 -> info.isScrollable = true
        1 -> info.className = ScrollView::class.java.name
        2 -> info.collectionInfo = AccessibilityNodeInfo.CollectionInfo(1, 1, false)
      }

      AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(host, info)

      assertThat(actionIds(info)).contains(REFRESH)
    }
    assertThat(provider.populations).isEqualTo(3)
  }

  @Test
  fun excludedVirtualForwardingCollectionDoesNotHideTheItemAlternative() {
    val provider = Provider()
    val collection = Collection()
    provider.addView(collection)
    val collectionProvider = CollectionProvider()
    for ((owner, host) in
        listOf(provider to collection, collectionProvider to collectionProvider)) {
      host.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      val info = virtualItemInfo(host)

      AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(host, info)

      assertThat(actionIds(info)).contains(REFRESH)
      assertThat(owner.populations).isEqualTo(1)
      assertThat(host.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }
  }

  @Test
  fun excludedCollectionProviderDoesNotHideTheRealItemAlternative() {
    val provider = CollectionProvider()
    provider.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    val item = View(context)
    provider.addView(item)
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(REFRESH)
    assertThat(provider.populations).isEqualTo(1)
    assertThat(provider.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
  }

  @Test
  fun innerProviderDoesNotBorrowAnOuterCollectionForItsOwnNodeOrRows() {
    val outer = CollectionProvider()
    val collection = Collection()
    val inner = Provider()
    val item = View(context)
    outer.addView(collection)
    collection.addView(inner)
    inner.addView(item)

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(
        inner,
        inner.createAccessibilityNodeInfo(),
    )
    val info = item.createAccessibilityNodeInfo()
    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(REFRESH)
    assertThat(inner.populations).isEqualTo(2)
    assertThat(inner.collectionChecks).doesNotContain(collection, outer)
    assertThat(outer.populations).isZero()
  }

  @Test
  fun unavailableVirtualHostProviderNeverUsesAnOuterCollectionProvider() {
    val outer = CollectionProvider()
    val host = Provider().apply { available = false }
    outer.addView(host)
    val info = virtualItemInfo(host)

    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(host, info)

    assertThat(actionIds(info)).doesNotContain(REFRESH)
    assertThat(host.populations).isEqualTo(1)
    assertThat(host.collectionChecks).doesNotContain(outer)
    assertThat(AncestorAccessibilityActions.supportsAction(host, REFRESH)).isTrue()
    assertThat(AncestorAccessibilityActions.performAction(host, REFRESH, null)).isFalse()
    assertThat(outer.populations).isZero()
    assertThat(outer.performances).isZero()
  }

  @Test
  fun collectionProviderPerformsEachRealOrVirtualRequestOnce() {
    val provider = CollectionProvider()
    val item = View(context)
    provider.addView(item)
    val arguments = Bundle()

    assertThat(AncestorAccessibilityActions.performAction(item, REFRESH, arguments)).isTrue()
    assertThat(provider.performances).isEqualTo(1)
    assertThat(AncestorAccessibilityActions.performAction(provider, REFRESH, arguments)).isTrue()
    assertThat(provider.performances).isEqualTo(2)
    assertThat(provider.lastArguments).isSameAs(arguments)
  }

  @Test
  fun unreportedAutoCollectionKeepsTheRealAndVirtualItemAlternative() {
    val provider = Provider()
    val collection =
        Collection().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO }
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    assertThat(collection.isImportantForAccessibility).isFalse()
    val realInfo = item.createAccessibilityNodeInfo()
    val virtualInfo = virtualItemInfo(collection)

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, realInfo)
    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(collection, virtualInfo)

    assertThat(actionIds(realInfo)).contains(REFRESH)
    assertThat(actionIds(virtualInfo)).contains(REFRESH)
    assertThat(provider.populations).isEqualTo(2)
    assertThat(collection.importantForAccessibility)
        .isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO)
  }

  @Test
  fun reportedAutoCollectionCarriesActionsWithoutItemHints() {
    val provider = Provider()
    val collection =
        Collection().apply {
          importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
          isFocusable = true
          accessibilityDelegate = View.AccessibilityDelegate()
        }
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    assertThat(collection.isImportantForAccessibility).isTrue()
    val realInfo = item.createAccessibilityNodeInfo()
    val virtualInfo = virtualItemInfo(collection)

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, realInfo)
    AncestorAccessibilityActions.onInitializeVirtualAccessibilityNodeInfo(collection, virtualInfo)

    assertThat(actionIds(realInfo)).doesNotContain(REFRESH)
    assertThat(actionIds(virtualInfo)).doesNotContain(REFRESH)
    assertThat(provider.populations).isZero()
    assertThat(collection.importantForAccessibility)
        .isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO)
  }

  @Test
  fun hiddenAncestorDoesNotLetAnUnreportedYesCollectionSuppressFallback() {
    val provider =
        Provider().apply {
          importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    val collection = Collection()
    val item = View(context)
    provider.addView(collection)
    collection.addView(item)
    assertThat(collection.isImportantForAccessibility).isFalse()
    val info = item.createAccessibilityNodeInfo()

    AncestorAccessibilityActions.onInitializeAccessibilityNodeInfo(item, info)

    assertThat(actionIds(info)).contains(REFRESH)
    assertThat(collection.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_YES)
  }

  private fun virtualItemInfo(host: View): AccessibilityNodeInfo =
      View(context).createAccessibilityNodeInfo().apply {
        setSource(host, 0)
        text = "Virtual item"
        isFocusable = true
        isEnabled = true
        isVisibleToUser = true
      }

  private inner class Collection : FrameLayout(context), AccessibilityActionForwardingCollection {
    init {
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
  }

  private inner class CollectionProvider : Provider(), AccessibilityActionForwardingCollection {
    init {
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
  }

  private fun actionIds(info: AccessibilityNodeInfo): List<Int> = info.actionList.map { it.id }

  private open inner class Provider(
      private val actionId: Int = REFRESH,
      private val extraActionIds: List<Int> = emptyList(),
      private val onAdd: (AccessibilityNodeInfo) -> Unit = {},
  ) : FrameLayout(context), AccessibilityActionProvider {
    var available = true
    var populations = 0
    var performances = 0
    var lastArguments: Bundle? = null
    val collectionChecks = mutableListOf<View>()

    override fun isAccessibilityCollectionForDescendants(view: View): Boolean {
      collectionChecks.add(view)
      return view is AccessibilityActionForwardingCollection
    }

    override fun supportsAccessibilityActionForDescendants(action: Int): Boolean =
        action == actionId || action in extraActionIds

    override fun addAccessibilityActionsForDescendants(info: AccessibilityNodeInfo) {
      populations++
      if (available) {
        onAdd(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(actionId, "Refresh"))
        extraActionIds.forEach {
          info.addAction(AccessibilityNodeInfo.AccessibilityAction(it, "Provider action"))
        }
      }
    }

    override fun performAccessibilityActionForDescendants(
        action: Int,
        arguments: Bundle?,
    ): Boolean {
      if (!available) {
        return false
      }
      performances++
      lastArguments = arguments
      return true
    }
  }

  companion object {
    private const val REFRESH = 0x7F020001
    private const val SAVE = 0x7F020002
    private const val HIGH_PACKAGE_SAVE = 0x80020002.toInt()
    private const val LOAD_MORE = 0x7F020003
    private const val FOCUS_BIT_ACTION = 0x80020040.toInt()
    private const val CLEAR_FOCUS_BIT_ACTION = 0x80020080.toInt()
    private const val BOTH_FOCUS_BITS_ACTION = 0x800200C0.toInt()
    private const val POSITIVE_FOCUS_BIT_ACTION = 0x7F0200C1
  }
}
