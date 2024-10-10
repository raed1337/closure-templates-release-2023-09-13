
/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy.internal.i18n;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.html.HtmlEscapers;
import com.google.template.soy.data.Dir;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SanitizedContents;
import javax.annotation.Nullable;

/**
 * Utility class for formatting text for display in a potentially opposite-directionality context
 * without garbling. The directionality of the context is set at formatter creation and the
 * directionality of the text can be either estimated or passed in when known. Provides the
 * following functionality:
 *
 * <p>1. Bidi Wrapping ...
 *
 * <p>2. Directionality estimation ...
 *
 * <p>3. Escaping ...
 *
 * <p>Thus, in a single call, the formatter can escape the input string as specified, determine its
 * directionality, and wrap it as necessary. It is then up to the caller to insert the return value
 * in the output.
 *
 */
public class BidiFormatter {

  /** The text used to bidi wrap a string. */
  @AutoValue
  public abstract static class BidiWrappingText {
    static BidiWrappingText create(String beforeText, String afterText) {
      return new AutoValue_BidiFormatter_BidiWrappingText(beforeText, afterText);
    }

    public abstract String beforeText();
    public abstract String afterText();
  }

  private static final BidiFormatter DEFAULT_LTR_INSTANCE = new BidiFormatter(Dir.LTR);
  private static final BidiFormatter DEFAULT_RTL_INSTANCE = new BidiFormatter(Dir.RTL);
  private static final SanitizedContent LTR_DIR = SanitizedContents.constantAttributes("dir=\"ltr\"");
  private static final SanitizedContent RTL_DIR = SanitizedContents.constantAttributes("dir=\"rtl\"");
  private static final SanitizedContent NEUTRAL_DIR = SanitizedContents.emptyString(ContentKind.ATTRIBUTES);

  private final Dir contextDir;

  public static BidiFormatter getInstance(Dir contextDir) {
    switch (contextDir) {
      case LTR:
        return DEFAULT_LTR_INSTANCE;
      case RTL:
        return DEFAULT_RTL_INSTANCE;
      case NEUTRAL:
        throw new IllegalArgumentException("invalid context directionality: " + contextDir);
    }
    throw new AssertionError(contextDir);
  }

  private BidiFormatter(@Nullable Dir contextDir) {
    this.contextDir = contextDir;
  }

  public SanitizedContent knownDirAttrSanitized(Dir dir) {
    Preconditions.checkNotNull(dir);
    if (dir != contextDir) {
      switch (dir) {
        case LTR:
          return LTR_DIR;
        case RTL:
          return RTL_DIR;
        case NEUTRAL:
          break; // fall out.
      }
    }
    return NEUTRAL_DIR;
  }

  public String spanWrap(@Nullable Dir dir, String str, boolean isHtml) {
    BidiWrappingText wrappingText = spanWrappingText(dir, str, isHtml);
    if (!isHtml) {
      str = HtmlEscapers.htmlEscaper().escape(str);
    }
    return wrappingText.beforeText() + str + wrappingText.afterText();
  }

  public BidiWrappingText spanWrappingText(@Nullable Dir dir, String str, boolean isHtml) {
    if (dir == null) {
      dir = estimateDirection(str, isHtml);
    }

    StringBuilder beforeText = new StringBuilder();
    StringBuilder afterText = new StringBuilder();
    if (shouldWrap(dir)) {
      beforeText.append("<span dir=\"").append(dir == Dir.RTL ? "rtl" : "ltr").append("\">");
      afterText.append("</span>");
    }
    afterText.append(markAfter(dir, str, isHtml));
    return BidiWrappingText.create(beforeText.toString(), afterText.toString());
  }

  private boolean shouldWrap(Dir dir) {
    return dir != Dir.NEUTRAL && dir != contextDir;
  }

  public String unicodeWrap(@Nullable Dir dir, String str, boolean isHtml) {
    BidiWrappingText wrappingText = unicodeWrappingText(dir, str, isHtml);
    return wrappingText.beforeText() + str + wrappingText.afterText();
  }

  public BidiWrappingText unicodeWrappingText(@Nullable Dir dir, String str, boolean isHtml) {
    if (dir == null) {
      dir = estimateDirection(str, isHtml);
    }
    StringBuilder beforeText = new StringBuilder();
    StringBuilder afterText = new StringBuilder();
    if (shouldWrap(dir)) {
      beforeText.append(dir == Dir.RTL ? BidiUtils.Format.RLE : BidiUtils.Format.LRE);
      afterText.append(BidiUtils.Format.PDF);
    }
    afterText.append(markAfter(dir, str, isHtml));
    return BidiWrappingText.create(beforeText.toString(), afterText.toString());
  }

  public String markAfter(@Nullable Dir dir, String str, boolean isHtml) {
    if (dir == null) {
      dir = estimateDirection(str, isHtml);
    }
    if (contextDir == Dir.LTR && (dir == Dir.RTL || BidiUtils.getExitDir(str, isHtml) == Dir.RTL)) {
      return BidiUtils.Format.LRM_STRING;
    }
    if (contextDir == Dir.RTL && (dir == Dir.LTR || BidiUtils.getExitDir(str, isHtml) == Dir.LTR)) {
      return BidiUtils.Format.RLM_STRING;
    }
    return "";
  }

  @VisibleForTesting
  static Dir estimateDirection(String str, boolean isHtml) {
    return BidiUtils.estimateDirection(str, isHtml);
  }
}
