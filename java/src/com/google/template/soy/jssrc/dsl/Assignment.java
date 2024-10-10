/*
 * Copyright 2016 Google Inc.
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
import com.google.errorprone.annotations.Immutable;
import java.util.Objects;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Represents an assignment to a variable. */
@AutoValue
@Immutable
abstract class Assignment extends Statement {
  abstract Expression lhs();

  abstract Expression rhs();

  @Nullable
  abstract JsDoc jsDoc();

  static Assignment create(Expression lhs, Expression rhs, JsDoc jsDoc) {
    return new AutoValue_Assignment(lhs, rhs, jsDoc);
  }

  @Override
  Stream<? extends CodeChunk> childrenStream() {
    return Stream.of(lhs(), rhs(), jsDoc()).filter(Objects::nonNull);
  }

  @Override
  void doFormatStatement(FormattingContext ctx) {
    ctx.appendInitialStatements(lhs()).appendInitialStatements(rhs());

    if (jsDoc() != null) {
      ctx.appendAll(jsDoc()).endLine();
    }

    ctx.appendOutputExpression(lhs())
        .noBreak()
        .append(" = ")
        .appendOutputExpression(rhs())
        .noBreak()
        .append(";")
        .endLine();
  }
}
