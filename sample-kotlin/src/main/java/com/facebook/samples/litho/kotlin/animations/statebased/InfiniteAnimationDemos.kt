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
import android.graphics.drawable.GradientDrawable
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.Value
import com.facebook.litho.animation.AnimationPlayback
import com.facebook.litho.animation.Easing
import com.facebook.litho.animation.PlaybackState
import com.facebook.litho.animation.RepeatMode
import com.facebook.litho.animation.StartOffset
import com.facebook.litho.animation.StartOffsetType
import com.facebook.litho.animation.alpha
import com.facebook.litho.animation.backgroundColor
import com.facebook.litho.animation.rotation
import com.facebook.litho.animation.scale
import com.facebook.litho.animation.translationX
import com.facebook.litho.animation.translationY
import com.facebook.litho.animation.tween
import com.facebook.litho.animation.useAnimationPlayback
import com.facebook.litho.animation.useInfiniteAnimationGroup
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.size
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.StackLayout
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.FlexboxScope
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useState
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.clip
import com.facebook.litho.view.clipToOutline
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.graphics.Color as LithoColor
import com.facebook.rendercore.ms
import com.facebook.rendercore.sp
import com.facebook.rendercore.stringRes
import com.facebook.samples.litho.R
import com.facebook.samples.litho.kotlin.drawable.RoundedRect
import com.facebook.samples.litho.kotlin.primitives.widgets.VerticalScroll
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

/**
 * Shared playback pauses and resumes every group. Telemetry also uses `shouldRun` to stop its loop
 * and restart it from the beginning.
 */
@OptIn(ExperimentalLithoApi::class)
class InfiniteAnimationDemosComponent : KComponent() {

  override fun ComponentScope.render(): Component {
    val playback = useAnimationPlayback()

    return InfiniteDemoSurface {
      child(
          ShowcaseHeader(playback.state == PlaybackState.Playing) {
            if (playback.state == PlaybackState.Playing) playback.pause() else playback.play()
          },
      )
      child(ShowcaseContent(playback))
    }
  }
}

private fun ResourcesScope.ShowcaseHeader(
    running: Boolean,
    onClick: () -> Unit,
): Component = Row(
    alignItems = YogaAlign.CENTER,
    justifyContent = YogaJustify.SPACE_BETWEEN,
    style = Style.widthPercent(100f).margin(bottom = 12.dp),
) {
  child(
      Column(style = Style.flex(grow = 1f, shrink = 1f).margin(end = 12.dp)) {
        child(
            Text(
                stringRes(R.string.litho_sample_infinite_showcase_title),
                textSize = 22.sp,
                textColor = INK,
            ),
        )
        child(
            Text(
                stringRes(R.string.litho_sample_infinite_showcase_subtitle),
                textSize = 13.sp,
                textColor = MUTED,
                style = Style.margin(top = 4.dp),
            ),
        )
      },
  )
  child(
      RunToggle(
          running = running,
          labelRes =
              if (running) R.string.litho_sample_infinite_showcase_pause
              else R.string.litho_sample_infinite_showcase_resume,
          onClick = onClick,
      ),
  )
}

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.ShowcaseContent(playback: AnimationPlayback): Component =
    VerticalScroll(style = Style.clipToOutline(false)) {
      Column {
        child(LaunchCommandCenterComponent(playback))
        child(
            Row(style = Style.margin(top = 12.dp), alignItems = YogaAlign.STRETCH) {
              child(
                  TelemetryRefreshComponent(
                      playback,
                      Style.flex(grow = 1f).margin(end = 12.dp),
                  ),
              )
              child(LaunchAssistantComponent(playback, Style.flex(grow = 1f)))
            },
        )
        child(RolloutForecastComponent(playback, Style.margin(top = 12.dp)))
      }
    }

@OptIn(ExperimentalLithoApi::class)
private class LaunchCommandCenterComponent(private val playback: AnimationPlayback) : KComponent() {

