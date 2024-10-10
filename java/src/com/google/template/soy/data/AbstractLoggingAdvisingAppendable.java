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
package com.google.template.soy.data;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import java.util.function.Function;

/**
 * A {@link LoggingAdvisingAppendable} that implements {@code logonly} behavior in response to log
 * statements
 *
 * <p>If a logging statement enables {@code logonly} it means that we will only collect logging
 * statements
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 */
public abstract class AbstractLoggingAdvisingAppendable extends LoggingAdvisingAppendable {
  /**
   * The current number of calls to {@link #enterLoggableElement(LogStatement)} without a matching
   * {@link #exitLoggableElement()}, after a call with the {@link LogStatement#logOnly()} bit set.
   */
  private int logOnlyDepth;

  /** Subclasses can call this to detect if they are in the logOnly state. */
  private boolean isLogOnly() {
    return logOnlyDepth > 0;
  }

  // covariant overrides

  @CanIgnoreReturnValue
  @Override
  public final AbstractLoggingAdvisingAppendable append(CharSequence csq) throws IOException {
    if (!isLogOnly()) {
      doAppend(csq);
    }
    return this;
  }

  @CanIgnoreReturnValue
  @Override
  public final AbstractLoggingAdvisingAppendable append(CharSequence csq, int start, int end)
      throws IOException {
    if (!isLogOnly()) {
      doAppend(csq, start, end);
    }
    return this;
  }

  @CanIgnoreReturnValue
  @Override
  public final AbstractLoggingAdvisingAppendable append(char c) throws IOException {
    if (!isLogOnly()) {
      doAppend(c);
    }
    return this;
  }

  /** Called whenever a logging function is being rendered. */
  @CanIgnoreReturnValue
  @Override
  public final AbstractLoggingAdvisingAppendable appendLoggingFunctionInvocation(
      LoggingFunctionInvocation funCall, ImmutableList<Function<String, String>> escapers)
      throws IOException {
    if (!isLogOnly()) {
      doAppendLoggingFunctionInvocation(funCall, escapers);
    }
    return this;
  }

  @CanIgnoreReturnValue
  @Override
  public final AbstractLoggingAdvisingAppendable enterLoggableElement(LogStatement statement) {
    int depth = logOnlyDepth;
    if (depth > 0) {
      depth++;
      if (depth < 0) {
        throw new IllegalStateException("overflowed logging depth");
      }
      logOnlyDepth = depth;
    } else if (statement.logOnly()) {
      logOnlyDepth = 1;
    }
    doEnterLoggableElement(statement);
    return this;
  }

  @CanIgnoreReturnValue
  @Override
  public final AbstractLoggingAdvisingAppendable exitLoggableElement() {
    int depth = logOnlyDepth;
    if (depth > 0) {
      depth--;
      logOnlyDepth = depth;
    }
    doExitLoggableElement();
    return this;
  }

  @ForOverride
  protected abstract void doAppend(CharSequence csq) throws IOException;

  @ForOverride
  protected abstract void doAppend(CharSequence csq, int start, int end) throws IOException;

  @ForOverride
  protected abstract void doAppend(char c) throws IOException;

  @ForOverride
  protected abstract void doEnterLoggableElement(LogStatement statement);

  @ForOverride
  protected abstract void doExitLoggableElement();

  @ForOverride
  protected abstract void doAppendLoggingFunctionInvocation(
      LoggingFunctionInvocation funCall, ImmutableList<Function<String, String>> escapers)
      throws IOException;
}
