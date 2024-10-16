/*
 * Copyright 2019 Google Inc.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.jbcsrc.api.RenderResult;
import com.google.template.soy.jbcsrc.runtime.JbcSrcRuntime.MsgRenderer;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.ibm.icu.util.ULocale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class JbcSrcRuntimeTest {

  @Test
  public void testChainOfOrderConstraints() {
    MsgRenderer renderer = createRenderer(SoyMsgRawTextPart.of("Hello "));
    renderer.setPlaceholderAndOrdering("A", StringData.forValue("foo"), "B");
    // can't extend a chain from B to C
    IllegalArgumentException iae =
        assertThrows(
            IllegalArgumentException.class,
            () -> renderer.setPlaceholderAndOrdering("B", StringData.forValue("bar"), "C"));
    assertThat(iae)
        .hasMessageThat()
        .isEqualTo(
            "B is supposed to come after A but before C. "
                + "Order constraints should not be transitive.");

    // can't extend a chain from Foo to A
    iae =
        assertThrows(
            IllegalArgumentException.class,
            () -> renderer.setPlaceholderAndOrdering("Foo", StringData.forValue("bar"), "A"));
    assertThat(iae)
        .hasMessageThat()
        .isEqualTo(
            "A is supposed to come after Foo but before B. "
                + "Order constraints should not be transitive.");
  }

  @Test
  public void testMessageRendering() {
    MsgRenderer renderer =
        createRenderer(
            SoyMsgRawTextPart.of("Hello "),
            new SoyMsgPlaceholderPart("NAME"),
            SoyMsgRawTextPart.of("."));
    renderer.setPlaceholder("NAME", StringData.forValue("world"));
    assertRendersAs(renderer, "Hello world.");
  }

  @Test
  public void testMessageRendering_orderConstraints() {
    MsgRenderer renderer =
        createRenderer(
            SoyMsgRawTextPart.of("Hello "),
            new SoyMsgPlaceholderPart("LINK_START"),
            SoyMsgRawTextPart.of("world."),
            new SoyMsgPlaceholderPart("LINK_END"));
    renderer.setPlaceholderAndOrdering("LINK_START", StringData.forValue("<a>"), "LINK_END");
    renderer.setPlaceholder("LINK_END", StringData.forValue("</a>"));
    assertRendersAs(renderer, "Hello <a>world.</a>");
  }

  @Test
  public void testMessageRender_orderingConstraints_reversed() {
    // imagine that the translator has reordered the placeholders incorrectly
    MsgRenderer renderer =
        createRenderer(
            SoyMsgRawTextPart.of("Hello "),
            new SoyMsgPlaceholderPart("LINK_END"),
            SoyMsgRawTextPart.of("world."),
            new SoyMsgPlaceholderPart("LINK_START"));
    renderer.setPlaceholderAndOrdering("LINK_START", StringData.forValue("<a>"), "LINK_END");
    renderer.setPlaceholder("LINK_END", StringData.forValue("</a>"));
    assertThat(assertThrows(IllegalStateException.class, renderer::status))
        .hasMessageThat()
        .isEqualTo(
            "Expected placeholder 'LINK_END' to come after one of [LINK_START], in message 0");
  }

  @Test
  public void testMessageRender_orderingConstraints_missingStart() {
    // imagine that the translator has dropped a start placeholder
    MsgRenderer renderer =
        createRenderer(
            SoyMsgRawTextPart.of("Hello "),
            SoyMsgRawTextPart.of("world."),
            new SoyMsgPlaceholderPart("LINK_END"));
    renderer.setPlaceholderAndOrdering("LINK_START", StringData.forValue("<a>"), "LINK_END");
    renderer.setPlaceholder("LINK_END", StringData.forValue("</a>"));
    assertThat(assertThrows(IllegalStateException.class, renderer::status))
        .hasMessageThat()
        .isEqualTo(
            "Expected placeholder 'LINK_END' to come after one of [LINK_START], in message 0");
  }

  @Test
  public void testMessageRender_orderingConstraints_missingEnd() {
    // imagine that the translator has dropped an end placeholder
    MsgRenderer renderer =
        createRenderer(
            SoyMsgRawTextPart.of("Hello "),
            new SoyMsgPlaceholderPart("LINK_START"),
            SoyMsgRawTextPart.of("world."));
    renderer.setPlaceholderAndOrdering("LINK_START", StringData.forValue("<a>"), "LINK_END");
    renderer.setPlaceholder("LINK_END", StringData.forValue("</a>"));
    assertThat(assertThrows(IllegalStateException.class, renderer::status))
        .hasMessageThat()
        .isEqualTo(
            "The following placeholders never had their matching placeholders rendered in "
                + "message 0: [LINK_START]");
  }

  @Test
  public void testMessageRender_orderingConstraints_oneEndForMultipleStarts() {
    // This is fairly common in real soy templates since the end tags always match they get one
    // placeholder
    MsgRenderer renderer =
        createRenderer(
            new SoyMsgPlaceholderPart("LINK_START_1"),
            SoyMsgRawTextPart.of("Hello"),
            new SoyMsgPlaceholderPart("LINK_END"),
            new SoyMsgPlaceholderPart("LINK_START_2"),
            SoyMsgRawTextPart.of("world."),
            new SoyMsgPlaceholderPart("LINK_END"));
    renderer.setPlaceholderAndOrdering("LINK_START_1", StringData.forValue("<a>"), "LINK_END");
    renderer.setPlaceholderAndOrdering("LINK_START_2", StringData.forValue("<a>"), "LINK_END");
    renderer.setPlaceholder("LINK_END", StringData.forValue("</a>"));
    // renders fine
    assertRendersAs(renderer, "<a>Hello</a><a>world.</a>");
  }

  static class FakeProvider implements SoyValueProvider {
    RenderResult result;
    int calls;

    FakeProvider() {}

    FakeProvider(RenderResult result) {
      this.result = result;
    }

    @Override
    public RenderResult renderAndResolve(
        LoggingAdvisingAppendable advisingAppendable, boolean isLast) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SoyValue resolve() {
      throw new UnsupportedOperationException();
    }

    @Override
    public RenderResult status() {
      calls++;
      return result;
    }
  }

  @Test
  public void testAwaitProvider_done() {
    FakeProvider provider = new FakeProvider(RenderResult.done());
    JbcSrcRuntime.awaitProvider(provider);
    assertThat(provider.calls).isEqualTo(1);
  }

  @Test
  public void testAwaitProvider_limited() {
    FakeProvider provider = new FakeProvider(RenderResult.limited());
    assertThrows(AssertionError.class, () -> JbcSrcRuntime.awaitProvider(provider));
    assertThat(provider.calls).isEqualTo(1);
  }

  @Test
  public void testAwaitProvider_detachOnce() {
    FakeProvider provider =
        new FakeProvider() {
          @Override
          public RenderResult status() {
            return calls++ == 0
                ? RenderResult.continueAfter(immediateFuture("hello"))
                : RenderResult.done();
          }
        };
    JbcSrcRuntime.awaitProvider(provider);
    assertThat(provider.calls).isEqualTo(2);
  }

  @Test
  public void testAwaitProvider_detachManyTimes() {
    FakeProvider provider =
        new FakeProvider() {
          @Override
          public RenderResult status() {
            return calls++ < 19
                ? RenderResult.continueAfter(immediateFuture("hello"))
                : RenderResult.done();
          }
        };
    JbcSrcRuntime.awaitProvider(provider);
    assertThat(provider.calls).isEqualTo(20);
  }

  private void assertRendersAs(MsgRenderer renderer, String expected) {
    assertThat(renderer.status().isDone()).isTrue();
    assertThat(renderer.resolve().coerceToString()).isEqualTo(expected);
  }

  private MsgRenderer createRenderer(SoyMsgPart... parts) {
    ImmutableList<SoyMsgPart> partsCopy = ImmutableList.copyOf(parts);
    int numPlaceholders =
        (int) partsCopy.stream().filter(p -> p instanceof SoyMsgPlaceholderPart).count();
    return new JbcSrcRuntime.MsgRenderer(
        /* msgId=*/ 0L, partsCopy, ULocale.US, numPlaceholders, /* htmlEscape= */ false);
  }
}
