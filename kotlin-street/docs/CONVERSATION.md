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
