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

/**
 * Writes JSON string literals. The library returns small documents, so a scan over the
 * characters replaces a JSON dependency and the reachability metadata it would need.
 */
final class Json {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Json() {
  }

  /**
   * Appends a quoted JSON string. Quote, backslash and the C0 controls are escaped; every
   * other character is written as it is, so the output is UTF-8 once encoded.
   *
   * @param out The target. Must not be {@code null}.
   * @param value The text to quote. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null}.
   */
  static void appendString(StringBuilder out, String value) {
    if (out == null) {
      throw new IllegalArgumentException("out must not be null");
    }
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
