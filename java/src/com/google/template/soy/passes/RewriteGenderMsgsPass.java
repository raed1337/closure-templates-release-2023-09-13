
/*
 * Copyright 2012 Google Inc.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.template.soy.soytree.MsgSubstUnitPlaceholderNameUtils.genNaiveBaseNameForExpr;
import static com.google.template.soy.soytree.MsgSubstUnitPlaceholderNameUtils.genNoncollidingBaseNamesForExprs;
import static com.google.template.soy.soytree.MsgSubstUnitPlaceholderNameUtils.genShortestBaseNameForExpr;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.ErrorReporter.Checkpoint;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.MsgPluralNode;
import com.google.template.soy.soytree.MsgSelectCaseNode;
import com.google.template.soy.soytree.MsgSelectDefaultNode;
import com.google.template.soy.soytree.MsgSelectNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Visitor for rewriting 'msg' nodes with 'genders' attribute into 'msg' nodes with one or more
 * levels of 'select'.
 */
final class RewriteGenderMsgsPass implements CompilerFilePass {

  private static final SoyErrorKind MORE_THAN_THREE_TOTAL_GENDERS =
      SoyErrorKind.of(
          "A message can only contain at most 3 genders between the ''genders'' attribute and "
              + "''select'' command.");

  private static final SoyErrorKind MORE_THAN_TWO_GENDER_EXPRS_WITH_PLURAL =
      SoyErrorKind.of(
          "A msg with ''plural'' can contain at most 2 gender expressions between the "
              + "''genders'' attribute and ''select'' command (otherwise, combinatorial explosion "
              + "would cause a gigantic generated message).");

  /** Fallback base select var name. */
  private static final String FALLBACK_BASE_SELECT_VAR_NAME = "GENDER";

  private final ErrorReporter errorReporter;

  RewriteGenderMsgsPass(ErrorReporter errorReporter) {
    this.errorReporter = Preconditions.checkNotNull(errorReporter);
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    for (MsgNode msg : SoyTreeUtils.getAllNodesOfType(file, MsgNode.class)) {
      maybeRewriteNode(msg, nodeIdGen);
    }
  }

  private void maybeRewriteNode(MsgNode msg, IdGenerator nodeIdGen) {
    List<ExprRootNode> genderExprs = msg.getAndRemoveGenderExprs();
    if (genderExprs == null) {
      return; // not a msg that this pass should rewrite
    }

    // ------ Do the rewrite. ------
    genderExprs = Lists.reverse(genderExprs);
    Checkpoint checkpoint = errorReporter.checkpoint();
    List<String> baseSelectVarNames = generateBaseSelectVarNames(genderExprs, checkpoint, nodeIdGen);
    if (errorReporter.errorsSince(checkpoint)) {
      return; // To prevent an IndexOutOfBoundsException below.
    }

    for (int i = 0; i < genderExprs.size(); i++) {
      splitMsgForGender(msg, genderExprs.get(i), baseSelectVarNames.get(i), nodeIdGen);
    }

    // ------ Verify from the re-written msg that gender restrictions are followed. ------
    checkExceedsMaxGenders((MsgSelectNode) msg.getChild(0), 1);
  }

  private List<String> generateBaseSelectVarNames(List<ExprRootNode> genderExprs, Checkpoint checkpoint, IdGenerator nodeIdGen) {
    return genNoncollidingBaseNamesForExprs(
        ExprRootNode.unwrap(genderExprs), FALLBACK_BASE_SELECT_VAR_NAME, errorReporter);
  }

  private static void splitMsgForGender(
      MsgNode msg,
      ExprRootNode genderExpr,
      @Nullable String baseSelectVarName,
      IdGenerator nodeIdGen) {

    List<StandaloneNode> origChildren = ImmutableList.copyOf(msg.getChildren());
    msg.clearChildren();

    MsgSelectNode selectNode = createSelectNode(msg, genderExpr, baseSelectVarName, nodeIdGen);
    msg.addChild(selectNode);
  }

