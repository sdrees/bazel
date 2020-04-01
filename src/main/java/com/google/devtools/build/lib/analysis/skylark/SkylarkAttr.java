// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.skylark;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.TransitionFactories;
import com.google.devtools.build.lib.analysis.config.transitions.SplitTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.AllowedValueSet;
import com.google.devtools.build.lib.packages.Attribute.ImmutableAttributeFactory;
import com.google.devtools.build.lib.packages.Attribute.SkylarkComputedDefaultTemplate;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.AttributeValueSource;
import com.google.devtools.build.lib.packages.BazelStarlarkContext;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.SkylarkAspect;
import com.google.devtools.build.lib.packages.SkylarkProviderIdentifier;
import com.google.devtools.build.lib.packages.StarlarkCallbackHelper;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkbuildapi.SkylarkAttrApi;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Sequence;
import com.google.devtools.build.lib.syntax.SkylarkType;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkFunction;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A helper class to provide Attr module in Skylark.
 *
 * <p>It exposes functions (for example, 'attr.string', 'attr.label_list', etc.) to Skylark users.
 * The functions are executed through reflection. As everywhere in Skylark, arguments are
 * type-checked with the signature and cannot be null.
 */
public final class SkylarkAttr implements SkylarkAttrApi {

  // Arguments

  private static boolean containsNonNoneKey(Map<String, Object> arguments, String key) {
    return arguments.containsKey(key) && arguments.get(key) != Starlark.NONE;
  }

  private static void setAllowedFileTypes(
      String attr, Object fileTypesObj, Attribute.Builder<?> builder) throws EvalException {
    if (fileTypesObj == Boolean.TRUE) {
      builder.allowedFileTypes(FileTypeSet.ANY_FILE);
    } else if (fileTypesObj == Boolean.FALSE) {
      builder.allowedFileTypes(FileTypeSet.NO_FILE);
    } else if (fileTypesObj instanceof Sequence) {
      ImmutableList<String> arg =
          ImmutableList.copyOf(
              Sequence.castSkylarkListOrNoneToList(
                  fileTypesObj, String.class, "allow_files argument"));
      builder.allowedFileTypes(FileType.of(arg));
    } else {
      throw new EvalException(null, attr + " should be a boolean or a string list");
    }
  }

  private static ImmutableAttributeFactory createAttributeFactory(
      Type<?> type, String doc, Map<String, Object> arguments, StarlarkThread thread)
      throws EvalException {
    // We use an empty name now so that we can set it later.
    // This trick makes sense only in the context of Skylark (builtin rules should not use it).
    return createAttributeFactory(type, doc, arguments, thread, "");
  }

  private static ImmutableAttributeFactory createAttributeFactory(
      Type<?> type,
      String doc,
      Map<String, Object> arguments,
      StarlarkThread thread,
      String name)
      throws EvalException {
    return createAttribute(type, doc, arguments, thread, name).buildPartial();
  }

