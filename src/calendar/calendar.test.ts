import { expect, it } from 'vitest';
import { dateOf, dayNumberOf, daylight, holidaysOn, isMarketDay, isPayday, schoolTerm, toShopDate } from './index';

it('round-trips dates across leap years, centuries and weekdays', () => {
  const start = { year: 1899, month: 12, day: 31 };
  for (const elapsed of [-1, 0, 1, 60, 366, 36584]) {
    const date = dateOf(elapsed, start)!;
    expect(dayNumberOf(date)).toBe(dayNumberOf(start)! + elapsed);
    expect(dateOf(dayNumberOf(date)!)).toEqual(date);
  }
  expect(dayNumberOf({ year: 1900, month: 2, day: 29 })).toBeNull();
  expect(dayNumberOf({ year: 2000, month: 2, day: 29 })).not.toBeNull();
  expect(dateOf(7, start)?.weekday).toBe(dateOf(0, start)?.weekday);
  expect(dateOf(1, start)?.weekday).toBe((dateOf(0, start)!.weekday + 1) % 7);
});

it('finds the third Monday across years and honors holiday eras', () => {
  for (const [year, day] of [[1951, 19], [1952, 18], [1953, 16]]) {
    expect(holidaysOn({ year: year!, month: 2, day: day! }).map(h => h.name)).toEqual(['Founders Day']);
    expect(holidaysOn({ year: year!, month: 2, day: day! - 7 })).toEqual([]);
  }
  expect(holidaysOn({ year: 1965, month: 3, day: 21 })).toEqual([]);
});

it('models the first encounter and switches from weekly to alternating Friday pay', () => {
  const start = { year: 1951, month: 9, day: 14 };
  const date = dateOf(0, start)!;
  expect(date).toMatchObject({ monthName: 'Harvestmonth', weekdayName: 'Friday' });
  expect([0, 7, 14].every(offset => isPayday(dateOf(offset, start)!))).toBe(true);
  expect(holidaysOn(dateOf(1, start)!)).toEqual([
    { name: 'Lantern Festival', closes: ['banks', 'post office'], opens: ['lantern fair'] },
  ]);
  expect(isMarketDay(dateOf(1, start)!)).toBe(true);
  const later = { year: 1985, month: 1, day: 4 };
  expect(isPayday(dateOf(0, later)!)).not.toBe(isPayday(dateOf(7, later)!));
  expect(isPayday(dateOf(0, later)!)).toBe(isPayday(dateOf(14, later)!));
  expect(toShopDate(date)).toEqual({ month: 9, day: 14 });
});

it('provides school terms and longer summer daylight deterministically', () => {
  const summer = { year: 1952, month: 7, day: 1 };
  const winter = { ...summer, month: 1 };
  const span = (date: typeof summer) => {
    const light = daylight(date)!;
    return light.sunset - light.sunrise;
  };
  expect(span(summer)).toBeGreaterThan(span(winter));
  expect(daylight(summer)).toEqual(JSON.parse(JSON.stringify(daylight(summer))));
  expect(schoolTerm(summer)).toBeNull();
  expect(schoolTerm(winter)).toBe('spring');
});
