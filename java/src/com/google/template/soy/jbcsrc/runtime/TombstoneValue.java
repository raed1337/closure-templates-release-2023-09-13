
/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.jbcsrc.runtime;

import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.SoyAbstractValue;

/**
 * A simple Tombstone SoyValue for state transitions in our SoyValueProvider subtypes.
 *
 * <p>This should never be exposed to users or end up being accessed via a template.
 */
final class TombstoneValue extends SoyAbstractValue {
  static final TombstoneValue INSTANCE = new TombstoneValue();

  /**
   * Renders the TombstoneValue. This operation is not supported as TombstoneValue
   * is not intended to be rendered. Throws UnsupportedOperationException.
   *
   * @param appendable the appendable to which the output would be rendered
   * @throws UnsupportedOperationException if this method is called
   */
  @Override
  public void render(LoggingAdvisingAppendable appendable) {
    throw new UnsupportedOperationException();
  }

  /**
   * Compares this TombstoneValue to another object for equality. This operation is not supported
   * as TombstoneValue does not have a meaningful equality comparison. Throws UnsupportedOperationException.
   *
   * @param other the object to compare with
   * @return false
   * @throws UnsupportedOperationException if this method is called
   */
  @Override
  public boolean equals(Object other) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the hash code for this TombstoneValue. This operation is not supported as
   * TombstoneValue does not have a meaningful hash code. Throws UnsupportedOperationException.
   *
   * @return 0
   * @throws UnsupportedOperationException if this method is called
   */
  @Override
  public int hashCode() {
    throw new UnsupportedOperationException();
  }

  /**
   * Coerces this TombstoneValue to a String. This operation is not supported as
   * TombstoneValue does not have a meaningful string representation. Throws UnsupportedOperationException.
   *
   * @return an empty string
   * @throws UnsupportedOperationException if this method is called
   */
  @Override
  public String coerceToString() {
    throw new UnsupportedOperationException();
  }

  /**
   * Coerces this TombstoneValue to a boolean. This operation is not supported as
   * TombstoneValue cannot be meaningfully coerced to a boolean. Throws UnsupportedOperationException.
   *
   * @return false
   * @throws UnsupportedOperationException if this method is called
   */
  @Override
  public boolean coerceToBoolean() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String toString() {
    return "TOMBSTONE";
  }

  private TombstoneValue() {}
}
