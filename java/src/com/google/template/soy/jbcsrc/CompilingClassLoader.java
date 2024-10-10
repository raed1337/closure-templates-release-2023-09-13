
/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.jbcsrc;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.base.internal.SoyFileSupplier;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyCompilationException;
import com.google.template.soy.error.SoyError;
import com.google.template.soy.internal.exemptions.NamespaceExemptions;
import com.google.template.soy.jbcsrc.internal.AbstractMemoryClassLoader;
import com.google.template.soy.jbcsrc.internal.ClassData;
import com.google.template.soy.jbcsrc.shared.Names;
import com.google.template.soy.soytree.PartialFileSetMetadata;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.types.SoyTypeRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** A classloader that can compile templates on demand. */
final class CompilingClassLoader extends AbstractMemoryClassLoader {
  private final Map<String, ClassData> classesByName = Collections.synchronizedMap(new HashMap<>());
  private final ImmutableMap<SourceFilePath, SoyFileSupplier> filePathsToSuppliers;
  private final ImmutableMap<String, SoyFileNode> javaClassNameToFile;
  private final SoyTypeRegistry typeRegistry;
  private final PartialFileSetMetadata fileSetMetadata;

  CompilingClassLoader(
      SoyFileSetNode fileSet,
      ImmutableMap<SourceFilePath, SoyFileSupplier> filePathsToSuppliers,
      SoyTypeRegistry typeRegistry,
      PartialFileSetMetadata fileSetMetadata) {
    this.filePathsToSuppliers = filePathsToSuppliers;
    this.typeRegistry = typeRegistry;
    this.fileSetMetadata = fileSetMetadata;
    this.javaClassNameToFile = initializeJavaClassNameToFile(fileSet);
  }

  private ImmutableMap<String, SoyFileNode> initializeJavaClassNameToFile(SoyFileSetNode fileSet) {
    Map<String, SoyFileNode> javaClassNameToFile = new LinkedHashMap<>();
    for (SoyFileNode file : fileSet.getChildren()) {
      if (NamespaceExemptions.isKnownDuplicateNamespace(file.getNamespace())) {
        for (TemplateNode template : file.getTemplates()) {
          javaClassNameToFile.put(Names.javaClassNameFromSoyTemplateName(template.getTemplateName()), file);
        }
      } else {
        javaClassNameToFile.put(Names.javaClassNameFromSoyNamespace(file.getNamespace()), file);
      }
    }
    return ImmutableMap.copyOf(javaClassNameToFile);
  }

  @Override
  protected ClassData getClassData(String name) {
    if (!Names.isGenerated(name)) {
      return null;
    }
    ClassData classDef = classesByName.remove(name);
    if (classDef != null) {
      return classDef;
    }
    return compileClassData(name);
  }

  private ClassData compileClassData(String name) {
    SoyFileNode node = javaClassNameToFile.get(name);
    if (node == null) {
      return null;
    }
    ErrorReporter reporter = ErrorReporter.create(filePathsToSuppliers);
    ClassData clazzToLoad = null;

    for (ClassData clazz : compileTemplates(node, reporter)) {
      String className = clazz.type().className();
      if (className.equals(name)) {
        clazzToLoad = clazz;
      } else {
        classesByName.put(className, clazz);
      }
    }

    if (reporter.hasErrors()) {
      reportCompilationErrors(reporter);
    }
    return clazzToLoad;
  }

  private Iterable<ClassData> compileTemplates(SoyFileNode node, ErrorReporter reporter) {
    return new SoyFileCompiler(
            node, new JavaSourceFunctionCompiler(typeRegistry, reporter), fileSetMetadata)
        .compile();
  }

  private void reportCompilationErrors(ErrorReporter reporter) {
    Iterable<SoyError> errors = Iterables.concat(reporter.getErrors(), reporter.getWarnings());
    throw new SoyCompilationException(errors);
  }
}
