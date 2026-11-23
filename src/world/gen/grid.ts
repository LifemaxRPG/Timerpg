// THE ATLAS GRID (kotlin-street/docs/WORLD_GEN.md section 1): the planet-wide skeleton grid, one cell per 2 chunks
// (1,075.2 m), 1,856 cells round (x wraps east-west: the planet is a cylinder) and 928 pole to pole (z does not wrap:
// the ice caps are the borders). Cell (i, j) covers x in [i, i+1) and z in [j, j+1) cell units; its centre is
// (i + 0.5, j + 0.5). A cell's index is j * W + i.

/** Tiles of 2.1 m in a chunk, chunks in an atlas cell. */
export const TILE_M = 2.1;
export const CHUNK_TILES = 256;
export const CHUNKS_PER_CELL = 2;
/** Metres and km across an atlas cell. */
export const CELL_M = TILE_M * CHUNK_TILES * CHUNKS_PER_CELL; // 1075.2
export const CELL_KM = CELL_M / 1000;
/** Cells round the planet (x, wraps) and pole to pole (z). */
export const W = 1856;
export const H = 928;
export const N = W * H;
/** The planet's circumference and pole-to-pole length in metres. */
export const CIRC_M = W * CELL_M;
export const POLE_M = H * CELL_M;
export const CELL_KM2 = CELL_KM * CELL_KM;

/** The wrapped difference x1 - x0 in cell units, in [-W/2, W/2). */
export const wrapDx = (dx: number): number => {
  let d = dx % W;
  if (d < -W / 2) d += W;
  else if (d >= W / 2) d -= W;
  return d;
};
export const wrapX = (x: number): number => ((x % W) + W) % W;

/** Distance in km between two points given in cell units (x wraps). */
export const distKm = (x0: number, z0: number, x1: number, z1: number): number => Math.hypot(wrapDx(x1 - x0), z1 - z0) * CELL_KM;

/** Latitude in degrees of a z in cell units (cell centres at j + 0.5): -90 north pole, +90 south pole. */
export const latOf = (z: number): number => (z / H) * 180 - 90;

export const idx = (i: number, j: number): number => j * W + wrapX(i);
export const cellI = (c: number): number => c % W;
export const cellJ = (c: number): number => (c / W) | 0;

/** The 8 neighbours: dx, dz and step length (cells). */
export const N8: readonly (readonly [number, number, number])[] = [
  [-1, -1, Math.SQRT2], [0, -1, 1], [1, -1, Math.SQRT2], [-1, 0, 1], [1, 0, 1], [-1, 1, Math.SQRT2], [0, 1, 1], [1, 1, Math.SQRT2],
];

/** The index of neighbour k of cell c, or -1 past the poles. */
export function nb(c: number, k: number): number {
  const j = (c / W) | 0, i = c - j * W;
  const d = N8[k]!;
  const jj = j + d[1];
  if (jj < 0 || jj >= H) return -1;
  let ii = i + d[0];
  if (ii < 0) ii += W; else if (ii >= W) ii -= W;
  return jj * W + ii;
}

/** A min-heap of (key, value) pairs in typed arrays (priority-flood, Dijkstra, A*). */
export class Heap {
  private k: Float64Array;
  private v: Int32Array;
  size = 0;
  constructor(cap = 1 << 16) { this.k = new Float64Array(cap); this.v = new Int32Array(cap); }
  clear(): void { this.size = 0; }
  push(key: number, val: number): void {
    if (this.size === this.k.length) {
      const k = new Float64Array(this.k.length * 2); k.set(this.k); this.k = k;
      const v = new Int32Array(this.v.length * 2); v.set(this.v); this.v = v;
    }
    let i = this.size++;
    const K = this.k, V = this.v;
    while (i > 0) {
      const p = (i - 1) >> 1;
      // (ties broken by the value, so the order never depends on insertion history in a way another port could not copy)
      if (K[p]! < key || (K[p] === key && V[p]! <= val)) break;
      K[i] = K[p]!; V[i] = V[p]!; i = p;
    }
    K[i] = key; V[i] = val;
  }
  /** Pops the smallest; its key is in [lastKey]. */
  lastKey = 0;
  pop(): number {
    const K = this.k, V = this.v;
    const top = V[0]!; this.lastKey = K[0]!;
    const n = --this.size;
    if (n > 0) {
      const key = K[n]!, val = V[n]!;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1;
        if (l >= n) break;
        let m = l;
        const r = l + 1;
        if (r < n && (K[r]! < K[l]! || (K[r] === K[l] && V[r]! < V[l]!))) m = r;
        if (K[m]! > key || (K[m] === key && V[m]! >= val)) break;
        K[i] = K[m]!; V[i] = V[m]!; i = m;
      }
      K[i] = key; V[i] = val;
    }
    return top;
  }
}

