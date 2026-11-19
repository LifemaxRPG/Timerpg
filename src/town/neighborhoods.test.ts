import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { houseArt } from './housing';
import { inspectAt } from './inspect';
import { layoutTown } from './layout';
import { neighborhoodOf, paletteColours, zoneNeighborhoods, type Palette } from './neighborhoods';
import { planTown } from './plan';
import { hitsWater } from './water';

const seeds = ['alpha', 'demo', 'zebra', 'quartz', 'maple', 'orbit', 'delta', 'nova'];

describe('seeded neighborhoods', () => {
  it('assigns every plot the neighbourhood of its district (the planned town: src/town/plat.ts)', () => {
    const HOOD: Record<string, string> = { core: 'old-town', square: 'old-town', civic: 'old-town', old: 'old-town', westmain: 'old-town', worker: 'the-flats',
      strip: 'gardens', postwar: 'gardens', heights: 'heights', late: 'woodside', edge: 'woodside', river: 'waterfront' };
    for (const seed of seeds) {
      const plan = planTown(seed);
      for (const c of plan.candidates) expect(c.neighborhood, `${seed} ${c.id}`).toBe(HOOD[c.district!]);
      expect(new Set(plan.candidates.map((c) => c.neighborhood)).size).toBeGreaterThanOrEqual(3);
      for (const c of plan.candidates) expect(plan.neighborhoods.some((n) => n.id === c.neighborhood)).toBe(true);
      // (the neighbourhoods' names and styles are zoneNeighborhoods' for the seed)
      const zoned = zoneNeighborhoods(seed, plan.candidates, plan.center, plan.water).neighborhoods;
      for (const n of plan.neighborhoods) { const z = zoned.find((q) => q.id === n.id); if (z) expect({ ...n, center: z.center }).toEqual(z); }
    }
  });

  it('keeps waterfront districts by actual water, and villa plots in the heights', () => {
    for (const seed of seeds) {
      const p = planTown(seed);
      for (const c of p.candidates) {
        if (c.neighborhood === 'waterfront') {
          expect(p.water).not.toBeNull();
          expect(hitsWater(p.water, c, 16)).toBe(true);
        }
        if ((c.kind === 'villa' || c.kind === 'estate') && c.district === 'heights') expect(c.neighborhood).toBe('heights');
        if (c.kind === 'shop' && c.district === 'core') expect(c.neighborhood).toBe('old-town');
      }
      if (!p.water) expect(p.neighborhoods.some((n) => n.id === 'waterfront')).toBe(false);
    }
  });

  it('uses coherent palettes with multiple real sprite variants inside each palette', () => {
    for (const palette of Object.keys(paletteColours) as Palette[]) {
      for (const tier of ['small', 'medium', 'house'] as const) {
        const variants = new Set<string>();
        for (let i = 0; i < 50; i++) {
          const art = houseArt(tier, (i + 0.5) / 50, 2000, palette);
          const key = art.layers[0]!.key;
          expect(paletteColours[palette]).toContain(Number(key.match(/_c(\d+)$/)?.[1] ?? 0));
          variants.add(key);
        }
        expect(variants.size).toBeGreaterThanOrEqual(3);
      }
    }
    expect(new Set(seeds.map((s) => planTown(s).neighborhoods[0]!.palette)).size).toBeGreaterThanOrEqual(2);
  });

  it('styles initial and newly built properties from their location and preserves that style', () => {
    const t = runTimeline({ seed: 'alpha', endYear: 2025 });
    const plan = planTown('alpha');
    for (const w of t.snapshots) {
      for (const lot of w.settlement!.properties) {
        const c = plan.candidates.find((c) => c.id === lot.slot)!;
        const n = neighborhoodOf(plan, c.neighborhood);
        expect(lot.neighborhood).toBe(c.neighborhood);
        if (lot.kind === 'house') {
          expect(lot.art).toEqual(houseArt(lot.tier as Exclude<typeof lot.tier, 'shop'>, lot.look, lot.builtYear!, n.palette));
        } else expect(lot.art.colour).toBe(n.shopColour);
      }
    }
    const baseline = JSON.stringify(plan);
    runTimeline({ seed: 'alpha', endYear: 2025, interventions: [{ kind: 'gift', year: 1951, targetId: 'gen:0:h', amount: 5000 }] });
    expect(JSON.stringify(planTown('alpha'))).toBe(baseline);
  });

  it('shows neighborhood names when inspecting homes and businesses', () => {
    const w = worldAt(runTimeline({ seed: 'alpha', endYear: 2025 }), 2025);
    const l = layoutTown(w);
    for (const lot of [...l.lots, ...l.vacantLots]) {
      const info = inspectAt(l, w, lot.x + 1, lot.y + 1)!;
      expect(info.lines).toContain(neighborhoodOf(l.plan, lot.neighborhood).name);
    }
  });
});
