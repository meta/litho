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

import android.app.Activity
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleService
import com.facebook.rendercore.MountContentPools.acquireMountContent
import com.facebook.rendercore.MountContentPools.clear
import com.facebook.rendercore.MountContentPools.getContentPoolItemCount
import com.facebook.rendercore.MountContentPools.onContextDestroyed
import com.facebook.rendercore.MountContentPools.onNonActivityContextDestroyed
import com.facebook.rendercore.MountContentPools.prefillMountContentPool
import com.facebook.rendercore.MountContentPools.recycle
import com.facebook.rendercore.MountContentPools.setMountContentPoolFactory
import java.lang.Thread
import java.time.Duration
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MountContentPoolsTest {

  private val context: Context = RuntimeEnvironment.getApplication()

  private lateinit var activityController: ActivityController<Activity>

  private lateinit var activity: Activity

  private lateinit var serviceController: ServiceController<LifecycleService>

  private lateinit var service: Service

  @Before
  fun setup() {
    clear()
    setMountContentPoolFactory(null)
    activityController = Robolectric.buildActivity(Activity::class.java).create()
    activity = activityController.get()
    serviceController = Robolectric.buildService(LifecycleService::class.java).create()
    service = serviceController.get()
  }

  @After
  fun cleanup() {
    setMountContentPoolFactory(null)
    RenderCoreConfig.resetHostTranslationOnRecycle = false
    RenderCoreConfig.holdReleasedViewGroupsUntilDrawn = false
    ErrorReporter.provide(DefaultErrorReporter())
  }

  @Test
  fun testReleasingAttachedViewReportsError() {
    val reports = captureReports()
    val testRenderUnit = TestRenderUnit(0)
    val content = acquireMountContent(context, testRenderUnit) as View
    val parent = FrameLayout(context)
    parent.addView(content)

    recycle(context, testRenderUnit, content)

    Assertions.assertThat(reports).hasSize(1)
    Assertions.assertThat(reports[0].first).isEqualTo(LogLevel.ERROR)
    Assertions.assertThat(reports[0].second)
        .isEqualTo(MountContentPools.ATTACHED_VIEW_RELEASED_CATEGORY)
    Assertions.assertThat(reports[0].third)
        .contains(View::class.java.name)
        .contains("parents=${FrameLayout::class.java.name}")
  }

  @Test
  fun testAttachedViewIsDroppedWithoutDiscardingItsContent() {
    captureReports()
    val testRenderUnit = TestRenderUnit(0)
    val content = acquireMountContent(context, testRenderUnit) as View
    FrameLayout(context).addView(content)

    recycle(context, testRenderUnit, content)

    Assertions.assertThat(getContentPoolItemCount(context, testRenderUnit)).isZero
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isZero
    Assertions.assertThat(acquireMountContent(context, testRenderUnit)).isNotSameAs(content)
  }

  @Test
  fun testViewAttachedWhilePooledIsReportedAndNotReused() {
    val reports = captureReports()
    val testRenderUnit = TestRenderUnit(0)
    val content = acquireMountContent(context, testRenderUnit) as View
    recycle(context, testRenderUnit, content)
    // Something outside the pool, e.g. a transition overlay, re-parents the pooled View.
    FrameLayout(context).addView(content)

    val acquired = acquireMountContent(context, testRenderUnit)

    Assertions.assertThat(acquired).isNotSameAs(content)
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isZero
    Assertions.assertThat(reports).hasSize(1)
    Assertions.assertThat(reports[0].second)
        .isEqualTo(MountContentPools.ATTACHED_VIEW_ACQUIRED_CATEGORY)
    Assertions.assertThat(reports[0].third).contains("parents=${FrameLayout::class.java.name}")
  }

  @Test
  fun testReleasingDetachedViewDoesNotReportError() {
    val reports = captureReports()
    val testRenderUnit = TestRenderUnit(0)
    val content = acquireMountContent(context, testRenderUnit)

    recycle(context, testRenderUnit, content)

    Assertions.assertThat(reports).isEmpty()
  }

  private fun captureReports(): MutableList<Triple<LogLevel, String, String>> {
    val reports = mutableListOf<Triple<LogLevel, String, String>>()
    ErrorReporter.provide { level, categoryKey, message, _, _, _ ->
      reports.add(Triple(level, categoryKey, message))
    }
    return reports
  }

  @Test
  fun testPrefillMountContentPool() {
    val prefillCount = 4
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = prefillCount)
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, customPoolSize = prefillCount)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire)
    }
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
    acquireMountContent(context, testRenderUnitToAcquire)
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(1)
  }

  @Test
  fun testCannotReleaseToPoolIfPolicyDoesNotAllow() {
    val prefillCount = 2
    val testRenderUnit =
        TestRenderUnit(id = 0, customPoolSize = prefillCount, policy = PoolingPolicy.AcquireOnly)

    // Assert prefill works
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(2)

    // Assert acquiring works by fetching from pool
    val mountContentList =
        (0 until prefillCount).map { acquireMountContent(context, testRenderUnit) }
    Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)

    // Attempt to release into the pool (should not work)
    for (i in 0 until prefillCount) {
      recycle(context, testRenderUnit.contentAllocator, mountContentList[i])
    }

    // Attempt to acquire again
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnit.contentAllocator)
    }
    // The number of creation should double because we had to create content again
    Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount * 2)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPool() {
    val prefillCount = 4
    val customPoolSize = 2
    val testRenderUnit = TestRenderUnit(0, customPoolSize)
    prefillMountContentPool(context, prefillCount, testRenderUnit)
    // the prefill count overrides the default pool size of the render unit
    Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, 2)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire)
    }
    // expect no new render units to be created as the pool has been prefilled
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
  }

  @Test
  fun testReleaseMountContentForDestroyedContextDoesNothing() {
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit)
    recycle(activity, testRenderUnit, content1)
    val content2 = acquireMountContent(activity, testRenderUnit)

    // Assert pooling was working before
    Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2)

    // Now destroy the activity and assert pooling no longer works. Next acquire should produce
    // difference content.
    onContextDestroyed(activity)
    val content3 = acquireMountContent(activity, testRenderUnit)
    Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testDestroyingActivityDoesNotAffectPoolingOfOtherContexts() {
    // Destroy activity context
    activityController.destroy()
    onContextDestroyed(activity)
    val testRenderUnit = TestRenderUnit(0)

    // Create content with different context
    val content1 = acquireMountContent(context, testRenderUnit)
    recycle(context, testRenderUnit, content1)
    val content2 = acquireMountContent(context, testRenderUnit)

    // Ensure different context is unaffected by destroying activity context.
    Assertions.assertThat(content1).isSameAs(content2)
  }

  @Test
  fun testDestroyingServiceReleasesThePool() {
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(service, testRenderUnit)
    recycle(service, testRenderUnit, content1)
    val content2 = acquireMountContent(service, testRenderUnit)

    // Ensure that the content is reused
    Assertions.assertThat(content1).isSameAs(content2)

    // Recycle the content
    recycle(service, testRenderUnit, content2)

    // Destroy the service
    serviceController.destroy()

    val content3 = acquireMountContent(service, testRenderUnit)
    // Ensure that the content acquired after destroying the service is different
    Assertions.assertThat(content3).isNotSameAs(content2)
  }

  @Test
  fun testAcquiringContentOnBgThreadAndDestroyingServiceReleasesThePool() {
    val testRenderUnit = TestRenderUnit(0)

    var content1: Any? = null
    val bgThread = Thread { content1 = acquireMountContent(service, testRenderUnit) }
    bgThread.start()
    bgThread.join()
    Robolectric.flushForegroundThreadScheduler()

    recycle(service, testRenderUnit, checkNotNull(content1))
    val content2 = acquireMountContent(service, testRenderUnit)

    // Ensure that the content is reused
    Assertions.assertThat(content1).isSameAs(content2)

    // Recycle the content
    recycle(service, testRenderUnit, content2)

    // Destroy the service
    serviceController.destroy()

    val content3 = acquireMountContent(service, testRenderUnit)
    // Ensure that the content acquired after destroying the service is different
    Assertions.assertThat(content3).isNotSameAs(content2)
  }

  @Test
  fun testAcquireAndReleaseReturnsCorrectContentInstances() {
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 2)

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Assertions.assertThat(firstContent).isNotNull
    Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should be the same instance that was just released
    Assertions.assertThat(thirdContent).isSameAs(secondContent)
  }

  @Test
  fun testRecyclingHostClearsTranslationWhenEnabled() {
    RenderCoreConfig.resetHostTranslationOnRecycle = true
    val hostRenderUnit = TestHostRenderUnit(id = 0)
    val host = acquireMountContent(context, hostRenderUnit) as Host
    host.translationX = 12f
    host.translationY = -861f

    recycle(context, hostRenderUnit, host)
    val reacquired = acquireMountContent(context, hostRenderUnit) as Host

    Assertions.assertThat(reacquired).isSameAs(host)
    Assertions.assertThat(reacquired.translationX).isEqualTo(0f)
    Assertions.assertThat(reacquired.translationY).isEqualTo(0f)
  }

  @Test
  fun testRecyclingHostKeepsTranslationWhenDisabled() {
    val hostRenderUnit = TestHostRenderUnit(id = 0)
    val host = acquireMountContent(context, hostRenderUnit) as Host
    host.translationY = -861f

    recycle(context, hostRenderUnit, host)
    val reacquired = acquireMountContent(context, hostRenderUnit) as Host

    Assertions.assertThat(reacquired).isSameAs(host)
    Assertions.assertThat(reacquired.translationY).isEqualTo(-861f)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.N_MR1])
  fun testHeldViewGroupIsPooledOnlyAfterAFrameIsDrawn() {
    RenderCoreConfig.holdReleasedViewGroupsUntilDrawn = true
    val hostRenderUnit = TestHostRenderUnit(id = 0)
    val host = acquireMountContent(context, hostRenderUnit)
    val acquiredPerFrame = mutableListOf<Any>()

    recycle(context, hostRenderUnit, host)
    acquiredPerFrame.add(acquireMountContent(context, hostRenderUnit))
    Choreographer.getInstance().postFrameCallback {
      acquiredPerFrame.add(acquireMountContent(context, hostRenderUnit))
      Choreographer.getInstance().postFrameCallback {
        acquiredPerFrame.add(acquireMountContent(context, hostRenderUnit))
      }
    }
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    // The frame after the release still draws it, so only the frame after that may reuse it.
    Assertions.assertThat(acquiredPerFrame).hasSize(3)
    Assertions.assertThat(acquiredPerFrame[0]).isNotSameAs(host)
    Assertions.assertThat(acquiredPerFrame[1]).isNotSameAs(host)
    Assertions.assertThat(acquiredPerFrame[2]).isSameAs(host)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.N_MR1])
  fun testHoldDoesNotDelayLeafViews() {
    RenderCoreConfig.holdReleasedViewGroupsUntilDrawn = true
    val testRenderUnit = TestRenderUnit(id = 0)
    val view = acquireMountContent(context, testRenderUnit)

    recycle(context, testRenderUnit, view)

    Assertions.assertThat(acquireMountContent(context, testRenderUnit)).isSameAs(view)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.O])
  fun testHoldDoesNotApplyFromO() {
    RenderCoreConfig.holdReleasedViewGroupsUntilDrawn = true
    val hostRenderUnit = TestHostRenderUnit(id = 0)
    val host = acquireMountContent(context, hostRenderUnit)

    recycle(context, hostRenderUnit, host)

    Assertions.assertThat(acquireMountContent(context, hostRenderUnit)).isSameAs(host)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.N_MR1])
  fun testViewGroupIsReusableInTheSameFrameWhenHoldIsOff() {
    val hostRenderUnit = TestHostRenderUnit(id = 0)
    val host = acquireMountContent(context, hostRenderUnit)

    recycle(context, hostRenderUnit, host)

    Assertions.assertThat(acquireMountContent(context, hostRenderUnit)).isSameAs(host)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.N_MR1])
  fun testViewGroupAttachedDuringTheHoldIsReportedAndNotPooled() {
    RenderCoreConfig.holdReleasedViewGroupsUntilDrawn = true
    val reports = captureReports()
    val hostRenderUnit = TestHostRenderUnit(id = 0)
    val host = acquireMountContent(context, hostRenderUnit) as View

    recycle(context, hostRenderUnit, host)
    // Something outside the pool, e.g. a transition overlay, re-parents the held ViewGroup.
    FrameLayout(context).addView(host)
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    Assertions.assertThat(getContentPoolItemCount(context, hostRenderUnit)).isZero
    Assertions.assertThat(hostRenderUnit.contentDiscardedCount).isZero
    Assertions.assertThat(reports).hasSize(1)
    Assertions.assertThat(reports[0].second)
        .isEqualTo(MountContentPools.ATTACHED_VIEW_RELEASED_CATEGORY)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.N_MR1])
  fun testHeldViewGroupIsDiscardedIfItsPoolIsReleasedFirst() {
    RenderCoreConfig.holdReleasedViewGroupsUntilDrawn = true
    val poolScope = PoolScope.ManuallyManaged()
    val hostRenderUnit = TestHostRenderUnit(id = 0)
    val host = acquireMountContent(activity, hostRenderUnit, poolScope)

    recycle(activity, hostRenderUnit, host, poolScope)
    poolScope.releaseScope()
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    Assertions.assertThat(hostRenderUnit.contentDiscardedCount).isEqualTo(1)
    Assertions.assertThat(acquireMountContent(activity, hostRenderUnit, poolScope))
        .isNotSameAs(host)
  }

  @Test
  fun testAcquireContentWhenPoolIsSize0ReturnsNewContentEveryTime() {
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 0) // disable Pooling

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Assertions.assertThat(firstContent).isNotNull
    Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should not be the same as just released instance because pool size is 0
    Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testAcquireContentWhenPoolingIsDisabledReturnsNewContentEveryTime() {
    val testRenderUnitToAcquire =
        TestRenderUnit(id = 0, customPoolSize = 5, policy = PoolingPolicy.Disabled)

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Assertions.assertThat(firstContent).isNotNull
    Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should not be the same as just released instance because pool size is 0
    Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testPolicyIsAcquireOnlyReturnsNewContentEveryTime() {
    val testRenderUnitToAcquire =
        TestRenderUnit(id = 0, customPoolSize = 5, policy = PoolingPolicy.AcquireOnly)

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire)

    // both of them should be created and they shouldn't be the same instance
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Assertions.assertThat(firstContent).isNotNull
    Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire)

    // it should not be the same as just released instance because pool size is 0
    Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPoolScope() {
    val poolScope = PoolScope.ManuallyManaged()
    val prefillCount = 4
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = prefillCount)
    prefillMountContentPool(context, prefillCount, testRenderUnit, poolScope)
    Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, customPoolSize = prefillCount)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire, poolScope)
    }
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
    acquireMountContent(context, testRenderUnitToAcquire)
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(1)
  }

  @Test
  fun testPrefillMountContentPoolWithCustomPoolAndCustomPoolScope() {
    val poolScope = PoolScope.ManuallyManaged()
    val prefillCount = 4
    val customPoolSize = 2
    val testRenderUnit = TestRenderUnit(0, customPoolSize)
    prefillMountContentPool(context, prefillCount, testRenderUnit, poolScope)
    // the prefill count overrides the default pool size of the render unit
    Assertions.assertThat(testRenderUnit.createdCount).isEqualTo(prefillCount)
    val testRenderUnitToAcquire = TestRenderUnit(0, 2)
    for (i in 0 until prefillCount) {
      acquireMountContent(context, testRenderUnitToAcquire, poolScope)
    }
    // expect no new render units to be created as the pool has been prefilled
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(0)
  }

  @Test
  fun testReleaseMountContentWithCustomPoolScopeForDestroyedContextDoesNothing() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, poolScope)

    // Assert pooling was working before
    Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2, poolScope)

    // Now destroy the activity and assert pooling no longer works. Next acquire should produce
    // difference content.
    onContextDestroyed(activity)
    val content3 = acquireMountContent(activity, testRenderUnit, poolScope)
    Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testReleaseMountContentWithCustomPoolScopeForReleasedPoolScopeDoesNothing() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, poolScope)

    // Assert pooling was working before
    Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2, poolScope)

    // Now release custom pool scope. Next acquire should produce difference content.
    poolScope.releaseScope()
    val content3 = acquireMountContent(activity, testRenderUnit, poolScope)
    Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun `content recycled after its lifecycle scope ends is discarded instead of pooled`() {
    val poolScope = PoolScope.LifecycleAware((service as LifecycleService).lifecycle)
    val testRenderUnit = TestRenderUnit(0)
    val content = acquireMountContent(activity, testRenderUnit, poolScope)

    // scenario: the surface owning the scope is gone, then one of its items unmounts late
    serviceController.destroy()
    recycle(activity, testRenderUnit, content, poolScope)

    // expectation: nothing is pooled under the dead scope, and the content's resources are freed
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit, poolScope)).isZero
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
    Assertions.assertThat(acquireMountContent(activity, testRenderUnit, poolScope))
        .isNotSameAs(content)
  }

  @Test
  fun `a lifecycle scope made from a destroyed lifecycle pools nothing`() {
    serviceController.destroy()
    val poolScope = PoolScope.LifecycleAware((service as LifecycleService).lifecycle)
    val testRenderUnit = TestRenderUnit(0)

    val content = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content, poolScope)

    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit, poolScope)).isZero
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun `a manually managed scope pools again after it is released`() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)

    // scenario: a list releases its scope when it detaches, then comes back with the same scope
    recycle(
        activity,
        testRenderUnit,
        acquireMountContent(activity, testRenderUnit, poolScope),
        poolScope,
    )
    poolScope.releaseScope()
    val contentAfterRelease = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, contentAfterRelease, poolScope)

    // expectation: the scope pools again; only the content pooled before the release was discarded
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit, poolScope)).isEqualTo(1)
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
    Assertions.assertThat(acquireMountContent(activity, testRenderUnit, poolScope))
        .isSameAs(contentAfterRelease)
  }

  @Test
  fun `a view attached by someone else stays with them when it is recycled into an ended scope`() {
    captureReports()
    val poolScope = PoolScope.LifecycleAware((service as LifecycleService).lifecycle)
    val testRenderUnit = TestRenderUnit(0)
    val content = acquireMountContent(activity, testRenderUnit, poolScope) as View
    FrameLayout(activity).addView(content)

    serviceController.destroy()
    recycle(activity, testRenderUnit, content, poolScope)

    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isZero
    Assertions.assertThat(content.parent).isNotNull
  }

  @Test
  fun testReleaseMountContentWithCustomPoolScopeForReleasedLifecycleAwarePoolScopeDoesNothing() {
    val servicePoolScope = PoolScope.LifecycleAware((service as LifecycleService).lifecycle)
    val testRenderUnit = TestRenderUnit(0)
    val content1 = acquireMountContent(activity, testRenderUnit, servicePoolScope)
    recycle(activity, testRenderUnit, content1, servicePoolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, servicePoolScope)

    // Assert pooling was working before
    Assertions.assertThat(content1).isSameAs(content2)
    recycle(activity, testRenderUnit, content2, servicePoolScope)

    // Now release custom pool scope by destroying the Service. Next acquire should produce
    // difference content.
    serviceController.destroy()
    val content3 = acquireMountContent(activity, testRenderUnit, servicePoolScope)
    Assertions.assertThat(content3).isNotSameAs(content1)
  }

  @Test
  fun testDestroyingActivityReleasesTheCustomScopedPool() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)
    val content2 = acquireMountContent(activity, testRenderUnit, poolScope)

    // Ensure that the content is reused
    Assertions.assertThat(content1).isSameAs(content2)

    // Recycle the content
    recycle(activity, testRenderUnit, content2, poolScope)

    // Destroy the activity
    activityController.destroy()
    onContextDestroyed(activity)

    val content3 = acquireMountContent(activity, testRenderUnit, poolScope)
    // Ensure that the content acquired after destroying the activity is different
    Assertions.assertThat(content3).isNotSameAs(content2)
  }

  @Test
  fun testAcquireAndReleaseWithDifferentCustomPoolScopesReturnsCorrectContentInstances() {
    val firstPoolScope = PoolScope.ManuallyManaged()
    val secondPoolScope = PoolScope.ManuallyManaged()
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 2)

    // acquire content objects
    val firstContentFirstScope =
        acquireMountContent(context, testRenderUnitToAcquire, firstPoolScope)
    val secondContentFirstScope =
        acquireMountContent(context, testRenderUnitToAcquire, firstPoolScope)

    val firstContentSecondScope =
        acquireMountContent(context, testRenderUnitToAcquire, secondPoolScope)
    val secondContentSecondScope =
        acquireMountContent(context, testRenderUnitToAcquire, secondPoolScope)

    // all of them should be created and they shouldn't be the same instance
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(4)
    Assertions.assertThat(firstContentFirstScope).isNotNull
    Assertions.assertThat(secondContentFirstScope).isNotSameAs(firstContentFirstScope)
    Assertions.assertThat(firstContentSecondScope).isNotNull
    Assertions.assertThat(secondContentSecondScope).isNotSameAs(firstContentSecondScope)

    // Recycle the second content instances
    recycle(context, testRenderUnitToAcquire, secondContentFirstScope, firstPoolScope)
    recycle(context, testRenderUnitToAcquire, secondContentSecondScope, secondPoolScope)

    // acquire the third content instances
    val thirdContentFirstScope =
        acquireMountContent(context, testRenderUnitToAcquire, firstPoolScope)
    val thirdContentSecondScope =
        acquireMountContent(context, testRenderUnitToAcquire, secondPoolScope)

    // they should be the same instances that were just released
    Assertions.assertThat(thirdContentFirstScope).isSameAs(secondContentFirstScope)
    Assertions.assertThat(thirdContentSecondScope).isSameAs(secondContentSecondScope)
  }

  @Test
  fun testAcquireContentWithCustomPoolScopeWhenPoolIsSize0ReturnsNewContentEveryTime() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnitToAcquire = TestRenderUnit(id = 0, customPoolSize = 0) // disable Pooling

    // acquire content objects
    val firstContent = acquireMountContent(context, testRenderUnitToAcquire, poolScope)
    val secondContent = acquireMountContent(context, testRenderUnitToAcquire, poolScope)

    // both of them should be created and they shouldn't be the same instance
    Assertions.assertThat(testRenderUnitToAcquire.createdCount).isEqualTo(2)
    Assertions.assertThat(firstContent).isNotNull
    Assertions.assertThat(secondContent).isNotSameAs(firstContent)

    // Recycle the second content instance
    recycle(context, testRenderUnitToAcquire, secondContent, poolScope)

    // acquire the third content instance
    val thirdContent = acquireMountContent(context, testRenderUnitToAcquire, poolScope)

    // it should not be the same as just released instance because pool size is 0
    Assertions.assertThat(thirdContent).isNotSameAs(secondContent)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenPoolIsCleared() {
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit)
    recycle(activity, testRenderUnit, content1)

    // Release the pool
    onContextDestroyed(activity)

    // Assert that content discarded was called once
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenScopedPoolIsCleared() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit, poolScope)
    recycle(activity, testRenderUnit, content1, poolScope)

    // Release custom pool scope
    poolScope.releaseScope()

    // Assert that content discarded was called once
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenLifecycleAwareScopedPoolIsCleared() {
    val servicePoolScope = PoolScope.LifecycleAware((service as LifecycleService).lifecycle)
    val testRenderUnit = TestRenderUnit(0)

    val content1 = acquireMountContent(activity, testRenderUnit, servicePoolScope)
    recycle(activity, testRenderUnit, content1, servicePoolScope)

    // Destroy the service should trigger custom pool release
    serviceController.destroy()

    // Assert that content discarded was called once
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenAllPoolsAreCleared() {
    val poolScope = PoolScope.ManuallyManaged()
    val unscopedTestRenderUnit = TestRenderUnit(0)
    val scopedTestRenderUnit = TestRenderUnit(0)

    val unscopedContent = acquireMountContent(activity, unscopedTestRenderUnit)
    val scopedContent = acquireMountContent(activity, scopedTestRenderUnit, poolScope)

    recycle(activity, unscopedTestRenderUnit, unscopedContent)
    recycle(activity, scopedTestRenderUnit, scopedContent, poolScope)

    // release all pools
    clear()

    // Assert that content discarded was called once for both units
    Assertions.assertThat(unscopedTestRenderUnit.contentDiscardedCount).isEqualTo(1)
    Assertions.assertThat(scopedTestRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenPoolIsFull() {
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = 2)

    val firstContent = acquireMountContent(activity, testRenderUnit)
    val secondContent = acquireMountContent(activity, testRenderUnit)
    val thirdContent = acquireMountContent(activity, testRenderUnit)

    recycle(activity, testRenderUnit, firstContent)
    // Pool size is 2 so first release shouldn't trigger content discarded listener
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, secondContent)
    // Pool size is 2 so second release shouldn't trigger content discarded listener
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, thirdContent)
    // Pool size is 2 so third release should trigger content discarded listener
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testContentDiscardedListenerIsTriggeredWhenScopedPoolIsFull() {
    val poolScope = PoolScope.ManuallyManaged()
    val testRenderUnit = TestRenderUnit(id = 0, customPoolSize = 2)

    val firstContent = acquireMountContent(activity, testRenderUnit, poolScope)
    val secondContent = acquireMountContent(activity, testRenderUnit, poolScope)
    val thirdContent = acquireMountContent(activity, testRenderUnit, poolScope)

    recycle(activity, testRenderUnit, firstContent, poolScope)
    // Pool size is 2 so first release shouldn't trigger content discarded listener
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, secondContent, poolScope)
    // Pool size is 2 so second release shouldn't trigger content discarded listener
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(0)

    recycle(activity, testRenderUnit, thirdContent, poolScope)
    // Pool size is 2 so third release should trigger content discarded listener
    Assertions.assertThat(testRenderUnit.contentDiscardedCount).isEqualTo(1)
  }

  @Test
  fun testPoolReturnsCorrectPooledItemsCount() {

    val testRenderUnit = TestRenderUnit(0)

    // Pool is initially empty
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit)).isZero

    val content1 = acquireMountContent(activity, testRenderUnit)
    val content2 = acquireMountContent(activity, testRenderUnit)

    // After acquiring the pool is empty
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit)).isZero

    recycle(activity, testRenderUnit, content1)

    // After recycling first item, the pool contains 1 item
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit)).isEqualTo(1)

    recycle(activity, testRenderUnit, content2)
    // After recycling second item, the pool contains 2 items
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit)).isEqualTo(2)

    clear()
    // After clear the pool is empty
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit)).isZero
  }

  @Test
  fun testOnNonActivityContextDestroyedEvictsOnlyExactKey() {
    val testRenderUnit = TestRenderUnit(0)
    val dialogContext = ContextThemeWrapper(activity, android.R.style.Theme)

    val dialogContent = acquireMountContent(dialogContext, testRenderUnit)
    recycle(dialogContext, testRenderUnit, dialogContent)
    val activityContent = acquireMountContent(activity, testRenderUnit)
    recycle(activity, testRenderUnit, activityContent)
    Assertions.assertThat(getContentPoolItemCount(dialogContext, testRenderUnit)).isEqualTo(1)
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit)).isEqualTo(1)

    // Evict only the dialog-scoped entry.
    onNonActivityContextDestroyed(dialogContext)

    // Dialog key was evicted: pool is empty and next acquire creates new content.
    Assertions.assertThat(getContentPoolItemCount(dialogContext, testRenderUnit)).isZero
    Assertions.assertThat(acquireMountContent(dialogContext, testRenderUnit))
        .isNotSameAs(dialogContent)

    // Sibling activity key is untouched: pooled content is still reused.
    Assertions.assertThat(getContentPoolItemCount(activity, testRenderUnit)).isEqualTo(1)
    Assertions.assertThat(acquireMountContent(activity, testRenderUnit)).isSameAs(activityContent)

    // Host activity keeps pooling (its root was NOT marked destroyed).
    recycle(activity, testRenderUnit, activityContent)
    Assertions.assertThat(acquireMountContent(activity, testRenderUnit)).isSameAs(activityContent)
  }

  class TestRenderUnit(
      override val id: Long,
      private val customPoolSize: Int = ContentAllocator.DEFAULT_MAX_PREALLOCATION,
      private val policy: PoolingPolicy = PoolingPolicy.Default,
  ) : RenderUnit<View>(), ContentAllocator<View> {

    var createdCount: Int = 0
      private set

    var contentDiscardedCount: Int = 0
      private set

    override fun createContent(context: Context): View {
      createdCount++
      return View(context)
    }

    override val contentAllocator: ContentAllocator<View>
      get() = this

    override val renderType: RenderType = RenderType.VIEW

    override val poolingPolicy: PoolingPolicy
      get() = policy

    override fun poolSize(): Int = customPoolSize

    override val onContentDiscarded: ((Any) -> Unit)?
      get() = { content -> contentDiscardedCount++ }
  }

  class TestHostRenderUnit(override val id: Long) : RenderUnit<View>(), ContentAllocator<View> {

    var contentDiscardedCount: Int = 0
      private set

    override fun createContent(context: Context): View = HostView(context)

    override val contentAllocator: ContentAllocator<View>
      get() = this

    override val renderType: RenderType = RenderType.VIEW

    override val onContentDiscarded: ((Any) -> Unit)?
      get() = { content -> contentDiscardedCount++ }
  }
}
