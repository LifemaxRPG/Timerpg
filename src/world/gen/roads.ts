import { B, type Climate } from './climate';
import { CELL_KM, CELL_M, H, Heap, N, N8, W, distKm, nb, wrapDx, wrapX } from './grid';
import { RIVER_FLOW, type Hydro } from './hydro';
import { hashStr, hashU } from './noise';
import type { Place } from './places';
import type { Terrain } from './terrain';

// ROADS (WORLD_GEN.md section 5). Highways join the hubs and the major towns of each landmass along a Gabriel graph
// (no third place inside the circle on the link: fewer detours than the sketch's relative-neighbourhood graph), each
// a least-cost path on the atlas grid (slope squared, water dear, the hubs' pads closed except at their pins, roads
// already built cheap so routes share); county roads bring every other town to the nearest highway; hamlets stand
// along them every 4-8 km. Every road has years: built, paved, rebuilt as an expressway.

export type RoadClass = 'highway' | 'county';
export interface Road {
  id: string;
  cls: RoadClass;
  /** The places it joins (a county road's b is the highway cell it meets: null). */
  a: string;
  b: string | null;
  cells: number[];
  km: number;
  built: number;
  /** Paved from (a highway by the 1930s, a county road 1950-1975). */
  paved: number;
  /** Rebuilt as an expressway (the limited-access network, from 1956). */
  expressway?: number;
}

/** The road's surface in a year. */
export const surfaceOf = (r: Road, year: number): 'none' | 'dirt' | 'gravel' | 'paved' | 'expressway' =>
  year < r.built ? 'none' : r.expressway !== undefined && year >= r.expressway ? 'expressway' : year >= r.paved ? 'paved' : r.cls === 'highway' ? 'gravel' : year < r.built + 10 ? 'dirt' : 'gravel';

/** A car's speed on it (km/h): highways 80 before 1950, 95 after; expressways 115; county roads 50 gravel, 70 paved. */
export function roadKmh(r: Road | null, year: number): number {
  if (!r) return 30; // town streets (a hub's centre to its pins)
  const s = surfaceOf(r, year);
  if (s === 'expressway') return 115;
  if (r.cls === 'highway') return s === 'paved' ? (year < 1950 ? 80 : 95) : s === 'gravel' ? 55 : 30;
  return s === 'paved' ? 70 : s === 'gravel' ? 50 : 30;
}

export interface Roads { roads: Road[]; hamlets: Place[]; onRoad: Uint8Array }

function baseCost(t: Terrain, cl: Climate, h: Hydro, bio: Uint8Array): Float32Array {
  const cost = new Float32Array(N);
  for (let c = 0; c < N; c++) {
    if (!t.land[c]) { cost[c] = Infinity; continue; }
    const s = cl.slope[c]! / 45;
    cost[c] = 1 + s * s + (h.lake[c] ? 25 : 0) + (h.flow[c]! >= RIVER_FLOW ? 2.5 : 0) + (bio[c] === B.ice || bio[c] === B.glacier ? 4 : 0) + (bio[c] === B.marsh ? 1.5 : 0);
  }
  return cost;
}

/** The pad cells of the hubs (closed to generated routes except through the pins): cell -> hub index. */
export function padCells(places: readonly Place[]): Map<number, number> {
  const out = new Map<number, number>();
  places.forEach((p, k) => {
    if (!p.hub) return;
    const hw = Math.ceil((p.hub.padM.w / 2 + (p.hub.type === 'capital' ? p.hub.growthM : 0)) / CELL_M);
    const hh = Math.ceil((p.hub.padM.h / 2 + (p.hub.type === 'capital' ? p.hub.growthM : 0)) / CELL_M);
    const i0 = Math.floor(p.x), j0 = Math.floor(p.z);
    for (let j = j0 - hh; j <= j0 + hh; j++) for (let i = i0 - hw; i <= i0 + hw; i++) if (j >= 0 && j < H) out.set(j * W + wrapX(i), k);
  });
  return out;
}

