
/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.sharedpasses.render;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.jbcsrc.api.RenderResult;
import java.io.IOException;
import javax.annotation.Nonnull;

/**
 * A renderable <a href="http://en.wikipedia.org/wiki/Thunk">thunk</a>.
 *
 * <p>Subclasses should override {@link #doRender(Appendable)} method to implement the rendering
 * logic.
 *
 * <p>This is analagous to {@code DetachableContentProvider} in the {@code jbcsrc} backend.
 */
public abstract class RenderableThunk implements SoyValueProvider {
  private String content;
  private SoyValue resolved;
  private final ContentKind kind;

  protected RenderableThunk(ContentKind kind) {
    this.kind = checkNotNull(kind);
  }

  @Override
  public RenderResult renderAndResolve(LoggingAdvisingAppendable appendable, boolean isLast)
      throws IOException {
    if (content == null) {
      resolveAndRender(new TeeAppendable(appendable));
    } else {
      appendable.append(content);
    }
    return RenderResult.done();
  }

  @Override
  @Nonnull
  public SoyValue resolve() {
    if (resolved == null) {
      resolveContent();
    }
    return resolved;
  }

  @Override
  @Nonnull
  public RenderResult status() {
    resolve();
    return RenderResult.done();
  }

  private void resolveAndRender(Appendable appendable) {
    doRender(appendable);
    content = appendable.toString();
    resolved = resolveContentValue(content);
  }

  private SoyValue resolveContentValue(String content) {
    return kind == ContentKind.TEXT 
        ? StringData.forValue(content) 
        : UnsafeSanitizedContentOrdainer.ordainAsSafe(content, kind);
  }

  private void resolveContent() {
    resolveAndRender(new StringBuilder());
  }

  protected abstract void doRender(Appendable appendable);

  /**
   * An {@link Appendable} that forwards to a delegate appenable but also saves all the same
   * forwarded content into a buffer.
   *
   * <p>See: <a href="http://en.wikipedia.org/wiki/Tee_%28command%29">Tee command for the unix
   * command on which this is based.
   */
  private static final class TeeAppendable implements Appendable {
    final StringBuilder buffer = new StringBuilder();
    final Appendable delegate;

    TeeAppendable(Appendable delegate) {
      this.delegate = delegate;
    }

    @CanIgnoreReturnValue
    @Override
    public Appendable append(CharSequence csq) throws IOException {
      delegate.append(csq);
      buffer.append(csq);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public Appendable append(CharSequence csq, int start, int end) throws IOException {
      delegate.append(csq, start, end);
      buffer.append(csq, start, end);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public Appendable append(char c) throws IOException {
      delegate.append(c);
      buffer.append(c);
      return this;
    }

    @Override
    public String toString() {
      return buffer.toString();
    }
  }
}
