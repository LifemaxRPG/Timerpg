import { expect, it } from 'vitest';
import { dayNumberOf } from '../calendar';
import { businessesForSale, openBusiness, runDay, valuation } from './index';

const place = { id: 'main', kind: 'five_and_dime', name: 'Main Street Five-and-Dime' };
const seed = 'town';
const offer = businessesForSale([place], 1952, seed)[0]!;
const shop = openBusiness(offer, 'lease', 50000, seed);
const day = (month: number, date: number) => dayNumberOf({ year: 1952, month, day: date })!;

it('lists the example shop at $2400 or $60 a month and quotes reproducible terms', () => {
  expect(offer).toMatchObject({ askingCents: 240000, leaseMonthlyCents: 6000 });
  expect(offer.stockCents + offer.fixturesCents + offer.goodwillCents).toBe(offer.askingCents);
  expect(businessesForSale([place], 1952, seed)).toEqual([offer]);
});

it('changes takings on weekdays and closes on fictional retail holidays', () => {
  const saturday = runDay(shop, day(9, 6), seed)!;
  const sunday = runDay(shop, day(9, 7), seed)!;
  expect(saturday.salesCents).toBeGreaterThan(sunday.salesCents);
  expect(runDay(shop, day(1, 5), seed)).toMatchObject({ customers: 0, salesCents: 0 });
  expect(runDay(shop, day(9, 6), seed, { streetActivity: 0 })?.salesCents).toBe(0);
  expect(runDay(shop, day(9, 6), seed, { closed: true })?.salesCents).toBe(0);
});

it('books theft and breakage, prevents replays, and makes overstaffing unprofitable', () => {
  const staffed = { ...shop, staff: Array.from({ length: 14 }, () => ({ job: 'shop_clerk' as const, hours: 8 })) };
  expect(runDay(staffed, day(9, 6), seed)!.profitCents).toBeLessThan(0);
  const normal = runDay(shop, day(9, 6), seed)!;
  const damaged = runDay(shop, day(9, 6), seed, {
    events: [{ kind: 'cash_theft', cents: 1500 }, { kind: 'breakage', cents: 2000 }],
  })!;
  expect(damaged.lossCents).toBe(3500);
  expect(damaged.profitCents).toBe(normal.profitCents - 3500);
  expect(runDay(damaged.business, day(9, 6), seed)).toBeNull();
});

it('warns before lockout and repossession if bills go unpaid', () => {
  let business = { ...shop, cashCents: 0, bankDailyCents: 100 };
  for (let i = 0; i < 14; i++) {
    const result = runDay(business, day(9, 1) + i, seed, { closed: true })!;
    expect(result.landlord).toBe(i < 6 ? 'warning' : 'lockout');
    expect(result.bank).toBe(i < 13 ? 'warning' : 'repossess');
    business = result.business;
  }
});

it('raises valuation with steady profit and round-trips a seeded day', () => {
  expect(valuation({ ...shop, recentProfitCents: [300, 400, 500] }, 1952)!)
    .toBeGreaterThan(valuation(shop, 1952)!);
  const date = day(9, 1);
  expect(runDay(shop, date, seed)).toEqual(runDay(JSON.parse(JSON.stringify(shop)), date, seed));
});
