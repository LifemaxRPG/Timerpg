# Conversations that feel live (design)

Goal: back-and-forth spoken dialogue with NPCs that feels snappy on a phone, although the on-device models (via
llama.cpp) and the voice engine take seconds per line. Nothing the model says decides anything: it only voices facts the
simulation already decided (see `tools/export-dialogue.ts`, `src/llm/factsheet.ts`), and every line is validated.

## Two models: the bank and live replies

- **Bank** (the main model, the strongest that fits the phone): writes the lines ahead of time, mostly overnight with the
  game closed. Its NPU copy needs about 6.6 GB free with the margin, and beside the built town the S25 Ultra had about
  4.1 GB (measured), so it is not resident in play unless there is room.
- **Live** (a lighter model of the same family, Q4_0 for the Hexagon NPU, 2.4 GB): loaded on the NPU right after the town
  is built, it answers in play: what the player says, and lines the bank does not have yet. It does not write ahead:
  pre-generation is paused while it is loaded (the overnight writer fills the bank with the main model). Its NPU load
  needs about 3.7 GB free with the same margin.
- The choice is `ModelRoles.pick` (unit tested): in play, the main model on the NPU if its copy fits, else the light one on
  the NPU, else the light one on the CPU from its mapped file; behind the game, the main model (on the CPU from its file
  if its copy does not fit). Nothing loads while the town is built. The AI panel shows which is live, on which device and
  what each needs, with a switch to put the main model in play for comparison.

## Getting the live model onto the NPU (2026-11-22)

**The runtime.** The NPU path is llama.cpp's own Hexagon backend (`ggml-hexagon`, built from the Hexagon SDK 6.7 by
`app/src/main/cpp/CMakeLists.txt`: `libggml-hexagon.so` on the ARM side, `libggml-htp-v79.so` on the S25 Ultra's DSP),
not QNN, Genie or AI Hub context binaries. It takes a plain GGUF (Q4_0 is its best-supported format): no per-model
compile step. It copies and retiles every weight into DMA buffers shared with the DSP (`rpcmem_alloc2` on the system heap,
at most 1 GiB each), so the copy cannot be memory-mapped from the file (`buffer_from_host_ptr` is not implemented, and the
retiled layout is not the file's): it is real memory, about the file's size. The GPU path (OpenCL, Adreno kernels) copies
too. The CPU path maps the file (clean pages the system may drop).

**Why it ran on the CPU.** `ModelRoles.pick` asked for the copy's whole need in Android's *available* count
(`ActivityManager.MemoryInfo.availMem`, free plus page cache): the light model's 2.46 GB file, its own buffers (~150 MB
measured on a computer) and a 900 MB margin, ~3.6 GB. Beside the town the phone showed 2.0-3.6 GB, so the copy never
loaded and the CPU read the mapped file instead (`runsOn: "CPU"`). That count leaves out what Android hands a foreground
app on demand: cached background apps' private memory (the low-memory killer ends those first) and what compressed swap
(zram) can take. On a 12 GB phone that can be gigabytes, but no app can read it in advance: only a real load tells.

