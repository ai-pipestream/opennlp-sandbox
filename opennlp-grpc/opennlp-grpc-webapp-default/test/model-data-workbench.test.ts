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

/** @vitest-environment jsdom */

import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  groupCatalogPacks,
  languageDisplayName,
  ModelDataWorkbench,
  readInstalledModels,
  readModelCatalog,
  type ModelCatalogApi,
  type ModelCatalogSummary,
} from "../src/model-data-workbench";

const STATIC_MODEL = {
  catalogId: "potion-base-8m",
  displayName: "Potion Base 8M",
  role: "static" as const,
  modelId: "potion-base-8m",
  sourceUri: "https://huggingface.co/minishlab/potion-base-8M",
  revision: "revision-1",
  licenseName: "MIT",
  licenseUri: "https://opensource.org/license/mit",
  byteSize: 30_236_760,
  dimension: 256,
  languages: ["en"],
  description: "Ready-to-serve static embeddings.",
};

function germanPackModel(
  role: ModelCatalogSummary["role"],
  suffix: string,
): ModelCatalogSummary {
  return {
    ...STATIC_MODEL,
    catalogId: `de-ud-gsd-${suffix}`,
    displayName: `German UD ${suffix}`,
    role,
    modelId: "de-ud-gsd",
    licenseName: "Apache-2.0",
    licenseUri: "https://www.apache.org/licenses/LICENSE-2.0",
    byteSize: 1_048_576,
    dimension: 0,
    languages: ["de"],
    description: `German ${suffix} model.`,
  };
}

const GERMAN_PACK: ModelCatalogSummary[] = [
  germanPackModel("sentence-detector", "sentence"),
  germanPackModel("tokenizer", "tokens"),
  germanPackModel("pos-tagger", "pos"),
  germanPackModel("lemmatizer", "lemmas"),
];

describe("model catalog readers", () => {
  it("reads every first-class catalog artifact role", () => {
    const result = readModelCatalog({ installsEnabled: true, models: [
      {
        catalogId: "teacher", displayName: "MiniLM", modelId: "mini",
        role: "MODEL_ARTIFACT_ROLE_DISTILLATION_TEACHER", byteSize: "10",
        sourceUri: "https://example.test/teacher", revision: "teacher-revision",
        licenseName: "Apache-2.0", licenseUri: "https://www.apache.org/licenses/LICENSE-2.0",
      },
      {
        catalogId: "static", displayName: "Potion", modelId: "potion",
        role: "MODEL_ARTIFACT_ROLE_STATIC_EMBEDDING", byteSize: "20", dimension: 256,
        sourceUri: "https://example.test/static", revision: "static-revision",
        licenseName: "MIT", licenseUri: "https://opensource.org/license/mit",
      },
      {
        catalogId: "parser", displayName: "GUM parser", modelId: "gum",
        role: "MODEL_ARTIFACT_ROLE_PARSER", byteSize: "30",
        sourceUri: "https://example.test/parser", revision: "parser-revision",
        licenseName: "CC-BY-4.0", licenseUri: "https://example.test/parser-license",
      },
      {
        catalogId: "chunker", displayName: "GUM chunker", modelId: "gum",
        role: "MODEL_ARTIFACT_ROLE_CHUNKER", byteSize: "40",
        sourceUri: "https://example.test/chunker", revision: "chunker-revision",
        licenseName: "CC-BY-4.0", licenseUri: "https://example.test/chunker-license",
      },
      {
        catalogId: "de-sentence", displayName: "German UD sentence detector", modelId: "de-ud",
        role: "MODEL_ARTIFACT_ROLE_SENTENCE_DETECTOR", byteSize: "50",
        sourceUri: "https://example.test/de", revision: "ud-revision",
        licenseName: "Apache-2.0", licenseUri: "https://www.apache.org/licenses/LICENSE-2.0",
      },
      {
        catalogId: "de-tokens", displayName: "German UD tokenizer", modelId: "de-ud",
        role: "MODEL_ARTIFACT_ROLE_TOKENIZER", byteSize: "51",
        sourceUri: "https://example.test/de", revision: "ud-revision",
        licenseName: "Apache-2.0", licenseUri: "https://www.apache.org/licenses/LICENSE-2.0",
      },
      {
        catalogId: "de-pos", displayName: "German UD POS tagger", modelId: "de-ud",
        role: "MODEL_ARTIFACT_ROLE_POS_TAGGER", byteSize: "52",
        sourceUri: "https://example.test/de", revision: "ud-revision",
        licenseName: "Apache-2.0", licenseUri: "https://www.apache.org/licenses/LICENSE-2.0",
      },
      {
        catalogId: "de-lemmas", displayName: "German UD lemmatizer", modelId: "de-ud",
        role: "MODEL_ARTIFACT_ROLE_LEMMATIZER", byteSize: "53",
        sourceUri: "https://example.test/de", revision: "ud-revision",
        licenseName: "Apache-2.0", licenseUri: "https://www.apache.org/licenses/LICENSE-2.0",
      },
    ] });

    expect(result.installsEnabled).toBe(true);
    expect(result.models.map((model) => model.role))
      .toEqual(["teacher", "static", "parser", "chunker",
        "sentence-detector", "tokenizer", "pos-tagger", "lemmatizer"]);
    expect(result.models[1]?.dimension).toBe(256);
    expect(readInstalledModels({ models: [{
      catalog: { catalogId: "static" }, artifactHash: "abc", byteSize: "20", loaded: true,
      installedAt: "2026-08-21T20:00:00Z",
    }] })).toEqual([{ catalogId: "static", artifactHash: "abc", byteSize: 20,
      installedAt: "2026-08-21T20:00:00Z", loaded: true }]);
  });

  it("rejects catalog cards that cannot safely support informed consent", () => {
    expect(() => readModelCatalog({ models: [{
      catalogId: "unsafe", modelId: "unsafe",
      role: "MODEL_ARTIFACT_ROLE_STATIC_EMBEDDING",
      sourceUri: "javascript:alert(1)", revision: "r", licenseName: "MIT",
      licenseUri: "https://opensource.org/license/mit",
    }] })).toThrow(/HTTPS/);
    expect(() => readModelCatalog({ models: [{
      catalogId: "moving", modelId: "moving",
      role: "MODEL_ARTIFACT_ROLE_STATIC_EMBEDDING",
      sourceUri: "https://example.test/model", licenseName: "MIT",
      licenseUri: "https://opensource.org/license/mit",
    }] })).toThrow(/revision/);
  });
});

