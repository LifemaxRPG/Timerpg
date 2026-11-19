// The whole town as a walkable graph (kotlin-street/docs/TOWN.md, phase T2): street.json v2, from OUR game's own town
// (src/town/layout.ts layoutTown), for the Filament town. tools/export-town.ts writes it.
//
// From the layout's road rectangles to a street graph:
//  - NODES are where road centre lines meet (a vertical road's centre crossing a horizontal one's, within a few tiles of
//    both ends: the game splits a road at every crossing and leaves the crossing square to neither) and the dead ends;
//    each has its ARMS, the directions a road leaves it in. 4 arms a crossroads, 3 a T, 1 a dead end; 2 at a right
//    angle a BEND (the main street's steps), 2 in line no node at all (a crossing whose other road is not built yet).
//  - ROADS run from one decision node (not a bend) to the next, through any bends, as a polyline.
//  - ROWS are one side of one road, in their own side-scroller frame: x to the right as seen from the pavement, the
//    buildings at +normal (so they stand on the left of a walker going +x). The row's line is its building line, the
//    road's centre line moved 2 tiles out (half the 3D street's 178 units), mitred at bends; x = 0 is the start node's
//    centre (the middle of a crossing road), x = length the end node's.
//  - At each end a row knows where the walker goes: STRAIGHT on across the side street to the same side's next row,
//    TURN IN round the corner building to the side street's row on the same block (today's up-key turn), or CROSS to the
//    facing row across the road. Every transition has its way back (end.straight A -> B is start.straight B -> A).
//
// Scale (TOWN.md "Geometry"): a tile is 26.29 street units at the art's size, but the 3D street wants the building line
// 89 units from the road's centre, where the game has it 2 tiles. So every POSITION scales by k = 89 / (2 x 26.29) =
// 1.693 (a tile of position is 44.5 units), while every building keeps its art size: the buildings along a row with
// gaps of at most a tile (a terrace of shops, an apartment block between them) move as one rigid group by their
// front-centre point, and the room this opens between groups becomes yards, lawns, driveways and alleys. A group whose
// end would land in a side street's mouth (less than 89 units from its centre) is nudged back along its row.
//
// Footprints (setback from the building line and depth back from the front, in street units) are what
// tools/make_buildings.py builds for each kind of sprite (FOOT below, checked against its models.json / houses.json for
// the showcase of every sprite). The export checks each footprint fits its plot and that no two overlap; `conflicts`
// lists anything that had to be nudged or does not fit.
//
// World axes of the v2 graph: x east, z south (the map's y), y up; one unit is a street unit. Tile coordinates scale by
// SCALE. Everything is a pure function of the seed and year.
import { archetypeOf } from '../../src/sim/archetype';
import { runTimeline, worldAt } from '../../src/sim';
import type { World } from '../../src/sim/types';
import { layoutTown, type LandmarkPlot, type Lot, type Prop, type TownLayout } from '../../src/town/layout';
import { LANDMARK_SPECS, landmarkArtKey } from '../../src/town/landmarks';
import { cinemaBlock, cinemaLandmark, type CinemaFootprint, type CinemaJson } from './town-cinema';
import { storeLandmark, storesBlock, type StoreFootprint, type StoreJson } from './town-stores';
import { neighborhoodOf } from '../../src/town/neighborhoods';
import { hash, makeBuildings, townLots, type Buildings } from './town-buildings';
import { townStreets } from './town-streets';

/** Street units per tile at the art's size (BuildingSpec.W 184 per 224 px; 32 px a tile). */
export const UNIT_PER_TILE = 32 * 184 / 224;
/** Street units per art pixel. */
export const PX = 184 / 224;
/** The 3D street's width between building lines (Crossroads.MOUTH). */
export const MOUTH = 178;
/** Tiles from a road's centre to its building line in the game (1 tile of road, 1 of pavement). */
const FRONT_TILES = 2;
/** Positions scale by K: the building line 2 tiles from the centre lands MOUTH / 2 from it. */
export const K = MOUTH / 2 / (FRONT_TILES * UNIT_PER_TILE);
/** World units per tile of position. */
export const SCALE = K * UNIT_PER_TILE;
/** Tiles of slack where road ends meet a crossing road's centre (the game leaves the crossing square to neither). */
const TOL = 3;
/** Buildings along a row closer than this (tiles, art extents) move as one rigid group. */
const GROUP_GAP = 1;
/** The lane the player spawns in (CornerWalk.ARRIVE_LANE: the front band of the pavement). */
const SPAWN_LANE = 7;

type P = [number, number];
type Dir = 'E' | 'W' | 'N' | 'S';
const DV: Record<Dir, P> = { E: [1, 0], W: [-1, 0], N: [0, -1], S: [0, 1] };
const OPP: Record<Dir, Dir> = { E: 'W', W: 'E', N: 'S', S: 'N' };
/** The direction to the left of a walker heading [d] (z is south). */
const LEFT: Record<Dir, Dir> = { E: 'N', N: 'W', W: 'S', S: 'E' };
const dirOf = (a: P, b: P): Dir => (Math.abs(b[0] - a[0]) >= Math.abs(b[1] - a[1]) ? (b[0] > a[0] ? 'E' : 'W') : (b[1] > a[1] ? 'S' : 'N'));
const r3 = (v: number): number => Math.round(v * 1000) / 1000;
const w3 = (p: P): P => [r3(p[0] * SCALE), r3(p[1] * SCALE)];

