
/*
 * Copyright 2013 Google Inc.
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

import java.util.List;

/**
 * Utilities for dealing with msg parts.
 */
public class MsgPartUtils {

  private MsgPartUtils() {}

  /**
   * Checks whether a given list of msg parts has any plural or select parts.
   *
   * @param msgParts The msg parts to check.
   * @return Whether there are any plural or select parts.
   */
  public static boolean hasPlrselPart(List<SoyMsgPart> msgParts) {
    if (isSinglePlrselPart(msgParts)) {
      return true;
    }
    return false;
  }

  /**
   * Determines if the list contains a single plural or select message part.
   *
   * @param msgParts The msg parts to check.
   * @return True if the list contains one and only one plural or select part; otherwise false.
   */
  private static boolean isSinglePlrselPart(List<SoyMsgPart> msgParts) {
    if (msgParts.size() == 1) {
      SoyMsgPart rootPart = msgParts.get(0);
      return rootPart instanceof SoyMsgPluralPart || rootPart instanceof SoyMsgSelectPart;
    }
    return false;
  }
}
