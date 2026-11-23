import { CELL_M, H, N, W, wrapDx, wrapX } from './grid';
import type { Hydro } from './hydro';
import { LazyFbm, NearTerrain } from './near';
import { routePath, type Pt } from './paths';
import type { Place } from './places';
import type { Road } from './roads';
import type { Terrain } from './terrain';

// WINDING ROADS (WORLD_GEN.md 5 and "Grand mountains": the owner's wish that country roads wind round the hills and climb
// to passes and mountain villages by switchbacks). roads.ts routes every road on the atlas grid (1 km cells: the
// network's topology, its junctions and drive times); here each road's drawn line is routed again on a fine grid of
// 134.4 m nodes (8 to an atlas cell, 32 directions of move) in a corridor round its cells, on a cost surface:
// - the grade is a hard limit (county roads 10%, highways 7%), measured on the ground the renderer lays a road on (the
//   skeleton's bilinear heights: WorldTerrain calms the land's detail within 36 m of a road to them), sampled every 70 m
//   or less along every move; a climb steeper than the limit can only be made across the slope, so the line zigzags up
//   it: switchbacks;
// - grade below the limit costs more the nearer it is to it (roads ease along contours);
// - knolls and ridges of the near terrain (near.ts: the relief drawn round the road, above those heights) cost by their
//   height, so roads go round the hills rather than through them (county roads more than highways: highways stay
//   straighter and gentler);
// - every river crossing costs a bridge (about 1.5 km of road); hub pads are closed (a road leaves a hub by its pin and
//   the half cell straight out, as paths.ts has it) and the capital's growth margin is dear.
// Where a road shares the cells of an earlier one (a county road joining a highway, two highways sharing a trunk), its
// line runs on the earlier road's line there and joins it exactly. Pure in the world; the line is the road's `path`.

/** The grade limits (rise over run). */
export const MAX_GRADE = { highway: 0.07, county: 0.1 } as const;
/** Fine nodes per atlas cell. */
export const FINE = 8;
const STEP_M = CELL_M / FINE;
/** A bridge's cost in metres of road. */
const BRIDGE_M = 1500;
/** Atlas cells guiding one routed stretch. */
const CHUNK = 20;

// the 32 moves: (1,0), (1,1), (2,1), (3,1), (3,2) and their reflections
const MOVES: [number, number, number][] = [];
for (const [a, b] of [[1, 0], [1, 1], [2, 1], [3, 1], [3, 2]] as const) {
  const set = new Set<string>();
  for (const [p, q] of [[a, b], [b, a]] as const) for (const sp of [1, -1]) for (const sq of [1, -1]) { const k = `${p * sp},${q * sq}`; if (!set.has(k)) { set.add(k); MOVES.push([p * sp, q * sq, Math.hypot(p, q) * STEP_M]); } }
}

const MOVES16 = MOVES.filter(([a, b]) => Math.abs(a) + Math.abs(b) <= 3);

export interface WindStats { roads: number; routed: number; relaxed: number; straight: number; switchbacks: number; bridges: number; ms: number }

interface Seg { ax: number; az: number; bx: number; bz: number }

