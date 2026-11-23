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
