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

**The minimap (`Minimap`, `MiniPainter`)**: top left of the HUD (docs/HUD.md), 150 dp, round; see "The minimap's look"
below (2026-11-24, second pass) for how it is drawn now.

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

## The minimap's look (2026-11-24, second pass: the owner asked for "way more detailed, pretty")

**Pictures per zoom.** The city map is drawn as its own picture for each zoom step (`MapData.CITY_STEPS`: 0.45, 0.65,
0.9, 1.3, 1.9 and 2.8 m a pixel; `MapData.cityStep / cityFor`), every width worked out for that scale, so nothing is a
scaled-down picture: at the default zoom a picture pixel is a whole number of screen pixels (2 on the S25) once the zoom
has settled. Made on the maps' worker the first time a zoom is used; the last 3 steps per look are kept (gamma: the
closest 4,629 x 1,906 px, about 35 MB, 0.7 s here; the default 2,315 x 953 in 0.12 s; server numbers, not the S25). The
base city picture (`TownMapModel.DEFAULT_UPP`) is now 0.76 m a pixel, 2.25 times the first minimap's 1.7 m.

**What is in the picture** (`TownMapModel.raster`, both looks): the ground with a sparse dither (the grass's texture);
the lots; the paved downtown and the plaza (a paving checker close in); the river rippled with a pixel of sand along its
shore; the yards' patios, drives and car pads (close in); every tree as a canopy with its shadow down and right and a lit
pixel; the back lanes; **pavements** each side of every road (2.4 m); the kerb and the road (main roads 1.35 times wider,
amber, with a brown kerb); **dashed centre lines** (main roads always, others when 7 px wide), kept clear of the
junctions; the bridges with their parapets; the buildings' **drop shadows** (darkening whatever they fall on, about
2 m down and right); the buildings by kind with a **lit top-left edge and a shaded bottom-right edge** (raised, lit as
the game's buildings are); sheds and garages close in; the pools with a pale coping.

**Drawn on the HUD's canvas** (`MiniPainter.draw`, at the screen's own resolution, allocating nothing a frame): the
picture for the zoom, **turned smoothly with the camera** in heading-up mode (the turn is the screen's, not a small
picture's); the GPS route (a dark edge, the cyan line, white **chevrons that flow toward the waypoint**); the camera's
**view cone**; **street names along their streets** when zoomed in (up to 6, where they fit, never overlapping, read left
to right whatever the turn); the places' **icons** (`MapIcons`, `PoiKind`: home, station, police, doctor, landmark, fire,
gas, bank, post, hotel, church, school, library, cinema/nightlife, food, gym, services, shops; 11 x 11 pixel badges,
upright, the minor ones only close in, none over another); the waypoint's pixel **pin**, or an arrow on the rim toward
it; the player's arrow. Round it a **bezel**: ticks every 30 degrees and **N / E / S / W** on the ring (pixel letters, N
red), turning with the map. The distance to the waypoint sits on the bezel's bottom. Before GPS the same in the paper
look, with a brass bezel and a red-ink arrow; without a map the compass alone (dial, needle, the lettered bezel).

**Zoom**: six steps (`MapSettings.MINIMAP_RADIUS_M`: 55 to 350 m from the player to the rim), pinch on the minimap, hold
it to step out, or the menu's Map tab. **Driving zooms out with the speed** (to 2.4 times at about 45 mph; eased, about
0.4 s; `MapSettings.autoZoom`, on by default, the menu's Map tab).

**Places marked** come from the town's data at load (`TownMapModel.pois`: landmarks and the glb landmarks by their
kind, the shops by street.json's `kind`; gamma has 108), plus the app's own (`MapSession.extraPois`: the railway
station's depot, and the player's rented room).

**The full map** gets the same: the picture for its zoom, the icons (the names now under them), the view cone, the route
chevrons, the pin, a **scale bar** and a **north compass** bottom left, and a **Legend** button (the colours and every
kind of place marked here). Its minimap toggle moved to the menu's Map tab.

**Renders** (desktop, from `MapsTest`, not S25 captures): `minimap_gps.png`, `minimap_gps_close.png`,
`minimap_gps_wide.png`, `minimap_gps_headingup.png`, `minimap_gps_driving.png` (heading up at 45 mph, zoomed out),
`minimap_paper_1952.png`, `fullmap_gps.png` (with the legend), `fullmap_gps_close.png`, `fullmap_paper_1952.png`,
`fullmap_regional.png`, and the HUD: `hud_walk.png`, `hud_drive.png`, `hud_menu.png` (docs/HUD.md). All at the S25's
2.81 px a dp.

**Earlier renders** (desktop, from `MapsTest`, not S25 captures): `minimap_gps.png`, `fullmap_gps.png` (gamma, 2025, the route
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
- The icons mark one place per building; a shop row's many small shops crowd Main Street close in on the full map.
- Nothing measured on the S25 yet: the minimap's frame cost, the pictures' memory (the zoom steps up to about 35 MB each, 3 kept per look; the
  world map 7 MB, a road map about 4 MB).

## Real addresses (the owner's decision, 2026-11-26)

Every building in the game has a real address (number, street, town, and a postal code from the era that had them)
that can be searched on the GPS in modern eras, found on paper maps and in directories in older ones, written on
letters, given by NPCs and read off house numbers and street signs in the world. The professor's opening letter
("come to the capital, to this address") is the first use. Built by Jeff in G025 (src/), drawn by Claude (signs,
house numbers, the GPS search).

## Finding your way (the owner's decisions, 2026-11-26)

No quest markers: the address is the marker. In the eras before GPS the player finds an address the way people did:
the city map's street index ("Elm Street ... C4"), house numbers rising away from the centre (odd one side, even the
other), street signs at every corner, and landmark directions from people ("past the fountain, left at the bank"),
which are true to the town and can be asked again. The player can pencil a mark on any map they own once they've found
the street.

**The character's memory:** once the player has been somewhere (driven, walked or ridden there), the character knows
the way for good: the place is remembered on every map they own and in the GPS-less eras the way there is shown as a
remembered route, like a person who never forgets a road. Only new places need the map, the index or asking.
