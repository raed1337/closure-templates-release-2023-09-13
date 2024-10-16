
/*
 * Copyright 2018 Google Inc.
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
package com.google.template.soy.types;

import com.google.common.base.Preconditions;
import com.google.template.soy.soytree.SoyTypeP;
import java.util.Objects;
import java.util.Optional;

/** Soy's ve type, for tracking VE names and their associated data type. */
public final class VeType extends SoyType {

  public static final VeType NO_DATA = new VeType(Optional.empty());

  private final Optional<String> dataType;

  private VeType(Optional<String> dataType) {
    this.dataType = dataType;
  }

  public static VeType of(String dataType) {
    Preconditions.checkNotNull(dataType);
    return createVeType(dataType);
  }

  private static VeType createVeType(String dataType) {
    if (NullType.getInstance().toString().equals(dataType)) {
      return NO_DATA;
    }
    return new VeType(Optional.of(dataType));
  }

  public Optional<String> getDataType() {
    return dataType;
  }

  @Override
  public Kind getKind() {
    return Kind.VE;
  }

  @Override
  boolean doIsAssignableFromNonUnionType(SoyType srcType) {
    if (srcType.getKind() != Kind.VE) {
      return false;
    }
    return isAssignableFromVeType((VeType) srcType);
  }

  private boolean isAssignableFromVeType(VeType otherVe) {
    if (dataType.isPresent() && dataType.get().equals(AnyType.getInstance().toString())) {
      return true;
    }
    return dataType.equals(otherVe.dataType);
  }

  @Override
  public String toString() {
    return "ve<"
        + (dataType.isPresent() ? dataType.get() : NullType.getInstance().toString())
        + ">";
  }

  @Override
  void doToProto(SoyTypeP.Builder builder) {
    builder.setVe(dataType.orElse("null"));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VeType && ((VeType) other).dataType.equals(this.dataType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.getClass(), dataType);
  }

  @Override
  public <T> T accept(SoyTypeVisitor<T> visitor) {
    return visitor.visit(this);
  }
}
