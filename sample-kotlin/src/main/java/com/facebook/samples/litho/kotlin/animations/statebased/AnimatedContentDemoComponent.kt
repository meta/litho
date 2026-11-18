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
import com.facebook.litho.animation.AnimatedContent
import com.facebook.litho.animation.Easing
import com.facebook.litho.animation.TransformOrigin
import com.facebook.litho.animation.fadeIn
import com.facebook.litho.animation.fadeOut
import com.facebook.litho.animation.scaleIn
import com.facebook.litho.animation.tween
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.ContentPosition
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useState
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.clip
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms
import com.facebook.rendercore.sp
import com.facebook.rendercore.stringRes
import com.facebook.samples.litho.R
import com.facebook.samples.litho.kotlin.drawable.RoundedRect
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

@OptIn(ExperimentalLithoApi::class)
class AnimatedContentDemoComponent : KComponent() {

  override fun ComponentScope.render(): Component {
    val selected = useState { ContentPage.Overview }
    val page = selected.value

    return DemoSurface {
      child(SectionTitle("AnimatedContent"))
      child(
          Row(style = Style.margin(bottom = 12.dp), alignItems = YogaAlign.CENTER) {
            ContentPage.entries.forEach { item ->
              child(
                  DemoButton(
                      label = stringRes(item.labelRes),
                      selected = item == page,
                      style = Style.margin(end = 8.dp).onClick { selected.update(item) },
                  ),
              )
            }
          },
      )
      child(
          Column(
              style =
                  Style.widthPercent(100f)
                      .padding(all = 12.dp)
                      .background(RoundedRect(0xFFF8FAFC, 18.dp)),
          ) {
            child(
                // content_example_start
                AnimatedContent(
                    targetState = page,
                    contentKey = { it.key },
                    contentPosition = ContentPosition.TopCenter,
                    style =
                        Style.backgroundColor(0xFF0F172A.toInt())
                            .clip(Shape.RoundedRectangle(16.dp)),
                    transitionSpec = {
                      val isForward = targetState > initialState
                      val enterSpec =
                          tween<Float>(220.ms, delay = 50.ms, easing = Easing.LinearOutSlowIn)
                      enter =
                          fadeIn(enterSpec) +
                              scaleIn(
                                  spec = enterSpec,
                                  initialScale = if (isForward) 0.95f else 1.05f,
                                  transformOrigin = TransformOrigin.TopCenter,
                              )
                      exit = fadeOut(tween(90.ms, easing = Easing.FastOutLinearIn))
                    },
                    label = "sample/content/card",
                ) {
                  ContentPageCard(it)
                },
                // content_example_end
            )
          },
      )
      child(
          StatusRow(
              "Key",
              page.key,
              style = Style.margin(top = 12.dp),
          ),
      )
    }
  }
}

private fun ResourcesScope.ContentPageCard(page: ContentPage): Component = Column(
    style = Style.widthPercent(100f).padding(all = 16.dp),
) {
  child(
      Row(alignItems = YogaAlign.CENTER, style = Style.widthPercent(100f)) {
        child(
            Column(
                alignItems = YogaAlign.CENTER,
                justifyContent = YogaJustify.CENTER,
                style =
                    Style.width(42.dp)
                        .height(42.dp)
                        .margin(end = 12.dp)
                        .background(RoundedRect(page.color, 21.dp)),
            ) {
              child(Text(page.initials, textSize = 13.sp, textColor = Color.WHITE))
            },
        )
        child(
            Column(style = Style.flex(grow = 1f, shrink = 1f)) {
              child(
                  Text(stringRes(page.labelRes), textSize = 12.sp, textColor = 0xFFCBD5E1.toInt()),
              )
              child(
                  Text(
                      stringRes(page.titleRes),
                      textSize = 18.sp,
                      textColor = Color.WHITE,
                      style = Style.margin(top = 2.dp),
                  ),
              )
            },
        )
        child(
            Text(
                stringRes(page.statusRes),
                textSize = 11.sp,
                textColor = Color.WHITE,
                style =
                    Style.padding(horizontal = 9.dp, vertical = 5.dp)
                        .background(RoundedRect(page.color, 12.dp)),
            ),
        )
      },
  )
  child(
      Text(
          stringRes(page.summaryRes),
          textSize = 13.sp,
          textColor = 0xFFCBD5E1.toInt(),
          style = Style.margin(top = 14.dp),
      ),
  )
  child(
      Row(style = Style.widthPercent(100f).margin(top = 16.dp), alignItems = YogaAlign.CENTER) {
        page.stats.forEach { stat ->
          child(ContentMetric(stat, Style.flex(grow = 1f).margin(end = 8.dp)))
        }
      },
  )
  child(
      Column(style = Style.widthPercent(100f).margin(top = 14.dp)) {
        page.rows.forEach { child(ContentRow(it)) }
      },
  )
}

private fun ResourcesScope.ContentMetric(stat: ContentStat, style: Style): Component = Column(
    style =
        style
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .background(RoundedRect(0x1AFFFFFF, 12.dp)),
) {
  child(Text(stat.value, textSize = 16.sp, textColor = Color.WHITE))
  child(Text(stringRes(stat.labelRes), textSize = 11.sp, textColor = 0xFFCBD5E1.toInt()))
}

