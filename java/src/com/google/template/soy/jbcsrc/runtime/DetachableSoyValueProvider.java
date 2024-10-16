
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

import static com.google.common.base.Preconditions.checkState;

import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.jbcsrc.api.RenderResult;
import java.io.IOException;

/**
 * A special implementation of {@link SoyValueProvider} to use as a shared base class for the {@code
 * jbcsrc} implementations of the generated {@code LetValueNode} and {@code CallParamValueNode}
 * implementations.
 *
 * <p>This class resolves to a {@link SoyValue} and calls {@link SoyValue#render}. If you need to
 * resolve to a {@link SoyValueProvider} to call {@link SoyValueProvider#renderAndResolve}, use
 * {@link DetachableSoyValueProviderProvider} instead.
 */
public abstract class DetachableSoyValueProvider implements SoyValueProvider {
  // TOMBSTONE marks this field as uninitialized which allows it to accept 'null' as a valid value.
  protected SoyValue resolvedValue = TombstoneValue.INSTANCE;

  @Override
  public final SoyValue resolve() {
    JbcSrcRuntime.awaitProvider(this);
    return getResolvedValue();
  }

  @Override
  public final RenderResult status() {
    return (resolvedValue != TombstoneValue.INSTANCE) ? RenderResult.done() : doResolve();
  }

  @Override
  public RenderResult renderAndResolve(LoggingAdvisingAppendable appendable, boolean isLast)
      throws IOException {
    RenderResult result = status();
    if (result.isDone()) {
      renderResolvedValue(appendable);
    }
    return result;
  }

  private SoyValue getResolvedValue() {
    SoyValue local = resolvedValue;
    checkState(local != TombstoneValue.INSTANCE, "doResolve didn't replace tombstone");
    return local;
  }

  private void renderResolvedValue(LoggingAdvisingAppendable appendable) throws IOException {
    SoyValue resolved = resolve();
    if (resolved == null) {
      appendable.append("null");
    } else {
      resolved.render(appendable);
    }
  }

  /** Overridden by generated subclasses to implement lazy detachable resolution. */
  protected abstract RenderResult doResolve();
}
