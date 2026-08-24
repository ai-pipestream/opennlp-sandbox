#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Smoke-tests the demonstration image end to end. Builds the image from the
# already-built jars, then runs it under the hardened flags the README
# recommends (read-only root filesystem, all capabilities dropped, no new
# privileges) and asserts:
#   1. the container reports healthy and serves /healthz,
#   2. the process runs as the non-root opennlp user,
#   3. the gateway answers a real analyze round trip through the gRPC server,
#   4. a mounted server.properties is honored,
#   5. docker stop drains and exits within the shutdown grace period.
# Requires docker and curl. Run from opennlp-grpc/ after `mvn package`:
#   bash docker/test-image.sh
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=${OPENNLP_TEST_IMAGE:-opennlp-grpc-demo-test}
NAME=opennlp-image-test-$$
HTTP_PORT=${OPENNLP_TEST_HTTP_PORT:-17072}
GRPC_PORT=${OPENNLP_TEST_GRPC_PORT:-17071}
failures=0

say() { printf '\n== %s\n' "$1"; }
check() {
  if [ "$2" = "$3" ]; then
    printf 'ok   %s\n' "$1"
  else
    printf 'FAIL %s: expected [%s], got [%s]\n' "$1" "$3" "$2"
    failures=$((failures + 1))
  fi
}
cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -f "$config_file"
}
trap cleanup EXIT

say "Building $IMAGE"
docker build -q -f docker/Dockerfile -t "$IMAGE" .

# A distinctive text limit proves the mounted configuration file was loaded.
config_file=$(mktemp)
printf 'server.max_text_bytes=524288\n' > "$config_file"
chmod 644 "$config_file"

say "Starting hardened container"
docker run -d --name "$NAME" \
  --read-only --tmpfs /tmp --tmpfs /srv/opennlp \
  --cap-drop=ALL --security-opt no-new-privileges \
  -v "$config_file":/srv/opennlp/server.properties:ro \
  -p "127.0.0.1:$GRPC_PORT:7071" -p "127.0.0.1:$HTTP_PORT:7072" \
  "$IMAGE" >/dev/null

say "Waiting for the healthcheck"
state=starting
for _ in $(seq 1 60); do
  state=$(docker inspect -f '{{.State.Health.Status}}' "$NAME")
  [ "$state" = healthy ] && break
  [ "$state" = unhealthy ] && break
  sleep 2
done
check "container healthy" "$state" healthy
if [ "$state" != healthy ]; then
  docker logs "$NAME" 2>&1 | tail -20
  exit 1
fi

say "Asserting the runtime user and endpoints"
check "runs as non-root" "$(docker exec "$NAME" id -u)" "$(docker exec "$NAME" id -u opennlp)"
check "gateway /healthz" "$(curl -s "http://127.0.0.1:$HTTP_PORT/healthz")" ok

say "Analyze round trip through the gateway and gRPC server"
analyzed=$(curl -s -X POST -H 'Content-Type: application/json' \
  "http://127.0.0.1:$HTTP_PORT/api/v1/analyze" \
  -d '{"document":{"docId":"smoke","rawText":"The quick brown fox jumps over the lazy dog."},
       "profile":{"steps":["PIPELINE_STEP_SENTENCE_DETECT","PIPELINE_STEP_TOKENIZE","PIPELINE_STEP_POS_TAG"]}}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["document"]; print(len(d["sentences"][0]["tokens"]))')
check "token count for the smoke sentence" "$analyzed" 10

say "Asserting the mounted server.properties is honored"
text_limit=$(curl -s "http://127.0.0.1:$HTTP_PORT/api/v1/service-info" \
  | python3 -c 'import json,sys; print(int(json.load(sys.stdin)["maxTextBytes"]))')
check "service-info reports the configured text limit" "$text_limit" 524288

say "Graceful shutdown"
start=$(date +%s)
docker stop "$NAME" >/dev/null
elapsed=$(( $(date +%s) - start ))
if [ "$elapsed" -le 15 ]; then
  printf 'ok   stopped in %ss\n' "$elapsed"
else
  printf 'FAIL stop took %ss (expected <= 15)\n' "$elapsed"
  failures=$((failures + 1))
fi

if [ "$failures" -eq 0 ]; then
  say "PASS: all image assertions held"
else
  say "FAIL: $failures assertion(s) failed"
  exit 1
fi
