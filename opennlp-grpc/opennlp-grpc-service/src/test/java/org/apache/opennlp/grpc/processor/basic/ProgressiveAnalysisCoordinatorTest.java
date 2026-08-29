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
package org.apache.opennlp.grpc.processor.basic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.opennlp.grpc.processor.ProgressiveAnalysisListener;
import org.apache.opennlp.grpc.v1.AnalysisLayerBatch;
import org.apache.opennlp.grpc.v1.AnalysisStarted;
import org.apache.opennlp.grpc.v1.AnalyzeDocumentRequest;
import org.apache.opennlp.grpc.v1.AnalyzeDocumentResponse;
import org.apache.opennlp.grpc.v1.CategoryChunkConfigEntry;
import org.apache.opennlp.grpc.v1.ChunkEmbedConfigEntry;
import org.apache.opennlp.grpc.v1.ChunkEmbeddingGroup;
import org.apache.opennlp.grpc.v1.OpenNlpDocument;
import org.apache.opennlp.grpc.v1.PipelineStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressiveAnalysisCoordinatorTest {

  @Test
  void independentBranchesRunAtTheSameTime() throws InterruptedException {
    final AnalyzeDocumentRequest request = AnalyzeDocumentRequest.newBuilder()
        .setDocument(OpenNlpDocument.newBuilder().setRawText("One short sentence.").build())
        .build();
    final AnalyzeDocumentResponse base;
    try (BasicDocumentAnalyzer analyzer = new BasicDocumentAnalyzer(Map.of())) {
      base = analyzer.analyze(request);
    }
    final CountDownLatch bothBranchesStarted = new CountDownLatch(2);
    final CountDownLatch terminal = new CountDownLatch(1);
    final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    final List<PipelineStep> finishedBranches =
        java.util.Collections.synchronizedList(new ArrayList<>());

    try (var executor = Executors.newFixedThreadPool(2)) {
      ProgressiveAnalysisCoordinator.start(
          request,
          EnumSet.of(
              PipelineStep.PIPELINE_STEP_SENTENCE_DETECT,
              PipelineStep.PIPELINE_STEP_TOKENIZE,
              PipelineStep.PIPELINE_STEP_DOC_CATEGORIZE,
              PipelineStep.PIPELINE_STEP_PARSE),
          executor,
          null,
          new ProgressiveAnalysisListener() {
            @Override
            public void onStarted(AnalysisStarted started) {
            }

            @Override
            public void onLayersReady(AnalysisLayerBatch layers) {
              if (layers.getStep() == PipelineStep.PIPELINE_STEP_DOC_CATEGORIZE
                  || layers.getStep() == PipelineStep.PIPELINE_STEP_PARSE) {
                finishedBranches.add(layers.getStep());
              }
            }

            @Override
            public void onStepFailed(PipelineStep step, RuntimeException branchFailure) {
              failure.set(branchFailure);
            }

            @Override
            public void onComplete(AnalyzeDocumentResponse response) {
              terminal.countDown();
            }

            @Override
            public void onError(RuntimeException terminalFailure) {
              failure.set(terminalFailure);
              terminal.countDown();
            }

            @Override
            public boolean isCancelled() {
              return false;
            }
          },
          (branchRequest, steps) -> {
            if (steps.contains(PipelineStep.PIPELINE_STEP_DOC_CATEGORIZE)
                || steps.contains(PipelineStep.PIPELINE_STEP_PARSE)) {
              bothBranchesStarted.countDown();
              try {
                if (!bothBranchesStarted.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException(
                      "independent branch did not start concurrently");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("branch wait was interrupted", e);
              }
            }
            return base;
          });

      assertTrue(terminal.await(10, TimeUnit.SECONDS));
    }

    assertNull(failure.get());
    assertTrue(finishedBranches.contains(PipelineStep.PIPELINE_STEP_DOC_CATEGORIZE));
    assertTrue(finishedBranches.contains(PipelineStep.PIPELINE_STEP_PARSE));
  }

  @Test
  void admitsAtMostFourHeavyBranchesAtOnce() throws InterruptedException {
    final AnalyzeDocumentRequest request = AnalyzeDocumentRequest.newBuilder()
        .setDocument(OpenNlpDocument.newBuilder().setRawText("One short sentence.").build())
        .build();
    final AnalyzeDocumentResponse base;
    try (BasicDocumentAnalyzer analyzer = new BasicDocumentAnalyzer(Map.of())) {
      base = analyzer.analyze(request);
    }
    final CountDownLatch firstWindowStarted = new CountDownLatch(4);
    final CountDownLatch fifthBranchStarted = new CountDownLatch(1);
    final CountDownLatch releaseBranches = new CountDownLatch(1);
    final CountDownLatch terminal = new CountDownLatch(1);
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maximumActive = new AtomicInteger();
    final AtomicInteger started = new AtomicInteger();
    final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    try (var executor = Executors.newFixedThreadPool(8)) {
      ProgressiveAnalysisCoordinator.start(
          request,
          EnumSet.of(
              PipelineStep.PIPELINE_STEP_SENTENCE_DETECT,
              PipelineStep.PIPELINE_STEP_TOKENIZE,
              PipelineStep.PIPELINE_STEP_SUBWORD_TOKENIZE,
              PipelineStep.PIPELINE_STEP_NER,
              PipelineStep.PIPELINE_STEP_POS_TAG,
              PipelineStep.PIPELINE_STEP_STEM,
              PipelineStep.PIPELINE_STEP_DOC_CATEGORIZE,
              PipelineStep.PIPELINE_STEP_SENTIMENT),
          executor,
          null,
          listener(terminal, failure, new ArrayList<>()),
          (branchRequest, steps) -> {
            if (isBackbone(steps)) {
              return base;
            }
            if (started.incrementAndGet() == 5) {
              fifthBranchStarted.countDown();
            }
            final int nowActive = active.incrementAndGet();
            maximumActive.accumulateAndGet(nowActive, Math::max);
            firstWindowStarted.countDown();
            try {
              if (!releaseBranches.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("branch admission window was not released");
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("branch wait was interrupted", e);
            } finally {
              active.decrementAndGet();
            }
            return base;
          });

      assertTrue(firstWindowStarted.await(5, TimeUnit.SECONDS));
      assertFalse(fifthBranchStarted.await(250, TimeUnit.MILLISECONDS));
      assertEquals(4, started.get());
      releaseBranches.countDown();
      assertTrue(terminal.await(10, TimeUnit.SECONDS));
    } finally {
      releaseBranches.countDown();
    }

    assertNull(failure.get());
    assertEquals(4, maximumActive.get());
  }

  @Test
  void chunkLayerUpdatesIncludeGroupsFromEveryCompletedBranch() throws InterruptedException {
    final AnalyzeDocumentRequest request = AnalyzeDocumentRequest.newBuilder()
        .setDocument(OpenNlpDocument.newBuilder().setRawText("One short sentence.").build())
        .addChunkEmbedConfigs(ChunkEmbedConfigEntry.newBuilder().setConfigId("sentences"))
        .addCategoryChunkConfigs(CategoryChunkConfigEntry.newBuilder().setConfigId("sentiment"))
        .build();
    final AnalyzeDocumentResponse base;
    try (BasicDocumentAnalyzer analyzer = new BasicDocumentAnalyzer(Map.of())) {
      base = analyzer.analyze(AnalyzeDocumentRequest.newBuilder()
          .setDocument(request.getDocument())
          .build());
    }
    final CountDownLatch terminal = new CountDownLatch(1);
    final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    final List<AnalysisLayerBatch> batches =
        java.util.Collections.synchronizedList(new ArrayList<>());

    try (var executor = Executors.newFixedThreadPool(2)) {
      ProgressiveAnalysisCoordinator.start(
          request,
          EnumSet.of(
              PipelineStep.PIPELINE_STEP_SENTENCE_DETECT,
              PipelineStep.PIPELINE_STEP_TOKENIZE,
              PipelineStep.PIPELINE_STEP_SENTIMENT,
              PipelineStep.PIPELINE_STEP_CHUNK),
          executor,
          null,
          listener(terminal, failure, batches),
          (branchRequest, steps) -> {
            if (branchRequest.getCategoryChunkConfigsCount() > 0) {
              return responseWithChunkGroup(base, "sentiment");
            }
            if (branchRequest.getChunkEmbedConfigsCount() > 0) {
              return responseWithChunkGroup(base, "sentences");
            }
            return base;
          });

      assertTrue(terminal.await(10, TimeUnit.SECONDS));
    }

    assertNull(failure.get());
    final List<AnalysisLayerBatch> chunkBatches = batches.stream()
        .filter(batch -> batch.getLayersList().stream()
            .anyMatch(layer -> layer.getId().equals("opennlp:chunk-groups")))
        .toList();
    assertEquals(2, chunkBatches.size());
    assertEquals(2, chunkBatches.get(1).getLayersList().stream()
        .filter(layer -> layer.getId().equals("opennlp:chunk-groups"))
        .findFirst()
        .orElseThrow()
        .getChunkGroupValues()
        .getAnnotationsCount());
  }

  private static boolean isBackbone(java.util.Set<PipelineStep> steps) {
    return steps.stream().allMatch(step -> step == PipelineStep.PIPELINE_STEP_SENTENCE_DETECT
        || step == PipelineStep.PIPELINE_STEP_TOKENIZE);
  }

  private static AnalyzeDocumentResponse responseWithChunkGroup(
      AnalyzeDocumentResponse base, String groupId) {
    final OpenNlpDocument.Builder document = base.getDocument().toBuilder()
        .clearLayers()
        .clearChunkEmbeddingGroups()
        .addChunkEmbeddingGroups(ChunkEmbeddingGroup.newBuilder().setGroupId(groupId));
    DocumentShapeAssembler.apply(document, document.getRawText());
    return AnalyzeDocumentResponse.newBuilder().setDocument(document).build();
  }

  private static ProgressiveAnalysisListener listener(
      CountDownLatch terminal,
      AtomicReference<RuntimeException> failure,
      List<AnalysisLayerBatch> batches) {
    return new ProgressiveAnalysisListener() {
      @Override
      public void onStarted(AnalysisStarted started) {
      }

      @Override
      public void onLayersReady(AnalysisLayerBatch layers) {
        batches.add(layers);
      }

      @Override
      public void onStepFailed(PipelineStep step, RuntimeException branchFailure) {
        failure.set(branchFailure);
      }

      @Override
      public void onComplete(AnalyzeDocumentResponse response) {
        terminal.countDown();
      }

      @Override
      public void onError(RuntimeException terminalFailure) {
        failure.set(terminalFailure);
        terminal.countDown();
      }

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }
}
