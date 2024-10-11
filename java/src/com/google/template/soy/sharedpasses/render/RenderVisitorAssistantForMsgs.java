
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

package com.google.template.soy.sharedpasses.render;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.SoyDataException;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.internal.MsgUtils;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralRemainderPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.msgs.restricted.SoyMsgSelectPart;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CaseOrDefaultNode;
import com.google.template.soy.soytree.EscapingMode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgHtmlTagNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.MsgPluralCaseNode;
import com.google.template.soy.soytree.MsgPluralDefaultNode;
import com.google.template.soy.soytree.MsgPluralNode;
import com.google.template.soy.soytree.MsgSelectCaseNode;
import com.google.template.soy.soytree.MsgSelectDefaultNode;
import com.google.template.soy.soytree.MsgSelectNode;
import com.google.template.soy.soytree.SoyNode;
import com.ibm.icu.util.ULocale;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Assistant visitor for RenderVisitor to handle messages.
 */
final class RenderVisitorAssistantForMsgs extends AbstractSoyNodeVisitor<Void> {

  /** Master instance of RenderVisitor. */
  private final RenderVisitor master;

  /** The bundle of translated messages, or null to use the messages from the Soy source. */
  private final SoyMsgBundle msgBundle;

  /**
   * @param master The master RenderVisitor instance.
   * @param msgBundle The bundle of translated messages, or null to use the messages from the Soy
   *     source.
   */
  RenderVisitorAssistantForMsgs(RenderVisitor master, SoyMsgBundle msgBundle) {
    this.master = master;
    this.msgBundle = msgBundle;
  }

  @Override
  public Void exec(SoyNode node) {
    throw new AssertionError();
  }

