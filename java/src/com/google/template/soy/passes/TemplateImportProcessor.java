
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

package com.google.template.soy.passes;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.soytree.FileSetMetadata;
import com.google.template.soy.soytree.ImportNode;
import com.google.template.soy.soytree.Metadata;
import com.google.template.soy.soytree.PartialFileMetadata;
import com.google.template.soy.soytree.PartialFileSetMetadata;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.defn.ImportedVar;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.TemplateImportType;
import com.google.template.soy.types.TemplateModuleImportType;
import com.google.template.soy.types.UnknownType;
import java.util.function.Supplier;

/**
 * Resolves Soy template imports; verifies that the imports are valid and populates a local template
 * registry that maps the imported symbols to their types.
 */
public final class TemplateImportProcessor implements ImportsPass.ImportProcessor {

  private final ErrorReporter errorReporter;
  private final Supplier<FileSetMetadata> registryFromDeps;
  private PartialFileSetMetadata fileSetMetadata;
  private SoyTypeRegistry typeRegistry;

  TemplateImportProcessor(ErrorReporter errorReporter, Supplier<FileSetMetadata> registryFromDeps) {
    this.registryFromDeps = registryFromDeps;
    this.errorReporter = errorReporter;
  }

  @Override
  public void init(ImmutableList<SoyFileNode> sourceFiles) {
    fileSetMetadata = Metadata.partialMetadataForAst(registryFromDeps.get(), sourceFiles);
  }

  @Override
  public void handle(SoyFileNode file, ImmutableCollection<ImportNode> imports) {
    typeRegistry = file.getSoyTypeRegistry();

    for (ImportNode anImport : imports) {
      anImport.setImportType(ImportNode.ImportType.TEMPLATE);
      if (anImport.isModuleImport()) {
        processImportedModule(anImport);
      } else {
        processImportedSymbols(anImport);
      }
    }
  }

  private void processImportedSymbols(ImportNode node) {
    PartialFileMetadata fileMetadata = fileSetMetadata.getPartialFile(SourceFilePath.create(node.getPath()));
    node.setModuleType(buildModuleType(node));
    
    for (ImportedVar symbol : node.getIdentifiers()) {
      validateAndSetSymbolType(symbol, fileMetadata, node);
    }
  }

  private void validateAndSetSymbolType(ImportedVar symbol, PartialFileMetadata fileMetadata, ImportNode node) {
    String name = symbol.getSymbol();
    boolean isTemplate = fileMetadata.hasTemplate(name);

    if (!isTemplate && !fileMetadata.hasConstant(name) && !fileMetadata.hasExtern(name)) {
      reportUnknownSymbol(symbol, node, fileMetadata);
      symbol.setType(UnknownType.getInstance());
      return;
    }

    if (isTemplate) {
      symbol.setType(typeRegistry.intern(TemplateImportType.create(templateFqn(fileMetadata, name))));
    }
  }

  private void reportUnknownSymbol(ImportedVar symbol, ImportNode node, PartialFileMetadata fileMetadata) {
    ImportsPass.reportUnknownSymbolError(
        errorReporter,
        symbol.nameLocation(),
        symbol.getSymbol(),
        node.getPath(),
        /* validSymbols= */ fileMetadata.getTemplateNames());
  }

  private static String templateFqn(PartialFileMetadata file, String name) {
    String namespace = file.getNamespace();
    return namespace.isEmpty() ? name : namespace + "." + name;
  }

  private void processImportedModule(ImportNode node) {
    Iterables.getOnlyElement(node.getIdentifiers()).setType(buildModuleType(node));
  }

  private TemplateModuleImportType buildModuleType(ImportNode node) {
    SourceFilePath path = SourceFilePath.create(node.getPath());
    PartialFileMetadata templatesPerFile = fileSetMetadata.getPartialFile(path);
    return typeRegistry.intern(
        TemplateModuleImportType.create(
            templatesPerFile.getNamespace(),
            path,
            ImmutableSet.copyOf(templatesPerFile.getConstantNames()),
            ImmutableSet.copyOf(templatesPerFile.getExternNames()),
            ImmutableSet.copyOf(templatesPerFile.getTemplateNames())));
  }

  @Override
  public boolean handlesPath(String path) {
    return fileSetMetadata.getPartialFile(SourceFilePath.create(path)) != null;
  }

  @Override
  public ImmutableCollection<String> getAllPaths() {
    return fileSetMetadata.getAllPartialFiles().stream()
        .map(f -> f.getPath().path())
        .collect(toImmutableSet());
  }
}
