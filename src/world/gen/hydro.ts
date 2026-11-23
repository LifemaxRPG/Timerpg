import { CELL_KM, CELL_KM2, H, Heap, N, W, components, nb } from './grid';
import { MONTHS, type Climate } from './climate';
import type { Terrain } from './terrain';

// RIVERS, LAKES AND COASTS (WORLD_GEN.md section 2.5). Priority-flood from the sea (x wraps) fills every pit, so every
// land cell has a path down to the sea; a cell's receiver is the cell the flood reached it from (the steepest way out
// on the filled surface); flow accumulates rain down the receivers. Rivers join, never split. A filled pit deep and big
// enough stays a lake (its surface the fill level, its outflow the spill point).

/** Flow (km2 of catchment times metres of rain a year) at which a cell is a river. */
export const RIVER_FLOW = 120;
/** A filled pit this deep (m) over this many cells or more stays a lake. */
export const LAKE_DEPTH = 20;
export const LAKE_CELLS = 8;

export interface Lake {
  id: number; cells: number; areaKm2: number; level: number; outlet: number; maxDepth: number;
  /** A closed basin in a dry climate: its inflow evaporates before it spills (a salt lake). */
  salt: boolean;
}
export interface RiverSeg {
  id: number;
  /** Cells from upstream to downstream; the last is where it joins (a river, a lake or the sea cell at the mouth). */
  cells: number[];
  /** Flow at the downstream end. */
  flow: number;
  /** Strahler-like order from the flow (1 small, up to 5 the trunks). */
  order: number;
  /** Where it ends: the sea, a lake, or another river. */
  end: 'sea' | 'lake' | 'river';
}

export interface Hydro {
  /** The flood's receiver of each land cell (a land or sea cell), -1 for sea. */
  recv: Int32Array;
  /** Filled surface (m). */
  filled: Float32Array;
  /** Flow accumulated (see RIVER_FLOW). */
  flow: Float32Array;
  /** How steady the flow is through the year: the driest month's flow over the mean month's (0 dries up, 1 steady). */
  steady: Float32Array;
  /** The month of the highest flow (0 January): the spring thaw or the rainy season. */
  peak: Uint8Array;
  /** Lake id per cell (0: none). */
  lake: Int32Array;
  lakes: Lake[];
  rivers: RiverSeg[];
  /** Coast kind per land cell beside the sea: 0 not coast, 1 sand, 2 rocky, 3 cliff. */
  coast: Uint8Array;
  /** Coast length per landmass (km, cell edges facing the sea). */
  coastKm: number[];
  /** Land cells in flood order (low first); downstream before upstream. */
  order: Int32Array;
}

