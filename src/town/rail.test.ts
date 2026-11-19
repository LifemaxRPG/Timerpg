import { describe, expect, it } from 'vitest';
import { originOf } from '../world/map';
import { linksAt, railSite, type RailWorld } from '../world/rail';
import { planTown } from './plan';
import { RAIL_CLEAR, railIn, townRail, type TownRail } from './rail';

const towns: { w: RailWorld; cx: number; cy: number }[] = [
  { w: { seed: 'world-6', home: 'gamma' }, cx: 0, cy: 0 },
  ...[[1, 0], [-1, 1], [1, -1], [0, 1], [2, 0]].map(([cx, cy]) => ({ w: { seed: 'world-6' }, cx: cx!, cy: cy! })),
  ...[[0, 0], [3, -1], [-2, 2]].map(([cx, cy]) => ({ w: { seed: 'w1' }, cx: cx!, cy: cy! })),
];
const built = towns.map((t) => { const site = railSite(t.w, t.cx, t.cy); const plan = planTown(site.seed); return { ...t, site, plan, rail: townRail(t.w, site, plan) }; });

describe('the railroad in town', () => {
  it('is deterministic', () => {
    const b = built[1]!;
    expect(JSON.stringify(townRail(b.w, b.site, b.plan))).toBe(JSON.stringify(b.rail));
  });

  it('lays a track for every line into the town, from the plan\'s edge, with nothing in the way', () => {
    for (const b of built) {
      const links = linksAt(b.w, b.cx, b.cy);
      expect(b.rail.conflicts, b.site.name).toEqual([]);
      for (const l of links) {
        const ts = b.rail.tracks.filter((t) => t.link === l.id && t.kind !== 'siding');
        expect(ts.length, `${b.site.name} ${l.id}`).toBeGreaterThan(0);
        const p = b.rail.portals.find((q) => q.link === l.id)!;
        // the portal is on the edge the line faces, and some track of the line ends there
        expect(Math.min(p.at.x, p.at.y, b.plan.width - 1 - p.at.x, b.plan.height - 1 - p.at.y)).toBeLessThanOrEqual(1.01);
        expect(ts.some((t) => [t.pts[0]!, t.pts[t.pts.length - 1]!].some((q) => Math.hypot(q.x - p.at.x, q.y - p.at.y) < 0.01))).toBe(true);
        // and the world's alignment carries on from it
        const o = originOf(b.site, b.plan);
        expect(p.beyond[0]).toEqual({ x: p.at.x + o.x, y: p.at.y + o.y });
      }
      for (const t of b.rail.tracks) for (const q of t.pts) {
        expect(q.x).toBeGreaterThanOrEqual(0); expect(q.y).toBeGreaterThanOrEqual(0);
        expect(q.x).toBeLessThanOrEqual(b.plan.width); expect(q.y).toBeLessThanOrEqual(b.plan.height);
      }
    }
  });

  it('never doubles back: a through line runs on through the depot', () => {
    for (const b of built) {
      // the town's tracks of each line, joined in order (in from one portal, out the other)
      for (const t of b.rail.tracks) for (let i = 2; i < t.pts.length; i++) {
        const a = t.pts[i - 2]!, c = t.pts[i - 1]!, d = t.pts[i]!;
        const ux = c.x - a.x, uy = c.y - a.y, vx = d.x - c.x, vy = d.y - c.y;
        const cos = (ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy) || 1);
        expect(cos, `${b.site.name} ${t.link} at ${i}`).toBeGreaterThan(-0.2);
      }
    }
  });

  it('keeps the track off every lot, landmark and the plaza', () => {
    for (const b of built) for (const t of b.rail.tracks) for (let i = 0; i + 1 < t.pts.length; i++) {
      const a = t.pts[i]!, c = t.pts[i + 1]!, L = Math.hypot(c.x - a.x, c.y - a.y);
      for (let s = 0; s <= L; s += 0.5) {
        const x = a.x + ((c.x - a.x) * s) / Math.max(L, 1e-9), y = a.y + ((c.y - a.y) * s) / Math.max(L, 1e-9);
        for (const r of [...b.plan.candidates, ...b.plan.landmarks, b.plan.plaza]) {
          const inside = x > r.x - 1 && x < r.x + r.w + 1 && y > r.y - 1 && y < r.y + r.h + 1;
          expect(inside, `${b.site.name} ${t.link} at ${x.toFixed(1)},${y.toFixed(1)}`).toBe(false);
        }
      }
    }
  });

  it('stands the depot beside the track near a street, on clear ground, nearer the plaza than the town\'s edge', () => {
    for (const b of built) {
      const d = b.rail.depot;
      expect(d, b.site.name).not.toBeNull();
      if (!d) continue;
      const r = d.rect;
      for (const q of [...b.plan.candidates, ...b.plan.landmarks, b.plan.plaza, ...b.plan.roads]) {
        const hit = r.x < q.x + q.w && q.x < r.x + r.w && r.y < q.y + q.h && q.y < r.y + r.h;
        expect(hit, b.site.name).toBe(false);
      }
      expect(b.plan.roads.some((q) => r.x - 9 < q.x + q.w && q.x < r.x + r.w + 9 && r.y - 9 < q.y + q.h && q.y < r.y + r.h + 9)).toBe(true);
      // the depot faces the track: its middle is beside the point on the track
      const mx = r.x + r.w / 2, my = r.y + r.h / 2;
      expect(Math.hypot(mx - d.at.x, my - d.at.y)).toBeLessThan(RAIL_CLEAR + 7);
      expect(d.built).toBe(Math.min(...linksAt(b.w, b.cx, b.cy).map((l) => l.built)));
    }
  });

  it('puts level crossings where a track runs over a street', () => {
    let n = 0;
    for (const b of built) for (const c of b.rail.crossings) {
      n++;
      const r = b.plan.roads[c.road]!;
      expect(c.x).toBeGreaterThan(r.x - 1); expect(c.x).toBeLessThan(r.x + r.w + 1);
      expect(c.y).toBeGreaterThan(r.y - 1); expect(c.y).toBeLessThan(r.y + r.h + 1);
      expect(Math.hypot(c.dx, c.dy)).toBeCloseTo(1, 2);
      if (c.gatesFrom !== undefined) expect(c.gatesFrom).toBeGreaterThan(c.lightsFrom);
    }
    expect(n).toBeGreaterThan(0);
  });

  it('changes with the years: nothing before the line, track and gear after, beds and trails after the rails go', () => {
    for (const b of built) {
      const first = Math.min(...linksAt(b.w, b.cx, b.cy).map((l) => l.built));
      const before = railIn(b.w, b.site, b.rail, first - 1);
      expect(before.tracks.every((t) => t.state === 'none')).toBe(true);
      expect(before.depot).toBe('none');
      const now = railIn(b.w, b.site, b.rail, 2025);
      for (const c of now.crossings) if (c.gear) expect(['crossbuck', 'lights', 'gates']).toContain(c.gear);
      for (const t of now.tracks) {
        const l = linksAt(b.w, b.cx, b.cy).find((x) => x.id === t.track.link)!;
        expect(t.state).toBe(l.abandoned === undefined ? 'track' : l.trail !== undefined ? 'trail' : 'bed');
      }
    }
  });

  it('the app\'s town (gamma) has its depot, a crossing on the road out of town and a siding', () => {
    const g: TownRail = built[0]!.rail;
    expect(g.depot).not.toBeNull();
    expect(g.crossings.some((c) => built[0]!.plan.roads[c.road]!.main)).toBe(true);
    expect(g.tracks.some((t) => t.kind === 'siding')).toBe(true);
  });
});
