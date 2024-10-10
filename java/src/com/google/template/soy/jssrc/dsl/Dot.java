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

import static com.google.template.soy.jssrc.dsl.Precedence.Associativity.LEFT;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.jssrc.dsl.Precedence.Associativity;
import java.util.stream.Stream;

/** Represents a JavaScript member access ({@code .}) expression. */
@AutoValue
@Immutable
abstract class Dot extends Operation {

  abstract Expression receiver();

  abstract Expression key();

  abstract boolean nullSafe();

  static Dot create(Expression receiver, Expression key) {
    return new AutoValue_Dot(receiver, key, false);
  }

  static Dot createNullSafe(Expression receiver, Expression key) {
    return new AutoValue_Dot(receiver, key, true);
  }

  @Override
  public Precedence precedence() {
    return Precedence.P17;
  }

  @Override
  public Associativity associativity() {
    return LEFT;
  }

  @Override
  Stream<? extends CodeChunk> childrenStream() {
    return Stream.of(receiver(), key());
  }

  @Override
  void doFormatOutputExpr(FormattingContext ctx) {
    formatOperand(receiver(), OperandPosition.LEFT, ctx);
    ctx.noBreak().append(nullSafe() ? "?." : ".");
    formatOperand(key(), OperandPosition.RIGHT, ctx);
  }

  @Override
  boolean initialExpressionIsObjectLiteral() {
    return receiver().initialExpressionIsObjectLiteral();
  }
}
