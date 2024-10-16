
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import com.google.common.collect.Tables;
import com.google.errorprone.annotations.Immutable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Utility for applying deltemplate selection logic to an arbitrary set of values.
 *
 * <p>This object allows selection logic to be shared between both tofu and jbcsrc which use
 * different runtime representations for templates, without needing to have hard dependencies on
 * those runtime representations. For example, tofu uses {@code TemplateDelegateNode} and jbcsrc
 * uses {@code CompiledTemplate}.
 *
 * <p>This logic should be kept in sync with the JS and Python runtime logic. See the JS {@code
 * soy.$$getDelegateFn} and {@code soy.$$registerDelegateFn} methods.
 *
 * @param <T> The type of the values in the selector
 */
@Immutable(containerOf = "T")
public final class DelTemplateSelector<T> {
  private final ImmutableTable<String, String, Group<T>> nameAndVariantToGroup;
  private final ImmutableListMultimap<String, T> delTemplateNameToValues;

  private DelTemplateSelector(Builder<T> builder) {
    ImmutableTable.Builder<String, String, Group<T>> nameAndVariantBuilder = ImmutableTable.builder();
    ImmutableListMultimap.Builder<String, T> delTemplateNameToValuesBuilder = ImmutableListMultimap.builder();
    
    for (Cell<String, String, Group.Builder<T>> entry : builder.nameAndVariantToGroup.cellSet()) {
      Group<T> group = entry.getValue().build();
      nameAndVariantBuilder.put(entry.getRowKey(), entry.getColumnKey(), group);
      populateDelTemplateNameToValues(delTemplateNameToValuesBuilder, entry.getRowKey(), group);
    }
    
    this.nameAndVariantToGroup = nameAndVariantBuilder.buildOrThrow();
    this.delTemplateNameToValues = delTemplateNameToValuesBuilder.build();
  }

  private void populateDelTemplateNameToValues(ImmutableListMultimap.Builder<String, T> builder, String delTemplateName, Group<T> group) {
    if (group.defaultValue != null) {
      builder.put(delTemplateName, group.defaultValue);
    }
    builder.putAll(delTemplateName, group.modToValue.values());
  }

  /**
   * Returns a multimap from deltemplate name to every member (disregarding variant).
   *
   * <p>This is useful for compiler passes that need to validate all members of deltemplate group.
   */
  public ImmutableListMultimap<String, T> delTemplateNameToValues() {
    return delTemplateNameToValues;
  }

  public boolean hasDelTemplateNamed(String delTemplateName) {
    return nameAndVariantToGroup.containsRow(delTemplateName);
  }

  /**
   * Returns an active delegate for the given name, variant and active packages. If no active
   * delegate if found for the {@code variant} the we fallback to a non variant lookup. Finally, we
   * return {@code null} if no such template can be found.
   *
   * <p>See {@code soy.$$getDelegateFn} for the {@code JS} version
   */
  @Nullable
  public T selectTemplate(String delTemplateName, String variant, Predicate<String> activeModSelector) {
    T selection = selectFromGroup(delTemplateName, variant, activeModSelector);
    
    if (selection != null) {
      return selection;
    }
    
    return variant.isEmpty() ? null : selectFromGroup(delTemplateName, "", activeModSelector);
  }

  @Nullable
  private T selectFromGroup(String delTemplateName, String variant, Predicate<String> activeModSelector) {
    Group<T> group = nameAndVariantToGroup.get(delTemplateName, variant);
    return group == null ? null : group.select(activeModSelector);
  }

  public Builder<T> toBuilder() {
    Builder<T> builder = new Builder<>();
    for (Cell<String, String, Group<T>> cell : nameAndVariantToGroup.cellSet()) {
      builder.nameAndVariantToGroup.put(cell.getRowKey(), cell.getColumnKey(), cell.getValue().toBuilder());
    }
    return builder;
  }

  /** A Builder for DelTemplateSelector. */
  public static final class Builder<T> {
    private final Table<String, String, Group.Builder<T>> nameAndVariantToGroup = Tables.newCustomTable(new LinkedHashMap<>(), LinkedHashMap::new);

    /** Adds the default template (not associated with any mod or variant). */
    public T addDefault(String delTemplateName, String variant, T value) {
      return getBuilder(delTemplateName, variant).setDefault(value);
    }

    /** Adds a deltemplate. */
    public T add(String delTemplateName, String mod, String variant, T value) {
      return getBuilder(delTemplateName, variant).add(mod, value);
    }

    private Group.Builder<T> getBuilder(String name, String variant) {
      checkArgument(!name.isEmpty());
      Group.Builder<T> v = nameAndVariantToGroup.get(name, variant);
      if (v == null) {
        v = new Group.Builder<>(name + (variant.isEmpty() ? "" : ":" + variant));
        nameAndVariantToGroup.put(name, variant, v);
      }
      return v;
    }

    public DelTemplateSelector<T> build() {
      return new DelTemplateSelector<>(this);
    }
  }

  /** Represents all the templates for a given deltemplate name and variant value. */
  @Immutable(containerOf = "T")
  private static final class Group<T> {
    final String formattedName;
    @Nullable final T defaultValue;
    final ImmutableMap<String, T> modToValue;

    private Group(Builder<T> builder) {
      this.formattedName = checkNotNull(builder.formattedName);
      this.defaultValue = builder.defaultValue;
      this.modToValue = ImmutableMap.copyOf(builder.modToValue);
    }

    /**
     * Returns the value from this group based on the current active packages, or the default if one
     * exists.
     */
    T select(Predicate<String> activeModSelector) {
      Map.Entry<String, T> selected = null;
      for (Map.Entry<String, T> entry : modToValue.entrySet()) {
        if (activeModSelector.test(entry.getKey())) {
          if (selected != null) {
            throw new IllegalArgumentException(
                String.format(
                    "For delegate template '%s', found two active implementations with equal"
                        + " priority in delegate packages '%s' and '%s'.",
                    formattedName, entry.getKey(), selected.getKey()));
          }
          selected = entry;
        }
      }
      return selected != null ? selected.getValue() : defaultValue;
    }

    Builder<T> toBuilder() {
      Builder<T> builder = new Builder<>(formattedName);
      builder.defaultValue = defaultValue;
      builder.modToValue.putAll(modToValue);
      return builder;
    }

    static final class Builder<T> {
      final String formattedName;
      Map<String, T> modToValue = new LinkedHashMap<>();
      T defaultValue;

      Builder(String formattedName) {
        this.formattedName = checkNotNull(formattedName);
      }

      T setDefault(T defaultValue) {
        if (this.defaultValue != null) {
          return this.defaultValue;
        }
        checkState(this.defaultValue == null);
        this.defaultValue = checkNotNull(defaultValue);
        return null;
      }

      T add(String mod, T value) {
        checkArgument(!mod.isEmpty());
        return modToValue.put(mod, checkNotNull(value));
      }

      Group<T> build() {
        return new Group<>(this);
      }
    }
  }
}
