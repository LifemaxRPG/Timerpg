# G023 — Dove Beach: Lots on Curved Streets & Era Ledger (1700s–2050s)

## Summary

Built Phase 5a of Dove Beach (`src/town/city2/`) on top of the existing terrain and curved-road network without moving a single road, grade, or dove outline point (`align.ts`, `site.ts`, `roads.ts`, `ground.ts`, and `city2.test.ts` have **0 edits**).

- **Oriented frontage lots on curved, sloped streets (`src/town/city2/lots.ts`):** Walks all 13 curved roads from `roads.ts` plus Seagrove's 1905 street grid (`newTownGrid`) to place deterministic, district-sized lots reusing `LotKind` from `src/town/kinds.ts`. Every lot carries front and back yard setbacks, a leveled inner building pad (`padW`, `padD`, `padCorners`, `y`), ground grade, and 4-corner world polygon (`corners`). Candidates are rejected if any corner, edge midpoint, or center falls on any carriageway or junction clearance zone, any Seagrove street, Heron Marsh (`0` lots), Long Strand beach or dunes, the Sallow River (`< 26 m`), the railway (`< 13 m`), ground steeper than `MAX_BUILDABLE_GRADE = 0.30`, an exclusive landmark footprint, or an existing lot (exact 2D SAT polygon check with `1.5 m` separation).
- **Landmark plots (`reserveLandmarkPlots()`):** Reserves oriented 4-corner footprints for all **19** landmarks in `site.ts` (`LANDMARKS`) keyed by their documented opening dates.
- **Deterministic era ledger (`src/town/city2/ledger.ts`):** `buildDoveBeachPlan(seed)` and `plan.standingIn(year)` step Dove Beach through 9 historical eras from the 1700s fishing hamlet to the 2050s following `kotlin-street/docs/CITY2.md` (1700s hamlet, 1790 Neck Fort, 1800 Old Town, 1838 Custom House, 1851 Head Light, 1861 Sallow Mills & 1871 Brewery, 1886 railway & Harbour Station, 1894 Crown Observatory & 1899 Funicular, 1905 Seagrove & Esplanade/Strand, 1909 Strand Pier, 1912 Dove Fountain, 1923 Heron Creek drawbridge, 1936 Ridge Drive, 1939–1984 Headland Naval Yard cleared to parkland in 1994, 1948 Fellside, 1952 Eastcove marina & shrimp dock and Strand motor courts, 1970s shuttered mills, 1978 Uplands, 1990s mill lofts/taprooms & boutique motels, and the 2034 tram return). Regular buildings are strictly capped at `1–4` storeys; only **Head Light** exceeds 4 storeys.
- **Top-down SVG era maps (`src/town/city2/mapSvg.ts`):** Pure TypeScript vector cartographic renderer (`renderDoveBeachSvg(plan, year)`) highlighting the 5-road dove loop, era-filtered roads & Seagrove grid, coastline, dunes, protected Heron Marsh, river, railway, era-colored lots/pads, and active landmarks.

## Verified Numbers (`seed = "city2"`)

- **Total frontage lots:** `1,662` across all 11 buildable districts (`harbour: 23`, `oldtown: 101`, `crown: 47`, `mills: 10`, `strand: 124`, `fellside: 164`, `ridge: 55`, `eastcove: 31`, `head: 68`, `uplands: 4`, `newtown: 1,035`, `marsh: 0`).
- **Landmark plots:** `19` (100% of `LANDMARKS` in `site.ts`).
- **Standing counts by year:**
  - **1760 (`hamlet`):** `4` buildings (`harbour: 4`), `0` landmarks
  - **1850 (`fort_harbour`):** `63` buildings (`harbour: 19`, `oldtown: 44`), `2` landmarks (Neck Fort, Custom House)
  - **1900 (`mills_rail`):** `127` buildings (`harbour: 23`, `oldtown: 89`, `mills: 10`, `crown: 5`), `9` landmarks — `0` Seagrove, `0` Strand, `0` Ridge, `0` Fellside, `0` Eastcove
  - **1925 (`resort`):** `308` buildings, `13` landmarks (Seagrove, Strand, Strand Pier, Dove Fountain active)
  - **1960 (`car_boom`):** `1,130` buildings, `19` landmarks (Ridge, Fellside, Eastcove marina, Strand motor courts, Headland Naval Yard active; `0` Uplands)
  - **1980 (`decline`):** `1,539` buildings, `19` landmarks (Uplands open from 1978; Sallow Mills shuttered)
  - **2000 (`revival`):** `1,594` buildings, `19` landmarks (Naval Yard cleared on Head; mill lofts/taprooms/studios active)
  - **2025 (`modern`):** `1,626` buildings, `19` landmarks
  - **2050 (`modern`):** `1,626` buildings, `19` landmarks

## Top-Down Maps

- `prototypes/G023/dove-beach-1900.svg` (68,296 bytes — 127 buildings, 9 landmarks)
- `prototypes/G023/dove-beach-1960.svg` (228,889 bytes — 1,130 buildings, 19 landmarks)
- `prototypes/G023/dove-beach-2025.svg` (296,994 bytes — 1,626 buildings, 19 landmarks)

## Files Touched

