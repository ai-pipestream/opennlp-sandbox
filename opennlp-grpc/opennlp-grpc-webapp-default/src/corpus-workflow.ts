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

import type {
  AnalyzeRequest,
  IndexDocumentsRequest,
  LearnVocabularyUpload,
  TrainStaticModelRequest,
} from "./api";
import {
  layerAccent,
  readDocumentShape,
  summarizeDocumentShape,
  type DocumentShapeView,
} from "./document-shape";
import { buildDocumentHeat, type HeatSegment } from "./search-heatmap";
import {
  createAllHitsSearchRequest,
  createSearchRequest,
  type SearchIndex,
  type SearchProviderInstance,
  type SearchRequest,
  type SearchResponse,
} from "./search-adapter";
import { matchedSegments, scoreColor } from "./search-view-model";
import { formatInteger, splitBlankLineDocuments } from "./text-utils";
import { errorMessage, requiredElement } from "./ui-utils";
import type {
  DictionaryArtifactSummary,
  TeacherOption,
  TrainedModelSummary,
} from "./vocabulary-trainer";

type WorkflowStage = "analyze" | "vocabulary" | "train" | "embed" | "index" | "search";
type WorkflowResultView = "analysis" | "search";

interface LearnedVocabularySummary {
  artifactId: string;
  displayName: string;
  termCount: number;
  dictionaryTermCount: number;
  corpusTermCount: number;
}

export interface CorpusWorkflowApi {
  listDictionaries(): Promise<DictionaryArtifactSummary[]>;
  listTeachers(): Promise<{ teachers: TeacherOption[]; writesEnabled: boolean }>;
  listProviders(): Promise<SearchProviderInstance[]>;
  analyze(request: AnalyzeRequest): Promise<unknown>;
  learnVocabulary(upload: LearnVocabularyUpload): Promise<LearnedVocabularySummary>;
  trainStaticModel(
    request: TrainStaticModelRequest,
    onProgress: (message: string) => void,
  ): Promise<TrainedModelSummary>;
  index(request: IndexDocumentsRequest): Promise<SearchIndex>;
  search(request: SearchRequest): Promise<SearchResponse>;
}

export interface CorpusWorkflowCallbacks {
  createAnalysisRequest(
    document: { docId: string; rawText: string },
    embeddingModelId?: string,
  ): AnalyzeRequest;
  onModelTrained(model: TrainedModelSummary): void;
  onOpenAnalysis(response: unknown, shape: DocumentShapeView): void;
  onIndexChanged(index: SearchIndex): void;
}

interface AnalyzedDocument {
  id: string;
  response: unknown;
  shape: DocumentShapeView;
}

const STAGES: WorkflowStage[] = ["analyze", "vocabulary", "train", "embed", "index", "search"];
const FLAT_FLOAT_PROVIDER = "STANDARD_SEARCH_PROVIDER_FLAT_FLOAT";

