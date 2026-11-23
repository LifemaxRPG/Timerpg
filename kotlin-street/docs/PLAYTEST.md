# The playtest recorder (spec, 2026-11-23)

The owner plays on the S25; everything useful for tuning goes to the private `reports` branch
(`play/<date>/<session>.jsonl.gz`, plus `talk/` for conversations: CONVERSATION.md "The conversation log").

**Already recorded (keep):**
- crashes and Android's exit reasons;
- frame spikes with their causes, and a 10 s breadcrumb window (FrameSpikes, FrameWork);
- the NPU fit test;
- the overnight writer's runs;
- screenshots on request.

## To add, most valuable first
1. **A continuous session timeline, every second.**
   - Frame time: p50, p95, p99 and the worst frame, with GPU time and CPU time.
   - Where and what: town or world position, the year and hour, the camera height and zoom, the activity (walking,
     riding, talking, inside a building, menu).
   - What's on screen: draws, triangles, people and buildings near.
   - Spikes alone can't show slow-but-steady areas.
2. **Heat and power:**
   - the thermal status and ADPF thermal headroom (forecast);
   - the battery level, current (`BATTERY_PROPERTY_CURRENT_NOW`) and temperature;
   - charging or not;
   - CPU and GPU frequencies where readable.
   This gives energy per activity and the time to throttling.
3. **Performance traces on demand:** the "mark this" button (and any severe spike) also asks Android's
   `ProfilingManager` (Android 15+) for a system trace of the last seconds. That's a Perfetto trace with every thread,
   the CPU cores, frequencies and GPU work, for real root-cause work.
4. **Loading and streaming:**
   - cold start by phase;
   - the town build;
   - terrain chunk and building request-to-ready times;
   - the disk cache hit rate;
   - texture uploads;
   - every hitch tied to what streamed in it.
5. **Memory over time:**
   - PSS, Java/native/graphics split;
   - our own GPU and town accounting lines;
   - trim-memory callbacks;
   - how close the low-memory killer came.
6. **AI:**
   - the queue depth;
   - the pre-written bank hit rate vs template vs live;
   - question to first word and to the last word;
   - tok/s by unit (NPU/GPU/CPU);
   - the speech recognizer's latency and confidence;
   - voice (TTS) latency;
   - AI work overlapping heavy frames.
7. **Input to response:** touch-down to the first changed frame (feel), and camera-control smoothness.
8. **Settings in effect:** the quality tier and every auto-tuned value, the app version and build, so each number is
   tied to the configuration that made it.
9. **Play patterns** (for both tuning and design):
   - a heatmap of where the player goes;
   - time per activity;
   - who they talk to and what they use;
   - where they quit or get stuck.

## Rules
- Cheap: about 1 KB/s compressed, no allocation per frame, written off the render thread, uploaded when charging or
  on Wi-Fi.
- The data stays in the private repo.
- Claude summarizes each session in plain words for the owner and turns it into tuning changes and fixes.

## What's built (2026-11-23)

