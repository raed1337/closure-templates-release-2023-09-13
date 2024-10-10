
/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License;
 * you may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.soytree;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.base.internal.TemplateContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.soytree.TemplateNode.SoyFileHeaderInfo;
import com.google.template.soy.soytree.defn.TemplateHeaderVarDefn;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builder for TemplateNode.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public abstract class TemplateNodeBuilder<T extends TemplateNodeBuilder<T>> {
  
  private static final SoyErrorKind SOYDOC_PARAM =
      SoyErrorKind.of(
          "SoyDoc params are not supported anymore. "
              + "Use '{@param}' in the template header instead.");
  private static final SoyErrorKind INVALID_PARAM_NAMED_IJ =
      SoyErrorKind.of("Invalid param name ''ij'' (''ij'' is for injected data).");
  private static final SoyErrorKind PARAM_ALREADY_DECLARED =
      SoyErrorKind.of("''{0}'' already declared.");

  protected final SoyFileHeaderInfo soyFileHeaderInfo;
  protected final ErrorReporter errorReporter;
  protected Integer id;
  protected String cmdText;

  private String templateName;
  private Identifier partialTemplateName;
  protected Visibility visibility;
  protected WhitespaceMode whitespaceMode = WhitespaceMode.JOIN;
  private ImmutableList<String> requiredCssNamespaces = ImmutableList.of();
  private String cssBaseNamespace;
  private boolean component;
  SourceLocation allowExtraAttributesLoc = null;
  private TemplateContentKind contentKind;
  protected String soyDoc;
  protected String soyDocDesc;
  @Nullable protected ImmutableList<TemplateHeaderVarDefn> params;
  protected boolean strictHtmlDisabled;
  private List<CommandTagAttribute> attributes;
  SourceLocation sourceLocation;
  SourceLocation openTagLocation;
  SourceLocation closeTagLocation;

  protected TemplateNodeBuilder(SoyFileHeaderInfo soyFileHeaderInfo, ErrorReporter errorReporter) {
    this.soyFileHeaderInfo = soyFileHeaderInfo;
    this.errorReporter = errorReporter;
  }

  @CanIgnoreReturnValue
  public T setId(int id) {
    checkState(this.id == null);
    this.id = id;
    return self();
  }

  public List<CommandTagAttribute> getAttributes() {
    return attributes;
  }

  @CanIgnoreReturnValue
  public T setSourceLocation(SourceLocation location) {
    checkState(sourceLocation == null);
    this.sourceLocation = checkNotNull(location);
    return self();
  }

  @CanIgnoreReturnValue
  public T setOpenTagLocation(SourceLocation location) {
    checkState(openTagLocation == null);
    this.openTagLocation = checkNotNull(location);
    return self();
  }

  @CanIgnoreReturnValue
  public T setCloseTagLocation(SourceLocation location) {
    checkState(closeTagLocation == null);
    this.closeTagLocation = checkNotNull(location);
    return self();
  }

  @CanIgnoreReturnValue
  public T setAllowExtraAttributes(SourceLocation loc) {
    this.allowExtraAttributesLoc = loc;
    return self();
  }

  public abstract T setCommandValues(Identifier name, List<CommandTagAttribute> attrs);

  protected static final ImmutableSet<String> COMMON_ATTRIBUTE_NAMES =
      ImmutableSet.of("kind", "requirecss", "cssbase", "stricthtml", "whitespace", "component");

  protected void setCommonCommandValues(List<CommandTagAttribute> attrs) {
    this.attributes = attrs;
    TemplateContentKind kind = TemplateContentKind.DEFAULT;
    for (CommandTagAttribute attribute : attrs) {
      Identifier name = attribute.getName();
      switch (name.identifier()) {
        case "kind":
          kind = handleKindAttribute(attribute);
          break;
        case "requirecss":
          setRequiredCssNamespaces(attribute.valueAsRequireCss(errorReporter));
          break;
        case "cssbase":
          setCssBaseNamespace(attribute.valueAsCssBase(errorReporter));
          break;
        case "stricthtml":
          strictHtmlDisabled = attribute.valueAsDisabled(errorReporter);
          break;
        case "whitespace":
          whitespaceMode = attribute.valueAsWhitespaceMode(errorReporter);
          break;
        case "component":
          setComponent(attribute.valueAsEnabled(errorReporter));
          break;
        default:
          break;
      }
    }
    setContentKind(kind);
  }

  private TemplateContentKind handleKindAttribute(CommandTagAttribute attribute) {
    Optional<TemplateContentKind> parsedKind = attribute.valueAsTemplateContentKind(errorReporter);
    if (parsedKind.orElse(null) == TemplateContentKind.DEFAULT) {
      errorReporter.report(
          attribute.getValueLocation(),
          CommandTagAttribute.EXPLICIT_DEFAULT_ATTRIBUTE,
          "kind",
          "html");
    }
    return parsedKind.orElse(TemplateContentKind.DEFAULT);
  }

  @CanIgnoreReturnValue
  public T setSoyDoc(String soyDoc, SourceLocation soyDocLocation) {
    Preconditions.checkState(this.soyDoc == null);
    Preconditions.checkState(cmdText != null);
    int paramOffset = soyDoc.indexOf("@param");
    if (paramOffset != -1) {
      errorReporter.report(
          new RawTextNode(-1, soyDoc, soyDocLocation)
              .substringLocation(paramOffset, paramOffset + "@param".length()),
          SOYDOC_PARAM);
    }
    this.soyDoc = soyDoc;
    Preconditions.checkArgument(soyDoc.startsWith("/**") && soyDoc.endsWith("*/"));
    this.soyDocDesc = cleanSoyDoc(soyDoc);
    return self();
  }

  @CanIgnoreReturnValue
  public T addVarDefns(Iterable<? extends TemplateHeaderVarDefn> varDefns) {
    Preconditions.checkState(this.params == null);
    Set<String> seenVarDefns = new HashSet<>();
    this.params = ImmutableList.copyOf(varDefns);
    validateVarDefns(varDefns, seenVarDefns);
    return self();
  }

  private void validateVarDefns(Iterable<? extends TemplateHeaderVarDefn> varDefns, Set<String> seenVarDefns) {
    for (TemplateHeaderVarDefn param : varDefns) {
      if (param.name().equals("ij")) {
        errorReporter.report(param.nameLocation(), INVALID_PARAM_NAMED_IJ);
      }
      if (!seenVarDefns.add(param.name())) {
        errorReporter.report(param.nameLocation(), PARAM_ALREADY_DECLARED, param.name());
      }
    }
  }

  public abstract TemplateNode build();

  protected void setContentKind(TemplateContentKind contentKind) {
    this.contentKind = contentKind;
  }

  Integer getId() {
    return id;
  }

  String getCmdText() {
    return cmdText;
  }

  String getSoyDoc() {
    return soyDoc;
  }

  String getSoyDocDesc() {
    return soyDocDesc;
  }

  public TemplateContentKind getContentKind() {
    return contentKind;
  }

  public WhitespaceMode getWhitespaceMode() {
    return whitespaceMode;
  }

  protected ImmutableList<String> getRequiredCssNamespaces() {
    return Preconditions.checkNotNull(requiredCssNamespaces);
  }

  protected void setRequiredCssNamespaces(ImmutableList<String> requiredCssNamespaces) {
    this.requiredCssNamespaces = Preconditions.checkNotNull(requiredCssNamespaces);
  }

  protected String getCssBaseNamespace() {
    return cssBaseNamespace;
  }

  protected void setCssBaseNamespace(String cssBaseNamespace) {
    this.cssBaseNamespace = cssBaseNamespace;
  }

  protected boolean getComponent() {
    return component;
  }

  protected void setComponent(boolean component) {
    this.component = component;
  }

  public static String combineNsAndName(String namespace, String templateName) {
    return namespace + (templateName.startsWith(".") ? "" : ".") + templateName;
  }

  protected final void setTemplateNames(Identifier partialTemplateName, String namespace) {
    this.templateName = combineNsAndName(namespace, partialTemplateName.identifier());
    this.partialTemplateName = checkNotNull(partialTemplateName);
  }

  protected boolean getStrictHtmlDisabled() {
    return strictHtmlDisabled;
  }

  protected String getTemplateName() {
    return templateName;
  }

  protected Identifier getPartialTemplateName() {
    return partialTemplateName;
  }

  protected abstract T self();

  private static final Pattern NEWLINE = Pattern.compile("\\n|\\r\\n?");
  private static final Pattern SOY_DOC_START =
      Pattern.compile("^ [/][*][*] [\\ ]* \\r?\\n?", Pattern.COMMENTS);
  private static final Pattern SOY_DOC_END =
      Pattern.compile("\\r?\\n? [\\ ]* [*][/] $", Pattern.COMMENTS);

  private static String cleanSoyDoc(String soyDoc) {
    soyDoc = NEWLINE.matcher(soyDoc).replaceAll("\n");
    soyDoc = soyDoc.replace("@deprecated", "&#64;deprecated");
    soyDoc = SOY_DOC_START.matcher(soyDoc).replaceFirst("");
    soyDoc = SOY_DOC_END.matcher(soyDoc).replaceFirst("");
    List<String> lines = Lists.newArrayList(Splitter.on(NEWLINE).split(soyDoc));
    removeCommonStartChar(lines, ' ', true);
    if (removeCommonStartChar(lines, '*', false) == 1) {
      removeCommonStartChar(lines, ' ', true);
    }
    return CharMatcher.whitespace().trimTrailingFrom(Joiner.on('\n').join(lines));
  }

  private static int removeCommonStartChar(List<String> lines, char charToRemove, boolean shouldRemoveMultiple) {
    int numCharsToRemove = 0;
    boolean isStillCounting = true;
    do {
      boolean areAllLinesEmpty = true;
      for (String line : lines) {
        if (line.length() == 0) {
          continue; // empty lines are okay
        }
        areAllLinesEmpty = false;
        if (line.length() <= numCharsToRemove || line.charAt(numCharsToRemove) != charToRemove) {
          isStillCounting = false;
          break;
        }
      }
      if (areAllLinesEmpty) {
        isStillCounting = false;
      }
      if (isStillCounting) {
        numCharsToRemove += 1;
      }
    } while (isStillCounting && shouldRemoveMultiple);

    if (numCharsToRemove > 0) {
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.length() == 0) {
          continue; // don't change empty lines
        }
        lines.set(i, line.substring(numCharsToRemove));
      }
    }
    return numCharsToRemove;
  }
}
