
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

package com.google.template.soy.soytree;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Base class for html tags. Provides easy access to the {@link TagName}.
 *
 * <p>The first child is guaranteed to be the tag name, any after that are guaranteed to be in
 * attribute context. There is always at least one child.
 *
 * <p>TODO(b/123090196): Merge {@link TagName} API into this class.
 */
public abstract class HtmlTagNode extends AbstractParentSoyNode<StandaloneNode>
    implements StandaloneNode {

  public enum TagExistence {
    IN_TEMPLATE,
    SYNTHETIC
  }

  private final TagName tagName;
  private final TagExistence tagExistence;
  private final boolean selfClosing;
  private final List<HtmlTagNode> taggedPairs = new ArrayList<>();

  protected HtmlTagNode(
      int id,
      StandaloneNode node,
      SourceLocation sourceLocation,
      TagExistence tagExistence,
      boolean selfClosing) {
    super(id, sourceLocation);
    checkNotNull(node);
    checkState(node.getParent() == null);
    addChild(node);
    this.tagName = tagNameFromNode(node);
    this.tagExistence = tagExistence;
    this.selfClosing = selfClosing;
  }

  protected HtmlTagNode(HtmlTagNode orig, CopyState copyState) {
    super(orig, copyState);
    this.tagExistence = orig.tagExistence;
    this.tagName = rebuildTagName(orig);
    copyState.updateRefs(orig, this);
    for (HtmlTagNode matchingNode : orig.taggedPairs) {
      copyState.registerRefListener(matchingNode, taggedPairs::add);
    }
    this.selfClosing = orig.selfClosing;
  }

  @SuppressWarnings("unchecked")
  @Override
  public final ParentSoyNode<StandaloneNode> getParent() {
    return (ParentSoyNode<StandaloneNode>) super.getParent();
  }

  public final TagName getTagName() {
    return tagName;
  }

  public List<HtmlTagNode> getTaggedPairs() {
    return this.taggedPairs;
  }

  public void addTagPair(HtmlTagNode node) {
    if (!this.taggedPairs.contains(node)) {
      this.taggedPairs.add(node);
    }
  }

  public boolean hasUnpredictableTagLocation() {
    if (!getTagName().isStatic()) {
      return true;
    }
    return isSelfClosingOrDefinitelyVoid() ? false : checkTaggedPairsLocation();
  }

  private boolean isSelfClosingOrDefinitelyVoid() {
    return isSelfClosing() || getTagName().isDefinitelyVoid();
  }

  private boolean checkTaggedPairsLocation() {
    if (getTaggedPairs().size() != 1 || getTaggedPairs().get(0).getTaggedPairs().size() != 1) {
      return true;
    }
    var parent = getParent();
    var closeTag = getTaggedPairs().get(0);
    var otherParent = closeTag.getParent();
    return parent != otherParent;
  }

  public boolean isSynthetic() {
    return tagExistence == TagExistence.SYNTHETIC;
  }

  @Nullable
  public HtmlAttributeNode getDirectAttributeNamed(String attrName) {
    for (int i = 1; i < numChildren(); i++) {
      StandaloneNode child = getChild(i);
      if (child instanceof HtmlAttributeNode && 
          ((HtmlAttributeNode) child).definitelyMatchesAttributeName(attrName)) {
        return (HtmlAttributeNode) child;
      }
    }
    return null;
  }

  public boolean isSelfClosing() {
    return selfClosing;
  }

  private static TagName tagNameFromNode(StandaloneNode rawTextOrPrintNode) {
    checkState(
        rawTextOrPrintNode instanceof RawTextNode || rawTextOrPrintNode instanceof PrintNode);
    return rawTextOrPrintNode instanceof RawTextNode
        ? new TagName((RawTextNode) rawTextOrPrintNode)
        : new TagName((PrintNode) rawTextOrPrintNode);
  }

  private TagName rebuildTagName(HtmlTagNode orig) {
    if (orig.numChildren() > 0) {
      StandaloneNode tagChild = orig.getChild(0);
      return tagNameFromNode(tagChild);
    }
    return null;
  }
}
