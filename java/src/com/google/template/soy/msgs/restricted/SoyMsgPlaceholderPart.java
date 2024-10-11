
/*
 * Copyright 2008 Google Inc.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.template.soy.soytree.MessagePlaceholder;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a placeholder within a message.
 */
public final class SoyMsgPlaceholderPart extends SoyMsgPart {

  /** The placeholder (as seen by translators). */
  private final MessagePlaceholder.Summary placeholder;

  public SoyMsgPlaceholderPart(String placeholderName) {
    this(placeholderName, Optional.empty());
  }

  /** @param placeholderExample An optional example. */
  public SoyMsgPlaceholderPart(String placeholderName, Optional<String> placeholderExample) {
    this(MessagePlaceholder.Summary.create(checkNotNull(placeholderName), placeholderExample));
  }

  /** @param placeholder Placeholder data. */
  public SoyMsgPlaceholderPart(MessagePlaceholder.Summary placeholder) {
    this.placeholder = checkNotNull(placeholder);
  }

  /** Returns the placeholder name (as seen by translators). */
  public String getPlaceholderName() {
    return placeholder.name();
  }

  /** Returns the (optional) placeholder example (as seen by translators). */
  public Optional<String> getPlaceholderExample() {
    return placeholder.example();
  }

  @Override
  public boolean equals(Object other) {
    return this == other || (other instanceof SoyMsgPlaceholderPart && placeholder.equals(((SoyMsgPlaceholderPart) other).placeholder));
  }

  @Override
  public int hashCode() {
    return Objects.hash(placeholder);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .omitNullValues()
        .add("name", placeholder.name())
        .add("example", placeholder.example().orElse(null))
        .toString();
  }
}
