// The near terrain's height (WORLD_GEN.md phase W5a), the cross-language part: the same doubles as the Kotlin
// WorldTerrain.smooth / slope / raw (kotlin-street/shared/src/main/kotlin/domain/WorldTerrain.kt), from the baked
// skeleton's heights and the integer-hashed noise. tools/world-fixture.ts writes samples of it for the Kotlin test.
// Only +, -, *, / and Math.floor/abs/sqrt are used (no exp, pow or trig), so both languages give the same bits.
import { H, W } from './grid';
import { hashStr, hashU } from './noise';

/** The atlas cell (m) as the literal Kotlin's Planet.CELL_M is (grid.ts's product of 2.1 is not bit for bit the same double). */
const CELL_M = 1075.2;

const fade = (t: number): number => t * t * t * (t * (t * 6 - 15) + 10);
const sat = (t: number): number => (t < 0 ? 0 : t > 1 ? 1 : t);
const smoothstep = (t: number): number => t * t * (3 - 2 * t);

/**
 * noise.ts's Lattice, its values hashed when asked rather than tabled (the near terrain's lattices are planet-sized at
 * metres apart: a table would be gigabytes). The same values, as Kotlin's WorldLattice.
 */
export class LazyLattice {
  readonly nx: number;
  readonly ny: number;
  private readonly s: number;
  private readonly h: number;
  constructor(seed: string, key: string, nx: number) {
    this.nx = nx;
    this.ny = Math.ceil((H / W) * nx) + 3;
    this.s = nx / W;
    this.h = hashStr(`${seed}|noise|${key}|${nx}`);
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
    const a = hashU(this.h, i0, j0), b = hashU(this.h, i1, j0), c = hashU(this.h, i0, j0 + 1), d = hashU(this.h, i1, j0 + 1);
    const top = a + (b - a) * fu, bot = c + (d - c) * fu;
    return top + (bot - top) * fv;
  }
}

/** noise.ts's Fbm over [LazyLattice]s. */
export class LazyFbm {
  private readonly oct: LazyLattice[] = [];
  private readonly amp: number[] = [];
  private readonly tot: number;
  constructor(seed: string, key: string, nx: number, octaves: number, gain = 0.5) {
    let a = 1, t = 0;
    for (let o = 0; o < octaves; o++) { this.oct.push(new LazyLattice(seed, `${key}${o}`, nx * 2 ** o)); this.amp.push(a); t += a; a *= gain; }
    this.tot = t;
  }
  at(x: number, z: number): number {
    let s = 0;
    for (let o = 0; o < this.oct.length; o++) s += this.amp[o]! * this.oct[o]!.at(x, z);
    return s / this.tot;
  }
}

/** The constants of the cliffs (WorldTerrain's companion). */
export const NEAR = { CLIFF_FROM: 0.7, CLIFF_FULL: 1.0, STEP_M: 7.0, STEP_SWING_M: 6.0, TREAD: 0.62, TREAD_RISE: 0.1, SLOPE_E_M: 1.05 } as const;

export class NearTerrain {
  private readonly detail: LazyFbm;
  private readonly hills: LazyFbm;
  private readonly ridge: LazyFbm;
  private readonly rough: LazyFbm;
  private readonly steps: LazyLattice;
  constructor(seed: string, private readonly elev: Int16Array) {
    this.detail = new LazyFbm(seed, 'w4-detail', W / 2, 3);
    this.hills = new LazyFbm(seed, 'w5-hills', W * 4, 4);
    this.ridge = new LazyFbm(seed, 'w5-ridge', W * 2, 3);
    this.rough = new LazyFbm(seed, 'w5-rough', W / 2, 2);
    this.steps = new LazyLattice(seed, 'w5-step', W * 8);
  }

  /** The skeleton's height (m) at (x, z) in cells: bilinear between the cells' centres (WorldSkeleton.height). */
  height(x: number, z: number): number {
    const u = x - 0.5;
    let v = z - 0.5; if (v < 0) v = 0; else if (v > H - 1) v = H - 1;
    const i = Math.floor(u);
    let j = Math.floor(v); if (j > H - 2) j = H - 2;
    const fu = u - i, fv = v - j;
    const at = (ii: number, jj: number): number => this.elev[jj * W + (((ii % W) + W) % W)]!;
    const a = at(i, j), b = at(i + 1, j), c = at(i, j + 1), d = at(i + 1, j + 1);
    return (a + (b - a) * fu) + ((c + (d - c) * fu) - (a + (b - a) * fu)) * fv;
  }

  /** The ground before cliffs (m above sea level): WorldTerrain.smooth. */
  smooth(cx: number, cz: number): number {
    const base = this.height(cx, cz);
    const coast = smoothstep(sat((base - 1.0) / 24.0));
    const w4 = (this.detail.at(cx, cz) - 0.5) * 36.0 * coast;
    const ro = this.rough.at(cx, cz);
    const r = sat((ro - 0.4) / 0.3);
    const crop = smoothstep(sat((ro - 0.62) / 0.12));
    const mount = smoothstep(sat((base - 450.0) / 900.0));
    const hil = (this.hills.at(cx, cz) - 0.5) * (10.0 + 26.0 * r);
    const rv = this.ridge.at(cx, cz);
    const ra = 2.0 * rv - 1.0;
    // (a ridge's crest rounded over a few metres, so the slope, and the cliffs it makes, do not jump there)
    let rr = 1.0 - Math.sqrt(ra * ra + 0.003);
    rr *= rr;
    const peaks = rr * (320.0 * mount + 160.0 * crop);
    return base + w4 + (hil + peaks) * coast;
  }

  slope(cx: number, cz: number): number {
    const e = NEAR.SLOPE_E_M / CELL_M;
    const dx = (this.smooth(cx + e, cz) - this.smooth(cx - e, cz)) / (2.0 * NEAR.SLOPE_E_M);
    const dz = (this.smooth(cx, cz + e) - this.smooth(cx, cz - e)) / (2.0 * NEAR.SLOPE_E_M);
    return Math.sqrt(dx * dx + dz * dz);
  }

  cliff(cx: number, cz: number): number {
    return smoothstep(sat((this.slope(cx, cz) - NEAR.CLIFF_FROM) / (NEAR.CLIFF_FULL - NEAR.CLIFF_FROM)));
  }

  /** The ground with its cliffs (m above sea level): WorldTerrain.raw. */
  raw(cx: number, cz: number): number {
    const h0 = this.smooth(cx, cz);
    const c = this.cliff(cx, cz);
    if (c <= 0) return h0;
    const step = NEAR.STEP_M + NEAR.STEP_SWING_M * this.steps.at(cx, cz);
    const t = h0 / step;
    const k = Math.floor(t);
    const f = t - k;
    const g = f < NEAR.TREAD ? (f * NEAR.TREAD_RISE) / NEAR.TREAD : NEAR.TREAD_RISE + ((f - NEAR.TREAD) * (1.0 - NEAR.TREAD_RISE)) / (1.0 - NEAR.TREAD);
    return h0 + ((k + g) * step - h0) * c;
  }
}
