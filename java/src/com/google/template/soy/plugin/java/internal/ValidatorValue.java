
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

import com.google.auto.value.AutoOneOf;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.MethodSignature;
import com.google.template.soy.types.BoolType;
import com.google.template.soy.types.FloatType;
import com.google.template.soy.types.IntType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.StringType;
import javax.annotation.Nullable;

/** A JavaValue for validating plugins. */
final class ValidatorValue implements JavaValue {
  private final boolean error;
  private final ValueType valueType;
  private final ValidatorErrorReporter reporter;
  private final MethodSignature methodSignature;

  static ValidatorValue forConstantNull(ValidatorErrorReporter reporter) {
    return new ValidatorValue(
        AutoOneOf_ValidatorValue_ValueType.constantNull(true),
        /* error= */ false,
        /* method= */ null,
        reporter);
  }

  static ValidatorValue forSoyType(SoyType type, ValidatorErrorReporter reporter) {
    return new ValidatorValue(
        AutoOneOf_ValidatorValue_ValueType.soyType(type),
        /* error= */ false,
        /* method= */ null,
        reporter);
  }

  static ValidatorValue forClazz(Class<?> clazz, ValidatorErrorReporter reporter) {
    return new ValidatorValue(
        AutoOneOf_ValidatorValue_ValueType.clazz(clazz),
        /* error= */ false,
        /* method= */ null,
        reporter);
  }

  static ValidatorValue forError(Class<?> clazz, ValidatorErrorReporter reporter) {
    return new ValidatorValue(
        AutoOneOf_ValidatorValue_ValueType.clazz(clazz),
        /* error= */ true,
        /* method= */ null,
        reporter);
  }

  static ValidatorValue forError(SoyType type, ValidatorErrorReporter reporter) {
    return new ValidatorValue(
        AutoOneOf_ValidatorValue_ValueType.soyType(type),
        /* error= */ true,
        /* method= */ null,
        reporter);
  }

  static ValidatorValue forMethodReturnType(
      MethodSignature method, ValidatorErrorReporter reporter) {
    SoyType type = getSoyTypeFromReturnType(method.returnType());
    if (type != null) {
      return new ValidatorValue(
          AutoOneOf_ValidatorValue_ValueType.soyType(type), /* error= */ false, method, reporter);
    }
    return new ValidatorValue(
        AutoOneOf_ValidatorValue_ValueType.clazz(method.returnType()),
        /* error= */ false,
        method,
        reporter);
  }

  private static SoyType getSoyTypeFromReturnType(Class<?> returnType) {
    if (returnType == boolean.class) {
      return BoolType.getInstance();
    }
    if (returnType == int.class || returnType == long.class) {
      return IntType.getInstance();
    }
    if (returnType == double.class) {
      return FloatType.getInstance();
    }
    if (returnType == String.class) {
      return StringType.getInstance();
    }
    return null;
  }

  private ValidatorValue(
      ValueType valueType, boolean error, MethodSignature method, ValidatorErrorReporter reporter) {
    this.valueType = valueType;
    this.reporter = reporter;
    this.error = error;
    this.methodSignature = method;
  }

  @Override
  public ValidatorValue isNonNull() {
    return forSoyType(BoolType.getInstance(), reporter);
  }

  @Override
  public ValidatorValue isNull() {
    return forSoyType(BoolType.getInstance(), reporter);
  }

  @Override
  public ValidatorValue asSoyBoolean() {
    return convertToSoyType(BoolType.getInstance(), "asSoyBoolean");
  }

  @Override
  public ValidatorValue asSoyString() {
    return convertToSoyType(StringType.getInstance(), "asSoyString");
  }

  @Override
  public ValidatorValue asSoyInt() {
    return convertToSoyType(IntType.getInstance(), "asSoyInt");
  }

  @Override
  public ValidatorValue asSoyFloat() {
    return convertToSoyType(FloatType.getInstance(), "asSoyFloat");
  }

  private ValidatorValue convertToSoyType(SoyType newType, String methodName) {
    if (!isSoyType()) {
      reportNonConvertible(newType, methodName);
      return forError(newType, reporter);
    }
    if (!valueType.soyType().isAssignableFromStrict(newType)) {
      reporter.incompatibleSoyType(valueType.soyType(), newType, methodName);
      return forError(newType, reporter);
    }
    return forSoyType(newType, reporter);
  }

  private boolean isSoyType() {
    return valueType.type() == ValueType.Type.SOY_TYPE;
  }

  private void reportNonConvertible(SoyType newType, String methodName) {
    reporter.nonSoyExpressionNotConvertible(
        isConstantNull() ? Object.class : valueType.clazz(), newType, methodName);
  }

  @Override
  public ValidatorValue coerceToSoyBoolean() {
    return coerceToType(BoolType.getInstance(), "coerceToSoyBoolean");
  }

  @Override
  public ValidatorValue coerceToSoyString() {
    return coerceToType(StringType.getInstance(), "coerceToSoyString");
  }

  private ValidatorValue coerceToType(SoyType newType, String methodName) {
    if (!isSoyType()) {
      reporter.nonSoyExpressionNotCoercible(
          isConstantNull() ? Object.class : valueType.clazz(), newType, methodName);
      return forError(newType, reporter);
    }
    return forSoyType(newType, reporter);
  }

  @Nullable
  MethodSignature methodInfo() {
    return methodSignature;
  }

  boolean isError() {
    return error;
  }

  ValueType valueType() {
    return valueType;
  }

  boolean hasSoyType() {
    return isSoyType();
  }

  boolean isConstantNull() {
    return valueType.type() == ValueType.Type.CONSTANT_NULL;
  }

  boolean hasClazz() {
    return valueType.type() == ValueType.Type.CLAZZ;
  }

  @AutoOneOf(ValueType.Type.class)
  abstract static class ValueType {
    enum Type {
      CONSTANT_NULL,
      SOY_TYPE,
      CLAZZ
    }

    abstract ValueType.Type type();

    abstract boolean constantNull();

    abstract SoyType soyType();

    abstract Class<?> clazz();
  }
}
