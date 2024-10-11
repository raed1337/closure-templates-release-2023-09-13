
/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy.msgs.internal;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralCaseSpec;
import com.google.template.soy.msgs.restricted.SoyMsgPluralPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.msgs.restricted.SoyMsgSelectPart;
import com.google.template.soy.soytree.CaseOrDefaultNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.MsgPluralCaseNode;
import com.google.template.soy.soytree.MsgPluralDefaultNode;
import com.google.template.soy.soytree.MsgPluralNode;
import com.google.template.soy.soytree.MsgSelectCaseNode;
import com.google.template.soy.soytree.MsgSelectDefaultNode;
import com.google.template.soy.soytree.MsgSelectNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyNode.MsgBlockNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.VeLogNode;

/**
 * Soy-specific utilities for working with messages.
 */
public class MsgUtils {

  private MsgUtils() {}

  // -----------------------------------------------------------------------------------------------
  // Utilities independent of msg id format.

  public static ImmutableList<SoyMsgPart> buildMsgParts(MsgNode msgNode) {
    return buildMsgPartsForChildren(msgNode, msgNode);
  }

  // -----------------------------------------------------------------------------------------------
  // Utilities assuming a specific dual format: use unbraced placeholders for regular messages and
  // use braced placeholders for plural/select messages.

  public static MsgPartsAndIds buildMsgPartsAndComputeMsgIdForDualFormat(MsgNode msgNode) {
    return msgNode.isPlrselMsg() 
        ? createMsgPartsAndIdsForPlrSel(msgNode) 
        : buildMsgPartsAndComputeMsgIds(msgNode, false);
  }

  private static MsgPartsAndIds createMsgPartsAndIdsForPlrSel(MsgNode msgNode) {
    MsgPartsAndIds mpai = buildMsgPartsAndComputeMsgIds(msgNode, true);
    return new MsgPartsAndIds(mpai.parts, mpai.idUsingBracedPhs, -1L);
  }

  public static long computeMsgIdForDualFormat(MsgNode msgNode) {
    return msgNode.isPlrselMsg() 
        ? computeMsgIdUsingBracedPhs(msgNode) 
        : computeMsgId(msgNode);
  }

  public static class MsgPartsAndIds {
    public final ImmutableList<SoyMsgPart> parts;
    public final long id;
    public final long idUsingBracedPhs;

    private MsgPartsAndIds(ImmutableList<SoyMsgPart> parts, long id, long idUsingBracedPhs) {
      this.parts = parts;
      this.id = id;
      this.idUsingBracedPhs = idUsingBracedPhs;
    }
  }

  private static MsgPartsAndIds buildMsgPartsAndComputeMsgIds(MsgNode msgNode, boolean doComputeMsgIdUsingBracedPhs) {
    ImmutableList<SoyMsgPart> msgParts = buildMsgParts(msgNode);
    long msgId = SoyMsgIdComputer.computeMsgId(msgParts, msgNode.getMeaning(), msgNode.getContentType());
    long msgIdUsingBracedPhs = doComputeMsgIdUsingBracedPhs 
        ? SoyMsgIdComputer.computeMsgIdUsingBracedPhs(msgParts, msgNode.getMeaning(), msgNode.getContentType())
        : -1L;
    return new MsgPartsAndIds(msgParts, msgId, msgIdUsingBracedPhs);
  }

  private static long computeMsgId(MsgNode msgNode) {
    return SoyMsgIdComputer.computeMsgId(buildMsgParts(msgNode), msgNode.getMeaning(), msgNode.getContentType());
  }

  private static long computeMsgIdUsingBracedPhs(MsgNode msgNode) {
    return SoyMsgIdComputer.computeMsgIdUsingBracedPhs(buildMsgParts(msgNode), msgNode.getMeaning(), msgNode.getContentType());
  }

  // -----------------------------------------------------------------------------------------------
  // Private helpers for building the list of message parts.

