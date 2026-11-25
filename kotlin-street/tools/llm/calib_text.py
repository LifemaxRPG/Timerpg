#!/usr/bin/env python3
"""Calibration text for the importance matrix (kotlin-street/docs/MODEL_FILES.md, "Calibrated quantization").

  calib_text.py templates DIALOGUE.json OUT_replies.json
      the game's own template line of every voiced prompt as a reply, in the replies.json form that
      tools/check-replies.ts judges (so the templates go through validate() like a model's reply)
  calib_text.py build OUT.txt REPLIES.json...
      every reply validate() accepted ("ts" not null, set by check-replies.ts), after its full ChatML prompt and
      closed with <|im_end|>: exactly the token sequence the model sees and writes in play. Shuffled with a fixed
      seed so each 512-token window of llama-imatrix mixes people, topics and years.
Server-side only.
"""
import json, random, sys


def templates(src, out):
    d = json.load(open(src, encoding='utf-8'))
    reps, seen = [], set()
    for p in d['npcs']:
        for t in p.get('topics', []):
            if not t.get('prompt') or not t.get('template') or t['prompt'] in seen: continue
            seen.add(t['prompt'])
            reps.append({'label': f'{p["name"]}: {t.get("intent")} [template]', 'raw': t['template'], 'prompt': t['prompt'],
                         'must': t.get('must') or [], 'self': t.get('self') or '', 'template': t['template']})
    json.dump({'replies': reps}, open(out, 'w', encoding='utf-8'), indent=1)
    print(f'{len(reps)} template replies')


def build(out, files):
    convs = []
    for f in files:
        n = 0
        for r in json.load(open(f, encoding='utf-8'))['replies']:
            if r.get('ts'):
                convs.append(r['prompt'] + r['ts'] + '<|im_end|>\n'); n += 1
        print(f'{f}: {n} validated replies')
    random.Random(20261125).shuffle(convs)
    open(out, 'w', encoding='utf-8').write(''.join(convs))
    print(f'{len(convs)} conversations, {sum(map(len, convs))} characters -> {out}')


if __name__ == '__main__':
    a = sys.argv[1:]
    if a and a[0] == 'templates': templates(a[1], a[2])
    elif a and a[0] == 'build': build(a[1], a[2:])
    else: print(__doc__); sys.exit(2)
