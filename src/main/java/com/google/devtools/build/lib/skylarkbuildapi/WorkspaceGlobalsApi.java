// Copyright 2019 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.skylarkbuildapi;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkGlobalLibrary;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.StarlarkThread;

/** A collection of global skylark build API functions that apply to WORKSPACE files. */
@SkylarkGlobalLibrary
public interface WorkspaceGlobalsApi {

  @SkylarkCallable(
      name = "workspace",
      doc =
          "<p>This command can only be used in a <code>WORKSPACE</code> file and must come "
              + "before all other commands in the <code>WORKSPACE</code> file. "
              + "Each <code>WORKSPACE</code> file should have a <code>workspace</code> command.</p>"
              + "<p>Sets the name for this workspace. "
              + "Workspace names should be a Java-package-style "
              + "description of the project, using underscores as separators, e.g., "
              + "github.com/bazelbuild/bazel should use com_github_bazelbuild_bazel. "
              + "<p>This name is used for the directory that the repository's runfiles are stored "
              + "in. For example, if there is a runfile <code>foo/bar</code> in the local "
              + "repository and the WORKSPACE file contains "
              + "<code>workspace(name = 'baz')</code>, then the runfile will be available under "
              + "<code>mytarget.runfiles/baz/foo/bar</code>.  If no workspace name is "
              + "specified, then the runfile will be symlinked to "
              + "<code>bar.runfiles/foo/bar</code>.</p> "
              + "<p><a href=\"../../external.html\">Remote repository</a> rule names must be"
              + "  valid workspace names. For example, you could have"
              + "  <code>maven_jar(name = 'foo')</code>, but not"
              + "  <code>maven_jar(name = 'foo.bar')</code>, as Bazel would attempt to write a"
              + "  WORKSPACE file for the <code>maven_jar</code> containing"
              + "  <code>workspace(name = 'foo.bar')</code>."
              + "</p>",
      allowReturnNones = true,
      parameters = {
        @Param(
            name = "name",
            type = String.class,
            doc =
                "the name of the workspace. Names must start with a letter and can only contain "
                    + "letters, numbers, and underscores.",
            named = true,
            positional = false),
        @Param(
            name = "managed_directories",
            type = SkylarkDict.class,
            generic1 = String.class,
            noneable = true,
            named = true,
            positional = false,
            defaultValue = "{}",
            doc =
                "Dict (strings to list of strings) for defining the mappings between external"
                    + " repositories and relative (to the workspace root) paths to directories"
                    + " they incrementally update."
                    + "\nManaged directories must be excluded from the source tree by listing"
                    + " them (or their parent directories) in the .bazelignore file."),
      },
      useAst = true,
      useStarlarkThread = true)
  NoneType workspace(
      String name,
      SkylarkDict<?, ?> managedDirectories, // <String, SkylarkList<String>>
      FuncallExpression ast,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "register_execution_platforms",
      doc = "Registers a platform so that it is available to execute actions.",
      allowReturnNones = true,
      extraPositionals =
          @Param(
              name = "platform_labels",
              type = SkylarkList.class,
              generic1 = String.class,
              doc = "The labels of the platforms to register."),
      useLocation = true,
      useStarlarkThread = true)
  NoneType registerExecutionPlatforms(
      SkylarkList<?> platformLabels, Location location, StarlarkThread thread)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "register_toolchains",
      doc =
          "Registers a toolchain created with the toolchain() rule so that it is available for "
              + "toolchain resolution.",
      allowReturnNones = true,
      extraPositionals =
          @Param(
              name = "toolchain_labels",
              type = SkylarkList.class,
              generic1 = String.class,
              doc = "The labels of the toolchains to register."),
      useLocation = true,
      useStarlarkThread = true)
  NoneType registerToolchains(
      SkylarkList<?> toolchainLabels, Location location, StarlarkThread thread)
      throws EvalException, InterruptedException;

  @SkylarkCallable(
      name = "bind",
      doc =
          "<p>Warning: use of <code>bind()</code> is not recommended. See <a"
              + " href=\"https://github.com/bazelbuild/bazel/issues/1952\">Consider removing"
              + " bind</a> for a long discussion if its issues and alternatives.</p> <p>Gives a"
              + " target an alias in the <code>//external</code> package.</p>",
      allowReturnNones = true,
      parameters = {
        @Param(
            name = "name",
            type = String.class,
            named = true,
            positional = false,
            doc = "The label under '//external' to serve as the alias name"),
        @Param(
            name = "actual",
            type = String.class,
            named = true,
            positional = false,
            noneable = true,
            defaultValue = "None",
            doc = "The real label to be aliased")
      },
      useAst = true,
      useStarlarkThread = true)
  NoneType bind(String name, Object actual, FuncallExpression ast, StarlarkThread thread)
      throws EvalException, InterruptedException;
}
