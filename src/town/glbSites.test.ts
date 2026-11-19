import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { railSite, type RailWorld } from '../world/rail';
import { GLB_LANDMARK_KINDS, GLB_LANDMARKS, type GlbLandmarkKind } from './glbLandmarks';
import { FUNERAL_NAMES, VIDEO_PEOPLE, VIDEO_STATES, glbSites, glbStandingOn, glbTenures, glbUnplaced, peakPeople, premierDepot, type GlbSite } from './glbSites';
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

  it('give a town of 1,500 and more a funeral home: a converted house, from the seed, standing to the present', () => {
    const kind: GlbLandmarkKind = 'landmark_glb_funeral_home';
    let placed = 0;
    for (const seed of [...SEEDS, 'theta', 'kappa', 'beta', 'zeta']) {
      const ts = glbTenures(seed).filter((t) => t.kind === kind);
      // one in every town of 1,500 and more, none below
      expect(ts.length, seed).toBe(peakPeople(seed) >= 1500 ? 1 : 0);
      for (const t of ts) {
        // converted 1935-60, from a house built by then (the design's years 1910-35) and in the town's early years
        expect(t.opened, `${seed} converted in 1935-60`).toBeGreaterThanOrEqual(1935);
        expect(t.opened).toBeLessThanOrEqual(1960);
        expect(t.houseBuilt!, `${seed} house built before the conversion`).toBeLessThan(t.opened);
        expect(t.houseBuilt!).toBeGreaterThanOrEqual(1910);
        expect(t.houseBuilt!).toBeLessThanOrEqual(1935);
        // a funeral home to the present, its family name painted from the seed (the sign keeps the founders' name)
        expect(t.closed).toBeUndefined(); expect(t.gone).toBeUndefined();
        expect(t.variant!).toBeGreaterThanOrEqual(0); expect(t.variant!).toBeLessThan(FUNERAL_NAMES.length);
        expect(t.name).toBe(FUNERAL_NAMES[t.variant!]);
        expect(t.owners![0]).toEqual({ from: t.opened, family: t.name.split(' & ')[0] });
        for (let i = 1; i < t.owners!.length; i++) { expect(t.owners![i]!.from).toBeGreaterThan(t.owners![i - 1]!.from); expect(t.owners![i]!.family).not.toBe(t.owners![i - 1]!.family); }
        expect(JSON.stringify(glbTenures(seed))).toBe(JSON.stringify(glbTenures(seed)));
      }
    }
    // placed: on the SEEDS' plans, a house lot on a side street, open ground until the conversion, standing from it to 2025
    for (const seed of SEEDS) {
      const { sites } = town(seed);
      const site = sites.find((s) => s.tenures.some((t) => t.kind === kind));
      if (!site) continue;
      placed++;
      const t = site.tenures[0]!;
      expect(site.lotType, seed).toBe('house');
      expect(site.tenures.length, `${seed}: nothing else on a house lot`).toBe(1);
      expect(planTown(seed).roads[site.road]!.main, `${seed} not on the main street (no highway strip)`).toBe(false);
      expect(glbStandingOn(site, t.opened - 1), seed).toBeUndefined();
      for (let y = t.opened; y <= 2025; y++) expect(glbStandingOn(site, y)?.state, `${seed} ${y}`).toBe('open');
    }
    expect(placed).toBeGreaterThanOrEqual(2);
  }, 120000);

  it('put the strip centre on one plot through its four states, in date order, in a town of 2,500 and more', () => {
    let placed = 0;
    for (const seed of [...SEEDS, 'theta', 'kappa', 'beta', 'zeta']) {
      const ts = glbTenures(seed).filter((t) => VIDEO_STATES.includes(t.kind));
      expect(ts.length, seed).toBe(peakPeople(seed) >= VIDEO_PEOPLE ? 4 : 0);
      if (!ts.length) continue;
      // the four states in date order, each ending where the next begins: no gap and no overlap
      expect(ts.map((t) => t.kind), seed).toEqual([...VIDEO_STATES]);
      for (let i = 0; i < 4; i++) {
        expect(ts[i]!.opened, `${seed} state ${i} in its era`).toBeGreaterThanOrEqual(GLB_LANDMARKS[ts[i]!.kind].era.from);
        expect(ts[i]!.opened).toBeLessThanOrEqual(GLB_LANDMARKS[ts[i]!.kind].era.to);
        if (i < 3) expect(ts[i]!.gone, `${seed} state ${i} ends as the next begins`).toBe(ts[i + 1]!.opened);
        else expect(ts[i]!.gone, 'the last state stands to the present').toBeUndefined();
      }
      const [a, b, c, d] = ts as [typeof ts[0], typeof ts[0], typeof ts[0], typeof ts[0]];
      // the strip is built 1972-82, the store opens 1980-86 (never before the strip), takes its second bay 1990-97, closes down
      // 2008-13 for a year or two, and the bay is reused from 2009-15
      expect(a!.opened).toBeGreaterThanOrEqual(1972); expect(a!.opened).toBeLessThanOrEqual(1982);
      expect(a!.shopOpened!).toBeGreaterThanOrEqual(Math.max(1980, a!.opened)); expect(a!.shopOpened!).toBeLessThanOrEqual(1986);
      expect(b!.opened).toBeGreaterThanOrEqual(Math.max(1990, a!.shopOpened! + 4)); expect(b!.opened).toBeLessThanOrEqual(1997);
      expect(c!.opened).toBeGreaterThanOrEqual(2008); expect(c!.opened).toBeLessThanOrEqual(2013);
      expect(d!.opened - c!.opened, `${seed} closing down for a year or two`).toBeGreaterThanOrEqual(1);
      expect(d!.opened - c!.opened).toBeLessThanOrEqual(2);
      expect(d!.name).toBe('Cell Fix');
      for (const t of [a!, b!, c!]) expect(t.name).toBe('Reel Time Video');
      expect(JSON.stringify(glbTenures(seed))).toBe(JSON.stringify(glbTenures(seed)));
    }
    // placed: all four on ONE plot, the 1984 state before the store opens, each state in its years, never two at once
    for (const seed of SEEDS) {
      const { sites } = town(seed);
      const site = sites.find((s) => s.tenures.some((t) => VIDEO_STATES.includes(t.kind)));
      if (!site) continue;
      placed++;
      expect(site.tenures.map((t) => t.kind), `${seed}: one plot, four states`).toEqual([...VIDEO_STATES]);
      expect(site.lotType).toBe('highway');
      expect(sites.filter((s) => s.tenures.some((t) => VIDEO_STATES.includes(t.kind))).length, `${seed} one site`).toBe(1);
      const t0 = site.tenures[0]!;
      expect(glbStandingOn(site, t0.opened - 1), `${seed} open ground before the strip is built`).toBeUndefined();
      for (let y = t0.opened; y <= 2025; y++) {
        const st = glbStandingOn(site, y)!;
        expect(st, `${seed} ${y}`).toBeDefined();
        const want = VIDEO_STATES.filter((k) => site.tenures.find((t) => t.kind === k)!.opened <= y).pop();
        expect(st.tenure.kind, `${seed} ${y}`).toBe(want);
        expect(st.state).toBe('open');
      }
      // before the store opens the 1984 state stands
      expect(glbStandingOn(site, Math.max(t0.opened, t0.shopOpened! - 1))?.tenure.kind).toBe(VIDEO_STATES[0]);
      // its own plot, not beside a coin laundry's (the strip's neighbour bay is a laundry)
      for (const o of sites) if (o.tenures.some((t) => t.kind === 'landmark_glb_coin_laundromat')) expect(hit(site.rect, o.rect, 10), `${seed} beside the coin laundry`).toBe(false);
    }
    // it appears in some seed, and in gamma
    expect(placed).toBeGreaterThanOrEqual(2);
    expect(town('gamma').sites.some((s) => s.tenures.some((t) => t.kind === VIDEO_STATES[0]))).toBe(true);
  }, 120000);

  it('do not let the strip centre cost another model its plot (gamma gets the feed mill back)', () => {
    const gone = (seed: string): string[] => glbUnplaced(seed, town(seed).sites).filter((k) => !VIDEO_STATES.includes(k));
    // gamma: the motel and the bowling alley are what its east strip cannot hold with the strip centre; the feed mill is placed
    expect(gone('gamma')).toEqual(['landmark_glb_roadside_motel', 'landmark_glb_starling_lanes']);
    expect(town('gamma').sites.some((s) => s.tenures.some((t) => t.kind === 'landmark_glb_feed_mill'))).toBe(true);
    for (const seed of ['mu', 'quartz', 'orbit']) expect(gone(seed).length, seed).toBeLessThanOrEqual(3);
  }, 120000);

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