  @SuppressWarnings("unchecked")
  private static Attribute.Builder<?> createAttribute(
      Type<?> type,
      String doc,
      Map<String, Object> arguments,
      StarlarkThread thread,
      String name)
      throws EvalException {
    Attribute.Builder<?> builder = Attribute.attr(name, type).setDoc(doc);

    Object defaultValue = arguments.get(DEFAULT_ARG);
    if (!EvalUtils.isNullOrNone(defaultValue)) {
      if (defaultValue instanceof StarlarkFunction) {
        // Computed attribute. Non label type attributes already caused a type check error.
        StarlarkCallbackHelper callback =
            new StarlarkCallbackHelper(
                (StarlarkFunction) defaultValue,
                thread.getSemantics(),
                BazelStarlarkContext.from(thread));
        // SkylarkComputedDefaultTemplate needs to know the names of all attributes that it depends
        // on. However, this method does not know anything about other attributes.
        // We solve this problem by asking the StarlarkCallbackHelper for the parameter names used
        // in the function definition, which must be the names of attributes used by the callback.
        builder.value(
            new SkylarkComputedDefaultTemplate(type, callback.getParameterNames(), callback));
      } else if (defaultValue instanceof SkylarkLateBoundDefault) {
        builder.value((SkylarkLateBoundDefault) defaultValue); // unchecked cast
      } else {
        builder.defaultValue(
            defaultValue,
            new BuildType.LabelConversionContext(
                (Label) Module.ofInnermostEnclosingStarlarkFunction(thread).getLabel(),
                BazelStarlarkContext.from(thread).getRepoMapping()),
            DEFAULT_ARG);
      }
    }

    for (String flag :
        Sequence.castSkylarkListOrNoneToList(arguments.get(FLAGS_ARG), String.class, FLAGS_ARG)) {
      builder.setPropertyFlag(flag);
    }

    if (containsNonNoneKey(arguments, MANDATORY_ARG) && (Boolean) arguments.get(MANDATORY_ARG)) {
      builder.setPropertyFlag("MANDATORY");
    }

    if (containsNonNoneKey(arguments, NON_EMPTY_ARG)
        && (Boolean) arguments.get(NON_EMPTY_ARG)) {
      if (thread.getSemantics().incompatibleDisableDeprecatedAttrParams()) {
        throw new EvalException(
            null,
            "'non_empty' is no longer supported. use allow_empty instead. You can use "
                + "--incompatible_disable_deprecated_attr_params=false to temporarily disable this "
                + "check.");
      }

      builder.setPropertyFlag("NON_EMPTY");
    }

    if (containsNonNoneKey(arguments, ALLOW_EMPTY_ARG)
        && !(Boolean) arguments.get(ALLOW_EMPTY_ARG)) {
      builder.setPropertyFlag("NON_EMPTY");
    }

    if (containsNonNoneKey(arguments, EXECUTABLE_ARG) && (Boolean) arguments.get(EXECUTABLE_ARG)) {
      builder.setPropertyFlag("EXECUTABLE");
      if (!containsNonNoneKey(arguments, CONFIGURATION_ARG)) {
        throw new EvalException(
            null,
            "cfg parameter is mandatory when executable=True is provided. Please see "
                + "https://www.bazel.build/versions/master/docs/skylark/rules.html#configurations "
                + "for more details.");
      }
    }

    if (containsNonNoneKey(arguments, SINGLE_FILE_ARG)
        && (Boolean) arguments.get(SINGLE_FILE_ARG)) {
      if (thread.getSemantics().incompatibleDisableDeprecatedAttrParams()) {
        throw new EvalException(
            null,
            "'single_file' is no longer supported. use allow_single_file instead. You can use "
                + "--incompatible_disable_deprecated_attr_params=false to temporarily disable this "
                + "check.");
      }
      if (containsNonNoneKey(arguments, ALLOW_SINGLE_FILE_ARG)) {
        throw new EvalException(
            null, "Cannot specify both single_file (deprecated) and allow_single_file");
      }

      builder.setPropertyFlag("SINGLE_ARTIFACT");
    }

    if (containsNonNoneKey(arguments, ALLOW_FILES_ARG)
        && containsNonNoneKey(arguments, ALLOW_SINGLE_FILE_ARG)) {
      throw new EvalException(null, "Cannot specify both allow_files and allow_single_file");
    }

    if (containsNonNoneKey(arguments, ALLOW_FILES_ARG)) {
      Object fileTypesObj = arguments.get(ALLOW_FILES_ARG);
      setAllowedFileTypes(ALLOW_FILES_ARG, fileTypesObj, builder);
    } else if (containsNonNoneKey(arguments, ALLOW_SINGLE_FILE_ARG)) {
      Object fileTypesObj = arguments.get(ALLOW_SINGLE_FILE_ARG);
      setAllowedFileTypes(ALLOW_SINGLE_FILE_ARG, fileTypesObj, builder);
      builder.setPropertyFlag("SINGLE_ARTIFACT");
    } else if (type.getLabelClass() == LabelClass.DEPENDENCY) {
      builder.allowedFileTypes(FileTypeSet.NO_FILE);
    }

    Object ruleClassesObj = arguments.get(ALLOW_RULES_ARG);
    if (ruleClassesObj != null && ruleClassesObj != Starlark.NONE) {
      builder.allowedRuleClasses(
          Sequence.castSkylarkListOrNoneToList(
              ruleClassesObj, String.class, "allowed rule classes for attribute definition"));
    }

    List<Object> values =
        Sequence.castSkylarkListOrNoneToList(arguments.get(VALUES_ARG), Object.class, VALUES_ARG);
    if (!Iterables.isEmpty(values)) {
      builder.allowedValues(new AllowedValueSet(values));
    }

    if (containsNonNoneKey(arguments, PROVIDERS_ARG)) {
      Object obj = arguments.get(PROVIDERS_ARG);
      SkylarkType.checkType(obj, Sequence.class, PROVIDERS_ARG);
      ImmutableList<ImmutableSet<SkylarkProviderIdentifier>> providersList =
          buildProviderPredicate((Sequence<?>) obj, PROVIDERS_ARG);

      // If there is at least one empty set, there is no restriction.
      if (providersList.stream().noneMatch(ImmutableSet::isEmpty)) {
        builder.mandatoryProvidersList(providersList);
      }
    }

    if (containsNonNoneKey(arguments, CONFIGURATION_ARG)) {
      Object trans = arguments.get(CONFIGURATION_ARG);
      boolean isSplit =
          trans instanceof SplitTransition
              || trans instanceof TransitionFactory
              || trans instanceof StarlarkDefinedConfigTransition;
      if (isSplit && defaultValue instanceof SkylarkLateBoundDefault) {
        throw new EvalException(
            null, "late-bound attributes must not have a split configuration transition");
      }
      if (trans.equals("host")) {
        builder.cfg(HostTransition.createFactory());
      } else if (trans.equals("exec")) {
        builder.cfg(ExecutionTransitionFactory.create());
      } else if (trans instanceof SplitTransition) {
        builder.cfg(TransitionFactories.of((SplitTransition) trans));
      } else if (trans instanceof TransitionFactory) {
        builder.cfg((TransitionFactory<AttributeTransitionData>) trans); // unchecked cast
      } else if (trans instanceof StarlarkDefinedConfigTransition) {
        StarlarkDefinedConfigTransition starlarkDefinedTransition =
            (StarlarkDefinedConfigTransition) trans;
        if (starlarkDefinedTransition.isForAnalysisTesting()) {
          builder.hasAnalysisTestTransition();
        } else {
          if (!thread.getSemantics().experimentalStarlarkConfigTransitions()) {
            throw new EvalException(
                null,
                "Starlark-defined transitions on rule attributes is experimental and disabled by "
                    + "default. This API is in development and subject to change at any time. Use "
                    + "--experimental_starlark_config_transitions to use this experimental API.");
          }
          builder.hasStarlarkDefinedTransition();
        }
        builder.cfg(new StarlarkAttributeTransitionProvider(starlarkDefinedTransition));
      } else if (!trans.equals("target")) {
        // TODO(b/121134880): update error message when starlark build configurations is ready.
        throw new EvalException(null, "cfg must be either 'host' or 'target'.");
      }
    }

    if (containsNonNoneKey(arguments, ASPECTS_ARG)) {
      Object obj = arguments.get(ASPECTS_ARG);
      SkylarkType.checkType(obj, Sequence.class, ASPECTS_ARG);

      List<SkylarkAspect> aspects = ((Sequence<?>) obj).getContents(SkylarkAspect.class, "aspects");
      for (SkylarkAspect aspect : aspects) {
        aspect.attachToAttribute(builder);
      }
    }

    return builder;
  }

