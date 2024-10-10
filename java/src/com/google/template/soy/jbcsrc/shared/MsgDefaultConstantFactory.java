
/*
 * Copyright 2021 Google Inc.
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
package com.google.template.soy.jbcsrc.shared;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Iterators.forArray;
import static com.google.common.collect.Iterators.peekingIterator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.PeekingIterator;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPart.Case;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralCaseSpec;
import com.google.template.soy.msgs.restricted.SoyMsgPluralPart;
import com.google.template.soy.msgs.restricted.SoyMsgPluralRemainderPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.msgs.restricted.SoyMsgSelectPart;
import java.lang.invoke.MethodHandles;
import java.util.function.Function;

/**
 * A {@code constantdynamic} bootstrap for handling msg constant defaults
 *
 * <p>In soy it is not unreasonable for there to be a very large number of msgs. To support missing
 * translations we need to encode defaults in the class file. A naive approach generates a lot of
 * code just to construct these defaults and requires a lot of constant fields
 *
 * <p>The benefit of using {@code constantdynamic} to construct these defaults as opposed to just
 * generating code that performs it inline is that we can ensure that the construction is only
 * performed once (and lazily) without needing to allocate {@code static final} fields to hold the
 * result. This saves on instructions and fields.
 *
 * <p>The downside is that we need to find an efficient way to pass the message to the bootstrap
 * method. For this we use a simple encoding/decoding routine to transform the objects into a form
 * suitable as constant bootstrap arguments.
 */
public final class MsgDefaultConstantFactory {
  
  private enum Tag {
    RAW, PLACEHOLDER, REMAINDER, BEGIN_PLURAL, BEGIN_SELECT, BEGIN_CASE, END;

    static Tag fromRaw(Object object) {
      return values()[(Integer) object];
    }
  }

  public static ImmutableList<Object> msgToPartsList(ImmutableList<SoyMsgPart> parts) {
    ImmutableList<Object> constantParts = partsToConstantPartsList(parts);
    return removeTrailingEndMarkers(constantParts);
  }

  private static ImmutableList<Object> removeTrailingEndMarkers(ImmutableList<Object> constantParts) {
    int lastElement = constantParts.size() - 1;
    while (lastElement >= 0 && constantParts.get(lastElement) instanceof Integer
        && ((Integer) constantParts.get(lastElement)) == Tag.END.ordinal()) {
      lastElement--;
    }
    return constantParts.subList(0, lastElement + 1);
  }

  private static ImmutableList<Object> partsToConstantPartsList(ImmutableList<SoyMsgPart> msgParts) {
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    for (SoyMsgPart msgPart : msgParts) {
      builder.addAll(partToConstantPartsList(msgPart));
    }
    return builder.build();
  }

  private static ImmutableList<Object> partToConstantPartsList(SoyMsgPart part) {
    if (part instanceof SoyMsgPlaceholderPart) {
      return handlePlaceholderPart((SoyMsgPlaceholderPart) part);
    } else if (part instanceof SoyMsgPluralPart) {
      return handlePluralPart((SoyMsgPluralPart) part);
    } else if (part instanceof SoyMsgPluralRemainderPart) {
      return handleRemainderPart((SoyMsgPluralRemainderPart) part);
    } else if (part instanceof SoyMsgRawTextPart) {
      return handleRawTextPart((SoyMsgRawTextPart) part);
    } else if (part instanceof SoyMsgSelectPart) {
      return handleSelectPart((SoyMsgSelectPart) part);
    } else {
      throw new AssertionError("unrecognized part: " + part);
    }
  }

  private static ImmutableList<Object> handlePlaceholderPart(SoyMsgPlaceholderPart part) {
    return ImmutableList.of(Tag.PLACEHOLDER.ordinal(), part.getPlaceholderName());
  }

  private static ImmutableList<Object> handlePluralPart(SoyMsgPluralPart part) {
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    builder.add(Tag.BEGIN_PLURAL.ordinal(), part.getPluralVarName(), part.getOffset());
    for (Case<SoyMsgPluralCaseSpec> item : part.getCases()) {
      builder.add(Tag.BEGIN_CASE.ordinal());
      builder.add(item.spec().getType() == SoyMsgPluralCaseSpec.Type.EXPLICIT
          ? item.spec().getExplicitValue()
          : item.spec().getType().name());
      builder.addAll(partsToConstantPartsList(item.parts()));
    }
    builder.add(Tag.END.ordinal());
    return builder.build();
  }

