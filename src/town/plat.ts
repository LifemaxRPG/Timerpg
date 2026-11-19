import { rand } from '../sim/rng';
import { archetypeOf, type ArchetypeId } from '../sim/archetype';
import { POPULATION_BY, POPULATION_FROM, populationIn } from './population';
import { storeNeeds } from './stores';
import { waterKind } from './water';
import type { Rect, Road } from './kinds';

// THE PLATTING (kotlin-street/docs/TOWN_PLAN.md): the town surveyed into blocks before anything is built, the way an
// American town was laid out along its railroad and round its square. A pure function of the seed:
//
//  - a GRID of block columns (west to east) and rows (north to south), measured between street centre lines, with the
//    civic block (the plaza and town hall, or a county seat's courthouse square) on the north side of Main Street;
//  - Main Street east-west through the downtown CORE (1-3 blocks each side of the civic block, by size and the seed),
//    West Main beyond the core's west end, the HIGHWAY STRIP east of it and the BYPASS at the strip's far end;
//  - the RAIL BAND: in most towns a row of the grid one block south of Main Street (Railroad Avenue on its town side,
//    South Street on the other, the track between, the depot at the foot of Depot Street, the civic block's side street);
//    in a T-plan town a column at the core's west end (Main Street runs up from the depot);
//  - the RIVER (a seed with one) through the core in a column of its own, Main Street and a parallel street or two on
//    bridges, its banks a park; it may jog a block sideways through the back lots of the core's north row (the shops
//    there back onto it);
//  - every other block given a DISTRICT (old town, West Main, across the tracks, post-war, heights, later subdivisions,
//    the edge) by where it lies, and the streets between blocks with the plan's exceptions (few crossings of the
//    track, few bridges, cul-de-sac stubs in the later subdivisions).
//
// The seed varies the block widths and depths, the plan form (parallel rail or T), which side the rail band and the
// strip take (the plan is mirrored), the river's column and its jog, the strip's and the core's length. Everything is
// built in an abstract frame (u along Main Street towards the strip, v towards the rail side) and mirrored at the end, so
// the streets stay axis-aligned rectangles split at every junction (src/town/network.ts's convention: a crossing's
// square belongs to neither street, Main Street is never split). src/town/districts.ts lays the lots and the reserved
// plots into the blocks.

export type District = 'core' | 'square' | 'civic' | 'old' | 'westmain' | 'worker' | 'rail' | 'strip' | 'postwar' | 'heights'
  | 'late' | 'edge' | 'river';

/** The year each district is platted (its streets and first plots may be built from then on). */
export const PLATTED: Record<District, number> = {
  core: 1885, square: 1885, civic: 1890, old: 1895, westmain: 1890, worker: 1905, rail: 1888, strip: 1950, postwar: 1947,
  heights: 1958, late: 1975, edge: 1985, river: 1885,
};

export type SizeClass = 'S' | 'M' | 'L';
export type PlanForm = 'parallel' | 'T';

export interface Block extends Rect {
  /** The grid cell (abstract column and row: column 0 the civic block's, row -1 north of Main Street, 0 south of it). */
  col: number;
  row: number;
  district: District;
  /** Which of its faces (in the final, mirrored frame) front a street. */
  faces: ('N' | 'S' | 'E' | 'W')[];
  /** It fronts Main Street on this face. */
  main?: 'N' | 'S';
}

export interface RailBand {
  /** The band's ground between its two streets' pavements (plan tiles). */
  rect: Rect;
  horizontal: boolean;
  /** The track's centre line: y (horizontal band) or x. */
  track: number;
  /** The depot's spot: the foot of Depot Street on the band's town side. */
  depot: { x: number; y: number };
  /** The side of the track the town (Main Street) is on: -1 above/west, +1 below/east. */
  townSide: -1 | 1;
}

