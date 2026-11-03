import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt, isAlive } from '../sim';
import { adultsOf, inspectAt } from './inspect';
import { layoutTown } from './layout';

describe('inspecting what you click', () => {
  const t = runTimeline({ seed: 'alpha', endYear: 2000 });
  const w = worldAt(t, 1975);
  const layout = layoutTown(w);

  it('a house lists exactly its living residents, ready to act on', () => {
    const lot = layout.lots.find((l) => l.kind === 'house')!;
    const ins = inspectAt(layout, w, lot.x + lot.w / 2, lot.y + lot.h / 2)!;
    expect(ins.business).toBeNull();
    expect(ins.people.map((p) => p.id).sort()).toEqual(lot.residents.filter((id) => isAlive(w.people[id]!)).sort());
    expect(ins.people.length).toBeGreaterThan(0);
  });

  it('a shop offers the business and its owner', () => {
    const lot = layout.lots.find((l) => l.kind === 'shop')!;
    const ins = inspectAt(layout, w, lot.x + 1, lot.y + 1)!;
    expect(ins.business!.id).toBe(lot.refId);
    expect(ins.title).toBe(w.businesses[lot.refId]!.name);
    expect(ins.people.map((p) => p.id)).toEqual(w.businesses[lot.refId]!.owner ? [w.businesses[lot.refId]!.owner] : []);
  });

  it('a landmark has nothing to act on, and empty ground is nothing', () => {
    const lm = layout.landmarks[0]!;
    const ins = inspectAt(layout, w, lm.x + 1, lm.y + 1)!;
    expect(ins.people).toEqual([]);
    expect(ins.business).toBeNull();
    expect(inspectAt(layout, w, -50, -50)).toBeNull();
  });

  it('lists adults to introduce, excluding the person themselves and children', () => {
    const adults = adultsOf(w);
    expect(adults.length).toBeGreaterThan(10);
    const first = adults[0]!.id;
    expect(adultsOf(w, first).some((a) => a.id === first)).toBe(false);
    for (const a of adults) expect(w.year - w.people[a.id]!.birthYear).toBeGreaterThanOrEqual(18);
  });
});
