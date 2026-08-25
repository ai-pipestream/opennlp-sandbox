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

import java.util.Iterator;
import java.util.List;

import org.apache.opennlp.grpc.v1.AnalyzeDocumentRequest;
import org.apache.opennlp.grpc.v1.AnalyzeDocumentResponse;
import org.apache.opennlp.grpc.v1.AnalyzeStreamRequest;
import org.apache.opennlp.grpc.v1.AnalyzeStreamResponse;
import org.apache.opennlp.grpc.v1.GetServiceInfoResponse;
import org.apache.opennlp.grpc.v1.ListModelBundlesResponse;

interface AnalysisRpc {

  /** @return Current service metadata. */
  GetServiceInfoResponse getServiceInfo();

  /** @return Configured model bundles. */
  ListModelBundlesResponse listModelBundles();

  /**
   * Analyzes one document.
   *
   * @param request The analysis request.
   * @return The analysis response.
   */
  AnalyzeDocumentResponse analyze(AnalyzeDocumentRequest request);

  /**
   * Analyzes a batch of documents over one AnalyzeStream call: every frame is sent,
   * the client half closes, and the completion-ordered responses stream back.
   *
   * @param frames The complete frame sequence: one configuration frame first, then
   *     one frame per document. Must not be {@code null} or empty.
   * @return The completion-ordered responses; iteration blocks on the stream.
   */
  Iterator<AnalyzeStreamResponse> analyzeStream(List<AnalyzeStreamRequest> frames);
}
