// The whole-town building list (tools/town-whole.ts) and the whole-town dialogue and interiors exports (--all): every
// building of the town under one sprite name each, the v1 streets' names unchanged, people found by sprite, and rows
// filled in from a street.json v2 when there is one.
import { describe, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { wholeTown, townLots, placesIn } from './town-whole';
import { townStreets } from './town-streets';
import { townGraph } from './town-graph';
import { isAlive } from '../../src/sim/types';

const ROOT = join(__dirname, '../..');
const run = (tool: string, ...args: string[]): string =>
  execFileSync('npx', ['tsx', `kotlin-street/tools/${tool}.ts`, ...args], { cwd: ROOT, encoding: 'utf8', maxBuffer: 1 << 28, stdio: ['ignore', 'pipe', 'ignore'] });

type NpcJ = { id: string; building: string; street: number; index: number; row: unknown; rowIndex: unknown; lot: string; neighbourhood: string; topics: unknown[] };

describe('whole town', () => {
  const t = wholeTown('gamma', 2025);

  it('names every building of the town once, keeping the v1 names', () => {
    expect(t.sprites).toHaveLength(townLots(t.layout).length);
    expect(t.sprites).toEqual(t.sprites.map((_, i) => `b${i}`));
    expect(new Set(t.lotOf.values()).size).toBe(t.sprites.length);
    const v1 = townStreets('gamma', 2025);
    for (const [s, lot] of v1.lotOf) expect(t.lotOf.get(s)!.slot, s).toBe(lot.slot);
    expect(t.buildings.entries).toHaveLength(t.sprites.length);
  });

  it('exports someone to talk to at every building with people, each carrying their sprite', () => {
    const d = JSON.parse(run('export-dialogue', 'gamma', '2025', '--all')) as { version: number; buildings: number; npcs: NpcJ[] };
    expect(d.version).toBe(2);
    expect(d.buildings).toBe(t.sprites.length);
    const at = new Set(d.npcs.map((n) => n.building));
    for (const s of t.sprites) {
      const l = t.lotOf.get(s)!;
      const living = l.kind === 'shop' ? [t.w.businesses[l.refId]?.owner].filter((id) => id && isAlive(t.w.people[id]!)) : l.residents.filter((id) => t.w.people[id] && isAlive(t.w.people[id]!));
      if (living.length) expect(at.has(s), s).toBe(true);
    }
    for (const n of d.npcs) {
      expect(t.lotOf.get(n.building)!.slot).toBe(n.lot);
      expect(n.neighbourhood).toBe(t.neighbourhoodOf(n.building).id);
      expect(n.row).toBeNull(); // the app's street.json is still v1: rows not known yet
      expect(n.topics.length).toBeGreaterThan(0);
    }
    // the people of the v1 streets are exactly those of the v1 export (same lines), at the same places
    const v1 = JSON.parse(run('export-dialogue', 'gamma', '2025')) as { npcs: NpcJ[] };
    const strip = (n: NpcJ): object => ({ ...n, row: undefined, rowIndex: undefined, lot: undefined, neighbourhood: undefined, neighbourhoodName: undefined });
    const onV1 = d.npcs.filter((n) => t.v1Place.has(n.building));
    // (a building on both v1 streets is listed once per street in v1, once in v2)
    const seen = new Set<string>();
    const v1Once = v1.npcs.filter((n) => { const k = `${n.building}:${n.id}`; if (seen.has(k)) return false; seen.add(k); return true; });
    expect(onV1.map(strip)).toEqual(v1Once.map(strip));
  }, 120_000);

  it('exports the rooms of every building, v1 rooms unchanged', () => {
    const all = JSON.parse(run('export-interiors', 'gamma', '2025', '--all')) as { version: number; rooms: Record<string, unknown> };
    const v1 = JSON.parse(run('export-interiors', 'gamma', '2025')) as { rooms: Record<string, unknown> };
    expect(all.version).toBe(2);
    expect(Object.keys(all.rooms).sort()).toEqual([...t.sprites].sort());
    for (const [s, room] of Object.entries(v1.rooms)) expect(all.rooms[s], s).toEqual(room);
  }, 120_000);

  it('reads rows from a street.json v2 (the hook the graph fills)', () => {
    const dir = mkdtempSync(join(tmpdir(), 'town-whole-'));
    const path = join(dir, 'street.json');
    const rows = [{ id: 'r0', buildings: [{ sprite: 'b3' }, { sprite: 'b7' }] }, { id: 5, buildings: [{ sprite: 'b1' }] }];
    writeFileSync(path, JSON.stringify({ version: 2, town: { seed: 'gamma', year: 2025, rows } }));
    const p = placesIn(path, 'gamma', 2025);
    expect(p.get('b7')).toEqual({ row: 'r0', index: 1 });
    expect(p.get('b1')).toEqual({ row: 5, index: 0 });
    expect(placesIn(path, 'eta', 2025).size).toBe(0);
    // the graph may sit beside v1's `town: seed` string instead
    writeFileSync(path, JSON.stringify({ version: 2, town: 'gamma', year: 2025, graph: { rows } }));
    expect(placesIn(path, 'gamma', 2025).get('b3')).toEqual({ row: 'r0', index: 0 });
    writeFileSync(path, JSON.stringify({ version: 2, town: { seed: 'gamma', year: 2025, rows } }));
    const d = JSON.parse(run('export-dialogue', 'gamma', '2025', '--all', '--street', path, '--sample', '1')) as { npcs: NpcJ[] };
    const b7 = d.npcs.filter((n) => n.building === 'b7');
    expect(b7.length).toBeGreaterThan(0);
    for (const n of b7) expect([n.row, n.rowIndex]).toEqual(['r0', 1]);
    // a graph that names a lot's building differently is refused (people would stand at the wrong doors)
    writeFileSync(path, JSON.stringify({ version: 2, town: { seed: 'gamma', year: 2025, rows: [{ id: 'r0', buildings: [{ sprite: 'b3', lot: t.lotOf.get('b4')!.slot }] }] } }));
    expect(() => run('export-dialogue', 'gamma', '2025', '--all', '--street', path)).toThrow();
    writeFileSync(path, JSON.stringify({ version: 2, town: { seed: 'gamma', year: 2025, rows: [{ id: 'r0', buildings: [{ sprite: 'b3', lot: t.lotOf.get('b3')!.slot }] }] } }));
    expect(JSON.parse(run('export-dialogue', 'gamma', '2025', '--all', '--street', path)).version).toBe(2);
  }, 120_000);

  it('names every lot as the graph does, and fills every NPC\'s row from the real street.json v2', () => {
    const g = townGraph('gamma', 2025);
    for (const s of t.sprites) expect(g.buildings.lotOf.get(s)?.slot, s).toBe(t.lotOf.get(s)!.slot);
    const dir = mkdtempSync(join(tmpdir(), 'town-whole-'));
    const path = join(dir, 'street.json');
    writeFileSync(path, JSON.stringify(g.json));
    const d = JSON.parse(run('export-dialogue', 'gamma', '2025', '--all', '--street', path)) as { npcs: NpcJ[] };
    const rowOf = new Map(g.graph.rows.flatMap((r) => r.buildings.map((b, i) => [b.sprite, [r.id, i]] as const)));
    expect(d.npcs.length).toBeGreaterThan(0);
    for (const n of d.npcs) expect([n.row, n.rowIndex], n.building).toEqual(rowOf.get(n.building));
  }, 120_000);
});
