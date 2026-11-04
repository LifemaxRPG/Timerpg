import { zoneNeighborhoods, type Neighborhood, type NeighborhoodId } from './neighborhoods';
import { rand } from '../sim/rng';
import { placeFrontage } from './frontage';
import { greenery } from './greenery';
import { Grid } from './grid';
import { SIZES, type LotKind, type Rect, type Road } from './kinds';
import { placeLandmarks, type LandmarkPlot } from './landmarks';
import { planNetwork } from './network';
import { planWater, shiftWater, waterKind, waterNear, type WaterBody } from './water';

export { FRONT, SIZES } from './kinds';
export type { LotKind, Rect, Road } from './kinds';

// The TOWN PLAN is everything about the place that does not depend on the year or on
// time-travel edits: street network, water, building plots, plaza, landmarks, greenery, street
// furniture. It is a pure function of the seed. `layout.ts` then decides who/what occupies the plots.
//
// Pipeline: street network -> water -> plaza -> landmarks -> building plots along the street
// frontages -> crop to the built-up area -> greenery and street furniture.

export interface Candidate extends Rect {
  id: string;
  kind: LotKind;
  /** Lower = closer to the town center; the town fills from rank 0 outward. */
  rank: number;
  neighborhood: NeighborhoodId;
}

export type PropKind = 'tree' | 'lamp' | 'bench' | 'bush' | 'flowerBush' | 'flowers' | 'fountain1' | 'fountain2' | 'fence' | 'hedge' | 'path';
/** A decoration. x is the center and y the bottom edge, in pixels. look picks the variant. */
export interface Prop {
  kind: PropKind; x: number; y: number; look: number;
  /** Index into plan.roads for street furniture: it is only drawn while that street is built (see layout.ts). */
  road?: number;
}

export interface TownPlan {
  seed: string;
  width: number;
  height: number;
  roads: Road[];
  candidates: Candidate[];
  neighborhoods: Neighborhood[];
  /** Static decoration (street furniture, plaza, greenery). */
  props: Prop[];
  mainY: number;
  center: { x: number; y: number };
  plaza: Rect;
  /** Civic landmark plots (reserved; which are open depends on the year, see layout.ts). */
  landmarks: LandmarkPlot[];
  /** River or lake (null if this seed has none); see water.ts. Bridges are in `water.bridges`. */
  water: WaterBody | null;
}

export const TILE = 32;

const cache = new Map<string, TownPlan>();

/** Is tile (tx,ty) on a road or its 1-tile sidewalk? */
export function nearRoad(roads: readonly Road[], tx: number, ty: number): boolean {
  return roads.some((r) => tx >= r.x - 1 && tx < r.x + r.w + 1 && ty >= r.y - 1 && ty < r.y + r.h + 1);
}

const FOREST = 14; // tiles of woodland kept around the built-up area
const PLAZA = { w: 16, h: 13 };

interface Built {
  net: ReturnType<typeof planNetwork>;
  water: WaterBody | null;
  plaza: Rect;
  landmarks: LandmarkPlot[];
  lots: ReturnType<typeof placeFrontage>;
  ok: boolean;
}

function build(seed: string, extra: number): Built | null {
  const net = planNetwork(seed, extra, waterKind(seed) === 'river');
  const { width: W, height: H, roads } = net;
  const water = planWater(seed, { width: W, height: H, marginL: 8, marginR: 8, top: 8, vroads: [], roads });
  const grid = new Grid(W, H);
  for (const r of roads) grid.mark(r, 1);
  if (water) {
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (water.raw[y * W + x]) grid.mark({ x, y, w: 1, h: 1 }, 2);
  }

  // Plaza: on the main street, as close to the center as a free spot allows.
  let plaza: Rect | undefined;
  let bestD = Infinity;
  for (const m of roads.filter((r) => r.main && r.horizontal)) {
    for (const north of [true, false]) {
      for (let x = m.x + 2; x + PLAZA.w <= m.x + m.w - 2; x++) {
        const rect = { x, y: north ? m.y - 1 - PLAZA.h : m.y + m.h + 1, w: PLAZA.w, h: PLAZA.h };
        const d = Math.abs(x + PLAZA.w / 2 - net.center.x) + (north ? 0 : 6);
        if (d < bestD && grid.free(rect, 2)) { plaza = rect; bestD = d; }
      }
    }
  }
  if (!plaza) return null;
  grid.mark(plaza, 1);

  const lm = placeLandmarks(seed, {
    marginL: 4, marginR: 4, top: 4, width: W, height: H, roads, plaza, center: net.center,
    plots: [], keep: [], civicStrip: false, blocked: (b) => !grid.free(b, 2),
  });
  for (const l of lm.landmarks) grid.mark(l, 1);

  const lots = placeFrontage({ seed, grid, roads, center: net.center, radius: net.radius });
  const n = (k: LotKind): number => lots.filter((l) => l.kind === k).length;
  // The sim never needs more than ~20 shops and ~100 households (apartments house about 4 each).
  const homeRoom = n('small') + n('medium') + n('house') + 4 * n('apartment');
  const ok = n('shop') >= 20 && n('villa') >= 2 && homeRoom >= 100 && lm.landmarks.length >= 3;
  return { net, water, plaza, landmarks: lm.landmarks, lots, ok };
}

