import { zoneNeighborhoods, type Neighborhood, type NeighborhoodId } from './neighborhoods';
import { rand } from '../sim/rng';
import { placeFrontage } from './frontage';
import { greenery } from './greenery';
import { Grid } from './grid';
import { SIZES, type LotKind, type Rect, type Road } from './kinds';
import { LANDMARK_KINDS, LANDMARK_SPECS } from './landmarks';
import { placeLandmarks, type LandmarkPlot } from './landmarks';
import { hasCinema } from './cinema';
import { isStoreSite, storeNeeds } from './stores';
import { EDGE_CANVAS, placeDowntownStores, placeEdgeStores } from './storeSites';
import { planNetwork } from './network';
import { planWater, riverWater, shiftWater, waterKind, waterNear, type WaterBody } from './water';
import { PLATTED, platTown, type Block, type District, type Plat, type RailBand } from './plat';
import { districtsOf, type Reserved } from './districts';
import { generalStoreBuilt } from './stores';
import { POPULATION_BY, POPULATION_FROM, populationIn } from './population';
import { streetBlockBuilt } from './downtown';

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
  /** Lower = closer to the town center; the town fills from rank 0 outward (the plan's order: district by district). */
  rank: number;
  neighborhood: NeighborhoodId;
  /** The plan's district (src/town/plat.ts); absent in the old plan. */
  district?: District;
  /** The year the lot is built when the district is built out (homes: as the town grows into the plan; shops: their
   * street block's year); 9999 never by the game's present. Absent in the old plan. */
  from?: number;
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
  /** The planned town (src/town/plat.ts, src/town/districts.ts); absent in the old plan. */
  v2?: PlanV2;
}

/** What the planned town adds to the plan (all in the plan's tiles). */
export interface PlanV2 {
  form: Plat['form'];
  size: Plat['size'];
  countySeat: boolean;
  blocks: Block[];
  /** The class of each street (index as plan.roads): main, street, depot, court, railroad, south, river, bridge, bypass, stub. */
  roadClass: string[];
  /** The reservation ledger: every reserved plot with its chain of uses. */
  reserved: Reserved[];
  alleys: Rect[];
  railBand: RailBand | null;
  /** The town hall's plot when it is not the plaza's back (a county seat's, facing the square). */
  townHall: Rect | null;
  /** The plan's move from the plat's canvas (plat + shift = plan). */
  shift: { dx: number; dy: number };
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
  /** The cinema has its site (or the town is too small for one). */
  cinema: boolean;
  /** Every store the town needs has its site (src/town/stores.ts storeNeeds). */
  stores: boolean;
}

function build(seed: string, extra: number): Built | null {
  const needs = storeNeeds(seed);
  const net0 = planNetwork(seed, extra, waterKind(seed) === 'river');
  // a town with stores at its edge plans on a bigger canvas (east and south), for them beyond the main street's end
  const pad = needs.superstore !== undefined ? EDGE_CANVAS.superstore : needs.supermarket !== undefined ? EDGE_CANVAS.supermarket : undefined;
  const net = !pad ? net0 : { ...net0, width: net0.width + pad.right, height: net0.height + pad.bottom, roads: [...net0.roads] };
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
  // the general store and the market, downtown (src/town/storeSites.ts), inside the streets' extent
  const ext = {
    x0: Math.min(...roads.map((r) => r.x)), y0: Math.min(...roads.map((r) => r.y), plaza.y),
    x1: Math.max(...roads.map((r) => r.x + r.w)), y1: Math.max(...roads.map((r) => r.y + r.h), plaza.y + plaza.h),
  };
  const downtown = placeDowntownStores(seed, { grid, roads, plaza, ext });

  let lots = placeFrontage({ seed, grid, roads, center: net.center, radius: net.radius });
  // the supermarket and the superstore beyond the east end of the main street, which runs on out of town past them
  const edge = placeEdgeStores(seed, { width: W, height: H, roads, taken: [...lm.landmarks, ...downtown, plaza], lots, water: water?.raw });
  if (edge.road) {
    const main = edge.road;
    roads[edge.index] = main;
    // a side street the road out of town crosses is cut in two at the junction (as the network cuts them)
    for (const i of [...edge.split].sort((a, b) => b - a)) {
      const r = roads[i]!;
      const pieces = [{ ...r, h: main.y - r.y }, { ...r, y: main.y + main.h, h: r.y + r.h - main.y - main.h }].filter((q) => q.h >= 2);
      roads.splice(i, 1, ...pieces);
    }
  }
  if (edge.dropped.length) lots = lots.filter((l) => !edge.dropped.includes(l.id));
  const stores = [...downtown, ...edge.plots];
  const has = (id: string): boolean => stores.some((l) => l.id === id);
  const storesOk = has('generalstore') && (needs.market === undefined || has('market'))
    && (needs.supermarket === undefined || has('supermarket')) && (needs.superstore === undefined || has('superstore'));
  const n = (k: LotKind): number => lots.filter((l) => l.kind === k).length;
  // The sim needs up to ~24 shops and ~105 households (apartments house about 4 each), with the stores' sites taken
  // (a few of the main street's plots, and a few where the road out of town runs).
  const homeRoom = n('small') + n('medium') + n('house') + 4 * n('apartment');
  const ok = n('shop') >= 24 && n('villa') >= 2 && homeRoom >= 106 && lm.landmarks.filter((l) => l.id !== 'cinema' && !isStoreSite(l.id)).length >= 3;
  // a town big enough for a cinema grows its streets until the cinema has a site too (src/town/cinema.ts)
  const cinema = !hasCinema(seed) || lm.landmarks.some((l) => l.id === 'cinema');
  return { net, water, plaza, landmarks: [...lm.landmarks, ...stores], lots, ok, cinema, stores: storesOk };
}

