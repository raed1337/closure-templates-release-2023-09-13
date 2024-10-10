
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.base.SoyBackendKind;
import com.google.template.soy.data.Dir;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Bidi global direction, which is either a "static" integer value (ltr=1, rtl=-1), or a code
 * snippet yielding such a value when evaluated at template runtime.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
@Immutable
public final class BidiGlobalDir {
  public static final BidiGlobalDir LTR = new BidiGlobalDir(1);
  public static final BidiGlobalDir RTL = new BidiGlobalDir(-1);

  private final String codeSnippet;
  @Nullable private final String namespace;
  private final int staticValue;

  private BidiGlobalDir(int staticValue) {
    this.staticValue = staticValue;
    this.codeSnippet = Integer.toString(staticValue);
    this.namespace = null;
  }

  private BidiGlobalDir(String codeSnippet, @Nullable String namespaceToImport) {
    this.codeSnippet = checkNotNull(codeSnippet);
    this.namespace = namespaceToImport;
    this.staticValue = 0;
  }

  public static BidiGlobalDir forStaticIsRtl(boolean isRtl) {
    return isRtl ? RTL : LTR;
  }

  public static BidiGlobalDir forStaticLocale(@Nullable String localeString) {
    return SoyBidiUtils.getBidiGlobalDir(localeString);
  }

  public static BidiGlobalDir forIsRtlCodeSnippet(
      String isRtlCodeSnippet, @Nullable String namespace, SoyBackendKind backend) {
    validateIsRtlCodeSnippet(isRtlCodeSnippet, backend);
    return createBidiGlobalDir(isRtlCodeSnippet, namespace, backend);
  }

  private static void validateIsRtlCodeSnippet(String isRtlCodeSnippet, SoyBackendKind backend) {
    Preconditions.checkArgument(
        isRtlCodeSnippet != null && !isRtlCodeSnippet.isEmpty(),
        "Bidi global direction source code snippet must be non-empty.");
    Preconditions.checkArgument(
        backend == SoyBackendKind.JS_SRC || backend == SoyBackendKind.PYTHON_SRC,
        "Bidi code snippets are only used in JS and Python.");
  }

  private static BidiGlobalDir createBidiGlobalDir(String isRtlCodeSnippet, 
      @Nullable String namespace, SoyBackendKind backend) {
    String code = (backend == SoyBackendKind.JS_SRC) 
        ? isRtlCodeSnippet + "?-1:1" 
        : "-1 if " + isRtlCodeSnippet + " else 1";
    return new BidiGlobalDir(code, namespace);
  }

  public boolean isStaticValue() {
    return staticValue != 0;
  }

  public int getStaticValue() {
    if (staticValue == 0) {
      throw new IllegalStateException("Cannot get static value for nonstatic BidiGlobalDir object.");
    }
    return staticValue;
  }

  public String getCodeSnippet() {
    return codeSnippet;
  }

  public Optional<String> getNamespace() {
    return Optional.ofNullable(namespace);
  }

  public Dir toDir() {
    return convertToDir(staticValue);
  }

  private Dir convertToDir(int value) {
    switch (value) {
      case -1:
        return Dir.RTL;
      case 1:
        return Dir.LTR;
      default:
        throw new IllegalArgumentException("invalid BidiGlobalDir for conversion to Dir: " + value);
    }
  }
}
