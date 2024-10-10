
/*
 * Copyright 2017 Google Inc.
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
package com.google.template.soy.passes;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.logging.LoggingFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.HtmlCloseTagNode;
import com.google.template.soy.soytree.HtmlOpenTagNode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ExprHolderNode;
import com.google.template.soy.soytree.SoyNode.MsgBlockNode;
import com.google.template.soy.soytree.SoyNode.MsgSubstUnitNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.VeLogNode;
import com.google.template.soy.types.BoolType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyType.Kind;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.SoyTypes;
import com.google.template.soy.types.VeType;
import java.util.List;
import java.util.Objects;

/**
 * Validates uses of the {@code velog} command and {@code ve_data} expression.
 *
 * <p>Must run after:
 *
 * <ul>
 *   <li>VeRewritePass since that rewrites VE syntactic sugar
 *   <li>ResolveTypesPass since we rely on type resolution data
 *   <li>ResolveFunctions pass since we need to validate the use of {@link LoggingFunction}
 *       invocations
 *   <li>VeLogRewritePass since that rewrites more VE syntactic sugar
 * </ul>
 */
final class VeLogValidationPass implements CompilerFileSetPass {
  private static final SoyErrorKind UNEXPECTED_DATA = SoyErrorKind.of(
      "Unexpected data argument. The VE is type ''{0}'' which means there cannot be any data. "
          + "The data is typed ''{1}'' and must match with the VE.");
  private static final SoyErrorKind WRONG_TYPE = SoyErrorKind.of("Expected an expression of type ''{0}'', instead got ''{1}''.");
  private static final SoyErrorKind LOGONLY_DISALLOWED_IN_MSG = SoyErrorKind.of(
      "The logonly attribute may not be set on '''{velog}''' nodes in '''{msg}''' context. "
          + "Consider moving the logonly content into another template and calling it, or "
          + "refactoring your '''{msg}''' into multiple distinct messages.");
  private static final SoyErrorKind REQUIRE_STRICTHTML = SoyErrorKind.of(
      "The '{'velog ...'}' command can only be used in templates with stricthtml=\"true\".");

  private static final SoyErrorKind INVALID_LOGGING_FUNCTION_LOCATION = SoyErrorKind.of(
      "The logging function ''{0}'' can only be evaluated in a print command that is the "
          + "only direct child of an html attribute value.{1}",
      SoyErrorKind.StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind NO_PRINT_DIRECTIVES = SoyErrorKind.of(
      "The logging function ''{0}'' can only be evaluated in a print command with no print "
          + "directives.");

  private static final SoyErrorKind UNKNOWN_PROTO = SoyErrorKind.of("Unknown proto type ''{0}'' configured for use with this VE.");
  private static final SoyErrorKind BAD_DATA_TYPE = SoyErrorKind.of(
      "Illegal VE metadata type ''{0}'' for this VE. The metadata must be a proto.");
  private static final SoyErrorKind INVALID_VE = SoyErrorKind.of(
      "The velog command requires a VE identifier, an expression of the ''ve'' type or an "
          + "expression of the ''ve_data'' type. Found an expression of type ''{0}''.");
  private static final SoyErrorKind VE_UNION_WITH_DATA = SoyErrorKind.of(
      "It is illegal to set the data parameter if the ve type is a union (''{0}'').");

  private static final SoyErrorKind LOG_WITHIN_MESSAGE_REQUIRES_ELEMENT = SoyErrorKind.of("'{velog'} within '{msg'} must directly wrap an HTML element.");

  private final ErrorReporter reporter;
  private final SoyTypeRegistry typeRegistry;

  VeLogValidationPass(ErrorReporter reporter, SoyTypeRegistry typeRegistry) {
    this.reporter = reporter;
    this.typeRegistry = typeRegistry;
  }

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator) {
    for (SoyFileNode file : sourceFiles) {
      for (TemplateNode template : file.getTemplates()) {
        run(template);
      }
    }
    return Result.CONTINUE;
  }