/** Store a plan computed elsewhere (a worker), so planTown(seed) returns it instantly. */
export function primePlan(seed: string, plan: TownPlan): void {
  cache.set(seed, plan);
}

/** The planned town (TOWN_PLAN.md) or the old grown one (network.ts, frontage.ts): kept behind this switch until the
 * plan is accepted. */
let PLAN_V2 = true;
export function setPlanVersion(v: 1 | 2): void { if ((v === 2) !== PLAN_V2) { PLAN_V2 = v === 2; cache.clear(); } }
export const planVersion = (): 1 | 2 => (PLAN_V2 ? 2 : 1);

export function planTown(seed: string): TownPlan {
  const hit = cache.get(seed);
  if (hit) return hit;
  if (PLAN_V2) { const p = planTownV2(seed); cache.set(seed, p); return p; }
  const R = (...k: (string | number)[]): number => rand(seed, 'plan', ...k);

  // Grow the town (longer streets, more of them) until everything fits.
  let built: Built | null = null;
  // the smallest that fits everything, else the smallest that fits the town and its stores, else the town and its cinema,
  // else the town alone
  let fits: Built | null = null;
  const score = (b: Built): number => (b.stores ? 2 : 0) + (b.cinema ? 1 : 0);
  for (let extra = 0; extra <= 8; extra++) {
    const b = build(seed, extra);
    if (b) built = b;
    if (b?.ok && (!fits || score(b) > score(fits))) fits = b;
    if (b?.ok && b.cinema && b.stores) break;
  }
  if (fits && !(built?.ok && built.cinema && built.stores)) built = fits;
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

// ------------------------------------------------------------------------------------------------ the planned town

/** Neighbourhoods by district: the old town and West Main old-town, post-war and the strip's rear gardens, the heights,
 * the later subdivisions woodside, across the tracks "the Flats", the river's banks riverside. */
const HOOD: Record<District, NeighborhoodId> = {
  core: 'old-town', square: 'old-town', civic: 'old-town', old: 'old-town', westmain: 'old-town', worker: 'the-flats', rail: 'the-flats',
  strip: 'gardens', postwar: 'gardens', heights: 'heights', late: 'woodside', edge: 'woodside', river: 'waterfront',
};

/** Households the town has in [year] (a household is ~3.2 simulated people; before 1950 a little smaller each decade). */
function homesIn(seed: string, year: number): number {
  const persons = populationIn(seed, Math.min(POPULATION_BY, Math.max(POPULATION_FROM, year)));
  return (persons / 3.2) * (year < POPULATION_FROM ? Math.max(0.55, 1 - (POPULATION_FROM - year) / 120) : 1);
}

/** Homes built per simulated household when the plan is built out (TOWN_PLAN.md "Households and filler homes"). */
export const HOMES_PER_HOUSEHOLD = 3;

function planTownV2(seed: string): TownPlan {
  const R = (...k: (string | number)[]): number => rand(seed, 'plan', ...k);
  const plat = platTown(seed);
  const dist = districtsOf(seed);
  // ---- crop to the built-up area (plus woodland) and move into final coordinates
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  const grow = (r: Rect): void => { minX = Math.min(minX, r.x); minY = Math.min(minY, r.y); maxX = Math.max(maxX, r.x + r.w); maxY = Math.max(maxY, r.y + r.h); };
  [...plat.roads, ...dist.lots, ...dist.reserved.filter((q) => q.id !== 'civic:depot'), dist.plaza].forEach(grow);
  const dx = FOREST - minX, dy = FOREST - minY;
  const width = maxX - minX + 2 * FOREST, height = maxY - minY + 2 * FOREST;
  const mv = <T extends Rect>(r: T): T => ({ ...r, x: r.x + dx, y: r.y + dy });
  const roads = plat.roads.map(mv);
  const plaza = mv(dist.plaza);
  const center = { x: plat.center.x + dx, y: plat.center.y + dy };
  const mainY = plat.mainY + dy;
  const reserved = dist.reserved.map(mv);

  // ---- civic landmarks and stores in their reserved plots
  const landmarks: LandmarkPlot[] = [];
  const needs = storeNeeds(seed);
  for (const q of reserved) {
    if (!q.id.startsWith('landmark:')) continue;
    const id = q.id.slice('landmark:'.length) as LandmarkPlot['id'];
    const s = LANDMARK_SPECS[id];
    const openYear = id === 'generalstore' ? generalStoreBuilt(seed) : id === 'market' ? needs.market! : id === 'supermarket' ? needs.supermarket! : id === 'superstore' ? needs.superstore!
      : s.open[0] + Math.floor(rand(seed, 'landmark', 'year', id) * (s.open[1] - s.open[0] + 1));
    landmarks.push({ id, name: s.name, blurb: s.blurb, x: q.x, y: q.y, w: q.w, h: q.h, foot: id === 'cinema' || isStoreSite(id) ? q.h : s.foot, openYear, look: rand(seed, 'landmark', 'look', id) });
  }
  landmarks.sort((a, b) => LANDMARK_KINDS.indexOf(a.id) - LANDMARK_KINDS.indexOf(b.id));

  // ---- water: the river down its course (meandering inside its column), or a lake beyond the town's edge
  let water: WaterBody | null = null;
  if (plat.river) water = riverWater(seed, plat.river.channel.map(mv), plat.river.column.map(mv), width, height, roads);
  else if (waterKind(seed) === 'lake') water = planWater(seed, { width, height, marginL: 8, marginR: 8, top: 8, vroads: [], roads, avoid: [...dist.lots.map(mv), ...reserved] });

  // ---- the lots: plan order, and the year each is built as the town grows into the plan
  const lots = dist.lots.map(mv).filter((l) => !water || !hitsWaterRect(water, l, 1));
  // (a home is a building: an apartment block counts as one, as a house does)
  const cap = (k: LotKind): number => (k === 'shop' ? 0 : 1);
  const homes = lots.filter((l) => l.kind !== 'shop');
  const total = homes.reduce((n, l) => n + cap(l.kind), 0);
  let peak = 0;
  for (let y = POPULATION_FROM; y <= POPULATION_BY; y++) peak = Math.max(peak, homesIn(seed, y));
  // the plan is sized for the town's peak: about three homes a simulated household (the simulated families live in a
  // third of them, the town's other families in the rest), never more than the plan holds
  const fill = Math.min(total * 0.96, peak * HOMES_PER_HOUSEHOLD) / Math.max(1, peak);
  const HOMES: number[] = [];
  for (let y = 1880; y <= POPULATION_BY; y++) HOMES.push(homesIn(seed, y) * fill);
  const from = new Map<string, number>();
  let filled = 0;
  for (const l of homes) {
    const i = HOMES.findIndex((h) => h > filled);
    const y = i < 0 ? 9999 : Math.max(1880 + i, PLATTED[l.district] + Math.floor(R('lag', l.id) * 8));
    from.set(l.id, y);
    filled += cap(l.kind);
  }
  const sites: (Candidate & { rank: number })[] = lots.map((l) => {
    const id = `${l.kind}:${l.x},${l.y}`;
    const f = l.kind === 'shop' ? streetBlockBuilt(seed, id, l.order, l.district !== 'core') : from.get(l.id)!;
    return { x: l.x, y: l.y, w: l.w, h: l.h, id, kind: l.kind, rank: l.order, neighborhood: HOOD[l.district], district: l.district, from: f };
  });
  sites.sort((a, b) => a.rank - b.rank || (a.id < b.id ? -1 : 1));
  const neighborhoods = districtNeighborhoods(seed, sites, center, water);

  // ---- static decoration: lamps along the streets, the plaza's furniture (not on a courthouse square: the model has its own)
  const props: Prop[] = [];
  const look = (...k: (string | number)[]): number => R('look', ...k);
  const add = (kind: PropKind, tx: number, ty: number, l: number, road?: number): void => {
    props.push({ kind, x: Math.round(tx * TILE), y: Math.round(ty * TILE), look: l, ...(road === undefined ? {} : { road }) });
  };
  const insideOther = (self: Road, tx: number, ty: number): boolean =>
    roads.some((o) => o !== self && tx >= o.x - 1 && tx < o.x + o.w + 1 && ty >= o.y - 1 && ty < o.y + o.h + 1);
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
  // the riverwalk through the core: lamps and benches along both banks (its paving is the export's: town-yards.ts)
  for (const b of plat.blocks.filter((q) => q.district === 'river' && (q.row === 0 || q.row === -1)).map(mv)) {
    for (const x of [b.x + 1, b.x + b.w - 1]) {
      for (let y = b.y + 3; y < b.y + b.h - 2; y += 9) {
        if (waterNear(water, Math.floor(x), y, 1)) continue;
        add('lamp', x, y + 1, look('rwlamp', x, y));
        if (y + 4 < b.y + b.h - 2 && !waterNear(water, Math.floor(x), y + 4, 1)) add('bench', x, y + 5, look('rwbench', x, y));
      }
    }
  }
  if (!plat.countySeat) {
    const pcx = plaza.x + plaza.w / 2;
    const pby = plaza.y + plaza.h - 1;
    add('fountain2', pcx, pby - 4, look('fountain'));
    add('bench', pcx - 4, pby - 2, 0.1);
    add('bench', pcx + 4, pby - 2, 0.1);
    for (let i = 0; i < 6; i++) add('tree', plaza.x + 1.5 + (i % 3) * ((plaza.w - 3) / 2), plaza.y + 3 + Math.floor(i / 3) * 2.2, look('ptree', i));
    for (let i = 0; i < 4; i++) add('flowerBush', plaza.x + 3 + i * 3.4, pby, look('pfb', i));
  }
  const developed = new Grid(width, height);
  for (const r of roads) developed.mark(r, 1);
  for (const c of sites) developed.mark(c, 1);
  for (const l of landmarks) developed.mark(l, 1);
  for (const q of reserved) developed.mark(q, 1);
  for (const a of dist.alleys) developed.mark(mv(a), 0);
  developed.mark(plaza, 1);
  const wet = new Grid(width, height);
  if (water) for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) if (water.raw[y * width + x]) wet.mark({ x, y, w: 1, h: 1 }, 2);
  props.push(...greenery(seed, developed, wet));

  const hall = reserved.find((q) => q.id === 'civic:townhall');
  const railBand = plat.railBand ? { ...plat.railBand, rect: mv(plat.railBand.rect), track: plat.railBand.track + (plat.railBand.horizontal ? dy : dx), depot: { x: plat.railBand.depot.x + dx, y: plat.railBand.depot.y + dy } } : null;
  return {
    seed, width, height, roads, candidates: sites, neighborhoods, props, mainY, center, plaza, landmarks, water,
    v2: { form: plat.form, size: plat.size, countySeat: plat.countySeat, blocks: plat.blocks.map(mv), roadClass: plat.roadClass, reserved,
      alleys: dist.alleys.map(mv), railBand, townHall: hall ? { x: hall.x, y: hall.y, w: hall.w, h: hall.h } : null, shift: { dx, dy } },
  };
}

