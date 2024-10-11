
/*
 * Copyright 2011 Google Inc.
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

package com.google.template.soy.sharedpasses.opti;

import static com.google.template.soy.exprtree.ExprNodes.isNullishLiteral;

import com.google.template.soy.data.SoyDataException;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.internalutils.InternalValueUtils;
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.FloatData;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.NullData;
import com.google.template.soy.data.restricted.PrimitiveData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.data.restricted.UndefinedData;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.AbstractExprNodeVisitor;
import com.google.template.soy.exprtree.BooleanNode;
import com.google.template.soy.exprtree.DataAccessNode;
import com.google.template.soy.exprtree.ExprEquivalence;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprNode.Kind;
import com.google.template.soy.exprtree.ExprNode.ParentExprNode;
import com.google.template.soy.exprtree.ExprNode.PrimitiveNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.FieldAccessNode;
import com.google.template.soy.exprtree.FloatNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.ItemAccessNode;
import com.google.template.soy.exprtree.ListLiteralNode;
import com.google.template.soy.exprtree.MapLiteralNode;
import com.google.template.soy.exprtree.MethodCallNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.NullSafeAccessNode;
import com.google.template.soy.exprtree.OperatorNodes.AndOpNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NullCoalescingOpNode;
import com.google.template.soy.exprtree.OperatorNodes.OrOpNode;
import com.google.template.soy.exprtree.ProtoEnumValueNode;
import com.google.template.soy.exprtree.RecordLiteralNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.logging.LoggingFunction;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.shared.internal.BuiltinMethod;
import com.google.template.soy.sharedpasses.render.Environment;
import com.google.template.soy.sharedpasses.render.RenderException;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/**
 * Visitor for simplifying expressions based on constant values known at compile time.
 *
 * <p>Package-private helper for {@link SimplifyVisitor}.
 */
final class SimplifyExprVisitor extends AbstractExprNodeVisitor<Void> {

  private static final SoyErrorKind SOY_DATA_ERROR = SoyErrorKind.of("Invalid value: {0}.");

  /** The PreevalVisitor for this instance (can reuse). */
  private final PreevalVisitor preevalVisitor;
  private final ErrorReporter errorReporter;

  SimplifyExprVisitor(ErrorReporter errorReporter) {
    this.preevalVisitor = new PreevalVisitor(Environment.prerenderingEnvironment());
    this.errorReporter = errorReporter;
  }

  // -----------------------------------------------------------------------------------------------
  // Implementation for root node.