  /**
   * Builds a list of sets of accepted providers from Skylark list {@code obj}. The list can either
   * be a list of providers (in that case the result is a list with one set) or a list of lists of
   * providers (then the result is the list of sets).
   *
   * @param argumentName used in error messages.
   */
  static ImmutableList<ImmutableSet<SkylarkProviderIdentifier>> buildProviderPredicate(
      Sequence<?> obj, String argumentName) throws EvalException {
    if (obj.isEmpty()) {
      return ImmutableList.of();
    }
    boolean isListOfProviders = true;
    for (Object o : obj) {
      if (!isProvider(o)) {
        isListOfProviders = false;
        break;
      }
    }
    if (isListOfProviders) {
      return ImmutableList.of(getSkylarkProviderIdentifiers(obj));
    } else {
      return getProvidersList(obj, argumentName);
    }
  }

  /**
   * Returns true if {@code o} is a Skylark provider (either a declared provider or
   * a legacy provider name.
   */
  static boolean isProvider(Object o) {
    return o instanceof String || o instanceof Provider;
  }

  /**
   * Converts Skylark identifiers of providers (either a string or a provider value) to their
   * internal representations.
   */
  static ImmutableSet<SkylarkProviderIdentifier> getSkylarkProviderIdentifiers(Sequence<?> list)
      throws EvalException {
    ImmutableList.Builder<SkylarkProviderIdentifier> result = ImmutableList.builder();

    for (Object obj : list) {
      if (obj instanceof String) {
        result.add(SkylarkProviderIdentifier.forLegacy((String) obj));
      } else if (obj instanceof Provider) {
        Provider constructor = (Provider) obj;
        if (!constructor.isExported()) {
          throw new EvalException(
              null, "Providers should be top-level values in extension files that define them.");
        }
        result.add(SkylarkProviderIdentifier.forKey(constructor.getKey()));
      }
    }
    return ImmutableSet.copyOf(result.build());
  }

