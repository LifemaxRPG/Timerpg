import { CELL_KM, CELL_M, H, Heap, N, W, distKm, wrapDx, wrapX } from './grid';
import type { Hydro } from './hydro';
import { hashStr, hashU } from './noise';
import type { Pt } from './paths';
import type { Place } from './places';
import { gabriel, type Road } from './roads';
import type { Terrain } from './terrain';

// HIGHWAYS THAT EVOLVE (WORLD_GEN.md "Highways that evolve", Highways v2). roads.ts and winding.ts make the old network
// (highways and county roads, with their years and terrain-following lines); this pass gives every piece of road a whole
// life by year and adds what the 20th century built on top:
// - a road's life: a wagon track before it was a road, dirt, gravel, the first paved two lanes, the 1930s realignments
//   (the worst curves straightened, a hill cut through; the old curvy piece left as a local road, some abandoned later),
//   widening to four lanes on the busy highways, the 1950s-60s bypasses round towns, then the expressways and beltways,
//   and from the 1970s the old highways the expressways replaced renumbered, turned into county roads, or kept;
// - a planned national expressway system (the Pennant routes) from 1956: per landmass, a few long corridors through the
//   hubs and the major towns (a spanning tree of the highways' own Gabriel graph plus the links a tree would make very
//   roundabout), chained into straight-through corridors, built in stages to 1985, the busiest (the sum of the places they
//   serve) first; each on its own new line (routed on 269 m nodes with a 5% grade limit, kept off the towns' cores, then
//   pulled straight and filleted into wide curves), its profile cut and filled to 5% (cuts, fills, tunnels and viaducts as
//   data), bridges on every river crossing (never fords);
// - beltways round the hubs and the biggest junction towns (1960s-70s), radials from them to an interchange near the town's
//   edge on the old highway (at the capital: the highway leaving its pin, so the plan's pins stay exact);
// - interchanges as data (system, service, terminal, the bypasses' junctions; their design by era), exits numbered by km;
// - lanes by year, never fewer; route numbers by year; frontage roads, rest areas, weigh stations, truck stops, motels,
//   diners and toll plazas as sites with a kind, a year range and the model that fits it;
// - for every town: whether and when it was bypassed (by the expressway or a highway bypass), and its interchange.
// One rule for every segment: it carries its whole history ([Seg]: built, gone, class, surface, lanes and number by year),
// and [segAt]/[networkAt] give exactly one year's roads. Pure in the world.

export type Surface = 'track' | 'dirt' | 'gravel' | 'paved' | 'concrete';
export type SegClass = 'track' | 'county' | 'highway' | 'expressway' | 'local' | 'frontage';
export type SegKind = 'original' | 'realignment' | 'bypass' | 'expressway' | 'beltway' | 'radial' | 'connector' | 'frontage';
/** A value from a year on. */
export type Step<T> = [number, T];

/** The expressways' grade limit (rise over run), on their cut-and-filled profile. */
export const XGRADE = 0.05;
/** Years an abandoned road takes to fade from the land (after which it is gone from every map). */
export const FADE_YEARS = 25;
/** The expressway system's first year and its last stage. */
export const SYSTEM_FROM = 1956;
export const SYSTEM_TO = 1985;
/** The national highway numbering's year (the old roads have no numbers before it). */
export const NUMBERING_YEAR = 1927;

export interface Structure { kind: 'cut' | 'fill' | 'tunnel' | 'viaduct'; /** km along the segment */ from: number; to: number; /** deepest cut or highest fill (m) */ depthM: number }
/** A river crossing: a ford until [bridge] (from [ford]), a bridge from [bridge]. */
export interface Crossing { at: Pt; flow: number; bridge: number; ford?: number }

export interface Seg {
  id: string;
  kind: SegKind;
  mass: number;
  /** The old road (w.roads id) a piece of it is, or the one a realignment, bypass or radial belongs with. */
  road?: string;
  /** A piece of an old road: its stretch of the road's path (point indices, inclusive). */
  range?: [number, number];
  /** Its line (atlas cell units, x unwrapped along it). */
  path: Pt[];
  km: number;
  /** The first year it was any way at all (a track for the old roads). */
  built: number;
  /** Abandoned from (it fades over [FADE_YEARS]). */
  gone?: number;
  cls: Step<SegClass>[];
  surface: Step<Surface>[];
  lanes: Step<number>[];
  /** Divided (a median) from. */
  divided?: number;
  number: Step<string | null>[];
  /** The route (RouteNo id) it carries the number of, when it does. */
  route?: string;
  crossings: Crossing[];
  structures: Structure[];
  /** Steepest grade of its profile (expressways: the cut-and-filled one). */
  maxGrade: number;
}

export interface SegState { cls: SegClass | 'abandoned'; surface: Surface; lanes: number; divided: boolean; number: string | null; /** 0 in use, up to 1 as an abandoned road fades */ fade: number }

export const valueAt = <T>(steps: readonly Step<T>[], year: number): T | undefined => {
  let v: T | undefined;
  for (const [y, x] of steps) { if (y <= year) v = x; else break; }
  return v;
};

/** A segment in [year], or null when it is not there (not built yet, or abandoned and faded). */
export function segAt(s: Seg, year: number): SegState | null {
  if (year < s.built) return null;
  const surface = valueAt(s.surface, year)!;
  if (s.gone !== undefined && year >= s.gone) {
    if (year >= s.gone + FADE_YEARS) return null;
    return { cls: 'abandoned', surface: surface === 'paved' || surface === 'concrete' ? surface : 'dirt', lanes: 0, divided: false, number: null, fade: (year - s.gone + 1) / FADE_YEARS };
  }
  return { cls: valueAt(s.cls, year)!, surface, lanes: valueAt(s.lanes, year)!, divided: s.divided !== undefined && year >= s.divided, number: valueAt(s.number, year) ?? null, fade: 0 };
}

export type InterchangeKind = 'system' | 'service' | 'terminal' | 'junction';
export interface Interchange {
  id: string;
  kind: InterchangeKind;
  /** Its design when built, and when (if) it was rebuilt to another. */
  type: string;
  year: number;
  rebuilt?: Step<string>;
  x: number;
  z: number;
  mass: number;
  /** The expressway segments meeting there, the old road it meets, the town it serves. */
  segs: string[];
  road?: string;
  place?: string;
  /** Exit numbers by route designation (km from the route's start). */
  exits: Record<string, string>;
}

export interface RouteNo {
  id: string;
  /** pennant: the expressways; national: the highways; county: county roads. */
  system: 'pennant' | 'national' | 'county';
  n: number;
  kind: 'trunk' | 'loop' | 'spur' | 'business';
  /** NS (odd), EW (even), ring (loops); spurs and business routes take their parent's. */
  dir: 'NS' | 'EW' | 'ring';
  designation: string;
  mass: number;
  parent?: string;
  from: number;
  /** The places it links, in order (trunks). */
  places: string[];
}

export type SiteKind = 'rest area' | 'weigh station' | 'truck stop' | 'motel' | 'diner' | 'toll plaza';
export interface Site {
  id: string;
  kind: SiteKind;
  x: number;
  z: number;
  mass: number;
  from: number;
  to?: number;
  seg?: string;
  interchange?: string;
  /** The model that fits each era of it (src/town/glbLandmarks.ts ids), when we have one. */
  models: { from: number; to?: number; id: string }[];
}

export interface TownRoads {
  place: string;
  /** The old highway through its Main Street. */
  mainStreet?: string;
  bypassed?: { year: number; by: 'expressway' | 'highway'; seg: string };
  interchange?: string;
  interchangeKm?: number;
  /** The year its nearest interchange opened. */
  interchangeYear?: number;
}

export interface HighwayStats {
  corridors: number; edges: number; unrouted: number; relaxed: number; beltways: number; radials: number;
  interchanges: Record<string, number>; realignments: number; highwayBypasses: number; bypassed: number;
  expresswayKm: number; tunnels: number; ms: number;
}

export interface Highways {
  segs: Seg[];
  interchanges: Interchange[];
  routes: RouteNo[];
  sites: Site[];
  towns: Map<string, TownRoads>;
  /** Extra roads for the road graph: the expressways (limited access, joined only at their junctions) and connectors. */
  links: Road[];
  stats: HighwayStats;
}

/** The network in [year]: every segment there, with its state. */
export function networkAt(hw: Highways, year: number): { seg: Seg; state: SegState }[] {
  const out: { seg: Seg; state: SegState }[] = [];
  for (const seg of hw.segs) { const state = segAt(seg, year); if (state) out.push({ seg, state }); }
  return out;
}

/** km of road by class in [year] (abandoned roads not counted). */
export function kmByClass(hw: Highways, year: number): Record<string, number> {
  const out: Record<string, number> = {};
  for (const { seg, state } of networkAt(hw, year)) if (state.cls !== 'abandoned') out[state.cls] = (out[state.cls] ?? 0) + seg.km;
  for (const k of Object.keys(out)) out[k] = Math.round(out[k]!);
  return out;
}

// ------------------------------------------------------------------------------------------------ geometry helpers

const dist = (a: Pt, b: Pt): number => Math.hypot(b[0] - a[0], b[1] - a[1]);
const shiftTo = (p: Pt, near: number): Pt => [near + wrapDx(p[0] - near), p[1]];
export function lineLen(l: readonly Pt[]): number { let s = 0; for (let i = 1; i < l.length; i++) s += dist(l[i - 1]!, l[i]!); return s; }
function cumOf(l: readonly Pt[]): Float64Array { const c = new Float64Array(l.length); for (let i = 1; i < l.length; i++) c[i] = c[i - 1]! + dist(l[i - 1]!, l[i]!); return c; }
function pointAt(l: readonly Pt[], cum: Float64Array, s: number): { p: Pt; i: number } {
  if (s <= 0) return { p: [l[0]![0], l[0]![1]], i: 0 };
  let i = 1;
  while (i < l.length - 1 && cum[i]! < s) i++;
  const a = l[i - 1]!, b = l[i]!, L = cum[i]! - cum[i - 1]!, t = L > 0 ? Math.min(1, (s - cum[i - 1]!) / L) : 0;
  return { p: [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t], i };
}
/** The nearest point of line [l] to [p] (x wrapping): its distance (cells), along-distance and segment index. */
function nearestOn(l: readonly Pt[], cum: Float64Array, p: Pt): { d: number; s: number; i: number; at: Pt } {
  let best = { d: Infinity, s: 0, i: 0, at: l[0]! };
  for (let i = 1; i < l.length; i++) {
    const a = l[i - 1]!, b = l[i]!;
    const px = a[0] + wrapDx(p[0] - a[0]);
    const dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
    const t = l2 > 0 ? Math.max(0, Math.min(1, ((px - a[0]) * dx + (p[1] - a[1]) * dz) / l2)) : 0;
    const q: Pt = [a[0] + dx * t, a[1] + dz * t];
    const d = Math.hypot(px - q[0], p[1] - q[1]);
    if (d < best.d) best = { d, s: cum[i - 1]! + Math.sqrt(l2) * t, i, at: q };
  }
  return best;
}
function sub(l: readonly Pt[], cum: Float64Array, s0: number, s1: number): Pt[] {
  const a = pointAt(l, cum, s0), b = pointAt(l, cum, s1);
  const out: Pt[] = [a.p];
  for (let i = a.i; i < b.i; i++) if (cum[i]! > s0 && cum[i]! < s1) out.push([l[i]![0], l[i]![1]]);
  out.push(b.p);
  return out;
}
function segX(ax: number, az: number, bx: number, bz: number, cx: number, cz: number, dx: number, dz: number): number {
  // the parameter on a-b where it crosses c-d, or -1
  const rx = bx - ax, rz = bz - az, sx = dx - cx, sz = dz - cz;
  const den = rx * sz - rz * sx;
  if (Math.abs(den) < 1e-12) return -1;
  const t = ((cx - ax) * sz - (cz - az) * sx) / den, u = ((cx - ax) * rz - (cz - az) * rx) / den;
  return t >= 0 && t < 1 && u >= 0 && u < 1 ? t : -1;
}
const unit = (dx: number, dz: number): [number, number] => { const l = Math.hypot(dx, dz) || 1; return [dx / l, dz / l]; };

/** A spatial index of line segments by atlas cell (x wrapping). */
class LineIndex {
  readonly lines: Pt[][] = [];
  private cells = new Map<number, number[]>();
  add(line: Pt[]): number {
    const id = this.lines.length;
    this.lines.push(line);
    for (let i = 1; i < line.length; i++) {
      const a = line[i - 1]!, b = line[i]!;
      const n = Math.max(1, Math.ceil(dist(a, b) * 2));
      let last = -1;
      for (let k = 0; k <= n; k++) {
        const x = a[0] + ((b[0] - a[0]) * k) / n, z = a[1] + ((b[1] - a[1]) * k) / n;
        const c = Math.floor(z) * W + wrapX(Math.floor(x));
        if (c === last || c < 0 || c >= N) continue;
        last = c;
        let l = this.cells.get(c);
        if (!l) { l = []; this.cells.set(c, l); }
        const ref = id * 1_000_000 + i;
        if (l[l.length - 1] !== ref) l.push(ref);
      }
    }
    return id;
  }
  /** Segment refs (line, index of its end point) in cells within [r] cells of (x, z). */
  near(x: number, z: number, r: number): Set<number> {
    const out = new Set<number>();
    const i0 = Math.floor(x - r), i1 = Math.floor(x + r), j0 = Math.max(0, Math.floor(z - r)), j1 = Math.min(H - 1, Math.floor(z + r));
    for (let j = j0; j <= j1; j++) for (let i = i0; i <= i1; i++) { const l = this.cells.get(j * W + wrapX(i)); if (l) for (const ref of l) out.add(ref); }
    return out;
  }
  /** Crossings of the segment a-b with the indexed lines: [line, its segment end index, t on a-b, point]. */
  crossings(a: Pt, b: Pt, skip?: (line: number) => boolean): { line: number; i: number; t: number }[] {
    const out: { line: number; i: number; t: number }[] = [];
    {
      // (most segments are nowhere near a line: rejected on the cells of their box)
      let any = false;
      const i0 = Math.floor(Math.min(a[0], b[0]) - 0.5), i1 = Math.floor(Math.max(a[0], b[0]) + 0.5), j0 = Math.max(0, Math.floor(Math.min(a[1], b[1]) - 0.5)), j1 = Math.min(H - 1, Math.floor(Math.max(a[1], b[1]) + 0.5));
      for (let j = j0; j <= j1 && !any; j++) for (let i = i0; i <= i1 && !any; i++) if (this.cells.has(j * W + wrapX(i))) any = true;
      if (!any) return out;
    }
    const refs = new Set<number>();
    const n = Math.max(1, Math.ceil(dist(a, b) * 2));
    for (let k = 0; k <= n; k++) for (const r of this.near(a[0] + ((b[0] - a[0]) * k) / n, a[1] + ((b[1] - a[1]) * k) / n, 0.5)) refs.add(r);
    for (const ref of refs) {
      const line = Math.floor(ref / 1_000_000), i = ref % 1_000_000;
      if (skip?.(line)) continue;
      const l = this.lines[line]!, c = l[i - 1]!, d = l[i]!;
      const sh = wrapDx(c[0] - a[0]) - (c[0] - a[0]);
      const t = segX(a[0], a[1], b[0], b[1], c[0] + sh, c[1], d[0] + sh, d[1]);
      if (t >= 0) out.push({ line, i, t });
    }
    return out.sort((p, q) => p.t - q.t || p.line - q.line || p.i - q.i);
  }
}

