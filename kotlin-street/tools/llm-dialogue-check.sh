#!/bin/sh
# Real NPC replies through the app's own checks, on a computer:
#  1. every prompt the model may voice in app/src/main/assets/dialogue.json goes through the native engine
#     (tools/llm-host-test.sh --dialogue: the app's settings, prompt tokenization checked, timings);
#  2. the game's TypeScript validate() judges each reply (tools/check-replies.ts);
#  3. HostRepliesTest runs the app's DialogueData and Dialogue.validate() on them: the same prompts as the app sends, the
#     same verdicts as the TypeScript, and a report of what the player would see.
#   tools/llm-dialogue-check.sh [model.gguf] [llm_host_test options, e.g. --npc b0 --npc Ray --threads 2 --seed 7]
# Output in $LLM_OUT (default /tmp/llm-dialogue-check): replies.json, host-test.log, report.txt.
# Needs: cmake + a C++ compiler, ANDROID_HOME and gradle ($GRADLE, default gradle) for the Kotlin side, and tsx for the
# TypeScript check ($TSX, default "npx tsx"; skipped if it does not run).
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"   # kotlin-street
out="${LLM_OUT:-/tmp/llm-dialogue-check}"
mkdir -p "$out"
model=""
case "$1" in *.gguf) model="$1"; shift ;; esac

status=0
# shellcheck disable=SC2086
sh "$here/tools/llm-host-test.sh" $model --dialogue "$here/app/src/main/assets/dialogue.json" --json-out "$out/replies.json" \
  --no-checks "$@" > "$out/host-test.log" || status=$?
tail -n 8 "$out/host-test.log"
[ -f "$out/replies.json" ] || { echo "no replies written (see $out/host-test.log)" >&2; exit 1; }

# shellcheck disable=SC2086
if ! (cd "$here/.." && ${TSX:-npx tsx} kotlin-street/tools/check-replies.ts "$out/replies.json"); then
  echo "TypeScript check skipped (set TSX to a working tsx)" >&2
fi

(cd "$here" && LLM_REPLIES="$out/replies.json" LLM_REPORT="$out/report.txt" ${GRADLE:-gradle} :app:testDebugUnitTest \
  --tests '*HostRepliesTest' --rerun -q) || status=$?
[ -f "$out/report.txt" ] && tail -n 14 "$out/report.txt" && echo "full report: $out/report.txt"
exit $status
