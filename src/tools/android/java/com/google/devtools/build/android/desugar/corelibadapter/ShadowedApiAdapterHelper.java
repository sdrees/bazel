/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.build.android.desugar.corelibadapter;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.android.desugar.langmodel.ClassName;
import com.google.devtools.build.android.desugar.langmodel.MemberUseKind;
import com.google.devtools.build.android.desugar.langmodel.MethodDeclInfo;
import com.google.devtools.build.android.desugar.langmodel.MethodInvocationSite;
import com.google.devtools.build.android.desugar.langmodel.MethodKey;
import com.google.devtools.build.android.desugar.typehierarchy.HierarchicalMethodKey;
import com.google.devtools.build.android.desugar.typehierarchy.HierarchicalMethodQuery;
import com.google.devtools.build.android.desugar.typehierarchy.TypeHierarchy;
import java.util.Optional;
import org.objectweb.asm.Type;

/**
 * Static utilities that serve conversions between desugar-shadowed platform types and their
 * desugared-mirrored counterparts.
 */
public class ShadowedApiAdapterHelper {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private ShadowedApiAdapterHelper() {}

  /**
   * Returns {@code true} if the desugar tool transforms given invocation site in an inline
   * strategy, i.e. inserting the parameter type conversion instructions before the give invocation
   * site.
   *
   * @param verbatimInvocationSite The invocation site parsed directly from the desugar input jar.
   *     No in-process label, such as "__desugar__/", is attached to this invocation site.
   * @param typeHierarchy The type hierarchy context of for this query API.
   */
  static boolean shouldUseInlineTypeConversion(
      MethodInvocationSite verbatimInvocationSite, TypeHierarchy typeHierarchy) {
    if (verbatimInvocationSite.method().getHeaderTypeNameSet().stream()
        .noneMatch(ClassName::isDesugarShadowedType)) {
      return false;
    }

    ClassName adjustedGrossOwner = verbatimInvocationSite.owner();

    // Upon on a super call, trace to the adjusted owner with code.
    if (verbatimInvocationSite.invocationKind() == MemberUseKind.INVOKESPECIAL
        && !verbatimInvocationSite.isConstructorInvocation()) {
      HierarchicalMethodQuery hierarchicalMethodQuery =
          HierarchicalMethodKey.from(verbatimInvocationSite.method())
              .inTypeHierarchy(typeHierarchy);
      if (!hierarchicalMethodQuery.isPresent()) {
        HierarchicalMethodKey baseMethod = hierarchicalMethodQuery.getFirstBaseClassMethod();
        if (baseMethod != null) {
          adjustedGrossOwner = baseMethod.owner().type();
        } else {
          logger.atSevere().log("Missing base method lookup: %s", verbatimInvocationSite);
        }
      }
    }

    return verbatimInvocationSite.invocationKind() == MemberUseKind.INVOKESPECIAL
        && adjustedGrossOwner.isInPackageEligibleForTypeAdapter();
  }

  /**
   * Returns {@code true} if the desugar tool transforms given invocation site in an adapter
   * strategy, that is to replace the original invocation with its corresponding adapter method.
   *
   * @param verbatimInvocationSite The invocation site parsed directly from the desugar input jar.
   *     No in-process label, such as "__desugar__/", is attached to this invocation site.
   */
  static boolean shouldUseApiTypeAdapter(MethodInvocationSite verbatimInvocationSite) {
    return verbatimInvocationSite.invocationKind() != MemberUseKind.INVOKESPECIAL
        && verbatimInvocationSite.owner().isInPackageEligibleForTypeAdapter()
        && verbatimInvocationSite.method().getHeaderTypeNameSet().stream()
            .anyMatch(ClassName::isDesugarShadowedType);
  }

