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

import android.graphics.Rect
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.debug.LithoDebugEvent
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.debug.DebugEvent
import com.facebook.rendercore.debug.DebugEventAttribute
import com.facebook.rendercore.debug.DebugEventBus
import com.facebook.rendercore.debug.DebugEventSubscriber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
class LithoSaveableStateRegistryTest {

  @Test
  fun `snapshot restores a live value`() {
    val registry = LithoSaveableStateRegistry()
    LiveValue(registry, "counter", 3)

    val restored = LithoSaveableStateRegistry(registry.snapshot())

    assertThat(restored.restoredValue("counter")?.value).isEqualTo(3)
  }

  @Test
  fun `two live values for a key save neither`() {
    ShadowLog.clear()
    val restored = LithoSaveableStateRegistry(savedState("counter" to 5))
    val first = LiveValue(restored, "counter", 1)
    val second = LiveValue(restored, "counter", 2)

    assertThat(restored.snapshot().containsKey("counter")).isFalse()
    assertThat(restored.restoredValue("counter")).isNull()
    assertThat(restored.snapshot().containsKey("counter")).isFalse()
    assertThat(duplicateKeyWarnings()).isEqualTo(1)

    first.close()
    second.close()
    assertThat(restored.snapshot().isEmpty).isTrue()
  }

  @Test
  fun `a value is not saved after its tree is released unless retained`() {
    val registry = LithoSaveableStateRegistry()
    val live = LiveValue(registry, "counter", 1)
    live.set(4)

    live.close()

    assertThat(registry.restoredValue("counter")).isNull()
    assertThat(registry.snapshot().isEmpty).isTrue()
  }

  @Test
  fun `owner can retain active values before replacing its tree`() {
    val registry = LithoSaveableStateRegistry()
    val live = LiveValue(registry, "counter", 1)
    live.set(4)

    registry.retainActiveValues()
    live.close()

    assertThat(registry.restoredValue("counter")?.value).isEqualTo(4)
  }

  @Test
  fun `a live value replaces its restored value in the snapshot`() {
    val restored = LithoSaveableStateRegistry(savedState("counter" to 2))

    LiveValue(restored, "counter", 5)

    assertThat(
        LithoSaveableStateRegistry(restored.snapshot()).restoredValue("counter")?.value,
    )
        .isEqualTo(5)
  }

  @Test
  fun `snapshot does not share retained value bundles`() {
    val registry = LithoSaveableStateRegistry(savedState("counter" to 2))

    val snapshot = registry.snapshot()
    snapshot.getBundle("counter")?.clear()

    assertThat(registry.restoredValue("counter")?.value).isEqualTo(2)
    registry.close()
  }

  @Test
  fun `malformed restored value is ignored`() {
    val restoredState = Bundle().apply { putInt("counter", 3) }
    val registry = LithoSaveableStateRegistry(restoredState)

    assertThat(registry.restoredValue("counter")).isNull()
    assertThat(registry.snapshot().containsKey("counter")).isFalse()
  }

  @Test
  fun `restored value that cannot be deserialized is ignored`() {
    val restoredState = parcelRoundTrip(
        Bundle().apply {
          putBundle(
              "counter",
              Bundle().apply { putParcelable("value", UnreadableParcelable()) },
          )
        },
    )
    val registry = LithoSaveableStateRegistry(
        restoredState,
        LithoSaveableStateRegistryTest::class.java.classLoader,
    )

    assertThat(registry.restoredValue("counter")).isNull()
    assertThat(registry.snapshot().containsKey("counter")).isFalse()
  }

  @Test
  fun `a registry reads its restored Bundle once`() {
    val restoredState = savedState("counter" to 1, childKey("child") to savedState("counter" to 2))
    val registry = LithoSaveableStateRegistry(restoredState)
    assertThat(registry.restoredValue("counter")?.value).isEqualTo(1)

    checkNotNull(restoredState.getBundle("counter")).putInt("value", 3)
    checkNotNull(restoredState.getBundle(childKey("child"))?.getBundle("value")).clear()
    restoredState.clear()

    assertThat(registry.restoredValue("counter")?.value).isEqualTo(1)
    assertThat(registry.createChildRegistry("child").restoredValue("counter")?.value).isEqualTo(2)
  }

