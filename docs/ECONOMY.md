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

Use the same stable seed for repeated reads of a shop. Include the world seed and outlet ID in the seed string to distinguish outlets. `stockOf` still gives the starting shelf snapshot. Use the stateful API below to track depletion and deliveries. The caller owns money, individual inventory, and the pawn ledger. The `serial` flag says whether that year's item normally carries a serial number. The caller assigns and preserves the number on the individual inventory item.

Canonical shop kinds are `general`, `market`, `hardware`, `pharmacy`, `five_and_dime`, `pawn`, `gas_station`, `diner`, and `clothing`. Names accept spaces or hyphens in place of underscores, with aliases such as `general store`, `grocer` and `pawn shop`. An unrecognized shop returns an empty shelf. Pawn shops stock a small core plus a seeded selection of currently available goods. A retired item can remain in the player's saved inventory, but the catalogue provides no new listing or quote beyond its sale years.

A clean pawn offer uses the category's resale share. Offers of $5 or more round to a quarter, capped below retail. The hot offer halves the clean offer and the fence pays one third, both rounded down to a cent. A used shelf price is 65% of retail, rounded to a cent and bounded above the clean offer and at or below retail.

The eight job IDs are `odd_jobs`, `shop_clerk`, `labourer`, `mechanic`, `clerk`, `nurse`, `teacher` and `police_officer`. `laborer` also works. The hourly rate interpolates between separately authored wage anchors and rounds to a cent. Shift hours depend on the job.

## Calibration

The index and 232 catalogue entries are authored gameplay data. The reference price is a modelling input even when an item has gone out of sale by 2025. Linear interpolation fills years between anchors. Retail prices multiply the common index by a relative category curve. Electronics fall in relative price as their product generations mature. The table radio and pocket calculator use their own curves. Food, fuel, tools, clothing, appliances and diner meals have separate anchors. Wages retain the occupation-specific curves.

| Year | Coffee, cents | Canned peaches, cents | Odd jobs, cents/hour |
|---|---:|---:|---:|
| 1925 | 5 (4–7) | 18 (15–25) | 30 (25–35) |
| 1952 | 6 (5–10) | 23 (20–29) | 75 (65–90) |
| 1978 | 16 (12–21) | 56 (45–70) | 275 (250–320) |
| 2025 | 77 (65–130) | 271 (240–330) | 1500 (1400–1800) |

## Persistent shelves

The bridge can expose the following calls as JSON inputs and outputs. Keep one serialized `ShopStock` per outlet, and supply its stable outlet seed on every restock.

| Call | Example |
|---|---|
| `openStock("general", 1952, "main-street-5")` | Save the returned `{"shopKind":"general","elapsedDays":0,"entries":[...]}`. The radio row has quantity 1 and price 2995. |
| `take(stock, "radio", 1)` | Returns a copy with the radio row at quantity 0. Save this result after buying or stealing. |
| `quantityOf(stock, "radio")` | Returns 0 on the depleted shelf or for an unknown item. |
| `restock(stock, 1, 1952, "main-street-5")` | Returns day 1 with the radio still sold out. Calling again with 6 days returns day 7 with three radios at 3048 cents each. |

`take` is all-or-nothing. Confirm sufficient quantity before charging the player or recording a theft. Invalid and oversized requests leave the stock unchanged. Persist the stock with the corresponding inventory and wallet changes.

Advance by whole elapsed days since the previous call. Delivery intervals are one day for markets and diners, three for pharmacies and gas stations, seven for general stores, hardware stores, five-and-dimes and pawn shops, and fourteen for clothing. A delivery resets quantities to seeded shelf capacity and refreshes the assortment from the supplied year. The caller should open the shelf for the destination timeline when switching timelines. If an advance crosses several deliveries in one year, only the last matters. Split advances using the same year and seed yield the same shelf as a single advance. Delivery prices vary from −3% to +5% of the current base quote, with all prices rounded to cents. Use the row's `priceCents` for checkout.

## Back-room buyers

These calls return JSON-safe facts for the game's dialogue and crime systems. Pass unique resident IDs and traits on a 0–1 scale. Examples below use an eligible adult named Vic from the game's resident roster.

| Call | Example input and result |
|---|---|
| `townFences(people, "capital")` | A roster containing `{"id":"vic","age":42,"job":"hardware clerk","honesty":0.2,"greed":0.8,"sociability":0.6}` can produce `{"personId":"vic","cover":"hardware clerk","where":"behind the hardware store","hours":[20,2],"categories":["valuables","hand_tools","power_tools"],"honesty":0.2,"greed":0.8}`. The list can be empty. |
| `fenceLeads(fences, people, "capital")` | A seeded lead may return `{"sourceId":"diner_regular","fenceId":"vic","where":"behind the hardware store","hours":[20,2],"willingness":0.75}`. The caller turns the fact into dialogue and decides when to reveal it. |
| `fenceDeal(fence, "pocketwatch", 1952, 0.4, 0)` | `{"accepted":true,"offerCents":210,"trustAfter":0.1}` for a stranger selling to Vic. Heat 0.9 returns `{"accepted":false,"reason":"too_hot"}` at trust 0 and is accepted at trust 0.8. |
| `fenceRisk(fence, 1952)` | `0.066` for Vic, a 6.6% chance per completed deal. Unsupported years return `null`. |

Hours are local, start inclusive and end exclusive. An end earlier than the start wraps past midnight. Cover and location describe an authored profile. The game must bind it to an available venue before exposing the lead. Resolve display names from the resident IDs.

Pass heat on a 0–1 scale. The game derives it from recency, local awareness and any active police case. Heat above 0.70 can require trust, which raises the acceptance ceiling up to 1 and softens the heat discount. Persist trust only after a completed exchange. Trust rises by 0.10, to a maximum of 1. An accepted quote is bounded below the clean pawn offer. The caller owns money, inventory and the crime report. Roll once per completed deal with a stable transaction key and persist its result. A fence transaction does not create a pawn ledger entry.
