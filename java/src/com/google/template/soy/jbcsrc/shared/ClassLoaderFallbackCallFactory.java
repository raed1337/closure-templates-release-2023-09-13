
/*
 * Copyright 2020 Google Inc.
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
package com.google.template.soy.jbcsrc.shared;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodType.methodType;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ObjectArrays;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.TemplateValue;
import com.google.template.soy.jbcsrc.api.RenderResult;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

public final class ClassLoaderFallbackCallFactory {
  private static final boolean FORCE_SLOWPATH =
      Boolean.getBoolean("soy_jbcsrc_take_classloader_fallback_slowpath");

  @VisibleForTesting
  public interface AlwaysSlowPath {}

  private static final class SlowPathHandles {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static MethodHandle findLocalStaticOrDie(String name, MethodType type) {
      try {
        return LOOKUP.findStatic(ClassLoaderFallbackCallFactory.class, name, type);
      } catch (ReflectiveOperationException e) {
        throw new LinkageError(e.getMessage(), e);
      }
    }

    private static final MethodHandle SLOWPATH_RENDER_RECORD =
        findLocalStaticOrDie(
            "slowPathRenderRecord",
            methodType(
                RenderResult.class,
                SoyCallSite.class,
                String.class,
                SoyRecord.class,
                SoyRecord.class,
                LoggingAdvisingAppendable.class,
                RenderContext.class));

    private static final MethodHandle SLOWPATH_RENDER_POSITIONAL =
        findLocalStaticOrDie(
            "slowPathRenderPositional",
            methodType(
                RenderResult.class,
                SoyCallSite.class,
                String.class,
                SoyValueProvider[].class,
                SoyRecord.class,
                LoggingAdvisingAppendable.class,
                RenderContext.class));

    private static final MethodHandle SLOWPATH_TEMPLATE =
        findLocalStaticOrDie(
            "slowPathTemplate",
            methodType(
                CompiledTemplate.class, SoyCallSite.class, String.class, RenderContext.class));

    private static final MethodHandle SLOWPATH_TEMPLATE_VALUE =
        findLocalStaticOrDie(
            "slowPathTemplateValue",
            methodType(TemplateValue.class, SoyCallSite.class, String.class, RenderContext.class));

    private static final MethodHandle SLOWPATH_CONST =
        findLocalStaticOrDie(
            "slowPathConst",
            methodType(Object.class, SoyCallSite.class, String.class, RenderContext.class));

    private static final MethodHandle SLOWPATH_EXTERN =
        findLocalStaticOrDie(
            "slowPathExtern",
            methodType(
                Object.class,
                SoyCallSite.class,
                String.class,
                RenderContext.class,
                Object[].class));

    private static final MethodHandle IS_CACHE_VALID =
        findLocalStaticOrDie(
            "isCacheValid", methodType(boolean.class, int.class, RenderContext.class));

    private static final MethodType RENDER_TYPE =
        methodType(
            RenderResult.class,
            SoyRecord.class,
            SoyRecord.class,
            LoggingAdvisingAppendable.class,
            RenderContext.class);

    private static final MethodType TEMPLATE_ACCESSOR_TYPE = methodType(CompiledTemplate.class);
    private static final MethodType METHOD_HANDLE_TYPE = methodType(MethodHandle.class);

    private static final MethodHandle WEAK_REF_GET;

    static {
      try {
        WEAK_REF_GET = LOOKUP.findVirtual(WeakReference.class, "get", methodType(Object.class));
      } catch (ReflectiveOperationException e) {
        throw new LinkageError(e.getMessage(), e);
      }
    }

    private SlowPathHandles() {}
  }

  private ClassLoaderFallbackCallFactory() {}

  public static CallSite bootstrapTemplateLookup(
      MethodHandles.Lookup lookup, String name, MethodType type, String templateName)
      throws NoSuchMethodException, IllegalAccessException {
    if (!FORCE_SLOWPATH) {
      return attemptTemplateLookup(lookup, type, templateName);
    }
    return createSlowPathTemplateCallSite(templateName, type);
  }

  private static CallSite attemptTemplateLookup(MethodHandles.Lookup lookup, MethodType type, String templateName) throws NoSuchMethodException, IllegalAccessException {
    Optional<Class<?>> templateClass = findTemplateClass(lookup, templateName);
    if (templateClass.isPresent()) {
      CompiledTemplate template = getTemplate(lookup, templateClass.get(), templateName);
      MethodHandle getter = constant(CompiledTemplate.class, template);
      getter = dropArguments(getter, 0, RenderContext.class);
      return new ConstantCallSite(getter);
    }
    return createSlowPathTemplateCallSite(templateName, type);
  }

  private static CallSite createSlowPathTemplateCallSite(String templateName, MethodType type) {
    MethodHandle slowPath = SlowPathHandles.SLOWPATH_TEMPLATE;
    slowPath = insertArguments(slowPath, 1, templateName);
    return new SoyCallSite(type, slowPath);
  }

  public static CallSite bootstrapTemplateValueLookup(
      MethodHandles.Lookup lookup, String name, MethodType type, String templateName)
      throws NoSuchMethodException, IllegalAccessException {
    if (!FORCE_SLOWPATH) {
      return attemptTemplateValueLookup(lookup, type, templateName);
    }
    return createSlowPathTemplateValueCallSite(templateName, type);
  }

  private static CallSite attemptTemplateValueLookup(MethodHandles.Lookup lookup, MethodType type, String templateName) throws NoSuchMethodException, IllegalAccessException {
    Optional<Class<?>> templateClass = findTemplateClass(lookup, templateName);
    if (templateClass.isPresent()) {
      CompiledTemplate template = getTemplate(lookup, templateClass.get(), templateName);
      TemplateValue value = TemplateValue.create(templateName, template);
      MethodHandle getter = constant(TemplateValue.class, value);
      getter = dropArguments(getter, 0, RenderContext.class);
      return new ConstantCallSite(getter);
    }
    return createSlowPathTemplateValueCallSite(templateName, type);
  }

  private static CallSite createSlowPathTemplateValueCallSite(String templateName, MethodType type) {
    MethodHandle slowPath = SlowPathHandles.SLOWPATH_TEMPLATE_VALUE;
    slowPath = insertArguments(slowPath, 1, templateName);
    return new SoyCallSite(type, slowPath);
  }

  private static CompiledTemplate getTemplate(
      MethodHandles.Lookup lookup, Class<?> templateClass, String templateName)
      throws NoSuchMethodException, IllegalAccessException {
    String methodName = Names.renderMethodNameFromSoyTemplateName(templateName);
    MethodHandle templateAccessor =
        lookup.findStatic(templateClass, methodName, SlowPathHandles.TEMPLATE_ACCESSOR_TYPE);
    try {
      return (CompiledTemplate) templateAccessor.invokeExact();
    } catch (Throwable t) {
      throw new AssertionError(t);
    }
  }

  public static CallSite bootstrapCall(
      MethodHandles.Lookup lookup, String name, MethodType type, String templateName)
      throws IllegalAccessException, NoSuchMethodException {
    if (!FORCE_SLOWPATH) {
      return attemptCall(lookup, type, templateName);
    }
    return createSlowPathCallSite(templateName, type);
  }

  private static CallSite attemptCall(MethodHandles.Lookup lookup, MethodType type, String templateName) throws NoSuchMethodException, IllegalAccessException {
    Optional<Class<?>> templateClass = findTemplateClass(lookup, templateName);
    String methodName = Names.renderMethodNameFromSoyTemplateName(templateName);
    if (templateClass.isPresent()) {
      return new ConstantCallSite(lookup.findStatic(templateClass.get(), methodName, type));
    }
    return createSlowPathCallSite(templateName, type);
  }

  private static CallSite createSlowPathCallSite(String templateName, MethodType type) {
    MethodHandle slowPathRenderHandle =
        type.equals(SlowPathHandles.RENDER_TYPE)
            ? SlowPathHandles.SLOWPATH_RENDER_RECORD
            : SlowPathHandles.SLOWPATH_RENDER_POSITIONAL;
    slowPathRenderHandle = insertArguments(slowPathRenderHandle, 1, templateName);
    if (!type.equals(SlowPathHandles.RENDER_TYPE)) {
      int numParams = type.parameterCount();
      int numPositionalParams = numParams - 3;
      slowPathRenderHandle =
          slowPathRenderHandle.asCollector(1, SoyValueProvider[].class, numPositionalParams);
    }
    return new SoyCallSite(type, slowPathRenderHandle);
  }

  public static CallSite bootstrapConstLookup(
      MethodHandles.Lookup lookup,
      String name,
      MethodType type,
      String constClassName,
      String constName)
      throws NoSuchMethodException, IllegalAccessException {
    if (!FORCE_SLOWPATH) {
      return attemptConstLookup(lookup, type, constClassName, constName);
    }
    return createSlowPathConstCallSite(constClassName, constName, type);
  }

  private static CallSite attemptConstLookup(MethodHandles.Lookup lookup, MethodType type, String constClassName, String constName) throws NoSuchMethodException, IllegalAccessException {
    ClassLoader callerClassLoader = lookup.lookupClass().getClassLoader();
    try {
      Class<?> constClass = callerClassLoader.loadClass(constClassName);
      MethodHandle handle =
          lookup.findStatic(
              constClass, constName, methodType(type.returnType(), RenderContext.class));
      return new ConstantCallSite(handle);
    } catch (ClassNotFoundException classNotFoundException) {
      // Fall back to using the RenderContext class loader.
    }
    return createSlowPathConstCallSite(constClassName, constName, type);
  }

  private static CallSite createSlowPathConstCallSite(String constClassName, String constName, MethodType type) {
    MethodHandle slowPath = SlowPathHandles.SLOWPATH_CONST;
    slowPath =
        insertArguments(
            slowPath, 1, constClassName + '#' + constName + '#' + type.toMethodDescriptorString());
    return new SoyCallSite(
        type, slowPath.asType(slowPath.type().changeReturnType(type.returnType())));
  }

  public static CallSite bootstrapExternCall(
      MethodHandles.Lookup lookup,
      String name,
      MethodType type,
      String externClassName,
      String externName)
      throws NoSuchMethodException, IllegalAccessException {
    if (!FORCE_SLOWPATH) {
      return attemptExternCall(lookup, type, externClassName, externName);
    }
    return createSlowPathExternCallSite(externClassName, externName, type);
  }

  private static CallSite attemptExternCall(MethodHandles.Lookup lookup, MethodType type, String externClassName, String externName) throws NoSuchMethodException, IllegalAccessException {
    ClassLoader callerClassLoader = lookup.lookupClass().getClassLoader();
    try {
      Class<?> constClass = callerClassLoader.loadClass(externClassName);
      MethodHandle handle = lookup.findStatic(constClass, externName, type);
      return new ConstantCallSite(handle);
    } catch (ClassNotFoundException classNotFoundException) {
      // Fall back to using the RenderContext class loader.
    }
    return createSlowPathExternCallSite(externClassName, externName, type);
  }

  private static CallSite createSlowPathExternCallSite(String externClassName, String externName, MethodType type) {
    MethodHandle slowPath = SlowPathHandles.SLOWPATH_EXTERN;
    slowPath =
        insertArguments(
            slowPath,
            1,
            externClassName + '#' + externName + '#' + type.toMethodDescriptorString());
    slowPath = slowPath.asCollector(Object[].class, type.parameterCount() - 1);
    return new SoyCallSite(type, slowPath.asType(type.insertParameterTypes(0, SoyCallSite.class)));
  }

  public static Object slowPathConst(
      SoyCallSite callSite, String constantFqn, RenderContext context) throws Throwable {
    CompiledTemplates templates = context.getTemplates();
    MethodHandle constMethod = templates.getConstOrExternMethod(constantFqn);
    callSite.update(templates, constMethod);
    return constMethod.invoke(context);
  }

  public static Object slowPathExtern(
      SoyCallSite callSite, String externFqn, RenderContext context, Object[] args)
      throws Throwable {
    CompiledTemplates templates = context.getTemplates();
    MethodHandle externMethod = templates.getConstOrExternMethod(externFqn);

    callSite.update(templates, externMethod);
    return externMethod.invokeWithArguments(ObjectArrays.concat(context, args));
  }

  private static Optional<Class<?>> findTemplateClass(
      MethodHandles.Lookup lookup, String templateName) throws NoSuchMethodException {
    ClassLoader callerClassLoader = lookup.lookupClass().getClassLoader();
    String className = Names.javaClassNameFromSoyTemplateName(templateName);
    Class<?> clazz;
    try {
      clazz = callerClassLoader.loadClass(className);
    } catch (ClassNotFoundException classNotFoundException) {
      return Optional.empty();
    }
    if (clazz.getClassLoader() instanceof AlwaysSlowPath) {
      Method method =
          clazz.getDeclaredMethod(Names.renderMethodNameFromSoyTemplateName(templateName));
      if (Modifier.isPublic(method.getModifiers())) {
        return Optional.empty();
      }
    }
    return Optional.of(clazz);
  }

  public static CompiledTemplate slowPathTemplate(
      SoyCallSite callSite, String templateName, RenderContext context) {
    CompiledTemplates templates = context.getTemplates();
    CompiledTemplate template = templates.getTemplate(templateName);
    callSite.updateWithConstant(templates, CompiledTemplate.class, template);
    return template;
  }

  public static TemplateValue slowPathTemplateValue(
      SoyCallSite callSite, String templateName, RenderContext context) {
    CompiledTemplates templates = context.getTemplates();
    TemplateValue value = templates.getTemplateValue(templateName);
    callSite.updateWithConstant(templates, TemplateValue.class, value);
    return value;
  }

  public static RenderResult slowPathRenderPositional(
      SoyCallSite callSite,
      String templateName,
      SoyValueProvider[] params,
      SoyRecord ij,
      LoggingAdvisingAppendable appendable,
      RenderContext context)
      throws Throwable {
    CompiledTemplates templates = context.getTemplates();
    MethodHandle renderMethod = templates.getPositionalRenderMethod(templateName, params.length);
    callSite.update(templates, renderMethod);
    Object[] args =
        ObjectArrays.concat(params, new Object[] {ij, appendable, context}, Object.class);
    return (RenderResult) renderMethod.invokeWithArguments(args);
  }

  public static RenderResult slowPathRenderRecord(
      SoyCallSite callSite,
      String templateName,
      SoyRecord params,
      SoyRecord ij,
      LoggingAdvisingAppendable appendable,
      RenderContext context)
      throws Throwable {
    CompiledTemplates templates = context.getTemplates();
    MethodHandle renderMethod = templates.getRenderMethod(templateName);
    callSite.update(templates, renderMethod);
    return (RenderResult) renderMethod.invoke(params, ij, appendable, context);
  }

  private static final class SoyCallSite extends MutableCallSite {
    private final MethodHandle test;
    private final MethodHandle slowPath;

    SoyCallSite(MethodType type, MethodHandle slowPath) {
      super(type);
      checkState(slowPath.type().parameterType(0).equals(SoyCallSite.class));
      slowPath = insertArguments(slowPath, 0, this);
      int renderContextIndex = slowPath.type().parameterList().indexOf(RenderContext.class);
      checkState(renderContextIndex != -1);
      this.slowPath = slowPath;
      this.test = MethodHandles.dropArgumentsToMatch(SlowPathHandles.IS_CACHE_VALID, 1, type.parameterList(), renderContextIndex);
      setTarget(slowPath);
    }

    void update(CompiledTemplates newTemplates, MethodHandle newTarget) {
      newTarget =
          MethodHandles.foldArguments(
              MethodHandles.exactInvoker(newTarget.type()),
              SlowPathHandles.WEAK_REF_GET
                  .bindTo(new WeakReference<>(newTarget))
                  .asType(SlowPathHandles.METHOD_HANDLE_TYPE));

      this.setTarget(
          MethodHandles.guardWithTest(
              insertArguments(this.test, 0, newTemplates.getId()), newTarget, slowPath));
    }

    void updateWithConstant(CompiledTemplates newTemplates, Class<?> type, Object value) {
      var fastPathHandle =
          MethodHandles.dropArgumentsToMatch(
              SlowPathHandles.WEAK_REF_GET
                  .bindTo(new WeakReference<>(value))
                  .asType(methodType(type)),
              0,
              type().parameterList(),
              0);
      this.setTarget(
          MethodHandles.guardWithTest(
              insertArguments(this.test, 0, newTemplates.getId()), fastPathHandle, slowPath));
    }
  }

  public static boolean isCacheValid(int currentTemplatesId, RenderContext context) {
    return currentTemplatesId == context.getTemplates().getId();
  }
}
