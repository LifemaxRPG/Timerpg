import { rand } from '../sim/rng';
import type { Rect, Road } from './kinds';
import type { LandmarkPlot } from './landmarks';
import type { TownPlan } from './plan';
import { hitsWater } from './water';
import { recipeOf } from './recipe';

// THE FARMS round the town (kotlin-street/docs/TOWN.md "Farms"): farmsteads and their fields in the open country along the
// roads out of town, a pure function of the seed's plan (rand(seed, 'farm', ...)).
//
//  - COUNTRY ROADS run on out of town from the ends of its streets that point outwards (the main street's two ends, the
//    furthest street north and south), straight out across the woods to the edge of the farmland, RING tiles past the map.
//    Where the town street they continue is not built yet in a year, the country road runs on along its line to the first
//    street that is (the town grew along its old roads): `farmsIn` works that out a year at a time.
//  - FARMSTEADS stand along the country roads (each side, every 40 to 60 tiles), outside everything the plan will ever
//    build (roads, plots, landmarks, plaza, water): a farmhouse facing the road with its front lawn, a barn behind across the
//    yard with its silos, a machine shed, a corral, the yard's props (tractor, hay, troughs, a well, crates, barrels), a
//    windbreak of trees along the back, and a gravel lane from the road into the yard. Each farm is a family (a rural
//    household for the people model to take up later: `household`), with a roadside stand in summer if it grows produce.
//  - FIELDS lie round each farmstead, fenced with a gate towards the yard or the road: corn and wheat in rotation, hay, or a
//    truck farm's vegetables (crops.json's Midwest crops). A field may lie over the plan's outer plots: the land is farmed
//    until the town reaches it, and the year anything is built on it (a house, a street, a store) the field is gone (sold).
//    So a small town is ringed by farms and a growing one eats into them, decade by decade.
//
// Units are the plan's tiles (x east, y south); the world is the map (0..width, 0..height) and RING tiles round it.

/** Tiles of farmland round the map (the world-edge woods stand past it). */
export const RING = 100;
/** The section road round the town: tiles past the map's edge (farms on both sides of it). */
export const PERIM = 36;
/** A farmstead's yard: tiles along its road and back from it. */
export const YARD = { w: 30, d: 26 };
/** Gap between the road's edge and the yard (the verge and the lane's mouth). */
const VERGE = 3;
/** Country roads are as wide as the town's (2 tiles). */
const ROAD_W = 2;

export type Side = 'N' | 'S' | 'E' | 'W';
const DIR: Record<Side, [number, number]> = { N: [0, -1], S: [0, 1], E: [1, 0], W: [-1, 0] };

/** A road out of town: its centre line (tile coordinates) from the town street's end outwards. */
export interface CountryRoad {
  id: string;
  /** A road out from a street's end ('spoke') or the section road round the town ('ring'). */
  kind: 'spoke' | 'ring';
  /** The way it runs (out of town, for a spoke). */
  side: Side;
  /** Index into plan.roads of the street it continues (-1: the section road). */
  root: number;
  /** The centre line: a spoke's [a] at the street's end, [b] at the far end (RING past the map). */
  a: [number, number];
  b: [number, number];
}

export type FarmModel = 'fm_house1' | 'fm_house2' | 'fm_barn_red' | 'fm_barn_grey' | 'fm_barn_yellow' | 'fm_barn_teal' | 'fm_silo1' | 'fm_silo2' |
  'fm_shed' | 'fm_corral' | 'fm_hay' | 'fm_troughs' | 'fm_crates' | 'fm_well' | 'fm_wellstone' | 'fm_tractor' | 'fm_roundbales' | 'fm_barrels' |
  'fm_stall_blue' | 'fm_stall_pink' | 'fm_stall_green' | 'fm_stall_yellow' | 'fm_bits';

/** Footprints (tiles along x the model's front, d back) the farmstead's layout keeps apart (models.json, rounded up). */
export const FOOT: Partial<Record<FarmModel, [number, number]>> = {
  fm_house1: [5, 4], fm_house2: [6, 4], fm_barn_red: [9, 9], fm_barn_grey: [9, 8], fm_barn_yellow: [9, 9], fm_barn_teal: [9, 8],
  fm_silo1: [5, 4], fm_silo2: [3, 3], fm_shed: [5, 4], fm_corral: [5, 4], fm_hay: [5, 2], fm_troughs: [4, 2], fm_crates: [4, 1],
  fm_well: [2, 2], fm_tractor: [3, 3], fm_roundbales: [4, 4], fm_barrels: [2, 1], fm_stall_blue: [4, 2],
};