  private static ImmutableList<ImmutableSet<SkylarkProviderIdentifier>> getProvidersList(
      Sequence<?> skylarkList, String argumentName) throws EvalException {
    ImmutableList.Builder<ImmutableSet<SkylarkProviderIdentifier>> providersList =
        ImmutableList.builder();
    String errorMsg = "Illegal argument: element in '%s' is of unexpected type. "
        + "Either all elements should be providers, "
        + "or all elements should be lists of providers, but got %s.";

    for (Object o : skylarkList) {
      if (!(o instanceof Sequence)) {
        throw new EvalException(
            null,
            String.format(
                errorMsg,
                PROVIDERS_ARG,
                "an element of type " + EvalUtils.getDataTypeName(o, true)));
      }
      for (Object value : (Sequence) o) {
        if (!isProvider(value)) {
          throw new EvalException(
              null,
              String.format(
                  errorMsg,
                  argumentName,
                  "list with an element of type "
                      + EvalUtils.getDataTypeNameFromClass(value.getClass())));
        }
      }
      providersList.add(getSkylarkProviderIdentifiers((Sequence<?>) o));
    }
    return providersList.build();
  }

  private static Descriptor createAttrDescriptor(
      String name, Map<String, Object> kwargs, Type<?> type, StarlarkThread thread)
      throws EvalException {
    try {
      return new Descriptor(name, createAttributeFactory(type, null, kwargs, thread));
    } catch (ConversionException e) {
      throw new EvalException(null, e.getMessage());
    }
  }

  private static final Map<Type<?>, String> whyNotConfigurable =
      ImmutableMap.<Type<?>, String>builder()
          .put(BuildType.LICENSE,
              "loading phase license checking logic assumes non-configurable values")
          .put(BuildType.OUTPUT, "output paths are part of the static graph structure")
          .build();

  /**
   * If the given attribute type is non-configurable, returns the reason why. Otherwise, returns
   * {@code null}.
   */
  @Nullable
  public static String maybeGetNonConfigurableReason(Type<?> type) {
    return whyNotConfigurable.get(type);
  }

  private static Descriptor createNonconfigurableAttrDescriptor(
      String name, Map<String, Object> kwargs, Type<?> type, StarlarkThread thread)
      throws EvalException {
    String whyNotConfigurableReason =
        Preconditions.checkNotNull(maybeGetNonConfigurableReason(type), type);
    try {
      // We use an empty name now so that we can set it later.
      // This trick makes sense only in the context of Skylark (builtin rules should not use it).
      return new Descriptor(
          name,
          createAttribute(type, null, kwargs, thread, "")
              .nonconfigurable(whyNotConfigurableReason)
              .buildPartial());
    } catch (ConversionException e) {
      throw new EvalException(null, e.getMessage());
    }
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<attr>");
  }

  @Override
  public Descriptor intAttribute(
      Integer defaultValue,
      String doc,
      Boolean mandatory,
      Sequence<?> values,
      StarlarkThread thread)
      throws EvalException {
    // TODO(bazel-team): Replace literal strings with constants.
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.int");
    return createAttrDescriptor(
        "int",
        optionMap(DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory, VALUES_ARG, values),
        Type.INTEGER,
        thread);
  }

  @Override
  public Descriptor stringAttribute(
      String defaultValue,
      String doc,
      Boolean mandatory,
      Sequence<?> values,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.string");
    return createAttrDescriptor(
        "string",
        optionMap(DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory, VALUES_ARG, values),
        Type.STRING,
        thread);
  }

