
package com.google.template.soy.passes;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.error.SoyErrors;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.plugin.internal.SoySourceFunctionDescriptor;
import com.google.template.soy.plugin.java.internal.PluginAnalyzer;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.shared.restricted.Signature;
import com.google.template.soy.shared.restricted.SoyDeprecated;
import com.google.template.soy.shared.restricted.SoyFieldSignature;
import com.google.template.soy.shared.restricted.SoyFunction;
import com.google.template.soy.shared.restricted.SoyFunctionSignature;
import com.google.template.soy.shared.restricted.SoyMethodSignature;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.shared.restricted.SoySourceFunctionMethod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** Encapsulates the logic for looking up plugins. */
public final class PluginResolver {
  
  public static PluginResolver nullResolver(Mode mode, ErrorReporter reporter) {
    return new PluginResolver(
        mode,
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        reporter);
  }

  private static final SoyErrorKind UNKNOWN_PLUGIN =
      SoyErrorKind.of("Unknown {0} ''{1}''.{2}", StyleAllowance.NO_PUNCTUATION);
  static final SoyErrorKind DEPRECATED_PLUGIN =
      SoyErrorKind.deprecation("{0} is deprecated: {1}", StyleAllowance.NO_PUNCTUATION, StyleAllowance.NO_CAPS);
  private static final SoyErrorKind INCORRECT_NUM_ARGS =
      SoyErrorKind.of("{0} called with {1} arguments (expected {2}).");
  private static final SoyErrorKind PLUGIN_NAME_NOT_ALLOWED =
      SoyErrorKind.of("Plugin ''{0}'' is named ''{1}'' which is not allowed because it conflicts with Soy''s {1}() literal syntax.");
  private static final SoyErrorKind DIFFERENT_IMPLS_REGISTERED =
      SoyErrorKind.of("Plugin named ''{0}'' has two different implementations registered: ''{1}'' and ''{2}''.");
  private static final SoyErrorKind MISSING_FUNCTION_SIGNATURE =
      SoyErrorKind.of("Plugin class ''{0}'' has no @SoyFunctionSignature annotation.");
  private static final SoyErrorKind MISSING_METHOD_SIGNATURE =
      SoyErrorKind.of("Plugin class ''{0}'' has no @SoyMethodSignature annotation.");
  private static final SoyErrorKind DIFFERENT_METHOD_IMPLS_REGISTERED =
      SoyErrorKind.of("Plugin method named ''{0}'' with base type ''{1}'' has two different implementations registered: ''{2}'' and ''{3}''.");
  private static final SoyErrorKind DIFFERENT_FIELD_IMPLS_REGISTERED =
      SoyErrorKind.of("Plugin field named ''{0}'' with base type ''{1}'' has two different implementations registered: ''{2}'' and ''{3}''.");
  private static final SoyErrorKind MULTIPLE_PLUGIN_INSTANCES =
      SoyErrorKind.of("Plugin class ''{0}'' uses callInstanceMethod for methods on multiple classes {1}.");
  private static final SoyErrorKind FUNCTION_PRINT_DIRECTIVE_COLLISION =
      SoyErrorKind.of("Plugin ''{0}'' named ''{1}'' collides with print directive ''{2}''.");
  private static final SoyErrorKind FUNCTION_NOT_CALLABLE =
      SoyErrorKind.of("Function ''{0}'' cannot be called as a print directive.");
  
  private static final ImmutableSet<String> COLLISION_WHITELIST = ImmutableSet.<String>builder().build();
  private static final SoySourceFunction ERROR_PLACEHOLDER_FUNCTION = new SoySourceFunction() {};

  public enum Mode {
    ALLOW_UNDEFINED,
    ALLOW_UNDEFINED_AND_WARN,
    REQUIRE_DEFINITIONS
  }

  private final Mode mode;
  private final ImmutableMap<String, SoyPrintDirective> printDirectives;
  private final ImmutableMap<String, Object> functions;
  private final ImmutableSetMultimap<String, SoySourceFunction> methodsByName;
  private final ImmutableSetMultimap<String, SoySourceFunction> fieldsByName;
  private final ImmutableMap<SoySourceFunction, SoySourceFunctionDescriptor> functionToDesc;
  private final ErrorReporter reporter;

  public PluginResolver(
      Mode mode,
      List<SoyPrintDirective> soyPrintDirectives,
      List<SoyFunction> soyFunctions,
      List<SoySourceFunctionDescriptor> sourceFunctions,
      List<SoySourceFunction> soyMethods,
      ErrorReporter reporter) {
    this.mode = checkNotNull(mode);
    this.reporter = checkNotNull(reporter);
    this.functions = mergeFunctions(soyFunctions, sourceFunctions);
    this.functionToDesc = buildFunctionDescriptorMap();
    this.printDirectives = buildPrintDirectiveMap(soyPrintDirectives);
    this.methodsByName = buildMethodsByName(soyMethods);
    this.fieldsByName = buildFieldsByName(soyMethods);
  }

