
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

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.shared.internal.Sanitizers;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.IfCondNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.RenderUnitNode;
import com.google.template.soy.soytree.TemplateNode;

/**
 * Adds html attributes to all html tags that document what template the tag came from.
 *
 * <p>This pass supports the debug view for inspecting template information in rendered pages. See
 * go/inspect-template-info-fw for details.
 *
 * <p>The strategy is to annotate every 'root' level {@link HtmlOpenTagNode} in a template, let or
 * param. This should make it possible to track every part of the DOM back to the template that
 * rendered it without adding too many annotations to the code.
 *
 * <p>This approach does have some drawbacks:
 *
 * <ul>
 *   <li>Templates without HTML open tags will not get annotated and if they do produce content they
 *       will get linked to their callers.
 *   <li>Our process for identifying 'root' nodes is a bit sloppy since we can't rely on the HTML
 *       being well formed.
 * </ul>
 *
 * <p>However, this heuristic should be good enough for most well structured templates.
 *
 * <p>One alternative approach would be to rely on the stricthtmlvalidationpass. We could modify the
 * pass to always run but not report errors for non-stricthtml templates and then we could use it to
 * annotate the AST with depth information, then this pass could just consume it instead of
 * attempting to calculate it itself. However, this is likely to be significantly more complex than
 * the current approach.
 */
@RunBefore(
    // So we don't need to worry about types for synthetic expressions.
    ResolveExpressionTypesPass.class)
final class AddDebugAttributesPass implements CompilerFilePass {

  public static final String DATA_DEBUG_SOY = "data-debug-soy";

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    new Visitor(nodeIdGen).exec(file);
  }

  private static final class Visitor extends AbstractSoyNodeVisitor<Void> {
    /** Tracks the user name of the current template. */
    String currentTemplate;
    /**
     * Tracks the number of open tags minus the number of close tags in a block (clamped to be
     * non-negative).
     */
    int tagDepth;
    final IdGenerator nodeIdGen;

    Visitor(IdGenerator nodeIdGen) {
      this.nodeIdGen = nodeIdGen;
    }

    @Override
    protected void visitTemplateNode(TemplateNode node) {
      currentTemplate = node.getTemplateNameForUserMsgs();
      visitBlock(node);
      currentTemplate = null;
    }

    private void visitBlock(BlockNode node) {
      int oldDepth = tagDepth;
      if (node instanceof RenderUnitNode) {
        tagDepth = 0;
      }
      visitChildren(node);
      tagDepth = oldDepth; // reset at the end of a block
    }

    @Override
    protected void visitHtmlOpenTagNode(HtmlOpenTagNode node) {
      addDebugAttribute(node);
      manageTagDepthOnOpen(node);
      visitChildren(node);
    }

    private void addDebugAttribute(HtmlOpenTagNode node) {
      if (tagDepth == 0 && node.getTagName().isStatic()) {
        node.addChild(createSoyDebug(node.getSourceLocation()));
      }
    }

    private void manageTagDepthOnOpen(HtmlOpenTagNode node) {
      if (!node.isSelfClosing() && !node.getTagName().isDefinitelyVoid()) {
        tagDepth++;
      }
    }

    @Override
    protected void visitHtmlCloseTagNode(HtmlCloseTagNode node) {
      manageTagDepthOnClose();
      visitChildren(node);
    }

    private void manageTagDepthOnClose() {
      tagDepth--;
      if (tagDepth < 0) {
        tagDepth = 0; // clamp to non-negative
      }
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof BlockNode) {
        visitBlock((BlockNode) node);
      } else if (node instanceof ParentSoyNode) {
        visitChildren((ParentSoyNode<?>) node);
      }
    }

    private IfNode createSoyDebug(SourceLocation insertionLocation) {
      IfNode ifNode = new IfNode(nodeIdGen.genId(), insertionLocation);
      FunctionNode funcNode = createDebugFunctionNode(insertionLocation);
      IfCondNode ifCondNode = new IfCondNode(nodeIdGen.genId(), insertionLocation, SourceLocation.UNKNOWN, "if", funcNode);
      HtmlAttributeNode attribute = createHtmlAttribute(insertionLocation);
      ifCondNode.addChild(attribute);
      ifNode.addChild(ifCondNode);
      return ifNode;
    }

    private FunctionNode createDebugFunctionNode(SourceLocation insertionLocation) {
      return FunctionNode.newPositional(
          Identifier.create(BuiltinFunction.DEBUG_SOY_TEMPLATE_INFO.getName(), insertionLocation),
          BuiltinFunction.DEBUG_SOY_TEMPLATE_INFO,
          insertionLocation);
    }

    private HtmlAttributeNode createHtmlAttribute(SourceLocation insertionLocation) {
      HtmlAttributeNode attribute = new HtmlAttributeNode(nodeIdGen.genId(), insertionLocation, insertionLocation.getBeginPoint());
      attribute.addChild(new RawTextNode(nodeIdGen.genId(), DATA_DEBUG_SOY, insertionLocation));
      HtmlAttributeValueNode attrValue = new HtmlAttributeValueNode(nodeIdGen.genId(), insertionLocation, HtmlAttributeValueNode.Quotes.DOUBLE);
      attribute.addChild(attrValue);
      attrValue.addChild(createAttributeValueNode(insertionLocation));
      return attribute;
    }

    private RawTextNode createAttributeValueNode(SourceLocation insertionLocation) {
      return new RawTextNode(
          nodeIdGen.genId(),
          Sanitizers.escapeHtmlAttribute(currentTemplate + " " + insertionLocation.getFilePath().path() + ":" + insertionLocation.getBeginLine()),
          insertionLocation);
    }
  }
}
