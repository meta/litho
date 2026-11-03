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

package com.facebook.samples.litho.kotlin.collection

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useState
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.onClick
import com.facebook.litho.widget.VerticalGravity
import com.facebook.litho.widget.collection.LazyList
import com.facebook.litho.widget.collection.LazyListScope
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp

/**
 * Exercises the default item animations of Lazy Collections: insert, remove and move with explicit
 * ids, nested horizontal lists, and an id-less mode that stays unanimated. The flag and the backend
 * are set on [ComponentsConfiguration.defaultInstance], as apps do, and apply after the activity is
 * recreated.
 */
class LazyListItemAnimationsComponent : KComponent() {

  // ComponentsConfiguration has a non-public ctor; copy() is the only way to derive variants.
  @Suppress("DATA_CLASS_INVISIBLE_COPY_USAGE_WARNING")
  override fun ComponentScope.render(): Component {
    val list = useState { DemoList((0 until INITIAL_ITEMS).toList(), INITIAL_ITEMS) }
    val useIds = useState { true }
    val edits = useState { emptyMap<Int, Int>() }
    val config = ComponentsConfiguration.defaultInstance

    fun mutate(change: (List<Int>, Int) -> List<Int>) {
      list.update { DemoList(change(it.items, it.nextId), it.nextId + 1) }
    }

    return Column(style = Style.flex(grow = 1f)) {
      child(
          Text(
              text =
                  "animations: ${if (config.useDefaultItemAnimatorInLazyCollections) "ON" else "OFF"}" +
                      "  |  collection: ${if (config.enableNewCollection) "NEW" else "LEGACY"}" +
                      "  |  ${if (useIds.value) "explicit" else "generated"} ids",
              textSize = 14.sp,
              style = Style.flex(shrink = 0f).padding(horizontal = 8.dp, vertical = 12.dp),
          ),
      )
      child(
          Row(style = Style.flex(shrink = 0f)) {
            child(
                button("Flag") {
                  restart(
                      config.copy(
                          useDefaultItemAnimatorInLazyCollections =
                              !config.useDefaultItemAnimatorInLazyCollections,
                      ),
                  )
                },
            )
            child(
                button("Backend") {
                  restart(config.copy(enableNewCollection = !config.enableNewCollection))
                },
            )
            child(button("Id mode") { useIds.update { !it } })
          },
      )
      child(
          Row(style = Style.flex(shrink = 0f)) {
            // RecyclerView keeps the first visible row anchored, so changes at index 0 would happen
            // off screen. These work just below it.
            child(button("Insert") { mutate { list, id -> list.take(1) + id + list.drop(1) } })
            child(button("Remove") { mutate { list, _ -> list.take(1) + list.drop(2) } })
            child(button("Move") { mutate { list, _ -> list.move(1, minOf(4, list.lastIndex)) } })
            child(
                button("Update") {
                  list.value.items.getOrNull(1)?.let { id ->
                    edits.update { it + (id to (it[id] ?: 0) + 1) }
                  }
                },
            )
            child(button("Append") { mutate { list, id -> list + id } })
          },
      )
      child(
          LazyList(style = Style.flex(grow = 1f)) {
            if (useIds.value) {
              children(items = list.value.items, id = ::childId) { row(it, edits.value[it] ?: 0) }
            } else {
              childrenWithoutIds(list.value.items) { row(it, edits.value[it] ?: 0) }
            }
          },
      )
    }
  }
}

// Without ids on purpose: this mode shows that lists with generated ids stay unanimated.
@SuppressLint("CollectionChildWithoutIdInLoopDetector")
private inline fun LazyListScope.childrenWithoutIds(
    items: List<Int>,
    component: (Int) -> Component,
) {
  for (item in items) {
    child(component(item))
  }
}

private fun isNested(item: Int): Boolean = item % NESTED_EVERY == NESTED_EVERY - 1

// Prefixed by row type, so a card and a nested list never share an id.
private fun childId(item: Int): String = if (isNested(item)) "nested:$item" else "card:$item"

private fun ResourcesScope.row(item: Int, edits: Int): Component =
    if (isNested(item)) nestedRow(item) else card(item, edits)

private fun ResourcesScope.card(item: Int, edits: Int): Component = Column(
    style =
        Style.height(72.dp)
            .margin(horizontal = 8.dp, vertical = 4.dp)
            .padding(all = 12.dp)
            .backgroundColor(COLORS[(item + edits) % COLORS.size]),
) {
  child(
      Text(
          text = if (edits == 0) "Item $item" else "Item $item (edited $edits)",
          textSize = 20.sp,
          textColor = Color.WHITE,
      ),
  )
}

private fun ResourcesScope.nestedRow(item: Int): Component =
    LazyList(orientation = RecyclerView.HORIZONTAL, style = Style.height(88.dp)) {
      children(items = NESTED_ITEMS, id = { it }) { i ->
        Column(
            style =
                Style.width(96.dp)
                    .height(72.dp)
                    .margin(all = 8.dp)
                    .padding(all = 8.dp)
                    .backgroundColor(COLORS[(item + i) % COLORS.size]),
        ) {
          child(Text(text = "$item.$i", textSize = 16.sp, textColor = Color.WHITE))
        }
      }
    }

private fun ResourcesScope.button(label: String, onClick: () -> Unit): Component = Text(
    text = label,
    textSize = 16.sp,
    textColor = Color.WHITE,
    verticalGravity = VerticalGravity.CENTER,
    style =
        Style.margin(all = 3.dp)
            .height(44.dp)
            .padding(horizontal = 8.dp)
            .backgroundColor(Color.DKGRAY)
            .onClick { onClick() },
)

private fun ResourcesScope.restart(newConfig: ComponentsConfiguration) {
  ComponentsConfiguration.defaultInstance = newConfig
  (context.androidContext as? Activity)?.recreate()
}

private fun List<Int>.move(from: Int, to: Int): List<Int> {
  if (size <= from) {
    return this
  }
  val result = toMutableList()
  result.add(to, result.removeAt(from))
  return result
}

private class DemoList(val items: List<Int>, val nextId: Int)

private const val INITIAL_ITEMS = 20
private const val NESTED_EVERY = 5
private val NESTED_ITEMS = (0 until 8).toList()
private val COLORS = intArrayOf(
    0xFF1E88E5.toInt(),
    0xFF43A047.toInt(),
    0xFFE53935.toInt(),
    0xFF8E24AA.toInt(),
    0xFFFB8C00.toInt(),
)
