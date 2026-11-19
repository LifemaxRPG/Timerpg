import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { cropIn, fieldClear, fieldLives, GRAIN, HAY, PRODUCE, planFarms, RING, roadRect, YARD, type Developed } from './farms';
import { layoutTown } from './layout';
import { planTown, type Rect } from './plan';
import { hitsWater } from './water';

const overlap = (a: Rect, b: Rect, pad = 0): boolean => a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;
const wet = (seed: string, r: Rect, pad: number): boolean => {
  const p = planTown(seed);
  return hitsWater(p.water, { x: Math.floor(r.x), y: Math.floor(r.y), w: Math.ceil(r.w), h: Math.ceil(r.h) }, pad) || planFarms(p).water.some((w) => overlap(r, w, pad));
};
const SEEDS = ['gamma', 'eta', 'alpha'];

describe('farms: where', () => {
  it('is deterministic', () => {
    for (const seed of SEEDS) {
      const a = JSON.stringify(planFarms(planTown(seed)));
      expect(a.length).toBeGreaterThan(1000);
      // (a fresh plan from the same seed: the cache keyed by seed gives the same object, so compare the text)
      expect(JSON.stringify(planFarms(planTown(seed)))).toBe(a);
    }
  });

  it('rings every town with farms along its roads out and the section road', () => {
    for (const seed of SEEDS) {
      const fp = planFarms(planTown(seed));
      expect(fp.roads.filter((r) => r.kind === 'spoke').length, seed).toBeGreaterThanOrEqual(2);
      expect(fp.roads.filter((r) => r.kind === 'ring').length).toBe(4);
      expect(fp.farms.length, seed).toBeGreaterThanOrEqual(12);
      expect(fp.fields.length, seed).toBeGreaterThanOrEqual(fp.farms.length * 1.5);
      for (const f of fp.farms) {
        expect(f.things.some((t) => t.model.startsWith('fm_barn') && t.door)).toBe(true);
        expect(f.things.some((t) => t.model.startsWith('fm_house') && t.door)).toBe(true);
        expect(f.things.some((t) => t.model === 'fm_tractor')).toBe(true);
        expect(f.household.rural).toBe(true);
        expect(f.founded).toBeLessThan(1950);
      }
    }
  });

  it('keeps farmsteads outside everything the town will ever build, and the woods, water and each other', () => {
    for (const seed of SEEDS) {
      const plan = planTown(seed);
      const fp = planFarms(plan);
      const town = [...plan.roads, ...plan.candidates, ...plan.landmarks, plan.plaza];
      for (const f of fp.farms) {
        expect(f.rect.w * f.rect.h).toBe(YARD.w * YARD.d);
        for (const t of town) expect(overlap(f.rect, t, 3), `${seed} ${f.id}`).toBe(false);
        expect(wet(seed, f.rect, 3), `${seed} ${f.id} in the river`).toBe(false);
        for (const r of fp.roads) expect(overlap(f.rect, roadRect(r)), `${seed} ${f.id} on ${r.id}`).toBe(false);
        for (const g of fp.farms) if (g !== f) expect(overlap(f.rect, g.rect)).toBe(false);
        // inside the farmland, past the map's forest edge or in open map land
        expect(f.rect.x >= -RING && f.rect.y >= -RING && f.rect.x + f.rect.w <= plan.width + RING && f.rect.y + f.rect.h <= plan.height + RING).toBe(true);
        // every thing stands in its yard (the summer stand at its front edge, by the road)
        for (const t of f.things) expect(overlap({ x: t.x, y: t.y, w: 0, h: 0 }, f.rect, 1.5), `${seed} ${f.id} ${t.model}`).toBe(true);
      }
    }
  });

  it('lays fields clear of streets, landmarks, the plaza, water, roads, yards and each other', () => {
    for (const seed of SEEDS) {
      const plan = planTown(seed);
      const fp = planFarms(plan);
      for (const f of fp.fields) {
        for (const r of plan.roads) expect(overlap(f, r), `${seed} ${f.id} on a street`).toBe(false);
        for (const l of plan.landmarks) expect(overlap(f, l), `${seed} ${f.id} on ${l.id}`).toBe(false);
        expect(overlap(f, plan.plaza)).toBe(false);
        expect(wet(seed, f, 2), `${seed} ${f.id} in the river`).toBe(false);
        for (const r of fp.roads) expect(overlap(f, roadRect(r)), `${seed} ${f.id} on ${r.id}`).toBe(false);
        for (const y of fp.farms) expect(overlap(f, y.rect)).toBe(false);
        for (const g of fp.fields) if (g !== f) expect(overlap(f, g), `${f.id} ${g.id}`).toBe(false);
        expect(fp.farms.some((y) => y.id === f.farm)).toBe(true);
        // the gate is on the field's edge
        const onEdge = Math.abs(f.gate.x - f.x) < 1e-6 || Math.abs(f.gate.x - f.x - f.w) < 1e-6 || Math.abs(f.gate.y - f.y) < 1e-6 || Math.abs(f.gate.y - f.y - f.h) < 1e-6;
        expect(onEdge, f.id).toBe(true);
        for (const c of f.rotation) expect([...GRAIN, ...PRODUCE, HAY]).toContain(c);
      }
    }
  });

  it('grows corn and wheat in rotation, hay, and a truck farm\'s vegetables', () => {
    const fp = planFarms(planTown('gamma'));
    const crops = new Set(fp.fields.map((f) => cropIn(f, 2025)));
    expect(crops.has('corn') || crops.has('wheat')).toBe(true);
    expect(fp.fields.some((f) => f.use === 'hay')).toBe(true);
    expect(fp.fields.some((f) => f.use === 'truck')).toBe(true);
    const grain = fp.fields.find((f) => f.use === 'grain')!;
    expect(new Set([2020, 2021, 2022].map((y) => cropIn(grain, y))).size).toBeGreaterThan(1);
  });
});

