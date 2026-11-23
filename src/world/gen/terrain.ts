import { CELL_KM, CELL_KM2, H, Heap, N, N8, W, components, distanceTo, latOf, nb, wrapDx } from './grid';
import { Fbm, Lattice, hashStr, hashU, stream } from './noise';

// CONTINENTS, PLATES AND ELEVATION (WORLD_GEN.md sections 2.1-2.2) on the atlas grid.
//
// The owner's decision (2026-11-23): 30% land split into 8-10 small continents and big islands (short drives, lots of
// sailing). Continent cores are placed apart round the planet in the warm and temperate bands; each core owns the cells
// nearest it (distance scaled by its size), and its land is a few overlapping elongated blobs plus domain-warped noise.
// A trench along the borders of the cores' regions keeps continents apart (deepened until no landmass holds two cores);
// extra noise throws off the big islands and archipelagos.

/** The land fraction outside the ice caps. */
export const LAND_FRACTION = 0.3;
/** Latitude past which the sea is the polar ice shelf (no land is generated; WORLD_GEN.md 1, "The ice caps"). */
export const ICE_SHELF_LAT = 72;
/** A landmass this big or bigger is a continent; smaller ones are islands (big islands from BIG_ISLAND_KM2). */
export const CONTINENT_KM2 = 12000;
export const BIG_ISLAND_KM2 = 1500;

/** Range classes (WORLD_GEN.md 2.2, "Grand mountains"). */
export const RANGE_MODEST = 1, RANGE_MIDDLE = 2, RANGE_GREAT = 3;
export const RANGE_CLASS = ['', 'modest', 'middle', 'great'] as const;
/** The steepest step between land neighbours one atlas cell apart (m): the faces of the great ranges. */
export const MAX_STEP_M = 480;
/** The start continent's heart (the capital's region stays gentle): land this near (km) its centre does not count to a
 * boundary's score for a great range, nor for a middle one. */
export const GREAT_START_KM = 170;
export const MIDDLE_START_KM = 110;

export interface RangeInfo {
  /** `r:<a>-<b>`: the plate pair. */
  id: string;
  pair: number;
  cls: 'modest' | 'middle' | 'great';
  /** The boundary's land score (land cells within 20 km of it, times the convergence). */
  score: number;
  /** Land cells where this range's term is the highest and the ground over 500 m; its highest cell and its height (m). */
  cells: number;
  peakCell: number;
  peakM: number;
  /** The continent core most of it stands on. */
  core: number;
}

export interface Core { x: number; z: number; areaKm2: number; radius: number }

export interface Terrain {
  seed: string;
  /** Land (1) or sea (0) per cell. */
  land: Uint8Array;
  /** Metres above sea level (negative: depth). */
  elev: Float32Array;
  /** The continent core that owns each cell (index into cores). */
  owner: Uint8Array;
  cores: Core[];
  /** Landmass id per cell (0 for sea), and each landmass's facts, largest first (id = index + 1). */
  mass: Int32Array;
  masses: Landmass[];
  /** km to the nearest sea cell (land) and to the nearest land cell (sea). */
  distSea: Float32Array;
  distLand: Float32Array;
  /** Mountain-range height contribution (m), for the rail's passes and the mountain towns. */
  mount: Float32Array;
  /** The range (plate pair a * plates + b, a < b) whose term is highest at each land cell where it passes 150 m; -1 elsewhere. */
  range: Int16Array;
  /** Every converging range with its class, largest score first among the great ones. */
  ranges: RangeInfo[];
  /** Each plate pair's class (RANGE_MODEST, RANGE_MIDDLE, RANGE_GREAT; 0 for pairs that do not converge). */
  rangeCls: Uint8Array;
  plates: number;
  /** The quantile threshold that made the land fraction (for the record). */
  thresholdTries: number;
}

export type MassKind = 'continent' | 'big island' | 'island';

export interface Landmass {
  id: number;
  kind: MassKind;
  cells: number;
  areaKm2: number;
  /** Centroid (cell units, x on the circle) and the long and short axes (km, principal components' extents). */
  cx: number;
  cz: number;
  longKm: number;
  shortKm: number;
  /** The continent core it holds (index), or -1. */
  core: number;
}