/** Runs the beginner-facing corpus, model, index, and search workflow. */
export class CorpusWorkflowWorkbench {
  readonly #api: CorpusWorkflowApi;
  readonly #callbacks: CorpusWorkflowCallbacks;
  readonly #status = requiredElement<HTMLElement>("workflow-status");
  readonly #corpus = requiredElement<HTMLTextAreaElement>("workflow-corpus");
  readonly #corpusStats = requiredElement<HTMLElement>("workflow-corpus-stats");
  readonly #name = requiredElement<HTMLInputElement>("workflow-name");
  readonly #dictionary = requiredElement<HTMLSelectElement>("workflow-dictionary-select");
  readonly #teacher = requiredElement<HTMLSelectElement>("workflow-teacher-select");
  readonly #provider = requiredElement<HTMLSelectElement>("workflow-provider-select");
  readonly #minFrequency = requiredElement<HTMLInputElement>("workflow-min-frequency");
  readonly #maxTerms = requiredElement<HTMLInputElement>("workflow-max-terms");
  readonly #pcaDims = requiredElement<HTMLInputElement>("workflow-pca-dims");
  readonly #query = requiredElement<HTMLInputElement>("workflow-query");
  readonly #runButton = requiredElement<HTMLButtonElement>("workflow-run-button");
  readonly #searchForm = requiredElement<HTMLFormElement>("workflow-search-form");
  readonly #searchButton = requiredElement<HTMLButtonElement>("workflow-search-button");
  readonly #artifacts = requiredElement<HTMLElement>("workflow-artifacts");
  readonly #analysisResults = requiredElement<HTMLElement>("workflow-analysis-results");
  readonly #searchHeatmap = requiredElement<HTMLElement>("workflow-search-heatmap");
  readonly #searchSelection = requiredElement<HTMLElement>("workflow-search-selection");
  readonly #analysisPanel = requiredElement<HTMLElement>("workflow-analysis-panel");
  readonly #searchPanel = requiredElement<HTMLElement>("workflow-search-panel");
  readonly #resultTabs = Array.from(
    document.querySelectorAll<HTMLButtonElement>("[data-workflow-result-tab]"));

  #ready = false;
  #busy = false;
  #index?: SearchIndex;
  #model?: TrainedModelSummary;
  #vocabulary?: LearnedVocabularySummary;
  #activeStage?: WorkflowStage;

  constructor(api: CorpusWorkflowApi, callbacks: CorpusWorkflowCallbacks) {
    this.#api = api;
    this.#callbacks = callbacks;
    this.#corpus.addEventListener("input", () => this.updateControls());
    this.#query.addEventListener("input", () => this.updateControls());
    this.#runButton.addEventListener("click", () => void this.run());
    this.#searchForm.addEventListener("submit", (event) => void this.searchAgain(event));
    for (const tab of this.#resultTabs) {
      tab.addEventListener("click", () => this.selectResultView(
        tab.dataset.workflowResultTab === "search" ? "search" : "analysis"));
    }
    this.updateControls();
  }

  /** Loads the selectable server resources used by the compact configuration drawer. */
  async initialize(): Promise<void> {
    try {
      const [dictionaries, teachers, providers] = await Promise.all([
        this.#api.listDictionaries().catch(() => []),
        this.#api.listTeachers(),
        this.#api.listProviders(),
      ]);
      this.populateDictionaries(dictionaries);
      this.populateTeachers(teachers.teachers);
      this.populateProviders(providers);
      this.#ready = teachers.writesEnabled && teachers.teachers.length > 0;
      this.setStatus(this.#ready
        ? "Ready. Paste text and keep the defaults, or open the configuration drawer."
        : "Training is unavailable because this server has no writable artifact root or teacher model.",
      !this.#ready);
    } catch (error) {
      this.#ready = false;
      this.setStatus(errorMessage(error, "Could not load workflow resources."), true);
    }
    this.updateControls();
  }

  private async run(): Promise<void> {
    if (this.#busy || !this.#ready) {
      return;
    }
    const documents = workflowDocuments(this.#corpus.value);
    const query = this.#query.value.trim();
    if (documents.length === 0 || !query) {
      this.setStatus("Add at least one document and a first search query.", true);
      return;
    }
    this.#busy = true;
    this.#index = undefined;
    this.#model = undefined;
    this.#vocabulary = undefined;
    this.resetStages();
    this.updateControls();
    const displayName = this.#name.value.trim() || "Text workflow";
    try {
      this.activate("analyze", `Analyzing ${documents.length} ${plural(documents.length, "document")}`);
      const analyzed = await this.analyzeDocuments(documents);
      this.complete("analyze", `${documents.length} ${plural(documents.length, "document")} analyzed`);
      this.renderAnalysis(analyzed);

      this.activate("vocabulary", "Learning terms from the pasted corpus");
      const dictionaryArtifactId = this.#dictionary.value;
      this.#vocabulary = await this.#api.learnVocabulary({
        start: {
          ...(dictionaryArtifactId ? { dictionaryArtifactId } : {}),
          displayName: `${displayName} vocabulary`,
          minFrequency: positiveInteger(this.#minFrequency.value, 1),
          maxTerms: positiveInteger(this.#maxTerms.value, 10_000),
          provenanceSummary: "Learned from text pasted into the Workflows workbench",
        },
        documents,
      });
      this.complete("vocabulary", `${formatInteger(this.#vocabulary.termCount)} terms ready`);

      this.activate("train", "Starting teacher distillation");
      this.#model = await this.#api.trainStaticModel({
        vocabularyArtifactId: this.#vocabulary.artifactId,
        teacherId: this.#teacher.value,
        displayName: `${displayName} embeddings`,
        pcaDims: nonNegativeInteger(this.#pcaDims.value, 0),
        provenanceSummary: "Distilled through the Workflows workbench",
      }, (progress) => this.setStageDetail("train", progress));
      this.complete("train", `${this.#model.dimension} dimensional model serving`);
      this.#callbacks.onModelTrained(this.#model);

      this.activate("embed", "Embedding analyzed sentence chunks");
      const embedded = await this.analyzeDocuments(documents, this.#model.artifactId);
      this.complete("embed", `${embedded.length} embedded ${plural(embedded.length, "document")}`);

      this.activate("index", "Publishing a live workspace index");
      this.#index = await this.#api.index({
        displayName,
        provider: { standard: this.#provider.value || FLAT_FLOAT_PROVIDER },
        documents: embedded.map((document) => indexDocument(document.response)),
        embedding: { modelId: this.#model.artifactId },
      });
      this.complete("index", `${formatInteger(this.#index.size ?? 0)} searchable chunks`);
      this.#callbacks.onIndexChanged(this.#index);

      await this.runSearch(query);
      this.renderArtifacts();
      this.selectResultView("search");
      this.setStatus(`Workflow '${displayName}' is ready to explore.`);
    } catch (error) {
      if (this.#activeStage) {
        this.fail(this.#activeStage, errorMessage(error, "Stage failed."));
      }
      this.setStatus(errorMessage(error, "The workflow did not complete."), true);
    } finally {
      this.#busy = false;
      this.#activeStage = undefined;
      this.updateControls();
    }
  }

  private async searchAgain(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const query = this.#query.value.trim();
    if (!this.#index || !query || this.#busy) {
      return;
    }
    this.#busy = true;
    this.activate("search", "Embedding and searching the new query");
    this.updateControls();
    try {
      await this.runSearch(query);
      this.selectResultView("search");
      this.setStatus(`Search complete in '${this.#index.label}'.`);
    } catch (error) {
      this.fail("search", errorMessage(error, "Search failed."));
      this.setStatus(errorMessage(error, "Search failed."), true);
    } finally {
      this.#busy = false;
      this.#activeStage = undefined;
      this.updateControls();
    }
  }

  private async analyzeDocuments(
    documents: Array<{ docId: string; rawText: string }>,
    modelId?: string,
  ): Promise<AnalyzedDocument[]> {
    const results: AnalyzedDocument[] = [];
    for (const document of documents) {
      const response = await this.#api.analyze(
        this.#callbacks.createAnalysisRequest(document, modelId));
      const shape = readDocumentShape(response);
      if (!shape.rawText) {
        throw new Error(`Analysis returned no document text for ${document.docId}.`);
      }
      results.push({ id: document.docId, response, shape });
      this.setStageDetail(modelId ? "embed" : "analyze",
        `${results.length} of ${documents.length} ${modelId ? "embedded" : "analyzed"}`);
    }
    return results;
  }

  private async runSearch(query: string): Promise<void> {
    const index = this.#index;
    if (!index) {
      throw new Error("The workflow index is not available.");
    }
    this.activate("search", "Embedding the query and scoring indexed chunks");
    const result = await this.#api.search(index.supportsAllHits
      ? createAllHitsSearchRequest(index.id, query)
      : createSearchRequest(index.id, query, Math.min(index.maxTopK ?? 50, 50)));
    this.renderSearchHeatmap(result);
    this.complete("search", `${result.hits.length} scored ${plural(result.hits.length, "chunk")}`);
  }

  private renderAnalysis(documents: AnalyzedDocument[]): void {
    this.#analysisResults.replaceChildren(...documents.map((document) => {
      const card = documentNode("article", "workflow-analysis-card");
      const heading = documentNode("header", "workflow-analysis-card-heading");
      const title = documentNode("div");
      const name = documentNode("h5");
      name.textContent = document.id;
      const summary = summarizeDocumentShape(document.shape);
      const facts = documentNode("small");
      facts.textContent = `${summary.layerCount} ${plural(summary.layerCount, "layer")} · `
        + `${summary.annotationCount} ${plural(summary.annotationCount, "annotation")}`;
      title.append(name, facts);
      const open = documentNode("button", "secondary-button") as HTMLButtonElement;
      open.type = "button";
      open.textContent = "Open full analysis";
      open.addEventListener("click", () => this.#callbacks.onOpenAnalysis(
        document.response, document.shape));
      heading.append(title, open);
      const layers = documentNode("div", "workflow-layer-chips");
      layers.append(...document.shape.layers.map((layer) => {
        const chip = documentNode("span");
        chip.dataset.accent = layerAccent(layer);
        chip.textContent = `${layer.title} ${layer.annotations.length}`;
        return chip;
      }));
      const text = documentNode("p", "workflow-analysis-text");
      text.textContent = document.shape.rawText;
      card.append(heading, layers, text);
      return card;
    }));
  }

  private renderSearchHeatmap(response: SearchResponse): void {
    const heats = buildDocumentHeat(response.hits);
    if (heats.length === 0) {
      this.#searchHeatmap.replaceChildren(empty("No source-mapped chunks matched this query."));
      return;
    }
    this.#searchHeatmap.replaceChildren(...heats.map((heat) => {
      const article = documentNode("article", "heat-document");
      const heading = documentNode("div", "heat-document-heading");
      const identity = documentNode("h4");
      identity.textContent = heat.documentId;
      const chunks = documentNode("small");
      chunks.textContent = `${heat.chunkCount} scored ${plural(heat.chunkCount, "chunk")}`;
      const score = documentNode("output", "server-hit-score");
      score.textContent = heat.maxScore.toFixed(3);
      const color = scoreColor(heat.maxScore);
      score.style.backgroundColor = color.background;
      score.style.color = color.foreground;
      heading.append(identity, chunks, score);
      const text = documentNode("p", "heat-text");
      text.append(...heat.segments.map((segment) => this.heatSegment(segment)));
      article.append(heading, text);
      return article;
    }));
  }

  private heatSegment(segment: HeatSegment): Node {
    if (segment.score === undefined || !segment.hitId) {
      return document.createTextNode(segment.text);
    }
    const color = scoreColor(segment.score);
    const button = documentNode("button", "heat-chunk") as HTMLButtonElement;
    button.type = "button";
    button.style.backgroundColor = color.background;
    button.style.color = color.foreground;
    button.title = `${segment.chunkId} · cosine ${segment.score.toFixed(4)}`;
    button.append(...matchedSegments({
      indexedChunkText: segment.text,
      matchedSpans: segment.matchedSpans,
    }).map((part) => {
      if (!part.matched) {
        return document.createTextNode(part.text);
      }
      const mark = documentNode("mark", "matched-span");
      mark.textContent = part.text;
      return mark;
    }));
    button.addEventListener("click", () => {
      for (const other of this.#searchHeatmap.querySelectorAll(".heat-chunk")) {
        other.setAttribute("aria-pressed", String(other === button));
      }
      this.#searchSelection.textContent = `${segment.chunkId}: cosine score `
        + `${segment.score?.toFixed(4)}. ${segment.text}`;
    });
    return button;
  }

  private renderArtifacts(): void {
    if (!this.#vocabulary || !this.#model || !this.#index) {
      return;
    }
    this.#artifacts.replaceChildren(
      artifact("Vocabulary", this.#vocabulary.artifactId),
      artifact("Model", this.#model.artifactId),
      artifact("Index", this.#index.id),
    );
  }

  private populateDictionaries(dictionaries: DictionaryArtifactSummary[]): void {
    this.#dictionary.replaceChildren(new Option("Corpus terms only (default)", ""));
    for (const dictionary of dictionaries) {
      this.#dictionary.add(new Option(
        `${dictionary.displayName} (${formatInteger(dictionary.entryCount)} entries)`,
        dictionary.artifactId));
    }
    this.#dictionary.disabled = false;
  }

  private populateTeachers(teachers: TeacherOption[]): void {
    this.#teacher.replaceChildren();
    for (const teacher of teachers) {
      const option = new Option(teacher.label, teacher.id);
      option.title = teacher.reference;
      this.#teacher.add(option);
    }
    if (teachers.length === 0) {
      this.#teacher.add(new Option("No teacher configured", ""));
    }
    this.#teacher.disabled = teachers.length === 0;
  }

  private populateProviders(providers: SearchProviderInstance[]): void {
    this.#provider.replaceChildren();
    const dynamic = providers.filter((provider) => provider.capabilities.includes("vector")
      && provider.capabilities.includes("live") && provider.standard);
    for (const provider of dynamic) {
      this.#provider.add(new Option(providerLabel(provider), provider.standard));
    }
    if (this.#provider.options.length === 0) {
      this.#provider.add(new Option("Exact flat float", FLAT_FLOAT_PROVIDER));
    }
    this.#provider.disabled = false;
  }

  private selectResultView(view: WorkflowResultView): void {
    this.#analysisPanel.hidden = view !== "analysis";
    this.#searchPanel.hidden = view !== "search";
    for (const tab of this.#resultTabs) {
      const selected = tab.dataset.workflowResultTab === view;
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
    }
  }

  private resetStages(): void {
    for (const stage of STAGES) {
      this.setStage(stage, "pending", "Waiting");
    }
  }

  private activate(stage: WorkflowStage, detail: string): void {
    this.#activeStage = stage;
    this.setStage(stage, "active", detail);
  }

  private complete(stage: WorkflowStage, detail: string): void {
    this.setStage(stage, "complete", detail);
  }

  private fail(stage: WorkflowStage, detail: string): void {
    this.setStage(stage, "error", detail);
  }

  private setStageDetail(stage: WorkflowStage, detail: string): void {
    const entry = this.stageElement(stage);
    const status = entry.querySelector<HTMLElement>(".workflow-stage-status");
    if (status) {
      status.textContent = detail;
    }
  }

  private setStage(stage: WorkflowStage, state: string, detail: string): void {
    const entry = this.stageElement(stage);
    entry.dataset.state = state;
    this.setStageDetail(stage, detail);
  }

  private stageElement(stage: WorkflowStage): HTMLElement {
    const entry = document.querySelector<HTMLElement>(`[data-workflow-stage="${stage}"]`);
    if (!entry) {
      throw new Error(`Missing workflow stage '${stage}'.`);
    }
    return entry;
  }

  private updateControls(): void {
    const documents = workflowDocuments(this.#corpus.value);
    this.#corpusStats.textContent = documents.length === 0
      ? "Add text to preview the workflow batch."
      : `${documents.length} ${plural(documents.length, "document")} ready · `
        + `${formatInteger(new TextEncoder().encode(this.#corpus.value).byteLength)} UTF-8 bytes`;
    this.#runButton.disabled = !this.#ready || this.#busy || documents.length === 0
      || !this.#query.value.trim();
    this.#searchButton.disabled = this.#busy || !this.#index || !this.#query.value.trim();
  }

  private setStatus(message: string, error = false): void {
    this.#status.textContent = message;
    this.#status.classList.toggle("is-error", error);
  }
}

