// Every building of a real town under its sprite name bN: the shared building list the whole-town exports (dialogue,
// interiors, and street.json v2) name buildings from, so a sprite means the same lot in all of them.
//
// Naming: the buildings of the v1 two streets (tools/town-streets.ts) keep their v1 names (b0, b1, ... in v1's order, so
// street.json v2 stays a superset of v1 and eta's names do not move), then every other lot of the town, in the order
// the layout lists them (shops first, then homes; the same lots v1 picks from: occupied shops and every home). Each is
// named and dressed by tools/town-buildings.ts.
//
// Where each building stands in the walkable town (its row and its place along it) comes from the graph (street.json
// v2, tools/town-graph.ts): placesIn() reads them from a v2 street.json. Until the graph is there, places are empty and
// the exports carry null rows (the hook: pass the v2 street.json, or let the exporters find the app's).
import { readFileSync, existsSync } from 'node:fs';
import type { Lot, TownLayout } from '../../src/town/layout';
import type { World } from '../../src/sim/types';
import { neighborhoodOf } from '../../src/town/neighborhoods';
import { townStreets, type TownStreets } from './town-streets';
import { makeBuildings, type Buildings } from './town-buildings';

export interface WholeTown {
  seed: string;
  year: number;
  w: World;
  layout: TownLayout;
  /** The v1 two streets (their sprite names are the same here). */
  v1: TownStreets;
  /** The names and street.json entries of every building (entry() for all lots already called; vary() is the caller's). */
  buildings: Buildings;
  /** Every sprite, in naming order (b0, b1, ...). */
  sprites: string[];
  /** Which lot each sprite name stands for. */
  lotOf: Map<string, Lot>;
  /** Where a sprite is on the v1 streets (street 0 main, 1 cross; index along it), if it is. */
  v1Place: Map<string, { street: number; index: number }>;
  /** The neighbourhood a sprite's lot is in: its id and name. */
  neighbourhoodOf(sprite: string): { id: string; name: string };
}

/** The lots v1 picks buildings from, in layout order: occupied shops, then every home. */
export const townLots = (layout: TownLayout): Lot[] => [
  ...layout.lots.filter((l) => l.kind === 'shop' && l.vacantSince === undefined),
  ...layout.lots.filter((l) => l.kind === 'house'),
];

export function wholeTown(seed: string, year: number): WholeTown {
  const v1 = townStreets(seed, year);
  const { w, layout } = v1;
  const buildings = makeBuildings(w, layout, year);
  // v1's names first, in v1's naming order (entry() names in first-call order, so b0.. come out the same)
  const v1Sprites = [...v1.lotOf.keys()].sort((a, b) => Number(a.slice(1)) - Number(b.slice(1)));
  for (const s of v1Sprites) {
    const got = buildings.entry(v1.lotOf.get(s)!);
    if (got !== s) throw new Error(`whole town: ${s} renamed ${got}`);
  }
  for (const l of townLots(layout)) buildings.entry(l);
  const sprites = [...buildings.lotOf.keys()];
  const v1Place = new Map<string, { street: number; index: number }>();
  v1.streets.forEach((st, street) => st.buildings.forEach((s, index) => { if (!v1Place.has(s)) v1Place.set(s, { street, index }); }));
  return {
    seed, year, w, layout, v1, buildings, sprites, lotOf: buildings.lotOf, v1Place,
    neighbourhoodOf: (sprite) => {
      const nb = neighborhoodOf(layout.plan, buildings.lotOf.get(sprite)!.neighborhood);
      return { id: nb.id, name: nb.name };
    },
  };
}

/** A building's place in the walkable town: its row (street.json v2 `town.rows[].id`) and its index along the row. */
export interface Place { row: string | number; index: number; /** The row building's `lot`, as street.json v2 gives it. */ lot?: string | number }

/**
 * Where each sprite stands, from a street.json v2 (`town.rows[].buildings[].sprite`), if [path] is one for this town and
 * year; an empty map otherwise (v1, another town, no file), with the reason in [why].
 */
export function placesIn(path: string, seed: string, year: number, why: (s: string) => void = () => {}): Map<string, Place> {
  const out = new Map<string, Place>();
  if (!existsSync(path)) { why(`${path}: no such file`); return out; }
  type Rows = { seed?: string; year?: number; rows?: { id: string | number; buildings?: { sprite: string; lot?: string | number }[] }[] };
  const sj = JSON.parse(readFileSync(path, 'utf8')) as Rows & { version?: number; town?: string | Rows; graph?: Rows };
  // TOWN.md puts the graph under `town`; v1 already uses `town` for the seed, so the graph may sit under another key or
  // at the top: take the first that has rows
  const town = [sj.town, sj.graph, sj].find((o): o is Rows => typeof o === 'object' && o !== null && Array.isArray(o.rows));
  if (sj.version !== 2 || !town?.rows) { why(`${path} is not street.json v2: rows not known yet`); return out; }
  const s0 = town.seed ?? (typeof sj.town === 'string' ? sj.town : sj.seed);
  const y0 = town.year ?? sj.year;
  if (s0 !== seed || y0 !== year) { why(`${path} is ${s0} ${y0}, not ${seed} ${year}`); return out; }
  for (const r of town.rows) (r.buildings ?? []).forEach((b, index) => { if (!out.has(b.sprite)) out.set(b.sprite, b.lot === undefined ? { row: r.id, index } : { row: r.id, index, lot: b.lot }); });
  return out;
}