/** A* on the atlas grid from [s] to [g] over cells of finite [cost] (8-neighbours, x wraps). */
export class GridSearch {
  private g = new Float64Array(N);
  private from = new Int32Array(N);
  private stamp = new Int32Array(N);
  private closed = new Int32Array(N);
  private gen = 0;
  private heap = new Heap(1 << 16);
  path(cost: Float32Array, s: number, goal: number, hWeight = 1, limit = 3e6): number[] | null {
    const gen = ++this.gen;
    const { g, from, stamp, closed, heap } = this;
    heap.clear();
    const gi = goal % W, gj = (goal / W) | 0;
    const hf = (c: number): number => Math.hypot(wrapDx(gi - (c % W)), gj - ((c / W) | 0)) * hWeight;
    g[s] = 0; from[s] = -1; stamp[s] = gen;
    heap.push(hf(s), s);
    let pops = 0;
    while (heap.size) {
      const c = heap.pop();
      if (closed[c] === gen) continue;
      closed[c] = gen;
      if (c === goal) break;
      if (++pops > limit) return null;
      const gc = g[c]!, cc = cost[c]!;
      for (let k = 0; k < 8; k++) {
        const m = nb(c, k);
        if (m < 0 || closed[m] === gen) continue;
        const cm = cost[m]!;
        if (!Number.isFinite(cm)) continue;
        const ng = gc + N8[k]![2] * (cc + cm) * 0.5;
        if (stamp[m] !== gen || ng < g[m]!) { stamp[m] = gen; g[m] = ng; from[m] = c; heap.push(ng + hf(m), m); }
      }
    }
    if (closed[goal] !== gen) return null;
    const out: number[] = [];
    for (let c = goal; c >= 0; c = from[c]!) out.push(c);
    return out.reverse();
  }
  /** Dijkstra from many sources over finite [cost]; returns each cell's predecessor (-1 at a source, -2 unreached). */
  flood(cost: Float32Array, sources: Iterable<number>): Int32Array {
    const pred = new Int32Array(N).fill(-2);
    const gen = ++this.gen;
    const { g, stamp, closed, heap } = this;
    heap.clear();
    for (const s of sources) { g[s] = 0; stamp[s] = gen; pred[s] = -1; heap.push(0, s); }
    while (heap.size) {
      const c = heap.pop();
      if (closed[c] === gen) continue;
      closed[c] = gen;
      const gc = g[c]!, cc = cost[c]!;
      for (let k = 0; k < 8; k++) {
        const m = nb(c, k);
        if (m < 0 || closed[m] === gen) continue;
        const cm = cost[m]!;
        if (!Number.isFinite(cm)) continue;
        const ng = gc + N8[k]![2] * (cc + cm) * 0.5;
        if (stamp[m] !== gen || ng < g[m]!) { stamp[m] = gen; g[m] = ng; pred[m] = c; heap.push(ng, m); }
      }
    }
    return pred;
  }
}

export const pathKm = (cells: readonly number[]): number => {
  let s = 0;
  for (let q = 1; q < cells.length; q++) {
    const a = cells[q - 1]!, b = cells[q]!;
    s += Math.hypot(wrapDx((b % W) - (a % W)), ((b / W) | 0) - ((a / W) | 0));
  }
  return s * CELL_KM;
};

/** Gabriel graph of points (no other point inside the circle whose diameter is the link), x wrapping. */
export function gabriel(pts: readonly { x: number; z: number }[]): [number, number][] {
  const out: [number, number][] = [];
  for (let a = 0; a < pts.length; a++) for (let b = a + 1; b < pts.length; b++) {
    const A = pts[a]!, Bp = pts[b]!;
    const mx = A.x + wrapDx(Bp.x - A.x) / 2, mz = (A.z + Bp.z) / 2;
    const r2 = (wrapDx(Bp.x - A.x) ** 2 + (Bp.z - A.z) ** 2) / 4;
    let ok = true;
    for (let c = 0; c < pts.length && ok; c++) {
      if (c === a || c === b) continue;
      const C = pts[c]!;
      if (wrapDx(C.x - mx) ** 2 + (C.z - mz) ** 2 < r2) ok = false;
    }
    if (ok) out.push([a, b]);
  }
  return out;
}

/** The pin of hub [p] of [kind] facing [to] best (its cell), or the place's own cell. */
export function pinToward(p: Place, kind: 'highway' | 'rail', to: { x: number; z: number }): number {
  const pins = (p.pins ?? []).filter((q) => q.kind === kind);
  if (!pins.length) return p.cell;
  const dx = wrapDx(to.x - p.x), dz = to.z - p.z, d = Math.hypot(dx, dz) || 1;
  let best = pins[0]!, bs = -Infinity;
  for (const q of pins) { const s = (q.ox * dx + q.oz * dz) / d; if (s > bs) { bs = s; best = q; } }
  // (one cell out past the pin, so the route leaves the pad the pin's way)
  const out = Math.floor(best.z + best.oz * 1.2) * W + wrapX(Math.floor(best.x + best.ox * 1.2));
  return out >= 0 && out < N ? out : best.cell;
}

