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

package com.google.devtools.build.lib.bazel.rules.ninja.actions;

import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.bazel.rules.ninja.file.GenericParsingException;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaRule;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaRuleVariable;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaScope;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaTarget;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaVariableValue;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Helper class for creating {@link NinjaAction} for each {@link NinjaTarget}, and linking the file
 * under the output_root into corresponding directory in execroot. See output_root property in the
 * {@link NinjaGraphRule}.
 */
public class NinjaActionsHelper {
  private final RuleContext ruleContext;
  private final ImmutableSortedMap<PathFragment, NinjaTarget> allUsualTargets;
  private final ImmutableSortedMap<PathFragment, PhonyTarget> phonyTargets;

  private final NinjaGraphArtifactsHelper artifactsHelper;

  private final PathFragment shellExecutable;
  private final ImmutableSortedMap<String, String> executionInfo;
  private final PhonyTargetArtifacts phonyTargetArtifacts;
  private final List<PathFragment> pathsToBuild;

  /**
   * Constructor
   *
   * @param ruleContext parent NinjaGraphRule rule context
   * @param artifactsHelper helper object to create artifacts
   * @param allUsualTargets mapping of outputs to all non-phony Ninja targets from Ninja file
   * @param phonyTargets mapping of names to all phony Ninja actions from Ninja file
   * @param phonyTargetArtifacts helper class for computing transitively included artifacts of phony
   *     targets
   * @param pathsToBuild paths requested by the user to be build (in output_groups attribute)
   */
  NinjaActionsHelper(
      RuleContext ruleContext,
      NinjaGraphArtifactsHelper artifactsHelper,
      ImmutableSortedMap<PathFragment, NinjaTarget> allUsualTargets,
      ImmutableSortedMap<PathFragment, PhonyTarget> phonyTargets,
      PhonyTargetArtifacts phonyTargetArtifacts,
      List<PathFragment> pathsToBuild) {
    this.ruleContext = ruleContext;
    this.artifactsHelper = artifactsHelper;
    this.allUsualTargets = allUsualTargets;
    this.phonyTargets = phonyTargets;
    this.shellExecutable = ShToolchain.getPathOrError(ruleContext);
    this.executionInfo = createExecutionInfo(ruleContext);
    this.phonyTargetArtifacts = phonyTargetArtifacts;
    this.pathsToBuild = pathsToBuild;
  }

  void createNinjaActions() throws GenericParsingException {
    // Traverse the action graph starting from the targets, specified by the user.
    // Only create the required actions.
    Set<PathFragment> visitedPaths = Sets.newHashSet();
    Set<NinjaTarget> visitedTargets = Sets.newHashSet();
    visitedPaths.addAll(pathsToBuild);
    ArrayDeque<PathFragment> queue = new ArrayDeque<>(pathsToBuild);
    Consumer<Collection<PathFragment>> enqueuer =
        paths -> {
          for (PathFragment input : paths) {
            if (visitedPaths.add(input)) {
              queue.add(input);
            }
          }
        };
    while (!queue.isEmpty()) {
      PathFragment fragment = queue.remove();
      NinjaTarget target = allUsualTargets.get(fragment);
      if (target != null) {
        if (visitedTargets.add(target)) {
          createNinjaAction(target);
        }
        enqueuer.accept(target.getAllInputs());
      } else {
        PhonyTarget phonyTarget = phonyTargets.get(fragment);
        // Phony target can be null, if the path in neither usual or phony target,
        // but the source file.
        if (phonyTarget != null) {
          phonyTarget.visitUsualInputs(phonyTargets, enqueuer::accept);
        }
      }
    }
  }

  private void createNinjaAction(NinjaTarget target) throws GenericParsingException {
    NinjaRule rule = getNinjaRule(target);

    NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    ImmutableList.Builder<Artifact> outputsBuilder = ImmutableList.builder();
    boolean isAlwaysDirty = fillArtifacts(target, inputsBuilder, outputsBuilder);

    NinjaScope targetScope = createTargetScope(target);
    int targetOffset = target.getOffset();
    maybeCreateRspFile(rule, targetScope, targetOffset, inputsBuilder);

    String command = resolveRuleCommand(targetScope, targetOffset, rule);
    if (!artifactsHelper.getWorkingDirectory().isEmpty()) {
      command = String.format("cd %s && ", artifactsHelper.getWorkingDirectory()) + command;
    }
    CommandLines commandLines =
        CommandLines.of(ImmutableList.of(shellExecutable.getPathString(), "-c", command));
    ruleContext.registerAction(
        new NinjaAction(
            ruleContext.getActionOwner(),
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            inputsBuilder.build(),
            outputsBuilder.build(),
            commandLines,
            Preconditions.checkNotNull(ruleContext.getConfiguration()).getActionEnvironment(),
            executionInfo,
            EmptyRunfilesSupplier.INSTANCE,
            isAlwaysDirty));
  }

  /** Returns true is the action shpould be marked as always dirty. */
  private boolean fillArtifacts(
      NinjaTarget target,
      NestedSetBuilder<Artifact> inputsBuilder,
      ImmutableList.Builder<Artifact> outputsBuilder)
      throws GenericParsingException {
    boolean isAlwaysDirty = false;
    for (PathFragment input : target.getAllInputs()) {
      PhonyTarget phonyTarget = this.phonyTargets.get(input);
      if (phonyTarget != null) {
        inputsBuilder.addTransitive(phonyTargetArtifacts.getPhonyTargetArtifacts(input));
        isAlwaysDirty |= phonyTarget.isAlwaysDirty();
      } else {
        Artifact artifact = artifactsHelper.getInputArtifact(input);
        inputsBuilder.add(artifact);
      }
    }

    for (PathFragment output : target.getAllOutputs()) {
      outputsBuilder.add(artifactsHelper.createOutputArtifact(output));
    }
    return isAlwaysDirty;
  }

