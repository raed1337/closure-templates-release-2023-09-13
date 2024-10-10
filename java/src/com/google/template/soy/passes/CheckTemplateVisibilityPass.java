
/*
 * Copyright 2014 Google Inc.
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
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.soytree.FileSetMetadata;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateMetadata;
import com.google.template.soy.soytree.Visibility;
import java.util.function.Supplier;

/**
 * Visitor for checking the visibility of a template.
 */
@RunAfter(FinalizeTemplateRegistryPass.class)
final class CheckTemplateVisibilityPass implements CompilerFileSetPass {

  private static final SoyErrorKind CALLEE_NOT_VISIBLE =
      SoyErrorKind.of("{0} has {1} access in {2}.");

  private final ErrorReporter errorReporter;
  private final Supplier<FileSetMetadata> templateRegistryFull;

  CheckTemplateVisibilityPass(
      ErrorReporter errorReporter, Supplier<FileSetMetadata> templateRegistryFull) {
    this.errorReporter = errorReporter;
    this.templateRegistryFull = templateRegistryFull;
  }

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator) {
    for (SoyFileNode file : sourceFiles) {
      checkTemplateVisibility(file);
    }
    return Result.CONTINUE;
  }

  private void checkTemplateVisibility(SoyFileNode file) {
    for (TemplateLiteralNode node : SoyTreeUtils.getAllNodesOfType(file, TemplateLiteralNode.class)) {
      validateTemplateVisibility(file, node);
    }
  }

  private void validateTemplateVisibility(SoyFileNode file, TemplateLiteralNode node) {
    String calleeName = node.getResolvedName();
    TemplateMetadata definition = templateRegistryFull.get().getBasicTemplateOrElement(calleeName);
    if (definition != null && !isVisible(file, definition)) {
      reportVisibilityError(node, calleeName, definition);
    }
  }

  private void reportVisibilityError(TemplateLiteralNode node, String calleeName, TemplateMetadata definition) {
    errorReporter.report(
        node.getSourceLocation(),
        CALLEE_NOT_VISIBLE,
        calleeName,
        definition.getVisibility().getAttributeValue(),
        definition.getSourceLocation().getFilePath().path());
  }

  private static boolean isVisible(SoyFileNode calledFrom, TemplateMetadata callee) {
    return callee.getVisibility() != Visibility.PRIVATE
        || callee.getSourceLocation().getFilePath().equals(calledFrom.getFilePath());
  }
}
