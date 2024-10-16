
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

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Longs;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.base.internal.QuoteStyle;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.IntegerNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.RecordLiteralNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.msgs.internal.MsgUtils;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.LetContentNode;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.LocalVarNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.defn.LocalVar;
import java.util.stream.Collectors;

/**
 * Implements the {@code msgWithId} and {@code msgId} functions.
 *
 * <p>These functions provide a way to access msg ids at runtime, their implementation is performed
 * by either calculating the msg id at compile time (if it is a constant) or rewriting the call to a
 * call to the {@code $$isPrimaryMsgInUse} function to detect which id is in use at runtime.
 *
 * <ul>
 *   <li>{@code msgId} simply returns the id
 *   <li>{@code msgWithId} will return a record of the id and the msg text which is slightly more
 *       usable.
 * </ul>
 */
@RunAfter({
  ResolveNamesPass.class,
  CheckNonEmptyMsgNodesPass.class,
})
@RunBefore({
  ResolveExpressionTypesPass.class,
})
final class MsgWithIdFunctionPass implements CompilerFilePass {
  private static final SoyErrorKind MSG_VARIABLE_NOT_IN_SCOPE =
      SoyErrorKind.of(
          "Function ''{0}'' must take a let variable containing a single msg "
              + "as its only argument.{1}",
          StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind ALTERNATE_ID_NOT_ALLOWED =
      SoyErrorKind.of(
          "Messages with alternate ids are not allowed to use function ''{0}''.{1}",
          StyleAllowance.NO_PUNCTUATION);

  private final ErrorReporter errorReporter;

  MsgWithIdFunctionPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    for (FunctionNode fn : getMsgWithIdFunctions(file)) {
      if (fn.numChildren() != 1) {
        continue;
      }
      ExprNode msgVariable = fn.getChild(0);
      if (!isValidMsgVariable(fn, msgVariable)) {
        continue;
      }
      LetContentNode letNode = (LetContentNode) ((LocalVar) ((VarRefNode) msgVariable).getDefnDecl()).declaringNode();
      MsgFallbackGroupNode fallbackGroupNode = getFallbackGroupNode(fn, letNode);
      if (fallbackGroupNode == null) {
        continue;
      }
      if (fallbackGroupNode.getMsg().getAlternateId().isPresent()) {
        badFunctionCall(fn, ALTERNATE_ID_NOT_ALLOWED, "");
        continue;
      }
      handleMsgIdCall(fn, fallbackGroupNode);
    }
  }

  private Iterable<FunctionNode> getMsgWithIdFunctions(SoyFileNode file) {
    return SoyTreeUtils.allFunctionInvocations(file, BuiltinFunction.MSG_WITH_ID).collect(Collectors.toList());
  }

  private boolean isValidMsgVariable(FunctionNode fn, ExprNode msgVariable) {
    if (!(msgVariable instanceof VarRefNode)) {
      badFunctionCall(fn, MSG_VARIABLE_NOT_IN_SCOPE, " It is not a variable.");
      return false;
    }
    VarDefn defn = ((VarRefNode) msgVariable).getDefnDecl();
    if (!(defn instanceof LocalVar)) {
      badFunctionCall(fn, MSG_VARIABLE_NOT_IN_SCOPE, " It is not a let variable.");
      return false;
    }
    LocalVarNode declaringNode = ((LocalVar) defn).declaringNode();
    if (!(declaringNode instanceof LetContentNode)) {
      badFunctionCall(fn, MSG_VARIABLE_NOT_IN_SCOPE, " It is not a let.");
      return false;
    }
    return true;
  }

  private MsgFallbackGroupNode getFallbackGroupNode(FunctionNode fn, LetContentNode letNode) {
    MsgFallbackGroupNode fallbackGroupNode = null;
    for (SoyNode child : letNode.getChildren()) {
      if (child instanceof RawTextNode && ((RawTextNode) child).getRawText().isEmpty()) {
        continue;
      } else if (child instanceof MsgFallbackGroupNode) {
        if (fallbackGroupNode == null) {
          fallbackGroupNode = (MsgFallbackGroupNode) child;
        } else {
          badFunctionCall(fn, MSG_VARIABLE_NOT_IN_SCOPE, " There is more than one msg.");
          return null;
        }
      } else {
        badFunctionCall(fn, MSG_VARIABLE_NOT_IN_SCOPE, " There is a non-msg child of the let.");
        return null;
      }
    }
    if (fallbackGroupNode == null) {
      badFunctionCall(fn, MSG_VARIABLE_NOT_IN_SCOPE, " There was no msg in the referenced let.");
    }
    return fallbackGroupNode;
  }

