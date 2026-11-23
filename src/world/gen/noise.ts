import { H, W } from './grid';

// INTEGER-HASHED NOISE (WORLD_GEN.md section 2): every random number of the skeleton comes from 32-bit integer hashes
// of the world seed, a key and lattice coordinates, using only operations a Kotlin port reproduces bit for bit
// (Math.imul, shifts, xor, then doubles for the interpolation in a fixed order). No tables, no global streams: a change
// in one feature never shifts the dice of another.

/** A 32-bit hash of a string (xmur3's mixing, as src/sim/rng.ts, finalised). */
export function hashStr(s: string): number {
  let h = 1779033703 ^ s.length;
  for (let i = 0; i < s.length; i++) {
    h = Math.imul(h ^ s.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  h = Math.imul(h ^ (h >>> 16), 2246822507);
  h = Math.imul(h ^ (h >>> 13), 3266489909);
  return (h ^ (h >>> 16)) >>> 0;
}

/** Hash of a 32-bit seed and up to three integers (murmur3's finaliser between the words). */
export function hash3(seed: number, a: number, b = 0, c = 0): number {
  let h = seed ^ Math.imul(a | 0, 0x9e3779b1);
  h = Math.imul(h ^ (h >>> 16), 0x85ebca6b);
  h ^= Math.imul(b | 0, 0xc2b2ae35);
  h = Math.imul(h ^ (h >>> 13), 0x27d4eb2f);
  h ^= Math.imul(c | 0, 0x165667b1);
  h = Math.imul(h ^ (h >>> 16), 0x85ebca6b);
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
  return (h ^ (h >>> 16)) >>> 0;
}

/** A float in [0, 1) from a seed and integers. */
export const hashU = (seed: number, a: number, b = 0, c = 0): number => hash3(seed, a, b, c) / 4294967296;

/** A keyed random stream (for placement loops): seeded from the world seed and the keys' hash. */
export function stream(seed: string, ...keys: (string | number)[]): () => number {
  const s = hashStr([seed, ...keys].join('|'));
  let n = 0;
  return () => hashU(s, n++);
}

const fade = (t: number): number => t * t * t * (t * (t * 6 - 15) + 10);

/**
 * Value noise on a lattice of [nx] cells round the planet (periodic in x), isotropic (the same lattice spacing in z),
 * sampled in atlas cell units. Values in [0, 1).
 */
export class Lattice {
  readonly nx: number;
  readonly ny: number;
  private readonly v: Float64Array;
  private readonly s: number;
  constructor(seed: string, key: string, nx: number) {
    this.nx = nx;
    this.ny = Math.ceil((H / W) * nx) + 3;
    this.s = nx / W;
    const h = hashStr(`${seed}|noise|${key}|${nx}`);
    this.v = new Float64Array(this.nx * this.ny);
    for (let j = 0; j < this.ny; j++) for (let i = 0; i < this.nx; i++) this.v[j * this.nx + i] = hashU(h, i, j);
  }
  at(x: number, z: number): number {
    const u = x * this.s, w = z * this.s + 1;
    const fu0 = Math.floor(u);
    let j0 = Math.floor(w);
    if (j0 < 0) j0 = 0; else if (j0 > this.ny - 2) j0 = this.ny - 2;
    const fu = fade(u - fu0);
    let tv = w - j0; if (tv < 0) tv = 0; else if (tv > 1) tv = 1;
    const fv = fade(tv);
    const nx = this.nx;
    const i0 = ((fu0 % nx) + nx) % nx, i1 = i0 + 1 === nx ? 0 : i0 + 1;
    const r0 = j0 * nx, r1 = r0 + nx;
    const a = this.v[r0 + i0]!, b = this.v[r0 + i1]!, c = this.v[r1 + i0]!, d = this.v[r1 + i1]!;
    const top = a + (b - a) * fu, bot = c + (d - c) * fu;
    return top + (bot - top) * fv;
  }
}

/** Fractal sum of value noise: octaves of [nx], 2nx, 4nx... lattice cells, normalised to [0, 1). */
export class Fbm {
  private readonly oct: Lattice[];
  private readonly amp: number[];
  private readonly tot: number;
  constructor(seed: string, key: string, nx: number, octaves: number, gain = 0.5) {
    this.oct = [];
    this.amp = [];
    let a = 1, t = 0;
    for (let o = 0; o < octaves; o++) { this.oct.push(new Lattice(seed, `${key}${o}`, nx * 2 ** o)); this.amp.push(a); t += a; a *= gain; }
    this.tot = t;
  }
  at(x: number, z: number): number {
    let s = 0;
    for (let o = 0; o < this.oct.length; o++) s += this.amp[o]! * this.oct[o]!.at(x, z);
    return s / this.tot;
  }
}
