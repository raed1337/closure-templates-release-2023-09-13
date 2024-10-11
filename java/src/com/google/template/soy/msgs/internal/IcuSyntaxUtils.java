
/*
 * Copyright 2010 Google Inc.
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

package com.google.template.soy.msgs.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.msgs.restricted.MsgPartUtils;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPart.Case;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralCaseSpec;
import com.google.template.soy.msgs.restricted.SoyMsgPluralPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralRemainderPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.msgs.restricted.SoyMsgSelectPart;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for building msg parts with ICU syntax.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public class IcuSyntaxUtils {

  private IcuSyntaxUtils() {}

  public static ImmutableList<SoyMsgPart> convertMsgPartsToEmbeddedIcuSyntax(
      ImmutableList<SoyMsgPart> origMsgParts) {

    if (!MsgPartUtils.hasPlrselPart(origMsgParts)) {
      return origMsgParts;
    }

    ImmutableList.Builder<SoyMsgPart> newMsgPartsBuilder = ImmutableList.builder();
    StringBuilder currRawTextSb = new StringBuilder();

    processMessageParts(newMsgPartsBuilder, currRawTextSb, origMsgParts, false);
    addRawTextIfNeeded(newMsgPartsBuilder, currRawTextSb);

    return newMsgPartsBuilder.build();
  }

  private static void processMessageParts(
      ImmutableList.Builder<SoyMsgPart> newMsgPartsBuilder,
      StringBuilder currRawTextSb,
      List<SoyMsgPart> origMsgParts,
      boolean isInPlrselPart) {

    for (SoyMsgPart origMsgPart : origMsgParts) {
      if (origMsgPart instanceof SoyMsgRawTextPart) {
        handleRawTextPart(newMsgPartsBuilder, currRawTextSb, (SoyMsgRawTextPart) origMsgPart, isInPlrselPart);
      } else if (origMsgPart instanceof SoyMsgPlaceholderPart) {
        handlePlaceholderPart(newMsgPartsBuilder, currRawTextSb, origMsgPart);
      } else if (origMsgPart instanceof SoyMsgPluralRemainderPart) {
        currRawTextSb.append(getPluralRemainderString());
      } else if (origMsgPart instanceof SoyMsgPluralPart) {
        convertPluralPart(newMsgPartsBuilder, currRawTextSb, (SoyMsgPluralPart) origMsgPart);
      } else if (origMsgPart instanceof SoyMsgSelectPart) {
        convertSelectPart(newMsgPartsBuilder, currRawTextSb, (SoyMsgSelectPart) origMsgPart);
      }
    }
  }

  private static void handleRawTextPart(
      ImmutableList.Builder<SoyMsgPart> newMsgPartsBuilder,
      StringBuilder currRawTextSb,
      SoyMsgRawTextPart origMsgPart,
      boolean isInPlrselPart) {

    String rawText = origMsgPart.getRawText();
    if (isInPlrselPart) {
      rawText = icuEscape(rawText);
    }
    currRawTextSb.append(rawText);
  }

  private static void handlePlaceholderPart(
      ImmutableList.Builder<SoyMsgPart> newMsgPartsBuilder,
      StringBuilder currRawTextSb,
      SoyMsgPart origMsgPart) {

    if (currRawTextSb.length() > 0) {
      newMsgPartsBuilder.add(SoyMsgRawTextPart.of(currRawTextSb.toString()));
      currRawTextSb.setLength(0);
    }
    newMsgPartsBuilder.add(origMsgPart);
  }

  private static void addRawTextIfNeeded(
      ImmutableList.Builder<SoyMsgPart> newMsgPartsBuilder,
      StringBuilder currRawTextSb) {

    if (currRawTextSb.length() > 0) {
      newMsgPartsBuilder.add(SoyMsgRawTextPart.of(currRawTextSb.toString()));
    }
  }

  private static void convertPluralPart(
      ImmutableList.Builder<SoyMsgPart> newMsgPartsBuilder,
      StringBuilder currRawTextSb,
      SoyMsgPluralPart origPluralPart) {

    currRawTextSb.append(getPluralOpenString(origPluralPart.getPluralVarName(), origPluralPart.getOffset()));

    for (Case<SoyMsgPluralCaseSpec> pluralCase : origPluralPart.getCases()) {
      currRawTextSb.append(getPluralCaseOpenString(pluralCase.spec()));
      processMessageParts(newMsgPartsBuilder, currRawTextSb, pluralCase.parts(), true);
      currRawTextSb.append(getPluralCaseCloseString());
    }

    currRawTextSb.append(getPluralCloseString());
  }

  private static void convertSelectPart(
      ImmutableList.Builder<SoyMsgPart> newMsgPartsBuilder,
      StringBuilder currRawTextSb,
      SoyMsgSelectPart origSelectPart) {

    currRawTextSb.append(getSelectOpenString(origSelectPart.getSelectVarName()));

    for (Case<String> selectCase : origSelectPart.getCases()) {
      currRawTextSb.append(getSelectCaseOpenString(selectCase.spec()));
      processMessageParts(newMsgPartsBuilder, currRawTextSb, selectCase.parts(), true);
      currRawTextSb.append(getSelectCaseCloseString());
    }

    currRawTextSb.append(getSelectCloseString());
  }

  private static final Pattern ICU_SYNTAX_CHAR_NEEDING_ESCAPE_PATTERN =
      Pattern.compile(" ' (?= ['{}\\#] ) | ' $ | [{}] ", Pattern.COMMENTS);

  private static final ImmutableMap<String, String> ICU_SYNTAX_CHAR_ESCAPE_MAP =
      ImmutableMap.of("'", "''", "{", "'{'", "}", "'}'");

  @VisibleForTesting
  static String icuEscape(String rawText) {

    Matcher matcher = ICU_SYNTAX_CHAR_NEEDING_ESCAPE_PATTERN.matcher(rawText);
    if (!matcher.find()) {
      return rawText;
    }

    StringBuffer escapedTextSb = new StringBuffer();
    do {
      String repl = ICU_SYNTAX_CHAR_ESCAPE_MAP.get(matcher.group());
      matcher.appendReplacement(escapedTextSb, repl);
    } while (matcher.find());
    matcher.appendTail(escapedTextSb);
    return escapedTextSb.toString();
  }

  private static String getPluralOpenString(String varName, int offset) {
    StringBuilder openingPartSb = new StringBuilder();
    openingPartSb.append('{').append(varName).append(",plural,");
    if (offset != 0) {
      openingPartSb.append("offset:").append(offset).append(' ');
    }
    return openingPartSb.toString();
  }

  private static String getPluralCloseString() {
    return "}";
  }

  private static String getPluralCaseOpenString(SoyMsgPluralCaseSpec pluralCaseSpec) {
    String icuCaseName =
        (pluralCaseSpec.getType() == SoyMsgPluralCaseSpec.Type.EXPLICIT)
            ? "=" + pluralCaseSpec.getExplicitValue()
            : pluralCaseSpec.getType().name().toLowerCase();
    return icuCaseName + "{";
  }

  private static String getPluralCaseCloseString() {
    return "}";
  }

  private static String getPluralRemainderString() {
    return "#";
  }

  private static String getSelectOpenString(String varName) {
    return "{" + varName + ",select,";
  }

  private static String getSelectCloseString() {
    return "}";
  }

  private static String getSelectCaseOpenString(String caseValue) {
    return ((caseValue != null) ? caseValue : "other") + "{";
  }

  private static String getSelectCaseCloseString() {
    return "}";
  }
}
