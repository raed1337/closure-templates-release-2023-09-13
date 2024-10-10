
package com.google.template.soy.basicfunctions;

import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.restricted.NumberData;
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
import com.google.template.soy.shared.restricted.SoyMethodSignature;
import com.google.template.soy.shared.restricted.SoyPureFunction;
import java.lang.reflect.Method;
import java.util.List;

/**
 * A function that determines if a given string contains another given string.
 *
 * <p>Duplicate functionality as StrContainsFunction but given includes name to allow for automatic
 * translation of TSX includes method. This method should only be used by TSX and not be hand
 * written.
 */
@SoyMethodSignature(
    name = "includes",
    baseType = "string",
    value = {
      @Signature(parameterTypes = "string", returnType = "bool"),
      @Signature(
          parameterTypes = {"string", "number"},
          returnType = "bool")
    })
@SoyPureFunction
public final class StrIncludesFunction
    implements SoyJavaSourceFunction, SoyJavaScriptSourceFunction, SoyPythonSourceFunction {
  
  private static final class Methods {
    static final Method STR_CONTAINS =
        JavaValueFactory.createMethod(
            BasicFunctionsRuntime.class, "strContains", SoyValue.class, String.class);
    static final Method STR_CONTAINS_FROM_INDEX =
        JavaValueFactory.createMethod(
            BasicFunctionsRuntime.class,
            "strContainsFromIndex",
            String.class,
            String.class,
            NumberData.class);
  }

  @Override
  public JavaScriptValue applyForJavaScriptSource(
      JavaScriptValueFactory factory, List<JavaScriptValue> args, JavaScriptPluginContext context) {
    return args.get(0).invokeMethod("includes", args.subList(1, args.size()));
  }

  @Override
  public PythonValue applyForPythonSource(
      PythonValueFactory factory, List<PythonValue> args, PythonPluginContext context) {
    return (args.size() == 3) 
        ? args.get(1).coerceToString().in(factory.global("runtime.str_substring")
            .call(args.get(0), args.get(2), factory.constantNull())) 
        : args.get(1).in(args.get(0));
  }

  @Override
  public JavaValue applyForJavaSource(
      JavaValueFactory factory, List<JavaValue> args, JavaPluginContext context) {
    return (args.size() == 3) 
        ? factory.callStaticMethod(Methods.STR_CONTAINS_FROM_INDEX, args.get(0), args.get(1), args.get(2)) 
        : factory.callStaticMethod(Methods.STR_CONTAINS, args.get(0), args.get(1).coerceToSoyString());
  }
}
