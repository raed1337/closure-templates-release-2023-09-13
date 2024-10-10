
/*
 * Copyright 2009 Google Inc.
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

package com.google.template.soy.internal.i18n;

import com.google.common.annotations.VisibleForTesting;
import com.google.template.soy.data.Dir;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.lang.UScript;
import com.ibm.icu.util.ULocale;

/**
 * Utility functions for performing common Bidi tests on strings.
 *
 */
public class BidiUtils {

  /** Not instantiable. */
  private BidiUtils() {}

  static final class Format {
    private Format() {} // Not instantiable.
    public static final char LRE = '\u202A';
    public static final char RLE = '\u202B';
    public static final char PDF = '\u202C';
    public static final char LRM = '\u200E';
    public static final char RLM = '\u200F';

    public static final String LRM_STRING = Character.toString(LRM);
    public static final String RLM_STRING = Character.toString(RLM);
  }

  public static boolean isRtlLanguage(String locale) {
    try {
      return UScript.isRightToLeft(
          UCharacter.getPropertyValueEnum(
              UProperty.SCRIPT, ULocale.addLikelySubtags(new ULocale(locale)).getScript()));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  @VisibleForTesting
  static class DirectionalityEstimator {
    private static final int DIR_TYPE_CACHE_SIZE = 0x700;
    private static final byte[] DIR_TYPE_CACHE;

    static {
      DIR_TYPE_CACHE = new byte[DIR_TYPE_CACHE_SIZE];
      for (int i = 0; i < DIR_TYPE_CACHE_SIZE; i++) {
        DIR_TYPE_CACHE[i] = UCharacter.getDirectionality(i);
      }
    }

    private static final double RTL_THRESHOLD = 0.4;

    private final String text;
    private final boolean isHtml;
    private final int length;
    private int charIndex;
    private char lastChar;

    private int ltrWordCount;
    private int rtlWordCount;
    private int urlWordCount;
    private int enWordCount;
    private int signedEnWordCount;
    private int plusAnWordCount;
    private int minusAnWordCount;
    private int wordType;

    private static class WordType {
      public static final int NEUTRAL = 0;
      public static final int PLUS = 1;
      public static final int MINUS = 2;
      public static final int EN = 3;
      public static final int AN = 4;
      public static final int SIGNED_EN = 5;
      public static final int PLUS_AN = 6;
      public static final int MINUS_AN = 7;
      public static final int STRONG = 8;
      public static final int URL = 9;
      public static final int EMBEDDED = 10;
    }

    DirectionalityEstimator(String text, boolean isHtml) {
      this.text = text;
      this.isHtml = isHtml;
      this.length = text.length();
    }

    Dir getExitDir() {
      charIndex = length;
      int embeddingLevel = 0;
      int lastNonEmptyEmbeddingLevel = 0;

      while (charIndex > 0) {
        switch (dirTypeBackward()) {
          case UCharacter.DIRECTIONALITY_LEFT_TO_RIGHT:
            if (embeddingLevel == 0) {
              return Dir.LTR;
            }
            if (lastNonEmptyEmbeddingLevel == 0) {
              lastNonEmptyEmbeddingLevel = embeddingLevel;
            }
            break;
          case UCharacter.DIRECTIONALITY_RIGHT_TO_LEFT:
          case UCharacter.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
            if (embeddingLevel == 0) {
              return Dir.RTL;
            }
            if (lastNonEmptyEmbeddingLevel == 0) {
              lastNonEmptyEmbeddingLevel = embeddingLevel;
            }
            break;
          case UCharacter.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT:
            embeddingLevel++;
            break;
          default:
            if (lastNonEmptyEmbeddingLevel == 0) {
              lastNonEmptyEmbeddingLevel = embeddingLevel;
            }
            break;
        }
      }
      return Dir.NEUTRAL;
    }

    Dir estimateDirectionByWordCount() {
      charIndex = 0;
      resetWordCounts();
      int embedLevel = 0;
      wordType = WordType.NEUTRAL;

      while (charIndex < length) {
        byte dirType = dirTypeForward();
        if (dirType == UCharacter.DIRECTIONALITY_LEFT_TO_RIGHT) {
          processStrong(false);
        } else {
          processDirectionType(dirType, embedLevel);
        }
      }

      return compareCounts();
    }

    private void resetWordCounts() {
      ltrWordCount = 0;
      rtlWordCount = 0;
      urlWordCount = 0;
      enWordCount = 0;
      signedEnWordCount = 0;
      plusAnWordCount = 0;
      minusAnWordCount = 0;
    }

    private void processDirectionType(byte dirType, int embedLevel) {
      switch (dirType) {
        case UCharacter.DIRECTIONALITY_RIGHT_TO_LEFT:
        case UCharacter.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
          processStrong(true);
          break;
        case UCharacter.DIRECTIONALITY_EUROPEAN_NUMBER:
          processEuropeanDigit();
          break;
        case UCharacter.DIRECTIONALITY_ARABIC_NUMBER:
          processArabicDigit();
          break;
        case UCharacter.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR:
          processSeparator();
          break;
        case UCharacter.DIRECTIONALITY_WHITESPACE:
        case UCharacter.DIRECTIONALITY_SEGMENT_SEPARATOR:
          processWhitespace();
          break;
        case UCharacter.DIRECTIONALITY_PARAGRAPH_SEPARATOR:
          embedLevel = 0;
          wordType = WordType.NEUTRAL;
          break;
        case UCharacter.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
          processStrong(false);
        case UCharacter.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
          if (embedLevel++ == 0) {
            wordType = WordType.EMBEDDED;
          }
          break;
        case UCharacter.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
          processStrong(true);
        case UCharacter.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
          if (embedLevel++ == 0) {
            wordType = WordType.EMBEDDED;
          }
          break;
        case UCharacter.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT:
          if (--embedLevel == 0) {
            wordType = WordType.NEUTRAL;
          }
          break;
        default:
          break;
      }
    }

    private void processSeparator() {
      if (wordType < WordType.STRONG) {
        if (wordType <= WordType.MINUS) {
          wordType = (lastChar == '+' ? WordType.PLUS : WordType.MINUS);
        } else {
          wordType = WordType.NEUTRAL;
        }
      }
    }

    private void processWhitespace() {
      if (wordType < WordType.EMBEDDED) {
        wordType = WordType.NEUTRAL;
      }
    }

    Dir compareCounts() {
      if (rtlWordCount > (ltrWordCount + rtlWordCount) * RTL_THRESHOLD) {
        return Dir.RTL;
      }
      if (ltrWordCount > 0) {
        return Dir.LTR;
      }
      if (minusAnWordCount > 0) {
        return Dir.RTL;
      }
      if (plusAnWordCount > 0) {
        return Dir.LTR;
      }
      return Dir.NEUTRAL;
    }

    private void processStrong(boolean isRtl) {
      if (wordType >= WordType.STRONG) {
        return;
      }
      switch (wordType) {
        case WordType.NEUTRAL:
          if (!isRtl && lastChar == 'h' && (matchForward("ttp://", true) || matchForward("ttps://", true))) {
            wordType = WordType.URL;
            ++urlWordCount;
            return;
          }
          break;
        case WordType.SIGNED_EN:
          --signedEnWordCount;
          break;
        case WordType.PLUS_AN:
          --plusAnWordCount;
          break;
        case WordType.MINUS_AN:
          --minusAnWordCount;
          break;
        case WordType.EN:
          --enWordCount;
          break;
      }
      wordType = WordType.STRONG;
      if (isRtl) {
        ++rtlWordCount;
      } else {
        ++ltrWordCount;
      }
    }

    private void processEuropeanDigit() {
      switch (wordType) {
        case WordType.NEUTRAL:
          ++enWordCount;
          wordType = WordType.EN;
          break;
        case WordType.PLUS:
        case WordType.MINUS:
          ++signedEnWordCount;
          wordType = WordType.SIGNED_EN;
          break;
      }
    }

    private void processArabicDigit() {
      switch (wordType) {
        case WordType.NEUTRAL:
          wordType = WordType.AN;
          break;
        case WordType.PLUS:
          ++plusAnWordCount;
          wordType = WordType.PLUS_AN;
          break;
        case WordType.MINUS:
          ++minusAnWordCount;
          wordType = WordType.MINUS_AN;
          break;
      }
    }

    @VisibleForTesting
    boolean matchForward(String match, boolean advance) {
      int matchLength = match.length();
      if (matchLength > length - charIndex) {
        return false;
      }
      for (int checkIndex = 0; checkIndex < matchLength; checkIndex++) {
        if (text.charAt(charIndex + checkIndex) != match.charAt(checkIndex)) {
          return false;
        }
      }
      if (advance) {
        charIndex += matchLength;
      }
      return true;
    }

    private static byte getCachedDirectionality(char c) {
      return c < DIR_TYPE_CACHE_SIZE ? DIR_TYPE_CACHE[c] : UCharacter.getDirectionality(c);
    }

    @VisibleForTesting
    byte dirTypeForward() {
      lastChar = text.charAt(charIndex);
      if (UCharacter.isHighSurrogate(lastChar)) {
        int codePoint = UCharacter.codePointAt(text, charIndex);
        charIndex += UCharacter.charCount(codePoint);
        return UCharacter.getDirectionality(codePoint);
      }
      charIndex++;
      byte dirType = getCachedDirectionality(lastChar);
      if (isHtml) {
        if (lastChar == '<') {
          dirType = skipTagForward();
        } else if (lastChar == '&') {
          dirType = skipEntityForward();
        }
      }
      return dirType;
    }

    @VisibleForTesting
    byte dirTypeBackward() {
      lastChar = text.charAt(charIndex - 1);
      if (UCharacter.isLowSurrogate(lastChar)) {
        int codePoint = UCharacter.codePointBefore(text, charIndex);
        charIndex -= UCharacter.charCount(codePoint);
        return UCharacter.getDirectionality(codePoint);
      }
      charIndex--;
      byte dirType = getCachedDirectionality(lastChar);
      if (isHtml) {
        if (lastChar == '>') {
          dirType = skipTagBackward();
        } else if (lastChar == ';') {
          dirType = skipEntityBackward();
        }
      }
      return dirType;
    }

    private byte skipTagForward() {
      int initialCharIndex = charIndex;
      while (charIndex < length) {
        lastChar = text.charAt(charIndex++);
        if (lastChar == '>') {
          return UCharacter.DIRECTIONALITY_BOUNDARY_NEUTRAL;
        }
        if (lastChar == '"' || lastChar == '\'') {
          char quote = lastChar;
          while (charIndex < length && (lastChar = text.charAt(charIndex++)) != quote) {}
        }
      }
      charIndex = initialCharIndex;
      lastChar = '<';
      return UCharacter.DIRECTIONALITY_OTHER_NEUTRALS;
    }

    private byte skipTagBackward() {
      int initialCharIndex = charIndex;
      while (charIndex > 0) {
        lastChar = text.charAt(--charIndex);
        if (lastChar == '<') {
          return UCharacter.DIRECTIONALITY_BOUNDARY_NEUTRAL;
        }
        if (lastChar == '>') {
          break;
        }
        if (lastChar == '"' || lastChar == '\'') {
          char quote = lastChar;
          while (charIndex > 0 && (lastChar = text.charAt(--charIndex)) != quote) {}
        }
      }
      charIndex = initialCharIndex;
      lastChar = '>';
      return UCharacter.DIRECTIONALITY_OTHER_NEUTRALS;
    }

    private byte skipEntityForward() {
      while (charIndex < length && (lastChar = text.charAt(charIndex++)) != ';') {}
      return UCharacter.DIRECTIONALITY_WHITESPACE;
    }

    private byte skipEntityBackward() {
      int initialCharIndex = charIndex;
      while (charIndex > 0) {
        lastChar = text.charAt(--charIndex);
        if (lastChar == '&') {
          return UCharacter.DIRECTIONALITY_WHITESPACE;
        }
        if (lastChar == ';') {
          break;
        }
      }
      charIndex = initialCharIndex;
      lastChar = ';';
      return UCharacter.DIRECTIONALITY_OTHER_NEUTRALS;
    }
  }

  public static Dir getExitDir(String str, boolean isHtml) {
    return new DirectionalityEstimator(str, isHtml).getExitDir();
  }

  public static Dir estimateDirection(String str, boolean isHtml) {
    return new DirectionalityEstimator(str, isHtml).estimateDirectionByWordCount();
  }
}
