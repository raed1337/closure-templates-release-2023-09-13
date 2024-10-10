
/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy.basicfunctions;

import com.google.template.soy.data.SoyValue;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.google.template.soy.plugin.java.restricted.JavaValueFactory;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptPluginContext;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValue;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValueFactory;
import com.google.template.soy.plugin.javascript.restricted.SoyJavaScriptSourceFunction;
import com.google.template.soy.plugin.python.restricted.PythonPluginContext;
import com.google.template.soy.plugin.python.restricted.PythonValue;
import com.google.template.soy.plugin.python.restricted.PythonValueFactory;
import com.google.template.soy.plugin.python.restricted.SoyPythonSourceFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.SoyPureFunction;
import java.lang.reflect.Method;
import java.util.List;

/** Soy function that returns whether a number is finite (not NaN or Infinity). */
@SoyPureFunction
@SoyFunctionSignature(
    name = "isFinite",
    value =
        @Signature(
            parameterTypes = {"number"},
            returnType = "bool"))
final class IsFiniteFunction
    implements SoyJavaSourceFunction, SoyJavaScriptSourceFunction, SoyPythonSourceFunction {

  @Override
  public JavaScriptValue applyForJavaScriptSource(
      JavaScriptValueFactory factory, List<JavaScriptValue> args, JavaScriptPluginContext context) {
    return invokeIsFiniteJavaScript(factory, args);
  }

  private JavaScriptValue invokeIsFiniteJavaScript(JavaScriptValueFactory factory, List<JavaScriptValue> args) {
    return factory.global("Number").invokeMethod("isFinite", args.get(0));
  }

  @Override
  public PythonValue applyForPythonSource(
      PythonValueFactory factory, List<PythonValue> args, PythonPluginContext context) {
    return invokeIsFinitePython(factory, args);
  }

  private PythonValue invokeIsFinitePython(PythonValueFactory factory, List<PythonValue> args) {
    return factory
        .global("isinstance")
        .call(args.get(0), factory.global("numbers.Number"))
        .and(factory.global("math.isfinite").call(args.get(0)));
  }

  // lazy singleton pattern, allows other backends to avoid the work.
  private static final class Methods {
    static final Method IS_FINITE_FN =
        JavaValueFactory.createMethod(BasicFunctionsRuntime.class, "isFinite", SoyValue.class);
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    return invokeIsFiniteJava(factory, args);
  }

  private JavaValue invokeIsFiniteJava(JavaValueFactory factory, List<JavaValue> args) {
    return factory.callStaticMethod(Methods.IS_FINITE_FN, args.get(0));
  }
}
