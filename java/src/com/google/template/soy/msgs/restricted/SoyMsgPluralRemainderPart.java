
/*
 * Copyright 2010 Google Inc.
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

package com.google.template.soy.msgs.restricted;

import java.util.Objects;

/**
 * Represents the placeholder part in the plural statement (The '#' sign in ICU syntax).
 */
public final class SoyMsgPluralRemainderPart extends SoyMsgPart {

  /** The plural variable name. */
  private final String pluralVarName;

  /** @param pluralVarName The plural variable name. */
  public SoyMsgPluralRemainderPart(String pluralVarName) {
    this.pluralVarName = Objects.requireNonNull(pluralVarName, "pluralVarName cannot be null");
  }

  public String getPluralVarName() {
    return pluralVarName;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof SoyMsgPluralRemainderPart)) return false;
    SoyMsgPluralRemainderPart that = (SoyMsgPluralRemainderPart) other;
    return pluralVarName.equals(that.pluralVarName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pluralVarName);
  }

  @Override
  public String toString() {
    return String.format("Remainder{pluralVarName='%s'}", pluralVarName);
  }
}
