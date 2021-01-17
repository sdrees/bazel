// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common;

import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;

/**
 * A {@link RemoteFileArtifactValue} with additional data only available when using Remote Execution
 * API (e.g. {@code isExecutable}).
 */
public class RemoteActionFileArtifactValue extends RemoteFileArtifactValue {

  private final boolean isExecutable;

  public RemoteActionFileArtifactValue(
      byte[] digest, long size, int locationIndex, String actionId, boolean isExecutable) {
    super(digest, size, locationIndex, actionId);
    this.isExecutable = isExecutable;
  }

  public boolean isExecutable() {
    return isExecutable;
  }
}
