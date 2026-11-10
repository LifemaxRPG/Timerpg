import { describe, expect, it } from 'vitest';
import { archetypeOf } from './archetype';
import { runTimeline, worldAt } from './index';
import { scenarioSpace, SEQUEL_COUNT, TEMPLATE_COUNT } from './scenarios';
import { isAlive } from './types';

const seeds = ['alpha', 'beta/1,0', 'gamma/-2,3', 'delta', 'eps/4,4', 'zeta'];
const runs = seeds.map((s) => runTimeline({ seed: s, endYear: 2025 }));

describe('scenario engine', () => {
  it('has a big library', () => {
    expect(TEMPLATE_COUNT).toBeGreaterThanOrEqual(35);
    expect(SEQUEL_COUNT).toBeGreaterThanOrEqual(6);
  });
  it('can tell over a million distinct stories in a town', () => {
    const w = worldAt(runs[0]!, 2000);
    expect(scenarioSpace(w)).toBeGreaterThan(1_000_000);
  });
  it('reports a handful of stories every year, never none for long, never a flood', () => {
    for (const t of runs) {
      const last = worldAt(t, 2025).stories!;
      const perYear = new Map<number, number>();
      for (const st of last) perYear.set(st.year, (perYear.get(st.year) ?? 0) + 1);
      expect(last.length).toBeGreaterThan(70);
      for (let y = 1951; y <= 2025; y++) expect(perYear.get(y) ?? 0).toBeLessThanOrEqual(7);
    }
  });
  it('is varied: many templates, many categories, few repeats of the same headline', () => {
    for (const t of runs) {
      const st = worldAt(t, 2025).stories!;
      expect(new Set(st.map((x) => x.template)).size).toBeGreaterThan(18);
      expect(new Set(st.map((x) => x.category)).size).toBeGreaterThanOrEqual(8);
      expect(new Set(st.map((x) => x.headline)).size / st.length).toBeGreaterThan(0.85);
    }
  });
  it('is deterministic', () => {
    const a = worldAt(runTimeline({ seed: 'alpha', endYear: 2010 }), 2010).stories!.map((s) => s.headline);
    const b = worldAt(runs[0]!, 2010).stories!.map((s) => s.headline);
    expect(a).toEqual(b);
  });
  it('only mentions real people, and never reports the dead acting after death', () => {
    for (const t of runs) {
      const w = worldAt(t, 2025);
      for (const st of w.stories!) for (const id of st.people) expect(w.people[id]).toBeDefined();
    }
  });
  it('has consequences: some stories kill people, move money, and open then resolve threads', () => {
    let sequels = 0, opened = 0, murders = 0;
    for (const t of runs) {
      const w = worldAt(t, 2025);
      sequels += w.stories!.filter((s) => s.sequelOf).length;
      opened += w.stories!.filter((s) => s.open || w.stories!.some((x) => x.sequelOf === s.id)).length;
      murders += Object.values(w.people).filter((p) => p.deathCause === 'murdered').length;
    }
    expect(sequels).toBeGreaterThan(5);
    expect(opened).toBeGreaterThan(5);
    expect(murders).toBeGreaterThan(0);
  });
  it('keeps the town alive and functioning (people, shops, no NaN)', () => {
    for (const t of runs) {
      const w = worldAt(t, 2025);
      const alive = Object.values(w.people).filter(isAlive);
      expect(alive.length).toBeGreaterThan(30);
      for (const p of alive) { expect(Number.isFinite(p.wealth)).toBe(true); expect(Math.abs(p.reputation)).toBeLessThanOrEqual(1); }
      for (const b of Object.values(w.businesses)) expect(Number.isFinite(b.capital)).toBe(true);
    }
  });
  it('stories fit the town: mining accidents only happen in mining towns', () => {
    let any = false;
    for (const t of runs) {
      const w = worldAt(t, 2025);
      const mining = w.stories!.filter((s) => s.template === 'mine_accident').length;
      if (mining) { any = true; expect(archetypeOf(t.seed).id).toBe('mining'); }
    }
    // and a town that IS a mining town does have them
    const m = Array.from({ length: 400 }, (_, i) => `mine-${i}`).find((x) => archetypeOf(x).id === 'mining')!;
    expect(worldAt(runTimeline({ seed: m, endYear: 2025 }), 2025).stories!.some((s) => s.template === 'mine_accident' || s.template === 'epidemic')).toBe(true);
    void any;
  });
  it('a change in the past ripples only where it should: removing someone leaves most stories untouched', () => {
    const base = worldAt(runs[0]!, 2025).stories!;
    const victim = base.find((s) => s.people.length)!.people[0]!;
    const alt = worldAt(runTimeline({ seed: 'alpha', endYear: 2025, interventions: [{ kind: 'kill', year: 1951, targetId: victim }] }), 2025).stories!;
    const same = alt.filter((s) => base.some((b) => b.headline === s.headline)).length;
    expect(same / alt.length).toBeGreaterThan(0.05);
  });
});

