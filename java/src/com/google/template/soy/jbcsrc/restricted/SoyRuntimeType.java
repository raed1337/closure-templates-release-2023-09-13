
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

package com.google.template.soy.jbcsrc.restricted;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.template.soy.jbcsrc.restricted.BytecodeUtils.SOY_VALUE_TYPE;

import com.google.common.base.Objects;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.template.soy.internal.proto.JavaQualifiedNames;
import com.google.template.soy.types.BoolType;
import com.google.template.soy.types.FloatType;
import com.google.template.soy.types.IntType;
import com.google.template.soy.types.ListType;
import com.google.template.soy.types.SoyProtoType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;
import com.google.template.soy.types.SoyTypes;
import com.google.template.soy.types.StringType;
import com.google.template.soy.types.UnionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.objectweb.asm.Type;

/**
 * The 'runtime type' of a {@link SoyType}.
 *
 * <p>In {@code jbcsrc} all types have a corresponding runtime type, and often distinct 'boxed' and
 * 'unboxed' forms.
 */
public abstract class SoyRuntimeType {

  /**
   * Returns the unboxed {@code jbcsrc} representation of the given type, or absent if no such
   * representation exists.
   *
   * <p>Types will fail to have unboxed representations mostly for unknown, any and union types.
   */
  public static Optional<SoyRuntimeType> getUnboxedType(SoyType soyType) {
    return Optional.ofNullable(unboxedTypeImpl(soyType));
  }

  /** Returns the boxed representation of the given type. */
  public static SoyRuntimeType getBoxedType(SoyType soyType) {
    return new BoxedSoyType(soyType, SOY_VALUE_TYPE);
  }

  @Nullable
  private static PrimitiveSoyType unboxedTypeImpl(SoyType soyType) {
    switch (soyType.getKind()) {
      case BOOL: return createPrimitiveSoyType(BoolType.getInstance(), Type.BOOLEAN_TYPE);
      case STRING: return createPrimitiveSoyType(StringType.getInstance(), BytecodeUtils.STRING_TYPE);
      case INT: return createPrimitiveSoyType(IntType.getInstance(), Type.LONG_TYPE);
      case FLOAT: return createPrimitiveSoyType(FloatType.getInstance(), Type.DOUBLE_TYPE);
      case PROTO_ENUM: return createPrimitiveSoyType(soyType, Type.LONG_TYPE);
      case MESSAGE: return createPrimitiveSoyType(soyType, BytecodeUtils.MESSAGE_TYPE);
      case PROTO: return createPrimitiveSoyType(soyType, protoType(((SoyProtoType) soyType).getDescriptor()));
      case LIST: return createPrimitiveSoyType(soyType, BytecodeUtils.LIST_TYPE);
      case UNION: return handleUnionType(soyType);
      case NULL:
      case UNDEFINED:
      case ATTRIBUTES:
      case CSS:
      case URI:
      case ELEMENT:
      case HTML:
      case JS:
      case TRUSTED_RESOURCE_URI:
      case LEGACY_OBJECT_MAP:
      case MAP:
      case RECORD:
      case TEMPLATE:
      case VE:
      case VE_DATA:
      case UNKNOWN:
      case ANY:
        return null;
      case CSS_TYPE:
      case CSS_MODULE:
      case PROTO_TYPE:
      case PROTO_ENUM_TYPE:
      case PROTO_EXTENSION:
      case PROTO_MODULE:
      case TEMPLATE_TYPE:
      case TEMPLATE_MODULE:
      case FUNCTION:
        throw new AssertionError("can't map " + soyType + " to an unboxed soy runtime type");
    }
    return null; // Fallback for safety
  }

  private static PrimitiveSoyType createPrimitiveSoyType(SoyType soyType, Type runtimeType) {
    return new PrimitiveSoyType(soyType, runtimeType);
  }

  @Nullable
  private static PrimitiveSoyType handleUnionType(SoyType soyType) {
    SoyType nonNullType = SoyTypes.removeNull(soyType);
    if (!nonNullType.equals(soyType)) {
      return null;
    }
    PrimitiveSoyType memberType = null;
    for (SoyType member : ((UnionType) soyType).getMembers()) {
      PrimitiveSoyType primitive = (PrimitiveSoyType) getUnboxedType(member).orElse(null);
      if (primitive == null) {
        return null;
      }
      memberType = updateMemberType(memberType, primitive);
    }
    return memberType != null ? new PrimitiveSoyType(soyType, memberType.runtimeType()) : null;
  }

  @Nullable
  private static PrimitiveSoyType updateMemberType(PrimitiveSoyType memberType, PrimitiveSoyType primitive) {
    if (memberType == null) {
      return primitive;
    }
    if (!memberType.runtimeType().equals(primitive.runtimeType())
        || !memberType.box().runtimeType().equals(primitive.box().runtimeType())) {
      return null;
    }
    return memberType;
  }

  /** Returns the runtime type for the message corresponding to the given descriptor. */
  public static Type protoType(Descriptor descriptor) {
    return BytecodeUtils.getTypeForClassName(JavaQualifiedNames.getClassName(descriptor));
  }

