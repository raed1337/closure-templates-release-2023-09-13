
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

package com.google.template.soy.shared.internal;

import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SoyMap;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.internal.SoyMapImpl;
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.FloatData;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.NumberData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.exprtree.MapLiteralFromListNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import javax.annotation.Nonnull;

/**
 * Runtime implementation of common expression operators to be shared between the {@code jbcsrc} and
 * {@code Tofu} backends.
 */
public final class SharedRuntime {

  public static boolean equal(SoyValue operand0, SoyValue operand1) {
    if (isStringComparison(operand0, operand1)) return compareString(getStringValue(operand0), operand1);
    if (isStringComparison(operand1, operand0)) return compareString(getStringValue(operand1), operand0);
    if (isBothNull(operand0, operand1)) return true;
    return Objects.equals(operand0, operand1);
  }

  public static boolean tripleEqual(SoyValue operand0, SoyValue operand1) {
    if (operand0 instanceof BooleanData && operand1 instanceof BooleanData) {
      return operand0.booleanValue() == operand1.booleanValue();
    }
    if (operand0 instanceof NumberData && operand1 instanceof NumberData) {
      return operand0.numberValue() == operand1.numberValue();
    }
    if (operand0 instanceof StringData && operand1 instanceof StringData) {
      return operand0.stringValue().equals(operand1.stringValue());
    }
    return operand0 == operand1;
  }

  public static boolean switchCaseEqual(SoyValue operand0, SoyValue operand1) {
    operand0 = sanitizeIfNecessary(operand0);
    operand1 = sanitizeIfNecessary(operand1);
    return tripleEqual(operand0, operand1);
  }

  @Nonnull
  public static SoyValue plus(SoyValue operand0, SoyValue operand1) {
    if (isIntegerPair(operand0, operand1)) {
      return IntegerData.forValue(operand0.longValue() + operand1.longValue());
    } else if (isNumberPair(operand0, operand1)) {
      return FloatData.forValue(operand0.numberValue() + operand1.numberValue());
    }
    return StringData.forValue(operand0.coerceToString() + operand1.coerceToString());
  }

  @Nonnull
  public static SoyValue minus(SoyValue operand0, SoyValue operand1) {
    if (isIntegerPair(operand0, operand1)) {
      return IntegerData.forValue(operand0.longValue() - operand1.longValue());
    }
    return FloatData.forValue(operand0.numberValue() - operand1.numberValue());
  }

  @Nonnull
  public static NumberData times(SoyValue operand0, SoyValue operand1) {
    if (isIntegerPair(operand0, operand1)) {
      return IntegerData.forValue(operand0.longValue() * operand1.longValue());
    }
    return FloatData.forValue(operand0.numberValue() * operand1.numberValue());
  }

  public static double dividedBy(SoyValue operand0, SoyValue operand1) {
    return operand0.numberValue() / operand1.numberValue();
  }

  @Nonnull
  public static NumberData mod(SoyValue operand0, SoyValue operand1) {
    if (isIntegerPair(operand0, operand1)) {
      return IntegerData.forValue(operand0.longValue() % operand1.longValue());
    }
    return FloatData.forValue(operand0.numberValue() % operand1.numberValue());
  }

  @Nonnull
  public static NumberData shiftRight(SoyValue operand0, SoyValue operand1) {
    return IntegerData.forValue(operand0.longValue() >> (int) operand1.longValue());
  }

  @Nonnull
  public static NumberData shiftLeft(SoyValue operand0, SoyValue operand1) {
    return IntegerData.forValue(operand0.longValue() << (int) operand1.longValue());
  }

  @Nonnull
  public static NumberData bitwiseOr(SoyValue operand0, SoyValue operand1) {
    return IntegerData.forValue(operand0.longValue() | operand1.longValue());
  }

  @Nonnull
  public static NumberData bitwiseXor(SoyValue operand0, SoyValue operand1) {
    return IntegerData.forValue(operand0.longValue() ^ operand1.longValue());
  }

  @Nonnull
  public static NumberData bitwiseAnd(SoyValue operand0, SoyValue operand1) {
    return IntegerData.forValue(operand0.longValue() & operand1.longValue());
  }

  public static boolean lessThan(SoyValue left, SoyValue right) {
    return compareValues(left, right, (l, r) -> l < r);
  }

