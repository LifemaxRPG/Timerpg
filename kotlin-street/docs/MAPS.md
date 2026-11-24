# Maps (the owner's request, 2026-11-24)

The owner asked for a map system, starting with a minimap.
- **In modern times** the player always has a minimap and a full map, because they have GPS.
- **Before GPS**, they buy paper maps.
- **Waypoints** can be set on any map they own.
- **Maps come in tiers:** a world map shows everything but no fine detail, while a closer map shows a specific shop in
  a specific town.

## The three tiers

| Tier | Shows | Doesn't show | Typical cost (1952) |
|---|---|---|---|
| **World map** | the continents and seas, capitals and big cities, main shipping lanes, the major railways | towns' streets, small towns, businesses | $1.50, at a bookshop or station |
| **Regional map** (a continent or a big region) | every town and village, highways and roads with route numbers, railways and stations, landmarks and wonders, gas stations along highways (from the era of road maps) | streets inside towns, individual shops | 25¢, free at gas stations in the road-map era |
| **City map** (one town) | every street and its name, parks, the river, landmarks, and every business with its name, plus the house-number ranges | | 35¢, at the town's general store, newsstand or station |

## The rules

- **What a map covers:** a map shows only its area, at its own level of detail. The minimap and the full map draw from
  the best map the player owns for where they are: the city map in town, the regional map on the road, the world map at
  sea.
- **GPS:** the phone holds every tier everywhere, with live position and a route line to the waypoint. It's built in
  from the year the player's phone could (consumer GPS navigation from about 2000, phone maps from about 2008).
  Before that, there's no GPS.
- **Before GPS:**
  - With a paper map for the area, the minimap shows the map, but the player's position is only shown if they can tell
    where they are: in town, a street they're on whose name they can read; on the road, the last sign they passed.
  - Without a map, there's no minimap, only a compass (always available, because the player can read the sun) and the
    names of streets as they walk them.
- **A map is printed in a year and shows the world as it was then.** A 1950 road map in 1975 misses the new expressway
  and still shows the closed diner. Time travel makes old maps a real thing to think about.
- **Waypoints:** set by tapping on any map the player owns (or by asking someone for directions, later). The minimap
  shows an arrow toward it when it's off the edge, and the distance. With GPS there's a route line; without it, a
  straight bearing only.
- **Shops sell the maps of their era:**
  - gas stations: free regional road maps in the road-map era (about 1920-1980), then for sale;
  - newsstands and general stores: city maps;
  - bookshops and stations: world maps.
  The economy's catalogue and stock will carry them.

## First step (now): the minimap
- A round minimap in a HUD corner, drawn from the town's data: roads, blocks and buildings by kind, the river, parks,
  landmarks, the player's arrow and heading, and north up or heading up (a setting).
- Tapping it opens the full map of what the player owns, where they can zoom, pan and set a waypoint.
- The GPS and paper-map rules above are applied from day one, by the town's year and the maps the player owns. Maps
  are items the loop saves.

## Built (2026-11-24)

The first step is in the game: the minimap, the full map with a waypoint, the tier, ownership and GPS rules, and paper
maps for sale in the loop. Code: `shared/src/main/kotlin/maps/` (platform-free, tested on the desktop),
`shared/src/main/kotlin/loop/LoopMaps.kt` and the "maps" section of `LoopGame`, `app/src/main/kotlin/MapHud.kt` (the
app's side), the maps rows in `LoopHud.kt`, `GameActivity` (the corner, the full map, the station's rack).
Tests: `MapsTest` (`gradle :desktop:crossroadsTest --tests MapsTest`; it also writes the renders below to
`desktop/build/maps`, or `$MAPS_OUT`).

**The rules (`MapRules`)**
- **GPS from 2008** (`MapRules.GPS_YEAR`): the year phone maps with a live position became common. Consumer GPS units
  came about 2000, but the player carries a phone, not a dashboard unit; a car GPS could be an item for 2000-2007
  later. Gamma is 2025, so the phone has every tier, the player's arrow and a route line, with no paper map.
- **Before GPS** the minimap draws the closest tier the player owns for where they are (`MapRules.access`): in town the
  city map, else the road map; at sea the world map. Without one, the corner shows only a **compass rose** (a needle
  and the N, turned with the camera).
- **The player's spot on paper**: shown while they stand on or beside one of the town's roads (within its half-width
  and about 12 m: `TownMapModel.streetAt`), since its street sign is readable there; off the streets (a yard, a back
  lane) the map stays centred where they last knew they were and the arrow is gone. The last road sign passed, on the
  road between towns, comes with travel.
- **What a map covers**: a city map its town (`area` = the town's seed), a road map its region (`area` = the landmass
  under the town, `mass:<n>` from the skeleton; `region:home` where the planet is not loaded), the world map everything.
- **Owned maps** are kept in the loop save (`maps`: tier, area, print year, label), as is the waypoint (`waypoint`).
  A second copy of the same print is refused.

**Buying maps (`LoopMaps`, `LoopGame.mapOffers / buyMap`)**: Kotlin's own stock beside the economy's shelves. City
maps at the general store (or its successor), the market (its newsstand rack), the drugstore, the gas station and the
railway station; the road map at the gas station (**free from 1920 to 1980**, then sold), the market and the general
store; the world map at the market and the station (the town has no bookshop yet). Prices are the 1952 prices above
scaled by the economy's `priceIndex(year) / priceIndex(1952)`, to the cent before 1980 and to five cents after: 1952
35¢, 25¢, $1.50; 2025 $4.10, $2.95, $17.65. Buying needs the shop open and a keeper who serves the player. The station's
rack is a "🗺 Maps" chip at the depot beside "Take the train". In 2025 they still sell, with a line that the phone has
GPS maps.