// ------------------------------------------------------------------------------------------------ footprints
/** What tools/make_buildings.py builds for a sprite, in art pixels: the yard in front, the building's depth, its width. */
export interface FootPx { setback: number; depth: number; width: number }
const LANDMARK_FOOT: Record<string, [number, number]> = {
  clocktower: [32, 234], school: [80, 278], fire: [48, 184], hospital: [120, 256], post: [32, 144], police: [48, 272], hotel: [64, 200],
};
const ESTATE_FOOT: Record<string, [number, number, number]> = {
  estate_one_story_house: [104, 192, 480], estate_country_house: [120, 200, 556], estate_japanese_house: [88, 220, 470], estate_modern_house: [88, 285, 736],
};
const BLOCK_FOOT: Record<string, [number, number]> = { condo_3_44: [72, 124], condo_3_45: [72, 124], condo_5_1: [80, 86], condo_5_2: [80, 86], condo_5_3: [80, 86], condo_9: [72, 142] };
/**
 * A sprite's footprint as tools/make_buildings.py builds it (setbacks and depths from its models.json and houses.json):
 * shops and the condo stand-ins are the classic box (the side sprite's 120 px, no yard); a mobile home stands its yard's
 * front tiles back (64 px for the game's 2) and 80 / 88 px deep; villas, terraced houses, estates, apartment blocks and
 * landmarks have the setbacks and depths of their builders.
 */
export function footprintPx(key: string, artW: number, front = 2, artH = 0): FootPx {
  // the cinema (no sprite; built in the engine): a tile of forecourt, then the lobby and hall to the plot's back
  if (key.startsWith('landmark_cinema_')) return { setback: 32, depth: Math.max(artH - 32, 320), width: artW };
  // a store (no sprite; built in the engine with its car park and yards): the whole plot, its depth at position scale
  if (key.startsWith('landmark_store_')) return { setback: 0, depth: Math.floor((artH / 32) * SCALE / PX * 1000) / 1000 - 0.01, width: artW };
  const lm = key.match(/^landmark_([a-z]+)_\d+$/);
  if (lm) { const [s, d] = LANDMARK_FOOT[lm[1]!] ?? [32, 200]; return { setback: s, depth: d, width: artW }; }
  if (/^house_small_/.test(key)) return { setback: front * 32, depth: 80, width: Math.max(artW, 224) };
  if (/^house_medium_/.test(key)) return { setback: front * 32, depth: 88, width: Math.max(artW, 224) };
  if (/^villa_\d+$/.test(key)) return { setback: front * 32 + 32, depth: 171, width: Math.max(artW, 256) };
  if (/^house_terraced_/.test(key)) return { setback: front * 32 + 16, depth: 256, width: Math.max(artW, 332) };
  const e = ESTATE_FOOT[key]; if (e) return { setback: e[0], depth: e[1], width: Math.max(artW, e[2]) };
  const b = BLOCK_FOOT[key]; if (b) return { setback: b[0], depth: b[1], width: Math.max(artW, 192) };
  return { setback: 0, depth: 120, width: artW };
}

// ------------------------------------------------------------------------------------------------ the v2 json
export interface ArmJson { road: number; heading: P; mouthHalf: number }
export interface NodeJson { id: number; x: number; z: number; kind: 'cross' | 'tee' | 'bend' | 'end'; arms: ArmJson[] }
export interface RoadJson { id: number; name: string; main: boolean; a: number; b: number; pts: P[]; width: number }
export interface BuildingJson {
  sprite: string; x0: number; x1: number; setback: number; depth: number; group: number; neighbourhood: string;
  kind: 'shop' | 'home' | 'landmark'; lot: string;
}
export interface EndJson { straight?: string; turnIn?: string; cross?: string; mouth: boolean }
export interface RowJson {
  id: string; road: number; side: 'L' | 'R'; from: number; to: number; origin: P; along: P; normal: P; length: number;
  /** The building line as a polyline (world), x = distance along it; straight rows have two points. */
  pts: P[];
  buildings: BuildingJson[];
  furniture: { kind: 'lamp' | 'bench'; x: number; look: number }[];
  ends: { start: EndJson; end: EndJson };
}
export interface LotJson { id: string; sprite: string; row: string; rect: [number, number, number, number]; kind: string; neighbourhood: string; yard: string }
export interface TownMapJson {
  seed: string; year: number; k: number; unitPerTile: number; scale: number; frontTiles: number;
  spawn: { row: string; x: number; lane: number };
  civic: { road: number; name: string };
  neighbourhoods: { id: string; name: string; palette: string; yard: string; shopColour: string }[];
  nodes: NodeJson[]; roads: RoadJson[]; rows: RowJson[];
  landmarks: { id: string; kind: string; name: string; sprite: string; art: string; row: string; rect: [number, number, number, number]; era?: string; footprint?: CinemaFootprint | StoreFootprint; store?: { site: string; kind: string; state: string } }[];
  /** The town's cinema in the export's year (town-cinema.ts), when it has one. */
  cinema?: CinemaJson;
  /** The town's stores standing in the export's year (town-stores.ts): the general store, market, supermarket, superstore. */
  stores?: StoreJson[];
  plaza: { rect: [number, number, number, number]; props: { kind: string; x: number; z: number; look: number }[] };
  water: { kind: string; polys: P[][] } | null;
  bridges: { rect: [number, number, number, number]; horizontal: boolean }[];
  props: { kind: string; x: number; z: number; look: number }[];
  lots: LotJson[];
}

export interface TownGraph {
  seed: string; year: number; w: World; layout: TownLayout;
  /** street.json v2: the v1 fields (for the spawn junction) and `graph`. */
  json: Record<string, unknown> & { version: 2; graph: TownMapJson };
  graph: TownMapJson;
  buildings: Buildings;
  /** Every building that had to be nudged, or does not fit its plot, or overlaps (empty when the town is clean). */
  conflicts: string[];
  /** Props left out because they stand in a yard (the 3D models bring their own) or in a building. */
  propsDropped: number;
  /** World footprints of every building (the yard in front included), for checks. */
  footprints: { sprite: string; row: string; rect: [number, number, number, number] }[];
}

export { townLots };

const STREET_NAMES = ['Elm Street', 'Mill Road', 'Church Street', 'Station Road', 'Orchard Lane', 'Cedar Avenue', 'Union Street', 'Market Lane',
  'Willow Road', 'Bridge Street', 'Maple Avenue', 'Chapel Lane', 'Garden Street', 'Hill Road', 'Oak Street', 'Park Avenue', 'Birch Lane',
  'Rose Street', 'Meadow Road', 'King Street', 'Queen Street', 'Ash Grove', 'Linden Avenue', 'Brook Lane', 'Forge Street', 'Granary Road',
  'Holly Lane', 'Ivy Street', 'Juniper Way', 'Kiln Lane', 'Larch Road', 'Miller Street', 'North Road', 'Pine Street', 'Quarry Lane',
  'River Road', 'School Lane', 'Tanner Street', 'Vine Street', 'Water Lane', 'Wharf Road', 'York Street', 'Spring Lane', 'Fern Road'];

