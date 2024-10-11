
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

package com.google.template.soy.jssrc;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;

/**
 * Compilation options for the JS Src output target (backend).
 */
public final class SoyJsSrcOptions implements Cloneable {

  private enum JsDepsStrategy {
    /** Whether we should generate code to provide/require Soy namespaces. */
    NAMESPACES,
    /** Whether we should generate code to declare/require goog.modules. */
    MODULE
  }

  private JsDepsStrategy depsStrategy;
  private boolean shouldGenerateGoogMsgDefs;
  private boolean dependOnCssHeader;
  private boolean googMsgsAreExternal;
  private int bidiGlobalDir;
  private boolean useGoogIsRtlForBidiGlobalDir;

  public SoyJsSrcOptions() {
    this.depsStrategy = JsDepsStrategy.NAMESPACES;
    this.dependOnCssHeader = false;
    this.shouldGenerateGoogMsgDefs = false;
    this.googMsgsAreExternal = false;
    this.bidiGlobalDir = 0;
    this.useGoogIsRtlForBidiGlobalDir = false;
  }

  private SoyJsSrcOptions(SoyJsSrcOptions orig) {
    this.depsStrategy = orig.depsStrategy;
    this.dependOnCssHeader = orig.dependOnCssHeader;
    this.shouldGenerateGoogMsgDefs = orig.shouldGenerateGoogMsgDefs;
    this.googMsgsAreExternal = orig.googMsgsAreExternal;
    this.bidiGlobalDir = orig.bidiGlobalDir;
    this.useGoogIsRtlForBidiGlobalDir = orig.useGoogIsRtlForBidiGlobalDir;
  }

  public void setShouldProvideRequireSoyNamespaces(boolean shouldProvideRequireSoyNamespaces) {
    if (shouldProvideRequireSoyNamespaces) {
      this.depsStrategy = JsDepsStrategy.NAMESPACES;
    }
  }

  public boolean shouldProvideRequireSoyNamespaces() {
    return this.depsStrategy == JsDepsStrategy.NAMESPACES;
  }

  public void setShouldGenerateGoogModules(boolean shouldGenerateGoogModules) {
    if (shouldGenerateGoogModules) {
      this.depsStrategy = JsDepsStrategy.MODULE;
    }
  }

  public boolean shouldGenerateGoogModules() {
    return this.depsStrategy == JsDepsStrategy.MODULE;
  }

  public void setShouldGenerateGoogMsgDefs(boolean shouldGenerateGoogMsgDefs) {
    this.shouldGenerateGoogMsgDefs = shouldGenerateGoogMsgDefs;
  }

  public boolean shouldGenerateGoogMsgDefs() {
    return this.shouldGenerateGoogMsgDefs;
  }

  public void setDependOnCssHeader(boolean dependOnCssHeader) {
    this.dependOnCssHeader = dependOnCssHeader;
  }

  public boolean dependOnCssHeader() {
    return this.dependOnCssHeader;
  }

  public void setGoogMsgsAreExternal(boolean googMsgsAreExternal) {
    this.googMsgsAreExternal = googMsgsAreExternal;
  }

  public boolean googMsgsAreExternal() {
    return this.googMsgsAreExternal;
  }

  public void setBidiGlobalDir(int bidiGlobalDir) {
    Preconditions.checkArgument(
        bidiGlobalDir >= -1 && bidiGlobalDir <= 1,
        "bidiGlobalDir must be 1 for LTR, or -1 for RTL (or 0 to leave unspecified).");
    Preconditions.checkState(
        !this.useGoogIsRtlForBidiGlobalDir || bidiGlobalDir == 0,
        "Must not specify both bidiGlobalDir and useGoogIsRtlForBidiGlobalDir.");
    this.bidiGlobalDir = bidiGlobalDir;
  }

  public int getBidiGlobalDir() {
    return this.bidiGlobalDir;
  }

  public void setUseGoogIsRtlForBidiGlobalDir(boolean useGoogIsRtlForBidiGlobalDir) {
    Preconditions.checkState(
        !useGoogIsRtlForBidiGlobalDir || this.shouldGenerateGoogMsgDefs,
        "Do not specify useGoogIsRtlForBidiGlobalDir without shouldGenerateGoogMsgDefs.");
    Preconditions.checkState(
        !useGoogIsRtlForBidiGlobalDir || this.bidiGlobalDir == 0,
        "Must not specify both bidiGlobalDir and useGoogIsRtlForBidiGlobalDir.");
    this.useGoogIsRtlForBidiGlobalDir = useGoogIsRtlForBidiGlobalDir;
  }

  public boolean getUseGoogIsRtlForBidiGlobalDir() {
    return this.useGoogIsRtlForBidiGlobalDir;
  }

  @Override
  public SoyJsSrcOptions clone() {
    return new SoyJsSrcOptions(this);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("shouldProvideRequireSoyNamespaces", shouldProvideRequireSoyNamespaces())
        .add("dependOnCssHeader", dependOnCssHeader)
        .add("shouldGenerateGoogMsgDefs", shouldGenerateGoogMsgDefs)
        .add("shouldGenerateGoogModules", shouldGenerateGoogModules())
        .add("googMsgsAreExternal", googMsgsAreExternal)
        .add("bidiGlobalDir", bidiGlobalDir)
        .add("useGoogIsRtlForBidiGlobalDir", useGoogIsRtlForBidiGlobalDir)
        .toString();
  }
}
