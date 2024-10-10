
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

package com.google.template.soy.plugin.python.restricted;

/** A factory for instructing soy how to implement a {@link SoyPythonSourceFunction}. */
public abstract class PythonValueFactory {
  
  /** Creates a constant value of a given type. */
  protected PythonValue createConstant(Object value) {
    if (value instanceof Long) {
      return constant((Long) value);
    } else if (value instanceof Double) {
      return constant((Double) value);
    } else if (value instanceof String) {
      return constant((String) value);
    } else if (value instanceof Boolean) {
      return constant((Boolean) value);
    } else if (value == null) {
      return constantNull();
    }
    throw new IllegalArgumentException("Unsupported type: " + value.getClass());
  }

  /** Creates an integer constant. */
  public abstract PythonValue constant(long num);

  /** Creates a floating point constant. */
  public abstract PythonValue constant(double num);

  /** Creates a String constant. */
  public abstract PythonValue constant(String str);

  /** Creates a boolean constant. */
  public abstract PythonValue constant(boolean bool);

  /** Creates a null constant. */
  public abstract PythonValue constantNull();

  /** Creates a reference to a global symbol, e.g. {@code Math}. */
  public abstract PythonValue global(String globalSymbol);
}
