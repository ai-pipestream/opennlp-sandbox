# OpenNLP as a native shared library

This module builds OpenNLP into `libopennlp.so` with GraalVM `native-image`, so a program in any
language that speaks C can run the OpenNLP pipeline in its own process: no JVM, no JNI, no server.
The `examples/` directory has one Rust program and one C++ program that link against the library.

The library loads four models at run time and returns JSON, which keeps one string per call at the
C boundary:

* sentence detection
* tokenization
* POS tagging
* person name finding

## Requirements

* GraalVM JDK 25 or later with `native-image` on the path.
* The four model files in one directory, `$HOME/.opennlp` by default:
  `opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin`, `opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin`,
  `opennlp-en-ud-ewt-pos-1.3-2.5.4.bin` and `en-ner-person.bin`.
* An OpenNLP build that carries the native image preparation of OPENNLP-1954, installed as
  `3.0.0-OPENNLP-1954-SNAPSHOT`. A released OpenNLP loads models through reflection, which a
  native image cannot follow. From an OpenNLP checkout on that branch:

```bash
./mvnw versions:set -DnewVersion=3.0.0-OPENNLP-1954-SNAPSHOT -DgenerateBackupPoms=false
./mvnw -DskipTests -pl opennlp-core/opennlp-runtime,\
opennlp-core/opennlp-ml/opennlp-ml-maxent,\
opennlp-core/opennlp-ml/opennlp-ml-perceptron,\
opennlp-core/opennlp-ml/opennlp-ml-bayes -am install
git checkout -- .
```

## Building the library

```bash
mvn -Pnative -pl opennlp-native package
```

Without the `native` profile only the Java classes compile. The profile writes into `target/`:

| File | Purpose |
| --- | --- |
| `libopennlp.so` | the library, about 23 MiB |
| `libopennlp.h` | the six entry points below |
| `graal_isolate.h` | isolate creation and teardown |

## The C API

```c
char*     opennlp_version(graal_isolatethread_t*);
long long opennlp_analyzer_create(graal_isolatethread_t*, char* model_directory);
char*     opennlp_analyze(graal_isolatethread_t*, long long handle, char* text);
void      opennlp_analyzer_destroy(graal_isolatethread_t*, long long handle);
char*     opennlp_last_error(graal_isolatethread_t*);
void      opennlp_free(graal_isolatethread_t*, char* text);
```

Rules a caller follows:

* Create an isolate with `graal_create_isolate` before the first call and pass the isolate thread
  it returns to every call. `graal_tear_down_isolate` ends it.
* Every returned string is `malloc` memory that belongs to the caller. Release it with
  `opennlp_free`.
* A call that fails returns `0` or `NULL`, and `opennlp_last_error` then reports why.
* One analyzer serves one caller at a time: the OpenNLP component classes keep per document
  state, so the analyzer synchronizes. For parallel analysis, create one analyzer per thread.

The JSON result has a `sentences` array. Each sentence carries its character offsets in the
original text, its tokens with offsets and POS tags, and the person names found in it.

## The Rust example

```bash
cd examples/rust
cargo build --release
./target/release/opennlp-example [model directory] [text]
```

`build.rs` points the linker at `../../target` and records that directory as an rpath, so the
program finds the library without `LD_LIBRARY_PATH`. Set `OPENNLP_NATIVE_LIB_DIR` to use a copy
somewhere else. The FFI declarations are written by hand: the library exports plain C, so the
example needs no bindgen and no crate dependencies.

## The C++ example

```bash
cd examples/cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
./build/opennlp_example [model directory] [text]
```

`CMakeLists.txt` includes the generated headers from `../../target` and links `-lopennlp`. Pass
`-DOPENNLP_NATIVE_LIB_DIR=...` to point at a copy somewhere else.

## What both programs print

```text
OpenNLP 3.0.0-SNAPSHOT in a native image, called from Rust

JSON from the library:
{"sentences":[{"start":0,"end":76,"text":"Pierre Vinken, 61 years old, ...

sentences: 2
tokens:    29
names:     1
```

## Measurements

On one Linux machine with GraalVM CE 25.1.3, the same pipeline over the same two sentences:

| | native image | JVM |
| --- | --- | --- |
| process startup | under 10 ms | 20 ms |
| startup plus reading the four models | 700 ms | 390 ms |

Native image wins the startup, which is what an embedded library or a short lived command needs.
The JVM reads the models faster, because that work is heavy enough for the JIT to pay off while
the AOT code runs at its compiled speed throughout. A process that loads models once and then
analyzes many texts should measure the analysis loop as well before choosing.
