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

package com.facebook.samples.litho.java.lithography;

import android.net.Uri;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.facebook.fresco.vito.litho.FrescoVitoImage2;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.annotations.FromEvent;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnCreateLayout;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.annotations.Prop;
import com.facebook.litho.sections.SectionContext;
import com.facebook.litho.sections.common.DataDiffSection;
import com.facebook.litho.sections.common.RenderEvent;
import com.facebook.litho.sections.widget.ListRecyclerConfiguration;
import com.facebook.litho.sections.widget.RecyclerBinderConfiguration;
import com.facebook.litho.sections.widget.RecyclerCollectionComponent;
import com.facebook.litho.sections.widget.RecyclerConfiguration;
import com.facebook.litho.widget.ComponentRenderInfo;
import com.facebook.litho.widget.RecyclerBinderConfig;
import com.facebook.litho.widget.RenderInfo;
import com.facebook.litho.widget.SnapUtil;
import com.facebook.litho.widget.collection.CrossAxisWrapMode;
import java.util.Arrays;
import javax.annotation.Nullable;

@LayoutSpec
public class FeedImageComponentSpec {

  private static final RecyclerConfiguration LIST_CONFIGURATION =
      ListRecyclerConfiguration.create()
          .orientation(LinearLayoutManager.HORIZONTAL)
          .snapMode(SnapUtil.SNAP_TO_START)
          .recyclerBinderConfiguration(
              RecyclerBinderConfiguration.create()
                  .recyclerBinderConfig(
                      RecyclerBinderConfig.create()
                          .crossAxisWrapMode(CrossAxisWrapMode.MatchFirstChild)
                          .build())
                  .build())
          .build();

  @OnCreateLayout
  static Component onCreateLayout(ComponentContext c, @Prop final String[] images) {
    return images.length == 1
        ? createImageComponent(c, images[0]).build()
        : RecyclerCollectionComponent.create(c)
            .disablePTR(true)
            .recyclerConfiguration(LIST_CONFIGURATION)
            .section(
                DataDiffSection.<String>create(new SectionContext(c))
                    .data(Arrays.asList(images))
                    .renderEventHandler(FeedImageComponent.onRender(c))
                    .build())
            .aspectRatio(2)
            .build();
  }

  @OnEvent(RenderEvent.class)
  static RenderInfo onRender(ComponentContext c, @FromEvent String model) {
    return ComponentRenderInfo.create().component(createImageComponent(c, model).build()).build();
  }

  @SuppressWarnings("rawtypes")
  private static Component.Builder createImageComponent(
      ComponentContext c, @Nullable String image) {
    return FrescoVitoImage2.create(c)
        .uri(image != null ? Uri.parse(image) : null)
        .imageAspectRatio(2f);
  }
}