function gauss(r: () => number): number {
  const u = Math.max(1e-12, r()), v = r();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

export function makeTerrain(seed: string): Terrain {
  const r = stream(seed, 'continents');
  const n = 8 + Math.floor(r() * 3); // 8-10 continents
  const polarJ = (H / 2) * (ICE_SHELF_LAT / 90);
  let usable = 0;
  for (let j = 0; j < H; j++) if (Math.abs(latOf(j + 0.5)) < ICE_SHELF_LAT) usable += W;
  const landKm2 = usable * CELL_KM2 * LAND_FRACTION;

  // ---- the cores: the start continent (0) about twice the others; placed apart round the planet
  const shares = [1.6 + 0.25 * r()];
  for (let k = 1; k < n; k++) shares.push(0.65 + 0.6 * r());
  const tot = shares.reduce((a, b) => a + b, 0);
  const cores: Core[] = [];
  let slack = 1.0;
  for (let k = 0; k < n; k++) {
    const areaKm2 = landKm2 * 0.8 * (shares[k]! / tot);
    const radius = Math.sqrt(areaKm2 / Math.PI) / CELL_KM; // cells
    for (let tries = 0; ; tries++) {
      if (tries > 0 && tries % 400 === 0) slack *= 0.93;
      const x = r() * W;
      const z = H / 2 + (r() * 2 - 1) * (H / 2) * (54 / 90);
      const ok = cores.every((c) => Math.hypot(wrapDx(x - c.x), z - c.z) > slack * (1.25 * (c.radius + radius) + 55 / CELL_KM));
      if (ok || tries > 6000) { cores.push({ x, z, areaKm2, radius }); break; }
    }
  }
  // each continent: 2-4 elongated blobs along a random heading
  interface Blob { x: number; z: number; ca: number; sa: number; a: number; b: number }
  const blobs: Blob[][] = cores.map((c) => {
    const ab = 1.25 + 0.6 * r(), ang = r() * Math.PI;
    const a = Math.sqrt((c.radius * c.radius) * ab), b = a / ab;
    const out: Blob[] = [];
    const m = 2 + Math.floor(r() * 3);
    for (let q = 0; q < m; q++) {
      const off = (r() * 2 - 1) * 0.55, side = (r() * 2 - 1) * 0.3;
      const ca = Math.cos(ang), sa = Math.sin(ang);
      out.push({ x: c.x + ca * off * a - sa * side * b, z: c.z + sa * off * a + ca * side * b, ca, sa, a: a * (0.6 + 0.3 * r()), b: b * (0.7 + 0.3 * r()) });
    }
    return out;
  });

  // ---- the land mask: own blob + ragged coast + islands - trench between cores' regions
  const wxN = new Fbm(seed, 'wx', 12, 4), wzN = new Fbm(seed, 'wz', 12, 4);
  const coastN = new Fbm(seed, 'coast', 20, 6), islN = new Fbm(seed, 'isl', 40, 5);
  const mask = new Float32Array(N);
  const owner = new Uint8Array(N);
  const gap = new Float32Array(N); // d2 - d1 (normalised): small near the border between two cores' regions
  const warpX = new Float32Array(N), warpZ = new Float32Array(N);
  const warp = 40 / CELL_KM;
  for (let j = 0; j < H; j++) {
    const lat = Math.abs(latOf(j + 0.5));
    for (let i = 0; i < W; i++) {
      const c = j * W + i, x = i + 0.5, z = j + 0.5;
      const wx = x + warp * (wxN.at(x, z) - 0.5) * 2, wz = z + warp * (wzN.at(x, z) - 0.5) * 2;
      warpX[c] = wx; warpZ[c] = wz;
      let d1 = Infinity, d2 = Infinity, k1 = 0;
      for (let k = 0; k < n; k++) {
        const co = cores[k]!;
        const d = Math.hypot(wrapDx(wx - co.x), wz - co.z) / (co.radius * 1.35);
        if (d < d1) { d2 = d1; d1 = d; k1 = k; } else if (d < d2) d2 = d;
      }
      owner[c] = k1;
      gap[c] = d2 - d1;
      let g = 0;
      for (const bl of blobs[k1]!) {
        const dx = wrapDx(wx - bl.x), dz = wz - bl.z;
        const u = (dx * bl.ca + dz * bl.sa) / bl.a, v = (-dx * bl.sa + dz * bl.ca) / bl.b;
        g = Math.max(g, Math.exp(-Math.pow(u * u + v * v, 1.3)));
      }
      const tg = (d2 - d1) / 0.3;
      const trench = Math.exp(-tg * tg);
      const isl = 3.2 * Math.max(0, islN.at(wx, wz) - 0.585);
      let m = g + 0.85 * (coastN.at(wx, wz) - 0.5) + isl - 0.7 * trench;
      if (lat > 56) m -= 0.8 * Math.min(1, (lat - 56) / 10);
      if (lat >= ICE_SHELF_LAT) m = -10;
      mask[c] = m;
    }
  }
  void polarJ;
  // threshold at the land fraction (outside the ice caps)
  const vals: number[] = [];
  for (let c = 0; c < N; c++) if (mask[c]! > -9) vals.push(mask[c]!);
  vals.sort((a, b) => a - b);
  const thr = vals[Math.floor(vals.length * (1 - LAND_FRACTION))]!;
  const land = new Uint8Array(N);
  for (let c = 0; c < N; c++) land[c] = mask[c]! > thr ? 1 : 0;

  // ---- keep continents apart: no landmass may hold the bulk of two cores; deepen the border trench until none does
  let tries = 0;
  for (let eps = 0.02; eps < 0.6; eps += 0.03) {
    tries++;
    const { label, sizes } = components((c) => land[c] === 1);
    const counts = new Map<number, number[]>();
    for (let c = 0; c < N; c++) {
      const l = label[c]!;
      if (!l || sizes[l]! * CELL_KM2 < BIG_ISLAND_KM2) continue;
      let a = counts.get(l);
      if (!a) { a = new Array(n).fill(0); counts.set(l, a); }
      a[owner[c]!]++;
    }
    const bad = new Set<number>();
    for (const [l, a] of counts) {
      const big = a.map((v, k) => (v * CELL_KM2 > 2500 ? k : -1)).filter((k) => k >= 0);
      if (big.length >= 2) bad.add(l);
    }
    if (!bad.size) break;
    for (let c = 0; c < N; c++) if (land[c] && bad.has(label[c]!) && gap[c]! < eps) land[c] = 0;
  }

  // ---- landmasses
  const { label, sizes } = components((c) => land[c] === 1);
  const order = sizes.map((s, l) => ({ l, s })).slice(1).sort((a, b) => b.s - a.s || a.l - b.l);
  const remap = new Int32Array(sizes.length);
  order.forEach((o, k) => { remap[o.l] = k + 1; });
  const mass = new Int32Array(N);
  for (let c = 0; c < N; c++) mass[c] = label[c] ? remap[label[c]!]! : 0;
  const masses = measureMasses(mass, order.length, owner);

  // ---- distances to the coast
  const ds = distanceTo((c) => land[c] === 0);
  const dl = distanceTo((c) => land[c] === 1);
  for (let c = 0; c < N; c++) { ds[c] = ds[c]! * CELL_KM; dl[c] = dl[c]! * CELL_KM; }

  // ---- plates (a warped Voronoi) and the ranges where they converge
  const K = 14 + Math.floor(r() * 4);
  const px: number[] = [], pz: number[] = [], vx: number[] = [], vz: number[] = [];
  for (let k = 0; k < K; k++) { px.push(r() * W); pz.push((0.08 + 0.84 * r()) * H); vx.push(gauss(r)); vz.push(gauss(r)); }
  const maxh: number[] = [];
  for (let k = 0; k < K * K; k++) maxh.push(1300 + 1700 * r());
  const rwN = new Lattice(seed, 'rw', 10), ridgeN = new Fbm(seed, 'ridge', 24, 5), hillsN = new Fbm(seed, 'hills', 16, 7);
  // Each pair of plates (a, b) has its own range: its crest height and convergence are the pair's (symmetric), and it
  // stands on the pair's boundary distance g = (d_a + d_b - 2 d1) / 2, which is the usual (d2 - d1) / 2 for the nearest
  // pair and grows smoothly past the ends of their boundary. Every pair's term is continuous everywhere, and the cell
  // takes the highest (a max of continuous terms is continuous), so the height never steps where the second-nearest
  // plate changes (the W4 plateau-wall east of the capital). The profile has compact support (a (1 - x^2)^3 bump), so
  // only plates within reach of the nearest can contribute and skipping the rest is exact, not an approximation.
  const pairConv = new Float64Array(K * K), pairH = new Float64Array(K * K);
  for (let a = 0; a < K; a++) for (let b = 0; b < K; b++) {
    if (a === b) continue;
    const ddx = wrapDx(px[b]! - px[a]!), ddz = pz[b]! - pz[a]!, nn = Math.hypot(ddx, ddz) + 1e-6;
    pairConv[a * K + b] = Math.min(Math.max(((vx[a]! - vx[b]!) * ddx + (vz[a]! - vz[b]!) * ddz) / nn, 0), 1);
    pairH[a * K + b] = maxh[Math.min(a, b) * K + Math.max(a, b)]!;
  }
  // ---- range classes (WORLD_GEN.md 2.2, "Grand mountains"): every converging pair's boundary is scored by how much land
  // it runs through (cells within 20 km of it, times the convergence), leaving out the start continent's heart (the
  // capital's region stays gentle). The strongest one or two become great ranges, the next one to three on each continent
  // middle ranges, the rest stay modest (today's 1,500-3,200 m nominal crests).
  const cls = classifyRanges(seed, K, px, pz, pairConv, warpX, warpZ, land, owner, masses[0]!, n);
  const rc = stream(seed, 'range-class');
  // (a middle range's own crest and convergence; its modest ones stay in pairH and pairConv for the start continent's heart)
  const midH = new Float64Array(K * K), midC = new Float64Array(K * K);
  for (let a = 0; a < K; a++) for (let b = a + 1; b < K; b++) {
    const k = a * K + b, hm = 3200 + 1300 * rc();
    if (cls.cls[k] === RANGE_MIDDLE) { midH[k] = hm; midC[k] = Math.max(pairConv[k]!, 0.75); }
  }
  const start = masses[0]!;
  const bump = (x: number): number => { if (x >= 1) return 0; const q = 1 - x * x; return q * q * q; };
  /** The range's crest falls to nothing at CREST x its width, its foothills at FOOT x (WORLD_GEN.md 2.2). */
  const CREST = 1.9, FOOT = 3.2, FOOT_SHARE = 0.16;
  // a great range: a massif BASE high, its half-width 46-74 km, ridgelines parallel to the boundary every RIDGE_KM (meandering
  // by a noise), peaks along them by a second noise, passes where a slow noise along the range lowers the ridges
  const G_BASE = 2300, G_RIDGE = 4300, RIDGE_KM = 17;
  const peakN = new Fbm(seed, 'great-peaks', 150, 3), passN = new Fbm(seed, 'great-pass', 60, 2), phaseN = new Fbm(seed, 'great-phase', 90, 3);
  const greatPair = new Uint8Array(K * K);
  for (const k of cls.great) { greatPair[k] = 1; greatPair[(k % K) * K + Math.floor(k / K)] = 1; }
  const dk = new Float64Array(K);
  const near: number[] = [];
  const elev = new Float32Array(N);
  const mount = new Float32Array(N);
  const range = new Int16Array(N).fill(-1);
  for (let j = 0; j < H; j++) for (let i = 0; i < W; i++) {
    const c = j * W + i, x = i + 0.5, z = j + 0.5;
    const wx = warpX[c]!, wz = warpZ[c]!;
    let d1 = Infinity;
    for (let k = 0; k < K; k++) {
      const d = Math.hypot(wrapDx(wx - px[k]!), wz - pz[k]!);
      dk[k] = d;
      if (d < d1) d1 = d;
    }
    const rw = rwN.at(x, z);
    const widthKm = 11 + 17 * rw;
    const halfG = 46 + 28 * rw;
    const reach = (2 * Math.max(FOOT * widthKm, 1.7 * halfG)) / CELL_KM; // g (in cells, doubled) past which no pair reaches
    near.length = 0;
    for (let k = 0; k < K; k++) if (dk[k]! - d1 < reach) near.push(k);
    // the crest (shaped by the ridged noise) on a broad apron of foothills: ranges rise over kilometres, not in a step
    const rd = 1 - Math.abs(2 * ridgeN.at(x, z) - 1);
    const crestMod = 0.3 + 0.7 * rd * rd;
    // (middle and great ranges ease back to modest ones towards the start continent's heart: the capital's region stays gentle)
    const dStart = Math.hypot(wrapDx(x - start.cx), z - start.cz) * CELL_KM;
    const fMid = smooth01((dStart - MIDDLE_START_KM - 20) / 120), fGreat = smooth01((dStart - GREAT_START_KM) / 120);
    let mt = 0, win = -1;
    for (let qa = 0; qa < near.length; qa++) for (let qb = qa + 1; qb < near.length; qb++) {
      const a = Math.min(near[qa]!, near[qb]!), b = Math.max(near[qa]!, near[qb]!), pc = pairConv[a * K + b]!;
      if (pc <= 0) continue;
      const gKm = ((dk[a]! + dk[b]! - 2 * d1) / 2) * CELL_KM;
      const k = a * K + b;
      const modest = (): number => pc * pairH[k]! * ((1 - FOOT_SHARE) * crestMod * bump(gKm / (CREST * widthKm)) + FOOT_SHARE * bump(gKm / (FOOT * widthKm)));
      let m: number;
      if (greatPair[k] && fGreat > 0) {
        // ridgelines: a triangle wave across the range (continuous), its phase meandering; peaks and passes along them
        const ph = gKm / RIDGE_KM + 1.6 * (phaseN.at(x, z) - 0.5);
        const f = ph - Math.floor(ph), tri = 1 - Math.abs(2 * f - 1);
        const pk = peakN.at(x, z), ps = passN.at(x, z);
        const pass = 0.12 + 0.88 * smooth01((ps - 0.32) / 0.36);
        const ridge = Math.pow(0.2 + 0.8 * tri, 1.5) * (0.45 + 0.55 * smooth01((pk - 0.3) / 0.45));
        m = G_BASE * bump(gKm / halfG) + G_RIDGE * pass * ridge * bump(gKm / (0.82 * halfG)) + 0.18 * G_BASE * bump(gKm / (1.7 * halfG));
        if (fGreat < 1) m = modest() + (m - modest()) * fGreat;
      } else if (midH[k]! > 0 && fMid > 0) {
        m = midC[k]! * midH[k]! * ((1 - FOOT_SHARE) * crestMod * bump(gKm / (CREST * widthKm)) + FOOT_SHARE * bump(gKm / (FOOT * widthKm)));
        if (fMid < 1) m = modest() + (m - modest()) * fMid;
      } else m = modest();
      if (m > mt) { mt = m; win = k; }
    }
    const hills = hillsN.at(x, z);
    if (land[c]) {
      mount[c] = mt;
      if (mt > 150) range[c] = win;
      let e = 20 + 380 * (1 - Math.exp(-ds[c]! / 35)) * (0.4 + hills) + 260 * Math.max(hills - 0.4, 0) + mt * Math.min(1, 0.35 + ds[c]! / 12);
      // (the highest summits eased under 7,000 m)
      if (e > 6000) e = 6000 + 1000 * Math.tanh((e - 6000) / 1000);
      elev[c] = Math.max(2, e);
    } else {
      elev[c] = Math.min(-5, -(80 + 4200 * (1 - Math.exp(-dl[c]! / 30))) * (0.75 + 0.5 * hills));
    }
  }
  // no land step steeper than MAX_STEP_M between neighbours (the great ranges' faces: the peaks become pyramids, the
  // ridgelines keep their line; everywhere else this changes nothing)
  limitSlope(land, elev, MAX_STEP_M);
  const ranges = measureRanges(K, cls, range, elev, land, owner);
  return { seed, land, elev, owner, cores, mass, masses, distSea: ds, distLand: dl, mount, range, rangeCls: cls.cls, ranges, plates: K, thresholdTries: tries };
}

function measureMasses(mass: Int32Array, count: number, owner: Uint8Array): Landmass[] {
  // centroid on the circle, then the principal axes' extents (an oriented bounding box)
  const sx = new Float64Array(count + 1), sy = new Float64Array(count + 1), sz = new Float64Array(count + 1), cnt = new Float64Array(count + 1);
  const cores = new Map<number, Map<number, number>>();
  for (let c = 0; c < N; c++) {
    const m = mass[c]!;
    if (!m) continue;
    const i = c % W, j = (c / W) | 0;
    const a = (2 * Math.PI * (i + 0.5)) / W;
    sx[m] += Math.cos(a); sy[m] += Math.sin(a); sz[m] += j + 0.5; cnt[m]++;
    let mm = cores.get(m); if (!mm) { mm = new Map(); cores.set(m, mm); }
    mm.set(owner[c]!, (mm.get(owner[c]!) ?? 0) + 1);
  }
  const cx = new Float64Array(count + 1), cz = new Float64Array(count + 1);
  for (let m = 1; m <= count; m++) {
    const ang = Math.atan2(sy[m]!, sx[m]!);
    cx[m] = (((ang / (2 * Math.PI)) * W) % W + W) % W;
    cz[m] = sz[m]! / cnt[m]!;
  }
  const cxx = new Float64Array(count + 1), czz = new Float64Array(count + 1), cxz = new Float64Array(count + 1);
  for (let c = 0; c < N; c++) {
    const m = mass[c]!;
    if (!m) continue;
    const dx = wrapDx(c % W + 0.5 - cx[m]!), dz = ((c / W) | 0) + 0.5 - cz[m]!;
    cxx[m] += dx * dx; czz[m] += dz * dz; cxz[m] += dx * dz;
  }
  const axes: [number, number][] = [];
  for (let m = 0; m <= count; m++) {
    const a = cxx[m]!, b = cxz[m]!, d = czz[m]!;
    const th = 0.5 * Math.atan2(2 * b, a - d);
    axes.push([Math.cos(th), Math.sin(th)]);
  }
  const lo1 = new Float64Array(count + 1).fill(Infinity), hi1 = new Float64Array(count + 1).fill(-Infinity);
  const lo2 = new Float64Array(count + 1).fill(Infinity), hi2 = new Float64Array(count + 1).fill(-Infinity);
  for (let c = 0; c < N; c++) {
    const m = mass[c]!;
    if (!m) continue;
    const dx = wrapDx(c % W + 0.5 - cx[m]!), dz = ((c / W) | 0) + 0.5 - cz[m]!;
    const [ux, uz] = axes[m]!;
    const p = dx * ux + dz * uz, q = -dx * uz + dz * ux;
    if (p < lo1[m]!) lo1[m] = p; if (p > hi1[m]!) hi1[m] = p;
    if (q < lo2[m]!) lo2[m] = q; if (q > hi2[m]!) hi2[m] = q;
  }
  const out: Landmass[] = [];
  for (let m = 1; m <= count; m++) {
    const areaKm2 = cnt[m]! * CELL_KM2;
    const e1 = (hi1[m]! - lo1[m]! + 1) * CELL_KM, e2 = (hi2[m]! - lo2[m]! + 1) * CELL_KM;
    let core = -1, best = 0;
    for (const [k, v] of cores.get(m) ?? []) if (v > best) { best = v; core = k; }
    out.push({
      id: m, kind: areaKm2 >= CONTINENT_KM2 ? 'continent' : areaKm2 >= BIG_ISLAND_KM2 ? 'big island' : 'island', cells: cnt[m]!, areaKm2: Math.round(areaKm2),
      cx: cx[m]!, cz: cz[m]!, longKm: Math.round(Math.max(e1, e2)), shortKm: Math.round(Math.min(e1, e2)), core: areaKm2 >= CONTINENT_KM2 ? core : -1,
    });
  }
  return out;
}

const smooth01 = (t: number): number => { const u = t < 0 ? 0 : t > 1 ? 1 : t; return u * u * (3 - 2 * u); };

interface RangeClasses { cls: Uint8Array; score: Float64Array; great: number[] }

/** Scores every converging plate pair's boundary by the land along it and picks the great and middle ranges. */
function classifyRanges(seed: string, K: number, px: number[], pz: number[], conv: Float64Array, warpX: Float32Array, warpZ: Float32Array,
  land: Uint8Array, owner: Uint8Array, start: Landmass, n: number): RangeClasses {
  const score = new Float64Array(K * K), byCore = new Float64Array(n * K * K);
  for (let c = 0; c < N; c++) {
    if (!land[c]) continue;
    const wx = warpX[c]!, wz = warpZ[c]!;
    let d1 = Infinity, d2 = Infinity, k1 = 0, k2 = 0;
    for (let k = 0; k < K; k++) {
      const d = Math.hypot(wrapDx(wx - px[k]!), wz - pz[k]!);
      if (d < d1) { d2 = d1; k2 = k1; d1 = d; k1 = k; } else if (d < d2) { d2 = d; k2 = k; }
    }
    if (((d2 - d1) / 2) * CELL_KM > 20) continue;
    const a = Math.min(k1, k2), b = Math.max(k1, k2), pc = conv[a * K + b]!;
    if (pc <= 0) continue;
    const i = c % W, j = (c / W) | 0;
    const dStart = Math.hypot(wrapDx(i + 0.5 - start.cx), j + 0.5 - start.cz) * CELL_KM;
    if (dStart >= MIDDLE_START_KM) byCore[owner[c]! * K * K + a * K + b] += pc;
    if (dStart >= GREAT_START_KM) score[a * K + b] += pc;
  }
  const cls = new Uint8Array(K * K);
  const pairs: number[] = [];
  for (let a = 0; a < K; a++) for (let b = a + 1; b < K; b++) if (conv[a * K + b]! > 0) { pairs.push(a * K + b); cls[a * K + b] = RANGE_MODEST; }
  pairs.sort((p, q) => score[q]! - score[p]! || p - q);
  const great: number[] = [];
  // the strongest, and the second when it is nearly as strong (1-2 on the planet)
  if (pairs.length && score[pairs[0]!]! > 40) great.push(pairs[0]!);
  if (pairs.length > 1 && score[pairs[1]!]! >= 0.6 * score[pairs[0]!]! && score[pairs[1]!]! > 40) great.push(pairs[1]!);
  for (const k of great) cls[k] = RANGE_GREAT;
  // each continent's next one to three strongest boundaries are its middle ranges
  for (let o = 0; o < n; o++) {
    const mine = pairs.filter((k) => cls[k] === RANGE_MODEST && byCore[o * K * K + k]! > 12).sort((p, q) => byCore[o * K * K + q]! - byCore[o * K * K + p]! || p - q);
    const take = Math.min(mine.length, 1 + Math.floor(hashU(hashStr(`${seed}|middle`), o) * 2));
    for (let q = 0; q < take; q++) cls[mine[q]!] = RANGE_MIDDLE;
  }
  return { cls, score, great };
}

/** Lowers land cells so no land neighbour pair differs by more than [step] per cell of distance (a min-plus cone
 * envelope from the lowest cells up: exact, and a no-op where the ground is already gentler). */
function limitSlope(land: Uint8Array, elev: Float32Array, step: number): void {
  const heap = new Heap(1 << 18);
  const done = new Uint8Array(N);
  for (let c = 0; c < N; c++) if (land[c]) heap.push(elev[c]!, c);
  while (heap.size) {
    const c = heap.pop();
    if (done[c]) continue;
    done[c] = 1;
    const e = elev[c]!;
    for (let k = 0; k < 8; k++) {
      const m = nb(c, k);
      if (m < 0 || !land[m] || done[m]) continue;
      const cap = e + step * N8[k]![2];
      if (elev[m]! > cap) { elev[m] = cap; heap.push(cap, m); }
    }
  }
}

function measureRanges(K: number, rc: RangeClasses, range: Int16Array, elev: Float32Array, land: Uint8Array, owner: Uint8Array): RangeInfo[] {
  const out = new Map<number, RangeInfo>();
  const owners = new Map<number, Map<number, number>>();
  for (let c = 0; c < N; c++) {
    const k = range[c]!;
    if (k < 0 || !land[c]) continue;
    let r = out.get(k);
    if (!r) {
      const a = Math.floor(k / K), b = k % K;
      r = { id: `r:${a}-${b}`, pair: k, cls: RANGE_CLASS[rc.cls[k]!] as RangeInfo['cls'], score: Math.round(rc.score[k]!), cells: 0, peakCell: c, peakM: 0, core: -1 };
      out.set(k, r);
    }
    if (elev[c]! > 500) r.cells++;
    if (elev[c]! > r.peakM) { r.peakM = Math.round(elev[c]!); r.peakCell = c; }
    let o = owners.get(k); if (!o) { o = new Map(); owners.set(k, o); }
    o.set(owner[c]!, (o.get(owner[c]!) ?? 0) + 1);
  }
  for (const [k, o] of owners) { let best = 0; for (const [core, v] of o) if (v > best) { best = v; out.get(k)!.core = core; } }
  const order = { great: 0, middle: 1, modest: 2 };
  return [...out.values()].filter((r) => r.cls).sort((p, q) => order[p.cls] - order[q.cls] || q.score - p.score || p.pair - q.pair);
}
