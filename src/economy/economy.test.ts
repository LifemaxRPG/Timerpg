import { describe, expect, it } from 'vitest';
import { ITEMS } from './catalogue';
import {
  FIRST_YEAR, LAST_YEAR, SHOP_KINDS, fenceOffer, formatMoney, pawnOffer, pawnPrice, priceIn, priceIndex, stockOf, wage,
} from './index';

describe('the era economy', () => {
  it('has unique catalogue IDs, valid availability and serial years, and original price metadata', () => {
    expect(ITEMS.length).toBeGreaterThanOrEqual(200);
    expect(new Set(ITEMS.map(item => item.id)).size).toBe(ITEMS.length);
    for (const item of ITEMS) {
      expect(item.from >= FIRST_YEAR && item.from <= item.to && item.to <= LAST_YEAR).toBe(true);
      expect(item.serialFrom === null || item.serialFrom >= item.from && item.serialFrom <= item.to).toBe(true);
      expect(Number.isSafeInteger(item.cents2025) && item.cents2025 > 0).toBe(true);
      expect(item.pawnShare >= 0 && item.pawnShare < .65).toBe(true);
      expect(item.shops.length).toBeGreaterThan(0);
    }
  });

  it('only sells items within their inclusive years in every shop, including pawn', () => {
    const byId = new Map(ITEMS.map(item => [item.id, item]));
    for (let year = FIRST_YEAR; year <= LAST_YEAR; year++) {
      for (const shop of SHOP_KINDS) {
        const invalid = stockOf(shop, year, 'capital').filter(entry => {
          const item = byId.get(entry.itemId)!;
          return year < item.from || year > item.to || entry.quantity < 1
            || entry.serial !== (item.serialFrom !== null && year >= item.serialFrom);
        });
        expect(invalid).toEqual([]);
      }
    }
    for (const [id, launch] of [['radio', 1922], ['television', 1946], ['pocket_calculator', 1971]] as const) {
      expect(priceIn(id, launch - 1)).toBeNull();
      expect(priceIn(id, launch)).toBeGreaterThan(0);
    }
    expect(priceIn('radio', 1970)).toBeGreaterThan(0);
    expect(priceIn('radio', 1971)).toBeNull();
    expect(stockOf('general', 1958, 'capital').find(row => row.itemId === 'headphones')?.serial).toBe(false);
    expect(stockOf('general', 1985, 'capital').find(row => row.itemId === 'headphones')?.serial).toBe(true);
  });

  it('reproduces the broad inflation and deflation periods with interpolated annual values', () => {
    expect(priceIndex(2025)).toBe(1);
    expect(priceIndex(1951)).toBeCloseTo(.0835);
    for (const [from, to] of [[1914, 1920], [1939, 1948], [1950, 2030]]) {
      for (let year = from; year < to; year++) {
        expect(priceIndex(year + 1)!).toBeGreaterThanOrEqual(priceIndex(year)!);
      }
    }
    for (let year = 1929; year < 1933; year++) {
      expect(priceIndex(year + 1)!).toBeLessThan(priceIndex(year)!);
    }
    expect(priceIndex(1982)! / priceIndex(1973)!).toBeGreaterThan(2);
  });

  it.each([
    [1925, [4, 7], [15, 25], [25, 35]],
    [1952, [5, 10], [20, 29], [65, 90]],
    [1978, [12, 21], [45, 70], [250, 320]],
    [2025, [65, 130], [240, 330], [1400, 1800]],
  ] as const)('keeps coffee, peaches and odd-job pay within range in %i', (year, coffee, peaches, oddJobs) => {
    for (const [value, [minimum, maximum]] of [
      [priceIn('coffee', year), coffee],
      [priceIn('peaches', year), peaches],
      [wage('odd_jobs', year)?.hourlyCents, oddJobs],
    ] as const) {
      expect(value).toBeGreaterThanOrEqual(minimum);
      expect(value).toBeLessThanOrEqual(maximum);
    }
  });

  it('delivers the 1952 first-encounter prices and sweeping wage', () => {
    const stock = stockOf('general store', 1952, 'capital');
    expect(stock.find(entry => entry.itemId === 'peaches')?.priceCents).toBe(23);
    expect(stock.find(entry => entry.itemId === 'radio')).toMatchObject({ priceCents: 2995, serial: true });
    expect(pawnOffer('radio', 1952, false)).toBe(600);
    expect(pawnOffer('radio', 1952, true)).toBe(300);
    expect(wage('odd jobs', 1952)).toEqual({ hourlyCents: 75, shiftHours: 2, shiftCents: 150 });
    expect(wage('mechanic', 1952)!.hourlyCents).toBeGreaterThan(wage('shop_clerk', 1952)!.hourlyCents);
  });

  it('keys stock to the seed, year, shop and item, with plain JSON rows', () => {
    const stock = stockOf('general', 1952, 'capital/a');
    const copy = JSON.parse(JSON.stringify(stock));
    stockOf('pawn', 1978, 'elsewhere');
    expect(stockOf('general', 1952, 'capital/a')).toEqual(copy);
    expect(stockOf('general', 1952, 'capital/b')).not.toEqual(copy);
    expect(stockOf('grocer', 1952, 'capital')).toEqual(stockOf('market', 1952, 'capital'));
    expect(stockOf('pawn', 1952, 'capital')).toEqual(expect.arrayContaining([
      expect.objectContaining({ itemId: 'ring', priceCents: pawnPrice('ring', 1952) }),
    ]));
  });

  it('pays less than retail or used ask, discounts hot items, and rejects perishables', () => {
    for (const item of ITEMS) {
      const year = item.from;
      const retail = priceIn(item.id, year)!;
      const clean = pawnOffer(item.id, year, false)!;
      const hot = pawnOffer(item.id, year, true)!;
      const fence = fenceOffer(item.id, year)!;
      expect(clean).toBeLessThan(retail);
      if (clean > 0) {
        expect(pawnPrice(item.id, year)!).toBeGreaterThan(clean);
        expect(hot).toBeLessThan(clean);
        expect(fence).toBeLessThan(clean);
        expect(fence).toBeLessThanOrEqual(hot);
      } else {
        expect(hot).toBe(0);
        expect(fence).toBe(0);
      }
    }
    expect(pawnOffer('milk', 1952, false)).toBe(0);
    expect(pawnPrice('milk', 1952)).toBeNull();
    expect(stockOf('pawn', 1952, 'capital').some(entry => entry.itemId === 'milk')).toBe(false);
  });

  it('formats money for the era and returns JSON-safe results for unsupported inputs', () => {
    expect(formatMoney(5, 1925)).toBe('5¢');
    expect(formatMoney(23, 1952)).toBe('23¢');
    expect(formatMoney(2995, 1952)).toBe('$29.95');
    expect(formatMoney(23, 2025)).toBe('$0.23');
    expect(formatMoney(-23, 1952)).toBe('-23¢');
    expect(formatMoney(0, 2025)).toBe('$0.00');
    expect(formatMoney(1.5, 2025)).toBeNull();
    expect(priceIndex(1879)).toBeNull();
    expect(priceIndex(2031)).toBeNull();
    expect(priceIn('missing', 1952)).toBeNull();
    expect(wage('missing', 1952)).toBeNull();
    expect(stockOf('missing', 1952, 'capital')).toEqual([]);
    expect(stockOf('general', 1952.5, 'capital')).toEqual([]);
  });
});