// ------------------------------------------------------------------------------------------------ the ground

/** What every routing and profile step asks of the land. */
class Ground {
  private ef: Float64Array;
  readonly rivers = new LineIndex();
  readonly flows: number[] = [];
  readonly bigFlow: number;
  private bucket = new Map<number, Place[]>();
  /** Cells with a town within 3 cells (where [townCost] can be more than 0). */
  private nearTown = new Uint8Array(N);
  constructor(readonly t: Terrain, readonly h: Hydro, readonly places: readonly Place[]) {
    this.ef = Float64Array.from(t.elev, (e) => Math.max(-32768, Math.min(32767, Math.round(e))));
    for (const r of h.rivers) {
      const line: Pt[] = r.cells.map((c) => [(c % W) + 0.5, Math.floor(c / W) + 0.5] as Pt);
      for (let i = 1; i < line.length; i++) line[i] = shiftTo(line[i]!, line[i - 1]![0]);
      this.rivers.add(line); this.flows.push(r.flow);
    }
    const fs = [...this.flows].sort((a, b) => a - b);
    this.bigFlow = fs[Math.floor(fs.length * 0.85)] ?? Infinity;
    for (const p of places) {
      if (p.kind === 'hamlet') continue;
      const k = Math.floor(p.z) * W + wrapX(Math.floor(p.x));
      const l = this.bucket.get(k); if (l) l.push(p); else this.bucket.set(k, [p]);
      const r = p.hub ? Math.ceil((Math.max(p.hub.padM.w, p.hub.padM.h) / 2 + p.hub.growthM) / CELL_M) + 1 : 3;
      for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) { const j = Math.floor(p.z) + dj; if (j >= 0 && j < H) this.nearTown[j * W + wrapX(Math.floor(p.x) + di)] = 1; }
    }
  }
  /** The skeleton's bilinear height (as NearTerrain.height and the Kotlin reader's). */
  height(x: number, z: number): number {
    const ef = this.ef;
    const u = x - 0.5;
    let v = z - 0.5; if (v < 0) v = 0; else if (v > H - 1) v = H - 1;
    const fi = Math.floor(u);
    let j = Math.floor(v); if (j > H - 2) j = H - 2;
    const fu = u - fi, fv = v - j;
    let i0 = fi % W; if (i0 < 0) i0 += W;
    const i1 = i0 + 1 === W ? 0 : i0 + 1, r0 = j * W, r1 = r0 + W;
    const a = ef[r0 + i0]!, b = ef[r0 + i1]!, c = ef[r1 + i0]!, d = ef[r1 + i1]!;
    const top = a + (b - a) * fu;
    return top + ((c + (d - c) * fu) - top) * fv;
  }
  cell(x: number, z: number): number { return Math.floor(z) * W + wrapX(Math.floor(x)); }
  /** On dry land, off the lakes, outside every hub's pad (plus [padMarginM]). */
  open(x: number, z: number, padMarginM = 150): boolean {
    if (z < 0.5 || z > H - 0.5) return false;
    const c = this.cell(x, z);
    if (!this.t.land[c] || this.h.lake[c]) return false;
    return !this.inPad(x, z, padMarginM);
  }
  inPad(x: number, z: number, marginM: number): Place | null {
    for (const p of this.hubsNear(x, z)) {
      const dx = Math.abs(wrapDx(x - p.x)) * CELL_M, dz = Math.abs(z - p.z) * CELL_M;
      if (dx <= p.hub!.padM.w / 2 + marginM && dz <= p.hub!.padM.h / 2 + marginM) return p;
    }
    return null;
  }
  private hubCells = new Map<number, Place[]>();
  private hubsNear(x: number, z: number): Place[] {
    const k = this.cell(Math.floor(x / 8) * 8, Math.floor(z / 8) * 8);
    let l = this.hubCells.get(k);
    if (!l) { l = this.places.filter((p) => p.hub && Math.abs(wrapDx(p.x - x)) < 16 && Math.abs(p.z - z) < 16); this.hubCells.set(k, l); }
    return l;
  }
  /** What keeps an expressway off a town's core: a cost over the plain km (0 well clear of every town). */
  townCost(x: number, z: number): number {
    let e = 0;
    const i0 = Math.floor(x), j0 = Math.floor(z);
    if (j0 < 0 || j0 >= H || !this.nearTown[j0 * W + wrapX(i0)]) return 0;
    for (let dj = -2; dj <= 2; dj++) for (let di = -2; di <= 2; di++) {
      const l = this.bucket.get((j0 + dj) * W + wrapX(i0 + di));
      if (!l) continue;
      for (const p of l) {
        if (p.hub) continue;
        const d = distKm(x, z, p.x, p.z);
        if (d < 1.4) e += 6; else if (d < 2.0) e += 1.2;
      }
    }
    // the capital's growth margin
    for (const p of this.hubsNear(x, z)) {
      if (p.hub!.type !== 'capital') continue;
      const dx = Math.abs(wrapDx(x - p.x)) * CELL_M, dz = Math.abs(z - p.z) * CELL_M;
      if (dx <= p.hub!.padM.w / 2 + p.hub!.growthM && dz <= p.hub!.padM.h / 2 + p.hub!.growthM) e += 3;
    }
    return e;
  }
  /** River crossings of a-b: [river index, t]. */
  crossings(a: Pt, b: Pt): { line: number; t: number }[] { return this.rivers.crossings(a, b); }
}

/** The cut-and-filled profile of a line at [gmax] (the mean of the highest grade-limited line under the ground and the
 * lowest over it: never steeper than [gmax], as near the ground as that allows), sampled every 50 m or less. */
export function profileOf(line: readonly Pt[], gmax: number, height: (x: number, z: number) => number): { s: Float64Array; ground: Float64Array; z: Float64Array } {
  const pts: Pt[] = [];
  for (let i = 1; i < line.length; i++) {
    const a = line[i - 1]!, b = line[i]!, n = Math.max(1, Math.ceil((dist(a, b) * CELL_M) / 50));
    for (let k = i === 1 ? 0 : 1; k <= n; k++) pts.push([a[0] + ((b[0] - a[0]) * k) / n, a[1] + ((b[1] - a[1]) * k) / n]);
  }
  if (line.length === 1) pts.push(line[0]!);
  const n = pts.length, s = new Float64Array(n), g = new Float64Array(n), lo = new Float64Array(n), hi = new Float64Array(n);
  for (let i = 0; i < n; i++) { g[i] = height(pts[i]![0], pts[i]![1]); if (i) s[i] = s[i - 1]! + dist(pts[i - 1]!, pts[i]!) * CELL_M; }
  for (let i = 0; i < n; i++) { lo[i] = g[i]!; hi[i] = g[i]!; }
  for (let i = 1; i < n; i++) { const d = (s[i]! - s[i - 1]!) * gmax; lo[i] = Math.min(lo[i]!, lo[i - 1]! + d); hi[i] = Math.max(hi[i]!, hi[i - 1]! - d); }
  for (let i = n - 2; i >= 0; i--) { const d = (s[i + 1]! - s[i]!) * gmax; lo[i] = Math.min(lo[i]!, lo[i + 1]! + d); hi[i] = Math.max(hi[i]!, hi[i + 1]! - d); }
  const z = new Float64Array(n);
  for (let i = 0; i < n; i++) z[i] = (lo[i]! + hi[i]!) / 2;
  return { s, ground: g, z };
}

/** Where a profile leaves the ground by more than [EARTH_M]: cuts (tunnels where deep and long), fills (viaducts where high). */
export const EARTH_M = 8;
export function structuresOf(p: { s: Float64Array; ground: Float64Array; z: Float64Array }): Structure[] {
  const out: Structure[] = [];
  const n = p.s.length;
  let i = 0;
  while (i < n) {
    const d = p.ground[i]! - p.z[i]!;
    if (Math.abs(d) <= EARTH_M) { i++; continue; }
    const cut = d > 0;
    let j = i, deep = 0;
    while (j < n && (cut ? p.ground[j]! - p.z[j]! : p.z[j]! - p.ground[j]!) > EARTH_M) { deep = Math.max(deep, Math.abs(p.ground[j]! - p.z[j]!)); j++; }
    const from = p.s[Math.max(0, i - 1)]! / 1000, to = p.s[Math.min(n - 1, j)]! / 1000;
    const kind = cut ? (deep > 30 && to - from > 0.25 ? 'tunnel' : 'cut') : deep > 22 ? 'viaduct' : 'fill';
    out.push({ kind, from: Math.round(from * 1000) / 1000, to: Math.round(to * 1000) / 1000, depthM: Math.round(deep) });
    i = j;
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ the router

/** Router nodes per atlas cell (269 m apart) and the 16 moves. */
const XF = 4;
const XSTEP_M = CELL_M / XF;
const XMOVES: [number, number, number][] = [];
for (const [a, b] of [[1, 0], [1, 1], [2, 1]] as const) {
  const set = new Set<string>();
  for (const [p, q] of [[a, b], [b, a]] as const) for (const sp of [1, -1]) for (const sq of [1, -1]) { const k = `${p * sp},${q * sq}`; if (!set.has(k)) { set.add(k); XMOVES.push([p * sp, q * sq, Math.hypot(p, q) * XSTEP_M]); } }
}

class Router {
  private cap = 1 << 18;
  private g = new Float64Array(this.cap);
  private from = new Int32Array(this.cap);
  private seen = new Int32Array(this.cap);
  private shut = new Int32Array(this.cap);
  private hc = new Float32Array(this.cap);
  private kc = new Float32Array(this.cap);
  private hs = new Int32Array(this.cap);
  private gen = 0;
  private heap = new Heap(1 << 16);
  constructor(private G: Ground) {}

  /** A route from [s] to [e] (cell units, e in s's frame) in the corridor of atlas cells within [margin] of [guide], its
   * moves' grades at most [gmax]; null if none. */
  route(s: Pt, e: Pt, guide: Pt[], margin: number, gmax: number, bridgeM: number): Pt[] | null {
    const G = this.G;
    let x0 = Math.min(s[0], e[0]), x1 = Math.max(s[0], e[0]), z0 = Math.min(s[1], e[1]), z1 = Math.max(s[1], e[1]);
    for (const [x, z] of guide) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); }
    const amin = Math.floor((x0 - margin - 1 - s[0]) * XF), amax = Math.ceil((x1 + margin + 1 - s[0]) * XF);
    const bmin = Math.floor((z0 - margin - 1 - s[1]) * XF), bmax = Math.ceil((z1 + margin + 1 - s[1]) * XF);
    const nx = amax - amin + 1, nz = bmax - bmin + 1, size = nx * nz;
    if (size > 12_000_000) return null;
    if (size > this.cap) {
      while (this.cap < size) this.cap *= 2;
      this.g = new Float64Array(this.cap); this.from = new Int32Array(this.cap); this.seen = new Int32Array(this.cap); this.shut = new Int32Array(this.cap);
      this.hc = new Float32Array(this.cap); this.kc = new Float32Array(this.cap); this.hs = new Int32Array(this.cap); this.gen = 0;
    }
    const id = ++this.gen;
    const { g, from, seen, shut, hc, kc, hs, heap } = this;
    // the corridor mask over the box's atlas cells
    const ci0 = Math.floor(s[0] + amin / XF) - 1, cj0 = Math.floor(s[1] + bmin / XF) - 1;
    const cw = Math.ceil(nx / XF) + 3, chh = Math.ceil(nz / XF) + 3;
    const mask = new Uint8Array(cw * chh);
    const r = Math.ceil(margin);
    const dense: Pt[] = [s];
    for (const q of [...guide, e]) { const p = dense[dense.length - 1]!; const n = Math.max(1, Math.ceil(dist(p, q) * 2)); for (let k = 1; k <= n; k++) dense.push([p[0] + ((q[0] - p[0]) * k) / n, p[1] + ((q[1] - p[1]) * k) / n]); }
    for (const [x, z] of dense) {
      const i0 = Math.floor(x) - ci0, j0 = Math.floor(z) - cj0;
      for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) { const i = i0 + di, j = j0 + dj; if (i >= 0 && j >= 0 && i < cw && j < chh && di * di + dj * dj <= (margin + 0.75) ** 2) mask[j * cw + i] = 1; }
    }
    const px = (a: number): number => s[0] + a / XF, pz = (b: number): number => s[1] + b / XF;
    const ix = (a: number, b: number): number => (b - bmin) * nx + (a - amin);
    const look = (a: number, b: number): number => {
      const i = ix(a, b);
      if (hs[i] !== id) {
        hs[i] = id;
        const x = px(a), z = pz(b);
        const mi = Math.floor(x) - ci0, mj = Math.floor(z) - cj0;
        const ok = mi >= 0 && mj >= 0 && mi < cw && mj < chh && mask[mj * cw + mi] === 1 && G.open(x, z);
        hc[i] = ok ? G.height(x, z) : 0;
        kc[i] = ok ? G.townCost(x, z) : -1;
      }
      return i;
    };
    const ea = Math.round((e[0] - s[0]) * XF), eb = Math.round((e[1] - s[1]) * XF);
    const s0 = look(0, 0);
    kc[s0] = Math.max(0, kc[s0]!);
    { const ie = look(ea, eb); kc[ie] = Math.max(0, kc[ie]!); }
    heap.clear();
    g[s0] = 0; from[s0] = -1; seen[s0] = id;
    const hd = (a: number, b: number): number => Math.hypot(ea - a, eb - b) * XSTEP_M;
    heap.push(hd(0, 0) * 1.3, s0);
    let goal = -1, pops = 0;
    while (heap.size) {
      const i = heap.pop();
      if (shut[i] === id) continue;
      shut[i] = id;
      const a = (i % nx) + amin, b = Math.floor(i / nx) + bmin;
      if (a === ea && b === eb) { goal = i; break; }
      if (++pops > 2_500_000) break;
      const gi = g[i]!, ha = hc[i]!;
      const pa: Pt = [px(a), pz(b)];
      for (const [da, db, L] of XMOVES) {
        const a2 = a + da, b2 = b + db;
        if (a2 < amin || a2 > amax || b2 < bmin || b2 > bmax) continue;
        const j = look(a2, b2);
        if (shut[j] === id || kc[j]! < 0) continue;
        let gr = Math.abs(hc[j]! - ha) / L;
        if (L > XSTEP_M * 1.5) { const hm = G.height(px(a + da / 2), pz(b + db / 2)); gr = Math.max(gr, Math.abs(hm - ha) / (L / 2), Math.abs(hc[j]! - hm) / (L / 2)); }
        if (gr > gmax) continue;
        const q = gr / gmax;
        let cost = L * (1 + 3 * q * q + kc[j]!);
        const pb: Pt = [px(a2), pz(b2)];
        const xs = G.crossings(pa, pb);
        for (const x of xs) cost += bridgeM * (G.flows[x.line]! >= G.bigFlow ? 2.5 : 1);
        const ng = gi + cost;
        if (seen[j] !== id || ng < g[j]!) { seen[j] = id; g[j] = ng; from[j] = i; heap.push(ng + hd(a2, b2) * 1.3, j); }
      }
    }
    if (goal < 0) return null;
    const out: Pt[] = [];
    for (let i = goal; i >= 0; i = from[i]!) out.push([px((i % nx) + amin), pz(Math.floor(i / nx) + bmin)]);
    out.reverse();
    out[0] = [s[0], s[1]];
    out[out.length - 1] = [e[0], e[1]];
    return out;
  }
}