  private ImmutableMap<String, Object> mergeFunctions(List<SoyFunction> soyFunctions, List<SoySourceFunctionDescriptor> sourceFunctions) {
    Map<String, Object> mergedFunctions = Maps.newLinkedHashMapWithExpectedSize(soyFunctions.size() + sourceFunctions.size());
    for (Object function : Iterables.concat(soyFunctions, sourceFunctions)) {
      String name = processFunction(function, mergedFunctions);
      if (BaseUtils.ILLEGAL_PLUGIN_NAMES.contains(name)) {
        reporter.report(SourceLocation.UNKNOWN, PLUGIN_NAME_NOT_ALLOWED, function.getClass().getName(), name);
      }
    }
    return ImmutableMap.copyOf(mergedFunctions);
  }

  private String processFunction(Object function, Map<String, Object> mergedFunctions) {
    String name;
    if (function instanceof SoySourceFunctionDescriptor) {
      SoySourceFunctionDescriptor desc = (SoySourceFunctionDescriptor) function;
      function = desc.soySourceFunction();
    }
    if (function instanceof SoySourceFunction) {
      SoyFunctionSignature sig = function.getClass().getAnnotation(SoyFunctionSignature.class);
      if (sig == null) {
        reporter.report(SourceLocation.UNKNOWN, MISSING_FUNCTION_SIGNATURE, function.getClass().getName());
        return null;
      }
      name = sig.name();
      checkSinglePluginInstance(function);
    } else {
      SoyFunction legacyFunction = (SoyFunction) function;
      name = legacyFunction.getName();
    }
    Object old = mergedFunctions.put(name, function);
    if (old != null) {
      reporter.report(SourceLocation.UNKNOWN, DIFFERENT_IMPLS_REGISTERED, name, old, function);
    }
    return name;
  }

  private void checkSinglePluginInstance(Object function) {
    if (function instanceof SoyJavaSourceFunction) {
      Set<String> instances = PluginAnalyzer.analyze((SoyJavaSourceFunction) function).pluginInstanceNames();
      if (instances.size() > 1) {
        reporter.report(SourceLocation.UNKNOWN, MULTIPLE_PLUGIN_INSTANCES, function.getClass().getName(), instances);
      }
    }
  }

  private ImmutableMap<SoySourceFunction, SoySourceFunctionDescriptor> buildFunctionDescriptorMap() {
    ImmutableMap.Builder<SoySourceFunction, SoySourceFunctionDescriptor> functionToDesc = ImmutableMap.builder();
    for (Object function : functions.values()) {
      if (function instanceof SoySourceFunctionDescriptor) {
        SoySourceFunctionDescriptor desc = (SoySourceFunctionDescriptor) function;
        functionToDesc.put(desc.soySourceFunction(), desc);
      }
    }
    return functionToDesc.buildOrThrow();
  }

  private ImmutableMap<String, SoyPrintDirective> buildPrintDirectiveMap(List<SoyPrintDirective> soyPrintDirectives) {
    Map<String, SoyPrintDirective> indexedDirectives = Maps.newLinkedHashMapWithExpectedSize(soyPrintDirectives.size());
    for (SoyPrintDirective directive : soyPrintDirectives) {
      SoyPrintDirective old = indexedDirectives.put(directive.getName(), directive);
      if (old != null) {
        reporter.report(SourceLocation.UNKNOWN, DIFFERENT_IMPLS_REGISTERED, directive.getName(), directive, old);
      }
      checkPrintDirectiveName(directive);
    }
    return ImmutableMap.copyOf(indexedDirectives);
  }

  private void checkPrintDirectiveName(SoyPrintDirective directive) {
    String functionName = getFunctionNameEquivalentToPrintDirectiveName(directive.getName());
    if (BaseUtils.ILLEGAL_PLUGIN_NAMES.contains(functionName)) {
      reporter.report(SourceLocation.UNKNOWN, PLUGIN_NAME_NOT_ALLOWED, directive.getClass().getName(), functionName);
    }
    if (COLLISION_WHITELIST.contains(functionName)) {
      return;
    }
    if (functions.containsKey(functionName)) {
      reporter.report(SourceLocation.UNKNOWN, FUNCTION_PRINT_DIRECTIVE_COLLISION, functions.get(functionName).getClass().getName(), functionName, directive.getClass().getName());
    }
  }

