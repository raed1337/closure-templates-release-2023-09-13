
/*
 * Copyright 2013 Google Inc.
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

package com.google.template.soy.soytree.defn;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceFilePath;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.basetree.Copyable;
import com.google.template.soy.exprtree.AbstractVarDefn;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.types.SoyType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A reference to an imported symbol. For proto deps, which have nested symbols under the imported
 * module or type, we may create nested instances of this class via {@link #nested}. Likewise for
 * templates and constants under a template module import.
 */
public final class ImportedVar extends AbstractVarDefn implements Copyable<ImportedVar> {

  public static final String MODULE_IMPORT = "*";

  private final String symbol;
  private final Map<String, ImportedVar> nestedVarDefns;
  private final ImportedVar parent;
  private SourceFilePath filePath;

  public ImportedVar(String name, @Nullable String alias, SourceLocation nameLocation) {
    super(alias != null ? alias : name, nameLocation, null);
    Preconditions.checkArgument(alias == null || !alias.isEmpty());
    this.nestedVarDefns = new LinkedHashMap<>();
    this.symbol = name;
    this.parent = null;
  }

  private ImportedVar(ImportedVar parent, String symbol) {
    super(parent.name() + "." + symbol, parent.nameLocation(), null);
    this.nestedVarDefns = new LinkedHashMap<>();
    this.symbol = symbol;
    this.parent = parent;
  }

  private ImportedVar(ImportedVar var, ImportedVar parent, CopyState copyState) {
    super(var);
    this.nestedVarDefns = new LinkedHashMap<>();
    for (Map.Entry<String, ImportedVar> entry : var.nestedVarDefns.entrySet()) {
      ImportedVar newNested = new ImportedVar(entry.getValue(), this, copyState);
      this.nestedVarDefns.put(entry.getKey(), newNested);
      copyState.updateRefs(entry.getValue(), newNested);
    }
    this.symbol = var.symbol;
    this.parent = parent;
    this.filePath = var.filePath;
  }

  public void onParentInit(SourceFilePath path) {
    Preconditions.checkState(parent == null);
    this.filePath = path;
  }

  @Override
  public ImportedVar copy(CopyState copyState) {
    return new ImportedVar(this, parent, copyState);
  }

  public ImportedVar getRoot() {
    ImportedVar current = this;
    while (current.parent != null) {
      current = current.parent;
    }
    return current;
  }

  public Set<String> getNestedTypes() {
    return Collections.unmodifiableSet(nestedVarDefns.keySet());
  }

  public ImportedVar nested(String symbolName) {
    return nestedVarDefns.computeIfAbsent(symbolName, n -> new ImportedVar(this, n));
  }

  public String getSymbol() {
    return symbol;
  }

  @Override
  public Kind kind() {
    return Kind.IMPORT_VAR;
  }

  public boolean isAliased() {
    return !name().equals(symbol);
  }

  @Override
  public boolean isInjected() {
    return false;
  }

  public boolean isModuleImport() {
    return MODULE_IMPORT.equals(symbol);
  }

  public void setType(SoyType type) {
    this.type = type;
  }

  public SourceFilePath getSourceFilePath() {
    return parent != null ? parent.getSourceFilePath() : filePath;
  }

  public ImmutableList<ImportedVar> getChain() {
    ImmutableList.Builder<ImportedVar> builder = ImmutableList.builder();
    ImportedVar current = this;
    while (current != null) {
      builder.add(current);
      current = current.parent;
    }
    return builder.build().reverse();
  }

  public SourceLocation symbolLocation() {
    SourceLocation full = nameLocation();
    return isAliased() && full.isSingleLine() ? full.substring(0, symbol.length()) : nameLocation();
  }

  private static boolean isProtoImport(ImportedVar var) {
    return var.type().getKind() == SoyType.Kind.PROTO_TYPE
        || var.type().getKind() == SoyType.Kind.PROTO_ENUM_TYPE;
  }

  @Override
  public boolean isEquivalent(VarDefn other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ImportedVar)) {
      return false;
    }
    return hasType()
        && other.hasType()
        && isProtoImport(this)
        && isProtoImport((ImportedVar) other)
        && type().equals(other.type());
  }
}
