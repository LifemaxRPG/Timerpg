# Ideas backlog (the owner's ideas, kept so none are lost)

Claude turns these into design docs when their pillar comes up (see CLAUDE.md, "Direction").

## Crime and the law (pillar 3)
- Grounded crime: anything a person could do in real life, with real consequences (2026-11-23).
- Witnesses remember what they saw (how clearly, what of you); they decide by traits and relationships whether to
  report, keep quiet or tell someone; gossip spreads through real relationships and distorts; whoever reports it brings
  the police in; each jurisdiction keeps its own case file; fleeing to another continent to hide out works unless word
  travels (bulletins, extradition, newspapers by era); ports and papers matter by era.
- Forensics by era: fingerprint cards (1920s+) matched by hand within a jurisdiction; automated fingerprint databases
  (1970s-80s) link old cards across jurisdictions (cold cases close); DNA (late 1980s+) from old evidence boxes;
  cameras (2000s) and phones (2020s). Gloves, wiping down and avoiding witnesses matter.
- Time-travel twist: prints left in 1955 by someone born later stay unmatchable for decades, until the player's later
  self is printed; detectives can't explain how the suspect was at a scene before they were born.
- Outcomes: arrest, trial, fines, bail, jail (time skipped or played), a record that follows you, lost reputation.

