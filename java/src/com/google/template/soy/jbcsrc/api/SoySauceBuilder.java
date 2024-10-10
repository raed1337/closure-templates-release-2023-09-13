
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

package com.google.template.soy.jbcsrc.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.template.soy.jbcsrc.shared.CompiledTemplates;
import com.google.template.soy.jbcsrc.shared.Names;
import com.google.template.soy.plugin.java.PluginInstances;
import com.google.template.soy.shared.internal.InternalPlugins;
import com.google.template.soy.shared.internal.SoyScopedData;
import com.google.template.soy.shared.internal.SoySimpleScope;
import com.google.template.soy.shared.restricted.SoyFunction;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.Supplier;

/** Constructs {@link SoySauce} implementations. */
public final class SoySauceBuilder {
  private ImmutableList<SoyFunction> userFunctions = ImmutableList.of();
  private ImmutableList<SoyPrintDirective> userDirectives = ImmutableList.of();
  private PluginInstances userPluginInstances = PluginInstances.empty();
  private CompiledTemplates.Factory compiledTemplatesFactory = CompiledTemplates::new;
  private ClassLoader loader;

  public SoySauceBuilder() {}

  @CanIgnoreReturnValue
  public SoySauceBuilder withPluginInstances(Map<String, ? extends Supplier<Object>> pluginInstances) {
    this.userPluginInstances = PluginInstances.of(pluginInstances);
    return this;
  }

  @CanIgnoreReturnValue
  public SoySauceBuilder withClassLoader(ClassLoader loader) {
    this.loader = loader;
    return this;
  }

  @CanIgnoreReturnValue
  SoySauceBuilder withFunctions(Iterable<? extends SoyFunction> userFunctions) {
    this.userFunctions = InternalPlugins.filterDuplicateFunctions(userFunctions);
    return this;
  }

  @CanIgnoreReturnValue
  SoySauceBuilder withDirectives(Iterable<? extends SoyPrintDirective> userDirectives) {
    this.userDirectives = InternalPlugins.filterDuplicateDirectives(userDirectives);
    return this;
  }

  @CanIgnoreReturnValue
  SoySauceBuilder withCustomCompiledTemplatesFactory(CompiledTemplates.Factory compiledTemplatesFactory) {
    this.compiledTemplatesFactory = compiledTemplatesFactory;
    return this;
  }

  public SoySauce build() {
    SoyScopedData scopedData = new SoySimpleScope();
    if (loader == null) {
      loader = SoySauceBuilder.class.getClassLoader();
    }
    return new SoySauceImpl(
        compiledTemplatesFactory.create(readDelTemplatesFromMetaInf(loader), loader),
        scopedData.enterable(),
        userFunctions,
        buildUserDirectives(scopedData),
        userPluginInstances);
  }

  private ImmutableList<SoyPrintDirective> buildUserDirectives(SoyScopedData scopedData) {
    return ImmutableList.<SoyPrintDirective>builder()
        .addAll(InternalPlugins.internalDirectives(scopedData))
        .addAll(userDirectives)
        .build();
  }

  private static ImmutableSet<String> readDelTemplatesFromMetaInf(ClassLoader loader) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    try {
      Enumeration<URL> resources = loader.getResources(Names.META_INF_DELTEMPLATE_PATH);
      while (resources.hasMoreElements()) {
        URL url = resources.nextElement();
        readResourceLines(url, builder);
      }
      return builder.build();
    } catch (IOException iox) {
      throw new RuntimeException("Unable to read deltemplate listing", iox);
    }
  }

  private static void readResourceLines(URL url, ImmutableSet.Builder<String> builder) throws IOException {
    try (InputStream in = url.openStream();
         BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        builder.add(line);
      }
    }
  }
}