export function makeRoads(t: Terrain, cl: Climate, h: Hydro, bio: Uint8Array, hubs: Place[], towns: Place[]): Roads {
  const seed = t.seed;
  const base = baseCost(t, cl, h, bio);
  const pads = padCells(hubs);
  const cost = Float32Array.from(base);
  for (const [c] of pads) if (Number.isFinite(cost[c]!)) cost[c] = cost[c]! * 40;
  const search = new GridSearch();
  const roads: Road[] = [];
  const onRoad = new Uint8Array(N);
  const hs = hashStr(`${seed}|road-hist`);
  const places = [...hubs, ...towns];
  const byMass = new Map<number, Place[]>();
  for (const p of places) { const a = byMass.get(p.mass); if (a) a.push(p); else byMass.set(p.mass, [p]); }
  const masses = [...byMass.keys()].sort((a, b) => a - b);
  // ---- highways
  for (const m of masses) {
    const all = byMass.get(m)!;
    let nodes = all.filter((p) => p.kind === 'hub' || p.rank === 'major');
    if (nodes.length < 2) nodes = all.filter((p) => p.kind === 'hub' || p.rank !== 'small');
    if (nodes.length < 2) nodes = all;
    if (nodes.length < 2) continue;
    const edges = gabriel(nodes).map(([a, b]) => ({ a, b, d: distKm(nodes[a]!.x, nodes[a]!.z, nodes[b]!.x, nodes[b]!.z) }))
      .sort((p, q) => p.d - q.d || p.a - q.a || p.b - q.b);
    for (const e of edges) {
      const A = nodes[e.a]!, Bp = nodes[e.b]!;
      const s = A.hub ? pinToward(A, 'highway', Bp) : A.cell, g = Bp.hub ? pinToward(Bp, 'highway', A) : Bp.cell;
      const sv = cost[s]!, gv = cost[g]!;
      cost[s] = base[s]!; cost[g] = base[g]!;
      const path = search.path(cost, s, g, 0.6);
      cost[s] = sv; cost[g] = gv;
      if (!path) continue;
      const cells = [...(A.hub ? [A.cell] : []), ...path, ...(Bp.hub ? [Bp.cell] : [])];
      const id = `hw:${roads.length}`;
      const r = (k: string): number => hashU(hs, hashStr(id + k));
      const built = 1905 + Math.floor(r('b') * 25);
      const both = A.kind === 'hub' && Bp.kind === 'hub';
      const capital = A.type === 'capital' || Bp.type === 'capital';
      const road: Road = { id, cls: 'highway', a: A.id, b: Bp.id, cells, km: Math.round(pathKm(cells) * 10) / 10, built, paved: Math.min(1938, built + 6 + Math.floor(r('p') * 10)) };
      if (both || (capital && r('x') < 0.8) || (Bp.kind === 'hub' || A.kind === 'hub') && r('x') < 0.35) road.expressway = (capital ? 1956 : 1959) + Math.floor(r('e') * (capital ? 8 : 22));
      roads.push(road);
      for (const c of path) { onRoad[c] = 1; cost[c] = Math.min(cost[c]!, 0.35 * base[c]!); }
    }
  }
  // ---- county roads: every place not on a highway to the nearest highway cell
  const sources: number[] = [];
  for (let c = 0; c < N; c++) if (onRoad[c]) sources.push(c);
  const pred = search.flood(base, sources);
  for (const p of places) {
    if (p.kind === 'hub' && roads.some((r) => r.a === p.id || r.b === p.id)) continue;
    if (onRoad[p.cell] || pred[p.cell] === -2) continue;
    const cells: number[] = [];
    for (let c = p.cell; c >= 0; c = pred[c]!) cells.push(c);
    const id = `co:${roads.length}`;
    const r = (k: string): number => hashU(hs, hashStr(id + k));
    const built = Math.max(1880, Math.min(1935, p.founded + 5 + Math.floor(r('b') * 20)));
    const paved = p.rank === 'major' || p.kind === 'hub' ? 1935 + Math.floor(r('p') * 15) : 1950 + Math.floor(r('p') * 26);
    roads.push({ id, cls: 'county', a: p.id, b: null, cells, km: Math.round(pathKm(cells) * 10) / 10, built, paved });
    for (const c of cells) onRoad[c] = 2;
  }
  // ---- hamlets: crossroads places every 4-8 km along the roads, kept 3.5 km off every other place
  const hamlets: Place[] = [];
  const BK = 8, nbx = Math.ceil(W / BK);
  const bucket = new Map<number, { x: number; z: number }[]>();
  const addB = (x: number, z: number): void => { const k = Math.floor(z / BK) * nbx + (Math.floor(x / BK) % nbx); const a = bucket.get(k); if (a) a.push({ x, z }); else bucket.set(k, [{ x, z }]); };
  const near = (x: number, z: number, km: number): boolean => {
    const bx = Math.floor(x / BK), bz = Math.floor(z / BK);
    for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
      const a = bucket.get((bz + dz) * nbx + ((((bx + dx) % nbx) + nbx) % nbx));
      if (a) for (const q of a) if (distKm(x, z, q.x, q.z) < km) return true;
    }
    return false;
  };
  for (const p of places) addB(p.x, p.z);
  for (const [c] of pads) addB((c % W) + 0.5, ((c / W) | 0) + 0.5);
  const hh = hashStr(`${seed}|hamlets`);
  for (const r of roads) {
    let next = 4 + 4 * hashU(hh, hashStr(r.id));
    let run = 0;
    for (let q = 1; q < r.cells.length; q++) {
      const a = r.cells[q - 1]!, c = r.cells[q]!;
      run += Math.hypot(wrapDx((c % W) - (a % W)), ((c / W) | 0) - ((a / W) | 0)) * CELL_KM;
      if (run < next) continue;
      const x = (c % W) + 0.5, z = ((c / W) | 0) + 0.5;
      if (!t.land[c] || h.lake[c] || bio[c] === B.glacier || bio[c] === B.ice || near(x, z, 3.5)) continue;
      run = 0; next = 4 + 4 * hashU(hh, c);
      hamlets.push({ id: `m:${hamlets.length}`, kind: 'hamlet', type: 'hamlet', rank: 'small', x, z, cell: c, mass: t.mass[c]!, seed: `${seed}/m/${hamlets.length}`, name: '', founded: 0, coastal: h.coast[c]! > 0 });
      addB(x, z);
    }
  }
  return { roads, hamlets, onRoad };
}

