
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

package com.google.template.soy.internal.proto;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Ascii;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.Set;

/**
 * A proto member field.
 *
 * <p>This is used to calculate field names and handle ambiguous extensions. Additional logic should
 * be handled by subclasses.
 */
public abstract class Field {
  /** A factory for field types. */
  public interface Factory<T extends Field> {
    /** Returns a field. */
    T create(FieldDescriptor fieldDescriptor);
  }

  /** Returns the set of fields indexed by soy accessor name for the given type. */
  public static <T extends Field> ImmutableMap<String, T> getFieldsForType(
      Descriptor descriptor, Set<FieldDescriptor> extensions, Factory<T> factory) {
    ImmutableMap.Builder<String, T> fields = ImmutableMap.builder();
    addFieldsToMap(descriptor, factory, fields);
    addExtensionsToMap(extensions, factory, fields);
    return fields.build();
  }

  private static <T extends Field> void addFieldsToMap(Descriptor descriptor, Factory<T> factory,
      ImmutableMap.Builder<String, T> fields) {
    for (FieldDescriptor fieldDescriptor : descriptor.getFields()) {
      if (ProtoUtils.shouldJsIgnoreField(fieldDescriptor)) {
        continue;
      }
      T field = factory.create(fieldDescriptor);
      fields.put(field.getName(), field);
    }
  }

  private static <T extends Field> void addExtensionsToMap(Set<FieldDescriptor> extensions, Factory<T> factory,
      ImmutableMap.Builder<String, T> fields) {
    for (FieldDescriptor extension : extensions) {
      T field = factory.create(extension);
      fields.put(field.getFullyQualifiedName(), field);
    }
  }

  private final FieldDescriptor fieldDesc;
  private final String name;
  private final String fullyQualifiedName;

  protected Field(FieldDescriptor fieldDesc) {
    this.fieldDesc = checkNotNull(fieldDesc);
    this.name = computeSoyName(fieldDesc);
    this.fullyQualifiedName = computeSoyFullyQualifiedName(fieldDesc);
  }

  /** Return the name of this member field. */
  public final String getName() {
    return name;
  }

  /** Return the fully qualified name of this member field. */
  public final String getFullyQualifiedName() {
    return fullyQualifiedName;
  }

  public final FieldDescriptor getDescriptor() {
    return fieldDesc;
  }

  /** Converts snake case to lower camel case and appends 'List' or 'Map' if necessary. */
  public static String computeSoyName(FieldDescriptor field) {
    String result = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, field.getName());
    return formatName(result) + fieldSuffix(field);
  }

  private static String formatName(String name) {
    String lowerCamelCaseGuarantee =
        Ascii.toLowerCase(name.substring(0, Math.min(1, name.length())))
            + name.substring(Math.min(1, name.length()));
    return lowerCamelCaseGuarantee;
  }

  public static String computeSoyFullyQualifiedName(FieldDescriptor field) {
    String fieldPath = determineFieldPath(field);
    return fieldPath + "." + computeSoyName(field);
  }

  private static String determineFieldPath(FieldDescriptor field) {
    if (!field.isExtension()) {
      return field.getContainingType().getFullName();
    } else if (field.getExtensionScope() != null) {
      return field.getExtensionScope().getFullName();
    } else {
      return field.getFile().getPackage();
    }
  }

  private static String fieldSuffix(FieldDescriptor field) {
    if (field.isMapField()) {
      return "Map";
    } else if (field.isRepeated()) {
      return "List";
    } else {
      return "";
    }
  }
}
