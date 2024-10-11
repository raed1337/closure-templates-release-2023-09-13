
/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.jssrc.restricted;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.internal.Converters;
import com.google.template.soy.data.internalutils.NodeContentKinds;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.jssrc.dsl.Precedence;
import java.util.List;

/**
 * Common utilities for dealing with JS expressions.
 *
 * <p>Important: This class may only be used in implementing plugins (e.g. functions, directives).
 */
public class JsExprUtils {

  /** Expression constant for empty string. */
  private static final JsExpr EMPTY_STRING = new JsExpr("''", Integer.MAX_VALUE);

  private JsExprUtils() {}

  /**
   * Builds one JS expression that computes the concatenation of the given JS expressions. The '+'
   * operator is used for concatenation. Operands will be protected with an extra pair of
   * parentheses if and only if needed.
   *
   * <p>The resulting expression is not guaranteed to be a string if the operands do not produce
   * strings when combined with the plus operator; e.g. 2+2 might be 4 instead of '22'.
   *
   * @param jsExprs The JS expressions to concatenate.
   * @return One JS expression that computes the concatenation of the given JS expressions.
   */
  public static JsExpr concatJsExprs(List<? extends JsExpr> jsExprs) {
    if (jsExprs.isEmpty()) {
      return EMPTY_STRING;
    }

    if (jsExprs.size() == 1) {
      return jsExprs.get(0);
    }

    int plusOpPrec = Precedence.forSoyOperator(Operator.PLUS).toInt();
    StringBuilder resultSb = new StringBuilder();
    appendJsExpressions(jsExprs, plusOpPrec, resultSb);
    
    return new JsExpr(resultSb.toString(), plusOpPrec);
  }

  private static void appendJsExpressions(List<? extends JsExpr> jsExprs, int plusOpPrec, StringBuilder resultSb) {
    boolean isFirst = true;
    for (JsExpr jsExpr : jsExprs) {
      boolean needsProtection = determineProtection(isFirst, jsExpr.getPrecedence(), plusOpPrec);
      if (isFirst) {
        isFirst = false;
      } else {
        resultSb.append(" + ");
      }

      if (needsProtection) {
        resultSb.append('(').append(jsExpr.getText()).append(')');
      } else {
        resultSb.append(jsExpr.getText());
      }
    }
  }

  private static boolean determineProtection(boolean isFirst, int exprPrecedence, int plusOpPrec) {
    return isFirst ? exprPrecedence < plusOpPrec : exprPrecedence <= plusOpPrec;
  }

  public static boolean isStringLiteral(JsExpr jsExpr) {
    String jsExprText = jsExpr.getText();
    int jsExprTextLastIndex = jsExprText.length() - 1;
    if (jsExprTextLastIndex < 1
        || jsExprText.charAt(0) != '\''
        || jsExprText.charAt(jsExprTextLastIndex) != '\'') {
      return false;
    }
    return isValidStringLiteral(jsExprText, jsExprTextLastIndex);
  }

  private static boolean isValidStringLiteral(String jsExprText, int jsExprTextLastIndex) {
    for (int i = 1; i < jsExprTextLastIndex; ++i) {
      char c = jsExprText.charAt(i);
      if (c == '\'') {
        return false;
      }
      if (c == '\\') {
        ++i; // skip the next character for escape sequences
      }
    }
    return true;
  }

  public static JsExpr toString(JsExpr expr) {
    if (isStringLiteral(expr)) {
      return expr;
    }
    return concatJsExprs(ImmutableList.of(EMPTY_STRING, expr));
  }

  /**
   * Wraps an expression in a function call.
   *
   * @param functionExprText expression for the function to invoke, such as a function name or
   *     constructor phrase (such as "new SomeClass").
   * @param jsExpr the expression to compute the argument to the function
   * @return a JS expression consisting of a call to the specified function, applied to the provided
   *     expression.
   */
  @VisibleForTesting
  static JsExpr wrapWithFunction(String functionExprText, JsExpr jsExpr) {
    Preconditions.checkNotNull(functionExprText);
    return new JsExpr(functionExprText + "(" + jsExpr.getText() + ")", Integer.MAX_VALUE);
  }

  /**
   * Wraps with the proper SanitizedContent constructor if contentKind is non-null.
   *
   * @param contentKind The kind of sanitized content.
   * @param jsExpr The expression to wrap.
   * @deprecated This method is not safe to use without a security review, please migrate away from
   *     it.
   */
  @Deprecated
  public static JsExpr maybeWrapAsSanitizedContent(ContentKind contentKind, JsExpr jsExpr) {
    if (contentKind == ContentKind.TEXT) {
      return jsExpr;
    } else {
      return wrapWithFunction(
          NodeContentKinds.toJsSanitizedContentOrdainer(
              Converters.toSanitizedContentKind(contentKind)),
          jsExpr);
    }
  }
}