  private static ImmutableList<Object> handleRemainderPart(SoyMsgPluralRemainderPart part) {
    return ImmutableList.of(Tag.REMAINDER.ordinal(), part.getPluralVarName());
  }

  private static ImmutableList<Object> handleRawTextPart(SoyMsgRawTextPart part) {
    return ImmutableList.of(Tag.RAW.ordinal(), part.getRawText());
  }

  private static ImmutableList<Object> handleSelectPart(SoyMsgSelectPart part) {
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    builder.add(Tag.BEGIN_SELECT.ordinal(), part.getSelectVarName());
    for (Case<String> item : part.getCases()) {
      builder.add(Tag.BEGIN_CASE.ordinal());
      builder.add(nullToEmpty(item.spec()));
      builder.addAll(partsToConstantPartsList(item.parts()));
    }
    builder.add(Tag.END.ordinal());
    return builder.build();
  }

  public static ImmutableList<SoyMsgPart> bootstrapMsgConstant(
      MethodHandles.Lookup lookup, String name, Class<?> type, Object... rawParts) {
    PeekingIterator<Object> itr = peekingIterator(forArray(rawParts));
    ImmutableList<SoyMsgPart> parts = parseParts(itr);
    checkState(!itr.hasNext());
    return parts;
  }

  private static ImmutableList<SoyMsgPart> parseParts(PeekingIterator<Object> rawParts) {
    return parseParts(rawParts, false);
  }

  private static ImmutableList<SoyMsgPart> parseParts(PeekingIterator<Object> rawParts, boolean isCase) {
    ImmutableList.Builder<SoyMsgPart> parts = ImmutableList.builder();
    while (rawParts.hasNext()) {
      Tag tag = Tag.fromRaw(rawParts.peek());
      if (isCase && (tag == Tag.BEGIN_CASE || tag == Tag.END)) {
        return parts.build();
      }
      rawParts.next();
      switch (tag) {
        case RAW:
          parts.add(SoyMsgRawTextPart.of((String) rawParts.next()));
          break;
        case PLACEHOLDER:
          parts.add(new SoyMsgPlaceholderPart((String) rawParts.next()));
          break;
        case REMAINDER:
          parts.add(new SoyMsgPluralRemainderPart((String) rawParts.next()));
          break;
        case BEGIN_PLURAL:
          parts.add(parsePluralPart(rawParts));
          break;
        case BEGIN_SELECT:
          parts.add(parseSelectPart(rawParts));
          break;
        case BEGIN_CASE:
        case END:
          throw new AssertionError();
      }
    }
    return parts.build();
  }

  private static SoyMsgPluralPart parsePluralPart(PeekingIterator<Object> rawParts) {
    String pluralVarName = (String) rawParts.next();
    int offset = (Integer) rawParts.next();
    ImmutableList<Case<SoyMsgPluralCaseSpec>> cases = parseCases(rawParts,
        spec -> spec instanceof Number ? new SoyMsgPluralCaseSpec(((Number) spec).longValue()) 
                                         : SoyMsgPluralCaseSpec.forType((String) spec));
    return new SoyMsgPluralPart(pluralVarName, offset, cases);
  }

  private static SoyMsgSelectPart parseSelectPart(PeekingIterator<Object> rawParts) {
    String selectVarName = (String) rawParts.next();
    ImmutableList<Case<String>> cases = parseCases(rawParts, 
        spec -> {
          String s = (String) spec;
          return s.isEmpty() ? null : s;
        });
    return new SoyMsgSelectPart(selectVarName, cases);
  }

  private static <T> ImmutableList<Case<T>> parseCases(PeekingIterator<Object> rawParts, Function<Object, T> specFactory) {
    ImmutableList.Builder<Case<T>> cases = ImmutableList.builder();
    while (rawParts.hasNext()) {
      Tag next = Tag.fromRaw(rawParts.next());
      if (next == Tag.BEGIN_CASE) {
        T spec = specFactory.apply(rawParts.next());
        cases.add(Case.create(spec, parseParts(rawParts, true)));
      } else if (next == Tag.END) {
        break;
      } else {
        throw new AssertionError();
      }
    }
    return cases.build();
  }

  private MsgDefaultConstantFactory() {}
}