  private final SoyType soyType;
  private final Type runtimeType;

  private SoyRuntimeType(SoyType soyType, Type runtimeType) {
    this.soyType = checkNotNull(soyType);
    this.runtimeType = checkNotNull(runtimeType);
  }

  public final SoyType soyType() {
    return soyType;
  }

  public final Type runtimeType() {
    return runtimeType;
  }

  public boolean assignableToNullableInt() {
    return assignableToNullableType(IntType.getInstance());
  }

  public boolean assignableToNullableFloat() {
    return assignableToNullableType(FloatType.getInstance());
  }

  public boolean assignableToNullableNumber() {
    return assignableToNullableType(SoyTypes.NUMBER_TYPE);
  }

  public boolean assignableToNullableString() {
    return soyType.getKind().isKnownStringOrSanitizedContent()
        || (soyType.getKind() == Kind.UNION
            && SoyTypes.removeNull(soyType).getKind().isKnownStringOrSanitizedContent());
  }

  private boolean assignableToNullableType(SoyType type) {
    return type.isAssignableFromStrict(soyType)
        || (soyType.getKind() == Kind.UNION
            && type.isAssignableFromStrict(SoyTypes.removeNull(soyType)));
  }

  public boolean isKnownString() {
    return soyType.getKind() == Kind.STRING;
  }

  public boolean isKnownStringOrSanitizedContent() {
    if (soyType.getKind().isKnownStringOrSanitizedContent()) {
      return true;
    }
    if (soyType.getKind() == Kind.UNION) {
      return ((UnionType) soyType).getMembers().stream()
          .allMatch(member -> member.getKind().isKnownStringOrSanitizedContent());
    }
    return false;
  }

  public boolean isKnownSanitizedContent() {
    return soyType.getKind().isKnownSanitizedContent();
  }

  public boolean isKnownInt() {
    return soyType.getKind() == Kind.INT || SoyTypes.isKindOrUnionOfKind(soyType, Kind.PROTO_ENUM);
  }

  public final boolean isKnownFloat() {
    return soyType.getKind() == Kind.FLOAT;
  }

  public final boolean isKnownListOrUnionOfLists() {
    return SoyTypes.isKindOrUnionOfKind(soyType, Kind.LIST);
  }

  public final ListType asListType() {
    checkState(isKnownListOrUnionOfLists());
    if (soyType instanceof ListType) {
      return (ListType) soyType;
    }
    List<SoyType> members = new ArrayList<>();
    for (SoyType member : ((UnionType) soyType).getMembers()) {
      ListType memberAsList = (ListType) member;
      if (memberAsList.getElementType() != null) {
        members.add(memberAsList.getElementType());
      }
    }
    return ListType.of(UnionType.of(members));
  }

  public final boolean isKnownLegacyObjectMapOrUnionOfMaps() {
    return SoyTypes.isKindOrUnionOfKind(soyType, Kind.LEGACY_OBJECT_MAP);
  }

  public final boolean isKnownMapOrUnionOfMaps() {
    return SoyTypes.isKindOrUnionOfKind(soyType, Kind.MAP);
  }

  public final boolean isKnownBool() {
    return soyType.getKind() == Kind.BOOL;
  }

  public final boolean isKnownProtoOrUnionOfProtos() {
    return SoyTypes.isKindOrUnionOfKind(soyType, Kind.PROTO);
  }

  public final boolean isKnownNumber() {
    return SoyTypes.NUMBER_TYPE.isAssignableFromStrict(soyType);
  }

  public final SoyRuntimeType asNonSoyNullish() {
    return withNewSoyType(SoyTypes.tryRemoveNull(soyType));
  }

  public final SoyRuntimeType asSoyNullish() {
    return withNewSoyType(SoyTypes.makeNullable(soyType));
  }

  private SoyRuntimeType withNewSoyType(SoyType newSoyType) {
    if (newSoyType != soyType) {
      return isBoxed() ? getBoxedType(newSoyType) : getUnboxedType(newSoyType).orElse(this);
    }
    return this;
  }

  public abstract boolean isBoxed();

  public SoyRuntimeType box() {
    return isBoxed() ? this : getBoxedType(soyType());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SoyRuntimeType)) return false;
    SoyRuntimeType that = (SoyRuntimeType) o;
    return Objects.equal(soyType, that.soyType)
        && Objects.equal(runtimeType, that.runtimeType)
        && isBoxed() == that.isBoxed();
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(soyType, runtimeType, isBoxed());
  }

  @Override
  public String toString() {
    return "SoyRuntimeType{" + soyType + ", " + runtimeType + "}";
  }

  private static final class PrimitiveSoyType extends SoyRuntimeType {

    PrimitiveSoyType(SoyType soyType, Type runtimeType) {
      super(soyType, runtimeType);
    }

    @Override
    public boolean isBoxed() {
      return false;
    }
  }

  private static final class BoxedSoyType extends SoyRuntimeType {

    BoxedSoyType(SoyType soyType, Type runtimeType) {
      super(soyType, runtimeType);
    }

    @Override
    public boolean isBoxed() {
      return true;
    }
  }
}
