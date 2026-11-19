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

## The overnight writer (kotlin-street/app/src/main/kotlin/night)

The owner leaves the phone on the charger overnight; the dialogue model writes the bank of lines so the game needs
little live generation.

- **When**: plugged in (not "charging": Samsung's battery protection stops charging at 80-85% with the charger in),
  battery at 30% or more, the game not in sight, the AI panel's switch on. Started as a foreground service (type
  specialUse; dataSync is for transfers and limited to 6 h a day from Android 15) when the game goes out of sight on the
  charger, or by a JobScheduler job (requires charging, persisted) at the next charge. Android 12+ refuses a foreground
  service started from the background unless the app's battery use is "Unrestricted"; then the job writes itself for its
  own time (~10 minutes) and is run again. Samsung: set the game's battery to Unrestricted and keep it out of "Sleeping
  apps"/"Deep sleeping apps", or the job may never run.
- **Heat**: bursts (8 min writing, 2 min rest), cool-downs at a moderate thermal status or headroom >= 0.9 until light
  and < 0.75, a pause at severe (the line in progress stopped), a stop at critical. Guesses, to be measured: the night
  report records every pause and the hottest status.
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
