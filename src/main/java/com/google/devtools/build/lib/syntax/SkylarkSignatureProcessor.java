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
package com.google.devtools.build.lib.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Booleans;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction.ExtraArgKind;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/**
 * This class defines utilities to process @SkylarkSignature annotations
 * to configure a given field.
 */
public class SkylarkSignatureProcessor {

  // A cache mapping string representation of a skylark parameter default value to the object
  // represented by that string. For example, "None" -> Runtime.NONE. This cache is manually
  // maintained (instead of using, for example, a LoadingCache), as default values may sometimes
  // be recursively requested.
  private static final ConcurrentHashMap<String, Object> defaultValueCache =
      new ConcurrentHashMap<>();

  /** Holds signature information extracted from a method's annotation. */
  public static final class SignatureInfo {
    public final FunctionSignature signature;
    @Nullable public final List<Object> defaultValues;
    @Nullable final List<SkylarkType> types; // "official" types (may differ from "enforced")

    SignatureInfo(
        FunctionSignature signature,
        @Nullable List<Object> defaultValues,
        @Nullable List<SkylarkType> types) {
      this.signature = signature;
      this.defaultValues = defaultValues;
      this.types = types;
    }
  }

  /**
   * Extracts signature information from a {@link SkylarkCallable}-annotated method.
   *
   * @param name the name of the function
   * @param descriptor the method descriptor
   * @param paramDoc an optional list into which to store documentation strings
   * @param enforcedTypesList an optional list into which to store effective types to enforce
   */
  public static SignatureInfo getSignatureForCallable(
      String name,
      MethodDescriptor descriptor,
      @Nullable List<String> paramDoc,
      @Nullable List<SkylarkType> enforcedTypesList) {

    SkylarkCallable annotation = descriptor.getAnnotation();

    // TODO(cparsons): Validate these properties with the annotation processor instead.
    Preconditions.checkArgument(name.equals(annotation.name()),
        "%s != %s", name, annotation.name());
    boolean documented = annotation.documented();
    if (annotation.doc().isEmpty() && documented) {
      throw new RuntimeException(String.format("function %s is undocumented", name));
    }

    return getSignatureForCallableImpl(
        name,
        documented,
        annotation.parameters(),
        annotation.extraPositionals(),
        annotation.extraKeywords(),
        paramDoc,
        enforcedTypesList);
  }

  /**
   * Extracts signature information from a {@link SkylarkSignature} annotation.
   *
   * @param name the name of the function
   * @param annotation the annotation
   * @param paramDoc an optional list into which to store documentation strings
   * @param enforcedTypesList an optional list into which to store effective types to enforce
   */
  // NB: the two arguments paramDoc and enforcedTypesList are used to "return" extra values via
  // side-effects, and that's ugly
  // TODO(bazel-team): use AutoValue to declare a value type to use as return value?
  static SignatureInfo getSignatureForCallable(
      String name,
      SkylarkSignature annotation,
      @Nullable List<String> paramDoc,
      @Nullable List<SkylarkType> enforcedTypesList) {

    Preconditions.checkArgument(name.equals(annotation.name()),
        "%s != %s", name, annotation.name());
    boolean documented = annotation.documented();
    if (annotation.doc().isEmpty() && documented) {
      throw new RuntimeException(String.format("function %s is undocumented", name));
    }
    return getSignatureForCallableImpl(
        name,
        documented,
        annotation.parameters(),
        annotation.extraPositionals(),
        annotation.extraKeywords(),
        paramDoc,
        enforcedTypesList);
  }

  private static boolean isParamNamed(Param param) {
    return param.named() || param.legacyNamed();
  }

  // TODO(bazel-team): Maybe have the annotation be a string representing the
  // python-style calling convention including default values, and have the regular Parser
  // process it? (builtin function call not allowed when evaluating values, but more complex
  // values are possible by referencing variables in some definition environment).
  // Then the only per-parameter information needed is a documentation string.

