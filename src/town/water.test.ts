import { describe, expect, it, vi } from 'vitest';
import { nearRoad, planTown, type Rect } from './plan';
import { hitsWater, isWater, onBridge } from './water';

const SEEDS = Array.from({ length: 40 }, (_, i) => `water${i}`);
const overlap = (a: Rect, b: Rect): boolean =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const withWater = SEEDS.filter((s) => planTown(s).water);

describe('water (river / lake)', () => {
  it('is a pure function of the seed (fresh module state gives the same plan)', async () => {
    const before = SEEDS.slice(0, 6).map((s) => JSON.stringify(planTown(s).water));
    vi.resetModules();
    const fresh = await import('./plan');
    expect(SEEDS.slice(0, 6).map((s) => JSON.stringify(fresh.planTown(s).water))).toEqual(before);
  });

  it('differs per seed, and some seeds have none', () => {
    expect(withWater.length).toBeGreaterThanOrEqual(10);
    expect(withWater.length).toBeLessThan(SEEDS.length);
    const kinds = new Set(withWater.map((s) => planTown(s).water!.kind));
    expect(kinds).toEqual(new Set(['river', 'lake']));
    const sigs = new Set(withWater.map((s) => JSON.stringify(Array.from(planTown(s).water!.mask.entries()).filter(([, v]) => v).map(([i]) => i))));
    expect(sigs.size).toBe(withWater.length);
  });

  it('is believable: rivers span the town edge to edge, 3-6 wide; lakes are sizable', () => {
    for (const s of withWater) {
      const p = planTown(s);
      const w = p.water!;
      if (w.kind === 'river') {
        let minW = 99, maxW = 0;
        for (let y = 0; y < w.height; y++) {
          let n = 0;
          for (let x = 0; x < w.width; x++) n += w.raw[y * w.width + x]!;
          expect(n, `${s} river missing at row ${y}`).toBeGreaterThan(0);
          minW = Math.min(minW, n);
          maxW = Math.max(maxW, n);
        }
        expect(minW, s).toBeGreaterThanOrEqual(3);
        expect(maxW, s).toBeLessThanOrEqual(12); // meanders widen a few rows (a sideways bend spans a diagonal)
      } else {
        expect(w.mask.reduce((a, b) => a + b, 0), s).toBeGreaterThan(80);
      }
    }
  });

  it('never overlaps plots, the plaza, props or roads', () => {
    for (const s of withWater) {
      const p = planTown(s);
      const w = p.water!;
      for (const c of p.candidates) expect(hitsWater(w, c, 0), `${s} ${c.id} in water`).toBe(false);
      expect(hitsWater(w, p.plaza, 0), `${s} plaza`).toBe(false);
      for (const prop of p.props) {
        const tx = Math.floor(prop.x / 32), ty = Math.floor((prop.y - 1) / 32);
        expect(isWater(w, tx, ty), `${s} ${prop.kind} in water at ${tx},${ty}`).toBe(false);
      }
      for (let y = 0; y < p.height; y++)
        for (let x = 0; x < p.width; x++) if (isWater(w, x, y)) expect(nearRoad(p.roads, x, y), `${s} water on road ${x},${y}`).toBe(false);
    }
  });

  it('bridges every street that meets the water, so roads stay connected', () => {
    for (const s of withWater) {
      const p = planTown(s);
      const w = p.water!;
      expect(w.bridges.length, s).toBeGreaterThanOrEqual(1);
      for (const b of w.bridges) {
        const road = p.roads.find((r) => r.horizontal === b.horizontal && overlap(b, { x: r.x - 1, y: r.y - 1, w: r.w + 2, h: r.h + 2 }));
        expect(road, `${s} bridge ${JSON.stringify(b)} carries no street`).toBeTruthy();
      }
      // Every street tile (road or sidewalk) that touches the water is on a deck.
      for (const r of p.roads)
        for (let y = r.y - 1; y < r.y + r.h + 1; y++)
          for (let x = r.x - 1; x < r.x + r.w + 1; x++) {
            if (x < 0 || y < 0 || x >= p.width || y >= p.height || !w.raw[y * p.width + x]) continue;
            expect(onBridge(w, x, y), `${s} street under water at ${x},${y}`).toBe(true);
          }
    }
  });

  it('keeps capacity for all shops and homes with water in the way', () => {
    for (const s of withWater) {
      const p = planTown(s);
      const n = (k: string): number => p.candidates.filter((c) => c.kind === k).length;
      expect(n('shop'), `${s} shops`).toBeGreaterThanOrEqual(22);
      expect(n('villa'), `${s} villas`).toBeGreaterThanOrEqual(6);
      expect(n('small') + n('medium') + n('house') + 3 * n('apartment'), `${s} homes`).toBeGreaterThanOrEqual(140);
    }
  });
});
