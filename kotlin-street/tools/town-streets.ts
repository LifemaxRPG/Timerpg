// The two streets of a real town (from OUR game logic), shared by tools/export-street.ts (the buildings, for the renderer)
// and tools/export-dialogue.ts (the people in them, for dialogue). Both see the same buildings under the same sprite names.
//
// What it finds, all from the town's real layout (src/town/layout.ts):
//  - streets[0], the main street: the buildings standing on the north side of the main road, west of a real crossroads, in
//    order along the road (the side-scrolling view looks north at them);
//  - streets[1], the cross street: the buildings on the west side of the road that runs north from that crossroads (after
//    the corner turn the camera looks west at them), nearest the junction first;
//  - junction: what the corner-turn scene needs besides those: the first buildings east of the crossroads on the main
//    street, and the buildings on the east side of the cross street;
//  - shops: every building above, once, for tools/make_buildings.py (each has its sprite name bN).
// How each building is named and dressed (shared with the whole-town export): tools/town-buildings.ts.
import { runTimeline, worldAt } from '../../src/sim';
import { archetypeOf } from '../../src/sim/archetype';
import { layoutTown, type Lot, type TownLayout } from '../../src/town/layout';
import type { World } from '../../src/sim/types';
import type { Road } from '../../src/town/kinds';
import { hash, makeBuildings } from './town-buildings';

export interface TownStreets {
  seed: string;
  year: number;
  w: World;
  layout: TownLayout;
  /** The two streets, by sprite name, in order along each. */
  streets: { name: string; buildings: string[] }[];
  /** Which lot each sprite name stands for. */
  lotOf: Map<string, Lot>;
  /** What tools/export-street.ts writes (street.json). */
  json: object;
}

