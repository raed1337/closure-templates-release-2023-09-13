
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

package com.google.template.soy.soytree;

import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.soytree.SoyNode.ConditionalBlockNode;

/**
 * Node representing the 'else' block within an 'if' statement.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public final class IfElseNode extends AbstractBlockCommandNode implements ConditionalBlockNode {

  /**
   * Constructs an IfElseNode with the specified id and source location.
   *
   * @param id The id for this node.
   * @param sourceLocation The node's source location.
   */
  public IfElseNode(int id, SourceLocation sourceLocation, SourceLocation openTagLocation) {
    super(id, sourceLocation, openTagLocation, "else");
  }

  /**
   * Copy constructor for creating a duplicate of the original IfElseNode.
   *
   * @param orig The node to copy.
   * @param copyState The state that tracks the copying process.
   */
  private IfElseNode(IfElseNode orig, CopyState copyState) {
    super(orig, copyState);
  }

  /**
   * Returns the kind of this node, which is IF_ELSE_NODE.
   *
   * @return The kind of the node.
   */
  @Override
  public Kind getKind() {
    return Kind.IF_ELSE_NODE;
  }

  /**
   * Converts this node to its source string representation.
   *
   * @return A string representation of the node's source.
   */
  @Override
  public String toSourceString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getTagString());
    appendSourceStringForChildren(sb);
    // Note: No end tag.
    return sb.toString();
  }

  /**
   * Creates a copy of this IfElseNode.
   *
   * @param copyState The state that tracks the copying process.
   * @return A new IfElseNode that is a copy of this one.
   */
  @Override
  public IfElseNode copy(CopyState copyState) {
    return new IfElseNode(this, copyState);
  }
}
