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

package com.facebook.litho.navigation

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import com.facebook.litho.Component
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.meta.foa.datasource.DataSource
import com.meta.foa.navigation.core.FoaDestinationDataSourceFactory
import com.meta.foa.navigation.core.FoaRenderer
import com.meta.foa.navigation.core.foaDataSource
import com.meta.foa.session.FoaUserSession
import kotlin.reflect.KClass

/** Litho renderer value stored in a destination spec; create it with [lithoRenderer]. */
@ExperimentalLithoApi
class LithoDestinationRenderer<D : LithoDestination>
internal constructor(
    private val factory: LithoEntryScope<D>.() -> Component,
) : FoaRenderer<D> {

  @MainThread internal fun create(scope: LithoEntryScope<D>): Component = scope.factory()
}

/**
 * What a [lithoRenderer] factory reads for one back-stack entry. Read what the screen needs and
 * pass it to the screen's component as props: the scope is only valid while the factory runs, and
 * using it later fails.
 */
@ExperimentalLithoApi
class LithoEntryScope<out D : LithoDestination>
internal constructor(
    /** The entry's destination. */
    val destination: D,
    private val store: ViewModelStore,
    /** The session the host was created with, which it prepares the entry's data source with. */
    val session: FoaUserSession,
) {

  internal var active: Boolean = true

  /**
   * The entry's `ViewModelStore`. The same one comes back while the entry is on the back stack,
   * also when it is covered or its Activity is recreated, and it is cleared when the entry is
   * popped. It has no saved-state owner, so ViewModels built from a `SavedStateHandle` are not
   * supported.
   */
  val viewModelStore: ViewModelStore
    get() = store.also { checkActive() }

  /** Returns the data source the destination's spec prepared for this entry. */
  fun <T : DataSource> dataSource(factory: FoaDestinationDataSourceFactory<in D, T>): T =
      viewModelStore.foaDataSource(
          factory,
          requester = "the renderer for ${destination.javaClass.name}",
      )

  /**
   * Returns the entry's ViewModel of class [modelClass], which [create] makes the first time; it
   * stays with the entry until the entry is popped. Pass a [key] to keep more than one of the same
   * class; keys are shared by all of the entry's ViewModels.
   */
  fun <VM : ViewModel> viewModel(
      modelClass: KClass<VM>,
      key: String? = null,
      create: () -> VM,
  ): VM {
    val provider =
        ViewModelProvider.create(
            viewModelStore,
            viewModelFactory { addInitializer(modelClass) { create() } },
        )
    return if (key == null) provider[modelClass] else provider[key, modelClass]
  }

  private fun checkActive() {
    check(active) {
      "LithoEntryScope used after its lithoRenderer factory returned; read values in the factory " +
          "and pass them to the screen as props."
    }
  }
}

/**
 * Creates a renderer whose [factory] builds the root component of a destination's screen.
 *
 * [LithoNavHost] calls [factory] on the main thread whenever the entry becomes the top of the back
 * stack, including inside the push or pop that makes it the top, so keep it cheap. The factory
 * reads from its [LithoEntryScope], for example the destination, the entry's ViewModels or the
 * prefetched data source, and passes the values to the component as props. It must not change the
 * back stack; to redirect, navigate from an effect or an event handler instead. The host fails if
 * it does.
 *
 * ```
 * lithoRenderer {
 *   ProfileScreen(
 *       id = destination.id,
 *       source = dataSource(ProfileDataSourceFactory),
 *       viewModel = viewModel(ProfileViewModel::class) { ProfileViewModel(destination.id) },
 *   )
 * }
 * ```
 */
@ExperimentalLithoApi
fun <D : LithoDestination> lithoRenderer(
    factory: LithoEntryScope<D>.() -> Component,
): LithoDestinationRenderer<D> = LithoDestinationRenderer(factory)
