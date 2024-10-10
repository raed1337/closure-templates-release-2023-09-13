
/*
 * Copyright 2018 Google Inc.
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

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.parsepasses.contextautoesc.ContextualAutoescaper;
import com.google.template.soy.parsepasses.contextautoesc.Inferences;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.FileSetMetadata;
import com.google.template.soy.soytree.SoyFileNode;
import java.util.function.Supplier;

/** A shim around ContextualAutoescaper to make it conform to the pass interface. */
@RunAfter(FinalizeTemplateRegistryPass.class)
final class AutoescaperPass implements CompilerFileSetPass {

  private final ErrorReporter errorReporter;
  private final ImmutableList<? extends SoyPrintDirective> printDirectives;
  private final boolean autoescaperEnabled;
  private final Supplier<FileSetMetadata> templateRegistryFull;

  AutoescaperPass(
      ErrorReporter errorReporter,
      ImmutableList<? extends SoyPrintDirective> printDirectives,
      boolean autoescaperEnabled,
      Supplier<FileSetMetadata> templateRegistryFull) {
    this.errorReporter = errorReporter;
    this.printDirectives = printDirectives;
    this.autoescaperEnabled = autoescaperEnabled;
    this.templateRegistryFull = templateRegistryFull;
  }

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator) {
    if (hasErrors()) {
      return Result.STOP;
    }
    
    ContextualAutoescaper autoescaper = createAutoescaper();
    Inferences inferences = annotateFiles(autoescaper, sourceFiles);
    
    if (inferences == null || (autoescaperEnabled && !rewriteFiles(autoescaper, sourceFiles, idGenerator, inferences))) {
      return Result.STOP;
    }
    
    return hasErrors() ? Result.STOP : Result.CONTINUE;
  }

  private ContextualAutoescaper createAutoescaper() {
    return new ContextualAutoescaper(errorReporter, printDirectives, templateRegistryFull.get());
  }

  private boolean hasErrors() {
    return errorReporter.hasErrors();
  }

  private Inferences annotateFiles(ContextualAutoescaper autoescaper, ImmutableList<SoyFileNode> sourceFiles) {
    return autoescaper.annotate(sourceFiles);
  }

  private boolean rewriteFiles(ContextualAutoescaper autoescaper, ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator, Inferences inferences) {
    autoescaper.rewrite(sourceFiles, idGenerator, inferences);
    return !hasErrors();
  }
}
