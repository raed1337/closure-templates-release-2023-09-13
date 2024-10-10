
/*
 * Copyright 2017 Google Inc.
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

import static com.google.template.soy.soytree.MessagePlaceholder.PHEX_ATTR;
import static com.google.template.soy.soytree.MessagePlaceholder.PHNAME_ATTR;

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.HtmlTagNode;
import com.google.template.soy.soytree.MsgHtmlTagNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.MsgPluralNode;
import com.google.template.soy.soytree.MsgSelectNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.MsgBlockNode;
import com.google.template.soy.soytree.SoyNode.MsgPlaceholderInitialNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.VeLogNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A compiler pass to insert {@link MsgPlaceholderNode placeholders} into {@link MsgNode messages}.
 *
 * <p>Also validates correct use of the {@code phname} and {@code phex} attributes; these attributes
 * can only be set within a <code>{msg ...}...{/msg}</code> context.
 */
final class InsertMsgPlaceholderNodesPass implements CompilerFilePass {
  private static final SoyErrorKind INVALID_PLACEHOLDER =
      SoyErrorKind.of(
          "''{0}'' attributes are only valid on placeholders inside of '''{'msg...'' tags.{1}",
          StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind UNEXPECTED_COMMAND_IN_MSG =
      SoyErrorKind.of(
          "Unexpected soy command in '''{'msg ...'}''' block. Only message placeholder commands "
              + "('{'print, '{'call and html tags) are allowed to be direct children of messages.");
  private final ErrorReporter errorReporter;

  InsertMsgPlaceholderNodesPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    new Visitor(nodeIdGen, errorReporter).exec(file);
  }

  private static final class Visitor extends AbstractSoyNodeVisitor<Void> {
    final List<SoyNode> nodesToReplace = new ArrayList<>();
    final IdGenerator nodeIdGen;
    final ErrorReporter errorReporter;
    boolean isValidMsgPlaceholderPosition = false;

    Visitor(IdGenerator nodeIdGen, ErrorReporter errorReporter) {
      this.errorReporter = errorReporter;
      this.nodeIdGen = nodeIdGen;
    }

    @Override
    protected void visitMsgNode(MsgNode msgNode) {
      msgNode.ensureSubstUnitInfoHasNotBeenAccessed();
      isValidMsgPlaceholderPosition = true;
      visitChildren(msgNode);
      isValidMsgPlaceholderPosition = false;
      replaceNodesInMsgBlock(msgNode);
    }

    private void replaceNodesInMsgBlock(MsgNode msgNode) {
      for (SoyNode node : nodesToReplace) {
        ParentSoyNode<?> parent = node.getParent();
        if (!(parent instanceof MsgBlockNode)) {
          throw new AssertionError(
              "Expected parent: "
                  + parent
                  + " of "
                  + node
                  + " to be a msgblocknode @ "
                  + parent.getSourceLocation());
        }
        int index = parent.getChildIndex(node);
        parent.removeChild(index);
        MsgPlaceholderInitialNode newNode = createNewPlaceholderNode(node, parent);
        ((MsgBlockNode) parent).addChild(index, new MsgPlaceholderNode(nodeIdGen.genId(), newNode));
      }
      nodesToReplace.clear();
    }

    private MsgPlaceholderInitialNode createNewPlaceholderNode(SoyNode node, ParentSoyNode<?> parent) {
      if (node instanceof HtmlTagNode) {
        return createHtmlTagPlaceholderNode((HtmlTagNode) node, parent);
      }
      return (MsgPlaceholderInitialNode) node;
    }

    private MsgPlaceholderInitialNode createHtmlTagPlaceholderNode(HtmlTagNode htmlNode, ParentSoyNode<?> parent) {
      VeLogNode veLogParent = null;
      if (parent instanceof VeLogNode && parent.getChildIndex(htmlNode) == 0) {
        veLogParent = (VeLogNode) parent;
      }
      return MsgHtmlTagNode.fromNode(nodeIdGen.genId(), htmlNode, veLogParent, errorReporter);
    }

    @Override
    protected void visitMsgPluralNode(MsgPluralNode node) {
      visitChildren(node);
    }

    @Override
    protected void visitMsgSelectNode(MsgSelectNode node) {
      visitChildren(node);
    }

