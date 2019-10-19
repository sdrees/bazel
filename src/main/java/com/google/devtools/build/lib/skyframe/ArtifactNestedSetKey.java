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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

/** SkyKey for {@code NestedSet<Artifact>}. */
public class ArtifactNestedSetKey implements SkyKey {
  private final Object rawChildren;

  @Override
  public SkyFunctionName functionName() {
    return SkyFunctions.ARTIFACT_NESTED_SET;
  }

  /**
   * We use Object instead of NestedSet to store children as a measure to save memory, and to be
   * consistent with the implementation of NestedSet.
   *
   * @param rawChildren the underlying members of the nested set.
   */
  ArtifactNestedSetKey(Object rawChildren) {
    Preconditions.checkState(rawChildren instanceof Object[] || rawChildren instanceof Artifact);
    this.rawChildren = rawChildren;
  }

  @Override
  public int hashCode() {
    if (rawChildren instanceof Object[]) {
      // Warning: Ignoring Order
      return Arrays.hashCode((Object[]) rawChildren);
    }
    return rawChildren.hashCode();
  }

  @Override
  public boolean equals(Object that) {
    if (this == that) {
      return true;
    }

    if (!(that instanceof ArtifactNestedSetKey)) {
      return false;
    }

    Object theirRawChildren = ((ArtifactNestedSetKey) that).rawChildren;
    if (rawChildren == theirRawChildren) {
      return true;
    }

    if (rawChildren instanceof Object[] && theirRawChildren instanceof Object[]) {
      return Arrays.equals((Object[]) rawChildren, (Object[]) theirRawChildren);
    }
    return rawChildren.equals(theirRawChildren);
  }

  @Override
  public String toString() {
    return rawChildren.toString();
  }

  /**
   * Return the set of transitive members.
   *
   * <p>This refers to the transitive members after any inlining that might have happened at
   * construction of the nested set.
   *
   * <p>TODO(b/142232950) Investigate the potential additional load on GC.
   */
  Iterable<SkyKey> transitiveKeys() {
    if (!(rawChildren instanceof Object[])) {
      return ImmutableSet.of();
    }
    return Arrays.stream((Object[]) rawChildren)
        .filter(c -> c instanceof Object[])
        .map(ArtifactNestedSetKey::new)
        .collect(Collectors.toList());
  }

  /**
   * Return the set of direct members.
   *
   * <p>This refers to the direct members after any inlining that might have happened at
   * construction of the nested set.
   *
   * <p>TODO(b/142232950) Investigate the potential additional load on GC.
   */
  Iterable<SkyKey> directKeys() {
    if (!(rawChildren instanceof Object[])) {
      return Collections.singletonList(Artifact.key((Artifact) rawChildren));
    }
    return Arrays.stream((Object[]) rawChildren)
        .filter(c -> !(c instanceof Object[]))
        .map(c -> Artifact.key((Artifact) c))
        .collect(Collectors.toList());
  }
}
