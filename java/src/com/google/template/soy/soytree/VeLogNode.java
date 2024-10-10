
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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.template.soy.soytree.SoyTreeUtils.getNodeAsHtmlTagNode;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.soytree.CommandTagAttribute.CommandTagAttributesHolder;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.MsgBlockNode;
import com.google.template.soy.soytree.SoyNode.StatementNode;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Node for a <code {@literal {}velog...}</code> statement.
 */
public final class VeLogNode extends AbstractBlockCommandNode
    implements ExprHolderNode, StatementNode, MsgBlockNode, CommandTagAttributesHolder {

  static final class SamenessKey {
    private VeLogNode delegate;

    private SamenessKey(VeLogNode delegate) {
      this.delegate = delegate;
    }

    private SamenessKey(SamenessKey orig, CopyState copyState) {
      this.delegate = orig.delegate;
      copyState.registerRefListener(orig.delegate, newDelegate -> this.delegate = newDelegate);
    }

    SamenessKey copy(CopyState copyState) {
      return new SamenessKey(this, copyState);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof SamenessKey)) {
        return false;
      }
      ExprEquivalence exprEquivalence = new ExprEquivalence();
      SamenessKey otherKey = (SamenessKey) other;
      return exprEquivalence.equivalent(delegate.veDataExpr, otherKey.delegate.veDataExpr)
          && exprEquivalence.equivalent(delegate.logonlyExpr, otherKey.delegate.logonlyExpr);
    }

    @Override
    public int hashCode() {
      return new ExprEquivalence().hash(Arrays.asList(delegate.veDataExpr, delegate.logonlyExpr));
    }
  }

  private final ExprRootNode veDataExpr;
  private boolean needsSyntheticVelogNode = false;
  private final List<CommandTagAttribute> attributes;
  @Nullable private final ExprRootNode logonlyExpr;

  public VeLogNode(
      int id,
      SourceLocation location,
      SourceLocation openTagLocation,
      ExprNode veDataExpr,
      List<CommandTagAttribute> attributes,
      ErrorReporter errorReporter) {
    super(id, location, openTagLocation, "velog");
    this.veDataExpr = new ExprRootNode(checkNotNull(veDataExpr));
    this.attributes = attributes;
    this.logonlyExpr = extractLogonlyExpr(attributes, errorReporter);
  }

  private ExprRootNode extractLogonlyExpr(List<CommandTagAttribute> attributes, ErrorReporter errorReporter) {
    for (CommandTagAttribute attr : attributes) {
      if ("logonly".equals(attr.getName().identifier())) {
        return attr.valueAsExpr(errorReporter);
      } else {
        errorReporter.report(
            attr.getName().location(),
            CommandTagAttribute.UNSUPPORTED_ATTRIBUTE_KEY,
            attr.getName().identifier(),
            "velog",
            ImmutableList.of("logonly"));
      }
    }
    return null;
  }

  private VeLogNode(VeLogNode orig, CopyState copyState) {
    super(orig, copyState);
    this.veDataExpr = orig.veDataExpr.copy(copyState);
    this.attributes =
        orig.attributes.stream().map(c -> c.copy(copyState)).collect(toImmutableList());
    this.logonlyExpr = orig.logonlyExpr == null ? null : orig.logonlyExpr.copy(copyState);
    this.needsSyntheticVelogNode = orig.needsSyntheticVelogNode;
    copyState.updateRefs(orig, this);
  }

  @Override
  public List<CommandTagAttribute> getAttributes() {
    return this.attributes;
  }

  SamenessKey getSamenessKey() {
    return new SamenessKey(this);
  }

  public void setNeedsSyntheticVelogNode(boolean needsSyntheticVelogNode) {
    this.needsSyntheticVelogNode = needsSyntheticVelogNode;
  }

  public boolean needsSyntheticVelogNode() {
    return needsSyntheticVelogNode;
  }

  public ExprRootNode getVeDataExpression() {
    return veDataExpr;
  }

  @Nullable
  public ExprRootNode getLogonlyExpression() {
    return logonlyExpr;
  }

  @Override
  public Kind getKind() {
    return Kind.VE_LOG_NODE;
  }

  @Nullable
  public HtmlOpenTagNode getOpenTagNode() {
    return getHtmlTagNode(0, true);
  }

  @Nullable
  public HtmlCloseTagNode getCloseTagNode() {
    return getHtmlTagNode(numChildren() - 1, false);
  }

  @Nullable
  private <T extends HtmlTagNode> T getHtmlTagNode(int index, boolean openTag) {
    if (numChildren() > index) {
      return (T) getNodeAsHtmlTagNode(getChild(index), openTag);
    }
    return null;
  }

  @Override
  public String getCommandText() {
    return buildCommandText();
  }

  private String buildCommandText() {
    return veDataExpr.toSourceString() +
           (logonlyExpr != null ? " logonly=\"" + logonlyExpr.toSourceString() + "\"" : "");
  }

  @Override
  @SuppressWarnings("unchecked")
  public ParentSoyNode<StandaloneNode> getParent() {
    return (ParentSoyNode<StandaloneNode>) super.getParent();
  }

  @Override
  public VeLogNode copy(CopyState copyState) {
    return new VeLogNode(this, copyState);
  }

  @Override
  public ImmutableList<ExprRootNode> getExprList() {
    ImmutableList.Builder<ExprRootNode> builder = ImmutableList.builder();
    builder.add(veDataExpr);
    if (logonlyExpr != null) {
      builder.add(logonlyExpr);
    }
    return builder.build();
  }

  @Override
  public String toSourceString() {
    return buildSourceString();
  }

  private String buildSourceString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getTagString());
    appendSourceStringForChildren(sb);
    sb.append("{/velog}");
    return sb.toString();
  }
}
