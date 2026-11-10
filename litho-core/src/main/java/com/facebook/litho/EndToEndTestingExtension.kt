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
import androidx.annotation.VisibleForTesting
import com.facebook.litho.EndToEndTestingExtension.EndToEndTestingExtensionInput
import com.facebook.rendercore.MountDelegateInput
import com.facebook.rendercore.MountDelegateTarget
import com.facebook.rendercore.RenderTreeNode
import com.facebook.rendercore.extensions.ExtensionState
import com.facebook.rendercore.extensions.MountExtension
import java.util.Deque
import java.util.HashMap
import java.util.LinkedList

class EndToEndTestingExtension(private val mountDelegateTarget: MountDelegateTarget) :
    MountExtension<EndToEndTestingExtensionInput, Void?>() {

  // A map from test key to a list of one or more `TestItem`s which is only allocated
  // and populated during test runs.
  private val testItemMap: MutableMap<String, Deque<TestItem>> = HashMap()
  private var input: EndToEndTestingExtensionInput? = null

  interface EndToEndTestingExtensionInput : MountDelegateInput {
    val testOutputCount: Int

    fun getTestOutputAt(position: Int): TestOutput?

    override fun getPositionForId(id: Long): Int

    override fun getMountableOutputAt(position: Int): RenderTreeNode

    override fun getMountableOutputCount(): Int
  }

  override fun createState(): Void? = null

  override fun beforeMount(
      extensionState: ExtensionState<Void?>,
      input: EndToEndTestingExtensionInput?,
      localVisibleRect: Rect?,
  ) {
    this.input = input
  }

  override fun afterMount(extensionState: ExtensionState<Void?>) {
    processTestOutputs()
  }

  override fun onUnmount(extensionState: ExtensionState<Void?>) = Unit

  override fun onUnbind(extensionState: ExtensionState<Void?>) = Unit

  private fun processTestOutputs() {
    testItemMap.clear()
    val input = this.input ?: return
    for (i in 0 until input.testOutputCount) {
      val testOutput = input.getTestOutputAt(i) ?: continue
      val layoutOutputId = testOutput.layoutOutputId
      val testItem = TestItem()
      testItem.host = getHost(testOutput)
      testItem.bounds = testOutput.bounds
      testItem.testKey = testOutput.testKey
      testItem.content = mountDelegateTarget.getContentById(layoutOutputId)
      val items = testItemMap[testOutput.testKey]
      val updatedItems = items ?: LinkedList()
      updatedItems.add(testItem)
      testItemMap[testOutput.testKey] = updatedItems
    }
  }

  private fun getHost(testOutput: TestOutput): ComponentHost? {
    val input = this.input ?: return null
    for (i in 0 until input.getMountableOutputCount()) {
      val renderTreeNode = input.getMountableOutputAt(i)
      if (renderTreeNode.renderUnit.id == testOutput.layoutOutputId) {
        val hostTreeNode = renderTreeNode.parent ?: return null
        return mountDelegateTarget.getContentById(hostTreeNode.renderUnit.id) as ComponentHost?
      }
    }
    return null
  }

  /** @see LithoViewTestHelper.findTestItems */
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  @Suppress("SENSELESS_COMPARISON")
  fun findTestItems(testKey: String?): Deque<TestItem> {
    if (testItemMap == null) {
      throw UnsupportedOperationException(
          "Trying to access TestItems while " +
              "ComponentsConfiguration.isEndToEndTestRun is false.",
      )
    }
    val items = testItemMap[testKey]
    return items ?: LinkedList()
  }
}
