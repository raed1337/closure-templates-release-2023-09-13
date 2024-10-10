
/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.base.internal.FixedIdGenerator;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.IncrementingIdGenerator;
import com.google.template.soy.base.internal.SoyFileSupplier;
import com.google.template.soy.css.CssRegistry;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.passes.PassManager;
import com.google.template.soy.shared.SoyAstCache;
import com.google.template.soy.soyparse.SoyFileParser;
import com.google.template.soy.soytree.FileSetMetadata;
import com.google.template.soy.soytree.Metadata;
import com.google.template.soy.soytree.Metadata.CompilationUnitAndKind;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.types.SoyTypeRegistry;
import java.io.IOException;
import java.io.Reader;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Static functions for parsing a set of Soy files into a {@link SoyFileSetNode}.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
@AutoValue
public abstract class SoyFileSetParser {

  /** A simple tuple for the result of a parse operation. */
  public static class ParseResult {
    private final SoyFileSetNode soyTree;
    private final Optional<FileSetMetadata> registry;
    private final CssRegistry cssRegistry;

    static ParseResult create(SoyFileSetNode soyTree, Optional<FileSetMetadata> registry, CssRegistry cssRegistry) {
      return new ParseResult(soyTree, registry, cssRegistry);
    }

    ParseResult(SoyFileSetNode soyTree, Optional<FileSetMetadata> registry, CssRegistry cssRegistry) {
      this.soyTree = soyTree;
      this.registry = registry;
      this.cssRegistry = cssRegistry;
    }

    public SoyFileSetNode fileSet() {
      return soyTree;
    }

    public final FileSetMetadata registry() {
      return registry.orElseThrow(() -> new IllegalStateException("No template registry, did you forget to check the error reporter?"));
    }

    public final CssRegistry cssRegistry() {
      return cssRegistry;
    }

    public final boolean hasRegistry() {
      return registry.isPresent();
    }
  }

  public static Builder newBuilder() {
    return new AutoValue_SoyFileSetParser.Builder();
  }

  @Nullable
  abstract SoyAstCache cache();

  public abstract ImmutableMap<SourceFilePath, SoyFileSupplier> soyFileSuppliers();

  abstract ImmutableList<CompilationUnitAndKind> compilationUnits();

  abstract PassManager passManager();

  abstract ErrorReporter errorReporter();

  public abstract SoyTypeRegistry typeRegistry();

  public abstract CssRegistry cssRegistry();

  /** Builder for {@link SoyFileSetParser}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setCache(SoyAstCache cache);
    public abstract Builder setSoyFileSuppliers(ImmutableMap<SourceFilePath, SoyFileSupplier> soyFileSuppliers);
    public abstract Builder setCompilationUnits(ImmutableList<CompilationUnitAndKind> compilationUnits);
    public abstract Builder setPassManager(PassManager passManager);
    public abstract Builder setErrorReporter(ErrorReporter errorReporter);
    public abstract Builder setTypeRegistry(SoyTypeRegistry typeRegistry);
    public abstract Builder setCssRegistry(CssRegistry cssRegistry);
    public abstract SoyFileSetParser build();
  }

  public ParseResult parse() {
    try {
      return parseWithVersions();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private ParseResult parseWithVersions() throws IOException {
    SoyFileSetNode soyTree = new SoyFileSetNode(new IncrementingIdGenerator());
    boolean filesWereSkipped = false;
    FixedIdGenerator fixedIdGenerator = new FixedIdGenerator(-1);
    
    for (SoyFileSupplier fileSupplier : soyFileSuppliers().values()) {
      SoyFileNode node = parseFile(fileSupplier, fixedIdGenerator);
      if (node == null) {
        filesWereSkipped = true;
        continue;
      }
      soyTree.addChild(SoyTreeUtils.cloneWithNewIds(node, soyTree.getNodeIdGenerator()));
    }

    if (filesWereSkipped) {
      return ParseResult.create(soyTree, Optional.empty(), CssRegistry.EMPTY);
    }

    return finalizeParsing(soyTree);
  }

  private SoyFileNode parseFile(SoyFileSupplier fileSupplier, IdGenerator fixedIdGenerator) throws IOException {
    SoyFileNode node = cache() != null ? cache().get(fileSupplier.getFilePath(), fileSupplier.getVersion()) : null;
    if (node == null) {
      node = parseSoyFileHelper(fileSupplier, fixedIdGenerator);
      if (node != null) {
        passManager().runParsePasses(node, fixedIdGenerator);
        if (cache() != null) {
          cache().put(fileSupplier.getFilePath(), fileSupplier.getVersion(), node);
        }
      }
    }
    return node;
  }

  private ParseResult finalizeParsing(SoyFileSetNode soyTree) throws IOException {
    FileSetMetadata partialRegistryForDeps = Metadata.metadataForDeps(compilationUnits(), errorReporter(), typeRegistry());
    passManager().runPasses(soyTree, partialRegistryForDeps);
    FileSetMetadata finalFileSetMetadata = passManager().getFinalTemplateRegistry();
    return ParseResult.create(soyTree, Optional.ofNullable(finalFileSetMetadata), cssRegistry());
  }

  private SoyFileNode parseSoyFileHelper(SoyFileSupplier soyFileSupplier, IdGenerator nodeIdGen) throws IOException {
    try (Reader soyFileReader = soyFileSupplier.open()) {
      String filePath = extractFilePath(soyFileSupplier);
      return new SoyFileParser(nodeIdGen, soyFileReader, SourceFilePath.create(filePath), errorReporter()).parseSoyFile();
    }
  }

  private String extractFilePath(SoyFileSupplier soyFileSupplier) {
    String filePath = soyFileSupplier.getFilePath().path();
    int lastBangIndex = filePath.lastIndexOf('!');
    return lastBangIndex != -1 ? filePath.substring(lastBangIndex + 1) : filePath;
  }
}