/** Pulls a routed line straight: from each point, the farthest later point reachable on a chord that stays open, under
 * [gmax] (sampled every 60 m), off the towns' cores and over no more rivers than the route between. */
function pull(G: Ground, line: Pt[], gmax: number): Pt[] {
  const n = line.length;
  if (n <= 2) return line;
  const cross = new Int32Array(n);
  for (let i = 1; i < n; i++) cross[i] = cross[i - 1]! + G.crossings(line[i - 1]!, line[i]!).length;
  const ok = (i: number, j: number): boolean => {
    const a = line[i]!, b = line[j]!;
    const L = dist(a, b) * CELL_M, k = Math.max(1, Math.ceil(L / 60));
    let prev = G.height(a[0], a[1]);
    for (let q = 1; q <= k; q++) {
      const x = a[0] + ((b[0] - a[0]) * q) / k, z = a[1] + ((b[1] - a[1]) * q) / k;
      if (q < k && (!G.open(x, z) || G.townCost(x, z) >= 6)) return false;
      const e = G.height(x, z);
      if (Math.abs(e - prev) / (L / k) > gmax * 1.1) return false;
      prev = e;
    }
    return G.crossings(a, b).length <= cross[j]! - cross[i]!;
  };
  const out: Pt[] = [line[0]!];
  let i = 0;
  while (i < n - 1) {
    let best = i + 1, step = 2;
    let j = i + step;
    while (j < n && ok(i, j)) { best = j; step *= 2; j = i + step; }
    if (j >= n && best < n - 1 && ok(i, n - 1)) best = n - 1;
    else {
      let lo = best, hi = Math.min(j, n - 1);
      while (hi - lo > 1) { const m = (lo + hi) >> 1; if (ok(i, m)) lo = m; else hi = m; }
      best = lo;
    }
    out.push(line[best]!);
    i = best;
  }
  return out;
}

/** Rounds every corner of a polyline with an arc of radius [rM] (smaller where the legs are short), every 4 degrees. */
function fillet(line: Pt[], rM: number): Pt[] {
  if (line.length < 3) return line;
  const out: Pt[] = [line[0]!];
  const R0 = rM / CELL_M;
  for (let i = 1; i < line.length - 1; i++) {
    const a = line[i - 1]!, b = line[i]!, c = line[i + 1]!;
    const [ux, uz] = unit(b[0] - a[0], b[1] - a[1]), [vx, vz] = unit(c[0] - b[0], c[1] - b[1]);
    const cos = Math.max(-1, Math.min(1, ux * vx + uz * vz)), th = Math.acos(cos);
    if (th < 0.01) { out.push(b); continue; }
    const la = dist(a, b) * (i === 1 ? 1 : 0.5), lc = dist(b, c) * (i === line.length - 2 ? 1 : 0.5);
    let T = R0 * Math.tan(th / 2);
    T = Math.min(T, la * 0.98, lc * 0.98);
    const R = T / Math.tan(th / 2);
    const p0: Pt = [b[0] - ux * T, b[1] - uz * T];
    // the arc's centre: to the turn's side
    const side = ux * vz - uz * vx > 0 ? 1 : -1;
    const cx = p0[0] - uz * R * side, cz = p0[1] + ux * R * side;
    const steps = Math.max(1, Math.ceil(th / ((4 * Math.PI) / 180)));
    const a0 = Math.atan2(p0[1] - cz, p0[0] - cx);
    for (let k = 0; k <= steps; k++) { const ang = a0 + side * (th * k) / steps; out.push([cx + Math.cos(ang) * R, cz + Math.sin(ang) * R]); }
  }
  out.push(line[line.length - 1]!);
  const d: Pt[] = [];
  for (const p of out) if (!d.length || dist(d[d.length - 1]!, p) > 1e-6) d.push(p);
  return d;
}

// ------------------------------------------------------------------------------------------------ the network

/** A place's weight in the system's planning (its traffic). */
export const weightOf = (p: Place): number => (p.kind === 'hub' ? (p.type === 'capital' ? 100 : 40) : p.rank === 'major' ? 12 : p.rank === 'town' ? 3 : p.kind === 'hamlet' ? 0.2 : 1);

interface Edge { a: number; b: number; d: number; road: number; line?: Pt[]; year?: number; corridor?: number; relaxed?: boolean; segIds?: string[]; lanes?: Step<number>[]; divided?: number }
interface Corridor { id: number; mass: number; nodes: number[]; edges: number[]; busy: number; start: number; dir: 'NS' | 'EW'; route?: RouteNo; tolled: boolean }
interface Ring { place: Place; rx: number; rz: number; anchors: (Pt | null)[]; radials: Map<number, { j: Pt; road: number; line?: Pt[]; year?: number }>; year?: number }
interface XLine { id: number; kind: 'expressway' | 'beltway' | 'radial'; line: Pt[]; cum: Float64Array; year: number; lanes: Step<number>[]; divided?: number; mass: number; edge?: number; ring?: Ring; route?: string; number?: string; graphRoad?: Road }
interface Junction { id: string; kind: InterchangeKind; line: number; s: number; at: Pt; prio: number; road?: number; place?: string; cell: number; other?: number; year: number; /** two lines running on into each other: a seam, no interchange */ pass?: boolean }

