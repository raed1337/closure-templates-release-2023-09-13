
/*
 * Copyright 2019 Google Inc.
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

package com.google.template.soy.plugin.java.internal;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.ibm.icu.util.ULocale;

/** A context for validating plugins. */
final class ValidatorContext implements JavaPluginContext {
  private final ValidatorErrorReporter reporter;

  ValidatorContext(ValidatorErrorReporter reporter) {
    this.reporter = reporter;
  }

  @Override
  public ValidatorValue getBidiDir() {
    return createValidatorValue(BidiGlobalDir.class);
  }

  @Override
  public ValidatorValue getULocale() {
    return createValidatorValue(ULocale.class);
  }

  @Override
  public ValidatorValue getAllRequiredCssNamespaces(JavaValue template) {
    return createValidatorValue(ImmutableList.class);
  }

  @Override
  public ValidatorValue getAllRequiredCssPaths(JavaValue template) {
    return createValidatorValue(ImmutableList.class);
  }

  private ValidatorValue createValidatorValue(Class<?> clazz) {
    return ValidatorValue.forClazz(clazz, reporter);
  }
}