**What changed.**
1. *Device gate* (`llm/NpuGate`, unit tested): a copy may count what Android is expected to find beyond its free count.
   The figure is the least that Android really found in the last measured loads (the fit test's, and tries in play:
   `found` = the copy's memory less what the free count fell by); before any measurement, half the free swap, at most
   1.5 GB. A try that failed (the load, the valve, a kill during it, memory critical within 5 minutes) with about as much
   free memory stops further tries of copies that size for a week. `ModelRoles.pick` marks such a load `tried`: the light
   model's copy is tried before the CPU; in play the main model's copy is never a try while the light one's is there.
   Each try is marked on disk before the load (a kill is counted on the next start) and watched by `llm/LoadValve`
   (cancelled if free memory falls to Android's threshold + 250 MB). The AI panel's switch turns the gate off.
2. *Holding less at once*: what already existed stays (`llm/AiLane`: one heavy model at work at a time;
   `llm/ModelRoom.dropFor`: speech, memory, judge and voices let go for the copy when it is short), and the fit test
   measures each one. The context is already short (1024, 8-bit KV; the light model's KV is ~19 MB, being a hybrid with
   few attention layers); the prompt batch is now a setting (`ai` prefs `batch`: 512, 256, 128; the native engine's bits
   12-14) to shrink the compute buffers if the fit test shows they are big on the NPU.
3. *NPU, then GPU, then CPU*: an NPU load that fails for another reason than memory tries the GPU (when the build has
   OpenCL, which it does with `opencl.*` in local.properties) before the CPU; a memory failure goes straight to the CPU
   (the GPU's copy is as big). The AI panel's switch can make it NPU then CPU.
4. *The NPU fit test* (`llm/NpuFitTest`, `llm/FitReport`): AI panel and Writer mode. In the game it runs with the town,
   then switches to the old crossroads (the town unloaded) and back; in Writer mode it runs there. For each condition:
   memory by several measures (availMem, threshold, /proc/meminfo, swap, the game's RSS and PSS by part, memory pressure
   when readable), each resident model's MB, what letting the dialogue model and the others go freed; then each copy:
   light NPU (batch 512 and 256), the small model's NPU copy when its file is there, light GPU, light CPU (once), main
   NPU (where the light one's numbers say it can fit; always in Writer mode). Per copy: loaded or the runtime's error,
   load time, llama.cpp's buffers, the lowest free memory during the load, what Android found, prefill and generation
   tok/s on a fixed prompt of its own (cold, then warm), heat before and after, which device really ran. The report
   (npu/<date>/<time>.json, with `inPlainWords`) is on disk before every step, naming the step under way, so a kill is
   still a report. Its measured `found` calibrates the gate.

**A smaller live model, if the light one's copy still does not fit.** The same family's 2B (Qwen3.5-2B) in Q4_0 is
about 1.24 GB: its copy needs ~2.3 GB with the margin, inside every reading the phone gave beside the town. Steps:
- the file: a Q4_0 GGUF of the 2B (published ones exist; or `convert_hf_to_gguf.py` on the Hugging Face weights, then
  `llama-quantize --pure ... Q4_0`, the token embedding left Q8_0 or Q4_0); no Qualcomm tooling, no context binaries;
- put it at `filesDir/models/live-small-q4_0.gguf` (`ModelFiles.LIVE_SMALL`): the fit test tries it already;
- then: a download entry with its exact size (`ModelDownloads`, as the light model's), and a third choice in
  `ModelRoles.pick` in play (the light copy, tried or not, then the small copy, then the light file on the CPU), and its
  lines marked like the light model's (rewritten by the main one when it writes). The 4B and 9B stay for the bank.
- Check its replies with the host test (`tools/llm-host-test.sh`, `validate()`) before it goes in play: the 0.6B passed
  89%, the 9B 97%; a 2B is expected in between, which is what the distillation below is for.

**Distillation (design only).** Teach the small live model to talk like the main model, on our own prompts.
- *Data*: the main model's lines for the exporter's prompts (`tools/export-dialogue.ts`: every person, topic and year,
  plus free-question prompts made from the themes), several samples per prompt (temperature 0.7), each kept only if it
  passes `validate()` (the same check the game runs). Tens of thousands of pairs. Where: a PC GPU (a 24 GB card writes
  that in hours with llama.cpp's server); this server at ~3-5 tok/s would take weeks, so only a pilot of a few thousand.
  The phone's own bank (written overnight) is a free source too, its lines already checked: never the lines steered by
  the player's words, which stay on the phone.
- *Format*: JSONL, one `{"messages": [system (rules + fact sheet), user (the question), assistant (the line)]}` per line,
  the ChatML the game already sends, with the empty think block as in play.
- *Training*: LoRA (rank 16-32, attention and MLP projections, 1-3 epochs, lr ~1e-4) on the 2B's full-precision weights
  (PEFT/Unsloth on one GPU; a 2B fits a 16 GB card in bf16), merged into the weights, then GGUF and Q4_0 as above.
  Q4_0 after merging, not a LoRA adapter at run time (the Hexagon path wants plain weights).
- *Evaluation*: a held-out set of prompts (other people and years than the training ones): `validate()` pass rate (goal:
  at the 4B's or better), fact recall (the named facts present), length, repetition across samples, and a blind side by
  side against the 9B's lines on 100 prompts by the owner. Then the fit test on the phone for speed and memory.

## A spoken turn in play (the always-listening mic), and where its time goes

The owner's first test on the S25 Ultra (always-listening mic): the words took very long to appear and the reply took
"way too long". The turn is now:

1. **Hearing** (`voice/PlayerVoice`). By default the phone's own **on-device** recognizer hears the player
   (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12+; never the cloud recognizer: the words stay on the
   phone), with partial results: the player's bubble shows the words while they talk and only stops changing when the
   result comes. Always listening starts the recognizer again after every result, "no match" and timeout, waits out a
   "busy", stops it while an NPC's voice plays (so the game does not hear itself) and when the mic is switched off or the
   game pauses. Without on-device recognition (older Android, its language pack missing: asked for on Android 13+, or it
   keeps failing) the game's own speech model hears instead. The AI panel's "Speech: phone (on-device) / game model"
   switch chooses; the game's model has two sizes ("Game speech: small (fast) / large (accurate)", the small one by
   default: about a third of the memory). With the phone's recognizer our speech model is never loaded, so its memory
   goes to the dialogue model. Our own path cuts utterances with the voice-activity detector, now ending one after 500 ms
   of quiet (it was 700).
2. **Answer at once when one is ready** (`DialogueEngine.askFree`): a frequent theme's line written ahead (as before),
   then words that plainly ask about one of the person's topics (their family, work, the town, rumours, a secret) get
   that topic's line from the bank, with no model run. Small talk and anything else goes live.
3. **Live reply, a sentence at a time**: the model's reply reaches the bubble as each sentence completes, each checked
   as a whole line is (bar the facts it must name, checked at the end). A sentence that fails stops the model at once
   (the line is cut after its good first sentence, or tried again); two good sentences are a whole line (the check keeps
   no more), so the model stops there too. The first sentence is voiced as soon as it is shown. The template still
   stands in when nothing passes.
4. **Residency**: in play the light model answers; the main model is loaded in play only where its NPU copy fits (never
   from its file on the CPU, where a line takes tens of seconds); without the light model on the phone nothing loads in
   play and the AI panel says so with its own download button. The light model goes on the NPU in play even with the GPU
   chosen in the panel (the phone's owner had it set from a test). When the copy is just short of room, the models that
   can go are let go first (our speech model unless it is hearing, the memory model and its judge, the voice engines
   unless one speaks: `llm/ModelRoom`) and the choice is made again; the breadcrumbs say why the CPU was chosen, with the
   free memory and what the copy needed. The memory judge (2.5 GB) never loads beside the dialogue model; the memory
   model loads only with room (else a person recalls only the last exchange). The two voice engines are never both in
   memory: the one a line needs, the other let go first.
5. **Memory measured, not guessed**: the dialogue model's load reads llama.cpp's own buffer sizes (weights mapped or
   copied, the KV cache, the recurrent state, compute) and the next choice counts that instead of a flat 300 MB. The
   attention cache is in 8 bits (the AI panel's "Model cache" switch; the load falls back to 16 bits where it cannot).
   On a computer (CPU, measured): the light model's own memory is 163 MB with a 16-bit cache at 1024 tokens (KV 32,
   recurrent state 50, compute 81) and 150 MB with 8 bits (KV 19): the measured figure instead of the guess frees ~140 MB
   of the margin, which is what the phone's build 321 lacked (3621 MB free, 3663 needed with the guess, 3513 with the
   measured 150). The speech model: one output row (no batch of logits over the vocabulary), a 1024-token context and
   an 8-bit cache: the small one 762 MB mapped + 214 MB encoder + 91 MB of its own; the large one 2059 + 356 + 119.
   On the NPU the compute buffers will differ: the phone's numbers come in the breadcrumbs.
6. **Timing on screen and in the breadcrumbs**: each spoken turn logs "voice turn (<name>): heard N s after you stopped
   (who wrote it down, a speech model load first), memories N s, answer: ready/live/template · which model on which device
   · first token N s · rest N s (tokens, tries) · first words on screen N s · whole line N s after you stopped"; the AI
   panel shows the last one, the hearer's last timing, and a memory line: every model resident and its MB, and the
   game's own memory by part (Java, native, graphics, code, other), also in the breadcrumbs every 30 s when it changes.

## Four tiers, fastest first

1. **Cached line** - pre-generated in the background (text and, when voices are on, audio). Plays at once.
2. **Bridge** - when the answer is not ready: a short in-character filler, pre-made per NPC in their own voice, so it is
   instant. Chosen by topic ("My family? Well..."), personality (warm: "Oh, good question!"; gruff: "Hm. Why d'you ask?")
   and never the same twice in a row. It buys one to three seconds.
3. **Streamed answer** - the model's tokens are cut at the first clause (comma, full stop) and sent to the voice engine,
   so speech starts after the first clause, not after the whole line.
4. **Template** - if the model is not ready by a deadline (about 2-3 s after the bridge) or its line fails validation,
   the template line plays (always pre-made, text and audio).

## Making it feel natural

- **Look-ahead**: when a conversation starts, queue that NPC's likely next topics first (the topic menu is small);
  approaching or facing an NPC raises their priority. Background pre-generation otherwise follows the player's
  "render distance" (nearest buildings first, then the rest of the street, then the next street).
- **Human timing**: a small, varied pause (250-600 ms) before even cached answers; occasional reactions (hmm, a laugh,
  a sigh) chosen by mood.
- **Body language**: the NPC looks up or shifts while the model thinks, so waiting reads as thinking.
- **Free input** (typed or spoken, later): map it to the nearest prepared topic first; only genuinely new questions go
  live to the model, behind a bridge line.
- **Memory**: remember what was said (no repeated fillers or answers; "Like I said...").

## Scheduling

One worker owns the model and one owns the voice engine; they alternate so neither starves the game. Both pause during
the corner turn and other heavy animations, and back off when the battery is low or the phone is hot. Generated lines
and audio are cached on disk (text as JSON, audio compressed with Opus) keyed by the prompt, so they survive restarts and
time travel only regenerates the lines whose facts changed.

## The overnight writer and Writer mode (kotlin-street/app/src/main/kotlin/night)

The owner leaves the phone on the charger overnight; the dialogue model writes the bank of lines so the game needs
little live generation. Writer mode does the same on demand, from its own screen.

- **Why nothing was written before** (the phone's night reports of 2026-11-19): the writer ran in the game's own
  process beside the town, so only the CPU copy fitted (the main model's NPU copy needs ~6.6 GB, ~4 GB were free); it
  waited for the battery with the phone plugged in at 9%; it "cooled down" for most of an hour at a moderate thermal
  status right after playing; and (06:27: 79 min, 22.6 min of "writing", 0 tokens) the game going to the background
  left a "memory" hold in the engine (onTrimMemory BACKGROUND) that nothing lifted (the memory watch only speaks on a
  change of level): the model was loaded again, ready, and held. Fixed: with the game closed, background trim levels do
  not hold the writing (only real pressure unloads the model; the writer loads it again with the hold lifted); the loop
  counts time held by the engine apart from writing time; the report names the hold, the engine's state at the end, and
  says when not a single token was generated.
- **Why it writes nothing, said on screen** (after the owner's Writer mode run of 2026-11-23: minutes, no line, no
  report reached GitHub): every tick `WriterWhy` names the reason it is not writing (waiting for the charger, resting
  for heat, loading, the model failed to load or was let go, held by the engine, counting the lines, every line timed
  out, failed or was turned down by the check, or ready with no token generated for 3 minutes). Writer mode's screen
  shows it under the phase ("Not writing: ..."), the notification carries it, and the writer's process keeps it
  (`WriterStatus` in the "night" preferences) for the game's AI panel and the screenshot JSON (`diagnostics.writer`,
  stale after 2 minutes without a word). Lines given up for taking over 2 minutes and lines the model failed on were
  counted nowhere (0 lines, 0 turned down): they are counted now (`timedOut`, `failed`, `lastFailure`, in the report
  too). A writer that does not start (Android refused the service, the job's conditions) says so too. Every run that
  reaches the model writes its report (a short one too), before it lets go of `writer.lock`; the game, opened, waits
  for that lock and sends the report (the writer's own process is cached and frozen moments after it stops, so its
  upload may never finish). Writer mode no longer stops at once ("the game was opened") while the game's screen is
  still leaving the front: it waits up to 10 s for that.
- **Its own process** (":writer": NightService, NightJob, WriterActivity): no town, Filament, voices, speech or memory
  model. It closes the game's process when that sits out of sight (Writer mode at once; overnight after 10 minutes out
  of sight unless there is room beside it already), marked so the game's next start does not report it as a crash. The
  game's process tells it when it opens (a broadcast, and its process coming to the front): the writer stops, saves the
  bank, lets the model go and releases `writer.lock`; the game reads the bank once that lock is free (up to 20 s). Both
  processes save the bank under a file lock, each merging what the other saved since (`ReplyCache.save`). The writer
  process's breadcrumbs go to crumbs-writer.txt.
- **Which model**: writing the bank on a phone with an NPU, the main model's copy there, else the light model's copy
  there, never either from its file on the CPU (`ModelRoles.pick` with `writing`); the NPU whatever device the AI panel
  was left on. Lines the light model wrote are marked and rewritten by the main model when it writes (stage 5, before
  variants). The free memory in Writer mode (and what closing the game freed) is measured and shown on the screen and in
  the report: whether the main model fits there is still to be measured on the S25.
- **Writer mode** (the AI panel's "Writer mode" button, which closes the game, or its own launcher icon "Timerpg
  Writer"): starts writing at once; shows the phase, the model and device, free memory and each copy's need, lines
  this session and in the bank, lines/hour, prefill and generation tok/s, lines left with an estimated time, the queue
  by year, person and topic, the last lines with the speaker's name, heat (status, headroom, hottest), battery and
  charging. Start/Pause (the model stays loaded)/Stop, "Keep screen on (dimmed)" (dims after 20 s untouched) and "Write
  on battery too" (the owner's choice; otherwise only plugged in). A foreground service (specialUse), so it goes on with
  the screen off; the notification opens Writer mode, not the game. It stops cleanly when the game is opened.
- **When (overnight)**: plugged in (not "charging": Samsung's battery protection stops charging at 80-85% with the
  charger in), the game not in sight, the AI panel's switch on. Started as a foreground service when the game goes out of
  sight on the charger, or by a JobScheduler job (requires charging only: "battery not low" kept a phone plugged in nearly
  empty from ever running it; an old job with it is scheduled again) at the next charge. Android 12+ refuses a foreground
  service started from the background unless the app's battery use is "Unrestricted"; then the job writes itself for its
  own time (~10 minutes) and is run again. Samsung: set the game's battery to Unrestricted and keep it out of "Sleeping
  apps"/"Deep sleeping apps". Writer mode's screen starts the service itself (allowed: it is in front).
- **Battery**: plugged in, it writes at any level while the battery charges; under 15% it waits only when the battery is
  not charging or falls while it writes (a weak charger). On battery (Writer mode's choice) it writes down to 20%.
- **Heat** (`NightRules`: scheduling, not quality): at thermal status light or less it starts at once (no cool-down
  first) and writes in 8-minute bursts with 2-minute rests, with no rest while the headroom is known and under 0.75; at
  moderate, or headroom >= 0.9, it goes on in 90-second bursts with 4-minute rests (a warm rest ends early once cool); at
  severe it pauses (the line in progress stopped) until moderate; at critical it stops. Guesses, to be measured: the
  report records the time spent each way and the hottest status.
- **The report** (the `reports` branch, night/<date>/<time>.json, `kind` "night" or "writer"): lines, variants,
  rejected, lines/hour, tok/s, the model and device, the memory (free at start, freed by closing the game, before and
  after the load, each copy's need), time writing / loading / held by the engine / resting / warm / hot / paused /
  waiting for the battery, battery and charger at both ends, lines left and the estimate, the queue, the last lines
  (lines steered by the player's words stay on the phone unless "with my words"), the engine's state at the end, and
  `inPlainWords`: what was written and where the time went, in one sentence. The AI panel's "Last run" shows it too.
- **What, in order** (most needed first, see below): every prompt of the town not written yet; the player's patterns
  (follow-ups to recent conversations, the frequent themes for every person); variants, up to 4 lines per prompt,
  written back to back so the model's prompt cache covers the whole prompt (each costs only its generation); other
  years. A variant must pass validate() and say something new (not the same words); temperature 0.7 (0.3 for first
  lines); 4 failed tries and a prompt gets no more.
- **Other years**: the fact sheets are made by the TypeScript simulation (`tools/export-dialogue.ts <seed> <year>`), not
  on the phone, and the app has no time travel yet. The writer reads any `town/<town>/years/<year>/dialogue.json`
  shipped in the assets (none yet). Needed: either export the likely years at build time into those folders, or run
  the simulation on the phone (a JS engine or a Kotlin port of src/sim and src/llm/factsheet.ts); and time travel in
  the app to use them.
- **Staleness**: a line's key is a hash of its whole prompt (fact sheet, question, example) with the generation
  settings; it records its slot (town/year/person/topic). Changed facts change the key: the old line is not found
  (stale, counted, kept up to 4000 in case the facts come back), the new key is written again; nobody else's lines
  change. BankTest checks this on gamma's file.

## The player's conversation log

What the player says steers what is written ahead.

- **Record**: each exchange said or typed (the words, the reply, the person, town, year, topic, and whether a ready line
  answered it or the model went live) is kept in the person's memories (memories.json, at most 200 a person and 2000 in
  all), with the question's vector from the memory model when it is installed. Tapped topics are counted
  (player-stats.json: per person and topic, and ready/live/template answers per day). Nothing leaves the phone except
  counts and theme labels in the night report (the player's words only with "Night reports: with my words").
- **Learn** (`Themes`, `Steer`): the kind of question by keywords (directions, gossip, work, family, the past, prices,
  the town, opinions, personal, flirting, time travel); themes by clustering the questions (cosine >= 0.72 between
  vectors, or word overlap >= 0.5 without them; the same kind and the same subject word); a theme's weight counts a
  question that had to go live 3 times. The people the player talks to and their neighbours (same building half,
  the next building a quarter) and the topics they ask give the writer's order.
- **Steer**: the overnight order is by that need; each person's last exchange (14 days) gets a follow-up (their small
  talk with that memory in its facts: what they say next time, said once); the top 8 themes asked twice or more are
  asked of every person, in the player's own first phrasing, from that person's fact sheet.
- **Match in play**: words close to a theme (cosine >= 0.80, or word overlap >= 0.6; same kind; a shared subject
  word) get that person's ready line; otherwise the model answers live as before. The thresholds are guesses, chosen
  so that a wrong-topic answer is rarer than a needless live one.
- **Measure**: the AI panel and the night report show the top themes and the questions answered ready vs live per day.

## The conversation log (the owner's decision, 2026-11-23)

The owner opts in: their conversations with NPCs are recorded and uploaded with the playtest reports, to tune
the game. This turns on the existing "with my words" switch by default in the owner's builds. The switch stays, off by
default for any other player.
- **What's logged per turn:**
  - the player's words (the speech recognizer's transcript, plus whether it was typed or spoken);
  - the NPC, place, year and hour;
  - the fact-sheet ids used;
  - the reply, and where it came from (pre-written bank hit, template, or the live model);
  - the model, latency, tokens and tok/s;
  - the `validate()` result and any retry;
  - whether the player walked away mid-reply.
  Raw audio is not uploaded: the transcript only.
- **Where it goes:** the `reports` branch, under `talk/<date>/<session>.jsonl`, in the private repo only.
- **What it's for:**
  - The most-asked questions: cluster the player's lines by theme per NPC kind. Those prompts go first in the
    overnight writer's queue, so common questions get pre-written answers.
  - Training data for the small live model (Distillation above): the player's real questions, answered by the main
    model offline, and kept only if they pass `validate()`.
  - Finding failures: slow replies, rejected lines, lines that ignored the question.
