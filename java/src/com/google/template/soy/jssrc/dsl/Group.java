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

import static com.google.template.soy.jssrc.dsl.Precedence.Associativity.UNARY;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.jssrc.dsl.Precedence.Associativity;
import com.google.template.soy.jssrc.restricted.JsExpr;
import java.util.stream.Stream;

/**
 * Represents a JavaScript grouping ({@code (...)}).
 *
 * <p>The CodeChunk DSL parenthesizes subexpressions only where required for correctness. This class
 * should therefore be rarely needed. The intended use case is for defensively parenthesizing a
 * {@link JsExpr} that may have come from a plugin, and whose precedence may be untrustworthy.
 */
@AutoValue
@Immutable
abstract class Group extends Operation {
  abstract Expression underlying();

  static Group create(Expression underlying) {
    return new AutoValue_Group(underlying);
  }

  @Override
  public Precedence precedence() {
    return Precedence.P18;
  }

  @Override
  public Associativity associativity() {
    return UNARY;
  }

  @Override
  Stream<? extends CodeChunk> childrenStream() {
    return Stream.of(underlying());
  }

  @Override
  void doFormatOutputExpr(FormattingContext ctx) {
    ctx.append('(').appendOutputExpression(underlying()).append(')');
  }
}
