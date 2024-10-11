
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

package com.google.template.soy.pysrc.restricted;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.internal.Converters;
import com.google.template.soy.data.internalutils.NodeContentKinds;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.exprtree.Operator.Operand;
import com.google.template.soy.exprtree.Operator.Spacer;
import com.google.template.soy.exprtree.Operator.SyntaxElement;
import com.google.template.soy.exprtree.Operator.Token;
import com.google.template.soy.exprtree.SoyPrecedence;
import com.google.template.soy.internal.targetexpr.TargetExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Common utilities for dealing with Python expressions.
 *
 * <p>Important: This class may only be used in implementing plugins (e.g. functions, directives).
 */
public final class PyExprUtils {

  /** The variable name used to reference the current translator instance. */
  public static final String TRANSLATOR_NAME = "translator_impl";

  /** Expression constant for empty string. */
  private static final PyExpr EMPTY_STRING = new PyStringExpr("''");

  public static final int CALL_PRECEDENCE = 9;

  public static final int SUBSCRIPT_PRECEDENCE = 9;
  public static final int GETPROP_PRECEDENCE = 9;

  public static final int IN_PRECEDENCE = 5;

  private static final ImmutableMap<Operator, Integer> PYTHON_PRECEDENCES =
      new ImmutableMap.Builder<Operator, Integer>()
          .put(Operator.NEGATIVE, 12)
          .put(Operator.TIMES, 11)
          .put(Operator.DIVIDE_BY, 11)
          .put(Operator.MOD, 11)
          .put(Operator.PLUS, 10)
          .put(Operator.MINUS, 10)
          .put(Operator.SHIFT_RIGHT, 9)
          .put(Operator.SHIFT_LEFT, 9)
          .put(Operator.BITWISE_AND, 8)
          .put(Operator.BITWISE_XOR, 7)
          .put(Operator.BITWISE_OR, 6)
          .put(Operator.LESS_THAN, 5)
          .put(Operator.GREATER_THAN, 5)
          .put(Operator.LESS_THAN_OR_EQUAL, 5)
          .put(Operator.GREATER_THAN_OR_EQUAL, 5)
          .put(Operator.EQUAL, 5)
          .put(Operator.NOT_EQUAL, 5)
          .put(Operator.TRIPLE_EQUAL, 5)
          .put(Operator.TRIPLE_NOT_EQUAL, 5)
          .put(Operator.NOT, 4)
          .put(Operator.AND, 3)
          .put(Operator.OR, 2)
          .put(Operator.NULL_COALESCING, 1)
          .put(Operator.LEGACY_NULL_COALESCING, 1)
          .put(Operator.CONDITIONAL, 1)
          .build();

  private PyExprUtils() {}

  public static PyExpr concatPyExprs(List<? extends PyExpr> pyExprs) {
    if (pyExprs.isEmpty()) {
      return EMPTY_STRING;
    }

    if (pyExprs.size() == 1) {
      return pyExprs.get(0).toPyString();
    }

    StringBuilder resultSb = new StringBuilder().append("[");
    String delimiter = "";

    for (PyExpr pyExpr : pyExprs) {
      resultSb.append(delimiter).append(pyExpr.toPyString().getText());
      delimiter = ",";
    }

    return new PyListExpr(resultSb.append("]").toString(), Integer.MAX_VALUE);
  }

  public static PyExpr genPyNotNullCheck(PyExpr pyExpr) {
    return createPyExprWithCheck(pyExpr, Operator.NOT_EQUAL, "is not");
  }

  public static PyExpr genPyNullCheck(PyExpr expr) {
    return createPyExprWithCheck(expr, Operator.EQUAL, "is");
  }

  private static PyExpr createPyExprWithCheck(PyExpr expr, Operator operator, String token) {
    ImmutableList<PyExpr> exprs = ImmutableList.of(expr, new PyExpr("None", Integer.MAX_VALUE));
    String conditionalExpr = genExprWithNewToken(operator, exprs, token);
    return new PyExpr(conditionalExpr, pyPrecedenceForOperator(operator));
  }

  public static PyExpr maybeProtect(PyExpr expr, int minSafePrecedence) {
    return expr.getPrecedence() >= minSafePrecedence 
        ? expr 
        : new PyExpr("(" + expr.getText() + ")", Integer.MAX_VALUE);
  }

  @Deprecated
  public static PyExpr wrapAsSanitizedContent(ContentKind contentKind, PyExpr pyExpr) {
    if (contentKind == ContentKind.TEXT) {
      return pyExpr;
    }
    String sanitizer = NodeContentKinds.toPySanitizedContentOrdainer(Converters.toSanitizedContentKind(contentKind));
    String approval = "sanitize.IActuallyUnderstandSoyTypeSafetyAndHaveSecurityApproval(" +
        "'Internally created Sanitization.')";
    return new PyExpr(sanitizer + "(" + pyExpr.getText() + ", approval=" + approval + ")", Integer.MAX_VALUE);
  }

