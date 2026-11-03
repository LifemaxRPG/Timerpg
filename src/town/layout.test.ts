import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { layoutTown } from './layout';
import { planTown, type Rect } from './plan';

const overlap = (a: Rect, b: Rect): boolean =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const SEEDS = ['alpha', 'demo', 'zebra', 'quartz', 'maple', 'orbit', 'delta', 'nova'];

describe('town plan (per-seed generation)', () => {
  it('is deterministic', () => {
    expect(JSON.stringify(planTown('alpha'))).toBe(JSON.stringify(planTown('alpha')));
  });

  it('makes visibly different towns for different seeds', () => {
    const sig = (s: string): string => {
      const p = planTown(s);
      return JSON.stringify([p.width, p.height, p.mainY, p.roads.filter((r) => !r.horizontal).map((r) => r.x), p.roads.filter((r) => r.horizontal).map((r) => r.y)]);
    };
    expect(new Set(SEEDS.map(sig)).size).toBeGreaterThanOrEqual(7);
  });

  it('never overlaps plots, roads or the plaza, and stays on the map', () => {
    for (const seed of SEEDS) {
      const p = planTown(seed);
      for (const c of p.candidates) {
        expect(c.x).toBeGreaterThanOrEqual(0);
        expect(c.y).toBeGreaterThanOrEqual(0);
        expect(c.x + c.w).toBeLessThanOrEqual(p.width);
        expect(c.y + c.h).toBeLessThanOrEqual(p.height);
        for (const r of p.roads) expect(overlap(c, r), `${seed} ${c.id} on road`).toBe(false);
        expect(overlap(c, p.plaza), `${seed} ${c.id} in plaza`).toBe(false);
      }
      for (let i = 0; i < p.candidates.length; i++)
        for (let j = i + 1; j < p.candidates.length; j++)
          expect(overlap(p.candidates[i]!, p.candidates[j]!), `${seed} ${p.candidates[i]!.id} vs ${p.candidates[j]!.id}`).toBe(false);
    }
  });

  it('always has room for the whole town', () => {
    for (const seed of SEEDS) {
      const p = planTown(seed);
      const n = (k: string): number => p.candidates.filter((c) => c.kind === k).length;
      expect(n('shop'), `${seed} shops`).toBeGreaterThanOrEqual(20);
      expect(n('villa'), `${seed} villas`).toBeGreaterThanOrEqual(2);
      // An apartment building houses at least 3 households, every other ordinary plot one.
      expect(n('small') + n('medium') + n('house') + 4 * n('apartment'), `${seed} homes`).toBeGreaterThanOrEqual(100);
    }
  });

  it('keeps decoration off the roads', () => {
    for (const seed of SEEDS) {
      const p = planTown(seed);
      for (const prop of p.props) {
        const tx = Math.floor(prop.x / 32);
        const ty = Math.floor((prop.y - 1) / 32);
        for (const r of p.roads) {
          const inside = tx >= r.x && tx < r.x + r.w && ty >= r.y && ty < r.y + r.h;
          expect(inside, `${seed} ${prop.kind} on road at ${tx},${ty}`).toBe(false);
        }
      }
    }
  });
});

