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
import android.graphics.Typeface
import com.facebook.litho.AccessibilityRole
import com.facebook.litho.ClickEvent
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.accessibility.accessibilityHeading
import com.facebook.litho.accessibility.accessibilityRole
import com.facebook.litho.accessibility.contentDescription
import com.facebook.litho.animation.PlaybackState
import com.facebook.litho.animation.useAnimationPlayback
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.heightPercent
import com.facebook.litho.core.margin
import com.facebook.litho.core.minHeight
import com.facebook.litho.core.padding
import com.facebook.litho.core.size
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.border
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useCached
import com.facebook.litho.useCallback
import com.facebook.litho.useEffect
import com.facebook.litho.useState
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.clip
import com.facebook.litho.view.onClick
import com.facebook.litho.view.selected
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp
import com.facebook.rendercore.stringRes
import com.facebook.samples.litho.R
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

/**
 * Scene choreography and shared playback in one `useAnimationScope`. Scene selections and replay
 * replace the `launchLatest` run, which combines finite sculpting steps with endless rotation and
 * breathing loops. [useAnimationPlayback] freezes and resumes the whole sequence.
 */
@OptIn(ExperimentalLithoApi::class)
class AnimationScopeShowcaseComponent : KComponent() {
  override fun ComponentScope.render(): Component = PrismStudio()
}

@OptIn(ExperimentalLithoApi::class)
private class PrismStudio : KComponent() {
  override fun ComponentScope.render(): Component {
    val selectedScene = useState { PrismScene.Halo }
    val scene = selectedScene.value
    val playback = useAnimationPlayback()
    val paused = playback.state == PlaybackState.Paused
    val choreography = usePrismChoreography(playback)

    useEffect(choreography) {
      choreography.introduce()
      null
    }
    val chooseScene =
        useCallback<PrismScene, Unit> { next ->
          selectedScene.update { next }
          choreography.play(next)
        }
    val replay = useCallback<ClickEvent, Unit> { choreography.play(scene) }
    val togglePlayback =
        useCallback<ClickEvent, Unit> {
          if (playback.state == PlaybackState.Playing) {
            playback.pause()
          } else {
            playback.play()
          }
        }
    val cardShape = useCached { Shape.RoundedRectangle(28.dp) }

    return Column(
        style =
            Style.widthPercent(100f)
                .heightPercent(100f)
                .padding(horizontal = 22.dp, vertical = 28.dp)
                .backgroundColor(PRISM_BACKGROUND),
    ) {
      child(
          Text(
              stringRes(R.string.litho_sample_prism_eyebrow),
              textSize = 11.sp,
              textColor = PRISM_ACCENT,
              textStyle = Typeface.BOLD,
          ),
      )
      child(
          Text(
              stringRes(R.string.litho_sample_prism_title),
              textSize = 46.sp,
              textColor = Color.WHITE,
              textStyle = Typeface.BOLD,
              style = Style.margin(top = 8.dp).accessibilityHeading(true),
          ),
      )
      child(
          Text(
              stringRes(R.string.litho_sample_prism_subtitle),
              textSize = 15.sp,
              textColor = PRISM_MUTED,
              style = Style.margin(top = 2.dp, bottom = 24.dp),
          ),
      )
      child(
          Column(
              style =
                  Style.widthPercent(100f)
                      .flex(grow = 1f, shrink = 1f, basis = 0.dp)
                      .background(PRISM_SURFACE, cardShape)
                      .border(1.dp, PRISM_BORDER, 28.dp)
                      .clip(cardShape),
          ) {
            child(
                PrismField(
                    motion = choreography,
                    style =
                        Style.widthPercent(100f)
                            .flex(grow = 1f, shrink = 1f, basis = 0.dp)
                            .accessibilityRole(AccessibilityRole.BUTTON)
                            .contentDescription(
                                stringRes(R.string.litho_sample_prism_replay_description),
                            )
                            .onClick(action = replay),
                ),
            )
            child(
                Row(
                    alignItems = YogaAlign.CENTER,
                    style = Style.padding(horizontal = 20.dp, top = 4.dp, bottom = 20.dp),
                ) {
                  child(
                      Column(style = Style.flex(grow = 1f, shrink = 1f).margin(end = 12.dp)) {
                        child(
                            Text(
                                stringRes(scene.titleRes),
                                textSize = 17.sp,
                                textColor = Color.WHITE,
                                textStyle = Typeface.BOLD,
                            ),
                        )
                        child(
                            Text(
                                stringRes(scene.descriptionRes),
                                textSize = 12.sp,
                                textColor = PRISM_MUTED,
                                style = Style.margin(top = 4.dp),
                            ),
                        )
                      },
                  )
                  child(
                      Column(
                          style =
                              Style.size(6.dp)
                                  .background(
                                      if (paused) PRISM_MUTED else PRISM_ACCENT,
                                      Shape.Circle,
                                  ),
                      ),
                  )
                },
            )
          },
      )
      child(
          Row(gap = 8.dp, style = Style.margin(top = 20.dp)) {
            for (option in PrismScene.entries) {
              child(
                  SceneButton(
                      option,
                      option == scene,
                      chooseScene,
                      Style.flex(basis = 0.dp, grow = 1f),
                  ),
              )
            }
          },
      )
      child(
          Row(gap = 8.dp, style = Style.margin(top = 10.dp)) {
            child(
                StudioButton(
                    label =
                        stringRes(
                            if (paused) R.string.litho_sample_prism_resume
                            else R.string.litho_sample_prism_pause,
                        ),
                    active = false,
                    onClick = togglePlayback,
                    style = Style.flex(basis = 0.dp, grow = 1f),
                ),
            )
            child(
                StudioButton(
                    label = stringRes(R.string.litho_sample_prism_replay),
                    active = false,
                    onClick = replay,
                    style = Style.flex(basis = 0.dp, grow = 1f),
                ),
            )
          },
      )
      child(
          Text(
              stringRes(R.string.litho_sample_prism_hint),
              textSize = 13.sp,
              textColor = PRISM_MUTED,
              style = Style.margin(top = 18.dp),
          ),
      )
    }
  }
}

