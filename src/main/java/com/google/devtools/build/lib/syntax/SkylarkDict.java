// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.SkylarkList.Tuple;
import com.google.devtools.build.lib.syntax.StarlarkMutable.MutableMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A Skylark dictionary (dict).
 *
 * <p>Although this implements the {@link Map} interface, it is not mutable via that interface's
 * methods. Instead, use the mutators that take in a {@link Mutability} object.
 */
@SkylarkModule(
    name = "dict",
    category = SkylarkModuleCategory.BUILTIN,
    doc =
        "A language built-in type representing a dictionary (associative mapping). Dictionaries"
            + " are mutable, indexable, ordered, iterable (equivalent to iterating over its keys),"
            + " and support fast membership tests of keys using the <code>in</code> operator. The"
            + " order of the keys is the order of their most recent insertion: it is unaffected by"
            + " updating the value associated with an existing key, but is affected by removing"
            + " then reinserting a key.</p>\n"
            + "<pre>d = {0: 0, 2: 2, 1: 1}\n"
            + "[k for k in d]  # [0, 2, 1]\n"
            + "d.pop(2)\n"
            + "d[0], d[2] = \"a\", \"b\"\n"
            + "0 in d, \"a\" in d  # (True, False)\n"
            + "[(k, v) for k, v in d.items()]  # [(0, \"a\"), (1, 1), (2, \"b\")]\n"
            + "</pre>\n"
            + "<p>There are 3 ways to construct a dictionary, each with a different treatment of"
            + " duplicate keys:</p>\n"
            + "<p>The dict literal expression will result in a dynamic error if duplicate keys are"
            + " given, regardless of whether the keys are themselves given as literals. The keys'"
            + " insertion order is the order in which they are given in the expression.</p>\n"
            + "<p>In the dict comprehension, key/value pairs yielded by the generator expression is"
            + " set in the dictionary in the order yielded: the first occurrence of the key"
            + " determines its insertion order, and the last determines the value associated to"
            + " it.</p>\n"
            + "<pre class=\"language-python\">\n"
            + "{k: v for k, v in ((\"a\", 0), (\"b\", 1), (\"a\", 2))}  # {\"a\": 2, \"b\": 1}\n"
            + "{i: 2*i for i in range(3)}  # {0: 0, 1: 2, 2: 4}\n"
            + "</pre>\n"
            + "<p>The <a href=\"globals.html#dict\">dict()</a> global function is documented"
            + " elsewhere.<p>")
