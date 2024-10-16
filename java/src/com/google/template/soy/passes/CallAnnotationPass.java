
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

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.TemplateNode;
import java.util.ArrayDeque;

/**
 * Annotates all calls with a string identifier, to be used in Incremental DOM and JBCSRC backends.
 */
@RunAfter(CheckTemplateHeaderVarsPass.class)
final class CallAnnotationPass implements CompilerFileSetPass {

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator) {
    for (SoyFileNode file : sourceFiles) {
      for (TemplateNode template : file.getTemplates()) {
        new KeyGenerator(template.getTemplateName()).exec(template);
      }
    }
    return Result.CONTINUE;
  }

  private static class KeyGenerator extends AbstractSoyNodeVisitor<Void> {
    private final ArrayDeque<Integer> idStack = new ArrayDeque<>();
    private final String templateName;

    public KeyGenerator(String templateName) {
      idStack.push(0);
      this.templateName = templateName;
    }

    @Override
    protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
      if (shouldPushIdStack(node)) {
        idStack.push(0);
      }
      visitChildren(node);
    }

    private boolean shouldPushIdStack(HtmlOpenTagNode node) {
      return node.getKeyNode() != null && !node.isSkipRoot();
    }

    @Override
    protected void visitCallNode(CallNode node) {
      int id = idStack.pop();
      node.setTemplateCallKey(templateName + "-call-" + id);
      idStack.push(id + 1);
      visitChildren(node);
    }

    @Override
    protected void visitHtmlCloseTagNode(HtmlCloseTagNode node) {
      if (isValidCloseTag(node)) {
        HtmlOpenTagNode openTag = (HtmlOpenTagNode) node.getTaggedPairs().get(0);
        if (shouldPopIdStack(openTag)) {
          idStack.pop();
        }
      }
    }

    private boolean isValidCloseTag(HtmlCloseTagNode node) {
      return node.getTaggedPairs().size() == 1;
    }

    private boolean shouldPopIdStack(HtmlOpenTagNode openTag) {
      return openTag.getKeyNode() != null && !openTag.isSkipRoot();
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ParentSoyNode) {
        visitChildren((ParentSoyNode) node);
      }
    }
  }
}
