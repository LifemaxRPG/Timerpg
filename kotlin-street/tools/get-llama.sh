#!/bin/sh
# Fetches llama.cpp (the on-device language model runtime) at the version the app is built and tested with, into
# kotlin-street/third_party/llama.cpp (git-ignored). Or point the build elsewhere with `llama.dir=...` in local.properties.
set -e
cd "$(dirname "$0")/.."
[ -d third_party/llama.cpp ] || git clone --depth 1 --branch v1.0.0 https://github.com/ggml-org/llama.cpp third_party/llama.cpp
