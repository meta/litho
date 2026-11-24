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

package com.facebook.litho.testing.espresso;

import static org.assertj.core.api.Assertions.assertThat;

import android.text.TextUtils;
import androidx.test.annotation.UiThreadTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentTree;
import com.facebook.litho.LithoView;
import com.facebook.litho.TextContent;
import com.facebook.litho.widget.Text;
import com.facebook.testing.screenshot.ViewHelpers;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device coverage for {@link TextContent.Item#getDisplayedText()}. This must run on a real
 * device/emulator: in its legacy graphics mode, Robolectric's {@code StaticLayout} does not
 * populate ellipsis metadata ({@code getEllipsisStart}/{@code getEllipsisCount}), so the
 * standard-ellipsize reconstruction in {@code LayoutTruncation.buildDisplayedText} can only be
 * exercised with a real layout. The displayed text is reconstructed from the layout's OWN text
 * ({@code layout.text}) so it reflects what is actually drawn.
 */
@RunWith(AndroidJUnit4.class)
public class TextEllipsisDisplayedTextTest {

  private static final String LONG_TEXT =
      "A very long string that will definitely not fit on a single narrow line";

  @UiThreadTest
  @Test
  public void displayedTextIsTheEllipsizedDrawnTextWhenTruncated() throws Throwable {
    final ComponentContext c =
        new ComponentContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    final Component component =
        Text.create(c).text(LONG_TEXT).maxLines(1).ellipsize(TextUtils.TruncateAt.END).build();
    final LithoView view = new LithoView(c);
    view.setComponentTree(ComponentTree.create(c, component).build());
    ViewHelpers.setupView(view).setExactWidthPx(120).setExactHeightPx(80).layout();

    final TextContent.Item item = view.getTextContent().get(0).getItems().get(0);
    final String displayed = item.getDisplayedText().toString();

    assertThat(item.isTruncated()).isTrue();
    // Reconstructed from the real layout's ellipsis metadata: the drawn line + the ellipsis glyph.
    assertThat(displayed).endsWith("…");
    assertThat(displayed.length()).isLessThan(LONG_TEXT.length());
    // The visible prefix is a genuine prefix of the original text (i.e. we report what is on
    // screen,
    // not a clamped slice of some other string).
    assertThat(LONG_TEXT).startsWith(displayed.substring(0, displayed.length() - 1));
  }

  @UiThreadTest
  @Test
  public void displayedTextEqualsTextWhenNotTruncated() throws Throwable {
    final String shortText = "Fits";
    final ComponentContext c =
        new ComponentContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
    final Component component =
        Text.create(c).text(shortText).maxLines(1).ellipsize(TextUtils.TruncateAt.END).build();
    final LithoView view = new LithoView(c);
    view.setComponentTree(ComponentTree.create(c, component).build());
    ViewHelpers.setupView(view).setExactWidthPx(400).setExactHeightPx(80).layout();

    final TextContent.Item item = view.getTextContent().get(0).getItems().get(0);

    assertThat(item.isTruncated()).isFalse();
    assertThat(item.getDisplayedText().toString()).isEqualTo(shortText);
  }
}
