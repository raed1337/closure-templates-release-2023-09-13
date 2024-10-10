
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
package com.google.template.soy.soytree;

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyNode.StatementNode;

/**
 * Node representing a 'skip' statement, e.g. {@code <div {skip}></div>}. This configures DOM nodes
 * to be unconditionally skipped in incremental dom.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public final class SkipNode extends AbstractParentCommandNode<StandaloneNode>
    implements StatementNode {

  private final boolean skipOnlyChildren;

  public SkipNode(int id, SourceLocation location) {
    super(id, location, "skip");
    this.skipOnlyChildren = false;
  }

  public SkipNode(int id, SourceLocation location, boolean skipOnlyChildren) {
    super(id, location, "skipchildren");
    this.skipOnlyChildren = skipOnlyChildren;
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  private SkipNode(SkipNode orig, CopyState copyState) {
    super(orig, copyState);
    for (StandaloneNode node : orig.getChildren()) {
      addChild(node.copy(copyState));
    }
    this.skipOnlyChildren = orig.skipOnlyChildren;
  }

  @Override
  public Kind getKind() {
    return Kind.SKIP_NODE;
  }

  @Override
  public String getCommandText() {
    return skipOnlyChildren ? "{skipchildren}" : "{skip}";
  }

  @Override
  public String toSourceString() {
    StringBuilder builder = new StringBuilder();
    builder.append(getCommandText());
    appendChildrenSourceString(builder);
    builder.append(getClosingTag());
    return builder.toString();
  }

  private void appendChildrenSourceString(StringBuilder builder) {
    for (StandaloneNode node : this.getChildren()) {
      builder.append(node.toSourceString());
    }
  }

  private String getClosingTag() {
    return skipOnlyChildren ? "{/skipchildren}" : "{/skip}";
  }

  public boolean skipOnlyChildren() {
    return this.skipOnlyChildren;
  }

  @SuppressWarnings("unchecked")
  @Override
  public ParentSoyNode<StandaloneNode> getParent() {
    return (ParentSoyNode<StandaloneNode>) super.getParent();
  }

  @Override
  public SkipNode copy(CopyState copyState) {
    return new SkipNode(this, copyState);
  }
}
