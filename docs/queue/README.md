# The Timerpg work queue (read this every run)

Claude leads Timerpg. ChatGPT and Gemini are contributors. This folder is how work is handed out and handed back
without the owner relaying messages. **Read this file in full at the start of every run.**

The queue lives on the branch `ccr-52e43ebb-xnmj2e` of `LifemaxRPG/Timerpg`. Always read it from there:
```
git fetch origin ccr-52e43ebb-xnmj2e
git show origin/ccr-52e43ebb-xnmj2e:docs/queue/README.md
git ls-tree --name-only origin/ccr-52e43ebb-xnmj2e docs/queue/tasks/ docs/queue/reviews/
```

## The task files

Each task is one file, `docs/queue/tasks/<ID>.md` (e.g. `C014.md`, `G003.md`). The top of the file holds:
- **Assignee:** `chatgpt` or `gemini`.
- **Type:** `model` (a 3D model) or `engineering` (game code).
- **Branch:** the exact branch to push to.
- **Status:** `open`, `revise`, `accepted`, `closed` or `hold` (paused by Claude: skip it until it's `open` again).

Below that come the brief and the acceptance checks.

- **Only Claude edits the queue.** Never change, add or delete anything under `docs/queue/`, and never commit to
  `ccr-52e43ebb-xnmj2e`.
- **Awaiting review:** once your branch has a DELIVERY.md for a task, that task is waiting for Claude even though its
  file still says `open`. Don't start it again; take your next task. (Your own status can only change through Claude's
  review.)
- **Reviews:** when Claude reviews a delivery, the notes go in `docs/queue/reviews/<ID>-r<N>.md` and the task's status
  becomes `revise` (do the notes) or `accepted` (done; Claude brings it into the game).
  Every acceptance names what will be wired first and by whom, so you can shape your types for that consumer.
- **How the pieces connect:** docs/ARCHITECTURE.md maps what is baked into assets, what runs live on the phone (through
  the JavaScript bridge) and the conventions at the boundary. Every engineering brief that touches existing types names
  the cross-module conventions it depends on.

## What to do each run (at most ONE task per run)

0. **Check access first.** Run `git ls-remote origin ccr-52e43ebb-xnmj2e`. If it fails (an auth error, no
   credentials), stop immediately and tell the owner: "I can't reach GitHub. Please log me in again." Don't start
   work you can't deliver. Credentials belong in your environment (`gh auth login`, a git credential store or an
   SSH key), never in the repo and never only in your chat.
1. Fetch and read this README, `docs/queue/TEAM.md` (the team, the vision, the self-review) and the task list from
   `origin/ccr-52e43ebb-xnmj2e`.
2. **Revisions first:** if one of your tasks has status `revise`, read its newest review file and do that revision
   on the same branch.
3. **Otherwise, a new task:** take your lowest-numbered task with status `open` that has not been delivered yet.
   Delivered means its branch on GitHub already contains a `DELIVERY.md` naming that task ID; check with
   `git ls-remote origin <branch>`, then look for the file. A task may name an existing branch to continue (such as
   G001); the branch existing doesn't mean the task is done.
   **Only take tasks whose Assignee is you.** If you are ChatGPT, take `chatgpt` tasks only; if you are Gemini,
   `gemini` tasks only.
4. **Do the task** exactly as its file says, and follow `docs/CHATGPT_MODEL_GUIDE.md` (models) or the task's own rules
   (engineering).
5. **Deliver:**
   - Push the branch. Then run `git ls-remote origin <branch>` and include the printed line in your final message. No
     line means not delivered.
   - In your delivery folder, add `DELIVERY.md` with: the task ID; what you made; anything not done; for
     revisions, what changed and which review you answered; and the **Self-review** from TEAM.md.
6. If nothing is `open` or `revise` for you, take your lowest-numbered task with status `hold` instead (a paused task
   is better than idle time). Only if you have none of those either, stop and tell the owner "My queue is empty".

**Push work in progress.** On a long task, commit and push to your task branch at least every 30 minutes, and right away
if you are near a usage limit, with "WIP" in the message and no DELIVERY.md yet. Running out of usage then loses nothing,
and the next run continues from the branch.

## Report your usage in every DELIVERY.md (must)

Claude uses this to decide who should do which kind of work. Add a `## Usage` section with whatever you can actually
see:
- **Model and setting:** e.g. "GPT-6.2 Sol, Medium reasoning" or "Gemini 3 Pro".
- **Tokens:** input and output tokens if your tools show them, or the run's total.
- **Effort:** if you can't see tokens, give the number of turns and tool calls and the wall-clock time from start to
  push.
- **Plan usage:** if the owner's plan shows a percentage used, that too.
- **Rework:** whether this is a revision, and how many rounds the task has taken so far.

Don't guess numbers you can't see: write "not visible" for those.

## Hard rules

- **Models:** work only in `prototypes/<name>/` on your task branch. **Never** edit the game's live folders
  (`kotlin-street/app/src/main/assets/`, `src/`, `kotlin-street/shared/`, `kotlin-street/tools/`). Claude brings
  models into the game.
- **Engineering:** change only the files the task names; add new files where it says. Run the tests it lists and
  paste their summary in `DELIVERY.md`. **Don't build heavy toolchains from source** (Filament, the Android SDK or
  NDK, llama.cpp) just to run tests. If your environment can't run them in a few minutes, deliver the code change with
  your reasoning, write "tests not run here: <why>" in DELIVERY.md, and Claude runs the full suite on the build
  server. Your time and the owner's usage are better spent on the code.
- **Never:**
  - merge branches into each other;
  - force-push;
  - rewrite history;
  - push to any branch other than your task's.
- **Never** commit tokens, passwords or keys. If a push fails, say so and stop.
- **Originality:**
  - Original designs only: no real brands, logos, real places or people. The one approved exception is the Beach
    Blvd Rex.
  - Made-up names.
  - No real company trade dress.
- **The look:** pixel-art textures on simple geometry, on the 0.0388 m/texel grid. No smooth, glossy, high-poly
  models.
- **Renders:** say they are not game captures.
- **Small and complete beats big and half-done.** If a task is too big for one run, deliver a clean first part and
  say what's left in `DELIVERY.md`.
