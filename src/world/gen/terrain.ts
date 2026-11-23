import { CELL_KM, CELL_KM2, H, N, W, components, distanceTo, latOf, wrapDx } from './grid';
import { Fbm, Lattice, stream } from './noise';

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
  for (let k = 0; k < K * K; k++) maxh.push(1500 + 1700 * r());
  const rwN = new Lattice(seed, 'rw', 10), ridgeN = new Fbm(seed, 'ridge', 24, 5), hillsN = new Fbm(seed, 'hills', 16, 7);
  const elev = new Float32Array(N);
  const mount = new Float32Array(N);
  for (let j = 0; j < H; j++) for (let i = 0; i < W; i++) {
    const c = j * W + i, x = i + 0.5, z = j + 0.5;
    const wx = warpX[c]!, wz = warpZ[c]!;
    let d1 = Infinity, d2 = Infinity, p1 = 0, p2 = 0;
    for (let k = 0; k < K; k++) {
      const d = Math.hypot(wrapDx(wx - px[k]!), wz - pz[k]!);
      if (d < d1) { d2 = d1; p2 = p1; d1 = d; p1 = k; } else if (d < d2) { d2 = d; p2 = k; }
    }
    const bdKm = ((d2 - d1) / 2) * CELL_KM;
    const ddx = wrapDx(px[p2]! - px[p1]!), ddz = pz[p2]! - pz[p1]!, nn = Math.hypot(ddx, ddz) + 1e-6;
    const conv = ((vx[p1]! - vx[p2]!) * ddx + (vz[p1]! - vz[p2]!) * ddz) / nn;
    const widthKm = 11 + 17 * rwN.at(x, z);
    const rd = 1 - Math.abs(2 * ridgeN.at(x, z) - 1);
    const mt = Math.min(Math.max(conv, 0), 1.0) * Math.exp(-(bdKm / widthKm) * (bdKm / widthKm)) * maxh[Math.min(p1, p2) * K + Math.max(p1, p2)]! * (0.3 + 0.7 * rd * rd);
    const hills = hillsN.at(x, z);
    if (land[c]) {
      mount[c] = mt;
      const e = 20 + 380 * (1 - Math.exp(-ds[c]! / 35)) * (0.4 + hills) + 260 * Math.max(hills - 0.4, 0) + mt * Math.min(1, 0.35 + ds[c]! / 12);
      elev[c] = Math.max(2, e);
    } else {
      elev[c] = Math.min(-5, -(80 + 4200 * (1 - Math.exp(-dl[c]! / 30))) * (0.75 + 0.5 * hills));
    }
  }
  return { seed, land, elev, owner, cores, mass, masses, distSea: ds, distLand: dl, mount, thresholdTries: tries };
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
