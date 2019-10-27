// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Location;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** A syntax-tree-walking evaluator. */
// TODO(adonovan): make this class the sole locus of tree-based evaluation logic.
// Make all its methods static, and thread StarlarkThread (soon: StarlarkThread) explicitly.
// The only actual state is the return value, which can be saved in the StarlarkThread's call frame.
// Move remaining Expression.eval logic here, and simplify.
final class Eval {

  private static final AtomicReference<Debugger> debugger = new AtomicReference<>();

  private final StarlarkThread thread;
  private final Debugger dbg;
  private Object result = Runtime.NONE;

  // ---- entry points ----

  static void setDebugger(Debugger dbg) {
    Debugger prev = debugger.getAndSet(dbg);
    if (prev != null) {
      prev.close();
    }
  }

  static void execFile(StarlarkThread thread, StarlarkFile file)
      throws EvalException, InterruptedException {
    for (Statement stmt : file.getStatements()) {
      execToplevelStatement(thread, stmt);
    }
  }

  static Object execStatements(StarlarkThread thread, List<Statement> statements)
      throws EvalException, InterruptedException {
    Eval eval = new Eval(thread);
    eval.execStatementsInternal(statements);
    return eval.result;
  }

  static void execToplevelStatement(StarlarkThread thread, Statement stmt)
      throws EvalException, InterruptedException {
    // Ignore the returned BREAK/CONTINUE/RETURN/PASS token:
    // the first three don't exist at top-level, and the last is a no-op.
    new Eval(thread).exec(stmt);

    // Hack for SkylarkImportLookupFunction's "export" semantics.
    if (thread.postAssignHook != null) {
      if (stmt instanceof AssignmentStatement) {
        AssignmentStatement assign = (AssignmentStatement) stmt;
        for (Identifier id : Identifier.boundIdentifiers(assign.getLHS())) {
          String name = id.getName();
          Object value = thread.moduleLookup(name);
          thread.postAssignHook.assign(name, value);
        }
      }
    }
  }

  private Eval(StarlarkThread thread) {
    this.thread = thread;
    this.dbg = debugger.get(); // capture value and use for lifetime of one Eval
  }

  private void execAssignment(AssignmentStatement node) throws EvalException, InterruptedException {
    Object rvalue = eval(thread, node.getRHS());
    assign(node.getLHS(), rvalue, thread, node.getLocation());
  }

  private TokenKind execFor(ForStatement node) throws EvalException, InterruptedException {
    Object o = eval(thread, node.getCollection());
    Iterable<?> col = EvalUtils.toIterable(o, node.getLocation(), thread);
    EvalUtils.lock(o, node.getLocation());
    try {
      for (Object it : col) {
        assign(node.getLHS(), it, thread, node.getLocation());

        switch (execStatementsInternal(node.getBlock())) {
          case PASS:
          case CONTINUE:
            // Stay in loop.
            continue;
          case BREAK:
            // Finish loop, execute next statement after loop.
            return TokenKind.PASS;
          case RETURN:
            // Finish loop, return from function.
            return TokenKind.RETURN;
          default:
            throw new IllegalStateException("unreachable");
        }
      }
    } finally {
      EvalUtils.unlock(o, node.getLocation());
    }
    return TokenKind.PASS;
  }

  private void execDef(DefStatement node) throws EvalException, InterruptedException {
    ArrayList<Object> defaultValues = null;
    for (Parameter param : node.getParameters()) {
      if (param.getDefaultValue() != null) {
        if (defaultValues == null) {
          defaultValues = new ArrayList<>(node.getSignature().numOptionals());
        }
        defaultValues.add(eval(thread, param.getDefaultValue()));
      }
    }

    // TODO(laurentlb): move to Parser or ValidationEnvironment.
    FunctionSignature sig = node.getSignature();
    if (sig.numMandatoryNamedOnly() > 0) {
      throw new EvalException(node.getLocation(), "Keyword-only argument is forbidden.");
    }

    thread.updateAndExport(
        node.getIdentifier().getName(),
        new StarlarkFunction(
            node.getIdentifier().getName(),
            node.getIdentifier().getLocation(),
            sig,
            defaultValues != null ? ImmutableList.copyOf(defaultValues) : null,
            node.getStatements(),
            thread.getGlobals()));
  }

