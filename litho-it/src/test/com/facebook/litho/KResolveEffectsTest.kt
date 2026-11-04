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

package com.facebook.litho

import com.facebook.litho.core.width
import com.facebook.litho.flexbox.flex
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.exactly
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class KResolveEffectsTest {

  @Rule @JvmField val lithoViewRule = LithoTestRule()

  private val events: MutableList<String> = Collections.synchronizedList(ArrayList())

  @Test
  fun `the resolve effect runs when the result is committed, even if it is never mounted`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread("resolver", tree, Effects(name = "a", deps = 0))

    assertThat(events).containsExactly("a tracked", "commit a on resolver")
    tree.release()
    assertThat(events).containsExactly("a tracked", "commit a on resolver", "cleanup a")
  }

  @Test
  fun `the resolve effect reruns only when its deps change, and cleans up when removed`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread("resolver", tree, Column { child(Effects(name = "a", deps = 0)) })
    resolveOnThread("resolver", tree, Column { child(Effects(name = "b", deps = 0)) })
    assertThat(events.filterNot { it.endsWith("tracked") }).containsExactly("commit a on resolver")

    resolveOnThread("resolver", tree, Column { child(Effects(name = "c", deps = 1)) })
    resolveOnThread("resolver", tree, Column {})
    tree.release()

    assertThat(events.filterNot { it.endsWith("tracked") })
        .containsExactly("commit a on resolver", "cleanup a", "commit c on resolver", "cleanup c")
  }

  @Test
  fun `a render whose result is superseded before it commits is discarded`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()
    val gate = Gate()

    val stale = Thread {
      tree.setRootAndSizeSpecSync(
          Effects(name = "stale", deps = 0, gate = gate),
          exactly(100),
          exactly(100),
      )
    }
    stale.start()
    assertThat(gate.rendered.await(5, TimeUnit.SECONDS)).isTrue()

    // Discarded as soon as the newer result commits, while the stale render is still running.
    resolveOnThread("fresh", tree, Effects(name = "fresh", deps = 0))
    assertThat(events.filterNot { it.endsWith("tracked") })
        .containsExactly("commit fresh on fresh", "discard stale")
    gate.proceed.countDown()
    stale.join(5000)
    tree.release()

    assertThat(events.filterNot { it.endsWith("tracked") })
        .containsExactly("commit fresh on fresh", "discard stale", "cleanup fresh")
  }

  @Test
  fun `a render whose tree is released before it commits is discarded`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()
    val gate = Gate()

    val pending = Thread {
      tree.setRootAndSizeSpecSync(
          Effects(name = "a", deps = 0, gate = gate),
          exactly(100),
          exactly(100),
      )
    }
    pending.start()
    assertThat(gate.rendered.await(5, TimeUnit.SECONDS)).isTrue()

    tree.release()
    assertThat(events).containsExactly("a tracked", "discard a")
    gate.proceed.countDown()
    pending.join(5000)

    assertThat(events).containsExactly("a tracked", "discard a")
  }

  @Test
  fun `the resolve effect runs before the component is mounted and its effects attach`() {
    lithoViewRule
        .render(widthSpec = exactly(100), heightSpec = exactly(100)) {
          Effects(name = "a", deps = 0, withUseEffect = true)
        }
        .detachFromWindow()
        .release()

    assertThat(events.map { it.substringBefore(" on ") })
        .containsExactly("a tracked", "commit a", "attach a", "detach a", "cleanup a")
  }

  @Test
  fun `a render in a measurement that isn't kept is discarded when the measurement returns`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread("resolver", tree, Measuring(Effects("measured", 0), keepResult = false))
    tree.release()

    assertThat(events)
        .containsExactly(
            "parent tracked",
            "measured tracked",
            "discard measured",
            "parent measured",
        )
  }

  @Test
  fun `a nested tree inside a measurement that isn't kept is discarded with it`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread(
        "resolver",
        tree,
        // In a column, so that measuring it lays the nested tree out.
        Measuring(
            Column { child(RenderWithConstraints { Effects("nested", 0) }) },
            keepResult = false,
        ),
    )
    tree.release()

    assertThat(events)
        .containsExactly("parent tracked", "nested tracked", "discard nested", "parent measured")
  }

  @Test
  fun `a render in a measurement the tree may reuse is a transient run`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread("resolver", tree, Measuring(Effects("measured", 0), keepResult = true))

    assertThat(events.first { it.startsWith("measured") }).isEqualTo("measured transient")
    tree.release()
  }

  @Test
  fun `the resolve effect of a render during layout runs when the layout is committed`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread("resolver", tree, Column { child(RenderWithConstraints { Effects("a", 0) }) })

    assertThat(events).containsExactly("a tracked", "commit a on resolver")
    tree.release()
    assertThat(events).containsExactly("a tracked", "commit a on resolver", "cleanup a")
  }

  @Test
  fun `every render during layout gets exactly one outcome`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    // A growing child next to a fixed one can be measured more than once, and each measurement
    // under new constraints renders it again.
    repeat(3) { i ->
      resolveOnThread(
          "resolver",
          tree,
          Row {
            child(Row(style = Style.width((10 + i).px)))
            child(RenderWithConstraints(style = Style.flex(grow = 1f)) { Effects("a", i) })
          },
      )
    }
    tree.release()

    val renders = events.count { it == "a tracked" }
    val commits = events.count { it.startsWith("commit a") }
    val discards = events.count { it == "discard a" }
    assertThat(commits + discards).isEqualTo(renders)
    assertThat(events.count { it == "cleanup a" }).isEqualTo(commits)
  }

  @Test
  fun `a render that throws under an error boundary is discarded when the result commits`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread("resolver", tree, Boundary(Effects("a", 0, throws = true)))

    assertThat(events).containsExactly("a tracked", "discard a")
    tree.release()
    assertThat(events).containsExactly("a tracked", "discard a")
  }

  @Test
  fun `a render whose child throws under an error boundary is discarded when the result commits`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread(
        "resolver",
        tree,
        Boundary(Effects("parent", 0, child = Effects("child", 0, throws = true))),
    )

    assertThat(events)
        .containsExactlyInAnyOrder(
            "parent tracked",
            "child tracked",
            "discard child",
            "discard parent",
        )
    tree.release()
  }

  @Test
  fun `a result committed by a resolve effect still detaches that effect`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()

    resolveOnThread(
        "resolver",
        tree,
        Effects(
            "a",
            0,
            // With other deps, so that it replaces the effect rather than keeping it.
            onCommit = { tree.setRootAndSizeSpecSync(Effects("b", 1), exactly(100), exactly(100)) },
        ),
    )

    assertThat(events.filterNot { it.endsWith("tracked") })
        .containsExactly("commit a on resolver", "cleanup a", "commit b on resolver")
    tree.release()
  }

  @Test
  fun `a cleanup that throws on release doesn't stop the others or the release`() {
    val tree = ComponentTree.create(lithoViewRule.context).build()
    resolveOnThread(
        "resolver",
        tree,
        Column {
          child(Effects("a", 0, cleanupThrows = true))
          child(Effects("b", 0))
        },
    )

    assertThatThrownBy { tree.release() }.hasMessage("cleanup a failed")

    assertThat(events).contains("cleanup a", "cleanup b")
    assertThat(tree.isReleased).isTrue()
  }

  private fun resolveOnThread(name: String, tree: ComponentTree, component: Component) {
    val thread =
        Thread({ tree.setRootAndSizeSpecSync(component, exactly(100), exactly(100)) }, name)
    thread.start()
    thread.join(5000)
  }

  /** Lets a test block a render after its callbacks are registered, until [proceed]. */
  private class Gate {
    val rendered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
  }

  /**
   * Records whether its render is transient, its resolve effect and its discard callback. With
   * [gate], render signals it once its callbacks are registered and waits to proceed.
   */
  private inner class Effects(
      private val name: String,
      private val deps: Int,
      private val gate: Gate? = null,
      private val withUseEffect: Boolean = false,
      private val throws: Boolean = false,
      private val child: Component? = null,
      private val cleanupThrows: Boolean = false,
      private val onCommit: () -> Unit = {},
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      events += if (isTransientRun) "$name transient" else "$name tracked"
      useResolveEffect(deps) {
        events += "commit $name on ${Thread.currentThread().name}"
        onCommit()
        onCleanup {
          events += "cleanup $name"
          if (cleanupThrows) {
            throw IllegalStateException("cleanup $name failed")
          }
        }
      }
      useOnDiscard { events += "discard $name" }
      if (withUseEffect) {
        useEffect(deps) {
          events += "attach $name"
          onCleanup { events += "detach $name" }
        }
      }
      gate?.rendered?.countDown()
      gate?.proceed?.await(5, TimeUnit.SECONDS)
      if (throws) {
        throw IllegalStateException("$name failed")
      }
      return child ?: Row()
    }
  }

  /** Catches what its [child] throws while it resolves, and leaves it out. */
  private class Boundary(private val child: Component) : KComponent() {
    override fun ComponentScope.render(): Component {
      useErrorBoundary { _: Exception -> }
      return Column { child(child) }
    }
  }

  /** Measures [child] while it renders, keeping the result for the tree only if [keepResult]. */
  private inner class Measuring(
      private val child: Component,
      private val keepResult: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      events += "parent tracked"
      child.measure(context, exactly(50), exactly(50), Size(), keepResult)
      if (!keepResult) {
        events += "parent measured"
      }
      return Row()
    }
  }
}
