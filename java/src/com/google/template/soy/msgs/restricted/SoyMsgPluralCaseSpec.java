
/*
 * Copyright 2011 Google Inc.
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

package com.google.template.soy.msgs.restricted;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.toImmutableEnumMap;
import static java.util.Arrays.stream;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.msgs.SoyMsgException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Function;

/**
 * Represents a plural case value.
 *
 * <p>A plural case value can be either a number, or one of {@code ZERO}, {@code ONE}, {@code TWO},
 * {@code FEW}, {@code MANY} or {@code OTHER}. Here, a number is represented by the number {@code
 * explicitValue} with status set to EXPLICIT and the remaining by an enum value.
 */
@Immutable
public final class SoyMsgPluralCaseSpec {

  /** The type. EXPLICIT indicating numeric, or one of the others indicating non-numeric. */
  public enum Type {
    EXPLICIT,
    ZERO,
    ONE,
    TWO,
    FEW,
    MANY,
    OTHER
  }

  /** Internal mapping of Type to String, reduces memory usage */
  private static final EnumMap<Type, String> TYPE_TO_STRING = new EnumMap<>(Type.class);

  static {
    for (Type t : EnumSet.allOf(Type.class)) {
      TYPE_TO_STRING.put(t, Ascii.toLowerCase(t.name()));
    }
  }

  private static final ImmutableMap<Type, SoyMsgPluralCaseSpec> TYPE_TO_SPEC =
      stream(Type.values())
          .map(SoyMsgPluralCaseSpec::new)
          .collect(toImmutableEnumMap(SoyMsgPluralCaseSpec::getType, Function.identity()));

  /** ZERO, ONE, TWO, FEW, MANY or OTHER if the type is non-numeric, or EXPLICIT if numeric. */
  private final Type type;

  /** The numeric value if the type is numeric, -1 otherwise. */
  private final long explicitValue;

  /**
   * Returns a SoyMsgPluralCaseSpec for a non-numeric value.
   *
   * @param typeStr String representation of the non-numeric value.
   * @throws IllegalArgumentException if typeStr (after converting to upper case) does not match
   *     with any of the enum types.
   */
  public static SoyMsgPluralCaseSpec forType(String typeStr) {
    return forType(Type.valueOf(Ascii.toUpperCase(typeStr)));
  }

  /** Constructs an object from a non-numeric value. */
  public static SoyMsgPluralCaseSpec forType(Type type) {
    return TYPE_TO_SPEC.get(checkNotNull(type, "type cannot be null"));
  }

  /** Constructs an object from a non-numeric value. */
  private SoyMsgPluralCaseSpec(Type type) {
    this.type = checkNotNull(type);
    this.explicitValue = -1;
  }

  /**
   * Constructs an object from a numeric value. The field type is set to EXPLICIT, and explicitValue
   * is set to the numeric value given.
   *
   * @param explicitValue The numeric value.
   * @throws SoyMsgException if invalid numeric value.
   */
  public SoyMsgPluralCaseSpec(long explicitValue) {
    if (explicitValue < 0) {
      throw new SoyMsgException("Negative plural case value.");
    }
    type = Type.EXPLICIT;
    this.explicitValue = explicitValue;
  }

  /**
   * Get the type.
   *
   * @return The type. EXPLICIT if numeric.
   */
  public Type getType() {
    return type;
  }

  /**
   * Get the numeric value.
   *
   * @return if numeric, return the numeric value, else -1.
   */
  public long getExplicitValue() {
    return explicitValue;
  }

  @Override
  public String toString() {
    return (type == Type.EXPLICIT) ? "=" + explicitValue : TYPE_TO_STRING.get(type);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof SoyMsgPluralCaseSpec)) {
      return false;
    }
    SoyMsgPluralCaseSpec otherSpec = (SoyMsgPluralCaseSpec) other;
    return type == otherSpec.type && explicitValue == otherSpec.explicitValue;
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, explicitValue);
  }
}
