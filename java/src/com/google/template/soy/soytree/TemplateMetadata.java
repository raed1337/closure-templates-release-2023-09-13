
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
package com.google.template.soy.soytree;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.primitives.Booleans.trueFirst;
import static java.util.Comparator.comparing;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.soytree.SoyNode.Kind;
import com.google.template.soy.soytree.defn.AttrParam;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.types.NullType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.TemplateType;
import com.google.template.soy.types.TemplateType.DataAllCallSituation;
import com.google.template.soy.types.TemplateType.Parameter;
import com.google.template.soy.types.TemplateType.ParameterKind;
import com.google.template.soy.types.UnknownType;
import javax.annotation.Nullable;

/**
 * An abstract representation of a template that provides the minimal amount of information needed
 * compiling against dependency templates.
 *
 * <p>When compiling with dependencies the compiler needs to examine certain information from
 * dependent templates in order to validate calls and escape call sites. Traditionally, the Soy
 * compiler accomplished this by having each compilation parse all transitive dependencies. This is
 * an expensive solution. So instead of that we instead use this object to represent the minimal
 * information we need about dependencies.
 *
 * <p>The APIs on this class mirror ones available on {@link TemplateNode}.
 */
@AutoValue
public abstract class TemplateMetadata {

  /** Builds a Template from a parsed TemplateNode. */
  public static TemplateMetadata fromTemplate(TemplateNode template) {
    TemplateMetadata.Builder builder = initializeBuilder(template);
    setHtmlElementIfPresent(builder, template);
    setDelegateTemplateInfo(builder, template);
    return builder.build();
  }

  private static TemplateMetadata.Builder initializeBuilder(TemplateNode template) {
    return builder()
            .setTemplateName(template.getTemplateName())
            .setSourceLocation(template.getSourceLocation())
            .setSoyFileKind(SoyFileKind.SRC)
            .setSoyElement(createSoyElementMetadata(template))
            .setTemplateType(buildTemplateType(template))
            .setComponent(template.getComponent())
            .setModName(template.getModName())
            .setVisibility(template.getVisibility());
  }

  private static SoyElementMetadataP createSoyElementMetadata(TemplateNode template) {
    return SoyElementMetadataP.newBuilder()
            .setIsSoyElement(template instanceof TemplateElementNode)
            .build();
  }

  private static void setHtmlElementIfPresent(TemplateMetadata.Builder builder, TemplateNode template) {
    if (template.getHtmlElementMetadata() != null) {
      builder.setHtmlElement(template.getHtmlElementMetadata());
    }
  }

  private static void setDelegateTemplateInfo(TemplateMetadata.Builder builder, TemplateNode template) {
    if (template.getKind() == Kind.TEMPLATE_DELEGATE_NODE) {
      TemplateDelegateNode delTemplate = (TemplateDelegateNode) template;
      builder.setDelTemplateName(delTemplate.getDelTemplateName());
      builder.setDelTemplateVariant(delTemplate.getDelTemplateVariant());
    } else if (template instanceof TemplateBasicNode) {
      setBasicTemplateInfo(builder, (TemplateBasicNode) template);
    }
  }

  private static void setBasicTemplateInfo(TemplateMetadata.Builder builder, TemplateBasicNode basicTemplate) {
    if (basicTemplate.isModifiable()) {
      builder.setDelTemplateName(basicTemplate.getTemplateName());
      builder.setDelTemplateVariant(basicTemplate.getDelTemplateVariant());
    } else if (basicTemplate.getModifiesExpr() != null) {
      setDelTemplateNameFromModifiesExpr(builder, basicTemplate);
      builder.setDelTemplateVariant(basicTemplate.getDelTemplateVariant());
    }
  }

  private static void setDelTemplateNameFromModifiesExpr(TemplateMetadata.Builder builder, TemplateBasicNode basicTemplate) {
    if (basicTemplate.getModifiesExpr().getRoot() instanceof TemplateLiteralNode) {
      SoyType modifiableType = basicTemplate.getModifiesExpr().getRoot().getType();
      builder.setDelTemplateName(getDelTemplateNameFromType(modifiableType, basicTemplate));
    } else {
      builder.setDelTemplateName("$__unresolvable__");
    }
  }

