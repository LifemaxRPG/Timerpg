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
