
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

package com.google.template.soy.passes;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.SanitizedContentKind;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.exprtree.TemplateLiteralNode;
import com.google.template.soy.exprtree.VarDefn;
import com.google.template.soy.exprtree.VarDefn.Kind;
import com.google.template.soy.passes.LocalVariablesNodeVisitor.LocalVariables;
import com.google.template.soy.shared.internal.DelTemplateSelector;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.FileSetMetadata;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateMetadata;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.TemplateType.Parameter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Checks various rules regarding the use of delegates (including delegate packages, delegate
 * templates, and delegate calls).
 */
@RunAfter(FinalizeTemplateRegistryPass.class)
final class CheckDelegatesPass implements CompilerFileSetPass {

  private static final SoyErrorKind CROSS_PACKAGE_DELCALL =
      SoyErrorKind.of(
          "Found illegal call from ''{0}'' to ''{1}'', which is in a different delegate package.");
  private static final SoyErrorKind DELCALL_TO_BASIC_TEMPLATE =
      SoyErrorKind.of("''delcall'' to basic template defined at ''{0}'' (expected ''call'').");
  private static final SoyErrorKind DELTEMPLATES_WITH_DIFFERENT_PARAM_DECLARATIONS =
      SoyErrorKind.of(
          "Found delegate template with same name ''{0}'' but different param declarations"
              + " compared to the definition at {1}.{2}",
          StyleAllowance.NO_PUNCTUATION);
  private static final SoyErrorKind STRICT_DELTEMPLATES_WITH_DIFFERENT_CONTENT_KIND =
      SoyErrorKind.of(
          "If one deltemplate has strict autoescaping, all its peers must also be strictly"
              + " autoescaped with the same content kind: {0} != {1}. Conflicting definition at"
              + " {2}.");
  private static final SoyErrorKind DELTEMPLATES_WITH_DIFFERENT_STRICT_HTML_MODE =
      SoyErrorKind.of(
          "Found delegate template with same name ''{0}'' but different strict html mode "
              + "compared to the definition at {1}.");
  private static final SoyErrorKind CANNOT_DELCALL_WITHOUT_LEGACY_NAMESPACE =
      SoyErrorKind.of(
          "Modifiable templates must have legacydeltemplatenamespace set to be used with"
              + " `delcall`.");
  private static final SoyErrorKind CANNOT_DELTEMPLATE_WITHOUT_LEGACY_NAMESPACE =
      SoyErrorKind.of(
          "Modifiable templates must have legacydeltemplatenamespace set to be used with"
              + " `deltemplate`.");
  private static final SoyErrorKind DELTEMPLATES_DEPRECATED =
      SoyErrorKind.of(
          "Deltemplates are deprecated. Use go/soy/reference/modifiable-templates instead.");

  private final ErrorReporter errorReporter;
  private final Supplier<FileSetMetadata> templateRegistryFull;

  CheckDelegatesPass(ErrorReporter errorReporter, Supplier<FileSetMetadata> templateRegistryFull) {
    this.errorReporter = errorReporter;
    this.templateRegistryFull = templateRegistryFull;
  }

  @Override
  public Result run(ImmutableList<SoyFileNode> sourceFiles, IdGenerator idGenerator) {
    checkTemplates(templateRegistryFull.get().getDelTemplateSelector());

    for (SoyFileNode fileNode : sourceFiles) {
      LocalVariables localVariables = LocalVariablesNodeVisitor.getFileScopeVariables(fileNode);
      for (TemplateNode template : fileNode.getTemplates()) {
        checkTemplateForDeprecation(template);
        String currTemplateNameForUserMsgs = template.getTemplateNameForUserMsgs();
        String currModName = template.getModName();
        checkTemplateLiterals(template, currModName, currTemplateNameForUserMsgs);
        checkCallDelegateNodes(template, localVariables);
      }
    }
    return Result.CONTINUE;
  }

  private void checkTemplateForDeprecation(TemplateNode template) {
    if (template instanceof TemplateDelegateNode
        && isNullOrEmpty(((TemplateDelegateNode) template).getDelTemplateVariant())
        && isNullOrEmpty(template.getModName())) {
      errorReporter.report(template.getSourceLocation(), DELTEMPLATES_DEPRECATED);
    }
  }

  private void checkTemplateLiterals(TemplateNode template, String currModName, String currTemplateNameForUserMsgs) {
    for (TemplateLiteralNode templateLiteralNode :
        SoyTreeUtils.getAllNodesOfType(template, TemplateLiteralNode.class)) {
      checkTemplateLiteralNode(templateLiteralNode, currModName, currTemplateNameForUserMsgs);
    }
  }

  private void checkCallDelegateNodes(TemplateNode template, LocalVariables localVariables) {
    for (CallDelegateNode callNode :
        SoyTreeUtils.getAllNodesOfType(template, CallDelegateNode.class)) {
      checkCallDelegateNode(callNode, localVariables, templateRegistryFull.get().getDelTemplateSelector());
    }
  }

