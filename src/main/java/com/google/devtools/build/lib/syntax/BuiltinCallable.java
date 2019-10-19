// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A BuiltinCallable is a callable Starlark value that reflectively invokes a method of a Java
 * object.
 */
// TODO(adonovan): make this private. Most users would be content with StarlarkCallable; the rest
// need only a means of querying the function's parameters.
public final class BuiltinCallable implements StarlarkCallable {

  private final Object obj;
  private final String methodName;

  public BuiltinCallable(Object obj, String methodName) {
    this.obj = obj;
    this.methodName = methodName;
  }

  @Override
  public Object call(
      List<Object> args,
      @Nullable Map<String, Object> kwargs,
      FuncallExpression ast,
      StarlarkThread thread)
      throws EvalException, InterruptedException {
    MethodDescriptor methodDescriptor = getMethodDescriptor(thread.getSemantics());
    Class<?> clazz;
    Object objValue;

    if (obj instanceof String) {
      args.add(0, obj);
      clazz = StringModule.class;
      objValue = StringModule.INSTANCE;
    } else {
      clazz = obj.getClass();
      objValue = obj;
    }

    // TODO(cparsons): Profiling should be done at the MethodDescriptor level.
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.STARLARK_BUILTIN_FN, methodName)) {
      Object[] javaArguments =
          CallUtils.convertStarlarkArgumentsToJavaMethodArguments(
              thread, ast, methodDescriptor, clazz, args, kwargs);
      return methodDescriptor.call(objValue, javaArguments, ast.getLocation(), thread);
    }
  }

  public MethodDescriptor getMethodDescriptor(StarlarkSemantics semantics) {
    return CallUtils.getMethod(semantics, obj.getClass(), methodName);
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<built-in function " + methodName + ">");
  }
}
