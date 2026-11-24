import { expect, it } from 'vitest';
import { priceIn } from '../economy';
import { eat, initialNeeds } from '../needs';
import { lodgingOffers, menuOf, placeHours, type MenuKind } from './index';

it('gates era-specific menus and hours', () => {
  expect(menuOf('automat', 1901, 'town')).toEqual([]);
  expect(menuOf('automat', 1992, 'town')).toEqual([]);
  expect(menuOf('drive_in', 1920, 'town')).toEqual([]);
  expect(menuOf('drive_in', 1952, 'town').length).toBeGreaterThan(0);
  expect(placeHours('automat', 1992, 2, 'town')).toEqual([]);
  expect(placeHours('automat', 1952, 2, 'town')).toEqual([{ open: 6, close: 23 }]);
});

it('closes legal drinking venues during Prohibition and gates speakeasies', () => {
  for (let year = 1920; year <= 1933; year++) {
    expect(menuOf('bar', year, 'town')).toEqual([]);
    expect(menuOf('tavern', year, 'town')).toEqual([]);
    expect(menuOf('speakeasy', year, 'town').length).toBeGreaterThan(0);
  }
  expect(menuOf('speakeasy', 1934, 'town')).toEqual([]);
  expect(placeHours('bar', 1952, 2, 'town')).toEqual([{ open: 17, close: 24 }]);
});

it('calibrates 1952 prices and station lodging', () => {
  const menu = menuOf('diner', 1952, 'town');
  expect(menu.find(item => item.id === 'diner_coffee')?.priceCents).toBe(10);
  expect(menu.find(item => item.id === 'blue_plate')?.priceCents).toBe(65);
  expect(menu.find(item => item.id === 'apple_pie')?.priceCents).toBe(20);
  expect(menuOf('drive_in', 1952, 'town').find(item => item.id === 'burger')?.priceCents)
    .toBe(priceIn('burger', 1952));
  expect(lodgingOffers('station_hotel', 1952, 'town')[0]).toMatchObject({ nightlyCents: 250, bathroom: 'shared' });
  expect(lodgingOffers('motel', 1925, 'town')).toEqual([]);
  expect(lodgingOffers('boarding_house', 1952, 'town')[0]).toMatchObject({ billingDays: 7, includedMealsPerDay: 2 });
});

it('generates repeatable specials and every menu item feeds the needs module', () => {
  const kinds: MenuKind[] = ['diner', 'lunch_counter', 'automat', 'drive_in', 'cafeteria', 'bakery', 'bar', 'tavern', 'speakeasy'];
  const state = { ...initialNeeds(), hunger: .8 };
  for (const kind of kinds) {
    const year = kind === 'speakeasy' ? 1925 : 1952;
    const menu = menuOf(kind, year, 'town');
    expect(menu.length).toBeGreaterThan(0);
    expect(menuOf(kind, year, 'town')).toEqual(JSON.parse(JSON.stringify(menu)));
    for (const item of menu) expect(eat(state, item.meal).hunger).toBeLessThan(state.hunger);
  }
  expect(new Set(Array.from({ length: 15 }, (_, i) => menuOf('diner', 1952, String(i)).at(-1)?.id)).size)
    .toBeGreaterThan(1);
});
