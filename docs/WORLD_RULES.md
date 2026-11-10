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

## The scenario engine (`src/sim/scenarios.ts`)
Each year a few stories happen in every town (more in bigger towns). A story is a **template** (about 40: courtship,
affair rumors, fence disputes, shop rivalries, fires, robberies, embezzlement, inventors, lotteries, layoffs, thefts,
missing people, murder mysteries, mayor scandals, festivals, anniversaries, storms, floods, blights, blizzards, mine
accidents, epidemics, car crashes, benefit suppers, returning natives, mysterious strangers, inheritance fights,
reunions, strange lights ...) whose **roles are filled by the town's real people and shops**, whose **outcome is
rolled**, whose **wording** is assembled from phrase pools that change with the era, and whose **effects are real**:
reputations move, money changes hands, bonds break or form, shops lose capital, people die, newcomers arrive.
- **Open threads and sequels:** some stories leave a thread open (a missing person, an unsolved murder, a feud, an
  affair, a stranger's past, an estate fight); years later a follow-up story resolves it one way or another.
- **Fits the town:** the weights depend on the town type (mine accidents only in mining towns, floods in water
  towns), the year (polio in the 1950s, a pandemic in 2020, layoffs after 1975) and the population.
- **No loops:** a kind of story cannot recur within 4 years, and an exact repeat is marked "Again: ...".
- **Causal ripples:** who plays a role is the eligible person with the highest keyed hash, so removing someone in
  the past changes only the stories they were in.
- **How many:** `scenarioSpace(world)` counts the distinct stories the library can tell in a town (roles filled from
  the real population x outcomes x phrasings): over a million per town, before multiplying by the 76 years and by
  every town in the endless world. A town only lives through a few hundred of them.
- **Where you see them:** the 📰 **Gazette** button lists the town's stories up to the year on the slider, and
  people can pass news on when you ask what is in the news (the on-device AI can voice it).
