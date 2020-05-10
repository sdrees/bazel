// Copyright 2006 The Bazel Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.Sets;
import com.google.devtools.build.lib.syntax.util.EvaluationTestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of StarlarkThread. */
@RunWith(JUnit4.class)
public final class StarlarkThreadTest extends EvaluationTestCase {

  // Test the API directly
  @Test
  public void testLookupAndUpdate() throws Exception {
    assertThat(lookup("foo")).isNull();
    update("foo", "bar");
    assertThat(lookup("foo")).isEqualTo("bar");
  }

  @Test
  public void testDoubleUpdateSucceeds() throws Exception {
    assertThat(lookup("VERSION")).isNull();
    update("VERSION", 42);
    assertThat(lookup("VERSION")).isEqualTo(42);
    update("VERSION", 43);
    assertThat(lookup("VERSION")).isEqualTo(43);
  }

  // Test assign through interpreter, lookup through API:
  @Test
  public void testAssign() throws Exception {
    assertThat(lookup("foo")).isNull();
    exec("foo = 'bar'");
    assertThat(lookup("foo")).isEqualTo("bar");
  }

  // Test update through API, reference through interpreter:
  @Test
  public void testReference() throws Exception {
    setFailFast(false);
    SyntaxError.Exception e = assertThrows(SyntaxError.Exception.class, () -> eval("foo"));
    assertThat(e).hasMessageThat().isEqualTo("name 'foo' is not defined");
    update("foo", "bar");
    assertThat(eval("foo")).isEqualTo("bar");
  }

  // Test assign and reference through interpreter:
  @Test
  public void testAssignAndReference() throws Exception {
    SyntaxError.Exception e = assertThrows(SyntaxError.Exception.class, () -> eval("foo"));
    assertThat(e).hasMessageThat().isEqualTo("name 'foo' is not defined");
    exec("foo = 'bar'");
    assertThat(eval("foo")).isEqualTo("bar");
  }

  @Test
  public void testBuilderRequiresSemantics() throws Exception {
    try (Mutability mut = Mutability.create("test")) {
      IllegalArgumentException expected =
          assertThrows(IllegalArgumentException.class, () -> StarlarkThread.builder(mut).build());
      assertThat(expected)
          .hasMessageThat()
          .contains("must call either setSemantics or useDefaultSemantics");
    }
  }

  @Test
  public void testGetVariableNames() throws Exception {
    StarlarkThread thread;
    try (Mutability mut = Mutability.create("outer")) {
      thread =
          StarlarkThread.builder(mut)
              .useDefaultSemantics()
              .setGlobals(Module.createForBuiltins(Starlark.UNIVERSE))
              .build();
      thread.getGlobals().put("foo", "bar");
      thread.getGlobals().put("wiz", 3);
    }

    assertThat(thread.getVariableNames())
        .isEqualTo(
            Sets.newHashSet(
                "foo",
                "wiz",
                "False",
                "None",
                "True",
                "all",
                "any",
                "bool",
                "dict",
                "dir",
                "enumerate",
                "fail",
                "getattr",
                "hasattr",
                "hash",
                "int",
                "len",
                "list",
                "max",
                "min",
                "print",
                "range",
                "repr",
                "reversed",
                "sorted",
                "str",
                "tuple",
                "type",
                "zip"));
  }

  @Test
  public void testBindToNullThrowsException() throws Exception {
    NullPointerException e =
        assertThrows(NullPointerException.class, () -> update("some_name", null));
    assertThat(e).hasMessageThat().isEqualTo("Module.put(some_name, null)");
  }

  @Test
  public void testFrozen() throws Exception {
    Module module;
    try (Mutability mutability = Mutability.create("testFrozen")) {
      // TODO(adonovan): make it simpler to construct a module without a thread,
      // and move this test to ModuleTest.
      StarlarkThread thread =
          StarlarkThread.builder(mutability)
              .useDefaultSemantics()
              .setGlobals(Module.createForBuiltins(Starlark.UNIVERSE))
              .build();
      module = thread.getGlobals();
      module.put("x", 1);
      assertThat(module.lookup("x")).isEqualTo(1);
      module.put("y", 2);
      assertThat(module.lookup("y")).isEqualTo(2);
      assertThat(module.lookup("x")).isEqualTo(1);
      module.put("x", 3);
      assertThat(module.lookup("x")).isEqualTo(3);
    }

    // This update to an existing variable should fail because the environment was frozen.
    EvalException ex = assertThrows(EvalException.class, () -> module.put("x", 4));
    assertThat(ex).hasMessageThat().isEqualTo("trying to mutate a frozen module");

    // This update to a new variable should also fail because the environment was frozen.
    ex = assertThrows(EvalException.class, () -> module.put("newvar", 5));
    assertThat(ex).hasMessageThat().isEqualTo("trying to mutate a frozen module");
  }

  @Test
  public void testBuiltinsCanBeShadowed() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    EvalUtils.exec(
        ParserInput.fromLines("True = 123"), FileOptions.DEFAULT, thread.getGlobals(), thread);
    assertThat(thread.getGlobals().lookup("True")).isEqualTo(123);
  }

  @Test
  public void testVariableIsReferencedBeforeAssignment() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    Module module = thread.getGlobals();
    module.put("global_var", 666);
    try {
      EvalUtils.exec(
          ParserInput.fromLines(
              "def foo(x): x += global_var; global_var = 36; return x", //
              "foo(1)"),
          FileOptions.DEFAULT,
          module,
          thread);
      throw new AssertionError("failed to fail");
    } catch (EvalExceptionWithStackTrace e) {
      assertThat(e)
          .hasMessageThat()
          .contains("local variable 'global_var' is referenced before assignment.");
    }
  }
}
