# C040 — Pockets, bags and hiding places

Added a pure carrying module with outfit-derived pockets, fixed storage profiles, catalogue-backed fit and capacity checks, item visibility, and scoped, era-aware searches. The 1952 hand drill fits in a wool coat, makes a visible bulge and turns up in a pat-down. Storing it in the trunk puts it outside the pat-down.

## Tests

- `npm test -- src/carrying src/clothes src/economy` passed 37 tests across 7 files.
- `npm run typecheck` passed.

## Self-review

1. The weakest part is capacity tuning. The module uses size units and fixed profiles. The profiles are centralized so playtesting can tune them without changing inventory records or search logic.
2. Tests exercise the real clothes and economy catalogues, overcapacity, visibility, search scope and deterministic discovery. Live inventory, police wiring and confiscation remain unwired.
3. The player would first notice that the coat hides the drill itself while its outline still draws attention.

## Usage

- Model and reasoning setting, token counts and plan usage: not visible.
- Effort: one scheduled run. Exact wall time to push and aggregate tool-call count not visible.
- Rework: initial delivery, zero review rounds.
