import { describe, expect, it } from 'vitest';
import { H, W } from './grid';
import { LazyFbm, LazyLattice, NEAR, NearTerrain } from './near';
import { Fbm, Lattice } from './noise';

// a made-up skeleton height grid: a plain rising to a range in the east
const elev = new Int16Array(W * H);
for (let j = 0; j < H; j++) for (let i = 0; i < W; i++) elev[j * W + i] = Math.min(3000, Math.max(-200, (i - 900) * 40 + 300));

describe('W5a near terrain (near.ts)', () => {
  it('hashes the same lattice values as noise.ts tables them', () => {
    const a = new Lattice('alpha', 'k', 32), b = new LazyLattice('alpha', 'k', 32);
    const f = new Fbm('alpha', 'f', 16, 3), g = new LazyFbm('alpha', 'f', 16, 3);
    for (const [x, z] of [[0, 0], [17.3, 400.25], [1855.9, 927.5], [-3.5, 10]] as const) {
      expect(b.at(x, z)).toBe(a.at(x, z));
      expect(g.at(x, z)).toBe(f.at(x, z));
    }
  });

  it('is deterministic and terraces only where steep', () => {
    const t1 = new NearTerrain('alpha', elev), t2 = new NearTerrain('alpha', elev);
    let cliffs = 0, flats = 0;
    for (let k = 0; k < 400; k++) {
      const cx = 880 + k * 0.137, cz = 300 + (k % 37) * 0.21;
      const raw = t1.raw(cx, cz);
      expect(t2.raw(cx, cz)).toBe(raw);
      if (t1.slope(cx, cz) <= NEAR.CLIFF_FROM) { expect(raw).toBe(t1.smooth(cx, cz)); flats++; } else cliffs++;
      // (the terracing moves the ground by less than a step)
      expect(Math.abs(raw - t1.smooth(cx, cz))).toBeLessThan(NEAR.STEP_M + NEAR.STEP_SWING_M);
    }
    expect(flats).toBeGreaterThan(0);
    expect(cliffs).toBeGreaterThan(0);
  });

  it('is continuous: neighbouring tiles differ by a bounded step', () => {
    const t = new NearTerrain('alpha', elev);
    const e = 2.1 / 1075.2;
    let worst = 0;
    for (let k = 0; k < 3000; k++) {
      const cx = 905 + k * e, cz = 300.4;
      worst = Math.max(worst, Math.abs(t.raw(cx + e, cz) - t.raw(cx, cz)));
    }
    // (a riser is at most about 2.5 times the steepest slope's rise over a tile)
    expect(worst).toBeLessThan(12);
  });
});
