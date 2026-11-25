#!/usr/bin/env python3
"""Our own text for the head vocabulary and the importance matrix (kotlin-street/docs/MODEL_FILES.md).

  game_corpus.py OUT_DIR EXPORT.json... [--src REPO/src] [--docs REPO] [--sample N]

From dialogue exports (tools/export-dialogue.ts <seed> <year> --all) writes:
  OUT_DIR/game.prompts   every distinct ChatML prompt, separated by lines "%%" (vocab_test reads it; tokenized with specials)
  OUT_DIR/game.txt       every template line, name, building and job, one per line (spoken text we know)
and with --src every string literal in the game's TypeScript (names, places, jobs the sim can generate) to
OUT_DIR/literals.txt, and with --docs every .md of the repository to OUT_DIR/docs.txt.
Server-side only (nothing here runs on the phone).
"""
import json, os, re, sys


def main():
    args = sys.argv[1:]
    out = args.pop(0)
    src = docs = None
    if '--src' in args:
        i = args.index('--src'); src = args[i + 1]; del args[i:i + 2]
    if '--docs' in args:
        i = args.index('--docs'); docs = args[i + 1]; del args[i:i + 2]
    every = 0
    if '--sample' in args:  # also OUT_DIR/sample.json: every Nth person of all exports, a dialogue.json for llm_host_test
        i = args.index('--sample'); every = int(args[i + 1]); del args[i:i + 2]
    os.makedirs(out, exist_ok=True)
    prompts, lines, picked, n_people = {}, set(), [], 0
    for f in args:
        d = json.load(open(f, encoding='utf-8'))
        for p in d.get('npcs', []):
            if every and n_people % every == 0: picked.append(p)
            n_people += 1
        for p in d.get('npcs', []):
            for k in ('name', 'first', 'buildingName', 'job', 'neighbourhoodName'):
                if isinstance(p.get(k), str): lines.add(p[k])
            for t in p.get('topics', []):
                if t.get('prompt'): prompts.setdefault(t['prompt'], f'{p["name"]}: {t.get("intent")}')
                if t.get('template'): lines.add(t['template'])
                for w in t.get('must') or []: lines.add(w)
        if isinstance(d.get('townName'), str): lines.add(d['townName'])
    with open(os.path.join(out, 'game.prompts'), 'w', encoding='utf-8') as o:
        for text, label in prompts.items():
            o.write(f'# {label}\n{text}\n%%\n')
    with open(os.path.join(out, 'game.txt'), 'w', encoding='utf-8') as o:
        for l in sorted(lines): o.write(l + '\n')
    print(f'{len(prompts)} prompts, {len(lines)} lines')
    if every:
        json.dump({'town': 'sample', 'year': 0, 'npcs': picked}, open(os.path.join(out, 'sample.json'), 'w', encoding='utf-8'))
        print(f'sample.json: {len(picked)} of {n_people} people, '
              f'{sum(1 for p in picked for t in p.get("topics", []) if t.get("prompt"))} prompts')
    if src:
        lit = set()
        rx = re.compile(r"'((?:[^'\\\n]|\\.)*)'|\"((?:[^\"\\\n]|\\.)*)\"|`((?:[^`\\]|\\.)*)`")
        for root, _, files in os.walk(src):
            for fn in files:
                if fn.endswith('.ts') and not fn.endswith('.test.ts'):
                    for m in rx.finditer(open(os.path.join(root, fn), encoding='utf-8').read()):
                        s = next(g for g in m.groups() if g is not None)
                        if re.search(r'[A-Za-z]', s): lit.add(s.replace('\n', ' '))
        with open(os.path.join(out, 'literals.txt'), 'w', encoding='utf-8') as o:
            for l in sorted(lit): o.write(l + '\n')
        print(f'{len(lit)} string literals')
    if docs:
        n = 0
        with open(os.path.join(out, 'docs.txt'), 'w', encoding='utf-8') as o:
            for root, dirs, files in os.walk(docs):
                dirs[:] = [d for d in dirs if not d.startswith('.') and d not in ('node_modules', 'third_party', 'build')]
                for fn in files:
                    if fn.endswith('.md'):
                        o.write(open(os.path.join(root, fn), encoding='utf-8', errors='replace').read() + '\n'); n += 1
        print(f'{n} docs')


if __name__ == '__main__':
    main()
