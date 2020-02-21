// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.syntax;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
import javax.annotation.Nullable;

/**
 * A Sequence is a finite iterable sequence of Starlark values, such as a list or tuple.
 *
 * <p>Sequences implement the read-only operations of the {@link List} interface, but not its update
 * operations, similar to {@code ImmutableList}. The specification of {@code List} governs how such
 * methods behave and in particular how they report errors. Subclasses of sequence may define ad-hoc
 * mutator methods, such as {@link StarlarkList#extend}, exposed to Starlark, or Java, or both.
 *
 * <p>In principle, subclasses of Sequence could also define the standard update operations of List,
 * but there appears to be little demand, and doing so carries some risk of obscuring unintended
 * mutations to Starlark values that would currently cause the program to crash.
 */
@SkylarkModule(
    name = "sequence",
    documented = false,
    category = SkylarkModuleCategory.BUILTIN,
    doc = "common type of lists and tuples.")
public interface Sequence<E>
    extends StarlarkValue, List<E>, RandomAccess, SkylarkIndexable, StarlarkIterable<E> {

  @Override
  default boolean truth() {
    return !isEmpty();
  }

  /** Returns an ImmutableList object with the current underlying contents of this Sequence. */
  default ImmutableList<E> getImmutableList() {
    return ImmutableList.copyOf(this);
  }

  /** Retrieves an entry from a Sequence. */
  @Override
  default E getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
    int index = Starlark.toInt(key, "sequence index");
    return get(EvalUtils.getSequenceIndex(index, size()));
  }

  @Override
  default boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
    return contains(key);
  }

  /**
   * Returns the slice of this sequence, {@code this[start:stop:step]}. <br>
   * For positive strides ({@code step > 0}), {@code 0 <= start <= stop <= size()}. <br>
   * For negative strides ({@code step < 0}), {@code -1 <= stop <= start < size()}. <br>
   * The caller must ensure that the start and stop indices are valid and that step is non-zero.
   */
  Sequence<E> getSlice(Mutability mu, int start, int stop, int step);

  /**
   * Casts a {@code List<?>} to an unmodifiable {@code List<T>}, after checking that its contents
   * all have type {@code type}.
   *
   * <p>The returned list may or may not be a view that is affected by updates to the original list.
   *
   * @param list the original list to cast
   * @param type the expected type of all the list's elements
   * @param description a description of the argument being converted, or null, for debugging
   */
  // We could have used bounded generics to ensure that only downcasts are possible (i.e. cast
  // List<S> to List<T extends S>), but this would be less convenient for some callers, and it would
  // disallow casting an empty list to any type.
  // TODO(adonovan): this method doesn't belong here.
  @SuppressWarnings("unchecked")
  public static <T> List<T> castList(List<?> list, Class<T> type, @Nullable String description)
      throws EvalException {
    Object desc = description == null ? null : Printer.formattable("'%s' element", description);
    for (Object value : list) {
      SkylarkType.checkType(value, type, desc);
    }
    return Collections.unmodifiableList((List<T>) list);
  }

  /**
   * If {@code obj} is a {@code Sequence}, casts it to an unmodifiable {@code List<T>} after
   * checking that each element has type {@code type}. If {@code obj} is {@code None} or null,
   * treats it as an empty list. For all other values, throws an {@link EvalException}.
   *
   * <p>The returned list may or may not be a view that is affected by updates to the original list.
   *
   * @param obj the object to cast. null and None are treated as an empty list.
   * @param type the expected type of all the list's elements
   * @param description a description of the argument being converted, or null, for debugging
   */
  // TODO(adonovan): this method doesn't belong here.
  public static <T> List<T> castSkylarkListOrNoneToList(
      Object obj, Class<T> type, @Nullable String description) throws EvalException {
    if (EvalUtils.isNullOrNone(obj)) {
      return ImmutableList.of();
    }
    if (obj instanceof Sequence) {
      return ((Sequence<?>) obj).getContents(type, description);
    }
    throw Starlark.errorf(
        "Illegal argument: %s is not of expected type list or NoneType",
        description == null ? Starlark.repr(obj) : String.format("'%s'", description));
  }

  /**
   * Casts this list as an unmodifiable {@code List<T>}, after checking that each element has type
   * {@code type}.
   *
   * @param type the expected type of all the list's elements
   * @param description a description of the argument being converted, or null, for debugging
   */
  // TODO(adonovan): this method doesn't belong here.
  default <T> List<T> getContents(Class<T> type, @Nullable String description)
      throws EvalException {
    return castList(this, type, description);
  }
}
