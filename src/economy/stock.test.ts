import { expect, it } from 'vitest';
import { openStock, priceIn, priceIndex, quantityOf, restock, stockOf, take, wage } from './index';

it('makes calculators cheaper in nominal cents while services outpace the index', () => {
  const calculator = priceIn('pocket_calculator', 1978)!;
  expect(calculator / (40 * wage('odd_jobs', 1978)!.hourlyCents)).toBeCloseTo(1, 1);
  expect(priceIn('pocket_calculator', 1971)!).toBeGreaterThan(calculator);
  expect(priceIn('pocket_calculator', 2005)!).toBeLessThan(1000);
  expect(priceIn('meatloaf_plate', 2025)! / priceIn('meatloaf_plate', 1925)!)
    .toBeGreaterThan(priceIndex(2025)! / priceIndex(1925)!);
});

it('sells out the lone radio and delivers new stock at a changed price after seven days', () => {
  const seed = 'main-street-5';
  const opened = openStock('general store', 1952, seed);
  expect(opened.entries).toEqual(stockOf('general', 1952, seed));
  expect(quantityOf(opened, 'radio')).toBe(1);
  const sold = take(opened, 'radio', 1);
  expect(quantityOf(sold, 'radio')).toBe(0);
  expect(quantityOf(opened, 'radio')).toBe(1);
  expect(take(sold, 'radio', 1)).toBe(sold);
  expect(take(opened, 'radio', 2)).toBe(opened);
  expect(take(opened, 'radio', -1)).toBe(opened);
  expect(take(opened, 'radio', .5)).toBe(opened);
  const tomorrow = restock(sold, 1, 1952, seed);
  expect(quantityOf(tomorrow, 'radio')).toBe(0);
  const refill = restock(tomorrow, 6, 1952, seed);
  expect(quantityOf(refill, 'radio')).toBeGreaterThan(0);
  expect(refill.entries.find(row => row.itemId === 'radio')?.priceCents).toBe(3048);
});

it('restocks the grocer daily and the hardware shop weekly', () => {
  const seed = 'town';
  for (const [shop, item, delay] of [['grocer', 'milk', 1], ['hardware', 'hammer', 7]] as const) {
    const initial = openStock(shop, 1952, seed);
    const empty = take(initial, item, quantityOf(initial, item));
    expect(quantityOf(restock(empty, delay - 1, 1952, seed), item)).toBe(0);
    expect(quantityOf(restock(empty, delay, 1952, seed), item)).toBeGreaterThan(0);
  }
});

it('round-trips through JSON and makes split day advances deterministic', () => {
  const seed = 'town';
  const initial = openStock('general', 1952, seed);
  const restored = JSON.parse(JSON.stringify(initial));
  const result = restock(initial, 16, 1952, seed);
  expect(restock(restock(restored, 7, 1952, seed), 9, 1952, seed)).toEqual(result);
  expect(restock(initial, 16, 1952, 'other-town')).not.toEqual(result);
  expect(restock(initial, -1, 1952, seed)).toBe(initial);
  expect(quantityOf(restock(initial, 7, 1978, seed), 'radio')).toBe(0);
  expect(quantityOf(restock(initial, 7, 1978, seed), 'pocket_calculator')).toBeGreaterThan(0);
});
