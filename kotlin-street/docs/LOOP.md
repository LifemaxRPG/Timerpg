# The first playable loop

The owner's decision (2026-11-23), on Sam's proposal, with Claude's agreement: before the planet fills with towns,
prove the game is fun in **a few blocks of the capital**. Planet-wide towns (TOWNS_EVERYWHERE.md) and the organic
street upgrade wait until this loop is playable on the S25 and the owner has played it.

## The loop, as the player lives it

1. **Arrive** in the capital with a little era cash in the wallet.
2. **Earn money**, either way:
   - legit: an odd job at a shop (a shift of sweeping or stocking or deliveries, paid at closing), or selling
     something you own at the pawn shop;
   - not legit: shoplift from a store, lift a wallet, take something from a yard or an unlocked house, take a car.
3. **Get noticed.** Anyone who could see you remembers what they saw (Jeff's witness core, G005): who, when, a
   description. Overnight, word spreads by traits and relationships (gossip); someone who would report it does,
   with the era's delay (G005 reporting).
4. **The town reacts**, and you can trace it to what you did:
   - the shopkeeper you robbed won't serve you, or watches you;
   - people mention it when you talk to them (the facts go into the Qwen fact sheets);
   - the police know: an officer patrols near the store, asks around, may recognise you and stop you (G009);
   - caught: arrested, fined or jailed for a night (the full trial, G010, comes later);
   - leave for a while and it cools down; leave the jurisdiction and you're not known there.
5. **Come back later** (sleep, or a day away) and the consequences are still there.

**Done means:** the owner plays it on the S25 for half an hour, earns money both ways, commits a crime someone saw,
and later meets a consequence they can trace back to it.

## How it is built

- **One source of truth for the rules.** The crime core, evidence, plans and police are TypeScript (`src/crime/`,
  `src/plan/`, `src/police/`). The phone runs that same code, bundled by esbuild into `assets/sim/sim.js`, in an
  embedded JavaScript engine (QuickJS-ng, in the native layer beside llama.cpp and Jolt). No Kotlin port to drift
  out of step. The desktop preview runs the same bundle the same way.
- **The game-facing API** (`src/loop/`, the facade the phone calls, JSON in and out):
  - `init(town, era, seed)`: the people, their homes, relationships, the jurisdictions;
  - `act(event)`: the player did something (bought, sold, worked a shift, took an item, was seen at a place);
  - `observe(sightings)`: who saw the player and how well (the renderer supplies line of sight, distance, light);
  - `advance(hours)`: gossip, reports, police plans, cooling off;
  - `query(...)`: what a person knows and thinks of the player (for dialogue and for shopkeepers), what the police
    know, active police plans to act out.
- **Kotlin owns what's physical:** the wallet and inventory, the interactions (buy, sell, take, work), line of sight,
  moving the officers through the streets, the arrest scene, saving.
- **Heat and battery:** the sim runs on its own thread in bursts (after an action, on the hour, while idle); nothing
  per frame.

## The blocks

The capital's civic centre and Main Street, with:
- a general store and a market (shoplifting, buying);
- the pawn shop (C010) on a side street, the police station (C009) near the courthouse: both placed in this intake;
- homes with yards on the next street (taking from yards, a house left unlocked);
- the drivable cars (C011–C013, C017), for taking a car.

## Who does what

- **Claude:** the JavaScript bridge and its tests, the Kotlin wallet/inventory/interactions and line of sight, the
  arrest scene, wiring it together, playtests and the APK.
- **Jeff:** G009 police in action, then G011, the `src/loop/` facade over his crime, evidence, plan and police code.
- **Sam:** the models the blocks still need (the gas station, C015, for the drive in; interior props for the shops).

## Open, for the owner when it's playable

- How harsh the first consequences should be (a fine and a ban from the shop, or a night in a cell).
- What the odd jobs should be.
