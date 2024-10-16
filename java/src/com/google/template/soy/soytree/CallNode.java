
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.template.soy.base.SourceLocation.UNKNOWN;
import static com.google.template.soy.base.internal.BaseUtils.convertToUpperUnderscore;
import static com.google.template.soy.soytree.CommandTagAttribute.INVALID_ATTRIBUTE;
import static com.google.template.soy.soytree.MessagePlaceholder.PHEX_ATTR;
import static com.google.template.soy.soytree.MessagePlaceholder.PHNAME_ATTR;
import static com.google.template.soy.soytree.MessagePlaceholder.validatePlaceholderExample;
import static com.google.template.soy.soytree.MessagePlaceholder.validatePlaceholderName;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.base.internal.QuoteStyle;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.CommandTagAttribute.CommandTagAttributesHolder;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.MsgPlaceholderInitialNode;
import com.google.template.soy.soytree.SoyNode.SplitLevelTopNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyNode.StatementNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Node representing a call.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public abstract class CallNode extends AbstractParentCommandNode<CallParamNode>
    implements StandaloneNode,
        SplitLevelTopNode<CallParamNode>,
        StatementNode,
        HtmlContext.HtmlContextHolder,
        ExprHolderNode,
        MsgPlaceholderInitialNode,
        CommandTagAttributesHolder {

  /** Fallback base placeholder name. */
  private static final String FALLBACK_BASE_PLACEHOLDER_NAME = "XXX";

  static final String KEY = "key";
  static final String DATA = "data";
  static final String ERROR_FALLBACK = "errorfallback";

  /** True if this call is passing data="all". */
  private boolean isPassingAllData;

  /** Used for formatting */
  private final boolean selfClosing;

  /** Used for formatting */
  private ImmutableList<CommandTagAttribute> attributes;

  private final SourceLocation openTagLocation;

  private final MessagePlaceholder placeholder;

  /** The HTML context that the call is in, such as in HTML or Attributes. */
  @Nullable private HtmlContext htmlContext;

  /**
   * The call key, which is the encompassing template name along with position in template. This is
   * used to help with dom alignment in Incremental DOM backend.
   */
  @Nullable private String callKey;

  /**
   * Escaping directives to apply to the return value. With strict autoescaping, the result of each
   * call site is escaped, which is potentially a no-op if the template's return value is the
   * correct SanitizedContent object.
   *
   * <p>Set by the contextual rewriter.
   */
  private ImmutableList<SoyPrintDirective> escapingDirectives = ImmutableList.of();

  /** True if this node is within a HTML context. */
  private boolean isPcData = false;

  private final boolean errorFallbackSkip;

  /** Protected constructor for use by subclasses. */
  protected CallNode(
      int id,
      SourceLocation location,
      SourceLocation openTagLocation,
      String commandName,
      List<CommandTagAttribute> attributes,
      boolean selfClosing,
      ErrorReporter reporter) {
    super(id, location, commandName);

    this.attributes = ImmutableList.copyOf(attributes);
    this.selfClosing = selfClosing;
    this.openTagLocation = openTagLocation;
    this.errorFallbackSkip = initializeAttributes(attributes, reporter);
    this.placeholder = createPlaceholder(attributes, reporter);
  }

  private boolean initializeAttributes(List<CommandTagAttribute> attributes, ErrorReporter reporter) {
    boolean errorFallbackSkip = false;

    for (CommandTagAttribute attr : attributes) {
      String name = attr.getName().identifier();

      switch (name) {
        case DATA:
          this.isPassingAllData = attr.hasExprValue() || "all".equals(attr.getValue());
          if (!this.isPassingAllData) {
            attr.checkAsExpr(reporter);
          }
          break;
        case KEY:
          attr.checkAsExpr(reporter);
          break;
        case PHNAME_ATTR:
          validatePlaceholderName(attr.getValue(), attr.getValueLocation(), reporter);
          break;
        case PHEX_ATTR:
          validatePlaceholderExample(attr.getValue(), attr.getValueLocation(), reporter);
          break;
        case ERROR_FALLBACK:
          if (attr.getValue().equals("skip")) {
            errorFallbackSkip = true;
          } else {
            reporter.report(attr.getSourceLocation(), INVALID_ATTRIBUTE, ERROR_FALLBACK, "skip");
          }
          break;
        default:
          // do nothing, validated by subclasses
      }
    }
    return errorFallbackSkip;
  }

  private MessagePlaceholder createPlaceholder(List<CommandTagAttribute> attributes, ErrorReporter reporter) {
    String phName = attributes.stream()
        .filter(attr -> PHNAME_ATTR.equals(attr.getName().identifier()))
        .map(attr -> validatePlaceholderName(attr.getValue(), attr.getValueLocation(), reporter))
        .findFirst()
        .orElse(null);

    Optional<String> phExample = attributes.stream()
        .filter(attr -> PHEX_ATTR.equals(attr.getName().identifier()))
        .map(attr -> validatePlaceholderExample(attr.getValue(), attr.getValueLocation(), reporter))
        .findFirst();

    return (phName == null)
        ? MessagePlaceholder.create(FALLBACK_BASE_PLACEHOLDER_NAME, phExample)
        : MessagePlaceholder.createWithUserSuppliedName(
            convertToUpperUnderscore(phName), phName, attributes.stream()
                .filter(attr -> PHNAME_ATTR.equals(attr.getName().identifier()))
                .findFirst().map(CommandTagAttribute::getValueLocation).orElse(UNKNOWN),
            phExample);
  }

  /**
   * Copy constructor.
   *
   * @param orig The node to copy.
   */
  protected CallNode(CallNode orig, CopyState copyState) {
    super(orig, copyState);
    this.isPassingAllData = orig.isPassingAllData;
    this.placeholder = orig.placeholder;
    this.escapingDirectives = orig.escapingDirectives;
    this.callKey = orig.callKey;
    this.isPcData = orig.getIsPcData();
    this.htmlContext = orig.htmlContext;
    this.openTagLocation = orig.openTagLocation;
    this.selfClosing = orig.selfClosing;
    this.attributes =
        orig.attributes.stream().map(c -> c.copy(copyState)).collect(toImmutableList());
    this.errorFallbackSkip = orig.errorFallbackSkip;
    copyState.updateRefs(orig, this);
  }

  @Override
  public HtmlContext getHtmlContext() {
    return checkNotNull(
        htmlContext, "Cannot access HtmlContext before HtmlContextVisitor or InferenceEngine.");
  }

  public void setHtmlContext(HtmlContext value) {
    this.htmlContext = value;
  }

  public boolean isPassingData() {
    return isPassingAllData || getDataExpr() != null;
  }

  public void setTemplateCallKey(String key) {
    this.callKey = key;
  }

  public String getTemplateCallKey() {
    return callKey;
  }

  public boolean isPassingAllData() {
    return isPassingAllData;
  }

  public boolean isSelfClosing() {
    return this.selfClosing;
  }

  public boolean isErrorFallbackSkip() {
    return errorFallbackSkip;
  }

  @Override
  public List<CommandTagAttribute> getAttributes() {
    return attributes;
  }

  @Nullable
  public ExprRootNode getDataExpr() {
    return attributes.stream()
        .filter(a -> a.hasName(DATA) && a.hasExprValue())
        .findFirst()
        .map(a -> a.valueAsExprList().get(0))
        .orElse(null);
  }

  @Nullable
  public ExprRootNode getKeyExpr() {
    return attributes.stream()
        .filter(a -> a.hasName(KEY))
        .findFirst()
        .map(a -> a.valueAsExprList().get(0))
        .orElse(null);
  }

  public void setKeyExpr(ExprRootNode expr) {
    CommandTagAttribute existing = attributes.stream().filter(a -> a.hasName(KEY)).findFirst().orElse(null);
    List<CommandTagAttribute> newAttr = new ArrayList<>(attributes);

    if (existing != null) {
      newAttr.remove(existing);
    }
    if (expr != null) {
      newAttr.add(createCommandTagAttribute(existing, expr));
    }

    attributes = ImmutableList.copyOf(newAttr);
  }

  private CommandTagAttribute createCommandTagAttribute(
      CommandTagAttribute existing, ExprRootNode expr) {
    return new CommandTagAttribute(
        existing != null ? existing.getName() : Identifier.create(KEY, UNKNOWN),
        existing != null ? existing.getQuoteStyle() : QuoteStyle.DOUBLE,
        ImmutableList.of(expr.getRoot()),
        existing != null ? existing.getSourceLocation() : UNKNOWN);
  }

  public boolean getIsPcData() {
    return isPcData;
  }

  public void setIsPcData(boolean isPcData) {
    this.isPcData = isPcData;
  }

  @Override
  public MessagePlaceholder getPlaceholder() {
    return placeholder;
  }

  @Override
  public SamenessKey genSamenessKey() {
    return new IdentitySamenessKey(this);
  }

  @Override
  public String getTagString() {
    return getTagString(numChildren() == 0);
  }

  @Override
  public String toSourceString() {
    return (numChildren() == 0) ? getTagString() : super.toSourceString();
  }

  @Override
  public ImmutableList<ExprRootNode> getExprList() {
    return attributes.stream()
        .filter(CommandTagAttribute::hasExprValue)
        .flatMap(a -> a.valueAsExprList().stream())
        .collect(toImmutableList());
  }

  @SuppressWarnings("unchecked")
  @Override
  public ParentSoyNode<StandaloneNode> getParent() {
    return (ParentSoyNode<StandaloneNode>) super.getParent();
  }

  @Override
  public SourceLocation getOpenTagLocation() {
    return openTagLocation;
  }

  /** Returns the location of the callee name in the source code. */
  public abstract SourceLocation getSourceCalleeLocation();

  /**
   * Returns the escaping directives, applied from left to right.
   *
   * <p>It is an error to call this before the contextual rewriter has been run.
   */
  public ImmutableList<SoyPrintDirective> getEscapingDirectives() {
    return escapingDirectives;
  }

  /** Sets the inferred escaping directives. */
  public void setEscapingDirectives(ImmutableList<SoyPrintDirective> escapingDirectives) {
    this.escapingDirectives = escapingDirectives;
  }
}