  @Override
  public Descriptor labelAttribute(
      Object defaultValue, // Label | String | LateBoundDefaultApi | StarlarkFunction
      String doc,
      Boolean executable,
      Object allowFiles,
      Object allowSingleFile,
      Boolean mandatory,
      Sequence<?> providers,
      Object allowRules,
      Boolean singleFile,
      Object cfg,
      Sequence<?> aspects,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.label");
    try {
      ImmutableAttributeFactory attribute =
          createAttributeFactory(
              BuildType.LABEL,
              doc,
              optionMap(
                  DEFAULT_ARG,
                  defaultValue,
                  EXECUTABLE_ARG,
                  executable,
                  ALLOW_FILES_ARG,
                  allowFiles,
                  ALLOW_SINGLE_FILE_ARG,
                  allowSingleFile,
                  MANDATORY_ARG,
                  mandatory,
                  PROVIDERS_ARG,
                  providers,
                  ALLOW_RULES_ARG,
                  allowRules,
                  SINGLE_FILE_ARG,
                  singleFile,
                  CONFIGURATION_ARG,
                  cfg,
                  ASPECTS_ARG,
                  aspects),
              thread,
              "label");
      return new Descriptor("label", attribute);
    } catch (EvalException e) {
      throw new EvalException(null, e.getMessage(), e);
    }
  }

  @Override
  public Descriptor stringListAttribute(
      Boolean mandatory,
      Boolean nonEmpty,
      Boolean allowEmpty,
      Sequence<?> defaultValue,
      String doc,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.string_list");
    return createAttrDescriptor(
        "string_list",
        optionMap(
            DEFAULT_ARG,
            defaultValue,
            MANDATORY_ARG,
            mandatory,
            NON_EMPTY_ARG,
            nonEmpty,
            ALLOW_EMPTY_ARG,
            allowEmpty),
        Type.STRING_LIST,
        thread);
  }

  @Override
  public Descriptor intListAttribute(
      Boolean mandatory,
      Boolean nonEmpty,
      Boolean allowEmpty,
      Sequence<?> defaultValue,
      String doc,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.int_list");
    return createAttrDescriptor(
        "int_list",
        optionMap(
            DEFAULT_ARG,
            defaultValue,
            MANDATORY_ARG,
            mandatory,
            NON_EMPTY_ARG,
            nonEmpty,
            ALLOW_EMPTY_ARG,
            allowEmpty),
        Type.INTEGER_LIST,
        thread);
  }

  @Override
  public Descriptor labelListAttribute(
      Boolean allowEmpty,
      Object defaultValue, // Sequence | StarlarkFunction
      String doc,
      Object allowFiles,
      Object allowRules,
      Sequence<?> providers,
      Sequence<?> flags,
      Boolean mandatory,
      Boolean nonEmpty,
      Object cfg,
      Sequence<?> aspects,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.label_list");
    Map<String, Object> kwargs =
        optionMap(
            DEFAULT_ARG,
            defaultValue,
            ALLOW_FILES_ARG,
            allowFiles,
            ALLOW_RULES_ARG,
            allowRules,
            PROVIDERS_ARG,
            providers,
            FLAGS_ARG,
            flags,
            MANDATORY_ARG,
            mandatory,
            NON_EMPTY_ARG,
            nonEmpty,
            ALLOW_EMPTY_ARG,
            allowEmpty,
            CONFIGURATION_ARG,
            cfg,
            ASPECTS_ARG,
            aspects);
    try {
      ImmutableAttributeFactory attribute =
          createAttributeFactory(BuildType.LABEL_LIST, doc, kwargs, thread, "label_list");
      return new Descriptor("label_list", attribute);
    } catch (EvalException e) {
      throw new EvalException(null, e.getMessage(), e);
    }
  }

  @Override
  public Descriptor labelKeyedStringDictAttribute(
      Boolean allowEmpty,
      Object defaultValue, // Dict | StarlarkFunction
      String doc,
      Object allowFiles,
      Object allowRules,
      Sequence<?> providers,
      Sequence<?> flags,
      Boolean mandatory,
      Boolean nonEmpty,
      Object cfg,
      Sequence<?> aspects,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.label_keyed_string_dict");
    Map<String, Object> kwargs =
        optionMap(
            DEFAULT_ARG,
            defaultValue,
            ALLOW_FILES_ARG,
            allowFiles,
            ALLOW_RULES_ARG,
            allowRules,
            PROVIDERS_ARG,
            providers,
            FLAGS_ARG,
            flags,
            MANDATORY_ARG,
            mandatory,
            NON_EMPTY_ARG,
            nonEmpty,
            ALLOW_EMPTY_ARG,
            allowEmpty,
            CONFIGURATION_ARG,
            cfg,
            ASPECTS_ARG,
            aspects);
    try {
      ImmutableAttributeFactory attribute =
          createAttributeFactory(
              BuildType.LABEL_KEYED_STRING_DICT,
              doc,
              kwargs,
              thread,
              "label_keyed_string_dict");
      return new Descriptor("label_keyed_string_dict", attribute);
    } catch (EvalException e) {
      throw new EvalException(null, e.getMessage(), e);
    }
  }

