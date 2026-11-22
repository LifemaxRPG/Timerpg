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
- **Status:** `open`, `revise`, `accepted` or `closed`.

Below that come the brief and the acceptance checks.

- **Only Claude edits the queue.** Never change, add or delete anything under `docs/queue/`, and never commit to
  `ccr-52e43ebb-xnmj2e`.
- **Reviews:** when Claude reviews a delivery, the notes go in `docs/queue/reviews/<ID>-r<N>.md` and the task's status
  becomes `revise` (do the notes) or `accepted` (done; Claude brings it into the game).

## What to do each run (at most ONE task per run)

1. Fetch and read this README and the task list from `origin/ccr-52e43ebb-xnmj2e`.
2. **Revisions first:** if one of your tasks has status `revise`, read its newest review file and do that revision
   on the same branch.
3. **Otherwise, a new task:** take your lowest-numbered task with status `open` that has no branch on GitHub yet
   (check with `git ls-remote origin <branch>`; if the branch exists, it is in progress or delivered: skip it unless
   it is your revision).
4. **Do the task** exactly as its file says, and follow `docs/CHATGPT_MODEL_GUIDE.md` (models) or the task's own rules
   (engineering).
5. **Deliver:**
   - Push the branch. Then run `git ls-remote origin <branch>` and include the printed line in your final message. No
     line means not delivered.
   - In your delivery folder, add `DELIVERY.md` with: the task ID; what you made; anything not done; and for
     revisions, what changed and which review you answered.
6. If nothing is open for you, do nothing and stop.

## Hard rules

- **Models:** work only in `prototypes/<name>/` on your task branch. **Never** edit the game's live folders
  (`kotlin-street/app/src/main/assets/`, `src/`, `kotlin-street/shared/`, `kotlin-street/tools/`). Claude brings
  models into the game.
- **Engineering:** change only the files the task names; add new files where it says. Run the tests it lists and
  paste their summary in `DELIVERY.md`.
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
