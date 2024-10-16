
/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0
 *
 * (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.google.template.soy.shared.restricted;

import com.google.template.soy.plugin.restricted.SoySourceFunction;

/**
 * Utility methods related to Soy functions and their two class hierarchies, {@link SoyFunction} and
 * {@link SoySourceFunction}.
 */
public final class SoyFunctions {

  private SoyFunctions() {}

  /**
   * Returns whether `function` is a pure function.
   *
   * @throws ClassCastException if `function` is not a {@link SoyFunction} or {@link
   *     SoySourceFunction}.
   */
  public static boolean isPure(Object function) {
    if (function instanceof SoyFunction) {
      return isSoyFunctionPure((SoyFunction) function);
    } else if (function instanceof SoySourceFunction) {
      return isSoySourceFunctionPure((SoySourceFunction) function);
    } else {
      throw new ClassCastException(function.getClass().getName());
    }
  }

  private static boolean isSoyFunctionPure(SoyFunction function) {
    return function.isPure();
  }

  private static boolean isSoySourceFunctionPure(SoySourceFunction function) {
    return function.getClass().isAnnotationPresent(SoyPureFunction.class);
  }
}
