#!/usr/bin/env python3
"""Sums the summaries of several `vocab_test decode` runs (parts of one prompt set): sum_decode.py RUN.txt...
Server-side only."""
import re, sys
from collections import defaultdict

tot = defaultdict(lambda: {'nuc': 0, 'maxp': 0.0, 'top40': 0, 'tok40': 0, 'rank': None, 'where': '', 'mass': 0.0})
prompts = runs = steps = 0
secs = 0
for f in sys.argv[1:]:
    for line in open(f, encoding='utf-8', errors='replace'):
        m = re.match(r'prompts (\d+), runs (\d+) .*decode steps (\d+), (\d+) s', line)
        if m:
            prompts += int(m[1]); runs += int(m[2]); steps += int(m[3]); secs += int(m[4]); continue
        m = re.match(r'K=(\d+): steps with a top-p 0.9 nucleus token outside K: (\d+) \(max p ([\d.e+-]+)\); with a top-40 token '
                     r'outside K: (\d+) \((\d+) tokens\); best rank of any token outside K: (\d+) \((.*)\); max softmax mass outside K: ([\d.e+-]+)', line)
        if m:
            t = tot[int(m[1])]
            t['nuc'] += int(m[2]); t['maxp'] = max(t['maxp'], float(m[3])); t['top40'] += int(m[4]); t['tok40'] += int(m[5])
            if t['rank'] is None or int(m[6]) < t['rank']: t['rank'] = int(m[6]); t['where'] = f'{f}: {m[7]}'
            t['mass'] = max(t['mass'], float(m[8]))
            continue
        m = re.match(r'K=(\d+) chain: .*candidate sets differ at (\d+) of (\d+) steps; total variation max ([\d.e+-]+), mean ([\d.e+-]+)', line)
        if m:
            t = tot[int(m[1])]
            t['cdiff'] = t.get('cdiff', 0) + int(m[2]); t['csteps'] = t.get('csteps', 0) + int(m[3])
            t['tvmax'] = max(t.get('tvmax', 0.0), float(m[4])); t['tvsum'] = t.get('tvsum', 0.0) + float(m[5]) * int(m[3])
print(f'{prompts} prompts, {runs} replies, {steps} decode steps, {secs} s')
for k in sorted(tot):
    t = tot[k]
    print(f'K={k}: steps with an outside token in the top-p 0.9 nucleus {t["nuc"]} (max p {t["maxp"]:.3g}); '
          f'in the top-40 {t["top40"]} of {steps} ({100 * t["top40"] / max(1, steps):.1f}%, {t["tok40"]} tokens); best rank of an '
          f'outside token {t["rank"]} ({t["where"]}); max softmax mass outside {t["mass"]:.3g}')
    if t.get('csteps'):
        print(f'  chain (top-k 40, top-p 0.9, temperature) trimmed vs full: candidates differ at {t["cdiff"]} of {t["csteps"]} '
              f'steps; total variation max {t["tvmax"]:.3g}, mean {t["tvsum"] / t["csteps"]:.3g}')