export function windRoads(t: Terrain, h: Hydro, hubs: readonly Place[], roads: Road[]): WindStats {
  const t0 = Date.now();
  const elevR = Int16Array.from(t.elev, (e) => Math.max(-32768, Math.min(32767, Math.round(e))));
  const nt = new NearTerrain(t.seed, elevR);
  // (the skeleton's bilinear height, as NearTerrain.height, made fast: the grade check samples it tens of millions of times)
  const ef = Float64Array.from(elevR);
  const height = (x: number, z: number): number => {
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
  };
  // the steepest a bilinear patch can be (its grade's bound from its edges), the worst of the 4 x 4 patches round each
  // cell: a move starting in a cell whose bound is under the grade limit needs no sampling along it
  const patch = new Float32Array(N);
  for (let j = 0; j < H - 1; j++) for (let i = 0; i < W; i++) {
    const i1 = (i + 1) % W, a = ef[j * W + i]!, b = ef[j * W + i1]!, c = ef[(j + 1) * W + i]!, d = ef[(j + 1) * W + i1]!;
    patch[j * W + i] = (Math.max(Math.abs(b - a), Math.abs(d - c)) + Math.max(Math.abs(c - a), Math.abs(d - b))) / CELL_M;
  }
  const bound = new Float32Array(N).fill(1);
  for (let j = 2; j < H - 2; j++) for (let i = 0; i < W; i++) {
    let m = 0;
    for (let dj = -2; dj <= 1; dj++) for (let di = -2; di <= 1; di++) m = Math.max(m, patch[(j + dj) * W + (i + di + W) % W]!);
    bound[j * W + i] = m;
  }
  // the near terrain's knolls and ridges (near.ts smooth()'s peaks term: its ridged noise on its roughness and height),
  // at each fine node: what a road should go round (the finer hills are calmed under a road anyway)
  const ridgeN = new LazyFbm(t.seed, 'w5-ridge', W * 2, 3), roughN = new LazyFbm(t.seed, 'w5-rough', W / 2, 2);
  const knollAt = (x: number, z: number): number => {
    let v: number;
    {
      const cx = x, cz = z;
      const base = height(cx, cz);
      const sat = (u: number): number => (u < 0 ? 0 : u > 1 ? 1 : u), sm = (u: number): number => u * u * (3 - 2 * u);
      const coast = sm(sat((base - 1) / 24));
      const ro = roughN.at(cx, cz);
      const crop = sm(sat((ro - 0.62) / 0.12)), mount = sm(sat((base - 450) / 900));
      const ra = 2 * ridgeN.at(cx, cz) - 1;
      let rr = 1 - Math.sqrt(ra * ra + 0.003); rr *= rr;
      v = rr * (320 * mount + 160 * crop) * coast;
    }
    return v;
  };
  // rivers as segments between cell centres, filed under the cells they touch
  const riverIdx = new Int32Array(N).fill(-1), riverLists: Seg[][] = [];
  const file = (c: number, s: Seg): void => { let k = riverIdx[c]!; if (k < 0) { k = riverLists.length; riverLists.push([]); riverIdx[c] = k; } riverLists[k]!.push(s); };
  const riverAt = { get: (c: number): Seg[] | undefined => { const k = riverIdx[c]!; return k < 0 ? undefined : riverLists[k]; } };
  for (const r of h.rivers) for (let q = 1; q < r.cells.length; q++) {
    const a = r.cells[q - 1]!, b = r.cells[q]!;
    if (!t.land[b] && !t.land[a]) continue;
    const ax = (a % W) + 0.5, az = Math.floor(a / W) + 0.5;
    const s: Seg = { ax, az, bx: ax + wrapDx((b % W) + 0.5 - ax), bz: Math.floor(b / W) + 0.5 };
    file(a, s); file(b, s);
    const ia = a % W, ja = Math.floor(a / W), ib = b % W, jb = Math.floor(b / W);
    if (ia !== ib && ja !== jb) { file(jb * W + ia, s); file(ja * W + ib, s); }
  }
  const hubAt = new Map<number, Place>();
  for (const p of hubs) hubAt.set(p.cell, p);
  const stats: WindStats = { roads: roads.length, routed: 0, relaxed: 0, straight: 0, switchbacks: 0, bridges: 0, ms: 0 };
  const firstAt = new Map<number, number>();
  const paths: Pt[][] = [];

  // ---- the fine search's buffers (grown as needed, reset by a stamp)
  let cap = 1 << 16;
  let g = new Float64Array(cap), from = new Int32Array(cap), seen = new Int32Array(cap), shut = new Int32Array(cap), hc = new Float32Array(cap), kc = new Float32Array(cap), hs = new Int32Array(cap);
  let gen = 0;
  const heapK: number[] = [], heapV: number[] = [];
  const push = (k: number, v: number): void => {
    let i = heapK.length; heapK.push(k); heapV.push(v);
    while (i > 0) { const p = (i - 1) >> 1; if (heapK[p]! < k || (heapK[p] === k && heapV[p]! <= v)) break; heapK[i] = heapK[p]!; heapV[i] = heapV[p]!; i = p; }
    heapK[i] = k; heapV[i] = v;
  };
  const pop = (): number => {
    const top = heapV[0]!, k = heapK.pop()!, v = heapV.pop()!, n = heapK.length;
    if (n) {
      let i = 0;
      for (;;) {
        const l = 2 * i + 1; if (l >= n) break;
        let m = l; const r = l + 1;
        if (r < n && (heapK[r]! < heapK[l]! || (heapK[r] === heapK[l] && heapV[r]! < heapV[l]!))) m = r;
        if (heapK[m]! > k || (heapK[m] === k && heapV[m]! >= v)) break;
        heapK[i] = heapK[m]!; heapV[i] = heapV[m]!; i = m;
      }
      heapK[i] = k; heapV[i] = v;
    }
    return top;
  };

  /** The steepest grade along the straight line p-q (sampled every 70 m or less). */
  const gradeOf = (px: number, pz: number, qx: number, qz: number): number => {
    const L = Math.hypot(qx - px, qz - pz) * CELL_M;
    if (L < 1e-6) return 0;
    const k = Math.max(1, Math.ceil(L / 70));
    let worst = 0, prev = height(px, pz);
    for (let s = 1; s <= k; s++) {
      const e = height(px + ((qx - px) * s) / k, pz + ((qz - pz) * s) / k);
      worst = Math.max(worst, Math.abs(e - prev) / (L / k));
      prev = e;
    }
    return worst;
  };
  const crossings = (px: number, pz: number, qx: number, qz: number): number => {
    let n = 0;
    const ca = Math.floor(pz) * W + wrapX(Math.floor(px)), cb = Math.floor(qz) * W + wrapX(Math.floor(qx));
    const la = riverAt.get(ca), lb = cb === ca ? undefined : riverAt.get(cb);
    if (!la && !lb) return 0;
    for (const list of [la, lb]) {
      if (!list) continue;
      for (const s of list) {
        if (list === lb && la && la.includes(s)) continue;
        // (the segment moved next to the line: x unwrapped)
        const sh = wrapDx(s.ax - px) - (s.ax - px);
        if (intersects(px, pz, qx, qz, s.ax + sh, s.az, s.bx + sh, s.bz)) n++;
      }
    }
    return n;
  };

  /**
   * The fine route from (sx, sz) to (ex, ez) (cell units, unwrapped) in the corridor round [guide] (unwrapped cell
   * centres), its grade at most [gmax]: the points after the start, ending exactly on the end; null if there is none.
   */
  const route = (sx: number, sz: number, ex: number, ez: number, guide: Pt[], margin: number, gmax: number, county: boolean, padHubs: Place[]): Pt[] | null => {
    let x0 = Math.min(sx, ex), x1 = Math.max(sx, ex), z0 = Math.min(sz, ez), z1 = Math.max(sz, ez);
    for (const [x, z] of guide) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); }
    const amin = Math.floor((x0 - margin - 1 - sx) * FINE), amax = Math.ceil((x1 + margin + 1 - sx) * FINE);
    const bmin = Math.floor((z0 - margin - 1 - sz) * FINE), bmax = Math.ceil((z1 + margin + 1 - sz) * FINE);
    const nx = amax - amin + 1, nz = bmax - bmin + 1, size = nx * nz;
    if (size > 4_000_000) return null;
    if (size > cap) {
      while (cap < size) cap *= 2;
      g = new Float64Array(cap); from = new Int32Array(cap); seen = new Int32Array(cap); shut = new Int32Array(cap); hc = new Float32Array(cap); kc = new Float32Array(cap); hs = new Int32Array(cap);
      gen = 0;
    }
    const id = ++gen;
    // the corridor: atlas cells within [margin] of the guide's cells (and the ends'), on a mask over the box's cells
    const ci0 = Math.floor(sx + amin / FINE) - 1, cj0 = Math.floor(sz + bmin / FINE) - 1;
    const cw = Math.ceil((amax - amin) / FINE) + 3, chh = Math.ceil((bmax - bmin) / FINE) + 3;
    const mask = new Uint8Array(cw * chh);
    const r = Math.ceil(margin);
    for (const [x, z] of [...guide, [sx, sz] as Pt, [ex, ez] as Pt]) {
      const i0 = Math.floor(x) - ci0, j0 = Math.floor(z) - cj0;
      for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) { const i = i0 + di, j = j0 + dj; if (i >= 0 && j >= 0 && i < cw && j < chh) mask[j * cw + i] = 1; }
    }
    const posX = (a: number): number => sx + a / FINE, posZ = (b: number): number => sz + b / FINE;
    const nodeOk = (a: number, b: number): boolean => {
      const x = posX(a), z = posZ(b);
      if (z < 0.5 || z > H - 0.5) return false;
      const mi = Math.floor(x) - ci0, mj = Math.floor(z) - cj0;
      if (mi < 0 || mj < 0 || mi >= cw || mj >= chh || !mask[mj * cw + mi]) return false;
      const c = Math.floor(z) * W + wrapX(Math.floor(x));
      if (!t.land[c]) return false;
      for (const p of padHubs) {
        const dx = Math.abs(wrapDx(x - p.x)) * CELL_M, dz = Math.abs(z - p.z) * CELL_M;
        if (dx <= p.hub!.padM.w / 2 && dz <= p.hub!.padM.h / 2) return false;
      }
      return true;
    };
    const extra = (a: number, b: number): number => {
      // the near terrain's knolls over the calm ground, a lake, the capital's growth margin
      const x = posX(a), z = posZ(b);
      const knoll = knollAt(sx + (2 * Math.floor(a / 2)) / FINE, sz + (2 * Math.floor(b / 2)) / FINE);
      const c = Math.floor(z) * W + wrapX(Math.floor(x));
      let e = knoll / (county ? 25 : 90) + (h.lake[c] ? 6 : 0);
      for (const p of padHubs) {
        if (p.hub!.type !== 'capital') continue;
        const dx = Math.abs(wrapDx(x - p.x)) * CELL_M, dz = Math.abs(z - p.z) * CELL_M;
        if (dx <= p.hub!.padM.w / 2 + p.hub!.growthM && dz <= p.hub!.padM.h / 2 + p.hub!.growthM) e += 8;
      }
      return e;
    };
    const ix = (a: number, b: number): number => (b - bmin) * nx + (a - amin);
    const hAt = (a: number, b: number): number => { const i = ix(a, b); if (hs[i] !== id) { hs[i] = id; hc[i] = height(posX(a), posZ(b)); kc[i] = nodeOk(a, b) ? extra(a, b) : -1; } return hc[i]!; };
    const okAt = (a: number, b: number): boolean => { hAt(a, b); return kc[ix(a, b)]! >= 0; };
    const ea = Math.round((ex - sx) * FINE), eb = Math.round((ez - sz) * FINE);
    const s0 = ix(0, 0);
    heapK.length = 0; heapV.length = 0;
    hAt(0, 0); kc[s0] = Math.max(0, kc[s0]!);
    g[s0] = 0; from[s0] = -1; seen[s0] = id;
    const hd = (a: number, b: number): number => Math.hypot((ex - sx) * FINE - a, (ez - sz) * FINE - b) * STEP_M;
    const hw = county ? 2 : 1.6;
    push(hd(0, 0) * hw, s0);
    let goal = -1, pops = 0;
    while (heapK.length) {
      const i = pop();
      if (shut[i] === id) continue;
      shut[i] = id;
      const a = (i % nx) + amin, b = Math.floor(i / nx) + bmin;
      // the end: within a couple of steps of it, reachable straight within the grade
      if (Math.abs(a - ea) <= 2 && Math.abs(b - eb) <= 2 && gradeOf(posX(a), posZ(b), ex, ez) <= gmax && !crossings(posX(a), posZ(b), ex, ez)) { goal = i; break; }
      if (++pops > 600_000) break;
      const gi = g[i]!, ha = hc[i]!;
      const px0 = posX(a), pz0 = posZ(b);
      const cell0 = Math.floor(pz0) * W + wrapX(Math.floor(px0));
      const bd = bound[cell0]!, gentle = bd <= gmax;
      // (16 directions where the ground is gentle, all 32 where a climb may need to zigzag)
      const moves = bd <= gmax * 2 ? MOVES16 : MOVES;
      for (let m = 0; m < moves.length; m++) {
        const mv = moves[m]!, a2 = a + mv[0], b2 = b + mv[1];
        if (a2 < amin || a2 > amax || b2 < bmin || b2 > bmax) continue;
        const j = ix(a2, b2);
        if (shut[j] === id) continue;
        if (!okAt(a2, b2)) continue;
        const L = mv[2];
        const hb = hc[j]!;
        let gr = Math.abs(hb - ha) / L;
        if (gr > gmax) continue;
        if (!gentle) { gr = gradeOf(px0, pz0, posX(a2), posZ(b2)); if (gr > gmax) continue; }
        const q = gr / gmax;
        const cost = L * (1 + (county ? 3 : 4) * q * q + kc[j]!) + crossings(px0, pz0, posX(a2), posZ(b2)) * BRIDGE_M;
        const ng = gi + cost;
        if (seen[j] !== id || ng < g[j]!) { seen[j] = id; g[j] = ng; from[j] = i; push(ng + hd(a2, b2) * hw, j); }
      }
    }
    if (goal < 0) return null;
    const out: Pt[] = [[ex, ez]];
    for (let i = goal; i >= 0 && i !== s0; i = from[i]!) out.push([posX((i % nx) + amin), posZ(Math.floor(i / nx) + bmin)]);
    out.reverse();
    // (the end node may be the end itself)
    if (out.length >= 2) { const p = out[out.length - 2]!; if (Math.hypot(p[0] - ex, p[1] - ez) < 1e-9) out.splice(out.length - 2, 1); }
    return out;
  };

  /** Routes from [start] through the guide cells to [end], a chunk at a time, relaxing the corridor and then the grade
   * where it must. */
  const windRun = (start: Pt, end: Pt, guide: Pt[], county: boolean, out: Pt[], padHubs: Place[]): void => {
    const gmax0 = county ? MAX_GRADE.county : MAX_GRADE.highway;
    let cur = start;
    const n = Math.max(1, Math.ceil(guide.length / CHUNK));
    for (let k = 0; k < n; k++) {
      const part = guide.slice(k * CHUNK, (k + 1) * CHUNK);
      const tgt: Pt = k === n - 1 ? end : part[part.length - 1]!;
      const tx = cur[0] + wrapDx(tgt[0] - cur[0]), tz = tgt[1];
      const near = part.map(([x, z]) => [cur[0] + wrapDx(x - cur[0]), z] as Pt);
      let got: Pt[] | null = null;
      let relaxed = false;
      for (const [margin, gm] of [[1.5, gmax0], [3, gmax0], [6, gmax0], [6, gmax0 * 1.4], [8, gmax0 * 2], [8, gmax0 * 3]] as const) {
        got = route(cur[0], cur[1], tx, tz, near, margin, gm, county, padHubs);
        if (got) break;
        if (margin === 6) relaxed = true;
      }
      if (relaxed && got) stats.relaxed++;
      if (!got) { got = [...near.slice(0, -1), [tx, tz] as Pt]; stats.straight++; }
      else stats.routed++;
      out.push(...got);
      cur = [tx, tz];
    }
  };

  roads.forEach((road, ri) => {
    const county = road.cls === 'county';
    const isHub = (id: string | null): boolean => !!id && id.startsWith('h:');
    const hubEnd = (c: number, id: string | null): Place | undefined => (isHub(id) ? hubAt.get(c) : undefined);
    const base = routePath(road.cells, 'highway', (c) => (c === road.cells[0] ? hubEnd(c, road.a) : c === road.cells[road.cells.length - 1] ? hubEnd(c, road.b) : undefined));
    // the base's points: [pin, out] at a hub start, one per cell between, [out, pin] at a hub end
    const head = road.cells.length >= 2 && hubEnd(road.cells[0]!, road.a) ? 2 : 0;
    const tail = road.cells.length >= 2 && hubEnd(road.cells[road.cells.length - 1]!, road.b) ? 2 : 0;
    const cellPts: { p: Pt; cell: number }[] = [];
    for (let q = head; q < base.length - tail; q++) cellPts.push({ p: base[q]!, cell: road.cells[q - head + (head ? 1 : 0)]! });
    const padHubs = hubs.filter((p) => road.cells.some((c, q) => q % 8 === 0 && Math.abs(wrapDx((c % W) + 0.5 - p.x)) < 12 && Math.abs(Math.floor(c / W) + 0.5 - p.z) < 12));
    const out: Pt[] = base.slice(0, head);
    // runs of cells: own (routed here) or on an earlier road (its line copied)
    type Run = { cover: number; pts: { p: Pt; cell: number }[]; i1?: number; i2?: number };
    const runs: Run[] = [];
    for (const cp of cellPts) {
      const cover = firstAt.get(cp.cell) ?? -1;
      const last = runs[runs.length - 1];
      if (last && last.cover === cover) last.pts.push(cp); else runs.push({ cover, pts: [cp] });
    }
    const nearestV = (line: Pt[], p: Pt): number => { let bi = 0, bd = Infinity; line.forEach((q, i) => { const d = Math.hypot(wrapDx(q[0] - p[0]), q[1] - p[1]); if (d < bd) { bd = d; bi = i; } }); return bi; };
    for (const run of runs) if (run.cover >= 0) { const line = paths[run.cover]!; run.i1 = nearestV(line, run.pts[0]!.p); run.i2 = nearestV(line, run.pts[run.pts.length - 1]!.p); }
    const shift = (q: Pt, near: Pt): Pt => [near[0] + wrapDx(q[0] - near[0]), q[1]];
    let cur: Pt | null = out.length ? out[out.length - 1]! : null;
    let switchbacks = 0;
    runs.forEach((run, k) => {
      if (run.cover >= 0) {
        const line = paths[run.cover]!;
        // (a road that starts on an earlier one, as two highways leaving one pin: it runs on from where it is)
        if (k === 0 && cur) run.i1 = nearestV(line, cur);
        const a = run.i1!, b = run.i2!;
        const seg = a <= b ? line.slice(a, b + 1) : line.slice(b, a + 1).reverse();
        for (const q of seg) { const s = shift(q, cur ?? q); if (!cur || Math.hypot(s[0] - cur[0], s[1] - cur[1]) > 1e-9) out.push(s); cur = s; }
        return;
      }
      const next = runs[k + 1];
      const startP: Pt = cur ?? run.pts[0]!.p;
      if (!cur) { out.push(startP); cur = startP; }
      const endRaw: Pt = next ? paths[next.cover]![next.i1!]! : tail ? base[base.length - tail]! : run.pts[run.pts.length - 1]!.p;
      const endP = shift(endRaw, cur);
      const guide = run.pts.map((q) => shift(q.p, cur!));
      const before = out.length;
      windRun(cur, endP, guide, county, out, padHubs);
      switchbacks += countSwitchbacks(out.slice(Math.max(0, before - 1)));
      cur = out[out.length - 1]!;
    });
    for (const q of base.slice(base.length - tail)) { const s = shift(q, cur ?? q); out.push(s); cur = s; }
    const line = dedupe(out);
    paths[ri] = line;
    road.path = line;
    let km = 0, worst = 0, bridges = 0;
    for (let q = 1; q < line.length; q++) {
      const [px, pz] = line[q - 1]!, [qx, qz] = line[q]!;
      km += Math.hypot(qx - px, qz - pz) * CELL_M / 1000;
      worst = Math.max(worst, gradeOf(px, pz, qx, qz));
      bridges += crossings(px, pz, qx, qz);
    }
    road.km = Math.round(km * 10) / 10;
    road.maxGrade = Math.round(worst * 1000) / 1000;
    road.switchbacks = switchbacks;
    road.bridges = bridges;
    stats.switchbacks += switchbacks;
    stats.bridges += bridges;
    for (const c of road.cells) if (!firstAt.has(c)) firstAt.set(c, ri);
  });
  stats.ms = Date.now() - t0;
  void N;
  return stats;
}