  /** This method must only be called by the master RenderVisitor. */
  void visitForUseByMaster(SoyNode node) {
    visit(node);
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for specific nodes.

  @Override
  protected void visitMsgFallbackGroupNode(MsgFallbackGroupNode node) {
    if (!renderTranslation(node)) {
      renderMsgFromSource(node.getChild(0));
    }
  }

  private boolean renderTranslation(MsgFallbackGroupNode node) {
    if (msgBundle != null) {
      for (MsgNode msg : node.getChildren()) {
        if (tryRenderTranslation(msg)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean tryRenderTranslation(MsgNode msg) {
    ImmutableList<SoyMsgPart> translation = msgBundle.getMsgParts(MsgUtils.computeMsgIdForDualFormat(msg));
    if (!translation.isEmpty()) {
      renderMsgFromTranslation(msg, translation, msgBundle.getLocale());
      return true;
    }
    ImmutableList<SoyMsgPart> translationByAlternateId = msg.getAlternateId().isPresent()
        ? msgBundle.getMsgParts(msg.getAlternateId().getAsLong())
        : ImmutableList.of();
    if (!translationByAlternateId.isEmpty()) {
      renderMsgFromTranslation(msg, translationByAlternateId, msgBundle.getLocale());
      return true;
    }
    return false;
  }

  /** Private helper for visitMsgFallbackGroupNode() to render a message from its translation. */
  private void renderMsgFromTranslation(MsgNode msg, ImmutableList<SoyMsgPart> msgParts, @Nullable ULocale locale) {
    SoyMsgPart firstPart = msgParts.get(0);
    if (firstPart instanceof SoyMsgPluralPart) {
      visitPluralPart(msg, locale, (SoyMsgPluralPart) firstPart);
    } else if (firstPart instanceof SoyMsgSelectPart) {
      visitSelectPart(msg, locale, (SoyMsgSelectPart) firstPart);
    } else {
      processMessageParts(msg, msgParts);
    }
  }

  private void visitPluralPart(MsgNode msg, @Nullable ULocale locale, SoyMsgPluralPart firstPart) {
    new PlrselMsgPartsVisitor(msg, locale).visitPart(firstPart);
  }

  private void visitSelectPart(MsgNode msg, @Nullable ULocale locale, SoyMsgSelectPart firstPart) {
    new PlrselMsgPartsVisitor(msg, locale).visitPart(firstPart);
  }

  private void processMessageParts(MsgNode msg, ImmutableList<SoyMsgPart> msgParts) {
    for (SoyMsgPart msgPart : msgParts) {
      if (msgPart instanceof SoyMsgRawTextPart) {
        appendRawText((SoyMsgRawTextPart) msgPart, msg);
      } else if (msgPart instanceof SoyMsgPlaceholderPart) {
        visit(msg.getRepPlaceholderNode(((SoyMsgPlaceholderPart) msgPart).getPlaceholderName()));
      } else {
        throw new AssertionError();
      }
    }
  }

  private void appendRawText(SoyMsgRawTextPart msgPart, MsgNode msg) {
    String s = msgPart.getRawText();
    if (msg.getEscapingMode() == EscapingMode.ESCAPE_HTML) {
      s = s.replace("<", "&lt;");
    }
    RenderVisitor.append(master.getCurrOutputBufForUseByAssistants(), s);
  }

  /** Private helper for visitMsgFallbackGroupNode() to render a message from its source. */
  private void renderMsgFromSource(MsgNode msg) {
    visitChildren(msg);
  }

  @Override
  protected void visitMsgNode(MsgNode node) {
    throw new AssertionError();
  }

  @Override
  protected void visitMsgPluralNode(MsgPluralNode node) {
    double pluralValue = evaluatePluralExpression(node);
    handlePluralCases(node, pluralValue);
  }

  private double evaluatePluralExpression(MsgPluralNode node) {
    ExprRootNode pluralExpr = node.getExpr();
    try {
      return master.evalForUseByAssistants(pluralExpr, node).numberValue();
    } catch (SoyDataException e) {
      throw RenderException.createWithSource(
          String.format("Plural expression \"%s\" doesn't evaluate to number.", pluralExpr.toSourceString()), e, node);
    }
  }

  private void handlePluralCases(MsgPluralNode node, double pluralValue) {
    for (CaseOrDefaultNode child : node.getChildren()) {
      if (child instanceof MsgPluralDefaultNode) {
        visitChildren(child);
        break;
      } else if (((MsgPluralCaseNode) child).getCaseNumber() == pluralValue) {
        visitChildren(child);
        break;
      }
    }
  }

  @Override
  protected void visitMsgSelectNode(MsgSelectNode node) {
    String selectValue = evaluateSelectExpression(node);
    handleSelectCases(node, selectValue);
  }

  private String evaluateSelectExpression(MsgSelectNode node) {
    ExprRootNode selectExpr = node.getExpr();
    try {
      return master.evalForUseByAssistants(selectExpr, node).coerceToString();
    } catch (SoyDataException e) {
      throw RenderException.createWithSource(
          String.format("Select expression \"%s\" doesn't evaluate to string.", selectExpr.toSourceString()), e, node);
    }
  }

  private void handleSelectCases(MsgSelectNode node, String selectValue) {
    for (CaseOrDefaultNode child : node.getChildren()) {
      if (child instanceof MsgSelectDefaultNode) {
        visitChildren(child);
      } else if (((MsgSelectCaseNode) child).getCaseValue().equals(selectValue)) {
        visitChildren(child);
        return;
      }
    }
  }

  @Override
  protected void visitMsgPlaceholderNode(MsgPlaceholderNode node) {
    visitChildren(node);
  }

  @Override
  protected void visitMsgHtmlTagNode(MsgHtmlTagNode node) {
    visitChildren(node);
  }

  // -----------------------------------------------------------------------------------------------
  // Helper class for traversing a translated plural/select message.

  /**
   * Visitor for processing {@code SoyMsgPluralPart} and {@code SoyMsgSelectPart} objects.
   *
   * <p>Visits the parts hierarchy, evaluates each part and appends the result into the parent
   * class' StringBuffer object.
   *
   * <p>In addition to writing to output, this inner class uses the outer class's master's eval()
   * method to evaluate the expressions associated with the nodes.
   */
  private class PlrselMsgPartsVisitor {

    /** The parent message node for the parts dealt here. */
    private final MsgNode msgNode;

    /** The locale for the translated message considered. */
    private final ULocale locale;

    /**
     * Constructor.
     *
     * @param msgNode The parent message node for the parts dealt here.
     * @param locale The locale of the Soy message.
     */
    public PlrselMsgPartsVisitor(MsgNode msgNode, ULocale locale) {
      this.msgNode = msgNode;
      this.locale = locale;
    }

    private void visitPart(SoyMsgSelectPart selectPart) {
      String selectVarName = selectPart.getSelectVarName();
      MsgSelectNode repSelectNode = msgNode.getRepSelectNode(selectVarName);
      String correctSelectValue = evaluateSelectValue(repSelectNode);
      List<SoyMsgPart> caseParts = selectPart.lookupCase(correctSelectValue);
      if (caseParts != null) {
        processSelectCaseParts(caseParts);
      }
    }

    private String evaluateSelectValue(MsgSelectNode repSelectNode) {
      ExprRootNode selectExpr = repSelectNode.getExpr();
      try {
        return master.evalForUseByAssistants(selectExpr, repSelectNode).stringValue();
      } catch (SoyDataException e) {
        throw RenderException.createWithSource(
            String.format("Select expression \"%s\" doesn't evaluate to string.", selectExpr.toSourceString()),
            e, repSelectNode);
      }
    }

    private void processSelectCaseParts(List<SoyMsgPart> caseParts) {
      for (SoyMsgPart casePart : caseParts) {
        if (casePart instanceof SoyMsgSelectPart) {
          visitPart((SoyMsgSelectPart) casePart);
        } else if (casePart instanceof SoyMsgPluralPart) {
          visitPart((SoyMsgPluralPart) casePart);
        } else if (casePart instanceof SoyMsgPlaceholderPart) {
          visitPart((SoyMsgPlaceholderPart) casePart);
        } else if (casePart instanceof SoyMsgRawTextPart) {
          appendRawTextPart((SoyMsgRawTextPart) casePart);
        } else {
          throw RenderException.create("Unsupported part of type " + casePart.getClass().getName() + " under a select case.")
              .addStackTraceElement(msgNode);
        }
      }
    }

    private void visitPart(SoyMsgPluralPart pluralPart) {
      MsgPluralNode repPluralNode = msgNode.getRepPluralNode(pluralPart.getPluralVarName());
      double correctPluralValue = evaluatePluralValue(repPluralNode);
      List<SoyMsgPart> caseParts = pluralPart.lookupCase(correctPluralValue, locale);
      processPluralCaseParts(caseParts, pluralPart);
    }

    private double evaluatePluralValue(MsgPluralNode repPluralNode) {
      ExprRootNode pluralExpr = repPluralNode.getExpr();
      try {
        return master.evalForUseByAssistants(pluralExpr, repPluralNode).numberValue();
      } catch (SoyDataException e) {
        throw RenderException.createWithSource(
            String.format("Plural expression \"%s\" doesn't evaluate to number.", pluralExpr.toSourceString()),
            e, repPluralNode);
      }
    }

    private void processPluralCaseParts(List<SoyMsgPart> caseParts, SoyMsgPluralPart pluralPart) {
      for (SoyMsgPart casePart : caseParts) {
        if (casePart instanceof SoyMsgPlaceholderPart) {
          visitPart((SoyMsgPlaceholderPart) casePart);
        } else if (casePart instanceof SoyMsgRawTextPart) {
          appendRawTextPart((SoyMsgRawTextPart) casePart);
        } else if (casePart instanceof SoyMsgPluralRemainderPart) {
          appendPluralRemainder(pluralPart);
        } else {
          throw RenderException.create("Unsupported part of type " + casePart.getClass().getName() + " under a plural case.")
              .addStackTraceElement(msgNode);
        }
      }
    }

    private void appendPluralRemainder(SoyMsgPluralPart pluralPart) {
      double currentPluralRemainderValue = pluralPart.getOffset();
      RenderVisitor.append(master.getCurrOutputBufForUseByAssistants(), String.valueOf(currentPluralRemainderValue));
    }

    private void visitPart(SoyMsgPlaceholderPart msgPlaceholderPart) {
      visit(msgNode.getRepPlaceholderNode(msgPlaceholderPart.getPlaceholderName()));
    }

    private void appendRawTextPart(SoyMsgRawTextPart rawTextPart) {
      RenderVisitor.append(master.getCurrOutputBufForUseByAssistants(), rawTextPart.getRawText());
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Fallback implementation.

  @Override
  protected void visitSoyNode(SoyNode node) {
    master.visitForUseByAssistants(node);
  }
}