export function townStreets(seed: string, year: number): TownStreets {
  const w: World = worldAt(runTimeline({ seed, endYear: year }), year);
  const layout = layoutTown(w);

  const lots = [...layout.lots.filter((l) => l.kind === 'shop' && l.vacantSince === undefined), ...layout.lots.filter((l) => l.kind === 'house')];

  // -------------------------------------------------------------------------------------------- the main street
  const mains = layout.roads.filter((r) => r.main && r.horizontal);
  /** A lot fronting a horizontal road from either side (the street view shows one row, so both sides become that row). */
  const besideH = (l: Lot, r: Road): boolean =>
    (Math.abs(l.y + l.h - r.y) <= 1 || Math.abs(l.y - (r.y + r.h)) <= 1) && l.x + l.w > r.x && l.x < r.x + r.w;
  /** Keep one building per stretch of road: where two overlap (one on each side), the shop, else the first. */
  function oneRow(ls: Lot[], start: (l: Lot) => number, len: (l: Lot) => number): Lot[] {
    const out: Lot[] = [];
    for (const l of [...ls].sort((a, b) => start(a) - start(b) || (a.kind === 'shop' ? -1 : 1))) {
      const last = out[out.length - 1];
      if (last && start(l) < start(last) + len(last) - 1) { if (l.kind === 'shop' && last.kind !== 'shop') out[out.length - 1] = l; continue; }
      out.push(l);
    }
    return out;
  }
  const onMain = oneRow(lots.filter((l) => mains.some((r) => besideH(l, r))), (l) => l.x, (l) => l.w);
  const mainSet = new Set(lots.filter((l) => mains.some((r) => besideH(l, r))));

  // -------------------------------------------------------------------------------------------- the crossroads
  /** Vertical roads meeting the main street, from the north or from the south. */
  const verticals = layout.roads.filter((r) => !r.horizontal);
  type Arm = { roads: Road[]; north: boolean; x: number };
  const arms: Arm[] = [];
  for (const v of verticals) {
    const fromNorth = mains.some((m) => Math.abs(v.y + v.h - m.y) <= 3 && v.x >= m.x - 2 && v.x <= m.x + m.w + 2);
    const fromSouth = mains.some((m) => Math.abs(v.y - (m.y + m.h)) <= 3 && v.x >= m.x - 2 && v.x <= m.x + m.w + 2);
    if (!fromNorth && !fromSouth) continue;
    // the whole road away from the junction: contiguous vertical segments (gaps of a few tiles are crossings)
    const roads = [v];
    for (;;) {
      const end = fromNorth ? Math.min(...roads.map((r) => r.y)) : Math.max(...roads.map((r) => r.y + r.h));
      const next = verticals.find((r) => !roads.includes(r) && Math.abs(r.x - v.x) <= 1 &&
        (fromNorth ? r.y + r.h <= end && end - (r.y + r.h) <= 6 : r.y >= end && r.y - end <= 6));
      if (!next) break;
      roads.push(next);
    }
    arms.push({ roads, north: fromNorth, x: v.x });
  }
  const besideV = (l: Lot, a: Arm, side: 'w' | 'e' | 'any'): boolean => a.roads.some((r) =>
    (side !== 'e' && Math.abs(l.x + l.w - r.x) <= 1 || side !== 'w' && Math.abs(l.x - (r.x + r.w)) <= 1) && l.y + l.h > r.y && l.y < r.y + r.h + 1);
  /** Distance from the main street along the arm. */
  const away = (a: Arm) => (l: Lot): number => (a.north ? -(l.y + l.h) : l.y);
  const crossOf = (a: Arm): Lot[] => oneRow(lots.filter((l) => besideV(l, a, 'any') && !mainSet.has(l)), away(a), (l) => l.h);
  const before = (a: Arm): number => onMain.filter((l) => l.x + l.w <= a.x + 1).length;
  const score = (a: Arm): number => Math.min(crossOf(a).length, 6) * 3 + Math.min(before(a), 8) * 2 - (before(a) < 3 ? 100 : 0) - (crossOf(a).length < 2 ? 50 : 0);
  const arm = [...arms].sort((a, b) => score(b) - score(a) || a.x - b.x)[0];
  if (!arm) throw new Error(`no crossroads on the main street of ${seed}`);
  const junctionX = arm.x;
  const crossRow = crossOf(arm);
  const crossRowSet = new Set(crossRow);
  const crossOpp = lots.filter((l) => besideV(l, arm, 'any') && !mainSet.has(l) && !crossRowSet.has(l)).sort((a, b) => away(arm)(a) - away(arm)(b));
  const crossWest = crossRow;
  const crossEast = crossOpp.length ? crossOpp : crossRow.slice().reverse();
  const mainWest = onMain.filter((l) => l.x + l.w <= junctionX + 1).slice(-8);
  const mainEast = onMain.filter((l) => l.x >= junctionX + 1).slice(0, 2);

  // -------------------------------------------------------------------------------------------- street names
  const NAMES = ['Elm Street', 'Mill Road', 'Church Street', 'Station Road', 'Orchard Lane', 'Cedar Avenue', 'Union Street', 'Market Lane',
    'Willow Road', 'Bridge Street', 'Maple Avenue', 'Chapel Lane', 'Garden Street', 'Hill Road', 'Oak Street', 'Park Avenue'];
  const crossName = NAMES[hash(`${seed}:${junctionX}`) % NAMES.length]!;

  const { entry, vary, entries, lotOf } = makeBuildings(w, layout, year);
  const main = mainWest.map(entry);
  const cross = crossWest.slice(0, 8).map(entry);
  const east = mainEast.map(entry);
  const crossOpposite = crossEast.slice(0, 5).map(entry);
  // the main street (with the buildings past the junction) and each side of the cross street, each without repeats
  vary([...main, ...east]); vary(cross); vary(crossOpposite);
  const streets = [{ name: 'Main Street', buildings: main }, { name: crossName, buildings: cross }];

  return {
    seed, year, w, layout, streets, lotOf,
    json: {
      town: seed, year, type: archetypeOf(seed).label,
      streets,
      junction: { x: junctionX, mainEast: east, crossEast: crossOpposite },
      shops: entries,
    },
  };
}
