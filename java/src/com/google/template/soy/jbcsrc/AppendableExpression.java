
/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.jbcsrc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.LOGGING_ADVISING_APPENDABLE_TYPE;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.LOGGING_ADVISING_BUILDER_TYPE;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.constant;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.data.LogStatement;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.LoggingFunctionInvocation;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.CodeBuilder;
import com.google.template.soy.jbcsrc.restricted.Expression;
import com.google.template.soy.jbcsrc.restricted.MethodRef;
import com.google.template.soy.jbcsrc.restricted.SoyExpression;
import com.google.template.soy.jbcsrc.restricted.Statement;
import java.util.List;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

/**
 * An expression for an {@link
 * com.google.template.soy.jbcsrc.api.AdvisingAppendable.AdvisingAppendable}.
 */
final class AppendableExpression extends Expression {
  private static final MethodRef APPEND =
      MethodRef.create(LoggingAdvisingAppendable.class, "append", CharSequence.class);

  private static final MethodRef APPEND_CHAR =
      MethodRef.create(LoggingAdvisingAppendable.class, "append", char.class);

  private static final MethodRef SOFT_LIMITED =
      MethodRef.create(LoggingAdvisingAppendable.class, "softLimitReached").asCheap();

  static final MethodRef ENTER_LOGGABLE_STATEMENT =
      MethodRef.create(LoggingAdvisingAppendable.class, "enterLoggableElement", LogStatement.class);

  private static final MethodRef EXIT_LOGGABLE_STATEMENT =
      MethodRef.create(LoggingAdvisingAppendable.class, "exitLoggableElement");

  private static final MethodRef APPEND_LOGGING_FUNCTION_INVOCATION =
      MethodRef.create(
          LoggingAdvisingAppendable.class,
          "appendLoggingFunctionInvocation",
          LoggingFunctionInvocation.class,
          ImmutableList.class);

  private static final MethodRef LOGGING_FUNCTION_INVOCATION_CREATE =
      MethodRef.create(
          LoggingFunctionInvocation.class, "create", String.class, String.class, List.class);

  private static final MethodRef SET_SANITIZED_CONTENT_KIND_AND_DIRECTIONALITY =
      MethodRef.create(
              LoggingAdvisingAppendable.class, "setKindAndDirectionality", ContentKind.class)
          .asCheap();

  private static final MethodRef FLUSH_BUFFERS =
      MethodRef.create(LoggingAdvisingAppendable.class, "flushBuffers", int.class);

  static AppendableExpression forExpression(Expression delegate) {
    return new AppendableExpression(delegate, false, true);
  }

  static AppendableExpression forStringBuilder(Expression delegate) {
    checkArgument(delegate.resultType().equals(LOGGING_ADVISING_BUILDER_TYPE));
    return new AppendableExpression(
        LOGGING_ADVISING_BUILDER_TYPE, delegate, false, false);
  }

  static AppendableExpression logger() {
    return new AppendableExpression(MethodRef.RUNTIME_LOGGER.invoke(), false, false);
  }

  private final Expression delegate;
  private final boolean hasSideEffects;
  private final boolean supportsSoftLimiting;

  private AppendableExpression(Expression delegate, boolean hasSideEffects, boolean supportsSoftLimiting) {
    this(LOGGING_ADVISING_APPENDABLE_TYPE, delegate, hasSideEffects, supportsSoftLimiting);
  }

  private AppendableExpression(Type resultType, Expression delegate, boolean hasSideEffects, boolean supportsSoftLimiting) {
    super(resultType, delegate.features());
    validateDelegate(delegate);
    this.delegate = delegate;
    this.hasSideEffects = hasSideEffects;
    this.supportsSoftLimiting = supportsSoftLimiting;
  }

  private void validateDelegate(Expression delegate) {
    delegate.checkAssignableTo(LOGGING_ADVISING_APPENDABLE_TYPE);
    checkArgument(delegate.isNonJavaNullable(),
        "advising appendable expressions should always be non nullable: %s",
        delegate);
  }

  @Override
  protected void doGen(CodeBuilder adapter) {
    delegate.gen(adapter);
  }

  AppendableExpression appendString(Expression exp) {
    return createNewDelegate(delegate.invoke(APPEND, exp), true);
  }

  AppendableExpression appendChar(Expression exp) {
    return createNewDelegate(delegate.invoke(APPEND_CHAR, exp), true);
  }

  Expression softLimitReached() {
    checkArgument(supportsSoftLimiting);
    return delegate.invoke(SOFT_LIMITED);
  }

  AppendableExpression enterLoggableElement(Expression logStatement) {
    return createNewDelegate(delegate.invoke(ENTER_LOGGABLE_STATEMENT, logStatement), true);
  }

  AppendableExpression exitLoggableElement() {
    return createNewDelegate(delegate.invoke(EXIT_LOGGABLE_STATEMENT), true);
  }

  AppendableExpression appendLoggingFunctionInvocation(
      String functionName,
      String placeholderValue,
      List<SoyExpression> args,
      List<Expression> escapingDirectives) {
    return createNewDelegate(
        delegate.invoke(
            APPEND_LOGGING_FUNCTION_INVOCATION,
            LOGGING_FUNCTION_INVOCATION_CREATE.invoke(
                constant(functionName),
                constant(placeholderValue),
                SoyExpression.asBoxedListWithJavaNullItems(args)),
            BytecodeUtils.asImmutableList(escapingDirectives)),
        true);
  }

  AppendableExpression setSanitizedContentKindAndDirectionality(SanitizedContentKind kind) {
    return createNewDelegate(
        delegate.invoke(
            SET_SANITIZED_CONTENT_KIND_AND_DIRECTIONALITY,
            BytecodeUtils.constantSanitizedContentKindAsContentKind(kind)),
        true);
  }

  Statement flushBuffers(int depth) {
    return delegate.invokeVoid(FLUSH_BUFFERS, constant(depth));
  }

  @Override
  public AppendableExpression labelStart(Label label) {
    return createNewDelegate(delegate.labelStart(label), hasSideEffects);
  }

  @Override
  public Statement toStatement() {
    return hasSideEffects ? super.toStatement() : Statement.NULL_STATEMENT;
  }

  private AppendableExpression createNewDelegate(Expression newDelegate, boolean hasSideEffects) {
    return new AppendableExpression(newDelegate, hasSideEffects, supportsSoftLimiting);
  }

  boolean supportsSoftLimiting() {
    return supportsSoftLimiting;
  }
}
