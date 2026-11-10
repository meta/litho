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

import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.onClick
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/** Unit tests for [useCached]. */
@Suppress("MagicNumber")
@RunWith(LithoTestRunner::class)
@LooperMode(LooperMode.Mode.LEGACY)
class KCachedTest {

  @JvmField @Rule val lithoViewRule = LithoTestRule()

  @Test
  fun `many cached hooks reuse warm values and update only changed dependencies`() {
    val calculations = IntArray(64)
    val dependency = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val count = useState { 0 }
        var total = 0
        for (index in calculations.indices) {
          val input = if (index == 0) dependency.get() else 0
          total +=
              useCached(index, input) {
                calculations[index]++
                index + input
              }
        }
        return Text("$total:${count.value}", style = Style.onClick { count.update { it + 1 } })
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(calculations).containsOnly(1)
    handle.findViewWithText("2016:0").performClick()
    lithoViewRule.idle()
    assertThat(handle.findViewWithText("2016:1")).isNotNull
    assertThat(calculations).containsOnly(1)

    dependency.incrementAndGet()
    handle.findViewWithText("2016:1").performClick()
    lithoViewRule.idle()
    assertThat(handle.findViewWithText("2017:2")).isNotNull
    assertThat(calculations[0]).isEqualTo(2)
    assertThat(calculations.drop(1)).containsOnly(1)
  }

  @Test
  fun `when component is removed then cached value should be cleared`() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    lithoViewRule.render(lithoView = handle.lithoView) { EmptyComponent() }
    lithoViewRule.render(lithoView = handle.lithoView) { TestComponent() }

