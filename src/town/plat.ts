import { rand } from '../sim/rng';
import { archetypeOf, type ArchetypeId } from '../sim/archetype';
import { POPULATION_BY, POPULATION_FROM, populationIn } from './population';
import { storeNeeds } from './stores';
import { waterKind } from './water';
import type { Rect, Road } from './kinds';
import { recipeOf } from './recipe';

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
//  - the RIVER (a seed with one) through the middle of the core in a column of its own, the core straddling it (about as
//    many blocks on each bank, the civic block one block from it), Main Street and a parallel street or two on bridges,
//    its banks a park and the riverwalk;
//  - every other block given a DISTRICT (old town, West Main, across the tracks, post-war, heights, later subdivisions,
//    the edge) by where it lies, and the streets between blocks with the plan's exceptions (few crossings of the
//    track, few bridges, cul-de-sac stubs in the later subdivisions).
//
// The seed varies the block widths and depths, the plan form (parallel rail or T), which side the rail band and the
// strip take (the plan is mirrored), which side of the civic block the river takes, the strip's and the core's length. Everything is
// built in an abstract frame (u along Main Street towards the strip, v towards the rail side) and mirrored at the end, so
// the streets stay axis-aligned rectangles split at every junction (src/town/network.ts's convention: a crossing's
// square belongs to neither street, Main Street is never split). src/town/districts.ts lays the lots and the reserved
// plots into the blocks.

export type District = 'core' | 'square' | 'civic' | 'old' | 'westmain' | 'worker' | 'rail' | 'strip' | 'postwar' | 'heights'
  | 'late' | 'edge' | 'river' | 'grounds';

/**
 * The capital (the owner's test city, CLAUDE.md "Hand-made hub towns plus procedural fill"): it gets every hand-made model and a
 * column of GROUNDS past the highway's far end, big blocks for the landmarks that need acres (the Great Meridian, the Eyrie, the
 * school campus, the rest area, the roadside giants). Other towns are platted as before.
 */
export const CAPITAL_SEED = 'gamma';
export const isCapital = (seed: string): boolean => seed === CAPITAL_SEED;
/** The grounds column's width and its blocks' depths (tiles; north of Main Street first, nearest it first), sized for the models (the third south block: the ballpark). */
export const GROUNDS = { w: 200, north: [62, 80], south: [30, 80, 80] } as const;

/** The year each district is platted (its streets and first plots may be built from then on). */
export const PLATTED: Record<District, number> = {
  core: 1885, square: 1885, civic: 1890, old: 1895, westmain: 1890, worker: 1905, rail: 1888, strip: 1950, postwar: 1947,
  heights: 1958, late: 1975, edge: 1985, river: 1885, grounds: 1885,
};

/** H a crossroads hamlet and V a village (only from a world place's recipe, src/town/recipe.ts), then the towns S, M, L. */
export type SizeClass = 'H' | 'V' | 'S' | 'M' | 'L';
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
  /** The river's course as rects of channel (plan tiles): one run down its column. */
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
  const r = recipeOf(seed);
  if (r) return r.size;
  const p = platPeak(seed);
  return p < 5000 ? 'S' : p < 9000 ? 'M' : 'L';
}

