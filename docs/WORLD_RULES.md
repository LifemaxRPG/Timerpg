# Town types and legends

## Town types (`src/sim/archetype.ts`)
Each town's seed picks a type, and the type changes the rules of its history:

| Type | Shops it favors | People moving in | Wealth | Water |
|---|---|---|---|---|
| Farm town | bakery, butcher | slow (0.6x) | low | - |
| Harbor town | bait shop, butcher | steady | slightly low | always |
| Mill town | clothing, bank | strong until 1980, then weak | average | always a river |
| Mining town | gym, bank, butcher | boom until 1975, then bust (1.7x -> 0.35x) | low | - |
| Resort town | ice cream, dress, clothing | slow until 1965, then 1.5x | high | always a lake |
| Commuter suburb | doctor, gym, bank, law | slow until 1960, then 1.8x | above average | - |

Town names are drawn from the type's own word lists. The type is shown on the arrival banner and under the year.
Tests (`archetype.test.ts`) check the effects on real histories, not just the labels.

## Legends and maps (`src/world/lore.ts`)
- Every town has **one legend**, fixed by the world seed, pointing at a **neighboring town** (walkable).
  The kind depends on the town type (a mining town tells of a mine collapse, a harbor of a wreck, ...).
- An old resident (40+) who trusts you (3+ chats) tells it when asked about local legends and gives you a **map**.
- 🗺 **Map** shows the towns around you. Towns you have not visited are "?", except targets of your maps.
  A 🧭 **compass** gives the direction and distance to the target, then to the exact spot inside the town
  (a landmark such as the clock tower, or the plaza fountain if that town lacks it).
- Standing at the spot uncovers the discovery, which goes in the 📓 notebook.
- Every legend is tied to a **year** (1952-1993). Before it, the old resident has nothing to tell, and the spot
  shows "nothing here yet": time travel decides when a legend exists.
- Legends are told in a town and found in the next one, so they chain across the endless world.
