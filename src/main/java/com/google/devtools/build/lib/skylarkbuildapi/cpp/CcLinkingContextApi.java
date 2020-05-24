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

package com.google.devtools.build.lib.skylarkbuildapi.cpp;

import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.syntax.Sequence;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkSemantics.FlagIdentifier;
import com.google.devtools.build.lib.syntax.StarlarkValue;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkDocumentationCategory;
import net.starlark.java.annot.StarlarkMethod;

/** Wrapper for every C++ linking provider. */
@StarlarkBuiltin(
    name = "LinkingContext",
    category = StarlarkDocumentationCategory.BUILTIN,
    doc =
        "Immutable store of information needed for C++ linking that is aggregated across "
            + "dependencies.")
public interface CcLinkingContextApi<FileT extends FileApi> extends StarlarkValue {
  @StarlarkMethod(
      name = "user_link_flags",
      doc = "Returns the list of user link flags passed as strings.",
      disableWithFlag = FlagIdentifier.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API,
      structField = true)
  Sequence<String> getStarlarkUserLinkFlags();

  @StarlarkMethod(
      name = "libraries_to_link",
      doc =
          "Returns the depset of <code>LibraryToLink</code>. May return a list but this is"
              + "deprecated. See #8118.",
      disableWithFlag = FlagIdentifier.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API,
      structField = true,
      useStarlarkSemantics = true)
  Object getStarlarkLibrariesToLink(StarlarkSemantics semantics);

  @StarlarkMethod(
      name = "additional_inputs",
      doc = "Returns the depset of additional inputs, e.g.: linker scripts.",
      disableWithFlag = FlagIdentifier.INCOMPATIBLE_REQUIRE_LINKER_INPUT_CC_API,
      structField = true)
  Depset getStarlarkNonCodeInputs();

  @StarlarkMethod(
      name = "linker_inputs",
      doc = "Returns the depset of linker inputs.",
      structField = true)
  Depset getStarlarkLinkerInputs();
}
