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
