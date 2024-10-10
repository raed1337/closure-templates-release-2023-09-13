/*
 * Copyright 2022 Google Inc.
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
package com.google.template.soy.jssrc.dsl;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.Immutable;

/** Represents a "/*... * /" range comment. */
@AutoValue
@Immutable
public abstract class RangeComment extends SpecialToken {

  abstract String comment();

  abstract boolean inline();

  public static RangeComment create(String comment, boolean inline) {
    if (comment.length() >= 4 && comment.startsWith("/*") && comment.endsWith("*/")) {
      // pre-formatted.
    } else {
      Preconditions.checkArgument(!comment.contains("*/"));
      comment =
          "/*"
              + (comment.startsWith("\n") ? "" : " ")
              + comment
              + (comment.endsWith("\n") ? "" : " ")
              + "*/";
    }
    return new AutoValue_RangeComment(comment, inline);
  }

  @Override
  void doFormatToken(FormattingContext ctx) {
    ctx.append(comment());
    if (!inline()) {
      ctx.endLine();
    }
  }
}
