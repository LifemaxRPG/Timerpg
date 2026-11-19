import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { railSite, type RailWorld } from '../world/rail';
import { GLB_LANDMARK_KINDS, GLB_LANDMARKS, type GlbLandmarkKind } from './glbLandmarks';
import { glbSites, glbStandingOn, glbTenures, glbUnplaced, premierDepot, type GlbSite } from './glbSites';
import { planTown } from './plan';
import { townRail, type TownRail } from './rail';
import type { Rect } from './kinds';

// The hand-made landmark models in the generated towns (src/town/glbSites.ts): deterministic, clear of everything, in
// their years, one club at a time, the depot on its track, and every model somewhere.

const SEEDS = ['gamma', 'mu', 'quartz', 'orbit'];
const hit = (a: Rect, b: Rect, pad = 0): boolean => a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;

interface Town { seed: string; sites: GlbSite[]; rail: TownRail; used: Set<string> }
const towns = new Map<string, Town>();
function town(seed: string): Town {
  let t = towns.get(seed);
  if (t) return t;
  const plan = planTown(seed);
  const w: RailWorld = { seed: 'world-6', home: seed };
  const rail = townRail(w, railSite(w, 0, 0), plan);
  const lots = worldAt(runTimeline({ seed, endYear: 2025 }), 2025).settlement!.properties;
  const input = { lots, rail: { tracks: rail.tracks.map((q) => q.pts), rects: [rail.depot?.rect, rail.depot?.premier, rail.elevator].filter((r): r is Rect => !!r), elevator: rail.elevator } };
  t = { seed, sites: glbSites(plan, input), rail, used: new Set(lots.map((l) => l.slot)) };
  towns.set(seed, t);
  return t;
}

