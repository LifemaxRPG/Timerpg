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
