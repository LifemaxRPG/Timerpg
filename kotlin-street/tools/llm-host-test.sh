#!/bin/sh
# Builds the native language-model engine (app/src/main/cpp: llama.cpp + llm_core) for this computer and runs its test
# (host_test.cpp): text helpers, failure paths, real NPC prompts with timings and KV-cache reuse, cancellation.
#   tools/llm-host-test.sh [model.gguf] [llm_host_test options, e.g. --threads 4 --prompts FILE --verbose]
# llama.cpp: llama.dir in local.properties, else third_party/llama.cpp (tools/get-llama.sh fetches it).
# Model: the argument, else $LLM_MODEL, else models/qwen3-0.6b-q4_k_m.gguf at the repository root (or the main
# checkout's, from a git worktree). Build directory: $LLM_HOST_BUILD (default /tmp/llm-host-build).
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"   # kotlin-street
build="${LLM_HOST_BUILD:-/tmp/llm-host-build}"

llama=""
if [ -f "$here/local.properties" ]; then
  llama="$(sed -n 's/^llama\.dir[[:space:]]*=[[:space:]]*//p' "$here/local.properties" | tail -n 1)"
  case "$llama" in ""|/*) ;; *) llama="$here/$llama" ;; esac
fi
[ -n "$llama" ] || llama="$here/third_party/llama.cpp"
if [ ! -f "$llama/include/llama.h" ]; then
  echo "llama.cpp not found at $llama: run tools/get-llama.sh (or set llama.dir in local.properties)" >&2
  exit 1
fi

model=""
case "$1" in *.gguf) model="$1"; shift ;; esac
[ -n "$model" ] || model="$LLM_MODEL"
if [ -z "$model" ]; then
  root="$(git -C "$here" rev-parse --show-toplevel 2>/dev/null || echo "$here/..")"
  model="$root/models/qwen3-0.6b-q4_k_m.gguf"
  if [ ! -f "$model" ]; then  # in a git worktree the model sits in the main checkout
    common="$(git -C "$here" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
    [ -n "$common" ] && model="$(dirname "$common")/models/qwen3-0.6b-q4_k_m.gguf"
  fi
fi
if [ ! -f "$model" ]; then
  echo "model not found ($model): pass the .gguf path or set LLM_MODEL" >&2
  exit 1
fi

gen=""
command -v ninja >/dev/null 2>&1 && gen="-G Ninja"
# shellcheck disable=SC2086
cmake -S "$here/app/src/main/cpp" -B "$build" $gen -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR="$llama" >/dev/null
cmake --build "$build" --target llm_host_test -j "$(nproc 2>/dev/null || echo 4)"
exec "$build/llm_host_test" "$model" "$@"
