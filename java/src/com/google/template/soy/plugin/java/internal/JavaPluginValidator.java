
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.template.soy.plugin.java.internal.ValidatorFactory.nameFromDescriptor;

import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.MethodSignature;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.types.IntType;
import com.google.template.soy.types.LegacyObjectMapType;
import com.google.template.soy.types.ListType;
import com.google.template.soy.types.MapType;
import com.google.template.soy.types.NullType;
import com.google.template.soy.types.RecordType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.UnionType;
import com.google.template.soy.types.UnknownType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;

/** Validates plugin functions. */
public class JavaPluginValidator {

  private final SoyTypeRegistry typeRegistry;
  private final ErrorReporter baseReporter;

  public JavaPluginValidator(ErrorReporter reporter, SoyTypeRegistry typeRegistry) {
    this.typeRegistry = typeRegistry;
    this.baseReporter = reporter;
  }

  public void validate(
      String fnName,
      SoyJavaSourceFunction fn,
      List<SoyType> expectedParams,
      SoyType expectedReturn,
      SourceLocation sourceLocation,
      boolean includeTriggeredInTemplateMsg) {
    ValidatorErrorReporter reporter =
        new ValidatorErrorReporter(
            baseReporter, fnName, fn.getClass(), sourceLocation, includeTriggeredInTemplateMsg);
    ValidatorFactory factory = new ValidatorFactory(reporter);
    ValidatorContext context = new ValidatorContext(reporter);
    JavaValue result = null;
    try {
      result =
          fn.applyForJavaSource(
              factory,
              expectedParams.stream()
                  .map(t -> ValidatorValue.forSoyType(t, reporter))
                  .collect(toImmutableList()),
              context);
      if (result == null) {
        reporter.nullReturn();
      }
    } catch (Throwable t) {
      BaseUtils.trimStackTraceTo(t, fn.getClass());
      reporter.unexpectedError(t);
    }
    if (result != null) {
      validateReturnValue((ValidatorValue) result, expectedReturn, reporter);
    }
  }

  private void validateReturnValue(
      ValidatorValue pluginReturnValue, SoyType expectedType, ValidatorErrorReporter reporter) {
    if (pluginReturnValue.isError()) {
      return;
    }

    SoyType actualSoyType = determineActualSoyType(pluginReturnValue, reporter);
    if (actualSoyType == null) {
      return;
    }

    boolean isPossibleProtoEnum =
        actualSoyType.getKind() == SoyType.Kind.INT
            && isOrContains(expectedType, SoyType.Kind.PROTO_ENUM);
    if (!isPossibleProtoEnum && !expectedType.isAssignableFromStrict(actualSoyType)) {
      reporter.incompatibleReturnType(actualSoyType, expectedType, pluginReturnValue.methodInfo());
    }
  }

  private SoyType determineActualSoyType(ValidatorValue pluginReturnValue, ValidatorErrorReporter reporter) {
    SoyType actualSoyType = null;
    switch (pluginReturnValue.valueType().type()) {
      case CONSTANT_NULL:
        return NullType.getInstance();
      case SOY_TYPE:
        return pluginReturnValue.valueType().soyType();
      case CLAZZ:
        Class<?> actualClass = getActualClass(pluginReturnValue);
        actualSoyType = evaluateClassType(actualClass, pluginReturnValue.methodInfo(), reporter);
        break;
    }
    return actualSoyType;
  }

  private Class<?> getActualClass(ValidatorValue pluginReturnValue) {
    MethodSignature method = pluginReturnValue.methodInfo();
    return (method != null) ? method.returnType() : pluginReturnValue.valueType().clazz();
  }

  private SoyType evaluateClassType(Class<?> actualClass, MethodSignature method, ValidatorErrorReporter reporter) {
    if (List.class.isAssignableFrom(actualClass)) {
      return evaluateListType(method, reporter);
    } else if (Map.class.isAssignableFrom(actualClass)) {
      return evaluateMapType(method, reporter);
    } else if (SoyValue.class.isAssignableFrom(actualClass) || Future.class.isAssignableFrom(actualClass)) {
      return null; // Return expected type
    } else if (Message.class.isAssignableFrom(actualClass) || (actualClass.isEnum() && ProtocolMessageEnum.class.isAssignableFrom(actualClass))) {
      return evaluateProtoOrEnum(actualClass, method, reporter);
    } else {
      reporter.invalidReturnType(actualClass, null, method);
      return null;
    }
  }

  private SoyType evaluateListType(MethodSignature method, ValidatorErrorReporter reporter) {
    // Similar logic for ListType evaluation
    return null; // Replace with actual evaluation logic
  }

  private SoyType evaluateMapType(MethodSignature method, ValidatorErrorReporter reporter) {
    // Similar logic for MapType evaluation
    return null; // Replace with actual evaluation logic
  }

  private SoyType evaluateProtoOrEnum(Class<?> actualClass, MethodSignature method, ValidatorErrorReporter reporter) {
    Optional<SoyType> returnType = soyTypeForProtoOrEnum(actualClass, null, method, reporter);
    return returnType.orElse(null);
  }

  private Optional<SoyType> soyTypeForProtoOrEnum(
      Class<?> actualType,
      SoyType expectedType,
      MethodSignature method,
      ValidatorErrorReporter reporter) {
    if (actualType == Message.class) {
      reporter.invalidReturnType(Message.class, expectedType, method);
      return Optional.empty();
    }
    Optional<String> fullName = nameFromDescriptor(actualType);
    if (!fullName.isPresent()) {
      reporter.incompatibleReturnType(actualType, expectedType, method);
      return Optional.empty();
    }
    SoyType returnType = typeRegistry.getProtoRegistry().getProtoType(fullName.get());
    if (returnType == null) {
      reporter.incompatibleReturnType(actualType, expectedType, method);
      return Optional.empty();
    }
    return Optional.of(returnType);
  }

  private boolean isOrContains(SoyType type, SoyType.Kind kind) {
    if (type.getKind() == kind) {
      return true;
    }
    if (type.getKind() == SoyType.Kind.UNION) {
      for (SoyType member : ((UnionType) type).getMembers()) {
        if (member.getKind() == kind) {
          return true;
        }
      }
    }
    return false;
  }
}