/** Exact squared Euclidean distance transform (Felzenszwalb) to the cells where [feature] is true, x wrapping; result in
 * cells (not squared). */
export function distanceTo(feature: (c: number) => boolean): Float32Array {
  const INF = 1e12;
  const g = new Float64Array(N);
  // columns (z, no wrap)
  const f = new Float64Array(Math.max(W * 3, H));
  const d = new Float64Array(Math.max(W * 3, H));
  const v = new Int32Array(Math.max(W * 3, H));
  const zz = new Float64Array(Math.max(W * 3, H) + 1);
  const dt1 = (n: number): void => {
    let k = 0; v[0] = 0; zz[0] = -INF; zz[1] = INF;
    for (let q = 1; q < n; q++) {
      let s = ((f[q]! + q * q) - (f[v[k]!]! + v[k]! * v[k]!)) / (2 * q - 2 * v[k]!);
      while (s <= zz[k]!) { k--; s = ((f[q]! + q * q) - (f[v[k]!]! + v[k]! * v[k]!)) / (2 * q - 2 * v[k]!); }
      k++; v[k] = q; zz[k] = s; zz[k + 1] = INF;
    }
    k = 0;
    for (let q = 0; q < n; q++) { while (zz[k + 1]! < q) k++; const dq = q - v[k]!; d[q] = dq * dq + f[v[k]!]!; }
  };
  for (let i = 0; i < W; i++) {
    for (let j = 0; j < H; j++) f[j] = feature(j * W + i) ? 0 : INF;
    dt1(H);
    for (let j = 0; j < H; j++) g[j * W + i] = d[j]!;
  }
  // rows (x wraps: three copies side by side, the middle one kept)
  const out = new Float32Array(N);
  for (let j = 0; j < H; j++) {
    for (let r = 0; r < 3; r++) for (let i = 0; i < W; i++) f[r * W + i] = g[j * W + i]!;
    dt1(3 * W);
    for (let i = 0; i < W; i++) out[j * W + i] = Math.sqrt(Math.min(d[W + i]!, INF));
  }
  return out;
}

/** Separable box blur (3 passes approximate a gaussian of sigma about [rx] and [rz] cells), x wrapping, z clamped. */
export function blur(src: Float32Array, rx: number, rz: number, passes = 3): Float32Array {
  let a = Float32Array.from(src), b = new Float32Array(N);
  const bx = Math.max(0, Math.round(Math.sqrt((12 * rx * rx) / passes + 1) / 2 - 0.5));
  const bz = Math.max(0, Math.round(Math.sqrt((12 * rz * rz) / passes + 1) / 2 - 0.5));
  for (let p = 0; p < passes; p++) {
    if (bx > 0) {
      const n = 2 * bx + 1;
      for (let j = 0; j < H; j++) {
        const row = j * W;
        let s = 0;
        for (let k = -bx; k <= bx; k++) s += a[row + wrapX(k)]!;
        for (let i = 0; i < W; i++) {
          b[row + i] = s / n;
          s += a[row + wrapX(i + bx + 1)]! - a[row + wrapX(i - bx)]!;
        }
      }
      [a, b] = [b, a];
    }
    if (bz > 0) {
      const n = 2 * bz + 1;
      for (let i = 0; i < W; i++) {
        let s = 0;
        for (let k = -bz; k <= bz; k++) s += a[Math.min(H - 1, Math.max(0, k)) * W + i]!;
        for (let j = 0; j < H; j++) {
          b[j * W + i] = s / n;
          s += a[Math.min(H - 1, j + bz + 1) * W + i]! - a[Math.max(0, j - bz) * W + i]!;
        }
      }
      [a, b] = [b, a];
    }
  }
  return a;
}

/** Connected components of the cells where [inSet] is true (8-neighbours, x wraps): labels from 1, 0 outside. */
export function components(inSet: (c: number) => boolean): { label: Int32Array; sizes: number[] } {
  const label = new Int32Array(N);
  const sizes: number[] = [0];
  const stack = new Int32Array(N);
  let n = 0;
  for (let c0 = 0; c0 < N; c0++) {
    if (label[c0] || !inSet(c0)) continue;
    n++;
    let sp = 0, size = 0;
    stack[sp++] = c0; label[c0] = n;
    while (sp) {
      const c = stack[--sp]!;
      size++;
      for (let k = 0; k < 8; k++) {
        const m = nb(c, k);
        if (m < 0 || label[m] || !inSet(m)) continue;
        label[m] = n; stack[sp++] = m;
      }
    }
    sizes.push(size);
  }
  return { label, sizes };
}
