import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { layoutTown, MAP_H, MAP_W, type Rect } from './layout';

const overlap = (a: Rect, b: Rect): boolean =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

describe('town layout', () => {
  const t = runTimeline({ seed: 'alpha', endYear: 2025 });

  it('is deterministic', () => {
    expect(JSON.stringify(layoutTown(worldAt(t, 2000)))).toBe(JSON.stringify(layoutTown(worldAt(t, 2000))));
  });

  it('never overlaps lots or roads, and stays on the map', () => {
    for (const year of [1950, 1975, 2000, 2025]) {
      const l = layoutTown(worldAt(t, year));
      for (const lot of l.lots) {
        expect(lot.x).toBeGreaterThanOrEqual(0);
        expect(lot.y).toBeGreaterThanOrEqual(0);
        expect(lot.x + lot.w).toBeLessThanOrEqual(MAP_W);
        expect(lot.y + lot.h).toBeLessThanOrEqual(MAP_H);
        for (const r of l.roads) expect(overlap(lot, r), `${lot.slot} on road in ${year}`).toBe(false);
      }
      for (let i = 0; i < l.lots.length; i++)
        for (let j = i + 1; j < l.lots.length; j++)
          expect(overlap(l.lots[i]!, l.lots[j]!), `${l.lots[i]!.slot} vs ${l.lots[j]!.slot}`).toBe(false);
    }
  });

  it('houses every living resident exactly once and shows every open business', () => {
    const w = worldAt(t, 2025);
    const l = layoutTown(w);
    const housed = l.lots.filter((x) => x.kind === 'house').flatMap((x) => x.residents);
    const living = Object.values(w.people).filter((p) => p.deathYear === undefined).length;
    expect(housed.length).toBe(living);
    expect(new Set(housed).size).toBe(living);
    const open = Object.values(w.businesses).filter((b) => b.closedYear === undefined).length;
    expect(l.lots.filter((x) => x.kind === 'shop').length).toBe(open);
  });

  it('keeps most buildings in the same place from one year to the next', () => {
    const a = layoutTown(worldAt(t, 2000));
    const b = layoutTown(worldAt(t, 2001));
    const slotOf = new Map(b.lots.map((x) => [x.refId, x.slot]));
    const same = a.lots.filter((x) => slotOf.get(x.refId) === x.slot).length;
    const kept = a.lots.filter((x) => slotOf.has(x.refId)).length;
    expect(same / kept).toBeGreaterThan(0.7);
  });
});
