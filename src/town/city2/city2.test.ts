import { describe, expect, it } from 'vitest';
import { corners, curvedShare, profileStats, type Alignment } from './align';
import { roadGround } from './ground';
import { buildRoads, JUNCTIONS, loopRoads, newTownGrid, ROADS } from './roads';
import { DISTRICTS, groundHeight, riverAt } from './site';

// The second city's prototype (kotlin-street/docs/CITY2.md): its roads meet where they are drawn, stay inside their
// limits, sit on the land, make the loop, and keep the loop's dove outline (the owner's constraint, 2026-11-25).

const roads = buildRoads();
const byId = (id: string) => roads.find((r) => r.spec.id === id)!;
const first = (a: Alignment) => a.samples[0]!;
const last = (a: Alignment) => a.samples[a.samples.length - 1]!;

describe('city 2 roads', () => {
  it('every road starts and ends exactly at its junctions, at their heights', () => {
    for (const { spec, a } of roads) {
      const [j0, j1] = spec.ends.map((e) => JUNCTIONS[e]!);
      expect(Math.hypot(first(a).x - j0!.at[0], first(a).z - j0!.at[1]), `${spec.id} start`).toBeLessThan(0.01);
      expect(Math.hypot(last(a).x - j1!.at[0], last(a).z - j1!.at[1]), `${spec.id} end`).toBeLessThan(0.01);
      expect(first(a).y, `${spec.id} start height`).toBeCloseTo(j0!.y, 1);
      expect(last(a).y, `${spec.id} end height`).toBeCloseTo(j1!.y, 1);
    }
  });

  it('keeps every road under its grade limit (over 10 m)', () => {
    for (const { spec, a } of roads) expect(profileStats(a).maxGrade, spec.id).toBeLessThanOrEqual(spec.maxGrade + 0.005);
  });

  it('stacks four hairpins on the Crown Road, nearly level through the turn', () => {
    const { a } = byId('crown');
    const pins = corners(a).filter((c) => c.sweepDeg > 150);
    expect(pins.length).toBe(4);
    for (const p of pins) {
      expect(p.radius).toBeGreaterThanOrEqual(16);
      expect(p.radius).toBeLessThanOrEqual(18);
      const s0 = a.samples[Math.round(p.s0)]!, s1 = a.samples[Math.round(p.s0 + p.len)]!;
      expect(Math.abs(s1.y - s0.y) / p.len).toBeLessThan(0.05);
    }
  });

  it('makes a closed loop of six roads', () => {
    const loop = loopRoads(roads);
    expect(loop.map((r) => r.spec.id)).toEqual(['esplanade', 'fellside', 'crown', 'ridge', 'neckhill', 'harbour']);
    for (let i = 0; i < loop.length; i++) expect(loop[i]!.spec.ends[1]).toBe(loop[(i + 1) % loop.length]!.spec.ends[0]);
    const len = loop.reduce((s, r) => s + r.a.length, 0);
    expect(len).toBeGreaterThan(12000);
    expect(len).toBeLessThan(16000);
  });

  it('meets at every junction of the loop without a crest or dip that throws a car (grade change under 6%)', () => {
    const loop = loopRoads(roads);
    for (let i = 0; i < loop.length; i++) {
      const a = loop[i]!.a, b = loop[(i + 1) % loop.length]!.a;
      const gIn = (last(a).y - a.samples[a.samples.length - 21]!.y) / 20;
      const gOut = (b.samples[20]!.y - first(b).y) / 20;
      expect(Math.abs(gOut - gIn), `${loop[i]!.spec.ends[1]}`).toBeLessThan(0.06);
    }
  });

  it('keeps the dove: the loop passes its outline points (the owner\'s constraint)', () => {
    // tail (Strand End), belly (the Esplanade's middle, Harbour Road), neck (Neck Hill), head (Neck Cross), back (Fellside
    // Road to the viaduct), the raised wing (the Crown Road's hairpins, the wingtip; the Ridge Drive to the Belvedere)
    const outline: [string, number, number][] = [
      ['strandEnd', -2693, 1963], ['esplanade', -1661, 919], ['harbour', -120, 245], ['neckhill', 656, 37], ['neck', 1264, -327],
      ['fellside', -2444, 226], ['fellside', -990, -965], ['crown', -610, -1420], ['crown', -415, -1100], ['ridge', 505, -1610], ['ridge', 1445, -917],
    ];
    const pts = loopRoads(roads).flatMap((r) => r.a.samples);
    for (const [what, x, z] of outline) {
      const d = Math.min(...pts.map((q) => Math.hypot(q.x - x, q.z - z)));
      expect(d, what).toBeLessThan(60);
    }
  });

  it('gives the loop every kind of corner', () => {
    const cs = loopRoads(roads).flatMap((r) => corners(r.a));
    expect(cs.filter((c) => c.vKmh < 60).length).toBeGreaterThanOrEqual(4); // the hairpins
    expect(cs.filter((c) => c.vKmh >= 120 && c.vKmh <= 200).length).toBeGreaterThanOrEqual(6); // the esses, the Belvedere
    expect(cs.filter((c) => c.vKmh > 220).length).toBeGreaterThanOrEqual(4); // the seafront sweepers
    const curved = roads.filter((r) => r.spec.cls !== 'expressway').map((r) => curvedShare(r.a) * r.a.length).reduce((a, b) => a + b, 0);
    const all = roads.filter((r) => r.spec.cls !== 'expressway').reduce((a, r) => a + r.a.length, 0);
    expect(curved / all).toBeGreaterThan(0.15);
  });

  it('sits on the land: no cut or fill over 45 m outside a bridge, viaduct or tunnel', () => {
    for (const { spec, a, spans } of roads) {
      if (spec.id === 'bayway') continue; // the elevated deck
      for (let i = 0; i < a.samples.length; i += 10) {
        const q = a.samples[i]!;
        if (spans.some((s) => q.s >= s.from && q.s <= s.to)) continue;
        expect(Math.abs(groundHeight(q.x, q.z) - q.y), `${spec.id} at ${Math.round(q.s)} m`).toBeLessThan(45);
      }
    }
  });

  it('bridges the Cleft high and tunnels under the Neck deep', () => {
    const f = byId('fellside');
    const v = f.spans.find((s) => s.kind === 'viaduct')!;
    let over = 0;
    for (const q of f.a.samples) if (q.s >= v.from && q.s <= v.to) { const r = riverAt(q.x, q.z); if (r.d < 25) over = Math.max(over, q.y - r.bed); }
    expect(over).toBeGreaterThan(35);
    const t = byId('tunnel');
    const span = t.spans[0]!;
    const mid = t.a.samples[Math.round((span.from + span.to) / 2)]!;
    expect(groundHeight(mid.x, mid.z) - mid.y).toBeGreaterThan(25);
  });

  it('benches the roads into the ground: on the carriageway the ground is the road', () => {
    const rg = roadGround(roads);
    const { a } = byId('ridge');
    for (const i of [300, 900, 1500, 2100]) {
      const q = a.samples[i]!;
      expect(rg.height(q.x, q.z)).toBeCloseTo(q.y, 1);
    }
  });

  it('is deterministic', () => {
    const again = buildRoads();
    expect(again.map((r) => r.a.samples[Math.floor(r.a.samples.length / 2)]!.y)).toEqual(roads.map((r) => r.a.samples[Math.floor(r.a.samples.length / 2)]!.y));
    expect(groundHeight(123.4, -567.8)).toBe(groundHeight(123.4, -567.8));
    const poly = DISTRICTS.find((d) => d.id === 'newtown')!.poly;
    expect(newTownGrid(undefined, poly)).toEqual(newTownGrid(undefined, poly));
    expect(newTownGrid('other', poly)).not.toEqual(newTownGrid(undefined, poly));
    expect(ROADS.length).toBe(roads.length);
  });
});
