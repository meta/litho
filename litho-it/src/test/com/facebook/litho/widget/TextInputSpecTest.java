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

package com.facebook.litho.widget;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Layout;
import android.text.Selection;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.LinkMovementMethod;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.facebook.litho.Column;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.EventHandler;
import com.facebook.litho.Handle;
import com.facebook.litho.LithoView;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.it.R;
import com.facebook.litho.testing.LithoTestRule;
import com.facebook.litho.testing.TestLithoView;
import com.facebook.litho.testing.eventhandler.EventHandlerTestHelper;
import com.facebook.litho.testing.helper.ComponentTestHelper;
import com.facebook.litho.testing.testrunner.LithoTestRunner;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.LooperMode;

/** Tests {@link TextInput} component. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner.class)
public class TextInputSpecTest {
  // Robolectric's legacy text layout breaks lines only at line breaks, so the uncut hint measures
  // two lines through the one past the cut.
  private static final String LONG_HINT =
      "Reply to Puaonaonamakamaeokalanikea about the view of the canyon\nand the river";
  private static final int NARROW_WIDTH_PX = 60;

  private ComponentContext mContext;

  public @Rule LithoTestRule lithoTestRule = new LithoTestRule();

  @Before
  public void setup() {
    mContext = new ComponentContext(getApplicationContext());
  }

  @Test
  public void testTextInputWithText() {
    String text = "Dummy text";
    int textSize = 10;
    Component.Builder component = TextInput.create(mContext).textSizePx(textSize).initialText(text);
    final android.widget.EditText editText = getEditText(component);

    assertThat(editText.getText().toString()).isEqualTo(text);
    assertThat(editText.getTextSize()).isEqualTo(textSize);
    assertThat(editText.getSelectionStart()).isEqualTo(textSize);
    assertThat(editText.getSelectionEnd()).isEqualTo(textSize);
  }

  // ArrowKeyMovementMethod passes -1 when Shift is pressed between touch down and up
  // (T291343055).
  @Test
  public void testSetSelectionWithNegativeOffsetIsClamped() {
    assertSelectionClamped(false, "hello world", -1, 0);
  }

  @Test
  public void testSetSelectionWithNegativeOffsetIsClampedForPrimitive() {
    assertSelectionClamped(true, "hello world", -1, 0);
  }

  @Test
  public void testSetSelectionPastEndIsClamped() {
    assertSelectionClamped(false, "abc", 11, 3);
  }

  @Test
  public void testSetSelectionPastEndIsClampedForPrimitive() {
    assertSelectionClamped(true, "abc", 11, 3);
  }

  private void assertSelectionClamped(
      boolean usePrimitiveTextInput, String text, int requestedOffset, int expectedOffset) {
    final boolean originalUsePrimitiveTextInput = ComponentsConfiguration.usePrimitiveTextInput;
    ComponentsConfiguration.usePrimitiveTextInput = usePrimitiveTextInput;
    try {
      final EditText editText = getEditText(TextInput.create(mContext).initialText(text));

      Selection.setSelection(editText.getText(), requestedOffset);

      assertThat(editText.getSelectionStart()).isEqualTo(expectedOffset);
      assertThat(editText.getSelectionEnd()).isEqualTo(expectedOffset);
    } finally {
      ComponentsConfiguration.usePrimitiveTextInput = originalUsePrimitiveTextInput;
    }
  }

  @Test
  public void testTextInputMultiline() {
    String multiline = "a\nb\nc";

    Component.Builder component = TextInput.create(mContext).initialText(multiline);
    android.widget.EditText editText = getEditText(component);
    assertThat(editText.getLineCount()).isEqualTo(1);

    component = TextInput.create(mContext).initialText(multiline).multiline(true);
    editText = getEditText(component);
    assertThat(editText.getLineCount()).isEqualTo(3);
  }

  @Test
  public void testLongHintFitsOneLineOnEmptyExpandedInputAndIsReadWhole() {
    assertLongHintFitsOneLineAndIsReadWhole(false);
  }

  @Test
  public void testLongHintFitsOneLineOnEmptyExpandedInputAndIsReadWholeForPrimitive() {
    assertLongHintFitsOneLineAndIsReadWhole(true);
  }

  @Test
  public void testHintStaysAsGivenWithoutHintOnOneLine() {
    final EditText field = getEditText(render(multilineInput().hint(LONG_HINT)));

    assertThat(field.getHint().toString()).isEqualTo(LONG_HINT);
  }

  private void assertLongHintFitsOneLineAndIsReadWhole(boolean usePrimitiveTextInput) {
    final boolean originalUsePrimitiveTextInput = ComponentsConfiguration.usePrimitiveTextInput;
    ComponentsConfiguration.usePrimitiveTextInput = usePrimitiveTextInput;
    try {
      final EditText field =
          getEditText(render(multilineInput().hint(LONG_HINT).isHintOnOneLine(true)));
      final EditText oneLine = getEditText(render(multilineInput().initialText("x")));
      final int textWidth =
          field.getWidth() - field.getCompoundPaddingLeft() - field.getCompoundPaddingRight();

      assertThat(Math.ceil(Layout.getDesiredWidth(field.getHint(), field.getPaint())))
          .isLessThanOrEqualTo(textWidth);
      assertThat(field.getHeight()).isEqualTo(oneLine.getHeight());
      assertThat(
              AccessibilityNodeInfoCompat.wrap(field.createAccessibilityNodeInfo())
                  .getHintText()
                  .toString())
          .isEqualTo(LONG_HINT);
    } finally {
      ComponentsConfiguration.usePrimitiveTextInput = originalUsePrimitiveTextInput;
    }
  }

  private TextInput.Builder multilineInput() {
    return TextInput.create(lithoTestRule.context).multiline(true).maxLines(5);
  }

  private LithoView render(TextInput.Builder input) {
    return lithoTestRule
        .render(null, null, NARROW_WIDTH_PX, componentScope -> input.build())
        .getLithoView();
  }

  @Test
  public void testCursorDrawableResNotSet()
      throws IllegalAccessException, NoSuchFieldException, ClassNotFoundException {
    // Obtain default style value with the reflection
    // https://stackoverflow.com/questions/8683411/the-import-com-android-internal-r-cannot-be-resolved/8683466
    Class clsStyleable = Class.forName("com.android.internal.R$styleable");
    Field fldStyleable = clsStyleable.getDeclaredField("TextView");
    fldStyleable.setAccessible(true);
    int[] textStyleArr = (int[]) fldStyleable.get(null);
    int defEditTextStyle = Resources.getSystem().getIdentifier("editTextStyle", "attr", "android");
    final Resources.Theme theme = mContext.getAndroidContext().getTheme();
    TypedArray a = theme.obtainStyledAttributes(null, textStyleArr, defEditTextStyle, 0);

    fldStyleable = clsStyleable.getDeclaredField("TextView_textCursorDrawable");
    fldStyleable.setAccessible(true);
    int textCursorStyle = (Integer) fldStyleable.get(null);

    int defaultDrawableRes = a.getResourceId(textCursorStyle, 0);

    Component.Builder component = TextInput.create(mContext);
    final android.widget.EditText editText = getEditText(component);
    Field f = TextView.class.getDeclaredField("mCursorDrawableRes");
    f.setAccessible(true);
    Object actualDrawableRes = f.get(editText);

    assertThat(actualDrawableRes).isEqualTo(defaultDrawableRes);
  }

  @Test
  public void testCursorDrawableResSet() {
    int drawableRes = R.drawable.litho;
    Component.Builder component = TextInput.create(mContext).cursorDrawableRes(drawableRes);
    final android.widget.EditText editText = getEditText(component);

    assertThat(editText.getTextCursorDrawable()).isNotNull();
  }

  @Test
  public void testNullInputBackground() {
    Component.Builder component = TextInput.create(mContext).inputBackground(null);
    final android.widget.EditText editText = getEditText(component);
    Drawable editTextBackground = editText.getBackground();
    assertThat(editTextBackground).isEqualTo(null);
  }

  @Test
  public void testDefaultInputBackground() {
    Component.Builder component = TextInput.create(mContext);
    final android.widget.EditText editText = getEditText(component);
    Drawable editTextBackground = editText.getBackground();
    assertThat(editTextBackground).isNotNull();
  }

  @Test
  public void testSetMovementMethod() {
    Component.Builder component =
        TextInput.create(mContext).movementMethod(LinkMovementMethod.getInstance());
    final android.widget.EditText editText = getEditText(component);
    assertThat(editText.getMovementMethod()).isInstanceOf(LinkMovementMethod.class);
  }

  @Test
  public void testDefaultMovementMethod() {
    Component.Builder component = TextInput.create(mContext);
    final android.widget.EditText editText = getEditText(component);
    assertThat(editText.getMovementMethod()).isInstanceOf(ArrowKeyMovementMethod.class);
    assertThat(new EditText(getApplicationContext()).getMovementMethod())
        .isInstanceOf(ArrowKeyMovementMethod.class);
  }

  @Test
  public void testErrorState() {
    String errorMessage = "Error message";
    Component.Builder component = TextInput.create(mContext).error(errorMessage);
    final android.widget.EditText editText = getEditText(component);
    assertThat(editText.getError()).isEqualTo(errorMessage);
    component = TextInput.create(mContext).error(null);
    assertThat(getEditText(component).getError()).isNullOrEmpty();
  }

  @Test
  public void testOnConnectionEventHandler() {
    InputConnection inputConnection = mock(InputConnection.class);
    EventHandler<InputConnectionEvent> inputConnectionEventHandler =
        EventHandlerTestHelper.createMockEventHandler(
            InputConnectionEvent.class,
            new EventHandlerTestHelper.MockEventHandler<InputConnectionEvent, Object>() {
              @Override
              public Object handleEvent(InputConnectionEvent event) {
                return inputConnection;
              }
            });
    Component.Builder component =
        TextInput.create(mContext).inputConnectionEventHandler(inputConnectionEventHandler);
    final android.widget.EditText editText = getEditText(component);
    InputConnection editTextInputConnection =
        editText.onCreateInputConnection(mock(EditorInfo.class));
    assertThat(editTextInputConnection).isEqualTo(inputConnection);
  }

  @Test
  public void textInput_getTextTrigger_returnsCurrentText() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    getEditText(testLithoView.getLithoView()).setText("text for test");

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    final CharSequence text = TextInput.getText(lithoTestRule.context, handle);
    assertThat(text).isNotNull();
    assertThat(text.toString()).isEqualTo("text for test");
  }

  @Test
  public void textInput_onTextChanged_whenSnapshotEnabled_savesDetachedText() {
    final boolean previousValue = ComponentsConfiguration.snapshotTextInputTextOnTextChanged;
    try {
      ComponentsConfiguration.snapshotTextInputTextOnTextChanged = true;
      final Handle handle = new Handle();
      final TestLithoView testLithoView =
          lithoTestRule.render(
              componentScope -> TextInput.create(lithoTestRule.context).handle(handle).build());
      final EditText editText = getEditText(testLithoView.getLithoView());

      editText.getText().append("before");
      final Editable liveText = editText.getText();
      testLithoView.getLithoView().unmountAllItems();
      liveText.append(" after");

      final CharSequence savedText = TextInput.getText(lithoTestRule.context, handle);
      assertThat(savedText).isNotSameAs(liveText);
      assertThat(savedText.toString()).isEqualTo("before");
    } finally {
      ComponentsConfiguration.snapshotTextInputTextOnTextChanged = previousValue;
    }
  }

  @Test
  public void textInput_setTextTrigger_setsText() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    TextInput.setText(lithoTestRule.context, handle, "set text in test");
    assertThat(getEditText(testLithoView.getLithoView()).getText().toString())
        .isEqualTo("set text in test");
  }

  @Test
  public void textInput_getTextTriggerFromUnmountedView_returnsCurrentText() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    getEditText(testLithoView.getLithoView()).setText("text for test");
    testLithoView.getLithoView().unmountAllItems();

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    final CharSequence text = TextInput.getText(lithoTestRule.context, handle);
    assertThat(text).isNotNull();
    assertThat(text.toString()).isEqualTo("text for test");
  }

  @Test
  public void textInput_setTextTriggerForUnmountedView_setsTextAfterMount() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    testLithoView.getLithoView().unmountAllItems();

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    TextInput.setText(lithoTestRule.context, handle, "set text in test");
    final CharSequence text = TextInput.getText(lithoTestRule.context, handle);
    assertThat(text).isNotNull();
    assertThat(text.toString()).isEqualTo("set text in test");

    // Mount the view again
    testLithoView.layout();

    assertThat(getEditText(testLithoView.getLithoView()).getText().toString())
        .isEqualTo("set text in test");
  }

  @Test
  public void textInput_updateWithNewTextInputAndUseGetTextTrigger_returnsCurrentText() {
    lithoTestRule.render(
        componentScope ->
            Column.create(lithoTestRule.context)
                .child(TextInput.create(lithoTestRule.context))
                .build());

    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(
                        TextInput.create(lithoTestRule.context).key("different_key").handle(handle))
                    .build());

    getEditText(testLithoView.getLithoView()).setText("text for test");

    CharSequence text = TextInput.getText(lithoTestRule.context, handle);
    assertThat(text).isNotNull();
    assertThat(text.toString()).isEqualTo("text for test");
  }

  private static EditText getEditText(Component.Builder component) {
    return getEditText(ComponentTestHelper.mountComponent(component));
  }

  private static EditText getEditText(LithoView lithoView) {
    return (EditText) lithoView.getChildAt(0);
  }

  @Test
  public void textInput_setReplaceText_replacesText() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    final CharSequence textToSet = "set text in test";
    final EditText editText = getEditText(testLithoView.getLithoView());

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    TextInput.replaceText(lithoTestRule.context, handle, textToSet, 0, 0, false);
    assertThat(editText.getText().toString()).isEqualTo(textToSet);
    assertThat(editText.getSelectionStart()).isEqualTo(textToSet.length());
    assertThat(editText.getSelectionEnd()).isEqualTo(textToSet.length());
  }

  @Test
  public void textInput_setReplaceText_replacesTextWithSkippedSelection() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    final CharSequence initialText = "some text with suggestion";
    final CharSequence textToSet = "";
    final EditText editText = getEditText(testLithoView.getLithoView());
    TextInput.setText(lithoTestRule.context, handle, initialText);
    editText.setSelection(5);

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    TextInput.replaceText(lithoTestRule.context, handle, textToSet, 9, initialText.length(), true);
    assertThat(editText.getText().toString()).isEqualTo("some text");
    assertThat(editText.getSelectionStart()).isEqualTo(5);
    assertThat(editText.getSelectionEnd()).isEqualTo(5);
  }

  @Test
  public void textInput_setReplaceText_replacesTextAtGivenIndices() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    final EditText editText = getEditText(testLithoView.getLithoView());
    editText.setText("0123");
    final CharSequence textToSet = "set text in test";

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    TextInput.replaceText(lithoTestRule.context, handle, textToSet, 0, 2, false);
    assertThat(editText.getText().toString()).isEqualTo(textToSet + "23");
    assertThat(editText.getSelectionStart()).isEqualTo(textToSet.length());
    assertThat(editText.getSelectionEnd()).isEqualTo(textToSet.length());
  }

  @Test
  public void textInput_setReplaceTextForUnmountedView_replacesTextAfterMount() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    testLithoView.getLithoView().unmountAllItems();

    final CharSequence textToSet = "set text in test";

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    TextInput.replaceText(lithoTestRule.context, handle, textToSet, 0, 0, false);
    final CharSequence text = TextInput.getText(lithoTestRule.context, handle);
    assertThat(text).isNotNull();
    assertThat(text.toString()).isEqualTo(textToSet);

    // Mount the view again
    testLithoView.layout();

    assertThat(getEditText(testLithoView.getLithoView()).getText().toString()).isEqualTo(textToSet);
  }

  @Test
  public void textInput_setReplaceTextForUnmountedViewWithExistingText_replacesTextAfterMount() {
    final Handle handle = new Handle();
    TestLithoView testLithoView =
        lithoTestRule.render(
            componentScope ->
                Column.create(lithoTestRule.context)
                    .child(TextInput.create(lithoTestRule.context).handle(handle))
                    .build());

    final EditText editText = getEditText(testLithoView.getLithoView());
    editText.setText("0123");

    testLithoView.getLithoView().unmountAllItems();

    final CharSequence textToSet = "set text in test";

    // We need a context with a ComponentTree on it for the Handle to properly resolve
    TextInput.replaceText(lithoTestRule.context, handle, textToSet, 0, 2, false);
    final CharSequence text = TextInput.getText(lithoTestRule.context, handle);
    assertThat(text).isNotNull();
    assertThat(text.toString()).isEqualTo(textToSet + "23");

    // Mount the view again
    testLithoView.layout();

    assertThat(getEditText(testLithoView.getLithoView()).getText().toString())
        .isEqualTo(textToSet + "23");
  }
}
