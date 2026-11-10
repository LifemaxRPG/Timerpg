import { describe, expect, it } from 'vitest';
import { loreOf } from './lore';
import { siteAt, CELL_W } from './map';

describe('lore', () => {
  const sites: ReturnType<typeof siteAt>[] = [];
  for (let cy = -3; cy <= 3; cy++) for (let cx = -3; cx <= 3; cx++) sites.push(siteAt('w1', cx, cy));
  it('is deterministic and different per town', () => {
    expect(loreOf('w1', sites[0]!)).toEqual(loreOf('w1', sites[0]!));
    expect(new Set(sites.map((s) => loreOf('w1', s).title)).size).toBeGreaterThan(8);
  });
  it('always points at a different, neighbouring town you can walk to', () => {
    for (const s of sites) {
      const l = loreOf('w1', s);
      expect(l.targetId).not.toBe(s.id);
      const t = siteAt('w1', ...(l.targetId.split(',').map(Number) as [number, number]));
      expect(Math.hypot(t.center.x - s.center.x, t.center.y - s.center.y)).toBeLessThan(CELL_W * 1.6);
      expect(l.legend).toContain(l.targetName);
      expect(l.discovery.length).toBeGreaterThan(30);
    }
  });
  it('happened inside the playable years, so time travel decides when it exists', () => {
    for (const s of sites) { const y = loreOf('w1', s).year; expect(y).toBeGreaterThan(1951); expect(y).toBeLessThan(1995); }
  });
  it('uses several kinds across the world', () => {
    expect(new Set(sites.map((s) => loreOf('w1', s).kind)).size).toBeGreaterThanOrEqual(4);
  });
});
