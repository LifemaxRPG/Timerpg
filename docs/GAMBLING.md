# Games of chance

Import from `src/gambling`. These are Timerpg game rules. All stakes and returns are nominal cents. Use stable action keys and save settlement with the wallet update.

| Call | Example |
|---|---|
| `suggestedStake("draw_poker",1952)` | `500` |
| `play("draw_poker",500,false,0.4,"town","hand-1")` | Resolves an authored hand tier and returns payout, net and a rigging observation. A strong hand returns 2200 cents gross. |
| `fixture("capital","ball_game",63,"town")` | Returns an event ID, entrants, probabilities, winner and baseball score. Horse races return a winner and null score. |
| `settleBet(event,"0",500)` | Returns gross payout, net, quoted multiplier and the 0.10 bookie cut. |
| `dailyNumbers("capital",63,"town","runner")` | Returns a three-character result, runner ID and payout multiple of 600. |
| `settleNumbers(draw,"012",5)` | Pays 3000 cents gross if 012 wins, otherwise zero. |
| `legality("slots","licensed_district",1952)` | Returns legal operation with a licence and legal participation at a licensed venue. |
| `debtStatus({debtorId:"player",creditorId:"runner",dueDay:63,remainingCents:500},70)` | Returns seven days late and a visit event. |

Poker tiers abstract a five-card hand. Dice, wheel and slots model a single roll. Crooked rounds reduce winning probabilities and may be noticed at higher skill. Gross payout includes returned stake. Net subtracts the initial stake.

Keep fixture results and daily draws private until their scheduled reveal. The caller schedules fixtures and publishes the same settled result to the paper and radio. Bet inputs must come from the generated fixture and draw. Licensing is an authored era table for town and licensed-district venues. The caller verifies the actual venue licence and applies criminal consequences. Unpaid debts move to warning after one day, visit after seven and escalation after fourteen. Save the last emitted threshold if triggering dialogue or world events repeatedly.
