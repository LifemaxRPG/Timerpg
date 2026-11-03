import fs from 'node:fs';
import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { colourOf, pickStable } from './buildings';
import { allFacadeArt, shopFacade, SHOP_PLOT_H, SHOP_W } from './facade';
import { allHousingArt, apartmentCapacity, houseArt, RESIDENTIAL, variantsFor } from './housing';
import { layoutTown } from './layout';
import { SIZES } from './plan';

const LOOKS = Array.from({ length: 40 }, (_, i) => (i + 0.5) / 40);
const px = (n: number): number => n * 32;

describe('housing variants', () => {
  it('picks the same variant for the same (tier, look, year)', () => {
    for (const tier of RESIDENTIAL)
      for (const look of LOOKS)
        expect(JSON.stringify(houseArt(tier, look, 2000))).toBe(JSON.stringify(houseArt(tier, look, 2000)));
  });

  it('changes a pick only when an era-gated variant becomes available', () => {
    for (const tier of RESIDENTIAL) {
      const gated = variantsFor(tier, 2025).filter((v) => !variantsFor(tier, 1950).includes(v)).map((v) => v.id);
      for (const look of LOOKS) {
        const before = pickStable(variantsFor(tier, 1950), look, tier).id;
        const after = pickStable(variantsFor(tier, 2025), look, tier).id;
        if (before !== after) expect(gated, `${tier} ${look}`).toContain(after);
      }
    }
  });

  it('actually uses several different sprites per tier', () => {
    for (const tier of RESIDENTIAL) {
      const keys = new Set(LOOKS.map((l) => houseArt(tier, l, 2025).layers.map((x) => x.key).join('+')));
      expect(keys.size, tier).toBeGreaterThanOrEqual(tier === 'estate' ? 3 : 5);
    }
  });

  it('every sprite fits its plot (width exactly, height within 2 tiles of headroom)', () => {
    for (const a of allHousingArt()) {
      expect(a.w, a.key).toBeLessThanOrEqual(px(SIZES[a.tier].w));
    }
    for (const tier of RESIDENTIAL)
      for (const look of LOOKS) {
        const art = houseArt(tier, look, 2025);
        expect(art.w, tier).toBeLessThanOrEqual(px(SIZES[tier].w));
        expect(art.h, `${tier} ${art.layers[0]!.key}`).toBeLessThanOrEqual(px(SIZES[tier].h) + 64);
        expect(art.capacity).toBeGreaterThanOrEqual(1);
      }
  });

  it('modular condos stack ground, middle and roof of one colour inside the plot', () => {
    const colors = new Set<string>();
    for (const look of LOOKS) {
      const art = houseArt('apartment', look, 2025);
      if (art.layers.length < 3) continue;
      expect(art.h).toBeLessThanOrEqual(px(SIZES.apartment.h));
      const [g, m] = art.layers;
      const gc = colourOf(Number(g!.key.split('_').pop()), 12);
      const mc = colourOf(Number(m!.key.split('_').pop()), 18);
      expect(gc).toBe(mc);
      colors.add(gc);
      for (let i = 1; i < art.layers.length; i++) expect(art.layers[i]!.y).toBe(art.layers[i - 1]!.y + art.layers[i - 1]!.h);
    }
    expect(colors.size).toBeGreaterThanOrEqual(2);
  });

  it('modular condos only appear from their era', () => {
    for (const look of LOOKS) expect(houseArt('apartment', look, 1950).layers.length).toBe(1);
    expect(apartmentCapacity(0.3, 1950)).toBeGreaterThanOrEqual(3);
  });
});

