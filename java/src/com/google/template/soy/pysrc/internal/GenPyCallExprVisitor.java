
/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.pysrc.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.pysrc.internal.GenPyExprsVisitor.GenPyExprsVisitorFactory;
import com.google.template.soy.pysrc.restricted.PyExpr;
import com.google.template.soy.pysrc.restricted.PyExprUtils;
import com.google.template.soy.pysrc.restricted.PyFunctionExprBuilder;
import com.google.template.soy.pysrc.restricted.PyListExpr;
import com.google.template.soy.pysrc.restricted.PyStringExpr;
import com.google.template.soy.pysrc.restricted.SoyPySrcPrintDirective;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.AbstractReturningSoyNodeVisitor;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamNode;
import com.google.template.soy.soytree.CallParamValueNode;
import com.google.template.soy.soytree.ConstNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.Visibility;
import com.google.template.soy.soytree.defn.TemplateParam;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Functions for generating Python code for template calls and their parameters.
 */
final class GenPyCallExprVisitor extends AbstractReturningSoyNodeVisitor<PyExpr> {

  private final IsComputableAsPyExprVisitor isComputableAsPyExprVisitor;
  private final PythonValueFactoryImpl pluginValueFactory;
  private final GenPyExprsVisitorFactory genPyExprsVisitorFactory;

  private LocalVariableStack localVarStack;
  private ErrorReporter errorReporter;

  GenPyCallExprVisitor(
      IsComputableAsPyExprVisitor isComputableAsPyExprVisitor,
      PythonValueFactoryImpl pluginValueFactory,
      GenPyExprsVisitorFactory genPyExprsVisitorFactory) {
    this.isComputableAsPyExprVisitor = isComputableAsPyExprVisitor;
    this.pluginValueFactory = pluginValueFactory;
    this.genPyExprsVisitorFactory = genPyExprsVisitorFactory;
  }

  PyExpr exec(CallNode callNode, LocalVariableStack localVarStack, ErrorReporter errorReporter) {
    this.localVarStack = localVarStack;
    this.errorReporter = errorReporter;
    PyExpr callExpr = visit(callNode);
    this.localVarStack = null;
    this.errorReporter = null;
    return callExpr;
  }

  @Override
  protected PyExpr visitCallBasicNode(CallBasicNode node) {
    TranslateToPyExprVisitor translator = createTranslator(node);
    PyExpr calleeExpr = getCalleeExpr(node, translator);
    return createCallExpression(calleeExpr, node);
  }

  @Override
  protected PyExpr visitCallDelegateNode(CallDelegateNode node) {
    PyExpr variantPyExpr = getVariantPyExpr(node);
    String calleeExprText = new PyFunctionExprBuilder("runtime.get_delegate_fn")
        .addArg(node.getDelCalleeName())
        .addArg(variantPyExpr)
        .build();
    String callExprText = calleeExprText + "(" + genObjToPass(node) + ", ijData)";
    return escapeCall(callExprText, node.getEscapingDirectives());
  }

  private TranslateToPyExprVisitor createTranslator(CallNode node) {
    return new TranslateToPyExprVisitor(localVarStack, pluginValueFactory, node, errorReporter);
  }

  private PyExpr getCalleeExpr(CallBasicNode node, TranslateToPyExprVisitor translator) {
    return node.isStaticCall()
        ? translator.getCalleeExpr((TemplateLiteralNode) node.getCalleeExpr().getRoot())
        : translator.exec(node.getCalleeExpr());
  }

  private PyExpr createCallExpression(PyExpr calleeExpr, CallBasicNode node) {
    String calleeExprText = calleeExpr.getPrecedence() < Integer.MAX_VALUE
        ? "(" + calleeExpr.getText() + ")"
        : calleeExpr.getText();
    calleeExprText += "(" + genObjToPass(node) + ", ijData)";
    return escapeCall(calleeExprText, node.getEscapingDirectives());
  }

  private PyExpr getVariantPyExpr(CallDelegateNode node) {
    ExprRootNode variantSoyExpr = node.getDelCalleeVariantExpr();
    if (variantSoyExpr == null) {
      return new PyStringExpr("''");
    }
    TranslateToPyExprVisitor translator = createTranslator(node);
    return translator.exec(variantSoyExpr);
  }

  public String genObjToPass(CallNode callNode) {
    TranslateToPyExprVisitor translator = createTranslator(callNode);
    String dataToPass = determineDataToPass(callNode, translator);
    Map<PyExpr, PyExpr> additionalParams = collectAdditionalParams(callNode, translator);
    Map<PyExpr, PyExpr> defaultParams = collectDefaultParams(callNode, translator);
    return buildFinalDataPassExpression(callNode, dataToPass, additionalParams, defaultParams);
  }

