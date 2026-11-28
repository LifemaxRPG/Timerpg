# G024 — Better Conversations: NPCs Who Follow the Talk & Keep Their Mood

Branched off `gemini/G021-memories-in-facts` (`5b6b33f`).

## Summary

Addresses all three failure modes observed in real playtest conversation logs (looping on one rumour 3 turns in a row, unprovoked `"shut up before I call the police"`, and high fallback rate to templates) cleanly inside `src/llm/`:

1. **Follow the conversation (`src/llm/conversation.ts` + `withConversation()` / `followUp()` in `src/llm/factsheet.ts`):**
   - `summarizeConversation(turns, maxTurns = 4)` compresses the last up to 4 turns into a bounded `Conversation so far: ...` line showing who asked what and what the NPC already told them, plus an explicit instruction to answer the player's current question now without repeating earlier replies.
   - `stripScaffoldFromSheet()` strips conversation summaries, told-topic markers, quoted prior replies, and mood lines before `validate()` checks name/place grounding, so player-invented nouns from earlier turns never leak into the fact sheet.
2. **Topic variety (`collectToldTopics()`, `formatToldGuidance()`, `repeatsToldTopic()`):**
   - Every rumour or fact told in prior turns is marked in the brief (`Already told in this talk: ...`).
   - When the player asks about the same topic again and a fresh untold fact (`freshFacts` / `freshMust`) is available, the brief directs the model to share the new detail instead. When no fresh facts remain, the brief marks the topic exhausted (`allowNothingMore = true`) so the NPC may say plainly they have nothing more (and may close or change the subject without false deflection rejection).
   - Stale `must` words belonging to an already-told rumour are automatically dropped from `b.must` so follow-up turns are never forced to keep naming the same person.
   - `validate()` rejects replies that retell an already-told rumour without adding a fresh detail or closing the topic (`'repeated an already-told topic'`).
3. **Fact-grounded mood (`deriveMood()`, `withMood()`, `isUnprovokedHostility()`):**
   - Computes `MoodState` (`mood`, `strength`, `reasons`, `allowsHostility`, `promptLine`) deterministically from familiarity, explicit attitude, G021 memories (`memories.ts`: witnessed crimes/arrests/trials vs police stops vs heard-only rumors vs kindness/purchases/work shifts), `wanted`, time of day (`hour`), voice manner (`voiceOf`), and what the player said (polite vs rude vs hostile/threatening).
   - States mood, strength, and a plain boundary directly in the brief (`Mood toward the player: polite (show it mildly — the player is a stranger). Stay civil; do not threaten, insult, or talk about calling the police.`).
   - `validate()` rejects unprovoked threats/abuse (`"shut up"`, `"call the police"`, `"get the hell out"`, etc.) as `'unprovoked hostility'` whenever `allowsHostility` is `false` (including for `grump` residents), while still allowing sharp warnings when grounded (`wanted`, witnessed crime, or provoked by a hostile player).
4. **Fewer false fallbacks without weakening quality checks (`check()` in `src/llm/factsheet.ts`):**
   - Trims to the first 2 sentences **before** enforcing the 320-char length limit (while rejecting >640-char runaway dumps first), saving grounded 3-sentence replies whose first 2 sentences are concise while still rejecting 400-char single-sentence walls.
   - Narrows `STAGE` so natural NPC speech (`"I haven't heard anything else"`, `"Like I say, quiet week"`, `"What can I say?"`) passes while AI-assistant offers (`"Glad to help. Let me know if there is anything else."`) and stage narration (`"A warm smile flickers in my eyes as I say yes."`) remain rejected.
   - Expands `HEDGE` with honest non-knowledge idioms (`"First I'm hearing of..."`, `"Beats me..."`, `"News to me..."`, `"Hard to say..."`) so denying a player-introduced place passes while claiming it (`"The new library is lovely."`) stays rejected.
   - Allows mid-sentence capitalized role/place words (`SHEET_GROUNDED_CAPS`, e.g. `"the Mayor"`, `"the General Store"`) **only** when their lowercase form appears as a whole word in the fact sheet, plus clause openers (`:`, `;`, `—`, `–`) and universal non-names (`Thanksgiving`, `Halloween`, `Heaven`, `Heavens`), while keeping invented names (`"Bob"`, `"Mr Lee"`, `"Jake"`) 100% rejected.
5. **24-conversation Before vs After evaluation (`src/llm/eval.ts`):**
   - Replays 24 multi-turn conversations across real simulated residents (`gamma` 2025 including Gladys Chen `imm:2021:1`, `alpha` 1990, `eta` 2025) through both a faithful pre-G024 baseline (`legacyCheck` + `freeQuestion`) and G024 (`followUp` + `validate`).

## Evaluation Results (Before vs After, 24 Multi-Turn Scenarios)

| Metric | BEFORE (pre-G024) | AFTER (G024) |
|---|---|---|
| Overall scenarios passed | 0/24 (0%) | **24/24 (100%)** |
| Brief expectations met | 0/24 (0%) | **24/24 (100%)** |
| Good replies passed | 14/24 (58%) | **24/24 (100%)** |
| False fallbacks (lower is better) | 10/24 | **0/24** |
| Bad replies caught | 15/22 (68%) | **22/22 (100%)** |
| Avg system prompt chars | 687 | 957 (+270 chars ≈ +65 tok) |

