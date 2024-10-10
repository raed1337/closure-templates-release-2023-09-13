
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

import static com.google.template.soy.soytree.TemplateDelegateNodeBuilder.isDeltemplateTemplateName;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.ForOverride;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.Node;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.AbstractExprNodeVisitor;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.ParentExprNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.ListComprehensionNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarDefn.Kind;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.ConstNode;
import com.google.template.soy.soytree.ExternNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.ImportNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.LetValueNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.defn.ExternVar;
import com.google.template.soy.soytree.defn.ImportedVar;
import com.google.template.soy.soytree.defn.TemplateHeaderVarDefn;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Combined soy node and expression node visitor that always has an up-to-date {@link
 * LocalVariables} available at every node visit.
 */
final class LocalVariablesNodeVisitor {

  private final NodeVisitor nodeVisitor;

  public LocalVariablesNodeVisitor(ExprVisitor exprVisitor) {
    this(new NodeVisitor() {
      @Override
      protected ExprVisitor getExprVisitor() {
        return exprVisitor;
      }
    });
  }

  public LocalVariablesNodeVisitor(NodeVisitor nodeVisitor) {
    this.nodeVisitor = nodeVisitor;
  }

  public void exec(SoyFileNode file) {
    nodeVisitor.exec(file);
  }

  static final class LocalVariables {

    private static final SoyErrorKind VARIABLE_ALREADY_DEFINED =
        SoyErrorKind.of("{0} ''{1}'' conflicts with symbol defined at {2}.");

    private ErrorReporter errorReporter;
    private final Deque<Map<String, VarDefn>> currentScope = new ArrayDeque<>();

    void enterScope() {
      currentScope.push(new LinkedHashMap<>());
    }

    void exitScope() {
      currentScope.pop();
    }

    VarDefn lookup(VarRefNode varRef) {
      VarDefn defn = lookup(varRef.getName());
      if (defn != null && varRef.originallyLeadingDot() && defn.kind() != Kind.TEMPLATE) {
        return null;
      }
      return defn;
    }

    VarDefn lookup(String name) {
      return currentScope.stream()
          .map(scope -> scope.get(name))
          .filter(Objects::nonNull)
          .findFirst()
          .orElse(null);
    }

    private boolean check(VarDefn defn, Node definingNode) {
      String refName = defn.refName();
      VarDefn preexisting = lookup(refName);
      if (preexisting != null) {
        reportVariableConflict(defn, definingNode, preexisting);
        return false;
      }
      return true;
    }

    private void reportVariableConflict(VarDefn defn, Node definingNode, VarDefn preexisting) {
      if (errorReporter != null && !shouldSkipError(defn, preexisting)) {
        SourceLocation defnSourceLocation =
            defn.nameLocation() == null ? definingNode.getSourceLocation() : defn.nameLocation();
        errorReporter.report(
            defnSourceLocation,
            VARIABLE_ALREADY_DEFINED,
            englishName(defn),
            defn.refName(),
            preexisting.nameLocation().toLineColumnString());
      }
    }

    void define(VarDefn defn, Node definingNode) {
      if (check(defn, definingNode)) {
        currentScope.peek().put(defn.refName(), defn);
      }
    }

    List<String> allVariablesInScope() {
      return currentScope.stream().flatMap(map -> map.keySet().stream()).collect(toList());
    }
  }

  abstract static class NodeVisitor extends AbstractSoyNodeVisitor<Void> {

    private LocalVariables localVariables;

    protected abstract LocalVariableExprVisitor getExprVisitor();

    @Nullable
    protected ErrorReporter getErrorReporter() {
      return null;
    }

    protected LocalVariables getLocalVariables() {
      return Preconditions.checkNotNull(localVariables);
    }

    @Override
    protected void visitSoyFileNode(SoyFileNode node) {
      localVariables = new LocalVariables();
      localVariables.errorReporter = getErrorReporter();
      localVariables.enterScope();
      defineTemplatesInScope(node);
      super.visitSoyFileNode(node);
      if (cleanUpFileScope()) {
        localVariables.exitScope();
        localVariables = null;
      }
    }

    private void defineTemplatesInScope(SoyFileNode node) {
      for (TemplateNode template : node.getTemplates()) {
        localVariables.define(template.asVarDefn(), template);
      }
    }

    @ForOverride
    protected boolean cleanUpFileScope() {
      return true;
    }

    @Override
    protected void visitImportNode(ImportNode node) {
      super.visitImportNode(node);
      for (ImportedVar var : node.getIdentifiers()) {
        localVariables.define(var, node);
      }
    }

    @Override
    protected void visitConstNode(ConstNode node) {
      super.visitConstNode(node);
      localVariables.define(node.getVar(), node);
    }

    @Override
    protected void visitExternNode(ExternNode node) {
      super.visitExternNode(node);
      defineExternVar(node);
    }

    private void defineExternVar(ExternNode node) {
      ExternVar var = node.getVar();
      VarDefn preexisting = localVariables.lookup(var.refName());
      if (!(preexisting instanceof ExternVar)) {
        localVariables.define(var, node);
      }
    }

