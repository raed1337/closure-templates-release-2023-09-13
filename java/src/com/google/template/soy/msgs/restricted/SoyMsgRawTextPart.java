
/*
 * Copyright 2008 Google Inc.
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

import com.google.common.base.Preconditions;

/** Represents a raw text string within a message (the stuff that translators change). */
public final class SoyMsgRawTextPart extends SoyMsgPart {
  private final String rawText;

  /** Returns a SoyMsgRawTextPart representing the specified raw text string. */
  public static SoyMsgRawTextPart of(String rawText) {
    return new SoyMsgRawTextPart(Preconditions.checkNotNull(rawText, "rawText cannot be null"));
  }

  private SoyMsgRawTextPart(String rawText) {
    this.rawText = rawText;
  }

  /** Returns the raw text string. */
  public String getRawText() {
    return rawText;
  }

  @Override
  public String toString() {
    return rawText;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof SoyMsgRawTextPart)) return false;
    SoyMsgRawTextPart that = (SoyMsgRawTextPart) other;
    return rawText.equals(that.rawText);
  }

  @Override
  public int hashCode() {
    return rawText.hashCode();
  }
}