describe("catalog language packs", () => {
  it("groups the four pipeline roles sharing one model id into a language pack", () => {
    const { packs, singles } = groupCatalogPacks([STATIC_MODEL, ...GERMAN_PACK]);

    expect(packs).toHaveLength(1);
    expect(packs[0]).toMatchObject({
      modelId: "de-ud-gsd",
      language: "de",
      licenseName: "Apache-2.0",
      byteSize: 4 * 1_048_576,
    });
    expect(packs[0]!.models.map((model) => model.role))
      .toEqual(["sentence-detector", "tokenizer", "pos-tagger", "lemmatizer"]);
    expect(singles).toEqual([STATIC_MODEL]);
  });

  it("keeps an incomplete pipeline group as single cards", () => {
    const { packs, singles } = groupCatalogPacks(GERMAN_PACK.slice(0, 3));

    expect(packs).toHaveLength(0);
    expect(singles).toHaveLength(3);
  });

  it("names languages for people and falls back to the raw code", () => {
    expect(languageDisplayName("de")).toBe("German");
    expect(languageDisplayName("")).toBe("Unknown language");
  });
});

describe("model catalog workbench", () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <strong id="resource-summary"></strong>
      <div id="resource-feature-list"></div>
      <ul id="resource-bundle-list"></ul>
      <pre id="resource-install-command"></pre>
      <button id="copy-resource-command"></button>
      <p id="resource-install-status"></p>
      <div id="resource-model-catalog"></div>
      <div id="resource-installed-models"></div>`;
  });

  function api(): ModelCatalogApi {
    return {
      listCatalog: vi.fn(async () => ({ models: [STATIC_MODEL], installsEnabled: true })),
      listInstalled: vi.fn(async () => []),
      install: vi.fn(async (_request, onProgress) => {
        onProgress({ stage: "downloading", message: "Downloading config.json",
          completedBytes: 10, totalBytes: STATIC_MODEL.byteSize });
        return { catalogId: STATIC_MODEL.catalogId, artifactHash: "abc",
          byteSize: STATIC_MODEL.byteSize, installedAt: "now", loaded: true };
      }),
    };
  }

  it("requires license acknowledgement before installing and activates static models", async () => {
    const service = api();
    const installed = vi.fn();
    const workbench = new ModelDataWorkbench(service, {
      onEmbeddingModelInstalled: installed,
      onTeacherInstalled: vi.fn(),
    });
    await workbench.initialize();

    const button = document.querySelector<HTMLButtonElement>("[data-catalog-install]")!;
    const consent = document.querySelector<HTMLInputElement>("[data-catalog-consent]")!;
    expect(button.disabled).toBe(true);
    expect(document.getElementById("resource-model-catalog")?.textContent)
      .toContain("Ready-to-serve static embeddings");
    expect(Array.from(document.querySelectorAll<HTMLAnchorElement>(".catalog-model-card a"))
      .map((link) => link.href)).toContain(STATIC_MODEL.licenseUri);

    consent.click();
    expect(button.disabled).toBe(false);
    button.click();
    await vi.waitFor(() => expect(service.install).toHaveBeenCalled());

    expect(service.install).toHaveBeenCalledWith({
      catalogId: STATIC_MODEL.catalogId,
      revision: STATIC_MODEL.revision,
      licenseName: STATIC_MODEL.licenseName,
      licenseAcknowledged: true,
    }, expect.any(Function));
    expect(installed).toHaveBeenCalledWith(STATIC_MODEL.modelId, STATIC_MODEL.displayName);
    expect(document.getElementById("resource-install-status")?.textContent)
      .toContain("installed and active");
  });

  it("publishes static models restored from the node inventory", async () => {
    const service = api();
    service.listInstalled = vi.fn(async () => [{
      catalogId: STATIC_MODEL.catalogId,
      artifactHash: "abc",
      byteSize: STATIC_MODEL.byteSize,
      installedAt: "now",
      loaded: true,
    }]);
    const installed = vi.fn();
    const workbench = new ModelDataWorkbench(service, {
      onEmbeddingModelInstalled: installed,
      onTeacherInstalled: vi.fn(),
    });

    await workbench.initialize();

    expect(installed).toHaveBeenCalledWith(STATIC_MODEL.modelId, STATIC_MODEL.displayName);
  });

  it("explains that a newly installed parser needs a server restart", async () => {
    const parser = {
      ...STATIC_MODEL,
      catalogId: "gum-parser",
      displayName: "GUM parser",
      role: "parser" as const,
      modelId: "gum",
      dimension: 0,
      licenseName: "CC-BY-4.0",
    };
    const service = api();
    service.listCatalog = vi.fn(async () => ({ models: [parser], installsEnabled: true }));
    service.install = vi.fn(async () => ({
      catalogId: parser.catalogId,
      artifactHash: "abc",
      byteSize: parser.byteSize,
      installedAt: "now",
      loaded: false,
    }));
    const workbench = new ModelDataWorkbench(service, {
      onEmbeddingModelInstalled: vi.fn(),
      onTeacherInstalled: vi.fn(),
    });
    await workbench.initialize();

    document.querySelector<HTMLInputElement>("[data-catalog-consent]")!.click();
    document.querySelector<HTMLButtonElement>("[data-catalog-install]")!.click();
    await vi.waitFor(() => expect(service.install).toHaveBeenCalled());

    expect(document.getElementById("resource-install-status")?.textContent)
      .toContain("restart required");
  });

  it("renders the verified downloaded-model inventory", async () => {
    const service = api();
    service.listInstalled = vi.fn(async () => [{
      catalogId: STATIC_MODEL.catalogId,
      artifactHash: "a4b3ea50e20ed3fac7c841c2953b8596b00321125ec085e81bbe1a6e737642a7",
      byteSize: STATIC_MODEL.byteSize,
      installedAt: "2026-08-21T20:00:00Z",
      loaded: true,
    }]);
    const workbench = new ModelDataWorkbench(service, {
      onEmbeddingModelInstalled: vi.fn(),
      onTeacherInstalled: vi.fn(),
    });

    await workbench.initialize();

    const inventory = document.getElementById("resource-installed-models")!;
    expect(inventory.textContent).toContain(STATIC_MODEL.displayName);
    expect(inventory.textContent).toContain("Installed and active");
    expect(inventory.textContent).toContain("28.8 MiB");
    expect(inventory.textContent).toContain("installed 2026-08-21 20:00 UTC");
    expect(inventory.textContent).toContain("a4b3ea50e20ed3f");
  });

  it("installs a whole language pack behind one license review", async () => {
    const service = api();
    service.listCatalog = vi.fn(async () => ({
      models: [STATIC_MODEL, ...GERMAN_PACK],
      installsEnabled: true,
    }));
    service.install = vi.fn(async (request) => ({
      catalogId: request.catalogId,
      artifactHash: "abc",
      byteSize: 1_048_576,
      installedAt: "now",
      loaded: false,
    }));
    const workbench = new ModelDataWorkbench(service, {
      onEmbeddingModelInstalled: vi.fn(),
      onTeacherInstalled: vi.fn(),
    });
    await workbench.initialize();

    const card = document.querySelector<HTMLElement>(".catalog-pack-card")!;
    expect(card.textContent).toContain("German language pack");
    expect(card.querySelectorAll(".catalog-pack-members li")).toHaveLength(4);
    const button = card.querySelector<HTMLButtonElement>("[data-pack-install]")!;
    expect(button.textContent).toBe("Install all four models");
    expect(button.disabled).toBe(true);

    card.querySelector<HTMLInputElement>("[data-pack-consent]")!.click();
    expect(button.disabled).toBe(false);
    button.click();
    await vi.waitFor(() => expect(service.install).toHaveBeenCalledTimes(4));

    expect(vi.mocked(service.install).mock.calls.map(([request]) => request.catalogId))
      .toEqual(["de-ud-gsd-sentence", "de-ud-gsd-tokens", "de-ud-gsd-pos", "de-ud-gsd-lemmas"]);
    expect(vi.mocked(service.install).mock.calls.every(
      ([request]) => request.licenseAcknowledged)).toBe(true);
    expect(document.getElementById("resource-install-status")?.textContent)
      .toContain("restart the server to activate the 'de' pipeline");
  });
});
