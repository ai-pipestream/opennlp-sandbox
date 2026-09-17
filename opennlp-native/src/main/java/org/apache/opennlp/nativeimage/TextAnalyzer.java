/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.opennlp.nativeimage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import opennlp.tools.util.model.BaseModel;
import opennlp.tools.util.model.ModelLoader;

/**
 * A four model pipeline over one text: sentence detection, tokenization, POS tagging and
 * person name finding. The result is JSON, which keeps the C boundary at one string per call.
 *
 * <p>The component classes hold per document state, so every method is synchronized and one
 * instance serves one caller at a time. A caller that wants parallel analysis creates several
 * instances.</p>
 */
final class TextAnalyzer {

  /** The model file names, as published on the OpenNLP model page. */
  private static final String SENTENCE_MODEL = "opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin";
  private static final String TOKENIZER_MODEL = "opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin";
  private static final String POS_MODEL = "opennlp-en-ud-ewt-pos-1.3-2.5.4.bin";
  private static final String NAME_MODEL = "en-ner-person.bin";

  private final SentenceDetectorME sentenceDetector;
  private final TokenizerME tokenizer;
  private final POSTaggerME tagger;
  private final NameFinderME nameFinder;

  private TextAnalyzer(SentenceModel sentenceModel, TokenizerModel tokenizerModel,
      POSModel posModel, TokenNameFinderModel nameModel) {
    this.sentenceDetector = new SentenceDetectorME(sentenceModel);
    this.tokenizer = new TokenizerME(tokenizerModel);
    this.tagger = new POSTaggerME(posModel);
    this.nameFinder = new NameFinderME(nameModel);
  }

  /**
   * Reads the four models from a directory.
   *
   * @param directory The directory with the model files. Must not be {@code null}.
   * @return The analyzer. Never {@code null}.
   * @throws IOException Thrown if a model file is missing or cannot be read.
   * @throws IllegalArgumentException Thrown if {@code directory} is {@code null}.
   */
  static TextAnalyzer fromDirectory(Path directory) throws IOException {
    if (directory == null) {
      throw new IllegalArgumentException("directory must not be null");
    }
    return new TextAnalyzer(
        read(directory, SENTENCE_MODEL, SentenceModel.class),
        read(directory, TOKENIZER_MODEL, TokenizerModel.class),
        read(directory, POS_MODEL, POSModel.class),
        read(directory, NAME_MODEL, TokenNameFinderModel.class));
  }

  /**
   * Analyzes one text.
   *
   * @param text The text to analyze. Must not be {@code null}.
   * @return A JSON object with a {@code sentences} array; each sentence has its character
   *         offsets, its tokens with POS tags, and the person names found in it.
   * @throws IllegalArgumentException Thrown if {@code text} is {@code null}.
   */
  synchronized String analyze(String text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final StringBuilder json = new StringBuilder(256);
    json.append("{\"sentences\":[");
    final Span[] sentences = sentenceDetector.sentPosDetect(text);
    for (int i = 0; i < sentences.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      appendSentence(json, text, sentences[i]);
    }
    json.append("]}");
    // the adaptive feature generators keep document state, which ends with the text
    nameFinder.clearAdaptiveData();
    return json.toString();
  }

  /** Appends one sentence object, with offsets relative to the whole text. */
  private void appendSentence(StringBuilder json, String text, Span sentence) {
    final String covered = sentence.getCoveredText(text).toString();
    final int base = sentence.getStart();
    final Span[] tokenSpans = tokenizer.tokenizePos(covered);
    final String[] tokens = Span.spansToStrings(tokenSpans, covered);
    final String[] tags = tagger.tag(tokens);
    final Span[] names = nameFinder.find(tokens);

    json.append("{\"start\":").append(base)
        .append(",\"end\":").append(sentence.getEnd())
        .append(",\"text\":");
    Json.appendString(json, covered);
    json.append(",\"tokens\":[");
    for (int i = 0; i < tokens.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"text\":");
      Json.appendString(json, tokens[i]);
      json.append(",\"start\":").append(base + tokenSpans[i].getStart())
          .append(",\"end\":").append(base + tokenSpans[i].getEnd())
          .append(",\"tag\":");
      Json.appendString(json, tags[i]);
      json.append('}');
    }
    json.append("],\"names\":[");
    for (int i = 0; i < names.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      final Span name = names[i];
      json.append("{\"text\":");
      Json.appendString(json, String.join(" ", java.util.Arrays.copyOfRange(tokens,
          name.getStart(), name.getEnd())));
      json.append(",\"start\":").append(base + tokenSpans[name.getStart()].getStart())
          .append(",\"end\":").append(base + tokenSpans[name.getEnd() - 1].getEnd())
          .append(",\"type\":");
      Json.appendString(json, name.getType());
      json.append('}');
    }
    json.append("]}");
  }

  /** Reads one model file through the registry, which needs no reflection. */
  private static <T extends BaseModel> T read(Path directory, String name, Class<T> type)
      throws IOException {
    final Path file = directory.resolve(name);
    if (!Files.isRegularFile(file)) {
      throw new IOException("model file not found: " + file.toAbsolutePath());
    }
    try (InputStream in = Files.newInputStream(file)) {
      return ModelLoader.forType(type).load(in);
    }
  }
}