  @Override
  protected void visitExprRootNode(ExprRootNode node) {
    visit(node.getRoot());
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for collection nodes.

  @Override
  protected void visitListLiteralNode(ListLiteralNode node) {
    visitChildren(node);
  }

  @Override
  protected void visitRecordLiteralNode(RecordLiteralNode node) {
    visitChildren(node);
  }

  @Override
  protected void visitMapLiteralNode(MapLiteralNode node) {
    visitChildren(node);
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for operators.

  @Override
  protected void visitAndOpNode(AndOpNode node) {
    visitChildren(node);
    simplifyAndNode(node);
  }

  private void simplifyAndNode(AndOpNode node) {
    SoyValue operand0 = getConstantOrNull(node.getChild(0));
    if (operand0 != null) {
      ExprNode replacementNode = operand0.coerceToBoolean() ? node.getChild(1) : node.getChild(0);
      node.getParent().replaceChild(node, replacementNode);
    }
  }

  @Override
  protected void visitOrOpNode(OrOpNode node) {
    visitChildren(node);
    simplifyOrNode(node);
  }

  private void simplifyOrNode(OrOpNode node) {
    SoyValue operand0 = getConstantOrNull(node.getChild(0));
    if (operand0 != null) {
      ExprNode replacementNode = operand0.coerceToBoolean() ? node.getChild(0) : node.getChild(1);
      node.getParent().replaceChild(node, replacementNode);
    }
  }

  @Override
  protected void visitConditionalOpNode(ConditionalOpNode node) {
    visitChildren(node);
    simplifyConditionalOpNode(node);
  }

  private void simplifyConditionalOpNode(ConditionalOpNode node) {
    SoyValue operand0 = getConstantOrNull(node.getChild(0));
    if (operand0 == null) return;

    ExprNode replacementNode = operand0.coerceToBoolean() ? node.getChild(1) : node.getChild(2);
    node.getParent().replaceChild(node, replacementNode);
  }

  @Override
  protected void visitNullCoalescingOpNode(NullCoalescingOpNode node) {
    visitChildren(node);
    simplifyNullCoalescingOpNode(node);
  }

  private void simplifyNullCoalescingOpNode(NullCoalescingOpNode node) {
    SoyValue operand0 = getConstantOrNull(node.getChild(0));
    if (operand0 != null) {
      node.getParent().replaceChild(node, operand0.isNullish() ? node.getChild(1) : node.getChild(0));
    } else {
      handleNonConstantFirstChild(node);
    }
  }

  private void handleNonConstantFirstChild(NullCoalescingOpNode node) {
    switch (node.getChild(0).getKind()) {
      case LIST_LITERAL_NODE:
      case RECORD_LITERAL_NODE:
        node.getParent().replaceChild(node, node.getChild(0));
        break;
      default:
        break; // do nothing.
    }
  }

  @Override
  protected void visitFieldAccessNode(FieldAccessNode node) {
    visitDataAccessNodeInternal(node, SimplifyExprVisitor::visitFieldAccessNode);
  }

  @Nullable
  private static ExprNode visitFieldAccessNode(FieldAccessNode node, ExprNode baseExpr) {
    if (baseExpr instanceof RecordLiteralNode) {
      RecordLiteralNode recordLiteral = (RecordLiteralNode) baseExpr;
      for (int i = 0; i < recordLiteral.numChildren(); i++) {
        if (recordLiteral.getKey(i).identifier().equals(node.getFieldName())) {
          return recordLiteral.getChild(i);
        }
      }
    }
    return null;
  }

  private <T extends DataAccessNode> void visitDataAccessNodeInternal(
      T node, BiFunction<T, ExprNode, ExprNode> delegate) {
    visitExprNode(node);
    if (node.getParent() == null) return;

    ExprNode baseExpr = node.getChild(0);
    ExprNode replacement = delegate.apply(node, baseExpr);
    if (replacement != null) {
      node.getParent().replaceChild(node, replacement);
    }
  }

  @Override
  protected void visitItemAccessNode(ItemAccessNode node) {
    visitDataAccessNodeInternal(node, SimplifyExprVisitor::visitItemAccessNode);
  }

  @Nullable
  private static ExprNode visitItemAccessNode(ItemAccessNode node, ExprNode baseExpr) {
    ExprNode keyExpr = node.getChild(1);
    if (baseExpr instanceof ListLiteralNode && keyExpr instanceof IntegerNode) {
      return simplifyItemAccessNode((ListLiteralNode) baseExpr, (IntegerNode) keyExpr, node);
    }
    return null;
  }

  private static ExprNode simplifyItemAccessNode(ListLiteralNode listLiteral, IntegerNode keyExpr, ItemAccessNode node) {
    long index = keyExpr.getValue();
    if (index >= 0 && index < listLiteral.numChildren()) {
      return listLiteral.getChild((int) index);
    } else {
      return new NullNode(node.getSourceLocation());
    }
  }

  @Override
  protected void visitMethodCallNode(MethodCallNode node) {
    visitDataAccessNodeInternal(node, SimplifyExprVisitor::visitMethodCallNode);
  }

  @Nullable
  private static ExprNode visitMethodCallNode(MethodCallNode node, ExprNode baseExpr) {
    if (baseExpr instanceof MapLiteralNode && node.isMethodResolved() && node.getSoyMethod() == BuiltinMethod.MAP_GET) {
      return simplifyMethodCallNode(node, (MapLiteralNode) baseExpr);
    }
    return null;
  }

  private static ExprNode simplifyMethodCallNode(MethodCallNode node, MapLiteralNode mapLiteral) {
    ExprNode keyExpr = node.getParams().get(0);
    boolean areAllKeysConstants = true;
    ExprEquivalence exprEquivalence = new ExprEquivalence();

    for (int i = 0; i < mapLiteral.numChildren(); i += 2) {
      ExprNode key = mapLiteral.getChild(i);
      ExprNode value = mapLiteral.getChild(i + 1);
      if (exprEquivalence.equivalent(keyExpr, key)) {
        return value;
      }
      areAllKeysConstants = areAllKeysConstants && isConstant(key);
    }

    if (isConstant(keyExpr) && areAllKeysConstants) {
      return new NullNode(node.getSourceLocation());
    }
    return null;
  }

  @Override
  protected void visitNullSafeAccessNode(NullSafeAccessNode node) {
    while (true) {
      visit(node.getBase());
      if (optimizeNullSafeAccess(node)) return;
    }
  }

  private boolean optimizeNullSafeAccess(NullSafeAccessNode node) {
    ExprNode base = node.getBase();
    if (isNullishLiteral(base)) {
      node.getParent().replaceChild(node, base);
      return true;
    }
    return handleDataAccessChild(node);
  }

  private boolean handleDataAccessChild(NullSafeAccessNode node) {
    ExprNode dataAccessChild = node.getDataAccess();
    switch (dataAccessChild.getKind()) {
      case ASSERT_NON_NULL_OP_NODE:
        return true;
      case NULL_SAFE_ACCESS_NODE:
        return handleNestedNullSafeAccess(node, (NullSafeAccessNode) dataAccessChild);
      case FIELD_ACCESS_NODE:
      case ITEM_ACCESS_NODE:
      case METHOD_CALL_NODE:
        return handleFinalNullSafeAccess(node, (DataAccessNode) dataAccessChild);
      default:
        throw new AssertionError(dataAccessChild.getKind());
    }
  }

  private boolean handleNestedNullSafeAccess(NullSafeAccessNode node, NullSafeAccessNode nullSafeAccessChild) {
    DataAccessNode dataAccessChain = (DataAccessNode) nullSafeAccessChild.getBase();
    DataAccessNode dataAccessChainBase = findBaseDataAccess(dataAccessChain);
    ExprNode replacement = findReplacement(dataAccessChainBase, node.getBase());

    if (replacement == null) {
      return false;
    } else {
      node.getParent().replaceChild(node, nullSafeAccessChild);
      replaceInChain(dataAccessChainBase, replacement, dataAccessChain);
      return true;
    }
  }

  private void replaceInChain(DataAccessNode dataAccessChainBase, ExprNode replacement, DataAccessNode dataAccessChain) {
    if (dataAccessChainBase == dataAccessChain) {
      dataAccessChain.replaceChild(dataAccessChain.getBaseExprChild(), replacement);
    } else {
      dataAccessChainBase.getParent().replaceChild(dataAccessChainBase, replacement);
    }
  }

  private boolean handleFinalNullSafeAccess(NullSafeAccessNode node, DataAccessNode dataAccessChild) {
    DataAccessNode dataAccessChainBase = findBaseDataAccess(dataAccessChild);
    ExprNode replacement = findReplacement(dataAccessChainBase, node.getBase());

    if (replacement == null) {
      return false;
    } else if (dataAccessChild == dataAccessChainBase) {
      node.getParent().replaceChild(node, replacement);
      visit(replacement);
    } else {
      dataAccessChainBase.getParent().replaceChild(dataAccessChainBase, replacement);
      node.getParent().replaceChild(node, dataAccessChild);
      visit(dataAccessChild);
    }
    return true;
  }

  @Nullable
  private static ExprNode findReplacement(DataAccessNode dataAccessChainBase, ExprNode base) {
    switch (dataAccessChainBase.getKind()) {
      case FIELD_ACCESS_NODE:
        return visitFieldAccessNode((FieldAccessNode) dataAccessChainBase, base);
      case ITEM_ACCESS_NODE:
        return visitItemAccessNode((ItemAccessNode) dataAccessChainBase, base);
      case METHOD_CALL_NODE:
        return visitMethodCallNode((MethodCallNode) dataAccessChainBase, base);
      default:
        throw new AssertionError(dataAccessChainBase.getKind());
    }
  }

  private static DataAccessNode findBaseDataAccess(DataAccessNode node) {
    if (node.getBaseExprChild() instanceof DataAccessNode) {
      return findBaseDataAccess((DataAccessNode) node.getBaseExprChild());
    }
    return node;
  }

  // -----------------------------------------------------------------------------------------------
  // Implementations for functions.

  @Override
  protected void visitFunctionNode(FunctionNode node) {
    if (!(node.getSoyFunction() instanceof BuiltinFunction) && !(node.getSoyFunction() instanceof LoggingFunction)) {
      visitExprNode(node);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Fallback implementation.

  @Override
  protected void visitExprNode(ExprNode node) {
    if (!(node instanceof ParentExprNode)) return;

    ParentExprNode nodeAsParent = (ParentExprNode) node;
    visitChildren(nodeAsParent);

    if (childrenAreConstant(nodeAsParent)) {
      attemptPreeval(nodeAsParent);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Helpers.

  private void attemptPreeval(ExprNode node) {
    SoyValue preevalResult;
    try {
      preevalResult = preevalVisitor.exec(node);
    } catch (RenderException | SoyDataException e) {
      if (e instanceof SoyDataException) {
        errorReporter.report(node.getSourceLocation(), SOY_DATA_ERROR, e.getMessage());
      }
      return;
    }

    if (preevalResult instanceof PrimitiveData) {
      PrimitiveNode newNode = InternalValueUtils.convertPrimitiveDataToExpr((PrimitiveData) preevalResult, node.getSourceLocation());
      if (newNode != null) {
        node.getParent().replaceChild(node, newNode);
      }
    }
  }

  private static boolean childrenAreConstant(ParentExprNode parent) {
    if (parent.getKind() == Kind.NULL_SAFE_ACCESS_NODE) {
      NullSafeAccessNode nullSafe = (NullSafeAccessNode) parent;
      return isConstant(nullSafe.getBase()) && childrenAreConstant((ParentExprNode) nullSafe.getDataAccess());
    }
    for (ExprNode child : parent.getChildren()) {
      if (!isConstant(child)) {
        return false;
      }
    }
    return true;
  }

  static boolean isConstant(ExprNode expr) {
    return expr instanceof PrimitiveNode;
  }

  /** Returns the value of the given expression if it's constant, else returns null. */
  static SoyValue getConstantOrNull(ExprNode expr) {
    switch (expr.getKind()) {
      case NULL_NODE:
        return NullData.INSTANCE;
      case UNDEFINED_NODE:
        return UndefinedData.INSTANCE;
      case BOOLEAN_NODE:
        return BooleanData.forValue(((BooleanNode) expr).getValue());
      case INTEGER_NODE:
        return IntegerData.forValue(((IntegerNode) expr).getValue());
      case FLOAT_NODE:
        return FloatData.forValue(((FloatNode) expr).getValue());
      case STRING_NODE:
        return StringData.forValue(((StringNode) expr).getValue());
      case PROTO_ENUM_VALUE_NODE:
        return IntegerData.forValue(((ProtoEnumValueNode) expr).getValue());
      default:
        return null;
    }
  }
}