  private void checkTemplates(DelTemplateSelector<TemplateMetadata> fileSetDelTemplateSelector) {
    for (Collection<TemplateMetadata> delTemplateGroup :
        fileSetDelTemplateSelector.delTemplateNameToValues().asMap().values()) {
      TemplateMetadata firstDelTemplate = findFirstSourceTemplate(delTemplateGroup);
      if (firstDelTemplate == null) {
        continue;
      }
      Set<Parameter> firstRequiredParamSet = getRequiredParamSet(firstDelTemplate);
      SanitizedContentKind firstContentKind =
          firstDelTemplate.getTemplateType().getContentKind().getSanitizedContentKind();
      boolean firstStrictHtml =
          firstDelTemplate.getTemplateType().isStrictHtml() && firstContentKind.isHtml();
      checkDelegateTemplateGroup(delTemplateGroup, firstDelTemplate, firstRequiredParamSet, firstContentKind, firstStrictHtml);
    }
  }

  private TemplateMetadata findFirstSourceTemplate(Collection<TemplateMetadata> delTemplateGroup) {
    for (TemplateMetadata delTemplate : delTemplateGroup) {
      if (delTemplate.getSoyFileKind() == SoyFileKind.SRC) {
        return delTemplate;
      }
    }
    return delTemplateGroup.isEmpty() ? null : delTemplateGroup.iterator().next();
  }

  private void checkDelegateTemplateGroup(Collection<TemplateMetadata> delTemplateGroup, 
                                          TemplateMetadata firstDelTemplate,
                                          Set<Parameter> firstRequiredParamSet,
                                          SanitizedContentKind firstContentKind,
                                          boolean firstStrictHtml) {
    for (TemplateMetadata delTemplate : delTemplateGroup) {
      if (firstDelTemplate == delTemplate) {
        continue;
      }
      checkParamSets(delTemplate, firstDelTemplate, firstRequiredParamSet);
      checkContentKind(delTemplate, firstDelTemplate, firstContentKind);
      checkStrictHtmlMode(delTemplate, firstDelTemplate, firstStrictHtml);
    }
    checkLegacyNamespace(delTemplateGroup);
  }

  private void checkParamSets(TemplateMetadata delTemplate, TemplateMetadata firstDelTemplate, Set<Parameter> firstRequiredParamSet) {
    Set<Parameter> currRequiredParamSet = getRequiredParamSet(delTemplate);
    if (!paramSetsEqual(currRequiredParamSet, firstRequiredParamSet)
        && !delTemplate.getTemplateType().isModifiable()
        && !delTemplate.getTemplateType().isModifying()) {
      List<Parameter> firstParamList = firstDelTemplate.getTemplateType().getParameters();
      List<Parameter> currParamList = delTemplate.getTemplateType().getParameters();
      Set<Parameter> missingParamSet =
          getRequiredParamsDifference(firstParamList, currParamList);
      Set<Parameter> unexpectedParamSet =
          getRequiredParamsDifference(currParamList, firstParamList);
      errorReporter.report(
          delTemplate.getSourceLocation(),
          DELTEMPLATES_WITH_DIFFERENT_PARAM_DECLARATIONS,
          delTemplate.getDelTemplateName(),
          firstDelTemplate.getSourceLocation().toString(),
          getInconsistentParamMessage(missingParamSet, unexpectedParamSet));
    }
  }

  private void checkContentKind(TemplateMetadata delTemplate, TemplateMetadata firstDelTemplate, SanitizedContentKind firstContentKind) {
    if (delTemplate.getTemplateType().getContentKind().getSanitizedContentKind() != firstContentKind) {
      errorReporter.report(
          firstDelTemplate.getSourceLocation(),
          STRICT_DELTEMPLATES_WITH_DIFFERENT_CONTENT_KIND,
          String.valueOf(delTemplate.getTemplateType().getContentKind().getSanitizedContentKind()),
          String.valueOf(firstContentKind),
          delTemplate.getSourceLocation().toString());
    }
  }

  private void checkStrictHtmlMode(TemplateMetadata delTemplate, TemplateMetadata firstDelTemplate, boolean firstStrictHtml) {
    if (delTemplate.getTemplateType().isStrictHtml() != firstStrictHtml) {
      errorReporter.report(
          firstDelTemplate.getSourceLocation(),
          DELTEMPLATES_WITH_DIFFERENT_STRICT_HTML_MODE,
          delTemplate.getDelTemplateName(),
          delTemplate.getSourceLocation().toString());
    }
  }

  private void checkLegacyNamespace(Collection<TemplateMetadata> delTemplateGroup) {
    TemplateMetadata defaultTemplate = getDefault(delTemplateGroup);
    if (defaultTemplate != null
        && defaultTemplate.getTemplateType().isModifiable()
        && defaultTemplate.getTemplateType().getLegacyDeltemplateNamespace().isEmpty()) {
      for (TemplateMetadata template : delTemplateGroup) {
        if (template != defaultTemplate && !template.getTemplateType().isModifying()) {
          errorReporter.report(
              template.getSourceLocation(), CANNOT_DELTEMPLATE_WITHOUT_LEGACY_NAMESPACE);
        }
      }
    }
  }