  private TokenKind execIf(IfStatement node) throws EvalException, InterruptedException {
    boolean cond = EvalUtils.toBoolean(eval(thread, node.getCondition()));
    if (cond) {
      return execStatementsInternal(node.getThenBlock());
    } else if (node.getElseBlock() != null) {
      return execStatementsInternal(node.getElseBlock());
    }
    return TokenKind.PASS;
  }

  private void execLoad(LoadStatement node) throws EvalException, InterruptedException {
    for (LoadStatement.Binding binding : node.getBindings()) {
      try {
        Identifier name = binding.getLocalName();
        Identifier declared = binding.getOriginalName();

        if (declared.isPrivate() && !node.mayLoadInternalSymbols()) {
          throw new EvalException(
              node.getLocation(),
              "symbol '" + declared.getName() + "' is private and cannot be imported.");
        }
        // The key is the original name that was used to define the symbol
        // in the loaded bzl file.
        thread.importSymbol(node.getImport().getValue(), name, declared.getName());
      } catch (StarlarkThread.LoadFailedException e) {
        throw new EvalException(node.getLocation(), e.getMessage());
      }
    }
  }

  private TokenKind execReturn(ReturnStatement node) throws EvalException, InterruptedException {
    Expression ret = node.getReturnExpression();
    if (ret != null) {
      this.result = eval(thread, ret);
    }
    return TokenKind.RETURN;
  }

  private TokenKind exec(Statement st) throws EvalException, InterruptedException {
    if (dbg != null) {
      dbg.before(thread, st.getLocation());
    }

    try {
      return execDispatch(st);
    } catch (EvalException ex) {
      throw maybeTransformException(st, ex);
    }
  }

  private TokenKind execDispatch(Statement st) throws EvalException, InterruptedException {
    switch (st.kind()) {
      case ASSIGNMENT:
        execAssignment((AssignmentStatement) st);
        return TokenKind.PASS;
      case AUGMENTED_ASSIGNMENT:
        execAugmentedAssignment((AugmentedAssignmentStatement) st);
        return TokenKind.PASS;
      case EXPRESSION:
        eval(thread, ((ExpressionStatement) st).getExpression());
        return TokenKind.PASS;
      case FLOW:
        return ((FlowStatement) st).getKind();
      case FOR:
        return execFor((ForStatement) st);
      case FUNCTION_DEF:
        execDef((DefStatement) st);
        return TokenKind.PASS;
      case IF:
        return execIf((IfStatement) st);
      case LOAD:
        execLoad((LoadStatement) st);
        return TokenKind.PASS;
      case RETURN:
        return execReturn((ReturnStatement) st);
    }
    throw new IllegalArgumentException("unexpected statement: " + st.kind());
  }

  private TokenKind execStatementsInternal(List<Statement> statements)
      throws EvalException, InterruptedException {
    // Hot code path, good chance of short lists which don't justify the iterator overhead.
    for (int i = 0; i < statements.size(); i++) {
      TokenKind flow = exec(statements.get(i));
      if (flow != TokenKind.PASS) {
        return flow;
      }
    }
    return TokenKind.PASS;
  }

  /**
   * Updates the environment bindings, and possibly mutates objects, so as to assign the given value
   * to the given expression. The expression must be valid for an {@code LValue}.
   */
  private static void assign(Expression expr, Object value, StarlarkThread thread, Location loc)
      throws EvalException, InterruptedException {
    if (expr instanceof Identifier) {
      assignIdentifier((Identifier) expr, value, thread);
    } else if (expr instanceof IndexExpression) {
      Object object = eval(thread, ((IndexExpression) expr).getObject());
      Object key = eval(thread, ((IndexExpression) expr).getKey());
      assignItem(object, key, value, thread, loc);
    } else if (expr instanceof ListExpression) {
      ListExpression list = (ListExpression) expr;
      assignList(list, value, thread, loc);
    } else {
      // Not possible for validated ASTs.
      throw new EvalException(loc, "cannot assign to '" + expr + "'");
    }
  }

  /** Binds a variable to the given value in the environment. */
  private static void assignIdentifier(Identifier ident, Object value, StarlarkThread thread)
      throws EvalException {
    thread.updateAndExport(ident.getName(), value);
  }

