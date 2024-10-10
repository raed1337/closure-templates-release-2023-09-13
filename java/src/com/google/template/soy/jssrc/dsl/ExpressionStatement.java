/*
 * Copyright 2018 Google Inc.
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
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Evaluates an expression as a statement. */
@AutoValue
@Immutable
public abstract class ExpressionStatement extends Statement {

  public static ExpressionStatement of(Expression expression) {
    return of(expression, /* jsDoc= */ null);
  }

  static ExpressionStatement of(Expression expression, JsDoc jsDoc) {
    return new AutoValue_ExpressionStatement(expression, jsDoc);
  }

  public abstract Expression expr();

  @Override
  public Expression asExpr() {
    return expr();
  }

  @Nullable
  abstract JsDoc jsDoc();

  @Override
  void doFormatStatement(FormattingContext ctx) {
    ctx.appendInitialStatements(expr());
    if (jsDoc() != null) {
      ctx.appendAll(jsDoc()).endLine();
    }
    ctx.appendOutputExpression(expr());
    ctx.noBreak().append(";");
    ctx.endLine();
  }

  @Override
  Stream<? extends CodeChunk> childrenStream() {
    return Stream.of(expr());
  }
}