  private void badFunctionCall(FunctionNode fn, SoyErrorKind errorKind, String explanation) {
    errorReporter.report(
        fn.getChild(0).getSourceLocation(), errorKind, fn.getStaticFunctionName(), explanation);

    RecordLiteralNode recordLiteral =
        new RecordLiteralNode(
            Identifier.create("record", fn.getSourceLocation()),
            ImmutableList.of(
                Identifier.create("id", fn.getSourceLocation()),
                Identifier.create("msg", fn.getSourceLocation())),
            fn.getSourceLocation());
    recordLiteral.addChildren(
        ImmutableList.of(
            new StringNode("error", QuoteStyle.SINGLE, fn.getSourceLocation()),
            fn.getChild(0).copy(new CopyState())));
    fn.getParent().replaceChild(fn, recordLiteral);
  }

  /**
   * Rewrites calls to msgId($msgVar) to either a static constant message id or a conditional if
   * there is a fallback.
   */
  private void handleMsgIdCall(FunctionNode fn, MsgFallbackGroupNode msgNode) {
    ExprNode msgIdNode = createMsgIdNode(fn, msgNode);
    RecordLiteralNode recordLiteral =
        new RecordLiteralNode(
            Identifier.create("record", fn.getSourceLocation()),
            ImmutableList.of(
                Identifier.create("id", fn.getSourceLocation()),
                Identifier.create("msg", fn.getSourceLocation())),
            fn.getSourceLocation());
    recordLiteral.addChildren(ImmutableList.of(msgIdNode, fn.getChild(0).copy(new CopyState())));
    fn.getParent().replaceChild(fn, recordLiteral);
  }

  private ExprNode createMsgIdNode(FunctionNode fn, MsgFallbackGroupNode msgNode) {
    long primaryMsgId = MsgUtils.computeMsgIdForDualFormat(msgNode.getChild(0));
    if (msgNode.numChildren() == 1) {
      return createMsgIdNode(primaryMsgId, fn.getSourceLocation());
    } else {
      return createConditionalMsgIdNode(fn, msgNode, primaryMsgId);
    }
  }

  private ConditionalOpNode createConditionalMsgIdNode(FunctionNode fn, MsgFallbackGroupNode msgNode, long primaryMsgId) {
    long fallbackMsgId = MsgUtils.computeMsgIdForDualFormat(msgNode.getChild(1));
    ConditionalOpNode condOpNode = new ConditionalOpNode(fn.getSourceLocation(), fn.getSourceLocation());
    FunctionNode isPrimaryMsgInUse = createIsPrimaryMsgInUseNode(fn, primaryMsgId, fallbackMsgId);
    condOpNode.addChild(isPrimaryMsgInUse);
    condOpNode.addChild(createMsgIdNode(primaryMsgId, fn.getSourceLocation()));
    condOpNode.addChild(createMsgIdNode(fallbackMsgId, fn.getSourceLocation()));
    return condOpNode;
  }

  private FunctionNode createIsPrimaryMsgInUseNode(FunctionNode fn, long primaryMsgId, long fallbackMsgId) {
    FunctionNode isPrimaryMsgInUse =
        FunctionNode.newPositional(
            Identifier.create(
                BuiltinFunction.IS_PRIMARY_MSG_IN_USE.getName(), fn.getSourceLocation()),
            BuiltinFunction.IS_PRIMARY_MSG_IN_USE,
            fn.getSourceLocation());
    isPrimaryMsgInUse.addChild(fn.getChild(0).copy(new CopyState()));
    isPrimaryMsgInUse.addChild(new IntegerNode(primaryMsgId, fn.getSourceLocation()));
    isPrimaryMsgInUse.addChild(new IntegerNode(fallbackMsgId, fn.getSourceLocation()));
    return isPrimaryMsgInUse;
  }

  private StringNode createMsgIdNode(long id, SourceLocation location) {
    return new StringNode(formatMsgId(id), QuoteStyle.SINGLE, location);
  }

  // encodes the id as web safe base64 string
  private static String formatMsgId(long id) {
    return BaseEncoding.base64Url().encode(Longs.toByteArray(id));
  }
}
