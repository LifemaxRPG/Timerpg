# The stores (world side)

Where the town buys its groceries, through the decades: a general store in every town, a market downtown, a supermarket
out on the main road, and in the biggest towns a superstore at the edge of town. The TypeScript game decides everything
(which stores, where, when they open, close and are reused, their made-up names, what the people say about them); the
engine builds each era's building from street.json v2. Every name is made up; real chains never appear (a blocklist test).

## Which towns, and when

From the town's natural population history (`src/town/population.ts`: the people only, no time travel, 1950-2025,
cached per seed), so the plan stays a pure function of the seed:

| store | opens | building |
|---|---|---|
| **general store** (`generalstore`) | every town, built 1885-1935 | a wooden false-front (55%) or brick store, porch and bench, painted sign, barrels, crates, ice chest, a gas pump until 1975 |
| **market** (`market`) | the year the town reaches **MARKET_POPULATION = 125**, if before 1970 | the 1950s-60s supermarket: plate glass, a lit sign, a small lot, downtown on a block-sized plot |
| **supermarket** (`supermarket`) | from 1970, when the town reaches **SUPERMARKET_POPULATION = 160** (the market moves out), or **125** if it had no market | the 1970s-80s store: long glass front, flat roof, pylon sign, car park with cart corrals, at the edge of town |
| **superstore** (`superstore`) | from 1990, when the town reaches **SUPERSTORE_POPULATION = 215** | the big box (about 118 x 95 m) with a garden centre, loading docks, a car park for 650 cars |

Then, in `storeHistories` (`src/town/stores.ts`): the general store may change hands once (45%, 1952-1985, renamed); the
market closes the year its business moves out to the supermarket (`why: 'moved'`), or 1-3 years after the superstore
opens if it never moved; the supermarket closes 1-3 years after the superstore. The general store: in 1 town of 4 the
first supermarket (market or edge supermarket) takes its trade and it closes 2-10 years after that opens
(`why: 'supermarket'`); otherwise it hangs on until the superstore comes and closes 0-2 years after it (still the
commonest path in the towns that get one). One still trading in 1988-1998 in a town with no superstore turns, 60% of the
time, to a new trade (`renamed` with `trade`): "<Owner>'s Store & Deli", "<Town> Bait & Tackle" or "<Owner>'s Country
Store"; from 1975 every open one is a country store with soda signs and a propane cage (features `soda propaneCage`).
A superstore opened before 2005 (`DISCOUNT_UNTIL`) opens as the smaller 1990s discount store (`discount: true` on its
`opened` event, about 60% the width and 75% the depth of the supercenter, one entrance, its extension land still grass)
and, 80% of the time, is extended into the supercenter 6-13 years later, not before 2005 (`expanded`). A closed store stands boarded up / for lease, and 1-8 years later is
reused (general store: an antiques shop, a church, a cafe, a museum, a feed and seed store; market and supermarket: a
thrift store, a church, a gym, a flea market, a discount outlet, the community center), or (about 1 in 5) is left empty
for good. Closings and reuses may fall after 2025, so time travel forward finds them changed; nothing opens after 2025.

Of the 55 known seeds (the 15 named test seeds and water0-39): 55 have a general store, 40 reach 125 before 1970 and
35 get a market (5 find no block downtown, and keep their general store until the supermarket opens), 54 need a
supermarket and 53 get one, 27 reach 215 from 1990 and 26 get a superstore (water32: its school stands where the road
out of town would run, so it gets neither edge store and its market stays open). orbit (peak 113) never grows past its
general store.

## Scale: who the stores serve

The town's simulated people are its households' adults and children, about 215 at most (106 households); the stores
are sized as a real town's are. A store serves more than the town: the farms round it and the smaller places without
one. The rule of thumb behind the numbers: each simulated household stands for about 20 real ones within the store's
reach, so a superstore town (215 people) has a trade area of roughly 2,000 households, a small real county seat, which
is where such stores were built in the 1990s. The car parks fill by this (StoreTraffic below), and the NPCs say the
superstore draws people from the next towns. Not done yet (a world-map step): a per-region cap, so one superstore serves
a cluster of towns and the others talk of "the big store out by the highway in the next town over".

## Where