  private static String getDelTemplateNameFromType(SoyType modifiableType, TemplateBasicNode basicTemplate) {
    return (modifiableType instanceof TemplateType && !((TemplateType) modifiableType).getLegacyDeltemplateNamespace().isEmpty())
            ? ((TemplateType) modifiableType).getLegacyDeltemplateNamespace()
            : ((TemplateLiteralNode) basicTemplate.getModifiesExpr().getRoot()).getResolvedName();
  }

  public static TemplateType buildTemplateType(TemplateNode template) {
    TemplateType.Builder builder = TemplateType.builder()
            .setTemplateKind(convertKind(template.getKind()))
            .setAllowExtraAttributes(template.getAllowExtraAttributes())
            .setReservedAttributes(template.getReservedAttributes())
            .setContentKind(template.getTemplateContentKind())
            .setStrictHtml(template.isStrictHtml())
            .setParameters(directParametersFromTemplate(template))
            .setDataAllCallSituations(dataAllCallSituationFromTemplate(template))
            .setIdentifierForDebugging(template.getTemplateName());
    setAdditionalTemplateTypeInfo(builder, template);
    return builder.build();
  }

  private static void setAdditionalTemplateTypeInfo(TemplateType.Builder builder, TemplateNode template) {
    if (template instanceof TemplateBasicNode) {
      TemplateBasicNode templateBasicNode = (TemplateBasicNode) template;
      builder.setUseVariantType(templateBasicNode.getUseVariantType())
             .setModifiable(templateBasicNode.isModifiable())
             .setModifying(templateBasicNode.getModifiesExpr() != null)
             .setLegacyDeltemplateNamespace(templateBasicNode.getLegacyDeltemplateNamespace());
    } else {
      builder.setUseVariantType(NullType.getInstance());
    }
  }

  public static TemplateMetadata.Builder builder() {
    return new AutoValue_TemplateMetadata.Builder();
  }

  private static ImmutableList<Parameter> directParametersFromTemplate(TemplateNode node) {
    return node.getParams().stream()
        .sorted(comparing(TemplateParam::isRequired, trueFirst()))
        .filter(param -> !param.isImplicit())
        .map(TemplateMetadata::parameterFromTemplateParam)
        .collect(toImmutableList());
  }

  public static Parameter parameterFromTemplateParam(TemplateParam param) {
    return Parameter.builder()
        .setName(param.name())
        .setKind(param instanceof AttrParam ? ParameterKind.ATTRIBUTE : ParameterKind.PARAM)
        .setType(param.typeOrDefault(UnknownType.getInstance()))
        .setRequired(param.isRequired())
        .setImplicit(param.isImplicit())
        .setDescription(param.desc())
        .build();
  }

  private static ImmutableList<DataAllCallSituation> dataAllCallSituationFromTemplate(TemplateNode node) {
    return SoyTreeUtils.allNodesOfType(node, CallNode.class)
        .filter(CallNode::isPassingAllData)
        .map(TemplateMetadata::createDataAllCallSituation)
        .collect(toImmutableSet())
        .asList();
  }

  private static DataAllCallSituation createDataAllCallSituation(CallNode call) {
    DataAllCallSituation.Builder builder = DataAllCallSituation.builder();
    ImmutableSet<String> explicitlyPassedParams = collectExplicitlyPassedParams(call);
    builder.setExplicitlyPassedParameters(explicitlyPassedParams);
    setCallTypeInfo(builder, call);
    return builder.build();
  }

  private static ImmutableSet<String> collectExplicitlyPassedParams(CallNode call) {
    ImmutableSet.Builder<String> explicitlyPassedParams = ImmutableSet.builder();
    for (CallParamNode param : call.getChildren()) {
      explicitlyPassedParams.add(param.getKey().identifier());
    }
    return explicitlyPassedParams.build();
  }

