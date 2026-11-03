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

package com.facebook.samples.litho.java.bordereffects;

import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.annotations.FromEvent;
import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.annotations.OnCreateLayout;
import com.facebook.litho.annotations.OnEvent;
import com.facebook.litho.sections.SectionContext;
import com.facebook.litho.sections.common.DataDiffSection;
import com.facebook.litho.sections.common.RenderEvent;
import com.facebook.litho.sections.widget.RecyclerCollectionComponent;
import com.facebook.litho.widget.ComponentRenderInfo;
import com.facebook.litho.widget.RenderInfo;
import com.facebook.litho.widget.Text;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

@LayoutSpec
class BorderEffectsComponentSpec {

  private static final List<Class<? extends Component>> componentsToBuild =
      Arrays.<Class<? extends Component>>asList(
          AlternateColorBorder.class,
          AlternateWidthBorder.class,
          AlternateColorWidthBorder.class,
          RtlColorWidthBorder.class,
          DashPathEffectBorder.class,
          VerticalDashPathEffectBorder.class,
          AlternateColorPathEffectBorder.class,
          AlternateColorCornerPathEffectBorder.class,
          CompositePathEffectBorder.class,
          VaryingRadiiBorder.class);

  @OnCreateLayout
  static Component onCreateLayout(ComponentContext c) {
    return RecyclerCollectionComponent.create(c)
        .disablePTR(true)
        .section(
            DataDiffSection.<Class<? extends Component>>create(new SectionContext(c))
                .data(componentsToBuild)
                .renderEventHandler(BorderEffectsComponent.onRender(c))
                .build())
        .build();
  }

  @OnEvent(RenderEvent.class)
  @SuppressWarnings("rawtypes")
  static RenderInfo onRender(ComponentContext c, @FromEvent Class<? extends Component> model) {

    try {
      Method createMethod = model.getMethod("create", ComponentContext.class);
      Component.Builder componentBuilder = (Component.Builder) createMethod.invoke(null, c);
      return ComponentRenderInfo.create().component(componentBuilder.build()).build();
    } catch (NoSuchMethodException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException ex) {
      return ComponentRenderInfo.create()
          .component(Text.create(c).textSizeDip(32).text(ex.getLocalizedMessage()).build())
          .build();
    }
  }
}
