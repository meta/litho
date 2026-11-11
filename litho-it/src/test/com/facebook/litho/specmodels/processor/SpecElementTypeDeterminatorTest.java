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

package com.facebook.litho.specmodels.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.facebook.litho.specmodels.model.SpecElementType;
import com.google.testing.compile.CompilationRule;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.jetbrains.annotations.NotNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link SpecElementTypeDeterminator} */
@RunWith(JUnit4.class)
public class SpecElementTypeDeterminatorTest {
  @Rule public CompilationRule mCompilationRule = new CompilationRule();

  /**
   * We can't test with a real kotlin object here, so we'll replicate the structure that we test
   * for.
   */
  public static class FakeKotlinSingleton {
    @NotNull public static final FakeKotlinSingleton INSTANCE = null;
  }

  @Test
  public void testNormalClass() {
    final Elements elements = mCompilationRule.getElements();
    final TypeElement typeElement = elements.getTypeElement(String.class.getName());

    assertThat(SpecElementTypeDeterminator.determine(typeElement))
        .isEqualTo(SpecElementType.JAVA_CLASS);
  }

  @Test
  public void testKotlinSingleton() {
    final Elements elements = mCompilationRule.getElements();
    final TypeElement typeElement =
        elements.getTypeElement(
            SpecElementTypeDeterminatorTest.FakeKotlinSingleton.class.getCanonicalName());

    assertThat(SpecElementTypeDeterminator.determine(typeElement))
        .isEqualTo(SpecElementType.KOTLIN_SINGLETON);
  }
}
