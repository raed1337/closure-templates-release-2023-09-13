
/*
 * Copyright 2013 Google Inc.
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
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.error.SoyErrors;
import com.google.template.soy.exprtree.GlobalNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.passes.LocalVariablesNodeVisitor.ExprVisitor;
import com.google.template.soy.passes.LocalVariablesNodeVisitor.LocalVariables;
import com.google.template.soy.soytree.SoyFileNode;

/**
 * Visitor which resolves all variable and parameter references to point to the corresponding
 * declaration object.
 */
final class ResolveNamesPass implements CompilerFilePass {

  private static final SoyErrorKind GLOBAL_MATCHES_VARIABLE =
      SoyErrorKind.of(
          "Found global reference aliasing a local variable ''{0}'', did you mean ''${0}''?");

  private static final SoyErrorKind UNKNOWN_VARIABLE =
      SoyErrorKind.of("Unknown variable.{0}", StyleAllowance.NO_PUNCTUATION);

  private final ErrorReporter errorReporter;

  public ResolveNamesPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    ResolveNamesExprVisitor exprVisitor = new ResolveNamesExprVisitor();
    new LocalVariablesNodeVisitor(
            new LocalVariablesNodeVisitor.NodeVisitor() {
              @Override
              protected ExprVisitor getExprVisitor() {
                return exprVisitor;
              }

              @Override
              protected ErrorReporter getErrorReporter() {
                return errorReporter;
              }
            })
        .exec(file);
  }

  // -----------------------------------------------------------------------------------------------
  // Expr visitor.

  /**
   * Visitor which resolves all variable and parameter references in expressions to point to the
   * corresponding declaration object.
   */
  private final class ResolveNamesExprVisitor extends LocalVariablesNodeVisitor.ExprVisitor {

    @Override
    protected void visitGlobalNode(GlobalNode node) {
      checkGlobalNodeForTypo(node);
    }

    private void checkGlobalNodeForTypo(GlobalNode node) {
      String globalName = node.getName();
      LocalVariables localVariables = getLocalVariables();
      VarDefn varDefn = localVariables.lookup("$" + globalName);
      if (varDefn != null) {
        errorReporter.report(node.getSourceLocation(), GLOBAL_MATCHES_VARIABLE, globalName);
        GlobalNode.replaceExprWithError(node);
      }
    }

    @Override
    protected void visitVarRefNode(VarRefNode varRef) {
      resolveVarRef(varRef);
    }

    private void resolveVarRef(VarRefNode varRef) {
      if (varRef.getDefnDecl() != null) {
        return;
      }
      LocalVariables localVariables = getLocalVariables();
      VarDefn varDefn = localVariables.lookup(varRef.getName());
      if (varDefn == null) {
        reportUnknownVariable(varRef, localVariables);
      } else {
        varRef.setDefn(varDefn);
      }
    }

    private void reportUnknownVariable(VarRefNode varRef, LocalVariables localVariables) {
      errorReporter.report(
          varRef.getSourceLocation(),
          UNKNOWN_VARIABLE,
          SoyErrors.getDidYouMeanMessage(
              localVariables.allVariablesInScope(), varRef.getOriginalName()));
      GlobalNode.replaceExprWithError(varRef);
    }
  }
}
