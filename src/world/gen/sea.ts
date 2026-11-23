import { CELL_KM, H, Heap, N, W, latOf, nb, wrapDx } from './grid';
import { hashStr, hashU } from './noise';
import type { Place } from './places';
import type { Terrain } from './terrain';

// SEA ROUTES AND SAILINGS (WORLD_GEN.md sections 6 and 10). The ports are the hub ports plus a ferry landing on every
// landmass that has places but no hub port (so every island town is reachable). Liner routes join the hub ports
// (a minimum spanning tree of the sea distances, each port's nearest port on another continent, and a line round the
// world in order of longitude); ferries join each landing to its nearest port. Paths are least-cost on a 2-cell sea
// grid (land impassable, the pack ice past 63 deg closed, the shallows by the coast a little dear). A crossing's time
// is the compression integral: the boat's visual speed times c = clamp(distance to land / 1 km, 1, 30).

/** Sea grid: 2 x 2 atlas cells (2.15 km). */
const S = 2;
const SW = W / S, SH = H / S, SN = SW * SH;
export const D0_KM = 1;
export const C_MAX = 30;
export const compression = (dKm: number): number => Math.min(C_MAX, Math.max(1, dKm / D0_KM));

export type SeaKind = 'liner' | 'coastal' | 'round' | 'ferry';
/** Visual speeds (km/h): the player's sailboat 6 knots; the ships of the routes. */
export const SAIL_KMH = 11.1;
export const VESSEL_KMH: Record<SeaKind, number> = { liner: 37, round: 33, coastal: 22, ferry: 28 };

export interface SeaRoute {
  id: string;
  kind: SeaKind;
  a: string;
  b: string;
  /** Atlas cells along the way (sea), from a's harbour to b's. */
  cells: number[];
  km: number;
  /** km of it out of sight of land (c at its cap). */
  openKm: number;
  /** Game hours for the route's ship and for the player's sailboat (the compression integral). */
  hours: number;
  sailHours: number;
  /** Hours at true speed (no compression) for the route's ship. */
  trueHours: number;
  opened: number;
  /** Liner service ends (then a car ferry or cruises may run on: see sailingsPerWeek). */
  ends?: number;
  /** A cruise route from this year. */
  cruise?: number;
}

export interface SeaNet { ports: Place[]; landings: Place[]; routes: SeaRoute[]; harbour: Map<string, number> }

/** Sailings a week each way on [r] in [year] (0: none that year), and the kind of ship. */
export type Ship = 'liner' | 'steamer' | 'ferry' | 'car ferry' | 'cruise ship' | 'freighter';
/** (When the liners and steamers stop, a route never goes dead: a car ferry takes over the short ones, and a weekly
 * freighter that carries a few passengers the long ones, as the cargo lines did; cruise ships come on some from the 1980s.) */
export function sailingsPerWeek(r: SeaRoute, year: number): { perWeek: number; ship: Ship | null } {
  if (year < r.opened) return { perWeek: 0, ship: null };
  switch (r.kind) {
    case 'ferry': return { perWeek: year < 1960 ? 7 : 14, ship: year < 1955 ? 'ferry' : 'car ferry' };
    case 'coastal':
      if (r.ends === undefined || year < r.ends) return { perWeek: year < 1950 ? 3 : 2, ship: 'steamer' };
      return r.km < 400 ? { perWeek: 7, ship: 'car ferry' } : r.cruise !== undefined && year >= r.cruise ? { perWeek: 1, ship: 'cruise ship' } : { perWeek: 1, ship: 'freighter' };
    case 'round':
      if (r.ends === undefined || year < r.ends) return { perWeek: 0.5, ship: 'liner' };
      return r.cruise !== undefined && year >= r.cruise ? { perWeek: 0.5, ship: 'cruise ship' } : { perWeek: 0.5, ship: 'freighter' };
    default:
      if (r.ends === undefined || year < r.ends) return { perWeek: year < 1950 ? 2 : year <= 1960 ? 3 : 1, ship: 'liner' };
      if (r.km < 450) return { perWeek: 7, ship: 'car ferry' };
      return r.cruise !== undefined && year >= r.cruise ? { perWeek: 1, ship: 'cruise ship' } : { perWeek: 1, ship: 'freighter' };
  }
}

