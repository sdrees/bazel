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
// limitations under the License

package com.google.devtools.build.lib.rules.config;

import static com.google.devtools.build.lib.analysis.skylark.FunctionTransitionUtil.COMMAND_LINE_OPTION_PREFIX;

import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.skylark.StarlarkTransition.Settings;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.config.ConfigGlobalLibraryApi;
import com.google.devtools.build.lib.skylarkbuildapi.config.ConfigurationTransitionApi;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link ConfigGlobalLibraryApi}.
 *
 * <p>A collection of top-level Starlark functions pertaining to configuration.
 */
public class ConfigGlobalLibrary implements ConfigGlobalLibraryApi {

  @Override
  public ConfigurationTransitionApi transition(
      BaseFunction implementation,
      SkylarkList<?> inputs, // <String> expected
      SkylarkList<?> outputs, // <String> expected
      Location location,
      StarlarkThread thread)
      throws EvalException {
    StarlarkSemantics semantics = thread.getSemantics();
    List<String> inputsList = inputs.getContents(String.class, "inputs");
    List<String> outputsList = outputs.getContents(String.class, "outputs");
    validateBuildSettingKeys(
        inputsList, Settings.INPUTS, location, semantics.experimentalStarlarkConfigTransitions());
    validateBuildSettingKeys(
        outputsList, Settings.OUTPUTS, location, semantics.experimentalStarlarkConfigTransitions());
    return StarlarkDefinedConfigTransition.newRegularTransition(
        implementation, inputsList, outputsList, semantics, thread);
  }

  @Override
  public ConfigurationTransitionApi analysisTestTransition(
      SkylarkDict<?, ?> changedSettings, // <String, String> expected
      Location location,
      StarlarkSemantics semantics)
      throws EvalException {
    Map<String, Object> changedSettingsMap =
        changedSettings.getContents(String.class, Object.class, "changed_settings dict");
    validateBuildSettingKeys(changedSettingsMap.keySet(), Settings.OUTPUTS, location, true);
    return StarlarkDefinedConfigTransition.newAnalysisTestTransition(changedSettingsMap, location);
  }

  private void validateBuildSettingKeys(
      Iterable<String> optionKeys,
      Settings keyErrorDescriptor,
      Location location,
      boolean starlarkTransitionsEnabled)
      throws EvalException {

    HashSet<String> processedOptions = Sets.newHashSet();
    String singularErrorDescriptor = keyErrorDescriptor == Settings.INPUTS ? "input" : "output";

    for (String optionKey : optionKeys) {
      if (!optionKey.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        if (!starlarkTransitionsEnabled) {
          throw new EvalException(
              location,
              "transitions on Starlark-defined build settings is experimental and "
                  + "disabled by default. This API is in development and subject to change at any"
                  + "time. Use --experimental_starlark_config_transitions to use this experimental "
                  + "API.");
        }
        try {
          Label.parseAbsoluteUnchecked(optionKey);
        } catch (IllegalArgumentException e) {
          throw new EvalException(
              location,
              String.format(
                  "invalid transition %s '%s'. If this is intended as a native option, "
                      + "it must begin with //command_line_option:",
                  singularErrorDescriptor, optionKey),
              e);
        }
      } else {
        String optionName = optionKey.substring(COMMAND_LINE_OPTION_PREFIX.length());
        if (optionName.startsWith("experimental_") || optionName.startsWith("incompatible_")) {
          throw new EvalException(
              location,
              String.format(
                  "Invalid transition %s '%s'. Cannot transition on --experimental_* or "
                      + "--incompatible_* options",
                  singularErrorDescriptor, optionKey));
        }
      }
      if (!processedOptions.add(optionKey)) {
        throw new EvalException(
            location,
            String.format("duplicate transition %s '%s'", singularErrorDescriptor, optionKey));
      }
    }
  }
}
