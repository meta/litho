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

// NameComponent below is an intentional docs example of a non-comparable prop.
@file:Suppress("KComponentNonComparableInputDetector")

package com.facebook.samples.litho.kotlin.collection

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.facebook.kotlin.compilerplugins.dataclassgenerate.annotation.DataClassGenerate
import com.facebook.litho.ClickEvent
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.drawableColor
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.useCallback
import com.facebook.litho.useState
import com.facebook.litho.view.background
import com.facebook.litho.view.onClick
import com.facebook.litho.widget.collection.LazyList

class ListWithIds(private val friends: List<Person>) : KComponent() {
  override fun ComponentScope.render(): Component {

    val shouldShowGreeting = useState { false }

    return LazyList {
      // start_static_id_example
      child(Header()) // generated id is "Header:0"
      // end_static_id_example

      // start_child_id_example
      if (shouldShowGreeting.value) {
        child(id = "greeting", component = Text("Greetings!"))
      }
      child(id = "title", component = Text("Title"))
      // end_child_id_example

      // start_children_id_example
      children(items = friends, id = { it.id }) { Text(it.name) }
      // end_children_id_example
    }
  }
}

class Header : KComponent() {
  override fun ComponentScope.render(): Component = Text("Header")
}

// start_name_list_unnecessary_update
class Name(val firstName: String, val secondName: String)

class NameComponent(val name: Name) : KComponent() {
  override fun ComponentScope.render(): Component = Text("${name.firstName} ${name.secondName}")
}

class NameList_UnnecessaryUpdate : KComponent() {
  override fun ComponentScope.render(): Component = LazyList {
    child(NameComponent(Name("Mark", "Zuckerberg")))
  }
}

// end_name_list_unnecessary_update

@DataClassGenerate data class NameWithEquals(val firstName: String, val secondName: String)

class NameComponentWithEquals(val name: NameWithEquals) : KComponent() {
  override fun ComponentScope.render(): Component = Text("${name.firstName} ${name.secondName}")
}

// start_name_list_fixed
class NameList_Fixed : KComponent() {
  override fun ComponentScope.render(): Component = LazyList {
    // Option 1. Convert to a prop with an `equals()` implementation
    child(NameComponentWithEquals(NameWithEquals("Mark", "Zuckerberg")))

    // Option 2. Manually specify dependencies (in this case empty)
    child(deps = arrayOf()) { NameComponent(Name("Mark", "Zuckerberg")) }
  }
}

// end_name_list_fixed

@Suppress("KComponentUncachedAllocationDetector") // intentional docs bad example
// start_drawable_unnecessary_update
class Drawable_UnnecessaryUpdate : KComponent() {
  override fun ComponentScope.render(): Component = LazyList {
    child(Text("text", style = Style.background(ColorDrawable(Color.RED))))
  }
}

// end_drawable_unnecessary_update

// start_drawable_fixed
class Drawable_Fixed : KComponent() {
  override fun ComponentScope.render(): Component = LazyList {
    // Option 1. Use a `ComparableDrawable` wrapper
    child(Text("text", style = Style.background(drawableColor(Color.RED))))

    // Option 2. Manually specify dependencies (in this case empty).
    child(deps = arrayOf()) { Text("text", style = Style.background(ColorDrawable(Color.RED))) }
  }
}

// end_drawable_fixed

@Suppress("CollectionChildLambdaWillTriggerUnnecessaryRerender") // intentional docs bad example
// start_lambda_unnecessary_update
class Lambda_UnnecessaryUpdate(val name: String) : KComponent() {
  override fun ComponentScope.render(): Component = LazyList {
    child(Text("text", style = Style.onClick { println("Hello $name") }))
  }
}

// end_lambda_unnecessary_update

// start_lambda_fixed
class Lambda_Fixed(val name: String) : KComponent() {
  override fun ComponentScope.render(): Component {
    val callBack = useCallback { _: ClickEvent -> println("Hello $name") }
    return LazyList { child(Text("text", style = Style.onClick(action = callBack))) }
  }
}

// end_lambda_fixed

// start_shopping_list_example
class ShoppingList : KComponent() {
  override fun ComponentScope.render(): Component {
    val shoppingList = listOf("Apples", "Cheese", "Bread")

    // Create a state containing the items that should be shown with a checkmark: ☑
    // Initially empty
    val checkedItems = useState { setOf<String>() }

    // Create a callback to toggle the checkmark for an item
    // States should always use immutable data, so a new Set is created
    val toggleChecked = useCallback { item: String ->
      checkedItems.update {
        it.toMutableSet().apply { if (contains(item)) remove(item) else add(item) }.toSet()
      }
    }

    return LazyList {
      children(items = shoppingList, id = { shoppingItem -> shoppingItem }) { currentItem ->
        val isChecked = checkedItems.value.contains(currentItem)
        ShoppingListItem(currentItem, isChecked, toggleChecked)
      }
    }
  }
}

class ShoppingListItem(
    private val item: String,
    private val isChecked: Boolean,
    private val toggleSelected: (String) -> Unit,
) : KComponent() {
  override fun ComponentScope.render(): Component =
      Text("${if (isChecked) "☑" else "☐"} $item", style = Style.onClick { toggleSelected(item) })
}
// end_shopping_list_example