- `src/town/storeSites.ts placeDowntownStores`, after the civic landmarks and the cinema (they keep their places), before
  the building plots: the general store's front on the main street, as near the plaza as it fits (12 x 15 tiles, else
  14 x 13 or 10 x 18); the market on the main street if it can, about 30 tiles from the plaza (22 x 30, else 26 x 26,
  20 x 34, 30 x 22), inside the streets' extent.
- `placeEdgeStores`, after the plots: the supermarket (52 x 40 tiles) and the superstore (128 x 98 tiles) beside the
  main street where it runs on out of town past its east end (south of the road 70%, else north): the supermarket
  nearest town, the superstore across the road from it or beyond. The main street's last segment is lengthened past
  them; a side street it crosses is cut in two at the new junction and the plots it runs through give way. A town that
  needs them plans on a bigger canvas (80 tiles east and 50 south for a supermarket, 230 / 110 for a superstore) so they
  fit, and `plan.ts` grows the town (more `extra`) until every store has a site, like the cinema; when nothing fits it
  keeps the smallest plan that fits the stores, else the cinema, else the town.
- A plot tile is 1.24 m along the street (the art's 26.29 units) and 2.10 m back from it (44.5 units of position), so
  the plots are: general store 15 x 32 m, market 27 x 63 m, supermarket 65 x 84 m, superstore 159 x 206 m.
- The plan now needs 24 shop plots (was 20) and room for 106 households (was 100): the stores take a few of the main
  street's plots and the road out of town a few more (quartz, which ran out of homes in 2016-17 before, now fits;
  water11 and water29 still run out of homes around 2020, as they did before the stores).
- `src/world/map.ts CELL_W` is 520 tiles (was 360): towns with a superstore reach ~300 tiles east of their centre.

## gamma through the decades

| year | stores |
|---|---|
| 1955 | Haskell's General Store (1927, a wooden false-front, gas pump), on Main Street |
| 1975 | Haskell's, and Whitlock's Super Market (1968), downtown on Main Street |
| 1995 | Haskell's; Prairie Larder (1984, the market moved out to the edge of town, a made-up regional chain); the old market is Cornerstone Chapel (1985) |
| 2025 | Broadmeadow, the superstore (2015), across the road from Prairie Larder, which closed in 2016 and stands empty (for lease); Haskell's closed in 2015 and is Mapleridge Antiques & Collectibles (2016); the chapel |

gamma's plan grew for them: 422 x 218 tiles (was 273 x 197), the main street running on east past the town to the
supermarket (south of the road) and the superstore (north of it, across from it). The rest of gamma moved too: the
civic avenue's spawn row now has the hotel (the clock tower across), the v1 junction is at x 164 (was 136), and the
gardens district stands empty in 2025. The CINEMA.md numbers for gamma's rows and sprites are from before.

## NPCs

