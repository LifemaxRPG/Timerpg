import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { planTown, planVersion, setPlanVersion, type TownPlan } from './plan';
import { isCountySeat, platTown } from './plat';
import { glbPlotOf, glbTenures, REPLACED_LANDMARKS } from './glbSites';
import type { GlbLandmarkKind } from './glbLandmarks';
import { chooseLandmarks } from './landmarks';
import { storeNeeds } from './stores';
import { populationIn, POPULATION_BY, POPULATION_FROM } from './population';
import type { Rect } from './kinds';
import { runTimeline } from '../sim';

// The planned town (kotlin-street/docs/TOWN_PLAN.md): src/town/plat.ts and src/town/districts.ts through plan.ts.
const SEEDS = ['gamma', 'pi', 'orbit', 'tau', 'beta', 'rho'];
const hit = (a: Rect, b: Rect, pad = 0): boolean => a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;
const site = (k: string): string => glbPlotOf(k as GlbLandmarkKind).slice('glb:'.length);

let before: 1 | 2;
beforeAll(() => { before = planVersion(); setPlanVersion(2); });
afterAll(() => setPlanVersion(before));

describe('the planned town', () => {
  const plans = new Map<string, TownPlan>();
  const plan = (s: string): TownPlan => plans.get(s) ?? (plans.set(s, planTown(s)), plans.get(s)!);

  it('is a pure function of the seed', () => {
    const a = JSON.stringify(plan('gamma'));
    setPlanVersion(1); setPlanVersion(2);
    expect(JSON.stringify(planTown('gamma'))).toBe(a);
  });

  it('has a county seat among the test seeds, with the courthouse on its square', () => {
    expect(isCountySeat('rho')).toBe(true);
    const p = plan('rho');
    expect(p.v2!.countySeat).toBe(true);
    const c = p.v2!.reserved.find((q) => q.id === 'glb:county_courthouse')!;
    expect(c).toBeTruthy();
    // the whole square: its 80 m lot (38 tiles of 2.1 m) fits both ways
    expect(Math.min(c.w, c.h)).toBeGreaterThanOrEqual(38);
  });

  it('keeps lots, streets, reserved plots and water apart', () => {
    for (const s of SEEDS) {
      const p = plan(s);
      const lots = p.candidates;
      for (let i = 0; i < lots.length; i++) for (let j = i + 1; j < lots.length; j++) expect(hit(lots[i]!, lots[j]!), `${s} ${lots[i]!.id} ${lots[j]!.id}`).toBe(false);
      for (const l of lots) for (const r of p.roads) expect(hit(l, r, 1), `${s} ${l.id} on a street`).toBe(false);
      const res = p.v2!.reserved.filter((q) => q.id !== 'civic:depot');
      for (const l of lots) for (const q of res) expect(hit(l, q), `${s} ${l.id} on ${q.id}`).toBe(false);
      // (the supermarket may take the drive-in's ground: a chain of uses, never at once)
      for (let i = 0; i < res.length; i++) for (let j = i + 1; j < res.length; j++) {
        const pair = [res[i]!.id, res[j]!.id].sort().join(' ');
        if (pair === 'glb:starlight_drive_in landmark:supermarket') continue;
        expect(hit(res[i]!, res[j]!), `${s} ${pair}`).toBe(false);
      }
      for (const q of res) for (const r of p.roads) expect(hit(q, r), `${s} ${q.id} on a street`).toBe(false);
      if (p.water) for (const l of lots) {
        for (let y = l.y; y < l.y + l.h; y++) for (let x = l.x; x < l.x + l.w; x++) expect(p.water.raw[y * p.width + x], `${s} ${l.id} in the water`).toBeFalsy();
      }
    }
  });

  it('connects every street to Main Street', () => {
    for (const s of SEEDS) {
      const p = plan(s);
      const touch = (a: Rect, b: Rect): boolean => a.x <= b.x + b.w + 1 && b.x <= a.x + a.w + 1 && a.y <= b.y + b.h + 1 && b.y <= a.y + a.h + 1;
      const seen = new Set(p.roads.map((r, i) => (r.main ? i : -1)).filter((i) => i >= 0));
      for (let grew = true; grew;) {
        grew = false;
        p.roads.forEach((r, i) => { if (!seen.has(i) && [...seen].some((j) => touch(r, p.roads[j]!))) { seen.add(i); grew = true; } });
      }
      expect(seen.size, s).toBe(p.roads.length);
      // Main Street runs east-west, one street
      expect(p.roads.filter((r) => r.main).length).toBe(1);
      expect(p.roads.find((r) => r.main)!.horizontal).toBe(true);
    }
  });

  it('reserves a plot in its district for every model, civic building and store the town earns', () => {
    for (const s of SEEDS) {
      const p = plan(s);
      const ids = p.v2!.reserved.map((q) => q.id);
      for (const t of glbTenures(s)) expect(ids, `${s} ${t.kind}`).toContain(`glb:${site(t.kind)}`);
      const lm = p.landmarks.map((l) => l.id);
      // (the capital's school campus, fire station and post office are models: the sprite landmarks they replace are not in its plan)
      const gone = Object.entries(REPLACED_LANDMARKS).filter(([m]) => glbTenures(s).some((t) => glbPlotOf(t.kind) === `glb:${m}`)).map(([, k]) => k as string);
      for (const k of chooseLandmarks(s)) if (!gone.includes(k)) expect(lm, `${s} ${k}`).toContain(k);
      for (const [k, y] of Object.entries(storeNeeds(s))) if (y !== undefined) expect(lm, `${s} ${k}`).toContain(k);
      expect(lm).toContain('generalstore');
      const district = (id: string): string => p.v2!.reserved.find((q) => q.id === id)?.district ?? '';
      if (ids.includes('glb:gazette_office')) expect(district('glb:gazette_office')).toBe('core');
      for (const k of ['junction_diner', 'roadside_motel', 'starling_lanes', 'coin_laundromat', 'reel_time_video', 'club', 'starlight_drive_in']) {
        if (ids.includes(`glb:${k}`)) expect(district(`glb:${k}`), `${s} ${k}`).toBe('strip');
      }
      if (ids.includes('glb:feed_mill')) expect(district('glb:feed_mill')).toBe('rail');
      if (ids.includes('glb:foundry')) expect(district('glb:foundry')).toBe('rail');
      if (ids.includes('landmark:cinema')) expect(district('landmark:cinema')).toBe('core');
    }
  });

  it('keeps every core face a street wall and the strip apart from it', () => {
    for (const s of SEEDS) {
      const p = plan(s);
      const shops = p.candidates.filter((c) => c.kind === 'shop');
      // the core's shops are at the pavement shoulder to shoulder; the strip's stand alone behind their car parks
      for (const c of shops) expect(['core', 'strip']).toContain(c.district);
      const core = shops.filter((c) => c.district === 'core'), strip = shops.filter((c) => c.district === 'strip');
      for (const a of strip) for (const b of core) expect(hit(a, b, 6), `${s} ${a.id} by ${b.id}`).toBe(false);
      expect(core.length, s).toBeGreaterThanOrEqual(16);
      expect(shops.length, s).toBeGreaterThanOrEqual(26);
    }
  });

  it('builds homes enough for the households at the peak, and fills outward by district', () => {
    for (const s of SEEDS) {
      const p = plan(s);
      let peak = 0;
      for (let y = POPULATION_FROM; y <= POPULATION_BY; y++) peak = Math.max(peak, populationIn(s, y) / 3.2);
      const built = p.candidates.filter((c) => c.kind !== 'shop' && c.from! <= POPULATION_BY).length;
      expect(built, s).toBeGreaterThanOrEqual(peak);
      // a later subdivision's lot is never built before the old town's first
      const first = (d: string): number => Math.min(...p.candidates.filter((c) => c.district === d && c.kind !== 'shop').map((c) => c.from!));
      if (p.candidates.some((c) => c.district === 'late')) expect(first('late')).toBeGreaterThanOrEqual(first('old'));
    }
  });

  it('varies the plan between seeds', () => {
    const forms = new Set(SEEDS.map((s) => `${platTown(s).form}${platTown(s).flipX}${platTown(s).flipY}`));
    expect(forms.size).toBeGreaterThanOrEqual(3);
    const widths = new Set(SEEDS.map((s) => plan(s).width));
    expect(widths.size).toBe(SEEDS.length);
  });

  it('runs the river through the core with bridges on Main Street and at least one parallel street', () => {
    for (const s of SEEDS) {
      const p = plan(s);
      if (p.water?.kind !== 'river') continue;
      const main = p.roads.find((r) => r.main)!;
      const bridges = p.water.bridges.filter((b) => b.horizontal);
      expect(bridges.some((b) => b.y <= main.y && b.y + b.h >= main.y + main.h), s).toBe(true);
      expect(bridges.filter((b) => !(b.y <= main.y && b.y + b.h >= main.y + main.h)).length, s).toBeGreaterThanOrEqual(1);
      // through downtown: core blocks on both banks where the river crosses Main Street, and a core street besides Main bridging it
      const col = p.v2!.blocks.filter((b) => b.district === 'river' && (b.row === 0 || b.row === -1));
      expect(col.length, s).toBeGreaterThan(0);
      for (const c of col) {
        const core = p.v2!.blocks.filter((b) => b.district === 'core' && b.row === c.row);
        expect(core.some((b) => Math.abs(b.x + b.w - c.x) <= 6), `${s} core west of the river`).toBe(true);
        expect(core.some((b) => Math.abs(c.x + c.w - b.x) <= 6), `${s} core east of the river`).toBe(true);
      }
      // the core straddles the river: Main Street's bridge inside the core's span, and the core's blocks along Main Street
      // (the civic block's column too) as many on each bank, give or take one
      const mainBridge = bridges.find((b) => b.y <= main.y && b.y + b.h >= main.y + main.h)!;
      const midX = mainBridge.x + mainBridge.w / 2;
      const onMain = p.v2!.blocks.filter((b) => (b.district === 'core' || b.district === 'square') && (b.row === 0 || b.row === -1));
      const coreX0 = Math.min(...onMain.map((b) => b.x)), coreX1 = Math.max(...onMain.map((b) => b.x + b.w));
      expect(mainBridge.x > coreX0 && mainBridge.x + mainBridge.w < coreX1, `${s} Main Street's bridge inside the core`).toBe(true);
      const cols = (west: boolean): number => new Set(onMain.filter((b) => (b.x + b.w / 2 < midX) === west).map((b) => b.col)).size;
      expect(Math.abs(cols(true) - cols(false)), `${s} core blocks each bank: ${cols(true)} | ${cols(false)}`).toBeLessThanOrEqual(1);
      // (the civic block one block from the river: a core block between them)
      const civic = p.v2!.blocks.find((b) => b.col === 0 && b.row === -1)!;
      const between = onMain.filter((b) => b.row === -1 && b !== civic && (b.x + b.w / 2 - midX) * (civic.x + civic.w / 2 - midX) > 0 && Math.abs(b.x + b.w / 2 - midX) < Math.abs(civic.x + civic.w / 2 - midX));
      expect(between.length, `${s} one block between the civic block and the river`).toBe(1);
      const coreRows = p.v2!.blocks.filter((b) => b.district === 'core');
      const y0 = Math.min(...coreRows.map((b) => b.y)) - 6, y1 = Math.max(...coreRows.map((b) => b.y + b.h)) + 6;
      expect(bridges.filter((b) => b.y >= y0 && b.y + b.h <= y1).length, `${s} core bridges`).toBeGreaterThanOrEqual(2);
    }
  });

  it('stands a mill town\'s works on the river where it meets the rail side', () => {
    let n = 0;
    for (const s of ['pi', 'rho', ...SEEDS]) {
      const p = plan(s);
      const w = p.v2!.reserved.find((q) => q.id === 'glb:foundry');
      if (!w || !['mill', 'mining'].includes(platTown(s).type) || p.water?.kind !== 'river') continue;
      n++;
      expect(w.district).toBe('rail');
      let near = Infinity;
      for (let y = w.y; y < w.y + w.h; y++) for (let x = 0; x < p.width; x++) if (p.water.raw[y * p.width + x]) near = Math.min(near, Math.abs(x - (x < w.x ? w.x : w.x + w.w)));
      expect(near, s).toBeLessThanOrEqual(40);
    }
    expect(n).toBeGreaterThan(0);
  });

  it('keeps room for more: empty civic and landmark plots reserved last in the ledger (a new model never moves a plot)', () => {
    for (const s of SEEDS) {
      const ids = plan(s).v2!.reserved.map((q) => q.id);
      const fut = ids.filter((i) => i.startsWith('future:'));
      expect(fut.length, s).toBeGreaterThanOrEqual(3);
      // (every future plot after every other: the ledger's order is the claim order)
      expect(ids.slice(ids.findIndex((i) => i.startsWith('future:'))).every((i) => i.startsWith('future:')), s).toBe(true);
      for (const q of plan(s).v2!.reserved.filter((r) => r.id.startsWith('future:'))) expect(q.chain.every((u) => u.use === 'reserved')).toBe(true);
    }
  });

  it('changes who lives where, never who exists: people, businesses and events as under the old plan', () => {
    for (const seed of ['gamma', 'pi', 'orbit', 'tau', 'beta', 'rho', 'alpha', 'eta']) {
      const run = (v: 1 | 2) => {
        setPlanVersion(v);
        const t = runTimeline({ seed, endYear: 1985 });
        const w = t.snapshots[t.snapshots.length - 1]!;
        return JSON.stringify({ people: Object.keys(w.people).sort(), alive: Object.values(w.people).filter((p) => p.deathYear === undefined).length,
          businesses: Object.values(w.businesses).map((b) => `${b.id}:${b.name}:${b.closedYear ?? ''}`).sort(), events: t.chronicle.map((e) => JSON.stringify(e)) });
      };
      const a = run(1), b = run(2);
      expect(b === a, seed).toBe(true);
    }
    setPlanVersion(2);
  }, 300000);
});