// TODO(b/64208606): eliminate these type parameters as they are wildly unsound.
// Starlark code may update a StarlarkDict in ways incompatible with its Java
// parameterized type. There is no realistic static or dynamic way to prevent
// this, as Java parameterized types are not accessible at runtime.
// Every cast to a parameterized type is a lie.
// Unchecked warnings should be treated as errors.
// Ditto SkylarkList.
public final class SkylarkDict<K, V> extends MutableMap<K, V>
    implements Map<K, V>, SkylarkIndexable {

  private final LinkedHashMap<K, V> contents = new LinkedHashMap<>();

  /** Final except for {@link #unsafeShallowFreeze}; must not be modified any other way. */
  private Mutability mutability;

  private SkylarkDict(@Nullable Mutability mutability) {
    this.mutability = mutability == null ? Mutability.IMMUTABLE : mutability;
  }

  private SkylarkDict(@Nullable StarlarkThread thread) {
    this.mutability = thread == null ? Mutability.IMMUTABLE : thread.mutability();
  }

  @SkylarkCallable(
      name = "get",
      doc =
          "Returns the value for <code>key</code> if <code>key</code> is in the dictionary, "
              + "else <code>default</code>. If <code>default</code> is not given, it defaults to "
              + "<code>None</code>, so that this method never throws an error.",
      parameters = {
        @Param(name = "key", noneable = true, doc = "The key to look for."),
        @Param(
            name = "default",
            defaultValue = "None",
            noneable = true,
            named = true,
            doc = "The default value to use (instead of None) if the key is not found.")
      },
      allowReturnNones = true,
      useStarlarkThread = true)
  public Object get(Object key, Object defaultValue, StarlarkThread thread) throws EvalException {
    if (containsKey(key, null, thread)) {
      return this.get(key);
    }
    return defaultValue;
  }

  @SkylarkCallable(
      name = "pop",
      doc =
          "Removes a <code>key</code> from the dict, and returns the associated value. "
              + "If no entry with that key was found, remove nothing and return the specified "
              + "<code>default</code> value; if no default value was specified, fail instead.",
      parameters = {
        @Param(name = "key", type = Object.class, doc = "The key.", noneable = true),
        @Param(
            name = "default",
            type = Object.class,
            defaultValue = "unbound",
            named = true,
            noneable = true,
            doc = "a default value if the key is absent."),
      },
      useLocation = true,
      useStarlarkThread = true)
  public Object pop(Object key, Object defaultValue, Location loc, StarlarkThread thread)
      throws EvalException {
    Object value = get(key);
    if (value != null) {
      remove(key, loc, thread.mutability());
      return value;
    }
    if (defaultValue != Runtime.UNBOUND) {
      return defaultValue;
    }
    throw new EvalException(loc, Printer.format("KeyError: %r", key));
  }

  @SkylarkCallable(
      name = "popitem",
      doc =
          "Remove and return an arbitrary <code>(key, value)</code> pair from the dictionary. "
              + "<code>popitem()</code> is useful to destructively iterate over a dictionary, "
              + "as often used in set algorithms. "
              + "If the dictionary is empty, calling <code>popitem()</code> fails. "
              + "It is deterministic which pair is returned.",
      useLocation = true,
      useStarlarkThread = true)
  public Tuple<Object> popitem(Location loc, StarlarkThread thread) throws EvalException {
    if (isEmpty()) {
      throw new EvalException(loc, "popitem(): dictionary is empty");
    }
    Object key = keySet().iterator().next();
    Object value = get(key);
    remove(key, loc, thread.mutability());
    return Tuple.of(key, value);
  }

  @SkylarkCallable(
      name = "setdefault",
      doc =
          "If <code>key</code> is in the dictionary, return its value. "
              + "If not, insert key with a value of <code>default</code> "
              + "and return <code>default</code>. "
              + "<code>default</code> defaults to <code>None</code>.",
      parameters = {
        @Param(name = "key", type = Object.class, doc = "The key."),
        @Param(
            name = "default",
            type = Object.class,
            defaultValue = "None",
            named = true,
            noneable = true,
            doc = "a default value if the key is absent."),
      },
      useLocation = true,
      useStarlarkThread = true)
  public Object setdefault(K key, V defaultValue, Location loc, StarlarkThread thread)
      throws EvalException {
    Object value = get(key);
    if (value != null) {
      return value;
    }
    put(key, defaultValue, loc, thread);
    return defaultValue;
  }

  @SkylarkCallable(
      name = "update",
      doc =
          "Update the dictionary with an optional positional argument <code>[pairs]</code> "
              + " and an optional set of keyword arguments <code>[, name=value[, ...]</code>\n"
              + "If the positional argument <code>pairs</code> is present, it must be "
              + "<code>None</code>, another <code>dict</code>, or some other iterable. "
              + "If it is another <code>dict</code>, then its key/value pairs are inserted. "
              + "If it is an iterable, it must provide a sequence of pairs (or other iterables "
              + "of length 2), each of which is treated as a key/value pair to be inserted.\n"
              + "For each <code>name=value</code> argument present, the name is converted to a "
              + "string and used as the key for an insertion into D, with its corresponding "
              + "value being <code>value</code>.",
      parameters = {
        @Param(
            name = "args",
            type = Object.class,
            defaultValue = "[]",
            doc =
                "Either a dictionary or a list of entries. Entries must be tuples or lists with "
                    + "exactly two elements: key, value."),
      },
      extraKeywords = @Param(name = "kwargs", doc = "Dictionary of additional entries."),
      useLocation = true,
      useStarlarkThread = true)
  public Runtime.NoneType update(
      Object args, SkylarkDict<?, ?> kwargs, Location loc, StarlarkThread thread)
      throws EvalException {
    // TODO(adonovan): opt: don't materialize dict; call put directly.

    // All these types and casts are lies.
    SkylarkDict<K, V> dict =
        args instanceof SkylarkDict
            ? (SkylarkDict<K, V>) args
            : getDictFromArgs("update", args, loc, thread);
    dict = SkylarkDict.plus(dict, (SkylarkDict<K, V>) kwargs, thread);
    putAll(dict, loc, thread.mutability(), thread);
    return Runtime.NONE;
  }

  @SkylarkCallable(
      name = "values",
      doc =
          "Returns the list of values:"
              + "<pre class=\"language-python\">"
              + "{2: \"a\", 4: \"b\", 1: \"c\"}.values() == [\"a\", \"b\", \"c\"]</pre>\n",
      useStarlarkThread = true)
  public MutableList<?> invoke(StarlarkThread thread) throws EvalException {
    return MutableList.copyOf(thread, values());
  }

  @SkylarkCallable(
      name = "items",
      doc =
          "Returns the list of key-value tuples:"
              + "<pre class=\"language-python\">"
              + "{2: \"a\", 4: \"b\", 1: \"c\"}.items() == [(2, \"a\"), (4, \"b\"), (1, \"c\")]"
              + "</pre>\n",
      useStarlarkThread = true)
  public MutableList<?> items(StarlarkThread thread) throws EvalException {
    ArrayList<Object> list = Lists.newArrayListWithCapacity(size());
    for (Map.Entry<?, ?> entries : entrySet()) {
      list.add(Tuple.of(entries.getKey(), entries.getValue()));
    }
    return MutableList.wrapUnsafe(thread, list);
  }

  @SkylarkCallable(
      name = "keys",
      doc =
          "Returns the list of keys:"
              + "<pre class=\"language-python\">{2: \"a\", 4: \"b\", 1: \"c\"}.keys() == [2, 4, 1]"
              + "</pre>\n",
      useStarlarkThread = true)
  public MutableList<?> keys(StarlarkThread thread) throws EvalException {
    ArrayList<Object> list = Lists.newArrayListWithCapacity(size());
    for (Map.Entry<?, ?> entries : entrySet()) {
      list.add(entries.getKey());
    }
    return MutableList.wrapUnsafe(thread, list);
  }

  private static final SkylarkDict<?, ?> EMPTY = withMutability(Mutability.IMMUTABLE);

  /** Returns an immutable empty dict. */
  // Safe because the empty singleton is immutable.
  @SuppressWarnings("unchecked")
  public static <K, V> SkylarkDict<K, V> empty() {
    return (SkylarkDict<K, V>) EMPTY;
  }

  /** Returns an empty dict with the given {@link Mutability}. */
  public static <K, V> SkylarkDict<K, V> withMutability(@Nullable Mutability mutability) {
    return new SkylarkDict<>(mutability);
  }

  /** @return a dict mutable in given environment only */
  public static <K, V> SkylarkDict<K, V> of(@Nullable StarlarkThread thread) {
    return new SkylarkDict<>(thread);
  }

  /** @return a dict mutable in given environment only, with given initial key and value */
  public static <K, V> SkylarkDict<K, V> of(@Nullable StarlarkThread thread, K k, V v) {
    return SkylarkDict.<K, V>of(thread).putUnsafe(k, v);
  }

  /** @return a dict mutable in given environment only, with two given initial key value pairs */
  public static <K, V> SkylarkDict<K, V> of(
      @Nullable StarlarkThread thread, K k1, V v1, K k2, V v2) {
    return SkylarkDict.<K, V>of(thread).putUnsafe(k1, v1).putUnsafe(k2, v2);
  }

  // TODO(bazel-team): Make other methods that take in mutabilities instead of environments, make
  // this method public.
  @VisibleForTesting
  static <K, V> SkylarkDict<K, V> copyOf(
      @Nullable Mutability mutability, Map<? extends K, ? extends V> m) {
    return SkylarkDict.<K, V>withMutability(mutability).putAllUnsafe(m);
  }

  /** @return a dict mutable in given environment only, with contents copied from given map */
  public static <K, V> SkylarkDict<K, V> copyOf(
      @Nullable StarlarkThread thread, Map<? extends K, ? extends V> m) {
    return SkylarkDict.<K, V>of(thread).putAllUnsafe(m);
  }

  /** Puts the given entry into the dict, without calling {@link #checkMutable}. */
  private SkylarkDict<K, V> putUnsafe(K k, V v) {
    contents.put(k, v);
    return this;
  }

  /** Puts all entries of the given map into the dict, without calling {@link #checkMutable}. */
  @SuppressWarnings("unchecked")
  private <KK extends K, VV extends V> SkylarkDict<K, V> putAllUnsafe(Map<KK, VV> m) {
    for (Map.Entry<KK, VV> e : m.entrySet()) {
      contents.put(e.getKey(), (VV) SkylarkType.convertToSkylark(e.getValue(), mutability));
    }
    return this;
  }

  @Override
  public Mutability mutability() {
    return mutability;
  }

  @Override
  public void unsafeShallowFreeze() {
    Mutability.Freezable.checkUnsafeShallowFreezePrecondition(this);
    this.mutability = Mutability.IMMUTABLE;
  }

  @Override
  protected Map<K, V> getContentsUnsafe() {
    return contents;
  }

  /**
   * Puts an entry into a dict, after validating that mutation is allowed.
   *
   * @param key the key of the added entry
   * @param value the value of the added entry
   * @param loc the location to use for error reporting
   * @param mutability the {@link Mutability} associated with the opreation
   * @throws EvalException if the key is invalid or the dict is frozen
   */
  public void put(K key, V value, Location loc, Mutability mutability) throws EvalException {
    checkMutable(loc, mutability);
    EvalUtils.checkValidDictKey(key, null);
    contents.put(key, value);
  }

  /**
   * Convenience version of {@link #put(K, V, Location, Mutability)} that uses the {@link
   * Mutability} of an {@link StarlarkThread}.
   */
  // TODO(bazel-team): Decide whether to eliminate this overload.
  public void put(K key, V value, Location loc, StarlarkThread thread) throws EvalException {
    checkMutable(loc, mutability);
    EvalUtils.checkValidDictKey(key, thread);
    contents.put(key, value);
  }

  /**
   * Puts all the entries from a given map into the dict, after validating that mutation is allowed.
   *
   * @param map the map whose entries are added
   * @param loc the location to use for error reporting
   * @param mutability the {@link Mutability} associated with the operation
   * @throws EvalException if some key is invalid or the dict is frozen
   */
  public <KK extends K, VV extends V> void putAll(
      Map<KK, VV> map, Location loc, Mutability mutability, StarlarkThread thread)
      throws EvalException {
    checkMutable(loc, mutability);
    for (Map.Entry<KK, VV> e : map.entrySet()) {
      KK k = e.getKey();
      EvalUtils.checkValidDictKey(k, thread);
      contents.put(k, e.getValue());
    }
  }

  /**
   * Deletes the entry associated with the given key.
   *
   * @param key the key to delete
   * @param loc the location to use for error reporting
   * @param mutability the {@link Mutability} associated with the operation
   * @return the value associated to the key, or {@code null} if not present
   * @throws EvalException if the dict is frozen
   */
  V remove(Object key, Location loc, Mutability mutability) throws EvalException {
    checkMutable(loc, mutability);
    return contents.remove(key);
  }

  @SkylarkCallable(
      name = "clear",
      doc = "Remove all items from the dictionary.",
      useLocation = true,
      useStarlarkThread = true)
  public Runtime.NoneType clearDict(Location loc, StarlarkThread thread) throws EvalException {
    clear(loc, thread.mutability());
    return Runtime.NONE;
  }

  /**
   * Clears the dict.
   *
   * @param loc the location to use for error reporting
   * @param mutability the {@link Mutability} associated with the operation
   * @throws EvalException if the dict is frozen
   */
  void clear(Location loc, Mutability mutability) throws EvalException {
    checkMutable(loc, mutability);
    contents.clear();
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.printList(entrySet(), "{", ", ", "}", null);
  }

  @Override
  public String toString() {
    return Printer.repr(this);
  }

  /**
   * If {@code obj} is a {@code SkylarkDict}, casts it to an unmodifiable {@code Map<K, V>} after
   * checking that each of its entries has key type {@code keyType} and value type {@code
   * valueType}. If {@code obj} is {@code None} or null, treats it as an empty dict.
   *
   * <p>The returned map may or may not be a view that is affected by updates to the original dict.
   *
   * @param obj the object to cast. null and None are treated as an empty dict.
   * @param keyType the expected type of all the dict's keys
   * @param valueType the expected type of all the dict's values
   * @param description a description of the argument being converted, or null, for debugging
   */
  public static <K, V> Map<K, V> castSkylarkDictOrNoneToDict(
      Object obj, Class<K> keyType, Class<V> valueType, @Nullable String description)
      throws EvalException {
    if (EvalUtils.isNullOrNone(obj)) {
      return empty();
    }
    if (obj instanceof SkylarkDict) {
      return ((SkylarkDict<?, ?>) obj).getContents(keyType, valueType, description);
    }
    throw new EvalException(
        null,
        String.format(
            "%s is not of expected type dict or NoneType",
            description == null ? Printer.repr(obj) : String.format("'%s'", description)));
  }

  /**
   * Returns an unmodifiable view of this StarlarkDict coerced to type {@code SkylarkDict<X, Y>},
   * after superficially checking that all keys and values are of class {@code keyType} and {@code
   * valueType} respectively.
   *
   * <p>The returned map is a view that reflects subsequent updates to the original dict. If such
   * updates should insert keys or values of types other than X or Y respectively, the reference
   * returned by getContents will have a false type that may cause the program to fail in unexpected
   * and hard-to-debug ways.
   *
   * <p>The dynamic checks are necessarily superficial if either of X or Y is itself a parameterized
   * type. For example, if Y is {@code List<String>}, getContents checks that the dict values are
   * instances of List, but it does not and cannot check that all the elements of those lists are
   * Strings. If one of the dict values in fact a List of Integer, the returned reference will again
   * have a false type.
   *
   * @param keyType the expected class of keys
   * @param valueType the expected class of values
   * @param description a description of the argument being converted, or null, for debugging
   */
  @SuppressWarnings("unchecked")
  public <X, Y> Map<X, Y> getContents(
      Class<X> keyType, Class<Y> valueType, @Nullable String description) throws EvalException {
    Object keyDescription = description == null
        ? null : Printer.formattable("'%s' key", description);
    Object valueDescription = description == null
        ? null : Printer.formattable("'%s' value", description);
    for (Map.Entry<?, ?> e : this.entrySet()) {
      SkylarkType.checkType(e.getKey(), keyType, keyDescription);
      if (e.getValue() != null) {
        SkylarkType.checkType(e.getValue(), valueType, valueDescription);
      }
    }
    return Collections.unmodifiableMap((SkylarkDict<X, Y>) this);
  }

  @Override
  public final Object getIndex(Object key, Location loc) throws EvalException {
    if (!this.containsKey(key)) {
      throw new EvalException(loc, Printer.format("key %r not found in dictionary", key));
    }
    return this.get(key);
  }

  @Override
  public final boolean containsKey(Object key, Location loc) throws EvalException {
    return this.containsKey(key);
  }

  @Override
  public final boolean containsKey(Object key, Location loc, StarlarkThread thread)
      throws EvalException {
    if (thread.getSemantics().incompatibleDisallowDictLookupUnhashableKeys()) {
      EvalUtils.checkValidDictKey(key, thread);
    }
    return this.containsKey(key);
  }

  public static <K, V> SkylarkDict<K, V> plus(
      SkylarkDict<? extends K, ? extends V> left,
      SkylarkDict<? extends K, ? extends V> right,
      @Nullable StarlarkThread thread) {
    SkylarkDict<K, V> result = SkylarkDict.of(thread);
    result.putAllUnsafe(left);
    result.putAllUnsafe(right);
    return result;
  }

  static <K, V> SkylarkDict<K, V> getDictFromArgs(
      String funcname, Object args, Location loc, @Nullable StarlarkThread thread)
      throws EvalException {
    Iterable<?> seq;
    try {
      seq = EvalUtils.toIterable(args, loc, thread);
    } catch (EvalException ex) {
      throw new EvalException(
          loc,
          String.format("in %s, got %s, want iterable", funcname, EvalUtils.getDataTypeName(args)));
    }
    SkylarkDict<K, V> result = SkylarkDict.of(thread);
    int pos = 0;
    for (Object item : seq) {
      Iterable<?> seq2;
      try {
        seq2 = EvalUtils.toIterable(item, loc, null);
      } catch (EvalException ex) {
        throw new EvalException(
            loc,
            String.format(
                "in %s, dictionary update sequence element #%d is not iterable (%s)",
                funcname, pos, EvalUtils.getDataTypeName(item)));
      }
      // TODO(adonovan): opt: avoid unnecessary allocations and copies.
      // Why is there no operator to compute len(x), following the spec, without iterating??
      List<Object> pair = Lists.newArrayList(seq2);
      if (pair.size() != 2) {
        throw new EvalException(
            loc,
            String.format(
                "in %s, item #%d has length %d, but exactly two elements are required",
                funcname, pos, pair.size()));
      }
      // These casts are lies
      result.put((K) pair.get(0), (V) pair.get(1), loc, thread);
      pos++;
    }
    return result;
  }

}
