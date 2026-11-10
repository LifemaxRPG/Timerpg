import { describe, expect, it } from 'vitest';
import { ageIn, isAlive, runTimeline, worldAt, type Intervention, type Person } from './index';

describe('time-traveler verbs', () => {
  it('introduce: a strongly introduced man becomes the bride she chooses', () => {
    const seed = 'alpha';
    const base = runTimeline({ seed, endYear: 1975 });
    const wedding = base.chronicle.find((e) => e.kind === 'marriage' && e.year >= 1955 && e.year <= 1970)!;
    const y = wedding.year;
    const wife = worldAt(base, y).people[wedding.subject!]!;
    const husbandId = wife.spouse!;
    const before = worldAt(base, y - 1);
    const after = worldAt(base, y);
    const rival: Person | undefined = Object.values(before.people).find(
      (m) =>
        m.sex === 'M' && isAlive(m) && !m.spouse && m.id !== husbandId &&
        ageIn(m, y) >= 20 && ageIn(m, y) <= 45 && Math.abs(m.birthYear - wife.birthYear) <= 12 &&
        !m.parents.some((p) => wife.parents.includes(p)) && after.people[m.id] && isAlive(after.people[m.id]!),
    );
    expect(rival, 'need an alternative suitor').toBeDefined();

    const alt = runTimeline({
      seed, endYear: y,
      interventions: [{ kind: 'introduce', year: y - 1, a: wife.id, b: rival!.id }],
    });
    expect(worldAt(alt, y).people[wife.id]!.spouse).toBe(rival!.id);
    expect(worldAt(alt, y).people[husbandId]!.spouse).not.toBe(wife.id);
  });

  it('rumor: reputation changes and can flip elections across seeds', () => {
    let flipped = 0;
    for (const seed of ['a', 'b', 'c', 'd', 'e', 'f']) {
      const base = runTimeline({ seed, endYear: 1980 });
      const mayor = worldAt(base, 1979).mayor!;
      const alt = runTimeline({
        seed, endYear: 1980,
        interventions: [{ kind: 'rumor', year: 1979, targetId: mayor, amount: -1 }],
      });
      // town stories in the same year may move it a little (a ribbon-cutting, a scandal), but the smear dominates
      expect(worldAt(alt, 1979).people[mayor]!.reputation).toBeLessThan(-0.5);
      expect(worldAt(alt, 1979).people[mayor]!.reputation).toBeLessThan(worldAt(base, 1979).people[mayor]!.reputation);
      if (worldAt(alt, 1980).mayor !== worldAt(base, 1980).mayor) flipped++;
    }
    expect(flipped).toBeGreaterThan(0);
  });

  it('gift raises wealth; sabotage cuts capital', () => {
    const seed = 'alpha';
    const base = runTimeline({ seed, endYear: 1955 });
    const w = worldAt(base, 1951);
    const person = Object.values(w.people).find((p) => isAlive(p) && ageIn(p, 1951) >= 20)!;
    const biz = Object.values(w.businesses).find((b) => b.closedYear === undefined)!;
    const ivs: Intervention[] = [
      { kind: 'gift', year: 1952, targetId: person.id, amount: 500 },
      { kind: 'sabotage', year: 1952, businessId: biz.id },
    ];
    const alt = runTimeline({ seed, endYear: 1952, interventions: ivs });
    const b1 = worldAt(base, 1952);
    const a1 = worldAt(alt, 1952);
    expect(a1.people[person.id]!.wealth).toBeGreaterThan(b1.people[person.id]!.wealth + 400);
    expect(a1.businesses[biz.id]!.capital).toBeLessThan(b1.businesses[biz.id]!.capital);
  });

  it('fails gracefully on dead or missing targets', () => {
    const t = runTimeline({
      seed: 'alpha', endYear: 1953,
      interventions: [
        { kind: 'introduce', year: 1952, a: 'nope', b: 'nada' },
        { kind: 'rumor', year: 1952, targetId: 'nope', amount: -1 },
        { kind: 'gift', year: 1952, targetId: 'nope', amount: 5 },
        { kind: 'sabotage', year: 1952, businessId: 'nope' },
      ],
    });
    expect(t.chronicle.filter((e) => e.kind === 'intervention_failed')).toHaveLength(4);
  });
});
