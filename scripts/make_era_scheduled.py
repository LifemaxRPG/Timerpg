#!/usr/bin/env python3
"""Builds docs/CHATGPT_ERA_SCHEDULED.md (the self-pacing scheduled-task prompt) from docs/CHATGPT_ERA_PROMPT.md."""
import re
p = open('docs/CHATGPT_ERA_PROMPT.md').read()
head = '''**Scheduled task: Timerpg era reference packs (one pack per run, 99 in all, then stop).**

Each run, do exactly this:
1. Look in the GitHub repository lifemaxrpg/timerpg, branch `content-inbox`, folder `content/inbox/era/`, for files
   named `era-<decade>-<topic>.json`.
2. Pick the NEXT missing pack in this order: topic `tech` for decades 1940, 1950, 1960, 1970, 1980, 1990, 2000, 2010,
   2020; then `speech` for the same nine decades; then `money`, `media`, `transport`, `shops`, `home`, `work`, `fashion`,
   `social`, `events` (each for all nine decades). That is 99 packs.
3. If all 99 already exist, write nothing, push nothing, and reply only: `ERA PACKS DONE - please turn off this
   scheduled task.`
4. Otherwise write that ONE pack following the instructions below, and commit it to branch `content-inbox` as
   `content/inbox/era/era-<decade>-<topic>.json` (e.g. `content/inbox/era/era-1960-tech.json`) with the commit message
   `Era pack <decade>s <topic>`. Never write more than one pack per run, never change other files, never push to any
   other branch. The file contains only the pack's JSON.
5. For "Already covered", read the packs that already exist for the SAME decade (any topic) and do not repeat their
   facts.

---

'''
i = p.index('## This run')
body = p[:i] + '''## This run

- Decade and topic: the next missing pack, chosen as in steps 1-2 at the top.
- Already covered: the facts in the packs that already exist for the same decade (step 5).
'''
body = body.replace('- Valid JSON only, no text before or after it.', '- The file you commit is valid JSON only, with no text before or after it.')
open('docs/CHATGPT_ERA_SCHEDULED.md', 'w').write(head + body)