/** One thing standing in a farmstead: a model, its front's middle (tiles) and the way its front faces. */
export interface FarmThing {
  model: FarmModel;
  /** World tiles: the middle of its front edge. */
  x: number; y: number;
  /** The way its front faces (unit, world tiles). */
  face: [number, number];
  /** Has a door a walker can use (farmhouse, barn). */
  door?: boolean;
  /** Only in the summer months (a roadside stand). */
  summer?: boolean;
}

export type FieldUse = 'grain' | 'hay' | 'truck';
export interface FarmField extends Rect {
  id: string;
  farm: string;
  use: FieldUse;
  /** The crops in rotation, one a year: the crop harvested in year Y is rotation[(Y - 1950 + phase) mod n]. */
  rotation: string[];
  phase: number;
  /** The rows run along x (true) or y. */
  rowsX: boolean;
  /** The gate: the middle of the fence's gap (tiles), and the side of the field it is on. */
  gate: { x: number; y: number; side: Side };
}

export interface Farmstead {
  id: string;
  /** The yard (world tiles). */
  rect: Rect;
  /** The way the yard's front faces (towards its road). */
  front: Side;
  road: string;
  /** The family's name, the year the farm was founded (before the game's 1950), and its rural household for the people model. */
  family: string;
  founded: number;
  household: { surname: string; rural: true; members: number };
  things: FarmThing[];
  /** The farmyard's beaten ground (between the house, the barn and the shed). */
  ground: Rect;
  /** The gravel lane from the road's edge into the yard: its centre line and width (tiles). */
  lane: { a: [number, number]; b: [number, number]; w: number };
  /** The windbreak's trees (tile points). */
  trees: [number, number][];
  /** Grows produce: a stand by the road in summer. */
  produce: boolean;
}

export interface FarmPlan {
  seed: string;
  roads: CountryRoad[];
  farms: Farmstead[];
  fields: FarmField[];
  /** The farmland's extent (the map and RING round it). */
  extent: Rect;
  /** The river past the map (tile rects), and the country roads' bridges over it. */
  water: Rect[];
  bridges: { rect: Rect; horizontal: boolean }[];
  /** A world place's roads in (src/town/recipe.ts): where each world road crosses the farmland's edge ([worldAt], plan
   * tiles along that side) and where the town's road meets it ([at]: the country road there, a spoke of the town's own
   * within ENTRY_SNAP tiles, or a road laid out to it from the section road). Empty without a recipe. */
  entries: { kind: string; road: string; side: Side; worldAt: number; at: number; spoke: string }[];
}

/** A world road crossing the farmland's edge this near (tiles) a country road the town has anyway meets that road (the
 * world road's last stretch is bent to it, as the capital's pins bend the world's roads); further off, a road is laid. */
export const ENTRY_SNAP = 40;

// ------------------------------------------------------------------------------------------------ crops
/** The crops our fields grow (crops.json `midwest`), by use. Corn and wheat are the field crops; the rest a truck farm's. */
export const GRAIN = ['corn', 'wheat'] as const;
export const PRODUCE = ['pumpkin', 'tomato', 'cabbage', 'carrot', 'onion', 'watermelon', 'zucchini', 'pepper', 'strawberry', 'cauliflower', 'turnip', 'radish'] as const;
export const HAY = 'hay';

/** The crop field [f] grows for harvest in [year]. */
export function cropIn(f: Pick<FarmField, 'rotation' | 'phase'>, year: number): string {
  const n = f.rotation.length;
  return f.rotation[(((year - 1950 + f.phase) % n) + n) % n]!;
}

const FAMILIES = ['Ostrander', 'Kettering', 'Lindgren', 'Halvorsen', 'Brubaker', 'Vandermeer', 'Schuyler', 'Mattingly', 'Pfeiffer', 'Rasmussen',
  'Stoltzfus', 'Hendrickson', 'Wollard', 'Gruber', 'Eberhart', 'Thorsby', 'McCready', 'Dahlquist', 'Kowalczyk', 'Ballantyne', 'Treadway', 'Augustin'];

// ------------------------------------------------------------------------------------------------ geometry
interface Box { x: number; y: number; w: number; h: number }
const hit = (a: Box, b: Box, pad = 0): boolean => a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;
/** A road's rectangle (tiles) from its centre line. */
export function roadRect(r: Pick<CountryRoad, 'a' | 'b'>, w = ROAD_W): Rect {
  const x0 = Math.min(r.a[0], r.b[0]), x1 = Math.max(r.a[0], r.b[0]), y0 = Math.min(r.a[1], r.b[1]), y1 = Math.max(r.a[1], r.b[1]);
  return x0 === x1 ? { x: x0 - w / 2, y: y0, w, h: y1 - y0 } : { x: x0, y: y0 - w / 2, w: x1 - x0, h: w };
}