private fun ResourcesScope.ContentRow(row: ContentTimelineRow): Component = Row(
    alignItems = YogaAlign.CENTER,
    style = Style.widthPercent(100f).margin(top = 8.dp),
) {
  child(
      Column(
          style =
              Style.width(8.dp)
                  .height(8.dp)
                  .margin(end = 10.dp)
                  .background(RoundedRect(row.color, 4.dp)),
      ),
  )
  child(
      Column(style = Style.flex(grow = 1f, shrink = 1f)) {
        child(Text(stringRes(row.titleRes), textSize = 13.sp, textColor = Color.WHITE))
        child(
            Text(
                stringRes(row.subtitleRes),
                textSize = 12.sp,
                textColor = 0xFF94A3B8.toInt(),
                style = Style.margin(top = 2.dp),
            ),
        )
      },
  )
  if (row.meta.isNotEmpty()) {
    child(Text(row.meta, textSize = 12.sp, textColor = 0xFFCBD5E1.toInt()))
  }
}

private class ContentStat(
    val value: String,
    val labelRes: Int,
)

private class ContentTimelineRow(
    val titleRes: Int,
    val subtitleRes: Int,
    val meta: String,
    val color: Int,
)

private enum class ContentPage(
    val key: String,
    val labelRes: Int,
    val initials: String,
    val titleRes: Int,
    val summaryRes: Int,
    val statusRes: Int,
    val color: Int,
    val stats: List<ContentStat>,
    val rows: List<ContentTimelineRow>,
) {
  Overview(
      key = "overview",
      labelRes = R.string.litho_sample_animation_content_overview_label,
      initials = "RH",
      titleRes = R.string.litho_sample_animation_content_overview_title,
      summaryRes = R.string.litho_sample_animation_content_overview_summary,
      statusRes = R.string.litho_sample_animation_content_status_healthy,
      color = 0xFF2563EB.toInt(),
      stats =
          listOf(
              ContentStat("99.2%", R.string.litho_sample_animation_content_stat_success),
              ContentStat("18", R.string.litho_sample_animation_content_stat_alerts),
              ContentStat("4m", R.string.litho_sample_animation_content_stat_latency),
          ),
      rows =
          listOf(
              ContentTimelineRow(
                  R.string.litho_sample_animation_content_android_lane_title,
                  R.string.litho_sample_animation_content_android_lane_subtitle,
                  "2m",
                  0xFF22C55E.toInt(),
              ),
              ContentTimelineRow(
                  R.string.litho_sample_animation_content_feed_ranking_title,
                  R.string.litho_sample_animation_content_feed_ranking_subtitle,
                  "8m",
                  0xFF38BDF8.toInt(),
              ),
          ),
  ),
  Pipeline(
      key = "pipeline",
      labelRes = R.string.litho_sample_animation_content_pipeline_label,
      initials = "BQ",
      titleRes = R.string.litho_sample_animation_content_pipeline_title,
      summaryRes = R.string.litho_sample_animation_content_pipeline_summary,
      statusRes = R.string.litho_sample_animation_content_status_busy,
      color = 0xFFDB2777.toInt(),
      stats =
          listOf(
              ContentStat("42", R.string.litho_sample_animation_content_stat_jobs),
              ContentStat("7", R.string.litho_sample_animation_content_stat_queued),
              ContentStat("3", R.string.litho_sample_animation_content_stat_blocked),
          ),
      rows =
          listOf(
              ContentTimelineRow(
                  R.string.litho_sample_animation_content_emulator_pool_title,
                  R.string.litho_sample_animation_content_emulator_pool_subtitle,
                  "18m",
                  0xFFF97316.toInt(),
              ),
              ContentTimelineRow(
                  R.string.litho_sample_animation_content_ios_lane_title,
                  R.string.litho_sample_animation_content_ios_lane_subtitle,
                  "11m",
                  0xFFFACC15.toInt(),
              ),
              ContentTimelineRow(
                  R.string.litho_sample_animation_content_server_lane_title,
                  R.string.litho_sample_animation_content_server_lane_subtitle,
                  "now",
                  0xFF22C55E.toInt(),
              ),
          ),
  ),
  Review(
      key = "review",
      labelRes = R.string.litho_sample_animation_content_review_label,
      initials = "LC",
      titleRes = R.string.litho_sample_animation_content_review_title,
      summaryRes = R.string.litho_sample_animation_content_review_summary,
      statusRes = R.string.litho_sample_animation_content_status_review,
      color = 0xFF0F766E.toInt(),
      stats =
          listOf(
              ContentStat("12", R.string.litho_sample_animation_content_stat_owners),
              ContentStat("8", R.string.litho_sample_animation_content_stat_signed),
              ContentStat("2", R.string.litho_sample_animation_content_stat_pending),
          ),
      rows =
          listOf(
              ContentTimelineRow(
                  R.string.litho_sample_animation_content_privacy_review_title,
                  R.string.litho_sample_animation_content_privacy_review_subtitle,
                  "P1",
                  0xFFF97316.toInt(),
              ),
              ContentTimelineRow(
                  R.string.litho_sample_animation_content_experiment_owner_title,
                  R.string.litho_sample_animation_content_experiment_owner_subtitle,
                  "P2",
                  0xFF38BDF8.toInt(),
              ),
          ),
  ),
}