/** Splits pasted text into stable workflow documents. */
export function workflowDocuments(text: string): Array<{ docId: string; rawText: string }> {
  return splitBlankLineDocuments(text).map((rawText, index) => ({
    docId: `workflow-doc-${index + 1}`,
    rawText,
  }));
}

function indexDocument(value: unknown): Record<string, unknown> {
  const envelope = record(value);
  const source = record(envelope.document);
  const result: Record<string, unknown> = {};
  for (const field of ["docId", "rawText", "offsetEncoding", "metadata", "chunkEmbeddingGroups"]) {
    if (source[field] !== undefined) {
      result[field] = source[field];
    }
  }
  if (!result.rawText || !result.chunkEmbeddingGroups) {
    throw new Error("Embedded analysis returned no indexable chunk groups.");
  }
  return result;
}

function positiveInteger(value: string, fallback: number): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function nonNegativeInteger(value: string, fallback: number): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

function plural(count: number, singular: string): string {
  return count === 1 ? singular : `${singular}s`;
}

function record(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}

function documentNode(tag: string, className?: string): HTMLElement {
  const element = document.createElement(tag);
  if (className) {
    element.className = className;
  }
  return element;
}

function artifact(label: string, id: string): HTMLElement {
  const chip = documentNode("span");
  const name = documentNode("small");
  name.textContent = label;
  const value = documentNode("code");
  value.textContent = id;
  chip.append(name, value);
  return chip;
}

function empty(message: string): HTMLElement {
  const element = documentNode("p", "empty-message");
  element.textContent = message;
  return element;
}

function providerLabel(provider: SearchProviderInstance): string {
  return provider.standard === "STANDARD_SEARCH_PROVIDER_TURBO_QUANT"
    ? "TurboQuant (quantized)" : "Exact flat float";
}