  // Build-time annotation processing ensures mandatory parameters do not follow optional ones.
  private static SignatureInfo getSignatureForCallableImpl(
      final String name,
      final boolean documented,
      Param[] parameters,
      @Nullable Param extraPositionals,
      @Nullable Param extraKeywords,
      @Nullable List<String> paramDoc,
      @Nullable List<SkylarkType> enforcedTypesList) {
    final HashMap<String, SkylarkType> enforcedTypes = new HashMap<>();
    final HashMap<String, String> doc = new HashMap<>();

    // TODO(adonovan): simplify this logic, possibly sharing or delegating to pieces of the
    // analogous logic in the parser/validator.

    BiFunction<Param, Object, SkylarkType> getParameterType =
        (Param param, Object defaultValue) ->
            getParameterType(name, documented, enforcedTypes, doc, param, defaultValue);

    int mandatoryPositionals = 0;
    int optionalPositionals = 0;
    int mandatoryNamedOnly = 0;
    int optionalNamedOnly = 0;
    boolean hasStar = false;
    String star = null;
    String starStar = null;
    SkylarkType starType = null;
    SkylarkType starStarType = null;
    ArrayList<String> params = new ArrayList<>();
    ArrayList<Object> defaults = new ArrayList<>();
    ArrayList<SkylarkType> types = new ArrayList<>();
    // optional named-only parameters are kept aside to be spliced after the mandatory ones.
    ArrayList<String> optionalNamedOnlyParams = new ArrayList<>();
    ArrayList<SkylarkType> optionalNamedOnlyTypes = new ArrayList<>();
    ArrayList<Object> optionalNamedOnlyDefaultValues = new ArrayList<>();

    for (Param param : parameters) {
      // Implicit * or *args parameter separates transition from positional to named.
      // f (..., *, ... )  or  f(..., *args, ...)
      if (isParamNamed(param) && !param.positional() && !hasStar) {
        hasStar = true;
        if (extraPositionals != null && !extraPositionals.name().isEmpty()) {
          starType = getParameterType.apply(extraPositionals, null);
          star = extraPositionals.name();
        }
      }

      boolean mandatory = param.defaultValue().isEmpty();
      if (mandatory) {
        // f(..., name, ...): required parameter
        SkylarkType t = getParameterType.apply(param, null);
        params.add(param.name());
        types.add(t);
        if (hasStar) {
          mandatoryNamedOnly++;
        } else {
          mandatoryPositionals++;
        }

      } else {
        // f(..., name=value, ...): optional parameter
        Object defaultValue = getDefaultValue(param);
        SkylarkType t = getParameterType.apply(param, defaultValue);
        if (hasStar) {
          optionalNamedOnly++;
          optionalNamedOnlyParams.add(param.name());
          optionalNamedOnlyTypes.add(t);
          optionalNamedOnlyDefaultValues.add(defaultValue);
        } else {
          optionalPositionals++;
          params.add(param.name());
          types.add(t);
          defaults.add(defaultValue);
        }
      }
    }
    params.addAll(optionalNamedOnlyParams);
    types.addAll(optionalNamedOnlyTypes);
    defaults.addAll(optionalNamedOnlyDefaultValues);

    // f(..., *args, ...)
    if (extraPositionals != null && !extraPositionals.name().isEmpty() && !hasStar) {
      star = extraPositionals.name();
      starType = getParameterType.apply(extraPositionals, null);
    }
    if (star != null) {
      params.add(star);
      types.add(starType);
    }

    // f(..., **kwargs)
    if (extraKeywords != null && !extraKeywords.name().isEmpty()) {
      starStar = extraKeywords.name();
      starStarType = getParameterType.apply(extraKeywords, null);
      params.add(starStar);
      types.add(starStarType);
    }

    FunctionSignature signature =
        FunctionSignature.create(
            mandatoryPositionals,
            optionalPositionals,
            mandatoryNamedOnly,
            optionalNamedOnly,
            star != null,
            starStar != null,
            ImmutableList.copyOf(params));

    for (String paramName : signature.getParameterNames()) {
      if (enforcedTypesList != null) {
        enforcedTypesList.add(enforcedTypes.get(paramName));
      }
      if (paramDoc != null) {
        paramDoc.add(doc.get(paramName));
      }
    }
    return new SignatureInfo(signature, defaults, types);
  }

  // getParameterType returns the parameter's type from the @Param annotation,
  // applies other checks and populates the type and doc mappings.
  private static SkylarkType getParameterType(
      // Param-independent:
      String name,
      boolean documented,
      HashMap<String, SkylarkType> enforcedTypes,
      HashMap<String, String> doc,
      // Param-specific:
      Param param,
      @Nullable Object defaultValue) {
    SkylarkType officialType = null;
    SkylarkType enforcedType = null;
    if (param.type() != Object.class) {
      if (param.generic1() != Object.class) {
        // Enforce the proper parametric type for Starlark list and set objects
        officialType = SkylarkType.of(param.type(), param.generic1());
        enforcedType = officialType;
      } else {
        officialType = SkylarkType.of(param.type());
        enforcedType = officialType;
      }
      if (param.callbackEnabled()) {
        officialType =
            SkylarkType.Union.of(
                officialType, SkylarkType.SkylarkFunctionType.of(name, officialType));
        enforcedType =
            SkylarkType.Union.of(
                enforcedType, SkylarkType.SkylarkFunctionType.of(name, enforcedType));
      }
      if (param.noneable()) {
        officialType = SkylarkType.Union.of(officialType, SkylarkType.NONE);
        enforcedType = SkylarkType.Union.of(enforcedType, SkylarkType.NONE);
      }
    }
    if (enforcedTypes.put(param.name(), enforcedType) != null) {
      throw new IllegalStateException(
          String.format("duplicate parameter %s on method %s", param.name(), name));
    }
    if (param.doc().isEmpty() && documented) {
      throw new IllegalStateException(
          String.format("parameter %s on method %s is undocumented", param.name(), name));
    }
    doc.put(param.name(), param.doc());
    if (defaultValue != null && !defaultValue.equals(Runtime.UNBOUND) && enforcedType != null) {
      Preconditions.checkArgument(
          enforcedType.contains(defaultValue),
          "In function '%s', parameter '%s' has default value %s that isn't of enforced type"
              + " %s",
          name,
          param.name(),
          Printer.repr(defaultValue),
          enforcedType);
    }
    return officialType;
  }