  private static boolean paramSetsEqual(Set<Parameter> s1, Set<Parameter> s2) {
    return s1.equals(s2);
  }

  private static Set<Parameter> getRequiredParamSet(TemplateMetadata delTemplate) {
    return delTemplate.getTemplateType().getParameters().stream()
        .filter(Parameter::isRequired)
        .map(Parameter::toComparable)
        .collect(Collectors.toSet());
  }

  private void checkTemplateLiteralNode(
      TemplateLiteralNode node, @Nullable String currModName, String currTemplateNameForUserMsgs) {
    String calleeName = node.getResolvedName();
    TemplateMetadata callee = templateRegistryFull.get().getBasicTemplateOrElement(calleeName);
    if (callee != null) {
      checkCalleeModName(node, currModName, currTemplateNameForUserMsgs, callee);
    }
  }

  private void checkCalleeModName(TemplateLiteralNode node, String currModName, String currTemplateNameForUserMsgs, TemplateMetadata callee) {
    String calleeModName = callee.getModName();
    if (calleeModName != null && !calleeModName.equals(currModName)) {
      errorReporter.report(
          node.getSourceLocation(),
          CROSS_PACKAGE_DELCALL,
          currTemplateNameForUserMsgs,
          callee.getTemplateName());
    }
  }

  @Nullable
  private TemplateMetadata getDefault(Iterable<TemplateMetadata> templates) {
    for (TemplateMetadata callee : templates) {
      if (callee.getModName() == null && isNullOrEmpty(callee.getDelTemplateVariant())) {
        return callee;
      }
    }
    return null;
  }

  private void checkCallDelegateNode(
      CallDelegateNode node,
      LocalVariables localVariables,
      DelTemplateSelector<TemplateMetadata> fileSetDelTemplateSelector) {
    String delCalleeName = node.getDelCalleeName();
    TemplateMetadata defaultTemplate =
        getDefault(fileSetDelTemplateSelector.delTemplateNameToValues().get(delCalleeName));
    if (defaultTemplate != null && defaultTemplate.getTemplateType().isModifiable()) {
      checkLegacyNamespaceForCall(node, defaultTemplate);
    }
    checkForVariableCollision(node, localVariables, delCalleeName);
  }

  private void checkLegacyNamespaceForCall(CallDelegateNode node, TemplateMetadata defaultTemplate) {
    if (defaultTemplate.getTemplateType().getLegacyDeltemplateNamespace().equals(node.getDelCalleeName())) {
      return;
    }
    errorReporter.report(node.getSourceLocation(), CANNOT_DELCALL_WITHOUT_LEGACY_NAMESPACE);
  }

  private void checkForVariableCollision(CallDelegateNode node, LocalVariables localVariables, String delCalleeName) {
    VarDefn collision = localVariables.lookup(delCalleeName);
    if (collision != null) {
      checkCollisionType(node, collision);
    }
  }

  private void checkCollisionType(CallDelegateNode node, VarDefn collision) {
    if (collision.kind() == Kind.TEMPLATE
        || (collision.kind() == Kind.IMPORT_VAR
            && collision.hasType()
            && collision.type().getKind() == SoyType.Kind.TEMPLATE_TYPE)) {
      errorReporter.report(
          node.getSourceLocation(),
          DELCALL_TO_BASIC_TEMPLATE,
          collision.nameLocation().toLineColumnString());
    }
  }

  private static String getInconsistentParamMessage(
      Set<Parameter> missingParamSet, Set<Parameter> unexpectedParamSet) {
    StringBuilder message = new StringBuilder();
    if (!missingParamSet.isEmpty()) {
      message.append(String.format("\n  Missing params: %s", formatParamSet(missingParamSet)));
    }
    if (!unexpectedParamSet.isEmpty()) {
      message.append(
          String.format("\n  Unexpected params: %s", formatParamSet(unexpectedParamSet)));
    }
    return message.toString();
  }

  private static Set<String> formatParamSet(Set<Parameter> paramSet) {
    return paramSet.stream()
        .map(
            (param) -> {
              String formattedParam = param.getName() + ": " + param.getType();
              formattedParam += param.isRequired() ? "" : " (optional)";
              return formattedParam;
            })
        .collect(Collectors.toSet());
  }

  private static Set<Parameter> getRequiredParamsDifference(
      List<Parameter> paramList1, List<Parameter> paramList2) {
    Map<String, Parameter> nameToParamMap =
        paramList2.stream()
            .map(Parameter::toComparable)
            .collect(toImmutableMap(Parameter::getName, param -> param));

    return paramList1.stream()
        .filter(
            (param) -> {
              String paramName = param.getName();
              if (!nameToParamMap.containsKey(paramName)) {
                return param.isRequired();
              }
              Parameter param2 = nameToParamMap.get(paramName);
              return !param.equals(param2) && (param.isRequired() || param2.isRequired());
            })
        .collect(Collectors.toSet());
  }
}
