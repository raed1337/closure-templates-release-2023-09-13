
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

package com.google.template.soy.jbcsrc;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteSource;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.base.internal.SoyFileSupplier;
import com.google.template.soy.base.internal.SoyJarFileWriter;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.internal.exemptions.NamespaceExemptions;
import com.google.template.soy.jbcsrc.api.PluginRuntimeInstanceInfo;
import com.google.template.soy.jbcsrc.internal.ClassData;
import com.google.template.soy.jbcsrc.restricted.Flags;
import com.google.template.soy.jbcsrc.shared.CompiledTemplates;
import com.google.template.soy.jbcsrc.shared.Names;
import com.google.template.soy.plugin.java.internal.PluginAnalyzer;
import com.google.template.soy.plugin.java.restricted.SoyJavaSourceFunction;
import com.google.template.soy.soytree.FileSetMetadata;
import com.google.template.soy.soytree.PartialFileSetMetadata;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateMetadata;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.TemplateType;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** The entry point to the {@code jbcsrc} compiler. */
public final class BytecodeCompiler {

  public static Optional<CompiledTemplates> compile(
      FileSetMetadata registry,
      SoyFileSetNode fileSet,
      ErrorReporter reporter,
      ImmutableMap<SourceFilePath, SoyFileSupplier> filePathsToSuppliers,
      SoyTypeRegistry typeRegistry) {
    ErrorReporter.Checkpoint checkpoint = reporter.checkpoint();
    CompiledTemplates templates = createCompiledTemplates(registry, fileSet, filePathsToSuppliers, typeRegistry);
    if (reporter.errorsSince(checkpoint)) {
      return Optional.empty();
    }
    return Optional.of(templates);
  }

  private static CompiledTemplates createCompiledTemplates(
      FileSetMetadata registry,
      SoyFileSetNode fileSet,
      ImmutableMap<SourceFilePath, SoyFileSupplier> filePathsToSuppliers,
      SoyTypeRegistry typeRegistry) {
    return new CompiledTemplates(
        registry.getAllTemplates().stream()
            .filter(BytecodeCompiler::isModTemplate)
            .map(BytecodeCompiler::modImplName)
            .collect(toImmutableSet()),
        new CompilingClassLoader(fileSet, filePathsToSuppliers, typeRegistry, registry));
  }

  static boolean isModTemplate(TemplateMetadata template) {
    return template.getTemplateType().getTemplateKind() == TemplateType.TemplateKind.DELTEMPLATE ||
           template.getTemplateType().isModifiable() || 
           template.getTemplateType().isModifying();
  }

  private static String namespaceFromTemplateName(String templateName) {
    return templateName.substring(0, templateName.lastIndexOf("."));
  }

  private static String modImplName(TemplateMetadata template) {
    return template.getTemplateName() +
        (template.getTemplateType().isModifiable() && 
         !NamespaceExemptions.isKnownDuplicateNamespace(namespaceFromTemplateName(template.getTemplateName())) 
            ? CompiledTemplateMetadata.DEFAULT_IMPL_JBC_CLASS_SUFFIX 
            : ".render" + CompiledTemplateMetadata.DEFAULT_IMPL_JBC_CLASS_SUFFIX);
  }

  public static void compileToJar(
      SoyFileSetNode fileSet,
      ErrorReporter reporter,
      SoyTypeRegistry typeRegistry,
      ByteSink sink,
      PartialFileSetMetadata fileSetMetadata) throws IOException {
    try (SoyJarFileWriter writer = new SoyJarFileWriter(sink.openStream())) {
      Set<String> modTemplates = new TreeSet<>();
      SortedMap<String, PluginRuntimeInstanceInfo> mergedInstanceIndex = new TreeMap<>();

      compileTemplates(
          fileSet,
          reporter,
          typeRegistry,
          createCompilerListener(writer, modTemplates, mergedInstanceIndex),
          fileSetMetadata);
      
      writeModTemplates(writer, modTemplates);
      writePluginInstances(writer, fileSet, mergedInstanceIndex);
    }
  }

