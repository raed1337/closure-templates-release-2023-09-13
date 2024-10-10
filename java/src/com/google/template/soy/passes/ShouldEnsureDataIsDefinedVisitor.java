
/*
 * Copyright 2012 Google Inc.
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

import com.google.template.soy.base.internal.TemplateContentKind;
import com.google.template.soy.basetree.AbstractNodeVisitor;
import com.google.template.soy.basetree.Node;
import com.google.template.soy.basetree.ParentNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.defn.TemplateParam;

/**
 * Visitor for determining whether a template needs to ensure that its data is defined.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public final class ShouldEnsureDataIsDefinedVisitor {

  /** Runs this pass on the given template. */
  public boolean exec(TemplateNode template) {
    if (hasRequiredParams(template)) {
      return false;
    }
    
    boolean hasOptional = hasOptionalParams(template);
    if (hasOptional) {
      return true;
    }
    
    return checkForDataReferences(template);
  }
  
  private boolean hasRequiredParams(TemplateNode template) {
    for (TemplateParam param : template.getParams()) {
      if (param.isImplicit() && !(template.getTemplateContentKind() instanceof TemplateContentKind.ElementContentKind)) {
        continue;
      }
      if (param.isRequired()) {
        return true;
      }
    }
    return false;
  }

  private boolean hasOptionalParams(TemplateNode template) {
    for (TemplateParam param : template.getParams()) {
      if (!param.isRequired()) {
        return true;
      }
    }
    return false;
  }
  
  private boolean checkForDataReferences(TemplateNode template) {
    return new DataReferenceVisitor().exec(template);
  }

  private static class DataReferenceVisitor extends AbstractNodeVisitor<Node, Boolean> {
    boolean shouldEnsureDataIsDefined;

    @Override
    public Boolean exec(Node node) {
      visit(node);
      return shouldEnsureDataIsDefined;
    }

    @Override
    public void visit(Node node) {
      if (node instanceof VarRefNode) {
        checkVarRefNode((VarRefNode) node);
      } else if (node instanceof CallNode) {
        checkCallNode((CallNode) node);
      } else if (node instanceof ParentNode) {
        checkParentNode((ParentNode<?>) node);
      } else if (node instanceof ExprHolderNode) {
        checkExprHolderNode((ExprHolderNode) node);
      }
    }

    private void checkVarRefNode(VarRefNode varRefNode) {
      VarDefn var = varRefNode.getDefnDecl();
      if ((var.kind() == VarDefn.Kind.PARAM)
          && !((TemplateParam) var).isImplicit()
          && (var.kind() != VarDefn.Kind.PARAM // a soydoc param -> not ij
              || !var.isInjected())) { // an {@param but not {@inject
        shouldEnsureDataIsDefined = true;
      }
    }

    private void checkCallNode(CallNode callNode) {
      if (callNode.isPassingAllData()) {
        shouldEnsureDataIsDefined = true;
      }
    }

    private void checkParentNode(ParentNode<?> parentNode) {
      for (Node child : parentNode.getChildren()) {
        visit(child);
        if (shouldEnsureDataIsDefined) {
          return;
        }
      }
    }

    private void checkExprHolderNode(ExprHolderNode exprHolderNode) {
      for (ExprRootNode expr : exprHolderNode.getExprList()) {
        visit(expr);
        if (shouldEnsureDataIsDefined) {
          return;
        }
      }
    }
  }
}
