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

package com.facebook.samples.litho.java.sections

import com.facebook.litho.annotations.Prop
import com.facebook.litho.sections.Children
import com.facebook.litho.sections.SectionContext
import com.facebook.litho.sections.annotations.GroupSectionSpec
import com.facebook.litho.sections.annotations.OnCreateChildren
import com.facebook.litho.sections.annotations.OnViewportChanged
import com.facebook.litho.sections.common.SingleComponentSection
import com.facebook.samples.litho.kotlin.lithography.components.LoadingComponent
import com.facebook.samples.litho.kotlin.lithography.data.Decade
import com.facebook.samples.litho.kotlin.sections.DecadeSection

@GroupSectionSpec
object LithoFeedSectionSpec {

  @OnCreateChildren
  fun onCreateChildren(
      c: SectionContext,
      @Prop decades: List<Decade>,
      @Prop loading: Boolean,
  ): Children {
    val children = Children.create()
    val decadeSections = decades.map {
      DecadeSection.create(c).decade(it).key("${it.year}").build()
    }

    children.child(decadeSections)

    if (loading) {
      children.child(SingleComponentSection.create(c).component(LoadingComponent()))
    }

    return children.build()
  }

  @OnViewportChanged
  fun onViewportChanged(
      c: SectionContext,
      firstVisible: Int,
      lastVisible: Int,
      totalCount: Int,
      fistFullyVisible: Int,
      lastFullyVisible: Int,
      @Prop fetcher: (lastFetchedDecade: Int) -> Unit,
      @Prop decades: List<Decade>,
  ) {
    val threshold = 2
    if (totalCount - lastVisible < threshold) {
      fetcher(decades.last().year)
    }
  }
}