  override fun ComponentScope.render(): Component {
    val infinite = useInfiniteAnimationGroup(label = "sample/launch-status", playback = playback)
    val scale =
        infinite.animateFloat(
            from = 0.92f,
            to = 1.08f,
            animation = tween(680.ms),
            label = "scale",
        )
    val alpha =
        infinite.animateFloat(
            from = 0.56f,
            to = 1f,
            animation = tween(680.ms),
            label = "alpha",
        )
    val color =
        infinite.animateColor(
            from = LithoColor.fromInt(0xFF2563EB.toInt()),
            to = LithoColor.fromInt(0xFF14B8A6.toInt()),
            animation = tween(1_360.ms),
            label = "color",
        )

    return ProductCard(
        style = Style.margin(top = 4.dp).background(RoundedRect(0xFF0F172A.toInt(), 22.dp)),
    ) {
      child(
          Row(
              alignItems = YogaAlign.CENTER,
              justifyContent = YogaJustify.SPACE_BETWEEN,
              style = Style.widthPercent(100f),
          ) {
            child(
                Row(alignItems = YogaAlign.CENTER, style = Style.flex(grow = 1f, shrink = 1f)) {
                  child(
                      LiveBeacon(
                          scale = scale,
                          alpha = alpha,
                          color = color,
                          style = Style.margin(end = 12.dp),
                      ),
                  )
                  child(
                      Column(style = Style.flex(grow = 1f, shrink = 1f)) {
                        child(
                            Text(
                                stringRes(R.string.litho_sample_infinite_showcase_hero_label),
                                textSize = 12.sp,
                                textColor = 0xFF99F6E4.toInt(),
                            ),
                        )
                        child(
                            Text(
                                stringRes(R.string.litho_sample_infinite_showcase_hero_title),
                                textSize = 20.sp,
                                textColor = Color.WHITE,
                                style = Style.margin(top = 2.dp),
                            ),
                        )
                      },
                  )
                },
            )
            child(StatusPill(R.string.litho_sample_infinite_showcase_live_status))
          },
      )
      child(
          Text(
              stringRes(R.string.litho_sample_infinite_showcase_hero_summary),
              textSize = 13.sp,
              textColor = 0xFFCBD5E1.toInt(),
              style = Style.margin(top = 14.dp),
          ),
      )
      child(
          Row(
              style = Style.widthPercent(100f).margin(top = 18.dp),
              alignItems = YogaAlign.STRETCH,
          ) {
            child(
                ProductMetric(
                    valueRes = R.string.litho_sample_infinite_showcase_metric_health_value,
                    labelRes = R.string.litho_sample_infinite_showcase_metric_health,
                    style = Style.flex(grow = 1f).margin(end = 8.dp),
                ),
            )
            child(
                ProductMetric(
                    valueRes = R.string.litho_sample_infinite_showcase_metric_checks_value,
                    labelRes = R.string.litho_sample_infinite_showcase_metric_checks,
                    style = Style.flex(grow = 1f).margin(end = 8.dp),
                ),
            )
            child(
                ProductMetric(
                    valueRes = R.string.litho_sample_infinite_showcase_metric_latency_value,
                    labelRes = R.string.litho_sample_infinite_showcase_metric_latency,
                    style = Style.flex(grow = 1f),
                ),
            )
          },
      )
    }
  }
}