export function makeHighways(t: Terrain, h: Hydro, places: readonly Place[], roads: Road[]): Highways {
  const t0 = Date.now();
  const seed = t.seed;
  const G = new Ground(t, h, places);
  const router = new Router(G);
  const hr = (...k: (string | number)[]): number => hashU(hashStr(`${seed}|hwy2|${k.join("|")}`), 0);
  const byId = new Map(places.map((p) => [p.id, p]));
  const stats: HighwayStats = { corridors: 0, edges: 0, unrouted: 0, relaxed: 0, beltways: 0, radials: 0, interchanges: {}, realignments: 0, highwayBypasses: 0, bypassed: 0, expresswayKm: 0, tunnels: 0, ms: 0 };
  const roadIdx = new Map(roads.map((r, k) => [r.id, k]));
  const pairRoad = new Map<string, number>();
  roads.forEach((r, k) => { if (r.cls === 'highway' && r.b) { pairRoad.set(`${r.a}|${r.b}`, k); pairRoad.set(`${r.b}|${r.a}`, k); } });
  /** A road's line oriented from place [from]. */
  const lineFrom = (k: number, from: string): Pt[] => { const p = roads[k]!.path!; return roads[k]!.a === from ? p : [...p].reverse(); };

  // ---- 1. the plan: per landmass, a spanning tree of the highways' Gabriel graph of hubs and major towns, plus the links
  // the tree makes very roundabout between big places; chained into straight-through corridors
  const nodesByMass = new Map<number, Place[]>();
  for (const p of places) if (p.kind === 'hub' || (p.kind === 'town' && p.rank === 'major')) { const l = nodesByMass.get(p.mass); if (l) l.push(p); else nodesByMass.set(p.mass, [p]); }
  const nodes: Place[] = [];
  const edges: Edge[] = [];
  const corridors: Corridor[] = [];
  const massOfNode: number[] = [];
  for (const [mass, ps] of [...nodesByMass].sort((a, b) => a[0] - b[0])) {
    if (ps.length < 2) continue;
    const base = nodes.length;
    nodes.push(...ps);
    for (const _ of ps) massOfNode.push(mass);
    const cand = gabriel(ps).map(([a, b]) => ({ a: base + a, b: base + b, d: distKm(ps[a]!.x, ps[a]!.z, ps[b]!.x, ps[b]!.z), road: pairRoad.get(`${ps[a]!.id}|${ps[b]!.id}`) ?? -1 }))
      .filter((e) => e.road >= 0).sort((p, q) => p.d - q.d || p.a - q.a || p.b - q.b);
    // Kruskal
    const parent = new Map<number, number>();
    const find = (x: number): number => { let r = x; while (parent.has(r) && parent.get(r) !== r) r = parent.get(r)!; return r; };
    const chosen: typeof cand = [], rest: typeof cand = [];
    for (const e of cand) { const ra = find(e.a), rb = find(e.b); if (ra !== rb) { parent.set(ra, rb); parent.set(rb, rb); chosen.push(e); } else rest.push(e); }
    // extra links: where the tree's way is over 1.5 times the direct one between big enough places
    const treeKm = (s: number, g: number, es: typeof cand): number => {
      const dd = new Map<number, number>([[s, 0]]), todo = [s];
      while (todo.length) { const c = todo.shift()!; for (const e of es) { const o = e.a === c ? e.b : e.b === c ? e.a : -1; if (o < 0 || dd.has(o)) continue; dd.set(o, dd.get(c)! + e.d); todo.push(o); } }
      return dd.get(g) ?? Infinity;
    };
    const extras = rest.map((e) => ({ e, score: (weightOf(nodes[e.a]!) * weightOf(nodes[e.b]!)) / (e.d * e.d) }))
      .filter(({ e }) => treeKm(e.a, e.b, chosen) > 1.5 * e.d).sort((p, q) => q.score - p.score || p.e.a - q.e.a).slice(0, Math.ceil(ps.length / 4));
    for (const { e } of extras) chosen.push(e);
    const first = edges.length;
    for (const e of chosen) edges.push({ ...e });
    // chain into corridors: at each node, pair its edges straightest-first (an angle over 120 degrees)
    const mine = edges.slice(first).map((_, k) => first + k);
    const pairOf = new Map<string, number>(); // `${edge}|${node}` -> the edge it continues into
    const dirOf = (ei: number, at: number): [number, number] => { const e = edges[ei]!, o = nodes[e.a === at ? e.b : e.a]!, p = nodes[at]!; return unit(wrapDx(o.x - p.x), o.z - p.z); };
    for (let n = base; n < nodes.length; n++) {
      const inc = mine.filter((ei) => edges[ei]!.a === n || edges[ei]!.b === n);
      const pairs: [number, number, number][] = [];
      for (let i = 0; i < inc.length; i++) for (let j = i + 1; j < inc.length; j++) { const u = dirOf(inc[i]!, n), v = dirOf(inc[j]!, n); const dot = u[0] * v[0] + u[1] * v[1]; if (dot < -0.5) pairs.push([dot, inc[i]!, inc[j]!]); }
      pairs.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
      const used = new Set<number>();
      for (const [, i, j] of pairs) { if (used.has(i) || used.has(j)) continue; used.add(i); used.add(j); pairOf.set(`${i}|${n}`, j); pairOf.set(`${j}|${n}`, i); }
    }
    const done = new Set<number>();
    const walk = (start: number, from: number): Corridor => {
      const cn = [from], ce: number[] = [];
      let ei = start, at = from;
      while (!done.has(ei)) {
        done.add(ei); ce.push(ei);
        const e = edges[ei]!; at = e.a === at ? e.b : e.a; cn.push(at);
        const nx = pairOf.get(`${ei}|${at}`);
        if (nx === undefined) break;
        ei = nx;
      }
      return { id: corridors.length, mass, nodes: cn, edges: ce, busy: 0, start: 0, dir: 'EW', tolled: false };
    };
    // corridors start at an unpaired end; then any loop left
    for (const ei of mine) for (const end of [edges[ei]!.a, edges[ei]!.b]) if (!done.has(ei) && !pairOf.has(`${ei}|${end}`)) corridors.push(walk(ei, end));
    for (const ei of mine) if (!done.has(ei)) corridors.push(walk(ei, edges[ei]!.a));
  }
  // busy: the places a corridor serves (its nodes, and the towns within 8 km of its straight legs)
  const townsByMass = new Map<number, Place[]>();
  for (const p of places) if (p.kind === 'town') { const l = townsByMass.get(p.mass); if (l) l.push(p); else townsByMass.set(p.mass, [p]); }
  for (const c of corridors) {
    c.busy = c.nodes.reduce((s, n) => s + weightOf(nodes[n]!), 0);
    for (const p of townsByMass.get(c.mass) ?? []) {
      if (c.nodes.some((n) => nodes[n] === p)) continue;
      for (let k = 1; k < c.nodes.length; k++) {
        const A = nodes[c.nodes[k - 1]!]!, Bn = nodes[c.nodes[k]!]!;
        const ax = A.x, bx = A.x + wrapDx(Bn.x - A.x), px = A.x + wrapDx(p.x - A.x);
        const dx = bx - ax, dz = Bn.z - A.z, l2 = dx * dx + dz * dz;
        const u = Math.max(0, Math.min(1, ((px - ax) * dx + (p.z - A.z) * dz) / l2));
        if (Math.hypot(px - ax - dx * u, p.z - A.z - dz * u) * CELL_KM < 8) { c.busy += weightOf(p) * 0.5; break; }
      }
    }
    const f = nodes[c.nodes[0]!]!, l = nodes[c.nodes[c.nodes.length - 1]!]!;
    let sx = Math.abs(wrapDx(l.x - f.x)), sz = Math.abs(l.z - f.z);
    if (c.nodes[0] === c.nodes[c.nodes.length - 1]) { const xs = c.nodes.map((n) => wrapDx(nodes[n]!.x - f.x)), zs = c.nodes.map((n) => nodes[n]!.z); sx = Math.max(...xs) - Math.min(...xs); sz = Math.max(...zs) - Math.min(...zs); }
    c.dir = sx >= sz ? 'EW' : 'NS';
    // (exits and the route's places read west to east, north to south)
    const flip = c.dir === 'EW' ? wrapDx(l.x - f.x) < 0 : l.z < f.z;
    if (flip) { c.nodes.reverse(); c.edges.reverse(); }
    c.tolled = hr('toll', c.id) < 0.2;
  }
  stats.corridors = corridors.length;
  // stages: corridors by busy, 1956 to 1978; within one, its legs busiest first, a year or three apart, all by 1985
  const order = [...corridors].sort((p, q) => q.busy - p.busy || p.id - q.id);
  order.forEach((c, k) => { c.start = SYSTEM_FROM + Math.round(22 * Math.pow(order.length > 1 ? k / (order.length - 1) : 0, 0.9)); });
  for (const c of corridors) {
    const legs = [...c.edges].sort((p, q) => (weightOf(nodes[edges[q]!.a]!) + weightOf(nodes[edges[q]!.b]!)) - (weightOf(nodes[edges[p]!.a]!) + weightOf(nodes[edges[p]!.b]!)) || p - q);
    const step = legs.length > 1 ? Math.max(1, Math.min(3, (SYSTEM_TO - c.start) / (legs.length - 1))) : 0;
    legs.forEach((ei, k) => { const e = edges[ei]!; e.corridor = c.id; e.year = Math.min(SYSTEM_TO, c.start + Math.round(k * step + hr('leg', ei))); });
  }
  const busyRank = new Map(order.map((c, k) => [c.id, order.length > 1 ? k / (order.length - 1) : 0]));

  // ---- 2. anchors: beltway rings round the hubs and the junction towns (three corridor legs or more), radials from the
  // ring to an interchange near the town's edge on the old highway; a through town's expressway passes 2.2 km off its
  // centre, a terminal town's ends on its highway 2.5 km out
  const degree = new Map<number, number>();
  for (const e of edges) for (const n of [e.a, e.b]) degree.set(n, (degree.get(n) ?? 0) + 1);
  const rings = new Map<number, Ring>();
  nodes.forEach((p, n) => {
    if (!degree.get(n) || (!p.hub && degree.get(n)! < 3)) return;
    const rx = p.hub ? (p.hub.padM.w / 2 + p.hub.growthM + 1000) / CELL_M : 3 / CELL_KM, rz = p.hub ? (p.hub.padM.h / 2 + p.hub.growthM + 1000) / CELL_M : 3 / CELL_KM;
    const anchors: (Pt | null)[] = [];
    for (let k = 0; k < 16; k++) { const a = (k * Math.PI) / 8; const q: Pt = [p.x + Math.cos(a) * rx, p.z + Math.sin(a) * rz]; anchors.push(G.open(q[0], q[1], 300) && G.townCost(q[0], q[1]) < 6 ? q : null); }
    rings.set(n, { place: p, rx, rz, anchors, radials: new Map() });
  });
  /** The ring anchor for highway road [k] leaving ring [R]: its radial (made on first use). */
  const radialFor = (R: Ring, k: number): number | null => {
    const line = lineFrom(k, R.place.id), cum = cumOf(line);
    const j = pointAt(line, cum, R.place.hub ? 1.0 / CELL_KM : 1.3 / CELL_KM).p;
    const phi = Math.atan2((j[1] - R.place.z) / R.rz, wrapDx(j[0] - R.place.x) / R.rx);
    let best = -1, bd = Infinity;
    R.anchors.forEach((q, i) => { if (!q) return; let d = Math.abs(phi - (i * Math.PI) / 8) % (2 * Math.PI); if (d > Math.PI) d = 2 * Math.PI - d; if (d < bd) { bd = d; best = i; } });
    if (best < 0) return null;
    if (!R.radials.has(best)) R.radials.set(best, { j, road: k });
    return best;
  };
  const anchorOf = (n: number, ei: number): { at: Pt; ring?: Ring; anchor?: number; terminal?: { j: Pt; road: number } } | null => {
    const e = edges[ei]!, p = nodes[n]!;
    const R = rings.get(n);
    if (R) { const a = radialFor(R, e.road); return a === null ? null : { at: R.anchors[a]!, ring: R, anchor: a }; }
    const inc = edges.filter((q) => q.a === n || q.b === n);
    if (inc.length === 1) {
      const line = lineFrom(e.road, p.id), cum = cumOf(line);
      const j = pointAt(line, cum, Math.min(2.5 / CELL_KM, cum[cum.length - 1]! / 3)).p;
      return { at: j, terminal: { j, road: e.road } };
    }
    // a through town: off its centre, square to the corridor
    const o = inc.map((q) => { const m = nodes[q.a === n ? q.b : q.a]!; return unit(wrapDx(m.x - p.x), m.z - p.z); });
    const tdir = unit(o[0]![0] - o[1]![0], o[0]![1] - o[1]![1]);
    const side = hr('side', p.id) < 0.5 ? 1 : -1;
    for (const km of [2.2, 1.8, 2.8, 3.4]) for (const sd of [side, -side]) {
      const q: Pt = [p.x - tdir[1] * sd * (km / CELL_KM), p.z + tdir[0] * sd * (km / CELL_KM)];
      if (G.open(q[0], q[1], 300) && G.townCost(q[0], q[1]) < 1.2) return { at: q };
    }
    return { at: [p.x, p.z] };
  };

  // ---- 3. route the legs
  const xlines: XLine[] = [];
  const RELAX: [number, number][] = [[3, XGRADE], [6, XGRADE], [10, XGRADE], [10, XGRADE * 1.4], [12, XGRADE * 2]];
  const routeLine = (s: Pt, e: Pt, guide: Pt[], relax: [number, number][], rM: number): { line: Pt[]; relaxed: boolean; ok: boolean } => {
    const e2 = shiftTo(e, s[0]);
    const gd = guide.map((q) => shiftTo(q, s[0]));
    for (let k = 1; k < gd.length; k++) gd[k] = shiftTo(gd[k]!, gd[k - 1]![0]);
    for (const [margin, gm] of relax) {
      const r = router.route(s, e2, gd, margin, gm, 700);
      if (r) return { line: fillet(pull(G, r, gm), rM), relaxed: gm > XGRADE, ok: true };
    }
    return { line: [s, ...gd, e2], relaxed: true, ok: false };
  };
  const ends = new Map<number, ReturnType<typeof anchorOf>[]>();
  edges.forEach((e, ei) => {
    const A = anchorOf(e.a, ei), Bq = anchorOf(e.b, ei);
    if (!A || !Bq) { stats.unrouted++; return; }
    ends.set(ei, [A, Bq]);
    const guide = roads[e.road]!.cells.map((c) => [(c % W) + 0.5, Math.floor(c / W) + 0.5] as Pt);
    if (roads[e.road]!.a !== nodes[e.a]!.id) guide.reverse();
    const r = routeLine(A.at, Bq.at, guide, RELAX, 1800);
    if (!r.ok) stats.unrouted++;
    if (r.relaxed) stats.relaxed++;
    e.line = r.line; e.relaxed = r.relaxed;
    const c = corridors[e.corridor!]!, f = busyRank.get(c.id)!;
    // lanes: the busiest fifth opens with four and gets six; the quietest third opens as a two-lane "super two" and is
    // twinned a decade or so later; the rest open with four
    const y = e.year!;
    const lanes: Step<number>[] = f > 0.66 ? [[y, 2], [y + 8 + Math.floor(hr('twin', ei) * 9), 4]] : [[y, 4]];
    if (f < 0.2) lanes.push([Math.max(y + 8, 1972 + Math.floor(hr('six', ei) * 16)), 6]);
    e.lanes = lanes; e.divided = lanes.find(([, n]) => n >= 4)![0];
    xlines.push({ id: xlines.length, kind: 'expressway', line: e.line, cum: cumOf(e.line), year: y, lanes, divided: e.divided, mass: massOfNode[e.a]!, edge: ei });
  });
  stats.edges = edges.filter((e) => e.line).length;
  // radials: built with the first leg that uses them
  for (const [n, R] of rings) {
    for (const [a, rad] of R.radials) {
      const users = edges.map((e, ei) => ({ e, ei, en: ends.get(ei) })).filter(({ e, en }) => en && ((e.a === n && en[0]!.anchor === a) || (e.b === n && en[1]!.anchor === a)));
      if (!users.length) continue;
      rad.year = Math.min(...users.map(({ e }) => e.year!));
      const road = lineFrom(rad.road, R.place.id), cum = cumOf(road);
      const near = nearestOn(road, cum, rad.j);
      const outS = Math.min(cum[cum.length - 1]!, near.s + (Math.hypot(R.rx, R.rz) * 0.9));
      const guide = sub(road, cum, near.s, outS);
      const r = routeLine(rad.j, R.anchors[a]!, guide, [[1.5, XGRADE], [3, XGRADE], [3, XGRADE * 1.4], [4, XGRADE * 2]], 900);
      rad.line = r.line;
      const lanes = users.map(({ e }) => e.lanes!).sort((p, q) => q.length - p.length)[0]!;
      const l4: Step<number>[] = [[rad.year, 4]];
      const six = lanes.find(([, k]) => k === 6);
      if (six) l4.push([Math.max(rad.year, six[0]), 6]);
      xlines.push({ id: xlines.length, kind: 'radial', line: r.line, cum: cumOf(r.line), year: rad.year, lanes: l4, divided: rad.year, mass: R.place.mass, ring: R });
      stats.radials++;
    }
  }
  // beltways: the longest run of open anchors round the ring (closed when all are), 1960s-70s, after the first radial
  for (const [, R] of rings) {
    if (R.radials.size < 2 && !(R.place.hub && R.radials.size >= 1)) continue;
    const ys = [...R.radials.values()].map((q) => q.year).filter((y): y is number => y !== undefined);
    if (!ys.length) continue;
    const okA = R.anchors.map((q) => q !== null);
    let run: number[] = [];
    if (okA.every(Boolean)) run = [...Array.from({ length: 16 }, (_, k) => k), 0];
    else {
      let best: number[] = [];
      for (let s = 0; s < 16; s++) {
        if (!okA[s] || okA[(s + 15) % 16]) continue;
        const r: number[] = [];
        for (let k = 0; k < 16 && okA[(s + k) % 16]; k++) r.push((s + k) % 16);
        if (r.length > best.length) best = r;
      }
      run = best;
    }
    if (run.length < 3 || run.filter((a) => R.radials.get(a)?.year !== undefined).length < (R.place.hub ? 1 : 2)) continue;
    const cap = R.place.type === 'capital';
    R.year = Math.min(1979, Math.max(Math.min(...ys) + 3, cap ? 1961 + Math.floor(hr('belt', R.place.id) * 5) : R.place.hub ? 1963 + Math.floor(hr('belt', R.place.id) * 14) : 1967 + Math.floor(hr('belt', R.place.id) * 11)));
    const lanes: Step<number>[] = [[R.year, 4]];
    if (cap || (R.place.hub && hr('belt6', R.place.id) < 0.4)) lanes.push([1984 + Math.floor(hr('belt6y', R.place.id) * 14), 6]);
    for (let k = 1; k < run.length; k++) {
      const a = run[k - 1]!, b = run[k]!;
      const A = R.anchors[a]!, Bq = R.anchors[b]!;
      const guide: Pt[] = [];
      const t0a = (a * Math.PI) / 8, t1a = b === 0 && a === 15 ? 2 * Math.PI : (b * Math.PI) / 8;
      for (let q = 1; q < 4; q++) { const ang = t0a + ((t1a - t0a) * q) / 4; guide.push([R.place.x + Math.cos(ang) * R.rx, R.place.z + Math.sin(ang) * R.rz]); }
      const r = routeLine(A, Bq, guide, [[1.2, XGRADE], [2.5, XGRADE], [2.5, XGRADE * 1.4], [3.5, XGRADE * 2]], 900);
      xlines.push({ id: xlines.length, kind: 'beltway', line: r.line, cum: cumOf(r.line), year: R.year, lanes, divided: R.year, mass: R.place.mass, ring: R });
    }
    stats.beltways++;
  }

  // ---- 4. numbers. Pennant trunks: north-south odd, west to east; east-west even, north to south; by position across the
  // landmass. Loops: an even hundred over the lowest trunk at the ring; spurs (radials): an odd hundred over theirs.
  const routes: RouteNo[] = [];
  const massSpan = new Map<number, { x0: number; x1: number; z0: number; z1: number; ref: number }>();
  for (const p of places) {
    const m = t.masses[p.mass - 1];
    const ref = m ? m.cx : p.x;
    const s = massSpan.get(p.mass) ?? { x0: Infinity, x1: -Infinity, z0: Infinity, z1: -Infinity, ref };
    const x = wrapDx(p.x - s.ref);
    s.x0 = Math.min(s.x0, x); s.x1 = Math.max(s.x1, x); s.z0 = Math.min(s.z0, p.z); s.z1 = Math.max(s.z1, p.z);
    massSpan.set(p.mass, s);
  }
  /** Numbers for items by direction: NS odd by mean x, EW even by mean z, spread over [1, 2 * top - 1], strictly rising. */
  const number = <T>(items: T[], mass: number, dirOf: (x: T) => 'NS' | 'EW', posOf: (x: T) => { x: number; z: number }, top: number): Map<T, number> => {
    const out = new Map<T, number>();
    const s = massSpan.get(mass)!;
    for (const dir of ['NS', 'EW'] as const) {
      const xs = items.filter((q) => dirOf(q) === dir).map((q) => { const p = posOf(q); return { q, f: dir === 'NS' ? (wrapDx(p.x - s.ref) - s.x0) / Math.max(1, s.x1 - s.x0) : (p.z - s.z0) / Math.max(1, s.z1 - s.z0) }; })
        .sort((a, b) => a.f - b.f);
      let prev = dir === 'NS' ? -1 : 0;
      for (const { q, f } of xs) {
        let n = dir === 'NS' ? 1 + 2 * Math.floor(Math.max(0, Math.min(0.999, f)) * top) : 2 + 2 * Math.floor(Math.max(0, Math.min(0.999, f)) * top);
        n = Math.max(n, prev + 2);
        out.set(q, n); prev = n;
      }
    }
    return out;
  };
  const meanPos = (ns: number[]): { x: number; z: number } => { const p0 = nodes[ns[0]!]!; let sx = 0, sz = 0; for (const n of ns) { sx += wrapDx(nodes[n]!.x - p0.x); sz += nodes[n]!.z; } return { x: p0.x + sx / ns.length, z: sz / ns.length }; };
  const byMassC = new Map<number, Corridor[]>();
  for (const c of corridors) if (c.edges.some((ei) => edges[ei]!.line)) { const l = byMassC.get(c.mass); if (l) l.push(c); else byMassC.set(c.mass, [c]); }
  const pennant = new Map<number, Set<number>>();
  for (const [mass, cs] of byMassC) {
    const nums = number(cs, mass, (c) => c.dir, (c) => meanPos(c.nodes), 49);
    const used = new Set<number>();
    for (const c of cs) {
      const n = nums.get(c)!;
      used.add(n);
      c.route = { id: `P${n}@${mass}`, system: 'pennant', n, kind: 'trunk', dir: c.dir, designation: `P${n}`, mass, from: Math.min(...c.edges.map((ei) => edges[ei]!.year!)), places: c.nodes.map((k) => nodes[k]!.id) };
      routes.push(c.route);
    }
    pennant.set(mass, used);
  }
  const corrOfEdge = (ei: number): Corridor => corridors[edges[ei]!.corridor!]!;
  for (const x of xlines) if (x.kind === 'expressway') { const c = corrOfEdge(x.edge!); x.route = c.route!.id; x.number = c.route!.designation; }
  const nextFree = (mass: number, base: number, odd: boolean): number => {
    const used = pennant.get(mass)!;
    for (let hh = odd ? 1 : 2; hh <= 9; hh += 2) { const n = hh * 100 + base; if (!used.has(n)) { used.add(n); return n; } }
    let n = 1000 + base; while (used.has(n)) n += 100; used.add(n); return n;
  };
  for (const [n, R] of rings) {
    const legs = edges.map((e, ei) => ({ e, ei })).filter(({ e, ei }) => e.line && (e.a === n || e.b === n) && ends.get(ei));
    if (!legs.length) continue;
    const parentOf = (filter: (ei: number) => boolean): RouteNo | undefined => legs.filter(({ ei }) => filter(ei)).map(({ ei }) => corrOfEdge(ei).route!).sort((p, q) => p.n - q.n)[0];
    const parent = parentOf(() => true)!;
    if (R.year !== undefined) {
      const k = nextFree(R.place.mass, parent.n, false);
      const route: RouteNo = { id: `P${k}@${R.place.mass}`, system: 'pennant', n: k, kind: 'loop', dir: 'ring', designation: `P${k}`, mass: R.place.mass, parent: parent.id, from: R.year, places: [R.place.id] };
      routes.push(route);
      for (const x of xlines) if (x.kind === 'beltway' && x.ring === R) { x.route = route.id; x.number = route.designation; }
    }
    for (const x of xlines) {
      if (x.kind !== 'radial' || x.ring !== R) continue;
      const a = R.anchors.findIndex((q) => q && dist(q, x.line[x.line.length - 1]!) < 1e-6);
      const par = parentOf((ei) => { const en = ends.get(ei)!; return (edges[ei]!.a === n && en[0]!.anchor === a) || (edges[ei]!.b === n && en[1]!.anchor === a); }) ?? parent;
      const k = nextFree(R.place.mass, par.n, true);
      const route: RouteNo = { id: `P${k}@${R.place.mass}`, system: 'pennant', n: k, kind: 'spur', dir: par.dir, designation: `P${k}`, mass: R.place.mass, parent: par.id, from: x.year, places: [R.place.id] };
      routes.push(route);
      x.route = route.id; x.number = route.designation;
    }
  }

  // ---- 5. graph roads for the expressways (limited access: joined only at their junctions)
  const xIndex = new LineIndex();
  for (const x of xlines) xIndex.add(x.line);
  const oldIndex = new LineIndex();
  const oldLineRoad: number[] = [];
  roads.forEach((r, k) => { oldIndex.add(r.path!); oldLineRoad.push(k); });
  const nearestCellOf = (k: number, p: Pt): number => {
    let best = roads[k]!.cells[0]!, bd = Infinity;
    for (const c of roads[k]!.cells) { const d = Math.hypot(wrapDx((c % W) + 0.5 - p[0]), Math.floor(c / W) + 0.5 - p[1]); if (d < bd) { bd = d; best = c; } }
    return best;
  };

  // ---- 6. interchanges: system (rings, expressways crossing), terminal (an expressway's end on a highway), service (the
  // highways crossed, county roads to towns, and every town within 6 km), merged where closer than 1.5 km
  const junctions: Junction[] = [];
  let jn = 0;
  const addJ = (j: Omit<Junction, 'id'>): Junction => { const q = { ...j, id: `x:${jn++}` }; junctions.push(q); return q; };
  const cellAt = (p: Pt): number => G.cell(p[0], p[1]);
  for (const x of xlines) {
    const L = x.cum[x.cum.length - 1]!;
    if (x.kind === 'radial') {
      // its outer end on the ring (a system interchange once the beltway is there, or where two legs meet it); its inner
      // end on the old highway (a terminal: a trumpet)
      const R = x.ring!;
      const k = [...R.radials.values()].find((q) => q.line === x.line)!.road;
      addJ({ kind: 'terminal', line: x.id, s: 0, at: x.line[0]!, prio: 3, road: k, cell: nearestCellOf(k, x.line[0]!), year: x.year });
      addJ({ kind: 'system', line: x.id, s: L, at: x.line[x.line.length - 1]!, prio: 3, cell: cellAt(x.line[x.line.length - 1]!), year: Math.max(x.year, R.year ?? x.year) });
    }
    if (x.kind === 'expressway') {
      const en = ends.get(x.edge!)!;
      en.forEach((q, side) => {
        const s = side ? L : 0, at = side ? x.line[x.line.length - 1]! : x.line[0]!;
        if (q!.terminal) addJ({ kind: 'terminal', line: x.id, s, at, prio: 3, road: q!.terminal.road, cell: nearestCellOf(q!.terminal.road, at), year: x.year });
        else addJ({ kind: 'system', line: x.id, s, at, prio: 3, cell: cellAt(at), year: x.year });
      });
    }
    if (x.kind === 'beltway') {
      addJ({ kind: 'system', line: x.id, s: 0, at: x.line[0]!, prio: 3, cell: cellAt(x.line[0]!), year: x.year });
      addJ({ kind: 'system', line: x.id, s: L, at: x.line[x.line.length - 1]!, prio: 3, cell: cellAt(x.line[x.line.length - 1]!), year: x.year });
    }
  }
  // crossings of the old roads and of other expressways
  for (const x of xlines) {
    const L = x.cum[x.cum.length - 1]!;
    for (let i = 1; i < x.line.length; i++) {
      const a = x.line[i - 1]!, b = x.line[i]!;
      for (const c of oldIndex.crossings(a, b)) {
        const s = x.cum[i - 1]! + dist(a, b) * c.t;
        if (s < 0.8 / CELL_KM || s > L - 0.8 / CELL_KM) continue;
        const k = oldLineRoad[c.line]!, r = roads[k]!;
        const at: Pt = [a[0] + (b[0] - a[0]) * c.t, a[1] + (b[1] - a[1]) * c.t];
        const pl = byId.get(r.a);
        const serve = r.cls === 'highway' || (pl !== undefined && (pl.kind === 'hub' || pl.rank !== 'small'));
        if (serve) addJ({ kind: 'service', line: x.id, s, at, prio: r.cls === 'highway' ? 1.5 : 1, road: k, cell: nearestCellOf(k, at), year: x.year });
      }
      for (const c of xIndex.crossings(a, b, (l) => l === x.id)) {
        if (c.line < x.id) continue;
        const s = x.cum[i - 1]! + dist(a, b) * c.t;
        const o = xlines[c.line]!;
        const at: Pt = [a[0] + (b[0] - a[0]) * c.t, a[1] + (b[1] - a[1]) * c.t];
        const so = nearestOn(o.line, o.cum, at).s;
        if (s < 0.5 / CELL_KM || s > L - 0.5 / CELL_KM || so < 0.5 / CELL_KM || so > o.cum[o.cum.length - 1]! - 0.5 / CELL_KM) continue;
        const cell = cellAt(at), year = Math.max(x.year, o.year);
        const j1 = addJ({ kind: 'system', line: x.id, s, at, prio: 3, cell, year });
        const j2 = addJ({ kind: 'system', line: o.id, s: so, at, prio: 3, cell, year });
        j1.other = o.id; j2.other = x.id;
      }
    }
  }
  // where only two lines meet end to end (a leg and a radial before the beltway, two legs at a through town, two pieces of a
  // beltway) the road just runs on: a seam in the graph, no interchange
  for (const j of junctions) {
    if (j.kind !== 'system' || j.other !== undefined) continue;
    const lines = new Set(junctions.filter((q) => q.kind === 'system' && dist(q.at, j.at) < 1e-6).map((q) => q.line));
    if (lines.size < 3) j.pass = true;
  }
  const merge = (): void => {
    const byLine = new Map<number, Junction[]>();
    for (const j of junctions) { const l = byLine.get(j.line); if (l) l.push(j); else byLine.set(j.line, [j]); }
    const drop = new Set<Junction>();
    for (const [, js] of byLine) {
      const sorted = [...js].sort((p, q) => q.prio - p.prio || p.s - q.s || p.id.localeCompare(q.id));
      const kept: Junction[] = [];
      for (const j of sorted) {
        if (j.prio < 3 && kept.some((k) => !k.pass && Math.abs(k.s - j.s) < 1.5 / CELL_KM)) { drop.add(j); continue; }
        kept.push(j);
      }
    }
    for (let i = junctions.length - 1; i >= 0; i--) if (drop.has(junctions[i]!)) junctions.splice(i, 1);
  };
  merge();
  // every town within 6 km of an expressway gets an interchange (its own, or one within 3.5 km of its nearest point)
  const townJ = new Map<string, Junction>();
  for (const p of places) {
    if (p.kind !== 'town') continue;
    let best: { x: XLine; d: number; s: number; at: Pt } | null = null;
    const refs = xIndex.near(p.x, p.z, 6 / CELL_KM + 1);
    const lines = new Set([...refs].map((r) => Math.floor(r / 1_000_000)));
    for (const li of [...lines].sort((a, b) => a - b)) {
      const x = xlines[li]!;
      const q = nearestOn(x.line, x.cum, [p.x, p.z]);
      if (q.d * CELL_KM <= 6 && (!best || q.d < best.d)) best = { x, d: q.d, s: q.s, at: q.at };
    }
    if (!best) continue;
    const dLine = best.d * CELL_KM;
    const near = junctions.filter((j) => !j.pass).map((j) => ({ j, d: distKm(j.at[0], j.at[1], p.x, p.z) })).filter((q) => q.d <= Math.min(6.5, dLine + 2.5)).sort((a, b) => a.d - b.d || a.j.id.localeCompare(b.j.id))[0];
    if (near) { townJ.set(p.id, near.j); if (!near.j.place) near.j.place = p.id; continue; }
    const L = best.x.cum[best.x.cum.length - 1]!;
    const s = Math.max(0.3 / CELL_KM, Math.min(L - 0.3 / CELL_KM, best.s));
    const at = pointAt(best.x.line, best.x.cum, s).p;
    townJ.set(p.id, addJ({ kind: 'service', line: best.x.id, s, at, prio: 2, place: p.id, cell: cellAt(at), year: best.x.year }));
  }
  merge();
  for (const [id, j] of townJ) if (!junctions.includes(j)) {
    // (merged away: the nearest kept one on its line)
    const k = junctions.filter((q) => q.line === j.line && !q.pass).sort((a, b) => Math.abs(a.s - j.s) - Math.abs(b.s - j.s) || a.id.localeCompare(b.id))[0];
    if (k) townJ.set(id, k); else townJ.delete(id);
  }

  // ---- 7. interchanges as data: one per place (the two ends of a system crossing are one interchange), design by era
  const segs: Seg[] = [];
  const interchanges: Interchange[] = [];
  const ixOf = new Map<Junction, Interchange>();
  const typeOf = (kind: InterchangeKind, year: number, busy: boolean, key: string): { type: string; rebuilt?: Step<string> } => {
    const r = hr('ix', key);
    if (kind === 'terminal') return { type: 'trumpet' };
    if (kind === 'junction') return { type: 'at-grade' };
    if (kind === 'system') {
      if (year < 1966) return { type: 'cloverleaf', ...(r < 0.6 ? { rebuilt: [1986 + Math.floor(r * 30), 'directional'] as Step<string> } : {}) };
      return { type: year < 1980 ? 'directional' : 'stack' };
    }
    if (year < 1962 || (busy && year < 1966)) return { type: 'cloverleaf', ...(r < 0.4 ? { rebuilt: [1984 + Math.floor(r * 50), 'partial cloverleaf'] as Step<string> } : {}) };
    if (year < 1990) return { type: r < 0.65 ? 'diamond' : 'partial cloverleaf' };
    return { type: r < 0.5 ? 'single-point' : 'diamond' };
  };
  for (const j of junctions) {
    if (j.pass) continue;
    // a system junction where another line's junction is at the same point is the same interchange
    const same = [...ixOf.entries()].find(([q]) => q.kind === 'system' && j.kind === 'system' && dist(q.at, j.at) < 1e-6);
    if (same) { ixOf.set(j, same[1]); same[1].year = Math.max(same[1].year, j.year); continue; }
    const busy = j.road !== undefined && roads[j.road]!.cls === 'highway';
    const ty = typeOf(j.kind, j.year, busy, j.id);
    const ic: Interchange = { id: `i:${interchanges.length}`, kind: j.kind, ...ty, year: j.year, x: wrapX(j.at[0]), z: j.at[1], mass: xlines[j.line]!.mass, segs: [], ...(j.road !== undefined ? { road: roads[j.road]!.id } : {}), ...(j.place ? { place: j.place } : {}), exits: {} };
    interchanges.push(ic);
    ixOf.set(j, ic);
  }
  // (a system interchange's year is when its last line opened, its design that year's)
  for (const ic of interchanges) if (ic.kind === 'system') { const ty = typeOf('system', ic.year, true, ic.id); ic.type = ty.type; if (ty.rebuilt) ic.rebuilt = ty.rebuilt; else delete ic.rebuilt; }
  // a system interchange on a ring before its beltway (two legs meeting, or a leg and a radial) is no interchange at all:
  // the road runs on; it becomes one in the beltway's year (kept, with that year)

  // ---- 8. the expressway segments: each line cut at its junctions
  const segOfLine = new Map<number, Seg[]>();
  for (const x of xlines) {
    const L = x.cum[x.cum.length - 1]!;
    const cuts = [...new Set([0, L, ...junctions.filter((j) => j.line === x.id).map((j) => j.s)])].sort((a, b) => a - b);
    const list: Seg[] = [];
    for (let k = 1; k < cuts.length; k++) {
      if (cuts[k]! - cuts[k - 1]! < 1e-6) continue;
      const path = sub(x.line, x.cum, cuts[k - 1]!, cuts[k]!);
      const prof = profileOf(path, XGRADE, (a, b) => G.height(a, b));
      const structures = structuresOf(prof);
      stats.tunnels += structures.filter((q) => q.kind === 'tunnel').length;
      let mg = 0;
      for (let i = 1; i < prof.s.length; i++) mg = Math.max(mg, Math.abs(prof.z[i]! - prof.z[i - 1]!) / Math.max(1e-9, prof.s[i]! - prof.s[i - 1]!));
      const crossings: Crossing[] = [];
      for (let i = 1; i < path.length; i++) for (const c of G.crossings(path[i - 1]!, path[i]!)) {
        const a = path[i - 1]!, b = path[i]!;
        crossings.push({ at: [a[0] + (b[0] - a[0]) * c.t, a[1] + (b[1] - a[1]) * c.t], flow: Math.round(G.flows[c.line]!), bridge: x.year });
      }
      const km = lineLen(path) * CELL_KM;
      stats.expresswayKm += km;
      const s: Seg = {
        id: `${x.kind === 'expressway' ? 'xe' : x.kind === 'beltway' ? 'xb' : 'xr'}:${x.id}.${list.length}`, kind: x.kind, mass: x.mass, path, km: Math.round(km * 100) / 100, built: x.year,
        cls: [[x.year, 'expressway']], surface: [[x.year, 'concrete']], lanes: x.lanes, ...(x.divided !== undefined ? { divided: x.divided } : {}),
        number: [[x.year, x.number ?? null]], ...(x.route ? { route: x.route } : {}), crossings, structures, maxGrade: Math.round(mg * 10000) / 10000,
        ...(x.kind === 'radial' ? { road: roads[[...x.ring!.radials.values()].find((q) => q.line === x.line)!.road]!.id } : x.kind === 'expressway' ? { road: roads[edges[x.edge!]!.road]!.id } : {}),
      };
      list.push(s); segs.push(s);
    }
    segOfLine.set(x.id, list);
    // the line's graph road: its atlas cells, its junctions' cells put in where they are
    const js = junctions.filter((j) => j.line === x.id).sort((a, b) => a.s - b.s);
    const entries: [number, number, number][] = js.map((j) => [j.s, 0, j.cell]);
    const n = Math.max(2, Math.ceil(L * 2));
    for (let k = 0; k <= n; k++) { const s = (L * k) / n; if (js.some((j) => Math.abs(j.s - s) < 0.25)) continue; entries.push([s, 1, cellAt(pointAt(x.line, x.cum, s).p)]); }
    entries.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
    const cells: number[] = [];
    for (const [, , c] of entries) if (cells[cells.length - 1] !== c) cells.push(c);
    let cellKm = 0;
    for (let q = 1; q < cells.length; q++) cellKm += Math.hypot(wrapDx((cells[q]! % W) - (cells[q - 1]! % W)), Math.floor(cells[q]! / W) - Math.floor(cells[q - 1]! / W)) * CELL_KM;
    x.graphRoad = { id: `x:${x.id}`, cls: 'expressway', a: x.route ?? `x:${x.id}`, b: null, cells, km: Math.round(L * CELL_KM * 10) / 10, cellKm: Math.max(0.01, cellKm), built: x.year, paved: x.year, limited: true, junctions: [...new Set(js.map((j) => j.cell))], lanesBy: x.lanes };
  }
  for (const j of junctions) { const ic = ixOf.get(j); if (!ic) continue; for (const s of segOfLine.get(j.line)!) if (s.path.some((p) => dist(p, j.at) < 1e-6) && !ic.segs.includes(s.id)) ic.segs.push(s.id); }

  // exits: km from the route's start (a trunk's west or north end, a loop's first anchor, a spur's inner end)
  for (const c of corridors) {
    if (!c.route) continue;
    let base = 0, at = c.nodes[0]!;
    c.edges.forEach((ei) => {
      const e = edges[ei]!;
      const fwd = e.a === at;
      if (!e.line) { at = fwd ? e.b : e.a; return; }
      const x = xlines.find((q) => q.edge === ei)!;
      const L = x.cum[x.cum.length - 1]!;
      for (const j of junctions.filter((q) => q.line === x.id)) {
        const km = base + (fwd ? j.s : L - j.s) * CELL_KM;
        const ic = ixOf.get(j);
        if (!ic) continue;
        if (ic.exits[c.route!.designation] === undefined) ic.exits[c.route!.designation] = `${Math.max(1, Math.round(km))}`;
      }
      base += L * CELL_KM;
      at = fwd ? e.b : e.a;
    });
    dedupeExits(interchanges, c.route.designation);
  }
  for (const route of routes.filter((q) => q.kind !== 'trunk')) {
    let base = 0;
    for (const x of xlines.filter((q) => q.route === route.id)) {
      for (const j of junctions.filter((q) => q.line === x.id)) { const ic = ixOf.get(j); if (ic && ic.exits[route.designation] === undefined) ic.exits[route.designation] = `${Math.max(1, Math.round(base + j.s * CELL_KM))}`; }
      base += x.cum[x.cum.length - 1]! * CELL_KM;
    }
    dedupeExits(interchanges, route.designation);
  }

  // ---- 9. connectors: a town's interchange to the town
  const links: Road[] = xlines.map((x) => x.graphRoad!);
  const connectorFor = new Map<string, Seg>();
  for (const [pid, j] of townJ) {
    const p = byId.get(pid)!, ic = ixOf.get(j)!;
    if (ic.kind !== 'service' || ic.place !== pid) continue;
    const at: Pt = j.at, to: Pt = shiftTo([p.x, p.z], at[0]);
    const d = dist(at, to) * CELL_KM;
    if (d < 0.3) continue;
    const path: Pt[] = [at, to];
    const s: Seg = { id: `xc:${segs.length}`, kind: 'connector', mass: p.mass, path, km: Math.round(d * 100) / 100, built: ic.year, cls: [[ic.year, 'highway']], surface: [[ic.year, 'paved']], lanes: [[ic.year, 2]], number: [[ic.year, null]], crossings: [], structures: [], maxGrade: Math.round((Math.abs(G.height(to[0], to[1]) - G.height(at[0], at[1])) / (d * 1000)) * 10000) / 10000 };
    segs.push(s); connectorFor.set(pid, s);
    const cells = [j.cell, p.cell];
    links.push({ id: s.id, cls: 'county', a: pid, b: null, cells, km: Math.max(0.1, Math.round(d * 10) / 10), cellKm: Math.max(0.01, Math.hypot(wrapDx((p.cell % W) - (j.cell % W)), Math.floor(p.cell / W) - Math.floor(j.cell / W)) * CELL_KM), built: ic.year, paved: ic.year, opened: ic.year });
  }

  // ---- 10. the old roads' lives: realignments in the 1930s, bypasses round towns, widening, numbers, decommissioning
  const paralleled = new Map<number, number>(); // old highway -> the year an expressway leg on its corridor opened
  for (const e of edges) if (e.line) paralleled.set(e.road, Math.min(paralleled.get(e.road) ?? 9999, e.year!));
  const xNearKm = (x: number, z: number, km: number): { d: number; line: XLine } | null => {
    let best: { d: number; line: XLine } | null = null;
    const lines = new Set([...xIndex.near(x, z, km / CELL_KM + 1)].map((r) => Math.floor(r / 1_000_000)));
    for (const li of [...lines].sort((a, b) => a - b)) { const xl = xlines[li]!; const q = nearestOn(xl.line, xl.cum, [x, z]); const d = q.d * CELL_KM; if (d <= km && (!best || d < best.d)) best = { d, line: xl }; }
    return best;
  };
  // national highway numbers (1927): chains of highways through their places, paired straightest-first
  const hwys = roads.map((r, k) => ({ r, k })).filter(({ r }) => r.cls === 'highway');
  const dirAt = (k: number, from: string): [number, number] => { const l = lineFrom(k, from), c = cumOf(l); const q = pointAt(l, c, Math.min(c[c.length - 1]!, 6 / CELL_KM)).p; return unit(q[0] - l[0]![0], q[1] - l[0]![1]); };
  const hpair = new Map<string, number>();
  const atPlace = new Map<string, number[]>();
  for (const { r, k } of hwys) for (const pid of [r.a, r.b!]) { const l = atPlace.get(pid); if (l) l.push(k); else atPlace.set(pid, [k]); }
  for (const [pid, ks] of [...atPlace].sort((a, b) => a[0].localeCompare(b[0]))) {
    const pairs: [number, number, number][] = [];
    for (let i = 0; i < ks.length; i++) for (let j = i + 1; j < ks.length; j++) { const u = dirAt(ks[i]!, pid), v = dirAt(ks[j]!, pid), dot = u[0] * v[0] + u[1] * v[1]; if (dot < -0.5) pairs.push([dot, ks[i]!, ks[j]!]); }
    pairs.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
    const used = new Set<number>();
    for (const [, i, j] of pairs) { if (used.has(i) || used.has(j)) continue; used.add(i); used.add(j); hpair.set(`${i}|${pid}`, j); hpair.set(`${j}|${pid}`, i); }
  }
  const hdone = new Set<number>();
  const chains: { mass: number; roads: number[]; places: string[] }[] = [];
  const hwalk = (k0: number, from: string): void => {
    const rs: number[] = [], ps = [from];
    let k = k0, at = from;
    while (!hdone.has(k)) { hdone.add(k); rs.push(k); const r = roads[k]!; at = r.a === at ? r.b! : r.a; ps.push(at); const nx = hpair.get(`${k}|${at}`); if (nx === undefined) break; k = nx; }
    chains.push({ mass: byId.get(from)!.mass, roads: rs, places: ps });
  };
  for (const { r, k } of hwys) for (const end of [r.a, r.b!]) if (!hdone.has(k) && !hpair.has(`${k}|${end}`)) hwalk(k, end);
  for (const { r, k } of hwys) if (!hdone.has(k)) hwalk(k, r.a);
  const hnum = new Map<number, string>(); // old road -> its national number
  const hroute = new Map<number, RouteNo>();
  const chainsByMass = new Map<number, typeof chains>();
  for (const c of chains) { const l = chainsByMass.get(c.mass); if (l) l.push(c); else chainsByMass.set(c.mass, [c]); }
  for (const [mass, cs] of chainsByMass) {
    const pos = (c: (typeof chains)[number]): { x: number; z: number } => { const ps = c.places.map((id) => byId.get(id)!); let sx = 0, sz = 0; for (const p of ps) { sx += wrapDx(p.x - ps[0]!.x); sz += p.z; } return { x: ps[0]!.x + sx / ps.length, z: sz / ps.length }; };
    const dir = (c: (typeof chains)[number]): 'NS' | 'EW' => { const a = byId.get(c.places[0]!)!, b = byId.get(c.places[c.places.length - 1]!)!; const ps = c.places.map((id) => byId.get(id)!); const xs = ps.map((p) => wrapDx(p.x - a.x)), zs = ps.map((p) => p.z); const sx = c.places[0] === c.places[c.places.length - 1] ? Math.max(...xs) - Math.min(...xs) : Math.abs(wrapDx(b.x - a.x)); const sz = c.places[0] === c.places[c.places.length - 1] ? Math.max(...zs) - Math.min(...zs) : Math.abs(b.z - a.z); return sx >= sz ? 'EW' : 'NS'; };
    const nums = number(cs, mass, dir, pos, 99);
    for (const c of cs) {
      const n = nums.get(c)!, d = dir(c);
      const from = Math.max(NUMBERING_YEAR, Math.min(...c.roads.map((k) => roads[k]!.built)));
      const route: RouteNo = { id: `N${n}@${mass}`, system: 'national', n, kind: 'trunk', dir: d, designation: `N${n}`, mass, from, places: c.places };
      routes.push(route);
      for (const k of c.roads) { hnum.set(k, route.designation); hroute.set(k, route); }
    }
  }
  // county numbers (from paving), per landmass by position
  const countyNo = new Map<number, string>();
  {
    const byM = new Map<number, number[]>();
    roads.forEach((r, k) => { if (r.cls !== 'county') return; const m = byId.get(r.a)?.mass ?? 0; const l = byM.get(m); if (l) l.push(k); else byM.set(m, [k]); });
    for (const [, ks] of byM) {
      const ord = ks.map((k) => ({ k, p: byId.get(roads[k]!.a)! })).sort((a, b) => a.p.z - b.p.z || a.p.x - b.p.x || a.k - b.k);
      ord.forEach(({ k }, i) => countyNo.set(k, `C${i + 1}`));
    }
  }
  const business = new Set<string>();
  const businessRoute = (k: number): string => {
    const base = hnum.get(k)!, d = `${base}B`;
    if (!business.has(`${d}@${hroute.get(k)!.mass}`)) { business.add(`${d}@${hroute.get(k)!.mass}`); routes.push({ id: `${d}@${hroute.get(k)!.mass}`, system: 'national', n: hroute.get(k)!.n, kind: 'business', dir: hroute.get(k)!.dir, designation: d, mass: hroute.get(k)!.mass, parent: hroute.get(k)!.id, from: 9999, places: [] }); }
    return d;
  };
  const touchRoute = (d: string, mass: number, year: number): void => { const r = routes.find((q) => q.id === `${d}@${mass}`); if (r && year < r.from) r.from = year; };

  // the towns each highway's line runs through (within 0.5 km of its line)
  const throughTowns = (k: number): { p: Place; s: number }[] => {
    const r = roads[k]!, line = r.path!, cum = cumOf(line), out: { p: Place; s: number }[] = [];
    const L = cum[cum.length - 1]!;
    for (const p of townsByMass.get(r.cls === 'highway' ? byId.get(r.a)!.mass : -1) ?? []) {
      const q = nearestOn(line, cum, [p.x, p.z]);
      if (q.d * CELL_KM <= 0.5 && q.s > 2 / CELL_KM && q.s < L - 2 / CELL_KM) out.push({ p, s: q.s });
    }
    return out.sort((a, b) => a.s - b.s);
  };
  const towns = new Map<string, TownRoads>();
  const townRec = (pid: string): TownRoads => { let t2 = towns.get(pid); if (!t2) { t2 = { place: pid }; towns.set(pid, t2); } return t2; };
  const windowsOf = new Map<number, { i0: number; i1: number; kind: 'realign' | 'bypass'; year: number; line: Pt[]; town?: string }[]>();

  for (const r of roads) if (r.cls === 'highway') for (const pid of [r.a, r.b!]) { const p = byId.get(pid)!; if (p.kind === 'town') townRec(pid).mainStreet ??= r.id; }
  roads.forEach((r, k) => {
    if (r.cls !== 'highway') return;
    const line = r.path!, cum = cumOf(line), L = cum[cum.length - 1]!;
    const wins: { i0: number; i1: number; kind: 'realign' | 'bypass'; year: number; line: Pt[]; town?: string }[] = [];
    const free = (i0: number, i1: number): boolean => wins.every((w) => i1 < w.i0 - 1 || i0 > w.i1 + 1);
    // bypasses (1948-68) round the towns on highways no expressway came near
    for (const { p, s } of throughTowns(k)) {
      townRec(p.id).mainStreet ??= r.id;
      if (xNearKm(p.x, p.z, 8)) continue;
      if (hr('byp', r.id, p.id) > (p.rank === 'major' ? 0.6 : 0.3)) continue;
      const R = 1.7 / CELL_KM;
      let i0 = nearestOn(line, cum, [p.x, p.z]).i, i1 = i0;
      while (i0 > 0 && distKm(line[i0]![0], line[i0]![1], p.x, p.z) < 1.6) i0--;
      while (i1 < line.length - 1 && distKm(line[i1]![0], line[i1]![1], p.x, p.z) < 1.6) i1++;
      if (i0 === 0 || i1 === line.length - 1 || !free(i0, i1) || cum[i0]! < 1.5 / CELL_KM || cum[i1]! > L - 1.5 / CELL_KM) continue;
      const a = line[i0]!, b = line[i1]!, cx = a[0] + wrapDx(p.x - a[0]);
      const t0 = Math.atan2(a[1] - p.z, a[0] - cx), t1 = Math.atan2(b[1] - p.z, b[0] - cx);
      let best: Pt[] | null = null, bestG = Infinity;
      for (const sideDir of [1, -1]) {
        let d = t1 - t0; while (d <= 0) d += 2 * Math.PI; if (sideDir < 0) d -= 2 * Math.PI;
        const arc: Pt[] = [a];
        const steps = Math.max(4, Math.ceil(Math.abs(d) / 0.12));
        let okArc = true, g = 0, prev = G.height(a[0], a[1]), pp = a;
        for (let q = 1; q < steps; q++) {
          const ang = t0 + (d * q) / steps; const pt: Pt = [cx + Math.cos(ang) * R, p.z + Math.sin(ang) * R];
          if (!G.open(pt[0], pt[1])) { okArc = false; break; }
          const e = G.height(pt[0], pt[1]); g = Math.max(g, Math.abs(e - prev) / (dist(pp, pt) * CELL_M)); prev = e; pp = pt; arc.push(pt);
        }
        arc.push(b);
        if (okArc && g < bestG && g <= 0.07) { bestG = g; best = arc; }
      }
      if (!best) continue;
      wins.push({ i0, i1, kind: 'bypass', year: 1948 + Math.floor(hr('bypy', r.id, p.id) * 21), line: best, town: p.id });
    }
    // 1930s realignments: the worst curves straightened (a stretch 1.2-6 km long at least 1.18 times its chord, the chord
    // under 7% on the ground or a modest cut), 2.5 km clear of the ends and 6 km of every hub
    const i = { v: 0 };
    let tries = 0;
    while (i.v < line.length - 2 && tries++ < 400) {
      const i0 = i.v;
      if (cum[i0]! < 2.5 / CELL_KM) { i.v++; continue; }
      let best = -1, gain = 0;
      for (let j = i0 + 2; j < line.length && cum[j]! - cum[i0]! <= 6 / CELL_KM; j++) {
        if (cum[j]! > L - 2.5 / CELL_KM) break;
        const arc = cum[j]! - cum[i0]!, ch = Math.hypot(line[j]![0] - line[i0]![0], line[j]![1] - line[i0]![1]);
        if (arc < 1.2 / CELL_KM || arc < 1.18 * ch || arc - ch <= gain) continue;
        if (!free(i0, j)) continue;
        if (places.some((p) => p.hub && distKm(p.x, p.z, line[i0]![0], line[i0]![1]) < 6)) continue;
        // the chord: open land, under the grade (or within a cut of 15 m), no more river crossings than the arc
        const a = line[i0]!, b = line[j]!;
        const n = Math.max(2, Math.ceil((ch * CELL_M) / 60));
        let ok = true;
        for (let q = 1; q < n && ok; q++) { const x = a[0] + ((b[0] - a[0]) * q) / n, z = a[1] + ((b[1] - a[1]) * q) / n; if (!G.open(x, z)) ok = false; }
        if (!ok) continue;
        const prof = profileOf([a, b], 0.07, (x, z) => G.height(x, z));
        let deep = 0;
        for (let q = 0; q < prof.s.length; q++) deep = Math.max(deep, Math.abs(prof.z[q]! - prof.ground[q]!));
        if (deep > 15) continue;
        let arcX = 0;
        for (let q = i0 + 1; q <= j; q++) arcX += G.crossings(line[q - 1]!, line[q]!).length;
        if (G.crossings(a, b).length > arcX) continue;
        best = j; gain = arc - ch;
      }
      if (best < 0) { i.v++; continue; }
      if (hr('realign', r.id, i0) < 0.75 && wins.filter((w) => w.kind === 'realign').length < 4) {
        const yr = Math.max(r.paved, 1931 + Math.floor(hr('realy', r.id, i0) * 11));
        if (yr <= 1941) wins.push({ i0, i1: best, kind: 'realign', year: yr, line: resampleChord(line[i0]!, line[best]!) });
      }
      i.v = best;
    }
    wins.sort((p, q) => p.i0 - q.i0);
    if (wins.length) windowsOf.set(k, wins);
  });

  // decommissioning (1970s on): the old highways an expressway replaced keep their number, become business routes, or are
  // handed to the counties
  const decom = new Map<number, { year: number; fate: 'kept' | 'business' | 'county' }>();
  for (const [k, y] of paralleled) {
    const r = hr('decom', roads[k]!.id);
    const year = Math.min(2000, Math.max(1970, y + 4 + Math.floor(hr('decy', roads[k]!.id) * 12)));
    const hubEnd = byId.get(roads[k]!.a)!.kind === 'hub' || byId.get(roads[k]!.b!)!.kind === 'hub';
    decom.set(k, { year, fate: r < 0.4 ? 'kept' : r < 0.7 || hubEnd ? 'business' : 'county' });
  }
  // widening: the busy highways no expressway relieved early go to four lanes 1950-68 (the busiest divided)
  const hBusy = (k: number): number => weightOf(byId.get(roads[k]!.a)!) + weightOf(byId.get(roads[k]!.b!)!);
  const roadHist = new Map<number, { cls: Step<SegClass>[]; surface: Step<Surface>[]; lanes: Step<number>[]; number: Step<string | null>[]; divided?: number; built: number }>();
  roads.forEach((r, k) => {
    const pa = byId.get(r.a), pb = r.b ? byId.get(r.b) : undefined;
    const f = Math.max(pa?.founded ?? 1850, pb?.founded ?? 0);
    const track = Math.min(r.built - 1, Math.max(1840, f + 2));
    const cls: Step<SegClass>[] = [[track, 'track'], [r.built, r.cls === 'highway' ? 'highway' : 'county']];
    const surface: Step<Surface>[] = [[track, 'track'], [r.built, r.cls === 'highway' ? 'gravel' : 'dirt']];
    if (r.cls === 'county' && r.built + 10 < r.paved) surface.push([r.built + 10, 'gravel']);
    surface.push([r.paved, 'paved']);
    const lanes: Step<number>[] = [[track, 1]];
    if (r.cls === 'highway') lanes.push([r.built, 2]); else lanes.push([Math.min(r.built + 10, r.paved), 2]);
    const number: Step<string | null>[] = [[track, null]];
    let divided: number | undefined;
    if (r.cls === 'highway') {
      number.push([Math.max(NUMBERING_YEAR, r.built), hnum.get(k) ?? null]);
      const busy = hBusy(k);
      const px = paralleled.get(k) ?? 9999;
      if (busy >= 24 || (busy >= 13 && hr('widen', r.id) < 0.5)) {
        const wy = 1950 + Math.floor(hr('wideny', r.id) * 19);
        if (wy < px - 2) { lanes.push([wy, 4]); if (busy >= 40) divided = wy + 4 + Math.floor(hr('div', r.id) * 8); }
      }
      const d = decom.get(k);
      if (d && d.fate === 'business') number.push([d.year, businessRoute(k)]);
      if (d && d.fate === 'county') { cls.push([d.year, 'county']); number.push([d.year, countyNo.get(k) ?? `C${k}`]); r.downgraded = d.year; }
    } else number.push([r.paved, countyNo.get(k) ?? null]);
    roadHist.set(k, { cls, surface, lanes, number, ...(divided !== undefined ? { divided } : {}), built: track });
  });
  for (const [k, d] of decom) if (d.fate === 'business') touchRoute(`${hnum.get(k)}B`, hroute.get(k)!.mass, d.year);
  /** Steps from [year] on (the state at [year] first). */
  const from = <T>(st: Step<T>[], year: number): Step<T>[] => { const v = valueAt(st, year); return [[year, v as T], ...st.filter(([y]) => y > year)]; };
  const until = <T>(st: Step<T>[], year: number, then: T): Step<T>[] => [...st.filter(([y]) => y < year), [year, then]];
  const crossingsOf = (line: Pt[], built: number, paved: number): Crossing[] => {
    const out: Crossing[] = [];
    for (let i = 1; i < line.length; i++) for (const c of G.crossings(line[i - 1]!, line[i]!)) {
      const a = line[i - 1]!, b = line[i]!, big = G.flows[c.line]! >= G.bigFlow;
      // a ford (a ferry on the big rivers) until the road was paved, a bridge since; a big river bridged when built if the
      // road was paved by then
      out.push({ at: [a[0] + (b[0] - a[0]) * c.t, a[1] + (b[1] - a[1]) * c.t], flow: Math.round(G.flows[c.line]!), bridge: big ? Math.max(built, Math.min(paved, 1925)) : paved, ford: built });
    }
    return out;
  };
  const gradeOfLine = (line: Pt[]): number => {
    let worst = 0;
    for (let i = 1; i < line.length; i++) { const a = line[i - 1]!, b = line[i]!, L = dist(a, b) * CELL_M; if (L > 1) worst = Math.max(worst, Math.abs(G.height(b[0], b[1]) - G.height(a[0], a[1])) / L); }
    return Math.round(worst * 10000) / 10000;
  };
  roads.forEach((r, k) => {
    const hist = roadHist.get(k)!;
    const line = r.path!;
    const wins = windowsOf.get(k) ?? [];
    const mass = byId.get(r.a)?.mass ?? 0;
    const piece = (i0: number, i1: number, extra: Partial<Seg>): Seg => {
      const path = line.slice(i0, i1 + 1).map((p) => [p[0], p[1]] as Pt);
      return {
        id: `o:${r.id}.${i0}`, kind: 'original', mass, road: r.id, range: [i0, i1], path, km: Math.round(lineLen(path) * CELL_KM * 100) / 100, built: hist.built,
        cls: hist.cls, surface: hist.surface, lanes: hist.lanes, ...(hist.divided !== undefined ? { divided: hist.divided } : {}), number: hist.number,
        ...(hroute.get(k) ? { route: hroute.get(k)!.id } : {}), crossings: crossingsOf(path, r.built, r.paved), structures: [], maxGrade: gradeOfLine(path), ...extra,
      };
    };
    let at = 0;
    for (const w of wins) {
      if (w.i0 > at) segs.push(piece(at, w.i0, {}));
      if (w.kind === 'realign') {
        stats.realignments++;
        // the old curvy piece: a local road from the realignment's year, unnumbered; some abandoned later
        const gone = hr('gone', r.id, w.i0) < 0.4 ? w.year + 12 + Math.floor(hr('goney', r.id, w.i0) * 40) : undefined;
        segs.push(piece(w.i0, w.i1, { cls: until(hist.cls, w.year, 'local'), number: until(hist.number, w.year, null), lanes: hist.lanes.filter(([y]) => y < w.year).length ? hist.lanes.filter(([y]) => y < w.year) : [[hist.built, 1]], ...(gone !== undefined && gone < 2030 ? { gone } : {}), divided: undefined }));
        const path = w.line;
        const prof = profileOf(path, 0.07, (x, z) => G.height(x, z));
        segs.push({
          id: `r:${r.id}.${w.i0}`, kind: 'realignment', mass, road: r.id, path, km: Math.round(lineLen(path) * CELL_KM * 100) / 100, built: w.year,
          cls: from(hist.cls, w.year), surface: from(hist.surface, w.year), lanes: from(hist.lanes, w.year), ...(hist.divided !== undefined ? { divided: hist.divided } : {}), number: from(hist.number, w.year),
          ...(hroute.get(k) ? { route: hroute.get(k)!.id } : {}), crossings: crossingsOf(path, w.year, w.year), structures: structuresOf(prof), maxGrade: gradeOfLine(path),
        });
        r.kmBy ??= [];
        r.kmBy.push([w.year, (lineLen(path) - (lineLen(line.slice(w.i0, w.i1 + 1)))) * CELL_KM]);
      } else {
        stats.highwayBypasses++;
        // the bypass takes the number; the old way through Main Street becomes the business route
        const b = businessRoute(k);
        touchRoute(b, hroute.get(k)!.mass, w.year);
        segs.push(piece(w.i0, w.i1, { number: [...hist.number.filter(([y]) => y < w.year), [w.year, b], ...hist.number.filter(([y]) => y > w.year).map(([y, v]) => [y, v === hnum.get(k) ? b : v] as Step<string | null>)] }));
        const path = w.line;
        const seg: Seg = {
          id: `b:${r.id}.${w.i0}`, kind: 'bypass', mass, road: r.id, path, km: Math.round(lineLen(path) * CELL_KM * 100) / 100, built: w.year,
          cls: from(hist.cls, w.year), surface: from(hist.surface, w.year), lanes: from(hist.lanes, w.year), number: from(hist.number, w.year),
          ...(hroute.get(k) ? { route: hroute.get(k)!.id } : {}), crossings: crossingsOf(path, w.year, w.year), structures: [], maxGrade: gradeOfLine(path),
        };
        segs.push(seg);
        // its two ends: at-grade junctions with the old route
        for (const end of [path[0]!, path[path.length - 1]!]) interchanges.push({ id: `i:${interchanges.length}`, kind: 'junction', type: 'at-grade', year: w.year, x: wrapX(end[0]), z: end[1], mass, segs: [seg.id], road: r.id, place: w.town!, exits: {} });
        const tr = townRec(w.town!);
        tr.bypassed = { year: w.year, by: 'highway', seg: seg.id };
        tr.interchange = interchanges[interchanges.length - 2]!.id;
      }
      at = w.i1;
    }
    segs.push(piece(at, line.length - 1, {}));
  });

  // ---- 11. the towns: bypassed by the expressway (the old highway through Main Street, an expressway within 6 km), their
  // interchanges
  const icById = new Map(interchanges.map((q) => [q.id, q]));
  for (const [pid, j] of townJ) {
    const ic = ixOf.get(j)!, p = byId.get(pid)!;
    const tr = townRec(pid);
    if (tr.bypassed?.by === 'highway') continue;
    tr.interchange = ic.id;
    tr.interchangeKm = Math.round(distKm(p.x, p.z, ic.x, ic.z) * 100) / 100;
    tr.interchangeYear = ic.year;
    if (tr.mainStreet) {
      const near = xNearKm(p.x, p.z, 6);
      if (near) tr.bypassed = { year: near.line.year, by: 'expressway', seg: segOfLine.get(near.line.id)![0]!.id };
    }
  }
  for (const [, tr] of towns) {
    if (!tr.interchange) continue;
    const ic = icById.get(tr.interchange) ?? interchanges.find((q) => q.id === tr.interchange)!;
    const p = byId.get(tr.place)!;
    tr.interchangeKm ??= Math.round(distKm(p.x, p.z, ic.x, ic.z) * 100) / 100;
    tr.interchangeYear ??= ic.year;
  }
  for (const [id, tr] of [...towns]) if (!tr.bypassed && !tr.interchange && !tr.mainStreet) towns.delete(id);
  stats.bypassed = [...towns.values()].filter((q) => q.bypassed).length;

  // ---- 12. frontage roads by the interchanges near towns
  for (const j of junctions) {
    const ic = ixOf.get(j);
    if (!ic || ic.kind !== 'service') continue;
    const x = xlines[j.line]!;
    const p = ic.place ? byId.get(ic.place) : undefined;
    if (!p || distKm(p.x, p.z, ic.x, ic.z) > 4) continue;
    const L = x.cum[x.cum.length - 1]!;
    const s0 = Math.max(0, j.s - 1.2 / CELL_KM), s1 = Math.min(L, j.s + 1.2 / CELL_KM);
    const base = sub(x.line, x.cum, s0, s1);
    const year = ic.year + Math.floor(hr('front', ic.id) * 10);
    for (const side of [1, -1]) {
      const off = offsetLine(base, (side * 75) / CELL_M);
      const km = lineLen(off) * CELL_KM;
      segs.push({ id: `f:${ic.id}.${side > 0 ? 'r' : 'l'}`, kind: 'frontage', mass: x.mass, path: off, km: Math.round(km * 100) / 100, built: year, cls: [[year, 'frontage']], surface: [[year, 'paved']], lanes: [[year, 2]], number: [[year, null]], crossings: [], structures: [], maxGrade: gradeOfLine(off) });
    }
  }

  // ---- 13. the roadside: rest areas every 60-95 km, weigh stations every 150-220 km, truck stops, motels and diners at
  // interchanges, toll plazas on the toll corridors
  const sites: Site[] = [];
  const site = (s: Omit<Site, 'id'>): void => { sites.push({ ...s, id: `s:${sites.length}` }); };
  for (const c of corridors) {
    if (!c.route) continue;
    let run = 0, nextRest = 30 + hr('rest0', c.id) * 40, nextWeigh = 60 + hr('weigh0', c.id) * 80;
    for (const ei of c.edges) {
      const x = xlines.find((q) => q.edge === ei);
      if (!x) continue;
      const L = x.cum[x.cum.length - 1]! * CELL_KM;
      for (let s = 0; s < L; s += 1) {
        run += 1;
        const at = pointAt(x.line, x.cum, s / CELL_KM).p;
        if (run >= nextRest && G.open(at[0], at[1], 600)) {
          const y = x.year + 1 + Math.floor(hr('resty', c.id, run) * 4);
          const to = hr('restc', c.id, run) < 0.2 ? 2003 + Math.floor(hr('restcy', c.id, run) * 13) : undefined;
          site({ kind: 'rest area', x: wrapX(at[0]), z: at[1], mass: c.mass, from: y, ...(to ? { to } : {}), seg: segOfLine.get(x.id)![0]!.id, models: !to || to > 1975 ? [{ from: Math.max(y, 1975), ...(to ? { to } : {}), id: 'landmark_glb_highway_rest_area' }] : [] });
          nextRest = run + 60 + hr('rest', c.id, run) * 35;
        }
        if (run >= nextWeigh && G.open(at[0], at[1], 600)) {
          site({ kind: 'weigh station', x: wrapX(at[0]), z: at[1], mass: c.mass, from: Math.max(x.year, 1962) + Math.floor(hr('weighy', c.id, run) * 7), seg: segOfLine.get(x.id)![0]!.id, models: [] });
          nextWeigh = run + 150 + hr('weigh', c.id, run) * 70;
        }
      }
    }
    if (c.tolled) for (const ei of [c.edges[0]!, c.edges[c.edges.length - 1]!]) {
      const x = xlines.find((q) => q.edge === ei);
      if (!x) continue;
      const at = pointAt(x.line, x.cum, Math.min(x.cum[x.cum.length - 1]! / 2, 3 / CELL_KM)).p;
      const to = hr('tollc', c.id, ei) < 0.4 ? 1985 + Math.floor(hr('tolly', c.id, ei) * 26) : undefined;
      if (!sites.some((q) => q.kind === 'toll plaza' && distKm(q.x, q.z, at[0], at[1]) < 1)) site({ kind: 'toll plaza', x: wrapX(at[0]), z: at[1], mass: c.mass, from: x.year, ...(to ? { to } : {}), seg: segOfLine.get(x.id)![0]!.id, models: [] });
    }
  }
  for (const ic of interchanges) {
    if (ic.kind !== 'service' && ic.kind !== 'terminal') continue;
    const nearTown = ic.place !== undefined || ic.road !== undefined;
    if (!nearTown) continue;
    const r1 = hr('truck', ic.id), r2 = hr('motel', ic.id), r3 = hr('diner', ic.id);
    const highway = ic.road !== undefined && roads[roadIdx.get(ic.road)!]!.cls === 'highway';
    if (r1 < (highway ? 0.45 : 0.2)) {
      const y = ic.year + 1 + Math.floor(hr('trucky', ic.id) * 5);
      const models = [{ from: Math.max(y, 1960), to: 1989, id: 'landmark_glb_truck_stop_1960' }, { from: Math.max(y, 1990), id: 'landmark_glb_truck_stop_1990' }].filter((m) => m.to === undefined || m.from <= m.to);
      site({ kind: 'truck stop', x: ic.x, z: ic.z, mass: ic.mass, from: y, interchange: ic.id, models });
    }
    if (r2 < 0.45) { const y = ic.year + 1 + Math.floor(hr('motely', ic.id) * 4); const to = hr('motelc', ic.id) < 0.3 ? 1985 + Math.floor(hr('motelcy', ic.id) * 28) : undefined; site({ kind: 'motel', x: ic.x, z: ic.z, mass: ic.mass, from: y, ...(to ? { to } : {}), interchange: ic.id, models: [] }); }
    if (r3 < 0.3) { const y = ic.year + Math.floor(hr('dinery', ic.id) * 6); site({ kind: 'diner', x: ic.x, z: ic.z, mass: ic.mass, from: y, interchange: ic.id, models: [] }); }
  }

  for (const ic of interchanges) stats.interchanges[ic.kind] = (stats.interchanges[ic.kind] ?? 0) + 1;
  stats.expresswayKm = Math.round(stats.expresswayKm);
  for (const r of routes) if (r.from === 9999) r.from = NUMBERING_YEAR;
  stats.ms = Date.now() - t0;
  void connectorFor;
  return { segs, interchanges, routes, sites, towns, links, stats };
}

