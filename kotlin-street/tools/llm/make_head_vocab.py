#!/usr/bin/env python3
"""Ranks the Qwen3.5 vocabulary for English NPC speech and writes head_vocab.txt (kotlin-street/docs/MODEL_FILES.md).

  make_head_vocab.py MODEL.gguf VOCAB_HASH OUT.txt --k 49152 --max 65536 \
      --must game-prompts.tsv game-lines.tsv --weigh tiny.tsv=1 pg19.tsv=1 wikitext.tsv=0.5 docs.tsv=0.25 \
      [--forms forms.tsv] [--report heldout.tsv ...]

Order (each tier sorted by its score, ties by id):
  0. every control / user-defined token (ChatML, <think>, tools, vision: all specials), every one of the 256 byte
     symbols of the byte-level BPE, every single-character ASCII token and its space-prefixed form;
  1. every token of our own text (--must: dialogue prompts and lines of other seeds and years, the game's string
     literals), most frequent first;
  2. the rest by their share of each English corpus (--weigh FILE=W: sum of W * count / corpus tokens), most first;
  with --forms (vocab_test forms over a first pass's list): right after each word token " word", the tokens of
     "word", "Word" and "\"Word" with no space before them (a reply's first word, after a line break or a quote);
the first --max are written (ids in the original numbering); every other token keeps its original order after them.
Prints the coverage of each corpus and --report file for K = 32k, 48k, 64k. Needs gguf-py (llama.cpp's) (set GGUF_PY to its gguf-py directory).
Server-side only.
"""
import os, sys
from collections import defaultdict


def read_counts(path):
    c, total = {}, 0
    for line in open(path, encoding='utf-8'):
        if line.startswith('#'):
            total = int(line.split()[2]); continue
        i, n = line.split('\t'); c[int(i)] = int(n)
    return c, total or sum(c.values())


def bytes_to_unicode():
    bs = list(range(ord('!'), ord('~') + 1)) + list(range(ord('¡'), ord('¬') + 1)) + list(range(ord('®'), ord('ÿ') + 1))
    cs = bs[:]; n = 0
    for b in range(256):
        if b not in bs: bs.append(b); cs.append(256 + n); n += 1
    return {chr(c) for c in cs}


def main():
    a = sys.argv[1:]
    model, vhash, out = a[0], a[1], a[2]
    opts, key = defaultdict(list), None
    for x in a[3:]:
        if x.startswith('--'): key = x[2:]; continue
        opts[key].append(x)
    k_default = int(opts['k'][0]); k_max = int(opts['max'][0])
    if os.environ.get('GGUF_PY'): sys.path.insert(0, os.environ['GGUF_PY'])
    from gguf import GGUFReader
    r = GGUFReader(model)
    f = r.fields['tokenizer.ggml.tokens']
    toks = [bytes(f.parts[i]).decode('utf-8') for i in f.data]
    tt = r.fields['tokenizer.ggml.token_type']
    types = [int(tt.parts[i][0]) for i in tt.data]
    n = len(toks)
    byte_syms = bytes_to_unicode()

    tier0 = [i for i in range(n) if types[i] in (3, 4) or toks[i] in byte_syms or
             (len(toks[i]) == 2 and toks[i][0] == 'Ġ' and ord(toks[i][1]) < 127)]
    must = defaultdict(int)
    for p in opts['must']:
        c, _ = read_counts(p)
        for i, v in c.items(): must[i] += v
    score = defaultdict(float)
    corpora = {}
    for spec in opts['weigh']:
        p, w = spec.rsplit('=', 1)
        c, total = read_counts(p)
        corpora[p] = (c, total)
        for i, v in c.items(): score[i] += float(w) * v / total
    order, seen = [], set()
    def add(ids):
        for i in ids:
            if i not in seen: seen.add(i); order.append(i)
    add(sorted(tier0))
    n0 = len(order)
    add(sorted(must, key=lambda i: (-must[i], i)))
    n1 = len(order)
    add(sorted(score, key=lambda i: (-score[i], i)))
    print(f'tier 0 (specials, bytes, ASCII): {n0}; + our own text: {n1 - n0} -> {n1}; + English corpora: {len(order) - n1} -> {len(order)} ranked of {n}')
    if opts['forms']:
        forms = {}
        for line in open(opts['forms'][0], encoding='utf-8'):
            f = [int(x) for x in line.split('\t')]
            forms[f[0]] = f[1:]
        old, order, seen = order, [], set()
        for i in old:
            add([i]); add(forms.get(i, []))
        print(f'with the line-start forms after their words: {len(order)} ranked')
    if len(order) < k_max:
        add(range(n))  # pad with the rest in original order (never needed with the corpora above)
    order = order[:k_max]

    pos = {t: j for j, t in enumerate(order)}
    ks = [32768, 49152, 65536]
    reports = dict(corpora)
    for p in opts['must'] + opts['report']:
        reports[p] = read_counts(p)
    print('coverage of token occurrences (distinct tokens) within the first K:')
    print('  corpus'.ljust(44) + ''.join(f'K={k // 1024}k'.rjust(24) for k in ks))
    for p, (c, total) in reports.items():
        row = p.split('/')[-1].ljust(42)
        for k in ks:
            occ = sum(v for i, v in c.items() if pos.get(i, 1 << 30) < k)
            dist = sum(1 for i in c if pos.get(i, 1 << 30) < k)
            row += f'{100 * occ / sum(c.values()):9.4f}% ({dist}/{len(c)})'.rjust(24)
        print('  ' + row)
    with open(out, 'w', encoding='utf-8') as o:
        o.write('# timerpg head vocabulary v1: Qwen3.5 token ids (original numbering), most useful for English NPC speech first\n')
        o.write('# made by kotlin-street/tools/llm/make_head_vocab.py, see kotlin-street/docs/MODEL_FILES.md\n')
        o.write(f'# vocab_hash {vhash}\n# n_vocab {n}\n# k {k_default}\n# ranked {len(order)}\n')
        for i in order: o.write(f'{i}\n')


if __name__ == '__main__':
    main()