    @Override
    protected void visitTemplateNode(TemplateNode node) {
      localVariables.enterScope();
      defineTemplateParams(node);
      super.visitTemplateNode(node);
      localVariables.exitScope();
    }

    private void defineTemplateParams(TemplateNode node) {
      for (TemplateHeaderVarDefn param : node.getHeaderParams()) {
        if (param.defaultValue() != null) {
          getExprVisitor().exec(param.defaultValue(), localVariables);
        }
        localVariables.define(param, node);
      }
    }

    @Override
    protected void visitPrintNode(PrintNode node) {
      visitSoyNode(node);
    }

    @Override
    protected void visitLetValueNode(LetValueNode node) {
      visitExpressions(node);
      localVariables.define(node.getVar(), node);
    }

    @Override
    protected void visitLetContentNode(LetContentNode node) {
      localVariables.enterScope();
      visitChildren(node);
      localVariables.exitScope();
      localVariables.define(node.getVar(), node);
    }

    @Override
    protected void visitForNonemptyNode(ForNonemptyNode node) {
      visitExpressions(node.getParent());
      localVariables.enterScope();
      localVariables.define(node.getVar(), node);
      if (node.getIndexVar() != null) {
        localVariables.define(node.getIndexVar(), node);
      }
      visitChildren(node);
      localVariables.exitScope();
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ExprHolderNode) {
        visitExpressions((ExprHolderNode) node);
      }
      if (node instanceof ParentSoyNode<?>) {
        handleParentSoyNode(node);
      }
    }

    private void handleParentSoyNode(SoyNode node) {
      if (node instanceof BlockNode) {
        localVariables.enterScope();
        visitChildren((BlockNode) node);
        localVariables.exitScope();
      } else {
        visitChildren((ParentSoyNode<?>) node);
      }
    }

    private void visitExpressions(ExprHolderNode node) {
      for (ExprRootNode expr : node.getExprList()) {
        getExprVisitor().exec(expr, localVariables);
      }
    }
  }

  private static final class GetFileScopeVisitor extends NodeVisitor {
    private final LocalVariableExprVisitor exprVisitor = (node, localVariables) -> null;

    @Override
    protected void visitTemplateNode(TemplateNode node) {}

    @Override
    protected boolean cleanUpFileScope() {
      return false;
    }

    @Override
    protected LocalVariableExprVisitor getExprVisitor() {
      return exprVisitor;
    }
  }

  public static LocalVariables getFileScopeVariables(SoyFileNode node) {
    GetFileScopeVisitor visitor = new GetFileScopeVisitor();
    visitor.exec(node);
    return visitor.getLocalVariables();
  }

  private static boolean shouldSkipError(VarDefn defn, VarDefn preexisting) {
    return defn.kind() == Kind.TEMPLATE
        && preexisting.kind() == Kind.TEMPLATE
        && isDeltemplateTemplateName(defn.name())
        && isDeltemplateTemplateName(preexisting.name());
  }

  private static String englishName(VarDefn varDefn) {
    switch (varDefn.kind()) {
      case PARAM:
        return "Parameter";
      case STATE:
        return "State parameter";
      case IMPORT_VAR:
        return "Imported symbol";
      case LOCAL_VAR:
      case COMPREHENSION_VAR:
        return "Local variable";
      case TEMPLATE:
        return "Template name";
      case EXTERN:
        return "Extern function";
      case CONST:
        return "Symbol";
    }
    throw new AssertionError(varDefn.kind());
  }

  interface LocalVariableExprVisitor {
    Void exec(ExprNode node, LocalVariables localVariables);
  }

  abstract static class ExprVisitor extends AbstractExprNodeVisitor<Void>
      implements LocalVariableExprVisitor {

    private LocalVariables localVariables;

    @Override
    public final Void exec(ExprNode node, LocalVariables localVariables) {
      this.localVariables = localVariables;
      exec(node);
      this.localVariables = null;
      return null;
    }

    @Override
    public Void exec(ExprNode node) {
      Preconditions.checkArgument(node instanceof ExprRootNode);
      visit(node);
      return null;
    }

    protected LocalVariables getLocalVariables() {
      return Preconditions.checkNotNull(localVariables);
    }

    @Override
    protected void visitExprRootNode(ExprRootNode node) {
      visitChildren(node);
    }

    @Override
    protected void visitExprNode(ExprNode node) {
      if (node instanceof ParentExprNode) {
        visitChildren((ParentExprNode) node);
      }
    }

    @Override
    protected void visitListComprehensionNode(ListComprehensionNode node) {
      visit(node.getListExpr());
      localVariables.enterScope();
      localVariables.define(node.getListIterVar(), node);
      if (node.getIndexVar() != null) {
        localVariables.define(node.getIndexVar(), node);
      }
      if (node.getFilterExpr() != null) {
        visit(node.getFilterExpr());
      }
      visit(node.getListItemTransformExpr());
      localVariables.exitScope();
    }
  }
}
