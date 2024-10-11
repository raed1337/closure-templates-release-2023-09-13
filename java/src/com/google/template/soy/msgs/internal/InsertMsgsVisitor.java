
/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.msgs.internal;

import static java.util.stream.Collectors.toList;

import com.google.common.collect.Lists;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.BooleanNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.restricted.SoyMsg;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgHtmlTagNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Visitor for inserting translated messages into Soy tree. This pass replaces the
 * MsgFallbackGroupNodes in the tree with sequences of RawTextNodes and other nodes. The only
 * exception is plural/select messages. This pass currently does not replace MsgFallbackGroupNodes
 * that contain plural/select messages.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * <p>If the Soy tree doesn't contain plural/select messages, then after this pass, the Soy tree
 * should no longer contain MsgFallbackGroupNodes, MsgNodes, MsgPlaceholderNodes, or
 * MsgHtmlTagNodes. If the Soy tree contains plural/select messages, then the only messages left in
 * the tree after this pass runs should be the plural/select messages.
 *
 * <p>Note that the Soy tree is usually simplifiable after this pass is run (e.g. it usually
 * contains consecutive RawTextNodes). It's usually advisable to run a simplification pass after
 * this pass.
 */
public final class InsertMsgsVisitor {

  private static final SoyErrorKind ENCOUNTERED_PLURAL_OR_SELECT =
      SoyErrorKind.of(
          "JS code generation currently only supports plural/select messages when "
              + "shouldGenerateGoogMsgDefs is true.");

  @Nullable private final SoyMsgBundle msgBundle;
  private final ErrorReporter errorReporter;
  private IdGenerator nodeIdGen;

  /** The replacement nodes for the current MsgFallbackGroupNode we're visiting (during a pass). */
  private List<StandaloneNode> currReplacementNodes;

  /**
   * @param msgBundle The bundle of translated messages, or null to use the messages from the Soy
   *     source.
   * @param errorReporter For reporting errors.
   */
  public InsertMsgsVisitor(@Nullable SoyMsgBundle msgBundle, ErrorReporter errorReporter) {
    this.msgBundle = msgBundle;
    this.errorReporter = errorReporter;
  }

  public void insertMsgs(SoyNode node) {
    nodeIdGen = node.getNearestAncestor(SoyFileSetNode.class).getNodeIdGenerator();
    for (MsgFallbackGroupNode msgNode : SoyTreeUtils.getAllNodesOfType(node, MsgFallbackGroupNode.class)) {
      replaceMsgNode(msgNode);
    }
    SoyTreeUtils.allFunctionInvocations(node, BuiltinFunction.IS_PRIMARY_MSG_IN_USE)
        .forEach(this::replaceIsPrimaryMsgInUseFunction);
  }

  private void replaceMsgNode(MsgFallbackGroupNode node) {
    if (containsPluralOrSelectMsg(node)) {
      errorReporter.report(node.getSourceLocation(), ENCOUNTERED_PLURAL_OR_SELECT);
      return;
    }

    currReplacementNodes = getReplacementNodes(node);
    replaceNodeInParent(node, currReplacementNodes);
    currReplacementNodes = null;
  }

  private boolean containsPluralOrSelectMsg(MsgFallbackGroupNode node) {
    return node.getChildren().stream().anyMatch(MsgNode::isPlrselMsg);
  }

  private List<StandaloneNode> getReplacementNodes(MsgFallbackGroupNode node) {
    List<StandaloneNode> replacementNodes = Lists.newArrayList();
    if (msgBundle != null) {
      for (MsgNode msg : node.getChildren()) {
        SoyMsg translation = msgBundle.getMsg(MsgUtils.computeMsgIdForDualFormat(msg));
        if (translation != null) {
          buildReplacementNodesFromTranslation(msg, translation);
          return currReplacementNodes;
        }
      }
    }
    buildReplacementNodesFromSource(node.getChild(0));
    return currReplacementNodes;
  }

  private void replaceNodeInParent(MsgFallbackGroupNode node, List<StandaloneNode> replacementNodes) {
    ParentSoyNode<StandaloneNode> parent = node.getParent();
    int indexInParent = parent.getChildIndex(node);
    parent.removeChild(indexInParent);
    parent.addChildren(indexInParent, replacementNodes);
  }

  private void buildReplacementNodesFromTranslation(MsgNode msg, SoyMsg translation) {
    currReplacementNodes = Lists.newArrayList();
    for (SoyMsgPart msgPart : translation.getParts()) {
      if (msgPart instanceof SoyMsgRawTextPart) {
        String rawText = ((SoyMsgRawTextPart) msgPart).getRawText();
        currReplacementNodes.add(new RawTextNode(nodeIdGen.genId(), rawText, SourceLocation.UNKNOWN));
      } else if (msgPart instanceof SoyMsgPlaceholderPart) {
        processPlaceholderPart(msg, (SoyMsgPlaceholderPart) msgPart);
      } else {
        throw new AssertionError();
      }
    }
  }

  private void processPlaceholderPart(MsgNode msg, SoyMsgPlaceholderPart msgPart) {
    String placeholderName = msgPart.getPlaceholderName();
    MsgPlaceholderNode placeholderNode = msg.getRepPlaceholderNode(placeholderName);
    for (StandaloneNode contentNode : placeholderNode.getChildren()) {
      if (contentNode instanceof MsgHtmlTagNode) {
        currReplacementNodes.addAll(((MsgHtmlTagNode) contentNode).getChildren().stream()
            .map(InsertMsgsVisitor::maybeRewriteSourceLocation)
            .collect(toList()));
      } else {
        currReplacementNodes.add(maybeRewriteSourceLocation(contentNode));
      }
    }
  }

  private static StandaloneNode maybeRewriteSourceLocation(StandaloneNode node) {
    if (node instanceof RawTextNode) {
      RawTextNode textNode = (RawTextNode) node;
      return new RawTextNode(textNode.getId(), textNode.getRawText(), SourceLocation.UNKNOWN);
    }
    return node;
  }

  private void buildReplacementNodesFromSource(MsgNode msg) {
    currReplacementNodes = Lists.newArrayList();
    for (StandaloneNode child : msg.getChildren()) {
      if (child instanceof RawTextNode) {
        currReplacementNodes.add(child);
      } else if (child instanceof MsgPlaceholderNode) {
        processPlaceholderNode((MsgPlaceholderNode) child);
      } else {
        throw new AssertionError();
      }
    }
  }

  private void processPlaceholderNode(MsgPlaceholderNode placeholderNode) {
    for (StandaloneNode contentNode : placeholderNode.getChildren()) {
      if (contentNode instanceof MsgHtmlTagNode) {
        currReplacementNodes.addAll(((MsgHtmlTagNode) contentNode).getChildren());
      } else {
        currReplacementNodes.add(contentNode);
      }
    }
  }

  private void replaceIsPrimaryMsgInUseFunction(FunctionNode node) {
    boolean isPrimaryMsgInUse = calculateIsPrimaryMsgInUse(node);
    node.getParent()
        .replaceChild(node, new BooleanNode(isPrimaryMsgInUse, node.getSourceLocation()));
  }

  private boolean calculateIsPrimaryMsgInUse(FunctionNode node) {
    if (msgBundle == null) {
      return true;
    } else {
      long primaryMsgId = ((IntegerNode) node.getChild(1)).getValue();
      long fallbackMsgId = ((IntegerNode) node.getChild(2)).getValue();
      return !msgBundle.getMsgParts(primaryMsgId).isEmpty()
          || msgBundle.getMsgParts(fallbackMsgId).isEmpty();
    }
  }
}
