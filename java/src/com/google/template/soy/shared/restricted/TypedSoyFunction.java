
/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.shared.restricted;

import com.google.common.collect.ImmutableSortedSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A soy function that carries type information.
 *
 * @deprecated New functions should use {@code SoyJavaSourceFunction}, {@code
 *     SoyJavaScriptSourceFunction} and {@code SoyPythonSourceFunction} instead of this.
 */
@Deprecated
public abstract class TypedSoyFunction implements SoyFunction {

  @Override
  public final String getName() {
    String name = getSignature().name();
    validateName(name);
    return name;
  }

  private void validateName(String name) {
    if (name.isEmpty()) {
      throw new AbstractMethodError(
          getClass() + "should either override getName() or specify a name in the signature.");
    }
  }

  @Override
  public final Set<Integer> getValidArgsSizes() {
    Map<Integer, Signature> validArgs = new HashMap<>();
    collectValidArgs(validArgs);
    return ImmutableSortedSet.copyOf(validArgs.keySet());
  }

  private void collectValidArgs(Map<Integer, Signature> validArgs) {
    for (Signature signature : getSignature().value()) {
      int argSize = signature.parameterTypes().length;
      checkDuplicateSignatures(validArgs, argSize, signature);
      validArgs.put(argSize, signature);
    }
  }

  private void checkDuplicateSignatures(Map<Integer, Signature> validArgs, int argSize, Signature signature) {
    if (validArgs.containsKey(argSize)) {
      throw new IllegalArgumentException(
          String.format(
              "TypedSoyFunction can only have exactly one signature for a given"
                  + " number of parameters. Found more than one signatures that specify "
                  + "%s parameters:\n  %s\n  %s",
              argSize, validArgs.get(argSize), signature));
    }
  }

  private SoyFunctionSignature getSignature() {
    SoyFunctionSignature signature = getClass().getAnnotation(SoyFunctionSignature.class);
    if (signature == null) {
      throw new IllegalStateException(
          "TypedSoyFunction must set @SoyFunctionSignature annotation.");
    }
    return signature;
  }
}