export interface Plat {
  seed: string;
  size: SizeClass;
  type: ArchetypeId;
  form: PlanForm;
  countySeat: boolean;
  /** Mirrored: the strip runs west (flipX) / the rail side is north (flipY). */
  flipX: boolean;
  flipY: boolean;
  /** Streets (Main Street main and horizontal), split at every junction. */
  roads: Road[];
  /** The street each road is (for names and the export): main, highway, bypass, depot, court, railroad, south, river, street, stub. */
  roadClass: string[];
  blocks: Block[];
  /** The civic block's ground (the square or the plaza's block). */
  civic: Block;
  railBand: RailBand | null;
  /** The river's course as rects of channel (plan tiles): vertical runs down its column, the jog across a block's back. */
  river: { channel: Rect[]; column: Rect[] } | null;
  /** Service alleys and old-town alleys (walkable ground, not streets). */
  alleys: Rect[];
  /** Main Street's top edge, and the centre of the civic block's Main Street front. */
  mainY: number;
  center: { x: number; y: number };
  /** Canvas (all of the above inside it, with a margin). */
  width: number;
  height: number;
}

/** Margin round the plat on its canvas (tiles): room for the woods; plan.ts crops to what is built. */
const MARGIN = 30;

interface Col { kind: 'outer' | 'westmain' | 'core' | 'civic' | 'strip' | 'edge' | 'river' | 'railcol'; w: number }
interface Row { kind: 'outer' | 'north' | 'civic2' | 'coreN' | 'coreS' | 'rail' | 'across' | 'south'; h: number }

/** The town's most inhabitants in any year of its natural history (36 a simulated person, as glbSites.ts peakPeople). */
export function platPeak(seed: string): number {
  let m = 0;
  for (let y = POPULATION_FROM; y <= POPULATION_BY; y++) m = Math.max(m, populationIn(seed, y) * 36);
  return m;
}

export function sizeOf(seed: string): SizeClass {
  const p = platPeak(seed);
  return p < 5000 ? 'S' : p < 9000 ? 'M' : 'L';
}

/** County seat: a seed roll by size (about 35% of L towns, 15% of M, 5% of S) until the world has counties. */
export function isCountySeat(seed: string): boolean {
  const s = sizeOf(seed);
  return rand(seed, 'plat', 'seat') < (s === 'L' ? 0.35 : s === 'M' ? 0.15 : 0.05);
}

const cache = new Map<string, Plat>();