  /**
   * Adds or changes an object-key-value relationship for a list or dict.
   *
   * <p>For a list, the key is an in-range index. For a dict, it is a hashable value.
   *
   * @throws EvalException if the object is not a list or dict
   */
  @SuppressWarnings("unchecked")
  private static void assignItem(
      Object object, Object key, Object value, StarlarkThread thread, Location loc)
      throws EvalException {
    if (object instanceof SkylarkDict) {
      SkylarkDict<Object, Object> dict = (SkylarkDict<Object, Object>) object;
      dict.put(key, value, loc, thread);
    } else if (object instanceof SkylarkList.MutableList) {
      SkylarkList.MutableList<Object> list = (SkylarkList.MutableList<Object>) object;
      int index = EvalUtils.getSequenceIndex(key, list.size(), loc);
      list.set(index, value, loc, thread.mutability());
    } else {
      throw new EvalException(
          loc,
          "can only assign an element in a dictionary or a list, not in a '"
              + EvalUtils.getDataTypeName(object)
              + "'");
    }
  }

  /**
   * Recursively assigns an iterable value to a sequence of assignable expressions.
   *
   * @throws EvalException if the list literal has length 0, or if the value is not an iterable of
   *     matching length
   */
  private static void assignList(
      ListExpression list, Object value, StarlarkThread thread, Location loc)
      throws EvalException, InterruptedException {
    Collection<?> collection = EvalUtils.toCollection(value, loc, thread);
    int len = list.getElements().size();
    if (len == 0) {
      throw new EvalException(
          loc, "lists or tuples on the left-hand side of assignments must have at least one item");
    }
    if (len != collection.size()) {
      throw new EvalException(
          loc,
          String.format(
              "assignment length mismatch: left-hand side has length %d, but right-hand side"
                  + " evaluates to value of length %d",
              len, collection.size()));
    }
    int i = 0;
    for (Object item : collection) {
      assign(list.getElements().get(i), item, thread, loc);
      i++;
    }
  }

  /**
   * Evaluates an augmented assignment that mutates this {@code LValue} with the given right-hand
   * side's value.
   *
   * <p>The left-hand side expression is evaluated only once, even when it is an {@link
   * IndexExpression}. The left-hand side is evaluated before the right-hand side to match Python's
   * behavior (hence why the right-hand side is passed as an expression rather than as an evaluated
   * value).
   */
  private void execAugmentedAssignment(AugmentedAssignmentStatement stmt)
      throws EvalException, InterruptedException {
    Expression lhs = stmt.getLHS();
    TokenKind op = stmt.getOperator();
    Expression rhs = stmt.getRHS();
    Location loc = stmt.getLocation();

    if (lhs instanceof Identifier) {
      Object x = eval(thread, lhs);
      Object y = eval(thread, rhs);
      Object z = inplaceBinaryOp(op, x, y, thread, loc);
      assignIdentifier((Identifier) lhs, z, thread);
    } else if (lhs instanceof IndexExpression) {
      // object[index] op= y
      // The object and key should be evaluated only once, so we don't use lhs.eval().
      IndexExpression index = (IndexExpression) lhs;
      Object object = eval(thread, index.getObject());
      Object key = eval(thread, index.getKey());
      Object x = EvalUtils.index(object, key, thread, loc);
      // Evaluate rhs after lhs.
      Object y = eval(thread, rhs);
      Object z = inplaceBinaryOp(op, x, y, thread, loc);
      assignItem(object, key, z, thread, loc);
    } else if (lhs instanceof ListExpression) {
      throw new EvalException(loc, "cannot perform augmented assignment on a list literal");
    } else {
      // Not possible for validated ASTs.
      throw new EvalException(loc, "cannot perform augmented assignment on '" + lhs + "'");
    }
  }

  private static Object inplaceBinaryOp(
      TokenKind op, Object x, Object y, StarlarkThread thread, Location location)
      throws EvalException, InterruptedException {
    // list += iterable  behaves like  list.extend(iterable)
    // TODO(b/141263526): following Python, allow list+=iterable (but not list+iterable).
    if (op == TokenKind.PLUS
        && x instanceof SkylarkList.MutableList
        && y instanceof SkylarkList.MutableList) {
      SkylarkList.MutableList<?> list = (SkylarkList.MutableList) x;
      list.extend(y, location, thread);
      return list;
    }
    return EvalUtils.binaryOp(op, x, y, thread, location);
  }

