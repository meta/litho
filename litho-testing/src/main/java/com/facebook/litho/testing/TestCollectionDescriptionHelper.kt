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

package com.facebook.litho.testing

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.facebook.litho.Component
import com.facebook.litho.ComponentHost
import com.facebook.litho.DebugComponent
import com.facebook.litho.LithoViewTestHelper
import com.facebook.litho.testing.viewtree.ViewExtractors
import com.facebook.litho.testing.viewtree.ViewTree

object TestCollectionDescriptionHelper {

  private const val DEPTH_3_LEADING_SPACE = "      "
  private const val ITEM_STARTING_DEPTH = 3

  fun collectionToString(testCollection: TestCollection): String {
    var hierarchyString = testCollection.recyclerView.toString()
    testCollection.items.forEach { item ->
      hierarchyString = "${hierarchyString}${itemToString(item)}"
    }
    return hierarchyString
  }

  private fun itemToString(item: TestCollectionItem): String {
    val testCollectionItemHeader =
        "\n└── index ${item.index}: Collection Item (id: ${item.id}, visibility: ${getVisibility(item)})"

    val itemDescription =
        if (item.renderInfo?.rendersView() == true) getViewInfo(item, item.recyclerView)
        else getComponentInfo(item)
    return "$testCollectionItemHeader${itemDescription}"
  }

  private fun getComponentInfo(item: TestCollectionItem): String {
    val debugComponent =
        DebugComponent.getRootInstance(item.componentTree)
            ?: return item.component?.let { fallbackComponentPrintInfo(it) } ?: ""

    return LithoViewTestHelper.rootInstanceToString(debugComponent, false, ITEM_STARTING_DEPTH)
  }

  private fun fallbackComponentPrintInfo(component: Component): String {
    val componentString = component.toString()
    return "\n$DEPTH_3_LEADING_SPACE$componentString{${component.hashCode()}}\n"
  }

  private fun getViewInfo(item: TestCollectionItem, recyclerView: RecyclerView): String {
    val itemView =
        recyclerView.findViewHolderForAdapterPosition(item.index)?.itemView
            ?: return "\n${DEPTH_3_LEADING_SPACE}Found null item view (no additional information available)\n"
    val viewTreeString =
        ViewTree.of(itemView).makeString(ITEM_STARTING_DEPTH, ::viewExtraTextExtractor)

    return "$viewTreeString\n"
  }

  private fun viewExtraTextExtractor(view: View?): String? {
    if (view is TextView || view is ComponentHost) {
      val textOutput =
          ViewExtractors.GET_TEXT_FUNCTION.apply(view).takeUnless {
            it.startsWith("No text found")
          }
      if (textOutput != null) {
        return textOutput
      }
    }

    if (view is ImageView || view is ComponentHost) {
      val textOutput =
          ViewExtractors.GET_DRAWABLE_FUNCTION.apply(view).takeUnless {
            it.startsWith("No drawable found")
          }
      if (textOutput != null) {
        return textOutput
      }
    }

    return null
  }

  private fun getVisibility(item: TestCollectionItem): String =
      when {
        item.isFullyVisible && item.isVisible -> "full" // checks both to handle mocks in odd states
        item.isVisible -> "partial"
        else -> "none"
      }
}
