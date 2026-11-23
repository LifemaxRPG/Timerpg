# Usage log: work done per usage window

The owner's plan-limited usage resets every 5 hours. We track how much finished work each window produces for the
tokens spent, so we can see whether the leaner way of working (CLAUDE.md, "Usage efficiency") gets more done.

## How it is measured

- **Tokens:** each agent's total from its completion notice (subagent_tokens), summed per window, with the model
  (Opus or Sonnet) noted; the main session's own share is not reported by the tools, so it is estimated and marked.
- **Finished work:** owner-visible items merged into the session branch in the window (a feature, a fixed screenshot
  issue, a converted building set), plus commits and lines changed as a rough second measure.
- **Rework:** items that had to be done a second time because the first try missed (counted against the window).
- Report: tokens per finished item, Opus share, rework count.

## Baseline: 2026-11-19, window from ~09:30 UTC (the old way: many parallel Opus jobs, many large renders)

| Job | Model | Tokens |
|---|---|---|
| Railroad (network, planner, trains, ride) | Opus | 655k |
| Farms round the town, crops, tractor wheels | Opus | 638k |
| Downtowns, pack buildings in towns, far view (resumed) | Opus | 568k |
| Landmark model loader (four models) | Opus | 494k |
| Farm pack buildings (resumed) | Sonnet | 436k |
| Bicycle (resumed) | Opus | 430k |
| Second screenshot batch fixes (resumed) | Opus | 382k |
| Screenshot voice notes and arrows | Sonnet | 118k |
| Still running at the end of the window: smoothness, building fixes, writer mode, rail+farms merge | Opus | not yet known |
| Main session (reviews, merges, many images) | Opus | not reported (large) |

- Known agent total: about **3.7M tokens**, ~85% Opus.
- Finished items merged: railroad (unmerged at window end: needs the merge job), farms, downtowns + far view, landmark
  loader, farm buildings, bicycle, screenshot fixes (4 issues), screenshot tools (2), wind sound (2 tries), night
  writer battery threshold: **about 10 owner-visible items**.
- Commits: 22 (non-merge) in the window, about 14.6k lines added.
- Rework: bike wind sound (2 passes); a merge-only job (rail + farms) caused by two parallel jobs on the same code.
- Rough rate: **~370k agent tokens per finished item**, mostly Opus.

## Next windows

Add a section per window with the same table and numbers, and compare.

## 2026-11-19, window from 14:30 UTC (first window on the leaner rules)

All four jobs were started in the morning window and resumed after the reset, so their totals below cover both
windows; the share spent after 14:30 is not reported separately.

| Job | Model | Tokens (whole job) |
|---|---|---|
| Smoothness: spike profiler, upload spreading, see-through, cutaway, haze/far/tree fades, night towers, bike camera | Opus | 639k |
| Building fixes: condo ground floors, apartment heights, house and trailer colours | Opus | 346k |
| Writer mode and the zero-lines bug | Opus | 323k |
| Rail + farms merge (one road set, track round fields) | Opus | 296k |
| Main session (merges, one regenerated file, small preview sheets only) | Opus | not reported |

- Agent total: about **1.6M tokens**, all Opus (these jobs were started before the Sonnet rule).
- Finished items merged: writer mode, zero-lines bug, condo ground floors, towers on residential streets, house colours,
  trailer colours, rail/farm roads and fields, stutter profiler and upload spreading, see-through only when hidden,
  camera out of the cinema, less eager cutaway, haze/far-box/tree fades, horizon line, night towers, clock at
  midnight, bike camera follow, BikeRideTest: **about 17 items**, one APK (0.1.423) at the end.
