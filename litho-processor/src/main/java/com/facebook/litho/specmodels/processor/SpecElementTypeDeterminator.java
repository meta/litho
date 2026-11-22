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

import com.facebook.litho.specmodels.internal.ImmutableList;
import com.facebook.litho.specmodels.model.SpecElementType;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

public class SpecElementTypeDeterminator {
  static boolean isKotlinSingleton(TypeElement element) {
    final String className = element.getQualifiedName().toString();
    return element.getKind() == ElementKind.CLASS
        && element.getEnclosedElements().stream()
            .anyMatch(
                e ->
                    isPublicStaticFinalElement(e)
                        && isElementWithTypeName(e, className)
                        && e.getSimpleName().contentEquals("INSTANCE"));
  }

  static boolean isKotlinClass(TypeElement element) {
    final String companionClassName = element.getQualifiedName().toString() + ".Companion";
    return element.getKind() == ElementKind.CLASS
        /* should contain a companion static field instance */
        && element.getEnclosedElements().stream()
            .anyMatch(
                e ->
                    isPublicStaticFinalElement(e)
                        && isElementWithTypeName(e, companionClassName)
                        && e.getSimpleName().contentEquals("Companion"))
        /* should contain a Companion class declaration. */
        && element.getEnclosedElements().stream()
            .anyMatch(
                e ->
                    isPublicStaticFinalElement(e)
                        && e.getKind() == ElementKind.CLASS
                        && e.getSimpleName().contentEquals("Companion"));
  }

  public static SpecElementType determine(TypeElement element) {
    if (isKotlinSingleton(element)) {
      return SpecElementType.KOTLIN_SINGLETON;
    }

    if (isKotlinClass(element)) {
      return SpecElementType.KOTLIN_CLASS;
    }

    return SpecElementType.JAVA_CLASS;
  }

  static boolean isPublicStaticFinalElement(Element e) {
    return e.getModifiers()
        .containsAll(ImmutableList.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL));
  }

  static boolean isElementWithTypeName(Element e, String name) {
    TypeMirror type = e.asType();
    return type.getKind() == TypeKind.DECLARED
        && ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().contentEquals(name);
  }
}
