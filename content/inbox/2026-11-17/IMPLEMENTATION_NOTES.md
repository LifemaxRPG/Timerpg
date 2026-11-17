# Generated content handoff

Eight batches from the owner's ChatGPT conversation, 64 unique items: 16 stories, 42 rumors, 4 dialogue sets, 2 legends. See manifest.json for exact paths and IDs. Files retain the original saved JSON; separate run directories prevent same-date filenames overwriting each other. These are source drafts for Claude's review, not an already integrated runtime format.

## Suggested task for Claude

Read ../../../docs/CHATGPT_CONTENT_PROMPT.md and ../README.md, then review every file listed in manifest.json. Implement suitable content in the deterministic simulation and Android presentation, following the existing project architecture. Preserve source IDs for traceability and prevent duplicate imports. Report any rejected or edited items and why.

## Integration details to resolve

- src/sim/scenarios.ts currently expresses templates as TypeScript functions (Template, weight, make, apply); these JSON documents do not plug into it automatically. Use the existing keyed RNG, role selection, and effect/clamp conventions. chance values are design probabilities, not automatically interchangeable with the engine's relative weights.
- src/sim/types.ts has numeric wealth, reputation, and traits. Define consistent thresholds for textual eligibility bands. Bind distinct, living people; verify all age, spouse, sibling, parent/child and other relation constraints. parent_child has no direction in the source schema: read each story's era_notes and prose to bind the correct parent. In story-20261117-197 A is the parent; in story-20261117-210 B is the parent.
- Where A owns a shop, bind shop to that same person's open business of the requested kind. If a business kind does not exist in the game, adapt deliberately or reject; do not select an unrelated shop.
- Rumor text and its three variants describe allegations. true_chance must be resolved deterministically; belief effects must not make an unverified allegation become a world fact or repeatedly compound on every retelling. Feed NPC generation only appropriate knowledge and truth state.
- Stories can introduce small facts (objects, receipts, damage, public events) through their selected outcome. Record any facts needed for later dialogue; prose must not imply persistent facts the engine cannot support.
- Named thread buried_clock_face in story-20261117-186 needs an explicit mapping to the existing open/sequelOf story model. A sequel description is a proposal, not an executable prerequisite or a supplied sequel template.
- Legends need event-year and evidence gating. Do not let a resident recount an event that has not happened in the viewed timeline, or let a time-travel intervention leave stale clues.
- Some descriptions imply seasonal weather, autumn events, snow or a working church/mill. The source condition vocabulary cannot express all of these. Add actual world eligibility checks, adapt the text, or reject rather than assuming every generated town supports them.
- Headline checks cover template strings (<=70 characters), not arbitrarily long substituted names. Handle rendered lengths appropriately.
- Dialogue sets are fallback/style material, not newly established NPC facts. The generated secret_deflect set does not itself create a secret.

## Verification already performed

All eight files parse as JSON; each has eight items; all 64 IDs are distinct; story outcome probabilities sum to one; each story outcome has three headlines and three bodies; headline templates are at most 70 characters; every rumor has three variants. Runtime import, eligibility, factual grounding, replay determinism, NPC knowledge boundaries and Android behavior still require implementation and tests. No game code is changed by this handoff.
