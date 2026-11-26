# G021 Delivery — NPCs Remember What the Player Did, in Their Own Words

## Summary

Added a pure, deterministic, plain-JSON memory builder (`memoriesFor(personId, events, now, options?)` and `memoriesForJson(...)`) in `src/llm/` and wired it into `brief()`, `withRecent()`, `withMemories()`, and `validate()` in `src/llm/factsheet.ts`.

- **First-person memories from loop events (`src/llm/memories.ts`):** Converts plain `LoopMemoryEvent` lists covering witnessed crimes, heard rumours (with who told them), shifts worked together, police stops, arrests, trials, purchases/sales, and acts of kindness into at most **6** (`MAX_MEMORIES = 6`) grounded first-person sentences ranked by relevance.
- **Epistemic certainty & time fading:** Enforces strict certainty ordering (`seen`/`direct` > `heard_friend` > `heard_rumor`), decays certainty and relevance exponentially over time (with longer half-lives for serious crimes/arrests/trials than routine purchases), drops heavily faded low-salience errands, and softens wording with age (`on Tuesday` → `a while back` → `some time ago`). Also handles unconfirmed dim-light sightings (`recognizedPlayer: false` → `"someone who looked like you"`), `options.friendIds` friend-teller promotion, and deduplication when the same NPC both witnessed and later heard gossip about an event (seen wins).
- **Trait shading without invention:** Loyal friends (`relationToPlayer: 'friend' | 'family'` or `traits.loyalty >= 0.7`) play negative events down (`tone: 'downplayed'`), while gossips (`traits.gossip >= 0.65` or `voiceOf` gossip `charm >= 0.7 && diligence < 0.45`) play them up (`tone: 'embellished'`) while keeping heard-only events strictly hedged (`heardOnly: true`, never claiming first-hand sight). Every returned `MemoryLine` maps 1-to-1 back to its source `eventId` and never invents names, places, items, or numbers.
- **Brief & epistemic `validate()` guard (`src/llm/factsheet.ts`):** `brief()`, `withRecent()`, and `withMemories()` inject memory lines before the example answer (upgrading `"The player is a stranger."` only when direct/seen contact exists, and adding an epistemic reminder whenever heard-only memories are present). `validate()` / `whyRejected()` rejects replies that claim first-hand sight (`"I saw you..."`, `"I witnessed..."`, `"with my own eyes"`, `"I was there when..."`, `"I saw the whole thing"`, including tag questions like `"I saw you ..., didn't I?"`) for anything the NPC only heard about (`"claimed to have seen something only heard"`), while still allowing genuine denials (`"I didn't see it myself"`, `"I wasn't there when it happened"`) and allowing seen claims on events the NPC actually witnessed in mixed seen+heard briefs.

## Files changed

- `src/loop/types.ts` — exported plain-JSON event types only: `LoopMemoryEventKind`, `LoopMemoryWitness`, `LoopMemoryHearer`, `LoopMemoryEvent`.
- `src/llm/memories.ts` (new) — `MAX_MEMORIES`, `memoriesFor`, `memoriesForJson`, and plain-JSON memory types (`MemoryLine`, `MemoryNow`, `MemoryOptions`, `MemoryPersonTraits`, `MemorySource`, `MemoryTone`).
- `src/llm/factsheet.ts` — re-exports `memoriesFor`/`memoriesForJson`/types, adds optional `memories?: MemoryLine[]` on `Brief`, adds `withMemories()`, adds optional `memories` parameter to `brief()` and `withRecent()`, and adds the sight-vs-hearsay check in `validate()` / `whyRejected()`.
- `src/llm/memories.test.ts` (new) — 7 test suites covering witness vs friend-hearsay vs town-rumor vs stranger, all 8 event kinds, time fading / relevance ranking / max-6 cap / 1-to-1 `eventId` mapping, loyal-friend vs gossip trait shading, `brief()`/`withRecent()`/`withMemories()` + `validate()` epistemic guard (including mixed seen+heard briefs), unconfirmed sightings / `friendIds` / deduplication, and `memoriesForJson` plain-JSON round-trip.

## Verification

- **TypeScript (`tsc -p tsconfig.json --noEmit`):** 0 errors.
- **Vitest (`vitest run --no-file-parallelism src/llm src/town/stores.test.ts src/loop`):** **11/11 test files, 76/76 tests passed** in 32.67s:
  - `src/llm/memories.test.ts`: **7/7 passed** (47ms).
  - `src/llm/factsheet.test.ts`: **6/6 passed**.
  - `src/llm/conversation-log.test.ts`: **6/6 passed**.
  - `src/town/stores.test.ts`: **18/18 passed**.
  - `src/loop/` (7 test files): **39/39 passed**.