  // ---- expressions ----

  /**
   * Returns the result of evaluating this build-language expression in the specified environment.
   * All BUILD language datatypes are mapped onto the corresponding Java types as follows:
   *
   * <pre>
   *    int   -> Integer
   *    float -> Double          (currently not generated by the grammar)
   *    str   -> String
   *    [...] -> List&lt;Object>    (mutable)
   *    (...) -> List&lt;Object>    (immutable)
   *    {...} -> Map&lt;Object, Object>
   *    func  -> Function
   * </pre>
   *
   * @return the result of evaluting the expression: a Java object corresponding to a datatype in
   *     the BUILD language.
   * @throws EvalException if the expression could not be evaluated.
   * @throws InterruptedException may be thrown in a sub class.
   */
  static Object eval(StarlarkThread thread, Expression expr)
      throws EvalException, InterruptedException {
    // TODO(adonovan): don't push and pop all the time. We should only need the stack of function
    // call frames, and we should recycle them.
    // TODO(adonovan): put the StarlarkThread (Starlark thread) into the Java thread-local store
    // once only, in enterScope, and undo this in exitScope.
    try {
      if (Callstack.enabled) {
        Callstack.push(expr);
      }
      try {
        return doEval(thread, expr);
      } catch (EvalException ex) {
        throw maybeTransformException(expr, ex);
      }
    } finally {
      if (Callstack.enabled) {
        Callstack.pop();
      }
    }
  }