`src/llm/factsheet.ts storeFacts` (news and "about the town"; two sentences at most): where this person buys their
groceries (the newest open store, though while it stands the general store keeps 55% of the over-60s and 20% of the
rest), and one of the last four years' changes in the town's shopping (the superstore or the first supermarket opening,
the market moving out, a store closing after the superstore came, the old building's new use), else, for half of those
old enough to remember, that they miss the old general store; for some (30%) the old building's new use as somewhere
they go ("You go to church at Cornerstone Chapel, in the old Whitlock's Super Market building"); and, while the
superstore is new, what they think of it (blaming it for the old stores closing only once one has). In a town whose
every store has shut, people drive to the next town over and say since when.

## street.json v2

`kotlin-street/tools/town-stores.ts` (hooked into tools/town-graph.ts):

```
graph.landmarks[]: {id:'landmark:superstore', kind:'superstore', name:'Broadmeadow', sprite:'b102', art:'landmark_store_superstore',
  row:'12L', rect:[x0,z0,x1,z1], store:{site:'superstore', kind:'superstore', state:'open'},
  footprint:{tilesW:128, tilesD:98, width:3364.571, depth:4361}}            // street units: along at art scale, back at position scale
graph.stores[]: {id:'store:generalstore', landmark:'landmark:generalstore', site:'generalstore', kind:'general', style:'frontier',
  state:'reused', name:"Haskell's General Store", sign:'Mapleridge Antiques & Collectibles',
  reuse:{kind:'antiques', name:'Mapleridge Antiques & Collectibles'}, why:'superstore', opened:1927, closed:2015, year:2025, seed:12345,
  features:['porch','bench','paintedSign','backyard','shed','reuse:antiques'],
  history:[{year:1927, event:'opened', name, variant:'general'}, {year:2015, event:'closed', name, why:'superstore'}, {year:2016, event:'reused', name, reuse}],
  footprint:{tilesW, tilesD, width, depth},
  layout:{width, depth, height, building:[x0,z0,x1,z1], porch?, sideYard?, backyard?, shed?, carPark?, serviceYard?, docks?, gardenCentre?, bays?, corrals?}}
```

- `kind` is the building standing: `general`, `market`, `supermarket`, `superstore`; `style` the general store's
  `frontier` or `brick` (`modern` for the others); `state` `open`, `closed` or `reused`; `why` `moved` or `superstore`;
  `movedFrom:'market'` on a supermarket the market moved into. `sign` is what the sign says (the new use's name once
  reused). Reuse kinds: antiques, church, cafe, museum, feed, thrift, gym, flea, outlet, community.
- `features` is what the builder shows that year: general store `porch bench paintedSign backyard shed`, open
  `barrels crates iceChest windowGoods` (+ `gasPump` before 1975, `soda propaneCage` after), maybe `dogOnPorch`, closed
  `boarded fadedSign emptyLot`; market `plateGlass litSign awning smallLot serviceYard` (+ `windowPosters carts` open);
  supermarket `longGlassFront flatRoof pylonSign carPark serviceYard` (+ `cartCorrals windowPosters` open); superstore
  `flatRoof pylonSign carPark cartCorrals gardenCentre loadingDocks carParkLamps twoEntrances` (+ `pharmacy` 2000,
  `pickupBays` 2012, `evChargers` 2020); closed `forLease emptyLot`; reused `reuse:<kind>`.
- `people`: the town's living people that year (the car parks fill by it).
- `layout` places the parts on the plot in street units (21.18 a metre): x along the front from its left as seen from
  the street, z back from the front edge. The row's building entry is `{sprite, kind:'landmark', x0, x1, setback:0,
  depth}` covering the whole plot (x1 - x0 = footprint.width, depth = footprint.depth less 0.01); `shops[]` has
  `{id:'landmark:<site>', parts:[{key:'landmark_store_<kind>', w, h}]}` and make_buildings writes nothing for it.
- Only stores standing in the export's year are listed in `stores` (a site is wooded until its store opens, like any landmark).

### Time travel: every year from one export

`graph.storeSites[]` lists every site the plan reserves, standing or not, with its whole life (future events too), and
`graph.storePeople` the town's people every 5 years (1950-2025):

```
graph.storeSites[]: {id:'store:superstore', landmark:'landmark:superstore', site:'superstore', style:'modern', seed, dog:false,
  events:[{year:2015, event:'opened', name:'Broadmeadow', variant:'superstore', discount?:true}, {year, event:'expanded'|'renamed'|
  'moved'|'closed'|'reused', name, why?, reuse?, trade?}],
  footprint:{tilesW, tilesD, width, depth}, layouts:{superstore:{...}}, rect:[x0,z0,x1,z1]}     // rect in world units