## Prison (pillar 3)
- Prison as a playable place, inspired by the escape-from-prison genre (2026-11-23): you're not stuck if caught.
  - Daily routine by the prison's schedule (roll call, meals, work duty, yard, lights out); our schedule simulation runs
    it.
  - Guards with patrols, shifts, memories and their own traits; some can be bribed, some can't.
  - Inmates are full NPCs with memories: friends, gangs, favours, trades. Informants can snitch (the gossip system again).
  - Contraband and crafting from everyday items (a spoon, a bedsheet, soap). Cell searches.
  - Escape routes: digging, vents, disguises (a guard's uniform), the laundry truck, a riot, an inside job.
  - Legit ways out too: serve the sentence, good behaviour and parole, a lawyer and an appeal, bail.
  - Escaping has consequences: a manhunt, a longer sentence if recaptured.
  - Prisons change by era (a 1940s county jail vs a 2020s state prison with cameras).

## The professor and the time machine (pillar 5)
- The time machine is always in the capital, kept by a professor; reaching the capital is an early goal.

## Trials with a jury (the owner's idea, 2026-11-23)

If the player is caught and charged, the case goes to court: a judge, a prosecutor, a defence lawyer and a jury of 12
real townspeople.
- **The evidence is what really happened:**
  - the police case file from the witness system (G005), with each witness's actual memory, confidence and wrong
    details;
  - the era's forensics;
  - anything the player left behind.
  Nothing is scripted.
- **Jury selection:** jurors are town NPCs with traits, relationships and opinions of the player's reputation. Some
  may know the player or the victim, and selection questions can strike them.
- **Testimony comes from memory.** Witnesses answer from what they really saw, so cross-examination can expose a
  mistaken detail ("you said a red coat"). The player can speak in their own defence by voice.
- **The lawyer depends on money:** a tired public defender or an expensive lawyer. Plea deals are offered by the
  strength of the case.
- **The verdict is decided by game logic:** the strength of the evidence, the jurors' traits and the performance in
  court. The language model voices the lawyers, witnesses and deliberation and never decides facts (as with all
  dialogue). Outcomes:
  - acquittal;
  - conviction and sentence (into the prison mechanics);
  - a hung jury and retrial;
  - appeal.
- **Crimes around the trial:** witness tampering, bribing a juror and perjury, each with its own consequences. The
  time-travel twist: go back and change the evidence before it exists.
- **By era:** 1880s circuit courts, mid-century trials, and modern trials with DNA and camera footage.
- **Performance:** a trial is a set piece. Lines are pre-generated while the player waits in jail (arrest to
  arraignment) and only follow-ups are live.

## Building your own structures (the owner's idea, 2026-11-23)

The player can build real structures, in a grounded way:
- **Land first.** Buy a plot through a land office or a realtor (the price follows the era, the town and the
  location), or rent one. The deed, zoning and building permits follow the era: no permit in 1890, inspections
  later. Building without one is a crime the town can notice.
- **Materials are real goods.** Buy lumber, nails, brick, cement, roofing, glass and pipe at the hardware store or the
  lumber yard at era prices, carry what fits (by hand, a cart, a truck bed), or order a delivery to your address,
  which arrives on a schedule by truck (or wagon, earlier). Stealing materials from a building site is possible, with
  the usual consequences.
- **Building takes work and time:**
  - foundations;
  - framing;
  - walls;
  - roof;
  - windows;
  - finish.
  Placed piece by piece on a grid that matches our pixel-art kit, in the same style as the town's buildings. Hire
  workers, or friends who owe you a favour, to go faster. Skill improves with practice.
- **Weathering through time.** Built in 1925 and visited in 2025, it has weathered by its materials and upkeep:
  - paint peels;
  - roofs sag;
  - wood greys;
  - vines climb;
  - an empty house is broken into, or squatted;
  - or someone bought it at a tax sale and changed it.
  Time travel makes this a mechanic, not just a look: build something in the past to have it in the future, or come
  back to find out what history did to it.
- **The world treats it like any building:**
  - it appears in the town's records;
  - neighbours have opinions;
  - it can be a home, a shop (a business for the money system), or a hideout (with the crime system's risks);
  - it can burn, be robbed, or be inherited.
- **Fits:** the money system (land and materials cost real money), the crime system (permits, theft, hideouts), the
  town plan (free plots are part of the capital's adaptable layout), and the 3D building kit we already have.

## Founding a settlement that grows into a city (the owner's idea, 2026-11-23)

Building your own structures scales up: the player can found a settlement and see it grow, by **work** or by **time**.
- **By work:**
  - claim or buy land;
  - build the first houses;
  - recruit settlers: drifters, families looking for land, workers you hire, people who owe you;
  - give them reasons to stay: jobs, a well, a store, safety, a church, a school;
  - bring in a road, then a rail siding;
  - get it chartered as a town, then elect a mayor (maybe you).
  Recruiting is real dialogue and real deals with NPCs, who have their own traits and needs.
- **By time:** found a hamlet in 1880, travel to 1980, and history has run on it. The simulation grows it (or doesn't)
  by its site:
  - water and land;
  - the roads and rail that came;
  - its industries;
  - booms, busts, fires and floods;
  - the choices of the people the player left in charge.
  A good site and a good start become a city; a bad one becomes a ghost town. The player can steer it by visiting at
  points in between.
- **The same engine as the world's own towns.** `src/sim` already simulates towns, people and history over decades,
  and the town generator already drafts layouts. A player settlement is a town the simulation grows from the player's
  seed buildings, with the generator adding streets and districts as it grows:
  - grid or organic, by era and terrain;
  - Main Street first, then neighbourhoods, then industry and a downtown.
- **Legacy:**
  - the founder is in the town's history;
  - a statue or a street name, if they're remembered well;
  - descendants of the first settlers;
  - the player's original buildings, weathered or preserved, at its old heart.
  It also fits the crime system: a settlement founded on crime grows a different kind of town.

### What makes a town thrive or fail (growth model draft)

One model for every town, the player's and the world's alike, so the world stays consistent. Each year the simulation
scores each town's pull (who moves in), hold (who stays), births and deaths, and new businesses from these factors.
The player can read a "town ledger" saying why it's growing or shrinking.

- **Site** (the ground itself):
  - **Water:** fresh water (river, lake, wells); drought risk.
  - **Land:** flat buildable land; flood risk.
  - **Soil and climate:** soil fertility; growing season and climate (from the climate model).
  - **Resources:** timber, stone, coal, ore, oil, fish.
  - **Defensibility,** in early eras.
- **Situation** (its place in the world):
  - **Crossroads:** a crossroads or river confluence; a natural harbour or a mountain pass.
  - **Transport:** distance to the nearest railway (a rail arrival is a boom); the highway (a bypass in the 1960s can
    kill a Main Street).
  - **Competition:** competing towns nearby that take trade.
  - **Status:** county seat or capital status.
- **Economy:**
  - **Jobs:** jobs and wages.
  - **Diversity:** a one-industry town booms and busts with its mine, mill or factory.
  - **Costs:** land and rent, taxes, business formation.
  - **Era events:** gold rushes, factory openings and closings, the Depression, the post-war boom.
- **Services and infrastructure:**
  - **Roads and links:** roads, the rail depot, the port.
  - **Utilities:** electricity, water and sewer.
  - **Public services:** schools, a doctor or hospital, a fire department, police.
- **Safety and governance:**
  - **Crime:** crime and policing, including the player's own crimes and gangs.
  - **Government:** corruption, the mayor's competence (the player, or the person they left in charge).
- **Disasters, by the real risks of the site:**
  - **Fire:** fire (wooden towns without a fire department burn).
  - **Water and health:** floods (river towns), epidemics (no clean water), droughts and crop failures.
  - **Geology and weather:** earthquakes and volcanoes near plate boundaries; storms on coasts.
- **People and culture:**
  - **Population:** families and birth rates, the age of the population, education.
  - **Life:** churches, saloons, parks, festivals.
  - **Reputation:** reputation (a lawless town drives families away), the founder's legacy.
- **Environment over time:**
  - **Damage:** soil exhaustion, deforestation, pollution.
  - **Recovery:** recovery and preservation.

This drives the world's own history too: which towns grew into cities, which emptied out. It is what a player's
settlement competes against.

## Government, elections and running for office (the owner's idea, 2026-11-23)

The simulation already elects mayors by public standing (`src/sim`: `World.mayor`, `Person.reputation`). This grows
it into real government at every level, with real NPC voters, and lets the player climb it, all the way to president,
realistically.
- **Offices by level:**
  - town council and mayor;
  - county sheriff, judge and commissioners;
  - state legislature and governor;
  - national legislature and president.
  Terms, term limits and eligibility (age, residency, citizenship) follow each era's rules in our fictional country's
  history. Who may vote widens over the century, as it really did.
- **NPCs really vote.** Each voter decides from:
  - their traits and interests: job, wealth, faith, family;
  - the issues: taxes, jobs, crime, the town's growth factors;
  - what they know and believe about the candidates: gossip, newspapers, speeches they heard, favours, scandals;
  - their relationships (family and friends vote alike, a feud votes against).
  Turnout depends on the weather, the stakes and the effort to get out the vote.
- **Campaigning is gameplay:**
  - money from donors (and what they expect back);
  - endorsements;
  - speeches and debates spoken by voice, with the language model voicing rivals and moderators while game logic
    scores the result;
  - door-to-door canvassing;
  - newspaper coverage by era (radio, then TV ads, then the internet);
  - opposition research.
  Start small: a council seat, then mayor, then up. A higher office needs the base, the money and the record from the
  ones below.
- **Office has power with consequences.** The player's laws change the simulation:
  - taxes, police funding, zoning;
  - building a rail line or a road;
  - corruption.
  The growth model then rewards or punishes the town, and voters remember.
- **Dirty politics are crimes.** Bribery, vote buying, fraud and blackmail all exist, and run through the witness,
  gossip and evidence systems: journalists, rivals and prosecutors dig, and scandals end careers or lead to trials.
- **Time travel as an edge, with risks:**
  - knowing what's coming: a bank failure, a flood, a war, an industry's rise;
  - investing ahead;
  - taking credit for being "right";
  - planting allies in the past, or building a family name over generations.
  Too-perfect foresight draws suspicion, and changing the past can change who your voters are.

## Simulation level of detail (the owner's note, 2026-11-23)

Simulate everyone, at the detail that matters:
- **Near (the player's surroundings, about 200-500 m):** full detail.
  - Minute-by-minute schedules, walking and pathfinding.
  - Seeing and hearing (the witness system).
  - Conversations on the language model, reactions.
- **Town (the rest of the player's town):** hourly.
  - Where each person is by schedule, not their exact steps.
  - Events resolved by rules and dice: who meets whom, who gossips, who reports a crime.
- **World (every other town):** daily to yearly, in aggregates.
  - The growth model, elections, births and deaths, businesses.
  - Named people keep their own ledgers (jobs, marriages, grudges, what they know).
  - Computed lazily: because everything is a pure function of the seed (`rand(seed, ...keys)`), a far town is caught up
    when the player arrives instead of ticking every second.
- **Rules that keep it honest:**
  - **One truth.** Moving a person between tiers never changes what is true. Someone seen in the town tier at the
    bakery at 3 p.m. is at the bakery when the player walks in.
  - **Facts are kept for everyone at every tier:** crimes, witness memories, debts, relationships. Only the
    moment-to-moment motion is coarse far away.
  - **News crosses tiers through people.** News reaches far towns through travellers, kin, newspapers and police
    reports (the G005 rules), at the pace the era allows.
- **Cost:** the language model runs only for people near the player. The town and world tiers are cheap CPU work,
  spread over idle frames, with bigger catch-ups done in the background or while charging.

## Mini games in the world's own places (the owner's idea, 2026-11-23)

Places in the world can be played, not just walked through. Each mini game lives in a building we already have, so it
fits the world rather than a menu:
- **Mini golf at Thimble Trails** (C007):
  - The course is built for it: 18 real cups, the pipe hole's anchors, the banked turn, the windmill rotor timing.
  - The ball runs on Jolt physics. Aim, power, and the windmill blades to time.
  - Par and scores; play with NPCs, who talk while you play; bets for money.
- **More of the same kind:**
  - batting practice and pickup games at the Alder Bend Ballpark (C003);
  - skating at the roller rink (C008);
  - pool and darts in bars;
  - fishing at the pier and on lakes;
  - card games in saloons and back rooms (gambling ties into money and crime);
  - races on the cars and motorcycles;
  - the arcade cabinets of each era.
- **Rules:**
  - short, era-correct, and optional;
  - each one connects to the rest: money, NPC relationships, reputation, crime (rigged games, illegal betting).

## Inventory and carrying (the owner's idea, 2026-11-23)

Grounded, like the rest: what you carry is limited by **weight and bulk**, where it sits matters, and what's visible
matters to the people who see you.
- **Two limits:** kilograms and litres, simple on a phone (a list with weight and space bars, not a puzzle grid).
  Rough capacities (tuned in play):
  - **Pockets:** about 2 kg and 3 L, hidden. Wallet, keys, cash, a knife, a phone in later eras.
  - **Hands:** one item each, or one big two-handed item (a TV, a crate), up to about 25 kg for short carries, and
    visible to everyone.
  - **Worn:** a belt and holster, coat pockets (more pocket space in winter coats), a shoulder bag.
  - **Back:** a backpack or rucksack by era, about 15-25 kg and 20-40 L, partly hidden.
  - **Carried containers:** a suitcase, a toolbox, a duffel bag, a briefcase, a cart, a wheelbarrow, a bicycle basket.
- **No encumbrance slowdown** (the owner's decision): carrying never slows you down. The limits are just what fits: when
  pockets, hands and bag are full, you need a bigger bag, a vehicle or a trip home.
- **Vehicles and places as storage:**
  - car trunks (the C011 cars already have `trunk` nodes) and the pickup's bed;
  - saddlebags on motorcycles and horses;
  - a home, a hidden stash, a safe, a bank deposit box, a rented storage unit, a hideout.
  Everything stored stays where you left it, and can be stolen, searched or found.
- **What you carry is evidence:**
  - Witnesses notice what's visible: a TV under your arm at 2 a.m. is remembered.
  - Police searches follow the era's rules: pat-downs, car searches, warrants.
  - Stolen goods can carry serial numbers and your fingerprints, and pawn shops (C010) may check them, by era.
  - Contraband by era: liquor in the 1920s, and so on.
- **Items are real things in the world.** Dropped items stay where they fall and NPCs may pick them up. Items have
  condition and age.
- **Time travel adds a twist:** an item from the future in the past (a phone in 1925) is a curiosity, a fortune, or a
  dangerous thing to be seen with. Era cash only works in its era.

### Caches across time (the owner's idea, 2026-11-23)

The time machine can only take what you carry. Anything bigger (a car, a crate of gold, furniture, documents you don't
want on you) can be **hidden in the past and collected in the future**, if it survives. So where you hide it matters.
- **The world decides, from what really happened to that spot:**
  - A house demolished in 1970 means the crew found what was in its walls.
  - Woods turned into a housing estate in 1985 means the dig turned it up.
  - A bank that failed in 1931 means the deposit box was seized.
  - A barn that stands untouched for 60 years means a "barn find".
  The growth model and the town's history (built, demolished, flooded, burned, dug up) are already simulated, so the
  result is determined, not luck. A careful player can learn which places last.
- **Decay by material and place:**
  - paper and cloth rot in damp ground;
  - metal rusts;
  - a sealed, dry container (a tin box, a waxed crate, a sealed jar) keeps things;
  - a car left outside rots, while a car in a dry, sealed barn survives.
- **If someone finds it,** an NPC acts on their own traits: they keep it, sell it, hand it to the police, or make the
  news ("1920s gold found in demolished house"). A found cache can become evidence, especially with your fingerprints
  on it.
- **If it survives, it's worth more.** Old items become antiques, a mint 1950s car is a collector's piece, and old
  currency is worth more to collectors than its face value.
- **Real tactics:**
  - buy the land first, so nobody digs it up;
  - hide it in a place that history protects (a church, a cemetery wall, a monument's foundation, the professor's
    basement);
  - check in on it at points in between.
- **When your own changes bury it** (the owner's idea): never scripted, never forced, never hinted at. It only
  happens when the simulation's own consequences put a building on the spot, so it's rare and comes as a surprise,
  and the systems below handle it as they would anything else. Something you did in the past changes the future, and now a
  building stands on your cache. You need the building to get it back:
  - **Buy it:** the owner is an NPC with their own traits, attachments and price. They may not want to sell; it's
    their family home, their business, or they get suspicious of why you want it so badly.
  - **Strike a deal:** by negotiation, a favour, a trade, a job for them, help with their problems, or leverage (with
    the crime system's consequences if it's blackmail).
  - **Or the other ways:**
    - rent it;
    - work there;
    - befriend them and get invited in;
    - break in and dig at night, with witnesses and evidence as usual;
    - or go back in time again and move the cache before the building goes up (if the timeline allows).
  - The dialogue model voices the negotiation, but the game logic decides whether they'll sell and at what price,
    from their traits, their money, their attachment to the place and their opinion of you.

## Limits on the time machine (decided by the owner, 2026-11-23: limit 1 only)

**Decision:** the only limit is limit 1, the machine stays in the capital. There's no cooldown or timer: how soon you
can jump again depends on how fast you can get back to the capital. Limits 2-5 below are not used as gates. They can
still appear as story flavour (the professor's opinions, a part that needs replacing in a quest), never as a cooldown.

The original proposal:

Free jumping would be too strong. These limits come from the story and the world rather than a timer. They are
layered, and each one is also gameplay:
1. **It's in the capital, and stays there.** Every jump starts and ends at the professor's lab. Living far away means
   a real journey (road, rail, sea) to use it. That's already the design; it's the first limit.
2. **Power.** Each jump needs the machine's cell fully charged, and longer jumps need more.
   - It charges from the local supply over in-game days, not real-time waits.
   - In eras without a grid you need another way: a generator and fuel, the mill's water wheel, the dam once it's
     built, or a lightning storm.
   - Big draws are noticed: lights dim across the block, the utility asks questions, and neighbours gossip about the
     professor (the witness and gossip systems).
3. **Worn parts.** A key component degrades with each jump. Replacements must be found or made in the right era: a
   valve from the 1940s, a crystal from a mine, a circuit from the 1990s. Better parts mean faster charging and longer
   reach. This is the machine's upgrade path, and a reason to explore eras and continents.
4. **The professor.** He calibrates every jump and has his own life, schedule, health and opinions. Your relationship
   with him matters; he may refuse a jump he thinks is reckless. In a changed timeline, he might not even be the same
   man.
5. **The ripple** (optional, for later). Many changes in a short span make the timeline's ripples harder to predict.
   A soft limit, never a game over.

In-game time is the cost, not real-time cooldowns. The player waits by living in the world, not by staring at a timer.

## Chains that grow across the century (the owner's idea, 2026-11-23)

Modern eras fill with chains, and the simulation can show one being born and spreading.
- **A chain starts as one business,** with an owner, in a town, at a time: a diner, a burger stand, a five-and-dime, a
  motel, a gas station. The growth model and the business simulation decide whether it succeeds, from its town, its
  owner's traits, its era's demand, and luck from the seed.
- **Successful ones expand:**
  - first to nearby towns along the roads and rail;
  - then by franchise across the continent;
  - and in later eras overseas, by the sea and air routes.
  Each new branch takes a lot in its town, sometimes replacing a local business (the Main Street story).
- **Chain identity.** One made-up name, one logo, one sign style and one building design (a standard model, painted
  with the chain's name rather than an owner's), changing by decade: the 1950s stand, the 1970s remodel, the 2000s
  redesign.
- **Visible history:**
  - the original first location becomes a landmark, sometimes with a plaque;
  - old branches close and become other businesses, keeping the shape of the chain's old building;
  - chains also fail, merge or get bought.
- **Time travel:**
  - see a chain's founder in their first year;
  - invest early, or work there;
  - buy the franchise rights in a new region;
  - or stop it (or start your own) and watch the map change.
- **Fits:** the growth model, the sign painter (chain names on standard models), the money system (investing, owning
  franchises), and Main Street through the decades.
- **The player's own businesses follow the same rules** (the owner's question, 2026-11-23: yes). A business you start
  runs in the same simulation as every NPC's:
  - the same demand, competition and growth model;
  - the same chance to become a chain;
  - the same chance to fail.
  You choose how hands-on to be:
  - work the counter yourself;
  - hire NPCs (with their own traits: a good manager, an employee who steals);
  - pick the location, prices and hours;
  - decide when and where to expand, and whether to franchise.
  A chain you founded spreads across the map like any other.
- **Time travel compounds it.** Found a diner in 1925 and come back in 1995 to a national chain, if it survived the
  Depression and the managers you left in charge. Its value, your shares, and your name on the first location's
  plaque all follow from the simulation.
- **Shady businesses too.** A business can be a front: laundering money, fencing goods, a speakeasy in the 1920s.
  These run through the crime and evidence systems like everything else.
