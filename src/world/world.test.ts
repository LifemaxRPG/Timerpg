import { describe, expect, it } from 'vitest';
import { planTown } from '../town/plan';
import type { Rect } from '../town/kinds';
import { CELL_H, CELL_W, linkRoad, mainStreetEnds, mapRectOf, originOf, siteAt, sitesNear } from './map';
import { townFounded, townName } from './names';
import { WILD_CHUNK, wildChunk } from './wild';

const overlap = (a: Rect, b: Rect, pad = 0): boolean =>
  a.x < b.x + b.w + pad && b.x < a.x + a.w + pad && a.y < b.y + b.h + pad && b.y < a.y + a.h + pad;

describe('town names', () => {
  it('are deterministic and varied', () => {
    expect(townName('x/1,2')).toBe(townName('x/1,2'));
    const names = new Set(Array.from({ length: 200 }, (_, i) => townName(`w/${i},0`)));
    expect(names.size).toBeGreaterThan(120);
  });

  it('look like names (no doubled words, sane length)', () => {
    for (let i = 0; i < 300; i++) {
      const n = townName(`w/${i},3`);
      expect(n.length).toBeGreaterThanOrEqual(5);
      expect(n.length).toBeLessThanOrEqual(18);
      expect(n, n).not.toMatch(/(brook|stone|wood|field|port|mont|ford|dale)\1/i);
    }
  });

  it('towns were founded before the game starts', () => {
    for (let i = 0; i < 100; i++) {
      const y = townFounded(`w/${i},0`);
      expect(y).toBeGreaterThanOrEqual(1830);
      expect(y).toBeLessThan(1950);
    }
  });
});

describe('world map', () => {
  it('is deterministic per world seed, and different between worlds', () => {
    expect(siteAt('demo', 3, -2)).toEqual(siteAt('demo', 3, -2));
    expect(siteAt('demo', 3, -2).seed).not.toBe(siteAt('other', 3, -2).seed);
    expect(siteAt('demo', 1, 0).name).not.toBe(siteAt('demo', 0, 0).name);
  });

  it('sitesNear returns the right block of cells around a position', () => {
    const near = sitesNear('demo', 0, 0, 1);
    expect(near).toHaveLength(9);
    expect(near.some((s) => s.cx === 0 && s.cy === 0)).toBe(true);
    expect(sitesNear('demo', CELL_W * 5, CELL_H * -3, 0)[0]!.id).toBe('5,-3');
  });

  it('never lets neighbouring towns overlap, with room to walk between them', () => {
    // Real plans: the biggest towns are ~280x236 tiles; cells must leave a walkable gap.
    for (const world of ['demo', 'orbit', 'maple']) {
      const grid: Record<string, { site: ReturnType<typeof siteAt>; rect: Rect }> = {};
      for (let cy = 0; cy < 3; cy++)
        for (let cx = 0; cx < 4; cx++) {
          const site = siteAt(world, cx, cy);
          grid[`${cx},${cy}`] = { site, rect: mapRectOf(site, planTown(site.seed)) };
        }
      for (const a of Object.values(grid))
        for (const b of Object.values(grid)) {
          if (a === b) continue;
          expect(overlap(a.rect, b.rect, 8), `${world}: ${a.site.name} and ${b.site.name} are too close`).toBe(false);
        }
    }
  });

  it('puts the town center where the site says', () => {
    const site = siteAt('demo', 2, 1);
    const plan = planTown(site.seed);
    const o = originOf(site, plan);
    expect(o.x + plan.center.x).toBe(site.center.x);
    expect(o.y + plan.center.y).toBe(site.center.y);
  });
});

describe('roads between towns', () => {
  it('run from the east end of one main street to the west end of the next, unbroken', () => {
    for (let cx = 0; cx < 4; cx++) {
      const a = siteAt('demo', cx, 0), b = siteAt('demo', cx + 1, 0);
      const pa = planTown(a.seed), pb = planTown(b.seed);
      const roads = linkRoad({ site: a, plan: pa }, { site: b, plan: pb });
      const from = mainStreetEnds(a, pa).east, to = mainStreetEnds(b, pb).west;
      expect(roads[0]!.x).toBeLessThanOrEqual(from.x);
      expect(roads[0]!.y).toBe(from.y);
      const last = roads[roads.length - 1]!;
      expect(last.x + last.w).toBeGreaterThanOrEqual(to.x);
      expect(last.y).toBe(to.y);
      // Each piece touches the next.
      for (let i = 0; i + 1 < roads.length; i++) expect(overlap(roads[i]!, roads[i + 1]!, 1)).toBe(true);
      // It really leaves one town and enters the other (the gap between them is wider than 0).
      expect(to.x - from.x).toBeGreaterThan(10);
    }
  });
});

describe('wilderness', () => {
  it('is deterministic, stays inside its chunk, and has both woods and clearings', () => {
    expect(JSON.stringify(wildChunk('demo', 4, -7))).toBe(JSON.stringify(wildChunk('demo', 4, -7)));
    let min = Infinity, max = 0;
    for (let i = 0; i < 40; i++) {
      const props = wildChunk('demo', i, i * 3);
      min = Math.min(min, props.length); max = Math.max(max, props.length);
      for (const p of props) {
        expect(p.x).toBeGreaterThanOrEqual(i * WILD_CHUNK * 32);
        expect(p.x).toBeLessThan((i + 1) * WILD_CHUNK * 32 + 64);
      }
    }
    expect(max).toBeGreaterThan(min * 2);
  });
});