  public static boolean lessThanOrEqual(SoyValue left, SoyValue right) {
    return compareValues(left, right, (l, r) -> l <= r);
  }

  @Nonnull
  public static NumberData negative(SoyValue node) {
    return (node instanceof IntegerData) ? 
        IntegerData.forValue(-node.longValue()) : 
        FloatData.forValue(-node.floatValue());
  }

  public static boolean compareString(String string, SoyValue other) {
    if (other instanceof StringData || other instanceof SanitizedContent) {
      return string.equals(other.toString());
    }
    if (other instanceof NumberData) {
      try {
        return Double.parseDouble(string) == other.numberValue();
      } catch (NumberFormatException nfe) {
        return false;
      }
    }
    return false;
  }

  @Nonnull
  public static String soyServerKey(SoyValue key) {
    if (key instanceof NumberData) {
      return serialize(key.coerceToString(), "#");
    }
    return serialize(key == null ? "null" : key.coerceToString(), key == null ? "_" : ":");
  }

  @Nonnull
  public static SoyMap constructMapFromList(List<? extends SoyValueProvider> list) {
    Map<SoyValue, SoyValueProvider> map = new HashMap<>();
    for (int i = 0; i < list.size(); i++) {
      SoyValue recordEntry = list.get(i).resolve();
      checkMapFromListConstructorCondition(recordEntry instanceof SoyRecord, recordEntry, OptionalInt.of(i));
      checkMapFromListConstructorCondition(
          ((SoyRecord) recordEntry).hasField(MapLiteralFromListNode.KEY_STRING)
              && ((SoyRecord) recordEntry).hasField(MapLiteralFromListNode.VALUE_STRING),
          recordEntry,
          OptionalInt.of(i));
      SoyValue key = ((SoyRecord) recordEntry).getField(MapLiteralFromListNode.KEY_STRING);
      SoyValueProvider valueProvider =
          ((SoyRecord) recordEntry).getFieldProvider(MapLiteralFromListNode.VALUE_STRING);
      checkMapFromListConstructorCondition(SoyMap.isAllowedKeyType(key), recordEntry, OptionalInt.of(i));
      map.put(key, valueProvider);
    }
    return SoyMapImpl.forProviderMap(map);
  }

  public static void checkMapFromListConstructorCondition(
      boolean condition, SoyValue list, OptionalInt index) {
    if (!condition) {
      String exceptionString = String.format(
          "Error constructing map. Expected a list where each item is a record of 'key',"
              + " 'value' pairs, with the 'key' fields holding primitive values. Found: %s", list);
      if (index.isPresent()) {
        exceptionString += String.format(" at index %d", index.getAsInt());
      }
      throw new IllegalArgumentException(exceptionString);
    }
  }

  private static String serialize(String key, String delimiter) {
    return key.length() + delimiter + key;
  }

  private static boolean isStringComparison(SoyValue operand, SoyValue other) {
    return operand instanceof StringData;
  }

  private static String getStringValue(SoyValue operand) {
    return operand instanceof StringData ? operand.stringValue() : operand.toString();
  }

  private static boolean isBothNull(SoyValue operand0, SoyValue operand1) {
    return (operand0 == null || operand0.isNullish()) && (operand1 == null || operand1.isNullish());
  }
  
  private static SoyValue sanitizeIfNecessary(SoyValue operand) {
    return (operand instanceof SanitizedContent) ? StringData.forValue(operand.toString()) : operand;
  }

  private static boolean isIntegerPair(SoyValue operand0, SoyValue operand1) {
    return operand0 instanceof IntegerData && operand1 instanceof IntegerData;
  }

  private static boolean isNumberPair(SoyValue operand0, SoyValue operand1) {
    return operand0 instanceof NumberData && operand1 instanceof NumberData;
  }

  private static boolean compareValues(SoyValue left, SoyValue right, java.util.function.BiPredicate<Double, Double> comparator) {
    if (left instanceof StringData && right instanceof StringData) {
      return left.stringValue().compareTo(right.stringValue()) < 0;
    } else if (left instanceof IntegerData && right instanceof IntegerData) {
      return comparator.test((double) left.longValue(), (double) right.longValue());
    } else {
      return comparator.test(left.numberValue(), right.numberValue());
    }
  }

  private SharedRuntime() {}
}