const cache = new Map<string, FarmPlan>();

/** The farms of [plan]'s town (cached per seed). */
export function planFarms(plan: TownPlan): FarmPlan {
  const hitC = cache.get(plan.seed);
  if (hitC && hitC.extent.w === plan.width + 2 * RING && hitC.extent.h === plan.height + 2 * RING) return hitC;
  const seed = plan.seed;
  const R = (...k: (string | number)[]): number => rand(seed, 'farm', ...k);
  const extent: Rect = { x: -RING, y: -RING, w: plan.width + 2 * RING, h: plan.height + 2 * RING };
  const W = plan.width, H = plan.height;

  // the river runs on past the map (straight out from where it meets the map's edge) to the farmland's edge
  const water: Rect[] = [];
  if (plan.water) {
    const raw = plan.water.raw;
    const runs = (n: number, at: (i: number) => boolean): [number, number][] => {
      const out: [number, number][] = [];
      for (let i = 0; i < n;) { if (!at(i)) { i++; continue; } let e = i; while (e < n && at(e)) e++; out.push([i, e]); i = e; }
      return out;
    };
    for (const [x0, x1] of runs(W, (x) => !!raw[x])) water.push({ x: x0, y: extent.y, w: x1 - x0, h: -extent.y });
    for (const [x0, x1] of runs(W, (x) => !!raw[(H - 1) * W + x])) water.push({ x: x0, y: H, w: x1 - x0, h: extent.y + extent.h - H });
    for (const [y0, y1] of runs(H, (y) => !!raw[y * W])) water.push({ x: extent.x, y: y0, w: -extent.x, h: y1 - y0 });
    for (const [y0, y1] of runs(H, (y) => !!raw[y * W + W - 1])) water.push({ x: W, y: y0, w: extent.x + extent.w - W, h: y1 - y0 });
  }

  // what the town will ever have: plots, streets, landmarks, plaza (the map's water is checked on its mask)
  const town: Box[] = [...plan.roads, ...plan.candidates, ...plan.landmarks, plan.plaza];
  // (the planned town: a field may lie over its later districts' streets and plots, farmed until they are built: farmsIn
  // sells it the year anything on it is; Main Street, the square and the rail band are never farmed)
  const fixed: Box[] = plan.v2 ? [...plan.roads.filter((r) => r.main), plan.plaza, ...(plan.v2.railBand ? [plan.v2.railBand.rect] : [])] : [...plan.roads, ...plan.landmarks, plan.plaza];
  const wet = (b: Box, pad: number): boolean =>
    hitsWater(plan.water, { x: Math.floor(b.x), y: Math.floor(b.y), w: Math.ceil(b.w), h: Math.ceil(b.h) }, pad) || water.some((w) => hit(b, w, pad));
  const inside = (b: Box): boolean => b.x >= extent.x + 2 && b.y >= extent.y + 2 && b.x + b.w <= extent.x + extent.w - 2 && b.y + b.h <= extent.y + extent.h - 2;
  const outer = (side: Side, at: [number, number]): [number, number] =>
    side === 'W' ? [extent.x, at[1]] : side === 'E' ? [extent.x + extent.w, at[1]] : side === 'N' ? [at[0], extent.y] : [at[0], extent.y + extent.h];

  // ---- country roads: out from the streets' outermost ends, and the section road round the town that links them
  const roads: CountryRoad[] = [];
  const pick = (side: Side): void => {
    const horiz = side === 'E' || side === 'W';
    let best: { i: number; at: [number, number]; score: number } | undefined;
    plan.roads.forEach((r, i) => {
      if (r.horizontal !== horiz) return;
      const at: [number, number] = side === 'W' ? [r.x, r.y + r.h / 2] : side === 'E' ? [r.x + r.w, r.y + r.h / 2] : side === 'N' ? [r.x + r.w / 2, r.y] : [r.x + r.w / 2, r.y + r.h];
      const corridor = roadRect({ a: at, b: outer(side, at) }, ROAD_W + 2);
      // the line out must be clear: no other street, plot, landmark or water across it in the map
      if (plan.roads.some((o, j) => j !== i && hit(corridor, o, 0) && !touchesEnd(o, at))) return;
      if ([...plan.candidates, ...plan.landmarks, plan.plaza].some((o) => hit(corridor, o, 0))) return;
      if (hitsWater(plan.water, { x: Math.floor(corridor.x), y: Math.floor(corridor.y), w: Math.ceil(corridor.w), h: Math.ceil(corridor.h) }, 2)) return;
      // outermost first; the main street's ends win ties
      const out = side === 'W' ? -at[0] : side === 'E' ? at[0] : side === 'N' ? -at[1] : at[1];
      const score = out + (r.main ? 30 : 0) + R('road', side, i) * 4;
      if (!best || score > best.score) best = { i, at, score };
    });
    if (best) roads.push({ id: `road:${side}`, kind: 'spoke', side, root: best.i, a: best.at, b: outer(side, best.at) });
  };
  for (const s of ['W', 'E', 'N', 'S'] as Side[]) pick(s);
  {
    const x0 = -PERIM, y0 = -PERIM, x1 = W + PERIM, y1 = H + PERIM;
    roads.push({ id: 'ring:N', kind: 'ring', side: 'E', root: -1, a: [x0, y0], b: [x1, y0] });
    roads.push({ id: 'ring:E', kind: 'ring', side: 'S', root: -1, a: [x1, y0], b: [x1, y1] });
    roads.push({ id: 'ring:S', kind: 'ring', side: 'W', root: -1, a: [x1, y1], b: [x0, y1] });
    roads.push({ id: 'ring:W', kind: 'ring', side: 'N', root: -1, a: [x0, y1], b: [x0, y0] });
  }
  // a world place's roads in: each meets a country road at the farmland's edge (its own, or one laid from the section road)
  const entries: FarmPlan['entries'] = [];
  for (const [k, e] of (recipeOf(seed)?.entries ?? []).entries()) {
    if (e.kind === 'rail') continue;
    const horiz = e.side === 'N' || e.side === 'S';
    const near = roads.filter((r) => r.kind === 'spoke' && r.side === e.side).map((r) => ({ r, at: horiz ? r.b[0] : r.b[1] }))
      .sort((a, b) => Math.abs(a.at - e.at) - Math.abs(b.at - e.at))[0];
    if (near && Math.abs(near.at - e.at) <= ENTRY_SNAP) { entries.push({ kind: e.kind, road: e.road, side: e.side, worldAt: e.at, at: near.at, spoke: near.r.id }); continue; }
    // (laid out from the section road round the town, which joins every road out: on the section road's span)
    const lim = horiz ? [-PERIM + 2, W + PERIM - 2] : [-PERIM + 2, H + PERIM - 2];
    const at = Math.round(Math.max(lim[0]!, Math.min(lim[1]!, e.at)));
    const from: [number, number] = e.side === 'N' ? [at, -PERIM] : e.side === 'S' ? [at, H + PERIM] : e.side === 'W' ? [-PERIM, at] : [W + PERIM, at];
    const id = `entry:${e.side}${k}`;
    roads.push({ id, kind: 'spoke', side: e.side, root: -1, a: from, b: outer(e.side, from) });
    entries.push({ kind: e.kind, road: e.road, side: e.side, worldAt: e.at, at, spoke: id });
  }
  // bridges where a country road crosses the river past the map
  const bridges: { rect: Rect; horizontal: boolean }[] = [];
  for (const r of roads) for (const w of water) {
    const rr = roadRect(r, ROAD_W + 2);
    if (!hit(rr, w)) continue;
    const horizontal = r.a[1] === r.b[1];
    const x0 = Math.max(rr.x, w.x - 1), x1 = Math.min(rr.x + rr.w, w.x + w.w + 1), y0 = Math.max(rr.y, w.y - 1), y1 = Math.min(rr.y + rr.h, w.y + w.h + 1);
    bridges.push({ rect: { x: x0, y: y0, w: x1 - x0, h: y1 - y0 }, horizontal });
  }

  // ---- farmsteads along each road (each side, every 40-60 tiles), then their fields
  const farms: Farmstead[] = [];
  const fields: FarmField[] = [];
  const taken: Box[] = roads.map((r) => roadRect(r, ROAD_W + 4));
  // (a farmstead keeps 10 tiles off the farmland's outer edge, where the railroads come in from the world)
  const insideYard = (b: Box): boolean => b.x >= extent.x + 10 && b.y >= extent.y + 10 && b.x + b.w <= extent.x + extent.w - 10 && b.y + b.h <= extent.y + extent.h - 10;
  const freeYard = (b: Box): boolean => insideYard(b) && !town.some((t) => hit(b, t, 4)) && !taken.some((t) => hit(b, t, 1)) && !wet(b, 4);
  const freeField = (b: Box): boolean => inside(b) && !fixed.some((t) => hit(b, t, 2)) && !taken.some((t) => hit(b, t, 2)) && !wet(b, 3);

  for (const road of roads) {
    const len = Math.abs(road.b[0] - road.a[0]) + Math.abs(road.b[1] - road.a[1]);
    const dx = Math.sign(road.b[0] - road.a[0]), dy = Math.sign(road.b[1] - road.a[1]);
    let t = 6 + Math.floor(R('first', road.id) * 14);
    let k = 0;
    while (t < len - YARD.w - 4) {
      const sides: (1 | -1)[] = R('side', road.id, k) < 0.5 ? [1, -1] : [-1, 1];
      let placed = false;
      for (const s of sides) {
        // the yard's front faces the road: `away` from it
        const away: [number, number] = [-dy * s, dx * s];
        const cx = road.a[0] + dx * (t + YARD.w / 2), cy = road.a[1] + dy * (t + YARD.w / 2);
        const off = ROAD_W / 2 + VERGE;
        const right: [number, number] = [-away[1], away[0]];
        const mouth: [number, number] = [cx + away[0] * off, cy + away[1] * off];
        const o: [number, number] = [mouth[0] - right[0] * YARD.w / 2, mouth[1] - right[1] * YARD.w / 2];
        const q: [number, number] = [o[0] + right[0] * YARD.w + away[0] * YARD.d, o[1] + right[1] * YARD.w + away[1] * YARD.d];
        const rect: Rect = { x: Math.min(o[0], q[0]), y: Math.min(o[1], q[1]), w: Math.abs(q[0] - o[0]), h: Math.abs(q[1] - o[1]) };
        if (!freeYard(rect)) continue;
        const front: Side = away[0] === 1 ? 'W' : away[0] === -1 ? 'E' : away[1] === 1 ? 'N' : 'S';
        const farm = farmstead(seed, `farm:${road.id.replace('road:', '').replace('ring:', 'r')}${k}`, road, rect, front, o, right, away);
        farms.push(farm);
        taken.push(rect);
        fields.push(...layFields(seed, farm, o, right, away, rect, freeField, (b) => taken.push(b)));
        placed = true;
        break;
      }
      t += placed ? YARD.w + 16 + Math.floor(R('gap', road.id, k) * 26) : 7;
      k++;
    }
  }

  // ---- fields over the town's outer plots: farmed until the town is built out to them (the farm nearest works them)
  if (farms.length) {
    const cx = plan.center.x, cy = plan.center.y;
    const cands: { b: Box; d: number }[] = [];
    const sizes: [number, number][] = [[30, 24], [26, 20], [22, 18], [18, 14]];
    for (let y = 1; y < H - 12; y += 3) for (let x = 1; x < W - 12; x += 3) {
      for (const [w, h] of sizes) {
        const b = { x, y, w, h };
        if (x + w > W - 1 || y + h > H - 1) continue;
        if (plan.roads.some((r) => hit(b, r, 1)) || plan.landmarks.some((l) => hit(b, l, 2)) || hit(b, plan.plaza, 2) || wet(b, 3)) continue;
        // it must take in future plots (not only the woods between them)
        let area = 0;
        for (const c of plan.candidates) {
          const ix = Math.max(0, Math.min(b.x + b.w, c.x + c.w) - Math.max(b.x, c.x)), iy = Math.max(0, Math.min(b.y + b.h, c.y + c.h) - Math.max(b.y, c.y));
          area += ix * iy;
        }
        if (area < 0.3 * w * h) continue;
        cands.push({ b, d: -Math.hypot(x + w / 2 - cx, (y + h / 2 - cy) * 1.3) + R('inner', x, y) * 6 });
        break;
      }
    }
    cands.sort((p, q) => p.d - q.d);
    let n = 0;
    for (const { b } of cands) {
      if (n >= 18) break;
      if (taken.some((t) => hit(b, t, 2))) continue;
      const mid: [number, number] = [b.x + b.w / 2, b.y + b.h / 2];
      const farm = farms.reduce((p, q) => (Math.hypot(q.rect.x + q.rect.w / 2 - mid[0], q.rect.y + q.rect.h / 2 - mid[1]) < Math.hypot(p.rect.x + p.rect.w / 2 - mid[0], p.rect.y + p.rect.h / 2 - mid[1]) ? q : p));
      taken.push(b);
      const k = n++;
      const rotation = R('inner-use', k) < 0.75 ? (R('inner-rot', k) < 0.5 ? ['corn', 'wheat'] : ['wheat', 'corn', 'corn']) : [HAY];
      fields.push({ ...b, id: `${farm.id}/t${k}`, farm: farm.id, use: rotation[0] === HAY ? 'hay' : 'grain', rotation, phase: Math.floor(R('inner-phase', k) * rotation.length),
        rowsX: b.w >= b.h, gate: gateOf(b, farm.rect, plan.roads) });
    }
  }
  const out: FarmPlan = { seed, roads, farms, fields, extent, water, bridges, entries };
  cache.set(seed, out);
  return out;
}

