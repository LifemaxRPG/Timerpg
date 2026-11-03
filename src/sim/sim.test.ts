import { describe, expect, it } from 'vitest';
import { diffWorlds, isEmptyDiff, runTimeline, worldAt, ageIn, isAlive, type Timeline } from './index';

const END = 2025;

function assertConsistent(t: Timeline): void {
  for (const w of t.snapshots) {
    for (const p of Object.values(w.people)) {
      expect(p.birthYear).toBeLessThanOrEqual(w.year);
      if (p.deathYear !== undefined) expect(p.deathYear).toBeLessThanOrEqual(w.year);
      // Living people's marriages must be mutual; the dead keep a link to their widow(er) for history.
      if (isAlive(p) && p.spouse) {
        const s = w.people[p.spouse]!;
        expect(s, `${p.id} spouse missing`).toBeDefined();
        expect(isAlive(s), `${p.id} married to dead ${s.id}`).toBe(true);
        expect(s.spouse).toBe(p.id);
      }
      for (const pid of p.parents) expect(w.people[pid]).toBeDefined();
      for (const cid of p.children) expect(w.people[cid]!.parents).toContain(p.id);
      expect(p.wealth).toBeGreaterThanOrEqual(0);
      if (isAlive(p)) expect(ageIn(p, w.year)).toBeLessThan(125);
    }
    for (const b of Object.values(w.businesses)) {
      if (b.closedYear === undefined) {
        expect(b.owner, `${b.id} open without owner`).toBeDefined();
        expect(isAlive(w.people[b.owner!]!)).toBe(true);
      }
    }
    if (w.mayor) expect(isAlive(w.people[w.mayor]!)).toBe(true);
  }
}

describe('simulation', () => {
  it('is deterministic for a seed', () => {
    const a = runTimeline({ seed: 'alpha', endYear: END });
    const b = runTimeline({ seed: 'alpha', endYear: END });
    expect(JSON.stringify(a.snapshots)).toBe(JSON.stringify(b.snapshots));
  });

  it('differs between seeds', () => {
    const a = worldAt(runTimeline({ seed: 'alpha', endYear: END }), END);
    const b = worldAt(runTimeline({ seed: 'beta', endYear: END }), END);
    expect(Object.keys(a.people)).not.toEqual(Object.keys(b.people));
  });

  it('keeps the town alive and consistent across many seeds', () => {
    for (const seed of ['s1', 's2', 's3', 's4', 's5', 's6']) {
      const t = runTimeline({ seed, endYear: END });
      assertConsistent(t);
      const w = worldAt(t, END);
      const pop = Object.values(w.people).filter(isAlive).length;
      expect(pop, seed).toBeGreaterThan(20);
      expect(pop, seed).toBeLessThan(700);
      expect(Object.values(w.businesses).some((b) => b.closedYear === undefined), seed).toBe(true);
    }
  });

  it('an empty intervention list reproduces the original timeline exactly', () => {
    const base = runTimeline({ seed: 'alpha', endYear: END });
    const alt = runTimeline({ seed: 'alpha', endYear: END, interventions: [] });
    expect(isEmptyDiff(diffWorlds(worldAt(base, END), worldAt(alt, END)))).toBe(true);
  });

  it('killing someone early ripples forward: victim and descendants are erased', () => {
    const base = runTimeline({ seed: 'alpha', endYear: END });
    const w0 = worldAt(base, END);
    // Pick a person who is the ancestor of living people in the present.
    const victim = Object.values(w0.people).find(
      (p) => p.id.startsWith('gen:') && p.sex === 'F' && p.children.length >= 2 && p.children.some((c) => w0.people[c]!.children.length > 0),
    )!;
    expect(victim).toBeDefined();
    const alt = runTimeline({
      seed: 'alpha', endYear: END,
      interventions: [{ kind: 'kill', year: 1951, targetId: victim.id }],
    });
    assertConsistent(alt);
    const d = diffWorlds(w0, worldAt(alt, END));
    expect(isEmptyDiff(d)).toBe(false);
    // Kids born after 1951 to the victim are gone.
    const lateKids = victim.children.filter((c) => w0.people[c]!.birthYear > 1951);
    for (const c of lateKids) expect(d.erased).toContain(c);
    expect(worldAt(alt, 1952).people[victim.id]!.deathCause).toBe('murdered');
  });

  it('unrelated people are untouched by a distant edit (keyed randomness)', () => {
    const base = runTimeline({ seed: 'alpha', endYear: 1960 });
    const w = worldAt(base, 1960);
    const victim = w.people['gen:0:h']!;
    const alt = runTimeline({ seed: 'alpha', endYear: 1960, interventions: [{ kind: 'kill', year: 1951, targetId: victim.id }] });
    const wa = worldAt(alt, 1960);
    // Immigrants are independent of everyone else, so the same ones arrive.
    const imm = (x: typeof w) => Object.keys(x.people).filter((i) => i.startsWith('imm:')).sort();
    expect(imm(wa)).toEqual(imm(w));
    // Founding people unrelated to the victim's household keep the same traits and fate.
    const other = w.people['gen:10:h']!;
    expect(wa.people[other.id]!.deathYear).toBe(other.deathYear);
  });

  it('investing early makes the business bigger and changes the present', () => {
    const base = runTimeline({ seed: 'alpha', endYear: END });
    const w0 = worldAt(base, 1951);
    const biz = Object.values(w0.businesses).find((b) => b.closedYear === undefined)!;
    const alt = runTimeline({
      seed: 'alpha', endYear: END,
      interventions: [{ kind: 'invest', year: 1951, businessId: biz.id, amount: 500 }],
    });
    assertConsistent(alt);
    const bAlt = worldAt(alt, 1960).businesses[biz.id]!;
    const bBase = worldAt(base, 1960).businesses[biz.id]!;
    expect(bAlt.capital).toBeGreaterThan(bBase.capital);
  });

  it('rejects impossible interventions gracefully', () => {
    const t = runTimeline({ seed: 'alpha', endYear: 1955, interventions: [{ kind: 'kill', year: 1952, targetId: 'nobody' }] });
    expect(t.chronicle.some((e) => e.kind === 'intervention_failed')).toBe(true);
  });
});
