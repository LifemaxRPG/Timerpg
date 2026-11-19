import { describe, expect, it } from 'vitest';
import { originOf } from '../world/map';
import { linksAt, railSite, type RailWorld } from '../world/rail';
import { planFarms } from './farms';
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
        // the portal is on the edge the line faces (of the ground the track is laid on: the map and its farmland), and some
        // track of the line ends there
        const B = b.rail.bounds;
        expect(Math.min(p.at.x - B.x, p.at.y - B.y, B.x + B.w - 1 - p.at.x, B.y + B.h - 1 - p.at.y)).toBeLessThanOrEqual(1.01);
        expect(ts.some((t) => [t.pts[0]!, t.pts[t.pts.length - 1]!].some((q) => Math.hypot(q.x - p.at.x, q.y - p.at.y) < 0.01))).toBe(true);
        // and the world's alignment carries on from it
        const o = originOf(b.site, b.plan);
        expect(p.beyond[0]).toEqual({ x: p.at.x + o.x, y: p.at.y + o.y });
      }
      for (const t of b.rail.tracks) for (const q of t.pts) {
        const B = b.rail.bounds;
        expect(q.x).toBeGreaterThanOrEqual(B.x); expect(q.y).toBeGreaterThanOrEqual(B.y);
        expect(q.x).toBeLessThanOrEqual(B.x + B.w); expect(q.y).toBeLessThanOrEqual(B.y + B.h);
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

  it('keeps the track out of the farmsteads, and out of every field but the ones it splits (off their pieces)', () => {
    for (const b of built) {
      const fp = planFarms(b.plan);
      const cut = new Map(b.rail.fieldCuts.map((c) => [c.field, c.pieces]));
      for (const t of b.rail.tracks) for (let i = 0; i + 1 < t.pts.length; i++) {
        const a = t.pts[i]!, c = t.pts[i + 1]!, L = Math.max(Math.hypot(c.x - a.x, c.y - a.y), 1e-9);
        for (let s = 0; s <= L; s += 0.5) {
          const x = a.x + ((c.x - a.x) * s) / L, y = a.y + ((c.y - a.y) * s) / L;
          const inR = (r: { x: number; y: number; w: number; h: number }, pad: number): boolean => x > r.x - pad && x < r.x + r.w + pad && y > r.y - pad && y < r.y + r.h + pad;
          for (const f of fp.farms) expect(inR(f.rect, 3), `${b.site.name} ${t.link} in ${f.id}`).toBe(false);
          for (const f of fp.fields) {
            const pieces = cut.get(f.id);
            if (!pieces) expect(inR(f, 0), `${b.site.name} ${t.link} in ${f.id}`).toBe(false);
            else for (const q of pieces) expect(inR(q, 1), `${b.site.name} ${t.link} in a piece of ${f.id}`).toBe(false);
          }
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
      expect([...b.plan.roads, ...b.rail.roadsOut].some((q) => r.x - 41 < q.x + q.w && q.x < r.x + r.w + 41 && r.y - 41 < q.y + q.h && q.y < r.y + r.h + 41)).toBe(true);
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
      const r = [...b.plan.roads, ...b.rail.roadsOut][c.road]!;
      expect(c.x).toBeGreaterThan(r.x - 1); expect(c.x).toBeLessThan(r.x + r.w + 1);
      expect(c.y).toBeGreaterThan(r.y - 1); expect(c.y).toBeLessThan(r.y + r.h + 1);
      expect(Math.hypot(c.dx, c.dy)).toBeCloseTo(1, 2);
      if (c.gatesFrom !== undefined) expect(c.gatesFrom).toBeGreaterThan(c.lightsFrom);
    }
    expect(n).toBeGreaterThan(0);
  });

  it('crosses the streets in town and the country roads square (within 15 degrees), at most one skewed crossing', () => {
    for (const b of built) {
      let skewed = 0;
      for (const c of b.rail.crossings) {
        const r = [...b.plan.roads, ...b.rail.roadsOut][c.road]!;
        // the share of the track's way along the street: sin of the angle off square
        const off = Math.abs(r.horizontal ? c.dx : c.dy);
        if (off > Math.sin((15 * Math.PI) / 180)) skewed++;
      }
      expect(skewed, b.site.name).toBeLessThanOrEqual(1);
    }
  });

  it('keeps to the town\'s grid inside the built-up area (east-west or north-south, square turns)', () => {
    for (const b of built) {
      const all = [...b.plan.candidates, ...b.plan.landmarks, b.plan.plaza];
      const x0 = Math.min(...all.map((r) => r.x)), x1 = Math.max(...all.map((r) => r.x + r.w)), y0 = Math.min(...all.map((r) => r.y)), y1 = Math.max(...all.map((r) => r.y + r.h));
      let along = 0, off = 0;
      for (const t of b.rail.tracks) for (let i = 0; i + 1 < t.pts.length; i++) {
        const p = t.pts[i]!, q = t.pts[i + 1]!;
        const mx = (p.x + q.x) / 2, my = (p.y + q.y) / 2;
        if (mx < x0 || mx > x1 || my < y0 || my > y1) continue;
        const L = Math.hypot(q.x - p.x, q.y - p.y);
        if (Math.min(Math.abs(q.x - p.x), Math.abs(q.y - p.y)) / L < 0.05) along += L; else off += L;
      }
      // (the corners' short curves are the only pieces off the grid)
      expect(off, b.site.name).toBeLessThanOrEqual(Math.max(80, (along + off) * 0.15));
    }
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
    expect(g.crossings.some((c) => c.road >= built[0]!.plan.roads.length || built[0]!.plan.roads[c.road]!.main)).toBe(true);
    expect(g.tracks.some((t) => t.kind === 'siding')).toBe(true);
    // (the farms' country roads are the roads out: the section road round the town is crossed too)
    expect(g.crossings.filter((c) => c.road >= built[0]!.plan.roads.length).length).toBeGreaterThanOrEqual(2);
  });

  it('keeps to the planned town\'s rail band: never through its blocks, the depot by the foot of Depot Street', () => {
    for (const b of built) {
      const v2 = b.plan.v2!;
      const band = v2.railBand!;
      let inBlocks = 0, total = 0;
      for (const t of b.rail.tracks) for (let i = 0; i + 1 < t.pts.length; i++) {
        const p = t.pts[i]!, q = t.pts[i + 1]!, L = Math.hypot(q.x - p.x, q.y - p.y);
        for (let s = 0; s < L; s += 1) {
          const x = p.x + ((q.x - p.x) * s) / L, y = p.y + ((q.y - p.y) * s) / L;
          total++;
          if (v2.blocks.some((k) => k.district !== 'rail' && k.district !== 'river' && x > k.x + 1 && x < k.x + k.w - 1 && y > k.y + 1 && y < k.y + k.h - 1)) inBlocks++;
        }
      }
      expect(inBlocks, `${b.site.name} through the town's blocks`).toBe(0);
      const d = b.rail.depot!;
      // (a town the world gives a through line across its band, not along it, has its depot on that line: the second band is to come)
      if (linksAt(b.w, b.cx, b.cy).filter((l) => l.dir === (band.horizontal ? 'ew' : 'ns')).length < 2 && linksAt(b.w, b.cx, b.cy).filter((l) => l.dir !== (band.horizontal ? 'ew' : 'ns')).length >= 2) continue;
      // on the band's track line, beside it on the town's side
      expect(Math.abs((band.horizontal ? d.at.y : d.at.x) - band.track), b.site.name).toBeLessThanOrEqual(2);
      const c = { x: d.rect.x + d.rect.w / 2, y: d.rect.y + d.rect.h / 2 };
      expect(Math.sign((band.horizontal ? c.y : c.x) - band.track), b.site.name).toBe(band.townSide);
      // (a parallel band: within a block of Depot Street's foot; a T-plan's line may meet its band from either end)
      if (band.horizontal) expect(Math.abs(d.at.x - band.depot.x), b.site.name).toBeLessThanOrEqual(60);
    }
  });
});