// ---------------------------------------------------------------- the road graph (for the planner)

export interface RoadEdge { to: number; km: number; road: number }

/** The road network as a graph of node cells (places, pins, ends, junctions), each edge one stretch of one road. */
export class RoadGraph {
  readonly adj = new Map<number, RoadEdge[]>();
  /** A place's node cell. */
  readonly nodeOf = new Map<string, number>();
  constructor(readonly roads: readonly Road[], places: readonly Place[]) {
    const isNode = new Set<number>();
    for (const r of roads) { isNode.add(r.cells[0]!); isNode.add(r.cells[r.cells.length - 1]!); }
    for (const p of places) { isNode.add(p.cell); this.nodeOf.set(p.id, p.cell); }
    // (where roads cross or meet: a cell on two roads)
    const seen = new Map<number, number>();
    roads.forEach((r, k) => { for (const c of r.cells) { const o = seen.get(c); if (o === undefined) seen.set(c, k); else if (o !== k) isNode.add(c); } });
    roads.forEach((r, k) => {
      let from = r.cells[0]!, km = 0;
      for (let q = 1; q < r.cells.length; q++) {
        const a = r.cells[q - 1]!, c = r.cells[q]!;
        km += Math.hypot(wrapDx((c % W) - (a % W)), ((c / W) | 0) - ((a / W) | 0)) * CELL_KM;
        if (isNode.has(c) || q === r.cells.length - 1) {
          if (c !== from) { this.add(from, c, km, k); this.add(c, from, km, k); }
          from = c; km = 0;
        }
      }
    });
  }
  private add(a: number, b: number, km: number, road: number): void {
    let l = this.adj.get(a);
    if (!l) { l = []; this.adj.set(a, l); }
    l.push({ to: b, km, road });
  }
  /** The quickest drive from [a] to [b] (place ids) in [year] at [speed] (a function of the road): minutes, km, roads. */
  drive(a: string, b: string, year: number, speed: (r: Road | null, year: number) => number = roadKmh): { min: number; km: number; roads: string[]; cells: number[] } | null {
    const s = this.nodeOf.get(a), g = this.nodeOf.get(b);
    if (s === undefined || g === undefined) return null;
    if (s === g) return { min: 0, km: 0, roads: [], cells: [s] };
    const dist = new Map<number, number>([[s, 0]]), km = new Map<number, number>([[s, 0]]);
    const prev = new Map<number, { from: number; road: number }>();
    const heap = new Heap(1024);
    heap.push(0, s);
    const done = new Set<number>();
    while (heap.size) {
      const c = heap.pop();
      if (done.has(c)) continue;
      done.add(c);
      if (c === g) break;
      const dc = dist.get(c)!;
      for (const e of this.adj.get(c) ?? []) {
        const r = this.roads[e.road]!;
        // (before a road was built there was a wagon track the same way: slow, but there)
        const nd = dc + (e.km / speed(r, year)) * 60;
        if (nd < (dist.get(e.to) ?? Infinity)) { dist.set(e.to, nd); km.set(e.to, km.get(c)! + e.km); prev.set(e.to, { from: c, road: e.road }); heap.push(nd, e.to); }
      }
    }
    if (!done.has(g)) return null;
    const rs: string[] = [], cells: number[] = [g];
    for (let c = g; c !== s; ) { const p = prev.get(c)!; const id = this.roads[p.road]!.id; if (rs[rs.length - 1] !== id) rs.push(id); c = p.from; cells.push(c); }
    return { min: dist.get(g)!, km: Math.round(km.get(g)! * 10) / 10, roads: rs.reverse(), cells: cells.reverse() };
  }
}
