
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

package com.google.template.soy.types;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.template.soy.internal.proto.Field;
import com.google.template.soy.internal.proto.FieldVisitor;
import com.google.template.soy.internal.proto.ProtoUtils;
import com.google.template.soy.soytree.SoyTypeP;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** A {@link SoyType} subclass which describes a protocol buffer type. */
public final class SoyProtoType extends SoyType {
  private static final class TypeVisitor extends FieldVisitor<SoyType> {
    private final TypeInterner interner;
    private final ProtoTypeRegistry registry;

    TypeVisitor(TypeInterner interner, ProtoTypeRegistry registry) {
      this.interner = interner;
      this.registry = registry;
    }

    @Override
    protected SoyType visitMap(FieldDescriptor mapField, SoyType keyType, SoyType valueType) {
      return interner.getOrCreateMapType(keyType, SoyTypes.removeNull(valueType));
    }

    @Override
    protected SoyType visitRepeated(SoyType value) {
      return interner.getOrCreateListType(SoyTypes.removeNull(value));
    }

    @Override
    protected SoyType visitMessage(Descriptor messageType) {
      return interner.getOrCreateUnionType(
          registry.getProtoType(messageType.getFullName()), NullType.getInstance());
    }

    @Override
    protected SoyType visitEnum(EnumDescriptor enumType, FieldDescriptor fieldType) {
      return registry.getProtoType(enumType.getFullName());
    }

    @Override
    protected SoyType visitLongAsInt() {
      return IntType.getInstance();
    }

    @Override
    protected SoyType visitUnsignedInt() {
      return IntType.getInstance();
    }

    @Override
    protected SoyType visitUnsignedLongAsString() {
      return StringType.getInstance();
    }

    @Override
    protected SoyType visitLongAsString() {
      return StringType.getInstance();
    }

    @Override
    protected SoyType visitBool() {
      return BoolType.getInstance();
    }

    @Override
    protected SoyType visitInt() {
      return IntType.getInstance();
    }

    @Override
    protected SoyType visitBytes() {
      return StringType.getInstance();
    }

    @Override
    protected SoyType visitString() {
      return StringType.getInstance();
    }

    @Override
    protected SoyType visitDoubleAsFloat() {
      return FloatType.getInstance();
    }

    @Override
    protected SoyType visitFloat() {
      return FloatType.getInstance();
    }

    @Override
    protected SoyType visitSafeHtml() {
      return interner.getOrCreateNullableType(SanitizedType.HtmlType.getInstance());
    }

    @Override
    protected SoyType visitSafeScript() {
      return interner.getOrCreateNullableType(SanitizedType.JsType.getInstance());
    }

    @Override
    protected SoyType visitSafeStyle() {
      return interner.getOrCreateNullableType(SanitizedType.StyleType.getInstance());
    }

    @Override
    protected SoyType visitSafeStyleSheet() {
      return interner.getOrCreateNullableType(SanitizedType.StyleType.getInstance());
    }

    @Override
    protected SoyType visitSafeUrl() {
      return interner.getOrCreateNullableType(SanitizedType.UriType.getInstance());
    }

    @Override
    protected SoyType visitTrustedResourceUrl() {
      return interner.getOrCreateNullableType(SanitizedType.TrustedResourceUriType.getInstance());
    }
  }

  private static final class FieldWithType extends Field {
    @GuardedBy("this")
    private SoyType type;

    @GuardedBy("this")
    private TypeVisitor visitor;

    FieldWithType(FieldDescriptor fieldDesc, TypeVisitor visitor) {
      super(fieldDesc);
      this.visitor = visitor;
    }

    synchronized SoyType getType() {
      if (type == null) {
        type = FieldVisitor.visitField(getDescriptor(), visitor);
        checkNotNull(type, "Couldn't find a type for: %s", getDescriptor());
        visitor = null;
      }
      return type;
    }
  }

  public static SoyProtoType newForTest(Descriptor d) {
    return new SoyProtoType(TypeRegistries.newTypeInterner(), (fqn) -> null, d, ImmutableSet.of());
  }

  private final Object scope;
  private final Descriptor typeDescriptor;
  private final ImmutableMap<String, FieldWithType> fields;
  private final ImmutableSet<String> extensionFieldNames;

  SoyProtoType(
      TypeInterner interner,
      ProtoTypeRegistry registry,
      Descriptor descriptor,
      Set<FieldDescriptor> extensions) {
    this(interner, new TypeVisitor(interner, registry), descriptor, extensions);
  }

  private SoyProtoType(
      Object scope, TypeVisitor visitor, Descriptor descriptor, Set<FieldDescriptor> extensions) {
    this.scope = scope;
    this.typeDescriptor = descriptor;
    this.fields = initializeFields(descriptor, extensions, visitor);
    this.extensionFieldNames = initializeExtensionFieldNames(extensions);
  }

  private ImmutableMap<String, FieldWithType> initializeFields(
      Descriptor descriptor, Set<FieldDescriptor> extensions, TypeVisitor visitor) {
    return Field.getFieldsForType(
        descriptor, extensions, fieldDescriptor -> new FieldWithType(fieldDescriptor, visitor));
  }

  private ImmutableSet<String> initializeExtensionFieldNames(Set<FieldDescriptor> extensions) {
    return extensions.stream()
        .map(Field::computeSoyFullyQualifiedName)
        .sorted()
        .collect(toImmutableSet());
  }

  @Override
  public Kind getKind() {
    return Kind.PROTO;
  }

  @Override
  boolean doIsAssignableFromNonUnionType(SoyType fromType) {
    return fromType == this;
  }

  public Descriptor getDescriptor() {
    return typeDescriptor;
  }

  public String getDescriptorExpression() {
    return ProtoUtils.getQualifiedOuterClassname(typeDescriptor);
  }

  public FieldDescriptor getFieldDescriptor(String fieldName) {
    FieldWithType field = fields.get(fieldName);
    if (field == null) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot find field %s in %s. Known fields are %s.",
              fieldName, this, getFieldNames()));
    }
    return field.getDescriptor();
  }

  @Nullable
  public SoyType getFieldType(String fieldName) {
    FieldWithType field = fields.get(fieldName);
    return field != null ? field.getType() : null;
  }

  public ImmutableSet<String> getFieldNames() {
    return fields.keySet();
  }

  public ImmutableSet<String> getExtensionFieldNames() {
    return extensionFieldNames;
  }

  public String getJsName(ProtoUtils.MutabilityMode mutabilityMode) {
    return ProtoUtils.calculateUnprefixedJsName(typeDescriptor);
  }

  @Override
  public String toString() {
    return typeDescriptor.getFullName();
  }

  @Override
  void doToProto(SoyTypeP.Builder builder) {
    builder.setProto(typeDescriptor.getFullName());
  }

  @Override
  public <T> T accept(SoyTypeVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SoyProtoType that = (SoyProtoType) o;
    if (scope != that.scope) {
      throw new IllegalArgumentException(
          String.format(
              "Illegal comparison of two SoyProtoType's from different type registries %s and %s.",
              scope, that.scope));
    }
    return Objects.equal(typeDescriptor.getFullName(), that.typeDescriptor.getFullName());
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hashCode(typeDescriptor.getFullName());
  }
}
