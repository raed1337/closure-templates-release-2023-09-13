
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

import static com.google.template.soy.soytree.MessagePlaceholder.PHEX_ATTR;
import static com.google.template.soy.soytree.MessagePlaceholder.PHNAME_ATTR;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.ForNode;
import com.google.template.soy.soytree.HtmlAttributeNode;
import com.google.template.soy.soytree.HtmlAttributeValueNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlTagNode;
import com.google.template.soy.soytree.IfNode;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.RenderUnitNode;
import com.google.template.soy.soytree.SoyNode.SplitLevelTopNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.SwitchNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A compiler pass that performs HTML validation that is always enabled, as opposed to
 * StrictHtmlValidationPass which is opt-out.
 */
final class BasicHtmlValidationPass implements CompilerFilePass {
  private static final SoyErrorKind MULTIPLE_ATTRIBUTES =
      SoyErrorKind.of("Found multiple ''{0}'' attributes with the same name.");

  private static final SoyErrorKind UNEXPECTED_CLOSE_TAG_CONTENT =
      SoyErrorKind.of("Unexpected close tag content, only whitespace is allowed in close tags.");
  private static final SoyErrorKind BAD_ID_VALUE =
      SoyErrorKind.of(
          "Html id attributes should not be valid JavaScript identifiers, consider hyphenating the"
              + " id.");

  private final ErrorReporter errorReporter;

  BasicHtmlValidationPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    SoyTreeUtils.allNodesOfType(file, HtmlTagNode.class)
        .forEach(node -> {
          validateHtmlTagNode(node);
        });
    SoyTreeUtils.allNodesOfType(file, RenderUnitNode.class)
        .filter(unit -> !unit.isImplicitContentKind() && unit.getContentKind() == SanitizedContentKind.ATTRIBUTES)
        .forEach(this::checkForDuplicateAttributes);
    SoyTreeUtils.allNodesOfType(file, HtmlAttributeNode.class)
        .forEach(this::warnOnIdAttributesMatchingJsIdentifiers);
  }

  private void validateHtmlTagNode(HtmlTagNode node) {
    checkForDuplicateAttributes(node);
    if (node instanceof HtmlCloseTagNode) {
      checkCloseTagChildren((HtmlCloseTagNode) node);
    }
  }

  private static final Pattern JS_IDENTIFIER_PATTERN =
      Pattern.compile("^[$_\\p{IsLetter}][$_\\p{IsLetter}\\p{IsDigit}]*$");

  private static boolean isIdShapedValue(HtmlAttributeValueNode node) {
    return node.numChildren() == 1 && isSingleChildIdValue(node.getChild(0));
  }

  private static boolean isSingleChildIdValue(StandaloneNode attrValueNode) {
    if (attrValueNode instanceof RawTextNode) {
      return JS_IDENTIFIER_PATTERN.matcher(((RawTextNode) attrValueNode).getRawText()).matches();
    } else if (attrValueNode instanceof PrintNode) {
      ExprNode exprRoot = ((PrintNode) attrValueNode).getExpr().getRoot();
      return exprRoot instanceof FunctionNode && ((FunctionNode) exprRoot).getFunctionName().equals("xid");
    }
    return false;
  }

  private void warnOnIdAttributesMatchingJsIdentifiers(HtmlAttributeNode attributeNode) {
    if (attributeNode.definitelyMatchesAttributeName("id") && attributeNode.hasValue()) {
      HtmlAttributeValueNode child = getHtmlAttributeValueNode(attributeNode);
      if (child != null && isIdShapedValue(child)) {
        errorReporter.warn(child.getSourceLocation(), BAD_ID_VALUE);
      }
    }
  }

  private HtmlAttributeValueNode getHtmlAttributeValueNode(HtmlAttributeNode attributeNode) {
    SoyNode child = attributeNode.getChild(1);
    return child instanceof HtmlAttributeValueNode ? (HtmlAttributeValueNode) child : null;
  }

  private void checkForDuplicateAttributes(ParentSoyNode<StandaloneNode> parentNode) {
    DuplicateAttributesVisitor visitor = new DuplicateAttributesVisitor();
    List<StandaloneNode> children = getChildrenExcludingTagName(parentNode);
    for (SoyNode child : children) {
      visitor.exec(child);
    }
  }

  private List<StandaloneNode> getChildrenExcludingTagName(ParentSoyNode<StandaloneNode> parentNode) {
    List<StandaloneNode> children = parentNode.getChildren();
    if (parentNode instanceof HtmlTagNode) {
      return children.subList(1, children.size()); // Skip the first child (tag name)
    }
    return children;
  }

  private void checkCloseTagChildren(HtmlCloseTagNode closeTag) {
    HtmlAttributeNode phNameAttribute = closeTag.getDirectAttributeNamed(PHNAME_ATTR);
    HtmlAttributeNode phExAttribute = closeTag.getDirectAttributeNamed(PHEX_ATTR);
    for (int i = 1; i < closeTag.numChildren(); i++) {
      StandaloneNode child = closeTag.getChild(i);
      if (child != phNameAttribute && child != phExAttribute) {
        errorReporter.report(child.getSourceLocation(), UNEXPECTED_CLOSE_TAG_CONTENT);
      }
    }
  }

  private final class DuplicateAttributesVisitor extends AbstractSoyNodeVisitor<Set<String>> {
    private final Set<String> foundSoFar;

    DuplicateAttributesVisitor() {
      this(ImmutableSet.of());
    }

    DuplicateAttributesVisitor(Set<String> foundSoFar) {
      this.foundSoFar = new HashSet<>(foundSoFar);
    }

    @Override
    public Set<String> exec(SoyNode n) {
      visit(n);
      return foundSoFar;
    }

    @Override
    protected void visitHtmlAttributeNode(HtmlAttributeNode node) {
      String attributeKey = node.getStaticKey();
      if (attributeKey != null) {
        attributeKey = Ascii.toLowerCase(attributeKey);
        if (!foundSoFar.add(attributeKey)) {
          errorReporter.report(node.getSourceLocation(), MULTIPLE_ATTRIBUTES, attributeKey);
        }
      }
    }

    @Override
    protected void visitIfNode(IfNode node) {
      visitControlFlowNode(node, node.hasElse());
    }

    @Override
    protected void visitSwitchNode(SwitchNode node) {
      visitControlFlowNode(node, node.hasDefaultCase());
    }

    @Override
    protected void visitForNode(ForNode node) {
      visitControlFlowNode(node, false);
    }

    private void visitControlFlowNode(SplitLevelTopNode<? extends BlockNode> parent, boolean exhaustive) {
      if (exhaustive) {
        handleExhaustiveControlFlow(parent);
      } else {
        for (BlockNode block : parent.getChildren()) {
          new DuplicateAttributesVisitor(foundSoFar).exec(block);
        }
      }
    }

    private void handleExhaustiveControlFlow(SplitLevelTopNode<? extends BlockNode> parent) {
      Set<String> definiteBlockAttrs = null;
      for (BlockNode block : parent.getChildren()) {
        Set<String> blockAttrs = new DuplicateAttributesVisitor(foundSoFar).exec(block);
        if (definiteBlockAttrs == null) {
          definiteBlockAttrs = new HashSet<>(Sets.difference(blockAttrs, foundSoFar));
        } else {
          definiteBlockAttrs.retainAll(blockAttrs); // only retain the intersection
        }
      }
      foundSoFar.addAll(definiteBlockAttrs);
    }

    @Override
    protected void visitCallNode(CallNode node) {
      // don't visit children
    }

    @Override
    protected void visitLetContentNode(LetContentNode node) {
      // don't visit children
    }

    @Override
    protected void visitSoyNode(SoyNode node) {
      if (node instanceof ParentSoyNode) {
        visitChildren((ParentSoyNode) node);
      }
    }
  }
}
