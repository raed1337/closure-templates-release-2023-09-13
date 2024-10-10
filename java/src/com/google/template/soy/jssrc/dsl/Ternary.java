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

import static com.google.template.soy.exprtree.Operator.CONDITIONAL;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.jssrc.dsl.Precedence.Associativity;
import java.util.stream.Stream;

/**
 * Represents a ternary expression. Its consequent and alternate chunks are required to be
 * representable as single expressions, though its predicate can be more complex.
 */
@AutoValue
@Immutable
abstract class Ternary extends Operation {
  abstract Expression predicate();

  abstract Expression consequent();

  abstract Expression alternate();

  static Ternary create(Expression predicate, Expression consequent, Expression alternate) {
    Preconditions.checkArgument(predicate.hasEquivalentInitialStatements(consequent));
    Preconditions.checkArgument(predicate.hasEquivalentInitialStatements(alternate));
    return new AutoValue_Ternary(predicate, consequent, alternate);
  }

  @Override
  public Precedence precedence() {
    return Precedence.forSoyOperator(CONDITIONAL);
  }

  @Override
  public Associativity associativity() {
    return Precedence.getAssociativity(CONDITIONAL);
  }

  @Override
  Stream<? extends CodeChunk> childrenStream() {
    return Stream.of(predicate(), consequent(), alternate());
  }

  @Override
  void doFormatOutputExpr(FormattingContext ctx) {
    formatOperand(predicate(), OperandPosition.LEFT, ctx);
    ctx.append(" ? ");
    formatOperand(consequent(), OperandPosition.LEFT, ctx);
    ctx.append(" : ");
    formatOperand(alternate(), OperandPosition.RIGHT, ctx);
  }

  @Override
  boolean initialExpressionIsObjectLiteral() {
    return predicate().initialExpressionIsObjectLiteral();
  }
}
