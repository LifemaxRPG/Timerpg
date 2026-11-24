# C033 — Banks

Added savings and checking accounts with passbook entries, loans and delinquency, and calendar-aware bank hours and seeded cash snapshots. The 1952 savings fixture opens with $5 at 2%, and the payday drawer is about $3,000. [Banking API](../../docs/BANKING.md)

## Verification

- All 35 banking, calendar and economy tests passed. TypeScript and staged whitespace checks passed.
- Wallet transactions, cheque clearing, default enforcement and gameplay security still need integration. Android and the full repository suite were untested.

## Self-review

1. Loans use fixed 30-day installments and authored era rates. The game owns collection and collateral seizure.
2. Tests covered account terms, balances, passbook entries, approval, default timing, seeded cash and era features.
3. A player with $5 can open a 1952 savings account. The used-car loan also needs a job, collateral and a co-signer.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.