    val config = lithoViewRule.context.componentsConfig
    if (config.useStateForCachedValues) {
      assertThat(initCounter.get()).isEqualTo(2)
    } else {
      assertThat(initCounter.get()).isEqualTo(1)
    }
  }

  @Test
  fun cachedValueIsCalculatedOnlyOnceWhenOneInputStayTheSame() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun cachedValueIsCalculatedOnlyOnceWhenTwoInputsStayTheSame() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello", 100) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 100)
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun cachedValueIsCalculatedOnlyOnceWhenInputArrayStaysTheSame() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello", 100, "litho") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 100, "litho")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun cachedValueIsRecalculatedWhenOneInputChange() {
    val repeatNum = AtomicInteger(100)
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached(repeatNum.get()) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", repeatNum.get())
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(handle.findViewWithText("count: 0")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.incrementAndGet()
    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun cachedValueIsRecalculatedWhenTwoInputsChange() {
    val repeatNum = AtomicInteger(100)
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello", repeatNum.get()) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", repeatNum.get())
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(handle.findViewWithText("count: 0")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.incrementAndGet()
    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun cachedValueIsRecalculatedWhenInputArrayChange() {
    val repeatNum = AtomicInteger(100)
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello", repeatNum.get(), "world") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", repeatNum.get(), "world")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(handle.findViewWithText("count: 0")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.incrementAndGet()
    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun `cached value with single null value that never changes only calculates once`() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached(null) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun `cached value with null input is only calculated once when 2 inputs unchanged`() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello", null) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 100)
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun `cached value with null inputs is only calculated once when array of inputs unchanged`() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached(null, "hello", null) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 100, "world")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun `cached value is recalculated when one null input changes`() {
    val repeatNum = AtomicReference<Integer?>(null)
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached(repeatNum.get()) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", repeatNum.get()?.toInt() ?: 2, "world")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(handle.findViewWithText("count: 0")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.set(Integer(5))
    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun `cached value is recalculated when two inputs with nullable value change`() {
    val repeatNum = AtomicReference<Integer?>(null)
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello", repeatNum.get()) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", repeatNum.get()?.toInt() ?: 2, "world")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(handle.findViewWithText("count: 0")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.set(Integer(5))
    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun `cached value is recalculated when input array with nullable input changes`() {
    val repeatNum = AtomicReference<Integer?>(null)
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val expensiveString =
            useCached("hello", repeatNum.get(), "litho") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", repeatNum.get()?.toInt() ?: 2, "world")
            }
        val count = useState { 0 }
        return Column {
          child(Text(text = expensiveString))
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(handle.findViewWithText("count: 0")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.set(Integer(5))
    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun cachedValueIsNotReusedBetweenComponentsOfSameTypeWhenInputsStayTheSame() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component? {
        return Row {
          child(Leaf1("hello", 100, initCounter))
          child(Column { child(Leaf1("hello", 100, initCounter)) })
        }
      }
    }

    lithoViewRule.render { TestComponent() }

    assertThat(initCounter.get())
        .describedAs(
            "CacheValue should not be shared between two `Leaf1` components under the same parent.",
        )
        .isEqualTo(2)
  }

  @Test
  fun cachedValuesWithTheSameInputsAndNameAreNotReusedBetweenComponentsOfDifferentTypes() {
    val initCounter = AtomicInteger(0)
    class TestComponent(val showLeaf1: Boolean) : KComponent() {
      override fun ComponentScope.render(): Component? {
        return Row {
          if (showLeaf1) {
            child(Leaf1("hello", 100, initCounter))
          } else {
            child(Leaf2("hello", 100, initCounter))
          }
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent(showLeaf1 = true) }

    assertThat(initCounter.get()).isEqualTo(1)

    lithoViewRule.render(lithoView = handle.lithoView) { TestComponent(showLeaf1 = true) }

    assertThat(initCounter.get()).isEqualTo(1)

    lithoViewRule.render(lithoView = handle.lithoView) { TestComponent(showLeaf1 = false) }

    assertThat(initCounter.get())
        .describedAs("CacheValue should not be shared between two components of different types")
        .isEqualTo(2)
  }

  @Test
  fun cachedValuesWithDifferentNamesAreCalculatedAndReusedIndependentlyEvenWhenHaveSameInputs() {
    val initCounter = AtomicInteger(0)
    val root = ComponentWithTwoCachedValuesWithSameInputs("hello", 20, initCounter)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val count = useState { 0 }
        return Column {
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
          child(root)
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(2)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun cachedValueIsCalculatedOnlyOnceWhenOneInputStayTheSameForPrimitive() {
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached("hello") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun cachedValueIsCalculatedOnlyOnceWhenTwoInputsStayTheSameForPrimitive() {
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached("hello", 100) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5)
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun cachedValueIsCalculatedOnlyOnceWhenInputArrayStaysTheSameForPrimitive() {
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached("hello", 100, "litho") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5, "litho")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun cachedValueIsRecalculatedWhenOneInputChangeForPrimitive() {
    val repeatNum = AtomicInteger(10)
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached(repeatNum.get()) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.incrementAndGet()
    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun cachedValueIsRecalculatedWhenTwoInputsChangeForPrimitive() {
    val repeatNum = AtomicInteger(10)
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached("hello", repeatNum.get()) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5)
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.incrementAndGet()
    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun cachedValueIsRecalculatedWhenInputArrayChangeForPrimitive() {
    val repeatNum = AtomicInteger(10)
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached("hello", repeatNum.get(), "litho") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5, "litho")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.incrementAndGet()
    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun `cached value with single null value that never changes only calculates once for primitive`() {
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached(null) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5, "litho")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun `cached value with null input is only calculated once when 2 inputs unchanged for primitive`() {
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached("hello", null) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5)
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun `cached value with null inputs is only calculated once when array of inputs unchanged for Primitive`() {
    val initCounter = AtomicInteger(0)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached(null, 100, "litho") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5, "litho")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(1)
  }

  @Test
  fun `cached value is recalculated when one null input changes for primitive`() {
    val initCounter = AtomicInteger(0)
    val repeatNum = AtomicReference<Integer?>(null)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached(repeatNum.get()) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5, "litho")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.set(Integer(5))
    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun `cached value is recalculated when two inputs with nullable value change for primitive`() {
    val initCounter = AtomicInteger(0)
    val repeatNum = AtomicReference<Integer?>(null)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached(repeatNum.get(), 100) {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5, "litho")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.set(Integer(5))
    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun `cached value is recalculated when input array with nullable input changes for primitive`() {
    val initCounter = AtomicInteger(0)
    val repeatNum = AtomicReference<Integer?>(null)
    class TestPrimitiveComponent : PrimitiveComponent() {
      override fun PrimitiveComponentScope.render(): LithoPrimitive {
        val string =
            useCached("hello", repeatNum.get(), "litho") {
              initCounter.incrementAndGet()
              expensiveRepeatFunc("hello", 5, "litho")
              "count: "
            }
        val count = useState { 0 }
        return LithoPrimitive(
            primitive = TestTextPrimitive(text = string + count.value, tag = "tag"),
            style = Style.onClick { count.update { it + 1 } },
        )
      }
    }

    val handle = lithoViewRule.render { TestPrimitiveComponent() }
    assertThat(initCounter.get()).isEqualTo(1)

    repeatNum.set(Integer(5))
    handle.findViewWithTag("tag").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  @Test
  fun cachedValueIsNotReusedBetweenComponentsOfSameTypeWhenInputsStayTheSameForPrimitive() {
    val initCounter = AtomicInteger(0)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val count = useState { 0 }
        return Row {
          child(PrimitiveLeaf1("hello", 100, initCounter))
          child(Column { child(PrimitiveLeaf1("hello", 100, initCounter)) })
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(2)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(initCounter.get())
        .describedAs(
            "CacheValue should not be shared between two `Leaf1` components under the same parent.",
        )
        .isEqualTo(2)
  }

  @Test
  fun cachedValuesWithTheSameInputsAndNameAreNotReusedBetweenComponentsOfDifferentTypesForPrimitive() {
    val initCounter = AtomicInteger(0)
    class TestComponent(val showLeaf1: Boolean) : KComponent() {
      override fun ComponentScope.render(): Component? {
        return Row {
          if (showLeaf1) {
            child(PrimitiveLeaf1("hello", 100, initCounter))
          } else {
            child(PrimitiveLeaf2("hello", 100, initCounter))
          }
        }
      }
    }

    val handle = lithoViewRule.render { TestComponent(showLeaf1 = true) }

    assertThat(initCounter.get()).isEqualTo(1)

    lithoViewRule.render(lithoView = handle.lithoView) { TestComponent(showLeaf1 = true) }

    assertThat(initCounter.get()).isEqualTo(1)

    lithoViewRule.render(lithoView = handle.lithoView) { TestComponent(showLeaf1 = false) }

    assertThat(initCounter.get())
        .describedAs("CacheValue should not be shared between two components of different types")
        .isEqualTo(2)
  }

  @Test
  fun cachedValuesWithDifferentNamesAreCalculatedAndReusedIndependentlyEvenWhenHaveSameInputsForPrimitive() {
    val initCounter = AtomicInteger(0)
    val root = PrimitiveComponentWithTwoCachedValuesWithSameInputs("hello", 20, initCounter)
    class TestComponent : KComponent() {
      override fun ComponentScope.render(): Component {
        val count = useState { 0 }
        return Column {
          child(Text("increment", style = Style.onClick { count.update { it + 1 } }))
          child(Text("count: ${count.value}"))
          child(root)
        }
      }
    }
    val handle = lithoViewRule.render { TestComponent() }
    assertThat(initCounter.get()).isEqualTo(2)

    handle.findViewWithText("increment").performClick()

    lithoViewRule.idle()

    assertThat(handle.findViewWithText("count: 1")).isNotNull
    assertThat(initCounter.get()).isEqualTo(2)
  }

  private class Leaf1(val str: String, val repeatNum: Int, val initCounter: AtomicInteger) :
      KComponent() {
    override fun ComponentScope.render(): Component? {
      val expensiveString =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }
      return Text(text = expensiveString)
    }
  }

  private class Leaf2(val str: String, val repeatNum: Int, val initCounter: AtomicInteger) :
      KComponent() {
    override fun ComponentScope.render(): Component? {
      val expensiveString =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }
      return Text(text = expensiveString)
    }
  }

  private class ComponentWithTwoCachedValuesWithSameInputs(
      val str: String,
      val repeatNum: Int,
      val initCounter: AtomicInteger,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val expensiveString1 =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }

      val expensiveString2 =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }

      return Row() {
        child(Text(text = expensiveString1))
        child(Text(text = expensiveString2))
      }
    }
  }

  private class PrimitiveLeaf1(
      val str: String,
      val repeatNum: Int,
      val initCounter: AtomicInteger,
  ) : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive {
      val expensiveString =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }
      return LithoPrimitive(TestTextPrimitive(text = expensiveString), null)
    }
  }

  private class PrimitiveLeaf2(
      val str: String,
      val repeatNum: Int,
      val initCounter: AtomicInteger,
  ) : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive {
      val expensiveString =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }
      return LithoPrimitive(TestTextPrimitive(text = expensiveString), null)
    }
  }

  private class PrimitiveComponentWithTwoCachedValuesWithSameInputs(
      val str: String,
      val repeatNum: Int,
      val initCounter: AtomicInteger,
  ) : PrimitiveComponent() {
    override fun PrimitiveComponentScope.render(): LithoPrimitive {
      val expensiveString1 =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }

      val expensiveString2 =
          useCached(str, repeatNum) {
            initCounter.incrementAndGet()
            expensiveRepeatFunc(str, repeatNum)
          }

      return LithoPrimitive(
          TestTextPrimitive(text = expensiveString1, tag = expensiveString2),
          null,
      )
    }
  }

  companion object {
    private fun expensiveRepeatFunc(prefix: String, num: Int = 20, suffix: String? = null): String {
      return StringBuilder()
          .apply {
            repeat(num) {
              append(prefix)
              suffix?.let { append(it) }
            }
          }
          .toString()
    }
  }
}