  private static Object doEval(StarlarkThread thread, Expression expr)
      throws EvalException, InterruptedException {
    switch (expr.kind()) {
      case BINARY_OPERATOR:
        {
          BinaryOperatorExpression binop = (BinaryOperatorExpression) expr;
          Object x = eval(thread, binop.getX());
          // AND and OR require short-circuit evaluation.
          switch (binop.getOperator()) {
            case AND:
              return EvalUtils.toBoolean(x) ? eval(thread, binop.getY()) : x;
            case OR:
              return EvalUtils.toBoolean(x) ? x : eval(thread, binop.getY());
            default:
              Object y = eval(thread, binop.getY());
              return EvalUtils.binaryOp(binop.getOperator(), x, y, thread, binop.getLocation());
          }
        }

      case COMPREHENSION:
        return evalComprehension(thread, (Comprehension) expr);

      case CONDITIONAL:
        {
          ConditionalExpression cond = (ConditionalExpression) expr;
          Object v = eval(thread, cond.getCondition());
          return eval(thread, EvalUtils.toBoolean(v) ? cond.getThenCase() : cond.getElseCase());
        }

      case DICT_EXPR:
        {
          DictExpression dictexpr = (DictExpression) expr;
          SkylarkDict<Object, Object> dict = SkylarkDict.of(thread);
          Location loc = dictexpr.getLocation();
          for (DictExpression.Entry entry : dictexpr.getEntries()) {
            Object k = eval(thread, entry.getKey());
            Object v = eval(thread, entry.getValue());
            int before = dict.size();
            dict.put(k, v, loc, thread);
            if (dict.size() == before) {
              throw new EvalException(
                  loc, "Duplicated key " + Printer.repr(k) + " when creating dictionary");
            }
          }
          return dict;
        }

      case DOT:
        {
          DotExpression dot = (DotExpression) expr;
          Object object = eval(thread, dot.getObject());
          String name = dot.getField().getName();
          Object result = EvalUtils.getAttr(thread, dot.getLocation(), object, name);
          return checkResult(object, result, name, dot.getLocation(), thread.getSemantics());
        }

      case FUNCALL:
        {
          FuncallExpression call = (FuncallExpression) expr;

          ArrayList<Object> posargs = new ArrayList<>();
          Map<String, Object> kwargs = new LinkedHashMap<>();

          // Optimization: call x.f() without materializing
          // a closure for x.f if f is a Java method.
          if (call.getFunction() instanceof DotExpression) {
            DotExpression dot = (DotExpression) call.getFunction();
            Object object = eval(thread, dot.getObject());
            evalArguments(thread, call, posargs, kwargs);
            return CallUtils.callMethod(
                thread, call, object, posargs, kwargs, dot.getField().getName(), dot.getLocation());
          }

          Object fn = eval(thread, call.getFunction());
          evalArguments(thread, call, posargs, kwargs);
          return CallUtils.call(thread, call, fn, posargs, kwargs);
        }

      case IDENTIFIER:
        {
          Identifier id = (Identifier) expr;
          String name = id.getName();
          if (id.getScope() == null) {
            // Legacy behavior, to be removed.
            Object result = thread.lookup(name);
            if (result == null) {
              String error =
                  ValidationEnvironment.createInvalidIdentifierException(
                      id.getName(), thread.getVariableNames());
              throw new EvalException(id.getLocation(), error);
            }
            return result;
          }

          Object result;
          switch (id.getScope()) {
            case Local:
              result = thread.localLookup(name);
              break;
            case Module:
              result = thread.moduleLookup(name);
              break;
            case Universe:
              result = thread.universeLookup(name);
              break;
            default:
              throw new IllegalStateException(id.getScope().toString());
          }
          if (result == null) {
            // Since Scope was set, we know that the variable is defined in the scope.
            // However, the assignment was not yet executed.
            String error = ValidationEnvironment.getErrorForObsoleteThreadLocalVars(id.getName());
            if (error == null) {
              error =
                  id.getScope().getQualifier()
                      + " variable '"
                      + name
                      + "' is referenced before assignment.";
            }
            throw new EvalException(id.getLocation(), error);
          }
          return result;
        }

      case INDEX:
        {
          IndexExpression index = (IndexExpression) expr;
          Object object = eval(thread, index.getObject());
          Object key = eval(thread, index.getKey());
          return EvalUtils.index(object, key, thread, index.getLocation());
        }

      case INTEGER_LITERAL:
        return ((IntegerLiteral) expr).getValue();

      case LIST_EXPR:
        {
          ListExpression list = (ListExpression) expr;
          ArrayList<Object> result = new ArrayList<>(list.getElements().size());
          for (Expression elem : list.getElements()) {
            result.add(eval(thread, elem));
          }
          return list.isTuple()
              ? SkylarkList.Tuple.copyOf(result) // TODO(adonovan): opt: avoid copy
              : SkylarkList.MutableList.wrapUnsafe(thread, result);
        }

      case SLICE:
        {
          SliceExpression slice = (SliceExpression) expr;
          Object object = eval(thread, slice.getObject());
          Object start = slice.getStart() == null ? Runtime.NONE : eval(thread, slice.getStart());
          Object end = slice.getEnd() == null ? Runtime.NONE : eval(thread, slice.getEnd());
          Object step = slice.getStep() == null ? Runtime.NONE : eval(thread, slice.getStep());
          Location loc = slice.getLocation();

          // TODO(adonovan): move the rest into a public EvalUtils.slice() operator.

          if (object instanceof SkylarkList) {
            return ((SkylarkList<?>) object).getSlice(start, end, step, loc, thread.mutability());
          }

          if (object instanceof String) {
            String string = (String) object;
            List<Integer> indices =
                EvalUtils.getSliceIndices(start, end, step, string.length(), loc);
            // TODO(adonovan): opt: optimize for common case, step=1.
            char[] result = new char[indices.size()];
            char[] original = string.toCharArray();
            int resultIndex = 0;
            for (int originalIndex : indices) {
              result[resultIndex] = original[originalIndex];
              ++resultIndex;
            }
            return new String(result);
          }

          throw new EvalException(
              loc,
              String.format(
                  "type '%s' has no operator [:](%s, %s, %s)",
                  EvalUtils.getDataTypeName(object),
                  EvalUtils.getDataTypeName(start),
                  EvalUtils.getDataTypeName(end),
                  EvalUtils.getDataTypeName(step)));
        }

      case STRING_LITERAL:
        return ((StringLiteral) expr).getValue();

      case UNARY_OPERATOR:
        {
          UnaryOperatorExpression unop = (UnaryOperatorExpression) expr;
          Object x = eval(thread, unop.getX());
          return EvalUtils.unaryOp(unop.getOperator(), x, unop.getLocation());
        }
    }
    throw new IllegalArgumentException("unexpected expression: " + expr.kind());
  }