    @Override
    protected void visitPrintNode(PrintNode node) {
      handlePlaceholderAndVisitChildren(node);
    }

    @Override
    protected void visitCallNode(CallNode node) {
      handlePlaceholderAndVisitChildren(node);
    }

    @Override
    protected void visitVeLogNode(VeLogNode node) {
      visitChildren(node);
    }

    @Override
    protected void visitMsgHtmlTagNode(MsgHtmlTagNode node) {
      throw new AssertionError("Unexpected node: " + node.toSourceString());
    }

    @Override
    protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
      handlePlaceholderAndVisitChildren(node);
    }

    @Override
    protected void visitHtmlCloseTagNode(HtmlCloseTagNode node) {
      handlePlaceholderAndVisitChildren(node);
      validateCloseTagAttributes(node);
    }

    private void validateCloseTagAttributes(HtmlCloseTagNode node) {
      if (!isValidMsgPlaceholderPosition) {
        for (String name : Arrays.asList("phname", "phex")) {
          HtmlAttributeNode attr = node.getDirectAttributeNamed(name);
          if (attr != null) {
            errorReporter.report(attr.getSourceLocation(), INVALID_PLACEHOLDER, name, "");
          }
        }
      }
    }

    @Override
    protected void visitRawTextNode(RawTextNode node) {
      // do nothing
    }

    @Override
    protected void visitHtmlAttributeNode(HtmlAttributeNode node) {
      visitChildren(node);
    }

    @Override
    protected void visitHtmlAttributeValueNode(HtmlAttributeValueNode node) {
      visitChildren(node);
    }

    @Override
    protected void visitMsgPlaceholderNode(MsgPlaceholderNode node) {
      throw new AssertionError("Unexpected node: " + node.toSourceString());
    }

    private void handlePlaceholderAndVisitChildren(SoyNode node) {
      if (isValidMsgPlaceholderPosition) {
        nodesToReplace.add(node);
      }
      if (node instanceof ParentSoyNode<?>) {
        boolean oldIsValidMsgPlaceholderPosition = isValidMsgPlaceholderPosition;
        isValidMsgPlaceholderPosition = false;
        visitChildren((ParentSoyNode<?>) node);
        isValidMsgPlaceholderPosition = oldIsValidMsgPlaceholderPosition;
      }
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (isValidMsgPlaceholderPosition && !(node instanceof MsgBlockNode)) {
        errorReporter.report(node.getSourceLocation(), UNEXPECTED_COMMAND_IN_MSG);
        return;
      }
      if (node instanceof MsgPlaceholderInitialNode) {
        checkPlaceholderNode((MsgPlaceholderInitialNode) node);
      }
      if (node instanceof ParentSoyNode<?>) {
        visitChildren((ParentSoyNode<?>) node);
      }
    }

    private void checkPlaceholderNode(MsgPlaceholderInitialNode node) {
      if (isValidMsgPlaceholderPosition) {
        return;
      }
      boolean hasUserSuppliedName = node.getPlaceholder().userSuppliedName().isPresent();
      boolean hasExample = node.getPlaceholder().example().isPresent();
      if (hasUserSuppliedName || hasExample) {
        reportInvalidPlaceholder(node, hasUserSuppliedName, hasExample);
      }
    }

    private void reportInvalidPlaceholder(MsgPlaceholderInitialNode node, boolean hasUserSuppliedName, boolean hasExample) {
      MsgNode msg = node.getNearestAncestor(MsgNode.class);
      String extra = "";
      if (msg != null) {
        extra = checkParentNodeForPlaceholder(node, msg);
      }
      if (hasUserSuppliedName) {
        errorReporter.report(node.getSourceLocation(), INVALID_PLACEHOLDER, PHNAME_ATTR, extra);
      }
      if (hasExample) {
        errorReporter.report(node.getSourceLocation(), INVALID_PLACEHOLDER, PHEX_ATTR, extra);
      }
    }

    private String checkParentNodeForPlaceholder(MsgPlaceholderInitialNode node, MsgNode msg) {
      SoyNode current = node.getParent();
      while (current != msg && !(current instanceof HtmlTagNode)) {
        current = current.getParent();
      }
      return current != msg ? " Did you mean to put this attribute on the surrounding html tag?" : "";
    }
  }
}