  /**
   * Returns {@code true} if the current method overrides a platform API with desugar-shadowed types
   * and should emit an overriding bridge method for the integrity of method dynamic dispatching.
   */
  static boolean shouldEmitApiOverridingBridge(
      MethodDeclInfo methodDeclInfo, TypeHierarchy typeHierarchy) {
    if (!methodDeclInfo.owner().isInPackageEligibleForHoldingOverridingBridges()
        || methodDeclInfo.methodKey().isConstructor()
        || methodDeclInfo.isStaticMethod()
        || methodDeclInfo.isPrivateAccess()
        || methodDeclInfo.headerTypeNameSet().stream()
            .noneMatch(ClassName::isDesugarShadowedType)) {
      return false;
    }

    HierarchicalMethodKey baseMethod =
        HierarchicalMethodKey.from(methodDeclInfo.methodKey())
            .inTypeHierarchy(typeHierarchy)
            .getFirstBaseClassMethod();
    return baseMethod != null
        && baseMethod.owner().type().isInPackageEligibleForShadowedOverridableAPIs();
  }

  /**
   * Returns an optional {@link MethodInvocationSite}, present if the given {@link ClassName} is
   * eligible for transforming a desugar-mirrored type to a desugar-shadowed platform type.
   */
  static Optional<MethodInvocationSite> anyMirroredToBuiltinTypeConversion(ClassName className) {
    return className.isDesugarMirroredType()
        ? Optional.of(
            MethodInvocationSite.builder()
                .setInvocationKind(MemberUseKind.INVOKESTATIC)
                .setMethod(
                    MethodKey.create(
                        className.mirroredToShadowed().typeConverterOwner(),
                        "to",
                        Type.getMethodDescriptor(
                            className.mirroredToShadowed().toAsmObjectType(),
                            className.toAsmObjectType())))
                .setIsInterface(false)
                .build())
        : Optional.empty();
  }

  /**
   * Returns an {@link MethodInvocationSite} that serves transforming a {@code
   * shadowedTypeName}-represented type to its desugar-mirrored counterpart.
   */
  public static MethodInvocationSite shadowedToMirroredTypeConversionSite(
      ClassName shadowedTypeName) {
    checkArgument(
        shadowedTypeName.isDesugarShadowedType(),
        "Expected desugar-shadowed type: Actual (%s)",
        shadowedTypeName);
    return MethodInvocationSite.builder()
        .setInvocationKind(MemberUseKind.INVOKESTATIC)
        .setMethod(
            MethodKey.create(
                shadowedTypeName.typeConverterOwner(),
                "from",
                Type.getMethodDescriptor(
                    shadowedTypeName.shadowedToMirrored().toAsmObjectType(),
                    shadowedTypeName.toAsmObjectType())))
        .setIsInterface(false)
        .build();
  }

  /**
   * Returns an {@link MethodInvocationSite} that serves transforming a {@code
   * mirroredTypeName}-represented type to its desugar-shadowed counterpart.
   */
  static MethodInvocationSite mirroredToShadowedTypeConversionSite(ClassName mirroredTypeName) {
    checkArgument(
        mirroredTypeName.isDesugarMirroredType(),
        "Expected mirrored type: Actual (%s)",
        mirroredTypeName);
    return MethodInvocationSite.builder()
        .setInvocationKind(MemberUseKind.INVOKESTATIC)
        .setMethod(
            MethodKey.create(
                mirroredTypeName.mirroredToShadowed().typeConverterOwner(),
                "to",
                Type.getMethodDescriptor(
                    mirroredTypeName.mirroredToShadowed().toAsmObjectType(),
                    mirroredTypeName.toAsmObjectType())))
        .setIsInterface(false)
        .build();
  }

  /**
   * Returns an {@link MethodInvocationSite} that serves as an adapter between desugar-mirrored
   * invocations and desugar-shadowed invocations.
   */
  static MethodInvocationSite getAdapterInvocationSite(MethodInvocationSite methodInvocationSite) {
    return MethodInvocationSite.builder()
        .setInvocationKind(MemberUseKind.INVOKESTATIC)
        .setMethod(
            methodInvocationSite
                .method()
                .toAdapterMethodForArgsAndReturnTypes(
                    methodInvocationSite.isStaticInvocation(), methodInvocationSite.hashCode()))
        .setIsInterface(false)
        .build();
  }
}