  public static int pyPrecedenceForOperator(Operator op) {
    return PYTHON_PRECEDENCES.get(op);
  }

  public static PyExpr convertIterableToPyListExpr(Iterable<?> iterable) {
    return convertIterableToPyExpr(iterable, true);
  }

  public static PyExpr convertIterableToPyTupleExpr(Iterable<?> iterable) {
    return convertIterableToPyExpr(iterable, false);
  }

  public static PyExpr convertMapToOrderedDict(Map<PyExpr, PyExpr> dict) {
    return convertMapToPyExpr(dict, "collections.OrderedDict");
  }

  public static PyExpr convertMapToPyExpr(Map<PyExpr, PyExpr> dict) {
    return convertMapToPyExpr(dict, "{}");
  }

  private static PyExpr convertMapToPyExpr(Map<PyExpr, PyExpr> dict, String wrapper) {
    List<String> values = new ArrayList<>();
    for (Map.Entry<PyExpr, PyExpr> entry : dict.entrySet()) {
      values.add(entry.getKey().getText() + ": " + entry.getValue().getText());
    }
    return new PyExpr(wrapper + Joiner.on(", ").join(values) + "}", Integer.MAX_VALUE);
  }

  public static PyExpr genPyMapLiteralFromListExpr(PyExpr listExpr, String varName, String keyString, String valueString) {
    String genCodeString = String.format("{%s['%s']: %s['%s'] for %s in %s}",
        varName, keyString, varName, valueString, varName, maybeProtect(listExpr, pyPrecedenceForOperator(Operator.OR)).getText());
    return new PyExpr(genCodeString, Integer.MAX_VALUE);
  }

  public static PyExpr genPyListComprehensionExpr(PyExpr listExpr, PyExpr transformExpr, PyExpr filterExpr, String varName, String indexName) {
    String genCodeString = indexName == null
        ? String.format("[%s for %s in %s", transformExpr.getText(), varName, maybeProtect(listExpr, pyPrecedenceForOperator(Operator.OR)).getText())
        : String.format("[%s for %s, %s in enumerate(%s)", transformExpr.getText(), indexName, varName, listExpr.getText());

    if (filterExpr != null) {
      genCodeString += " if " + maybeProtect(filterExpr, pyPrecedenceForOperator(Operator.OR)).getText();
    }

    genCodeString += "]";
    return new PyExpr(genCodeString, Integer.MAX_VALUE);
  }

  private static PyExpr convertIterableToPyExpr(Iterable<?> iterable, boolean asArray) {
    List<String> values = new ArrayList<>();
    String leftDelimiter = asArray ? "[" : "(";
    String rightDelimiter = asArray ? "]" : ")";

    for (Object elem : iterable) {
      if (!(elem instanceof Number || elem instanceof String || elem instanceof PyExpr)) {
        throw new UnsupportedOperationException("Only Number, String and PyExpr is allowed");
      }
      values.add(elem instanceof Number ? String.valueOf(elem) : elem instanceof PyExpr ? ((PyExpr) elem).getText() : "'" + elem + "'");
    }

    String contents = Joiner.on(", ").join(values);
    if (values.size() == 1 && !asArray) {
      contents += ",";
    }
    return new PyListExpr(leftDelimiter + contents + rightDelimiter, Integer.MAX_VALUE);
  }

  public static String genExprWithNewToken(Operator op, List<? extends TargetExpr> operandExprs, String newToken) {
    int opPrec = PYTHON_PRECEDENCES.get(op);
    boolean isLeftAssociative = op.getAssociativity() == SoyPrecedence.Associativity.LEFT;
    StringBuilder exprSb = new StringBuilder();

    for (int i = 0, n = op.getSyntax().size(); i < n; i++) {
      SyntaxElement syntaxEl = op.getSyntax().get(i);
      if (syntaxEl instanceof Operand) {
        int operandIndex = ((Operand) syntaxEl).getIndex();
        TargetExpr operandExpr = operandExprs.get(operandIndex);
        boolean needsProtection = (i == (isLeftAssociative ? 0 : n - 1))
            ? operandExpr.getPrecedence() < opPrec
            : operandExpr.getPrecedence() <= opPrec;
        exprSb.append(needsProtection ? "(" + operandExpr.getText() + ")" : operandExpr.getText());
      } else if (syntaxEl instanceof Token) {
        exprSb.append(newToken != null ? newToken : ((Token) syntaxEl).getValue());
      } else if (syntaxEl instanceof Spacer) {
        exprSb.append(' ');
      } else {
        throw new AssertionError();
      }
    }

    return exprSb.toString();
  }
}
