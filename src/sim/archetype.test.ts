import { describe, expect, it } from 'vitest';
import { ARCHETYPES, archetypeOf, type ArchetypeId } from './archetype';
import { runTimeline, worldAt } from './index';
import { isAlive } from './types';

/** n seeds of each town type. */
function seedsByType(n: number): Record<ArchetypeId, string[]> {
  const out = { farm: [], harbor: [], mill: [], mining: [], resort: [], suburb: [] } as Record<ArchetypeId, string[]>;
  for (let i = 0; out.farm.length < n || out.harbor.length < n || out.mill.length < n || out.mining.length < n || out.resort.length < n || out.suburb.length < n; i++) {
    const s = `type-${i}`;
    const t = archetypeOf(s).id;
    if (out[t].length < n) out[t].push(s);
    if (i > 5000) break;
  }
  return out;
}
const SEEDS = seedsByType(5);
const pop = (seed: string, year: number): number => Object.values(worldAt(runTimeline({ seed, endYear: year }), year).people).filter(isAlive).length;
const mean = (a: number[]): number => a.reduce((x, y) => x + y, 0) / a.length;

describe('town types', () => {
  it('are stable per seed and all occur', () => {
    expect(archetypeOf('x/1,2')).toBe(archetypeOf('x/1,2'));
    for (const id of Object.keys(ARCHETYPES) as ArchetypeId[]) expect(SEEDS[id].length).toBe(5);
  });
  it('change history: a mining town booms before 1975 and then stops growing, a farm town stays smaller', () => {
    const mining70 = mean(SEEDS.mining.map((s) => pop(s, 1974))), mining2000 = mean(SEEDS.mining.map((s) => pop(s, 2000)));
    const farm70 = mean(SEEDS.farm.map((s) => pop(s, 1974)));
    expect(mining70).toBeGreaterThan(farm70);
    // growth per year after the bust is well below growth during the boom
    const boom = (mining70 - mean(SEEDS.mining.map((s) => pop(s, 1950)))) / 24, bust = (mining2000 - mining70) / 26;
    expect(bust).toBeLessThan(boom);
  });
  it('change which shops open: harbors have more bait shops, resorts more ice cream', () => {
    const count = (ids: string[], kind: string): number => ids.reduce((n, s) => n + Object.values(worldAt(runTimeline({ seed: s, endYear: 1990 }), 1990).businesses).filter((b) => b.kind === kind && b.closedYear === undefined).length, 0);
    expect(count(SEEDS.harbor, 'Bait Shop')).toBeGreaterThan(count(SEEDS.suburb, 'Bait Shop'));
    expect(count(SEEDS.resort, 'Ice Cream Parlor')).toBeGreaterThan(count(SEEDS.mining, 'Ice Cream Parlor'));
  });
  it('resorts are better off than mining towns', () => {
    const rich = (ids: string[]): number => mean(ids.map((s) => mean(Object.values(worldAt(runTimeline({ seed: s, endYear: 1985 }), 1985).people).filter(isAlive).map((p) => p.wealth))));
    expect(rich(SEEDS.resort)).toBeGreaterThan(rich(SEEDS.mining));
  });
});
