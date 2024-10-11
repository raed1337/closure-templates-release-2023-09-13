
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.ibm.icu.util.ULocale;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * Represents all renderable messages in a locale.
 *
 * <p>This saves significant memory from the normal SoyMsgBundleImpl, but doesn't store details like
 * message descriptions. This also has small runtime performance penalties, such as using binary
 * search instead of hash tables, constructing wrapper objects on the fly, and computing properties
 * of the message instead of storing them.
 */
@Immutable
public final class RenderOnlySoyMsgBundleImpl extends SoyMsgBundle {

  /** The language/locale string of this bundle's messages. */
  private final String localeString;
  private final ULocale locale;
  private final boolean isRtl;

  @SuppressWarnings("Immutable")
  private final long[] ids; // Sorted array of message ID's for binary search.
  private final ImmutableList<SoyMsgPart> values;

  @SuppressWarnings("Immutable")
  private final int[] partRanges; // Index-ranges for parts belonging to messages.

  private static final int BUCKET_SHIFT = 6;
  private final int bucketMask;

  @SuppressWarnings("Immutable")
  private final int[] bucketBoundaries;

  /** Returns the bucket index of the given ID. */
  private int bucketOf(long msgId) {
    return ((int) msgId) & bucketMask;
  }

  /**
   * Constructs a map of render-only soy messages. This implementation saves memory but doesn't
   * store all fields necessary during extraction.
   *
   * @param localeString The language/locale string of this bundle of messages, or null if unknown.
   *     Should only be null for bundles newly extracted from source files. Should always be set for
   *     bundles parsed from message files/resources.
   * @param msgs The list of messages. List order will become the iteration order. Duplicate message
   *     ID's are not permitted.
   */
  public RenderOnlySoyMsgBundleImpl(@Nullable String localeString, Iterable<SoyMsg> msgs) {
    this.localeString = localeString;
    this.locale = localeString == null ? null : new ULocale(localeString);
    this.isRtl = BidiGlobalDir.forStaticLocale(localeString) == BidiGlobalDir.RTL;

    int maskHigh = Integer.highestOneBit(Iterables.size(msgs));
    this.bucketMask = maskHigh == 0 ? 0 : (maskHigh | (maskHigh - 1)) >>> BUCKET_SHIFT;
    int numBuckets = this.bucketMask + 1;

    Comparator<SoyMsg> bucketComparator = Comparator.comparingInt((SoyMsg m) -> bucketOf(m.getId()))
        .thenComparingLong(SoyMsg::getId);
    ImmutableList<SoyMsg> sortedMsgs = ImmutableList.sortedCopyOf(bucketComparator, msgs);

    bucketBoundaries = new int[numBuckets + 1];
    for (int bucket = 0, idx = 0; bucket < numBuckets; bucket++) {
      bucketBoundaries[bucket] = idx;
      while (idx < sortedMsgs.size() && bucketOf(sortedMsgs.get(idx).getId()) == bucket) {
        idx++;
      }
    }
    bucketBoundaries[numBuckets] = sortedMsgs.size();
    
    ids = new long[sortedMsgs.size()];
    ImmutableList.Builder<SoyMsgPart> partsBuilder = ImmutableList.builder();
    partRanges = new int[sortedMsgs.size() + 1];
    partRanges[0] = 0;
    long priorId = sortedMsgs.isEmpty() ? -1L : sortedMsgs.get(0).getId() - 1L;
    int runningPartCount = 0;

    for (int i = 0, c = sortedMsgs.size(); i < c; i++) {
      SoyMsg msg = sortedMsgs.get(i);
      ImmutableList<SoyMsgPart> parts = msg.getParts();

      checkArgument(msg.getId() != priorId, "Duplicate messages are not permitted in the render-only impl.");
      checkArgument(MsgPartUtils.hasPlrselPart(parts) == msg.isPlrselMsg(), "Message's plural/select status is inconsistent -- internal compiler bug.");

      priorId = msg.getId();
      ids[i] = msg.getId();
      partsBuilder.addAll(parts);
      runningPartCount += parts.size();
      partRanges[i + 1] = runningPartCount; // runningPartCount is the end of range, hence +1
    }

    values = partsBuilder.build();
  }

  /** Copies a RenderOnlySoyMsgBundleImpl, replacing only the localeString. */
  public RenderOnlySoyMsgBundleImpl(@Nullable String localeString, RenderOnlySoyMsgBundleImpl exemplar) {
    this(localeString, exemplar.locale, exemplar.isRtl, exemplar.bucketMask, exemplar.bucketBoundaries, exemplar.ids, exemplar.values, exemplar.partRanges);
  }

  private RenderOnlySoyMsgBundleImpl(@Nullable String localeString, ULocale locale, boolean isRtl,
      int bucketMask, int[] bucketBoundaries, long[] ids, ImmutableList<SoyMsgPart> values, int[] partRanges) {
    this.localeString = localeString;
    this.locale = locale;
    this.isRtl = isRtl;
    this.bucketMask = bucketMask;
    this.bucketBoundaries = bucketBoundaries;
    this.ids = ids;
    this.values = values;
    this.partRanges = partRanges;
  }

  /** Brings a message back to life from only its ID and parts. */
  private SoyMsg resurrectMsg(long id, ImmutableList<SoyMsgPart> parts) {
    return SoyMsg.builder()
        .setId(id)
        .setLocaleString(localeString)
        .setIsPlrselMsg(MsgPartUtils.hasPlrselPart(parts))
        .setParts(parts)
        .build();
  }

  @Override
  public String getLocaleString() {
    return localeString;
  }

  @Override
  @Nullable
  public ULocale getLocale() {
    return locale;
  }

  @Override
  public boolean isRtl() {
    return isRtl;
  }

  private ImmutableList<SoyMsgPart> partsForIndex(int index) {
    int startInclusive = partRanges[index];
    int endExclusive = partRanges[index + 1];
    return values.subList(startInclusive, endExclusive);
  }

  @Override
  public SoyMsg getMsg(long msgId) {
    int index = binarySearch(msgId);
    return index >= 0 ? resurrectMsg(msgId, partsForIndex(index)) : null;
  }

  @Override
  public ImmutableList<SoyMsgPart> getMsgParts(long msgId) {
    int index = binarySearch(msgId);
    return index >= 0 ? partsForIndex(index) : ImmutableList.of();
  }

  private int binarySearch(long key) {
    int bucket = bucketOf(key);
    int low = bucketBoundaries[bucket];
    int high = bucketBoundaries[bucket + 1];
    return Arrays.binarySearch(ids, low, high, key);
  }

  @Override
  public int getNumMsgs() {
    return ids.length;
  }

  @Override
  public Iterator<SoyMsg> iterator() {
    return new Iterator<>() {
      int index = 0;

      @Override
      public boolean hasNext() {
        return index < ids.length;
      }

      @Override
      public SoyMsg next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        SoyMsg result = resurrectMsg(ids[index], partsForIndex(index));
        index++;
        return result;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("Iterator is immutable");
      }
    };
  }
}