  private ImmutableSetMultimap<String, SoySourceFunction> buildMethodsByName(List<SoySourceFunction> soyMethods) {
    Map<String, SoySourceFunction> uniqueMethods = new HashMap<>();
    return soyMethods.stream()
        .filter(method -> method.getClass().isAnnotationPresent(SoyMethodSignature.class))
        .filter(method -> checkUniqueMethod(method, uniqueMethods))
        .collect(toImmutableSetMultimap(method -> method.getClass().getAnnotation(SoyMethodSignature.class).name(), method -> method));
  }

  private boolean checkUniqueMethod(SoySourceFunction method, Map<String, SoySourceFunction> uniqueMethods) {
    SoyMethodSignature sig = method.getClass().getAnnotation(SoyMethodSignature.class);
    String key = sig.name() + "/" + sig.baseType();
    SoySourceFunction old = uniqueMethods.put(key, method);
    if (old != null) {
      reporter.report(SourceLocation.UNKNOWN, DIFFERENT_METHOD_IMPLS_REGISTERED, sig.name(), sig.baseType(), old.getClass().getCanonicalName(), method.getClass().getCanonicalName());
      return false;
    }
    return true;
  }

  private ImmutableSetMultimap<String, SoySourceFunction> buildFieldsByName(List<SoySourceFunction> soyMethods) {
    Map<String, SoySourceFunction> uniqueFields = new HashMap<>();
    return soyMethods.stream()
        .filter(method -> method.getClass().isAnnotationPresent(SoyFieldSignature.class))
        .filter(method -> checkUniqueField(method, uniqueFields))
        .collect(toImmutableSetMultimap(method -> method.getClass().getAnnotation(SoyFieldSignature.class).name(), method -> method));
  }

  private boolean checkUniqueField(SoySourceFunction method, Map<String, SoySourceFunction> uniqueFields) {
    SoyFieldSignature sig = method.getClass().getAnnotation(SoyFieldSignature.class);
    String key = sig.name() + "/" + sig.baseType();
    SoySourceFunction old = uniqueFields.put(key, method);
    if (old != null) {
      reporter.report(SourceLocation.UNKNOWN, DIFFERENT_FIELD_IMPLS_REGISTERED, sig.name(), sig.baseType(), old.getClass().getCanonicalName(), method.getClass().getCanonicalName());
      return false;
    }
    return true;
  }

  public Mode getPluginResolutionMode() {
    return mode;
  }

  public SoyPrintDirective lookupPrintDirective(String name, int numArgs, SourceLocation location) {
    SoyPrintDirective soyPrintDirective = printDirectives.get(name);
    if (soyPrintDirective == null) {
      reportMissing(location, "print directive", name, printDirectives.keySet());
      soyPrintDirective = createPlaceholderPrintDirective(name, numArgs);
    }
    checkNumArgs("print directive", soyPrintDirective.getValidArgsSizes(), numArgs, location);
    warnIfDeprecated(name, soyPrintDirective, location);
    return soyPrintDirective;
  }

  Optional<SoySourceFunction> getFunctionCallableAsPrintDirective(String directiveName, SourceLocation sourceLocation) {
    if (printDirectives.containsKey(directiveName)) {
      return Optional.empty();
    }
    String functionName = getFunctionNameEquivalentToPrintDirectiveName(directiveName);
    if (COLLISION_WHITELIST.contains(functionName)) {
      return Optional.empty();
    }
    Object function = functions.get(functionName);
    if (function == null) {
      return Optional.empty();
    }
    if (function instanceof SoySourceFunction) {
      SoyFunctionSignature signature = function.getClass().getAnnotation(SoyFunctionSignature.class);
      if (signature.callableAsDeprecatedPrintDirective()) {
        return Optional.of((SoySourceFunction) function);
      }
    }
    reporter.report(sourceLocation, FUNCTION_NOT_CALLABLE, functionName);
    return Optional.empty();
  }

  @Nullable
  public Object lookupSoyFunction(String name, int numArgs, SourceLocation location) {
    Object soyFunction = functions.get(name);
    if (soyFunction == null) {
      return null;
    }
    Set<Integer> validArgsSize = getValidArgsSizes(soyFunction);
    checkNumArgs("function", validArgsSize, numArgs, location);
    warnIfDeprecated(name, soyFunction, location);
    return soyFunction;
  }

  public ImmutableSet<SoySourceFunction> lookupSoyMethods(String methodName) {
    return methodsByName.get(methodName);
  }

  public ImmutableSet<String> getAllMethodNames() {
    return methodsByName.keySet();
  }

  public ImmutableSet<SoySourceFunction> lookupSoyFields(String methodName) {
    return fieldsByName.get(methodName);
  }

  public ImmutableSet<String> getAllFieldNames() {
    return fieldsByName.keySet();
  }

