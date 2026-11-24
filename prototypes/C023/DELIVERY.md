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

## Revision 1 — C023-r1

Ten consecutive on-time shifts now clear old strikes. A second strike before that still dismisses the worker, and being caught stealing still dismisses immediately. Added counter clerk openings for pharmacies, five-and-dimes, clothing stores and pawn shops.

Regression tests check the strike reset and vacancy coverage for every economy shop kind. All 34 jobs and economy tests, TypeScript, and staged whitespace checks passed. On-device integration was untested.

Revision usage was one interactive run with a WIP and final push. Model setting, tokens and plan usage were not visible.