  private String determineDataToPass(CallNode callNode, TranslateToPyExprVisitor translator) {
    if (callNode.isPassingAllData()) {
      return "data";
    } else if (callNode.isPassingData()) {
      return translator.exec(callNode.getDataExpr()).getText();
    } else {
      return "{}";
    }
  }

  private Map<PyExpr, PyExpr> collectAdditionalParams(CallNode callNode, TranslateToPyExprVisitor translator) {
    Map<PyExpr, PyExpr> additionalParams = new LinkedHashMap<>();
    for (CallParamNode child : callNode.getChildren()) {
      PyExpr key = new PyStringExpr("'" + child.getKey().identifier() + "'");
      additionalParams.put(key, generateValueForParam(child, translator, key));
    }
    return additionalParams;
  }

  private PyExpr generateValueForParam(CallParamNode child, TranslateToPyExprVisitor translator, PyExpr key) {
    if (child instanceof CallParamValueNode) {
      CallParamValueNode cpvn = (CallParamValueNode) child;
      return translator.exec(cpvn.getExpr());
    } else {
      return handleCallParamContentNode((CallParamContentNode) child, translator);
    }
  }

  private PyExpr handleCallParamContentNode(CallParamContentNode cpcn, TranslateToPyExprVisitor translator) {
    PyExpr valuePyExpr;
    if (isComputableAsPyExprVisitor.exec(cpcn)) {
      valuePyExpr = PyExprUtils.concatPyExprs(genPyExprsVisitorFactory.create(localVarStack, errorReporter).exec(cpcn));
    } else {
      String paramExpr = "param" + cpcn.getId();
      valuePyExpr = new PyListExpr(paramExpr, Integer.MAX_VALUE);
    }
    return InternalPyExprUtils.wrapAsSanitizedContent(cpcn.getContentKind(), valuePyExpr.toPyString());
  }

  private Map<PyExpr, PyExpr> collectDefaultParams(CallNode callNode, TranslateToPyExprVisitor translator) {
    Map<PyExpr, PyExpr> defaultParams = new LinkedHashMap<>();
    for (TemplateParam param : callNode.getNearestAncestor(TemplateNode.class).getParams()) {
      if (param.hasDefault()) {
        defaultParams.put(new PyStringExpr("'" + param.name() + "'"), translator.exec(param.defaultValue()));
      }
    }
    return defaultParams;
  }

  private String buildFinalDataPassExpression(CallNode callNode, String dataToPass, Map<PyExpr, PyExpr> additionalParams, Map<PyExpr, PyExpr> defaultParams) {
    PyExpr additionalParamsExpr = PyExprUtils.convertMapToPyExpr(additionalParams);
    if (callNode.isPassingData()) {
      if (callNode.numChildren() > 0) {
        dataToPass = "dict(" + dataToPass + ")";
        dataToPass = "runtime.merge_into_dict(" + dataToPass + ", " + additionalParamsExpr.getText() + ")";
      }
      if (!defaultParams.isEmpty()) {
        PyExpr defaultParamsExpr = PyExprUtils.convertMapToPyExpr(defaultParams);
        dataToPass = "runtime.merge_into_dict(" + defaultParamsExpr.getText() + ", " + dataToPass + ")";
      }
      return dataToPass;
    } else {
      return additionalParamsExpr.getText();
    }
  }

  private PyExpr escapeCall(String callExpr, ImmutableList<SoyPrintDirective> directives) {
    PyExpr escapedExpr = new PyExpr(callExpr, Integer.MAX_VALUE);
    if (directives.isEmpty()) {
      return escapedExpr;
    }
    for (SoyPrintDirective directive : directives) {
      Preconditions.checkState(directive instanceof SoyPySrcPrintDirective, "Autoescaping produced a bogus directive: %s", directive.getName());
      escapedExpr = ((SoyPySrcPrintDirective) directive).applyForPySrc(escapedExpr, ImmutableList.of());
    }
    return escapedExpr;
  }

  static String getLocalTemplateName(TemplateNode node) {
    String templateName = node.getPartialTemplateName();
    return node.getVisibility() == Visibility.PRIVATE ? "__" + templateName : templateName;
  }

  static String getLocalConstName(ConstNode node) {
    String functionName = node.getVar().name();
    return !node.isExported() ? "__" + functionName : functionName;
  }
}
