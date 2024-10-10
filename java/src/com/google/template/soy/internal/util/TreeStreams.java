
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

package com.google.template.soy.internal.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Utility method related to generic tree data structures. */
public final class TreeStreams {

  private TreeStreams() {}

  public static <T> Stream<? extends T> ancestor(T root, Function<T, ? extends T> ancestor) {
    return StreamSupport.stream(
        new AbstractSpliterator<>(
            Long.MAX_VALUE,
            Spliterator.ORDERED | Spliterator.DISTINCT) {

          private T current = root;

          @Override
          public boolean tryAdvance(Consumer<? super T> action) {
            if (current == null) {
              return false;
            }
            action.accept(current);
            current = ancestor.apply(current);
            return true;
          }
        },
        /* parallel= */ false);
  }

  public static <T> Stream<? extends T> breadthFirst(T root, Function<T, Iterable<? extends T>> successors) {
    Deque<T> queue = new ArrayDeque<>();
    queue.add(root);
    return StreamSupport.stream(
        new AbstractSpliterator<>(
            Long.MAX_VALUE,
            Spliterator.ORDERED | Spliterator.DISTINCT) {
          @Override
          public boolean tryAdvance(Consumer<? super T> action) {
            T current = queue.poll();
            if (current == null) {
              return false;
            }
            Iterables.addAll(queue, successors.apply(current));
            action.accept(current);
            return true;
          }
        },
        /* parallel= */ false);
  }

  public static <T> Stream<? extends T> depthFirst(T root, Function<T, Iterable<? extends T>> successors) {
    Deque<T> stack = new ArrayDeque<>();
    stack.add(root);
    return StreamSupport.stream(
        new AbstractSpliterator<>(
            Long.MAX_VALUE,
            Spliterator.ORDERED | Spliterator.DISTINCT) {
          @Override
          public boolean tryAdvance(Consumer<? super T> action) {
            T current = stack.poll();
            if (current == null) {
              return false;
            }

            addChildrenToStack(successors.apply(current), stack);
            action.accept(current);
            return true;
          }

          private void addChildrenToStack(Iterable<? extends T> children, Deque<T> stack) {
            List<? extends T> reverseOrder = children instanceof List
                ? Lists.reverse((List<? extends T>) children)
                : ImmutableList.copyOf(children).reverse();
            for (T child : reverseOrder) {
              stack.push(child);
            }
          }
        },
        /* parallel= */ false);
  }

  public static <T> Stream<T> collateAndMerge(Stream<? extends T> source, BiPredicate<T, T> accept, Function<List<T>, T> merger) {
    PeekingIterator<T> iterator = Iterators.peekingIterator(source.iterator());
    return StreamSupport.stream(
        new AbstractSpliterator<>(
            Long.MAX_VALUE,
            Spliterator.ORDERED) {
          @Override
          public boolean tryAdvance(Consumer<? super T> action) {
            if (!iterator.hasNext()) {
              return false;
            }
            T next = iterator.next();
            List<T> merged = new ArrayList<>();
            merged.add(next);
            while (iterator.hasNext() && accept.test(next, iterator.peek())) {
              next = iterator.next();
              merged.add(next);
            }
            action.accept(merged.size() > 1 ? merger.apply(merged) : next);
            return true;
          }
        },
        /* parallel= */ false);
  }
}
