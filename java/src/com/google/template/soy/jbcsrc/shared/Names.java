
/*
 * Copyright 2015 Google Inc.
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.internal.exemptions.NamespaceExemptions;
import java.util.regex.Pattern;
import org.objectweb.asm.Type;

/**
 * Utilities for translating soy symbols to and from strings that are suitable for use in java class
 * files. These utilities are shared between the compiler and the runtime system.
 */
public final class Names {
  public static final String META_INF_DELTEMPLATE_PATH =
      "META-INF/services/com.google.template.soy.deltemplates";

  public static final String META_INF_PLUGIN_PATH =
      "META-INF/services/com.google.template.soy.plugins";

  static final String CLASS_PREFIX = "com.google.template.soy.jbcsrc.gen.";
  public static final String INTERNAL_CLASS_PREFIX = CLASS_PREFIX.replace('.', '/');

  public static final String VARIANT_VAR_NAME = "__modifiable_variant__";

  private Names() {}

  public static boolean isGenerated(Type type) {
    return isGenerated(type.getClassName());
  }

  public static boolean isGenerated(String javaFqn) {
    return javaFqn.startsWith(CLASS_PREFIX);
  }

  public static final ImmutableSet<String> ALLOWED_SVP_PREFIXES =
      ImmutableSet.of("param", "let", "ph");

  private static final Pattern SVP_PATTERN =
      Pattern.compile("\\$(" + Joiner.on('|').join(ALLOWED_SVP_PREFIXES) + ")_[_\\w]+(#\\d+)?$");

  public static boolean isGeneratedSoyValueProvider(Type type) {
    return isGenerated(type) && SVP_PATTERN.matcher(type.getClassName()).find();
  }

  public static String javaClassNameFromSoyTemplateName(String soyTemplate) {
    checkArgument(BaseUtils.isDottedIdentifier(soyTemplate), "%s is not a valid template name.", soyTemplate);
    int lastDot = soyTemplate.lastIndexOf('.');
    checkArgument(lastDot != -1, "%s should contain a dot", soyTemplate);
    String soyNamespace = soyTemplate.substring(0, lastDot);
    return getJavaClassName(soyNamespace, soyTemplate);
  }

  private static String getJavaClassName(String soyNamespace, String soyTemplate) {
    return NamespaceExemptions.isKnownDuplicateNamespace(soyNamespace)
        ? CLASS_PREFIX + soyTemplate
        : CLASS_PREFIX + soyNamespace;
  }

  public static String javaClassNameFromSoyNamespace(String soyTemplateNamespace) {
    checkArgument(BaseUtils.isDottedIdentifier(soyTemplateNamespace), "%s is not a valid template namespace.", soyTemplateNamespace);
    checkArgument(!NamespaceExemptions.isKnownDuplicateNamespace(soyTemplateNamespace));
    return CLASS_PREFIX + soyTemplateNamespace;
  }

  public static String renderMethodNameFromSoyTemplateName(String soyTemplate) {
    checkArgument(BaseUtils.isDottedIdentifier(soyTemplate), "%s is not a valid template name.", soyTemplate);
    int lastDot = soyTemplate.lastIndexOf('.');
    String soyNamespace = soyTemplate.substring(0, lastDot);
    return NamespaceExemptions.isKnownDuplicateNamespace(soyNamespace) ? "render" : soyTemplate.substring(lastDot + 1);
  }

  public static String templateMethodNameFromSoyTemplateName(String soyTemplate) {
    checkArgument(BaseUtils.isDottedIdentifier(soyTemplate), "%s is not a valid template name.", soyTemplate);
    int lastDot = soyTemplate.lastIndexOf('.');
    String soyNamespace = soyTemplate.substring(0, lastDot);
    return NamespaceExemptions.isKnownDuplicateNamespace(soyNamespace) ? "template" : "template$" + soyTemplate.substring(lastDot + 1);
  }

  public static String javaFileName(String soyNamespace, String fileName) {
    checkArgument(BaseUtils.isDottedIdentifier(soyNamespace), "%s is not a valid soy namespace name.", soyNamespace);

    String javaClassName = javaClassNameFromSoyTemplateName(soyNamespace + ".foo");
    String javaPackageName = javaClassName.substring(0, javaClassName.lastIndexOf('.'));
    return javaPackageName.replace('.', '/') + '/' + fileName;
  }

  public static void rewriteStackTrace(Throwable throwable) {
    StackTraceElement[] stack = throwable.getStackTrace();
    for (int i = 0; i < stack.length; i++) {
      StackTraceElement curr = stack[i];
      if (isGenerated(curr.getClassName())) {
        stack[i] = createNewStackTraceElement(curr);
      }
    }
    throwable.setStackTrace(stack);
  }

  private static StackTraceElement createNewStackTraceElement(StackTraceElement curr) {
    return new StackTraceElement(
        curr.getClassName().substring(CLASS_PREFIX.length()),
        curr.getMethodName(),
        curr.getFileName(),
        curr.getLineNumber());
  }
}
