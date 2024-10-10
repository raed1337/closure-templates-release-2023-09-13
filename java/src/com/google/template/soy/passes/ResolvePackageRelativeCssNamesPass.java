
/*
 * Copyright 2014 Google Inc.
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

import com.google.common.collect.Iterables;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.QuoteStyle;
import com.google.template.soy.css.CssPrefixUtil;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.exprtree.StringNode;
import com.google.template.soy.shared.internal.BuiltinFunction;
import com.google.template.soy.soytree.ConstNode;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateNode;
import javax.annotation.Nullable;

/** Converts package-relative CSS class names to absolute names. */
final class ResolvePackageRelativeCssNamesPass implements CompilerFilePass {

  private static final String RELATIVE_SELECTOR_PREFIX = "%";

  private static final SoyErrorKind PACKAGE_RELATIVE_CLASS_NAME_USED_WITH_COMPONENT_NAME =
      SoyErrorKind.of(
          "Package-relative class name ''{0}'' cannot be used with component expression.");
  private static final SoyErrorKind NO_CSS_PACKAGE =
      SoyErrorKind.of(
          "No CSS package defined for package-relative class name ''{0}''. "
              + "CSS package prefixes are set via the ''cssbase'' attribute on the template, a "
              + "''cssbase'' attribute on the namespace, or the first ''requirecss'' package on "
              + "the namespace.{1}.");

  private final ErrorReporter errorReporter;

  ResolvePackageRelativeCssNamesPass(ErrorReporter errorReporter) {
    this.errorReporter = errorReporter;
  }

  @Override
  public void run(SoyFileNode file, IdGenerator nodeIdGen) {
    String namespacePrefix = CssPrefixUtil.getNamespacePrefix(file);
    processTemplates(file, namespacePrefix);
    processConstants(file, namespacePrefix);
  }

  private void processTemplates(SoyFileNode file, String namespacePrefix) {
    for (TemplateNode template : file.getTemplates()) {
      String templatePrefix = CssPrefixUtil.getTemplatePrefix(template, namespacePrefix);
      SoyTreeUtils.allFunctionInvocations(template, BuiltinFunction.CSS)
          .forEach(fn -> resolveSelector(template, fn, templatePrefix));
    }
  }

  private void processConstants(SoyFileNode file, String namespacePrefix) {
    for (ConstNode constNode : file.getConstants()) {
      SoyTreeUtils.allFunctionInvocations(constNode, BuiltinFunction.CSS)
          .forEach(fn -> resolveSelector(constNode, fn, namespacePrefix));
    }
  }

  private void resolveSelector(
      SoyNode templateOrConstant, FunctionNode node, @Nullable String packagePrefix) {
    ExprNode lastChild = Iterables.getLast(node.getChildren(), null);
    if (!(lastChild instanceof StringNode)) {
      return;
    }

    StringNode selector = (StringNode) lastChild;
    String selectorText = selector.getValue();
    if (!selectorText.startsWith(RELATIVE_SELECTOR_PREFIX)) {
      return;
    }

    validateSelectorUsage(selector, node, templateOrConstant, selectorText, packagePrefix);
    replaceSelector(node, selector, packagePrefix, selectorText);
  }

  private void validateSelectorUsage(StringNode selector, FunctionNode node,
                                      SoyNode templateOrConstant, String selectorText,
                                      @Nullable String packagePrefix) {
    if (node.numChildren() > 1) {
      errorReporter.report(
          selector.getSourceLocation(),
          PACKAGE_RELATIVE_CLASS_NAME_USED_WITH_COMPONENT_NAME,
          selectorText);
    }

    if (packagePrefix == null && templateOrConstant instanceof TemplateNode) {
      TemplateNode template = (TemplateNode) templateOrConstant;
      errorReporter.report(
          selector.getSourceLocation(),
          NO_CSS_PACKAGE,
          selectorText,
          template.getRequiredCssNamespaces().isEmpty()
              ? ""
              : " NOTE" + ": ''requirecss'' on a template is not used to infer the CSS package.");
    }
  }

  private void replaceSelector(FunctionNode node, StringNode selector,
                               @Nullable String packagePrefix, String selectorText) {
    String prefixed = packagePrefix + selectorText.substring(RELATIVE_SELECTOR_PREFIX.length());
    StringNode newSelector =
        new StringNode(prefixed, QuoteStyle.SINGLE, selector.getSourceLocation());
    node.replaceChild(selector, newSelector);
  }
}
