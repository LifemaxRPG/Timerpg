#!/bin/sh
# validate() pass rate of a model file on the app's eval prompts, through the app's own engine (as
# tools/llm-dialogue-check.sh, without the Kotlin half): llm_host_test --dialogue, then tools/check-replies.ts.
#   eval-pass-rate.sh MODEL.gguf OUT_DIR [SEED...]       (default seeds: 42 7)
# HOST: the llm_host_test binary (default /dev/shm/tl/host/llm_host_test, built from app/src/main/cpp with
# -DLLAMA_DIR=<llama.cpp> -DGGML_CPU_REPACK=OFF, so the CPU keeps no second, repacked copy of the weights).
# DIALOGUE: the prompts (default the app's assets/dialogue.json: eta 2025, 45 prompts, held out of every calibration).
set -e
here="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$here/../../.." && pwd)"
model="$1"; out="$2"; shift 2
seeds="${*:-42 7}"
host="${HOST:-/dev/shm/tl/host/llm_host_test}"
dialogue="${DIALOGUE:-$repo/kotlin-street/app/src/main/assets/dialogue.json}"
mkdir -p "$out"
for s in $seeds; do
  "$host" "$model" --dialogue "$dialogue" --json-out "$out/replies-$s.json" --no-checks --seed "$s" --threads "${THREADS:-4}" \
    > "$out/host-$s.log" 2>&1
  (cd "$repo" && ./node_modules/.bin/tsx kotlin-street/tools/check-replies.ts "$out/replies-$s.json") | tee "$out/pass-$s.txt"
done
