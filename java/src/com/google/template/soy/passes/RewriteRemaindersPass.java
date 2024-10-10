
/*
 * Copyright 2011 Google Inc.
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

import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.MsgPluralNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;

/**
 * Visitor for finding calls to {@code remainder} and validating/replacing them the appropriate
 * expression.
 *
 * <p>The {@code remainder()} function must satisfy the following rules:
 *
 * <ul>
 *   <li>It may only be used in the context of a {@code plural} inside a {@code msg} with a non-zero
 *       {@code offset}
 *   <li>It must used the same expression as its corresponding {@code plural}.
 *   <li>If it is used as the root expression in a print node, then there can be no phname
 * </ul>
 */
@RunAfter(ResolvePluginsPass.class)
final class RewriteRemaindersPass implements CompilerFilePass {

  private static final SoyErrorKind REMAINDER_OUTSIDE_PLURAL =
      SoyErrorKind.of("Special function ''remainder'' is for use in plural messages only.");
  private static final SoyErrorKind REMAINDER_PLURAL_EXPR_MISMATCH =
      SoyErrorKind.of("Argument to ''remainder'' must be the same as the ''plural'' variable.");
  private static final SoyErrorKind REMAINDER_UNNECESSARY_AT_OFFSET_0 =
      SoyErrorKind.of("''remainder'' is unnecessary since offset=0.");
  private static final SoyErrorKind REMAINDER_WITH_PHNAME =
      SoyErrorKind.of("Special function ''remainder'' cannot be used with ''phname''.");

  private final ErrorReporter errorReporter;

  public RewriteRemaindersPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, com.google.template.soy.base.internal.IdGenerator nodeIdGen) {
    new Visitor().exec(file);
  }

  private final class Visitor extends AbstractSoyNodeVisitor<Void> {
    /** The MsgPluralNode most recently visited. */
    private MsgPluralNode currPluralNode;

    private PrintNode currPrintNode;

    @Override
    protected void visitPrintNode(PrintNode node) {
      currPrintNode = node;
      super.visitPrintNode(node);
      currPrintNode = null;
    }

    @Override
    protected void visitMsgPluralNode(MsgPluralNode node) {
      MsgPluralNode prev = currPluralNode;
      currPluralNode = node;
      visitChildren(node);
      currPluralNode = prev;
    }

    private void rewriteRemainder(FunctionNode functionNode) {
      if (currPluralNode == null) {
        reportErrorAndRemove(functionNode, REMAINDER_OUTSIDE_PLURAL);
        return;
      }
      if (functionNode.numChildren() != 1) {
        removeBadRemainder(functionNode);
        return;
      }
      if (!isEquivalentToPlural(functionNode)) {
        reportErrorAndRemove(functionNode, REMAINDER_PLURAL_EXPR_MISMATCH);
        return;
      }
      if (currPluralNode.getOffset() == 0) {
        reportErrorAndRemove(functionNode, REMAINDER_UNNECESSARY_AT_OFFSET_0);
        return;
      }
      if (isInvalidPhnameUsage(functionNode)) {
        reportErrorAndRemove(functionNode, REMAINDER_WITH_PHNAME);
        return;
      }
      rewriteFunctionNode(functionNode);
    }

    private boolean isEquivalentToPlural(FunctionNode functionNode) {
      return new ExprEquivalence()
          .equivalent(functionNode.getChild(0), currPluralNode.getExpr().getRoot());
    }

    private boolean isInvalidPhnameUsage(FunctionNode functionNode) {
      return currPrintNode != null
          && currPrintNode.getExpr() == functionNode.getParent()
          && currPrintNode.getPlaceholder().userSuppliedName().isPresent();
    }

    private void reportErrorAndRemove(FunctionNode functionNode, SoyErrorKind errorKind) {
      errorReporter.report(functionNode.getSourceLocation(), errorKind);
      removeBadRemainder(functionNode);
    }

    private void rewriteFunctionNode(FunctionNode functionNode) {
      ExprNode plural = currPluralNode.getExpr().getRoot().copy(new CopyState());
      ExprNode offset =
          new IntegerNode(currPluralNode.getOffset(), functionNode.getSourceLocation());
      ExprNode remainder =
          Operator.MINUS.createNode(
              plural.getSourceLocation().extend(offset.getSourceLocation()),
              /*operatorLocation=*/ functionNode.getSourceLocation(),
              plural,
              offset);
      functionNode.getParent().replaceChild(functionNode, remainder);
    }

    // -----------------------------------------------------------------------------------------------
    // Fallback implementation.

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ParentSoyNode<?>) {
        visitChildren((ParentSoyNode<?>) node);
      }
      if (node instanceof ExprHolderNode) {
        for (ExprNode expr : ((ExprHolderNode) node).getExprList()) {
          SoyTreeUtils.allFunctionInvocations(expr, BuiltinFunction.REMAINDER)
              .forEach(this::rewriteRemainder);
        }
      }
    }
  }

  private static void removeBadRemainder(FunctionNode functionNode) {
    functionNode
        .getParent()
        .replaceChild(functionNode, new IntegerNode(0, functionNode.getSourceLocation()));
  }
}