@OptIn(ExperimentalLithoApi::class)
private class TelemetryRefreshComponent(
    private val playback: AnimationPlayback,
    private val style: Style,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val runState = useState { true }
    val running = runState.value
    val infinite = useInfiniteAnimationGroup(
        shouldRun = running,
        label = "sample/telemetry-sync",
        playback = playback,
    )
    val rotation =
        infinite.animateFloat(
            from = 0f,
            to = 360f,
            animation = tween(1_000.ms, easing = Easing.Linear),
            repeatMode = RepeatMode.Restart,
            label = "rotation",
        )

    return ProductCard(style) {
      child(SectionLabel(R.string.litho_sample_infinite_showcase_telemetry_label))
      child(
          Column(
              alignItems = YogaAlign.CENTER,
              justifyContent = YogaJustify.CENTER,
              style = Style.widthPercent(100f).height(126.dp).margin(top = 10.dp),
          ) {
            child(SyncSpinner(rotation))
            child(
                Text(
                    stringRes(
                        if (running) R.string.litho_sample_infinite_showcase_telemetry_title
                        else R.string.litho_sample_infinite_showcase_telemetry_stopped_title,
                    ),
                    textSize = 15.sp,
                    textColor = INK,
                    style = Style.margin(top = 12.dp),
                ),
            )
            child(
                Text(
                    stringRes(
                        if (running) R.string.litho_sample_infinite_showcase_telemetry_subtitle
                        else R.string.litho_sample_infinite_showcase_telemetry_stopped_subtitle,
                    ),
                    textSize = 12.sp,
                    textColor = MUTED,
                    style = Style.margin(top = 4.dp),
                ),
            )
          },
      )
      child(
          Row(
              justifyContent = YogaJustify.CENTER,
              style = Style.widthPercent(100f).margin(top = 12.dp),
          ) {
            child(
                RunToggle(
                    running = running,
                    labelRes =
                        if (running) R.string.litho_sample_infinite_showcase_stop
                        else R.string.litho_sample_infinite_showcase_restart,
                    onClick = { runState.update { !it } },
                ),
            )
          },
      )
    }
  }
}

@OptIn(ExperimentalLithoApi::class)
private class LaunchAssistantComponent(
    private val playback: AnimationPlayback,
    private val style: Style,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val infinite = useInfiniteAnimationGroup(label = "sample/assistant-dots", playback = playback)
    val offsets =
        (0..2).map { index ->
          infinite.animateFloat(
              from = 0f,
              to = -12f,
              animation = tween(360.ms),
              initialStartOffset = StartOffset(index * 120, StartOffsetType.FastForward),
              label = "dot$index",
          )
        }

    return ProductCard(style) {
      child(SectionLabel(R.string.litho_sample_infinite_showcase_assistant_label))
      child(
          Column(
              justifyContent = YogaJustify.CENTER,
              style = Style.widthPercent(100f).height(126.dp).margin(top = 10.dp),
          ) {
            child(
                Text(
                    stringRes(R.string.litho_sample_infinite_showcase_assistant_title),
                    textSize = 15.sp,
                    textColor = INK,
                ),
            )
            child(
                Text(
                    stringRes(R.string.litho_sample_infinite_showcase_assistant_subtitle),
                    textSize = 12.sp,
                    textColor = MUTED,
                    style = Style.margin(top = 4.dp),
                ),
            )
            child(TypingIndicator(offsets, Style.margin(top = 18.dp)))
          },
      )
    }
  }
}

