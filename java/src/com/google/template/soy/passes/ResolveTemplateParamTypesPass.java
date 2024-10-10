
/*
 * Copyright 2018 Google Inc.
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
import com.google.template.soy.base.internal.TemplateContentKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.soytree.ExternNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.TemplateElementNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.defn.AttrParam;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.soytree.defn.TemplateStateVar;
import com.google.template.soy.types.FunctionType;
import com.google.template.soy.types.UnknownType;
import com.google.template.soy.types.ast.TypeNodeConverter;

/** Resolve the TypeNode objects in TemplateParams to SoyTypes */
final class ResolveTemplateParamTypesPass implements CompilerFilePass {
  private final ErrorReporter errorReporter;
  private final boolean disableAllTypeChecking;

  private static final SoyErrorKind ATTRIBUTE_PARAM_ONLY_IN_ELEMENT_TEMPLATE =
      SoyErrorKind.of("Only templates of kind=\"html<?>\" can have @attribute.");

  public ResolveTemplateParamTypesPass(
      ErrorReporter errorReporter, boolean disableAllTypeChecking) {
    this.errorReporter = errorReporter;
    this.disableAllTypeChecking = disableAllTypeChecking;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    TypeNodeConverter converter = createTypeNodeConverter(file);
    processExternals(file, converter);
    processTemplates(file, converter);
  }

  private TypeNodeConverter createTypeNodeConverter(SoyFileNode file) {
    return TypeNodeConverter.builder(errorReporter)
        .setTypeRegistry(file.getSoyTypeRegistry())
        .setDisableAllTypeChecking(disableAllTypeChecking)
        .build();
  }

  private void processExternals(SoyFileNode file, TypeNodeConverter converter) {
    for (ExternNode extern : file.getExterns()) {
      extern.setType((FunctionType) converter.getOrCreateType(extern.typeNode()));
    }
  }

  private void processTemplates(SoyFileNode file, TypeNodeConverter converter) {
    for (TemplateNode template : file.getTemplates()) {
      processTemplateParams(template, converter);
      if (template instanceof TemplateElementNode) {
        processStateVars((TemplateElementNode) template, converter);
      }
    }
  }

  private void processTemplateParams(TemplateNode template, TypeNodeConverter converter) {
    for (TemplateParam param : template.getAllParams()) {
      validateAttrParam(param, template);
      setParamType(param, converter);
    }
  }

  private void validateAttrParam(TemplateParam param, TemplateNode template) {
    if (param instanceof AttrParam
        && !(template.getTemplateContentKind() instanceof TemplateContentKind.ElementContentKind)) {
      errorReporter.report(param.getSourceLocation(), ATTRIBUTE_PARAM_ONLY_IN_ELEMENT_TEMPLATE);
    }
  }

  private void setParamType(TemplateParam param, TypeNodeConverter converter) {
    if (param.getTypeNode() != null) {
      param.setType(converter.getOrCreateType(param.getTypeNode()));
    } else if (disableAllTypeChecking) {
      param.setType(UnknownType.getInstance());
    }
  }

  private void processStateVars(TemplateElementNode template, TypeNodeConverter converter) {
    for (TemplateStateVar state : template.getStateVars()) {
      if (state.getTypeNode() != null) {
        state.setType(converter.getOrCreateType(state.getTypeNode()));
      }
    }
  }
}
