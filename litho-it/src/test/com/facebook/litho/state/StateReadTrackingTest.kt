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

package com.facebook.litho.state

import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.mutableScatterSetOf
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.GlobalKey
import com.facebook.litho.KComponent
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.LithoView
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.components.StateReadingTestLayoutComponent
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.layout.RenderWithConstraints
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.rendercore.IntSize
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.utils.withEqualDimensions
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(LithoTestRunner::class)
class StateReadTrackingTest {

  @Rule
  @JvmField
  val lithoTestRule =
      LithoTestRule(ComponentsConfiguration.defaultInstance.copy(enableStateReadTracking = true))

  @Test
  fun `no state is read in resolve`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent = RootComponent(ref, readers) { MyKComponent(readState = false, it.getState) }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve).isEmpty()
    assertThat(layoutState.stateReads).isNullOrEmpty()
    assertThat(readers.size).isZero
  }

  @Test
  fun `state read in resolve is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent = RootComponent(ref, readers) { MyKComponent(readState = true, it.getState) }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve[ref.get()]).isEqualTo(readers)
    assertThat(readers.size).isOne

    assertThat(layoutState.stateReads).isNullOrEmpty()
  }

  @Test
  fun `state read in primitive in resolve is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) {
          MyPrimitiveComponent(readInResolve = true, getState = it.getState)
        }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve[ref.get()]).isEqualTo(readers)
    assertThat(readers.size).isOne

    assertThat(layoutState.stateReads).isNullOrEmpty()
  }

  @Test
  fun `state read in spec in resolve is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) { data ->
          StateReadingTestLayoutComponent(readState = true, getState = data.getState)
        }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(readers.size).isOne
    assertStateReadersMatch(stateReadsInResolve[ref.get()], readers)

    assertThat(layoutState.stateReads).isNullOrEmpty()
  }

  @Test
  fun `state read in container in resolve is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) { data ->
          Column {
            child(MyKComponent(readState = true, data.getState))
            child(MyPrimitiveComponent(readInResolve = true, getState = data.getState))
          }
        }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve[ref.get()]).isEqualTo(readers)
    assertThat(readers.size).isEqualTo(2)

    assertThat(layoutState.stateReads).isNullOrEmpty()
  }

  @Test
  fun `state read in sub-tree in resolve is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()

    class RootWithSubTreeComponent : KComponent() {
      override fun ComponentScope.render(): Component? {
        return Column {
          child(
              RootComponent(ref, readers) {
                MyPrimitiveComponent(readInResolve = true, getState = it.getState)
              },
          )
        }
      }
    }

    val testView = lithoTestRule.render { RootWithSubTreeComponent() }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve[ref.get()]).isEqualTo(readers)
    assertThat(readers.size).isOne

    assertThat(layoutState.stateReads).isNullOrEmpty()
  }

  @Test
  fun `state read in nested component is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) { data ->
          RenderWithConstraints { MyKComponent(readState = true, data.getState) }
        }
    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve).isEmpty()

    assertThat(layoutState.stateReads?.get(ref.get())).isEqualTo(readers)
    assertThat(readers.size).isOne
  }

  @Test
  fun `state read in nested spec is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) { data ->
          RenderWithConstraints {
            StateReadingTestLayoutComponent(readState = true, getState = data.getState)
          }
        }
    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve).isEmpty()

    assertThat(readers.size).isOne
    assertStateReadersMatch(layoutState.stateReads?.get(ref.get()), readers)
  }

  @Test
  fun `state read in nested primitive is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) { data ->
          RenderWithConstraints {
            // Note that this primitive's resolve actually gets triggered during layout
            MyPrimitiveComponent(readInResolve = true, getState = data.getState)
          }
        }
    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve).isEmpty()

    assertThat(layoutState.stateReads?.get(ref.get())).isEqualTo(readers)
    assertThat(readers.size).isOne
  }

  @Test
  fun `state read in nested container is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) { data ->
          RenderWithConstraints {
            Column {
              child(MyKComponent(readState = true, data.getState))
              child(MyPrimitiveComponent(readInResolve = true, getState = data.getState))
            }
          }
        }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve).isEmpty()

    assertThat(layoutState.stateReads?.get(ref.get())).isEqualTo(readers)
    assertThat(readers.size).isEqualTo(2)
  }

  @Test
  fun `state read in nested sub-tree is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()

    class RootWithSubTreeComponent : KComponent() {
      override fun ComponentScope.render() = RenderWithConstraints {
        Column {
          child(
              RootComponent(ref, readers) {
                MyPrimitiveComponent(readInResolve = true, getState = it.getState)
              },
          )
        }
      }
    }

    val testView = lithoTestRule.render { RootWithSubTreeComponent() }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = layoutState.resolveResult.outputs?.stateReads

    assertThat(stateReadsInResolve).isNullOrEmpty()

    assertThat(layoutState.stateReads?.get(ref.get())).isEqualTo(readers)
    assertThat(readers.size).isOne
  }

  @Test
  fun `state read in primitive in layout is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) {
          MyPrimitiveComponent(readInLayout = true, getState = it.getState)
        }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve).isEmpty()

    assertThat(layoutState.stateReads?.get(ref.get())).isEqualTo(readers)
    assertThat(readers.size).isOne
  }

  @Test
  fun `state read in resolve and layout is tracked`() {
    val ref = AtomicReference<StateId>()
    val readers = mutableScatterSetOf<GlobalKey>()
    val rootComponent =
        RootComponent(ref, readers) {
          MyPrimitiveComponent(readInResolve = true, readInLayout = true, getState = it.getState)
        }

    val testView = lithoTestRule.render { rootComponent }

    val layoutState = checkNotNull(testView.committedLayoutState)
    val stateReadsInResolve = checkNotNull(layoutState.resolveResult.outputs?.stateReads)

    assertThat(stateReadsInResolve[ref.get()]).isEqualTo(readers)
    assertThat(layoutState.stateReads?.get(ref.get())).isEqualTo(readers)

    assertThat(readers.size).isOne
  }

  /**
   * Compares two ScatterSets of GlobalKey by string representation. This is needed because spec
   * component tests wrap keys in StringGlobalKey while the framework may use DefaultGlobalKey. Both
   * represent the same logical key but have different equals/hashCode implementations.
   */
  private fun assertStateReadersMatch(
      actual: ScatterSet<GlobalKey>?,
      expected: ScatterSet<GlobalKey>,
  ) {
    assertThat(actual).isNotNull
    assertThat(actual!!.size).isEqualTo(expected.size)
    val actualStrings = mutableSetOf<String>()
    actual.forEach { actualStrings.add(it.toString()) }
    val expectedStrings = mutableSetOf<String>()
    expected.forEach { expectedStrings.add(it.toString()) }
    assertThat(actualStrings).isEqualTo(expectedStrings)
  }
}

private class RootComponent(
    private val stateRef: AtomicReference<StateId>,
    private val stateReaders: MutableScatterSet<GlobalKey> = mutableScatterSetOf(),
    private val content: ComponentScope.(RootData) -> Component,
) : KComponent() {
  override fun ComponentScope.render(): Component? {
    val state = useState { 0 }
    useEffect(Unit) {
      stateRef.set(state.stateId)
      null
    }
    val rootData = RootData { readerId ->
      stateReaders.add(readerId)
      state.value
    }
    return content(rootData)
  }
}

private class RootData(val getState: (GlobalKey) -> Int)

private class MyKComponent(
    private val readState: Boolean,
    private val getState: (readerId: GlobalKey) -> Int,
) : KComponent() {
  override fun ComponentScope.render(): Component? {
    if (readState) getState(context.globalKey)
    return Column()
  }
}

@Suppress("PrimitiveLayoutBehaviorCaptureDetector")
private class MyPrimitiveComponent(
    private val readInResolve: Boolean = false,
    private val readInLayout: Boolean = false,
    private val readInBinder: Boolean = false,
    private val getState: (readerId: GlobalKey) -> Int,
) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    val readerId = context.globalKey
    if (readInResolve) getState(readerId)
    return LithoPrimitive(
        layoutBehavior =
            LayoutBehavior { sizeConstraints ->
              if (readInLayout) getState(readerId)
              PrimitiveLayoutResult(size = IntSize.withEqualDimensions(sizeConstraints))
            },
        mountBehavior =
            MountBehavior(ViewAllocator { context -> LithoView(context) }) {
              bind(Any()) {
                if (readInBinder) getState(readerId)
                onUnbind {}
              }
            },
        style = null,
    )
  }
}