const hitsWaterRect = (w: WaterBody, r: Rect, pad: number): boolean => {
  for (let y = Math.max(0, r.y - pad); y < Math.min(w.height, r.y + r.h + pad); y++)
    for (let x = Math.max(0, r.x - pad); x < Math.min(w.width, r.x + r.w + pad); x++) if (w.raw[y * w.width + x]) return true;
  return false;
};

/** The neighbourhoods of a planned town: each district's, anchored at its lots' middle (zoneNeighborhoods' names and styles). */
function districtNeighborhoods(seed: string, sites: Candidate[], center: { x: number; y: number }, water: WaterBody | null): Neighborhood[] {
  const { neighborhoods } = zoneNeighborhoods(seed, sites.map((s) => ({ ...s })), center, water);
  const out: Neighborhood[] = [];
  for (const id of ['old-town', 'heights', 'gardens', 'woodside', 'waterfront', 'the-flats'] as NeighborhoodId[]) {
    const mine = sites.filter((s) => s.neighborhood === id);
    const base = neighborhoods.find((n) => n.id === id);
    if (!mine.length && id !== 'old-town') continue;
    const anchor = mine.length ? { x: mine.reduce((t, s) => t + s.x + s.w / 2, 0) / mine.length, y: mine.reduce((t, s) => t + s.y + s.h / 2, 0) / mine.length } : center;
    if (base) out.push({ ...base, center: id === 'old-town' ? center : anchor });
    else if (id === 'waterfront') out.push({ id, name: water?.kind === 'lake' ? 'Lakeside' : 'Riverside', center: anchor, palette: 'blue', shopColour: 'blue', yard: 'natural' });
    else if (id === 'the-flats') out.push({ id, name: 'The Flats', center: anchor, palette: 'stone', shopColour: 'gray', yard: 'compact' });
    else out.push({ id, name: id === 'woodside' ? 'Woodside' : id, center: anchor, palette: 'sage', shopColour: 'gray', yard: 'natural' });
  }
  return out;
}