Code: `app/src/main/kotlin/play/` (PlayLog is the entry point); small hooks in GameActivity (start/stop, touch, trim,
the state and settings providers, the conversation turns, the 🔖 button), FilamentLayer (each frame, cold-start
phases, the town's build), TownSession (load and first view), DialogueEngine (model loads), the voices (TTS timing, the
phone recognizer's confidence) and ShotUploader (the upload). Tests: `app/src/test/kotlin/PlayRecorderTest.kt`.

**How it runs.** The render thread hands each frame to `PlayLog.frame` (fixed float arrays sized for 240 Hz: nothing
allocated per frame, about 50 ns a frame on the desktop JVM). A low-priority thread ("playlog") closes each second
(the percentiles sorted in a scratch array, the line built in a reused builder: 0 bytes allocated a second in the test),
asks the game screen's provider where the player is and what they do, and appends the line; every 30 s the lines go to
disk as one gzip member. A session is the game in front; it survives trips out under 5 minutes, and a killed process's
file is closed as it was at the next start (each member is whole). The 🔖 button (beside 📷, shown with "Screenshots to
Claude" on) marks the moment, asks for a system trace and opens the screenshot sheet for a note.

**Files** (the reports folder on the phone, sent to the `reports` branch; names with `~` for `/`; `open-` while written):
- `play/<date>/<HHmmss>-<versionCode>.jsonl.gz`: the timeline (gzip members; `zcat` reads it whole). One JSON object a
  line, `k` its kind, `t` ms since the session began:
  - `session` (first line): `id`, `start`, `app` (version, git, device, Android), `screen`, `traces` (supported),
    `recordTalk`, `format`.
  - `s` (every second): `f` frames drawn, `mv` of them moving, `ft`/`cpu`/`gpu` [p50, p95, p99, max] ms (the frame
    interval while frames flow; the render thread's CPU time; Filament's GPU time, each GPU frame once), `spk` frames
    over 20 ms, `dr` draws, `tri`/`triMax` triangles, `bv` buildings in view (FilamentTown.stats, averaged), `parts`
    FrameWork's parts (mean ms a frame, those over 0.05), `up` uploads by kind and `upKB`, `touch` touch-down to the
    next frame begun after it (ms; one sampled each 2 s at most), `act` (loading, walk, stand, ride, talk, inside,
    train, menu, paused), `town`, `st`, `hood`, `x`/`z` town position, `wx`/`wz` planet metres, `yr`, `hr`, `cam`
    [distance, pitch, yaw, height], `ppl` people drawn, `talk` NPC id, `room`, `ai` the AI lane at work, `aiQ` lines queued.
  - `pow` (every 5 s): `th` thermal status, `head` ADPF headroom forecast for 10 s, `bat` %, `mA` current, `mV`, `mW`,
    `degC` battery, `chg`, `plug`, `cpuMHz` by policy and `gpuMHz` where sysfs is readable (left out where not).
  - `mem` (every 5 s): `pss`, `java`, `native`, `gfx`, `code`, `stack`, `other`, `swap` (MB, Debug.MemoryInfo),
    `heapUsed`/`heapMax`, `free` and `lmkAt` (Android's available memory and its threshold: how close the low-memory
    killer is), `lvl` MemoryWatch's level, `psiSome`/`psiFull` memory pressure.
  - `accounts` (checked every 60 s, written when changed): GpuAccount's and TownMemory's lines.
  - `settings` (checked every 10 s, written when changed): renderer, view, town, resolution, shadow map, cascades, ASTC,
    camera options, display Hz, the AI's role, unit, model size and NPU tuning, voices, mic, recordTalk, the app.
  - `phase` (`n`, `ms` since the process started): process.init, activity.create, activity.content, filament.start,
    filament.drawing, town.firstView, town.built.
  - `span` (`n`, `ms`, `d`): town.read, town.firstView, town.loadToView, town.build, filament.start, model.load; any
    system adds its own with `PlayLog.span(name).end(detail)`, `PlayLog.span(name) { ... }` or `PlayLog.spanDone(name, ms)`.
  - `ai` (each NPC exchange): `src` bank/template/live, `firstMs`, `lastMs`, `totalMs`, `tok`, `unit`, `away`.
  - `asr` (`by`, `ms` after the player stopped, `audioMs`, `conf`); `tts` (`npc`, `engine`, `firstMs`, `audioS`,
    `madeMs`, `loaded`).
  - `trim` (onTrimMemory's level), `foreground`, `mark` (with the game snapshot), `markNote`, `trace` (requested,
    failed, or the file it became), `setting`, `patterns` (every 5 min and at the end: seconds per activity, the heatmap
    per town as [cell x, cell z, seconds] in 16-unit cells, use counts), `end`.
- `talk/<date>/<session>.jsonl` (with "Record my conversations" on, in the AI panel: the owner's build's default,
  `owner.build` in local.properties; off otherwise): `k` "turn" lines with `t`, `session`, `npc` {id, name}, `place`
  {town, building, room}, `year`, `hour`, `input` (spoken, typed, topic), `said`, `intent`, `topic`, `facts` {sheet,
  memories}, `asr` {by, latencyMs, audioMs, confidence}, `reply`, `source` (bank, template, live), `model`, `unit`,
  `waitMs`, `firstTokenMs`, `lastTokenMs` (both from the question), `tokens`, `tokPerSec`, `valid`, `why`, `tries`,
  `totalMs`, `walkedAway`; and `k` "tts" lines (npc, engine, firstSoundMs, madeMs, audioS). Transcripts only, never
  audio. The owner's build also turns "Night reports: with my words" on by default (CONVERSATION.md).
- `trace/<date>/<HHmmss>-<mark|spike>.perfetto-trace.gz`: a 10 s ProfilingManager system trace (Android 15+, reached by
  reflection: the project builds against SDK 34), asked for by 🔖 (30 s apart at least) and by a frame over 250 ms
  while moving in the town (5 minutes apart at least). The trace starts when asked: it shows what follows a spike, not
  the spike itself. At most 6 wait, each under 25 MB gzipped.

**Uploads:** on each start and at each session's end, with the token: `play/` and `talk/` on Wi-Fi or while charging,
`trace/` only on Wi-Fi and charging; at most 60 of each kind wait on the phone. No token or credential is written.

**Measured (desktop JVM, the unit tests; not the S25):** a synthetic hour of 120 Hz play is about 300 KB, 84 bytes a
second compressed (the budget is 1 KB/s; real sessions add events, settings and account lines); the per-frame path
allocates nothing and costs about 50 ns. On the phone the costliest read is `Debug.getMemoryInfo` every 5 s on the
recorder's thread (an estimate: tens of ms of one core's time each time), still to be measured on the S25.

**Not recorded yet (needs hooks):** terrain chunk and building request-to-ready times, the disk cache hit rate and
texture upload times (FilamentTown and TownRun under `shared/src/filament`: only FrameWork's per-frame upload counts
and bytes are read now); the dynamic resolution's current scale; per-fact ids in the fact sheets (the sheet is named by
NPC and topic); our own speech model's confidence (NativeAsr gives none); a typed question box (none exists yet:
"typed" means a question without the recognizer's timing); where the player quits or gets stuck (to be derived from
the timeline's last seconds and `act`).
