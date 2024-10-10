
/*
 * Copyright 2016 Google Inc.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.TemplateContentKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.TemplateType;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * An html tag name that could either be a {@code StaticTagName} or a {@code PrintNode}. We only
 * allow {@code PrintNode} for dynamic tag name at this point.
 *
 * <p>For {code @StaticTagName}, the equality semantics are based on the lower-ascii tag name and
 * ignore source location. So 'DIV' and 'div' are considered equivalent.
 *
 * <p>For {@code DynamicTagName}, the equality semantics are based on the {@code ExprRootNode}
 * associated with the {@code PrintNode}.
 */
public final class TagName {

  public enum RcDataTagName {
    SCRIPT,
    STYLE,
    TITLE,
    TEXTAREA,
    XMP;

    @Override
    public String toString() {
      return Ascii.toLowerCase(name());
    }
  }

  private static final ImmutableSet<String> VOID_TAG_NAMES = ImmutableSet.of(
          "area", "base", "basefont", "br", "col", "command", "embed", "hr", "img",
          "input", "isindex", "keygen", "link", "meta", "param", "plaintext", 
          "source", "track", "wbr");

  private static final ImmutableSet<String> INLINE_TAG_NAME = ImmutableSet.of(
          "a", "abbr", "acronym", "b", "bdo", "big", "br", "button", "cite", 
          "code", "dfn", "em", "i", "img", "input", "kbd", "label", "map", 
          "object", "output", "q", "samp", "script", "select", "small", "span", 
          "strong", "sub", "sup", "textarea", "time", "tt", "var");

  private static final ImmutableSetMultimap<String, String> OPTIONAL_TAG_CLOSE_TAG_RULES = new ImmutableSetMultimap.Builder<String, String>()
          .putAll("head", "body", "html")
          .put("body", "html")
          .putAll("li", "ul", "ol")
          .put("dt", "dl")
          .put("dd", "dl")
          .put("rb", "ruby")
          .put("rt", "ruby")
          .put("rtc", "ruby")
          .put("rp", "ruby")
          .put("optgroup", "select")
          .putAll("option", "select", "datalist", "optgroup")
          .put("colgroup", "table")
          .put("thead", "table")
          .put("tbody", "table")
          .put("tfoot", "table")
          .putAll("tr", "thead", "tbody", "tfoot", "table")
          .putAll("td", "tr", "thead", "tbody", "tfoot", "table")
          .putAll("th", "tr", "thead", "tbody", "tfoot", "table")
          .build();

  private static final ImmutableSet<String> PTAG_CLOSE_EXCEPTIONS = ImmutableSet.of(
          "a", "audio", "del", "ins", "map", "noscript", "video");

  private static final ImmutableSet<String> HTML_OPEN_TAG_EXCLUDE_SET = ImmutableSet.of(
          "head", "body", "html");

  private static final ImmutableSet<String> FOCUSABLE_TAG_NAMES = ImmutableSet.of(
          "a", "input", "textarea", "select", "button");

  public static final String WILDCARD = "";

  private static final ImmutableSetMultimap<String, String> OPTIONAL_TAG_OPEN_CLOSE_RULES = new ImmutableSetMultimap.Builder<String, String>()
          .put("li", "li")
          .putAll("dt", "dt", "dd")
          .putAll("dd", "dd", "dt")
          .putAll("rt", "rt", "rp")
          .putAll("rp", "rp", "rt")
          .put("optgroup", "optgroup")
          .putAll("option", "option", "optgroup")
          .putAll("p", "p")
          .putAll("thead", "tbody", "tfoot")
          .putAll("tbody", "tbody", "tfoot")
          .put("tfoot", "table")
          .put("tr", "tr")
          .putAll("td", "tr", "th", "td")
          .putAll("th", "td", "th")
          .build();

  private final StandaloneNode node;
  @Nullable private final String nameAsLowerCase;
  @Nullable private final RcDataTagName rcDataTagName;

  public TagName(RawTextNode node) {
    this.node = checkNotNull(node);
    this.nameAsLowerCase = Ascii.toLowerCase(node.getRawText());
    this.rcDataTagName = determineRcDataTagName(nameAsLowerCase);
  }

  public TagName(PrintNode node) {
    this.node = checkNotNull(node);
    this.nameAsLowerCase = null;
    this.rcDataTagName = null;
  }

  public boolean isStatic() {
    return node instanceof RawTextNode;
  }

  public boolean isLegacyDynamicTagName() {
    if (isStatic()) {
      return false;
    }
    ExprNode root = getDynamicTagName().getExpr().getRoot();
    return root instanceof FunctionNode
            && ((FunctionNode) root).isResolved()
            && ((FunctionNode) root).getSoyFunction() == BuiltinFunction.LEGACY_DYNAMIC_TAG;
  }

