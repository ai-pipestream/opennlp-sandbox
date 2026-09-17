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

// Calls OpenNLP through libopennlp.so, the GraalVM native image of the opennlp-native module.
//
// Usage: opennlp_example [model directory] [text]
// The model directory defaults to $HOME/.opennlp and holds the four models the library reads.

#include <cstdlib>
#include <iostream>
#include <optional>
#include <string>

#include "libopennlp.h"

namespace {

/// Copies a string the library returned into std::string and releases the C buffer.
std::optional<std::string> take_string(graal_isolatethread_t* thread, char* value) {
  if (value == nullptr) {
    return std::nullopt;
  }
  std::string copy(value);
  opennlp_free(thread, value);
  return copy;
}

/// Reports why the most recent call failed.
std::string last_error(graal_isolatethread_t* thread) {
  return take_string(thread, opennlp_last_error(thread)).value_or("unknown");
}

/// Counts the occurrences of a key. The example keeps a JSON library out of the picture, so
/// the point stays the C boundary rather than the parsing.
std::size_t count_of(const std::string& json, const std::string& key) {
  std::size_t count = 0;
  for (std::size_t at = json.find(key); at != std::string::npos; at = json.find(key, at + key.size())) {
    count++;
  }
  return count;
}

}  // namespace

int main(int argc, char** argv) {
  const char* home = std::getenv("HOME");
  std::string model_directory = argc > 1 ? argv[1] : std::string(home == nullptr ? "." : home) + "/.opennlp";
  std::string text = argc > 2 ? argv[2]
      : "Pierre Vinken, 61 years old, will join the board as a nonexecutive director. "
        "Mr. Vinken is chairman of Elsevier N.V., the Dutch publishing group.";

  graal_isolate_t* isolate = nullptr;
  graal_isolatethread_t* thread = nullptr;
  if (graal_create_isolate(nullptr, &isolate, &thread) != 0) {
    std::cerr << "could not create the isolate\n";
    return 1;
  }

  if (auto version = take_string(thread, opennlp_version(thread))) {
    std::cout << "OpenNLP " << *version << " in a native image, called from C++\n";
  }

  const long long handle = opennlp_analyzer_create(thread, model_directory.data());
  if (handle == 0) {
    std::cerr << "could not load the models from " << model_directory << ": " << last_error(thread) << "\n";
    graal_tear_down_isolate(thread);
    return 1;
  }

  auto result = take_string(thread, opennlp_analyze(thread, handle, text.data()));
  opennlp_analyzer_destroy(thread, handle);

  if (!result) {
    std::cerr << "the analysis failed: " << last_error(thread) << "\n";
    graal_tear_down_isolate(thread);
    return 1;
  }

  std::cout << "\nJSON from the library:\n" << *result << "\n";
  std::cout << "\nsentences: " << count_of(*result, "\"tokens\":[") << "\n";
  std::cout << "tokens:    " << count_of(*result, "\"tag\":") << "\n";
  std::cout << "names:     " << count_of(*result, "\"type\":") << "\n";

  graal_tear_down_isolate(thread);
  return 0;
}