  private static ImmutableList<SoyMsgPart> buildMsgPartsForChildren(MsgBlockNode parent, MsgNode msgNode) {
    ImmutableList.Builder<SoyMsgPart> msgParts = ImmutableList.builder();
    doBuildMsgPartsForChildren(parent, msgNode, msgParts);
    return msgParts.build();
  }

  private static void doBuildMsgPartsForChildren(MsgBlockNode parent, MsgNode msgNode, ImmutableList.Builder<SoyMsgPart> msgParts) {
    for (StandaloneNode child : parent.getChildren()) {
      addChildMsgPart(child, msgNode, msgParts);
    }
  }

  private static void addChildMsgPart(StandaloneNode child, MsgNode msgNode, ImmutableList.Builder<SoyMsgPart> msgParts) {
    if (child instanceof RawTextNode) {
      msgParts.add(SoyMsgRawTextPart.of(((RawTextNode) child).getRawText()));
    } else if (child instanceof MsgPlaceholderNode) {
      msgParts.add(new SoyMsgPlaceholderPart(msgNode.getPlaceholder((MsgPlaceholderNode) child)));
    } else if (child instanceof MsgPluralNode) {
      msgParts.add(buildMsgPartForPlural((MsgPluralNode) child, msgNode));
    } else if (child instanceof MsgSelectNode) {
      msgParts.add(buildMsgPartForSelect((MsgSelectNode) child, msgNode));
    } else if (child instanceof VeLogNode) {
      doBuildMsgPartsForChildren((VeLogNode) child, msgNode, msgParts);
    } else {
      throw new AssertionError("unexpected child: " + child);
    }
  }

  private static SoyMsgPluralPart buildMsgPartForPlural(MsgPluralNode msgPluralNode, MsgNode msgNode) {
    ImmutableList.Builder<SoyMsgPart.Case<SoyMsgPluralCaseSpec>> pluralCases = ImmutableList.builder();
    for (CaseOrDefaultNode child : msgPluralNode.getChildren()) {
      pluralCases.add(createPluralCase(child, msgNode));
    }
    return new SoyMsgPluralPart(msgNode.getPluralVarName(msgPluralNode), msgPluralNode.getOffset(), pluralCases.build());
  }

  private static SoyMsgPart.Case<SoyMsgPluralCaseSpec> createPluralCase(CaseOrDefaultNode child, MsgNode msgNode) {
    ImmutableList<SoyMsgPart> caseMsgParts = buildMsgPartsForChildren((MsgBlockNode) child, msgNode);
    SoyMsgPluralCaseSpec caseSpec = (child instanceof MsgPluralCaseNode) 
        ? new SoyMsgPluralCaseSpec(((MsgPluralCaseNode) child).getCaseNumber()) 
        : SoyMsgPluralCaseSpec.forType(SoyMsgPluralCaseSpec.Type.OTHER);
    return SoyMsgPart.Case.create(caseSpec, caseMsgParts);
  }

  private static SoyMsgSelectPart buildMsgPartForSelect(MsgSelectNode msgSelectNode, MsgNode msgNode) {
    ImmutableList.Builder<SoyMsgPart.Case<String>> selectCases = ImmutableList.builder();
    for (CaseOrDefaultNode child : msgSelectNode.getChildren()) {
      selectCases.add(createSelectCase(child, msgNode));
    }
    return new SoyMsgSelectPart(msgNode.getSelectVarName(msgSelectNode), selectCases.build());
  }

  private static SoyMsgPart.Case<String> createSelectCase(CaseOrDefaultNode child, MsgNode msgNode) {
    ImmutableList<SoyMsgPart> caseMsgParts = buildMsgPartsForChildren((MsgBlockNode) child, msgNode);
    String caseValue = (child instanceof MsgSelectCaseNode) 
        ? ((MsgSelectCaseNode) child).getCaseValue() 
        : null;
    return SoyMsgPart.Case.create(caseValue, caseMsgParts);
  }
}
