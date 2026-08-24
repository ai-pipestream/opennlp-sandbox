/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.opennlp.grpc.training;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.opennlp.grpc.v1.ModelArtifactRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardModelCatalogTest {

  @Test
  void catalogsPinnedEmbeddingParserAndChunkerModels() {
    final List<CatalogModel> models = StandardModelCatalog.models();

    assertEquals(List.of(
        "all-minilm-l6-v2-teacher",
        "de-ud-gsd-lemmas",
        "de-ud-gsd-pos",
        "de-ud-gsd-sentence",
        "de-ud-gsd-tokens",
        "es-ud-gsd-lemmas",
        "es-ud-gsd-pos",
        "es-ud-gsd-sentence",
        "es-ud-gsd-tokens",
        "fr-ud-gsd-lemmas",
        "fr-ud-gsd-pos",
        "fr-ud-gsd-sentence",
        "fr-ud-gsd-tokens",
        "gum-cc-by-4-chunker",
        "gum-cc-by-4-parser",
        "paraphrase-multilingual-minilm-l12-v2-teacher",
        "potion-base-8m",
        "potion-multilingual-128m",
        "potion-retrieval-32m"),
        models.stream().map(model -> model.descriptor().getCatalogId()).toList());
    final Map<String, ModelArtifactRole> roles = models.stream().collect(
        Collectors.toMap(
            model -> model.descriptor().getCatalogId(),
            model -> model.descriptor().getRole()));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_DISTILLATION_TEACHER,
        roles.get("all-minilm-l6-v2-teacher"));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_DISTILLATION_TEACHER,
        roles.get("paraphrase-multilingual-minilm-l12-v2-teacher"));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_CHUNKER,
        roles.get("gum-cc-by-4-chunker"));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_PARSER,
        roles.get("gum-cc-by-4-parser"));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_SENTENCE_DETECTOR,
        roles.get("de-ud-gsd-sentence"));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_TOKENIZER,
        roles.get("fr-ud-gsd-tokens"));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_POS_TAGGER,
        roles.get("es-ud-gsd-pos"));
    assertEquals(ModelArtifactRole.MODEL_ARTIFACT_ROLE_LEMMATIZER,
        roles.get("de-ud-gsd-lemmas"));
    assertTrue(models.stream()
        .filter(model -> model.descriptor().getCatalogId().startsWith("potion-"))
        .allMatch(model -> model.descriptor().getRole()
            == ModelArtifactRole.MODEL_ARTIFACT_ROLE_STATIC_EMBEDDING));
    assertEquals(256, dimensionOf(models, "potion-base-8m"));
    assertEquals(256, dimensionOf(models, "potion-multilingual-128m"));
    assertEquals(512, dimensionOf(models, "potion-retrieval-32m"));
  }

  /** Returns the declared dimension of one catalog entry. */
  private static int dimensionOf(List<CatalogModel> models, String catalogId) {
    return models.stream()
        .filter(model -> catalogId.equals(model.descriptor().getCatalogId()))
        .findFirst().orElseThrow().descriptor().getDimension();
  }

  @Test
  void everyCatalogFileHasAnExactSizeAndSha256() {
    for (CatalogModel model : StandardModelCatalog.models()) {
      long total = 0;
      for (CatalogFile file : model.files()) {
        assertTrue(file.relativePath().getNameCount() <= 2);
        assertTrue(file.byteSize() > 0);
        assertEquals(64, file.sha256().length());
        total += file.byteSize();
      }
      assertEquals(total, model.descriptor().getByteSize());
    }
  }
}
