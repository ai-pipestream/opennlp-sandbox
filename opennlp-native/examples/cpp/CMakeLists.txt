# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 3.16)
project(opennlp_example CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libopennlp.so, libopennlp.h and graal_isolate.h are written here by the native profile.
set(OPENNLP_NATIVE_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../target"
    CACHE PATH "Directory holding libopennlp.so and the generated headers")

add_executable(opennlp_example main.cpp)
target_include_directories(opennlp_example PRIVATE "${OPENNLP_NATIVE_LIB_DIR}")
target_link_directories(opennlp_example PRIVATE "${OPENNLP_NATIVE_LIB_DIR}")
target_link_libraries(opennlp_example PRIVATE opennlp)

# the example runs from the build directory, so record where the library lives
set_target_properties(opennlp_example PROPERTIES
    BUILD_RPATH "${OPENNLP_NATIVE_LIB_DIR}"
    INSTALL_RPATH "${OPENNLP_NATIVE_LIB_DIR}")
