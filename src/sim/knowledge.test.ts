import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from './index';
import { heardBy, holdersOf, secretsOf, trustNeeded } from './knowledge';
import { isAlive } from './types';

const t = runTimeline({ seed: 'alpha', endYear: 2000 });
const w = worldAt(t, 1990);
const all = Object.values(w.people).filter(isAlive).flatMap((p) => secretsOf(w, p));

describe('secrets', () => {
  it('a town has some secrets, but not everyone has one', () => {
    expect(all.length).toBeGreaterThan(3);
    expect(all.length).toBeLessThan(Object.values(w.people).filter(isAlive).length);
  });
  it('are deterministic and keyed by person, so they survive time travel', () => {
    const w2 = worldAt(t, 1990);
    expect(Object.values(w2.people).filter(isAlive).flatMap((p) => secretsOf(w2, p)).map((s) => s.id)).toEqual(all.map((s) => s.id));
  });
  it('are always known to their subject, and by few others', () => {
    for (const s of all) {
      const h = holdersOf(w, s);
      expect(h).toContain(s.about);
      expect(h.length).toBeLessThan(10);
    }
  });
  it('people hear about secrets of others, never their own', () => {
    for (const p of Object.values(w.people).filter(isAlive).slice(0, 40)) {
      for (const s of heardBy(w, p)) expect(s.about).not.toBe(p.id);
    }
    expect(Object.values(w.people).filter(isAlive).some((p) => heardBy(w, p).length > 0)).toBe(true);
  });
  it('a darker secret takes more trust to share than a petty one', () => {
    const p = Object.values(w.people).find(isAlive)!;
    expect(trustNeeded(p, { id: 'x', about: p.id, kind: 'affair', text: '', weight: 0.9 }, true))
      .toBeGreaterThan(trustNeeded(p, { id: 'y', about: p.id, kind: 'ambition', text: '', weight: 0.3 }, false));
  });
});
