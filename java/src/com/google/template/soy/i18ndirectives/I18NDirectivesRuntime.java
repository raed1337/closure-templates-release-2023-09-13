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

package com.google.template.soy.i18ndirectives;

import static java.util.Comparator.comparing;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.SoyList;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.restricted.NumberData;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.CompactDecimalFormat;
import com.ibm.icu.text.CompactDecimalFormat.CompactStyle;
import com.ibm.icu.text.NumberFormat;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.util.ULocale;
import javax.annotation.Nullable;

/** Java implementations of the i18n directives. */
public final class I18NDirectivesRuntime {

  private I18NDirectivesRuntime() {}

  public static String formatNum(
      ULocale uLocale,
      @Nullable SoyValue number,
      String formatType,
      String numbersKeyword,
      @Nullable NumberData minFractionDigits,
      @Nullable NumberData maxFractionDigits) {
    if (number == null) {
      return "";
    } else if (number instanceof NumberData) {
      return formatInternal(
          uLocale,
          ((NumberData) number).toFloat(),
          formatType,
          numbersKeyword,
          minFractionDigits != null ? (int) minFractionDigits.numberValue() : null,
          maxFractionDigits != null ? (int) maxFractionDigits.numberValue() : null);
    } else {
      return "NaN";
    }
  }

  public static String format(Object number, ULocale uLocale) {
    return format(number, "decimal", null, null, null, uLocale);
  }

  public static String format(Object number, String formatType, ULocale uLocale) {
    return format(number, formatType, null, null, null, uLocale);
  }

  public static String format(
      Object number, String formatType, String numbersKeyword, ULocale uLocale) {
    return format(number, formatType, numbersKeyword, null, null, uLocale);
  }

  public static String format(
      Object number,
      String formatType,
      String numbersKeyword,
      Integer minFractionDigits,
      ULocale uLocale) {
    return format(number, formatType, numbersKeyword, minFractionDigits, null, uLocale);
  }

  public static String format(
      Object number,
      String formatType,
      String numbersKeyword,
      Integer minFractionDigits,
      Integer maxFractionDigits,
      ULocale uLocale) {
    if (number == null) {
      return "";
    }
    if (number instanceof Number) {
      Double val;
      if (number instanceof Double) {
        val = (Double) number;
      } else if (number instanceof Long) {
        val = ((Long) number).doubleValue();
      } else if (number instanceof Integer) {
        val = ((Integer) number).doubleValue();
      } else {
        val = Double.parseDouble(number.toString());
      }
      return formatInternal(
          uLocale, val, formatType, numbersKeyword, minFractionDigits, maxFractionDigits);
    }
    return "NaN";
  }

  /**
   * Formats a number using ICU4J. Note: If min or max fraction digits is null, the param will be
   * ignored.
   */
  private static String formatInternal(
      ULocale uLocale,
      double number,
      String formatType,
      String numbersKeyword,
      @Nullable Integer minFractionDigits,
      @Nullable Integer maxFractionDigits) {
    uLocale = uLocale.setKeywordValue("numbers", numbersKeyword);
    NumberFormat numberFormat;
    switch (formatType) {
      case "decimal":
        numberFormat = NumberFormat.getInstance(uLocale);
        break;
      case "percent":
        numberFormat = NumberFormat.getPercentInstance(uLocale);
        break;
      case "currency":
        numberFormat = NumberFormat.getCurrencyInstance(uLocale);
        break;
      case "scientific":
        numberFormat = NumberFormat.getScientificInstance(uLocale);
        break;
      case "compact_short":
        numberFormat = CompactDecimalFormat.getInstance(uLocale, CompactStyle.SHORT);
        break;
      case "compact_long":
        numberFormat = CompactDecimalFormat.getInstance(uLocale, CompactStyle.LONG);
        break;
      default:
        throw new IllegalArgumentException(
            "First argument to formatNum must be "
                + "constant, and one of: 'decimal', 'currency', 'percent', 'scientific', "
                + "'compact_short', or 'compact_long'.");
    }

    if (minFractionDigits != null || maxFractionDigits != null) {
      if (maxFractionDigits == null) {
        maxFractionDigits = minFractionDigits;
      }
      if (minFractionDigits != null) {
        numberFormat.setMinimumFractionDigits(minFractionDigits);
      }
      numberFormat.setMaximumFractionDigits(maxFractionDigits);
    } else if (numberFormat instanceof CompactDecimalFormat) {
      ((CompactDecimalFormat) numberFormat).setMaximumSignificantDigits(3);
    }

    return numberFormat.format(number);
  }

  public static ImmutableList<SoyValueProvider> localeSort(
      ULocale uLocale, SoyList list, @Nullable SoyRecord options) {
    RuleBasedCollator collator = (RuleBasedCollator) Collator.getInstance(uLocale);
    if (options != null) {
      if (options.hasField("numeric")) {
        collator.setNumericCollation(options.getField("numeric").booleanValue());
      }
      if (options.hasField("caseFirst")) {
        String caseFirst = options.getField("caseFirst").stringValue();
        if ("upper".equals(caseFirst)) {
          collator.setUpperCaseFirst(true);
        } else if ("lower".equals(caseFirst)) {
          collator.setLowerCaseFirst(true);
        }
      }
      if (options.hasField("sensitivity")) {
        String sensitivity = options.getField("sensitivity").stringValue();
        switch (sensitivity) {
          case "base":
            collator.setStrength(Collator.PRIMARY);
            break;
          case "accent":
            collator.setStrength(Collator.SECONDARY);
            break;
          case "case":
            collator.setStrength(Collator.TERTIARY);
            break;
          case "variant":
            collator.setStrength(Collator.IDENTICAL);
            break;
          default:
            throw new IllegalArgumentException("Bad value for sensitivity: " + sensitivity);
        }
      } else {
        // Match Intl.Collator default sensitivity (variant).
        collator.setStrength(Collator.IDENTICAL);
      }
    }
    return ImmutableList.sortedCopyOf(
        comparing((SoyValueProvider arg) -> arg.resolve().stringValue(), collator),
        list.asJavaList());
  }
}
