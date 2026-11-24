import { describe, expect, it } from 'vitest';
import { simCall } from './bridge';

const call = (method: string, ...args: unknown[]) => JSON.parse(simCall(JSON.stringify({ method, args })));

describe('business and places through simCall', () => {
  it('reaches places: the era menu, Prohibition, lodging, hours', () => {
    const menu = call('places.menuOf', 'diner', 2025, 'town|diner');
    expect(menu.length).toBeGreaterThan(3);
    expect(menu.every((m: { priceCents: number; meal: string }) => m.priceCents > 0 && ['snack', 'diner', 'grocery_cooked'].includes(m.meal))).toBe(true);
    expect(call('places.menuOf', 'bar', 1925, 'town')).toEqual([]);
    expect(call('places.menuOf', 'speakeasy', 1925, 'town').length).toBeGreaterThan(0);
    expect(call('places.menuOf', 'speakeasy', 2025, 'town')).toEqual([]);
    expect(call('places.lodgingOffers', 'station_hotel', 1952, 'town')[0].nightlyCents).toBe(250);
    expect(call('places.placeHours', 'speakeasy', 1925, 2, 'town')).toEqual([{ open: 17, close: 24 }]);
  });

  it('reaches business: an offer, opening, a day, a valuation', () => {
    const offers = call('business.businessesForSale', [
      { id: 'a', kind: 'general', name: 'A' }, { id: 'b', kind: 'diner', name: 'B' }, { id: 'c', kind: 'market', name: 'C' }, { id: 'd', kind: 'hardware', name: 'D' },
    ], 2025, 'town');
    expect(offers.length).toBeGreaterThan(0);
    const biz = call('business.openBusiness', offers[0], 'buy', 5000, 'town');
    const start = call('calendar.dayNumberOf', { year: 2025, month: 5, day: 6 });
    const r = call('business.runDay', biz, start, 'town', { streetActivity: 1, events: [] });
    expect(r.salesCents).toBeGreaterThan(0);
    expect(call('business.valuation', r.business, 2025)).toBeGreaterThan(0);
  });
});
