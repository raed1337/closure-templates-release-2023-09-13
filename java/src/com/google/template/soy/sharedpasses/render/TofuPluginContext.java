
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

package com.google.template.soy.sharedpasses.render;

import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.plugin.java.restricted.JavaPluginContext;
import com.google.template.soy.plugin.java.restricted.JavaValue;
import com.ibm.icu.util.ULocale;
import javax.annotation.Nullable;

/** Exposes plugin context for Tofu, based on a SoyMsgBundle. */
final class TofuPluginContext implements JavaPluginContext {
  @Nullable private final SoyMsgBundle msgBundle;

  public TofuPluginContext(@Nullable SoyMsgBundle msgBundle) {
    this.msgBundle = msgBundle;
  }

  @Override
  public TofuJavaValue getBidiDir() {
    return TofuJavaValue.forRaw(getBidiGlobalDir());
  }

  public BidiGlobalDir getBidiGlobalDir() {
    return BidiGlobalDir.forStaticIsRtl(msgBundle != null && msgBundle.isRtl());
  }

  @Override
  public TofuJavaValue getULocale() {
    ULocale locale = (msgBundle == null) ? ULocale.ENGLISH : msgBundle.getLocale();
    return TofuJavaValue.forRaw(locale);
  }

  @Override
  public TofuJavaValue getAllRequiredCssNamespaces(JavaValue template) {
    throw new UnsupportedOperationException(
        "Tofu does not support getting required CSS namespaces.");
  }

  @Override
  public TofuJavaValue getAllRequiredCssPaths(JavaValue template) {
    throw new UnsupportedOperationException("Tofu does not support getting required CSS paths.");
  }
}