  private static MsgSelectNode createSelectNode(MsgNode msg, ExprRootNode genderExpr, @Nullable String baseSelectVarName, IdGenerator nodeIdGen) {
    MsgSelectNode selectNode = MsgSelectNode.fromGenderExpr(
        nodeIdGen.genId(),
        msg.getSourceLocation(),
        msg.getOpenTagLocation(),
        genderExpr,
        baseSelectVarName);
    
    selectNode.addChild(createGenderCase(msg, "female", nodeIdGen));
    selectNode.addChild(createGenderCase(msg, "male", nodeIdGen));
    selectNode.addChild(createDefaultCase(msg, nodeIdGen));
    
    return selectNode;
  }

  private static MsgSelectCaseNode createGenderCase(MsgNode msg, String gender, IdGenerator nodeIdGen) {
    List<StandaloneNode> origChildren = ImmutableList.copyOf(msg.getChildren());
    MsgSelectCaseNode caseNode = new MsgSelectCaseNode(
        nodeIdGen.genId(), msg.getSourceLocation(), msg.getOpenTagLocation(), gender);
    caseNode.addChildren(origChildren);
    return caseNode;
  }

  private static MsgSelectDefaultNode createDefaultCase(MsgNode msg, IdGenerator nodeIdGen) {
    List<StandaloneNode> origChildren = ImmutableList.copyOf(msg.getChildren());
    MsgSelectDefaultNode defaultCase = new MsgSelectDefaultNode(
        nodeIdGen.genId(), msg.getSourceLocation(), msg.getOpenTagLocation());
    defaultCase.addChildren(copyWhilePreservingPlaceholderIdentity(origChildren, nodeIdGen));
    return defaultCase;
  }

  private static List<StandaloneNode> copyWhilePreservingPlaceholderIdentity(
      List<StandaloneNode> nodes, IdGenerator nodeIdGen) {
    List<StandaloneNode> copy = SoyTreeUtils.cloneListWithNewIds(nodes, nodeIdGen);
    List<MsgPlaceholderNode> placeholders = allPlaceholders(nodes);
    List<MsgPlaceholderNode> copyPlaceholders = allPlaceholders(copy);
    checkState(copyPlaceholders.size() == placeholders.size());
    for (int i = 0; i < copyPlaceholders.size(); i++) {
      copyPlaceholders.get(i).copySamenessKey(placeholders.get(i));
    }
    return copy;
  }

  private static List<MsgPlaceholderNode> allPlaceholders(List<StandaloneNode> nodes) {
    return nodes.stream()
        .flatMap(node -> SoyTreeUtils.allNodesOfType(node, MsgPlaceholderNode.class))
        .collect(toList());
  }

  /**
   * Helper to verify that a rewritten soy msg tree does not exceed the restriction on number of
   * total genders allowed (2 if includes plural, 3 otherwise).
   *
   * @param selectNode The select node to start searching from.
   * @param depth The current depth of the select node.
   * @return Whether the tree is valid.
   */
  private boolean checkExceedsMaxGenders(MsgSelectNode selectNode, int depth) {
    for (int caseNum = 0; caseNum < selectNode.numChildren(); caseNum++) {
      if (selectNode.getChild(caseNum).numChildren() > 0) {
        StandaloneNode caseNodeChild = selectNode.getChild(caseNum).getChild(0);
        if (caseNodeChild instanceof MsgPluralNode && depth >= 3) {
          errorReporter.report(selectNode.getSourceLocation(), MORE_THAN_TWO_GENDER_EXPRS_WITH_PLURAL);
          return false;
        }
        if (caseNodeChild instanceof MsgSelectNode) {
          if (depth >= 3) {
            errorReporter.report(selectNode.getSourceLocation(), MORE_THAN_THREE_TOTAL_GENDERS);
            return false;
          } else {
            boolean validSubtree = checkExceedsMaxGenders((MsgSelectNode) caseNodeChild, depth + 1);
            if (!validSubtree) {
              return false;
            }
          }
        }
      }
    }
    return true;
  }
}