/** A field's gate: the middle of its side facing the nearest street ([roads]), else the side facing [yard]. */
function gateOf(b: Rect, yard: Rect, roads: readonly Rect[]): FarmField['gate'] {
  let best: { side: Side; d: number } | undefined;
  const sides: [Side, number, number][] = [['N', b.x + b.w / 2, b.y], ['S', b.x + b.w / 2, b.y + b.h], ['W', b.x, b.y + b.h / 2], ['E', b.x + b.w, b.y + b.h / 2]];
  for (const [side, x, y] of sides) for (const r of roads) {
    const d = Math.hypot(Math.max(r.x - x, 0, x - r.x - r.w), Math.max(r.y - y, 0, y - r.y - r.h));
    if (d < 4 && (!best || d < best.d)) best = { side, d };
  }
  if (!best) {
    const ddx = yard.x + yard.w / 2 - (b.x + b.w / 2), ddy = yard.y + yard.h / 2 - (b.y + b.h / 2);
    const horiz = Math.abs(ddx) / b.w > Math.abs(ddy) / b.h;
    best = { side: horiz ? (ddx > 0 ? 'E' : 'W') : (ddy > 0 ? 'S' : 'N'), d: 0 };
  }
  const s = best.side;
  return { x: r3(s === 'E' ? b.x + b.w : s === 'W' ? b.x : b.x + b.w / 2), y: r3(s === 'S' ? b.y + b.h : s === 'N' ? b.y : b.y + b.h / 2), side: s };
}

