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

//! Calls OpenNLP through libopennlp.so, the GraalVM native image of the opennlp-native module.
//!
//! Usage: opennlp-example [model directory] [text]
//! The model directory defaults to $HOME/.opennlp and holds the four models the library reads.

use std::env;
use std::ffi::{c_char, c_int, c_longlong, c_void, CStr, CString};
use std::process::ExitCode;

/// An isolate is the heap of one embedded Java runtime.
#[repr(C)]
pub struct GraalIsolate {
    _private: [u8; 0],
}

/// A thread attached to an isolate. Every library call takes one.
#[repr(C)]
pub struct GraalIsolateThread {
    _private: [u8; 0],
}

extern "C" {
    fn graal_create_isolate(
        params: *mut c_void,
        isolate: *mut *mut GraalIsolate,
        thread: *mut *mut GraalIsolateThread,
    ) -> c_int;
    fn graal_tear_down_isolate(thread: *mut GraalIsolateThread) -> c_int;

    fn opennlp_version(thread: *mut GraalIsolateThread) -> *mut c_char;
    fn opennlp_analyzer_create(thread: *mut GraalIsolateThread, model_dir: *mut c_char) -> c_longlong;
    fn opennlp_analyze(
        thread: *mut GraalIsolateThread,
        handle: c_longlong,
        text: *mut c_char,
    ) -> *mut c_char;
    fn opennlp_analyzer_destroy(thread: *mut GraalIsolateThread, handle: c_longlong);
    fn opennlp_last_error(thread: *mut GraalIsolateThread) -> *mut c_char;
    fn opennlp_free(thread: *mut GraalIsolateThread, text: *mut c_char);
}

/// Copies a string the library returned into Rust and releases the C buffer.
///
/// # Safety
/// `ptr` is either null or a pointer the library allocated for this caller.
unsafe fn take_string(thread: *mut GraalIsolateThread, ptr: *mut c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    let owned = CStr::from_ptr(ptr).to_string_lossy().into_owned();
    opennlp_free(thread, ptr);
    Some(owned)
}

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let model_dir = args.next().unwrap_or_else(|| {
        format!("{}/.opennlp", env::var("HOME").unwrap_or_else(|_| ".".to_string()))
    });
    let text = args.next().unwrap_or_else(|| {
        "Pierre Vinken, 61 years old, will join the board as a nonexecutive director. \
         Mr. Vinken is chairman of Elsevier N.V., the Dutch publishing group."
            .to_string()
    });

    let mut isolate: *mut GraalIsolate = std::ptr::null_mut();
    let mut thread: *mut GraalIsolateThread = std::ptr::null_mut();

    unsafe {
        if graal_create_isolate(std::ptr::null_mut(), &mut isolate, &mut thread) != 0 {
            eprintln!("could not create the isolate");
            return ExitCode::FAILURE;
        }

        match take_string(thread, opennlp_version(thread)) {
            Some(version) => println!("OpenNLP {version} in a native image, called from Rust"),
            None => println!("OpenNLP version unavailable"),
        }

        let dir = CString::new(model_dir.clone()).expect("the model directory holds no zero byte");
        let handle = opennlp_analyzer_create(thread, dir.as_ptr() as *mut c_char);
        if handle == 0 {
            let reason = take_string(thread, opennlp_last_error(thread))
                .unwrap_or_else(|| "unknown".to_string());
            eprintln!("could not load the models from {model_dir}: {reason}");
            graal_tear_down_isolate(thread);
            return ExitCode::FAILURE;
        }

        let input = CString::new(text).expect("the text holds no zero byte");
        let result = take_string(thread, opennlp_analyze(thread, handle, input.as_ptr() as *mut c_char));

        opennlp_analyzer_destroy(thread, handle);

        let code = match result {
            Some(json) => {
                print_summary(&json);
                ExitCode::SUCCESS
            }
            None => {
                let reason = take_string(thread, opennlp_last_error(thread))
                    .unwrap_or_else(|| "unknown".to_string());
                eprintln!("the analysis failed: {reason}");
                ExitCode::FAILURE
            }
        };

        graal_tear_down_isolate(thread);
        code
    }
}

/// Prints the JSON the library returned, plus the fields a reader looks for first.
fn print_summary(json: &str) {
    println!("\nJSON from the library:\n{json}");
    println!("\nsentences: {}", count_of(json, "\"tokens\":["));
    println!("tokens:    {}", count_of(json, "\"tag\":"));
    println!("names:     {}", count_of(json, "\"type\":"));
}

/// Counts the occurrences of a key. The example keeps a JSON crate out of the picture, so the
/// point stays the C boundary rather than the parsing.
fn count_of(json: &str, key: &str) -> usize {
    json.matches(key).count()
}
