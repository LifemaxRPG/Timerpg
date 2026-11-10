import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { layoutTown } from './layout';
import { buildResidents, dayAt, walkable } from './schedule';

describe('daily life', () => {
  const t = runTimeline({ seed: 'alpha', endYear: 2000 });
  const w = worldAt(t, 1990);
  const layout = layoutTown(w);
  const cast = buildResidents(layout, w);
  const ok = walkable(layout);

  it('gives most households people with a home, a job and a route', () => {
    expect(cast.length).toBeGreaterThan(20);
    for (const r of cast) {
      expect(ok(r.home.x, r.home.y)).toBe(true);
      expect(r.toWork[0]).toEqual(r.home);
      expect(r.job.length).toBeGreaterThan(0);
    }
  });

  it('is deterministic', () => {
    const again = buildResidents(layout, w);
    expect(again.map((r) => r.id)).toEqual(cast.map((r) => r.id));
    expect(dayAt(again[0]!, 8)).toEqual(dayAt(cast[0]!, 8));
  });

  it('keeps everyone indoors at 3am, and puts some people on the street in the morning', () => {
    expect(cast.every((r) => dayAt(r, 3).pose === null)).toBe(true);
    const morning = cast.filter((r) => { const p = dayAt(r, 7.6).pose; return p?.moving; });
    expect(morning.length).toBeGreaterThan(0);
  });

  it('walkers stay on walkable ground', () => {
    for (const r of cast.slice(0, 30)) for (let h = 6; h < 22; h += 0.25) {
      const p = dayAt(r, h).pose;
      if (p) expect(ok(Math.floor(p.x), Math.floor(p.y))).toBe(true);
    }
  });
});