  static Object getDefaultValue(Param param) {
    return getDefaultValue(param.name(), param.defaultValue());
  }

  static Object getDefaultValue(String paramName, String paramDefaultValue) {
    if (paramDefaultValue.isEmpty()) {
      return Runtime.NONE;
    } else {
      try {
        Object defaultValue = defaultValueCache.get(paramDefaultValue);
        if (defaultValue != null) {
          return defaultValue;
        }
        try (Mutability mutability = Mutability.create("initialization")) {
          // Note that this Skylark thread ignores command line flags.
          StarlarkThread thread =
              StarlarkThread.builder(mutability)
                  .useDefaultSemantics()
                  .setGlobals(StarlarkThread.CONSTANTS_ONLY)
                  .build()
                  .update("unbound", Runtime.UNBOUND);
          defaultValue = EvalUtils.eval(ParserInput.fromLines(paramDefaultValue), thread);
          defaultValueCache.put(paramDefaultValue, defaultValue);
          return defaultValue;
        }
      } catch (Exception e) {
        throw new RuntimeException(
            String.format(
                "Exception while processing @SkylarkSignature.Param %s, default value %s",
                paramName, paramDefaultValue),
            e);
      }
    }
  }

  /** Extract additional signature information for BuiltinFunction-s */
  static ExtraArgKind[] getExtraArgs(SkylarkSignature annotation) {
    final int numExtraArgs =
        Booleans.countTrue(
            annotation.useLocation(), annotation.useAst(), annotation.useStarlarkThread());
    if (numExtraArgs == 0) {
      return null;
    }
    final ExtraArgKind[] extraArgs = new ExtraArgKind[numExtraArgs];
    int i = 0;
    if (annotation.useLocation()) {
      extraArgs[i++] = ExtraArgKind.LOCATION;
    }
    if (annotation.useAst()) {
      extraArgs[i++] = ExtraArgKind.SYNTAX_TREE;
    }
    if (annotation.useStarlarkThread()) {
      extraArgs[i++] = ExtraArgKind.ENVIRONMENT;
    }
    return extraArgs;
  }

  /**
   * Processes all {@link SkylarkSignature}-annotated fields in a class.
   *
   * <p>This includes registering these fields as builtins using {@link Runtime}, and for {@link
   * BaseFunction} instances, calling {@link BaseFunction#configure(SkylarkSignature)}. The fields
   * will be picked up by reflection even if they are not public.
   *
   * <p>This function should be called once per class, before the builtins registry is frozen. In
   * practice, this is usually called from the class's own static initializer block. E.g., a class
   * {@code Foo} containing {@code @SkylarkSignature} annotations would end with
   * {@code static { SkylarkSignatureProcessor.configureSkylarkFunctions(Foo.class); }}.
   *
   * <p><b>If you see exceptions from {@link Runtime.BuiltinRegistry} here:</b> Be sure the class's
   * static initializer has in fact been called before the registry was frozen. In Bazel, see
   * {@link com.google.devtools.build.lib.runtime.BlazeRuntime#initSkylarkBuiltinsRegistry}.
   */
  public static void configureSkylarkFunctions(Class<?> type) {
    Runtime.BuiltinRegistry builtins = Runtime.getBuiltinRegistry();
    for (Field field : type.getDeclaredFields()) {
      if (field.isAnnotationPresent(SkylarkSignature.class)) {
        // The annotated fields are often private, but we need access them.
        field.setAccessible(true);
        SkylarkSignature annotation = field.getAnnotation(SkylarkSignature.class);
        Object value = null;
        try {
          value =
              Preconditions.checkNotNull(
                  field.get(null),
                  "Error while trying to configure %s.%s: its value is null",
                  type,
                  field);
          builtins.registerBuiltin(type, field.getName(), value);
          if (BaseFunction.class.isAssignableFrom(field.getType())) {
            BaseFunction function = (BaseFunction) value;
            if (!function.isConfigured()) {
              function.configure(annotation);
            }
            Class<?> nameSpace = function.getObjectType();
            if (nameSpace != null) {
              builtins.registerFunction(nameSpace, function);
            }
          }
        } catch (IllegalAccessException e) {
          throw new RuntimeException(String.format(
              "Error while trying to configure %s.%s (value %s)", type, field, value), e);
        }
      }
    }
  }
}
