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
    VarDefn defn = varRef.getDefnDecl();
    if (defn != null && defn.hasType()) {
      if (BAD_SOY_TYPE_KINDS.contains(defn.type().getKind())) {
        errorReporter.report(
            varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
      }
    }

    if (varRef.getType().getKind() == SoyType.Kind.PROTO_TYPE
        && !(varRef.getParent() == null
            || varRef.getParent().getKind() == ExprNode.Kind.FIELD_ACCESS_NODE
            || varRef.getParent().getKind() == ExprNode.Kind.FUNCTION_NODE)) {
      // This is for imports like:
      // import {MyMessage} from 'path/to/my/proto/file.proto';
      // These are only allowed in:
      // - Proto init calls (i.e. `MyMessage()`). In this case, the parent is null (the VarRefNode
      //   is the `nameExpr` on the FunctionNode).
      // - Field accesses, like `MyMessage.MySubMessage`
      // - `ve_def` data proto type parameters (i.e. `ve_def('MyVe', 123, MyMessage)`). However,
      //   this allows any FunctionNode, not just `ve_def`, to prevent duplicate/confusing errors on
      //   things like misspelling `ve_def`. Function parameters are type checked elsewhere, which
      //   which will report any issues.
      errorReporter.report(
          varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
    } else if (varRef.getType().getKind() == SoyType.Kind.PROTO_EXTENSION
        && varRef.getParent().getKind() != ExprNode.Kind.METHOD_CALL_NODE) {
      // This is for imports like:
      // import {TopLevelEnum} from 'path/to/my/proto/file.proto';
      // These are only allowed as the parameter to the `getExtension`, `getReadonlyExtension` and
      // `hasExtension` proto methods. However, this allows any MethodCallNode to prevent
      // duplicate/confusing errors on things like misspelling one of the method names. Method
      // parameters are type checked elsewhere, which which will report any issues.
      errorReporter.report(
          varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
    } else if (varRef.getType().getKind() == SoyType.Kind.CSS_TYPE
        && varRef.getParent().getKind() != ExprNode.Kind.FIELD_ACCESS_NODE) {
      // This is for imports like:
      // import {classes} from 'path/to/my/css/file/css';
      // These are only allowed in field accesses, like `classes.myStyleClassName`
      errorReporter.report(
          varRef.getSourceLocation(), ILLEGAL_TYPE_OF_VARIABLE, varRef.toSourceString());
    }
  }
}
