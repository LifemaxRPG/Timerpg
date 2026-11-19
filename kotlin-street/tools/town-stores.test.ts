// The stores in street.json v2 (tools/town-stores.ts): gamma through the decades gets the general store, then the market
// downtown, the supermarket at the edge of town, and the superstore across the road from it; each is a landmark on a
// row, its footprint the whole plot, its layout inside the plot, and the graph clean (no conflicts) in every year.
import { describe, expect, it } from 'vitest';
import { townGraph } from './town-graph';

const years = [1955, 1975, 1995, 2025] as const;
const graphs = new Map(years.map((y) => [y, townGraph('gamma', y)]));

describe('town stores (street.json v2)', () => {
  it('exports the year\'s stores of gamma', () => {
    const at = (y: number) => (graphs.get(y as typeof years[number])!.graph.stores ?? []).map((s) => `${s.site}:${s.kind}:${s.state}`);
    expect(at(1955)).toEqual(['generalstore:general:open']);
    expect(at(1975)).toEqual(['generalstore:general:open', 'market:market:open']);
    expect(at(1995)).toEqual(['generalstore:general:open', 'market:market:reused', 'supermarket:supermarket:open']);
    expect(at(2025)).toEqual(['generalstore:general:reused', 'market:market:reused', 'supermarket:supermarket:closed', 'superstore:superstore:open']);
  });

  it('puts each store on a row as a landmark, its footprint the plot, its layout inside it, with no conflicts', () => {
    for (const [y, g] of graphs) {
      expect(g.conflicts, String(y)).toEqual([]);
      for (const s of g.graph.stores ?? []) {
        const lm = g.graph.landmarks.find((l) => l.id === s.landmark)!;
        expect(lm.store).toEqual({ site: s.site, kind: s.kind, state: s.state });
        expect(lm.art).toBe(`landmark_store_${s.kind}`);
        const b = g.graph.rows.find((r) => r.id === lm.row)!.buildings.find((x) => x.sprite === lm.sprite)!;
        expect(b.x1 - b.x0).toBeCloseTo(s.footprint.width, 1);
        expect(b.depth).toBeLessThanOrEqual(s.footprint.depth);
        expect(b.depth).toBeGreaterThan(s.footprint.depth - 1);
        const L = s.layout as Record<string, unknown> & { width: number; depth: number; building: number[] };
        expect(L.width).toBeLessThanOrEqual(s.footprint.width + 1);
        expect(L.depth).toBeLessThanOrEqual(s.footprint.depth + 1);
        expect(L.building[2]!).toBeLessThanOrEqual(L.width);
        expect(L.building[3]!).toBeLessThanOrEqual(L.depth);
        if (s.state === 'reused') expect(s.reuse?.name).toBe(s.sign);
        expect(s.history[0]!.year).toBe(s.opened);
      }
    }
    // the superstore is big-box size: ~160 x 205 m of plot (21.2 units a metre)
    const ss = graphs.get(2025)!.graph.stores!.find((s) => s.site === 'superstore')!;
    expect(ss.footprint.width / (36 / 1.7)).toBeGreaterThan(150);
    expect(ss.footprint.depth / (36 / 1.7)).toBeGreaterThan(195);
  });
}, 120_000);
