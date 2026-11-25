#!/bin/sh
# Perplexity of a model file on held-out dialogue (kotlin-street/docs/MODEL_FILES.md): gamma 2025's prompts (the app's
# town, held out of the calibration) each followed by its validated template reply, in 512-token windows with the
# ChatML specials parsed (llama-imatrix's --ppl does that; llama-perplexity cannot), the same 16 windows for every file.
#   eval-ppl.sh MODEL.gguf HELDOUT.txt     (HELDOUT: calib_text.py templates + check-replies.ts + build on gamma)
# TOOLS (default /dev/shm/tl/tools). Prints the final estimate line.
set -e
tools="${TOOLS:-/dev/shm/tl/tools}"
tmp="$(mktemp -d)"
"$tools/bin/llama-imatrix" -m "$1" -f "$2" -o "$tmp/x.gguf" --parse-special -c 512 -b 512 --chunks "${CHUNKS:-16}" \
  --ppl -t "${THREADS:-4}" -nr > "$tmp/log" 2>&1 || { tail -5 "$tmp/log"; rm -r "$tmp"; exit 1; }
grep -E 'Final estimate|\[[0-9]+\]' "$tmp/log" | tail -2
rm -r "$tmp"