  private static CompilerListener<Void, IOException> createCompilerListener(
      SoyJarFileWriter writer, Set<String> modTemplates, SortedMap<String, PluginRuntimeInstanceInfo> mergedInstanceIndex) {
    return new CompilerListener<Void, IOException>() {
      @Override
      void onCompile(ClassData clazz) throws IOException {
        writer.writeEntry(clazz.type().internalName() + ".class", ByteSource.wrap(clazz.data()));
      }

      @Override
      void onCompileModifiableTemplate(String name) {
        modTemplates.add(name);
      }

      @Override
      void onFunctionCallFound(FunctionNode fnNode) {
        if (fnNode.getSoyFunction() instanceof SoyJavaSourceFunction) {
          Set<String> instances = PluginAnalyzer.analyze((SoyJavaSourceFunction) fnNode.getSoyFunction(), fnNode.numChildren())
              .pluginInstanceNames();
          if (!instances.isEmpty()) {
            mergedInstanceIndex.merge(fnNode.getStaticFunctionName(),
                PluginRuntimeInstanceInfo.builder()
                    .setPluginName(fnNode.getStaticFunctionName())
                    .setInstanceClassName(Iterables.getOnlyElement(instances))
                    .addSourceLocation(fnNode.getSourceLocation().toString())
                    .build(),
                PluginRuntimeInstanceInfo::merge);
          }
        }
      }
    };
  }

  private static void writeModTemplates(SoyJarFileWriter writer, Set<String> modTemplates) throws IOException {
    if (!modTemplates.isEmpty()) {
      String delData = Joiner.on('\n').join(modTemplates);
      writer.writeEntry(Names.META_INF_DELTEMPLATE_PATH, ByteSource.wrap(delData.getBytes(UTF_8)));
    }
  }

  private static void writePluginInstances(SoyJarFileWriter writer, SoyFileSetNode fileSet, 
                                            SortedMap<String, PluginRuntimeInstanceInfo> mergedInstanceIndex) throws IOException {
    fileSet.getChildren().stream()
        .flatMap(f -> f.getExterns().stream())
        .filter(e -> e.getJavaImpl().isPresent())
        .map(e -> e.getJavaImpl().get())
        .filter(j -> !j.isStatic())
        .map(j -> PluginRuntimeInstanceInfo.builder()
            .setPluginName(j.className())
            .setInstanceClassName(j.className())
            .addSourceLocation(j.getSourceLocation().toString())
            .build())
        .forEach(i -> mergedInstanceIndex.merge(i.pluginName(), i, PluginRuntimeInstanceInfo::merge));

    if (!mergedInstanceIndex.isEmpty()) {
      writer.writeEntry(Names.META_INF_PLUGIN_PATH, PluginRuntimeInstanceInfo.serialize(mergedInstanceIndex.values()));
    }
  }

  public static void writeSrcJar(
      SoyFileSetNode soyFileSet, ImmutableMap<SourceFilePath, SoyFileSupplier> files, ByteSink sink)
      throws IOException {
    try (SoyJarFileWriter writer = new SoyJarFileWriter(sink.openStream())) {
      for (SoyFileNode file : soyFileSet.getChildren()) {
        String namespace = file.getNamespace();
        String fileName = file.getFileName();
        writer.writeEntry(Names.javaFileName(namespace, fileName), files.get(file.getFilePath()).asCharSource().asByteSource(UTF_8));
      }
    }
  }

  private abstract static class CompilerListener<T, E extends Throwable> {
    abstract void onCompile(ClassData newClass) throws E;

    void onCompileModifiableTemplate(String name) {}

    void onCompileTemplate(String name) {}

    void onFunctionCallFound(FunctionNode function) {}

    T getResult() {
      return null;
    }
  }

  private static <T, E extends Throwable> T compileTemplates(
      SoyFileSetNode fileSet,
      ErrorReporter errorReporter,
      SoyTypeRegistry typeRegistry,
      CompilerListener<T, E> listener,
      PartialFileSetMetadata fileSetMetadata) throws E {
    JavaSourceFunctionCompiler javaSourceFunctionCompiler =
        new JavaSourceFunctionCompiler(typeRegistry, errorReporter);
    for (SoyFileNode file : fileSet.getChildren()) {
      for (ClassData clazz : new SoyFileCompiler(file, javaSourceFunctionCompiler, fileSetMetadata).compile()) {
        if (Flags.DEBUG) {
          clazz.checkClass();
        }
        listener.onCompile(clazz);
      }
      for (TemplateNode template : file.getTemplates()) {
        TemplateMetadata metadata = TemplateMetadata.fromTemplate(template);
        if (isModTemplate(metadata)) {
          listener.onCompileModifiableTemplate(modImplName(metadata));
        } else {
          listener.onCompileTemplate(template.getTemplateName());
        }
        for (FunctionNode fnNode : SoyTreeUtils.getAllNodesOfType(template, FunctionNode.class)) {
          listener.onFunctionCallFound(fnNode);
        }
      }
    }
    return listener.getResult();
  }

  private BytecodeCompiler() {}
}
