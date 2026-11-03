import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { layoutTown } from './layout';
import { landmarkFootprint, LANDMARK_SPECS } from './landmarks';
import { planTown, type Rect } from './plan';

const overlap = (a: Rect, b: Rect): boolean =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const SEEDS = ['alpha', 'demo', 'zebra', 'quartz', 'maple', 'orbit', 'delta', 'nova', 'river', 'pine', 'ember', 'koala'];

describe('civic landmarks (plan)', () => {
  it('is deterministic', () => {
    expect(JSON.stringify(planTown('alpha').landmarks)).toBe(JSON.stringify(planTown('alpha').landmarks));
  });

  it('gives every town at least three landmarks, with sane sizes and opening years', () => {
    for (const seed of SEEDS) {
      const ls = planTown(seed).landmarks;
      expect(ls.length, seed).toBeGreaterThanOrEqual(3);
      for (const l of ls) {
        const s = LANDMARK_SPECS[l.id];
        expect([l.w, l.h]).toEqual([s.w, s.h]);
        expect(l.openYear).toBeGreaterThanOrEqual(s.open[0]);
        expect(l.openYear).toBeLessThanOrEqual(s.open[1]);
      }
    }
  });

  it('differs per seed (which landmarks, when they open, where they stand)', () => {
    const sig = (s: string): string => JSON.stringify(planTown(s).landmarks.map((l) => [l.id, l.openYear, l.x, l.y]));
    expect(new Set(SEEDS.map(sig)).size).toBe(SEEDS.length);
    const sets = new Set(SEEDS.map((s) => planTown(s).landmarks.map((l) => l.id).join()));
    expect(sets.size).toBeGreaterThanOrEqual(3);
    const years = new Set(SEEDS.flatMap((s) => planTown(s).landmarks.filter((l) => l.id === 'police').map((l) => l.openYear)));
    expect(years.size).toBeGreaterThanOrEqual(3);
  });

  it('never overlaps roads (with sidewalks), the plaza, plots or each other, and stays on the map', () => {
    for (const seed of SEEDS) {
      const p = planTown(seed);
      for (const l of p.landmarks) {
        expect(l.x, seed).toBeGreaterThanOrEqual(0);
        expect(l.y, seed).toBeGreaterThanOrEqual(0);
        expect(l.x + l.w, seed).toBeLessThanOrEqual(p.width);
        expect(l.y + l.h, seed).toBeLessThanOrEqual(p.height);
        for (const r of p.roads)
          expect(overlap(l, { x: r.x - 1, y: r.y - 1, w: r.w + 2, h: r.h + 2 }), `${seed} ${l.id} on road`).toBe(false);
        expect(overlap(l, p.plaza), `${seed} ${l.id} in plaza`).toBe(false);
        for (const c of p.candidates) expect(overlap(l, c), `${seed} ${l.id} vs ${c.id}`).toBe(false);
        for (const o of p.landmarks) if (o !== l) expect(overlap(l, o), `${seed} ${l.id} vs ${o.id}`).toBe(false);
      }
    }
  });

  it('keeps room for the whole town', () => {
    for (const seed of SEEDS) {
      const p = planTown(seed);
      const n = (k: string): number => p.candidates.filter((c) => c.kind === k).length;
      expect(n('shop'), seed).toBeGreaterThanOrEqual(22);
      expect(n('villa'), seed).toBeGreaterThanOrEqual(6);
      expect(n('small') + n('medium'), seed).toBeGreaterThanOrEqual(140);
    }
  });

  it('footprints lie inside the landmark', () => {
    for (const l of planTown('alpha').landmarks) {
      const f = landmarkFootprint(l);
      expect(f.y + f.h).toBe(l.y + l.h);
      expect(f.y).toBeGreaterThanOrEqual(l.y);
    }
  });
});

describe('civic landmarks (layout by year)', () => {
  const t = runTimeline({ seed: 'alpha', endYear: 2025 });

  it('only shows landmarks once world.year >= openYear', () => {
    const plan = planTown('alpha');
    for (const year of [1950, 1960, 1975, 1990, 2010, 2025]) {
      const l = layoutTown(worldAt(t, year));
      const expected = plan.landmarks.filter((x) => year >= x.openYear).map((x) => x.id);
      expect(l.landmarks.map((x) => x.id)).toEqual(expected);
    }
    const early = layoutTown(worldAt(t, 1950)).landmarks.length;
    const late = layoutTown(worldAt(t, 2025)).landmarks.length;
    expect(late).toBeGreaterThanOrEqual(early);
    expect(late).toBe(plan.landmarks.length);
  });

  it('leaves unopened landmark plots wooded and open ones clear', () => {
    const plan = planTown('alpha');
    const l = plan.landmarks.reduce((a, b) => (b.openYear > a.openYear ? b : a));
    expect(l.openYear).toBeGreaterThan(1950);
    const inside = (lay: ReturnType<typeof layoutTown>): number =>
      lay.props.filter((p) => p.x / 32 >= l.x && p.x / 32 <= l.x + l.w && p.y / 32 >= l.y && p.y / 32 <= l.y + l.h + 0.1).length;
    expect(inside(layoutTown(worldAt(t, l.openYear - 1)))).toBeGreaterThan(5);
    expect(inside(layoutTown(worldAt(t, l.openYear)))).toBe(0);
  });
});

describe('landmarks vs water', () => {
  it('never sit in or beside a river or lake', async () => {
    const { hitsWater } = await import('./water');
    const { planTown } = await import('./plan');
    let withWater = 0;
    for (let i = 0; i < 40; i++) {
      const p = planTown(`seed-${i}`);
      if (p.water) withWater++;
      for (const l of p.landmarks) expect(hitsWater(p.water, l, 1), `seed-${i} ${l.id} touches water`).toBe(false);
    }
    expect(withWater).toBeGreaterThan(10);
  });
});