/** [o] (a street) touches the point [p] where a country road leaves a street's end (the street it continues, or a crossing there). */
function touchesEnd(o: Road, p: [number, number]): boolean {
  return p[0] >= o.x - 1 && p[0] <= o.x + o.w + 1 && p[1] >= o.y - 1 && p[1] <= o.y + o.h + 1;
}

/** The farmstead's buildings and props laid out in its yard (local x along the road, z back from the yard's front). */
function farmstead(seed: string, id: string, road: CountryRoad, rect: Rect, front: Side, o: [number, number], right: [number, number], away: [number, number]): Farmstead {
  const R = (...k: (string | number)[]): number => rand(seed, 'farm', id, ...k);
  // [o] is the yard's front-left corner seen from the road (looking away from it, x runs to [right])
  const mirror = R('mirror') < 0.5;
  const W = (lx: number, lz: number): [number, number] => {
    const x = mirror ? YARD.w - lx : lx;
    return [o[0] + right[0] * x + away[0] * lz, o[1] + right[1] * x + away[1] * lz];
  };
  const toRoad: [number, number] = [-away[0], -away[1]];
  const sideways = (s: 1 | -1): [number, number] => { const v: [number, number] = [right[0] * s * (mirror ? -1 : 1), right[1] * s * (mirror ? -1 : 1)]; return v; };
  const things: FarmThing[] = [];
  const put = (model: FarmModel, lx: number, lz: number, face: [number, number] = toRoad, extra: Partial<FarmThing> = {}): void => {
    const [x, y] = W(lx, lz);
    things.push({ model, x: r3(x), y: r3(y), face, ...extra });
  };
  const house: FarmModel = R('house') < 0.5 ? 'fm_house1' : 'fm_house2';
  const barn = (['fm_barn_red', 'fm_barn_red', 'fm_barn_grey', 'fm_barn_yellow', 'fm_barn_teal'] as const)[Math.floor(R('barn') * 5)]!;
  // the house near the road on the left, the barn back across the yard on the right, its silos beyond it
  put(house, 6.5, 4, toRoad, { door: true });
  put(barn, 19.5, 10, toRoad, { door: true });
  put(R('silo') < 0.5 ? 'fm_silo2' : 'fm_silo1', 26.8, 11.5);
  if (R('silo2') < 0.6) put('fm_silo2', 26.8, 16);
  // the machine shed behind the house, facing across the yard
  put('fm_shed', 5, 15.5, sideways(1));
  put('fm_corral', 19.5, 21.5);
  put('fm_tractor', 13.2, 12, sideways(R('tr') < 0.5 ? 1 : -1));
  put('fm_hay', 13, 22.5);
  put('fm_troughs', 25, 22.8, sideways(-1));
  put(R('well') < 0.5 ? 'fm_well' : 'fm_wellstone', 11, 5.5);
  put('fm_crates', 9, 19.6, sideways(1));
  put('fm_barrels', 9.5, 13, sideways(1));
  const produce = R('produce') < 0.55;
  if (produce) {
    const stalls = ['fm_stall_blue', 'fm_stall_pink', 'fm_stall_green', 'fm_stall_yellow'] as const;
    // by the road on the lane's far side, its front to the road
    put(stalls[Math.floor(R('stall') * 4)]!, 16.5, -0.6, toRoad, { summer: true });
  }
  // the lane: from the road's edge into the yard between the house and the barn
  const la = W(12.5, -(VERGE)); const lb = W(12.5, 9);
  // the windbreak: a row of trees along the back and down the house's side
  const trees: [number, number][] = [];
  for (let x = 1; x < YARD.w; x += 2.6 + R('wb', x) * 0.8) trees.push(W(x, YARD.d - 0.8 - R('wbz', x) * 0.6).map(r3) as [number, number]);
  for (let z = 9; z < YARD.d - 3; z += 2.8 + R('ws', z) * 0.8) trees.push(W(0.8, z).map(r3) as [number, number]);
  const family = FAMILIES[Math.floor(R('family') * FAMILIES.length)]!;
  const g0 = W(9.5, 7), g1 = W(28.5, 24.5);
  const ground: Rect = { x: Math.min(g0[0], g1[0]), y: Math.min(g0[1], g1[1]), w: Math.abs(g1[0] - g0[0]), h: Math.abs(g1[1] - g0[1]) };
  return {
    id, rect, front, road: road.id, family, founded: 1855 + Math.floor(R('founded') * 80),
    household: { surname: family, rural: true, members: 2 + Math.floor(R('members') * 5) },
    things, ground, lane: { a: la.map(r3) as [number, number], b: lb.map(r3) as [number, number], w: 1.6 }, trees, produce,
  };
}