  @Test
  fun `a restored value that cannot be read is dropped and reported, and the others restore`() {
    val restoredState = parcelRoundTrip(
        savedState(
            "broken" to UnreadableParcelable(),
            "first" to 1,
            "last" to 3,
            childKey("child") to savedState("broken" to UnreadableParcelable(), "counter" to 4),
        ),
    )

    val sources = restoreFailureSources {
      val registry = LithoSaveableStateRegistry(
          restoredState,
          LithoSaveableStateRegistryTest::class.java.classLoader,
      )

      assertThat(registry.restoredValue("broken")).isNull()
      assertThat(registry.restoredValue("first")?.value).isEqualTo(1)
      assertThat(registry.restoredValue("last")?.value).isEqualTo(3)
      val child = registry.createChildRegistry("child")
      assertThat(child.restoredValue("broken")).isNull()
      assertThat(child.restoredValue("counter")?.value).isEqualTo(4)
      assertThat(registry.snapshot().containsKey("broken")).isFalse()
    }

    assertThat(sources).containsExactly("restore", "restore")
  }

  @Test
  fun `a root claim drops and reports the values it cannot read and restores the others`() {
    val root =
        LithoSaveableStateRegistry.ownerBacked(
            LithoSaveableStateRegistryTest::class.java.classLoader,
        ) {
          parcelRoundTrip(
              savedState(
                  "broken" to UnreadableParcelable(),
                  "first" to 1,
                  childKey("child") to
                      savedState("broken" to UnreadableParcelable(), "counter" to 4),
                  childKey("discarded") to savedState("broken" to UnreadableParcelable()),
              ),
          )
        }
    root.discardChildState("discarded")

    val sources = restoreFailureSources {
      assertThat(root.restoredValue("first")?.value).isEqualTo(1)
    }

    assertThat(root.restoredValue("broken")).isNull()
    assertThat(root.createChildRegistry("child").restoredValue("counter")?.value).isEqualTo(4)
    assertThat(sources).containsExactly("root claim", "root claim")
  }

  @Test
  fun `a late restore drops a value that cannot be read and adopts the others`() {
    val root =
        LithoSaveableStateRegistry.ownerBacked(
            LithoSaveableStateRegistryTest::class.java.classLoader,
        ) {
          null
        }
    val row = root.createChildRegistry("list").createChildRegistry("row-0")
    assertThat(row.restoredValue("label")).isNull()

    val sources = restoreFailureSources {
      root.adoptLateRestoredValues(
          parcelRoundTrip(
              savedState(
                  "broken" to UnreadableParcelable(),
                  "header" to "header",
                  childKey("list") to
                      savedState(
                          childKey("row-0") to
                              savedState("broken" to UnreadableParcelable(), "label" to "row 0"),
                      ),
              ),
          ),
      )
    }

    assertThat(root.restoredValue("header")?.value).isEqualTo("header")
    assertThat(row.restoredValue("label")?.value).isEqualTo("row 0")
    assertThat(sources).containsExactly("late restore", "late restore")
  }

  @Test
  fun `a closed registry reads no late restored values`() {
    val root =
        LithoSaveableStateRegistry.ownerBacked(
            LithoSaveableStateRegistryTest::class.java.classLoader,
        ) {
          null
        }
    root.close()

    val sources = restoreFailureSources {
      root.adoptLateRestoredValues(parcelRoundTrip(savedState("broken" to UnreadableParcelable())))
    }

    assertThat(sources).isEmpty()
  }

