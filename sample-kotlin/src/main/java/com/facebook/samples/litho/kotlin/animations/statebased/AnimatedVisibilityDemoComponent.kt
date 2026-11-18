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

package com.facebook.samples.litho.kotlin.animations.statebased

import android.graphics.Color
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.animation.AnimatedVisibility
import com.facebook.litho.animation.TransformOrigin
import com.facebook.litho.animation.expandVertically
import com.facebook.litho.animation.fadeIn
import com.facebook.litho.animation.fadeOut
import com.facebook.litho.animation.scaleIn
import com.facebook.litho.animation.scaleOut
import com.facebook.litho.animation.shrinkVertically
import com.facebook.litho.animation.slideInVertically
import com.facebook.litho.animation.slideOutVertically
import com.facebook.litho.animation.tween
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useState
import com.facebook.litho.view.background
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms
import com.facebook.rendercore.sp
import com.facebook.samples.litho.kotlin.drawable.RoundedRect
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

@OptIn(ExperimentalLithoApi::class)
class AnimatedVisibilityDemoComponent : KComponent() {

  override fun ComponentScope.render(): Component {
    val visible = useState { true }

    return DemoSurface {
      child(SectionTitle("AnimatedVisibility"))
      child(
          DemoButton(
              if (visible.value) "Hide panel" else "Show panel",
              style = Style.margin(bottom = 12.dp).onClick { visible.update { !it } },
          ),
      )
      child(StatusRow("Queue", if (visible.value) "Mounted" else "Exiting"))
      child(
          // visibility_example_start
          AnimatedVisibility(
              visible = visible.value,
              enter =
                  fadeIn(tween(180.ms)) +
                      scaleIn(
                          spec = tween(240.ms),
                          initialScale = 0.94f,
                          transformOrigin = TransformOrigin.TopCenter,
                      ) +
                      slideInVertically(tween(240.ms)) { -24 } +
                      expandVertically(tween(260.ms)),
              exit =
                  fadeOut(tween(180.ms), targetAlpha = 0.1f) +
                      scaleOut(
                          spec = tween(220.ms),
                          targetScale = 0.96f,
                          transformOrigin = TransformOrigin.TopCenter,
                      ) +
                      slideOutVertically(tween(220.ms)) { -18 } +
                      shrinkVertically(tween(220.ms)),
              label = "sample/visibility/feed",
              style = Style.margin(bottom = 12.dp),
          ) {
            NotificationPanel(FULL_NOTIFICATIONS)
          },
          // visibility_example_end
      )
      child(StatusRow("Sibling", "Moves when the container leaves layout"))
      child(
          Column(
              style =
                  Style.widthPercent(100f)
                      .padding(all = 14.dp)
                      .background(RoundedRect(0xFFEFF6FF, 14.dp)),
          ) {
            child(Text("Next section", textSize = 15.sp, textColor = INK))
            child(
                Text(
                    "This row is outside AnimatedVisibility.",
                    textSize = 13.sp,
                    textColor = MUTED,
                    style = Style.margin(top = 4.dp),
                ),
            )
          },
      )
    }
  }
}

private fun ResourcesScope.NotificationPanel(
    notifications: List<NotificationItem>,
): Component = Column(
    style =
        Style.widthPercent(100f).padding(all = 14.dp).background(RoundedRect(0xFF102A43, 18.dp)),
) {
  child(Text("Priority inbox", textSize = 18.sp, textColor = Color.WHITE))
  notifications.forEach { item ->
    child(NotificationRow(item))
  }
}

private fun ResourcesScope.NotificationRow(item: NotificationItem): Component = Row(
    alignItems = YogaAlign.CENTER,
    style =
        Style.widthPercent(100f)
            .margin(top = 10.dp)
            .padding(all = 10.dp)
            .background(RoundedRect(0xFF1E3A5F, 12.dp)),
) {
  child(
      Column(
          style =
              Style.width(38.dp)
                  .height(38.dp)
                  .margin(end = 10.dp)
                  .background(RoundedRect(item.color, 19.dp)),
          alignItems = YogaAlign.CENTER,
          justifyContent = YogaJustify.CENTER,
      ) {
        child(Text(item.initials, textSize = 13.sp, textColor = Color.WHITE))
      },
  )
  child(
      Column(style = Style.flex(grow = 1f)) {
        child(Text(item.title, textSize = 14.sp, textColor = Color.WHITE))
        child(
            Text(
                item.subtitle,
                textSize = 12.sp,
                textColor = 0xFFB6C5D6.toInt(),
                style = Style.margin(top = 3.dp),
            ),
        )
      },
  )
  child(
      Text(
          item.count,
          textSize = 12.sp,
          textColor = Color.WHITE,
          style =
              Style.padding(horizontal = 8.dp, vertical = 4.dp)
                  .background(RoundedRect(0xFF486581, 12.dp)),
      ),
  )
}

private class NotificationItem(
    val initials: String,
    val title: String,
    val subtitle: String,
    val count: String,
    val color: Int,
)

private val FULL_NOTIFICATIONS = listOf(
    NotificationItem(
        "AK",
        "Design review",
        "Container motion tokens are ready",
        "3",
        0xFF2563EB.toInt(),
    ),
    NotificationItem(
        "MS",
        "Perf run",
        "Medium list reflow benchmark finished",
        "1",
        0xFF0F766E.toInt(),
    ),
    NotificationItem(
        "JL",
        "API feedback",
        "Naming notes from product surface team",
        "5",
        0xFFB45309.toInt(),
    ),
)

private val INK = 0xFF0F172A.toInt()
private val MUTED = 0xFF64748B.toInt()
