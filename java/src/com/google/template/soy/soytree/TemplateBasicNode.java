
package com.google.template.soy.soytree;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.soytree.defn.TemplateHeaderVarDefn;
import com.google.template.soy.types.NullType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.SoyTypes;
import com.google.template.soy.types.StringType;
import com.google.template.soy.types.TemplateType;

import java.util.Optional;
import javax.annotation.Nullable;

public final class TemplateBasicNode extends TemplateNode {

    public static final SoyErrorKind INVALID_USEVARIANTTYPE =
            SoyErrorKind.of(
                    "Invalid type name \"{0}\" for attribute \"usevarianttype\". Must be \"number\", "
                            + "\"string\", or a proto enum.");

    private final boolean modifiable;
    private final CommandTagAttribute legacyDeltemplateNamespaceAttr;
    private final CommandTagAttribute useVariantTypeAttr;

    private String variantString = null;
    private SoyType useVariantType = null;

    TemplateBasicNode(
            TemplateBasicNodeBuilder nodeBuilder,
            SoyFileHeaderInfo soyFileHeaderInfo,
            Visibility visibility,
            boolean modifiable,
            @Nullable CommandTagAttribute legacyDeltemplateNamespaceAttr,
            @Nullable CommandTagAttribute useVariantTypeAttr,
            @Nullable ImmutableList<TemplateHeaderVarDefn> params) {
        super(nodeBuilder, "template", soyFileHeaderInfo, visibility, params);
        this.modifiable = modifiable;
        this.legacyDeltemplateNamespaceAttr = legacyDeltemplateNamespaceAttr;
        this.useVariantTypeAttr = useVariantTypeAttr;
    }

    private TemplateBasicNode(TemplateBasicNode orig, CopyState copyState) {
        super(orig, copyState);
        this.modifiable = orig.modifiable;
        this.legacyDeltemplateNamespaceAttr =
                copyState.copyNullable(orig.legacyDeltemplateNamespaceAttr);
        this.useVariantTypeAttr = copyState.copyNullable(orig.useVariantTypeAttr);
        this.useVariantType = orig.useVariantType;
    }

    @Override
    public String getTemplateNameForUserMsgs() {
        return getTemplateName();
    }

    @Override
    public Kind getKind() {
        return Kind.TEMPLATE_BASIC_NODE;
    }

    @Override
    public TemplateBasicNode copy(CopyState copyState) {
        return new TemplateBasicNode(this, copyState);
    }

    public boolean isModifiable() {
        return modifiable;
    }

    private Optional<CommandTagAttribute> getCommandTagAttribute(String name) {
        return getAttributes().stream().filter(a -> name.equals(a.getName().identifier())).findFirst();
    }

    private Optional<CommandTagAttribute> getCommandTagAttributeExpr(String name) {
        return getCommandTagAttribute(name).filter(CommandTagAttribute::hasExprValue);
    }

    @Nullable
    public ExprRootNode getModifiesExpr() {
        return getCommandTagAttributeExpr("modifies").map(a -> a.valueAsExprList().get(0)).orElse(null);
    }

    public String getLegacyDeltemplateNamespace() {
        return Optional.ofNullable(legacyDeltemplateNamespaceAttr).map(CommandTagAttribute::getValue).orElse("");
    }

    @Nullable
    public ExprRootNode getVariantExpr() {
        return getCommandTagAttributeExpr("variant").map(a -> a.valueAsExprList().get(0)).orElse(null);
    }

    private static boolean isValidVariantType(SoyType type) {
        return type.equals(SoyTypes.NUMBER_TYPE)
                || type.equals(StringType.getInstance())
                || type.getKind().equals(SoyType.Kind.PROTO_ENUM);
    }

    public void resolveUseVariantType(SoyTypeRegistry registry, ErrorReporter errorReporter) {
        Preconditions.checkState(useVariantType == null);
        if (useVariantTypeAttr == null) {
            useVariantType = NullType.getInstance();
            return;
        }
        SoyType resolvedType = registry.getType(useVariantTypeAttr.getValue());
        if (resolvedType == null || !isValidVariantType(resolvedType)) {
            errorReporter.report(
                    getCommandTagAttribute("usevarianttype")
                            .map(CommandTagAttribute::getSourceLocation)
                            .orElse(getSourceLocation()),
                    INVALID_USEVARIANTTYPE,
                    useVariantTypeAttr.getValue());
            useVariantType = NullType.getInstance();
        } else {
            useVariantType = resolvedType;
        }
    }

    @Nullable
    public CommandTagAttribute getUseVariantTypeAttribute() {
        return useVariantTypeAttr;
    }

    public SoyType getUseVariantType() {
        Preconditions.checkNotNull(
                useVariantType,
                "if usevarianttype is set, resolveUseVariantType() needs to be called to resolve the type"
                        + " before getUseVariantType() is used");
        return useVariantType;
    }

    public String getDelTemplateVariant() {
        if (getVariantExpr() == null) {
            variantString = "";
            return variantString;
        }
        if (variantString != null) {
            return variantString;
        }
        return resolveVariantExpression();
    }

    private String resolveVariantExpression() {
        variantString = TemplateNode.variantExprToString(getVariantExpr().getRoot());
        return variantString;
    }

    @Nullable
    public String moddedSoyNamespace() {
        if (getModifiesExpr() != null
                && getModName() != null
                && getModifiesExpr().getRoot() instanceof TemplateLiteralNode) {
            TemplateLiteralNode templateLiteralNode = (TemplateLiteralNode) getModifiesExpr().getRoot();
            SoyType nodeType = templateLiteralNode.getType();
            if ((nodeType instanceof TemplateType)
                    && ((TemplateType) nodeType).getLegacyDeltemplateNamespace().isEmpty()) {
                return templateLiteralNode
                        .getResolvedName()
                        .substring(0, templateLiteralNode.getResolvedName().lastIndexOf("."));
            }
        }
        return null;
    }

    @Nullable
    public String deltemplateAnnotationName() {
        if (isModifiable()) {
            return !getLegacyDeltemplateNamespace().isEmpty() ? getLegacyDeltemplateNamespace() : null;
        }
        if (getModifiesExpr() != null && getModifiesExpr().getRoot() instanceof TemplateLiteralNode) {
            TemplateLiteralNode templateLiteralNode = (TemplateLiteralNode) getModifiesExpr().getRoot();
            SoyType nodeType = templateLiteralNode.getType();
            if (nodeType instanceof TemplateType) {
                String legacyNamespace = ((TemplateType) nodeType).getLegacyDeltemplateNamespace();
                return !legacyNamespace.isEmpty() ? legacyNamespace : null;
            }
        }
        return null;
    }
}
