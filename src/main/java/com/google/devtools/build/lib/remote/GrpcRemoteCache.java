// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.remote;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheBlockingStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashingOutputStream;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteRetrier.ProgressiveBackoff;
import com.google.devtools.build.lib.remote.common.SimpleBlobStore.ActionKey;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.grpc.CallCredentials;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
@ThreadSafe
public class GrpcRemoteCache extends AbstractRemoteActionCache {
  private final CallCredentials credentials;
  private final ReferenceCountedChannel channel;
  private final RemoteRetrier retrier;
  private final ByteStreamUploader uploader;
  private final int maxMissingBlobsDigestsPerMessage;

  private AtomicBoolean closed = new AtomicBoolean();

  @VisibleForTesting
  public GrpcRemoteCache(
      ReferenceCountedChannel channel,
      CallCredentials credentials,
      RemoteOptions options,
      RemoteRetrier retrier,
      DigestUtil digestUtil,
      ByteStreamUploader uploader) {
    super(options, digestUtil);
    this.credentials = credentials;
    this.channel = channel;
    this.retrier = retrier;
    this.uploader = uploader;
    maxMissingBlobsDigestsPerMessage = computeMaxMissingBlobsDigestsPerMessage();
    Preconditions.checkState(
        maxMissingBlobsDigestsPerMessage > 0, "Error: gRPC message size too small.");
  }

  private int computeMaxMissingBlobsDigestsPerMessage() {
    final int overhead =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .build()
            .getSerializedSize();
    final int tagSize =
        FindMissingBlobsRequest.newBuilder()
                .addBlobDigests(Digest.getDefaultInstance())
                .build()
                .getSerializedSize()
            - FindMissingBlobsRequest.getDefaultInstance().getSerializedSize();
    // We assume all non-empty digests have the same size. This is true for fixed-length hashes.
    final int digestSize = digestUtil.compute(new byte[] {1}).getSerializedSize() + tagSize;
    return (options.maxOutboundMessageSize - overhead) / digestSize;
  }

