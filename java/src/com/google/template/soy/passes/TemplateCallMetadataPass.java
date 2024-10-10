
package com.google.template.soy.passes;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Lists.newArrayList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.CallableExpr.ParamsStyle;
import com.google.template.soy.exprtree.ExprNode.Kind;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.MethodCallNode;
import com.google.template.soy.exprtree.RecordLiteralNode;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamNode;
import com.google.template.soy.soytree.CallParamValueNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.LetValueNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.LocalVarNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateBasicNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.defn.LocalVar;
import com.google.template.soy.templatecall.TemplateCallMetadata;
import com.google.template.soy.templatecall.TemplateCallMetadata.TemplateCall;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.TemplateImportType;
import com.google.template.soy.types.TemplateType;
import java.util.List;
import java.util.Optional;

/** Provides a serializable proto containing descriptions of template calls. */
@RunAfter({
  // Required to use getCalleeName()
  ResolveTemplateNamesPass.class,
  // Required to use allowedToInvokeAsFunction() to identify short form template calls
  ResolveExpressionTypesPass.class,
})
final class TemplateCallMetadataPass implements CompilerFileSetPass {

  private ErrorReporter errorReporter;

  TemplateCallMetadataPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator nodeIdGen) {
    if (errorReporter.hasErrors()) {
      return Result.CONTINUE;
    }
    for (SoyFileNode file : sourceFiles) {
      for (TemplateNode template : file.getTemplates()) {
        List<TemplateCallMetadata.TemplateCall> shortFormCalls = parseShortFormCalls(template);
        template.addTemplateCallMetadata(createTemplateCallMetadata(template, shortFormCalls));
      }
    }
    return Result.CONTINUE;
  }

  private List<TemplateCallMetadata.TemplateCall> parseShortFormCalls(TemplateNode template) {
    return SoyTreeUtils.getAllNodesOfType(template, PrintNode.class).stream()
        .map(TemplateCallMetadataPass::parseShortFormTemplateCallAndElementComposition)
        .flatMap(Streams::stream)
        .collect(toImmutableList());
  }

  private TemplateCallMetadata.Template createTemplateCallMetadata(TemplateNode template, 
      List<TemplateCallMetadata.TemplateCall> shortFormCalls) {
    return TemplateCallMetadata.Template.newBuilder()
        .setName(getTemplateName(template))
        .setModname(nullToEmpty(template.getModName()))
        .addAllCalls(
            SoyTreeUtils.getAllNodesOfType(template, CallNode.class).stream()
                .map(TemplateCallMetadataPass::calculateTemplateCall)
                .collect(toImmutableList()))
        .addAllCalls(shortFormCalls)
        .build();
  }

  private String getTemplateName(TemplateNode template) {
    if (!(template instanceof TemplateBasicNode)) {
      return template.getTemplateName();
    }
    TemplateBasicNode basicNode = (TemplateBasicNode) template;
    if (basicNode.getModifiesExpr() != null
        && basicNode.getModifiesExpr().getRoot() instanceof TemplateLiteralNode) {
      TemplateLiteralNode literal = (TemplateLiteralNode) basicNode.getModifiesExpr().getRoot();
      return literal.getResolvedName();
    }
    return template.getTemplateName();
  }

  private static Optional<TemplateCallMetadata.TemplateCall>
      parseShortFormTemplateCallAndElementComposition(PrintNode printNode) {
    if (printNode.getExpr().getRoot() instanceof FunctionNode) {
      return parseFunctionNode((FunctionNode) printNode.getExpr().getRoot());
    }
    return resolveTemplateReference(printNode.getExpr().getRoot());
  }

  private static Optional<TemplateCallMetadata.TemplateCall> parseFunctionNode(FunctionNode fnNode) {
    if (!fnNode.allowedToInvokeAsFunction() || fnNode.getParamsStyle() == ParamsStyle.POSITIONAL) {
      return Optional.empty();
    }
    VarRefNode fnNameExpr = (VarRefNode) fnNode.getNameExpr();
    SoyType possibleTemplateImportType = fnNameExpr.getDefnDecl().type();
    if (!isTemplateType(possibleTemplateImportType)) {
      return Optional.empty();
    }
    return buildTemplateCall(fnNameExpr, fnNode, possibleTemplateImportType);
  }

  private static boolean isTemplateType(SoyType type) {
    SoyType.Kind kind = type.getKind();
    return kind == SoyType.Kind.TEMPLATE_TYPE || kind == SoyType.Kind.TEMPLATE;
  }

  private static Optional<TemplateCallMetadata.TemplateCall> buildTemplateCall(VarRefNode fnNameExpr, 
      FunctionNode fnNode, SoyType possibleTemplateImportType) {
    TemplateCallMetadata.TemplateCall.Builder templateCall = TemplateCallMetadata.TemplateCall.newBuilder()
        .addAllParamArgs(getFunctionParams(fnNode.getParamNames(), fnNode.getParams()));
    if (possibleTemplateImportType.getKind() == SoyType.Kind.TEMPLATE) {
      return resolveVarRefTemplateCall(fnNameExpr, templateCall);
    }
    return Optional.of(templateCall
        .setDestTemplateName(((TemplateImportType) possibleTemplateImportType).getName())
        .build());
  }

  private static TemplateCallMetadata.TemplateCall calculateTemplateCall(CallNode templateCallNode) {
    ImmutableList<TemplateCallMetadata.ParamArg> callParamArgs =
        templateCallNode.getChildren().stream()
            .map(TemplateCallMetadataPass::createParamArg)
            .collect(toImmutableList());

    if (templateCallNode.getKind() == SoyNode.Kind.CALL_BASIC_NODE) {
      return handleBindStatement(templateCallNode, callParamArgs);
    }
    return buildTemplateCallMetadata(templateCallNode, callParamArgs);
  }

  private static TemplateCallMetadata.TemplateCall handleBindStatement(CallNode templateCallNode,
      ImmutableList<TemplateCallMetadata.ParamArg> callParamArgs) {
    ExprNode exprNode = ((CallBasicNode) templateCallNode).getCalleeExpr().getRoot();
    if (isBindStatement(exprNode) || exprNode.getKind() == Kind.VAR_REF_NODE) {
      Optional<TemplateCallMetadata.TemplateCall> boundTemplateCall =
          resolveTemplateReference(exprNode);
      if (boundTemplateCall.isPresent()) {
        return TemplateCallMetadata.TemplateCall.newBuilder(boundTemplateCall.get())
            .addAllParamArgs(callParamArgs)
            .build();
      }
    }
    return buildTemplateCallMetadata(templateCallNode, callParamArgs);
  }

  private static TemplateCallMetadata.TemplateCall buildTemplateCallMetadata(CallNode templateCallNode, 
      ImmutableList<TemplateCallMetadata.ParamArg> callParamArgs) {
    TemplateCallMetadata.TemplateCall.Builder templateCallMetaData =
        TemplateCallMetadata.TemplateCall.newBuilder()
            .setDestTemplateName(getDestTemplateName(templateCallNode))
            .setIsDelcall(templateCallNode.getKind() == SoyNode.Kind.CALL_DELEGATE_NODE)
            .setIsModifiableCall(isModifiableCall(templateCallNode))
            .addAllParamArgs(callParamArgs);
    if (templateCallNode.isPassingAllData()) {
      templateCallMetaData.setIsPassingAllData(true);
    } else if (templateCallNode.getDataExpr() != null) {
      templateCallMetaData.setDataArg(
          resolveLocalVarRefToParamRef(
              templateCallNode.getDataExpr().getRoot(),
              TemplateCallMetadata.VarRefInfo.newBuilder()));
    }
    return templateCallMetaData.build();
  }

  private static boolean isModifiableCall(CallNode call) {
    if (!(call instanceof CallBasicNode)) {
      return false;
    }
    CallBasicNode callBasicNode = (CallBasicNode) call;
    return callBasicNode.getCalleeExpr().getRoot().getType() instanceof TemplateType
        && ((TemplateType) callBasicNode.getCalleeExpr().getRoot().getType()).isModifiable();
  }

  private static Optional<TemplateCallMetadata.TemplateCall> resolveTemplateReference(
      ExprNode exprNode) {
    if (exprNode instanceof TemplateLiteralNode) {
      return resolveTemplateLiteral((TemplateLiteralNode) exprNode);
    } else if (exprNode.getKind() == Kind.VAR_REF_NODE) {
      return resolveVarRefTemplateCall((VarRefNode) exprNode, TemplateCall.newBuilder());
    } else if (!isBindStatement(exprNode)) {
      return Optional.empty();
    }
    return resolveBindTemplateReference((MethodCallNode) exprNode);
  }

  private static Optional<TemplateCallMetadata.TemplateCall> resolveTemplateLiteral(TemplateLiteralNode literal) {
    String destTemplateName = literal.getResolvedName();
    return Optional.of(TemplateCallMetadata.TemplateCall.newBuilder()
        .setDestTemplateName(destTemplateName)
        .build());
  }

  private static Optional<TemplateCallMetadata.TemplateCall> resolveBindTemplateReference(MethodCallNode bind) {
    ExprNode bindCaller = bind.getChild(0);
    TemplateCall.Builder templateCall = TemplateCall.newBuilder()
        .addAllParamArgs(getBoundTemplateParams(bind))
        .setIsDelcall(false);
    if (bindCaller.getKind() == Kind.VAR_REF_NODE) {
      return resolveVarRefTemplateCall((VarRefNode) bindCaller, templateCall);
    }
    String destTemplateName = ((TemplateLiteralNode) bindCaller).getResolvedName();
    return Optional.of(templateCall.setDestTemplateName(destTemplateName).build());
  }

  private static Optional<TemplateCallMetadata.TemplateCall> resolveVarRefTemplateCall(
      VarRefNode varRefNode, TemplateCallMetadata.TemplateCall.Builder templateCallInfo) {
    if (templateCallInfo.getDestTemplateName().isEmpty()) {
      templateCallInfo.setDestTemplateName(varRefNode.getName());
    }
    VarDefn varDefn = varRefNode.getDefnDecl();

    if (varDefn.kind() == VarDefn.Kind.PARAM
        && (varDefn.type().getKind() == SoyType.Kind.TEMPLATE_TYPE
            || varDefn.type().getKind() == SoyType.Kind.TEMPLATE)) {
      return Optional.of(templateCallInfo.setSourceParam(varDefn.name()).build());
    } else if (varDefn.kind() == VarDefn.Kind.LOCAL_VAR) {
      return handleLocalVarRef((LocalVar) varDefn, templateCallInfo);
    }
    return Optional.empty();
  }

  private static Optional<TemplateCallMetadata.TemplateCall> handleLocalVarRef(LocalVar varDefn, 
      TemplateCallMetadata.TemplateCall.Builder templateCallInfo) {
    LocalVarNode localVarDefn = varDefn.declaringNode();
    if (localVarDefn instanceof LetValueNode) {
      return handleLetValueNode((LetValueNode) localVarDefn, templateCallInfo);
    }
    return Optional.empty();
  }

  private static Optional<TemplateCallMetadata.TemplateCall> handleLetValueNode(LetValueNode letNode, 
      TemplateCallMetadata.TemplateCall.Builder templateCallInfo) {
    ExprNode letExpression = letNode.getExpr().getRoot();
    if (letExpression.getKind() == Kind.TEMPLATE_LITERAL_NODE) {
      return Optional.of(templateCallInfo
          .setSourceTemplate(((TemplateLiteralNode) letExpression).getResolvedName())
          .build());
    } else if (isBindStatement(letExpression)) {
      templateCallInfo.addAllParamArgs(getBoundTemplateParams((MethodCallNode) letExpression));
      return resolveBindCaller(letExpression, templateCallInfo);
    }
    return Optional.empty();
  }

  private static Optional<TemplateCallMetadata.TemplateCall> resolveBindCaller(ExprNode letExpression, 
      TemplateCallMetadata.TemplateCall.Builder templateCallInfo) {
    ExprNode bindCaller = ((MethodCallNode) letExpression).getChild(0);
    if (bindCaller.getKind() == Kind.TEMPLATE_LITERAL_NODE) {
      return Optional.of(templateCallInfo
          .setSourceTemplate(((TemplateLiteralNode) bindCaller).getResolvedName())
          .build());
    } else if (bindCaller.getKind() == Kind.VAR_REF_NODE) {
      return resolveVarRefTemplateCall((VarRefNode) bindCaller, templateCallInfo);
    }
    return Optional.empty();
  }

  private static TemplateCallMetadata.ParamArg createParamArg(CallParamNode callParamNode) {
    // only shorthand param ref syntax is supported
    if (!(callParamNode instanceof CallParamValueNode)) {
      return TemplateCallMetadata.ParamArg.newBuilder()
          .setKey(callParamNode.getKey().identifier())
          .build();
    }
    ExprNode possibleParamRefExpr = ((CallParamValueNode) callParamNode).getExpr().getRoot();
    return resolveParam(callParamNode.getKey().identifier(), possibleParamRefExpr);
  }

  private static TemplateCallMetadata.ParamArg resolveParam(String key, ExprNode valueExpr) {
    TemplateCallMetadata.ParamArg.Builder paramArg =
        TemplateCallMetadata.ParamArg.newBuilder().setKey(key);
    TemplateCallMetadata.VarRefInfo varRefInfo =
        resolveLocalVarRefToParamRef(valueExpr, TemplateCallMetadata.VarRefInfo.newBuilder());
    if (varRefInfo.getHeaderParam().isEmpty()) {
      Optional<TemplateCallMetadata.TemplateCall> boundTemplate =
          resolveTemplateReference(valueExpr);
      if (boundTemplate.isPresent()) {
        return paramArg.setBoundTemplate(boundTemplate.get()).build();
      }
    }
    return paramArg.setVarRef(varRefInfo).build();
  }

  private static ImmutableList<TemplateCallMetadata.ParamArg> getFunctionParams(
      List<Identifier> keys, List<ExprNode> values) {
    List<TemplateCallMetadata.ParamArg> callParamArgs = newArrayList();
    if (keys.size() != values.size()) {
      throw new IllegalArgumentException("Both keys and values must be the same size");
    }
    for (int i = 0; i < keys.size(); i++) {
      callParamArgs.add(resolveParam(keys.get(i).identifier(), values.get(i)));
    }
    return ImmutableList.copyOf(callParamArgs);
  }

  private static ImmutableList<TemplateCallMetadata.ParamArg> getBoundTemplateParams(MethodCallNode bind) {
    ExprNode possibleBindParams = bind.getParams().get(0);
    if (possibleBindParams.getKind() != Kind.RECORD_LITERAL_NODE) {
      return ImmutableList.of();
    }
    RecordLiteralNode recordLiteralNode = (RecordLiteralNode) possibleBindParams;
    List<ExprNode> bindExprs = recordLiteralNode.getChildren();
    return getFunctionParams(recordLiteralNode.getKeys(), bindExprs);
  }

  private static TemplateCallMetadata.VarRefInfo resolveLocalVarRefToParamRef(
      ExprNode varExpr, TemplateCallMetadata.VarRefInfo.Builder varRefInfo) {
    if (varExpr.getKind() == Kind.VAR_REF_NODE) {
      return handleVarRefNode((VarRefNode) varExpr, varRefInfo);
    } else if (varExpr.getKind() == Kind.ITEM_ACCESS_NODE) {
      varRefInfo.setUsesListIndex(true);
      return resolveLocalVarRefToParamRef(
          ((ItemAccessNode) varExpr).getBaseExprChild(), varRefInfo);
    } else if (varExpr.getKind() == Kind.FIELD_ACCESS_NODE) {
      FieldAccessNode fieldAccessNode = ((FieldAccessNode) varExpr);
      varRefInfo.setDataAccessAlias(fieldAccessNode.getFieldName());
      return resolveLocalVarRefToParamRef(fieldAccessNode.getBaseExprChild(), varRefInfo);
    } else if (varExpr.getKind() == Kind.FUNCTION_NODE) {
      if ("checkNotNull".equals(((FunctionNode) varExpr).getFunctionName())) {
        return resolveLocalVarRefToParamRef(((FunctionNode) varExpr).getChild(0), varRefInfo);
      }
    }

    return varRefInfo.build();
  }

  private static TemplateCallMetadata.VarRefInfo handleVarRefNode(VarRefNode varRefNode, 
      TemplateCallMetadata.VarRefInfo.Builder varRefInfo) {
    VarDefn possibleParamRefDef = varRefNode.getDefnDecl();
    if (possibleParamRefDef.kind() == VarDefn.Kind.PARAM) {
      varRefInfo.setHeaderParam(possibleParamRefDef.name());
      return varRefInfo.build();
    } else if (possibleParamRefDef.kind() == VarDefn.Kind.LOCAL_VAR) {
      LocalVarNode varRefNodeLocal = ((LocalVar) possibleParamRefDef).declaringNode();
      if (varRefNodeLocal.getKind() == SoyNode.Kind.FOR_NONEMPTY_NODE) {
        varRefInfo.setUsesListIndex(true);
        return resolveLocalVarRefToParamRef(
            ((ForNonemptyNode) varRefNodeLocal).getExpr().getRoot(), varRefInfo);
      } else if (varRefNodeLocal instanceof LetValueNode) {
        return resolveLocalVarRefToParamRef(
            ((LetValueNode) varRefNodeLocal).getExpr().getRoot(), varRefInfo);
      }
    }
    return varRefInfo.build();
  }

  private static boolean isBindStatement(ExprNode exprNode) {
    return exprNode.getKind() == Kind.METHOD_CALL_NODE
        && ((MethodCallNode) exprNode).getMethodName().identifier().equals("bind");
  }

  private static String getDestTemplateName(CallNode callNode) {
    switch (callNode.getKind()) {
      case CALL_BASIC_NODE:
        CallBasicNode basicNode = ((CallBasicNode) callNode);
        return basicNode.isStaticCall()
            ? basicNode.getCalleeName()
            : basicNode.getCalleeExpr().toSourceString();
      case CALL_DELEGATE_NODE:
        return ((CallDelegateNode) callNode).getDelCalleeName();
      default:
        throw new IllegalStateException("Unknown CallNode kind");
    }
  }
}