  private static Object evalComprehension(StarlarkThread thread, Comprehension comp)
      throws EvalException, InterruptedException {
    final SkylarkDict<Object, Object> dict = comp.isDict() ? SkylarkDict.of(thread) : null;
    final ArrayList<Object> list = comp.isDict() ? null : new ArrayList<>();

    // Save values of all variables bound in a 'for' clause
    // so we can restore them later.
    // TODO(adonovan) throw all this away when we implement flat environments.
    List<Object> saved = new ArrayList<>(); // alternating keys and values
    for (Comprehension.Clause clause : comp.getClauses()) {
      if (clause instanceof Comprehension.For) {
        for (Identifier ident :
            Identifier.boundIdentifiers(((Comprehension.For) clause).getVars())) {
          String name = ident.getName();
          Object value = thread.localLookup(ident.getName());
          saved.add(name);
          saved.add(value);
        }
      }
    }

    // The Lambda class serves as a recursive lambda closure.
    class Lambda {
      // execClauses(index) recursively executes the clauses starting at index,
      // and finally evaluates the body and adds its value to the result.
      void execClauses(int index) throws EvalException, InterruptedException {
        // recursive case: one or more clauses
        if (index < comp.getClauses().size()) {
          Comprehension.Clause clause = comp.getClauses().get(index);
          if (clause instanceof Comprehension.For) {
            Comprehension.For forClause = (Comprehension.For) clause;

            Object iterable = eval(thread, forClause.getIterable());
            Location loc = comp.getLocation();
            Iterable<?> listValue = EvalUtils.toIterable(iterable, loc, thread);
            EvalUtils.lock(iterable, loc);
            try {
              for (Object elem : listValue) {
                assign(forClause.getVars(), elem, thread, loc);
                execClauses(index + 1);
              }
            } finally {
              EvalUtils.unlock(iterable, loc);
            }

          } else {
            Comprehension.If ifClause = (Comprehension.If) clause;
            if (EvalUtils.toBoolean(eval(thread, ifClause.getCondition()))) {
              execClauses(index + 1);
            }
          }
          return;
        }

        // base case: evaluate body and add to result.
        if (dict != null) {
          DictExpression.Entry body = (DictExpression.Entry) comp.getBody();
          Object k = eval(thread, body.getKey());
          EvalUtils.checkValidDictKey(k, thread);
          Object v = eval(thread, body.getValue());
          dict.put(k, v, comp.getLocation(), thread);
        } else {
          list.add(eval(thread, ((Expression) comp.getBody())));
        }
      }
    }
    new Lambda().execClauses(0);

    // Restore outer scope variables.
    // This loop implicitly undefines comprehension variables.
    for (int i = 0; i != saved.size(); ) {
      String name = (String) saved.get(i++);
      Object value = saved.get(i++);
      thread.updateInternal(name, value);
    }

    return comp.isDict() ? dict : SkylarkList.MutableList.copyOf(thread, list);
  }

  /** Returns an exception which should be thrown instead of the original one. */
  private static EvalException maybeTransformException(Node node, EvalException original) {
    // If there is already a non-empty stack trace, we only add this node iff it describes a
    // new scope (e.g. FuncallExpression).
    if (original instanceof EvalExceptionWithStackTrace) {
      EvalExceptionWithStackTrace real = (EvalExceptionWithStackTrace) original;
      if (node instanceof FuncallExpression) {
        real.registerNode(node);
      }
      return real;
    }

    if (original.canBeAddedToStackTrace()) {
      return new EvalExceptionWithStackTrace(original, node);
    } else {
      return original;
    }
  }

  /** Throws the correct error message if the result is null depending on the objValue. */
  // TODO(adonovan): inline sole call and simplify.
  private static Object checkResult(
      Object objValue, Object result, String name, Location loc, StarlarkSemantics semantics)
      throws EvalException {
    if (result != null) {
      return result;
    }
    throw EvalUtils.getMissingFieldException(objValue, name, loc, semantics, "field");
  }

