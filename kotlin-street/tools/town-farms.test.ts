// The farms in the whole-town export (tools/town-farms.ts, graph.farms) for the town of tools/town-config.ts: deterministic;
// the shipped street.json carries them; every field farmed in the export year clear of the town's streets, buildings, lots,
// yards and landmarks, of the farmsteads and of each other; no forest prop left standing in a field, a yard or on a country
// road; the windbreaks and the river past the map (with its bridges) in the graph; the fields' lives (built over, by year).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { townGraph } from './town-graph';
import { withYards } from './town-yards';
import { withFarms, type FarmsJson } from './town-farms';
import { TOWN } from './town-config';

type R4 = [number, number, number, number];
const g = townGraph(TOWN.seed, TOWN.year);
const json = withFarms(g, withYards(g));
const graph = json.graph as typeof g.graph & { farms: FarmsJson; yards: { parcel: R4 }[] };
const farms = graph.farms;
const hit = (a: R4, b: R4, pad = 0): boolean => a[0] < b[2] + pad && b[0] < a[2] + pad && a[1] < b[3] + pad && b[1] < a[3] + pad;
const farmed = farms.fields.filter((f) => f.from <= TOWN.year && (f.sold === undefined || f.sold > TOWN.year));

describe('farms in the export', () => {
  it('is deterministic and shipped', () => {
    expect(JSON.stringify(withFarms(g, withYards(g)).graph)).toBe(JSON.stringify(json.graph));
    const shipped = JSON.parse(readFileSync(new URL(`../app/src/main/assets/town/${TOWN.seed}/street.json`, import.meta.url), 'utf8'));
    expect(JSON.stringify(shipped.graph.farms)).toBe(JSON.stringify(farms));
    expect(JSON.stringify(shipped.graph.props)).toBe(JSON.stringify(graph.props));
  });

  it('has farms, fields and roads, each farm with its things', () => {
    expect(farms.farms.length).toBeGreaterThanOrEqual(12);
    expect(farmed.length).toBeGreaterThanOrEqual(farms.farms.length);
    expect(farms.roads.length).toBeGreaterThanOrEqual(6);
    for (const f of farms.farms) {
      expect(f.things.filter((t) => t.door).length).toBe(2);
      expect(f.household.rural).toBe(true);
    }
  });

  it('keeps every farmed field clear of the town, the farmsteads and each other', () => {
    for (const f of farmed) {
      for (const l of g.graph.lots) expect(hit(f.rect, l.rect), `${f.id} on lot ${l.id}`).toBe(false);
      for (const fp of g.footprints) expect(hit(f.rect, fp.rect), `${f.id} on ${fp.sprite}`).toBe(false);
      for (const y of graph.yards) expect(hit(f.rect, y.parcel), `${f.id} on a yard`).toBe(false);
      for (const lm of g.graph.landmarks) expect(hit(f.rect, lm.rect), `${f.id} on ${lm.id}`).toBe(false);
      for (const r of g.graph.roads) for (let i = 1; i < r.pts.length; i++) {
        const a = r.pts[i - 1]!, b = r.pts[i]!;
        const box: R4 = [Math.min(a[0], b[0]) - 89, Math.min(a[1], b[1]) - 89, Math.max(a[0], b[0]) + 89, Math.max(a[1], b[1]) + 89];
        expect(hit(f.rect, box), `${f.id} on road ${r.id}`).toBe(false);
      }
      for (const y of farms.farms) expect(hit(f.rect, y.rect), `${f.id} on ${y.id}`).toBe(false);
      for (const o of farmed) if (o !== f) expect(hit(f.rect, o.rect)).toBe(false);
      for (const p of graph.water?.polys ?? []) expect(hit(f.rect, [p[0]![0], p[0]![1], p[2]![0], p[2]![1]]), `${f.id} in the river`).toBe(false);
    }
  });

  it('leaves no forest standing in a field, a farmyard or on a country road; the windbreaks stand', () => {
    for (const p of graph.props) {
      for (const f of farmed) expect(p.x > f.rect[0] && p.x < f.rect[2] && p.z > f.rect[1] && p.z < f.rect[3], `${p.kind} at ${p.x},${p.z} in ${f.id}`).toBe(false);
      for (const f of farms.farms) expect(p.x > f.rect[0] + 60 && p.x < f.rect[2] - 60 && p.z > f.rect[1] + 60 && p.z < f.rect[3] - 60 && p.kind !== 'tree', `${p.kind} in ${f.id}`).toBe(false);
      for (const r of farms.roads) {
        const [a, b] = [r.pts[0]!, r.pts[r.pts.length - 1]!];
        const box: R4 = [Math.min(a[0], b[0]) - r.width / 2, Math.min(a[1], b[1]) - r.width / 2, Math.max(a[0], b[0]) + r.width / 2, Math.max(a[1], b[1]) + r.width / 2];
        expect(p.x > box[0] && p.x < box[2] && p.z > box[1] && p.z < box[3], `${p.kind} at ${p.x},${p.z} on ${r.id}`).toBe(false);
      }
    }
    // the farthest props are the farms' windbreaks, out past the map: the world's woods stand past them
    const xs = graph.props.map((p) => p.x);
    expect(Math.min(...xs)).toBeLessThan(Math.min(...g.graph.props.map((p) => p.x)));
    expect(graph.bridges.length).toBeGreaterThan(g.graph.bridges.length);
    expect(graph.water!.polys.length).toBeGreaterThan(g.graph.water!.polys.length);
  });

  it('records each field\'s life: farmed from 1950, some built over by the export year', () => {
    const sold = farms.fields.filter((f) => f.sold !== undefined && f.sold <= TOWN.year);
    expect(sold.length).toBeGreaterThan(0);
    expect(sold.some((f) => f.becomes === 'houses')).toBe(true);
    for (const f of farms.fields) expect(f.from).toBe(1950);
  });
});