- Rough rate: **~95k agent tokens per finished item** (baseline ~370k), with the caveat above.
- Rework: none known yet (the phone has not tested this window's work). The rail+farms merge job itself was rework
  from the morning's parallel jobs on the same code.
- Lean rules followed: one APK, small downscaled sheets, merges done in the main session, no parallel jobs on the same
  files. Not yet followed: Sonnet for routine work (all four jobs predated the rule).

## Contributors (ChatGPT, Gemini) from the work queue

From 2026-11-22 each queue delivery reports its usage in DELIVERY.md (docs/queue/README.md). Claude records one
row per accepted or revised delivery here, so we can compare cost per finished item by worker and kind of work.

| Task | Worker (model, setting) | Kind | Tokens or effort | Rounds to accept | Verdict |
|---|---|---|---|---|---|
| C001 five-and-dime | ChatGPT (setting not reported) | model, 4 states + LODs | not reported (delivered before the usage rule) | 1 | revise: lettering clipped, roof texture |
| C001 five-and-dime r1 | ChatGPT (Codex, GPT-6 family; setting not visible) | model revision | 1 turn, ~40 tool calls, ~7 min | 2 | accepted |
| G001 moving landmarks (finish) | Gemini (AI Studio Build) | engineering | ~16 turns, ~35 tool calls, ~15 min (plus an aborted Filament source build) | 1 | accepted pending merge tests (strong root-cause work) |
| C002 grain elevator | ChatGPT (Codex, GPT-6 family) | model, 3 states + LODs | 1 turn, ~30 tool calls, ~9 min | 1 | accepted first try |
| G002 ocean prototype | Gemini (AI Studio Build) | engineering, new system | ~25 tool calls, 1 session | 1 | revise: water flipped vs boat, no horizon coverage, reads as clouds (engineering itself strong) |
| G002 ocean r1 | Gemini (AI Studio Build) | engineering, revision | ~14 tool calls, ~14 min | 2 | revise: projection, horizon and swell fixed; day glint is a solid white sheet; small fixes |
| G002 ocean r2 | Gemini (setting not visible) | engineering, revision | ~38 min push to push; tokens and tool calls not reported | 3 | accepted: waves with form, glint fixed; polish moves to the integration task |
| C003 ballpark | ChatGPT (Codex, GPT-6 family) | model, 4 states + LODs | 1 turn, ~30 tool calls, ~10 min | 1 | accepted first try |
| C004 schools | ChatGPT (Codex, GPT-6 family) | model, 4 states + LODs | 1 turn, ~30 tool calls, ~10 min | 1 | revise: abandoned state not abandoned enough, blank flat roofs, eras too alike |
| C004 schools r1 | ChatGPT (Codex, GPT-6 family) | model revision | 1 turn, ~38 tool calls, ~10 min | 2 | accepted (first delivery with a self-review) |
| C005 barbershop and beauty parlour | ChatGPT (Codex, GPT-6 family) | model, 6 states + LODs | 1 turn, ~25 tool calls, ~8 min | 1 | accepted first try (with self-review) |
| C006 truck stop | ChatGPT (Codex, GPT-6 family) | model, 2 states + LODs | 1 turn, ~32 tool calls, ~13 min | 1 | revise: bare lot, needs lot life, signs, era extras, night floods |
| C007 mini golf | ChatGPT (Codex, GPT-6 family) | model, 3 states + LODs | 1 turn, ~27 tool calls, ~10 min | 1 | revise: rigid rows, flat, closed state not closed |
| C006 truck stop r1 | ChatGPT (Codex) | model revision | ~14 min, tool calls not reported | 2 | accepted |
| C007 mini golf r1 | ChatGPT (Codex) | model revision | ~18 min | 2 | accepted |
| C011 early cars | ChatGPT (Codex) | 3 vehicles + LODs | ~15 min | 1 | accepted first try |
| C012 later cars | ChatGPT (Codex) | 4 vehicles + LODs | ~11 min | 1 | revise: the 2010s crossover reads as an 80s wagon (3 of 4 good) |
| G005 crime core (witnesses, gossip, police) | Gemini/Jeff (AI Studio Build) | engineering, new system | ~9 tool calls, ~7 min on the rebuild run | 1 | accepted first try; merged |
| C013 motorcycles | ChatGPT (Codex) | 3 vehicles + LODs | ~2 h 35 min elapsed incl. an interruption | 1 | accepted first try |
| C012 later cars r1 (the crossover) | ChatGPT (Codex) | model revision | ~10 min | 2 | accepted |
| G006 sign painter | Gemini/Jeff (AI Studio Build) | engineering, new system (no JVM in its sandbox) | not visible | 1 | accepted first try; compiled first time here, 7/7 |