  @Test
  fun `restored values are read with the host class loader without changing the restored Bundle`() {
    val loaded = mutableListOf<String>()
    val hostClassLoader =
        object : ClassLoader(LithoSaveableStateRegistryTest::class.java.classLoader) {
          override fun loadClass(name: String, resolve: Boolean): Class<*> {
            loaded += name
            return super.loadClass(name, resolve)
          }
        }
    val restoredState = parcelRoundTrip(savedState("bounds" to Rect(1, 2, 3, 4)))
    // An owner may read the Bundle before it hands it over.
    restoredState.keySet()

    val registry = LithoSaveableStateRegistry(restoredState, hostClassLoader)

    assertThat(registry.restoredValue("bounds")?.value).isEqualTo(Rect(1, 2, 3, 4))
    assertThat(loaded).contains(Rect::class.java.name)
    assertThat(restoredState.classLoader).isNotSameAs(hostClassLoader)
  }

  /**
   * ```text
   * Snapshot thread                 Other thread
   * ---------------                 ------------
   * Walk the live values
   * Run a saver outside the lock    Another tree uses the registry
   * Finish snapshot                 Complete without waiting for the saver
   * ```
   */
  @Test
  fun `snapshot does not hold the registry lock while a saver runs`() {
    val registry = LithoSaveableStateRegistry()
    val saverStarted = CountDownLatch(1)
    val allowSaverToFinish = CountDownLatch(1)
    val calls = AtomicInteger()
    val counter = LiveValue(
        registry,
        "counter",
        7,
        Saver(
            save = { value ->
              if (calls.incrementAndGet() == 1) {
                saverStarted.countDown()
                check(allowSaverToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
              }
              value
            },
            restore = { it },
        ),
    )
    val snapshot = FutureTask { registry.snapshot() }
    val useOther = FutureTask { LiveValue(registry, "other", 2) }
    val snapshotThread = Thread(snapshot)
    val otherThread = Thread(useOther)

    try {
      snapshotThread.start()
      check(saverStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      otherThread.start()

      val other = useOther.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      allowSaverToFinish.countDown()

      val restored = LithoSaveableStateRegistry(snapshot.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      assertThat(restored.restoredValue("counter")?.value).isEqualTo(7)
      assertThat(calls).hasValue(1)
      other.close()
      counter.close()
    } finally {
      allowSaverToFinish.countDown()
      snapshotThread.interrupt()
      otherThread.interrupt()
      registry.close()
    }
  }

  @Test
  fun `unsupported updated value reports its owning key during snapshot`() {
    val registry = LithoSaveableStateRegistry()
    val live = LiveValue(registry, "counter", 1)
    live.set(Any())

    assertThatThrownBy { registry.snapshot() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("counter")
        .hasMessageContaining(Any::class.java.name)
  }

  @Test
  fun `unsupported updated value reports its owning key when retained`() {
    val registry = LithoSaveableStateRegistry()
    val live = LiveValue(registry, "counter", 1)
    live.set(Any())

    assertThatThrownBy { registry.retainActiveValues() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("counter")
        .hasMessageContaining(Any::class.java.name)
  }

  @Test
  fun `close is idempotent and makes later access inert`() {
    val registry = LithoSaveableStateRegistry(savedState("counter" to 1))
    val other = LiveValue(registry, "other", 1)

    registry.close()
    registry.close()

    assertThat(registry.snapshot().isEmpty).isTrue()
    assertThat(registry.restoredValue("counter")).isNull()
    val lateSaves = AtomicInteger()
    val late = LiveValue(
        registry,
        "late",
        1,
        Saver(
            save = { value ->
              lateSaves.incrementAndGet()
              value
            },
            restore = { it },
        ),
    )
    registry.retainActiveValues()
    assertThat(registry.snapshot().isEmpty).isTrue()
    assertThat(lateSaves.get()).isZero()
    late.close()
    other.close()
  }

  /**
   * ```text
   * Background calculation             Main thread
   * ----------------------             -----------
   * Read the nested tree's registry
   *                                    Release the nested tree
   *                                    Save the parent
   * Read and use it again
   * ```
   */
  @Test
  fun `closing a nested registry mid-calculation keeps parent saves working`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    LiveValue(child, "counter", 7)

    child.close()

    assertThat(parent.snapshot().isEmpty).isTrue()
    assertThat(child.restoredValue("counter")).isNull()
    LiveValue(child, "again", 8).close()
    assertThat(parent.snapshot().isEmpty).isTrue()
    parent.close()
  }

  /**
   * ```text
   * Root -> "list" -> "row-0" reads before the values arrive   row-0 reads nothing
   * The root adopts the late restored values                   row-0 reads "row 0" again
   * ```
   */
  @Test
  fun `a late restore reaches nested registries that read before it`() {
    val root = LithoSaveableStateRegistry.ownerBacked(null) { null }
    val row = root.createChildRegistry("list").createChildRegistry("row-0")
    assertThat(row.restoredValue("label")).isNull()

    root.adoptLateRestoredValues(savedList())

    assertThat(row.generation).isEqualTo(1)
    assertThat(row.restoredValue("label")?.value).isEqualTo("row 0")
  }

  @Test
  fun `a nested registry created after a late restore claims its restored values`() {
    val root = LithoSaveableStateRegistry.ownerBacked(null) { null }
    val list = root.createChildRegistry("list")
    assertThat(list.createChildRegistry("row-0").restoredValue("label")).isNull()

    root.adoptLateRestoredValues(savedList())

    val row = list.createChildRegistry("row-1")
    assertThat(row.restoredValue("label")?.value).isEqualTo("row 1")
    assertThat(row.generation).isZero()
  }

  /**
   * ```text
   * row-0 reads before the values arrive, then the root adopts them
   * row-0 takes its restored value, so "list" replaces the root's copy with its own snapshot
   * Save                                     row-0 and row-1, which never read, are still saved
   * ```
   */
  @Test
  fun `a save after a late restore keeps the restored values of nested registries`() {
    val root = LithoSaveableStateRegistry.ownerBacked(null) { null }
    val row = root.createChildRegistry("list").createChildRegistry("row-0")
    assertThat(row.restoredValue("label")).isNull()
    root.adoptLateRestoredValues(savedList())

    LiveValue(row, "label", row.restoredValue("label")?.value)

    val saved = root.snapshotForOwner()
    assertThat(savedLabel(saved, "row-0")).isEqualTo("row 0")
    assertThat(savedLabel(saved, "row-1")).isEqualTo("row 1")
  }

  /**
   * ```text
   * Restored from a save: row-0 takes its value, row-1 and its "detail" child never read
   * Save again                               row-1 and its "detail" child are saved unchanged
   * ```
   */
  @Test
  fun `nested values restore from a save and a part no child read is saved again unchanged`() {
    val source = LithoSaveableStateRegistry()
    val sourceList = source.createChildRegistry("list")
    LiveValue(sourceList.createChildRegistry("row-0"), "label", "row 0")
    val sourceRow = sourceList.createChildRegistry("row-1")
    LiveValue(sourceRow, "label", "row 1")
    LiveValue(sourceRow.createChildRegistry("detail"), "text", "more")

    val restored = LithoSaveableStateRegistry(source.snapshot())
    val row = restored.createChildRegistry("list").createChildRegistry("row-0")
    LiveValue(row, "label", row.restoredValue("label")?.value)
    val list = LithoSaveableStateRegistry(restored.snapshot()).createChildRegistry("list")

    assertThat(list.createChildRegistry("row-0").restoredValue("label")?.value).isEqualTo("row 0")
    val unread = list.createChildRegistry("row-1")
    assertThat(unread.restoredValue("label")?.value).isEqualTo("row 1")
    assertThat(unread.createChildRegistry("detail").restoredValue("text")?.value).isEqualTo("more")
  }

  @Test
  fun `a late restore keeps ambiguous keys dropped`() {
    val root = LithoSaveableStateRegistry.ownerBacked(null) { null }
    LiveValue(root, "header", "first")
    LiveValue(root, "header", "second")
    root.snapshotForOwner()
    val first = root.createChildRegistry("list")
    val second = root.createChildRegistry("list")
    assertThat(first.restoredValue("label")).isNull()
    assertThat(second.restoredValue("label")).isNull()

    root.adoptLateRestoredValues(savedList())

    assertThat(root.restoredValue("header")).isNull()
    assertThat(first.createChildRegistry("row-0").restoredValue("label")).isNull()
    assertThat(second.createChildRegistry("row-0").restoredValue("label")).isNull()
    assertThat(root.snapshotForOwner().isEmpty).isTrue()
  }

  @Test
  fun `a detached child is not saved when a tree uses it`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    LiveValue(child, "first", 1)

    child.detachFromParent()
    LiveValue(child, "second", 2)

    assertThat(parent.snapshot().isEmpty).isTrue()
  }

  @Test
  fun `a child detached before it reads claims nothing when a tree uses it`() {
    val parent = LithoSaveableStateRegistry(savedChild("child", 5))
    val detached = parent.createChildRegistry("child")
    detached.detachFromParent()

    LiveValue(detached, "other", 1)

    assertThat(parent.createChildRegistry("child").restoredValue("counter")?.value).isEqualTo(5)
  }

  @Test
  fun `attaching a child saves the values it got while detached`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    child.detachFromParent()
    LiveValue(child, "counter", 3)

    child.attachToParent()

    assertThat(restoredChild(parent.snapshot(), "child").restoredValue("counter")?.value)
        .isEqualTo(3)
  }

  @Test
  fun `attaching a child that was saved when detached saves it again`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    val live = LiveValue(child, "counter", 1)
    child.detachFromParent()
    live.set(4)
    assertThat(parent.snapshot().isEmpty).isTrue()

    child.attachToParent()

    assertThat(restoredChild(parent.snapshot(), "child").restoredValue("counter")?.value)
        .isEqualTo(4)
  }

  @Test
  fun `retaining a child without live values claims nothing from its parent`() {
    val parent = LithoSaveableStateRegistry(savedChild("child", 5))

    parent.createChildRegistry("child").retainActiveValues()

    assertThat(parent.createChildRegistry("child").restoredValue("counter")?.value).isEqualTo(5)
  }

  @Test
  fun `retaining a root without live values claims nothing from its owner`() {
    val claims = AtomicInteger()
    val root =
        LithoSaveableStateRegistry.ownerBacked(null) {
          claims.incrementAndGet()
          null
        }

    root.retainActiveValues()

    assertThat(claims.get()).isZero()
  }

  @Test
  fun `a child left out of its parent's snapshot keeps its values and children`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    LiveValue(child, "counter", 3)
    LiveValue(child.createChildRegistry("nested"), "counter", 4)

    child.setSavedInParent(false)

    assertThat(parent.snapshot().isEmpty).isTrue()
    val childValues = LithoSaveableStateRegistry(child.snapshot())
    assertThat(childValues.restoredValue("counter")?.value).isEqualTo(3)
    assertThat(childValues.createChildRegistry("nested").restoredValue("counter")?.value)
        .isEqualTo(4)
  }