interface GNode { id: number; x: number; y: number; arms: Map<Dir, Piece> }
interface Piece { a: GNode; b: GNode; seg: number; main: boolean }
interface Chain { id: number; a: GNode; b: GNode; pts: P[]; main: boolean; pieces: Piece[] }
interface Row {
  id: string; chain: Chain; side: 'L' | 'R'; from: GNode; to: GNode;
  /** Centre line and building line in tiles, from -> to. */
  centre: P[]; front: P[]; cum: number[]; length: number;
  items: Item[];
}
/** A building on a row, in tile terms first (s along the building line), then placed. */
interface Item {
  sprite: string; kind: 'shop' | 'home' | 'landmark'; lotId: string; neighbourhood: string; yard: string; tier: string;
  rect: { x: number; y: number; w: number; h: number };
  /** Along the building line (tiles): the art's extent and the plot's. */
  s0: number; s1: number; l0: number; l1: number;
  /** Plot in front of / behind the building line (tiles): its front edge's offset and its depth. */
  e: number; depthT: number;
  foot: FootPx;
  group: number; x0: number; x1: number; lx0: number; lx1: number;
}

/** Nearest point on polyline [pts] to [p]: its distance along and its distance off (signed, + to the left of travel). */
function project(pts: P[], cum: number[], p: P): { s: number; off: number; seg: number } {
  let best = { s: 0, off: Infinity, seg: 0, d: Infinity };
  for (let i = 0; i + 1 < pts.length; i++) {
    const a = pts[i]!, b = pts[i + 1]!;
    const dx = b[0] - a[0], dy = b[1] - a[1], L = Math.hypot(dx, dy);
    const t = Math.max(0, Math.min(L, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L));
    const qx = a[0] + dx / L * t, qy = a[1] + dy / L * t;
    const d = Math.hypot(p[0] - qx, p[1] - qy);
    // + to the left of travel: left of (dx, dy) with z south is (dy, -dx)
    const off = ((p[0] - qx) * dy - (p[1] - qy) * dx) / L;
    if (d < best.d - 1e-9) best = { s: cum[i]! + t, off, seg: i, d };
  }
  return best;
}
const cumOf = (pts: P[]): number[] => { const c = [0]; for (let i = 1; i < pts.length; i++) c.push(c[i - 1]! + Math.hypot(pts[i]![0] - pts[i - 1]![0], pts[i]![1] - pts[i - 1]![1])); return c; };
/** Point and frame at distance [s] along a polyline. */
function frameAt(pts: P[], cum: number[], s: number): { p: P; along: P; normal: P } {
  let i = 0; while (i + 2 < pts.length && s > cum[i + 1]!) i++;
  const a = pts[i]!, b = pts[i + 1]!, L = cum[i + 1]! - cum[i]!;
  const ux = (b[0] - a[0]) / L, uy = (b[1] - a[1]) / L, t = s - cum[i]!;
  return { p: [a[0] + ux * t, a[1] + uy * t], along: [ux, uy], normal: [uy, -ux] };
}

