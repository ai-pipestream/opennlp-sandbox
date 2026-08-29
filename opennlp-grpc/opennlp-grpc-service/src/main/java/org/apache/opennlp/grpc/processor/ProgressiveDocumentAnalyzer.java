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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.opennlp.grpc.processor;

import java.util.concurrent.Executor;

import org.apache.opennlp.grpc.v1.AnalyzeDocumentRequest;

/** An analyzer that can publish independent pipeline results as they finish. */
public interface ProgressiveDocumentAnalyzer extends DocumentAnalyzer {

  /**
   * Starts one progressive analysis. CPU-bound branches run on {@code branchExecutor};
   * listener callbacks are serialized by the implementation.
   *
   * @param request The document and analysis configuration.
   * @param branchExecutor The bounded executor used for independent branches.
   * @param listener The recipient of ordered analysis updates.
   */
  void analyzeProgressively(
      AnalyzeDocumentRequest request,
      Executor branchExecutor,
      ProgressiveAnalysisListener listener);
}