  @Test
  fun `a child left out of its parent's snapshot still holds its key`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    LiveValue(child, "counter", 1)
    child.setSavedInParent(false)

    LiveValue(parent.createChildRegistry("child"), "counter", 2)
    child.setSavedInParent(true)

    assertThat(parent.snapshot().isEmpty).isTrue()
  }

  @Test
  fun `a child saved in its parent again is saved with its current values`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    val live = LiveValue(child, "counter", 1)
    child.setSavedInParent(false)
    live.set(6)

    child.setSavedInParent(true)

    assertThat(restoredChild(parent.snapshot(), "child").restoredValue("counter")?.value)
        .isEqualTo(6)
  }

  @Test
  fun `a child left out of its parent's snapshot reads its restored values without saving them`() {
    val parent = LithoSaveableStateRegistry(savedChild("child", 5))
    val child = parent.createChildRegistry("child")
    child.setSavedInParent(false)

    assertThat(child.restoredValue("counter")?.value).isEqualTo(5)

    assertThat(parent.snapshot().isEmpty).isTrue()
    child.close()
    assertThat(parent.snapshot().isEmpty).isTrue()
  }

  @Test
  fun `a parent keeps no older copy of a child left out of its snapshot`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    LiveValue(child, "counter", 1)
    parent.retainActiveValues()

    child.setSavedInParent(false)
    child.close()

    assertThat(parent.snapshot().isEmpty).isTrue()
  }

  @Test
  fun `retaining a parent does not keep a child left out of its snapshot`() {
    val parent = LithoSaveableStateRegistry()
    val child = parent.createChildRegistry("child")
    LiveValue(child, "counter", 1)
    child.setSavedInParent(false)

    parent.retainActiveValues()
    child.close()

    assertThat(parent.snapshot().isEmpty).isTrue()
  }

  @Test
  fun `a child that read its restored values and is saved again saves them`() {
    val parent = LithoSaveableStateRegistry(savedChild("child", 5))
    val child = parent.createChildRegistry("child")
    assertThat(child.restoredValue("counter")?.value).isEqualTo(5)
    child.setSavedInParent(false)

    child.setSavedInParent(true)

    assertThat(restoredChild(parent.snapshot(), "child").restoredValue("counter")?.value)
        .isEqualTo(5)
  }

  @Test
  fun `leaving out a child that has not read claims nothing and keeps its restored values saved`() {
    val parent = LithoSaveableStateRegistry(savedChild("child", 5))
    val child = parent.createChildRegistry("child")

    child.setSavedInParent(false)
    assertThat(restoredChild(parent.snapshot(), "child").restoredValue("counter")?.value)
        .isEqualTo(5)
    child.setSavedInParent(true)

    assertThat(parent.createChildRegistry("child").restoredValue("counter")?.value).isEqualTo(5)
  }

  @Test
  fun `discarding child state before the claim drops the restored value and claims nothing`() {
    val claims = AtomicInteger()
    val saved = savedState(
        childKey("dropped") to savedState("counter" to 1),
        childKey("kept") to savedState("counter" to 2),
    )
    val root =
        LithoSaveableStateRegistry.ownerBacked(null) {
          claims.incrementAndGet()
          saved
        }

    root.discardChildState("dropped")

    assertThat(claims.get()).isZero()
    assertThat(root.createChildRegistry("dropped").restoredValue("counter")).isNull()
    assertThat(root.createChildRegistry("kept").restoredValue("counter")?.value).isEqualTo(2)
  }

  @Test
  fun `a child discarding child state before it restores claims nothing from its parent`() {
    val claims = AtomicInteger()
    val saved = savedState(
        childKey("list") to
            savedState(
                childKey("dropped") to savedState("counter" to 1),
                childKey("kept") to savedState("counter" to 2),
            ),
    )
    val root =
        LithoSaveableStateRegistry.ownerBacked(null) {
          claims.incrementAndGet()
          saved
        }
    val list = root.createChildRegistry("list")

    list.discardChildState("dropped")

    assertThat(claims.get()).isZero()
    assertThat(list.createChildRegistry("dropped").restoredValue("counter")).isNull()
    assertThat(list.createChildRegistry("kept").restoredValue("counter")?.value).isEqualTo(2)
  }

  @Test
  fun `a registry holds state while it has a live value, a retained value or an open child`() {
    val parent = LithoSaveableStateRegistry()
    val withLiveValue = parent.createChildRegistry("live")
    LiveValue(withLiveValue, "counter", 1)
    val withRetainedValue = parent.createChildRegistry("retained")
    val retained = LiveValue(withRetainedValue, "counter", 2)
    withRetainedValue.retainActiveValues()
    retained.close()
    val withChild = parent.createChildRegistry("parent")
    val child = withChild.createChildRegistry("child")
    child.restoredValue("counter")

    assertThat(parent.createChildRegistry("empty").holdsState()).isFalse()
    assertThat(withLiveValue.holdsState()).isTrue()
    assertThat(withRetainedValue.holdsState()).isTrue()
    assertThat(withChild.holdsState()).isTrue()
    child.close()
    assertThat(withChild.holdsState()).isFalse()
    withLiveValue.close()
    assertThat(withLiveValue.holdsState()).isFalse()
  }

  @Test
  fun `closing a child that holds nothing leaves its restored values to the next child`() {
    val parent = LithoSaveableStateRegistry(savedChild("child", 5))
    val child = parent.createChildRegistry("child")
    assertThat(child.holdsState()).isFalse()

    child.close()

    assertThat(parent.createChildRegistry("child").restoredValue("counter")?.value).isEqualTo(5)
  }

  /** A saved root with a "header" and the rows "row-0" and "row-1" in its nested "list". */
  private fun savedList(): Bundle = savedState(
      "header" to "header",
      childKey("list") to
          savedState(
              childKey("row-0") to savedState("label" to "row 0"),
              childKey("row-1") to savedState("label" to "row 1"),
          ),
  )

  private fun savedLabel(saved: Bundle, row: String): Any? =
      LithoSaveableStateRegistry(saved)
          .createChildRegistry("list")
          .createChildRegistry(row)
          .restoredValue("label")
          ?.value

  /** The sources of the restore failures reported while [block] runs. */
  private fun restoreFailureSources(block: () -> Unit): List<Any?> {
    val sources = mutableListOf<Any?>()
    val subscriber =
        object : DebugEventSubscriber(LithoDebugEvent.DebugInfo) {
          override fun onEvent(event: DebugEvent) {
            if (event.attributes[DebugEventAttribute.Name] == RESTORE_FAILED_CATEGORY) {
              sources += event.attributes["source"]
            }
          }
        }
    DebugEventBus.subscribe(subscriber)
    try {
      block()
    } finally {
      DebugEventBus.unsubscribe(subscriber)
    }
    return sources
  }

  private fun duplicateKeyWarnings(): Int =
      ShadowLog.getLogs().count { it.msg.orEmpty().startsWith("LithoSaveableState:DuplicateKey") }

  private companion object {
    const val TIMEOUT_SECONDS: Long = 5
    const val RESTORE_FAILED_CATEGORY: String = "LithoSaveableState:RestoreFailed"

    /** A snapshot with one child registry, [key], whose `counter` is [value]. */
    private fun savedChild(key: String, value: Int): Bundle =
        savedState(childKey(key) to savedState("counter" to value))

    private fun restoredChild(snapshot: Bundle, key: String): LithoSaveableStateRegistry =
        LithoSaveableStateRegistry(snapshot).createChildRegistry(key)

    fun parcelRoundTrip(bundle: Bundle): Bundle {
      val bytes =
          Parcel.obtain().let { parcel ->
            try {
              parcel.writeBundle(bundle)
              parcel.marshall()
            } finally {
              parcel.recycle()
            }
          }
      return Parcel.obtain().let { parcel ->
        try {
          parcel.unmarshall(bytes, 0, bytes.size)
          parcel.setDataPosition(0)
          checkNotNull(parcel.readBundle(LithoSaveableStateRegistryTest::class.java.classLoader))
        } finally {
          parcel.recycle()
        }
      }
    }
  }

  @android.annotation.SuppressLint("MigrateToUsingParcelizePlugin")
  private class UnreadableParcelable() : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
      parcel.writeInt(1)
    }

    override fun describeContents(): Int = 0

    private constructor(parcel: Parcel) : this() {
      parcel.readInt()
      throw BadParcelableException("Test value cannot be deserialized")
    }

    companion object {
      @JvmField
      val CREATOR: Parcelable.Creator<UnreadableParcelable> =
          object : Parcelable.Creator<UnreadableParcelable> {
            override fun createFromParcel(parcel: Parcel): UnreadableParcelable =
                UnreadableParcelable(parcel)

            override fun newArray(size: Int): Array<UnreadableParcelable?> = arrayOfNulls(size)
          }
    }
  }
}