/** The departures of [r] from end [from] in [year]: minutes from the start of week 0 (day 0, 00:00), repeating with
 * [period] (minutes). Hours are fixed per route, direction and era. */
export function sailings(r: SeaRoute, from: string, year: number): { period: number; times: number[] } {
  const { perWeek } = sailingsPerWeek(r, year);
  if (!perWeek) return { period: 7 * 1440, times: [] };
  const era = year < 1950 ? 0 : year < (r.ends ?? 9999) ? 1 : 2;
  const h = hashStr(`${r.id}|${from}|${era}`);
  const period = perWeek >= 1 ? 7 * 1440 : Math.round(7 / perWeek) * 1440;
  const n = Math.max(1, Math.round(perWeek >= 1 ? perWeek : 1));
  const times: number[] = [];
  const span = period / n;
  for (let k = 0; k < n; k++) {
    const day = Math.floor(hashU(h, k) * (span / 1440));
    const hour = n >= 7 ? 6 * 60 + Math.floor(hashU(h, 100 + (k % 2)) * 4) * 120 : 9 * 60 + Math.floor(hashU(h, 200 + k) * 10) * 60;
    times.push(Math.round(k * span + day * 1440 + hour) % period);
  }
  return { period, times: times.sort((a, b) => a - b) };
}

/** The next sailing from [from] at or after [t] (minutes from week 0's start), or Infinity. */
export function nextSailing(r: SeaRoute, from: string, year: number, t: number): number {
  const { period, times } = sailings(r, from, year);
  if (!times.length) return Infinity;
  const base = Math.floor(t / period) * period;
  for (const b of [base, base + period]) for (const x of times) if (b + x >= t) return b + x;
  return Infinity;
}

