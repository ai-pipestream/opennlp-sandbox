/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.apache.opennlp.grpc.webapp;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.opennlp.grpc.v1.AnalyzeDocumentRequest;
import org.apache.opennlp.grpc.v1.AnalyzeDocumentResponse;
import org.apache.opennlp.grpc.v1.AnalyzeStreamRequest;
import org.apache.opennlp.grpc.v1.AnalyzeStreamResponse;
import org.apache.opennlp.grpc.v1.GetServiceInfoRequest;
import org.apache.opennlp.grpc.v1.GetServiceInfoResponse;
import org.apache.opennlp.grpc.v1.ListModelBundlesRequest;
import org.apache.opennlp.grpc.v1.ListModelBundlesResponse;
import org.apache.opennlp.grpc.v1.OpenNlpAnalysisServiceGrpc;

final class GrpcAnalysisRpc implements AnalysisRpc {

  /** Marks the ordered end of a response stream in the transfer queue. */
  private static final Object STREAM_COMPLETE = new Object();

  private final OpenNlpAnalysisServiceGrpc.OpenNlpAnalysisServiceBlockingStub stub;
  private final OpenNlpAnalysisServiceGrpc.OpenNlpAnalysisServiceStub asyncStub;
  private final long timeoutNanos;
  private final long streamTimeoutNanos;

  /**
   * Creates a blocking gRPC adapter.
   *
   * @param channel The channel to the OpenNLP service.
   * @param timeout The deadline applied to every unary call.
   * @param streamTimeout The deadline applied to a whole batch AnalyzeStream call.
   * @throws IllegalArgumentException If an argument is {@code null} or a timeout is not positive.
   */
  GrpcAnalysisRpc(Channel channel, Duration timeout, Duration streamTimeout) {
    if (channel == null) {
      throw new IllegalArgumentException("channel must not be null");
    }
    if (timeout == null) {
      throw new IllegalArgumentException("timeout must not be null");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (streamTimeout == null) {
      throw new IllegalArgumentException("streamTimeout must not be null");
    }
    if (streamTimeout.isZero() || streamTimeout.isNegative()) {
      throw new IllegalArgumentException("streamTimeout must be positive");
    }
    this.stub = OpenNlpAnalysisServiceGrpc.newBlockingStub(channel);
    this.asyncStub = OpenNlpAnalysisServiceGrpc.newStub(channel);
    this.timeoutNanos = timeout.toNanos();
    this.streamTimeoutNanos = streamTimeout.toNanos();
  }

  /** {@inheritDoc} */
  @Override
  public GetServiceInfoResponse getServiceInfo() {
    return deadlineStub().getServiceInfo(GetServiceInfoRequest.getDefaultInstance());
  }

  /** {@inheritDoc} */
  @Override
  public ListModelBundlesResponse listModelBundles() {
    return deadlineStub().listModelBundles(ListModelBundlesRequest.getDefaultInstance());
  }

  /** {@inheritDoc} */
  @Override
  public AnalyzeDocumentResponse analyze(AnalyzeDocumentRequest request) {
    return deadlineStub().analyzeDocument(request);
  }

  /** {@inheritDoc} */
  @Override
  public Iterator<AnalyzeStreamResponse> analyzeStream(List<AnalyzeStreamRequest> frames) {
    if (frames == null || frames.isEmpty()) {
      throw new IllegalArgumentException("frames must not be null or empty");
    }
    final BlockingQueue<Object> transfer = new LinkedBlockingQueue<>();
    final StreamObserver<AnalyzeStreamRequest> requests = asyncStub
        .withDeadlineAfter(streamTimeoutNanos, TimeUnit.NANOSECONDS)
        .analyzeStream(new StreamObserver<>() {
          @Override
          public void onNext(AnalyzeStreamResponse response) {
            transfer.add(response);
          }

          @Override
          public void onError(Throwable failure) {
            transfer.add(failure);
          }

          @Override
          public void onCompleted() {
            transfer.add(STREAM_COMPLETE);
          }
        });
    for (AnalyzeStreamRequest frame : frames) {
      requests.onNext(frame);
    }
    requests.onCompleted();
    return new Iterator<>() {
      private Object pending;

      @Override
      public boolean hasNext() {
        if (pending == null) {
          try {
            pending = transfer.take();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED.withDescription("interrupted while streaming")
                .asRuntimeException();
          }
        }
        if (pending instanceof Throwable failure) {
          throw Status.fromThrowable(failure).asRuntimeException();
        }
        return pending != STREAM_COMPLETE;
      }

      @Override
      public AnalyzeStreamResponse next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        final AnalyzeStreamResponse response = (AnalyzeStreamResponse) pending;
        pending = null;
        return response;
      }
    };
  }

  /** @return A stub carrying the configured deadline. */
  private OpenNlpAnalysisServiceGrpc.OpenNlpAnalysisServiceBlockingStub deadlineStub() {
    return stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS);
  }
}
