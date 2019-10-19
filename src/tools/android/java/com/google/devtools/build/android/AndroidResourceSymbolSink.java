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
package com.google.devtools.build.android;

import com.android.resources.ResourceType;
import com.google.common.base.Optional;
import java.util.Map;

/** Defines a sink for collecting data about resource symbols. */
public interface AndroidResourceSymbolSink {

  void acceptSimpleResource(DependencyInfo dependencyInfo, ResourceType type, String name);

  // "inlineable" below affects how resource IDs are assigned by
  // PlaceholderIdFieldInitializerBuilder to attempt to match the final IDs assigned by aapt1.  This
  // shouldn't matter, but legacy tests with ODR violations might be relying on this.
  void acceptStyleableResource(
      DependencyInfo dependencyInfo,
      FullyQualifiedName key,
      Map<FullyQualifiedName, /*inlineable=*/ Boolean> attrs);

  /**
   * Marks a resource as public.
   *
   * <p>This is orthogonal to the two methods above, and omits the 'DependencyInfo' parameter since
   * a 'public' declaration must also have a matching definition (which triggers a call to one of
   * the above methods).
   */
  void acceptPublicResource(ResourceType type, String name, Optional<Integer> value);
}
