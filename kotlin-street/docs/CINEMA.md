# The cinema (world side)

A full-size movie theater for towns that grow big enough, with posters for every decade you travel to. The TypeScript
game decides everything (where it stands, its name, its front, what plays); the engine builds the 3D building from
street.json v2.

## Which towns, and where

- `src/town/cinema.ts` `hasCinema(seed)`: the town's natural history (no time travel; `naturalPopulation` in
  src/sim/simulate.ts, the people only, ~0.15 s) reaches **CINEMA_POPULATION = 220** living people at its peak between
  1950 and 2025. gamma peaks at 231 (yes), eta at 203 (no). Of 63 known seeds (the named test seeds and water0-39),
  29 qualify and 25 get a site; 4 (maple, water8, water9, water22) find no block deep enough even grown and go without.
- `src/town/landmarks.ts`: landmark kind `cinema`, placed after the civic landmarks (they keep their places), on or just
  off the main street as near the plaza as it fits, inside the streets' extent (the town does not shift for it). Plot
  24 x 36 tiles (lobby front, the hall behind: ~24 m x 35 m), else the first of 26x32, 30x28, 34x24, 40x20, 44x18 that
  fits (the hall turned along the street). `src/town/plan.ts` grows a qualifying town's streets (like any plan that does
  not fit) until the cinema has a site. Open from 1936-1949, so it stands in every year of the game.
- gamma: plot x192-216, y71-107 (tiles), front on Main Street (main segment 3, y108) just east of its jog, ~60 tiles east
  of the plaza: row **6L (Main Street)**, building **b98**, x 2800-3430 along the row (past the bend), 630 wide, 920 deep,
  setback 26. The civic centre, spawn, rows (96), nodes (47) and lots (92) are unchanged; 4 unused plots gave way.

## Era fronts

`cinemaEra(year)`: `palace` (< 1960: the picture palace, blade sign, chasing-bulb marquee), `modern` (1960-79: the
modernised front, backlit letter board), `twin` (1980-99: twin, then triple from 1990, neon trim), `multiplex` (2000+:
6-8 screens, wide lit sign, digital marquee, poster wall). `landmarkArtKey` gives `landmark_cinema_<era>` (no LimeZu
sprite; tools/make_buildings.py skips it). Names: a house name kept all century ("Avalon", or the town's own), worn as
"The Avalon" (palace, modern), "Avalon Twin"/"Avalon Triple", "Avalon 8"; a town-named house is "The <Town> Palace",
"<Town> Cinema", "<Town> Cinemas 8".

## The films

`src/town/films.ts`: one made-up catalogue for the whole world, 16 releases a year 1950-2040 (generated in order, so no
title ever repeats), word pools and title patterns per decade and genre (1950s monster/western/musical/space/noir;
1960s epic/spy/beach/gothic/western; 1970s disaster/car chase/cop/space/conspiracy/kung fu; 1980s neon action/sci-fi/
slasher/teen/fantasy/buddy cop; 1990s thriller/family/action/romcom/disaster/crime; 2000s fantasy epic/animated/found
footage/heist/disaster/comedy; 2010s superhero-ish/dystopia/animated/horror/space/action; 2020s elevated horror/
multiverse/prestige drama/animated/biopic/action), sequels to hits a few years back from the 1970s on, a made-up star,
a tagline, a rating from 1968 (G/PG/R; PG-13 from 1984), and a poster: the decade's `style` (painted-pulp,
painted-epic, airbrush, neon-grid, photo-montage, blockbuster, teal-orange, minimal), a genre `motif`, a 4-colour
`palette` (background, main, accent, lettering) and a painter `seed`. A year's bill never repeats a pattern, franchise,
name or place. `cinemaProgram(seed, year)`: the town's pick of the year's releases (1 palace and 1960s; 2 in the 1970s;
2 / 3 twin / triple; 6-8 multiplex), next year's on the coming-soon cases (8+ posters in all), the marquee lines.
Tests (src/town/films.test.ts): deterministic, no title twice ever, ~300 famous real titles and real studios, chains and
franchises never appear, every decade in its style, ratings only from 1968.

## NPCs

`src/llm/factsheet.ts` `cinemaFacts`: for small talk, news and "about the town", one line on what is showing this week
(up to three titles with the genre in words) and, for 40% of people aged 8+, what they saw and what they thought.

## street.json v2

`tools/town-cinema.ts` (hooked into tools/town-graph.ts):

```
graph.landmarks[]: {id:'landmark:cinema', kind:'cinema', name:'Avalon 8', sprite:'b98', art:'landmark_cinema_multiplex',
  row:'6L', rect:[x0,z0,x1,z1], era:'multiplex',
  footprint:{tilesW:24, tilesD:36, setback:26.286, depth:920, width:630.857}}   // street units, as rows' buildings
graph.cinema: {landmark:'landmark:cinema', name, era, decade, year, screens, marquee:['NOW PLAYING', 'TITLE', ...],
  nowShowing:[{title, genre, tagline, rating?, star, poster:{style, motif, palette:[4 x '#rrggbb'], seed}}],
  comingSoon:[...same]}
```

The row's building entry is the usual `{sprite:'b98', kind:'landmark', x0, x1, setback, depth, ...}`; `shops[]` has
`{id:'landmark:cinema', sprite:'b98', kind:'Landmark', parts:[{key:'landmark_cinema_<era>', w, h}]}` and make_buildings
writes nothing for it.

