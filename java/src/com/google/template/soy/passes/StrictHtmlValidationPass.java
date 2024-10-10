
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.passes.htmlmatcher.ActiveEdge;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherAccumulatorNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherBlockNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherConditionNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraph;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherGraphNode.EdgeKind;
import com.google.template.soy.passes.htmlmatcher.HtmlMatcherTagNode;
import com.google.template.soy.passes.htmlmatcher.HtmlTagMatchingPass;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPluralCaseNode;
import com.google.template.soy.soytree.MsgPluralDefaultNode;
import com.google.template.soy.soytree.MsgSelectCaseNode;
import com.google.template.soy.soytree.MsgSelectDefaultNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SwitchCaseNode;
import com.google.template.soy.soytree.SwitchNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.VeLogNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A {@link CompilerFilePass} that checks strict html mode. See go/soy-html for usages.
 */
@RunAfter(ResolveNamesPass.class)
public final class StrictHtmlValidationPass implements CompilerFilePass {

  private final ErrorReporter errorReporter;

  @Nullable private HtmlMatcherGraph htmlMatcherGraph = null;

  public StrictHtmlValidationPass(ErrorReporter errorReporter) {
    this.errorReporter = checkNotNull(errorReporter, "errorReporter must be non-null.");
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    for (TemplateNode node : file.getTemplates()) {
      checkTemplateNode(node, nodeIdGen);
    }
  }

  private void checkTemplateNode(TemplateNode node, IdGenerator idGenerator) {
    ErrorReporter reporter = ErrorReporter.create(ImmutableMap.of());
    htmlMatcherGraph = new HtmlTagVisitor(idGenerator, reporter).exec(node);
    runHtmlTagMatchingPass(node, reporter, idGenerator);
    if (node.isStrictHtml()) {
      reporter.copyTo(this.errorReporter);
    }
  }

  private void runHtmlTagMatchingPass(TemplateNode node, ErrorReporter reporter, IdGenerator idGenerator) {
    new HtmlTagMatchingPass(
            reporter,
            idGenerator,
            /* inCondition= */ false,
            /* foreignContentTagDepth= */ 0,
            /* parentBlockType= */ null)
        .run(htmlMatcherGraph);
  }

  @VisibleForTesting
  public Optional<HtmlMatcherGraph> getHtmlMatcherGraph() {
    return Optional.ofNullable(htmlMatcherGraph);
  }

  private static final class HtmlTagVisitor extends AbstractSoyNodeVisitor<HtmlMatcherGraph> {

    private final HtmlMatcherGraph htmlMatcherGraph = new HtmlMatcherGraph();
    private final ArrayDeque<List<ActiveEdge>> activeEdgeStack = new ArrayDeque<>();
    private final IdGenerator idGenerator;
    private final ErrorReporter errorReporter;

    HtmlTagVisitor(IdGenerator idGenerator, ErrorReporter errorReporter) {
      this.idGenerator = idGenerator;
      this.errorReporter = errorReporter;
    }

    @Override
    public HtmlMatcherGraph exec(SoyNode node) {
      visitChildren((BlockNode) node);
      return htmlMatcherGraph;
    }

    @Override
    protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
      htmlMatcherGraph.addNode(new HtmlMatcherTagNode(node));
      visitChildren(node);
    }

    @Override
    protected void visitHtmlCloseTagNode(HtmlCloseTagNode node) {
      htmlMatcherGraph.addNode(new HtmlMatcherTagNode(node));
    }

    @Override
    protected void visitIfNode(IfNode node) {
      enterConditionalContext();
      visitChildren(node);
      exitConditionalContext();
    }

    @Override
    protected void visitIfCondNode(IfCondNode node) {
      processIfCondNode(node);
    }

    private void processIfCondNode(IfCondNode node) {
      HtmlMatcherConditionNode conditionNode = enterConditionBranch(node.getExpr(), node);
      visitChildren(node);
      exitConditionBranch(conditionNode);
    }

    @Override
    protected void visitSwitchNode(SwitchNode node) {
      enterConditionalContext();
      visitChildren(node);
      exitConditionalContext();
    }

    @Override
    protected void visitSwitchCaseNode(SwitchCaseNode node) {
      for (ExprNode expr : node.getExprList()) {
        processSwitchCaseNode(expr, node);
      }
    }