describe('hand-made landmark models in the towns', () => {
  it('are placed the same way every time', () => {
    const a = town('gamma');
    const plan = planTown('gamma');
    const lots = worldAt(runTimeline({ seed: 'gamma', endYear: 2025 }), 2025).settlement!.properties;
    const b = glbSites(plan, { lots, rail: { tracks: a.rail.tracks.map((q) => q.pts), rects: [a.rail.depot?.rect, a.rail.depot?.premier, a.rail.elevator].filter((r): r is Rect => !!r), elevator: a.rail.elevator } });
    expect(JSON.stringify(b)).toBe(JSON.stringify(a.sites));
    expect(JSON.stringify(glbTenures('mu'))).toBe(JSON.stringify(glbTenures('mu')));
  }, 60000);

  it('keep off the streets, the river, the track, the plaza, the landmarks, the lots and each other', () => {
    for (const seed of SEEDS) {
      const { sites, rail, used } = town(seed);
      const plan = planTown(seed);
      for (const s of sites) {
        const r = s.rect;
        for (const road of plan.roads) expect(hit(r, road), `${seed} ${s.id} on a street`).toBe(false);
        // its street is a tile off its front
        const front = plan.roads[s.road]!;
        expect(hit(r, front, 1.01), `${seed} ${s.id} fronts its street`).toBe(true);
        if (plan.water) for (let y = r.y; y < r.y + r.h; y++) for (let x = r.x; x < r.x + r.w; x++) expect(plan.water.raw[y * plan.width + x], `${seed} ${s.id} in the river`).toBe(0);
        for (const t of rail.tracks) for (const p of t.pts) {
          const dx = Math.max(r.x - p.x, 0, p.x - (r.x + r.w)), dy = Math.max(r.y - p.y, 0, p.y - (r.y + r.h));
          expect(Math.hypot(dx, dy), `${seed} ${s.id} by the track`).toBeGreaterThanOrEqual(3);
        }
        for (const q of [rail.depot?.rect, rail.depot?.premier, rail.elevator]) if (q) expect(hit(r, q), `${seed} ${s.id} on the depot`).toBe(false);
        expect(hit(r, plan.plaza), `${seed} ${s.id} in the plaza`).toBe(false);
        for (const l of plan.landmarks) expect(hit(r, l), `${seed} ${s.id} on ${l.id}`).toBe(false);
        for (const c of plan.candidates) if (used.has(c.id)) expect(hit(r, c), `${seed} ${s.id} on lot ${c.id}`).toBe(false);
        for (const o of sites) if (o !== s) expect(hit(r, o.rect), `${seed} ${s.id} on ${o.id}`).toBe(false);
      }
    }
  }, 120000);

  it('stand in their years, one business at a time on a plot', () => {
    for (const seed of SEEDS) for (const s of town(seed).sites) {
      for (const t of s.tenures) {
        const { from, to } = GLB_LANDMARKS[t.kind].era;
        expect(t.opened, `${seed} ${t.kind} opens in its era`).toBeGreaterThanOrEqual(from);
        expect(t.opened, `${seed} ${t.kind} opens in its era`).toBeLessThanOrEqual(to);
        if (t.closed !== undefined) expect(t.closed).toBeGreaterThan(t.opened);
        if (t.gone !== undefined) expect(t.gone).toBeGreaterThan(t.opened);
        if (t.closed !== undefined && t.gone !== undefined) expect(t.gone).toBeGreaterThanOrEqual(t.closed);
        // nothing before it opens or after it is pulled down
        expect(glbStandingOn(s, t.opened - 1)?.tenure).not.toBe(t);
        if (t.gone !== undefined) expect(glbStandingOn(s, t.gone)?.tenure).not.toBe(t);
      }
      for (let i = 1; i < s.tenures.length; i++) expect(s.tenures[i - 1]!.gone, `${seed} ${s.id} pulled down before the next`).toBeLessThanOrEqual(s.tenures[i]!.opened);
    }
    // the 1950s station after its pumps: a repair garage with them hidden, or shut
    const st = SEEDS.flatMap((seed) => town(seed).sites).flatMap((s) => s.tenures).filter((t) => t.kind === 'landmark_glb_main_street_service_station');
    for (const t of st) expect(t.closed, 'the 1950s station stops selling gas').toBeLessThanOrEqual(1990);
  }, 120000);

  it('have one club at a time', () => {
    const clubs: GlbLandmarkKind[] = ['landmark_glb_mirage_nightclub', 'landmark_glb_pulse_nightclub', 'landmark_glb_foundry_nightclub'];
    for (const seed of [...SEEDS, 'theta', 'zeta', 'kappa', 'lambda']) {
      const ts = glbTenures(seed).filter((t) => clubs.includes(t.kind));
      expect(ts.length).toBeLessThanOrEqual(2);
      for (let y = 1950; y <= 2025; y++) expect(ts.filter((t) => y >= t.opened && (t.gone === undefined || y < t.gone)).length, `${seed} ${y}`).toBeLessThanOrEqual(1);
      // a Pulse comes after a Mirage, never with it
      if (ts.length === 2) expect(ts[0]!.kind).toBe('landmark_glb_mirage_nightclub');
    }
    // a Pulse on its Mirage's plot
    const mu = town('mu').sites.find((s) => s.tenures.some((t) => t.kind === 'landmark_glb_mirage_nightclub'));
    if (mu && glbTenures('mu').some((t) => t.kind === 'landmark_glb_pulse_nightclub')) expect(mu.tenures.map((t) => t.kind)).toContain('landmark_glb_pulse_nightclub');
  }, 60000);

  it('keep the works and The Foundry on one plot, in order', () => {
    let seen = 0;
    for (const seed of [...SEEDS, 'theta', 'kappa', 'beta']) {
      const ts = glbTenures(seed);
      const works = ts.find((t) => t.kind === 'landmark_glb_foundry_factory');
      const club = ts.find((t) => t.kind === 'landmark_glb_foundry_nightclub');
      // no Foundry without its works
      if (club) expect(works, seed).toBeDefined();
      if (!works) continue;
      // opens 1900-45, shuts 1970-2005, stands dark at least five years, then the club 2010-29 (or it stays shut)
      expect(works.opened).toBeGreaterThanOrEqual(1900); expect(works.opened).toBeLessThanOrEqual(1945);
      expect(works.closed!).toBeGreaterThanOrEqual(1970); expect(works.closed!).toBeLessThanOrEqual(2005);
      expect(works.variant).toBeGreaterThanOrEqual(0);
      if (club) {
        seen++;
        expect(club.opened - works.closed!).toBeGreaterThanOrEqual(5);
        expect(club.opened).toBeGreaterThanOrEqual(2010);
        expect(works.gone).toBe(club.opened);
        if (SEEDS.includes(seed)) {
          const site = town(seed).sites.find((s) => s.tenures.includes(works) || s.tenures.some((t) => t.kind === works.kind));
          expect(site?.tenures.map((t) => t.kind), seed).toEqual(['landmark_glb_foundry_factory', 'landmark_glb_foundry_nightclub']);
          for (let y = works.opened; y <= 2025; y++) {
            const st = glbStandingOn(site!, y)!;
            expect(st.tenure.kind, `${seed} ${y}`).toBe(y < club.opened ? 'landmark_glb_foundry_factory' : 'landmark_glb_foundry_nightclub');
            if (y >= works.closed! && y < club.opened) expect(st.state).toBe('closed');
          }
        }
      } else expect(works.gone).toBeUndefined();
    }
    expect(seen).toBeGreaterThan(0);
  }, 60000);

  it('put the hand-made depot on its track in a town of 3,000 and more', () => {
    // (gamma's own, laid round the 3D town's yards, is checked on the shipped rail.json: tools/town-glb.test.ts)
    expect(SEEDS.filter((seed) => town(seed).rail.depot?.premier).length).toBeGreaterThanOrEqual(2);
    for (const seed of SEEDS) {
      const { rail } = town(seed);
      const d = rail.depot;
      if (!d) continue;
      if (!premierDepot(seed)) { expect(d.premier).toBeUndefined(); continue; }
      // (where its whole plot is clear beside the depot's stretch: else the halt)
      if (!d.premier) continue;
      const p = d.premier;
      // the platform side 3 tiles from the track's centre, along the depot's stretch, 26 tiles long
      const nx = d.dy * d.side, ny = -d.dx * d.side;
      const near = { x: d.at.x + nx * 3, y: d.at.y + ny * 3 };
      expect(near.x >= p.x - 0.6 && near.x <= p.x + p.w + 0.6 && near.y >= p.y - 0.6 && near.y <= p.y + p.h + 0.6, `${seed} platform by the track`).toBe(true);
      expect(Math.max(p.w, p.h)).toBe(26);
      // the depot's point is on the track
      const on = rail.tracks.some((t) => t.pts.some((q, i) => i > 0 && (() => {
        const a = t.pts[i - 1]!, l = Math.hypot(q.x - a.x, q.y - a.y) || 1;
        const u = Math.max(0, Math.min(1, ((d.at.x - a.x) * (q.x - a.x) + (d.at.y - a.y) * (q.y - a.y)) / (l * l)));
        return Math.hypot(a.x + (q.x - a.x) * u - d.at.x, a.y + (q.y - a.y) * u - d.at.y) < 0.6;
      })()));
      expect(on, `${seed} depot on the track`).toBe(true);
    }
  }, 60000);

  it('place every model in some town and year', () => {
    const seen = new Set<string>();
    for (const seed of SEEDS) for (const s of town(seed).sites) for (const t of s.tenures) seen.add(t.kind);
    for (const seed of SEEDS) if (town(seed).rail.depot?.premier) seen.add('landmark_glb_premier_railroad_depot');
    expect(GLB_LANDMARK_KINDS.filter((k) => !seen.has(k))).toEqual([]);
    // and gamma, the app's town, finds a plot for nearly all it earns
    expect(glbUnplaced('gamma', town('gamma').sites).length).toBeLessThanOrEqual(2);
    // 1955 and 1985 show something standing
    for (const y of [1955, 1985]) expect(SEEDS.some((seed) => town(seed).sites.some((s) => glbStandingOn(s, y)))).toBe(true);
  }, 60000);
});
