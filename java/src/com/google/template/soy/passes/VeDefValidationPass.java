
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

package com.google.template.soy.passes;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.logging.LoggingConfigValidator;
import com.google.template.soy.logging.LoggingConfigValidator.VisualElement;
import com.google.template.soy.passes.CompilerFileSetPass.Result;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.ConstNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.types.ProtoImportType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Validates the arguments to ve_def, and validates the VEs. */
@RunAfter({ResolveExpressionTypesPass.class})
final class VeDefValidationPass implements CompilerFileSetPass {

  private static final SoyErrorKind VE_DEF_OUTSIDE_CONST =
      SoyErrorKind.of("Visual elements defined with ve_def() must be assigned to a constant.");

  private static final SoyErrorKind BAD_VE_DEF_NAME =
      SoyErrorKind.of("The first argument to ve_def() must be an string literal.");

  private static final SoyErrorKind BAD_VE_DEF_ID =
      SoyErrorKind.of("The second argument to ve_def() must be an integer literal.");

  private static final SoyErrorKind BAD_VE_DEF_DATA_PROTO_TYPE =
      SoyErrorKind.of("The third argument to ve_def() must be proto type or 'null'.");

  private static final SoyErrorKind BAD_VE_DEF_METADATA =
      SoyErrorKind.of(
          "The fourth argument to ve_def() must be a proto init expression of "
              + "LoggableElementMetadata. All fields must be literals.");

  private final ErrorReporter errorReporter;
  private final ExprEquivalence exprEquivalence;
  private final CopyState copyState;

  VeDefValidationPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
    this.exprEquivalence = new ExprEquivalence();
    this.copyState = new CopyState();
  }

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator) {
    Set<ExprNode> vedefsInConstNodes = new HashSet<>();
    List<VisualElement> vedefs = new ArrayList<>();

    for (SoyFileNode file : sourceFiles) {
      collectVeDefsInConst(file, vedefsInConstNodes);
      validateVeDefs(file, vedefsInConstNodes, vedefs);
    }
    
    LoggingConfigValidator.validate(vedefs, errorReporter);
    return Result.CONTINUE;
  }

  private void collectVeDefsInConst(SoyFileNode file, Set<ExprNode> veDefsInConstNodes) {
    SoyTreeUtils.allNodesOfType(file, ConstNode.class)
        .forEach(c -> maybeAddVeDefFromConst(c, veDefsInConstNodes));
  }

  private void validateVeDefs(SoyFileNode file, Set<ExprNode> vedefsInConstNodes, List<VisualElement> vedefs) {
    SoyTreeUtils.allNodesOfType(file, FunctionNode.class)
        .filter(VeDefValidationPass::isVeDef)
        .forEach(func -> {
          if (!vedefsInConstNodes.contains(func)) {
            errorReporter.report(func.getSourceLocation(), VE_DEF_OUTSIDE_CONST);
          }
          buildVeDefAndValidate(func, vedefs);
        });
  }

  private void maybeAddVeDefFromConst(ConstNode constNode, Set<ExprNode> veDefsInConstNodes) {
    if (isVeDef(constNode.getExpr().getRoot())) {
      veDefsInConstNodes.add(constNode.getExpr().getRoot());
    }
  }

  private void buildVeDefAndValidate(FunctionNode func, List<VisualElement> vedefs) {
    if (func.numChildren() < 2) {
      return;
    }

    validateVeDefName(func);
    validateVeDefId(func);
    Optional<String> dataProtoType = validateDataProtoType(func);
    Optional<Object> staticMetadata = validateStaticMetadata(func);

    vedefs.add(
        LoggingConfigValidator.VisualElement.create(
            ((StringNode) func.getChild(0)).getValue(),
            ((IntegerNode) func.getChild(1)).getValue(),
            dataProtoType,
            staticMetadata,
            func.getSourceLocation()));
  }

  private void validateVeDefName(FunctionNode func) {
    if (!(func.getChild(0) instanceof StringNode)) {
      errorReporter.report(func.getChild(0).getSourceLocation(), BAD_VE_DEF_NAME);
    }
  }

  private void validateVeDefId(FunctionNode func) {
    if (!(func.getChild(1) instanceof IntegerNode)) {
      errorReporter.report(func.getChild(1).getSourceLocation(), BAD_VE_DEF_ID);
    }
  }

  private Optional<String> validateDataProtoType(FunctionNode func) {
    if (func.getChildren().size() < 3 || func.getChild(2) instanceof NullNode) {
      return Optional.empty();
    } else {
      if (!(func.getChild(2).getType() instanceof ProtoImportType)) {
        errorReporter.report(func.getChild(2).getSourceLocation(), BAD_VE_DEF_DATA_PROTO_TYPE);
        return Optional.empty();
      }
      return Optional.of(func.getChild(2).getType().toString());
    }
  }

  private Optional<Object> validateStaticMetadata(FunctionNode func) {
    if (func.getChildren().size() < 4) {
      return Optional.empty();
    } else {
      if (!func.getChild(3).getType().toString().equals("soy.LoggableElementMetadata")) {
        errorReporter.report(func.getChild(3).getSourceLocation(), BAD_VE_DEF_METADATA);
        return Optional.empty();
      }
      return Optional.of(exprEquivalence.wrap(func.getChild(3).copy(copyState)));
    }
  }

  private static boolean isVeDef(ExprNode node) {
    if (!(node instanceof FunctionNode)) {
      return false;
    }
    FunctionNode functionNode = (FunctionNode) node;
    return functionNode.isResolved() && functionNode.getSoyFunction() == BuiltinFunction.VE_DEF;
  }
}
