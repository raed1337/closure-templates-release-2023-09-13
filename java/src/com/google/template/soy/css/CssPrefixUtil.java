
/*
 * Copyright 2023 Google Inc.
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

package com.google.template.soy.css;

import com.google.common.base.CaseFormat;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.TemplateNode;
import javax.annotation.Nullable;

/** Utilities related to resolving the '%' prefix in the built-in css() function. */
public class CssPrefixUtil {

  private CssPrefixUtil() {}

  @Nullable
  public static String getNamespacePrefix(SoyFileNode file) {
    String cssPrefix = getCssPrefix(file);
    if (cssPrefix != null) {
      return cssPrefix;
    }
    return getBaseNamespaceOrRequired(file);
  }

  @Nullable
  private static String getCssPrefix(SoyFileNode file) {
    return file.getCssPrefix();
  }

  @Nullable
  private static String getBaseNamespaceOrRequired(SoyFileNode file) {
    if (file.getCssBaseNamespace() != null) {
      return toCamelCase(file.getCssBaseNamespace());
    } else if (!file.getRequiredCssNamespaces().isEmpty()) {
      return toCamelCase(file.getRequiredCssNamespaces().get(0));
    }
    return null;
  }

  @Nullable
  public static String getTemplatePrefix(TemplateNode template, @Nullable String namespacePrefix) {
    return template.getCssBaseNamespace() != null 
        ? toCamelCase(template.getCssBaseNamespace()) 
        : namespacePrefix;
  }

  private static String toCamelCase(String packageName) {
    String packageNameWithDashes = packageName.replace('.', '-');
    return CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, packageNameWithDashes);
  }
}