  private void run(TemplateNode template) {
    SoyTreeUtils.allFunctionInvocations(template, BuiltinFunction.VE_DATA)
        .forEach(this::validateVeDataFunctionNode);
    for (VeLogNode node : SoyTreeUtils.getAllNodesOfType(template, VeLogNode.class)) {
      if (template.isStrictHtml()) {
        validateVelogElementStructure(node);
        validateVeLogNode(node);
      } else {
        reporter.report(node.getVeDataExpression().getSourceLocation(), REQUIRE_STRICTHTML);
      }
    }
    validateLoggingFunctions(template);
  }

  private void validateLoggingFunctions(TemplateNode template) {
    SoyTreeUtils.visitExprNodesWithHolder(
        template,
        FunctionNode.class,
        (holderNode, function) -> {
          if (function.isResolved() && function.getSoyFunction() instanceof LoggingFunction) {
            validateLoggingFunction(holderNode, function);
          }
        });
  }

  private void validateLoggingFunction(ExprHolderNode holderNode, FunctionNode function) {
    if (function.getParent().getKind() != ExprNode.Kind.EXPR_ROOT_NODE) {
      reportInvalidLoggingFunctionLocation(function, "It is part of complex expression.");
      return;
    }
    if (holderNode.getKind() != SoyNode.Kind.PRINT_NODE) {
      reportInvalidLoggingFunctionLocation(function, "It isn't in a print node.");
      return;
    }
    validatePrintNode(holderNode, function);
  }

  private void reportInvalidLoggingFunctionLocation(FunctionNode function, String message) {
    reporter.report(function.getSourceLocation(), INVALID_LOGGING_FUNCTION_LOCATION, function.getStaticFunctionName(), message);
  }

  private void validatePrintNode(ExprHolderNode holderNode, FunctionNode function) {
    PrintNode printNode = (PrintNode) holderNode;
    if (printNode.numChildren() != 0) {
      reporter.report(printNode.getChild(0).getSourceLocation(), NO_PRINT_DIRECTIVES, function.getStaticFunctionName());
    }
    if (holderNode.getParent().getKind() != SoyNode.Kind.HTML_ATTRIBUTE_VALUE_NODE) {
      reportInvalidLoggingFunctionLocation(function, "It isn't the direct child of an attribute value.");
      return;
    }
    if (holderNode.getParent().numChildren() > 1) {
      reporter.report(function.getSourceLocation(), INVALID_LOGGING_FUNCTION_LOCATION, function.getStaticFunctionName(), "It has sibling nodes in the attribute value.");
    }
  }

  private void validateVelogElementStructure(VeLogNode node) {
    List<StandaloneNode> children = node.getChildren().stream()
        .filter(child -> !SoyElementPass.ALLOWED_CHILD_NODES.contains(child.getKind()))
        .collect(toImmutableList());

    if (shouldOutputSyntheticVelogNode(node, children)) {
      node.setNeedsSyntheticVelogNode(true);
      return;
    }

    HtmlOpenTagNode firstTag = node.getOpenTagNode();
    if (firstTag == null || !isValidFirstTag(firstTag)) {
      node.setNeedsSyntheticVelogNode(true);
      return;
    }

    HtmlCloseTagNode lastTag = node.getCloseTagNode();
    if (lastTag == null || !isSingleTopLevelElement(firstTag, lastTag)) {
      node.setNeedsSyntheticVelogNode(true);
    }
  }

  private boolean shouldOutputSyntheticVelogNode(VeLogNode node, List<StandaloneNode> children) {
    return (node.getNearestAncestor(MsgFallbackGroupNode.class) == null && children.size() == 1 && Iterables.getLast(children) instanceof CallBasicNode)
        || node.numChildren() == 0;
  }

  private boolean isValidFirstTag(HtmlOpenTagNode firstTag) {
    return firstTag.getTagName().isStatic() || firstTag.getTagName().isLegacyDynamicTagName() || !(firstTag.isSelfClosing() || firstTag.getTagName().isDefinitelyVoid());
  }