/** County seat: a seed roll by size (about 35% of L towns, 15% of M, 5% of S) until the world has counties. */
export function isCountySeat(seed: string): boolean {
  const rc = recipeOf(seed);
  if (rc) return rc.countySeat;
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
  // (a world place's recipe says whether a rail line stops here; a hamlet is never a T plan: it is a crossroads)
  const rail = recipeOf(seed)?.rail ?? true;
  /** A hamlet or a village (a world place's small sizes): one or two blocks each way from the crossroads. */
  const tiny = size === 'H', small = size === 'H' || size === 'V' || size === 'S';
  const form: PlanForm = rail && !tiny && R('form') < (type === 'farm' ? 0.45 : 0.2) ? 'T' : 'parallel';
  const flipX = R('flipX') < 0.3;
  const flipY = R('flipY') < 0.35;

  // ---------------------------------------------------------------------------------------------- the grid
  const coreBase = small ? 1 : 2;
  let coreW = tiny ? 0 : Math.min(3, Math.max(1, coreBase + (seat ? 1 : 0) - (!small && R('coreW') < 0.3 ? 1 : 0)));
  let coreE = tiny ? 1 : Math.min(3, Math.max(1, coreBase + (seat ? 1 : 0) + (size === 'L' && R('coreE') < 0.5 ? 1 : 0)));
  // A river town's downtown STRADDLES the river (the owner's choice, "river through downtown"): Main Street crosses it in
  // the middle of the core, the civic block one block from it on one bank (a core block between them), and about as many
  // core blocks on the other bank as on the civic block's (at most one more or fewer). [far] core blocks lie beyond the
  // civic block, away from the river; [other] on the far bank. The river takes the strip's side of the civic block or the
  // other, by the seed (never leaving the civic block on the core's strip end: the strip starts past a core block).
  let riverSide: 1 | -1 = 1, other = 0;
  if (river) {
    const far = small ? 0 : Math.min(2, 1 + (seat || (size === 'L' && R('coreFar') < 0.5) ? 1 : 0));
    other = tiny ? 1 : Math.min(3, Math.max(1, far + 2 - (R('coreOther') < 0.4 ? 1 : 0)));
    riverSide = far > 0 && R('riverSide') < 0.5 ? -1 : 1;
    // (the river's column goes in between the civic side's core and the far bank's below)
    if (riverSide > 0) { coreW = far; coreE = 1 + other; } else { coreW = 1 + other; coreE = far; }
  }
  const westCols = small ? 1 : size === 'M' ? jit(1, 2, 'westCols') : jit(2, 3, 'westCols');
  const stripCols = tiny || size === 'V' ? 1 : size === 'S' ? 2 : size === 'M' ? jit(2, 3, 'stripCols') : 3;
  const edgeW = needs.superstore !== undefined ? jit(146, 154, 'edgeW') : needs.supermarket !== undefined ? jit(98, 106, 'edgeW') : jit(70, 84, 'edgeW');
  const cols: Col[] = [];
  for (let i = westCols - 1; i >= 1; i--) cols.push({ kind: 'outer', w: jit(44, 50, 'cw', 'o', i) });
  cols.push({ kind: 'westmain', w: jit(44, 48, 'cw', 'wm') });
  if (form === 'T' && rail) cols.push({ kind: 'railcol', w: 44 });
  for (let i = coreW; i >= 1; i--) cols.push({ kind: 'core', w: jit(42, 48, 'cw', 'cW', i) });
  const civicCol = cols.length;
  cols.push({ kind: 'civic', w: seat ? 44 : jit(42, 44, 'cw', 'civic') });
  for (let i = 1; i <= coreE; i++) cols.push({ kind: 'core', w: jit(42, 48, 'cw', 'cE', i) });
  for (let i = 1; i <= stripCols; i++) cols.push({ kind: 'strip', w: jit(60, 68, 'cw', 's', i) });
  // (a hamlet ends at its strip: no edge column, no bypass)
  if (!tiny) cols.push({ kind: 'edge', w: edgeW });

  // the river's column through the core: between the core block beside the civic block and the far bank's core
  let riverB = -1;
  if (river) {
    riverB = riverSide > 0 ? civicCol + 2 : civicCol - 1;
    cols.splice(riverB, 0, { kind: 'river', w: jit(32, 36, 'riverW') });
  }
  const civic = cols.findIndex((c) => c.kind === 'civic');

  const north = tiny ? 3 : size === 'V' || size === 'S' ? 2 : size === 'M' ? 3 : jit(3, 4, 'north');
  // (the superstore needs about 100 tiles of depth north of the highway in the edge column)
  const northRows = needs.superstore !== undefined ? Math.max(north, 3) : north;
  const rows: Row[] = [];
  for (let i = northRows; i >= 2; i--) rows.push({ kind: i === northRows && northRows >= 3 ? 'outer' : i === 2 ? 'civic2' : 'north', h: i === northRows && northRows >= 3 ? jit(44, 50, 'rh', 'o') : jit(39, 44, 'rh', i) });
  const coreNRow = rows.length;
  rows.push({ kind: 'coreN', h: seat ? 44 : jit(36, 40, 'rh', 'cN') });
  rows.push({ kind: 'coreS', h: jit(40, 43, 'rh', 'cS') });
  if (form === 'parallel' && rail) {
    rows.push({ kind: 'rail', h: 44 });
    const across = size === 'L' ? 2 : 1;
    for (let i = 1; i <= across; i++) rows.push({ kind: 'across', h: jit(40, 46, 'rh', 'a', i) });
  } else {
    // (a T plan's rows south of the core, or a town no railroad reaches: its homes south of Main Street too)
    const south = small && !tiny ? 1 : 2;
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
  /** Does the river run through cell (ci, rj)? (its column, from end to end) */
  const riverHere = (ci: number, _rj: number): boolean => riverCol(ci);
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
      // (a hamlet's store row: the one block of shops beside the crossroads on Main Street's civic side; houses across)
      if (tiny && c.kind === 'core' && r.kind === 'coreS') return 'old';
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
    if (r.kind === 'civic2' && small && (c.kind === 'westmain' || c.kind === 'outer')) return 'heights';
    if (r.kind === 'north' && !small) return ci < civic - 1 || ci > civic + 1 ? 'postwar' : 'old';
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
  // (Main Street, the core's back streets on both sides, the street past the track)
  const bridgeRows = new Set<number>([mainRow, mainRow + 1, mainRow + 1 + (form === 'parallel' ? 1 : 0), coreNRow]);
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
        : rail && i === depotCol && (j === coreNRow || j === mainRow) ? 'depot' : i === courtCol && j < mainRow ? 'court'
        : railcol >= 0 && (i === railcol || i === railcol + 1) ? 'railroad'
        : riverCol(i) || riverCol(i - 1) ? 'river' : 'street';
      add({ x: x - 1, y: lo, w: 2, h: hi - lo, main: false, horizontal: false }, cls);
    }
  }

  // ---- a hamlet is a crossroads: Main Street (the road through) and the one street crossing it beside the civic block
  // (the road to the next place), no streets round its blocks; its houses front those two roads, the fields behind them
  if (tiny) {
    const crossX = X[depotCol]! - 1;
    let y0 = Infinity, y1 = -Infinity;
    for (let i = roads.length - 1; i >= 0; i--) {
      const r = roads[i]!;
      if (r.main || (rail && roadClass[i] === 'railroad')) continue;
      if (!r.horizontal && r.x === crossX && !(rail && rows[rows.findIndex((_, j) => Y[j]! < r.y && r.y < Y[j + 1]!)]?.kind === 'rail')) { y0 = Math.min(y0, r.y); y1 = Math.max(y1, r.y + r.h); }
      roads.splice(i, 1); roadClass.splice(i, 1);
    }
    // (the cross street in one piece from the hamlet's north edge to its south, a little past its last houses: Main
    // Street's square stays Main Street's)
    y0 -= 8; y1 += 8;
    const mainR = roads.find((r) => r.main)!;
    if (y0 < mainR.y) { roads.push({ x: crossX, y: y0, w: 2, h: mainR.y - y0, main: false, horizontal: false }); roadClass.push('street'); }
    if (y1 > mainR.y + mainR.h) { roads.push({ x: crossX, y: mainR.y + mainR.h, w: 2, h: y1 - mainR.y - mainR.h, main: false, horizontal: false }); roadClass.push('street'); }
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
  // the capital's rail column: the two blocks at its far end (no street between them) made one, 82 tiles along the track, for the grain elevator
  // (the third intake: its 1950 state is 71 m of bins along the siding; the column's blocks are 36-42 tiles long)
  if (isCapital(seed) && railcol >= 0) {
    const rb = blocks.filter((b) => b.col === railcol - civic && b.district === 'rail' && (b.row === 1 || b.row === 2));
    if (rb.length === 2) {
      const top = Math.min(...rb.map((b) => b.y)), bot = Math.max(...rb.map((b) => b.y + b.h));
      const keep = rb.find((b) => b.row === 1)!;
      keep.y = top; keep.h = bot - top;
      blocks.splice(blocks.indexOf(rb.find((b) => b !== keep)!), 1);
    }
  }

  // ---------------------------------------------------------------------------------------------- the capital's grounds
  // (abstract frame, past the edge column: Main Street runs on through it; a street between it and the edge, a street between each
  // of its blocks; the blocks north of Main Street reach past the grid's top, as far as the models need)
  let groundsTop = Y[0]!, groundsBottom = Y[rows.length]!, groundsRight = X[cols.length]!;
  if (isCapital(seed)) {
    const gx0 = X[cols.length]!, G = GROUNDS.w;
    const mainY = Y[mainRow]!;
    const mainRoad = roads.find((r) => r.main)!;
    mainRoad.w += G;
    const northH = GROUNDS.north.reduce((a, b) => a + b + 4, 0), southH = GROUNDS.south.reduce((a, b) => a + b + 4, 0);
    groundsTop = Math.min(Y[0]!, mainY - northH); groundsBottom = Math.max(Y[rows.length]!, mainY + southH); groundsRight = gx0 + G;
    add({ x: gx0 - 1, y: groundsTop - 1, w: 2, h: groundsBottom - groundsTop + 2, main: false, horizontal: false }, 'street');
    // blocks outward from Main Street, a street (2 tiles, 2 more of verge each side) between them
    let yN = mainY, yS = mainY;
    GROUNDS.north.forEach((h, k) => {
      const y1 = yN - 2, y0 = y1 - h;
      blocks.push({ x: gx0 + 2, y: y0, w: G - 4, h, col: cols.length - civic, row: -1 - k, district: 'grounds', faces: [] });
      yN = y0 - 2;
      add({ x: gx0 + 1, y: yN - 1, w: G - 2, h: 2, main: false, horizontal: true }, 'street');
    });
    GROUNDS.south.forEach((h, k) => {
      const y0 = yS + 2;
      blocks.push({ x: gx0 + 2, y: y0, w: G - 4, h, col: cols.length - civic, row: k, district: 'grounds', faces: [] });
      yS = y0 + h + 2;
      add({ x: gx0 + 1, y: yS - 1, w: G - 2, h: 2, main: false, horizontal: true }, 'street');
    });
  }

  // ---------------------------------------------------------------------------------------------- the rail band
  let railBand: RailBand | null = null;
  const railRow = rows.findIndex((r) => r.kind === 'rail');
  if (railRow >= 0) {
    const yT = Y[railRow]!, yB = Y[railRow + 1]!;
    railBand = { rect: { x: X[0]! - 40, y: yT + 2, w: X[cols.length]! - X[0]! + 80, h: yB - yT - 4 }, horizontal: true, track: yT + 26, depot: { x: X[depotCol]!, y: yT + 2 }, townSide: -1 };
  } else if (railcol >= 0) {
    const xL = X[railcol]!, xR = X[railcol + 1]!;
    railBand = { rect: { x: xL + 2, y: Y[0]! - 40, w: xR - xL - 4, h: Y[rows.length]! - Y[0]! + 80 }, horizontal: false, track: xL + 18, depot: { x: xR - 2, y: Y[mainRow]! - 20 }, townSide: 1 };
  }

  // ---------------------------------------------------------------------------------------------- the river's course
  let riverOut: Plat['river'] = null;
  if (riverB >= 0) {
    const channel: Rect[] = [], column: Rect[] = [];
    const top = Math.min(Y[0]! - 80, groundsTop - 40), bottom = Math.max(Y[rows.length]! + 80, groundsBottom + 40); // (past the capital's grounds too: the plan's top and bottom rows)
    const cB = X[riverB]! + cols[riverB]!.w / 2;
    channel.push({ x: cB - 5, y: top, w: 10, h: bottom - top });
    column.push({ x: X[riverB]!, y: top, w: cols[riverB]!.w, h: bottom - top });
    riverOut = { channel, column };
  }

  // ---------------------------------------------------------------------------------------------- alleys (abstract)
  // filled in by the districts (src/town/districts.ts) where it lays the lots; none here
  const alleys: Rect[] = [];

  // ---------------------------------------------------------------------------------------------- mirror and move
  const W0 = X[0]! - MARGIN - 80, W1 = groundsRight + MARGIN + 80;
  const H0 = groundsTop - MARGIN - 80, H1 = groundsBottom + MARGIN + 80;
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
