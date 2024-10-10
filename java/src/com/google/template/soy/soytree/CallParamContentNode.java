
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

import static com.google.template.soy.soytree.CommandTagAttribute.UNSUPPORTED_ATTRIBUTE_KEY_SINGLE;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.base.internal.SetOnce;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.basetree.MixinParentNode;
import com.google.template.soy.basetree.Node;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.soytree.CommandTagAttribute.CommandTagAttributesHolder;
import com.google.template.soy.soytree.SoyNode.RenderUnitNode;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Node representing a 'param' with content.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public final class CallParamContentNode extends CallParamNode
    implements RenderUnitNode, CommandTagAttributesHolder {

  /** The mixin object that implements the ParentNode functionality. */
  private final MixinParentNode<StandaloneNode> parentMixin;

  /** The param's content kind. */
  private final SetOnce<SanitizedContentKind> contentKind;

  private final SourceLocation openTagLocation;
  @Nullable private final CommandTagAttribute kindAttr;

  public CallParamContentNode(
      int id,
      SourceLocation location,
      SourceLocation openTagLocation,
      Identifier key,
      @Nullable CommandTagAttribute kindAttr,
      ErrorReporter errorReporter) {
    super(id, location, key);
    this.parentMixin = new MixinParentNode<>(this);
    this.contentKind = new SetOnce<>();
    this.kindAttr = kindAttr;
    this.openTagLocation = openTagLocation;
    setContentKindFromAttribute(errorReporter);
  }

  private void setContentKindFromAttribute(ErrorReporter errorReporter) {
    if (kindAttr != null) {
      if (!kindAttr.hasName("kind")) {
        errorReporter.report(
            kindAttr.getName().location(),
            UNSUPPORTED_ATTRIBUTE_KEY_SINGLE,
            kindAttr.getName().identifier(),
            "param",
            "kind");
        contentKind.set(SanitizedContentKind.HTML);
      } else {
        Optional<SanitizedContentKind> parsedKind = kindAttr.valueAsContentKind(errorReporter);
        contentKind.set(parsedKind.orElse(SanitizedContentKind.HTML));
      }
    } else {
      contentKind.set(SanitizedContentKind.HTML);
    }
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  private CallParamContentNode(CallParamContentNode orig, CopyState copyState) {
    super(orig, copyState);
    this.parentMixin = new MixinParentNode<>(orig.parentMixin, this, copyState);
    this.contentKind = orig.contentKind.copy();
    this.openTagLocation = orig.openTagLocation;
    this.kindAttr = orig.kindAttr == null ? null : orig.kindAttr.copy(copyState);
  }

  @Override
  public Kind getKind() {
    return Kind.CALL_PARAM_CONTENT_NODE;
  }

  @Override
  public SanitizedContentKind getContentKind() {
    return contentKind.get();
  }

  @Override
  public boolean isImplicitContentKind() {
    return !contentKind.isPresent();
  }

  public void setContentKind(SanitizedContentKind kind) {
    contentKind.set(kind);
  }

  @Override
  public String getCommandText() {
    return getKey().identifier() + (isImplicitContentKind() ? "" : " kind=\"" + contentKind.get().asAttributeValue() + "\"");
  }

  @Override
  public String toSourceString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getTagString());
    appendSourceStringForChildren(sb);
    sb.append("{/").append(getCommandName()).append('}');
    return sb.toString();
  }

  @Override
  public ImmutableList<CommandTagAttribute> getAttributes() {
    return kindAttr == null ? ImmutableList.of() : ImmutableList.of(kindAttr);
  }

  @Override
  public CallParamContentNode copy(CopyState copyState) {
    return new CallParamContentNode(this, copyState);
  }

  // -----------------------------------------------------------------------------------------------
  // ParentSoyNode stuff.

  @Override
  public int numChildren() {
    return parentMixin.numChildren();
  }

  @Override
  public StandaloneNode getChild(int index) {
    return parentMixin.getChild(index);
  }

  @Override
  public int getChildIndex(Node child) {
    return parentMixin.getChildIndex(child);
  }

  @Override
  public List<StandaloneNode> getChildren() {
    return parentMixin.getChildren();
  }

  @Override
  public void addChild(StandaloneNode child) {
    parentMixin.addChild(child);
  }

  @Override
  public void addChild(int index, StandaloneNode child) {
    parentMixin.addChild(index, child);
  }

  @Override
  public void removeChild(int index) {
    parentMixin.removeChild(index);
  }

  @Override
  public void removeChild(StandaloneNode child) {
    parentMixin.removeChild(child);
  }

  @Override
  public void replaceChild(int index, StandaloneNode newChild) {
    parentMixin.replaceChild(index, newChild);
  }

  @Override
  public void replaceChild(StandaloneNode currChild, StandaloneNode newChild) {
    parentMixin.replaceChild(currChild, newChild);
  }

  @Override
  public void clearChildren() {
    parentMixin.clearChildren();
  }

  @Override
  public void addChildren(List<? extends StandaloneNode> children) {
    parentMixin.addChildren(children);
  }

  @Override
  public void addChildren(int index, List<? extends StandaloneNode> children) {
    parentMixin.addChildren(index, children);
  }

  @Override
  public void appendSourceStringForChildren(StringBuilder sb) {
    parentMixin.appendSourceStringForChildren(sb);
  }

  @Override
  public SourceLocation getOpenTagLocation() {
    return openTagLocation;
  }
}