export function makeSea(t: Terrain, places: Place[], hubs: Place[]): SeaNet {
  const seed = t.seed;
  const hs = hashStr(`${seed}|sea`);
  // ---- the sea grid
  const sea = new Uint8Array(SN);
  const scost = new Float32Array(SN);
  const sdist = new Float32Array(SN);
  for (let sj = 0; sj < SH; sj++) {
    const lat = Math.abs(latOf(sj * S + 1));
    for (let si = 0; si < SW; si++) {
      let k = 0, dl = 0;
      for (let dj = 0; dj < S; dj++) for (let di = 0; di < S; di++) { const c = (sj * S + dj) * W + si * S + di; if (!t.land[c]) k++; dl += t.distLand[c]!; }
      const q = sj * SW + si;
      sea[q] = k >= 2 && lat < 63 ? 1 : 0;
      const d = dl / (S * S);
      sdist[q] = d;
      scost[q] = (d < 2 ? 1.6 : d < 6 ? 1.25 : 1) * (lat > 58 ? 1.5 : 1);
    }
  }
  const snb = (q: number, k: number): number => {
    const j = (q / SW) | 0, i = q - j * SW;
    const dj = [-1, -1, -1, 0, 0, 1, 1, 1][k]!, di = [-1, 0, 1, -1, 1, -1, 0, 1][k]!;
    const jj = j + dj;
    if (jj < 0 || jj >= SH) return -1;
    return jj * SW + ((i + di + SW) % SW);
  };
  const toS = (c: number): number => (((c / W) | 0) >> 1) * SW + ((c % W) >> 1);
  // a port's harbour: the nearest sea cell (fine), then its sea-grid cell (opened if need be)
  const harbour = new Map<string, number>();
  const harbourOf = (p: Place): number | null => {
    const i0 = p.cell % W, j0 = (p.cell / W) | 0;
    let best: number | null = null, bd = Infinity;
    for (let r = 1; r <= 12 && best === null; r++) for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) {
      const j = j0 + dj;
      if (j < 0 || j >= H) continue;
      const c = j * W + ((i0 + di + W) % W);
      if (t.land[c]) continue;
      const d = Math.hypot(di, dj);
      if (d < bd) { bd = d; best = c; }
    }
    if (best === null) return null;
    harbour.set(p.id, best);
    const q = toS(best);
    sea[q] = 1;
    return q;
  };
  // ---- the ports: hub ports, then a ferry landing on each landmass with places and no hub port
  const ports = hubs.filter((p) => p.type === 'port');
  const masses = new Set(places.map((p) => p.mass));
  // (each landmass's candidates for its landing, best first: the first whose harbour opens on the open sea is used)
  const landingCands: Place[][] = [];
  for (const m of [...masses].sort((a, b) => a - b)) {
    if (ports.some((p) => p.mass === m)) continue;
    const here = places.filter((p) => p.mass === m);
    const rankV = (p: Place): number => (p.kind === 'hub' ? 3 : p.rank === 'major' ? 2.5 : p.rank === 'town' ? 2 : p.kind === 'town' ? 1.5 : 1);
    landingCands.push(here.map((p) => ({ p, s: rankV(p) * (p.coastal ? 3 : 1) - t.distSea[p.cell]! / 10 })).sort((a, b) => b.s - a.s || (a.p.id < b.p.id ? -1 : 1)).slice(0, 8).map((o) => o.p));
  }
  const landings: Place[] = [];
  const portQ = new Map<string, number>();
  for (const p of ports) { const q = harbourOf(p); if (q !== null) portQ.set(p.id, q); }
  // ---- Dijkstra on the sea grid
  const dist = new Float64Array(SN), pred = new Int32Array(SN);
  const heap = new Heap(1 << 15);
  const flood = (src: number, stopAt?: Set<number>): number | null => {
    dist.fill(Infinity); pred.fill(-1);
    heap.clear();
    dist[src] = 0; heap.push(0, src);
    while (heap.size) {
      const q = heap.pop();
      const d = heap.lastKey;
      if (d > dist[q]!) continue;
      if (stopAt && stopAt.has(q) && q !== src) return q;
      for (let k = 0; k < 8; k++) {
        const m = snb(q, k);
        if (m < 0 || !sea[m]) continue;
        const step = (k === 0 || k === 2 || k === 5 || k === 7 ? Math.SQRT2 : 1) * (scost[q]! + scost[m]!) * 0.5;
        const nd = d + step;
        if (nd < dist[m]!) { dist[m] = nd; pred[m] = q; heap.push(nd, m); }
      }
    }
    return null;
  };
  const trace = (g: number): number[] => { const out: number[] = []; for (let q = g; q >= 0; q = pred[q]!) out.push(q); return out.reverse(); };
  const hp = ports.filter((p) => portQ.has(p.id));
  const n = hp.length;
  const M: number[][] = hp.map(() => new Array<number>(n).fill(Infinity));
  const paths = new Map<string, number[]>();
  for (let a = 0; a < n; a++) {
    flood(portQ.get(hp[a]!.id)!);
    for (let b = 0; b < n; b++) if (b !== a) { M[a]![b] = dist[portQ.get(hp[b]!.id)!]!; if (b > a && Number.isFinite(M[a]![b]!)) paths.set(`${a}|${b}`, trace(portQ.get(hp[b]!.id)!)); }
  }
  const pairs = new Map<string, SeaKind>();
  // MST (Prim)
  if (n > 1) {
    const inT = new Array<boolean>(n).fill(false); inT[0] = true;
    for (let it = 1; it < n; it++) {
      let ba = -1, bb = -1, bd = Infinity;
      for (let a = 0; a < n; a++) if (inT[a]) for (let b = 0; b < n; b++) if (!inT[b] && M[a]![b]! < bd) { bd = M[a]![b]!; ba = a; bb = b; }
      if (bb < 0) break;
      inT[bb] = true;
      pairs.set(`${Math.min(ba, bb)}|${Math.max(ba, bb)}`, hp[ba]!.mass === hp[bb]!.mass ? 'coastal' : 'liner');
    }
    for (let a = 0; a < n; a++) {
      let bb = -1, bd = Infinity;
      for (let b = 0; b < n; b++) if (hp[b]!.mass !== hp[a]!.mass && M[a]![b]! < bd) { bd = M[a]![b]!; bb = b; }
      if (bb >= 0) pairs.set(`${Math.min(a, bb)}|${Math.max(a, bb)}`, 'liner');
    }
    // round the world: the ports in order of longitude (consecutive pairs not already joined)
    const ordr = hp.map((p, i) => ({ i, x: p.x })).sort((p, q) => p.x - q.x || p.i - q.i).map((o) => o.i);
    for (let k = 0; k < ordr.length; k++) {
      const a = ordr[k]!, b = ordr[(k + 1) % ordr.length]!;
      const key = `${Math.min(a, b)}|${Math.max(a, b)}`;
      if (a !== b && !pairs.has(key) && Number.isFinite(M[a]![b]!)) pairs.set(key, 'round');
    }
  }
  const routes: SeaRoute[] = [];
  const build = (id: string, kind: SeaKind, a: Place, b: Place, sq: number[]): void => {
    const cells = sq.map((q) => ((q / SW) | 0) * S * W + (q % SW) * S + W + 1);
    cells[0] = harbour.get(a.id) ?? cells[0]!; cells[cells.length - 1] = harbour.get(b.id) ?? cells[cells.length - 1]!;
    let km = 0, open = 0, hours = 0, sail = 0;
    for (let k = 1; k < cells.length; k++) {
      const u = cells[k - 1]!, v = cells[k]!;
      const seg = Math.hypot(wrapDx((v % W) - (u % W)), ((v / W) | 0) - ((u / W) | 0)) * CELL_KM;
      const d = (t.distLand[u]! + t.distLand[v]!) / 2;
      const c = compression(d);
      km += seg; if (c >= C_MAX) open += seg;
      hours += seg / (VESSEL_KMH[kind] * c); sail += seg / (SAIL_KMH * c);
    }
    const r = (k: string): number => hashU(hs, hashStr(id + k));
    const opened = kind === 'ferry' ? Math.max(1890, Math.min(1935, Math.max(a.founded, b.founded) + 10)) : 1870 + Math.floor(r('o') * 40);
    const route: SeaRoute = { id, kind, a: a.id, b: b.id, cells, km: Math.round(km), openKm: Math.round(open), hours: r1(hours), sailHours: r1(sail), trueHours: r1(km / VESSEL_KMH[kind]), opened };
    if (kind === 'liner' || kind === 'round') route.ends = 1965 + Math.floor(r('e') * 11);
    if (kind === 'coastal') route.ends = 1950 + Math.floor(r('e') * 12);
    if (kind !== 'ferry' && r('c') < 0.45) route.cruise = 1980 + Math.floor(r('cy') * 15);
    routes.push(route);
  };
  for (const [key, kind] of [...pairs].sort((p, q) => (p[0] < q[0] ? -1 : 1))) {
    const [a, b] = key.split('|').map(Number) as [number, number];
    const p = paths.get(`${a}|${b}`);
    if (p) build(`sea:${routes.length}`, kind, hp[a]!, hp[b]!, p);
  }
  // ferries: each landing to its nearest port (a hub port or, failing that, any other landing already joined)
  const portSet = new Map<number, Place>();
  for (const p of hp) portSet.set(portQ.get(p.id)!, p);
  for (const cands of landingCands) {
    for (const l of cands) {
      const q = harbourOf(l);
      if (q === null) continue;
      const g = flood(q, new Set(portSet.keys()));
      if (g === null) { harbour.delete(l.id); continue; }
      portQ.set(l.id, q);
      build(`ferry:${routes.length}`, 'ferry', l, portSet.get(g)!, trace(g));
      portSet.set(q, l);
      landings.push(l);
      break;
    }
  }
  return { ports: hp, landings, routes, harbour };
}

const r1 = (v: number): number => Math.round(v * 10) / 10;
void N; void nb;
