
/*
 * Copyright 2016 Google Inc.
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
import com.google.template.soy.base.internal.Identifier;
import com.google.template.soy.exprtree.GlobalNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;

/** A {@link CompilerFilePass} that resolves globals against {alias} commands in the same file. */
@RunBefore({CheckGlobalsPass.class})
final class RewriteGlobalsPass implements CompilerFilePass {

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    SoyTreeUtils.allNodesOfType(file, GlobalNode.class)
        .forEach(global -> resolveGlobal(file, global));
  }

  private void resolveGlobal(SoyFileNode file, GlobalNode global) {
    Identifier original = global.getIdentifier();
    Identifier alias = resolveAlias(file, original);
    updateGlobalResolution(global, original, alias);
  }

  private Identifier resolveAlias(SoyFileNode file, Identifier identifier) {
    return file.resolveAlias(identifier);
  }

  private void updateGlobalResolution(GlobalNode global, Identifier original, Identifier alias) {
    if (!alias.equals(original)) {
      global.resolve(alias.identifier());
    }
  }
}