graph.storePeople: [[1950, 79], ..., [2025, 231]]
```

`StoreSiteInfo.fromGraph(graph)` reads them, and `site.at(year, hour, weekday)` gives the `StoreInfo` the builder
takes for any year (null before the store opens). It mirrors the generator's `storeState` / `storeFeatures` feature for
feature (`StoreSiteInfo.featuresOf` = `featuresOf` in stores.ts); `tools/stores-fixture.ts` writes gamma's sites and
what the generator says each one is for 1900-2040 to `desktop/src/filamentTest/resources/stores-gamma-years.json`,
StoreKitTest checks `at` against it year by year, and src/town/stores.test.ts checks the fixture is current. Before its
store opens a site is a field (long grass, trees, a farm fence along the road: `StoreSitePlaceholder.build(site, year)`);
the year before, a building site (graded dirt, a site fence, a COMING SOON board). Preview a town's years:
`-DstoresFrom=street.json -DstoresYears=1955,1975,1995,2025`.

### Features: the generator decides, the builder draws

Every feature name the generator can emit (`STORE_FEATURES` in stores.ts) is in `StoreKit.FEATURES`, and the builder
draws each one when it is given (`StoreInfo.has`; with no features, it works them out as the generator would): the
general store's `bench barrels crates iceChest windowGoods gasPump soda propaneCage deli bait dogOnPorch`, the big
stores' `cartCorrals discountStore pharmacy pickupBays evChargers forLease securityLights`. Both tests check the lists
agree.
- The stores are also landmarks in the classic game (`LANDMARK_SPECS`: no sprite, so it draws nothing for them; an
  interior template each in src/town/interiors.ts).

## Exports and fixtures

- `kotlin-street/build-town/gamma` (not in git) is regenerated with the stores (`tools/build-town.sh gamma 2025`: 105
  buildings cut, the 4 stores skipped).
- The v1 two-street export of eta the app ships (assets/street.json, buildings/, dialogue.json, interiors.json) is
  re-exported: eta's plan changed (its general store and market take main-street plots), so its street is 13 buildings
  now (was 18).
- NOT refreshed: the app's `assets/town/gamma` (the Filament town; integration step) and the Kotlin fixture
  `desktop/src/test/resources/town-gamma.json`, which pins TownMapTest, TownWalkTest, TownPlaceTest and
  StreetPropRulesTest (being rewritten by the open-world work). Re-exported with the stores, 11 of their 31 tests fail on
  pinned counts and rows (rows 47 -> 52, sprites 99 -> 105, turn plans; one camera check, "camera turned -0.598 at 7R",
  worth a real look). The TS test that checks the fixture is current is skipped until then.

## Before the merge (blockers)

- The Kotlin fixture `desktop/src/test/resources/town-gamma.json` and the app's `assets/town/gamma` are stale (the plan
  changed for the stores); re-export them and re-pin TownMapTest, TownWalkTest, TownPlaceTest and StreetPropRulesTest
  (11 of 31 fail on pinned counts once refreshed). The TS freshness check of that fixture is `it.skip` until then. The
  "camera turned -0.598 at 7R" failure is to be looked at in the open-world branch (which rewrites that camera), not
  re-pinned.
- eta's shipped v1 street went from 18 buildings to 13 (its general store and market take main-street plots): check it
  by eye before merging.
- `-DstoresFrom` renders need a fresh export with `storeSites` (`npx tsx kotlin-street/tools/export-town.ts gamma 2025`).

## In the town (Integrate, later)

Follow the cinema (`CinemaSite.parse` / `TownCinema` in scene3d/TownCinema.kt): parse `graph.stores` with each store's
landmark (`StoreInfo.fromGraph`), and let `FilamentTown` leave each store landmark's slot (art `landmark_store_*`, no
building art in buildings/) to a `TownStores` that builds the store on a town worker, packs it, uploads it into its own
culled `PropLayer`, places it in the slot's frame (front on the row's building line, setback 0, the plot `footprint.width`
along by `footprint.depth` back) and lights it from dusk. TownMap should keep lamps and benches off a store's front like
the cinema's (`clearOfCinemas`) and off the car parks. The superstore's site is big (159 x 206 m): cull it by part.

**Scales (do not place by tiles x one scale):** a plot's `footprint.width` is along the street at the art's scale (26.29
units a tile) and `footprint.depth` back from it at position scale (44.5 a tile), while the landmark's `rect` is in world
units at position scale both ways (gamma's superstore: rect 5696 x 4361, footprint 3365 x 4361). Place the store's frame
from the row's slot (front on the building line, x across `footprint.width`), and add a test that the frame placed for
gamma's superstore covers its TownMap tile rectangle once the town fixture is refreshed.

## The buildings in Filament (Kotlin)

`shared/src/filament/kotlin/scene3d/StoreKit.kt` (+ `StoreMarkets.kt`, `StoreGrounds.kt`, `StoreSigns.kt`) builds a store
for a `StoreInfo` (`StoreInfo.fromGraph(graph)` reads `graph.stores[]` above: kind, name, sign, state, reuse, style,
features, opened/closed, seed, footprint) as low-poly boxes wearing code-painted pixel pictures at the buildings' texel,
nearest-sampled, like the cinema. Each kind on its whole plot, with its grounds, so it is complete from behind and above:

- **general** (`style` frontier: a clapboard false front over a tin or shingle gable, braced behind; brick: a corbelled
  parapet over a flat roof): a covered porch on a lattice-skirted deck (posts and brackets, end railings, steps, a
  lettered fascia "GENERAL STORE" / "GENERAL MERCHANDISE" before 1930), a hand-painted name board, two display windows of
  shelved goods, can pyramids, sacks, barrels and lanterns, a bench, barrels, crates, an ice chest, a gas pump (the
  visible-glass pump before 1940, the boxy fifties pump after; `features` gasPump when given, else before 1966), side
  windows on two storeys, a side door, a board lean-to with a back door, a stovepipe; the yard: gravel apron and drive,
  the owner's truck, a split-rail fence, a woodpile, an outhouse before 1950 or a propane tank after, a clothesline, shade
  trees, a mercury yard light from 1950. Closed: boarded windows and door, CLOSED board, faded name, peeling paint, rust,
  a broken post and missing planks, weeds, a FOR SALE sign, nothing lit. Reused: the new name on the board, the fascia's
  word, windows and porch by use (old goods for antiques/museum/thrift/flea; paper stained glass, a cross and a notice
  board for a church; a cafe's counter, tables and a sandwich board for cafe/community/gym; sacks for feed/outlet).
- **market** (1950s-60s): buff brick, plate glass in aluminium bays with the lit aisles, checkouts and shoppers behind,
  paper specials on the glass, IN/OUT doors, a canopy on steel columns with downlights, the name in red lettering on a
  cream (fifties) or turquoise (sixties) enamel band, a FOOD sign tower with a starburst at the road, one row of head-in
  bays, a dock, a back door, roof units and skylights.
- **supermarket** (1970s-80s): tan brick under a cedar-shake mansard (asphalt shingle in the eighties) on brick piers, a
  backlit sign box with the name and BAKERY DELI / FOOD & DRUG boards, glass bays and IN/OUT sliding doors, drink
  machines, an ice chest, a bench, a payphone, a propane cage from 1985, carts by the doors; a car park of double rows
  with sodium lamps, cart corrals, islands with trees (1980+), a pole sign with a reader board; three docks, a trailer,
  a compactor, pallets, a chain-link yard.
- **superstore** (1990s-on): split-face block and smooth panels with a band in the made-up chain's colour, pilasters; the
  storefront varies by the chain's seed so no superstore wears one real chain's trade dress (`StoreMarkets.facade`): one
  entrance or two, flat or gabled tower tops, entrance words (MARKET / HOME, FOODS / GENERAL, FRESH FOODS / HOME & MORE,
  or none), an emblem (a leaf, a wave, a barn, a star in a ring; never a spark or sunburst), a line under the name
  (DISCOUNT FOODS, FAMILY CENTER, VALUE CENTER, EVERYDAY SAVINGS, FOODS & MORE, FAMILY STORE; FAMILY DISCOUNT on the
  1990s discount store), PHARMACY from 2000 (`pharmacy`), painted ORDER PICKUP bays from 2012, EV chargers from 2020;
  "Supercenter", "Depot" and their kin are on a lookalike blocklist checked against every sign. GARDEN CENTER and AUTO CARE signs and bays, bollards,
  benches, cart rows, ice, a propane cage, a movie kiosk from 2005; a giant car park (painted bays, a fire lane, crosswalks,
  LED lamps from 2010, sodium before, corrals, tree islands), a pylon at the road (OPEN 24 HOURS from 1997), a fenced garden
  centre with a shade roof, plant tables and mulch pallets, a truck court of docks with trailers and a tractor.

Closed big stores decay with the years shut (`yearsClosed`): just shut, the name still up but unlit; from 2 years the
letters taken down to their ghost and bolt holes (the supermarket's backlit faces out, its side boards blank), a vinyl
AVAILABLE / FOR LEASE banner with a made-up agent and 555 number (weathered from 5 years); from 3 years faded lines,
cracks and weeds in the joints, a few corrals left behind, stray carts and one on its side. At night they keep their
security lights (`securityLights`: a wall-pack at each front corner and one dim lot lamp), so the site is not a black
hole. Reused ones are lit under the new name (WELCOME on the road sign, no specials, few cars). A market or supermarket
become a church has its glass painted over in opaque panels with a tall coloured pane in one bay of three, glass doors
with a cross, and a changeable-letter board (SUNDAY 10 AM / ALL WELCOME) on the old market tower where the starburst was.

### Car parks: by the hour, the day and the town

The cars are not part of the store: `StoreKit.buildWithSite(info, cars = false)` gives the store and its `bays` (each
with how far it is from the doors; the staff's down the building's side), and `StoreCars.build(info, bays)` builds the
parked cars at `info.hour` on `info.weekday` as their own small model, to be rebuilt as the clock moves on (on the
hour) without touching the store. `StoreTraffic` decides how many: shoppers by a weekday curve (late morning, lunch, the
after-work peak) or a weekend one (a broad midday peak), Saturday the busiest day, scaled by the town's people (a
superstore 0.9 cars a person at its peak, a supermarket 0.45, a market 0.16), plus the staff's cars while it is open and
an hour either side (a 24-hour superstore keeps about 10 overnight); none after closing. gamma's superstore (650 bays,
215 people): about 28% full on Saturday afternoon, 18% on a weekday lunchtime, 2% at 3 am. A reused building fills by
its use (a church on Sunday morning, a gym at dawn and after work, a flea market at the weekend). Shoppers take the bays
nearest the doors first (seeded, so an hour's cars are the same each time). Lots are lit evenly at night: every lamp's
head glows and lays a pool of light on the asphalt (a decal of the asphalt's own speckle glowing from dusk), with every
other lamp a real light. Cars are of their year: 1920s cars (short, tall, black) before 1932.
The builder reads the generator's block as it is: `sign` is the name shown, `style` picks frontier or brick, `features`
(when given) decides the gas pump, and every reuse kind has its look. A general store reused as antiques, museum, thrift
or flea shows old goods; church a chapel; cafe, community or gym a cafe room; feed or outlet sacks and shelves. A market
or supermarket reused shows, through its glass, a church room (paper stained glass along the top, a cross, pews), a gym
(treadmills, weights, a mirror wall), or a thrift, flea or outlet floor (clothes racks, shelves of housewares)
(`StoreSigns.inside`); its door signs and specials are gone, the road tower reads CHAPEL / GYM / THRIFT, the mansard's
boards WELCOME and SUNDAY 10 AM / OPEN 24 HRS / DONATIONS, and there are no carts or corrals and few cars. Render a
town's own stores with `-DstoresFrom=build-town/gamma/street.json` (gamma 2025: the antiques shop, Cornerstone Chapel
in the old market, the closed Prairie Larder, Broadmeadow on its full 159 x 206 m site).

The model's frame: x along the street with the plot's middle at 0, y up, z out towards the road, the plot's front edge at
z 0 and its back at -depth (the generator's `layout` has x from the plot's left and z back from the front: model x =
x - width/2, model z = -z). `StoreKit.build(info)` returns a `CinemaModel` (parts by role, real lights), so the cinema's
`CinemaLayer` / `CinemaLights` draw and light it unchanged; `StoreKit.buildWithSite` also gives the main block's plan.
A closed store's parts carry no glow masks and it has no lights.

**Hook (Integrate, later)**, as `TownCinema` does: a `TownStores` that, for each `graph.stores[]` entry and its landmark's
slot, runs `StoreKit.build` on a town worker, `CinemaLayer.pack(model, at)` there with `at` = the slot's frame at the
plot's front edge (the row's building line; the store fills its whole plot, setback 0), uploads on the engine's thread
into its own culled `PropLayer` (per part for the superstore's 160 x 200 m site), adds `CinemaLights` and calls
`CinemaLayer.update(dark, seconds)` / `CinemaLights.update(dark)` each frame, then drops the model.

Pictures on the heap while building (ARGB, glow masks counted; measured on the desktop): general store 1.0-2.1 MiB,
market 1.8-3.5, supermarket 3.9-4.5, superstore 3.1 (closed) to 7.2 MiB; triangles 1.4k-23k; lights 3-26.
Preview: `gradle :desktop:preview -Dmode=filament-stores [-Dstores=general,market_1956,...] [-Dhour=22.5]`: each variant
from the street, the storefront close up, three-quarter, behind and above at 13:00, dusk and night, into
build/preview/stores/ with stores.txt. Tests: `desktop/src/filamentTest/kotlin/StoreKitTest.kt` (on the plot, on the
ground, real scale, small pictures, dark when closed, the block's JSON, names never a real chain's).
