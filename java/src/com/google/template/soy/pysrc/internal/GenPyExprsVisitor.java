
/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.pysrc.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.base.internal.QuoteStyle;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.msgs.internal.MsgUtils;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyExprUtils;
import com.google.template.soy.pysrc.restricted.PyStringExpr;
import com.google.template.soy.pysrc.restricted.SoyPySrcPrintDirective;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfElseNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Visitor for generating Python expressions for parse tree nodes.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public final class GenPyExprsVisitor extends AbstractSoyNodeVisitor<List<PyExpr>> {
  private static final SoyErrorKind UNKNOWN_SOY_PY_SRC_PRINT_DIRECTIVE =
      SoyErrorKind.of("Unknown SoyPySrcPrintDirective ''{0}''.");

  /** Injectable factory for creating an instance of this class. */
  public static final class GenPyExprsVisitorFactory {
    private final IsComputableAsPyExprVisitor isComputableAsPyExprVisitor;
    private final PythonValueFactoryImpl pluginValueFactory;
    private final Supplier<GenPyCallExprVisitor> genPyCallExprVisitor;

    GenPyExprsVisitorFactory(
        IsComputableAsPyExprVisitor isComputableAsPyExprVisitor,
        PythonValueFactoryImpl pluginValueFactory,
        Supplier<GenPyCallExprVisitor> genPyCallExprVisitor) {
      this.isComputableAsPyExprVisitor = isComputableAsPyExprVisitor;
      this.pluginValueFactory = pluginValueFactory;
      this.genPyCallExprVisitor = genPyCallExprVisitor;
    }

    public GenPyExprsVisitor create(LocalVariableStack localVarExprs, ErrorReporter errorReporter) {
      return new GenPyExprsVisitor(
          isComputableAsPyExprVisitor,
          this,
          genPyCallExprVisitor.get(),
          pluginValueFactory,
          localVarExprs,
          errorReporter);
    }
  }

  private final IsComputableAsPyExprVisitor isComputableAsPyExprVisitor;
  private final GenPyExprsVisitorFactory genPyExprsVisitorFactory;
  private final PythonValueFactoryImpl pluginValueFactory;
  private final GenPyCallExprVisitor genPyCallExprVisitor;
  private final LocalVariableStack localVarExprs;
  private List<PyExpr> pyExprs;
  private final ErrorReporter errorReporter;

  GenPyExprsVisitor(
      IsComputableAsPyExprVisitor isComputableAsPyExprVisitor,
      GenPyExprsVisitorFactory genPyExprsVisitorFactory,
      GenPyCallExprVisitor genPyCallExprVisitor,
      PythonValueFactoryImpl pluginValueFactory,
      LocalVariableStack localVarExprs,
      ErrorReporter errorReporter) {
    this.isComputableAsPyExprVisitor = isComputableAsPyExprVisitor;
    this.genPyExprsVisitorFactory = genPyExprsVisitorFactory;
    this.genPyCallExprVisitor = genPyCallExprVisitor;
    this.pluginValueFactory = pluginValueFactory;
    this.localVarExprs = localVarExprs;
    this.errorReporter = errorReporter;
  }

  @Override
  public List<PyExpr> exec(SoyNode node) {
    Preconditions.checkArgument(isComputableAsPyExprVisitor.exec(node));
    pyExprs = new ArrayList<>();
    visit(node);
    return pyExprs;
  }

  List<PyExpr> execOnChildren(ParentSoyNode<?> node) {
    Preconditions.checkArgument(isComputableAsPyExprVisitor.execOnChildren(node));
    pyExprs = new ArrayList<>();
    visitChildren(node);
    return pyExprs;
  }

  @Override
  protected void visitRawTextNode(RawTextNode node) {
    String exprText =
        BaseUtils.escapeToWrappedSoyString(node.getRawText(), false, QuoteStyle.SINGLE);
    pyExprs.add(new PyStringExpr(exprText));
  }

  @Override
  protected void visitPrintNode(PrintNode node) {
    PyExpr pyExpr = processPrintNode(node);
    pyExprs.add(pyExpr);
  }

  private PyExpr processPrintNode(PrintNode node) {
    TranslateToPyExprVisitor translator =
        new TranslateToPyExprVisitor(localVarExprs, pluginValueFactory, node, errorReporter);
    PyExpr pyExpr = translator.exec(node.getExpr());

    for (PrintDirectiveNode directiveNode : node.getChildren()) {
      processPrintDirective(directiveNode, translator, pyExpr);
    }

    return pyExpr;
  }

  private void processPrintDirective(PrintDirectiveNode directiveNode, TranslateToPyExprVisitor translator, PyExpr pyExpr) {
    SoyPrintDirective directive = directiveNode.getPrintDirective();
    if (!(directive instanceof SoyPySrcPrintDirective)) {
      errorReporter.report(directiveNode.getSourceLocation(), UNKNOWN_SOY_PY_SRC_PRINT_DIRECTIVE, directiveNode.getName());
      return;
    }

    List<ExprRootNode> args = directiveNode.getArgs();
    List<PyExpr> argsPyExprs = new ArrayList<>(args.size());
    for (ExprRootNode arg : args) {
      argsPyExprs.add(translator.exec(arg));
    }

    pyExpr = ((SoyPySrcPrintDirective) directive).applyForPySrc(pyExpr, argsPyExprs);
  }

  @Override
  protected void visitMsgFallbackGroupNode(MsgFallbackGroupNode node) {
    PyExpr msg = generateMsgFunc(node.getMsg(), false);
    String pyExprText = buildMsgFallbackGroupExpression(node, msg);
    msg = new PyStringExpr(pyExprText, PyExprUtils.pyPrecedenceForOperator(Operator.CONDITIONAL));

    applyEscapingDirectives(node, msg);
    pyExprs.add(msg);
  }

  private String buildMsgFallbackGroupExpression(MsgFallbackGroupNode node, PyExpr msg) {
    String ternaryExpression = "(%s) if (translator_impl.is_msg_available(%d) or not translator_impl.is_msg_available(%d)) else (%s)";
    long msgId = MsgUtils.computeMsgIdForDualFormat(node.getMsg());
    long alternateId = node.getMsg().getAlternateId().orElse(-1);
    String usePrimaryOrAlternateMsg = buildPrimaryOrAlternateMsg(node, msg, msgId, ternaryExpression);
    String pyExprText = "";

    if (node.hasFallbackMsg()) {
      long fallbackId = MsgUtils.computeMsgIdForDualFormat(node.getFallbackMsg());
      PyExpr fallbackMsg = generateMsgFunc(node.getFallbackMsg(), false);
      String useFbOrFbAlternateMsg = buildFallbackMsg(node, fallbackMsg, fallbackId);

      pyExprText = buildTernaryExpression(node, usePrimaryOrAlternateMsg, useFbOrFbAlternateMsg, msgId, alternateId, fallbackId);
    } else if (alternateId != -1) {
      pyExprText = usePrimaryOrAlternateMsg;
    }

    return pyExprText;
  }

  private String buildPrimaryOrAlternateMsg(MsgFallbackGroupNode node, PyExpr msg, long msgId, String ternaryExpression) {
    return node.getMsg().getAlternateId().isPresent()
        ? String.format(ternaryExpression, msg.getText(), msgId, node.getMsg().getAlternateId().getAsLong(), generateMsgFunc(node.getMsg(), true).getText())
        : msg.getText();
  }

  private String buildFallbackMsg(MsgFallbackGroupNode node, PyExpr fallbackMsg, long fallbackId) {
    return node.getFallbackMsg().getAlternateId().isPresent()
        ? String.format("( %s if translator_impl.is_msg_available(%d) else %s)", fallbackMsg.getText(), fallbackId, generateMsgFunc(node.getFallbackMsg(), true).getText())
        : fallbackMsg.getText();
  }

  private String buildTernaryExpression(MsgFallbackGroupNode node, String usePrimaryOrAlternateMsg, String useFbOrFbAlternateMsg, long msgId, long alternateId, long fallbackId) {
    if (alternateId != -1) {
      return String.format(usePrimaryOrAlternateMsg + " if (translator_impl.is_msg_available(%d) or translator_impl.is_msg_available(%d)) else " + useFbOrFbAlternateMsg,
          msgId, alternateId);
    } else {
      return String.format(usePrimaryOrAlternateMsg + " if (translator_impl.is_msg_available(%d)) else " + useFbOrFbAlternateMsg,
          msgId);
    }
  }

  private void applyEscapingDirectives(MsgFallbackGroupNode node, PyExpr msg) {
    for (SoyPrintDirective directive : node.getEscapingDirectives()) {
      Preconditions.checkState(directive instanceof SoyPySrcPrintDirective, "Contextual autoescaping produced a bogus directive: %s", directive.getName());
      msg = ((SoyPySrcPrintDirective) directive).applyForPySrc(msg, ImmutableList.of());
    }
  }

  private PyStringExpr generateMsgFunc(MsgNode msg, boolean useAlternateId) {
    return new MsgFuncGenerator(
            genPyExprsVisitorFactory,
            pluginValueFactory,
            msg,
            localVarExprs,
            errorReporter,
            useAlternateId)
        .getPyExpr();
  }

  @Override
  protected void visitIfNode(IfNode node) {
    StringBuilder pyExprTextSb = new StringBuilder();
    boolean hasElse = false;
    int pendingParens = 0;

    for (SoyNode child : node.getChildren()) {
      if (child instanceof IfCondNode) {
        pendingParens = processIfCondNode((IfCondNode) child, pyExprTextSb, pendingParens);
      } else if (child instanceof IfElseNode) {
        hasElse = true;
        processIfElseNode((IfElseNode) child, pyExprTextSb, pendingParens);
      } else {
        throw new AssertionError("Unexpected if child node type. Child: " + child);
      }
    }

    if (!hasElse) {
      pyExprTextSb.append("''");
    }
    for (int i = 0; i < pendingParens; i++) {
      pyExprTextSb.append(")");
    }

    pyExprs.add(new PyStringExpr(pyExprTextSb.toString(), PyExprUtils.pyPrecedenceForOperator(Operator.CONDITIONAL)));
  }

  private int processIfCondNode(IfCondNode icn, StringBuilder pyExprTextSb, int pendingParens) {
    GenPyExprsVisitor genPyExprsVisitor =
        genPyExprsVisitorFactory.create(localVarExprs, errorReporter);
    TranslateToPyExprVisitor translator =
        new TranslateToPyExprVisitor(localVarExprs, pluginValueFactory, icn.getParent(), errorReporter);

    PyExpr condBlock = PyExprUtils.concatPyExprs(genPyExprsVisitor.exec(icn)).toPyString();
    condBlock = PyExprUtils.maybeProtect(condBlock, PyExprUtils.pyPrecedenceForOperator(Operator.CONDITIONAL));
    pyExprTextSb.append("(").append(condBlock.getText());

    PyExpr condPyExpr = translator.exec(icn.getExpr());
    pyExprTextSb.append(") if (").append(condPyExpr.getText()).append(") else (");
    return pendingParens + 1;
  }

  private void processIfElseNode(IfElseNode ien, StringBuilder pyExprTextSb, int pendingParens) {
    PyExpr elseBlock = PyExprUtils.concatPyExprs(execOnChildren(ien)).toPyString();
    pyExprTextSb.append(elseBlock.getText()).append(")");
  }

  @Override
  protected void visitIfCondNode(IfCondNode node) {
    visitChildren(node);
  }

  @Override
  protected void visitIfElseNode(IfElseNode node) {
    visitChildren(node);
  }

  @Override
  protected void visitCallNode(CallNode node) {
    pyExprs.add(genPyCallExprVisitor.exec(node, localVarExprs, errorReporter));
  }

  @Override
  protected void visitCallParamContentNode(CallParamContentNode node) {
    visitChildren(node);
  }
}