function dedupeExits(ics: Interchange[], d: string): void {
  const seen = new Map<string, number>();
  const list = ics.filter((q) => q.exits[d] !== undefined).sort((a, b) => Number(a.exits[d]) - Number(b.exits[d]) || a.id.localeCompare(b.id));
  for (const ic of list) {
    const n = ic.exits[d]!;
    const k = seen.get(n) ?? 0;
    if (k) ic.exits[d] = `${n}${String.fromCharCode(64 + k)}`;
    seen.set(n, k + 1);
  }
}

function resampleChord(a: Pt, b: Pt): Pt[] {
  const n = Math.max(1, Math.ceil((dist(a, b) * CELL_M) / 250));
  const out: Pt[] = [];
  for (let k = 0; k <= n; k++) out.push([a[0] + ((b[0] - a[0]) * k) / n, a[1] + ((b[1] - a[1]) * k) / n]);
  return out;
}

function offsetLine(l: Pt[], d: number): Pt[] {
  return l.map((p, i) => {
    const a = l[Math.max(0, i - 1)]!, b = l[Math.min(l.length - 1, i + 1)]!;
    const [ux, uz] = unit(b[0] - a[0], b[1] - a[1]);
    return [p[0] - uz * d, p[1] + ux * d] as Pt;
  });
}