const r3 = (v: number): number => Math.round(v * 1000) / 1000;

/** The fields round [farm]: up to five, beside its yard along the road and behind it. */
function layFields(seed: string, farm: Farmstead, o: [number, number], right: [number, number], away: [number, number], yard: Rect,
  free: (b: Box) => boolean, take: (b: Box) => void): FarmField[] {
  const R = (...k: (string | number)[]): number => rand(seed, 'farm', farm.id, 'field', ...k);
  const out: FarmField[] = [];
  const gap = 2;
  // candidate slots in the yard's frame (x to the right seen from the road, z back from the yard's front): beside the yard
  // along the road each side, behind it, and behind those
  const slots: { a: number; z: number; w: number; d: number }[] = [];
  const W1 = 26 + Math.floor(R('w1') * 18), D1 = 22 + Math.floor(R('d1') * 12);
  const D2 = 22 + Math.floor(R('d2') * 14);
  slots.push({ a: YARD.w + gap, z: 0, w: W1, d: D1 });
  slots.push({ a: -gap - W1, z: 0, w: W1, d: D1 });
  slots.push({ a: 0, z: YARD.d + gap, w: YARD.w, d: D2 });
  slots.push({ a: YARD.w + gap, z: D1 + gap, w: W1, d: D2 });
  slots.push({ a: -gap - W1, z: D1 + gap, w: W1, d: D2 });
  const P = (a: number, z: number): [number, number] => [o[0] + right[0] * a + away[0] * z, o[1] + right[1] * a + away[1] * z];
  const use = (k: number): FieldUse => { const r = R('use', k); return farm.produce && k === 0 ? 'truck' : r < 0.62 ? 'grain' : r < 0.82 ? 'hay' : 'truck'; };
  let k = 0;
  for (const s of slots) {
    if (out.length >= 5) break;
    // shrink a field that does not fit, down to two thirds
    let placed: Box | undefined;
    for (const f of [1, 0.85, 0.7]) {
      const w = Math.round(s.w * f), d = Math.round(s.d * f);
      const a0 = s.a < 0 ? s.a + (s.w - w) : s.a;
      const p = P(a0, s.z), q = P(a0 + w, s.z + d);
      const b = { x: Math.min(p[0], q[0]), y: Math.min(p[1], q[1]), w: Math.abs(q[0] - p[0]), h: Math.abs(q[1] - p[1]) };
      if (free(b)) { placed = b; break; }
    }
    k++;
    if (!placed) continue;
    take(placed);
    const u = use(out.length);
    const rotation = u === 'grain' ? (R('rot', k) < 0.5 ? ['corn', 'corn', 'wheat'] : ['corn', 'wheat'])
      : u === 'hay' ? [HAY]
      : [0, 1, 2, 3].map((j) => PRODUCE[Math.floor(R('veg', k, j) * PRODUCE.length)]!);
    out.push({
      ...placed, id: `${farm.id}/f${out.length}`, farm: farm.id, use: u, rotation, phase: Math.floor(R('phase', k) * rotation.length),
      rowsX: placed.w >= placed.h ? R('rows', k) < 0.8 : R('rows', k) < 0.2, gate: gateOf(placed, yard, []),
    });
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ a year
/** What the town has built in a year (layout.ts): its streets, plots and open landmarks. */
export interface Developed { roads: readonly Rect[]; lots: readonly Rect[]; landmarks: readonly LandmarkPlot[] }

export interface FarmsYear {
  year: number;
  /** Country roads as they run this year: out from the street's end, and in along an unbuilt street's line to a built one. */
  roads: (CountryRoad & { inner?: [number, number] })[];
  /** The farmsteads (all stand every year; `working` is false once all its fields are sold). */
  farms: (Farmstead & { working: boolean })[];
  /** The fields farmed this year (none of them overlaps anything built), with this year's crop. */
  fields: (FarmField & { crop: string })[];
}

/** Is [f] clear of everything built in [d] (a field there would be under a house, a street or a store)? */
export function fieldClear(f: Rect, d: Developed): boolean {
  return !d.roads.some((r) => hit(f, r, 1)) && !d.lots.some((l) => hit(f, l, 1)) && !d.landmarks.some((l) => hit(f, l, 2));
}

/** The farms in [year], given what the town has built then ([d]). */
export function farmsIn(plan: TownPlan, year: number, d: Developed): FarmsYear {
  const fp = planFarms(plan);
  const fields = fp.fields.filter((f) => fieldClear(f, d)).map((f) => ({ ...f, crop: cropIn(f, year) }));
  const live = new Set(fields.map((f) => f.farm));
  const roads = fp.roads.map((r) => {
    if (r.kind === 'ring' || r.root < 0) return r;
    const root = plan.roads[r.root]!;
    if (d.roads.some((b) => b.x === root.x && b.y === root.y && b.w === root.w && b.h === root.h)) return r;
    // the street it continues is not built yet: run on along its line to the first built street
    const [dx, dy] = DIR[r.side];
    for (let t = 1; t < 400; t++) {
      const p: [number, number] = [r.a[0] - dx * t, r.a[1] - dy * t];
      if (d.roads.some((b) => p[0] >= b.x && p[0] <= b.x + b.w && p[1] >= b.y && p[1] <= b.y + b.h)) return { ...r, inner: p };
      if (p[0] < 0 || p[1] < 0 || p[0] > plan.width || p[1] > plan.height) break;
    }
    return r;
  });
  return { year, roads, farms: fp.farms.map((f) => ({ ...f, working: live.has(f.id) })), fields };
}

/** A field's life over the years 1950..last: farmed [from] (1950: farmed before the game begins), until it is built over in [sold] (if it is), by [becomes]. */
export interface FieldLife { id: string; from: number; sold?: number; becomes?: 'houses' | 'shops' | 'street' | 'store' | 'landmark' }

/**
 * Every field's life, given what the town has built each year from 1950 to [last] ([developed]: layout.ts's streets, plots
 * and open landmarks that year). A field already built over in 1950 has no life (it never shows).
 */
export function fieldLives(plan: TownPlan, developed: (year: number) => Developed & { shops?: readonly Rect[] }, last: number): FieldLife[] {
  const fp = planFarms(plan);
  const out = new Map<string, FieldLife>();
  for (let y = 1950; y <= last; y++) {
    const d = developed(y);
    for (const f of fp.fields) {
      const clear = fieldClear(f, d);
      const life = out.get(f.id);
      if (!life) { if (clear && y === 1950) out.set(f.id, { id: f.id, from: 1950 }); continue; }
      if (life.sold !== undefined || clear) continue;
      life.sold = y;
      const lm = d.landmarks.find((l) => hit(f, l, 2));
      life.becomes = lm ? (/^(generalstore|market|supermarket|superstore)$/.test(lm.id) ? 'store' : 'landmark')
        : d.lots.some((l) => hit(f, l, 1)) ? ((d.shops ?? []).some((l) => hit(f, l, 1)) ? 'shops' : 'houses') : 'street';
    }
  }
  return fp.fields.flatMap((f) => (out.has(f.id) ? [out.get(f.id)!] : []));
}