export function makeHydro(t: Terrain, cl: Climate): Hydro {
  const { land, elev } = t;
  const recv = new Int32Array(N).fill(-1);
  const filled = Float32Array.from(elev);
  const seen = new Uint8Array(N);
  const heap = new Heap(1 << 18);
  for (let c = 0; c < N; c++) {
    if (!land[c]) { seen[c] = 1; continue; }
    let best = Infinity, bj = -1;
    for (let k = 0; k < 8; k++) {
      const m = nb(c, k);
      if (m >= 0 && !land[m] && elev[m]! < best) { best = elev[m]!; bj = m; }
    }
    if (bj >= 0) { recv[c] = bj; seen[c] = 1; heap.push(elev[c]!, c); }
  }
  const order = new Int32Array(N);
  let no = 0;
  while (heap.size) {
    const c = heap.pop();
    const e = heap.lastKey;
    order[no++] = c;
    for (let k = 0; k < 8; k++) {
      const m = nb(c, k);
      if (m < 0 || seen[m]) continue;
      seen[m] = 1;
      const fe = Math.max(elev[m]!, e + 0.01);
      filled[m] = fe;
      recv[m] = c;
      heap.push(fe, m);
    }
  }
  const ord = order.subarray(0, no);
  // flow: each month's runoff (rain less evaporation, snow held until the thaw) over each cell's area, down the
  // receivers (upstream first); the year's flow and how steady it is (the driest month's share of the mean)
  const flow = new Float32Array(N);
  const fmin = new Float32Array(N).fill(Infinity), fmax = new Float32Array(N);
  const peak = new Uint8Array(N);
  const snow = new Float32Array(N);
  const mflow = new Float32Array(N);
  for (let lap = 0; lap < 2; lap++) for (let m = 0; m < MONTHS; m++) {
    mflow.fill(0);
    for (let c = 0; c < N; c++) {
      if (!land[c]) continue;
      const T = cl.tempM[m * N + c]!, P = cl.rainM[m * N + c]!;
      let ro: number;
      if (T < -1) { snow[c] += P; ro = 0.05 * P; }
      else {
        const melt = T > 3 ? snow[c]! : snow[c]! * 0.5;
        snow[c] -= melt;
        const pet = Math.max(0, 4.8 * T);
        ro = Math.max(0, P - 0.75 * pet) + melt + 0.08 * Math.min(P, 0.75 * pet);
      }
      mflow[c] = (ro / 1000) * CELL_KM2;
    }
    if (!lap) continue;
    for (let q = no - 1; q >= 0; q--) {
      const c = ord[q]!, r = recv[c]!;
      if (r >= 0 && land[r]) mflow[r] += mflow[c]!;
    }
    for (let c = 0; c < N; c++) {
      if (!land[c]) continue;
      const v = mflow[c]!;
      flow[c] += v;
      if (v < fmin[c]!) fmin[c] = v;
      if (v > fmax[c]!) { fmax[c] = v; peak[c] = m; }
    }
  }
  const steady = new Float32Array(N);
  for (let c = 0; c < N; c++) steady[c] = land[c] && flow[c]! > 0 ? fmin[c]! / (flow[c]! / MONTHS) : 0;
  // lakes
  const deep = (c: number): boolean => land[c] === 1 && filled[c]! - elev[c]! > LAKE_DEPTH;
  const comp = components(deep);
  const lake = new Int32Array(N);
  const lakes: Lake[] = [];
  const keep = new Int32Array(comp.sizes.length);
  for (let l = 1; l < comp.sizes.length; l++) if (comp.sizes[l]! >= LAKE_CELLS) { lakes.push({ id: lakes.length + 1, cells: comp.sizes[l]!, areaKm2: Math.round(comp.sizes[l]! * CELL_KM2), level: 0, outlet: -1, maxDepth: 0, salt: false }); keep[l] = lakes.length; }
  for (let c = 0; c < N; c++) {
    const l = keep[comp.label[c]!]!;
    if (!l) continue;
    lake[c] = l;
    const lk = lakes[l - 1]!;
    lk.level = Math.max(lk.level, filled[c]!);
    lk.maxDepth = Math.max(lk.maxDepth, filled[c]! - elev[c]!);
  }
  // a lake's outlet: its cell whose receiver leaves the lake (the first in flood order: the spill point)
  for (let q = 0; q < no; q++) {
    const c = ord[q]!, l = lake[c]!;
    if (l && lakes[l - 1]!.outlet < 0 && lake[recv[c]!] !== l) lakes[l - 1]!.outlet = c;
  }
  for (const lk of lakes) {
    lk.level = Math.round(lk.level);
    // (a lake whose year's inflow is less than what its surface evaporates: salt, and it does not spill)
    const o = lk.outlet;
    if (o >= 0) { const evap = (Math.max(0, cl.temp[o]!) * 70 / 1000) * lk.areaKm2; lk.salt = flow[o]! < evap; }
  }

  // river segments: from each source or confluence down to the next confluence, lake or the sea
  const isRiver = (c: number): boolean => land[c] === 1 && flow[c]! >= RIVER_FLOW && !lake[c];
  const donors = new Uint8Array(N);
  for (let c = 0; c < N; c++) if (isRiver(c) && recv[c]! >= 0) donors[recv[c]!]++;
  const rivers: RiverSeg[] = [];
  for (let c = 0; c < N; c++) {
    if (!isRiver(c)) continue;
    // a segment starts at a source (no river donor) or just below a confluence (a cell whose receiver... ) : starts are
    // cells with donors != 1
    if (donors[c] === 1) continue;
    const cells = [c];
    let cur = c, end: RiverSeg['end'] = 'sea';
    for (let guard = 0; guard < N; guard++) {
      const r = recv[cur]!;
      if (r < 0) { end = 'sea'; break; }
      cells.push(r);
      if (!land[r]) { end = 'sea'; break; }
      if (lake[r]) { end = 'lake'; break; }
      if (!isRiver(r)) { end = 'sea'; break; }
      if (donors[r]! !== 1) { end = 'river'; break; }
      cur = r;
    }
    const f = flow[cells[cells.length - 2] ?? c]!;
    rivers.push({ id: rivers.length + 1, cells, flow: Math.round(f), order: f < 600 ? 1 : f < 1500 ? 2 : f < 4000 ? 3 : f < 10000 ? 4 : 5, end });
  }

  // coasts
  const coast = new Uint8Array(N);
  const coastKm = new Array<number>(t.masses.length + 1).fill(0);
  for (let c = 0; c < N; c++) {
    if (!land[c]) continue;
    let edges = 0, any = false;
    for (let k = 0; k < 8; k++) {
      const m = nb(c, k);
      if (m >= 0 && !land[m]) { any = true; if (k === 1 || k === 3 || k === 4 || k === 6) edges++; }
    }
    if (!any) continue;
    coastKm[t.mass[c]!] += edges * CELL_KM;
    const s = cl.slope[c]!, e = elev[c]!;
    coast[c] = s > 120 || e > 140 ? 3 : s > 40 || e > 50 ? 2 : 1;
  }
  return { recv, filled, flow, steady, peak, lake, lakes, rivers, coast, coastKm: coastKm.map((v) => Math.round(v)), order: ord };
}

/** Does every river cell drain to the sea or a lake? (Follows receivers; for the tests.) Returns the cells that do not. */
export function undrained(t: Terrain, h: Hydro): number[] {
  const bad: number[] = [];
  const ok = new Uint8Array(N);
  for (const r of h.rivers) for (const c0 of r.cells) {
    let c = c0;
    const path: number[] = [];
    let good = false;
    for (let g = 0; g < N; g++) {
      if (ok[c]) { good = true; break; }
      if (!t.land[c] || h.lake[c]) { good = true; break; }
      path.push(c);
      const r2 = h.recv[c]!;
      if (r2 < 0) break;
      c = r2;
    }
    if (good) for (const p of path) ok[p] = 1;
    else bad.push(c0);
  }
  return bad;
}
void H; void W;