/** Store a plan computed elsewhere (a worker), so planTown(seed) returns it instantly. */
export function primePlan(seed: string, plan: TownPlan): void {
  cache.set(seed, plan);
}

export function planTown(seed: string): TownPlan {
  const hit = cache.get(seed);
  if (hit) return hit;
  const R = (...k: (string | number)[]): number => rand(seed, 'plan', ...k);

  // Grow the town (longer streets, more of them) until everything fits.
  let built: Built | null = null;
  for (let extra = 0; extra <= 8; extra++) {
    const b = build(seed, extra);
    if (b) built = b;
    if (b?.ok) break;
  }
  if (!built) throw new Error(`no town plan fits for seed ${seed}`);
  const { net, plaza } = built;

  // ---- crop to the built-up area (plus woodland) and move into final coordinates ----
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  const grow = (r: Rect): void => {
    minX = Math.min(minX, r.x); minY = Math.min(minY, r.y);
    maxX = Math.max(maxX, r.x + r.w); maxY = Math.max(maxY, r.y + r.h);
  };
  [...net.roads, ...built.lots, ...built.landmarks, plaza].forEach(grow);
  const dx = FOREST - minX, dy = FOREST - minY;
  const width = maxX - minX + 2 * FOREST, height = maxY - minY + 2 * FOREST;
  const mv = <T extends Rect>(r: T): T => ({ ...r, x: r.x + dx, y: r.y + dy });
  const roads = net.roads.map(mv);
  const center = { x: net.center.x + dx, y: net.center.y + dy };
  const mainY = net.mainY + dy;
  const plazaR = mv(plaza);
  const landmarks = built.landmarks.map(mv);
  const water = shiftWater(built.water, dx, dy, width, height);
  const sites = built.lots.map(mv).map((c) => ({
    ...c,
    rank: Math.hypot(c.x + c.w / 2 - center.x, (c.y + c.h / 2 - center.y) * 1.3) + R('rank', c.id) * 8,
  })).sort((a, b) => a.rank - b.rank || (a.id < b.id ? -1 : 1));

  const { neighborhoods, candidates } = zoneNeighborhoods(seed, sites, center, water);

  // ---- static decoration ----
  const props: Prop[] = [];
  const look = (...k: (string | number)[]): number => R('look', ...k);
  const add = (kind: PropKind, tx: number, ty: number, l: number, road?: number): void => {
    props.push({ kind, x: Math.round(tx * TILE), y: Math.round(ty * TILE), look: l, ...(road === undefined ? {} : { road }) });
  };
  const insideOther = (self: Road, tx: number, ty: number): boolean =>
    roads.some((o) => o !== self && tx >= o.x - 1 && tx < o.x + o.w + 1 && ty >= o.y - 1 && ty < o.y + o.h + 1);

  // Street lamps along every sidewalk (and benches along the main street), skipping junctions.
  for (const r of roads) {
    const spacing = r.main ? 9 : 13;
    const start = (r.horizontal ? r.x : r.y) + 2 + Math.floor(R('lamp0', r.x, r.y) * spacing);
    const end = (r.horizontal ? r.x + r.w : r.y + r.h) - 2;
    for (const side of [0, 1]) {
      for (let t = start; t < end; t += spacing) {
        const tx = r.horizontal ? t : side ? r.x + r.w : r.x - 1;
        const ty = r.horizontal ? (side ? r.y + r.h : r.y - 1) : t;
        if (insideOther(r, tx, ty) || waterNear(water, tx, ty, 2)) continue;
        add('lamp', tx + 0.5, ty + 1, look('lamp', r.x, r.y, side, t), roads.indexOf(r));
        if (r.main && r.horizontal && look('bench', r.x, side, t) < 0.4) {
          const bx = t + 4;
          if (!insideOther(r, bx, ty) && !waterNear(water, bx, ty, 2)) add('bench', bx, ty + 1, look('bench2', t, side), roads.indexOf(r));
        }
      }
    }
  }

  // Plaza: fountain, benches, trees, flower beds.
  const pcx = plazaR.x + plazaR.w / 2;
  const pby = plazaR.y + plazaR.h - 1;
  add('fountain2', pcx, pby - 4, look('fountain'));
  add('bench', pcx - 4, pby - 2, 0.1);
  add('bench', pcx + 4, pby - 2, 0.1);
  for (let i = 0; i < 6; i++) {
    add('tree', plazaR.x + 1.5 + (i % 3) * ((plazaR.w - 3) / 2), plazaR.y + 3 + Math.floor(i / 3) * 2.2, look('ptree', i));
  }
  for (let i = 0; i < 4; i++) add('flowerBush', plazaR.x + 3 + i * 3.4, pby, look('pfb', i));

  // Greenery: sparse near buildings, thickening into forest toward the edge.
  const developed = new Grid(width, height);
  for (const r of roads) developed.mark(r, 1);
  for (const c of candidates) developed.mark(c, 1);
  for (const l of landmarks) developed.mark(l, 1);
  developed.mark(plazaR, 1);
  const wet = new Grid(width, height);
  if (water) for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) if (water.raw[y * width + x]) wet.mark({ x, y, w: 1, h: 1 }, 2);
  props.push(...greenery(seed, developed, wet));

  const plan: TownPlan = { seed, width, height, roads, candidates, neighborhoods, props, mainY, center, plaza: plazaR, landmarks, water };
  cache.set(seed, plan);
  return plan;
}
