
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

import static com.google.template.soy.base.internal.BaseUtils.convertToUpperUnderscore;
import static com.google.template.soy.soytree.MessagePlaceholder.PHEX_ATTR;
import static com.google.template.soy.soytree.MessagePlaceholder.PHNAME_ATTR;
import static com.google.template.soy.soytree.MessagePlaceholder.validatePlaceholderExample;
import static com.google.template.soy.soytree.MessagePlaceholder.validatePlaceholderName;

import com.google.auto.value.AutoValue;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.soytree.SoyNode.MsgPlaceholderInitialNode;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Node representing an HTML tag within a {@code msg} statement/block.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public final class MsgHtmlTagNode extends AbstractBlockNode implements MsgPlaceholderInitialNode {

  private static final SoyErrorKind DYNAMIC_TAG_NAME_IN_MSG_BLOCK =
      SoyErrorKind.of("HTML tags within ''msg'' blocks must use constant tag names.");
  private static final SoyErrorKind INVALID_ATTRIBUTE =
      SoyErrorKind.of("''{0}'' attribute is not a constant.");

  /** Creates a {@link MsgHtmlTagNode} from a {@link HtmlTagNode}. */
  public static MsgHtmlTagNode fromNode(
      int id, HtmlTagNode tagNode, @Nullable VeLogNode velogParent, ErrorReporter errorReporter) {
    Optional<String> phExample = extractPlaceholderExample(tagNode, errorReporter);
    MessagePlaceholder placeholder = extractPlaceholder(tagNode, phExample, errorReporter);
    String fullTagText = getFullTagText(tagNode);
    String lcTagName = getLcTagName(errorReporter, tagNode.getTagName());
    boolean isSelfClosing = determineSelfClosing(tagNode);
    VeLogNode.SamenessKey key = velogParent != null ? velogParent.getSamenessKey() : null;

    if (placeholder == null) {
      placeholder = MessagePlaceholder.create(genBasePhName(lcTagName, isSelfClosing), phExample);
    }

    return new MsgHtmlTagNode(
        id,
        tagNode.getSourceLocation(),
        placeholder,
        lcTagName,
        isSelfClosing,
        fullTagText != null ? SamenessKeyImpl.create(placeholder.name(), fullTagText, key) : null,
        tagNode);
  }

  private static Optional<String> extractPlaceholderExample(HtmlTagNode tagNode, ErrorReporter errorReporter) {
    RawTextNode phExampleNode = getAttributeValue(tagNode, PHEX_ATTR, errorReporter);
    return Optional.ofNullable(phExampleNode)
        .map(node -> validatePlaceholderExample(node.getRawText(), node.getSourceLocation(), errorReporter));
  }

  private static MessagePlaceholder extractPlaceholder(HtmlTagNode tagNode, Optional<String> phExample, ErrorReporter errorReporter) {
    RawTextNode userSuppliedPhNameNode = getAttributeValue(tagNode, PHNAME_ATTR, errorReporter);
    MessagePlaceholder placeholder = null;

    if (userSuppliedPhNameNode != null) {
      SourceLocation phNameLocation = userSuppliedPhNameNode.getSourceLocation();
      String phName = validatePlaceholderName(userSuppliedPhNameNode.getRawText(), phNameLocation, errorReporter);
      if (phName != null) {
        placeholder = MessagePlaceholder.createWithUserSuppliedName(
            convertToUpperUnderscore(phName), phName, phNameLocation, phExample);
      }
    }

    return placeholder;
  }

  private static boolean determineSelfClosing(HtmlTagNode tagNode) {
    if (tagNode instanceof HtmlCloseTagNode) {
      return false;
    } else if (tagNode instanceof HtmlOpenTagNode) {
      return ((HtmlOpenTagNode) tagNode).isSelfClosing();
    }
    return false;
  }

  @Nullable
  private static String getFullTagText(HtmlTagNode openTagNode) {
    return SoyTreeUtils.allNodes(openTagNode)
            .anyMatch(node -> !(node instanceof RawTextNode || node instanceof HtmlAttributeNode || node instanceof HtmlAttributeValueNode || node instanceof HtmlOpenTagNode || node instanceof HtmlCloseTagNode))
        ? null
        : openTagNode.toSourceString();
  }

  @Nullable
  private static RawTextNode getAttributeValue(HtmlTagNode tagNode, String name, ErrorReporter errorReporter) {
    HtmlAttributeNode attribute = tagNode.getDirectAttributeNamed(name);
    if (attribute == null) {
      return null;
    }
    RawTextNode value = getAttributeValue(attribute, name, errorReporter);
    tagNode.removeChild(attribute);
    return value;
  }

  @Nullable
  private static RawTextNode getAttributeValue(HtmlAttributeNode htmlAttributeNode, String name, ErrorReporter errorReporter) {
    StandaloneNode valueNode = htmlAttributeNode.getChild(1);
    if (valueNode instanceof HtmlAttributeValueNode) {
      HtmlAttributeValueNode attributeValueNode = (HtmlAttributeValueNode) valueNode;
      if (attributeValueNode.numChildren() == 1 && attributeValueNode.getChild(0) instanceof RawTextNode) {
        return (RawTextNode) attributeValueNode.getChild(0);
      }
    }
    errorReporter.report(valueNode.getSourceLocation(), INVALID_ATTRIBUTE, name);
    return null;
  }

  private static String getLcTagName(ErrorReporter errorReporter, TagName tagName) {
    if (!tagName.isStatic()) {
      errorReporter.report(tagName.getTagLocation(), DYNAMIC_TAG_NAME_IN_MSG_BLOCK);
      return "error";
    }
    return tagName.getStaticTagNameAsLowerCase();
  }

  /** Returns the lower-case HTML tag name (includes '/' for end tags). */
  public String getLcTagName() {
    return lcTagName;
  }

  private static final ImmutableMap<String, String> LC_TAG_NAME_TO_PLACEHOLDER_NAME_MAP =
      ImmutableMap.<String, String>builder()
          .put("a", "link")
          .put("br", "break")
          .put("b", "bold")
          .put("i", "italic")
          .put("li", "item")
          .put("ol", "ordered_list")
          .put("ul", "unordered_list")
          .put("p", "paragraph")
          .put("img", "image")
          .put("em", "emphasis")
          .buildOrThrow();

  private final String lcTagName;
  private final boolean isSelfClosing;
  @Nullable private final SamenessKey samenessKey;
  private final MessagePlaceholder placeholder;

  private MsgHtmlTagNode(
      int id,
      SourceLocation sourceLocation,
      MessagePlaceholder placeholder,
      String lcTagName,
      boolean isSelfClosing,
      @Nullable SamenessKey samenessKey,
      HtmlTagNode child) {
    super(id, sourceLocation);
    this.placeholder = placeholder;
    this.lcTagName = lcTagName;
    this.isSelfClosing = isSelfClosing;
    this.samenessKey = samenessKey;
    addChild(child);
  }

  private MsgHtmlTagNode(MsgHtmlTagNode orig, CopyState copyState) {
    super(orig, copyState);
    this.lcTagName = orig.lcTagName;
    this.isSelfClosing = orig.isSelfClosing;
    this.samenessKey = orig.samenessKey != null ? orig.samenessKey.copy(copyState) : null;
    this.placeholder = orig.placeholder;
    copyState.updateRefs(orig, this);
  }

  @Override
  public Kind getKind() {
    return Kind.MSG_HTML_TAG_NODE;
  }

  @Override
  public MessagePlaceholder getPlaceholder() {
    return placeholder;
  }

  private static final CharMatcher INVALID_PLACEHOLDER_CHARS =
      CharMatcher.inRange('a', 'z')
          .or(CharMatcher.inRange('A', 'Z'))
          .or(CharMatcher.inRange('0', '9'))
          .or(CharMatcher.is('_'))
          .negate()
          .precomputed();

  private static String genBasePhName(String lcTagName, boolean isSelfClosing) {
    boolean isEndTag = lcTagName.startsWith("/");
    String baseLcTagName = isEndTag ? lcTagName.substring(1) : lcTagName;
    String basePlaceholderName = LC_TAG_NAME_TO_PLACEHOLDER_NAME_MAP.getOrDefault(baseLcTagName, baseLcTagName);
    
    if (isEndTag) {
      basePlaceholderName = "end_" + basePlaceholderName;
    } else if (!isSelfClosing) {
      basePlaceholderName = "start_" + basePlaceholderName;
    }
    
    return Ascii.toUpperCase(INVALID_PLACEHOLDER_CHARS.replaceFrom(basePlaceholderName, '_'));
  }

  @Override
  public SamenessKey genSamenessKey() {
    return samenessKey == null ? new IdentitySamenessKey(this) : samenessKey;
  }

  @AutoValue
  abstract static class SamenessKeyImpl implements SamenessKey {
    static SamenessKeyImpl create(
        String userSuppliedPlaceholderName, String fullTagText, VeLogNode.SamenessKey key) {
      if (userSuppliedPlaceholderName == null && fullTagText == null && key == null) {
        throw new IllegalArgumentException("at least one parameter should be nonnull");
      }
      return new AutoValue_MsgHtmlTagNode_SamenessKeyImpl(
          userSuppliedPlaceholderName, fullTagText, key);
    }

    @Override
    public SamenessKeyImpl copy(CopyState copyState) {
      return create(
          userSuppliedPlaceholderName(),
          fullTagText(),
          logKey() == null ? null : logKey().copy(copyState));
    }

    @Nullable
    abstract String userSuppliedPlaceholderName();

    @Nullable
    abstract String fullTagText();

    @Nullable
    abstract VeLogNode.SamenessKey logKey();
  }

  @Override
  public String toSourceString() {
    StringBuilder sb = new StringBuilder();
    appendSourceStringForChildren(sb);
    int indexBeforeClose = sb.length() - (isSelfClosing ? 2 : 1);
    
    if (!sb.substring(indexBeforeClose).equals(isSelfClosing ? "/>" : ">")) {
      throw new AssertionError();
    }

    placeholder.example().ifPresent(phex -> sb.insert(indexBeforeClose, " phex=\"" + phex + "\""));
    placeholder.userSuppliedName().ifPresent(phname -> sb.insert(indexBeforeClose, " phname=\"" + phname + "\""));

    return sb.toString();
  }

  @Override
  public BlockNode getParent() {
    return (BlockNode) super.getParent();
  }

  @Override
  public MsgHtmlTagNode copy(CopyState copyState) {
    return new MsgHtmlTagNode(this, copyState);
  }

  public boolean isDefinitelyVoid() {
    return TagName.isDefinitelyVoid(getLcTagName());
  }

  public boolean isSelfClosing() {
    return isSelfClosing;
  }
}