  @Override
  public Descriptor boolAttribute(
      Boolean defaultValue, String doc, Boolean mandatory, StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.bool");
    return createAttrDescriptor(
        "bool",
        optionMap(DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory),
        Type.BOOLEAN,
        thread);
  }

  @Override
  public Descriptor outputAttribute(
      String doc,
      Boolean mandatory,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.output");

    return createNonconfigurableAttrDescriptor(
        "output", optionMap(MANDATORY_ARG, mandatory), BuildType.OUTPUT, thread);
  }

  @Override
  public Descriptor outputListAttribute(
      Boolean allowEmpty,
      String doc,
      Boolean mandatory,
      Boolean nonEmpty,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.output_list");

    return createAttrDescriptor(
        "output_list",
        optionMap(
            MANDATORY_ARG,
            mandatory,
            NON_EMPTY_ARG,
            nonEmpty,
            ALLOW_EMPTY_ARG,
            allowEmpty),
        BuildType.OUTPUT_LIST,
        thread);
  }

  @Override
  public Descriptor stringDictAttribute(
      Boolean allowEmpty,
      Dict<?, ?> defaultValue,
      String doc,
      Boolean mandatory,
      Boolean nonEmpty,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.string_dict");
    return createAttrDescriptor(
        "string_dict",
        optionMap(
            DEFAULT_ARG,
            defaultValue,
            MANDATORY_ARG,
            mandatory,
            NON_EMPTY_ARG,
            nonEmpty,
            ALLOW_EMPTY_ARG,
            allowEmpty),
        Type.STRING_DICT,
        thread);
  }

  @Override
  public Descriptor stringListDictAttribute(
      Boolean allowEmpty,
      Dict<?, ?> defaultValue,
      String doc,
      Boolean mandatory,
      Boolean nonEmpty,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.string_list_dict");
    return createAttrDescriptor(
        "string_list_dict",
        optionMap(
            DEFAULT_ARG,
            defaultValue,
            MANDATORY_ARG,
            mandatory,
            NON_EMPTY_ARG,
            nonEmpty,
            ALLOW_EMPTY_ARG,
            allowEmpty),
        Type.STRING_LIST_DICT,
        thread);
  }

  @Override
  public Descriptor licenseAttribute(
      Object defaultValue, String doc, Boolean mandatory, StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext.from(thread).checkLoadingOrWorkspacePhase("attr.license");
    return createNonconfigurableAttrDescriptor(
        "license",
        optionMap(DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory),
        BuildType.LICENSE,
        thread);
  }

  /** A descriptor of an attribute defined in Skylark. */
  @AutoCodec
  public static final class Descriptor implements SkylarkAttrApi.Descriptor {
    private final ImmutableAttributeFactory attributeFactory;
    private final String name;

    @AutoCodec.VisibleForSerialization
    Descriptor(String name, ImmutableAttributeFactory attributeFactory) {
      this.attributeFactory = Preconditions.checkNotNull(attributeFactory);
      this.name = name;
    }

    public boolean hasDefault() {
      return attributeFactory.isValueSet();
    }

    public AttributeValueSource getValueSource() {
      return attributeFactory.getValueSource();
    }

    public Attribute build(String name) {
      return attributeFactory.build(name);
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<attr." + name + ">");
    }
  }

  // Returns an immutable map from a list of alternating name/value pairs,
  // skipping values that are null or None. Keys must be unique.
  private static Map<String, Object> optionMap(Object... pairs) {
    Preconditions.checkArgument(pairs.length % 2 == 0);
    ImmutableMap.Builder<String, Object> b = new ImmutableMap.Builder<>();
    for (int i = 0; i < pairs.length; i += 2) {
      String key = (String) Preconditions.checkNotNull(pairs[i]);
      Object value = pairs[i + 1];
      if (value != null && value != Starlark.NONE) {
        b.put(key, value);
      }
    }
    return b.build();
  }
}