export function townGraph(seed: string, year: number): TownGraph {
  const w: World = worldAt(runTimeline({ seed, endYear: year }), year);
  const layout = layoutTown(w);
  const plan = layout.plan;
  const conflicts: string[] = [];

  // ---------------------------------------------------------------------------------------------- road centre lines
  const segs = layout.roads.map((r) => (r.horizontal
    ? { h: true, c: r.y + r.h / 2, lo: r.x, hi: r.x + r.w, main: r.main }
    : { h: false, c: r.x + r.w / 2, lo: r.y, hi: r.y + r.h, main: r.main }));

  // ---------------------------------------------------------------------------------------------- nodes
  const nodes: GNode[] = [];
  const nodeAt = (x: number, y: number, tol: number): GNode => {
    let n = nodes.find((q) => Math.abs(q.x - x) <= tol && Math.abs(q.y - y) <= tol);
    if (!n) nodes.push(n = { id: nodes.length, x, y, arms: new Map() });
    return n;
  };
  segs.forEach((hs) => {
    if (!hs.h) return;
    segs.forEach((vs) => {
      if (vs.h) return;
      const x = vs.c, y = hs.c;
      if (x >= hs.lo - TOL && x <= hs.hi + TOL && y >= vs.lo - TOL && y <= vs.hi + TOL) nodeAt(x, y, 1);
    });
  });
  // pieces: consecutive nodes along each centre line (with a dead end where the line runs on past its last node)
  const pieces: Piece[] = [];
  const seenPair = new Set<string>();
  segs.forEach((s, i) => {
    const pos = (n: GNode): number => (s.h ? n.x : n.y);
    const on = nodes.filter((n) => Math.abs((s.h ? n.y : n.x) - s.c) <= 0.5 && pos(n) >= s.lo - TOL && pos(n) <= s.hi + TOL).sort((a, b) => pos(a) - pos(b));
    const at = (v: number): [number, number] => (s.h ? [v, s.c] : [s.c, v]);
    if (!on.length || pos(on[0]!) - s.lo > TOL) on.unshift(nodeAt(...at(s.lo), TOL));
    if (s.hi - pos(on[on.length - 1]!) > TOL) on.push(nodeAt(...at(s.hi), TOL));
    for (let j = 0; j + 1 < on.length; j++) {
      const a = on[j]!, b = on[j + 1]!;
      if (a === b) continue;
      const key = a.id < b.id ? `${a.id}-${b.id}` : `${b.id}-${a.id}`;
      if (seenPair.has(key)) continue;
      seenPair.add(key);
      pieces.push({ a, b, seg: i, main: s.main });
    }
  });
  for (const p of pieces) {
    const d = dirOf([p.a.x, p.a.y], [p.b.x, p.b.y]);
    if (p.a.arms.has(d) || p.b.arms.has(OPP[d])) conflicts.push(`graph: two roads leave node ${p.a.x},${p.a.y} heading ${d}`);
    p.a.arms.set(d, p); p.b.arms.set(OPP[d], p);
  }
  type Kind = 'cross' | 'tee' | 'bend' | 'end' | 'through';
  const kindOf = (n: GNode): Kind => {
    const k = n.arms.size;
    if (k >= 4) return 'cross';
    if (k === 3) return 'tee';
    if (k === 1) return 'end';
    const [d1, d2] = [...n.arms.keys()];
    return OPP[d1!] === d2 ? 'through' : 'bend';
  };
  const decision = (n: GNode): boolean => kindOf(n) !== 'bend' && kindOf(n) !== 'through';

  // ---------------------------------------------------------------------------------------------- roads (chains)
  const chains: Chain[] = [];
  const used = new Set<Piece>();
  const other = (p: Piece, n: GNode): GNode => (p.a === n ? p.b : p.a);
  for (const start of nodes) {
    if (!decision(start)) continue;
    for (const d of ['E', 'S', 'W', 'N'] as Dir[]) {
      const first = start.arms.get(d);
      if (!first || used.has(first)) continue;
      const pts: P[] = [[start.x, start.y]];
      const ps: Piece[] = [];
      let cur = start, p = first;
      for (;;) {
        used.add(p); ps.push(p);
        const next = other(p, cur);
        if (kindOf(next) !== 'through') pts.push([next.x, next.y]);
        if (decision(next) || next === start) { cur = next; break; }
        const np = [...next.arms.values()].find((q) => q !== p)!;
        cur = next; p = np;
      }
      chains.push({ id: chains.length, a: start, b: cur, pts, main: ps.some((q) => q.main), pieces: ps });
    }
  }
  if (used.size !== pieces.length) conflicts.push(`graph: ${pieces.length - used.size} road pieces in a loop of bends, left out`);
  const chainOfPiece = new Map<Piece, Chain>();
  for (const c of chains) for (const p of c.pieces) chainOfPiece.set(p, c);

  // street lines: chains carried straight on through a crossing share a name; the whole main street is one
  const parent = chains.map((_, i) => i);
  const find = (i: number): number => (parent[i] === i ? i : (parent[i] = find(parent[i]!)));
  const join = (a: number, b: number): void => { parent[find(a)] = find(b); };
  for (const n of nodes) {
    if (!decision(n)) continue;
    for (const [d1, d2] of [['E', 'W'], ['N', 'S']] as [Dir, Dir][]) {
      const p1 = n.arms.get(d1), p2 = n.arms.get(d2);
      if (p1 && p2) join(chainOfPiece.get(p1)!.id, chainOfPiece.get(p2)!.id);
    }
  }
  const mains = chains.filter((c) => c.main);
  for (const c of mains) join(c.id, mains[0]!.id);

  // ---------------------------------------------------------------------------------------------- rows
  const rows: Row[] = [];
  const rowsOf = new Map<Chain, { L: Row; R: Row }>();
  const offset = (centre: P[]): P[] => centre.map((p, i) => {
    // mitred: each point moves out by the normals of the pieces either side of it (one normal at the ends)
    const dirs: Dir[] = [];
    if (i > 0) dirs.push(dirOf(centre[i - 1]!, p));
    if (i + 1 < centre.length) dirs.push(dirOf(p, centre[i + 1]!));
    const ns = [...new Set(dirs.map((d) => LEFT[d]))];
    let x = p[0], y = p[1];
    for (const nd of ns) { x += DV[nd][0] * FRONT_TILES; y += DV[nd][1] * FRONT_TILES; }
    return [x, y];
  });
  for (const c of chains) {
    const mk = (side: 'L' | 'R'): Row => {
      const centre = side === 'L' ? c.pts : [...c.pts].reverse();
      const front = offset(centre);
      const cum = cumOf(front);
      return { id: `${c.id}${side}`, chain: c, side, from: side === 'L' ? c.a : c.b, to: side === 'L' ? c.b : c.a, centre, front, cum, length: cum[cum.length - 1]!, items: [] };
    };
    const pair = { L: mk('L'), R: mk('R') };
    rows.push(pair.L, pair.R);
    rowsOf.set(c, pair);
  }

  // ---------------------------------------------------------------------------------------------- buildings onto rows
  // naming: the v1 two streets' buildings keep their v1 names (b0.. in v1's order), then every other lot in layout order,
  // then the landmarks (the same names tools/town-whole.ts gives the dialogue and interiors)
  const v1 = townStreets(seed, year, { world: { w, layout } });
  const buildings = makeBuildings(w, layout, year);
  for (const s of [...v1.lotOf.keys()].sort((a, b) => Number(a.slice(1)) - Number(b.slice(1)))) {
    const got = buildings.entry(v1.lotOf.get(s)!);
    if (got !== s) throw new Error(`town graph: ${s} renamed ${got}`);
  }
  const lots = townLots(layout);
  for (const l of lots) buildings.entry(l);
  const nbOfPoint = (x: number, y: number): string => {
    // a landmark's neighbourhood: its nearest lot's
    let best = lots[0]!, bd = Infinity;
    for (const l of lots) { const d = Math.hypot(l.x + l.w / 2 - x, l.y + l.h / 2 - y); if (d < bd) { bd = d; best = l; } }
    return best.neighborhood;
  };
  const landmarkSprite = new Map<LandmarkPlot, string>();
  for (const l of layout.landmarks) landmarkSprite.set(l, buildings.landmark(l, neighborhoodOf(plan, nbOfPoint(l.x + l.w / 2, l.y + l.h / 2) as Lot['neighborhood']).yard));
  const entryOf = new Map((buildings.entries as { sprite: string; parts: { key: string; w: number; h: number }[]; yard?: { front: number } }[]).map((e) => [e.sprite, e]));

  /** The road a plot fronts: the one below it first (the art faces south), then above, then beside; main roads first. */
  const frontOf = (r: { x: number; y: number; w: number; h: number }): { seg: number; dir: Dir; gap: number } | undefined => {
    const c: { seg: number; dir: Dir; gap: number; score: number }[] = [];
    segs.forEach((s, i) => {
      const road = layout.roads[i]!;
      if (s.h) {
        const ox = Math.min(r.x + r.w, road.x + road.w) - Math.max(r.x, road.x);
        if (ox <= 0) return;
        const below = road.y - (r.y + r.h), above = r.y - (road.y + road.h);
        if (below >= 0 && below <= 2) c.push({ seg: i, dir: 'S', gap: below, score: 300 + ox });
        if (above >= 0 && above <= 2) c.push({ seg: i, dir: 'N', gap: above, score: 200 + ox });
      } else {
        const oy = Math.min(r.y + r.h, road.y + road.h) - Math.max(r.y, road.y);
        if (oy <= 0) return;
        const east = road.x - (r.x + r.w), west = r.x - (road.x + road.w);
        if (east >= 0 && east <= 2) c.push({ seg: i, dir: 'E', gap: east, score: 100 + oy });
        if (west >= 0 && west <= 2) c.push({ seg: i, dir: 'W', gap: west, score: 100 + oy });
      }
    });
    for (const q of c) if (segs[q.seg]!.main) q.score += 50;
    return c.sort((a, b) => b.score - a.score || a.seg - b.seg)[0];
  };
  /** The row a plot facing road [seg] in direction [dir] stands on, and its place along it. */
  const place = (r: { x: number; y: number; w: number; h: number }, f: { seg: number; dir: Dir }): { row: Row; s: number; half: number; e: number; depthT: number } => {
    const cx = r.x + r.w / 2, cy = r.y + r.h / 2;
    const s = segs[f.seg]!;
    // the piece of this road the plot's centre is beside
    const along = s.h ? cx : cy;
    const ps = pieces.filter((p) => p.seg === f.seg);
    const pc = ps.find((p) => { const a = s.h ? p.a.x : p.a.y, b = s.h ? p.b.x : p.b.y; return along >= Math.min(a, b) - 1e-9 && along <= Math.max(a, b) + 1e-9; })
      ?? ps.sort((p, q) => Math.min(Math.abs((s.h ? p.a.x : p.a.y) - along), Math.abs((s.h ? p.b.x : p.b.y) - along)) - Math.min(Math.abs((s.h ? q.a.x : q.a.y) - along), Math.abs((s.h ? q.b.x : q.b.y) - along)))[0]!;
    const chain = chainOfPiece.get(pc)!;
    const pair = rowsOf.get(chain)!;
    // the building side is the plot's side of the road: the row whose normal points at it
    const pick = [pair.L, pair.R].find((row) => {
      const pr = project(row.centre, cumOf(row.centre), [cx, cy]);
      return pr.off > 0;
    }) ?? pair.L;
    const pf = project(pick.front, pick.cum, [cx, cy]);
    const half = (f.dir === 'S' || f.dir === 'N') ? r.w / 2 : r.h / 2;
    const depthT = (f.dir === 'S' || f.dir === 'N') ? r.h : r.w;
    // the plot's front edge: its distance from the road's centre, less the 2 tiles of road and pavement
    const edge = f.dir === 'S' ? (s.c - (r.y + r.h)) : f.dir === 'N' ? (r.y - s.c) : f.dir === 'E' ? (s.c - (r.x + r.w)) : (r.x - s.c);
    return { row: pick, s: pf.s, half, e: edge - FRONT_TILES, depthT };
  };

  const addItem = (r: { x: number; y: number; w: number; h: number }, sprite: string, kind: Item['kind'], lotId: string, nb: string, tier: string): void => {
    const f = frontOf(r);
    if (!f) { conflicts.push(`${sprite} (${lotId}) fronts no road`); return; }
    const pl = place(r, f);
    const e = entryOf.get(sprite)!;
    const artW = Math.max(...e.parts.map((p) => p.w));
    const foot = footprintPx(e.parts[0]!.key, artW, e.yard?.front ?? 2, e.parts[0]!.h);
    const wT = foot.width / 32;
    pl.row.items.push({
      sprite, kind, lotId, neighbourhood: nb, yard: neighborhoodOf(plan, nb as Lot['neighborhood']).yard, tier, rect: r,
      s0: pl.s - wT / 2, s1: pl.s + wT / 2, l0: pl.s - pl.half, l1: pl.s + pl.half, e: pl.e, depthT: pl.depthT, foot,
      group: 0, x0: 0, x1: 0, lx0: 0, lx1: 0,
    });
  };
  for (const l of lots) addItem(l, buildings.entry(l), l.kind === 'shop' ? 'shop' : 'home', l.slot, l.neighborhood, l.tier);
  for (const l of layout.landmarks) addItem(l, landmarkSprite.get(l)!, 'landmark', `landmark:${l.id}`, nbOfPoint(l.x + l.w / 2, l.y + l.h / 2), l.id);

  // ---------------------------------------------------------------------------------------------- row ends
  /** The row of chain [c] that leaves node [n] heading [d] (it starts there), or that arrives at [n] heading [d] (ends there). */
  const rowLeaving = (n: GNode, d: Dir): Row | undefined => {
    const p = n.arms.get(d); if (!p) return undefined;
    const pair = rowsOf.get(chainOfPiece.get(p)!)!;
    return [pair.L, pair.R].find((r) => r.from === n && dirOf(r.centre[0]!, r.centre[1]!) === d);
  };
  const rowArriving = (n: GNode, d: Dir): Row | undefined => {
    const p = n.arms.get(OPP[d]); if (!p) return undefined;
    const pair = rowsOf.get(chainOfPiece.get(p)!)!;
    return [pair.L, pair.R].find((r) => r.to === n && dirOf(r.centre[r.centre.length - 2]!, r.centre[r.centre.length - 1]!) === d);
  };
  const endsOf = (row: Row) => {
    const other = rowsOf.get(row.chain)![row.side === 'L' ? 'R' : 'L'];
    const dS = dirOf(row.centre[0]!, row.centre[1]!);
    const dE = dirOf(row.centre[row.centre.length - 2]!, row.centre[row.centre.length - 1]!);
    const nS = LEFT[dS], nE = LEFT[dE];
    // a mouth: a road on the building side, or a road across the row's way on (the stem of a T), unless a dead end
    const mouthAt = (n: GNode, ahead: Dir, side: Dir): boolean => kindOf(n) !== 'end' && (n.arms.has(side) || !n.arms.has(ahead));
    const start: EndJson = { mouth: mouthAt(row.from, OPP[dS], nS) };
    const sS = rowArriving(row.from, dS); if (sS && sS !== row) start.straight = sS.id;
    const tS = rowArriving(row.from, OPP[nS]); if (tS) start.turnIn = tS.id;
    start.cross = other.id;
    const end: EndJson = { mouth: mouthAt(row.to, dE, nE) };
    const sE = rowLeaving(row.to, dE); if (sE && sE !== row) end.straight = sE.id;
    const tE = rowLeaving(row.to, nE); if (tE) end.turnIn = tE.id;
    end.cross = other.id;
    return { start, end };
  };
  const ends = new Map(rows.map((r) => [r, endsOf(r)]));

  // ---------------------------------------------------------------------------------------------- rigid groups
  const MARGIN = MOUTH / 2;
  for (const row of rows) {
    const L = row.length * SCALE;
    const items = row.items.sort((a, b) => a.s0 - b.s0 || a.sprite.localeCompare(b.sprite, 'en', { numeric: true }));
    const groups: Item[][] = [];
    for (const it of items) {
      const g = groups[groups.length - 1];
      if (g && it.s0 - Math.max(...g.map((q) => q.s1)) <= GROUP_GAP + 1e-9) g.push(it); else groups.push([it]);
    }
    const placed = groups.map((g, gi) => {
      const lo = Math.min(...g.map((q) => q.s0)), hi = Math.max(...g.map((q) => q.s1));
      const c = (lo + hi) / 2;
      return { g, gi, X: c * SCALE, c, lo: c * SCALE + (lo - c) * UNIT_PER_TILE, hi: c * SCALE + (hi - c) * UNIT_PER_TILE };
    });
    const { start, end } = ends.get(row)!;
    const minX = start.mouth ? MARGIN : -Infinity, maxX = end.mouth ? L - MARGIN : Infinity;
    const shift = (p: typeof placed[number], dx: number, why: string): void => {
      if (Math.abs(dx) < 1e-6) return;
      p.X += dx; p.lo += dx; p.hi += dx;
      conflicts.push(`row ${row.id}: group ${p.g.map((q) => q.sprite).join('+')} nudged ${r3(dx)} units (${why})`);
    };
    // clear of the mouths, then of each other (forward and back), keeping at least a tile between groups
    for (const p of placed) { if (p.lo < minX) shift(p, minX - p.lo, 'start mouth'); if (p.hi > maxX) shift(p, maxX - p.hi, 'end mouth'); }
    for (let i = 1; i < placed.length; i++) { const a = placed[i - 1]!, b = placed[i]!; if (b.lo < a.hi + UNIT_PER_TILE - 1e-6) shift(b, a.hi + UNIT_PER_TILE - b.lo, 'neighbour'); }
    for (let i = placed.length - 1; i >= 0; i--) {
      const p = placed[i]!;
      if (p.hi > maxX + 1e-6) shift(p, maxX - p.hi, 'end mouth');
      const a = placed[i - 1];
      if (a && a.hi > p.lo - UNIT_PER_TILE + 1e-6) shift(a, p.lo - UNIT_PER_TILE - a.hi, 'neighbour');
    }
    for (const p of placed) {
      if (p.lo < minX - 1e-6 || p.hi > maxX + 1e-6) conflicts.push(`row ${row.id}: group ${p.g.map((q) => q.sprite).join('+')} does not fit between its mouths (${r3(p.lo)}..${r3(p.hi)} of ${r3(L)})`);
      for (const it of p.g) {
        it.group = p.gi;
        it.x0 = p.X + (it.s0 - p.c) * UNIT_PER_TILE; it.x1 = p.X + (it.s1 - p.c) * UNIT_PER_TILE;
        it.lx0 = p.X + (it.l0 - p.c) * UNIT_PER_TILE; it.lx1 = p.X + (it.l1 - p.c) * UNIT_PER_TILE;
      }
    }
  }

  // ---------------------------------------------------------------------------------------------- footprints and plots
  const frontW = (row: Row): P[] => row.front.map(w3);
  const cumW = new Map(rows.map((r) => [r, cumOf(frontW(r))]));
  /** World rectangle of row-frame box x0..x1 by depth d0..d1 (the row is straight there: axis-aligned). */
  const boxOf = (row: Row, x0: number, x1: number, d0: number, d1: number): [number, number, number, number] => {
    const pts = frontW(row), cum = cumW.get(row)!;
    const f = frameAt(pts, cum, Math.max(0, Math.min(cum[cum.length - 1]!, (x0 + x1) / 2)));
    const s = (x0 + x1) / 2;
    const corner = (x: number, d: number): P => [f.p[0] + f.along[0] * (x - s) + f.normal[0] * d, f.p[1] + f.along[1] * (x - s) + f.normal[1] * d];
    const cs = [corner(x0, d0), corner(x1, d0), corner(x0, d1), corner(x1, d1)];
    return [r3(Math.min(...cs.map((c) => c[0]))), r3(Math.min(...cs.map((c) => c[1]))), r3(Math.max(...cs.map((c) => c[0]))), r3(Math.max(...cs.map((c) => c[1])))];
  };
  const footprints: TownGraph['footprints'] = [];
  const lotsJson: LotJson[] = [];
  const rowJson = new Map<Row, RowJson>();
  for (const row of rows) {
    const out: BuildingJson[] = [];
    for (const it of row.items) {
      const plotFront = it.e * SCALE, plotBack = (it.e + it.depthT) * SCALE;
      let setback = plotFront + it.foot.setback * PX;
      const depth = it.foot.depth * PX;
      if (setback + depth > plotBack + 1e-6) {
        const nudged = Math.max(plotFront, plotBack - depth);
        conflicts.push(`${it.sprite} (${it.lotId}, ${row.id}): footprint ${r3(setback + depth - plotFront)} deep in a plot ${r3(plotBack - plotFront)} deep; setback ${r3(setback)} -> ${r3(nudged)}`);
        setback = nudged;
      }
      out.push({ sprite: it.sprite, x0: r3(it.x0), x1: r3(it.x1), setback: r3(setback), depth: r3(depth), group: it.group, neighbourhood: it.neighbourhood, kind: it.kind, lot: it.lotId });
      footprints.push({ sprite: it.sprite, row: row.id, rect: boxOf(row, it.x0, it.x1, plotFront, setback + depth) });
      if (it.kind !== 'landmark') lotsJson.push({ id: it.lotId, sprite: it.sprite, row: row.id, rect: boxOf(row, it.lx0, it.lx1, plotFront, plotBack), kind: it.tier, neighbourhood: it.neighbourhood, yard: it.yard });
    }
    const pts = frontW(row);
    const f0 = frameAt(pts, cumW.get(row)!, 0);
    rowJson.set(row, {
      id: row.id, road: row.chain.id, side: row.side, from: row.from.id, to: row.to.id,
      origin: [r3(f0.p[0]), r3(f0.p[1])], along: f0.along, normal: f0.normal, length: r3(row.length * SCALE), pts,
      buildings: out, furniture: [], ends: ends.get(row)!,
    });
  }
  // the yard-to-back plots, and each building's own footprint, must not overlap one another or any road
  const hit = (a: number[], b: number[], pad = 0.01): boolean => a[0]! < b[2]! - pad && b[0]! < a[2]! - pad && a[1]! < b[3]! - pad && b[1]! < a[3]! - pad;
  for (let i = 0; i < footprints.length; i++) for (let j = i + 1; j < footprints.length; j++) {
    if (hit(footprints[i]!.rect, footprints[j]!.rect)) conflicts.push(`overlap: ${footprints[i]!.sprite} (${footprints[i]!.row}) and ${footprints[j]!.sprite} (${footprints[j]!.row})`);
  }
  const corridors = chains.flatMap((c) => c.pts.slice(1).map((b, i) => {
    const a = c.pts[i]!;
    return [Math.min(a[0], b[0]) * SCALE - MARGIN, Math.min(a[1], b[1]) * SCALE - MARGIN, Math.max(a[0], b[0]) * SCALE + MARGIN, Math.max(a[1], b[1]) * SCALE + MARGIN];
  }));
  for (const f of footprints) for (const c of corridors) if (hit(f.rect, c, 0.5)) { conflicts.push(`road: ${f.sprite} (${f.row}) stands in a street`); break; }

  // ---------------------------------------------------------------------------------------------- the civic avenue and spawn
  const isMainNode = (n: GNode): boolean => [...n.arms.values()].some((p) => p.main);
  const civicChain = chains.filter((c) => !c.main && (isMainNode(c.a) || isMainNode(c.b)))
    .map((c) => ({ c, n: [rowsOf.get(c)!.L, rowsOf.get(c)!.R].reduce((s, r) => s + r.items.filter((i) => i.kind === 'landmark').length, 0) }))
    .sort((a, b) => b.n - a.n || a.c.id - b.c.id)[0]?.c ?? chains[0]!;
  const civicNode = isMainNode(civicChain.a) ? civicChain.a : civicChain.b;
  const spawnRow = rowsOf.get(civicChain)!.L.from === civicNode ? rowsOf.get(civicChain)!.L : rowsOf.get(civicChain)!.R;
  // by the plaza if it is on this row, else by the first building
  const pz = plan.plaza;
  const pzS = project(spawnRow.front, spawnRow.cum, [pz.x + pz.w / 2, pz.y + pz.h / 2]);
  const spawnX = pzS.off > 0 && pzS.off < pz.w + FRONT_TILES + 2 ? pzS.s * SCALE : (spawnRow.items[0]?.x0 ?? MARGIN + 40);

  // ---------------------------------------------------------------------------------------------- names
  const lineName = new Map<number, string>();
  const takenNames = new Set<string>();
  const mainLine = mains.length ? find(mains[0]!.id) : -1;
  lineName.set(mainLine, 'Main Street'); takenNames.add('Main Street');
  lineName.set(find(civicChain.id), 'Civic Avenue'); takenNames.add('Civic Avenue');
  for (const c of chains) {
    const l = find(c.id);
    if (lineName.has(l)) continue;
    let k = hash(`${seed}:street:${c.id}`) % STREET_NAMES.length;
    for (let t = 0; t < STREET_NAMES.length && takenNames.has(STREET_NAMES[k]!); t++) k = (k + 1) % STREET_NAMES.length;
    const name = takenNames.has(STREET_NAMES[k]!) ? `Street ${c.id}` : STREET_NAMES[k]!;
    lineName.set(l, name); takenNames.add(name);
  }

  // ---------------------------------------------------------------------------------------------- props
  const tileOf = (p: Prop): P => [p.x / 32, p.y / 32 - 0.5];
  const inRect = (q: P, r: { x: number; y: number; w: number; h: number }, pad = 0): boolean => q[0] >= r.x - pad && q[0] <= r.x + r.w + pad && q[1] >= r.y - pad && q[1] <= r.y + r.h + pad;
  const builtPlanRoads = new Set(layout.roads.map((r) => plan.roads.indexOf(r)));
  const plazaProps: TownMapJson['plaza']['props'] = [];
  const props: TownMapJson['props'] = [];
  let dropped = 0;
  const plots = [...lots, ...layout.landmarks];
  for (const p of [...plan.props, ...layout.props]) {
    const q = tileOf(p);
    const wp = w3(q);
    if (p.road !== undefined) {
      if (!builtPlanRoads.has(p.road)) continue;
      if (p.kind !== 'lamp' && p.kind !== 'bench') continue;
      // street furniture: onto the nearest row on its side of its road
      const road = plan.roads[p.road]!;
      const seg = layout.roads.indexOf(road);
      const cands = rows.filter((r) => r.chain.pieces.some((pc) => pc.seg === seg));
      let best: { row: Row; s: number; d: number } | undefined;
      for (const r of cands) {
        const pr = project(r.centre, cumOf(r.centre), q);
        if (pr.off <= 0) continue;
        const d = Math.abs(pr.off);
        if (!best || d < best.d) best = { row: r, s: project(r.front, r.cum, q).s, d };
      }
      if (best) rowJson.get(best.row)!.furniture.push({ kind: p.kind, x: r3(best.s * SCALE), look: r3(p.look) });
      continue;
    }
    if (inRect(q, pz)) { plazaProps.push({ kind: p.kind, x: wp[0], z: wp[1], look: r3(p.look) }); continue; }
    // yards belong to their buildings (the 3D models carry their own); anything standing in a footprint is dropped too
    if (plots.some((l) => inRect(q, l, -0.01)) || footprints.some((f) => wp[0] > f.rect[0] && wp[0] < f.rect[2] && wp[1] > f.rect[1] && wp[1] < f.rect[3])) { dropped++; continue; }
    props.push({ kind: p.kind, x: wp[0], z: wp[1], look: r3(p.look) });
  }
  for (const r of rowJson.values()) r.furniture.sort((a, b) => a.x - b.x);

  // ---------------------------------------------------------------------------------------------- water
  let water: TownMapJson['water'] = null;
  if (plan.water) {
    const W = plan.water.width, H = plan.water.height, m = plan.water.mask;
    // runs of water per tile row, merged down while the run below is the same
    type Run = { x0: number; x1: number; y0: number; y1: number };
    const open = new Map<string, Run>(); const done: Run[] = [];
    for (let y = 0; y < H; y++) {
      const here = new Map<string, Run>();
      for (let x = 0; x < W;) {
        if (!m[y * W + x]) { x++; continue; }
        let e = x; while (e < W && m[y * W + e]) e++;
        const k = `${x}:${e}`;
        const r = open.get(k);
        if (r) { r.y1 = y + 1; here.set(k, r); open.delete(k); } else here.set(k, { x0: x, x1: e, y0: y, y1: y + 1 });
        x = e;
      }
      for (const r of open.values()) done.push(r);
      open.clear(); for (const [k, r] of here) open.set(k, r);
    }
    for (const r of open.values()) done.push(r);
    done.sort((a, b) => a.y0 - b.y0 || a.x0 - b.x0);
    water = { kind: plan.water.kind, polys: done.map((r) => [w3([r.x0, r.y0]), w3([r.x1, r.y0]), w3([r.x1, r.y1]), w3([r.x0, r.y1])]) };
  }
  for (const f of footprints) {
    if (water?.polys.some((p) => hit(f.rect, [p[0]![0], p[0]![1], p[2]![0], p[2]![1]], 0.5))) conflicts.push(`water: ${f.sprite} (${f.row}) stands in the ${water.kind}`);
  }
  const rectW = (r: { x: number; y: number; w: number; h: number }): [number, number, number, number] => [...w3([r.x, r.y]), ...w3([r.x + r.w, r.y + r.h])] as [number, number, number, number];

  // ---------------------------------------------------------------------------------------------- assemble
  // dress: no front repeats along a row (after the v1 streets', as v1 does it)
  const v1Spawn = townStreets(seed, year, { world: { w, layout }, junctionX: civicNode.x - 1, buildings });
  for (const row of rows) buildings.vary(row.items.map((i) => i.sprite));
  const rowIdOfSprite = new Map<string, string>();
  for (const r of rowJson.values()) for (const b of r.buildings) rowIdOfSprite.set(b.sprite, r.id);
  const kept = new Set(nodes.filter((n) => kindOf(n) !== 'through'));
  const ids = new Map([...kept].map((n, i) => [n, i]));
  const graph: TownMapJson = {
    seed, year, k: Math.round(K * 1e4) / 1e4, unitPerTile: r3(UNIT_PER_TILE), scale: r3(SCALE), frontTiles: FRONT_TILES,
    spawn: { row: spawnRow.id, x: r3(spawnX), lane: SPAWN_LANE },
    civic: { road: civicChain.id, name: lineName.get(find(civicChain.id))! },
    neighbourhoods: plan.neighborhoods.map((n) => ({ id: n.id, name: n.name, palette: n.palette, yard: n.yard, shopColour: n.shopColour })),
    nodes: [...kept].map((n) => ({
      id: ids.get(n)!, x: r3(n.x * SCALE), z: r3(n.y * SCALE), kind: kindOf(n) as NodeJson['kind'],
      arms: (['E', 'S', 'W', 'N'] as Dir[]).filter((d) => n.arms.has(d)).map((d) => ({ road: chainOfPiece.get(n.arms.get(d)!)!.id, heading: DV[d], mouthHalf: MOUTH / 2 })),
    })),
    roads: chains.map((c) => ({ id: c.id, name: lineName.get(find(c.id))!, main: c.main, a: ids.get(c.a)!, b: ids.get(c.b)!, pts: c.pts.map(w3), width: MOUTH })),
    rows: rows.map((r) => ({ ...rowJson.get(r)!, from: ids.get(r.from)!, to: ids.get(r.to)! })),
    landmarks: layout.landmarks.map((l) => ({ id: `landmark:${l.id}`, kind: l.id, name: l.name, sprite: landmarkSprite.get(l)!, art: landmarkArtKey(l), row: rowIdOfSprite.get(landmarkSprite.get(l)!) ?? '', rect: rectW(l), ...cinemaLandmark(l), ...storeLandmark(l) })),
    ...cinemaBlock(seed, year, layout),
    ...storesBlock(seed, year, layout),
    plaza: { rect: rectW(pz), props: plazaProps },
    water,
    bridges: (plan.water?.bridges ?? []).map((b) => ({ rect: rectW(b), horizontal: b.horizontal })),
    props,
    lots: lotsJson,
  };
  const v1json = v1Spawn.json as Record<string, unknown>;
  const json = {
    town: seed, year, type: archetypeOf(seed).label,
    streets: v1json.streets, junction: v1json.junction,
    shops: buildings.entries,
    version: 2 as const,
    graph,
  };
  return { seed, year, w, layout, json, graph, buildings, conflicts, footprints, propsDropped: dropped };
}
