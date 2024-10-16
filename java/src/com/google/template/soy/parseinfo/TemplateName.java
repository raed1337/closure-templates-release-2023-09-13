
/*
 * Copyright 2021 Google Inc.
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

package com.google.template.soy.parseinfo;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.DoNotMock;
import com.google.errorprone.annotations.Immutable;

/** Opaque string representation of the name of a Soy template. */
@DoNotMock
@AutoValue
@Immutable
public abstract class TemplateName {

  public static TemplateName of(String name) {
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalArgumentException("Template name must not be null or empty.");
    }
    return new AutoValue_TemplateName(name);
  }

  public abstract String name();
}