**The city map (`TownMapModel`)**, built at the town's load (`TownSession.read`, about 1/3 s on this server): drawn once
into plain pixels (`MapRaster`: no anti-aliasing; 1,225 x 505 pixels for gamma at 1.7 m a pixel) in two looks, the
phone's (dark green ground, white streets, Main Street and Civic Avenue yellow and wider) and a printed paper map (cream,
brown ink). Layers: the ground, the lots, the paved downtown and the plaza, the river with a one-pixel darker edge, the
forest's and the streets' trees, the back lanes, the roads (an edge and the road), the bridges, the buildings' real
footprints (the town collision's solid parts, read at load before the walk takes it; a slot's box where it has none) by
kind (homes tan, shops orange, landmarks purple, civic buildings blue, each with a darker edge), the rich homes' pools.
Names: the streets (each street's pieces joined through the junctions where it runs on straight, a candidate at the
middle of each run and at a sixth and five sixths of long ones), the landmarks and civic buildings (Town.landmarks and
the hand-made glb landmarks, under their names in the town's year) and the businesses (street.json's shops, the store
sites under the name they trade as).

**The regional and world maps (`PlanetMapModel`)**, from the baked skeleton, in the town's own street units (the planet
unwrapped round the town, so the player, the waypoint and every tier share one frame): land coloured by biome (high
ground a step lighter, the coast a dark pixel, other landmasses dimmed on a road map), sea and deep sea. The **road
map** (the town's landmass and a margin, 3 pixels a 1.07 km cell): rivers, the roads **as they were in the print year**
(Highways v2: expressways orange, highways yellow, county roads pale; a 1925 road map has fewer roads and towns than a
2025 one), route numbers as shields, the railways dashed, every town founded by the print year (dots by rank), the
natural wonders, truck stops and diners as red marks. The **world map** (1,856 x 928, a pixel a cell): the continents,
the capitals and big cities, the main railways and the shipping lanes (straight dashes between their ports). Both are
made on a worker thread of their own (`MapData`), about 0.3 s each on this server, per print year.

**The minimap (`Minimap`, `MiniPainter`)**: top right under the season and weather chips, 150 dp, round. It is drawn into
its own 120 x 120 picture and scaled up with nearest filtering, so the map, the route, the arrow, the two-ring frame and a
5 x 5 pixel N are all the HUD's chunky pixels. A frame draws a window of the map's picture and a few shapes, allocating
nothing. North up by default; **heading up** turns it with the camera's yaw (the full map's "Minimap: heading up"
button; kept in the app's preferences with the zoom). Zoom: pinch on it or the - / + buttons (six steps). The player's
arrow points the way they head. The waypoint is a pink pin, or an arrow at the rim toward it with the distance ("250 m").
With GPS the route line runs along the roads.

**The full map (`FullMap`)**: tap the minimap. It opens on the best tier for here (the city map in town), pans and pinches
(the point under the fingers stays put); zooming out past the city map goes to the road map, then the world, and
zooming in comes back; or the tier buttons (only the tiers the player has). Names claim room by importance and are left
out where they would overlap: street names along their streets when the street is long enough on screen, landmarks a
little closer, businesses only close in (city tier only); towns by rank, route numbers and wonders on the road map;
capitals and cities on the world map. A hard one-pixel halo, monospace, no blur. The caption says "GPS · live" or
"Printed 1952". **Tap to set the waypoint, "Clear waypoint" to clear it.** With GPS and both ends in town the route is
A* over the town's junctions along its roads (`TownMapModel.route`; gamma corner to corner: 2,475 m by road for 2,028 m
straight, about 1 ms here), redone when the player has moved about 6 m; outside the town and without GPS, a straight
dashed bearing from the player (when they know where they are) and the straight distance.

**Renders** (desktop, from `MapsTest`, not S25 captures): `minimap_gps.png`, `fullmap_gps.png` (gamma, 2025, the route
from Main Street to the police station), `minimap_paper_1952.png` (with the compass rose), `fullmap_paper_1952.png`,
`fullmap_regional.png`, `regional_2025.png`, `world_2025.png`.

**Gaps, next**
- **A city map shows the town as it is in the town's year**, whatever its print year: it is labelled "Printed 1952",
  but gamma's data has only its own year's buildings and names. Time-correct city maps need the town's history by year
  (the stores already have theirs; the landmarks have `history`). The road and world maps are time-correct for roads and
  towns already; the rail lines have no years in the skeleton, so all are drawn.
- The paper position rule is "on any of the town's roads"; reading a sign only on streets the player has walked, and the
  last road sign on the road between towns, come later. Asking someone for directions to set a waypoint: later.
- Route on the regional and world tiers is a straight line; road routing over Highways v2 comes with travel.
- The town has no bookshop or newsstand: the world map is at the market and the station. A dashboard GPS unit for
  2000-2007 is not an item yet. Maps can't be shoplifted (no "Take" on the rack).
- The world map is unwrapped round the town, so a waypoint across the date line is the long way round.
- The farms round the town and its rail line are not drawn on the city map yet; the shops are as deep as the town's
  collision holds them (their slot boxes).
- Nothing measured on the S25 yet: the minimap's frame cost, the pictures' memory (the city map about 2.5 MB a look, the
  world map 7 MB, a road map about 4 MB).