private class SceneButton(
    private val scene: PrismScene,
    private val active: Boolean,
    private val onChoose: (PrismScene) -> Unit,
    private val style: Style,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    val choose = useCallback<ClickEvent, Unit> { onChoose(scene) }
    return StudioButton(stringRes(scene.labelRes), active, choose, style)
  }
}

private class StudioButton(
    private val label: String,
    private val active: Boolean,
    private val onClick: (ClickEvent) -> Unit,
    private val style: Style,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    val shape = useCached { Shape.RoundedRectangle(14.dp) }
    return Row(
        alignItems = YogaAlign.CENTER,
        justifyContent = YogaJustify.CENTER,
        style =
            style
                .minHeight(50.dp)
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .background(if (active) PRISM_ACCENT else PRISM_SURFACE, shape)
                .border(1.dp, if (active) PRISM_ACCENT else PRISM_BORDER, 14.dp)
                .accessibilityRole(AccessibilityRole.BUTTON)
                .selected(active)
                .onClick(action = onClick),
    ) {
      child(
          Text(
              label,
              textSize = 14.sp,
              textColor = if (active) PRISM_BACKGROUND else Color.WHITE,
              textStyle = Typeface.BOLD,
          ),
      )
    }
  }
}

internal val PRISM_BACKGROUND = 0xFF070914.toInt()
internal val PRISM_SURFACE = 0xFF0D1222.toInt()
private val PRISM_BORDER = 0xFF25304A.toInt()
private val PRISM_MUTED = 0xFF8D9BB6.toInt()
private val PRISM_ACCENT = 0xFFBEEFFF.toInt()
