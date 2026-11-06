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

package com.facebook.litho;

import com.facebook.infer.annotation.Nullsafe;
import com.facebook.litho.testing.subcomponents.InspectableComponent;
import org.assertj.core.api.Condition;
import org.assertj.core.description.TextDescription;

@Nullsafe(Nullsafe.Mode.LOCAL)
public final class BaseMatcherBuilder {
  private BaseMatcherBuilder() {}

  @SuppressWarnings("rawtypes")
  public static Condition<InspectableComponent> buildCommonMatcher(final BaseMatcher matcher) {
    return new Condition<InspectableComponent>() {
      @Override
      public boolean matches(InspectableComponent component) {
        final Component underlyingComponent = component.getComponent();
        final CommonProps commonProps =
            (underlyingComponent instanceof SpecGeneratedComponent)
                ? ((SpecGeneratedComponent) underlyingComponent).getCommonProps()
                : null;
        if (matcher.mClickHandlerMatcher != null
            && commonProps != null
            // NULLSAFE_FIXME[Parameter Not Nullable]
            && !matcher.mClickHandlerMatcher.matches(commonProps.getClickHandler())) {
          as(
              new TextDescription(
                  "Click handler <%s> (doesn't match <%s>)",
                  matcher.mClickHandlerMatcher, commonProps.getClickHandler()));
          return false;
        }

        if (matcher.mLongClickHandlerMatcher != null
            && commonProps != null
            // NULLSAFE_FIXME[Parameter Not Nullable]
            && !matcher.mLongClickHandlerMatcher.matches(commonProps.getLongClickHandler())) {
          as(
              new TextDescription(
                  "LongClick handler <%s> (doesn't match <%s>)",
                  matcher.mLongClickHandlerMatcher, commonProps.getLongClickHandler()));
          return false;
        }

        if (matcher.mFocusChangeHandlerMatcher != null
            && commonProps != null
            // NULLSAFE_FIXME[Parameter Not Nullable]
            && !matcher.mFocusChangeHandlerMatcher.matches(commonProps.getFocusChangeHandler())) {
          as(
              new TextDescription(
                  "FocusChange handler <%s> (doesn't match <%s>)",
                  matcher.mFocusChangeHandlerMatcher, commonProps.getFocusChangeHandler()));
          return false;
        }

        if (matcher.mTouchEventHandlerMatcher != null
            && commonProps != null
            // NULLSAFE_FIXME[Parameter Not Nullable]
            && !matcher.mTouchEventHandlerMatcher.matches(commonProps.getTouchHandler())) {
          as(
              new TextDescription(
                  "TouchEvent handler <%s> (doesn't match <%s>)",
                  matcher.mTouchEventHandlerMatcher, commonProps.getTouchHandler()));
          return false;
        }

        if (matcher.mInterceptTouchHandlerMatcher != null
            && commonProps != null
            && !matcher.mInterceptTouchHandlerMatcher.matches(
                // NULLSAFE_FIXME[Parameter Not Nullable]
                commonProps.getInterceptTouchHandler())) {
          as(
              new TextDescription(
                  "InterceptTouch handler <%s> (doesn't match <%s>)",
                  matcher.mInterceptTouchHandlerMatcher, commonProps.getInterceptTouchHandler()));
          return false;
        }

        if (matcher.mFocusable != null
            && commonProps != null
            && !matcher.mFocusable.matches(commonProps.getFocusable())) {
          as(
              new TextDescription(
                  "Focusable <%s> (doesn't match <%s>)",
                  matcher.mFocusable, commonProps.getFocusable()));
          return false;
        }

        if (matcher.mTransitionKey != null
            && commonProps != null
            // NULLSAFE_FIXME[Parameter Not Nullable]
            && !matcher.mTransitionKey.matches(commonProps.getTransitionKey())) {
          as(
              new TextDescription(
                  "Transition key <%s> (doesn't match <\"%s\">)",
                  matcher.mTransitionKey, commonProps.getTransitionKey()));
          return false;
        }

        return true;
      }
    };
  }
}
