# C023 — Jobs

Added seeded job openings, hiring and probation, and a saveable shift record with warnings, dismissal, raises and references. The 1952 hardware listing pays 60¢ per hour. [Jobs API](../../docs/JOBS.md)

## Verification

- `npm test -- src/jobs src/economy` passed 32 of 32 tests.
- `npm run typecheck` and staged whitespace checks passed.
- Hiring UI, shift scheduling and payouts still need game integration. Android and the full repository suite were untested.

## Self-review

1. Listings express vacancies for a fixed week. The caller tracks when someone fills one.
2. Tests checked era rules, the sample wage, hiring, duplicate shifts, dismissal and the raise.
3. A player with no reference gets a trial, and the second late shift costs the job.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, tokens and plan usage were not visible.
- Initial delivery, zero review rounds.