  public boolean isTemplateCall() {
    return !isStatic()
            && !isLegacyDynamicTagName()
            && getDynamicTagName().getExpr().getType() != null
            && getDynamicTagName().getExpr().getType().getKind() == SoyType.Kind.TEMPLATE;
  }

  public boolean isWildCard() {
    return node instanceof RawTextNode && WILDCARD.equals(((RawTextNode) node).getRawText());
  }

  public boolean isDefinitelyVoid() {
    return isDefinitelyVoid(nameAsLowerCase);
  }

  public static boolean isDefinitelyVoid(String tagName) {
    return VOID_TAG_NAMES.contains(tagName);
  }

  public boolean isDefinitelyInline() {
    return INLINE_TAG_NAME.contains(nameAsLowerCase);
  }

  public boolean isExcludedOptionalTag() {
    return HTML_OPEN_TAG_EXCLUDE_SET.contains(nameAsLowerCase);
  }

  public boolean isDefinitelyOptional() {
    return OPTIONAL_TAG_CLOSE_TAG_RULES.containsKey(nameAsLowerCase)
            || OPTIONAL_TAG_OPEN_CLOSE_RULES.containsKey(nameAsLowerCase)
            || "html".equals(nameAsLowerCase);
  }

  public boolean isFocusable() {
    return FOCUSABLE_TAG_NAMES.contains(nameAsLowerCase);
  }

  public static boolean checkCloseTagClosesOptional(TagName closeTag, TagName optionalOpenTag) {
    if (!optionalOpenTag.isStatic() || !optionalOpenTag.isDefinitelyOptional()) {
      return false;
    }
    if (!closeTag.isStatic()) {
      return true;
    }
    String openTagName = optionalOpenTag.getStaticTagNameAsLowerCase();
    String closeTagName = closeTag.getStaticTagNameAsLowerCase();
    checkArgument(!openTagName.equals(closeTagName));
    return isPTagClosing(openTagName, closeTagName) || 
           OPTIONAL_TAG_CLOSE_TAG_RULES.containsEntry(openTagName, closeTagName);
  }

  private static boolean isPTagClosing(String openTagName, String closeTagName) {
    return "p".equals(openTagName) && !PTAG_CLOSE_EXCEPTIONS.contains(closeTagName);
  }

  public static boolean checkOpenTagClosesOptional(TagName openTag, TagName optionalOpenTag) {
    checkArgument(optionalOpenTag.isDefinitelyOptional(), "Open tag is not optional.");
    if (!(openTag.isStatic() && optionalOpenTag.isStatic())) {
      return false;
    }
    String optionalTagName = optionalOpenTag.getStaticTagNameAsLowerCase();
    String openTagName = openTag.getStaticTagNameAsLowerCase();
    return OPTIONAL_TAG_OPEN_CLOSE_RULES.containsEntry(optionalTagName, openTagName);
  }

  public boolean isForeignContent() {
    return "svg".equals(nameAsLowerCase);
  }

  @Nullable
  public RcDataTagName getRcDataTagName() {
    return rcDataTagName;
  }

  public String getTagString() {
    if (isStatic()) {
      return getStaticTagName();
    } else if (isTemplateCall()) {
      TemplateType templateType = (TemplateType) getDynamicTagName().getExpr().getType();
      return ((TemplateContentKind.ElementContentKind) templateType.getContentKind()).getTagName();
    }
    return null;
  }

  public String getStaticTagName() {
    checkState(isStatic());
    return ((RawTextNode) node).getRawText();
  }

  public String getStaticTagNameAsLowerCase() {
    checkState(isStatic());
    return nameAsLowerCase;
  }

  public StandaloneNode getNode() {
    return node;
  }

  public PrintNode getDynamicTagName() {
    checkState(!isStatic());
    return (PrintNode) node;
  }

  public SourceLocation getTagLocation() {
    return node.getSourceLocation();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (other instanceof TagName) {
      TagName tag = (TagName) other;
      if (isStatic() != tag.isStatic()) {
        return false;
      }
      return isStatic() ? nameAsLowerCase.equals(tag.nameAsLowerCase) :
             PrintNode.PrintEquivalence.get().equivalent((PrintNode) node, (PrintNode) tag.node);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return isStatic() ? nameAsLowerCase.hashCode() : PrintNode.PrintEquivalence.get().hash((PrintNode) node);
  }

  @Override
  public String toString() {
    return node.toSourceString();
  }

  private RcDataTagName determineRcDataTagName(String tagName) {
    switch (tagName) {
      case "script": return RcDataTagName.SCRIPT;
      case "style": return RcDataTagName.STYLE;
      case "textarea": return RcDataTagName.TEXTAREA;
      case "title": return RcDataTagName.TITLE;
      case "xmp": return RcDataTagName.XMP;
      default: return null;
    }
  }
}
