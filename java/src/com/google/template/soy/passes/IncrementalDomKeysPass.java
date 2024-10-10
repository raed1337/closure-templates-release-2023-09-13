
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

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlContext;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.KeyNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SkipNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.VeLogNode;
import com.google.template.soy.types.SanitizedType.HtmlType;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

final class IncrementalDomKeysPass implements CompilerFilePass {
  private final boolean disableAllTypeChecking;

  public IncrementalDomKeysPass(boolean disableAllTypeChecking) {
    this.disableAllTypeChecking = disableAllTypeChecking;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    new IncrementalDomKeysPassVisitor(disableAllTypeChecking).exec(file);
  }

  private static final class IncrementalDomKeysPassVisitor extends AbstractSoyNodeVisitor<Void> {
    private ArrayDeque<AtomicInteger> keyCounterStack;
    private ArrayDeque<Boolean> htmlKeyStack;
    private TemplateNode template;
    private boolean mustEmitKeyNodes = false;
    private boolean templateContainsUnpredictableContent = false;
    private final boolean disableAllTypeChecking;

    public IncrementalDomKeysPassVisitor(boolean disableAllTypeChecking) {
      this.disableAllTypeChecking = disableAllTypeChecking;
    }

    @Override
    public void visitTemplateNode(TemplateNode templateNode) {
      initializeStacks();
      template = templateNode;
      templateContainsUnpredictableContent = false;
      visitBlockNode(templateNode);
    }

    private void initializeStacks() {
      htmlKeyStack = new ArrayDeque<>();
      keyCounterStack = new ArrayDeque<>();
      keyCounterStack.push(new AtomicInteger());
    }

    private void visitBlockNode(ParentSoyNode<?> node) {
      mustEmitKeyNodes = true;
      visitChildren(node);
    }

    @Override
    public void visitPrintNode(PrintNode node) {
      if (shouldEmitKeyForPrintNode(node)) {
        mustEmitKeyNodes = true;
        htmlKeyStack.push(true);
      }
    }

    private boolean shouldEmitKeyForPrintNode(PrintNode node) {
      return !disableAllTypeChecking && node.getExpr().getRoot().getType().isAssignableFromStrict(HtmlType.getInstance());
    }

    @Override
    public void visitLetContentNode(LetContentNode node) {
      boolean oldMustEmitKeyNodes = mustEmitKeyNodes;
      mustEmitKeyNodes = true;
      visitChildren(node);
      mustEmitKeyNodes = oldMustEmitKeyNodes;
    }

    @Override
    public void visitSoyNode(SoyNode node) {
      if (isRelevantSoyNode(node)) {
        visitBlockNode((ParentSoyNode) node);
        return;
      }
      if (node instanceof ParentSoyNode) {
        visitChildren((ParentSoyNode) node);
      }
    }

    private boolean isRelevantSoyNode(SoyNode node) {
      return node instanceof TemplateNode || node instanceof VeLogNode || 
             (node instanceof HtmlContext.HtmlContextHolder && 
              ((HtmlContext.HtmlContextHolder) node).getHtmlContext() == HtmlContext.HTML_PCDATA);
    }

    @Override
    public void visitHtmlOpenTagNode(HtmlOpenTagNode openTagNode) {
      processHtmlOpenTagNode(openTagNode);
      visitChildren(openTagNode);
      htmlKeyStack.push(mustEmitKeyNodes);
      mustEmitKeyNodes = false;
    }

    private void processHtmlOpenTagNode(HtmlOpenTagNode openTagNode) {
      KeyNode keyNode = openTagNode.getKeyNode();
      updateTemplateUnpredictability(openTagNode);
      if (keyNode != null) {
        keyCounterStack.push(new AtomicInteger());
      } else {
        handleDynamicHtmlTag(openTagNode);
      }
    }

    private void updateTemplateUnpredictability(HtmlOpenTagNode openTagNode) {
      if (!openTagNode.isSelfClosing()) {
        templateContainsUnpredictableContent = 
            templateContainsUnpredictableContent || openTagNode.hasUnpredictableTagLocation();
      }
    }

    private void handleDynamicHtmlTag(HtmlOpenTagNode openTagNode) {
      openTagNode.setIsDynamic(
          mustEmitKeyNodes || templateContainsUnpredictableContent || openTagNode.isSkipRoot());
      openTagNode.setKeyId(incrementKeyForTemplate(template, openTagNode.isElementRoot()));
    }

    @Override
    public void visitHtmlCloseTagNode(HtmlCloseTagNode closeTagNode) {
      if (closeTagNode.getTaggedPairs().size() == 1) {
        handleHtmlCloseTagNode(closeTagNode);
      }
    }

    private void handleHtmlCloseTagNode(HtmlCloseTagNode closeTagNode) {
      HtmlOpenTagNode openTag = (HtmlOpenTagNode) closeTagNode.getTaggedPairs().get(0);
      if (openTag.getKeyNode() != null && !(openTag.getParent() instanceof SkipNode)) {
        keyCounterStack.pop();
      }
      if (!htmlKeyStack.isEmpty()) {
        mustEmitKeyNodes = htmlKeyStack.pop();
      }
    }

    private String incrementKeyForTemplate(TemplateNode template, boolean isElementRoot) {
      if (isElementRoot) {
        return template.getTemplateName() + "-root";
      }
      AtomicInteger keyCounter = keyCounterStack.peek();
      return template.getTemplateName() + "-" + keyCounter.getAndIncrement();
    }
  }
}
