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
package org.apache.opennlp.grpc.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.util.Span;
import org.apache.opennlp.grpc.v1.AnnotatedSentence;
import org.apache.opennlp.grpc.v1.AnnotationSpan;
import org.apache.opennlp.grpc.v1.CoordinateSpace;
import org.apache.opennlp.grpc.v1.NamedEntity;
import org.apache.opennlp.grpc.v1.Token;
import org.apache.opennlp.grpc.spi.model.NerModel;
import org.apache.opennlp.grpc.spi.model.NerSpans;

/**
 * {@link NerModel} backed by a classic OpenNLP {@link NameFinderME}. Each instance serves
 * exactly one entity type. {@code find} returns token-index spans, which this model maps
 * to document character offsets using the tokens' own spans.
 */
final class ClassicNerModel implements NerModel {

  /** Backend id reported for models served by the classic OpenNLP maxent runtime. */
  static final String BACKEND_ID = "opennlp-me";

  private final String entityType;
  private final NameFinderME nameFinder;
  private final int priority;
  private final String artifactHash;

  /**
   * Creates a classic name-finder registration.
   *
   * @param entityType The logical entity type and model id.
   * @param nameFinder The initialized OpenNLP name finder.
   * @param priority The selection priority among engines serving {@code entityType}.
   * @param artifactHash The model artifact hash, or blank when unavailable.
   */
  ClassicNerModel(String entityType, NameFinderME nameFinder, int priority, String artifactHash) {
    if (entityType == null) {
      throw new IllegalArgumentException("entityType must not be null");
    }
    this.entityType = entityType;
    if (nameFinder == null) {
      throw new IllegalArgumentException("nameFinder must not be null");
    }
    this.nameFinder = nameFinder;
    this.priority = priority;
    this.artifactHash = artifactHash == null ? "" : artifactHash;
  }

  /** {@inheritDoc} */
  @Override
  public String id() {
    return entityType;
  }

  /** {@inheritDoc} */
  @Override
  public String backendId() {
    return BACKEND_ID;
  }

  /** {@inheritDoc} */
  @Override
  public String artifactHash() {
    return artifactHash;
  }

  /** {@inheritDoc} */
  @Override
  public int priority() {
    return priority;
  }

  /** {@inheritDoc} */
  @Override
  public Set<String> entityTypes() {
    return Set.of(entityType);
  }

  /** {@inheritDoc} */
  @Override
  public boolean isStateful() {
    return true;
  }

  /** {@inheritDoc} */
  @Override
  public void clearAdaptiveData() {
    nameFinder.clearAdaptiveData();
  }

  /** {@inheritDoc} */
  @Override
  public void clearThreadLocalState() {
    nameFinder.clearThreadLocalState();
  }

  /** {@inheritDoc} */
  @Override
  public List<NamedEntity> recognize(AnnotatedSentence sentence, boolean includeProbabilities) {
    if (sentence.getTokensCount() == 0) {
      return List.of();
    }
    final String[] tokens = NerSpans.tokenTexts(sentence);
    final Span[] spans = nameFinder.find(tokens);
    final double[] probabilities = includeProbabilities ? nameFinder.probs(spans) : null;
    final List<NamedEntity> entities = new ArrayList<>(spans.length);
    for (int e = 0; e < spans.length; e++) {
      final NamedEntity.Builder entity = NamedEntity.newBuilder()
          .setAnnotationSpan(NerSpans.tokenSpanToDocumentSpan(sentence, spans[e]))
          .setEntityType(NerSpans.resolveEntityType(entityType, spans[e]));
      if (probabilities != null && e < probabilities.length) {
        entity.setProbability(probabilities[e]);
      }
      entities.add(entity.build());
    }
    return entities;
  }
}