  /**
   * Add one named argument to the keyword map, and returns whether that name has been encountered
   * before.
   */
  private static boolean addKeywordArgAndCheckIfDuplicate(
      Map<String, Object> kwargs, String name, Object value) {
    return kwargs.put(name, value) != null;
  }

  /**
   * Add multiple arguments to the keyword map (**kwargs), and returns all the names of those
   * arguments that have been encountered before or {@code null} if there are no such names.
   */
  @Nullable
  private static ImmutableList<String> addKeywordArgsAndReturnDuplicates(
      Map<String, Object> kwargs, Object items, Location location) throws EvalException {
    if (!(items instanceof Map<?, ?>)) {
      throw new EvalException(
          location,
          "argument after ** must be a dictionary, not '" + EvalUtils.getDataTypeName(items) + "'");
    }
    ImmutableList.Builder<String> duplicatesBuilder = null;
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) items).entrySet()) {
      if (!(entry.getKey() instanceof String)) {
        throw new EvalException(
            location,
            "keywords must be strings, not '" + EvalUtils.getDataTypeName(entry.getKey()) + "'");
      }
      String argName = (String) entry.getKey();
      if (addKeywordArgAndCheckIfDuplicate(kwargs, argName, entry.getValue())) {
        if (duplicatesBuilder == null) {
          duplicatesBuilder = ImmutableList.builder();
        }
        duplicatesBuilder.add(argName);
      }
    }
    return duplicatesBuilder == null ? null : duplicatesBuilder.build();
  }

  /**
   * Evaluate this FuncallExpression's arguments, and put the resulting evaluated expressions into
   * the given {@code posargs} and {@code kwargs} collections.
   *
   * @param posargs a list to which all positional arguments will be added
   * @param kwargs a mutable map to which all keyword arguments will be added. A mutable map is used
   *     here instead of an immutable map builder to deal with duplicates without memory overhead
   * @param thread the Starlark thread for the call
   */
  @SuppressWarnings("unchecked")
  private static void evalArguments(
      StarlarkThread thread,
      FuncallExpression call,
      List<Object> posargs,
      Map<String, Object> kwargs)
      throws EvalException, InterruptedException {

    // Optimize allocations for the common case where they are no duplicates.
    ImmutableList.Builder<String> duplicatesBuilder = null;
    // Iterate over the arguments. We assume all positional arguments come before any keyword
    // or star arguments, because the argument list was already validated by the Parser,
    // which should be the only place that build FuncallExpression-s.
    // Argument lists are typically short and functions are frequently called, so go by index
    // (O(1) for ImmutableList) to avoid the iterator overhead.
    for (int i = 0; i < call.getArguments().size(); i++) {
      Argument arg = call.getArguments().get(i);
      Object value = eval(thread, arg.getValue());
      if (arg instanceof Argument.Positional) {
        // f(expr)
        posargs.add(value);
      } else if (arg instanceof Argument.Star) {
        // f(*args): expand args
        if (!(value instanceof Iterable)) {
          throw new EvalException(
              call.getLocation(),
              "argument after * must be an iterable, not " + EvalUtils.getDataTypeName(value));
        }
        for (Object starArgUnit : (Iterable<Object>) value) {
          posargs.add(starArgUnit);
        }
      } else if (arg instanceof Argument.StarStar) {
        // f(**kwargs): expand kwargs
        ImmutableList<String> duplicates =
            addKeywordArgsAndReturnDuplicates(kwargs, value, call.getLocation());
        if (duplicates != null) {
          if (duplicatesBuilder == null) {
            duplicatesBuilder = ImmutableList.builder();
          }
          duplicatesBuilder.addAll(duplicates);
        }
      } else {
        // f(id=expr)
        String name = arg.getName();
        if (addKeywordArgAndCheckIfDuplicate(kwargs, name, value)) {
          if (duplicatesBuilder == null) {
            duplicatesBuilder = ImmutableList.builder();
          }
          duplicatesBuilder.add(name);
        }
      }
    }
    if (duplicatesBuilder != null) {
      ImmutableList<String> dups = duplicatesBuilder.build();
      throw new EvalException(
          call.getLocation(),
          "duplicate keyword"
              + (dups.size() > 1 ? "s" : "")
              + " '"
              + Joiner.on("', '").join(dups)
              + "' in call to "
              + call.getFunction());
    }
  }
}