- `src/town/city2/lots.ts` (new)
- `src/town/city2/ledger.ts` (new)
- `src/town/city2/mapSvg.ts` (new)
- `src/town/city2/town.test.ts` (new)
- `kotlin-street/docs/CITY2.md` (appended Phase 5a section)
- `prototypes/G023/dove-beach-1900.svg` (new)
- `prototypes/G023/dove-beach-1960.svg` (new)
- `prototypes/G023/dove-beach-2025.svg` (new)
- `prototypes/G023/DELIVERY.md` (new)

Zero edits to `align.ts`, `site.ts`, `roads.ts`, `ground.ts`, `city2.test.ts`, or any capital town files.

## Verification

- `npx tsc -p tsconfig.json --noEmit`: **0 errors**
- `src/town/city2/city2.test.ts` (11/11 passed, including `"keeps the dove"`) + `src/town/city2/town.test.ts` (9/9 passed): **20/20 passed**
- Capital town tests verified (15 files, **138/138 passed**): `era.test.ts` (5), `farms.test.ts` (8), `films.test.ts` (9), `housing.test.ts` (17), `interiors.test.ts` (12), `landmarks.test.ts` (11), `downtown.test.ts` (6), `largeBuildings.test.ts` (6), `layout.test.ts` (12), `neighborhoods.test.ts` (5), `plat.test.ts` (12), `properties.test.ts` (7), `schedule.test.ts` (4), `stores.test.ts` (18), `water.test.ts` (6). (The remaining 4 untouched GLB/rail/inspect test files timed out past 25 s in the sandbox; zero files outside `src/town/city2/` were modified.)

## Usage

```ts
import { buildDoveBeachPlan } from './src/town/city2/ledger';
import { renderDoveBeachSvg } from './src/town/city2/mapSvg';
import { CITY2_SEED } from './src/town/city2/site';

const plan = buildDoveBeachPlan(CITY2_SEED);
const snapshot1960 = plan.standingIn(1960);
// snapshot1960.era === 'car_boom'
// snapshot1960.buildings -> standing oriented lots with kind, role, storeys, padCorners, y
// snapshot1960.landmarks -> active landmark plots with state text and footprints

const svg = renderDoveBeachSvg(plan, 1960);
```

Run tests:
```bash
npx tsc -p tsconfig.json --noEmit
npx vitest run src/town/city2/city2.test.ts src/town/city2/town.test.ts
```

## Self-review

1. **Does it do what the brief actually asked, or an approximation?**
   It implements the full brief: oriented lots with front/back yards and pads along every curved road and Seagrove grid street, exact 2D SAT non-overlap and terrain/road/marsh/beach/river/rail guards, reserved plots for all 19 landmarks, a deterministic 1700s–2050s era ledger with `standingIn(year)` and the 4-storey cap (only Head Light taller), and top-down SVG maps for 1900, 1960, and 2025 — with zero changes to the road alignments or dove outline. One honest limit: `uplands` only places `4` lots along Pass Road because Pass Road climbs the steep Cleft gorge (`grade > 0.30` on most shoulders) and `roads.ts` has no residential Uplands spur street yet.
2. **Would a careful human reviewer find anything sloppy, brittle, or missing?**
   Two things worth calling out: (a) Landmark labels in `mapSvg.ts` are placed at a fixed `(+5, -4)` pixel offset rather than force-directed de-cluttering, so a couple of closely spaced Old Town/Harbour landmarks slightly crowd each other at full-town zoom; (b) `town.test.ts` takes ~8.6 s mainly because `keeps every lot off every carriageway...` queries `roadGround` and all 18 Seagrove segments across 9 sample points for all 1,662 lots — thorough, but a spatial index for the test assertion could speed it up if CI budget tightens.
3. **What is the most likely next bug or integration snag when someone wires this up?**
   When Phase 2b/3a bakes these lots for Kotlin/Filament (`CityGround.kt` + building placement), lots on `0.15–0.30` cross-slopes will need retaining walls or stepped foundation skirts between `padY` and the downhill yard edge (`padCorners` sit at a single flat `y` while the natural terrain drops 2–5 m across a 25 m deep hillside lot on Crown/Ridge/Fellside).

## Ideas for the team

- **Uplands cul-de-sac / side loop (Phase 2a/5a):** Adding one short post-1978 residential loop road off the top of Pass Road (on the gentle Uplands plateau north of the Cleft gorge) would expand Uplands from 4 gorge-shoulder lots to a proper 40–60 house suburb without touching the dove loop.
- **Retaining-wall skirt metadata on `City2Lot`:** Exporting `minCornerY` / `maxCornerY` alongside `y` (`padY`) would let Phase 3a/5a's Kotlin builder automatically decide where to emit stone retaining walls or stilt pilings (especially for Raised Beach Cottages and Crown/Ridge hillside villas).
- **Bake hook for `tools/city2-bake.ts`:** `buildDoveBeachPlan(CITY2_SEED)` is already pure, deterministic, and dependency-free, so Phase 2b can serialize `plan.lots`, `plan.landmarkPlots`, `plan.lotLedger`, and `plan.landmarkLedger` straight into `assets/town/dove-beach/plan.json` for `CityPlan.kt`.
