import { describe, expect, it } from 'vitest';
import { planTown } from './plan';
import { firstYearReaching, populationIn } from './population';
import {
  CHAPEL_PLOT, chapel, downtownHistory, downtownSites, downtownStanding, downtownTier, HIGHRISE_FROM, HIGHRISE_POPULATION,
  MIDRISE_FROM, MIDRISE_POPULATION, runOf, streetBlock, townHall,
} from './downtown';
import { hitsWater } from './water';

const SEEDS = ['gamma', 'quartz', 'eta', 'orbit', 'water32', 'water8'];
const hit = (a: { x: number; y: number; w: number; h: number }, b: { x: number; y: number; w: number; h: number }): boolean =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

describe('downtown', () => {
  it('is a pure function of the seed', () => {
    for (const seed of ['gamma', 'quartz']) {
      const plan = planTown(seed);
      const a = JSON.stringify(downtownHistory(plan));
      // (a copy of the plan, so no cache by object is involved)
      const b = JSON.stringify(downtownHistory({ ...plan }));
      expect(a).toBe(b);
      expect(JSON.stringify(chapel(plan))).toBe(JSON.stringify(chapel({ ...plan })));
    }
  });

  it('builds mid-rises and towers only once the town has the people, and only from their decades', () => {
    for (const seed of SEEDS) {
      const plan = planTown(seed);
      for (const b of downtownHistory(plan)) {
        if (b.kind === 'parking') { expect(b.floors).toBe(0); continue; }
        if (b.tier === 'midrise') {
          expect(b.built).toBeGreaterThanOrEqual(MIDRISE_FROM);
          expect(populationIn(seed, b.built - 1)).toBeGreaterThanOrEqual(MIDRISE_POPULATION);
          expect(b.floors).toBeGreaterThanOrEqual(6);
          expect(b.floors).toBeLessThanOrEqual(12);
        } else {
          expect(b.built).toBeGreaterThanOrEqual(HIGHRISE_FROM);
          expect(populationIn(seed, b.built - 2)).toBeGreaterThanOrEqual(HIGHRISE_POPULATION);
          expect(b.floors).toBeGreaterThanOrEqual(13);
          expect(b.floors).toBeLessThanOrEqual(19);
        }
        if (b.demolished !== undefined) expect(b.demolished).toBeGreaterThan(b.built);
      }
      // the tier follows the people: none before the threshold's year
      const mid = firstYearReaching(seed, MIDRISE_POPULATION, MIDRISE_FROM);
      for (const y of [1955, 1975, 1995, 2025]) {
        const tier = downtownTier(plan, y);
        if (mid === undefined || y <= mid) expect(tier).toBe('main');
        if (y < HIGHRISE_FROM) expect(tier).not.toBe('highrise');
      }
    }
  });

  it('keeps small towns low and lets the big ones grow towers', () => {
    // orbit never passes 113 people: Main Street blocks only; quartz (321 by 2025) has glass towers; gamma (231) mid-rises
    expect([1955, 1975, 1995, 2025].map((y) => downtownTier(planTown('orbit'), y))).toEqual(['main', 'main', 'main', 'main']);
    expect(downtownTier(planTown('quartz'), 2025)).toBe('highrise');
    expect(downtownTier(planTown('quartz'), 1975)).toBe('main');
    expect(downtownTier(planTown('gamma'), 2025)).toBe('midrise');
    expect(downtownTier(planTown('gamma'), 1975)).toBe('main');
    // the tallest building standing never shrinks by more than one demolition's worth from decade to decade
    const tallest = (y: number): number => Math.max(0, ...downtownStanding(planTown('quartz'), y).map((b) => b.floors));
    expect(tallest(1955)).toBe(0);
    expect(tallest(2025)).toBeGreaterThanOrEqual(15);
    expect(tallest(1995)).toBeGreaterThanOrEqual(7);
  });

  it('puts every building on a pair of neighbouring shop plots, one at a time on each', () => {
    for (const seed of SEEDS) {
      const plan = planTown(seed);
      const shops = new Map(plan.candidates.filter((c) => c.kind === 'shop').map((c) => [c.id, c]));
      const sites = new Map(downtownSites(plan).map((s) => [s.id, s]));
      const hist = downtownHistory(plan);
      for (const b of hist) {
        const s = sites.get(b.site)!;
        expect(s).toBeDefined();
        const [a, c] = s.slots.map((id) => shops.get(id)!);
        expect(a!.y).toBe(c!.y);
        expect(a!.x + a!.w).toBe(c!.x);
        expect(s.rect).toEqual({ x: a!.x, y: a!.y, w: a!.w + c!.w, h: a!.h });
      }
      // on each plot, at most one building stands in any year
      for (let y = 1950; y <= 2040; y += 1) {
        const standing = downtownStanding(plan, y);
        const plots = standing.flatMap((b) => sites.get(b.site)!.slots);
        expect(new Set(plots).size).toBe(plots.length);
      }
      // no two names repeat
      const names = hist.filter((b) => b.kind !== 'parking').map((b) => b.name);
      expect(new Set(names).size).toBe(names.length);
    }
  });

  it('gives every core shop plot a 2-4 storey Main Street block built 1885-1935, and the rest post-war stand-alone shops', () => {
    for (const seed of SEEDS) {
      const plan = planTown(seed);
      for (const c of plan.candidates.filter((q) => q.kind === 'shop')) {
        const b = streetBlock(plan, c.id)!;
        // (one frontage all along a block face: a street wall or a strip)
        for (const q of runOf(plan, c)) expect(streetBlock(plan, q.id)!.standalone, `${c.id} / ${q.id}`).toBe(b.standalone);
        if (b.standalone) { expect(b.built).toBeGreaterThanOrEqual(1950); expect(b.middles).toBeLessThanOrEqual(1); continue; }
        expect(b.built).toBeGreaterThanOrEqual(1885);
        expect(b.built).toBeLessThanOrEqual(1935);
        expect(b.middles).toBeGreaterThanOrEqual(1);
        expect(b.middles).toBeLessThanOrEqual(3);
        if (b.cornerBlock) expect(b.corner).toBe(true);
      }
    }
  });

  it('stands the town hall in the plaza and the chapel on free ground by a street', () => {
    let halls = 0, chapels = 0;
    for (const seed of [...SEEDS, ...Array.from({ length: 12 }, (_, i) => `water${i}`)]) {
      const plan = planTown(seed);
      const h = townHall(plan);
      if (h) { halls++; expect(h.rect).toEqual(plan.v2?.townHall ?? plan.plaza); expect(h.built).toBeGreaterThanOrEqual(1880); expect(h.built).toBeLessThanOrEqual(1910); }
      const c = chapel(plan);
      if (!c) continue;
      chapels++;
      expect([c.rect.w * c.rect.h]).toEqual([CHAPEL_PLOT.w * CHAPEL_PLOT.h]);
      for (const r of plan.roads) expect(hit(c.rect, { x: r.x - 1, y: r.y - 1, w: r.w + 2, h: r.h + 2 })).toBe(false);
      for (const q of plan.candidates) expect(hit(c.rect, q)).toBe(false);
      for (const l of plan.landmarks) expect(hit(c.rect, l)).toBe(false);
      expect(hit(c.rect, plan.plaza)).toBe(false);
      expect(hitsWater(plan.water, c.rect, 1)).toBe(false);
      // a street runs along it (its front a tile off the road)
      expect(plan.roads.some((r) => hit({ x: c.rect.x - 2, y: c.rect.y - 2, w: c.rect.w + 4, h: c.rect.h + 4 }, r))).toBe(true);
    }
    expect(halls).toBeGreaterThan(8);
    // (a chapel needs a free stretch of woodland by a street within reach of the centre: about two towns in five have one)
    expect(chapels).toBeGreaterThan(5);
  });
});
