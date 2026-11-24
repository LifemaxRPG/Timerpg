# Player needs

Import from `src/needs`. Save the returned JSON state. Hunger and fatigue rise toward 1 as needs worsen. Hygiene and health are best at 1. Time is in game hours.

| Call | Example |
|---|---|
| `initialNeeds()` | `{"hunger":0.12,"fatigue":0.08,"hygiene":1,"health":1,"ill":false,"injuries":[]}` |
| `tick(state, 4, "walking")` | Advances hunger and fatigue and lowers hygiene. Activities are idle, walking, running, working and sleeping. |
| `eat(state, "diner")` | Reduces hunger by 0.37, down to a floor of zero. |
| `sleep(state, 8, "bed")` | Advances eight sleeping hours. Bench and cell recover fatigue more slowly than a bed. |
| `wash(state)` | Resets hygiene to 1. |
| `effects(state)` | Returns labels, a walking speed multiplier, an additive social reaction modifier and a doze probability per hour. |
| `injure(state, "sprain")` | Starts a 96-hour sprain and lowers health by 0.12. |
| `treat(state, "sprain")` | Doubles the recovery rate for the active sprain. |
| `treatmentCost("sprain", 1952)` | `300` nominal cents, or $3. |

Use either `tick` or `sleep` for a given time interval. A regular day with meals and a full night's sleep keeps needs manageable. From the initial state, skipping food at idle shows hunger by hour 11 and starvation by hour 23. Prolonged starvation or exhaustion reduces health and can cause illness. Recovery occurs gradually after relief. The game owns the doze roll, any injury triggered by an incident, and payment for treatment.

Meal IDs are `diner`, `grocery_cooked`, `boarding_house_board` and `snack`. The first three correspond to the C022 diner, cooked and boarding meal prices. The game's menu supplies individual prices, including breakfast. Injuries are sprains, cuts and bruises. Repeat injuries of the same kind restart that kind's timer.
