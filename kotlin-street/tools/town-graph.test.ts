// The whole-town graph (tools/town-graph.ts, street.json v2) for the town of tools/town-config.ts: deterministic, every
// building on exactly one row, no footprints overlapping (each fits its plot, none in a street or the river), every row
// end's way on has its way back, the whole town walkable from the spawn, the Kotlin fixture current, and v1 untouched.
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { townGraph, townLots, type RowJson } from './town-graph';
import { townStreets } from './town-streets';
import { TOWN } from './town-config';
import { apartmentNearCore, storeysOf } from './town-downtown';

const g = townGraph(TOWN.seed, TOWN.year);
const rows = new Map(g.graph.rows.map((r) => [r.id, r]));

describe('town graph', () => {
  it('is deterministic', () => {
    expect(JSON.stringify(townGraph(TOWN.seed, TOWN.year).json)).toBe(JSON.stringify(g.json));
  });

  it('puts every lot and landmark on exactly one row', () => {
    const where = new Map<string, string[]>();
    for (const r of g.graph.rows) for (const b of r.buildings) where.set(b.lot, [...(where.get(b.lot) ?? []), r.id]);
    const want = [...townLots(g.layout).map((l) => l.slot), ...g.layout.landmarks.map((l) => `landmark:${l.id}`), ...(g.graph.extras ?? []).map((x) => x.id),
      ...(g.graph.glbLandmarks ?? []).map((x) => x.landmark)];
    for (const id of want) expect(where.get(id), id).toHaveLength(1);
    expect(where.size).toBe(want.length);
    expect(g.graph.lots.map((l) => l.id).sort()).toEqual(townLots(g.layout).map((l) => l.slot).sort());
    for (const l of g.graph.landmarks) expect(rows.get(l.row)?.buildings.some((b) => b.sprite === l.sprite), l.id).toBe(true);
  });

  it('has no overlapping footprints after scaling, each in its plot, clear of streets and water', () => {
    expect(g.conflicts).toEqual([]);
    const f = g.footprints;
    for (let i = 0; i < f.length; i++) for (let j = i + 1; j < f.length; j++) {
      const a = f[i]!.rect, b = f[j]!.rect;
      const overlap = a[0] < b[2] - 0.01 && b[0] < a[2] - 0.01 && a[1] < b[3] - 0.01 && b[1] < a[3] - 0.01;
      expect(overlap, `${f[i]!.sprite} / ${f[j]!.sprite}`).toBe(false);
    }
    // lots do not overlap either (their yards and gardens)
    const L = g.graph.lots;
    for (let i = 0; i < L.length; i++) for (let j = i + 1; j < L.length; j++) {
      const a = L[i]!.rect, b = L[j]!.rect;
      expect(a[0] < b[2] - 0.01 && b[0] < a[2] - 0.01 && a[1] < b[3] - 0.01 && b[1] < a[3] - 0.01, `${L[i]!.id} / ${L[j]!.id}`).toBe(false);
    }
    // buildings keep their art size: a row's buildings never overlap along it, and stand in its length past a mouth
    for (const r of g.graph.rows) {
      // (the buildings with nobody in them stand behind the others, a tower behind the shops: apart in depth, not along)
      const front = r.buildings.filter((b) => b.kind !== 'extra');
      for (let i = 1; i < front.length; i++) expect(front[i]!.x0).toBeGreaterThanOrEqual(front[i - 1]!.x1 - 1e-3);
      for (const x of r.buildings.filter((b) => b.kind === 'extra')) for (const b of front) {
        if (!(b.x1 > x.x0 && b.x0 < x.x1)) continue;
        // (a strip shop's own car park in front of it, a tower or car park behind the shops of a street wall)
        if (x.setback < 1) expect(x.setback + x.depth, `${x.sprite} before ${b.sprite}`).toBeLessThanOrEqual(b.setback);
        else expect(x.setback, `${x.sprite} behind ${b.sprite}`).toBeGreaterThanOrEqual(b.setback + b.depth + 40);
      }
      if (r.ends.start.mouth && r.buildings.length) expect(r.buildings[0]!.x0).toBeGreaterThanOrEqual(89 - 1e-3);
      if (r.ends.end.mouth && r.buildings.length) expect(r.buildings[r.buildings.length - 1]!.x1).toBeLessThanOrEqual(r.length - 89 + 1e-3);
    }
  });

  it('has every transition both ways', () => {
    for (const r of g.graph.rows) {
      for (const k of ['straight', 'turnIn', 'cross'] as const) {
        const e = r.ends.end[k];
        if (e) expect(rows.get(e)!.ends.start[k], `${r.id}.end.${k} -> ${e}`).toBe(r.id);
        const s = r.ends.start[k];
        if (s) expect(rows.get(s)!.ends.end[k], `${r.id}.start.${k} -> ${s}`).toBe(r.id);
      }
      expect(r.ends.start.cross).toBeDefined();
    }
    // a crossroads gives every row ending there all three ways on
    const kinds = new Map(g.graph.nodes.map((n) => [n.id, n.kind]));
    const atCross = g.graph.rows.filter((r) => kinds.get(r.to) === 'cross');
    expect(atCross.length).toBeGreaterThan(20);
    for (const r of atCross) expect(r.ends.end.straight && r.ends.end.turnIn && r.ends.end.cross, r.id).toBeTruthy();
  });

  it('reaches every row from the spawn on Main Street before the civic block', () => {
    const seen = new Set([g.graph.spawn.row]);
    const queue = [g.graph.spawn.row];
    while (queue.length) {
      const r = rows.get(queue.shift()!)!;
      for (const end of [r.ends.start, r.ends.end]) for (const t of [end.straight, end.turnIn, end.cross]) if (t && !seen.has(t)) { seen.add(t); queue.push(t); }
    }
    expect(seen.size).toBe(g.graph.rows.length);
    const spawn = rows.get(g.graph.spawn.row) as RowJson;
    // the planned town: on Main Street a block down from the civic block, facing it (the start view looks along Main
    // Street to the square): the next stretch ahead has its post office and clock tower, the cinema across from them
    expect(g.graph.roads[spawn.road]!.name).toBe('Main Street');
    expect(g.graph.spawn.x).toBeGreaterThan(89);
    expect(g.graph.spawn.x).toBeLessThan(spawn.length - 89);
    expect(spawn.buildings.filter((b) => b.kind === 'shop').length, 'a street wall at the start').toBeGreaterThanOrEqual(2);
    const dir = g.graph.spawn.dir ?? 1;
    const ahead = rows.get((dir < 0 ? spawn.ends.start.straight : spawn.ends.end.straight)!)!;
    expect(ahead.buildings.map((b) => b.lot)).toEqual(expect.arrayContaining(['landmark:post', 'landmark:clocktower']));
    const across = [ahead.ends.start.cross, ahead.ends.end.cross].map((id) => rows.get(id!)!);
    expect(across.flatMap((r) => r.buildings.map((b) => b.lot))).toContain('landmark:cinema');
    // the civic avenue still has its name (the v1 crossroads' junction is on it)
    expect(g.graph.roads.some((r) => r.name === 'Civic Avenue')).toBe(true);
  });

  it('keeps the v1 fields, for the spawn junction, and names buildings as v1 does', () => {
    const j = g.json as Record<string, unknown> & { shops: { sprite: string }[]; junction: { x: number } };
    expect(j.town).toBe(TOWN.seed);
    expect(j.version).toBe(2);
    // (the v1 crossroads' junction: the civic avenue's node on Main Street)
    expect(j.junction.x).toBeGreaterThan(0);
    expect(j.shops.map((s) => s.sprite)).toEqual(j.shops.map((_, i) => `b${i}`));
    const v1 = townStreets(TOWN.seed, TOWN.year);
    for (const [sprite, lot] of v1.lotOf) expect(g.buildings.lotOf.get(sprite)?.slot, sprite).toBe(lot.slot);
    // and the v1 export itself is still what the app ships
    expect(`${JSON.stringify(townStreets('eta', 2025).json, null, 2)}\n`).toBe(readFileSync(join(__dirname, '../app/src/main/assets/street.json'), 'utf8'));
  });

  it('builds no tower on a house lot, and only low walk-ups on apartment lots out in the residential streets', () => {
    const shops = (g.json as { shops: { sprite: string; spec?: Record<string, unknown>; parts: { key: string }[] }[] }).shops;
    let low = 0;
    for (const s of shops) {
      const lot = g.buildings.lotOf.get(s.sprite);
      if (lot?.tier === 'apartment' && !apartmentNearCore(g.layout.plan, lot.slot)) expect(s.parts[0]!.key, s.sprite).not.toMatch(/^condo_(3_45|5_\d|9)$/);
      if (!lot || lot.kind !== 'house' || !s.spec) continue;
      expect(lot.tier, s.sprite).toBe('apartment');
      if (!apartmentNearCore(g.layout.plan, lot.slot)) { expect(storeysOf(s.spec), s.sprite).toBeLessThanOrEqual(3); low++; }
    }
    expect(low).toBeGreaterThan(0);
  });

  // the fixture pins the Kotlin town tests (TownMapTest, TownWalkTest, TownPlaceTest, StreetPropRulesTest, OpenWalkTest)
  it('matches the Kotlin tests\' fixture', () => {
    expect(readFileSync(join(__dirname, '../desktop/src/test/resources/town-gamma.json'), 'utf8')).toBe(`${JSON.stringify(g.json)}\n`);
  });
});
