#!/usr/bin/env python3
"""Builds the pronunciation lexicon the Kotlin Kokoro voice uses on the phone (no phonemizer on the device).

Kokoro-82M was trained on the phonemes of misaki's US English dictionaries (gold, then silver); those are the lexicon's
core. Every word our game can say that they lack - the people's names, shop and town words, anything quoted in the
game's source and in the exported street and dialogue - is phonemized once here with misaki's espeak fallback (espeak's
IPA mapped to Kokoro's alphabet) and added. On the phone a line whose words are all in the lexicon is spoken by Kokoro;
anything else goes to Supertonic, which reads raw text.

Usage (in a Python env with misaki, phonemizer-fork and espeakng-loader):
  python make_kokoro_lexicon.py <repo root> <out .tsv>
Output: one "word<TAB>phonemes" per line, words lower-cased (the lookup lower-cases too)."""
import glob, json, os, re, sys

# espeak-ng from the espeakng-loader wheel (no system install needed)
import espeakng_loader
from phonemizer.backend.espeak.wrapper import EspeakWrapper
EspeakWrapper.set_library(espeakng_loader.get_library_path())
EspeakWrapper.set_data_path(espeakng_loader.get_data_path())

import misaki
from misaki.espeak import EspeakFallback

root, out = sys.argv[1], sys.argv[2]
data = os.path.join(os.path.dirname(misaki.__file__), 'data')

def load(name):
    d = json.load(open(os.path.join(data, name)))
    lex = {}
    for k, v in d.items():
        if isinstance(v, dict): v = v.get('DEFAULT') or next((x for x in v.values() if x), None)
        if v: lex[k] = v
    return lex

lex = {}
for name in ('us_silver.json', 'us_gold.json'):           # gold last, so it wins
    for k, v in load(name).items(): lex[k.lower()] = v
print('dictionary words', len(lex))

# the game's own words: anything quoted in the TypeScript source, plus the exported street and dialogue
words = set()
quoted = re.compile(r"'([^'\n]{1,200})'|\"([^\"\n]{1,200})\"|`([^`\n]{1,400})`")
for f in glob.glob(os.path.join(root, 'src', '**', '*.ts'), recursive=True):
    if f.endswith('.test.ts'): continue
    for m in quoted.finditer(open(f, encoding='utf-8').read()):
        for w in re.findall(r"[A-Za-z][A-Za-z']*", ''.join(x for x in m.groups() if x)): words.add(w.strip("'"))
for f in glob.glob(os.path.join(root, 'kotlin-street', 'app', 'src', 'main', 'assets', '*.json')):
    for w in re.findall(r"[A-Za-z][A-Za-z']*", open(f, encoding='utf-8').read()): words.add(w.strip("'"))

fallback = EspeakFallback(british=False)
class Tok:
    def __init__(self, t): self.text = t
added = 0
for w in sorted(words):
    k = w.lower()
    if not k or k in lex: continue
    if k.endswith("'s") and k[:-2] in lex: continue            # possessives are made on the phone
    ps, _ = fallback(Tok(w))
    if ps:
        lex[k] = ps.replace(' ', '')
        added += 1
print('game words added by espeak', added)

with open(out, 'w', encoding='utf-8') as fh:
    for k in sorted(lex): fh.write(f'{k}\t{lex[k]}\n')
print('wrote', len(lex), 'entries to', out)