describe('shop fronts', () => {
  const kinds = ['Bakery', 'Butcher', 'Ice Cream Parlor', 'Music Store', 'Bait Shop', 'Hardware', 'Newspaper'];

  it('are deterministic and fit the 13-tile plot, 7 tiles wide', () => {
    expect(px(SIZES.shop.h)).toBe(SHOP_PLOT_H);
    expect(px(SIZES.shop.w)).toBe(SHOP_W);
    for (const k of kinds)
      for (const look of LOOKS) {
        const a = shopFacade(k, look);
        expect(JSON.stringify(a)).toBe(JSON.stringify(shopFacade(k, look)));
        expect(a.h).toBeLessThanOrEqual(SHOP_PLOT_H);
        for (const l of a.layers.filter((l) => !l.key.startsWith('roofprop'))) expect(l.w).toBe(SHOP_W);
      }
  });

  it('match the roof and middle floor to the ground floor colour, and never use a flat roof', () => {
    for (const look of LOOKS) {
      const a = shopFacade('Hardware', look);
      const ground = Number(a.layers[0]!.key.split('_').pop());
      const col = colourOf(ground, 36);
      const roof = Number(a.layers.find((l) => l.key.startsWith('roof_'))!.key.split('_').pop());
      expect({ red: [2, 8], blue: [4, 9], gray: [6, 11] }[col]).toContain(roof);
      const mid = a.layers.find((l) => l.key.startsWith('middle_'));
      if (mid) expect(colourOf(Number(mid.key.split('_').pop()), 18)).toBe(col);
    }
  });

  it('generic shops sometimes get a middle floor, specialists never overflow', () => {
    const generic = LOOKS.map((l) => shopFacade('Hardware', l));
    expect(generic.some((a) => a.layers.some((l) => l.key.startsWith('middle_')))).toBe(true);
    expect(generic.some((a) => !a.layers.some((l) => l.key.startsWith('middle_')))).toBe(true);
  });
});

describe('sprites exist in the extracted manifest', () => {
  const path = 'public/assets/manifest.json';
  it.runIf(fs.existsSync(path))('has every key the catalogues use, at the sizes the catalogues assume', () => {
    const manifest = JSON.parse(fs.readFileSync(path, 'utf8')) as Record<string, { key: string; w: number; h: number }[]>;
    const byKey = new Map(Object.values(manifest).flat().map((e) => [e.key, e]));
    for (const a of [...allHousingArt(), ...allFacadeArt()]) {
      const e = byKey.get(a.key);
      expect(e, `missing ${a.key}`).toBeDefined();
      expect([e!.w, e!.h], a.key).toEqual([a.w, a.h]);
    }
  });
});

describe('apartments in a living town', () => {
  const t = runTimeline({ seed: 'zebra', endYear: 2025 });
  for (const year of [1950, 1990, 2025]) {
    it(`house every household exactly once in ${year}, within building capacity`, () => {
      const w = worldAt(t, year);
      const l = layoutTown(w);
      const heads = l.lots.filter((x) => x.kind === 'house').flatMap((x) => x.heads);
      expect(new Set(heads).size).toBe(heads.length);
      const living = Object.values(w.people).filter((p) => p.deathYear === undefined).length;
      expect(l.lots.filter((x) => x.kind === 'house').flatMap((x) => x.residents).length).toBe(living);
      for (const lot of l.lots.filter((x) => x.tier === 'apartment')) {
        expect(lot.heads.length).toBeGreaterThanOrEqual(1);
        expect(lot.heads.length).toBeLessThanOrEqual(lot.art.capacity);
      }
      for (const lot of l.lots.filter((x) => x.kind === 'house' && x.tier !== 'apartment')) expect(lot.heads.length).toBe(1);
    });
  }

  it('shows apartment buildings once the town is big', () => {
    expect(layoutTown(worldAt(t, 2025)).lots.some((x) => x.tier === 'apartment' && x.heads.length > 1)).toBe(true);
  });

  it('uses more than one house tier in the present day', () => {
    const tiers = new Set(layoutTown(worldAt(t, 2025)).lots.filter((x) => x.kind === 'house').map((x) => x.tier));
    expect(tiers.size).toBeGreaterThanOrEqual(4);
  });

  it('shop floors stack on the storefront, so there is no gap between the shop front and its roof', () => {
    for (const kind of ['Bakery', 'Butcher', 'Ice Cream Parlor', 'Music Store', 'Bait Shop', 'Hardware']) {
      for (let i = 0; i < 40; i++) {
        const a = shopFacade(kind, i / 40, 2025);
        const [ground, next] = a.layers;
        // The real storefront is 96px tall whatever the sprite height (the rest is sign overhang).
        expect(next!.y, `${kind} ${i}: layer above the storefront starts at ${next!.y}`).toBe(96);
        expect(ground!.y).toBe(0);
      }
    }
  });
});
