import { describe, expect, it } from 'vitest';
import { BILLBOARDS_FROM, lampIndexes, pickIndex, treeIndexes } from './era';
import { shopFacade } from './facade';

describe('era', () => {
  it('lamps and trees only add options as the years pass (nothing disappears)', () => {
    for (let y = 1950; y < 2025; y++) {
      for (const f of [lampIndexes, treeIndexes]) for (const i of f(y)) expect(f(y + 1)).toContain(i);
    }
  });

  it('1950 has only old-fashioned lamps and young trees; 2025 has modern lamps and big trees', () => {
    expect(lampIndexes(1950)).not.toContain(2);
    expect(lampIndexes(1950)).not.toContain(3);
    expect(lampIndexes(2025)).toContain(3);
    expect(treeIndexes(1950)).not.toContain(7);
    expect(treeIndexes(2025)).toContain(8);
  });

  it('pickIndex is stable and clamps to what exists', () => {
    expect(pickIndex([0, 1, 4], 5, 0.5)).toBe(pickIndex([0, 1, 4], 5, 0.5));
    expect(pickIndex([0, 1, 7, 8], 3, 0.99)).toBe(1);
    expect(pickIndex([0, 1], 5, 0.999)).toBe(1);
  });

  it('rooftop billboards only appear from the mid-70s', () => {
    for (let i = 0; i < 200; i++) {
      const look = i / 200;
      const early = shopFacade('Clothing Store', look, BILLBOARDS_FROM - 1);
      expect(early.layers.some((l) => l.key.startsWith('roofprop_')), `look ${look}`).toBe(false);
    }
    let late = 0;
    for (let i = 0; i < 200; i++) if (shopFacade('Clothing Store', i / 200, 2025).layers.some((l) => l.key.startsWith('roofprop_'))) late++;
    expect(late).toBeGreaterThan(20);
  });

  it('shops are plain painted-sign storefronts before 1975 and have the fancy signs after', () => {
    for (const kind of ['Bakery', 'Butcher', 'Ice Cream Parlor', 'Music Store', 'Bait Shop', 'Clothing Store', 'Toy Store']) {
      for (let i = 0; i < 20; i++) {
        const early = shopFacade(kind, i / 20, 1952);
        const late = shopFacade(kind, i / 20, 2025);
        expect(early.layers[0]!.key.startsWith('classic_'), `${kind} 1952`).toBe(true);
        expect(early.layers[0]!.h).toBe(96);
        expect(late.layers[0]!.key.startsWith('shop_'), `${kind} 2025`).toBe(true);
        // Same building either way: same number of floors and same total height.
        expect(early.h).toBe(late.h);
      }
    }
  });
});
