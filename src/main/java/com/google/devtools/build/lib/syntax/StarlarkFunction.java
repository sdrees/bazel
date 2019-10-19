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
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.StarlarkThread.LexicalFrame;

/** A StarlarkFunction is the function value created by a Starlark {@code def} statement. */
public final class StarlarkFunction extends BaseFunction {

  private final ImmutableList<Statement> statements;

  // we close over the globals at the time of definition
  private final Module definitionGlobals;

  // TODO(adonovan): make this private. The CodecTests should go through interpreter to instantiate
  // such things.
  public StarlarkFunction(
      String name,
      Location location,
      FunctionSignature signature,
      ImmutableList<Object> defaultValues,
      ImmutableList<Statement> statements,
      Module definitionGlobals) {
    super(name, signature, defaultValues, location);
    this.statements = statements;
    this.definitionGlobals = definitionGlobals;
  }

  public ImmutableList<Statement> getStatements() {
    return statements;
  }

  public Module getDefinitionGlobals() {
    return definitionGlobals;
  }

  @Override
  protected Object call(Object[] arguments, FuncallExpression ast, StarlarkThread thread)
      throws EvalException, InterruptedException {
    if (thread.mutability().isFrozen()) {
      throw new EvalException(getLocation(), "Trying to call in frozen environment");
    }
    if (thread.isRecursiveCall(this)) {
      throw new EvalException(
          getLocation(),
          String.format(
              "Recursion was detected when calling '%s' from '%s'",
              getName(), thread.getCurrentFunction().getName()));
    }

    ImmutableList<String> names = signature.getParameterNames();
    LexicalFrame lexicalFrame = LexicalFrame.create(thread.mutability(), /*numArgs=*/ names.size());
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.STARLARK_USER_FN, getName())) {
      thread.enterScope(this, lexicalFrame, ast, definitionGlobals);

      // Registering the functions's arguments as variables in the local StarlarkThread
      // foreach loop is not used to avoid iterator overhead
      for (int i = 0; i < names.size(); ++i) {
        thread.update(names.get(i), arguments[i]);
      }

      return Eval.execStatements(thread, statements);
    } finally {
      thread.exitScope();
    }
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    Object label = this.definitionGlobals.getLabel();

    printer.append("<function " + getName());
    if (label != null) {
      printer.append(" from " + label);
    }
    printer.append(">");
  }
}
