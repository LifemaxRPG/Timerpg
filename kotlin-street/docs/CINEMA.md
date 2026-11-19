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