  private static void setCallTypeInfo(DataAllCallSituation.Builder builder, CallNode call) {
    switch (call.getKind()) {
      case CALL_BASIC_NODE:
        setBasicCallInfo(builder, (CallBasicNode) call);
        break;
      case CALL_DELEGATE_NODE:
        builder.setDelCall(true)
               .setTemplateName(((CallDelegateNode) call).getDelCalleeName());
        break;
      default:
        throw new AssertionError("unexpected call kind: " + call.getKind());
    }
  }

  private static void setBasicCallInfo(DataAllCallSituation.Builder builder, CallBasicNode call) {
    SoyType type = call.getCalleeExpr().getType();
    boolean isModifiable = type instanceof TemplateType && ((TemplateType) type).isModifiable();
    builder.setDelCall(isModifiable);
    builder.setTemplateName(getTemplateName(call, isModifiable, type));
  }

  private static String getTemplateName(CallBasicNode call, boolean isModifiable, SoyType type) {
    return (isModifiable && !((TemplateType) type).getLegacyDeltemplateNamespace().isEmpty())
            ? ((TemplateType) type).getLegacyDeltemplateNamespace()
            : call.isStaticCall() ? call.getCalleeName() : "$error";
  }

  public abstract SoyFileKind getSoyFileKind();

  /**
   * The source location of the template. For non {@code SOURCE} templates this will merely refer to
   * the file path, line and column information isn't recorded.
   */
  public abstract SourceLocation getSourceLocation();

  @Nullable
  public abstract HtmlElementMetadataP getHtmlElement();

  @Nullable
  public abstract SoyElementMetadataP getSoyElement();

  public abstract String getTemplateName();

  /** Guaranteed to be non-null for deltemplates or mod templates, null otherwise. */
  @Nullable
  public abstract String getDelTemplateName();

  /**
   * Guaranteed to be non-null for deltemplates or mod templates (possibly empty string), null
   * otherwise.
   */
  @Nullable
  public abstract String getDelTemplateVariant();

  public abstract TemplateType getTemplateType();

  public abstract Visibility getVisibility();

  @Nullable
  public abstract String getModName();

  public abstract boolean getComponent();

  public abstract Builder toBuilder();

  /** Builder for {@link TemplateMetadata} */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setSoyFileKind(SoyFileKind location);

    public abstract Builder setSourceLocation(SourceLocation location);

    public abstract Builder setHtmlElement(HtmlElementMetadataP isHtml);

    public abstract Builder setSoyElement(SoyElementMetadataP isSoyEl);

    public abstract Builder setTemplateName(String templateName);

    public abstract Builder setDelTemplateName(String delTemplateName);

    public abstract Builder setDelTemplateVariant(String delTemplateVariant);

    public abstract Builder setTemplateType(TemplateType templateType);

    public abstract Builder setModName(@Nullable String modName);

    public abstract Builder setVisibility(Visibility visibility);

    public abstract Builder setComponent(boolean isComponent);

    public final TemplateMetadata build() {
      TemplateMetadata built = autobuild();
      validateDelTemplate(built);
      return built;
    }

    private void validateDelTemplate(TemplateMetadata built) {
      if (built.getTemplateType().getTemplateKind() == TemplateType.TemplateKind.DELTEMPLATE
          || built.getTemplateType().isModifiable()
          || built.getTemplateType().isModifying()) {
        checkState(built.getDelTemplateName() != null, "Deltemplates must have a deltemplateName");
        checkState(built.getDelTemplateVariant() != null, "Deltemplates must have a variant");
      } else {
        checkState(built.getDelTemplateVariant() == null, "non-Deltemplates must not have a variant");
        checkState(built.getDelTemplateName() == null, "non-Deltemplates must not have a deltemplateName");
      }
    }

    abstract TemplateMetadata autobuild();
  }

  private static TemplateType.TemplateKind convertKind(SoyNode.Kind kind) {
    switch (kind) {
      case TEMPLATE_BASIC_NODE:
        return TemplateType.TemplateKind.BASIC;
      case TEMPLATE_DELEGATE_NODE:
        return TemplateType.TemplateKind.DELTEMPLATE;
      case TEMPLATE_ELEMENT_NODE:
        return TemplateType.TemplateKind.ELEMENT;
      default:
        throw new AssertionError("unexpected template kind: " + kind);
    }
  }
}
