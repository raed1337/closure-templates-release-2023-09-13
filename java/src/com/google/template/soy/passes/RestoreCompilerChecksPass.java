
/*
 * Copyright 2020 Google Inc.
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

import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.AbstractLocalVarDefn;
import com.google.template.soy.exprtree.ListComprehensionNode;
import com.google.template.soy.exprtree.RecordLiteralNode;
import com.google.template.soy.soytree.ForNonemptyNode;
import com.google.template.soy.soytree.LetNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;

/**
 * Reimplement certain compiler checks that were removed from the compiler when the distinction
 * between $ var refs and non-$ globals was removed.
 */
@RunAfter(RestoreGlobalsPass.class)
@RunBefore(ResolveTemplateNamesPass.class)
final class RestoreCompilerChecksPass implements CompilerFilePass {

  private static final SoyErrorKind MUST_BE_DOLLAR_IDENT =
      SoyErrorKind.of("Name must begin with a ''$''.");

  private static final SoyErrorKind MUST_NOT_BE_DOLLAR_IDENT =
      SoyErrorKind.of("Name must not begin with a ''$''.");

  private static final SoyErrorKind MUST_BE_CONSTANT =
      SoyErrorKind.of("Expected constant identifier.");

  private final ErrorReporter errorReporter;

  public RestoreCompilerChecksPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    enforceDollarIdentForLetNodes(file);
    enforceDollarIdentForForNonemptyNodes(file);
    enforceDollarIdentForListComprehensionNodes(file);
    enforceNonDollarIdentForRecordKeys(file);
  }

  private void enforceDollarIdentForLetNodes(SoyFileNode file) {
    SoyTreeUtils.allNodesOfType(file, LetNode.class)
        .map(LetNode::getVar)
        .forEach(this::checkDollarIdent);
  }

  private void enforceDollarIdentForForNonemptyNodes(SoyFileNode file) {
    SoyTreeUtils.allNodesOfType(file, ForNonemptyNode.class)
        .forEach(forNode -> {
          checkDollarIdent(forNode.getVar());
          if (forNode.getIndexVar() != null) {
            checkDollarIdent(forNode.getIndexVar());
          }
        });
  }

  private void enforceDollarIdentForListComprehensionNodes(SoyFileNode file) {
    SoyTreeUtils.allNodesOfType(file, ListComprehensionNode.class)
        .forEach(listNode -> {
          checkDollarIdent(listNode.getListIterVar());
          if (listNode.getIndexVar() != null) {
            checkDollarIdent(listNode.getIndexVar());
          }
        });
  }

  private void enforceNonDollarIdentForRecordKeys(SoyFileNode file) {
    SoyTreeUtils.allNodesOfType(file, RecordLiteralNode.class)
        .flatMap(r -> r.getKeys().stream())
        .filter(k -> k.identifier().startsWith("$"))
        .forEach(k -> errorReporter.report(k.location(), MUST_NOT_BE_DOLLAR_IDENT));
  }

  private void checkDollarIdent(AbstractLocalVarDefn<?> localVar) {
    if (!localVar.getOriginalName().startsWith("$")) {
      errorReporter.report(localVar.nameLocation(), MUST_BE_DOLLAR_IDENT);
    }
  }
}