- **Kotlin `ValidateVectors` parity (`tsx kotlin-street/tools/validate-vectors.ts | diff -u kotlin-street/app/src/test/kotlin/ValidateVectors.kt -`):** **0 diff** (all existing validation vectors unchanged).
- **QuickJS-ng neutral ES2020 bundle (`esbuild src/llm/memories.ts --bundle --platform=neutral --target=es2020 --format=iife`):** **23,324 bytes (22.8 kb)**, `0` disallowed globals (`Math.random`, `Date.now`, `process.env`, `require(`, `fs.readFile`, `Buffer.`, `window.`, `document.`), verified in a bare Node `vm.createContext({})` sandbox via `memoriesForJson`.

## Usage

- Environment: sandboxed Linux container (`node v22.23.5`, `vitest 2.1.9`, `esbuild`, `tsc`, `tsx`).
- Branch: `gemini/G021-memories-in-facts` off `ccr-52e43ebb-xnmj2e`.
- One early WIP commit pushed (`1599553`) followed by final commit with edge-case refinements, tests, and delivery notes.

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   In `validate()`, distinguishing *which* memory a sentence refers to when an NPC has **both** a seen memory (e.g. saw a watch theft) and a heard-only memory (e.g. heard about an arrest) relies on grounded cue overlap (`MemoryLine.cues` vs the reply sentence's tokens). If a model writes an ultra-vague eyewitness claim like `"I saw it myself."` on a mixed seen+heard brief without naming any cue word from either event, our mixed-brief check won't know which event `"it"` refers to and allows it (whereas on a heard-only brief with zero seen memories, `"I saw it myself."` is always rejected). To make cue matching reliable across natural phrasing, we populated `cues` with both grounded event nouns (`placeName`, `itemLabel`, `charges`) and kind-level semantic roots (`arrest`/`picked`/`police`, `fire`/`burn`, `stole`/`took`, `court`/`trial`) while excluding teller names (like `Lois`) so attributing hearsay never collides with event cues.
2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - **Checked by running:** All 76 Vitest tests across `src/llm/`, `src/town/stores.test.ts`, and `src/loop/`, `tsc --noEmit`, `ValidateVectors.kt` parity diff, and a neutral ES2020 IIFE bundle of `src/llm/memories.ts` executed via `memoriesForJson` in a bare JS VM context.
   - **Only reasoned about:** Live small-model (Qwen3 4B on S25 NPU) reply phrasing when given these memory lines, and porting the new epistemic check from `src/llm/factsheet.ts` into `kotlin-street/app/src/main/kotlin/llm/Dialogue.kt` (untouched here because `kotlin-street/` is outside G021's allowed files).
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   They would notice that loop events now turn directly into natural, grounded first-person lines tailored to who the NPC is — `"I saw you take a watch from Fairchild's on Tuesday."` for the clerk, `"Lois told me you were picked up by the police, but I don't know if it's true."` for a friend who heard it, nothing for a stranger — and that `validate()` catches an NPC trying to pass off hearsay as something they saw with their own eyes.

## Ideas for the team

1. **Expose `memoriesForJson` on `TimerpgSim` / `simCall` and add a `loopMemoryEvents(sim)` extractor:** Since G021's allowed files only included `src/llm/` and `src/loop/types.ts`, wiring a small `eventsForMemories(sim)` helper in `src/loop/facade.ts` and attaching `memories.forPerson` on `src/loop/bridge.ts` in a tiny follow-up will let `kotlin-street` pull fresh `MemoryLine[]` straight out of `sim.js` every time the player talks to an NPC.
2. **Port `claimedSeenOnHearsay` to `Dialogue.kt` + `validate-vectors.ts`:** Once G021 and G024 land on the main branch, adding 4–5 hearsay-claimed-as-seen vectors to `kotlin-street/tools/validate-vectors.ts` and porting `CLAIMED_SEEN` / `NEGATED_SIGHT` to `Dialogue.kt` will keep the phone's Kotlin validator in 100% lockstep with `src/llm/factsheet.ts`.
3. **Feed `MemoryLine.tone` and `MemoryLine.kind` straight into G024's mood builder:** Because every `MemoryLine` already carries `kind`, `source`, `heardOnly`, `certainty`, `relevance`, and `tone`, G024 can compute the NPC's mood toward the player directly from those fields (e.g., direct witnessed crime vs loyal-friend downplay vs recent kindness/work shift) without re-parsing English text.