  private ContentAddressableStorageFutureStub casFutureStub() {
    return ContentAddressableStorageGrpc.newFutureStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ByteStreamStub bsAsyncStub() {
    return ByteStreamGrpc.newStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ActionCacheBlockingStub acBlockingStub() {
    return ActionCacheGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    uploader.release();
    channel.release();
  }

  /** Returns true if 'options.remoteCache' uses 'grpc' or an empty scheme */
  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    if (isNullOrEmpty(options.remoteCache)) {
      return false;
    }
    // TODO(ishikhman): add proper URI validation/parsing for remote options
    return !(Ascii.toLowerCase(options.remoteCache).startsWith("http://")
        || Ascii.toLowerCase(options.remoteCache).startsWith("https://"));
  }

  @Override
  public ListenableFuture<ImmutableSet<Digest>> findMissingDigests(Iterable<Digest> digests) {
    if (Iterables.isEmpty(digests)) {
      return Futures.immediateFuture(ImmutableSet.of());
    }
    // Need to potentially split the digests into multiple requests.
    FindMissingBlobsRequest.Builder requestBuilder =
        FindMissingBlobsRequest.newBuilder().setInstanceName(options.remoteInstanceName);
    List<ListenableFuture<FindMissingBlobsResponse>> getMissingDigestCalls = new ArrayList<>();
    for (Digest digest : digests) {
      requestBuilder.addBlobDigests(digest);
      if (requestBuilder.getBlobDigestsCount() == maxMissingBlobsDigestsPerMessage) {
        getMissingDigestCalls.add(getMissingDigests(requestBuilder.build()));
        requestBuilder.clearBlobDigests();
      }
    }

    if (requestBuilder.getBlobDigestsCount() > 0) {
      getMissingDigestCalls.add(getMissingDigests(requestBuilder.build()));
    }

    return Futures.whenAllSucceed(getMissingDigestCalls)
        .call(
            () -> {
              ImmutableSet.Builder<Digest> result = ImmutableSet.builder();
              for (ListenableFuture<FindMissingBlobsResponse> callFuture : getMissingDigestCalls) {
                result.addAll(callFuture.get().getMissingBlobDigestsList());
              }
              return result.build();
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<FindMissingBlobsResponse> getMissingDigests(
      FindMissingBlobsRequest request) {
    Context ctx = Context.current();
    return retrier.executeAsync(() -> ctx.call(() -> casFutureStub().findMissingBlobs(request)));
  }

  /**
   * Ensures that the tree structure of the inputs, the input files themselves, and the command are
   * available in the remote cache, such that the tree can be reassembled and executed on another
   * machine given the root digest.
   *
   * <p>The cache may check whether files or parts of the tree structure are already present, and do
   * not need to be uploaded again.
   *
   * <p>Note that this method is only required for remote execution, not for caching itself.
   * However, remote execution uses a cache to store input files, and that may be a separate
   * end-point from the executor itself, so the functionality lives here.
   */
  public void ensureInputsPresent(
      MerkleTree merkleTree, Map<Digest, Message> additionalInputs, Path execRoot)
      throws IOException, InterruptedException {
    Iterable<Digest> allDigests =
        Iterables.concat(merkleTree.getAllDigests(), additionalInputs.keySet());
    ImmutableSet<Digest> missingDigests = Utils.getFromFuture(findMissingDigests(allDigests));
    Map<HashCode, Chunker> inputsToUpload = Maps.newHashMapWithExpectedSize(missingDigests.size());
    for (Digest missingDigest : missingDigests) {
      Directory node = merkleTree.getDirectoryByDigest(missingDigest);
      HashCode hash = HashCode.fromString(missingDigest.getHash());
      if (node != null) {
        Chunker c = Chunker.builder().setInput(node.toByteArray()).build();
        inputsToUpload.put(hash, c);
        continue;
      }

      ActionInput file = merkleTree.getInputByDigest(missingDigest);
      if (file != null) {
        Chunker c =
            Chunker.builder().setInput(missingDigest.getSizeBytes(), file, execRoot).build();
        inputsToUpload.put(hash, c);
        continue;
      }

      Message message = additionalInputs.get(missingDigest);
      if (message != null) {
        Chunker c = Chunker.builder().setInput(message.toByteArray()).build();
        inputsToUpload.put(hash, c);
        continue;
      }

      throw new IOException(
          format(
              "findMissingDigests returned a missing digest that has not been requested: %s",
              missingDigest));
    }

    uploader.uploadBlobs(inputsToUpload, /* forceUpload= */ true);
  }

  @Override
  protected ListenableFuture<Void> downloadBlob(Digest digest, OutputStream out) {
    if (digest.getSizeBytes() == 0) {
      return Futures.immediateFuture(null);
    }
    String resourceName = "";
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName += options.remoteInstanceName + "/";
    }
    resourceName += "blobs/" + digestUtil.toString(digest);

    @Nullable Supplier<HashCode> hashSupplier = null;
    if (options.remoteVerifyDownloads) {
      HashingOutputStream hashOut = digestUtil.newHashingOutputStream(out);
      hashSupplier = hashOut::hash;
      out = hashOut;
    }

    SettableFuture<Void> outerF = SettableFuture.create();
    Futures.addCallback(
        downloadBlob(resourceName, digest, out, hashSupplier),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            outerF.set(null);
          }

          @Override
          public void onFailure(Throwable t) {
            if (t instanceof StatusRuntimeException) {
              t = new IOException(t);
            }
            outerF.setException(t);
          }
        },
        Context.current().fixedContextExecutor(MoreExecutors.directExecutor()));
    return outerF;
  }

  private ListenableFuture<Void> downloadBlob(
      String resourceName,
      Digest digest,
      OutputStream out,
      @Nullable Supplier<HashCode> hashSupplier) {
    Context ctx = Context.current();
    AtomicLong offset = new AtomicLong(0);
    ProgressiveBackoff progressiveBackoff = new ProgressiveBackoff(retrier::newBackoff);
    return Futures.catchingAsync(
        retrier.executeAsync(
            () ->
                ctx.call(
                    () ->
                        requestRead(
                            resourceName, offset, progressiveBackoff, digest, out, hashSupplier)),
            progressiveBackoff),
        StatusRuntimeException.class,
        (e) -> Futures.immediateFailedFuture(new IOException(e)),
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<Void> requestRead(
      String resourceName,
      AtomicLong offset,
      ProgressiveBackoff progressiveBackoff,
      Digest digest,
      OutputStream out,
      @Nullable Supplier<HashCode> hashSupplier) {
    SettableFuture<Void> future = SettableFuture.create();
    bsAsyncStub()
        .read(
            ReadRequest.newBuilder()
                .setResourceName(resourceName)
                .setReadOffset(offset.get())
                .build(),
            new StreamObserver<ReadResponse>() {
              @Override
              public void onNext(ReadResponse readResponse) {
                ByteString data = readResponse.getData();
                try {
                  data.writeTo(out);
                  offset.addAndGet(data.size());
                } catch (IOException e) {
                  future.setException(e);
                  // Cancel the call.
                  throw new RuntimeException(e);
                }
                // reset the stall backoff because we've made progress or been kept alive
                progressiveBackoff.reset();
              }

              @Override
              public void onError(Throwable t) {
                Status status = Status.fromThrowable(t);
                if (status.getCode() == Status.Code.NOT_FOUND) {
                  future.setException(new CacheNotFoundException(digest, digestUtil));
                } else {
                  future.setException(t);
                }
              }

              @Override
              public void onCompleted() {
                try {
                  if (hashSupplier != null) {
                    verifyContents(
                        digest.getHash(), DigestUtil.hashCodeToString(hashSupplier.get()));
                  }
                  out.flush();
                  future.set(null);
                } catch (IOException e) {
                  future.setException(e);
                }
              }
            });
    return future;
  }

  @Override
  protected ListenableFuture<Void> uploadFile(Digest digest, Path path) {
    return uploader.uploadBlobAsync(
        HashCode.fromString(digest.getHash()),
        Chunker.builder().setInput(digest.getSizeBytes(), path).build(),
        /* forceUpload= */ true);
  }

  @Override
  protected ListenableFuture<Void> uploadBlob(Digest digest, ByteString data) {
    return uploader.uploadBlobAsync(
        HashCode.fromString(digest.getHash()),
        Chunker.builder().setInput(data.toByteArray()).build(),
        /* forceUpload= */ true);
  }

  // Execution Cache API

  @Override
  public ActionResult getCachedActionResult(ActionKey actionKey)
      throws IOException, InterruptedException {
    try {
      return retrier.execute(
          () ->
              acBlockingStub()
                  .getActionResult(
                      GetActionResultRequest.newBuilder()
                          .setInstanceName(options.remoteInstanceName)
                          .setActionDigest(actionKey.getDigest())
                          .build()));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        // Return null to indicate that it was a cache miss.
        return null;
      }
      throw new IOException(e);
    }
  }

  @Override
  protected void setCachedActionResult(ActionKey actionKey, ActionResult result)
      throws IOException, InterruptedException {
    try {
      retrier.execute(
          () ->
              acBlockingStub()
                  .updateActionResult(
                      UpdateActionResultRequest.newBuilder()
                          .setInstanceName(options.remoteInstanceName)
                          .setActionDigest(actionKey.getDigest())
                          .setActionResult(result)
                          .build()));
    } catch (StatusRuntimeException e) {
      throw new IOException(e);
    }
  }
}
