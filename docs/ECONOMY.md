# Era economy

Import the pure TypeScript API from `src/economy/index.ts`. The phone bridge can pass inputs and results as JSON. All money is in integer nominal cents for the requested calendar year. The supported years are 1880–2030 inclusive.

| Call | Example result |
|---|---|
| `priceIndex(1952)` | `0.085`, relative to 2025 |
| `priceIn('peaches', 1952)` | `23` |
| `stockOf('general', 1952, 'capital')` | Includes `{"itemId":"radio","label":"Table radio","category":"electronics","weight":"handheld","serial":true,"quantity":2,"priceCents":2995}` |
| `wage('odd_jobs', 1952)` | `{"hourlyCents":75,"shiftHours":2,"shiftCents":150}` |
| `pawnOffer('radio', 1952, false)` | `600` the shop pays the player |
| `pawnOffer('radio', 1952, true)` | `300` when the shop treats it as hot |
| `pawnPrice('radio', 1952)` | `1947` the player pays for a used radio |
| `fenceOffer('radio', 1952)` | `200` the back-room buyer pays |
| `formatMoney(23, 1952)` | `"23¢"` |
| `formatMoney(2995, 1952)` | `"$29.95"` |

Unsupported or non-integer years return `null`, or `[]` for stock. Unknown IDs or jobs and out-of-era items return `null`. An eligible item the buyer declines returns a zero offer. Sub-dollar amounts use the cents sign through 1979 and decimal dollars from 1980. Zero always displays as `$0.00`.

## Stock and resale

Use the same stable seed for repeated reads of a shop. Include the world seed and outlet ID in the seed string to distinguish outlets. Stock is a starting shelf snapshot. The caller owns purchases, depleted stock, restocking, and the pawn ledger. The `serial` flag says whether that year's item normally carries a serial number. The caller assigns and preserves the number on the individual inventory item.

Canonical shop kinds are `general`, `market`, `hardware`, `pharmacy`, `five_and_dime`, `pawn`, `gas_station`, `diner`, and `clothing`. Names accept spaces or hyphens in place of underscores, with aliases such as `general store`, `grocer` and `pawn shop`. An unrecognized shop returns an empty shelf. Pawn shops stock a small core plus a seeded selection of currently available goods. A retired item can remain in the player's saved inventory, but the catalogue provides no new listing or quote beyond its sale years.

A clean pawn offer uses the category's resale share. Offers of $5 or more round to a quarter, capped below retail. The hot offer halves the clean offer and the fence pays one third, both rounded down to a cent. A used shelf price is 65% of retail, rounded to a cent and bounded above the clean offer and at or below retail.

The eight job IDs are `odd_jobs`, `shop_clerk`, `labourer`, `mechanic`, `clerk`, `nurse`, `teacher` and `police_officer`. `laborer` also works. The hourly rate interpolates between separately authored wage anchors and rounds to a cent. Shift hours depend on the job.

## Calibration

The index and 230 catalogue entries are authored gameplay data. The reference price is a modelling input even when an item has gone out of sale by 2025. Linear interpolation fills years between anchors. The current model uses a common index across categories.

| Year | Coffee, cents | Canned peaches, cents | Odd jobs, cents/hour |
|---|---:|---:|---:|
| 1925 | 5 (4–7) | 18 (15–25) | 30 (25–35) |
| 1952 | 7 (5–10) | 23 (20–29) | 75 (65–90) |
| 1978 | 16 (12–21) | 56 (45–70) | 275 (250–320) |
| 2025 | 77 (65–130) | 271 (240–330) | 1500 (1400–1800) |