export function platTown(seed: string): Plat {
  const hit = cache.get(seed);
  if (hit) return hit;
  const R = (...k: (string | number)[]): number => rand(seed, 'plat', ...k);
  const jit = (lo: number, hi: number, ...k: (string | number)[]): number => lo + Math.floor(R(...k) * (hi - lo + 1));
  const size = sizeOf(seed);
  const type = archetypeOf(seed).id;
  const seat = isCountySeat(seed);
  const river = waterKind(seed) === 'river';
  const needs = storeNeeds(seed);
  const form: PlanForm = R('form') < (type === 'farm' ? 0.45 : 0.2) ? 'T' : 'parallel';
  const flipX = R('flipX') < 0.3;
  const flipY = R('flipY') < 0.35;

  // ---------------------------------------------------------------------------------------------- the grid
  const coreBase = size === 'S' ? 1 : 2;
  const coreW = Math.min(3, Math.max(1, coreBase + (seat ? 1 : 0) - (size !== 'S' && R('coreW') < 0.3 ? 1 : 0)));
  const coreE = Math.min(3, coreBase + (seat ? 1 : 0) + (size === 'L' && R('coreE') < 0.5 ? 1 : 0));
  const westCols = size === 'S' ? 1 : size === 'M' ? jit(1, 2, 'westCols') : jit(2, 3, 'westCols');
  const stripCols = size === 'S' ? 2 : size === 'M' ? jit(2, 3, 'stripCols') : 3;
  const edgeW = needs.superstore !== undefined ? jit(146, 154, 'edgeW') : needs.supermarket !== undefined ? jit(98, 106, 'edgeW') : jit(70, 84, 'edgeW');
  const cols: Col[] = [];
  for (let i = westCols - 1; i >= 1; i--) cols.push({ kind: 'outer', w: jit(44, 50, 'cw', 'o', i) });
  cols.push({ kind: 'westmain', w: jit(44, 48, 'cw', 'wm') });
  if (form === 'T') cols.push({ kind: 'railcol', w: 44 });
  for (let i = coreW; i >= 1; i--) cols.push({ kind: 'core', w: jit(42, 48, 'cw', 'cW', i) });
  const civicCol = cols.length;
  cols.push({ kind: 'civic', w: seat ? 44 : jit(42, 44, 'cw', 'civic') });
  for (let i = 1; i <= coreE; i++) cols.push({ kind: 'core', w: jit(42, 48, 'cw', 'cE', i) });
  for (let i = 1; i <= stripCols; i++) cols.push({ kind: 'strip', w: jit(60, 68, 'cw', 's', i) });
  cols.push({ kind: 'edge', w: edgeW });

  // the river's column (or two, when it jogs): through the core, at the core's edge in a mill town
  let riverB = -1, riverA = -1;
  if (river) {
    const coreIdx = cols.map((c, i) => (c.kind === 'core' || c.kind === 'civic' ? i : -1)).filter((i) => i >= 0);
    const first = coreIdx[0]!, last = coreIdx[coreIdx.length - 1]!;
    // gaps: before index g (between g-1 and g); not touching the civic block
    const inner = coreIdx.filter((i) => i > first && i !== civicCol && i !== civicCol + 1);
    const edges = [first, last + 1];
    const mill = type === 'mill' || type === 'mining';
    const options = mill || !inner.length ? edges : R('riverEdge') < 0.25 ? edges : inner;
    riverB = options[Math.floor(R('riverB') * options.length)]!;
    cols.splice(riverB, 0, { kind: 'river', w: jit(32, 36, 'riverW') });
    // (indexes past the insertion move along)
    const civicNow = civicCol + (riverB <= civicCol ? 1 : 0);
    // the jog: north of the core the river runs a block or two further from the square, through the core's north back lots
    if (R('jog') < 0.55) {
      const away = riverB <= civicNow ? -1 : 1;
      const step = 1 + (R('jogStep') < 0.35 ? 1 : 0);
      let a = riverB + away * step + (away > 0 ? 1 : 0);
      // (stay inside the town's columns, west of the strip)
      const lastStrip = cols.findIndex((c) => c.kind === 'strip');
      if (a > 0 && a < cols.length - 1 && (lastStrip < 0 || a <= lastStrip)) {
        cols.splice(a, 0, { kind: 'river', w: jit(30, 34, 'riverWA') });
        if (a <= riverB) riverB++;
        riverA = a;
      }
    }
  }
  const civic = cols.findIndex((c) => c.kind === 'civic');

  const north = size === 'S' ? 2 : size === 'M' ? 3 : jit(3, 4, 'north');
  // (the superstore needs about 100 tiles of depth north of the highway in the edge column)
  const northRows = needs.superstore !== undefined ? Math.max(north, 3) : north;
  const rows: Row[] = [];
  for (let i = northRows; i >= 2; i--) rows.push({ kind: i === northRows && northRows >= 3 ? 'outer' : i === 2 ? 'civic2' : 'north', h: i === northRows && northRows >= 3 ? jit(44, 50, 'rh', 'o') : jit(39, 44, 'rh', i) });
  const coreNRow = rows.length;
  rows.push({ kind: 'coreN', h: seat ? 44 : jit(36, 40, 'rh', 'cN') });
  rows.push({ kind: 'coreS', h: jit(40, 43, 'rh', 'cS') });
  if (form === 'parallel') {
    rows.push({ kind: 'rail', h: 44 });
    const across = size === 'L' ? 2 : 1;
    for (let i = 1; i <= across; i++) rows.push({ kind: 'across', h: jit(40, 46, 'rh', 'a', i) });
  } else {
    const south = size === 'S' ? 1 : 2;
    for (let i = 1; i <= south; i++) rows.push({ kind: 'south', h: jit(40, 46, 'rh', 'so', i) });
  }
  const mainRow = coreNRow + 1; // Main Street runs along the top of this row

  // abstract boundaries: X[i] the centre line west of column i (X[cols.length] the east end), Y[j] north of row j
  const X: number[] = [0];
  for (const c of cols) X.push(X[X.length - 1]! + c.w);
  const Y: number[] = [0];
  for (const r of rows) Y.push(Y[Y.length - 1]! + r.h);

  // ---------------------------------------------------------------------------------------------- districts
  const riverCol = (ci: number): boolean => cols[ci]!.kind === 'river';
  /** Does the river run through cell (ci, rj)? (column A north of the core's north row, B from it south; both in the jog row) */
  const riverHere = (ci: number, rj: number): boolean => {
    if (!riverCol(ci)) return false;
    if (riverA < 0) return true;
    if (ci === riverA) return rj <= coreNRow;
    return rj >= coreNRow;
  };
  const stripStart = cols.findIndex((c) => c.kind === 'strip');
  const westmain = cols.findIndex((c) => c.kind === 'westmain');
  const railcol = cols.findIndex((c) => c.kind === 'railcol');
  const isCore = (ci: number): boolean => cols[ci]!.kind === 'core' || cols[ci]!.kind === 'civic'
    || (cols[ci]!.kind === 'river' && ci > Math.min(...cols.map((c, i) => (c.kind === 'core' ? i : 99))) && ci < Math.max(...cols.map((c, i) => (c.kind === 'core' ? i : -1))));
  function districtOf(ci: number, rj: number): District {
    const c = cols[ci]!, r = rows[rj]!;
    if (riverHere(ci, rj)) return 'river';
    if (c.kind === 'railcol') return 'rail';
    if (r.kind === 'rail') return 'rail';
    if (c.kind === 'edge') return r.kind === 'coreN' || r.kind === 'coreS' || ((r.kind === 'north' || r.kind === 'civic2' || r.kind === 'outer') && needs.superstore !== undefined) ? 'edge' : 'late';
    const westOfTrack = railcol >= 0 && ci < railcol;
    if (r.kind === 'coreN' || r.kind === 'coreS') {
      if (ci === civic && r.kind === 'coreN') return 'square';
      if (isCore(ci) || c.kind === 'river') return 'core';
      if (c.kind === 'strip') return 'strip';
      if (westOfTrack) return 'worker';
      return c.kind === 'westmain' ? 'westmain' : 'old';
    }
    if (r.kind === 'across') return c.kind === 'strip' ? (ci === stripStart ? 'postwar' : 'late') : 'worker';
    if (westOfTrack) return r.kind === 'outer' ? 'heights' : 'worker';
    if (c.kind === 'strip') return r.kind === 'civic2' || r.kind === 'north' ? 'postwar' : 'late';
    if (r.kind === 'south') return isCore(ci) && rj === mainRow + 1 ? 'old' : ci < civic ? 'postwar' : 'late';
    if (r.kind === 'outer') return ci <= civic ? 'heights' : 'late';
    if (r.kind === 'civic2' && ci === civic) return 'civic';
    if (r.kind === 'civic2' && size === 'S' && (c.kind === 'westmain' || c.kind === 'outer')) return 'heights';
    if (r.kind === 'north' && size !== 'S') return ci < civic - 1 || ci > civic + 1 ? 'postwar' : 'old';
    return 'old';
  }

  // ---------------------------------------------------------------------------------------------- streets
  // (abstract frame; segments between junctions; Main Street one rect)
  const roads: Road[] = [];
  const roadClass: string[] = [];
  const add = (r: Road, cls: string): void => { roads.push(r); roadClass.push(cls); };
  const depotCol = civic + (R('depotSide') < 0.5 ? 0 : 1); // the civic block's side street running to the depot
  const courtCol = depotCol === civic ? civic + 1 : civic;
  // level crossings of a horizontal band: the core's two ends, the town's two ends, the bypass (the depot street ends at the depot)
  const coreLo = cols.findIndex((c) => c.kind === 'core' || c.kind === 'civic');
  const coreHi = cols.length - 1 - [...cols].reverse().findIndex((c) => c.kind === 'core' || c.kind === 'civic');
  const crossAt = new Set<number>([coreLo, coreHi + 1, 1, cols.length - 1]);
  // bridges over the river: Main Street, the street past the track (or one south), and one street north of the core
  const bridgeRows = new Set<number>([mainRow, mainRow + 1 + (form === 'parallel' ? 1 : 0), coreNRow]);
  for (let j = 0; j <= rows.length; j++) {
    const y = Y[j]!;
    if (j === mainRow) {
      // Main Street: from the town's west end on out past the bypass (the farms' road out continues it both ways)
      add({ x: X[0]! - 1, y: y - 1, w: X[cols.length]! - X[0]! + 2, h: 2, main: true, horizontal: true }, 'main');
      continue;
    }
    for (let i = 0; i < cols.length; i++) {
      const above = j > 0 ? districtOf(i, j - 1) : undefined, below = j < rows.length ? districtOf(i, j) : undefined;
      // the river: only the bridging streets cross it
      if (riverCol(i) && ((above === 'river' || below === 'river') && !bridgeRows.has(j))) continue;
      // the edge column's big boxes: one block north of the highway (no streets through it)
      if (cols[i]!.kind === 'edge' && above === 'edge' && below === 'edge') continue;
      // the outermost streets round the plan: only where a district is on their inner side
      if (j === 0 && below === 'river') continue;
      if (j === rows.length && above === 'river') continue;
      // the T-plan's rail column: crossed by Main Street and one street north of the core only
      if (cols[i]!.kind === 'railcol' && j !== coreNRow) continue;
      const cls = rows[j]?.kind === 'rail' && j > 0 ? 'railroad' : rows[j - 1]?.kind === 'rail' ? 'south' : riverCol(i) ? 'bridge' : 'street';
      add({ x: X[i]! + 1, y: y - 1, w: cols[i]!.w - 2, h: 2, main: false, horizontal: true }, cls);
    }
  }
  for (let i = 1; i < cols.length; i++) {
    const x = X[i]!;
    for (let j = 0; j < rows.length; j++) {
      const left = districtOf(i - 1, j), right = districtOf(i, j);
      // the rail band: streets cross the track only at the level crossings
      if (rows[j]!.kind === 'rail' && !crossAt.has(i)) continue;
      // the edge's big block north of the highway: no street through it
      if (left === 'edge' && right === 'edge') continue;
      const lo = Y[j]! + 1, hi = Y[j + 1]! - 1;
      // the later subdivisions: every other street a cul-de-sac stub into the long block (from the street nearer Main)
      const late = (left === 'late' || right === 'late') && left !== 'edge' && right !== 'edge' && cols[i]!.kind !== 'edge';
      if (late && R('stub', i, j) < 0.45) {
        const len = Math.min(24, hi - lo - 8);
        const fromTop = j >= mainRow;
        add({ x: x - 1, y: fromTop ? lo : hi - len, w: 2, h: len, main: false, horizontal: false }, 'stub');
        continue;
      }
      const cls = i === cols.length - 1 && cols[i]!.kind === 'edge' ? 'bypass'
        : i === depotCol && (j === coreNRow || j === mainRow) ? 'depot' : i === courtCol && j < mainRow ? 'court'
        : railcol >= 0 && (i === railcol || i === railcol + 1) ? 'railroad'
        : riverCol(i) || riverCol(i - 1) ? 'river' : 'street';
      add({ x: x - 1, y: lo, w: 2, h: hi - lo, main: false, horizontal: false }, cls);
    }
  }

  // ---- keep only streets connected to Main Street (a perimeter piece between two cul-de-sac stubs goes)
  {
    const touch = (a: Rect, b: Rect): boolean => a.x <= b.x + b.w + 1 && b.x <= a.x + a.w + 1 && a.y <= b.y + b.h + 1 && b.y <= a.y + a.h + 1;
    const seen = new Set<number>(roads.map((r, i) => (r.main ? i : -1)).filter((i) => i >= 0));
    for (let grew = true; grew;) {
      grew = false;
      roads.forEach((r, i) => { if (!seen.has(i) && [...seen].some((j) => touch(r, roads[j]!))) { seen.add(i); grew = true; } });
    }
    for (let i = roads.length - 1; i >= 0; i--) if (!seen.has(i)) { roads.splice(i, 1); roadClass.splice(i, 1); }
  }

  // ---------------------------------------------------------------------------------------------- blocks
  const blocks: Block[] = [];
  for (let i = 0; i < cols.length; i++) for (let j = 0; j < rows.length; j++) {
    const d = districtOf(i, j);
    blocks.push({ x: X[i]! + 2, y: Y[j]! + 2, w: cols[i]!.w - 4, h: rows[j]!.h - 4, col: i - civic, row: j - mainRow, district: d, faces: [] });
  }
  // the edge's big boxes: its blocks north of the highway made one
  const edgeN = blocks.filter((b) => b.col === cols.length - 1 - civic && b.district === 'edge' && b.row < 0);
  if (edgeN.length > 1) {
    const top = Math.min(...edgeN.map((b) => b.y)), bot = Math.max(...edgeN.map((b) => b.y + b.h));
    const keep = edgeN.find((b) => b.row === -1)!;
    keep.y = top; keep.h = bot - top;
    for (const b of edgeN) if (b !== keep) blocks.splice(blocks.indexOf(b), 1);
  }

  // ---------------------------------------------------------------------------------------------- the rail band
  let railBand: RailBand | null = null;
  const railRow = rows.findIndex((r) => r.kind === 'rail');
  if (railRow >= 0) {
    const yT = Y[railRow]!, yB = Y[railRow + 1]!;
    railBand = { rect: { x: X[0]! - 40, y: yT + 2, w: X[cols.length]! - X[0]! + 80, h: yB - yT - 4 }, horizontal: true, track: yT + 26, depot: { x: X[depotCol]!, y: yT + 2 }, townSide: -1 };
  } else if (railcol >= 0) {
    const xL = X[railcol]!, xR = X[railcol + 1]!;
    railBand = { rect: { x: xL + 2, y: Y[0]! - 40, w: xR - xL - 4, h: Y[rows.length]! - Y[0]! + 80 }, horizontal: false, track: xL + 18, depot: { x: xR - 2, y: Y[mainRow]! - 2 }, townSide: 1 };
  }

  // ---------------------------------------------------------------------------------------------- the river's course
  let riverOut: Plat['river'] = null;
  if (riverB >= 0) {
    const channel: Rect[] = [], column: Rect[] = [];
    const top = Y[0]! - 80, bottom = Y[rows.length]! + 80;
    const cB = X[riverB]! + cols[riverB]!.w / 2;
    if (riverA >= 0) {
      const cA = X[riverA]! + cols[riverA]!.w / 2;
      // the jog runs through the back of the core's north row: 4-14 tiles from its north street
      const jy = Y[coreNRow]! + 4;
      channel.push({ x: cA - 5, y: top, w: 10, h: jy + 10 - top });
      channel.push({ x: Math.min(cA, cB) - 5, y: jy, w: Math.abs(cB - cA) + 10, h: 10 });
      channel.push({ x: cB - 5, y: jy, w: 10, h: bottom - jy });
      column.push({ x: X[riverA]!, y: top, w: cols[riverA]!.w, h: Y[coreNRow + 1]! - top }, { x: X[riverB]!, y: Y[coreNRow]!, w: cols[riverB]!.w, h: bottom - Y[coreNRow]! });
    } else {
      channel.push({ x: cB - 5, y: top, w: 10, h: bottom - top });
      column.push({ x: X[riverB]!, y: top, w: cols[riverB]!.w, h: bottom - top });
    }
    riverOut = { channel, column };
  }

  // ---------------------------------------------------------------------------------------------- alleys (abstract)
  // filled in by the districts (src/town/districts.ts) where it lays the lots; none here
  const alleys: Rect[] = [];

  // ---------------------------------------------------------------------------------------------- mirror and move
  const W0 = X[0]! - MARGIN - 80, W1 = X[cols.length]! + MARGIN + 80;
  const H0 = Y[0]! - MARGIN - 80, H1 = Y[rows.length]! + MARGIN + 80;
  const fx = (r: Rect): Rect => (flipX ? { ...r, x: W1 - (r.x + r.w) + W0 } : r);
  const fy = (r: Rect): Rect => (flipY ? { ...r, y: H1 - (r.y + r.h) + H0 } : r);
  const mv = <T extends Rect>(r: T): T => { const a = fy(fx(r)); return { ...r, x: a.x - W0, y: a.y - H0 }; };
  const mvPt = (p: { x: number; y: number }): { x: number; y: number } => { const a = mv({ x: p.x, y: p.y, w: 0, h: 0 }); return { x: a.x, y: a.y }; };
  const outRoads = roads.map(mv);
  const outBlocks = blocks.map((b) => mv(b));
  // faces (final frame): a face fronts a street if a road runs along it a tile away
  for (const b of outBlocks) {
    const along = (side: 'N' | 'S' | 'E' | 'W'): Road | undefined => outRoads.find((r) => {
      if (side === 'N') return r.horizontal && r.y + r.h === b.y - 1 && r.x < b.x + b.w && b.x < r.x + r.w;
      if (side === 'S') return r.horizontal && r.y === b.y + b.h + 1 && r.x < b.x + b.w && b.x < r.x + r.w;
      if (side === 'W') return !r.horizontal && r.x + r.w === b.x - 1 && r.y < b.y + b.h && b.y < r.y + r.h;
      return !r.horizontal && r.x === b.x + b.w + 1 && r.y < b.y + b.h && b.y < r.y + r.h;
    });
    for (const s of ['N', 'S', 'E', 'W'] as const) {
      const r = along(s);
      if (r) { b.faces.push(s); if (r.main && (s === 'N' || s === 'S')) b.main = s; }
    }
  }
  const civicBlock = outBlocks.find((b) => b.col === 0 && b.row === -1)!;
  let band = railBand;
  if (band) {
    const r = mv(band.rect);
    const trackPt = band.horizontal ? mvPt({ x: 0, y: band.track }) : mvPt({ x: band.track, y: 0 });
    // the depot's spot: its foot point on the town's side of the band (mirrored with it)
    const dp = mvPt(band.depot);
    const townSide: -1 | 1 = band.horizontal ? (flipY ? 1 : -1) : (flipX ? -1 : 1);
    band = { rect: r, horizontal: band.horizontal, track: band.horizontal ? trackPt.y : trackPt.x, depot: dp, townSide };
  }
  const mainRoad = outRoads[roadClass.indexOf('main')]!;
  const plat: Plat = {
    seed, size, type, form, countySeat: seat, flipX, flipY, roads: outRoads, roadClass, blocks: outBlocks, civic: civicBlock, railBand: band,
    river: riverOut ? { channel: riverOut.channel.map(mv), column: riverOut.column.map(mv) } : null,
    alleys: alleys.map(mv), mainY: mainRoad.y,
    center: { x: civicBlock.x + civicBlock.w / 2, y: civicBlock.main === 'S' ? civicBlock.y + civicBlock.h : civicBlock.y },
    width: W1 - W0, height: H1 - H0,
  };
  cache.set(seed, plat);
  return plat;
}
