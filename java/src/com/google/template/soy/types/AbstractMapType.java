
/*
 * Copyright 2017 Google Inc.
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

package com.google.template.soy.types;

/** Abstract base class for {@link LegacyObjectMapType} and {@link MapType}. */
public abstract class AbstractMapType extends SoyType {
  
  /** Returns the type for keys of this map. */
  public abstract SoyType getKeyType();

  /** Returns the type for values in this map. */
  public abstract SoyType getValueType();

  /** 
   * Utility method to check if key and value types are not null. 
   * This improves the safety of the map type by ensuring valid types.
   */
  public boolean hasValidTypes() {
    return getKeyType() != null && getValueType() != null;
  }
}
