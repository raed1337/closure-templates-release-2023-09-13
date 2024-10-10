
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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.exprtree.CallableExprBuilder;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.Kind;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.passes.LocalVariablesNodeVisitor.ExprVisitor;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.types.SoyType;
import java.util.Optional;

/**
 * Populates the {@link FunctionNode} and {@link PrintDirectiveNode} with their plugin instances
 * based on a registry of such names. Also resolves functions that are imported symbols (e.g. for
 * proto init).
 */
@RunBefore(SoyConformancePass.class)
final class ResolvePluginsPass implements CompilerFilePass {

  private final PluginResolver resolver;

  ResolvePluginsPass(PluginResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    new LocalVariablesNodeVisitor(new Visitor()).exec(file);
  }

  private class Visitor extends LocalVariablesNodeVisitor.NodeVisitor {

    private final LocalVariablesNodeVisitor.ExprVisitor exprVisitor = new LocalVariablesNodeVisitor.ExprVisitor() {
      @Override
      protected void visitFunctionNode(FunctionNode node) {
        visitChildren(node);
        if (node.isResolved()) {
          return;
        }
        if (!node.hasStaticName()) {
          setSoyFunctionForNameExpr(node);
          return;
        }
        handleStaticFunctionName(node);
      }

      private void handleStaticFunctionName(FunctionNode node) {
        VarDefn varDefn = getLocalVariables().lookup(node.getStaticFunctionName());
        boolean varDefnIsTemplate = varDefn != null && varDefn.kind() == VarDefn.Kind.TEMPLATE;

        if (varDefnIsTemplate && trySetFunction(node)) {
          return;
        }

        if (varDefn != null) {
          replaceWithFunctionNameExpression(node, varDefn);
          return;
        }

        trySetFunction(node);
      }

      private void replaceWithFunctionNameExpression(FunctionNode node, VarDefn varDefn) {
        VarRefNode functionRef = new VarRefNode(node.getStaticFunctionName(), node.getIdentifier().location(), varDefn);
        FunctionNode newFunct = CallableExprBuilder.builder(node)
            .setIdentifier(null)
            .setFunctionExpr(functionRef)
            .buildFunction();
        setSoyFunctionForNameExpr(newFunct);
        node.getParent().replaceChild(node, newFunct);
      }

      @CanIgnoreReturnValue
      private boolean trySetFunction(FunctionNode node) {
        Object impl = resolver.lookupSoyFunction(node.getStaticFunctionName(), node.numChildren(), node.getSourceLocation());
        if (impl != null) {
          node.setSoyFunction(impl);
          return true;
        }
        return false;
      }
    };

    @Override
    protected ExprVisitor getExprVisitor() {
      return exprVisitor;
    }

    @Override
    protected void visitPrintDirectiveNode(PrintDirectiveNode directiveNode) {
      super.visitPrintDirectiveNode(directiveNode);
      String name = directiveNode.getName();
      Optional<SoySourceFunction> aliasedFunction = resolver.getFunctionCallableAsPrintDirective(name, directiveNode.getSourceLocation());
      if (aliasedFunction.isPresent()) {
        directiveNode.setPrintDirectiveFunction(aliasedFunction.get());
      } else {
        directiveNode.setPrintDirective(resolver.lookupPrintDirective(name, directiveNode.getExprList().size(), directiveNode.getSourceLocation()));
      }
    }
  }

  static void setSoyFunctionForNameExpr(FunctionNode function) {
    Object fct = getSoyFunctionForExpr(function.getNameExpr());
    if (fct != null) {
      function.setSoyFunction(BuiltinFunction.PROTO_INIT);
    }
  }

  private static Object getSoyFunctionForExpr(ExprNode expr) {
    if (expr.getKind() == Kind.VAR_REF_NODE && ((VarRefNode) expr).hasType() && expr.getType().getKind() == SoyType.Kind.PROTO_TYPE) {
      return BuiltinFunction.PROTO_INIT;
    }
    return null;
  }
}