  private boolean isSingleTopLevelElement(HtmlOpenTagNode firstTag, HtmlCloseTagNode lastTag) {
    return lastTag.getTaggedPairs().size() == 1 && Objects.equals(lastTag.getTaggedPairs().get(0), firstTag);
  }

  /** Type checks the VE and logonly expressions. */
  private void validateVeLogNode(VeLogNode node) {
    if (node.getVeDataExpression().getRoot().getType().getKind() != Kind.VE_DATA) {
      reporter.report(node.getVeDataExpression().getSourceLocation(), INVALID_VE, node.getVeDataExpression().getRoot().getType());
    }
    if (node.needsSyntheticVelogNode() && isInMsgNode(node)) {
      reporter.report(node.getSourceLocation(), LOG_WITHIN_MESSAGE_REQUIRES_ELEMENT);
    }
    validateLogonlyExpression(node);
  }

  private void validateLogonlyExpression(VeLogNode node) {
    if (node.getLogonlyExpression() != null) {
      if (isInMsgNode(node)) {
        reporter.report(node.getLogonlyExpression().getSourceLocation(), LOGONLY_DISALLOWED_IN_MSG);
      }
      SoyType type = node.getLogonlyExpression().getType();
      if (type.getKind() != Kind.BOOL) {
        reporter.report(node.getLogonlyExpression().getSourceLocation(), WRONG_TYPE, BoolType.getInstance(), type);
      }
    }
  }

  private void validateVeDataFunctionNode(FunctionNode node) {
    if (node.numChildren() < 1 || node.numChildren() > 2) {
      return; // an error has already been reported
    }
    ExprNode veExpr = node.getChild(0);
    ExprNode dataExpr = node.getChild(1);

    if (veExpr.getType().getKind() == Kind.VE) {
      validateVeData(veExpr, dataExpr);
    } else if (SoyTypes.isKindOrUnionOfKind(veExpr.getType(), Kind.VE)) {
      if (dataExpr.getType().getKind() != Kind.NULL) {
        reporter.report(dataExpr.getSourceLocation(), VE_UNION_WITH_DATA, veExpr.getType());
      }
    } else {
      reporter.report(veExpr.getSourceLocation(), WRONG_TYPE, "ve", veExpr.getType());
    }
  }

  private void validateVeData(ExprNode veExpr, ExprNode dataExpr) {
    if (dataExpr.getType().getKind() != Kind.NULL) {
      VeType veType = (VeType) veExpr.getType();
      SoyType dataType = dataExpr.getType();
      if (!veType.getDataType().isPresent()) {
        reporter.report(dataExpr.getSourceLocation(), UNEXPECTED_DATA, veType, dataType);
      } else {
        validateProtoType(veExpr, veType, dataExpr, dataType);
      }
    }
  }

  private void validateProtoType(ExprNode veExpr, VeType veType, ExprNode dataExpr, SoyType dataType) {
    SoyType veDataType = typeRegistry.getProtoRegistry().getProtoType(veType.getDataType().get());
    if (veDataType == null) {
      reporter.report(veExpr.getSourceLocation(), UNKNOWN_PROTO, veType.getDataType().get());
    } else if (veDataType.getKind() != Kind.PROTO) {
      reporter.report(veExpr.getSourceLocation(), BAD_DATA_TYPE, veDataType);
    } else if (!dataType.equals(veDataType)) {
      reporter.report(dataExpr.getSourceLocation(), WRONG_TYPE, veType.getDataType().get(), dataType);
    }
  }

  private static boolean isInMsgNode(SoyNode node) {
    if (node instanceof MsgNode) {
      return true;
    }
    ParentSoyNode<?> parent = node.getParent();
    return (parent instanceof MsgBlockNode || parent instanceof MsgSubstUnitNode) && isInMsgNode(parent);
  }
}
