import { readFileSync } from 'fs';
import { join } from 'path';
import { describe, expect, it } from 'vitest';
import { townGraph } from './town-graph';

// The hand-made landmark models in the shipped town (tools/town-glb.ts): gamma 2025's street.json carries them in their
// rows' slots, clear of every lot, yard, lane, prop and farmed field; its rail.json has the model depot on its track; the
// export of other years places what stands then without conflicts.

const asset = (p: string): string => readFileSync(join(__dirname, '../app/src/main/assets/town/gamma', p), 'utf8');
type R4 = [number, number, number, number];
const hit = (a: R4, b: R4): boolean => a[0] < b[2] - 0.01 && b[0] < a[2] - 0.01 && a[1] < b[3] - 0.01 && b[1] < a[3] - 0.01;

describe('the hand-made landmark models in gamma', () => {
  const g = JSON.parse(asset('street.json')).graph;

  it('ships what gamma 2025 earns, each in its row slot', () => {
    const kinds = (g.glbLandmarks as { kind: string }[]).map((x) => x.kind).sort();
    expect(kinds).toEqual(expect.arrayContaining(['landmark_glb_gazette_office', 'landmark_glb_water_tower', 'landmark_glb_modern_gas_station']));
    for (const x of g.glbLandmarks) {
      const row = g.rows.find((r: { id: string }) => r.id === x.row);
      expect(row.buildings.some((b: { sprite: string; kind: string }) => b.sprite === x.sprite && b.kind === 'landmark'), x.sprite).toBe(true);
      expect(g.landmarks.some((l: { sprite: string; art: string }) => l.sprite === x.sprite && l.art === x.kind)).toBe(true);
    }
  });

  it('keeps their plots clear of lots, yards, lanes, props and farmed fields', () => {
    for (const x of g.glbLandmarks as { rect: R4; sprite: string }[]) {
      for (const l of g.lots) expect(hit(x.rect, l.rect), `${x.sprite} on lot ${l.id}`).toBe(false);
      for (const y of g.yards ?? []) expect(hit(x.rect, y.parcel), `${x.sprite} on a yard`).toBe(false);
      for (const l of g.lanes ?? []) expect(hit(x.rect, l.rect), `${x.sprite} on a lane`).toBe(false);
      for (const p of g.props) expect(p.x > x.rect[0] && p.x < x.rect[2] && p.z > x.rect[1] && p.z < x.rect[3], `${x.sprite}: a ${p.kind} inside`).toBe(false);
      for (const f of g.farms.fields) if (f.sold === undefined || f.sold > g.year) expect(hit(x.rect, f.rect), `${x.sprite} in field ${f.id}`).toBe(false);
    }
  });

  it('has the model depot on its track', () => {
    const d = JSON.parse(asset('rail.json')).rail.depot;
    expect(d.premier).toBeDefined();
    const [x0, z0, x1, z1] = d.premier as R4;
    // its plot beside the track: 3 tiles (133.5 units) from the track's centre on the depot's side, 26 tiles along it
    const nx = d.dir[1] * d.side, nz = -d.dir[0] * d.side;
    const px = d.at[0] + nx * 133.5, pz = d.at[1] + nz * 133.5;
    expect(px >= x0 - 30 && px <= x1 + 30 && pz >= z0 - 30 && pz <= z1 + 30).toBe(true);
    expect(Math.max(x1 - x0, z1 - z0)).toBeCloseTo(26 * 44.5, 0);
  });

  it('exports other years without conflicts', () => {
    for (const y of [1955, 1985]) {
      const t = townGraph('gamma', y);
      const sprites = new Set(((t.json.graph as { glbLandmarks?: { sprite: string }[] }).glbLandmarks ?? []).map((x) => x.sprite));
      expect(sprites.size, `${y}`).toBeGreaterThan(0);
      expect(t.conflicts.filter((c) => [...sprites].some((s) => c.includes(`${s} `))), `${y}`).toEqual([]);
    }
  }, 120000);
});
