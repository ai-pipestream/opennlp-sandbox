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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

import opennlp.tools.util.Version;

/**
 * The C entry points of the shared library. A caller creates a GraalVM isolate, keeps the
 * isolate thread it receives, and passes that thread to every call.
 *
 * <p>The C names and signatures are:</p>
 * <pre>
 * char*     opennlp_version(graal_isolatethread_t*);
 * long long opennlp_analyzer_create(graal_isolatethread_t*, const char* model_directory);
 * char*     opennlp_analyze(graal_isolatethread_t*, long long handle, const char* text);
 * void      opennlp_analyzer_destroy(graal_isolatethread_t*, long long handle);
 * char*     opennlp_last_error(graal_isolatethread_t*);
 * void      opennlp_free(graal_isolatethread_t*, char* text);
 * </pre>
 *
 * <p>Every returned string is allocated with {@code malloc} and belongs to the caller, who
 * releases it with {@code opennlp_free}. A call that fails returns {@code 0} or {@code NULL}
 * and leaves a message for {@code opennlp_last_error}. An exception must not cross into C,
 * so each entry point catches whatever the pipeline throws.</p>
 */
public final class OpenNlpLibrary {

  /** The analyzers a caller has created, by handle. */
  private static final Map<Long, TextAnalyzer> ANALYZERS = new ConcurrentHashMap<>();

  /** Handles start at one, so zero always means failure. */
  private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);

  /** The message of the most recent failure, or an empty string. */
  private static final AtomicReference<String> LAST_ERROR = new AtomicReference<>("");

  private OpenNlpLibrary() {
  }

  /**
   * Reports the OpenNLP version the library was built from.
   *
   * @param thread The calling isolate thread.
   * @return The version text. The caller releases it with {@code opennlp_free}.
   */
  @CEntryPoint(name = "opennlp_version")
  static CCharPointer version(IsolateThread thread) {
    try {
      return copyToNative(Version.currentVersion().toString());
    } catch (Throwable t) {
      return failPointer(t);
    }
  }

  /**
   * Loads the models of one analyzer.
   *
   * @param thread The calling isolate thread.
   * @param modelDirectory The directory with the model files.
   * @return A handle for the other calls, or {@code 0} when the models cannot be read.
   */
  @CEntryPoint(name = "opennlp_analyzer_create")
  static long analyzerCreate(IsolateThread thread, CCharPointer modelDirectory) {
    try {
      final String directory = CTypeConversion.toJavaString(modelDirectory);
      final TextAnalyzer analyzer = TextAnalyzer.fromDirectory(Path.of(directory));
      final long handle = NEXT_HANDLE.getAndIncrement();
      ANALYZERS.put(handle, analyzer);
      LAST_ERROR.set("");
      return handle;
    } catch (Throwable t) {
      LAST_ERROR.set(describe(t));
      return 0L;
    }
  }

  /**
   * Analyzes one text.
   *
   * @param thread The calling isolate thread.
   * @param handle A handle from {@code opennlp_analyzer_create}.
   * @param text The text to analyze.
   * @return The JSON result, or {@code NULL} when the handle is unknown or the analysis fails.
   *         The caller releases it with {@code opennlp_free}.
   */
  @CEntryPoint(name = "opennlp_analyze")
  static CCharPointer analyze(IsolateThread thread, long handle, CCharPointer text) {
    try {
      final TextAnalyzer analyzer = ANALYZERS.get(handle);
      if (analyzer == null) {
        LAST_ERROR.set("unknown analyzer handle: " + handle);
        return WordFactory.nullPointer();
      }
      final String result = analyzer.analyze(CTypeConversion.toJavaString(text));
      LAST_ERROR.set("");
      return copyToNative(result);
    } catch (Throwable t) {
      return failPointer(t);
    }
  }

  /**
   * Releases the models of one analyzer. An unknown handle is accepted and ignored.
   *
   * @param thread The calling isolate thread.
   * @param handle A handle from {@code opennlp_analyzer_create}.
   */
  @CEntryPoint(name = "opennlp_analyzer_destroy")
  static void analyzerDestroy(IsolateThread thread, long handle) {
    ANALYZERS.remove(handle);
  }

  /**
   * Reports why the most recent call failed.
   *
   * @param thread The calling isolate thread.
   * @return The message, or {@code NULL} when the most recent call succeeded. The caller
   *         releases it with {@code opennlp_free}.
   */
  @CEntryPoint(name = "opennlp_last_error")
  static CCharPointer lastError(IsolateThread thread) {
    final String message = LAST_ERROR.get();
    if (message.isEmpty()) {
      return WordFactory.nullPointer();
    }
    try {
      return copyToNative(message);
    } catch (Throwable t) {
      return WordFactory.nullPointer();
    }
  }

  /**
   * Releases a string the library returned. A {@code NULL} pointer is accepted and ignored.
   *
   * @param thread The calling isolate thread.
   * @param text A pointer from one of the other calls.
   */
  @CEntryPoint(name = "opennlp_free")
  static void free(IsolateThread thread, CCharPointer text) {
    if (text.isNonNull()) {
      UnmanagedMemory.free(text);
    }
  }

  /** Copies a string into malloc memory as UTF-8 with a terminating zero byte. */
  private static CCharPointer copyToNative(String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    final CCharPointer buffer = UnmanagedMemory.malloc(bytes.length + 1);
    for (int i = 0; i < bytes.length; i++) {
      buffer.write(i, bytes[i]);
    }
    buffer.write(bytes.length, (byte) 0);
    return buffer;
  }

  /** Records a failure and answers the pointer calls with NULL. */
  private static CCharPointer failPointer(Throwable t) {
    LAST_ERROR.set(describe(t));
    return WordFactory.nullPointer();
  }

  /** Builds a message a C caller can print, since the stack trace ends at the boundary. */
  private static String describe(Throwable t) {
    final String message = t.getMessage();
    return message == null ? t.getClass().getName() : t.getClass().getName() + ": " + message;
  }
}
