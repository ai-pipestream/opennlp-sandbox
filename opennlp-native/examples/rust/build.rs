// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use std::env;
use std::path::PathBuf;

/// Points the linker at libopennlp.so. Set OPENNLP_NATIVE_LIB_DIR when the library sits
/// somewhere other than the Maven output directory of this module.
fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let default_dir = manifest.join("../../target").canonicalize().unwrap_or(manifest);
    let lib_dir = env::var("OPENNLP_NATIVE_LIB_DIR").unwrap_or_else(|_| default_dir.display().to_string());

    println!("cargo:rustc-link-search=native={lib_dir}");
    println!("cargo:rustc-link-lib=dylib=opennlp");
    // the example runs from the build directory, so record where the library lives
    println!("cargo:rustc-link-arg=-Wl,-rpath,{lib_dir}");
    println!("cargo:rerun-if-env-changed=OPENNLP_NATIVE_LIB_DIR");
}