  private void maybeCreateRspFile(
      NinjaRule rule,
      NinjaScope targetScope,
      int targetOffset,
      NestedSetBuilder<Artifact> inputsBuilder)
      throws GenericParsingException {
    NinjaVariableValue value = rule.getVariables().get(NinjaRuleVariable.RSPFILE);
    NinjaVariableValue content = rule.getVariables().get(NinjaRuleVariable.RSPFILE_CONTENT);
    if (value == null && content == null) {
      return;
    }
    if (value == null || content == null) {
      ruleContext.ruleError(
          String.format(
              "Both rspfile and rspfile_content should be defined for rule '%s'.", rule.getName()));
      return;
    }
    String fileName = targetScope.getExpandedValue(targetOffset, value);
    String contentString = targetScope.getExpandedValue(targetOffset, content);
    if (!fileName.trim().isEmpty()) {
      DerivedArtifact rspArtifact =
          artifactsHelper.createOutputArtifact(PathFragment.create(fileName));
      FileWriteAction fileWriteAction =
          FileWriteAction.create(ruleContext, rspArtifact, contentString, false);
      ruleContext.registerAction(fileWriteAction);
      inputsBuilder.add(rspArtifact);
    }
  }

  /**
   * Here we resolve the rule's variables with the following assumptions: Rule variables can refer
   * to target's variables (and file variables). Interdependence between rule variables can happen
   * only for 'command' variable, for now we ignore other possible dependencies between rule
   * variables (seems the only other variable which can meaningfully depend on sibling variables is
   * description, and currently we are ignoring it).
   *
   * <p>Also, for resolving rule's variables we are using scope+offset of target, according to
   * specification (https://ninja-build.org/manual.html#_variable_expansion).
   *
   * <p>See {@link NinjaRuleVariable} for the list.
   */
  private static String resolveRuleCommand(
      NinjaScope targetScope, int targetOffset, NinjaRule rule) {
    ImmutableSortedMap.Builder<String, List<Pair<Integer, String>>> builder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<NinjaRuleVariable, NinjaVariableValue> entry : rule.getVariables().entrySet()) {
      NinjaRuleVariable type = entry.getKey();
      // Skip command for now, and description because we do not use it.
      if (NinjaRuleVariable.COMMAND.equals(type) || NinjaRuleVariable.DESCRIPTION.equals(type)) {
        continue;
      }
      String expandedValue = targetScope.getExpandedValue(targetOffset, entry.getValue());
      builder.put(Ascii.toLowerCase(type.name()), ImmutableList.of(Pair.of(0, expandedValue)));
    }
    NinjaScope expandedRuleScope = targetScope.createScopeFromExpandedValues(builder.build());
    return expandedRuleScope.getExpandedValue(
        targetOffset, rule.getVariables().get(NinjaRuleVariable.COMMAND));
  }

  private NinjaScope createTargetScope(NinjaTarget target) {
    ImmutableSortedMap.Builder<String, List<Pair<Integer, String>>> builder =
        ImmutableSortedMap.naturalOrder();
    target
        .getVariables()
        .forEach((key, value) -> builder.put(key, ImmutableList.of(Pair.of(0, value))));
    String inNewline =
        target.getUsualInputs().stream()
            .map(this::getInputPathWithDepsMappingReplacement)
            .collect(Collectors.joining("\n"));
    String out =
        target.getOutputs().stream()
            .map(PathFragment::getPathString)
            .collect(Collectors.joining(" "));
    builder.put("in", ImmutableList.of(Pair.of(0, inNewline.replace('\n', ' '))));
    builder.put("in_newline", ImmutableList.of(Pair.of(0, inNewline)));
    builder.put("out", ImmutableList.of(Pair.of(0, out)));

    return target.getScope().createScopeFromExpandedValues(builder.build());
  }

  private static NinjaRule getNinjaRule(NinjaTarget target) throws GenericParsingException {
    String ruleName = target.getRuleName();
    Preconditions.checkState(!"phony".equals(ruleName));
    NinjaRule rule = target.getScope().findRule(target.getOffset(), ruleName);
    if (rule == null) {
      throw new GenericParsingException(String.format("Unknown Ninja rule: '%s'", ruleName));
    }
    return rule;
  }

  private static ImmutableSortedMap<String, String> createExecutionInfo(RuleContext ruleContext) {
    ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();
    builder.putAll(TargetUtils.getExecutionInfo(ruleContext.getRule()));
    builder.put("local", "");
    ImmutableSortedMap<String, String> map = builder.build();
    Preconditions.checkNotNull(ruleContext.getConfiguration())
        .modifyExecutionInfo(map, "NinjaRule");
    return map;
  }

  private String getInputPathWithDepsMappingReplacement(PathFragment fragment) {
    Artifact bazelArtifact = artifactsHelper.getDepsMappingArtifact(fragment);
    if (bazelArtifact != null) {
      return bazelArtifact.getPath().getPathString();
    }
    return fragment.getPathString();
  }
}
