
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

package com.google.template.soy.types.ast;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.template.soy.types.SoyTypes.SAFE_PROTO_TO_SANITIZED_TYPE;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.DoNotCall;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.error.SoyErrors;
import com.google.template.soy.types.FunctionType;
import com.google.template.soy.types.NullType;
import com.google.template.soy.types.ProtoTypeRegistry;
import com.google.template.soy.types.RecordType;
import com.google.template.soy.types.SanitizedType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.TemplateType;
import com.google.template.soy.types.TypeInterner;
import com.google.template.soy.types.TypeRegistries;
import com.google.template.soy.types.TypeRegistry;
import com.google.template.soy.types.UnknownType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves {@link TypeNode}s into {@link SoyType}s. */
public final class TypeNodeConverter
    implements TypeNodeVisitor<SoyType>, Function<TypeNode, SoyType> {

  private static final SoyErrorKind UNKNOWN_TYPE =
      SoyErrorKind.of("Unknown type ''{0}''.{1}", StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind DUPLICATE_RECORD_FIELD =
      SoyErrorKind.of("Duplicate field ''{0}'' in record declaration.");

  private static final SoyErrorKind DUPLICATE_TEMPLATE_ARGUMENT =
      SoyErrorKind.of("Duplicate argument ''{0}'' in template type declaration.");

  private static final SoyErrorKind DUPLICATE_FUNCTION_PARAM =
      SoyErrorKind.of("Duplicate parameter ''{0}'' in function type declaration.");

  private static final SoyErrorKind INVALID_TEMPLATE_RETURN_TYPE =
      SoyErrorKind.of(
          "Template types can only return html, attributes, string, js, css, uri, or"
              + " trusted_resource_uri.");

  private static final SoyErrorKind UNEXPECTED_TYPE_PARAM =
      SoyErrorKind.of(
          "Unexpected type parameter: ''{0}'' only has {1}", StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind EXPECTED_TYPE_PARAM =
      SoyErrorKind.of("Expected a type parameter: ''{0}'' has {1}", StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind NOT_A_GENERIC_TYPE =
      SoyErrorKind.of("''{0}'' is not a generic type, expected ''list'' or ''map''.");

  private static final SoyErrorKind MISSING_GENERIC_TYPE_PARAMETERS =
      SoyErrorKind.of("''{0}'' is a generic type, expected {1}.");

  public static final SoyErrorKind SAFE_PROTO_TYPE =
      SoyErrorKind.of("Please use Soy''s native ''{0}'' type instead of the ''{1}'' type.");

  public static final SoyErrorKind DASH_NOT_ALLOWED =
      SoyErrorKind.of(
          "parse error at ''-'': expected identifier",
          StyleAllowance.NO_CAPS,
          StyleAllowance.NO_PUNCTUATION);

  private static final ImmutableSet<Kind> ALLOWED_TEMPLATE_RETURN_TYPES =
      Sets.immutableEnumSet(
          Kind.ELEMENT,
          Kind.HTML,
          Kind.ATTRIBUTES,
          Kind.STRING,
          Kind.JS,
          Kind.CSS,
          Kind.URI,
          Kind.TRUSTED_RESOURCE_URI);

  private static final ImmutableMap<String, BaseGenericTypeInfo> GENERIC_TYPES =
      ImmutableMap.of(
          "list",
          new GenericTypeInfo(1) {
            @Override
            SoyType create(List<SoyType> types, TypeInterner interner) {
              return interner.getOrCreateListType(types.get(0));
            }
          },
          "legacy_object_map",
          new GenericTypeInfo(2) {
            @Override
            SoyType create(List<SoyType> types, TypeInterner interner) {
              return interner.getOrCreateLegacyObjectMapType(types.get(0), types.get(1));
            }
          },
          "map",
          new GenericTypeInfo(2) {
            @Override
            SoyType create(List<SoyType> types, TypeInterner interner) {
              return interner.getOrCreateMapType(types.get(0), types.get(1));
            }
          },
          "ve",
          new GenericTypeInfo(1) {
            @Override
            SoyType create(List<SoyType> types, TypeInterner interner) {
              return interner.getOrCreateVeType(types.get(0).toString());
            }
          });

  private static final ImmutableMap<String, BaseGenericTypeInfo> GENERIC_TYPES_WITH_ELEMENT =
      new ImmutableMap.Builder<String, BaseGenericTypeInfo>()
          .putAll(GENERIC_TYPES)
          .put(
              "html",
              new StringArgGenericTypeInfo(1) {
                @Override
                SoyType create(List<String> types, TypeInterner interner) {
                  String tag = types.size() == 1 && !"?".equals(types.get(0)) ? types.get(0) : "";
                  return interner.getOrCreateElementType(tag);
                }
              })
          .build();

  private abstract static class BaseGenericTypeInfo {
    final int numParams;

    BaseGenericTypeInfo(int numParams) {
      this.numParams = numParams;
    }

    final String formatNumTypeParams() {
      return numParams + " type parameter" + (numParams > 1 ? "s" : "");
    }
  }

  /** Simple representation of a generic type specification. */
  private abstract static class GenericTypeInfo extends BaseGenericTypeInfo {
    public GenericTypeInfo(int numParams) {
      super(numParams);
    }

    abstract SoyType create(List<SoyType> types, TypeInterner interner);
  }

  private abstract static class StringArgGenericTypeInfo extends BaseGenericTypeInfo {
    public StringArgGenericTypeInfo(int numParams) {
      super(numParams);
    }

    abstract SoyType create(List<String> types, TypeInterner interner);
  }

  public static Builder builder(ErrorReporter errorReporter) {
    return new Builder().setErrorReporter(errorReporter);
  }

  /** Builder pattern for {@link TypeNodeConverter}. */
  public static class Builder {
    private ErrorReporter errorReporter;
    private TypeInterner interner;
    private TypeRegistry typeRegistry;
    private ProtoTypeRegistry protoRegistry;
    private boolean disableAllTypeChecking = false;
    private boolean systemExternal = false;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder setErrorReporter(ErrorReporter errorReporter) {
      this.errorReporter = Preconditions.checkNotNull(errorReporter);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setDisableAllTypeChecking(boolean disableAllTypeChecking) {
      this.disableAllTypeChecking = disableAllTypeChecking;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSystemExternal(boolean systemExternal) {
      this.systemExternal = systemExternal;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setTypeRegistry(SoyTypeRegistry typeRegistry) {
      this.interner = typeRegistry;
      this.typeRegistry = typeRegistry;
      this.protoRegistry = typeRegistry.getProtoRegistry();
      return this;
    }

    public TypeNodeConverter build() {
      Preconditions.checkState(interner != null);
      return new TypeNodeConverter(
          errorReporter,
          interner,
          systemExternal ? TypeRegistries.builtinTypeRegistry() : typeRegistry,
          systemExternal ? protoRegistry : null,
          disableAllTypeChecking);
    }
  }

  private final ErrorReporter errorReporter;
  private final TypeInterner interner;
  private final TypeRegistry typeRegistry;
  private final ProtoTypeRegistry protoRegistry;
  private final boolean disableAllTypeChecking;

  private TypeNodeConverter(
      ErrorReporter errorReporter,
      TypeInterner interner,
      TypeRegistry typeRegistry,
      ProtoTypeRegistry protoRegistry,
      boolean disableAllTypeChecking) {
    this.errorReporter = errorReporter;
    this.interner = interner;
    this.typeRegistry = typeRegistry;
    this.protoRegistry = protoRegistry;
    this.disableAllTypeChecking = disableAllTypeChecking;
  }

  public SoyType getOrCreateType(TypeNode node) {
    return node.accept(this);
  }

  @Override
  public SoyType visit(NamedTypeNode node) {
    String name = node.name().identifier();
    handleDashNotAllowed(name, node);
    SoyType type = resolveNamedType(name, node);
    node.setResolvedType(type);
    return type;
  }

  private void handleDashNotAllowed(String name, NamedTypeNode node) {
    if (name.contains("-")) {
      errorReporter.report(node.sourceLocation(), DASH_NOT_ALLOWED);
    }
  }

  private SoyType resolveNamedType(String name, NamedTypeNode node) {
    SoyType type =
        typeRegistry instanceof SoyTypeRegistry
            ? TypeRegistries.getTypeOrProtoFqn(
                (SoyTypeRegistry) typeRegistry, errorReporter, node.name())
            : typeRegistry.getType(name);

    if (type == null && protoRegistry != null) {
      type = protoRegistry.getProtoType(name);
    }

    if (type == null) {
      BaseGenericTypeInfo genericType = GENERIC_TYPES.get(name);
      if (genericType != null) {
        errorReporter.report(
            node.sourceLocation(),
            MISSING_GENERIC_TYPE_PARAMETERS,
            name,
            genericType.formatNumTypeParams());
      } else {
        reportUnknownType(name, node);
      }
      return UnknownType.getInstance();
    }
    return checkSafeProtoType(type, node, name);
  }

  private void reportUnknownType(String name, NamedTypeNode node) {
    if (!disableAllTypeChecking) {
      errorReporter.report(
          node.sourceLocation(),
          UNKNOWN_TYPE,
          name,
          SoyErrors.getDidYouMeanMessage(typeRegistry.getAllSortedTypeNames(), name));
    }
  }

  private SoyType checkSafeProtoType(SoyType type, NamedTypeNode node, String name) {
    if (type.getKind() == Kind.PROTO) {
      SanitizedType safeProtoType = SAFE_PROTO_TO_SANITIZED_TYPE.get(type.toString());
      if (safeProtoType != null) {
        String safeProtoNativeType = safeProtoType.getContentKind().asAttributeValue();
        errorReporter.report(node.sourceLocation(), SAFE_PROTO_TYPE, safeProtoNativeType, name);
        return UnknownType.getInstance();
      }
    }
    return type;
  }

  @Override
  public SoyType visit(GenericTypeNode node) {
    return visit(node, GENERIC_TYPES);
  }

  private SoyType visit(GenericTypeNode node, ImmutableMap<String, BaseGenericTypeInfo> genericTypes) {
    ImmutableList<TypeNode> args = node.arguments();
    String name = node.name();
    BaseGenericTypeInfo genericType = genericTypes.get(name);
    if (genericType == null) {
      errorReporter.report(node.sourceLocation(), NOT_A_GENERIC_TYPE, name);
      return UnknownType.getInstance();
    }
    if (args.size() < genericType.numParams) {
      reportExpectedTypeParam(node, name, genericType);
      return UnknownType.getInstance();
    } else if (args.size() > genericType.numParams) {
      reportUnexpectedTypeParam(args, genericType, node, name);
      return UnknownType.getInstance();
    }

    SoyType type = createType(node, genericType, args);
    node.setResolvedType(type);
    return type;
  }

  private void reportExpectedTypeParam(GenericTypeNode node, String name, BaseGenericTypeInfo genericType) {
    errorReporter.report(
        // blame the '>'
        node.sourceLocation().getEndLocation(),
        EXPECTED_TYPE_PARAM,
        name,
        genericType.formatNumTypeParams());
  }

  private void reportUnexpectedTypeParam(ImmutableList<TypeNode> args, BaseGenericTypeInfo genericType,
                                          GenericTypeNode node, String name) {
    errorReporter.report(
        // blame the first unexpected argument
        args.get(genericType.numParams).sourceLocation(),
        UNEXPECTED_TYPE_PARAM,
        name,
        genericType.formatNumTypeParams());
  }

  private SoyType createType(GenericTypeNode node, BaseGenericTypeInfo genericType, ImmutableList<TypeNode> args) {
    if (genericType instanceof GenericTypeInfo) {
      return ((GenericTypeInfo) genericType)
          .create(args.stream().map(this).collect(toImmutableList()), interner);
    } else if (genericType instanceof StringArgGenericTypeInfo) {
      return ((StringArgGenericTypeInfo) genericType)
          .create(args.stream().map(TypeNode::toString).collect(Collectors.toList()), interner);
    } else {
      throw new AssertionError();
    }
  }

  @Override
  public SoyType visit(UnionTypeNode node) {
    SoyType type =
        interner.getOrCreateUnionType(
            node.candidates().stream().map(this).collect(toImmutableList()));
    node.setResolvedType(type);
    return type;
  }

  @Override
  public SoyType visit(RecordTypeNode node) {
    Map<String, RecordType.Member> map = Maps.newLinkedHashMap();
    for (RecordTypeNode.Property property : node.properties()) {
      processRecordProperty(property, map);
    }
    SoyType type = interner.getOrCreateRecordType(map.values());
    node.setResolvedType(type);
    return type;
  }

  private void processRecordProperty(RecordTypeNode.Property property, Map<String, RecordType.Member> map) {
    RecordType.Member oldType =
        map.put(
            property.name(),
            RecordType.memberOf(
                property.name(), property.optional(), property.type().accept(this)));
    if (oldType != null) {
      errorReporter.report(property.nameLocation(), DUPLICATE_RECORD_FIELD, property.name());
      map.put(property.name(), oldType);
    }
  }

  @Override
  public SoyType visit(TemplateTypeNode node) {
    Map<String, TemplateType.Parameter> map = new LinkedHashMap<>();
    for (TemplateTypeNode.Parameter parameter : node.parameters()) {
      processTemplateParameter(parameter, map);
    }
    SoyType returnType = handleReturnTypeOfTemplateType(node.returnType());
    validateReturnType(node, returnType);
    SoyType type =
        interner.internTemplateType(
            TemplateType.declaredTypeOf(
                map.values(), returnType, NullType.getInstance(), false, false, ""));
    node.setResolvedType(type);
    return type;
  }

  private void processTemplateParameter(TemplateTypeNode.Parameter parameter, Map<String, TemplateType.Parameter> map) {
    TemplateType.Parameter oldParameter =
        map.put(
            parameter.name(),
            TemplateType.Parameter.builder()
                .setName(parameter.name())
                .setKind(parameter.kind())
                .setType(parameter.type().accept(this))
                .setRequired(true)
                .setImplicit(false)
                .build());
    if (oldParameter != null) {
      errorReporter.report(
          parameter.nameLocation(), DUPLICATE_TEMPLATE_ARGUMENT, parameter.name());
      map.put(parameter.name(), oldParameter);
    }
  }

  private void validateReturnType(TemplateTypeNode node, SoyType returnType) {
    if (!ALLOWED_TEMPLATE_RETURN_TYPES.contains(returnType.getKind())) {
      errorReporter.report(node.returnType().sourceLocation(), INVALID_TEMPLATE_RETURN_TYPE);
    }
  }

  @Override
  public SoyType visit(FunctionTypeNode node) {
    Map<String, FunctionType.Parameter> map = new LinkedHashMap<>();
    for (FunctionTypeNode.Parameter parameter : node.parameters()) {
      processFunctionParameter(parameter, map);
    }
    SoyType type = interner.intern(FunctionType.of(map.values(), node.returnType().accept(this)));
    node.setResolvedType(type);
    return type;
  }

  private void processFunctionParameter(FunctionTypeNode.Parameter parameter, Map<String, FunctionType.Parameter> map) {
    FunctionType.Parameter oldParameter =
        map.put(
            parameter.name(),
            FunctionType.Parameter.of(parameter.name(), parameter.type().accept(this)));
    if (oldParameter != null) {
      errorReporter.report(parameter.nameLocation(), DUPLICATE_FUNCTION_PARAM, parameter.name());
      map.put(parameter.name(), oldParameter);
    }
  }

  private SoyType handleReturnTypeOfTemplateType(TypeNode node) {
    if (node instanceof GenericTypeNode) {
      return visit((GenericTypeNode) node, GENERIC_TYPES_WITH_ELEMENT);
    }
    return node.accept(this);
  }

  @DoNotCall
  @Override
  public SoyType apply(TypeNode node) {
    return node.accept(this);
  }
}