## The building in Filament (Kotlin)

`shared/src/filament/kotlin/scene3d/CinemaKit.kt` builds the theater for a `CinemaInfo` (`CinemaInfo.fromGraph(graph)` reads
the `cinema` block above and the landmark's footprint): four era fronts (palace, modern, twin, multiplex) over a plain
auditorium box (brick or render, fire exits, a loading door, a ladder, projection bump, vents and plant on the roof), as
low-poly boxes wearing code-painted pixel pictures at the buildings' texel, nearest-sampled. `CinemaPosters.kt` paints each
film's 48x72 poster in its style (the town's `style`/`motif`/`palette`/`seed`), `CinemaLayer.kt` hands the parts to a
`PropLayer` and lights them (warm lobby glass from nightfall, signs and poster cases from dusk, three phases of marquee bulbs
chasing at 7 steps a second). Pictures per era: about 2.4-3.2 MiB of ARGB on the heap while building (big walls are small
repeating tiles); drop the model after adding it. Preview: `gradle :desktop:preview -Dmode=filament-cinema` (each era from
street, three-quarter, marquee, side, behind and above at day, dusk and night, chase frames, a poster sheet per decade;
`-DcinemaBills=DIR` for the town's own bills), tests in `desktop/src/filamentTest/kotlin/CinemaKitTest.kt`.

Scale and site (review fixes, 2026-11-19). A figure is 36 units (1.7 m, about 21 units a metre), so the theater is built to
real size: the palace's front 280 tall (13 m) under a 300-tall hall with a fly tower to 360 and the blade sign to 444; the
modern front 220 over a 290 hall; the twin 150 over 240/214 halls with a 300 pylon; the multiplex a 200 front and a 220
atrium over stepped stadium halls of 330 and 270, with exit stair towers. Exit doors 22 x 46, the loading door 64 x 70.
`CinemaKit.build` lays the building on its plot (`Plot`: the narrow eras centred with an alley either side, the twin to
one side of its car park, the multiplex beside a lane to its car park behind) and `CinemaGrounds.kt` fills the rest of
the plot: a paved forecourt, alleys (setts or slabs) with bins, crates, wall lamps and a smokers' bench, a service yard
with dumpsters behind brick walls or chain link and a gate, car parks with painted bays, tall lamps (sodium for the twin,
LED after) and seeded parked cars. The model carries real lights (`CinemaLight`: the marquee's soffit spots on the
pavement, sign washes, the car parks' and alleys' lamps), made by `CinemaLights` (CinemaLayer.kt) and turned up from dusk.
The twin's neon steps (its runs in the chase phases) and the multiplex's LED board pages through its six lines
(`PAGE0..2`, a line every 1.6 s). Posters glow at about half strength so pale ones keep their lettering at night. Each
decade's painter has a composition per motif (`CinemaPosterArt.kt`: a rider, a duel, a spider, a sea beast, a chorus line,
a colossus, a kung fu kick, a big rig, a slasher's lake, teens, a sword on a crag, buddy cops, a romcom, a heist, an army,
a vault door, a tidal wave, an astronaut's visor, a walled city, and six symbols, four doors, three portraits for the
2020s); films of one motif on a bill take its compositions in turn (`CinemaFilm.look`), and no tagline prints twice on a
bill (`CinemaInfo.distinctTaglines`; `distinctTaglines` in src/town/films.ts). No title is a bare "The <word>". Pictures
per era: about 5.3-6.7 MiB with the site (the town builds at a 512 MB preview heap). Street lamps and benches are kept off a
cinema's front (TownMap moves them to its ends) and PropRules keeps the whole front clear.

## In the town (Integrate)

- `CinemaSite.parse(street.json)` (scene3d/TownCinema.kt) reads the cinema landmark's sprite and `graph.cinema`; the app's
  `TownData.cinema` and the town preview pass it to `FilamentTown(..., cinema = ...)`.
- `FilamentTown` leaves that slot to `TownCinema` (the slot has no art: `make_buildings.py` skips it): `CinemaKit.build`
  runs on a town worker, its parts are packed there (`CinemaLayer.pack`), the engine's thread uploads them into the
  cinema's own culled `PropLayer`, and the model is let go. It stands in the slot's own frame (`TownPlace`, front on the
  setback), is culled, range-faded and cut with the street view like a building, counts in `pending` until it is up, and
  `CinemaLayer.update` lights the signs and chases the bulbs each frame.
- Every era stays on its plot (CinemaKitTest): the back's fittings and the multiplex's drum are kept inside it.
- The app ships the regenerated gamma (assets/town/gamma: street.json v2 with graph.cinema, buildings/, and dialogue.json
  and interiors.json re-exported): every NPC's fact sheet has the week's bill, and about 40% what they saw.
- The town is exported for one year (gamma: 2025, the multiplex "Avalon 8"); another year needs the town exported for it.
  The cinema has no interior in interiors.json yet (landmarks are skipped by the rooms' sprite check).
- Town preview shots: `-Dmode=filament-town -Dshots=cinema` (street day/dusk/night, three-quarter, low aerial, behind).
  The town memory check passes with the cinema (held peak about 200 MB of the 256 MB budget, desktop).
