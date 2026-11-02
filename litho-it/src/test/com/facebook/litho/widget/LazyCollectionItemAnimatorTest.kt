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

package com.facebook.litho.widget

import android.view.View
import androidx.dynamicanimation.animation.FrameCallbackScheduler
import androidx.recyclerview.widget.ItemAnimatorFinishRecorder
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LazyCollectionItemAnimatorTest {

  private val animator = LazyCollectionItemAnimator()
  private val recorder = ItemAnimatorFinishRecorder(animator)
  private val view = View(RuntimeEnvironment.getApplication())
  private val holder: RecyclerView.ViewHolder = TestViewHolder(view)
  private val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())

  @After
  fun endSprings() {
    // androidx AnimationHandler is a per-thread singleton that only posts a new frame once its
    // callback list drains, and Robolectric drops queued frames between tests. Ending every spring
    // and letting one frame run keeps later tests' springs ticking.
    animator.endAnimations()
    settle()
  }

  @Test
  fun `change animations are disabled`() {
    assertThat(animator.supportsChangeAnimations).isFalse()
  }

  @Test
  fun `springs run to completion and report each item once`() {
    animator.animateAdd(holder)
    animator.runPendingAnimations()
    assertThat(view.alpha).isEqualTo(0f)

    settle()

    assertThat(animator.isRunning).isFalse()
    assertThat(view.alpha).isEqualTo(1f)
    assertThat(recorder.finished).containsExactly(holder)
  }

  @Test
  fun `rows added in the layout after a skip show up without fading in`() {
    animator.skipFadeInsInNextLayout(recyclerView)

    animator.animateAdd(holder)
    assertThat(view.alpha).isEqualTo(1f)
    animator.runPendingAnimations()

    assertThat(recorder.finished).containsExactly(holder)
    assertThat(animator.isRunning).isFalse()
  }

  @Test
  fun `a skip only applies to the next layout`() {
    recyclerView.layout(0, 0, 100, 100)
    animator.skipFadeInsInNextLayout(recyclerView)
    animator.animateAdd(holder)
    relayoutWithSameBounds()

    val next = TestViewHolder(View(RuntimeEnvironment.getApplication()))
    animator.animateAdd(next)

    assertThat(view.alpha).isEqualTo(1f)
    assertThat(next.itemView.alpha).isEqualTo(0f)
  }

  @Test
  fun `a skip ends when the next layout finishes even if its bounds did not change`() {
    recyclerView.layout(0, 0, 100, 100)
    animator.skipFadeInsInNextLayout(recyclerView)

    relayoutWithSameBounds()
    animator.animateAdd(holder)

    assertThat(view.alpha).isEqualTo(0f)
  }

  @Test
  fun `animations started before the next layout do not end the skip`() {
    animator.skipFadeInsInNextLayout(recyclerView)

    // An animation runner posted by an earlier layout can run before the reattach layout.
    animator.runPendingAnimations()
    animator.animateAdd(holder)

    assertThat(view.alpha).isEqualTo(1f)
  }

  @Test
  fun `a layout while layout is suppressed does not end the skip`() {
    recyclerView.layout(0, 0, 100, 100)
    animator.skipFadeInsInNextLayout(recyclerView)
    recyclerView.suppressLayout(true)

    recyclerView.layout(0, 0, 100, 200)
    animator.animateAdd(holder)

    assertThat(view.alpha).isEqualTo(1f)
  }

  @Test
  fun `ending animations ends the skip`() {
    animator.skipFadeInsInNextLayout(recyclerView)

    animator.endAnimations()
    animator.animateAdd(holder)

    assertThat(view.alpha).isEqualTo(0f)
  }

  @Test
  fun `a skip after animations were ended still ends at the next layout`() {
    recyclerView.layout(0, 0, 100, 100)
    animator.skipFadeInsInNextLayout(recyclerView)
    animator.endAnimations()
    animator.skipFadeInsInNextLayout(recyclerView)

    relayoutWithSameBounds()
    animator.animateAdd(holder)

    assertThat(view.alpha).isEqualTo(0f)
  }

  @Test
  fun `a removed item is restored for reuse after it finishes`() {
    animator.animateRemove(holder)
    animator.runPendingAnimations()

    settle()

    assertThat(recorder.finished).containsExactly(holder)
    assertThat(view.alpha).isEqualTo(1f)
    assertThat(animator.isRunning).isFalse()
  }

  @Test
  fun `a second animation before the runner finishes the first`() {
    assertThat(animator.animateMove(holder, 0, 0, 0, 100)).isTrue()
    assertThat(animator.animateMove(holder, 0, 100, 0, 200)).isTrue()

    // The first move is reported finished when the second takes over, at its offset.
    assertThat(recorder.finished).containsExactly(holder)
    assertThat(view.translationY).isEqualTo(-200f)

    animator.endAnimations()
    assertThat(recorder.finished).hasSize(2)
    assertThat(animator.isRunning).isFalse()
  }

  @Test
  fun `adding then removing before the runner leaves nothing running`() {
    animator.animateAdd(holder)
    animator.animateRemove(holder)
    animator.runPendingAnimations()
    animator.endAnimations()

    settle()

    assertThat(recorder.finished).hasSize(2)
    assertThat(view.alpha).isEqualTo(1f)
    assertThat(animator.isRunning).isFalse()
  }

  @Test
  fun `a removed item fades out from where its fade-in got to`() {
    animator.animateAdd(holder)
    animator.runPendingAnimations()
    view.alpha = 0.4f

    animator.animateRemove(holder)

    assertThat(view.alpha).isEqualTo(0.4f)
    assertThat(recorder.finished).containsExactly(holder)
  }

  @Test
  fun `removing a sliding item ends the slide first and keeps its position`() {
    animator.animateMove(holder, 0, 0, 0, 100)
    animator.runPendingAnimations()
    view.translationY = -40f

    animator.animateRemove(holder)
    animator.runPendingAnimations()

    // The slide is reported finished before the removal starts, so RecyclerView can take the view
    // out as soon as the fade ends.
    assertThat(recorder.finished).containsExactly(holder)
    assertThat(view.translationY).isEqualTo(-40f)
    settle()
    assertThat(recorder.finished).hasSize(2)
    assertThat(view.translationY).isEqualTo(0f)
  }

  @Test
  fun `a removed item fades out in place even if it was sliding`() {
    val frames = ManualFrames()
    val animator = LazyCollectionItemAnimator(frames)
    animator.animateMove(holder, 0, 0, 0, 100)
    animator.runPendingAnimations()
    view.translationY = -40f

    animator.animateRemove(holder)
    animator.runPendingAnimations()
    frames.advance() // The first frame only records the start time.
    frames.advance()

    assertThat(view.alpha).isStrictlyBetween(0f, 1f)
    assertThat(view.translationY).isEqualTo(-40f)
    animator.endAnimations()
  }

  @Test
  fun `animations are not reported finished while a later item is still waiting to start`() {
    val other = TestViewHolder(View(RuntimeEnvironment.getApplication()))
    animator.animateMove(holder, 0, 0, 0, 100)
    animator.animateMove(other, 0, 0, 0, 100)
    view.translationY = 0f // The first move is already at rest when the runner starts.
    var finishedCallbacks = 0
    animator.isRunning { finishedCallbacks++ }

    animator.runPendingAnimations()

    assertThat(finishedCallbacks).isEqualTo(0)
    assertThat(animator.isRunning).isTrue()
    animator.endAnimations()
    assertThat(finishedCallbacks).isEqualTo(1)
  }

  @Test
  fun `ending animations from a finish callback also ends items that have not started`() {
    val other = TestViewHolder(View(RuntimeEnvironment.getApplication()))
    ItemAnimatorFinishRecorder(animator) { finished ->
      if (finished === holder) {
        animator.endAnimations()
      }
    }
    animator.animateMove(holder, 0, 0, 0, 100)
    animator.animateMove(other, 0, 0, 0, 100)
    view.translationY = 0f

    animator.runPendingAnimations()

    assertThat(animator.isRunning).isFalse()
    assertThat(other.itemView.translationY).isEqualTo(0f)
  }

  @Test
  fun `an interrupted move continues from where it was drawn`() {
    animator.animateMove(holder, 0, 0, 0, 100)
    animator.runPendingAnimations()
    view.translationY = -40f

    // A new layout moves the item again while the first move is running.
    animator.animateMove(holder, 0, 100, 0, 200)

    assertThat(recorder.finished).containsExactly(holder)
    assertThat(view.translationY).isEqualTo(-140f)
    animator.endAnimations()
    assertThat(recorder.finished).hasSize(2)
  }

  @Test
  fun `a move whose offset was reset before it started finishes immediately`() {
    animator.animateMove(holder, 0, 0, 0, 100)
    view.translationY = 0f

    animator.runPendingAnimations()

    assertThat(recorder.finished).containsExactly(holder)
    assertThat(animator.isRunning).isFalse()
  }

  @Test
  fun `a move without an offset finishes without animating`() {
    assertThat(animator.animateMove(holder, 0, 100, 0, 100)).isFalse()

    assertThat(recorder.finished).containsExactly(holder)
    assertThat(animator.isRunning).isFalse()
  }

  @Test
  fun `a change between two holders swaps them without animating`() {
    val newView = View(RuntimeEnvironment.getApplication())
    val newHolder = TestViewHolder(newView)

    assertThat(animator.animateChange(holder, newHolder, 0, 0, 0, 0)).isFalse()

    assertThat(recorder.finished).containsExactly(holder, newHolder)
    assertThat(animator.isRunning).isFalse()
  }

  @Test
  fun `endAnimations finishes pending and running animations`() {
    val moved = View(RuntimeEnvironment.getApplication())
    val movedHolder = TestViewHolder(moved)
    animator.animateAdd(holder)
    animator.runPendingAnimations()
    animator.animateMove(movedHolder, 0, 0, 0, 100)
    assertThat(animator.isRunning).isTrue()

    animator.endAnimations()

    assertThat(animator.isRunning).isFalse()
    assertThat(view.alpha).isEqualTo(1f)
    assertThat(moved.translationY).isEqualTo(0f)
    assertThat(recorder.finished).containsExactlyInAnyOrder(holder, movedHolder)
  }

  @Test
  fun `resolve replaces the marker with a new instance every time`() {
    val first = LazyCollectionItemAnimator.resolve(LazyCollectionItemAnimator.Default)
    val second = LazyCollectionItemAnimator.resolve(LazyCollectionItemAnimator.Default)

    assertThat(first).isInstanceOf(LazyCollectionItemAnimator::class.java)
    assertThat(first).isNotSameAs(LazyCollectionItemAnimator.Default)
    assertThat(second).isNotSameAs(first)
  }

  @Test
  fun `resolve passes other animators through`() {
    val custom = Recycler.NoUpdateItemAnimator()

    assertThat(LazyCollectionItemAnimator.resolve(custom)).isSameAs(custom)
    assertThat(LazyCollectionItemAnimator.resolve(null)).isNull()
  }

  /**
   * What a frame does after RecyclerView asks for a layout at the same size: measure, then layout.
   */
  private fun relayoutWithSameBounds() {
    recyclerView.requestLayout()
    val exactly100 = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
    recyclerView.measure(exactly100, exactly100)
    recyclerView.layout(0, 0, 100, 100)
  }

  /** Advances the clock so that springs started by runPendingAnimations settle. */
  private fun settle() {
    ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
  }

  private class TestViewHolder(view: View) : RecyclerView.ViewHolder(view)

  /** Runs the springs' frames on demand, 16 ms apart on the test clock. */
  private class ManualFrames : FrameCallbackScheduler {
    private val callbacks = mutableListOf<Runnable>()

    override fun postFrameCallback(frameCallback: Runnable) {
      callbacks.add(frameCallback)
    }

    override fun isCurrentThread(): Boolean = true

    fun advance() {
      ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
      val due = callbacks.toList()
      callbacks.clear()
      due.forEach { it.run() }
    }
  }
}