    private void processSwitchCaseNode(ExprNode expr, SwitchCaseNode node) {
      HtmlMatcherConditionNode conditionNode = enterConditionBranch(expr, node);
      visitChildren(node);
      exitConditionBranch(conditionNode);
    }

    @Override
    protected void visitMsgNode(MsgNode node) {
      addHtmlMatcherBlockNode(node, "msg");
    }

    @Override
    protected void visitMsgPluralCaseNode(MsgPluralCaseNode node) {
      addHtmlMatcherBlockNode(node, "msg");
    }

    @Override
    protected void visitMsgPluralDefaultNode(MsgPluralDefaultNode node) {
      addHtmlMatcherBlockNode(node, "msg");
    }

    @Override
    protected void visitMsgSelectCaseNode(MsgSelectCaseNode node) {
      addHtmlMatcherBlockNode(node, "msg");
    }

    @Override
    protected void visitMsgSelectDefaultNode(MsgSelectDefaultNode node) {
      addHtmlMatcherBlockNode(node, "msg");
    }

    @Override
    protected void visitForNonemptyNode(ForNonemptyNode node) {
      addHtmlMatcherBlockNode(node, "loop");
    }

    @Override
    protected void visitVeLogNode(VeLogNode node) {
      addHtmlMatcherBlockNode(node, "velog");
    }

    private void addHtmlMatcherBlockNode(SoyNode node, String blockType) {
      htmlMatcherGraph.addNode(
          new HtmlMatcherBlockNode(
              new HtmlTagVisitor(idGenerator, errorReporter).exec(node), blockType));
    }

    @Override
    protected void visitLetContentNode(LetContentNode node) {
      runHtmlTagMatchingPass(node, "let content");
    }

    @Override
    protected void visitCallParamContentNode(CallParamContentNode node) {
      runHtmlTagMatchingPass(node, "call param content");
    }

    private void runHtmlTagMatchingPass(SoyNode node, String blockType) {
      HtmlMatcherGraph htmlMatcherGraph = new HtmlTagVisitor(idGenerator, errorReporter).exec(node);
      new HtmlTagMatchingPass(
              errorReporter,
              idGenerator,
              /* inCondition= */ false,
              /* foreignContentTagDepth= */ 0,
              blockType)
          .run(htmlMatcherGraph);
    }

    private void enterConditionalContext() {
      activeEdgeStack.push(new ArrayList<>());
    }

    private void exitConditionalContext() {
      List<ActiveEdge> activeEdges = activeEdgeStack.pop();
      if (htmlMatcherGraph.getNodeAtCursor().isPresent()) {
        HtmlMatcherGraphNode activeNode = htmlMatcherGraph.getNodeAtCursor().get();
        activeEdges.add(ActiveEdge.create(activeNode, activeNode.getActiveEdgeKind()));
      }
      HtmlMatcherAccumulatorNode accNode = new HtmlMatcherAccumulatorNode();
      accNode.accumulateActiveEdges(ImmutableList.copyOf(activeEdges));
      htmlMatcherGraph.addNode(accNode);
    }

    private HtmlMatcherConditionNode enterConditionBranch(ExprNode expr, SoyNode node) {
      HtmlMatcherConditionNode conditionNode =
          new HtmlMatcherConditionNode(
              node, expr, new HtmlTagVisitor(idGenerator, errorReporter).exec(node));
      htmlMatcherGraph.addNode(conditionNode);
      htmlMatcherGraph.saveCursor();
      conditionNode.setActiveEdgeKind(EdgeKind.TRUE_EDGE);
      return conditionNode;
    }

    private void exitConditionBranch(HtmlMatcherConditionNode ifConditionNode) {
      if (htmlMatcherGraph.getNodeAtCursor().isPresent()) {
        HtmlMatcherGraphNode activeNode = htmlMatcherGraph.getNodeAtCursor().get();
        activeEdgeStack.peek().add(ActiveEdge.create(activeNode, activeNode.getActiveEdgeKind()));
      }
      ifConditionNode.setActiveEdgeKind(EdgeKind.FALSE_EDGE);
      htmlMatcherGraph.restoreCursor();
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ParentSoyNode) {
        visitChildren((ParentSoyNode<?>) node);
      }
    }
  }
}