describe('town layout (who lives where)', () => {
  const t = runTimeline({ seed: 'alpha', endYear: 2025 });

  it('is deterministic', () => {
    expect(JSON.stringify(layoutTown(worldAt(t, 2000)))).toBe(JSON.stringify(layoutTown(worldAt(t, 2000))));
  });

  it('never overlaps lots and houses everyone / shows every open business', () => {
    for (const year of [1950, 1975, 2000, 2025]) {
      const w = worldAt(t, year);
      const l = layoutTown(w);
      for (let i = 0; i < l.lots.length; i++)
        for (let j = i + 1; j < l.lots.length; j++)
          expect(overlap(l.lots[i]!, l.lots[j]!), `${l.lots[i]!.slot} vs ${l.lots[j]!.slot} in ${year}`).toBe(false);
      const housed = l.lots.filter((x) => x.kind === 'house').flatMap((x) => x.residents);
      const living = Object.values(w.people).filter((p) => p.deathYear === undefined).length;
      expect(housed.length).toBe(living);
      expect(new Set(housed).size).toBe(living);
      const open = Object.values(w.businesses).filter((b) => b.closedYear === undefined).length;
      expect(l.lots.filter((x) => x.kind === 'shop').length).toBe(open);
    }
  });

  it('keeps most buildings in the same place from one year to the next', () => {
    const a = layoutTown(worldAt(t, 2000));
    const b = layoutTown(worldAt(t, 2001));
    const slotOf = new Map(b.lots.map((x) => [x.refId, x.slot]));
    const same = a.lots.filter((x) => slotOf.get(x.refId) === x.slot).length;
    const kept = a.lots.filter((x) => slotOf.has(x.refId)).length;
    expect(same / kept).toBeGreaterThan(0.7);
  });

  it('grows outward from the center: young town is compact, old town sprawls', () => {
    const spread = (year: number): number => {
      const l = layoutTown(worldAt(t, year));
      const houses = l.lots.filter((x) => x.kind === 'house');
      const d = houses.map((h) => Math.hypot(h.x + h.w / 2 - l.plan.center.x, h.y + h.h / 2 - l.plan.center.y));
      return d.reduce((s, v) => s + v, 0) / d.length;
    };
    expect(spread(1950)).toBeLessThan(spread(2025));
  });

  it('does not decorate on top of buildings, and only grows woodland over streets that are not built', () => {
    for (const year of [1950, 2025]) {
      const l = layoutTown(worldAt(t, year));
      const unbuilt = l.plan.roads.filter((r) => !l.roads.includes(r));
      for (const p of l.props) {
        const tx = p.x / 32;
        const ty = (p.y - 1) / 32;
        // Props sit inside a plot or landmark site, or over a street that is not built yet.
        const host = [...l.plan.candidates.map((c) => ({ ...c })), ...l.plan.landmarks.map((c) => ({ ...c, id: c.id as string }))]
          .find((c) => tx >= c.x && tx <= c.x + c.w && ty >= c.y && ty <= c.y + c.h);
        const overUnbuilt = unbuilt.some((r) => tx >= r.x - 1 && tx <= r.x + r.w + 1 && ty >= r.y - 1 && ty <= r.y + r.h + 1);
        expect(host !== undefined || overUnbuilt, `${year} prop at ${tx.toFixed(1)},${ty.toFixed(1)} is on nothing`).toBe(true);
        // ...and never on top of a building that is there this year.
        const other = l.lots.find((o) => o.slot !== host?.id && tx >= o.x && tx < o.x + o.w && ty >= o.y && ty < o.y + o.h);
        expect(other).toBeUndefined();
        // ...and never on a street that IS built.
        expect(l.roads.some((r) => tx >= r.x && tx < r.x + r.w && ty >= r.y && ty < r.y + r.h)).toBe(false);
      }
    }
  });

  it('builds streets outward over time: fewer in 1950, all connected, and every lot has one beside it', () => {
    const touching = (a: Rect, b: Rect): boolean => a.x <= b.x + b.w + 1 && b.x <= a.x + a.w + 1 && a.y <= b.y + b.h + 1 && b.y <= a.y + a.h + 1;
    const early = layoutTown(worldAt(t, 1950));
    const late = layoutTown(worldAt(t, 2025));
    expect(early.roads.length).toBeLessThan(late.roads.length);
    for (const l of [early, late]) {
      // Everything built links back to the main street through other built streets.
      const seen = new Set(l.roads.filter((r) => r.main));
      for (let grew = true; grew; ) {
        grew = false;
        for (const r of l.roads) if (!seen.has(r) && [...seen].some((q) => touching(r, q))) { seen.add(r); grew = true; }
      }
      expect(seen.size).toBe(l.roads.length);
      // Every occupied lot has a built street beside it.
      for (const lot of l.lots) expect(l.roads.some((r) => touching({ x: lot.x - 2, y: lot.y - 2, w: lot.w + 4, h: lot.h + 4 }, r)), `${lot.slot} has no street`).toBe(true);
    }
    // 1950's streets are still there in 2025 (towns do not lose streets).
    const lateSet = new Set(late.roads);
    for (const r of early.roads) expect(lateSet.has(r)).toBe(true);
  });
});
