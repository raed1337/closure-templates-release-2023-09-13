
/*
 * Copyright 2021 Google Inc.
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

import com.google.common.collect.ImmutableSet;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.types.SoyType;

/** Reports errors for illegal symbol references. */
@RunAfter({ResolveExpressionTypesPass.class, MoreCallValidationsPass.class})
final class CheckValidVarrefsPass implements CompilerFilePass {

  private static final ImmutableSet<SoyType.Kind> BAD_SOY_TYPE_KINDS =
      ImmutableSet.of(
          SoyType.Kind.FUNCTION,
          SoyType.Kind.TEMPLATE_MODULE,
          SoyType.Kind.PROTO_ENUM_TYPE,
          SoyType.Kind.PROTO_MODULE);

  private static final SoyErrorKind ILLEGAL_TYPE_OF_VARIABLE =
      SoyErrorKind.of("Illegal use of symbol ''{0}''.");

  private final ErrorReporter errorReporter;

  CheckValidVarrefsPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator idGenerator) {
    SoyTreeUtils.allNodesOfType(file, VarRefNode.class).forEach(this::checkVarRef);
  }

  private void checkVarRef(VarRefNode varRef) {
    validateBadSoyTypes(varRef);
    validateProtoType(varRef);
    validateProtoExtension(varRef);
    validateCssType(varRef);
  }

  private void validateBadSoyTypes(VarRefNode varRef) {
    VarDefn defn = varRef.getDefnDecl();
    if (defn != null && defn.hasType()) {
      if (BAD_SOY_TYPE_KINDS.contains(defn.type().getKind())) {
        errorReporter.report(
            varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
      }
    }
  }

  private void validateProtoType(VarRefNode varRef) {
    if (varRef.getType().getKind() == SoyType.Kind.PROTO_TYPE
        && !(varRef.getParent() == null
            || varRef.getParent().getKind() == ExprNode.Kind.FIELD_ACCESS_NODE
            || varRef.getParent().getKind() == ExprNode.Kind.FUNCTION_NODE)) {
      errorReporter.report(
          varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
    }
  }

  private void validateProtoExtension(VarRefNode varRef) {
    if (varRef.getType().getKind() == SoyType.Kind.PROTO_EXTENSION
        && varRef.getParent().getKind() != ExprNode.Kind.METHOD_CALL_NODE) {
      errorReporter.report(
          varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
    }
  }

  private void validateCssType(VarRefNode varRef) {
    if (varRef.getType().getKind() == SoyType.Kind.CSS_TYPE
        && varRef.getParent().getKind() != ExprNode.Kind.FIELD_ACCESS_NODE) {
      errorReporter.report(
          varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
    }
  }
}
