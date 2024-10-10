
/*
 * Copyright 2020 Google Inc.
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

package com.google.template.soy.soyparse;

import com.google.template.soy.base.SourceLocation.Point;
import java.util.Arrays;

/**
 * Extends the generated char stream here:
 */
final class SoySimpleCharStream extends SimpleCharStream {
  int[] lineLengths = new int[2048];

  @Override
  public char readChar() throws java.io.IOException {
    char c = super.readChar();
    updateLineLengthsForNewChar();
    return c;
  }

  /** Update line lengths for the current char. */
  private void updateLineLengthsForNewChar() {
    ensureCapacityForLineLengths();
    updateCurrentLineLength();
  }

  /** Ensure the capacity of lineLengths array is sufficient. */
  private void ensureCapacityForLineLengths() {
    if (line >= lineLengths.length) {
      lineLengths = Arrays.copyOf(lineLengths, Math.max(lineLengths.length, line) + 2048);
    }
  }

  /** Update the current line length based on the column position. */
  private void updateCurrentLineLength() {
    lineLengths[line] = Math.max(lineLengths[line], column);
  }

  /**
   * Gets the point just before the upcoming token. Note that the char stream's "current token" is
   * the upcoming token that hasn't actually been parsed yet. So for "foo bar baz", once the parser
   * has executed a line like "token foo = foo()", then current token would refer to bar and not
   * foo.
   */
  Point getPointJustBeforeNextToken() {
    validateTokenBegin();

    if (tokenBegin > 0) {
      return Point.create(bufline[tokenBegin - 1], bufcolumn[tokenBegin - 1]);
    }

    return constructPreviousPoint();
  }

  /** Validate the current token position. */
  private void validateTokenBegin() {
    if (bufline[tokenBegin] <= 1 && bufcolumn[tokenBegin] <= 1) {
      throw new IllegalStateException("Can't get point before beginning of file");
    }
  }

  /** Construct the previous point based on the token position. */
  private Point constructPreviousPoint() {
    if (bufcolumn[tokenBegin] == 1) {
      return Point.create(bufline[tokenBegin] - 1, lineLengths[bufline[tokenBegin] - 1]);
    }
    return Point.create(bufline[tokenBegin], bufcolumn[tokenBegin] - 1);
  }

  /** Constructor. */
  public SoySimpleCharStream(
      java.io.Reader dstream, int startline, int startcolumn, int buffersize) {
    super(dstream, startline, startcolumn, buffersize);
  }

  /** Constructor. */
  public SoySimpleCharStream(java.io.Reader dstream, int startline, int startcolumn) {
    this(dstream, startline, startcolumn, 4096);
  }

  /** Constructor. */
  public SoySimpleCharStream(java.io.Reader dstream) {
    this(dstream, 1, 1, 4096);
  }
}