@OptIn(ExperimentalLithoApi::class)
private class RolloutForecastComponent(
    private val playback: AnimationPlayback,
    private val style: Style,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val infinite = useInfiniteAnimationGroup(label = "sample/forecast-shimmer", playback = playback)
    val translateX =
        infinite.animateFloat(
            from = -220f,
            to = 420f,
            animation = tween(1_800.ms, easing = Easing.Linear),
            repeatMode = RepeatMode.Restart,
            label = "sweep",
        )
    val sweepAlpha =
        infinite.animateFloat(
            from = 0.46f,
            to = 0.88f,
            animation = tween(900.ms),
            label = "sweepAlpha",
        )

    return ProductCard(style) {
      child(
          Row(
              alignItems = YogaAlign.CENTER,
              justifyContent = YogaJustify.SPACE_BETWEEN,
              style = Style.widthPercent(100f),
          ) {
            child(SectionLabel(R.string.litho_sample_infinite_showcase_forecast_label))
            child(
                Text(
                    stringRes(R.string.litho_sample_infinite_showcase_forecast_status),
                    textSize = 12.sp,
                    textColor = MUTED,
                ),
            )
          },
      )
      child(
          StackLayout(
              style =
                  Style.widthPercent(100f)
                      .height(148.dp)
                      .margin(top = 14.dp)
                      .clip(Shape.RoundedRectangle(18.dp))
                      .background(RoundedRect(0xFFF8FAFC, 18.dp)),
          ) {
            child(ForecastSkeleton())
            child(
                ShimmerSweep(
                    Style.width(220.dp).height(148.dp).translationX(translateX).alpha(sweepAlpha),
                ),
            )
          },
      )
    }
  }
}

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.LiveBeacon(
    scale: Value<Float>,
    alpha: Value<Float>,
    color: Value<LithoColor>,
    style: Style,
): Component = Column(
    alignItems = YogaAlign.CENTER,
    justifyContent = YogaJustify.CENTER,
    style = style.size(58.dp).scale(scale).alpha(alpha).clip(Shape.Circle).backgroundColor(color),
) {
  child(Column(style = Style.size(18.dp).background(RoundedRect(0xFFFFFFFF.toInt(), 9.dp))))
}

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.SyncSpinner(rotation: Value<Float>): Component =
    StackLayout(style = Style.size(62.dp).rotation(rotation)) {
      child(Column(style = Style.size(62.dp).background(RoundedRect(0xFFE0F2FE, 31.dp))))
      child(
          Column(
              style =
                  Style.width(22.dp)
                      .height(22.dp)
                      .margin(start = 21.dp)
                      .background(RoundedRect(0xFF0284C7.toInt(), 11.dp)),
          ),
      )
      child(
          Column(
              style =
                  Style.width(34.dp)
                      .height(34.dp)
                      .margin(start = 14.dp, top = 14.dp)
                      .background(RoundedRect(0xFFFFFFFF.toInt(), 17.dp)),
          ),
      )
    }

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.TypingIndicator(
    offsets: List<Value<Float>>,
    style: Style,
): Component = Row(
    alignItems = YogaAlign.CENTER,
    justifyContent = YogaJustify.CENTER,
    style = style.widthPercent(100f).height(42.dp).background(RoundedRect(0xFFF1F5F9, 21.dp)),
) {
  offsets.forEachIndexed { index, offset ->
    child(
        Column(
            style =
                Style.size(10.dp)
                    .margin(horizontal = 4.dp)
                    .translationY(offset)
                    .background(RoundedRect(DOT_COLORS[index], 5.dp)),
        ),
    )
  }
}

private fun ResourcesScope.ForecastSkeleton(): Component =
    Column(style = Style.widthPercent(100f).padding(all = 16.dp)) {
      child(
          Row(alignItems = YogaAlign.CENTER, style = Style.widthPercent(100f)) {
            child(SkeletonBlock(SKELETON_STRONG, Style.size(38.dp)))
            child(
                Column(style = Style.flex(grow = 1f).margin(start = 12.dp)) {
                  child(SkeletonLine(SKELETON_STRONG, Style.widthPercent(52f)))
                  child(SkeletonLine(SKELETON_SOFT, Style.widthPercent(34f).margin(top = 8.dp)))
                },
            )
            child(SkeletonLine(SKELETON_SOFT, Style.width(56.dp).height(22.dp)))
          },
      )
      child(
          Row(
              style = Style.widthPercent(100f).margin(top = 18.dp),
              alignItems = YogaAlign.STRETCH,
          ) {
            child(
                SkeletonBlock(
                    SKELETON_SOFT,
                    Style.flex(grow = 1f).height(48.dp).margin(end = 8.dp),
                ),
            )
            child(
                SkeletonBlock(
                    SKELETON_SOFT,
                    Style.flex(grow = 1f).height(48.dp).margin(end = 8.dp),
                ),
            )
            child(SkeletonBlock(SKELETON_SOFT, Style.flex(grow = 1f).height(48.dp)))
          },
      )
      child(SkeletonLine(SKELETON_SOFT, Style.widthPercent(66f).margin(top = 12.dp)))
    }

