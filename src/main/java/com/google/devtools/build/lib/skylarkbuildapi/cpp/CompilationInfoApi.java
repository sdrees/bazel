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

import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.syntax.StarlarkValue;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkDocumentationCategory;
import net.starlark.java.annot.StarlarkMethod;

/** Interface for a helper class containing CC compilation providers. */
@StarlarkBuiltin(
    name = "compilation_info",
    documented = false,
    category = StarlarkDocumentationCategory.BUILTIN,
    doc = "Helper class containing CC compilation providers.")
public interface CompilationInfoApi<FileT extends FileApi> extends StarlarkValue {
  @StarlarkMethod(name = "cc_compilation_outputs", structField = true, documented = false)
  CcCompilationOutputsApi<?> getCcCompilationOutputs();

  @StarlarkMethod(name = "compilation_context", structField = true, documented = false)
  CcCompilationContextApi<FileT> getCcCompilationContext();
}