describe('farms: by year', () => {
  const seed = 'gamma';
  const tl = runTimeline({ seed, endYear: 2025 });
  const dev = (y: number): Developed => { const L = layoutTown(worldAt(tl, y)); return { roads: L.roads, lots: [...L.lots, ...L.vacantLots], landmarks: L.landmarks }; };

  it('farms a field only while nothing is built on it', () => {
    for (const y of [1955, 1975, 2000, 2025]) {
      const L = layoutTown(worldAt(tl, y));
      for (const f of L.farms.fields) {
        for (const r of L.roads) expect(overlap(f, r), `${y} ${f.id}`).toBe(false);
        for (const l of [...L.lots, ...L.vacantLots]) expect(overlap(f, l), `${y} ${f.id} under ${l.label}`).toBe(false);
        for (const l of L.landmarks) expect(overlap(f, l), `${y} ${f.id} under ${l.id}`).toBe(false);
      }
    }
  });

  it('loses fields to the town as it grows: farmed in 1955, houses by 2025', () => {
    const plan = planTown(seed);
    const lives = fieldLives(plan, dev, 2025);
    const sold = lives.filter((l) => l.sold !== undefined);
    expect(sold.length).toBeGreaterThan(0);
    expect(sold.some((l) => l.becomes === 'houses')).toBe(true);
    for (const l of sold) {
      const f = planFarms(plan).fields.find((x) => x.id === l.id)!;
      expect(fieldClear(f, dev(l.sold! - 1))).toBe(true);
      expect(fieldClear(f, dev(l.sold!))).toBe(false);
    }
    const in1955 = layoutTown(worldAt(tl, 1955)).farms.fields.length;
    const in2025 = layoutTown(worldAt(tl, 2025)).farms.fields.length;
    expect(in2025).toBeLessThan(in1955);
  });

  it('runs a country road in along a street not built yet', () => {
    const y1955 = layoutTown(worldAt(tl, 1955)).farms.roads;
    expect(y1955.some((r) => r.inner)).toBe(true);
  });
});