private fun ResourcesScope.StatusPill(labelRes: Int): Component = Text(
    stringRes(labelRes),
    textSize = 12.sp,
    textColor = Color.WHITE,
    style =
        Style.padding(horizontal = 10.dp, vertical = 5.dp)
            .background(RoundedRect(0xFF0F766E, 13.dp)),
)

private fun ResourcesScope.ProductMetric(
    valueRes: Int,
    labelRes: Int,
    style: Style,
): Component = Column(
    style =
        style
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .background(RoundedRect(0x1AFFFFFF, 14.dp)),
) {
  child(Text(stringRes(valueRes), textSize = 16.sp, textColor = Color.WHITE))
  child(Text(stringRes(labelRes), textSize = 11.sp, textColor = 0xFFCBD5E1.toInt()))
}

private fun ResourcesScope.InfiniteDemoSurface(content: FlexboxScope.() -> Unit): Component =
    Column(style = Style.padding(all = 18.dp).background(RoundedRect(0xFFF8FAFC.toInt(), 0.dp))) {
      content()
    }

private fun ResourcesScope.ProductCard(
    style: Style = Style,
    content: FlexboxScope.() -> Unit,
): Component = Column(
    style =
        Style.widthPercent(100f)
            .padding(all = 16.dp)
            .background(RoundedRect(0xFFFFFFFF.toInt(), 18.dp)) + style,
) {
  content()
}

private fun ResourcesScope.SectionLabel(labelRes: Int): Component = Text(
    stringRes(labelRes),
    textSize = 12.sp,
    textColor = MUTED,
    style =
        Style.padding(horizontal = 10.dp, vertical = 5.dp)
            .background(RoundedRect(0xFFEFF6FF, 14.dp)),
)

private fun ResourcesScope.RunToggle(
    running: Boolean,
    labelRes: Int,
    onClick: () -> Unit,
): Component = Row(
    alignItems = YogaAlign.CENTER,
    justifyContent = YogaJustify.CENTER,
    style =
        Style.width(104.dp)
            .height(40.dp)
            .padding(horizontal = 12.dp)
            .background(RoundedRect(if (running) 0xFF0F766E else 0xFFE2E8F0, 19.dp))
            .onClick { onClick() },
) {
  child(
      Text(
          stringRes(labelRes),
          textSize = 13.sp,
          textColor = if (running) Color.WHITE else INK,
      ),
  )
}

private fun ResourcesScope.SkeletonLine(
    color: Int,
    style: Style,
): Component = Column(style = style.height(14.dp).background(RoundedRect(color, 7.dp)))

private fun ResourcesScope.SkeletonBlock(
    color: Int,
    style: Style,
): Component = Column(style = style.background(RoundedRect(color, 12.dp)))

private fun ResourcesScope.ShimmerSweep(style: Style): Component = Column(
    style =
        style.background(
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    0x00FFFFFF,
                    0x22FFFFFF,
                    0x7AFFFFFF,
                    0x22FFFFFF,
                    0x00FFFFFF,
                ),
            )
                .apply { cornerRadius = 28.dp.toPixels(resourceResolver).toFloat() },
        ),
)

private val DOT_COLORS = intArrayOf(0xFF2563EB.toInt(), 0xFF0F766E.toInt(), 0xFFDB2777.toInt())
private val SKELETON_SOFT = 0xFFE5E7EB.toInt()
private val SKELETON_STRONG = 0xFFD1D5DB.toInt()

private val INK = 0xFF0F172A.toInt()
private val MUTED = 0xFF64748B.toInt()
