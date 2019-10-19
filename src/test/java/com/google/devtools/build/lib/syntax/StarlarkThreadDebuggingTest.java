// Copyright 2018 The Bazel Authors. All Rights Reserved.
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
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.Location.LineAndColumn;
import com.google.devtools.build.lib.syntax.StarlarkThread.LexicalFrame;
import com.google.devtools.build.lib.syntax.StarlarkThread.ReadyToPause;
import com.google.devtools.build.lib.syntax.StarlarkThread.Stepping;
import com.google.devtools.build.lib.vfs.PathFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests of {@link StarlarkThread}s implementation of {@link StarlarkThread}. */
@RunWith(JUnit4.class)
public class StarlarkThreadDebuggingTest {

  private static StarlarkThread newStarlarkThread() {
    Mutability mutability = Mutability.create("test");
    return StarlarkThread.builder(mutability).useDefaultSemantics().build();
  }

  /** Enter a dummy function scope with the given name, and the current environment's globals. */
  private static void enterFunctionScope(
      StarlarkThread thread, String functionName, Location location) {
    FuncallExpression ast = new FuncallExpression(Identifier.of("test"), ImmutableList.of());
    ast.setLocation(location);
    thread.enterScope(
        new BaseFunction(functionName) {},
        LexicalFrame.create(thread.mutability()),
        ast,
        thread.getGlobals());
  }

  @Test
  public void testListFramesFromGlobalFrame() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    thread.update("a", 1);
    thread.update("b", 2);
    thread.update("c", 3);

    ImmutableList<DebugFrame> frames = thread.listFrames(Location.BUILTIN);

    assertThat(frames).hasSize(1);
    assertThat(frames.get(0))
        .isEqualTo(
            DebugFrame.builder()
                .setFunctionName("<top level>")
                .setLocation(Location.BUILTIN)
                .setGlobalBindings(ImmutableMap.of("a", 1, "b", 2, "c", 3))
                .build());
  }

  @Test
  public void testListFramesFromChildFrame() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    thread.update("a", 1);
    thread.update("b", 2);
    thread.update("c", 3);
    Location funcallLocation =
        Location.fromPathAndStartColumn(
            PathFragment.create("foo/bar"), 0, 0, new LineAndColumn(12, 0));
    enterFunctionScope(thread, "function", funcallLocation);
    thread.update("a", 4); // shadow parent frame var
    thread.update("y", 5);
    thread.update("z", 6);

    ImmutableList<DebugFrame> frames = thread.listFrames(Location.BUILTIN);

    assertThat(frames).hasSize(2);
    assertThat(frames.get(0))
        .isEqualTo(
            DebugFrame.builder()
                .setFunctionName("function")
                .setLocation(Location.BUILTIN)
                .setLexicalFrameBindings(ImmutableMap.of("a", 4, "y", 5, "z", 6))
                .setGlobalBindings(ImmutableMap.of("a", 1, "b", 2, "c", 3))
                .build());
    assertThat(frames.get(1))
        .isEqualTo(
            DebugFrame.builder()
                .setFunctionName("<top level>")
                .setLocation(funcallLocation)
                .setGlobalBindings(ImmutableMap.of("a", 1, "b", 2, "c", 3))
                .build());
  }

  @Test
  public void testStepIntoFunction() {
    StarlarkThread thread = newStarlarkThread();

    ReadyToPause predicate = thread.stepControl(Stepping.INTO);
    enterFunctionScope(thread, "function", Location.BUILTIN);

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepIntoFallsBackToStepOver() {
    // test that when stepping into, we'll fall back to stopping at the next statement in the
    // current frame
    StarlarkThread thread = newStarlarkThread();

    ReadyToPause predicate = thread.stepControl(Stepping.INTO);

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepIntoFallsBackToStepOut() {
    // test that when stepping into, we'll fall back to stopping when exiting the current frame
    StarlarkThread thread = newStarlarkThread();
    enterFunctionScope(thread, "function", Location.BUILTIN);

    ReadyToPause predicate = thread.stepControl(Stepping.INTO);
    thread.exitScope();

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOverFunction() {
    StarlarkThread thread = newStarlarkThread();

    ReadyToPause predicate = thread.stepControl(Stepping.OVER);
    enterFunctionScope(thread, "function", Location.BUILTIN);

    assertThat(predicate.test(thread)).isFalse();
    thread.exitScope();
    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOverFallsBackToStepOut() {
    // test that when stepping over, we'll fall back to stopping when exiting the current frame
    StarlarkThread thread = newStarlarkThread();
    enterFunctionScope(thread, "function", Location.BUILTIN);

    ReadyToPause predicate = thread.stepControl(Stepping.OVER);
    thread.exitScope();

    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOutOfInnerFrame() {
    StarlarkThread thread = newStarlarkThread();
    enterFunctionScope(thread, "function", Location.BUILTIN);

    ReadyToPause predicate = thread.stepControl(Stepping.OUT);

    assertThat(predicate.test(thread)).isFalse();
    thread.exitScope();
    assertThat(predicate.test(thread)).isTrue();
  }

  @Test
  public void testStepOutOfOutermostFrame() {
    StarlarkThread thread = newStarlarkThread();

    assertThat(thread.stepControl(Stepping.OUT)).isNull();
  }

  @Test
  public void testStepControlWithNoSteppingReturnsNull() {
    StarlarkThread thread = newStarlarkThread();

    assertThat(thread.stepControl(Stepping.NONE)).isNull();
  }

  @Test
  public void testEvaluateVariableInScope() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    thread.update("a", 1);

    Object a = thread.debugEval(Expression.parse(ParserInput.fromLines("a")));
    assertThat(a).isEqualTo(1);
  }

  @Test
  public void testEvaluateVariableNotInScopeFails() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    thread.update("a", 1);

    EvalException e =
        assertThrows(
            EvalException.class,
            () -> thread.debugEval(Expression.parse(ParserInput.fromLines("b"))));
    assertThat(e).hasMessageThat().isEqualTo("name 'b' is not defined");
  }

  @Test
  public void testEvaluateExpressionOnVariableInScope() throws Exception {
    StarlarkThread thread = newStarlarkThread();
    thread.update("a", "string");

    assertThat(thread.debugEval(Expression.parse(ParserInput.fromLines("a.startswith('str')"))))
        .isEqualTo(true);
    EvalUtils.exec(
        EvalUtils.parseAndValidateSkylark(ParserInput.fromLines("a = 1"), thread), thread);
    assertThat(thread.debugEval(Expression.parse(ParserInput.fromLines("a")))).isEqualTo(1);
  }
}