function dedupe(pts: Pt[]): Pt[] {
  const out: Pt[] = [];
  for (const p of pts) { const l = out[out.length - 1]; if (!l || Math.hypot(l[0] - p[0], l[1] - p[1]) > 1e-9) out.push(p); }
  return out;
}

/** Hairpins: the heading turning by more than 110 degrees over a vertex (or two short ones), on a line of fine moves. */
export function countSwitchbacks(line: readonly Pt[]): number {
  let n = 0;
  for (let q = 1; q + 1 < line.length; q++) {
    const a = line[q - 1]!, b = line[q]!;
    // (the next point at least a fine step on, so two short moves round a bend count as one turn)
    let k = q + 1;
    while (k + 1 < line.length && Math.hypot(line[k]![0] - b[0], line[k]![1] - b[1]) < 1.5 / FINE) k++;
    const c = line[k]!;
    const ux = b[0] - a[0], uz = b[1] - a[1], vx = c[0] - b[0], vz = c[1] - b[1];
    const lu = Math.hypot(ux, uz), lv = Math.hypot(vx, vz);
    if (lu < 1e-9 || lv < 1e-9) continue;
    if ((ux * vx + uz * vz) / (lu * lv) < Math.cos((110 * Math.PI) / 180)) { n++; q = k - 1; }
  }
  return n;
}

function intersects(ax: number, az: number, bx: number, bz: number, cx: number, cz: number, dx: number, dz: number): boolean {
  const d1 = (dx - cx) * (az - cz) - (dz - cz) * (ax - cx);
  const d2 = (dx - cx) * (bz - cz) - (dz - cz) * (bx - cx);
  const d3 = (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
  const d4 = (bx - ax) * (dz - az) - (bz - az) * (dx - ax);
  return ((d1 > 0) !== (d2 > 0)) && ((d3 > 0) !== (d4 > 0));
}
