#!/bin/sh
# Calibrated quantization of the dialogue models (kotlin-street/docs/MODEL_FILES.md, ENGINE_TAILORING.md idea 3).
#
#   quantize-ours.sh calib    WORK_DIR                                   our calibration text (needs make-head-vocab.sh's
#                                                                         exports in WORK_DIR/export)
#   quantize-ours.sh imatrix  SRC-BF16.gguf CALIB.txt OUT.imatrix.gguf   server: the importance matrix, once per model
#   quantize-ours.sh quantize SRC-BF16.gguf IMATRIX OUT.gguf TYPE         server or phone: TYPE Q4_0 or Q4_K_M
#
# TOOLS: the build of kotlin-street/tools/llm/CMakeLists.txt (default /dev/shm/tl/tools). THREADS (default: all cores).
# The quantize step is tools/llm/quantize_ours (quantize_ours.cpp: llama_model_quantize() with the imatrix, token_embd
# and output at Q8_0, everything else as llama.cpp chooses for TYPE, at most 512 MiB of rows in memory at once): the
# phone runs that same function, so its file is the one measured here.
set -e
here="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$here/../../.." && pwd)"
tools="${TOOLS:-/dev/shm/tl/tools}"
threads="${THREADS:-$(nproc)}"
case "$1" in
  calib)
    # every 40th person of the other seeds' exports (760 voiced prompts), their template lines as the reply, kept when
    # validate() passes them (752), shuffled: prompt + reply + <|im_end|>, exactly what the model reads and writes
    w="$2"
    python3 -I "$here/game_corpus.py" "$w/calib40" "$w"/export/*.json --sample 40
    python3 -I "$here/calib_text.py" templates "$w/calib40/sample.json" "$w/calib40/templates.json"
    (cd "$repo" && ./node_modules/.bin/tsx kotlin-street/tools/check-replies.ts "$w/calib40/templates.json")
    python3 -I "$here/calib_text.py" build "$w/calib.txt" "$w/calib40/templates.json"
    ;;
  imatrix)
    # 512-token windows over our ChatML conversations, specials parsed as the app's tokenizer does, 100 windows asked
    # (the shipped file holds 90: the run was killed by the server's memory limit after its 90th save)
    exec "$tools/bin/llama-imatrix" -m "$2" -f "$3" -o "$4" --output-format gguf --parse-special -c 512 -b 512 \
      -t "$threads" --no-ppl --process-output --chunks 100
    ;;
  quantize)
    exec "$tools/quantize_ours" "$2" "$3" "$4" "$5" --threads "$threads"
    ;;
  *)
    echo "usage: $0 calib WORK | imatrix SRC CALIB OUT | quantize SRC IMATRIX OUT TYPE" >&2; exit 2 ;;
esac
