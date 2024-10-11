
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Represents a plural statement within a message.
 */
public final class SoyMsgPluralPart extends SoyMsgPart {

  /** The plural variable name. */
  private final String pluralVarName;

  /** The offset. */
  private final int offset;

  /** The various cases for this plural statement. The default statement has a null key. */
  private final ImmutableList<Case<SoyMsgPluralCaseSpec>> cases;

  /**
   * @param pluralVarName The plural variable name.
   * @param offset The offset for this plural statement.
   * @param cases The list of cases for this plural statement.
   */
  public SoyMsgPluralPart(
      String pluralVarName, int offset, Iterable<Case<SoyMsgPluralCaseSpec>> cases) {

    this.pluralVarName = pluralVarName;
    this.offset = offset;
    this.cases = ImmutableList.copyOf(cases);
  }

  /** Returns the plural variable name. */
  public String getPluralVarName() {
    return pluralVarName;
  }

  /** Returns the offset. */
  public int getOffset() {
    return offset;
  }

  /** Returns the cases. */
  public ImmutableList<Case<SoyMsgPluralCaseSpec>> getCases() {
    return cases;
  }

  /**
   * Returns the list of parts to implement the case.
   *
   * @param pluralValue The current plural value
   * @param locale The locale for interpreting non-specific plural parts. Allowed to be null if it
   *     is known that there are no non-specific plural parts (This is commonly the case for default
   *     messages, since soy only allows direct specification of explicit or 'other').
   */
  public ImmutableList<SoyMsgPart> lookupCase(double pluralValue, @Nullable ULocale locale) {
    ImmutableList<SoyMsgPart> caseParts = findExplicitCase(pluralValue);
    if (caseParts != null) {
      return checkNotNull(caseParts);
    }

    String pluralKeyword = PluralRules.forLocale(locale).select(pluralValue - offset);
    SoyMsgPluralCaseSpec.Type correctCaseType = SoyMsgPluralCaseSpec.forType(pluralKeyword).getType();
    caseParts = findNonExplicitCase(correctCaseType);

    if (caseParts == null) {
      caseParts = findOtherCase();
    }
    return checkNotNull(caseParts);
  }

  private ImmutableList<SoyMsgPart> findExplicitCase(double pluralValue) {
    for (Case<SoyMsgPluralCaseSpec> case0 : getCases()) {
      SoyMsgPluralCaseSpec pluralCaseSpec = case0.spec();
      if (pluralCaseSpec.getType() == SoyMsgPluralCaseSpec.Type.EXPLICIT &&
          pluralCaseSpec.getExplicitValue() == pluralValue) {
        return case0.parts();
      }
    }
    return null;
  }

  private ImmutableList<SoyMsgPart> findNonExplicitCase(SoyMsgPluralCaseSpec.Type correctCaseType) {
    for (Case<SoyMsgPluralCaseSpec> case0 : getCases()) {
      if (case0.spec().getType() == correctCaseType) {
        return case0.parts();
      }
    }
    return null;
  }

  private ImmutableList<SoyMsgPart> findOtherCase() {
    for (Case<SoyMsgPluralCaseSpec> case0 : getCases()) {
      if (case0.spec().getType() == SoyMsgPluralCaseSpec.Type.OTHER) {
        return case0.parts();
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof SoyMsgPluralPart)) {
      return false;
    }
    SoyMsgPluralPart otherPlural = (SoyMsgPluralPart) other;
    return offset == otherPlural.offset
        && pluralVarName.equals(otherPlural.pluralVarName)
        && cases.equals(otherPlural.cases);
  }

  @Override
  public int hashCode() {
    return Objects.hash(SoyMsgPluralPart.class, offset, pluralVarName, cases);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper("Plural")
        .omitNullValues()
        .addValue(pluralVarName)
        .add("cases", cases)
        .add("offset", offset == 0 ? null : Integer.toString(offset))
        .toString();
  }
}
