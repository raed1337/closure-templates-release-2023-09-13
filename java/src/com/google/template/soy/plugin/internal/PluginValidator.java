
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

package com.google.template.soy.plugin.internal;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.ErrorReporter.Checkpoint;
import com.google.template.soy.plugin.java.internal.JavaPluginValidator;
import com.google.template.soy.plugin.java.internal.MethodSignatureValidator;
import com.google.template.soy.plugin.java.internal.ValidatorErrorReporter;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.soyparse.SoyFileParser;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.UnknownType;
import com.google.template.soy.types.ast.TypeNode;
import com.google.template.soy.types.ast.TypeNodeConverter;
import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Validates all source functions. */
public final class PluginValidator {

  private final SoyTypeRegistry typeRegistry;
  private final ErrorReporter errorReporter;
  private final JavaPluginValidator javaValidator;
  private final MethodSignatureValidator methodSignatureValidator;

  public PluginValidator(
      ErrorReporter errorReporter, SoyTypeRegistry typeRegistry, List<File> pluginRuntimeJars) {
    this.typeRegistry = typeRegistry;
    this.errorReporter = errorReporter;
    this.javaValidator = new JavaPluginValidator(errorReporter, typeRegistry);
    this.methodSignatureValidator = new MethodSignatureValidator(pluginRuntimeJars, errorReporter);
  }

  public void validate(Iterable<SoySourceFunction> fns) {
    for (SoySourceFunction fn : fns) {
      if (fn instanceof SoyJavaSourceFunction) {
        validateJavaFunction((SoyJavaSourceFunction) fn);
      }
    }
  }

  private void validateJavaFunction(SoyJavaSourceFunction fn) {
    SoyFunctionSignature fnSig = fn.getClass().getAnnotation(SoyFunctionSignature.class);
    String fnName = fnSig.name();
    SourceLocation location = new SourceLocation(SourceFilePath.create(fn.getClass().getName()));
    methodSignatureValidator.validate(fnName, fn, location, false);
    ValidatorErrorReporter validatorReporter = createValidatorErrorReporter(fnName, fn, location);
    
    for (Signature sig : fnSig.value()) {
      validateSignature(fn, validatorReporter, fnName, sig, location);
    }
  }

  private ValidatorErrorReporter createValidatorErrorReporter(String fnName, SoyJavaSourceFunction fn, SourceLocation location) {
    return new ValidatorErrorReporter(
        errorReporter,
        fnName,
        fn.getClass(),
        location,
        false);
  }

  private void validateSignature(SoyJavaSourceFunction fn, ValidatorErrorReporter validatorReporter, String fnName, Signature sig, SourceLocation location) {
    Checkpoint checkpoint = errorReporter.checkpoint();
    List<SoyType> paramTypes = getParameterTypes(sig, fn, validatorReporter);
    SoyType returnType = typeFor(sig.returnType(), fn, validatorReporter);

    if (!errorReporter.errorsSince(checkpoint)) {
      javaValidator.validate(fnName, fn, paramTypes, returnType, location, false);
    }
  }

  private List<SoyType> getParameterTypes(Signature sig, SoyJavaSourceFunction fn, ValidatorErrorReporter validatorReporter) {
    return Arrays.stream(sig.parameterTypes())
        .map(p -> typeFor(p, fn, validatorReporter))
        .collect(toImmutableList());
  }

  private SoyType typeFor(
      String typeStr, SoyJavaSourceFunction fn, ValidatorErrorReporter validatorReporter) {
    ErrorReporter localReporter = ErrorReporter.create(ImmutableMap.of());
    SoyType type = parseType(typeStr, fn, localReporter, typeRegistry);
    validatorReporter.wrapErrors(localReporter.getErrors());
    validatorReporter.wrapWarnings(localReporter.getWarnings());

    return type;
  }

  /** Shared utility function for parsing a SoyType from a string in a @SoyFunctionSignature. */
  public static SoyType parseType(
      String typeStr,
      SoySourceFunction plugin,
      ErrorReporter errorReporter,
      SoyTypeRegistry typeRegistry) {
    TypeNode typeNode =
        SoyFileParser.parseType(
            typeStr, SourceFilePath.create(plugin.getClass().getName()), errorReporter);
    return typeNode == null
        ? UnknownType.getInstance()
        : TypeNodeConverter.builder(errorReporter)
            .setTypeRegistry(typeRegistry)
            .setSystemExternal(true)
            .build()
            .getOrCreateType(typeNode);
  }
}
