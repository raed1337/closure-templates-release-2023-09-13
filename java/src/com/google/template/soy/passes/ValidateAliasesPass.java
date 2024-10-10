
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

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.soytree.AliasDeclaration;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.types.SoyProtoEnumType;
import com.google.template.soy.types.SoyProtoType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.TypeRegistries;
import com.google.template.soy.types.TypeRegistry;

/**
 * Checks that aliases don't conflict with things that can be aliased (or their namespace prefixes).
 */
final class ValidateAliasesPass implements CompilerFilePass {

  private static final SoyErrorKind ALIAS_CONFLICTS_WITH_TYPE_NAME =
      SoyErrorKind.of("Alias ''{0}'' conflicts with a type of the same name.");

  private final ErrorReporter errorReporter;

  ValidateAliasesPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    TypeRegistry registry = TypeRegistries.builtinTypeRegistry();
    for (AliasDeclaration alias : file.getAliasDeclarations()) {
      validateAliasConflict(alias, registry);
    }
  }

  private void validateAliasConflict(AliasDeclaration alias, TypeRegistry registry) {
    SoyType type = registry.getType(alias.alias().identifier());
    // When running with a dummy type provider that parses all types as unknown, ignore that.
    if (type != null && type.getKind() != SoyType.Kind.UNKNOWN) {
      // Temporarily, while we migrate all proto aliases to imports, ignore aliases that are
      // identical to proto imports. They don't conflict and Tricorder will remove the aliases.
      if (!alias.namespace().identifier().equals(getFqProtoName(type))) {
        errorReporter.report(
            alias.alias().location(), ALIAS_CONFLICTS_WITH_TYPE_NAME, alias.alias());
      }
    }
  }

  private static String getFqProtoName(SoyType type) {
    if (type instanceof SoyProtoType) {
      return ((SoyProtoType) type).getDescriptor().getFullName();
    }
    if (type instanceof SoyProtoEnumType) {
      return ((SoyProtoEnumType) type).getDescriptor().getFullName();
    }
    return null;
  }
}