  public void reportUnresolved(FunctionNode fct) {
    Preconditions.checkArgument(!fct.isResolved());
    if (fct.hasStaticName()) {
      reportMissing(fct.getFunctionNameLocation(), "function", fct.getStaticFunctionName(), functions.keySet());
    } else {
      reportMissing(fct.getNameExpr().getSourceLocation(), "function", fct.getNameExpr().toSourceString(), "");
    }
    fct.setSoyFunction(ERROR_PLACEHOLDER_FUNCTION);
  }

  @Nullable
  public SoySourceFunctionDescriptor getDescriptor(Object function) {
    return functionToDesc.get(function);
  }

  private void reportMissing(SourceLocation location, String type, String name, Set<String> alternatives) {
    String didYouMean = SoyErrors.getDidYouMeanMessage(alternatives, name);
    reportMissing(location, type, name, didYouMean);
  }

  private void reportMissing(SourceLocation location, String type, String name, String didYouMean) {
    switch (mode) {
      case REQUIRE_DEFINITIONS:
        reporter.report(location, UNKNOWN_PLUGIN, type, name, didYouMean);
        break;
      case ALLOW_UNDEFINED_AND_WARN:
        reporter.warn(location, UNKNOWN_PLUGIN, type, name, didYouMean);
        break;
      case ALLOW_UNDEFINED:
        break;
    }
  }

  private static Set<Integer> getValidArgsSizes(Object soyFunction) {
    if (soyFunction instanceof SoyFunction) {
      return ((SoyFunction) soyFunction).getValidArgsSizes();
    } else {
      SoyFunctionSignature signature = soyFunction.getClass().getAnnotation(SoyFunctionSignature.class);
      Preconditions.checkArgument(signature != null);
      return getValidArgsSizes(signature.value());
    }
  }

  static Set<Integer> getValidArgsSizes(Signature[] signatures) {
    ImmutableSortedSet.Builder<Integer> builder = ImmutableSortedSet.naturalOrder();
    for (Signature signature : signatures) {
      builder.add(signature.parameterTypes().length);
    }
    return builder.build();
  }

  private void checkNumArgs(String pluginKind, Set<Integer> arities, int actualNumArgs, SourceLocation location) {
    if (!arities.contains(actualNumArgs)) {
      reporter.report(location, INCORRECT_NUM_ARGS, pluginKind, actualNumArgs, Joiner.on(" or ").join(arities));
    }
  }

  private void warnIfDeprecated(String name, Object plugin, SourceLocation location) {
    warnIfDeprecated(reporter, name, plugin, location);
  }

  static void warnIfDeprecated(ErrorReporter reporter, String name, Object plugin, SourceLocation location) {
    if (plugin instanceof SoySourceFunctionMethod) {
      SoySourceFunction function = ((SoySourceFunctionMethod) plugin).getImpl();
      if (warnIfSoyDeprecated(reporter, name, function, location)) {
        return;
      }
      SoyMethodSignature sig = function.getClass().getAnnotation(SoyMethodSignature.class);
      if (sig != null && !sig.deprecatedWarning().isEmpty()) {
        reporter.warn(location, DEPRECATED_PLUGIN, name, sig.deprecatedWarning());
      }
      return;
    } else if (plugin instanceof BuiltinFunction) {
      BuiltinFunction builtin = (BuiltinFunction) plugin;
      if (!builtin.deprecatedWarning().isEmpty()) {
        reporter.warn(location, DEPRECATED_PLUGIN, name, builtin.deprecatedWarning());
      }
    }

    if (warnIfSoyDeprecated(reporter, name, plugin, location)) {
      return;
    }

    if (plugin instanceof SoySourceFunction) {
      SoyFunctionSignature sig = plugin.getClass().getAnnotation(SoyFunctionSignature.class);
      if (sig != null && !sig.deprecatedWarning().isEmpty()) {
        reporter.warn(location, DEPRECATED_PLUGIN, name, sig.deprecatedWarning());
      }
    }
  }

  private static boolean warnIfSoyDeprecated(ErrorReporter reporter, String name, Object anything, SourceLocation location) {
    SoyDeprecated deprecatedNotice = anything.getClass().getAnnotation(SoyDeprecated.class);
    if (deprecatedNotice == null) {
      return false;
    }
    reporter.warn(location, DEPRECATED_PLUGIN, name, deprecatedNotice.value());
    return true;
  }

  private static SoyPrintDirective createPlaceholderPrintDirective(String name, int arity) {
    ImmutableSet<Integer> validArgSizes = ImmutableSet.of(arity);
    return new SoyPrintDirective() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public Set<Integer> getValidArgsSizes() {
        return validArgSizes;
      }
    };
  }

  static String getFunctionNameEquivalentToPrintDirectiveName(String printDirectiveName) {
    Preconditions.checkArgument(printDirectiveName.startsWith("|"), "Expected print directive name '%s' to start with '|'", printDirectiveName);
    return printDirectiveName.substring(1);
  }
}