By category:
- `follow_conversation`: BEFORE 0/5 -> AFTER **5/5**
- `topic_variety`: BEFORE 0/5 -> AFTER **5/5**
- `mood_fit`: BEFORE 0/6 -> AFTER **6/6**
- `false_fallback`: BEFORE 0/5 -> AFTER **5/5**
- `quality_guard`: BEFORE 0/3 -> AFTER **3/3**

## Files Touched

- `src/llm/conversation.ts` (new — conversation summary, told-topic tracking, fact-grounded mood, hostility guard)
- `src/llm/factsheet.ts` (modified — `withConversation`, `withMood`, `followUp`, mood line in `brief()`, refined `validate()`/`check()`)
- `src/llm/eval.ts` (new — 24 multi-turn Before vs After evaluation suite & report formatter)
- `src/llm/conversation.test.ts` (new — 8 test blocks covering summary, told-topic marking, mood, false-fallback fixes, and the 24-scenario eval)
- `prototypes/G024/DELIVERY.md` (new)

## Verification

- `npx tsc -p tsconfig.json --noEmit`: **0 errors**
- `npx vitest run src/llm/`: **4/4 files, 27/27 tests passed**
  - `src/llm/factsheet.test.ts`: 6/6 passed
  - `src/llm/conversation-log.test.ts`: 6/6 passed
  - `src/llm/memories.test.ts`: 7/7 passed
  - `src/llm/conversation.test.ts`: 8/8 passed

## Usage

```ts
import { brief, followUp, validate } from './src/llm/factsheet';

const base = brief(ctx, 'rumors', template, [rumor1], ['Margaret']);
const nextTurn = followUp(base, 'Heard anything else about town?', {
  turns: [
    {
      player: 'Do you know any secrets about this town?',
      npc: 'Keep it quiet, Margaret Singh holds a grudge against Arthur Calloway.',
      topic: 'rumors',
      toldFacts: ['rumor:Margaret Singh and Arthur Calloway'],
    },
  ],
  currentTopic: 'rumors',
  memories, // optional MemoryLine[] from memoriesFor() (G021)
  hour: 15,
  keepMust: true, // stale "Margaret" is dropped automatically because it was already told
});

const cleaned = validate(modelRawReply, nextTurn);
```

Run tests and evaluation:
```bash
npx tsc -p tsconfig.json --noEmit
npx vitest run src/llm/
npx tsx -e "import { runConversationEval, formatEvalReport } from './src/llm/eval'; console.log(formatEvalReport(runConversationEval()));"
```

## Self-review

1. **Does it do what the brief actually asked, or an approximation?**
   It covers all five deliverables inside `src/llm/`: bounded multi-turn conversation summaries, told-topic marking with fresh-detail vs nothing-more branching, fact-grounded mood with strength & hostility boundaries (wired to G021 memories, familiarity/attitude, time of day, and player speech), targeted `validate()` fixes for real false fallbacks without loosening quality guards, and a 24-conversation Before vs After automated eval. Honest limitation: this sandbox has no local GGUF / `llm_host_test` binary, so the 24-conversation evaluation scores realistic scripted good/bad model outputs deterministically rather than live Qwen3-0.6B inference tokens.
2. **Would a careful human reviewer find anything sloppy, brittle, or missing?**
   Two honest caveats: (a) `kotlin-street/app/src/main/kotlin/llm/Dialogue.kt` and `DialogueEngine.kt` are outside G024's allowed files (`src/llm/`), so Kotlin's mirror of `validate()` and `ValidateVectors.kt` will need a matching port once G024 is reviewed and merged; (b) `HOSTILE_PLAYER` and `HOSTILE_REPLY` are English keyword/phrase regexes — they catch the actual playtest failure (`"shut up before I call the police"`) and common threats/insults reliably, but subtle sarcasm or creative novel insults won't match regex patterns without a classifier.
3. **What is the most likely next bug or integration snag when someone wires this up?**
   `brief()` now appends a `Mood toward the player: ...` line (+~120 chars), which changes the exported prompt hash/size for pre-written bank lines (`export-dialogue.ts` / `BankTest`) and requires `Dialogue.kt`'s `stripScaffoldFromSheet()` port on Android so `Mood toward the player:` / `Conversation so far:` text is stripped before Kotlin's name/place grounding scan.

## Ideas for the team

- **Port G024's `validate()` refinements & `followUp()` prompt builder to Kotlin (`Dialogue.kt` + `DialogueEngine.kt`)** and regenerate `ValidateVectors.kt` so on-device Android replies immediately get the lower fallback rate, told-topic tracking, and unprovoked-hostility guard.
- **Run `kotlin-street/tools/llm/eval-pass-rate.sh` against Qwen3-0.6B with the new briefs** on the desktop/phone host to measure live model pass-rate uplift on top of the deterministic 24-scenario benchmark here.
- **Pass `toldFacts` from the player's notebook (`TalkCtx.journal`) into `followUp()`** automatically so an NPC never retells a town rumour that the player already heard from them earlier in the same visit.
