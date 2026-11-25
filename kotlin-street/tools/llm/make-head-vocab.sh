#!/bin/sh
# Rebuilds kotlin-street/tools/llm/head_vocab.txt from scratch (kotlin-street/docs/MODEL_FILES.md, "The kept tokens").
#   make-head-vocab.sh MODEL.gguf [WORK_DIR]
# MODEL: any Qwen3.5 GGUF (they share one tokenizer; only its vocabulary is read). WORK (default /dev/shm/tl/hv): about
# 1 GB of text while it runs. Needs: the tools built by CMakeLists.txt here (TOOLS, default /dev/shm/tl/tools), node + tsx
# (the repository's), python3 with pyarrow, numpy and pyyaml (PY, default python3), llama.cpp's gguf-py (GGUF_PY).
set -e
model="$1"; work="${2:-/dev/shm/tl/hv}"
here="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$here/../../.." && pwd)"
tools="${TOOLS:-/dev/shm/tl/tools}"; py="${PY:-python3}"
mkdir -p "$work/export" "$work/eng" "$work/counts"

# 1. our own text: dialogue exports of seeds and years other than the app's (eta, gamma: kept for the held-out checks)
for s in alpha beta delta epsilon zeta theta iota kappa lambda mu nu xi; do
  for y in 1950 1975 2000 2025; do
    (cd "$repo" && ./node_modules/.bin/tsx kotlin-street/tools/export-dialogue.ts $s $y --all) > "$work/export/$s-$y.json" 2>/dev/null
  done
done
(cd "$repo" && ./node_modules/.bin/tsx kotlin-street/tools/export-dialogue.ts delta 1965 --all) > "$work/export/delta-1965.json" 2>/dev/null
"$py" -I "$here/game_corpus.py" "$work/game" "$work"/export/*.json --src "$repo/src" --docs "$repo"
"$py" -I "$here/game_corpus.py" "$work/heldout" "$repo/kotlin-street/app/src/main/assets/dialogue.json" \
  "$repo/kotlin-street/app/src/main/assets/town/gamma/dialogue.json"

# 2. English text (token counts only; nothing of it is shipped): PG-19 books (public domain), WikiText-103, TinyStories
hf=https://huggingface.co/datasets
curl -sSL -o "$work/pg19.parquet" "$hf/emozilla/pg19/resolve/main/data/train-00000-of-00023-5263dc4323e881d2.parquet"
curl -sSL -o "$work/pg19-test.parquet" "$hf/emozilla/pg19/resolve/main/data/test-00000-of-00001-29a571947c0b5ccc.parquet"
curl -sSL -o "$work/wt.parquet" "$hf/Salesforce/wikitext/resolve/main/wikitext-103-raw-v1/train-00001-of-00002.parquet"
curl -sSL -o "$work/tiny.parquet" "$hf/roneneldan/TinyStories/resolve/main/data/train-00000-of-00004-2d5a1467fff1081b.parquet"
curl -sSL -o "$work/eng/tiny-valid.txt" "$hf/roneneldan/TinyStories/resolve/main/TinyStoriesV2-GPT4-valid.txt"
pq() { "$py" -I -c 'import sys, pyarrow.parquet as pq
n=0; o=open(sys.argv[2],"w",encoding="utf-8")
for b in pq.ParquetFile(sys.argv[1]).iter_batches(columns=["text"], batch_size=256):
    for t in b.column(0).to_pylist():
        if t:
            o.write(t+"\n"); n+=len(t)
            if n>=int(sys.argv[3]): sys.exit(0)' "$1" "$2" "$3"; }
pq "$work/pg19.parquet" "$work/eng/pg19.txt" 300000000
pq "$work/pg19-test.parquet" "$work/eng/pg19-test.txt" 100000000
pq "$work/wt.parquet" "$work/eng/wikitext.txt" 250000000
pq "$work/tiny.parquet" "$work/eng/tiny.txt" 150000000
rm -f "$work"/*.parquet

# 3. token counts with the model's own tokenizer
t="$tools/vocab_test"; c="$work/counts"
"$t" count "$model" "$c/game-prompts.tsv" --special "$work/game/game.prompts"
"$t" count "$model" "$c/game-lines.tsv" "$work/game/game.txt" "$work/game/literals.txt"
"$t" count "$model" "$c/docs.tsv" "$work/game/docs.txt"
"$t" count "$model" "$c/pg19.tsv" "$work/eng/pg19.txt" "$work/eng/pg19-test.txt"
"$t" count "$model" "$c/wikitext.tsv" "$work/eng/wikitext.txt"
"$t" count "$model" "$c/tiny.tsv" "$work/eng/tiny.txt" "$work/eng/tiny-valid.txt"
"$t" count "$model" "$c/heldout.tsv" --special "$work/heldout/game.prompts"

# 4. rank (two passes: the second adds each word's line-start forms right after it)
hash="$("$tools/vocab_reorder" --hash "$model")"
rank() { (cd "$c" && GGUF_PY="$GGUF_PY" "$py" -I "$here/make_head_vocab.py" "$model" "$hash" "$1" --k 49152 --max 65536 \
  --must game-prompts.tsv game-lines.tsv --weigh tiny.tsv=1 pg19.tsv=1 wikitext.tsv=0.5 docs.tsv=0.25 --report heldout.tsv $2); }
rank "$work/pass1.txt" ""
"$t" forms "$model" "$work/pass1.txt" "$c/forms.tsv"
rank "$here/head_vocab.txt" "--forms forms.tsv"
echo "wrote $here/head_vocab.txt"
